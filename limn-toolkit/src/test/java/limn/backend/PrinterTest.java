package limn.backend;

import limn.backend.spi.PrintJob;
import limn.backend.spi.PrinterPeer;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What a window's printer refuses before the system is asked, and what it hands the system. */
class PrinterTest {

    private static final byte[] PDF = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);

    /** A peer that records the job it is given and answers PRINTED. */
    private static class Recorded implements PrinterPeer {
        PrintJob job;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public CompletionStage<PrintResult> print(PrintJob job) {
            this.job = job;
            return CompletableFuture.completedFuture(PrintResult.PRINTED);
        }
    }

    @Test
    void aWindowWithNoPrintingAnswersUnavailable() {
        try (HeadlessUi ui = new HeadlessUi()) {
            assertFalse(Printer.NONE.isAvailable());
            assertEquals(PrintResult.UNAVAILABLE, Printer.NONE.print(PDF, "Job").toCompletableFuture().join());
        }
    }

    @Test
    void bytesThatAreNotAPdfAreNeverHandedToTheSystem() {
        try (HeadlessUi ui = new HeadlessUi()) {
            Recorded peer = new Recorded();
            Printer printer = new Printer(peer);
            PrintResult result = printer.print("not a pdf".getBytes(StandardCharsets.ISO_8859_1), "Job")
                    .toCompletableFuture().join();
            assertEquals(PrintResult.FAILED, result);
            assertNull(peer.job, "the system was not asked");
        }
    }

    @Test
    void aPdfReachesThePeerWithItsTitleAndUnknownPaper() {
        try (HeadlessUi ui = new HeadlessUi()) {
            Recorded peer = new Recorded();
            AtomicReference<PrintResult> result = new AtomicReference<>();
            new Printer(peer).print(PDF, "Invoice 0091").thenAccept(result::set);
            assertEquals(PrintResult.PRINTED, result.get());
            assertSame(PDF, peer.job.pdf());
            assertEquals("Invoice 0091", peer.job.title());
            assertFalse(peer.job.knowsPaper());
        }
    }

    @Test
    void aJobNeedsAName() {
        assertThrows(IllegalArgumentException.class, () -> new PrintJob(" ", PDF, 0, 0, -1));
    }

    /**
     * A file whose peer throws, an {@link Error} included, or whose peer's stage fails, answers
     * FAILED. The print was asked from the stage that read the file, where a throw ended that
     * stage, which nobody holds, and the stage the caller held never completed; an
     * OutOfMemoryError copying a large PDF into native memory is the one a window's door threw.
     */
    @Test
    void aFileWhoseSystemThrowsOrFailsAnswersFailed() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("limn-printer", ".pdf");
        try (HeadlessUi ui = new HeadlessUi()) {
            java.nio.file.Files.write(file, PDF);
            List<PrinterPeer> peers = List.of(new Recorded() {
                @Override
                public CompletionStage<PrintResult> print(PrintJob job) {
                    throw new Error("a native copy of the PDF");
                }
            }, new Recorded() {
                @Override
                public CompletionStage<PrintResult> print(PrintJob job) {
                    return CompletableFuture.failedFuture(new IllegalStateException("the system refused"));
                }
            });
            for (PrinterPeer peer : peers) {
                AtomicReference<PrintResult> result = new AtomicReference<>();
                new Printer(peer).print(file, "Invoice 0091").thenAccept(result::set);
                ui.pumpUntil(() -> result.get() != null);
                assertEquals(PrintResult.FAILED, result.get());
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    /**
     * A file printed under a blank name is refused at the call, as bytes are, before the file is
     * read and the system asked. The name was checked only once the file had been read, inside the
     * stage's dependent on the UI thread, so the refusal ended there and the stage the caller held
     * never completed.
     */
    @Test
    void aFileWithABlankNameIsRefusedAtTheCall() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("limn-printer", ".pdf");
        try (HeadlessUi ui = new HeadlessUi()) {
            java.nio.file.Files.write(file, PDF);
            Recorded peer = new Recorded();
            Printer printer = new Printer(peer);
            assertThrows(IllegalArgumentException.class, () -> printer.print(file, " "));
            assertThrows(IllegalArgumentException.class, () -> printer.print(PDF, ""));
            ui.runtime().drain();
            assertNull(peer.job, "the system was not asked");
            AtomicReference<PrintResult> result = new AtomicReference<>();
            printer.print(file, "Invoice 0091").thenAccept(result::set);
            ui.pumpUntil(() -> result.get() != null);
            assertEquals(PrintResult.PRINTED, result.get(), "and a named one prints");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
