package es.ulpgc.bigdata.control;

/** Cuenta lo que ha pasado con cada libro de una ejecución. */
public class Summary {

    public int downloaded;
    public int discarded;
    /** Ya estaban descargados o descartados. */
    public int skipped;
    public int indexed;

    @Override
    public boolean equals(Object o) {
        return o instanceof Summary s && s.downloaded == downloaded && s.discarded == discarded
                && s.skipped == skipped && s.indexed == indexed;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(downloaded, discarded, skipped, indexed);
    }

    @Override
    public String toString() {
        return "Summary{downloaded=" + downloaded + ", discarded=" + discarded + ", skipped=" + skipped
                + ", indexed=" + indexed + "}";
    }
}
