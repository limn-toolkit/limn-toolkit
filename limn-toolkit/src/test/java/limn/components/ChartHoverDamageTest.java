package limn.components;

import limn.components.chart.BarChart;
import limn.components.chart.Chart;
import limn.components.chart.ChartSeries;
import limn.components.chart.DonutChart;
import limn.components.chart.LineChart;
import limn.graphics.Rect;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * A hover repaints the marks it lights and the panel it raises, not the chart.
 *
 * <p>ADR 043 &sect;9.4.2. Three gestures, and the third was the surprising one: a chart used to
 * repaint itself entirely for fourteen frames when the pointer <em>left</em> it, running a
 * tooltip fade whose panel was already gone. The other two are a hover arriving and a pointer
 * moving within one mark, which invalidated the whole chart on every motion event.
 *
 * <p>What is asserted is the clip, and per chart kind, because each declares its own region: a
 * cartesian chart lights a category band across the plot, a donut pops a slice out of a ring.
 * The share is of the widget, and the widget is boxed inside a larger window so the two are
 * different rectangles.
 */
class ChartHoverDamageTest extends ComponentTestBase {

    private static final int W = 500;
    private static final int H = 400;
    private static final int INSET = 40;

    private Scene scene;
    private AtomicLong nanos;
    private RecordingTestCanvas canvas;
    private Widget subject;
    /**
     * The union of every repaint pass a gesture caused. Accumulated during the gesture rather
     * than read after it: the loop below runs until the animation settles, so the canvas then
     * holds the empty frame that ended it and asking it afterwards reads nothing.
     */
    private Rect gestureDamage;

    private void mount(Widget widget) {
        subject = widget;
        nanos = new AtomicLong();
        scene = new Scene(new Padding(Insets.all(INSET), widget), nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        canvas = new RecordingTestCanvas(W, H);
        for (int i = 0; i < 300; i++) {
            nanos.addAndGet(200_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                return;
            }
        }
        throw new AssertionError("the chart never settled, so a reading would measure the "
                + "settling and not the hover");
    }

    /**
     * Moves the pointer and runs every frame the move causes.
     *
     * @return the worst share of the widget any of those frames repainted
     */
    private float worstShareOf(float x, float y, String what) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
        float box = Math.max(1, subject.width() * subject.height());
        float worst = 0;
        int painted = 0;
        gestureDamage = null;
        for (int i = 0; i < 40; i++) {
            nanos.addAndGet(16_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            if (canvas.nothingPainted()) {
                break;
            }
            painted++;
            assertFalse(canvas.cleared, what + " cleared the frame, so it repainted the window");
            worst = Math.max(worst, canvas.damagedArea() / box);
            for (Rect pass : canvas.passClips()) {
                gestureDamage = gestureDamage == null ? pass : gestureDamage.union(pass);
            }
        }
        assertTrue(painted > 0, what + " painted nothing, so this asserted nothing");
        return worst;
    }

    private void assertUnder(float share, float ceiling, String what) {
        assertTrue(share <= ceiling, what + " repainted " + Math.round(share * 100)
                + "% of the chart, ceiling " + Math.round(ceiling * 100) + "%");
    }

    /**
     * The three gestures, against one ceiling per chart kind.
     *
     * @param onMark  a point over a mark
     * @param nudge   a second point over the SAME mark, so only the panel moves
     * @param ceiling the most of the chart any frame may repaint
     */
    private void hoverArriveMoveLeave(Chart chart, float onMark, float onMarkY,
                                      float nudge, float nudgeY, float ceiling, String name) {
        mount(chart);
        assertUnder(worstShareOf(onMark, onMarkY, name + ": arriving"), ceiling,
                name + ": a pointer arriving on a mark");
        assertUnder(worstShareOf(nudge, nudgeY, name + ": moving"), ceiling,
                name + ": a pointer moving within one mark");
        assertUnder(worstShareOf(2, 2, name + ": leaving"), ceiling,
                name + ": a pointer leaving the chart");
    }

    @Test
    void aBarChartHoverRepaintsTheCategoryAndItsPanel() {
        BarChart chart = new BarChart();
        chart.addSeries(ChartSeries.of("v", 3, 17, 37, 12, 25));
        hoverArriveMoveLeave(chart, INSET + 140, INSET + 220, INSET + 145, INSET + 225,
                0.45f, "BarChart");
    }

    @Test
    void aLineChartHoverRepaintsTheCategoryAndItsPanel() {
        LineChart chart = new LineChart();
        chart.addSeries(ChartSeries.of("v", 3, 17, 37, 12, 25));
        hoverArriveMoveLeave(chart, INSET + 140, INSET + 160, INSET + 145, INSET + 165,
                0.45f, "LineChart");
    }

    /**
     * A donut's ceiling is higher on purpose: what the pop moves is the ring, and a ring fills
     * most of a chart that also carries a title and a legend. It is still not the chart.
     */
    @Test
    void aDonutHoverRepaintsTheRing() {
        DonutChart chart = new DonutChart();
        chart.addSeries(ChartSeries.of("v", 3, 17, 37, 12, 25));
        hoverArriveMoveLeave(chart, INSET + 210, INSET + 60, INSET + 215, INSET + 62,
                0.75f, "DonutChart");
    }

    /**
     * The panel has to be erased where it was, not only drawn where it is going. A pointer
     * crossing from one category to the next moves both the lit band and the panel, and damage
     * that named only the destination would leave the old panel painted on the chart — a defect
     * a headless assertion about a picture could never see, but one about a rectangle can.
     */
    @Test
    void aHoverCrossingCategoriesDamagesWhereItCameFrom() {
        BarChart chart = new BarChart();
        chart.addSeries(ChartSeries.of("v", 3, 17, 37, 12, 25));
        mount(chart);
        worstShareOf(INSET + 100, INSET + 220, "settling on the first category");
        Rect first = gestureDamage;
        assertTrue(first != null, "the first hover damaged nothing, so this asserts nothing");
        // Two categories over: the band and the panel both move a long way.
        worstShareOf(INSET + 300, INSET + 220, "crossing to another category");
        Rect crossing = gestureDamage;
        assertTrue(crossing != null, "the crossing damaged nothing");
        // Everything the first hover drew must be repainted, or it is still on the chart.
        assertTrue(crossing.x() <= first.x() + 1 && crossing.y() <= first.y() + 1
                        && crossing.x() + crossing.width() >= first.x() + first.width() - 1
                        && crossing.y() + crossing.height() >= first.y() + first.height() - 1,
                "the crossing damaged " + crossing + ", which does not cover " + first
                        + " where the first hover had drawn");
    }
}
