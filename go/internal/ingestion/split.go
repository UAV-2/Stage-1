// Package ingestion obtiene el texto crudo de los libros y lo separa en
// cabecera y cuerpo.
package ingestion

import (
	"errors"
	"fmt"
	"regexp"
	"strings"
)

var (
	// Marcadores case-insensitive
	startRegex = regexp.MustCompile(`(?i)\*\*\* ?START OF (THE|THIS) PROJECT GUTENBERG EBOOK[^\n]*\n`)
	endRegex   = regexp.MustCompile(`(?i)\*\*\* ?END OF (THE|THIS) PROJECT GUTENBERG EBOOK`)
)

// ErrUnavailable marca los libros que no se pueden ingerir (no existen o no
// tienen marcadores). Se descartan: reintentarlos daría el mismo resultado.
var ErrUnavailable = errors.New("libro no disponible")

// Book es un libro ya separado.
type Book struct {
	ID     int
	Header string
	Body   string
}

// Split separa el texto crudo: header es todo lo anterior al START y body lo
// que hay entre el final de la línea START y el END. El footer se descarta.
func Split(id int, raw string) (Book, error) {
	text := strings.ReplaceAll(raw, "\r\n", "\n")

	startMatch := startRegex.FindStringIndex(text)
	endMatch := endRegex.FindStringIndex(text)
	if startMatch == nil || endMatch == nil || endMatch[0] < startMatch[1] {
		return Book{}, fmt.Errorf("%w: marcadores no encontrados en el libro %d", ErrUnavailable, id)
	}

	return Book{
		ID:     id,
		Header: strings.TrimSpace(text[:startMatch[0]]),
		Body:   strings.TrimSpace(text[startMatch[1]:endMatch[0]]),
	}, nil
}
