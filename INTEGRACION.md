# Integración de los tres lenguajes

Guía para dejar Python, Java y Go en un único repositorio con la estructura de `SPEC.md` §2. Cuando el montaje esté hecho y los dumps coincidan, este fichero se puede borrar.

## Estado actual (1 de octubre)

| Pieza | Dónde está | Estado |
|---|---|---|
| Estructura común (`shared/`, `sample_data/`, `benchmarks/`, `docker-compose.yml`, `README.md`, `.gitignore`) | rama `GO` | Hecha |
| Go | rama `GO`, carpeta `go/` | Completo: pipeline, dumps y benchmarks |
| Java | rama `JAVA`, carpeta `Stage-1-JAVA 3/bigdatajava/` | Descarga, datalakes y control. Faltan metadatos, índices, consultas, dumps y benchmarks |
| Python | sin rama en el remoto | Pendiente de subir |
| `benchmarks/plots.py` | — | Pendiente (Python, según el reparto del SPEC) |

## Lo que necesita cada implementación para encajar

1. **Carpeta propia en la raíz**: `python/` y `java/`, igual que `go/`.
2. **Entradas comunes**: leer `../shared/book_ids.txt`, `stopwords.txt` y `queries.txt`, y los libros crudos de `../cache/<id>.txt`. Para la prueba rápida, `../sample_data/` con `../shared/book_ids_sample.txt`.
3. **Salidas con las rutas del SPEC** en `../output/<lang>/`. Diferencias que hay hoy en Java:
   - la tercera variante se llama `datalake_batch/000000-000999/`; el SPEC (§5) la llama `datalake_range/0-999/`, sin ceros a la izquierda;
   - el control va en `control/<tipo>/`; el SPEC (§9) usa un único `control/`;
   - sin lista, los IDs se eligen al azar; el SPEC (§9) los toma en el orden de `shared/book_ids.txt`;
   - la variante `time` localiza los libros recorriendo carpetas; el SPEC (§5) pide el índice auxiliar `datalake_time/_locations.tsv`.
4. **Dumps** en `output/<lang>/dumps/<índice>/metadata.tsv` e `index.tsv`, sin línea de cabecera y con `\n` al final de cada línea. Después: `python3 benchmarks/compare_outputs.py`.
5. **MongoDB**: colección `inverted_index_<lang>` de la base `search_engine`. Los benchmarks deberían usar otra colección para no borrar la del pipeline; Go usa `inverted_index_go_bench`.
6. **Resultados**: `benchmarks/results/<lang>.csv` con el formato del SPEC y los mismos nombres de métrica y unidades que `go.csv`, y la salida cruda de la herramienta en `benchmarks/raw/<lang>/`.

## Decisiones tomadas en Go que hay que acordar y añadir al SPEC

Son huecos del SPEC. Si alguien lo resolvió de otra forma, hay que elegir una antes de comparar dumps o medir.

| Tema | Cómo lo hace Go |
|---|---|
| Ubicación y formato de los dumps | `output/<lang>/dumps/<índice>/`, sin cabecera |
| `metadata_query_time` | Dos filas en el CSV: `metadata_query_time_author` y `metadata_query_time_id` |
| Libros sin marcadores | Se anotan en `control/failed_books.txt` (igual que Java) y no se vuelven a pedir |
| Campo de cabecera vacío (`Title:` sin nada) | `NULL`, igual que si no existiera |
| Normalización del idioma | Coincidencia exacta con `English`, `Spanish`, `French` y `German`; cualquier otro valor, en minúsculas |
| Consulta que solo tiene stopwords | Resultado vacío |
| Escritura del índice `folders` | Atómica (`.tmp` + renombrar), como el datalake. Cuesta casi el doble de operaciones de disco por término, así que los tres lenguajes tienen que hacer lo mismo o la comparación no es justa |
| `index_build_time` | Incluye leer y tokenizar los N bodies, y construye el índice en un solo lote |
| `update_time` | Abre el índice de cero dentro de la medida, así que en JSON incluye cargar el fichero |
| `disk_usage` | Suma del tamaño de los ficheros; en MongoDB, `storageSize` tras forzar un checkpoint (`fsync`) |
| `peak_memory` | Memoria del proceso (`runtime.MemStats.Sys`), sin la del servidor de MongoDB |
| Dataset | Los 1.000 primeros textos en inglés del catálogo oficial. Los 320 primeros están descargados y comprobados (marcadores e idioma); los demás, solo por catálogo |

Hay además un problema de portabilidad: en Windows, `con`, `aux`, `nul` y `prn` son nombres de fichero reservados, y `con` aparece como término en los textos. Quien ejecute el índice `folders` en Windows fallará al escribir `C/con.txt`.

## Montaje en git

La rama `GO` ya tiene la estructura final. `GO` y `main` no comparten historia, y `JAVA` sí sale de `main`. Para juntarlo todo en `main` conservando la historia de cada uno (el enunciado la evalúa):

```bash
# 1. Java mueve su proyecto a java/ en su rama
git checkout JAVA
git mv "Stage-1-JAVA 3/bigdatajava" java
git mv "Stage-1-JAVA 3/README.md" java/README.md
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
