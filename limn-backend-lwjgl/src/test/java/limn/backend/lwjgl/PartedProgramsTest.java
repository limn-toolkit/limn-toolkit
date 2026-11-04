package limn.backend.lwjgl;

import limn.concurrent.UiRuntime;
import limn.graphics.BackdropEffect;
import limn.graphics.BlendMode;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Gradient;
import limn.graphics.Image;
import limn.graphics.ImagePaint;
import limn.graphics.LinearGradient;
import limn.graphics.Path2D;
import limn.graphics.RadialGradient;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the renderer runs fragment shaders on the processor, each flush draws with the canvas
 * program compiled with only the parts its primitives use, and draws the pixels the program with
 * all of them drew, against a real device: every kind of primitive, every paint, every clip and a
 * blend rule. A primitive that left out a part it needs would draw wrong here.
 *
 * <p>The cost this exists for: Mesa's llvmpipe runs every branch of the program for every pixel,
 * the shadow's and the glass's included, so a 512-pixel disc with a gradient and two letters took
 * 41 ms with all of them and 9.7 ms with its own.
 */
class PartedProgramsTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 140;
    private static final Color BACKGROUND = Color.rgb(0x203040);
    private static final Color RED = Color.rgb(0xE04020);
    private static final Color BLUE = Color.rgba(0x2060E0, 0.7f);
    private static final Color WHITE = Color.rgb(0xF0F0F0);

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;
    private UiRuntime runtime;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        runtime = ui.runtime();
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
        if (runtime != null) {
            ui.close();
            runtime = null;
        }
    }

    @Test
    void theCanvasReadsItsDevice() {
        assertEquals(HeadlessGl.isSoftware(), canvas.partedPrograms,
                "the canvas's reading of " + HeadlessGl.describe());
    }

    @Test
    void shapesAndPaints() {
        assertSamePixels("shapes and paints", c -> {
            c.fillRoundRect(4, 4, 40, 30, 6, RED);
            c.fillEllipse(70, 20, 22, 14, new LinearGradient(48, 6, 92, 34, RED, BLUE));
            c.drawCircle(120, 20, 15, 3, new RadialGradient(120, 20, 18, WHITE, BLUE));
            c.fillRect(140, 4, 50, 30, Gradient.linear(140, 4, 190, 34, RED, BLUE));
            c.fillPath(new Path2D().addCircle(25, 60, 18), Gradient.sweep(25, 60, 0, RED, BLUE, RED));
            c.fillPath(new Path2D().moveTo(60, 42).lineTo(95, 78).lineTo(60, 78).lineTo(95, 42).close(), WHITE);
            c.drawLine(100, 45, 190, 75, 2, RED);
            c.fillCircle(150, 105, 30, Gradient.radial(150, 105, 30, WHITE, RED).withFocus(140, 95));
        });
    }

    @Test
    void textImagesAndImagePaint() {
        Image checker = checker();
        assertSamePixels("text, images and image paint", c -> {
            c.drawText("Limn 123", 6, 24, Font.of(16), WHITE);
            c.drawText("Limn", 6, 50, Font.of(22), Gradient.linear(6, 30, 70, 50, RED, BLUE));
            c.drawImage(checker, 100, 6, 32, 32);
            c.drawImage(checker, 140, 6, 32, 32, RED);
            c.fillRoundRect(100, 50, 90, 40, 10, ImagePaint.of(checker));
        });
    }

    @Test
    void shadowGlassClipsAndARule() {
        BackdropEffect frost = new BackdropEffect.Blur(Color.rgba(0xFFFFFF, 0.2f), 6f, BackdropEffect.Blur.Axis.X);
        assertSamePixels("shadow, glass, clips and a rule", c -> {
            c.fillRect(0, 0, 200, 70, Gradient.linear(0, 0, 200, 0, RED, BLUE));
            c.drawShadow(RoundRect.of(10, 10, 50, 40, 8), new Shadow(0, 4, 10, Color.rgba(0x000000, 0.4f)));
            c.fillRoundRect(10, 10, 50, 40, 8, WHITE);
            c.fillBackdropRoundRect(70, 10, 60, 50, 10, frost);
            c.save();
            c.clipRoundRect(RoundRect.of(140, 8, 50, 50, 14));
            c.fillCircle(165, 33, 30, RED);
            c.restore();
            c.save();
            c.clipPath(new Path2D().addCircle(40, 105, 28));
            c.fillRect(0, 70, 90, 70, Gradient.linear(0, 70, 90, 140, WHITE, BLUE));
            c.restore();
            c.save();
            c.setBlendMode(BlendMode.DARKEN);
            c.fillRoundRect(100, 80, 90, 50, 12, Color.rgba(0x40C080, 0.8f));
            c.restore();
        });
    }

    @Test
    void aProgramIdleLongerThanAllowedGoesAndIsMadeAgainWhenNeeded() {
        Consumer<GlCanvas> scene = c -> {
            c.fillRoundRect(4, 4, 60, 40, 8, Gradient.linear(4, 4, 64, 44, RED, BLUE));
            c.drawText("Limn", 80, 30, Font.of(18), WHITE);
        };
        canvas.partedPrograms = true;
        byte[] first = render(scene);
        int made = canvas.partedProgramCount();
        assertTrue(made > 0, "programs with fewer parts made");

        long now = System.nanoTime();
        canvas.letIdleProgramsGo(now + 10_000_000_000L, 0);
        assertEquals(made, canvas.partedProgramCount(), "an idle time of 0 keeps every one");
        canvas.letIdleProgramsGo(now + 10_000_000_000L, 30_000_000_000L);
        assertEquals(made, canvas.partedProgramCount(), "drawn with 10 s ago, under 30 s idle");
        canvas.letIdleProgramsGo(now + 40_000_000_000L, 30_000_000_000L);
        assertEquals(0, canvas.partedProgramCount(), "idle past 30 s, let go");

        byte[] again = render(scene);
        assertEquals(made, canvas.partedProgramCount(), "made again when a flush needs them");
        assertTrue(java.util.Arrays.equals(first, again), "and drawing the same pixels");
    }

    // --------------------------------------------------------------- helpers

    /**
     * Renders {@code paint} with the program with all its parts and with the parted ones, and fails
     * with the pixels that differ by more than one: the same arithmetic, compiled apart, may round a
     * channel the other way.
     */
    private void assertSamePixels(String what, Consumer<GlCanvas> paint) {
        canvas.partedPrograms = false;
        byte[] whole = render(paint);
        canvas.partedPrograms = true;
        byte[] parted = render(paint);
        assertTrue(canvas.partedProgramCount() > 0, what + ": no program with fewer parts was made");
        int offByOne = 0;
        int further = 0;
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < whole.length; i += 4) {
            int delta = 0;
            for (int channel = 0; channel < 4; channel++) {
                delta = Math.max(delta, Math.abs((whole[i + channel] & 0xFF) - (parted[i + channel] & 0xFF)));
            }
            if (delta == 1) {
                offByOne++;
            } else if (delta > 1) {
                further++;
                if (further <= 6) {
                    where.append(String.format(" (%d,%d) %d", (i / 4) % WIDTH, HEIGHT - 1 - (i / 4) / WIDTH, delta));
                }
            }
        }
        assertEquals(0, further, what + ": pixels differ by more than one at" + where + " on " + HeadlessGl.describe());
        assertTrue(offByOne * 100 < WIDTH * HEIGHT, what + ": " + offByOne + " pixels off by one");
    }

    private static Image checker() {
        byte[] rgba = new byte[16 * 16 * 4];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int i = (y * 16 + x) * 4;
                boolean on = ((x / 4) + (y / 4)) % 2 == 0;
                rgba[i] = (byte) (on ? 255 : 30);
                rgba[i + 1] = (byte) (on ? 200 : 60);
                rgba[i + 2] = (byte) (on ? 40 : 200);
                rgba[i + 3] = (byte) (on ? 255 : 160);
            }
        }
        return new Image(16, 16, rgba);
    }

    private byte[] render(Consumer<GlCanvas> paint) {
        int texture = Gl.glGenTextures();
        int fbo = Gl.glGenFramebuffers();
        int depthStencil = Gl.glGenRenderbuffers();
        int previousFbo = Gl.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        Gl.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        ByteBuffer pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        try {
            Gl.glActiveTexture(GL33C.GL_TEXTURE0);
            Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            Gl.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            Gl.glBindRenderbuffer(GL33C.GL_RENDERBUFFER, depthStencil);
            Gl.glRenderbufferStorage(GL33C.GL_RENDERBUFFER, GL33C.GL_DEPTH24_STENCIL8, WIDTH, HEIGHT);
            Gl.glFramebufferRenderbuffer(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_STENCIL_ATTACHMENT,
                    GL33C.GL_RENDERBUFFER, depthStencil);
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE, Gl.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER),
                    "render target");
            Gl.glViewport(0, 0, WIDTH, HEIGHT);
            canvas.beginFrame(WIDTH, HEIGHT, 1);
            canvas.clear(BACKGROUND);
            canvas.save();
            paint.accept(canvas);
            canvas.restore();
            canvas.endFrame();
            Gl.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            Gl.glReadPixels(0, 0, WIDTH, HEIGHT, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
            byte[] out = new byte[WIDTH * HEIGHT * 4];
            pixels.get(out);
            return out;
        } finally {
            MemoryUtil.memFree(pixels);
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            Gl.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
            Gl.glDeleteRenderbuffers(depthStencil);
        }
    }
}
