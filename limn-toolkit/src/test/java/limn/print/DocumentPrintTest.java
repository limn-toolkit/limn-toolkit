package limn.print;

import limn.backend.PrintResult;
import limn.backend.Printer;
import limn.backend.spi.PrintJob;
import limn.backend.spi.PrinterPeer;
import limn.components.Label;
import limn.graphics.TextRulers;
import limn.testing.HeadlessUi;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A document's print answers its caller whatever the window's printer does: it is asked from the
 * stage that wrote the PDF, where a throw, an {@link Error} included, ended that stage, which
 * nobody holds, and a stage of the printer's that failed was never heard; either way the stage the
 * caller held never completed.
 */
class DocumentPrintTest {

    private HeadlessUi ui;

    @BeforeEach
    void start() {
        ui = new HeadlessUi();
        TextRulers.install(TestRulers.FIXED);
    }

    @AfterEach
    void stop() {
        TextRulers.uninstall(TestRulers.FIXED);
        ui.close();
    }

    /** A printer whose system answers as it is told. */
    private static PrinterPeer answering(java.util.function.Supplier<CompletionStage<PrintResult>> answer) {
        return new PrinterPeer() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public CompletionStage<PrintResult> print(PrintJob job) {
                return answer.get();
            }
        };
    }

    @Test
    void aPrinterThatThrowsOrFailsAnswersFailed() {
        List<PrinterPeer> peers = List.of(
                answering(() -> {
                    throw new Error("a native copy of the PDF");
                }),
                answering(() -> CompletableFuture.failedFuture(new IllegalStateException("the system refused"))),
                answering(() -> CompletableFuture.completedFuture(PrintResult.CANCELLED)));
        List<PrintResult> expected = List.of(PrintResult.FAILED, PrintResult.FAILED, PrintResult.CANCELLED);
        for (int i = 0; i < peers.size(); i++) {
            Document document = Document.of(Paper.A5).title("Order 4512").add(new Label("Order 4512"));
            AtomicReference<PrintResult> result = new AtomicReference<>();
            document.print(PrintingWindow.of(new Printer(peers.get(i)))).thenAccept(result::set);
            ui.pumpUntil(() -> result.get() != null);
            assertEquals(expected.get(i), result.get(), "printer " + i);
        }
    }
}
