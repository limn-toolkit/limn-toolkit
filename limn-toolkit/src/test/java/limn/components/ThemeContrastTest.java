package limn.components;

import limn.graphics.Color;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contrast targets the two Limn palettes were solved for.
 *
 * <p>Every tone in both was chosen by solving for a target against the tone beneath it, so
 * this is not a sanity check bolted on afterwards; it is the specification, and the hex
 * values are its output. A tone nudged "to taste" fails here, which is the point.
 *
 * <p>Both palettes face the same bars. A light palette is not an excuse for a softer set,
 * and running them through one parameterised suite is what stops the light one from
 * quietly becoming the lenient one.
 *
 * <p>The colour bars are WCAG&nbsp;2.1: 4.5:1 for body text, 3:1 for large text and for
 * meaningful non-text such as an accent-filled indicator or a focus ring. <b>Elevation is
 * asserted in L*, not in contrast ratio</b>, because the ratio is unusable for this at the
 * light end: the step Limn uses between its canvas and its cards is 1.46:1, and from a
 * near-white canvas that ratio would require a luminance above 1.0. L* is perceptual and
 * works at both ends.
 */
class ThemeContrastTest {

    /**
     * Every palette that ships, not just the project's own two. A palette a picker offers is a
     * palette an application will be read in, so the bars apply to all of them, and the ten
     * borrowed from other projects were renamed precisely because meeting these bars moved tones
     * far enough that calling them by the original names would have been a false claim.
     */
    static List<Theme> palettes() {
        return Theme.builtins();
    }

    /** WCAG 2.1 relative luminance. */
    private static double luminance(Color color) {
        return 0.2126 * channel(color.r()) + 0.7152 * channel(color.g()) + 0.0722 * channel(color.b());
    }

