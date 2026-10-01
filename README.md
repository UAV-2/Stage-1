# Stage 1 — Data layer de un buscador

Proyecto de Big Data (Grado en Ciencia e Ingeniería de Datos, ULPGC). Este stage construye la capa de datos de un buscador sobre libros de [Project Gutenberg](https://www.gutenberg.org/):

- **Datalake**: los libros descargados, separados en cabecera y cuerpo. Hay tres organizaciones de carpetas: por fecha y hora, por libro y por rangos de IDs.
- **Datamarts**: los metadatos de cada libro en SQLite y el índice invertido (término → libros) en tres estructuras: un JSON monolítico, una carpeta por letra con un fichero por término, y MongoDB.
- **Capa de control**: ficheros que registran qué libros están descargados e indexados, para reanudar tras un corte sin duplicar ni perder libros.

El pipeline está implementado en **Python, Java y Go** con exactamente las mismas reglas, definidas en [`SPEC.md`](SPEC.md). Así los benchmarks comparan lenguajes y estructuras, no diferencias de funcionalidad.

## Estructura del repositorio

```
├── README.md
├── SPEC.md                 reglas comunes a los tres lenguajes
├── docker-compose.yml      MongoDB
├── shared/                 única fuente de verdad para los tres lenguajes
│   ├── book_ids.txt        dataset: 1.000 IDs de libros en inglés, en orden ascendente
│   ├── book_ids_sample.txt los 20 primeros, para la prueba rápida
│   ├── stopwords.txt
│   └── queries.txt
├── sample_data/            los 20 libros de muestra ya descargados
├── python/                 implementación en Python
├── java/                   implementación en Java
├── go/                     implementación en Go
├── benchmarks/
│   ├── compare_outputs.py  compara los dumps de equivalencia entre lenguajes y estructuras
│   ├── check_equivalence.py ejecuta los tres lenguajes con los mismos libros y lo compara todo
│   ├── results/<lang>.csv  resultados con formato común
│   └── raw/<lang>/         salida cruda de cada herramienta
├── cache/                  libros crudos descargados (no se sube)
└── output/<lang>/          todo lo que genera cada implementación (no se sube)
    ├── datalake_time/  datalake_book/  datalake_range/
    ├── datamarts/          metadata.db, inverted_index.json, inverted_index/
    ├── control/            downloaded_books.txt, indexed_books.txt
    └── dumps/<índice>/     metadata.tsv, index.tsv
```

## Implementaciones

| Lenguaje | Carpeta | Instrucciones |
|---|---|---|
| Python | [`python/`](python/) | [`python/README.md`](python/README.md) |
| Java | [`java/`](java/) | [`java/README.md`](java/README.md) |
| Go | [`go/`](go/) | [`go/README.md`](go/README.md) |

Las tres leen `shared/` y `cache/` y escriben en `output/<lang>/`, así que se pueden ejecutar una detrás de otra sin pisarse.

## Requisitos

- Los de cada lenguaje, que están en su README: **Python 3.10+**, **Java 17+ con Maven** y **Go 1.26+ con gcc** (el driver de SQLite de Go usa cgo; en Windows, MinGW-w64).
- Los scripts de `benchmarks/` solo usan la biblioteca estándar de Python.
- **Docker**, solo para el índice en MongoDB.

MongoDB se levanta desde la raíz:

```bash
docker compose up -d      # mongodb://localhost:27017, sin autenticación
```

## Prueba rápida

Con los 20 libros de `sample_data/`, sin red ni MongoDB. Los tres lenguajes tienen los mismos comandos y opciones (SPEC §17):

```bash
cd python && python main.py pipeline --sample && python main.py dump && cd ..
cd java && mvn -q package -DskipTests && java -jar target/stage1-java.jar pipeline --sample && java -jar target/stage1-java.jar dump && cd ..
cd go && go run . pipeline --sample && go run . dump && cd ..
python benchmarks/compare_outputs.py      # los tres dumps tienen que coincidir
```

Después, en cualquiera de ellos: `query` (consultas de `shared/queries.txt`), `query whale`, `books --author="Lewis Carroll"`, `lookup 11`, `status`.

## Dataset

- `shared/book_ids.txt` tiene los 1.000 primeros IDs del [catálogo oficial de Gutenberg](https://www.gutenberg.org/cache/epub/feeds/pg_catalog.csv) que son textos en inglés (`Type = Text`, `Language = en`), en orden ascendente, sin los que no se pueden ingerir (SPEC §3): el 673 no tiene marcadores START/END y el 900 no existe (404), así que en su lugar están el 1066 y el 1067. Para los experimentos de escalabilidad se usan los N primeros, con N ∈ {100, 250, 500, 1000}.
- La lista se genera con `python/tools/build_dataset.py` (ver su ayuda); `--check` la compara con el catálogo sin tocarla.
- Los libros se descargan de `https://www.gutenberg.org/cache/epub/{id}/pg{id}.txt` con 1 s de espera entre peticiones. Para no saturar Gutenberg, una sola persona descarga el dataset completo a `cache/<id>.txt` y lo comparte; el resto de ejecuciones leen de `cache/` en modo offline.

## Verificación de equivalencia

Cada implementación exporta un dump canónico de los metadatos y del índice:

- `metadata.tsv`: `book_id\ttitle\tauthor\tlanguage`, ordenado por ID, con los NULL como cadena vacía.
- `index.tsv`: `term\tid1,id2,id3`, ordenado por término.

Ambos van en `output/<lang>/dumps/<índice>/`. `compare_outputs.py` compara todos los dumps entre sí: los de los tres lenguajes y los de las tres estructuras de índice.

```bash
python benchmarks/compare_outputs.py
```

`check_equivalence.py` hace la comprobación completa: ejecuta los tres lenguajes con los mismos libros en cada combinación de datalake e índice (borrando antes `output/<lang>/`) y compara los dumps, los ficheros del datalake byte a byte, los ficheros de control, el `body_path` de los metadatos, el `inverted_index.json` y el resultado de las consultas.

```bash
python benchmarks/check_equivalence.py --sample                     # 20 libros, 3 datalakes × 3 índices
python benchmarks/check_equivalence.py --n=1000 --datalakes=book     # desde cache/
```

Los benchmarks no se miden hasta que todos coinciden.

## Benchmarks

Cada experimento combina un dataset (N libros), un método (lenguaje × estructura) y una medida. Cada lenguaje usa la herramienta vista en clase, con la misma configuración: 5 iteraciones de calentamiento, 10 de medida y N parametrizado.

| Lenguaje | Herramienta | Comando |
|---|---|---|
| Python | pytest-benchmark | ver [`python/README.md`](python/README.md) |
| Java | JMH | ver [`java/README.md`](java/README.md) |
| Go | `testing` | `cd go && go run ./cmd/bench` |

Cada lenguaje deja sus resultados en `benchmarks/results/<lang>.csv` con este formato:

```
lang,component,structure,n_books,metric,mean,stddev,min,max,ci_low,ci_high,iterations,unit
```

Las métricas y cómo se mide cada una están en `SPEC.md` (§11) y, para Go, en [`go/README.md`](go/README.md#benchmarks). La salida cruda de cada herramienta se guarda en `benchmarks/raw/<lang>/`.
