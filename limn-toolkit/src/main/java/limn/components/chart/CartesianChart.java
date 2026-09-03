package limn.components.chart;

import limn.components.SizeTokens;
import limn.components.Strokes;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.scene.LayoutDirection;

import java.util.ArrayList;
import java.util.List;

/**
 * The half of a chart that has axes: a category axis with one slot per label and a value
 * axis with a scale over the numbers. {@link BarChart} and {@link LineChart} share it;
 * {@link DonutChart} does not, which is why this is its own class rather than part of
 * {@link Chart}.
 *
 * <p>Both axes are automatic (see {@link ChartAxis}) and both can be pinned. The chart
 * resolves the scale from the values the application set (never from the animated
 * in-between), so an axis does not slide around while its bars grow into it.
 *
 * <p><b>Orientation is a property, not a class.</b> {@link #setHorizontal(boolean)} swaps
 * which screen axis carries the values, and everything downstream (grid, labels, marks,
 * hit-testing, the tooltip) runs through {@link #valuePosition(double)} and
 * {@link #bandStart(int)}, so the two orientations cannot drift apart.
 *
 * <p><b>Direction goes through the same two doors.</b> A chart read right to left runs its
 * value scale and its category sequence from the right edge of the plot, and the axis labels
 * sit in a gutter on that side. Both mirrors live in {@code valuePosition} and
 * {@code bandStart}, so a subclass placing a mark or hit-testing one gets the mirror for free
 * and must not apply a second one. The vertical axis is not a reading axis and never moves.
 */
public abstract class CartesianChart extends Chart {

    private final ChartAxis valueAxis;
    private final ChartAxis categoryAxis = new ChartAxis(false, false);
    private boolean stacked;
    private boolean horizontal;

    // Resolved before every paint and every pointer test.
    private ChartAxis.Scale scale = new ChartAxis.Scale(0, 1, 1);
    private String[] tickLabels = new String[0];
    // The direction this pass is laying out for, resolved beside the plot rectangle and read by
    // the geometry helpers instead of the axis. It sits here rather than being threaded as a
    // parameter because valuePosition and bandStart are the subclass contract: a subclass calls
    // them from its own paint and its own hit test and has no direction in hand to pass.
    private boolean rtl;
    private float plotX;
    private float plotY;
    private float plotWidth;
    private float plotHeight;
    private List<String> cachedStackKeys = List.of();
    private int stackKeysGeneration = -1;
    // Cache keys for the two O(categories) scans below. Both fold in the UI language,
    // because both cache resolved text and a language change re-reads nothing on its own —
    // and the language is the i18n epoch AND the effective locale, because this widget's own
    // declared locale (ADR 035) moves the resolution without moving the epoch. Both also fold
    // in the direction: what they hold is a width, the width is taken from a line shaped for a
    // paragraph direction, and nothing else in either key moves when that direction does.
    // Without it the plot keeps a gutter measured for the other direction and is told it is
    // current, which no screenshot shows and every geometry query is wrong about.
    private int scaleGeneration = -1;
    private long scaleEpoch = -1;
    private java.util.Locale scaleLocale;
    private Font scaleFont;
    private boolean scaleRtl;
    private float tickLabelWidth;
    private int categoryScanGeneration = -1;
    private long categoryScanEpoch = -1;
    private java.util.Locale categoryScanLocale;
    private Font categoryScanFont;
    private boolean categoryScanRtl;
    private float widestCategoryLabel;

    /** The sideways category labels as drawn, cut to the gutter; see {@link #shownCategoryLabel}. */
    private ShapedText[] shownCategoryLabels;
    private int shownGeneration = -1;
    private long shownEpoch = -1;
    private java.util.Locale shownLocale;
    private Font shownFont;
    private boolean shownRtl;
    private float shownAvailable = Float.NaN;

    CartesianChart(boolean beginAtZero) {
        valueAxis = new ChartAxis(beginAtZero, true);
        valueAxis.owner = this;
        categoryAxis.owner = this;
    }

