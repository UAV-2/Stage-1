package es.ulpgc.bigdata.util;

import java.util.List;

/**
 * Utilidades de texto que se comportan igual que la librería estándar de Go y Python,
 * para que los tres lenguajes produzcan exactamente la misma salida. Los métodos de
 * Java equivalentes (strip, toLowerCase) difieren en casos límite.
 */
public final class Text {

    private Text() {
    }

    /** Equivalente a unicode.IsSpace de Go (Character.isWhitespace de Java no incluye U+00A0). */
    public static boolean isSpace(int c) {
        return switch (c) {
            case '\t', '\n', 0x0B, '\f', '\r', ' ', 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 ->
                    true;
            default -> c >= 0x2000 && c <= 0x200A;
        };
    }

    /** Equivalente a strings.TrimSpace de Go. */
    public static String trimSpace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end) {
            int c = s.codePointAt(start);
            if (!isSpace(c)) {
                break;
            }
            start += Character.charCount(c);
        }
        while (end > start) {
            int c = s.codePointBefore(end);
            if (!isSpace(c)) {
                break;
            }
            end -= Character.charCount(c);
        }
        return s.substring(start, end);
    }

    /** Minúsculas carácter a carácter, sin las reglas de contexto de String.toLowerCase. */
    public static String toLower(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(c -> sb.appendCodePoint(Character.toLowerCase(c)));
        return sb.toString();
    }

    /** Formato de una lista de IDs en la salida por consola: [1 2 3]. */
    public static String formatIds(List<Integer> ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(ids.get(i));
        }
        return sb.append(']').toString();
    }
}
