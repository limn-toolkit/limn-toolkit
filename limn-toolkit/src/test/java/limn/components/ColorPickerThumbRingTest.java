package limn.components;

import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.scene.Scene;
import limn.scene.SystemPreferences;
import limn.scene.SystemPreferences.ColorScheme;
import limn.scene.internal.SystemPreferencesAccess;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The ring round a focused channel rail's thumb is drawn in the palette the rail paints with, the
 * contrast palette while the system asks for more contrast (ADR 081), as the rail's own outline
 * is: it was drawn from {@link Theme#current()}, the application's focus ring beside the contrast
 * theme's.
 */
class ColorPickerThumbRingTest extends ComponentTestBase {

    @AfterEach
    void putBackTheSystem() {
        SystemPreferences.report(SystemPreferences.NONE);
    }

    @Test
    void theThumbsFocusRingIsThePaletteTheRailPaintsWith() {
        SystemPreferences.ContrastColors hc2 = ContrastPaletteTest.WINDOWS_THEMES.get("hc2");
        SystemPreferences.report(SystemPreferencesAccess.of(ColorScheme.LIGHT, false, true, false, hc2));
        ColorPicker picker = new ColorPicker();
        AtomicLong nanos = new AtomicLong();
        Scene scene = new Scene(picker, nanos::get);
        scene.setTextRuler(RULER);
        Rings canvas = new Rings(420, 640);
        scene.renderFrame(canvas);
        scene.requestFocus(picker.rail(0));
        for (int i = 0; i < 40; i++) { // the ring fades in on the scene's clock
            nanos.addAndGet(100_000_000L);
            scene.renderFrame(canvas, false, Float.NaN, 0);
        }
        canvas.rings.clear();
        scene.renderFrame(canvas, false, Float.NaN, 0);
        assertFalse(canvas.rings.isEmpty(), "the focused rail rings its thumb");
        assertEquals(Theme.of(picker.rail(0)).focusRing(), hc2.link(), "sanity: the contrast theme's link colour");
        assertEquals(Set.of(hc2.link()), new HashSet<>(canvas.rings), "every ring is the contrast theme's");
    }

    /** Every focus-ring-wide rounded outline a frame strokes, in its colour. */
    private static final class Rings extends NoopCanvas {
        final List<Paint> rings = new ArrayList<>();

        Rings(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
            if (strokeWidth == Strokes.FOCUS_RING && paint instanceof Color color && color.a() > 0.99f) {
                rings.add(color.withAlpha(1)); // the fade in's last step, whole
            }
        }
    }
}
