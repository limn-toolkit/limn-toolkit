package limn.components;

import limn.components.chart.BarChart;
import limn.components.chart.Chart;
import limn.components.chart.ChartSeries;
import limn.components.chart.LineChart;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.RoundRect;
import limn.graphics.ShapedText;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Values a chart's scale does not hold: past a pinned end, or infinite. Each used to be drawn
 * where the arithmetic put it, over the axis labels and out of the chart's box, or to wreck the
 * whole chart; what is asserted here is that such a value costs its own mark and nothing else.
 *
 * <p>The chart sits inside a padding, so "outside its box" is somewhere a canvas can record ink.
 */
class ChartOutOfScaleTest extends ComponentTestBase {

    /**
     * Follows translation and clipping, and records every mark and grid line in scene
     * coordinates as the part the clip leaves visible, plus every label drawn.
     */
    private static final class InkCanvas extends FakeCanvas {
        final List<float[]> bars = new ArrayList<>();
        final List<float[]> strokes = new ArrayList<>();
        final List<Float> horizontalLines = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        int nonFinite;
        private float tx;
        private float ty;
        private float[] clip;
        private final Deque<float[]> saved = new ArrayDeque<>();

        InkCanvas() {
            super(500, 400);
        }

        @Override
        public void save() {
            super.save();
            saved.push(new float[] {tx, ty, clip == null ? Float.NaN : clip[0],
                    clip == null ? 0 : clip[1], clip == null ? 0 : clip[2], clip == null ? 0 : clip[3]});
        }

        @Override
        public void restore() {
            super.restore();
            float[] s = saved.pop();
            tx = s[0];
            ty = s[1];
            clip = Float.isNaN(s[2]) ? null : new float[] {s[2], s[3], s[4], s[5]};
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            float[] next = {x + tx, y + ty, x + tx + w, y + ty + h};
            if (clip != null) {
                next = new float[] {Math.max(next[0], clip[0]), Math.max(next[1], clip[1]),
                        Math.min(next[2], clip[2]), Math.min(next[3], clip[3])};
            }
            clip = next;
        }

        /** The visible part of a local box, or {@code null} when the clip hides all of it. */
        private float[] visible(float x0, float y0, float x1, float y1) {
            if (!Float.isFinite(x0) || !Float.isFinite(y0) || !Float.isFinite(x1) || !Float.isFinite(y1)) {
                nonFinite++;
                return null;
            }
            float[] box = {Math.min(x0, x1) + tx, Math.min(y0, y1) + ty,
                    Math.max(x0, x1) + tx, Math.max(y0, y1) + ty};
            if (clip != null) {
                box = new float[] {Math.max(box[0], clip[0]), Math.max(box[1], clip[1]),
                        Math.min(box[2], clip[2]), Math.min(box[3], clip[3])};
            }
            return box[2] > box[0] && box[3] > box[1] ? box : null;
        }

        @Override
        public void fillRoundRect(RoundRect r, Paint paint) {
            float[] box = visible(r.x(), r.y(), r.x() + r.width(), r.y() + r.height());
            if (box != null) {
                bars.add(box);
            }
        }

        @Override
        public void drawPath(Path2D path, float strokeWidth, Paint paint) {
            float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            boolean[] broken = {false};
            path.flatten(0.5f, new Path2D.Flattened() {
                @Override
                public void moveTo(float x, float y) {
                    add(x, y);
                }

                @Override
                public void lineTo(float x, float y) {
                    add(x, y);
                }

                @Override
                public void closePath() {
                }

                private void add(float x, float y) {
                    if (!Float.isFinite(x) || !Float.isFinite(y)) {
                        broken[0] = true;
                        return;
                    }
                    bounds[0] = Math.min(bounds[0], x);
                    bounds[1] = Math.min(bounds[1], y);
                    bounds[2] = Math.max(bounds[2], x);
                    bounds[3] = Math.max(bounds[3], y);
                }
            });
            if (broken[0]) {
                nonFinite++;
                return;
            }
            float half = strokeWidth / 2;
            float[] box = visible(bounds[0] - half, bounds[1] - half, bounds[2] + half, bounds[3] + half);
            if (box != null) {
                strokes.add(box);
            }
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
            if (y1 == y2) {
                horizontalLines.add(y1 + ty);
            }
        }

        @Override
        public void drawText(ShapedText line, float x, float y, Paint paint) {
            texts.add(line.text());
        }

        @Override
        public void drawText(String text, float x, float y, limn.graphics.Font font, Paint paint) {
            texts.add(text);
        }

        float topGridLine() {
            float top = Float.MAX_VALUE;
            for (float y : horizontalLines) {
                top = Math.min(top, y);
            }
            return top;
        }
    }

    private Scene sceneOf(Chart<?> chart) {
        chart.setAnimationDuration(0);
        chart.setLegendPosition(Chart.LegendPosition.NONE);
        chart.setPreferredSize(300, 200);
        Scene scene = new Scene(new Padding(Insets.all(100), chart));
        scene.setTextRuler(RULER);
        return scene;
    }

