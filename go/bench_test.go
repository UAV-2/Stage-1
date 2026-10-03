package main

// Micro-benchmarks del SPEC §11. Cada uno se llama
// Benchmark<Métrica>/<estructura>/n=<libros>, que es lo que cmd/bench
// convierte en filas de benchmarks/results/go.csv.
//
// Lo normal es lanzarlos con "go run ./cmd/bench", que elige las opciones
// adecuadas para cada grupo. A mano:
//
//	go test -run='^$' -bench=. -benchmem -count=15 -timeout=0
//
// La configuración va en variables de entorno: STAGE1_ROOT (raíz del
// repositorio, por defecto ..), STAGE1_SIZES (por defecto 100,250,500,1000) y
// STAGE1_MONGO (por defecto mongodb://localhost:27017). cmd/bench usa además
// STAGE1_FOLDERS_FROM=N y STAGE1_FOLDERS=skip|only para medir aparte, con
// menos iteraciones, la construcción y la actualización de folders con N grande.

import (
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"testing"

	"stage1_go/internal/benchkit"
	"stage1_go/internal/datalake"
	"stage1_go/internal/index"
	"stage1_go/internal/metadata"
)

var (
	lakeKinds  = []string{"time", "book", "range"}
	indexKinds = []string{"json", "folders", "mongo"}

	env    *benchkit.Env
	envErr error
)

func TestMain(m *testing.M) {
	code := m.Run()
	if env != nil {
		prepare("fin") // suelta lo último preparado (y vacía su índice)
	}
	// cmd/bench reutiliza la carpeta de trabajo entre ejecuciones y la borra
	// él al terminar.
	if env != nil && os.Getenv("STAGE1_KEEP_WORK") == "" {
		os.RemoveAll(env.Work)
	}
	os.Exit(code)
}

func benchEnv(b *testing.B) *benchkit.Env {
	b.Helper()
	if env == nil && envErr == nil {
		env, envErr = benchkit.Load()
	}
	if envErr != nil {
		b.Skipf("no se puede preparar el benchmark: %v", envErr)
	}
	return env
}

func check(b *testing.B, err error) {
	b.Helper()
	if err != nil {
		b.Fatal(err)
	}
}

// benchBooks devuelve los n primeros libros del dataset; si no están en cache/,
// el benchmark se salta.
func benchBooks(b *testing.B, n int) []int {
	b.Helper()
	ids, err := benchEnv(b).Books(n)
	if err != nil {
		b.Skip(err)
	}
	return ids
}

func source(b *testing.B) datalake.Store {
	b.Helper()
	benchBooks(b, benchEnv(b).MaxBooks())
	store, err := benchEnv(b).Source()
	check(b, err)
	return store
}

// benchIndex abre una variante del índice; si es la de MongoDB y el servidor
// no responde, el benchmark se salta.
func benchIndex(b *testing.B, kind string) index.Index {
	b.Helper()
	idx, err := benchEnv(b).OpenIndex(kind, filepath.Join(benchEnv(b).Work, "datamarts"))
	if err != nil {
		if kind == "mongo" {
			b.Skip(err)
		}
		b.Fatal(err)
	}
	return idx
}

// each ejecuta un sub-benchmark por cada estructura y cada tamaño.
func each(b *testing.B, kinds []string, run func(b *testing.B, kind string, n int)) {
	for _, kind := range kinds {
		for _, n := range benchEnv(b).Sizes {
			if !selected(kind, n) {
				continue
			}
			b.Run(fmt.Sprintf("%s/n=%d", kind, n), func(b *testing.B) { run(b, kind, n) })
		}
	}
}

// selected aplica STAGE1_FOLDERS: "skip" omite folders con N >= STAGE1_FOLDERS_FROM
// y "only" deja solo esos.
func selected(kind string, n int) bool {
	from, err := strconv.Atoi(os.Getenv("STAGE1_FOLDERS_FROM"))
	if err != nil || from <= 0 {
		return true
	}
	large := kind == "folders" && n >= from
	switch os.Getenv("STAGE1_FOLDERS") {
	case "skip":
		return !large
	case "only":
		return large
	}
	return true
}

