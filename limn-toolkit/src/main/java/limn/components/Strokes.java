package limn.components;

/**
 * Quantities that are <b>pixel-locked</b>: identical at every
 * {@link limn.scene.ControlSize} step.
 *
 * <p>A quantity belongs here if it expresses a weight, a rasterization correction
 * derived from a weight, a device fact, or a human motor constant. It belongs in
 * {@link SizeTokens} instead if it expresses an extent or an optical gap.
 *
 * <p>The separation is structural, not editorial: these are {@code public static final
 * float} and {@code SizeTokens} has no row for any of them, so there is nowhere to put
 * the five columns a scaled value would need. <b>No member of this class may be indexed
 * by a {@code ControlSize}.</b>
 *
 * <p><b>Trap when migrating a literal.</b> The four text-cluster components draw one
 * rounded rect whose stroke width is the expression {@code BORDER + (FOCUS_RING -
 * BORDER) * focus}, so the border thickens continuously as the focus transition runs.
 * Writing {@code focus > 0 ? FOCUS_RING : BORDER} instead deletes that animation.
 * A test that records stroke widths over these components must paint at a settled
 * transition ({@code focus} exactly 0, then exactly 1) or it records frame-dependent
 * fractional widths.
 */
public final class Strokes {

    private Strokes() {
    }

    // ------------------------------------------------------------------ borders

    /**
     * Every border and hairline separator. A toolbar and the buttons inside it share one
     * border weight, and scaling either one breaks that read.
     */
    public static final float BORDER = 1;

    /** Alias: the same weight, read as a separator rather than an outline. */
    public static final float HAIRLINE = BORDER;

    /**
     * Half of {@link #BORDER}. Lands a 1&nbsp;pt stroke on a whole device pixel instead
     * of straddling two and going grey: {@code drawRoundRect(0.5f, 0.5f, w - 1, h - 1,
     * …)}. A function of the stroke, not of the size: at XLARGE it is still 0.5.
     */
    public static final float HALF_PIXEL_INSET = 0.5f;

    // -------------------------------------------------------------- focus rings

    /**
     * Visible-focus indicator weight. WCAG-style focus indicators are specified in
     * absolute thickness, and a form mixing size steps must afford focus uniformly.
     */
    public static final float FOCUS_RING = 2;

    /** The thin focus weight, for indicator-scale controls. Two named weights, not an accident. */
    public static final float FOCUS_RING_THIN = 1.5f;

    /** A 2&nbsp;pt stroke centred 2&nbsp;pt out leaves exactly 1&nbsp;pt clear. */
    public static final float FOCUS_GAP_BUTTON = 2;

    /**
     * Focus gap for the indicator-scale toggles, which are in declared lockstep and must
     * not differ here. A gap must be at least as wide as the thinner stroke it separates:
     * between an {@link #INDICATOR_BORDER} and a {@link #FOCUS_RING_THIN}, both 1.5, a
     * 1&nbsp;pt gap reads as a seam rather than as clearance.
     */
    public static final float FOCUS_GAP_INDICATOR = 1.5f;

    /**
     * Slider's focus gap. Accepted consequence: Slider's height derives from
     * {@code 2 × (knobHover + gap + BORDER)}, so pinning the gap makes the small steps
     * proportionally taller than the knob ramp predicts. The ring needs absolute room.
     */
    public static final float FOCUS_GAP_SLIDER = 3;

    /** {@code tabHoverInset > FOCUS_GAP_TAB} must hold at every step. */
    public static final float FOCUS_GAP_TAB = 2;

    /**
     * The declared paint reach of a ring drawn at −2 with a 2&nbsp;pt centred stroke: the
     * ring spans −3…−1. Locked because the ring is. Consumed by {@code paintOutset()}
     * overrides, whose whole purpose is to change which pixels are repainted.
     */
    public static final float FOCUS_RING_OUTSET = 3;

    // --------------------------------------------------------- marks and glyphs

    /** Checkbox box, RadioButton ring and Slider knob outline: 1.5&nbsp;pt at every step. */
    public static final float INDICATOR_BORDER = 1.5f;

    /**
     * The check-mark pen. The mark's extent scales with the indicator; the pen does not.
     * At XSMALL the mark still keeps 1.83&nbsp;pt of clearance from the box border's inner
     * ink edge (1.75 at MEDIUM), so one pen is optically sound at every step.
     */
    public static final float CHECK_MARK = 2;

    /** Menu tick. Heavier than {@link #ARROW_PEN} by convention: a tick is not an arrow. */
    public static final float MENU_CHECK_PEN = 1.8f;

    /**
     * Icon line weight: the glyph grows, the pen does not. Sound only because every
     * locked-pen glyph is floored at its MEDIUM extent for XSMALL and SMALL, which keeps
     * {@code pen ÷ glyphMinExtent ≤ 0.45} at all five steps.
     */
    public static final float ARROW_PEN = 1.6f;

    /**
     * The colour picker's cursor ring, drawn twice one point apart in black and white so it
     * stays legible on whatever colour it lands on. Not {@link #FOCUS_RING}: it happens to be
     * the same weight today, and it means something else: a retune of the focus ring must not
     * silently move a marker that is not one.
     */
    public static final float PICKER_CURSOR = 2;

