package limn.components.chart;

import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.RoundRect;

import java.util.List;

/**
 * Bars, grouped or stacked, vertical or horizontal.
 *
 * <p>Series stand side by side in each category by default. {@link #setStacked(boolean)}
 * accumulates them instead; series that name the same {@linkplain ChartSeries#setStack
 * stack key} share a column, and different keys stand side by side, so a chart can show
 * two stacks per category. Negative values stack downward on their own side of zero.
 *
 * <p>The value axis {@linkplain ChartAxis#setBeginAtZero begins at zero}: the length of a
 * bar is the value it stands for, and an axis that starts anywhere else draws a ratio the
 * data does not have. It can be overridden; it should not be.
 *
 * <pre>{@code
 * BarChart chart = new BarChart();
 * chart.setLabels("Q1", "Q2", "Q3", "Q4");
 * chart.addSeries(ChartSeries.of("Direct",  120, 145, 132, 168));
 * chart.addSeries(ChartSeries.of("Partner",  80,  92, 105,  99));
 * chart.setStacked(true);
 * }</pre>
 */
public class BarChart extends CartesianChart {

    /** Surface showing between stacked segments, in logical points. */
    private static final float SEGMENT_GAP = 2;
    /** A mark thinner than this is not drawn: it reads as a line, not as a value. */
    private static final float MIN_SLOT = 0.5f;

    private float barRadius = 4;
    private float barPadding = 0.22f;
    private float seriesGap = 2;

    /** Scratch for {@link #markRect}: x, y, width, height. One pass, one thread, no garbage. */
    private final float[] rect = new float[4];

    public BarChart() {
        super(true);
    }

    /** A bar chart over {@code labels}, with the given series. */
    public static BarChart of(List<String> labels, ChartSeries... series) {
        BarChart chart = new BarChart();
        chart.setLabels(labels);
        for (ChartSeries s : series) {
            chart.addSeries(s);
        }
        return chart;
    }

    /** Corner radius of a bar's outer end, in logical points. */
    public float barRadius() {
        return barRadius;
    }

    /**
     * Rounds the outer end of every bar; the end standing on the baseline stays square, so
     * the bar still reads as anchored to zero rather than floating.
     */
    public BarChart setBarRadius(float value) {
        Ui.checkUiThread();
        this.barRadius = Math.max(0, value);
        invalidate();
        return this;
    }

    /** The fraction of each category slot left empty, split between its two sides. */
    public float barPadding() {
        return barPadding;
    }

    /**
     * Sets how much of each category slot is empty space, as a fraction in {@code [0, 0.9]}
     * (default {@code 0.22}). {@code 0} makes a histogram, where bars touch.
     */
    public BarChart setBarPadding(float fraction) {
        Ui.checkUiThread();
        this.barPadding = Math.max(0, Math.min(0.9f, fraction));
        invalidate();
        return this;
    }

    /** Points of surface between two bars of the same category. */
    public float seriesGap() {
        return seriesGap;
    }

    /** Sets the gap between grouped bars, in logical points. */
    public BarChart setSeriesGap(float points) {
        Ui.checkUiThread();
        this.seriesGap = Math.max(0, points);
        invalidate();
        return this;
    }

    /** Bars wipe in from the first category rather than all appearing at once. */
    @Override
    protected float staggerFraction() {
        return 0.06f;
    }

    // --------------------------------------------------------------- geometry

