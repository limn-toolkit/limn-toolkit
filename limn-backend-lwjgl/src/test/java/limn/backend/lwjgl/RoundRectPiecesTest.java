package limn.backend.lwjgl;

import limn.concurrent.UiRuntime;
import limn.graphics.Color;
import limn.graphics.LinearGradient;
import limn.graphics.RoundRect;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ProgressBar;
import limn.components.TextField;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An axis-aligned rounded rectangle is shaded only where its distance field decides something, and
 * draws the pixels the single quad over its box drew, against a real device.
 *
 * <p>The cost this exists for: every rounded rectangle was one quad over its whole box, and the
 * fragment shader ran its distance field, its derivatives and its clip test on every pixel of it.
 * A one-point border around a card shaded all of the card to discard all but its edge, and a pass of
 * a partial repaint that crossed a card shaded all of the card to keep the pass. On a renderer that
 * runs fragment shaders on the CPU (Apple Software Renderer, a VM with no GPU) that was the frame:
 * at 2604 x 1850 a 560 x 300 pt card's border cost 52 ms and its fill 52 ms, and a form of 28 rows
 * of fields, checkboxes, bars and buttons 360 ms, of which 348 were rounded fills and borders;
 * drawn in pieces, 2.1 ms, 4.3 ms and 108 ms.
 *
 * <p>So these pin the structure, counted by the device itself with an occlusion query: the samples
 * a border shades lie along its ring, and the samples a surface shades under a pass lie in the pass.
 * And they pin the pixels against the quad, drawn by the same canvas with its pieces turned off.
 */
class RoundRectPiecesTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 140;
    private static final Color BACKGROUND = Color.rgb(0x203040);
    private static final Color FILL = Color.rgba(0x40C0E0, 0.6f);
    private static final Color OPAQUE_FILL = Color.rgb(0xE04020);
    private static final Color BORDER = Color.rgb(0xF0F0F0);
    private static final Color TRANSLUCENT_BORDER = Color.rgba(0xFFE000, 0.5f);

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;
    private UiRuntime runtime;
    private final List<ShapingRuler> rulers = new ArrayList<>();

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
        rulers.forEach(ShapingRuler::close);
        rulers.clear();
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
    void aBorderShadesOnlyItsRing() {
        // A 60 x 40 pt box at 2x, radius 6, one-point border: the quad over it is about
        // 124 x 84 device pixels, and the ring the border can colour is a few pixels deep on each
        // side plus the four 12-pixel corner squares.
        float scale = 2;
        long ring = samples(scale, c -> c.drawRoundRectBorder(20.25f, 10.5f, 60, 40, 6, 1, BORDER));
        long quad = (long) ((60 + 2) * scale) * (long) ((40 + 2) * scale);
        long band = 2 * (long) ((60 + 40) * scale) * 6 + 4 * 16 * 16;
        assertTrue(ring <= band, "the border shaded " + ring + " samples of a quad of about " + quad
                + ", more than the ring of " + band + " it can colour");

        long rect = samples(scale, c -> c.drawRect(20, 10, 60, 40, 2, BORDER));
        assertTrue(rect <= 2 * (long) ((60 + 40) * scale) * 7,
                "a square-cornered stroke shaded " + rect + " samples of a quad of about " + quad);
    }

    @Test
    void aPassShadesABorderedSurfaceOnlyInsideItself() {
        // A pass of 10 x 6 pt through the middle of a card, on whole device pixels as a partial
        // repaint's passes are; the card's fill and border are two shapes over 72 x 52 pt.
        float scale = 2;
        long inPass = samples(scale, c -> {
            c.clipRect(30, 30, 10, 6);
            c.fillRoundRectWithBorder(4, 4, 72, 52, 8, FILL, 1, BORDER);
        });
        long bound = 2L * (long) (10 * scale + 2) * (long) (6 * scale + 2);
        assertTrue(inPass <= bound, "the card shaded " + inPass + " samples for a pass of "
                + (long) (10 * scale * 6 * scale) + " (at most " + bound + " with a pixel either side)");

        assertSamePixels(scale, c -> {
            c.clipRect(30, 30, 10, 6);
            c.fillRoundRectWithBorder(4, 4, 72, 52, 8, FILL, 1, BORDER);
        }, "a card through a pass");
    }

    @Test
    void onASoftwareRendererALargeSolidInteriorIsFlatAndNothingElseIs() {
        // The path a software renderer takes, forced whatever this device is. At 2x a 72 x 52 pt
        // card's interior is about 136 x 96 device pixels, over the 64 x 64 the flat program has
        // to repay its draw calls; a 30-point square's is under it.
        canvas.flatInteriors = true;
        assertFlatInteriors(1, "a card's solid fill", c ->
                c.fillRoundRectWithBorder(4.25f, 4.5f, 72, 52, 8, FILL, 1, BORDER));
        assertFlatInteriors(0, "a small fill", c -> c.fillRoundRect(4, 4, 30, 30, 4, FILL));
        assertFlatInteriors(0, "a border alone", c -> c.drawRoundRectBorder(4, 4, 72, 52, 8, 1, BORDER));
        assertFlatInteriors(0, "a gradient", c -> c.fillRoundRect(4, 4, 72, 52, 8,
                new LinearGradient(4, 4, 76, 4, OPAQUE_FILL, FILL)));
        assertFlatInteriors(0, "under a rounded clip", c -> {
            c.clipRoundRect(RoundRect.of(0, 0, 90, 60, 6));
            c.fillRoundRect(4, 4, 72, 52, 8, FILL);
        });
        assertFlatInteriors(0, "rotated", c -> {
            c.rotate(0.05f);
            c.fillRoundRect(4, 4, 72, 52, 8, FILL);
        });

        for (float scale : new float[] {1.5f, 2f, 2.5f}) {
            assertSamePixels(scale, c -> {
                c.fillRoundRectWithBorder(4.25f, 4.5f, 72, 52, 8, FILL, 1, BORDER);
                c.setOpacity(0.7f);
                c.fillRoundRectWithBorder(30.75f, 20, 40, 30, 3, OPAQUE_FILL, 1, TRANSLUCENT_BORDER);
                c.clipRect(0, 0, 60.4f, 44.6f);
                c.fillRoundRect(2, 2, 70, 50, 12, FILL);
            }, "flat interiors at " + scale + "x");
        }

        canvas.flatInteriors = false;
        assertFlatInteriors(0, "a card's solid fill, on a GPU", c ->
                c.fillRoundRectWithBorder(4.25f, 4.5f, 72, 52, 8, FILL, 1, BORDER));
    }

    @Test
    void aDenseFormIsOneDrawCallOnAGpuAndFlatInteriorsOnASoftwareRenderer() {
        // The canvas reads its device when it is built, by the same rule this suite reads it by.
        assertEquals(HeadlessGl.isSoftware(), canvas.flatInteriors,
                "the canvas's reading of " + HeadlessGl.describe());

        Scene form = form();
        int formWidth = 600;
        int formHeight = 400;
        boolean[] software = {false, true};
        int[] drawCalls = new int[2];
        int[] flat = new int[2];
        byte[][] pixels = new byte[2][];
        for (int path = 0; path < 2; path++) {
            canvas.flatInteriors = software[path];
            int p = path;
            withTarget(formWidth, formHeight, () -> {
                canvas.beginFrame(formWidth, formHeight, 2);
                form.renderFrame(canvas, false, Float.NaN, 0);
                flat[p] = canvas.flatRectsThisFrame();
                canvas.endFrame();
                drawCalls[p] = canvas.drawCallsLastFrame();
                pixels[p] = read(formWidth, formHeight);
            });
        }

        assertEquals(1, drawCalls[0], "draw calls for a form of fields, checkboxes, bars and buttons "
                + "on a GPU, where the flat program's draw calls cost more than the pixels it spares");
        assertEquals(0, flat[0], "flat interiors on a GPU");
        assertTrue(flat[1] >= 5, "a software renderer draws the fields' interiors flat, " + flat[1]);
        assertTrue(drawCalls[1] > 1, "and pays a draw call for each switch, " + drawCalls[1]);
        int differing = 0;
        for (int i = 0; i < pixels[0].length; i++) {
            if (pixels[0][i] != pixels[1][i]) {
                differing++;
            }
        }
        assertEquals(0, differing, "bytes that differ between the two paths' frames");
    }

    /** Five rows of a field, a checkbox, a bar and a button, settled past every transition. */
    private Scene form() {
        Column list = new Column();
        list.gap(4);
        for (int i = 0; i < 5; i++) {
            Row row = new Row();
            row.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
            row.add(Expanded.of(new TextField().setText("Value " + i), 1));
            row.add(new Checkbox(Checkbox.Variant.BOX, "On").setChecked(i % 2 == 0));
            ProgressBar bar = new ProgressBar();
            bar.setProgress(i / 5f);
            row.add(Expanded.of(bar, 1));
            row.add(new Button("Open"));
            list.add(row);
        }
        AtomicLong now = new AtomicLong(1_000_000_000L);
        Scene scene = new Scene(new Padding(Insets.all(8), list), now::get);
        ShapingRuler ruler = new ShapingRuler(fonts);
        rulers.add(ruler);
        scene.setTextRuler(ruler);
        withTarget(600, 400, () -> {
            for (int i = 0; i < 20; i++) {
                now.addAndGet(50_000_000L);
                runtime.drain();
                canvas.beginFrame(600, 400, 2);
                scene.renderFrame(canvas, false, Float.NaN, 0);
                canvas.endFrame();
            }
        });
        return scene;
    }

    @Test
    void thePiecesDrawWhatTheQuadDrew() {
        // Fractional offsets, content scales that round a one-point border to one, two and three
        // device pixels, radii from square to large, translucent and gradient paints, a rounded
        // clip, a pass on and off the grid, and mirrored and anisotropic transforms.
        long pieces = 0;
        long quads = 0;
        for (float scale : new float[] {1f, 1.25f, 1.5f, 2f, 2.5f}) {
            for (float offset : new float[] {0f, .25f, .5f, .75f}) {
                float o = offset;
                Consumer<GlCanvas> scene = c -> {
                    float y = 2 + o;
                    for (float r : new float[] {0, 3, 8}) {
                        c.drawRoundRectBorder(2 + o, y, 30, 18, r, 1, BORDER);
                        c.fillRoundRectInsideBorder(36 + o, y, 30, 18, r, 1, FILL);
                        c.fillRoundRectWithBorder(70 + o, y, 30, 18, r, OPAQUE_FILL, 1, TRANSLUCENT_BORDER);
                        c.fillRoundRect(new RoundRect(104 + o, y, 30, 18, r, 0, r / 2, 2 * r),
                                new LinearGradient(104, y, 134, y + 18, OPAQUE_FILL, FILL));
                        c.drawRoundRect(138 + o, y, 30, 18, r, 3, TRANSLUCENT_BORDER);
                        y += 22;
                    }
                    c.save();
                    c.clipRoundRect(RoundRect.of(10, 70, 40, 20, 7));
                    c.fillRoundRectWithBorder(2 + o, 66 + o, 44, 22, 5, FILL, 1, BORDER);
                    c.restore();
                    c.save();
                    c.clipRect(60.3f, 70.6f, 20, 12);
                    c.fillRoundRectWithBorder(52 + o, 66 + o, 44, 22, 5, OPAQUE_FILL, 1, BORDER);
                    c.restore();
                    c.save();
                    c.translate(146 + o, 66);
                    c.scale(-1, 1);
                    c.fillRoundRectWithBorder(0, o, 44, 22, 5, FILL, 1, BORDER);
                    c.restore();
                    c.save();
                    c.translate(100, 66 + o);
                    c.scale(1.3f, 0.8f);
                    c.fillRoundRectWithBorder(o, 0, 30, 26, 4, FILL, 1, BORDER);
                    c.restore();
                };
                assertSamePixels(scale, scene, "scale " + scale + ", offset " + offset);
                pieces += samples(scale, scene);
                canvas.splitRoundRects = false;
                try {
                    quads += samples(scale, scene);
                } finally {
                    canvas.splitRoundRects = true;
                }
            }
        }
        assertTrue(pieces * 4 < quads * 3, "the pieces shaded " + pieces + " samples, the quads "
                + quads + ": no fewer than three quarters as many");
    }

    // --------------------------------------------------------------- helpers

    private void assertFlatInteriors(int expected, String what, Consumer<GlCanvas> paint) {
        int[] drawn = new int[1];
        withTarget(() -> {
            canvas.beginFrame(WIDTH, HEIGHT, 2);
            canvas.clear(BACKGROUND);
            canvas.save();
            paint.accept(canvas);
            canvas.restore();
            drawn[0] = canvas.flatRectsThisFrame();
            canvas.endFrame();
        });
        assertEquals(expected, drawn[0], what);
    }

    /**
     * Renders {@code paint} with the canvas's pieces and with its single quads, and fails with the
     * pixels that differ by more than rounding: a channel off by one where a piece's triangles
     * interpolate an attribute to the other side of a rounding step than the quad's did. Beyond
     * that, only the corner pixel of a square corner may move a little further, because the
     * distance field's gradient turns there, so the width of its antialiasing depends on which side
     * of the turn a neighbouring pixel's interpolated position lands.
     */
    private void assertSamePixels(float scale, Consumer<GlCanvas> paint, String what) {
        byte[] split = render(scale, paint);
        canvas.splitRoundRects = false;
        byte[] quad;
        try {
            quad = render(scale, paint);
        } finally {
            canvas.splitRoundRects = true;
        }
        int offByOne = 0;
        int further = 0;
        // A software rasteriser moves that corner pixel a little further than a GPU: llvmpipe
        // (Mesa 26, the CI's renderer) put one at 5 at 2.5x with every other pixel of the battery
        // within 1, where the GPU never passes 4.
        int cornerCap = HeadlessGl.isSoftware() ? 8 : 4;
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < split.length; i += 4) {
            int delta = 0;
            for (int channel = 0; channel < 4; channel++) {
                delta = Math.max(delta, Math.abs((split[i + channel] & 0xFF) - (quad[i + channel] & 0xFF)));
            }
            if (delta == 1) {
                offByOne++;
            } else if (delta > 1) {
                further++;
                if (further <= 6) {
                    where.append(String.format(" (%d,%d) %d", (i / 4) % WIDTH,
                            HEIGHT - 1 - (i / 4) / WIDTH, delta));
                }
                if (delta > cornerCap) {
                    fail(what + ": a pixel differs by " + delta + " from the quad's at"
                            + where + " on " + HeadlessGl.describe());
                }
            }
        }
        int pixels = WIDTH * HEIGHT;
        assertTrue(offByOne * 100 < pixels, what + ": " + offByOne + " pixels off by one of " + pixels);
        assertTrue(further <= 8, what + ": " + further + " pixels differ by 2 to " + cornerCap + ":" + where);
    }

    /** Renders one frame, cleared to the background, and reads it back. */
    private byte[] render(float scale, Consumer<GlCanvas> paint) {
        byte[][] out = new byte[1][];
        withTarget(() -> {
            frame(scale, paint);
            out[0] = read();
        });
        return out[0];
    }

    /** The samples the device shaded for one frame of {@code paint}, the clear excluded. */
    private long samples(float scale, Consumer<GlCanvas> paint) {
        long[] out = new long[1];
        withTarget(() -> {
            int query = GL33C.glGenQueries();
            try {
                canvas.beginFrame(WIDTH, HEIGHT, scale);
                canvas.clear(BACKGROUND);
                GL33C.glBeginQuery(GL33C.GL_SAMPLES_PASSED, query);
                canvas.save();
                paint.accept(canvas);
                canvas.restore();
                canvas.endFrame();
                GL33C.glEndQuery(GL33C.GL_SAMPLES_PASSED);
                out[0] = GL33C.glGetQueryObjecti64(query, GL33C.GL_QUERY_RESULT);
            } finally {
                GL33C.glDeleteQueries(query);
            }
        });
        return out[0];
    }

    private void frame(float scale, Consumer<GlCanvas> paint) {
        canvas.beginFrame(WIDTH, HEIGHT, scale);
        canvas.clear(BACKGROUND);
        canvas.save();
        paint.accept(canvas);
        canvas.restore();
        canvas.endFrame();
    }

    private void withTarget(Runnable body) {
        withTarget(WIDTH, HEIGHT, body);
    }

    private void withTarget(int width, int height, Runnable body) {
        int texture = GL33C.glGenTextures();
        int fbo = GL33C.glGenFramebuffers();
        int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        try {
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, width, height, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE,
                    GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER), "render target");
            GL33C.glViewport(0, 0, width, height);
            body.run();
        } finally {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glViewport(previousViewport[0], previousViewport[1],
                    previousViewport[2], previousViewport[3]);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
        }
    }

    private static byte[] read() {
        return read(WIDTH, HEIGHT);
    }

    private static byte[] read(int width, int height) {
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            GL33C.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            GL33C.glReadPixels(0, 0, width, height, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
            byte[] out = new byte[width * height * 4];
            pixels.get(out);
            return out;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}
