package limn.backend.lwjgl;

import limn.backend.spi.DragRequest;
import limn.backend.spi.FrameCallback;
import limn.backend.spi.FrameInfo;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.DragStartEvent;
import limn.testing.HeadlessUi;
import limn.testing.StubWindow;
import limn.transfer.DataFormat;
import limn.transfer.Drag;
import limn.transfer.Transfer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The picture the system is handed for a drag, from the scene to the renderer and back, against a
 * real device: the source paints the dragged thing alone, and what it does not paint is
 * see-through in the picture.
 */
class DragPictureTest {

    /** A widget that fills itself green and drags a red square painted on nothing. */
    private static final class Source extends Widget<Source> {
        private final float unit;

        Source(float unit) {
            this.unit = unit;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRect(0, 0, width(), height(), Color.rgb(0x22CC44));
        }

        @Override
        protected Drag onDragStart(DragStartEvent event) {
            return Drag.of(Transfer.of(DataFormat.TEXT, "square"))
                    .preview(8 * unit, 8 * unit, 4 * unit, 4 * unit,
                            canvas -> canvas.fillRect(2 * unit, 2 * unit, 4 * unit, 4 * unit, Color.rgb(0xFF0000)));
        }
    }

    /** A window that takes a drag, as one with a native drag does, and keeps what it was handed. */
    private static final class TakingWindow extends StubWindow {
        FrameCallback frames;
        DragRequest handed;

        @Override
        public void setFrameCallback(FrameCallback callback) {
            frames = callback;
        }

        @Override
        public boolean supportsDrag() {
            return true;
        }

        @Override
        public boolean startDrag(DragRequest request) {
            handed = request;
            return true;
        }
    }

    private HeadlessUi ui;
    private FontStore fonts;
    private GlRenderer renderer;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        fonts = new FontStore();
        renderer = new GlRenderer(fonts);
    }

    @AfterEach
    void closeContext() {
        if (renderer != null) {
            renderer.dispose();
        }
        if (fonts != null) {
            fonts.close();
        }
        if (ui != null) {
            ui.close();
        }
    }

    private float scale = 1f;

    private void frame(TakingWindow window, int w, int h) {
        renderer.beginFrame(w, h, scale, false);
        try {
            window.frames.onFrame(renderer, new FrameInfo(w, h, scale, false));
        } finally {
            renderer.endFrame();
        }
    }

    @Test
    void onADenseDisplayThePictureHasTheFramesOwnPixels() {
        scale = 2f;
        int[] viewport = new int[4];
        Gl.glGetIntegerv(GL33C.GL_VIEWPORT, viewport);
        int w = viewport[2];
        int h = viewport[3];
        TakingWindow window = new TakingWindow();
        Scene scene = new Scene(new Source(0.5f)); // a preview of four points: eight pixels
        scene.bind(window);
        frame(window, w, h);
        frame(window, w, h);
        drive(scene).mouseMoved(2, 2);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 2, 2, 1);
        drive(scene).inputBatchEnded();
        drive(scene).mouseMoved(7, 2);
        drive(scene).inputBatchEnded();
        frame(window, w, h);

        assertNotNull(window.handed, "the drag was handed to the system");
        Image picture = window.handed.image();
        assertNotNull(picture, "with a picture");
        assertEquals(8, picture.width(), "two pixels a point");
        assertEquals(4f, window.handed.width(), "shown four points wide");
        byte[] rgba = picture.pixels();
        assertEquals(0, rgba[3] & 0xff);
        int painted = (3 * 8 + 3) * 4;
        assertEquals(255, rgba[painted] & 0xff, "the square is red");
        assertEquals(191, rgba[painted + 3] & 0xff);
    }

    @Test
    void theSystemIsHandedTheDraggedThingAloneOnNothing() {
        int[] viewport = new int[4];
        Gl.glGetIntegerv(GL33C.GL_VIEWPORT, viewport);
        int w = viewport[2];
        int h = viewport[3];
        TakingWindow window = new TakingWindow();
        Scene scene = new Scene(new Source(1f));
        scene.bind(window);
        frame(window, w, h);
        frame(window, w, h);

        drive(scene).mouseMoved(4, 4);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 4, 4, 1);
        drive(scene).inputBatchEnded();
        drive(scene).mouseMoved(12, 4);
        drive(scene).inputBatchEnded();
        frame(window, w, h); // the frame that makes the picture and hands the drag over

        assertNotNull(window.handed, "the drag was handed to the system");
        Image picture = window.handed.image();
        assertNotNull(picture, "with a picture");
        assertEquals(8, picture.width());
        assertEquals(8f, window.handed.width());
        byte[] rgba = picture.pixels();
        assertEquals(0, rgba[3] & 0xff, "what the source did not paint is see-through, not the green behind it");
        int painted = (3 * 8 + 3) * 4;
        assertEquals(255, rgba[painted] & 0xff, "the square is red");
        assertEquals(0, rgba[painted + 1] & 0xff);
        assertEquals(191, rgba[painted + 3] & 0xff, "at the opacity a dragged thing is shown at");
    }
}
