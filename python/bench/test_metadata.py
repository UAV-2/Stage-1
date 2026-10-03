"""Micro-benchmarks del datamart de metadatos en SQLite (SPEC §11)."""

import itertools

import pytest

from bench.benchkit import SAMPLES, iterations, remove, sample, sizes
from search_engine.metadata import Filter, MetadataDB

CASES = [pytest.param(n, id=f"sqlite-{n}") for n in sizes()]


def metadata_db(env, fixture, n):
    """Base con los metadatos de los n primeros libros, ya abierta."""

    def build(out):
        db = MetadataDB(out / "metadata.db")
        fixture.closers.append(db.close)
        db.insert(env.records(env.source(), env.books(n)))
        return db

    return fixture.get(("metadata", n), build)


@pytest.mark.parametrize("n", CASES)
def test_metadata_insert_time(benchmark, record, env, n):
    """Insertar las N filas en una sola transacción; extraer los metadatos y
    crear la base vacía va fuera de la medida."""
    record("metadata", "sqlite", n, "metadata_insert_time", "ms", "ms")
    records = env.records(env.source(), env.books(n))
    directory = env.work / "insert"
    state = {}

    def setup():
        if "db" in state:
            state.pop("db").close()
        remove(directory)
        state["db"] = MetadataDB(directory / "metadata.db")

    warmup, rounds = iterations()
    benchmark.pedantic(lambda: state["db"].insert(records), setup=setup, rounds=rounds, warmup_rounds=warmup)
    state.pop("db").close()
    remove(directory)


@pytest.mark.parametrize("n", CASES)
def test_metadata_query_time_author(benchmark, record, env, fixture, n):
    """Todos los libros de un autor, sobre 200 autores al azar."""
    record("metadata", "sqlite", n, "metadata_query_time_author", "us", "us")
    db = metadata_db(env, fixture, n)
    authors = [book.author for book in env.records(env.source(), env.books(n)) if book.author]
    targets = itertools.cycle(sample(authors))

    def query():
        if not db.find(Filter(author=next(targets))):
            raise AssertionError("consulta por autor sin resultados")

    warmup, rounds = iterations()
    benchmark.pedantic(query, rounds=rounds, iterations=SAMPLES, warmup_rounds=warmup)


@pytest.mark.parametrize("n", CASES)
def test_metadata_query_time_id(benchmark, record, env, fixture, n):
    """La fila de un libro con su body_path, sobre 200 IDs al azar."""
    record("metadata", "sqlite", n, "metadata_query_time_id", "us", "us")
    db = metadata_db(env, fixture, n)
    targets = itertools.cycle(sample(env.books(n)))

    def query():
        if db.by_id(next(targets)) is None:
            raise AssertionError("libro no encontrado")

    warmup, rounds = iterations()
    benchmark.pedantic(query, rounds=rounds, iterations=SAMPLES, warmup_rounds=warmup)
