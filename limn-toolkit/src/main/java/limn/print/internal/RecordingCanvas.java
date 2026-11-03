package limn.print.internal;

import limn.backend.spi.OffscreenPainter;
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
import limn.graphics.Stroke;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.graphics.TextRulers;
import limn.math.Affine2D;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * The canvas a page is painted into: it keeps every call in a {@link Recording} and answers what a
 * painter asks of a canvas (its size, its scale, its state, where its clip is) as a canvas that draws
 * would, so a scene paints a page exactly as it paints a window.
 *
 * <p>What it says it is: the frame size of the scene being painted now ({@link #setFrame}), which the
 * scene lays out to, and a content scale of the document's raster resolution over 96, so an icon
 * drawn as a bitmap is drawn at the resolution of print. What it does not keep:
 * {@code clear} and {@code clearRect}, which wipe a window's frame and have nothing to wipe on paper,
 * and {@code drawSurface}, whose GPU texture no page can hold; the first of those per page is
 * logged.
 *
 * <p>A layer whose drawing sets a Porter-Duff rule ({@link BlendMode#CLEAR} to {@link BlendMode#XOR})
 * is kept as a picture: PDF has no such rules, so at the layer's restore its calls are drawn off
 * screen by the backend's {@link OffscreenPainter}, at the page's resolution times the scale the
 * layer is drawn at, and the layer becomes that image laid where the layer stood, at the layer's
 * opacity. Outside any layer the page is the layer: a page whose drawing sets one is kept as one
 * image of the page. The preview replays the same picture. Where no picture can be made the calls
 * stay as they were, and the rules are drawn as normal painting, said once per page.
 *
 * <p>UI thread only, as every canvas a scene paints into.
 */
public final class RecordingCanvas implements Canvas {

    private static final System.Logger LOG = System.getLogger(RecordingCanvas.class.getName());

    private final Recording.Builder out;
    private final GlyphBank glyphs;
    /** The shaping whose glyphs were collected last, so its ranges collect them once. */
    private @Nullable ShapedText collected;
    private final float contentScale;
    private float frameWidth;
    private float frameHeight;
    private boolean surfaceLogged;
    private boolean rasterLogged;
    private final float pageWidth;
    private final float pageHeight;
    /**
     * Beside {@link #saved}, one entry per save: the layer a {@link #saveLayer} opened, or
     * {@link #NO_LAYER} for a plain save.
     */
    private final Deque<Layer> layers = new ArrayDeque<>();
    /** Whether the page's own drawing, outside any layer, set a Porter-Duff rule. */
    private boolean pageRuled;
    /** Whether a layer under a Porter-Duff rule is drawn as a picture: false for a scratch recording. */
    private final boolean pictures;
    /** Whether a GPU surface is kept, as a {@link limn.graphics.Picture} keeps it; a page leaves it out. */
    private final boolean keepsSurfaces;

    private State state = new State(Affine2D.IDENTITY, null, 1, BlendMode.NORMAL, Sampling.SMOOTH, true);
    private final Deque<State> saved = new ArrayDeque<>();

    /**
     * @param pageWidth    the page's width, in document units
     * @param pageHeight   its height
     * @param contentScale what the canvas answers as its scale: the raster resolution over 96
     * @param glyphs       where the outlines of the text recorded are kept for the PDF writer
     */
    public RecordingCanvas(float pageWidth, float pageHeight, float contentScale, GlyphBank glyphs) {
        this(pageWidth, pageHeight, contentScale, glyphs, true, false);
    }

    /**
     * A recording no page keeps, which a scene's frames are drawn into while its animations settle:
     * a layer under a Porter-Duff rule is not drawn as a picture, which would be thrown away.
     *
     * @param width        the frame's width, in document units
     * @param height       its height
     * @param contentScale what the canvas answers as its scale
     * @return the canvas
     */
    public static RecordingCanvas scratch(float width, float height, float contentScale) {
        return new RecordingCanvas(width, height, contentScale, new GlyphBank(limn.backend.spi.GlyphOutlines.NONE),
                false, false);
    }

    /**
     * The canvas a {@link limn.graphics.Picture} is recorded with: every call kept as it is, a layer
     * under a Porter-Duff rule and a GPU surface included, to be replayed onto a canvas that draws
     * them; a page the picture is replayed into decides what it does with each.
     *
     * @param width        the picture's width
     * @param height       its height
     * @param contentScale what the canvas answers as its scale
     * @return the canvas
     */
    public static RecordingCanvas picture(float width, float height, float contentScale) {
        return new RecordingCanvas(width, height, contentScale, new GlyphBank(limn.backend.spi.GlyphOutlines.NONE),
                false, true);
    }

    private RecordingCanvas(float pageWidth, float pageHeight, float contentScale, GlyphBank glyphs,
                            boolean pictures, boolean keepsSurfaces) {
        this.pictures = pictures;
        this.keepsSurfaces = keepsSurfaces;
        this.out = new Recording.Builder(pageWidth, pageHeight);
        this.glyphs = Objects.requireNonNull(glyphs, "glyphs");
        this.contentScale = contentScale;
        this.frameWidth = pageWidth;
        this.frameHeight = pageHeight;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
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
        if (pageRuled) {
            List<Recording.Op> page = out.cut(0);
            Image picture = raster(page, 0, 0, pageWidth, pageHeight, contentScale);
            if (picture == null) {
                page.forEach(out::add);
            } else {
                out.add(c -> c.drawImage(picture, 0, 0, pageWidth, pageHeight));
            }
        }
        return out.build();
    }

    /** A layer opened by {@link #saveLayer}: where its calls start, where it stands, and how it is laid. */
    private static final class Layer {
        final int start;
        final float x;
        final float y;
        final float width;
        final float height;
        final float opacity;
        /** Device pixels per unit of the layer's coordinates, on the page at its raster resolution. */
        final float scale;
        boolean ruled;

        Layer(int start, float x, float y, float width, float height, float opacity, float scale) {
            this.start = start;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.opacity = opacity;
            this.scale = scale;
        }
    }

    private static final Layer NO_LAYER = new Layer(-1, 0, 0, 0, 0, 1, 1);

    /** Whether {@code mode} is one of the Porter-Duff rules, which PDF has none of. */
    private static boolean porterDuff(BlendMode mode) {
        return switch (mode) {
            case CLEAR, SOURCE, DESTINATION_OVER, SOURCE_IN, DESTINATION_IN, SOURCE_OUT, DESTINATION_OUT,
                 SOURCE_ATOP, DESTINATION_ATOP, XOR -> true;
            default -> false;
        };
    }

    /**
     * Draws {@code ops}, recorded in coordinates where the region {@code (x, y, width, height)} is,
     * into a picture of that region at {@code scale} pixels to the unit; null when the backend makes
     * none, said once per page.
     */
    private @Nullable Image raster(List<Recording.Op> ops, float x, float y, float width, float height,
                                   float scale) {
        int pw = (int) Math.ceil(width * scale);
        int ph = (int) Math.ceil(height * scale);
        Image picture = null;
        if (pw > 0 && ph > 0) {
            float sx = pw / width;
            float sy = ph / height;
            picture = limn.graphics.internal.Offscreen.current().paint(pw, ph, 1f, c -> {
                c.scale(sx, sy);
                c.translate(-x, -y);
                for (Recording.Op op : ops) {
                    op.replay(c);
                }
            });
        }
        if (picture == null && !rasterLogged) {
            rasterLogged = true;
            LOG.log(System.Logger.Level.WARNING, "a layer drawn under a Porter-Duff rule is written as normal "
                    + "painting: PDF has no such rules, and the backend drew no picture of it");
        }
        return picture;
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
        layers.push(NO_LAYER);
        out.add(Canvas::save);
    }

    @Override
    public void restore() {
        if (saved.isEmpty()) {
            return;
        }
        state = saved.pop();
        Layer layer = layers.pop();
        if (layer.ruled) {
            List<Recording.Op> calls = out.cut(layer.start);
            Image picture = raster(calls.subList(1, calls.size()), layer.x, layer.y, layer.width, layer.height,
                    layer.scale);
            if (picture != null) {
                // Laid where the layer stood, in the state it was opened in, at its opacity.
                out.add(Canvas::save);
                if (layer.opacity < 1) {
                    out.add(c -> c.setOpacity(layer.opacity));
                }
                out.add(c -> c.drawImage(picture, layer.x, layer.y, layer.width, layer.height));
                out.add(Canvas::restore);
                return;
            }
            calls.forEach(out::add);
        }
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
    public void shear(float sx, float sy) {
        state = state.with(state.transform.shear(sx, sy));
        out.add(c -> c.shear(sx, sy));
    }

    @Override
    public void concat(Affine2D matrix) {
        Objects.requireNonNull(matrix, "matrix");
        state = state.with(state.transform.concat(matrix));
        out.add(c -> c.concat(matrix));
    }

    @Override
    public Affine2D transform() {
        return state.transform;
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
        Affine2D t = state.transform;
        float stretch = (float) Math.sqrt(Math.abs(t.a() * t.d() - t.b() * t.c()));
        layers.push(new Layer(out.size(), x, y, Math.max(0, width), Math.max(0, height),
                Math.max(0, Math.min(1, opacity)), contentScale * Math.max(stretch, 1e-3f)));
        saved.push(state);
        // Inside a layer the opacity starts again from 1, and the drawing from the normal mode.
        state = new State(state.transform, state.clip, 1, BlendMode.NORMAL, state.sampling, state.snap);
        out.add(c -> c.saveLayer(x, y, width, height, opacity));
    }

    @Override
    public void setBlendMode(BlendMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (pictures && porterDuff(mode)) {
            Layer inner = null;
            for (Layer l : layers) {
                if (l != NO_LAYER) {
                    inner = l; // the deque runs from the innermost save outward
                    break;
                }
            }
            if (inner != null) {
                inner.ruled = true;
            } else {
                pageRuled = true;
            }
        }
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

    @Override
    public void clipPath(Path2D path) {
        Objects.requireNonNull(path, "path");
        Rect bounds = path.bounds();
        if (bounds == null) {
            narrow(0, 0, 0, 0);
        } else {
            narrow(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        }
        Path2D kept = path.copy();
        out.add(c -> c.clipPath(kept));
    }

    /** Intersects the tracked clip, kept as a box on the page, with a rectangle's box on the page. */
    private void narrow(float x, float y, float width, float height) {
        Affine2D t = state.transform;
        double x0 = Double.MAX_VALUE;
        double y0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y1 = -Double.MAX_VALUE;
        double[][] corners = {{x, y}, {x + width, y}, {x, y + height}, {x + width, y + height}};
        for (double[] p : corners) {
            double px = t.a() * p[0] + t.c() * p[1] + t.e();
            double py = t.b() * p[0] + t.d() * p[1] + t.f();
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
        Affine2D t = state.transform;
        if (clip == null || Math.abs(t.b()) >= 1e-9 || Math.abs(t.c()) >= 1e-9 || Math.abs(t.a()) < 1e-9 || Math.abs(t.d()) < 1e-9) {
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

    @Override
    public void drawPath(Path2D path, Stroke stroke, Paint paint) {
        Objects.requireNonNull(stroke, "stroke");
        Objects.requireNonNull(paint, "paint");
        Path2D kept = path.copy();
        out.add(c -> c.drawPath(kept, stroke, paint));
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
    public void drawText(ShapedText text, int charStart, int charEnd, float x, float y, Paint paint) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(paint, "paint");
        if (text.glyphCount() == 0 || charStart >= charEnd) {
            return;
        }
        TextMetrics metrics = text.metrics();
        if (outside(x, y - metrics.ascent(), metrics.width(), metrics.ascent() + metrics.descent())) {
            return; // as a whole line: a clipped range is not read from the page either
        }
        if (collected != text) {
            // One shaping drawn a range per colour is collected once, not once per range.
            glyphs.collect(text);
            collected = text;
        }
        out.add(c -> c.drawText(text, charStart, charEnd, x, y, paint));
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
        Affine2D t = state.transform;
        double x0 = Double.MAX_VALUE;
        double y0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y1 = -Double.MAX_VALUE;
        double[][] corners = {{x, y}, {x + width, y}, {x, y + height}, {x + width, y + height}};
        for (double[] p : corners) {
            double px = t.a() * p[0] + t.c() * p[1] + t.e();
            double py = t.b() * p[0] + t.d() * p[1] + t.f();
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
        if (keepsSurfaces) {
            Objects.requireNonNull(surface, "surface");
            out.add(c -> c.drawSurface(surface, x, y, w, h));
            return;
        }
        // A GPU texture (a video's frame, a 3D view) has no picture a page can hold: it is left out,
        // said once per page.
        if (!surfaceLogged) {
            surfaceLogged = true;
            LOG.log(System.Logger.Level.WARNING, "a video or 3D view is left out of a printed page: a GPU "
                    + "surface has no picture a page can hold; print an Image of it instead");
        }
    }

    /** What a save keeps. */
    private record State(Affine2D transform, @Nullable Rect clip, float opacity, BlendMode blend,
                         Sampling sampling, boolean snap) {
        State with(Affine2D moved) {
            return new State(moved, clip, opacity, blend, sampling, snap);
        }
    }
}
