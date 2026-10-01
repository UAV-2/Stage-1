"""Datalake (§5), metadatos en SQLite (§6) e índice invertido (§8)."""

import os
import sqlite3
from datetime import datetime, timedelta
from pathlib import Path

import pytest

from search_engine.datalake import TimeStore, new_store
from search_engine.index import dump_tsv, new_index, search
from search_engine.metadata import BookMetadata, Filter, MetadataDB

LAKES = ["time", "book", "range"]


def count_files(root: Path) -> int:
    return sum(len(files) for _, _, files in os.walk(root))


@pytest.mark.parametrize(
    "kind, book_id, header, body",
    [
        ("time", 1342, "datalake_time/20260930/07/1342.header.txt", "datalake_time/20260930/07/1342.body.txt"),
        ("book", 1342, "datalake_book/1342/header.txt", "datalake_book/1342/body.txt"),
        ("range", 1342, "datalake_range/1000-1999/1342.header.txt", "datalake_range/1000-1999/1342.body.txt"),
        ("range", 11, "datalake_range/0-999/11.header.txt", "datalake_range/0-999/11.body.txt"),
        ("range", 1000, "datalake_range/1000-1999/1000.header.txt", "datalake_range/1000-1999/1000.body.txt"),
    ],
)
def test_rutas(tmp_path, kind, book_id, header, body):
    store = new_store(kind, tmp_path)
    if isinstance(store, TimeStore):
        store.now = lambda: datetime(2026, 9, 30, 7, 5)
    location = store.save(book_id, "cabecera", "cuerpo")
    assert location == (tmp_path / header, tmp_path / body)
    assert location.header.read_text() == "cabecera"
    assert location.body.read_text() == "cuerpo"


@pytest.mark.parametrize("kind", LAKES)
def test_localizar_y_listar(tmp_path, kind):
    store = new_store(kind, tmp_path)
    for book_id in (1342, 11, 84):
        store.save(book_id, "h", "b")
    # Se reabre para comprobar que no depende del estado en memoria.
    store = new_store(kind, tmp_path)
    assert store.locate(84) is not None
    assert store.locate(999) is None
    assert store.list() == [11, 84, 1342]


@pytest.mark.parametrize("kind", LAKES)
def test_libro_incompleto_no_cuenta(tmp_path, kind):
    store = new_store(kind, tmp_path)
    store.save(84, "h", "b").body.unlink()
    assert store.locate(84) is None
    assert store.list() == []


@pytest.mark.parametrize("kind", LAKES)
def test_guardar_dos_veces_no_duplica(tmp_path, kind):
    """Ni siquiera en time cuando la segunda vez cae en otra hora."""
    first = new_store(kind, tmp_path).save(84, "h", "viejo")
    before = count_files(tmp_path)
    store = new_store(kind, tmp_path)
    if isinstance(store, TimeStore):
        store.now = lambda: datetime.now() + timedelta(hours=3)
    second = store.save(84, "h", "nuevo")
    assert first == second
    assert count_files(tmp_path) == before
    assert second.body.read_text() == "nuevo"


def test_datalake_desconocido(tmp_path):
    with pytest.raises(ValueError):
        new_store("batch", tmp_path)


def test_metadatos(tmp_path):
    path = tmp_path / "datamarts" / "metadata.db"
    books = [
        BookMetadata(1342, "Pride and Prejudice", "Jane Austen", "en", "datalake_book/1342/body.txt"),
        BookMetadata(11, "Alice", None, None, "datalake_book/11/body.txt"),
    ]
    db = MetadataDB(path)
    assert db.empty()
    db.insert(books)
    # Repetir la inserción (reindexar tras un corte) no duplica filas.
    db.insert(books[:1])
    db.close()

    db = MetadataDB(path)
    assert db.all() == [books[1], books[0]]
    assert db.by_id(1342) == books[0]
    assert db.by_id(5) is None
    assert db.find(Filter(author="Jane Austen")) == [books[0]]
    assert db.find(Filter(author="Jane Austen", language="es")) == []
    assert db.find(Filter()) == [books[1], books[0]]
    # Los campos ausentes se guardan como NULL, no como cadena vacía.
    with sqlite3.connect(path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM books WHERE author IS NULL AND language IS NULL").fetchone()[0] == 1

    dump = tmp_path / "metadata.tsv"
    db.dump_tsv(dump)
    assert dump.read_bytes() == b"11\tAlice\t\t\n1342\tPride and Prejudice\tJane Austen\ten\n"
    db.reset()
    assert db.empty()
    db.close()


def open_index(kind: str, directory: Path):
    """La variante de MongoDB solo se prueba si MONGO_URI apunta a un servidor,
    y usa una colección propia."""
    uri = os.environ.get("MONGO_URI", "")
    if kind == "mongo" and not uri:
        pytest.skip("MONGO_URI no está definida")
    return new_index(kind, directory, uri, "test")


@pytest.mark.parametrize("kind", ["json", "folders", "mongo"])
def test_contenido_comun(tmp_path, kind):
    index = open_index(kind, tmp_path)
    index.reset()
    assert index.empty()
    # Dos lotes con IDs desordenados y un libro repetido.
    index.add({"island": [1342, 5], "adventure": [42, 5]})
    index.add({"adventure": [12, 5], "whale": [2701]})
    index.close()

    # Se reabre para comprobar que todo está en disco.
    index = open_index(kind, tmp_path)
    assert index.lookup("adventure") == [5, 12, 42]
    assert index.lookup("island") == [5, 1342]
    assert index.lookup("whale") == [2701]
    assert index.lookup("missing") == []

    stop = frozenset({"the"})
    assert search(index, "Adventure", stop) == [5, 12, 42]
    assert search(index, "the island ADVENTURE", stop) == [5]
    assert search(index, "island island", stop) == [5, 1342]
    assert search(index, "whale island", stop) == []
    assert search(index, "island missing", stop) == []
    assert search(index, "the", stop) == []
    assert index.disk_usage() > 0

    dump = tmp_path / "index.tsv"
    dump_tsv(index, dump)
    # Las tres variantes dan el mismo dump canónico.
    assert dump.read_bytes() == b"adventure\t5,12,42\nisland\t5,1342\nwhale\t2701\n"
    index.reset()
    assert index.empty()
    index.close()


def test_formato_json(tmp_path):
    index = open_index("json", tmp_path)
    index.add({"island": [1342, 5], "adventure": [42, 12, 5]})
    assert (tmp_path / "inverted_index.json").read_bytes() == b'{"adventure":[5,12,42],"island":[5,1342]}'


def test_formato_carpetas(tmp_path):
    index = open_index("folders", tmp_path)
    index.add({"island": [1342, 5], "adventure": [42, 12, 5], "abc": [1], "abcd": [2]})
    assert (tmp_path / "inverted_index" / "A" / "adventure.txt").read_bytes() == b"5\n12\n42\n"
    assert (tmp_path / "inverted_index" / "I" / "island.txt").read_bytes() == b"5\n1342\n"
    assert [term for term, _ in index.each()] == ["abc", "abcd", "adventure", "island"]


def test_indice_desconocido(tmp_path):
    with pytest.raises(ValueError):
        new_index("sqlite", tmp_path, "", "test")
