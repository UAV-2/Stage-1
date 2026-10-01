package es.ulpgc.bigdata.control;

import es.ulpgc.bigdata.util.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Lectura de la lista común de IDs (shared/book_ids.txt). */
public final class BookIds {

    private BookIds() {
    }

    /**
     * Lee una lista de IDs (uno por línea) respetando el orden del fichero. Las líneas
     * vacías y las que empiezan por "#" se ignoran.
     */
    public static List<Integer> read(Path path) throws IOException {
        String[] lines = Files.readString(path, StandardCharsets.UTF_8).split("\n", -1);
        List<Integer> ids = new ArrayList<>();
        for (int n = 0; n < lines.length; n++) {
            String line = Text.trimSpace(lines[n]);
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                ids.add(Integer.parseInt(line));
            } catch (NumberFormatException e) {
                throw new IOException(path + ":" + (n + 1) + ": \"" + line + "\" no es un ID de libro");
            }
        }
        return ids;
    }
}
