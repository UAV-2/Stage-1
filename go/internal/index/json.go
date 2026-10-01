package index

import (
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"slices"

	"stage1_go/internal/fileutil"
)

// jsonIndex: datamarts/inverted_index.json, un único objeto
// {"adventure":[5,12,42],"island":[5,1342]} con las claves ordenadas y sin
// espacios. Actualizarlo es cargar, fusionar y reescribir el fichero entero.
type jsonIndex struct {
	path     string
	postings map[string][]int // nil hasta la primera carga
}

func (j *jsonIndex) Name() string { return "json" }

func (j *jsonIndex) load() error {
	if j.postings != nil {
		return nil
	}
	postings := make(map[string][]int)
	data, err := os.ReadFile(j.path)
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	if err == nil {
		if err := json.Unmarshal(data, &postings); err != nil {
			return err
		}
	}
	j.postings = postings
	return nil
}

func (j *jsonIndex) Add(postings map[string][]int) error {
	if err := j.load(); err != nil {
		return err
	}
	for term, ids := range postings {
		j.postings[term] = merge(j.postings[term], ids)
	}

	// encoding/json ya escribe las claves de un map ordenadas y sin espacios.
	data, err := json.Marshal(j.postings)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(j.path), 0755); err != nil {
		return err
	}
	return fileutil.AtomicWrite(j.path, data)
}

func (j *jsonIndex) Lookup(term string) ([]int, error) {
	if err := j.load(); err != nil {
		return nil, err
	}
	return j.postings[term], nil
}

func (j *jsonIndex) Each(fn func(term string, ids []int) error) error {
	if err := j.load(); err != nil {
		return err
	}
	terms := make([]string, 0, len(j.postings))
	for term := range j.postings {
		terms = append(terms, term)
	}
	slices.Sort(terms)
	for _, term := range terms {
		if err := fn(term, j.postings[term]); err != nil {
			return err
		}
	}
	return nil
}

func (j *jsonIndex) Empty() (bool, error) {
	if err := j.load(); err != nil {
		return false, err
	}
	return len(j.postings) == 0, nil
}

func (j *jsonIndex) DiskUsage() (int64, error) {
	info, err := os.Stat(j.path)
	if errors.Is(err, fs.ErrNotExist) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	return info.Size(), nil
}

func (j *jsonIndex) Reset() error {
	if err := os.Remove(j.path); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	j.postings = make(map[string][]int)
	return nil
}

func (j *jsonIndex) Close() error { return nil }
