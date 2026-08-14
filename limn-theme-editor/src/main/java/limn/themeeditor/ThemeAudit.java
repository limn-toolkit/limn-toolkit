package limn.themeeditor;

import limn.components.Theme;
import limn.graphics.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What is wrong with a palette, measured rather than judged.
 *
 * <p>A palette is easy to build and hard to build <em>legibly</em>: every tone is chosen
 * against a white page in an editor and then lands on a surface it was never compared to.
 * This runs the comparisons (every ink against every surface it can appear on, every
 * elevation step, and the two distinctness rules that a contrast ratio alone would call
 * fine), so an editor can say which pair is the problem instead of leaving it to be found
 * in a screenshot later.
 *
 * <p><b>The bars are WCAG 2.1.</b> 4.5:1 for body text, 3:1 for large text and for
 * meaningful non-text such as an accent-filled indicator or a focus ring. Disabled text is
 * <em>exempt</em> from the guideline and is reported at {@link Level#INFO} for that reason:
 * a control that is switched off is allowed to be quiet, and an editor that flagged it as
 * an error would be teaching the wrong lesson.
 *
 * <p>Elevation is measured in CIE L*, not in contrast ratio; see
 * {@link Color#lightness()} for why the ratio is unusable for it at the light end.
 *
 * <p><b>One tone is measured alone.</b> The modal veil is a colour with an alpha painted
 * over whatever the application happens to be showing, so there is nothing in the palette
 * to measure it against and its finding names no second tone. The guideline has no bar for
 * a veil at all, which is why both ends of that rule are a {@link Level#WARNING} and
 * neither is an error.
 *
 * <p>Pure computation over a value: no widgets, no UI thread, no state.
 */
public final class ThemeAudit {

    /** How much a finding matters. */
    public enum Level {
        /** A bar the guideline sets for text. Something is hard to read. */
        ERROR,
        /** A bar for non-text, or a step too small to see. Something is hard to make out. */
        WARNING,
        /** Below a bar the guideline exempts. Worth knowing, not worth fixing. */
        INFO
    }

    /** Which scale a finding was measured on, and how a number on it is written. */
    public enum Metric {
        /** WCAG contrast ratio between two tones, 1 to 21. */
        CONTRAST(true, ":1"),
        /** CIE L* difference between two tones, 0 to 100. */
        LIGHTNESS_STEP(true, " L*"),
        /**
         * How opaque one tone is, 0 to 100: a percentage rather than a fraction because a
         * percentage is what a colour picker's alpha line reads, so the number in the
         * finding is the number the author has to move.
         */
        ALPHA(false, "% alpha");

        private final boolean pairwise;
        private final String unit;

        Metric(boolean pairwise, String unit) {
            this.pairwise = pairwise;
            this.unit = unit;
        }

        /**
         * Whether this scale compares two tones, and so whether a finding on it names an
         * {@link Finding#against}. Alpha does not: it belongs to a single tone.
         */
        public boolean isPairwise() {
            return pairwise;
        }

        /** How a number on this scale is written, appended to the number itself. */
        public String unit() {
            return unit;
        }
    }

    /** Which side of its bar a measurement came out on. */
    public enum Bound {
        /** {@code required} is a floor, and the palette scored under it. */
        AT_LEAST,
        /** {@code required} is a ceiling, and the palette scored over it. */
        AT_MOST
    }

    /**
     * One measurement that missed its bar.
     *
     * @param level    how much it matters
     * @param subject  the tone that is hard to see
     * @param against  the tone it is hard to see against, or {@code null} when
     *                 {@code metric} measures {@code subject} on its own. Naming a second
     *                 tone anyway (the subject twice, or whichever surface seems likely)
     *                 states a comparison that was never made, and the next reader acts on
     *                 it
     * @param metric   the scale {@code measured} and {@code required} are on
     * @param bound    whether {@code required} is a floor or a ceiling
     * @param measured what the palette scored
     * @param required the bar it missed
     */
    public record Finding(Level level, Theme.Token subject, Theme.Token against,
                          Metric metric, Bound bound, double measured, double required) {

        /**
         * @throws IllegalArgumentException if {@code against} disagrees with
         *                                  {@link Metric#isPairwise()}, or if a tone is
         *                                  measured against itself: a finding that named a
         *                                  comparison nobody made would be acted on
         */
        public Finding {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(metric, "metric");
            Objects.requireNonNull(bound, "bound");
            if (metric.isPairwise() != (against != null)) {
                throw new IllegalArgumentException(metric.isPairwise()
                        ? metric + " compares two tones, and " + subject.key() + " names none"
                        : metric + " measures one tone, and " + subject.key() + " names "
                                + against.key() + " as well");
            }
            if (subject == against) {
                throw new IllegalArgumentException(
                        subject.key() + " cannot be measured against itself");
            }
        }

        /**
         * A line an editor can show: {@code "text on surface: 3.21:1, below 4.50:1"}, or
         * {@code "scrim: 10.20% alpha, below 35.00% alpha"} for a tone measured alone.
         */
        public String describe() {
            String unit = metric.unit();
            return subject.key() + (against == null ? "" : " on " + against.key()) + ": "
                    + format(measured) + unit
                    + (bound == Bound.AT_LEAST ? ", below " : ", above ")
                    + format(required) + unit;
        }

        private static String format(double value) {
            return String.format(java.util.Locale.ROOT, "%.2f", value);
        }
    }

    /**
     * The faintest veil that still reads as one, as one of the 255 steps a palette's alpha
     * is written down in. Below roughly a third, the screen behind a modal is dimmed by no
     * more than a palette's own layering already dims it (a raised surface, a shadow), so
     * the dialog reads as one more layer rather than as the rest of the window having
     * stopped answering.
     *
     * <p><b>Both bars are steps, not fractions</b>, and the comparison happens on the step.
     * A palette rounds every channel to {@code n/255}, so an alpha line set to 35% lands on
     * 89 and never on 0.35: a bar of "35%" would be one the author's own control cannot
     * express, and typing the number the finding asks for would leave the finding standing.
     */
    private static final int VEIL_FAINTEST = 89;

    /**
     * The heaviest, on the same scale: where an alpha line set to 90% lands. Past nine
     * tenths what the dialog blocks is not dimmed but gone, and a user cannot tell an
     * application waiting behind a modal from one that has lost its screen.
     */
    private static final int VEIL_HEAVIEST = 230;

    /** A step of alpha as the percentage the report and the picker's alpha line speak. */
    private static double percentOf(int steps) {
        return steps * 100 / 255.0;
    }

    private ThemeAudit() {
    }

    /**
     * Every bar {@code theme} misses, worst first and then in token order.
     *
     * <p>An empty list is not a promise that a palette is beautiful, only that nothing
     * in it is measurably illegible. Taste is still the author's.
     */
    public static List<Finding> of(Theme theme) {
        Objects.requireNonNull(theme, "theme");
        List<Finding> findings = new ArrayList<>();

        // Body text, on all three surfaces it can land on. surfaceRaised is the one people
        // forget: it is where popovers, menus and dialogs sit, so it is where the error
        // message an application most wants read actually gets drawn.
        for (Theme.Token surface : surfaces()) {
            contrast(findings, Level.ERROR, theme, Theme.Token.TEXT, surface, 4.5);
            contrast(findings, Level.ERROR, theme, Theme.Token.TEXT_MUTED, surface, 4.5);
            // Muted text is still body text. It is the single most common failure in a
            // hand-built palette, because "muted" is chosen by eye against one surface.
            contrast(findings, Level.ERROR, theme, Theme.Token.DANGER, surface, 4.5);
            contrast(findings, Level.ERROR, theme, Theme.Token.SUCCESS, surface, 4.5);
            contrast(findings, Level.ERROR, theme, Theme.Token.WARNING, surface, 4.5);
            contrast(findings, Level.ERROR, theme, Theme.Token.INFO, surface, 4.5);
            // The ring has to be findable on whatever the focused control sits on.
            contrast(findings, Level.WARNING, theme, Theme.Token.FOCUS_RING, surface, 3);
            contrast(findings, Level.WARNING, theme, Theme.Token.OUTLINE, surface, 1.5);
        }

        // The accent's own label, on all three states of the accent. The pressed state is
        // where this fails: it is the one tone nobody looks at while choosing an accent,
        // and pressing a button is exactly when its label matters.
        for (Theme.Token accent : List.of(Theme.Token.PRIMARY, Theme.Token.PRIMARY_HOVER,
                Theme.Token.PRIMARY_PRESSED)) {
            contrast(findings, Level.ERROR, theme, Theme.Token.ON_PRIMARY, accent, 4.5);
        }
        // The accent as a mark rather than as a fill: an indicator, a selected tab's bar.
        contrast(findings, Level.WARNING, theme, Theme.Token.PRIMARY, Theme.Token.BACKGROUND, 3);
        contrast(findings, Level.WARNING, theme, Theme.Token.PRIMARY, Theme.Token.SURFACE, 3);

        // Exempt from the guideline, reported anyway: a disabled control that cannot be
        // read at all reads as an empty control rather than as an unavailable one.
        contrast(findings, Level.INFO, theme, Theme.Token.DISABLED_TEXT,
                Theme.Token.DISABLED_FILL, 3);

        // Elevation. Two steps, both perceptual: a card you cannot see on the canvas, and a
        // popover you cannot see on the card, are the same defect one layer apart.
        step(findings, theme, Theme.Token.SURFACE, Theme.Token.BACKGROUND, 2);
        step(findings, theme, Theme.Token.SURFACE_RAISED, Theme.Token.SURFACE, 2);

        // Distinctness, not legibility, and invisible to every rule above: a focus ring the
        // same colour as the accent disappears the moment it lands on an accent-filled
        // control (a selected segment, a primary button), which is the one place a keyboard
        // user most needs it. Eight of the palettes this toolkit ships were built that way.
        contrast(findings, Level.WARNING, theme, Theme.Token.FOCUS_RING, Theme.Token.PRIMARY, 1.2);
        // And a hover state that cannot be told from rest is a button that does not answer.
        contrast(findings, Level.INFO, theme, Theme.Token.PRIMARY_HOVER, Theme.Token.PRIMARY, 1.1);

        // The modal veil: the one tone with an alpha, the one rule with no guideline behind
        // it, and the only measurement here that names a single token.
        veil(findings, theme);

        findings.sort((a, b) -> {
            int byLevel = a.level().compareTo(b.level());
            if (byLevel != 0) {
                return byLevel;
            }
            int bySubject = a.subject().compareTo(b.subject());
            if (bySubject != 0) {
                return bySubject;
            }
            if (a.against() == null || b.against() == null) {
                // A tone measured alone sorts before the pairs about the same tone.
                return (a.against() == null ? 0 : 1) - (b.against() == null ? 0 : 1);
            }
            return a.against().compareTo(b.against());
        });
        return List.copyOf(findings);
    }

    /** @return the worst level in {@code findings}, or {@code null} when there are none */
    public static Level worst(List<Finding> findings) {
        Level worst = null;
        for (Finding finding : findings) {
            if (worst == null || finding.level().compareTo(worst) < 0) {
                worst = finding.level();
            }
        }
        return worst;
    }

    /** The three surfaces an ink can land on, in the order they stack. */
    private static List<Theme.Token> surfaces() {
        return List.of(Theme.Token.BACKGROUND, Theme.Token.SURFACE, Theme.Token.SURFACE_RAISED);
    }

    private static void contrast(List<Finding> into, Level level, Theme theme,
                                 Theme.Token subject, Theme.Token against, double bar) {
        double measured = Color.contrastRatio(subject.read(theme), against.read(theme));
        if (measured < bar) {
            into.add(new Finding(level, subject, against, Metric.CONTRAST, Bound.AT_LEAST,
                    measured, bar));
        }
    }

    private static void step(List<Finding> into, Theme theme,
                             Theme.Token subject, Theme.Token against, double bar) {
        double measured = Math.abs(subject.read(theme).lightness() - against.read(theme).lightness());
        if (measured < bar) {
            into.add(new Finding(Level.WARNING, subject, against, Metric.LIGHTNESS_STEP,
                    Bound.AT_LEAST, measured, bar));
        }
    }

    /**
     * The modal veil, held to a window at both ends and measured on nothing but its own
     * alpha.
     *
     * <p><b>A composite is the wrong measurement and looks like the right one.</b> Veiling
     * the canvas and reporting how far it moved answers exactly zero for a palette whose
     * canvas is already black, and such a palette needs the <em>heaviest</em> veil there
     * is, because what its veil dims is the ink on that canvas, not the canvas. No single
     * composite describes this tone across a set of palettes; the alpha is the only property
     * of it that means the same thing on every backdrop.
     *
     * <p><b>A warning at both ends, never an error.</b> The guideline has no bar for a veil:
     * an error here would set a number this project invented beside the ones WCAG wrote, and
     * an author who fixes the errors first would be sent to the wrong row. Nor is either end
     * a note: {@link Level#INFO} is for a bar the guideline deliberately exempts, and it
     * has not exempted the veil, it has never considered it. What both ends are is something
     * the user cannot make out: at one end that the window has stopped answering, at the
     * other everything the dialog is covering.
     */
    private static void veil(List<Finding> into, Theme theme) {
        int steps = Math.round(Theme.Token.SCRIM.read(theme).a() * 255);
        if (steps < VEIL_FAINTEST) {
            into.add(new Finding(Level.WARNING, Theme.Token.SCRIM, null, Metric.ALPHA,
                    Bound.AT_LEAST, percentOf(steps), percentOf(VEIL_FAINTEST)));
        } else if (steps > VEIL_HEAVIEST) {
            into.add(new Finding(Level.WARNING, Theme.Token.SCRIM, null, Metric.ALPHA,
                    Bound.AT_MOST, percentOf(steps), percentOf(VEIL_HEAVIEST)));
        }
    }
}
