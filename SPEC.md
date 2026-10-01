# SPEC — Stage 1: Data Layer del buscador

Documento de acuerdos del grupo. **Si algo no está aquí, se decide entre los tres y se añade antes de programarlo.**
**Versión 2 (1 de octubre):** cierra los huecos detectados al integrar Go y Java. Los cambios están resumidos en el §18 y quedan aprobados cuando los tres den el OK.
El objetivo es que las tres implementaciones produzcan **exactamente la misma salida** con los mismos datos, para que los benchmarks comparen lenguajes y estructuras, no diferencias de funcionalidad.

---

## 1. Equipo y reparto

| Persona | Lenguaje | Tarea transversal |
|---|---|---|
| `<Nombre A>` | Python (implementación de referencia) | Script común de benchmark + gráficas |
| `<Nombre B>` | Java | MongoDB en Docker + README de instalación y ejecución |
| `<Nombre C>` | Go | Informe PDF (estructura, arquitectura, diagramas) |

- Cada persona implementa **todo el pipeline** en su lenguaje: descarga, 3 datalakes, metadatos, 3 índices, control y consultas.
- Python va ligeramente por delante: ante cualquier duda de comportamiento, **la salida de Python es la referencia** (salvo que se demuestre que tiene un bug).
- Conclusiones y análisis del informe: entre los tres.

Nombre del grupo: `<group_name>` → repo: `https://github.com/<group_name>/stage_1`

---

## 2. Estructura del repositorio

```
stage_1/
├── README.md
├── SPEC.md
├── docker-compose.yml          # MongoDB
├── shared/                     # ÚNICA fuente de verdad para los tres lenguajes
│   ├── book_ids.txt            # dataset fijo
│   ├── book_ids_sample.txt     # dataset pequeño para que el profesor pruebe
│   ├── stopwords.txt
│   └── queries.txt
├── sample_data/                # libros de muestra ya descargados (para el README)
├── cache/                      # copia local de los .txt crudos (NO se sube a Git)
├── python/
├── java/
├── go/
├── output/                     # salidas generadas (NO se sube a Git)
│   └── <lang>/
│       ├── datalake_time/
│       ├── datalake_book/
│       ├── datalake_range/
│       ├── datamarts/
│       ├── control/
│       └── dumps/<índice>/     # dumps canónicos (§12)
└── benchmarks/
    ├── results/                # CSV de cada lenguaje
    ├── raw/<lang>/             # salida cruda de cada herramienta
    ├── compare_outputs.py      # verificación de equivalencia
    └── plots.py
```

Cada implementación se ejecuta desde su carpeta y recibe la raíz del repositorio con `--root` (por defecto `..`).

`cache/` y `output/` van en `.gitignore`.

---

## 3. Dataset

