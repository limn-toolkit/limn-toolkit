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

/**
 * A window that a modal dialog locks is dimmed by its scene, and the dim must darken what the
 * window shows and nothing it does not. A dialog's own window is transparent round its rounded
 * card, and the dim was a black rectangle over the whole framebuffer: where the window had nothing
 * it put half-opaque black, so a dialog with a second one open over it showed four dark squares at
 * the corners of its card, outside its rounded edge (the owner's review of 2026-10-20, the
 * customer's form with the question over it). The corners must stay as clear as they were, and the
 * card dim by the veil's share. Multiplying the veil kept the corners clear but also left a
 * translucent pixel's coverage alone, so the desktop seen through a translucent locked window was
 * not dimmed at all: a pixel of coverage {@code A} must reach {@code A(1 + a)} under a veil of
 * {@code a}, its colour {@code C(1 - a)}, which keeps 0 at 0 and 1 at 1. On a whole frame and on
 * passes that repaint part of one, again and again, with nothing outside them touched; light and
 * dark, opaque, translucent and clear; on the GPU and on a software renderer. An opaque window dims
 * exactly as before.
 */
class BlockedWindowVeilPixelTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 200;
    private static final int H_PT = 120;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final float RADIUS = 16;
    private static final Color LIGHT = Color.rgb(0xF2F2F2);
    private static final Color DARK = Color.rgb(0x2B2D31);
    /** A glassy card: what a translucent tool window shows, the desktop seen through it. */
    private static final float GLASS = 0.6f;
    /** The veil's alpha once it has settled, the toolkit's constant for a native modal's dim. */
    private static final float VEIL = 0.5f;
    /** The part of the window a partial pass repaints, in points: the card's top left corner. */
    private static final int PASS_W_PT = 40;
    private static final int PASS_H_PT = 30;

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

    /** A dialog's card: a rounded surface filling its window, nothing beyond its corners. */
    private static final class CardOnly extends Widget<CardOnly> {
        private final Color color;

        CardOnly(Color color) {
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

    /** What the locked window shows: its background and its card. */
    private record Look(String name, Color background, Color card) {
    }

    private static final Look[] TRANSPARENT_WINDOWS = {
        new Look("light opaque card", Color.TRANSPARENT, LIGHT),
        new Look("dark opaque card", Color.TRANSPARENT, DARK),
        new Look("light glass card", Color.TRANSPARENT, LIGHT.withAlpha(GLASS)),
        new Look("dark glass card", Color.TRANSPARENT, DARK.withAlpha(GLASS)),
        new Look("light glass on light glass", LIGHT.withAlpha(0.3f), LIGHT.withAlpha(GLASS)),
    };

    @Test
    void onTheGpuTheCornersOfALockedDialogsCardStayClearAndTheCardDims() {
        for (Look look : TRANSPARENT_WINDOWS) {
            new Target().run(look);
        }
    }

    @Test
    void onASoftwareRendererTheCornersOfALockedDialogsCardStayClearAndTheCardDims() {
        HeadlessGl.onSoftwareRenderer(() -> {
            for (Look look : TRANSPARENT_WINDOWS) {
                new Target().run(look);
            }
        });
    }

    @Test
    void anOpaqueLockedWindowDimsAsBeforeEverywhere() {
        new Target().run(new Look("opaque window, light", Color.rgb(0x3060C0), LIGHT));
        new Target().run(new Look("opaque window, dark", Color.rgb(0x1B1D21), DARK));
    }

    @Test
    void onASoftwareRendererAnOpaqueLockedWindowDimsAsBeforeEverywhere() {
        HeadlessGl.onSoftwareRenderer(() -> new Target().run(new Look("opaque window", Color.rgb(0x3060C0), LIGHT)));
    }

    /** A framebuffer of its own in the current context, and the scenario drawn into it. */
    private final class Target {
        private final GlCanvas canvas = new GlCanvas(fonts);
        private final int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        private final int[] previousViewport = new int[4];
        private int texture;
        private int fbo;

        void run(Look look) {
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
                scenario(look);
            } finally {
                GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
                GL33C.glViewport(previousViewport[0], previousViewport[1], previousViewport[2],
                        previousViewport[3]);
                GL33C.glDeleteFramebuffers(fbo);
                GL33C.glDeleteTextures(texture);
                canvas.dispose();
            }
        }

        private void scenario(Look look) {
            String device = HeadlessGl.describe() + ", " + look.name();
            CardOnly card = new CardOnly(look.card());
            Scene scene = new Scene(card, () -> nanos[0]);
            scene.setTextRuler(new ShapingRuler(fonts));
            scene.setBackground(look.background());
            StubWindow window = new StubWindow();
            scene.bind(window);
            frame(scene, 0);
            byte[] unlocked = read();
            if (look.background().a() == 0) {
                assertEquals(0, pixel(unlocked, 1, 1)[3], device + ": the corner outside the card is clear to begin with");
            }

            window.modalBlocked = true; // a dialog opened over this one
            for (int i = 0; i < 12; i++) {
                nanos[0] += TimeUnit.MILLISECONDS.toNanos(30);
                frame(scene, 0); // the veil retints the whole window while it moves
            }
            byte[] whole = read();
            checkVeiled(device + ", whole frame", unlocked, whole);

            // Repaints of part of the window, each of which clears its rectangle and paints it
            // again, the veil included: the card's top left corner, so a pass covers what must stay
            // clear. Five in a row, which must neither darken the pass further nor touch the rest.
            for (int pass = 1; pass <= 5; pass++) {
                card.invalidate(0, 0, PASS_W_PT, PASS_H_PT);
                nanos[0] += TimeUnit.MILLISECONDS.toNanos(30);
                frame(scene, 1);
                byte[] partial = read();
                String what = device + ", partial pass " + pass;
                checkVeiled(what, unlocked, partial);
                int margin = 8; // device pixels round the pass, which its antialiasing may reach
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        if (x < PASS_W_PT * SCALE + margin && y < PASS_H_PT * SCALE + margin) {
                            continue;
                        }
                        int[] now = pixel(partial, x, y);
                        int[] before = pixel(whole, x, y);
                        if (!java.util.Arrays.equals(now, before)) {
                            throw new AssertionError(what + ": the pass touched " + x + "," + y + " outside it: "
                                    + str(before) + " -> " + str(now));
                        }
                    }
                }
            }
        }

        /**
         * Every probe is the unlocked pixel veiled: colour times {@code 1 - a}, coverage times
         * {@code 1 + a} up to full. The corner outside the card, the card's middle outside any
         * pass, and a point inside the pass.
         */
        private void checkVeiled(String what, byte[] unlocked, byte[] locked) {
            int[][] probes = {{1, 1}, {WIDTH / 2, HEIGHT / 2}, {(int) (30 * SCALE), (int) (20 * SCALE)}};
            for (int[] at : probes) {
                int[] before = pixel(unlocked, at[0], at[1]);
                int[] after = pixel(locked, at[0], at[1]);
                String where = what + " at " + at[0] + "," + at[1] + ": " + str(before) + " -> " + str(after);
                for (int c = 0; c < 3; c++) {
                    assertEquals(before[c] * (1 - VEIL), after[c], 2, where + ", colour channel " + c);
                }
                assertEquals(Math.min(255, before[3] * (1 + VEIL)), after[3], 2,
                        where + ", coverage: a clear pixel stays clear, an opaque one opaque, and a "
                                + "translucent one covers more, so the desktop through it darkens");
            }
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
