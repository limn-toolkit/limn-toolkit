package limn.components;

import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;

/**
 * {@link TextField} that renders every character as a mask dot, with an optional reveal toggle.
 * While masked, copy/cut are blocked (the clipboard never sees the secret); paste and all editing
 * behave like a TextField.
 *
 * <p>The dot is <b>drawn, not typeset</b>; see {@link #DOT_DIAMETER}. Everything else (geometry,
 * padding, the animated border) is inherited from {@link TextField}: this class declares no extent
 * of its own beyond the dot's two ratios.
 *
 * <p><b>The content is never shaped.</b> {@link #shapeDisplay} builds the masked line from
 * {@link ShapedText#uniform}, which is one multiplication and no glyphs, so the secret never
 * reaches a shaper nor the memo a shaper keeps — and the caret, the click mapping, the selection
 * band and the painted dots then all come out of that one piece of arithmetic instead of two that
 * have to be kept in agreement.
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
     * against, because it is the advance {@link #shapeDisplay} hands to
     * {@link ShapedText#uniform}. It must stay comfortably greater than {@link #DOT_DIAMETER}:
     * the dots fusing into a dashed rule at the dense steps is the exact failure the old glyph
     * table was invented to dodge, and here it is a gap, not a glyph choice.
     */
    private static final float DOT_ADVANCE = 0.56f;

    private boolean revealed;

    /** Whether the characters are shown instead of the mask. */
    public boolean isRevealed() {
        return revealed;
    }

    /** Shows/hides the real text (the optional "reveal" toggle). */
    public PasswordField setRevealed(boolean newRevealed) {
        Ui.checkUiThread();
        this.revealed = newRevealed;
        // The held display line is refreshed against the text, the font and the ruler's epoch, and
        // none of those changed here: the base cannot see this flag, so it has to be told.
        invalidateDisplayLine();
        invalidate();
        return this;
    }

    /**
     * The masked line: one dot cell per grapheme cluster, built by arithmetic and not by a shaper.
     *
     * <p>{@link ShapedText#uniform} is what makes the whole class two overrides instead of a pair
     * of parallel measurements. It gives a line whose index space <em>is</em> the model's, so the
     * caret, the selection range and a click need no translation from source offsets to mask
     * offsets — the count-preserving substitution the old mask string had to guarantee is not a
     * thing that can go wrong any more, because there is no second string. And the content does not
     * reach {@code shape}, which is the reason this override exists: a shaper resolves faces,
     * memoizes what it was asked, and would be a place a secret comes to rest.
     *
     * <p>Cells are grapheme clusters, so one dot stands for one user-perceived character and the
     * caret can never land at a fractional dot: an astral character is one dot, not two, which also
     * keeps the mask from leaking that a character was astral.
     */
    @Override
    protected ShapedText shapeDisplay(String text, Font font) {
        if (revealed) {
            return super.shapeDisplay(text, font);
        }
        TextRuler ruler = textRuler();
        return ShapedText.uniform(text, font, cell(font), ruler.measure("Hg", font), ruler.epoch());
    }

    /**
     * Paints the mask as circles on the ink box's centre line.
     *
     * <p>The centre is {@code baseline - ascent + height/2}, the middle of the same band the
     * selection fill and the caret span, so the dots stay centred against any face the field's own
     * text would use, at any step, with no per-font vertical fudge. (A dot has no baseline of its
     * own to sit on: this is why the vertical anchor is the band and not the baseline.)
     */
    @Override
    protected void paintDisplayText(Canvas canvas, ShapedText display, float x, float baseline,
                                    TextMetrics metrics, SizeTokens t, Color ink) {
        if (revealed) {
            super.paintDisplayText(canvas, display, x, baseline, metrics, t, ink);
            return;
        }
        // TRAP: `display` carries the SECRET as its text(), because its index space has to be the
        // model's. It must never reach canvas.drawText -- so the super call above is the only one
        // in this class, and it is guarded by `revealed`.
        float cell = cell(t.body());
        float radius = DOT_DIAMETER * t.body().size() / 2;
        float centerY = baseline - metrics.ascent() + metrics.height() / 2;
        // Caret stops are the cells' own boundaries plus the end of the line, so the mark count is
        // one less than the stop count: no width has to be divided by an advance to recover it.
        int count = display.caretCount() - 1;
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

    /**
     * One dot's advance, in logical points.
     *
     * <p>The single expression of the pitch, read by both overrides. Written twice it would be the
     * drift the old {@code displayWidth}/{@code paintDisplayText} pair had a paragraph of warning
     * about: the geometry says the n-th dot is at one x and the ink puts it at another, and the
     * caret ends up beside the character it edits rather than on it.
     */
    private static float cell(Font font) {
        return DOT_ADVANCE * font.size();
    }
}
