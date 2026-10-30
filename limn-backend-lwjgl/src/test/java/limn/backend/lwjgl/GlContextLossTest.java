package limn.backend.lwjgl;

import limn.concurrent.UiRuntime;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.testing.HeadlessUi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A canvas whose context is gone (ADR 082): it forgets every GL object without touching GL, and in
 * a new context draws the same pixels it drew before.
 *
 * <p>The new context is a real one, a second hidden window's, in which none of the old numbers name
 * anything: a stale texture or buffer id bound there either raises an error or, once the new context
 * has made objects of its own, samples an unrelated one, which the pixel comparison catches.
 */
class GlContextLossTest {

    private static final int SIZE = 96;
    private static final Font FONT = Font.of(16);

    private GlCanvas canvas;
    private FontStore fonts;
    private ShapingRuler ruler;
    private HeadlessUi ui;
    private UiRuntime runtime;
    private long second;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeWindow();
        HeadlessGl.assumeDesktop("the second context is made with the desktop profile's hints");
        ui = new HeadlessUi();
        runtime = ui.runtime();
        fonts = new FontStore();
        canvas = new GlCanvas(fonts);
        ruler = new ShapingRuler(fonts);
        limn.graphics.TextRulers.install(ruler);
    }

    @AfterEach
    void closeContext() {
        if (ruler != null) {
            limn.graphics.TextRulers.uninstall(ruler);
        }
        if (canvas != null) {
            canvas.dispose(); // in whichever context is current: the second, when a test made one
        }
        if (second != 0) {
            GLFW.glfwMakeContextCurrent(0);
            GLFW.glfwDestroyWindow(second);
        }
        if (HeadlessGl.window() != 0) {
            GLFW.glfwMakeContextCurrent(HeadlessGl.window());
            Gl.api().setCapabilities(HeadlessGl.capabilities());
        }
        if (fonts != null) {
            fonts.close();
        }
        if (runtime != null) {
            ui.close();
        }
    }

    /** What every kind of object the canvas makes is drawn from: glyphs, an image, a layer. */
    private void scene(GlCanvas c) {
        c.fillRect(0, 0, SIZE, SIZE, Color.rgb(0x203040));
        c.fillRoundRect(6, 6, 50, 30, 8, Color.rgb(0xE0A030));
        c.drawText("Lost", 8, 60, FONT, Color.WHITE);
        c.drawImage(checker(), 60, 8, 28, 28);
        c.saveLayer(40, 40, 50, 50, 0.5f);
        c.fillRect(40, 40, 50, 50, Color.rgb(0x30C060));
        c.restore();
    }

    @Test
    void aNewContextDrawsWhatTheLostOneDrew() {
        byte[] before = render();
        assertTrue(distinctColours(before) > 4, "the scene has to draw something to compare");

        second = makeSecondContext();
        canvas.contextLost();
        byte[] after = render();

        assertEquals(GL33C.GL_NO_ERROR, Gl.glGetError(), "no stale name reached the new context");
        assertArrayEquals(before, after, "the new context draws the very pixels the lost one drew");
    }

    @Test
    void forgettingTouchesNoGl() {
        render(); // every kind of object made once
        GLFW.glfwMakeContextCurrent(0);
        Gl.api().setCapabilities(null);
        // With no context and no capabilities any GL call throws; forgetting must make none.
        assertDoesNotThrow(canvas::contextLost);
        canvas = null; // its objects are in the first context, forgotten; nothing left to dispose
    }

    @Test
    void aSecondLossIsAsGoodAsTheFirst() {
        byte[] before = render();
        second = makeSecondContext();
        canvas.contextLost();
        render();
        GLFW.glfwMakeContextCurrent(0);
        GLFW.glfwDestroyWindow(second);
        second = makeSecondContext();
        canvas.contextLost();
        assertArrayEquals(before, render(), "the canvas comes back from as many losses as it meets");
    }

    /** A second hidden window's context, current; for the other loss tests. */
    static long makeSecondContextForTests() {
        return makeSecondContext();
    }

    private static long makeSecondContext() {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(16, 16, "limn context loss", 0L, 0L);
        assertNotEquals(0L, window, "a second context for the loss");
        GLFW.glfwMakeContextCurrent(window);
        Gl.api().setCapabilities(Gl.api().createCapabilities());
        return window;
    }

    private static Image checker() {
        int n = 8;
        byte[] rgba = new byte[n * n * 4];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int i = (y * n + x) * 4;
                boolean dark = ((x + y) & 1) == 0;
                rgba[i] = (byte) (dark ? 0x20 : 0xF0);
                rgba[i + 1] = (byte) (dark ? 0x40 : 0x30);
                rgba[i + 2] = (byte) (dark ? 0xC0 : 0x30);
                rgba[i + 3] = (byte) 0xFF;
            }
        }
        return new Image(n, n, rgba);
    }

    /** One frame into an offscreen texture of the current context, read back top-down. */
    private byte[] render() {
        int texture = Gl.glGenTextures();
        int fbo = Gl.glGenFramebuffers();
        int previousFbo = Gl.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        Gl.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        try {
            Gl.glActiveTexture(GL33C.GL_TEXTURE0);
            Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, SIZE, SIZE, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
            Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            Gl.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            Gl.glViewport(0, 0, SIZE, SIZE);
            canvas.beginFrame(SIZE, SIZE, 1f);
            scene(canvas);
            canvas.endFrame();
            return readTopDown();
        } finally {
            // The shared test context's state as it was: later tests read the window's size off the
            // viewport.
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            Gl.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
        }
    }

    private static byte[] readTopDown() {
        ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        try {
            Gl.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            Gl.glReadPixels(0, 0, SIZE, SIZE, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
            byte[] out = new byte[SIZE * SIZE * 4];
            int stride = SIZE * 4;
            for (int row = 0; row < SIZE; row++) {
                pixels.position((SIZE - 1 - row) * stride);
                pixels.get(out, row * stride, stride);
            }
            return out;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static int distinctColours(byte[] rgba) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < rgba.length; i += 4) {
            seen.add(((rgba[i] & 0xff) << 16) | ((rgba[i + 1] & 0xff) << 8) | (rgba[i + 2] & 0xff));
        }
        return seen.size();
    }
}
