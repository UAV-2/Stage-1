package es.ulpgc.bigdata.tokenizer;

import es.ulpgc.bigdata.util.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Convierte un texto en los términos que se indexan. Las reglas tienen que dar el mismo
 * resultado en Python, Java y Go, así que se trabaja byte a byte y no se usa ninguna
 * función Unicode del lenguaje.
 */
public final class Tokenizer {

    private static final int MIN_LENGTH = 2;

    private Tokenizer() {
    }

    /** Lee un fichero de stopwords con una palabra por línea. */
    public static Set<String> loadStopwords(Path path) throws IOException {
        Set<String> stop = new HashSet<>();
        for (String line : Files.readString(path, StandardCharsets.UTF_8).split("\n", -1)) {
            String word = Text.trimSpace(line);
            if (!word.isEmpty()) {
                stop.add(word);
            }
        }
        return stop;
    }

    public static List<String> terms(String text, Set<String> stopwords) {
        return terms(text.getBytes(StandardCharsets.UTF_8), stopwords);
    }

    /**
     * Devuelve los términos distintos de un texto, en orden de aparición:
     * <ol>
     *   <li>minúsculas solo para A-Z;</li>
     *   <li>un token es una secuencia [a-z]+; todo lo demás separa;</li>
     *   <li>se descartan los tokens de menos de 2 letras;</li>
     *   <li>se descartan las stopwords;</li>
     *   <li>cada término aparece una sola vez.</li>
     * </ol>
     */
    public static List<String> terms(byte[] text, Set<String> stopwords) {
        Set<String> stop = stopwords == null ? Set.of() : stopwords;
        List<String> terms = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        byte[] token = new byte[64];
        int length = 0;

        for (int i = 0; i <= text.length; i++) {
            int b = i < text.length ? text[i] & 0xFF : -1;
            if ('A' <= b && b <= 'Z') {
                b += 'a' - 'A';
            }
            if ('a' <= b && b <= 'z') {
                if (length == token.length) {
                    token = java.util.Arrays.copyOf(token, length * 2);
                }
                token[length++] = (byte) b;
                continue;
            }
            if (length >= MIN_LENGTH) {
                String term = new String(token, 0, length, StandardCharsets.US_ASCII);
                if (!stop.contains(term) && seen.add(term)) {
                    terms.add(term);
                }
            }
            length = 0;
        }
        return terms;
    }
}
