"""Interfaz de línea de comandos común a los tres lenguajes (SPEC §17)."""

import argparse
import re
import sys
from pathlib import Path

from . import tokenizer
from .control import Pipeline, Report, State, Summary, read_ids
from .datalake import new_store
from .index import dump_tsv, new_index, search
from .ingestion import CacheSource, GutenbergSource
from .metadata import Filter, MetadataDB
from .text import format_ids, trim_space

LANG = "python"

USAGE = """Uso: python main.py <comando> [argumentos...] [opciones]

Comandos:
  pipeline [ids...]   ciclo completo: indexa lo pendiente, descarga los libros nuevos y los indexa
  download [ids...]   guarda en el datalake los libros que falten (sin IDs: los de la lista)
  index               indexa los libros descargados que aún no están indexados
  query [texto...]    busca en el índice (sin texto: las consultas de shared/queries.txt)
  books [ids...]      consulta los metadatos por ID o con --author, --title y --language
  dump                exporta metadata.tsv e index.tsv a output/python/dumps/<índice>/
  lookup <ids...>     localiza header y body de cada libro
  status              resumen del estado del pipeline
  recover             alinea los ficheros de control con el datalake

Opciones:
  --datalake=time|book|range   estructura del datalake (por defecto book)
  --index=json|folders|mongo   estructura del índice invertido (por defecto json)
  --offline                    leer los libros de cache/ en vez de descargarlos
  --sample                     usar el dataset de muestra: sample_data/ y book_ids_sample.txt, sin red
  --n=N                        usar solo los primeros N IDs de la lista (0 = todos)
  --batch=N                    libros por lote de indexación (por defecto 100; 0 = todos)
  --rebuild                    con index: vaciar índice y metadatos y reconstruirlos
  --author=A --title=T         con books y query: solo los libros con ese autor, título
  --language=L                 o idioma (coincidencia exacta)
  --ids=FICHERO                lista de IDs (por defecto <root>/shared/book_ids.txt)
  --delay=1s                   espera entre peticiones a Gutenberg
  --mongo=URI                  servidor de MongoDB (por defecto mongodb://localhost:27017)
  --root=DIR                   raíz del repositorio: shared/, cache/ y output/ (por defecto ..)"""

_DURATION_UNITS = {"ns": 1e-9, "us": 1e-6, "µs": 1e-6, "ms": 1e-3, "s": 1.0, "m": 60.0, "h": 3600.0}
_DURATION_PART = re.compile(r"(\d+(?:\.\d*)?|\.\d+)(ns|us|µs|ms|s|m|h)")


def parse_duration(text: str) -> float:
    """Formato de duración de Go ("1s", "500ms", "1m30s", "0"), en segundos."""
    if text == "0":
        return 0.0
    seconds, end = 0.0, 0
    for match in _DURATION_PART.finditer(text):
        if match.start() != end:
            break
        seconds += float(match.group(1)) * _DURATION_UNITS[match.group(2)]
        end = match.end()
    if end == 0 or end != len(text):
        raise argparse.ArgumentTypeError(f'duración no válida "{text}" (ejemplos: 1s, 500ms)')
    return seconds


class _Parser(argparse.ArgumentParser):
    def error(self, message):
        raise ValueError(message)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = _Parser(add_help=False, allow_abbrev=False)
    parser.add_argument("positional", nargs="*")
    parser.add_argument("-h", "--help", action="store_true")
    parser.add_argument("--root", type=Path, default=Path(".."))
    parser.add_argument("--datalake", default="book")
    parser.add_argument("--index", default="json")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--sample", action="store_true")
    parser.add_argument("--rebuild", action="store_true")
    parser.add_argument("--n", type=int, default=0)
    parser.add_argument("--batch", type=int, default=100)
    parser.add_argument("--ids", dest="ids_file", type=Path)
    parser.add_argument("--delay", type=parse_duration, default=1.0)
    parser.add_argument("--mongo", default="mongodb://localhost:27017")
    parser.add_argument("--author")
    parser.add_argument("--title")
    parser.add_argument("--language")
    # Como el paquete flag de Go: opciones y argumentos en cualquier orden.
    opts = parser.parse_intermixed_args(argv)
    if opts.sample:
        opts.offline = True
    opts.filter = Filter(title=opts.title, author=opts.author, language=opts.language)
    return opts


