package limn.backend.lwjgl;

import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.RoundRect;
import limn.graphics.Shadow;
import limn.math.Vec3;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Opacity;
import limn.scene.layout.Padding;
import limn.scene.layout.Stack;
import limn.scene.layout.Transform;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a picture turned in depth costs a frame, on the built canvas: the two measurements section 7
 * of the design of transforms asks of stage 3 before it is accepted, "the projected composite
 * against the opacity record's flat one, for a card and for a page, at 0, 30 and 75 degrees", and
 * {@code glGenerateMipmap} per frame for a turned page, which question 11 left to the numbers. A
 * probe, not a test: it runs only with {@code LIMN_PROBE_TRANSFORM_DEPTH} set in the environment,
 * prints its table, and asserts nothing. Run it with {@code --rerun --no-configuration-cache}: the
 * build's configuration cache keeps the environment the test's process was configured with, and a
 * variable set after that never reaches it.
 *
 * <p>By the method of the layer probes: a context of its own ({@link ProbeGl}), on this machine's
 * GPU or, with {@code LIMN_PROBE_RENDERER=software}, on Apple's software renderer, and elsewhere on
 * the system's own driver; the modes interleaved frame by frame and their order rotated each step;
 * medians of 300 frames after 60 warm ones on a GPU and of 40 after 5 on the software renderer, two
 * rounds; a second opaque mode as the floor. A frame is timed from its first command to the
 * canvas's end (CPU), and to a {@code glFinish} (frame), with the GPU's own time from a
 * {@code GL_TIME_ELAPSED} query.
 *
 * <p>The window is 1280 by 800 points at a scale of 2, a striped page under the opacity record's
 * popup, 300 by 400 points. Two sets of modes, each on canvases of its own:
 * <ul>
 *   <li><b>The card</b>, in partial frames, each the card's own damage, which a turned card makes
 *       its projected bounds: {@code C_OPAQUE} and {@code C_OPAQUE'}, the card drawn straight;
 *       {@code C_FLAT}, the card in an {@code Opacity} at one half, the record's flat composite;
 *       {@code C_ROT_3}, the card rotated 3 degrees in the plane, stage 2's affine picture, and
 *       {@code C_3}, turned 3 degrees in depth, a picture laid in perspective at nearly the same
 *       size and over nearly the same pass, the two telling the perspective's composite from the
 *       size of a turned card's picture and pass;
 *       {@code C_0}, {@code C_30} and {@code C_75}, the card in a {@code Transform} seen from 1000
 *       points, two and a half times its larger side, turned 0, 30 and 75 degrees about its
 *       vertical axis.</li>
 *   <li><b>The page</b>, stripes and card, all in one container, in whole frames: {@code P_OPAQUE},
 *       {@code P_OPAQUE'}, {@code P_FLAT}, and {@code P_0}, {@code P_30} and {@code P_75} seen from
 *       3200 points; and {@code P_30_MIP} and {@code P_75_MIP}, the same with
 *       {@code glGenerateMipmap} run on the page's picture after each frame, what a mipmapped
 *       composite would add.</li>
 * </ul>
 * A turn of 0 degrees is at rest and draws with no picture, as the container promises. With
 * {@code LIMN_PROBE_DEPTH_SET} set to {@code card} or {@code page}, only that set runs.
 */
class TransformDepthProbe {

    private static final float SCALE = 2f;
    private static final int WIDTH = 2560;
    private static final int HEIGHT = 1600;

    private enum Mode {
        C_OPAQUE, C_OPAQUE2, C_FLAT, C_ROT_3, C_3, C_0, C_30, C_75,
        P_OPAQUE, P_OPAQUE2, P_FLAT, P_0, P_30, P_75, P_30_MIP, P_75_MIP;

        boolean page() {
            return name().startsWith("P_");
        }

        boolean mipmapped() {
            return name().endsWith("_MIP");
        }

        String label() {
            return name().replace("OPAQUE2", "OPAQUE'");
        }
    }

    /** Stripes behind everything. */
    private static final class Page extends Widget<Page> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(WIDTH / SCALE, HEIGHT / SCALE);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            for (int band = 0; band * 8 < height(); band++) {
                canvas.fillRect(0, band * 8, width(), 8,
                        band % 2 == 0 ? Color.rgb(0xEEF1F6) : Color.rgb(0xE2E7EF));
            }
        }
    }

    /** The opacity record's popup: its shadows, panel, rows and thumb, its shadow its outset. */
    private static final class Popup extends Widget<Popup> {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(300, 400);
        }

        @Override
        protected float paintOutset() {
            return 32;
        }

        @Override
        protected void onPaint(Canvas c) {
            RoundRect shape = RoundRect.of(0, 0, 300, 400, 10);
            c.drawShadow(shape, new Shadow(0, 8, 24, Color.rgba(0x000000, 0.18f)));
            c.drawShadow(shape, new Shadow(0, 2, 6, Color.rgba(0x000000, 0.12f)));
            c.fillRoundRectWithBorder(0, 0, 300, 400, 10, Color.rgb(0xFFFFFF), 1, Color.rgb(0xC9CDD6));
            for (int i = 0; i < 16; i++) {
                float y = 8 + i * 22;
                if (i == 5) {
                    c.fillRoundRect(6, y, 280, 22, 5, Color.rgb(0xD6E4FF));
                }
                c.fillRoundRect(14, y + 4, 14, 14, 3, Color.rgb(0x5B7BD5));
                c.fillRoundRect(36, y + 7, 150 - i * 4, 8, 4, Color.rgb(0x9AA3B2));
            }
            c.fillRoundRect(290, 12, 4, 120, 2, Color.rgba(0x000000, 0.3f));
        }
    }

    /** One mode's scene, canvas and what it repaints each frame. */
    private static final class Subject {
        final Mode mode;
        final Scene scene;
        final Widget<?> repainted;
        final GlCanvas canvas;
        final List<double[]> samples = new ArrayList<>();

        Subject(Mode mode, FontStore fonts) {
            this.mode = mode;
            Popup card = new Popup();
            Stack page = new Stack();
            page.add(new Page());
            Widget<?> placedCard = mode.page() ? card : turned(card, mode, 1000);
            page.add(new Padding(new Insets(160, 0, 0, 480), placedCard));
            Widget<?> root = mode.page() ? turned(page, mode, 3200) : page;
            scene = new Scene(root);
            scene.setTextRuler(new ShapingRuler(fonts));
            scene.setPartialRendering(true);
            canvas = new GlCanvas(fonts);
            repainted = mode.page() ? page : card;
        }

        private static Widget<?> turned(Widget<?> what, Mode mode, float distance) {
            return switch (mode) {
                case C_OPAQUE, C_OPAQUE2, P_OPAQUE, P_OPAQUE2 -> what;
                case C_FLAT, P_FLAT -> new Opacity(what).setOpacity(0.5f);
                case C_ROT_3 -> new Transform(what).setRotation(3);
                case C_3 -> new Transform(what).setPerspective(distance).setRotation3D(Vec3.UNIT_Y, 3);
                case C_0, P_0 -> new Transform(what).setPerspective(distance).setRotation3D(Vec3.UNIT_Y, 0);
                case C_30, P_30, P_30_MIP -> new Transform(what).setPerspective(distance)
                        .setRotation3D(Vec3.UNIT_Y, 30);
                case C_75, P_75, P_75_MIP -> new Transform(what).setPerspective(distance)
                        .setRotation3D(Vec3.UNIT_Y, 75);
            };
        }
    }

    @Test
    void measure() {
        Assumptions.assumeTrue(System.getenv("LIMN_PROBE_TRANSFORM_DEPTH") != null,
                "a probe: set LIMN_PROBE_TRANSFORM_DEPTH to run it");
        boolean software = ProbeGl.software();
        long context = ProbeGl.open(software);
        try (HeadlessUi ui = new HeadlessUi(); FontStore fonts = new FontStore()) {
            Theme.setCurrent(Theme.light());
            // The renderer asked for beside the one given: a variable that did not reach the test's
            // process (a configuration cached before it was set) shows here, not as wrong numbers.
            System.out.println("asked: " + System.getenv("LIMN_PROBE_RENDERER") + "; renderer: "
                    + GL33C.glGetString(GL33C.GL_RENDERER) + " / " + GL33C.glGetString(GL33C.GL_VERSION));
            int warm = software ? 5 : 60;
            int measured = software ? 40 : 300;
            String only = System.getenv("LIMN_PROBE_DEPTH_SET");
            for (boolean page : new boolean[] {false, true}) {
                if (only != null && !only.equals(page ? "page" : "card")) {
                    continue;
                }
                for (int round = 1; round <= 2; round++) {
                    run(fonts, round, warm, measured, page);
                }
            }
        } finally {
            ProbeGl.close(context);
        }
    }

    /** One round of one set's modes, interleaved, each frame repainting what the mode repaints. */
    private void run(FontStore fonts, int round, int warm, int measured, boolean page) {
        List<Subject> subjects = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            if (mode.page() == page) {
                subjects.add(new Subject(mode, fonts));
            }
        }
        int texture = GL33C.glGenTextures();
        int fbo = GL33C.glGenFramebuffers();
        int query = GL33C.glGenQueries();
        try {
            bindTarget(texture, fbo);
            for (Subject s : subjects) {
                frame(s, 0, query, false); // laid out and drawn whole once
            }
            int total = warm + measured;
            for (int step = 0; step < total; step++) {
                for (int k = 0; k < subjects.size(); k++) {
                    Subject s = subjects.get((step + k) % subjects.size());
                    s.repainted.invalidate();
                    frame(s, page ? 0 : 1, query, step >= warm);
                }
            }
            for (Subject s : subjects) {
                report(round, s);
                s.canvas.dispose();
            }
        } finally {
            GL33C.glDeleteQueries(query);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
        }
    }

    private static void frame(Subject s, int bufferAge, int query, boolean record) {
        GL33C.glBeginQuery(GL33C.GL_TIME_ELAPSED, query);
        long start = System.nanoTime();
        s.canvas.beginFrame(WIDTH, HEIGHT, SCALE);
        s.scene.renderFrame(s.canvas, false, Float.NaN, bufferAge);
        s.canvas.endFrame();
        if (s.mode.mipmapped()) {
            // What a mipmapped composite would add: the chain made again from the picture the
            // frame drew, which the pool still holds until the frame after its last use.
            for (int picture : s.canvas.layerTextures()) {
                GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, picture);
                GL33C.glGenerateMipmap(GL33C.GL_TEXTURE_2D);
            }
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, 0);
        }
        long issued = System.nanoTime();
        GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED);
        GL33C.glFinish();
        long finished = System.nanoTime();
        long gpu = GL33C.glGetQueryObjecti64(query, GL33C.GL_QUERY_RESULT);
        if (record) {
            s.samples.add(new double[] {(issued - start) / 1e6, gpu / 1e6, (finished - start) / 1e6,
                    s.canvas.layerTextures().length});
        }
    }

    private static void report(int round, Subject s) {
        System.out.printf(java.util.Locale.ROOT,
                "round %d %-7s %-10s frames %4d  CPU %.3f  GPU %.3f  frame %.3f ms  pictures %.0f%n",
                round, s.mode.page() ? "whole" : "partial", s.mode.label(), s.samples.size(),
                median(s.samples, 0), median(s.samples, 1), median(s.samples, 2), median(s.samples, 3));
    }

    private static double median(List<double[]> samples, int column) {
        double[] values = new double[samples.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = samples.get(i)[column];
        }
        Arrays.sort(values);
        return values.length == 0 ? Double.NaN : values[values.length / 2];
    }

    private static void bindTarget(int texture, int fbo) {
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
        GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                GL33C.GL_TEXTURE_2D, texture, 0);
        GL33C.glViewport(0, 0, WIDTH, HEIGHT);
    }
}
