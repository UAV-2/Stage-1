# Integración de los tres lenguajes

Guía para dejar Python, Java y Go en un único repositorio con la estructura de `SPEC.md` §2. Cuando el montaje esté hecho y los dumps coincidan, este fichero se puede borrar.

Las reglas que tienen que cumplir las tres implementaciones están en **`SPEC.md`, versión 2**. El §18 resume lo que cambió respecto a la primera versión: formato y ubicación de los dumps, definición exacta de cada benchmark, `failed_books.txt`, recuperación, dataset, interfaz de línea de comandos…

## Estado actual (1 de octubre)

| Pieza | Dónde está | Estado |
|---|---|---|
| Estructura común (`shared/`, `sample_data/`, `benchmarks/`, `docker-compose.yml`, `README.md`, `.gitignore`) | rama `GO` | Hecha |
| Go | rama `GO`, carpeta `go/` | Completo: pipeline, dumps y benchmarks (N=100 y 250 medidos) |
| Java | rama `JAVA`, carpeta `Stage-1-JAVA 3/bigdatajava/` | Pipeline completo con las reglas del SPEC v2. Faltan los benchmarks, el comando `books` y `--sample` |
| Python | sin rama en el remoto | Pendiente de subir |
| `benchmarks/plots.py` | — | Pendiente (Python, según el reparto del SPEC) |

## Lo que necesita cada implementación para encajar

1. **Carpeta propia en la raíz**: `python/` y `java/`, igual que `go/`.
2. **Entradas comunes**: leer `../shared/` y `../cache/<id>.txt`; con `--sample`, `../sample_data/` y `../shared/book_ids_sample.txt`. Java tiene su propia `shared/book_ids.txt` (la lista antigua de 20 libros), que hay que borrar para usar la común.
3. **Salidas** en `../output/<lang>/` con las rutas del SPEC, y dumps en `output/<lang>/dumps/<índice>/`. Después: `python3 benchmarks/compare_outputs.py`, que tiene que decir que todo coincide.
4. **Interfaz de línea de comandos** del §17. A Java le faltan `books` (consultas de metadatos, que pide el enunciado), los filtros `--author`/`--title`/`--language` y `--sample`.
5. **Benchmarks** con la herramienta de cada lenguaje (JMH, pytest-benchmark) y las definiciones del §11. Resultados en `benchmarks/results/<lang>.csv` y salida cruda en `benchmarks/raw/<lang>/`. `go.csv` sirve de ejemplo de formato, nombres de métrica y unidades.

## Antes de medir

1. Una persona descarga los 1.050 primeros libros de `shared/book_ids.txt` a `cache/` y la comparte con los demás. Por ejemplo, desde `go/`: `go run . download --n=1050`, que tarda unos 20–25 minutos.
2. Los tres generan sus dumps con los mismos libros y `compare_outputs.py` confirma que coinciden.
3. Se elige la máquina (Linux, §11) y se ejecutan los tres lenguajes en ella, con el portátil enchufado y sin otros programas.

## Montaje en git

La rama `GO` ya tiene la estructura final. `GO` y `main` no comparten historia, y `JAVA` sí sale de `main`. Para juntarlo todo en `main` conservando la historia de cada uno (el enunciado la evalúa):

```bash
# 1. Java mueve su proyecto a java/ en su rama y quita su copia de shared/
git checkout JAVA
git mv "Stage-1-JAVA 3/bigdatajava" java
git mv "Stage-1-JAVA 3/README.md" java/README.md
git rm -r "Stage-1-JAVA 3/shared"
git commit -m "java: mover el proyecto a java/"

# 2. main incorpora la rama GO. El único conflicto es README.md: se queda el de GO
git checkout main
git merge --allow-unrelated-histories GO
git checkout --theirs README.md && git add README.md
git commit

# 3. main incorpora JAVA (y después Python, con el mismo proceso)
git merge JAVA
git push origin main
```

Por último, el enunciado exige que el repositorio se llame exactamente `https://github.com/<group_name>/stage_1`. Hoy se llama `Stage-1`; se renombra en GitHub, en *Settings → Repository name*, y las URL antiguas siguen redirigiendo.
