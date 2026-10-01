#!/usr/bin/env python3
"""Ejecuta las tres implementaciones con los mismos libros y comprueba que
producen exactamente lo mismo (SPEC §12).

Para cada variante del datalake, cada lenguaje empieza con su output/<lang>/
vacío, ejecuta el pipeline con la primera variante del índice y reconstruye las
demás con "index --rebuild". Después se compara:

- los dumps canónicos de metadatos e índice, con compare_outputs.py: entre
  lenguajes y entre las tres estructuras de índice;
- los ficheros del datalake, byte a byte (en time, sin la carpeta de la hora);
- los ficheros de control y el body_path de cada libro en metadata.db;
- el fichero inverted_index.json, byte a byte;
- los resultados de las consultas de shared/queries.txt.

Uso (desde la raíz del repositorio; borra output/<lang>/ de cada lenguaje):

    python benchmarks/check_equivalence.py --sample
    python benchmarks/check_equivalence.py --n=1000 --datalakes=book
    python benchmarks/check_equivalence.py --sample --go=go/stage1_go.exe --indexes=json,folders

Por defecto: Python con este mismo intérprete, Java con su jar
(mvn package) y Go con "go run .". MongoDB tiene que estar levantado si se
incluye el índice mongo.
"""

import argparse
import hashlib
import os
import re
import shlex
import shutil
import sqlite3
import subprocess
import sys
from pathlib import Path

import compare_outputs

LANGS = ("python", "java", "go")
DEFAULT_COMMANDS = {
    "python": [sys.executable, "main.py"],
    "java": ["java", "-jar", "target/stage1-java.jar"],
    "go": ["go", "run", "."],
}


def split_command(command: str) -> list[str]:
    """Trocea un comando respetando comillas, también con rutas de Windows."""
    if os.name != "nt":
        return shlex.split(command)
    parts = shlex.split(command, posix=False)
    return [p[1:-1] if len(p) > 1 and p[0] == p[-1] and p[0] in "\"'" else p for p in parts]


class Runner:
    def __init__(self, root: Path, commands: dict[str, list[str]]):
        self.root = root
        self.commands = commands

    def __call__(self, lang: str, *args: str) -> str:
        command = self.commands[lang] + list(args)
        result = subprocess.run(command, cwd=self.root / lang, capture_output=True, encoding="utf-8", errors="replace")
        if result.returncode != 0:
            sys.exit(f"[{lang}] falla {' '.join(args)}:\n{result.stdout}{result.stderr}")
        return result.stdout


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def datalake_files(lake: Path, kind: str) -> dict[str, str]:
    """Hash de cada fichero del datalake por su ruta relativa. En time se quita
    la carpeta YYYYMMDD/HH, que depende de la hora de la ejecución."""
    files = {}
    for path in lake.rglob("*"):
        if not path.is_file() or path.name == "_locations.tsv":
            continue
        parts = path.relative_to(lake).parts
        key = "/".join(parts[2:] if kind == "time" else parts)
        files[key] = digest(path)
    return files


def state_of(output: Path, kind: str) -> dict[str, object]:
    """Todo lo que tiene que coincidir entre lenguajes, salvo los dumps."""
    state: dict[str, object] = {"datalake": datalake_files(output / f"datalake_{kind}", kind)}
    for name in ("downloaded_books.txt", "indexed_books.txt", "failed_books.txt"):
        path = output / "control" / name
        state[f"control/{name}"] = path.read_bytes() if path.exists() else b""
    with sqlite3.connect(output / "datamarts" / "metadata.db") as conn:
        rows = conn.execute("SELECT book_id, body_path FROM books ORDER BY book_id").fetchall()
    if kind == "time":
        rows = [(i, re.sub(r"^datalake_time/\d{8}/\d{2}/", "datalake_time/*/", p)) for i, p in rows]
    state["metadata.db body_path"] = rows
    json_index = output / "datamarts" / "inverted_index.json"
    if json_index.exists():
        state["inverted_index.json"] = digest(json_index)
    return state


def describe(value: object) -> str:
    if isinstance(value, dict):
        return f"{len(value)} ficheros"
    if isinstance(value, (bytes, list)):
        return f"{len(value.splitlines()) if isinstance(value, bytes) else len(value)} líneas"
    return str(value)[:16]


