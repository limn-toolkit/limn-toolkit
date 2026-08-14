package limn.components.chart;

import limn.components.Strokes;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.LinearGradient;
import limn.graphics.Path2D;

import java.util.List;

/**
 * Lines, with optional filled areas and markers.
 *
 * <p>Everything about how a line looks belongs to its {@link ChartSeries} (width, marker
 * size, whether it is filled, whether it curves), because a chart normally mixes them: a
 * filled band for the range and a plain line for the actual. The chart-level setters here
 * write through to every series, present and future, as a shorthand for "all of them".
 *
 * <p>{@link Double#NaN} breaks the line. A gap is drawn as a gap, and a filled series
 * fills each run of real values separately rather than bridging the hole with a shape that
 * asserts data nobody has.
 *
 * <p>Unlike bars, the value axis does <b>not</b> begin at zero: a line encodes value as
 * position rather than as length, so it may zoom into the range that matters.
 * {@code chart.valueAxis().setBeginAtZero(true)} restores the zero baseline.
 *
 * <pre>{@code
 * LineChart chart = new LineChart();
 * chart.setLabels("00", "04", "08", "12", "16", "20");
 * chart.addSeries(ChartSeries.of("Latency", 24, 21, 38, 42, 31, 26).setFilled(true));
 * chart.setSmooth(true);
 * }</pre>
 */
public class LineChart extends CartesianChart {

    /** Alpha at the line, and at the baseline, of an area fill. */
    private static final float FILL_TOP_ALPHA = 0.32f;
    private static final float FILL_BOTTOM_ALPHA = 0.03f;
    /** How much a marker grows when its category is hovered. */
    private static final float HOVER_SCALE = 1.7f;

    private float tension = 0.4f;
    private Boolean defaultSmooth;
    private Boolean defaultFilled;
    private Float defaultLineWidth;
    private Float defaultPointRadius;

    private final Path2D path = new Path2D();
    /** Point buffers for one series, reused across series and frames. */
    private float[] px = new float[0];
    private float[] py = new float[0];
    private float[] bx = new float[0];
    private float[] by = new float[0];
    private boolean[] real = new boolean[0];
    /** Which series the buffers above currently hold, and the state they were computed for. */
    private int pointsSeries = -1;
    private int pointsGeneration = -1;
    private float pointsProgress = Float.NaN;
    private float pointsPlotX;
    private float pointsPlotY;
    private float pointsPlotWidth;
    private float pointsPlotHeight;

    public LineChart() {
        super(false);
    }

    /** A line chart over {@code labels}, with the given series. */
    public static LineChart of(List<String> labels, ChartSeries... series) {
        LineChart chart = new LineChart();
        chart.setLabels(labels);
        for (ChartSeries s : series) {
            chart.addSeries(s);
        }
        return chart;
    }

    /** Curves every series, and every series added later. See {@link ChartSeries#setSmooth}. */
    public LineChart setSmooth(boolean value) {
        Ui.checkUiThread();
        defaultSmooth = value;
        for (int i = 0; i < seriesCount(); i++) {
            series(i).setSmooth(value);
        }
        return this;
    }

    /** Fills every series, and every series added later. See {@link ChartSeries#setFilled}. */
    public LineChart setArea(boolean value) {
        Ui.checkUiThread();
        defaultFilled = value;
        for (int i = 0; i < seriesCount(); i++) {
            series(i).setFilled(value);
        }
        return this;
    }

    /** Sets the stroke width of every series, and of every series added later. */
    public LineChart setLineWidth(float points) {
        Ui.checkUiThread();
        defaultLineWidth = points;
        for (int i = 0; i < seriesCount(); i++) {
            series(i).setLineWidth(points);
        }
        return this;
    }

    /** Sets the marker radius of every series, and of every series added later ({@code 0} hides). */
    public LineChart setPointRadius(float points) {
        Ui.checkUiThread();
        defaultPointRadius = points;
        for (int i = 0; i < seriesCount(); i++) {
            series(i).setPointRadius(points);
        }
        return this;
    }

    /** How hard a smoothed line curves. */
    public float tension() {
        return tension;
    }

    /**
     * Sets the curve strength of {@linkplain ChartSeries#setSmooth smoothed} series, from
     * {@code 0} (straight segments) to about {@code 0.5} (a Catmull-Rom spline through the
     * points). Default {@code 0.4}. Higher values overshoot past the data.
     */
    public LineChart setTension(float value) {
        Ui.checkUiThread();
        this.tension = Math.max(0, Math.min(1, value));
        invalidate();
        return this;
    }

