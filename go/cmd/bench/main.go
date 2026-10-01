// Comando bench: ejecuta los benchmarks de Go del SPEC §11 y deja los
// resultados en benchmarks/results/go.csv y la salida cruda en
// benchmarks/raw/go/.
//
// Se lanza desde la carpeta go/:
//
//	go run ./cmd/bench                       # micro-benchmarks y métricas de script
//	go run ./cmd/bench --only=download       # throughput de descarga (usa la red)
//	go run ./cmd/bench --sizes=100,250       # solo algunos tamaños
//
// Cada ejecución actualiza las filas que mide y conserva las demás del CSV.
package main

import (
	"bufio"
	"errors"
	"flag"
	"fmt"
	"io"
	"math"
	"math/rand"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"time"

	"stage1_go/internal/benchkit"
	"stage1_go/internal/control"
	"stage1_go/internal/datalake"
	"stage1_go/internal/fileutil"
	"stage1_go/internal/ingestion"
)

const lang = "go"

var (
	lakeKinds  = []string{"time", "book", "range"}
	indexKinds = []string{"json", "folders", "mongo"}
)

type options struct {
	root      string
	sizes     string
	mongo     string
	only      string
	count     int
	warmup    int
	reps      int
	downloads int
	datalake  string

	// Modo hijo: el propio programa se relanza para medir en un proceso nuevo.
	child string
	kind  string
	out   string
	n     int
}

func main() {
	var opts options
	flag.StringVar(&opts.root, "root", "..", "raíz del repositorio")
	flag.StringVar(&opts.sizes, "sizes", "100,250,500,1000", "valores de N")
	flag.StringVar(&opts.mongo, "mongo", "mongodb://localhost:27017", "servidor de MongoDB")
	flag.StringVar(&opts.only, "only", "micro,script", "qué medir: micro, script y/o download")
	flag.IntVar(&opts.count, "count", 10, "iteraciones medidas de cada micro-benchmark")
	flag.IntVar(&opts.warmup, "warmup", 5, "iteraciones de calentamiento que se descartan")
	flag.IntVar(&opts.reps, "reps", 5, "repeticiones de las métricas de script")
	flag.IntVar(&opts.downloads, "download-books", 50, "libros del throughput de descarga")
	flag.StringVar(&opts.datalake, "datalake", "book", "datalake del throughput de descarga")
	flag.StringVar(&opts.child, "child", "", "uso interno")
	flag.StringVar(&opts.kind, "kind", "", "uso interno")
	flag.StringVar(&opts.out, "out", "", "uso interno")
	flag.IntVar(&opts.n, "n", 0, "uso interno")
	flag.Parse()

	var err error
	if opts.child != "" {
		err = runChild(opts)
	} else {
		err = run(opts)
	}
	if err != nil {
		fmt.Printf("[ERROR]: %v\n", err)
		os.Exit(1)
	}
}

