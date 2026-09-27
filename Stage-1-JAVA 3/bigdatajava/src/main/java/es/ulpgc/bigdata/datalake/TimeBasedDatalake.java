package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.ingestion.BookParts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** datalake_time/YYYYMMDD/HH/<id>.header.txt y <id>.body.txt */
public class TimeBasedDatalake extends FileDatalake {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH");

    private final Clock clock;

    public TimeBasedDatalake(Path root) {
        this(root, Clock.systemDefaultZone());
    }

    public TimeBasedDatalake(Path root, Clock clock) {
        super(root);
        this.clock = clock;
    }

    @Override
    public String name() {
        return "time";
    }

    @Override
    public BookLocation save(BookParts book) throws IOException {
        LocalDateTime now = LocalDateTime.now(clock);
        Path dir = root.resolve(DAY.format(now)).resolve(HOUR.format(now));
        String id = book.bookId();
        return write(new BookLocation(id, dir.resolve(id + HEADER_SUFFIX), dir.resolve(id + BODY_SUFFIX)), book);
    }

    /** No se sabe cuándo se descargó el libro, así que hay que recorrer las carpetas. */
    @Override
    public Optional<BookLocation> locate(String bookId) throws IOException {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        String bodyName = bookId + BODY_SUFFIX;
        try (Stream<Path> files = Files.walk(root, 3)) {
            return files.filter(p -> p.getFileName().toString().equals(bodyName))
                        .findFirst()
                        .flatMap(body -> ifComplete(new BookLocation(bookId,
                                body.resolveSibling(bookId + HEADER_SUFFIX), body)));
        }
    }

    @Override
    public Set<String> listBookIds() throws IOException {
        return scanFlatFiles(3);
    }
}