- `shared/book_ids.txt`: un ID por línea, ordenado ascendentemente. Contiene los **1.000 primeros IDs** del catálogo oficial ([`pg_catalog.csv`](https://www.gutenberg.org/cache/epub/feeds/pg_catalog.csv)) con `Type = Text` y `Language = en` exactamente.
- Al leer cualquier lista de IDs se ignoran las líneas vacías y las que empiezan por `#`.
- `shared/book_ids_sample.txt`: los primeros 20 IDs del anterior. Esos 20 libros, ya descargados, están en `sample_data/` para la prueba rápida.
- Escalabilidad: se usan los **primeros N** IDs del fichero, con N ∈ {100, 250, 500, 1000}. Los benchmarks de actualización añaden los 50 siguientes, así que `cache/` tiene que tener los **1.050 primeros**.
- Se excluyen los libros sin marcadores START/END o sin idioma inglés (se filtran **una vez** al crear la lista, no en cada ejecución). Si al descargar uno no los cumple, se quita de la lista y se añade al final el siguiente ID del catálogo.
- `shared/stopwords.txt`: la lista de stopwords en inglés de NLTK, sin las formas con apóstrofo (153 palabras).

### Descargas y Gutenberg

- Para no saturar a Gutenberg: **solo una persona descarga el dataset completo** a `cache/<id>.txt` y lo comparte (Drive, zip, etc.).
- El benchmark de *throughput de descarga* se hace con **50 libros** por lenguaje y una espera de **1 s entre peticiones**. Se reporta aparte.
- El resto de benchmarks leen de `cache/` (modo `--offline`), para medir escritura/split sin depender de la red.

---

## 4. Descarga y separación header/body

URL: `https://www.gutenberg.org/cache/epub/{id}/pg{id}.txt`

Marcadores (regex, **case-insensitive**, porque hay libros antiguos con "THIS"):

```
START: \*\*\* ?START OF (THE|THIS) PROJECT GUTENBERG EBOOK[^\n]*\n
END:   \*\*\* ?END OF (THE|THIS) PROJECT GUTENBERG EBOOK
```

- `header` = todo lo anterior al START, con `strip()`.
- `body` = entre el final de la línea START y el END, con `strip()`. El footer se descarta.
- Codificación: leer y escribir siempre en **UTF-8**. Saltos de línea: normalizar `\r\n` → `\n` antes de separar.
- `strip()` quita los espacios en blanco Unicode del principio y del final, como `strings.TrimSpace` de Go. El `str.strip()` de Python además quita `\x1c`–`\x1f`, pero ningún libro del dataset los tiene en esas posiciones.
- El END es su primera aparición en el texto. Si queda antes del final de la línea START, se trata como si faltaran los marcadores.
- Si faltan los marcadores o Gutenberg responde 404 → el libro se descarta: se anota en `control/failed_books.txt`, no se escribe nada y no se vuelve a pedir. Cualquier otro error (red, HTTP 5xx) detiene la ejecución, que después se reanuda.

---

## 5. Datalake: 3 variantes

| Variante | Ruta |
|---|---|
| `time` | `datalake_time/YYYYMMDD/HH/<id>.header.txt` y `<id>.body.txt` |
| `book` | `datalake_book/<id>/header.txt` y `body.txt` |
| `range` | `datalake_range/<inicio>-<fin>/<id>.header.txt` y `<id>.body.txt`, con rangos de 1000 (`0-999`, `1000-1999`, …) |

- Fecha/hora en **hora local de la máquina**, formato 24 h.
- **Escritura atómica**: escribir a `*.tmp` y renombrar al final. Así, si el proceso se corta, no quedan ficheros a medias (necesario para la métrica de recuperación).
- Orden de escritura: header, después body (su existencia marca el libro como completo) y, al final, la línea en `downloaded_books.txt`. Un libro está en el datalake si existen sus dos ficheros.
- Para localizar un libro en `time` hace falta saber dónde está: se mantiene un índice auxiliar `datalake_time/_locations.tsv` (`id\tYYYYMMDD/HH`). Esto cuenta como *overhead* en el benchmark. La línea se añade **antes** de escribir los ficheros: si se reanuda en otra hora, el libro vuelve a su carpeta y no queda duplicado.

---

## 6. Metadatos

Regex sobre el header, **línea a línea, case-insensitive**, primera coincidencia:

| Campo | Regex |
|---|---|
| title | `^Title:\s*(.+)$` |
| author | `^Author:\s*(.+)$` |
| language | `^Language:\s*(.+)$` |

- Las líneas se separan solo por `\n` (`header.split("\n")`, no `splitlines()`).
- Valores: `strip()`. Si no existe → `NULL` (no cadena vacía). Cuenta la primera línea que casa: si su valor queda vacío tras `strip()`, el campo es `NULL` y no se sigue buscando.
- Títulos que continúan en la línea siguiente (indentada): **se ignora la continuación** (simplificación acordada).
- `language` se normaliza a código con coincidencia **exacta**: `English` → `en`, `Spanish` → `es`, `French` → `fr`, `German` → `de`; cualquier otro valor (incluido `english`) → minúsculas tal cual.

Esquema SQLite (`datamarts/metadata.db`):

```sql
CREATE TABLE IF NOT EXISTS books (
    book_id   INTEGER PRIMARY KEY,
    title     TEXT,
    author    TEXT,
    language  TEXT,
    body_path TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_books_author ON books(author);
CREATE INDEX IF NOT EXISTS idx_books_title  ON books(title);
```

`body_path` = ruta al body relativa a `output/<lang>/`, con `/` como separador (p. ej. `datalake_book/1342/body.txt`).

Inserción con `INSERT OR REPLACE`, para que repetir un lote tras un corte no duplique filas. Consultas que se exponen (enunciado §4.1): por `book_id`, que devuelve la fila con `body_path`, y por `title`, `author` y `language` con igualdad exacta (se combinan con AND).

---

## 7. Tokenización (crítico: debe ser idéntica en los tres)

Sobre el **body**:

1. Pasar a minúsculas **solo A–Z → a–z** (no usar funciones Unicode del lenguaje, que difieren entre Python/Java/Go).
2. Tokens = secuencias que casan con `[a-z]+`. Todo lo demás (números, tildes, apóstrofes, guiones, `_`) actúa como separador.
   - `don't` → `don`, `t` · `well-known` → `well`, `known` · `café` → `caf`
3. Descartar tokens de longitud **< 2**.
4. Descartar tokens presentes en `shared/stopwords.txt` (una palabra por línea, minúsculas).
5. El índice guarda cada término **una vez por libro** (índice a nivel de documento, sin posiciones ni frecuencias).

---

## 8. Índice invertido: 3 variantes

Contenido lógico común: `término → lista de book_id` **únicos, ordenados ascendentemente, como enteros**.

### 8.1 Monolítico JSON — `datamarts/inverted_index.json`
```json
{"adventure": [5, 12, 42], "island": [5, 1342]}
```
- Claves **ordenadas alfabéticamente**, sin espacios ni indentación (`separators=(",", ":")` en Python).
- Actualizar = cargar, fusionar y reescribir el fichero completo, de forma atómica (`.tmp` + renombrar).

### 8.2 Carpetas — `datamarts/inverted_index/`
- Subcarpeta = primera letra del término **en mayúscula**: `A/adventure.txt`.
- Contenido: un `book_id` por línea, ordenado, con `\n` final.
- Actualizar = solo se reescriben los ficheros de los términos afectados. Cada uno se escribe de forma **atómica** (`.tmp` + renombrar): un fichero cortado a mitad dejaría un ID truncado que parecería válido. Esto casi duplica las operaciones de disco por término, así que los tres lenguajes lo hacen igual.

### 8.3 MongoDB
- Base de datos: `search_engine` · colección: `inverted_index_<lang>` (`python`, `java`, `go`) para no pisarnos.
- Documento: `{"term": "adventure", "postings": [5, 12, 42]}`.
- Índice único en `term`.
- Actualizar = `updateOne({term}, {$addToSet: {postings: {$each: ids}}}, upsert)` y ordenar en la lectura.
- Mongo vía `docker-compose.yml` (puerto 27017, sin auth, `mongo:7.0`).
- Los benchmarks usan otra colección, `inverted_index_<lang>_bench`, para no borrar la del pipeline.

---

## 9. Capa de control

Ficheros en `output/<lang>/control/`:

- `downloaded_books.txt`: IDs descargados **y escritos correctamente** en el datalake.
- `indexed_books.txt`: IDs indexados correctamente.

Reglas:
- Un ID por línea, en modo *append*. Se escribe **después** de que la operación termine (nunca antes).
- Al arrancar: `pendientes = downloaded − indexed` → se indexan primero.
- Los nuevos libros se toman **en orden** de `shared/book_ids.txt` (no aleatorio, para que todos procesen lo mismo).
- Reanudar tras un corte no debe duplicar ni perder libros (se prueba en el benchmark de recuperación).
- `failed_books.txt`: libros descartados (§4). Se escribe igual que los otros dos.
- Hay un único `control/` para todas las variantes de datalake e índice.
- Al arrancar, la recuperación:
  1. borra los `*.tmp`;
  2. alinea `downloaded_books.txt` con el datalake: registra los libros escritos sin registrar y quita los registrados que no están;
  3. quita de `indexed_books.txt` los libros que no están en el datalake;
  4. si el índice o los metadatos de la variante elegida están vacíos, vacía `indexed_books.txt` y se reindexa todo.
- Una última línea sin `\n` (append cortado) se descarta y se recorta del fichero.
- La indexación va por lotes (`--batch`, por defecto 100 libros): metadatos, índice y, solo después, las líneas de `indexed_books.txt`. Repetir un lote no duplica nada (`INSERT OR REPLACE` y fusión de conjuntos).

---

## 10. Consultas

`shared/queries.txt`: una consulta por línea. Lista inicial (ampliable, **acordar antes de medir**):

```
adventure
island
love
war peace
ship captain sea
whale
king queen
murder detective
```

Semántica:
- Normalizar la consulta con las **mismas reglas del punto 7**.
- 1 término → su lista de postings.
- Varios términos → **intersección (AND)**.
- Resultado: lista de `book_id` ordenada.
- Si la consulta no deja ningún término (vacía o solo stopwords) → lista vacía. Un término repetido cuenta una vez.
- Filtros opcionales de metadatos (`--author`, `--title`, `--language`): el resultado se restringe a los libros que los cumplen.

---

## 11. Benchmarks

Seguimos las 3 fases de la metodología de la asignatura: **1) diseño de experimentos → 2) ejecución → 3) análisis**.
Cada experimento es una combinación **dataset × método × medida**:

