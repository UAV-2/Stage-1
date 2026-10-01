package es.ulpgc.bigdata.control;

import es.ulpgc.bigdata.datalake.BookLocation;
import es.ulpgc.bigdata.datalake.DatalakeStore;
import es.ulpgc.bigdata.index.InvertedIndex;
import es.ulpgc.bigdata.ingestion.Book;
import es.ulpgc.bigdata.ingestion.BookSource;
import es.ulpgc.bigdata.ingestion.BookSplitter;
import es.ulpgc.bigdata.ingestion.BookUnavailableException;
import es.ulpgc.bigdata.metadata.BookMetadata;
import es.ulpgc.bigdata.metadata.MetadataDatabase;
import es.ulpgc.bigdata.metadata.MetadataExtractor;
import es.ulpgc.bigdata.tokenizer.Tokenizer;
import es.ulpgc.bigdata.util.FileUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Coordina la ingesta y la indexación sobre una variante del datalake y una del índice.
 * El source solo hace falta para descargar; metadatos, índice y stopwords, solo para indexar.
 */
public class ControlPipeline {

    private final DatalakeStore store;
    private final ControlState state;
    private BookSource source;

    private MetadataDatabase metadata;
    private InvertedIndex index;
    private Set<String> stopwords = Set.of();
    private Path datamarts; // carpeta de los datamarts, para limpiar sus .tmp

    private Consumer<String> log = message -> { };

    public ControlPipeline(DatalakeStore store, ControlState state) {
        this.store = store;
        this.state = state;
    }

    public DatalakeStore store() {
        return store;
    }

    public ControlState state() {
        return state;
    }

    public InvertedIndex index() {
        return index;
    }

    public ControlPipeline withSource(BookSource source) {
        this.source = source;
        return this;
    }

    public ControlPipeline withDatamarts(MetadataDatabase metadata, InvertedIndex index,
                                         Set<String> stopwords, Path datamarts) {
        this.metadata = metadata;
        this.index = index;
        this.stopwords = stopwords == null ? Set.of() : stopwords;
        this.datamarts = datamarts;
        return this;
    }

    /** Recibe una línea por cada paso. */
    public ControlPipeline withLog(Consumer<String> log) {
        this.log = log;
        return this;
    }

    private void log(String format, Object... args) {
        log.accept(String.format(format, args));
    }

    /** Se ejecuta al arrancar para dejar el estado coherente tras una interrupción. */
    public RecoveryReport recover() throws IOException {
        List<Path> dirs = new ArrayList<>(List.of(store.root(), state.dir()));
        if (datamarts != null) {
            dirs.add(datamarts);
        }
        int temps = 0;
        for (Path dir : dirs) {
            temps += FileUtils.removeTemp(dir);
        }

        RecoveryReport report = state.reconcile(store.list());
        report.tempFiles = temps;
        if (index == null || state.indexed().isEmpty()) {
            return report;
        }

        // El control es único para las tres variantes del índice: si la que se va a usar
        // está vacía, los libros marcados se indexaron en otra.
        if (metadata.isEmpty() || index.isEmpty()) {
            report.reindex = state.indexed().size();
            state.clearIndexed();
        }
        return report;
    }

    /**
     * Ingiere, en el orden de ids, los libros que todavía no se conocen. Un libro no
     * disponible se descarta y se sigue; cualquier otro error detiene la ejecución, que se
     * puede reanudar más tarde sin repetir trabajo.
     */
    public void download(List<Integer> ids, Summary summary) throws IOException, InterruptedException {
        for (int id : ids) {
            if (state.isKnown(id)) {
                summary.skipped++;
                continue;
            }
            try {
                ingest(id);
            } catch (BookUnavailableException e) {
                state.markFailed(id);
                summary.discarded++;
                log("Libro %d descartado: %s", id, e.getMessage());
                continue;
            }
            summary.downloaded++;
            log("Libro %d guardado en el datalake", id);
        }
    }

    private void ingest(int id) throws IOException, InterruptedException {
        Book book = BookSplitter.split(id, source.fetch(id));
        store.save(book.id(), book.header(), book.body());
        state.markDownloaded(id);
    }

    /**
     * Indexa los libros descargados que aún no están indexados, en lotes de batch libros
     * (batch &lt;= 0: todos de una vez).
     */
    public void indexPending(int batch, Summary summary) throws IOException {
        List<Integer> pending = state.pending();
        int size = batch <= 0 ? pending.size() : batch;
        for (int start = 0; start < pending.size(); start += size) {
            List<Integer> chunk = pending.subList(start, Math.min(start + size, pending.size()));
            indexBatch(chunk);
            summary.indexed += chunk.size();
        }
    }

    /**
     * Lleva un lote a los dos datamarts y solo después lo marca como indexado. Si se corta
     * antes, el lote se repite entero al reanudar; tanto los metadatos como el índice
     * admiten la repetición sin duplicar nada.
     */
    private void indexBatch(List<Integer> ids) throws IOException {
        List<BookMetadata> books = new ArrayList<>(ids.size());
        Map<String, List<Integer>> postings = new LinkedHashMap<>();
        Path outputDir = store.root().toAbsolutePath().getParent();

        for (int id : ids) {
            BookLocation location = store.locate(id).orElseThrow(() -> new IOException(
                    "el libro " + id + " está registrado pero no está en el datalake"));
            String header = Files.readString(location.header(), StandardCharsets.UTF_8);
            byte[] body = Files.readAllBytes(location.body());
            String bodyPath = outputDir.relativize(location.body().toAbsolutePath()).toString()
                    .replace('\\', '/');

            books.add(MetadataExtractor.extract(id, header).withBodyPath(bodyPath));
            for (String term : Tokenizer.terms(body, stopwords)) {
                postings.computeIfAbsent(term, t -> new ArrayList<>()).add(id);
            }
        }

        metadata.insert(books);
        index.add(postings);
        for (int id : ids) {
            state.markIndexed(id);
        }
        log("%d libros indexados (%d términos)", ids.size(), postings.size());
    }

    /** Vacía los datamarts y deja todos los libros descargados como pendientes de indexar. */
    public void rebuild() throws IOException {
        index.reset();
        metadata.reset();
        state.clearIndexed();
    }

    /**
     * Ciclo completo: primero indexa lo que quedó pendiente y después recorre ids en orden,
     * descargando los libros nuevos e indexándolos por lotes.
     */
    public void run(List<Integer> ids, int batch, Summary summary) throws IOException, InterruptedException {
        indexPending(batch, summary);
        int size = batch <= 0 ? ids.size() : batch;
        for (int start = 0; start < ids.size(); start += size) {
            download(ids.subList(start, Math.min(start + size, ids.size())), summary);
            indexPending(batch, summary);
        }
    }
}
