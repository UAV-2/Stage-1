# Implementación en Python

Pipeline completo del Stage 1 en Python: descarga de Project Gutenberg, tres variantes de datalake, metadatos en SQLite, tres variantes de índice invertido, capa de control, consultas y dumps de equivalencia. Es la implementación de referencia del [`SPEC.md`](../SPEC.md): con los mismos datos produce exactamente la misma salida (ficheros y consola) que las versiones de Java y Go.

## Requisitos

- **Python 3.10** o superior. El pipeline con los índices `json` y `folders` solo usa la biblioteca estándar.
- `pip install -r requirements.txt` para el índice `mongo` (`pymongo`), los tests (`pytest`) y los benchmarks (`pytest-benchmark`).
- **MongoDB**, solo para el índice `mongo`. Se levanta desde la raíz con `docker compose up -d`.

Todos los comandos se ejecutan desde esta carpeta (`python/`). Las rutas se resuelven respecto a la raíz del repositorio (`--root`, por defecto `..`): los ficheros comunes están en `shared/`, los libros crudos en `cache/` y todo lo generado en `output/python/`.

## Prueba rápida con el dataset de muestra

El repositorio incluye 20 libros en `sample_data/` (los IDs de `shared/book_ids_sample.txt`), así que esto funciona sin red ni MongoDB:

```bash
cd python
python main.py pipeline --sample               # datalake book + índice json + metadatos
python main.py query                           # las consultas de shared/queries.txt
python main.py query whale
python main.py books --author="Lewis Carroll"  # metadatos: título, autor, idioma y ruta del body
python main.py dump                            # dumps de equivalencia en output/python/dumps/json/
python ../benchmarks/compare_outputs.py
```

## Comandos

| Comando | Qué hace |
|---|---|
| `pipeline [ids...]` | Ciclo completo de la capa de control: indexa lo pendiente, descarga los libros nuevos en el orden de la lista y los indexa por lotes |
| `download [ids...]` | Solo la ingesta: guarda en el datalake los libros que falten (sin IDs, los de `shared/book_ids.txt`) |
| `index` | Indexa los libros descargados que aún no están indexados |
| `query [texto...]` | Busca en el índice (AND entre términos); sin texto, ejecuta `shared/queries.txt` |
| `books [ids...]` | Consulta los metadatos por ID o con `--author`, `--title` y `--language` |
| `dump` | Exporta `metadata.tsv` e `index.tsv` a `output/python/dumps/<índice>/` |
| `lookup <ids...>` | Localiza el header y el body de cada libro en el datalake |
| `status` | Libros en el datalake, descargados, indexados, pendientes y descartados |
| `recover` | Alinea los ficheros de control con el contenido real del datalake |

| Opción | Por defecto | Descripción |
|---|---|---|
| `--datalake=time\|book\|range` | `book` | Estructura del datalake |
| `--index=json\|folders\|mongo` | `json` | Estructura del índice invertido |
| `--sample` | | Dataset de muestra: `sample_data/` y `book_ids_sample.txt`, sin red |
| `--offline` | | Lee los libros de `cache/` en vez de descargarlos |
| `--n=N` | todos | Usa solo los N primeros IDs de la lista |
| `--batch=N` | `100` | Libros por lote de indexación (`0` = todos de una vez) |
| `--rebuild` | | Con `index`: vacía el índice y los metadatos y los reconstruye |
| `--author`, `--title`, `--language` | | Con `books` y `query`: filtran por metadatos (coincidencia exacta) |
| `--ids=FICHERO` | `shared/book_ids.txt` | Otra lista de IDs |
| `--delay=1s` | `1s` | Espera entre peticiones a Gutenberg (formato de Go: `500ms`, `2s`…) |
| `--mongo=URI` | `mongodb://localhost:27017` | Servidor de MongoDB |
| `--root=DIR` | `..` | Raíz del repositorio |

Opciones e IDs se pueden escribir en cualquier orden.

### Ejemplos

```bash
python main.py download --n=100                          # descarga 100 libros (y los deja en cache/)
python main.py pipeline --offline --n=100 --datalake=time --index=folders
python main.py index --index=mongo                       # mismo dataset en otra estructura de índice
python main.py query war peace --language=en
python main.py lookup 11 1342 --datalake=time
python main.py index --rebuild                           # reconstruir índice y metadatos desde cero
```

## Estructura del código

```
python/
├── main.py                 CLI (python main.py <comando>)
├── requirements.txt
├── search_engine/
│   ├── cli.py              comandos y opciones (SPEC §17)
│   ├── ingestion.py        descarga (red o caché) y separación header/body
│   ├── datalake.py         variantes time, book y range
│   ├── metadata.py         extracción de metadatos y base SQLite
│   ├── tokenizer.py        reglas de tokenización (SPEC §7)
│   ├── index/              índice invertido json, folders y mongo; consultas y dump
│   ├── control.py          ficheros de control, pipeline y recuperación
│   ├── fileutil.py         escritura atómica y utilidades de ficheros
│   └── text.py             strip() y minúsculas con las reglas de Go
├── tools/build_dataset.py  genera shared/book_ids.txt desde el catálogo de Gutenberg
├── tests/                  pytest
└── bench/                  benchmarks: pytest-benchmark (test_*.py), métricas de script y run.py
```

## Decisiones de diseño

Son las mismas en los tres lenguajes (ver también el README de Go):

