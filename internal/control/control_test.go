package control

import (
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"testing"

	"stage1_go/internal/datalake"
	"stage1_go/internal/index"
	"stage1_go/internal/ingestion"
	"stage1_go/internal/metadata"
	"stage1_go/internal/tokenizer"
)

// fakeSource sirve libros válidos salvo los IDs marcados como rotos.
type fakeSource struct {
	broken  map[int]bool
	fetched []int
}

func (f *fakeSource) Fetch(id int) (string, error) {
	f.fetched = append(f.fetched, id)
	if f.broken[id] {
		return "sin marcadores", nil
	}
	return fmt.Sprintf("Title: Libro %d\n*** START OF THE PROJECT GUTENBERG EBOOK ***\ncuerpo del libro %d\n*** END OF THE PROJECT GUTENBERG EBOOK ***\n", id, id), nil
}

func newPipeline(t *testing.T, kind, out string, source ingestion.Source) *Pipeline {
	t.Helper()
	store, err := datalake.New(kind, out)
	if err != nil {
		t.Fatal(err)
	}
	state, err := Open(filepath.Join(out, "control"))
	if err != nil {
		t.Fatal(err)
	}
	return &Pipeline{Store: store, State: state, Source: source}
}

func readFile(t *testing.T, path string) string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func TestDescargaEnOrdenYSinRepetir(t *testing.T) {
	out := t.TempDir()
	source := &fakeSource{broken: map[int]bool{84: true}}
	pipeline := newPipeline(t, "book", out, source)

	summary, err := pipeline.Download([]int{1342, 84, 11})
	if err != nil {
		t.Fatal(err)
	}
	if want := (Summary{Downloaded: 2, Discarded: 1}); summary != want {
		t.Errorf("summary = %+v, se esperaba %+v", summary, want)
	}
	if got := readFile(t, filepath.Join(out, "control", "downloaded_books.txt")); got != "1342\n11\n" {
		t.Errorf("downloaded_books.txt = %q", got)
	}
	if got := readFile(t, filepath.Join(out, "control", "failed_books.txt")); got != "84\n" {
		t.Errorf("failed_books.txt = %q", got)
	}
	if _, ok := pipeline.Store.Locate(84); ok {
		t.Error("se ha escrito un libro sin marcadores")
	}

	// Una segunda ejecución solo pide los libros nuevos.
	source.fetched = nil
	pipeline = newPipeline(t, "book", out, source)
	summary, err = pipeline.Download([]int{1342, 84, 11, 1661})
	if err != nil {
		t.Fatal(err)
	}
	if want := (Summary{Downloaded: 1, Skipped: 3}); summary != want {
		t.Errorf("summary = %+v, se esperaba %+v", summary, want)
	}
	if want := []int{1661}; !slices.Equal(source.fetched, want) {
		t.Errorf("fetched = %v, se esperaba %v", source.fetched, want)
	}
	if want := []int{1342, 11, 1661}; !slices.Equal(pipeline.State.Pending(), want) {
		t.Errorf("Pending = %v, se esperaba %v", pipeline.State.Pending(), want)
	}
}

// Simula los dos cortes posibles y comprueba que al reanudar no hay ni
// duplicados ni pérdidas.
func TestReanudarTrasUnCorte(t *testing.T) {
	for _, kind := range []string{"time", "book", "range"} {
		t.Run(kind, func(t *testing.T) {
			out := t.TempDir()
			source := &fakeSource{}
			pipeline := newPipeline(t, kind, out, source)
			if _, err := pipeline.Download([]int{11, 84}); err != nil {
				t.Fatal(err)
			}

			// Corte 1: el libro 1342 llegó al datalake pero no al control.
			loc, err := pipeline.Store.Save(1342, "h", "b")
			if err != nil {
				t.Fatal(err)
			}
			// Corte 2: el libro 1661 se quedó a medio escribir.
			tmp := filepath.Join(filepath.Dir(loc.Body), "1661.body.txt.tmp")
			if err := os.WriteFile(tmp, []byte("a medias"), 0644); err != nil {
				t.Fatal(err)
			}
			// Corte 3: el 84 está registrado pero su body ha desaparecido.
			lost, _ := pipeline.Store.Locate(84)
			if err := os.Remove(lost.Body); err != nil {
				t.Fatal(err)
			}

			source.fetched = nil
			pipeline = newPipeline(t, kind, out, source)
			report, err := pipeline.Recover()
			if err != nil {
				t.Fatal(err)
			}
			if report.TempFiles != 1 || !slices.Equal(report.Unregistered, []int{1342}) || !slices.Equal(report.Missing, []int{84}) {
				t.Errorf("report = %+v", report)
			}
			if _, err := pipeline.Download([]int{11, 84, 1342, 1661}); err != nil {
				t.Fatal(err)
			}

			if want := []int{84, 1661}; !slices.Equal(source.fetched, want) {
				t.Errorf("fetched = %v, se esperaba %v", source.fetched, want)
			}
			downloaded := slices.Clone(pipeline.State.Downloaded())
			slices.Sort(downloaded)
			inDatalake, err := pipeline.Store.List()
			if err != nil {
				t.Fatal(err)
			}
			want := []int{11, 84, 1342, 1661}
			if !slices.Equal(downloaded, want) || !slices.Equal(inDatalake, want) {
				t.Errorf("control = %v, datalake = %v, se esperaba %v en ambos", downloaded, inDatalake, want)
			}
		})
	}
}

