package es.ulpgc.bigdata.datalake;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatalakeTest {

    private static DatalakeStore open(String kind, Path out, LocalDateTime now) throws IOException {
        if (kind.equals("time") && now != null) {
            return new TimeBasedDatalake(out.resolve("datalake_time"), () -> now);
        }
        return DatalakeStore.create(kind, out);
    }

    private static long countFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "time, 1342, datalake_time/20260930/07/1342.header.txt, datalake_time/20260930/07/1342.body.txt",
            "book, 1342, datalake_book/1342/header.txt, datalake_book/1342/body.txt",
            "range, 1342, datalake_range/1000-1999/1342.header.txt, datalake_range/1000-1999/1342.body.txt",
            "range, 11, datalake_range/0-999/11.header.txt, datalake_range/0-999/11.body.txt",
            "range, 1000, datalake_range/1000-1999/1000.header.txt, datalake_range/1000-1999/1000.body.txt",
    })
    void rutas(String kind, int id, String header, String body, @TempDir Path out) throws IOException {
        DatalakeStore store = open(kind, out, LocalDateTime.of(2026, 9, 30, 7, 5));
        BookLocation location = store.save(id, "cabecera", "cuerpo");
        assertEquals(new BookLocation(out.resolve(header), out.resolve(body)), location);
        assertEquals("cabecera", Files.readString(location.header()));
        assertEquals("cuerpo", Files.readString(location.body()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"time", "book", "range"})
    void localizarYListar(String kind, @TempDir Path out) throws IOException {
        DatalakeStore store = DatalakeStore.create(kind, out);
        for (int id : List.of(1342, 11, 84)) {
            store.save(id, "h", "b");
        }
        // Se reabre para comprobar que no depende del estado en memoria.
        store = DatalakeStore.create(kind, out);
        assertTrue(store.locate(84).isPresent());
        assertTrue(store.locate(999).isEmpty());
        assertEquals(List.of(11, 84, 1342), store.list());
    }

    @ParameterizedTest
    @ValueSource(strings = {"time", "book", "range"})
    void libroIncompletoNoCuenta(String kind, @TempDir Path out) throws IOException {
        DatalakeStore store = DatalakeStore.create(kind, out);
        Files.delete(store.save(84, "h", "b").body());
        assertTrue(store.locate(84).isEmpty());
        assertEquals(List.of(), store.list());
    }

    /** Ni siquiera en time, cuando la segunda vez cae en otra hora. */
    @ParameterizedTest
    @ValueSource(strings = {"time", "book", "range"})
    void guardarDosVecesNoDuplica(String kind, @TempDir Path out) throws IOException {
        BookLocation first = DatalakeStore.create(kind, out).save(84, "h", "viejo");
        long before = countFiles(out);

        DatalakeStore store = open(kind, out, LocalDateTime.now().plusHours(3));
        BookLocation second = store.save(84, "h", "nuevo");

        assertEquals(first, second);
        assertEquals(before, countFiles(out));
        assertEquals("nuevo", Files.readString(second.body()));
    }

    @Test
    void varianteDesconocida(@TempDir Path out) {
        assertThrows(IllegalArgumentException.class, () -> DatalakeStore.create("batch", out));
    }
}
