package limn.components;

import limn.animation.Easing;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Fonts;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Central design tokens: colors, corner radii, typography and spacing.
 * Components read <em>everything</em> from the theme: zero hardcoded colors
 * in widgets. Ships with a set of built-in palettes (see {@link #builtins()}):
 * {@link #light()}/{@link #dark()}, the project's own {@link #limn()} and
 * {@link #limnLight()}, plus
 * ones drawn from familiar editor palettes (Darkling, Draculite, Nordic,
 * Arch&nbsp;Dark, Onyx&nbsp;Dark, Monoko&nbsp;Pro, Grovebox&nbsp;Dark, Solaris light/dark,
 * GitHub&nbsp;light, High&nbsp;contrast).
 * The active theme is process-wide ({@link #current()}/{@link #setCurrent}).
 * After switching, call {@code root.markNeedsLayout()} so sizes/typography update.
 *
 * <p><b>An application can build its own.</b> {@link #builder(String, boolean)} starts from
 * a working palette and {@link #toBuilder()} starts from this one, so a palette of your own
 * costs only the tones that make it yours; {@link Token} enumerates them so that
 * code treating them alike does not have to name all of them.
 *
 * <p>What a palette carries is <b>colour, plus one metric</b>: {@link #cornerScale}, how
 * round the corners are. Spacing and typography stay process-wide and identical in every
 * palette; see {@link #tokens} for why that boundary falls exactly where it does, and why
 * a radius is the one metric allowed across it.
 */
public final class Theme {

    /**
     * The largest {@link #cornerScale} a palette may ask for. Past this every control in
     * the toolkit is already a pill (a radius beyond half the shorter side is capped when
     * it is drawn), so a higher number would change nothing and only look like it might.
     */
    public static final float MAX_CORNER_SCALE = 4;

    // ------------------------------------------------------------ identity
    /** Human-readable name, the label a theme picker shows. */
    public final String name;
    /** Whether this is a dark palette (lets callers group/pick a sensible default). */
    public final boolean dark;

    // ------------------------------------------------------------- palette
    public final Color background;
    public final Color surface;
    public final Color surfaceRaised;
    public final Color primary;
    public final Color primaryHover;
    public final Color primaryPressed;
    public final Color onPrimary;
    public final Color text;
    public final Color textMuted;
    public final Color outline;
    public final Color focusRing;
    public final Color disabledFill;
    public final Color disabledText;
    /**
     * The veil an in-scene modal dialog paints over everything beneath it, <b>alpha
     * included</b>: the one tone in a palette whose alpha carries meaning. It is painted
     * once, over the whole scene, and the card sits on top of it.
     *
     * <p>Between transparent and opaque, and neither end is usable: at alpha 0 nothing
     * behind the card dims and there is no sign the rest of the window has stopped
     * accepting input, and at alpha 1 the content behind the card is gone rather than
     * blocked. {@code ThemeContrastTest} holds every shipped palette between those.
     *
     * <p><b>A window blocked by a <em>native</em> modal is dimmed by the scene, not by
     * this.</b> That veil lives a layer below, where no palette can be seen at all, so
     * moving this tone does not move it and the two are only ever approximately alike.
     */
    public final Color scrim;

    // ------------------------------------------------------- semantic states
    /** Error / destructive (red). */
    public final Color danger;
    /** Success / valid (green). */
    public final Color success;
    /** Warning / caution (amber). */
    public final Color warning;
    /** Informational / neutral accent (blue). */
    public final Color info;

    // ----------------------------------------------------------------- shape
    /**
     * How round the corners are, as a multiplier on every radius in the size table: 0 for
     * square, 1 for the shipped ramp, above 1 for softer, and far enough above for a pill
     * (a radius past half the shorter side is capped when it is drawn).
     *
     * <p><b>The one metric a palette carries</b>, and it is here rather than in the size
     * table because it is the only one <em>nothing measures from</em>: not one
     * {@code onMeasure}, {@code onLayout}, {@code baselineOffset} or {@code paintOutset} in
     * this toolkit reads a radius, so a change of shape is a repaint exactly like a change
     * of colour. {@code ThemeShapeTest} asserts that, and it is what the whole design rests
     * on; see {@link #tokens}.
     */
    public final float cornerScale;

    // -------------------------------------------------------- preferred type
    /**
     * The typeface family this palette asks for, or {@link Font#DEFAULT_FAMILY}: the value it
     * carries when a palette expresses no preference, and the one every built-in carries.
     *
     * <p><b>A declaration, not a resolution.</b> Nothing here reads a font file or checks that
     * the family exists; a palette is a value and stays one. What acts on it is
     * {@link #applyFontFamily()}, which hands the name to {@link Fonts#setDefaultFamily}, and
     * that is where the fallback lives: a family the backend cannot resolve, including every
     * family on a machine that does not have it installed, renders in the toolkit's embedded
     * Roboto. Naming a font that only exists on the designer's laptop therefore degrades to the
     * shipped face rather than to tofu or to an exception.
     *
     * <p><b>Why this is a name and not a {@link Font} on the size rows.</b> Type is measured
     * from, and {@link #setCurrent} notifies nobody; see {@link #tokens}. Baking the family
     * into the five rows would change every text measurement in every live window with no
     * relayout anywhere. {@code Font.DEFAULT_FAMILY} is resolved late, by the backend, and
     * {@code Fonts} already notifies its listeners so that scenes re-lay-out; routing a
     * palette's type preference through that mechanism is the only shape that does not need a
     * theme-change listener this repository does not have.
     */
    public final String fontFamily;

    // ---------------------------------------------------------- typography
    // Rebased: NOT constant variables (Font.of is a method call), so nothing was ever
    // inlined from them and rebasing costs no compatibility. It buys object identity
    // (assertSame(theme.body, theme.tokens(MEDIUM).body())) and collapses 13 palettes'
    // 13 distinct Font instances into one, so the backend's identity-keyed font memo
    // carries one entry for one logical face instead of 13.
    public final Font body = SizeTokens.MEDIUM.body();
    public final Font label = SizeTokens.MEDIUM.label();
    public final Font title = SizeTokens.MEDIUM.title();

    // ------------------------------------------------------------- spacing
    // JLS 4.12.4 constant variables: a final primitive with a constant initializer is a
    // constant variable even as an INSTANCE field, and JLS 13.1 resolves every reference to it
    // at compile time (verified with javap: theme.spacingLarge compiles to `ldc 20.0f` with the
    // receiver null-checked and popped). Rebasing these onto SizeTokens.MEDIUM would turn every
    // read into a getfield, so they stay literals ON PURPOSE. The static initializer at the foot
    // of this file checks them against SizeTokens.MEDIUM, so drift is impossible rather than
    // unlikely.
    //
    // Spacing is safe to inline where a radius was not: a constant cannot follow cornerScale, so
    // the three radius fields that used to sit here were silently the wrong number on any palette
    // with a shape of its own. They are gone; tokens(step).radiusSmall() and its two siblings
    // are the only way to read a radius, and they follow the scale.
    public final float spacingSmall = 6;
    public final float spacingMedium = 12;
    public final float spacingLarge = 20;

    // ----------------------------------------------------------- animation
    // Default timing for widget state transitions, in seconds. Shared across
    // themes; components feed these into Transition.duration(...).
    /** Default timing curve for state transitions. */
    public final Easing animEasing = Easing.EASE_OUT;
    /** Hover fade in/out (buttons, tabs, combo, rows). */
    public final double animHover = 0.12;
    /** Focus-ring fade in/out. */
    public final double animFocus = 0.14;
    /**
     * Generic show/hide and value fades: the progress bar, a chart's entry animation, and the
     * check and dot marks. Deliberately not the scrollbar, which declares its own four durations
     * because its reveal is asymmetric (it appears fast and leaves slowly, after a hold), and
     * no single token can say that.
     */
    public final double animFade = 0.18;
    /** Selected-tab indicator slide (TabbedPane). */
    public final double animTab = 0.22;
    /** Whole-window show/hide fade (native dialogs, popups, floating windows). */
    public final double animWindow = 0.16;

    /**
     * The five size rows this palette answers with, built once. At {@link #cornerScale} 1
     * this <em>is</em> the process-wide table, so identity (and the backend's font memo)
     * are exactly what they were before a palette could have a shape.
     */
    private final SizeTokens[] rows;

    private Theme(String name, boolean dark, float cornerScale, String fontFamily,
                  Color background, Color surface, Color surfaceRaised,
                  Color primary, Color primaryHover, Color primaryPressed, Color onPrimary,
                  Color text, Color textMuted, Color outline, Color focusRing,
                  Color disabledFill, Color disabledText, Color scrim,
                  Color danger, Color success, Color warning, Color info) {
        this.name = name;
        this.dark = dark;
        this.cornerScale = cornerScale;
        this.fontFamily = fontFamily;
        this.rows = SizeTokens.tableWithCornerScale(cornerScale);
        this.background = background;
        this.surface = surface;
        this.surfaceRaised = surfaceRaised;
        this.primary = primary;
        this.primaryHover = primaryHover;
        this.primaryPressed = primaryPressed;
        this.onPrimary = onPrimary;
        this.text = text;
        this.textMuted = textMuted;
        this.outline = outline;
        this.focusRing = focusRing;
        this.disabledFill = disabledFill;
        this.disabledText = disabledText;
        this.scrim = scrim;
        this.danger = danger;
        this.success = success;
        this.warning = warning;
        this.info = info;
    }

    // Default semantic state colors, by mode: readable on any light/dark surface.
    // (Per-theme fidelity can override, but these generic tones fit every palette.)
    private static final Color DANGER_D = Color.rgb(0xF7768E);
    private static final Color DANGER_L = Color.rgb(0xC62828);
    private static final Color SUCCESS_D = Color.rgb(0x9ECE6A);
    private static final Color SUCCESS_L = Color.rgb(0x2E7D32);
    private static final Color WARNING_D = Color.rgb(0xE0AF68);
    private static final Color WARNING_L = Color.rgb(0xB26A00);
    private static final Color INFO_D = Color.rgb(0x7AA2F7);
    private static final Color INFO_L = Color.rgb(0x1565C0);

    /**
     * The veil of every palette that does not spell out its own. Black rather than tinted,
     * because a veil is meant to say "not this part" and a hue says something else as well;
     * and 140/255 rather than a round 0.55 because a palette is written down in eight bits
     * per channel, so a tone off that grid could not be saved and read back unchanged.
     */
    private static final Color SCRIM_DEFAULT = Color.rgba(0x000000, 140 / 255f);

    /**
     * Builds a theme from its core tones, deriving the hover/pressed accent, the disabled
     * pair and the semantic four, so each palette only spells out what makes it distinct.
     * All args are 0xRRGGBB.
     *
     * <p>Routed through {@link Builder} rather than through the constructor, so the
     * derivations an application reaches for are the same expressions the eleven palettes
     * below were built from. A change to one is a visible change to all of them.
     */
    private static Builder make(String name, boolean dark,
                                int background, int surface, int surfaceRaised,
                                int primary, int onPrimary,
                                int text, int textMuted, int outline, int focusRing) {
        return builder(name, dark)
                .background(Color.rgb(background))
                .surface(Color.rgb(surface))
                .surfaceRaised(Color.rgb(surfaceRaised))
                .primary(Color.rgb(primary))
                .onPrimary(Color.rgb(onPrimary))
                .text(Color.rgb(text))
                .textMuted(Color.rgb(textMuted))
                .outline(Color.rgb(outline))
                .focusRing(Color.rgb(focusRing))
                .deriveAccentStates()
                .deriveDisabled()
                .deriveSemanticStates();
    }

    // ---- the two originals keep their exact, hand-tuned accent ramp ----------
    private static final Theme LIGHT = new Theme("Light", false, 1f, Font.DEFAULT_FAMILY,
            Color.rgb(0xEEF0F4), Color.rgb(0xFFFFFF), Color.rgb(0xEDF0F6),
            Color.rgb(0x2960CE), Color.rgb(0x4373D4), Color.rgb(0x214DA5), Color.WHITE,
            Color.rgb(0x1B2333), Color.rgb(0x5B6675), Color.rgb(0xB0B8C4), Color.rgb(0x173675),
            Color.rgb(0xDDE2EA), Color.rgb(0x9AA3B0), SCRIM_DEFAULT,
            DANGER_L, Color.rgb(0x2E7C32), Color.rgb(0x9F5E00), INFO_L);

    private static final Theme DARK = new Theme("Dark", true, 1f, Font.DEFAULT_FAMILY,
            Color.rgb(0x14181F), Color.rgb(0x1D242F), Color.rgb(0x27303D),
            Color.rgb(0x4C8DFF), Color.rgb(0x6BA1FF), Color.rgb(0x4F7CCA), Color.rgb(0x0B1220),
            Color.rgb(0xE8ECF2), Color.rgb(0x8D97A8), Color.rgb(0x495361), Color.rgb(0xB2CEFF),
            Color.rgb(0x232A35), Color.rgb(0x5C6674), SCRIM_DEFAULT,
            DANGER_D, SUCCESS_D, WARNING_D, INFO_D);

    // ---- the project's own palette -------------------------------------------
    // Every tone was solved for a target contrast ratio against the tone beneath it
    // rather than picked by eye, and ThemeContrastTest asserts each of those targets.
    // Two of them are counter-intuitive enough to state, because both were reached by
    // trying the obvious thing first and watching it fail:
    //
    //  * The accent is light (a vivid violet) and carries DARK label ink, not white.
    //    A violet deep enough for white ink cannot also clear 4.5:1 against a canvas
    //    this dark, so the light-accent/dark-ink pairing is the only one that works
    //    here; do not "fix" onPrimary to white.
    //  * That leaves the accent almost no room to darken, so this palette spells out
    //    its own hover/pressed ramp instead of calling make(). make() darkens pressed
    //    20% toward black, which drops the dark label under 4.5:1; pressing the button
    //    would make its own text harder to read. Moving LIMN onto make() reintroduces
    //    exactly that, and the test will say so.
    private static final Theme LIMN = new Theme("Limn", true, 1f, Font.DEFAULT_FAMILY,
            Color.rgb(0x120D19), Color.rgb(0x362C45), Color.rgb(0x483D59),
            Color.rgb(0xAF7AFF), Color.rgb(0xC8A3FF), Color.rgb(0x9752FF), Color.rgb(0x0C0616),
            Color.rgb(0xF5F1FB), Color.rgb(0xB8ADC8), Color.rgb(0x695783), Color.rgb(0xE0CCFF),
            Color.rgb(0x2D2539), Color.rgb(0x78747F), SCRIM_DEFAULT,
            // danger and info are lifted off the shared dark tones: those two clear 4.5:1
            // on the canvas and on surface, but not on surfaceRaised, which is where the
            // popovers and dialogs that carry error text actually sit.
            Color.rgb(0xF990A3), SUCCESS_D, WARNING_D, Color.rgb(0x8BAEF8));

    // The light companion, solved the same way and mirrored where mirroring works. Three
    // places where the mirror is not literal, all of them load-bearing:
    //
    //  * The ink flips. Here the accent is deep and its label is WHITE, so the state that
    //    binds is hover (which lightens) rather than pressed (which darkens): the exact
    //    opposite of LIMN, and the reason both palettes assert the whole ramp instead of
    //    just the resting accent.
    //  * The focus ring goes DARKER than the accent, not lighter. A ring lighter than a deep
    //    accent cannot also clear 3:1 against a near-white card; that constraint has no
    //    solution, so the ring is nearly black.
    //  * Elevation is expressed in L*, not in contrast ratio. A 1.46:1 step (what LIMN uses
    //    between its canvas and its cards) is unreachable from a light canvas at all: it
    //    would need a luminance above 1.0. The steps here are perceptual and deliberately
    //    smaller than a strict mirror, which would land the card in mid-lavender and stop it
    //    reading as a card.
    //
    // The canvas is pure white, which fixes the two surfaces above it: L* 100 leaves a card
    // no more than L* 95 and a popover no more than L* 89 if each is to lift off the one
    // below by the 5 L* the suite asserts. Tinting them violet rather than grey is what keeps
    // the elevation from reading as dirt.
    private static final Theme LIMN_LIGHT = new Theme("Limn Light", false, 1f, Font.DEFAULT_FAMILY,
            Color.WHITE, Color.rgb(0xF1ECFD), Color.rgb(0xE2D8FA),
            Color.rgb(0x6D00E0), Color.rgb(0x7F16F5), Color.rgb(0x5600B0), Color.WHITE,
            Color.rgb(0x170A2E), Color.rgb(0x4F3A78), Color.rgb(0xAC97D9), Color.rgb(0x26005C),
            Color.rgb(0xF0ECF8), Color.rgb(0x9A8FB0), SCRIM_DEFAULT,
            // Deepened off the shared light tones for the same reason LIMN lifts two of the
            // dark ones: they clear 4.5:1 on the canvas but not on surfaceRaised, which in a
            // light palette is the darkest surface and the one dialogs sit on.
            Color.rgb(0xAB0019), Color.rgb(0x0F6530), Color.rgb(0x875000), Color.rgb(0x135CB0));

    // ---- FlatLaf / IntelliJ-inspired palettes (name,dark, bg,surface,raised,
    //      primary,onPrimary, text,textMuted,outline,focusRing) ----------------
    private static final Theme DARCULA = make("Darkling", true,
            0x2B2B2B, 0x3C3F41, 0x4D5153, 0x8AA1CB, 0x000000, 0xFFFFFF, 0xC4C4C4, 0x6A6C6D, 0xD9E2F0)
            .danger(Color.rgb(0xFAB0BD))
            .success(Color.rgb(0xA5D174))
            .warning(Color.rgb(0xE6BD83))
            .info(Color.rgb(0xAAC4FA)).build();
    private static final Theme DRACULA = make("Draculite", true,
            0x282A36, 0x343746, 0x44475A, 0xBD93F9, 0x000000, 0xF8F8F2, 0xADB6D0, 0x616474, 0xEFE6FE)
            .danger(Color.rgb(0xF99CAD))
            .info(Color.rgb(0x96B6F9)).build();
    private static final Theme NORD = make("Nordic", true,
            0x2E3440, 0x3B4252, 0x475061, 0x88C0D0, 0x000000, 0xFEFFFF, 0xBBC2CE, 0x677080, 0xFFFFFF)
            .danger(Color.rgb(0xFAACBA))
            .success(Color.rgb(0xA1CF6E))
            .warning(Color.rgb(0xE4BB7E))
            .info(Color.rgb(0xA7C1FA)).build();
    private static final Theme ARC_DARK = make("Arch Dark", true,
            0x2B2E37, 0x383C4A, 0x444955, 0x5898E3, 0x000000, 0xF3F5F7, 0xB4B8BC, 0x656A74, 0xBCD6F4)
            .danger(Color.rgb(0xF99EAF))
            .info(Color.rgb(0x99B7F9)).build();
    private static final Theme ONE_DARK = make("Onyx Dark", true,
            0x282C34, 0x353943, 0x424854, 0x61AFEF, 0x000000, 0xEEF0F2, 0xB2B6BC, 0x616670, 0xDEEFFC)
            .danger(Color.rgb(0xF99BAC))
            .info(Color.rgb(0x96B6F9)).build();
    private static final Theme MONOKAI_PRO = make("Monoko Pro", true,
            0x2D2A2E, 0x39363A, 0x464346, 0xFFD866, 0x2D2A2E, 0xFCFCFA, 0xB0B0B0, 0x656365, 0xB59948)
            .danger(Color.rgb(0xF992A5))
            .info(Color.rgb(0x8DB0F8)).build();
    private static final Theme GRUVBOX_DARK = make("Grovebox Dark", true,
            0x282828, 0x363433, 0x44403E, 0xFE8019, 0x000000, 0xF2E8CD, 0xB5AAA0, 0x66605C, 0xFDE3A5)
            .danger(Color.rgb(0xF88CA0))
            .info(Color.rgb(0x88ABF8)).build();
    private static final Theme SOLARIZED_LIGHT = make("Solaris Light", false,
            0xFDF6E3, 0xE9E3D1, 0xDAD3C0, 0x1D6AA1, 0xFFFFFF, 0x293336, 0x555D5D, 0xA7A193, 0x113B5B)
            .danger(Color.rgb(0xB02424))
            .success(Color.rgb(0x26682A))
            .warning(Color.rgb(0x854F00))
            .info(Color.rgb(0x135BAC)).build();
    private static final Theme SOLARIZED_DARK = make("Solaris Dark", true,
            0x002B36, 0x0C3A46, 0x0D4A5A, 0x3D98D7, 0x000000, 0xE5E9E9, 0xA5B2B7, 0x366976, 0xA5D8D3)
            .danger(Color.rgb(0xF992A5))
            .info(Color.rgb(0x8DB0F8)).build();
    private static final Theme GITHUB_LIGHT = make("Octo Light", false,
            0xFFFFFF, 0xECEEF0, 0xDCE0E4, 0x0963CE, 0xFFFFFF, 0x1F2328, 0x5D646D, 0xA5AAB0, 0x053876)
            .danger(Color.rgb(0xBE2626))
            .success(Color.rgb(0x2A712D))
            .warning(Color.rgb(0x905500))
            .info(Color.rgb(0x1462BB)).build();
    // The heavier veil is not decoration. This canvas is already black, so darkening it
    // does nothing at all; what the scrim actually dims here is the white text on it, and
    // the shared 55% leaves that text bright enough to keep competing with the card.
    private static final Theme HIGH_CONTRAST = make("High Contrast", true,
            0x000000, 0x121212, 0x1F1F1F, 0xFFD500, 0x000000, 0xFFFFFF, 0xD0D0D0, 0x8C8C8C, 0x21A5D2)
            .scrim(Color.rgba(0x000000, 204 / 255f)).build();

    private static final List<Theme> BUILTINS = List.of(
            LIGHT, DARK, LIMN, LIMN_LIGHT, DARCULA, DRACULA, NORD, ARC_DARK, ONE_DARK, MONOKAI_PRO,
            GRUVBOX_DARK, SOLARIZED_LIGHT, SOLARIZED_DARK, GITHUB_LIGHT, HIGH_CONTRAST);

    private static volatile Theme current = DARK;

    /** The built-in light palette. */
    public static Theme light() {
        return LIGHT;
    }

    /** The built-in dark palette. */
    public static Theme dark() {
        return DARK;
    }

    /**
     * The project's own palette: a deep violet canvas with a vivid violet accent, tuned so
     * that body text, muted text, the accent's own label and the focus ring each clear
     * WCAG&nbsp;AA on the surface they land on.
     */
    public static Theme limn() {
        return LIMN;
    }

    /** The light companion to {@link #limn()}, held to the same contrast targets. */
    public static Theme limnLight() {
        return LIMN_LIGHT;
    }

    /**
     * The name a picker shows, in the UI language; {@link #name} stays the
     * identifier. Only the descriptive palettes translate ({@code Light},
     * {@code Dark}, {@code High Contrast}); the rest are the names of the palettes
     * they came from and read the same everywhere.
     */
    public limn.i18n.I18nString displayName() {
        return ThemeStrings.of(this);
    }

    /** Every palette that ships with the toolkit, in presentation order. */
    public static List<Theme> builtins() {
        return BUILTINS;
    }

    /** @return the process-wide active theme */
    public static Theme current() {
        return current;
    }

    // ---------------------------------------------------------- size tokens

    /**
     * Metric tokens for a size step. The returned record (and the {@link Font}s inside it)
     * are stable for the life of this palette, so {@code ==} holds and the backend's
     * identity-keyed font memo keeps hitting. Never build tokens per call, and never call
     * {@code Font.withSize} to derive a step's font.
     *
     * <p>Every metric here is <b>palette-independent except the corner radii</b>, which
     * follow {@link #cornerScale}. At the default scale this returns the process-wide row
     * itself, so a palette without a shape of its own is indistinguishable from what shipped
     * before shape existed.
     *
     * <p><b>Why the radii are allowed out and nothing else is.</b> {@link #setCurrent} only
     * assigns a volatile field (there is no theme-change listener anywhere in this
     * repository), so a palette that could change a metric something <em>measures</em> from
     * would change every measurement in every window with zero relayout. A radius is the one
     * metric nothing measures from: no {@code onMeasure}, {@code onLayout},
     * {@code baselineOffset} or {@code paintOutset} in this toolkit reads one, which
     * {@code ThemeShapeTest} asserts by measuring a tree at two shapes and demanding the same
     * numbers. Break that and shape stops being safe, not just the test.
     *
     * <p>Two further traps stand in the way of any metric that <em>is</em> measured from. The
     * six float token fields above are inlined at their call sites (JLS 13.1), so a
     * per-palette value cannot reach a field read at all, which is exactly why the three
     * radius fields are deprecated rather than made to follow the scale. And the nine
     * token-backed fields read the static MEDIUM row, so they would be invisible to every
     * unmigrated call site. If per-palette <em>type or spacing</em> is ever wanted, both
     * prerequisites are required: route {@code setCurrent} through the same weak per-scene
     * registry as {@code Fonts}/{@code ControlSize}, and initialize those fields from
     * {@code this.tokens(MEDIUM)} in the constructor.
     */
    public final SizeTokens tokens(limn.scene.ControlSize size) {
        return rows[Objects.requireNonNull(size, "size").ordinal()];
    }

    /** Tokens for the step resolved on {@code widget}, the one line components call. */
    public final SizeTokens tokensFor(limn.scene.Widget widget) {
        return tokens(widget.controlSize());
    }

    /**
     * Switches the process-wide palette. Nothing is notified: call
     * {@code root.markNeedsLayout()} on each live scene afterwards, since type and
     * spacing can differ between palettes.
     */
    public static void setCurrent(Theme theme) {
        current = Objects.requireNonNull(theme, "theme");
    }

    /**
     * Asks {@link Fonts} to resolve {@link Font#DEFAULT_FAMILY} to this palette's
     * {@link #fontFamily}: the act that makes a palette's type preference visible.
     *
     * <p>Separate from {@link #setCurrent} on purpose, and the separation is the contract:
     * switching a palette is a repaint, while switching a font is a relayout <em>and</em>, the
     * first time a face is named, a font file read inside the next frame. {@code Fonts} says so
     * on {@link Fonts#setDefaultFamily} and the rule it states applies here unchanged; call
     * this from a settings screen or a theme picker, where one long frame reads as the switch
     * happening, and never from an animation or a drag. Folding it into {@code setCurrent}
     * would smuggle that read into every palette change, including the ones a colour well makes
     * on every frame of a drag.
     *
     * <p>A palette that expresses no preference carries {@code Font.DEFAULT_FAMILY}, so calling
     * this for one restores the toolkit's own face rather than leaving the previous palette's
     * choice in place. Both directions are therefore safe to call unconditionally.
     *
     * <p>UI thread.
     */
    public void applyFontFamily() {
        Fonts.setDefaultFamily(fontFamily);
    }

    // ------------------------------------------------------- building a palette

    /**
     * A palette of this application's own, seeded from the built-in {@link #light()} or
     * {@link #dark()} so that every tone is already a working one and only what makes the
     * palette distinct has to be spelled out.
     *
     * <pre>{@code
     * Theme mine = Theme.builder("Ocean", true)
     *         .background(Color.rgb(0x0B1A24))
     *         .surface(Color.rgb(0x11242F))
     *         .surfaceRaised(Color.rgb(0x1A3340))
     *         .primary(Color.rgb(0x4FD1C5))
     *         .onPrimary(Color.rgb(0x04141A))
     *         .deriveAccentStates()
     *         .deriveDisabled()
     *         .build();
     * Theme.setCurrent(mine);
     * }</pre>
     *
     * @param name the palette's identifier and its fallback display text; see
     *             {@link Builder#name}, which explains why a name shared with a built-in
     *             is a name to avoid
     * @param dark whether the palette is dark, which decides the seed <em>and</em> which
     *             tones the {@code derive} methods produce
     */
    public static Builder builder(String name, boolean dark) {
        return new Builder(dark ? DARK : LIGHT).name(name).dark(dark);
    }

    /**
     * This palette's tones, in a builder: the form to start from when an application
     * wants a built-in with two things changed rather than a palette of its own.
     *
     * <p>The theme is unaffected: a {@code Theme} is immutable and the builder holds a
     * copy.
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Every colour a palette is made of, as values, so that code which has to
     * treat every tone alike (a serializer, an editor, a contrast report) enumerates them
     * instead of naming them all and getting all but one right.
     *
     * <p>The order is the one a palette is reasoned about in: the surfaces it is built
     * on, then the accent, then the ink, then the states, then the semantic four. It is
     * stable, and a serialized palette's key order follows it.
     */
    public enum Token {
        /** {@link Theme#background}: the canvas a window clears to. */
        BACKGROUND("background", t -> t.background, b -> b.background, Builder::background),
        /** {@link Theme#surface}: a card, a field, a panel on the canvas. */
        SURFACE("surface", t -> t.surface, b -> b.surface, Builder::surface),
        /** {@link Theme#surfaceRaised}: a popover, a menu, a dialog above a surface. */
        SURFACE_RAISED("surfaceRaised", t -> t.surfaceRaised, b -> b.surfaceRaised,
                Builder::surfaceRaised),
        /** {@link Theme#primary}: the accent, at rest. */
        PRIMARY("primary", t -> t.primary, b -> b.primary, Builder::primary),
        /** {@link Theme#primaryHover}: the accent under the pointer. */
        PRIMARY_HOVER("primaryHover", t -> t.primaryHover, b -> b.primaryHover,
                Builder::primaryHover),
        /** {@link Theme#primaryPressed}: the accent while held. */
        PRIMARY_PRESSED("primaryPressed", t -> t.primaryPressed, b -> b.primaryPressed,
                Builder::primaryPressed),
        /** {@link Theme#onPrimary}: the label on an accent fill. */
        ON_PRIMARY("onPrimary", t -> t.onPrimary, b -> b.onPrimary, Builder::onPrimary),
        /** {@link Theme#text}: body ink. */
        TEXT("text", t -> t.text, b -> b.text, Builder::text),
        /** {@link Theme#textMuted}: secondary ink; still body text, still 4.5:1. */
        TEXT_MUTED("textMuted", t -> t.textMuted, b -> b.textMuted, Builder::textMuted),
        /** {@link Theme#outline}: borders and separators. */
        OUTLINE("outline", t -> t.outline, b -> b.outline, Builder::outline),
        /** {@link Theme#focusRing}: the ring around the focused control. */
        FOCUS_RING("focusRing", t -> t.focusRing, b -> b.focusRing, Builder::focusRing),
        /** {@link Theme#disabledFill}: the fill of a control that cannot be used. */
        DISABLED_FILL("disabledFill", t -> t.disabledFill, b -> b.disabledFill,
                Builder::disabledFill),
        /** {@link Theme#disabledText}: its label. */
        DISABLED_TEXT("disabledText", t -> t.disabledText, b -> b.disabledText,
                Builder::disabledText),
        /** {@link Theme#scrim}: the veil under a modal, and the one tone with an alpha. */
        SCRIM("scrim", t -> t.scrim, b -> b.scrim, Builder::scrim),
        /** {@link Theme#danger}: error and destructive. */
        DANGER("danger", t -> t.danger, b -> b.danger, Builder::danger),
        /** {@link Theme#success}: success and valid. */
        SUCCESS("success", t -> t.success, b -> b.success, Builder::success),
        /** {@link Theme#warning}: warning and caution. */
        WARNING("warning", t -> t.warning, b -> b.warning, Builder::warning),
        /** {@link Theme#info}: informational. */
        INFO("info", t -> t.info, b -> b.info, Builder::info);

        private final String key;
        private final Function<Theme, Color> reader;
        private final Function<Builder, Color> pending;
        private final BiFunction<Builder, Color, Builder> writer;

        Token(String key, Function<Theme, Color> reader, Function<Builder, Color> pending,
              BiFunction<Builder, Color, Builder> writer) {
            this.key = key;
            this.reader = reader;
            this.pending = pending;
            this.writer = writer;
        }

        /**
         * The stable identifier, spelled exactly like the {@link Theme} field it names:
         * what a serialized palette writes and what an editor keys its rows by. Unlike
         * {@link #name()}, it is API: renaming it would orphan every saved palette.
         */
        public String key() {
            return key;
        }

        /** This tone, read off {@code theme}. */
        public Color read(Theme theme) {
            return reader.apply(Objects.requireNonNull(theme, "theme"));
        }

        /** This tone, as {@code builder} currently holds it, before any {@code build()}. */
        public Color read(Builder builder) {
            return pending.apply(Objects.requireNonNull(builder, "builder"));
        }

        /** Sets this tone on {@code builder}, and returns that builder for chaining. */
        public Builder write(Builder builder, Color color) {
            return writer.apply(Objects.requireNonNull(builder, "builder"), color);
        }

        /** @return the token whose {@link #key()} is {@code key}, or {@code null} if none is */
        public static Token byKey(String key) {
            for (Token token : values()) {
                if (token.key.equals(key)) {
                    return token;
                }
            }
            return null;
        }
    }

    /**
     * Collects the colours of a palette and builds one. Not thread-safe and not
     * meant to be: build the palette, then hand the {@link Theme} around.
     *
     * <p><b>Colours only.</b> Spacing, radii and typography are not per-palette and cannot
     * be made so from here; they come from the process-wide {@link SizeTokens} table, and
     * {@link Theme#tokens} explains at length why. A palette changes what the toolkit looks
     * like, never how big it is.
     *
     * <p>The three {@code derive} methods are the same expressions the built-in palettes
     * were built from, and each reads the tones set <em>before</em> it: set the accent
     * first, then {@link #deriveAccentStates()}. Call them in any order relative to each
     * other; call them again after changing an input, or the derived tone stays where the
     * previous input put it.
     *
     * <p><b>Every tone is snapped to eight bits per channel</b> on the way in, so what
     * {@link #get} answers is what {@link #build()} produces. That is the precision a hex
     * value, a colour field and a monitor all share, and it is what makes a palette
     * exactly representable: a tone that could not be written down would come back
     * different from a file it had just been saved to, and two palettes that render
     * identically would compare unequal. A colour handed in at higher precision (anything
     * interpolated, which is most derived tones) is rounded, by at most 1/255.
     */
    public static final class Builder {

        private String name;
        private boolean dark;
        private float cornerScale;
        private String fontFamily;
        private Color background;
        private Color surface;
        private Color surfaceRaised;
        private Color primary;
        private Color primaryHover;
        private Color primaryPressed;
        private Color onPrimary;
        private Color text;
        private Color textMuted;
        private Color outline;
        private Color focusRing;
        private Color disabledFill;
        private Color disabledText;
        private Color scrim;
        private Color danger;
        private Color success;
        private Color warning;
        private Color info;

        private Builder(Theme base) {
            this.name = base.name;
            this.dark = base.dark;
            this.cornerScale = base.cornerScale;
            this.fontFamily = base.fontFamily;
            for (Token token : Token.values()) {
                token.write(this, token.read(base));
            }
        }

        /**
         * The palette's identifier: {@link Theme#name}, which is also what
         * {@link Theme#displayName()} falls back to.
         *
         * <p><b>Avoid a built-in's name.</b> Display names are looked up by this string,
         * so a palette called {@code "Nordic"} is shown under the built-in Nordic's
         * translation in every language that has one. Anything else stands as written,
         * since nothing could translate it.
         */
        public Builder name(String value) {
            this.name = Objects.requireNonNull(value, "name");
            return this;
        }

        /**
         * Whether the palette is dark. It is not decoration: it decides which tones
         * {@link #deriveAccentStates()} and {@link #deriveSemanticStates()} produce, and
         * applications group and default on it. Changing it does <em>not</em> re-derive
         * anything already derived.
         */
        public Builder dark(boolean value) {
            this.dark = value;
            return this;
        }

        /**
         * How round the corners are; see {@link Theme#cornerScale}. Clamped to
         * {@code [0, MAX_CORNER_SCALE]}; 1 is the shipped ramp.
         *
         * @throws IllegalArgumentException if {@code value} is not a finite number, which is
         *                                  what a text field hands over when it is empty
         */
        public Builder cornerScale(float value) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("cornerScale must be finite, was " + value);
            }
            this.cornerScale = Math.max(0f, Math.min(MAX_CORNER_SCALE, value));
            return this;
        }

        /** The shape, as set so far. */
        public float cornerScale() {
            return cornerScale;
        }

        /**
         * The typeface family this palette asks for; see {@link Theme#fontFamily}.
         * {@code null} or blank means no preference and stores {@link Font#DEFAULT_FAMILY},
         * which is what the built-ins carry.
         *
         * <p>The name is <b>not</b> validated against the installed faces, and deliberately: a
         * palette is a value that outlives the machine it was authored on, so a family this
         * machine happens not to have is a legitimate thing to write down. It falls back to the
         * embedded face when it is applied (see {@link Theme#applyFontFamily()}) rather than
         * being rejected here, which would make a palette unloadable on the wrong laptop.
         */
        public Builder fontFamily(String value) {
            this.fontFamily = value == null || value.isBlank() ? Font.DEFAULT_FAMILY : value;
            return this;
        }

        /** The typeface family, as set so far. */
        public String fontFamily() {
            return fontFamily;
        }

        /** @see Theme#background */
        public Builder background(Color value) {
            this.background = snap(Objects.requireNonNull(value, "background"));
            return this;
        }

        /** @see Theme#surface */
        public Builder surface(Color value) {
            this.surface = snap(Objects.requireNonNull(value, "surface"));
            return this;
        }

        /** @see Theme#surfaceRaised */
        public Builder surfaceRaised(Color value) {
            this.surfaceRaised = snap(Objects.requireNonNull(value, "surfaceRaised"));
            return this;
        }

        /** The accent at rest; {@link #deriveAccentStates()} turns it into the ramp. */
        public Builder primary(Color value) {
            this.primary = snap(Objects.requireNonNull(value, "primary"));
            return this;
        }

        /** @see Theme#primaryHover */
        public Builder primaryHover(Color value) {
            this.primaryHover = snap(Objects.requireNonNull(value, "primaryHover"));
            return this;
        }

        /** @see Theme#primaryPressed */
        public Builder primaryPressed(Color value) {
            this.primaryPressed = snap(Objects.requireNonNull(value, "primaryPressed"));
            return this;
        }

        /** @see Theme#onPrimary */
        public Builder onPrimary(Color value) {
            this.onPrimary = snap(Objects.requireNonNull(value, "onPrimary"));
            return this;
        }

        /** @see Theme#text */
        public Builder text(Color value) {
            this.text = snap(Objects.requireNonNull(value, "text"));
            return this;
        }

        /** @see Theme#textMuted */
        public Builder textMuted(Color value) {
            this.textMuted = snap(Objects.requireNonNull(value, "textMuted"));
            return this;
        }

        /** @see Theme#outline */
        public Builder outline(Color value) {
            this.outline = snap(Objects.requireNonNull(value, "outline"));
            return this;
        }

        /** @see Theme#focusRing */
        public Builder focusRing(Color value) {
            this.focusRing = snap(Objects.requireNonNull(value, "focusRing"));
            return this;
        }

        /** @see Theme#disabledFill */
        public Builder disabledFill(Color value) {
            this.disabledFill = snap(Objects.requireNonNull(value, "disabledFill"));
            return this;
        }

        /** @see Theme#disabledText */
        public Builder disabledText(Color value) {
            this.disabledText = snap(Objects.requireNonNull(value, "disabledText"));
            return this;
        }

        /**
         * The modal veil; see {@link Theme#scrim}. The <b>only</b> tone whose alpha is
         * read rather than assumed opaque, and passing an opaque colour here is the way to
         * make a modal hide what it blocks instead of dimming it.
         */
        public Builder scrim(Color value) {
            this.scrim = snap(Objects.requireNonNull(value, "scrim"));
            return this;
        }

        /** @see Theme#danger */
        public Builder danger(Color value) {
            this.danger = snap(Objects.requireNonNull(value, "danger"));
            return this;
        }

        /** @see Theme#success */
        public Builder success(Color value) {
            this.success = snap(Objects.requireNonNull(value, "success"));
            return this;
        }

        /** @see Theme#warning */
        public Builder warning(Color value) {
            this.warning = snap(Objects.requireNonNull(value, "warning"));
            return this;
        }

        /** @see Theme#info */
        public Builder info(Color value) {
            this.info = snap(Objects.requireNonNull(value, "info"));
            return this;
        }

        /** This tone, as the builder currently holds it: the read half of {@link #set}. */
        public Color get(Token token) {
            return Objects.requireNonNull(token, "token").read(this);
        }

        /** Sets any tone by token, the form a generic editor or parser uses. */
        public Builder set(Token token, Color value) {
            return Objects.requireNonNull(token, "token").write(this, value);
        }

        /** The palette's name, as set so far. */
        public String name() {
            return name;
        }

        /** Whether the palette is dark, as set so far. */
        public boolean isDark() {
            return dark;
        }

        /**
         * Hover and pressed, from the accent: lightened toward white by 16% dark / 12%
         * light, and darkened toward black by 20%.
         *
         * <p>The darkening is the one to check by eye rather than trust. A light accent
         * carrying dark label ink has almost no room to darken: 20% can drop the label
         * below 4.5:1, so pressing the button makes its own text harder to read. That is
         * why the toolkit's own palette spells its ramp out instead of calling this.
         */
        public Builder deriveAccentStates() {
            primaryHover(primary.lerp(Color.WHITE, dark ? 0.16f : 0.12f));
            return primaryPressed(primary.lerp(Color.BLACK, 0.20f));
        }

        /**
         * The disabled pair, by fading the raised surface and the body ink halfway back
         * into the canvas, a control that reads as absent rather than as a second colour.
         * Reads {@code surfaceRaised}, {@code text} and {@code background}.
         */
        public Builder deriveDisabled() {
            disabledFill(surfaceRaised.lerp(background, 0.5f));
            return disabledText(text.lerp(background, 0.55f));
        }

        /**
         * The semantic four, from the generic tones that read on any surface of this
         * mode. Reads only {@link #dark}.
         *
         * <p>They are deliberately not derived from the accent: red means error in every
         * palette, and a palette whose danger colour follows its brand is a palette where
         * an error looks like a link. Override individually where a tone has to clear
         * 4.5:1 on a surface these generic ones do not.
         */
        public Builder deriveSemanticStates() {
            danger(dark ? DANGER_D : DANGER_L);
            success(dark ? SUCCESS_D : SUCCESS_L);
            warning(dark ? WARNING_D : WARNING_L);
            return info(dark ? INFO_D : INFO_L);
        }

        /**
         * A colour at the precision a palette can be written down in. Rounding per channel
         * rather than truncating keeps the snap symmetric, so a tone already on the grid
         * (every hand-authored one) comes back untouched.
         */
        private static Color snap(Color color) {
            return new Color(round(color.r()), round(color.g()), round(color.b()),
                    round(color.a()));
        }

        private static float round(float channel) {
            return Math.round(channel * 255f) / 255f;
        }

        /**
         * The palette. Every field is non-null by construction (the builder is seeded from
         * a built-in and every setter null-checks), so this cannot fail. The builder stays
         * usable afterwards and builds a fresh palette each call.
         */
        public Theme build() {
            return new Theme(name, dark, cornerScale, fontFamily, background, surface, surfaceRaised,
                    primary, primaryHover, primaryPressed, onPrimary,
                    text, textMuted, outline, focusRing, disabledFill, disabledText, scrim,
                    danger, success, warning, info);
        }
    }

    /**
     * Two palettes are equal when they carry the same name, mode, shape, typeface preference
     * and colours, which is everything a palette is, and everything {@code ThemeFormat} writes.
     * The metrics do not appear here because they are process-wide: only the corner scale and
     * the family name are a palette's to choose, and both are compared.
     *
     * <p>Here so that an editor can ask "has this been changed" and a parser can be held
     * to a round trip. The toolkit itself still compares the active palette by identity.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Theme that) || dark != that.dark || !name.equals(that.name)
                || Float.compare(cornerScale, that.cornerScale) != 0
                || !fontFamily.equals(that.fontFamily)) {
            return false;
        }
        for (Token token : Token.values()) {
            if (!token.read(this).equals(token.read(that))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(name, dark, cornerScale, fontFamily);
        for (Token token : Token.values()) {
            result = 31 * result + token.read(this).hashCode();
        }
        return result;
    }

    /** The palette's name. A theme picker shows {@link #displayName()}, not this. */
    @Override
    public String toString() {
        return name;
    }

    /**
     * How a hover tooltip should look, in the current palette at {@code step}. The toolkit
     * draws tooltips but has no {@link Theme}, so this is installed on {@code Scene} as a
     * supplier and resolved at paint, which is what makes a tooltip follow both a theme
     * switch and a size change with nothing subscribed.
     *
     * <p>The row comes from the <b>palette</b> rather than from the static table, because a
     * tooltip is a surface like any other: reading the table instead leaves it as the one
     * thing on screen keeping square corners while every panel around it rounds.
     */
    static limn.scene.TooltipStyle tooltipStyle(limn.scene.ControlSize step) {
        Theme t = current();
        SizeTokens s = t.tokens(step);
        return new limn.scene.TooltipStyle(t.surfaceRaised, t.outline, t.text,
                s.label(), s.radiusSmall(), s.tooltipPadH(), s.tooltipPadV());
    }

    static {
        // The three spacing tokens keep their literal initializers (see the note on inlining
        // above). This is what makes that safe: a divergence between a literal here and the
        // MEDIUM row is a hard failure at class init, not a silent 2pt drift.
        SizeTokens m = SizeTokens.MEDIUM;
        Theme probe = DARK;
        if (probe.spacingSmall != m.spacingSmall()
                || probe.spacingMedium != m.spacingMedium()
                || probe.spacingLarge != m.spacingLarge()) {
            throw new AssertionError(
                    "Theme's inlined MEDIUM literals disagree with SizeTokens.MEDIUM");
        }
        // The toolkit renders hover tooltips but has no Theme; give it the current theme's
        // panel appearance at the step resolved on the hovered anchor (resolved at paint, so
        // it follows both theme switches and size changes).
        limn.scene.Scene.installTooltipStyle(Theme::tooltipStyle);
    }
}

