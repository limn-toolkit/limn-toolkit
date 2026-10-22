package limn.backend.lwjgl;

import limn.components.Button;
import limn.components.Card;
import limn.components.Label;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;
import limn.testing.HeadlessUi;
import limn.testing.StubWindow;
import limn.testing.TestPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A {@link Card} against a real device (ADR 073 §6, stage 1): a picture that covers the card is
 * rounded by the card's own corners where it meets them and stays square where it meets the
 * parts below, a clip and not a renderer feature; and a partial frame that draws the focus ring
 * outside a card, or the hover over its area, draws what the whole frame draws, the shadow's and
 * the ring's reach declared so that nothing is left stale.
 */
class CardPixelTest {

    private static final float SCALE = 2f;
    private static final int WIDTH = 480;
    private static final int HEIGHT = 400;
    private static final Color RED = Color.rgb(0xE02020);

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;
    private TestPreferences preferences;
    private int texture;
    private int fbo;
    private int previousFbo;
    private final int[] previousViewport = new int[4];

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        fonts = new FontStore();
        canvas = new GlCanvas(fonts);
        Theme.setCurrent(Theme.light());
        // Every fade jumps to its end, so a frame shows a state and not a moment of a motion.
        preferences = new TestPreferences().reduceMotion(true);
        previousFbo = Gl.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        Gl.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        texture = Gl.glGenTextures();
        fbo = Gl.glGenFramebuffers();
        Gl.glActiveTexture(GL33C.GL_TEXTURE0);
        Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
        Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
        Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
        Gl.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                GL33C.GL_TEXTURE_2D, texture, 0);
        Gl.glViewport(0, 0, WIDTH, HEIGHT);
    }

    @AfterEach
    void closeContext() {
        if (canvas != null) {
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            Gl.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
            canvas.dispose();
            canvas = null;
        }
        if (preferences != null) {
            preferences.close();
            preferences = null;
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

    /** A picture that is red to its edges. */
    private static final class Red extends Widget<Red> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(400, 200);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRect(0, 0, width(), height(), RED);
        }
    }

    @Test
    void aCoveringPictureIsRoundedByTheCardsOuterCornersAndSquareInside() {
        Red picture = new Red();
        Card card = new Card(new Label("Delivery routes")).setMedia(picture)
                .setMediaFit(Card.MediaFit.COVER).setMediaAspectRatio(2);
        Scene scene = bind(new SizedBox(200, SizedBox.UNSET, card));
        frame(scene, 0);
        byte[] image = read();
        float x = card.localToScene(0, 0).x();
        float y = card.localToScene(0, 0).y();
        float r = Theme.current().tokensFor(card).radiusMedium();
        assertTrue(isRed(image, x + 0.75f, y + 0.75f) == false,
                "the card's top-left corner is cut round: " + str(image, x + 0.75f, y + 0.75f));
        assertTrue(!isRed(image, x + 200 - 0.75f, y + 0.75f), "and its top-right");
        assertTrue(isRed(image, x + r, y + r), "inside the corner, the picture");
        float bottom = y + 100;
        assertTrue(isRed(image, x + 0.75f, bottom - 0.75f),
                "the picture's own bottom corner, inside the card, is square: " + str(image, x + 0.75f, bottom - 0.75f));
        assertTrue(isRed(image, x + 200 - 0.75f, bottom - 0.75f), "both of them");
        assertTrue(!isRed(image, x + 100, bottom + 2), "and the picture stops where its box does");
    }

    @Test
    void aPartialFrameOfTheRingOutsideACardDrawsWhatTheWholeFrameDraws() {
        Card card = new Card(new Label("Three items, due on Friday.")).setTitle("Invoice 1042");
        card.onActivate(() -> { });
        Scene scene = bind(new SizedBox(200.5f, SizedBox.UNSET, card));
        assertPartialIsWhole(scene, () -> tab(scene));
    }

    private static void tab(Scene scene) {
        drive(scene).keyEvent(Keys.TAB, true, false, 0);
        drive(scene).keyEvent(Keys.TAB, false, false, 0);
        drive(scene).inputBatchEnded();
    }

    @Test
    void aPartialFrameOfTheHoverOverTheAreaDrawsWhatTheWholeFrameDraws() {
        Card card = new Card(new Label("Three items, due on Friday.")).setTitle("Invoice 1042")
                .setFooter(new Button("Approve"));
        card.onActivate(() -> { });
        Scene scene = bind(new SizedBox(200.5f, SizedBox.UNSET, card));
        float x = card.localToScene(0, 0).x() + 20;
        float y = card.localToScene(0, 0).y() + 20;
        assertPartialIsWhole(scene, () -> {
            drive(scene).mouseMoved(x, y);
            drive(scene).inputBatchEnded();
        });
    }

    private Scene bind(Widget<?> content) {
        Column column = new Column();
        column.add(content);
        column.add(new Label("Below the card"));
        // Far enough below that a frame repainting the card does not reach it: what tells a
        // partial frame from a whole one.
        column.add(new SizedBox(10, 60, new Label(" ")));
        column.add(new Label("Far below"));
        // A fraction of a point off the grid, where a damage rectangle rounded the wrong way
        // would show as a seam.
        Scene scene = new Scene(new Padding(new Insets(30.25f, 30.25f, 0, 0), column));
        scene.setTextRuler(new ShapingRuler(fonts));
        scene.bind(new StubWindow());
        frame(scene, 0);
        return scene;
    }

    private void assertPartialIsWhole(Scene scene, Runnable change) {
        scene.setPartialRendering(true);
        frame(scene, 0);
        frame(scene, 1);
        change.run();
        frame(scene, 1);
        byte[] partial = read();
        float partialWidgets = scene.metrics().paintedWidgets().last();
        scene.requestRender();
        frame(scene, 0);
        byte[] whole = read();
        assertTrue(partialWidgets > 0 && partialWidgets < scene.metrics().paintedWidgets().last(),
                "the change was drawn by a partial frame: " + partialWidgets + " widgets of "
                        + scene.metrics().paintedWidgets().last());
        int bad = 0;
        int worst = 0;
        int where = 0;
        for (int i = 0; i < whole.length; i++) {
            if (i % 4 == 3) {
                continue;
            }
            int d = Math.abs((whole[i] & 0xFF) - (partial[i] & 0xFF));
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

    private void frame(Scene scene, int bufferAge) {
        canvas.beginFrame(WIDTH, HEIGHT, SCALE);
        scene.renderFrame(canvas, false, Float.NaN, bufferAge);
        canvas.endFrame();
    }

    private static boolean isRed(byte[] image, float x, float y) {
        int[] p = pixel(image, x, y);
        return p[0] > 200 && p[1] < 80 && p[2] < 80;
    }

    private static String str(byte[] image, float x, float y) {
        int[] p = pixel(image, x, y);
        return p[0] + "," + p[1] + "," + p[2];
    }

    private static int[] pixel(byte[] image, float x, float y) {
        int px = (int) (x * SCALE);
        int py = (int) (y * SCALE);
        int at = (py * WIDTH + px) * 4;
        return new int[] {image[at] & 0xFF, image[at + 1] & 0xFF, image[at + 2] & 0xFF};
    }

    private static byte[] read() {
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
