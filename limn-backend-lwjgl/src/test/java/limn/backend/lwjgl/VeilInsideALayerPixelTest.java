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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A modal's veil laid while a layer is open veils what the layer holds, as at the top level it
 * veils what the window holds: a pixel the layer leaves clear stays clear, an opaque one is what
 * painting the veil over it gives, and a partly covered one darkens and gains coverage, so what lies
 * under the layer is to the veil what the desktop under a translucent window is. An application that
 * renders a whole scene inside a layer of its own, to fade it or turn it, keeps the dim of every
 * modal in that scene, and a transparent scene's clear corners stay clear in it; what the
 * application drew under that layer before it opened is not the scene's, and the veil leaves it as
 * it was where the scene is clear. On the GPU and on a software renderer, through a layer that is
 * really one (an opacity just under 1).
 */
class VeilInsideALayerPixelTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 32;
    private static final Color CONTENT = Color.rgb(0x3060C0);
    private static final Color VEIL = Color.rgba(0x000000, 0.5f);
    /** The locked window's veil once it has settled, the toolkit's constant for a native modal's dim. */
    private static final float SETTLED = 0.5f;
    /** A glassy card: what a translucent tool window shows. */
    private static final float GLASS = 0.6f;
    /** Where the card starts, in points; left of it the scene paints nothing. */
    private static final int CARD_X = 16;
    /** Just under 1: a layer that is really composited, not elided. */
    private static final float LAYER = 0.999f;

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

    @Test
    void onTheGpuALockedSceneRenderedInsideAnApplicationsLayerKeepsItsDim() {
        lockedSceneInsideALayer(Color.rgb(0xF2F2F2), CONTENT);
    }

    @Test
    void onASoftwareRendererALockedSceneRenderedInsideAnApplicationsLayerKeepsItsDim() {
        HeadlessGl.onSoftwareRenderer(() -> lockedSceneInsideALayer(Color.rgb(0xF2F2F2), CONTENT));
    }

    @Test
    void onTheGpuATransparentLockedSceneInsideALayerKeepsItsClearPixelsClearAndDimsItsGlass() {
        lockedSceneInsideALayer(Color.TRANSPARENT, CONTENT.withAlpha(GLASS));
    }

    @Test
    void onASoftwareRendererATransparentLockedSceneInsideALayerKeepsItsClearPixelsClearAndDimsItsGlass() {
        HeadlessGl.onSoftwareRenderer(() -> lockedSceneInsideALayer(Color.TRANSPARENT, CONTENT.withAlpha(GLASS)));
    }

    @Test
    void onTheGpuAVeilInsideALayerLeavesWhatLiesUnderTheLayerAsItWas() {
        veilOverWhatLiesUnderALayer();
    }

    @Test
    void onASoftwareRendererAVeilInsideALayerLeavesWhatLiesUnderTheLayerAsItWas() {
        HeadlessGl.onSoftwareRenderer(this::veilOverWhatLiesUnderALayer);
    }

    /**
     * A scene a modal locks, its whole frame rendered inside a layer the application opened over
     * a backdrop of its own: every probe is the unlocked pixel veiled as the window's own veil
     * veils it, the backdrop seen through a clear pixel included.
     */
    private void lockedSceneInsideALayer(Color background, Color card) {
        String device = HeadlessGl.describe() + ", " + (background.a() == 0 ? "transparent" : "opaque") + " scene";
        Scene scene = new Scene(new Card(card), () -> nanos[0]);
        scene.setTextRuler(new ShapingRuler(fonts));
        scene.setBackground(background);
        StubWindow window = new StubWindow();
        scene.bind(window);
        Consumer<GlCanvas> frame = canvas -> {
            canvas.beginFrame(WIDTH, HEIGHT, 1f);
            canvas.saveLayer(0, 0, WIDTH, HEIGHT, LAYER);
            scene.renderFrame(canvas, false, Float.NaN, 0);
            canvas.restore();
            canvas.endFrame();
        };
        byte[] unlocked = draw(frame);
        window.modalBlocked = true; // a dialog opened over this window
        byte[] locked = null;
        for (int i = 0; i < 12; i++) {
            nanos[0] += TimeUnit.MILLISECONDS.toNanos(30);
            locked = draw(frame); // the veil fades in
        }
        for (int[] at : new int[][] {{2, HEIGHT / 2}, {WIDTH / 2, HEIGHT / 2}}) {
            checkVeiled(device + " at " + at[0] + "," + at[1], pixel(unlocked, at[0], at[1]),
                    pixel(locked, at[0], at[1]), SETTLED);
        }
        if (background.a() == 0) {
            assertEquals(0, pixel(locked, 2, HEIGHT / 2)[3], device + ": left of the card, the layer stays clear");
        }
    }

    /** Content at the top level, the veil in a layer over it, as the canvas is handed them. */
    private void veilOverWhatLiesUnderALayer() {
        Consumer<GlCanvas> under = canvas -> canvas.fillRect(0, 0, WIDTH, HEIGHT, CONTENT);
        int[] before = pixel(draw(canvas -> {
            canvas.beginFrame(WIDTH, HEIGHT, 1f);
            under.accept(canvas);
            canvas.endFrame();
        }), WIDTH / 2, HEIGHT / 2);
        int[] after = pixel(draw(canvas -> {
            canvas.beginFrame(WIDTH, HEIGHT, 1f);
            under.accept(canvas);
            canvas.saveLayer(0, 0, WIDTH, HEIGHT, LAYER);
            canvas.fillVeil(0, 0, WIDTH, HEIGHT, VEIL);
            canvas.restore();
            canvas.endFrame();
        }), WIDTH / 2, HEIGHT / 2);
        for (int c = 0; c < 4; c++) {
            assertEquals(before[c], after[c], 1, HeadlessGl.describe() + ": " + str(before) + " -> " + str(after)
                    + ", the layer held nothing there, and a veil in it veils nothing, as a clear window's "
                    + "veil leaves the desktop under it");
        }
    }

    /**
     * The pixel veiled by black at {@code alpha}: its colour times {@code 1 - alpha}, its coverage
     * times {@code 1 + alpha} up to full.
     */
    private static void checkVeiled(String what, int[] before, int[] after, float alpha) {
        String where = what + ": " + str(before) + " -> " + str(after);
        for (int c = 0; c < 3; c++) {
            assertEquals(before[c] * (1 - alpha), after[c], 2, where + ", colour channel " + c + " is dimmed");
        }
        assertEquals(Math.min(255, before[3] * (1 + alpha)), after[3], 2, where + ", coverage");
    }

    /** What the scene shows: a card from {@link #CARD_X} to the right edge, nothing left of it. */
    private static final class Card extends Widget<Card> {
        private final Color color;

        Card(Color color) {
            this.color = color;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(WIDTH, HEIGHT);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRect(CARD_X, 0, width() - CARD_X, height(), color);
        }
    }

    /** Draws into a framebuffer of its own, cleared, in the current context and reads it back. */
    private byte[] draw(Consumer<GlCanvas> drawing) {
        int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        GlCanvas canvas = new GlCanvas(fonts);
        int texture = GL33C.glGenTextures();
        int fbo = GL33C.glGenFramebuffers();
        try {
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            GL33C.glViewport(0, 0, WIDTH, HEIGHT);
            GL33C.glClearColor(0, 0, 0, 0);
            GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT);
            drawing.accept(canvas);
            return read();
        } finally {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glViewport(previousViewport[0], previousViewport[1], previousViewport[2],
                    previousViewport[3]);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
            canvas.dispose();
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
            pixels.get(out);
            return out;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}
