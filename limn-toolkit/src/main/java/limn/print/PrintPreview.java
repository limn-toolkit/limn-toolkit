package limn.print;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.Elevation;
import limn.components.ScrollView;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Rect;
import limn.graphics.RoundRect;
import limn.print.internal.Recording;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
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
 * <p>A reader hears a group named "Print preview", described by the page in the middle of the view
 * and the count ("Page 3 of 12"); the pages' own text is not published to it.
 *
 * <p>The pages are recorded when the preview is made and again by {@link #refresh()}; a face the
 * text asked for that was still loading records them again once it has loaded. UI thread only.
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

    private final Document document;
    private final Sheets sheets = new Sheets();
    private final ScrollView scroller = new ScrollView(sheets);
    private List<Recording> pages = List.of();
    private Zoom zoom = Zoom.FIT_WIDTH;
    private float percent = 100;
    private long refreshes;

    /**
     * @param document the document to show; its pages are recorded now
     */
    public PrintPreview(Document document) {
        Ui.checkUiThread();
        this.document = Objects.requireNonNull(document, "document");
        add(scroller);
        refresh();
    }

    /**
     * Records the document's pages again and shows them: after the application changed what its
     * widgets show, or the document's paper.
     *
     * @return this preview
     */
    public PrintPreview refresh() {
        Ui.checkUiThread();
        long asked = ++refreshes;
        document.recordSettled(result -> {
            if (asked == refreshes) {
                pages = result.pages();
                markNeedsLayout();
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
        markNeedsLayout();
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
        markNeedsLayout();
        return this;
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
    }

    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.GROUP);
        a.name(PrintStrings.PREVIEW);
        a.description(limn.i18n.I18nString.literal(PrintStrings.PAGE_OF.format(currentPage(), pageCount())));
    }

    /** The column of sheets inside the scroll view. */
    private final class Sheets extends Widget<Sheets> {

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
