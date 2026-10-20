package limn.backend.lwjgl;

import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.testing.HeadlessUi;
import limn.testing.StubWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window that a modal dialog locks is dimmed by its scene, and the dim must darken only what the
 * window shows. A dialog's own window is transparent round its rounded card, and the dim was a
 * black rectangle over the whole framebuffer: where the window had nothing it put half-opaque
 * black, so a dialog with a second one open over it showed four dark squares at the corners of its
 * card, outside its rounded edge (the owner's review of 2026-10-20, the customer's form with the
 * question over it). The corners must stay as clear as they were, and the card dim by the veil's
 * share, on a whole frame and on a pass that repaints part of one, on the GPU and on a software
 * renderer. An opaque window dims exactly as before.
 */
class BlockedWindowVeilPixelTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 200;
    private static final int H_PT = 120;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final float RADIUS = 16;
    private static final Color CARD = Color.rgb(0xF2F2F2);
    /** The veil's alpha once it has settled, the toolkit's constant for a native modal's dim. */
    private static final float VEIL = 0.5f;

    private final long[] nanos = {TimeUnit.SECONDS.toNanos(1)};
    private HeadlessUi ui;
    private FontStore fonts;

    @BeforeEach
    void install() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi(() -> nanos[0]);
        fonts = new FontStore();
        Theme.setCurrent(Theme.light());
    }

    @AfterEach
    void uninstall() {
        if (fonts != null) {
            fonts.close();
        }
        if (ui != null) {
            ui.close();
        }
    }

    /** A dialog's card: a rounded opaque surface filling its window, nothing beyond its corners. */
    private static final class CardOnly extends Widget<CardOnly> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(W_PT, H_PT);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRoundRect(0, 0, width(), height(), RADIUS, CARD);
        }
    }

    @Test
    void onTheGpuTheCornersOfALockedDialogsCardStayClearAndTheCardDims() {
        new Target().run(true);
    }

    @Test
    void onASoftwareRendererTheCornersOfALockedDialogsCardStayClearAndTheCardDims() {
        HeadlessGl.onSoftwareRenderer(() -> new Target().run(true));
    }

    @Test
    void anOpaqueLockedWindowDimsAsBeforeEverywhere() {
        new Target().run(false);
    }

    /** A framebuffer of its own in the current context, and the scenario drawn into it. */
    private final class Target {
        private final GlCanvas canvas = new GlCanvas(fonts);
        private final int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        private final int[] previousViewport = new int[4];
        private int texture;
        private int fbo;

        void run(boolean transparent) {
            GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
            texture = GL33C.glGenTextures();
            fbo = GL33C.glGenFramebuffers();
            try {
                GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
                GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
                GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                        GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
                GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                        GL33C.GL_TEXTURE_2D, texture, 0);
                GL33C.glViewport(0, 0, WIDTH, HEIGHT);
                scenario(transparent);
            } finally {
                GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
                GL33C.glViewport(previousViewport[0], previousViewport[1], previousViewport[2],
                        previousViewport[3]);
                GL33C.glDeleteFramebuffers(fbo);
                GL33C.glDeleteTextures(texture);
                canvas.dispose();
            }
        }

        private void scenario(boolean transparent) {
            String device = HeadlessGl.describe();
            CardOnly card = new CardOnly();
            Scene scene = new Scene(card, () -> nanos[0]);
            scene.setTextRuler(new ShapingRuler(fonts));
            Color background = transparent ? Color.TRANSPARENT : Color.rgb(0x3060C0);
            scene.setBackground(background);
            StubWindow window = new StubWindow();
            scene.bind(window);
            frame(scene, 0);
            int[] corner = pixel(read(), 1, 1);
            int[] middle = pixel(read(), WIDTH / 2, HEIGHT / 2);
            if (transparent) {
                assertEquals(0, corner[3], device + ": the corner outside the card is clear to begin with");
            }

            window.modalBlocked = true; // a dialog opened over this one
            for (int i = 0; i < 12; i++) {
                nanos[0] += TimeUnit.MILLISECONDS.toNanos(30);
                frame(scene, 0); // the veil retints the whole window while it moves
            }
            byte[] whole = read();
            check(device + ", whole frame", whole, corner, middle, transparent);

            // A repaint of part of the window, which clears its rectangle and paints it again, the
            // veil included: the card's top left corner, so the pass covers what must stay clear.
            card.invalidate(0, 0, 40, 30);
            nanos[0] += TimeUnit.MILLISECONDS.toNanos(30);
            frame(scene, 1);
            check(device + ", partial pass", read(), corner, middle, transparent);
        }

        private void check(String what, byte[] image, int[] cornerBefore, int[] middleBefore,
                           boolean transparent) {
            int[] corner = pixel(image, 1, 1);
            int[] middle = pixel(image, WIDTH / 2, HEIGHT / 2);
            if (transparent) {
                assertTrue(corner[3] <= 1 && corner[0] <= 1 && corner[1] <= 1 && corner[2] <= 1,
                        what + ": outside the rounded card the locked window stays clear, not a dark square: "
                                + str(corner));
            } else {
                for (int c = 0; c < 3; c++) {
                    assertEquals(cornerBefore[c] * (1 - VEIL), corner[c], 2,
                            what + ": an opaque window's corner dims by the veil, channel " + c);
                }
                assertEquals(255, corner[3], what + ": and stays opaque");
            }
            for (int c = 0; c < 3; c++) {
                assertEquals(middleBefore[c] * (1 - VEIL), middle[c], 2,
                        what + ": the card dims by the veil, channel " + c + " " + str(middleBefore)
                                + " -> " + str(middle));
            }
            assertEquals(255, middle[3], what + ": the card stays opaque");
        }

        private void frame(Scene scene, int bufferAge) {
            canvas.beginFrame(WIDTH, HEIGHT, SCALE);
            scene.renderFrame(canvas, false, Float.NaN, bufferAge);
            canvas.endFrame();
        }
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
