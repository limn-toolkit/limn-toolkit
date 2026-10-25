package limn.print;

import limn.backend.NativeWindow;
import limn.backend.PrintResult;
import limn.backend.Printer;
import limn.backend.spi.GlyphOutlines;
import limn.backend.spi.PrintJob;
import limn.backend.spi.PrinterPeer;
import limn.backend.spi.WindowPeer;
import limn.components.Label;
import limn.graphics.Font;
import limn.graphics.Path2D;
import limn.graphics.TextRulers;
import limn.print.internal.Outlines;
import limn.testing.HeadlessUi;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A face that never finishes loading holds no output for ever: a PDF's work and a print wait for
 * it as long as they are told to ({@link Document#FACE_WAIT_MILLIS} by default, much less here),
 * record the pages with the faces that have loaded, say so once, and go on. Both waited on the
 * load with no limit: a print stuck behind a load that never ended neither showed its dialog nor
 * completed, and a PDF's work held its worker until the process ended. A load that ends in time
 * still records the pages at once, never at the deadline.
 */
class DocumentFaceWaitTest {

    private HeadlessUi ui;
    private CompletableFuture<Void> loading;
    private GlyphOutlines outlines;

    private final List<String> warnings = new ArrayList<>();
    private final Logger logger = Logger.getLogger(Document.class.getName());
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    /** Each character a square glyph of face 7, with a load the test ends, or never does. */
    private GlyphOutlines whileLoading(CompletionStage<Void> settled) {
        return new GlyphOutlines() {
            @Override
            public int unitsPerEm(int faceId) {
                return faceId == 7 ? 1000 : 0;
            }

            @Override
            public String faceName(int faceId) {
                return "Letters";
            }

            @Override
            public boolean isSyntheticBold(int faceId) {
                return false;
            }

            @Override
            public boolean outline(int faceId, int glyphId, Path2D.Visitor out) {
                out.moveTo(0, 0);
                out.lineTo(500, 0);
                out.lineTo(500, 700);
                out.close();
                return faceId == 7;
            }

            @Override
            public List<Piece> cluster(String characters, Font font) {
                List<Piece> pieces = new ArrayList<>();
                characters.codePoints().forEach(cp -> pieces.add(new Glyph(7, cp, 0.6f)));
                return pieces;
            }

            @Override
            public CompletionStage<Void> settled() {
                return settled;
            }
        };
    }

    @BeforeEach
    void start() {
        ui = new HeadlessUi();
        TextRulers.install(TestRulers.FIXED);
        loading = new CompletableFuture<>();
        outlines = whileLoading(loading);
        Outlines.install(outlines);
        logger.addHandler(handler);
    }

    @AfterEach
    void stop() {
        logger.removeHandler(handler);
        Outlines.uninstall(outlines);
        TextRulers.uninstall(TestRulers.FIXED);
        ui.close();
    }

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

    /** A window that has nothing but its printer. */
    private static NativeWindow windowPrintingTo(Printer printer) {
        return (NativeWindow) Proxy.newProxyInstance(WindowPeer.class.getClassLoader(),
                new Class<?>[] {WindowPeer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("printer")) {
                        return printer;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type.isPrimitive() && type != void.class) {
                        return type == float.class ? 0f : type == double.class ? 0d : type == long.class ? 0L : 0;
                    }
                    return null;
                });
    }

    @Test
    void aPdfIsWrittenWhenAFaceNeverFinishesLoading() {
        Document document = Document.of(Paper.A5).title("Order 4512").add(new Label("Order 4512"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicReference<Object> done = new AtomicReference<>();
        document.writePdf(bytes, 200).onSuccess(done::set).onFailure(done::set).start();
        ui.pumpUntil(() -> done.get() != null, 5_000_000_000L);
        assertSame(bytes, done.get(), "written: " + done.get());
        assertEquals("%PDF-", new String(bytes.toByteArray(), 0, 5, StandardCharsets.ISO_8859_1));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("still loading"), warnings.get(0));
    }

    @Test
    void aPrintIsAskedWhenAFaceNeverFinishesLoading() {
        Document document = Document.of(Paper.A5).title("Order 4512").add(new Label("Order 4512"));
        Recorded peer = new Recorded();
        AtomicReference<PrintResult> result = new AtomicReference<>();
        document.print(windowPrintingTo(new Printer(peer)), 200).thenAccept(result::set);
        ui.pumpUntil(() -> result.get() != null, 5_000_000_000L);
        assertEquals(PrintResult.PRINTED, result.get());
        assertNotNull(peer.job, "the system was asked");
        assertEquals("Order 4512", peer.job.title());
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void aFaceThatLoadsInTimeIsNotWaitedOnToTheDeadline() {
        Document document = Document.of(Paper.A5).title("Order 4512").add(new Label("Order 4512"));
        Recorded peer = new Recorded();
        AtomicReference<PrintResult> result = new AtomicReference<>();
        long began = System.nanoTime();
        document.print(windowPrintingTo(new Printer(peer)), 60_000).thenAccept(result::set);
        ui.runtime().drain();
        loading.complete(null);
        ui.pumpUntil(() -> result.get() != null, 5_000_000_000L);
        assertEquals(PrintResult.PRINTED, result.get());
        assertTrue(System.nanoTime() - began < 30_000_000_000L, "at the load, not at the deadline");
        assertEquals(List.of(), warnings, "nothing to say");
    }
}
