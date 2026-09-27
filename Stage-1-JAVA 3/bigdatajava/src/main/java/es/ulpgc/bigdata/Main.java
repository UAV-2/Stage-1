package es.ulpgc.bigdata;

import es.ulpgc.bigdata.control.CandidateProvider;
import es.ulpgc.bigdata.control.ControlPipeline;
import es.ulpgc.bigdata.control.ControlState;
import es.ulpgc.bigdata.datalake.BookLocation;
import es.ulpgc.bigdata.datalake.DatalakeFactory;
import es.ulpgc.bigdata.datalake.DatalakeStore;
import es.ulpgc.bigdata.indexing.BookIndexer;
import es.ulpgc.bigdata.ingestion.GutenbergDownloader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class Main {

    private static final int TOTAL_BOOKS = 70000;

    public static void main(String[] args) {
        Map<String, String> options = new HashMap<>();
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                int eq = arg.indexOf('=');
                options.put(arg.substring(2, eq), arg.substring(eq + 1));
            } else {
                positional.add(arg);
            }
        }
        if (positional.isEmpty()) {
            printUsage();
            return;
        }

        try {
            Path output = Path.of(options.getOrDefault("out", "output/java"));
            String type = options.getOrDefault("datalake", "book");
            DatalakeStore store = DatalakeFactory.create(type, output);
            ControlState state = new ControlState(output.resolve("control").resolve(type));
            List<String> ids = positional.subList(1, positional.size());

            switch (positional.get(0)) {
                case "pipeline" -> runPipeline(store, state, options);
                case "download" -> {
                    List<String> targets = ids.isEmpty() ? List.of("1342") : ids;
                    ControlPipeline pipeline = new ControlPipeline(store, state, new GutenbergDownloader(),
                            BookIndexer.noOp(), CandidateProvider.fromList(targets));
                    pipeline.recover();
                    System.out.println("[DOWNLOAD] Resultado: " + pipeline.run(2 * targets.size() + 1));
                }
                case "lookup" -> lookup(store, ids);
                case "status" -> status(store, state);
                case "recover" -> {
                    ControlPipeline pipeline = new ControlPipeline(store, state, new GutenbergDownloader(),
                            BookIndexer.noOp(), s -> Optional.empty());
                    System.out.println("[RECOVER] " + pipeline.recover());
                }
                default -> printUsage();
            }
        } catch (Exception e) {
            System.out.printf("[ERROR]: %s%n", e.getMessage());
        }
    }

    private static void runPipeline(DatalakeStore store, ControlState state, Map<String, String> options)
            throws IOException, InterruptedException {
        int steps = Integer.parseInt(options.getOrDefault("steps", "10"));
        CandidateProvider candidates = options.containsKey("ids")
                ? CandidateProvider.fromFile(Path.of(options.get("ids")))
                : CandidateProvider.random(TOTAL_BOOKS, Long.parseLong(options.getOrDefault("seed", "42")));

        ControlPipeline pipeline = new ControlPipeline(store, state, new GutenbergDownloader(),
                BookIndexer.noOp(), candidates);
        pipeline.recover();
        System.out.println("[PIPELINE] Resultado: " + pipeline.run(steps));
    }

    private static void lookup(DatalakeStore store, List<String> ids) throws IOException {
        for (String id : ids) {
            Optional<BookLocation> location = store.locate(id);
            System.out.printf("[LOOKUP:%s] %s -> %s%n", store.name(), id,
                    location.map(l -> l.body().getParent().toString()).orElse("no encontrado"));
        }
    }

    private static void status(DatalakeStore store, ControlState state) throws IOException {
        System.out.printf("""
                [STATUS:%s]
                  en datalake:   %d
                  descargados:   %d
                  indexados:     %d
                  pendientes:    %d
                  descartados:   %d%n""",
                store.name(), store.listBookIds().size(), state.downloaded().size(),
                state.indexed().size(), state.pendingToIndex().size(), state.failed().size());
    }

    private static void printUsage() {
        System.out.println("""
                Uso: Main <comando> [ids...] [opciones]

                Comandos:
                  pipeline            ejecuta el ciclo descarga/indexación del control layer
                  download <id...>    descarga libros concretos (por defecto 1342)
                  lookup <id...>      localiza header y body de cada libro
                  status              resumen del estado del pipeline
                  recover             alinea los ficheros de control con el datalake

                Opciones:
                  --datalake=time|book|batch   estructura del datalake (por defecto book)
                  --out=DIR                    carpeta de salida (por defecto output/java)
                  --steps=N                    pasos del pipeline (por defecto 10)
                  --ids=FICHERO                lista fija de IDs a descargar
                  --seed=N                     semilla para IDs aleatorios (por defecto 42)""");
    }
}
