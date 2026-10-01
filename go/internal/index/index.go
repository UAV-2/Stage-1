// Package index implementa el índice invertido (término -> libros) sobre tres
// estructuras distintas con el mismo contenido lógico.
package index

import (
	"bytes"
	"fmt"
	"path/filepath"
	"slices"
	"strconv"

	"stage1_go/internal/fileutil"
	"stage1_go/internal/tokenizer"
)

// Index es el contrato común a las tres variantes. Los postings son siempre
// IDs de libro únicos y en orden ascendente.
type Index interface {
	Name() string
	// Add fusiona un lote de postings con lo que ya hay en el índice.
	// Repetir el mismo lote no cambia el resultado.
	Add(postings map[string][]int) error
	// Lookup devuelve los libros de un término; vacío si no está.
	Lookup(term string) ([]int, error)
	// Each recorre el índice entero en orden alfabético de término.
	Each(fn func(term string, ids []int) error) error
	Empty() (bool, error)
	// DiskUsage devuelve los bytes que ocupa el índice.
	DiskUsage() (int64, error)
	// Reset borra el índice para reconstruirlo desde cero.
	Reset() error
	Close() error
}

// Config reúne lo que necesita cada variante para abrirse.
type Config struct {
	Datamarts string // carpeta output/<lang>/datamarts
	MongoURI  string
	Lang      string // sufijo de la colección de MongoDB
}

func New(kind string, cfg Config) (Index, error) {
	switch kind {
	case "json":
		return &jsonIndex{path: filepath.Join(cfg.Datamarts, "inverted_index.json")}, nil
	case "folders":
		return &folderIndex{root: filepath.Join(cfg.Datamarts, "inverted_index")}, nil
	case "mongo":
		return newMongoIndex(cfg.MongoURI, cfg.Lang)
	}
	return nil, fmt.Errorf("índice desconocido %q (json, folders o mongo)", kind)
}

// merge une dos listas de IDs y deja el resultado ordenado y sin repetidos.
func merge(existing, added []int) []int {
	merged := slices.Concat(existing, added)
	slices.Sort(merged)
	return slices.Compact(merged)
}

// intersect devuelve los IDs comunes a dos listas ordenadas.
func intersect(a, b []int) []int {
	var common []int
	for i, j := 0, 0; i < len(a) && j < len(b); {
		switch {
		case a[i] < b[j]:
			i++
		case a[i] > b[j]:
			j++
		default:
			common = append(common, a[i])
			i++
			j++
		}
	}
	return common
}

// Search normaliza la consulta con las mismas reglas que la indexación y
// devuelve los libros que contienen todos sus términos (AND).
func Search(idx Index, query string, stop tokenizer.Stopwords) ([]int, error) {
	terms := tokenizer.Terms([]byte(query), stop)
	if len(terms) == 0 {
		return nil, nil
	}
	result, err := idx.Lookup(terms[0])
	if err != nil {
		return nil, err
	}
	for _, term := range terms[1:] {
		if len(result) == 0 {
			break
		}
		ids, err := idx.Lookup(term)
		if err != nil {
			return nil, err
		}
		result = intersect(result, ids)
	}
	return result, nil
}

// DumpTSV escribe el dump canónico: término\tid1,id2,id3, ordenado por
// término.
func DumpTSV(idx Index, path string) error {
	var buf bytes.Buffer
	err := idx.Each(func(term string, ids []int) error {
		buf.WriteString(term)
		buf.WriteByte('\t')
		for i, id := range ids {
			if i > 0 {
				buf.WriteByte(',')
			}
			buf.WriteString(strconv.Itoa(id))
		}
		buf.WriteByte('\n')
		return nil
	})
	if err != nil {
		return err
	}
	return fileutil.AtomicWrite(path, buf.Bytes())
}
