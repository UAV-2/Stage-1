"""Preparación común a los micro-benchmarks y a las métricas de script, para
que ambos midan exactamente lo mismo (SPEC §11).

La configuración va en variables de entorno, como en Go: STAGE1_ROOT (raíz del
repositorio, por defecto ..), STAGE1_SIZES (por defecto 100,250,500,1000),
STAGE1_MONGO, STAGE1_WARMUP (5), STAGE1_ROUNDS (10) y
STAGE1_REDUCE_FOLDERS_FROM (0 = nunca; con N >= ese valor, la construcción y
la actualización del índice folders usan 1 + 3 iteraciones).
"""

import ctypes
import os
import random
import shutil
import sys
from pathlib import Path

from search_engine import metadata, tokenizer
from search_engine.control import Pipeline, State, Summary, read_ids
from search_engine.datalake import Store, new_store
from search_engine.fileutil import decode
from search_engine.index import Index, new_index
from search_engine.ingestion import CacheSource
from search_engine.text import trim_space

# Libros que se añaden en las medidas de actualización y de detección.
EXTRA = 50
# IDs (o autores) aleatorios de las medidas de lookup y de metadatos.
SAMPLES = 200
SEED = 42
# Colección de MongoDB propia, para no tocar la del pipeline.
BENCH_LANG = "python_bench"
REDUCED = (1, 3)


def _env(name: str, default: str) -> str:
    return os.environ.get(name) or default


def sizes() -> list[int]:
    return [int(s) for s in _env("STAGE1_SIZES", "100,250,500,1000").split(",")]


def iterations(kind: str | None = None, n: int = 0, heavy: bool = False) -> tuple[int, int]:
    """(calentamiento, medidas) de un micro-benchmark."""
    reduce_from = int(_env("STAGE1_REDUCE_FOLDERS_FROM", "0"))
    if heavy and kind == "folders" and reduce_from and n >= reduce_from:
        return REDUCED
    return int(_env("STAGE1_WARMUP", "5")), int(_env("STAGE1_ROUNDS", "10"))


def sample(items: list) -> list:
    """SAMPLES elementos al azar, con repetición, con la semilla acordada."""
    rng = random.Random(SEED)
    return [items[rng.randrange(len(items))] for _ in range(SAMPLES)]


class Env:
    """Configuración de una ejecución y datos que comparten las medidas."""

    def __init__(self):
        self.root = Path(_env("STAGE1_ROOT", "..")).resolve()
        # En disco, no en /tmp, que en muchas distribuciones está en RAM.
        self.work = self.root / "output" / "python_bench"
        self.sizes = sizes()
        self.mongo = _env("STAGE1_MONGO", "mongodb://localhost:27017")
        shared = self.root / "shared"
        self.ids = read_ids(shared / "book_ids.txt")
        self.stop = tokenizer.load_stopwords(shared / "stopwords.txt")
        lines = decode((shared / "queries.txt").read_bytes()).split("\n")
        self.queries = [q for q in map(trim_space, lines) if q]
        self._source: Store | None = None

    def max_books(self) -> int:
        """Libros que hacen falta en cache/: el mayor N más los añadidos."""
        return max(self.sizes) + EXTRA

    def books(self, n: int) -> list[int]:
        """Los n primeros IDs del dataset, comprobando que están en cache/."""
        if n > len(self.ids):
            raise RuntimeError(f"hacen falta {n} libros y shared/book_ids.txt solo tiene {len(self.ids)}")
        for book_id in self.ids[:n]:
            if not (self.root / "cache" / f"{book_id}.txt").exists():
                raise RuntimeError(f"el libro {book_id} no está en cache/ (python main.py download --n={n})")
        return self.ids[:n]

    def ingest(self, kind: str, out: Path, ids: list[int]) -> Pipeline:
        """Guarda ids en el datalake kind de out leyendo de cache/, como el
        pipeline con --offline (incluida la línea de control)."""
        pipeline = Pipeline(new_store(kind, out), State(out / "control"))
        pipeline.source = CacheSource(self.root / "cache")
        summary = Summary()
        pipeline.download(ids, summary)
        if summary.discarded:
            raise RuntimeError(f"{summary.discarded} libros del dataset no tienen marcadores")
        return pipeline

    def source(self) -> Store:
        """Datalake book con todos los libros del experimento. De él salen los
        bodies y las cabeceras de las medidas del índice y de los metadatos."""
        if self._source is None:
            ids = self.books(self.max_books())
            self._source = self.ingest("book", self.work / "source", ids).store
        return self._source

    def postings(self, store: Store, ids: list[int]) -> dict[str, list[int]]:
        """Leer y tokenizar los bodies: la parte común de la indexación."""
        postings: dict[str, list[int]] = {}
        for book_id in ids:
            location = store.locate(book_id)
            if location is None:
                raise RuntimeError(f"el libro {book_id} no está en el datalake")
            for term in tokenizer.terms(location.body.read_bytes(), self.stop):
                postings.setdefault(term, []).append(book_id)
        return postings

    def records(self, store: Store, ids: list[int]) -> list[metadata.BookMetadata]:
        books = []
        for book_id in ids:
            location = store.locate(book_id)
            book = metadata.extract(book_id, decode(location.header.read_bytes()))
            body_path = location.body.relative_to(store.root.parent).as_posix()
            books.append(metadata.BookMetadata(book.id, book.title, book.author, book.language, body_path))
        return books

    def open_index(self, kind: str, datamarts: Path) -> Index:
        return new_index(kind, datamarts, self.mongo, BENCH_LANG)


def undo_folders(root: Path, base: dict[str, list[int]], added: dict[str, list[int]]) -> None:
    """Deja el índice por carpetas como estaba antes de añadir `added`: cada
    término afectado recupera sus postings de `base` o desaparece. Es el setup
    de update_time; reconstruir el índice entero tarda minutos en folders."""
    for term in added:
        path = root / term[0].upper() / f"{term}.txt"
        ids = base.get(term)
        if ids is None:
            path.unlink(missing_ok=True)
        else:
            path.write_bytes("".join(f"{i}\n" for i in ids).encode("ascii"))


def peak_memory() -> tuple[int, str]:
    """Pico de memoria residente del proceso en bytes y de dónde sale. En Linux,
    VmHWM; en Windows, el pico del working set, que es su equivalente."""
    try:
        for line in Path("/proc/self/status").read_text().split("\n"):
            if line.startswith("VmHWM:"):
                return int(line.split()[1]) * 1024, "VmHWM"
    except OSError:
        pass
    if sys.platform == "win32":
        class Counters(ctypes.Structure):
            _fields_ = [("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong)] + [
                (name, ctypes.c_size_t) for name in (
                    "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
                    "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]

        kernel32 = ctypes.windll.kernel32
        kernel32.GetCurrentProcess.restype = ctypes.c_void_p
        kernel32.K32GetProcessMemoryInfo.argtypes = [ctypes.c_void_p, ctypes.POINTER(Counters), ctypes.c_ulong]
        counters = Counters()
        counters.cb = ctypes.sizeof(counters)
        if not kernel32.K32GetProcessMemoryInfo(kernel32.GetCurrentProcess(), ctypes.byref(counters), counters.cb):
            raise OSError("GetProcessMemoryInfo ha fallado")
        return counters.PeakWorkingSetSize, "PeakWorkingSetSize"
    import resource

    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024, "ru_maxrss"


def remove(path: Path) -> None:
    shutil.rmtree(path, ignore_errors=True)
