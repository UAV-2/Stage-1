package es.ulpgc.bigdata.indexing;

import es.ulpgc.bigdata.datalake.BookLocation;

import java.io.IOException;

/** Punto de enganche para el indexador (se implementa en la parte del índice invertido). */
@FunctionalInterface
public interface BookIndexer {

    void index(BookLocation book) throws IOException;

    static BookIndexer noOp() {
        return book -> { };
    }
}
