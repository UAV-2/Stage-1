package control

import (
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"testing"

	"stage1_go/internal/datalake"
	"stage1_go/internal/ingestion"
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
	return fmt.Sprintf("Title: Libro %d\n*** START OF THE PROJECT GUTENBERG EBOOK ***\ncuerpo %d\n*** END OF THE PROJECT GUTENBERG EBOOK ***\n", id, id), nil
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
