package es.ulpgc.bigdata;

import es.ulpgc.bigdata.control.BookIds;
import es.ulpgc.bigdata.control.ControlPipeline;
import es.ulpgc.bigdata.control.ControlState;
import es.ulpgc.bigdata.control.RecoveryReport;
import es.ulpgc.bigdata.control.Summary;
import es.ulpgc.bigdata.datalake.BookLocation;
import es.ulpgc.bigdata.datalake.DatalakeStore;
import es.ulpgc.bigdata.index.InvertedIndex;
import es.ulpgc.bigdata.index.Postings;
import es.ulpgc.bigdata.ingestion.CacheSource;
import es.ulpgc.bigdata.ingestion.GutenbergSource;
import es.ulpgc.bigdata.metadata.BookMetadata;
import es.ulpgc.bigdata.metadata.MetadataDatabase;
import es.ulpgc.bigdata.metadata.MetadataFilter;
import es.ulpgc.bigdata.tokenizer.Tokenizer;
import es.ulpgc.bigdata.util.Text;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** CLI del pipeline. Mismos comandos, opciones y salida que las versiones de Python y Go. */
public class Main {

    static final String LANG = "java";

    private static final String USAGE = """
            Uso: mvn -q exec:java -Dexec.args="<comando> [argumentos...] [opciones]"
                 java -jar target/stage1-java.jar <comando> [argumentos...] [opciones]

            Comandos:
              pipeline [ids...]   ciclo completo: indexa lo pendiente, descarga los libros nuevos y los indexa
              download [ids...]   guarda en el datalake los libros que falten (sin IDs: los de la lista)
              index               indexa los libros descargados que aún no están indexados
              query [texto...]    busca en el índice (sin texto: las consultas de shared/queries.txt)
              books [ids...]      consulta los metadatos por ID o con --author, --title y --language
              dump                exporta metadata.tsv e index.tsv a output/java/dumps/<índice>/
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
              --root=DIR                   raíz del repositorio: shared/, cache/ y output/ (por defecto ..)""";

