package limn.components.chart;

import limn.components.SizeTokens;
import limn.components.Strokes;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.TextMetrics;

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
 */
public abstract class CartesianChart extends Chart {

    private final ChartAxis valueAxis;
    private final ChartAxis categoryAxis = new ChartAxis(false, false);
    private boolean stacked;
    private boolean horizontal;

    // Resolved before every paint and every pointer test.
    private ChartAxis.Scale scale = new ChartAxis.Scale(0, 1, 1);
    private String[] tickLabels = new String[0];
    private float plotX;
    private float plotY;
    private float plotWidth;
    private float plotHeight;
    private List<String> cachedStackKeys = List.of();
    private int stackKeysGeneration = -1;
    // Cache keys for the two O(categories) scans below. Both fold in the UI language,
    // because both cache resolved text and a language change re-reads nothing on its own.
    private int scaleGeneration = -1;
    private long scaleEpoch = -1;
    private Font scaleFont;
    private float tickLabelWidth;
    private int categoryScanGeneration = -1;
    private long categoryScanEpoch = -1;
    private Font categoryScanFont;
    private float widestCategoryLabel;

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

    /** Where {@code value} falls along the value axis, in local coordinates. */
    protected final float valuePosition(double value) {
        double span = scale.max() - scale.min();
        double t = span == 0 ? 0 : (value - scale.min()) / span;
        return horizontal
                ? (float) (plotX + t * plotWidth)
                : (float) (plotY + plotHeight - t * plotHeight);
    }

    /** The width (or height, turned sideways) of one category slot. */
    protected final float bandSize() {
        int count = Math.max(1, categoryCount());
        return (horizontal ? plotHeight : plotWidth) / count;
    }

    /** Where category {@code index} starts along the category axis, in local coordinates. */
    protected final float bandStart(int index) {
        return (horizontal ? plotY : plotX) + index * bandSize();
    }

    /** The middle of category {@code index} along the category axis. */
    protected final float bandCenter(int index) {
        return bandStart(index) + bandSize() / 2;
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
     * generation (bumped by every value, visibility and axis change) and the UI language
     * (the tick labels are formatted text). {@code ChartLayoutCostTest} pins that this
     * stays off the per-frame path.
     */
    private void resolveScale() {
        Font font = tokens().label();
        if (scaleGeneration == dataGeneration() && scaleEpoch == limn.i18n.I18n.epoch()
                && scaleFont == font) {
            return;
        }
        scaleGeneration = dataGeneration();
        scaleEpoch = limn.i18n.I18n.epoch();
        scaleFont = font;
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
            tickLabelWidth = Math.max(tickLabelWidth, measure(tickLabels[i], labelFont).width());
        }
    }

    /**
     * The widest category label, measured once per data change rather than once per frame.
     * It decides the gutter of a horizontal chart and the label-skip step of a vertical
     * one, so both used to walk every label on every paint and every pointer move.
     */
    private float widestCategoryLabel(Font font) {
        if (categoryScanGeneration == dataGeneration() && categoryScanFont == font
                && categoryScanEpoch == limn.i18n.I18n.epoch()) {
            return widestCategoryLabel;
        }
        float widest = 0;
        for (int i = 0; i < categoryCount(); i++) {
            widest = Math.max(widest, measure(label(i), font).width());
        }
        widestCategoryLabel = widest;
        categoryScanGeneration = dataGeneration();
        categoryScanFont = font;
        categoryScanEpoch = limn.i18n.I18n.epoch();
        return widest;
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

        float left = horizontal ? categoryGutter : valueGutter;
        float bottom = horizontal ? valueGutter : categoryGutter;
        // Half a line of headroom at the top: the topmost tick label is centred on the
        // plot's top edge and would otherwise be cut in half by the content box.
        float top = valueAxis.isVisible() && !horizontal ? lineHeight / 2 : 0;
        float right = valueAxis.isVisible() && horizontal
                ? measure(tickLabels.length > 0 ? tickLabels[tickLabels.length - 1] : "", font)
                .width() / 2 : 0;

        plotX = x + left;
        plotY = y + top;
        plotWidth = Math.max(0, w - left - right);
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
            for (int i = 0; i <= categoryCount(); i++) {
                float p = bandStart(i);
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
                String label = tickLabels[i];
                TextMetrics m = measure(label, font);
                float p = valuePosition(scale.tick(i));
                if (horizontal) {
                    canvas.drawText(label, p - m.width() / 2, plotY + plotHeight + gap + m.ascent(),
                            font, theme.textMuted);
                } else {
                    canvas.drawText(label, plotX - gap - m.width(), p + m.height() / 2 - m.descent(),
                            font, theme.textMuted);
                }
            }
        }
        if (categoryAxis.isVisible()) {
            paintCategoryLabels(canvas, font, gap, theme);
        }
        paintAxisTitles(canvas, t, font, theme);
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
                // The gutter, not the distance to the widget's left edge: plotX is absolute,
                // so subtracting only the gap hands the label everything to the left of the
                // content as well: the outer padding, and a left-hand legend to draw over.
                float available = plotX - gap - contentLeft();
                String fitted = ellipsize(text, font, available);
                TextMetrics m = measure(fitted, font);
                float center = bandCenter(i);
                canvas.drawText(fitted, plotX - gap - m.width(), center + m.height() / 2 - m.descent(),
                        font, theme.textMuted);
            } else {
                TextMetrics m = measure(text, font);
                float center = bandCenter(i);
                canvas.drawText(text, center - m.width() / 2, plotY + plotHeight + gap + m.ascent(),
                        font, theme.textMuted);
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
                drawRotated(canvas, valueTitle, font, theme,
                        t.spacingSmall() + measure(valueTitle, font).ascent(),
                        plotY + plotHeight / 2);
            }
        }
        if (categoryTitle != null) {
            if (horizontal) {
                drawRotated(canvas, categoryTitle, font, theme,
                        t.spacingSmall() + measure(categoryTitle, font).ascent(),
                        plotY + plotHeight / 2);
            } else {
                drawCentered(canvas, categoryTitle, font, theme, plotX + plotWidth / 2,
                        height() - t.spacingSmall() - measure(categoryTitle, font).descent());
            }
        }
    }

    private void drawCentered(Canvas canvas, String text, Font font, Theme theme,
                              float centerX, float baseline) {
        TextMetrics m = measure(text, font);
        canvas.drawText(text, centerX - m.width() / 2, baseline, font, theme.textMuted);
    }

    /** An axis title reading bottom-to-top, the way every other toolkit draws one. */
    private void drawRotated(Canvas canvas, String text, Font font, Theme theme,
                             float baselineX, float centerY) {
        TextMetrics m = measure(text, font);
        canvas.save();
        try {
            canvas.translate(baselineX, centerY);
            canvas.rotate((float) -Math.PI / 2);
            canvas.drawText(text, -m.width() / 2, 0, font, theme.textMuted);
        } finally {
            canvas.restore();
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected ChartPoint pickAt(float localX, float localY) {
        // The pointer path resolves the same scale and the same plot rectangle the frame
        // was drawn with; anything else would report a different bar than the one aimed at.
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

    /** The category a local point falls in, or {@code -1} outside the plot. */
    protected final int categoryAt(float localX, float localY) {
        int count = categoryCount();
        if (count == 0) {
            return -1;
        }
        float along = horizontal ? localY - plotY : localX - plotX;
        float extent = horizontal ? plotHeight : plotWidth;
        if (along < 0 || along > extent) {
            return -1;
        }
        return Math.max(0, Math.min(count - 1, (int) (along / (extent / count))));
    }
}
