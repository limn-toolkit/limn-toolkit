package limn.backend.lwjgl;

import limn.components.Button;
import limn.components.ComboBox;
import limn.components.TextField;
import limn.components.Theme;
import limn.components.date.DateField;
import limn.graphics.SvgIcon;
import limn.scene.ControlSize;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;
import limn.testing.HeadlessUi;
import limn.testing.StubWindow;
import limn.testing.TestPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the ink of a single-line control lands against its box on a real device, with the bundled
 * faces and the real shaper: the measurements behind the owner's eye on the Showcase, read as rows
 * and columns of pixels and not as the numbers the layout believes.
 *
 * <p>The text of a field, a combo, a date field and a button is centred on its box by its <em>cap
 * height</em>, at every control size, at 1x and 2x, wherever on the pixel grid the box happens to
 * start. And the gap between a leading icon's ink and its text's ink is the same in a button, a
 * field and a combo, in either direction.
 *
 * <p>Set the environment variable {@code LIMN_TEST_INK_REPORT} to a file to have every measurement
 * written there, and {@code LIMN_TEST_INK_DUMP} to a directory for the frames, as raw RGBA.
 */
class ControlInkPixelTest {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 300;
    /** A file every measurement is appended to, named by {@code LIMN_TEST_INK_REPORT}; none by default. */
    private static final String REPORT_FILE = System.getenv("LIMN_TEST_INK_REPORT");
    private static final boolean REPORT = REPORT_FILE != null;
    /** Capitals with flat tops and a flat foot, so the ink spans exactly cap height to baseline. */
    private static final String CAPS = "HEIDE";
    /** A check mark on the 24-unit grid stroke icons are drawn on, a round-capped 2-unit pen. */
    private static final String CHECK = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\""
            + " fill=\"none\" stroke=\"#000\" stroke-width=\"2\" stroke-linecap=\"round\""
            + " stroke-linejoin=\"round\"><path d=\"M5 12l5 5l10 -10\"/></svg>";