func run(opts options) error {
	if _, err := os.Stat("go.mod"); err != nil {
		return errors.New("hay que ejecutarlo desde la carpeta go/")
	}
	root, err := filepath.Abs(opts.root)
	if err != nil {
		return err
	}
	// Los micro-benchmarks y los procesos hijos leen la configuración de aquí.
	os.Setenv("STAGE1_ROOT", root)
	os.Setenv("STAGE1_SIZES", opts.sizes)
	os.Setenv("STAGE1_MONGO", opts.mongo)
	os.Setenv("STAGE1_KEEP_WORK", "1")

	env, err := benchkit.Load()
	if err != nil {
		return err
	}
	defer os.RemoveAll(env.Work)

	rawDir := filepath.Join(root, "benchmarks", "raw", lang)
	csvPath := filepath.Join(root, "benchmarks", "results", lang+".csv")
	for _, dir := range []string{rawDir, filepath.Dir(csvPath)} {
		if err := os.MkdirAll(dir, 0755); err != nil {
			return err
		}
	}
	if err := writeEnvironment(filepath.Join(rawDir, "environment.txt"), opts); err != nil {
		return err
	}
	if idx, err := env.OpenIndex("mongo", env.Work); err != nil {
		fmt.Printf("[AVISO] MongoDB no responde en %s: se omiten las medidas de mongo\n", opts.mongo)
	} else {
		idx.Close()
	}

	var rows []row
	only := strings.Split(opts.only, ",")
	if slices.Contains(only, "micro") {
		measured, err := microBenchmarks(opts, rawDir)
		if err != nil {
			return err
		}
		rows = append(rows, measured...)
	}
	if slices.Contains(only, "script") || slices.Contains(only, "download") {
		logName := "script.txt"
		if !slices.Contains(only, "script") {
			logName = "download.txt"
		}
		log, err := os.Create(filepath.Join(rawDir, logName))
		if err != nil {
			return err
		}
		defer log.Close()
		s := &script{env: env, opts: opts, log: io.MultiWriter(os.Stdout, log)}
		if slices.Contains(only, "script") {
			measured, err := s.all()
			if err != nil {
				return err
			}
			rows = append(rows, measured...)
		}
		if slices.Contains(only, "download") {
			measured, err := s.downloadThroughput()
			if err != nil {
				return err
			}
			rows = append(rows, measured...)
		}
	}

	if err := updateCSV(csvPath, rows); err != nil {
		return err
	}
	fmt.Printf("[BENCH] %d filas actualizadas en %s\n", len(rows), csvPath)
	return nil
}

// ------------------------------------------------------------------ CSV

const csvHeader = "lang,component,structure,n_books,metric,mean,stddev,min,max,ci_low,ci_high,iterations,unit"

type row struct {
	component, structure string
	n                    int
	metric               string
	values               []float64
	unit                 string
}

func (r row) key() string {
	return fmt.Sprintf("%s,%s,%s,%d,%s", lang, r.component, r.structure, r.n, r.metric)
}

func formatNumber(v float64) string {
	return strconv.FormatFloat(v, 'f', -1, 64)
}

// line calcula media, desviación típica (muestral), mínimo y máximo. El
// intervalo de confianza se deja vacío, como acuerda el SPEC para Go.
func (r row) line() string {
	n := float64(len(r.values))
	mean := 0.0
	for _, v := range r.values {
		mean += v
	}
	mean /= n
	variance := 0.0
	for _, v := range r.values {
		variance += (v - mean) * (v - mean)
	}
	stddev := 0.0
	if len(r.values) > 1 {
		stddev = math.Sqrt(variance / (n - 1))
	}
	round := func(v float64) string { return formatNumber(math.Round(v*1000) / 1000) }
	return strings.Join([]string{
		r.key(), round(mean), round(stddev), round(slices.Min(r.values)), round(slices.Max(r.values)),
		"", "", strconv.Itoa(len(r.values)), r.unit,
	}, ",")
}

// updateCSV reescribe el CSV con las filas nuevas y conserva las anteriores
// que no se han vuelto a medir.
func updateCSV(path string, rows []row) error {
	lines := make(map[string]string)
	if data, err := os.ReadFile(path); err == nil {
		for _, line := range strings.Split(string(data), "\n")[1:] {
			fields := strings.Split(line, ",")
			if len(fields) == 13 {
				lines[strings.Join(fields[:5], ",")] = line
			}
		}
	}
	for _, r := range rows {
		lines[r.key()] = r.line()
	}

	keys := make([]string, 0, len(lines))
	for key := range lines {
		keys = append(keys, key)
	}
	// Orden: componente, métrica, estructura y N (numérico).
	slices.SortFunc(keys, func(a, b string) int {
		fa, fb := strings.Split(a, ","), strings.Split(b, ",")
		for _, i := range []int{1, 4, 2} {
			if c := strings.Compare(fa[i], fb[i]); c != 0 {
				return c
			}
		}
		na, _ := strconv.Atoi(fa[3])
		nb, _ := strconv.Atoi(fb[3])
		return na - nb
	})

	var sb strings.Builder
	sb.WriteString(csvHeader + "\n")
	for _, key := range keys {
		sb.WriteString(lines[key] + "\n")
	}
	return fileutil.AtomicWrite(path, []byte(sb.String()))
}

// --------------------------------------------------------- micro-benchmarks

