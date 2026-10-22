package limn.print.internal;

import limn.graphics.BlendMode;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.GpuSurface;
import limn.graphics.Image;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.Rect;
import limn.graphics.RoundRect;
import limn.graphics.Sampling;
import limn.graphics.Shadow;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.graphics.TextRulers;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * The canvas a page is painted into: it keeps every call in a {@link Recording} and answers what a
 * painter asks of a canvas (its size, its scale, its state, where its clip is) as a canvas that draws
 * would, so a scene paints a page exactly as it paints a window.
 *
 * <p>What it says it is: the frame size of the scene being painted now ({@link #setFrame}), which the
 * scene lays out to, and a content scale of the document's raster resolution over 96, so an icon
 * drawn as a bitmap is drawn at the resolution of print (ADR 078 §5.1). What it does not keep:
 * {@code clear} and {@code clearRect}, which wipe a window's frame and have nothing to wipe on paper,
 * and {@code drawSurface}, whose GPU texture no page can hold (§7.3); the first of those per page is
 * logged.
 *
 * <p>UI thread only, as every canvas a scene paints into.
 */
public final class RecordingCanvas implements Canvas {

    private static final System.Logger LOG = System.getLogger(RecordingCanvas.class.getName());

    private final Recording.Builder out;
    private final GlyphBank glyphs;
    private final float contentScale;
    private float frameWidth;
    private float frameHeight;
    private boolean surfaceLogged;

    private State state = new State(Affine.IDENTITY, null, 1, BlendMode.NORMAL, Sampling.SMOOTH, true);
    private final Deque<State> saved = new ArrayDeque<>();

    /**
     * @param pageWidth    the page's width, in document units
     * @param pageHeight   its height
     * @param contentScale what the canvas answers as its scale: the raster resolution over 96
     * @param glyphs       where the outlines of the text recorded are kept for the PDF writer
     */
    public RecordingCanvas(float pageWidth, float pageHeight, float contentScale, GlyphBank glyphs) {
        this.out = new Recording.Builder(pageWidth, pageHeight);
        this.glyphs = Objects.requireNonNull(glyphs, "glyphs");
        this.contentScale = contentScale;
        this.frameWidth = pageWidth;
        this.frameHeight = pageHeight;
    }

    /**
     * Sets the size the canvas answers, for the scene painted next: a scene lays itself out to its
     * canvas's size.
     *
     * @param width  the frame's width
     * @param height its height
     */
    public void setFrame(float width, float height) {
        this.frameWidth = width;
        this.frameHeight = height;
    }

    /** @return what has been recorded, as a page */
    public Recording finish() {
        restoreToCount(0);
        return out.build();
    }

    // ------------------------------------------------------------------ frame

    @Override
    public float width() {
        return frameWidth;
    }

    @Override
    public float height() {
        return frameHeight;
    }

    @Override
    public float contentScale() {
        return contentScale;
    }

    @Override
    public void clear(Color color) {
        // Paper is not cleared: the page is white, and a scene's background is the window's.
    }

    @Override
    public void clearRect(float x, float y, float width, float height, Color color) {
        // As clear: nothing to wipe.
    }

    // ------------------------------------------------------------------ state

    @Override
    public void save() {
        saved.push(state);
        out.add(Canvas::save);
    }

    @Override
    public void restore() {
        if (saved.isEmpty()) {
            return;
        }
        state = saved.pop();
        out.add(Canvas::restore);
    }

    @Override
    public int saveCount() {
        return saved.size();
    }

    @Override
    public void restoreToCount(int count) {
        while (saved.size() > Math.max(0, count)) {
            restore();
        }
    }

    @Override
    public void translate(float dx, float dy) {
        state = state.with(state.transform.translate(dx, dy));
        out.add(c -> c.translate(dx, dy));
    }

    @Override
    public void scale(float sx, float sy) {
        state = state.with(state.transform.scale(sx, sy));
        out.add(c -> c.scale(sx, sy));
    }

    @Override
    public void rotate(float angleRadians) {
        state = state.with(state.transform.rotate(angleRadians));
        out.add(c -> c.rotate(angleRadians));
    }

    @Override
    public void setOpacity(float opacity) {
        float clamped = Math.max(0, Math.min(1, opacity));
        state = new State(state.transform, state.clip, state.opacity * clamped, state.blend, state.sampling,
                state.snap);
        out.add(c -> c.setOpacity(clamped));
    }

    @Override
    public float opacity() {
        return state.opacity;
    }

    @Override
    public void saveLayer(float x, float y, float width, float height, float opacity) {
        saved.push(state);
        // Inside a layer the opacity starts again from 1, and the drawing from the normal mode.
        state = new State(state.transform, state.clip, 1, BlendMode.NORMAL, state.sampling, state.snap);
        out.add(c -> c.saveLayer(x, y, width, height, opacity));
    }

    @Override
    public void setBlendMode(BlendMode mode) {
        Objects.requireNonNull(mode, "mode");
        state = new State(state.transform, state.clip, state.opacity, mode, state.sampling, state.snap);
        out.add(c -> c.setBlendMode(mode));
    }

    @Override
    public BlendMode blendMode() {
        return state.blend;
    }

    @Override
    public void setSampling(Sampling sampling) {
        Objects.requireNonNull(sampling, "sampling");
        state = new State(state.transform, state.clip, state.opacity, state.blend, sampling, state.snap);
        out.add(c -> c.setSampling(sampling));
    }

    @Override
    public Sampling sampling() {
        return state.sampling;
    }

    @Override
    public void setPixelSnap(boolean snap) {
        state = new State(state.transform, state.clip, state.opacity, state.blend, state.sampling, snap);
        out.add(c -> c.setPixelSnap(snap));
    }

    @Override
    public boolean pixelSnap() {
        return state.snap;
    }

    // ------------------------------------------------------------------ clip

    @Override
    public void clipRect(float x, float y, float width, float height) {
        narrow(x, y, width, height);
        out.add(c -> c.clipRect(x, y, width, height));
    }

    @Override
    public void clipRoundRect(RoundRect roundRect) {
        Objects.requireNonNull(roundRect, "roundRect");
        narrow(roundRect.x(), roundRect.y(), roundRect.width(), roundRect.height());
        out.add(c -> c.clipRoundRect(roundRect));
    }

    /** Intersects the tracked clip, kept as a box on the page, with a rectangle's box on the page. */
    private void narrow(float x, float y, float width, float height) {
        Affine t = state.transform;
        double x0 = Double.MAX_VALUE;
        double y0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y1 = -Double.MAX_VALUE;
        double[][] corners = {{x, y}, {x + width, y}, {x, y + height}, {x + width, y + height}};
        for (double[] p : corners) {
            double px = t.x(p[0], p[1]);
            double py = t.y(p[0], p[1]);
            x0 = Math.min(x0, px);
            y0 = Math.min(y0, py);
            x1 = Math.max(x1, px);
            y1 = Math.max(y1, py);
        }
        Rect box = new Rect((float) x0, (float) y0, (float) Math.max(0, x1 - x0), (float) Math.max(0, y1 - y0));
        Rect clip = state.clip == null ? box : intersect(state.clip, box);
        state = new State(state.transform, clip, state.opacity, state.blend, state.sampling, state.snap);
    }

    private static Rect intersect(Rect a, Rect b) {
        float x0 = Math.max(a.x(), b.x());
        float y0 = Math.max(a.y(), b.y());
        float x1 = Math.min(a.x() + a.width(), b.x() + b.width());
        float y1 = Math.min(a.y() + a.height(), b.y() + b.height());
        return new Rect(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    @Override
    public @Nullable Rect clipBounds() {
        Rect clip = state.clip;
        Affine t = state.transform;
        if (clip == null || !t.axisAligned() || Math.abs(t.a()) < 1e-9 || Math.abs(t.d()) < 1e-9) {
            return null;
        }
        double xa = (clip.x() - t.e()) / t.a();
        double xb = (clip.x() + clip.width() - t.e()) / t.a();
        double ya = (clip.y() - t.f()) / t.d();
        double yb = (clip.y() + clip.height() - t.f()) / t.d();
        return new Rect((float) Math.min(xa, xb), (float) Math.min(ya, yb),
                (float) Math.abs(xb - xa), (float) Math.abs(yb - ya));
    }

    // ------------------------------------------------------------------ shapes

    @Override
    public void fillRect(float x, float y, float width, float height, Paint paint) {
        Objects.requireNonNull(paint, "paint");
        if (outside(x, y, width, height)) {
            return;
        }
        out.add(c -> c.fillRect(x, y, width, height, paint));
    }

    @Override
    public void drawRect(float x, float y, float width, float height, float strokeWidth, Paint paint) {
        Objects.requireNonNull(paint, "paint");
        out.add(c -> c.drawRect(x, y, width, height, strokeWidth, paint));
    }

    @Override
    public void fillRoundRect(RoundRect roundRect, Paint paint) {
        Objects.requireNonNull(roundRect, "roundRect");
        Objects.requireNonNull(paint, "paint");
        out.add(c -> c.fillRoundRect(roundRect, paint));
    }

    @Override
    public void drawShadow(RoundRect shape, Shadow shadow) {
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(shadow, "shadow");
        out.add(c -> c.drawShadow(shape, shadow));
    }

    @Override
    public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
        Objects.requireNonNull(roundRect, "roundRect");
        Objects.requireNonNull(paint, "paint");
        out.add(c -> c.drawRoundRect(roundRect, strokeWidth, paint));
    }

    @Override
    public void fillCircle(float cx, float cy, float radius, Paint paint) {
        Objects.requireNonNull(paint, "paint");
        out.add(c -> c.fillCircle(cx, cy, radius, paint));
    }

    @Override
    public void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint) {
        Objects.requireNonNull(paint, "paint");
        out.add(c -> c.drawCircle(cx, cy, radius, strokeWidth, paint));
    }

    @Override
    public void fillEllipse(float cx, float cy, float radiusX, float radiusY, Paint paint) {
        Objects.requireNonNull(paint, "paint");
        out.add(c -> c.fillEllipse(cx, cy, radiusX, radiusY, paint));
    }

    @Override
    public void drawEllipse(float cx, float cy, float radiusX, float radiusY, float strokeWidth, Paint paint) {
        Objects.requireNonNull(paint, "paint");
        out.add(c -> c.drawEllipse(cx, cy, radiusX, radiusY, strokeWidth, paint));
    }

    @Override
    public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
        Objects.requireNonNull(paint, "paint");
        out.add(c -> c.drawLine(x1, y1, x2, y2, strokeWidth, paint));
    }

    @Override
    public void fillPath(Path2D path, Paint paint) {
        Objects.requireNonNull(paint, "paint");
        Path2D kept = path.copy();
        out.add(c -> c.fillPath(kept, paint));
    }

    @Override
    public void drawPath(Path2D path, float strokeWidth, Paint paint) {
        Objects.requireNonNull(paint, "paint");
        Path2D kept = path.copy();
        out.add(c -> c.drawPath(kept, strokeWidth, paint));
    }

    // ------------------------------------------------------------------ text

    @Override
    public void drawText(String text, float x, float y, Font font, Paint paint) {
        Objects.requireNonNull(font, "font");
        Objects.requireNonNull(paint, "paint");
        if (text == null || text.isEmpty()) {
            return;
        }
        // Shaped here, once, as a canvas that draws shapes it: the page keeps glyphs, never strings.
        drawText(ruler().shape(text, font), x, y, paint);
    }

    @Override
    public void drawText(ShapedText text, float x, float y, Paint paint) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(paint, "paint");
        if (text.glyphCount() == 0) {
            return;
        }
        TextMetrics metrics = text.metrics();
        if (outside(x, y - metrics.ascent(), metrics.width(), metrics.ascent() + metrics.descent())) {
            // A line a page's clip hides entirely is not kept: a reader extracting the page's text
            // reads a clipped line as well as a shown one, and a paragraph cut across pages would
            // be read whole on every page it touches.
            return;
        }
        glyphs.collect(text);
        out.add(c -> c.drawText(text, x, y, paint));
    }

    @Override
    public TextMetrics measureText(String text, Font font) {
        Objects.requireNonNull(font, "font");
        return ruler().shape(text == null ? "" : text, font).metrics();
    }

    private static TextRuler ruler() {
        return TextRulers.get();
    }

    // ------------------------------------------------------------------ images

    /**
     * Whether a box in current coordinates lies wholly outside the clip, so that drawing it would
     * leave nothing on the page.
     */
    private boolean outside(float x, float y, float width, float height) {
        Rect clip = state.clip;
        if (clip == null) {
            return false;
        }
        Affine t = state.transform;
        double x0 = Double.MAX_VALUE;
        double y0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y1 = -Double.MAX_VALUE;
        double[][] corners = {{x, y}, {x + width, y}, {x, y + height}, {x + width, y + height}};
        for (double[] p : corners) {
            double px = t.x(p[0], p[1]);
            double py = t.y(p[0], p[1]);
            x0 = Math.min(x0, px);
            y0 = Math.min(y0, py);
            x1 = Math.max(x1, px);
            y1 = Math.max(y1, py);
        }
        return x1 <= clip.x() || x0 >= clip.x() + clip.width() || y1 <= clip.y() || y0 >= clip.y() + clip.height();
    }

    @Override
    public void drawImage(Image image, float x, float y, float w, float h) {
        Objects.requireNonNull(image, "image");
        if (outside(x, y, w, h)) {
            return;
        }
        out.add(c -> c.drawImage(image, x, y, w, h));
    }

    @Override
    public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                          float dstX, float dstY, float dstW, float dstH) {
        Objects.requireNonNull(image, "image");
        out.add(c -> c.drawImage(image, srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH));
    }

    @Override
    public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                          float dstX, float dstY, float dstW, float dstH, Color tint) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(tint, "tint");
        out.add(c -> c.drawImage(image, srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH, tint));
    }

    @Override
    public void drawImage(Image image, float x, float y, float w, float h, Color tint) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(tint, "tint");
        out.add(c -> c.drawImage(image, x, y, w, h, tint));
    }

    @Override
    public void drawImageMask(Image image, float x, float y, float w, float h, Color tint) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(tint, "tint");
        out.add(c -> c.drawImageMask(image, x, y, w, h, tint));
    }

    @Override
    public void drawSurface(GpuSurface surface, float x, float y, float w, float h) {
        // A GPU texture (a video's frame, a 3D view) has no picture a page can hold: it is left out,
        // said once per page (ADR 078 §7.3).
        if (!surfaceLogged) {
            surfaceLogged = true;
            LOG.log(System.Logger.Level.WARNING, "a video or 3D view is left out of a printed page: a GPU "
                    + "surface has no picture a page can hold; print an Image of it instead");
        }
    }

    /** What a save keeps. */
    private record State(Affine transform, @Nullable Rect clip, float opacity, BlendMode blend,
                         Sampling sampling, boolean snap) {
        State with(Affine moved) {
            return new State(moved, clip, opacity, blend, sampling, snap);
        }
    }
}
