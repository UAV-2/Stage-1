package es.ulpgc.bigdata.metadata;

import es.ulpgc.bigdata.util.Text;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extrae título, autor e idioma de la cabecera de cada libro con expresiones regulares. */
public final class MetadataExtractor {

    // UNIX_LINES: "." acepta cualquier carácter salvo "\n", como en Go y Python.
    // [\t\n\f\r ] en lugar de \s, porque el \s de Java incluye también \v.
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNIX_LINES;
    private static final Pattern TITLE = Pattern.compile("^Title:[\\t\\n\\f\\r ]*(.+)$", FLAGS);
    private static final Pattern AUTHOR = Pattern.compile("^Author:[\\t\\n\\f\\r ]*(.+)$", FLAGS);
    private static final Pattern LANGUAGE = Pattern.compile("^Language:[\\t\\n\\f\\r ]*(.+)$", FLAGS);

    private static final Map<String, String> LANGUAGE_CODES = Map.of(
            "English", "en",
            "Spanish", "es",
            "French", "fr",
            "German", "de");

    private MetadataExtractor() {
    }

    /**
     * Busca cada campo línea a línea y se queda con la primera coincidencia. Si el título
     * continúa en la línea siguiente, la continuación se ignora.
     */
    public static BookMetadata extract(int id, String header) {
        String[] lines = header.split("\n", -1);
        return new BookMetadata(id,
                firstMatch(TITLE, lines),
                firstMatch(AUTHOR, lines),
                normalizeLanguage(firstMatch(LANGUAGE, lines)),
                "");
    }

    private static String firstMatch(Pattern pattern, String[] lines) {
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return Text.trimSpace(matcher.group(1));
            }
        }
        return "";
    }

    private static String normalizeLanguage(String language) {
        String code = LANGUAGE_CODES.get(language);
        return code != null ? code : Text.toLower(language);
    }
}