// microMetric dice cómo convertir el ns/op de cada benchmark en la métrica
// del SPEC.
type microMetric struct {
	component, metric, unit string
	convert                 func(nsPerOp float64, n int) float64
}

func perUnit(divisor float64) func(float64, int) float64 {
	return func(ns float64, _ int) float64 { return ns / divisor }
}

var microMetrics = map[string]microMetric{
	"WriteThroughput":         {"datalake", "write_throughput", "books/s", func(ns float64, n int) float64 { return float64(n) / (ns / 1e9) }},
	"LookupTime":              {"datalake", "lookup_time", "us", perUnit(1e3)},
	"IncrementalDetectTime":   {"datalake", "incremental_detect_time", "ms", perUnit(1e6)},
	"MetadataInsertTime":      {"metadata", "metadata_insert_time", "ms", perUnit(1e6)},
	"MetadataQueryTimeAuthor": {"metadata", "metadata_query_time_author", "us", perUnit(1e3)},
	"MetadataQueryTimeID":     {"metadata", "metadata_query_time_id", "us", perUnit(1e3)},
	"IndexBuildTime":          {"index", "index_build_time", "ms", perUnit(1e6)},
	"QueryTime":               {"index", "query_time", "us", perUnit(1e3)},
	"UpdateTime":              {"index", "update_time", "ms", perUnit(1e6)},
}

// Las medidas pesadas (segundos por operación, con preparación costosa) se
// hacen con una operación por iteración; las ligeras dejan que Go ajuste b.N
// durante el segundo que dura cada iteración.
var microGroups = []struct{ name, pattern, benchtime string }{
	{"heavy", "^Benchmark(WriteThroughput|IndexBuildTime|UpdateTime)$", "1x"},
	{"light", "^Benchmark(LookupTime|IncrementalDetectTime|MetadataInsertTime|MetadataQueryTimeAuthor|MetadataQueryTimeID|QueryTime)$", "1s"},
}

var benchLine = regexp.MustCompile(`^Benchmark(\w+)/(\w+)/n=(\d+)(?:-\d+)?\s+\d+\s+([0-9.e+]+) ns/op`)

func microBenchmarks(opts options, rawDir string) ([]row, error) {
	series := make(map[string]*row)
	var order []string
	for _, group := range microGroups {
		rawPath := filepath.Join(rawDir, "micro_"+group.name+".txt")
		fmt.Printf("[BENCH] go test %s -> %s\n", group.pattern, rawPath)
		raw, err := os.Create(rawPath)
		if err != nil {
			return nil, err
		}
		cmd := exec.Command("go", "test", "-run=^$", "-bench="+group.pattern, "-benchmem",
			"-benchtime="+group.benchtime, fmt.Sprintf("-count=%d", opts.warmup+opts.count), "-timeout=0", ".")
		cmd.Stderr = os.Stderr
		stdout, err := cmd.StdoutPipe()
		if err != nil {
			return nil, err
		}
		if err := cmd.Start(); err != nil {
			return nil, err
		}

		scanner := bufio.NewScanner(stdout)
		for scanner.Scan() {
			line := scanner.Text()
			fmt.Fprintln(raw, line)
			fmt.Println(line)
			match := benchLine.FindStringSubmatch(line)
			if match == nil {
				continue
			}
			metric, ok := microMetrics[match[1]]
			if !ok {
				continue
			}
			n, _ := strconv.Atoi(match[3])
			ns, _ := strconv.ParseFloat(match[4], 64)
			key := match[1] + "/" + match[2] + "/" + match[3]
			if series[key] == nil {
				series[key] = &row{component: metric.component, structure: match[2], n: n, metric: metric.metric, unit: metric.unit}
				order = append(order, key)
			}
			series[key].values = append(series[key].values, metric.convert(ns, n))
		}
		raw.Close()
		if err := cmd.Wait(); err != nil {
			return nil, fmt.Errorf("go test (%s): %w", group.name, err)
		}
	}

	var rows []row
	for _, key := range order {
		r := *series[key]
		if len(r.values) <= opts.warmup {
			fmt.Printf("[AVISO] %s: solo %d iteraciones, no se guarda\n", key, len(r.values))
			continue
		}
		r.values = r.values[opts.warmup:]
		rows = append(rows, r)
	}
	return rows, nil
}

