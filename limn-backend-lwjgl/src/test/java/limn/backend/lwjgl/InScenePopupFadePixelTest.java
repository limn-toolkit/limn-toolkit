package limn.backend.lwjgl;

import limn.components.ComboBox;
import limn.components.DisplayMode;
import limn.components.SizeTokens;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.HeadlessUi;
import limn.testing.StubWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A combo box's list fading in, in the scene, against a real device: halfway through, a pixel of
 * its highlighted row is the opaque list's pixel mixed with what is under the list, by the same
 * share as a plain row's, which is what one picture means (ADR 071 §2.7). Drawn per draw, as the
 * list was, the panel under the highlight showed through it, so the highlighted row came out
 * darker than the mix by as much as the panel differs from what is beneath.
 *
 * <p>What is under the list is a bright box, so that the panel, which is dark, differs from it by a
 * lot, and the share a plain row's pixel gives is read back from that pixel itself.
 */
class InScenePopupFadePixelTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 240;
    private static final int H_PT = 220;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final Color UNDER = Color.rgb(0xF5D547);

    private final long[] nanos = {TimeUnit.SECONDS.toNanos(1)};
    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;
    private int texture;
    private int fbo;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi(() -> nanos[0]);
        fonts = new FontStore();
        canvas = new GlCanvas(fonts);
        Theme.setCurrent(Theme.dark());
        texture = GL33C.glGenTextures();
        fbo = GL33C.glGenFramebuffers();
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
        GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                GL33C.GL_TEXTURE_2D, texture, 0);
        GL33C.glViewport(0, 0, WIDTH, HEIGHT);
    }

    @AfterEach
    void closeContext() {
        if (canvas != null) {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
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

    /** The bright box the list drops over. */
    private static final class Under extends Widget<Under> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(W_PT, 180);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRect(0, 0, width(), height(), UNDER);
        }
    }

    @Test
    void halfwayThroughItsFadeTheHighlightedRowIsTheOpaqueRowMixedAsAPlainOneIs() {
        ComboBox<String> combo = new ComboBox<>(List.of("one", "two", "three", "four"));
        combo.setDisplayMode(DisplayMode.IN_SCENE);
        Column root = new Column();
        root.add(combo);
        root.add(new Under());
        Scene scene = new Scene(root, () -> nanos[0]);
        scene.setTextRuler(new ShapingRuler(fonts));
        scene.bind(new StubWindow());
        frame(scene);

        combo.open();
        frame(scene); // the fade's first frame, which moves nothing
        nanos[0] += TimeUnit.MILLISECONDS.toNanos(70);
        frame(scene);
        byte[] halfway = read();
        for (int i = 0; i < 20; i++) {
            nanos[0] += TimeUnit.MILLISECONDS.toNanos(50);
            frame(scene);
        }
        byte[] opaque = read();

        SizeTokens t = Theme.current().tokensFor(combo);
        float listTop = combo.localToSceneY() + combo.height() + t.popupGap() + t.popupPadV();
        int x = (int) ((combo.localToSceneX() + combo.width() * 0.8f) * SCALE);
        int highlighted = (int) ((listTop + 0.5f * t.popupItemHeight()) * SCALE); // the selection, row 0
        int plain = (int) ((listTop + 2.5f * t.popupItemHeight()) * SCALE);

        int[] plainOpaque = pixel(opaque, x, plain);
        int[] plainHalf = pixel(halfway, x, plain);
        int[] litOpaque = pixel(opaque, x, highlighted);
        int[] litHalf = pixel(halfway, x, highlighted);
        assertTrue(plainOpaque[0] < 120 && litOpaque[2] != plainOpaque[2],
                "the case needs a dark panel and a highlight that differs from it: plain "
                        + str(plainOpaque) + ", highlighted " + str(litOpaque));
        // The share the fade has reached, read from the plain row's red, which the bright box under
        // it is furthest from.
        float under = 255 * UNDER.r();
        float share = (plainHalf[0] - under) / (plainOpaque[0] - under);
        assertTrue(share > 0.1f && share < 0.9f, "halfway through the fade: " + share);
        for (int channel = 0; channel < 3; channel++) {
            float u = 255 * (channel == 0 ? UNDER.r() : channel == 1 ? UNDER.g() : UNDER.b());
            float expected = share * litOpaque[channel] + (1 - share) * u;
            assertEquals(expected, litHalf[channel], 3, "channel " + channel + " of the highlighted row at "
                    + share + ": the opaque row " + str(litOpaque) + " mixed with the box beneath, "
                    + "as the plain row " + str(plainOpaque) + " -> " + str(plainHalf) + " is");
        }
    }

    private void frame(Scene scene) {
        canvas.beginFrame(WIDTH, HEIGHT, SCALE);
        scene.requestRender();
        scene.renderFrame(canvas, false, Float.NaN, 0);
        canvas.endFrame();
    }

    private static String str(int[] p) {
        return p[0] + "," + p[1] + "," + p[2];
    }

    private static int[] pixel(byte[] image, int x, int y) {
        int at = (y * WIDTH + x) * 4;
        return new int[] {image[at] & 0xFF, image[at + 1] & 0xFF, image[at + 2] & 0xFF};
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
