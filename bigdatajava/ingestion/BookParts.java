package es.ulpgc.bigdata.ingestion;

/**
 * Resultado de dividir un libro de Project Gutenberg en cabecera y cuerpo.
 */
public record BookParts(String bookId, String header, String body) {
}
