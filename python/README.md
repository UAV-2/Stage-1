# Implementación en Python

Pipeline completo del Stage 1 en Python: descarga de Project Gutenberg, tres variantes de datalake, metadatos en SQLite, tres variantes de índice invertido, capa de control, consultas y dumps de equivalencia. Es la implementación de referencia del [`SPEC.md`](../SPEC.md): con los mismos datos produce exactamente la misma salida (ficheros y consola) que las versiones de Java y Go.

## Requisitos

- **Python 3.10** o superior. El pipeline con los índices `json` y `folders` solo usa la biblioteca estándar.
- `pip install -r requirements.txt` para el índice `mongo` (`pymongo`) y los tests (`pytest`).
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
└── tests/                  pytest
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

Pendientes: se harán con **pytest-benchmark** siguiendo las definiciones del SPEC §11, una vez confirmada la equivalencia de los dumps.