def first_difference(a: object, b: object) -> str:
    if isinstance(a, dict) and isinstance(b, dict):
        for key in sorted(set(a) | set(b)):
            if a.get(key) != b.get(key):
                return f"{key}: {'falta' if key not in a else 'distinto' if key in b else 'sobra'}"
    if isinstance(a, list) and isinstance(b, list):
        for x, y in zip(a, b):
            if x != y:
                return f"{x!r} != {y!r}"
    return f"{describe(a)} / {describe(b)}"


def check_datalake(run: Runner, root: Path, args, kind: str) -> bool:
    data = ["--sample"] if args.sample else ["--offline", f"--n={args.n}"]
    # La primera variante del índice se llena con el pipeline completo. MongoDB
    # conserva su colección entre ejecuciones, así que siempre se reconstruye.
    indexes = sorted(args.indexes, key=lambda i: i == "mongo")
    states, queries = {}, {}
    for lang in args.langs:
        shutil.rmtree(root / "output" / lang, ignore_errors=True)
        for position, index in enumerate(indexes):
            options = [f"--datalake={kind}", f"--index={index}", f"--batch={args.batch}"]
            if position == 0 and index != "mongo":
                run(lang, "pipeline", *options, *data)
            else:
                if position == 0:
                    run(lang, "download", *options, *data)
                run(lang, "index", "--rebuild", *options)
            run(lang, "dump", *options)
            # El prefijo [QUERY:<índice>] cambia; el resultado no puede cambiar.
            queries[f"{lang}/{index}"] = re.sub(r"^\[QUERY:\w+\]", "", run(lang, "query", *options), flags=re.M)
        states[lang] = state_of(root / "output" / lang, kind)
        print(f"  {lang}: {len(states[lang]['datalake']) // 2} libros en el datalake, "
              f"{len(states[lang]['metadata.db body_path'])} en los metadatos")

    print()
    ok = compare_outputs.compare(root, args.langs[0], args.langs) == 0
    reference = args.langs[0]
    print()
    for key in states[reference]:
        different = [lang for lang in args.langs if states[lang].get(key) != states[reference][key]]
        ok &= not different
        verdict = "OK      " if not different else "DISTINTO"
        print(f"[{key}] {verdict} {describe(states[reference][key])}")
        for lang in different:
            print(f"    {reference} vs {lang}: {first_difference(states[reference][key], states[lang].get(key))}")
    reference_queries = queries[f"{reference}/{indexes[0]}"]
    different = [name for name, result in queries.items() if result != reference_queries]
    ok &= not different
    print(f"[queries.txt] {'OK      ' if not different else 'DISTINTO'} {len(queries)} ejecuciones"
          + (f": {', '.join(different)}" if different else ""))
    return ok


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    dataset = parser.add_mutually_exclusive_group(required=True)
    dataset.add_argument("--sample", action="store_true", help="los 20 libros de sample_data/")
    dataset.add_argument("--n", type=int, help="los N primeros libros de shared/book_ids.txt, desde cache/")
    parser.add_argument("--langs", default=",".join(LANGS), help="el primero es la referencia")
    parser.add_argument("--datalakes", default="time,book,range")
    parser.add_argument("--indexes", default="json,folders,mongo")
    parser.add_argument("--batch", type=int, default=100)
    for lang in LANGS:
        parser.add_argument(f"--{lang}", help=f"comando para ejecutar {lang} desde {lang}/")
    args = parser.parse_args()
    args.langs = args.langs.split(",")
    args.indexes = args.indexes.split(",")

    root = args.root.resolve()
    commands = {lang: split_command(getattr(args, lang)) if getattr(args, lang) else DEFAULT_COMMANDS[lang]
                for lang in LANGS}
    run = Runner(root, commands)
    results = {}
    for kind in args.datalakes.split(","):
        print(f"\n===== datalake {kind}: {', '.join(args.langs)} × {', '.join(args.indexes)} =====")
        results[kind] = check_datalake(run, root, args, kind)

    print("\n===== Resumen =====")
    for kind, ok in results.items():
        print(f"  datalake {kind}: {'todo coincide' if ok else 'HAY DIFERENCIAS'}")
    return 0 if all(results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
