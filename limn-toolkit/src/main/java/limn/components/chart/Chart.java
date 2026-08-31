package limn.components.chart;

import limn.animation.Easing;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.SizeTokens;
import limn.components.Strokes;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.MouseEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.Function;

/**
 * What every chart in this package has in common: the data (labels and
 * {@linkplain ChartSeries series}), the colors, the title, the legend, the hover tooltip,
 * the click callback and the animation. {@link BarChart}, {@link LineChart} and
 * {@link DonutChart} add the marks and the axes.
 *
 * <p>A chart is usable with nothing configured: colors come from the palette that matches
 * the active {@link Theme}, the scale rounds itself, the legend appears when there is more
 * than one thing to name, and values animate in. Everything past that is a setter.
 *
 * <pre>{@code
 * BarChart chart = new BarChart();
 * chart.setLabels("Mon", "Tue", "Wed", "Thu", "Fri");
 * chart.addSeries(ChartSeries.of("Signups", 12, 19, 3, 5, 22));
 * chart.addSeries(ChartSeries.of("Churn",    2,  3, 1, 4,  2));
 * chart.setStacked(true);
 * chart.onPointClick(point -> open(point.label()));
 * }</pre>
 *
 * <p><b>Animation.</b> Values interpolate, not pixels: on the first appearance a series
 * grows out of the axis baseline, and afterwards it eases from the number on screen to the
 * number it was just given, so a live chart re-pushed every second reads as one moving
 * shape rather than as a slideshow. {@link #setAnimationDuration(double)} with {@code 0}
 * turns it off, which is also what a chart with no scene does: a headless test or a
 * screenshot sees final values, never an in-between frame.
 *
 * <p><b>Interaction.</b> Hovering reports the datum under the pointer; the tooltip follows
 * it and stays inside the chart. A chart with more than one series reports every series at
 * the hovered category at once ({@link TooltipMode#INDEX}), which is what makes two lines
 * comparable at a glance. Clicking calls {@link #onPointClick(Consumer)} with the same
 * datum. Clicking a legend entry hides and shows its series, animating the scale and any
 * stack around it.
 *
 * <p>Charts are painted, not composed: a chart has no child widgets, with one exception:
 * {@link DonutChart#setCenter(Widget)} puts a real widget in the hole, so the middle of a
 * donut can hold a label, an icon or a button.
 *
 * <p><b>On chaining.</b> The setters here return {@code Chart}, so a chain that starts on a
 * subclass cannot continue into one of that subclass's own setters:
 * {@code new BarChart().setTitle("x").setStacked(true)} does not compile. Configure a chart
 * in statements (which is how the demo scenes read anyway) or start from
 * {@link BarChart#of}, {@link LineChart#of} and {@link DonutChart#of}, which take the labels
 * and the series together. The alternative, redeclaring every setter in all three
 * subclasses purely to narrow its return type, buys one expression shape at the price of
 * forty methods whose documentation could only repeat their signatures.
 */
public abstract class Chart extends Widget {

    /** Where the legend sits relative to the plot. */
    public enum LegendPosition {
        /** Below the plot when there are two or more entries to name, hidden otherwise. */
        AUTO,
        /** Never drawn. */
        NONE,
        TOP, BOTTOM, LEFT, RIGHT
    }

    /** How much a hover reports. */
    public enum TooltipMode {
        /** Every visible series at the hovered category, the default for bars and lines. */
        INDEX,
        /** Only the mark under the pointer. */
        POINT
    }

    /** One row of the legend: a swatch, a name, and whether its data is currently drawn. */
    protected record LegendEntry(String text, Color color, boolean visible) {
    }

    /** Hands a dimension back to the default size, the {@code ProgressBar.UNSET} idiom. */
    public static final float UNSET = -1;

    private static final float DEFAULT_WIDTH = 360;
    private static final float DEFAULT_HEIGHT = 220;
    /** Entry/change animation, in seconds. Long enough to read, short enough not to wait. */
    private static final double DEFAULT_ANIMATION = 0.55;
    /** Pointer offset for the tooltip panel, in logical points. */
    private static final float TOOLTIP_OFFSET = 14;

    private final List<ChartSeries> series = new ArrayList<>();
    private List<I18nString> labels = List.of();
    private ChartPalette palette;
    private I18nString title;
    private Color background;
    private LegendPosition legendPosition = LegendPosition.AUTO;
    private boolean legendInteractive = true;
    private TooltipMode tooltipMode = TooltipMode.INDEX;
    private boolean tooltipEnabled = true;
    private DoubleFunction<String> valueFormat = ChartFormats.number();
    private Function<ChartPoint, String> tooltipFormat;
    private float preferredWidth = UNSET;
    private float preferredHeight = UNSET;
    private double animationSeconds = DEFAULT_ANIMATION;
    private Easing animationEasing = Easing.EASE_OUT;
    private Consumer<ChartPoint> onPointClick = point -> {
    };
    private Consumer<ChartPoint> onHover = point -> {
    };

    /** Drives every value interpolation in the chart; 1 = the data as it stands. */
    private final Transition anim = new Transition(this, 1);
    private final Transition tooltipFade =
            new Transition(this).duration(Theme.current().animFade).easing(Theme.current().animEasing);

    private int dataGeneration = 1;
    private ChartPoint hovered;
    private List<ChartPoint> tooltipRowsCache = List.of();
    private ChartPoint tooltipRowsFor;
    private int tooltipRowsGeneration = -1;
    private long tooltipRowsEpoch = -1;
    private float pointerX;
    private float pointerY;
    private int hoveredLegend = -1;
    private boolean pointerCursor;

    /**
     * Derived-font memos, one per base. {@code Font.bold()} allocates a fresh record and
     * the backend resolves fonts through an {@code IdentityHashMap}, so deriving the title
     * and tooltip faces per paint would miss that memo every frame and grow it forever.
     * Two memos, not one: the title and the tooltip derive from different rows, and a
     * single slot would thrash between them.
     */
    private final BoldMemo titleFont = new BoldMemo();
    private final BoldMemo tooltipTitleFont = new BoldMemo();

    /** One base-to-bold memo. Keyed on instance identity, which the token rows guarantee. */
    private static final class BoldMemo {
        private Font base;
        private Font derived;

        Font of(Font newBase) {
            if (newBase != base) {
                base = newBase;
                derived = newBase.bold();
            }
            return derived;
        }
    }

