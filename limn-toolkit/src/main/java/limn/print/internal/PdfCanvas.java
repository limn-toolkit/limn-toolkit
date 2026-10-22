package limn.print.internal;

import limn.backend.spi.GlyphOutlines;
import limn.graphics.BlendMode;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.GpuSurface;
import limn.graphics.Image;
import limn.graphics.LinearGradient;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.RadialGradient;
import limn.graphics.Rect;
import limn.graphics.RoundRect;
import limn.graphics.Sampling;
import limn.graphics.Shadow;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * A canvas that writes what is drawn into it as a PDF page's content (ADR 078 §7.3): shapes as
 * paths, colours and gradients as fills and shadings, layers as transparency groups, images as
 * image objects, text as Type 3 glyphs that keep their characters. What has no PDF form becomes a
 * picture at the document's raster resolution (a soft shadow), or the fallback the {@link Canvas}
 * contract already gives it (a backdrop's tint).
 *
 * <p>Only a {@link Recording} is replayed into it, so it answers no layout question: its
 * {@link #measureText} is a stub. The page's coordinates are document units, y down; the content
 * stream starts by mapping them onto the paper's points, y up.
 */
final class PdfCanvas implements Canvas {

    /** A finished content stream and the resources it names. */
    record Content(String ops, Set<String> used) {
    }

    /** One content stream being written: the page's, or a layer's. */
    private static final class Buffer {
        final StringBuilder ops = new StringBuilder(4096);
        final Set<String> used = new HashSet<>();
        /** What maps this stream's coordinates at its start onto its pattern space. */
        final Affine base;

        Buffer(Affine base) {
            this.base = base;
        }
    }

    /** What a save keeps, and whether it opened a layer. */
    private record State(Affine ctm, float opacity, BlendMode blend, Sampling sampling, boolean layer,
                         float lx, float ly, float lw, float lh, float layerOpacity, BlendMode layerBlend) {
        State with(Affine moved) {
            return new State(moved, opacity, blend, sampling, layer, lx, ly, lw, lh, layerOpacity, layerBlend);
        }
    }

    private final PdfWriter writer;
    private final float width;
    private final float height;
    private final Deque<Buffer> buffers = new ArrayDeque<>();
    private final Deque<State> saved = new ArrayDeque<>();
    private State state = new State(Affine.IDENTITY, 1, BlendMode.NORMAL, Sampling.SMOOTH, false,
            0, 0, 0, 0, 1, BlendMode.NORMAL);

    PdfCanvas(PdfWriter writer, float width, float height, float widthPt, float heightPt) {
        this.writer = writer;
        this.width = width;
        this.height = height;
        // Document units are 1/96 inch, y down; the page's default space is points, y up.
        double sx = widthPt / width;
        double sy = heightPt / height;
        Affine base = new Affine(sx, 0, 0, -sy, 0, heightPt);
        Buffer page = new Buffer(base);
        page.ops.append(Pdf.num(sx)).append(" 0 0 ").append(Pdf.num(-sy)).append(" 0 ").append(Pdf.num(heightPt))
                .append(" cm\n");
        buffers.push(page);
    }

    /** @return the page's content, every save closed */
    Content finish() {
        restoreToCount(0);
        Buffer page = buffers.peek();
        return new Content(page.ops.toString(), page.used);
    }

    private Buffer out() {
        return buffers.peek();
    }

    private StringBuilder ops() {
        return out().ops;
    }

    private String use(String name) {
        out().used.add(name);
        return name;
    }

    // ------------------------------------------------------------------ frame

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
        return writer.rasterScale();
    }

    @Override
    public void clear(Color color) {
    }

    @Override
    public void clearRect(float x, float y, float width, float height, Color color) {
    }

    // ------------------------------------------------------------------ state

    @Override
    public void save() {
        saved.push(state);
        state = new State(state.ctm, state.opacity, state.blend, state.sampling, false, 0, 0, 0, 0, 1,
                BlendMode.NORMAL);
        ops().append("q\n");
    }

    @Override
    public void restore() {
        if (saved.isEmpty()) {
            return;
        }
        State closing = state;
        state = saved.pop();
        if (!closing.layer) {
            ops().append("Q\n");
            return;
        }
        Buffer layer = buffers.pop();
        try {
            String form = writer.form(new Content(layer.ops.toString(), layer.used), closing.lx, closing.ly,
                    closing.lw, closing.lh);
            float alpha = closing.layerOpacity * state.opacity;
            StringBuilder ops = ops();
            ops.append("q\n");
            if (alpha < 1 || closing.layerBlend != BlendMode.NORMAL) {
                ops.append('/').append(use(writer.state(alpha, closing.layerBlend))).append(" gs\n");
            }
            ops.append('/').append(use(form)).append(" Do\nQ\n");
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
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
        state = state.with(state.ctm.translate(dx, dy));
        ops().append("1 0 0 1 ").append(Pdf.num(dx)).append(' ').append(Pdf.num(dy)).append(" cm\n");
    }

    @Override
    public void scale(float sx, float sy) {
        state = state.with(state.ctm.scale(sx, sy));
        ops().append(Pdf.num(sx)).append(" 0 0 ").append(Pdf.num(sy)).append(" 0 0 cm\n");
    }

    @Override
    public void rotate(float angleRadians) {
        state = state.with(state.ctm.rotate(angleRadians));
        double cos = Math.cos(angleRadians);
        double sin = Math.sin(angleRadians);
        ops().append(Pdf.num(cos)).append(' ').append(Pdf.num(sin)).append(' ').append(Pdf.num(-sin)).append(' ')
                .append(Pdf.num(cos)).append(" 0 0 cm\n");
    }

    @Override
    public void setOpacity(float opacity) {
        float clamped = Math.max(0, Math.min(1, opacity));
        state = new State(state.ctm, state.opacity * clamped, state.blend, state.sampling, state.layer, state.lx,
                state.ly, state.lw, state.lh, state.layerOpacity, state.layerBlend);
    }

    @Override
    public float opacity() {
        return state.opacity;
    }

    @Override
    public void saveLayer(float x, float y, float w, float h, float opacity) {
        saved.push(state);
        float clamped = Math.max(0, Math.min(1, opacity));
        // The layer's content is a form drawn where the canvas stands now: its own coordinates start
        // from here, and its pattern space is this space.
        state = new State(Affine.IDENTITY, 1, BlendMode.NORMAL, state.sampling, true, x, y, w, h, clamped,
                state.blend);
        buffers.push(new Buffer(Affine.IDENTITY));
    }

    @Override
    public void setBlendMode(BlendMode mode) {
        state = new State(state.ctm, state.opacity, mode, state.sampling, state.layer, state.lx, state.ly,
                state.lw, state.lh, state.layerOpacity, state.layerBlend);
    }

    @Override
    public BlendMode blendMode() {
        return state.blend;
    }

    @Override
    public void setSampling(Sampling sampling) {
        state = new State(state.ctm, state.opacity, state.blend, sampling, state.layer, state.lx, state.ly,
                state.lw, state.lh, state.layerOpacity, state.layerBlend);
    }

    @Override
    public Sampling sampling() {
        return state.sampling;
    }

    // ------------------------------------------------------------------ clip

    @Override
    public void clipRect(float x, float y, float w, float h) {
        ops().append(PdfPaths.rect(x, y, Math.max(0, w), Math.max(0, h))).append("W n\n");
    }

    @Override
    public @Nullable Rect clipBounds() {
        return null;
    }

    @Override
    public void clipRoundRect(RoundRect roundRect) {
        ops().append(PdfPaths.roundRect(roundRect)).append("W n\n");
    }

    // ------------------------------------------------------------------ painting

    private enum Mode { FILL, STROKE }

    /**
     * Paints a path: the colour or the gradient, the opacity in force times the paint's own alpha,
     * the blend mode; inside its own save whenever it sets anything a later draw must not inherit.
     */
    private void paint(String path, Paint paint, Mode mode, float strokeWidth, int cap, int join) {
        try {
            StringBuilder ops = ops();
            ops.append("q\n");
            if (mode == Mode.STROKE) {
                ops.append(Pdf.num(strokeWidth)).append(" w ").append(cap).append(" J ").append(join).append(" j\n");
            }
            setPaint(paint, mode == Mode.FILL);
            ops.append(path).append(mode == Mode.FILL ? "f\n" : "S\n").append("Q\n");
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** Sets the colour, the alpha and the blend mode for what follows in the current save. */
    private void setPaint(Paint paint, boolean fill) throws IOException {
        StringBuilder ops = ops();
        if (paint instanceof Color color) {
            float alpha = color.a() * state.opacity;
            if (alpha < 1 || state.blend != BlendMode.NORMAL) {
                ops.append('/').append(use(writer.state(alpha, state.blend))).append(" gs\n");
            }
            ops.append(Pdf.num(color.r())).append(' ').append(Pdf.num(color.g())).append(' ')
                    .append(Pdf.num(color.b())).append(fill ? " rg\n" : " RG\n");
            return;
        }
        float a0 = paint instanceof LinearGradient l ? l.start().a() : ((RadialGradient) paint).center().a();
        float a1 = paint instanceof LinearGradient l ? l.end().a() : ((RadialGradient) paint).edge().a();
        if (a0 != a1) {
            ops.append('/').append(use(writer.maskedState(paint, a0 * state.opacity, a1 * state.opacity,
                    state.blend))).append(" gs\n");
        } else if (a0 * state.opacity < 1 || state.blend != BlendMode.NORMAL) {
            ops.append('/').append(use(writer.state(a0 * state.opacity, state.blend))).append(" gs\n");
        }
        String pattern = use(writer.pattern(paint, out().base.times(state.ctm)));
        ops.append(fill ? "/Pattern cs /" : "/Pattern CS /").append(pattern).append(fill ? " scn\n" : " SCN\n");
    }

    @Override
    public void fillRect(float x, float y, float w, float h, Paint paint) {
        if (w <= 0 || h <= 0) {
            return;
        }
        paint(PdfPaths.rect(x, y, w, h), paint, Mode.FILL, 0, 0, 0);
    }

    @Override
    public void drawRect(float x, float y, float w, float h, float strokeWidth, Paint paint) {
        if (w < 0 || h < 0 || strokeWidth <= 0) {
            return;
        }
        paint(PdfPaths.rect(x, y, w, h), paint, Mode.STROKE, strokeWidth, 2, 0);
    }

    @Override
    public void fillRoundRect(RoundRect roundRect, Paint paint) {
        if (roundRect.width() <= 0 || roundRect.height() <= 0) {
            return;
        }
        paint(PdfPaths.roundRect(roundRect), paint, Mode.FILL, 0, 0, 0);
    }

    @Override
    public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
        if (roundRect.width() <= 0 || roundRect.height() <= 0 || strokeWidth <= 0) {
            return;
        }
        paint(PdfPaths.roundRect(roundRect), paint, Mode.STROKE, strokeWidth, 0, 1);
    }

    @Override
    public void fillCircle(float cx, float cy, float radius, Paint paint) {
        fillEllipse(cx, cy, radius, radius, paint);
    }

    @Override
    public void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint) {
        drawEllipse(cx, cy, radius, radius, strokeWidth, paint);
    }

    @Override
    public void fillEllipse(float cx, float cy, float radiusX, float radiusY, Paint paint) {
        if (radiusX <= 0 || radiusY <= 0) {
            return;
        }
        paint(PdfPaths.ellipse(cx, cy, radiusX, radiusY), paint, Mode.FILL, 0, 0, 0);
    }

    @Override
    public void drawEllipse(float cx, float cy, float radiusX, float radiusY, float strokeWidth, Paint paint) {
        if (radiusX <= 0 || radiusY <= 0 || strokeWidth <= 0) {
            return;
        }
        paint(PdfPaths.ellipse(cx, cy, radiusX, radiusY), paint, Mode.STROKE, strokeWidth, 0, 1);
    }

    @Override
    public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
        if (strokeWidth <= 0) {
            return;
        }
        paint(Pdf.num(x1) + " " + Pdf.num(y1) + " m\n" + Pdf.num(x2) + " " + Pdf.num(y2) + " l\n", paint,
                Mode.STROKE, strokeWidth, 1, 1);
    }

    @Override
    public void fillPath(Path2D path, Paint paint) {
        // Each closed subpath on its own, as the canvas contract fills them (Path2D's notes).
        PdfPaths.subpaths(path, subpath -> paint(subpath, paint, Mode.FILL, 0, 0, 0));
    }

    @Override
    public void drawPath(Path2D path, float strokeWidth, Paint paint) {
        if (strokeWidth <= 0 || path.isEmpty()) {
            return;
        }
        paint(PdfPaths.whole(path), paint, Mode.STROKE, strokeWidth, 1, 1);
    }

    @Override
    public void drawShadow(RoundRect shape, Shadow shadow) {
        if (shadow.color().a() <= 0 || shape.width() <= 0 || shape.height() <= 0) {
            return;
        }
        RoundRect moved = new RoundRect(shape.x() + shadow.offsetX(), shape.y() + shadow.offsetY(), shape.width(),
                shape.height(), shape.topLeft(), shape.topRight(), shape.bottomRight(), shape.bottomLeft());
        if (shadow.blur() < 0.5f) {
            fillRoundRect(moved, shadow.color());
            return;
        }
        try {
            Shadows.Picture picture = Shadows.rasterize(moved, shadow.blur(), writer.rasterScale());
            Color opaque = new Color(shadow.color().r(), shadow.color().g(), shadow.color().b(), 1);
            String image = writer.coverage(picture.columns(), picture.rows(), picture.alpha(), opaque, true);
            drawXObject(image, picture.x(), picture.y(), picture.width(), picture.height(), shadow.color().a());
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    // ------------------------------------------------------------------ text

    @Override
    public void drawText(String text, float x, float y, Font font, Paint paint) {
        // Never reached: a recording keeps text shaped.
    }

    @Override
    public void drawText(ShapedText text, float x, float y, Paint paint) {
        if (text.glyphCount() == 0) {
            return;
        }
        try {
            new TextWriter(text, x, y, paint).write();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    @Override
    public TextMetrics measureText(String text, Font font) {
        return new TextMetrics(0, 0, 0, 0);
    }

    /** Writes one shaped line: its glyphs as Type 3 codes, its glyphless clusters as their pieces. */
    private final class TextWriter {
        private final ShapedText text;
        private final float x;
        private final float y;
        private final Paint paint;
        private final StringBuilder ops = ops();
        private final GlyphBank glyphs = writer.glyphs();
        private final float size;
        private boolean inText;
        private PdfFonts.@Nullable Subset font;
        private boolean inArray;
        private float pen; // where the last code put the pen, in user space along the line
        private float penY = Float.NaN;

        TextWriter(ShapedText text, float x, float y, Paint paint) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.paint = paint;
            this.size = text.font().size();
        }

        void write() throws IOException {
            String value = text.text();
            boolean actual = needsActualText();
            ops.append("q\n");
            setPaint(paint, true);
            if (actual) {
                ops.append("/Span<</ActualText").append(Pdf.text(value)).append(">>BDC\n");
            }
            int[] ends = clusterEnds();
            boolean[] first = new boolean[value.length() + 1];
            for (ShapedText.Run run : text.runs()) {
                GlyphBank.Face face = glyphs.keptFace(run.faceId());
                for (int g = run.glyphStart(); g < run.glyphEnd(); g++) {
                    int glyph = text.glyphId(g);
                    int from = text.glyphCluster(g);
                    float gx = text.glyphX(g);
                    float gy = text.glyphY(g);
                    if (glyph == ShapedText.NO_GLYPH || face == null) {
                        pieces(value, from, gx);
                        continue;
                    }
                    String chars = "";
                    if (from >= 0 && from < first.length && !first[from]) {
                        first[from] = true;
                        int to = Math.min(value.length(), Math.max(from, ends[from]));
                        chars = value.substring(from, to);
                    }
                    float units = text.glyphAdvance(g) / size * face.unitsPerEm();
                    glyph(face, glyph, chars, units, gx, gy);
                }
            }
            endText();
            if (actual) {
                ops.append("EMC\n");
            }
            ops.append("Q\n");
        }

        /** A line whose glyphs do not each stand for their own characters in reading order. */
        private boolean needsActualText() {
            int last = -1;
            for (ShapedText.Run run : text.runs()) {
                if (run.rtl()) {
                    return true;
                }
            }
            for (int g = 0; g < text.glyphCount(); g++) {
                int cluster = text.glyphCluster(g);
                if (cluster <= last || text.glyphId(g) == ShapedText.NO_GLYPH) {
                    return true;
                }
                last = cluster;
            }
            return false;
        }

        /** For each char offset that starts a cluster, where the cluster ends. */
        private int[] clusterEnds() {
            String value = text.text();
            int[] ends = new int[value.length() + 1];
            java.util.TreeSet<Integer> starts = new java.util.TreeSet<>();
            for (int g = 0; g < text.glyphCount(); g++) {
                starts.add(text.glyphCluster(g));
            }
            for (ShapedText.Run run : text.runs()) {
                for (int g = run.glyphStart(); g < run.glyphEnd(); g++) {
                    int from = text.glyphCluster(g);
                    Integer next = starts.higher(from);
                    int end = next == null ? value.length() : next;
                    if (end > run.charEnd() && from < run.charEnd()) {
                        end = run.charEnd();
                    }
                    if (from >= 0 && from < ends.length) {
                        ends[from] = Math.max(from, end);
                    }
                }
            }
            return ends;
        }

        private void pieces(String value, int from, float gx) throws IOException {
            int to = text.caretIndex(text.caretOrdinal(from) + 1);
            if (to <= from || to > value.length()) {
                return;
            }
            float penAt = gx;
            for (GlyphOutlines.Piece piece : glyphs.keptCluster(value.substring(from, to), text.font())) {
                if (piece instanceof GlyphOutlines.Glyph drawn) {
                    GlyphBank.Face face = glyphs.keptFace(drawn.faceId());
                    if (face != null) {
                        glyph(face, drawn.glyphId(), penAt == gx ? value.substring(from, to) : "",
                                drawn.advance() * face.unitsPerEm(), penAt, 0);
                    }
                    penAt += drawn.advance() * size;
                } else if (piece instanceof GlyphOutlines.Picture picture) {
                    endText();
                    float alpha = paint instanceof Color c ? c.a() : 1;
                    drawXObject(writer.image(picture.image(), true), x + penAt, y - picture.top() * size,
                            picture.width() * size, picture.height() * size, alpha);
                    penAt += picture.advance() * size;
                }
            }
        }

        private void glyph(GlyphBank.Face face, int glyph, String chars, float units, float gx, float gy)
                throws IOException {
            PdfFonts.Placed placed = writer.fonts().place(face, glyph, chars, units);
            float gxUser = x + gx;
            float gyUser = y + gy;
            if (!inText) {
                ops.append("BT\n");
                inText = true;
            }
            if (font != placed.subset()) {
                closeArray();
                font = placed.subset();
                ops.append('/').append(use(font.resource)).append(' ').append(Pdf.num(size)).append(" Tf\n");
            }
            if (!inArray || gyUser != penY) {
                closeArray();
                ops.append("1 0 0 -1 ").append(Pdf.num(gxUser)).append(' ').append(Pdf.num(gyUser)).append(" Tm\n[");
                inArray = true;
                penY = gyUser;
                pen = gxUser;
            }
            float gap = gxUser - pen;
            if (Math.abs(gap) > 1e-3f) {
                // TJ moves the pen left by a positive number, in thousandths of the font size.
                ops.append(Pdf.num(-gap / size * 1000)).append(' ');
            }
            ops.append(String.format(java.util.Locale.ROOT, "<%02X>", placed.code()));
            pen = gxUser + placed.width() / face.unitsPerEm() * size;
        }

        private void closeArray() {
            if (inArray) {
                ops.append("] TJ\n");
                inArray = false;
            }
        }

        private void endText() {
            closeArray();
            if (inText) {
                ops.append("ET\n");
                inText = false;
                font = null;
            }
        }
    }

    // ------------------------------------------------------------------ images

    /** Draws an image object into a box, at an alpha multiplied by the opacity in force. */
    private void drawXObject(String name, float x, float y, float w, float h, float alpha) throws IOException {
        if (w <= 0 || h <= 0) {
            return;
        }
        StringBuilder ops = ops();
        ops.append("q\n");
        float a = alpha * state.opacity;
        if (a < 1 || state.blend != BlendMode.NORMAL) {
            ops.append('/').append(use(writer.state(a, state.blend))).append(" gs\n");
        }
        ops.append(Pdf.num(w)).append(" 0 0 ").append(Pdf.num(-h)).append(' ').append(Pdf.num(x)).append(' ')
                .append(Pdf.num(y + h)).append(" cm\n/").append(use(name)).append(" Do\nQ\n");
    }

    private boolean smooth() {
        return state.sampling != Sampling.PIXELATED;
    }

    @Override
    public void drawImage(Image image, float x, float y, float w, float h) {
        try {
            drawXObject(writer.image(image, smooth()), x, y, w, h, 1);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    @Override
    public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                          float dstX, float dstY, float dstW, float dstH) {
        drawImage(image, srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH, Color.WHITE);
    }

    @Override
    public void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                          float dstX, float dstY, float dstW, float dstH, Color tint) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return;
        }
        float sx = dstW / srcW;
        float sy = dstH / srcH;
        try {
            String name = tint.equals(Color.WHITE) ? writer.image(image, smooth()) : writer.tinted(image, tint, smooth());
            StringBuilder ops = ops();
            ops.append("q\n").append(PdfPaths.rect(dstX, dstY, dstW, dstH)).append("W n\n");
            drawXObject(name, dstX - srcX * sx, dstY - srcY * sy, image.width() * sx, image.height() * sy, 1);
            ops().append("Q\n");
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    @Override
    public void drawImage(Image image, float x, float y, float w, float h, Color tint) {
        try {
            drawXObject(tint.equals(Color.WHITE) ? writer.image(image, smooth()) : writer.tinted(image, tint, smooth()),
                    x, y, w, h, 1);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    @Override
    public void drawImageMask(Image image, float x, float y, float w, float h, Color tint) {
        try {
            Color opaque = new Color(tint.r(), tint.g(), tint.b(), 1);
            drawXObject(writer.mask(image, opaque, smooth()), x, y, w, h, tint.a());
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    @Override
    public void drawSurface(GpuSurface surface, float x, float y, float w, float h) {
        // Never recorded (RecordingCanvas leaves a GPU surface out).
    }
}