| Dimensión | Valores |
|---|---|
| Dataset | N ∈ {100, 250, 500, 1000} libros |
| Método | lenguaje (`python`, `java`, `go`) × estructura (`time`, `book`, `range` / `json`, `folders`, `mongo` / `sqlite`) |
| Medida | las de la tabla "Métricas" más abajo |

### Herramientas (las vistas en clase)

| Lenguaje | Herramienta | Uso |
|---|---|---|
| Java | **JMH** (`jmh-core` + `jmh-generator-annprocess` 1.35, vía Maven) | `java -jar target/benchmarks.jar -wi 5 -i 10 -f 2` |
| Python | **pytest-benchmark** | `pytest --benchmark-only --benchmark-json=…` |
| Go | paquete estándar `testing` (`func BenchmarkX(b *testing.B)`) | `go test -bench=. -benchmem -count=10` |
| C (si se elige en vez de Go) | **perf** (compilar con `-O2`) | `perf stat -r 10 ./programa` |

Configuración común, equivalente en las tres herramientas:

- **Calentamiento: 5 iteraciones** (en JMH `@Warmup(iterations = 5)`; en pytest `warmup=True` / ronda descartada; en Go `-count` y se descarta la primera).
- **Medición: 10 iteraciones** (`@Measurement(iterations = 10)`, `rounds=10`, `-count=10`).
- **JMH: `@Fork(2)`** para que el resultado no dependa de una única JVM.
- **Tamaño del dataset parametrizado**: en JMH con `@Param({"100", "250", "500", "1000"})`, en pytest con `@pytest.mark.parametrize`, en Go con sub-benchmarks `b.Run("n=100", …)`.
- **Modo según la métrica**:
  - Construcción de datalake/índice y actualizaciones → tiempo medio por operación (`Mode.AverageTime` / `benchmark.pedantic`). El borrado de `output/` va en el **setup**, fuera de la medida (`@Setup(Level.Invocation)` en JMH, argumento `setup=` en `pedantic`, `b.StopTimer()/b.StartTimer()` en Go).
  - Consultas y lookups → **throughput** (operaciones/segundo) y tiempo medio.
  - Si se quiere el coste "en frío" (primera ejecución, JVM sin calentar) → `Mode.SingleShotTime` en JMH y se reporta aparte.
