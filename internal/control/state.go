// Package control lleva la cuenta de qué libros están descargados e indexados
// y coordina el pipeline para que pueda reanudarse tras un corte.
package control

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"stage1_go/internal/fileutil"
)

// bookList es un fichero de control: un ID por línea, en orden de llegada y
// sin repetidos.
type bookList struct {
	path string
	ids  []int
	has  map[int]bool
}

func loadBookList(path string) (*bookList, error) {
	lines, err := fileutil.LoadLines(path)
	if err != nil {
		return nil, err
	}
	list := &bookList{path: path, has: make(map[int]bool, len(lines))}
	for _, line := range lines {
		id, err := strconv.Atoi(line)
		if err != nil || list.has[id] {
			continue
		}
		list.ids = append(list.ids, id)
		list.has[id] = true
	}
	return list, nil
}

func (l *bookList) add(id int) error {
	if l.has[id] {
		return nil
	}
	if err := fileutil.AppendLine(l.path, strconv.Itoa(id)); err != nil {
		return err
	}
	l.ids = append(l.ids, id)
	l.has[id] = true
	return nil
}

func (l *bookList) replace(ids []int) error {
	var sb strings.Builder
	has := make(map[int]bool, len(ids))
	for _, id := range ids {
		sb.WriteString(strconv.Itoa(id))
		sb.WriteByte('\n')
		has[id] = true
	}
	if err := fileutil.AtomicWrite(l.path, []byte(sb.String())); err != nil {
		return err
	}
	l.ids, l.has = ids, has
	return nil
}

// State es el estado del pipeline guardado en output/<lang>/control.
type State struct {
	dir        string
	downloaded *bookList
	indexed    *bookList
	failed     *bookList
}

func Open(dir string) (*State, error) {
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, err
	}
	downloaded, err := loadBookList(filepath.Join(dir, "downloaded_books.txt"))
	if err != nil {
		return nil, err
	}
	indexed, err := loadBookList(filepath.Join(dir, "indexed_books.txt"))
	if err != nil {
		return nil, err
	}
	failed, err := loadBookList(filepath.Join(dir, "failed_books.txt"))
	if err != nil {
		return nil, err
	}
	return &State{dir: dir, downloaded: downloaded, indexed: indexed, failed: failed}, nil
}

func (s *State) Dir() string { return s.dir }

// Las marcas se escriben siempre después de que la operación haya terminado.

func (s *State) MarkDownloaded(id int) error { return s.downloaded.add(id) }
func (s *State) MarkIndexed(id int) error    { return s.indexed.add(id) }
func (s *State) MarkFailed(id int) error     { return s.failed.add(id) }

func (s *State) Downloaded() []int { return s.downloaded.ids }
func (s *State) Indexed() []int    { return s.indexed.ids }
func (s *State) Failed() []int     { return s.failed.ids }

// Pending son los libros descargados pero todavía no indexados, en orden de
// descarga.
func (s *State) Pending() []int {
	var pending []int
	for _, id := range s.downloaded.ids {
		if !s.indexed.has[id] {
			pending = append(pending, id)
		}
	}
	return pending
}

// Known indica que el libro ya está descargado o descartado: no hay que
// volver a pedirlo.
func (s *State) Known(id int) bool {
	return s.downloaded.has[id] || s.failed.has[id]
}

// Report resume lo que ha habido que corregir al arrancar.
type Report struct {
	TempFiles     int   // .tmp borrados
	Unregistered  []int // en el datalake pero sin marcar como descargados
	Missing       []int // marcados como descargados pero no están en el datalake
	OrphanIndexed []int // marcados como indexados pero no están en el datalake
}

func (r Report) Clean() bool {
	return r.TempFiles == 0 && len(r.Unregistered) == 0 && len(r.Missing) == 0 && len(r.OrphanIndexed) == 0
}

// Reconcile alinea los ficheros de control con lo que hay realmente en el
// datalake. Cubre los dos cortes posibles: libro escrito sin registrar y
// registro sin libro.
func (s *State) Reconcile(inDatalake []int) (Report, error) {
	present := make(map[int]bool, len(inDatalake))
	for _, id := range inDatalake {
		present[id] = true
	}

	var report Report
	var downloaded, indexed []int
	for _, id := range s.downloaded.ids {
		if present[id] {
			downloaded = append(downloaded, id)
		} else {
			report.Missing = append(report.Missing, id)
		}
	}
	for _, id := range inDatalake {
		if !s.downloaded.has[id] {
			downloaded = append(downloaded, id)
			report.Unregistered = append(report.Unregistered, id)
		}
	}
	for _, id := range s.indexed.ids {
		if present[id] {
			indexed = append(indexed, id)
		} else {
			report.OrphanIndexed = append(report.OrphanIndexed, id)
		}
	}

	if len(report.Missing) > 0 || len(report.Unregistered) > 0 {
		if err := s.downloaded.replace(downloaded); err != nil {
			return report, err
		}
	}
	if len(report.OrphanIndexed) > 0 {
		if err := s.indexed.replace(indexed); err != nil {
			return report, err
		}
	}
	return report, nil
}
