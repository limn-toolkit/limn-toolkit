package limn.backend.lwjgl;

import limn.backend.Platform;
import limn.backend.PrintResult;
import limn.backend.spi.PrintJob;
import limn.backend.spi.PrinterPeer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A window's printer: the system's print dialog, owned by the window, one at a time for the
 * window, each answer delivered on the UI thread after the event that asked. PDFKit on
 * macOS, the desktop portal on Linux, {@code PrintDlgEx} and Direct2D's print control on Windows.
 *
 * <p>Every request is answered exactly once, whatever its door throws, an {@link Error} included:
 * a request that never answered left the window believing its dialog open, and every later print
 * on it answered {@link PrintResult#CANCELLED}.
 */
final class WindowPrinter implements PrinterPeer {

    private static final Platform PLATFORM = Platform.current();
    private static final System.Logger LOG = System.getLogger(WindowPrinter.class.getName());

    /** What asks the system for one request: the door of the system the window is on. */
    interface Door {
        /**
         * @param job  what to print
         * @param done the answer, called once on the UI thread, now or after the event that answers
         */
        void print(PrintJob job, Consumer<PrintResult> done);
    }

    private final BooleanSupplier closed;
    private final Door door;
    /** Whether a print dialog is open on this window, which makes the next request answer cancelled. */
    private boolean open;

    WindowPrinter(LwjglWindow window) {
        this(window::isClosed, (job, done) -> {
            if (PLATFORM.isMacOs()) {
                MacPrinter.print(window::cocoaWindow, window.isVisible(), job, done);
            } else if (PLATFORM.isWindows()) {
                WindowsPrinter.print(window.nativeHandle(), job, done);
            } else {
                PortalPrinter.print(System.getenv("DBUS_SESSION_BUS_ADDRESS"), window.portalParent(), job, done);
            }
        });
    }

    /**
     * @param closed whether the window is closed
     * @param door   what asks the system; a test's door, or the window's system's
     */
    WindowPrinter(BooleanSupplier closed, Door door) {
        this.closed = closed;
        this.door = door;
    }

    @Override
    public boolean isAvailable() {
        if (closed.getAsBoolean()) {
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
        if (closed.getAsBoolean()) {
            answer.complete(PrintResult.UNAVAILABLE);
            return answer;
        }
        if (open) {
            // A second "Print…" while the dialog is up: the system shows one dialog per window.
            answer.complete(PrintResult.CANCELLED);
            return answer;
        }
        open = true;
        boolean[] answered = {false};
        Consumer<PrintResult> done = result -> {
            if (answered[0]) {
                return; // a door that answered and then threw, or answered twice: the first answer stands
            }
            answered[0] = true;
            open = false;
            answer.complete(result);
        };
        try {
            door.print(job, done);
        } catch (Throwable failed) {
            // An Error as well as an exception: an OutOfMemoryError copying a large PDF into native
            // memory is the likely one, and it escaped, leaving the dialog open for good. Nobody is
            // waiting on the stage yet, so answering first runs nothing before the warning.
            done.accept(PrintResult.FAILED);
            LOG.log(System.Logger.Level.WARNING, "the system's print dialog could not be shown", failed);
        }
        return answer;
    }
}
