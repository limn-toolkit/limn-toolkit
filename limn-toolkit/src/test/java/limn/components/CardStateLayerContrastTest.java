package limn.components;

import limn.graphics.Color;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hover and the press of a clicked {@link Card} on every built-in palette (ADR 073 §7),
 * measured as {@link ThemeContrastTest} measures a lift, in L*: a hovered secondary button steps
 * 5.0 to 7.8 L* off its surface across the palettes, and the card's hover, the text colour laid at
 * six hundredths over its fill, steps 3.5 to 6.9. The layer goes over the fill of every style, a
 * raised one included, which is why it is the text colour and not a step toward the raised tone.
 *
 * <p>What it costs the text over it, measured 2026-10-18: over a plain fill (the raised and the
 * outlined card) muted text keeps 4.5:1 under the hover in every palette, 4.51 at the least (Dark).
 * Over the raised fill of a filled card it does not: muted text there already sits a hair above
 * 4.5:1 at rest in most palettes, and any veil seen at all takes it to 3.78 to 4.10:1 in twelve of
 * fifteen. The floor below is that measurement, written down so a change that lowers it fails, and
 * the choice between a quieter hover on a filled card and this one is the owner's.
 */
class CardStateLayerContrastTest {

    static List<Theme> palettes() {
        return Theme.builtins();
    }

    private static double lift(Color a, Color b) {
        return Math.abs(a.lightness() - b.lightness());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("palettes")
    void theHoverAndThePressAreSeenAndTheTextUnderThemStillReads(Theme theme) {
        for (Color fill : List.of(theme.surface(), theme.surfaceRaised())) {
            boolean raised = fill.equals(theme.surfaceRaised()) && !fill.equals(theme.surface());
            String where = theme.name() + (raised ? ", a filled card" : ", a raised or outlined card");
            Color hovered = fill.lerp(theme.text(), Card.HOVER_VEIL);
            Color pressed = fill.lerp(theme.text(), Card.PRESS_VEIL);
            double hover = lift(hovered, fill);
            double press = lift(pressed, fill);
            assertTrue(hover >= 3.4, where + ": the hover moves " + String.format("%.2f", hover) + " L*");
            assertTrue(press >= hover + 2.5, where + ": the press moves " + String.format("%.2f", press)
                    + " L*, too close to the hover's " + String.format("%.2f", hover));
            double text = Color.contrastRatio(theme.text(), pressed);
            assertTrue(text >= 4.5, where + ": text over the pressed layer, " + String.format("%.2f", text));
            double muted = Color.contrastRatio(theme.textMuted(), hovered);
            double floor = raised ? 3.75 : 4.5;
            assertTrue(muted >= floor, where + ": muted text over the hovered layer, " + String.format("%.2f", muted)
                    + ", below " + floor);
        }
    }
}
