package es.ulpgc.bigdata;

import es.ulpgc.bigdata.ingestion.GutenbergDownloader;

import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    public static void main(String[] args) {
        // Prototipo Fase 1: solo descarga y Datalake
        Path datalakeDir = Path.of("output", "java", "datalake_book");
        String bookId = args.length > 0 ? args[0] : "1342";

        try {
            Files.createDirectories(datalakeDir);
            System.out.printf("[PROTOTIPO] Iniciando descarga del libro %s...%n", bookId);
            new GutenbergDownloader().downloadAndSplit(bookId, datalakeDir);
            System.out.printf("[PROTOTIPO] Libro %s descargado y separado con éxito en %s.%n", bookId, datalakeDir);
        } catch (Exception e) {
            System.out.printf("[ERROR]: %s%n", e.getMessage());
        }
    }
}
