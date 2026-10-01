# Stage 1 – Implementación en Java

Implementación en Java de la capa de datos del buscador (Big Data, GCID – ULPGC). Tiene la
misma funcionalidad, comandos, formatos de fichero y salida por consola que las versiones de
Python y Go, para que los benchmarks comparen solo el lenguaje y la estructura de datos.

## Requisitos

- Java 17 o superior y Maven 3.8 o superior
- Conexión a Internet para descargar de Project Gutenberg (o `--offline` con `cache/`, o
  `--sample` con los 20 libros de `sample_data/`)
- MongoDB solo si se usa `--index=mongo` (por defecto `mongodb://localhost:27017`); se levanta
  desde la raíz con `docker compose up -d`

Los comandos se ejecutan desde esta carpeta (`java/`); `--root` (por defecto `..`) apunta a la
raíz del repositorio, que contiene `shared/`, `sample_data/`, `cache/` y `output/`. Todo lo que
genera esta implementación va a `output/java/`.

## Prueba rápida con el dataset de muestra

Sin red ni MongoDB:

```bash
cd java
mvn -q package -DskipTests
java -jar target/stage1-java.jar pipeline --sample
java -jar target/stage1-java.jar query
java -jar target/stage1-java.jar books --author="Lewis Carroll"
java -jar target/stage1-java.jar dump
python ../benchmarks/compare_outputs.py
```

## Compilación y ejecución

```bash
cd java
mvn -q compile
mvn -q exec:java -Dexec.args="pipeline --datalake=time --index=json"

# O con un jar autocontenido (recomendado para los benchmarks: sin el arranque de Maven)
mvn -q package
java -jar target/stage1-java.jar pipeline --datalake=range --index=folders
```

Ejemplos:

```bash
java -jar target/stage1-java.jar pipeline --n=50                 # primeros 50 IDs de la lista
java -jar target/stage1-java.jar download 1342 84 11             # libros concretos
java -jar target/stage1-java.jar pipeline --offline --index=mongo
java -jar target/stage1-java.jar index --rebuild --index=folders # reconstruir un índice
java -jar target/stage1-java.jar query pride prejudice
java -jar target/stage1-java.jar query                           # consultas de shared/queries.txt
java -jar target/stage1-java.jar query war peace --language=en
java -jar target/stage1-java.jar books 1342 84                   # metadatos y ruta del body
java -jar target/stage1-java.jar dump --index=json
java -jar target/stage1-java.jar lookup 1342 --datalake=time
java -jar target/stage1-java.jar status
```

| Comando | Descripción |
|---|---|
| `pipeline [ids...]` | Ciclo completo: indexa lo pendiente, descarga los libros nuevos y los indexa por lotes |
| `download [ids...]` | Guarda en el datalake los libros que falten (sin IDs: los de la lista) |
| `index` | Indexa los libros descargados que aún no están indexados |
| `query [texto...]` | Busca en el índice (AND de todos los términos); sin texto, `shared/queries.txt` |
| `books [ids...]` | Consulta los metadatos por ID o con `--author`, `--title` y `--language` |
| `dump` | Exporta `metadata.tsv` e `index.tsv` en formato canónico |
| `lookup <ids...>` | Localiza header y body de cada libro |
| `status` | Resumen del estado del pipeline |
| `recover` | Alinea los ficheros de control con el datalake |

| Opción | Por defecto | Descripción |
|---|---|---|
| `--datalake=time\|book\|range` | `book` | Estructura del datalake |
| `--index=json\|folders\|mongo` | `json` | Estructura del índice invertido |
| `--offline` | – | Leer los libros de `cache/` en vez de descargarlos |
| `--sample` | – | Dataset de muestra: `sample_data/` y `book_ids_sample.txt`, sin red |
| `--n=N` | `0` (todos) | Usar solo los primeros N IDs de la lista |
| `--batch=N` | `100` | Libros por lote de indexación (0 = todos) |
| `--rebuild` | – | Con `index`: vaciar índice y metadatos y reconstruirlos |
| `--author`, `--title`, `--language` | – | Con `books` y `query`: filtran por metadatos (coincidencia exacta) |
| `--ids=FICHERO` | `<root>/shared/book_ids.txt` | Lista de IDs |
| `--delay=1s` | `1s` | Espera entre peticiones a Gutenberg (`500ms`, `2s`...) |
| `--mongo=URI` | `mongodb://localhost:27017` | Servidor de MongoDB |
| `--root=DIR` | `..` | Carpeta con `shared/`, `cache/` y `output/` |

## Código

