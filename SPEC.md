# SPEC — Stage 1: Data Layer del buscador

Documento de acuerdos del grupo. **Si algo no está aquí, se decide entre los tres y se añade antes de programarlo.**
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
│       └── control/
└── benchmarks/
    ├── results/                # CSV de cada lenguaje
    ├── compare_outputs.py      # verificación de equivalencia
    └── plots.py
```

`cache/` y `output/` van en `.gitignore`.

---

## 3. Dataset

- `shared/book_ids.txt`: un ID por línea, ordenado ascendentemente. Tamaño objetivo: **1.000 libros en inglés**.
- `shared/book_ids_sample.txt`: los primeros 20 IDs del anterior.
- Escalabilidad: se usan los **primeros N** IDs del fichero, con N ∈ {100, 250, 500, 1000}.
- Se excluyen los libros sin marcadores START/END o sin idioma inglés (se filtran **una vez** al crear la lista, no en cada ejecución).

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
- Si faltan los marcadores → el libro se marca como fallido y no se escribe nada.

---

## 5. Datalake: 3 variantes

| Variante | Ruta |
|---|---|
| `time` | `datalake_time/YYYYMMDD/HH/<id>.header.txt` y `<id>.body.txt` |
| `book` | `datalake_book/<id>/header.txt` y `body.txt` |
| `range` | `datalake_range/<inicio>-<fin>/<id>.header.txt` y `<id>.body.txt`, con rangos de 1000 (`0-999`, `1000-1999`, …) |

- Fecha/hora en **hora local de la máquina**, formato 24 h.
- **Escritura atómica**: escribir a `*.tmp` y renombrar al final. Así, si el proceso se corta, no quedan ficheros a medias (necesario para la métrica de recuperación).
- Para localizar un libro en `time` hace falta saber dónde está: se mantiene un índice auxiliar `datalake_time/_locations.tsv` (`id\tYYYYMMDD/HH`). Esto cuenta como *overhead* en el benchmark.

---

## 6. Metadatos

Regex sobre el header, **línea a línea, case-insensitive**, primera coincidencia:

| Campo | Regex |
|---|---|
| title | `^Title:\s*(.+)$` |
| author | `^Author:\s*(.+)$` |
| language | `^Language:\s*(.+)$` |

- Valores: `strip()`. Si no existe → `NULL` (no cadena vacía).
- Títulos que continúan en la línea siguiente (indentada): **se ignora la continuación** (simplificación acordada).
- `language` se normaliza a código: `English` → `en`, `Spanish` → `es`, `French` → `fr`, `German` → `de`; cualquier otro → minúsculas tal cual.

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

`body_path` = ruta relativa al body en la variante de datalake elegida como final.

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
- Actualizar = cargar, fusionar y reescribir el fichero completo.

### 8.2 Carpetas — `datamarts/inverted_index/`
- Subcarpeta = primera letra del término **en mayúscula**: `A/adventure.txt`.
- Contenido: un `book_id` por línea, ordenado, con `\n` final.
- Actualizar = solo se reescriben los ficheros de los términos afectados.

### 8.3 MongoDB
- Base de datos: `search_engine` · colección: `inverted_index_<lang>` (`python`, `java`, `go`) para no pisarnos.
- Documento: `{"term": "adventure", "postings": [5, 12, 42]}`.
- Índice único en `term`.
- Actualizar = `updateOne({term}, {$addToSet: {postings: {$each: ids}}}, upsert)` y ordenar en la lectura.
- Mongo vía `docker-compose.yml` (puerto 27017, sin auth, versión fijada en el compose).

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
- **Memoria**: JMH con `-prof gc`; Python con `tracemalloc` (pico); Go con `-benchmem` + `runtime.MemStats`.

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

### Formato de resultados — `benchmarks/results/<lang>.csv`

```
lang,component,structure,n_books,metric,mean,stddev,min,max,ci_low,ci_high,iterations,unit
python,datalake,time,1000,write_throughput,85.3,2.1,81.0,88.9,,,10,books/s
java,index,mongo,500,query_time,412.0,15.2,390.1,441.7,398.3,425.7,20,us
```

- `component` ∈ {`datalake`, `metadata`, `index`}
- `structure` ∈ {`time`, `book`, `range`, `sqlite`, `json`, `folders`, `mongo`}
- `ci_low` / `ci_high`: intervalo de confianza (JMH lo da al 99,9 %; en Python y Go se deja vacío o se calcula en `plots.py`).
- Cada persona convierte la salida de su herramienta a este CSV; `plots.py` solo lee CSV.

### Análisis
- Gráficas mínimas: tiempo vs N (escalabilidad) por lenguaje y estructura; barras comparando estructuras; **tiempo vs memoria** marcando el **frente de Pareto** (las combinaciones lenguaje/estructura que no son peores en ambas métricas a la vez).
- Mostrar siempre la variabilidad (barras de error con `stddev` o el IC), no solo la media.
- Cada conclusión del informe debe apoyarse en una gráfica o tabla concreta.

---

## 12. Verificación de equivalencia

Cada implementación debe poder exportar un **dump canónico**:
- `metadata.tsv`: `book_id\ttitle\tauthor\tlanguage`, ordenado por id (NULL → cadena vacía).
- `index.tsv`: `term\tid1,id2,id3`, ordenado por término.

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

- [ ] Nombre del grupo
- [ ] Tercer lenguaje definitivo (Go / C# / Rust / Node)
- [ ] Lista final de `book_ids.txt` y `stopwords.txt`
- [ ] Lista final de `queries.txt`
- [ ] Máquina donde se ejecutan los benchmarks
- [ ] Go (`testing`) o C (`perf`) como tercer lenguaje: decide la herramienta de benchmark
- [ ] Quién sube el PDF
