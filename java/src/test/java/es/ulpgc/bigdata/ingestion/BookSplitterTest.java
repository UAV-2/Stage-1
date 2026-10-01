package es.ulpgc.bigdata.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookSplitterTest {

    private static void check(String raw, String header, String body) throws Exception {
        Book book = BookSplitter.split(1, raw);
        assertEquals(header, book.header());
        assertEquals(body, book.body());
    }

    @Test
    void marcadoresActuales() throws Exception {
        check("Title: A\n\n*** START OF THE PROJECT GUTENBERG EBOOK A ***\n\nTexto\n\n*** END OF THE PROJECT GUTENBERG EBOOK A ***\nLicencia",
                "Title: A", "Texto");
    }

    @Test
    void libroAntiguoConThisYSinEspacio() throws Exception {
        check("Title: B\n***START OF THIS PROJECT GUTENBERG EBOOK B***\nTexto\n***END OF THIS PROJECT GUTENBERG EBOOK B***",
                "Title: B", "Texto");
    }

    @Test
    void minusculas() throws Exception {
        check("Title: C\n*** start of the project gutenberg ebook c ***\nTexto\n*** end of the project gutenberg ebook c ***",
                "Title: C", "Texto");
    }

    @Test
    void saltosDeLineaDeWindows() throws Exception {
        check("Title: D\r\n*** START OF THE PROJECT GUTENBERG EBOOK D ***\r\nUno\r\nDos\r\n*** END OF THE PROJECT GUTENBERG EBOOK D ***\r\n",
                "Title: D", "Uno\nDos");
    }

    @Test
    void espaciosUnicodeComoEnGo() throws Exception {
        // U+00A0 se recorta (Go lo considera espacio); U+FEFF (BOM) no.
        check("\uFEFFTitle: E\u00A0\n*** START OF THE PROJECT GUTENBERG EBOOK E ***\n\u00A0Texto\u00A0\n*** END OF THE PROJECT GUTENBERG EBOOK E ***",
                "\uFEFFTitle: E", "Texto");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "texto sin marcadores",
            "*** START OF THE PROJECT GUTENBERG EBOOK A ***\nsin final",
            "sin principio\n*** END OF THE PROJECT GUTENBERG EBOOK A ***",
            "*** END OF THE PROJECT GUTENBERG EBOOK A ***\n*** START OF THE PROJECT GUTENBERG EBOOK A ***\n",
    })
    void sinMarcadores(String raw) {
        assertThrows(BookUnavailableException.class, () -> BookSplitter.split(1, raw));
    }
}