    /**
     * The tab strip's sliding indicator. Locked rather than tabled: 2.5&nbsp;pt reads
     * correctly across the strip's 2.1× range, and locking keeps the indicator inside
     * {@code height()} for free ({@code y = height() − thickness}), so no
     * {@code paintOutset} question arises. Bottom-anchored, so the centred-stroke parity
     * rule does not apply.
     */
    public static final float TAB_INDICATOR = 2.5f;

    // ----------------------------------------------------------- text machinery

    /** The text caret. */
    public static final float CARET = 1;

    /**
     * The ±1&nbsp;pt overshoot that makes a caret bracket its glyphs and a selection band
     * clear the tallest ascender. An optical correction (the {@code metrics.height()}
     * term it is added to already scales) and only meaningful from the <em>ink</em> box.
     */
    public static final float INK_BLEED = 1;

    /**
     * Antialiasing fringe allowance for a text clip. An absolute device effect: scaling it
     * would let 2&nbsp;pt of the leading pad be overwritten at XLARGE.
     */
    public static final float AA_BLEED = 2;

    /**
     * Caret damage margin: antialiasing plus hairline snap. Growing it at large steps
     * inflates per-blink damage and defeats partial rendering.
     */
    public static final float DAMAGE_MARGIN = 2;

    /**
     * Slack so a 1&nbsp;pt caret clears a hard-edged clip. TextField's two uses of this
     * must stay identical or the caret oscillates per keystroke.
     */
    public static final float CLIP_CLEARANCE = 1;

    /**
     * Visibility floor so a zero-width selection on an empty line still shows
     * ({@code Math.max(2, x1 - x0)}). The minimum that survives antialiasing at any size.
     */
    public static final float MIN_SELECTION_SLIVER = 2;

    /** IME preedit underline, resting weight. Platform IMEs draw a hairline regardless of text size. */
    public static final float IME_UNDERLINE = 1;

    /**
     * IME preedit underline for the converting block. The 1-vs-2 <em>contrast</em> carries
     * the meaning; a scaled 0.5-vs-1 pair at XSMALL would erase it.
     */
    public static final float IME_UNDERLINE_ACTIVE = 2;

    // ------------------------------------------------------------- menu / popup

    /** Row clip inset whose value <em>is</em> the border width. */
    public static final float ROW_CLIP = 1;

    /**
     * The gap that stops two highlighted rows fusing. Scaled to 3&nbsp;pt at XLARGE it
     * would read as a deliberate stripe.
     */
    public static final float ROW_GUTTER = 1;

    /**
     * Exactly twice {@link #BORDER}: it exists to hide the seam between two columns'
     * borders. 4&nbsp;pt at XLARGE looks glued; 1&nbsp;pt at XSMALL reveals a gap.
     */
    public static final float SUBMENU_OVERLAP = 2;

    /** Spinner hover fill inset. Its value <em>is</em> the divider width, which the fill must not cover. */
    public static final float SPINNER_HOVER_INSET = 1;

    /**
     * Scrollbar chrome breathing room, and what keeps {@code thickness() = WIDE + 4}
     * trivial. {@code ScrollBar} reads it for both its track geometry and its drag
     * arithmetic, which is what keeps the two in lockstep: a bar that painted with one
     * margin and dragged with another would put the thumb a pixel away from the pointer.
     */
    public static final float SCROLLBAR_MARGIN = 2;

    /**
     * The popup's scroll-hint band. A <em>control</em> that intercepts clicks before item
     * activation; below ~10&nbsp;pt the scroll/activate boundary is unaimable.
     */
    public static final float MENU_SCROLL_HINT_H = 12;

    // -------------------------------------------------------- device and motor

    /**
     * A wheel detent is a device unit: the same physical flick must move the same physical
     * distance in a dense list and a roomy one. Accepted consequence: a notch covers ~1.7
     * menu rows at MEDIUM and ~2.0 at XSMALL.
     */
    public static final float WHEEL_STEP = 48;

    /**
     * WCAG 2.2 SC 2.5.8 (AA): the accessibility floor does not scale.
     *
     * <p>The height ramp pays this floor in paint (XSMALL is 24), so no control needs a
     * pointer target wider than its painted box: there is no hit outset and no two-pass
     * hit test, only a {@code Math.max} clamp at the few sites where an axis can fall
     * short. A clamp that can never bind is a defect rather than defensive coding: it
     * claims an axis can fall below the floor when it cannot, and the next reader has to
     * re-derive the ramp to find out.
     */
    public static final float MIN_HIT_TARGET = 24;

    /** Hand jitter: a motor constant. Growing it at XLARGE would make small deliberate drags read as clicks. */
    public static final float DRAG_SLOP = 2;

    /**
     * Icon glyphs have no ascender/descender slack and must be drawn slightly larger than
     * the text box to read at the same weight. A constant correction, not a proportion.
     */
    public static final float ICON_OPTICAL_BUMP = 2;
}
