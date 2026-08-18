package limn.components;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.Rect;
import limn.graphics.RoundRect;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Caret blink under partial rendering: the 2 Hz toggle must damage only the
 * caret column (via {@code caretRect}), not the whole field: the flagship
 * "stop repainting the window for a blinking cursor" case. Runs on a
 * controllable clock so the {@code Ui.postDelayed} blink chain can be fired
 * deterministically.
 */
class CaretDamageTest {

    private static final TextRuler RULER = (text, font) ->
            new TextMetrics(10f * (int) text.codePoints().count(), 8, 2, 12);

    /**
     * Records clear/clip/fill so a frame can be classified full vs partial.
     * Only the FIRST clip matters: it is the scene's partial-repaint clip;
     * widgets push their own clips later (TextField clips its text run).
     */
    static final class RecordingCanvas implements Canvas {
        boolean cleared;
        Rect firstClip;
        int paints;

        @Override public float width() { return 240; }
        @Override public float height() { return 32; }
        @Override public float contentScale() { return 1; }
        @Override public void clear(Color color) { cleared = true; }
    private int savedDepth;

    @Override public void save() { savedDepth++; }
    @Override public void restore() { if (savedDepth > 0) { savedDepth--; } }
    @Override public int saveCount() { return savedDepth; }
    @Override public void restoreToCount(int count) { savedDepth = Math.max(0, Math.min(savedDepth, count)); }
        @Override public void translate(float dx, float dy) { }
        @Override public void scale(float sx, float sy) { }
        @Override public void rotate(float angleRadians) { }
        @Override public void setOpacity(float opacity) { }
        @Override public float opacity() { return 1; }
        @Override public void clipRect(float x, float y, float w, float h) {
            if (firstClip == null) {
                firstClip = new Rect(x, y, w, h);
            }
        }
        @Override public void clipRoundRect(RoundRect roundRect) { }
        @Override public void fillRect(float x, float y, float w, float h, Paint paint) { paints++; }
        @Override public void drawRect(float x, float y, float w, float h, float sw, Paint paint) { paints++; }
        @Override public void fillRoundRect(RoundRect roundRect, Paint paint) { paints++; }
        @Override public void drawRoundRect(RoundRect roundRect, float sw, Paint paint) { paints++; }
        @Override public void fillCircle(float cx, float cy, float radius, Paint paint) { paints++; }
        @Override public void drawCircle(float cx, float cy, float radius, float sw, Paint paint) { paints++; }
        @Override public void fillEllipse(float cx, float cy, float rx, float ry, Paint paint) { paints++; }
        @Override public void drawEllipse(float cx, float cy, float rx, float ry, float sw, Paint paint) { paints++; }
        @Override public void drawLine(float x1, float y1, float x2, float y2, float sw, Paint paint) { paints++; }
        @Override public void fillPath(Path2D path, Paint paint) { paints++; }
        @Override public void drawPath(Path2D path, float sw, Paint paint) { paints++; }
        @Override public void drawText(String text, float x, float y, Font font, Paint paint) { paints++; }
        @Override public TextMetrics measureText(String text, Font font) { return RULER.measure(text, font); }
        @Override public void drawImage(Image image, float x, float y, float w, float h) { paints++; }
        @Override public void drawImage(Image image, float x, float y, float w, float h, Color tint) { paints++; }
        @Override public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                                        float dstX, float dstY, float dstW, float dstH) { paints++; }

        @Override public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                                        float dstX, float dstY, float dstW, float dstH,
                                        Color tint) { paints++; }

        void reset() {
            cleared = false;
            firstClip = null;
            paints = 0;
        }
    }

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private ExecutorService workers;
    private UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(nanos::get, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        Theme.setCurrent(Theme.dark());
    }

    @AfterEach
    void uninstallRuntime() {
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    /** Viewport-like container clipping one child placed at a fixed offset. */
    static final class ClippingViewport extends limn.scene.Widget {
        private final limn.scene.Widget child;
        private final float childY;

        ClippingViewport(limn.scene.Widget child, float childY) {
            this.child = child;
            this.childY = childY;
            add(child);
        }

        @Override
        protected boolean clipsChildren() {
            return true;
        }

        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints constraints) {
            return constraints.constrain(240, 32);
        }

        @Override
        protected void onLayout() {
            child.measure(limn.scene.Constraints.tight(240, 32));
            child.layoutBox(0, childY, 240, 32);
        }
    }

    @Test
    void caretBlinkInAClippedAwayFieldDamagesNothing() {
        TextField field = new TextField();
        ClippingViewport viewport = new ClippingViewport(field, -64); // scrolled fully out of view
        Scene scene = new Scene(viewport, nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        scene.layoutPass(240, 32);
        scene.requestFocus(field); // focus (and the blink chain) work while clipped

        RecordingTestCanvas canvas = new RecordingTestCanvas(240, 32);
        boolean settled = false;
        for (int i = 0; i < 30 && !settled; i++) {
            nanos.addAndGet(200_000_000L);
            runtime.drain();
            canvas.reset();
            scene.renderFrame(canvas);
            settled = canvas.nothingPainted();
        }
        assertTrue(settled, "focus fade must settle without visible painting");

        nanos.addAndGet(600_000_000L);
        runtime.drain(); // blink toggle fires: the caret is clipped away
        canvas.reset();
        scene.renderFrame(canvas);
        assertTrue(canvas.nothingPainted(),
                "a caret blinking outside the viewport must not damage anything");
    }

    @Test
    void caretBlinkDamagesOnlyTheCaretColumn() {
        TextField field = new TextField();
        Scene scene = new Scene(field, nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        scene.layoutPass(240, 32);
        scene.requestFocus(field); // starts the focus fade and the blink chain

        // Let the focus-ring transition finish and the damage history drain.
        RecordingCanvas canvas = new RecordingCanvas();
        boolean settled = false;
        for (int i = 0; i < 30 && !settled; i++) {
            nanos.addAndGet(200_000_000L);
            runtime.drain();
            canvas.reset();
            scene.renderFrame(canvas);
            settled = !canvas.cleared && canvas.firstClip == null && canvas.paints == 0;
        }
        assertTrue(settled, "focused idle field must stop painting entirely");

        // Fire the next blink toggle (BLINK_SECONDS = 0.5s).
        nanos.addAndGet(600_000_000L);
        runtime.drain();
        canvas.reset();
        scene.renderFrame(canvas);

        assertTrue(canvas.firstClip != null && !canvas.cleared,
                "blink must be a partial frame, not a full one");
        assertTrue(canvas.firstClip.width() <= 10,
                "blink damage must be the caret column, got " + canvas.firstClip);
        assertTrue(canvas.paints > 0, "the caret region must actually repaint");
    }
}
