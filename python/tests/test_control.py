"""Capa de control (§9): orden, descartes, recuperación tras cortes y lotes."""

import pytest

from search_engine.control import Pipeline, State, Summary, read_ids
from search_engine.datalake import new_store
from search_engine.index import new_index
from search_engine.metadata import BookMetadata, MetadataDB


class FakeSource:
    """Sirve libros válidos salvo los IDs marcados como rotos."""

    def __init__(self, broken=()):
        self.broken = set(broken)
        self.fetched = []

    def fetch(self, book_id):
        self.fetched.append(book_id)
        if book_id in self.broken:
            return "sin marcadores"
        return (
            f"Title: Libro {book_id}\n*** START OF THE PROJECT GUTENBERG EBOOK ***\n"
            f"cuerpo del libro {book_id}\n*** END OF THE PROJECT GUTENBERG EBOOK ***\n"
        )


def new_pipeline(kind, out, source):
    pipeline = Pipeline(new_store(kind, out), State(out / "control"))
    pipeline.source = source
    return pipeline


@pytest.fixture
def datamarts():
    """Añade a un pipeline los datamarts de out, con la variante de índice
    indicada, y los cierra al terminar el test."""
    opened = []

    def attach(pipeline, kind, out):
        directory = out / "datamarts"
        pipeline.metadata = MetadataDB(directory / "metadata.db")
        pipeline.index = new_index(kind, directory, "", "test")
        pipeline.datamarts = directory
        pipeline.stopwords = frozenset({"del"})
        opened.append(pipeline)
        return pipeline

    yield attach
    for pipeline in opened:
        pipeline.index.close()
        pipeline.metadata.close()


def test_descarga_en_orden_y_sin_repetir(tmp_path):
    source = FakeSource(broken={84})
    pipeline = new_pipeline("book", tmp_path, source)
    summary = Summary()
    pipeline.download([1342, 84, 11], summary)
    assert summary == Summary(downloaded=2, discarded=1)
    assert (tmp_path / "control" / "downloaded_books.txt").read_bytes() == b"1342\n11\n"
    assert (tmp_path / "control" / "failed_books.txt").read_bytes() == b"84\n"
    assert pipeline.store.locate(84) is None

    # Una segunda ejecución solo pide los libros nuevos.
    source.fetched = []
    pipeline = new_pipeline("book", tmp_path, source)
    summary = Summary()
    pipeline.download([1342, 84, 11, 1661], summary)
    assert summary == Summary(downloaded=1, skipped=3)
    assert source.fetched == [1661]
    assert pipeline.state.pending() == [1342, 11, 1661]


@pytest.mark.parametrize("kind", ["time", "book", "range"])
def test_reanudar_tras_un_corte(tmp_path, kind):
    """Simula los cortes posibles y comprueba que al reanudar no hay ni
    duplicados ni pérdidas."""
    source = FakeSource()
    pipeline = new_pipeline(kind, tmp_path, source)
    pipeline.download([11, 84], Summary())
    # Corte 1: el libro 1342 llegó al datalake pero no al control.
    location = pipeline.store.save(1342, "h", "b")
    # Corte 2: el libro 1661 se quedó a medio escribir.
    (location.body.parent / "1661.body.txt.tmp").write_text("a medias")
    # Corte 3: el 84 está registrado pero su body ha desaparecido.
    pipeline.store.locate(84).body.unlink()

    source.fetched = []
    pipeline = new_pipeline(kind, tmp_path, source)
    report = pipeline.recover()
    assert (report.temp_files, report.unregistered, report.missing) == (1, [1342], [84])
    pipeline.download([11, 84, 1342, 1661], Summary())
    assert source.fetched == [84, 1661]
    assert sorted(pipeline.state.downloaded.ids) == [11, 84, 1342, 1661]
    assert pipeline.store.list() == [11, 84, 1342, 1661]