class Options:
    """Rutas que se derivan de las opciones."""

    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.output = args.root / "output" / LANG
        self.datamarts = self.output / "datamarts"

    def shared(self, name: str) -> Path:
        return self.args.root / "shared" / name

    def cache_dir(self) -> Path:
        """De donde se leen los libros crudos sin red: la caché completa o los
        pocos libros de muestra que sí están en el repositorio."""
        return self.args.root / ("sample_data" if self.args.sample else "cache")

    def ids_path(self) -> Path:
        if self.args.ids_file:
            return self.args.ids_file
        return self.shared("book_ids_sample.txt" if self.args.sample else "book_ids.txt")


def main(argv: list[str] | None = None) -> int:
    # Como Go y Java, la salida siempre en UTF-8, sea cual sea la consola.
    sys.stdout.reconfigure(encoding="utf-8", errors="surrogateescape")
    try:
        run(sys.argv[1:] if argv is None else argv)
    except Exception as e:  # noqa: BLE001 - cualquier error termina el programa con su mensaje
        print(f"[ERROR]: {e}")
        return 1
    return 0


def run(argv: list[str]) -> None:
    args = parse_args(argv)
    if args.help or not args.positional:
        print(USAGE)
        return
    opts = Options(args)
    command, rest = args.positional[0], args.positional[1:]
    commands = {
        "pipeline": lambda: run_pipeline(opts, rest),
        "download": lambda: download(opts, rest),
        "index": lambda: index_pending(opts),
        "query": lambda: query(opts, rest),
        "books": lambda: books(opts, rest),
        "dump": lambda: dump(opts),
        "lookup": lambda: lookup(opts, rest),
        "status": lambda: status(opts),
        "recover": lambda: recover_state(new_pipeline(opts)),
    }
    if command not in commands:
        print(USAGE)
        raise ValueError(f'comando desconocido "{command}"')
    commands[command]()


def new_pipeline(opts: Options) -> Pipeline:
    """Abre el datalake y el control, que usan casi todos los comandos."""
    store = new_store(opts.args.datalake, opts.output)
    state = State(opts.output / "control")
    return Pipeline(store, state, log=lambda message: print(f"[CONTROL:{store.name}] {message}"))


def set_source(pipeline: Pipeline, opts: Options) -> None:
    if opts.args.offline:
        pipeline.source = CacheSource(opts.cache_dir())
    else:
        pipeline.source = GutenbergSource(opts.cache_dir(), opts.args.delay)


def open_metadata(opts: Options) -> MetadataDB:
    return MetadataDB(opts.datamarts / "metadata.db")


def open_index(opts: Options):
    return new_index(opts.args.index, opts.datamarts, opts.args.mongo, LANG)


def load_stopwords(opts: Options) -> frozenset[str]:
    path = opts.shared("stopwords.txt")
    try:
        return tokenizer.load_stopwords(path)
    except FileNotFoundError:
        print(f"[AVISO] no existe {path}: no se filtra ninguna stopword")
        return frozenset()


class Datamarts:
    """Añade al pipeline lo que hace falta para indexar y lo cierra al salir."""

    def __init__(self, pipeline: Pipeline, opts: Options):
        self.pipeline, self.opts = pipeline, opts

    def __enter__(self):
        stopwords = load_stopwords(self.opts)
        self.metadata = open_metadata(self.opts)
        try:
            self.index = open_index(self.opts)
        except Exception:
            self.metadata.close()
            raise
        self.pipeline.stopwords = stopwords
        self.pipeline.metadata = self.metadata
        self.pipeline.index = self.index
        self.pipeline.datamarts = self.opts.datamarts
        return self

    def __exit__(self, *exc):
        try:
            self.index.close()
        finally:
            self.metadata.close()


def parse_ids(args: list[str]) -> list[int]:
    ids = []
    for arg in args:
        try:
            ids.append(int(arg))
        except ValueError:
            raise ValueError(f'"{arg}" no es un ID de libro') from None
    return ids


def target_ids(opts: Options, args: list[str]) -> list[int]:
    """Libros con los que trabaja el comando: los que se pasan como argumento
    o, si no hay, los de la lista compartida. Con --n, solo los N primeros."""
    ids = parse_ids(args) or read_ids(opts.ids_path())
    if 0 < opts.args.n < len(ids):
        ids = ids[: opts.args.n]
    return ids


def recover_state(pipeline: Pipeline) -> None:
    report: Report = pipeline.recover()
    if not report.clean():
        print(
            f"[RECOVER:{pipeline.store.name}] {report.temp_files} .tmp borrados, "
            f"{len(report.unregistered)} sin registrar, {len(report.missing)} registrados sin libro, "
            f"{len(report.orphan_indexed)} indexados sin libro, {report.reindex} por reindexar"
        )


