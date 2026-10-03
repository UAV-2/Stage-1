"""Ejecuta los benchmarks de Python del SPEC §11 y deja los resultados en
benchmarks/results/python.csv y la salida cruda en benchmarks/raw/python/.

Se lanza desde la carpeta python/:

    python -m bench.run                          # micro-benchmarks y métricas de script
    python -m bench.run --only=download          # throughput de descarga (usa la red)
    python -m bench.run --sizes=100,250          # solo algunos tamaños

Cada ejecución actualiza las filas que mide y conserva las demás del CSV.
"""

import argparse
import ctypes
import json
import math
import os
import platform
import subprocess
import sys
from datetime import datetime
from pathlib import Path

LANG = "python"
CSV_HEADER = "lang,component,structure,n_books,metric,mean,stddev,min,max,ci_low,ci_high,iterations,unit"


def number(value: float) -> str:
    """Punto decimal y como mucho 3 decimales, sin ceros de sobra."""
    text = f"{round(value, 3):.3f}".rstrip("0").rstrip(".")
    return "0" if text == "-0" else text


def csv_line(row: tuple) -> str:
    component, structure, n, metric, values, unit = row
    mean = sum(values) / len(values)
    stddev = math.sqrt(sum((v - mean) ** 2 for v in values) / (len(values) - 1)) if len(values) > 1 else 0.0
    fields = [LANG, component, structure, str(n), metric,
              number(mean), number(stddev), number(min(values)), number(max(values)), "", "", str(len(values)), unit]
    return ",".join(fields)


def update_csv(path: Path, rows: list[tuple]) -> None:
    """Reescribe el CSV con las filas nuevas y conserva las anteriores que no
    se han vuelto a medir. Orden: componente, métrica, estructura y N."""
    lines = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").split("\n")[1:]:
            fields = line.split(",")
            if len(fields) == 13:
                lines[tuple(fields[:5])] = line
    for row in rows:
        line = csv_line(row)
        lines[tuple(line.split(",")[:5])] = line
    keys = sorted(lines, key=lambda k: (k[1], k[4], k[2], int(k[3])))
    path.write_bytes((CSV_HEADER + "\n" + "".join(lines[k] + "\n" for k in keys)).encode("utf-8"))


def micro_rows(raw_json: Path) -> list[tuple]:
    """Convierte la salida de pytest-benchmark: un valor por ronda medida."""
    rows = []
    for bench in json.loads(raw_json.read_text(encoding="utf-8"))["benchmarks"]:
        info = bench["extra_info"]
        seconds = bench["stats"]["data"]  # por operación; sin las rondas de calentamiento
        convert = {
            "ms": lambda s: s * 1e3,
            "us": lambda s: s * 1e6,
            "throughput": lambda s: info["n_books"] / s,
        }[info["convert"]]
        rows.append((info["component"], info["structure"], info["n_books"], info["metric"],
                     [convert(s) for s in seconds], info["unit"]))
    return rows


def run_micro(args, raw_dir: Path) -> tuple[list[tuple], bool]:
    """Devuelve las filas de los micro-benchmarks que han terminado y si han
    terminado todos: si falla uno, los demás se conservan."""
    raw_json = raw_dir / "micro.json"
    command = [sys.executable, "-m", "pytest", "bench", "-p", "no:cacheprovider", "--benchmark-only",
               "--benchmark-save-data", f"--benchmark-json={raw_json}", "--benchmark-columns=mean,stddev,min,max,rounds",
               "-o", "testpaths=bench"]
    if args.select:
        command += ["-k", args.select]
    print(f"[BENCH] {' '.join(command)}", flush=True)
    with open(raw_dir / "micro.txt", "w", encoding="utf-8") as log:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                   encoding="utf-8", errors="replace")
        for line in process.stdout:
            log.write(line)
            print(line, end="", flush=True)
        ok = process.wait() == 0
    if not ok:
        print(f"[ERROR] algún micro-benchmark ha fallado (ver {raw_dir / 'micro.txt'}); se guardan los demás")
    return (micro_rows(raw_json) if raw_json.exists() else []), ok


