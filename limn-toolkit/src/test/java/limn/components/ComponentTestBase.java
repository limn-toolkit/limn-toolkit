package limn.components;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.RoundRect;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Component tests run headless: a UiRuntime bound to the JUnit thread (thread
 * confinement), a deterministic monospace {@link #RULER} (every code point is
 * 10pt wide; ascent 8, descent 2, lineHeight 12) and a no-op {@link Canvas}
 * so full frames can run without GL.
 */
abstract class ComponentTestBase {

    /** 10pt per code point: ellipsis math becomes exact integers. */
    static final TextRuler RULER = (text, font) ->
            new TextMetrics(10f * (int) text.codePoints().count(), 8, 2, 12);

    /**
     * Ruler that scales with the font, for control-size tests. Uses the embedded Roboto's real
     * vertical ratios (em 2048, ascent 1900, descent -500, lineGap 0): ascent 0.927734em,
     * descent 0.244141em, lineHeight 1.171875em. Advance is a flat 0.6em per code point (real
     * advances vary; only the vertical metrics need to be faithful for size assertions).
     * {@link #RULER} stays font-blind so the existing exact-integer assertions hold unchanged.
     *
     * <p>This exists because <b>MEDIUM under the degenerate ruler was never the shipped
     * MEDIUM</b>: {@link #RULER} returns {@code lineHeight = 12} regardless of the font, so a
     * MEDIUM Button measures 28 there against 32.40625 in real rendering, and 32 once the
     * {@code max(controlHeight, ...)} floor lands. Every "MEDIUM is the identity" baseline must
     * be captured under <em>this</em> ruler; capturing one under {@link #RULER} pins the wrong
     * number.
     */
    static final TextRuler SCALED_RULER = (text, font) -> {
        float s = font.size();
        return new TextMetrics(0.6f * s * (int) text.codePoints().count(),
                0.927734375f * s, 0.244140625f * s, 1.171875f * s);
    };

    protected ExecutorService workers;
    protected UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        Theme.setCurrent(Theme.dark());
        // Must come after Ui.install: setProcessDefault is UI-thread-checked. And it only has
        // any effect at all because Scene subscribes its metrics listener in its CONSTRUCTOR;
        // with the subscription in bind() this reset was a silent no-op on every headless
        // scene in the suite.
        limn.scene.ControlSize.setProcessDefault(limn.scene.ControlSize.MEDIUM);
    }

    @AfterEach
    void uninstallRuntime() {
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    /**
     * Canvas that draws nothing; lets Scene.renderFrame run headless. Not final:
     * a test that needs to assert painted geometry overrides the one call it cares
     * about (see {@code SliderTest}'s pixel-grid test).
     */
    static class FakeCanvas implements Canvas {
        private final float width;
        private final float height;

        FakeCanvas(float width, float height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public float width() {
            return width;
        }

        @Override
        public float height() {
            return height;
        }

        @Override
        public float contentScale() {
            return 1;
        }

        @Override
        public void clear(Color color) {
        }
    private int savedDepth;

    @Override public void save() { savedDepth++; }
    @Override public void restore() { if (savedDepth > 0) { savedDepth--; } }
    @Override public int saveCount() { return savedDepth; }
    @Override public void restoreToCount(int count) { savedDepth = Math.max(0, Math.min(savedDepth, count)); }

        @Override
        public void translate(float dx, float dy) {
        }

        @Override
        public void scale(float sx, float sy) {
        }

        @Override
        public void rotate(float angleRadians) {
        }

        @Override
        public void setOpacity(float opacity) {
        }

        @Override
        public float opacity() {
            return 1;
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
        }

        @Override
        public void clipRoundRect(RoundRect roundRect) {
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
        }

        @Override
        public void drawRect(float x, float y, float w, float h, float strokeWidth, Paint paint) {
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
        }

        @Override
        public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
        }

        @Override
        public void fillCircle(float cx, float cy, float radius, Paint paint) {
        }

        @Override
        public void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint) {
        }

        @Override
        public void fillEllipse(float cx, float cy, float rx, float ry, Paint paint) {
        }

        @Override
        public void drawEllipse(float cx, float cy, float rx, float ry, float strokeWidth, Paint paint) {
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
        }

        @Override
        public void fillPath(Path2D path, Paint paint) {
        }

        @Override
        public void drawPath(Path2D path, float strokeWidth, Paint paint) {
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
        }

        @Override
        public TextMetrics measureText(String text, Font font) {
            return RULER.measure(text, font);
        }

        @Override
        public void drawImage(limn.graphics.Image image, float x, float y, float w, float h) {
        }

        @Override
        public void drawImage(limn.graphics.Image image, float x, float y, float w, float h, Color tint) {
        }

        @Override
        public void drawImage(limn.graphics.Image image,
                              float srcX, float srcY, float srcW, float srcH,
                              float dstX, float dstY, float dstW, float dstH) {
        }

        @Override
        public void drawImage(limn.graphics.Image image,
                              float srcX, float srcY, float srcW, float srcH,
                              float dstX, float dstY, float dstW, float dstH, Color tint) {
        }
    }
}
