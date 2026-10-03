#!/usr/bin/env python3
"""Gráficas de los benchmarks (SPEC §11, "Análisis"). Solo lee los CSV de
benchmarks/results/ y deja PNG en benchmarks/plots/:

- escalabilidad_<métrica>.png: la métrica frente a N, un panel por estructura
  y una línea por lenguaje, con barras de error (desviación típica);
- estructuras_<métrica>.png: barras por estructura y lenguaje con el mayor N;
- pareto_indice.png: tiempo de construcción del índice frente a pico de
  memoria con el mayor N, con el frente de Pareto.

Uso (desde la raíz del repositorio; necesita matplotlib):

    python benchmarks/plots.py
"""

import argparse
import csv
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

# El color identifica el lenguaje (paleta categórica validada, orden fijo); el
# marcador lo repite para impresión en blanco y negro y daltonismo.
LANGS = ["python", "java", "go"]
COLORS = {"python": "#2a78d6", "java": "#eb6834", "go": "#1baf7a"}
MARKERS = {"python": "o", "java": "s", "go": "^"}
NAMES = {"python": "Python", "java": "Java", "go": "Go"}
INK, INK_SOFT, GRID, SURFACE = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"

STRUCTURES = {
    "datalake": ["time", "book", "range"],
    "metadata": ["sqlite"],
    "index": ["json", "folders", "mongo"],
}
METRICS = {
    "write_throughput": ("datalake", "Throughput de escritura", "libros/s"),
    "lookup_time": ("datalake", "Lookup de header y body", "µs"),
    "incremental_detect_time": ("datalake", "Detección de libros nuevos", "ms"),
    "metadata_insert_time": ("metadata", "Inserción de metadatos", "ms"),
    "metadata_query_time_author": ("metadata", "Consulta de metadatos por autor", "µs"),
    "metadata_query_time_id": ("metadata", "Consulta de metadatos por ID", "µs"),
    "index_build_time": ("index", "Construcción del índice", "ms"),
    "query_time": ("index", "Consulta (AND)", "µs"),
    "update_time": ("index", "Actualización (+50 libros)", "ms"),
    "peak_memory": ("index", "Pico de memoria al construir el índice", "MB"),
    "disk_usage": ("index", "Tamaño del índice en disco", "bytes"),
}