// Un append cortado a mitad no puede contaminar el siguiente ID.
func TestLineaDeControlCortada(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "downloaded_books.txt")
	if err := os.WriteFile(path, []byte("11\n84\n13"), 0644); err != nil {
		t.Fatal(err)
	}

	state, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if want := []int{11, 84}; !slices.Equal(state.Downloaded(), want) {
		t.Errorf("Downloaded = %v, se esperaba %v", state.Downloaded(), want)
	}
	if err := state.MarkDownloaded(1342); err != nil {
		t.Fatal(err)
	}
	if got := readFile(t, path); got != "11\n84\n1342\n" {
		t.Errorf("downloaded_books.txt = %q", got)
	}
}

func TestReadIDs(t *testing.T) {
	path := filepath.Join(t.TempDir(), "book_ids.txt")
	if err := os.WriteFile(path, []byte("# dataset\n11\r\n84\n\n1342"), 0644); err != nil {
		t.Fatal(err)
	}
	ids, err := ReadIDs(path)
	if err != nil {
		t.Fatal(err)
	}
	if want := []int{11, 84, 1342}; !slices.Equal(ids, want) {
		t.Errorf("ReadIDs = %v, se esperaba %v", ids, want)
	}
	// Leer la lista compartida nunca la modifica.
	if got := readFile(t, path); got != "# dataset\n11\r\n84\n\n1342" {
		t.Errorf("el fichero ha cambiado: %q", got)
	}
}

// withDatamarts añade a un pipeline los datamarts de out, con la variante de
// índice indicada.
func withDatamarts(t *testing.T, pipeline *Pipeline, kind, out string) *Pipeline {
	t.Helper()
	datamarts := filepath.Join(out, "datamarts")
	db, err := metadata.Open(filepath.Join(datamarts, "metadata.db"))
	if err != nil {
		t.Fatal(err)
	}
	idx, err := index.New(kind, index.Config{Datamarts: datamarts})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		idx.Close()
		db.Close()
	})
	pipeline.Metadata = db
	pipeline.Index = idx
	pipeline.Datamarts = datamarts
	pipeline.Stopwords = tokenizer.Stopwords{"del": {}}
	return pipeline
}

func lookupTerm(t *testing.T, pipeline *Pipeline, term string) []int {
	t.Helper()
	ids, err := pipeline.Index.Lookup(term)
	if err != nil {
		t.Fatal(err)
	}
	return ids
}

func TestCicloCompleto(t *testing.T) {
	for _, kind := range []string{"json", "folders"} {
		t.Run(kind, func(t *testing.T) {
			out := t.TempDir()
			source := &fakeSource{broken: map[int]bool{84: true}}
			pipeline := withDatamarts(t, newPipeline(t, "book", out, source), kind, out)

			summary, err := pipeline.Run([]int{1342, 84, 11, 1661}, 2)
			if err != nil {
				t.Fatal(err)
			}
			if want := (Summary{Downloaded: 3, Discarded: 1, Indexed: 3}); summary != want {
				t.Errorf("summary = %+v, se esperaba %+v", summary, want)
			}
			if got := readFile(t, filepath.Join(out, "control", "indexed_books.txt")); got != "1342\n11\n1661\n" {
				t.Errorf("indexed_books.txt = %q", got)
			}
			if len(pipeline.State.Pending()) != 0 {
				t.Errorf("Pending = %v", pipeline.State.Pending())
			}
			if want := []int{11, 1342, 1661}; !slices.Equal(lookupTerm(t, pipeline, "cuerpo"), want) {
				t.Errorf("postings = %v, se esperaba %v", lookupTerm(t, pipeline, "cuerpo"), want)
			}
			if got := lookupTerm(t, pipeline, "del"); got != nil {
				t.Errorf("se ha indexado una stopword: %v", got)
			}

			books, err := pipeline.Metadata.All()
			if err != nil {
				t.Fatal(err)
			}
			want := metadata.Book{ID: 11, Title: "Libro 11", BodyPath: "datalake_book/11/body.txt"}
			if len(books) != 3 || books[0] != want {
				t.Errorf("metadatos = %+v", books)
			}

			// Repetir la ejecución no hace nada.
			summary, err = pipeline.Run([]int{1342, 84, 11, 1661}, 2)
			if err != nil {
				t.Fatal(err)
			}
			if want := (Summary{Skipped: 4}); summary != want {
				t.Errorf("summary = %+v, se esperaba %+v", summary, want)
			}
		})
	}
}

