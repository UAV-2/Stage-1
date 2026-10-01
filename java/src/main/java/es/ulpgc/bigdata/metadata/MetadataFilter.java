package es.ulpgc.bigdata.metadata;

/**
 * Selección de libros por coincidencia exacta. Un campo nulo o vacío no filtra; si hay
 * varios, se tienen que cumplir todos.
 */
public record MetadataFilter(String title, String author, String language) {

    public static final MetadataFilter NONE = new MetadataFilter(null, null, null);

    public boolean isEmpty() {
        return isBlank(title) && isBlank(author) && isBlank(language);
    }

    static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