def load(results: Path) -> dict:
    """{(lang, structure, metric): {n: (mean, stddev)}} de todos los CSV."""
    data = defaultdict(dict)
    for path in sorted(results.glob("*.csv")):
        with path.open(encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                if row["lang"] in LANGS:
                    data[(row["lang"], row["structure"], row["metric"])][int(row["n_books"])] = (
                        float(row["mean"]), float(row["stddev"] or 0))
    return data


def style(ax, title: str | None = None):
    ax.set_facecolor(SURFACE)
    ax.grid(True, axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(INK_SOFT)
    ax.tick_params(colors=INK_SOFT, labelsize=9)
    if title:
        ax.set_title(title, color=INK, fontsize=11, loc="left")


def spread_labels(ax, labels, min_gap_px: float = 13, near_x_px: float = 140):
    """Coloca etiquetas de texto junto a sus puntos ([(texto, x, y), ...]) y baja
    las que se pisarían con otra ya colocada cerca."""
    if not labels:
        return
    fig = ax.figure
    fig.canvas.draw()  # fija límites y escalas para pasar a píxeles
    placed = []
    for text, x, y in sorted(labels, key=lambda label: -ax.transData.transform((label[1], label[2]))[1]):
        px, py = ax.transData.transform((x, y))
        target = py
        for other_x, other_y in placed:
            if abs(other_x - px) < near_x_px and abs(other_y - target) < min_gap_px:
                target = other_y - min_gap_px
        placed.append((px, target))
        ax.annotate(text, (x, y), xytext=(7, (target - py) * 72 / fig.dpi), textcoords="offset points",
                    va="center", fontsize=8, color=INK_SOFT)


def wide_range(values) -> bool:
    """Escala logarítmica si los valores abarcan más de dos órdenes de magnitud."""
    positive = [v for v in values if v > 0]
    return len(positive) > 1 and max(positive) / min(positive) > 100


def scalability(data, metric: str, out: Path) -> bool:
    component, title, unit = METRICS[metric]
    structures = [s for s in STRUCTURES[component] if any((l, s, metric) in data for l in LANGS)]
    if not structures:
        return False
    fig, axes = plt.subplots(1, len(structures), figsize=(4.2 * len(structures), 3.6), squeeze=False,
                             facecolor=SURFACE)
    ends = {}
    for ax, structure in zip(axes[0], structures):
        style(ax, structure)
        all_values, ends[structure] = [], []
        for lang in LANGS:
            series = data.get((lang, structure, metric))
            if not series:
                continue
            ns = sorted(series)
            means = [series[n][0] for n in ns]
            errors = [series[n][1] for n in ns]
            all_values += means
            ax.errorbar(ns, means, yerr=errors, color=COLORS[lang], marker=MARKERS[lang], markersize=6,
                        linewidth=2, capsize=3, elinewidth=1, label=NAMES[lang])
            ends[structure].append((NAMES[lang], ns[-1], means[-1]))
        if wide_range(all_values):
            ax.set_yscale("log")
        ax.set_xlabel("N (libros)", color=INK_SOFT, fontsize=9)
        ax.set_xticks(sorted({n for lang in LANGS for n in data.get((lang, structure, metric), {})}))
        ax.margins(x=0.12)
    axes[0][0].set_ylabel(unit, color=INK_SOFT, fontsize=9)
    handles, labels = axes[0][0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper right", frameon=False, ncol=len(labels), fontsize=9)
    fig.suptitle(f"{title} frente a N", x=0.01, ha="left", color=INK, fontsize=12)
    fig.tight_layout(rect=(0, 0, 1, 0.93))
    # Etiqueta directa al final de cada línea, después de fijar el diseño.
    for ax, structure in zip(axes[0], structures):
        spread_labels(ax, ends[structure])
    fig.savefig(out / f"escalabilidad_{metric}.png", dpi=150)
    plt.close(fig)
    return True


def structures_bar(data, metric: str, n: int, out: Path) -> bool:
    component, title, unit = METRICS[metric]
    structures = [s for s in STRUCTURES[component] if any(n in data.get((l, s, metric), {}) for l in LANGS)]
    if len(structures) < 2:
        return False
    langs = [l for l in LANGS if any(n in data.get((l, s, metric), {}) for s in structures)]
    fig, ax = plt.subplots(figsize=(1.6 + 1.9 * len(structures), 3.8), facecolor=SURFACE)
    style(ax)
    width = 0.8 / len(langs)
    values = []
    for i, lang in enumerate(langs):
        xs, means, errors = [], [], []
        for j, structure in enumerate(structures):
            if n in data.get((lang, structure, metric), {}):
                mean, stddev = data[(lang, structure, metric)][n]
                xs.append(j + (i - (len(langs) - 1) / 2) * width)
                means.append(mean)
                errors.append(stddev)
        values += means
        # Separación de 2 px entre barras contiguas con un borde del color de fondo.
        ax.bar(xs, means, width, yerr=errors, color=COLORS[lang], label=NAMES[lang], edgecolor=SURFACE,
               linewidth=1.5, capsize=3, error_kw={"elinewidth": 1, "ecolor": INK_SOFT})
    if wide_range(values):
        ax.set_yscale("log")
    ax.set_xticks(range(len(structures)), structures)
    ax.set_ylabel(unit, color=INK_SOFT, fontsize=9)
    ax.legend(frameon=False, fontsize=9, ncol=len(langs), loc="upper left", bbox_to_anchor=(0, 1.12))
    ax.set_title(f"{title} con N = {n}", color=INK, fontsize=12, loc="left", pad=28)
    fig.tight_layout()
    fig.savefig(out / f"estructuras_{metric}.png", dpi=150)
    plt.close(fig)
    return True


def pareto(data, n: int, out: Path) -> bool:
    """Construcción del índice frente a memoria: un punto por lenguaje y
    estructura. El frente de Pareto une los que no son peores en las dos."""
    points = []
    for lang in LANGS:
        for structure in STRUCTURES["index"]:
            time = data.get((lang, structure, "index_build_time"), {}).get(n)
            memory = data.get((lang, structure, "peak_memory"), {}).get(n)
            if time and memory:
                points.append((memory[0], time[0], lang, structure))
    if len(points) < 2:
        return False
    front = sorted(p for p in points if not any(
        q[0] <= p[0] and q[1] <= p[1] and (q[0] < p[0] or q[1] < p[1]) for q in points))

    fig, ax = plt.subplots(figsize=(7.2, 4.6), facecolor=SURFACE)
    style(ax)
    ax.grid(True, axis="x", color=GRID, linewidth=0.8)
    ax.plot([p[0] for p in front], [p[1] for p in front], color=INK_SOFT, linewidth=1.2, linestyle="--",
            zorder=1, label="Frente de Pareto")
    for memory, time, lang, structure in points:
        ax.scatter(memory, time, s=80, color=COLORS[lang], marker=MARKERS[lang], edgecolor=SURFACE,
                   linewidth=2, zorder=2)
    for lang in LANGS:
        if any(p[2] == lang for p in points):
            ax.scatter([], [], s=80, color=COLORS[lang], marker=MARKERS[lang], label=NAMES[lang])
    if wide_range([p[1] for p in points]):
        ax.set_yscale("log")
    ax.margins(x=0.2)  # sitio para las etiquetas de los puntos más a la derecha
    ax.set_xlim(left=0)
    ax.set_xlabel("Pico de memoria (MB)", color=INK_SOFT, fontsize=9)
    ax.set_ylabel("Construcción del índice (ms)", color=INK_SOFT, fontsize=9)
    ax.legend(frameon=False, fontsize=9, loc="upper right")
    ax.set_title(f"Tiempo frente a memoria al construir el índice (N = {n})", color=INK, fontsize=12, loc="left")
    fig.tight_layout()
    spread_labels(ax, [(f"{NAMES[lang]} · {structure}", memory, time) for memory, time, lang, structure in points])
    fig.savefig(out / "pareto_indice.png", dpi=150)
    plt.close(fig)
    print("Frente de Pareto: " + ", ".join(f"{NAMES[p[2]]}/{p[3]}" for p in front))
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    data = load(args.root / "benchmarks" / "results")
    if not data:
        print("No hay resultados en benchmarks/results/")
        return 1
    out = args.root / "benchmarks" / "plots"
    out.mkdir(exist_ok=True)
    n = max(n for series in data.values() for n in series)

    written = 0
    for metric in METRICS:
        written += scalability(data, metric, out)
        written += structures_bar(data, metric, n, out)
    written += pareto(data, n, out)
    print(f"{written} gráficas en {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
