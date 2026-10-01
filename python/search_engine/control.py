"""Capa de control: lleva la cuenta de qué libros están descargados e indexados
y coordina el pipeline para que pueda reanudarse tras un corte (SPEC §9)."""

from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Callable

from . import metadata, tokenizer
from .datalake import Store
from .fileutil import append_line, atomic_write, decode, load_lines, remove_temp
from .index import Index
from .ingestion import BookUnavailable, split
from .text import trim_space


def read_ids(path: Path) -> list[int]:
    """Lee una lista de IDs (uno por línea) respetando el orden del fichero.
    Las líneas vacías y las que empiezan por "#" se ignoran."""
    ids = []
    for number, line in enumerate(decode(path.read_bytes()).split("\n"), start=1):
        line = trim_space(line)
        if not line or line.startswith("#"):
            continue
        try:
            ids.append(int(line))
        except ValueError:
            raise ValueError(f'{path}:{number}: "{line}" no es un ID de libro') from None
    return ids


class BookList:
    """Un fichero de control: un ID por línea, en orden de llegada y sin repetidos."""

    def __init__(self, path: Path):
        self.path = path
        self.ids: list[int] = []
        self.has: set[int] = set()
        for line in load_lines(path):
            try:
                book_id = int(line)
            except ValueError:
                continue  # línea corrupta: se ignora
            if book_id not in self.has:
                self.ids.append(book_id)
                self.has.add(book_id)

    def add(self, book_id: int) -> None:
        if book_id in self.has:
            return
        append_line(self.path, str(book_id))
        self.ids.append(book_id)
        self.has.add(book_id)

    def replace(self, ids: list[int]) -> None:
        atomic_write(self.path, "".join(f"{book_id}\n" for book_id in ids).encode("ascii"))
        self.ids, self.has = list(ids), set(ids)


@dataclass
class Report:
    """Lo que ha habido que corregir al arrancar."""

    temp_files: int = 0  # .tmp borrados
    unregistered: list[int] = field(default_factory=list)  # en el datalake pero sin marcar como descargados
    missing: list[int] = field(default_factory=list)  # marcados como descargados pero no están en el datalake
    orphan_indexed: list[int] = field(default_factory=list)  # marcados como indexados pero no están en el datalake
    reindex: int = 0  # marcados como indexados pero el índice está vacío

    def clean(self) -> bool:
        return not (self.temp_files or self.unregistered or self.missing or self.orphan_indexed or self.reindex)


class State:
    """Estado del pipeline guardado en output/<lang>/control. Las marcas se
    escriben siempre después de que la operación haya terminado."""

    def __init__(self, directory: Path):
        directory.mkdir(parents=True, exist_ok=True)
        self.dir = directory
        self.downloaded = BookList(directory / "downloaded_books.txt")
        self.indexed = BookList(directory / "indexed_books.txt")
        self.failed = BookList(directory / "failed_books.txt")

    def pending(self) -> list[int]:
        """Descargados pero todavía no indexados, en orden de descarga."""
        return [i for i in self.downloaded.ids if i not in self.indexed.has]

    def known(self, book_id: int) -> bool:
        """Ya descargado o descartado: no hay que volver a pedirlo."""
        return book_id in self.downloaded.has or book_id in self.failed.has

    def clear_indexed(self) -> None:
        """Olvida qué libros están indexados: todos los descargados vuelven a
        quedar pendientes."""
        self.indexed.replace([])

    def reconcile(self, in_datalake: list[int]) -> Report:
        """Alinea los ficheros de control con lo que hay realmente en el
        datalake. Cubre los dos cortes posibles: libro escrito sin registrar y
        registro sin libro."""
        present = set(in_datalake)
        report = Report()
        downloaded = [i for i in self.downloaded.ids if i in present]
        report.missing = [i for i in self.downloaded.ids if i not in present]
        report.unregistered = [i for i in in_datalake if i not in self.downloaded.has]
        downloaded += report.unregistered
        indexed = [i for i in self.indexed.ids if i in present]
        report.orphan_indexed = [i for i in self.indexed.ids if i not in present]

        if report.missing or report.unregistered:
            self.downloaded.replace(downloaded)
        if report.orphan_indexed:
            self.indexed.replace(indexed)
        return report


@dataclass
class Summary:
    """Lo que ha pasado con cada libro de una ejecución."""

    downloaded: int = 0
    discarded: int = 0
    skipped: int = 0  # ya estaban descargados o descartados
    indexed: int = 0


