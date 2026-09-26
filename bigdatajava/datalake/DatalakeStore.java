package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.ingestion.BookParts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/** Contrato común a las tres organizaciones del datalake. */
public interface DatalakeStore {

    String name();

    Path root();

    BookLocation save(BookParts book) throws IOException;

    Optional<BookLocation> locate(String bookId) throws IOException;

    /** IDs de los libros completos (header y body presentes). */
    Set<String> listBookIds() throws IOException;

    /** Borra ficheros .tmp que hayan quedado de una escritura interrumpida. */
    int removeTempFiles() throws IOException;

    default boolean contains(String bookId) throws IOException {
        return locate(bookId).isPresent();
    }
}