```
java/src/main/java/es/ulpgc/bigdata/
├── Main.java                  CLI
├── ingestion/                 fuente de libros y separación header/body
│   ├── BookSource.java, GutenbergSource.java, CacheSource.java
│   ├── BookSplitter.java, Book.java, BookUnavailableException.java
├── datalake/                  las tres organizaciones del datalake
│   ├── DatalakeStore.java     interfaz común y factoría
│   ├── TimeBasedDatalake.java, BookBasedDatalake.java, RangeDatalake.java
│   └── BookLocation.java
├── metadata/                  datamart de metadatos (SQLite)
│   ├── MetadataExtractor.java, MetadataDatabase.java, BookMetadata.java, MetadataFilter.java
├── tokenizer/Tokenizer.java   normalización común a los tres lenguajes
├── index/                     las tres estructuras del índice invertido
│   ├── InvertedIndex.java     interfaz común y factoría
│   ├── JsonIndex.java, FolderIndex.java, MongoIndex.java
│   └── Postings.java          merge, intersección, búsqueda y dump
├── control/                   capa de control
│   ├── ControlPipeline.java, ControlState.java, BookList.java
│   ├── BookIds.java, RecoveryReport.java, Summary.java
└── util/                      FileUtils (escritura atómica), Text (trim/lower como Go)
```

Tests: `mvn test`. El de MongoDB solo se ejecuta si está definida `MONGO_URI`.

## Reglas comunes con Python y Go

**Ingesta.** Se normalizan `\r\n` a `\n` y se buscan, sin distinguir mayúsculas,
`*** START OF (THE|THIS) PROJECT GUTENBERG EBOOK` y `*** END OF ...` (el espacio tras `***` es
opcional). El header es todo lo anterior al START; el body, lo que hay entre el final de la
línea START y el END. Un 404 o la falta de marcadores descartan el libro para siempre
(`failed_books.txt`); cualquier otro error detiene la ejecución, que se puede reanudar.

**Metadatos.** Primera línea que cumple `^Title:`, `^Author:` y `^Language:` (sin distinguir
mayúsculas). English/Spanish/French/German pasan a `en/es/fr/de`; el resto, a minúsculas. Los
campos ausentes se guardan como `NULL`. Tabla `books(book_id, title, author, language,
body_path)` con índices en `author` y `title`; `body_path` es relativo a `output/java`. Se
consulta por ID (devuelve la fila con `body_path`) y por título, autor e idioma con igualdad
exacta, combinados con AND (comando `books`).

**Tokenizador.** Byte a byte: A-Z pasa a minúsculas, un término es una secuencia `[a-z]+`, se
descartan los de menos de 2 letras y las stopwords, y cada término cuenta una vez por libro.
Las consultas pasan por el mismo tokenizador.

**Dumps.** `metadata.tsv` (`book_id\ttitle\tauthor\tlanguage`, por ID) e `index.tsv`
(`término\tid1,id2,...`, por término). Si los tres lenguajes procesan los mismos libros, sus
dumps tienen que ser idénticos:

```bash
python benchmarks/compare_outputs.py                 # compara los dumps que haya
python benchmarks/check_equivalence.py --sample      # ejecuta los tres lenguajes y compara todo
```

## Estructuras del datalake

| Tipo | Ruta | Lookup |
|---|---|---|
| `time` | `datalake_time/YYYYMMDD/HH/<id>.header.txt` y `<id>.body.txt` | Índice auxiliar `_locations.tsv` (id → carpeta) |
| `book` | `datalake_book/<id>/header.txt` y `body.txt` | Directo |
| `range` | `datalake_range/1000-1999/<id>.header.txt` y `<id>.body.txt` | Directo, carpeta `id / 1000` |

## Estructuras del índice invertido

| Tipo | Dónde | Actualización |
|---|---|---|
| `json` | `datamarts/inverted_index.json`, claves ordenadas y sin espacios | Carga, fusiona y reescribe el fichero entero |
| `folders` | `datamarts/inverted_index/<LETRA>/<término>.txt`, un ID por línea | Solo reescribe los términos afectados |
| `mongo` | Colección `search_engine.inverted_index_java`, `{term, postings}` con índice único en `term` | `$addToSet` por término en un bulk write |

## Recuperación ante interrupciones

- Todos los ficheros se escriben en `.tmp` y se renombran; el body se escribe después del
  header y su existencia marca el libro como completo.
- En `time`, la carpeta se registra en `_locations.tsv` antes de escribir, así que al reanudar
  el libro vuelve a la misma carpeta aunque haya cambiado la hora.
- Un libro se registra como descargado después de guardarlo, y como indexado después de
  escribir los dos datamarts. Repetir un lote no duplica nada (`INSERT OR REPLACE` y fusión
  de postings).
- Una última línea de control sin `\n` (append cortado) se descarta y se recorta.
- Al arrancar se borran los `.tmp` y se reconcilia el control con el datalake. El control es
  único: si el índice o los metadatos elegidos están vacíos, los libros se vuelven a indexar.

## Benchmarks

Pendientes: se harán con **JMH** (`jmh-core` + `jmh-generator-annprocess` 1.35) siguiendo las
definiciones del SPEC §11, una vez confirmada la equivalencia de los dumps.
