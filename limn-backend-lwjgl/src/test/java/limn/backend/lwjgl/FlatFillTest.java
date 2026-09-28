package limn.backend.lwjgl;

import limn.concurrent.UiRuntime;
import limn.graphics.Color;
import limn.graphics.LinearGradient;
import limn.graphics.RoundRect;
import limn.scene.Scene;
import limn.scene.layout.SizedBox;
import limn.testing.HeadlessUi;
import limn.testing.StubWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Large solid rectangles on whole device pixels are drawn by the flat program, cut to the clip,
 * against a real device.
 *
 * <p>The cost this exists for: the scrim that dims a window a modal blocks was one SDF quad over the
 * whole window, and on a renderer that runs the fragment shader on the CPU (Apple Software
 * Renderer, a VM with no GPU) that quad cost as much as every widget of a form beneath it: 365 ms at
 * 2604 x 1850 against 9.9 ms for the flat program, so a blocked window's full frame took twice an
 * unblocked one's. A partial repaint paid it once per pass over the bounding box of every pass,
 * because the clip rectangle discarded the scrim's pixels only after the shader had run for each:
 * two 200 x 20 pt passes in opposite corners cost 734 ms, against 0.23 ms cut.
 *
 * <p>So these pin the structure, which is what regresses silently: that the scrim is a flat
 * rectangle, that a pass draws it only inside itself, and that nothing whose coverage is not exactly
 * one takes the flat path. And they pin the pixels, which the flat path must not change: every one
 * is the colour composited at full coverage, the target's last row and column included, where the
 * SDF had left up to 33 of 255 of it out.
 */
class FlatFillTest {