def machine() -> tuple[str, str]:
    """CPU y RAM de la máquina, para anotarlas junto a los resultados."""
    if Path("/proc/cpuinfo").exists():
        cpu = next((line.split(":", 1)[1].strip() for line in Path("/proc/cpuinfo").read_text().split("\n")
                    if line.startswith("model name")), platform.processor())
        ram = Path("/proc/meminfo").read_text().split("\n")[0].split(":", 1)[1].strip()
        return cpu, ram
    if sys.platform == "win32":
        import winreg

        key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0")
        cpu = winreg.QueryValueEx(key, "ProcessorNameString")[0].strip()

        class Status(ctypes.Structure):
            _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong)] + [
                (name, ctypes.c_ulonglong) for name in ("ullTotalPhys", "ullAvailPhys", "ullTotalPageFile",
                                                        "ullAvailPageFile", "ullTotalVirtual", "ullAvailVirtual",
                                                        "ullAvailExtendedVirtual")]

        status = Status()
        status.dwLength = ctypes.sizeof(status)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status))
        return cpu, f"{status.ullTotalPhys // 1024} kB"
    return platform.processor(), "desconocida"


def write_environment(path: Path, args) -> None:
    """Anota la máquina y la configuración. Cada ejecución añade un bloque."""
    import pytest_benchmark

    cpu, ram = machine()
    block = "\n".join([
        f"fecha: {datetime.now().astimezone().isoformat(timespec='seconds')}",
        f"python: {platform.python_version()} ({platform.python_implementation()}), pytest-benchmark {pytest_benchmark.__version__}",
        f"sistema: {platform.system()} {platform.release()} {platform.machine()}",
        f"cpus: {os.cpu_count()}",
        f"cpu: {cpu}",
        f"ram: {ram}",
        f"tamaños: {args.sizes}",
        f"medidas: {args.only}",
        f"iteraciones: {args.warmup} de calentamiento + {args.rounds} medidas"
        + (f" (folders con N >= {args.reduce_folders_from}: construcción, actualización y memoria con 1 + 3)"
           if args.reduce_folders_from else ""),
        f"repeticiones de script: {args.reps}",
        f"mongo: {args.mongo}",
    ]) + "\n"
    separator = "\n" if path.exists() and path.stat().st_size > 0 else ""
    with open(path, "a", encoding="utf-8") as f:
        f.write(separator + block)


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default="..", help="raíz del repositorio")
    parser.add_argument("--sizes", default="100,250,500,1000", help="valores de N")
    parser.add_argument("--mongo", default="mongodb://localhost:27017", help="servidor de MongoDB")
    parser.add_argument("--only", default="micro,script", help="qué medir: micro, script y/o download")
    parser.add_argument("--warmup", type=int, default=5, help="rondas de calentamiento que se descartan")
    parser.add_argument("--rounds", type=int, default=10, help="rondas medidas de cada micro-benchmark")
    parser.add_argument("--reps", type=int, default=5, help="repeticiones de las métricas de script")
    parser.add_argument("--reduce-folders-from", type=int, default=0,
                        help="con N >= este valor, folders usa 1 + 3 iteraciones al construir, actualizar y medir memoria")
    parser.add_argument("--download-books", type=int, default=50, help="libros del throughput de descarga")
    parser.add_argument("--select", help="solo los micro-benchmarks que casen con esta expresión de pytest -k")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    os.environ.update(STAGE1_ROOT=str(root), STAGE1_SIZES=args.sizes, STAGE1_MONGO=args.mongo,
                      STAGE1_WARMUP=str(args.warmup), STAGE1_ROUNDS=str(args.rounds),
                      STAGE1_REDUCE_FOLDERS_FROM=str(args.reduce_folders_from))
    from bench import scripts
    from bench.benchkit import Env, remove

    raw_dir = root / "benchmarks" / "raw" / LANG
    csv_path = root / "benchmarks" / "results" / f"{LANG}.csv"
    raw_dir.mkdir(parents=True, exist_ok=True)
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    write_environment(raw_dir / "environment.txt", args)

    only = args.only.split(",")
    env = Env()
    rows = []
    ok = True
    try:
        if "micro" in only:
            measured, ok = run_micro(args, raw_dir)
            rows += measured
        for name, enabled in (("script", "script" in only), ("download", "download" in only)):
            if not enabled:
                continue
            with open(raw_dir / f"{name}.txt", "w", encoding="utf-8") as log_file:
                def log(message: str) -> None:
                    print(f"[SCRIPT] {message}", flush=True)
                    log_file.write(f"[SCRIPT] {message}\n")
                    log_file.flush()

                if name == "script":
                    rows += scripts.storage(env, log)
                    rows += scripts.recovery(env, args.reps, log)
                    rows += scripts.index_footprint(env, args.reps, args.reduce_folders_from, log)
                else:
                    rows += scripts.download_throughput(env, args.reps, args.download_books, log)
    finally:
        update_csv(csv_path, rows)
        print(f"[BENCH] {len(rows)} filas actualizadas en {csv_path}")
        remove(env.work)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
