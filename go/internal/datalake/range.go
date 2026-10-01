package datalake

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
)

const rangeSize = 1000

// rangeStore: datalake_range/<inicio>-<fin>/<id>.header.txt y <id>.body.txt,
// con rangos de 1000 (0-999, 1000-1999, ...)
type rangeStore struct {
	root string
}

func (s *rangeStore) Name() string { return "range" }
func (s *rangeStore) Root() string { return s.root }

func rangeFolder(id int) string {
	start := id / rangeSize * rangeSize
	return fmt.Sprintf("%d-%d", start, start+rangeSize-1)
}

func (s *rangeStore) location(id int) Location {
	return flatLocation(filepath.Join(s.root, rangeFolder(id)), id)
}

func (s *rangeStore) Save(id int, header, body string) (Location, error) {
	loc := s.location(id)
	return loc, write(loc, header, body)
}

func (s *rangeStore) Locate(id int) (Location, bool) {
	loc := s.location(id)
	return loc, complete(loc)
}

func (s *rangeStore) List() ([]int, error) {
	folders, err := os.ReadDir(s.root)
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return nil, err
	}
	var ids []int
	for _, folder := range folders {
		if !folder.IsDir() {
			continue
		}
		files, err := os.ReadDir(filepath.Join(s.root, folder.Name()))
		if err != nil {
			return nil, err
		}
		for _, file := range files {
			name, isBody := strings.CutSuffix(file.Name(), ".body.txt")
			if !isBody {
				continue
			}
			id, err := strconv.Atoi(name)
			if err != nil {
				continue
			}
			if _, ok := s.Locate(id); ok {
				ids = append(ids, id)
			}
		}
	}
	slices.Sort(ids)
	return ids, nil
}
