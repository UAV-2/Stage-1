package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.ingestion.BookParts;
import es.ulpgc.bigdata.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Lógica compartida por las implementaciones basadas en ficheros. */
public abstract class FileDatalake implements DatalakeStore {

    protected static final String BODY_SUFFIX = ".body.txt";
    protected static final String HEADER_SUFFIX = ".header.txt";

    protected final Path root;

    protected FileDatalake(Path root) {
        this.root = root;
    }

    @Override
    public Path root() {
        return root;
    }

    /** El body se escribe al final: su existencia marca el libro como completo. */
    protected BookLocation write(BookLocation location, BookParts book) throws IOException {
        Files.createDirectories(location.body().getParent());
        FileUtils.atomicWrite(location.header(), book.header());
        FileUtils.atomicWrite(location.body(), book.body());
        return location;
    }

    protected static Optional<BookLocation> ifComplete(BookLocation location) {
        boolean complete = Files.isRegularFile(location.body()) && Files.isRegularFile(location.header());
        return complete ? Optional.of(location) : Optional.empty();
    }

    /** Recorre el datalake buscando ficheros "<id>.body.txt" con su header al lado. */
    protected Set<String> scanFlatFiles(int depth) throws IOException {
        Set<String> ids = new TreeSet<>();
        if (!Files.isDirectory(root)) {
            return ids;
        }
        try (Stream<Path> files = Files.walk(root, depth)) {
            files.filter(p -> p.getFileName().toString().endsWith(BODY_SUFFIX))
                 .forEach(body -> {
                     String name = body.getFileName().toString();
                     String id = name.substring(0, name.length() - BODY_SUFFIX.length());
                     if (Files.isRegularFile(body.resolveSibling(id + HEADER_SUFFIX))) {
                         ids.add(id);
                     }
                 });
        }
        return ids;
    }

    @Override
    public int removeTempFiles() throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        List<Path> temps;
        try (Stream<Path> files = Files.walk(root)) {
            temps = files.filter(p -> p.toString().endsWith(".tmp")).toList();
        }
        for (Path tmp : temps) {
            Files.deleteIfExists(tmp);
        }
        return temps.size();
    }
}
