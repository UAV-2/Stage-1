package es.ulpgc.bigdata.control;

import es.ulpgc.bigdata.datalake.BookLocation;
import es.ulpgc.bigdata.datalake.DatalakeStore;
import es.ulpgc.bigdata.indexing.BookIndexer;
import es.ulpgc.bigdata.ingestion.BookSource;
import es.ulpgc.bigdata.ingestion.BookUnavailableException;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Coordina descarga e indexación siguiendo la lógica del enunciado (sección 5.2). */
public class ControlPipeline {

    private final DatalakeStore store;
    private final ControlState state;
    private final BookSource source;
    private final BookIndexer indexer;
    private final CandidateProvider candidates;

    public ControlPipeline(DatalakeStore store, ControlState state, BookSource source,
                           BookIndexer indexer, CandidateProvider candidates) {
        this.store = store;
        this.state = state;
        this.source = source;
        this.indexer = indexer;
        this.candidates = candidates;
    }

    /** Se ejecuta al arrancar para dejar el estado coherente tras una interrupción. */
    public RecoveryReport recover() throws IOException {
        int temps = store.removeTempFiles();
        RecoveryReport report = state.reconcile(store.listBookIds());
        if (temps > 0 || !report.isClean()) {
            log("Recuperación: %d .tmp borrados, %s", temps, report);
        }
        return report;
    }

    public StepResult step() throws InterruptedException {
        List<String> pending = state.pendingToIndex();
        return pending.isEmpty() ? downloadNext() : index(pending.get(0));
    }

    private StepResult index(String id) {
        try {
            Optional<BookLocation> location = store.locate(id);
            if (location.isEmpty()) {
                log("Libro %s registrado pero no está en el datalake", id);
                return StepResult.ERROR;
            }
            indexer.index(location.get());
            state.markIndexed(id);
            log("Libro %s indexado", id);
            return StepResult.INDEXED;
        } catch (IOException e) {
            log("Error indexando %s: %s", id, e.getMessage());
            return StepResult.ERROR;
        }
    }

    private StepResult downloadNext() throws InterruptedException {
        Optional<String> candidate = candidates.next(state);
        if (candidate.isEmpty()) {
            return StepResult.IDLE;
        }
        String id = candidate.get();
        try {
            store.save(source.fetch(id));
            state.markDownloaded(id);
            log("Libro %s descargado", id);
            return StepResult.DOWNLOADED;
        } catch (BookUnavailableException e) {
            return discard(id, e.getMessage());
        } catch (IOException e) {
            log("Error descargando %s (se reintentará): %s", id, e.getMessage());
            return StepResult.ERROR;
        }
    }

    private StepResult discard(String id, String reason) {
        try {
            state.markFailed(id);
        } catch (IOException e) {
            log("No se pudo registrar el descarte de %s: %s", id, e.getMessage());
        }
        log("Libro %s descartado: %s", id, reason);
        return StepResult.UNAVAILABLE;
    }

    /** Ejecuta pasos hasta maxSteps o hasta que no quede trabajo. */
    public Map<StepResult, Integer> run(int maxSteps) throws InterruptedException {
        Map<StepResult, Integer> counts = new EnumMap<>(StepResult.class);
        for (int i = 0; i < maxSteps; i++) {
            StepResult result = step();
            counts.merge(result, 1, Integer::sum);
            if (result == StepResult.IDLE) {
                break;
            }
        }
        return counts;
    }

    private void log(String format, Object... args) {
        System.out.printf("[CONTROL:%s] %s%n", store.name(), String.format(format, args));
    }
}
