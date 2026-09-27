package es.ulpgc.bigdata.control;

import java.util.Set;

public record RecoveryReport(Set<String> recovered, Set<String> droppedDownloads,
                             Set<String> droppedIndexed) {

    public boolean isClean() {
        return recovered.isEmpty() && droppedDownloads.isEmpty() && droppedIndexed.isEmpty();
    }

    @Override
    public String toString() {
        return "recuperados=" + recovered + ", descargas sin fichero=" + droppedDownloads
                + ", indexados sin fichero=" + droppedIndexed;
    }
}