    /** The axis carrying the numbers: vertical unless {@link #setHorizontal(boolean)}. */
    public final ChartAxis valueAxis() {
        return valueAxis;
    }

    /** The axis carrying the labels: horizontal unless {@link #setHorizontal(boolean)}. */
    public final ChartAxis categoryAxis() {
        return categoryAxis;
    }

    /** Whether series accumulate instead of standing beside each other. */
    public final boolean isStacked() {
        return stacked;
    }

    /**
     * Stacks the series that share a {@linkplain ChartSeries#setStack stack key} on top of
     * each other. Positive and negative values stack away from zero on their own sides, so
     * a mixed series does not cancel itself out mid-column.
     */
    public final CartesianChart setStacked(boolean value) {
        Ui.checkUiThread();
        if (stacked != value) {
            beginDataChange();
            stacked = value;
            endDataChange();
        }
        return this;
    }

    /** Whether the value axis runs left-to-right instead of bottom-to-top. */
    public final boolean isHorizontal() {
        return horizontal;
    }

    /**
     * Turns the chart on its side: values run left-to-right and categories down the left
     * edge. The layout long labels want: a vertical axis gives each one a whole row
     * instead of a band the width of one bar.
     */
    public final CartesianChart setHorizontal(boolean value) {
        Ui.checkUiThread();
        if (horizontal != value) {
            horizontal = value;
            invalidate();
        }
        return this;
    }

    // ------------------------------------------------------------- geometry

    /** Left edge of the plot region, in the chart's local coordinates. */
    protected final float plotX() {
        return plotX;
    }

    /** Top edge of the plot region, in the chart's local coordinates. */
    protected final float plotY() {
        return plotY;
    }

    /** Width of the plot region. */
    protected final float plotWidth() {
        return plotWidth;
    }

    /** Height of the plot region. */
    protected final float plotHeight() {
        return plotHeight;
    }

    /** The resolved value scale: the two ends drawn and the tick spacing between them. */
    protected final ChartAxis.Scale scale() {
        return scale;
    }

    /**
     * Where {@code value} falls along the value axis, in local coordinates.
     *
     * <p>Only the sideways branch is on the reading axis, and only it mirrors: the low end of
     * the scale sits where reading starts, so it is the plot's right edge reading right to
     * left. The upright branch is the vertical axis, which no direction moves.
     */
    protected final float valuePosition(double value) {
        double span = scale.max() - scale.min();
        double t = span == 0 ? 0 : (value - scale.min()) / span;
        if (!horizontal) {
            return (float) (plotY + plotHeight - t * plotHeight);
        }
        return (float) (rtl ? plotX + plotWidth - t * plotWidth : plotX + t * plotWidth);
    }

    /** The width (or height, turned sideways) of one category slot. */
    protected final float bandSize() {
        int count = Math.max(1, categoryCount());
        return (horizontal ? plotHeight : plotWidth) / count;
    }

    /**
     * The <b>left</b> edge (the top edge, turned sideways) of category {@code index}'s band, in
     * local coordinates.
     *
     * <p>What mirrors is the coordinate, not the walk along the axis: the step from one category
     * to the next stays {@code index * bandSize()} and only the point it is turned into is
     * reflected, so a band is exactly one band wide at either end of the plot.
     *
     * <p>What this returns is a band's box, not the point reading starts from. {@link
     * #bandCenter}, the hover band and every mark a subclass places compose from a left edge, so
     * reflecting the point instead would move all three into the neighbouring band. The lines
     * <em>between</em> bands are a different question, and {@link #bandBoundary} answers it.
     */
    protected final float bandStart(int index) {
        float along = index * bandSize();
        if (horizontal) {
            return plotY + along;
        }
        return plotX + (rtl ? plotWidth - along - bandSize() : along);
    }

    /**
     * The middle of category {@code index} along the category axis.
     *
     * <p>A centre, and centres do not move: once {@link #bandStart} names the band's own left
     * edge in either direction, this is already the middle of the right band.
     */
    protected final float bandCenter(int index) {
        return bandStart(index) + bandSize() / 2;
    }

