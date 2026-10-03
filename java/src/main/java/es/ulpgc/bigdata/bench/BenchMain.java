package es.ulpgc.bigdata.bench;

import es.ulpgc.bigdata.control.ControlPipeline;
import es.ulpgc.bigdata.control.ControlState;
import es.ulpgc.bigdata.control.Summary;
import es.ulpgc.bigdata.datalake.DatalakeStore;
import es.ulpgc.bigdata.index.InvertedIndex;
import es.ulpgc.bigdata.ingestion.GutenbergSource;
import es.ulpgc.bigdata.util.FileUtils;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.openjdk.jmh.util.ListStatistics;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleBinaryOperator;
import java.util.stream.Collectors;

/**
 * Ejecuta los benchmarks de Java del SPEC §11 y deja los resultados en
 * benchmarks/results/java.csv y la salida cruda en benchmarks/raw/java/. Desde java/:
 * <pre>
 *   java -cp target/benchmarks.jar es.ulpgc.bigdata.bench.BenchMain                    # JMH y métricas de script
 *   java -cp target/benchmarks.jar es.ulpgc.bigdata.bench.BenchMain --only=download    # throughput de descarga
 *   java -cp target/benchmarks.jar es.ulpgc.bigdata.bench.BenchMain --sizes=100,250
 * </pre>
 * Cada ejecución actualiza las filas que mide y conserva las demás del CSV. Los
 * micro-benchmarks también se pueden lanzar con JMH directamente:
 * {@code java -jar target/benchmarks.jar -wi 5 -i 10 -f 2}.
 */
public final class BenchMain {

    static final String LANG = "java";
    static final String CSV_HEADER = "lang,component,structure,n_books,metric,mean,stddev,min,max,ci_low,ci_high,iterations,unit";
    static final List<String> LAKES = List.of("time", "book", "range");
    static final List<String> INDEXES = List.of("json", "folders", "mongo");

    /** Una fila del CSV: los valores son las iteraciones medidas, ya en la unidad de la métrica. */
    record Row(String component, String structure, int n, String metric, List<Double> values, String unit,
               double[] ci) {
        String key() {
            return String.join(",", LANG, component, structure, String.valueOf(n), metric);
        }
    }

    /** Cómo se convierte el resultado de cada método de JMH en la métrica del SPEC. */
    record Metric(String component, String metric, String unit, DoubleBinaryOperator convert) {
    }

    static final Map<String, Metric> METRICS = Map.of(
            "writeThroughput", new Metric("datalake", "write_throughput", "books/s", (ms, n) -> n / (ms / 1000)),
            "lookupTime", new Metric("datalake", "lookup_time", "us", (us, n) -> us),
            "incrementalDetectTime", new Metric("datalake", "incremental_detect_time", "ms", (ms, n) -> ms),
            "metadataInsertTime", new Metric("metadata", "metadata_insert_time", "ms", (ms, n) -> ms),
            "metadataQueryTimeAuthor", new Metric("metadata", "metadata_query_time_author", "us", (us, n) -> us),
            "metadataQueryTimeId", new Metric("metadata", "metadata_query_time_id", "us", (us, n) -> us),
            "indexBuildTime", new Metric("index", "index_build_time", "ms", (ms, n) -> ms),
            "queryTime", new Metric("index", "query_time", "us", (us, n) -> us),
            "updateTime", new Metric("index", "update_time", "ms", (ms, n) -> ms));

    private final Map<String, String> opts;
    private final BenchEnv env;
    private final Path rawDir;
    private PrintWriter log;

