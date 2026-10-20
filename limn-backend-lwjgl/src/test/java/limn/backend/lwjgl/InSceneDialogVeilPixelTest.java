package limn.backend.lwjgl;

import limn.components.Dialog;
import limn.components.DisplayMode;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import limn.testing.TestPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An in-scene dialog lays its veil over the scene it opens in, and that scene may be a window
 * transparent round what it shows: a dialog in a window of its own, round its rounded card, with a
 * question opened in the scene over a field of the form. The veil was painted over the whole scene,
 * so it put the same dark squares at that window's corners that a locked window's veil had put
 * there; it is laid as that veil is now, keeping clear pixels clear, opaque ones as painting over
 * gave them, and darkening a translucent one and the desktop through it. Light and dark, opaque and
 * glass, on a whole frame and on a pass that repaints the corner, on the GPU and on a software
 * renderer.
 */
class InSceneDialogVeilPixelTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 400;
    private static final int H_PT = 300;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final float RADIUS = 16;

    private final long[] nanos = {TimeUnit.SECONDS.toNanos(1)};
    private HeadlessUi ui;
    private FontStore fonts;
    private TestPreferences preferences;

    @BeforeEach
    void install() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi(() -> nanos[0]);
        fonts = new FontStore();
        preferences = new TestPreferences().reduceMotion(true); // the veil at its depth at once
    }

    @AfterEach
    void uninstall() {
        if (preferences != null) {
            preferences.close();
        }
        if (fonts != null) {
            fonts.close();
        }
        if (ui != null) {
            ui.close();
        }
        Theme.setCurrent(Theme.light());
    }

    /** The form's card, filling its transparent window, nothing beyond its rounded corners. */
    private static final class FormCard extends Widget<FormCard> {
        private final Color color;

        FormCard(Color color) {
            this.color = color;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(W_PT, H_PT);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRoundRect(0, 0, width(), height(), RADIUS, color);
        }
    }

    @Test
    void onTheGpuAnInSceneDialogsVeilKeepsATransparentWindowsCornersClear() {
        everyLook();
    }

    @Test
    void onASoftwareRendererAnInSceneDialogsVeilKeepsATransparentWindowsCornersClear() {
        HeadlessGl.onSoftwareRenderer(this::everyLook);
    }

    private void everyLook() {
        for (boolean dark : new boolean[] {false, true}) {
            Theme.setCurrent(dark ? Theme.dark() : Theme.light());
            Color surface = Theme.current().surface();
            run((dark ? "dark" : "light") + ", opaque card", surface);
            run((dark ? "dark" : "light") + ", glass card", surface.withAlpha(0.6f));
        }
    }

    private void run(String look, Color card) {
        int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        int texture = GL33C.glGenTextures();
        int fbo = GL33C.glGenFramebuffers();
        GlCanvas canvas = new GlCanvas(fonts);
        try {
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            GL33C.glViewport(0, 0, WIDTH, HEIGHT);
            scenario(HeadlessGl.describe() + ", " + look, canvas, card);
        } finally {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
            canvas.dispose();
        }
    }

    private void scenario(String what, GlCanvas canvas, Color card) {
        HeadlessBackend backend = new HeadlessBackend(ui.runtime());
        HeadlessWindow window = backend.open("Edit customer", W_PT, H_PT);
        FormCard form = new FormCard(card);
        Scene scene = new Scene(form, () -> nanos[0]);
        scene.setTextRuler(new ShapingRuler(fonts));
        scene.setBackground(Color.TRANSPARENT);
        scene.bind(window);
        frame(scene, canvas, 0);
        byte[] open = read();

        new Dialog("Save changes?", "").addButton("Cancel", "cancel").addPrimaryButton("Save", "save")
                .setCancelResult("cancel").setDisplayMode(DisplayMode.IN_SCENE).show(scene);
        for (int i = 0; i < 6; i++) {
            nanos[0] += TimeUnit.MILLISECONDS.toNanos(30);
            ui.runtime().drain();
            frame(scene, canvas, 0);
        }
        float veil = Theme.current().scrim().a();
        check(what + ", whole frame", open, read(), veil);

        form.invalidate(0, 0, 40, 30); // a pass over the corner, the veil repainted in it
        nanos[0] += TimeUnit.MILLISECONDS.toNanos(30);
        frame(scene, canvas, 1);
        check(what + ", partial pass", open, read(), veil);
    }

    /**
     * The window's corner outside the form's card, and a point of the card far from the dialog's
     * card and its shadow: each the open pixel veiled, colour {@code C(1 - a)} (the veil is black),
     * coverage {@code A(1 + a)} up to full.
     */
    private static void check(String what, byte[] open, byte[] veiled, float veil) {
        int[][] probes = {{1, 1}, {(int) (30 * SCALE), (int) (10 * SCALE)}};
        for (int[] at : probes) {
            int[] before = pixel(open, at[0], at[1]);
            int[] after = pixel(veiled, at[0], at[1]);
            String where = what + " at " + at[0] + "," + at[1] + ": " + str(before) + " -> " + str(after);
            for (int c = 0; c < 3; c++) {
                assertEquals(before[c] * (1 - veil), after[c], 2, where + ", colour channel " + c);
            }
            assertEquals(Math.min(255, before[3] * (1 + veil)), after[3], 2,
                    where + ", coverage: the corner outside the card stays clear, not a dark square");
        }
    }

    private static void frame(Scene scene, GlCanvas canvas, int bufferAge) {
        canvas.beginFrame(WIDTH, HEIGHT, SCALE);
        scene.renderFrame(canvas, false, Float.NaN, bufferAge);
        canvas.endFrame();
    }

    private static String str(int[] p) {
        return p[0] + "," + p[1] + "," + p[2] + "," + p[3];
    }

    private static int[] pixel(byte[] image, int x, int y) {
        int at = (y * WIDTH + x) * 4;
        return new int[] {image[at] & 0xFF, image[at + 1] & 0xFF, image[at + 2] & 0xFF, image[at + 3] & 0xFF};
    }

    private static byte[] read() {
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
