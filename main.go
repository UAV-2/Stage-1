package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"stage1_go/internal/control"
	"stage1_go/internal/datalake"
	"stage1_go/internal/ingestion"
)

const lang = "go"

const usage = `Uso: go run . <comando> [ids...] [opciones]

Comandos:
  download [ids...]   guarda en el datalake los libros que falten (sin IDs: los de la lista)
  lookup <ids...>     localiza header y body de cada libro
  status              resumen del estado del pipeline
  recover             alinea los ficheros de control con el datalake

Opciones:
  --datalake=time|book|range   estructura del datalake (por defecto book)
  --offline                    leer los libros de cache/ en vez de descargarlos
  --n=N                        usar solo los primeros N IDs de la lista (0 = todos)
  --ids=FICHERO                lista de IDs (por defecto <root>/shared/book_ids.txt)
  --delay=1s                   espera entre peticiones a Gutenberg
  --root=DIR                   raíz del repositorio: shared/, cache/ y output/ (por defecto ..)`

type options struct {
	root     string
	datalake string
	offline  bool
	n        int
	idsFile  string
	delay    time.Duration
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
	flags.BoolVar(&opts.offline, "offline", false, "")
	flags.IntVar(&opts.n, "n", 0, "")
	flags.StringVar(&opts.idsFile, "ids", "", "")
	flags.DurationVar(&opts.delay, "delay", time.Second, "")

	// El paquete flag se para en el primer argumento que no es una opción;
	// se repite para admitir opciones e IDs en cualquier orden.
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

	ids, err := parseIDs(positional[1:])
	if err != nil {
		return err
	}

	outputDir := filepath.Join(opts.root, "output", lang)
	store, err := datalake.New(opts.datalake, outputDir)
	if err != nil {
		return err
	}
	state, err := control.Open(filepath.Join(outputDir, "control"))
	if err != nil {
		return err
	}
	pipeline := &control.Pipeline{
		Store: store,
		State: state,
		Logf: func(format string, args ...any) {
			fmt.Printf("[CONTROL:%s] %s\n", store.Name(), fmt.Sprintf(format, args...))
		},
	}

	switch positional[0] {
	case "download":
		return download(pipeline, opts, ids)
	case "lookup":
		lookup(store, ids)
		return nil
	case "status":
		return status(store, state)
	case "recover":
		return recoverState(pipeline)
	}
	fmt.Println(usage)
	return fmt.Errorf("comando desconocido %q", positional[0])
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

func download(pipeline *control.Pipeline, opts options, ids []int) error {
	if len(ids) == 0 {
		idsFile := opts.idsFile
		if idsFile == "" {
			idsFile = filepath.Join(opts.root, "shared", "book_ids.txt")
		}
		var err error
		if ids, err = control.ReadIDs(idsFile); err != nil {
			return err
		}
	}
	if opts.n > 0 && opts.n < len(ids) {
		ids = ids[:opts.n]
	}

	cacheDir := filepath.Join(opts.root, "cache")
	if opts.offline {
		pipeline.Source = ingestion.Cache{Dir: cacheDir}
	} else {
		pipeline.Source = ingestion.NewGutenberg(cacheDir, opts.delay)
	}

	if err := recoverState(pipeline); err != nil {
		return err
	}
	summary, err := pipeline.Download(ids)
	fmt.Printf("[DOWNLOAD:%s] %d guardados, %d descartados, %d ya estaban\n",
		pipeline.Store.Name(), summary.Downloaded, summary.Discarded, summary.Skipped)
	return err
}

func recoverState(pipeline *control.Pipeline) error {
	report, err := pipeline.Recover()
	if err != nil {
		return err
	}
	if !report.Clean() {
		fmt.Printf("[RECOVER:%s] %d .tmp borrados, %d sin registrar, %d registrados sin libro, %d indexados sin libro\n",
			pipeline.Store.Name(), report.TempFiles, len(report.Unregistered), len(report.Missing), len(report.OrphanIndexed))
	}
	return nil
}

func lookup(store datalake.Store, ids []int) {
	for _, id := range ids {
		loc, ok := store.Locate(id)
		if !ok {
			fmt.Printf("[LOOKUP:%s] %d -> no encontrado\n", store.Name(), id)
			continue
		}
		fmt.Printf("[LOOKUP:%s] %d -> %s | %s\n", store.Name(), id, loc.Header, loc.Body)
	}
}

func status(store datalake.Store, state *control.State) error {
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
