package limn.components;

import limn.graphics.Color;
import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Every tone of a palette is opaque but the scrim, whose alpha is the veil's depth: a builder and
 * a theme file both drop the alpha of any other tone. When they kept it, a theme file with a
 * half-transparent focus ring drew it half-transparent on a list, which multiplies the tone's
 * alpha by its fade, and opaque on a button, which replaces it, so one palette had two rings.
 */
class ThemeToneAlphaTest extends ComponentTestBase {

    /** The ring tone a theme file wrote with eight digits: half transparent. */
    private static final Color HALF_RING = Color.rgba(0x5B9DFF, 0x80 / 255f);

    @AfterEach
    void putThePaletteBack() {
        Theme.setCurrent(Theme.dark());
    }

    @Test
    void aBuilderMakesEveryToneButTheScrimOpaque() {
        Theme.Builder builder = Theme.builder("Translucent", true);
        for (Theme.Token token : Theme.Token.values()) {
            builder.set(token, Color.rgba(0x336699, 0.5f));
        }
        Theme theme = builder.build();

        for (Theme.Token token : Theme.Token.values()) {
            Color tone = token.read(theme);
            if (token == Theme.Token.SCRIM) {
                assertEquals(128 / 255f, tone.a(), 1e-6f, "the scrim keeps its alpha");
            } else {
                assertEquals(1f, tone.a(), token.key() + " is opaque");
                assertEquals(Color.rgb(0x336699), tone, token.key() + " keeps its colour");
            }
        }
        assertEquals(Color.rgb(0x5B9DFF), Theme.builder("Ring", true).focusRing(HALF_RING).build().focusRing(),
                "the named setter drops the alpha as the token does");
    }

    @Test
    void aThemeFileWithAnEightDigitToneLoadsItOpaque() {
        Theme theme = ThemeFormat.parse("""
                name = Half ring
                dark = true
                focusRing = #5B9DFF80
                scrim = #00000080
                """);

        assertEquals(Color.rgb(0x5B9DFF), theme.focusRing(), "the ring is opaque");
        assertEquals(Color.rgba(0x000000, 0x80 / 255f), theme.scrim(), "the scrim keeps its alpha");
        assertEquals(theme, ThemeFormat.parse(ThemeFormat.write(theme)),
                "and what is written reads back as the same palette");
    }

    @Test
    void aButtonAndAListDrawTheSameRingAtFullFocus() {
        Theme.setCurrent(Theme.builder("Half ring", true).focusRing(HALF_RING).build());

        Button button = new Button("OK");
        Color buttonRing = ringOf(button, 120, 40, null);

        ListView<String> list = new ListView<>(Row::new);
        list.setItems(List.of("one", "two", "three"));
        list.setSelectionMode(SelectionMode.NONE); // the cursor alone, drawn as the ring
        Color listRing = ringOf(list, 200, 90, Keys.DOWN);

        assertEquals(1f, buttonRing.a(), "the button's ring at full focus is opaque");
        assertEquals(buttonRing, listRing, "and the list draws the same ring");
    }

    /**
     * Focuses {@code widget} alone in a scene, lets the focus fade arrive, and returns the paint
     * of the one stroke it draws at the ring's weight.
     */
    private Color ringOf(Widget<?> widget, float width, float height, Integer key) {
        AtomicLong clock = new AtomicLong();
        Scene scene = new Scene(widget, clock::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(width, height);
        scene.requestFocus(widget);
        if (key != null) {
            drive(scene).press(key);
        }
        // Two frames: a ticker's first carries no time, the second jumps far past the fade.
        scene.renderFrame(new FakeCanvas(width, height));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        scene.renderFrame(new FakeCanvas(width, height));
        RingCanvas canvas = new RingCanvas(width, height);
        scene.requestRender();
        scene.renderFrame(canvas);
        assertFalse(canvas.rings.isEmpty(), widget.getClass().getSimpleName() + " drew no ring");
        assertEquals(1, canvas.rings.size(), widget.getClass().getSimpleName() + " drew one ring");
        return (Color) canvas.rings.get(0);
    }

    /** Records the paint of every rounded stroke at the focus ring's weight. */
    private static final class RingCanvas extends FakeCanvas {
        final List<Paint> rings = new ArrayList<>();

        RingCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
            if (strokeWidth == Strokes.FOCUS_RING) {
                rings.add(paint);
            }
        }
    }

    /** A list row with a height and nothing to paint. */
    private static final class Row extends Widget<Row> {
        Row(String text) {
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), 24);
        }
    }
}
