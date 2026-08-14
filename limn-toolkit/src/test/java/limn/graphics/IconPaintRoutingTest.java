package limn.graphics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Icon#paint} must route a <em>tintable</em> icon through the coverage-mask
 * path ({@link Canvas#drawImageMask}) so it recolors regardless of source color,
 * and draw a non-tintable picture untinted. It also requests the bitmap at the
 * device resolution ({@code size × contentScale}).
 */
class IconPaintRoutingTest {

    private record StubIcon(boolean tint, int[] requestedPx) implements Icon {
        @Override
        public Image image(int pixelSize, boolean dark) {
            requestedPx[0] = pixelSize;
            return new Image(1, 1, new byte[4]);
        }

        @Override
        public boolean tintable() {
            return tint;
        }
    }

    @Test
    void tintableIconUsesTheCoverageMaskPathAtDeviceSize() {
        RecordingCanvas c = new RecordingCanvas(2f);
        int[] px = new int[1];
        new StubIcon(true, px).paint(c, 0, 0, 16, Color.rgb(0xFF0000), false);

        assertEquals(1, c.maskCalls, "tintable → drawImageMask (coverage tint)");
        assertEquals(0, c.tintedImageCalls, "must not take the multiply path");
        assertEquals(0, c.plainImageCalls);
        assertEquals(32, px[0], "requested at 16pt × 2.0 device scale");
    }

    @Test
    void nonTintableIconDrawsThePictureUntinted() {
        RecordingCanvas c = new RecordingCanvas(2f);
        new StubIcon(false, new int[1]).paint(c, 0, 0, 16, Color.rgb(0xFF0000), false);

        assertEquals(0, c.maskCalls);
        assertEquals(0, c.tintedImageCalls);
        assertEquals(1, c.plainImageCalls, "non-tintable → drawImage untinted");
    }

    /** Records the image-draw calls; every other Canvas method is an inert stub. */
    private static class RecordingCanvas implements Canvas {
        final float scale;
        int maskCalls;
        int tintedImageCalls;
        int plainImageCalls;

        RecordingCanvas(float scale) {
            this.scale = scale;
        }

        @Override
        public float contentScale() {
            return scale;
        }

        @Override
        public void drawImageMask(Image image, float x, float y, float w, float h, Color tint) {
            maskCalls++;
        }

        @Override
        public void drawImage(Image image, float x, float y, float w, float h, Color tint) {
            tintedImageCalls++;
        }

        @Override
        public void drawImage(Image image, float x, float y, float w, float h) {
            plainImageCalls++;
        }

        @Override
        public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                              float dstX, float dstY, float dstW, float dstH) {
            plainImageCalls++;
        }

        @Override
        public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                              float dstX, float dstY, float dstW, float dstH, Color tint) {
            plainImageCalls++;
        }

        // ---- inert stubs (not exercised by Icon.paint) ----
        @Override public float width() { return 0; }
        @Override public float height() { return 0; }
        @Override public void clear(Color color) { }
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
        @Override public void clipRect(float x, float y, float width, float height) { }
        @Override public void clipRoundRect(RoundRect roundRect) { }
        @Override public void fillRect(float x, float y, float width, float height, Paint paint) { }
        @Override public void drawRect(float x, float y, float width, float height, float sw, Paint paint) { }
        @Override public void fillRoundRect(RoundRect roundRect, Paint paint) { }
        @Override public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) { }
        @Override public void fillCircle(float cx, float cy, float radius, Paint paint) { }
        @Override public void drawCircle(float cx, float cy, float radius, float sw, Paint paint) { }
        @Override public void fillEllipse(float cx, float cy, float rx, float ry, Paint paint) { }
        @Override public void drawEllipse(float cx, float cy, float rx, float ry, float sw, Paint paint) { }
        @Override public void drawLine(float x1, float y1, float x2, float y2, float sw, Paint paint) { }
        @Override public void fillPath(Path2D path, Paint paint) { }
        @Override public void drawPath(Path2D path, float strokeWidth, Paint paint) { }
        @Override public void drawText(String text, float x, float y, Font font, Paint paint) { }
        @Override public TextMetrics measureText(String text, Font font) { return null; }
    }
}
