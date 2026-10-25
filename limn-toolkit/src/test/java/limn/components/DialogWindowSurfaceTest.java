package limn.components;

import limn.backend.NativeWindow;
import limn.backend.WindowStyle;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.scene.Scene;
import limn.scene.SystemPreferences;
import limn.scene.SystemPreferences.ColorScheme;
import limn.scene.internal.SystemPreferencesAccess;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dialog in a window of its own, decorated or opaque, fills that window from the palette its
 * text paints with (ADR 081): the contrast palette while the system asks for more contrast, the
 * application's otherwise, and it follows either one changing while the dialog is open. The fill
 * was the scene's clear colour, copied from {@link Theme#current()} as the window opened, while
 * every widget inside paints through {@link Theme#of}: a light application under a dark Windows
 * contrast theme drew the message white on white, and a dark one under a light contrast theme
 * dark on dark.
 */
class DialogWindowSurfaceTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    private HeadlessBackend backend;

    @AfterEach
    void putBackTheSystem() {
        SystemPreferences.report(SystemPreferences.NONE);
    }

    private static void contrast(String windowsTheme) {
        SystemPreferences.report(SystemPreferencesAccess.of(ColorScheme.LIGHT, false, true, false,
                ContrastPaletteTest.WINDOWS_THEMES.get(windowsTheme)));
    }

    @ParameterizedTest
    @EnumSource(value = WindowStyle.class, names = {"DECORATED", "UNDECORATED_OPAQUE"})
    void theWindowIsFilledFromThePaletteItsTextPaintsWith(WindowStyle style) {
        Theme.setCurrent(Theme.light());
        contrast("hc2"); // black window, white text
        backend = new HeadlessBackend(runtime);
        HeadlessWindow owner = backend.open("Orders", 800, 600);
        Scene host = new Scene(new Label("Orders"), clock::get);
        host.setTextRuler(RULER);
        host.bind(owner);
        owner.frame();
        Dialog dialog = new Dialog("Ship order", "Harbor Supply")
                .setDisplayMode(DisplayMode.NATIVE_WINDOW)
                .setStyle(style)
                .addButton("Cancel", "cancel")
                .addPrimaryButton("Ship", "ship");
        dialog.show(host);
        settle();
        Scene window = Objects.requireNonNull(dialog.contentRoot().scene());
        assertTrue(window != host, "sanity: the dialog is a window of its own");

        Painted dark = paint(window);
        assertEquals(Color.rgb(0x000000), dark.fill, style + ": the contrast theme's window colour");
        assertEquals(Color.rgb(0xFFFFFF), dark.message, style + ": the contrast theme's text on it");
        samePalette(dialog, dark, style + " under the dark contrast theme");

        SystemPreferences.report(SystemPreferences.NONE); // the user turns contrast off
        samePalette(dialog, paint(window), style + " with contrast turned off while open");
        assertEquals(Theme.light().surface(), paint(window).fill, style + ": the application's light palette");

        Theme.setCurrent(Theme.dark()); // the application switches palette
        samePalette(dialog, paint(window), style + " after a switch to the dark palette while open");
        assertEquals(Theme.dark().surface(), paint(window).fill, style + ": the dark palette");

        contrast("hcwhite"); // a light contrast theme over the dark application
        Painted light = paint(window);
        assertEquals(Color.rgb(0xFFFAEF), light.fill, style + ": the light contrast theme's window colour");
        samePalette(dialog, light, style + " under the light contrast theme");
    }

    /** The window's fill and the message's ink are one palette's: the one the content paints with. */
    private static void samePalette(Dialog dialog, Painted painted, String when) {
        Theme content = Theme.of(dialog.contentRoot());
        assertEquals(content.surface(), painted.fill, when + ": the window is the content's surface");
        assertEquals(content.textMuted(), painted.message, when + ": the message is the content's text");
        double ratio = Color.contrastRatio(painted.message, painted.fill);
        assertTrue(ratio >= 4.5, when + ": the message reads on the window, " + ratio);
    }

    /** What a whole frame of the dialog's window leaves behind its content, and the message's ink. */
    private record Painted(Color fill, Color message) {
    }

    private static Painted paint(Scene window) {
        NativeWindow native_ = Objects.requireNonNull(window.window());
        Capture capture = new Capture(native_.logicalWidth(), native_.logicalHeight());
        window.renderFrame(capture, false, Float.NaN, 0); // age 0: the whole window repainted
        assertNotNull(capture.fill, "the window was filled");
        assertNotNull(capture.message, "the message was drawn");
        return new Painted(capture.fill, capture.message);
    }

    /**
     * Keeps the last colour that covered the whole window, a clear or an opaque fill as large as
     * it, and the ink the message was drawn in.
     */
    private static final class Capture extends NoopCanvas {
        private final float width;
        private final float height;
        private final List<float[]> stack = new ArrayList<>();
        private float dx;
        private float dy;
        Color fill;
        Color message;

        Capture(float width, float height) {
            super(width, height);
            this.width = width;
            this.height = height;
        }

        @Override
        public void save() {
            super.save();
            stack.add(new float[] {dx, dy});
        }

        @Override
        public void restore() {
            super.restore();
            if (!stack.isEmpty()) {
                float[] was = stack.remove(stack.size() - 1);
                dx = was[0];
                dy = was[1];
            }
        }

        @Override
        public void restoreToCount(int count) {
            while (saveCount() > count && saveCount() > 0) {
                restore();
            }
        }

        @Override
        public void translate(float x, float y) {
            dx += x;
            dy += y;
        }

        @Override
        public void clear(Color color) {
            fill = color;
        }

        @Override
        public void clearRect(float x, float y, float w, float h, Color color) {
            if (covers(x, y, w, h)) {
                fill = color;
            }
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            if (paint instanceof Color color && color.a() >= 1 && covers(x, y, w, h)) {
                fill = color;
            }
        }

        private boolean covers(float x, float y, float w, float h) {
            return dx + x <= 0.5f && dy + y <= 0.5f && dx + x + w >= width - 0.5f && dy + y + h >= height - 0.5f;
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            if (text.equals("Harbor Supply") && paint instanceof Color color) {
                message = color;
            }
        }
    }

    /** Frames every window over half a second of the clock, so the fades finish. */
    private void settle() {
        for (int i = 0; i < 12; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
            runtime.drain();
            for (HeadlessWindow w : backend.windows()) {
                if (w.scene() != null && !w.isClosed()) {
                    w.frame();
                }
            }
        }
        runtime.drain();
    }
}
