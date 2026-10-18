package limn.backend.lwjgl;

import limn.components.Label;
import limn.components.Surface;
import limn.components.Theme;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
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
 * What a scale animation of a card full of text costs in glyphs and in time, on the built canvas:
 * the measurement section 7 of the design of transforms asks for before it is accepted
 * ("rasterizations and atlas pages per frame of a {@code scaleTo} of a text-heavy card, and the
 * frame time it costs"). A probe, not a test: it runs only with {@code LIMN_PROBE_TRANSFORM_GLYPHS}
 * set in the environment, prints its table, and asserts nothing.
 *
 * <p>By the method the opacity's record measured with: a context of its own with no window, on
 * this machine's GPU or, with {@code LIMN_PROBE_RENDERER=software}, on Apple's software renderer
 * (macOS only, through CGL); the modes interleaved frame by frame and their order rotated each
 * step, so a clock change lands on every mode alike; medians of 300 frames after 60 warm ones on a
 * GPU and of 40 after 5 on the software renderer, two rounds; and a second still mode as the floor
 * every other is read against. A frame is timed from its first command to the canvas's end
 * (CPU), and to a {@code glFinish} (frame), with the GPU's own time from a
 * {@code GL_TIME_ELAPSED} query.
 *
 * <p>The window is 1280 by 800 points at a scale of 2, a striped page under a card of 26 lines
 * of text at four sizes. Each frame repaints the card (partial rendering, a buffer that keeps
 * its contents). The modes, each with a canvas and so a glyph atlas of its own:
 * <ul>
 *   <li>{@code STILL} and {@code STILL'}: the card at rest, invalidated;</li>
 *   <li>{@code SCALED}: the card held at 0.975, invalidated: drawing through a scale, glyphs warm;</li>
 *   <li>{@code WARM}: a scale animation from 0.95 to 1 over twelve frames, eased as a motion is,
 *       repeated, so after its first run every size it passes through is in the atlas;</li>
 *   <li>{@code COLD}: the same twelve frames on a fresh canvas each run, so every size is new to
 *       the atlas, the first time an application runs the animation;</li>
 *   <li>{@code FRESH}: the card at rest on a fresh canvas each twelve frames, the control for
 *       {@code COLD}, whose first frame of each run is set apart for both.</li>
 * </ul>
 */
class TransformGlyphProbe {

    private static final float SCALE = 2f;
    private static final int WIDTH = 2560;
    private static final int HEIGHT = 1600;
    private static final int RUN = 12;

    private enum Mode { STILL, STILL2, SCALED, WARM, COLD, FRESH }

    /** Bright stripes behind everything. */
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

    /** One mode's scene, canvas and card. */
    private static final class Subject {
        final Mode mode;
        final FontStore fonts;
        final Scene scene;
        final Transform card;
        final Widget<?> body;
        GlCanvas canvas;
        int frame;
        final List<double[]> samples = new ArrayList<>();
        long rasterized;
        int runs;
        int maxPages;

        Subject(Mode mode, FontStore fonts) {
            this.mode = mode;
            this.fonts = fonts;
            body = card();
            card = new Transform(body);
            Stack root = new Stack();
            root.add(new Page());
            root.add(new Padding(new Insets(120, 0, 0, 420), card));
            scene = new Scene(root);
            scene.setTextRuler(new ShapingRuler(fonts));
            scene.setPartialRendering(true);
            canvas = new GlCanvas(fonts);
            if (mode == Mode.SCALED) {
                card.setScale(0.975f);
            }
        }
    }

    private static Widget<?> card() {
        Column lines = new Column();
        lines.gap(6).crossAlignment(Flex.CrossAlignment.STRETCH);
        lines.add(new Label("Harbor Lane Supplies").setStrong(true));
        lines.add(new Label("Invoice INV-2041, issued 14 October, due in thirty days").setMuted(true));
        String[] items = {
                "Office chair, mesh back", "Standing desk, 140 cm", "Monitor arm, dual",
                "Keyboard, low profile", "Mouse, wireless", "Desk lamp, LED", "Cable tray, steel",
                "Footrest, adjustable", "Whiteboard, 120 by 90", "Filing cabinet, three drawers",
                "Visitor chair, stacking", "Coat stand, oak", "Paper shredder, cross cut",
                "Label printer, thermal", "Document scanner", "Headset, noise cancelling",
                "Webcam, 1080p", "Docking station, USB-C", "Laptop stand, aluminium",
                "Power strip, six sockets", "Bookshelf, five shelves", "Plant pot, ceramic"};
        for (int i = 0; i < items.length; i++) {
            Row row = new Row();
            row.gap(12);
            row.add(Expanded.of(new Label(items[i]), 1));
            row.add(new Label("x " + (i % 5 + 1)).setMuted(true));
            row.add(new SizedBox(90, SizedBox.UNSET, new Label(String.format("%,.2f", 37.5 + i * 41.25))));
            lines.add(row);
        }
        lines.add(new Label("Total 21,486.75").setStrong(true));
        lines.add(new Label("Questions about this invoice go to accounts@harborlane.example, quoting its number.")
                .setWrap(true));
        Surface surface = new Surface(new Padding(Insets.all(16), new SizedBox(400, SizedBox.UNSET, lines)));
        return surface;
    }

    @Test
    void measure() throws ReflectiveOperationException {
        Assumptions.assumeTrue(System.getenv("LIMN_PROBE_TRANSFORM_GLYPHS") != null,
                "a probe: set LIMN_PROBE_TRANSFORM_GLYPHS to run it");
        boolean software = "software".equals(System.getenv("LIMN_PROBE_RENDERER"));
        long context = openContext(software);
        try (HeadlessUi ui = new HeadlessUi(); FontStore fonts = new FontStore()) {
            Theme.setCurrent(Theme.light());
            System.out.println("renderer: " + GL33C.glGetString(GL33C.GL_RENDERER) + " / "
                    + GL33C.glGetString(GL33C.GL_VERSION));
            int warm = software ? 5 : 60;
            int measured = software ? 40 : 300;
            for (int round = 1; round <= 2; round++) {
                run(fonts, round, warm, measured);
            }
        } finally {
            CGL.CGLSetCurrentContext(0L);
            CGL.CGLDestroyContext(context);
        }
    }

    private void run(FontStore fonts, int round, int warm, int measured) throws ReflectiveOperationException {
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
                if (s.mode == Mode.WARM) {
                    // One whole run before anything is measured, whatever the warm-up's length:
                    // every size the animation passes through is then in this canvas's atlas.
                    for (int i = 0; i < RUN; i++) {
                        prepare(s);
                        frame(s, 1, query, false);
                    }
                }
            }
            int total = warm + measured;
            for (int step = 0; step < total; step++) {
                for (int k = 0; k < modes.length; k++) {
                    Subject s = subjects.get((step + k) % modes.length);
                    prepare(s);
                    frame(s, 1, query, step >= warm);
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

    /** The change a mode makes before its frame. */
    private void prepare(Subject s) {
        int inRun = s.frame % RUN;
        if ((s.mode == Mode.COLD || s.mode == Mode.FRESH) && inRun == 0) {
            // A fresh canvas, and with it an empty glyph atlas; drawn whole once, at rest for the
            // control and at the run's first size for COLD, outside what is measured.
            s.canvas.dispose();
            s.canvas = new GlCanvas(s.fonts);
            s.runs++;
        }
        switch (s.mode) {
            case STILL, STILL2, SCALED, FRESH -> s.body.invalidate();
            case WARM, COLD -> {
                float t = (inRun + 1) / (float) RUN;
                float eased = limn.math.Scalars.smoothstep(t);
                s.card.setScale(0.95f + 0.05f * eased);
            }
        }
        s.frame++;
    }

    private void frame(Subject s, int bufferAge, int query, boolean record) throws ReflectiveOperationException {
        int glyphsBefore = glyphCount(s.canvas);
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
        int rasterized = Math.max(0, glyphCount(s.canvas) - glyphsBefore);
        s.maxPages = Math.max(s.maxPages, atlasPages(s.canvas));
        boolean firstOfRun = (s.mode == Mode.COLD || s.mode == Mode.FRESH) && (s.frame - 1) % RUN == 0;
        if (record && !firstOfRun) {
            s.samples.add(new double[] {(issued - start) / 1e6, gpu / 1e6, (finished - start) / 1e6, rasterized});
            s.rasterized += rasterized;
        }
    }

    private static void report(int round, Subject s) {
        int n = s.samples.size();
        System.out.printf(java.util.Locale.ROOT, "round %d %-7s frames %4d  CPU %.3f  GPU %.3f  frame %.3f ms  "
                        + "glyphs rasterized per frame %.1f (max %d)  atlas pages %d%n",
                round, s.mode == Mode.STILL2 ? "STILL'" : s.mode.name(), n,
                median(s.samples, 0), median(s.samples, 1), median(s.samples, 2),
                n == 0 ? 0 : s.rasterized / (double) n, (int) max(s.samples, 3), s.maxPages);
    }

    private static double median(List<double[]> samples, int column) {
        double[] values = new double[samples.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = samples.get(i)[column];
        }
        Arrays.sort(values);
        return values.length == 0 ? Double.NaN : values[values.length / 2];
    }

    private static double max(List<double[]> samples, int column) {
        double m = 0;
        for (double[] sample : samples) {
            m = Math.max(m, sample[column]);
        }
        return m;
    }

    // ------------------------------------------------------------------ the atlas, read

    private static Object atlas(GlCanvas canvas) throws ReflectiveOperationException {
        java.lang.reflect.Field field = GlCanvas.class.getDeclaredField("atlas");
        field.setAccessible(true);
        return field.get(canvas);
    }

    private static int glyphCount(GlCanvas canvas) throws ReflectiveOperationException {
        Object atlas = atlas(canvas);
        java.lang.reflect.Field glyphs = GlyphAtlas.class.getDeclaredField("glyphs");
        glyphs.setAccessible(true);
        Object map = glyphs.get(atlas);
        java.lang.reflect.Method size = map.getClass().getDeclaredMethod("size");
        size.setAccessible(true);
        return (int) size.invoke(map);
    }

    private static int atlasPages(GlCanvas canvas) throws ReflectiveOperationException {
        return ((GlyphAtlas) atlas(canvas)).stats().textures();
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
