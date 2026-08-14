package limn.graphics;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Canvas} that records the {@link Canvas#drawSurface} calls made
 * against it and does nothing else: the seam a widget draws a GPU surface
 * through, without a GPU.
 *
 * <p>Overriding {@code drawSurface} is the whole point: it is a default method
 * whose default is to draw <em>nothing at all</em>, so a canvas that does not
 * override it swallows every surface silently. Public, so tests of any package
 * in this module can use it, and subclassable so a test that needs to record
 * one more call does not have to restate every stub in this file.
 */
public class SurfaceRecordingCanvas implements Canvas {

    /** One recorded call: the surface and the rectangle it was asked to fill, in points. */
    public record Draw(GpuSurface surface, float x, float y, float width, float height) {
    }

    private final float width;
    private final float height;
    private final List<Draw> draws = new ArrayList<>();

    public SurfaceRecordingCanvas(float width, float height) {
        this.width = width;
        this.height = height;
    }

    /** @return the surface draws recorded so far, in order */
    public List<Draw> draws() {
        return List.copyOf(draws);
    }

    @Override
    public void drawSurface(GpuSurface surface, float x, float y, float w, float h) {
        draws.add(new Draw(surface, x, y, w, h));
    }

    @Override public float width() {
        return width;
    }

    @Override public float height() {
        return height;
    }

    @Override public float contentScale() {
        return 1;
    }

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

    @Override public float opacity() {
        return 1;
    }

    @Override public void clipRect(float x, float y, float w, float h) { }

    @Override public void clipRoundRect(RoundRect roundRect) { }

    @Override public void fillRect(float x, float y, float w, float h, Paint paint) { }

    @Override public void drawRect(float x, float y, float w, float h, float strokeWidth, Paint paint) { }

    @Override public void fillRoundRect(RoundRect roundRect, Paint paint) { }

    @Override public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) { }

    @Override public void fillCircle(float cx, float cy, float radius, Paint paint) { }

    @Override public void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint) { }

    @Override public void fillEllipse(float cx, float cy, float radiusX, float radiusY, Paint paint) { }

    @Override public void drawEllipse(float cx, float cy, float radiusX, float radiusY,
                                      float strokeWidth, Paint paint) { }

    @Override public void drawLine(float x1, float y1, float x2, float y2,
                                   float strokeWidth, Paint paint) { }

    @Override public void fillPath(Path2D path, Paint paint) { }

    @Override public void drawPath(Path2D path, float strokeWidth, Paint paint) { }

    @Override public void drawText(String text, float x, float y, Font font, Paint paint) { }

    @Override public TextMetrics measureText(String text, Font font) {
        return new TextMetrics(0, 8, 2, 12);
    }

    @Override public void drawImage(Image image, float x, float y, float w, float h) { }

    @Override public void drawImage(Image image, float x, float y, float w, float h, Color tint) { }

    @Override public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                                    float dstX, float dstY, float dstW, float dstH) { }

    @Override public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                                    float dstX, float dstY, float dstW, float dstH, Color tint) { }
}
