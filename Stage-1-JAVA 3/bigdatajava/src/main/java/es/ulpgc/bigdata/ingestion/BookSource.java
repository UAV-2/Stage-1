package es.ulpgc.bigdata.ingestion;

import java.io.IOException;

/** Origen de libros ya separados en header y body. */
@FunctionalInterface
public interface BookSource {
    BookParts fetch(String bookId) throws IOException, InterruptedException;
}