- **Estado compartido** (índice ya cargado, lista de IDs, conexión Mongo): en JMH con `@State(Scope.Benchmark)` + `@Setup(Level.Trial)`, en pytest con *fixtures*, en Go creado antes de `b.ResetTimer()`.
- **Memoria**: la asignación por operación (JMH `-prof gc`, `tracemalloc`, `-benchmem`) se guarda solo en la salida cruda. La métrica `peak_memory` es la misma en los tres: el pico de memoria residente del proceso (ver la tabla de definiciones).

Métricas que **no** encajan en un micro-benchmark (`download_throughput`, `recovery_ok`, `files_count`, `disk_usage`) se miden con un script propio en cada lenguaje, repitiendo 5 veces.

### Reglas generales
- Misma máquina si es posible; si no, anotar CPU, RAM, SO y tipo de disco en el CSV y en el informe.
- Cerrar otros programas durante la ejecución; portátil enchufado.
- Guardar la **salida cruda** de cada herramienta (`benchmarks/raw/<lang>/`: JSON de JMH con `-rf json`, JSON de pytest-benchmark, texto de `go test`) además del CSV unificado.

### Métricas

| Ámbito | `metric` | Unidad | Cómo |
|---|---|---|---|
| Datalake | `download_throughput` | libros/s | 50 libros desde red |
| Datalake | `write_throughput` | libros/s | N libros desde `cache/` |
| Datalake | `lookup_time` | µs | localizar header+body de 200 IDs aleatorios (semilla 42), media |
| Datalake | `incremental_detect_time` | ms | detectar libros nuevos tras añadir 50 |
| Datalake | `recovery_ok` | bool | matar el proceso a mitad, reanudar, comprobar 0 duplicados/0 pérdidas |
| Datalake | `files_count` / `dirs_count` | nº | incluyendo ficheros de control y auxiliares |
| Metadatos | `metadata_insert_time` | ms | N libros |
| Metadatos | `metadata_query_time` | µs | por autor y por id |
| Índice | `index_build_time` | ms | N libros |
| Índice | `query_time` | µs | media sobre `queries.txt` |
| Índice | `update_time` | ms | añadir 50 libros a un índice existente |
| Índice | `disk_usage` | bytes | tamaño en disco (en Mongo: `storageSize`) |
| Índice | `peak_memory` | MB | pico de RAM del proceso |

