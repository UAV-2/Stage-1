package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * datalake_time/YYYYMMDD/HH/&lt;id&gt;.header.txt y &lt;id&gt;.body.txt
 * <p>
 * La ruta depende de cuándo se guardó el libro, así que se mantiene el índice auxiliar
 * _locations.tsv (id\tYYYYMMDD/HH) para poder localizarlo sin recorrer el árbol.
 */
public class TimeBasedDatalake implements DatalakeStore {

    static final String LOCATIONS_FILE = "_locations.tsv";
    private static final DateTimeFormatter FOLDER_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd/HH");

    private final Path root;
    private final Supplier<LocalDateTime> now;
    private final Map<Integer, String> locations = new HashMap<>();

    public TimeBasedDatalake(Path root) throws IOException {
        this(root, LocalDateTime::now);
    }

    TimeBasedDatalake(Path root, Supplier<LocalDateTime> now) throws IOException {
        this.root = root;
        this.now = now;
        for (String line : FileUtils.loadLines(root.resolve(LOCATIONS_FILE))) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String folder = line.substring(tab + 1);
            DatalakeStore.parseId(line.substring(0, tab)).ifPresent(id -> locations.put(id, folder));
        }
    }

    @Override
    public String name() {
        return "time";
    }

    @Override
    public Path root() {
        return root;
    }

    private BookLocation location(int id, String folder) {
        Path dir = root;
        for (String part : folder.split("/")) {
            dir = dir.resolve(part);
        }
        return BookLocation.flat(dir, id);
    }

    /**
     * Registra la carpeta antes de escribir los ficheros: si el proceso se corta a mitad,
     * al reanudar el libro vuelve a la misma carpeta aunque haya cambiado la hora, y no
     * queda duplicado en dos sitios.
     */
    @Override
    public BookLocation save(int id, String header, String body) throws IOException {
        String folder = locations.get(id);
        if (folder == null) {
            folder = now.get().format(FOLDER_FORMAT);
            Files.createDirectories(root);
            FileUtils.appendLine(root.resolve(LOCATIONS_FILE), id + "\t" + folder);
            locations.put(id, folder);
        }
        BookLocation location = location(id, folder);
        DatalakeStore.write(location, header, body);
        return location;
    }

    @Override
    public Optional<BookLocation> locate(int id) {
        String folder = locations.get(id);
        if (folder == null) {
            return Optional.empty();
        }
        BookLocation location = location(id, folder);
        return DatalakeStore.complete(location) ? Optional.of(location) : Optional.empty();
    }

    @Override
    public List<Integer> list() {
        List<Integer> ids = new ArrayList<>();
        for (int id : locations.keySet()) {
            if (locate(id).isPresent()) {
                ids.add(id);
            }
        }
        ids.sort(null);
        return ids;
    }
}
