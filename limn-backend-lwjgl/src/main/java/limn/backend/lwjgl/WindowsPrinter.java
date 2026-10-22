package limn.backend.lwjgl;

import limn.backend.PrintResult;
import limn.backend.spi.PrintJob;

import java.util.function.Consumer;

/** Windows' print dialog and Direct2D's print control (ADR 078 §8.2). Not written yet: answers unavailable. */
final class WindowsPrinter {

    private WindowsPrinter() {
    }

    static boolean isAvailable() {
        return false;
    }

    static void print(long hwnd, PrintJob job, Consumer<PrintResult> done) {
        done.accept(PrintResult.UNAVAILABLE);
    }
}