class Pipeline:
    """Coordina la ingesta y la indexación sobre una variante del datalake y una
    del índice. source solo hace falta para descargar; metadata, index y
    stopwords, solo para indexar."""

    def __init__(self, store: Store, state: State, log: Callable[[str], None] | None = None):
        self.store = store
        self.state = state
        self.source = None
        self.metadata: metadata.MetadataDB | None = None
        self.index: Index | None = None
        self.stopwords: frozenset[str] = frozenset()
        self.datamarts: Path | None = None  # carpeta de los datamarts, para limpiar sus .tmp
        self.log = log or (lambda message: None)

    def recover(self) -> Report:
        """Se ejecuta al arrancar para dejar el estado coherente tras una
        interrupción."""
        dirs = [self.store.root, self.state.dir]
        if self.datamarts is not None:
            dirs.append(self.datamarts)
        temps = sum(remove_temp(d) for d in dirs)

        report = self.state.reconcile(self.store.list())
        report.temp_files = temps
        if self.index is None or not self.state.indexed.ids:
            return report

        # El control es único para las tres variantes del índice: si la que se
        # va a usar está vacía, los libros marcados se indexaron en otra.
        if self.metadata.empty() or self.index.empty():
            report.reindex = len(self.state.indexed.ids)
            self.state.clear_indexed()
        return report

    def download(self, ids: list[int], summary: Summary) -> None:
        """Ingiere, en el orden de ids, los libros que todavía no se conocen. Un
        libro no disponible se descarta y se sigue; cualquier otro error detiene
        la ejecución, que se puede reanudar más tarde sin repetir trabajo."""
        for book_id in ids:
            if self.state.known(book_id):
                summary.skipped += 1
                continue
            try:
                self._ingest(book_id)
            except BookUnavailable as e:
                self.state.failed.add(book_id)
                summary.discarded += 1
                self.log(f"Libro {book_id} descartado: {e}")
                continue
            summary.downloaded += 1
            self.log(f"Libro {book_id} guardado en el datalake")

    def _ingest(self, book_id: int) -> None:
        book = split(book_id, self.source.fetch(book_id))
        self.store.save(book.id, book.header, book.body)
        self.state.downloaded.add(book_id)

    def index_pending(self, batch: int, summary: Summary) -> None:
        """Indexa los libros descargados que aún no están indexados, en lotes de
        batch libros (batch <= 0: todos de una vez)."""
        pending = self.state.pending()
        size = batch if batch > 0 else max(len(pending), 1)
        for start in range(0, len(pending), size):
            chunk = pending[start : start + size]
            self._index_batch(chunk)
            summary.indexed += len(chunk)

    def _index_batch(self, ids: list[int]) -> None:
        """Lleva un lote a los dos datamarts y solo después lo marca como
        indexado. Si se corta antes, el lote se repite entero al reanudar; tanto
        los metadatos como el índice admiten la repetición sin duplicar nada."""
        books = []
        postings: dict[str, list[int]] = {}
        output_dir = self.store.root.parent
        for book_id in ids:
            location = self.store.locate(book_id)
            if location is None:
                raise OSError(f"el libro {book_id} está registrado pero no está en el datalake")
            header = decode(location.header.read_bytes())
            body = location.body.read_bytes()
            body_path = location.body.relative_to(output_dir).as_posix()
            books.append(replace(metadata.extract(book_id, header), body_path=body_path))
            for term in tokenizer.terms(body, self.stopwords):
                postings.setdefault(term, []).append(book_id)

        self.metadata.insert(books)
        self.index.add(postings)
        for book_id in ids:
            self.state.indexed.add(book_id)
        self.log(f"{len(ids)} libros indexados ({len(postings)} términos)")

    def rebuild(self) -> None:
        """Vacía los datamarts y deja todos los libros descargados como
        pendientes de indexar."""
        self.index.reset()
        self.metadata.reset()
        self.state.clear_indexed()

    def run(self, ids: list[int], batch: int, summary: Summary) -> None:
        """Ciclo completo: primero indexa lo que quedó pendiente y después
        recorre ids en orden, descargando los libros nuevos e indexándolos por
        lotes."""
        self.index_pending(batch, summary)
        size = batch if batch > 0 else max(len(ids), 1)
        for start in range(0, len(ids), size):
            self.download(ids[start : start + size], summary)
            self.index_pending(batch, summary)
