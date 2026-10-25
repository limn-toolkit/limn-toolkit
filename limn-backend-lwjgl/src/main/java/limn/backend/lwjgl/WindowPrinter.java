package limn.backend.lwjgl;

import limn.backend.Platform;
import limn.backend.PrintResult;
import limn.backend.spi.PrintJob;
import limn.backend.spi.PrinterPeer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * A window's printer: the system's print dialog, owned by the window, one at a time for the
 * window, each answer delivered on the UI thread after the event that asked. PDFKit on
 * macOS, the desktop portal on Linux, {@code PrintDlgEx} and Direct2D's print control on Windows.
 */
final class WindowPrinter implements PrinterPeer {

    private static final Platform PLATFORM = Platform.current();

    private final LwjglWindow window;
    /** Whether a print dialog is open on this window, which makes the next request answer cancelled. */
    private boolean open;

    WindowPrinter(LwjglWindow window) {
        this.window = window;
    }

    @Override
    public boolean isAvailable() {
        if (window.isClosed()) {
            return false;
        }
        if (PLATFORM.isMacOs()) {
            return MacPrinter.isAvailable();
        }
        if (PLATFORM.isWindows()) {
            return WindowsPrinter.isAvailable();
        }
        return PortalPrinter.isAvailable(System.getenv("DBUS_SESSION_BUS_ADDRESS"));
    }

    @Override
    public CompletionStage<PrintResult> print(PrintJob job) {
        CompletableFuture<PrintResult> answer = new CompletableFuture<>();
        if (window.isClosed()) {
            answer.complete(PrintResult.UNAVAILABLE);
            return answer;
        }
        if (open) {
            // A second "Print…" while the dialog is up: the system shows one dialog per window.
            answer.complete(PrintResult.CANCELLED);
            return answer;
        }
        open = true;
        Consumer<PrintResult> done = result -> {
            open = false;
            answer.complete(result);
        };
        try {
            if (PLATFORM.isMacOs()) {
                MacPrinter.print(window::cocoaWindow, window.isVisible(), job, done);
            } else if (PLATFORM.isWindows()) {
                WindowsPrinter.print(window.nativeHandle(), job, done);
            } else {
                PortalPrinter.print(System.getenv("DBUS_SESSION_BUS_ADDRESS"), window.portalParent(), job,
                        done);
            }
        } catch (RuntimeException | LinkageError failed) {
            System.getLogger(WindowPrinter.class.getName()).log(System.Logger.Level.WARNING,
                    "the system's print dialog could not be shown", failed);
            done.accept(PrintResult.FAILED);
        }
        return answer;
    }
}