    @Override
    protected void onSeriesAdded(ChartSeries added) {
        if (defaultSmooth != null) {
            added.setSmooth(defaultSmooth);
        }
        if (defaultFilled != null) {
            added.setFilled(defaultFilled);
        }
        if (defaultLineWidth != null) {
            added.setLineWidth(defaultLineWidth);
        }
        if (defaultPointRadius != null) {
            added.setPointRadius(defaultPointRadius);
        }
    }

    // --------------------------------------------------------------- geometry

    /**
     * Computes one series' points into the reusable buffers: {@code px}/{@code py} for the
     * line, {@code bx}/{@code by} for the surface it is filled down to (the baseline, or
     * the top of the stack below it), and {@code real} for which indexes carry a value.
     *
     * <p>Memoized on the series and on everything the result depends on: the data, the
     * animation's progress and the plot rectangle. One hover asks for the same series three
     * times (the pick, the datum it reports, and the paint that follows), and with stacking
     * each call is O(series x categories); without this, pointing at a chart recomputed the
     * whole thing three times per frame.
     */
    private void computePoints(int seriesIndex) {
        int count = categoryCount();
        float progress = progress();
        if (pointsSeries == seriesIndex && pointsGeneration == dataGeneration()
                && Float.compare(pointsProgress, progress) == 0
                && pointsPlotX == plotX() && pointsPlotY == plotY()
                && pointsPlotWidth == plotWidth() && pointsPlotHeight == plotHeight()) {
            return;
        }
        pointsSeries = seriesIndex;
        pointsGeneration = dataGeneration();
        pointsProgress = progress;
        pointsPlotX = plotX();
        pointsPlotY = plotY();
        pointsPlotWidth = plotWidth();
        pointsPlotHeight = plotHeight();
        if (px.length < count) {
            px = new float[count];
            py = new float[count];
            bx = new float[count];
            by = new float[count];
            real = new boolean[count];
        }
        boolean horizontal = isHorizontal();
        for (int c = 0; c < count; c++) {
            double value = drawnValue(seriesIndex, c);
            double base = stackBase(seriesIndex, c);
            real[c] = !Double.isNaN(value);
            double top = isStacked() ? base + value : value;
            double bottom = isStacked() ? base : animationBaseline();
            float along = bandCenter(c);
            float across = valuePosition(real[c] ? top : bottom);
            float baseAcross = valuePosition(bottom);
            px[c] = horizontal ? across : along;
            py[c] = horizontal ? along : across;
            bx[c] = horizontal ? baseAcross : along;
            by[c] = horizontal ? along : baseAcross;
        }
    }

