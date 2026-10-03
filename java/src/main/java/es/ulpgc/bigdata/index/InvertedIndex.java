package es.ulpgc.bigdata.index;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Índice invertido (término -&gt; libros). Las tres variantes guardan el mismo contenido
 * lógico; los postings son siempre IDs de libro únicos y en orden ascendente.
 */
public interface InvertedIndex extends AutoCloseable {

    String name();

    /** Fusiona un lote de postings con lo que ya hay. Repetir el mismo lote no cambia el resultado. */
    void add(Map<String, List<Integer>> postings) throws IOException;

    /** Libros de un término; lista vacía si no está. */
    List<Integer> lookup(String term) throws IOException;

    /** Recorre el índice entero en orden alfabético de término. */
    void forEach(TermVisitor visitor) throws IOException;

    boolean isEmpty() throws IOException;

    /** Bytes que ocupa el índice. */
    long diskUsage() throws IOException;

    /** Borra el índice para reconstruirlo desde cero. */
    void reset() throws IOException;

    @Override
    void close() throws IOException;

    @FunctionalInterface
    interface TermVisitor {
        void visit(String term, List<Integer> ids) throws IOException;
    }

    /** Lo que necesita cada variante para abrirse. */
    record Config(Path datamarts, String mongoUri, String lang) {
    }

    static InvertedIndex create(String kind, Config config) throws IOException {
        return switch (kind) {
            case "json" -> new JsonIndex(config.datamarts().resolve("inverted_index.json"));
            case "folders" -> new FolderIndex(config.datamarts().resolve("inverted_index"));
            case "mongo" -> new MongoIndex(config.mongoUri(), config.lang());
            default -> throw new IllegalArgumentException(
                    "índice desconocido \"" + kind + "\" (json, folders o mongo)");
        };
    }
}
