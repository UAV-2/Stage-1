package es.ulpgc.bigdata.index;

import es.ulpgc.bigdata.tokenizer.Tokenizer;
import es.ulpgc.bigdata.util.FileUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Operaciones sobre listas de postings comunes a las tres variantes del índice. */
public final class Postings {

    private Postings() {
    }

    /** Une dos listas de IDs y deja el resultado ordenado y sin repetidos. */
    public static List<Integer> merge(List<Integer> existing, List<Integer> added) {
        List<Integer> merged = new ArrayList<>(existing.size() + added.size());
        merged.addAll(existing);
        merged.addAll(added);
        merged.sort(null);
        List<Integer> unique = new ArrayList<>(merged.size());
        for (Integer id : merged) {
            if (unique.isEmpty() || !unique.get(unique.size() - 1).equals(id)) {
                unique.add(id);
            }
        }
        return unique;
    }

    /** IDs comunes a dos listas ordenadas. */
    public static List<Integer> intersect(List<Integer> a, List<Integer> b) {
        List<Integer> common = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            int cmp = Integer.compare(a.get(i), b.get(j));
            if (cmp < 0) {
                i++;
            } else if (cmp > 0) {
                j++;
            } else {
                common.add(a.get(i));
                i++;
                j++;
            }
        }
        return common;
    }

    /**
     * Normaliza la consulta con las mismas reglas que la indexación y devuelve los libros
     * que contienen todos sus términos (AND).
     */
    public static List<Integer> search(InvertedIndex index, String query, Set<String> stopwords)
            throws IOException {
        List<String> terms = Tokenizer.terms(query, stopwords);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<Integer> result = index.lookup(terms.get(0));
        for (String term : terms.subList(1, terms.size())) {
            if (result.isEmpty()) {
                break;
            }
            result = intersect(result, index.lookup(term));
        }
        return result;
    }

    /** Escribe el dump canónico: término\tid1,id2,id3, ordenado por término. */
    public static void dumpTsv(InvertedIndex index, Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        index.forEach((term, ids) -> {
            sb.append(term).append('\t');
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(ids.get(i));
            }
            sb.append('\n');
        });
        FileUtils.atomicWrite(path, sb.toString());
    }
}