def run_pipeline(opts: Options, args: list[str]) -> None:
    ids = target_ids(opts, args)
    pipeline = new_pipeline(opts)
    set_source(pipeline, opts)
    with Datamarts(pipeline, opts):
        recover_state(pipeline)
        summary = Summary()
        try:
            pipeline.run(ids, opts.args.batch, summary)
        finally:
            print(
                f"[PIPELINE:{pipeline.store.name}/{pipeline.index.name}] {summary.downloaded} guardados, "
                f"{summary.discarded} descartados, {summary.skipped} ya estaban, {summary.indexed} indexados"
            )


def download(opts: Options, args: list[str]) -> None:
    ids = target_ids(opts, args)
    pipeline = new_pipeline(opts)
    set_source(pipeline, opts)
    recover_state(pipeline)
    summary = Summary()
    try:
        pipeline.download(ids, summary)
    finally:
        print(
            f"[DOWNLOAD:{pipeline.store.name}] {summary.downloaded} guardados, "
            f"{summary.discarded} descartados, {summary.skipped} ya estaban"
        )


def index_pending(opts: Options) -> None:
    pipeline = new_pipeline(opts)
    with Datamarts(pipeline, opts):
        if opts.args.rebuild:
            pipeline.rebuild()
        recover_state(pipeline)
        summary = Summary()
        try:
            pipeline.index_pending(opts.args.batch, summary)
        finally:
            print(f"[INDEX:{pipeline.index.name}] {summary.indexed} libros indexados")


def query(opts: Options, words: list[str]) -> None:
    if words:
        queries = [" ".join(words)]
    else:
        lines = opts.shared("queries.txt").read_text(encoding="utf-8").split("\n")
        queries = [line for line in map(trim_space, lines) if line]

    stopwords = load_stopwords(opts)
    index = open_index(opts)
    try:
        allowed = filtered_ids(opts)
        for text in queries:
            ids = search(index, text, stopwords)
            if allowed is not None:
                ids = [i for i in ids if i in allowed]
            print(f"[QUERY:{index.name}] {text} -> {len(ids)} libros {format_ids(ids)}")
    finally:
        index.close()


def filtered_ids(opts: Options) -> set[int] | None:
    """Libros que cumplen el filtro de metadatos, o None si no se ha pedido
    ningún filtro."""
    if opts.args.filter.empty():
        return None
    db = open_metadata(opts)
    try:
        return {book.id for book in db.find(opts.args.filter)}
    finally:
        db.close()


def books(opts: Options, args: list[str]) -> None:
    """Consulta el datamart de metadatos: por ID, o por título, autor e idioma.
    Cada línea incluye la ruta del body en el datalake."""
    ids = parse_ids(args)
    db = open_metadata(opts)
    try:
        found = [] if ids else db.find(opts.args.filter)
        for book_id in ids:
            book = db.by_id(book_id)
            if book is None:
                print(f"[BOOKS] {book_id} -> no está en los metadatos")
                continue
            found.append(book)
    finally:
        db.close()

    for book in found:
        print(f"[BOOKS] {book.id} | {book.title or '-'} | {book.author or '-'} | {book.language or '-'} | {book.body_path}")
    print(f"[BOOKS] {len(found)} libros")


def dump(opts: Options) -> None:
    """Exporta el contenido de los datamarts en el formato canónico que se usa
    para comprobar que los tres lenguajes producen lo mismo (SPEC §12)."""
    db = open_metadata(opts)
    try:
        index = open_index(opts)
        try:
            directory = opts.output / "dumps" / index.name
            directory.mkdir(parents=True, exist_ok=True)
            db.dump_tsv(directory / "metadata.tsv")
            dump_tsv(index, directory / "index.tsv")
            print(f"[DUMP:{index.name}] metadata.tsv e index.tsv en {directory}")
        finally:
            index.close()
    finally:
        db.close()


def lookup(opts: Options, args: list[str]) -> None:
    ids = parse_ids(args)
    store = new_store(opts.args.datalake, opts.output)
    for book_id in ids:
        location = store.locate(book_id)
        if location is None:
            print(f"[LOOKUP:{store.name}] {book_id} -> no encontrado")
            continue
        print(f"[LOOKUP:{store.name}] {book_id} -> {location.header} | {location.body}")


def status(opts: Options) -> None:
    pipeline = new_pipeline(opts)
    store, state = pipeline.store, pipeline.state
    print(f"[STATUS:{store.name}]")
    print(f"  en datalake:   {len(store.list())}")
    print(f"  descargados:   {len(state.downloaded.ids)}")
    print(f"  indexados:     {len(state.indexed.ids)}")
    print(f"  pendientes:    {len(state.pending())}")
    print(f"  descartados:   {len(state.failed.ids)}")
