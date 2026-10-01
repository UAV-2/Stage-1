package es.ulpgc.bigdata.ingestion;

import es.ulpgc.bigdata.util.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Separa el texto crudo de Project Gutenberg en cabecera y cuerpo. */
public final class BookSplitter {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    // Marcadores sin distinguir mayúsculas, con THE o THIS y espacio opcional tras "***".
    private static final Pattern START = Pattern.compile(
            "\\*\\*\\* ?START OF (THE|THIS) PROJECT GUTENBERG EBOOK[^\\n]*\\n", FLAGS);
    private static final Pattern END = Pattern.compile(
            "\\*\\*\\* ?END OF (THE|THIS) PROJECT GUTENBERG EBOOK", FLAGS);

    private BookSplitter() {
    }

    /**
     * El header es todo lo anterior al START y el body lo que hay entre el final de la
     * línea START y el END. El footer se descarta.
     */
    public static Book split(int id, String raw) throws BookUnavailableException {
        String text = raw.replace("\r\n", "\n");

        Matcher start = START.matcher(text);
        Matcher end = END.matcher(text);
        if (!start.find() || !end.find() || end.start() < start.end()) {
            throw new BookUnavailableException("marcadores no encontrados en el libro " + id);
        }

        return new Book(id,
                Text.trimSpace(text.substring(0, start.start())),
                Text.trimSpace(text.substring(start.end(), end.start())));
    }
}