    private static InkCanvas frame(Scene scene) {
        InkCanvas canvas = new InkCanvas();
        scene.renderFrame(canvas);
        return canvas;
    }

    private static void assertInside(Chart<?> chart, List<float[]> boxes, String what) {
        float x = chart.localToSceneX();
        float y = chart.localToSceneY();
        for (float[] box : boxes) {
            assertTrue(box[0] >= x - 0.01f && box[1] >= y - 0.01f
                            && box[2] <= x + chart.width() + 0.01f && box[3] <= y + chart.height() + 0.01f,
                    what + " drawn outside the chart's box [" + x + "," + y + " " + chart.width() + "x"
                            + chart.height() + "]: " + java.util.Arrays.toString(box));
        }
    }

    // ------------------------------------------------------------- past a pinned end

    @Test
    void aBarPastAPinnedMaximumStopsAtTheTopOfThePlot() {
        BarChart chart = BarChart.of(List.of("a", "b", "c"), ChartSeries.of("v", 10, 200, 30));
        chart.valueAxis().setMax(50.0);
        InkCanvas canvas = frame(sceneOf(chart));

        assertEquals(3, canvas.bars.size(), "every bar still shows the part the scale holds");
        assertInside(chart, canvas.bars, "a bar");
        float tallTop = canvas.bars.get(1)[1];
        assertEquals(canvas.topGridLine(), tallTop, 0.01f,
                "the bar past the maximum must end on the top of the plot, where 50 is");
    }

    @Test
    void aLinePastAPinnedMaximumStaysInsideTheChart() {
        LineChart chart = LineChart.of(List.of("a", "b", "c"), ChartSeries.of("v", 10, 200, 30));
        chart.valueAxis().setMax(50.0);
        InkCanvas canvas = frame(sceneOf(chart));

        assertFalse(canvas.strokes.isEmpty(), "the line is still drawn");
        assertInside(chart, canvas.strokes, "the line");
    }

    @Test
    void newValuesLeaveNothingOutsideWhatTheChartDamages() {
        // The chart damages its own box when its values change, so ink past it was never
        // erased: after the values came back into range, the old bar stayed on screen above it.
        ChartSeries series = ChartSeries.of("v", 10, 200, 30);
        BarChart chart = BarChart.of(List.of("a", "b", "c"), series);
        chart.valueAxis().setMax(50.0);
        Scene scene = sceneOf(chart);
        assertInside(chart, frame(scene).bars, "a bar before the change");

        series.setValues(10, 20, 30);
        InkCanvas after = frame(scene);
        assertInside(chart, after.bars, "a bar after the change");
    }

    @Test
    void aPinnedMinimumAboveTheDataKeepsTheAxisTheRightWayUp() {
        // The pinned minimum was swapped with the data's maximum: the scale ran from 3 to 10,
        // with the 10 asked for at the bottom nowhere on it.
        BarChart chart = BarChart.of(List.of("a", "b", "c"), ChartSeries.of("v", 1, 2, 3));
        chart.setLocale(java.util.Locale.ENGLISH);
        chart.valueAxis().setMin(10.0);
        InkCanvas canvas = frame(sceneOf(chart));

        assertTrue(canvas.texts.contains("10"), "the pinned minimum is on the axis: " + canvas.texts);
        assertFalse(canvas.texts.contains("3"), "the data's maximum is not an end: " + canvas.texts);
        assertTrue(canvas.bars.isEmpty(), "every value lies below the scale, so no bar shows");
    }

    // --------------------------------------------------------------------- infinities

    @Test
    void anInfiniteValueIsAGapAndTheRestChartAsWithoutIt() {
        BarChart chart = BarChart.of(List.of("a", "b", "c"),
                ChartSeries.of("v", 10, Double.POSITIVE_INFINITY, 30));
        chart.setLocale(java.util.Locale.ENGLISH);
        InkCanvas canvas = frame(sceneOf(chart));

        assertEquals(0, canvas.nonFinite, "no mark may carry a non-finite coordinate");
        assertEquals(2, canvas.bars.size(), "the infinity draws no bar and the others draw theirs");
        assertTrue(canvas.texts.containsAll(List.of("0", "10", "20", "30")),
                "the scale follows the finite values, not 0..1: " + canvas.texts);
    }

    @Test
    void aNegativeInfinityBreaksALineLikeAGap() {
        LineChart chart = LineChart.of(List.of("a", "b", "c", "d", "e"),
                ChartSeries.of("v", 10, 20, Double.NEGATIVE_INFINITY, 30, 40));
        InkCanvas canvas = frame(sceneOf(chart));

        assertEquals(0, canvas.nonFinite, "no stroke may carry a non-finite coordinate");
        assertEquals(2, canvas.strokes.size(), "one stroke on each side of the gap");
        assertInside(chart, canvas.strokes, "the line");
    }
}
