package es.ulpgc.bigdata.metadata;

/**
 * Una fila de la tabla books. Una cadena vacía significa que el dato no está en la
 * cabecera y se guarda como NULL.
 */
public record BookMetadata(int id, String title, String author, String language, String bodyPath) {

    public BookMetadata withBodyPath(String path) {
        return new BookMetadata(id, title, author, language, path);
    }
}