    /**
     * The {@code index}-th line between two category bands, counting from the plot's left edge
     * (its top edge, turned sideways). There are {@code categoryCount() + 1} of them and the
     * first and last lie on the plot's own edges.
     *
     * <p>Direction-free, which is why it is not {@link #bandStart}. The bands tile the plot
     * evenly, so the set of lines between them is the same set read either way; all that changes
     * is which band each line belongs to, and a grid does not ask.
     */
    private float bandBoundary(int index) {
        return (horizontal ? plotY : plotX) + index * bandSize();
    }

    /**
     * The value where the axis crosses the plot: zero when the scale contains it, the
     * nearer end otherwise. Bars stand on it and areas fill down to it.
     */
    @Override
    protected final double animationBaseline() {
        return Math.max(scale.min(), Math.min(scale.max(), 0));
    }

    /**
     * How much of its stack lies between the baseline and series {@code seriesIndex} at
     * {@code index}, following the animation so a stack re-flows rather than jumping.
     * {@code 0} when the chart does not stack.
     */
    protected final double stackBase(int seriesIndex, int index) {
        if (!stacked) {
            return 0;
        }
        String key = series(seriesIndex).stack();
        double own = drawnValue(seriesIndex, index);
        boolean negative = own < 0;
        double base = 0;
        for (int i = 0; i < seriesIndex; i++) {
            if (!series(i).stack().equals(key)) {
                continue;
            }
            double value = drawnValue(i, index);
            if (Double.isNaN(value) || value < 0 != negative) {
                continue; // the other side of zero stacks the other way
            }
            base += value;
        }
        return base;
    }

    /** The sum of the stack {@code seriesIndex} belongs to at {@code index}, for shares. */
    protected final double stackTotal(int seriesIndex, int index) {
        if (!stacked) {
            return Double.NaN;
        }
        String key = series(seriesIndex).stack();
        double total = 0;
        for (int i = 0; i < seriesCount(); i++) {
            ChartSeries s = series(i);
            if (!s.isVisible() || !s.stack().equals(key)) {
                continue;
            }
            double value = s.value(index);
            if (!Double.isNaN(value)) {
                total += value;
            }
        }
        return total;
    }

    /**
     * The distinct stack keys of the visible series, in first-appearance order: one bar
     * slot per key per category.
     *
     * <p>Cached against {@link #dataGeneration()} because it is walked once per bar per
     * frame: rebuilt per call, a stacked chart allocated a list for every bar it drew.
     */
    protected final List<String> stackKeys() {
        if (stackKeysGeneration != dataGeneration()) {
            List<String> keys = new ArrayList<>(4);
            for (int i = 0; i < seriesCount(); i++) {
                ChartSeries s = series(i);
                if (s.isVisible() && !keys.contains(s.stack())) {
                    keys.add(s.stack());
                }
            }
            cachedStackKeys = keys;
            stackKeysGeneration = dataGeneration();
        }
        return cachedStackKeys;
    }

    // ---------------------------------------------------------------- labels

    /**
     * One axis label as a shaped line.
     *
     * <p>The chart's direction is offered as the fallback and not imposed: the first-strong rule
     * still decides everything a strong character can decide, so a Latin category name in an
     * Arabic chart still reads left to right. Shaped rather than handed to the canvas as a
     * string because the canvas would have to guess that fallback, and the guess it is obliged
     * to make is the one this chart can answer.
     */
    private ShapedText shapeLabel(String text, Font font) {
        return shapeText(text, font);
    }

    /**
     * The width a label will actually be drawn at, which is what every gutter here is sized
     * from. Measured through the shaper rather than through the ruler's per-string form so that
     * the width a gutter reserves and the width drawn into it come from the same paragraph.
     */
    private float labelWidth(String text, Font font) {
        return shapeLabel(text, font).metrics().width();
    }

    // ------------------------------------------------------- subclass contract

    /** Paints the marks inside the plot region; grid and axes are already down. */
    protected abstract void paintMarks(Canvas canvas);

