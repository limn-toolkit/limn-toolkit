package limn.backend;

import limn.backend.spi.PrintJob;
import limn.backend.spi.PrinterPeer;
import limn.concurrent.Ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * What prints a PDF through the system's own print dialog, owned by a window: reached from
 * {@link NativeWindow#printer()}.
 *
 * <pre>{@code
 * window.printer().print(invoice, "Invoice 0091")
 *         .thenAccept(result -> status.setText(result.toString()));
 * }</pre>
 *
 * <p>The dialog is the system's: it chooses the printer, the copies, the pages and the paper, and
 * it may save the job as PDF where the system offers that. While it is open the window waits and
 * the UI thread does not. A document Limn lays out is printed by {@code limn.print.Document#print},
 * which writes its PDF and comes here; a PDF the application already has (one it received, one it
 * saved earlier) is printed by the calls below without a document.
 *
 * <p>The system scales a page to the printer's paper when the two differ, and never up; it does not
 * lay the document out again.
 *
 * <p>Each call returns at once and completes on the UI thread. It throws for a null, a blank title
 * and off the UI thread, as the rest of the window's API does, at the call and before anything is
 * read, and never for what the PDF holds: a file that cannot be read, or a system that refuses the
 * bytes, completes with {@link PrintResult#FAILED} and a {@code WARNING} saying why.
 */
public final class Printer {

    /** The printer of a window with no printing under it: every call answers {@link PrintResult#UNAVAILABLE}. */
    public static final Printer NONE = new Printer(PrinterPeer.NONE);

    private static final System.Logger LOG = System.getLogger(Printer.class.getName());

    private final PrinterPeer peer;

    /**
     * @param peer what the window's backend asks the system through
     */
    public Printer(PrinterPeer peer) {
        this.peer = Objects.requireNonNull(peer, "peer");
    }

    /**
     * Whether a print can be asked under this window, answered without asking anything: an
     * application greys out its "Print…" when it is false. Saving a document as PDF never depends
     * on it.
     *
     * @return false for a window with no desktop under it, and for a Linux desktop with no print
     *         portal
     */
    public boolean isAvailable() {
        Ui.checkUiThread();
        return peer.isAvailable();
    }

    /**
     * Prints a PDF file through the system's dialog.
     *
     * @param pdf   the file; read on a thread of its own
     * @param title the job's name in the system's queue; never blank
     * @return what became of it, completed on the UI thread
     * @throws IllegalArgumentException if {@code title} is blank, here, before the file is read
     */
    public CompletionStage<PrintResult> print(Path pdf, String title) {
        Objects.requireNonNull(pdf, "pdf");
        checkTitle(title);
        Ui.checkUiThread();
        if (!peer.isAvailable()) {
            return CompletableFuture.completedFuture(PrintResult.UNAVAILABLE);
        }
        CompletableFuture<PrintResult> answer = new CompletableFuture<>();
        Path file = pdf.toAbsolutePath().normalize();
        Ui.async(() -> {
            try {
                return Files.readAllBytes(file);
            } catch (IOException unreadable) {
                LOG.log(System.Logger.Level.WARNING, "not printed, the file cannot be read: " + file,
                        unreadable);
                return null;
            }
        }).thenAccept(bytes -> {
            if (bytes == null) {
                answer.complete(PrintResult.FAILED);
                return;
            }
            print(new PrintJob(title, bytes, 0, 0, -1)).thenAccept(answer::complete);
        });
        return answer;
    }

    /**
     * Prints a PDF held in memory through the system's dialog.
     *
     * @param pdf   a complete PDF file; not copied, and not to be changed until the stage completes
     * @param title the job's name in the system's queue; never blank
     * @return what became of it, completed on the UI thread
     * @throws IllegalArgumentException if {@code title} is blank
     */
    public CompletionStage<PrintResult> print(byte[] pdf, String title) {
        return print(new PrintJob(title, pdf, 0, 0, -1));
    }

    /**
     * Prints a job, the form a document laid out by Limn uses: it knows its paper, which the
     * system's dialog then starts from.
     *
     * @param job what to print
     * @return what became of it, completed on the UI thread
     */
    public CompletionStage<PrintResult> print(PrintJob job) {
        Objects.requireNonNull(job, "job");
        Ui.checkUiThread();
        if (!peer.isAvailable()) {
            return CompletableFuture.completedFuture(PrintResult.UNAVAILABLE);
        }
        if (!looksLikePdf(job.pdf())) {
            LOG.log(System.Logger.Level.WARNING, "not printed, the bytes are not a PDF: {0}", job.title());
            return CompletableFuture.completedFuture(PrintResult.FAILED);
        }
        return peer.print(job);
    }

    /**
     * Refuses a job's name at the call, as {@link PrintJob} does: {@link #print(Path, String)} makes
     * its job only once the file is read, on the UI thread after a worker, where a refusal ended the
     * dependent stage and the one the caller holds never completed.
     */
    private static void checkTitle(String title) {
        if (Objects.requireNonNull(title, "title").isBlank()) {
            throw new IllegalArgumentException("a print job needs a name");
        }
    }

    /** A PDF begins with its header; anything else would reach the system's spooler as garbage. */
    private static boolean looksLikePdf(byte[] bytes) {
        return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
                && bytes[3] == 'F' && bytes[4] == '-';
    }
}
