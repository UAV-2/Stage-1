package es.ulpgc.bigdata.bench;

import es.ulpgc.bigdata.datalake.DatalakeStore;
import es.ulpgc.bigdata.index.InvertedIndex;
import es.ulpgc.bigdata.index.Postings;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Micro-benchmarks del índice invertido (SPEC §11). */
@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class IndexBench {

    /** Índice vacío en cada invocación. */
    @State(Scope.Benchmark)
    public static class Build {
        @Param({"json", "folders", "mongo"})
        public String kind;
        @Param({"100", "250", "500", "1000"})
        public int n;

        BenchEnv env;
        DatalakeStore store;
        List<Integer> ids;
        InvertedIndex index;

        @Setup(Level.Trial)
        public void trial() throws IOException {
            env = BenchEnv.get();
            store = env.source();
            ids = env.books(n);
            index = env.openIndex(kind, env.work.resolve("build"));
        }

        @Setup(Level.Invocation)
        public void reset() throws IOException {
            index.reset();
        }

        @TearDown(Level.Trial)
        public void done() throws IOException {
            index.reset();
            index.close();
        }
    }

    /** index_build_time: leer y tokenizar los N bodies y construir el índice en un solo lote. */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void indexBuildTime(Build s) throws IOException {
        s.index.add(s.env.postings(s.store, s.ids));
    }

    /** Índice con N libros, ya abierto y cargado. */
    @State(Scope.Benchmark)
    public static class Loaded {
        @Param({"json", "folders", "mongo"})
        public String kind;
        @Param({"100", "250", "500", "1000"})
        public int n;

        BenchEnv env;
        InvertedIndex index;
        int next;

        @Setup(Level.Trial)
        public void trial() throws IOException {
            env = BenchEnv.get();
            index = env.openIndex(kind, env.work.resolve("query"));
            index.reset();
            index.add(env.postings(env.source(), env.books(n)));
        }

        @TearDown(Level.Trial)
        public void done() throws IOException {
            index.reset();
            index.close();
        }
    }

    /** query_time: una consulta de shared/queries.txt (se van alternando). */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public List<Integer> queryTime(Loaded s) throws IOException {
        return Postings.search(s.index, s.env.queries.get(s.next++ % s.env.queries.size()), s.env.stop);
    }

    /** Índice con los N primeros libros antes de cada invocación. */
    @State(Scope.Benchmark)
    public static class Update {
        @Param({"json", "folders", "mongo"})
        public String kind;
        @Param({"100", "250", "500", "1000"})
        public int n;

        BenchEnv env;
        DatalakeStore store;
        Path datamarts;
        Map<String, List<Integer>> base;
        List<Integer> added;
        InvertedIndex measured;
        Map<String, List<Integer>> lastAdded;

        @Setup(Level.Trial)
        public void trial() throws IOException {
            env = BenchEnv.get();
            store = env.source();
            datamarts = env.work.resolve("update");
            base = env.postings(store, env.books(n));
            added = env.books(n + BenchEnv.EXTRA).subList(n, n + BenchEnv.EXTRA);
        }

        /** En folders se deshace la última actualización; reconstruirlo tarda minutos. */
        @Setup(Level.Invocation)
        public void restore() throws IOException {
            if (measured != null) {
                measured.close();
                measured = null;
            }
            try (InvertedIndex index = env.openIndex(kind, datamarts)) {
                if (kind.equals("folders") && lastAdded != null) {
                    BenchEnv.undoFolders(datamarts.resolve("inverted_index"), base, lastAdded);
                } else {
                    index.reset();
                    index.add(base);
                }
            }
        }

        @TearDown(Level.Trial)
        public void done() throws IOException {
            if (measured != null) {
                measured.close();
            }
            try (InvertedIndex index = env.openIndex(kind, datamarts)) {
                index.reset();
            }
        }
    }

    /**
     * update_time: abrir el índice de cero (en JSON, cargar el fichero), leer y tokenizar los
     * libros N+1 … N+50 de la lista y añadirlos.
     */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void updateTime(Update s) throws IOException {
        s.measured = s.env.openIndex(s.kind, s.datamarts);
        Map<String, List<Integer>> postings = s.env.postings(s.store, s.added);
        s.measured.add(postings);
        s.lastAdded = postings;
    }
}
