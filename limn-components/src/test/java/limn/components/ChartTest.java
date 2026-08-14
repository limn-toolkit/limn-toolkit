package limn.components;

import limn.components.chart.BarChart;
import limn.components.chart.Chart;
import limn.components.chart.ChartPoint;
import limn.components.chart.ChartSeries;
import limn.components.chart.DonutChart;
import limn.components.chart.LineChart;
import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.RoundRect;
import limn.input.Keys;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a chart draws, what it reports, and what it animates. Geometry cases run with the
 * animation off, which is also what a detached chart does, so a single frame shows final
 * values; the animation itself is pinned separately by driving a scene clock.
 *
 * <p>Assertions are on <em>relationships</em> (this bar is half that one, this segment
 * stands on that one) rather than on absolute coordinates, because the gutters move with
 * the tick labels and a pixel-exact expectation would pin the ruler rather than the chart.
 */
class ChartTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong();

    /** Records the marks: filled round rects (bars), paths (areas, slices), text (labels). */
    private static final class RecordingCanvas extends FakeCanvas {
        final List<float[]> rects = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        int paths;
        int strokedPaths;
        int circles;

        RecordingCanvas(float width, float height) {
            super(width, height);
        }

        final List<Paint> roundRectPaints = new ArrayList<>();

        @Override
        public void fillRoundRect(RoundRect r, Paint paint) {
            rects.add(new float[] { r.x(), r.y(), r.width(), r.height() });
            roundRectPaints.add(paint);
        }

        @Override
        public void drawText(String text, float x, float y, limn.graphics.Font font, Paint paint) {
            texts.add(text);
        }

        @Override
        public void fillPath(Path2D path, Paint paint) {
            paths++;
        }

        @Override
        public void drawPath(Path2D path, float strokeWidth, Paint paint) {
            strokedPaths++;
        }

        @Override
        public void fillCircle(float cx, float cy, float radius, Paint paint) {
            circles++;
        }

        void reset() {
            rects.clear();
            roundRectPaints.clear();
            texts.clear();
            paths = 0;
            strokedPaths = 0;
            circles = 0;
        }
    }

    private Scene sceneOf(Chart chart, RecordingCanvas canvas) {
        Scene scene = new Scene(chart, clock::get);
        scene.setTextRuler(RULER);
        scene.renderFrame(canvas);
        return scene;
    }

    private static float bottom(float[] rect) {
        return rect[1] + rect[3];
    }

    // ------------------------------------------------------------------ scale

    @Test
    void theScaleRoundsOutwardToNumbersAHumanWouldHaveChosen() {
        // 37 must not put a tick at 37: the whole point of the nice-number pass is that a
        // reader can interpolate between labels without doing arithmetic.
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b", "c");
        chart.addSeries(ChartSeries.of("v", 3, 17, 37));
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        sceneOf(chart, canvas);

        assertTrue(canvas.texts.containsAll(List.of("0", "10", "20", "30", "40")),
                "ticks should be 0..40 by 10, but were " + canvas.texts);
    }

    @Test
    void aPinnedEndStopsTheScaleFromFollowingTheData() {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a");
        chart.addSeries(ChartSeries.of("v", 37));
        chart.valueAxis().setMax(100.0);
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        sceneOf(chart, canvas);

        assertTrue(canvas.texts.contains("100"), "a pinned max must be drawn: " + canvas.texts);
        assertTrue(canvas.texts.contains("60"),
                "and the scale must span to it, not stop at the data: " + canvas.texts);
    }

    // ------------------------------------------------------------------- bars

    @Test
    void barLengthIsTheValueAndEveryBarStandsOnTheSameBaseline() {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b", "c");
        chart.addSeries(ChartSeries.of("v", 10, 20, 40));
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        sceneOf(chart, canvas);

        assertEquals(3, canvas.rects.size(), "one bar per category");
        float[] ten = canvas.rects.get(0);
        float[] twenty = canvas.rects.get(1);
        float[] forty = canvas.rects.get(2);
        assertEquals(forty[3] / 4, ten[3], 0.01f, "10 must be a quarter of 40");
        assertEquals(forty[3] / 2, twenty[3], 0.01f, "20 must be half of 40");
        assertEquals(bottom(forty), bottom(ten), 0.01f, "bars stand on one baseline");
        assertEquals(bottom(forty), bottom(twenty), 0.01f, "bars stand on one baseline");
    }

    @Test
    void aStackedSegmentStandsOnTheOneBelowItWithASurfaceGapBetween() {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a");
        chart.addSeries(ChartSeries.of("lower", 10));
        chart.addSeries(ChartSeries.of("upper", 10));
        chart.setStacked(true);
        chart.setLegendPosition(Chart.LegendPosition.NONE); // every recorded rect is a mark
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        sceneOf(chart, canvas);

        assertEquals(2, canvas.rects.size(), "one segment per series");
        float[] lower = canvas.rects.get(0);
        float[] upper = canvas.rects.get(1);
        assertEquals(lower[0], upper[0], 0.01f, "a stack is one column");
        assertEquals(lower[1] - 2, bottom(upper), 0.01f,
                "the upper segment ends 2pt of surface above the lower one's top");
        assertTrue(upper[1] < lower[1], "and sits above it");
    }

    @Test
    void groupedSeriesStandSideBySideInsideOneCategory() {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a");
        chart.addSeries(ChartSeries.of("left", 10));
        chart.addSeries(ChartSeries.of("right", 10));
        chart.setLegendPosition(Chart.LegendPosition.NONE);
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        sceneOf(chart, canvas);

        float[] left = canvas.rects.get(0);
        float[] right = canvas.rects.get(1);
        assertEquals(bottom(left), bottom(right), 0.01f, "both stand on the baseline");
        assertTrue(right[0] >= left[0] + left[2],
                "grouped bars must not overlap: " + left[0] + "+" + left[2] + " vs " + right[0]);
    }

    @Test
    void aNanIsAGapAndNotAZero() {
        // The distinction the whole data model turns on: "we measured nothing" must not
        // draw the same mark as "we measured zero".
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b", "c");
        chart.addSeries(ChartSeries.of("v", 10, Double.NaN, 30));
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        sceneOf(chart, canvas);

        assertEquals(2, canvas.rects.size(), "the gap draws no bar");
    }

    @Test
    void aHorizontalChartRunsItsValuesAcrossInsteadOfUp() {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b");
        chart.addSeries(ChartSeries.of("v", 10, 20));
        chart.setHorizontal(true);
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        sceneOf(chart, canvas);

        float[] ten = canvas.rects.get(0);
        float[] twenty = canvas.rects.get(1);
        assertEquals(twenty[2] / 2, ten[2], 0.01f, "length is now the width");
        assertEquals(ten[0], twenty[0], 0.01f, "and both start at the same baseline");
        assertTrue(twenty[1] > ten[1], "categories run down the side");
    }

    // ------------------------------------------------------------- animation

    @Test
    void valuesGrowOutOfTheBaselineAndSettleOnTheData() {
        BarChart chart = new BarChart();
        chart.setLabels("a");
        chart.addSeries(ChartSeries.of("v", 40));
        chart.setAnimationDuration(0.5);
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        Scene scene = sceneOf(chart, canvas); // arms the animation on attach

        canvas.reset();
        scene.renderFrame(canvas);
        float atStart = canvas.rects.isEmpty() ? 0 : canvas.rects.get(0)[3];

        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
        canvas.reset();
        scene.renderFrame(canvas);
        float midway = canvas.rects.get(0)[3];

        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
        canvas.reset();
        scene.renderFrame(canvas);
        float settled = canvas.rects.get(0)[3];

        assertTrue(midway > atStart, "the bar must be growing: " + atStart + " -> " + midway);
        assertTrue(settled > midway, "and still growing at the halfway point");
        // The settled height is the whole plot: 40 is the top of a 0..40 scale.
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
        canvas.reset();
        scene.renderFrame(canvas);
        assertEquals(settled, canvas.rects.get(0)[3], 0.01f, "and then stops");
    }

    @Test
    void aChartWithNoSceneDrawsFinalValues() {
        // The headless guarantee: a screenshot or a test renders the data, never a frame
        // of an animation that has no clock to run on.
        BarChart chart = new BarChart();
        chart.setLabels("a");
        chart.addSeries(ChartSeries.of("v", 40));
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        chart.measure(limn.scene.Constraints.tight(400, 300));
        chart.layoutBox(0, 0, 400, 300);
        chart.paintWidget(canvas);

        assertEquals(1, canvas.rects.size(), "the bar is drawn");
        assertTrue(canvas.rects.get(0)[3] > 200,
                "and at full height, not part-way through an animation");
    }

    // ------------------------------------------------------------ interaction

    @Test
    void hoveringReportsTheDatumUnderThePointerAndLeavingClearsIt() {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b");
        chart.addSeries(ChartSeries.of("v", 10, 20));
        AtomicReference<ChartPoint> seen = new AtomicReference<>();
        chart.onPointHover(seen::set);
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        Scene scene = sceneOf(chart, canvas);

        scene.mouseMoved(300, 200); // inside the second category
        scene.inputBatchEnded();
        ChartPoint point = chart.hoveredPoint();
        assertNotNull(point, "a pointer inside the plot must report a datum");
        assertEquals(1, point.index(), "the second category");
        assertEquals(20, point.value(), 1e-9);
        assertEquals("b", point.label());
        assertSame(point, seen.get(), "and the hover callback must see the same datum");

        scene.mouseMoved(-5, -5); // out of the widget
        scene.inputBatchEnded();
        assertNull(chart.hoveredPoint(), "leaving clears the hover");
        assertNull(seen.get(), "and reports null");
    }

    @Test
    void clickingReportsTheDatumUnderThePointer() {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b");
        chart.addSeries(ChartSeries.of("v", 10, 20));
        AtomicReference<ChartPoint> clicked = new AtomicReference<>();
        chart.onPointClick(clicked::set);
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        Scene scene = sceneOf(chart, canvas);

        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 300, 200);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 300, 200);
        scene.inputBatchEnded();

        assertNotNull(clicked.get(), "a click on a bar must be reported");
        assertEquals(1, clicked.get().index());
        assertEquals(20, clicked.get().value(), 1e-9);
    }

    @Test
    void aClickInTheEmptySpaceAboveABarStillReportsThatCategory() {
        // Index-mode interaction: the reader is pointing at a column, not at a rectangle.
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b");
        chart.addSeries(ChartSeries.of("v", 1, 40));
        AtomicReference<ChartPoint> clicked = new AtomicReference<>();
        chart.onPointClick(clicked::set);
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        Scene scene = sceneOf(chart, canvas);

        // Category "a" is 1 out of 40: near the top of its column there is no bar at all.
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 120, 40);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 120, 40);
        scene.inputBatchEnded();

        assertNotNull(clicked.get(), "the column is the target, not just the bar");
        assertEquals(0, clicked.get().index());
    }

    @Test
    void theTooltipNamesEverySeriesAtTheHoveredCategory() {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b");
        chart.addSeries(ChartSeries.of("first", 10, 20));
        chart.addSeries(ChartSeries.of("second", 30, 40));
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        Scene scene = sceneOf(chart, canvas);

        scene.mouseMoved(300, 200);
        scene.inputBatchEnded();
        canvas.reset();
        scene.renderFrame(canvas);

        assertTrue(canvas.texts.contains("first") && canvas.texts.contains("second"),
                "both series should be in the tooltip: " + canvas.texts);
        assertTrue(canvas.texts.contains("20") && canvas.texts.contains("40"),
                "with their values at that category: " + canvas.texts);
    }

    @Test
    void clickingALegendEntryHidesItsSeriesAndRescalesTheAxis() {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a");
        chart.addSeries(ChartSeries.of("keep", 10));
        chart.addSeries(ChartSeries.of("drop", 100));
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        Scene scene = sceneOf(chart, canvas);
        assertTrue(canvas.texts.contains("100"), "the scale starts at the taller series");

        // The legend sits at the bottom of the box; its entries are the only thing there.
        float legendY = 300 - 6 - 6; // padding, then the row
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 260, legendY);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 260, legendY);
        scene.inputBatchEnded();
        canvas.reset();
        scene.renderFrame(canvas);

        List<ChartSeries> series = chart.series();
        assertTrue(series.get(0).isVisible() != series.get(1).isVisible(),
                "exactly one series should have been toggled");
        assertFalse(canvas.texts.contains("100"),
                "the scale should follow what is left: " + canvas.texts);
    }

    @Test
    void aHiddenSeriesKeepsItsLegendEntrySoItCanComeBack() {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a");
        chart.addSeries(ChartSeries.of("visible", 10));
        chart.addSeries(ChartSeries.of("hidden", 20));
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        Scene scene = sceneOf(chart, canvas);

        chart.series().get(1).setVisible(false);
        canvas.reset();
        scene.renderFrame(canvas);

        assertTrue(canvas.texts.contains("hidden"), "a hidden series keeps its legend entry");
    }

    @Test
    void aHiddenSeriesDrawsNoMark() {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a");
        chart.addSeries(ChartSeries.of("visible", 10));
        chart.addSeries(ChartSeries.of("hidden", 20));
        chart.setLegendPosition(Chart.LegendPosition.NONE);
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        Scene scene = sceneOf(chart, canvas);
        assertEquals(2, canvas.rects.size(), "both bars to begin with");

        chart.series().get(1).setVisible(false);
        canvas.reset();
        scene.renderFrame(canvas);
        assertEquals(1, canvas.rects.size(), "the hidden series draws nothing");
    }

    // ------------------------------------------------------------------ lines

    @Test
    void aLineIsOneStrokePerRunOfRealValues() {
        LineChart chart = new LineChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b", "c", "d", "e");
        chart.addSeries(ChartSeries.of("v", 1, 2, Double.NaN, 4, 5));
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        sceneOf(chart, canvas);

        assertEquals(2, canvas.strokedPaths,
                "the gap must break the line into two strokes, not bridge it");
        assertEquals(4, canvas.circles / 2,
                "a marker per real value (each is a ring plus a dot)");
    }

    @Test
    void aFilledSeriesDrawsAnAreaUnderEachRun() {
        LineChart chart = new LineChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b", "c");
        chart.addSeries(ChartSeries.of("v", 1, 2, 3).setFilled(true));
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        sceneOf(chart, canvas);

        assertEquals(1, canvas.paths, "one filled area");
        assertEquals(1, canvas.strokedPaths, "and the line on top of it");
    }

    @Test
    void aLineAxisDoesNotForceZeroButABarAxisDoes() {
        // Not a preference: a bar's length is the value, so its axis has to start at zero;
        // a line's position is the value, so it may zoom in.
        LineChart line = new LineChart();
        line.setAnimationDuration(0);
        line.setLabels("a", "b");
        line.addSeries(ChartSeries.of("v", 20, 25));
        RecordingCanvas lineCanvas = new RecordingCanvas(400, 300);
        sceneOf(line, lineCanvas);
        assertFalse(lineCanvas.texts.contains("0"),
                "a line should not drag the axis to zero: " + lineCanvas.texts);
        assertTrue(lineCanvas.texts.contains("20"), "it frames the data: " + lineCanvas.texts);

        BarChart bar = new BarChart();
        bar.setAnimationDuration(0);
        bar.setLabels("a", "b");
        bar.addSeries(ChartSeries.of("v", 20, 25));
        RecordingCanvas barCanvas = new RecordingCanvas(400, 300);
        sceneOf(bar, barCanvas);
        assertTrue(barCanvas.texts.contains("0"),
                "a bar axis must reach zero: " + barCanvas.texts);
    }

    // ----------------------------------------------------------------- donuts

    @Test
    void everySliceOfADonutIsDrawnAndTheHoleIsAWidgetSlot() {
        DonutChart chart = new DonutChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b", "c", "d");
        chart.addSeries(ChartSeries.of("ring", 40, 30, 20, 10));
        Label total = new Label("100");
        chart.setCenter(total);
        RecordingCanvas canvas = new RecordingCanvas(400, 400);
        sceneOf(chart, canvas);

        assertEquals(4, canvas.paths, "one path per slice");
        assertTrue(total.width() > 0 && total.height() > 0, "the centre widget is laid out");
        float cx = total.x() + total.width() / 2;
        float cy = total.y() + total.height() / 2;
        assertTrue(Math.abs(cx - 200) < 40 && Math.abs(cy - 190) < 60,
                "and centred in the ring, but was at " + cx + "," + cy);
    }

    @Test
    void aDonutReportsTheSliceUnderThePointerWithItsShare() {
        DonutChart chart = new DonutChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b", "c", "d");
        chart.addSeries(ChartSeries.of("ring", 25, 25, 25, 25));
        // No legend: the ring is then centred in the 400x400 box, so the pointer
        // coordinates below are the ring's own geometry rather than a layout guess.
        chart.setLegendPosition(Chart.LegendPosition.NONE);
        RecordingCanvas canvas = new RecordingCanvas(400, 400);
        Scene scene = sceneOf(chart, canvas);

        // The first slice starts at noon and sweeps a quarter turn clockwise; this is
        // half-past-one at a radius well inside the ring.
        scene.mouseMoved(306, 94);
        scene.inputBatchEnded();
        ChartPoint point = chart.hoveredPoint();
        assertNotNull(point, "the ring must report the slice under the pointer");
        assertEquals(0, point.index(), "noon-to-three is the first slice");
        assertEquals(0.25, point.share(), 1e-6, "a quarter of the ring");
    }

    @Test
    void theHoleIsNotPartOfTheRing() {
        DonutChart chart = new DonutChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b");
        chart.addSeries(ChartSeries.of("ring", 50, 50));
        chart.setLegendPosition(Chart.LegendPosition.NONE);
        RecordingCanvas canvas = new RecordingCanvas(400, 400);
        Scene scene = sceneOf(chart, canvas);

        scene.mouseMoved(200, 200); // the middle of the hole
        scene.inputBatchEnded();
        assertNull(chart.hoveredPoint(), "the hole belongs to whatever is in it");
    }

    @Test
    void hidingASliceGivesItsAngleBackToTheOthers() {
        DonutChart chart = new DonutChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b", "c", "d");
        chart.addSeries(ChartSeries.of("ring", 25, 25, 25, 25));
        chart.setLegendPosition(Chart.LegendPosition.NONE);
        RecordingCanvas canvas = new RecordingCanvas(400, 400);
        Scene scene = sceneOf(chart, canvas);

        chart.setSliceVisible(3, false);
        canvas.reset();
        scene.renderFrame(canvas);
        assertEquals(3, canvas.paths, "the hidden slice is gone");

        scene.mouseMoved(200, 340); // six o'clock: the border of two slices, now inside one
        scene.inputBatchEnded();
        ChartPoint point = chart.hoveredPoint();
        assertNotNull(point, "the remaining slices must have grown into the gap");
        assertEquals(1.0 / 3, point.share(), 1e-6, "three slices now share the ring");
    }


    @Test
    void hoverAndClickWorkTheSameWayOnAChartTurnedSideways() {
        // The orientation swap runs through valuePosition/bandStart, so the pointer path is
        // shared code, but "shared" is a claim about a refactor, and this is the assertion.
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("first", "second");
        chart.addSeries(ChartSeries.of("v", 10, 20));
        chart.setHorizontal(true);
        AtomicReference<ChartPoint> clicked = new AtomicReference<>();
        chart.onPointClick(clicked::set);
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        Scene scene = sceneOf(chart, canvas);

        // Categories run down the side now: the lower half of the plot is "second".
        scene.mouseMoved(200, 230);
        scene.inputBatchEnded();
        ChartPoint hovered = chart.hoveredPoint();
        assertNotNull(hovered, "a pointer inside the plot must report a datum");
        assertEquals(1, hovered.index(), "the lower band is the second category");
        assertEquals("second", hovered.label());

        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 200, 230);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 200, 230);
        scene.inputBatchEnded();
        assertNotNull(clicked.get(), "and clicking must report the same one");
        assertEquals(1, clicked.get().index());
        assertEquals(20, clicked.get().value(), 1e-9);
    }

    @Test
    void theTooltipStaysInsideTheChartAtEveryEdge() {
        // The chart declares no paint outset, so anything it draws outside its box is a
        // stale-pixel bug under partial rendering, not merely an ugly frame.
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b", "c");
        chart.addSeries(ChartSeries.of("first", 10, 20, 30));
        chart.addSeries(ChartSeries.of("second", 15, 25, 35));
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        Scene scene = sceneOf(chart, canvas);

        for (float[] corner : new float[][] { {396, 8}, {396, 292}, {6, 292}, {6, 8} }) {
            scene.mouseMoved(corner[0], corner[1]);
            scene.inputBatchEnded();
            canvas.reset();
            scene.renderFrame(canvas);
            for (float[] rect : canvas.rects) {
                assertTrue(rect[0] >= -0.01f && rect[1] >= -0.01f
                                && rect[0] + rect[2] <= 400.01f && rect[1] + rect[3] <= 300.01f,
                        "pointer at " + corner[0] + "," + corner[1] + " drew a panel at "
                                + rect[0] + "," + rect[1] + " " + rect[2] + "x" + rect[3]
                                + ": outside the 400x300 box");
            }
        }
    }

    @Test
    void dataThatChangesMidAnimationResumesFromWhatIsOnScreen() {
        // The one ordering that is easy to get wrong and impossible to see in a still: the
        // snapshot has to be taken BEFORE the values move. Taken after, it records the
        // destination and the mark jumps to a fraction of the NEW value instead of carrying
        // on from where it was. The axis is pinned so that pixels can only move for one
        // reason.
        BarChart chart = new BarChart();
        chart.setLabels("a");
        ChartSeries series = ChartSeries.of("v", 40);
        chart.addSeries(series);
        chart.valueAxis().setMin(0.0);
        chart.valueAxis().setMax(100.0);
        chart.setAnimationDuration(0.5);
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        Scene scene = sceneOf(chart, canvas);

        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
        canvas.reset();
        scene.renderFrame(canvas);
        float midFlight = canvas.rects.get(0)[3];
        assertTrue(midFlight > 1, "the bar should be part-way up: " + midFlight);

        series.setValues(10);
        canvas.reset();
        scene.renderFrame(canvas);
        float afterChange = canvas.rects.get(0)[3];
        assertEquals(midFlight, afterChange, 1.5f,
                "the bar jumped from " + midFlight + " to " + afterChange
                        + ": the snapshot was taken after the values moved");

        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(600));
        canvas.reset();
        scene.renderFrame(canvas);
        float settled = canvas.rects.get(0)[3];
        assertTrue(settled < midFlight,
                "and then eases down to the new, smaller value: " + settled);
    }


    @Test
    void aChartTurnedSidewaysThinsItsLabelsToo() {
        // The same promise the vertical branch keeps: every label drawn is drawn whole.
        // Sideways, labels stack down the category axis, so it is their line height that
        // has to fit the band, and 60 of them in 300pt cannot.
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        List<String> labels = new ArrayList<>();
        double[] values = new double[60];
        for (int i = 0; i < 60; i++) {
            labels.add("c" + i);
            values[i] = i;
        }
        chart.setLabels(labels);
        chart.addSeries(ChartSeries.of("v", values));
        chart.setHorizontal(true);
        chart.setLegendPosition(Chart.LegendPosition.NONE);
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        sceneOf(chart, canvas);

        long drawn = canvas.texts.stream().filter(t -> t.startsWith("c")).count();
        assertTrue(drawn < 30,
                drawn + " of 60 category labels were drawn into a 300pt axis: they collide");
        assertTrue(drawn > 0, "but some must still be drawn");
    }

    @Test
    void aSidewaysLabelIsFittedToItsGutterAndNotToTheWholeChart() {
        // The gutter is the space between the chart's content edge and the plot. Measuring
        // it from the widget's origin instead over-reports it by everything to the left
        // (padding, and a left-hand legend), and the label is then drawn over them.
        // Moving the legend to the left makes the gutter SMALLER, so the label can only
        // shrink; if it grows, the gutter was measured from the wrong edge.
        String longLabel = "a-very-long-category-label";
        assertTrue(fittedLabel(longLabel, Chart.LegendPosition.LEFT).length()
                        <= fittedLabel(longLabel, Chart.LegendPosition.NONE).length(),
                "a left legend takes room from the label gutter, so the label cannot grow: "
                        + fittedLabel(longLabel, Chart.LegendPosition.LEFT) + " vs "
                        + fittedLabel(longLabel, Chart.LegendPosition.NONE));
    }

    /** Draws a sideways chart with one long category label and returns the text drawn for it. */
    private String fittedLabel(String label, Chart.LegendPosition legend) {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels(label, "b");
        chart.addSeries(ChartSeries.of("first", 10, 20));
        chart.addSeries(ChartSeries.of("second", 12, 22));
        chart.setHorizontal(true);
        chart.setLegendPosition(legend);
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        sceneOf(chart, canvas);
        return canvas.texts.stream().filter(t -> t.endsWith("…")).findFirst().orElse("");
    }

    @Test
    void recolouringSlicesRecoloursTheLegendWithThem() {
        // The legend is the identity key for the slices. A cached legend that keeps the old
        // swatches while the ring repaints in new colours does not merely look wrong; it
        // says the wrong slice is the wrong thing.
        DonutChart chart = new DonutChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a", "b");
        chart.addSeries(ChartSeries.of("ring", 50, 50));
        chart.setSliceColors(Color.rgb(0xFF0000), Color.rgb(0x00FF00));
        RecordingCanvas canvas = new RecordingCanvas(400, 400);
        Scene scene = sceneOf(chart, canvas);
        assertTrue(canvas.roundRectPaints.contains(Color.rgb(0xFF0000)),
                "the legend swatch starts at the colour it was given");

        chart.setSliceColors(Color.rgb(0x0000FF), Color.rgb(0xFFFF00));
        canvas.reset();
        scene.renderFrame(canvas);
        assertTrue(canvas.roundRectPaints.contains(Color.rgb(0x0000FF)),
                "the legend swatch must follow the new slice colour");
        assertFalse(canvas.roundRectPaints.contains(Color.rgb(0xFF0000)),
                "and must not still be painting the old one");
    }

    // ------------------------------------------------------------ series API

    @Test
    void aSeriesBelongsToOneChart() {
        ChartSeries shared = ChartSeries.of("v", 1);
        BarChart first = new BarChart();
        first.addSeries(shared);
        BarChart second = new BarChart();
        assertTrue(org.junit.jupiter.api.Assertions
                        .assertThrows(IllegalStateException.class, () -> second.addSeries(shared))
                        .getMessage().contains("already"),
                "adding one series to two charts must fail loudly");
    }

    @Test
    void changingValuesThroughTheSeriesRedrawsTheChart() {
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels("a");
        ChartSeries series = ChartSeries.of("v", 10);
        chart.addSeries(series);
        RecordingCanvas canvas = new RecordingCanvas(400, 300);
        Scene scene = sceneOf(chart, canvas);
        float before = canvas.rects.get(0)[3];

        series.setValues(20);
        canvas.reset();
        scene.renderFrame(canvas);
        // 10 on a 0..10 scale filled the plot; 20 on a 0..20 scale fills the same plot, so
        // the height is unchanged and the axis is what moved.
        assertEquals(before, canvas.rects.get(0)[3], 0.01f);
        assertTrue(canvas.texts.contains("20"), "the axis follows the new value: " + canvas.texts);
    }
}
