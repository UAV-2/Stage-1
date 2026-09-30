package control

import (
	"errors"

	"stage1_go/internal/datalake"
	"stage1_go/internal/fileutil"
	"stage1_go/internal/ingestion"
)

// Pipeline coordina la ingesta sobre una variante del datalake.
type Pipeline struct {
	Store  datalake.Store
	State  *State
	Source ingestion.Source
	// Logf recibe una línea por cada libro procesado; nil = en silencio.
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
	temps := 0
	for _, dir := range []string{p.Store.Root(), p.State.Dir()} {
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
	return report, err
}

// Summary cuenta lo que ha pasado con cada libro de una ejecución.
type Summary struct {
	Downloaded int
	Discarded  int
	Skipped    int // ya estaban descargados o descartados
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