// ------------------------------------------------------- métricas de script

// script mide lo que no encaja en un micro-benchmark: necesita procesos
// nuevos, matar procesos o la red.
type script struct {
	env  *benchkit.Env
	opts options
	log  io.Writer
}

func (s *script) logf(format string, args ...any) {
	fmt.Fprintf(s.log, "[SCRIPT] "+format+"\n", args...)
}

func (s *script) all() ([]row, error) {
	var rows []row
	for _, step := range []func() ([]row, error){s.storage, s.recovery, s.indexFootprint} {
		measured, err := step()
		if err != nil {
			return nil, err
		}
		rows = append(rows, measured...)
	}
	return rows, nil
}

// storage: ficheros y carpetas de cada datalake con N libros, contando los
// ficheros de control y el índice auxiliar de time. Es determinista, así que
// se mide una vez.
func (s *script) storage() ([]row, error) {
	var rows []row
	for _, kind := range lakeKinds {
		for _, n := range s.env.Sizes {
			ids, err := s.env.Books(n)
			if err != nil {
				return nil, err
			}
			out := filepath.Join(s.env.Work, "storage")
			os.RemoveAll(out)
			if _, err := s.env.Ingest(kind, out, ids); err != nil {
				return nil, err
			}
			files, dirs, size, err := fileutil.TreeStats(out)
			if err != nil {
				return nil, err
			}
			os.RemoveAll(out)
			s.logf("storage %s n=%d: %d ficheros, %d carpetas, %d bytes", kind, n, files, dirs, size)
			rows = append(rows,
				row{"datalake", kind, n, "files_count", []float64{float64(files)}, "count"},
				row{"datalake", kind, n, "dirs_count", []float64{float64(dirs)}, "count"})
		}
	}
	return rows, nil
}

// recovery: se lanza la ingesta en un proceso hijo, se mata varias veces en
// puntos al azar, se reanuda hasta el final y se comprueba que no hay
// duplicados ni pérdidas. El valor es la fracción de repeticiones correctas.
func (s *script) recovery() ([]row, error) {
	rng := rand.New(rand.NewSource(benchkit.Seed))
	var rows []row
	for _, kind := range lakeKinds {
		for _, n := range s.env.Sizes {
			ids, err := s.env.Books(n)
			if err != nil {
				return nil, err
			}
			out := filepath.Join(s.env.Work, "recovery")

			// Una ingesta completa sin cortes da la escala de tiempo.
			os.RemoveAll(out)
			start := time.Now()
			if err := s.child("ingest", kind, out, n).Run(); err != nil {
				return nil, err
			}
			full := time.Since(start)

			var values []float64
			for rep := 0; rep < s.opts.reps; rep++ {
				os.RemoveAll(out)
				kills := 0
				for cut := 0; cut < 3; cut++ {
					cmd := s.child("ingest", kind, out, n)
					if err := cmd.Start(); err != nil {
						return nil, err
					}
					time.Sleep(time.Duration((0.1 + 0.5*rng.Float64()) * float64(full)))
					if cmd.Process.Kill() == nil {
						kills++
					}
					cmd.Wait()
				}
				if err := s.child("ingest", kind, out, n).Run(); err != nil {
					return nil, err
				}
				problem := verifyIngest(kind, out, ids)
				ok := 0.0
				if problem == "" {
					ok = 1
				}
				s.logf("recovery %s n=%d rep=%d: %d cortes, %s", kind, n, rep+1, kills, describe(problem))
				values = append(values, ok)
			}
			os.RemoveAll(out)
			rows = append(rows, row{"datalake", kind, n, "recovery_ok", values, "bool"})
		}
	}
	return rows, nil
}

func describe(problem string) string {
	if problem == "" {
		return "sin duplicados ni pérdidas"
	}
	return "ERROR: " + problem
}

