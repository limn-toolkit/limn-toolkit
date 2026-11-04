package limn.backend.lwjgl;

import limn.backend.spi.OffscreenPainter;
import limn.graphics.BlendMode;
import limn.graphics.Color;
import limn.graphics.Gradient;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Images#render} through the backend's canvas on a real device: the image is what the canvas
 * draws, transparent where it draws nothing, at the scale asked; drawn inside a frame, the frame goes
 * on where it was, and the canvas it draws with is kept for the next; and with no painter there is no
 * image, said.
 */
class GlOffscreenTest {

    private FontStore fonts;
    private HeadlessUi ui;
    private OffscreenPainter painter;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        fonts = new FontStore();
        painter = (w, h, k, p) -> GlOffscreen.paintHere(fonts, w, h, k, p);
        OffscreenPainter.install(painter);
    }

    @AfterEach
    void closeContext() {
        if (painter != null) {
            OffscreenPainter.uninstall(painter);
        }
        if (fonts != null) {
            fonts.close();
        }
        if (ui != null) {
            ui.close();
        }
    }

    private static int[] rgba(Image image, int x, int y) {
        int at = (y * image.width() + x) * 4;
        byte[] p = image.pixels();
        return new int[] {p[at] & 0xff, p[at + 1] & 0xff, p[at + 2] & 0xff, p[at + 3] & 0xff};
    }

    @Test
    void anImageIsWhatTheCanvasDrawsAtTheScaleAsked() {
        Image image = Images.render(40, 30, 2, c -> {
            c.fillCircle(20, 15, 10, Color.rgb(0xCC2020));
            c.setBlendMode(BlendMode.DESTINATION_OUT);
            c.fillCircle(20, 15, 4, Color.BLACK);
        });
        assertEquals(80, image.width());
        assertEquals(60, image.height());
        assertEquals(255, rgba(image, 40 - 14, 30)[3], "the disc, opaque");
        assertEquals(0xCC, rgba(image, 40 - 14, 30)[0], "and red, straight alpha");
        assertEquals(0, rgba(image, 40, 30)[3], "the hole the eraser made");
        assertEquals(0, rgba(image, 2, 2)[3], "clear where nothing is drawn");
    }

    @Test
    void aCanvasKeepsItsLastPicturesFramebufferUpToTheSizeAllowed() {
        GlCanvas canvas = new GlCanvas(fonts);
        long allowed = GlOffscreen.keptPixels;
        try {
            GlOffscreen.paint(canvas, 30, 20, 1, c -> c.fillRect(0, 0, 30, 20, Color.rgb(0xCC2020)));
            GlOffscreen.Target kept = canvas.offscreenTarget;
            assertNotNull(kept, "kept for the next picture of its size");
            Image again = GlOffscreen.paint(canvas, 30, 20, 1, c -> c.fillRect(10, 5, 10, 10, Color.rgb(0x2020CC)));
            assertSame(kept, canvas.offscreenTarget, "the same framebuffer, drawn again");
            assertEquals(0, rgba(again, 2, 2)[3], "cleared: nothing of the picture before");
            assertEquals(0x20, rgba(again, 15, 10)[0], "and the new picture in it");

            GlOffscreen.paint(canvas, 40, 20, 1, c -> c.fillRect(0, 0, 40, 20, Color.BLACK));
            assertNotSame(kept, canvas.offscreenTarget, "another size replaces it");

            GlOffscreen.keptPixels = 40 * 20 - 1;
            Image larger = GlOffscreen.paint(canvas, 40, 20, 1, c -> c.fillRect(0, 0, 40, 20, Color.WHITE));
            assertNull(canvas.offscreenTarget, "a picture over the size allowed keeps nothing");
            assertEquals(255, rgba(larger, 39, 19)[0], "and is drawn all the same");
            GlOffscreen.paint(canvas, 30, 20, 1, c -> c.fillRect(0, 0, 30, 20, Color.WHITE));
            assertNotNull(canvas.offscreenTarget, "one under it is kept");
            GlOffscreen.keptPixels = 0;
            GlOffscreen.paint(canvas, 30, 20, 1, c -> c.fillRect(0, 0, 30, 20, Color.WHITE));
            assertNull(canvas.offscreenTarget, "0 keeps none");
        } finally {
            GlOffscreen.keptPixels = allowed;
            canvas.dispose();
        }
    }

    @Test
    void aPictureWhoseBytesWouldNotFitInOneArrayIsRefusedBeforeAnythingIsDrawn() {
        // 32768 by 32768, a size a GPU allows, is 4 GiB of RGBA: its size in an int wrapped round to
        // 0, and the device was asked to read 4 GiB into nothing.
        boolean[] painted = {false};
        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Images.render(32768, 32768, 1, c -> painted[0] = true));
        assertTrue(refused.getMessage().contains("32768 x 32768"), refused.getMessage());
        assertTrue(!painted[0], "nothing drawn");
        Image justUnder = Images.render(1, 1, 1, c -> c.fillRect(0, 0, 1, 1, Color.WHITE));
        assertEquals(1, justUnder.width(), "a small one still drawn");
    }

    @Test
    void anImageDrawnInsideAFrameLeavesTheFrameAsItWas() {
        GlCanvas canvas = new GlCanvas(fonts);
        int texture = Gl.glGenTextures();
        int fbo = Gl.glGenFramebuffers();
        int[] viewport = new int[4];
        Gl.glGetIntegerv(GL33C.GL_VIEWPORT, viewport);
        try {
            Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, 200, 100, 0, GL33C.GL_RGBA,
                    GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            Gl.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D,
                    texture, 0);
            Gl.glViewport(0, 0, 200, 100);
            AtomicReference<Image> made = new AtomicReference<>();
            canvas.beginFrame(200, 100, 1);
            canvas.clear(Color.WHITE);
            canvas.fillRect(10, 10, 30, 30, Color.rgb(0x2050C0)); // queued before
            made.set(Images.render(20, 20, 1, c -> c.fillRect(0, 0, 20, 20,
                    Gradient.linear(0, 0, 20, 0, Color.rgb(0xFF0000), Color.rgb(0x0000FF)))));
            GlCanvas kept = canvas.offscreen();
            Images.render(4, 4, 1, c -> c.fillRect(0, 0, 4, 4, Color.BLACK));
            assertSame(kept, canvas.offscreen(), "the canvas it draws with is kept");
            assertSame(canvas, GlCanvas.current(), "the frame's canvas is the current one again");
            canvas.fillRect(60, 10, 30, 30, Color.rgb(0x10A070)); // drawn after
            canvas.drawImage(made.get(), 120, 10, 20, 20);
            canvas.endFrame();
            ByteBuffer pixels = MemoryUtil.memAlloc(200 * 100 * 4);
            try {
                Gl.glReadPixels(0, 0, 200, 100, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
                // Rows bottom-up: y = 25 from the top is row 74.
                assertEquals(0x20, pixels.get((74 * 200 + 25) * 4) & 0xff, "what was queued before is in the frame");
                assertEquals(0x10, pixels.get((74 * 200 + 75) * 4) & 0xff, "and what was drawn after");
                int red = pixels.get((80 * 200 + 120) * 4) & 0xff;
                int blue = pixels.get((80 * 200 + 120) * 4 + 2) & 0xff;
                assertEquals(true, red > 240 && blue < 15, "the image's red end, in the frame: " + red + ", " + blue);
            } finally {
                MemoryUtil.memFree(pixels);
            }
            Image image = made.get();
            // Each end pixel's centre lies half a unit into the gradient: 249 of 255.
            assertEquals(249, rgba(image, 0, 10)[0], 2, "the image: red at its start");
            assertEquals(249, rgba(image, 19, 10)[2], 2, "blue at its end");
        } finally {
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0);
            Gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]); // as the next test finds it
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
            canvas.dispose();
        }
    }

    @Test
    void withNoPainterThereIsNoImageAndItSaysSo() {
        OffscreenPainter.uninstall(painter);
        painter = null;
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Images.render(10, 10, 1, c -> { }));
        assertEquals(true, refused.getMessage().contains("no backend"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Images.render(0, 10, 1, c -> { }));
    }
}
