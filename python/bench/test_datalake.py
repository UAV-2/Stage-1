"""Micro-benchmarks del datalake (SPEC §11)."""

import itertools

import pytest

from bench.benchkit import EXTRA, SAMPLES, iterations, remove, sample, sizes
from search_engine.datalake import new_store

KINDS = ["time", "book", "range"]
CASES = [pytest.param(kind, n, id=f"{kind}-{n}") for kind in KINDS for n in sizes()]


def lake(env, fixture, kind, n):
    """Datalake de la variante kind con los n primeros libros, recién abierto."""
    return fixture.get(("lake", kind, n), lambda out: (env.ingest(kind, out, env.books(n)), new_store(kind, out))[1])


@pytest.mark.parametrize("kind, n", CASES)
def test_write_throughput(benchmark, record, env, kind, n):
    """Leer de cache/, separar y escribir N libros (con su línea de control) en
    un datalake vacío. Valor = N / segundos."""
    record("datalake", kind, n, "write_throughput", "books/s", "throughput")
    ids = env.books(n)
    out = env.work / "write"
    warmup, rounds = iterations()
    benchmark.pedantic(lambda: env.ingest(kind, out, ids), setup=lambda: remove(out),
                       rounds=rounds, warmup_rounds=warmup)
    remove(out)


@pytest.mark.parametrize("kind, n", CASES)
def test_lookup_time(benchmark, record, env, fixture, kind, n):
    """Localizar header y body de un libro; 200 IDs al azar por ronda."""
    record("datalake", kind, n, "lookup_time", "us", "us")
    store = lake(env, fixture, kind, n)
    targets = itertools.cycle(sample(env.books(n)))

    def lookup():
        if store.locate(next(targets)) is None:
            raise AssertionError("libro no encontrado")

    warmup, rounds = iterations()
    benchmark.pedantic(lookup, rounds=rounds, iterations=SAMPLES, warmup_rounds=warmup)


@pytest.mark.parametrize("kind, n", CASES)
def test_incremental_detect_time(benchmark, record, env, fixture, kind, n):
    """Abrir de cero un datalake de N+50 libros (en time, leer _locations.tsv),
    listar sus libros y quedarse con los que no están entre los N indexados."""
    record("datalake", kind, n, "incremental_detect_time", "ms", "ms")
    ids = env.books(n + EXTRA)
    out = lake(env, fixture, kind, n + EXTRA).root.parent
    indexed = set(ids[:n])

    def detect():
        added = [i for i in new_store(kind, out).list() if i not in indexed]
        if len(added) != EXTRA:
            raise AssertionError(f"se han detectado {len(added)} libros nuevos")

    warmup, rounds = iterations()
    benchmark.pedantic(detect, rounds=rounds, iterations=10, warmup_rounds=warmup)
