# Implementación en Go

Pipeline completo del Stage 1 en Go: descarga de Project Gutenberg, tres variantes de datalake, metadatos en SQLite, tres variantes de índice invertido, capa de control, consultas, dumps de equivalencia y benchmarks. Sigue las reglas de [`SPEC.md`](../SPEC.md), así que con los mismos datos produce la misma salida que las implementaciones de Python y Java.

## Requisitos

- **Go 1.26** o superior.
- **gcc**, porque el driver de SQLite (`github.com/mattn/go-sqlite3`) usa cgo. En Linux viene con `build-essential`/`gcc`; en Windows hace falta MinGW-w64.
- **MongoDB**, solo para el índice `mongo`. Se levanta desde la raíz con `docker compose up -d`.

Todos los comandos se ejecutan desde esta carpeta (`go/`). Las rutas se resuelven respecto a la raíz del repositorio (`--root`, por defecto `..`): los ficheros comunes están en `shared/`, los libros crudos en `cache/` y todo lo generado en `output/go/`.

## Prueba rápida con el dataset de muestra

El repositorio incluye 20 libros en `sample_data/` (los 20 primeros IDs de `shared/book_ids_sample.txt`), así que esto funciona sin red ni MongoDB:

```bash
cd go
go run . pipeline --sample               # datalake book + índice json + metadatos
go run . query                           # las consultas de shared/queries.txt
go run . query whale
go run . books --author="Lewis Carroll"  # metadatos: título, autor, idioma y ruta del body
go run . dump                            # dumps de equivalencia en output/go/dumps/json/
python3 ../benchmarks/compare_outputs.py
```

## Comandos

| Comando | Qué hace |
|---|---|
| `pipeline [ids...]` | Ciclo completo de la capa de control: indexa lo pendiente, descarga los libros nuevos en el orden de la lista y los indexa por lotes |
| `download [ids...]` | Solo la ingesta: guarda en el datalake los libros que falten (sin IDs, los de `shared/book_ids.txt`) |
| `index` | Indexa los libros descargados que aún no están indexados |
| `query [texto...]` | Busca en el índice (AND entre términos); sin texto, ejecuta `shared/queries.txt` |
| `books [ids...]` | Consulta los metadatos por ID o con `--author`, `--title` y `--language` |
| `dump` | Exporta `metadata.tsv` e `index.tsv` a `output/go/dumps/<índice>/` |
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
| `--delay=1s` | `1s` | Espera entre peticiones a Gutenberg |
| `--mongo=URI` | `mongodb://localhost:27017` | Servidor de MongoDB |
| `--root=DIR` | `..` | Raíz del repositorio |

Opciones e IDs se pueden escribir en cualquier orden.

### Ejemplos

```bash
go run . download --n=100                          # descarga 100 libros (y los deja en cache/)
go run . pipeline --offline --n=100 --datalake=time --index=folders
go run . index --index=mongo                       # mismo dataset en otra estructura de índice
go run . query war peace --language=en
go run . lookup 11 1342 --datalake=time
go run . index --rebuild                           # reconstruir índice y metadatos desde cero
```

## Estructura del código

```
go/
├── main.go             CLI
├── bench_test.go       micro-benchmarks del SPEC §11
├── cmd/bench/          lanza todos los benchmarks y genera el CSV
└── internal/
    ├── ingestion/      descarga (red o caché) y separación header/body
    ├── datalake/       variantes time, book y range
    ├── metadata/       extracción de metadatos y base SQLite
    ├── tokenizer/      reglas de tokenización (SPEC §7)
    ├── index/          índice invertido json, folders y mongo; consultas y dump
    ├── control/        ficheros de control, pipeline y recuperación
    ├── benchkit/       preparación de datos común a los benchmarks
    └── fileutil/       escritura atómica y utilidades de ficheros
```

## Decisiones de diseño

- **Escritura atómica.** Todo lo que se reescribe (ficheros del datalake, caché, índice JSON, ficheros del índice por carpetas, reescrituras del control) se escribe en `*.tmp` y se renombra. Si el proceso se corta, nunca queda un fichero final a medias.
- **Orden de escritura.** Primero el header, después el body (su existencia marca el libro como completo) y, solo al final, la línea en `downloaded_books.txt`. En `time`, la carpeta del libro se registra en `_locations.tsv` antes de escribirlo: si se reanuda en otra hora, el libro vuelve a su carpeta y no queda duplicado.
- **Recuperación al arrancar.** Se borran los `.tmp` y el control se alinea con el datalake: los libros escritos pero sin registrar se registran, y los registrados sin libro se vuelven a pedir. Una línea de control cortada a mitad se descarta. Esto se ha probado matando el proceso con `kill -9` en mitad de la ingesta y de la indexación.
- **Indexación por lotes.** Cada lote se lleva a los metadatos y al índice, y solo después se marca en `indexed_books.txt`. Si se corta, el lote se repite entero; metadatos (`INSERT OR REPLACE`) e índice (fusión de conjuntos) admiten la repetición sin duplicar nada.
- **Un único `control/`**, como dice el SPEC, para todas las variantes. Si se cambia a una variante de índice vacía, los libros ya descargados se reindexan solos; si se cambia de datalake, el control se realinea con el nuevo.
- **Libros descartados.** Un libro sin marcadores START/END o que no existe se anota en `control/failed_books.txt` para no volver a pedirlo (SPEC §4).

