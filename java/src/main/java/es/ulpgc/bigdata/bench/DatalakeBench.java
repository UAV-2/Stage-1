package es.ulpgc.bigdata.bench;

import es.ulpgc.bigdata.datalake.BookLocation;
import es.ulpgc.bigdata.datalake.DatalakeStore;
import es.ulpgc.bigdata.util.FileUtils;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Micro-benchmarks del datalake (SPEC §11). */
@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class DatalakeBench {

    /** Datalake vacío en cada invocación. */
    @State(Scope.Benchmark)
    public static class Empty {
        @Param({"time", "book", "range"})
        public String kind;
        @Param({"100", "250", "500", "1000"})
        public int n;

        BenchEnv env;
        List<Integer> ids;
        Path out;

        @Setup(Level.Trial)
        public void trial() throws IOException {
            env = BenchEnv.get();
            ids = env.books(n);
            out = env.work.resolve("write");
        }

        @Setup(Level.Invocation)
        public void clean() throws IOException {
            FileUtils.deleteRecursively(out);
        }

        @TearDown(Level.Trial)
        public void done() throws IOException {
            FileUtils.deleteRecursively(out);
        }
    }

    /**
     * write_throughput: leer de cache/, separar y escribir N libros (con su línea de control)
     * en un datalake vacío. BenchMain lo convierte en N / segundos.
     */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Object writeThroughput(Empty s) throws IOException {
        return s.env.ingest(s.kind, s.out, s.ids);
    }

    /** Datalake con N libros, ya abierto, y los 200 IDs al azar que se buscan. */
    @State(Scope.Benchmark)
    public static class Lake {
        @Param({"time", "book", "range"})
        public String kind;
        @Param({"100", "250", "500", "1000"})
        public int n;

        DatalakeStore store;
        List<Integer> targets;
        int next;
        private Path out;

        @Setup(Level.Trial)
        public void trial() throws IOException {
            BenchEnv env = BenchEnv.get();
            out = env.work.resolve("lake");
            FileUtils.deleteRecursively(out);
            env.ingest(kind, out, env.books(n));
            store = DatalakeStore.create(kind, out);
            targets = BenchEnv.sample(env.books(n));
        }

        @TearDown(Level.Trial)
        public void done() throws IOException {
            FileUtils.deleteRecursively(out);
        }
    }

    /** lookup_time: localizar header y body de un libro (los dos ficheros tienen que existir). */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public BookLocation lookupTime(Lake s) {
        return s.store.locate(s.targets.get(s.next++ % BenchEnv.SAMPLES)).orElseThrow();
    }

    /** Datalake con N + 50 libros, de los que los N primeros están indexados. */
    @State(Scope.Benchmark)
    public static class Grown {
        @Param({"time", "book", "range"})
        public String kind;
        @Param({"100", "250", "500", "1000"})
        public int n;

        Path out;
        Set<Integer> indexed;

        @Setup(Level.Trial)
        public void trial() throws IOException {
            BenchEnv env = BenchEnv.get();
            List<Integer> ids = env.books(n + BenchEnv.EXTRA);
            out = env.work.resolve("incremental");
            FileUtils.deleteRecursively(out);
            env.ingest(kind, out, ids);
            indexed = new HashSet<>(ids.subList(0, n));
        }

        @TearDown(Level.Trial)
        public void done() throws IOException {
            FileUtils.deleteRecursively(out);
        }
    }

    /**
     * incremental_detect_time: abrir el datalake de cero (en time, leer _locations.tsv),
     * listar sus libros y quedarse con los que no están entre los N indexados.
     */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public int incrementalDetectTime(Grown s) throws IOException {
        int added = 0;
        for (int id : DatalakeStore.create(s.kind, s.out).list()) {
            if (!s.indexed.contains(id)) {
                added++;
            }
        }
        if (added != BenchEnv.EXTRA) {
            throw new IllegalStateException("se han detectado " + added + " libros nuevos");
        }
        return added;
    }
}
