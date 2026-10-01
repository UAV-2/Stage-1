package es.ulpgc.bigdata.control;

import es.ulpgc.bigdata.util.FileUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Un fichero de control: un ID por línea, en orden de llegada y sin repetidos. */
class BookList {

    private final Path path;
    private List<Integer> ids = new ArrayList<>();
    private Set<Integer> has = new HashSet<>();

    BookList(Path path) throws IOException {
        this.path = path;
        for (String line : FileUtils.loadLines(path)) {
            try {
                int id = Integer.parseInt(line);
                if (has.add(id)) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // línea corrupta: se ignora
            }
        }
    }

    boolean contains(int id) {
        return has.contains(id);
    }

    List<Integer> ids() {
        return Collections.unmodifiableList(ids);
    }

    void add(int id) throws IOException {
        if (has.contains(id)) {
            return;
        }
        FileUtils.appendLine(path, String.valueOf(id));
        ids.add(id);
        has.add(id);
    }

    void replace(List<Integer> newIds) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int id : newIds) {
            sb.append(id).append('\n');
        }
        FileUtils.atomicWrite(path, sb.toString());
        ids = new ArrayList<>(newIds);
        has = new HashSet<>(newIds);
    }
}
