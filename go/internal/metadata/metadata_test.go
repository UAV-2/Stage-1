package metadata

import (
	"os"
	"path/filepath"
	"slices"
	"testing"
)

func TestExtract(t *testing.T) {
	tests := []struct {
		name   string
		header string
		want   Book
	}{
		{
			name:   "cabecera normal",
			header: "The Project Gutenberg eBook of Pride and Prejudice\n\nTitle: Pride and Prejudice\n\nAuthor: Jane Austen\n\nRelease date: June 1, 1998\n\nLanguage: English",
			want:   Book{ID: 7, Title: "Pride and Prejudice", Author: "Jane Austen", Language: "en"},
		},
		{
			name:   "mayúsculas y espacios",
			header: "TITLE:   Moby Dick  \nauthor:Herman Melville\nLANGUAGE:\tFrench",
			want:   Book{ID: 7, Title: "Moby Dick", Author: "Herman Melville", Language: "fr"},
		},
		{
			name:   "primera coincidencia y continuación ignorada",
			header: "Title: The Life and Adventures\n       of Robinson Crusoe\nTitle: Otro\nLanguage: Spanish",
			want:   Book{ID: 7, Title: "The Life and Adventures", Language: "es"},
		},
		{
			name:   "idioma sin código",
			header: "Title: Kalevala\nLanguage: Finnish",
			want:   Book{ID: 7, Title: "Kalevala", Language: "finnish"},
		},
		{
			name:   "la etiqueta tiene que estar al principio de la línea",
			header: "Original Title: No\n  Author: Tampoco\nLanguage: German",
			want:   Book{ID: 7, Language: "de"},
		},
		{
			name:   "campos vacíos",
			header: "Title:\nAuthor:   \nsin idioma",
			want:   Book{ID: 7},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := Extract(7, tt.header); got != tt.want {
				t.Errorf("Extract = %+v, se esperaba %+v", got, tt.want)
			}
		})
	}
}

func TestDB(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "datamarts", "metadata.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if empty, err := db.Empty(); err != nil || !empty {
		t.Errorf("Empty = %v, %v", empty, err)
	}

	books := []Book{
		{ID: 1342, Title: "Pride and Prejudice", Author: "Jane Austen", Language: "en", BodyPath: "datalake_book/1342/body.txt"},
		{ID: 11, Title: "Alice", BodyPath: "datalake_book/11/body.txt"},
	}
	if err := db.Insert(books); err != nil {
		t.Fatal(err)
	}
	// Repetir la inserción (reindexar tras un corte) no duplica filas.
	if err := db.Insert(books[:1]); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	all, err := db.All()
	if err != nil {
		t.Fatal(err)
	}
	if len(all) != 2 || all[0] != books[1] || all[1] != books[0] {
		t.Errorf("All = %+v", all)
	}

	// Los campos ausentes se guardan como NULL, no como cadena vacía.
	var nulls int
	if err := db.db.QueryRow(`SELECT COUNT(*) FROM books WHERE author IS NULL AND language IS NULL`).Scan(&nulls); err != nil {
		t.Fatal(err)
	}
	if nulls != 1 {
		t.Errorf("filas con NULL = %d, se esperaba 1", nulls)
	}

	// Consultas: por ID (para llegar al body) y por campos.
	if book, ok, err := db.ByID(1342); err != nil || !ok || book.BodyPath != "datalake_book/1342/body.txt" {
		t.Errorf("ByID(1342) = %+v, %v, %v", book, ok, err)
	}
	if _, ok, err := db.ByID(999); err != nil || ok {
		t.Errorf("ByID(999) = %v, %v", ok, err)
	}
	for _, tt := range []struct {
		filter Filter
		want   []int
	}{
		{Filter{Author: "Jane Austen"}, []int{1342}},
		{Filter{Title: "Alice"}, []int{11}},
		{Filter{Language: "en"}, []int{1342}},
		{Filter{Author: "Jane Austen", Title: "Alice"}, nil},
		{Filter{Author: "jane austen"}, nil},
		{Filter{}, []int{11, 1342}},
	} {
		found, err := db.Find(tt.filter)
		if err != nil {
			t.Fatal(err)
		}
		var ids []int
		for _, book := range found {
			ids = append(ids, book.ID)
		}
		if !slices.Equal(ids, tt.want) {
			t.Errorf("Find(%+v) = %v, se esperaba %v", tt.filter, ids, tt.want)
		}
	}

	dump := filepath.Join(dir, "metadata.tsv")
	if err := db.DumpTSV(dump); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(dump)
	if err != nil {
		t.Fatal(err)
	}
	if want := "11\tAlice\t\t\n1342\tPride and Prejudice\tJane Austen\ten\n"; string(data) != want {
		t.Errorf("dump = %q, se esperaba %q", data, want)
	}

	if err := db.Reset(); err != nil {
		t.Fatal(err)
	}
	if empty, err := db.Empty(); err != nil || !empty {
		t.Errorf("Empty tras Reset = %v, %v", empty, err)
	}
}
