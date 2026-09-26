package es.ulpgc.bigdata.ingestion;

import es.ulpgc.bigdata.util.FileUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Descarga un libro de Project Gutenberg y lo separa en header y body.
 * Usa exactamente las mismas reglas que la implementación en Go para que
 * las salidas sean equivalentes entre lenguajes.
 */
public class GutenbergDownloader {

    // Mismos marcadores (case-insensitive) que en Go.
    // El START consume el resto de la línea del marcador, incluido el salto de línea.
    private static final Pattern START = Pattern.compile(
            "(?i)\\*\\*\\* ?START OF (THE|THIS) PROJECT GUTENBERG EBOOK[^\\n]*\\n");
    private static final Pattern END = Pattern.compile(
            "(?i)\\*\\*\\* ?END OF (THE|THIS) PROJECT GUTENBERG EBOOK");

    private static final String URL_TEMPLATE = "https://www.gutenberg.org/cache/epub/%s/pg%s.txt";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)   // http.Get de Go también sigue redirecciones
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** Descarga el texto completo del libro. */
    public String download(String bookId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(String.format(URL_TEMPLATE, bookId, bookId)))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " al descargar el libro " + bookId);
        }
        return response.body();
    }

    /** Separa header y body usando los marcadores START / END. */
    public static BookParts split(String bookId, String rawText) throws IOException {
        String text = rawText.replace("\r\n", "\n");

        Matcher start = START.matcher(text);
        Matcher end = END.matcher(text);
        if (!start.find() || !end.find()) {
            throw new IOException("marcadores no encontrados");
        }

        String header = text.substring(0, start.start()).strip();
        String body = text.substring(start.end(), end.start()).strip();
        return new BookParts(bookId, header, body);
    }

    /**
     * Descarga, separa y guarda el libro en datalakeDir/<bookId>/{header,body}.txt
     * (estructura book-based, igual que el prototipo en Go).
     */
    public BookParts downloadAndSplit(String bookId, Path datalakeDir) throws IOException, InterruptedException {
        BookParts parts = split(bookId, download(bookId));

        Path bookDir = datalakeDir.resolve(bookId);
        Files.createDirectories(bookDir);
        FileUtils.atomicWrite(bookDir.resolve("header.txt"), parts.header());
        FileUtils.atomicWrite(bookDir.resolve("body.txt"), parts.body());
        return parts;
    }
}
