package es.ulpgc.bigdata.index;

import es.ulpgc.bigdata.util.FileUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * datamarts/inverted_index/&lt;LETRA&gt;/&lt;término&gt;.txt, con un ID por línea. La
 * subcarpeta es la primera letra del término en mayúscula. Actualizarlo solo reescribe
 * los ficheros de los términos afectados.
 */
public class FolderIndex implements InvertedIndex {

    private static final String SUFFIX = ".txt";

    private final Path root;

    public FolderIndex(Path root) {
        this.root = root;
    }

    @Override
    public String name() {
        return "folders";
    }

    private Path path(String term) {
        return root.resolve(term.substring(0, 1).toUpperCase(Locale.ROOT)).resolve(term + SUFFIX);
    }

    @Override
    public void add(Map<String, List<Integer>> postings) throws IOException {
        Set<Path> created = new HashSet<>();
        for (Map.Entry<String, List<Integer>> entry : postings.entrySet()) {
            String term = entry.getKey();
            List<Integer> existing = lookup(term);

            Path path = path(term);
            if (created.add(path.getParent())) {
                Files.createDirectories(path.getParent());
            }

            StringBuilder content = new StringBuilder();
            for (int id : Postings.merge(existing, entry.getValue())) {
                content.append(id).append('\n');
            }
            // Atómica: un fichero cortado a mitad dejaría un ID truncado que parecería
            // un posting válido.
            FileUtils.atomicWrite(path, content.toString());
        }
    }

    @Override
    public List<Integer> lookup(String term) throws IOException {
        String content;
        try {
            content = Files.readString(path(term), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (String field : content.trim().split("\\s+")) {
            if (field.isEmpty()) {
                continue;
            }
            try {
                ids.add(Integer.parseInt(field));
            } catch (NumberFormatException e) {
                throw new IOException("posting no válido \"" + field + "\" en " + path(term), e);
            }
        }
        return ids;
    }

    @Override
    public void forEach(TermVisitor visitor) throws IOException {
        // Las carpetas A..Z se recorren en orden; dentro de cada una se ordena por
        // término, no por nombre de fichero.
        for (Path letter : FileUtils.sortedEntries(root)) {
            if (!Files.isDirectory(letter)) {
                continue;
            }
            List<String> terms = new ArrayList<>();
            for (Path file : FileUtils.sortedEntries(letter)) {
                String name = file.getFileName().toString();
                if (name.endsWith(SUFFIX)) {
                    terms.add(name.substring(0, name.length() - SUFFIX.length()));
                }
            }
            terms.sort(null);
            for (String term : terms) {
                visitor.visit(term, lookup(term));
            }
        }
    }

    @Override
    public boolean isEmpty() throws IOException {
        if (!Files.isDirectory(root)) {
            return true;
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries.findAny().isEmpty();
        }
    }

    /**
     * Suma el tamaño de los ficheros; el espacio real es mayor, porque cada fichero ocupa
     * al menos un bloque del sistema de ficheros.
     */
    @Override
    public long diskUsage() throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(root)) {
            long total = 0;
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                total += Files.size(file);
            }
            return total;
        }
    }

    @Override
    public void reset() throws IOException {
        FileUtils.deleteRecursively(root);
    }

    @Override
    public void close() {
    }
}