// Go vuelve a llamar a cada sub-benchmark varias veces (una por -count y por
// cada ajuste de b.N), así que lo que cuesta preparar se guarda aquí y se
// suelta cuando la estructura o el tamaño cambian.
var fixture struct {
	key      string
	lake     datalake.Store
	idx      index.Index
	db       *metadata.DB
	postings map[string][]int
	added    map[string][]int // lo último que añadió update_time
}

// prepare devuelve true si hay que construir la preparación de key.
func prepare(key string) bool {
	if fixture.key == key {
		return false
	}
	if fixture.idx != nil {
		fixture.idx.Reset()
		fixture.idx.Close()
	}
	if fixture.db != nil {
		fixture.db.Close()
	}
	fixture.lake, fixture.idx, fixture.db, fixture.postings, fixture.added = nil, nil, nil, nil, nil
	os.RemoveAll(filepath.Join(env.Work, "fixture"))
	fixture.key = key
	return true
}

// lake devuelve un datalake de la variante kind con los n primeros libros,
// recién abierto.
func lake(b *testing.B, kind string, n int) datalake.Store {
	b.Helper()
	e := benchEnv(b)
	ids := benchBooks(b, n)
	out := filepath.Join(e.Work, "fixture")
	if prepare(fmt.Sprintf("lake/%s/%d", kind, n)) {
		_, err := e.Ingest(kind, out, ids)
		check(b, err)
		fixture.lake, err = datalake.New(kind, out)
		check(b, err)
	}
	return fixture.lake
}

// ---------------------------------------------------------------- datalake

// write_throughput: libros por segundo al leer de cache/, separar y escribir
// N libros en un datalake vacío.
func BenchmarkWriteThroughput(b *testing.B) {
	each(b, lakeKinds, func(b *testing.B, kind string, n int) {
		e := benchEnv(b)
		ids := benchBooks(b, n)
		out := filepath.Join(e.Work, "write")
		for i := 0; i < b.N; i++ {
			b.StopTimer()
			os.RemoveAll(out)
			b.StartTimer()
			_, err := e.Ingest(kind, out, ids)
			check(b, err)
		}
		b.StopTimer()
		os.RemoveAll(out)
		b.ReportMetric(float64(n*b.N)/b.Elapsed().Seconds(), "books/s")
	})
}

// lookup_time: localizar header y body de un libro, sobre 200 IDs al azar.
func BenchmarkLookupTime(b *testing.B) {
	each(b, lakeKinds, func(b *testing.B, kind string, n int) {
		store := lake(b, kind, n)
		targets := benchkit.Sample(benchBooks(b, n))
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			if _, ok := store.Locate(targets[i%len(targets)]); !ok {
				b.Fatal("libro no encontrado")
			}
		}
	})
}

// incremental_detect_time: tras añadir 50 libros a un datalake de N, abrirlo
// de cero y averiguar cuáles son nuevos respecto a los ya indexados.
func BenchmarkIncrementalDetectTime(b *testing.B) {
	each(b, lakeKinds, func(b *testing.B, kind string, n int) {
		ids := benchBooks(b, n+benchkit.Extra)
		store := lake(b, kind, n+benchkit.Extra)
		out := filepath.Dir(store.Root())
		indexed := make(map[int]bool, n)
		for _, id := range ids[:n] {
			indexed[id] = true
		}
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			fresh, err := datalake.New(kind, out)
			check(b, err)
			inLake, err := fresh.List()
			check(b, err)
			added := 0
			for _, id := range inLake {
				if !indexed[id] {
					added++
				}
			}
			if added != benchkit.Extra {
				b.Fatalf("se han detectado %d libros nuevos, se esperaban %d", added, benchkit.Extra)
			}
		}
	})
}

