package limn.graphics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Degenerate geometry must draw nothing, not explode.
 *
 * <p>The border idiom every component uses,
 * {@code drawRoundRect(0.5f, 0.5f, width() - 1, height() - 1, …)}, produces a
 * negative size the instant a widget is laid out under 1 pt, which happens
 * routinely while a window is dragged small. Throwing there turned an ordinary
 * resize into a painting crash.
 */
class DegenerateGeometryTest {

    /** Canvas double that records which shape calls actually reached the backend. */
    private static final class RecordingCanvas implements Canvas {
        final List<String> calls = new ArrayList<>();

        @Override
        public float width() {
            return 100;
        }

        @Override
        public float height() {
            return 100;
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
        public void clipRect(float x, float y, float width, float height) {
        }

        @Override
        public void clipRoundRect(RoundRect roundRect) {
        }

        @Override
        public void fillRect(float x, float y, float width, float height, Paint paint) {
            calls.add("fillRect");
        }

        @Override
        public void drawRect(float x, float y, float width, float height, float strokeWidth, Paint paint) {
            calls.add("drawRect");
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            calls.add("fillRoundRect");
        }

        @Override
        public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
            calls.add("drawRoundRect");
        }

        @Override
        public void fillCircle(float cx, float cy, float radius, Paint paint) {
        }

        @Override
        public void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint) {
        }

        @Override
        public void fillEllipse(float cx, float cy, float radiusX, float radiusY, Paint paint) {
        }

        @Override
        public void drawEllipse(float cx, float cy, float radiusX, float radiusY,
                                float strokeWidth, Paint paint) {
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
            return new TextMetrics(text.length() * 7f, 8, 2, 12);
        }

        @Override
        public void drawImage(Image image, float x, float y, float w, float h) {
        }

        @Override
        public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                              float dstX, float dstY, float dstW, float dstH) {
        }

        @Override
        public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                              float dstX, float dstY, float dstW, float dstH, Color tint) {
        }

        @Override
        public void drawImage(Image image, float x, float y, float w, float h, Color tint) {
        }
    }

    private final RecordingCanvas canvas = new RecordingCanvas();
    private final Color ink = Color.rgb(0xFFFFFF);

    @Test
    void theBorderIdiomSurvivesASubPointWidget() {
        // Exactly what a widget does at height 0.7: height() - 1 == -0.3
        assertDoesNotThrow(() -> canvas.drawRoundRect(0.5f, 0.5f, 343f, -0.31f, 8, 1, ink));
        assertDoesNotThrow(() -> canvas.fillRoundRect(0.5f, 0.5f, -0.3f, 20f, 8, ink));
        assertTrue(canvas.calls.isEmpty(), "nothing should have reached the backend");
    }

    @Test
    void zeroSizeDrawsNothing() {
        canvas.drawRoundRect(0, 0, 0, 10, 4, 1, ink);
        canvas.fillRoundRect(0, 0, 10, 0, 4, ink);
        assertTrue(canvas.calls.isEmpty());
    }

    @Test
    void positiveSizeStillDraws() {
        canvas.fillRoundRect(0, 0, 10, 10, 4, ink);
        canvas.drawRoundRect(0, 0, 10, 10, 4, 1, ink);
        assertEquals(List.of("fillRoundRect", "drawRoundRect"), canvas.calls);
    }

    @Test
    void buildingARoundRectDirectlyStillValidates() {
        // The value type keeps its contract: genuinely malformed geometry
        // (swapped corners, a bad computation) is still caught at construction.
        assertThrows(IllegalArgumentException.class, () -> RoundRect.of(0, 0, 10, -1, 4));
    }
}