    /** A square that inks the whole of its box. */
    private static final String SQUARE = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\">"
            + "<rect width=\"24\" height=\"24\" fill=\"#000\"/></svg>";
    /** A fixed today, so no date part depends on the day the test runs. */
    private static final java.time.Clock CLOCK =
            java.time.Clock.fixed(java.time.Instant.parse("2026-10-20T12:00:00Z"), java.time.ZoneOffset.UTC);

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;
    private TestPreferences preferences;
    private LunaSvgRenderer svg;
    private int texture;
    private int fbo;
    private int previousFbo;
    private final int[] previousViewport = new int[4];

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        fonts = new FontStore();
        canvas = new GlCanvas(fonts);
        svg = new LunaSvgRenderer();
        SvgIcon.installRenderer(svg);
        Theme.setCurrent(Theme.light());
        preferences = new TestPreferences().reduceMotion(true);
        previousFbo = GL33C.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        texture = GL33C.glGenTextures();
        fbo = GL33C.glGenFramebuffers();
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
        GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                GL33C.GL_TEXTURE_2D, texture, 0);
        GL33C.glViewport(0, 0, WIDTH, HEIGHT);
    }

    @AfterEach
    void closeContext() {
        if (canvas != null) {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            GL33C.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            GL33C.glDeleteFramebuffers(fbo);
            GL33C.glDeleteTextures(texture);
            canvas.dispose();
            canvas = null;
        }
        if (svg != null) {
            SvgIcon.uninstallRenderer(svg);
            svg = null;
        }
        if (preferences != null) {
            preferences.close();
            preferences = null;
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

    // ------------------------------------------------------------------ D5: vertical centring

    /** The four controls the Showcase's forms set side by side, each showing {@code text}. */
    private static List<Named> textControls(String text) {
        List<Named> all = new ArrayList<>();
        all.add(new Named("TextField", () -> new TextField().setText(text)));
        all.add(new Named("ComboBox", () -> new ComboBox<>(List.of(text)).setSelectedIndex(0)));
        all.add(new Named("Button", () -> new Button(text)));
        return all;
    }

    @Test
    void theCapitalsOfAFieldAComboAButtonAndADateSitOnTheBoxCentreAtEverySizeScaleAndPhase() {
        List<String> failures = new ArrayList<>();
        float worst = 0;
        for (float scale : new float[] {1f, 2f}) {
            for (ControlSize size : ControlSize.values()) {
                List<Named> controls = textControls(CAPS);
                controls.add(new Named("DateField", () -> new DateField().setClock(CLOCK).setDate(LocalDate.of(2026, 10, 20))));
                for (Named control : controls) {
                    for (int phase = 0; phase < 8; phase++) {
                        float top = 20 + phase / 8f;
                        Widget<?> widget = control.make().get().setControlSize(size);
                        Placed placed = render(widget, scale, top, 20, 240, null);
                        float boxCentre = (placed.top + widget.height() / 2) * scale;
                        // The caps' columns only: the field's own text, clear of icons and chevrons.
                        Inner in = inner(widget, placed, scale);
                        int x0 = in.x0;
                        int x1 = (int) ((placed.left + widget.width() * 0.6f) * scale);
                        if (widget instanceof DateField) {
                            // The day's two digits alone: a date's slash reaches below the baseline.
                            var t = Theme.current().tokensFor(widget);
                            x1 = (int) ((placed.left + t.fieldPadH()
                                    + new ShapingRuler(fonts).measure("20", t.body()).width()) * scale);
                        }
                        int y0 = in.y0;
                        int y1 = in.y1;
                        Span ink = rows(placed.image, x0, y0, x1, y1);
                        float offset = ink.centre() - boxCentre;
                        worst = Math.max(worst, Math.abs(offset));
                        if (REPORT) {
                            report("centre %-9s %-7s %.0fx phase %.3f: box %.2f..%.2f ink %.2f..%.2f"
                                            + " offset %+.2f px%n", control.name, size, scale, top - 20,
                                    placed.top * scale, (placed.top + widget.height()) * scale,
                                    ink.from, ink.to, offset);
                        }
                        if (Math.abs(offset) > 0.55f) {
                            failures.add(String.format(Locale.ROOT, "%s %s %.0fx phase %.3f: %+.2f px",
                                    control.name, size, scale, top - 20, offset));
                        }
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "capitals off the box centre by more than half a device pixel (worst "
                + worst + "): " + failures);
    }

    @Test
    void anotherScriptsGlyphsKeepTheLatinBaselineAndStayInsideTheBox() {
        // Measured and reported, and held only to the box: a fallback face's glyphs sit on the
        // primary face's baseline, which is the point of a shared baseline, and where their own
        // ink centres is the face's design, not this rule's.
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        org.junit.jupiter.api.Assumptions.assumeTrue(loaded != null, "the CJK fallback face is on the test path");
        assertTrue(fonts.installHeavyFallbacks(loaded));
        for (float scale : new float[] {1f, 2f}) {
            for (String sample : new String[] {"\u6771\u4eac\u90fd", "\u0645\u0631\u062d\u0628\u0627", "Harbor"}) {
                Widget<?> widget = new TextField().setText(sample);
                Placed placed = render(widget, scale, 20, 20, 240, null);
                Inner in = inner(widget, placed, scale);
                Span ink = rows(placed.image, in.x0, in.y0, (int) ((placed.left + 120) * scale), in.y1);
                float boxCentre = (placed.top + widget.height() / 2) * scale;
                if (REPORT) {
                    report("script %s %.0fx: box %.2f..%.2f ink %.2f..%.2f offset %+.2f px%n", sample, scale,
                            placed.top * scale, (placed.top + widget.height()) * scale, ink.from, ink.to,
                            ink.centre() - boxCentre);
                }
                assertTrue(ink.from > placed.top * scale && ink.to < (placed.top + widget.height()) * scale,
                        sample + " inside its box: " + ink);
            }
        }
    }

    // ------------------------------------------------------------------ D6: icon to text gap

    @Test
    void aButtonsIconStandsAsFarFromItsTextAsAComboIconDoes() {
        // Asserted with a square that inks its whole box, so the gap read is the token and the
        // letter's own side bearing and nothing else: a button's icon box is larger than a field's,
        // and a stroke icon's margin inside its box grows with the box. The check mark, the icon
        // the owner saw, is measured beside it for the report.
        List<String> failures = new ArrayList<>();
        for (float scale : new float[] {1f, 2f}) {
            for (ControlSize size : ControlSize.values()) {
                for (LayoutDirection direction : LayoutDirection.values()) {
                    for (String svg : new String[] {SQUARE, CHECK}) {
                        String kind = svg == SQUARE ? " square" : " check";
                        float combo = iconGap(() -> new ComboBox<>(List.of(CAPS)).setSelectedIndex(0)
                                .setItemIcon(item -> SvgIcon.of(svg)), size, scale, direction, "ComboBox" + kind);
                        float button = iconGap(() -> new Button(CAPS).setIcon(SvgIcon.of(svg)),
                                size, scale, direction, "Button" + kind);
                        float field = iconGap(() -> new TextField().setText(CAPS).setLeadingIcon(SvgIcon.of(svg)),
                                size, scale, direction, "TextField" + kind);
                        // One device pixel: each edge is read where the pixel grid put it.
                        float pixel = 1.05f / scale;
                        if (svg == SQUARE && (Math.abs(button - combo) > pixel || Math.abs(field - combo) > pixel)) {
                            failures.add(String.format(Locale.ROOT, "%s %.0fx %s: combo %.2f, button %.2f, field %.2f pt",
                                    size, scale, direction, combo, button, field));
                        }
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "icon-to-text gaps disagree: " + failures);
    }

    /** Points of empty paper between the icon's ink and the text's ink, on the side between them. */
    private float iconGap(Supplier<Widget<?>> make, ControlSize size, float scale, LayoutDirection direction,
                          String name) {
        Widget<?> widget = make.get().setControlSize(size);
        Placed placed = render(widget, scale, 20, 20, 240, direction);
        Inner in = inner(widget, placed, scale);
        List<Span> runs = columnRuns(placed.image, in.x0, in.y0, in.x1, in.y1, 2);
        // The icon and the first letter are the two runs at the leading end; a combo's chevron and
        // anything further along are not read.
        boolean rtl = direction == LayoutDirection.RTL;
        Span icon = rtl ? runs.get(runs.size() - 1) : runs.get(0);
        Span text = rtl ? runs.get(runs.size() - 2) : runs.get(1);
        float gap = (rtl ? icon.from - text.to : text.from - icon.to) / scale;
        if (REPORT) {
            report("gap %-16s %-7s %.0fx %s: icon ink %.2f..%.2f text from %.2f..%.2f gap %.2f pt"
                            + " (runs %d)%n", name, size, scale, direction, icon.from, icon.to, text.from, text.to,
                    gap, runs.size());
        }
        return gap;
    }

    // ------------------------------------------------------------------ harness

    private record Named(String name, Supplier<Widget<?>> make) {
    }

    private record Placed(byte[] image, float left, float top) {
    }

    /** The device rectangle inside the box clear of its border and its rounded corners. */
    private record Inner(int x0, int y0, int x1, int y1) {
    }

    private static Inner inner(Widget<?> widget, Placed placed, float scale) {
        float radius = Theme.current().tokensFor(widget).radiusMedium();
        return new Inner((int) Math.ceil((placed.left + 2.5f) * scale) + 1,
                (int) Math.ceil((placed.top + radius) * scale),
                (int) ((placed.left + widget.width() - 2.5f) * scale) - 1,
                (int) ((placed.top + widget.height() - radius) * scale));
    }

    /** A run of ink, in device pixels, with the edge pixels' coverage counted as a fraction. */
    private record Span(float from, float to) {
        float centre() {
            return (from + to) / 2;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%.2f..%.2f", from, to);
        }
    }

    private Placed render(Widget<?> widget, float scale, float top, float left, float width,
                          LayoutDirection direction) {
        // A column, so the box is the width asked for and not the window's.
        Column column = new Column();
        column.add(new SizedBox(width, SizedBox.UNSET, widget));
        Scene scene = new Scene(new Padding(new Insets(top, 0, 0, left), column));
        if (direction != null) {
            scene.setLayoutDirection(direction);
        }
        scene.setTextRuler(new ShapingRuler(fonts));
        scene.bind(new StubWindow());
        for (int i = 0; i < 2; i++) {
            canvas.beginFrame(WIDTH, HEIGHT, scale);
            scene.renderFrame(canvas, false, Float.NaN, 0);
            canvas.endFrame();
        }
        byte[] image = read();
        dump(image, widget);
        return new Placed(image, widget.localToScene(0, 0).x(), widget.localToScene(0, 0).y());
    }

    private static void report(String format, Object... args) {
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(REPORT_FILE),
                    String.format(Locale.ROOT, format, args), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static int dumps;

    private static void dump(byte[] image, Widget<?> widget) {
        String dir = System.getenv("LIMN_TEST_INK_DUMP");
        if (dir == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir));
            java.nio.file.Files.write(java.nio.file.Path.of(dir, String.format(Locale.ROOT, "%04d-%s.rgba",
                    dumps++, widget.getClass().getSimpleName())), image);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The background is the box's own paper, read at its first inner column on the middle row. */
    private static Span rows(byte[] image, int x0, int y0, int x1, int y1) {
        int bg = lum(image, x0, (y0 + y1) / 2);
        float[] cover = new float[y1 - y0];
        float peak = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                cover[y - y0] = Math.max(cover[y - y0], Math.abs(lum(image, x, y) - bg));
            }
            peak = Math.max(peak, cover[y - y0]);
        }
        return span(cover, y0, peak);
    }

    /** The runs of inked columns, split where at least {@code split} empty columns stand between. */
    private static List<Span> columnRuns(byte[] image, int x0, int y0, int x1, int y1, int split) {
        int bg = lum(image, x0, (y0 + y1) / 2);
        float[] cover = new float[x1 - x0];
        float peak = 0;
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                cover[x - x0] = Math.max(cover[x - x0], Math.abs(lum(image, x, y) - bg));
            }
            peak = Math.max(peak, cover[x - x0]);
        }
        List<Span> runs = new ArrayList<>();
        float threshold = peak * 0.15f;
        int i = 0;
        while (i < cover.length) {
            if (cover[i] <= threshold) {
                i++;
                continue;
            }
            int start = i;
            int end = i;
            int empty = 0;
            for (int j = i; j < cover.length; j++) {
                if (cover[j] > threshold) {
                    end = j;
                    empty = 0;
                } else if (++empty >= split) {
                    break;
                }
            }
            float[] slice = java.util.Arrays.copyOfRange(cover, start, end + 1);
            runs.add(span(slice, x0 + start, peak));
            i = end + 1;
        }
        return runs;
    }

    private static Span span(float[] cover, int origin, float peak) {
        float threshold = peak * 0.15f;
        int first = -1;
        int last = -1;
        for (int i = 0; i < cover.length; i++) {
            if (cover[i] > threshold) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0) {
            return new Span(Float.NaN, Float.NaN);
        }
        float from = origin + first + 1 - Math.min(1, cover[first] / peak);
        float to = origin + last + Math.min(1, cover[last] / peak);
        return new Span(from, to);
    }

    private static int lum(byte[] image, int x, int y) {
        int at = (y * WIDTH + x) * 4;
        return ((image[at] & 0xFF) * 299 + (image[at + 1] & 0xFF) * 587 + (image[at + 2] & 0xFF) * 114) / 1000;
    }

    private static byte[] read() {
        ByteBuffer pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        try {
            GL33C.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            GL33C.glReadPixels(0, 0, WIDTH, HEIGHT, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixels);
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
