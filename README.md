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
│   ├── compare_outputs.py  verificación de equivalencia entre lenguajes
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

- Los de cada lenguaje, que están en su README (Java 17+ con Maven; Go 1.26+ con gcc).
- **Python 3.9+**, para la verificación de equivalencia (solo usa la biblioteca estándar).
- **Docker**, solo para el índice en MongoDB.

MongoDB se levanta desde la raíz:

```bash
docker compose up -d      # mongodb://localhost:27017, sin autenticación
```

## Prueba rápida

Con los 20 libros de `sample_data/`, sin red ni MongoDB. Por ejemplo, en Go:

```bash
cd go
go run . pipeline --sample        # descarga simulada desde sample_data/, datalake, metadatos e índice
go run . query                    # consultas de shared/queries.txt
go run . books --author="Lewis Carroll"
go run . dump                     # dumps canónicos en output/go/dumps/json/
```

Los comandos equivalentes de Python y Java están en sus README.

## Dataset

- `shared/book_ids.txt` tiene los 1.000 primeros IDs del [catálogo oficial de Gutenberg](https://www.gutenberg.org/cache/epub/feeds/pg_catalog.csv) que son textos en inglés (`Type = Text`, `Language = en`), en orden ascendente. Para los experimentos de escalabilidad se usan los N primeros, con N ∈ {100, 250, 500, 1000}.
- Los libros se descargan de `https://www.gutenberg.org/cache/epub/{id}/pg{id}.txt` con 1 s de espera entre peticiones. Para no saturar Gutenberg, una sola persona descarga el dataset completo a `cache/<id>.txt` y lo comparte; el resto de ejecuciones leen de `cache/` en modo offline.

## Verificación de equivalencia

Cada implementación exporta un dump canónico de los metadatos y del índice:

- `metadata.tsv`: `book_id\ttitle\tauthor\tlanguage`, ordenado por ID, con los NULL como cadena vacía.
- `index.tsv`: `term\tid1,id2,id3`, ordenado por término.

Ambos van en `output/<lang>/dumps/<índice>/`. El script compara todos los dumps entre sí: los de los tres lenguajes y los de las tres estructuras de índice.

```bash
python3 benchmarks/compare_outputs.py
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