// verifyIngest comprueba que un datalake tiene exactamente ids: cada uno
// registrado una sola vez, con sus dos ficheros y sin restos de escrituras.
func verifyIngest(kind, out string, ids []int) string {
	data, err := os.ReadFile(filepath.Join(out, "control", "downloaded_books.txt"))
	if err != nil {
		return err.Error()
	}
	var registered []int
	for _, line := range strings.Fields(string(data)) {
		id, _ := strconv.Atoi(line)
		registered = append(registered, id)
	}
	slices.Sort(registered)
	if !slices.Equal(registered, ids) {
		return fmt.Sprintf("downloaded_books.txt tiene %d líneas para %d libros", len(registered), len(ids))
	}

	store, err := datalake.New(kind, out)
	if err != nil {
		return err.Error()
	}
	inLake, err := store.List()
	if err != nil {
		return err.Error()
	}
	if !slices.Equal(inLake, ids) {
		return fmt.Sprintf("el datalake tiene %d libros completos de %d", len(inLake), len(ids))
	}

	files, _, _, err := fileutil.TreeStats(store.Root())
	if err != nil {
		return err.Error()
	}
	want := 2 * len(ids)
	if kind == "time" {
		want++ // _locations.tsv
	}
	if files != want {
		return fmt.Sprintf("%d ficheros en el datalake, se esperaban %d", files, want)
	}
	return ""
}

// indexFootprint: para cada índice y cada N, un proceso nuevo lo construye y
// devuelve su pico de memoria residente y lo que ocupa el índice en disco.
func (s *script) indexFootprint() ([]row, error) {
	if _, err := s.env.Source(); err != nil {
		return nil, err
	}
	var rows []row
	for _, kind := range indexKinds {
		if kind == "mongo" {
			idx, err := s.env.OpenIndex(kind, s.env.Work)
			if err != nil {
				continue
			}
			idx.Close()
		}
		for _, n := range s.env.Sizes {
			var memory, disk []float64
			for rep := 0; rep < s.opts.reps; rep++ {
				output, err := s.child("index", kind, filepath.Join(s.env.Work, "footprint"), n).Output()
				if err != nil {
					return nil, fmt.Errorf("índice %s n=%d: %w", kind, n, err)
				}
				var peak, size int64
				var source string
				if _, err := fmt.Sscan(string(output), &peak, &size, &source); err != nil {
					return nil, fmt.Errorf("índice %s n=%d: salida inesperada %q", kind, n, output)
				}
				memory = append(memory, float64(peak)/(1<<20))
				disk = append(disk, float64(size))
				s.logf("footprint %s n=%d rep=%d: %.1f MB de pico (%s), %d bytes en disco", kind, n, rep+1, float64(peak)/(1<<20), source, size)
			}
			rows = append(rows,
				row{"index", kind, n, "peak_memory", memory, "MB"},
				row{"index", kind, n, "disk_usage", disk, "bytes"})
		}
	}
	return rows, nil
}

// downloadThroughput: libros por segundo descargando de Gutenberg, con la
// espera de 1 s entre peticiones del SPEC.
func (s *script) downloadThroughput() ([]row, error) {
	n := s.opts.downloads
	if n > len(s.env.IDs) {
		return nil, fmt.Errorf("--download-books=%d es mayor que la lista", n)
	}
	ids := s.env.IDs[:n]
	var values []float64
	for rep := 0; rep < s.opts.reps; rep++ {
		out := filepath.Join(s.env.Work, "download")
		os.RemoveAll(out)
		store, err := datalake.New(s.opts.datalake, out)
		if err != nil {
			return nil, err
		}
		state, err := control.Open(filepath.Join(out, "control"))
		if err != nil {
			return nil, err
		}
		pipeline := &control.Pipeline{
			Store:  store,
			State:  state,
			Source: ingestion.NewGutenberg(filepath.Join(out, "cache"), time.Second),
		}
		start := time.Now()
		summary, err := pipeline.Download(ids)
		if err != nil {
			return nil, err
		}
		rate := float64(summary.Downloaded) / time.Since(start).Seconds()
		s.logf("download %s rep=%d: %d libros, %.3f libros/s", s.opts.datalake, rep+1, summary.Downloaded, rate)
		values = append(values, rate)
		os.RemoveAll(out)
	}
	return []row{{"datalake", s.opts.datalake, n, "download_throughput", values, "books/s"}}, nil
}

