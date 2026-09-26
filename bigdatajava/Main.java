package es.ulpgc.bigdata;

import es.ulpgc.bigdata.datalake.BookLocation;
import es.ulpgc.bigdata.datalake.DatalakeFactory;
import es.ulpgc.bigdata.datalake.DatalakeStore;
import es.ulpgc.bigdata.ingestion.BookParts;
import es.ulpgc.bigdata.ingestion.GutenbergDownloader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Main {

    private static final Path OUTPUT = Path.of("output", "java");

    public static void main(String[] args) {
        String datalakeType = "book";
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--datalake=")) {
                datalakeType = arg.substring("--datalake=".length());
            } else {
                positional.add(arg);
            }
        }
        if (positional.isEmpty()) {
            printUsage();
            return;
        }

        try {
            DatalakeStore store = DatalakeFactory.create(datalakeType, OUTPUT);
            String command = positional.get(0);
            List<String> ids = positional.subList(1, positional.size());

            switch (command) {
                case "download" -> download(store, ids.isEmpty() ? List.of("1342") : ids);
                case "lookup" -> lookup(store, ids);
                case "list" -> System.out.println(store.listBookIds());
                default -> printUsage();
            }
        } catch (Exception e) {
            System.out.printf("[ERROR]: %s%n", e.getMessage());
        }
    }

    private static void download(DatalakeStore store, List<String> ids) throws InterruptedException {
        GutenbergDownloader downloader = new GutenbergDownloader();
        for (String id : ids) {
            try {
                BookParts book = downloader.fetch(id);
                BookLocation location = store.save(book);
                System.out.printf("[DATALAKE:%s] Libro %s guardado en %s%n", store.name(), id,
                        location.body().getParent());
            } catch (java.io.IOException e) {
                System.out.printf("[ERROR] Libro %s: %s%n", id, e.getMessage());
            }
        }
    }

    private static void lookup(DatalakeStore store, List<String> ids) throws java.io.IOException {
        for (String id : ids) {
            Optional<BookLocation> location = store.locate(id);
            System.out.printf("[LOOKUP:%s] %s -> %s%n", store.name(), id,
                    location.map(l -> l.body().getParent().toString()).orElse("no encontrado"));
        }
    }

    private static void printUsage() {
        System.out.println("""
                Uso: Main <comando> [ids...] [--datalake=time|book|batch]
                  download <id...>   descarga y guarda libros (por defecto 1342)
                  lookup <id...>     localiza header y body de cada libro
                  list               lista los libros completos del datalake""");
    }
}