    // Regions, recomputed by layoutRegions() before every paint and every pointer test so
    // the two never disagree about where a mark is.
    private float contentX;
    private float contentY;
    private float contentWidth;
    private float contentHeight;
    private float legendX;
    private float legendY;
    private float legendWidth;
    private float legendHeight;
    /** Four floats per legend entry (x, y, w, h), reused so hovering allocates nothing. */
    private float[] legendBoxes = new float[0];
    private List<LegendEntry> legendCache;
    /** The language {@link #legendCache} was resolved in; a change re-reads every entry. */
    private long legendEpoch;

    // --------------------------------------------------------------------- data

    /** The category labels as they currently read, in order. */
    public List<String> labels() {
        List<String> resolved = new ArrayList<>(labels.size());
        for (I18nString label : labels) {
            resolved.add(label.get());
        }
        return resolved;
    }

    /** The label for {@code index}, or {@code ""} when there are fewer labels than values. */
    public String label(int index) {
        return index >= 0 && index < labels.size() ? labels.get(index).get() : "";
    }

    /**
     * The localizable value behind {@link #label(int)}, or {@link I18nString#EMPTY} past the
     * end: what a language change re-resolves.
     */
    public I18nString labelSource(int index) {
        return index >= 0 && index < labels.size() ? labels.get(index) : I18nString.EMPTY;
    }

    /** Sets the category labels: the x axis of a bar or line chart, the slices of a donut. */
    public Chart setLabels(String... values) {
        return setLabels(List.of(values));
    }

    /**
     * Sets category labels that follow the UI language. There is no {@code List} form of it
     * ({@code List<String>} and {@code List<I18nString>} erase to the same signature), so hand
     * a list over as {@code list.toArray(new I18nString[0])}.
     */
    public Chart setLabels(I18nString... values) {
        Ui.checkUiThread();
        beginDataChange();
        this.labels = List.of(values);
        endDataChange();
        return this;
    }

    /** {@link #setLabels(String...)} from a list; the list is copied. */
    public Chart setLabels(List<String> values) {
        Ui.checkUiThread();
        beginDataChange();
        List<I18nString> wrapped = new ArrayList<>(values.size());
        for (String value : values) {
            wrapped.add(I18nString.literal(value));
        }
        this.labels = List.copyOf(wrapped);
        endDataChange();
        return this;
    }

    /** The series, in the order they were added (paint and palette order). */
    public List<ChartSeries> series() {
        return List.copyOf(series);
    }

    /**
     * Adds a series. It takes the next palette slot unless it names its own color.
     *
     * @throws IllegalStateException if the series already belongs to a chart
     */
    public Chart addSeries(ChartSeries newSeries) {
        Ui.checkUiThread();
        Objects.requireNonNull(newSeries, "series");
        if (newSeries.owner != null) {
            throw new IllegalStateException("series already belongs to a chart");
        }
        beginDataChange();
        newSeries.owner = this;
        newSeries.fade = new Transition(this, newSeries.isVisible() ? 1 : 0)
                .duration(stateFadeSeconds()).easing(Theme.current().animEasing);
        series.add(newSeries);
        onSeriesAdded(newSeries);
        endDataChange();
        return this;
    }

    /**
     * Called with a series that has just joined the chart, before anything is animated.
     * Charts with per-series defaults of their own apply them here, so a series added
     * after the default was set is styled like the ones that were already there.
     */
    protected void onSeriesAdded(ChartSeries added) {
    }

    /** Replaces every series at once. */
    public Chart setSeries(ChartSeries... newSeries) {
        Ui.checkUiThread();
        clearSeries();
        for (ChartSeries s : newSeries) {
            addSeries(s);
        }
        return this;
    }

    /** Removes a series; a no-op when it is not in this chart. */
    public Chart removeSeries(ChartSeries victim) {
        Ui.checkUiThread();
        if (series.contains(victim)) {
            beginDataChange();
            series.remove(victim);
            detach(victim);
            endDataChange();
        }
        return this;
    }

    /** Removes every series. */
    public Chart clearSeries() {
        Ui.checkUiThread();
        if (series.isEmpty()) {
            return this;
        }
        beginDataChange();
        for (ChartSeries s : series) {
            detach(s);
        }
        series.clear();
        endDataChange();
        return this;
    }

    private static void detach(ChartSeries s) {
        s.owner = null;
        s.fade = null;
        s.from = null;
    }

    /**
     * The series at {@code index}, without copying the list, the accessor the painting
     * and hit-testing paths use, since {@link #series()} allocates and both run per frame.
     */
    protected final ChartSeries series(int index) {
        return series.get(index);
    }

    /** How many series the chart holds, visible or not. */
    protected final int seriesCount() {
        return series.size();
    }

    /**
     * Bumped by every change to the data or to what is visible in it. Derived structures
     * that are expensive to rebuild (a stack-key list walked once per bar) cache against
     * it instead of rebuilding per frame.
     */
    protected final int dataGeneration() {
        return dataGeneration;
    }

    /** Left edge of the region left after the title and the legend. */
    protected final float contentLeft() {
        return contentX;
    }

    /** Top edge of the region left after the title and the legend. */
    protected final float contentTop() {
        return contentY;
    }

    /** Width of the region left after the title and the legend. */
    protected final float contentBoxWidth() {
        return contentWidth;
    }

    /** Height of the region left after the title and the legend. */
    protected final float contentBoxHeight() {
        return contentHeight;
    }

    /** How many categories the chart spans: the labels, or the longest series. */
    public final int categoryCount() {
        int count = labels.size();
        for (int i = 0; i < series.size(); i++) {
            count = Math.max(count, series.get(i).size());
        }
        return count;
    }

    // -------------------------------------------------------------- appearance

    /** The palette in force: the one that was set, or the built-in one for this theme. */
    public ChartPalette palette() {
        return palette != null ? palette : ChartPalette.defaultFor(Theme.current());
    }

    /**
     * Pins the palette; {@code null} hands it back to {@link ChartPalette#defaultFor},
     * which follows the active theme's light/dark mode.
     */
    public Chart setPalette(ChartPalette value) {
        Ui.checkUiThread();
        this.palette = value;
        legendCache = null;
        invalidate();
        return this;
    }

    /** The color of series {@code index}: its own, or its palette slot. */
    public final Color seriesColor(int index) {
        ChartSeries s = series.get(index);
        return s.color() != null ? s.color() : palette().color(index);
    }

    /** The chart title as it currently reads, or {@code null} when there is none. */
    public String title() {
        return title == null ? null : title.get();
    }

