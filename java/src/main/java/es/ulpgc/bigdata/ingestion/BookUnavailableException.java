package es.ulpgc.bigdata.ingestion;

import java.io.IOException;

/**
 * Marca los libros que no se pueden ingerir (no existen o no tienen marcadores).
 * Se descartan: reintentarlos daría el mismo resultado.
 */
public class BookUnavailableException extends IOException {

    private static final long serialVersionUID = 1L;

    public BookUnavailableException(String message) {
        super("libro no disponible: " + message);
    }
}
