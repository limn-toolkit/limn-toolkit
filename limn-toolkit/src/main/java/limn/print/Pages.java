package limn.print;

import limn.backend.spi.GlyphOutlines;
import limn.components.Theme;
import limn.components.internal.LineCuts;
import limn.components.internal.PrintRows;
import limn.print.internal.GlyphBank;
import limn.print.internal.Outlines;
import limn.print.internal.Recording;
import limn.print.internal.RecordingCanvas;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.internal.SceneAccess;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Cuts a document into pages and records them: the blocks laid out top to bottom in
 * each page's content box, a block that does not fit cut where it can be cut or moved to the next
 * page, a list, a table or a tree cut between rows with a page's worth of rows realized at a time,
 * and the header and the footer drawn on every page once the count is known.
 *
 * <p>Each block lives in a scene of its own that no window shows ({@link Document} keeps them), a
 * page's scene: no focus, no hover, its animations run to their end, its palette the document's.
 * UI thread only.
 */
final class Pages {

    /** Document units (1/96 inch) per point. */
    static final float UNITS_PER_POINT = 96f / 72f;

    /** How far a page's scene's clock jumps on each settling frame, and how much faster it runs. */
    private static final long SETTLE_NANOS = 1_000_000_000L;
    private static final double SETTLE_SCALE = 1000;

    /** How many times the header and footer may grow the reserved space before the cut is final. */
    private static final int MAX_PASSES = 3;

    /**
     * The smallest content box a page lays anything out in, each way, in document units: a
     * quarter inch, 18 points. A line of body text fits in it with its leading at every control
     * size (the largest body face is 19 units), so every page can take at least a line, and every
     * page moves the flow on by at least this much: a block's pages are bounded by its height over
     * a quarter inch. What a header and a footer that fill the page leave, or margins nearly as
     * wide or as tall as the paper, is less than any line, and was cut forever: an empty page sliced
     * a block by the room it had, and a room of nothing never moved on.
     */
    static final float MIN_CONTENT = 24;

    /** The pages of a document, recorded, with the glyphs their text draws. */
    record Result(List<Recording> pages, GlyphBank glyphs, float widthPt, float heightPt) {
    }

    /** One stretch of one block on one page. */
    private record Placement(Block block, float sliceTop, float height, float pageY, int firstRow) {
    }

    /** A block of the document and the scene it is painted in. */
    static final class Block {
        final @Nullable Widget<?> widget;
        final boolean keepTogether;
        final boolean breakBefore;
        final @Nullable Scene scene;
        final long[] now;
        /** The height it was laid out at in this run, or a negative number before it was. */
        float natural = -1;

        Block(@Nullable Widget<?> widget, boolean keepTogether, boolean breakBefore, @Nullable Scene scene,
              long[] now) {
            this.widget = widget;
            this.keepTogether = keepTogether;
            this.breakBefore = breakBefore;
            this.scene = scene;
            this.now = now;
        }
    }

    private final Document document;
    private final Theme palette;
    private final float pageW;
    private final float pageH;
    private final float left;
    private final float top;
    private final float right;
    private final float contentW;
    private final float bottomMargin;
    private final float rasterScale;
    private final GlyphBank glyphs;

    Pages(Document document, Theme palette) {
        this.document = document;
        this.palette = palette;
        Paper paper = document.paper();
        this.pageW = paper.width() * UNITS_PER_POINT;
        this.pageH = paper.height() * UNITS_PER_POINT;
        float[] margins = document.marginsPoints();
        this.top = margins[0] * UNITS_PER_POINT;
        this.right = margins[1] * UNITS_PER_POINT;
        this.bottomMargin = margins[2] * UNITS_PER_POINT;
        this.left = margins[3] * UNITS_PER_POINT;
        this.contentW = Math.max(1, pageW - left - right);
        this.rasterScale = document.rasterDpi() / 96f;
        GlyphOutlines source = Outlines.current();
        this.glyphs = new GlyphBank(source);
    }

