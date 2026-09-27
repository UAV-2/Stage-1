package es.ulpgc.bigdata.control;

import es.ulpgc.bigdata.util.FileUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Estado del pipeline guardado en ficheros de texto (un BOOK_ID por línea). */
public class ControlState {

    private final Path downloadedFile;
    private final Path indexedFile;
    private final Path failedFile;

    private final Set<String> downloaded;
    private final Set<String> indexed;
    private final Set<String> failed;

    public ControlState(Path controlDir) throws IOException {
        Files.createDirectories(controlDir);
        this.downloadedFile = controlDir.resolve("downloaded_books.txt");
        this.indexedFile = controlDir.resolve("indexed_books.txt");
        this.failedFile = controlDir.resolve("failed_books.txt");
        this.downloaded = load(downloadedFile);
        this.indexed = load(indexedFile);
        this.failed = load(failedFile);
    }

    private static Set<String> load(Path file) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String id = line.strip();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static void append(Path file, String id) throws IOException {
        Files.writeString(file, id + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void rewrite(Path file, Set<String> ids) throws IOException {
        StringBuilder sb = new StringBuilder();
        ids.forEach(id -> sb.append(id).append('\n'));
        FileUtils.atomicWrite(file, sb.toString());
    }

    public void markDownloaded(String id) throws IOException {
        if (downloaded.add(id)) {
            append(downloadedFile, id);
        }
    }

    public void markIndexed(String id) throws IOException {
        if (indexed.add(id)) {
            append(indexedFile, id);
        }
    }

    public void markFailed(String id) throws IOException {
        if (failed.add(id)) {
            append(failedFile, id);
        }
    }

    /** Descargados pero todavía no indexados, en orden de descarga. */
    public List<String> pendingToIndex() {
        return downloaded.stream().filter(id -> !indexed.contains(id)).toList();
    }

    /** Ya descargado o descartado: no hay que volver a pedirlo. */
    public boolean isKnown(String id) {
        return downloaded.contains(id) || failed.contains(id);
    }

    /**
     * Alinea los ficheros de control con lo que hay realmente en el datalake.
     * Cubre los dos casos de interrupción: libro escrito sin registrar
     * y registro sin libro (p. ej. una línea cortada a medias).
     */
    public RecoveryReport reconcile(Set<String> inDatalake) throws IOException {
        Set<String> registeredMissing = new LinkedHashSet<>(downloaded);
        registeredMissing.removeAll(inDatalake);

        Set<String> unregistered = new LinkedHashSet<>(inDatalake);
        unregistered.removeAll(downloaded);

        Set<String> orphanIndexed = new LinkedHashSet<>(indexed);
        orphanIndexed.removeAll(inDatalake);

        if (!registeredMissing.isEmpty() || !unregistered.isEmpty()) {
            downloaded.removeAll(registeredMissing);
            downloaded.addAll(unregistered);
            rewrite(downloadedFile, downloaded);
        }
        if (!orphanIndexed.isEmpty()) {
            indexed.removeAll(orphanIndexed);
            rewrite(indexedFile, indexed);
        }
        return new RecoveryReport(unregistered, registeredMissing, orphanIndexed);
    }

    public Set<String> downloaded() {
        return Collections.unmodifiableSet(downloaded);
    }

    public Set<String> indexed() {
        return Collections.unmodifiableSet(indexed);
    }

    public Set<String> failed() {
        return Collections.unmodifiableSet(failed);
    }
}
