package es.ulpgc.bigdata.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataTest {

    private static void check(String header, String title, String author, String language) {
        assertEquals(new BookMetadata(7, title, author, language, ""), MetadataExtractor.extract(7, header));
    }

    @Test
    void extract() {
        check("The Project Gutenberg eBook of Pride and Prejudice\n\nTitle: Pride and Prejudice\n\nAuthor: Jane Austen\n\nRelease date: June 1, 1998\n\nLanguage: English",
                "Pride and Prejudice", "Jane Austen", "en");
        check("TITLE:   Moby Dick  \nauthor:Herman Melville\nLANGUAGE:\tFrench",
                "Moby Dick", "Herman Melville", "fr");
        // Primera coincidencia; la continuación del título se ignora.
        check("Title: The Life and Adventures\n       of Robinson Crusoe\nTitle: Otro\nLanguage: Spanish",
                "The Life and Adventures", "", "es");
        check("Title: Kalevala\nLanguage: Finnish", "Kalevala", "", "finnish");
        // La etiqueta tiene que estar al principio de la línea.
        check("Original Title: No\n  Author: Tampoco\nLanguage: German", "", "", "de");
        check("Title:\nAuthor:   \nsin idioma", "", "", "");
    }

    @Test
    void database(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("datamarts").resolve("metadata.db");
        List<BookMetadata> books = List.of(
                new BookMetadata(1342, "Pride and Prejudice", "Jane Austen", "en", "datalake_book/1342/body.txt"),
                new BookMetadata(11, "Alice", "", "", "datalake_book/11/body.txt"));

        try (MetadataDatabase db = new MetadataDatabase(path)) {
            assertTrue(db.isEmpty());
            db.insert(books);
            // Repetir la inserción (reindexar tras un corte) no duplica filas.
            db.insert(books.subList(0, 1));
        }

        try (MetadataDatabase db = new MetadataDatabase(path)) {
            assertEquals(List.of(books.get(1), books.get(0)), db.all());
            assertFalse(db.isEmpty());

            assertEquals(Optional.of(books.get(0)), db.byId(1342));
            assertEquals(Optional.empty(), db.byId(5));
            assertEquals(List.of(books.get(0)), db.find(new MetadataFilter(null, "Jane Austen", null)));
            assertEquals(List.of(), db.find(new MetadataFilter(null, "Jane Austen", "es")));
            assertEquals(db.all(), db.find(MetadataFilter.NONE));

            // Los campos ausentes se guardan como NULL, no como cadena vacía.
            try (var conn = DriverManager.getConnection("jdbc:sqlite:" + path);
                 var rows = conn.createStatement().executeQuery(
                         "SELECT COUNT(*) FROM books WHERE author IS NULL AND language IS NULL")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }

            Path dump = dir.resolve("metadata.tsv");
            db.dumpTsv(dump);
            assertEquals("11\tAlice\t\t\n1342\tPride and Prejudice\tJane Austen\ten\n", Files.readString(dump));

            db.reset();
            assertTrue(db.isEmpty());
        }
    }
}
