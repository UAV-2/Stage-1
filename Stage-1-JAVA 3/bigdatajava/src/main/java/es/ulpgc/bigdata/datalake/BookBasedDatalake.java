package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.util.FileUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** datalake_book/&lt;id&gt;/header.txt y body.txt */
public class BookBasedDatalake implements DatalakeStore {

    private final Path root;

    public BookBasedDatalake(Path root) {
        this.root = root;
    }

    @Override
    public String name() {
        return "book";
    }

    @Override
    public Path root() {
        return root;
    }

    private BookLocation location(int id) {
        Path dir = root.resolve(String.valueOf(id));
        return new BookLocation(dir.resolve("header.txt"), dir.resolve("body.txt"));
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
        for (Path entry : FileUtils.sortedEntries(root)) {
            DatalakeStore.parseId(entry.getFileName().toString())
                    .filter(id -> locate(id).isPresent())
                    .ifPresent(ids::add);
        }
        ids.sort(null);
        return ids;
    }
}