    /**
     * Appends points {@code [first, last]} to {@link #path}, forward or backward, curved
     * when {@code smooth}. Backward is what closes an area against the surface below it.
     */
    private void appendRun(float[] xs, float[] ys, int first, int last, boolean forward,
                           boolean smooth, boolean start) {
        int step = forward ? 1 : -1;
        int from = forward ? first : last;
        int to = forward ? last : first;
        if (start) {
            path.moveTo(xs[from], ys[from]);
        } else {
            path.lineTo(xs[from], ys[from]);
        }
        for (int i = from; i != to; i += step) {
            int next = i + step;
            if (!smooth || tension <= 0) {
                path.lineTo(xs[next], ys[next]);
                continue;
            }
            // Catmull-Rom through the points, converted to a cubic. The neighbours outside
            // the run are clamped to its ends, which keeps the first and last segments from
            // curling away from data that does not exist.
            int prev = clamp(i - step, first, last);
            int after = clamp(next + step, first, last);
            float f = tension / 3f;
            float c1x = xs[i] + (xs[next] - xs[prev]) * f;
            float c1y = ys[i] + (ys[next] - ys[prev]) * f;
            float c2x = xs[next] - (xs[after] - xs[i]) * f;
            float c2y = ys[next] - (ys[after] - ys[i]) * f;
            path.cubicTo(c1x, c1y, c2x, c2y, xs[next], ys[next]);
        }
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    // --------------------------------------------------------------- painting

    @Override
    protected void paintMarks(Canvas canvas) {
        Theme theme = Theme.current();
        ChartPoint hovered = hoveredPoint();
        if (hovered != null && tooltipMode() == TooltipMode.INDEX) {
            paintCrosshair(canvas, theme, hovered.index());
        }
        int count = categoryCount();
        for (int i = 0; i < seriesCount(); i++) {
            ChartSeries s = series(i);
            float alpha = seriesAlpha(i);
            if (alpha <= 0.004f || count == 0) {
                continue;
            }
            computePoints(i);
            Color color = seriesColor(i);
            if (alpha < 1) {
                color = color.withAlpha(color.a() * alpha);
            }
            int runStart = -1;
            for (int c = 0; c <= count; c++) {
                boolean has = c < count && real[c];
                if (has && runStart < 0) {
                    runStart = c;
                } else if (!has && runStart >= 0) {
                    paintRun(canvas, s, color, runStart, c - 1);
                    runStart = -1;
                }
            }
            paintMarkers(canvas, s, color, theme, hovered);
        }
    }

    /** One contiguous run of real values: the fill under it, then the line itself. */
    private void paintRun(Canvas canvas, ChartSeries s, Color color, int first, int last) {
        if (s.isFilled()) {
            path.reset();
            appendRun(px, py, first, last, true, s.isSmooth(), true);
            appendRun(bx, by, first, last, false, s.isSmooth(), false);
            path.close();
            canvas.fillPath(path, fillPaint(color, first, last));
        }
        if (s.lineWidth() > 0 && last > first) {
            path.reset();
            appendRun(px, py, first, last, true, s.isSmooth(), true);
            canvas.drawPath(path, s.lineWidth(), color);
        }
    }

    /**
     * A fade from the line down to the surface, along whichever axis carries the values.
     *
     * <p>Anchored to the run's own far edge rather than to the top of the scale: a series
     * sitting low on a tall axis would otherwise be filled entirely out of the pale end of
     * the gradient, which is a fill nobody can see.
     */
    private LinearGradient fillPaint(Color color, int first, int last) {
        Color near = color.withAlpha(color.a() * FILL_TOP_ALPHA);
        Color away = color.withAlpha(color.a() * FILL_BOTTOM_ALPHA);
        float base = valuePosition(animationBaseline());
        float far = base;
        for (int i = first; i <= last; i++) {
            float p = isHorizontal() ? px[i] : py[i];
            if (Math.abs(p - base) > Math.abs(far - base)) {
                far = p;
            }
        }
        return isHorizontal()
                ? new LinearGradient(far, plotY(), base, plotY(), near, away)
                : new LinearGradient(plotX(), far, plotX(), base, near, away);
    }

    private void paintMarkers(Canvas canvas, ChartSeries s, Color color, Theme theme,
                              ChartPoint hovered) {
        if (s.pointRadius() <= 0) {
            return;
        }
        int count = categoryCount();
        Color ring = theme.surface;
        for (int c = 0; c < count; c++) {
            if (!real[c]) {
                continue;
            }
            boolean lit = hovered != null && hovered.index() == c;
            float radius = s.pointRadius() * (lit ? HOVER_SCALE : 1);
            // A ring in the surface color, not a gap: two series crossing at a point must
            // still read as two marks, and a hole would show whatever is behind the chart.
            canvas.fillCircle(px[c], py[c], radius + Strokes.BORDER, ring);
            canvas.fillCircle(px[c], py[c], radius, color);
        }
    }

    private void paintCrosshair(Canvas canvas, Theme theme, int category) {
        float center = bandCenter(category);
        Color ink = theme.outline;
        if (isHorizontal()) {
            canvas.drawLine(plotX(), center, plotX() + plotWidth(), center, Strokes.HAIRLINE, ink);
        } else {
            canvas.drawLine(center, plotY(), center, plotY() + plotHeight(), Strokes.HAIRLINE, ink);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected int pickSeries(int category, float localX, float localY) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < seriesCount(); i++) {
            ChartSeries s = series(i);
            if (!s.isVisible() || Double.isNaN(s.value(category))) {
                continue;
            }
            computePoints(i);
            double distance = Math.hypot(px[category] - localX, py[category] - localY);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    @Override
    protected ChartPoint pointOf(int seriesIndex, int category) {
        computePoints(seriesIndex);
        double total = stackTotal(seriesIndex, category);
        double share = Double.isNaN(total) || total == 0
                ? Double.NaN : series(seriesIndex).value(category) / total;
        return pointFor(seriesIndex, category, share, px[category], py[category]);
    }
}
