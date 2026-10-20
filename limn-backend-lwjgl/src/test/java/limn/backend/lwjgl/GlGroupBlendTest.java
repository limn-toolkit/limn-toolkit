package limn.backend.lwjgl;

import limn.graphics.BlendMode;
import limn.graphics.Color;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Opacity;
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
 * A blend mode inside a group, against a real device: inside a layer a draw in another mode blends
 * with the group's own backdrop, which is transparent where the group has drawn nothing, as an
 * isolated group of the web's compositing rules does, so there the source shows unblended; over
 * what the group drew it blends with that; and with no group, at an opacity of 1, it blends with
 * the window. Each pixel is held to the premultiplied form of the web's formula,
 * {@code co = cs·(1 − αb) + αs·αb·B(Cb, Cs) + cb·(1 − αs)}, the group then laid over the page at its
 * opacity, on the GPU and on a software renderer.
 *
 * <p>Before, a draw in {@link BlendMode#MULTIPLY} inside a group multiplied the transparent
 * picture and drew nothing, at every opacity below 1, and multiplied the window at 1: a fade in
 * showed nothing and the multiplied draw appeared on its last frame.
 */
class GlGroupBlendTest {

    private static final int SIZE = 64;
    /** The window behind the group. */
    private static final Color PAGE = Color.rgb(0xF2A65A);
    /** What draws in the mode: the audit's tint. */
    private static final Color SOURCE = Color.rgb(0x8080FF);
    /** A second draw in the mode, over the first. */
    private static final Color SECOND = Color.rgb(0xC0A040);
    /** What the group draws itself, in NORMAL, under the source. */
    private static final Color GROUP = Color.rgb(0x40C080);
    private static final float HALF = 0.5f;
    /** Two roundings of 1/255, the layer's and the composite's, and one more for a second pass. */
    private static final int TOLERANCE = 2;

    private FontStore fonts;
    private HeadlessUi ui;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        fonts = new FontStore();
    }

    @AfterEach
    void closeContext() {
        if (fonts != null) {
            fonts.close();
            fonts = null;
        }
        if (ui != null) {
            ui.close();
            ui = null;
        }
    }

    @Test
    void insideAGroupAModeBlendsWithTheGroupsOwnBackdropOnThisDevice() {
        GlCanvas canvas = new GlCanvas(fonts);
        try {
            everyCase(canvas, HeadlessGl.describe());
        } finally {
            canvas.dispose();
        }
    }

    @Test
    void insideAGroupAModeBlendsWithTheGroupsOwnBackdropOnASoftwareRenderer() {
        HeadlessGl.onSoftwareRenderer(() -> {
            GlCanvas canvas = new GlCanvas(fonts);
            try {
                everyCase(canvas, "software: " + HeadlessGl.describe());
            } finally {
                canvas.dispose();
            }
        });
    }

    private void everyCase(GlCanvas canvas, String device) {
        for (BlendMode mode : new BlendMode[] {BlendMode.MULTIPLY, BlendMode.ADDITIVE}) {
            String what = mode + " on " + device + ": ";
            // Over a part of the group nothing else drew: the source, unblended, at the group's opacity.
            assertPixel(over(PAGE, HALF, blend(mode, SOURCE, 1, null, 0)), render(canvas, c -> {
                c.saveLayer(0, 0, SIZE, SIZE, HALF);
                inMode(c, mode, SOURCE, 1);
                c.restore();
            }), what + "over a transparent part of the group");
            // Over what the group drew: blended with that, and the result at the group's opacity.
            assertPixel(over(PAGE, HALF, blend(mode, SOURCE, 1, GROUP, 1)), render(canvas, c -> {
                c.saveLayer(0, 0, SIZE, SIZE, HALF);
                c.fillRect(0, 0, SIZE, SIZE, GROUP);
                inMode(c, mode, SOURCE, 1);
                c.restore();
            }), what + "over what the group drew");
            // Both translucent: the whole formula, the backdrop's coverage between the two answers.
            assertPixel(over(PAGE, HALF, blend(mode, SOURCE, 0.6f, GROUP, 0.5f)), render(canvas, c -> {
                c.saveLayer(0, 0, SIZE, SIZE, HALF);
                c.fillRect(0, 0, SIZE, SIZE, GROUP.withAlpha(0.5f));
                inMode(c, mode, SOURCE, 0.6f);
                c.restore();
            }), what + "translucent over translucent group content");
            // Two draws in the mode that overlap: the second blends with the first, as drawn one
            // after the other, though a mode drawn in two passes reads what its first pass left.
            assertPixel(over(PAGE, HALF, blend(mode, SECOND, 1, SOURCE, 1)), render(canvas, c -> {
                c.saveLayer(0, 0, SIZE, SIZE, HALF);
                c.save();
                c.setBlendMode(mode);
                c.fillRect(0, 0, SIZE, SIZE, SOURCE);
                c.fillRect(SIZE / 4f, SIZE / 4f, SIZE / 2f, SIZE / 2f, SECOND);
                c.restore();
                c.restore();
            }), what + "two draws that overlap");
            // A group composited in the mode into another group: the same rule for the picture,
            // over a part of the outer group that is transparent and over what it drew.
            assertPixel(over(PAGE, HALF, blend(mode, SOURCE, 1, null, 0)), render(canvas, c -> {
                c.saveLayer(0, 0, SIZE, SIZE, HALF);
                nestedInMode(c, mode);
                c.restore();
            }), what + "a group composited over a transparent part of the outer one");
            assertPixel(over(PAGE, HALF, blend(mode, SOURCE, 1, GROUP, 1)), render(canvas, c -> {
                c.saveLayer(0, 0, SIZE, SIZE, HALF);
                c.fillRect(0, 0, SIZE, SIZE, GROUP);
                nestedInMode(c, mode);
                c.restore();
            }), what + "a group composited over what the outer one drew");
            // No group at 1: the mode blends with the window, as it always has.
            assertPixel(over(PAGE, 1, blend(mode, SOURCE, 1, PAGE, 1)), render(canvas, c -> {
                c.saveLayer(0, 0, SIZE, SIZE, 1f);
                inMode(c, mode, SOURCE, 1);
                c.restore();
            }), what + "at 1, with the window");
        }
    }

    /**
     * Ten tints in MULTIPLY that do not overlap, inside a group over a card it drew: drawn in one
     * run, both passes over all ten, and not a pair of draws each. Each took a flush of its own,
     * 22 draw calls for the frame where it is 4.
     */
    @Test
    void tintsInMultiplyThatDoNotOverlapShareTheirTwoPasses() {
        GlCanvas canvas = new GlCanvas(fonts);
        try {
            Consumer<GlCanvas> card = c -> {
                c.saveLayer(0, 0, SIZE, SIZE, HALF);
                c.fillRect(0, 0, SIZE, SIZE, GROUP);
                c.save();
                c.setBlendMode(BlendMode.MULTIPLY);
                for (int i = 0; i < 10; i++) {
                    c.fillCircle(6 + (i % 5) * 12, 16 + (i / 5) * 24, 3, SOURCE.withAlpha(0.6f));
                }
                c.restore();
                c.restore();
            };
            render(canvas, card);
            int calls = canvas.drawCallsLastFrame();
            assertTrue(calls <= 5, "draw calls for the card, its ten tints and its composite: " + calls);
            // And still the formula at a tint's centre: the card, multiplied once.
            int[] atTint = renderAt(canvas, 6, 16, card);
            assertPixel(over(PAGE, HALF, blend(BlendMode.MULTIPLY, SOURCE, 0.6f, GROUP, 1)), atTint,
                    "at a tint's centre");
        } finally {
            canvas.dispose();
        }
    }

    /**
     * Tints in MULTIPLY one device pixel apart, the gap of a heat map's cells, inside a group over
     * nothing: they share their two passes, judged on the pixels each can cover rather than on its
     * quad, which reaches a pixel past its edge for the antialiasing, and the picture is the one
     * NORMAL draws there, as it must be over a transparent group. Judged on the quads, every tint
     * took two draw calls of its own: 21 for ten. Tints that overlap by a pixel still do not share.
     */
    @Test
    void tintsInMultiplyOnePixelApartShareTheirTwoPassesAndStayExact() {
        GlCanvas canvas = new GlCanvas(fonts);
        try {
            tintsOnePixelApart(canvas);
        } finally {
            canvas.dispose();
        }
    }

    @Test
    void tintsInMultiplyOnePixelApartShareTheirTwoPassesAndStayExactOnASoftwareRenderer() {
        HeadlessGl.onSoftwareRenderer(() -> {
            GlCanvas canvas = new GlCanvas(fonts);
            try {
                tintsOnePixelApart(canvas);
            } finally {
                canvas.dispose();
            }
        });
    }

    private void tintsOnePixelApart(GlCanvas canvas) {
        for (float gap : new float[] {1, -1}) {
            float cell = 5;
            java.util.function.BiConsumer<GlCanvas, BlendMode> tints = (c, mode) -> {
                c.saveLayer(0, 0, SIZE, SIZE, HALF);
                c.save();
                c.setBlendMode(mode);
                for (int i = 0; i < 10; i++) {
                    float x = 2 + (i % 5) * (cell + gap);
                    float y = 10 + (i / 5) * (cell + gap);
                    c.fillRoundRect(limn.graphics.RoundRect.of(x, y, cell, cell, 1.5f), SOURCE.withAlpha(0.6f));
                    c.fillCircle(x + cell / 2, y + 20 + cell / 2, cell / 2, SOURCE.withAlpha(0.6f));
                }
                c.restore();
                c.restore();
            };
            render(canvas, Color.TRANSPARENT, c -> tints.accept(c, BlendMode.NORMAL));
            byte[] normal = lastImage;
            render(canvas, Color.TRANSPARENT, c -> tints.accept(c, BlendMode.MULTIPLY));
            int calls = canvas.drawCallsLastFrame();
            byte[] multiply = lastImage;
            if (gap > 0) {
                assertTrue(worst(normal, multiply) <= 1, "MULTIPLY over nothing is NORMAL, off by "
                        + worst(normal, multiply));
                assertTrue(calls <= 4, "twenty tints a pixel apart share their passes: " + calls + " draw calls");
            } else {
                assertTrue(calls > 10, "tints that overlap take passes of their own: " + calls + " draw calls");
            }
        }
    }

    /**
     * Shapes in MULTIPLY that touch, or stand less than a device pixel apart, inside a group over
     * nothing, a white one drawn before a black one, the worst pair for the second pass: the
     * picture is the one each draws in a run of its own (the run broken between them by a NORMAL
     * draw in a far corner), bars of a chart touching at whole pixels with different heights, a
     * rounded corner beside a bar, two circles, tiles at fractional positions. Judged on bounds
     * that assumed an outline's coverage stops half a pixel past it, they shared a run while a
     * pixel diagonal to a corner, where the antialiasing ramp widens, had coverage from both: the
     * pass order changed it by up to 7 steps on a GPU and 21 on a software renderer.
     */
    @Test
    void shapesInMultiplyLessThanAPixelApartDrawAsInRunsOfTheirOwn() {
        GlCanvas canvas = new GlCanvas(fonts);
        try {
            lessThanAPixelApart(canvas);
        } finally {
            canvas.dispose();
        }
    }

    @Test
    void shapesInMultiplyLessThanAPixelApartDrawAsInRunsOfTheirOwnOnASoftwareRenderer() {
        HeadlessGl.onSoftwareRenderer(() -> {
            GlCanvas canvas = new GlCanvas(fonts);
            try {
                lessThanAPixelApart(canvas);
            } finally {
                canvas.dispose();
            }
        });
    }

    private void lessThanAPixelApart(GlCanvas canvas) {
        java.util.List<String> failures = new java.util.ArrayList<>();
        for (float top : new float[] {20f, 20.35f, 20.7f}) {
            for (float gap : new float[] {0f, 0.2f, 0.45f, 0.8f}) {
                float t = top;
                float g = gap;
                pair(canvas, failures, "bars, top " + top + ", gap " + gap,
                        c -> c.fillRect(2, t, 8, 40 - t, Color.WHITE),
                        c -> c.fillRect(10 + g, 4, 8, 36, Color.BLACK));
                pair(canvas, failures, "a rounded rect beside a bar, top " + top + ", gap " + gap,
                        c -> c.fillRoundRect(limn.graphics.RoundRect.of(2, t, 8, 40 - t, 1f), Color.WHITE),
                        c -> c.fillRect(10 + g, 4, 8, 36, Color.BLACK));
                pair(canvas, failures, "tiles at fractions, top " + top + ", gap " + gap,
                        c -> c.fillRoundRect(limn.graphics.RoundRect.of(2.3f, t, 7.4f, 9.6f, 2f), Color.WHITE),
                        c -> c.fillRoundRect(limn.graphics.RoundRect.of(9.7f + g, t - 9.6f - g, 7.4f, 9.6f, 2f),
                                Color.BLACK));
            }
        }
        for (float distance : new float[] {8f, 8.25f, 8.5f, 8.85f}) {
            for (float dy : new float[] {0f, 0.3f, 0.6f}) {
                float d = distance;
                float y = dy;
                pair(canvas, failures, "two circles " + distance + " apart, dy " + dy,
                        c -> c.fillCircle(20, 24 + y, 4, Color.WHITE),
                        c -> c.fillCircle(20 + d, 24, 4, Color.BLACK));
            }
        }
        assertEquals(java.util.List.of(), failures, "shapes less than a pixel apart drew as in runs of their own");
    }

    /**
     * Draws {@code a} then {@code b} in MULTIPLY in a group over nothing, once as the canvas runs
     * them and once with a NORMAL draw in the far corner between them, which breaks the run, and
     * adds to {@code failures} a case whose pictures differ by more than a step.
     */
    private void pair(GlCanvas canvas, java.util.List<String> failures, String what, Consumer<GlCanvas> a,
                      Consumer<GlCanvas> b) {
        for (boolean broken : new boolean[] {false, true}) {
            render(canvas, Color.TRANSPARENT, c -> {
                c.saveLayer(0, 0, SIZE, SIZE, 0.999f);
                c.save();
                c.setBlendMode(BlendMode.MULTIPLY);
                a.accept(c);
                c.restore();
                if (broken) {
                    c.fillRect(SIZE - 1, SIZE - 1, 1, 1, new Color(0, 0, 0, 0.0001f));
                }
                c.save();
                c.setBlendMode(BlendMode.MULTIPLY);
                b.accept(c);
                c.restore();
                c.restore();
            });
            if (broken) {
                byte[] separate = lastImage;
                int worst = 0;
                for (int i = 0; i < separate.length; i++) {
                    int pixel = i / 4;
                    boolean farCorner = pixel % SIZE == SIZE - 1 && pixel / SIZE == 0; // bottom-up rows
                    if (i % 4 != 3 && !farCorner) {
                        worst = Math.max(worst, Math.abs((shared[i] & 0xFF) - (separate[i] & 0xFF)));
                    }
                }
                if (worst > 1) {
                    failures.add(what + ": off by " + worst);
                }
            } else {
                shared = lastImage;
            }
        }
    }

    /** The picture {@link #pair} drew with the two draws in one run. */
    private byte[] shared;

    /**
     * Additive light over translucent content of the group, the group laid over nothing: every
     * pixel it leaves is a valid premultiplied one, no channel above its coverage, and it is the
     * sum of the two, colour and coverage, clamped, as a web page's plus-lighter has it. The colour
     * was summed while the coverage grew as an over's, so white at one half over white at one half
     * came to a colour of 1 at a coverage of 0.75, which the composite then laid as more light
     * than either rule gives.
     */
    @Test
    void additiveOverTranslucentGroupContentLeavesAValidPremultipliedPixel() {
        GlCanvas canvas = new GlCanvas(fonts);
        try {
            int[] pixel = render(canvas, Color.TRANSPARENT, c -> {
                c.saveLayer(0, 0, SIZE, SIZE, HALF);
                c.fillRect(0, 0, SIZE, SIZE, Color.WHITE.withAlpha(0.5f));
                inMode(c, BlendMode.ADDITIVE, Color.WHITE, 0.5f);
                c.restore();
            });
            for (int i = 0; i < 3; i++) {
                assertTrue(pixel[i] <= pixel[3] + 1, "channel " + i + " " + pixel[i] + " within the coverage "
                        + pixel[3]);
            }
            // The group's pixel is white at a coverage of 1, laid at one half over nothing.
            assertEquals(128, pixel[3], TOLERANCE, "coverage");
            assertEquals(128, pixel[0], TOLERANCE, "red");
        } finally {
            canvas.dispose();
        }
    }

    /**
     * The audit's scene: a widget that tints in MULTIPLY inside an {@code Opacity} container, over
     * the page drawn outside it. At one half it showed the page untouched (242, 166, 90), the tint
     * absent for the whole of a fade; it is the tint at one half over the page. At 1 the tint
     * multiplies the page (121, 83, 90), as it did.
     */
    @Test
    void aTintInMultiplyInsideAFadingContainerShowsForTheWholeFade() {
        GlCanvas canvas = new GlCanvas(fonts);
        try {
            Opacity container = new Opacity(new Tint(BlendMode.MULTIPLY));
            container.measure(Constraints.tight(SIZE, SIZE));
            container.layoutBox(0, 0, SIZE, SIZE);
            Consumer<GlCanvas> paint = c -> {
                c.save();
                container.paintWidget(c);
                c.restore();
            };
            container.setOpacity(HALF);
            int[] fading = render(canvas, paint);
            assertPixel(over(PAGE, HALF, blend(BlendMode.MULTIPLY, SOURCE, 1, null, 0)), fading,
                    "at one half, the tint over the page");
            container.setOpacity(1f);
            int[] arrived = render(canvas, paint);
            assertPixel(new float[] {121 / 255f, 83 / 255f, 90 / 255f}, arrived, "at 1, the tint multiplies the page");
        } finally {
            canvas.dispose();
        }
    }

    /** A widget that fills its box with the tint in a mode. */
    private static final class Tint extends Widget<Tint> {
        private final BlendMode mode;

        Tint(BlendMode mode) {
            this.mode = mode;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(SIZE, SIZE);
        }

        @Override
        protected void onPaint(limn.graphics.Canvas canvas) {
            canvas.save();
            canvas.setBlendMode(mode);
            canvas.fillRect(0, 0, width(), height(), SOURCE);
            canvas.restore();
        }
    }

    /** A group of its own at 1 under {@code mode}, which then composites its picture in it. */
    private static void nestedInMode(GlCanvas c, BlendMode mode) {
        c.save();
        c.setBlendMode(mode);
        c.saveLayer(0, 0, SIZE, SIZE, 1f);
        c.fillRect(0, 0, SIZE, SIZE, SOURCE);
        c.restore();
        c.restore();
    }

    private static void inMode(GlCanvas c, BlendMode mode, Color color, float alpha) {
        c.save();
        c.setBlendMode(mode);
        c.fillRect(0, 0, SIZE, SIZE, color.withAlpha(alpha));
        c.restore();
    }

    // ------------------------------------------------------------------ the web's formula

    /**
     * The group's pixel, premultiplied: {@code source} at {@code sourceAlpha} drawn in {@code mode}
     * over {@code backdrop} at {@code backdropAlpha} (none for null), by the separable blend of the
     * web's compositing rules, MULTIPLY's {@code B} being {@code Cb·Cs}; ADDITIVE is the web's
     * plus-lighter, colour and coverage each summed and clamped.
     *
     * @return red, green, blue premultiplied, then alpha
     */
    private static float[] blend(BlendMode mode, Color source, float sourceAlpha, Color backdrop, float backdropAlpha) {
        float as = sourceAlpha;
        float ab = backdrop == null ? 0 : backdropAlpha;
        float[] out = new float[4];
        for (int i = 0; i < 3; i++) {
            float s = channel(source, i);
            float b = backdrop == null ? 0 : channel(backdrop, i);
            float cs = as * s;
            float cb = ab * b;
            out[i] = switch (mode) {
                case MULTIPLY -> cs * (1 - ab) + cs * cb + cb * (1 - as);
                case ADDITIVE -> Math.min(1, cs + cb);
                default -> cs + cb * (1 - as);
            };
        }
        out[3] = mode == BlendMode.ADDITIVE ? Math.min(1, as + ab) : as + ab * (1 - as);
        return out;
    }

    /** {@code group}, premultiplied, laid over the opaque {@code page} at {@code opacity}. */
    private static float[] over(Color page, float opacity, float[] group) {
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            out[i] = opacity * group[i] + (1 - opacity * group[3]) * channel(page, i);
        }
        return out;
    }

    private static float channel(Color c, int i) {
        return i == 0 ? c.r() : i == 1 ? c.g() : c.b();
    }

    private static void assertPixel(float[] expected, int[] actual, String what) {
        for (int i = 0; i < 3; i++) {
            int exact = Math.round(expected[i] * 255);
            assertEquals(exact, actual[i], TOLERANCE, what + ", channel " + i + ": expected "
                    + Math.round(expected[0] * 255) + "," + Math.round(expected[1] * 255) + ","
                    + Math.round(expected[2] * 255) + ", was " + actual[0] + "," + actual[1] + "," + actual[2]);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** One frame over the page into a texture of its own; the pixel at the middle. */
    private static int[] render(GlCanvas canvas, Consumer<GlCanvas> paint) {
        return render(canvas, PAGE, SIZE / 2, SIZE / 2, paint);
    }

    /** One frame over the page; the pixel at {@code (x, y)}, top-down. */
    private static int[] renderAt(GlCanvas canvas, int x, int y, Consumer<GlCanvas> paint) {
        return render(canvas, PAGE, x, y, paint);
    }

    private static int[] render(GlCanvas canvas, Color page, Consumer<GlCanvas> paint) {
        return render(canvas, page, SIZE / 2, SIZE / 2, paint);
    }

    /** Every pixel of the last {@link #render}, bottom-up as GL reads them. */
    private static byte[] lastImage;

    /** The largest difference of any colour channel between two images. */
    private static int worst(byte[] a, byte[] b) {
        int worst = 0;
        for (int i = 0; i < a.length; i++) {
            if (i % 4 != 3) {
                worst = Math.max(worst, Math.abs((a[i] & 0xFF) - (b[i] & 0xFF)));
            }
        }
        return worst;
    }

    /** One frame over {@code page} into a texture of its own; the pixel at {@code (x, y)}, alpha last. */
    private static int[] render(GlCanvas canvas, Color page, int x, int y, Consumer<GlCanvas> paint) {
        int texture = GL33C.glGenTextures();
        int fbo = GL33C.glGenFramebuffers();
        int previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        try {
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, SIZE, SIZE, 0,
                    GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
            GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                    GL33C.GL_TEXTURE_2D, texture, 0);
            assertEquals(GL33C.GL_FRAMEBUFFER_COMPLETE, GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER));
            GL33C.glViewport(0, 0, SIZE, SIZE);
            canvas.beginFrame(SIZE, SIZE, 1f);
            canvas.clear(page);
            paint.accept(canvas);
            canvas.endFrame();
            GL33C.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            ByteBuffer all = MemoryUtil.memAlloc(SIZE * SIZE * 4);
            try {
                GL33C.glReadPixels(0, 0, SIZE, SIZE, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, all);
                lastImage = new byte[SIZE * SIZE * 4];
                all.get(lastImage);
            } finally {
                MemoryUtil.memFree(all);
            }
            GL33C.glReadPixels(x, SIZE - 1 - y, 1, 1, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixel);
            return new int[] {pixel.get(0) & 0xFF, pixel.get(1) & 0xFF, pixel.get(2) & 0xFF, pixel.get(3) & 0xFF};
        } finally {
            MemoryUtil.memFree(pixel);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
        }
    }
}
