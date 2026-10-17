package limn.backend.lwjgl;

import limn.components.BackdropPanel;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import limn.scene.layout.Stack;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A partial frame through a widget drawn as one picture (ADR 071 §2.4) draws what the whole frame
 * drew, against a real device: the group's layer is made for the group's part of each pass, and
 * nothing outside the pass is read or written.
 *
 * <p>Three cases, each a whole frame, then one change and a partial frame into the same target kept
 * as a buffer that holds its contents (age 1), then a whole frame of the same state to hold it
 * against, as {@code BackdropPassSeamTest} does for glass: something inside a fading panel
 * repaints, so the pass cuts the group; the panel's opacity itself moves, so the pass is the
 * group's box; and a glass inside a fading panel repaints, so its copy is composed through a layer
 * cut by the pass. The panel sits a fraction of a point off the grid, which is where a layer
 * rounded the wrong way would show as a seam.
 */
class OpacityPassSeamTest {

    private static final float SCALE = 2f;
    private static final int WIDTH = 320;
    private static final int HEIGHT = 200;

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        fonts = new FontStore();
        canvas = new GlCanvas(fonts);
        Theme.setCurrent(Theme.dark());
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

    /** Bright stripes behind everything, so a share of the wrong thing anywhere shows. */
    private static final class Picture extends Widget<Picture> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(WIDTH / SCALE, HEIGHT / SCALE);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            for (int band = 0; band * 6 < height(); band++) {
                canvas.fillRect(0, band * 6, width(), 6,
                        band % 2 == 0 ? Color.rgb(0xF2A65A) : Color.rgb(0x3050C8));
            }
        }
    }

    /** A card with a shadow-like rim and a highlight, so its parts overlap as a popup's do. */
    private static final class Card extends Widget<Card> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(90, 60);
        }

        @Override
        protected float paintOutset() {
            return 3;
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRoundRect(-3, -3, width() + 6, height() + 6, 9, Color.rgba(0x000000, 0.3f));
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), 6, Color.WHITE, 1, Color.rgb(0x808890));
            canvas.fillRoundRect(6, 20, width() - 12, 18, 4, Color.rgb(0xD6E4FF));
        }
    }

    /** What repaints inside: a thumb, a button under the pointer. */
    private static final class Thumb extends Widget<Thumb> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(12, 12);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRoundRect(0, 0, width(), height(), 3, Color.rgb(0x5B7BD5));
        }
    }

    @Test
    void somethingRepaintingInsideAFadingPanelDrawsWhatTheWholeFrameDrew() {
        Thumb thumb = new Thumb();
        Stack panel = new Stack();
        panel.add(new Card());
        panel.add(new Padding(new Insets(24, 0, 0, 20), thumb));
        panel.setOpacity(0.55f);
        Stack layers = new Stack();
        layers.add(new Picture());
        layers.add(new Padding(new Insets(30.35f, 0, 0, 20.1f), panel));

        assertPartialIsWhole(layers, thumb::invalidate);
    }

    @Test
    void aFadeStepDrawsWhatTheWholeFrameDrew() {
        Stack panel = new Stack();
        panel.add(new Card());
        panel.add(new Padding(new Insets(24, 0, 0, 20), new Thumb()));
        panel.setOpacity(0.55f);
        Stack layers = new Stack();
        layers.add(new Picture());
        layers.add(new Padding(new Insets(30.35f, 0, 0, 20.1f), panel));

        assertPartialIsWhole(layers, () -> panel.setOpacity(0.3f));
    }

    @Test
    void aGlassInsideAFadingPanelDrawsWhatTheWholeFrameDrew() {
        Thumb thumb = new Thumb();
        BackdropPanel glass = BackdropPanel.frosted(Insets.all(10), thumb);
        glass.setOpacity(0.7f);
        Stack layers = new Stack();
        layers.add(new Picture());
        layers.add(new Padding(new Insets(30.35f, 0, 0, 20.1f), glass));

        assertPartialIsWhole(layers, thumb::invalidate);
    }

    private void assertPartialIsWhole(Widget<?> root, Runnable change) {
        Scene scene = new Scene(root);
        scene.setPartialRendering(true);
        int texture = GL33C.glGenTextures();
        int fbo = GL33C.glGenFramebuffers();
        int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        try {
            bindTarget(texture, fbo);
            frame(scene, 0);
            change.run();
            frame(scene, 1);
            assertTrue(canvas.layerStats().textures() > 0, "the partial frame drew through a layer");
            byte[] partial = readTopDown();
            frame(scene, 0);
            byte[] whole = readTopDown();
            assertSame(whole, partial);
        } finally {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
        }
    }

    private void frame(Scene scene, int bufferAge) {
        canvas.beginFrame(WIDTH, HEIGHT, SCALE);
        scene.renderFrame(canvas, false, Float.NaN, bufferAge);
        canvas.endFrame();
    }

    /** Every pixel within 2 of the reference, or the worst one and how many are not. */
    private static void assertSame(byte[] expected, byte[] actual) {
        int bad = 0;
        int worst = 0;
        int where = 0;
        for (int i = 0; i < expected.length; i++) {
            if (i % 4 == 3) {
                continue;
            }
            int d = Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF));
            if (d > 2) {
                bad++;
            }
            if (d > worst) {
                worst = d;
                where = i / 4;
            }
        }
        if (bad > 0) {
            fail("the partial frame differs from the whole one in " + bad + " channel(s), worst " + worst
                    + " at " + (where % WIDTH) + "," + (where / WIDTH));
        }
        assertEquals(0, bad);
    }

    private static void bindTarget(int texture, int fbo) {
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
        GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                GL33C.GL_TEXTURE_2D, texture, 0);
        assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE, GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER),
                "render target");
        GL33C.glViewport(0, 0, WIDTH, HEIGHT);
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
