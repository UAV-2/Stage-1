// Package tokenizer convierte un texto en los términos que se indexan. Las
// reglas tienen que dar el mismo resultado en Python, Java y Go, así que se
// trabaja byte a byte y no se usa ninguna función Unicode del lenguaje.
package tokenizer

import (
	"os"
	"strings"
)

const minLength = 2

// Stopwords es el conjunto de palabras que no se indexan.
type Stopwords map[string]struct{}

// LoadStopwords lee un fichero con una palabra por línea.
func LoadStopwords(path string) (Stopwords, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	stop := make(Stopwords)
	for _, line := range strings.Split(string(data), "\n") {
		if word := strings.TrimSpace(line); word != "" {
			stop[word] = struct{}{}
		}
	}
	return stop, nil
}

// Terms devuelve los términos distintos de un texto, en orden de aparición:
//
//  1. minúsculas solo para A-Z;
//  2. un token es una secuencia [a-z]+; todo lo demás separa;
//  3. se descartan los tokens de menos de 2 letras;
//  4. se descartan las stopwords;
//  5. cada término aparece una sola vez.
func Terms(text []byte, stop Stopwords) []string {
	var terms []string
	seen := make(map[string]struct{})
	token := make([]byte, 0, 32)

	flush := func() {
		if len(token) >= minLength {
			_, isStopword := stop[string(token)]
			_, repeated := seen[string(token)]
			if !isStopword && !repeated {
				term := string(token)
				seen[term] = struct{}{}
				terms = append(terms, term)
			}
		}
		token = token[:0]
	}

	for _, b := range text {
		if 'A' <= b && b <= 'Z' {
			b += 'a' - 'A'
		}
		if 'a' <= b && b <= 'z' {
			token = append(token, b)
		} else if len(token) > 0 {
			flush()
		}
	}
	flush()
	return terms
}
