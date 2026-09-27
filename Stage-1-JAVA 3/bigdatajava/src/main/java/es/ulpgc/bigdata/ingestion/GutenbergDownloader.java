package es.ulpgc.bigdata.ingestion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Descarga libros de Project Gutenberg y los separa con las mismas reglas que Go. */
public class GutenbergDownloader implements BookSource {

    private static final Pattern START = Pattern.compile(
            "(?i)\\*\\*\\* ?START OF (THE|THIS) PROJECT GUTENBERG EBOOK[^\\n]*\\n");
    private static final Pattern END = Pattern.compile(
            "(?i)\\*\\*\\* ?END OF (THE|THIS) PROJECT GUTENBERG EBOOK");

    private static final String URL_TEMPLATE = "https://www.gutenberg.org/cache/epub/%s/pg%s.txt";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    public BookParts fetch(String bookId) throws IOException, InterruptedException {
        return split(bookId, download(bookId));
    }

    public String download(String bookId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(String.format(URL_TEMPLATE, bookId, bookId)))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            throw new BookUnavailableException("el libro " + bookId + " no existe");
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " al descargar el libro " + bookId);
        }
        return response.body();
    }

    public static BookParts split(String bookId, String rawText) throws BookUnavailableException {
        String text = rawText.replace("\r\n", "\n");

        Matcher start = START.matcher(text);
        Matcher end = END.matcher(text);
        if (!start.find() || !end.find()) {
            throw new BookUnavailableException("marcadores no encontrados en el libro " + bookId);
        }

        String header = text.substring(0, start.start()).strip();
        String body = text.substring(start.end(), end.start()).strip();
        return new BookParts(bookId, header, body);
    }
}
