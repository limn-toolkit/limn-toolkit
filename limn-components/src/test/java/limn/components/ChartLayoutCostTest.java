package limn.components;

import limn.components.chart.BarChart;
import limn.components.chart.Chart;
import limn.components.chart.ChartSeries;
import limn.graphics.Font;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a chart is allowed to cost per frame, expressed in text measurements: the one unit
 * that is both countable and honest here, since measuring is what the layout does and
 * everything else it does is arithmetic.
 *
 * <p>The invariant under test is <b>not</b> "layout is fast". It is that the per-frame cost
 * is a function of what is <em>drawn</em>, not of how much data the chart holds: the value
 * scale, the widest tick label and the widest category label are resolved once per data
 * change, not once per paint and once per pointer move. Losing that cache is invisible on
 * the twelve-point chart in the demo and turns a mouse move over a thousand-point series
 * into a full scan of the dataset, which is exactly the kind of regression that ships.
 */
class ChartLayoutCostTest extends ComponentTestBase {

    /** {@link #RULER}, counting. */
    private static final class CountingRuler implements TextRuler {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public TextMetrics measure(String text, Font font) {
            calls.incrementAndGet();
            return RULER.measure(text, font);
        }
    }

    private record Probe(int paintMeasures, int hoverMeasures) {
    }

    /** Renders and hovers a chart with {@code categories} categories, counting measurements. */
    private Probe probe(int categories) {
        return probe(categories, false);
    }

    /**
     * As above, optionally turned sideways, where the category labels also decide the
     * gutter, so the pointer path resolves them too.
     */
    private Probe probe(int categories, boolean horizontal) {
        List<String> labels = new ArrayList<>(categories);
        double[] values = new double[categories];
        for (int i = 0; i < categories; i++) {
            labels.add("c" + i);
            values[i] = i % 50;
        }
        BarChart chart = new BarChart();
        chart.setAnimationDuration(0);
        chart.setLabels(labels);
        chart.addSeries(ChartSeries.of("v", values));
        chart.setLegendPosition(Chart.LegendPosition.NONE);
        chart.setHorizontal(horizontal);

        CountingRuler ruler = new CountingRuler();
        Scene scene = new Scene(chart, new AtomicLong()::get);
        scene.setTextRuler(ruler);
        FakeCanvas canvas = new FakeCanvas(400, 300);
        scene.renderFrame(canvas); // warm: the first frame is allowed to resolve everything

        ruler.calls.set(0);
        for (int i = 0; i < 5; i++) {
            scene.renderFrame(canvas);
        }
        int paint = ruler.calls.get();

        ruler.calls.set(0);
        for (int i = 0; i < 20; i++) {
            scene.mouseMoved(60 + i * 5, 150);
            scene.inputBatchEnded();
        }
        return new Probe(paint, ruler.calls.get());
    }

    @Test
    void perFrameCostFollowsWhatIsDrawnAndNotHowMuchDataIsHeld() {
        Probe small = probe(20);
        Probe large = probe(400);

        // Twenty times the data. Without the caches the large chart measures every label
        // twice per paint (the widest-label scan and the skip-step scan) plus every tick,
        // so this ratio was the category ratio itself.
        assertTrue(large.paintMeasures() <= small.paintMeasures() * 2 + 20,
                "painting 400 categories cost " + large.paintMeasures()
                        + " measurements against " + small.paintMeasures() + " for 20; the "
                        + "layout is scanning the whole dataset per frame again");
        assertTrue(large.hoverMeasures() <= small.hoverMeasures() * 2 + 20,
                "hovering cost " + large.hoverMeasures() + " measurements against "
                        + small.hoverMeasures() + ": the pointer path is rescanning the data");
    }

    @Test
    void aPointerMoveCostsAFewMeasurementsAndNotAFrameOfLayout() {
        // Twenty moves over a 400-category chart, turned sideways so the pointer path has to
        // resolve the category gutter as well as the scale. That is the shape where a lost
        // cache costs the most: it was 400 measurements per move, twenty times over.
        Probe large = probe(400, true);
        assertTrue(large.hoverMeasures() < 200,
                "20 pointer moves cost " + large.hoverMeasures() + " text measurements");
    }
}
