package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.ingestion.BookParts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/** datalake_book/<id>/header.txt y body.txt (misma estructura que el prototipo Go). */
public class BookBasedDatalake extends FileDatalake {

    public BookBasedDatalake(Path root) {
        super(root);
    }

    @Override
    public String name() {
        return "book";
    }

    private BookLocation pathsFor(String bookId) {
        Path dir = root.resolve(bookId);
        return new BookLocation(bookId, dir.resolve("header.txt"), dir.resolve("body.txt"));
    }

    @Override
    public BookLocation save(BookParts book) throws IOException {
        return write(pathsFor(book.bookId()), book);
    }

    @Override
    public Optional<BookLocation> locate(String bookId) {
        return ifComplete(pathsFor(bookId));
    }

    @Override
    public Set<String> listBookIds() throws IOException {
        Set<String> ids = new TreeSet<>();
        if (!Files.isDirectory(root)) {
            return ids;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                .map(d -> d.getFileName().toString())
                .filter(id -> locate(id).isPresent())
                .forEach(ids::add);
        }
        return ids;
    }
}
