package es.ulpgc.bigdata.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Operaciones de fichero que comparten el datalake, los datamarts y la capa de control. */
public final class FileUtils {

    private FileUtils() {
    }

    /**
     * Escribe en "&lt;path&gt;.tmp" y renombra al final: si el proceso se corta a mitad,
     * nunca queda un fichero final a medias.
     */
    public static void atomicWrite(Path path, byte[] data) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, data);
        replace(tmp, path);
    }

    private static final int RENAME_ATTEMPTS = 10;
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    /**
     * Renombra source a target sustituyéndolo. En Windows, el antivirus o el indexador pueden
     * tener abierto el destino un instante y el renombrado falla con "acceso denegado": se
     * reintenta unas cuantas veces antes de dar el error.
     */
    public static void replace(Path source, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                if (!WINDOWS || attempt == RENAME_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(10L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    public static void atomicWrite(Path path, String data) throws IOException {
        atomicWrite(path, data.getBytes(StandardCharsets.UTF_8));
    }

    /** Añade una línea al final de un fichero de registro, creándolo si no existe. */
    public static void appendLine(Path path, String line) throws IOException {
        Files.writeString(path, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    /**
     * Devuelve las líneas no vacías de un fichero de registro; lista vacía si no existe.
     * Una última línea sin "\n" es un append interrumpido: se descarta y se recorta el
     * fichero, para que el siguiente append no se pegue a ella.
     */
    public static List<String> loadLines(Path path) throws IOException {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            return new ArrayList<>();
        }

        int end = data.length;
        while (end > 0 && data[end - 1] != '\n') {
            end--;
        }
        if (end < data.length) {
            try (var channel = Files.newByteChannel(path, StandardOpenOption.WRITE)) {
                channel.truncate(end);
            }
        }

        List<String> lines = new ArrayList<>();
        for (String line : new String(data, 0, end, StandardCharsets.UTF_8).split("\n", -1)) {
            String trimmed = Text.trimSpace(line);
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /** Borra los ".tmp" que haya dejado una escritura interrumpida y devuelve cuántos ha borrado. */
    public static int removeTemp(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0;
        }
        List<Path> temps;
        try (Stream<Path> files = Files.walk(root)) {
            temps = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".tmp"))
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        for (Path temp : temps) {
            Files.delete(temp);
        }
        return temps.size();
    }

    /** Borra una carpeta con todo su contenido; no hace nada si no existe. */
    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }

    /** Entradas de una carpeta ordenadas por nombre; lista vacía si no existe. */
    public static List<Path> sortedEntries(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public static boolean isFile(Path path) {
        return Files.isRegularFile(path);
    }
}
