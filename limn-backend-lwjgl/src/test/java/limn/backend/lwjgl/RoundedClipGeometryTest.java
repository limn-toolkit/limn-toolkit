package limn.backend.lwjgl;

import limn.concurrent.UiRuntime;
import limn.graphics.Color;
import limn.graphics.Rect;
import limn.graphics.RoundRect;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A rounded clip is rounded at its own corners and nowhere else, against a real device.
 *
 * <p>The defect this exists for: the canvas kept one clip, an axis-aligned rectangle and a radius,
 * and rounded the corners of that rectangle. Every rectangular clip intersected it, the one a
 * partial repaint puts around each pass included, so a pass that cut a surface drawn inside a
 * rounded clip grew rounded corners where it cut, and the background the pass had cleared showed
 * through them as dark arcs a pixel or two inside the surface's border. It was measured on a colour
 * picker's hue ramp, a pass that covered all of the ramp but its leftmost point and a half turning
 * the ramp's inner corner pixels from red to the window's background.
 *
 * <p>The invariant every case here states is the one a partial repaint depends on: <b>a
 * rectangular clip never changes a pixel inside itself</b>. What a pass draws is what the whole
 * frame drew there, and what a widget draws under a rectangle of its own inside a rounded clip is
 * what the rounded clip alone drew there. Content scale 2 and a pass edge on a half point, because
 * that is where it was seen.
 */
class RoundedClipGeometryTest {

    private static final float SCALE = 2f;
    /** The target in device pixels: 48 × 32 points. */
    private static final int WIDTH = 96;
    private static final int HEIGHT = 64;
    private static final Color BACKGROUND = Color.rgb(0x14181F);
    private static final Color SURFACE = Color.rgb(0xDC2313);
    /** What the target holds before a pass, so a pass that drew nothing cannot pass for one that did. */
    private static final Color STALE = Color.rgb(0x22CC44);
    /** The rounded surface, in points: device 8..88 × 8..56, a 12-pixel corner. */
    private static final RoundRect SURFACE_SHAPE = RoundRect.of(4, 4, 40, 24, 6);

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
    void aPassCuttingARoundedSurfaceDrawsWhatTheWholeFrameDrew() {
        // The pass starts a point and a half inside the surface's left edge (device x 11), well
        // inside its 12-pixel corners: exactly where the old clip put a false corner.
        Rect pass = new Rect(5.5f, 0, 42.5f, 32);
        byte[] whole = render(c -> fullFrame(c, this::roundedSurface));
        byte[] partial = render(c -> fullFrame(c, target -> target.clear(STALE)),
                c -> passFrame(c, pass, this::roundedSurface));

        assertSameInside(whole, partial, pass, "a pass cutting a rounded clip");
    }

    @Test
    void aPassCuttingARoundedSurfaceAcrossItsCornersOnEverySideDrawsWhatTheWholeFrameDrew() {
        // A pass inset from all four sides at once, so every corner is cut on both axes: the old
        // clip rounded all four of the pass's own corners, which lie inside the surface.
        Rect pass = new Rect(6f, 5.5f, 36f, 21.5f);
        byte[] whole = render(c -> fullFrame(c, this::roundedSurface));
        byte[] partial = render(c -> fullFrame(c, target -> target.clear(STALE)),
                c -> passFrame(c, pass, this::roundedSurface));

        assertSameInside(whole, partial, pass, "a pass inside a rounded clip on every side");
    }

    @Test
    void aRectangleClippedInsideARoundedClipCutsItStraight() {
        // The other order, and not a repaint at all: a widget clipping a rectangle of its own
        // inside a rounded clip (a scrolled band, a caret's cell). Inside that rectangle it draws
        // what the rounded clip alone drew; rounding the rectangle's corners was the same defect
        // in a whole frame.
        Rect cut = new Rect(5.5f, 5f, 30f, 30f);
        byte[] rounded = render(c -> fullFrame(c, this::roundedSurface));
        byte[] cutStraight = render(c -> fullFrame(c, target -> {
            target.save();
            target.clipRoundRect(SURFACE_SHAPE);
            target.clipRect(cut);
            target.fillRect(0, 0, 48, 32, SURFACE);
            target.restore();
        }));

        assertSameInside(rounded, cutStraight, cut, "a rectangle clipped inside a rounded clip");
    }

    @Test
    void aRoundedClipInsideAnotherDrawsExactlyTheInnerOne() {
        // The nested rule, pinned where it was already right and must stay right: an inner rounded
        // rect wholly inside an outer one clips to the inner shape, its own radius at its own
        // corners, whatever radius the outer one had.
        RoundRect inner = RoundRect.of(10, 8, 28, 16, 3);
        byte[] alone = render(c -> fullFrame(c, target -> {
            target.save();
            target.clipRoundRect(inner);
            target.fillRect(0, 0, 48, 32, SURFACE);
            target.restore();
        }));
        byte[] nested = render(c -> fullFrame(c, target -> {
            target.save();
            target.clipRoundRect(SURFACE_SHAPE);
            target.clipRoundRect(inner);
            target.fillRect(0, 0, 48, 32, SURFACE);
            target.restore();
        }));

        assertSameInside(alone, nested, new Rect(0, 0, 48, 32), "a rounded clip inside another");
    }

