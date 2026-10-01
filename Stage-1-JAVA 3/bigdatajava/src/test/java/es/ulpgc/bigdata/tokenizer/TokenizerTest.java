package es.ulpgc.bigdata.tokenizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenizerTest {

    @Test
    void terms() {
        Set<String> stop = Set.of("the", "and");
        assertEquals(List.of("don"), Tokenizer.terms("don't", stop));
        assertEquals(List.of("well", "known"), Tokenizer.terms("well-known", stop));
        assertEquals(List.of("caf"), Tokenizer.terms("café", stop));
        assertEquals(List.of("island"), Tokenizer.terms("The ISLAND and the Island", stop));
        assertEquals(List.of(), Tokenizer.terms("a I x", stop));
        assertEquals(List.of("chapter", "snake", "case"), Tokenizer.terms("chapter 12: snake_case", stop));
        // Las mayúsculas fuera de A-Z no se convierten: separan.
        assertEquals(List.of("cole", "ngel"), Tokenizer.terms("ÉCOLE Ángel", stop));
        assertEquals(List.of("sea", "ship"), Tokenizer.terms("sea, ship; sea. SHIP!", stop));
        assertEquals(List.of(), Tokenizer.terms("", stop));
    }

    @Test
    void tokenLargo() {
        String word = "a".repeat(200);
        assertEquals(List.of(word), Tokenizer.terms(word, Set.of()));
    }

    @Test
    void loadStopwords(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("stopwords.txt");
        Files.writeString(path, "the\r\nand\n\nof");
        Set<String> stop = Tokenizer.loadStopwords(path);
        assertEquals(3, stop.size());
        assertEquals(List.of("tale", "two", "cities"), Tokenizer.terms("the tale of two cities", stop));
    }
}
