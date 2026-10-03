"""Métricas que no encajan en un micro-benchmark (SPEC §11): necesitan
procesos nuevos, matar procesos o la red. Cada función devuelve filas
(component, structure, n, metric, values, unit) para el CSV.

Los procesos hijos son este mismo módulo:
    python -m bench.scripts ingest <kind> <out> <n>
    python -m bench.scripts index <kind> <out> <n>
"""

import random
import subprocess
import sys
import time
from pathlib import Path

from bench.benchkit import REDUCED, SEED, Env, peak_memory, remove
from search_engine.control import Pipeline, State, Summary
from search_engine.datalake import new_store
from search_engine.fileutil import tree_stats
from search_engine.ingestion import GutenbergSource

LAKES = ["time", "book", "range"]
INDEXES = ["json", "folders", "mongo"]


def child(mode: str, kind: str, out: Path, n: int) -> list[str]:
    return [sys.executable, "-m", "bench.scripts", mode, kind, str(out), str(n)]


def storage(env: Env, log) -> list[tuple]:
    """Ficheros y carpetas de cada datalake con N libros, contando el control y
    el índice auxiliar de time. Es determinista: se mide una vez."""
    rows = []
    for kind in LAKES:
        for n in env.sizes:
            out = env.work / "storage"
            remove(out)
            env.ingest(kind, out, env.books(n))
            files, dirs, size = tree_stats(out)
            remove(out)
            log(f"storage {kind} n={n}: {files} ficheros, {dirs} carpetas, {size} bytes")
            rows += [("datalake", kind, n, "files_count", [files], "count"),
                     ("datalake", kind, n, "dirs_count", [dirs], "count")]
    return rows


def verify_ingest(kind: str, out: Path, ids: list[int]) -> str:
    """Cada ID una sola vez en downloaded_books.txt, todos con sus dos ficheros,
    ningún .tmp y ningún fichero de más. Devuelve el problema o ""."""
    registered = sorted(int(line) for line in (out / "control" / "downloaded_books.txt").read_text().split())
    if registered != ids:
        return f"downloaded_books.txt tiene {len(registered)} líneas para {len(ids)} libros"
    store = new_store(kind, out)
    if store.list() != ids:
        return f"el datalake tiene {len(store.list())} libros completos de {len(ids)}"
    files = tree_stats(store.root)[0]
    want = 2 * len(ids) + (1 if kind == "time" else 0)  # _locations.tsv
    return "" if files == want else f"{files} ficheros en el datalake, se esperaban {want}"


def recovery(env: Env, reps: int, log) -> list[tuple]:
    """Se lanza la ingesta en un proceso hijo, se mata 3 veces en momentos al
    azar entre el 10 % y el 60 % de una ingesta completa, se reanuda hasta el
    final y se comprueba. Valor = fracción de repeticiones correctas."""
    rng = random.Random(SEED)
    rows = []
    for kind in LAKES:
        for n in env.sizes:
            ids = sorted(env.books(n))
            out = env.work / "recovery"
            remove(out)
            start = time.perf_counter()
            subprocess.run(child("ingest", kind, out, n), check=True)
            full = time.perf_counter() - start

            values = []
            for rep in range(reps):
                remove(out)
                kills = 0
                for _ in range(3):
                    process = subprocess.Popen(child("ingest", kind, out, n))
                    time.sleep((0.1 + 0.5 * rng.random()) * full)
                    if process.poll() is None:
                        process.kill()
                        kills += 1
                    process.wait()
                subprocess.run(child("ingest", kind, out, n), check=True)
                problem = verify_ingest(kind, out, ids)
                log(f"recovery {kind} n={n} rep={rep + 1}: {kills} cortes, "
                    f"{'ERROR: ' + problem if problem else 'sin duplicados ni pérdidas'}")
                values.append(0 if problem else 1)
            remove(out)
            rows.append(("datalake", kind, n, "recovery_ok", values, "bool"))
    return rows


def index_footprint(env: Env, reps: int, reduce_from: int, log) -> list[tuple]:
    """Para cada índice y cada N, un proceso nuevo lo construye en un lote y
    devuelve su pico de memoria residente y lo que ocupa el índice en disco."""
    env.source()
    rows = []
    for kind in INDEXES:
        if kind == "mongo":
            try:
                env.open_index(kind, env.work).close()
            except OSError:
                log("MongoDB no responde: se omiten las medidas de mongo")
                continue
        for n in env.sizes:
            repeat = REDUCED[1] if kind == "folders" and reduce_from and n >= reduce_from else reps
            memory, disk = [], []
            for rep in range(repeat):
                output = subprocess.run(child("index", kind, env.work / "footprint", n),
                                        check=True, capture_output=True, text=True).stdout
                peak, size, source = output.split()
                memory.append(int(peak) / (1 << 20))
                disk.append(int(size))
                log(f"footprint {kind} n={n} rep={rep + 1}: {int(peak) / (1 << 20):.1f} MB de pico ({source}), "
                    f"{size} bytes en disco")
            rows += [("index", kind, n, "peak_memory", memory, "MB"),
                     ("index", kind, n, "disk_usage", disk, "bytes")]
    return rows


def download_throughput(env: Env, reps: int, books: int, log) -> list[tuple]:
    """Libros por segundo descargando de Gutenberg al datalake book, con 1 s
    entre peticiones."""
    ids = env.ids[:books]
    values = []
    for rep in range(reps):
        out = env.work / "download"
        remove(out)
        pipeline = Pipeline(new_store("book", out), State(out / "control"))
        pipeline.source = GutenbergSource(out / "cache", 1.0)
        summary = Summary()
        start = time.perf_counter()
        pipeline.download(ids, summary)
        rate = summary.downloaded / (time.perf_counter() - start)
        log(f"download book rep={rep + 1}: {summary.downloaded} libros, {rate:.3f} libros/s")
        values.append(rate)
        remove(out)
    return [("datalake", "book", books, "download_throughput", values, "books/s")]


def run_child(mode: str, kind: str, out: Path, n: int) -> None:
    env = Env()
    ids = env.books(n)
    if mode == "ingest":
        env.ingest(kind, out, ids)
        return
    store = env.source()
    index = env.open_index(kind, out)
    index.reset()
    index.add(env.postings(store, ids))
    size = index.disk_usage()
    peak, source = peak_memory()
    print(peak, size, source)
    index.reset()
    index.close()


if __name__ == "__main__":
    mode, kind, out, n = sys.argv[1:]
    run_child(mode, kind, Path(out), int(n))