Escalabilidad: repetir las métricas de construcción y consulta con N ∈ {100, 250, 500, 1000}.

### Definición exacta de cada medida

Para que los tres lenguajes midan lo mismo. "Dentro" es lo que se cronometra; lo demás va en el setup, fuera de la medida.

| `metric` | Dentro de la medida | Fuera (setup) |
|---|---|---|
| `write_throughput` | Leer de `cache/`, separar y escribir N libros (con su línea de control) en un datalake vacío. Valor = N / segundos | Borrar la salida anterior |
| `lookup_time` | Localizar header y body de un libro (comprobar que existen los dos ficheros), sobre 200 IDs elegidos al azar con repetición entre los N primeros, semilla 42. Valor = media por libro | Datalake con N libros, ya abierto |
| `incremental_detect_time` | Abrir el datalake de cero (en `time`, leer `_locations.tsv`), listar sus libros y quedarse con los que no están entre los N indexados | Datalake con N+50 libros |
| `recovery_ok` | Lanzar la ingesta de N libros en un proceso, matarlo 3 veces en momentos al azar entre el 10 % y el 60 % de lo que tarda una ingesta completa, reanudar hasta el final y comprobar: cada ID una sola vez en `downloaded_books.txt`, todos con sus dos ficheros, ningún `.tmp` y ningún fichero de más. Valor = fracción de repeticiones correctas | — |
| `files_count`, `dirs_count` | Ficheros y carpetas bajo `output/<lang>/` tras ingerir N libros: datalake, control e índice auxiliar (sin contar la carpeta raíz). Se mide una vez | — |
| `metadata_insert_time` | Insertar las N filas en una sola transacción | Extraer los metadatos y crear la base vacía |
| `metadata_query_time_author` | Todos los libros de un autor, sobre 200 autores al azar (semilla 42) | Base con N libros, ya abierta |
| `metadata_query_time_id` | La fila de un libro con su `body_path`, sobre 200 IDs al azar (semilla 42) | Base con N libros, ya abierta |
| `index_build_time` | Leer y tokenizar los N bodies y construir el índice en **un solo lote** | Vaciar el índice |
| `query_time` | Una consulta de `queries.txt` (se van alternando). Valor = media por consulta | Índice con N libros, ya abierto y cargado |
| `update_time` | Abrir el índice de cero (en JSON, cargar el fichero), leer y tokenizar los libros N+1 … N+50 de la lista y añadirlos | Reconstruir el índice con los N primeros |
| `disk_usage` | Suma del tamaño de los ficheros del índice con N libros. En MongoDB, `storageSize` de `collStats` después de un comando `fsync` (sin él, WiredTiger aún no ha escrito los datos y sale casi 0) | — |
| `peak_memory` | Pico de memoria residente de un proceso nuevo que construye el índice de N libros en un lote: `VmHWM` de `/proc/self/status` (en Python vale `resource.getrusage(RUSAGE_SELF).ru_maxrss`, en KB). En `mongo` no incluye el servidor | — |
| `download_throughput` | Descargar 50 libros de la red con 1 s entre peticiones, en el datalake `book`. Valor = libros / segundos; en el CSV, `structure = book` y `n_books = 50` | Borrar la salida anterior |

