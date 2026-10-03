package es.ulpgc.bigdata.bench;

import es.ulpgc.bigdata.control.BookIds;
import es.ulpgc.bigdata.control.ControlPipeline;
import es.ulpgc.bigdata.control.ControlState;
import es.ulpgc.bigdata.control.Summary;
import es.ulpgc.bigdata.datalake.BookLocation;
import es.ulpgc.bigdata.datalake.DatalakeStore;
import es.ulpgc.bigdata.index.InvertedIndex;
import es.ulpgc.bigdata.ingestion.CacheSource;
import es.ulpgc.bigdata.metadata.BookMetadata;
import es.ulpgc.bigdata.metadata.MetadataExtractor;
import es.ulpgc.bigdata.tokenizer.Tokenizer;
import es.ulpgc.bigdata.util.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Preparación común a los micro-benchmarks (JMH) y a las métricas de script, para que ambos
 * midan exactamente lo mismo (SPEC §11). La configuración se lee de las propiedades
 * stage1.root, stage1.sizes y stage1.mongo o de las variables de entorno STAGE1_ROOT,
 * STAGE1_SIZES y STAGE1_MONGO; BenchMain se las pasa a los forks de JMH.
 */
public final class BenchEnv {

    /** Libros que se añaden en las medidas de actualización y de detección. */
    public static final int EXTRA = 50;
    /** IDs (o autores) aleatorios de las medidas de lookup y de metadatos. */
    public static final int SAMPLES = 200;
    public static final long SEED = 42;
    /** Colección de MongoDB propia, para no tocar la del pipeline. */
    static final String BENCH_LANG = "java_bench";

    private static BenchEnv instance;

    final Path root;
    /** En disco, no en /tmp, que en muchas distribuciones está en RAM. */
    final Path work;
    final List<Integer> sizes = new ArrayList<>();
    final String mongoUri;
    final List<Integer> ids;
    final Set<String> stop;
    final List<String> queries = new ArrayList<>();
    private DatalakeStore source;

    private BenchEnv() throws IOException {
        root = Path.of(setting("root", "..")).toAbsolutePath().normalize();
        work = root.resolve("output").resolve("java_bench");
        mongoUri = setting("mongo", "mongodb://localhost:27017");
        for (String size : setting("sizes", "100,250,500,1000").split(",")) {
            sizes.add(Integer.parseInt(size.trim()));
        }
        Path shared = root.resolve("shared");
        ids = BookIds.read(shared.resolve("book_ids.txt"));
        stop = Tokenizer.loadStopwords(shared.resolve("stopwords.txt"));
        for (String line : Files.readString(shared.resolve("queries.txt"), StandardCharsets.UTF_8).split("\n", -1)) {
            String query = Text.trimSpace(line);
            if (!query.isEmpty()) {
                queries.add(query);
            }
        }
    }

    static String setting(String name, String fallback) {
        String value = System.getProperty("stage1." + name);
        if (value == null || value.isEmpty()) {
            value = System.getenv("STAGE1_" + name.toUpperCase());
        }
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** Una por JVM: cada fork de JMH lee la configuración una vez. */
    public static synchronized BenchEnv get() throws IOException {
        if (instance == null) {
            instance = new BenchEnv();
        }
        return instance;
    }

    /** Libros que hacen falta en cache/: el mayor N más los que se añaden después. */
    int maxBooks() {
        return sizes.stream().mapToInt(Integer::intValue).max().orElse(0) + EXTRA;
    }

    /** Los n primeros IDs del dataset, comprobando que están en cache/. */
    List<Integer> books(int n) throws IOException {
        if (n > ids.size()) {
            throw new IOException("hacen falta " + n + " libros y shared/book_ids.txt solo tiene " + ids.size());
        }
        for (int id : ids.subList(0, n)) {
            if (!Files.exists(root.resolve("cache").resolve(id + ".txt"))) {
                throw new IOException("el libro " + id + " no está en cache/");
            }
        }
        return ids.subList(0, n);
    }

    /**
     * Guarda ids en el datalake kind de out leyendo de cache/, como el pipeline con --offline
     * (incluida la línea de control).
     */
    ControlPipeline ingest(String kind, Path out, List<Integer> books) throws IOException {
        ControlPipeline pipeline = new ControlPipeline(DatalakeStore.create(kind, out),
                new ControlState(out.resolve("control")))
                .withSource(new CacheSource(root.resolve("cache")));
        Summary summary = new Summary();
        try {
            pipeline.download(books, summary);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        if (summary.discarded > 0) {
            throw new IOException(summary.discarded + " libros del dataset no tienen marcadores");
        }
        return pipeline;
    }

    /**
     * Datalake book con todos los libros del experimento. De él salen los bodies y las
     * cabeceras de las medidas del índice y de los metadatos.
     */
    synchronized DatalakeStore source() throws IOException {
        if (source == null) {
            source = ingest("book", work.resolve("source"), books(maxBooks())).store();
        }
        return source;
    }

    /** Leer y tokenizar los bodies: la parte de la indexación común a las tres variantes. */
    Map<String, List<Integer>> postings(DatalakeStore store, List<Integer> books) throws IOException {
        Map<String, List<Integer>> postings = new HashMap<>();
        for (int id : books) {
            BookLocation location = store.locate(id)
                    .orElseThrow(() -> new IOException("el libro " + id + " no está en el datalake"));
            for (String term : Tokenizer.terms(Files.readAllBytes(location.body()), stop)) {
                postings.computeIfAbsent(term, t -> new ArrayList<>()).add(id);
            }
        }
        return postings;
    }

    List<BookMetadata> records(DatalakeStore store, List<Integer> books) throws IOException {
        List<BookMetadata> records = new ArrayList<>(books.size());
        Path output = store.root().toAbsolutePath().getParent();
        for (int id : books) {
            BookLocation location = store.locate(id).orElseThrow();
            String header = Files.readString(location.header(), StandardCharsets.UTF_8);
            String bodyPath = output.relativize(location.body().toAbsolutePath()).toString().replace('\\', '/');
            records.add(MetadataExtractor.extract(id, header).withBodyPath(bodyPath));
        }
        return records;
    }

    InvertedIndex openIndex(String kind, Path datamarts) throws IOException {
        return InvertedIndex.create(kind, new InvertedIndex.Config(datamarts, mongoUri, BENCH_LANG));
    }

    /** SAMPLES elementos al azar, con repetición, con la semilla acordada. */
    static <T> List<T> sample(List<T> items) {
        Random random = new Random(SEED);
        List<T> sample = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            sample.add(items.get(random.nextInt(items.size())));
        }
        return sample;
    }

    /**
     * Deja el índice por carpetas como estaba antes de añadir {@code added}: cada término
     * afectado recupera sus postings de {@code base} o desaparece. Es el setup de
     * update_time; reconstruir el índice entero tarda minutos en folders.
     */
    static void undoFolders(Path root, Map<String, List<Integer>> base, Map<String, List<Integer>> added)
            throws IOException {
        for (String term : added.keySet()) {
            Path path = root.resolve(term.substring(0, 1).toUpperCase(Locale.ROOT)).resolve(term + ".txt");
            List<Integer> ids = base.get(term);
            if (ids == null) {
                Files.deleteIfExists(path);
            } else {
                StringBuilder content = new StringBuilder();
                for (int id : ids) {
                    content.append(id).append('\n');
                }
                Files.writeString(path, content, StandardCharsets.US_ASCII);
            }
        }
    }
}