// --------------------------------------------------------------- metadatos

var metadataKinds = []string{"sqlite"}

// metadata_insert_time: insertar los metadatos de N libros en una base vacía.
func BenchmarkMetadataInsertTime(b *testing.B) {
	each(b, metadataKinds, func(b *testing.B, _ string, n int) {
		e := benchEnv(b)
		records, err := e.Records(source(b), benchBooks(b, n))
		check(b, err)
		dir := filepath.Join(e.Work, "insert")
		for i := 0; i < b.N; i++ {
			b.StopTimer()
			os.RemoveAll(dir)
			db, err := metadata.Open(filepath.Join(dir, "metadata.db"))
			check(b, err)
			b.StartTimer()
			check(b, db.Insert(records))
			b.StopTimer()
			db.Close()
		}
		os.RemoveAll(dir)
	})
}

// metadataDB devuelve una base con los metadatos de los n primeros libros.
func metadataDB(b *testing.B, n int) (*metadata.DB, []metadata.Book) {
	b.Helper()
	e := benchEnv(b)
	records, err := e.Records(source(b), benchBooks(b, n))
	check(b, err)
	if prepare(fmt.Sprintf("metadata/%d", n)) {
		fixture.db, err = metadata.Open(filepath.Join(e.Work, "fixture", "metadata.db"))
		check(b, err)
		check(b, fixture.db.Insert(records))
	}
	return fixture.db, records
}

// metadata_query_time (por autor): todos los libros de un autor.
func BenchmarkMetadataQueryTimeAuthor(b *testing.B) {
	each(b, metadataKinds, func(b *testing.B, _ string, n int) {
		db, records := metadataDB(b, n)
		var authors []string
		for _, record := range records {
			if record.Author != "" {
				authors = append(authors, record.Author)
			}
		}
		targets := benchkit.Sample(authors)
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			found, err := db.Find(metadata.Filter{Author: targets[i%len(targets)]})
			if err != nil || len(found) == 0 {
				b.Fatalf("consulta por autor: %d libros, %v", len(found), err)
			}
		}
	})
}

// metadata_query_time (por id): la fila de un libro, con la ruta de su body.
func BenchmarkMetadataQueryTimeID(b *testing.B) {
	each(b, metadataKinds, func(b *testing.B, _ string, n int) {
		db, _ := metadataDB(b, n)
		targets := benchkit.Sample(benchBooks(b, n))
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			if _, ok, err := db.ByID(targets[i%len(targets)]); err != nil || !ok {
				b.Fatalf("consulta por id: %v, %v", ok, err)
			}
		}
	})
}

// ------------------------------------------------------------------ índice

// index_build_time: leer y tokenizar N libros y construir el índice desde
// cero, en un solo lote.
func BenchmarkIndexBuildTime(b *testing.B) {
	each(b, indexKinds, func(b *testing.B, kind string, n int) {
		e := benchEnv(b)
		store, ids := source(b), benchBooks(b, n)
		prepare("build")
		idx := benchIndex(b, kind)
		defer idx.Close()
		for i := 0; i < b.N; i++ {
			b.StopTimer()
			check(b, idx.Reset())
			b.StartTimer()
			postings, err := e.Postings(store, ids)
			check(b, err)
			check(b, idx.Add(postings))
		}
		b.StopTimer()
		check(b, idx.Reset())
	})
}

// query_time: una consulta de shared/queries.txt sobre un índice de N libros
// ya abierto.
func BenchmarkQueryTime(b *testing.B) {
	each(b, indexKinds, func(b *testing.B, kind string, n int) {
		e := benchEnv(b)
		store, ids := source(b), benchBooks(b, n)
		if prepare(fmt.Sprintf("index/%s/%d", kind, n)) {
			idx := benchIndex(b, kind)
			check(b, idx.Reset())
			postings, err := e.Postings(store, ids)
			check(b, err)
			check(b, idx.Add(postings))
			fixture.idx = idx
		}
		idx := fixture.idx
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			_, err := index.Search(idx, e.Queries[i%len(e.Queries)], e.Stop)
			check(b, err)
		}
	})
}