Reglas comunes:

- `metadata_query_time` se reporta en dos filas: `metadata_query_time_author` y `metadata_query_time_id`.
- Los datos de trabajo de los benchmarks van en disco (por ejemplo `output/<lang>_bench/`), nunca en `/tmp`, que en muchas distribuciones está en RAM.
- `peak_memory` necesita `/proc`, así que **los benchmarks finales se ejecutan en Linux**, los tres lenguajes en la misma máquina.

### Formato de resultados — `benchmarks/results/<lang>.csv`

```
lang,component,structure,n_books,metric,mean,stddev,min,max,ci_low,ci_high,iterations,unit
python,datalake,time,1000,write_throughput,85.3,2.1,81.0,88.9,,,10,books/s
java,index,mongo,500,query_time,412.0,15.2,390.1,441.7,398.3,425.7,20,us
```

- `component` ∈ {`datalake`, `metadata`, `index`}
- `structure` ∈ {`time`, `book`, `range`, `sqlite`, `json`, `folders`, `mongo`}
- Unidades (`unit`): `books/s`, `us`, `ms`, `count`, `bool`, `bytes`, `MB` (MiB). Los números van con punto decimal y como mucho 3 decimales.
- `iterations`: número de valores con los que se calcula la fila (10 en los micro-benchmarks, 5 en los scripts, 1 en los recuentos).
- `ci_low` / `ci_high`: intervalo de confianza (JMH lo da al 99,9 %; en Python y Go se deja vacío o se calcula en `plots.py`).
- Cada persona convierte la salida de su herramienta a este CSV; `plots.py` solo lee CSV.

### Análisis
- Gráficas mínimas: tiempo vs N (escalabilidad) por lenguaje y estructura; barras comparando estructuras; **tiempo vs memoria** marcando el **frente de Pareto** (las combinaciones lenguaje/estructura que no son peores en ambas métricas a la vez).
- Mostrar siempre la variabilidad (barras de error con `stddev` o el IC), no solo la media.
- Cada conclusión del informe debe apoyarse en una gráfica o tabla concreta.

---

## 12. Verificación de equivalencia

Cada implementación debe poder exportar un **dump canónico** en `output/<lang>/dumps/<índice>/` (comando `dump`):
- `metadata.tsv`: `book_id\ttitle\tauthor\tlanguage`, ordenado por id (NULL → cadena vacía).
- `index.tsv`: `term\tid1,id2,id3`, ordenado por término.
- Sin línea de cabecera, en UTF-8 y con `\n` al final de cada línea (también de la última).

`benchmarks/compare_outputs.py` compara los dumps de los tres lenguajes (y las 3 estructuras de índice entre sí). **No se miden benchmarks hasta que los dumps coincidan.**

---

## 13. Cómo documentar los benchmarks en el informe

Sección "Benchmarks and results" del PDF, siguiendo la plantilla de la asignatura:

- **Título** del experimento.
- **Abstract** con esta forma: *"Estudiamos el comportamiento de varias estructuras de almacenamiento (datalake e índice invertido) implementadas en Python, Java y Go para la construcción de la capa de datos de un buscador. Analizamos: throughput de escritura, coste de lookup, tiempo de construcción y actualización del índice, tiempo de consulta, uso de memoria y disco, y escalabilidad. Nuestros experimentos proporcionan un benchmark reproducible … Basándonos en ellos, recomendamos …"*
- **Setup**: máquina, versiones (JDK, Python, Go, MongoDB, SQLite), herramienta y configuración (warmup, iteraciones, forks), dataset.
- **Resultados**: gráficas y tablas de la sección 11.
- **Discusión**: por qué gana cada lenguaje/estructura y con qué coste; estructura elegida para la versión final.
- **Reproducibilidad**: comandos exactos para repetir cada benchmark (también en el README).

---

## 14. Calendario interno

Rellenar con fechas reales (entrega: `<fecha>`):

