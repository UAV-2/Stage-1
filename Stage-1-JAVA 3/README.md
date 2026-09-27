# Stage 1 – Implementación en Java

Implementación en Java de la capa de datos del buscador (Big Data, GCID – ULPGC).

## Requisitos

- Java 17 o superior
- Maven 3.8 o superior
- Conexión a Internet (descarga desde Project Gutenberg)

## Compilación y ejecución

El proyecto está en la carpeta `bigdatajava/`. Todos los comandos se ejecutan desde ahí:

```bash
cd bigdatajava
mvn compile
mvn exec:java -Dexec.args="<comando> [ids...] [opciones]"
```

Ejemplos:

```bash
# Descargar libros concretos en el datalake book-based
mvn exec:java -Dexec.args="download 1342 84 11"

# Ejecutar el pipeline con la lista común de IDs y el datalake por fecha
mvn exec:java -Dexec.args="pipeline --ids=data/book_ids.txt --steps=50 --datalake=time"

# Pipeline con IDs aleatorios reproducibles
mvn exec:java -Dexec.args="pipeline --steps=20 --seed=7 --datalake=batch"

# Consultar dónde está un libro y el estado del pipeline
mvn exec:java -Dexec.args="lookup 1342 --datalake=time"
mvn exec:java -Dexec.args="status --datalake=time"
```

| Comando | Descripción |
|---|---|
| `pipeline` | Ciclo del control layer: indexa lo pendiente o descarga un libro nuevo |
| `download <id...>` | Descarga libros concretos (por defecto 1342) |
| `lookup <id...>` | Localiza header y body de cada libro |
| `status` | Resumen: libros en el datalake, descargados, indexados, pendientes y descartados |
| `recover` | Alinea los ficheros de control con el contenido real del datalake |

| Opción | Valor por defecto | Descripción |
|---|---|---|
| `--datalake=time\|book\|batch` | `book` | Estructura del datalake |
| `--out=DIR` | `output/java` | Carpeta de salida |
| `--steps=N` | `10` | Número máximo de pasos del pipeline |
| `--ids=FICHERO` | – | Lista fija de IDs (una por línea, `#` para comentarios) |
| `--seed=N` | `42` | Semilla para la selección aleatoria de IDs |

## Estructura del proyecto

```
bigdatajava/
├── pom.xml
├── data/book_ids.txt             dataset común para todos los lenguajes
└── src/main/java/es/ulpgc/bigdata/
├── Main.java                     CLI
├── ingestion/                    descarga y separación header/body
│   ├── BookSource.java
│   ├── GutenbergDownloader.java
│   ├── BookParts.java
│   └── BookUnavailableException.java
├── datalake/                     las tres organizaciones del datalake
│   ├── DatalakeStore.java        interfaz común
│   ├── FileDatalake.java         lógica compartida
│   ├── TimeBasedDatalake.java
│   ├── BookBasedDatalake.java
│   ├── BatchDatalake.java
│   └── DatalakeFactory.java
├── control/                      control layer
│   ├── ControlPipeline.java
│   ├── ControlState.java
│   ├── CandidateProvider.java
│   ├── RecoveryReport.java
│   └── StepResult.java
├── indexing/
│   └── BookIndexer.java          punto de enganche del índice invertido
└── util/
    └── FileUtils.java            escritura atómica
```

## Ingesta

Cada libro se descarga de `https://www.gutenberg.org/cache/epub/<id>/pg<id>.txt` y se
procesa con estas reglas, idénticas a las de Go:

1. Se normalizan los saltos de línea `\r\n` a `\n`.
2. Se buscan los marcadores sin distinguir mayúsculas:
   `*** START OF (THE|THIS) PROJECT GUTENBERG EBOOK` y `*** END OF (THE|THIS) PROJECT GUTENBERG EBOOK`.
3. El header es todo lo anterior al marcador START; el body va desde el final de la línea
   START hasta el marcador END. Ambos se guardan sin espacios al inicio ni al final.

Un 404 o la ausencia de marcadores lanza `BookUnavailableException` y el libro se descarta
de forma permanente. Cualquier otro error de red no se registra y el libro se reintentará.

## Estructuras del datalake

Las tres implementan `DatalakeStore`, así que el resto del sistema no depende de la elegida.

| Tipo | Ruta | Lookup |
|---|---|---|
| `time` | `datalake_time/YYYYMMDD/HH/<id>.header.txt` y `<id>.body.txt` | Recorrido de carpetas (la fecha no se deduce del ID) |
| `book` | `datalake_book/<id>/header.txt` y `body.txt` | Directo, O(1) |
| `batch` | `datalake_batch/001000-001999/<id>.header.txt` y `<id>.body.txt` | Directo, carpeta calculada con `id / 1000` |

Resumen de trade-offs:

- **time**: trazabilidad y procesamiento incremental natural (basta con leer las carpetas
  más recientes), pero localizar un libro concreto obliga a recorrer el árbol.
- **book**: lookup inmediato y fácil de depurar, pero una carpeta por libro produce muchos
  directorios en un mismo nivel cuando el dataset crece.
- **batch**: lookup directo y como máximo 1000 libros (2000 ficheros) por carpeta. Pierde
  la información temporal, que queda en los ficheros de control.

## Control layer

Los ficheros de control se guardan en `bigdatajava/output/java/control/<tipo>/`, uno por estructura
para que los experimentos no se mezclen:

- `downloaded_books.txt`: libros descargados y guardados correctamente
- `indexed_books.txt`: libros indexados
- `failed_books.txt`: IDs descartados (no existen o no tienen marcadores)

Cada paso del pipeline sigue la sección 5.2 del enunciado:

1. Si hay libros descargados y no indexados, se indexa el primero.
2. Si no, se elige un ID nuevo (de la lista con `--ids`, o aleatorio con hasta 10 intentos)
   que no esté ni descargado ni descartado, se descarga y se guarda.
3. Se actualiza el fichero de control correspondiente.

El indexador todavía es un `BookIndexer.noOp()`. Se sustituirá por los índices invertidos
sin cambiar el control layer.

## Recuperación ante interrupciones

- Header y body se escriben primero en `.tmp` y después se renombran de forma atómica,
  así que nunca queda un fichero final a medias.
- El body se escribe después del header: un libro solo se considera completo si existen
  los dos.
- Un libro solo se registra en `downloaded_books.txt` después de estar guardado.
- Al arrancar, `recover()` borra los `.tmp` sobrantes y reconcilia el estado con el datalake:
  - libros guardados pero no registrados (caída entre la escritura y el registro) → se registran;
  - IDs registrados sin fichero (por ejemplo una línea cortada) → se eliminan;
  - IDs indexados que ya no están en el datalake → se eliminan.

Con esto el pipeline se puede reanudar sin duplicar ni perder libros.

## Dataset común

`bigdatajava/data/book_ids.txt` contiene la lista de IDs que deben usar todos los lenguajes en los
benchmarks, para que la comparación dependa solo de la implementación y no de los datos.