- **Escritura atómica.** Todo lo que se reescribe (ficheros del datalake, caché, índice JSON, ficheros del índice por carpetas, reescrituras del control) se escribe en `*.tmp` y se renombra con `os.replace`.
- **Orden de escritura.** Primero el header, después el body (su existencia marca el libro como completo) y, solo al final, la línea en `downloaded_books.txt`. En `time`, la carpeta del libro se registra en `_locations.tsv` antes de escribirlo.
- **Recuperación al arrancar.** Se borran los `.tmp`, el control se alinea con el datalake y, si el índice o los metadatos de la variante elegida están vacíos, se reindexa todo. Una línea de control cortada a mitad se descarta y se recorta.
- **Indexación por lotes.** Metadatos (`INSERT OR REPLACE`), índice (fusión de conjuntos) y, solo después, `indexed_books.txt`. Repetir un lote no duplica nada.
- **Mismas reglas de texto que Go.** `str.strip()` y `str.lower()` difieren de Go en casos límite, así que `text.py` implementa `strings.TrimSpace` y `strings.ToLower`. El tokenizador trabaja sobre bytes (`bytes.lower()` solo cambia A-Z) y la regex de metadatos usa `[\t\n\f\r ]` en lugar de `\s`, que en Python acepta más caracteres.
- **Bytes no UTF-8.** Los libros se leen con `surrogateescape`: un byte inválido se conserva tal cual al escribir el datalake, como en Go, en lugar de abortar.

## Tests

```bash
pytest                                                # sin MongoDB
MONGO_URI=mongodb://localhost:27017 pytest            # incluye la variante mongo
```

Cubren los mismos casos que los tests de Go y Java: separación, metadatos, tokenización, rutas de los datalakes, formato de los índices, recuperación tras cortes simulados e indexación por lotes.

## Benchmarks

Los micro-benchmarks usan **pytest-benchmark** (`bench/test_*.py`). Las métricas que no encajan en un micro-benchmark (recuperación, ficheros, disco, memoria y descarga) están en `bench/scripts.py`. `bench/run.py` lo lanza todo y genera `benchmarks/results/python.csv` con el formato común del SPEC; la salida cruda (JSON de pytest-benchmark y registros) queda en `benchmarks/raw/python/`.

```bash
pip install -r requirements.txt
python -m bench.run                          # micro-benchmarks y métricas de script, N ∈ {100, 250, 500, 1000}
python -m bench.run --only=download          # throughput de descarga: 5 × 50 libros desde la red
python -m bench.run --sizes=100,250          # solo algunos tamaños
python -m bench.run --reduce-folders-from=100
```

Necesita en `cache/` los N + 50 primeros libros de `shared/book_ids.txt` para el mayor N (`python main.py download --n=1050`) y, para las medidas de `mongo`, MongoDB levantado (si no responde, se saltan). Cada ejecución actualiza en el CSV las filas que mide y conserva las demás. Para los tres lenguajes a la vez: `python ../benchmarks/run_all.py`.

Configuración (SPEC §11): 5 rondas de calentamiento que se descartan y 10 medidas (`benchmark.pedantic`), y 5 repeticiones en las métricas de script. Lo que no forma parte de la medida (borrar la salida anterior, crear la base vacía, vaciar o preparar el índice) va en el `setup` de `pedantic`. En las medidas de microsegundos cada ronda hace 200 operaciones (200 IDs o autores al azar con semilla 42, o 200 consultas alternando las de `shared/queries.txt`), y el valor es la media por operación.

`--reduce-folders-from=N` baja a 1 + 3 rondas la construcción y la actualización del índice `folders` con N libros o más, y a 3 repeticiones la medida de memoria. Sirve para máquinas donde escribir cientos de miles de ficheros pequeños es muy lento (en Windows con el antivirus activo, una construcción con N=1000 tarda unos 5 minutos). La columna `iterations` del CSV refleja las iteraciones usadas.

| Métrica | Cómo se mide |
|---|---|
| `write_throughput` | Leer de `cache/`, separar y escribir N libros en un datalake vacío (libros/s) |
| `lookup_time` | Localizar header y body; media sobre 200 IDs al azar (µs) |
| `incremental_detect_time` | Abrir un datalake de N+50 libros y detectar los 50 que no están indexados (ms) |
| `recovery_ok` | Matar la ingesta 3 veces en puntos al azar, reanudar y comprobar; fracción de repeticiones correctas |
| `files_count`, `dirs_count` | Ficheros y carpetas del datalake con N libros, incluidos control e índice auxiliar |
| `metadata_insert_time` | Insertar los metadatos de N libros en una base vacía, en una transacción (ms) |
| `metadata_query_time_author`, `metadata_query_time_id` | Todos los libros de un autor / la fila de un libro con su body (µs) |
| `index_build_time` | Leer y tokenizar N libros y construir el índice en un lote (ms) |
| `query_time` | Una consulta de `shared/queries.txt` sobre el índice ya cargado (µs) |
| `update_time` | Abrir un índice de N libros y añadir 50 (ms); en JSON incluye cargar y reescribir el fichero |
| `disk_usage` | Bytes de los ficheros del índice; en MongoDB, `storageSize` tras `fsync` |
| `peak_memory` | Pico de memoria residente de un proceso nuevo que construye el índice (MB): `VmHWM` en Linux y el pico del working set en Windows |
| `download_throughput` | Descargar 50 libros de Gutenberg con 1 s entre peticiones (libros/s) |

En el setup de `update_time`, el índice vuelve a tener los N primeros libros antes de cada ronda: en `json` y `mongo` se vacía y se vuelve a cargar; en `folders` se deshace la actualización anterior (cada término afectado recupera sus postings o desaparece), porque reconstruirlo entero tarda minutos. El estado lógico es el mismo y no se mide.
