package datalake

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"strconv"
)

// bookStore: datalake_book/<id>/header.txt y body.txt
type bookStore struct {
	root string
}

func (s *bookStore) Name() string { return "book" }
func (s *bookStore) Root() string { return s.root }

func (s *bookStore) location(id int) Location {
	dir := filepath.Join(s.root, strconv.Itoa(id))
	return Location{
		Header: filepath.Join(dir, "header.txt"),
		Body:   filepath.Join(dir, "body.txt"),
	}
}

func (s *bookStore) Save(id int, header, body string) (Location, error) {
	loc := s.location(id)
	return loc, write(loc, header, body)
}

func (s *bookStore) Locate(id int) (Location, bool) {
	loc := s.location(id)
	return loc, complete(loc)
}

func (s *bookStore) List() ([]int, error) {
	entries, err := os.ReadDir(s.root)
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return nil, err
	}
	var ids []int
	for _, entry := range entries {
		id, err := strconv.Atoi(entry.Name())
		if err != nil {
			continue
		}
		if _, ok := s.Locate(id); ok {
			ids = append(ids, id)
		}
	}
	slices.Sort(ids)
	return ids, nil
}
