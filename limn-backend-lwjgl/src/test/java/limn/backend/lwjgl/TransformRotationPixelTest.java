package limn.backend.lwjgl;

import limn.components.ScrollView;
import limn.components.TextField;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.input.Keys;
import limn.math.Vec2;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;
import limn.scene.layout.Stack;
import limn.scene.layout.Transform;
import limn.testing.HeadlessUi;
import limn.testing.StubWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rotated {@link Transform} in a scene, against a real device (ADR 072 §6, stage 2): a scroll
 * view turned inside it shows nothing past its turned viewport, a text field turned inside it
 * draws its caret and its selection where the pointer pressed and dragged, and the picture a
 * rotation draws through is given back the frame after the rotation ends.
 */
class TransformRotationPixelTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 360;
    private static final int H_PT = 240;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final Color PAGE = Color.rgb(0xF4F5F8);
    private static final Color INK = Color.rgb(0x2050C0);

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;
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
        texture = GL33C.glGenTextures();
        fbo = GL33C.glGenFramebuffers();
        previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
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
    }

    @AfterEach
    void closeContext() {
        if (canvas == null) {
            return;
        }
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
        GL33C.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        GL33C.glDeleteFramebuffers(fbo);
        GL33C.glDeleteTextures(texture);
        canvas.dispose();
        canvas = null;
        fonts.close();
        ui.close();
    }

    /** Fills its box with one colour. */
    private static final class Fill extends Widget<Fill> {
        private final float w;
        private final float h;
        private final Color color;

        Fill(float w, float h, Color color) {
            this.w = w;
            this.h = h;
            this.color = color;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(w, h);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRect(0, 0, width(), height(), color);
        }
    }

    /** {@code widget} with its corner at {@code (left, top)}, over a page of one colour. */
    private static Stack page(Widget<?> widget, float top, float left) {
        Stack root = new Stack();
        root.add(new Fill(W_PT, H_PT, PAGE));
        root.add(new Padding(new Insets(top, 0, 0, left), widget));
        return root;
    }

    // ------------------------------------------------------------------ a turned scroll view

    @Test
    void aRotatedScrollViewShowsNothingInItsCorners() {
        // Content running far past a 160 by 100 viewport, turned 30 degrees about its middle.
        ScrollView view = new ScrollView(new Fill(160, 600, INK));
        Transform t = new Transform(new SizedBox(160, 100, view)).setRotation(30);
        Scene scene = scene(page(t, 70, 100));
        byte[] frame = frame(scene, 0);
        // The viewport's middle, and a point in its turned bounding box's top right corner that
        // lies past the turned viewport: turned back, 107 points above the middle, past its 50.
        float midX = 100 + 80;
        float midY = 70 + 50;
        assertEquals(red(INK), red(pixel(frame, midX, midY)), 1, "the content inside the viewport");
        assertEquals(red(PAGE), red(pixel(frame, midX + 85, midY - 75)), 1,
                "the page past the turned viewport, in its bounding box's corner");
        // Every point the turned viewport does not cover, inside its bounding box, is the page.
        double r = Math.toRadians(30);
        int inkOutside = 0;
        for (int y = 0; y < HEIGHT; y += 2) {
            for (int x = 0; x < WIDTH; x += 2) {
                double px = (x + 0.5) / SCALE - midX;
                double py = (y + 0.5) / SCALE - midY;
                double lx = Math.cos(r) * px + Math.sin(r) * py; // turned back
                double ly = -Math.sin(r) * px + Math.cos(r) * py;
                boolean outside = Math.abs(lx) > 80 + 1.5 || Math.abs(ly) > 50 + 1.5;
                if (outside && Math.abs(red(pixel(frame, x, y)) - red(PAGE)) > 2) {
                    inkOutside++;
                }
            }
        }
        assertEquals(0, inkOutside, "pixels drawn past the turned viewport");
    }

    @Test
    void aRotatedScrollViewStillSkipsTheRowsScrolledOutOfItsView() {
        // Two hundred rows of 20 points behind a viewport of 100: inside the picture the clip is an
        // exact rectangle again and the canvas answers it, so the rows past it are not painted.
        int upright = paintedWithRows(0);
        int turned = paintedWithRows(30);
        assertTrue(upright < 30, "upright, the view paints what it shows: " + upright);
        assertEquals(upright, turned, "turned, it paints the same rows, and not two hundred");
    }

    private int paintedWithRows(float degrees) {
        limn.scene.layout.Column rows = new limn.scene.layout.Column();
        for (int i = 0; i < 200; i++) {
            rows.add(new Fill(160, 20, i % 2 == 0 ? INK : PAGE));
        }
        Transform t = new Transform(new SizedBox(160, 100, new ScrollView(rows))).setRotation(degrees);
        Scene scene = scene(page(t, 70, 100));
        frame(scene, 0);
        return (int) scene.metrics().paintedWidgets().last();
    }

    // ------------------------------------------------------------------ a turned text field

    @Test
    void aRotatedTextFieldDrawsItsCaretAndItsSelectionWhereThePointerIs() {
        TextField field = new TextField().setText("Harbor Lane Supplies, invoice 2041");
        Transform t = new Transform(new SizedBox(260, 34, field)).setRotation(-24);
        Scene scene = scene(page(t, 100, 50));
        scene.bind(new StubWindow());
        frame(scene, 0);

        // A click at 60% of the field: the caret is drawn there, turned with the field.
        float clickX = field.width() * 0.6f;
        float midY = field.height() / 2;
        press(scene, field, clickX, midY, true);
        press(scene, field, clickX, midY, false);
        byte[] atClick = frame(scene, 0);
        drive(scene).press(Keys.HOME);
        drive(scene).inputBatchEnded();
        byte[] atHome = frame(scene, 0);
        List<float[]> caret = new ArrayList<>();
        for (float[] local : changedPixels(field, atClick, atHome)) {
            if (local[0] > field.width() / 3) {
                caret.add(local); // the caret at the click, and not the one at the start
            }
        }
        assertFalse(caret.isEmpty(), "the caret was drawn somewhere past the field's first third");
        for (float[] local : caret) {
            assertEquals(clickX, local[0], 6, "a caret pixel lies, along the field, at the pointer, "
                    + "within half a glyph and its antialiasing");
            assertTrue(local[1] > 0 && local[1] < field.height(), "and across it, inside the field");
        }

        // A drag from 30% to 75%: the selection band is drawn between the two points.
        float fromX = field.width() * 0.3f;
        float toX = field.width() * 0.75f;
        press(scene, field, fromX, midY, true);
        move(scene, field, (fromX + toX) / 2, midY);
        move(scene, field, toX, midY);
        press(scene, field, toX, midY, false);
        byte[] selected = frame(scene, 0);
        assertTrue(field.selectionEnd() > field.selectionStart(), "the drag selected");
        // Left collapses the selection to its start, where the caret is then drawn: inside the band.
        drive(scene).press(Keys.LEFT);
        drive(scene).inputBatchEnded();
        byte[] collapsed = frame(scene, 0);
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        for (float[] local : changedPixels(field, selected, collapsed)) {
            minX = Math.min(minX, local[0]);
            maxX = Math.max(maxX, local[0]);
        }
        assertEquals(fromX, minX, 6, "the band starts where the drag started");
        assertEquals(toX, maxX, 6, "and ends where it ended");
    }

    // ------------------------------------------------------------------ the picture's lifetime

    @Test
    void thePictureIsGivenBackTheFrameAfterARotationEnds() {
        Transform t = new Transform(new Fill(120, 80, INK)).setRotation(18);
        Scene scene = scene(page(t, 60, 100));
        frame(scene, 0);
        assertEquals(1, canvas.layerStats().textures(), "turned: one picture held");
        frame(scene, 0);
        assertEquals(1, canvas.layerStats().textures(), "kept while frames use it");
        t.setRotation(0);
        frame(scene, 1);
        assertEquals(0, canvas.layerStats().textures(), "upright again: the frame after gives it back");
    }

    // ------------------------------------------------------------------ plumbing

    private Scene scene(Widget<?> root) {
        Scene scene = new Scene(root);
        scene.setTextRuler(new ShapingRuler(fonts));
        scene.setPartialRendering(true);
        return scene;
    }

    private byte[] frame(Scene scene, int bufferAge) {
        canvas.beginFrame(WIDTH, HEIGHT, SCALE);
        scene.renderFrame(canvas, false, Float.NaN, bufferAge);
        canvas.endFrame();
        return readTopDown();
    }

    /** Presses or releases the left button where the field's local point is drawn. */
    private static void press(Scene scene, Widget<?> on, float x, float y, boolean down) {
        Vec2 p = on.localToScene(x, y);
        drive(scene).mouseMoved(p.x(), p.y());
        drive(scene).mouseButton(Keys.MOUSE_LEFT, down, 0, p.x(), p.y(), 1);
        drive(scene).inputBatchEnded();
    }

    private static void move(Scene scene, Widget<?> on, float x, float y) {
        Vec2 p = on.localToScene(x, y);
        drive(scene).mouseMoved(p.x(), p.y());
        drive(scene).inputBatchEnded();
    }

    /** The field's local points of every pixel that differs between two frames, turned back. */
    private static List<float[]> changedPixels(Widget<?> field, byte[] a, byte[] b) {
        List<float[]> points = new ArrayList<>();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int i = (y * WIDTH + x) * 4;
                int d = Math.abs((a[i] & 0xFF) - (b[i] & 0xFF)) + Math.abs((a[i + 1] & 0xFF) - (b[i + 1] & 0xFF))
                        + Math.abs((a[i + 2] & 0xFF) - (b[i + 2] & 0xFF));
                if (d > 24) {
                    Vec2 local = field.sceneToLocal((x + 0.5f) / SCALE, (y + 0.5f) / SCALE);
                    points.add(new float[] {local.x(), local.y()});
                }
            }
        }
        return points;
    }

    private static int[] pixel(byte[] image, float xPt, float yPt) {
        return pixel(image, (int) (xPt * SCALE), (int) (yPt * SCALE));
    }

    private static int[] pixel(byte[] image, int x, int y) {
        int i = (y * WIDTH + x) * 4;
        return new int[] {image[i] & 0xFF, image[i + 1] & 0xFF, image[i + 2] & 0xFF};
    }

    private static int red(int[] rgb) {
        return rgb[0];
    }

    private static int red(Color c) {
        return Math.round(255 * c.r());
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
