package limn.backend.spi;

import limn.backend.PrintResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * What a backend's window implements so that {@link limn.backend.Printer} can print through it: the
 * system's own print dialog, owned by the window, and the hand-over of a PDF to the system once the
 * dialog is confirmed.
 *
 * <p>Called on the UI thread. The stage completes on the UI thread, and the UI thread is never
 * blocked while the dialog is open: the window waits, as it does for the system's file choosers.
 * A peer that cannot ask answers {@link PrintResult#UNAVAILABLE}; one that asked and failed logs
 * why and answers {@link PrintResult#FAILED}. A peer never throws for what the PDF holds.
 */
public interface PrinterPeer {

    /**
     * @return whether a print can be asked under this window now, without asking anything; false
     *         for a window with no desktop under it and for a Linux desktop with no print portal
     */
    boolean isAvailable();

    /**
     * Shows the system's print dialog, owned by the window, and hands the job's PDF to the system
     * if the user confirms it.
     *
     * @param job what to print
     * @return what became of it, completed on the UI thread
     */
    CompletionStage<PrintResult> print(PrintJob job);

    /** The peer of a window with no printing under it: nothing is asked. */
    PrinterPeer NONE = new PrinterPeer() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public CompletionStage<PrintResult> print(PrintJob job) {
            return CompletableFuture.completedFuture(PrintResult.UNAVAILABLE);
        }
    };
}
