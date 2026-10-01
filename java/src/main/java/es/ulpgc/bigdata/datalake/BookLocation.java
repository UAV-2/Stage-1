package es.ulpgc.bigdata.datalake;

import java.nio.file.Path;

/** Rutas de las dos mitades de un libro. */
public record BookLocation(Path header, Path body) {

    /** Disposición "&lt;id&gt;.header.txt / &lt;id&gt;.body.txt" que comparten time y range. */
    static BookLocation flat(Path dir, int id) {
        return new BookLocation(dir.resolve(id + ".header.txt"), dir.resolve(id + ".body.txt"));
    }
}
