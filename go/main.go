package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"stage1_go/internal/control"
	"stage1_go/internal/datalake"
	"stage1_go/internal/index"
	"stage1_go/internal/ingestion"
	"stage1_go/internal/metadata"
	"stage1_go/internal/tokenizer"
)

const lang = "go"

const usage = `Uso: go run . <comando> [argumentos...] [opciones]

Comandos:
  pipeline [ids...]   ciclo completo: indexa lo pendiente, descarga los libros nuevos y los indexa
  download [ids...]   guarda en el datalake los libros que falten (sin IDs: los de la lista)
  index               indexa los libros descargados que aún no están indexados
  query [texto...]    busca en el índice (sin texto: las consultas de shared/queries.txt)
  dump                exporta metadata.tsv e index.tsv a output/go/dumps/<índice>/
  lookup <ids...>     localiza header y body de cada libro
  status              resumen del estado del pipeline
  recover             alinea los ficheros de control con el datalake

Opciones:
  --datalake=time|book|range   estructura del datalake (por defecto book)
  --index=json|folders|mongo   estructura del índice invertido (por defecto json)
  --offline                    leer los libros de cache/ en vez de descargarlos
  --n=N                        usar solo los primeros N IDs de la lista (0 = todos)
  --batch=N                    libros por lote de indexación (por defecto 100; 0 = todos)
  --rebuild                    con index: vaciar índice y metadatos y reconstruirlos
  --ids=FICHERO                lista de IDs (por defecto <root>/shared/book_ids.txt)
  --delay=1s                   espera entre peticiones a Gutenberg
  --mongo=URI                  servidor de MongoDB (por defecto mongodb://localhost:27017)
  --root=DIR                   raíz del repositorio: shared/, cache/ y output/ (por defecto ..)`

type options struct {
	root     string
	datalake string
	index    string
	offline  bool
	rebuild  bool
	n        int
	batch    int
	idsFile  string
	delay    time.Duration
	mongoURI string
}

