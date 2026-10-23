package limn.backend.lwjgl;

import limn.graphics.Color;
import limn.math.Mat4;
import limn.math.Quat;
import limn.math.Vec3;
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
 * A picture minified past two to one gets a mipmap chain for its composite, and no other does (ADR
 * 072 section 12, the owner's decision of 2026-10-18, answer 11 revised), against a real device: a
 * page of fine stripes turned far from the viewer, at rest, aliases less with the chain than without
 * it, held against the stripes averaged over each pixel by hand; a picture not minified past two to
 * one makes none, counted; and the pool's pictures keep working, their levels allocated only when a
 * composite needs them and freed with the picture.
 */
class GlMipmapLayerTest {

    private static final float SCALE = 2f;
    private static final int W_PT = 400;
    private static final int H_PT = 300;
    private static final int WIDTH = (int) (W_PT * SCALE);
    private static final int HEIGHT = (int) (H_PT * SCALE);
    private static final Color PAGE = Color.rgb(0x808080);
    /** The pivot every case turns about, in points. */
    private static final float CX = 200;
    private static final float CY = 150;
    /** The stripes' card, about the pivot, in points. */
    private static final float HALF_W = 120;
    private static final float HALF_H = 90;

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

    /** A turn of {@code degrees} about the vertical axis through the pivot, seen from {@code distance}. */
    private static Mat4 turned(float degrees, float distance) {
        Mat4 turn = Mat4.rotation(Quat.fromAxisAngle(Vec3.UNIT_Y, (float) Math.toRadians(degrees)));
        return Mat4.translation(new Vec3(CX, CY, 0)).multiply(Mat4.perspectiveAt(distance)).multiply(turn)
                .multiply(Mat4.translation(new Vec3(-CX, -CY, 0)));
    }

    /** Stripes one point wide, black and white, across the card: as fine as a line of small text. */
    private static void stripes(GlCanvas c) {
        c.fillRect(CX - HALF_W, CY - HALF_H, 2 * HALF_W, 2 * HALF_H, Color.WHITE);
        for (int i = 0; i < 2 * HALF_W; i += 2) {
            c.fillRect(CX - HALF_W + i, CY - HALF_H, 1, 2 * HALF_H, Color.BLACK);
        }
    }

    /**
     * The point of the card's plane, {@code u} from the pivot, that the ray through the window point
     * {@code x} in points meets, for a turn of {@code degrees} about the vertical axis seen from
     * {@code distance}, where a point is drawn at {@code u cos / w} from the pivot with
     * {@code w = 1 + u sin / distance}.
     */
    private static double sentBack(double x, double degrees, double distance) {
        double r = Math.toRadians(degrees);
        double dx = x - CX;
        return dx / (Math.cos(r) - dx * Math.sin(r) / distance);
    }

    /**
     * How far, on average over the pixels that lie wholly on the stripes, the frame is from the
     * stripes averaged over each pixel by hand: 16 points across each pixel sent back to the card,
     * each black or white by the stripe it lands on, their mean the pixel's true grey.
     */
    private static double meanError(byte[] image, double degrees, double distance) {
        double sum = 0;
        int counted = 0;
        for (int py = 0; py < HEIGHT; py++) {
            for (int px = 0; px < WIDTH; px++) {
                double u0 = sentBack(px / SCALE, degrees, distance);
                double u1 = sentBack((px + 1) / SCALE, degrees, distance);
                // Inside the card by two points, and well inside it top and bottom at either end.
                double w = 1 + Math.max(u0, u1) * Math.sin(Math.toRadians(degrees)) / distance;
                double v0 = (py / SCALE - CY) * w;
                double v1 = ((py + 1) / SCALE - CY) * w;
                if (Math.min(u0, u1) < -HALF_W + 2 || Math.max(u0, u1) > HALF_W - 2
                        || Math.min(v0, v1) < -HALF_H + 4 || Math.max(v0, v1) > HALF_H - 4) {
                    continue;
                }
                double white = 0;
                for (int s = 0; s < 16; s++) {
                    double u = sentBack((px + (s + 0.5) / 16) / SCALE, degrees, distance);
                    white += Math.floorMod((long) Math.floor(u + HALF_W), 2L) == 1 ? 1 : 0;
                }
                double truth = 255 * white / 16;
                sum += Math.abs((image[(py * WIDTH + px) * 4] & 0xFF) - truth);
                counted++;
            }
        }
        assertTrue(counted > 20_000, "enough of the card is checked: " + counted);
        return sum / counted;
    }

    @Test
    void aPageTurnedFarFromTheViewerAliasesLessWithItsMipmaps() {
        // 75 degrees from 600 points: the card is seen at a third to a sixth of its picture's size
        // across, so a bilinear composite reads one texel in three to seven and the stripes beat.
        float degrees = 75;
        float distance = 600;
        Consumer<GlCanvas> page = c -> {
            c.saveLayer(CX - HALF_W, CY - HALF_H, 2 * HALF_W, 2 * HALF_H, 1f, turned(degrees, distance));
            stripes(c);
            c.restore();
        };
        canvas.mipmapPictures = false;
        byte[] bilinear = render(page);
        double before = meanError(bilinear, degrees, distance);
        assertEquals(0, canvas.mipmapsMade(), "no chain while they are turned off");

        canvas.mipmapPictures = true;
        byte[] trilinear = render(page);
        double after = meanError(trilinear, degrees, distance);
        assertEquals(1, canvas.mipmapsMade(), "one chain, for the one composite");
        // Measured on this Mac's GPU, 2026-10-18: 71 steps without, 25 with. The chain does not reach
        // the truth: a trilinear read averages over about twice a pixel's footprint, and the true
        // grey of a pixel two or three stripes wide swings by a tenth either side of the mean.
        assertTrue(after < before / 2 && after < 32,
                "the stripes averaged within " + after + " steps with mipmaps, against " + before + " without");
    }

    @Test
    void aPictureNotMinifiedPastTwoToOneMakesNoMipmaps() {
        // Turned 20 degrees, the far edge is seen at three quarters of the picture's size; rotated in
        // the plane, at its full size; the card turned far, past two to one, makes one a frame.
        render(c -> {
            c.saveLayer(CX - HALF_W, CY - HALF_H, 2 * HALF_W, 2 * HALF_H, 1f, turned(20, 600));
            stripes(c);
            c.restore();
            c.saveLayer(20, 20, 60, 40, 1f, Mat4.translation(new Vec3(50, 40, 0))
                    .multiply(Mat4.rotation(Quat.fromAxisAngle(Vec3.UNIT_Z, (float) Math.toRadians(30))))
                    .multiply(Mat4.translation(new Vec3(-50, -40, 0))));
            c.fillRect(20, 20, 60, 40, Color.BLACK);
            c.restore();
        });
        assertEquals(0, canvas.mipmapsMade(), "a turn of 20 degrees and a rotation make none");
        for (int texture : canvas.layerTextures()) {
            assertEquals(0, levelWidth(texture, 1), "and no picture holds a level past its own");
        }
        for (int frame = 1; frame <= 3; frame++) {
            render(c -> {
                c.saveLayer(CX - HALF_W, CY - HALF_H, 2 * HALF_W, 2 * HALF_H, 1f, turned(75, 600));
                stripes(c);
                c.restore();
            });
            assertEquals(frame, canvas.mipmapsMade(), "the turn far from the viewer makes one a frame");
        }
    }

    @Test
    void thePoolsPicturesKeepWorkingAndTheirLevelsGoWithThem() {
        Consumer<GlCanvas> farTurn = c -> {
            c.saveLayer(CX - HALF_W, CY - HALF_H, 2 * HALF_W, 2 * HALF_H, 1f, turned(75, 600));
            stripes(c);
            c.restore();
        };
        Consumer<GlCanvas> nearTurn = c -> {
            c.saveLayer(CX - HALF_W, CY - HALF_H, 2 * HALF_W, 2 * HALF_H, 1f, turned(20, 600));
            stripes(c);
            c.restore();
        };
        render(farTurn);
        int[] held = canvas.layerTextures();
        assertEquals(1, held.length);
        int width = levelWidth(held[0], 0);
        int height = levelHeight(held[0], 0);
        assertTrue(levelWidth(held[0], 1) > 0, "the chain's levels are allocated");
        long base = (long) width * height * 4;
        long counted = canvas.layerStats().textureBytes();
        assertTrue(counted > base * 5 / 4 && counted < base * 4 / 3 + 64,
                "the chain counted, a third more: " + counted + " against " + base);

        // The same picture taken again for a composite that needs no chain draws what a fresh one
        // draws, within a step of rounding, the larger texture's coordinates being other numbers: its
        // stale levels, a page of stripes, are never read.
        byte[] reused = render(nearTurn);
        assertEquals(held[0], canvas.layerTextures()[0], "the pool's picture, taken again");
        GlCanvas fresh = new GlCanvas(fonts);
        try {
            byte[] expected = render(fresh, nearTurn);
            assertAtMost(1, expected, reused, "a reused picture's composite");
        } finally {
            fresh.dispose();
        }

        // A frame that opens no layer gives the picture, and its levels, back to the driver.
        render(c -> { });
        assertEquals(0, canvas.layerTextures().length);
        assertEquals(0, canvas.layerStats().textureBytes());
        assertTrue(!Gl.glIsTexture(held[0]), "the texture and its levels deleted");
    }

    // ------------------------------------------------------------------ plumbing

    private static int levelWidth(int texture, int level) {
        HeadlessGl.assumeDesktop("reads a level's size with glGetTexLevelParameteri, which ES 3.0 lacks");
        Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        int width = GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D, level, GL33C.GL_TEXTURE_WIDTH);
        Gl.glBindTexture(GL33C.GL_TEXTURE_2D, 0);
        return width;
    }

    private static int levelHeight(int texture, int level) {
        HeadlessGl.assumeDesktop("reads a level's size with glGetTexLevelParameteri, which ES 3.0 lacks");
        Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        int height = GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D, level, GL33C.GL_TEXTURE_HEIGHT);
        Gl.glBindTexture(GL33C.GL_TEXTURE_2D, 0);
        return height;
    }

    /** Fails naming the worst pixel when any channel of any pixel differs by more than {@code tolerance}. */
    private static void assertAtMost(int tolerance, byte[] expected, byte[] actual, String what) {
        int worst = 0;
        int where = 0;
        for (int i = 0; i < expected.length; i++) {
            if (i % 4 == 3) {
                continue;
            }
            int d = Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF));
            if (d > worst) {
                worst = d;
                where = i / 4;
            }
        }
        assertTrue(worst <= tolerance, what + ": off by " + worst + " at " + (where % WIDTH) + "," + (where / WIDTH));
    }

    private byte[] render(Consumer<GlCanvas> paint) {
        return render(canvas, paint);
    }

    /** One frame of {@code canvas} over the page into a texture of its own, read back top-down. */
    private static byte[] render(GlCanvas canvas, Consumer<GlCanvas> paint) {
        int texture = Gl.glGenTextures();
        int fbo = Gl.glGenFramebuffers();
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
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE, Gl.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER));
            Gl.glViewport(0, 0, WIDTH, HEIGHT);
            canvas.beginFrame(WIDTH, HEIGHT, SCALE);
            canvas.clear(PAGE);
            paint.accept(canvas);
            canvas.endFrame();
            return readTopDown();
        } finally {
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            Gl.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
        }
    }

    private static byte[] readTopDown() {
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