    /** Cuts the document and records every page. */
    Result run(List<Block> blocks) {
        for (Block block : blocks) {
            block.natural = -1;
        }
        float header = measure(document.header(), 1, 1);
        float footer = measure(document.footer(), 1, 1);
        List<List<Placement>> layout = List.of();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            layout = cut(blocks, header, footer);
            int count = Math.max(1, layout.size());
            float wantHeader = header;
            float wantFooter = footer;
            for (int page = 1; page <= count; page++) {
                wantHeader = Math.max(wantHeader, measure(document.header(), page, count));
                wantFooter = Math.max(wantFooter, measure(document.footer(), page, count));
            }
            if (wantHeader <= header && wantFooter <= footer) {
                break;
            }
            header = wantHeader;
            footer = wantFooter;
        }
        if (layout.isEmpty()) {
            layout = List.of(List.of()); // a document with nothing in it is one blank page
        }
        List<Recording> pages = new ArrayList<>(layout.size());
        for (int i = 0; i < layout.size(); i++) {
            RecordingCanvas canvas = new RecordingCanvas(pageW, pageH, rasterScale, glyphs);
            PageInfo info = new PageInfo(i + 1, layout.size(), document.paper());
            paintEdge(canvas, document.header(), info, top, header);
            for (Placement placement : layout.get(i)) {
                paint(canvas, placement);
            }
            paintEdge(canvas, document.footer(), info, pageH - bottomMargin - footer, footer);
            pages.add(canvas.finish());
        }
        return new Result(pages, glyphs, document.paper().width(), document.paper().height());
    }

    // ------------------------------------------------------------------ cutting

    private List<List<Placement>> cut(List<Block> blocks, float header, float footer) {
        checkRoom(header, footer);
        float contentTop = top + header;
        float contentBottom = pageH - bottomMargin - footer;
        float contentH = contentBottom - contentTop;
        List<List<Placement>> pages = new ArrayList<>();
        List<Placement> page = new ArrayList<>();
        float y = contentTop;
        for (Block block : blocks) {
            if (block.breakBefore && !page.isEmpty()) {
                pages.add(page);
                page = new ArrayList<>();
                y = contentTop;
            }
            Widget<?> widget = block.widget;
            if (widget == null) {
                continue; // a page break alone
            }
            PrintRows rows = PrintRows.of(widget);
            if (rows != null && !block.keepTogether && rows.rowCount() > 0) {
                // Cut between rows: the widget is laid out per page exactly as tall as what fits.
                settle(block, contentW, contentH);
                int first = 0;
                int count = rows.rowCount();
                while (first < count) {
                    float room = contentBottom - y;
                    float chrome = rows.chromeHeight();
                    float used = chrome;
                    int taken = 0;
                    while (first + taken < count) {
                        float h = rows.rowHeight(first + taken);
                        if (used + h > room + 0.01f && (taken > 0 || !page.isEmpty())) {
                            break;
                        }
                        used += h;
                        taken++;
                        if (used > room) {
                            break; // a row taller than a whole page: it goes alone, clipped
                        }
                    }
                    if (taken == 0) {
                        pages.add(page);
                        page = new ArrayList<>();
                        y = contentTop;
                        continue;
                    }
                    page.add(new Placement(block, 0, Math.min(used, room), y, first));
                    y += used;
                    first += taken;
                    if (first < count) {
                        pages.add(page);
                        page = new ArrayList<>();
                        y = contentTop;
                    }
                }
                continue;
            }
            float height = naturalHeight(block);
            float[] cuts = block.keepTogether ? new float[0] : cutsOf(block, height);
            float from = 0;
            while (from < height - 0.01f) {
                float room = contentBottom - y;
                if (height - from <= room + 0.01f) {
                    page.add(new Placement(block, from, height - from, y, -1));
                    y += height - from;
                    break;
                }
                float cut = lastCut(cuts, from, from + room);
                if (cut > from + 0.01f) {
                    page.add(new Placement(block, from, cut - from, y, -1));
                    from = cut;
                } else if (page.isEmpty()) {
                    // Nothing can be cut, and a whole page is not enough: the last resort, a slice
                    // at the page's height. A page with nothing on it has the whole content box,
                    // which checkRoom holds to MIN_CONTENT, so the slice moves on; the floor makes
                    // that true here too, whatever room a page is ever offered.
                    float slice = Math.max(room, MIN_CONTENT);
                    page.add(new Placement(block, from, slice, y, -1));
                    from += slice;
                }
                pages.add(page);
                page = new ArrayList<>();
                y = contentTop;
            }
        }
        if (!page.isEmpty() || pages.isEmpty()) {
            pages.add(page);
        }
        return pages;
    }

    /**
     * Refuses a content box smaller than {@link #MIN_CONTENT} either way: the paper less its
     * margins, and less the header and the footer as tall as they measure.
     *
     * @throws IllegalStateException naming the paper, the margins, and the header's and the
     *         footer's heights
     */
    private void checkRoom(float header, float footer) {
        float wide = pageW - left - right;
        float tall = pageH - top - bottomMargin - header - footer;
        if (wide >= MIN_CONTENT && tall >= MIN_CONTENT) {
            return;
        }
        Paper paper = document.paper();
        float[] margins = document.marginsPoints();
        throw new IllegalStateException(String.format(Locale.ROOT,
                "%s (%.1f x %.1f pt) with margins of %.1f, %.1f, %.1f and %.1f pt (top, right, bottom, left),"
                        + " %s and %s leaves a content box of %.1f x %.1f units, and a page lays nothing out"
                        + " in less than %.0f units either way (a quarter inch): shorten the header or the"
                        + " footer, narrow the margins, or choose larger paper",
                paper.name(), paper.width(), paper.height(), margins[0], margins[1], margins[2], margins[3],
                edge("header", header), edge("footer", footer), wide, tall, MIN_CONTENT));
    }

    private static String edge(String name, float height) {
        return height > 0 ? String.format(Locale.ROOT, "a %s %.1f units tall", name, height) : "no " + name;
    }

    private static float lastCut(float[] cuts, float from, float limit) {
        float best = from;
        for (float cut : cuts) {
            if (cut > from + 0.01f && cut <= limit + 0.01f) {
                best = cut;
            }
        }
        return best;
    }

    /** The block's height at the content width with no limit, laid out at it. */
    private float naturalHeight(Block block) {
        if (block.natural >= 0) {
            return block.natural;
        }
        float height = Objects.requireNonNull(block.widget)
                .measure(new Constraints(0, contentW, 0, Constraints.UNBOUNDED_LIMIT)).height();
        height = Math.max(1, (float) Math.ceil(height));
        settle(block, contentW, height);
        block.natural = height;
        return height;
    }

    /**
     * Where a block may be cut: every edge of a widget in it that does not pass through a widget
     * that cannot be cut (a leaf: a button, a picture, a line of text), and the boundaries between
     * the lines of a widget that holds lines. A container's own box may be cut; what is in it may
     * not, except where it says.
     */
    private float[] cutsOf(Block block, float height) {
        TreeSet<Float> candidates = new TreeSet<>();
        List<float[]> forbidden = new ArrayList<>();
        walk(Objects.requireNonNull(block.widget), 0, candidates, forbidden);
        List<Float> valid = new ArrayList<>();
        for (float y : candidates) {
            if (y <= 0.01f || y >= height - 0.01f) {
                continue;
            }
            boolean inside = false;
            for (float[] zone : forbidden) {
                if (y > zone[0] + 0.01f && y < zone[1] - 0.01f) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                valid.add(y);
            }
        }
        float[] out = new float[valid.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = valid.get(i);
        }
        return out;
    }

    private static void walk(Widget<?> widget, float originY, TreeSet<Float> candidates, List<float[]> forbidden) {
        if (!widget.isVisible()) {
            return;
        }
        float y0 = originY;
        float y1 = originY + widget.height();
        candidates.add(y0);
        candidates.add(y1);
        List<Widget<?>> children = widget.children();
        if (LineCuts.answers(widget)) {
            float[] lines = LineCuts.of(widget);
            float from = y0;
            for (float line : lines) {
                candidates.add(y0 + line);
                forbidden.add(new float[] {from, y0 + line});
                from = y0 + line;
            }
            forbidden.add(new float[] {from, y1});
            return;
        }
        if (children.isEmpty()) {
            forbidden.add(new float[] {y0, y1});
            return;
        }
        for (Widget<?> child : children) {
            walk(child, originY + child.y(), candidates, forbidden);
        }
    }

    // ------------------------------------------------------------------ painting

    private void paint(RecordingCanvas canvas, Placement placement) {
        Block block = placement.block();
        PrintRows rows = placement.firstRow() >= 0 ? PrintRows.of(Objects.requireNonNull(block.widget)) : null;
        float frameH;
        if (rows != null) {
            rows.beginAt(placement.firstRow());
            frameH = placement.height();
        } else {
            frameH = naturalHeight(block);
        }
        // A cut list was laid out at another height for the next page's measure; this one is laid
        // out again at its own by the frame.
        int depth = canvas.saveCount();
        canvas.save();
        try {
            canvas.translate(left, placement.pageY() - placement.sliceTop());
            canvas.clipRect(0, placement.sliceTop(), contentW, placement.height());
            canvas.setFrame(contentW, frameH);
            block.now[0] += SETTLE_NANOS;
            Objects.requireNonNull(block.scene).renderFrame(canvas);
        } finally {
            canvas.restoreToCount(depth);
            canvas.setFrame(pageW, pageH);
        }
    }

    /** Draws a header or a footer for one page, in a scene made for it and let go of after. */
    private void paintEdge(RecordingCanvas canvas, @Nullable Function<PageInfo, Widget<?>> edge, PageInfo info,
                           float y, float height) {
        if (edge == null || height <= 0) {
            return;
        }
        Widget<?> widget = edge.apply(info);
        long[] now = {0};
        Scene scene = scene(widget, now);
        try {
            settleScene(scene, now, contentW, height);
            int depth = canvas.saveCount();
            canvas.save();
            try {
                canvas.translate(left, y);
                canvas.clipRect(0, 0, contentW, height);
                canvas.setFrame(contentW, height);
                now[0] += SETTLE_NANOS;
                scene.renderFrame(canvas);
            } finally {
                canvas.restoreToCount(depth);
                canvas.setFrame(pageW, pageH);
            }
        } finally {
            release(scene);
        }
    }

    /** How tall a header or a footer is for one page, or 0 for none. */
    private float measure(@Nullable Function<PageInfo, Widget<?>> edge, int number, int count) {
        if (edge == null) {
            return 0;
        }
        Widget<?> widget = edge.apply(new PageInfo(number, count, document.paper()));
        long[] now = {0};
        Scene scene = scene(widget, now);
        try {
            return (float) Math.ceil(widget.measure(new Constraints(0, contentW, 0, Constraints.UNBOUNDED_LIMIT))
                    .height());
        } finally {
            release(scene);
        }
    }

    // ------------------------------------------------------------------ scenes

    /** A page's scene over {@code root}: the document's palette, locale and direction, no window. */
    Scene scene(Widget<?> root, long[] now) {
        Scene scene = new Scene(root, () -> now[0]);
        configure(scene, palette, document.locale());
        return scene;
    }

    /** Makes {@code scene} a page's: painted whole, its time run fast, its palette and locale the document's. */
    static void configure(Scene scene, Theme palette, @Nullable Locale locale) {
        SceneAccess.printWith(scene, palette);
        scene.setPartialRendering(false);
        scene.setTimeScale(SETTLE_SCALE);
        scene.setLocale(locale);
        scene.setLayoutDirection(locale == null ? null : LayoutDirection.forLocale(locale));
    }

    /** Lays a block's scene out at a size and lets every animation in it run to its end. */
    private void settle(Block block, float width, float height) {
        settleScene(Objects.requireNonNull(block.scene), block.now, width, height);
    }

    private void settleScene(Scene scene, long[] now, float width, float height) {
        RecordingCanvas scratch = new RecordingCanvas(width, height, rasterScale, new GlyphBank(GlyphOutlines.NONE));
        for (int frame = 0; frame < 3; frame++) {
            now[0] += SETTLE_NANOS;
            scene.renderFrame(scratch);
        }
    }

    static void release(Scene scene) {
        SceneAccess.input(scene).windowClosed();
    }
}
