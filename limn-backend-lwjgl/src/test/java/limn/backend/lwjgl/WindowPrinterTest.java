package limn.backend.lwjgl;

import limn.backend.PrintResult;
import limn.backend.spi.PrintJob;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A window's printer answers every request once, whatever its system's door throws, an
 * {@link Error} included, and is free to print again after it. An OutOfMemoryError (copying a
 * large PDF into native memory is the likely one) escaped the door: the window went on believing
 * its dialog open and answered every later print on it CANCELLED, and the caller's stage never
 * completed. The portal's door threw it on a thread of its own, which ended before the answer was
 * posted. A plain {@code Error} stands for it here: JUnit ends the whole run when an
 * OutOfMemoryError leaves a test, which is what a regression would make these do.
 */
class WindowPrinterTest {

    private static final PrintJob JOB = new PrintJob("Invoice 0091",
            "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1), 0, 0, -1);

    @Test
    void aDoorThatThrowsAnErrorAnswersFailedAndTheWindowPrintsAgain() {
        List<Consumer<PrintResult>> asked = new ArrayList<>();
        boolean[] throwing = {true};
        WindowPrinter printer = new WindowPrinter(() -> false, (job, done) -> {
            if (throwing[0]) {
                throw new Error("a native copy of the PDF");
            }
            asked.add(done);
        });
        assertEquals(PrintResult.FAILED, printer.print(JOB).toCompletableFuture().getNow(null));

        throwing[0] = false;
        CompletableFuture<PrintResult> second = printer.print(JOB).toCompletableFuture();
        assertEquals(1, asked.size(), "the door is asked again: no dialog is believed open");
        assertEquals(PrintResult.CANCELLED, printer.print(JOB).toCompletableFuture().getNow(null),
                "while that one is up, another is cancelled, as before");
        asked.get(0).accept(PrintResult.PRINTED);
        assertEquals(PrintResult.PRINTED, second.getNow(null));
    }

    /**
     * A request answered FAILED for a throw after its dialog was already asked for keeps that
     * answer when the dialog's own comes late, and that late answer does not close the window's
     * next dialog: it would have let a third print through while the second was up.
     */
    @Test
    void aLateAnswerToARequestAlreadyAnsweredChangesNothing() {
        List<Consumer<PrintResult>> asked = new ArrayList<>();
        WindowPrinter printer = new WindowPrinter(() -> false, (job, done) -> {
            asked.add(done);
            if (asked.size() == 1) {
                throw new Error("after the dialog was asked for");
            }
        });
        CompletableFuture<PrintResult> first = printer.print(JOB).toCompletableFuture();
        assertEquals(PrintResult.FAILED, first.getNow(null));
        CompletableFuture<PrintResult> second = printer.print(JOB).toCompletableFuture();
        assertEquals(2, asked.size());

        asked.get(0).accept(PrintResult.PRINTED); // the first dialog's answer, late
        assertEquals(PrintResult.FAILED, first.getNow(null));
        assertEquals(PrintResult.CANCELLED, printer.print(JOB).toCompletableFuture().getNow(null),
                "the second dialog is still up");
        assertEquals(2, asked.size());
        asked.get(1).accept(PrintResult.PRINTED);
        assertEquals(PrintResult.PRINTED, second.getNow(null));
    }

    @Test
    void thePortalsThreadAnswersFailedWhenItsCallThrowsAnError() {
        try (HeadlessUi ui = new HeadlessUi()) {
            AtomicReference<PrintResult> answer = new AtomicReference<>();
            PortalPrinter.print("unix:path=/run/user/1000/bus", "", JOB, answer::set, (bus, parent, job, why) -> {
                throw new Error("a memory file of the PDF");
            });
            ui.pumpUntil(() -> answer.get() != null);
            assertEquals(PrintResult.FAILED, answer.get());
        }
    }
}
