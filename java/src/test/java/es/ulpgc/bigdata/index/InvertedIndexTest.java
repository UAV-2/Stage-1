package es.ulpgc.bigdata.index;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvertedIndexTest {

    /** La variante de MongoDB solo se prueba si MONGO_URI apunta a un servidor. */
    private static InvertedIndex open(String kind, Path dir) throws IOException {
        String uri = System.getenv("MONGO_URI");
        if (kind.equals("mongo")) {
            Assumptions.assumeTrue(uri != null && !uri.isEmpty(), "MONGO_URI no está definida");
        }
        return InvertedIndex.create(kind, new InvertedIndex.Config(dir, uri, "test"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "folders", "mongo"})
    void contenidoComun(String kind, @TempDir Path dir) throws IOException {
        try (InvertedIndex idx = open(kind, dir)) {
            idx.reset();
            assertTrue(idx.isEmpty());
            // Dos lotes con IDs desordenados y un libro repetido.
            idx.add(Map.of("island", List.of(1342, 5), "adventure", List.of(42, 5)));
            idx.add(Map.of("adventure", List.of(12, 5), "whale", List.of(2701)));
        }

        // Se reabre para comprobar que todo está en disco.
        try (InvertedIndex idx = open(kind, dir)) {
            assertEquals(List.of(5, 12, 42), idx.lookup("adventure"));
            assertEquals(List.of(5, 1342), idx.lookup("island"));
            assertEquals(List.of(2701), idx.lookup("whale"));
            assertEquals(List.of(), idx.lookup("missing"));

            Set<String> stop = Set.of("the");
            assertEquals(List.of(5, 12, 42), Postings.search(idx, "Adventure", stop));
            assertEquals(List.of(5), Postings.search(idx, "the island ADVENTURE", stop));
            assertEquals(List.of(), Postings.search(idx, "whale island", stop));
            assertEquals(List.of(), Postings.search(idx, "island missing", stop));
            assertEquals(List.of(), Postings.search(idx, "the", stop));
            assertTrue(idx.diskUsage() > 0);

            // Todas las variantes dan el mismo dump canónico.
            Path dump = dir.resolve("index.tsv");
            Postings.dumpTsv(idx, dump);
            assertEquals("adventure\t5,12,42\nisland\t5,1342\nwhale\t2701\n", Files.readString(dump));

            idx.reset();
            assertTrue(idx.isEmpty());
        }
    }

    @Test
    void formatoJson(@TempDir Path dir) throws IOException {
        try (InvertedIndex idx = open("json", dir)) {
            idx.add(Map.of("island", List.of(1342, 5), "adventure", List.of(42, 12, 5)));
        }
        assertEquals("{\"adventure\":[5,12,42],\"island\":[5,1342]}",
                Files.readString(dir.resolve("inverted_index.json")));
    }

    @Test
    void leeJsonConEspacios(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("inverted_index.json"), "{\n  \"island\": [5, 1342],\n  \"sea\": []\n}");
        try (InvertedIndex idx = open("json", dir)) {
            assertEquals(List.of(5, 1342), idx.lookup("island"));
            assertEquals(List.of(), idx.lookup("sea"));
        }
    }

    @Test
    void formatoCarpetas(@TempDir Path dir) throws IOException {
        try (InvertedIndex idx = open("folders", dir)) {
            idx.add(Map.of("island", List.of(1342, 5), "adventure", List.of(42, 12, 5),
                    "abc", List.of(1), "abcd", List.of(2)));
            assertEquals("5\n12\n42\n", Files.readString(dir.resolve("inverted_index/A/adventure.txt")));
            assertEquals("5\n1342\n", Files.readString(dir.resolve("inverted_index/I/island.txt")));

            List<String> terms = new ArrayList<>();
            idx.forEach((term, ids) -> terms.add(term));
            assertEquals(List.of("abc", "abcd", "adventure", "island"), terms);
        }
    }

    @Test
    void varianteDesconocida(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> InvertedIndex.create("sqlite", new InvertedIndex.Config(dir, null, "test")));
    }
}
