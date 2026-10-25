package limn.print;

import limn.backend.spi.GlyphOutlines;
import limn.components.Label;
import limn.graphics.Canvas;
import limn.graphics.Font;
import limn.graphics.Path2D;
import limn.graphics.TextRulers;
import limn.print.internal.Outlines;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.testing.HeadlessUi;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A preview whose pages are recorded again once a face has loaded, and fail then, keeps showing
 * the pages recorded before and says why. A header that measures taller in the face that loaded
 * can leave less than the quarter inch a page needs; the throw happened in a posted task, where it
 * reached nobody but the loop's crash log, and the preview, which shows nothing while a face loads,
 * went on showing nothing.
 */
class PrintPreviewFailureTest {

    private HeadlessUi ui;
    private CompletableFuture<Void> loading;
    private GlyphOutlines outlines;
    /** How tall the header measures: in the face the text has before the load, and after. */
    private final float[] headerHeight = {30};

    /** A leaf as tall as the header is said to be now. */
    private final class Header extends Widget<Header> {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(10, headerHeight[0]);
        }

        @Override
        protected void onPaint(Canvas canvas) {
        }
    }

    @BeforeEach
    void start() {
        ui = new HeadlessUi();
        TextRulers.install(TestRulers.FIXED);
        loading = new CompletableFuture<>();
        outlines = new GlyphOutlines() {
            @Override
            public int unitsPerEm(int faceId) {
                return 0;
            }

            @Override
            public String faceName(int faceId) {
                return "None";
            }

            @Override
            public boolean isSyntheticBold(int faceId) {
                return false;
            }

            @Override
            public boolean outline(int faceId, int glyphId, Path2D.Visitor out) {
                return false;
            }

            @Override
            public List<Piece> cluster(String characters, Font font) {
                return List.of();
            }

            @Override
            public CompletionStage<Void> settled() {
                return loading;
            }
        };
        Outlines.install(outlines);
    }

    @AfterEach
    void stop() {
        Outlines.uninstall(outlines);
        TextRulers.uninstall(TestRulers.FIXED);
        ui.close();
    }

    @Test
    void aRecordingMadeAgainThatFailsKeepsThePagesBeforeAndSaysWhy() {
        // A6 on its side keeps 252.9 units between its default margins: a header of 30 leaves room,
        // one of 240 leaves less than the quarter inch.
        Document document = Document.of(Paper.A6.landscape())
                .header(page -> new Header())
                .add(new Label("Invoice 4512"));
        PrintPreview preview = new PrintPreview(document);
        List<Change> said = new ArrayList<>();
        preview.observeChanges((widget, change) -> {
            if (change.aspect() == Change.Aspect.VALIDITY) {
                said.add(change);
            }
        });
        assertEquals(0, preview.recordedPages(), "nothing shown while a face loads");

        headerHeight[0] = 240; // the face that loads sets the header taller
        loading.complete(null);
        ui.pumpUntil(() -> preview.failure() != null || preview.recordedPages() > 0);
        ui.runtime().drain();
        assertEquals(1, preview.recordedPages(), "the pages recorded before the face loaded are shown");
        IllegalStateException why = assertInstanceOf(IllegalStateException.class, preview.failure());
        assertTrue(why.getMessage().contains("quarter inch"), why.getMessage());
        assertEquals(1, said.size(), "announced once");
        assertEquals(Change.Origin.CODE, said.get(0).origin());

        headerHeight[0] = 30;
        preview.refresh();
        assertNull(preview.failure(), "a recording that succeeds clears it");
        assertEquals(2, said.size(), "and says so");

        headerHeight[0] = 240;
        assertThrows(IllegalStateException.class, preview::refresh, "a recording made in the call throws to it");
        assertNull(preview.failure(), "and leaves the preview as it was");
        assertEquals(1, preview.recordedPages());
    }
}