    /**
     * Which series a pointer inside {@code category} is reporting: the mark it is on if
     * any, and otherwise the nearest one, so a click in the whitespace of a column still
     * names a datum. {@code -1} when the category has nothing visible in it.
     */
    protected abstract int pickSeries(int category, float localX, float localY);

    /** The datum for one series at one category, anchored at that series' mark. */
    protected abstract ChartPoint pointOf(int seriesIndex, int category);

    // ---------------------------------------------------------------- painting

    @Override
    protected void paintContent(Canvas canvas, float x, float y, float w, float h) {
        Theme theme = Theme.current();
        SizeTokens t = tokens();
        // Resolved once for the whole pass, before anything that reads it. Two resolutions
        // inside one frame is how the grid ends up mirrored and the marks standing on it do not.
        rtl = layoutDirection() == LayoutDirection.RTL;
        resolveScale();
        layoutPlot(t, x, y, w, h);
        if (plotWidth <= 1 || plotHeight <= 1) {
            return;
        }
        paintGrid(canvas, t, theme);
        paintHoverBand(canvas, theme);
        paintMarks(canvas);
        paintAxisLabels(canvas, t, theme);
    }

    /**
     * Resolves the value scale, at most once per data change.
     *
     * <p>Called from the paint <em>and</em> from every pointer move, and it is O(series x
     * categories) plus one format call per tick, which is nothing at twelve points and a
     * scan of the whole dataset per mouse move at a thousand. The cache key is the data
     * generation (bumped by every value, visibility and axis change), the UI language
     * (the tick labels are formatted text) and the resolved direction (what it keeps is the
     * widest tick label's shaped width, and the paragraph direction is an input to that).
     * {@code ChartLayoutCostTest} pins that this stays off the per-frame path.
     */
    private void resolveScale() {
        Font font = tokens().label();
        java.util.Locale locale = limn.i18n.I18n.locale();
        if (scaleGeneration == dataGeneration() && scaleEpoch == limn.i18n.I18n.epoch()
                && locale.equals(scaleLocale) && scaleFont == font && scaleRtl == rtl) {
            return;
        }
        scaleGeneration = dataGeneration();
        scaleEpoch = limn.i18n.I18n.epoch();
        scaleLocale = locale;
        scaleFont = font;
        scaleRtl = rtl;
        resolveScaleUncached(font);
    }