    /** How many bars stand in one category: one per stack key, or one per visible series. */
    private int slotCount() {
        if (isStacked()) {
            return Math.max(1, stackKeys().size());
        }
        int count = 0;
        for (int i = 0; i < seriesCount(); i++) {
            if (series(i).isVisible()) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    /**
     * Which of those slots {@code seriesIndex} stands in, or {@code -1} when it has none.
     *
     * <p>A hidden series keeps its slot in a stacked chart (the key is still there,
     * because its neighbours are), so it shrinks and fades out of the column while what
     * was above it slides down. Grouped, there is nothing to shrink into: the slot itself
     * is gone, and the remaining bars take the width.
     */
    private int slotOf(int seriesIndex) {
        if (isStacked()) {
            return stackKeys().indexOf(series(seriesIndex).stack());
        }
        if (!series(seriesIndex).isVisible()) {
            return -1;
        }
        int slot = 0;
        for (int i = 0; i < seriesIndex; i++) {
            if (series(i).isVisible()) {
                slot++;
            }
        }
        return slot;
    }

    /**
     * The rectangle one bar occupies right now (animation, stacking and orientation
     * folded in), written into {@link #rect}. {@code false} when there is nothing to draw:
     * no value, a value that has not grown yet, or a series that is not drawn at all.
     */
    private boolean markRect(int seriesIndex, int category) {
        int slot = slotOf(seriesIndex);
        if (slot < 0) {
            return false;
        }
        double value = drawnValue(seriesIndex, category);
        if (Double.isNaN(value)) {
            return false;
        }
        double base = stackBase(seriesIndex, category);
        double from = isStacked() ? base : animationBaseline();
        double to = isStacked() ? base + value : value;

        float band = bandSize();
        float inset = band * barPadding / 2;
        float groupSize = band - 2 * inset;
        int slots = slotCount();
        float gap = slots > 1 ? Math.min(seriesGap, groupSize / (slots * 2f)) : 0;
        float slotSize = (groupSize - (slots - 1) * gap) / slots;
        if (slotSize < MIN_SLOT) {
            return false;
        }
        float catA = bandStart(category) + inset + slot * (slotSize + gap);
        float valueA = valuePosition(from);
        float valueB = valuePosition(to);
        if (isStacked() && base != 0) {
            // One surface-wide gap where this segment meets the one below it. Skipped when
            // the segment is thinner than the gap, which would otherwise invert it.
            float shrink = Math.min(SEGMENT_GAP, Math.abs(valueB - valueA) - 1);
            if (shrink > 0) {
                valueA += valueA > valueB ? -shrink : shrink;
            }
        }
        float low = Math.min(valueA, valueB);
        float size = Math.abs(valueB - valueA);
        if (size < 0.35f) {
            return false; // nothing has grown yet, or the value rounds to the baseline
        }
        if (isHorizontal()) {
            rect[0] = low;
            rect[1] = catA;
            rect[2] = size;
            rect[3] = slotSize;
        } else {
            rect[0] = catA;
            rect[1] = low;
            rect[2] = slotSize;
            rect[3] = size;
        }
        return true;
    }

    /**
     * Whether this segment is the last one on its side of zero, the only one whose outer
     * end is rounded. A rounded end inside a stack would read as the top of the stack.
     */
    private boolean isOuterSegment(int seriesIndex, int category, boolean negative) {
        if (!isStacked()) {
            return true;
        }
        String key = series(seriesIndex).stack();
        for (int i = seriesIndex + 1; i < seriesCount(); i++) {
            if (!series(i).isVisible() || !series(i).stack().equals(key)) {
                continue;
            }
            double value = drawnValue(i, category);
            if (!Double.isNaN(value) && value != 0 && value < 0 == negative) {
                return false;
            }
        }
        return true;
    }

    /** The bar's rectangle with its outer end rounded, in the orientation in force. */
    private RoundRect rounded(float x, float y, float w, float h, boolean negative, boolean outer) {
        float r = outer ? Math.min(barRadius, Math.min(w, h) / 2) : 0;
        if (r <= 0) {
            return RoundRect.of(x, y, w, h, 0);
        }
        // "Outer" is away from the baseline: up for a positive vertical bar, right for a
        // positive horizontal one, and mirrored for negatives.
        if (isHorizontal()) {
            return negative
                    ? new RoundRect(x, y, w, h, r, 0, 0, r)
                    : new RoundRect(x, y, w, h, 0, r, r, 0);
        }
        return negative
                ? new RoundRect(x, y, w, h, 0, 0, r, r)
                : new RoundRect(x, y, w, h, r, r, 0, 0);
    }

    // --------------------------------------------------------------- painting

    @Override
    protected void paintMarks(Canvas canvas) {
        int categories = categoryCount();
        ChartPoint hovered = hoveredPoint();
        // Toward the surface's opposite: lightening a mark on a light theme washes it out,
        // and darkening one on a dark theme reads as disabled.
        Color lift = limn.components.Theme.current().dark ? Color.WHITE : Color.BLACK;
        for (int c = 0; c < categories; c++) {
            for (int i = 0; i < seriesCount(); i++) {
                if (!markRect(i, c)) {
                    continue;
                }
                float alpha = seriesAlpha(i);
                if (alpha <= 0.004f) {
                    continue;
                }
                boolean negative = drawnValue(i, c) < 0;
                Color color = seriesColor(i);
                if (hovered != null && hovered.index() == c) {
                    // The hovered category lifts toward the reader rather than dimming its
                    // neighbours: dimming makes the chart flash on every pointer move.
                    color = color.lerp(lift, 0.16f);
                }
                canvas.fillRoundRect(
                        rounded(rect[0], rect[1], rect[2], rect[3], negative,
                                isOuterSegment(i, c, negative)),
                        alpha >= 1 ? color : color.withAlpha(color.a() * alpha));
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected int pickSeries(int category, float localX, float localY) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < seriesCount(); i++) {
            if (!series(i).isVisible()) {
                continue;
            }
            if (!markRect(i, category)) {
                // A series with no mark here (a gap, or a value at the baseline) is still
                // reportable: fall back to the whole slot so the category stays clickable.
                if (best < 0 && !Double.isNaN(series(i).value(category))) {
                    best = i;
                    bestDistance = Float.MAX_VALUE - 1;
                }
                continue;
            }
            float distance = distanceTo(rect, localX, localY);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
            if (distance == 0) {
                return i; // inside a bar: nothing can be nearer
            }
        }
        return best;
    }

    private static float distanceTo(float[] box, float x, float y) {
        float dx = Math.max(0, Math.max(box[0] - x, x - (box[0] + box[2])));
        float dy = Math.max(0, Math.max(box[1] - y, y - (box[1] + box[3])));
        return (float) Math.hypot(dx, dy);
    }

    @Override
    protected ChartPoint pointOf(int seriesIndex, int category) {
        float x;
        float y;
        if (markRect(seriesIndex, category)) {
            boolean negative = drawnValue(seriesIndex, category) < 0;
            if (isHorizontal()) {
                x = negative ? rect[0] : rect[0] + rect[2];
                y = rect[1] + rect[3] / 2;
            } else {
                x = rect[0] + rect[2] / 2;
                y = negative ? rect[1] + rect[3] : rect[1];
            }
        } else {
            x = isHorizontal() ? valuePosition(animationBaseline()) : bandCenter(category);
            y = isHorizontal() ? bandCenter(category) : valuePosition(animationBaseline());
        }
        return pointFor(seriesIndex, category, stackShare(seriesIndex, category), x, y);
    }

    private double stackShare(int seriesIndex, int category) {
        double total = stackTotal(seriesIndex, category);
        if (Double.isNaN(total) || total == 0) {
            return Double.NaN;
        }
        return series(seriesIndex).value(category) / total;
    }
}
