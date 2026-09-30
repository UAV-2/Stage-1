package tokenizer

import (
	"os"
	"path/filepath"
	"slices"
	"testing"
)

func TestTerms(t *testing.T) {
	stop := Stopwords{"the": {}, "and": {}}
	tests := []struct {
		text string
		want []string
	}{
		{"don't", []string{"don"}},
		{"well-known", []string{"well", "known"}},
		{"café", []string{"caf"}},
		{"The ISLAND and the Island", []string{"island"}},
		{"a I x", nil},
		{"chapter 12: snake_case", []string{"chapter", "snake", "case"}},
		// Las mayúsculas fuera de A-Z no se convierten: separan.
		{"ÉCOLE Ángel", []string{"cole", "ngel"}},
		{"sea, ship; sea. SHIP!", []string{"sea", "ship"}},
		{"", nil},
	}
	for _, tt := range tests {
		if got := Terms([]byte(tt.text), stop); !slices.Equal(got, tt.want) {
			t.Errorf("Terms(%q) = %v, se esperaba %v", tt.text, got, tt.want)
		}
	}
}

func TestLoadStopwords(t *testing.T) {
	path := filepath.Join(t.TempDir(), "stopwords.txt")
	if err := os.WriteFile(path, []byte("the\r\nand\n\nof"), 0644); err != nil {
		t.Fatal(err)
	}
	stop, err := LoadStopwords(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(stop) != 3 {
		t.Errorf("stopwords = %v", stop)
	}
	if got := Terms([]byte("the tale of two cities"), stop); !slices.Equal(got, []string{"tale", "two", "cities"}) {
		t.Errorf("Terms = %v", got)
	}
}
