package es.ulpgc.bigdata.control;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Estado del pipeline guardado en output/java/control: qué libros están descargados,
 * indexados o descartados. Las marcas se escriben siempre después de que la operación
 * haya terminado.
 */
public class ControlState {

    private final Path dir;
    private final BookList downloaded;
    private final BookList indexed;
    private final BookList failed;

    public ControlState(Path dir) throws IOException {
        Files.createDirectories(dir);
        this.dir = dir;
        this.downloaded = new BookList(dir.resolve("downloaded_books.txt"));
        this.indexed = new BookList(dir.resolve("indexed_books.txt"));
        this.failed = new BookList(dir.resolve("failed_books.txt"));
    }

    public Path dir() {
        return dir;
    }

    public void markDownloaded(int id) throws IOException {
        downloaded.add(id);
    }

    public void markIndexed(int id) throws IOException {
        indexed.add(id);
    }

    public void markFailed(int id) throws IOException {
        failed.add(id);
    }

    public List<Integer> downloaded() {
        return downloaded.ids();
    }

    public List<Integer> indexed() {
        return indexed.ids();
    }

    public List<Integer> failed() {
        return failed.ids();
    }

    /** Descargados pero todavía no indexados, en orden de descarga. */
    public List<Integer> pending() {
        List<Integer> pending = new ArrayList<>();
        for (int id : downloaded.ids()) {
            if (!indexed.contains(id)) {
                pending.add(id);
            }
        }
        return pending;
    }

    /** Ya descargado o descartado: no hay que volver a pedirlo. */
    public boolean isKnown(int id) {
        return downloaded.contains(id) || failed.contains(id);
    }

    /** Olvida qué libros están indexados: todos los descargados vuelven a quedar pendientes. */
    public void clearIndexed() throws IOException {
        indexed.replace(List.of());
    }

    /**
     * Alinea los ficheros de control con lo que hay realmente en el datalake. Cubre los dos
     * cortes posibles: libro escrito sin registrar y registro sin libro.
     */
    public RecoveryReport reconcile(List<Integer> inDatalake) throws IOException {
        Set<Integer> present = new HashSet<>(inDatalake);
        RecoveryReport report = new RecoveryReport();

        List<Integer> keptDownloaded = new ArrayList<>();
        for (int id : downloaded.ids()) {
            if (present.contains(id)) {
                keptDownloaded.add(id);
            } else {
                report.missing.add(id);
            }
        }
        for (int id : inDatalake) {
            if (!downloaded.contains(id)) {
                keptDownloaded.add(id);
                report.unregistered.add(id);
            }
        }
        List<Integer> keptIndexed = new ArrayList<>();
        for (int id : indexed.ids()) {
            if (present.contains(id)) {
                keptIndexed.add(id);
            } else {
                report.orphanIndexed.add(id);
            }
        }

        if (!report.missing.isEmpty() || !report.unregistered.isEmpty()) {
            downloaded.replace(keptDownloaded);
        }
        if (!report.orphanIndexed.isEmpty()) {
            indexed.replace(keptIndexed);
        }
        return report;
    }
}
