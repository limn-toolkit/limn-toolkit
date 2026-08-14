package limn.components;

import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.TextMetrics;

/**
 * {@link TextField} that renders every code point as a mask dot, with an
 * optional reveal toggle. While masked, copy/cut are blocked (the clipboard
 * never sees the secret); paste and all editing behave like a TextField.
 *
 * <p>The dot is <b>drawn, not typeset</b>; see {@link #DOT_DIAMETER}. Everything else
 * (geometry, padding, the animated border) is inherited from {@link TextField}: this class
 * declares no extent of its own beyond the dot's two ratios.
 */
public class PasswordField extends TextField {

    /**
     * Dot diameter as a fraction of the body font size, so the mask scales with the type
     * ramp like everything else.
     *
     * <p>The dot is <b>drawn, not a glyph</b>. A glyph mask would depend on which faces are
     * resident: {@code U+25CF} is absent from the bundled last-resort face, so it resolved
     * through the fallback chain, which loads in the background, so the first frame of a
     * password field in a fresh process painted {@code .notdef} boxes. A circle needs no
     * font. 0.36&nbsp;em is where the faces normally used for password fields put their
     * bullet, and it stays a solid countable mark at the dense steps without becoming a row
     * of buttons at the display ones.
     */
    private static final float DOT_DIAMETER = 0.36f;

    /**
     * Dot pitch (one dot's cell) as a fraction of the body font size: 0.20&nbsp;em of gap
     * around 0.36&nbsp;em of ink, near enough Verdana's proportion for the same mark
     * (0.365&nbsp;em of ink in a 0.545&nbsp;em advance).
     *
     * <p>This is the advance the caret, the selection band and the click mapping all measure
     * against, because {@link TextField#displayWidth} is the single answer to "how wide is
     * this". It must stay comfortably greater than {@link #DOT_DIAMETER}:
     * the dots fusing into a dashed rule at the dense steps is the exact failure the old glyph
     * table was invented to dodge, and here it is a gap, not a glyph choice.
     */
    private static final float DOT_ADVANCE = 0.56f;

    /**
     * The character the mask string is built from.
     *
     * <p>It is a <b>counter, not a mark</b>: while masked nothing paints it; {@link
     * #paintDisplayText} draws circles and {@link #displayWidth} computes the advance from
     * {@link #DOT_ADVANCE}, so the display string only ever has to carry the right <em>number</em>
     * of code points. BULLET is kept anyway because it is the honest stand-in if some future path
     * ever falls back to typesetting it, and because it must be a single BMP {@code char}: the
     * mask is built with {@code String.repeat}, so an astral glyph would emit two chars per source
     * code point and every char offset over {@link #displayText()} would double.
     */
    private static final char MASK = '•';

    private boolean revealed;

    /** Whether the characters are shown instead of the mask. */
    public boolean isRevealed() {
        return revealed;
    }

    /** Shows/hides the real text (the optional "reveal" toggle). */
    public PasswordField setRevealed(boolean newRevealed) {
        Ui.checkUiThread();
        this.revealed = newRevealed;
        invalidate();
        return this;
    }

    @Override
    protected String displayText() {
        return revealed ? super.displayText() : mask(model.text());
    }

    @Override
    protected String displayPrefix(int charIndex) {
        return revealed ? super.displayPrefix(charIndex) : mask(super.displayPrefix(charIndex));
    }

    /**
     * One dot cell per masked code point, instead of the ruler's opinion about a glyph.
     *
     * <p>Overridden together with {@link #paintDisplayText}, which is what keeps the caret on
     * the dot it edits: both read {@link #DOT_ADVANCE}, so the n-th dot is painted at exactly
     * the x the prefix of n dots measures to. Overriding one without the other is the drift
     * this pair exists to make impossible.
     */
    @Override
    protected float displayWidth(String display, SizeTokens t) {
        return revealed ? super.displayWidth(display, t) : dots(display) * cell(t);
    }

    /**
     * Paints the mask as circles on the ink box's centre line.
     *
     * <p>The centre is {@code inkTop + height/2}, the middle of the same band the selection
     * fill and the caret span, so the dots stay centred against any face the field's own text
     * would use, at any step, with no per-font vertical fudge. (A dot has no baseline of its
     * own to sit on: this is why the vertical anchor is the band and not the baseline.)
     */
    @Override
    protected void paintDisplayText(Canvas canvas, String display, float x, float baseline,
                                    TextMetrics metrics, SizeTokens t, Color ink) {
        if (revealed) {
            super.paintDisplayText(canvas, display, x, baseline, metrics, t, ink);
            return;
        }
        float cell = cell(t);
        float radius = DOT_DIAMETER * t.body().size() / 2;
        float centerY = baseline - metrics.ascent() + metrics.height() / 2;
        int count = dots(display);
        for (int i = 0; i < count; i++) {
            canvas.fillCircle(x + (i + 0.5f) * cell, centerY, radius, ink);
        }
    }

    @Override
    protected boolean allowClipboardCopy() {
        return revealed;
    }

    /**
     * While masked, word-wise caret jumps and deletes would let an observer
     * count the words and their lengths inside the secret; modifier+arrows
     * degrade to per-character moves until revealed.
     */
    @Override
    protected boolean allowsWordJumps() {
        return revealed;
    }

    /**
     * Secure text entry keeps the platform IME off (the convention of
     * {@code NSSecureTextField}/{@code ES_PASSWORD}): composition would echo
     * the secret in cleartext between the mask characters, surface it in the
     * OS candidate window, and feed it to the IME's learning dictionary. The
     * scene also drops preedit events for widgets that refuse text input, so
     * no composition can reach this field even mid-teardown. Direct key/char
     * input (including paste) is unaffected.
     */
    @Override
    protected boolean acceptsTextInput() {
        return false;
    }

    /** One dot's advance at the step resolved on this widget. */
    private static float cell(SizeTokens t) {
        return DOT_ADVANCE * t.body().size();
    }

    /** How many dots a display string stands for. */
    private static int dots(String display) {
        return display.codePointCount(0, display.length());
    }

    /**
     * Masks {@code text} as exactly <b>one mask code point per source code point</b>.
     *
     * <p>This count is load-bearing, not cosmetic.
     * {@link limn.components.text.TextEditModel} offsets (caret index,
     * selection range, the binary search behind click mapping) are indices into the
     * <em>source</em>, and the only bridge to what is painted is
     * {@code displayPrefix(charIndex)}: the caret's x is the width of the mask of the prefix.
     * Painted dots and model offsets therefore agree only while the substitution is
     * count-preserving. Emit one dot too many or too few for any input and the caret drifts from
     * the character it edits, a click lands on a neighbour, and selection highlights the wrong
     * run.
     *
     * <p>{@code codePoints().count()} and not {@code length()}: an astral character (one code
     * point, two chars as a surrogate pair) must mask to <b>one</b> dot. Counting chars would
     * show two, which both breaks the agreement above and leaks that the character was astral.
     * Symmetrically the glyph itself is BMP (see {@link #MASK}), so that the mask's char length
     * equals its code-point count and no char-index arithmetic over the display string can shift.
     */
    private static String mask(String text) {
        return String.valueOf(MASK).repeat((int) text.codePoints().count());
    }
}
