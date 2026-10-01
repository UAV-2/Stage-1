package es.ulpgc.bigdata.ingestion;

import java.io.IOException;

/** Entrega el texto crudo de un libro. */
@FunctionalInterface
public interface BookSource {

    String fetch(int id) throws IOException, InterruptedException;
}
