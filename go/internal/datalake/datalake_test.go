package datalake

import (
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"
)

func read(t *testing.T, path string) string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

// countFiles cuenta los ficheros que hay bajo root.
func countFiles(t *testing.T, root string) int {
	t.Helper()
	count := 0
	err := filepath.WalkDir(root, func(_ string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			count++
		}
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	return count
}

func TestRutas(t *testing.T) {
	fixed := time.Date(2026, 9, 30, 7, 5, 0, 0, time.Local)
	tests := []struct {
		kind   string
		id     int
		header string
		body   string
	}{
		{"time", 1342, "datalake_time/20260930/07/1342.header.txt", "datalake_time/20260930/07/1342.body.txt"},
		{"book", 1342, "datalake_book/1342/header.txt", "datalake_book/1342/body.txt"},
		{"range", 1342, "datalake_range/1000-1999/1342.header.txt", "datalake_range/1000-1999/1342.body.txt"},
		{"range", 11, "datalake_range/0-999/11.header.txt", "datalake_range/0-999/11.body.txt"},
		{"range", 1000, "datalake_range/1000-1999/1000.header.txt", "datalake_range/1000-1999/1000.body.txt"},
	}
	for _, tt := range tests {
		t.Run(tt.kind, func(t *testing.T) {
			out := t.TempDir()
			store, err := New(tt.kind, out)
			if err != nil {
				t.Fatal(err)
			}
			if ts, ok := store.(*timeStore); ok {
				ts.now = func() time.Time { return fixed }
			}

			loc, err := store.Save(tt.id, "cabecera", "cuerpo")
			if err != nil {
				t.Fatal(err)
			}
			want := Location{
				Header: filepath.Join(out, filepath.FromSlash(tt.header)),
				Body:   filepath.Join(out, filepath.FromSlash(tt.body)),
			}
			if loc != want {
				t.Errorf("Save = %+v, se esperaba %+v", loc, want)
			}
			if got := read(t, loc.Header); got != "cabecera" {
				t.Errorf("header = %q", got)
			}
			if got := read(t, loc.Body); got != "cuerpo" {
				t.Errorf("body = %q", got)
			}
		})
	}
}

func TestLocalizarYListar(t *testing.T) {
	for _, kind := range []string{"time", "book", "range"} {
		t.Run(kind, func(t *testing.T) {
			out := t.TempDir()
			store, err := New(kind, out)
			if err != nil {
				t.Fatal(err)
			}
			for _, id := range []int{1342, 11, 84} {
				if _, err := store.Save(id, "h", "b"); err != nil {
					t.Fatal(err)
				}
			}

			// Se reabre para comprobar que no depende del estado en memoria.
			store, err = New(kind, out)
			if err != nil {
				t.Fatal(err)
			}
			if _, ok := store.Locate(84); !ok {
				t.Error("no se encuentra el libro 84")
			}
			if _, ok := store.Locate(999); ok {
				t.Error("se encuentra un libro que no se ha guardado")
			}
			ids, err := store.List()
			if err != nil {
				t.Fatal(err)
			}
			if want := []int{11, 84, 1342}; !slices.Equal(ids, want) {
				t.Errorf("List = %v, se esperaba %v", ids, want)
			}
		})
	}
}

func TestLibroIncompletoNoCuenta(t *testing.T) {
	for _, kind := range []string{"time", "book", "range"} {
		t.Run(kind, func(t *testing.T) {
			store, err := New(kind, t.TempDir())
			if err != nil {
				t.Fatal(err)
			}
			loc, err := store.Save(84, "h", "b")
			if err != nil {
				t.Fatal(err)
			}
			if err := os.Remove(loc.Body); err != nil {
				t.Fatal(err)
			}

			if _, ok := store.Locate(84); ok {
				t.Error("se localiza un libro sin body")
			}
			if ids, _ := store.List(); len(ids) != 0 {
				t.Errorf("List = %v, se esperaba vacío", ids)
			}
		})
	}
}

// Guardar dos veces el mismo libro no puede dejarlo duplicado, ni siquiera en
// time cuando la segunda vez cae en otra hora.
func TestGuardarDosVecesNoDuplica(t *testing.T) {
	for _, kind := range []string{"time", "book", "range"} {
		t.Run(kind, func(t *testing.T) {
			out := t.TempDir()
			store, err := New(kind, out)
			if err != nil {
				t.Fatal(err)
			}
			first, err := store.Save(84, "h", "viejo")
			if err != nil {
				t.Fatal(err)
			}
			before := countFiles(t, out)

			store, err = New(kind, out)
			if err != nil {
				t.Fatal(err)
			}
			if ts, ok := store.(*timeStore); ok {
				ts.now = func() time.Time { return time.Now().Add(3 * time.Hour) }
			}
			second, err := store.Save(84, "h", "nuevo")
			if err != nil {
				t.Fatal(err)
			}

			if first != second {
				t.Errorf("el libro ha cambiado de sitio: %+v -> %+v", first, second)
			}
			if after := countFiles(t, out); after != before {
				t.Errorf("ficheros: %d -> %d", before, after)
			}
			if got := read(t, second.Body); got != "nuevo" {
				t.Errorf("body = %q", got)
			}
		})
	}
}

func TestVarianteDesconocida(t *testing.T) {
	if _, err := New("batch", t.TempDir()); err == nil {
		t.Error("se esperaba un error")
	}
}