// child prepara un proceso hijo de este mismo programa.
func (s *script) child(mode, kind, out string, n int) *exec.Cmd {
	exe, err := os.Executable()
	if err != nil {
		exe = os.Args[0]
	}
	cmd := exec.Command(exe, "--child="+mode, "--kind="+kind, "--out="+out, "--n="+strconv.Itoa(n))
	cmd.Stderr = os.Stderr
	return cmd
}

func runChild(opts options) error {
	env, err := benchkit.Load()
	if err != nil {
		return err
	}
	ids, err := env.Books(opts.n)
	if err != nil {
		return err
	}
	switch opts.child {
	case "ingest":
		_, err := env.Ingest(opts.kind, opts.out, ids)
		return err
	case "index":
		store, err := env.Source()
		if err != nil {
			return err
		}
		idx, err := env.OpenIndex(opts.kind, opts.out)
		if err != nil {
			return err
		}
		defer idx.Close()
		if err := idx.Reset(); err != nil {
			return err
		}
		postings, err := env.Postings(store, ids)
		if err != nil {
			return err
		}
		if err := idx.Add(postings); err != nil {
			return err
		}
		size, err := idx.DiskUsage()
		if err != nil {
			return err
		}
		peak, source := peakMemory()
		fmt.Println(peak, size, source)
		return idx.Reset()
	}
	return fmt.Errorf("modo hijo desconocido %q", opts.child)
}

// peakMemory devuelve el pico de memoria residente del proceso (VmHWM), que
// es lo que miden los tres lenguajes (SPEC §11). Fuera de Linux no existe
// /proc y se usa la memoria pedida por el runtime de Go, que se anota en la
// salida cruda para no mezclarla con la otra.
func peakMemory() (int64, string) {
	if data, err := os.ReadFile("/proc/self/status"); err == nil {
		for _, line := range strings.Split(string(data), "\n") {
			if value, ok := strings.CutPrefix(line, "VmHWM:"); ok {
				var kb int64
				if _, err := fmt.Sscan(strings.TrimSuffix(strings.TrimSpace(value), " kB"), &kb); err == nil {
					return kb * 1024, "VmHWM"
				}
			}
		}
	}
	var stats runtime.MemStats
	runtime.ReadMemStats(&stats)
	return int64(stats.Sys), "MemStats.Sys"
}

// writeEnvironment anota la máquina y la configuración, que el SPEC pide
// guardar junto a los resultados. Cada ejecución añade un bloque, así que el
// fichero refleja todas las que han aportado filas al CSV.
func writeEnvironment(path string, opts options) error {
	var sb strings.Builder
	if info, err := os.Stat(path); err == nil && info.Size() > 0 {
		sb.WriteString("\n")
	}
	fmt.Fprintf(&sb, "fecha: %s\n", time.Now().Format(time.RFC3339))
	fmt.Fprintf(&sb, "go: %s %s/%s\n", runtime.Version(), runtime.GOOS, runtime.GOARCH)
	fmt.Fprintf(&sb, "cpus: %d\n", runtime.NumCPU())
	if data, err := os.ReadFile("/proc/cpuinfo"); err == nil {
		for _, line := range strings.Split(string(data), "\n") {
			if name, ok := strings.CutPrefix(line, "model name"); ok {
				fmt.Fprintf(&sb, "cpu: %s\n", strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(name), ":")))
				break
			}
		}
	}
	if data, err := os.ReadFile("/proc/meminfo"); err == nil {
		fmt.Fprintf(&sb, "ram: %s\n", strings.TrimSpace(strings.TrimPrefix(strings.SplitN(string(data), "\n", 2)[0], "MemTotal:")))
	}
	fmt.Fprintf(&sb, "tamaños: %s\nmedidas: %s\niteraciones: %d de calentamiento + %d medidas\nrepeticiones de script: %d\nmongo: %s\n",
		opts.sizes, opts.only, opts.warmup, opts.count, opts.reps, opts.mongo)
	fmt.Fprintln(&sb, "disco: anotar a mano (SSD/HDD) y el sistema de ficheros")

	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		return err
	}
	if _, err := f.WriteString(sb.String()); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}
