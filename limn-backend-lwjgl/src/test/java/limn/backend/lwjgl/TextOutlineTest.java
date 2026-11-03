package limn.backend.lwjgl;

import limn.backend.spi.GlyphOutlines;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Path2D;
import limn.graphics.Rect;
import limn.graphics.ShapedText;
import limn.graphics.Stroke;
import limn.graphics.TextRulers;
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
 * Text as a shape, with the backend's own fonts and outlines: the outline of a line covers what the
 * line drawn as text covers, stands where it is drawn, and is what a clip by the text lets through.
 */
class TextOutlineTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 240;
    private static final int H_PT = 160;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final Color PAGE = Color.WHITE;
    private static final Color INK = Color.BLACK;
    private static final Font FONT = Font.of(48);

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;
    private ShapingRuler ruler;
    private FontOutlines outlines;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        fonts = new FontStore();
        ruler = new ShapingRuler(fonts);
        outlines = new FontOutlines(fonts);
        TextRulers.install(ruler);
        GlyphOutlines.install(outlines);
        canvas = new GlCanvas(fonts);
    }

    @AfterEach
    void closeContext() {
        if (canvas != null) {
            canvas.dispose();
        }
        if (outlines != null) {
            GlyphOutlines.uninstall(outlines);
        }
        if (ruler != null) {
            TextRulers.uninstall(ruler);
            ruler.close();
        }
        if (fonts != null) {
            fonts.close();
        }
        if (ui != null) {
            ui.close();
        }
    }

    @Test
    void aLinesOutlineCoversWhatTheLineDrawnAsTextCovers() {
        ShapedText line = ruler.shape("Limn Ágil", FONT);
        Path2D outline = line.outline(12, 90);
        assertFalse(outline.isEmpty());
        double text = coverage(render(PAGE, c -> c.drawText(line, 12, 90, INK)));
        double shape = coverage(render(PAGE, c -> c.fillPath(outline, INK)));
        // The text is drawn from a rasterized atlas, the outline as a path: their antialiasing differs
        // by a share of a pixel along every edge, and no more.
        assertEquals(text, shape, text * 0.04, "text " + text + ", outline " + shape);
    }

    @Test
    void theOutlineStandsWhereTheLineIsDrawn() {
        ShapedText line = ruler.shape("Limn", FONT);
        Rect box = line.outline(12, 90).bounds();
        assertTrue(box.x() >= 12 - 1 && box.x() < 12 + 8, "starts at the pen: " + box);
        assertTrue(box.x() + box.width() <= 12 + line.metrics().width() + 1, "ends by its advance: " + box);
        assertTrue(box.y() + box.height() <= 90 + 1, "on the baseline, for letters with no descent: " + box);
        assertTrue(box.y() >= 90 - line.metrics().ascent() - 1, "under the ascent: " + box);
    }

    @Test
    void aClipByTheTextLetsThroughWhatItsFillCovers() {
        ShapedText line = ruler.shape("LIMN", FONT);
        Path2D outline = line.outline(12, 100);
        byte[] clipped = render(PAGE, c -> {
            c.clipPath(outline);
            c.fillRect(0, 0, W_PT, H_PT, INK);
        });
        byte[] filled = render(PAGE, c -> c.fillPath(outline, INK));
        assertEquals(coverage(filled), coverage(clipped), coverage(filled) * 0.002);
        // And it strokes: a dashed outline of the letters covers less than they do and more than none.
        double dashed = coverage(render(PAGE, c -> c.drawPath(outline, Stroke.of(1.5f).withDash(4, 3), INK)));
        assertTrue(dashed > 100 && dashed < coverage(filled), "dashed: " + dashed);
    }

    /** Ink on the page, in pixels: each pixel counted by how dark it is. */
    private static double coverage(byte[] image) {
        double sum = 0;
        for (int i = 0; i < image.length; i += 4) {
            sum += (255 - (image[i] & 0xff)) / 255.0;
        }
        return sum;
    }

    /** Draws a frame cleared to {@code page} into a framebuffer of its own with a stencil, read top-down. */
    private byte[] render(Color page, Consumer<GlCanvas> paint) {
        int texture = Gl.glGenTextures();
        int fbo = Gl.glGenFramebuffers();
        int depthStencil = Gl.glGenRenderbuffers();
        int previousFbo = Gl.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        Gl.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        try {
            Gl.glActiveTexture(GL33C.GL_TEXTURE0);
            Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
            Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            Gl.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            Gl.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, depthStencil);
            Gl.glRenderbufferStorage(GL33C.GL_RENDERBUFFER, GL33C.GL_DEPTH24_STENCIL8, WIDTH, HEIGHT);
            Gl.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, 0);
            Gl.glFramebufferRenderbuffer(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_STENCIL_ATTACHMENT,
                    GL33C.GL_RENDERBUFFER, depthStencil);
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE, Gl.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER));
            Gl.glViewport(0, 0, WIDTH, HEIGHT);
            canvas.beginFrame(WIDTH, HEIGHT, SCALE);
            canvas.clear(page);
            paint.accept(canvas);
            canvas.endFrame();
            ByteBuffer pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
            try {
                Gl.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
                Gl.glReadPixels(0, 0, WIDTH, HEIGHT, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
                byte[] image = new byte[WIDTH * HEIGHT * 4];
                int row = WIDTH * 4;
                for (int y = 0; y < HEIGHT; y++) {
                    pixels.position((HEIGHT - 1 - y) * row);
                    pixels.get(image, y * row, row);
                }
                return image;
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } finally {
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            Gl.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
            Gl.glDeleteRenderbuffers(depthStencil);
        }
    }
}