def test_linea_de_control_cortada(tmp_path):
    """Un append cortado a mitad no puede contaminar el siguiente ID."""
    path = tmp_path / "downloaded_books.txt"
    path.write_bytes(b"11\n84\n13")
    state = State(tmp_path)
    assert state.downloaded.ids == [11, 84]
    state.downloaded.add(1342)
    assert path.read_bytes() == b"11\n84\n1342\n"


def test_read_ids(tmp_path):
    path = tmp_path / "book_ids.txt"
    path.write_bytes(b"# dataset\n11\r\n84\n\n1342")
    assert read_ids(path) == [11, 84, 1342]
    # Leer la lista compartida nunca la modifica.
    assert path.read_bytes() == b"# dataset\n11\r\n84\n\n1342"


@pytest.mark.parametrize("kind", ["json", "folders"])
def test_ciclo_completo(tmp_path, datamarts, kind):
    source = FakeSource(broken={84})
    pipeline = datamarts(new_pipeline("book", tmp_path, source), kind, tmp_path)
    summary = Summary()
    pipeline.run([1342, 84, 11, 1661], 2, summary)
    assert summary == Summary(downloaded=3, discarded=1, indexed=3)
    assert (tmp_path / "control" / "indexed_books.txt").read_bytes() == b"1342\n11\n1661\n"
    assert pipeline.state.pending() == []
    assert pipeline.index.lookup("cuerpo") == [11, 1342, 1661]
    assert pipeline.index.lookup("del") == []
    books = pipeline.metadata.all()
    assert len(books) == 3
    assert books[0] == BookMetadata(11, "Libro 11", None, None, "datalake_book/11/body.txt")

    # Repetir la ejecución no hace nada.
    summary = Summary()
    pipeline.run([1342, 84, 11, 1661], 2, summary)
    assert summary == Summary(skipped=4)


def test_reanudar_indexacion(tmp_path, datamarts):
    """Corte entre escribir el índice y marcar el libro como indexado: al
    reanudar se indexa primero lo pendiente y el libro no queda duplicado."""
    source = FakeSource()
    new_pipeline("book", tmp_path, source).download([11, 84], Summary())
    pipeline = datamarts(new_pipeline("book", tmp_path, source), "json", tmp_path)
    pipeline.index.add({"cuerpo": [11]})
    pipeline.metadata.insert([BookMetadata(11, "Libro 11", None, None, "x")])
    steps = []
    pipeline.log = steps.append

    pipeline.recover()
    summary = Summary()
    pipeline.run([11, 84, 1342], 10, summary)
    assert summary == Summary(downloaded=1, skipped=2, indexed=3)
    assert steps == [
        "2 libros indexados (2 términos)",
        "Libro 1342 guardado en el datalake",
        "1 libros indexados (2 términos)",
    ]
    assert pipeline.index.lookup("cuerpo") == [11, 84, 1342]
    assert len(pipeline.metadata.all()) == 3


def test_cambio_de_indice_y_rebuild(tmp_path, datamarts):
    """El control es único: al cambiar a una variante de índice vacía, o al
    pedir una reconstrucción, los libros ya descargados se vuelven a indexar."""
    source = FakeSource()
    pipeline = datamarts(new_pipeline("book", tmp_path, source), "json", tmp_path)
    pipeline.run([11, 84], 0, Summary())

    pipeline = datamarts(new_pipeline("book", tmp_path, source), "folders", tmp_path)
    assert pipeline.recover().reindex == 2
    summary = Summary()
    pipeline.index_pending(0, summary)
    assert summary.indexed == 2
    assert pipeline.index.lookup("cuerpo") == [11, 84]

    pipeline.rebuild()
    assert pipeline.index.lookup("cuerpo") == []
    summary = Summary()
    pipeline.index_pending(1, summary)
    assert summary.indexed == 2
    assert pipeline.index.lookup("cuerpo") == [11, 84]


def test_lote_vacio_con_batch_cero(tmp_path, datamarts):
    pipeline = datamarts(new_pipeline("book", tmp_path, FakeSource()), "json", tmp_path)
    summary = Summary()
    pipeline.run([], 0, summary)
    assert summary == Summary()
