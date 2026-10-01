// Package benchkit prepara los datos que comparten los micro-benchmarks
// (bench_test.go) y las métricas de cmd/bench, para que ambos midan
// exactamente lo mismo.
package benchkit

import (
	"fmt"
	"math/rand"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"stage1_go/internal/control"
	"stage1_go/internal/datalake"
	"stage1_go/internal/index"
	"stage1_go/internal/ingestion"
	"stage1_go/internal/metadata"
	"stage1_go/internal/tokenizer"
)

const (
	// Extra son los libros que se añaden en las medidas de actualización y
	// de detección de libros nuevos.
	Extra = 50
	// Samples son los IDs aleatorios de las medidas de lookup.
	Samples = 200
	// Seed es la semilla acordada para cualquier elección aleatoria.
	Seed = 42

	defaultSizes = "100,250,500,1000"
	defaultMongo = "mongodb://localhost:27017"
	// benchLang separa la colección de MongoDB de la del pipeline real.
	benchLang = "go_bench"
)

// Env es la configuración de una ejecución de benchmarks. Se lee de las
// variables de entorno STAGE1_ROOT, STAGE1_SIZES y STAGE1_MONGO.
type Env struct {
	Root     string // raíz del repositorio
	Work     string // carpeta de trabajo; en disco, no en /tmp, que suele ser RAM
	IDs      []int  // shared/book_ids.txt
	Sizes    []int  // valores de N
	Stop     tokenizer.Stopwords
	Queries  []string
	MongoURI string

	source datalake.Store
}

func getenv(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

// Load lee la configuración y los ficheros de shared/. A diferencia del
// pipeline, aquí todos son obligatorios: sin ellos las medidas no serían
// comparables con las de los otros lenguajes.
func Load() (*Env, error) {
	root, err := filepath.Abs(getenv("STAGE1_ROOT", ".."))
	if err != nil {
		return nil, err
	}
	env := &Env{
		Root:     root,
		Work:     filepath.Join(root, "output", "go_bench"),
		MongoURI: getenv("STAGE1_MONGO", defaultMongo),
	}

	for _, field := range strings.Split(getenv("STAGE1_SIZES", defaultSizes), ",") {
		size, err := strconv.Atoi(strings.TrimSpace(field))
		if err != nil || size <= 0 {
			return nil, fmt.Errorf("STAGE1_SIZES: %q no es un tamaño válido", field)
		}
		env.Sizes = append(env.Sizes, size)
	}

	shared := filepath.Join(root, "shared")
	if env.IDs, err = control.ReadIDs(filepath.Join(shared, "book_ids.txt")); err != nil {
		return nil, err
	}
	if env.Stop, err = tokenizer.LoadStopwords(filepath.Join(shared, "stopwords.txt")); err != nil {
		return nil, err
	}
	data, err := os.ReadFile(filepath.Join(shared, "queries.txt"))
	if err != nil {
		return nil, err
	}
	for _, line := range strings.Split(string(data), "\n") {
		if line = strings.TrimSpace(line); line != "" {
			env.Queries = append(env.Queries, line)
		}
	}
	if len(env.Queries) == 0 {
		return nil, fmt.Errorf("shared/queries.txt está vacío")
	}
	return env, nil
}

// MaxBooks es el número de libros que hacen falta en cache/ para ejecutar
// todas las medidas: el mayor N más los que se añaden después.
func (e *Env) MaxBooks() int {
	largest := 0
	for _, size := range e.Sizes {
		largest = max(largest, size)
	}
	return largest + Extra
}

// Books devuelve los n primeros IDs del dataset y comprueba que estén en
// cache/.
func (e *Env) Books(n int) ([]int, error) {
	if n > len(e.IDs) {
		return nil, fmt.Errorf("hacen falta %d libros y shared/book_ids.txt solo tiene %d", n, len(e.IDs))
	}
	for _, id := range e.IDs[:n] {
		if _, err := os.Stat(filepath.Join(e.Root, "cache", strconv.Itoa(id)+".txt")); err != nil {
			return nil, fmt.Errorf("el libro %d no está en cache/ (ejecuta: go run . download --n=%d)", id, n)
		}
	}
	return e.IDs[:n], nil
}

// Ingest guarda ids en el datalake kind de la carpeta out, leyendo los
// libros de cache/ como hace el pipeline con --offline. Los que ya estén en
// out se saltan.
func (e *Env) Ingest(kind, out string, ids []int) (*control.Pipeline, error) {
	store, err := datalake.New(kind, out)
	if err != nil {
		return nil, err
	}
	state, err := control.Open(filepath.Join(out, "control"))
	if err != nil {
		return nil, err
	}
	pipeline := &control.Pipeline{
		Store:  store,
		State:  state,
		Source: ingestion.Cache{Dir: filepath.Join(e.Root, "cache")},
	}
	summary, err := pipeline.Download(ids)
	if err != nil {
		return nil, err
	}
	if summary.Discarded > 0 {
		return nil, fmt.Errorf("%d libros del dataset no tienen marcadores: hay que quitarlos de shared/book_ids.txt", summary.Discarded)
	}
	return pipeline, nil
}

// Source es un datalake con todos los libros del experimento. De él salen
// los bodies y las cabeceras para las medidas del índice y de los metadatos,
// que así no dependen de la variante de datalake.
func (e *Env) Source() (datalake.Store, error) {
	if e.source != nil {
		return e.source, nil
	}
	ids, err := e.Books(e.MaxBooks())
	if err != nil {
		return nil, err
	}
	pipeline, err := e.Ingest("book", filepath.Join(e.Work, "source"), ids)
	if err != nil {
		return nil, err
	}
	e.source = pipeline.Store
	return e.source, nil
}

// Postings lee y tokeniza los bodies de ids: es la parte de la indexación
// común a las tres variantes del índice.
func (e *Env) Postings(store datalake.Store, ids []int) (map[string][]int, error) {
	postings := make(map[string][]int)
	for _, id := range ids {
		loc, ok := store.Locate(id)
		if !ok {
			return nil, fmt.Errorf("el libro %d no está en el datalake", id)
		}
		body, err := os.ReadFile(loc.Body)
		if err != nil {
			return nil, err
		}
		for _, term := range tokenizer.Terms(body, e.Stop) {
			postings[term] = append(postings[term], id)
		}
	}
	return postings, nil
}

// Records extrae los metadatos de ids.
func (e *Env) Records(store datalake.Store, ids []int) ([]metadata.Book, error) {
	books := make([]metadata.Book, 0, len(ids))
	for _, id := range ids {
		loc, ok := store.Locate(id)
		if !ok {
			return nil, fmt.Errorf("el libro %d no está en el datalake", id)
		}
		header, err := os.ReadFile(loc.Header)
		if err != nil {
			return nil, err
		}
		book := metadata.Extract(id, string(header))
		book.BodyPath = filepath.ToSlash(loc.Body)
		books = append(books, book)
	}
	return books, nil
}

// OpenIndex abre una variante del índice dentro de datamarts. La de MongoDB
// usa una colección propia para no tocar la del pipeline.
func (e *Env) OpenIndex(kind, datamarts string) (index.Index, error) {
	return index.New(kind, index.Config{Datamarts: datamarts, MongoURI: e.MongoURI, Lang: benchLang})
}

// Sample elige Samples elementos al azar (con repetición) con la semilla
// acordada.
func Sample[T any](items []T) []T {
	rng := rand.New(rand.NewSource(Seed))
	sample := make([]T, Samples)
	for i := range sample {
		sample[i] = items[rng.Intn(len(items))]
	}
	return sample
}
