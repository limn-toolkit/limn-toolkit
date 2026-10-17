package limn.backend.lwjgl;

import limn.components.BackdropPanel;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import limn.scene.layout.Stack;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A partial frame over a panel of glass draws what the whole frame drew, against a real device.
 *
 * <p>The defect this exists for was seen on the Showcase's backdrop page on a Retina screen: a thin
 * line down the left edge of the floating panel, through the sunset picture under it, that came and
 * went. It was a pass edge. The scene widens a frame's damage by the rectangle of every panel of
 * glass the damage reaches, since a panel made of the pixels behind it is stale when they change,
 * and that rectangle went in at the panel's layout position, a fraction of a point in, where every
 * other damage rectangle is snapped outward to whole device pixels. A pass clears its rectangle
 * rounded to whole pixels and paints inside a clip that covers the pixel its edge cuts only in part,
 * so that pixel came back as the picture with a share of the window's background in it: a column the
 * height of the pass, standing until something repainted that pixel whole. Nothing about the glass
 * itself was wrong, which is why the line stood outside it.
 *
 * <p>Measured on the backdrop page before the fix: at every window width that put the panel a
 * fraction of a pixel off the grid, hovering or dragging anything inside the panel left the column
 * at its edge, while the same steps with the panel on the grid drew every pixel of the whole frame.
 */
class BackdropPassSeamTest {

    private static final float SCALE = 2f;
    /** The target in device pixels: 160 × 80 points. */
    private static final int WIDTH = 320;
    private static final int HEIGHT = 160;

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        fonts = new FontStore();
        canvas = new GlCanvas(fonts);
        Theme.setCurrent(Theme.dark());
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

    /** Something bright and busy under the glass, so a share of a dark background in it shows. */
    private static final class Picture extends Widget<Picture> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(WIDTH / SCALE, HEIGHT / SCALE);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            for (int band = 0; band * 6 < height(); band++) {
                canvas.fillRect(0, band * 6, width(), 6,
                        band % 2 == 0 ? Color.rgb(0xF2A65A) : Color.rgb(0xC8507A));
            }
        }
    }

    /** What repaints inside the panel: a slider's thumb, a button under the pointer. */
    private static final class Thumb extends Widget<Thumb> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(12, 12);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRect(0, 0, width(), height(), Color.WHITE);
        }
    }

    @Test
    void aPartialFrameOverAPanelOffTheGridDrawsWhatTheWholeFrameDrew() {
        Thumb thumb = new Thumb();
        BackdropPanel glass = BackdropPanel.frosted(Insets.all(10), thumb);
        Stack layers = new Stack();
        layers.add(new Picture());
        // Where a centred panel lands: a fraction of a point in. 20.1 points is device x 40.2, and
        // the rectangle the scene adds a point outside it starts at 38.2, so the pass cuts device
        // pixel 38 at four fifths.
        layers.add(new Padding(new Insets(30.35f, 0, 0, 20.1f), glass));
        Scene scene = new Scene(layers);
        scene.setPartialRendering(true);

        int texture = GL33C.glGenTextures();
        int fbo = GL33C.glGenFramebuffers();
        int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        try {
            bindTarget(texture, fbo);
            // A whole frame, then the same target kept, as a buffer that holds its contents is (age
            // 1): what the pass does not repaint is exactly the whole frame's.
            frame(scene, 0);
            assertEquals(20.1f, glass.localToSceneX(), 1e-4f, "the panel sits off the grid, as the case needs");
            thumb.invalidate();
            frame(scene, 1);
            byte[] partial = readTopDown();
            frame(scene, 0);
            byte[] whole = readTopDown();

            assertSame(whole, partial);
        } finally {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
        }
    }

    private void frame(Scene scene, int bufferAge) {
        canvas.beginFrame(WIDTH, HEIGHT, SCALE);
        scene.renderFrame(canvas, false, Float.NaN, bufferAge);
        canvas.endFrame();
    }

    /** Every pixel within 2 of the reference, or the columns that are not, with their extent. */
    private static void assertSame(byte[] expected, byte[] actual) {
        Map<Integer, int[]> columns = new TreeMap<>();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int at = (y * WIDTH + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    if (Math.abs((expected[at + channel] & 0xFF) - (actual[at + channel] & 0xFF)) > 2) {
                        int[] column = columns.get(x);
                        if (column == null) {
                            column = new int[] {0, y, y, at}; // count, first row, last row, a sample
                            columns.put(x, column);
                        }
                        column[0]++;
                        column[2] = y;
                        break;
                    }
                }
            }
        }
        if (!columns.isEmpty()) {
            StringBuilder report = new StringBuilder();
            columns.entrySet().stream().limit(6).forEach(e -> {
                int[] c = e.getValue();
                report.append(String.format(" x %d: %d px, y %d..%d, %s vs %s;",
                        e.getKey(), c[0], c[1], c[2], hex(actual, c[3]), hex(expected, c[3])));
            });
            fail("the partial frame differs from the whole one in " + columns.size()
                    + " device column(s), partial vs whole:" + report);
        }
    }

    private static String hex(byte[] pixels, int at) {
        return String.format("%02x%02x%02x", pixels[at] & 0xFF, pixels[at + 1] & 0xFF, pixels[at + 2] & 0xFF);
    }

    private static void bindTarget(int texture, int fbo) {
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
        GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                GL33C.GL_TEXTURE_2D, texture, 0);
        assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE, GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER),
                "render target");
        GL33C.glViewport(0, 0, WIDTH, HEIGHT);
    }

    private static byte[] readTopDown() {
        ByteBuffer pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        try {
            GL33C.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            GL33C.glReadPixels(0, 0, WIDTH, HEIGHT, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
            byte[] out = new byte[WIDTH * HEIGHT * 4];
            int stride = WIDTH * 4;
            for (int row = 0; row < HEIGHT; row++) {
                pixels.position((HEIGHT - 1 - row) * stride);
                pixels.get(out, row * stride, stride);
            }
            return out;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}
