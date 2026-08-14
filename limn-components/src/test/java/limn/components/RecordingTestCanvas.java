package limn.components;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.Rect;
import limn.graphics.RoundRect;
import limn.graphics.TextMetrics;

/**
 * Records clear/clip/paint calls so a frame can be classified full vs partial.
 * Only the FIRST clip matters: it is the scene's partial-repaint pass clip;
 * widgets push their own clips later (text fields clip their text runs).
 *
 * <p>{@code drawSurface} is recorded too, and overriding it is the whole point:
 * it is a default method whose default draws <em>nothing at all</em>, so a canvas
 * that leaves it alone swallows every GPU surface silently.
 */
final class RecordingTestCanvas implements Canvas {

    /** One recorded {@code drawSurface}: the surface and its rectangle, in logical points. */
    record SurfaceDraw(limn.graphics.GpuSurface surface, float x, float y,
                       float width, float height) {
    }

    private final float width;
    private final float height;
    /** Device pixels per logical point; the letterbox snaps sub-device-pixel remainders. */
    float contentScale = 1;
    boolean cleared;
    Rect firstClip;
    /**
     * Every clip pushed during the frame, in order. {@link #firstClip} is the scene's own
     * repaint-pass clip and is therefore the same size as the frame on a full pass, so a test
     * asking whether a particular WIDGET clipped has to look past it, at a rectangle whose size is
     * that widget's rather than the frame's.
     */
    final java.util.List<Rect> clips = new java.util.ArrayList<>();
    int paints;
    final java.util.List<SurfaceDraw> surfaces = new java.util.ArrayList<>();

    RecordingTestCanvas(float width, float height) {
        this.width = width;
        this.height = height;
    }

    void reset() {
        cleared = false;
        firstClip = null;
        paints = 0;
        surfaces.clear();
        clips.clear();
        saveDepth = 0;
    }

    boolean nothingPainted() {
        return !cleared && firstClip == null && paints == 0;
    }

    /** @return the only surface draw recorded; fails the caller's expectation otherwise */
    SurfaceDraw onlySurface() {
        if (surfaces.size() != 1) {
            throw new AssertionError("expected exactly one surface draw, got " + surfaces);
        }
        return surfaces.get(0);
    }

    @Override public float width() { return width; }
    @Override public float height() { return height; }
    @Override public float contentScale() { return contentScale; }

    @Override public void drawSurface(limn.graphics.GpuSurface surface,
                                      float x, float y, float w, float h) {
        surfaces.add(new SurfaceDraw(surface, x, y, w, h));
        paints++;
    }
    @Override public void clear(Color color) { cleared = true; }
    /**
     * How many {@code save()}s are outstanding. The real canvas keeps the same count and warns at
     * the end of a frame when it is not zero, but by then the widget that leaked it is long out of
     * the stack, so the warning names nobody. Counting it here is what lets a test name the widget.
     */
    int saveDepth;

    @Override public void save() {
        saveDepth++;
    }

    @Override public void restore() {
        if (saveDepth > 0) {
            saveDepth--;
        }
    }

    @Override public int saveCount() {
        return saveDepth;
    }

    @Override public void restoreToCount(int count) {
        saveDepth = Math.max(0, Math.min(saveDepth, count));
    }
    @Override public void translate(float dx, float dy) { }
    @Override public void scale(float sx, float sy) { }
    @Override public void rotate(float angleRadians) { }
    @Override public void setOpacity(float opacity) { }
    @Override public float opacity() { return 1; }
    @Override public void clipRect(float x, float y, float w, float h) {
        if (firstClip == null) {
            firstClip = new Rect(x, y, w, h);
        }
        clips.add(new Rect(x, y, w, h));
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
    @Override public TextMetrics measureText(String text, Font font) {
        return new TextMetrics(10f * (int) text.codePoints().count(), 8, 2, 12);
    }
    @Override public void drawImage(Image image, float x, float y, float w, float h) { paints++; }
    @Override public void drawImage(Image image, float x, float y, float w, float h, Color tint) { paints++; }
    @Override public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                                    float dstX, float dstY, float dstW, float dstH) { paints++; }

    @Override public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                                    float dstX, float dstY, float dstW, float dstH,
                                    Color tint) { paints++; }
}
