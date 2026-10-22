package limn.backend.lwjgl;

import limn.backend.PrintResult;
import limn.backend.spi.PrintJob;

import java.util.function.Consumer;

/** macOS's print panel over PDFKit (ADR 078 §8.2). Not written yet: answers unavailable. */
final class MacPrinter {

    private MacPrinter() {
    }

    static boolean isAvailable() {
        return false;
    }

    static void print(long nsWindow, boolean visible, PrintJob job, Consumer<PrintResult> done) {
        done.accept(PrintResult.UNAVAILABLE);
    }
}
