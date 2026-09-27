package es.ulpgc.bigdata.datalake;

import java.nio.file.Path;

public record BookLocation(String bookId, Path header, Path body) {
}
