package es.ulpgc.bigdata.ingestion;

import es.ulpgc.bigdata.util.FileUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/**
 * Descarga de Project Gutenberg. Espera {@code delay} entre peticiones para no saturar
 * el servidor y deja una copia cruda en cache/, que después sirve para --offline.
 */
public class GutenbergSource implements BookSource {

    private static final String URL_TEMPLATE = "https://www.gutenberg.org/cache/epub/%d/pg%d.txt";

    private final Path cacheDir;
    private final Duration delay;
    private final String urlTemplate;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(60))
            .build();
    private long lastRequest = -1;

    public GutenbergSource(Path cacheDir, Duration delay) {
        this(cacheDir, delay, URL_TEMPLATE);
    }

    /** urlTemplate lleva dos %d con el ID del libro (los tests usan un servidor local). */
    GutenbergSource(Path cacheDir, Duration delay, String urlTemplate) {
        this.cacheDir = cacheDir;
        this.delay = delay;
        this.urlTemplate = urlTemplate;
    }

    @Override
    public String fetch(int id) throws IOException, InterruptedException {
        if (lastRequest >= 0) {
            long wait = lastRequest + delay.toNanos() - System.nanoTime();
            if (wait > 0) {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            }
        }
        try {
            return download(id);
        } finally {
            lastRequest = System.nanoTime();
        }
    }

    private String download(int id) throws IOException, InterruptedException {
        // Gutenberg sirve algunos libros solo comprimidos (negociación de contenido de
        // Apache) y los envía con Content-Encoding: gzip. Se acepta gzip y se descomprime,
        // como hace el cliente HTTP de Go.
        HttpRequest request = HttpRequest.newBuilder(URI.create(String.format(urlTemplate, id, id)))
                .timeout(Duration.ofSeconds(60))
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() == 404) {
            throw new BookUnavailableException("el libro " + id + " no existe");
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " al descargar el libro " + id);
        }

        byte[] content = response.body();
        if (response.headers().firstValue("Content-Encoding").orElse("").equalsIgnoreCase("gzip")) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content))) {
                content = gzip.readAllBytes();
            }
        }
        Files.createDirectories(cacheDir);
        FileUtils.atomicWrite(CacheSource.cachePath(cacheDir, id), content);
        return new String(content, StandardCharsets.UTF_8);
    }
}
