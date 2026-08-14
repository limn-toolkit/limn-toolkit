package limn.themeeditor;

import limn.components.Theme;
import limn.graphics.Color;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit, against palettes built to fail one rule each.
 *
 * <p>Deliberately <b>not</b> asserted against the palettes the toolkit ships. Several of
 * them do miss a bar (that is a known finding about those palettes, not about this code),
 * and a test that pinned today's list would have to be edited every time one of them is
 * corrected, which is the opposite of what a test is for. The two the repository solved for
 * contrast on purpose are checked, because for those the bar <em>is</em> the specification.
 *
 * <p>The modal veil is the other exception, for a different reason: its bars are not the
 * guideline's, they are this project's own taste, and the shipped palettes are the set that
 * taste was formed on. An audit that flagged one of them would mean the bar is wrong rather
 * than the palette, so every built-in is held to that one rule here.
 */
class ThemeAuditTest {

    /** A palette with one tone moved, so exactly one rule is under test. */
    private static Theme with(Theme.Token token, Color colour) {
        return Theme.dark().toBuilder().set(token, colour).build();
    }

    private static boolean flags(List<ThemeAudit.Finding> findings,
                                 Theme.Token subject, Theme.Token against) {
        return findings.stream()
                .anyMatch(f -> f.subject() == subject && f.against() == against);
    }

    // --- the two palettes that were solved for these bars ---------------------

    @Test
    void theToolkitsOwnPalettesCarryNoFailingText() {
        for (Theme theme : List.of(Theme.limn(), Theme.limnLight())) {
            List<ThemeAudit.Finding> errors = ThemeAudit.of(theme).stream()
                    .filter(f -> f.level() == ThemeAudit.Level.ERROR)
                    .toList();
            assertEquals(List.of(), errors, theme.name + " has illegible text: " + describe(errors));
        }
    }

    // --- one rule at a time ---------------------------------------------------

    @Test
    void bodyTextThatVanishesIntoASurfaceIsAnError() {
        List<ThemeAudit.Finding> findings = ThemeAudit.of(with(Theme.Token.TEXT,
                Theme.dark().surfaceRaised));
        assertTrue(flags(findings, Theme.Token.TEXT, Theme.Token.SURFACE_RAISED));
        assertTrue(findings.stream()
                .anyMatch(f -> f.subject() == Theme.Token.TEXT
                        && f.level() == ThemeAudit.Level.ERROR));
    }

    /**
     * The failure a hand-built palette actually ships: muted text chosen by eye against the
     * canvas, and never compared to the popover it also lands on.
     */
    @Test
    void mutedTextIsHeldToTheSameBarAsBodyText() {
        Theme theme = with(Theme.Token.TEXT_MUTED, Color.rgb(0x4A5262));
        List<ThemeAudit.Finding> findings = ThemeAudit.of(theme);
        assertTrue(flags(findings, Theme.Token.TEXT_MUTED, Theme.Token.SURFACE_RAISED));
        for (ThemeAudit.Finding finding : findings) {
            if (finding.subject() == Theme.Token.TEXT_MUTED) {
                assertEquals(4.5, finding.required(), 1e-9, "muted text is still body text");
            }
        }
    }

    /**
     * The pressed accent is the tone nobody looks at while choosing one, and pressing a
     * button is exactly when its label has to be readable.
     */
    @Test
    void theAccentsLabelIsCheckedAgainstAllThreeAccentStates() {
        Theme theme = Theme.dark().toBuilder()
                .primary(Color.rgb(0xFFE000))
                .onPrimary(Color.WHITE)
                .deriveAccentStates()
                .build();
        List<ThemeAudit.Finding> findings = ThemeAudit.of(theme);
        assertTrue(flags(findings, Theme.Token.ON_PRIMARY, Theme.Token.PRIMARY));
        assertTrue(flags(findings, Theme.Token.ON_PRIMARY, Theme.Token.PRIMARY_HOVER));
        assertTrue(flags(findings, Theme.Token.ON_PRIMARY, Theme.Token.PRIMARY_PRESSED));
    }

    /**
     * The rule no contrast check would catch on its own, and the one eight of the shipped
     * palettes miss: a ring the same colour as the accent disappears the moment it lands on
     * an accent-filled control, which is where a keyboard user most needs it.
     */
    @Test
    void aFocusRingTheColourOfTheAccentIsReported() {
        Theme theme = Theme.dark().toBuilder().focusRing(Theme.dark().primary).build();
        List<ThemeAudit.Finding> findings = ThemeAudit.of(theme);
        assertTrue(flags(findings, Theme.Token.FOCUS_RING, Theme.Token.PRIMARY));
        assertFalse(flags(ThemeAudit.of(Theme.dark()), Theme.Token.FOCUS_RING, Theme.Token.PRIMARY),
                "the built-in dark palette does not have this problem");
    }

