package es.ulpgc.bigdata.control;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/** Decide qué libro descargar a continuación. */
@FunctionalInterface
public interface CandidateProvider {

    Optional<String> next(ControlState state);

    /** Como el ejemplo del enunciado: hasta 10 intentos aleatorios. Con semilla es reproducible. */
    static CandidateProvider random(int totalBooks, long seed) {
        Random random = new Random(seed);
        return state -> {
            for (int i = 0; i < 10; i++) {
                String id = String.valueOf(random.nextInt(totalBooks) + 1);
                if (!state.isKnown(id)) {
                    return Optional.of(id);
                }
            }
            return Optional.empty();
        };
    }

    /** Recorre una lista fija de IDs; es lo que permite comparar lenguajes con el mismo dataset. */
    static CandidateProvider fromList(List<String> ids) {
        return state -> ids.stream().filter(id -> !state.isKnown(id)).findFirst();
    }

    static CandidateProvider fromFile(Path file) throws IOException {
        List<String> ids = Files.readAllLines(file).stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();
        return fromList(ids);
    }
}
