package limn.components.chart;

import limn.concurrent.Ui;
import limn.graphics.Color;
import limn.i18n.I18nString;

import java.util.Objects;

/**
 * One named run of values: a bar series, a line, or (in a {@link DonutChart}) the ring
 * itself. The values are indexed against the chart's
 * {@linkplain Chart#setLabels(java.util.List) labels}: value {@code i} belongs to category
 * {@code i}, and a series shorter than the label list simply has no value for the
 * categories past its end.
 *
 * <p>{@link Double#NaN} is a <b>gap</b>, not a zero: a line breaks across it and a bar is
 * not drawn for it. That is the difference between "we measured nothing" and "we measured
 * zero", and the two must not look the same.
 *
 * <p>Every setter here reaches back into the chart holding the series, so changing a
 * series after the chart is on screen animates and repaints exactly like changing it
 * through the chart. UI thread only, like every other widget mutation.
 *
 * <pre>{@code
 * ChartSeries revenue = ChartSeries.of("Revenue", 12, 19, 3, 5)
 *         .setStack("2026")        // bar: which stack this belongs to
 *         .setFilled(true);        // line: fill the area under it
 * }</pre>
 */
public final class ChartSeries {

    private I18nString name;
    private double[] values;
    private Color color;             // null = take the chart's palette slot
    private String stack = "";
    private boolean visible = true;

    // ---- line/area options, read by LineChart only --------------------------
    private boolean filled;
    private boolean smooth;
    private float lineWidth = 2;
    private float pointRadius = 3;

    // ---- animation state, owned by the chart --------------------------------
    /** The chart this series was added to; {@code null} while it is unattached. */
    Chart owner;
    /**
     * Where the current animation started, per index; {@code null} means "start from the
     * chart's baseline", which is what makes the first appearance grow out of the axis
     * instead of cross-fading from zero at whatever the axis minimum happens to be.
     */
    double[] from;
    /** Visibility fade in {@code [0,1]}; created by the chart, which owns the transition. */
    limn.animation.Transition fade;

    private ChartSeries(I18nString name, double[] values) {
        this.name = name;
        this.values = values;
    }

    /** A series named {@code name} over {@code values}. */
    public static ChartSeries of(String name, double... values) {
        Objects.requireNonNull(name, "name");
        return of(I18nString.literal(name), values);
    }

