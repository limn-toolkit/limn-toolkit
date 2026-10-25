package limn.backend.lwjgl;

import limn.backend.PrintResult;
import limn.backend.lwjgl.a11y.linux.PortalPrint;
import limn.backend.spi.PrintJob;
import limn.concurrent.Ui;
import limn.concurrent.internal.Threads;
import org.jspecify.annotations.Nullable;

import java.lang.System.Logger.Level;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The desktop portal's print dialog on Linux: {@code PreparePrint} and then
 * {@code Print} with the PDF as a file descriptor, through {@link PortalPrint}, on a thread of its
 * own; the answer is posted to the UI thread. Parented to the window under X11 ({@code x11:} and its
 * id); a Wayland window has no name GLFW can export, so there the dialog opens unparented, as the
 * portal's file chooser does.
 *
 * <p><b>Whether a print can be asked.</b> {@link #isAvailable} must answer on the UI thread without
 * waiting on the bus for long, and the answer is whether the session's portal has a print interface,
 * which is a question to the bus. So the answer is kept: the first question starts a read of the
 * interface's {@code version} property on a thread of its own and waits for it up to
 * {@value #FIRST_WAIT_MS} ms; a read that has not come back by then answers yes when there is a
 * session bus at all, which {@link #print} corrects by answering {@link PrintResult#UNAVAILABLE} if
 * the portal then turns out to have no print dialog. A kept answer older than
 * {@value #REFRESH_MS} ms is read again in the background and the kept one given meanwhile, so a
 * portal started or stopped with the session is seen within that time.
 */
final class PortalPrinter {

    private static final System.Logger LOG = System.getLogger(PortalPrinter.class.getName());

    /** How long the first question waits for the bus, on the UI thread. */
    static final long FIRST_WAIT_MS = 250;
    /** How old a kept answer may be before it is read again, off the UI thread. */
    static final long REFRESH_MS = 60_000;

    /** The kept answer: the address it is for, the print interface's version (0: none), and when. */
    private record Known(String address, int version, long at) {
    }

    private static volatile @Nullable Known known;
    private static @Nullable CompletableFuture<Known> reading;

    private PortalPrinter() {
    }

    /**
     * @param sessionBus {@code DBUS_SESSION_BUS_ADDRESS}, or null
     * @return whether the session's portal has a print dialog, as last read
     */
    static boolean isAvailable(@Nullable String sessionBus) {
        if (sessionBus == null || sessionBus.isBlank()) {
            return false;
        }
        Known now = known;
        if (now != null && now.address.equals(sessionBus)) {
            if (System.nanoTime() - now.at > TimeUnit.MILLISECONDS.toNanos(REFRESH_MS)) {
                read(sessionBus);
            }
            return now.version > 0;
        }
        CompletableFuture<Known> answer = read(sessionBus);
        try {
            return answer.get(FIRST_WAIT_MS, TimeUnit.MILLISECONDS).version > 0;
        } catch (TimeoutException slow) {
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        } catch (ExecutionException failed) {
            return false;
        }
    }

    /** Starts a read of the print interface's version unless one is under way. */
    private static synchronized CompletableFuture<Known> read(String sessionBus) {
        if (reading != null && !reading.isDone()) {
            return reading;
        }
        CompletableFuture<Known> answer = new CompletableFuture<>();
        reading = answer;
        Threads.daemon("limn-print-portal-version", () -> {
            int version = 0;
            try {
                version = PortalPrint.version(sessionBus);
            } catch (Exception | LinkageError unreachable) {
                LOG.log(Level.DEBUG, "the session bus did not answer for the print portal", unreachable);
            }
            Known read = new Known(sessionBus, version, System.nanoTime());
            known = read;
            answer.complete(read);
        });
        return answer;
    }

    /**
     * Asks the portal on a thread of its own.
     *
     * @param sessionBus the session bus address
     * @param parent     the portal's name for the window, or {@code ""}
     * @param done       the answer, called on the UI thread
     */
    static void print(@Nullable String sessionBus, String parent, PrintJob job, Consumer<PrintResult> done) {
        print(sessionBus, parent, job, done, (bus, window, asked, why) -> PortalPrint.print(bus, window, asked.title(),
                asked.pdf(), asked.paperWidth(), asked.paperHeight(), why));
    }

    /** What asks the portal, on the dialog's thread: {@link PortalPrint#print}, or a test's. */
    interface Ask {
        PortalPrint.Outcome print(String sessionBus, String parent, PrintJob job, Consumer<String> why);
    }

    /** {@link #print(String, String, PrintJob, Consumer)} through {@code ask}; for the tests. */
    static void print(@Nullable String sessionBus, String parent, PrintJob job, Consumer<PrintResult> done, Ask ask) {
        if (sessionBus == null || sessionBus.isBlank()) {
            done.accept(PrintResult.UNAVAILABLE);
            return;
        }
        Threads.daemon("limn-print-dialog", () -> {
            PrintResult result = PrintResult.FAILED;
            try {
                result = ask(sessionBus, parent, job, ask);
            } catch (Throwable failed) {
                // An Error as well (memory, a stack overflow): it ended this thread before the
                // answer was posted, and the window kept believing its dialog open, answering every
                // later print cancelled. PortalPrint itself turns what the bus does into an outcome.
                LOG.log(Level.WARNING, "not printed, the print portal's call failed: " + job.title(), failed);
            }
            PrintResult answer = result;
            Ui.post(() -> done.accept(answer));
        });
    }

    private static PrintResult ask(String sessionBus, String parent, PrintJob job, Ask ask) {
        StringBuilder why = new StringBuilder();
        PortalPrint.Outcome outcome = ask.print(sessionBus, parent, job,
                line -> why.append(why.length() > 0 ? "; " : "").append(line));
        if (outcome == PortalPrint.Outcome.FAILED) {
            LOG.log(Level.WARNING, "not printed, " + job.title() + ": " + why);
        } else if (outcome == PortalPrint.Outcome.UNAVAILABLE) {
            LOG.log(Level.INFO, "not printed, " + job.title() + ": " + why);
            known = new Known(sessionBus, 0, System.nanoTime());
        }
        return switch (outcome) {
            case PRINTED -> PrintResult.PRINTED;
            case CANCELLED -> PrintResult.CANCELLED;
            case UNAVAILABLE -> PrintResult.UNAVAILABLE;
            default -> PrintResult.FAILED;
        };
    }
}