    @Test
    void aCardYouCannotSeeOnTheCanvasIsAnElevationFinding() {
        Theme theme = with(Theme.Token.SURFACE, Theme.dark().background);
        ThemeAudit.Finding step = ThemeAudit.of(theme).stream()
                .filter(f -> f.metric() == ThemeAudit.Metric.LIGHTNESS_STEP
                        && f.subject() == Theme.Token.SURFACE)
                .findFirst().orElseThrow();
        assertEquals(ThemeAudit.Level.WARNING, step.level());
        assertEquals(0, step.measured(), 1e-9);
    }

    /**
     * Disabled text is exempt from the guideline. Reporting it as an error would teach a
     * palette author to make a switched-off control shout.
     */
    @Test
    void unreadableDisabledTextIsANoteAndNotAnError() {
        Theme theme = with(Theme.Token.DISABLED_TEXT, Theme.dark().disabledFill);
        ThemeAudit.Finding note = ThemeAudit.of(theme).stream()
                .filter(f -> f.subject() == Theme.Token.DISABLED_TEXT)
                .findFirst().orElseThrow();
        assertEquals(ThemeAudit.Level.INFO, note.level());
    }

    // --- the modal veil -------------------------------------------------------

    /** The veil's finding, or {@code null}: the one measurement that names a single tone. */
    private static ThemeAudit.Finding veilOf(Theme theme) {
        return ThemeAudit.of(theme).stream()
                .filter(f -> f.subject() == Theme.Token.SCRIM)
                .findFirst().orElse(null);
    }

    private static Theme withVeil(float alpha) {
        return with(Theme.Token.SCRIM, Color.rgba(0x000000, alpha));
    }

    /**
     * A veil nobody can see gives no sign that the rest of the window has stopped answering;
     * the modal reads as one more layer rather than as a block.
     */
    @Test
    void aVeilTooFaintToReadAsBlockingIsReported() {
        ThemeAudit.Finding veil = veilOf(withVeil(0.1f));
        assertEquals(ThemeAudit.Metric.ALPHA, veil.metric());
        assertEquals(ThemeAudit.Bound.AT_LEAST, veil.bound());
        assertEquals(10.2, veil.measured(), 0.05, "26/255, the eight-bit grid the builder snaps to");
        assertEquals(89 * 100 / 255.0, veil.required(), 1e-9,
                "the bar is the step an alpha line set to 35% lands on");
    }

    /** At the other end the content behind the card is not dimmed but gone. */
    @Test
    void aVeilThatHidesWhatItBlocksIsReported() {
        ThemeAudit.Finding veil = veilOf(withVeil(1f));
        assertEquals(ThemeAudit.Bound.AT_MOST, veil.bound());
        assertEquals(100, veil.measured(), 1e-9);
        assertTrue(veil.describe().contains("above"),
                "a ceiling has to read as one: " + veil.describe());
    }

    /**
     * The veil composites over whatever the application is showing, which is the one thing a
     * palette cannot know. Naming a second tone here (the canvas, or the veil itself) would
     * be reporting a comparison that was never made.
     */
    @Test
    void theVeilIsMeasuredOnItsOwnAndNamesNoSecondTone() {
        ThemeAudit.Finding veil = veilOf(withVeil(0.1f));
        assertNull(veil.against());
        assertFalse(veil.metric().isPairwise());
        assertFalse(veil.describe().contains(" on "),
                "a lone measurement must not read as a pair: " + veil.describe());
        assertTrue(veil.describe().startsWith("scrim: 10."), veil.describe());
    }

    /**
     * WCAG has no bar for a modal veil at all. An error would set a number this project
     * invented beside the ones the guideline wrote; a note is for a bar the guideline
     * <em>exempts</em>, and it has not exempted this; it has never considered it.
     */
    @Test
    void neitherEndOfTheVeilIsAnErrorOrANote() {
        assertEquals(ThemeAudit.Level.WARNING, veilOf(withVeil(0.1f)).level());
        assertEquals(ThemeAudit.Level.WARNING, veilOf(withVeil(1f)).level());
    }

    /**
     * The bars are steps of alpha rather than fractions of it, because a palette is written
     * down in eight bits per channel: an alpha line set to 35% lands on 89/255 and never on
     * 0.35, so a bar of "35%" would be one the author's own control cannot reach; they
     * would type the number the finding asked for and the finding would stay.
     */
    @Test
    void aVeilSetToTheNumberTheBarNamesClearsIt() {
        assertNull(veilOf(withVeil(0.55f)), "the veil every palette ships unless it says otherwise");
        assertNull(veilOf(withVeil(0.35f)), "35% on an alpha line is 89/255");
        assertNull(veilOf(withVeil(0.9f)), "and 90% is 230/255");
        assertNotNull(veilOf(withVeil(0.34f)), "a step under the floor is still a finding");
        assertNotNull(veilOf(withVeil(0.91f)), "and a step over the ceiling");
    }

    /** Every palette the toolkit ships clears the rule, since they are what it was drawn from. */
    @Test
    void noShippedPaletteTripsTheVeilRule() {
        for (Theme theme : Theme.builtins()) {
            ThemeAudit.Finding veil = veilOf(theme);
            assertNull(veil, theme.name + ": " + (veil == null ? "" : veil.describe()));
        }
    }