// Un corte entre escribir el índice y marcar el libro como indexado: al
// reanudar se indexa primero lo pendiente y el libro no queda duplicado.
func TestReanudarIndexacion(t *testing.T) {
	out := t.TempDir()
	source := &fakeSource{}
	pipeline := newPipeline(t, "book", out, source)
	if _, err := pipeline.Download([]int{11, 84}); err != nil {
		t.Fatal(err)
	}

	pipeline = withDatamarts(t, newPipeline(t, "book", out, source), "json", out)
	if err := pipeline.Index.Add(map[string][]int{"cuerpo": {11}}); err != nil {
		t.Fatal(err)
	}
	if err := pipeline.Metadata.Insert([]metadata.Book{{ID: 11, Title: "Libro 11", BodyPath: "x"}}); err != nil {
		t.Fatal(err)
	}

	var steps []string
	pipeline.Logf = func(format string, args ...any) {
		steps = append(steps, fmt.Sprintf(format, args...))
	}
	if _, err := pipeline.Recover(); err != nil {
		t.Fatal(err)
	}
	summary, err := pipeline.Run([]int{11, 84, 1342}, 10)
	if err != nil {
		t.Fatal(err)
	}
	if want := (Summary{Downloaded: 1, Skipped: 2, Indexed: 3}); summary != want {
		t.Errorf("summary = %+v, se esperaba %+v", summary, want)
	}
	wantSteps := []string{
		"2 libros indexados (2 términos)",
		"Libro 1342 guardado en el datalake",
		"1 libros indexados (2 términos)",
	}
	if !slices.Equal(steps, wantSteps) {
		t.Errorf("pasos = %q, se esperaba %q", steps, wantSteps)
	}
	if want := []int{11, 84, 1342}; !slices.Equal(lookupTerm(t, pipeline, "cuerpo"), want) {
		t.Errorf("postings = %v, se esperaba %v", lookupTerm(t, pipeline, "cuerpo"), want)
	}
	if books, _ := pipeline.Metadata.All(); len(books) != 3 {
		t.Errorf("metadatos = %+v", books)
	}
}

// El control es único: al cambiar a una variante de índice vacía, o al pedir
// una reconstrucción, los libros ya descargados se vuelven a indexar.
func TestCambioDeIndiceYRebuild(t *testing.T) {
	out := t.TempDir()
	source := &fakeSource{}
	pipeline := withDatamarts(t, newPipeline(t, "book", out, source), "json", out)
	if _, err := pipeline.Run([]int{11, 84}, 0); err != nil {
		t.Fatal(err)
	}

	pipeline = withDatamarts(t, newPipeline(t, "book", out, source), "folders", out)
	report, err := pipeline.Recover()
	if err != nil {
		t.Fatal(err)
	}
	if report.Reindex != 2 {
		t.Errorf("Reindex = %d, se esperaba 2", report.Reindex)
	}
	if indexed, err := pipeline.IndexPending(0); err != nil || indexed != 2 {
		t.Errorf("IndexPending = %d, %v", indexed, err)
	}
	if want := []int{11, 84}; !slices.Equal(lookupTerm(t, pipeline, "cuerpo"), want) {
		t.Errorf("postings = %v, se esperaba %v", lookupTerm(t, pipeline, "cuerpo"), want)
	}

	if err := pipeline.Rebuild(); err != nil {
		t.Fatal(err)
	}
	if got := lookupTerm(t, pipeline, "cuerpo"); got != nil {
		t.Errorf("postings tras Rebuild = %v", got)
	}
	if indexed, err := pipeline.IndexPending(1); err != nil || indexed != 2 {
		t.Errorf("IndexPending = %d, %v", indexed, err)
	}
	if want := []int{11, 84}; !slices.Equal(lookupTerm(t, pipeline, "cuerpo"), want) {
		t.Errorf("postings = %v, se esperaba %v", lookupTerm(t, pipeline, "cuerpo"), want)
	}
}
