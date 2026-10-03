#!/usr/bin/env python3
"""Ejecuta los benchmarks de los tres lenguajes, uno detrás de otro y con las
mismas opciones (SPEC §11). Cada lenguaje deja su CSV en benchmarks/results/ y
su salida cruda en benchmarks/raw/<lang>/; aquí solo se guarda el registro de
cada ejecución en benchmarks/raw/<lang>/run.log.

Uso (desde la raíz del repositorio):

    python benchmarks/run_all.py
    python benchmarks/run_all.py --only=micro,script,download --reduce-folders-from=100
    python benchmarks/run_all.py --langs=python,java --sizes=100,250

Antes: MongoDB levantado (docker compose up -d), los libros en cache/
(N + 50 primeros de shared/book_ids.txt), `mvn package` en java/ y las
dependencias de Python (pip install -r python/requirements.txt).
"""

import argparse
import os
import subprocess
import sys
import time
from datetime import timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def commands(python: str, java: str) -> dict[str, list[str]]:
    return {
        "go": ["go", "run", "./cmd/bench"],
        "python": [python, "-m", "bench.run"],
        "java": [java, "-cp", "target/benchmarks.jar", "es.ulpgc.bigdata.bench.BenchMain"],
    }


def keep_awake() -> None:
    """En Windows, impide que el equipo se suspenda mientras dure este proceso
    (la petición desaparece sola al terminar; no cambia la configuración)."""
    if sys.platform == "win32":
        import ctypes

        es_continuous, es_system_required = 0x80000000, 0x00000001
        ctypes.windll.kernel32.SetThreadExecutionState(es_continuous | es_system_required)


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    keep_awake()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--langs", default="go,python,java")
    parser.add_argument("--sizes", default="100,250,500,1000")
    parser.add_argument("--only", default="micro,script,download")
    parser.add_argument("--reduce-folders-from", default="0",
                        help="con N >= este valor, folders usa 1 + 3 iteraciones al construir, actualizar y medir memoria")
    parser.add_argument("--mongo", default="mongodb://localhost:27017")
    parser.add_argument("--python", default=sys.executable, help="intérprete de Python con las dependencias")
    parser.add_argument("--java", default="java", help="ejecutable de Java")
    args = parser.parse_args()

    options = [f"--sizes={args.sizes}", f"--only={args.only}", f"--mongo={args.mongo}",
               f"--reduce-folders-from={args.reduce_folders_from}"]
    failed = []
    for lang in args.langs.split(","):
        command = commands(args.python, args.java)[lang] + options
        log_path = ROOT / "benchmarks" / "raw" / lang / "run.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        print(f"[RUN] {lang}: {' '.join(command)}  (registro en {log_path})", flush=True)
        start = time.monotonic()
        with open(log_path, "w", encoding="utf-8") as log:
            code = subprocess.run(command, cwd=ROOT / lang, stdout=log, stderr=subprocess.STDOUT,
                                  env=os.environ).returncode
        elapsed = timedelta(seconds=round(time.monotonic() - start))
        print(f"[RUN] {lang}: {'terminado' if code == 0 else f'ERROR (código {code})'} en {elapsed}", flush=True)
        if code != 0:
            failed.append(lang)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
