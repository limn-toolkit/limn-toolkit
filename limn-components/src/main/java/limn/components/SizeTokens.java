package limn.components;

import java.util.Objects;
import limn.graphics.Font;
import limn.scene.ControlSize;

/**
 * The numeric size table: one immutable row per {@link ControlSize} step.
 *
 * <p>Hand-authored, five rows, no {@code base * k(step)} anywhere. Three
 * independent ramps: type 11/12/14/16/19 (span 1.73x), control height
 * 24/28/32/40/50 (2.08x), spacing 3/4/6/8/10 (3.33x). Reach a row with
 * {@link Theme#tokensFor(limn.scene.Widget)}; {@link #MEDIUM} is the default and
 * the nine {@code Theme} tokens are initialized <em>from</em> it, so
 * {@code assertSame(theme.body, SizeTokens.MEDIUM.body())} holds.
 *
 * <p><b>Quantities that are weights, not sizes</b> (every border, focus ring and
 * its gap, hairline, check-mark pen, caret, AA bleed, damage margin, wheel step,
 * and the WCAG hit floor) live in {@link Strokes} and are identical at every
 * step <em>by construction</em>: there is no five-column row for any of them.
 * This record therefore declares <b>no {@code double} of any kind</b>, so an
 * animation duration cannot be smuggled in.
 *
 * <p>{@code padV} is a <b>measure-only</b> floor input to
 * {@code max(controlHeight, lineHeight + 2 * padV)}. The box binds at every step,
 * so this number must never appear as a paint or hit-test coordinate; what a
 * control centres against is the effective padV {@code (height - lineHeight)/2}
 * = 5.554688 / 6.968750 / 7.796875 / 10.625000 / 13.867188.
 */