func (o options) output() string            { return filepath.Join(o.root, "output", lang) }
func (o options) datamarts() string         { return filepath.Join(o.output(), "datamarts") }
func (o options) shared(name string) string { return filepath.Join(o.root, "shared", name) }

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Printf("[ERROR]: %v\n", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	var opts options
	flags := flag.NewFlagSet("stage1_go", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	flags.StringVar(&opts.root, "root", "..", "")
	flags.StringVar(&opts.datalake, "datalake", "book", "")
	flags.StringVar(&opts.index, "index", "json", "")
	flags.BoolVar(&opts.offline, "offline", false, "")
	flags.BoolVar(&opts.rebuild, "rebuild", false, "")
	flags.IntVar(&opts.n, "n", 0, "")
	flags.IntVar(&opts.batch, "batch", 100, "")
	flags.StringVar(&opts.idsFile, "ids", "", "")
	flags.DurationVar(&opts.delay, "delay", time.Second, "")
	flags.StringVar(&opts.mongoURI, "mongo", "mongodb://localhost:27017", "")

	// El paquete flag se para en el primer argumento que no es una opción;
	// se repite para admitir opciones y argumentos en cualquier orden.
	var positional []string
	for {
		if err := flags.Parse(args); err != nil {
			if errors.Is(err, flag.ErrHelp) {
				fmt.Println(usage)
				return nil
			}
			return err
		}
		if flags.NArg() == 0 {
			break
		}
		positional = append(positional, flags.Arg(0))
		args = flags.Args()[1:]
	}
	if len(positional) == 0 {
		fmt.Println(usage)
		return nil
	}

	command, args := positional[0], positional[1:]
	switch command {
	case "pipeline":
		return runPipeline(opts, args)
	case "download":
		return download(opts, args)
	case "index":
		return indexPending(opts)
	case "query":
		return query(opts, args)
	case "dump":
		return dump(opts)
	case "lookup":
		return lookup(opts, args)
	case "status":
		return status(opts)
	case "recover":
		pipeline, err := newPipeline(opts)
		if err != nil {
			return err
		}
		return recoverState(pipeline)
	}
	fmt.Println(usage)
	return fmt.Errorf("comando desconocido %q", command)
}

// newPipeline abre el datalake y el control, que usan casi todos los comandos.
func newPipeline(opts options) (*control.Pipeline, error) {
	store, err := datalake.New(opts.datalake, opts.output())
	if err != nil {
		return nil, err
	}
	state, err := control.Open(filepath.Join(opts.output(), "control"))
	if err != nil {
		return nil, err
	}
	return &control.Pipeline{
		Store: store,
		State: state,
		Logf: func(format string, args ...any) {
			fmt.Printf("[CONTROL:%s] %s\n", store.Name(), fmt.Sprintf(format, args...))
		},
	}, nil
}

func setSource(pipeline *control.Pipeline, opts options) {
	cacheDir := filepath.Join(opts.root, "cache")
	if opts.offline {
		pipeline.Source = ingestion.Cache{Dir: cacheDir}
	} else {
		pipeline.Source = ingestion.NewGutenberg(cacheDir, opts.delay)
	}
}

// openDatamarts añade al pipeline lo que hace falta para indexar. La función
// que devuelve cierra los datamarts.
func openDatamarts(pipeline *control.Pipeline, opts options) (func(), error) {
	stop, err := loadStopwords(opts)
	if err != nil {
		return nil, err
	}
	db, err := openMetadata(opts)
	if err != nil {
		return nil, err
	}
	idx, err := openIndex(opts)
	if err != nil {
		db.Close()
		return nil, err
	}
	pipeline.Stopwords = stop
	pipeline.Metadata = db
	pipeline.Index = idx
	pipeline.Datamarts = opts.datamarts()
	return func() {
		idx.Close()
		db.Close()
	}, nil
}

func openMetadata(opts options) (*metadata.DB, error) {
	return metadata.Open(filepath.Join(opts.datamarts(), "metadata.db"))
}

func openIndex(opts options) (index.Index, error) {
	return index.New(opts.index, index.Config{
		Datamarts: opts.datamarts(),
		MongoURI:  opts.mongoURI,
		Lang:      lang,
	})
}

func loadStopwords(opts options) (tokenizer.Stopwords, error) {
	path := opts.shared("stopwords.txt")
	stop, err := tokenizer.LoadStopwords(path)
	if errors.Is(err, fs.ErrNotExist) {
		fmt.Printf("[AVISO] no existe %s: no se filtra ninguna stopword\n", path)
		return nil, nil
	}
	return stop, err
}

func parseIDs(args []string) ([]int, error) {
	ids := make([]int, 0, len(args))
	for _, arg := range args {
		id, err := strconv.Atoi(arg)
		if err != nil {
			return nil, fmt.Errorf("%q no es un ID de libro", arg)
		}
		ids = append(ids, id)
	}
	return ids, nil
}

// targetIDs son los libros con los que trabaja el comando: los que se pasan
// como argumento o, si no hay, los primeros --n de la lista compartida.
func targetIDs(opts options, args []string) ([]int, error) {
	ids, err := parseIDs(args)
	if err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		idsFile := opts.idsFile
		if idsFile == "" {
			idsFile = opts.shared("book_ids.txt")
		}
		if ids, err = control.ReadIDs(idsFile); err != nil {
			return nil, err
		}
	}
	if opts.n > 0 && opts.n < len(ids) {
		ids = ids[:opts.n]
	}
	return ids, nil
}

func recoverState(pipeline *control.Pipeline) error {
	report, err := pipeline.Recover()
	if err != nil {
		return err
	}
	if !report.Clean() {
		fmt.Printf("[RECOVER:%s] %d .tmp borrados, %d sin registrar, %d registrados sin libro, %d indexados sin libro, %d por reindexar\n",
			pipeline.Store.Name(), report.TempFiles, len(report.Unregistered), len(report.Missing),
			len(report.OrphanIndexed), report.Reindex)
	}
	return nil
}

func runPipeline(opts options, args []string) error {
	ids, err := targetIDs(opts, args)
	if err != nil {
		return err
	}
	pipeline, err := newPipeline(opts)
	if err != nil {
		return err
	}
	setSource(pipeline, opts)
	closeDatamarts, err := openDatamarts(pipeline, opts)
	if err != nil {
		return err
	}
	defer closeDatamarts()

	if err := recoverState(pipeline); err != nil {
		return err
	}
	summary, err := pipeline.Run(ids, opts.batch)
	fmt.Printf("[PIPELINE:%s/%s] %d guardados, %d descartados, %d ya estaban, %d indexados\n",
		pipeline.Store.Name(), pipeline.Index.Name(),
		summary.Downloaded, summary.Discarded, summary.Skipped, summary.Indexed)
	return err
}

