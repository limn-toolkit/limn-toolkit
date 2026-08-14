package limn.components.chart;

import limn.animation.Transition;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Path2D;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * A ring of slices, or a pie, with {@link #setInnerRadius(float)} at {@code 0}.
 *
 * <p>The chart draws its <b>first visible series</b> as the ring: one slice per value,
 * named by the chart's labels and colored by palette slot, because in a donut it is the
 * slices that are the categories. A second series is left undrawn rather than drawn as a
 * second ring; two rings on one chart is a chart nobody can read.
 *
 * <p><b>The hole is a real widget slot.</b> {@link #setCenter(Widget)} puts a widget in the
 * middle (a total, an icon, a button), laid out in the largest square that fits inside the
 * ring, and it takes input like any other widget: a button in the hole is clickable, and the
 * chart's own hover stops at the ring.
 *
 * <p>Negative values are ignored: they have no share of a whole. Hiding a slice from the
 * legend animates it closed and gives its angle back to the others, because the shares are
 * recomputed from what is still shown.
 *
 * <pre>{@code
 * DonutChart chart = new DonutChart();
 * chart.setLabels("Direct", "Search", "Social", "Mail");
 * chart.addSeries(ChartSeries.of("Traffic", 42, 31, 18, 9));
 * chart.setCenter(new Label("100%").setRole(Label.Role.TITLE));
 * }</pre>
 */
public class DonutChart extends Chart {

    /** The ring's share of the box, leaving room for the marks not to touch the edges. */
    private static final float RADIUS_INSET = 4;
    /** How far the hovered slice steps out of the ring, in logical points. */
    private static final float HOVER_POP = 5;
    /** Largest arc a single cubic approximates well. */
    private static final double MAX_ARC = Math.PI / 2;

    private float innerRadius = 0.62f;
    private float startAngle = -90;
    private float sliceGap = 2;
    private Color[] sliceColors;
    private Widget center;

    private final BitSet hiddenSlices = new BitSet();
    /** One weight per slice, animating 1 → 0 as it is hidden, so the ring closes over it. */
    private final List<Transition> sliceWeights = new ArrayList<>();
    private final Transition hoverPop = new Transition(this);
    private final Path2D path = new Path2D();

    /** The ring geometry, recomputed with the regions each paint and each pointer test. */
    private float centerX;
    private float centerY;
    private float outerRadius;

    /** A donut over {@code labels} showing {@code values}. */
    public static DonutChart of(List<String> labels, double... values) {
        DonutChart chart = new DonutChart();
        chart.setLabels(labels);
        chart.addSeries(ChartSeries.of("", values));
        return chart;
    }

    /** The hole's diameter as a fraction of the ring's. */
    public float innerRadius() {
        return innerRadius;
    }

    /**
     * Sets the hole as a fraction of the outer radius, in {@code [0, 0.95]}. {@code 0}
     * draws a pie; the default {@code 0.62} leaves a hole big enough for a headline number.
     */
    public DonutChart setInnerRadius(float fraction) {
        Ui.checkUiThread();
        this.innerRadius = Math.max(0, Math.min(0.95f, fraction));
        markNeedsLayout(); // the hole sizes the centre widget
        return this;
    }

    /** Where the first slice starts, in degrees clockwise from three o'clock. */
    public float startAngle() {
        return startAngle;
    }

    /** Sets the first slice's starting angle in degrees; {@code -90} (the default) is noon. */
    public DonutChart setStartAngle(float degrees) {
        Ui.checkUiThread();
        this.startAngle = degrees;
        invalidate();
        return this;
    }

    /** Points of surface between two slices. */
    public float sliceGap() {
        return sliceGap;
    }

    /** Sets the gap between slices in logical points ({@code 0} makes them touch). */
    public DonutChart setSliceGap(float points) {
        Ui.checkUiThread();
        this.sliceGap = Math.max(0, points);
        invalidate();
        return this;
    }

    /**
     * Pins the slice colors, in slice order; a shorter array leaves the rest to the
     * palette, and {@code null} hands all of them back to it.
     */
    public DonutChart setSliceColors(Color... colors) {
        Ui.checkUiThread();
        this.sliceColors = colors == null ? null : colors.clone();
        // The legend bakes the resolved colour into each entry, so a repaint alone would
        // leave it naming the slices by the colours they no longer have.
        legendChanged();
        return this;
    }

    /** The color of slice {@code index}: its own, or its palette slot. */
    public Color sliceColor(int index) {
        if (sliceColors != null && index < sliceColors.length && sliceColors[index] != null) {
            return sliceColors[index];
        }
        return palette().color(index);
    }

    /** The widget in the hole, or {@code null}. */
    public Widget center() {
        return center;
    }

    /**
     * Puts a widget in the hole. It is laid out centred in the largest square that fits
     * inside the ring and is a normal child in every other way: it takes input, it can be
     * a button, and it is measured against that square rather than against the chart.
     * {@code null} empties the hole.
     */
    public DonutChart setCenter(Widget widget) {
        Ui.checkUiThread();
        if (center == widget) {
            return this;
        }
        if (center != null) {
            remove(center);
        }
        center = widget;
        if (widget != null) {
            add(widget);
        }
        markNeedsLayout();
        return this;
    }

    /** Whether slice {@code index} is currently drawn. */
    public boolean isSliceVisible(int index) {
        return !hiddenSlices.get(index);
    }

    /** Hides or shows one slice; the rest of the ring closes over it, or opens for it. */
    public DonutChart setSliceVisible(int index, boolean visible) {
        Ui.checkUiThread();
        if (index < 0 || isSliceVisible(index) == visible) {
            return this;
        }
        hiddenSlices.set(index, !visible);
        weight(index).duration(stateFadeSeconds()).easing(Theme.current().animEasing);
        weight(index).to(visible ? 1 : 0);
        legendChanged();
        invalidate();
        return this;
    }

    // ---------------------------------------------------------------- ring

    /** The series drawn as the ring, or {@code -1} when there is nothing to draw. */
    private int ringIndex() {
        for (int i = 0; i < seriesCount(); i++) {
            if (series(i).isVisible()) {
                return i;
            }
        }
        return -1;
    }

    private Transition weight(int index) {
        while (sliceWeights.size() <= index) {
            sliceWeights.add(new Transition(this, 1));
        }
        return sliceWeights.get(index);
    }

    /** A slice's value as drawn: the animated number, faded out if the slice is hidden. */
    private double sliceValue(int ring, int index) {
        double value = drawnValue(ring, index);
        if (Double.isNaN(value) || value <= 0) {
            return 0; // a negative has no share of a whole
        }
        return value * weight(index).value();
    }

    private double total(int ring) {
        double sum = 0;
        for (int i = 0; i < categoryCount(); i++) {
            sum += sliceValue(ring, i);
        }
        return sum;
    }

    /**
     * Whether this is a first appearance rather than a change of numbers. During one, the
     * ring wipes round; during the other it morphs, because re-wiping the whole ring every
     * time a number arrives would make a live chart unreadable.
     *
     * <p>The two need separate treatment because a donut's shares are ratios: interpolating
     * every value from zero leaves every ratio exactly where it started, so a donut cannot
     * animate its entry the way a bar does.
     */
    private boolean isEntering(int ring) {
        return series(ring).from == null;
    }

    /** Recomputes the ring's box from the content region; call after the regions are fresh. */
    private void layoutRing() {
        float size = Math.min(contentBoxWidth(), contentBoxHeight());
        centerX = contentLeft() + contentBoxWidth() / 2;
        centerY = contentTop() + contentBoxHeight() / 2;
        outerRadius = Math.max(0, size / 2 - RADIUS_INSET);
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onLayout() {
        if (center == null) {
            return;
        }
        updateRegions();
        layoutRing();
        // The largest square inside the hole: a widget measured against the hole's diameter
        // would have its corners outside the ring.
        float box = Math.max(0, innerRadius * outerRadius * 1.414f);
        Size size = center.measure(Constraints.loose(box, box));
        center.layoutBox(centerX - size.width() / 2, centerY - size.height() / 2,
                size.width(), size.height());
    }

    // --------------------------------------------------------------- painting

    @Override
    protected void paintContent(Canvas canvas, float x, float y, float w, float h) {
        int ring = ringIndex();
        if (ring < 0 || categoryCount() == 0) {
            return;
        }
        layoutRing();
        if (outerRadius <= 1) {
            return;
        }
        double sum = total(ring);
        if (sum <= 0) {
            return;
        }
        ChartPoint hovered = hoveredPoint();
        Color lift = Theme.current().dark ? Color.WHITE : Color.BLACK;
        float sweepFactor = isEntering(ring) ? progress() : 1;
        double angle = Math.toRadians(startAngle);
        float inner = innerRadius * outerRadius;
        for (int i = 0; i < categoryCount(); i++) {
            double value = sliceValue(ring, i);
            if (value <= 0) {
                continue;
            }
            double sweep = 2 * Math.PI * (value / sum) * sweepFactor;
            boolean lit = hovered != null && hovered.index() == i;
            float radius = outerRadius + (lit ? HOVER_POP * hoverPop.value() : 0);
            Color color = sliceColor(i);
            if (lit) {
                color = color.lerp(lift, 0.16f);
            }
            buildSlice(angle, angle + sweep, inner, radius);
            if (!path.isEmpty()) {
                canvas.fillPath(path, color);
            }
            angle += sweep;
        }
    }

    /**
     * One slice into {@link #path}, its ends pulled in by half {@link #sliceGap} each so the
     * surface shows between neighbours. A slice narrower than the gap keeps a hairline of
     * itself rather than inverting.
     */
    private void buildSlice(double from, double to, float inner, float outer) {
        path.reset();
        float midRadius = (inner + outer) / 2;
        double gap = midRadius > 0 ? Math.min((to - from) * 0.4, sliceGap / (2.0 * midRadius)) : 0;
        double a0 = from + gap;
        double a1 = to - gap;
        if (a1 <= a0) {
            return;
        }
        if (inner <= 0.5f) {
            path.moveTo(centerX, centerY);
            path.lineTo(px(a0, outer), py(a0, outer));
            appendArc(a0, a1, outer);
            path.close();
            return;
        }
        path.moveTo(px(a0, outer), py(a0, outer));
        appendArc(a0, a1, outer);
        path.lineTo(px(a1, inner), py(a1, inner));
        appendArc(a1, a0, inner);
        path.close();
    }

    /**
     * Appends the arc from {@code a0} to {@code a1} at {@code radius}, in cubic pieces of
     * at most a quarter turn: past that the Bézier approximation of a circle visibly
     * flattens, which on a donut reads as a dent.
     */
    private void appendArc(double a0, double a1, float radius) {
        double span = a1 - a0;
        int steps = Math.max(1, (int) Math.ceil(Math.abs(span) / MAX_ARC));
        double step = span / steps;
        double kappa = 4.0 / 3.0 * Math.tan(step / 4);
        double angle = a0;
        for (int i = 0; i < steps; i++) {
            double next = angle + step;
            float x0 = px(angle, radius);
            float y0 = py(angle, radius);
            float x1 = px(next, radius);
            float y1 = py(next, radius);
            float c1x = (float) (x0 - kappa * radius * Math.sin(angle));
            float c1y = (float) (y0 + kappa * radius * Math.cos(angle));
            float c2x = (float) (x1 + kappa * radius * Math.sin(next));
            float c2y = (float) (y1 - kappa * radius * Math.cos(next));
            path.cubicTo(c1x, c1y, c2x, c2y, x1, y1);
            angle = next;
        }
    }

    private float px(double angle, float radius) {
        return (float) (centerX + radius * Math.cos(angle));
    }

    private float py(double angle, float radius) {
        return (float) (centerY + radius * Math.sin(angle));
    }

    // ------------------------------------------------------------------ input

    @Override
    protected ChartPoint pickAt(float localX, float localY) {
        int ring = ringIndex();
        if (ring < 0) {
            return null;
        }
        layoutRing();
        double sum = total(ring);
        if (sum <= 0 || outerRadius <= 1) {
            return null;
        }
        float dx = localX - centerX;
        float dy = localY - centerY;
        double distance = Math.hypot(dx, dy);
        if (distance > outerRadius + HOVER_POP || distance < innerRadius * outerRadius) {
            return null;
        }
        double pointer = Math.atan2(dy, dx);
        double start = Math.toRadians(startAngle);
        // Into [0, 2pi) from the ring's own start, so the comparison below is one sweep.
        double relative = pointer - start;
        relative -= 2 * Math.PI * Math.floor(relative / (2 * Math.PI));
        double angle = 0;
        for (int i = 0; i < categoryCount(); i++) {
            double value = sliceValue(ring, i);
            if (value <= 0) {
                continue;
            }
            double sweep = 2 * Math.PI * (value / sum);
            if (relative >= angle && relative < angle + sweep) {
                return slicedPoint(ring, i, value / sum, start + angle + sweep / 2);
            }
            angle += sweep;
        }
        return null;
    }

    private ChartPoint slicedPoint(int ring, int index, double share, double midAngle) {
        float midRadius = (innerRadius * outerRadius + outerRadius) / 2;
        return pointFor(ring, index, share, px(midAngle, midRadius), py(midAngle, midRadius));
    }

    @Override
    protected void onHoverChanged(ChartPoint picked) {
        // One transition for whichever slice is hovered: the pop belongs to the pointer, not
        // to the slice, so moving between slices does not queue two animations.
        hoverPop.duration(animationDuration() <= 0 ? 0 : Theme.current().animHover)
                .easing(Theme.current().animEasing);
        if (picked == null) {
            hoverPop.to(0);
        } else {
            hoverPop.snap(0); // the new slice starts flush, whatever the old one had reached
            hoverPop.to(1);
        }
    }

    // ---------------------------------------------------------- text & legend

    @Override
    protected List<ChartPoint> tooltipRows(ChartPoint picked) {
        // A donut has one series, so the index mode of a bar chart would report one row
        // either way; this keeps that explicit rather than accidental.
        return List.of(picked);
    }

    /** The series names the ring as a whole ("Traffic"); the row names the slice. */
    @Override
    protected String tooltipTitle(ChartPoint picked) {
        return picked.series().name();
    }

    @Override
    protected String tooltipRowName(ChartPoint row) {
        return row.label();
    }

    @Override
    protected String tooltipRowValue(ChartPoint row) {
        String value = valueFormat().apply(row.value());
        if (Double.isNaN(row.share())) {
            return value;
        }
        return value + " (" + Math.round(row.share() * 100) + "%)";
    }

    @Override
    protected Color tooltipRowColor(ChartPoint row) {
        return sliceColor(row.index());
    }

    @Override
    protected List<LegendEntry> legendEntries() {
        int ring = ringIndex();
        if (ring < 0) {
            return List.of();
        }
        List<LegendEntry> entries = new ArrayList<>(categoryCount());
        for (int i = 0; i < categoryCount(); i++) {
            entries.add(new LegendEntry(label(i), sliceColor(i), isSliceVisible(i)));
        }
        return entries;
    }

    @Override
    protected void toggleLegendEntry(int index) {
        setSliceVisible(index, !isSliceVisible(index));
    }
}
