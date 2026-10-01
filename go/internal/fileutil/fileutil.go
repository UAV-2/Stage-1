// Package fileutil reúne las operaciones de fichero que comparten el
// datalake y la capa de control.
package fileutil

import (
	"bytes"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// AtomicWrite escribe en "<path>.tmp" y renombra al final: si el proceso se
// corta a mitad, nunca queda un fichero final a medias.
func AtomicWrite(path string, data []byte) error {
	tmpPath := path + ".tmp"
	if err := os.WriteFile(tmpPath, data, 0644); err != nil {
		return err
	}
	return os.Rename(tmpPath, path)
}

// AppendLine añade una línea al final de un fichero de registro, creándolo si
// no existe.
func AppendLine(path, line string) error {
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		return err
	}
	if _, err := f.WriteString(line + "\n"); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

// LoadLines devuelve las líneas no vacías de un fichero de registro. Si el
// fichero no existe devuelve una lista vacía.
//
// Una última línea sin "\n" es un append interrumpido: se descarta y se
// recorta el fichero, para que el siguiente append no se pegue a ella.
func LoadLines(path string) ([]string, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	end := bytes.LastIndexByte(data, '\n') + 1
	if end < len(data) {
		if err := os.Truncate(path, int64(end)); err != nil {
			return nil, err
		}
		data = data[:end]
	}

	var lines []string
	for _, line := range strings.Split(string(data), "\n") {
		if line = strings.TrimSpace(line); line != "" {
			lines = append(lines, line)
		}
	}
	return lines, nil
}

// RemoveTemp borra los ".tmp" que haya dejado una escritura interrumpida y
// devuelve cuántos ha borrado.
func RemoveTemp(root string) (int, error) {
	removed := 0
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				return nil
			}
			return err
		}
		if !d.IsDir() && strings.HasSuffix(path, ".tmp") {
			if err := os.Remove(path); err != nil {
				return err
			}
			removed++
		}
		return nil
	})
	return removed, err
}

// TreeStats cuenta los ficheros y carpetas que hay bajo root (sin contar
// root) y suma el tamaño de los ficheros. Si root no existe, todo es cero.
func TreeStats(root string) (files, dirs int, size int64, err error) {
	err = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				return nil
			}
			return err
		}
		if d.IsDir() {
			if path != root {
				dirs++
			}
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		files++
		size += info.Size()
		return nil
	})
	return files, dirs, size, err
}
