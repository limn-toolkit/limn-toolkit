package limn.backend.lwjgl;

import limn.backend.PrintResult;
import limn.backend.spi.PrintJob;

import java.util.function.Consumer;

/** The desktop portal's print dialog on Linux. Not written yet: answers unavailable. */
final class PortalPrinter {

    private PortalPrinter() {
    }

    static boolean isAvailable(String sessionBus) {
        return false;
    }

    static void print(String sessionBus, String parent, PrintJob job, Consumer<PrintResult> done) {
        done.accept(PrintResult.UNAVAILABLE);
    }
}
