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
    private static final class Probe extends BarChart {

        float left() {
            return plotX();
        }

        float top() {
            return plotY();
        }

        float span() {
            return plotWidth();
        }

        float tall() {
            return plotHeight();
        }

        float band() {
            return bandSize();
        }

        float bandLeft(int index) {
            return bandStart(index);
        }

        float bandMiddle(int index) {
            return bandCenter(index);
        }

        float valueAt(double value) {
            return valuePosition(value);
        }

        int categoryUnder(float x, float y) {
            return categoryAt(x, y);
        }

        float contentX() {
            return contentLeft();
        }

        float contentSpan() {
            return contentBoxWidth();
        }
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

    private Probe build(LayoutDirection direction, boolean horizontal) {
        Probe chart = new Probe();
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
    private Probe upright(LayoutDirection direction) {
        return build(direction, false);
    }

    /** Turned on its side: the value axis is the horizontal one, which is the reading axis. */
    private Probe sideways(LayoutDirection direction) {
        return build(direction, true);
    }

    private static float gap(Probe chart) {
        return Theme.current().tokensFor(chart).spacingSmall();
    }

    // ------------------------------------------------------------ the value axis

    @Test
    void aSidewaysValueAxisStartsAtTheEdgeReadingStartsFrom() {
        Probe chart = sideways(LayoutDirection.RTL);
        assertEquals(chart.left() + chart.span(), chart.valueAt(LOW), EPS,
                "the low end of the scale is on the right");
        assertEquals(chart.left(), chart.valueAt(HIGH), EPS, "and the high end is on the left");
        assertEquals(chart.left() + chart.span() / 2, chart.valueAt((LOW + HIGH) / 2), EPS,
                "with the scale still linear between them");
    }

    @Test
    void aSidewaysValueAxisIsUnchangedReadingLeftToRight() {
        Probe chart = sideways(LayoutDirection.LTR);
        assertEquals(chart.left(), chart.valueAt(LOW), EPS);
        assertEquals(chart.left() + chart.span(), chart.valueAt(HIGH), EPS);
    }

    @Test
    void anUprightValueAxisDoesNotMirror() {
        // A vertical axis is not a reading axis. The two directions must agree to the point, or
        // a later sweep has mirrored the branch of valuePosition that has no business moving.
        Probe ltr = upright(LayoutDirection.LTR);
        Probe rtl = upright(LayoutDirection.RTL);
        assertEquals(ltr.top() + ltr.tall(), ltr.valueAt(LOW), EPS);
        assertEquals(ltr.top(), ltr.valueAt(HIGH), EPS);
        assertEquals(ltr.valueAt(LOW), rtl.valueAt(LOW), EPS, "the low end did not move");
        assertEquals(ltr.valueAt(HIGH), rtl.valueAt(HIGH), EPS, "and neither did the high one");
    }

    // --------------------------------------------------------- the plot rectangle

    @Test
    void theAxisGutterMovesToTheOtherSideAndThePlotKeepsItsWidth() {
        Probe ltr = upright(LayoutDirection.LTR);
        Probe rtl = upright(LayoutDirection.RTL);
        assertEquals(ltr.span(), rtl.span(), EPS,
                "the plot is the same width either way: the insets are a sum");

        // An upright chart reserves its gutter on one side only, so the plot is flush against
        // the content box at the other end, and which end that is is the whole of the mirror.
        assertEquals(ltr.contentX() + ltr.contentSpan(), ltr.left() + ltr.span(), EPS,
                "reading left to right the gutter is on the left");
        assertEquals(rtl.contentX(), rtl.left(), EPS,
                "and reading right to left there is nothing on the left at all");
        assertTrue(ltr.left() > ltr.contentX() + EPS, "the gutter is not empty in this fixture");
    }

    // ------------------------------------------------------------ the category bands

    @Test
    void categoryZeroTakesTheBandAtTheEdgeReadingStartsFrom() {
        Probe chart = upright(LayoutDirection.RTL);
        float band = chart.span() / CATEGORIES;
        assertEquals(band, chart.band(), EPS);
        assertEquals(chart.left() + chart.span() - band, chart.bandLeft(0), EPS,
                "category zero owns the rightmost band");
        assertEquals(chart.left(), chart.bandLeft(CATEGORIES - 1), EPS,
                "and the last category finishes against the plot's left edge");
    }

    @Test
    void categoryZeroIsUnchangedReadingLeftToRight() {
        Probe chart = upright(LayoutDirection.LTR);
        assertEquals(chart.left(), chart.bandLeft(0), EPS);
        assertEquals(chart.left() + chart.span() - chart.band(), chart.bandLeft(CATEGORIES - 1),
                EPS);
    }

    @Test
    void everyBandIsOneBandWideAndInsideThePlot() {
        // What separates mirroring the band from mirroring the point: reflecting the point puts
        // category zero's left edge on the plot's right edge, and its whole box outside the plot.
        // Both forms pass the two assertions above; only one of them passes this.
        Probe chart = upright(LayoutDirection.RTL);
        for (int i = 0; i < CATEGORIES; i++) {
            assertTrue(chart.bandLeft(i) >= chart.left() - EPS,
                    "band " + i + " starts at " + chart.bandLeft(i) + ", left of the plot");
            assertTrue(chart.bandLeft(i) + chart.band() <= chart.left() + chart.span() + EPS,
                    "band " + i + " ends at " + (chart.bandLeft(i) + chart.band())
                            + ", right of the plot");
        }
    }

    @Test
    void aBandCentreIsTheMiddleOfItsOwnBandInBothDirections() {
        // A centre does not move: once the band is on the right side of the plot, its middle is
        // already correct, and a second mirror here would walk every mark into its neighbour.
        for (LayoutDirection direction : LayoutDirection.values()) {
            Probe chart = upright(direction);
            for (int i = 0; i < CATEGORIES; i++) {
                assertEquals(chart.bandLeft(i) + chart.band() / 2, chart.bandMiddle(i), EPS,
                        "band " + i + " reading " + direction);
            }
        }
    }

    @Test
    void theCategoryAxisOfASidewaysChartDoesNotMirror() {
        // Turned sideways the categories run down the plot, and a vertical sequence is not on
        // the reading axis. Only one branch of bandStart is a site, and this is the other one.
        Probe ltr = sideways(LayoutDirection.LTR);
        Probe rtl = sideways(LayoutDirection.RTL);
        assertEquals(ltr.top(), rtl.top(), EPS);
        assertEquals(ltr.tall(), rtl.tall(), EPS);
        for (int i = 0; i < CATEGORIES; i++) {
            assertEquals(ltr.bandLeft(i), rtl.bandLeft(i), EPS, "band " + i + " did not move");
        }
    }

    // ------------------------------------------------------------------ the grid

    @Test
    void theCategoryGridStillDrawsOneLinePerBoundaryFromEdgeToEdge() {
        // The boundary set is the same set read either way, and there are one more of them than
        // there are bands. A grid drawn from band origins loses the far edge in one direction and
        // draws a line a whole band outside the plot in the other.
        for (LayoutDirection direction : LayoutDirection.values()) {
            Probe chart = new Probe();
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
            assertEquals(chart.left(), xs.get(0), EPS, "the first is the plot's left edge");
            assertEquals(chart.left() + chart.span(), xs.get(xs.size() - 1), EPS,
                    "and the last is its right edge");
            for (int i = 0; i <= CATEGORIES; i++) {
                assertEquals(chart.left() + i * chart.band(), xs.get(i), EPS,
                        "boundary " + i + " reading " + direction);
            }
        }
    }

    // ---------------------------------------------------------------- the labels

    @Test
    void theValueTickLabelsSitInTheGutterOnTheSideReadingStartsFrom() {
        Probe rtl = labelsOnly(LayoutDirection.RTL);
        float gap = gap(rtl);
        assertFalse(canvas.texts.isEmpty(), "the tick labels are drawn");
        for (RecordingCanvas.Drawn drawn : canvas.texts) {
            assertEquals(rtl.left() + rtl.span() + gap, drawn.x(), EPS,
                    "'" + drawn.text() + "' starts against the plot's right edge");
        }

        Probe ltr = labelsOnly(LayoutDirection.LTR);
        assertFalse(canvas.texts.isEmpty());
        for (RecordingCanvas.Drawn drawn : canvas.texts) {
            assertEquals(ltr.left() - gap - width(drawn.text()), drawn.x(), EPS,
                    "'" + drawn.text() + "' finishes against the plot's left edge");
        }
    }

    @Test
    void theUprightCategoryLabelsStayCentredOnTheirBands() {
        // A centred label is not a site. It follows its band, and its band has already moved.
        Probe chart = upright(LayoutDirection.RTL);
        for (int i = 0; i < CATEGORIES; i++) {
            String label = chart.label(i);
            RecordingCanvas.Drawn drawn = drawnWithText(label);
            assertNotNull(drawn, "'" + label + "' is drawn");
            assertEquals(chart.bandMiddle(i) - width(label) / 2, drawn.x(), EPS,
                    "'" + label + "' is centred on band " + i);
        }
    }

    @Test
    void aRotatedAxisTitleParksAgainstTheEdgeReadingStartsFrom() {
        // The ink runs one ascent before the baseline and one descent after it, so the mirrored
        // form measures the descent in from the far edge: it is the title's box that reflects,
        // not its baseline. Ascent 8 and descent 2 come from the ruler.
        Probe ltr = titled(LayoutDirection.LTR);
        assertEquals(1, canvas.rotatedAt.size(), "one rotated title");
        assertEquals(gap(ltr) + 8, canvas.rotatedAt.get(0), EPS);

        Probe rtl = titled(LayoutDirection.RTL);
        assertEquals(1, canvas.rotatedAt.size());
        assertEquals(rtl.width() - gap(rtl) - 2, canvas.rotatedAt.get(0), EPS);
    }

    @Test
    void aCentredAxisTitleDoesNotMoveAtAll() {
        // The other title of the same pair: centred under the plot, and a centre is the one x
        // that reads the same in both directions.
        for (LayoutDirection direction : LayoutDirection.values()) {
            Probe chart = new Probe();
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
            assertEquals(chart.left() + chart.span() / 2 - width("months") / 2, drawn.x(), EPS,
                    "centred under the plot reading " + direction);
        }
    }

    // -------------------------------------------------------------- the hit test

    @Test
    void theHitTestNamesTheBandPaintedUnderThePointer() {
        for (LayoutDirection direction : LayoutDirection.values()) {
            Probe chart = upright(direction);
            for (int i = 0; i < CATEGORIES; i++) {
                assertEquals(i, chart.categoryUnder(chart.bandMiddle(i), chart.top() + 1),
                        "the middle of band " + i + " reading " + direction);
            }
        }
    }

    @Test
    void thePointerPathResolvesTheSameDirectionThePaintDid() {
        // The two paths resolve independently, and a direction resolved on one and not the other
        // reports the bar mirrored about the middle of the plot: the tooltip names the far end of
        // the chart from the one the pointer is over.
        Probe chart = upright(LayoutDirection.RTL);
        float inside = chart.left() + chart.span() - chart.band() / 2;
        scene.mouseMoved(inside, chart.top() + chart.tall() / 2);
        scene.inputBatchEnded();

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
        Probe chart = new Probe();
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
    private Probe labelsOnly(LayoutDirection direction) {
        Probe chart = new Probe();
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
    private Probe titled(LayoutDirection direction) {
        Probe chart = new Probe();
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