    public static void main(String[] args) {
        // Como Go y Python, la salida siempre en UTF-8, sea cual sea la codificación del sistema.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        try {
            run(args);
        } catch (Exception e) {
            System.out.printf("[ERROR]: %s%n", e.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        Options opts;
        try {
            opts = Options.parse(args);
        } catch (Options.HelpRequested e) {
            System.out.println(USAGE);
            return;
        }
        if (opts.positional.isEmpty()) {
            System.out.println(USAGE);
            return;
        }
        if (opts.sample) {
            opts.offline = true;
        }

        String command = opts.positional.get(0);
        List<String> rest = opts.positional.subList(1, opts.positional.size());
        switch (command) {
            case "pipeline" -> runPipeline(opts, rest);
            case "download" -> download(opts, rest);
            case "index" -> indexPending(opts);
            case "query" -> query(opts, rest);
            case "books" -> books(opts, rest);
            case "dump" -> dump(opts);
            case "lookup" -> lookup(opts, rest);
            case "status" -> status(opts);
            case "recover" -> recoverState(newPipeline(opts));
            default -> {
                System.out.println(USAGE);
                throw new IllegalArgumentException("comando desconocido \"" + command + "\"");
            }
        }
    }

    /** Abre el datalake y el control, que usan casi todos los comandos. */
    private static ControlPipeline newPipeline(Options opts) throws IOException {
        DatalakeStore store = DatalakeStore.create(opts.datalake, opts.output());
        ControlState state = new ControlState(opts.output().resolve("control"));
        return new ControlPipeline(store, state)
                .withLog(message -> System.out.printf("[CONTROL:%s] %s%n", store.name(), message));
    }

    private static void setSource(ControlPipeline pipeline, Options opts) {
        Path cacheDir = opts.cacheDir();
        pipeline.withSource(opts.offline ? new CacheSource(cacheDir) : new GutenbergSource(cacheDir, opts.delay));
    }

    /** Datamarts abiertos: metadatos + índice. Se cierran juntos. */
    private record Datamarts(MetadataDatabase metadata, InvertedIndex index) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                index.close();
            } finally {
                metadata.close();
            }
        }
    }

    /** Añade al pipeline lo que hace falta para indexar. */
    private static Datamarts openDatamarts(ControlPipeline pipeline, Options opts) throws IOException {
        Set<String> stop = loadStopwords(opts);
        MetadataDatabase db = openMetadata(opts);
        InvertedIndex idx;
        try {
            idx = openIndex(opts);
        } catch (IOException | RuntimeException e) {
            db.close();
            throw e;
        }
        pipeline.withDatamarts(db, idx, stop, opts.datamarts());
        return new Datamarts(db, idx);
    }

    private static MetadataDatabase openMetadata(Options opts) throws IOException {
        return new MetadataDatabase(opts.datamarts().resolve("metadata.db"));
    }

    private static InvertedIndex openIndex(Options opts) throws IOException {
        return InvertedIndex.create(opts.index, new InvertedIndex.Config(opts.datamarts(), opts.mongoUri, LANG));
    }

    private static Set<String> loadStopwords(Options opts) throws IOException {
        Path path = opts.shared("stopwords.txt");
        try {
            return Tokenizer.loadStopwords(path);
        } catch (NoSuchFileException e) {
            System.out.printf("[AVISO] no existe %s: no se filtra ninguna stopword%n", path);
            return Set.of();
        }
    }

    private static List<Integer> parseIds(List<String> args) {
        List<Integer> ids = new ArrayList<>(args.size());
        for (String arg : args) {
            try {
                ids.add(Integer.parseInt(arg));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("\"" + arg + "\" no es un ID de libro");
            }
        }
        return ids;
    }

    /**
     * Libros con los que trabaja el comando: los que se pasan como argumento o, si no hay,
     * los primeros --n de la lista compartida.
     */
    private static List<Integer> targetIds(Options opts, List<String> args) throws IOException {
        List<Integer> ids = parseIds(args);
        if (ids.isEmpty()) {
            ids = BookIds.read(opts.idsPath());
        }
        if (opts.n > 0 && opts.n < ids.size()) {
            ids = ids.subList(0, opts.n);
        }
        return ids;
    }

    private static void recoverState(ControlPipeline pipeline) throws IOException {
        RecoveryReport report = pipeline.recover();
        if (!report.isClean()) {
            System.out.printf("[RECOVER:%s] %d .tmp borrados, %d sin registrar, %d registrados sin libro, "
                            + "%d indexados sin libro, %d por reindexar%n",
                    pipeline.store().name(), report.tempFiles, report.unregistered.size(),
                    report.missing.size(), report.orphanIndexed.size(), report.reindex);
        }
    }

    private static void runPipeline(Options opts, List<String> args) throws Exception {
        List<Integer> ids = targetIds(opts, args);
        ControlPipeline pipeline = newPipeline(opts);
        setSource(pipeline, opts);
        try (Datamarts ignored = openDatamarts(pipeline, opts)) {
            recoverState(pipeline);
            Summary summary = new Summary();
            try {
                pipeline.run(ids, opts.batch, summary);
            } finally {
                System.out.printf("[PIPELINE:%s/%s] %d guardados, %d descartados, %d ya estaban, %d indexados%n",
                        pipeline.store().name(), pipeline.index().name(),
                        summary.downloaded, summary.discarded, summary.skipped, summary.indexed);
            }
        }
    }

    private static void download(Options opts, List<String> args) throws Exception {
        List<Integer> ids = targetIds(opts, args);
        ControlPipeline pipeline = newPipeline(opts);
        setSource(pipeline, opts);

        recoverState(pipeline);
        Summary summary = new Summary();
        try {
            pipeline.download(ids, summary);
        } finally {
            System.out.printf("[DOWNLOAD:%s] %d guardados, %d descartados, %d ya estaban%n",
                    pipeline.store().name(), summary.downloaded, summary.discarded, summary.skipped);
        }
    }

    private static void indexPending(Options opts) throws IOException {
        ControlPipeline pipeline = newPipeline(opts);
        try (Datamarts ignored = openDatamarts(pipeline, opts)) {
            if (opts.rebuild) {
                pipeline.rebuild();
            }
            recoverState(pipeline);
            Summary summary = new Summary();
            try {
                pipeline.indexPending(opts.batch, summary);
            } finally {
                System.out.printf("[INDEX:%s] %d libros indexados%n", pipeline.index().name(), summary.indexed);
            }
        }
    }

    private static void query(Options opts, List<String> words) throws IOException {
        List<String> queries = new ArrayList<>();
        if (words.isEmpty()) {
            for (String line : Files.readString(opts.shared("queries.txt"), StandardCharsets.UTF_8).split("\n", -1)) {
                String trimmed = Text.trimSpace(line);
                if (!trimmed.isEmpty()) {
                    queries.add(trimmed);
                }
            }
        } else {
            queries.add(String.join(" ", words));
        }

        Set<String> stop = loadStopwords(opts);
        try (InvertedIndex idx = openIndex(opts)) {
            Set<Integer> allowed = filteredIds(opts);
            for (String text : queries) {
                List<Integer> ids = Postings.search(idx, text, stop);
                if (allowed != null) {
                    ids = ids.stream().filter(allowed::contains).toList();
                }
                System.out.printf("[QUERY:%s] %s -> %d libros %s%n", idx.name(), text, ids.size(), Text.formatIds(ids));
            }
        }
    }

    /** Libros que cumplen el filtro de metadatos, o null si no se ha pedido ningún filtro. */
    private static Set<Integer> filteredIds(Options opts) throws IOException {
        if (opts.filter().isEmpty()) {
            return null;
        }
        try (MetadataDatabase db = openMetadata(opts)) {
            Set<Integer> allowed = new HashSet<>();
            for (BookMetadata book : db.find(opts.filter())) {
                allowed.add(book.id());
            }
            return allowed;
        }
    }

    /**
     * Consulta el datamart de metadatos: por ID, o por título, autor e idioma. Cada línea
     * incluye la ruta del body en el datalake.
     */
    private static void books(Options opts, List<String> args) throws IOException {
        List<Integer> ids = parseIds(args);
        List<BookMetadata> found = new ArrayList<>();
        try (MetadataDatabase db = openMetadata(opts)) {
            if (ids.isEmpty()) {
                found.addAll(db.find(opts.filter()));
            }
            for (int id : ids) {
                Optional<BookMetadata> book = db.byId(id);
                if (book.isEmpty()) {
                    System.out.printf("[BOOKS] %d -> no está en los metadatos%n", id);
                    continue;
                }
                found.add(book.get());
            }
        }
        for (BookMetadata book : found) {
            System.out.printf("[BOOKS] %d | %s | %s | %s | %s%n", book.id(), orDash(book.title()),
                    orDash(book.author()), orDash(book.language()), book.bodyPath());
        }
        System.out.printf("[BOOKS] %d libros%n", found.size());
    }

    private static String orDash(String value) {
        return value.isEmpty() ? "-" : value;
    }

    /**
     * Exporta el contenido de los datamarts en el formato canónico que se usa para
     * comprobar que los tres lenguajes producen lo mismo.
     */
    private static void dump(Options opts) throws IOException {
        try (MetadataDatabase db = openMetadata(opts); InvertedIndex idx = openIndex(opts)) {
            Path dir = opts.output().resolve("dumps").resolve(idx.name());
            Files.createDirectories(dir);
            db.dumpTsv(dir.resolve("metadata.tsv"));
            Postings.dumpTsv(idx, dir.resolve("index.tsv"));
            System.out.printf("[DUMP:%s] metadata.tsv e index.tsv en %s%n", idx.name(), dir);
        }
    }

    private static void lookup(Options opts, List<String> args) throws IOException {
        List<Integer> ids = parseIds(args);
        DatalakeStore store = DatalakeStore.create(opts.datalake, opts.output());
        for (int id : ids) {
            Optional<BookLocation> location = store.locate(id);
            if (location.isEmpty()) {
                System.out.printf("[LOOKUP:%s] %d -> no encontrado%n", store.name(), id);
                continue;
            }
            System.out.printf("[LOOKUP:%s] %d -> %s | %s%n", store.name(), id,
                    location.get().header(), location.get().body());
        }
    }

    private static void status(Options opts) throws IOException {
        ControlPipeline pipeline = newPipeline(opts);
        DatalakeStore store = pipeline.store();
        ControlState state = pipeline.state();
        System.out.printf("""
                        [STATUS:%s]
                          en datalake:   %d
                          descargados:   %d
                          indexados:     %d
                          pendientes:    %d
                          descartados:   %d
                        """, store.name(), store.list().size(), state.downloaded().size(),
                state.indexed().size(), state.pending().size(), state.failed().size());
    }

    /** Opciones de la línea de comandos, con las mismas reglas que el paquete flag de Go. */
    static final class Options {

        static final class HelpRequested extends Exception {
            private static final long serialVersionUID = 1L;
        }

        Path root = Path.of("..");
        String datalake = "book";
        String index = "json";
        boolean offline;
        boolean sample;
        boolean rebuild;
        int n;
        int batch = 100;
        String idsFile;
        Duration delay = Duration.ofSeconds(1);
        String mongoUri = "mongodb://localhost:27017";
        String author;
        String title;
        String language;
        final List<String> positional = new ArrayList<>();

        Path output() {
            return root.resolve("output").resolve(LANG);
        }

        Path datamarts() {
            return output().resolve("datamarts");
        }

        Path shared(String name) {
            return root.resolve("shared").resolve(name);
        }

        /**
         * De donde se leen los libros crudos sin red: la caché completa o los pocos libros
         * de muestra que sí están en el repositorio.
         */
        Path cacheDir() {
            return root.resolve(sample ? "sample_data" : "cache");
        }

        Path idsPath() {
            if (idsFile != null) {
                return Path.of(idsFile);
            }
            return shared(sample ? "book_ids_sample.txt" : "book_ids.txt");
        }

        MetadataFilter filter() {
            return new MetadataFilter(title, author, language);
        }

        /** Admite -opcion o --opcion, con =valor o con el valor como argumento siguiente. */
        static Options parse(String[] args) throws HelpRequested {
            Options opts = new Options();
            boolean onlyPositional = false;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (onlyPositional || !arg.startsWith("-") || arg.equals("-")) {
                    opts.positional.add(arg);
                    continue;
                }
                if (arg.equals("--")) {
                    onlyPositional = true;
                    continue;
                }
                String name = arg.substring(arg.startsWith("--") ? 2 : 1);
                String value = null;
                int eq = name.indexOf('=');
                if (eq >= 0) {
                    value = name.substring(eq + 1);
                    name = name.substring(0, eq);
                }
                switch (name) {
                    case "h", "help" -> throw new HelpRequested();
                    case "offline" -> opts.offline = parseBool(name, value);
                    case "sample" -> opts.sample = parseBool(name, value);
                    case "rebuild" -> opts.rebuild = parseBool(name, value);
                    default -> {
                        if (value == null) {
                            if (i + 1 >= args.length) {
                                throw new IllegalArgumentException("falta el valor de la opción -" + name);
                            }
                            value = args[++i];
                        }
                        opts.set(name, value);
                    }
                }
            }
            return opts;
        }

        private void set(String name, String value) {
            switch (name) {
                case "root" -> root = Path.of(value);
                case "datalake" -> datalake = value;
                case "index" -> index = value;
                case "n" -> n = parseInt(name, value);
                case "batch" -> batch = parseInt(name, value);
                case "ids" -> idsFile = value;
                case "delay" -> delay = parseDuration(value);
                case "mongo" -> mongoUri = value;
                case "author" -> author = value;
                case "title" -> title = value;
                case "language" -> language = value;
                default -> throw new IllegalArgumentException("opción desconocida: -" + name);
            }
        }

        private static boolean parseBool(String name, String value) {
            if (value == null) {
                return true;
            }
            return switch (value) {
                case "1", "t", "T", "true", "TRUE", "True" -> true;
                case "0", "f", "F", "false", "FALSE", "False" -> false;
                default -> throw new IllegalArgumentException(
                        "valor \"" + value + "\" no válido para la opción -" + name);
            };
        }

        private static int parseInt(String name, String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("valor \"" + value + "\" no válido para la opción -" + name);
            }
        }

        /** Formato de duración de Go: "1s", "500ms", "1m30s", "0"... */
        static Duration parseDuration(String text) {
            if (text.equals("0")) {
                return Duration.ZERO;
            }
            var matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d*)?|\\.\\d+)(ns|us|µs|ms|s|m|h)")
                    .matcher(text);
            double nanos = 0;
            int end = 0;
            while (matcher.find() && matcher.start() == end) {
                double amount = Double.parseDouble(matcher.group(1));
                nanos += amount * switch (matcher.group(2)) {
                    case "ns" -> 1L;
                    case "us", "µs" -> 1_000L;
                    case "ms" -> 1_000_000L;
                    case "s" -> 1_000_000_000L;
                    case "m" -> 60_000_000_000L;
                    default -> 3_600_000_000_000L;
                };
                end = matcher.end();
            }
            if (end == 0 || end != text.length()) {
                throw new IllegalArgumentException("duración no válida \"" + text + "\" (ejemplos: 1s, 500ms)");
            }
            return Duration.ofNanos((long) nanos);
        }
    }
}
