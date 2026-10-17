package limn.backend.lwjgl;

import limn.graphics.BackdropEffect;
import limn.graphics.Color;
import limn.graphics.RoundRect;
import limn.graphics.Shadow;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A layer (ADR 071 §2.3) against a real device: what it composites, where, and what it holds.
 *
 * <p>The load-bearing case is the record's own pixel, section 0's: on a popup's highlighted row,
 * inside its icon, the icon over the row's highlight over the panel over the page. At one half, as
 * one picture, that pixel must be the opaque popup's mixed half and half with the page, which is
 * {@code 168, 184, 231}; drawn per draw it is {@code 157, 177, 229}, because the highlight and the
 * panel under the icon show through it, each at half. The probe read 167 or 168 through a layer on
 * every device the lab has, and 157 or 158 per draw.
 */
class GlLayerTest {

    private static final float SCALE = 2f;
    /** The target, in points: room for section 0's popup and its shadow. */
    private static final int W_PT = 400;
    private static final int H_PT = 480;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    /** The page behind the popup: section 0's table row under the highlighted row's icon. */
    private static final Color PAGE = Color.rgb(0xF4F5F8);
    private static final float POP_X = 50;
    private static final float POP_Y = 40;
    private static final float POP_W = 300;
    private static final float POP_H = 400;

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
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
        if (ui != null) {
            ui.close();
            ui = null;
        }
    }

    // ------------------------------------------------------------------ the record's pixel

    /** Section 0's popup, at its own origin: two shadow layers, a bordered panel, a highlighted row. */
    private static void popup(GlCanvas c) {
        RoundRect shape = RoundRect.of(POP_X, POP_Y, POP_W, POP_H, 10);
        c.drawShadow(shape, new Shadow(0, 8, 24, Color.rgba(0x000000, 0.18f)));
        c.drawShadow(shape, new Shadow(0, 2, 6, Color.rgba(0x000000, 0.12f)));
        c.fillRoundRectWithBorder(POP_X, POP_Y, POP_W, POP_H, 10, Color.rgb(0xFFFFFF), 1, Color.rgb(0xC9CDD6));
        for (int i = 0; i < 16; i++) {
            float y = POP_Y + 8 + i * 22;
            if (i == 5) {
                c.fillRoundRect(POP_X + 6, y, POP_W - 20, 22, 5, Color.rgb(0xD6E4FF));
            }
            c.fillRoundRect(POP_X + 14, y + 4, 14, 14, 3, Color.rgb(0x5B7BD5));
        }
    }

    /** The highlighted row's icon, in device pixels: x, y top-down. */
    private static final int ICON_X = (int) ((POP_X + 20) * SCALE);
    private static final int ICON_Y = (int) ((POP_Y + 8 + 5 * 22 + 10) * SCALE);

    @Test
    void aPopupAtOneHalfThroughALayerIsTheOpaquePopupMixedWithThePage() {
        int[] page = pixel(render(c -> { }), ICON_X, ICON_Y);
        int[] opaque = pixel(render(GlLayerTest::popup), ICON_X, ICON_Y);
        int[] layer = pixel(render(c -> {
            c.saveLayer(POP_X - 24, POP_Y - 24, POP_W + 48, POP_H + 48, 0.5f);
            popup(c);
            c.restore();
        }), ICON_X, ICON_Y);
        int[] perDraw = pixel(render(c -> {
            c.save();
            c.setOpacity(0.5f);
            popup(c);
            c.restore();
        }), ICON_X, ICON_Y);

        assertEquals(244, page[0], "the page under the icon, as the record has it");
        assertEquals(91, opaque[0], "the icon drawn opaque, as the record has it");
        for (int channel = 0; channel < 3; channel++) {
            int exact = Math.round(0.5f * opaque[channel] + 0.5f * page[channel]);
            assertEquals(exact, layer[channel], 1, "channel " + channel
                    + ": one picture at one half is the opaque picture mixed half and half with the page");
        }
        assertEquals(168, layer[0], 1, "the record's exact red, 168");
        assertEquals(157, perDraw[0], 1, "per draw the highlight and the panel show through the icon: "
                + "the record's 157, eleven steps off");
    }

    // ------------------------------------------------------------------ nesting, transforms, clips

    @Test
    void aLayerInsideALayerMultipliesAsPicturesDo() {
        Color ink = Color.rgb(0x2050C0);
        int[] nested = pixel(render(c -> {
            c.saveLayer(10, 10, 100, 100, 0.5f);
            c.fillRect(20, 20, 40, 40, Color.BLACK); // under the inner one, far from the page so it tells
            c.saveLayer(20, 20, 60, 60, 0.5f);
            c.fillRect(20, 20, 40, 40, ink);
            c.restore();
            c.restore();
        }), 60, 60);
        // The black, then the ink over it at one half inside the same picture, and that
        // picture at one half over the page.
        for (int channel = 0; channel < 3; channel++) {
            float inner = 0.5f * channel(ink, channel); // over black
            float expected = 0.5f * inner + 0.5f * channel(PAGE, channel);
            assertEquals(Math.round(expected), nested[channel], 2, "channel " + channel);
        }
        assertEquals(0, canvas.openLayers(), "both closed");
    }

    @Test
    void setOpacityMultipliesTheOpacityInForce() {
        Color ink = Color.rgb(0x2050C0);
        float[] seen = new float[1];
        int[] quarter = pixel(render(c -> {
            c.save();
            c.setOpacity(0.5f);
            c.save();
            c.setOpacity(0.5f);
            seen[0] = c.opacity();
            c.fillRect(20, 20, 40, 40, ink);
            c.restore();
            c.restore();
        }), 60, 60);
        assertEquals(0.25f, seen[0], 1e-6f, "a half inside a half is a quarter");
        for (int channel = 0; channel < 3; channel++) {
            float expected = 0.25f * channel(ink, channel) + 0.75f * channel(PAGE, channel);
            assertEquals(Math.round(expected), quarter[channel], 1, "channel " + channel);
        }
        int[] insideLayer = pixel(render(c -> {
            c.saveLayer(0, 0, 100, 100, 0.5f);
            c.setOpacity(0.5f);
            c.fillRect(20, 20, 40, 40, ink);
            c.restore();
        }), 60, 60);
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(quarter[channel], insideLayer[channel], 1,
                    "an opacity set inside a layer combines with the layer's: channel " + channel);
        }
    }

    @Test
    void oneShapeThroughALayerUnderARotationIsTheShapeDrawnPerDraw() {
        Consumer<GlCanvas> shape = c -> c.fillRoundRect(-40, -30, 80, 60, 8, Color.rgb(0x40A060));
        byte[] layer = render(c -> {
            c.translate(200, 200);
            c.rotate(0.4f);
            c.saveLayer(-41, -31, 82, 62, 0.6f);
            shape.accept(c);
            c.restore();
        });
        byte[] perDraw = render(c -> {
            c.translate(200, 200);
            c.rotate(0.4f);
            c.save();
            c.setOpacity(0.6f);
            shape.accept(c);
            c.restore();
        });
        // One shape has nothing to show through, so the two agree: the layer's box is the rotated
        // box's bounds, and nothing the shape draws is cut.
        assertAtMost(2, perDraw, layer, "a rotated layer");
    }

    @Test
    void aLayerCutByAClipKeepsWhatTheClipShowsAndNothingElse() {
        Consumer<GlCanvas> body = c -> {
            c.clipRect(60, 60, 50.5f, 70.25f); // a scroll view's viewport, a fraction of a point off the grid
            c.fillRoundRect(40, 40, 120, 120, 12, Color.rgb(0xC04080));
        };
        byte[] layer = render(c -> {
            c.save();
            c.clipRect(60, 60, 50.5f, 70.25f);
            c.saveLayer(40, 40, 120, 120, 0.7f);
            c.fillRoundRect(40, 40, 120, 120, 12, Color.rgb(0xC04080));
            c.restore();
            c.restore();
        });
        byte[] perDraw = render(c -> {
            c.save();
            body.accept(c);
            c.restore();
        });
        // The per-draw picture of one shape at the layer's opacity, under the same clip.
        byte[] reference = render(c -> {
            c.save();
            c.setOpacity(0.7f);
            body.accept(c);
            c.restore();
        });
        assertAtMost(2, reference, layer, "a clipped layer, its antialiased edge included");
        assertFalse(java.util.Arrays.equals(perDraw, layer), "the layer is faded");
    }

    // ------------------------------------------------------------------ the two ends

    @Test
    void anOpacityOfOneOpensNoLayerAndZeroDrawsNothing() {
        byte[] none = render(c -> { });
        byte[] zero = render(c -> {
            c.saveLayer(0, 0, W_PT, H_PT, 0f);
            popup(c);
            c.restore();
        });
        assertAtMost(0, none, zero, "nothing drawn at 0");

        int[] open = new int[1];
        byte[] one = render(c -> {
            c.saveLayer(0, 0, W_PT, H_PT, 1f);
            open[0] = c.openLayers();
            popup(c);
            c.restore();
        });
        assertEquals(0, open[0], "at 1 the layer is a save: no picture is made");
        assertEquals(0, canvas.layerStats().textures(), "and none is held");
        assertAtMost(0, render(GlLayerTest::popup), one, "the frame of today");
    }

    // ------------------------------------------------------------------ memory

    @Test
    void siblingsShareOnePictureAndAFrameWithNoLayerFreesIt() {
        render(c -> c.fillRect(0, 0, 10, 10, Color.rgb(0xE04020)));
        long before = canvas.stats().textureBytes();
        render(c -> {
            for (int i = 0; i < 3; i++) {
                c.saveLayer(10 + i * 40, 10, 30, 30, 0.5f);
                c.fillRect(10 + i * 40, 10, 30, 30, Color.rgb(0xE04020));
                c.restore();
            }
        });
        assertEquals(1, canvas.layerStats().textures(), "three siblings, one picture");
        assertTrue(canvas.layerStats().textureBytes() >= 60L * 60 * 4, "counted in the stats");
        assertEquals(before + canvas.layerStats().textureBytes(), canvas.stats().textureBytes(),
                "and in the canvas's stats, which the performance monitor shows");

        render(c -> {
            c.saveLayer(12, 12, 30, 30, 0.5f); // a fade's next frame, a pixel off: the same picture
            c.restore();
        });
        assertEquals(1, canvas.layerStats().textures(), "kept while consecutive frames use it");

        render(c -> c.fillRect(0, 0, 10, 10, Color.rgb(0xE04020)));
        assertEquals(0, canvas.layerStats().textures(), "a frame that opens no layer frees it");
    }

    @Test
    void aClosedWindowKeepsNoPicture() {
        render(c -> {
            c.saveLayer(10, 10, 100, 100, 0.5f);
            c.saveLayer(20, 20, 50, 50, 0.5f); // nested: two held at once
            c.restore();
            c.restore();
        });
        int[] held = canvas.layerTextures();
        assertEquals(2, held.length, "one picture per level of nesting");
        canvas.dispose();
        canvas = null;
        for (int texture : held) {
            assertFalse(GL33C.glIsTexture(texture), "texture " + texture + " freed with the window");
        }
    }

    // ------------------------------------------------------------------ a glass inside a layer

    /** A banded page, so a glass that reads the wrong thing shows it. */
    private static void bands(GlCanvas c) {
        for (int i = 0; i * 8 < H_PT; i++) {
            c.fillRect(0, i * 8, W_PT, 8, i % 2 == 0 ? Color.rgb(0xF2A65A) : Color.rgb(0x3050C8));
        }
    }

    private static final BackdropEffect FROST = new BackdropEffect.Blur(Color.rgba(0xFFFFFF, 0.2f), 6f,
            BackdropEffect.Blur.Axis.X);

    @Test
    void aGlassInsideALayerReadsWhatItWouldReadIfTheLayerWereOpaque() {
        byte[] bare = render(c -> {
            bands(c);
            c.fillRect(40, 40, 200, 200, Color.rgb(0x204020)); // the group's own content under the glass
            c.fillBackdropRoundRect(60, 60, 160, 160, 12, FROST);
        });
        freshCanvas(); // no backdrop copy left over from the frame before, which would read right by luck
        byte[] nearlyOpaque = render(c -> {
            bands(c);
            // A point past the content, for its antialiased edge, as a widget's layer takes one.
            c.saveLayer(39, 39, 202, 202, 0.99f);
            c.fillRect(40, 40, 200, 200, Color.rgb(0x204020));
            c.fillBackdropRoundRect(60, 60, 160, 160, 12, FROST);
            c.restore();
        });
        // Continuous at the top end: at 0.99 the picture is the opaque one but for a hundredth of the
        // page showing through it.
        assertAtMost(4, bare, nearlyOpaque, "a glass in a group at 0.99 against no group");

        // And reading through the group: a glass whose group content stops short of it reads the
        // page there, blurred, and not the empty layer.
        byte[] glassOverPage = render(c -> {
            bands(c);
            c.fillBackdropRoundRect(260, 60, 100, 100, 12, FROST);
        });
        freshCanvas();
        byte[] glassInLayerOverPage = render(c -> {
            bands(c);
            c.saveLayer(250, 50, 120, 120, 0.99f);
            c.fillBackdropRoundRect(260, 60, 100, 100, 12, FROST);
            c.restore();
        });
        assertAtMost(4, glassOverPage, glassInLayerOverPage, "a glass over the page inside a group");
    }

    // ------------------------------------------------------------------ plumbing

    /** A canvas with nothing kept from earlier frames: no backdrop copy, no pictures. */
    private void freshCanvas() {
        canvas.dispose();
        canvas = new GlCanvas(fonts);
    }

    private static float channel(Color c, int channel) {
        return 255 * (channel == 0 ? c.r() : channel == 1 ? c.g() : c.b());
    }

    private static int[] pixel(byte[] image, int x, int y) {
        int at = (y * WIDTH + x) * 4;
        return new int[] {image[at] & 0xFF, image[at + 1] & 0xFF, image[at + 2] & 0xFF, image[at + 3] & 0xFF};
    }

    /** Fails naming the worst pixel when any channel of any pixel differs by more than {@code tolerance}. */
    private static void assertAtMost(int tolerance, byte[] expected, byte[] actual, String what) {
        int worst = 0;
        int where = -1;
        for (int i = 0; i < expected.length; i++) {
            if (i % 4 == 3) {
                continue;
            }
            int d = Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF));
            if (d > worst) {
                worst = d;
                where = i;
            }
        }
        int px = where / 4;
        StringBuilder around = new StringBuilder();
        if (worst > tolerance) {
            // The red channel around it, expected/actual, so a failure says what kind of edge it is.
            for (int dy = -2; dy <= 2; dy++) {
                around.append(" |");
                for (int dx = -2; dx <= 2; dx++) {
                    int at = Math.max(0, Math.min(expected.length - 4, (px + dy * WIDTH + dx) * 4));
                    around.append(String.format(" %d/%d", expected[at] & 0xFF, actual[at] & 0xFF));
                }
            }
        }
        assertTrue(worst <= tolerance, what + ": off by " + worst + " at " + (px % WIDTH) + "," + (px / WIDTH)
                + around);
    }

    /** One frame over the page into a texture of its own, read back top-down. */
    private byte[] render(Consumer<GlCanvas> paint) {
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
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE, GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER));
            GL33C.glViewport(0, 0, WIDTH, HEIGHT);
            canvas.beginFrame(WIDTH, HEIGHT, SCALE);
            canvas.clear(PAGE);
            paint.accept(canvas);
            canvas.endFrame();
            assertEquals(fbo, GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING),
                    "the frame's own target is bound again when every layer has closed");
            return readTopDown();
        } finally {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
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
