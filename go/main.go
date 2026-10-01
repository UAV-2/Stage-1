package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
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
  books [ids...]      consulta los metadatos por ID o con --author, --title y --language
  dump                exporta metadata.tsv e index.tsv a output/go/dumps/<índice>/
  lookup <ids...>     localiza header y body de cada libro
  status              resumen del estado del pipeline
  recover             alinea los ficheros de control con el datalake

Opciones:
  --datalake=time|book|range   estructura del datalake (por defecto book)
  --index=json|folders|mongo   estructura del índice invertido (por defecto json)
  --offline                    leer los libros de cache/ en vez de descargarlos
  --sample                     usar el dataset de muestra: sample_data/ y book_ids_sample.txt, sin red
  --n=N                        usar solo los primeros N IDs de la lista (0 = todos)
  --batch=N                    libros por lote de indexación (por defecto 100; 0 = todos)
  --rebuild                    con index: vaciar índice y metadatos y reconstruirlos
  --author=A --title=T         con books y query: solo los libros con ese autor, título
  --language=L                 o idioma (coincidencia exacta)
  --ids=FICHERO                lista de IDs (por defecto <root>/shared/book_ids.txt)
  --delay=1s                   espera entre peticiones a Gutenberg
  --mongo=URI                  servidor de MongoDB (por defecto mongodb://localhost:27017)
  --root=DIR                   raíz del repositorio: shared/, cache/ y output/ (por defecto ..)`

type options struct {
	root     string
	datalake string
	index    string
	offline  bool
	sample   bool
	rebuild  bool
	n        int
	batch    int
	idsFile  string
	delay    time.Duration
	mongoURI string
	filter   metadata.Filter
}

func (o options) output() string            { return filepath.Join(o.root, "output", lang) }
func (o options) datamarts() string         { return filepath.Join(o.output(), "datamarts") }
func (o options) shared(name string) string { return filepath.Join(o.root, "shared", name) }

// cacheDir es de donde se leen los libros crudos sin red: la caché completa
// o los pocos libros de muestra que sí están en el repositorio.
func (o options) cacheDir() string {
	if o.sample {
		return filepath.Join(o.root, "sample_data")
	}
	return filepath.Join(o.root, "cache")
}

func (o options) idsPath() string {
	switch {
	case o.idsFile != "":
		return o.idsFile
	case o.sample:
		return o.shared("book_ids_sample.txt")
	}
	return o.shared("book_ids.txt")
}

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
	flags.BoolVar(&opts.sample, "sample", false, "")
	flags.BoolVar(&opts.rebuild, "rebuild", false, "")
	flags.IntVar(&opts.n, "n", 0, "")
	flags.IntVar(&opts.batch, "batch", 100, "")
	flags.StringVar(&opts.idsFile, "ids", "", "")
	flags.DurationVar(&opts.delay, "delay", time.Second, "")
	flags.StringVar(&opts.mongoURI, "mongo", "mongodb://localhost:27017", "")
	flags.StringVar(&opts.filter.Author, "author", "", "")
	flags.StringVar(&opts.filter.Title, "title", "", "")
	flags.StringVar(&opts.filter.Language, "language", "", "")

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

	if opts.sample {
		opts.offline = true
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
	case "books":
		return books(opts, args)
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
	if opts.offline {
		pipeline.Source = ingestion.Cache{Dir: opts.cacheDir()}
	} else {
		pipeline.Source = ingestion.NewGutenberg(opts.cacheDir(), opts.delay)
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
// como argumento o, si no hay, los de la lista compartida. Con --n, solo los
// N primeros.
func targetIDs(opts options, args []string) ([]int, error) {
	ids, err := parseIDs(args)
	if err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		if ids, err = control.ReadIDs(opts.idsPath()); err != nil {
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
	allowed, err := filteredIDs(opts)
	if err != nil {
		return err
	}

	for _, text := range queries {
		ids, err := index.Search(idx, text, stop)
		if err != nil {
			return err
		}
		if allowed != nil {
			ids = slices.DeleteFunc(slices.Clone(ids), func(id int) bool { return !allowed[id] })
		}
		fmt.Printf("[QUERY:%s] %s -> %d libros %v\n", idx.Name(), text, len(ids), ids)
	}
	return nil
}

// filteredIDs devuelve los libros que cumplen el filtro de metadatos, o nil
// si no se ha pedido ningún filtro.
func filteredIDs(opts options) (map[int]bool, error) {
	if opts.filter.Empty() {
		return nil, nil
	}
	db, err := openMetadata(opts)
	if err != nil {
		return nil, err
	}
	defer db.Close()
	found, err := db.Find(opts.filter)
	if err != nil {
		return nil, err
	}
	allowed := make(map[int]bool, len(found))
	for _, book := range found {
		allowed[book.ID] = true
	}
	return allowed, nil
}

// books consulta el datamart de metadatos: por ID, o por título, autor e
// idioma. Cada línea incluye la ruta del body en el datalake.
func books(opts options, args []string) error {
	ids, err := parseIDs(args)
	if err != nil {
		return err
	}
	db, err := openMetadata(opts)
	if err != nil {
		return err
	}
	defer db.Close()

	var found []metadata.Book
	if len(ids) == 0 {
		if found, err = db.Find(opts.filter); err != nil {
			return err
		}
	}
	for _, id := range ids {
		book, ok, err := db.ByID(id)
		if err != nil {
			return err
		}
		if !ok {
			fmt.Printf("[BOOKS] %d -> no está en los metadatos\n", id)
			continue
		}
		found = append(found, book)
	}

	for _, book := range found {
		fmt.Printf("[BOOKS] %d | %s | %s | %s | %s\n", book.ID,
			orDash(book.Title), orDash(book.Author), orDash(book.Language), book.BodyPath)
	}
	fmt.Printf("[BOOKS] %d libros\n", len(found))
	return nil
}

func orDash(value string) string {
	if value == "" {
		return "-"
	}
	return value
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