    private static final float SCALE = 2f;
    /** The target in device pixels: 64 × 40 points. */
    private static final int WIDTH = 128;
    private static final int HEIGHT = 80;
    private static final Color BACKGROUND = Color.rgb(0xF0E8D8);
    /** Coloured and translucent, so a colour composited straight rather than premultiplied shows. */
    private static final Color SCRIM = Color.rgba(0x3060C0, 0.5f);
    /** What the scene's own scrim is once it has faded in. */
    private static final Color SCENE_SCRIM = Color.rgba(0x000000, 0.5f);
    /** What the target holds before a pass, so a pass that drew nothing cannot pass for one that did. */
    private static final Color STALE = Color.rgb(0x22CC44);

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;
    private UiRuntime runtime;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        runtime = ui.runtime();
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
        if (runtime != null) {
            ui.close();
            runtime = null;
        }
    }

    @Test
    void aWholeTargetScrimIsOneFlatRectangleAtFullCoverageEverywhere() {
        int[] drawn = new int[1];
        byte[] pixels = render(c -> {
            c.beginFrame(WIDTH, HEIGHT, SCALE);
            c.clear(BACKGROUND);
            c.fillRect(0, 0, c.width(), c.height(), SCRIM);
            drawn[0] = c.flatRectsThisFrame();
            assertEquals((long) WIDTH * HEIGHT, c.flatPixelsThisFrame(), "the whole target, once");
            c.endFrame();
        });

        assertEquals(1, drawn[0], "the scrim takes the flat program");
        assertEverywhere(pixels, over(BACKGROUND, SCRIM), 0, 0, WIDTH, HEIGHT, "a scrimmed target");
    }

    @Test
    void aPassDrawsTheScrimOnlyInsideItself() {
        // Two passes in opposite corners, the calls the scene makes for each: the damage scissor is
        // their bounding box, which is the whole target, so only the cut keeps the scrim's work to
        // the passes.
        float[][] passes = {{2, 3, 10, 6}, {48, 30, 14, 8}};
        long[] covered = new long[1];
        byte[] pixels = render(c -> {
            c.beginFrame(WIDTH, HEIGHT, SCALE);
            c.clear(STALE);
            c.endFrame();
        }, c -> {
            c.beginFrame(WIDTH, HEIGHT, SCALE);
            c.damageScissorHint(2, 3, 60, 35);
            for (float[] pass : passes) {
                c.save();
                c.clipRect(pass[0], pass[1], pass[2], pass[3]);
                c.clearRect(pass[0], pass[1], pass[2], pass[3], BACKGROUND);
                c.fillRect(0, 0, c.width(), c.height(), SCRIM);
                c.restore();
            }
            covered[0] = c.flatPixelsThisFrame();
            c.endFrame();
        });

        long expected = 0;
        for (float[] pass : passes) {
            expected += (long) (pass[2] * SCALE) * (long) (pass[3] * SCALE);
            assertEverywhere(pixels, over(BACKGROUND, SCRIM), (int) (pass[0] * SCALE),
                    (int) (pass[1] * SCALE), (int) ((pass[0] + pass[2]) * SCALE),
                    (int) ((pass[1] + pass[3]) * SCALE), "inside a pass");
        }
        assertEquals(expected, covered[0], "the scrim is drawn over the passes, not over the target");
        assertEverywhere(pixels, STALE, 0, 0, 4, HEIGHT, "left of every pass");
        assertEverywhere(pixels, STALE, 40, 0, 96, 60, "between the passes");
    }

    @Test
    void onlyAnExactFullCoverageTakesTheFlatPath() {
        assertFlat(1, "a quarter of the target on whole pixels", c ->
                c.fillRect(8, 4, 32, 20, SCRIM));
        assertFlat(0, "less than a quarter", c ->
                c.fillRect(8, 4, 32, 19.5f, SCRIM));
        assertFlat(0, "an edge a quarter pixel off the grid", c ->
                c.fillRect(0.125f, 0, 64, 40, SCRIM));
        assertFlat(0, "cut by a clip edge off the grid", c -> {
            c.clipRect(0, 0, 40.25f, 40);
            c.fillRect(0, 0, 64, 40, SCRIM);
        });
        assertFlat(0, "under a rounded clip", c -> {
            c.clipRoundRect(RoundRect.of(0, 0, 64, 40, 6));
            c.fillRect(0, 0, 64, 40, SCRIM);
        });
        assertFlat(0, "rounded itself", c -> c.fillRoundRect(0, 0, 64, 40, 4, SCRIM));
        assertFlat(0, "a gradient", c -> c.fillRect(0, 0, 64, 40,
                new LinearGradient(0, 0, 64, 0, Color.BLACK, Color.WHITE)));
        assertFlat(0, "rotated", c -> {
            c.rotate(0.1f);
            c.fillRect(0, 0, 64, 40, SCRIM);
        });
        assertFlat(0, "clipped away", c -> {
            c.clipRect(0, 0, 10, 10);
            c.fillRect(20, 0, 44, 40, SCRIM);
        });
    }

    @Test
    void theScrimOfAWindowAModalBlocksIsFlatAndOnlyThere() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        Scene scene = new Scene(new SizedBox(20, 10), now::get);
        StubWindow window = new StubWindow();
        scene.bind(window);
        int[] plain = new int[1];
        render(c -> {
            c.beginFrame(WIDTH, HEIGHT, SCALE);
            scene.renderFrame(c, false, Float.NaN, 0);
            plain[0] = c.flatRectsThisFrame();
            c.endFrame();
        });
        window.modalBlocked = true;
        int[] blocked = new int[1];
        byte[] pixels = null;
        for (int i = 0; i < 20; i++) { // past the scrim's fade
            now.addAndGet(50_000_000L);
            pixels = render(c -> {
                c.beginFrame(WIDTH, HEIGHT, SCALE);
                scene.renderFrame(c, false, Float.NaN, 0);
                blocked[0] = c.flatRectsThisFrame();
                c.endFrame();
            });
        }

        assertEquals(0, plain[0], "nothing in the unblocked window is large and flat");
        assertEquals(1, blocked[0], "the scrim is the one flat rectangle");
        assertEverywhere(pixels, over(scene.background(), SCENE_SCRIM), 0, 0, WIDTH, HEIGHT,
                "a blocked window");
    }

    // --------------------------------------------------------------- helpers

    private void assertFlat(int expected, String what, Consumer<GlCanvas> paint) {
        int[] drawn = new int[1];
        render(c -> {
            c.beginFrame(WIDTH, HEIGHT, SCALE);
            c.clear(BACKGROUND);
            c.save();
            paint.accept(c);
            c.restore();
            drawn[0] = c.flatRectsThisFrame();
            c.endFrame();
        });
        assertEquals(expected, drawn[0], what);
    }

    /** {@code top} over an opaque {@code bottom}, source-over, as 8-bit channels. */
    private static int[] over(Color bottom, Color top) {
        float a = top.a();
        return new int[] {
                Math.round((top.r() * a + bottom.r() * (1 - a)) * 255),
                Math.round((top.g() * a + bottom.g() * (1 - a)) * 255),
                Math.round((top.b() * a + bottom.b() * (1 - a)) * 255)};
    }

    private static void assertEverywhere(byte[] pixels, Color color, int x0, int y0, int x1, int y1,
                                         String what) {
        assertEverywhere(pixels, new int[] {Math.round(color.r() * 255), Math.round(color.g() * 255),
                Math.round(color.b() * 255)}, x0, y0, x1, y1, what);
    }

    private static void assertEverywhere(byte[] pixels, int[] want, int x0, int y0, int x1, int y1,
                                         String what) {
        StringBuilder differences = new StringBuilder();
        int count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int at = (y * WIDTH + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    if (Math.abs((pixels[at + channel] & 0xFF) - want[channel]) > 1) {
                        if (count < 8) {
                            differences.append(String.format(" (%d,%d) %02x%02x%02x;", x, y,
                                    pixels[at] & 0xFF, pixels[at + 1] & 0xFF, pixels[at + 2] & 0xFF));
                        }
                        count++;
                        break;
                    }
                }
            }
        }
        if (count > 0) {
            fail(what + ": " + count + " device pixel(s) differ by more than 1 from "
                    + String.format("%02x%02x%02x", want[0], want[1], want[2]) + ":" + differences);
        }
    }

    /** Runs the frames in order into one fresh target and reads it back top-down. */
    @SafeVarargs
    private byte[] render(Consumer<GlCanvas>... frames) {
        int texture = GL33C.glGenTextures();
        int fbo = GL33C.glGenFramebuffers();
        int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        try {
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE,
                    GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER), "render target");
            GL33C.glViewport(0, 0, WIDTH, HEIGHT);
            for (Consumer<GlCanvas> frame : frames) {
                frame.accept(canvas);
            }
            return readTopDown();
        } finally {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glViewport(previousViewport[0], previousViewport[1],
                    previousViewport[2], previousViewport[3]);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
        }
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
