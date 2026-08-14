package limn.backend.lwjgl;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.Image;
import limn.graphics.ScenePixels;
import limn.render3d.ColorSpace;
import limn.render3d.RenderTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a render target back, on a real device: the row order, the un-premultiply, the display
 * transform and the MSAA resolve. All four are invisible in a screenshot of a symmetric scene and
 * each of them is a plausible wrong edit, so each gets an asymmetric input that fails loudly.
 */
class GlSurfaceReadbackTest {

    private static final int WIDTH = 8;
    private static final int HEIGHT = 6;

    private GlCanvas canvas;
    private FontStore fonts;
    private ExecutorService workers;
    private UiRuntime runtime;

    @BeforeEach
    void openFrame() {
        HeadlessGl.assumeAvailable();
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        fonts = new FontStore();
        canvas = new GlCanvas(fonts);
        canvas.beginFrame(WIDTH, HEIGHT, 1f);
    }

    @AfterEach
    void closeFrame() {
        if (canvas != null) {
            canvas.endFrame();
            canvas.dispose();
            canvas = null;
        }
        if (fonts != null) {
            fonts.close();
            fonts = null;
        }
        if (runtime != null) {
            Ui.uninstall(runtime);
            workers.shutdownNow();
            runtime = null;
        }
    }

    @Test
    void sceneReadbackReturnsTopDownRowsOfWhatThePassWrote() {
        GlRenderTarget target = target(1);
        try {
            // GL's origin is bottom-left, so this paints the LAST rows of the image.
            fill(target, 0.25f, 0f, 0f, 1f);
            scissorFill(target, 0, 0, WIDTH, HEIGHT / 2, 0.75f, 0f, 0f, 1f);

            ScenePixels scene = target.readSceneReferred();

            assertEquals(WIDTH, scene.width());
            assertEquals(HEIGHT, scene.height());
            assertEquals(0.25f, scene.channel(0, 0, 0), 1e-3f, "row 0 is the top one");
            assertEquals(0.75f, scene.channel(0, HEIGHT - 1, 0), 1e-3f,
                    "the last row is the one GL wrote first");
        } finally {
            target.dispose();
        }
    }

    @Test
    void sceneReadbackKeepsValuesAboveOne() {
        GlRenderTarget target = target(1);
        try {
            fill(target, 4f, 2f, 0f, 1f);

            ScenePixels scene = target.readSceneReferred();

            assertEquals(4f, scene.channel(3, 3, 0), 1e-2f,
                    "scene-referred light is not clamped on the way out; that is why it is float");
            assertEquals(2f, scene.channel(3, 3, 1), 1e-2f);
        } finally {
            target.dispose();
        }
    }

    @Test
    void displayReadbackAppliesTheTransformOnceAndUnpremultiplies() {
        GlRenderTarget target = target(1);
        try {
            // Half-covered pixel: premultiplied linear 0.3 over alpha 0.5 is straight linear 0.6.
            fill(target, 0.3f, 0.15f, 0.05f, 0.5f);

            Image image = target.readDisplayReferred();
            int at = (2 * WIDTH + 2) * 4;

            assertEquals(expected(0.6f), image.pixels()[at] & 0xFF, 1,
                    "colour must be un-premultiplied before the (non-linear) tonemap");
            assertEquals(expected(0.3f), image.pixels()[at + 1] & 0xFF, 1);
            assertEquals(128, image.pixels()[at + 3] & 0xFF, 1, "alpha stays as it was, straight");
        } finally {
            target.dispose();
        }
    }

    @Test
    void displayReadbackClearsColourWhereNothingWasDrawn() {
        GlRenderTarget target = target(1);
        try {
            fill(target, 0f, 0f, 0f, 0f);

            Image image = target.readDisplayReferred();

            for (byte channel : image.pixels()) {
                assertEquals(0, channel & 0xFF, "alpha 0 has no colour to recover");
            }
        } finally {
            target.dispose();
        }
    }

    @Test
    void multisampledTargetIsResolvedBeforeItIsRead() {
        GlRenderTarget target = target(4);
        try {
            assertTrue(target.samples() > 1, "the device refused MSAA; nothing to test here");
            fill(target, 0.5f, 0.25f, 0.125f, 1f);

            ScenePixels scene = target.readSceneReferred();

            assertEquals(0.5f, scene.channel(1, 1, 0), 1e-3f);
            assertEquals(0.25f, scene.channel(1, 1, 1), 1e-3f);
        } finally {
            target.dispose();
        }
    }

    @Test
    void subRectangleReadsTheRectangleAskedFor() {
        GlRenderTarget target = target(1);
        try {
            fill(target, 0.25f, 0f, 0f, 1f);
            scissorFill(target, 0, 0, WIDTH, HEIGHT / 2, 0.75f, 0f, 0f, 1f);

            ScenePixels bottom = target.readSceneReferred(0, HEIGHT / 2, WIDTH, HEIGHT / 2);

            assertEquals(HEIGHT / 2, bottom.height());
            assertEquals(0.75f, bottom.channel(0, 0, 0), 1e-3f);
        } finally {
            target.dispose();
        }
    }

    @Test
    void aRectangleOutsideTheSurfaceIsRefused() {
        GlRenderTarget target = target(1);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> target.readSceneReferred(1, 0, WIDTH, HEIGHT));
            assertThrows(IllegalArgumentException.class,
                    () -> target.readDisplayReferred(0, 0, 0, HEIGHT));
        } finally {
            target.dispose();
        }
    }

    @Test
    void readingADisposedTargetFails() {
        GlRenderTarget target = target(1);
        target.dispose();

        assertThrows(IllegalStateException.class, target::readSceneReferred);
    }

    /** What the composite would put on screen for one straight linear channel at exposure 1. */
    private static int expected(float straightLinear) {
        return Math.round(ColorSpace.displayTransform(straightLinear, 1f) * 255f);
    }

    private GlRenderTarget target(int samples) {
        RenderTarget created = canvas.gl3d().createTarget(WIDTH, HEIGHT, samples);
        return (GlRenderTarget) created;
    }

    /** Writes premultiplied linear RGBA into the target, the way a pass does. */
    private static void fill(GlRenderTarget target, float r, float g, float b, float a) {
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, target.renderFramebuffer());
        GL33C.glDisable(GL33C.GL_SCISSOR_TEST);
        GL33C.glClearColor(r, g, b, a);
        GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT);
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0);
    }

    /** Same, restricted to a GL-space rectangle (origin bottom-left). */
    private static void scissorFill(GlRenderTarget target, int x, int y, int w, int h,
                                    float r, float g, float b, float a) {
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, target.renderFramebuffer());
        GL33C.glEnable(GL33C.GL_SCISSOR_TEST);
        GL33C.glScissor(x, y, w, h);
        GL33C.glClearColor(r, g, b, a);
        GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT);
        GL33C.glDisable(GL33C.GL_SCISSOR_TEST);
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0);
    }
}