| Hito | Fecha | Criterio de "hecho" |
|---|---|---|
| SPEC cerrado + `shared/` completo | `<fecha>` | los tres dan el OK |
| Python pipeline completo | `<fecha>` | genera dumps |
| Java y Go pipeline completo | `<fecha>` | dumps idénticos a Python |
| Benchmarks ejecutados | `<fecha>` | 3 CSV en `results/` |
| Gráficas + borrador del informe | `<fecha>` | PDF revisado por los tres |
| Entrega | `<fecha>` | PDF subido por `<Nombre>` |

---

## 15. Normas de Git

- Commits pequeños y frecuentes, con mensajes descriptivos: `python: tokenizer + tests`.
- Cada uno trabaja en su carpeta; cambios en `shared/` o en este SPEC **se avisan al grupo** antes del commit.
- Ramas opcionales (`python-dev`, `java-dev`, `go-dev`), merge a `main` cuando algo funcione.
- Nunca subir `cache/`, `output/`, ficheros `.db` ni credenciales.

---

## 16. Decisiones pendientes

- [ ] Nombre del grupo. El enunciado exige que el repositorio sea exactamente `https://github.com/<group_name>/stage_1`; hoy se llama `UAV-2/Stage-1`.
- [x] Tercer lenguaje definitivo: **Go**.
- [x] Lista final de `book_ids.txt` y `stopwords.txt`: ver §3.
- [x] Lista final de `queries.txt`: las 8 consultas del §10.
- [ ] Máquina donde se ejecutan los benchmarks. Tiene que ser Linux (§11).
- [x] Go (`testing`) o C (`perf`): **Go**, con el paquete `testing`.
- [ ] Quién sube el PDF.

---

## 17. Interfaz común de línea de comandos

Los tres lenguajes exponen los mismos comandos y opciones, para que el README y los scripts de benchmark sean iguales en todos.

| Comando | Qué hace |
|---|---|
| `pipeline [ids...]` | Ciclo completo: indexa lo pendiente, descarga los libros nuevos en el orden de la lista y los indexa por lotes |
| `download [ids...]` | Solo la ingesta al datalake (sin IDs, los de la lista) |
| `index` | Indexa los descargados que aún no están indexados |
| `query [texto...]` | Busca (AND); sin texto, ejecuta `shared/queries.txt` |
| `books [ids...]` | Consulta los metadatos por ID o con `--author`, `--title` y `--language` |
| `dump` | Exporta los dumps del §12 |
| `lookup <ids...>` | Localiza header y body de cada libro |
| `status` | Libros en el datalake, descargados, indexados, pendientes y descartados |
| `recover` | Alinea el control con el datalake |

Opciones: `--datalake=time|book|range` (por defecto `book`), `--index=json|folders|mongo` (por defecto `json`), `--offline`, `--sample` (lee `sample_data/` con `book_ids_sample.txt`, sin red), `--n=N`, `--batch=N` (por defecto 100), `--rebuild`, `--ids=FICHERO`, `--delay=1s`, `--mongo=URI` (por defecto `mongodb://localhost:27017`), `--author`, `--title`, `--language` y `--root=DIR` (por defecto `..`).

---

## 18. Registro de cambios

**Versión 2 (1 de octubre).** Cierra los huecos que aparecieron al integrar Go y Java:

- §3: criterio exacto del dataset, 1.050 libros en caché, `sample_data/` y lista de stopwords.
- §4: definición de `strip()`, END antes de START y `failed_books.txt`.
- §5: orden de escritura y `_locations.tsv` antes que los ficheros.
- §6: separación de líneas, campo vacío → `NULL`, idioma con coincidencia exacta, `body_path`, `INSERT OR REPLACE` y consultas de metadatos.
- §8: escritura atómica de los índices de ficheros y colección aparte para benchmarks.
- §9: control único, recuperación al arrancar e indexación por lotes.
- §10: consulta sin términos y filtros de metadatos.
- §11: definición exacta de cada medida, `metadata_query_time` en dos filas, `peak_memory` como pico de memoria residente y benchmarks en Linux.
- §12: ubicación y formato exacto de los dumps.
- §16: decisiones resueltas.
- §17: interfaz de línea de comandos común (nuevo).