    /** The localizable value behind {@link #title()}, or {@code null}. */
    public I18nString titleSource() {
        return title;
    }

    /** Sets a title drawn above the plot ({@code null} for none). */
    public Chart setTitle(String value) {
        return setTitle(value == null ? null : I18nString.literal(value));
    }

    /** Sets a title that follows the UI language ({@code null} for none). */
    public Chart setTitle(I18nString value) {
        Ui.checkUiThread();
        this.title = value;
        markNeedsLayout();
        return this;
    }

    /** The panel color behind the chart, or {@code null} when it paints on what is below. */
    public Color background() {
        return background;
    }

    /**
     * Fills the chart's box with {@code color} before anything else, rounded like a card;
     * {@code null} (the default) paints nothing, letting the chart sit on the surface it
     * was placed on.
     */
    public Chart setBackground(Color color) {
        Ui.checkUiThread();
        this.background = color;
        invalidate();
        return this;
    }

    /** Where the legend sits. */
    public LegendPosition legendPosition() {
        return legendPosition;
    }

    /** Moves the legend, or takes it away with {@link LegendPosition#NONE}. */
    public Chart setLegendPosition(LegendPosition position) {
        Ui.checkUiThread();
        this.legendPosition = Objects.requireNonNull(position, "position");
        legendCache = null; // NONE caches an empty list; the entries have to come back
        markNeedsLayout();
        return this;
    }

    /** Whether clicking a legend entry hides and shows its data. */
    public boolean isLegendInteractive() {
        return legendInteractive;
    }

    /** Enables or disables hide/show on legend clicks (on by default). */
    public Chart setLegendInteractive(boolean value) {
        Ui.checkUiThread();
        this.legendInteractive = value;
        return this;
    }

    /**
     * Overrides the natural size ({@value #DEFAULT_WIDTH} x {@value #DEFAULT_HEIGHT});
     * {@link #UNSET} on either axis restores it. A chart in an {@code Expanded} or a
     * stretched {@code Column} takes the space it is given regardless; this is the
     * fallback for an unconstrained parent.
     */
    public Chart setPreferredSize(float width, float height) {
        Ui.checkUiThread();
        this.preferredWidth = width;
        this.preferredHeight = height;
        markNeedsLayout();
        return this;
    }

    // -------------------------------------------------------------- formatting

    /** How values are written in tooltips (and, unless overridden, on the value axis). */
    public DoubleFunction<String> valueFormat() {
        return valueFormat;
    }

    /** Sets the tooltip value format; see {@link ChartFormats} for ready-made ones. */
    public Chart setValueFormat(DoubleFunction<String> format) {
        Ui.checkUiThread();
        this.valueFormat = Objects.requireNonNull(format, "format");
        invalidate();
        return this;
    }

    /** Whether hovering shows a tooltip. */
    public boolean isTooltipEnabled() {
        return tooltipEnabled;
    }

    /** Turns the hover tooltip on or off (on by default). */
    public Chart setTooltipEnabled(boolean value) {
        Ui.checkUiThread();
        this.tooltipEnabled = value;
        if (!value) {
            tooltipFade.snap(0);
        }
        invalidate();
        return this;
    }

    /** How much of the data a hover reports. */
    public TooltipMode tooltipMode() {
        return tooltipMode;
    }

    /** Sets whether a hover reports the whole category or only the mark under the pointer. */
    public Chart setTooltipMode(TooltipMode mode) {
        Ui.checkUiThread();
        this.tooltipMode = Objects.requireNonNull(mode, "mode");
        invalidate();
        return this;
    }

    /**
     * Replaces the text of a tooltip row. The default lays each row out in two columns (the
     * series name where reading starts, the formatted value where it ends, so both columns
     * swap in a chart that reads right to left), which is what makes a multi-series tooltip
     * scannable; a formatter set here produces the whole row as one string instead.
     * {@code null} restores the default.
     */
    public Chart setTooltipFormat(Function<ChartPoint, String> format) {
        Ui.checkUiThread();
        this.tooltipFormat = format;
        invalidate();
        return this;
    }

    // --------------------------------------------------------------- animation

    /** Length of the entry and value-change animation, in seconds. */
    public double animationDuration() {
        return animationSeconds;
    }

    /** Sets the animation length in seconds; {@code 0} draws every change immediately. */
    public Chart setAnimationDuration(double seconds) {
        Ui.checkUiThread();
        this.animationSeconds = Math.max(0, seconds);
        if (animationSeconds == 0) {
            anim.snap(1);
        }
        return this;
    }

    /** Sets the animation curve (default {@link Easing#EASE_OUT}). */
    public Chart setAnimationEasing(Easing easing) {
        Ui.checkUiThread();
        this.animationEasing = Objects.requireNonNull(easing, "easing");
        return this;
    }

    /** Replays the entry animation from the axis baseline. */
    public Chart replayAnimation() {
        Ui.checkUiThread();
        for (ChartSeries s : series) {
            s.from = null;
        }
        restartAnimation();
        return this;
    }

    // ------------------------------------------------------------------ events