    @Test
    void aSecondRoundedClipKeepsTheFirstOnesRectangle() {
        // Nested rounded clips intersect their rectangles: the surface's own edge still bounds a
        // rounded clip that reaches past it, so nothing is drawn left of the surface.
        byte[] nested = render(c -> fullFrame(c, target -> {
            target.save();
            target.clipRoundRect(SURFACE_SHAPE);
            target.clipRoundRect(RoundRect.of(0, 0, 30, 32, 2));
            target.fillRect(0, 0, 48, 32, SURFACE);
            target.restore();
        }));

        assertPixel(nested, 4, 30, BACKGROUND, "left of the outer rounded clip");
        assertPixel(nested, 30, 30, SURFACE, "inside both");
        assertPixel(nested, 70, 30, BACKGROUND, "right of the inner rounded clip");
    }

    @Test
    void theClipBoundsAreStillEveryClipIntersected() {
        // What a caller culls against is unchanged: the box every clip has left, rounded or not,
        // pass or not.
        render(c -> {
            c.beginFrame(WIDTH, HEIGHT, SCALE);
            c.save();
            c.clipRect(5.5f, 0, 42.5f, 32);
            c.clipRoundRect(SURFACE_SHAPE);
            Rect bounds = c.clipBounds();
            c.clipRect(0, 10, 48, 4);
            Rect band = c.clipBounds();
            c.restore();
            c.endFrame();
            assertEquals(new Rect(5.5f, 4, 38.5f, 24), bounds, "pass and rounded rect intersected");
            assertEquals(new Rect(5.5f, 10, 38.5f, 4), band, "and a rectangle after them");
        });
    }

    // ------------------------------------------------------------------ scene

    private void roundedSurface(GlCanvas target) {
        target.save();
        target.clipRoundRect(SURFACE_SHAPE);
        target.fillRect(0, 0, 48, 32, SURFACE);
        target.restore();
    }

    private static void fullFrame(GlCanvas target, Consumer<GlCanvas> paint) {
        target.beginFrame(WIDTH, HEIGHT, SCALE);
        target.clear(BACKGROUND);
        paint.accept(target);
        target.endFrame();
    }

    /** One pass of a partial repaint, the calls the scene makes for each damage rectangle. */
    private static void passFrame(GlCanvas target, Rect pass, Consumer<GlCanvas> paint) {
        target.beginFrame(WIDTH, HEIGHT, SCALE);
        target.damageScissorHint(pass.x(), pass.y(), pass.width(), pass.height());
        target.save();
        target.clipRect(pass);
        target.clearRect(pass.x(), pass.y(), pass.width(), pass.height(), BACKGROUND);
        paint.accept(target);
        target.restore();
        target.endFrame();
    }

    // --------------------------------------------------------------- readback

    private static void assertSameInside(byte[] expected, byte[] actual, Rect region, String what) {
        int x0 = Math.round(region.x() * SCALE);
        int y0 = Math.round(region.y() * SCALE);
        int x1 = Math.min(WIDTH, Math.round((region.x() + region.width()) * SCALE));
        int y1 = Math.min(HEIGHT, Math.round((region.y() + region.height()) * SCALE));
        StringBuilder differences = new StringBuilder();
        int count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int at = (y * WIDTH + x) * 4;
                for (int channel = 0; channel < 4; channel++) {
                    int e = expected[at + channel] & 0xFF;
                    int a = actual[at + channel] & 0xFF;
                    if (Math.abs(e - a) > 1) {
                        if (count < 8) {
                            differences.append(String.format(" (%d,%d) %s vs %s;", x, y,
                                    hex(expected, at), hex(actual, at)));
                        }
                        count++;
                        break;
                    }
                }
            }
        }
        if (count > 0) {
            fail(what + ": " + count + " device pixel(s) inside it differ by more than 1 from the "
                    + "reference:" + differences);
        }
    }

    private static void assertPixel(byte[] pixels, int x, int y, Color color, String what) {
        int at = (y * WIDTH + x) * 4;
        int[] want = {Math.round(color.r() * 255), Math.round(color.g() * 255),
                Math.round(color.b() * 255)};
        for (int channel = 0; channel < 3; channel++) {
            assertTrue(Math.abs((pixels[at + channel] & 0xFF) - want[channel]) <= 1,
                    what + " at " + x + "," + y + ": " + hex(pixels, at));
        }
    }

    private static String hex(byte[] pixels, int at) {
        return String.format("%02x%02x%02x", pixels[at] & 0xFF, pixels[at + 1] & 0xFF,
                pixels[at + 2] & 0xFF);
    }

    /** Runs the frames in order into one fresh target and reads it back top-down. */
    @SafeVarargs
    private byte[] render(Consumer<GlCanvas>... frames) {
        int texture = Gl.glGenTextures();
        int fbo = Gl.glGenFramebuffers();
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
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE,
                    Gl.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER), "render target");
            Gl.glViewport(0, 0, WIDTH, HEIGHT);
            for (Consumer<GlCanvas> frame : frames) {
                frame.accept(canvas);
            }
            return readTopDown();
        } finally {
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            Gl.glViewport(previousViewport[0], previousViewport[1],
                    previousViewport[2], previousViewport[3]);
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
        }
    }

    private static byte[] readTopDown() {
        ByteBuffer pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        try {
            Gl.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            Gl.glReadPixels(0, 0, WIDTH, HEIGHT, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
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
