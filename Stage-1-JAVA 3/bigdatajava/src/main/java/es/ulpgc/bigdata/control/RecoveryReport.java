package es.ulpgc.bigdata.control;

import java.util.ArrayList;
import java.util.List;

/** Resume lo que ha habido que corregir al arrancar. */
public class RecoveryReport {

    /** .tmp borrados. */
    public int tempFiles;
    /** En el datalake pero sin marcar como descargados. */
    public final List<Integer> unregistered = new ArrayList<>();
    /** Marcados como descargados pero no están en el datalake. */
    public final List<Integer> missing = new ArrayList<>();
    /** Marcados como indexados pero no están en el datalake. */
    public final List<Integer> orphanIndexed = new ArrayList<>();
    /** Marcados como indexados pero el índice está vacío. */
    public int reindex;

    public boolean isClean() {
        return tempFiles == 0 && unregistered.isEmpty() && missing.isEmpty()
                && orphanIndexed.isEmpty() && reindex == 0;
    }
}
