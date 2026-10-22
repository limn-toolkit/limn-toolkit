package limn.backend.lwjgl;

import limn.concurrent.UiRuntime;
import limn.graphics.Color;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a bordered surface fills meets its border where the border is drawn, at any offset from the
 * pixel grid, against a real device: none of it shows past the border, and no background shows
 * between the two.
 *
 * <p>The defect this exists for: the canvas moves a stroke's centre lines onto the device grid, by
 * up to half a pixel, and left a fill's edges where they were. A surface's fill stops at its
 * border's centre line, so the two disagreed by as much as the border moved. Where it moved
 * inward, the fill's corner arcs lost their common centre with the border's and poked past its
 * outer edge on the diagonal; where it moved outward, or rounded to a thinner device width, the
 * background showed through between the fill and the border's inner edge. Measured on this very
 * scene before the fix, over sixteen offsets: the fill past a one-point border on 45 pixels at 1x,
 * 10 at 1.25x and 4 at 2x (27 and 2 through the clip), the worst at 35% fill; and a seam on 33
 * pixels at 1x, 84 at 1.25x and 6 at 2x (23 and 64 through the clip).
 *
 * <p>How it is read: the background is black, the border pure blue, the fill pure red, so a
 * pixel's blue is exactly the border's coverage, its red exactly the fill's share, and what is
 * left the background's. The border's side of things is found from the pixels alone: everything
 * reachable from the cell's edge through 4-connected pixels less than half covered by the border
 * lies outside it, and the rest that is less than half covered lies inside it. A border at least a
 * device pixel wide always leaves a closed ring of pixels at least half covered, because a step to
 * a 4-neighbour moves the distance to the border's centre line by at most one pixel and so some
 * step lands within half a pixel of it; so the test never needs to know where the grid put the
 * border. A pixel counts when the colour that does not belong there is above 20 (8%), where it
 * stops reading as a blend of the two that do.
 */
class BorderedFillGeometryTest {

    /** 1.5 and 2.5 round a one-point border up to 2 and 3 device pixels; 1.25 rounds it down to 1. */
    private static final float[] SCALES = {1f, 1.25f, 1.5f, 2f, 2.5f};
    private static final float[] OFFSETS = {0f, .25f, .5f, .75f};
    private static final float BORDER_WIDTH = 1;
    private static final float BOX_W = 40;
    private static final float BOX_H = 24;
    private static final float RADIUS = 6;
    private static final float CELL_W = 48;
    private static final float CELL_H = 32;
    private static final float MARGIN = 4;
    private static final int STRAY_SHARE_LIMIT = 20;
    private static final Color BACKGROUND = Color.rgb(0x000000);
    private static final Color BORDER = Color.rgb(0x0000FF);
    private static final Color FILL = Color.rgb(0xFF0000);

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
    void aFillInsideABorderNeverShowsPastIt() {
        assertNoStrayColour(true, "the fill shows past its border");
    }

    @Test
    void aFillInsideABorderLeavesNoSeamUnderIt() {
        assertNoStrayColour(false, "the background shows between the fill and its border");
    }

    @Test
    void aFillWithoutABorderKeepsItsOwnEdges() {
        // Only a fill that stops at a border follows the border onto the grid. One standing alone
        // keeps its edge where it was asked for: half a point into a pixel at 1x covers half of it.
        int width = (int) Math.ceil(2 * MARGIN + 4 * CELL_W);
        int height = (int) Math.ceil(2 * MARGIN + 4 * CELL_H);
        byte[] pixels = render(1f, width, height, target -> target.fillRoundRect(4.5f, 4, 40, 24, 6, FILL));
        int red = pixels[(16 * width + 4) * 4] & 0xFF;
        assertTrue(red > 100 && red < 156, "the pixel the fill's left edge halves reads " + red
                + "/255 of the fill, where about half was asked for");
    }

    // ------------------------------------------------------------------ cases

    /**
     * Renders both routes at every scale and fails with the count per case: fill outside the
     * border when {@code outside}, background inside it otherwise.
     */
    private void assertNoStrayColour(boolean outside, String what) {
        StringBuilder report = new StringBuilder();
        int total = 0;
        for (boolean clipped : new boolean[] {false, true}) {
            for (float scale : SCALES) {
                Frame frame = renderBoxes(scale, clipped);
                int caseTotal = 0;
                int worst = 0;
                StringBuilder cells = new StringBuilder();
                for (int ix = 0; ix < OFFSETS.length; ix++) {
                    for (int iy = 0; iy < OFFSETS.length; iy++) {
                        int[] stray = strayColour(frame, ix, iy, outside);
                        caseTotal += stray[0];
                        worst = Math.max(worst, stray[1]);
                        if (stray[0] > 0) {
                            cells.append(String.format(Locale.ROOT, " (%.2f,%.2f)=%d",
                                    OFFSETS[ix], OFFSETS[iy], stray[0]));
                        }
                    }
                }
                total += caseTotal;
                report.append(String.format(Locale.ROOT, "%n  %s at %.2fx: %d pixel(s), share up to "
                                + "%d/255%s", clipped ? "clip + fillRect" : "fillRoundRectWithBorder",
                        scale, caseTotal, worst, cells));
            }
        }
        assertEquals(0, total, what + " in " + total + " device pixel(s) over five scales, sixteen "
                + "offsets each (x,y offset in points = count):" + report
                + "\non " + HeadlessGl.describe());
    }

    private record Frame(byte[] pixels, int width, int height, float scale) {
    }