public record SizeTokens(
        // ---- fonts (3.1, 3.2) -------------------------------------------
        Font body, Font label, Font title,
        // ---- spacing ramp (3.4) -----------------------------------------
        float spacingSmall, float spacingMedium, float spacingLarge,
        // ---- radii (3.5) ------------------------------------------------
        float radiusSmall, float radiusMedium, float radiusLarge,
        float indicatorRadius,
        // ---- heights (3.3) ----------------------------------------------
        float controlHeight, float listRowSeed, float menuRowHeight,
        float popupItemHeight,
        // ---- paddings ---------------------------------------------------
        float areaPad, float fieldPadH, float menuBarPadH, float menuPadV,
        float padH, float padV, float popupPadV, float popupRowInsetX,
        float segPadH, float spinnerFieldPadX, float tabPadH, float tabPadV,
        float toolBarPad, float tooltipPadH, float tooltipPadV,
        // ---- gaps (3.4: em-tuned, off the spacing curve) -----------------
        float colorGap, float gapButtonRow, float gapIcon, float gapLabel, float newlineHint,
        float popupGap, float tabIconGap, float tabRevealMargin,
        float toggleColumnGap, float toolBarGap,
        // ---- insets -----------------------------------------------------
        float comboCaretCenterX, float fieldDividerInset, float indicatorInset,
        float menuArrowNudge, float menuBarChipInset, float menuCheckInset,
        float menuHiliteInsetX, float menuHiliteInsetY, float menuSepInsetX,
        float popupBarInsetX, float popupDotCol, float popupMarkerCol,
        float segInset, float spinnerFieldInset, float stripBtnHoverInset,
        float switchThumbInset, float tabHoverInset, float toolBarSepInset,
        // ---- extents ----------------------------------------------------
        float areaHeight, float areaWidth, float arrowHalf, float checkGlyphW,
        float chevronHalfW, float colorDialogW, float colorFieldH, float colorRailH, float colorRampW,
        float colorThumbH, float colorThumbW, float comboCaretGutter, float comboTextClip,
        float dialogMaxWidth, float fieldIcon, float fieldTrailing,
        float fieldWidth, float iconBox, float indicator, float menuArrowGutter,
        float menuArrowH, float menuArrowW, float menuCheckGutter,
        float popupDotRadius, float progressThickness, float scrollChevronHalf,
        float separatorBox, float sliderKnob, float sliderKnobHover,
        float sliderRail, float spinnerButtonW, float switchTrackH,
        float switchTrackW, float tabChevron, float tabIconSize,
        // ---- free-axis floors the parent normally overrides --------------
        float listWidth, float menuMinWidth, float spinnerWidth) {

    // =================================================================
    //  THE FIVE ROWS, written as 88 columns.
    //  Column order is ALWAYS  { XSMALL, SMALL, MEDIUM, LARGE, XLARGE }.
    // =================================================================

    // fonts ------------------------------------------------------------
    private static final Font[] BODY  = { Font.of(11), Font.of(12), Font.of(14),  Font.of(16), Font.of(19) };
    private static final Font[] LABEL = { Font.of(10), Font.of(11), Font.of(13),  Font.of(14), Font.of(17) };
    private static final Font[] TITLE = { Font.of(15), Font.of(17), Font.of(20),  Font.of(24), Font.of(29) };
    // spacing ramp -----------------------------------------------------
    private static final float[] SPACING_SMALL         = {    3,     4,        6,     8,    10 };
    private static final float[] SPACING_MEDIUM        = {    6,     9,       12,    16,    20 };
    private static final float[] SPACING_LARGE         = {   12,    16,       20,    26,    32 };
    // radii ------------------------------------------------------------
    private static final float[] RADIUS_SMALL          = {    3,     3,        4,     5,     6 };
    private static final float[] RADIUS_MEDIUM         = {    5,     6,        8,    10,    12 };
    private static final float[] RADIUS_LARGE          = {    9,    11,       14,    17,    20 };
    private static final float[] INDICATOR_RADIUS      = {    4,     4,        4,     5,     6 }; // Checkbox / RadioButton box

    // heights ----------------------------------------------------------
    private static final float[] CONTROL_HEIGHT        = {   24,    28,       32,    40,    50 }; // two controls lose a fraction of a point here so the ramp stays integral
    private static final float[] LIST_ROW_SEED         = {   30,    38,       48,    58,    70 };
    private static final float[] MENU_ROW_HEIGHT       = {   24,    26,       28,    34,    40 };
    private static final float[] POPUP_ITEM_HEIGHT     = {   24,    26,       30,    36,    42 };
    // paddings ---------------------------------------------------------
    private static final float[] AREA_PAD              = {    6,     7,        8,    10,    12 };
    private static final float[] FIELD_PAD_H           = {    8,    10,       12,    15,    18 };
    private static final float[] MENU_BAR_PAD_H        = {    6,     9,       12,    15,    18 };
    private static final float[] MENU_PAD_V            = {    4,     5,        6,     7,     8 };
    private static final float[] PAD_H                 = {   12,    16,       20,    26,    32 };
    private static final float[] PAD_V                 = {    5,     6,        7,    10,    13 }; // MEASURE-ONLY.
    private static final float[] POPUP_PAD_V           = {    3,     4,        6,     8,    10 };
    private static final float[] POPUP_ROW_INSET_X     = {    3,     4,        6,     8,    10 };
    private static final float[] SEG_PAD_H             = {    8,    12,       16,    20,    26 };
    private static final float[] SPINNER_FIELD_PAD_X   = {    2,     2,        3,     4,     5 };
    private static final float[] TAB_PAD_H             = {    8,    12,       16,    20,    24 };
    private static final float[] TAB_PAD_V             = {    6,     7,        9,    12,    15 };
    private static final float[] TOOL_BAR_PAD          = {    4,     6,        8,    10,    12 };
    private static final float[] TOOLTIP_PAD_H         = {    5,     6,        8,    10,    12 };
    private static final float[] TOOLTIP_PAD_V         = {    3,     4,        5,     6,     8 }; // must stay integral

    // gaps -------------------------------------------------------------
    private static final float[] GAP_BUTTON_ROW        = {    4,     5,        6,     8,    10 };
    private static final float[] COLOR_GAP             = {    5,     6,        8,    10,    13 }; // ColorPicker block + row gap
    private static final float[] GAP_ICON              = {    5,     6,        8,     9,    11 };
    private static final float[] GAP_LABEL             = {    4,     5,        6,     8,    10 };
    private static final float[] NEWLINE_HINT          = {    4,     5,        6,     8,    10 };
    private static final float[] POPUP_GAP             = {    2,     3,        4,     5,     6 };
    private static final float[] TAB_ICON_GAP          = {    4,     5,        6,     8,    10 };
    private static final float[] TAB_REVEAL_MARGIN     = {   12,    18,       24,    30,    36 };
    private static final float[] TOGGLE_COLUMN_GAP     = {    6,     6,        6,     8,    10 }; // = max(spacingSmall, MIN_HIT_TARGET - indicator)
    private static final float[] TOOL_BAR_GAP          = {    4,     6,        8,    10,    12 }; // ToolBar (default only; latch an explicit gap(f))

    // insets -----------------------------------------------------------
    private static final float[] COMBO_CARET_CENTER_X  = {   11,    13,       16,    19,    22 };
    private static final float[] FIELD_DIVIDER_INSET   = {    6,     7,        8,    10,    13 }; // TextField (pinned at 8: padV drops to 7 and is not a paint coord)
    private static final float[] INDICATOR_INSET       = {    4,     4,        4,     5,   5.5f};
    private static final float[] MENU_ARROW_NUDGE      = {    4,     5,        6,     7,     8 };
    private static final float[] MENU_BAR_CHIP_INSET   = {    2,     3,        3,     4,     5 };
    private static final float[] MENU_CHECK_INSET      = {    5,     7,        8,    10,    12 };
    private static final float[] MENU_HILITE_INSET_X   = {    3,     3,        4,     5,     6 };
    private static final float[] MENU_HILITE_INSET_Y   = {    2,     2,        2,     3,     4 };
    private static final float[] MENU_SEP_INSET_X      = {    8,     9,       10,    12,    14 };
    private static final float[] POPUP_BAR_INSET_X     = {    2,     2,        3,     4,     5 };
    private static final float[] POPUP_DOT_COL         = {    5,     6,        7,   8.5f,   10 };
    private static final float[] POPUP_MARKER_COL      = {   11,    13,       16,    19,    22 };
    private static final float[] SEG_INSET             = {    2,     3,        3,     4,     5 };
    private static final float[] SPINNER_FIELD_INSET   = {    2,     3,        4,     5,     6 };
    private static final float[] STRIP_BTN_HOVER_INSET = {    2,     2,        3,     5,     6 };
    private static final float[] SWITCH_THUMB_INSET    = {    3,     3,        3,  3.5f,     4 };
    private static final float[] TAB_HOVER_INSET       = {    3,     3,        4,     5,     6 };
    private static final float[] TOOL_BAR_SEP_INSET    = {    2,     3,        4,     5,     6 };
    // extents ----------------------------------------------------------
    private static final float[] AREA_HEIGHT           = {  110,   120,      140,   162,   192 };
    // Deliberately not re-derived from the horizontal inset. TextArea takes fieldPadH across,
    // shared with TextField, and that climbs faster than areaPad, so the visible column count
    // is 42.9/42.7/42.3/42.3/42.1 rather than flat. Re-deriving this row to flatten it would
    // move the MEDIUM box, which is the one measurement every existing layout is built on. The
    // residual is under 3% and monotone; a flat column count, if ever wanted, is a re-baseline
    // of its own rather than an edit here.
    private static final float[] AREA_WIDTH            = {  252,   276,      320,   368,   436 }; // TextArea
    private static final float[] ARROW_HALF            = {    4,     4,        4,     5,     6 };
    private static final float[] CHECK_GLYPH_W         = {    9,     9,        9, 10.5f,    12 };
    private static final float[] CHEVRON_HALF_W        = {    5,     5,        5,     6,     7 };
    // ColorPicker (7.32). The picker is one composite whose parts have no analogue
    // elsewhere: a two-axis colour canvas, the ramp beside it, and the channel rails
    // under it. MEDIUM holds the shipped geometry exactly, so adopting the axis moved
    // no pixel at the default step.
    private static final float[] COLOR_DIALOG_W        = {  240,   280,      320,   380,   440 }; // picker inside a dialog
    private static final float[] COLOR_FIELD_H         = {  100,   122,      148,   180,   216 }; // ColorPicker field
    private static final float[] COLOR_RAIL_H          = {    6,     8,       10,    12,    14 }; // EVEN: centred in the rail box
    private static final float[] COLOR_RAMP_W          = {   12,    14,       18,    22,    26 }; // hue ramp
    private static final float[] COLOR_THUMB_H         = {   12,    15,       18,    22,    26 }; // rail thumb
    private static final float[] COLOR_THUMB_W         = {    8,     9,       10,    12,    14 }; // rail thumb
    private static final float[] COMBO_CARET_GUTTER    = {   16,    20,       24,    28,    34 };
    private static final float[] COMBO_TEXT_CLIP       = {   18,    22,       26,    30,    36 };
    private static final float[] DIALOG_MAX_WIDTH      = {  320,   380,      440,   520,   600 };
    private static final float[] FIELD_ICON            = {   12,    14,       16,    18,    22 };
    private static final float[] FIELD_TRAILING        = {   24,    28,       32,    40,    50 };
    private static final float[] FIELD_WIDTH           = {  172,   204,      240,   300,   360 };
    private static final float[] ICON_BOX              = {   14,    16,       18,    20,    24 };
    private static final float[] INDICATOR             = {   18,    18,       18,    22,    24 };
    private static final float[] MENU_ARROW_GUTTER     = {   15,    18,       22,    26,    30 };
    private static final float[] MENU_ARROW_H          = {    8,     8,        8,  9.5f,    11 };
    private static final float[] MENU_ARROW_W          = {    5,     5,        5,     6,     7 };
    private static final float[] MENU_CHECK_GUTTER     = {   18,    22,       26,    30,    34 };
    private static final float[] POPUP_DOT_RADIUS      = { 1.75f,    2,     2.5f,     3,  3.5f}; // ComboBox (decision 7)
    private static final float[] PROGRESS_THICKNESS    = {    4,     6,        8,    10,    12 };
    private static final float[] SCROLL_CHEVRON_HALF   = {    4,     4,        4,  4.5f,     5 };
    private static final float[] SEPARATOR_BOX         = {    5,     7,        9,    13,    17 };
    private static final float[] SLIDER_KNOB           = {    5,  6.5f,        8,    10,    12 };
    private static final float[] SLIDER_KNOB_HOVER     = { 6.5f,     8,       10, 12.5f,    15 };
    private static final float[] SLIDER_RAIL           = {    4,     4,        6,     7,     8 }; // Slider (6, NOT the 5 in ADR 8.2)
    private static final float[] SPINNER_BUTTON_W      = {   18,    22,       26,    30,    36 };
    private static final float[] SWITCH_TRACK_H        = {   22,    22,       22,    26,    30 };
    private static final float[] SWITCH_TRACK_W        = {   40,    40,       40,    47,    55 };
    private static final float[] TAB_CHEVRON           = {    5,     5,        5,     6,  7.5f};
    private static final float[] TAB_ICON_SIZE         = {   13,    14,       16,    19,    22 };

    // free-axis floors -------------------------------------------------
    private static final float[] LIST_WIDTH            = {  160,   200,      240,   288,   340 };
    private static final float[] MENU_MIN_WIDTH        = {  112,   140,      168,   196,   224 };
    private static final float[] SPINNER_WIDTH         = {   96,   116,      140,   168,   200 };
    /** Indexed by {@link ControlSize#ordinal()}. Static, palette-independent (7.30). */
    public static final SizeTokens[] TABLE = build();

    /** The default step's row. {@code Theme}'s nine tokens are initialized from this. */
    public static final SizeTokens MEDIUM = TABLE[ControlSize.MEDIUM.ordinal()];

    /**
     * The whole table with every corner radius multiplied by {@code scale}, the one metric a
     * {@link Theme} is allowed to carry, because it is the only one nothing measures from.
     *
     * <p>Returns the shared table itself at scale 1, so a palette that does not ask for a
     * shape of its own keeps the process-wide identity every other caller relies on.
     * Anything else builds five rows <b>once</b>, for the palette to hold: building them per
     * call would defeat the backend's identity-keyed font memo, which is what the rows are
     * shared for.
     */
    static SizeTokens[] tableWithCornerScale(float scale) {
        return scale == 1f ? TABLE : build(scale);
    }

    private static SizeTokens[] build() {
        return build(1f);
    }

    private static SizeTokens[] build(float cornerScale) {
        SizeTokens[] rows = new SizeTokens[ControlSize.values().length];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new SizeTokens(
                    BODY[i], LABEL[i], TITLE[i],
                    SPACING_SMALL[i], SPACING_MEDIUM[i], SPACING_LARGE[i],
                    RADIUS_SMALL[i] * cornerScale, RADIUS_MEDIUM[i] * cornerScale,
                    RADIUS_LARGE[i] * cornerScale,
                    INDICATOR_RADIUS[i] * cornerScale,
                    CONTROL_HEIGHT[i], LIST_ROW_SEED[i], MENU_ROW_HEIGHT[i],
                    POPUP_ITEM_HEIGHT[i],
                    AREA_PAD[i], FIELD_PAD_H[i], MENU_BAR_PAD_H[i], MENU_PAD_V[i],
                    PAD_H[i], PAD_V[i], POPUP_PAD_V[i], POPUP_ROW_INSET_X[i],
                    SEG_PAD_H[i], SPINNER_FIELD_PAD_X[i], TAB_PAD_H[i], TAB_PAD_V[i],
                    TOOL_BAR_PAD[i], TOOLTIP_PAD_H[i], TOOLTIP_PAD_V[i],
                    COLOR_GAP[i], GAP_BUTTON_ROW[i], GAP_ICON[i], GAP_LABEL[i], NEWLINE_HINT[i],
                    POPUP_GAP[i], TAB_ICON_GAP[i], TAB_REVEAL_MARGIN[i],
                    TOGGLE_COLUMN_GAP[i], TOOL_BAR_GAP[i],
                    COMBO_CARET_CENTER_X[i], FIELD_DIVIDER_INSET[i], INDICATOR_INSET[i],
                    MENU_ARROW_NUDGE[i], MENU_BAR_CHIP_INSET[i], MENU_CHECK_INSET[i],
                    MENU_HILITE_INSET_X[i], MENU_HILITE_INSET_Y[i], MENU_SEP_INSET_X[i],
                    POPUP_BAR_INSET_X[i], POPUP_DOT_COL[i], POPUP_MARKER_COL[i],
                    SEG_INSET[i], SPINNER_FIELD_INSET[i], STRIP_BTN_HOVER_INSET[i],
                    SWITCH_THUMB_INSET[i], TAB_HOVER_INSET[i], TOOL_BAR_SEP_INSET[i],
                    AREA_HEIGHT[i], AREA_WIDTH[i], ARROW_HALF[i], CHECK_GLYPH_W[i],
                    CHEVRON_HALF_W[i], COLOR_DIALOG_W[i], COLOR_FIELD_H[i], COLOR_RAIL_H[i],
                    COLOR_RAMP_W[i],
                    COLOR_THUMB_H[i], COLOR_THUMB_W[i],
                    COMBO_CARET_GUTTER[i], COMBO_TEXT_CLIP[i],
                    DIALOG_MAX_WIDTH[i], FIELD_ICON[i], FIELD_TRAILING[i],
                    FIELD_WIDTH[i], ICON_BOX[i], INDICATOR[i], MENU_ARROW_GUTTER[i],
                    MENU_ARROW_H[i], MENU_ARROW_W[i], MENU_CHECK_GUTTER[i],
                    POPUP_DOT_RADIUS[i], PROGRESS_THICKNESS[i], SCROLL_CHEVRON_HALF[i],
                    SEPARATOR_BOX[i], SLIDER_KNOB[i], SLIDER_KNOB_HOVER[i],
                    SLIDER_RAIL[i], SPINNER_BUTTON_W[i], SWITCH_TRACK_H[i],
                    SWITCH_TRACK_W[i], TAB_CHEVRON[i], TAB_ICON_SIZE[i],
                    LIST_WIDTH[i], MENU_MIN_WIDTH[i], SPINNER_WIDTH[i]);
        }
        return rows;
    }

    /** The row for one step. */
    public static SizeTokens of(ControlSize step) {
        return TABLE[Objects.requireNonNull(step, "step").ordinal()];
    }

    // ---- derived, deliberately NOT table rows ---------------------------
    // Each is a function of the row above; giving any of them a five-column
    // row would create a second, silently divergent path to the same pixels.

    /** The one height formula for every text-bearing control (3.3). */
    public float resolvedHeight(float lineHeight) {
        return Math.max(controlHeight, lineHeight + 2 * padV);
    }

    /** What a control actually centres against: (h - lineHeight)/2. Fractional by design (3.6). */
    public float effectivePadV(float lineHeight) {
        return (resolvedHeight(lineHeight) - lineHeight) / 2;
    }

    /** SegmentedControl's inset pill, concentric with the track at every step. */
    public float segPillRadius() {
        // fudge that is 2pt off the concentric answer; the inset pill now curves parallel to the
        // track it sits in, like the other four steps already did.
        return Math.max(0, radiusMedium - segInset);
    }

    /** Checkbox / RadioButton focus radius. The +2 is locked. */
    public float indicatorFocusRadius() {
        return indicatorRadius + 2;
    }

    /** Slider's half-height: knobHover + the ring's gap + the ring's own stroke, at every step. */
    public float sliderPad() {
        // MEDIUM slider's ring was clipped by half its own width at the ends of the track.
        return sliderKnobHover + Strokes.FOCUS_GAP_SLIDER + Strokes.BORDER;
    }

    /** Slider's measured height: 24 / 24 / 28 / 33 / 38. */
    public float sliderHeight() {
        return Math.max(Strokes.MIN_HIT_TARGET, 2 * sliderPad());
    }
}
