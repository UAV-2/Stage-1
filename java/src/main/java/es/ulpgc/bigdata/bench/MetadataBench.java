package es.ulpgc.bigdata.bench;

import es.ulpgc.bigdata.metadata.BookMetadata;
import es.ulpgc.bigdata.metadata.MetadataDatabase;
import es.ulpgc.bigdata.metadata.MetadataFilter;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Micro-benchmarks del datamart de metadatos en SQLite (SPEC §11). */
@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class MetadataBench {

    /** Metadatos ya extraídos y una base vacía en cada invocación. */
    @State(Scope.Benchmark)
    public static class Insert {
        @Param({"100", "250", "500", "1000"})
        public int n;

        List<BookMetadata> records;
        MetadataDatabase db;
        private Path dir;

        @Setup(Level.Trial)
        public void trial() throws IOException {
            BenchEnv env = BenchEnv.get();
            records = env.records(env.source(), env.books(n));
            dir = env.work.resolve("insert");
        }

        @Setup(Level.Invocation)
        public void open() throws IOException {
            if (db != null) {
                db.close();
            }
            FileUtils.deleteRecursively(dir);
            db = new MetadataDatabase(dir.resolve("metadata.db"));
        }

        @TearDown(Level.Trial)
        public void done() throws IOException {
            db.close();
            FileUtils.deleteRecursively(dir);
        }
    }

    /** metadata_insert_time: insertar las N filas en una sola transacción. */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void metadataInsertTime(Insert s) throws IOException {
        s.db.insert(s.records);
    }

    /** Base con N libros, ya abierta, y los 200 autores e IDs al azar que se consultan. */
    @State(Scope.Benchmark)
    public static class Loaded {
        @Param({"100", "250", "500", "1000"})
        public int n;

        MetadataDatabase db;
        List<String> authors;
        List<Integer> ids;
        int next;
        private Path dir;

        @Setup(Level.Trial)
        public void trial() throws IOException {
            BenchEnv env = BenchEnv.get();
            List<BookMetadata> records = env.records(env.source(), env.books(n));
            dir = env.work.resolve("metadata");
            FileUtils.deleteRecursively(dir);
            db = new MetadataDatabase(dir.resolve("metadata.db"));
            db.insert(records);
            authors = BenchEnv.sample(records.stream().map(BookMetadata::author).filter(a -> !a.isEmpty()).toList());
            ids = BenchEnv.sample(env.books(n));
        }

        @TearDown(Level.Trial)
        public void done() throws IOException {
            db.close();
            FileUtils.deleteRecursively(dir);
        }
    }

    /** metadata_query_time_author: todos los libros de un autor. */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public List<BookMetadata> metadataQueryTimeAuthor(Loaded s) throws IOException {
        List<BookMetadata> found = s.db.find(new MetadataFilter(null, s.authors.get(s.next++ % BenchEnv.SAMPLES), null));
        if (found.isEmpty()) {
            throw new IllegalStateException("consulta por autor sin resultados");
        }
        return found;
    }

    /** metadata_query_time_id: la fila de un libro con su body_path. */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public BookMetadata metadataQueryTimeId(Loaded s) throws IOException {
        return s.db.byId(s.ids.get(s.next++ % BenchEnv.SAMPLES)).orElseThrow();
    }
}
