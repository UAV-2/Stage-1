package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.ingestion.BookParts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/** datalake_batch/000000-000999/<id>.header.txt y <id>.body.txt */
public class BatchDatalake extends FileDatalake {

    public static final int DEFAULT_BATCH_SIZE = 1000;

    private final int batchSize;

    public BatchDatalake(Path root) {
        this(root, DEFAULT_BATCH_SIZE);
    }

    public BatchDatalake(Path root, int batchSize) {
        super(root);
        this.batchSize = batchSize;
    }

    @Override
    public String name() {
        return "batch";
    }

    String batchFolder(String bookId) {
        long id = Long.parseLong(bookId);
        long start = (id / batchSize) * batchSize;
        return String.format("%06d-%06d", start, start + batchSize - 1);
    }

    private BookLocation pathsFor(String bookId) {
        Path dir = root.resolve(batchFolder(bookId));
        return new BookLocation(bookId, dir.resolve(bookId + HEADER_SUFFIX), dir.resolve(bookId + BODY_SUFFIX));
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
        return scanFlatFiles(2);
    }
}