func download(opts options, args []string) error {
	ids, err := targetIDs(opts, args)
	if err != nil {
		return err
	}
	pipeline, err := newPipeline(opts)
	if err != nil {
		return err
	}
	setSource(pipeline, opts)

	if err := recoverState(pipeline); err != nil {
		return err
	}
	summary, err := pipeline.Download(ids)
	fmt.Printf("[DOWNLOAD:%s] %d guardados, %d descartados, %d ya estaban\n",
		pipeline.Store.Name(), summary.Downloaded, summary.Discarded, summary.Skipped)
	return err
}

func indexPending(opts options) error {
	pipeline, err := newPipeline(opts)
	if err != nil {
		return err
	}
	closeDatamarts, err := openDatamarts(pipeline, opts)
	if err != nil {
		return err
	}
	defer closeDatamarts()

	if opts.rebuild {
		if err := pipeline.Rebuild(); err != nil {
			return err
		}
	}
	if err := recoverState(pipeline); err != nil {
		return err
	}
	indexed, err := pipeline.IndexPending(opts.batch)
	fmt.Printf("[INDEX:%s] %d libros indexados\n", pipeline.Index.Name(), indexed)
	return err
}

func query(opts options, words []string) error {
	queries := []string{strings.Join(words, " ")}
	if len(words) == 0 {
		data, err := os.ReadFile(opts.shared("queries.txt"))
		if err != nil {
			return err
		}
		queries = nil
		for _, line := range strings.Split(string(data), "\n") {
			if line = strings.TrimSpace(line); line != "" {
				queries = append(queries, line)
			}
		}
	}

	stop, err := loadStopwords(opts)
	if err != nil {
		return err
	}
	idx, err := openIndex(opts)
	if err != nil {
		return err
	}
	defer idx.Close()

	for _, text := range queries {
		ids, err := index.Search(idx, text, stop)
		if err != nil {
			return err
		}
		fmt.Printf("[QUERY:%s] %s -> %d libros %v\n", idx.Name(), text, len(ids), ids)
	}
	return nil
}

// dump exporta el contenido de los datamarts en el formato canónico que se
// usa para comprobar que los tres lenguajes producen lo mismo.
func dump(opts options) error {
	db, err := openMetadata(opts)
	if err != nil {
		return err
	}
	defer db.Close()
	idx, err := openIndex(opts)
	if err != nil {
		return err
	}
	defer idx.Close()

	dir := filepath.Join(opts.output(), "dumps", idx.Name())
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	if err := db.DumpTSV(filepath.Join(dir, "metadata.tsv")); err != nil {
		return err
	}
	if err := index.DumpTSV(idx, filepath.Join(dir, "index.tsv")); err != nil {
		return err
	}
	fmt.Printf("[DUMP:%s] metadata.tsv e index.tsv en %s\n", idx.Name(), dir)
	return nil
}

func lookup(opts options, args []string) error {
	ids, err := parseIDs(args)
	if err != nil {
		return err
	}
	store, err := datalake.New(opts.datalake, opts.output())
	if err != nil {
		return err
	}
	for _, id := range ids {
		loc, ok := store.Locate(id)
		if !ok {
			fmt.Printf("[LOOKUP:%s] %d -> no encontrado\n", store.Name(), id)
			continue
		}
		fmt.Printf("[LOOKUP:%s] %d -> %s | %s\n", store.Name(), id, loc.Header, loc.Body)
	}
	return nil
}

func status(opts options) error {
	pipeline, err := newPipeline(opts)
	if err != nil {
		return err
	}
	store, state := pipeline.Store, pipeline.State
	inDatalake, err := store.List()
	if err != nil {
		return err
	}
	fmt.Printf(`[STATUS:%s]
  en datalake:   %d
  descargados:   %d
  indexados:     %d
  pendientes:    %d
  descartados:   %d
`, store.Name(), len(inDatalake), len(state.Downloaded()), len(state.Indexed()),
		len(state.Pending()), len(state.Failed()))
	return nil
}
