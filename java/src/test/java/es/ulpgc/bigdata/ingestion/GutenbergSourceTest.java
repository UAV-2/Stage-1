package es.ulpgc.bigdata.ingestion;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Descarga de Gutenberg (§4) contra un servidor local que se comporta como el de Gutenberg:
 * algunos libros solo existen comprimidos y, si el cliente no acepta gzip, Apache responde 406.
 */
class GutenbergSourceTest {

    private static final byte[] BOOK = ("Title: Libro\r\n*** START OF THE PROJECT GUTENBERG EBOOK ***\r\n"
            + "cuerpo\r\n*** END OF THE PROJECT GUTENBERG EBOOK ***\r\n").getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private String template;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        template = "http://127.0.0.1:" + server.getAddress().getPort() + "/%d.txt";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] body;
        if (path.equals("/1.txt")) { // solo existe la variante comprimida
            String accept = exchange.getRequestHeaders().getFirst("Accept-Encoding");
            if (accept == null || !accept.contains("gzip")) {
                exchange.sendResponseHeaders(406, -1);
                return;
            }
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(BOOK);
            }
            body = compressed.toByteArray();
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
        } else if (path.equals("/2.txt")) {
            body = BOOK;
        } else {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void descargaComprimidaYSinComprimir(@TempDir Path dir) throws Exception {
        GutenbergSource source = new GutenbergSource(dir.resolve("cache"), Duration.ZERO, template);
        for (int id : new int[] {1, 2}) {
            assertEquals("cuerpo", BookSplitter.split(id, source.fetch(id)).body());
            // La caché guarda el texto crudo, ya descomprimido.
            assertArrayEquals(BOOK, Files.readAllBytes(dir.resolve("cache").resolve(id + ".txt")));
        }
    }

    @Test
    void error404DescartaElLibro(@TempDir Path dir) {
        GutenbergSource source = new GutenbergSource(dir.resolve("cache"), Duration.ZERO, template);
        assertThrows(BookUnavailableException.class, () -> source.fetch(3));
    }
}
