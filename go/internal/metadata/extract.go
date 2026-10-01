// Package metadata extrae título, autor e idioma de la cabecera de cada libro
// y los guarda en SQLite.
package metadata

import (
	"regexp"
	"strings"
)

var (
	titleRegex    = regexp.MustCompile(`(?i)^Title:\s*(.+)$`)
	authorRegex   = regexp.MustCompile(`(?i)^Author:\s*(.+)$`)
	languageRegex = regexp.MustCompile(`(?i)^Language:\s*(.+)$`)
)

var languageCodes = map[string]string{
	"English": "en",
	"Spanish": "es",
	"French":  "fr",
	"German":  "de",
}

// Book es una fila de la tabla books. Un campo vacío significa que el dato
// no está en la cabecera y se guarda como NULL.
type Book struct {
	ID       int
	Title    string
	Author   string
	Language string
	BodyPath string
}

// Extract busca cada campo línea a línea y se queda con la primera
// coincidencia. Si el título continúa en la línea siguiente, la continuación
// se ignora.
func Extract(id int, header string) Book {
	lines := strings.Split(header, "\n")
	return Book{
		ID:       id,
		Title:    firstMatch(titleRegex, lines),
		Author:   firstMatch(authorRegex, lines),
		Language: normalizeLanguage(firstMatch(languageRegex, lines)),
	}
}

func firstMatch(re *regexp.Regexp, lines []string) string {
	for _, line := range lines {
		if match := re.FindStringSubmatch(line); match != nil {
			return strings.TrimSpace(match[1])
		}
	}
	return ""
}

func normalizeLanguage(language string) string {
	if code, ok := languageCodes[language]; ok {
		return code
	}
	return strings.ToLower(language)
}
