package control

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"stage1_go/internal/datalake"
	"stage1_go/internal/fileutil"
	"stage1_go/internal/index"
	"stage1_go/internal/ingestion"
	"stage1_go/internal/metadata"
	"stage1_go/internal/tokenizer"
)

// Pipeline coordina la ingesta y la indexación sobre una variante del
// datalake y una del índice. Source solo hace falta para descargar; Metadata,
// Index y Stopwords, solo para indexar.
type Pipeline struct {
	Store  datalake.Store
	State  *State
	Source ingestion.Source

	Metadata  *metadata.DB
	Index     index.Index
	Stopwords tokenizer.Stopwords
	Datamarts string // carpeta de los datamarts, para limpiar sus .tmp

	// Logf recibe una línea por cada paso; nil = en silencio.
	Logf func(format string, args ...any)
}

func (p *Pipeline) logf(format string, args ...any) {
	if p.Logf != nil {
		p.Logf(format, args...)
	}
}

// Recover se ejecuta al arrancar para dejar el estado coherente tras una
// interrupción.
func (p *Pipeline) Recover() (Report, error) {
	dirs := []string{p.Store.Root(), p.State.Dir()}
	if p.Datamarts != "" {
		dirs = append(dirs, p.Datamarts)
	}
	temps := 0
	for _, dir := range dirs {
		removed, err := fileutil.RemoveTemp(dir)
		if err != nil {
			return Report{}, err
		}
		temps += removed
	}

	inDatalake, err := p.Store.List()
	if err != nil {
		return Report{}, err
	}
	report, err := p.State.Reconcile(inDatalake)
	report.TempFiles = temps
	if err != nil || p.Index == nil || len(p.State.Indexed()) == 0 {
		return report, err
	}

	// El control es único para las tres variantes del índice: si la que se
	// va a usar está vacía, los libros marcados se indexaron en otra.
	noMetadata, err := p.Metadata.Empty()
	if err != nil {
		return report, err
	}
	noIndex, err := p.Index.Empty()
	if err != nil {
		return report, err
	}
	if noMetadata || noIndex {
		report.Reindex = len(p.State.Indexed())
		err = p.State.ClearIndexed()
	}
	return report, err
}

// Summary cuenta lo que ha pasado con cada libro de una ejecución.
type Summary struct {
	Downloaded int
	Discarded  int
	Skipped    int // ya estaban descargados o descartados
	Indexed    int
}

// Download ingiere, en el orden de ids, los libros que todavía no se conocen.
// Un libro no disponible se descarta y se sigue; cualquier otro error detiene
// la ejecución, que se puede reanudar más tarde sin repetir trabajo.
func (p *Pipeline) Download(ids []int) (Summary, error) {
	var summary Summary
	for _, id := range ids {
		if p.State.Known(id) {
			summary.Skipped++
			continue
		}

		err := p.ingest(id)
		if errors.Is(err, ingestion.ErrUnavailable) {
			if err := p.State.MarkFailed(id); err != nil {
				return summary, err
			}
			summary.Discarded++
			p.logf("Libro %d descartado: %v", id, err)
			continue
		}
		if err != nil {
			return summary, err
		}
		summary.Downloaded++
		p.logf("Libro %d guardado en el datalake", id)
	}
	return summary, nil
}

func (p *Pipeline) ingest(id int) error {
	raw, err := p.Source.Fetch(id)
	if err != nil {
		return err
	}
	book, err := ingestion.Split(id, raw)
	if err != nil {
		return err
	}
	if _, err := p.Store.Save(book.ID, book.Header, book.Body); err != nil {
		return err
	}
	return p.State.MarkDownloaded(id)
}

// IndexPending indexa los libros descargados que aún no están indexados, en
// lotes de batch libros (batch <= 0: todos de una vez). Devuelve cuántos ha
// indexado.
func (p *Pipeline) IndexPending(batch int) (int, error) {
	pending := p.State.Pending()
	if batch <= 0 {
		batch = len(pending)
	}
	indexed := 0
	for len(pending) > 0 {
		chunk := pending[:min(batch, len(pending))]
		if err := p.indexBatch(chunk); err != nil {
			return indexed, err
		}
		indexed += len(chunk)
		pending = pending[len(chunk):]
	}
	return indexed, nil
}

// indexBatch lleva un lote a los dos datamarts y solo después lo marca como
// indexado. Si se corta antes, el lote se repite entero al reanudar; tanto
// los metadatos como el índice admiten la repetición sin duplicar nada.
func (p *Pipeline) indexBatch(ids []int) error {
	books := make([]metadata.Book, 0, len(ids))
	postings := make(map[string][]int)
	for _, id := range ids {
		loc, ok := p.Store.Locate(id)
		if !ok {
			return fmt.Errorf("el libro %d está registrado pero no está en el datalake", id)
		}
		header, err := os.ReadFile(loc.Header)
		if err != nil {
			return err
		}
		body, err := os.ReadFile(loc.Body)
		if err != nil {
			return err
		}
		bodyPath, err := filepath.Rel(filepath.Dir(p.Store.Root()), loc.Body)
		if err != nil {
			return err
		}

		book := metadata.Extract(id, string(header))
		book.BodyPath = filepath.ToSlash(bodyPath)
		books = append(books, book)
		for _, term := range tokenizer.Terms(body, p.Stopwords) {
			postings[term] = append(postings[term], id)
		}
	}

	if err := p.Metadata.Insert(books); err != nil {
		return err
	}
	if err := p.Index.Add(postings); err != nil {
		return err
	}
	for _, id := range ids {
		if err := p.State.MarkIndexed(id); err != nil {
			return err
		}
	}
	p.logf("%d libros indexados (%d términos)", len(ids), len(postings))
	return nil
}

// Rebuild vacía los datamarts y deja todos los libros descargados como
// pendientes de indexar.
func (p *Pipeline) Rebuild() error {
	if err := p.Index.Reset(); err != nil {
		return err
	}
	if err := p.Metadata.Reset(); err != nil {
		return err
	}
	return p.State.ClearIndexed()
}

// Run es el ciclo completo: primero indexa lo que quedó pendiente y después
// recorre ids en orden, descargando los libros nuevos e indexándolos por
// lotes.
func (p *Pipeline) Run(ids []int, batch int) (Summary, error) {
	var summary Summary
	indexed, err := p.IndexPending(batch)
	summary.Indexed += indexed
	if err != nil {
		return summary, err
	}

	if batch <= 0 {
		batch = len(ids)
	}
	for start := 0; start < len(ids); start += batch {
		chunk := ids[start:min(start+batch, len(ids))]
		downloaded, err := p.Download(chunk)
		summary.Downloaded += downloaded.Downloaded
		summary.Discarded += downloaded.Discarded
		summary.Skipped += downloaded.Skipped
		if err != nil {
			return summary, err
		}

		indexed, err := p.IndexPending(batch)
		summary.Indexed += indexed
		if err != nil {
			return summary, err
		}
	}
	return summary, nil
}
