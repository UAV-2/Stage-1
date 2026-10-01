#!/usr/bin/env python3
"""Verificación de equivalencia (SPEC §12).

Busca los dumps canónicos que exporta cada implementación en

    output/<lang>/dumps/<índice>/metadata.tsv
    output/<lang>/dumps/<índice>/index.tsv

y comprueba que todos son idénticos: entre lenguajes y entre las tres
estructuras de índice. Si hay diferencias, muestra la primera línea distinta
de cada fichero y termina con código 1.

Uso (desde la raíz del repositorio):

    python3 benchmarks/compare_outputs.py
    python3 benchmarks/compare_outputs.py --reference go
"""

import argparse
import hashlib
import sys
from pathlib import Path

DUMPS = ("metadata.tsv", "index.tsv")


def find_dumps(output: Path, langs: list[str] | None = None) -> dict[str, list[Path]]:
    """Devuelve, para cada nombre de dump, las rutas encontradas (solo las de
    langs, si se indica)."""
    found = {name: [] for name in DUMPS}
    for path in sorted(output.glob("*/dumps/*/*.tsv")):
        if path.name in found and (langs is None or path.relative_to(output).parts[0] in langs):
            found[path.name].append(path)
    return found


def label(path: Path, output: Path) -> str:
    lang, _, structure = path.relative_to(output).parts[:3]
    return f"{lang}/{structure}"


def first_difference(a: Path, b: Path) -> str:
    with a.open(encoding="utf-8", newline="") as fa, b.open(encoding="utf-8", newline="") as fb:
        for number, (line_a, line_b) in enumerate(zip(fa, fb), start=1):
            if line_a != line_b:
                return f"línea {number}:\n      {line_a.rstrip()[:120]!r}\n      {line_b.rstrip()[:120]!r}"
        rest_a, rest_b = fa.read(), fb.read()
    longer = a if rest_a else b
    return f"uno de los dos termina antes; {longer} tiene más líneas"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def compare(root: Path, reference_lang: str = "python", langs: list[str] | None = None) -> int:
    """Compara los dumps de root/output (de todos los lenguajes o solo de langs)
    y devuelve 0 si coinciden."""
    output = root / "output"
    all_equal = True
    for name, paths in find_dumps(output, langs).items():
        if not paths:
            print(f"[{name}] no hay ningún dump en {output}/<lang>/dumps/<índice>/")
            all_equal = False
            continue

        preferred = [p for p in paths if label(p, output).startswith(reference_lang + "/")]
        reference = (preferred or paths)[0]
        reference_digest = digest(reference)
        lines = sum(1 for _ in reference.open(encoding="utf-8"))
        print(f"[{name}] referencia: {label(reference, output)} ({lines} líneas)")

        for path in paths:
            if path == reference:
                continue
            if digest(path) == reference_digest:
                print(f"    OK        {label(path, output)}")
            else:
                all_equal = False
                print(f"    DISTINTO  {label(path, output)}: {first_difference(reference, path)}")

    found = sorted({p.relative_to(output).parts[0] for paths in find_dumps(output, langs).values() for p in paths})
    print(f"\nLenguajes con dumps: {', '.join(found) or 'ninguno'}")
    print("TODOS LOS DUMPS COINCIDEN" if all_equal else "HAY DIFERENCIAS: no se mide hasta que coincidan (SPEC §12)")
    return 0 if all_equal else 1


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent,
                        help="raíz del repositorio (por defecto, la carpeta padre de benchmarks/)")
    parser.add_argument("--reference", default="python",
                        help="lenguaje de referencia (por defecto python; si no hay, el primero que aparezca)")
    args = parser.parse_args()
    return compare(args.root, args.reference)


if __name__ == "__main__":
    sys.exit(main())