    private BenchMain(Map<String, String> opts) throws IOException {
        this.opts = opts;
        System.setProperty("stage1.root", opts.get("root"));
        System.setProperty("stage1.sizes", opts.get("sizes"));
        System.setProperty("stage1.mongo", opts.get("mongo"));
        env = BenchEnv.get();
        rawDir = env.root.resolve("benchmarks").resolve("raw").resolve(LANG);
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        Map<String, String> opts = new LinkedHashMap<>(Map.of(
                "root", "..", "sizes", "100,250,500,1000", "mongo", "mongodb://localhost:27017",
                "only", "micro,script", "warmup", "5", "count", "10", "forks", "2", "reps", "5",
                "reduce-folders-from", "0", "download-books", "50"));
        for (String arg : args) {
            String[] parts = arg.replaceFirst("^--?", "").split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("opción no válida: " + arg + " (formato --nombre=valor)");
            }
            opts.put(parts[0], parts[1]);
        }
        if (opts.containsKey("child")) {
            runChild(opts);
            return;
        }
        new BenchMain(opts).run();
    }

    private int option(String name) {
        return Integer.parseInt(opts.get(name));
    }

    private void run() throws Exception {
        Files.createDirectories(rawDir);
        Path csv = env.root.resolve("benchmarks").resolve("results").resolve(LANG + ".csv");
        Files.createDirectories(csv.getParent());
        writeEnvironment(rawDir.resolve("environment.txt"));

        List<String> only = List.of(opts.get("only").split(","));
        List<Row> rows = new ArrayList<>();
        try {
            if (only.contains("micro")) {
                rows.addAll(micro());
            }
            if (only.contains("script")) {
                try (PrintWriter writer = logTo("script.txt")) {
                    rows.addAll(storage());
                    rows.addAll(recovery());
                    rows.addAll(indexFootprint());
                }
            }
            if (only.contains("download")) {
                try (PrintWriter writer = logTo("download.txt")) {
                    rows.addAll(downloadThroughput());
                }
            }
        } finally {
            updateCsv(csv, rows);
            System.out.printf("[BENCH] %d filas actualizadas en %s%n", rows.size(), csv);
            FileUtils.deleteRecursively(env.work);
        }
    }

    // ------------------------------------------------------------------ JMH

    /** Una ejecución de JMH: qué métodos, con qué parámetros y cuántas iteraciones. */
    private record Group(String name, String include, List<String> kinds, List<Integer> sizes, boolean reduced) {
    }

    private List<Group> groups() {
        List<Integer> sizes = env.sizes;
        int reduceFrom = option("reduce-folders-from");
        List<String> indexes = new ArrayList<>(INDEXES);
        if (!mongoAvailable()) {
            System.out.printf("[AVISO] MongoDB no responde en %s: se omiten las medidas de mongo%n", env.mongoUri);
            indexes.remove("mongo");
        }
        List<String> others = indexes.stream().filter(k -> !k.equals("folders")).toList();
        List<Integer> small = sizes.stream().filter(n -> reduceFrom == 0 || n < reduceFrom).toList();
        List<Integer> large = sizes.stream().filter(n -> reduceFrom > 0 && n >= reduceFrom).toList();

        List<Group> groups = new ArrayList<>();
        groups.add(new Group("datalake", "DatalakeBench", LAKES, sizes, false));
        groups.add(new Group("metadata", "MetadataBench", null, sizes, false));
        groups.add(new Group("index", "IndexBench", others, sizes, false));
        groups.add(new Group("index_folders_query", "IndexBench\\.queryTime$", List.of("folders"), sizes, false));
        // Construir y actualizar folders escribe un fichero por término: con N grande se
        // reduce a 1 + 3 iteraciones en un fork (ver --reduce-folders-from).
        String heavy = "IndexBench\\.(indexBuildTime|updateTime)$";
        groups.add(new Group("index_folders", heavy, List.of("folders"), small, false));
        groups.add(new Group("index_folders_reduced", heavy, List.of("folders"), large, true));
        return groups.stream().filter(g -> !g.sizes().isEmpty() && (g.kinds() == null || !g.kinds().isEmpty())).toList();
    }

    private List<Row> micro() throws RunnerException, IOException {
        List<Row> rows = new ArrayList<>();
        for (Group group : groups()) {
            Path result = rawDir.resolve("jmh_" + group.name() + ".json");
            ChainedOptionsBuilder builder = new OptionsBuilder()
                    .include("es\\.ulpgc\\.bigdata\\.bench\\." + group.include())
                    .param("n", group.sizes().stream().map(String::valueOf).toArray(String[]::new))
                    .warmupIterations(group.reduced() ? 1 : option("warmup"))
                    .measurementIterations(group.reduced() ? 3 : option("count"))
                    .forks(group.reduced() ? 1 : option("forks"))
                    .warmupTime(TimeValue.seconds(1))
                    .measurementTime(TimeValue.seconds(1))
                    .jvmArgsAppend("-Dstage1.root=" + env.root, "-Dstage1.sizes=" + opts.get("sizes"),
                            "-Dstage1.mongo=" + env.mongoUri)
                    .resultFormat(ResultFormatType.JSON)
                    .result(result.toString());
            if (group.kinds() != null) {
                builder.param("kind", group.kinds().toArray(String[]::new));
            }
            System.out.printf("[BENCH] JMH %s -> %s%n", group.name(), result);
            for (RunResult run : new Runner(builder.build()).run()) {
                rows.add(toRow(run));
            }
        }
        return rows;
    }

    private static Row toRow(RunResult run) {
        String benchmark = run.getParams().getBenchmark();
        Metric metric = METRICS.get(benchmark.substring(benchmark.lastIndexOf('.') + 1));
        String kind = run.getParams().getParam("kind");
        int n = Integer.parseInt(run.getParams().getParam("n"));
        List<Double> values = new ArrayList<>();
        for (BenchmarkResult fork : run.getBenchmarkResults()) {
            for (IterationResult iteration : fork.getIterationResults()) {
                values.add(metric.convert().applyAsDouble(iteration.getPrimaryResult().getScore(), n));
            }
        }
        return new Row(metric.component(), kind == null ? "sqlite" : kind, n, metric.metric(), values,
                metric.unit(), confidence(values));
    }

    /** Intervalo de confianza al 99,9 %, como el que da JMH. */
    private static double[] confidence(List<Double> values) {
        if (values.size() < 2) {
            return null;
        }
        ListStatistics stats = new ListStatistics(values.stream().mapToDouble(Double::doubleValue).toArray());
        return stats.getConfidenceIntervalAt(0.999);
    }

    private boolean mongoAvailable() {
        try (InvertedIndex index = env.openIndex("mongo", env.work)) {
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // --------------------------------------------------- métricas de script

    private PrintWriter logTo(String name) throws IOException {
        log = new PrintWriter(Files.newBufferedWriter(rawDir.resolve(name), StandardCharsets.UTF_8), true);
        return log;
    }

    private void logf(String format, Object... args) {
        String line = "[SCRIPT] " + String.format(Locale.ROOT, format, args);
        System.out.println(line);
        log.println(line);
    }

    private static Row scriptRow(String component, String structure, int n, String metric, List<Double> values,
                                 String unit) {
        return new Row(component, structure, n, metric, values, unit, null);
    }

    /**
     * Ficheros y carpetas de cada datalake con N libros, contando el control y el índice
     * auxiliar de time. Es determinista: se mide una vez.
     */
    private List<Row> storage() throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String kind : LAKES) {
            for (int n : env.sizes) {
                Path out = env.work.resolve("storage");
                FileUtils.deleteRecursively(out);
                env.ingest(kind, out, env.books(n));
                long[] stats = treeStats(out);
                FileUtils.deleteRecursively(out);
                logf("storage %s n=%d: %d ficheros, %d carpetas, %d bytes", kind, n, stats[0], stats[1], stats[2]);
                rows.add(scriptRow("datalake", kind, n, "files_count", List.of((double) stats[0]), "count"));
                rows.add(scriptRow("datalake", kind, n, "dirs_count", List.of((double) stats[1]), "count"));
            }
        }
        return rows;
    }

    /** Ficheros, carpetas (sin contar root) y bytes que hay bajo root. */
    static long[] treeStats(Path root) throws IOException {
        long[] stats = new long[3];
        if (!Files.exists(root)) {
            return stats;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isDirectory(path)) {
                    stats[1] += path.equals(root) ? 0 : 1;
                } else {
                    stats[0]++;
                    stats[2] += Files.size(path);
                }
            }
        }
        return stats;
    }

    /** Proceso hijo: esta misma clase con --child, con el mismo java y classpath. */
    private ProcessBuilder child(String mode, String kind, Path out, int n) {
        String java = ProcessHandle.current().info().command().orElse("java");
        return new ProcessBuilder(java, "-Dstage1.root=" + env.root, "-Dstage1.sizes=" + opts.get("sizes"),
                "-Dstage1.mongo=" + env.mongoUri, "-cp", System.getProperty("java.class.path"),
                BenchMain.class.getName(), "--child=" + mode, "--kind=" + kind, "--out=" + out, "--n=" + n)
                .redirectError(ProcessBuilder.Redirect.INHERIT);
    }

    private static void waitFor(ProcessBuilder builder) throws IOException, InterruptedException {
        Process process = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (process.waitFor() != 0) {
            throw new IOException("el proceso hijo ha fallado: " + builder.command());
        }
    }

    /**
     * Se lanza la ingesta en un proceso hijo, se mata 3 veces en momentos al azar entre el
     * 10 % y el 60 % de una ingesta completa, se reanuda hasta el final y se comprueba.
     * Valor = fracción de repeticiones correctas.
     */
    private List<Row> recovery() throws IOException, InterruptedException {
        Random random = new Random(BenchEnv.SEED);
        List<Row> rows = new ArrayList<>();
        for (String kind : LAKES) {
            for (int n : env.sizes) {
                List<Integer> ids = env.books(n).stream().sorted().toList();
                Path out = env.work.resolve("recovery");
                FileUtils.deleteRecursively(out);
                long start = System.nanoTime();
                waitFor(child("ingest", kind, out, n));
                long full = System.nanoTime() - start;

                List<Double> values = new ArrayList<>();
                for (int rep = 0; rep < option("reps"); rep++) {
                    FileUtils.deleteRecursively(out);
                    int kills = 0;
                    for (int cut = 0; cut < 3; cut++) {
                        Process process = child("ingest", kind, out, n)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                        long wait = (long) ((0.1 + 0.5 * random.nextDouble()) * full);
                        Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                        if (process.isAlive()) {
                            process.destroyForcibly();
                            kills++;
                        }
                        process.waitFor();
                    }
                    waitFor(child("ingest", kind, out, n));
                    String problem = verifyIngest(kind, out, ids);
                    logf("recovery %s n=%d rep=%d: %d cortes, %s", kind, n, rep + 1, kills,
                            problem.isEmpty() ? "sin duplicados ni pérdidas" : "ERROR: " + problem);
                    values.add(problem.isEmpty() ? 1.0 : 0.0);
                }
                FileUtils.deleteRecursively(out);
                rows.add(scriptRow("datalake", kind, n, "recovery_ok", values, "bool"));
            }
        }
        return rows;
    }

    /**
     * Cada ID una sola vez en downloaded_books.txt, todos con sus dos ficheros, ningún .tmp y
     * ningún fichero de más. Devuelve el problema o "".
     */
    private static String verifyIngest(String kind, Path out, List<Integer> ids) throws IOException {
        List<Integer> registered = Arrays.stream(Files.readString(out.resolve("control").resolve("downloaded_books.txt"))
                        .trim().split("\\s+"))
                .filter(s -> !s.isEmpty()).map(Integer::parseInt).sorted().toList();
        if (!registered.equals(ids)) {
            return "downloaded_books.txt tiene " + registered.size() + " líneas para " + ids.size() + " libros";
        }
        DatalakeStore store = DatalakeStore.create(kind, out);
        List<Integer> inLake = store.list();
        if (!inLake.equals(ids)) {
            return "el datalake tiene " + inLake.size() + " libros completos de " + ids.size();
        }
        long files = treeStats(store.root())[0];
        long want = 2L * ids.size() + (kind.equals("time") ? 1 : 0); // _locations.tsv
        return files == want ? "" : files + " ficheros en el datalake, se esperaban " + want;
    }

    /**
     * Para cada índice y cada N, un proceso nuevo lo construye en un lote y devuelve su pico
     * de memoria residente y lo que ocupa el índice en disco.
     */
    private List<Row> indexFootprint() throws IOException, InterruptedException {
        env.source();
        int reduceFrom = option("reduce-folders-from");
        List<Row> rows = new ArrayList<>();
        for (String kind : INDEXES) {
            if (kind.equals("mongo") && !mongoAvailable()) {
                logf("MongoDB no responde: se omiten las medidas de mongo");
                continue;
            }
            for (int n : env.sizes) {
                int reps = kind.equals("folders") && reduceFrom > 0 && n >= reduceFrom ? 3 : option("reps");
                List<Double> memory = new ArrayList<>();
                List<Double> disk = new ArrayList<>();
                for (int rep = 0; rep < reps; rep++) {
                    Process process = child("index", kind, env.work.resolve("footprint"), n).start();
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (process.waitFor() != 0) {
                        throw new IOException("índice " + kind + " n=" + n + ": el proceso hijo ha fallado");
                    }
                    String[] fields = output.split("\\s+");
                    double peak = Long.parseLong(fields[0]) / (double) (1 << 20);
                    memory.add(peak);
                    disk.add((double) Long.parseLong(fields[1]));
                    logf("footprint %s n=%d rep=%d: %.1f MB de pico (%s), %s bytes en disco", kind, n, rep + 1, peak,
                            fields[2], fields[1]);
                }
                rows.add(scriptRow("index", kind, n, "peak_memory", memory, "MB"));
                rows.add(scriptRow("index", kind, n, "disk_usage", disk, "bytes"));
            }
        }
        return rows;
    }

    /** Libros por segundo descargando de Gutenberg al datalake book, con 1 s entre peticiones. */
    private List<Row> downloadThroughput() throws IOException, InterruptedException {
        int books = option("download-books");
        List<Integer> ids = env.ids.subList(0, books);
        List<Double> values = new ArrayList<>();
        for (int rep = 0; rep < option("reps"); rep++) {
            Path out = env.work.resolve("download");
            FileUtils.deleteRecursively(out);
            ControlPipeline pipeline = new ControlPipeline(DatalakeStore.create("book", out),
                    new ControlState(out.resolve("control")))
                    .withSource(new GutenbergSource(out.resolve("cache"), Duration.ofSeconds(1)));
            Summary summary = new Summary();
            long start = System.nanoTime();
            pipeline.download(ids, summary);
            double rate = summary.downloaded / ((System.nanoTime() - start) / 1e9);
            logf("download book rep=%d: %d libros, %.3f libros/s", rep + 1, summary.downloaded, rate);
            values.add(rate);
            FileUtils.deleteRecursively(out);
        }
        return List.of(scriptRow("datalake", "book", books, "download_throughput", values, "books/s"));
    }

    private static void runChild(Map<String, String> opts) throws Exception {
        BenchEnv env = BenchEnv.get();
        String kind = opts.get("kind");
        Path out = Path.of(opts.get("out"));
        List<Integer> ids = env.books(Integer.parseInt(opts.get("n")));
        if (opts.get("child").equals("ingest")) {
            env.ingest(kind, out, ids);
            return;
        }
        DatalakeStore store = env.source();
        try (InvertedIndex index = env.openIndex(kind, out)) {
            index.reset();
            index.add(env.postings(store, ids));
            long size = index.diskUsage();
            PeakMemory.Reading peak = PeakMemory.read();
            System.out.println(peak.bytes() + " " + size + " " + peak.source());
            index.reset();
        }
    }

    // ------------------------------------------------------------------ CSV

    /** Punto decimal y como mucho 3 decimales, sin ceros de sobra. */
    static String number(double value) {
        String text = String.format(Locale.ROOT, "%.3f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text.equals("-0") ? "0" : text;
    }

    static String line(Row row) {
        List<Double> values = row.values();
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
        double stddev = values.size() > 1 ? Math.sqrt(variance / (values.size() - 1)) : 0;
        double[] ci = row.ci();
        boolean hasCi = ci != null && !Double.isNaN(ci[0]) && !Double.isNaN(ci[1]);
        return String.join(",", row.key(), number(mean), number(stddev),
                number(values.stream().mapToDouble(Double::doubleValue).min().orElse(0)),
                number(values.stream().mapToDouble(Double::doubleValue).max().orElse(0)),
                hasCi ? number(ci[0]) : "", hasCi ? number(ci[1]) : "", String.valueOf(values.size()), row.unit());
    }

    /**
     * Reescribe el CSV con las filas nuevas y conserva las anteriores que no se han vuelto a
     * medir. Orden: componente, métrica, estructura y N.
     */
    static void updateCsv(Path path, List<Row> rows) throws IOException {
        Map<String, String> lines = new HashMap<>();
        if (Files.exists(path)) {
            List<String> existing = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : existing.subList(Math.min(1, existing.size()), existing.size())) {
                String[] fields = line.split(",", -1);
                if (fields.length == 13) {
                    lines.put(String.join(",", Arrays.copyOf(fields, 5)), line);
                }
            }
        }
        for (Row row : rows) {
            lines.put(row.key(), line(row));
        }
        Comparator<String[]> order = Comparator.<String[], String>comparing(f -> f[1])
                .thenComparing(f -> f[4]).thenComparing(f -> f[2]).thenComparingInt(f -> Integer.parseInt(f[3]));
        String body = lines.keySet().stream().map(k -> k.split(",")).sorted(order)
                .map(f -> lines.get(String.join(",", f)) + "\n").collect(Collectors.joining());
        Files.writeString(path, CSV_HEADER + "\n" + body, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- entorno

    /** Anota la máquina y la configuración. Cada ejecución añade un bloque. */
    private void writeEnvironment(Path path) throws IOException {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        String reduced = option("reduce-folders-from") > 0
                ? " (folders con N >= " + opts.get("reduce-folders-from")
                + ": construcción, actualización y memoria con 1 + 3, 1 fork)" : "";
        String block = String.join("\n",
                "fecha: " + OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + "), JMH 1.35",
                "sistema: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                        + System.getProperty("os.arch"),
                "cpus: " + Runtime.getRuntime().availableProcessors(),
                "cpu: " + cpuName(),
                "ram: " + os.getTotalMemorySize() / 1024 + " kB",
                "tamaños: " + opts.get("sizes"),
                "medidas: " + opts.get("only"),
                "iteraciones: " + opts.get("warmup") + " de calentamiento + " + opts.get("count") + " medidas, "
                        + opts.get("forks") + " forks" + reduced,
                "repeticiones de script: " + opts.get("reps"),
                "mongo: " + env.mongoUri) + "\n";
        String separator = Files.exists(path) && Files.size(path) > 0 ? "\n" : "";
        Files.writeString(path, separator + block, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String cpuName() {
        try {
            Path cpuinfo = Path.of("/proc/cpuinfo");
            if (Files.exists(cpuinfo)) {
                return Files.readAllLines(cpuinfo).stream().filter(l -> l.startsWith("model name"))
                        .map(l -> l.split(":", 2)[1].trim()).findFirst().orElse("?");
            }
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                Process process = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                        "(Get-CimInstance Win32_Processor).Name").start();
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            // se anota como desconocida
        }
        return "?";
    }
}
