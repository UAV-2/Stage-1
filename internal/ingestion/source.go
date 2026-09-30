package ingestion

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"stage1_go/internal/fileutil"
)

const urlTemplate = "https://www.gutenberg.org/cache/epub/%d/pg%d.txt"

// Source entrega el texto crudo de un libro.
type Source interface {
	Fetch(id int) (string, error)
}

func cachePath(dir string, id int) string {
	return filepath.Join(dir, fmt.Sprintf("%d.txt", id))
}

// Cache lee los .txt crudos de cache/ sin tocar la red (modo --offline).
type Cache struct {
	Dir string
}

func (c Cache) Fetch(id int) (string, error) {
	data, err := os.ReadFile(cachePath(c.Dir, id))
	if errors.Is(err, fs.ErrNotExist) {
		return "", fmt.Errorf("el libro %d no está en %s", id, c.Dir)
	}
	if err != nil {
		return "", err
	}
	return string(data), nil
}

// Gutenberg descarga de la red. Espera Delay entre peticiones para no saturar
// el servidor y deja una copia cruda en CacheDir.
type Gutenberg struct {
	CacheDir string
	Delay    time.Duration

	client      *http.Client
	lastRequest time.Time
}

func NewGutenberg(cacheDir string, delay time.Duration) *Gutenberg {
	return &Gutenberg{
		CacheDir: cacheDir,
		Delay:    delay,
		client:   &http.Client{Timeout: 60 * time.Second},
	}
}

func (g *Gutenberg) Fetch(id int) (string, error) {
	if !g.lastRequest.IsZero() {
		time.Sleep(time.Until(g.lastRequest.Add(g.Delay)))
	}
	defer func() { g.lastRequest = time.Now() }()

	resp, err := g.client.Get(fmt.Sprintf(urlTemplate, id, id))
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return "", fmt.Errorf("%w: el libro %d no existe", ErrUnavailable, id)
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("HTTP %d al descargar el libro %d", resp.StatusCode, id)
	}

	content, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", err
	}

	if err := os.MkdirAll(g.CacheDir, 0755); err != nil {
		return "", err
	}
	if err := fileutil.AtomicWrite(cachePath(g.CacheDir, id), content); err != nil {
		return "", err
	}
	return string(content), nil
}
