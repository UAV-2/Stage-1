package es.ulpgc.bigdata.datalake;

import java.nio.file.Path;
import java.util.List;

public final class DatalakeFactory {

    public static final List<String> TYPES = List.of("time", "book", "batch");

    private DatalakeFactory() {
    }

    public static DatalakeStore create(String type, Path baseDir) {
        return switch (type) {
            case "time" -> new TimeBasedDatalake(baseDir.resolve("datalake_time"));
            case "book" -> new BookBasedDatalake(baseDir.resolve("datalake_book"));
            case "batch" -> new BatchDatalake(baseDir.resolve("datalake_batch"));
            default -> throw new IllegalArgumentException(
                    "Tipo de datalake desconocido: " + type + " (usa " + String.join(", ", TYPES) + ")");
        };
    }
}
