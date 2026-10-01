package index

import (
	"os"
	"path/filepath"
	"slices"
	"testing"

	"stage1_go/internal/tokenizer"
)

// open abre una variante sobre dir. La de MongoDB solo se prueba si
// MONGO_URI apunta a un servidor, y usa una colección propia.
func open(t *testing.T, kind, dir string) Index {
	t.Helper()
	cfg := Config{Datamarts: dir, Lang: "test"}
	if kind == "mongo" {
		cfg.MongoURI = os.Getenv("MONGO_URI")
		if cfg.MongoURI == "" {
			t.Skip("MONGO_URI no está definida")
		}
	}
	idx, err := New(kind, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { idx.Close() })
	return idx
}

func readFile(t *testing.T, path string) string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

var kinds = []string{"json", "folders", "mongo"}

func TestContenidoComun(t *testing.T) {
	var dumps []string
	for _, kind := range kinds {
		t.Run(kind, func(t *testing.T) {
			dir := t.TempDir()
			idx := open(t, kind, dir)
			if err := idx.Reset(); err != nil {
				t.Fatal(err)
			}
			if empty, err := idx.Empty(); err != nil || !empty {
				t.Fatalf("Empty = %v, %v", empty, err)
			}

			// Dos lotes con IDs desordenados y un libro repetido.
			if err := idx.Add(map[string][]int{"island": {1342, 5}, "adventure": {42, 5}}); err != nil {
				t.Fatal(err)
			}
			if err := idx.Add(map[string][]int{"adventure": {12, 5}, "whale": {2701}}); err != nil {
				t.Fatal(err)
			}

			// Se reabre para comprobar que todo está en disco.
			idx = open(t, kind, dir)
			for term, want := range map[string][]int{
				"adventure": {5, 12, 42},
				"island":    {5, 1342},
				"whale":     {2701},
				"missing":   nil,
			} {
				got, err := idx.Lookup(term)
				if err != nil {
					t.Fatal(err)
				}
				if !slices.Equal(got, want) {
					t.Errorf("Lookup(%q) = %v, se esperaba %v", term, got, want)
				}
			}

			stop := tokenizer.Stopwords{"the": {}}
			for query, want := range map[string][]int{
				"Adventure":            {5, 12, 42},
				"the island ADVENTURE": {5},
				"whale island":         nil,
				"island missing":       nil,
				"the":                  nil,
			} {
				got, err := Search(idx, query, stop)
				if err != nil {
					t.Fatal(err)
				}
				if !slices.Equal(got, want) {
					t.Errorf("Search(%q) = %v, se esperaba %v", query, got, want)
				}
			}

			if size, err := idx.DiskUsage(); err != nil || size <= 0 {
				t.Errorf("DiskUsage = %d, %v", size, err)
			}

			dump := filepath.Join(dir, "index.tsv")
			if err := DumpTSV(idx, dump); err != nil {
				t.Fatal(err)
			}
			dumps = append(dumps, readFile(t, dump))

			if err := idx.Reset(); err != nil {
				t.Fatal(err)
			}
			if empty, err := idx.Empty(); err != nil || !empty {
				t.Errorf("Empty tras Reset = %v, %v", empty, err)
			}
		})
	}

	// Las variantes que se han podido probar dan el mismo dump canónico.
	want := "adventure\t5,12,42\nisland\t5,1342\nwhale\t2701\n"
	for _, dump := range dumps {
		if dump != want {
			t.Errorf("dump = %q, se esperaba %q", dump, want)
		}
	}
}

func TestFormatoJSON(t *testing.T) {
	dir := t.TempDir()
	idx := open(t, "json", dir)
	if err := idx.Add(map[string][]int{"island": {1342, 5}, "adventure": {42, 12, 5}}); err != nil {
		t.Fatal(err)
	}
	got := readFile(t, filepath.Join(dir, "inverted_index.json"))
	if want := `{"adventure":[5,12,42],"island":[5,1342]}`; got != want {
		t.Errorf("inverted_index.json = %s, se esperaba %s", got, want)
	}
}

func TestFormatoCarpetas(t *testing.T) {
	dir := t.TempDir()
	idx := open(t, "folders", dir)
	if err := idx.Add(map[string][]int{"island": {1342, 5}, "adventure": {42, 12, 5}, "abc": {1}, "abcd": {2}}); err != nil {
		t.Fatal(err)
	}
	if got := readFile(t, filepath.Join(dir, "inverted_index", "A", "adventure.txt")); got != "5\n12\n42\n" {
		t.Errorf("A/adventure.txt = %q", got)
	}
	if got := readFile(t, filepath.Join(dir, "inverted_index", "I", "island.txt")); got != "5\n1342\n" {
		t.Errorf("I/island.txt = %q", got)
	}

	var terms []string
	err := idx.Each(func(term string, _ []int) error {
		terms = append(terms, term)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"abc", "abcd", "adventure", "island"}; !slices.Equal(terms, want) {
		t.Errorf("orden = %v, se esperaba %v", terms, want)
	}
}

func TestVarianteDesconocida(t *testing.T) {
	if _, err := New("sqlite", Config{Datamarts: t.TempDir()}); err == nil {
		t.Error("se esperaba un error")
	}
}
