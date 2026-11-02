package limn.backend.lwjgl;

import limn.components.chart.Chart;
import limn.components.chart.ChartSeries;
import limn.components.chart.LineChart;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.Path2D;
import limn.graphics.Rect;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A line chart's area where its values change sign, drawn on a real device: it covers exactly the
 * region between the line and the baseline, on both sides of zero, and each side fades from its
 * own farthest value to the baseline. Before, the area was one outline crossing itself filled by an
 * ear clipper, which drew a wedge from the baseline up to the highest value and left the lobes
 * under zero partly empty; and one gradient, anchored above the baseline, left every lobe under it
 * at the faint end.
 *
 * <p>The oracle is the area's own outline, read from what the chart asks the canvas to fill: a
 * point well inside it (under the non-zero rule, a pixel and a half from any edge) must be tinted,
 * a point well outside must show the page.
 */
class GlLineChartAreaTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 240;
    private static final int H_PT = 160;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final Color PAGE = Color.WHITE;
    /** Half a pixel and one more, in points: the reach of the fringe and of a gradient's step. */
    private static final float MARGIN = 1.5f;

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        fonts = new FontStore();
        canvas = new GlCanvas(fonts);
    }

    @AfterEach
    void closeContext() {
        if (canvas != null) {
            canvas.dispose();
            canvas = null;
        }
        if (fonts != null) {
            fonts.close();
            fonts = null;
        }
        if (ui != null) {
            ui.close();
            ui = null;
        }
    }

    private static LineChart chart() {
        LineChart chart = LineChart.of(List.of("a", "b", "c", "d", "e", "f"),
                ChartSeries.of("margin", 3, -2, 4, -3, 2, -1)
                        .setColor(Color.BLACK).setLineWidth(0).setPointRadius(0));
        chart.setArea(true);
        chart.setAnimationDuration(0);
        chart.setLegendPosition(Chart.LegendPosition.NONE);
        chart.valueAxis().setGrid(false);
        chart.categoryAxis().setGrid(false);
        chart.measure(limn.scene.Constraints.tight(W_PT, H_PT));
        chart.layoutBox(0, 0, W_PT, H_PT);
        return chart;
    }

    /** The paths the chart asks to fill, in its own coordinates, which are the frame's. */
    private static List<Path2D> areas(LineChart chart) {
        List<Path2D> filled = new ArrayList<>();
        chart.paintWidget(new NoopCanvas(W_PT, H_PT) {
            @Override
            public void fillPath(Path2D path, Paint paint) {
                filled.add(path.copy());
            }
        });
        return filled;
    }

    @Test
    void theAreaCoversWhatLiesBetweenTheLineAndTheBaselineAndNothingElse() {
        LineChart chart = chart();
        Path2D area = areas(chart).get(0);
        byte[] image = render(c -> chart.paintWidget(c));
        Rect box = area.bounds();
        int inside = 0;
        int outside = 0;
        for (float y = box.y() + 1; y < box.y() + box.height(); y += 2) {
            for (float x = box.x() + 1; x < box.x() + box.width(); x += 2) {
                Boolean side = clearly(area, x, y);
                if (side == null) {
                    continue;
                }
                int[] pixel = pixel(image, x, y);
                if (side) {
                    inside++;
                    assertTrue(pixel[0] < 255 - 4, "a point inside the area, at " + x + ", " + y
                            + ", shows the page: " + pixel[0]);
                } else {
                    outside++;
                    assertEquals(255, pixel[0], 1, "a point outside the area, at " + x + ", " + y + ", is tinted");
                }
            }
        }
        assertTrue(inside > 200 && outside > 200, "too few points tested: " + inside + " in, " + outside + " out");
    }

    @Test
    void eachSideFadesFromItsOwnFarthestValue() {
        LineChart chart = chart();
        List<Path2D> filled = areas(chart);
        assertEquals(2, filled.size(), "one fill for each side of the baseline");
        float[] points = linePoints(filled.get(0));
        byte[] image = render(c -> chart.paintWidget(c));
        // The baseline is where the line crosses between "a" (3) and "b" (-2): 3/5 of the way down.
        float base = points[1] + (points[3] - points[1]) * 3 / 5f;
        float high = points[5]; // "c", at 4, the highest value
        float low = points[7];  // "d", at -3, the lowest
        // At the same fraction of the way from the baseline to each side's farthest value, the
        // same alpha: below "c" and above "d", a fifth of the way in from the line.
        int[] above = pixel(image, points[4], base + (high - base) * 0.8f);
        int[] below = pixel(image, points[6], base + (low - base) * 0.8f);
        assertEquals(above[0], below[0], 2, "the lobe under zero fades as the one above it does");
        assertTrue(below[0] < 255 - 40, "the lobe under zero is drawn at the strong end near its line: " + below[0]);
    }

    /** Whether a point is inside the area by a margin, outside by one, or too near an edge to say. */
    private static Boolean clearly(Path2D area, float x, float y) {
        boolean in = area.contains(x, y);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (area.contains(x + dx * MARGIN, y + dy * MARGIN) != in) {
                    return null;
                }
            }
        }
        return in;
    }

    /** The line's points, x then y, from the area's outline: its first six vertices. */
    private static float[] linePoints(Path2D area) {
        float[] points = new float[12];
        int[] n = {0};
        area.forEach(new Path2D.Visitor() {
            @Override
            public void moveTo(float x, float y) {
                add(x, y);
            }

            @Override
            public void lineTo(float x, float y) {
                add(x, y);
            }

            @Override
            public void quadTo(float cx, float cy, float x, float y) {
                add(x, y);
            }

            @Override
            public void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
                add(x, y);
            }

            @Override
            public void close() {
            }

            private void add(float x, float y) {
                if (n[0] < 12) {
                    points[n[0]++] = x;
                    points[n[0]++] = y;
                }
            }
        });
        return points;
    }

    /** The pixel under a point given in logical points, its RGB. */
    private static int[] pixel(byte[] image, float xPt, float yPt) {
        int x = (int) (xPt * SCALE);
        int y = (int) (yPt * SCALE);
        int at = (y * WIDTH + x) * 4;
        return new int[] {image[at] & 0xff, image[at + 1] & 0xff, image[at + 2] & 0xff};
    }

    private byte[] render(Consumer<Canvas> paint) {
        int texture = Gl.glGenTextures();
        int fbo = Gl.glGenFramebuffers();
        int depthStencil = Gl.glGenRenderbuffers();
        int previousFbo = Gl.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        Gl.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        try {
            Gl.glActiveTexture(GL33C.GL_TEXTURE0);
            Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
            Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            Gl.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            Gl.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, depthStencil);
            Gl.glRenderbufferStorage(GL33C.GL_RENDERBUFFER, GL33C.GL_DEPTH24_STENCIL8, WIDTH, HEIGHT);
            Gl.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, 0);
            Gl.glFramebufferRenderbuffer(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_STENCIL_ATTACHMENT,
                    GL33C.GL_RENDERBUFFER, depthStencil);
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE, Gl.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER));
            Gl.glViewport(0, 0, WIDTH, HEIGHT);
            canvas.beginFrame(WIDTH, HEIGHT, SCALE);
            canvas.clear(PAGE);
            paint.accept(canvas);
            canvas.endFrame();
            ByteBuffer pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
            try {
                Gl.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
                Gl.glReadPixels(0, 0, WIDTH, HEIGHT, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
                byte[] image = new byte[WIDTH * HEIGHT * 4];
                int row = WIDTH * 4;
                for (int y = 0; y < HEIGHT; y++) {
                    pixels.position((HEIGHT - 1 - y) * row);
                    pixels.get(image, y * row, row);
                }
                return image;
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } finally {
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            Gl.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
            Gl.glDeleteRenderbuffers(depthStencil);
        }
    }
}
