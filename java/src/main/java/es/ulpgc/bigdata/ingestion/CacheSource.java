package es.ulpgc.bigdata.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** Lee los .txt crudos de cache/ sin tocar la red (modo --offline). */
public class CacheSource implements BookSource {

    private final Path dir;

    public CacheSource(Path dir) {
        this.dir = dir;
    }

    static Path cachePath(Path dir, int id) {
        return dir.resolve(id + ".txt");
    }

    @Override
    public String fetch(int id) throws IOException {
        try {
            return new String(Files.readAllBytes(cachePath(dir, id)), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new IOException("el libro " + id + " no está en " + dir);
        }
    }
}
