package es.ulpgc.bigdata.index;

import es.ulpgc.bigdata.util.FileUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * datamarts/inverted_index.json: un único objeto {"adventure":[5,12,42],"island":[5,1342]}
 * con las claves ordenadas y sin espacios. Actualizarlo es cargar, fusionar y reescribir
 * el fichero entero.
 */
public class JsonIndex implements InvertedIndex {

    private final Path path;
    private TreeMap<String, List<Integer>> postings; // null hasta la primera carga

    public JsonIndex(Path path) {
        this.path = path;
    }

    @Override
    public String name() {
        return "json";
    }

    private TreeMap<String, List<Integer>> load() throws IOException {
        if (postings == null) {
            try {
                postings = JsonCodec.parse(Files.readString(path, StandardCharsets.UTF_8));
            } catch (NoSuchFileException e) {
                postings = new TreeMap<>();
            }
        }
        return postings;
    }

    @Override
    public void add(Map<String, List<Integer>> batch) throws IOException {
        TreeMap<String, List<Integer>> index = load();
        batch.forEach((term, ids) -> index.put(term, Postings.merge(index.getOrDefault(term, List.of()), ids)));
        Files.createDirectories(path.toAbsolutePath().getParent());
        FileUtils.atomicWrite(path, JsonCodec.write(index));
    }

    @Override
    public List<Integer> lookup(String term) throws IOException {
        return load().getOrDefault(term, List.of());
    }

    @Override
    public void forEach(TermVisitor visitor) throws IOException {
        for (Map.Entry<String, List<Integer>> entry : load().entrySet()) {
            visitor.visit(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public boolean isEmpty() throws IOException {
        return load().isEmpty();
    }

    @Override
    public void reset() throws IOException {
        Files.deleteIfExists(path);
        postings = new TreeMap<>();
    }

    @Override
    public void close() {
    }

    /**
     * Lectura y escritura del formato del índice sin dependencias externas. Los términos
     * solo contienen [a-z], así que no hace falta escapar nada al escribir.
     */
    static final class JsonCodec {

        private JsonCodec() {
        }

        static String write(Map<String, List<Integer>> index) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, List<Integer>> entry : index.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(entry.getKey()).append("\":[");
                List<Integer> ids = entry.getValue();
                for (int i = 0; i < ids.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(ids.get(i));
                }
                sb.append(']');
            }
            return sb.append('}').toString();
        }

        static TreeMap<String, List<Integer>> parse(String json) throws IOException {
            Parser parser = new Parser(json);
            TreeMap<String, List<Integer>> index = parser.object();
            parser.skipSpaces();
            if (!parser.atEnd()) {
                throw parser.error("contenido inesperado al final");
            }
            return index;
        }

        private static final class Parser {
            private final String s;
            private int pos;

            Parser(String s) {
                this.s = s;
            }

            boolean atEnd() {
                return pos >= s.length();
            }

            void skipSpaces() {
                while (!atEnd() && " \t\n\r".indexOf(s.charAt(pos)) >= 0) {
                    pos++;
                }
            }

            IOException error(String message) {
                return new IOException("inverted_index.json no válido (posición " + pos + "): " + message);
            }

            void expect(char c) throws IOException {
                skipSpaces();
                if (atEnd() || s.charAt(pos) != c) {
                    throw error("se esperaba '" + c + "'");
                }
                pos++;
            }

            boolean consume(char c) {
                skipSpaces();
                if (!atEnd() && s.charAt(pos) == c) {
                    pos++;
                    return true;
                }
                return false;
            }

            TreeMap<String, List<Integer>> object() throws IOException {
                TreeMap<String, List<Integer>> index = new TreeMap<>();
                expect('{');
                if (consume('}')) {
                    return index;
                }
                do {
                    String term = string();
                    expect(':');
                    index.put(term, array());
                } while (consume(','));
                expect('}');
                return index;
            }

            String string() throws IOException {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (!atEnd() && s.charAt(pos) != '"') {
                    char c = s.charAt(pos++);
                    if (c == '\\') {
                        if (atEnd()) {
                            break;
                        }
                        char escaped = s.charAt(pos++);
                        switch (escaped) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case 'r' -> sb.append('\r');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'u' -> {
                                if (pos + 4 > s.length()) {
                                    throw error("secuencia \\u incompleta");
                                }
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                                pos += 4;
                            }
                            default -> sb.append(escaped);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                expect('"');
                return sb.toString();
            }

            List<Integer> array() throws IOException {
                List<Integer> ids = new ArrayList<>();
                expect('[');
                if (consume(']')) {
                    return ids;
                }
                do {
                    skipSpaces();
                    int start = pos;
                    if (!atEnd() && s.charAt(pos) == '-') {
                        pos++;
                    }
                    while (!atEnd() && Character.isDigit(s.charAt(pos))) {
                        pos++;
                    }
                    if (start == pos) {
                        throw error("se esperaba un número");
                    }
                    ids.add(Integer.parseInt(s.substring(start, pos)));
                } while (consume(','));
                expect(']');
                return ids;
            }
        }
    }
}
