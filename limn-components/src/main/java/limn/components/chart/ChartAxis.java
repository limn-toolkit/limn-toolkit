package limn.components.chart;

import limn.concurrent.Ui;

import java.util.Objects;
import java.util.function.DoubleFunction;

/**
 * One axis of a {@link CartesianChart}: the value axis that carries the numbers, or the
 * category axis that carries the labels. Reach them with
 * {@link CartesianChart#valueAxis()} and {@link CartesianChart#categoryAxis()}; both are
 * created with the chart and live as long as it does.
 *
 * <p>An axis is <b>automatic until told otherwise</b>: it reads the data, rounds the range
 * outward to values a human would have chosen, and picks a tick spacing near
 * {@link #setTickCount(int)}. {@link #setMin(Double)} and {@link #setMax(Double)} pin
 * either end; whichever end stays {@code null} keeps following the data.
 *
 * <p>Four settings apply only to the value axis and are ignored on the category axis,
 * which has no numbers to place: {@code min}, {@code max}, {@code tickCount} and
 * {@code beginAtZero}. {@code format} is likewise a value-axis setting; category labels
 * come from {@link Chart#setLabels(java.util.List)}.
 */
public final class ChartAxis {

    /** Set by the chart that owns this axis, so a setter can repaint it. */
    Chart owner;

    private Double min;
    private Double max;
    private boolean beginAtZero;
    private int tickCount = 5;
    private DoubleFunction<String> format = ChartFormats.number();
    private boolean grid = true;
    private boolean visible = true;
    private String title;

    ChartAxis(boolean beginAtZero, boolean grid) {
        this.beginAtZero = beginAtZero;
        this.grid = grid;
    }

    /** The pinned lower bound, or {@code null} while it follows the data. */
    public Double min() {
        return min;
    }

    /** Pins the lower bound; {@code null} hands it back to the data. */
    public ChartAxis setMin(Double value) {
        Ui.checkUiThread();
        this.min = value;
        changed();
        return this;
    }

    /** The pinned upper bound, or {@code null} while it follows the data. */
    public Double max() {
        return max;
    }

    /** Pins the upper bound; {@code null} hands it back to the data. */
    public ChartAxis setMax(Double value) {
        Ui.checkUiThread();
        this.max = value;
        changed();
        return this;
    }

    /** Whether the automatic range always reaches zero. */
    public boolean beginsAtZero() {
        return beginAtZero;
    }

    /**
     * Whether the automatic range must include zero. On by default for bars, off for
     * lines, and that difference is not a preference: the length of a bar <em>is</em> the
     * value, so a bar axis that starts anywhere else draws a ratio that is not the data's.
     * A line encodes value as position, so it may zoom into the range that matters.
     */
    public ChartAxis setBeginAtZero(boolean value) {
        Ui.checkUiThread();
        this.beginAtZero = value;
        changed();
        return this;
    }

    /** The tick count the automatic scale aims for. */
    public int tickCount() {
        return tickCount;
    }

    /**
     * Sets how many ticks the automatic scale aims for (clamped to {@code [2, 20]}). It is
     * a target, not a promise: the scale rounds the spacing to 1, 2 or 5 times a power of
     * ten first, and honours that over the count.
     */
    public ChartAxis setTickCount(int value) {
        Ui.checkUiThread();
        this.tickCount = Math.max(2, Math.min(20, value));
        changed();
        return this;
    }

    /** How tick values are turned into text. */
    public DoubleFunction<String> format() {
        return format;
    }

    /**
     * Sets the tick text format. Defaults to {@link ChartFormats#number()}; the chart's
     * own {@link Chart#setValueFormat(DoubleFunction)} does not reach here, so an axis and
     * its tooltips can read differently (compact ticks, exact tooltips).
     */
    public ChartAxis setFormat(DoubleFunction<String> value) {
        Ui.checkUiThread();
        this.format = Objects.requireNonNull(value, "format");
        changed();
        return this;
    }

    /** Whether this axis draws grid lines across the plot. */
    public boolean hasGrid() {
        return grid;
    }

    /** Shows or hides this axis' grid lines (value axis on, category axis off by default). */
    public ChartAxis setGrid(boolean value) {
        Ui.checkUiThread();
        this.grid = value;
        changed();
        return this;
    }