    /**
     * The improvement that must never be made, pinned as a premise and a verdict.
     *
     * <p>Compositing the veil over the canvas and reporting how far the canvas moved looks
     * like the stronger measurement. It answers <b>zero</b> for this palette, whose canvas is
     * already black, and this is the palette with the heaviest veil of the fifteen, because
     * what its veil dims is the white ink on that canvas, not the canvas. A composite bar
     * would report the one palette that got this most right.
     */
    @Test
    void theHeaviestShippedVeilIsCleanAndACompositeWouldCallItNothing() {
        Theme theme = builtin("High Contrast");
        assertTrue(theme.scrim.a() > Theme.dark().scrim.a(),
                "the premise: this palette ships the heavier veil");

        Color veiled = theme.background.lerp(theme.scrim.withAlpha(1f), theme.scrim.a());
        assertEquals(theme.background.lightness(), veiled.lightness(), 1e-9,
                "the trap: veiling this canvas moves it by nothing measurable");

        assertNull(veilOf(theme), "and the audit must still call the palette clean");
    }

    /** The report reads top to bottom; a lone measurement must not upset the order. */
    @Test
    void aVeilFindingSortsWithTheOtherWarnings() {
        Theme theme = Theme.dark().toBuilder()
                .text(Theme.dark().surface)
                .scrim(Color.rgba(0x000000, 0.1f))
                .disabledText(Theme.dark().disabledFill)
                .build();
        List<ThemeAudit.Finding> findings = ThemeAudit.of(theme);
        assertTrue(findings.stream().anyMatch(f -> f.subject() == Theme.Token.SCRIM));
        for (int i = 1; i < findings.size(); i++) {
            assertTrue(findings.get(i - 1).level().compareTo(findings.get(i).level()) <= 0);
        }
    }

    // --- what a finding may claim ---------------------------------------------

    /**
     * The two ways to smuggle a lone measurement in as a pair, both refused where they would
     * be written rather than where they would be read.
     */
    @Test
    void aFindingCannotInventTheToneItWasMeasuredAgainst() {
        assertThrows(IllegalArgumentException.class, () ->
                new ThemeAudit.Finding(ThemeAudit.Level.WARNING, Theme.Token.SCRIM,
                        Theme.Token.SCRIM, ThemeAudit.Metric.ALPHA,
                        ThemeAudit.Bound.AT_LEAST, 10, 35),
                "a tone measured against itself");
        assertThrows(IllegalArgumentException.class, () ->
                new ThemeAudit.Finding(ThemeAudit.Level.WARNING, Theme.Token.SCRIM,
                        Theme.Token.BACKGROUND, ThemeAudit.Metric.ALPHA,
                        ThemeAudit.Bound.AT_LEAST, 10, 35),
                "an alpha does not composite against a tone the palette knows");
        assertThrows(IllegalArgumentException.class, () ->
                new ThemeAudit.Finding(ThemeAudit.Level.ERROR, Theme.Token.TEXT, null,
                        ThemeAudit.Metric.CONTRAST, ThemeAudit.Bound.AT_LEAST, 1, 4.5),
                "a ratio is between two tones and cannot have one");
    }

    private static Theme builtin(String name) {
        return Theme.builtins().stream().filter(t -> t.name.equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no built-in called " + name));
    }

    // --- the shape of the answer ---------------------------------------------

    @Test
    void findingsComeBackWorstFirst() {
        Theme theme = Theme.dark().toBuilder()
                .text(Theme.dark().surface)
                .focusRing(Theme.dark().primary)
                .disabledText(Theme.dark().disabledFill)
                .build();
        List<ThemeAudit.Finding> findings = ThemeAudit.of(theme);
        assertEquals(ThemeAudit.Level.ERROR, ThemeAudit.worst(findings));
        for (int i = 1; i < findings.size(); i++) {
            assertTrue(findings.get(i - 1).level().compareTo(findings.get(i).level()) <= 0,
                    "an editor reads this top to bottom; the errors have to be at the top");
        }
    }

    @Test
    void aFindingSaysWhatItMeasuredAndWhatItNeeded() {
        ThemeAudit.Finding finding = ThemeAudit.of(with(Theme.Token.TEXT, Theme.dark().surface))
                .stream().filter(f -> f.against() == Theme.Token.SURFACE).findFirst().orElseThrow();
        String line = finding.describe();
        assertTrue(line.startsWith("text on surface"), line);
        assertTrue(line.contains("1.00:1"), line);
        assertTrue(line.contains("4.50:1"), line);
    }

    @Test
    void worstOfNothingIsNothing() {
        assertNull(ThemeAudit.worst(List.of()));
    }

    private static String describe(List<ThemeAudit.Finding> findings) {
        StringBuilder out = new StringBuilder();
        for (ThemeAudit.Finding finding : findings) {
            out.append("\n  ").append(finding.describe());
        }
        return out.toString();
    }
}