## Benchmarks

Los micro-benchmarks usan el paquete estándar `testing` (`bench_test.go`). Las métricas que no encajan en un micro-benchmark (descarga, recuperación, ficheros, disco y memoria) las mide `cmd/bench`, que además lanza los micro-benchmarks y genera `benchmarks/results/go.csv` con el formato común del SPEC.

```bash
go run ./cmd/bench                     # todo menos la descarga, N ∈ {100, 250, 500, 1000}
go run ./cmd/bench --only=download     # throughput de descarga: 5 × 50 libros desde la red
go run ./cmd/bench --sizes=100,250     # solo algunos tamaños
```

Necesita en `cache/` los N+50 primeros libros de `shared/book_ids.txt` para el mayor N (`go run . download --n=1050` los descarga) y, para las medidas de `mongo`, MongoDB levantado (si no responde, esas medidas se omiten). Cada ejecución actualiza en el CSV las filas que mide y conserva las demás. La salida cruda queda en `benchmarks/raw/go/`, junto a `environment.txt` con la máquina y la configuración.

Con N=100 y 250 tarda unos 40 minutos en un portátil con un i5 de 13.ª generación; con los cuatro tamaños, calcula entre 2 y 3 horas. Lo que más tarda es el índice `folders`, que escribe un fichero por término. Conviene lanzarla con el portátil enchufado y sin otros programas abiertos.

| Métrica | Cómo se mide |
|---|---|
| `write_throughput` | Leer de `cache/`, separar y escribir N libros en un datalake vacío (libros/s) |
| `lookup_time` | Localizar header y body; media sobre 200 IDs al azar con semilla 42 (µs) |
| `incremental_detect_time` | Abrir un datalake de N+50 libros y detectar los 50 que no están indexados (ms) |
| `recovery_ok` | Matar la ingesta 3 veces en puntos al azar, reanudar y comprobar que no hay duplicados ni pérdidas; fracción de repeticiones correctas |
| `files_count`, `dirs_count` | Ficheros y carpetas del datalake con N libros, incluidos control e índice auxiliar |
| `metadata_insert_time` | Insertar los metadatos de N libros en una base vacía, en una transacción (ms) |
| `metadata_query_time_author`, `metadata_query_time_id` | Todos los libros de un autor / la fila de un libro con la ruta de su body (µs) |
| `index_build_time` | Leer y tokenizar N libros y construir el índice desde cero en un lote (ms) |
| `query_time` | Una consulta de `shared/queries.txt` sobre el índice ya abierto (µs) |
| `update_time` | Abrir un índice de N libros y añadir 50 (ms); en JSON incluye cargar y reescribir el fichero |
| `disk_usage` | Bytes de los ficheros del índice; en MongoDB, `storageSize` tras forzar un checkpoint |
| `peak_memory` | Pico de memoria residente (`VmHWM`) de un proceso nuevo que construye el índice (MB) |
| `download_throughput` | Descargar 50 libros de Gutenberg con 1 s entre peticiones (libros/s) |

Las definiciones exactas, comunes a los tres lenguajes, están en `SPEC.md` §11. Configuración: 5 iteraciones de calentamiento que se descartan y 10 medidas (`-count=15`), y 5 repeticiones en las métricas de script. Las medidas que tardan segundos se hacen con una operación por iteración (`-benchtime=1x`); el resto, con el segundo por iteración que usa Go por defecto. Lo que no forma parte de la medida (borrar la salida anterior, preparar el datalake o el índice de partida) queda fuera del cronómetro con `b.StopTimer()`.

Al interpretar los resultados hay que tener en cuenta:

- `disk_usage` suma el tamaño de los ficheros. En `folders` el espacio real en disco es bastante mayor, porque cada término ocupa al menos un bloque del sistema de ficheros.
- `peak_memory` es el pico de memoria residente del proceso de Go, que se lee de `/proc`, así que la medida es válida en Linux (en otro sistema se usa la memoria del runtime y la salida cruda lo indica). En `mongo` no incluye la del servidor.
- `metadata_query_time` se reporta en dos filas, por autor y por ID, porque el SPEC pide medir los dos casos.

## Tests

```bash
go test ./...                                                      # sin MongoDB
MONGO_URI=mongodb://localhost:27017 go test ./internal/index/      # incluye la variante mongo
```

Los tests cubren las reglas del SPEC (separación, rutas de los datalakes, metadatos, tokenización, formato de los índices) y la recuperación tras cortes simulados.