    /** A series whose name follows the UI language; see {@link I18nString}. */
    public static ChartSeries of(I18nString name, double... values) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(values, "values");
        return new ChartSeries(name, values.clone());
    }

    /** The name as it currently reads, in the legend and in tooltip rows. */
    public String name() {
        return name.get();
    }

    /** The localizable value behind {@link #name()}, which a language change re-resolves. */
    public I18nString nameSource() {
        return name;
    }

    /** Renames the series. */
    public ChartSeries setName(String value) {
        Objects.requireNonNull(value, "name");
        return setName(I18nString.literal(value));
    }

    /** Renames the series with a value that follows the UI language. */
    public ChartSeries setName(I18nString value) {
        Ui.checkUiThread();
        this.name = Objects.requireNonNull(value, "name");
        changed(false);
        return this;
    }

    /** How many values this series carries. */
    public int size() {
        return values.length;
    }

    /**
     * The value at {@code index}, or {@link Double#NaN} when the series is shorter than
     * that, which reads as a gap, exactly like an explicit NaN.
     */
    public double value(int index) {
        return index >= 0 && index < values.length ? values[index] : Double.NaN;
    }

    /** The values, copied. */
    public double[] values() {
        return values.clone();
    }

    /**
     * Replaces the values. The chart animates from what is currently drawn to the new
     * numbers, so a live series can be re-pushed as often as its data arrives.
     */
    public ChartSeries setValues(double... newValues) {
        Ui.checkUiThread();
        Objects.requireNonNull(newValues, "values");
        beforeChange();
        this.values = newValues.clone();
        changed(true);
        return this;
    }

    /** The color the application pinned, or {@code null} while the palette decides. */
    public Color color() {
        return color;
    }

    /** Pins this series' color; {@code null} hands it back to the chart's palette. */
    public ChartSeries setColor(Color value) {
        Ui.checkUiThread();
        this.color = value;
        changed(false);
        return this;
    }

    /**
     * The stack this series belongs to in a {@linkplain BarChart#setStacked stacked} bar
     * chart, or in a stacked line chart. Series sharing a key stack on top of each other;
     * different keys stand side by side. Default {@code ""}: one stack for everything.
     */
    public String stack() {
        return stack;
    }

    /** Sets the {@linkplain #stack() stack key}. */
    public ChartSeries setStack(String key) {
        Ui.checkUiThread();
        beforeChange();
        this.stack = Objects.requireNonNull(key, "key");
        changed(true);
        return this;
    }

    /** Whether this series is drawn; the legend toggles it. */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Shows or hides the series. The marks fade out and, in a stacked chart, the stack
     * re-flows around them; the scale is recomputed from what is left.
     */
    public ChartSeries setVisible(boolean value) {
        Ui.checkUiThread();
        if (this.visible == value) {
            return this;
        }
        beforeChange();
        this.visible = value;
        if (owner != null) {
            owner.seriesVisibilityChanged(this);
        }
        return this;
    }

    /** Whether a {@link LineChart} fills the area under this line. */
    public boolean isFilled() {
        return filled;
    }

    /** Fills the area between this line and the baseline with a fade of its own color. */
    public ChartSeries setFilled(boolean value) {
        Ui.checkUiThread();
        this.filled = value;
        changed(false);
        return this;
    }

    /** Whether a {@link LineChart} draws this series as a curve rather than as segments. */
    public boolean isSmooth() {
        return smooth;
    }

    /**
     * Draws the line as a curve through its points. Off by default: a curve invents
     * intermediate values the data never had, which is fine for a trend and wrong for a
     * measurement the reader may try to read off the chart.
     */
    public ChartSeries setSmooth(boolean value) {
        Ui.checkUiThread();
        this.smooth = value;
        changed(false);
        return this;
    }

    /** Line thickness in logical points (default 2). */
    public float lineWidth() {
        return lineWidth;
    }

    /** Sets the line thickness in logical points. */
    public ChartSeries setLineWidth(float value) {
        Ui.checkUiThread();
        this.lineWidth = Math.max(0, value);
        changed(false);
        return this;
    }

    /** Marker radius in logical points; {@code 0} draws no markers (default 3). */
    public float pointRadius() {
        return pointRadius;
    }

    /** Sets the marker radius in logical points ({@code 0} hides the markers). */
    public ChartSeries setPointRadius(float value) {
        Ui.checkUiThread();
        this.pointRadius = Math.max(0, value);
        changed(false);
        return this;
    }

    // ------------------------------------------------------------------ internal

    /**
     * The value this series should be drawn at for {@code index}, part-way through the
     * chart's animation: {@code progress} 0 is where the animation started (the chart's
     * baseline on first appearance, the previously drawn value afterwards) and 1 is the
     * value the series actually holds.
     *
     * <p>A hidden series targets the baseline rather than its own value, which is what
     * makes a stack collapse rather than punch a hole when a legend entry is switched off.
     */
    double drawnValue(int index, float progress, double baseline) {
        double target = visible ? value(index) : baseline;
        if (progress >= 1f || Double.isNaN(target)) {
            return target;
        }
        double start = from == null ? baseline
                : index < from.length ? from[index] : baseline;
        if (Double.isNaN(start)) {
            return target; // a gap becoming a value appears; it has nowhere to grow from
        }
        return start + (target - start) * progress;
    }

    /** Snapshots what is on screen as the next animation's starting point. */
    void snapshot(int count, float progress, double baseline) {
        double[] snap = new double[count];
        for (int i = 0; i < count; i++) {
            snap[i] = drawnValue(i, progress, baseline);
        }
        from = snap;
    }

    /**
     * Snapshots the chart before a change that moves marks. Paired with
     * {@code changed(true)}: the two bracket the mutation, because a snapshot taken after
     * it would record the destination.
     */
    private void beforeChange() {
        if (owner != null) {
            owner.beginDataChange();
        }
    }

    private void changed(boolean animate) {
        if (owner == null) {
            return;
        }
        if (animate) {
            owner.endDataChange();
        } else {
            owner.seriesRestyled();
        }
    }
}
