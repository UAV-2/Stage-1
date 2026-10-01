package index

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"

	"stage1_go/internal/fileutil"
)

// folderIndex: datamarts/inverted_index/<LETRA>/<término>.txt, con un ID por
// línea. La subcarpeta es la primera letra del término en mayúscula.
// Actualizarlo solo reescribe los ficheros de los términos afectados.
type folderIndex struct {
	root string
}

func (f *folderIndex) Name() string { return "folders" }

func (f *folderIndex) path(term string) string {
	return filepath.Join(f.root, strings.ToUpper(term[:1]), term+".txt")
}

func (f *folderIndex) Add(postings map[string][]int) error {
	created := make(map[string]bool)
	for term, ids := range postings {
		existing, err := f.Lookup(term)
		if err != nil {
			return err
		}

		path := f.path(term)
		if dir := filepath.Dir(path); !created[dir] {
			if err := os.MkdirAll(dir, 0755); err != nil {
				return err
			}
			created[dir] = true
		}

		var content []byte
		for _, id := range merge(existing, ids) {
			content = strconv.AppendInt(content, int64(id), 10)
			content = append(content, '\n')
		}
		// Atómica: un fichero cortado a mitad dejaría un ID truncado que
		// parecería un posting válido.
		if err := fileutil.AtomicWrite(path, content); err != nil {
			return err
		}
	}
	return nil
}

func (f *folderIndex) Lookup(term string) ([]int, error) {
	data, err := os.ReadFile(f.path(term))
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var ids []int
	for _, line := range strings.Fields(string(data)) {
		id, err := strconv.Atoi(line)
		if err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, nil
}

func (f *folderIndex) Each(fn func(term string, ids []int) error) error {
	letters, err := os.ReadDir(f.root)
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	// ReadDir devuelve las carpetas A..Z ya ordenadas; dentro de cada una se
	// ordena por término, no por nombre de fichero.
	for _, letter := range letters {
		if !letter.IsDir() {
			continue
		}
		files, err := os.ReadDir(filepath.Join(f.root, letter.Name()))
		if err != nil {
			return err
		}
		var terms []string
		for _, file := range files {
			if term, ok := strings.CutSuffix(file.Name(), ".txt"); ok {
				terms = append(terms, term)
			}
		}
		slices.Sort(terms)
		for _, term := range terms {
			ids, err := f.Lookup(term)
			if err != nil {
				return err
			}
			if err := fn(term, ids); err != nil {
				return err
			}
		}
	}
	return nil
}

func (f *folderIndex) Empty() (bool, error) {
	letters, err := os.ReadDir(f.root)
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return false, err
	}
	return len(letters) == 0, nil
}

// DiskUsage suma el tamaño de los ficheros; el espacio real es mayor, porque
// cada fichero ocupa al menos un bloque del sistema de ficheros.
func (f *folderIndex) DiskUsage() (int64, error) {
	_, _, size, err := fileutil.TreeStats(f.root)
	return size, err
}

func (f *folderIndex) Reset() error { return os.RemoveAll(f.root) }

func (f *folderIndex) Close() error { return nil }
