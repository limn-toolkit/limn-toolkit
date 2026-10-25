package limn.print;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.Accelerator;
import limn.components.Elevation;
import limn.components.ScrollView;
import limn.components.Strokes;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Rect;
import limn.graphics.RoundRect;
import limn.input.Keys;
import limn.print.internal.Recording;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * A document's pages as sheets on a neutral background, before they are printed or saved: what a
 * business application shows under "Print preview".
 *
 * <pre>{@code
 * PrintPreview preview = new PrintPreview(order);
 * dialogBody.add(preview);                 // with the application's own Print and Paper buttons
 * preview.setZoom(PrintPreview.Zoom.FIT_WIDTH);
 * }</pre>
 *
 * <p>It shows the document's own recording, the one its PDF is written from, so what it shows is
 * what the file and the printer get. The pages scroll as a column, one sheet under the next, at a
 * zoom that fits a whole page, fits the width, or is a percentage of the paper's real size. It is
 * the toolkit's and the same on every system, because only one of the three systems' print dialogs
 * previews an application's pages.
 *
 * <p>The user zooms it as a document viewer is zoomed: a pinch on a trackpad, or the wheel with
 * Command held on macOS and Control elsewhere (Control on macOS too), around the point under the
 * pointer ({@link MouseEvent.Type#ZOOM}), and, with the preview focused, Command or Control with {@code +} or {@code =} to zoom in, {@code -} to zoom out
 * and {@code 0} for the paper's real size. A zoom the user made is a {@link Zoom#PERCENT} one and is
 * announced as a {@link Change.Aspect#VALUE} change of the user's, which a toolbar showing the zoom
 * watches. Focused, the arrows, Page Up, Page Down, Home and End scroll the pages.
 *
 * <p>A reader hears a group named "Print preview", described by the page in the middle of the view
 * and the count ("Page 3 of 12"); the pages' own text is not published to it.
 *
 * <p>The pages are recorded when the preview is made and again by {@link #refresh()}; a face the
 * text asked for that was still loading records them again once it has loaded, or after ten
 * seconds without it, with a warning. UI thread only.
 */
public final class PrintPreview extends Widget<PrintPreview> {

    /**
     * How the sheets are sized to the view.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Zoom {
        /** A whole page in the view's height and width. */
        FIT_PAGE,
        /** A page as wide as the view. */
        FIT_WIDTH,
        /** The percentage {@link #setZoomPercent} set, of the paper's real size on a 96-dpi screen. */
        PERCENT
    }

    /** The space around and between sheets, in points. */
    private static final float GAP = 16;

    /** What one key press zooms by, in or out. */
    private static final float KEY_STEP = 1.25f;

    /** What an arrow key scrolls by, in points. */
    private static final float ARROW_STEP = 48;

    private final Document document;
    private final Sheets sheets = new Sheets();
    /**
     * Both ways: a sheet zoomed past the view's width scrolls sideways too, or the point a zoom
     * keeps under the pointer could not be kept and the sheet stayed against the left edge.
     */
    private final ScrollView scroller = new ScrollView(sheets, true, true);
    private List<Recording> pages = List.of();
    private Zoom zoom = Zoom.FIT_WIDTH;
    private float percent = 100;
    private long refreshes;
    /** Where the scroll view goes once a zoom the user made is laid out: x, y; null for nowhere. */
    private float @Nullable [] pendingScroll;

    /**
     * @param document the document to show; its pages are recorded now
     * @throws IllegalStateException if the document's margins, header and footer leave a content
     *         box smaller than a quarter inch either way ({@link Document} says why)
     */
    public PrintPreview(Document document) {
        Ui.checkUiThread();
        this.document = Objects.requireNonNull(document, "document");
        // The pages' own text is not published (the class says why); the preview speaks for them.
        sheets.setAccessibleIgnored(true);
        add(scroller);
        setFocusable(true);
        refresh();
    }

    /**
     * Records the document's pages again and shows them: after the application changed what its
     * widgets show, or the document's paper.
     *
     * @return this preview
     * @throws IllegalStateException as the constructor does, for what the document's widgets show
     *         now
     */
    public PrintPreview refresh() {
        Ui.checkUiThread();
        long asked = ++refreshes;
        document.recordSettled(Document.FACE_WAIT_MILLIS, result -> {
            if (asked == refreshes) {
                pages = result.pages();
                sizeChanged();
                invalidate();
            }
        }, failed -> {
            throw failed instanceof RuntimeException runtime ? runtime : new IllegalStateException(failed);
        });
        return this;
    }

    /** @return how many pages the document has, as last recorded */
    public int pageCount() {
        return Math.max(1, pages.size());
    }

    /**
     * The sheets' size changed, with the zoom or the pages: they are measured inside the scroll
     * view, which keeps its measure while its own box is the same, so they are marked themselves.
     * Marking only the preview left the column at its old width, and a zoom kept the point under
     * the pointer only up and down.
     */
    private void sizeChanged() {
        sheets.markNeedsLayout();
        markNeedsLayout();
    }

    /** @return how many pages the last recording that arrived has; 0 before the first; for the tests */
    int recordedPages() {
        return pages.size();
    }

    /** @return how far the sheets are scrolled sideways, in points; for the tests */
    float scrolledX() {
        return scroller.offsetX();
    }

    /** @return the page in the middle of the view, from 1 */
    public int currentPage() {
        if (pages.isEmpty()) {
            return 1;
        }
        float middle = scroller.offsetY() + scroller.viewportHeight() / 2;
        float step = sheetHeight() + GAP;
        int index = step <= 0 ? 0 : (int) Math.floor((middle - GAP / 2) / step);
        return Math.max(1, Math.min(pages.size(), index + 1));
    }

    /**
     * Scrolls a page's top into the view.
     *
     * @param number the page, from 1; clamped to the document's pages
     * @return this preview
     */
    public PrintPreview showPage(int number) {
        Ui.checkUiThread();
        int page = Math.max(1, Math.min(pageCount(), number));
        scroller.scrollTo(scroller.offsetX(), GAP / 2 + (page - 1) * (sheetHeight() + GAP));
        invalidate();
        return this;
    }

    /**
     * @param zoom how the sheets are sized; {@link Zoom#PERCENT} uses the last percentage set
     * @return this preview
     */
    public PrintPreview setZoom(Zoom zoom) {
        Ui.checkUiThread();
        this.zoom = Objects.requireNonNull(zoom, "zoom");
        sizeChanged();
        return this;
    }

    /** @return how the sheets are sized */
    public Zoom zoom() {
        return zoom;
    }

    /**
     * Sizes the sheets at a percentage of the paper's real size, on a screen of 96 dots per inch at
     * 100 %, and sets the zoom to {@link Zoom#PERCENT}.
     *
     * @param percent from 10 to 800
     * @return this preview
     */
    public PrintPreview setZoomPercent(float percent) {
        Ui.checkUiThread();
        if (!(percent >= 10) || !(percent <= 800)) {
            throw new IllegalArgumentException("a zoom from 10 to 800 %: " + percent);
        }
        this.percent = percent;
        this.zoom = Zoom.PERCENT;
        sizeChanged();
        return this;
    }

    /**
     * @return the size the sheets are shown at, as a percentage of the paper's real size on a
     *         96-dpi screen, whichever the zoom: what a fitting zoom comes to at the view's size
     */
    public float zoomPercent() {
        return scale() * 100f;
    }

    // ------------------------------------------------------------------ the user's zoom

    /**
     * Zooms by a factor the user asked for, keeping the point of the pages at a place in the view
     * where it is.
     *
     * @param factor what to multiply the present size by
     * @param viewX  the place in the view, from its left
     * @param viewY  the place in the view, from its top
     */
    private void zoomBy(float factor, float viewX, float viewY) {
        float before = scale();
        float after = Math.max(0.10f, Math.min(8f, before * factor));
        zoomTo(after, viewX, viewY);
    }

    private void zoomTo(float after, float viewX, float viewY) {
        float before = scale();
        if (after == before || pages.isEmpty()) {
            if (after != before) {
                percent = after * 100f;
                zoom = Zoom.PERCENT;
                sizeChanged();
                notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.USER));
            }
            return;
        }
        // The point under the place, on its page, in the paper's own units.
        float x = scroller.offsetX() + viewX;
        float y = scroller.offsetY() + viewY;
        float sheetH = pageHeight() * before;
        float step = sheetH + GAP;
        int page = Math.max(0, Math.min(pages.size() - 1, (int) Math.floor((y - GAP) / step)));
        float onPageY = (y - GAP - page * step) / before;
        float onPageX = (x - sheetLeft(before)) / before;
        percent = after * 100f;
        zoom = Zoom.PERCENT;
        // The same point once the sheets have their new size, put back under the place.
        float newY = GAP + page * (pageHeight() * after + GAP) + onPageY * after;
        float newX = sheetLeft(after) + onPageX * after;
        pendingScroll = new float[] {newX - viewX, newY - viewY};
        sizeChanged();
        invalidate();
        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.USER));
    }

    /** Where a sheet's left edge is in the column at a scale, as {@link Sheets} places it. */
    private float sheetLeft(float s) {
        float sheetW = pageWidth() * s;
        float columnW = Math.max(scroller.viewportWidth(), sheetW + 2 * GAP);
        return Math.max(GAP, (columnW - sheetW) / 2);
    }

    /** Whether a mask holds the modifier a zoom is asked with, and nothing else but Shift. */
    private static boolean zoomChord(int modifiers) {
        int held = modifiers & ~Keys.MOD_SHIFT;
        return held == Accelerator.commandModifier() || held == Keys.MOD_CONTROL;
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed() || event.isConsumed()) {
            return;
        }
        float middleX = scroller.viewportWidth() / 2;
        float middleY = scroller.viewportHeight() / 2;
        if (zoomChord(event.modifiers())) {
            int typed = event.layoutCodePoint();
            if (typed == '+' || typed == '=' || event.key() == Keys.EQUAL) {
                zoomBy(KEY_STEP, middleX, middleY);
            } else if (typed == '-' || event.key() == Keys.MINUS) {
                zoomBy(1 / KEY_STEP, middleX, middleY);
            } else if (typed == '0' || event.key() == Keys.NUM_0) {
                zoomTo(1f, middleX, middleY);
            } else {
                return;
            }
            event.consume();
            return;
        }
        if (event.modifiers() != 0) {
            return;
        }
        float x = scroller.offsetX();
        float y = scroller.offsetY();
        switch (event.key()) {
            case Keys.UP -> y -= ARROW_STEP;
            case Keys.DOWN -> y += ARROW_STEP;
            case Keys.LEFT -> x -= ARROW_STEP;
            case Keys.RIGHT -> x += ARROW_STEP;
            case Keys.PAGE_UP -> y -= scroller.viewportHeight();
            case Keys.PAGE_DOWN -> y += scroller.viewportHeight();
            case Keys.HOME -> y = 0;
            case Keys.END -> y = scroller.maxOffsetY();
            default -> {
                return;
            }
        }
        x = Math.max(0, Math.min(scroller.maxOffsetX(), x));
        y = Math.max(0, Math.min(scroller.maxOffsetY(), y));
        if (x != scroller.offsetX() || y != scroller.offsetY()) {
            scroller.scrollTo(x, y);
            invalidate();
            event.consume();
        }
    }

    @Override
    protected void onFocusGained() {
        invalidate();
    }

    @Override
    protected void onFocusLost() {
        invalidate();
    }

    // ------------------------------------------------------------------ layout

    /** Document units to view points, at the zoom and the view's size. */
    private float scale() {
        float pageW = pageWidth();
        float pageH = pageHeight();
        // The preview's own box, not the scroll view's viewport: the sheets are measured inside
        // the scroll view's layout, before its viewport is known for this size.
        float viewW = Math.max(1, width() - 2 * GAP);
        float viewH = Math.max(1, height() - 2 * GAP);
        return switch (zoom) {
            case FIT_WIDTH -> viewW / pageW;
            case FIT_PAGE -> Math.min(viewW / pageW, viewH / pageH);
            case PERCENT -> percent / 100f;
        };
    }

    private float pageWidth() {
        return document.paper().width() * Pages.UNITS_PER_POINT;
    }

    private float pageHeight() {
        return document.paper().height() * Pages.UNITS_PER_POINT;
    }

    private float sheetHeight() {
        return pageHeight() * scale();
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        float w = constraints.hasBoundedWidth() ? constraints.maxWidth() : 480;
        float h = constraints.hasBoundedHeight() ? constraints.maxHeight() : 640;
        return constraints.constrain(w, h);
    }

    @Override
    protected void onLayout() {
        scroller.measure(Constraints.tight(width(), height()));
        scroller.layoutBox(0, 0, width(), height());
        float[] to = pendingScroll;
        if (to != null) {
            pendingScroll = null;
            scroller.scrollTo(Math.max(0, Math.min(scroller.maxOffsetX(), to[0])),
                    Math.max(0, Math.min(scroller.maxOffsetY(), to[1])));
        }
    }

    @Override
    protected void onPaintOverlay(Canvas canvas) {
        if (!isFocused()) {
            return;
        }
        // Inside the preview's own box: the pages fill it to its edges, and a ring outside it
        // would be cut by whatever holds the preview.
        Theme theme = Theme.of(this);
        float inset = Strokes.FOCUS_RING / 2;
        canvas.drawRoundRect(inset, inset, width() - 2 * inset, height() - 2 * inset, 0,
                Strokes.FOCUS_RING, theme.focusRing());
    }

    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.GROUP);
        a.name(PrintStrings.PREVIEW);
        a.description(limn.i18n.I18nString.literal(PrintStrings.PAGE_OF.format(currentPage(), pageCount())));
    }

    /** The column of sheets inside the scroll view. */
    private final class Sheets extends Widget<Sheets> {

        /** A pinch, or the wheel with the zoom's key, around the pointer. */
        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() != MouseEvent.Type.ZOOM) {
                return;
            }
            limn.math.Vec2 local = sceneToLocal(event.x(), event.y());
            zoomBy(event.scale(), local.x() - scroller.offsetX(), local.y() - scroller.offsetY());
            event.consume();
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            float s = scale();
            int count = Math.max(1, pages.size());
            float sheetW = pageWidth() * s;
            float w = Math.max(constraints.hasBoundedWidth() ? constraints.maxWidth() : 0, sheetW + 2 * GAP);
            float h = GAP + count * (pageHeight() * s + GAP);
            return new Size(w, h);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            canvas.fillRect(0, 0, width(), height(), theme.isDark()
                    ? theme.background() : theme.background().lerp(Color.BLACK, 0.06f));
            float s = scale();
            float sheetW = pageWidth() * s;
            float sheetH = pageHeight() * s;
            float x = Math.max(GAP, (width() - sheetW) / 2);
            @Nullable Rect visible = canvas.clipBounds();
            for (int i = 0; i < pages.size(); i++) {
                float y = GAP + i * (sheetH + GAP);
                if (visible != null && (y > visible.y() + visible.height() || y + sheetH < visible.y())) {
                    continue; // a sheet scrolled out of the view
                }
                RoundRect sheet = RoundRect.of(x, y, sheetW, sheetH, 0);
                theme.drawElevation(canvas, sheet, Elevation.RAISED);
                canvas.fillRect(x, y, sheetW, sheetH, Color.WHITE);
                int depth = canvas.saveCount();
                canvas.save();
                try {
                    canvas.clipRect(x, y, sheetW, sheetH);
                    canvas.translate(x, y);
                    canvas.scale(s, s);
                    pages.get(i).replay(canvas);
                } finally {
                    canvas.restoreToCount(depth);
                }
            }
        }
    }
}
