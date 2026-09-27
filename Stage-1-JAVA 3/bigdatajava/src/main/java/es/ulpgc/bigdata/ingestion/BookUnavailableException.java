package es.ulpgc.bigdata.ingestion;

import java.io.IOException;

/** El libro no existe o no tiene los marcadores: no merece la pena reintentarlo. */
public class BookUnavailableException extends IOException {
    private static final long serialVersionUID = 1L;

    public BookUnavailableException(String message) {
        super(message);
    }
}
