package es.ulpgc.bigdata.ingestion;

/** Un libro ya separado en cabecera y cuerpo. */
public record Book(int id, String header, String body) {
}
