"""Micro-benchmarks del índice invertido (SPEC §11)."""

import itertools

import pytest

from bench.benchkit import EXTRA, iterations, sizes, undo_folders
from search_engine.index import search

KINDS = ["json", "folders", "mongo"]
CASES = [pytest.param(kind, n, id=f"{kind}-{n}") for kind in KINDS for n in sizes()]
# Consultas por ronda: se van alternando las de shared/queries.txt.
QUERIES_PER_ROUND = 200


def open_index(env, kind, datamarts):
    """Si MongoDB no responde, las medidas de mongo se saltan."""
    try:
        return env.open_index(kind, datamarts)
    except OSError as e:
        pytest.skip(str(e))


@pytest.mark.parametrize("kind, n", CASES)
def test_index_build_time(benchmark, record, env, kind, n):
    """Leer y tokenizar los N bodies y construir el índice en un solo lote; el
    índice se vacía fuera de la medida."""
    record("index", kind, n, "index_build_time", "ms", "ms")
    store, ids = env.source(), env.books(n)
    index = open_index(env, kind, env.work / "build")
    warmup, rounds = iterations(kind, n, heavy=True)
    benchmark.pedantic(lambda: index.add(env.postings(store, ids)), setup=index.reset,
                       rounds=rounds, warmup_rounds=warmup)
    index.reset()
    index.close()


@pytest.mark.parametrize("kind, n", CASES)
def test_query_time(benchmark, record, env, fixture, kind, n):
    """Una consulta de shared/queries.txt sobre un índice de N libros ya
    abierto y cargado."""
    record("index", kind, n, "query_time", "us", "us")

    def build(out):
        index = open_index(env, kind, out)
        fixture.closers.append(lambda: (index.reset(), index.close()))
        index.reset()
        index.add(env.postings(env.source(), env.books(n)))
        return index

    index = fixture.get(("index", kind, n), build)
    queries = itertools.cycle(env.queries)
    warmup, rounds = iterations()
    benchmark.pedantic(lambda: search(index, next(queries), env.stop),
                       rounds=rounds, iterations=QUERIES_PER_ROUND, warmup_rounds=warmup)


@pytest.mark.parametrize("kind, n", CASES)
def test_update_time(benchmark, record, env, fixture, kind, n):
    """Abrir el índice de cero (en JSON, cargar el fichero), leer y tokenizar
    los libros N+1 … N+50 y añadirlos. Fuera de la medida, el índice vuelve a
    tener los N primeros."""
    record("index", kind, n, "update_time", "ms", "ms")
    store = env.source()
    datamarts = env.work / "update"
    open_index(env, kind, datamarts).close()  # si no hay MongoDB, se salta antes de preparar nada
    base = fixture.get(("postings", n), lambda _: env.postings(store, env.books(n)))
    added_ids = env.books(n + EXTRA)[n:]
    state = {"index": None, "added": None}

    def setup():
        if state["index"] is not None:
            state["index"].close()
        index = open_index(env, kind, datamarts)
        if kind == "folders" and state["added"] is not None:
            undo_folders(index.root, base, state["added"])
        else:
            index.reset()
            index.add(base)
        index.close()

    def update():
        index = state["index"] = open_index(env, kind, datamarts)
        postings = state["added"] = env.postings(store, added_ids)
        index.add(postings)

    warmup, rounds = iterations(kind, n, heavy=True)
    benchmark.pedantic(update, setup=setup, rounds=rounds, warmup_rounds=warmup)
    state["index"].reset()
    state["index"].close()
