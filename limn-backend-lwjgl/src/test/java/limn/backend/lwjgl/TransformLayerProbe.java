package limn.backend.lwjgl;

import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.RoundRect;
import limn.graphics.Shadow;
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
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.CGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a rotated card's picture costs a frame, on the built canvas: the two measurements section 7
 * of the design of transforms asks of stage 2 before it is accepted, "the re-basing a transform's
 * picture needs and an opacity's does not: a rotated card's frame against the same card in an
 * opacity group", and "the rounded enclosing clip in the composite program on the software
 * renderer, against the scissor alone". A probe, not a test: it runs only with
 * {@code LIMN_PROBE_TRANSFORM_LAYER} set in the environment, prints its table, and asserts nothing.
 *
 * <p>By the method the opacity's record measured with: a context of its own with no window, on
 * this machine's GPU or, with {@code LIMN_PROBE_RENDERER=software}, on Apple's software renderer
 * (macOS only, through CGL); the modes interleaved frame by frame and their order rotated each
 * step; medians of 300 frames after 60 warm ones on a GPU and of 40 after 5 on the software
 * renderer, two rounds; a second opaque mode as the floor. A frame is timed from its first command
 * to the canvas's end (CPU), and to a {@code glFinish} (frame), with the GPU's own time from a
 * {@code GL_TIME_ELAPSED} query.
 *
 * <p>The window is 1280 by 800 points at a scale of 2, a striped page under the opacity record's
 * popup: 300 by 400 points, two shadows, a bordered panel, sixteen rows of an icon with one row
 * highlighted, and a scroll thumb. Each frame repaints the card: first as partial rendering does,
 * a buffer that keeps its contents and the pass the card's own damage, which a turned card makes
 * larger by its turned bounds; then whole frames, where every mode repaints the same window and
 * what differs is the picture alone. The modes, each on a canvas of its own:
 * <ul>
 *   <li>{@code OPAQUE} and {@code OPAQUE'}: the card drawn straight;</li>
 *   <li>{@code GROUP}: the card in an {@code Opacity} container at one half, the record's layer;</li>
 *   <li>{@code TURNED} and {@code TURNED_30}: the card in a {@code Transform} rotated 3 and 30
 *       degrees, drawn through a picture in its own space and laid at the angle;</li>
 *   <li>{@code RECT_CLIP} and {@code ROUND_CLIP}: the card turned 3 degrees inside a panel a
 *       little larger than it that clips it, with a rectangle (the composite's scissor) or with
 *       corners of 16 points (the composite's variant program).</li>
 * </ul>
 */
class TransformLayerProbe {

    private static final float SCALE = 2f;
    private static final int WIDTH = 2560;
    private static final int HEIGHT = 1600;

    private enum Mode { OPAQUE, OPAQUE2, GROUP, TURNED, TURNED_30, RECT_CLIP, ROUND_CLIP }

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

    /** A panel that clips what it holds, square or rounded. */
    private static final class Clip extends Widget<Clip> {
        private final float radius;

        Clip(Widget<?> child, float radius) {
            this.radius = radius;
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            children().get(0).measure(Constraints.loose(300, 400));
            return c.constrain(320, 420);
        }

        @Override
        protected void onLayout() {
            children().get(0).layoutBox(10, 10, 300, 400);
        }

        @Override
        protected boolean clipsChildren() {
            return true;
        }

        @Override
        protected void paintChildren(Canvas canvas) {
            canvas.save();
            if (radius > 0) {
                canvas.clipRoundRect(RoundRect.of(0, 0, width(), height(), radius));
            } else {
                canvas.clipRect(0, 0, width(), height());
            }
            super.paintChildren(canvas);
            canvas.restore();
        }
    }

    /** One mode's scene, canvas and card. */
    private static final class Subject {
        final Mode mode;
        final Scene scene;
        final Popup card = new Popup();
        final GlCanvas canvas;
        final List<double[]> samples = new ArrayList<>();

        Subject(Mode mode, FontStore fonts) {
            this.mode = mode;
            Widget<?> placed = switch (mode) {
                case OPAQUE, OPAQUE2 -> card;
                case GROUP -> new Opacity(card).setOpacity(0.5f);
                case TURNED -> new Transform(card).setRotation(3);
                case TURNED_30 -> new Transform(card).setRotation(30);
                case RECT_CLIP -> new Clip(new Transform(card).setRotation(3), 0);
                case ROUND_CLIP -> new Clip(new Transform(card).setRotation(3), 16);
            };
            Stack root = new Stack();
            root.add(new Page());
            root.add(new Padding(new Insets(160, 0, 0, 480), placed));
            scene = new Scene(root);
            scene.setTextRuler(new ShapingRuler(fonts));
            scene.setPartialRendering(true);
            canvas = new GlCanvas(fonts);
        }
    }

    @Test
    void measure() {
        Assumptions.assumeTrue(System.getenv("LIMN_PROBE_TRANSFORM_LAYER") != null,
                "a probe: set LIMN_PROBE_TRANSFORM_LAYER to run it");
        boolean software = "software".equals(System.getenv("LIMN_PROBE_RENDERER"));
        long context = openContext(software);
        try (HeadlessUi ui = new HeadlessUi(); FontStore fonts = new FontStore()) {
            Theme.setCurrent(Theme.light());
            System.out.println("renderer: " + GL33C.glGetString(GL33C.GL_RENDERER) + " / "
                    + GL33C.glGetString(GL33C.GL_VERSION));
            int warm = software ? 5 : 60;
            int measured = software ? 40 : 300;
            for (int round = 1; round <= 2; round++) {
                run(fonts, round, warm, measured, true);
            }
            for (int round = 1; round <= 2; round++) {
                run(fonts, round, warm, measured, false);
            }
        } finally {
            CGL.CGLSetCurrentContext(0L);
            CGL.CGLDestroyContext(context);
        }
    }

    /**
     * One round of every mode, interleaved: partial frames, each the card's own damage, or whole
     * frames, where every mode repaints the same window and what differs is the picture alone.
     */
    private void run(FontStore fonts, int round, int warm, int measured, boolean partial) {
        Mode[] modes = Mode.values();
        List<Subject> subjects = new ArrayList<>();
        for (Mode mode : modes) {
            subjects.add(new Subject(mode, fonts));
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
                for (int k = 0; k < modes.length; k++) {
                    Subject s = subjects.get((step + k) % modes.length);
                    s.card.invalidate();
                    frame(s, partial ? 1 : 0, query, step >= warm);
                }
            }
            for (Subject s : subjects) {
                report(round, partial ? "partial" : "whole", s);
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
        long issued = System.nanoTime();
        GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED);
        GL33C.glFinish();
        long finished = System.nanoTime();
        long gpu = GL33C.glGetQueryObjecti64(query, GL33C.GL_QUERY_RESULT);
        if (record) {
            s.samples.add(new double[] {(issued - start) / 1e6, gpu / 1e6, (finished - start) / 1e6});
        }
    }

    private static void report(int round, String frames, Subject s) {
        System.out.printf(java.util.Locale.ROOT, "round %d %-7s %-10s frames %4d  CPU %.3f  GPU %.3f  frame %.3f ms%n",
                round, frames, s.mode == Mode.OPAQUE2 ? "OPAQUE'" : s.mode.name(), s.samples.size(),
                median(s.samples, 0), median(s.samples, 1), median(s.samples, 2));
    }

    private static double median(List<double[]> samples, int column) {
        double[] values = new double[samples.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = samples.get(i)[column];
        }
        Arrays.sort(values);
        return values.length == 0 ? Double.NaN : values[values.length / 2];
    }

    // ------------------------------------------------------------------ the context and target

    /** A CGL context with no window: this Mac's GPU, or Apple's software renderer by its id. */
    private static long openContext(boolean software) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attributes = software
                    ? stack.ints(CGL.kCGLPFAOpenGLProfile, CGL.kCGLOGLPVersion_3_2_Core,
                            CGL.kCGLPFARendererID, 0x00020400, 0)
                    : stack.ints(CGL.kCGLPFAOpenGLProfile, CGL.kCGLOGLPVersion_3_2_Core,
                            CGL.kCGLPFAAccelerated, 0);
            PointerBuffer format = stack.mallocPointer(1);
            IntBuffer count = stack.mallocInt(1);
            int error = CGL.CGLChoosePixelFormat(attributes, format, count);
            Assumptions.assumeTrue(error == 0 && format.get(0) != 0L, "no pixel format: " + error);
            PointerBuffer context = stack.mallocPointer(1);
            error = CGL.CGLCreateContext(format.get(0), 0L, context);
            CGL.CGLDestroyPixelFormat(format.get(0));
            Assumptions.assumeTrue(error == 0, "no context: " + error);
            CGL.CGLSetCurrentContext(context.get(0));
            GL.createCapabilities();
            return context.get(0);
        }
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