    private Frame renderBoxes(float scale, boolean clipped) {
        int width = (int) Math.ceil((2 * MARGIN + 4 * CELL_W) * scale);
        int height = (int) Math.ceil((2 * MARGIN + 4 * CELL_H) * scale);
        byte[] pixels = render(scale, width, height, target -> {
            for (int ix = 0; ix < OFFSETS.length; ix++) {
                for (int iy = 0; iy < OFFSETS.length; iy++) {
                    float x = MARGIN + ix * CELL_W + OFFSETS[ix];
                    float y = MARGIN + iy * CELL_H + OFFSETS[iy];
                    if (clipped) {
                        // A surface whose fill is not one paint: clip, paint the whole box, border.
                        target.save();
                        target.clipRoundRectInsideBorder(x, y, BOX_W, BOX_H, RADIUS, BORDER_WIDTH);
                        target.fillRect(x, y, BOX_W, BOX_H, FILL);
                        target.restore();
                        target.drawRoundRectBorder(x, y, BOX_W, BOX_H, RADIUS, BORDER_WIDTH, BORDER);
                    } else {
                        target.fillRoundRectWithBorder(x, y, BOX_W, BOX_H, RADIUS, FILL,
                                BORDER_WIDTH, BORDER);
                    }
                }
            }
        });
        return new Frame(pixels, width, height, scale);
    }

    /**
     * {pixels showing more than the limit of a colour that does not belong, the largest share any
     * shows} for the box in cell (ix, iy): the fill outside the border when {@code outside}, the
     * background inside it otherwise. Fails if the box has no border or no fill to measure.
     */
    private static int[] strayColour(Frame frame, int ix, int iy, boolean outside) {
        float cellX = MARGIN + ix * CELL_W;
        float cellY = MARGIN + iy * CELL_H;
        int x0 = (int) Math.ceil(cellX * frame.scale());
        int y0 = (int) Math.ceil(cellY * frame.scale());
        int x1 = Math.min(frame.width(), (int) Math.floor((cellX + CELL_W) * frame.scale()));
        int y1 = Math.min(frame.height(), (int) Math.floor((cellY + CELL_H) * frame.scale()));
        int w = x1 - x0;
        int h = y1 - y0;
        boolean[] reached = new boolean[w * h];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        for (int i = 0; i < w; i++) {
            pending.add(i);
            pending.add((h - 1) * w + i);
        }
        for (int j = 0; j < h; j++) {
            pending.add(j * w);
            pending.add(j * w + w - 1);
        }
        int ring = 0;
        while (!pending.isEmpty()) {
            int k = pending.poll();
            if (reached[k]) {
                continue;
            }
            int i = k % w;
            int j = k / w;
            if (isBorder(frame, x0 + i, y0 + j)) {
                ring++;
                continue;
            }
            reached[k] = true;
            if (i > 0) {
                pending.add(k - 1);
            }
            if (i < w - 1) {
                pending.add(k + 1);
            }
            if (j > 0) {
                pending.add(k - w);
            }
            if (j < h - 1) {
                pending.add(k + w);
            }
        }
        int centreX = Math.round((cellX + OFFSETS[ix] + BOX_W / 2) * frame.scale());
        int centreY = Math.round((cellY + OFFSETS[iy] + BOX_H / 2) * frame.scale());
        String where = String.format(Locale.ROOT, "the box at offset (%.2f, %.2f), %.2fx",
                OFFSETS[ix], OFFSETS[iy], frame.scale());
        assertTrue(ring > 0, where + " has no border to measure against");
        assertTrue(channel(frame, centreX, centreY, 0) >= 250 && !reached[(centreY - y0) * w + centreX - x0],
                where + " has no fill inside its border");
        int count = 0;
        int worst = 0;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int x = x0 + i;
                int y = y0 + j;
                int share;
                if (outside) {
                    if (!reached[j * w + i]) {
                        continue;
                    }
                    share = channel(frame, x, y, 0);
                } else {
                    if (reached[j * w + i] || isBorder(frame, x, y)) {
                        continue; // the border's own outer fringe mixes in background, rightly
                    }
                    share = 255 - channel(frame, x, y, 0) - channel(frame, x, y, 2);
                }
                worst = Math.max(worst, share);
                if (share > STRAY_SHARE_LIMIT) {
                    count++;
                }
            }
        }
        return new int[] {count, worst};
    }

    /** At least half covered by the border: its blue is the border's coverage. */
    private static boolean isBorder(Frame frame, int x, int y) {
        return channel(frame, x, y, 2) >= 127;
    }

    private static int channel(Frame frame, int x, int y, int channel) {
        return frame.pixels()[(y * frame.width() + x) * 4 + channel] & 0xFF;
    }

    // --------------------------------------------------------------- readback

    /** Renders one frame into a fresh {@code width × height} target and reads it back top-down. */
    private byte[] render(float scale, int width, int height, Consumer<GlCanvas> paint) {
        int texture = Gl.glGenTextures();
        int fbo = Gl.glGenFramebuffers();
        int previousFbo = Gl.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        Gl.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        try {
            Gl.glActiveTexture(GL33C.GL_TEXTURE0);
            Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, width, height, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
            Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            Gl.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE,
                    Gl.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER), "render target");
            Gl.glViewport(0, 0, width, height);
            canvas.beginFrame(width, height, scale);
            canvas.clear(BACKGROUND);
            paint.accept(canvas);
            canvas.endFrame();
            ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
            try {
                Gl.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
                Gl.glReadPixels(0, 0, width, height, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
                byte[] out = new byte[width * height * 4];
                int stride = width * 4;
                for (int row = 0; row < height; row++) {
                    pixels.position((height - 1 - row) * stride);
                    pixels.get(out, row * stride, stride);
                }
                return out;
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } finally {
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            Gl.glViewport(previousViewport[0], previousViewport[1],
                    previousViewport[2], previousViewport[3]);
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
        }
    }
}
