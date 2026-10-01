package ingestion

import (
	"errors"
	"testing"
)

func TestSplit(t *testing.T) {
	tests := []struct {
		name   string
		raw    string
		header string
		body   string
	}{
		{
			name:   "marcadores actuales",
			raw:    "Title: A\n\n*** START OF THE PROJECT GUTENBERG EBOOK A ***\n\nTexto\n\n*** END OF THE PROJECT GUTENBERG EBOOK A ***\nLicencia",
			header: "Title: A",
			body:   "Texto",
		},
		{
			name:   "libro antiguo con THIS y sin espacio",
			raw:    "Title: B\n***START OF THIS PROJECT GUTENBERG EBOOK B***\nTexto\n***END OF THIS PROJECT GUTENBERG EBOOK B***",
			header: "Title: B",
			body:   "Texto",
		},
		{
			name:   "minúsculas",
			raw:    "Title: C\n*** start of the project gutenberg ebook c ***\nTexto\n*** end of the project gutenberg ebook c ***",
			header: "Title: C",
			body:   "Texto",
		},
		{
			name:   "saltos de línea de Windows",
			raw:    "Title: D\r\n*** START OF THE PROJECT GUTENBERG EBOOK D ***\r\nUno\r\nDos\r\n*** END OF THE PROJECT GUTENBERG EBOOK D ***\r\n",
			header: "Title: D",
			body:   "Uno\nDos",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			book, err := Split(1, tt.raw)
			if err != nil {
				t.Fatal(err)
			}
			if book.Header != tt.header || book.Body != tt.body {
				t.Errorf("header=%q body=%q, se esperaba header=%q body=%q", book.Header, book.Body, tt.header, tt.body)
			}
		})
	}
}

func TestSplitSinMarcadores(t *testing.T) {
	for _, raw := range []string{
		"texto sin marcadores",
		"*** START OF THE PROJECT GUTENBERG EBOOK A ***\nsin final",
		"sin principio\n*** END OF THE PROJECT GUTENBERG EBOOK A ***",
	} {
		if _, err := Split(1, raw); !errors.Is(err, ErrUnavailable) {
			t.Errorf("Split(%q) = %v, se esperaba ErrUnavailable", raw, err)
		}
	}
}
