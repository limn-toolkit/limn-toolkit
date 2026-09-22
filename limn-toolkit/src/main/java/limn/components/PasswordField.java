package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.scene.Change;

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
 *
 * <p>What an assistive technology is told follows the same toggle. Masked, the node is a password
 * field carrying the <em>mask</em> — one bullet per painted dot, with the caret and the selection
 * in the mask's own offsets — and revealed it is a plain text field carrying the value under the
 * model's. The role and the masked state move together, so no platform ever sees a password role
 * over cleartext; see {@link #onAccessibility}.
 */
public final class PasswordField extends TextField {

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

    /**
     * What a reader is told a masked character is: {@code U+2022 BULLET}, the platform convention
     * for a secure field's value on all three.
     *
     * <p>Deliberately unrelated to the painted mark, which is a drawn circle and no glyph at all
     * (see {@link #DOT_DIAMETER}). The two are the same fact said to two different consumers — one
     * counts marks on a screen, the other counts characters in a string — and the count is what
     * ties them together, not the shape.
     */
    private static final String MASK = "•";

    private boolean revealed;

    /**
     * The string the tree publishes and the counter that says when it moved: the mask while
     * masked, the base's own held text while revealed, and <b>one counter across both</b>.
     *
     * <p>Two counters is the defect this field exists to prevent, and it is not visible from
     * either branch alone. The difference between two snapshots compares the witness and nothing
     * else about the string, so a reveal that happened to hand over the same number as the mask
     * had would publish cleartext with an unmoved witness and raise no text change at all: a
     * reader would go on speaking bullets over a field that now reads plainly, and on the way back
     * would go on speaking the secret it had cached. The two counters do not merely happen to
     * collide, they collide immediately — both are 1 after their first build.
     */
    private String publishedText = "";

    /** The witness {@link #publishedText} is handed over with; moves when the string does. */
    private long publishedRevision;

    /** Which branch {@link #publishedText} was built for; half of its cache key. */
    private boolean publishedRevealed;

    /**
     * The other half: the dot count the mask was built for, or the base's own text revision the
     * revealed string came from. {@code -1} forces the first build, and the branch flag beside it
     * is what keeps a dot count from being mistaken for a revision.
     */
    private long publishedKey = -1;

    /** Whether the characters are shown instead of the mask. */
    public boolean isRevealed() {
        return revealed;
    }

    /** Shows/hides the real text (the optional "reveal" toggle). */
    public PasswordField setRevealed(boolean newRevealed) {
        Ui.checkUiThread();
        if (revealed == newRevealed) {
            return this;
        }
        this.revealed = newRevealed;
        // The held display line is refreshed against the text, the font and the ruler's epoch, and
        // none of those changed here: the base cannot see this flag, so it has to be told.
        invalidateDisplayLine();
        invalidate();
        // What changed is what the field shows of its own value, which is the thing a reader
        // would speak: bullets became plaintext, or the reverse.
        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.CODE));
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

    // -------------------------------------------------------- accessibility

    /**
     * The same node {@link TextField} publishes, said to be masked and carrying the <em>mask</em>
     * in place of the contents — one bullet per painted dot, with the caret and the selection in
     * the mask's own offsets.
     *
     * <p><b>The role and the {@code PASSWORD} state follow the toggle, and they move together.</b>
     * ADR 039 §7's row states both flat and conditions only the facet on the flag, and that
     * combination contradicts itself: {@code State.PASSWORD} says in its own words that the text
     * is masked and must never be spoken, which is not a thing to publish beside the cleartext the
     * same row asks for when the field is revealed. Which of the two follows the flag is then the
     * real question, and it is the role as well, because a platform's refusal to speak is keyed on
     * the role and not on the state — AT-SPI's password role, AppKit's secure-field class,
     * {@code Role.PASSWORD_FIELD}'s own definition as "a text field whose content is masked". A
     * revealed field left under that role would be the one control on screen a blind user cannot
     * read <em>after deliberately asking to</em>, which is the whole purpose of the toggle. Moving
     * the two together is what keeps any platform from ever seeing a password role over cleartext,
     * or a plain field over a mask.
     *
     * <p>The role is written after the super call and cannot move before it, for the reason
     * {@link SearchField} records: a role is a plain store into the node's slot, the base writes
     * {@code TEXT_FIELD} into it, and it lands on this widget's node rather than on the trailing
     * button's only because the base closes every synthetic child it opened.
     *
     * <p>Everything else is inherited and deliberately not restated: the placeholder names the
     * node, {@code EDITABLE} and the context menu are the base's, and a trailing button — the
     * natural place to hang a reveal control — is the base's synthetic child with the base's box.
     * {@code READ_ONLY} is never set: a masked field is editable, and masking is not read-only.
     * There is no node for the reveal toggle itself, because the toggle is a public setter an
     * application drives from a control of its own and this widget draws nothing for it; §7.1 asks
     * that a synthetic child be something its owner draws.
     *
     * <p>The facet is republished here in <b>both</b> branches, over whatever the base wrote,
     * because the two branches have to share one witness; {@link #publishedText} carries the whole
     * of that reasoning. The base's write while revealed is a dead set of stores this hook
     * overwrites in full, which costs nothing.
     *
     * @param a the node being described
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        super.onAccessibility(a);
        a.role(revealed ? Accessible.Role.TEXT_FIELD : Accessible.Role.PASSWORD_FIELD);
        a.state(Accessible.State.PASSWORD, !revealed);
        // The held masked line, read for its CARET STOPS and never for its text(), which IS the
        // secret. Not asked for at all while revealed: the offsets are then the model's own, and a
        // frame that only republishes the tree would otherwise shape a line nothing draws.
        ShapedText line = revealed ? null : displayLine();
        // The identical expression paintDisplayText's loop counts with, so the published mask and
        // the painted dots cannot drift; not a second walk over the clusters, which is the rule
        // ShapedText.uniform exists to delete.
        int dots = revealed ? 0 : line.caretCount() - 1;
        // Asking for the string first is what refreshes the counter beside it.
        String held = revealed ? accessibleText() : null;
        long key = revealed ? accessibleTextRevision() : dots;
        if (publishedRevealed != revealed || publishedKey != key) {
            // Only here, never per frame: the counter moves when the string moves and not when the
            // rebuild runs, which is the shape accessibleText() itself has and for the same reason.
            String rebuilt = revealed ? held : MASK.repeat(dots);
            publishedRevealed = revealed;
            publishedKey = key;
            if (!rebuilt.equals(publishedText)) {
                publishedText = rebuilt;
                publishedRevision++;
            }
        }
        // Mask ordinals through the shaped line's own inverse. The model's offsets are the
        // secret's UTF-16 offsets: with one astral character in the value they run past the end of
        // a mask that is one dot per cluster, and the caret would be published outside the string
        // it is a caret into. The side is pinned downstream while masked because the mask is a
        // simple left-to-right line where every offset has exactly one visual position -- and
        // because it is a field the difference compares, so an upstream side kept across a toggle
        // would report a caret move nobody can perceive, carrying a fact derived from a shaping of
        // the secret.
        a.text(publishedText, publishedRevision,
                revealed ? model.cursor() : line.caretOrdinal(model.cursor()),
                revealed ? model.caretAffinity() : ShapedText.Affinity.DOWNSTREAM,
                revealed ? model.selectionStart() : line.caretOrdinal(model.selectionStart()),
                revealed ? model.selectionEnd() : line.caretOrdinal(model.selectionEnd()),
                // From the model as the base does, so a subclass holding a multi-line model cannot
                // drift; the caret box is the base's held one, already mask geometry because it is
                // measured from the line the mask replaced, and null unless focused.
                model.lineCount(), isFocused() ? heldCaretBox() : null, false);
    }

    /**
     * Translates a caret or a selection back out of the mask's offsets before the field places it,
     * and refuses one the mask has no stop for.
     *
     * <p><b>This is the half ADR 039 §7's row is silent about, and it is where the bug is.</b> The
     * row maps a model offset forward to a mask ordinal for the facet and stops there. The verbs
     * come back the other way: a client holds the published string, so it asks in <em>mask</em>
     * ordinals, and the inherited handler consumes model {@code char} offsets. Left alone, a
     * reader asking for the caret after the second dot of a value whose first character is astral
     * lands inside the surrogate pair, silently — the shorter buffer's own bounds check passes,
     * which is the same shape of failure the base refuses an offset-carrying verb over during an
     * IME composition.
     *
     * <p>An ordinal outside the mask is <b>refused and never clamped</b>, because
     * {@link ShapedText#caretIndex} clamps: without the check a client asking for dot forty of a
     * three-dot field would be quietly answered with the end of the value, which is the mistake
     * the base states its own reason for refusing rather than hiding.
     *
     * <p>Everything else goes to {@code super} and must. The context menu is still the only route
     * a reader has to Cut, Copy, Paste and Select All, and the two the mask must not let out are
     * already greyed by {@link #allowClipboardCopy()} rather than by suppressing the menu. The
     * whole-value set carries no offsets at all, needs no translation, and is how a password
     * manager fills the field.
     *
     * @param action what is being asked
     * @param arg    the text for a whole-value set, and the mask's own range for the other two
     * @return whether this widget did it
     */
    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        if (revealed
                || (action != Accessible.Action.SET_CARET
                        && action != Accessible.Action.SET_SELECTION)
                || !(arg instanceof Accessible.Argument.OfRange range)) {
            return super.onAccessibilityAction(action, arg);
        }
        ShapedText line = displayLine();
        int dots = line.caretCount() - 1;
        if (range.start() < 0 || range.start() > dots || range.end() < 0 || range.end() > dots) {
            return false;
        }
        // One argument per user-driven verb, which is not the per-frame allocation the cost rule
        // is about. The base's "a caret is a collapsed range" check survives the translation,
        // because equal ordinals give equal char indices.
        return super.onAccessibilityAction(action, new Accessible.Argument.OfRange(
                line.caretIndex(range.start()), line.caretIndex(range.end())));
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
