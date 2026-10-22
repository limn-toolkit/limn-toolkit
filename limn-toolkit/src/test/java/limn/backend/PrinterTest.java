package limn.backend;

import limn.backend.spi.PrintJob;
import limn.backend.spi.PrinterPeer;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
    private static final class Recorded implements PrinterPeer {
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
}