// update_time: añadir 50 libros a un índice que ya tiene N. El índice se
// abre de cero dentro de la medida, así que el JSON paga la carga del fichero.
// Fuera de la medida, el índice vuelve a tener los N primeros: en folders se
// deshace la actualización anterior, porque reconstruirlo tarda minutos.
func BenchmarkUpdateTime(b *testing.B) {
	each(b, indexKinds, func(b *testing.B, kind string, n int) {
		e := benchEnv(b)
		benchIndex(b, kind).Close() // si no hay MongoDB, se salta antes de preparar nada
		store := source(b)
		added := benchBooks(b, n+benchkit.Extra)[n:]
		if prepare(fmt.Sprintf("update/%s/%d", kind, n)) {
			var err error
			fixture.postings, err = e.Postings(store, benchBooks(b, n))
			check(b, err)
			fixture.idx = benchIndex(b, kind) // para vaciarlo al cambiar de tamaño
		}
		folders := filepath.Join(e.Work, "datamarts", "inverted_index")
		for i := 0; i < b.N; i++ {
			b.StopTimer()
			idx := benchIndex(b, kind)
			if kind == "folders" && fixture.added != nil {
				check(b, benchkit.UndoFolders(folders, fixture.postings, fixture.added))
			} else {
				check(b, idx.Reset())
				check(b, idx.Add(fixture.postings))
			}
			check(b, idx.Close())
			b.StartTimer()

			idx = benchIndex(b, kind)
			postings, err := e.Postings(store, added)
			check(b, err)
			check(b, idx.Add(postings))

			b.StopTimer()
			fixture.added = postings
			check(b, idx.Close())
		}
	})
}

// Comprueba que la preparación de los benchmarks deja los datos que se
// espera medir; así un fallo de configuración no pasa por una medida rara.
func TestBenchkit(t *testing.T) {
	if _, err := os.Stat(filepath.Join("..", "shared", "book_ids_sample.txt")); err != nil {
		t.Skip("no hay dataset de muestra")
	}
	root := t.TempDir()
	for _, dir := range []string{"shared", "sample_data"} {
		if err := os.CopyFS(filepath.Join(root, dir), os.DirFS(filepath.Join("..", dir))); err != nil {
			t.Fatal(err)
		}
	}
	// El dataset de muestra hace de dataset completo y de caché.
	for from, to := range map[string]string{
		"sample_data":                "cache",
		"shared/book_ids_sample.txt": "shared/book_ids.txt",
	} {
		if err := os.Rename(filepath.Join(root, from), filepath.Join(root, to)); err != nil {
			t.Fatal(err)
		}
	}
	t.Setenv("STAGE1_ROOT", root)
	t.Setenv("STAGE1_SIZES", "5")

	e, err := benchkit.Load()
	if err != nil {
		t.Fatal(err)
	}
	ids, err := e.Books(5)
	if err != nil {
		t.Fatal(err)
	}
	pipeline, err := e.Ingest("range", filepath.Join(e.Work, "lake"), ids)
	if err != nil {
		t.Fatal(err)
	}
	if got := pipeline.State.Downloaded(); !slices.Equal(got, ids) {
		t.Errorf("descargados = %v, se esperaba %v", got, ids)
	}
	postings, err := e.Postings(pipeline.Store, ids)
	if err != nil || len(postings) == 0 {
		t.Errorf("postings = %d términos, %v", len(postings), err)
	}
	records, err := e.Records(pipeline.Store, ids)
	if err != nil || len(records) != len(ids) {
		t.Errorf("metadatos = %d filas, %v", len(records), err)
	}
}
