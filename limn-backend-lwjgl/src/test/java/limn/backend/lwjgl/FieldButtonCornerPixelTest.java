package limn.backend.lwjgl;

import limn.components.Spinner;
import limn.components.TextField;
import limn.components.Theme;
import limn.components.date.DatePicker;
import limn.graphics.SvgIcon;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.scene.layout.SizedBox;
import limn.testing.HeadlessUi;
import limn.testing.SceneDriver;
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
 * The button at the end of a field lights up under the pointer without touching the field's
 * rounded border: the border's arcs at the two corners on that side read the same, pixel for
 * pixel, with the pointer on the button as without it, at 1x, 2x and the 2.5x of a Windows
 * display, in either direction.
 *
 * <p>The date picker's calendar button drew its hover as a small pill after the picker had drawn
 * its border, and the pill's corners covered the border's arcs, seen on Windows at 250&nbsp;%. The
 * text field's coupled button and the spinner's arrows are here because they sit in the same place
 * and solved it otherwise (a fill under the border, a clip to its centre line).
 */
class FieldButtonCornerPixelTest {

    private static final int WIDTH = 900;
    private static final int HEIGHT = 300;
    /** How much darker or lighter the arc may read under the pointer, of 255: blending noise only. */
    private static final int TOLERANCE = 12;
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
        previousFbo = Gl.glGetInteger(GL33C.GL_FRAMEBUFFER_BINDING);
        Gl.glGetIntegerv(GL33C.GL_VIEWPORT, previousViewport);
        texture = Gl.glGenTextures();
        fbo = Gl.glGenFramebuffers();
        Gl.glActiveTexture(GL33C.GL_TEXTURE0);
        Gl.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
        Gl.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, WIDTH, HEIGHT, 0,
                GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST);
        Gl.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_NEAREST);
        Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, fbo);
        Gl.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0,
                GL33C.GL_TEXTURE_2D, texture, 0);
        Gl.glViewport(0, 0, WIDTH, HEIGHT);
    }

    @AfterEach
    void closeContext() {
        if (canvas != null) {
            Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, previousFbo);
            Gl.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            Gl.glDeleteFramebuffers(fbo);
            Gl.glDeleteTextures(texture);
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

    /** A field, and where on it the pointer goes, as fractions of its height in from its end. */
    private record Case(String name, Supplier<Widget<?>> make, float inFromEnd, float down) {
    }

    @Test
    void theBordersArcsAtTheButtonsEndReadTheSameUnderThePointer() {
        Case[] cases = {
                new Case("date picker", () -> new DatePicker().setClock(CLOCK).setDate(LocalDate.of(2026, 10, 20)),
                        0.5f, 0.5f),
                new Case("text field's button", () -> new TextField().setText("abc").setTrailingButton(
                        SvgIcon.fromResource("/limn/components/icons/close.svg"), () -> { }), 0.5f, 0.5f),
                new Case("spinner's up arrow", () -> new Spinner(0, 10, 1).setValue(5), 0.2f, 0.15f),
                new Case("spinner's down arrow", () -> new Spinner(0, 10, 1).setValue(5), 0.2f, 0.85f),
        };
        List<String> failures = new ArrayList<>();
        for (float scale : new float[] {1f, 2f, 2.5f}) {
            for (LayoutDirection direction : LayoutDirection.values()) {
                for (Case c : cases) {
                    Widget<?> widget = c.make().get();
                    Scene scene = scene(widget, direction);
                    byte[] rest = frame(scene, scale);
                    boolean rtl = direction == LayoutDirection.RTL;
                    float w = widget.width();
                    float h = widget.height();
                    float x = rtl ? h * c.inFromEnd() : w - h * c.inFromEnd();
                    var pointer = widget.localToScene(x, h * c.down());
                    SceneDriver.drive(scene).moveTo(pointer.x(), pointer.y());
                    byte[] hover = frame(scene, scale);
                    for (boolean top : new boolean[] {true, false}) {
                        int before = arc(rest, widget, scale, rtl, top);
                        int after = arc(hover, widget, scale, rtl, top);
                        if (Math.abs(after - before) > TOLERANCE) {
                            failures.add(String.format(Locale.ROOT, "%s, %s, %.1fx, %s corner: the arc reads %d"
                                    + " at rest and %d under the pointer", c.name(), direction, scale,
                                    top ? "top" : "bottom", before, after));
                        }
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private Scene scene(Widget<?> widget, LayoutDirection direction) {
        Column column = new Column();
        column.add(new SizedBox(200, SizedBox.UNSET, widget));
        Scene scene = new Scene(new Padding(new Insets(20, 0, 0, 20), column));
        scene.setLayoutDirection(direction);
        scene.setTextRuler(new ShapingRuler(fonts));
        scene.bind(new StubWindow());
        return scene;
    }

    private byte[] frame(Scene scene, float scale) {
        for (int i = 0; i < 2; i++) {
            canvas.beginFrame(WIDTH, HEIGHT, scale);
            scene.renderFrame(canvas, false, Float.NaN, 0);
            canvas.endFrame();
        }
        return read();
    }

    /**
     * The darkest pixel on the diagonal from the box's corner inward, as far as the field's radius
     * reaches: that is where the diagonal crosses the border's arc, so it reads the border's ink,
     * and reads the fill's instead if something was drawn over the arc.
     */
    private static int arc(byte[] image, Widget<?> widget, float scale, boolean rtl, boolean top) {
        var origin = widget.localToScene(0, 0);
        float radius = Theme.current().tokensFor(widget).radiusMedium();
        int x = rtl ? (int) Math.floor(origin.x() * scale)
                : (int) Math.ceil((origin.x() + widget.width()) * scale) - 1;
        int y = top ? (int) Math.floor(origin.y() * scale)
                : (int) Math.ceil((origin.y() + widget.height()) * scale) - 1;
        int dx = rtl ? 1 : -1;
        int dy = top ? 1 : -1;
        int darkest = 255;
        for (int k = 0; k <= Math.ceil(radius * scale); k++) {
            darkest = Math.min(darkest, lum(image, x + dx * k, y + dy * k));
        }
        return darkest;
    }

    private static int lum(byte[] image, int x, int y) {
        int at = (y * WIDTH + x) * 4;
        return ((image[at] & 0xFF) * 299 + (image[at + 1] & 0xFF) * 587 + (image[at + 2] & 0xFF) * 114) / 1000;
    }

    private static byte[] read() {
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
