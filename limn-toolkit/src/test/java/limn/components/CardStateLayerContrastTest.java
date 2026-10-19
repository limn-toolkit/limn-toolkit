package limn.components;

import limn.graphics.Color;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hover and the press of a clicked {@link Card} on every built-in palette and in each of the
 * four looks (ADR 073 §7), measured as {@link ThemeContrastTest} measures a lift, in L*: a hovered
 * secondary button steps 5.0 to 7.8 L* off its surface across the palettes, and the card's hover,
 * the text colour laid at six hundredths over the tone under it, steps 3.8 to 6.9. The layer goes
 * over the fill of every style, and over the page itself under a plain card, which is why it is the
 * text colour and not a step toward a raised tone.
 *
 * <p>What it costs the text over it, measured 2026-10-18: muted text keeps 4.5:1 under the hover
 * in every palette and every look, 4.51 at the least over the surface (Dark) and 4.57 over the page
 * (Light, a plain card). The filled card used to take the raised surface, where the same hover took
 * muted text to 3.78 to 4.10:1 in twelve palettes of fifteen; it takes the surface now, so one bar
 * holds for all four looks, and it is held apart from the page as a card lifting off it is.
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
        for (Card.Style style : Card.Style.values()) {
            Color fill = Card.fill(style, theme);
            // A plain card shows the page through it, and its layer is laid over the page.
            Color under = fill == null ? theme.background() : fill;
            String where = theme.name() + ", " + style;
            Color hovered = under.lerp(theme.text(), Card.HOVER_VEIL);
            Color pressed = under.lerp(theme.text(), Card.PRESS_VEIL);
            double hover = lift(hovered, under);
            double press = lift(pressed, under);
            assertTrue(hover >= 3.4, where + ": the hover moves " + String.format("%.2f", hover) + " L*");
            assertTrue(press >= hover + 2.5, where + ": the press moves " + String.format("%.2f", press)
                    + " L*, too close to the hover's " + String.format("%.2f", hover));
            double text = Color.contrastRatio(theme.text(), pressed);
            assertTrue(text >= 4.5, where + ": text over the pressed layer, " + String.format("%.2f", text));
            double muted = Color.contrastRatio(theme.textMuted(), hovered);
            assertTrue(muted >= 4.5, where + ": muted text over the hovered layer, "
                    + String.format("%.2f", muted));
            double resting = Color.contrastRatio(theme.textMuted(), under);
            assertTrue(resting >= 4.5, where + ": muted text at rest, " + String.format("%.2f", resting));
        }
    }

    /**
     * A filled card is set apart from the page by its tone alone, so the tone has to be seen: as
     * far off the page as a card lifting off it, 5 L*, the bar {@link ThemeContrastTest} holds the
     * surface to. Its first fill, the raised surface, was 0.02 L* off the Light palette's page.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("palettes")
    void aFilledCardStandsApartFromThePage(Theme theme) {
        Color fill = Card.fill(Card.Style.FILLED, theme);
        assertTrue(fill != null, theme.name() + ": a filled card has a fill");
        double step = lift(fill, theme.background());
        assertTrue(step >= 5, theme.name() + ": a filled card is " + String.format("%.2f", step)
                + " L* off the page");
    }
}
