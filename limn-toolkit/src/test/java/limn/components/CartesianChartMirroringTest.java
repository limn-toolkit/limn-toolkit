package limn.components;

import limn.components.chart.BarChart;
import limn.components.chart.Chart;
import limn.components.chart.ChartPoint;
import limn.components.chart.ChartSeries;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * A cartesian chart read right to left: which end of the plot the value scale starts at, which
 * band category zero occupies, which side the axis labels are drawn on, and whether the pointer
 * still names the band painted under it.
 *
 * <p>Every expectation is arithmetic against the plot rectangle the chart resolved and against
 * {@link #RULER}'s 10pt clusters, never a picture. A mirrored chart is a layout that is inside
 * out, and the failures that matter here — a band reflected as a point, a hit test that is no
 * longer the inverse of the paint — are off by one band, which a screenshot of forty bars shows
 * as nothing at all.
 *
 * <p>The value axis is pinned so the two ends of the scale are numbers this file knows: the
 * mirror is a claim about where {@code 0} and {@code 100} land, and resolving them from the data
 * would make it a claim about the tick algorithm as well.
 */
class CartesianChartMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float WIDTH = 400;
    private static final float HEIGHT = 300;
    private static final int CATEGORIES = 4;
    private static final double LOW = 0;
    private static final double HIGH = 100;

    private final AtomicLong clock = new AtomicLong();
    private RecordingCanvas canvas;
    private Scene scene;

    /**
     * Reaches the geometry a subclass draws and hit-tests through. Both mirrors live behind these
     * four methods, which is the whole reason {@link BarChart} and {@code LineChart} do not each
     * need one of their own.
     */
    /**
     * The geometry a chart computed, read through CartesianChart's protected accessors by
     * reflection: BarChart is final (ADR 046 §2), so the probe that used to subclass it cannot.
     */
    private static Object call(BarChart chart, String name, Class<?>[] types, Object... args) {
        for (Class<?> c = chart.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(chart, args);
            } catch (NoSuchMethodException next) {
                // declared higher up
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(name, e);
            }
        }
        throw new AssertionError("no method " + name);
    }

    private static float f(BarChart chart, String name) {
        return (Float) call(chart, name, new Class<?>[0]);
    }

    private static float left(BarChart c) { return f(c, "plotX"); }
    private static float top(BarChart c) { return f(c, "plotY"); }
    private static float span(BarChart c) { return f(c, "plotWidth"); }
    private static float tall(BarChart c) { return f(c, "plotHeight"); }
    private static float band(BarChart c) { return f(c, "bandSize"); }
    private static float contentX(BarChart c) { return f(c, "contentLeft"); }
    private static float contentSpan(BarChart c) { return f(c, "contentBoxWidth"); }

    private static float bandLeft(BarChart c, int index) {
        return (Float) call(c, "bandStart", new Class<?>[] {int.class}, index);
    }

    private static float bandMiddle(BarChart c, int index) {
        return (Float) call(c, "bandCenter", new Class<?>[] {int.class}, index);
    }

    private static float valueAt(BarChart c, double value) {
        return (Float) call(c, "valuePosition", new Class<?>[] {double.class}, value);
    }

    private static int categoryUnder(BarChart c, float x, float y) {
        return (Integer) call(c, "categoryAt", new Class<?>[] {float.class, float.class}, x, y);
    }

    /** Records the ink: label positions, grid lines, and the frame a rotated title is drawn in. */
    private static final class RecordingCanvas extends FakeCanvas {

        record Drawn(String text, float x, float y) {
        }

        final List<Drawn> texts = new ArrayList<>();
        final List<float[]> lines = new ArrayList<>();
        final List<Float> rotatedAt = new ArrayList<>();
        private float lastTranslateX;

        RecordingCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(new Drawn(text, x, y));
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth,
                Paint paint) {
            lines.add(new float[] { x1, y1, x2, y2 });
        }

        @Override
        public void translate(float dx, float dy) {
            lastTranslateX = dx;
        }

        @Override
        public void rotate(float angleRadians) {
            // The x the rotated frame was pinned at, which is the only part of a rotated axis
            // title this axis moves.
            rotatedAt.add(lastTranslateX);
        }

        /** The vertical lines, left to right: the category boundaries of an upright chart. */
        List<Float> verticalXs() {
            List<Float> xs = new ArrayList<>();
            for (float[] line : lines) {
                if (Math.abs(line[0] - line[2]) < EPS) {
                    xs.add(line[0]);
                }
            }
            xs.sort(Float::compare);
            return xs;
        }
    }

    private BarChart build(LayoutDirection direction, boolean horizontal) {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLegendPosition(Chart.LegendPosition.NONE);
        chart.setLabels("q1", "q2", "q3", "q4");
        chart.addSeries(ChartSeries.of("v", 10, 20, 30, 40));
        chart.valueAxis().setMin(LOW).setMax(HIGH);
        chart.setHorizontal(horizontal);
        chart.setLayoutDirection(direction);
        canvas = new RecordingCanvas(WIDTH, HEIGHT);
        scene = new Scene(chart, clock::get);
        scene.setTextRuler(RULER);
        scene.renderFrame(canvas);
        return chart;
    }

    /** Values along the vertical axis, categories along the bottom: the ordinary chart. */
    private BarChart upright(LayoutDirection direction) {
        return build(direction, false);
    }

    /** Turned on its side: the value axis is the horizontal one, which is the reading axis. */
    private BarChart sideways(LayoutDirection direction) {
        return build(direction, true);
    }

    private static float gap(BarChart chart) {
        return Theme.current().tokensFor(chart).spacingSmall();
    }

    // ------------------------------------------------------------ the value axis

    @Test
    void aSidewaysValueAxisStartsAtTheEdgeReadingStartsFrom() {
        BarChart chart = sideways(LayoutDirection.RTL);
        assertEquals(left(chart) + span(chart), valueAt(chart, LOW), EPS,
                "the low end of the scale is on the right");
        assertEquals(left(chart), valueAt(chart, HIGH), EPS, "and the high end is on the left");
        assertEquals(left(chart) + span(chart) / 2, valueAt(chart, (LOW + HIGH) / 2), EPS,
                "with the scale still linear between them");
    }

    @Test
    void aSidewaysValueAxisIsUnchangedReadingLeftToRight() {
        BarChart chart = sideways(LayoutDirection.LTR);
        assertEquals(left(chart), valueAt(chart, LOW), EPS);
        assertEquals(left(chart) + span(chart), valueAt(chart, HIGH), EPS);
    }

    @Test
    void anUprightValueAxisDoesNotMirror() {
        // A vertical axis is not a reading axis. The two directions must agree to the point, or
        // a later sweep has mirrored the branch of valuePosition that has no business moving.
        BarChart ltr = upright(LayoutDirection.LTR);
        BarChart rtl = upright(LayoutDirection.RTL);
        assertEquals(top(ltr) + tall(ltr), valueAt(ltr, LOW), EPS);
        assertEquals(top(ltr), valueAt(ltr, HIGH), EPS);
        assertEquals(valueAt(ltr, LOW), valueAt(rtl, LOW), EPS, "the low end did not move");
        assertEquals(valueAt(ltr, HIGH), valueAt(rtl, HIGH), EPS, "and neither did the high one");
    }

    // --------------------------------------------------------- the plot rectangle

    @Test
    void theAxisGutterMovesToTheOtherSideAndThePlotKeepsItsWidth() {
        BarChart ltr = upright(LayoutDirection.LTR);
        BarChart rtl = upright(LayoutDirection.RTL);
        assertEquals(span(ltr), span(rtl), EPS,
                "the plot is the same width either way: the insets are a sum");

        // An upright chart reserves its gutter on one side only, so the plot is flush against
        // the content box at the other end, and which end that is is the whole of the mirror.
        assertEquals(contentX(ltr) + contentSpan(ltr), left(ltr) + span(ltr), EPS,
                "reading left to right the gutter is on the left");
        assertEquals(contentX(rtl), left(rtl), EPS,
                "and reading right to left there is nothing on the left at all");
        assertTrue(left(ltr) > contentX(ltr) + EPS, "the gutter is not empty in this fixture");
    }

    // ------------------------------------------------------------ the category bands

    @Test
    void categoryZeroTakesTheBandAtTheEdgeReadingStartsFrom() {
        BarChart chart = upright(LayoutDirection.RTL);
        float band = span(chart) / CATEGORIES;
        assertEquals(band, band(chart), EPS);
        assertEquals(left(chart) + span(chart) - band, bandLeft(chart, 0), EPS,
                "category zero owns the rightmost band");
        assertEquals(left(chart), bandLeft(chart, CATEGORIES - 1), EPS,
                "and the last category finishes against the plot's left edge");
    }

    @Test
    void categoryZeroIsUnchangedReadingLeftToRight() {
        BarChart chart = upright(LayoutDirection.LTR);
        assertEquals(left(chart), bandLeft(chart, 0), EPS);
        assertEquals(left(chart) + span(chart) - band(chart), bandLeft(chart, CATEGORIES - 1),
                EPS);
    }

    @Test
    void everyBandIsOneBandWideAndInsideThePlot() {
        // What separates mirroring the band from mirroring the point: reflecting the point puts
        // category zero's left edge on the plot's right edge, and its whole box outside the plot.
        // Both forms pass the two assertions above; only one of them passes this.
        BarChart chart = upright(LayoutDirection.RTL);
        for (int i = 0; i < CATEGORIES; i++) {
            assertTrue(bandLeft(chart, i) >= left(chart) - EPS,
                    "band " + i + " starts at " + bandLeft(chart, i) + ", left of the plot");
            assertTrue(bandLeft(chart, i) + band(chart) <= left(chart) + span(chart) + EPS,
                    "band " + i + " ends at " + (bandLeft(chart, i) + band(chart))
                            + ", right of the plot");
        }
    }

    @Test
    void aBandCentreIsTheMiddleOfItsOwnBandInBothDirections() {
        // A centre does not move: once the band is on the right side of the plot, its middle is
        // already correct, and a second mirror here would walk every mark into its neighbour.
        for (LayoutDirection direction : LayoutDirection.values()) {
            BarChart chart = upright(direction);
            for (int i = 0; i < CATEGORIES; i++) {
                assertEquals(bandLeft(chart, i) + band(chart) / 2, bandMiddle(chart, i), EPS,
                        "band " + i + " reading " + direction);
            }
        }
    }

    @Test
    void theCategoryAxisOfASidewaysChartDoesNotMirror() {
        // Turned sideways the categories run down the plot, and a vertical sequence is not on
        // the reading axis. Only one branch of bandStart is a site, and this is the other one.
        BarChart ltr = sideways(LayoutDirection.LTR);
        BarChart rtl = sideways(LayoutDirection.RTL);
        assertEquals(top(ltr), top(rtl), EPS);
        assertEquals(tall(ltr), tall(rtl), EPS);
        for (int i = 0; i < CATEGORIES; i++) {
            assertEquals(bandLeft(ltr, i), bandLeft(rtl, i), EPS, "band " + i + " did not move");
        }
    }

    // ------------------------------------------------------------------ the grid

    @Test
    void theCategoryGridStillDrawsOneLinePerBoundaryFromEdgeToEdge() {
        // The boundary set is the same set read either way, and there are one more of them than
        // there are bands. A grid drawn from band origins loses the far edge in one direction and
        // draws a line a whole band outside the plot in the other.
        for (LayoutDirection direction : LayoutDirection.values()) {
            BarChart chart = new BarChart();
            chart.setAnimationDuration(0);
            chart.setLegendPosition(Chart.LegendPosition.NONE);
            chart.setLabels("q1", "q2", "q3", "q4");
            chart.addSeries(ChartSeries.of("v", 10, 20, 30, 40));
            chart.valueAxis().setMin(LOW).setMax(HIGH).setGrid(false);
            chart.categoryAxis().setGrid(true);
            chart.setLayoutDirection(direction);
            RecordingCanvas recorder = new RecordingCanvas(WIDTH, HEIGHT);
            Scene s = new Scene(chart, clock::get);
            s.setTextRuler(RULER);
            s.renderFrame(recorder);

            List<Float> xs = recorder.verticalXs();
            assertEquals(CATEGORIES + 1, xs.size(),
                    "one boundary per band plus the far edge, reading " + direction);
            assertEquals(left(chart), xs.get(0), EPS, "the first is the plot's left edge");
            assertEquals(left(chart) + span(chart), xs.get(xs.size() - 1), EPS,
                    "and the last is its right edge");
            for (int i = 0; i <= CATEGORIES; i++) {
                assertEquals(left(chart) + i * band(chart), xs.get(i), EPS,
                        "boundary " + i + " reading " + direction);
            }
        }
    }

    // ---------------------------------------------------------------- the labels

    @Test
    void theValueTickLabelsSitInTheGutterOnTheSideReadingStartsFrom() {
        BarChart rtl = labelsOnly(LayoutDirection.RTL);
        float gap = gap(rtl);
        assertFalse(canvas.texts.isEmpty(), "the tick labels are drawn");
        for (RecordingCanvas.Drawn drawn : canvas.texts) {
            assertEquals(left(rtl) + span(rtl) + gap, drawn.x(), EPS,
                    "'" + drawn.text() + "' starts against the plot's right edge");
        }

        BarChart ltr = labelsOnly(LayoutDirection.LTR);
        assertFalse(canvas.texts.isEmpty());
        for (RecordingCanvas.Drawn drawn : canvas.texts) {
            assertEquals(left(ltr) - gap - width(drawn.text()), drawn.x(), EPS,
                    "'" + drawn.text() + "' finishes against the plot's left edge");
        }
    }

    @Test
    void theUprightCategoryLabelsStayCentredOnTheirBands() {
        // A centred label is not a site. It follows its band, and its band has already moved.
        BarChart chart = upright(LayoutDirection.RTL);
        for (int i = 0; i < CATEGORIES; i++) {
            String label = chart.label(i);
            RecordingCanvas.Drawn drawn = drawnWithText(label);
            assertNotNull(drawn, "'" + label + "' is drawn");
            assertEquals(bandMiddle(chart, i) - width(label) / 2, drawn.x(), EPS,
                    "'" + label + "' is centred on band " + i);
        }
    }

    @Test
    void aRotatedAxisTitleParksAgainstTheEdgeReadingStartsFrom() {
        // The ink runs one ascent before the baseline and one descent after it, so the mirrored
        // form measures the descent in from the far edge: it is the title's box that reflects,
        // not its baseline. Ascent 8 and descent 2 come from the ruler.
        BarChart ltr = titled(LayoutDirection.LTR);
        assertEquals(1, canvas.rotatedAt.size(), "one rotated title");
        assertEquals(gap(ltr) + 8, canvas.rotatedAt.get(0), EPS);

        BarChart rtl = titled(LayoutDirection.RTL);
        assertEquals(1, canvas.rotatedAt.size());
        assertEquals(rtl.width() - gap(rtl) - 2, canvas.rotatedAt.get(0), EPS);
    }

    @Test
    void aCentredAxisTitleDoesNotMoveAtAll() {
        // The other title of the same pair: centred under the plot, and a centre is the one x
        // that reads the same in both directions.
        for (LayoutDirection direction : LayoutDirection.values()) {
            BarChart chart = new BarChart();
            chart.setAnimationDuration(0);
            chart.setLegendPosition(Chart.LegendPosition.NONE);
            chart.setLabels("q1", "q2", "q3", "q4");
            chart.addSeries(ChartSeries.of("v", 10, 20, 30, 40));
            chart.valueAxis().setMin(LOW).setMax(HIGH).setVisible(false);
            chart.categoryAxis().setVisible(false).setTitle("months");
            chart.setLayoutDirection(direction);
            canvas = new RecordingCanvas(WIDTH, HEIGHT);
            scene = new Scene(chart, clock::get);
            scene.setTextRuler(RULER);
            scene.renderFrame(canvas);

            RecordingCanvas.Drawn drawn = drawnWithText("months");
            assertNotNull(drawn, "the title is drawn reading " + direction);
            assertEquals(left(chart) + span(chart) / 2 - width("months") / 2, drawn.x(), EPS,
                    "centred under the plot reading " + direction);
        }
    }

    // -------------------------------------------------------------- the hit test

    @Test
    void theHitTestNamesTheBandPaintedUnderThePointer() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            BarChart chart = upright(direction);
            for (int i = 0; i < CATEGORIES; i++) {
                assertEquals(i, categoryUnder(chart, bandMiddle(chart, i), top(chart) + 1),
                        "the middle of band " + i + " reading " + direction);
            }
        }
    }

    @Test
    void thePointerPathResolvesTheSameDirectionThePaintDid() {
        // The two paths resolve independently, and a direction resolved on one and not the other
        // reports the bar mirrored about the middle of the plot: the tooltip names the far end of
        // the chart from the one the pointer is over.
        BarChart chart = upright(LayoutDirection.RTL);
        float inside = left(chart) + span(chart) - band(chart) / 2;
        drive(scene).mouseMoved(inside, top(chart) + tall(chart) / 2);
        drive(scene).inputBatchEnded();

        ChartPoint point = chart.hoveredPoint();
        assertNotNull(point, "a pointer inside the plot must report a datum");
        assertEquals(0, point.index(), "the rightmost band is the first category");
        assertEquals("q1", point.label());
    }

    // ------------------------------------------------- the held gutter's cache key

    @Test
    void theCachedGutterIsMeasuredAgainWhenTheDirectionChanges() {
        // Both scans are keyed by hand, and neither the data generation nor the language epoch
        // moves when the direction does. Counted rather than measured, for the reason the bidi
        // width tests give: the fake ruler is direction-blind, so the width itself does not move
        // here and only the missing re-scan can be seen.
        int categories = 200;
        List<String> labels = new ArrayList<>(categories);
        double[] values = new double[categories];
        for (int i = 0; i < categories; i++) {
            labels.add("c" + i);
            values[i] = i % 50;
        }
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLegendPosition(Chart.LegendPosition.NONE);
        chart.setLabels(labels);
        chart.addSeries(ChartSeries.of("v", values));

        CountingRuler ruler = new CountingRuler();
        Scene s = new Scene(chart, clock::get);
        s.setTextRuler(ruler);
        FakeCanvas plain = new FakeCanvas(WIDTH, HEIGHT);
        s.renderFrame(plain); // warm: the first frame is allowed to resolve everything

        ruler.calls.set(0);
        s.renderFrame(plain);
        int steady = ruler.calls.get();
        assertTrue(steady < categories,
                "a frame that changed nothing measured " + steady + " strings");

        ruler.calls.set(0);
        chart.setLayoutDirection(LayoutDirection.RTL);
        s.renderFrame(plain);
        assertTrue(ruler.calls.get() > categories,
                "the direction changed and the gutter was not measured again: "
                        + ruler.calls.get() + " measurements against " + steady
                        + " for a frame that changed nothing");
    }

    /** {@link #RULER}, counting, as {@code ChartLayoutCostTest}'s does. */
    private static final class CountingRuler implements TextRuler {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public TextMetrics measure(String text, Font font) {
            calls.incrementAndGet();
            return RULER.measure(text, font);
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** An upright chart with only the value axis visible, so the ticks are the only text. */
    private BarChart labelsOnly(LayoutDirection direction) {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLegendPosition(Chart.LegendPosition.NONE);
        chart.setLabels("q1", "q2", "q3", "q4");
        chart.addSeries(ChartSeries.of("v", 10, 20, 30, 40));
        chart.valueAxis().setMin(LOW).setMax(HIGH);
        chart.categoryAxis().setVisible(false);
        chart.setLayoutDirection(direction);
        canvas = new RecordingCanvas(WIDTH, HEIGHT);
        scene = new Scene(chart, clock::get);
        scene.setTextRuler(RULER);
        scene.renderFrame(canvas);
        return chart;
    }

    /** An upright chart whose value-axis title is the rotated one. */
    private BarChart titled(LayoutDirection direction) {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLegendPosition(Chart.LegendPosition.NONE);
        chart.setLabels("q1", "q2", "q3", "q4");
        chart.addSeries(ChartSeries.of("v", 10, 20, 30, 40));
        chart.valueAxis().setMin(LOW).setMax(HIGH).setTitle("revenue");
        chart.setLayoutDirection(direction);
        canvas = new RecordingCanvas(WIDTH, HEIGHT);
        scene = new Scene(chart, clock::get);
        scene.setTextRuler(RULER);
        scene.renderFrame(canvas);
        return chart;
    }

    /** What {@link #RULER} makes of a string: 10pt per code point. */
    private static float width(String text) {
        return 10f * (int) text.codePoints().count();
    }

    private RecordingCanvas.Drawn drawnWithText(String text) {
        for (RecordingCanvas.Drawn drawn : canvas.texts) {
            if (drawn.text().equals(text)) {
                return drawn;
            }
        }
        return null;
    }
}
