package es.ulpgc.bigdata.datalake;

import es.ulpgc.bigdata.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Contrato común a las tres variantes del datalake. */
public interface DatalakeStore {

    String name();

    Path root();

    /** Escribe el libro; repetirlo con el mismo ID lo sobrescribe en su sitio, nunca lo duplica. */
    BookLocation save(int id, String header, String body) throws IOException;

    /** Vacío si falta alguna de las dos mitades. */
    Optional<BookLocation> locate(int id);

    /** IDs de los libros completos, en orden ascendente. */
    List<Integer> list() throws IOException;

    /** Crea la variante pedida dentro de outputDir (output/java). */
    static DatalakeStore create(String kind, Path outputDir) throws IOException {
        Path root = outputDir.resolve("datalake_" + kind);
        return switch (kind) {
            case "time" -> new TimeBasedDatalake(root);
            case "book" -> new BookBasedDatalake(root);
            case "range" -> new RangeDatalake(root);
            default -> throw new IllegalArgumentException(
                    "datalake desconocido \"" + kind + "\" (time, book o range)");
        };
    }

    /** Deja el body para el final: su existencia marca el libro como completo. */
    static void write(BookLocation location, String header, String body) throws IOException {
        Files.createDirectories(location.body().getParent());
        FileUtils.atomicWrite(location.header(), header);
        FileUtils.atomicWrite(location.body(), body);
    }

    static boolean complete(BookLocation location) {
        return FileUtils.isFile(location.header()) && FileUtils.isFile(location.body());
    }

    /** Integer.parseInt sin excepción: vacío si el nombre no es un ID. */
    static Optional<Integer> parseId(String text) {
        try {
            return Optional.of(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
