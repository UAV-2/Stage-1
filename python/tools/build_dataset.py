#!/usr/bin/env python3
"""Genera shared/book_ids.txt y shared/book_ids_sample.txt (SPEC §3).

Toma del catálogo oficial de Gutenberg los primeros IDs, en orden ascendente,
con Type = Text y Language = en exactamente. Con --cache, además descarta los
libros de cache/ sin marcadores START/END o cuya cabecera no dice
"Language: English", y en su lugar toma el siguiente ID del catálogo. Los que
Gutenberg no tiene (404) no llegan a cache/: se excluyen con --exclude, que
admite el failed_books.txt de cualquier lenguaje.

Uso (desde python/):

    python tools/build_dataset.py --check            # compara con la lista actual, sin escribir
    python tools/build_dataset.py --count=1050       # reescribe las dos listas
    python tools/build_dataset.py --count=1050 --cache=../cache --exclude=../output/go/control/failed_books.txt
"""

import argparse
import csv
import io
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from search_engine.control import read_ids  # noqa: E402
from search_engine.fileutil import decode  # noqa: E402
from search_engine.ingestion import BookUnavailable, split  # noqa: E402
from search_engine.metadata import extract  # noqa: E402

CATALOG_URL = "https://www.gutenberg.org/cache/epub/feeds/pg_catalog.csv"
SAMPLE_SIZE = 20


def catalog_ids(source: str) -> list[int]:
    if source.startswith("http"):
        with urllib.request.urlopen(source, timeout=120) as response:
            text = response.read().decode("utf-8")
    else:
        text = Path(source).read_text(encoding="utf-8")
    rows = csv.DictReader(io.StringIO(text, newline=""))
    return sorted(int(row["Text#"]) for row in rows if row["Type"] == "Text" and row["Language"] == "en")


def usable(book_id: int, cache: Path) -> bool:
    """Tiene marcadores y la cabecera dice que está en inglés."""
    try:
        book = split(book_id, decode((cache / f"{book_id}.txt").read_bytes()))
    except BookUnavailable:
        return False
    return extract(book_id, book.header).language == "en"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(".."), help="raíz del repositorio")
    parser.add_argument("--catalog", default=CATALOG_URL, help="URL o ruta local de pg_catalog.csv")
    parser.add_argument("--count", type=int, default=1000, help="número de IDs de la lista")
    parser.add_argument("--cache", type=Path, help="carpeta con los .txt crudos para filtrar los libros")
    parser.add_argument("--exclude", type=Path, help="fichero con IDs que no se incluyen (p. ej. los 404)")
    parser.add_argument("--check", action="store_true", help="solo comparar con shared/book_ids.txt")
    args = parser.parse_args()

    candidates = catalog_ids(args.catalog)
    excluded = set(read_ids(args.exclude)) if args.exclude else set()
    ids, discarded = [], []
    for book_id in candidates:
        if len(ids) == args.count:
            break
        if book_id in excluded:
            discarded.append(book_id)
            continue
        if args.cache is not None:
            if not (args.cache / f"{book_id}.txt").exists():
                sys.exit(f"el libro {book_id} no está en {args.cache}")
            if not usable(book_id, args.cache):
                discarded.append(book_id)
                continue
        ids.append(book_id)
    if discarded:
        print(f"Descartados (excluidos, sin marcadores o no en inglés): {discarded}")

    shared = args.root / "shared"
    if args.check:
        current = read_ids(shared / "book_ids.txt")
        same = current == ids[: len(current)]
        print(f"shared/book_ids.txt: {len(current)} IDs; "
              f"{'coinciden' if same else 'NO coinciden'} con los {len(current)} primeros del catálogo")
        return 0 if same else 1

    # write_bytes: con write_text, Windows escribiría \r\n.
    (shared / "book_ids.txt").write_bytes("".join(f"{i}\n" for i in ids).encode("ascii"))
    (shared / "book_ids_sample.txt").write_bytes("".join(f"{i}\n" for i in ids[:SAMPLE_SIZE]).encode("ascii"))
    print(f"shared/book_ids.txt: {len(ids)} IDs ({ids[0]} … {ids[-1]}); book_ids_sample.txt: {SAMPLE_SIZE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
