package limn.components;

import limn.components.chart.Chart;
import limn.components.chart.ChartPoint;
import limn.components.chart.ChartSeries;
import limn.graphics.Canvas;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Chart} read right to left: where the title is pinned, which end of the legend the first
 * entry sits at, which side of an entry its swatch leads from, and which side of the pointer the
 * tooltip opens on &mdash; together with the four places a chart deliberately does <em>not</em>
 * move, so a later sweep cannot quietly mirror them.
 *
 * <p>Every expectation is arithmetic against {@link #RULER}'s 10pt clusters rather than a picture.
 * A mirroring bug in a chart is not a wrong-looking screenshot: it is a swatch painted on one side
 * of an entry and hit-tested on the other, which a picture shows as a legend that simply does not
 * respond, and arithmetic shows as the two numbers that disagree.
 */
class ChartMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float W = 400;
    private static final float H = 300;

    private static final SizeTokens T = SizeTokens.of(ControlSize.MEDIUM);
    /** The box padding {@code layoutRegions} takes off every side. */
    private static final float PAD = T.spacingSmall();
    /** The title's own pad, taken off both ends, so the budget is the same in either direction. */
    private static final float TITLE_PAD = T.spacingMedium();
    private static final float GAP = T.spacingMedium();
    private static final float GAP_ICON = T.gapIcon();
    private static final float TIP_PAD_H = T.tooltipPadH();
    private static final float TIP_PAD_V = T.tooltipPadV();
    private static final float SWATCH = Math.round(T.label().size() * 0.75f);
    /** {@link #RULER}'s line height, which is taller than the swatch at this step. */
    private static final float LINE = 12;
    private static final float ROW_HEIGHT = Math.max(SWATCH, LINE);
    /**
     * The pointer offset the tooltip panel is placed at. The chart holds it privately; repeated
     * here because the assertions are about which <em>side</em> of the pointer it is applied to.
     */
    private static final float TOOLTIP_OFFSET = 14;
    /** One code point under {@link #RULER}. */
    private static final float CP = 10;

    private static float runWidth(String text) {
        return CP * text.codePointCount(0, text.length());
    }

    /** A legend entry's own width: the swatch, the gap, and the name. */
    private static float entryWidth(String name) {
        return SWATCH + GAP_ICON + runWidth(name);
    }

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private TestChart chart;
    private Scene scene;
    private RecordingCanvas canvas;

    /**
     * A chart with no marks of its own: everything asserted here belongs to {@link Chart}, and a
     * subclass that painted would put its own geometry in the recording.
     */
    private static final class TestChart extends Chart {
        @Override
        protected void paintContent(Canvas canvas, float x, float y, float w, float h) {
        }

        @Override
        protected ChartPoint pickAt(float localX, float localY) {
            return seriesCount() == 0 ? null : pointFor(0, 0, Double.NaN, localX, localY);
        }

        /** The plot rectangle's physical left edge, which no direction moves. */
        float plotLeft() {
            return contentLeft();
        }

        /** The plot rectangle's width, for the same reason. */
        float plotWidth() {
            return contentBoxWidth();
        }
    }

    private record Text(String text, float x) {
    }

    private static final class RecordingCanvas extends FakeCanvas {
        final List<float[]> fills = new ArrayList<>();
        final List<float[]> outlines = new ArrayList<>();
        final List<float[]> lines = new ArrayList<>();
        final List<Text> texts = new ArrayList<>();

        RecordingCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillRoundRect(RoundRect r, Paint paint) {
            fills.add(new float[] { r.x(), r.y(), r.width(), r.height() });
        }

        @Override
        public void drawRoundRect(RoundRect r, float strokeWidth, Paint paint) {
            outlines.add(new float[] { r.x(), r.y(), r.width(), r.height() });
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth,
                Paint paint) {
            lines.add(new float[] { x1, y1, x2, y2 });
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(new Text(text, x));
        }

        void reset() {
            fills.clear();
            outlines.clear();
            lines.clear();
            texts.clear();
        }
    }

    private void build(LayoutDirection direction) {
        chart = new TestChart();
        chart.setAnimationDuration(0);
        chart.setLayoutDirection(direction);
        scene = new Scene(chart, nanos::get);
        scene.setTextRuler(RULER);
        canvas = new RecordingCanvas(W, H);
        scene.layoutPass(W, H);
    }

    private void paint() {
        canvas.reset();
        scene.renderFrame(canvas);
    }

    /**
     * Hovers, then opens the tooltip fully. The fade is a real-time transition driven by the
     * scene's clock, so the frames are advanced rather than waited for: the first arms the
     * ticker at {@code dt == 0}, the second carries it past the fade, and the third is the one
     * the assertions read.
     */
    private void hover(float x, float y) {
        scene.mouseMoved(x, y);
        scene.inputBatchEnded();
        scene.renderFrame(canvas);
        nanos.addAndGet(500_000_000L);
        scene.renderFrame(canvas);
        paint();
    }

    private void click(float x, float y) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
    }

    private Text textOf(String wanted) {
        for (Text t : canvas.texts) {
            if (t.text().equals(wanted)) {
                return t;
            }
        }
        throw new AssertionError(wanted + " was never drawn; drawn: " + canvas.texts);
    }

    // ------------------------------------------------------------------ title

    @Test
    void theTitleIsPinnedToTheSideReadingStartsFrom() {
        build(LayoutDirection.RTL);
        chart.setTitle("Sales");
        paint();

        assertEquals(W - TITLE_PAD - runWidth("Sales"), textOf("Sales").x(), EPS,
                "the title's trailing edge sits in the pad on the right");
    }

    @Test
    void theTitleIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR);
        chart.setTitle("Sales");
        paint();

        assertEquals(TITLE_PAD, textOf("Sales").x(), EPS);
    }

    @Test
    void aMirroredTitleIsMeasuredFromTheStringThatIsActuallyDrawn() {
        // The trap: the metrics the baseline comes from are taken before the ellipsis, and a
        // title right-aligned from those would hang off the edge by exactly the number of
        // characters the ellipsis replaced.
        build(LayoutDirection.RTL);
        String overlong = "x".repeat(40); // 400pt against a 376pt budget
        chart.setTitle(overlong);
        paint();

        Text drawn = canvas.texts.get(0);
        assertNotEquals(overlong, drawn.text(), "the title must have been ellipsized to fit");
        assertEquals(W - TITLE_PAD, drawn.x() + runWidth(drawn.text()), EPS,
                "and its trailing edge is still flush against the pad");
        assertTrue(drawn.x() >= TITLE_PAD - EPS,
                "so it cannot start off the leading edge, at " + drawn.x());
    }

    // ----------------------------------------------------------------- legend

    /** Where a single centred row of two entries begins, in either direction. */
    private float rowLeft(String first, String second) {
        float available = W - 2 * PAD;
        float rowWidth = entryWidth(first) + GAP + entryWidth(second);
        return PAD + (available - rowWidth) / 2;
    }

    private void twoSeries() {
        chart.setLabels("cat");
        chart.addSeries(ChartSeries.of("aa", 1));
        chart.addSeries(ChartSeries.of("bbbb", 2));
    }

    @Test
    void theFirstLegendEntryIsTheOneTheReaderMeetsFirst() {
        build(LayoutDirection.RTL);
        twoSeries();
        paint();

        float left = rowLeft("aa", "bbbb");
        float rowWidth = entryWidth("aa") + GAP + entryWidth("bbbb");
        // Entry zero takes the trailing end of the centred row, and its own swatch takes the
        // trailing end of its box: reading right to left, both are the right-hand edge.
        assertEquals(left + rowWidth - SWATCH, canvas.fills.get(0)[0], EPS,
                "the first entry's swatch is at the row's right edge");
        assertEquals(left + rowWidth - entryWidth("aa"), textOf("aa").x(), EPS,
                "and its name fills the rest of that box, left edge first");
        assertEquals(left + entryWidth("bbbb") - SWATCH, canvas.fills.get(1)[0], EPS,
                "the second entry sits inboard of it");
        assertEquals(left, textOf("bbbb").x(), EPS);
    }

    @Test
    void theLegendIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR);
        twoSeries();
        paint();

        float left = rowLeft("aa", "bbbb");
        assertEquals(left, canvas.fills.get(0)[0], EPS);
        assertEquals(left + SWATCH + GAP_ICON, textOf("aa").x(), EPS);
        assertEquals(left + entryWidth("aa") + GAP, canvas.fills.get(1)[0], EPS);
        assertEquals(left + entryWidth("aa") + GAP + SWATCH + GAP_ICON, textOf("bbbb").x(), EPS);
    }

    @Test
    void aClickLandsOnTheEntryThatWasPaintedThere() {
        // The one bug this widget must not have: the paint and the hit test are two readings of
        // the same boxes, and a legend that highlights one entry and toggles another is worse
        // than a legend that does nothing.
        float left = rowLeft("aa", "bbbb");
        float rowWidth = entryWidth("aa") + GAP + entryWidth("bbbb");
        float insideTheRightHandEntry = left + rowWidth - entryWidth("aa") / 2;
        float rowMiddle = H - PAD - ROW_HEIGHT / 2;

        build(LayoutDirection.RTL);
        twoSeries();
        paint();
        click(insideTheRightHandEntry, rowMiddle);
        assertFalse(chart.series().get(0).isVisible(),
                "the right-hand entry is the first one, reading right to left");
        assertTrue(chart.series().get(1).isVisible());

        build(LayoutDirection.LTR);
        twoSeries();
        paint();
        click(insideTheRightHandEntry, rowMiddle);
        assertTrue(chart.series().get(0).isVisible());
        assertFalse(chart.series().get(1).isVisible(),
                "and the second one reading left to right, which is unchanged");
    }

    @Test
    void aHiddenEntrySwatchAndItsStrikeThroughFollowTheNameTheyBelongTo() {
        build(LayoutDirection.RTL);
        twoSeries();
        chart.series().get(0).setVisible(false);
        paint();

        float left = rowLeft("aa", "bbbb");
        float rowWidth = entryWidth("aa") + GAP + entryWidth("bbbb");
        float boxX = left + rowWidth - entryWidth("aa");
        // The outline swatch is the filled one's other branch; if only one of them mirrored, a
        // toggled entry would jump sides.
        assertEquals(boxX + entryWidth("aa") - SWATCH,
                canvas.outlines.get(0)[0] - Strokes.HALF_PIXEL_INSET, EPS);
        // The strike-through is a span over the run and needs no direction of its own, because
        // the x it starts from is the run's left edge in both directions.
        float[] strike = canvas.lines.get(0);
        assertEquals(boxX, strike[0], EPS);
        assertEquals(boxX + runWidth("aa"), strike[2], EPS);
    }

    // ---------------------------------------------------------------- tooltip

    private void oneSeries() {
        chart.setLabels("cat");
        chart.addSeries(ChartSeries.of("s", 12));
        chart.setValueFormat(value -> "9"); // a fixed width, so the columns are arithmetic
    }

    /** The panel the tooltip sizes itself to: the wider of the heading and the one row. */
    private static float panelWidth() {
        float row = SWATCH + GAP_ICON + runWidth("s") + GAP + runWidth("9");
        return Math.max(runWidth("cat"), row) + 2 * TIP_PAD_H;
    }

    @Test
    void theTooltipOpensOnTheSideReadingRunsTowards() {
        build(LayoutDirection.RTL);
        oneSeries();
        hover(200, 150);

        float px = 200 - TOOLTIP_OFFSET - panelWidth();
        assertEquals(px, canvas.fills.get(0)[0], EPS, "the panel leads the pointer to the left");

        // ...and the two columns swap with it, in the same pass. The heading and the row's name
        // are flush to the panel's trailing pad; the value takes the far column.
        assertEquals(px + panelWidth() - TIP_PAD_H - runWidth("cat"), textOf("cat").x(), EPS);
        assertEquals(px + panelWidth() - TIP_PAD_H - SWATCH, canvas.fills.get(1)[0], EPS);
        assertEquals(px + panelWidth() - TIP_PAD_H - SWATCH - GAP_ICON - runWidth("s"),
                textOf("s").x(), EPS);
        assertEquals(px + TIP_PAD_H, textOf("9").x(), EPS);
        assertTrue(textOf("9").x() + runWidth("9") <= textOf("s").x() + EPS,
                "the name and the value must not collide");
    }

    @Test
    void theTooltipIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR);
        oneSeries();
        hover(200, 150);

        float px = 200 + TOOLTIP_OFFSET;
        assertEquals(px, canvas.fills.get(0)[0], EPS);
        assertEquals(px + TIP_PAD_H, textOf("cat").x(), EPS);
        assertEquals(px + TIP_PAD_H, canvas.fills.get(1)[0], EPS);
        assertEquals(px + TIP_PAD_H + SWATCH + GAP_ICON, textOf("s").x(), EPS);
        assertEquals(px + panelWidth() - TIP_PAD_H - runWidth("9"), textOf("9").x(), EPS);
    }

    @Test
    void theTooltipFlipsToTheOtherSideRatherThanRunningOffTheBox() {
        // The preferred side and its fallback are one decision: a preferred side that mirrored
        // without its fallback would place the panel outside the chart at the opposite edge.
        build(LayoutDirection.RTL);
        oneSeries();
        hover(20, 150);
        assertEquals(20 + TOOLTIP_OFFSET, canvas.fills.get(0)[0], EPS,
                "no room on the leading side, so it takes the other one");

        build(LayoutDirection.LTR);
        oneSeries();
        hover(380, 150);
        assertEquals(380 - TOOLTIP_OFFSET - panelWidth(), canvas.fills.get(0)[0], EPS,
                "which is what it has always done at the right-hand edge");
    }

    // ------------------------------------------------ what does NOT mirror

    @Test
    void theVerticalHalfOfTheTooltipDoesNotMirror() {
        build(LayoutDirection.LTR);
        oneSeries();
        hover(200, 150);
        float ltrY = canvas.fills.get(0)[1];

        build(LayoutDirection.RTL);
        oneSeries();
        hover(200, 150);
        assertEquals(ltrY, canvas.fills.get(0)[1], EPS,
                "there is nothing on the vertical axis for a direction to say");
        assertEquals(150 + TOOLTIP_OFFSET, ltrY, EPS);
    }

    @Test
    void aLegendPinnedLeftStaysOnTheLeft() {
        // LegendPosition.LEFT and RIGHT are published physical constants. Re-reading LEFT as
        // "leading" would silently move the legend of every chart that already asks for it,
        // which is a behaviour change dressed as a mirroring fix.
        build(LayoutDirection.LTR);
        twoSeries();
        chart.setLegendPosition(Chart.LegendPosition.LEFT);
        paint();
        float ltrPlotLeft = chart.plotLeft();
        float ltrPlotWidth = chart.plotWidth();
        float ltrSwatchX = canvas.fills.get(0)[0];
        assertEquals(PAD, ltrSwatchX, EPS, "the column starts in the box's own pad");

        build(LayoutDirection.RTL);
        twoSeries();
        chart.setLegendPosition(Chart.LegendPosition.LEFT);
        paint();

        float column = entryWidth("bbbb"); // the widest entry, under the 40% clamp
        assertEquals(PAD + column - SWATCH, canvas.fills.get(0)[0], EPS,
                "the entries mirror inside their boxes, but the column does not move");
        assertTrue(canvas.fills.get(0)[0] < W / 2, "and it is still the left-hand column");
        // The plot rectangle is what the legend carved itself out of, so it cannot have moved
        // either. contentLeft() means the physical left edge in both directions: mirroring the
        // marks drawn inside it is a decision for the chart that draws them.
        assertEquals(ltrPlotLeft, chart.plotLeft(), EPS);
        assertEquals(ltrPlotWidth, chart.plotWidth(), EPS);
    }

    @Test
    void theCentredLegendRowDoesNotMove() {
        // Only the order the row is walked in mirrors. The block itself is centred in the band
        // in both directions, so its two outer edges are the same numbers.
        build(LayoutDirection.LTR);
        twoSeries();
        paint();
        float ltrLeft = textOf("aa").x() - SWATCH - GAP_ICON;
        float ltrRight = textOf("bbbb").x() + runWidth("bbbb");

        build(LayoutDirection.RTL);
        twoSeries();
        paint();
        float rtlLeft = textOf("bbbb").x();
        float rtlRight = canvas.fills.get(0)[0] + SWATCH;

        assertEquals(ltrLeft, rtlLeft, EPS, "the centred block keeps its left edge");
        assertEquals(ltrRight, rtlRight, EPS, "and its right one");
        assertEquals(rowLeft("aa", "bbbb"), rtlLeft, EPS);
    }
}