    /**
     * Reads the data (the values as set, never the animated ones) and resolves the value
     * scale. An axis that followed the animation would slide under the marks growing into
     * it, and every tick label would change on every frame.
     */
    private void resolveScaleUncached(Font labelFont) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int categories = categoryCount();
        if (stacked) {
            List<String> keys = stackKeys();
            for (int c = 0; c < categories; c++) {
                for (String key : keys) {
                    double positive = 0;
                    double negative = 0;
                    for (int i = 0; i < seriesCount(); i++) {
                        ChartSeries s = series(i);
                        if (!s.isVisible() || !s.stack().equals(key)) {
                            continue;
                        }
                        double value = s.value(c);
                        if (Double.isNaN(value)) {
                            continue;
                        }
                        if (value < 0) {
                            negative += value;
                        } else {
                            positive += value;
                        }
                    }
                    min = Math.min(min, negative);
                    max = Math.max(max, positive);
                }
            }
        } else {
            for (int i = 0; i < seriesCount(); i++) {
                ChartSeries s = series(i);
                if (!s.isVisible()) {
                    continue;
                }
                for (int c = 0; c < categories; c++) {
                    double value = s.value(c);
                    if (!Double.isNaN(value)) {
                        min = Math.min(min, value);
                        max = Math.max(max, value);
                    }
                }
            }
        }
        if (min > max) {
            min = 0;
            max = 1;
        }
        scale = valueAxis.resolve(min, max);
        tickLabels = new String[scale.tickCount()];
        tickLabelWidth = 0;
        for (int i = 0; i < tickLabels.length; i++) {
            tickLabels[i] = valueAxis.format().apply(scale.tick(i));
            tickLabelWidth = Math.max(tickLabelWidth, labelWidth(tickLabels[i], labelFont));
        }
    }

    /**
     * The widest category label, measured once per data change rather than once per frame.
     * It decides the gutter of a horizontal chart and the label-skip step of a vertical
     * one, so both used to walk every label on every paint and every pointer move.
     *
     * <p>The direction is in the key for the reason the font is: it is one of the inputs to
     * the width being cached, and no other part of the key moves when it changes.
     */
    private float widestCategoryLabel(Font font) {
        java.util.Locale locale = limn.i18n.I18n.locale();
        if (categoryScanGeneration == dataGeneration() && categoryScanFont == font
                && categoryScanEpoch == limn.i18n.I18n.epoch()
                && locale.equals(categoryScanLocale) && categoryScanRtl == rtl) {
            return widestCategoryLabel;
        }
        float widest = 0;
        for (int i = 0; i < categoryCount(); i++) {
            widest = Math.max(widest, labelWidth(label(i), font));
        }
        widestCategoryLabel = widest;
        categoryScanGeneration = dataGeneration();
        categoryScanFont = font;
        categoryScanEpoch = limn.i18n.I18n.epoch();
        categoryScanLocale = locale;
        categoryScanRtl = rtl;
        return widest;
    }

    /**
     * Category label {@code i} cut to the gutter and shaped, held from one paint to the next
     * under the same key {@link #widestCategoryLabel} uses plus the gutter's width, so that a
     * settled chart shapes nothing at all when it paints. The line is checked against the
     * ruler's epoch as well: a held shaping outlives a face swap only if it is re-shaped.
     */
    private ShapedText shownCategoryLabel(int i, String text, Font font, float available) {
        java.util.Locale locale = limn.i18n.I18n.locale();
        int count = categoryCount();
        if (shownCategoryLabels == null || shownCategoryLabels.length != count
                || shownGeneration != dataGeneration() || shownFont != font
                || shownEpoch != limn.i18n.I18n.epoch() || !locale.equals(shownLocale)
                || shownRtl != rtl || shownAvailable != available) {
            shownCategoryLabels = new ShapedText[count];
            shownGeneration = dataGeneration();
            shownFont = font;
            shownEpoch = limn.i18n.I18n.epoch();
            shownLocale = locale;
            shownRtl = rtl;
            shownAvailable = available;
        }
        ShapedText line = shownCategoryLabels[i];
        if (line == null || !line.matches(line.text(), font, line.baseDirection(), textRuler())) {
            line = shapeLabel(ellipsize(text, font, available), font);
            shownCategoryLabels[i] = line;
        }
        return line;
    }

    /** Takes the axis gutters out of the content box; what is left is the plot. */
    private void layoutPlot(SizeTokens t, float x, float y, float w, float h) {
        Font font = t.label();
        float gap = t.spacingSmall();
        float lineHeight = measure("X", font).lineHeight();

        float valueGutter = 0;
        if (valueAxis.isVisible()) {
            valueGutter = horizontal ? lineHeight + gap : tickLabelWidth + gap;
        }
        if (valueAxis.title() != null) {
            valueGutter += lineHeight;
        }

        float categoryGutter = 0;
        if (categoryAxis.isVisible() && categoryCount() > 0) {
            if (horizontal) {
                categoryGutter = Math.min(widestCategoryLabel(font), w * 0.35f) + gap;
            } else {
                categoryGutter = lineHeight + gap;
            }
        }
        if (categoryAxis.title() != null) {
            categoryGutter += lineHeight;
        }

        // Whichever axis has its labels beside the plot rather than under it, they are drawn in
        // the gutter on the side reading starts from: this is a leading inset and not a left one.
        float leading = horizontal ? categoryGutter : valueGutter;
        float bottom = horizontal ? valueGutter : categoryGutter;
        // Half a line of headroom at the top: the topmost tick label is centred on the
        // plot's top edge and would otherwise be cut in half by the content box.
        float top = valueAxis.isVisible() && !horizontal ? lineHeight / 2 : 0;
        // The same headroom for the last tick label of a sideways value axis, which is centred
        // on the high end of the scale. That end is where the value axis finishes reading, so
        // the reserve is a trailing one and follows the scale to the other side.
        float trailing = valueAxis.isVisible() && horizontal
                ? labelWidth(tickLabels.length > 0 ? tickLabels[tickLabels.length - 1] : "", font)
                / 2 : 0;

        // The one place the two magnitudes are assigned to physical sides. Everything above is a
        // distance from an edge that has no side yet, which is what lets each of them stay one
        // expression; the width below is their sum and is the same either way round.
        plotX = x + (rtl ? trailing : leading);
        plotY = y + top;
        plotWidth = Math.max(0, w - leading - trailing);
        plotHeight = Math.max(0, h - top - bottom);
    }

    private void paintGrid(Canvas canvas, SizeTokens t, Theme theme) {
        Color grid = theme.outline.withAlpha(0.45f);
        Color axisLine = theme.outline;
        if (valueAxis.hasGrid()) {
            for (int i = 0; i < scale.tickCount(); i++) {
                double value = scale.tick(i);
                float p = valuePosition(value);
                boolean zero = value == 0;
                Color ink = zero ? axisLine : grid;
                if (horizontal) {
                    canvas.drawLine(p, plotY, p, plotY + plotHeight, Strokes.HAIRLINE, ink);
                } else {
                    canvas.drawLine(plotX, p, plotX + plotWidth, p, Strokes.HAIRLINE, ink);
                }
            }
        }
        if (categoryAxis.hasGrid()) {
            // Boundaries and not band edges: the loop runs to categoryCount() inclusive, so its
            // last line is the far edge of the last band rather than the start of a band at all.
            // The two used to be the same expression and stopped being one when the bands
            // mirrored; asking bandStart for index count now names a band outside the plot.
            for (int i = 0; i <= categoryCount(); i++) {
                float p = bandBoundary(i);
                if (horizontal) {
                    canvas.drawLine(plotX, p, plotX + plotWidth, p, Strokes.HAIRLINE, grid);
                } else {
                    canvas.drawLine(p, plotY, p, plotY + plotHeight, Strokes.HAIRLINE, grid);
                }
            }
        }
        // The baseline itself, drawn even when the grid is off: marks need something to
        // stand on, and a scale that never reaches zero still has a floor.
        float base = valuePosition(animationBaseline());
        if (horizontal) {
            canvas.drawLine(base, plotY, base, plotY + plotHeight, Strokes.HAIRLINE, axisLine);
        } else {
            canvas.drawLine(plotX, base, plotX + plotWidth, base, Strokes.HAIRLINE, axisLine);
        }
    }

    /** A soft band behind the hovered category, so the tooltip and the marks agree. */
    private void paintHoverBand(Canvas canvas, Theme theme) {
        ChartPoint point = hoveredPoint();
        if (point == null || tooltipMode() != TooltipMode.INDEX) {
            return;
        }
        float start = bandStart(point.index());
        float size = bandSize();
        Color ink = theme.outline.withAlpha(0.28f);
        if (horizontal) {
            canvas.fillRect(plotX, start, plotWidth, size, ink);
        } else {
            canvas.fillRect(start, plotY, size, plotHeight, ink);
        }
    }

    private void paintAxisLabels(Canvas canvas, SizeTokens t, Theme theme) {
        Font font = t.label();
        float gap = t.spacingSmall();
        if (valueAxis.isVisible()) {
            for (int i = 0; i < tickLabels.length; i++) {
                ShapedText line = shapeLabel(tickLabels[i], font);
                TextMetrics m = line.metrics();
                float p = valuePosition(scale.tick(i));
                if (horizontal) {
                    // Centred on its own tick, and a centre does not move: the tick itself
                    // already carries the mirror out of valuePosition.
                    canvas.drawText(line, p - m.width() / 2, plotY + plotHeight + gap + m.ascent(),
                            theme.textMuted);
                } else {
                    canvas.drawText(line, gutterLabelX(gap, m.width()),
                            p + m.height() / 2 - m.descent(), theme.textMuted);
                }
            }
        }
        if (categoryAxis.isVisible()) {
            paintCategoryLabels(canvas, font, gap, theme);
        }
        paintAxisTitles(canvas, t, font, theme);
    }

    /**
     * Where a label drawn in the axis gutter puts its <b>left</b> edge: hard against the plot on
     * the side reading starts from, one {@code gap} clear of it.
     *
     * <p>Reading left to right that side is the plot's left edge, so the label is pushed back by
     * its own width to finish against it; reading right to left it is the plot's right edge and
     * the label simply begins there. One expression for the value ticks and the sideways
     * category labels together, because they share the gutter and must share its edge.
     */
    private float gutterLabelX(float gap, float labelWidth) {
        return rtl ? plotX + plotWidth + gap : plotX - gap - labelWidth;
    }

    /**
     * Category labels, thinned until they fit. Every label that is drawn is drawn whole:
     * the alternative (drawing them all and letting them collide) is the failure mode
     * this exists to prevent, and rotating them trades one unreadable layout for another.
     */
    private void paintCategoryLabels(Canvas canvas, Font font, float gap, Theme theme) {
        int count = categoryCount();
        if (count == 0) {
            return;
        }
        float band = bandSize();
        // What has to fit the band is the label's extent ALONG the category axis, and the
        // orientation decides which extent that is: sideways the labels stack, so it is one
        // line height each; upright they sit next to each other, so it is the widest.
        float extent = horizontal
                ? measure("X", font).lineHeight() + gap
                : widestCategoryLabel(font) + gap * 2;
        int step = Math.max(1, (int) Math.ceil(extent / Math.max(1, band)));
        for (int i = 0; i < count; i += step) {
            String text = label(i);
            if (text.isEmpty()) {
                continue;
            }
            if (horizontal) {
                // The gutter, not the distance to the widget's own edge: plotX is absolute, so
                // subtracting only the gap hands the label everything outside the content as
                // well: the outer padding, and a legend on that side to draw over. The mirrored
                // form measures the same span inwards from the content's other edge, and it is
                // the same mistake to make from there.
                float available = rtl
                        ? contentLeft() + contentBoxWidth() - (plotX + plotWidth) - gap
                        : plotX - gap - contentLeft();
                ShapedText line = shownCategoryLabel(i, text, font, available);
                TextMetrics m = line.metrics();
                float center = bandCenter(i);
                canvas.drawText(line, gutterLabelX(gap, m.width()),
                        center + m.height() / 2 - m.descent(), theme.textMuted);
            } else {
                // Centred on its band, which bandCenter already put on the right side of the plot.
                ShapedText line = shapeLabel(text, font);
                TextMetrics m = line.metrics();
                float center = bandCenter(i);
                canvas.drawText(line, center - m.width() / 2,
                        plotY + plotHeight + gap + m.ascent(), theme.textMuted);
            }
        }
    }

    private void paintAxisTitles(Canvas canvas, SizeTokens t, Font font, Theme theme) {
        String valueTitle = valueAxis.title();
        String categoryTitle = categoryAxis.title();
        if (valueTitle != null) {
            if (horizontal) {
                drawCentered(canvas, valueTitle, font, theme, plotX + plotWidth / 2,
                        height() - t.spacingSmall() - measure(valueTitle, font).descent());
            } else {
                drawRotated(canvas, valueTitle, font, theme, t.spacingSmall(),
                        plotY + plotHeight / 2);
            }
        }
        if (categoryTitle != null) {
            if (horizontal) {
                drawRotated(canvas, categoryTitle, font, theme, t.spacingSmall(),
                        plotY + plotHeight / 2);
            } else {
                drawCentered(canvas, categoryTitle, font, theme, plotX + plotWidth / 2,
                        height() - t.spacingSmall() - measure(categoryTitle, font).descent());
            }
        }
    }

    /** A title centred under the plot. A centre is the one x that is the same in both directions. */
    private void drawCentered(Canvas canvas, String text, Font font, Theme theme,
                              float centerX, float baseline) {
        ShapedText line = shapeLabel(text, font);
        canvas.drawText(line, centerX - line.metrics().width() / 2, baseline, theme.textMuted);
    }

    /**
     * An axis title reading bottom-to-top, the way every other toolkit draws one, parked {@code
     * inset} in from the edge the interface reads from.
     *
     * <p>Only the side it parks on moves. Which way the rotated line itself reads is a question
     * about vertical writing, and this is a horizontal chart turned on its side rather than a
     * vertical writing mode.
     *
     * <p>What gets reflected is the ink, not the baseline: the ink runs from one ascent before
     * the baseline to one descent after it, so the mirrored form measures the descent in from
     * the far edge where the first measures the ascent in from the near one. It parks against
     * the widget's own edge rather than the content box's, on both sides, which is what it did
     * before it could move at all.
     */
    private void drawRotated(Canvas canvas, String text, Font font, Theme theme,
                             float inset, float centerY) {
        ShapedText line = shapeLabel(text, font);
        TextMetrics m = line.metrics();
        float baselineX = rtl ? width() - inset - m.descent() : inset + m.ascent();
        canvas.save();
        try {
            canvas.translate(baselineX, centerY);
            canvas.rotate((float) -Math.PI / 2);
            canvas.drawText(line, -m.width() / 2, 0, theme.textMuted);
        } finally {
            canvas.restore();
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected ChartPoint pickAt(float localX, float localY) {
        // The pointer path resolves the same direction, the same scale and the same plot
        // rectangle the frame was drawn with; anything else would report a different bar than
        // the one aimed at, and a direction resolved on one path and not the other would report
        // the bar mirrored about the middle of the plot.
        rtl = layoutDirection() == LayoutDirection.RTL;
        resolveScale();
        layoutPlot(tokens(), contentLeft(), contentTop(), contentBoxWidth(), contentBoxHeight());
        if (plotWidth <= 0 || plotHeight <= 0) {
            return null;
        }
        if (localX < plotX || localX > plotX + plotWidth
                || localY < plotY || localY > plotY + plotHeight) {
            return null;
        }
        int category = categoryAt(localX, localY);
        if (category < 0) {
            return null;
        }
        int seriesIndex = pickSeries(category, localX, localY);
        return seriesIndex < 0 ? null : pointOf(seriesIndex, category);
    }

    @Override
    protected List<ChartPoint> tooltipRows(ChartPoint picked) {
        if (tooltipMode() == TooltipMode.POINT) {
            return List.of(pointOf(picked.seriesIndex(), picked.index()));
        }
        List<ChartPoint> rows = new ArrayList<>(seriesCount());
        for (int i = 0; i < seriesCount(); i++) {
            ChartSeries s = series(i);
            if (s.isVisible() && !Double.isNaN(s.value(picked.index()))) {
                rows.add(pointOf(i, picked.index()));
            }
        }
        return rows.isEmpty() ? List.of(picked) : rows;
    }

    /**
     * The category a local point falls in, or {@code -1} outside the plot.
     *
     * <p>The exact inverse of {@link #bandStart}: it counts from the same end of the plot that
     * category zero's band begins at, so the band the pointer names and the band painted under
     * it are the same one. A mismatch here shows up as a tooltip naming the neighbouring bar and
     * as nothing at all in a screenshot.
     */
    protected final int categoryAt(float localX, float localY) {
        int count = categoryCount();
        if (count == 0) {
            return -1;
        }
        float along = horizontal
                ? localY - plotY
                : (rtl ? plotX + plotWidth - localX : localX - plotX);
        float extent = horizontal ? plotHeight : plotWidth;
        if (along < 0 || along > extent) {
            return -1;
        }
        return Math.max(0, Math.min(count - 1, (int) (along / (extent / count))));
    }
}
