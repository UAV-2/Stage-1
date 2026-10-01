package es.ulpgc.bigdata.control;

import es.ulpgc.bigdata.datalake.BookLocation;
import es.ulpgc.bigdata.datalake.DatalakeStore;
import es.ulpgc.bigdata.index.InvertedIndex;
import es.ulpgc.bigdata.ingestion.BookSource;
import es.ulpgc.bigdata.metadata.BookMetadata;
import es.ulpgc.bigdata.metadata.MetadataDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPipelineTest {

    /** Sirve libros válidos salvo los IDs marcados como rotos. */
    private static class FakeSource implements BookSource {
        final Set<Integer> broken;
        final List<Integer> fetched = new ArrayList<>();

        FakeSource(Integer... broken) {
            this.broken = Set.of(broken);
        }

        @Override
        public String fetch(int id) {
            fetched.add(id);
            if (broken.contains(id)) {
                return "sin marcadores";
            }
            return "Title: Libro " + id + "\n*** START OF THE PROJECT GUTENBERG EBOOK ***\ncuerpo del libro " + id
                    + "\n*** END OF THE PROJECT GUTENBERG EBOOK ***\n";
        }
    }

    private final List<AutoCloseable> toClose = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
    }

    private static ControlPipeline newPipeline(String kind, Path out, BookSource source) throws IOException {
        return new ControlPipeline(DatalakeStore.create(kind, out), new ControlState(out.resolve("control")))
                .withSource(source);
    }

    private ControlPipeline withDatamarts(ControlPipeline pipeline, String kind, Path out) throws IOException {
        Path datamarts = out.resolve("datamarts");
        MetadataDatabase db = new MetadataDatabase(datamarts.resolve("metadata.db"));
        InvertedIndex idx = InvertedIndex.create(kind, new InvertedIndex.Config(datamarts, null, "test"));
        toClose.add(idx);
        toClose.add(db);
        return pipeline.withDatamarts(db, idx, Set.of("del"), datamarts);
    }

    private static Summary summary(int downloaded, int discarded, int skipped, int indexed) {
        Summary s = new Summary();
        s.downloaded = downloaded;
        s.discarded = discarded;
        s.skipped = skipped;
        s.indexed = indexed;
        return s;
    }

    private static List<Integer> sorted(List<Integer> ids) {
        List<Integer> copy = new ArrayList<>(ids);
        copy.sort(null);
        return copy;
    }

    @Test
    void descargaEnOrdenYSinRepetir(@TempDir Path out) throws Exception {
        FakeSource source = new FakeSource(84);
        ControlPipeline pipeline = newPipeline("book", out, source);

        Summary summary = new Summary();
        pipeline.download(List.of(1342, 84, 11), summary);
        assertEquals(summary(2, 1, 0, 0), summary);
        assertEquals("1342\n11\n", Files.readString(out.resolve("control/downloaded_books.txt")));
        assertEquals("84\n", Files.readString(out.resolve("control/failed_books.txt")));
        assertTrue(pipeline.store().locate(84).isEmpty(), "se ha escrito un libro sin marcadores");

        // Una segunda ejecución solo pide los libros nuevos.
        source.fetched.clear();
        pipeline = newPipeline("book", out, source);
        summary = new Summary();
        pipeline.download(List.of(1342, 84, 11, 1661), summary);
        assertEquals(summary(1, 0, 3, 0), summary);
        assertEquals(List.of(1661), source.fetched);
        assertEquals(List.of(1342, 11, 1661), pipeline.state().pending());
    }

    /** Simula los cortes posibles y comprueba que al reanudar no hay duplicados ni pérdidas. */
    @ParameterizedTest
    @ValueSource(strings = {"time", "book", "range"})
    void reanudarTrasUnCorte(String kind, @TempDir Path out) throws Exception {
        FakeSource source = new FakeSource();
        ControlPipeline pipeline = newPipeline(kind, out, source);
        pipeline.download(List.of(11, 84), new Summary());

        // Corte 1: el libro 1342 llegó al datalake pero no al control.
        BookLocation location = pipeline.store().save(1342, "h", "b");
        // Corte 2: el libro 1661 se quedó a medio escribir.
        Files.writeString(location.body().getParent().resolve("1661.body.txt.tmp"), "a medias");
        // Corte 3: el 84 está registrado pero su body ha desaparecido.
        Files.delete(pipeline.store().locate(84).orElseThrow().body());

        source.fetched.clear();
        pipeline = newPipeline(kind, out, source);
        RecoveryReport report = pipeline.recover();
        assertEquals(1, report.tempFiles);
        assertEquals(List.of(1342), report.unregistered);
        assertEquals(List.of(84), report.missing);

        pipeline.download(List.of(11, 84, 1342, 1661), new Summary());
        assertEquals(List.of(84, 1661), source.fetched);
        List<Integer> want = List.of(11, 84, 1342, 1661);
        assertEquals(want, sorted(pipeline.state().downloaded()));
        assertEquals(want, pipeline.store().list());
    }

    /** Un append cortado a mitad no puede contaminar el siguiente ID. */
    @Test
    void lineaDeControlCortada(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("downloaded_books.txt");
        Files.writeString(path, "11\n84\n13");
        ControlState state = new ControlState(dir);
        assertEquals(List.of(11, 84), state.downloaded());
        state.markDownloaded(1342);
        assertEquals("11\n84\n1342\n", Files.readString(path));
    }

    @Test
    void leerIds(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("book_ids.txt");
        String content = "# dataset\n11\r\n84\n\n1342";
        Files.writeString(path, content);
        assertEquals(List.of(11, 84, 1342), BookIds.read(path));
        // Leer la lista compartida nunca la modifica.
        assertEquals(content, Files.readString(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "folders"})
    void cicloCompleto(String kind, @TempDir Path out) throws Exception {
        FakeSource source = new FakeSource(84);
        ControlPipeline pipeline = withDatamarts(newPipeline("book", out, source), kind, out);

        Summary summary = new Summary();
        pipeline.run(List.of(1342, 84, 11, 1661), 2, summary);
        assertEquals(summary(3, 1, 0, 3), summary);
        assertEquals("1342\n11\n1661\n", Files.readString(out.resolve("control/indexed_books.txt")));
        assertEquals(List.of(), pipeline.state().pending());
        assertEquals(List.of(11, 1342, 1661), pipeline.index().lookup("cuerpo"));
        assertEquals(List.of(), pipeline.index().lookup("del"), "se ha indexado una stopword");

        try (MetadataDatabase db = new MetadataDatabase(out.resolve("datamarts/metadata.db"))) {
            List<BookMetadata> books = db.all();
            assertEquals(3, books.size());
            assertEquals(new BookMetadata(11, "Libro 11", "", "", "datalake_book/11/body.txt"), books.get(0));
        }

        // Repetir la ejecución no hace nada.
        summary = new Summary();
        pipeline.run(List.of(1342, 84, 11, 1661), 2, summary);
        assertEquals(summary(0, 0, 4, 0), summary);
    }

    /**
     * Un corte entre escribir el índice y marcar el libro como indexado: al reanudar se
     * indexa primero lo pendiente y el libro no queda duplicado.
     */
    @Test
    void reanudarIndexacion(@TempDir Path out) throws Exception {
        FakeSource source = new FakeSource();
        newPipeline("book", out, source).download(List.of(11, 84), new Summary());

        ControlPipeline pipeline = withDatamarts(newPipeline("book", out, source), "json", out);
        pipeline.index().add(Map.of("cuerpo", List.of(11)));
        try (MetadataDatabase db = new MetadataDatabase(out.resolve("datamarts/metadata.db"))) {
            db.insert(List.of(new BookMetadata(11, "Libro 11", "", "", "x")));
        }

        List<String> steps = new ArrayList<>();
        pipeline.withLog(steps::add);
        pipeline.recover();
        Summary summary = new Summary();
        pipeline.run(List.of(11, 84, 1342), 10, summary);

        assertEquals(summary(1, 0, 2, 3), summary);
        assertEquals(List.of(
                "2 libros indexados (2 términos)",
                "Libro 1342 guardado en el datalake",
                "1 libros indexados (2 términos)"), steps);
        assertEquals(List.of(11, 84, 1342), pipeline.index().lookup("cuerpo"));
        try (MetadataDatabase db = new MetadataDatabase(out.resolve("datamarts/metadata.db"))) {
            assertEquals(3, db.all().size());
        }
    }

    /**
     * El control es único: al cambiar a una variante de índice vacía, o al pedir una
     * reconstrucción, los libros ya descargados se vuelven a indexar.
     */
    @Test
    void cambioDeIndiceYRebuild(@TempDir Path out) throws Exception {
        FakeSource source = new FakeSource();
        withDatamarts(newPipeline("book", out, source), "json", out).run(List.of(11, 84), 0, new Summary());

        ControlPipeline pipeline = withDatamarts(newPipeline("book", out, source), "folders", out);
        assertEquals(2, pipeline.recover().reindex);
        Summary summary = new Summary();
        pipeline.indexPending(0, summary);
        assertEquals(2, summary.indexed);
        assertEquals(List.of(11, 84), pipeline.index().lookup("cuerpo"));

        pipeline.rebuild();
        assertEquals(List.of(), pipeline.index().lookup("cuerpo"));
        summary = new Summary();
        pipeline.indexPending(1, summary);
        assertEquals(2, summary.indexed);
        assertEquals(List.of(11, 84), pipeline.index().lookup("cuerpo"));
    }
}
