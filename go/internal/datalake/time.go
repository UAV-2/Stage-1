package datalake

import (
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"

	"stage1_go/internal/fileutil"
)

const locationsFile = "_locations.tsv"

// timeStore: datalake_time/YYYYMMDD/HH/<id>.header.txt y <id>.body.txt
//
// La ruta depende de cuándo se guardó el libro, así que se mantiene el índice
// auxiliar _locations.tsv (id\tYYYYMMDD/HH) para poder localizarlo.
type timeStore struct {
	root      string
	now       func() time.Time
	locations map[int]string
}

func newTimeStore(root string) (*timeStore, error) {
	lines, err := fileutil.LoadLines(filepath.Join(root, locationsFile))
	if err != nil {
		return nil, err
	}
	locations := make(map[int]string, len(lines))
	for _, line := range lines {
		idText, folder, ok := strings.Cut(line, "\t")
		id, err := strconv.Atoi(idText)
		if !ok || err != nil {
			continue
		}
		locations[id] = folder
	}
	return &timeStore{root: root, now: time.Now, locations: locations}, nil
}

func (s *timeStore) Name() string { return "time" }
func (s *timeStore) Root() string { return s.root }

func (s *timeStore) location(id int, folder string) Location {
	return flatLocation(filepath.Join(s.root, filepath.FromSlash(folder)), id)
}

// Save registra la carpeta antes de escribir los ficheros: si el proceso se
// corta a mitad, al reanudar el libro vuelve a la misma carpeta aunque haya
// cambiado la hora, y no queda duplicado en dos sitios.
func (s *timeStore) Save(id int, header, body string) (Location, error) {
	folder, known := s.locations[id]
	if !known {
		folder = s.now().Format("20060102/15")
		if err := os.MkdirAll(s.root, 0755); err != nil {
			return Location{}, err
		}
		entry := fmt.Sprintf("%d\t%s", id, folder)
		if err := fileutil.AppendLine(filepath.Join(s.root, locationsFile), entry); err != nil {
			return Location{}, err
		}
		s.locations[id] = folder
	}
	loc := s.location(id, folder)
	return loc, write(loc, header, body)
}

func (s *timeStore) Locate(id int) (Location, bool) {
	folder, known := s.locations[id]
	if !known {
		return Location{}, false
	}
	loc := s.location(id, folder)
	return loc, complete(loc)
}

func (s *timeStore) List() ([]int, error) {
	var ids []int
	for id := range s.locations {
		if _, ok := s.Locate(id); ok {
			ids = append(ids, id)
		}
	}
	slices.Sort(ids)
	return ids, nil
}
