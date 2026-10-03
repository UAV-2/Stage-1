package es.ulpgc.bigdata.bench;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pico de memoria residente del proceso (SPEC §11): VmHWM de /proc/self/status en Linux y,
 * en Windows, el pico del working set, que es su equivalente.
 */
final class PeakMemory {

    private PeakMemory() {
    }

    /** Valor en bytes y de dónde sale. */
    record Reading(long bytes, String source) {
    }

    static Reading read() throws IOException {
        Path status = Path.of("/proc/self/status");
        if (Files.exists(status)) {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("VmHWM:")) {
                    return new Reading(Long.parseLong(line.split("\\s+")[1]) * 1024, "VmHWM");
                }
            }
        }
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            Psapi.Counters counters = new Psapi.Counters();
            counters.cb = counters.size();
            if (!Psapi.INSTANCE.GetProcessMemoryInfo(Kernel32.INSTANCE.GetCurrentProcess(), counters, counters.cb)) {
                throw new IOException("GetProcessMemoryInfo ha fallado");
            }
            return new Reading(counters.PeakWorkingSetSize, "PeakWorkingSetSize");
        }
        throw new IOException("no se puede leer el pico de memoria en este sistema");
    }

    interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer GetCurrentProcess();
    }

    interface Psapi extends Library {
        Psapi INSTANCE = Native.load("psapi", Psapi.class);

        boolean GetProcessMemoryInfo(Pointer process, Counters counters, int size);

        /** PROCESS_MEMORY_COUNTERS de Windows (los SIZE_T son de 64 bits en una JVM de 64 bits). */
        @Structure.FieldOrder({"cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize",
                "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
                "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage"})
        class Counters extends Structure {
            public int cb;
            public int PageFaultCount;
            public long PeakWorkingSetSize;
            public long WorkingSetSize;
            public long QuotaPeakPagedPoolUsage;
            public long QuotaPagedPoolUsage;
            public long QuotaPeakNonPagedPoolUsage;
            public long QuotaNonPagedPoolUsage;
            public long PagefileUsage;
            public long PeakPagefileUsage;
        }
    }
}
