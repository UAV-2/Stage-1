package es.ulpgc.bigdata.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class FileUtils {

    private FileUtils() {
    }

    /**
     * Escritura atómica: primero se escribe en "<path>.tmp" y después se renombra.
     * Si el proceso se interrumpe a mitad, nunca queda un fichero final a medias
     * (equivalente a atomicWrite de la versión Go).
     */
    public static void atomicWrite(Path path, String data) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, data, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
