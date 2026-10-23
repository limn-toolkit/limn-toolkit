package limn.backend.lwjgl;

import limn.graphics.Color;
import limn.graphics.Image;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A part of the frame read back with its transparency, against a real device: what a drag's
 * picture is made from. Cleared to nothing and painted on, the part comes back see-through where
 * nothing was painted and opaque where something was.
 */
class GlReadBackTest {

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

    @Test
    void aClearedCornerPaintedOnComesBackWithItsTransparency() {
        int[] viewport = new int[4];
        Gl.glGetIntegerv(GL33C.GL_VIEWPORT, viewport);
        int w = viewport[2];
        int h = viewport[3];
        renderer.beginFrame(w, h, 1f, false);
        try {
            renderer.canvas().clear(Color.rgb(0x22CC44));
            renderer.canvas().clearRect(0, 0, 8, 8, Color.rgba(0, 0f));
            renderer.canvas().fillRect(2, 2, 4, 4, Color.rgb(0xFF0000));
            Image part = renderer.canvas().readBack(0, 0, 8, 8);
            assertNotNull(part, "the frame keeps transparency and can be read");
            assertEquals(8, part.width());
            byte[] rgba = part.pixels();
            assertEquals(0, rgba[3] & 0xff, "the corner nothing was painted on is see-through");
            int painted = (3 * 8 + 3) * 4;
            assertEquals(255, rgba[painted + 3] & 0xff, "what was painted is opaque");
            assertEquals(255, rgba[painted] & 0xff, "and red");
            assertEquals(0, rgba[painted + 1] & 0xff);
        } finally {
            renderer.endFrame();
        }
    }
}