    private static double channel(float value) {
        return value <= 0.03928f ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** CIE L*, so one bar describes a perceptual step at either end of the range. */
    private static double lightness(Color color) {
        double y = luminance(color);
        return y > 0.008856 ? 116 * Math.cbrt(y) - 16 : 903.3 * y;
    }

    private static void atLeast(double bar, Color foreground, Color background, String what) {
        double actual = contrast(foreground, background);
        assertTrue(actual >= bar, what + ": " + String.format("%.2f", actual)
                + ":1, below the " + bar + ":1 it was solved for");
    }

    private static void stepsApart(double bar, Color a, Color b, String what) {
        double actual = Math.abs(lightness(a) - lightness(b));
        assertTrue(actual >= bar, what + ": " + String.format("%.1f", actual)
                + " L*, below the " + bar + " it was solved for");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("palettes")
    void textClearsEverySurfaceItLandsOn(Theme theme) {
        atLeast(12, theme.text, theme.background, "text on the canvas");
        atLeast(10, theme.text, theme.surface, "text on a card");
        atLeast(8, theme.text, theme.surfaceRaised, "text on a popover");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("palettes")
    void mutedTextIsStillBodyTextAndClears45(Theme theme) {
        atLeast(4.5, theme.textMuted, theme.background, "muted text on the canvas");
        atLeast(4.5, theme.textMuted, theme.surface, "muted text on a card");
        atLeast(4.5, theme.textMuted, theme.surfaceRaised, "muted text on a popover");
    }

    /**
     * The whole ramp, not just the resting accent, and the state that binds is not the same
     * one in both palettes. Limn's accent is light with dark ink, so pressing it (which
     * darkens) is what breaks first; Limn Light's is deep with white ink, so hovering it
     * (which lightens) is. Either way the defect is the same: a button whose own label gets
     * harder to read while the user is interacting with it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("palettes")
    void theAccentLabelSurvivesEveryStateOfTheAccent(Theme theme) {
        atLeast(4.5, theme.onPrimary, theme.primary, "label on the accent");
        atLeast(4.5, theme.onPrimary, theme.primaryHover, "label on the hovered accent");
        atLeast(4.5, theme.onPrimary, theme.primaryPressed, "label on the pressed accent");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("palettes")
    void theAccentReadsOnEverySurface(Theme theme) {
        atLeast(4.5, theme.primary, theme.background, "accent on the canvas");
        atLeast(3, theme.primary, theme.surface, "accent on a card");
        atLeast(3, theme.primary, theme.surfaceRaised, "accent on a popover");
    }

    /**
     * A focus ring drawn around an accent-filled button sits on the accent, so it has to
     * contrast with the accent itself: several palettes set focusRing equal to primary,
     * which makes the ring invisible on exactly the control most likely to be focused first.
     * Limn's ring is lighter than its accent and Limn Light's is darker; on a light canvas a
     * lighter ring has no solution at all.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("palettes")
    void theFocusRingIsVisibleOnTheControlItSurrounds(Theme theme) {
        atLeast(2, theme.focusRing, theme.primary, "focus ring on an accent-filled button");
        atLeast(3, theme.focusRing, theme.surface, "focus ring on a card");
        atLeast(3, theme.focusRing, theme.background, "focus ring on the canvas");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("palettes")
    void bordersAndElevationAreVisible(Theme theme) {
        atLeast(2, theme.outline, theme.surface, "a border on a card");
        stepsApart(5, theme.surface, theme.background, "a card lifting off the canvas");
        stepsApart(5, theme.surfaceRaised, theme.surface, "a popover lifting off a card");
    }

    /**
     * Error and informational text land on popovers and dialogs, which sit on the raised
     * surface, the one furthest from the canvas, and the one the shared semantic tones do
     * not clear in either palette. Both override the tones that fall short.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("palettes")
    void semanticStatesClearTheFurthestSurface(Theme theme) {
        atLeast(4.5, theme.danger, theme.surfaceRaised, "error text on a dialog");
        atLeast(4.5, theme.success, theme.surfaceRaised, "success text on a dialog");
        atLeast(4.5, theme.warning, theme.surfaceRaised, "warning text on a dialog");
        atLeast(4.5, theme.info, theme.surfaceRaised, "informational text on a dialog");
    }

    /** Disabled text must read as unavailable and still be legible. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("palettes")
    void disabledTextIsDimmedWithoutDisappearing(Theme theme) {
        atLeast(2, theme.disabledText, theme.surface, "disabled text on a card");
        assertTrue(contrast(theme.disabledText, theme.surface) < contrast(theme.textMuted, theme.surface),
                "disabled text must be dimmer than muted text, or the two states look alike");
    }

    /**
     * The project's own pair has to be usable as a pair: one accent family, opposite modes. This
     * one is deliberately NOT run over the others: a borrowed palette is recognised by its
     * accent, and demanding Limn's violet of all of them would leave twelve identical palettes.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("limnPair")
    void theLimnPairIsOneFamily(Theme theme) {
        Color accent = theme.primary;
        assertTrue(accent.b() > accent.r() && accent.r() > accent.g(),
                theme.name + ": the accent is not in the violet family (b > r > g)");
    }

    static List<Theme> limnPair() {
        return List.of(Theme.limn(), Theme.limnLight());
    }

    /**
     * The modal veil has to veil without hiding: at alpha 0 a modal gives no sign that the
     * rest of the window has stopped answering, and at alpha 1 the content it blocks is gone
     * rather than blocked.
     *
     * <p><b>The bar is on the alpha, and it cannot be on a composite.</b> The obvious
     * stronger test (veil the canvas and measure how far it moved) reports exactly zero for
     * High Contrast, whose canvas is already black, and that palette ships the heaviest veil
     * of the fifteen. What its veil dims is the ink on the canvas, not the canvas, so no one
     * composite describes the tone across the set. The two ends below are the claim; the
     * distance between them is taste, and is deliberately wide.
     *
     * <p><b>Both bars are steps of 1/255, not fractions</b>, and that is not tidiness. A
     * palette rounds every channel to {@code n/255}, so an alpha line set to 35% lands on 89
     * and never on 0.35; against a bar written as {@code 0.35f} a palette set to exactly the
     * number the bar names would fail it, and there would be no way to type one that passes.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("palettes")
    void theModalVeilVeilsWithoutHiding(Theme theme) {
        float alpha = theme.scrim.a();
        assertTrue(alpha >= 89 / 255f,
                theme.name + ": the modal veil is too faint to read as blocking (" + alpha + ')');
        assertTrue(alpha <= 230 / 255f,
                theme.name + ": the modal veil hides what it blocks instead of dimming it ("
                        + alpha + ')');
    }

    /** Every palette's mode flag has to agree with the canvas it actually ships. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("palettes")
    void theModeFlagAgreesWithTheCanvas(Theme theme) {
        assertTrue(theme.dark == (luminance(theme.background) < 0.2),
                theme.name + ": the dark flag disagrees with the canvas it ships");
    }
}