    /** Whether this axis draws its labels at all. */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Shows or hides the axis labels. Hiding them gives the plot their gutter back, the
     * shape a sparkline wants.
     */
    public ChartAxis setVisible(boolean value) {
        Ui.checkUiThread();
        this.visible = value;
        changed();
        return this;
    }

    /** The axis title, or {@code null} for none. */
    public String title() {
        return title;
    }

    /** Sets a title drawn beside the axis ({@code null} for none). */
    public ChartAxis setTitle(String value) {
        Ui.checkUiThread();
        this.title = value;
        changed();
        return this;
    }

    private void changed() {
        if (owner != null) {
            owner.axisChanged();
        }
    }

    // --------------------------------------------------------------- the scale

    /**
     * A resolved value axis: the two ends actually drawn and the spacing between ticks.
     * {@code step} is always positive, and {@code max - min} is always a whole number of
     * steps unless an end was pinned.
     */
    record Scale(double min, double max, double step) {

        /** How many ticks fall on this scale, first and last included. */
        int tickCount() {
            return (int) Math.floor((max - min) / step + 1e-6) + 1;
        }

        /** The value of tick {@code i}, counted from {@link #min()}. */
        double tick(int i) {
            double v = min + i * step;
            // Snap the neighbourhood of zero: 0.1 + 0.2 arithmetic otherwise labels a tick
            // "-0" or "1e-17" on any scale that crosses the axis.
            return Math.abs(v) < step * 1e-9 ? 0 : v;
        }
    }

    /**
     * Rounds {@code [dataMin, dataMax]} out to a scale a human would have drawn, honouring
     * whichever end is pinned. Degenerate input (no data, all-equal values, infinities)
     * resolves to a usable scale rather than throwing: a chart with nothing in it still
     * has to paint an axis.
     */
    Scale resolve(double dataMin, double dataMax) {
        double lo = min != null ? min : dataMin;
        double hi = max != null ? max : dataMax;
        if (!Double.isFinite(lo) || !Double.isFinite(hi)) {
            lo = 0;
            hi = 1;
        }
        if (beginAtZero) {
            if (min == null) {
                lo = Math.min(0, lo);
            }
            if (max == null) {
                hi = Math.max(0, hi);
            }
        }
        if (hi < lo) {
            double swap = lo;
            lo = hi;
            hi = swap;
        }
        if (hi - lo < Math.ulp(hi) * 8) {
            // A flat series still needs a range to draw in; centre it on the value.
            double pad = Math.abs(hi) > 1e-9 ? Math.abs(hi) * 0.5 : 1;
            if (min == null) {
                lo -= pad;
            }
            if (max == null) {
                hi += pad;
            }
            if (hi - lo < Math.ulp(hi) * 8) {
                hi = lo + 1; // both ends pinned to the same number
            }
        }
        double step = niceNum((hi - lo) / Math.max(1, tickCount - 1), true);
        if (!(step > 0) || !Double.isFinite(step)) {
            step = (hi - lo) / Math.max(1, tickCount - 1);
        }
        double niceLo = min != null ? lo : Math.floor(lo / step) * step;
        double niceHi = max != null ? hi : Math.ceil(hi / step) * step;
        if (niceHi - niceLo < step) {
            niceHi = niceLo + step;
        }
        return new Scale(niceLo, niceHi, step);
    }

    /**
     * The nearest "round" number to {@code value}: 1, 2, 5 or 10 times a power of ten.
     * Heckbert's loose labelling: it is what makes an axis read 0/25/50/75/100 instead of
     * 0/23.7/47.4, and it is the whole reason tick spacing is computed rather than
     * divided.
     */
    private static double niceNum(double value, boolean round) {
        if (!(value > 0) || !Double.isFinite(value)) {
            return 1;
        }
        double exponent = Math.floor(Math.log10(value));
        double fraction = value / Math.pow(10, exponent);
        double nice;
        if (round) {
            nice = fraction < 1.5 ? 1 : fraction < 3 ? 2 : fraction < 7 ? 5 : 10;
        } else {
            nice = fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10;
        }
        return nice * Math.pow(10, exponent);
    }
}
