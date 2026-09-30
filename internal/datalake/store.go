// Package datalake guarda header y body de cada libro en disco, con tres
// organizaciones de carpetas distintas.
package datalake

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"

	"stage1_go/internal/fileutil"
)

// Location son las rutas de las dos mitades de un libro.
type Location struct {
	Header string
	Body   string
}

// Store es el contrato común a las tres variantes del datalake.
type Store interface {
	Name() string
	Root() string
	// Save escribe el libro; repetirlo con el mismo ID lo sobrescribe en su
	// sitio, nunca lo duplica.
	Save(id int, header, body string) (Location, error)
	// Locate devuelve false si falta alguna de las dos mitades.
	Locate(id int) (Location, bool)
	// List devuelve los IDs de los libros completos, en orden ascendente.
	List() ([]int, error)
}

// New crea la variante pedida dentro de outputDir (output/<lang>).
func New(kind, outputDir string) (Store, error) {
	root := filepath.Join(outputDir, "datalake_"+kind)
	switch kind {
	case "time":
		return newTimeStore(root)
	case "book":
		return &bookStore{root: root}, nil
	case "range":
		return &rangeStore{root: root}, nil
	}
	return nil, fmt.Errorf("datalake desconocido %q (time, book o range)", kind)
}

// flatLocation es la disposición "<id>.header.txt / <id>.body.txt" que
// comparten time y range.
func flatLocation(dir string, id int) Location {
	name := strconv.Itoa(id)
	return Location{
		Header: filepath.Join(dir, name+".header.txt"),
		Body:   filepath.Join(dir, name+".body.txt"),
	}
}

// write deja el body para el final: su existencia marca el libro como completo.
func write(loc Location, header, body string) error {
	if err := os.MkdirAll(filepath.Dir(loc.Body), 0755); err != nil {
		return err
	}
	if err := fileutil.AtomicWrite(loc.Header, []byte(header)); err != nil {
		return err
	}
	return fileutil.AtomicWrite(loc.Body, []byte(body))
}

func complete(loc Location) bool {
	return isFile(loc.Header) && isFile(loc.Body)
}

func isFile(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}
