package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * datalake_range/&lt;inicio&gt;-&lt;fin&gt;/&lt;id&gt;.header.txt y &lt;id&gt;.body.txt,
 * con rangos de 1000 (0-999, 1000-1999, ...).
 */
public class RangeDatalake implements DatalakeStore {

    static final int RANGE_SIZE = 1000;
    private static final String BODY_SUFFIX = ".body.txt";

    private final Path root;

    public RangeDatalake(Path root) {
        this.root = root;
    }

    @Override
    public String name() {
        return "range";
    }

    @Override
    public Path root() {
        return root;
    }

    static String rangeFolder(int id) {
        int start = id / RANGE_SIZE * RANGE_SIZE;
        return start + "-" + (start + RANGE_SIZE - 1);
    }

    private BookLocation location(int id) {
        return BookLocation.flat(root.resolve(rangeFolder(id)), id);
    }

    @Override
    public BookLocation save(int id, String header, String body) throws IOException {
        BookLocation location = location(id);
        DatalakeStore.write(location, header, body);
        return location;
    }

    @Override
    public Optional<BookLocation> locate(int id) {
        BookLocation location = location(id);
        return DatalakeStore.complete(location) ? Optional.of(location) : Optional.empty();
    }

    @Override
    public List<Integer> list() throws IOException {
        List<Integer> ids = new ArrayList<>();
        for (Path folder : FileUtils.sortedEntries(root)) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            for (Path file : FileUtils.sortedEntries(folder)) {
                String name = file.getFileName().toString();
                if (!name.endsWith(BODY_SUFFIX)) {
                    continue;
                }
                DatalakeStore.parseId(name.substring(0, name.length() - BODY_SUFFIX.length()))
                        .filter(id -> locate(id).isPresent())
                        .ifPresent(ids::add);
            }
        }
        ids.sort(null);
        return ids;
    }
}