    /**
     * Called with the datum under the pointer on a left click. In
     * {@link TooltipMode#INDEX} a click anywhere in a category reports that category's
     * nearest mark, so a thin line is as clickable as a fat bar.
     */
    public Chart onPointClick(Consumer<ChartPoint> listener) {
        Ui.checkUiThread();
        this.onPointClick = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Called whenever the hovered datum changes, with {@code null} when the pointer leaves
     * the marks. Fires on changes only, not on every pointer move.
     */
    public Chart onPointHover(Consumer<ChartPoint> listener) {
        Ui.checkUiThread();
        this.onHover = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** The datum under the pointer, or {@code null}. */
    public ChartPoint hoveredPoint() {
        return hovered;
    }

    /** Chaining form of {@link #setControlSize}; {@code setControlSize} is {@code void}. */
    public Chart withControlSize(ControlSize size) {
        setControlSize(size);
        return this;
    }

    // ------------------------------------------------------- subclass contract

    /**
     * Paints the marks inside the region left after the title and the legend, in the
     * chart's own coordinates. Grid, axes and everything data-shaped belong here.
     */
    protected abstract void paintContent(Canvas canvas, float x, float y, float w, float h);

    /**
     * The datum under a point in the chart's local coordinates, or {@code null} when the
     * pointer is not over the marks. Called for hover and for clicks, so it decides both.
     */
    protected abstract ChartPoint pickAt(float localX, float localY);

    /**
     * The rows a tooltip shows for {@code picked}. The default is
     * {@link TooltipMode#INDEX}-aware: every visible series at the same index, or just the
     * picked datum in {@link TooltipMode#POINT}.
     */
    protected List<ChartPoint> tooltipRows(ChartPoint picked) {
        if (tooltipMode == TooltipMode.POINT) {
            // Rebuilt rather than echoed: a chart whose values are re-pushed while the
            // pointer rests on a mark must report what the mark says now.
            return List.of(pointFor(picked.seriesIndex(), picked.index(), picked.share(),
                    picked.x(), picked.y()));
        }
        List<ChartPoint> rows = new ArrayList<>(series.size());
        for (int i = 0; i < series.size(); i++) {
            ChartSeries s = series.get(i);
            if (!s.isVisible() || Double.isNaN(s.value(picked.index()))) {
                continue;
            }
            rows.add(pointFor(i, picked.index(), Double.NaN, 0, 0));
        }
        return rows.isEmpty() ? List.of(picked) : rows;
    }

    /** The heading over the tooltip rows; the category label by default. */
    protected String tooltipTitle(ChartPoint picked) {
        return picked.label();
    }

    /** The name a tooltip row carries where reading starts, the series name by default. */
    protected String tooltipRowName(ChartPoint row) {
        return row.series().name();
    }

    /** The value a tooltip row carries where reading ends. */
    protected String tooltipRowValue(ChartPoint row) {
        return valueFormat.apply(row.value());
    }

    /** The swatch color for a tooltip row. */
    protected Color tooltipRowColor(ChartPoint row) {
        return seriesColor(row.seriesIndex());
    }

    /** One entry per series, in palette order. Overridden by charts whose legend is not that. */
    protected List<LegendEntry> legendEntries() {
        List<LegendEntry> entries = new ArrayList<>(series.size());
        for (int i = 0; i < series.size(); i++) {
            ChartSeries s = series.get(i);
            entries.add(new LegendEntry(s.name(), seriesColor(i), s.isVisible()));
        }
        return entries;
    }

    /** Hides or shows what legend entry {@code index} stands for. */
    protected void toggleLegendEntry(int index) {
        ChartSeries s = series.get(index);
        s.setVisible(!s.isVisible());
    }

    /** Drops the legend's cached entries, for a chart whose legend is not the series list. */
    protected final void legendChanged() {
        legendCache = null;
        invalidate();
    }

    /**
     * Called when the datum under the pointer changes, {@code null} when there is none:
     * the hook for a chart that animates something on hover. The application-facing
     * callback is {@link #onPointHover(Consumer)}; this is for subclasses.
     */
    protected void onHoverChanged(ChartPoint picked) {
    }

    /** Recomputes the title/legend/plot split. For a subclass laying out in {@code onLayout}. */
    protected final void updateRegions() {
        layoutRegions(tokens(), isRtl());
    }

    /**
     * The value a series grows out of when it first appears, and collapses to when it is
     * hidden: where the axis crosses the plot, which is zero on any scale that contains
     * it and the nearer end otherwise.
     */
    protected double animationBaseline() {
        return 0;
    }

    /**
     * How far apart consecutive elements start, as a fraction of the animation: a wipe
     * rather than a single pop. {@code 0} (the default) starts everything at once, which
     * is what a line wants: staggering the points of a line animates a wave through it.
     */
    protected float staggerFraction() {
        return 0;
    }

    // ------------------------------------------------------- painting helpers

    /** Overall animation progress in {@code [0,1]}; 1 means the data as it stands. */
    protected final float progress() {
        return animationSeconds <= 0 ? 1 : anim.value();
    }

    /**
     * Animation progress for element {@code index} of {@code count}, with
     * {@link #staggerFraction()} applied. Elements past the first start later and finish
     * later, and every one of them reaches 1 by the time {@link #progress()} does.
     */
    protected final float elementProgress(int index, int count) {
        float p = progress();
        float stagger = staggerFraction();
        if (stagger <= 0 || count <= 1 || p >= 1) {
            return p;
        }
        float step = Math.min(stagger, 0.6f / (count - 1));
        float span = 1 - step * (count - 1);
        float local = (p - index * step) / span;
        return Math.max(0, Math.min(1, local));
    }

    /** The alpha a series is currently drawn at, folding in its hide/show fade. */
    protected final float seriesAlpha(int index) {
        Transition fade = series.get(index).fade;
        return fade == null ? (series.get(index).isVisible() ? 1 : 0) : fade.value();
    }

    /** The value series {@code seriesIndex} is drawn at right now, animation included. */
    protected final double drawnValue(int seriesIndex, int index) {
        ChartSeries s = series.get(seriesIndex);
        return s.drawnValue(index, elementProgress(index, categoryCount()), animationBaseline());
    }

    /** Builds a {@link ChartPoint} for a datum, with the anchor the caller measured. */
    protected final ChartPoint pointFor(int seriesIndex, int index, double share, float x, float y) {
        ChartSeries s = series.get(seriesIndex);
        return new ChartPoint(s, seriesIndex, index, label(index), s.value(index), share, x, y);
    }

    /** The tokens for the step resolved on this chart; resolve once per pass, never in a field. */
    protected final SizeTokens tokens() {
        return Theme.current().tokensFor(this);
    }

    /**
     * Whether this chart reads right to left. Called exactly once at the top of a paint, an
     * overlay paint, a region pass or a pointer pass, and the answer is threaded down from
     * there: a paint and a hit test that resolved the direction separately could disagree, and
     * a legend whose swatches are painted on one side and hit-tested on the other is the one
     * bug this widget must not have.
     *
     * <p>Never a field and never resolved in a constructor. A chart is normally built and
     * filled before it joins a scene, so a direction captured while it still had no parent
     * would be the process default for the rest of its life, with no path to recovery.
     */
    private boolean isRtl() {
        return layoutDirection() == LayoutDirection.RTL;
    }

    /** Measures a single line with the layout ruler (agrees with what {@code drawText} draws). */
    protected final TextMetrics measure(String text, Font font) {
        return textRuler().measure(text, font);
    }

    /**
     * One line of the chart's own chrome &mdash; a title, a legend name, a tooltip row &mdash;
     * shaped for the paragraph this chart reads in, so that the width a box is sized from is the
     * width of the line that box will hold.
     *
     * <p><b>Why a chart is the widget this matters most to.</b> Its chrome is application data
     * that is very often entirely neutral: a series named {@code 2024}, a tooltip row reading
     * {@code 3.5}, a category called {@code Q1}. Not one of those has a strong character, so the
     * first-strong rule has nothing to decide with and the fallback decides all of it &mdash; and
     * the fallback is the direction of the interface, which only the widget knows. A series named
     * {@code Vendas} is unaffected, because its V already decided.
     *
     * <p>{@code base} is passed in rather than resolved here so that one pass resolves it once:
     * the layout walk that sizes the legend and the paint that fills it must be answering for the
     * same paragraph, or the boxes and the names in them come from two different shapings.
     *
     * <p>Not held. The ruler memoizes shaping, which is what makes this affordable for a widget
     * that rebuilds its chrome every frame of an animation &mdash; the case {@code TextRuler}
     * names when it says an implementation is expected to memoize.
     */
    private ShapedText shaped(String text, Font font, ShapedText.Direction base) {
        return textRuler().shape(text, font, ShapedText.Direction.of(text, base));
    }

    /**
     * What a piece of chart chrome with no strong character falls back to, from a direction the
     * caller has already resolved for its pass. A method rather than a ternary at six call sites
     * so that "which enum names this side" is answered in one place.
     */
    private static ShapedText.Direction baseFor(boolean rtl) {
        return rtl ? ShapedText.Direction.RTL : ShapedText.Direction.LTR;
    }

    /**
     * {@code text} shortened with an ellipsis until it fits {@code maxWidth}, or
     * {@code ""} when not even the ellipsis fits. Category labels are application data:
     * they are as long as they are, and a chart that lets them collide is unreadable.
     */
    protected final String ellipsize(String text, Font font, float maxWidth) {
        if (text.isEmpty() || measure(text, font).width() <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        if (measure(ellipsis, font).width() > maxWidth) {
            return "";
        }
        int end = text.length();
        while (end > 0) {
            String candidate = text.substring(0, end) + ellipsis;
            if (measure(candidate, font).width() <= maxWidth) {
                return candidate;
            }
            end--;
        }
        return ellipsis;
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onAttached() {
        // A chart is normally built and filled before it joins a scene, and a Transition
        // with no scene snaps instead of animating, so the entry animation would never be
        // seen. Arm it here, the same way ProgressBar arms its sweep.
        restartAnimation();
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        return constraints.constrain(
                preferredWidth >= 0 ? preferredWidth : DEFAULT_WIDTH,
                preferredHeight >= 0 ? preferredHeight : DEFAULT_HEIGHT);
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = tokens();
        boolean rtl = isRtl();
        if (background != null) {
            canvas.fillRoundRect(0, 0, width(), height(), t.radiusMedium(), background);
        }
        layoutRegions(t, rtl);
        String heading = title == null ? "" : title.get();
        if (!heading.isEmpty()) {
            Font font = titleFont.of(t.body());
            TextMetrics m = measure(heading, font);
            // The drawn string, hoisted: ellipsize may have dropped characters, and a title
            // right-aligned from the width of the full heading would hang off the edge by
            // exactly the number it dropped. The width budget itself is symmetric (the pad
            // comes off both ends), so it is the same number in either direction.
            String shown = ellipsize(heading, font, width() - 2 * t.spacingMedium());
            // Shaped once: the edge a mirrored title is placed from is the width of this very
            // line, and it is this line that is drawn. The baseline still comes from the full
            // heading's vertical metrics, which ellipsizing cannot move.
            ShapedText line = shaped(shown, font, baseFor(rtl));
            float titleX = rtl
                    ? width() - t.spacingMedium() - line.metrics().width()
                    : t.spacingMedium();
            canvas.drawText(line, titleX, t.spacingSmall() + m.ascent(), theme.text);
        }
        paintLegend(canvas, t, theme, rtl);
        if (contentWidth > 1 && contentHeight > 1) {
            paintContent(canvas, contentX, contentY, contentWidth, contentHeight);
        }
    }

    @Override
    protected void onPaintOverlay(Canvas canvas) {
        paintTooltip(canvas, tokens(), Theme.current(), isRtl());
    }

    // ---------------------------------------------------------------- regions

    /**
     * Splits the box into title, legend and plot. Called before every paint and before
     * every pointer test: a hover that resolved against a different plot rectangle than
     * the frame the user aimed at would report the wrong bar, which is the one bug this
     * kind of widget must not have. {@code rtl} is resolved once by the caller and handed down
     * for that same reason.
     *
     * <p>The plot rectangle it leaves behind stays physical: {@link #contentLeft()} means the
     * physical left edge in both directions, and mirroring what a subclass draws inside it is
     * that subclass's decision, taken against its own axes.
     */
    private void layoutRegions(SizeTokens t, boolean rtl) {
        // Resolved once for the whole walk and handed to every line it shapes: the boxes this
        // leaves behind are what the paint fills, so the two have to size from one paragraph.
        ShapedText.Direction base = baseFor(rtl);
        float pad = t.spacingSmall();
        float x = pad;
        float y = pad;
        float w = Math.max(0, width() - 2 * pad);
        float h = Math.max(0, height() - 2 * pad);

        String heading = title == null ? "" : title.get();
        if (!heading.isEmpty()) {
            float titleHeight = measure(heading, titleFont.of(t.body())).lineHeight()
                    + t.spacingSmall();
            y += titleHeight;
            h -= titleHeight;
        }

        List<LegendEntry> entries = legend();
        legendWidth = 0;
        legendHeight = 0;
        if (!entries.isEmpty()) {
            LegendPosition position = resolvedLegendPosition(entries);
            Font font = t.label();
            float swatch = swatchSize(font);
            float rowHeight = Math.max(swatch, measure("X", font).lineHeight());
            float gap = t.spacingMedium();
            if (legendBoxes.length < entries.size() * 4) {
                legendBoxes = new float[entries.size() * 4];
            }
            switch (position) {
                case LEFT, RIGHT -> {
                    // LEFT and RIGHT are published physical constants and stay physical: a
                    // chart that already asks for the legend on the left must keep getting it
                    // there, whichever way the interface reads. A logical pair would be added
                    // beside them, never carved out of them.
                    float maxWidth = 0;
                    for (LegendEntry e : entries) {
                        maxWidth = Math.max(maxWidth,
                                swatch + t.gapIcon() + shaped(e.text(), font, base).metrics().width());
                    }
                    legendWidth = Math.min(maxWidth, w * 0.4f);
                    legendHeight = entries.size() * (rowHeight + t.spacingSmall());
                    legendX = position == LegendPosition.LEFT ? x : x + w - legendWidth;
                    legendY = y + Math.max(0, (h - legendHeight) / 2);
                    for (int i = 0; i < entries.size(); i++) {
                        setBox(i, legendX, legendY + i * (rowHeight + t.spacingSmall()),
                                legendWidth, rowHeight);
                    }
                    if (position == LegendPosition.LEFT) {
                        x += legendWidth + gap;
                    }
                    w -= legendWidth + gap;
                }
                case TOP, BOTTOM -> {
                    // Wrapped rows, each centred: a legend that runs off the side names
                    // series the reader cannot see.
                    int rows = 1;
                    float lineWidth = 0;
                    for (LegendEntry e : entries) {
                        float entryWidth =
                                swatch + t.gapIcon() + shaped(e.text(), font, base).metrics().width();
                        if (lineWidth > 0 && lineWidth + gap + entryWidth > w) {
                            rows++;
                            lineWidth = entryWidth;
                        } else {
                            lineWidth += (lineWidth > 0 ? gap : 0) + entryWidth;
                        }
                    }
                    legendHeight = rows * rowHeight + (rows - 1) * t.spacingSmall();
                    legendWidth = w;
                    legendX = x;
                    legendY = position == LegendPosition.TOP ? y : y + h - legendHeight;
                    layoutLegendRows(entries, t, font, swatch, rowHeight, gap, w, rtl);
                    if (position == LegendPosition.TOP) {
                        y += legendHeight + t.spacingMedium();
                    }
                    h -= legendHeight + t.spacingMedium();
                }
                default -> {
                }
            }
        }

        contentX = x;
        contentY = y;
        contentWidth = Math.max(0, w);
        contentHeight = Math.max(0, h);
    }

    /**
     * Places one wrapped, centred row of entries at a time into {@link #legendBoxes}.
     *
     * <p>The centred block itself does not move: a row narrower than the band is centred in it
     * in both directions, and the wrap points are the same because the row widths are. What the
     * direction decides is only the order the row is walked in, so that entry zero is the one
     * the reader meets first. The walk keeps its arithmetic and the coordinate is mirrored at
     * the point it becomes a box, which is what keeps the hit test correct without a second
     * decision: {@link #legendEntryAt} is plain rectangle containment and never learns that
     * anything moved.
     */
    private void layoutLegendRows(List<LegendEntry> entries, SizeTokens t, Font font,
                                  float swatch, float rowHeight, float gap, float available,
                                  boolean rtl) {
        // The row's own resolution, from the direction its caller already resolved: an entry
        // measured for one paragraph and wrapped against widths taken for another would break the
        // row at a point the paint does not agree with.
        ShapedText.Direction base = baseFor(rtl);
        int rowStart = 0;
        float rowWidth = 0;
        float rowY = legendY;
        for (int i = 0; i <= entries.size(); i++) {
            float entryWidth = i < entries.size()
                    ? swatch + t.gapIcon()
                            + shaped(entries.get(i).text(), font, base).metrics().width()
                    : 0;
            boolean wraps = i == entries.size()
                    || (rowWidth > 0 && rowWidth + gap + entryWidth > available);
            if (wraps) {
                float rowLeft = legendX + Math.max(0, (available - rowWidth) / 2);
                float consumed = 0;
                for (int j = rowStart; j < i; j++) {
                    float itemWidth = swatch + t.gapIcon()
                            + shaped(entries.get(j).text(), font, base).metrics().width();
                    setBox(j, rtl ? rowLeft + rowWidth - consumed - itemWidth : rowLeft + consumed,
                            rowY, itemWidth, rowHeight);
                    consumed += itemWidth + gap;
                }
                rowStart = i;
                rowWidth = entryWidth;
                rowY += rowHeight + t.spacingSmall();
            } else {
                rowWidth += (rowWidth > 0 ? gap : 0) + entryWidth;
            }
        }
    }

    private void setBox(int index, float x, float y, float w, float h) {
        legendBoxes[index * 4] = x;
        legendBoxes[index * 4 + 1] = y;
        legendBoxes[index * 4 + 2] = w;
        legendBoxes[index * 4 + 3] = h;
    }

    private float swatchSize(Font font) {
        return Math.round(font.size() * 0.75f);
    }

    private LegendPosition resolvedLegendPosition(List<LegendEntry> entries) {
        if (legendPosition != LegendPosition.AUTO) {
            return legendPosition;
        }
        return entries.size() >= 2 ? LegendPosition.BOTTOM : LegendPosition.NONE;
    }

    /**
     * The legend entries, rebuilt only when the data behind them changed, or when the UI
     * language did, since an entry holds resolved text and nothing else would re-read it.
     */
    private List<LegendEntry> legend() {
        if (legendCache == null || legendEpoch != I18n.epoch()) {
            List<LegendEntry> entries = legendEntries();
            legendCache = resolvedLegendPosition(entries) == LegendPosition.NONE
                    ? List.of() : entries;
            legendEpoch = I18n.epoch();
        }
        return legendCache;
    }

    /**
     * Draws the entries into the boxes {@link #layoutRegions} left behind.
     *
     * <p>Mirroring happens <em>inside</em> the box, off its own two edges, and never by
     * rewriting the box: two different paths write those boxes and the hit test reads them
     * without knowing which one did, so the box is the contract and its contents are the
     * decision. A box can be narrower than the entry it holds &mdash; the side legend clamps
     * its column and legend text is never ellipsized &mdash; and aligning off the box's own
     * trailing edge lets that overflow run out the far side exactly as it already runs out the
     * right in a left-to-right chart.
     */
    private void paintLegend(Canvas canvas, SizeTokens t, Theme theme, boolean rtl) {
        List<LegendEntry> entries = legend();
        if (entries.isEmpty()) {
            return;
        }
        Font font = t.label();
        float swatch = swatchSize(font);
        // One resolution for the whole legend, the same one layoutRegions sized these boxes with.
        ShapedText.Direction base = baseFor(rtl);
        for (int i = 0; i < entries.size(); i++) {
            LegendEntry entry = entries.get(i);
            float x = legendBoxes[i * 4];
            float y = legendBoxes[i * 4 + 1];
            float w = legendBoxes[i * 4 + 2];
            float h = legendBoxes[i * 4 + 3];
            // The line the box was sized from and the line this row draws: one value, so a name
            // cannot be placed against a width nothing on the screen has.
            ShapedText line = shaped(entry.text(), font, base);
            TextMetrics m = line.metrics();
            float swatchY = y + (h - swatch) / 2;
            float radius = swatch * 0.3f;
            float swatchX = rtl ? x + w - swatch : x;
            if (entry.visible()) {
                canvas.fillRoundRect(swatchX, swatchY, swatch, swatch, radius, entry.color());
            } else {
                canvas.drawRoundRect(swatchX + Strokes.HALF_PIXEL_INSET,
                        swatchY + Strokes.HALF_PIXEL_INSET,
                        swatch - 1, swatch - 1, radius, Strokes.BORDER, theme.textMuted);
            }
            // The run's LEFT edge in both directions, which is what drawText places against:
            // reading right to left the name ends where the swatch begins, so its left edge is
            // that point less the width the run will take.
            float textX = rtl
                    ? x + w - swatch - t.gapIcon() - m.width()
                    : x + swatch + t.gapIcon();
            float baseline = y + (h - m.height()) / 2 + m.ascent();
            Color ink = !entry.visible() ? theme.disabledText
                    : i == hoveredLegend ? theme.text : theme.textMuted;
            canvas.drawText(line, textX, baseline, ink);
            if (!entry.visible()) {
                // Struck through, so "hidden" survives being read in grayscale.
                float midline = y + h / 2;
                canvas.drawLine(textX, midline, textX + m.width(), midline, Strokes.BORDER,
                        theme.disabledText);
            }
        }
    }

    // ---------------------------------------------------------------- tooltip

    private void paintTooltip(Canvas canvas, SizeTokens t, Theme theme, boolean rtl) {
        float fade = tooltipFade.value();
        ChartPoint picked = hovered;
        if (fade <= 0.01f || picked == null || !tooltipEnabled) {
            return;
        }
        List<ChartPoint> rows = cachedTooltipRows(picked);
        Font headingFont = tooltipTitleFont.of(t.label());
        Font rowFont = t.label();
        String heading = tooltipTitle(picked);
        float padH = t.tooltipPadH();
        float padV = t.tooltipPadV();
        float gap = t.spacingMedium();
        float swatch = swatchSize(rowFont);

        float rowHeight = measure("X", rowFont).lineHeight();
        float headingHeight = heading.isEmpty() ? 0 : measure(heading, headingFont).lineHeight();
        // The panel is sized from the lines it will hold. A tooltip row is the most reliably
        // neutral string a chart draws -- a name that is a year, a value that is a number -- so
        // sizing it without the fallback sizes a different line than the one painted below.
        ShapedText.Direction base = baseFor(rtl);
        float panelWidth = heading.isEmpty() ? 0
                : shaped(heading, headingFont, base).metrics().width();
        for (ChartPoint row : rows) {
            float width = swatch + t.gapIcon()
                    + shaped(rowName(row), rowFont, base).metrics().width();
            if (tooltipFormat == null) {
                width += gap + shaped(tooltipRowValue(row), rowFont, base).metrics().width();
            }
            panelWidth = Math.max(panelWidth, width);
        }
        panelWidth += 2 * padH;
        float panelHeight = 2 * padV + headingHeight + rows.size() * rowHeight;

        // Beside the pointer, flipped rather than clamped when it would not fit: a panel
        // pinned to the edge covers the very marks the reader is pointing at. The side offered
        // first is the one reading runs towards, so the panel opens away from the pointer the
        // way the reader's eye already travels; the two sides swap as one expression, since a
        // preferred side that flipped without its fallback could place the panel outside the box.
        float px;
        if (rtl) {
            px = pointerX - TOOLTIP_OFFSET - panelWidth;
            if (px < 0) {
                px = pointerX + TOOLTIP_OFFSET;
            }
        } else {
            px = pointerX + TOOLTIP_OFFSET;
            if (px + panelWidth > width()) {
                px = pointerX - TOOLTIP_OFFSET - panelWidth;
            }
        }
        px = Math.max(0, Math.min(width() - panelWidth, px));
        float py = pointerY + TOOLTIP_OFFSET;
        if (py + panelHeight > height()) {
            py = pointerY - TOOLTIP_OFFSET - panelHeight;
        }
        py = Math.max(0, Math.min(Math.max(0, height() - panelHeight), py));

        canvas.save();
        try {
            canvas.setOpacity(canvas.opacity() * fade);
            canvas.fillRoundRect(px, py, panelWidth, panelHeight, t.radiusSmall(),
                    theme.surfaceRaised);
            canvas.drawRoundRect(px + Strokes.HALF_PIXEL_INSET, py + Strokes.HALF_PIXEL_INSET,
                    panelWidth - 1, panelHeight - 1, t.radiusSmall(), Strokes.BORDER, theme.outline);
            float y = py + padV;
            if (!heading.isEmpty()) {
                ShapedText line = shaped(heading, headingFont, base);
                TextMetrics m = line.metrics();
                float headingX = rtl ? px + panelWidth - padH - m.width() : px + padH;
                canvas.drawText(line, headingX, y + m.ascent(), theme.text);
                y += headingHeight;
            }
            for (ChartPoint row : rows) {
                ShapedText nameLine = shaped(rowName(row), rowFont, base);
                TextMetrics m = nameLine.metrics();
                float swatchY = y + (rowHeight - swatch) / 2;
                float swatchX = rtl ? px + panelWidth - padH - swatch : px + padH;
                canvas.fillRoundRect(swatchX, swatchY, swatch, swatch, swatch * 0.3f,
                        tooltipRowColor(row));
                // Again the run's left edge in both directions, measured back from the swatch.
                float textX = rtl
                        ? px + panelWidth - padH - swatch - t.gapIcon() - m.width()
                        : px + padH + swatch + t.gapIcon();
                float baseline = y + (rowHeight - m.height()) / 2 + m.ascent();
                canvas.drawText(nameLine, textX, baseline, theme.textMuted);
                if (tooltipFormat == null) {
                    // The value is the row's far column, so it swaps sides with the name in the
                    // same pass; separately they would collide.
                    ShapedText valueLine = shaped(tooltipRowValue(row), rowFont, base);
                    float valueWidth = valueLine.metrics().width();
                    float valueX = rtl ? px + padH : px + panelWidth - padH - valueWidth;
                    canvas.drawText(valueLine, valueX, baseline, theme.text);
                }
                y += rowHeight;
            }
        } finally {
            canvas.restore();
        }
    }

    /**
     * {@link #tooltipRows} for the datum currently hovered, memoized. The rows report the
     * values as set (not the animated ones), so they change only with the data, while
     * this is called on every frame the tooltip is on screen.
     */
    private List<ChartPoint> cachedTooltipRows(ChartPoint picked) {
        if (picked != tooltipRowsFor || tooltipRowsGeneration != dataGeneration
                || tooltipRowsEpoch != I18n.epoch()) {
            tooltipRowsCache = tooltipRows(picked);
            tooltipRowsFor = picked;
            tooltipRowsGeneration = dataGeneration;
            tooltipRowsEpoch = I18n.epoch();
        }
        return tooltipRowsCache;
    }

    private String rowName(ChartPoint row) {
        return tooltipFormat != null ? tooltipFormat.apply(row) : tooltipRowName(row);
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void onMouseEvent(MouseEvent event) {
        float localX = sceneToLocalX(event.x());
        float localY = sceneToLocalY(event.y());
        switch (event.type()) {
            case ENTER, MOVE, DRAG -> updatePointer(localX, localY);
            case EXIT -> clearPointer();
            case CLICK -> {
                if (event.button() != Keys.MOUSE_LEFT || !isEnabled()) {
                    return;
                }
                updatePointer(localX, localY);
                if (hoveredLegend >= 0 && legendInteractive) {
                    toggleLegendEntry(hoveredLegend);
                    event.consume();
                } else if (hovered != null) {
                    onPointClick.accept(hovered);
                    event.consume();
                }
            }
            default -> {
            }
        }
    }

    private void updatePointer(float localX, float localY) {
        layoutRegions(tokens(), isRtl());
        pointerX = localX;
        pointerY = localY;

        int legendHit = legendEntryAt(localX, localY);
        if (legendHit != hoveredLegend) {
            hoveredLegend = legendHit;
            invalidate();
        }

        ChartPoint picked = legendHit >= 0 ? null : pickAt(localX, localY);
        // Only on a change: setCursor notifies the scene, and this runs on every motion.
        boolean wantsPointer = legendHit >= 0 && legendInteractive || picked != null;
        if (wantsPointer != pointerCursor) {
            pointerCursor = wantsPointer;
            setCursor(wantsPointer ? Cursor.POINTER : null);
        }
        if (samePoint(picked, hovered)) {
            if (picked != null) {
                invalidate(); // the panel follows the pointer even within one mark
            }
            return;
        }
        hovered = picked;
        tooltipFade.to(picked != null && tooltipEnabled ? 1 : 0);
        invalidate();
        onHoverChanged(picked);
        onHover.accept(picked);
    }

    private void clearPointer() {
        if (hoveredLegend >= 0) {
            hoveredLegend = -1;
            invalidate();
        }
        if (pointerCursor) {
            pointerCursor = false;
            setCursor(null);
        }
        if (hovered != null) {
            hovered = null;
            tooltipFade.to(0);
            invalidate();
            onHoverChanged(null);
            onHover.accept(null);
        }
    }

    /** Two picks are the same hover when they name the same datum, wherever the pointer is. */
    private static boolean samePoint(ChartPoint a, ChartPoint b) {
        return a == b
                || a != null && b != null && a.seriesIndex() == b.seriesIndex() && a.index() == b.index();
    }

    private int legendEntryAt(float x, float y) {
        List<LegendEntry> entries = legend();
        for (int i = 0; i < entries.size(); i++) {
            float bx = legendBoxes[i * 4];
            float by = legendBoxes[i * 4 + 1];
            float bw = legendBoxes[i * 4 + 2];
            float bh = legendBoxes[i * 4 + 3];
            if (x >= bx && x <= bx + bw && y >= by && y <= by + bh) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------- change plumbing

    /**
     * Freezes what is on screen as the next animation's starting point. <b>Every mutation
     * that moves a mark calls this before it changes anything</b>: the snapshot reads the
     * current values through the current animation, so taken afterwards it would capture
     * the destination and the change would jump instead of animating.
     *
     * <p>Idempotent within one batch: after the first call the animation restarts at
     * progress 0, where the drawn value <em>is</em> the snapshot, so a second call
     * re-records the same numbers.
     */
    void beginDataChange() {
        int count = categoryCount();
        float p = progress();
        double baseline = animationBaseline();
        for (ChartSeries s : series) {
            s.snapshot(count, p, baseline);
        }
    }

    /** Closes a data change: drops the derived caches and animates to the new numbers. */
    void endDataChange() {
        legendCache = null;
        dataGeneration++;
        // A hover survives its own series being re-valued (a live chart must not blink its
        // tooltip once a second) but not the series list changing underneath it, where the
        // index it holds would name someone else's data, or nobody's.
        if (hovered != null && (hovered.seriesIndex() >= series.size()
                || series.get(hovered.seriesIndex()) != hovered.series())) {
            hovered = null;
            tooltipFade.snap(0);
        }
        restartAnimation();
        invalidate();
    }

    /** A series changed something that only affects how it is drawn, not what it draws. */
    void seriesRestyled() {
        legendCache = null;
        invalidate();
    }

    /** A series was hidden or shown, from its own setter or from the legend. */
    void seriesVisibilityChanged(ChartSeries changed) {
        if (changed.fade != null) {
            // Re-read at every toggle rather than at construction: setAnimationDuration(0)
            // means "no animation", and a fade armed before that call would ignore it.
            changed.fade.duration(stateFadeSeconds());
            changed.fade.to(changed.isVisible() ? 1 : 0);
        }
        endDataChange();
    }

    /**
     * How long a state fade lasts (hiding a series, popping a hovered mark), in seconds.
     * Short, because it is feedback rather than data movement, and {@code 0} whenever
     * {@link #setAnimationDuration(double)} turned animation off, so "off" means all of it.
     */
    protected final double stateFadeSeconds() {
        return animationSeconds <= 0 ? 0 : Theme.current().animFade;
    }

    /**
     * An axis setting changed. Bumps the generation rather than only repainting: the
     * resolved scale is cached against it, and a pinned bound that did not invalidate that
     * cache would be a setter that silently does nothing.
     */
    void axisChanged() {
        dataGeneration++;
        invalidate();
    }

    private void restartAnimation() {
        if (animationSeconds <= 0 || scene() == null) {
            anim.snap(1);
            return;
        }
        anim.duration(animationSeconds).easing(animationEasing);
        anim.snap(0);
        anim.to(1);
    }
}
