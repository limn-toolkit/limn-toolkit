package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.text.TextEditModel;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.i18n.I18nString;
import limn.graphics.Icon;
import limn.graphics.Rect;
import limn.graphics.RoundRect;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.CharEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.event.PreeditEvent;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Single-line text input: blinking cursor (frame-clock ticker), mouse and
 * Shift+arrow selection, Home/End, Ctrl/Cmd+A/C/V/X through the system
 * clipboard, placeholder, and horizontal scrolling that keeps the cursor
 * visible when the text overflows. Colors come from the {@link Theme}, metrics from the
 * {@link SizeTokens} row of the step resolved on this widget, and every stroke from
 * {@link Strokes}, which is why a focus ring is the same weight at XSMALL and XLARGE.
 *
 * <p>The reference implementation for the text cluster: {@link PasswordField} and
 * {@link SearchField} inherit all of this geometry and declare none of their own.
 *
 * <p><b>Every horizontal coordinate here comes from one shaped line</b>, held in a field and
 * re-shaped only when its inputs change. Caret x is {@link ShapedText#caretX}, a click is
 * {@link ShapedText#hitTest}, the selection band is the {@code N} boxes
 * {@link ShapedText#selection(int, int, float[]) selection} returns, and the arrow keys step
 * through {@link ShapedText#caretLeft}/{@link ShapedText#caretRight}. None of it is the width of a
 * prefix of the string, because with a shaper in the pipeline that width is not a thing that
 * exists: inside their line characters join, ligate, kern and <em>reorder</em> differently than
 * they do alone, so a prefix measurement is not a slower way to place a caret but a wrong one.
 * {@link #shapeDisplay} is where a subclass substitutes a display form, and its index space is the
 * model's, which is what lets the substitution happen without any index arithmetic at all.
 */
public class TextField extends Widget {

    /** Validation state; colors the border (and a caller-supplied message). */
    public enum Validation { NONE, ERROR, WARNING, SUCCESS, INFO }

    private static final double BLINK_SECONDS = 0.5;

    /**
     * The key of the one synthetic child this widget declares, the trailing coupled button.
     *
     * <p>A constant, because there is exactly one such button and it stands for the same thing
     * across every {@link #setTrailingButton} call: replacing its icon or its action is a rename
     * and not a re-key, so an assistive technology holding the node keeps holding it.
     */
    private static final long TRAILING_BUTTON = 1L;

    protected final TextEditModel model = new TextEditModel(true);
    private I18nString placeholder = I18nString.EMPTY;
    private Consumer<String> onChange = text -> {
    };
    /**
     * {@code < 0} means "unset": {@link #onMeasure} falls back to the resolved step's
     * {@code fieldWidth}. A step cannot be read in a field initializer: the widget has no
     * parent yet and would latch the process default forever.
     */
    private float preferredWidth = -1;
    private float scrollX;
    private boolean cursorVisible = true;
    // Optional in-field adornments (icons rasterize/select lazily at paint).
    private Icon leadingIcon;
    private Icon trailingIcon;
    private I18nString trailingName;
    /** Whether each icon turns around in a right-to-left subtree; the application's word. */
    private Icon.Mirroring leadingMirroring = Icon.Mirroring.NEVER;
    private Icon.Mirroring trailingMirroring = Icon.Mirroring.NEVER;
    private Runnable onTrailing = () -> {
    };
    private boolean trailingHover;
    private boolean trailingArmed; // trailing button pressed, draws the press feedback
    private Validation validation = Validation.NONE;
    // Active IME composition ("preedit"): shown inline at the caret, underlined,
    // NOT part of the model; the commit later arrives as an ordinary CharEvent.
    private String preedit = "";
    private int preeditCaret;      // caret within the preedit, in chars
    private int preeditFocusStart; // char range of the focused (converting) block
    private int preeditFocusEnd;
    /** The held display line and its key; see {@link #displayLine(SizeTokens)}. */
    private ShapedText display;
    private long displayVersion = -1; // model.textVersion(); -1 forces the first build
    private Font displayFont;
    private long displayEpoch;
    private ShapedText.Direction displayBase;
    /** The held composed line and its key while an IME composition is open; see {@link #composedLine}. */
    private ShapedText composed;
    private long composedVersion = -1;
    private int composedCursor = -1;
    private String composedPreedit;
    private Font composedFont;
    private long composedEpoch = -1;
    private ShapedText.Direction composedBase;
    /** Selection boxes, reused across paints; see {@link #fillSpans}. */
    private float[] spans = new float[8];
    /** The caret box in widget-local coordinates and the scratch it is compared through. */
    private Rect caretBox;
    private final float[] caretScratch = new float[4];
    /** The string the accessible tree publishes and its key; see {@link #accessibleText()}. */
    private String accessibleText = "";
    private long accessibleTextRevision;
    private long accessibleTextVersion = -1; // model.textVersion(); -1 forces the first build
    private boolean accessibleTextComposing;
    private int accessibleTextCursor = -1;
    private String accessibleTextPreedit = "";
    /** Bumped whenever the blink phase restarts; stale scheduled toggles no-op. */
    private int blinkGeneration;
    /** Focus-ring fade: morphs the border between outline and focusRing. */
    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);

    /** An empty single-line field. */
    public TextField() {
        setFocusable(true);
        setCursor(Cursor.TEXT); // I-beam over editable text
    }

    // ------------------------------------------------------------------- API

    /** The current contents. */
    public String text() {
        return model.text();
    }

    /** Replaces the contents, clearing the selection and undo history. UI thread only. */
    public TextField setText(String text) {
        Ui.checkUiThread();
        model.setText(text);
        // Show the head of the text; onPaint re-clamps against the real width
        // (which may still be 0 here: set before the first layout pass).
        scrollX = 0;
        invalidate();
        return this;
    }

    /** Sets a fixed placeholder, shown only while the field is empty. */
    public TextField setPlaceholder(String newPlaceholder) {
        return setPlaceholder(I18nString.literal(
                Objects.requireNonNull(newPlaceholder, "newPlaceholder")));
    }

    /**
     * Sets a placeholder that follows the UI language. A subclass shipping a default
     * one (see {@code SearchField}) simply calls this in its constructor: an
     * application's own {@code setPlaceholder} replaces the value, so there is no
     * "did the app override it" state to track.
     */
    public TextField setPlaceholder(I18nString newPlaceholder) {
        Ui.checkUiThread();
        this.placeholder = Objects.requireNonNull(newPlaceholder, "newPlaceholder");
        invalidate();
        return this;
    }

    /** The placeholder as it currently reads. */
    public String placeholder() {
        return placeholder.get();
    }

    /** Called with the full text after every edit, typed or programmatic. */
    public TextField onChange(Consumer<String> listener) {
        Ui.checkUiThread();
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** Inserts {@code text} at the cursor (replacing any selection), as if typed. UI thread. */
    public TextField insertText(String text) {
        Ui.checkUiThread();
        if (text == null || text.isEmpty()) {
            return this;
        }
        model.insert(text);
        fireChange();
        ensureCursorVisible();
        resetBlink();
        invalidate();
        return this;
    }

    /** Overrides the step's {@code fieldWidth}; a negative value restores it. */
    public TextField setPreferredWidth(float width) {
        Ui.checkUiThread();
        this.preferredWidth = width;
        markNeedsLayout();
        return this;
    }

    /**
     * A leading icon inside the field, tinted to the muted text color ({@code null} clears).
     * Drawn as authored whichever way the field reads; use
     * {@link #setLeadingIcon(Icon, Icon.Mirroring)} for an icon that means a direction.
     */
    public TextField setLeadingIcon(Icon icon) {
        return setLeadingIcon(icon, Icon.Mirroring.NEVER);
    }

    /**
     * A leading icon that says whether it turns around when the interface does. Only the code that
     * placed an icon knows whether its arrow means "back" or "download", which is why this is a
     * flag here and never a classification inside the toolkit.
     */
    public TextField setLeadingIcon(Icon icon, Icon.Mirroring mirroring) {
        Ui.checkUiThread();
        this.leadingIcon = icon;
        this.leadingMirroring = Objects.requireNonNull(mirroring, "mirroring");
        markNeedsLayout();
        return this;
    }

    /**
     * A trailing coupled button (icon + action) inside the field, the caret/arrow
     * region idiom of {@link ComboBox}. {@code icon == null} removes it.
     *
     * <p>The button it makes has <b>no name</b>. It is an operable control, so the accessible tree
     * cannot leave it out, and an icon and a callback carry nothing to announce it by; use
     * {@link #setTrailingButton(Icon, I18nString, Runnable)} for a button a screen reader can name.
     */
    public TextField setTrailingButton(Icon icon, Runnable action) {
        return setTrailingButton(icon, action, Icon.Mirroring.NEVER);
    }

    /**
     * A trailing coupled button whose icon says whether it turns around when the interface does;
     * see {@link #setLeadingIcon(Icon, Icon.Mirroring)} for why the toolkit does not decide that.
     *
     * <p>The button it makes has no name; see {@link #setTrailingButton(Icon, Runnable)}.
     */
    public TextField setTrailingButton(Icon icon, Runnable action, Icon.Mirroring mirroring) {
        return setTrailingButton(icon, null, action, mirroring);
    }

    /**
     * A trailing coupled button with a name: what it does, said in words, for whoever cannot see
     * the icon.
     *
     * <p>The other two overloads take an icon and a callback and have nowhere to put one, so the
     * button they make is an operable control the tree publishes unnamed — which the gallery check
     * refuses for any scene that uses it, and rightly.
     *
     * @param icon   the glyph, or {@code null} to remove the button
     * @param name   what the button does, following the UI language; {@code null} leaves it unnamed
     * @param action what to run when it is pressed; {@code null} for nothing
     * @return this field
     */
    public TextField setTrailingButton(Icon icon, I18nString name, Runnable action) {
        return setTrailingButton(icon, name, action, Icon.Mirroring.NEVER);
    }

    /**
     * A named trailing button whose icon says whether it turns around when the interface does.
     *
     * @param icon      the glyph, or {@code null} to remove the button
     * @param name      what the button does; {@code null} leaves it unnamed
     * @param action    what to run when it is pressed; {@code null} for nothing
     * @param mirroring whether the icon turns around in a right-to-left interface
     * @return this field
     * @throws NullPointerException if {@code mirroring} is {@code null}
     */
    public TextField setTrailingButton(Icon icon, I18nString name, Runnable action,
                                       Icon.Mirroring mirroring) {
        Ui.checkUiThread();
        this.trailingIcon = icon;
        this.trailingName = name;
        this.trailingMirroring = Objects.requireNonNull(mirroring, "mirroring");
        this.onTrailing = action != null ? action : () -> {
        };
        markNeedsLayout();
        return this;
    }

    /**
     * @return what the trailing button does, said in words, or {@code null} when it has no name or
     *         there is no trailing button
     */
    public I18nString trailingButtonName() {
        return trailingIcon == null ? null : trailingName;
    }

    /** Sets the validation state; colors the border danger/warning/success. */
    public TextField setValidation(Validation state) {
        Ui.checkUiThread();
        this.validation = Objects.requireNonNull(state, "state");
        invalidate();
        return this;
    }

    /** Convenience: {@code ERROR} when true, {@code NONE} when false. */
    public TextField setError(boolean error) {
        return setValidation(error ? Validation.ERROR : Validation.NONE);
    }

    /** The current validation state, which drives the border and helper colours. */
    public Validation validation() {
        return validation;
    }

    /** @return the editing model (cursor/selection state), for tests and subclasses */
    public TextEditModel model() {
        return model;
    }

    // -------------------------------------------------------- display hooks

    /** Whether copy/cut may export the content (password fields say no). */
    protected boolean allowClipboardCopy() {
        return true;
    }

    /**
     * The display form of this field's content as a shaped line: the <b>one</b> place a subclass
     * changes what is drawn and how wide it is.
     *
     * <p><b>Its index space is the model's.</b> {@link ShapedText#caretX},
     * {@link ShapedText#hitTest} and {@link ShapedText#selection(int, int, float[]) selection} on
     * the returned value take and return offsets into {@code text}, with no translation anywhere in
     * this component. That is what <em>deletes</em> the prefix arithmetic the caret used to rest on
     * rather than moving it one layer down, and it is the whole contract: an override that returns
     * a line shaped from some other string, with its own boundaries, puts the caret on a neighbour
     * of the character it edits and a click one mark away from the pointer. An override that
     * substitutes marks keeps {@code text} as the returned value's {@link ShapedText#text()} and
     * changes only the geometry — {@link ShapedText#uniform} builds exactly that, from one
     * multiplication, with no glyphs and without the content reaching a shaper at all.
     *
     * <p>{@code font} is the font this line must be shaped for, and it is half the key the held
     * value is refreshed against, so an override that shapes in some <em>other</em> font makes that
     * value lie about when it is stale. There is deliberately no {@link SizeTokens} parameter: the
     * font is the only thing in that row a display line can legitimately depend on, and two ways to
     * reach it is one too many.
     *
     * <p>An override whose display form depends on its own state — a reveal toggle — must call
     * {@link #invalidateDisplayLine()} when that state changes: the key is text, font and ruler
     * epoch, and it cannot see a field it does not know about.
     *
     * @param text the model's text, which is the index space of the result
     * @param font the font the line is drawn in
     * @return the shaped display line; never null
     */
    protected ShapedText shapeDisplay(String text, Font font) {
        return shapeText(text, font);
    }

    /**
     * Draws a display line with its left edge at {@code x}: the paint-side twin of
     * {@link #shapeDisplay}. {@code metrics} is passed rather than only the baseline so an override
     * can place ink against the same band the caret and the selection fill use: the ink box starts
     * at {@code baseline - metrics.ascent()} and is {@code metrics.height()} tall.
     *
     * @param canvas   where to draw
     * @param display  the shaped display line, as {@link #shapeDisplay} produced it
     * @param x        left edge of the line, in this widget's coordinates
     * @param baseline the text baseline, in this widget's coordinates
     * @param metrics  the line's vertical band
     * @param t        the size row resolved for this pass
     * @param ink      the colour to draw in
     */
    protected void paintDisplayText(Canvas canvas, ShapedText display, float x, float baseline,
                                    TextMetrics metrics, SizeTokens t, Color ink) {
        // TRAP: a display line carries its own CONTENT as text() -- it has to, because the model's
        // indices have to be its indices -- so a subclass that substitutes marks for a secret hands
        // this method the secret with instructions not to typeset it. This is the only call in the
        // component that gives a display line to the canvas, and that is the whole of the
        // guarantee: an override that paints its own marks and does not delegate here cannot leak.
        canvas.drawText(display, x, baseline, ink);
    }

    /**
     * The display line, re-shaped only when one of its inputs actually changed.
     *
     * <p>Keyed on {@link TextEditModel#textVersion()} and <b>not</b> through
     * {@link ShapedText#matches}, which is the idiom for a widget that holds a {@code String}:
     * {@code model.text()} builds a fresh {@code String} on every call, so {@code matches} would
     * miss its identity fast path and pay a full character scan on every paint, every blink and
     * every frame of a drag. The version is the model's own answer to "did the text change", it is
     * one comparison, and a caret move does not bump it. The other two parts of staleness are the
     * ones {@code matches} would have tested anyway: the {@link Font} (a value, so a control-size
     * step or a theme change is caught here with no extra machinery) and the ruler's
     * {@linkplain TextRuler#epoch() epoch}, which covers every input this widget cannot see.
     */
    private ShapedText displayLine(SizeTokens t) {
        Font f = t.body();
        TextRuler ruler = textRuler();
        long version = model.textVersion();
        // The paragraph direction is part of the key because this cache is hand-written and
        // never asks ShapedText.matches: the direction a line was shaped for is not recoverable
        // from the version, the font or the epoch, and a line shaped for the other one is wrong
        // by a fraction of a point in every geometry query asked of it.
        ShapedText.Direction base = neutralBase();
        if (display == null || displayVersion != version || !f.equals(displayFont)
                || displayEpoch != ruler.epoch() || displayBase != base) {
            display = shapeDisplay(model.text(), f);
            displayVersion = version;
            displayFont = f;
            displayEpoch = ruler.epoch();
            displayBase = base;
        }
        return display;
    }

    /**
     * Entry-point form: resolves the step once for callers that have no tokens in hand.
     *
     * <p>Package-private rather than private because a subclass that substitutes marks has to read
     * this line to describe itself: the caret stop table is where the number of marks and the
     * mapping between a model offset and a mark offset both come from, and there is no second
     * expression of either. One reader and not three narrow ones — a count, a forward map, an
     * inverse map — because each of those would resolve the step again, and a describe hook needs
     * all three answers off the same line.
     *
     * <p><b>TRAP: the value carries the CONTENT as its {@link ShapedText#text()}</b>, because the
     * model's indices have to be its indices, so on a masked field this line <em>is</em> the
     * secret. It is read for its caret stops and never for its text, exactly as
     * {@link #paintDisplayText} is the one call that may hand it to a canvas.
     */
    ShapedText displayLine() {
        return displayLine(Theme.current().tokensFor(this));
    }

    /**
     * {@link ShapedText#selection(int, int, float[])} into {@link #spans}, grown to the exact
     * bound the value states. The buffer form and not the list: a selection drag repaints at frame
     * rate, and the list form puts a list and a record per box on the floor each time. The sizing
     * lives here rather than beside the held line because the call throws on a short buffer, and a
     * guarantee written one screen away from the call it guards is one an edit can separate.
     *
     * @return how many boxes were written; box {@code i} is {@code spans[2i] .. spans[2i + 1]}
     */
    private int fillSpans(ShapedText line, int from, int to) {
        int need = 2 * line.runs().size();
        if (spans.length < need) {
            spans = new float[need];
        }
        return line.selection(from, to, spans);
    }

    /**
     * Drops the held display line, so the next paint rebuilds it through {@link #shapeDisplay}.
     *
     * <p>For a subclass whose display form depends on state of its own: the held value is refreshed
     * against the text, the font and the ruler's epoch, and none of those changes when a reveal
     * toggle does.
     */
    protected final void invalidateDisplayLine() {
        display = null;
    }

    /**
     * The composed line: the committed text with the IME's preedit spliced in at the caret, shaped
     * <b>once</b>.
     *
     * <p>Once, because three measurements of three pieces are three wrong numbers under a shaper.
     * Arabic and Indic join across exactly the seams the splice cuts, so the preedit's width inside
     * this line is not its width measured alone, and the committed tail does not begin where the
     * preedit's own advance ends. One shaping is the only form that is right, and the multi-box
     * {@link ShapedText#selection(int, int) selection} turns out to be precisely the primitive the
     * underline and the converting block's highlight need — asked of a sub-range of the same
     * shaping, so neither can drift from the run it sits in.
     *
     * <p>Keyed on the five things it is built from, and <b>not</b> through
     * {@link ShapedText#matches}, for the same reason {@link #displayLine} is not: this value is
     * derived from the model, so testing it by content means splicing the composed string on every
     * call merely to discover it is unchanged &mdash; and the callers are the scroll clamp,
     * {@link #caretRect()} and the paint, which between them run on every blink of a composing
     * field. The key answers the question without building anything.
     *
     * <p>The preedit is compared by <b>identity</b>: this widget is the only writer of that field
     * and it writes the {@code String} the event carried, so a hit needs the very same object and
     * therefore the very same characters. It can only rebuild more often than strictly necessary,
     * which is the cheap direction to be wrong in.
     */
    private ShapedText composedLine(SizeTokens t) {
        Font f = t.body();
        TextRuler ruler = textRuler();
        long version = model.textVersion();
        long epoch = ruler.epoch();
        int cursor = model.cursor();
        ShapedText.Direction neutral = neutralBase();
        if (composed == null || composedVersion != version || composedCursor != cursor
                || composedPreedit != preedit || !f.equals(composedFont) || composedEpoch != epoch
                || composedBase != neutral) {
            String committed = model.text();
            int c = Math.min(cursor, committed.length());
            String spliced = committed.substring(0, c) + preedit + committed.substring(c);
            composed = ruler.shape(spliced, f, ShapedText.Direction.of(spliced, neutral));
            composedVersion = version;
            composedCursor = cursor;
            composedPreedit = preedit;
            composedFont = f;
            composedEpoch = epoch;
            composedBase = neutral;
        }
        return composed;
    }

    /**
     * Where the caret sits on the composed line: inside the preedit, at its own caret.
     *
     * <p>The index alone, and the side is {@link ShapedText.Affinity#UPSTREAM} wherever this is
     * used — which is not arbitrary: the preedit caret TRAILS the text just typed, so the next
     * character of the same script appears where the caret is drawn. The pair used to be minted
     * here as a {@link ShapedText.Position}, which put one object on the floor per blink of a
     * composing field and one more per damaged frame once the accessible tree started asking.
     */
    private int composedCaretIndex() {
        return Math.min(model.cursor(), model.length())
                + Math.min(preeditCaret, preedit.length());
    }

    // ----------------------------------------------------------- measurement

    /**
     * Top of the text <em>ink</em> box: the single vertical anchor for the baseline, the
     * caret, the selection band, the preedit underlines and {@link #caretRect()}. Before this
     * existed the baseline was centred while everything else was placed from {@code padV},
     * two expressions that only agreed at MEDIUM: at the steps where the height floor binds
     * the locked 1&nbsp;pt {@code INK_BLEED} silently became 0.03–1.97&nbsp;pt and the preedit
     * underline landed inside the descender ink.
     */
    private float textTop(TextMetrics metrics) {
        return (height() - metrics.height()) / 2;
    }

    /**
     * Space reserved before the text starts, on the side reading starts from: the pad and any
     * leading icon. A magnitude and not a coordinate, which is what lets it stay one expression
     * in both directions; {@link #contentLeft} turns it into an x.
     */
    private float leadingInset(SizeTokens t) {
        return t.fieldPadH() + (leadingIcon == null ? 0 : t.fieldIcon() + t.gapIcon());
    }

    /**
     * The coupled trailing button's extent: one expression for the painted region <em>and</em>
     * the hit region, so the two can never drift apart.
     *
     * <p>No {@code MIN_HIT_TARGET} clamp here, deliberately: {@code fieldTrailing} is the
     * control height at every step, so it is already 24 at its smallest. {@link Strokes} and
     * {@link limn.scene.ControlSize} both enumerate the surviving clamp sites exhaustively
     * ({@code MenuBar.titleWidth} and {@code SegmentedControl}'s segment width, and nothing
     * else), and a third one here, however harmless, would make those statements false. That
     * enforcement is meant to be structural rather than editorial.
     */
    private float trailingWidth(SizeTokens t) {
        return t.fieldTrailing();
    }

    /**
     * Space reserved after the text ends, on the side reading ends on: the pad, or the whole
     * trailing button region when there is one. A magnitude, for {@link #leadingInset}'s reason.
     */
    private float trailingInset(SizeTokens t) {
        return trailingIcon == null ? t.fieldPadH() : trailingWidth(t);
    }

    /** Whether this field reads right to left. Resolve it once per pass. */
    private boolean isRtl() {
        return layoutDirection() == LayoutDirection.RTL;
    }

    /**
     * Physical left edge of the text area: the leading inset reading left to right, and the
     * trailing one reading right to left. Every coordinate in this widget is composed from this
     * and {@link #innerWidth}, so the leading icon, the trailing button, the clip, the caret and
     * the hit test cannot disagree about which side is which.
     */
    private float contentLeft(SizeTokens t) {
        return isRtl() ? trailingInset(t) : leadingInset(t);
    }

    /**
     * Where a shaped line of {@code lineWidth} puts its <b>left</b> edge, which is what
     * {@code drawText} places against.
     *
     * <p>Reading left to right the line's leading edge is its left one, so it starts at the
     * content's left edge and {@code scrollX} pulls it back. Reading right to left the leading
     * edge is the right one, so the line is pushed out until its right edge meets the content's
     * right edge, and {@code scrollX} pushes it further. Same convention as a horizontal scroll:
     * zero is the leading edge, and the offset is a distance travelled.
     */
    private float originX(SizeTokens t, float lineWidth) {
        float left = contentLeft(t);
        return isRtl() ? left + innerWidth(t) - lineWidth + scrollX : left - scrollX;
    }

    /**
     * Where the visible window starts <b>in line coordinates</b>: what {@code scrollX} means once
     * the direction has been applied. It grows with {@code scrollX} in one direction and shrinks
     * with it in the other, which is the whole of what {@link #ensureCursorVisible} has to know.
     */
    private float viewStart(SizeTokens t, float lineWidth) {
        return contentLeft(t) - originX(t, lineWidth);
    }

    /** Physical left edge of the trailing button's region; it sits on the trailing side. */
    private float trailingRegionX(SizeTokens t) {
        return isRtl() ? 0 : width() - trailingWidth(t);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        TextMetrics metrics = textRuler().measure("Hg", t.body());
        return constraints.constrain(preferredWidth >= 0 ? preferredWidth : t.fieldWidth(),
                t.resolvedHeight(metrics.lineHeight()));
    }

    @Override
    protected float baselineOffset() {
        TextMetrics metrics = textRuler().measure("Hg", Theme.current().tokensFor(this).body());
        return textTop(metrics) + metrics.ascent();
    }

    private float innerWidth(SizeTokens t) {
        return Math.max(0, width() - leadingInset(t) - trailingInset(t));
    }

    /**
     * The caret position a click at display-x {@code px} asks for, side included.
     *
     * <p>Side included because an index alone is a caret that jumps the next time it moves: on a
     * direction boundary one index is two points on the line, and which of them the click meant is
     * the thing {@link ShapedText#hitTest} resolved and the thing this hands to the model. It also
     * never has to snap the answer to a grapheme boundary, the way a search over code-point offsets
     * did: caret stops are the shaper's own cluster boundaries, and there is nothing between them
     * to land in.
     */
    private ShapedText.Position positionAt(float px, SizeTokens t) {
        ShapedText line = displayLine(t);
        // Empty space to the right of the line means the LOGICAL end of the line. On a line that
        // ends in the direction opposite the paragraph's, the nearest cluster to the right edge is
        // not the last character, so hitTest's clamp -- which is what a drag past the end wants --
        // is wrong exactly here.
        return px > line.metrics().width()
                ? new ShapedText.Position(model.length(), ShapedText.Affinity.UPSTREAM)
                : line.hitTest(px);
    }

    /**
     * The display-x on the live line under a click at this widget's local {@code lx}: the inverse
     * of {@link #originX}, and the only place a pointer coordinate becomes a line coordinate.
     */
    private float displayX(float lx, SizeTokens t) {
        float lineWidth = (preedit.isEmpty() ? displayLine(t) : composedLine(t))
                .metrics().width();
        return lx - originX(t, lineWidth);
    }

    /** Entry-point form: resolves the step once for callers that have no tokens in hand. */
    private void ensureCursorVisible() {
        ensureCursorVisible(Theme.current().tokensFor(this));
    }

    private void ensureCursorVisible(SizeTokens t) {
        if (width() <= 0) {
            return; // not laid out yet; onPaint clamps once real bounds exist
        }
        float cursorX = caretDisplayX(t); // includes any in-progress composition
        float inner = innerWidth(t);
        float lineWidth = (preedit.isEmpty() ? displayLine(t) : composedLine(t))
                .metrics().width();
        // Where the window onto the line currently starts, and where it would have to start for
        // the caret to be inside it. Both are line coordinates, so the arithmetic is the one this
        // always had; only turning the answer back into a scroll offset knows a direction.
        float view = viewStart(t, lineWidth);
        float wanted = view;
        // The two CLIP_CLEARANCE terms must stay identical or the caret oscillates per keystroke.
        if (cursorX - wanted > inner - Strokes.CLIP_CLEARANCE) {
            wanted = cursorX - inner + Strokes.CLIP_CLEARANCE;
        }
        if (cursorX < wanted) {
            wanted = cursorX;
        }
        if (wanted != view) {
            scrollX = isRtl() ? lineWidth - inner - wanted : wanted;
        }
        clampScrollX(t);
    }

    /**
     * Keeps scrollX within [0, overflow] against the CURRENT width. The range is the same in both
     * layout directions: {@code scrollX} is a distance travelled from the leading edge and never
     * a coordinate, so only the translation that consumes it knows which edge that is.
     */
    private void clampScrollX(SizeTokens t) {
        // The composed line's width already includes the preedit, and includes it correctly: a
        // separately measured preedit added to a separately measured committed line counts the
        // splice's own joining twice, once wrongly at each seam.
        float content = (preedit.isEmpty() ? displayLine(t) : composedLine(t)).metrics().width();
        float overflow = Math.max(0, content - innerWidth(t));
        scrollX = Math.max(0, Math.min(scrollX, overflow));
    }

    /**
     * Display-x of the caret, on whichever line is live: the composed one while composing.
     *
     * <p>Through the index-and-side form of {@link ShapedText#caretX}, never the
     * {@link ShapedText.Position} one: this runs on every keystroke, on every blink and once more
     * per damaged frame for the box the accessible tree publishes, and the pair is two values the
     * model already stores apart.
     */
    private float caretDisplayX(SizeTokens t) {
        return preedit.isEmpty()
                ? displayLine(t).caretX(model.cursor(), model.caretAffinity())
                : composedLine(t).caretX(composedCaretIndex(), ShapedText.Affinity.UPSTREAM);
    }

    // ---------------------------------------------------------------- paint

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        Font f = t.body();
        TextMetrics metrics = textRuler().measure("Hg", f);

        // Resolved once for the whole pass: the leading icon, the trailing button, the clip, the
        // selection band, the text and the caret all compose from this one answer.
        boolean rtl = isRtl();

        Color fill = isEnabled() ? theme.surface : theme.disabledFill;
        canvas.fillRoundRect(0, 0, width(), height(), t.radiusMedium(), fill);
        float focus = focusFade.value();

        // Leading icon, tinted to the muted ink, inside the pad on the side reading starts from.
        if (leadingIcon != null) {
            float ico = t.fieldIcon();
            float iconX = rtl ? width() - t.fieldPadH() - ico : t.fieldPadH();
            leadingIcon.paint(canvas, iconX, (height() - ico) / 2, ico,
                    isEnabled() ? theme.textMuted : theme.disabledText, theme.dark,
                    rtl && leadingMirroring == Icon.Mirroring.IN_RTL);
        }
        // Trailing coupled button (ComboBox-caret idiom): a themed background that
        // lights up on hover and deepens on press (click feedback), then the divider
        // and the icon on top. The background fills the whole region after the
        // divider (square on the divider side, the field's own radius on the outer
        // side), so it reads as one coupled button, not a floating pill.
        if (trailingIcon != null) {
            float regionW = trailingWidth(t);
            float regionX = trailingRegionX(t);
            if (isEnabled() && (trailingHover || trailingArmed)) {
                Color bg = trailingArmed
                        ? theme.surfaceRaised.lerp(theme.text, 0.10f)
                        : theme.surfaceRaised;
                float r = t.radiusMedium();
                // Square on the divider side and rounded on the outer one, so the region reads as
                // one coupled button either way round; which side is which is the direction's.
                canvas.fillRoundRect(rtl
                        ? new RoundRect(regionX, 0, regionW, height(), r, 0, 0, r)
                        : new RoundRect(regionX, 0, regionW, height(), 0, r, r, 0), bg);
            }
            // Its own inset token: the divider is a paint coordinate, and padV is not one. It
            // stands on the region's inner edge, which is the far one reading right to left.
            float dividerX = rtl ? regionX + regionW : regionX;
            canvas.drawLine(dividerX, t.fieldDividerInset(), dividerX,
                    height() - t.fieldDividerInset(), Strokes.BORDER, theme.outline);
            float ico = t.fieldIcon();
            Color tint = !isEnabled() ? theme.disabledText
                    : (trailingHover || trailingArmed) ? theme.primary : theme.textMuted;
            trailingIcon.paint(canvas, regionX + (regionW - ico) / 2,
                    (height() - ico) / 2, ico, tint, theme.dark,
                    rtl && trailingMirroring == Icon.Mirroring.IN_RTL);
        }

        // Bounds may have changed since the last edit (resize, or setText
        // before the first layout): keep the horizontal scroll valid so the
        // text is never clipped fully out of view.
        clampScrollX(t);

        float left = contentLeft(t);
        float inner = innerWidth(t);
        canvas.save();
        canvas.clipRect(left - Strokes.AA_BLEED, 0,
                inner + 2 * Strokes.AA_BLEED, height());
        float inkTop = textTop(metrics);
        float baseline = inkTop + metrics.ascent();
        float liveWidth = (preedit.isEmpty() ? displayLine(t) : composedLine(t))
                .metrics().width();
        float originX = originX(t, liveWidth);

        boolean composing = !preedit.isEmpty();
        String hint = placeholder.get();
        // The model's own emptiness, not the display line's: a substituted display form has the
        // model's index space, so the two agree, and asking the model never touches the content.
        if (model.length() == 0 && !composing && !hint.isEmpty()) {
            // Keep the hint visible even while focused (only the caret joins it). The hint is a
            // string and not a held line, so it is shaped here for the field's own direction and
            // placed against the leading edge, which is the edge the caret is on.
            ShapedText hintLine = textRuler().shape(hint, f,
                    ShapedText.Direction.of(hint, neutralBase()));
            float hintX = rtl ? left + inner - hintLine.metrics().width() : left;
            float caretX = rtl ? left + inner : left;
            canvas.drawText(hintLine, hintX, baseline, theme.textMuted);
            if (isFocused() && cursorVisible) {
                canvas.drawLine(caretX, inkTop - Strokes.INK_BLEED,
                        caretX, inkTop + metrics.height() + Strokes.INK_BLEED,
                        Strokes.CARET, theme.text);
            }
        } else if (composing) {
            paintComposing(canvas, theme, t, metrics, originX, inkTop, baseline);
        } else {
            ShapedText line = displayLine(t);
            if (model.hasSelection() && isFocused()) {
                // N boxes, never one. A range contiguous in the string stops being contiguous on
                // the line the moment it crosses a direction boundary, and the smallest rectangle
                // covering both halves would highlight text the user did not select.
                int boxes = fillSpans(line, model.selectionStart(), model.selectionEnd());
                for (int i = 0; i < boxes; i++) {
                    float x0 = spans[i * 2];
                    canvas.fillRect(originX + x0, inkTop - Strokes.INK_BLEED, spans[i * 2 + 1] - x0,
                            metrics.height() + 2 * Strokes.INK_BLEED,
                            theme.primary.withAlpha(0.35f));
                }
            }
            Color ink = isEnabled() ? theme.text : theme.disabledText;
            paintDisplayText(canvas, line, originX, baseline, metrics, t, ink);
            if (isFocused() && cursorVisible && !model.hasSelection()) {
                // ONE caret, not the two caretAt() offers: the model stores the side, so there is
                // no ambiguity left to show -- and caretRect() describes one column, which is what
                // a blink damages, so a second mark elsewhere on the line would not be repainted.
                float cx = originX + line.caretX(model.caret());
                canvas.drawLine(cx, inkTop - Strokes.INK_BLEED,
                        cx, inkTop + metrics.height() + Strokes.INK_BLEED,
                        Strokes.CARET, theme.text);
            }
        }
        canvas.restore();

        // Focus/validation border LAST: nothing (not even the trailing-button
        // background) paints above the focus ring. ONE stroke that thickens
        // continuously as the fade runs; a ternary here deletes the animation.
        Color border = borderColor(theme, focus);
        canvas.drawRoundRect(Strokes.HALF_PIXEL_INSET, Strokes.HALF_PIXEL_INSET,
                width() - 2 * Strokes.HALF_PIXEL_INSET, height() - 2 * Strokes.HALF_PIXEL_INSET,
                t.radiusMedium(),
                Strokes.BORDER + (Strokes.FOCUS_RING - Strokes.BORDER) * focus, border);
    }

    private Color borderColor(Theme theme, float focus) {
        return switch (validation) {
            case NONE -> theme.outline.lerp(theme.focusRing, focus);
            case ERROR -> theme.danger;
            case WARNING -> theme.warning;
            case SUCCESS -> theme.success;
            case INFO -> theme.info;
        };
    }

    /**
     * Draws the composition: the committed text with the preedit spliced in at the caret, as
     * <b>one</b> shaped line, with the preedit underlined, the block being converted highlighted,
     * and the caret placed inside it.
     *
     * <p>One line, and the pieces are never measured apart. Under a shaper the committed prefix,
     * the preedit and the committed suffix join across the two seams the splice cuts — Arabic and
     * Indic do it, and the joining changes the width of every piece — so measuring the three and
     * adding them up is three wrong numbers that also disagree with what is drawn. The underline
     * and the converting block are sub-ranges of the same shaping, asked for with the same
     * multi-box {@code selection} the resting selection band uses, so neither can drift from the
     * text it marks.
     */
    private void paintComposing(Canvas canvas, Theme theme, SizeTokens t, TextMetrics metrics,
                                float originX, float inkTop, float baseline) {
        ShapedText line = composedLine(t);
        int c = Math.min(model.cursor(), model.length());
        Color ink = isEnabled() ? theme.text : theme.disabledText;
        float bandTop = inkTop - Strokes.INK_BLEED;
        float bandH = metrics.height() + 2 * Strokes.INK_BLEED;
        float underlineY = inkTop + metrics.height();
        // Asked once and read twice, and only when there IS a converting block: an empty range
        // still allocates a scratch box array inside selection(), on a path that runs per blink.
        boolean converting = preeditFocusEnd > preeditFocusStart;
        List<ShapedText.Span> focusBoxes = converting
                ? line.selection(c + preeditFocusStart, c + preeditFocusEnd)
                : List.of();

        // Highlight first, so it sits behind the ink rather than over it.
        for (ShapedText.Span s : focusBoxes) {
            canvas.fillRect(originX + s.x0(), bandTop, s.width(), bandH,
                    theme.primary.withAlpha(0.18f));
        }
        // NOT through paintDisplayText, deliberately: that seam exists for a subclass that
        // substitutes its own marks, and there is no such thing as a masked composition -- the one
        // subclass that masks refuses text input precisely so a secret never reaches an IME. Cutting
        // this line back into halves to route them through the seam would undo the single shaping.
        canvas.drawText(line, originX, baseline, ink);
        for (ShapedText.Span s : line.selection(c, c + preedit.length())) {
            canvas.drawLine(originX + s.x0(), underlineY, originX + s.x1(), underlineY,
                    Strokes.IME_UNDERLINE, theme.textMuted);
        }
        // The 1-vs-2 contrast is what says "this block is converting"; scaling either erases it.
        for (ShapedText.Span s : focusBoxes) {
            canvas.drawLine(originX + s.x0(), underlineY, originX + s.x1(), underlineY,
                    Strokes.IME_UNDERLINE_ACTIVE, theme.primary);
        }
        if (isFocused() && cursorVisible) {
            float cx = originX + caretDisplayX(t); // the same x caretRect() reports
            canvas.drawLine(cx, bandTop, cx, bandTop + bandH, Strokes.CARET, theme.text);
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void onMouseEvent(MouseEvent event) {
        SizeTokens t = Theme.current().tokensFor(this);
        float lx = sceneToLocalX(event.x());
        float ly = sceneToLocalY(event.y());
        // Bounded on both axes: a DRAG that leaves the field vertically used to keep
        // reporting "over the trailing button" because Y was ignored entirely.
        float trailingLeft = trailingRegionX(t);
        boolean overTrailing = trailingIcon != null
                && lx >= trailingLeft && lx < trailingLeft + trailingWidth(t)
                && ly >= 0 && ly < height();
        switch (event.type()) {
            case MOVE, ENTER -> {
                if (overTrailing != trailingHover) {
                    trailingHover = overTrailing;
                    setCursor(overTrailing ? Cursor.POINTER : Cursor.TEXT);
                    invalidate();
                }
            }
            case EXIT -> {
                if (trailingHover || trailingArmed) {
                    trailingHover = false;
                    trailingArmed = false;
                    setCursor(Cursor.TEXT);
                    invalidate();
                }
            }
            case RELEASE -> {
                if (trailingArmed) {
                    trailingArmed = false;
                    invalidate();
                }
            }
            case PRESS -> {
                if (ContextMenus.isRequest(event)) {
                    // Focus first: the menu's Cut and Paste act on this field, and a field that
                    // was not focused when they run would edit while the caret lives elsewhere.
                    requestFocus();
                    showContextMenu(event.x(), event.y());
                    event.consume();
                } else if (event.button() == Keys.MOUSE_LEFT) {
                    if (overTrailing) {
                        // The same guarded call an assistive technology's press reaches; the armed
                        // shade is the pointer's alone, because only a pointer sends the RELEASE
                        // that clears it.
                        if (pressTrailing()) {
                            trailingArmed = true; // press feedback until release
                            invalidate();
                        }
                    } else {
                        model.setCaret(positionAt(displayX(lx, t), t),
                                (event.modifiers() & Keys.MOD_SHIFT) != 0);
                        resetBlink();
                    }
                    event.consume();
                }
            }
            case DRAG -> {
                if (!overTrailing) {
                    model.setCaret(positionAt(displayX(lx, t), t), true);
                    ensureCursorVisible(t);
                    resetBlink();
                }
                event.consume();
            }
            default -> {
            }
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (ContextMenus.isRequest(event)) {
            event.consume();
            showContextMenuForFocus();
            return;
        }
        if (!event.isPressed()) {
            return;
        }
        int mods = event.modifiers();
        boolean shift = (mods & Keys.MOD_SHIFT) != 0;
        // Word-wise with Ctrl (Windows/Linux) or Alt/Option (macOS); Cmd jumps to the
        // line edge (macOS). Ctrl/Cmd still trigger the letter shortcuts below.
        boolean word = allowsWordJumps() && (mods & (Keys.MOD_CONTROL | Keys.MOD_ALT)) != 0;
        boolean lineEdge = (mods & Keys.MOD_SUPER) != 0;
        // Ctrl+Alt together is AltGr (Windows synthesizes LCtrl+RAlt for it), so a
        // printable AltGr combo must fall through to the char callback instead of
        // firing the Ctrl letter shortcuts; else typing 'ą' (AltGr+A) select-alls
        // and the arriving char replaces the whole field.
        boolean altGr = (mods & Keys.MOD_CONTROL) != 0 && (mods & Keys.MOD_ALT) != 0;
        boolean shortcut = (mods & Keys.MOD_SUPER) != 0
                || (!altGr && (mods & Keys.MOD_CONTROL) != 0);
        boolean handled = true;
        switch (event.key()) {
            // Left and Right are VISUAL: the keys are named for a direction on the screen, so they
            // step one cluster left or right ON THE LINE whatever direction the text under them
            // runs. Home, End and the word jumps stay LOGICAL, and in right-to-left text that means
            // Left and Ctrl+Left move the caret in opposite directions -- which is what Windows and
            // GTK do, and is forced anyway: Shift+Ctrl+Left has to produce a selection, a selection
            // is one contiguous range of the string, and the range from the caret to the visual
            // left edge of a mixed line is not one. The boolean result is ignored here: a
            // single-line field has no other line for the caret to enter.
            case Keys.LEFT -> {
                if (word) {
                    model.moveWordLeft(shift);
                } else if (lineEdge) {
                    model.moveHome(shift);
                } else {
                    model.moveVisualLeft(displayLine(), 0, shift);
                }
            }
            case Keys.RIGHT -> {
                if (word) {
                    model.moveWordRight(shift);
                } else if (lineEdge) {
                    model.moveEnd(shift);
                } else {
                    model.moveVisualRight(displayLine(), 0, shift);
                }
            }
            case Keys.HOME -> model.moveHome(shift);
            case Keys.END -> model.moveEnd(shift);
            case Keys.BACKSPACE -> fireIfChanged(word ? model::deleteWordBackward : model::backspace);
            case Keys.DELETE -> fireIfChanged(word ? model::deleteWordForward : model::deleteForward);
            case Keys.A -> {
                if (shortcut) {
                    model.selectAll();
                } else {
                    handled = false;
                }
            }
            case Keys.C -> handled = shortcut && copySelection(false);
            case Keys.X -> handled = shortcut && copySelection(true);
            case Keys.V -> {
                if (shortcut) {
                    String pasted = clipboard().get();
                    // Pasting an empty clipboard must not destroy the selection.
                    if (!pasted.isEmpty()) {
                        fireIfChanged(() -> model.insert(pasted));
                    }
                } else {
                    handled = false;
                }
            }
            case Keys.Z -> {
                if (shortcut) {
                    fireIfChanged(shift ? () -> model.redo() : () -> model.undo());
                } else {
                    handled = false;
                }
            }
            case Keys.Y -> {
                if (shortcut) {
                    fireIfChanged(() -> model.redo());
                } else {
                    handled = false;
                }
            }
            default -> handled = false;
        }
        if (handled) {
            ensureCursorVisible();
            resetBlink();
            invalidate();
            event.consume();
        }
    }

    private boolean copySelection(boolean cut) {
        if (!model.hasSelection() || !allowClipboardCopy()) {
            return false;
        }
        clipboard().set(model.selectedText());
        if (cut) {
            model.deleteSelection();
            fireChange();
        }
        return true;
    }

    @Override
    protected void onCharTyped(CharEvent event) {
        int cp = event.codepoint();
        if (cp < 0x20 || cp == 0x7F) {
            return;
        }
        model.insertCodePoint(cp);
        fireChange();
        ensureCursorVisible();
        resetBlink();
        invalidate();
        event.consume();
    }

    @Override
    protected boolean acceptsTextInput() {
        return true;
    }

    /** Whether modifier+arrow/delete may move word-wise (see {@link PasswordField}). */
    protected boolean allowsWordJumps() {
        return true;
    }

    /** @return the text currently being composed by the IME (empty when not composing) */
    public String composingText() {
        return preedit;
    }

    @Override
    protected void onPreedit(PreeditEvent event) {
        preedit = event.text();
        preeditCaret = codePointToChar(preedit, event.caret());
        computeFocusedBlock(event);
        ensureCursorVisible();
        resetBlink();
        invalidate();
        event.consume();
    }

    /** Resolves the focused block's code-point range to a char range within the preedit. */
    private void computeFocusedBlock(PreeditEvent event) {
        preeditFocusStart = 0;
        preeditFocusEnd = 0;
        int[] blocks = event.blockSizes();
        int focused = event.focusedBlock();
        if (focused < 0 || focused >= blocks.length) {
            return;
        }
        int cpStart = 0;
        for (int i = 0; i < focused; i++) {
            cpStart += blocks[i];
        }
        preeditFocusStart = codePointToChar(preedit, cpStart);
        preeditFocusEnd = codePointToChar(preedit, cpStart + blocks[focused]);
    }

    /** @return the char offset in {@code text} of code-point index {@code cpIndex} */
    private static int codePointToChar(String text, int cpIndex) {
        if (cpIndex <= 0) {
            return 0;
        }
        int total = text.codePointCount(0, text.length());
        return cpIndex >= total ? text.length() : text.offsetByCodePoints(0, cpIndex);
    }

    /**
     * The caret's box in this widget's <b>own</b> coordinates, written into {@code out} as x, y,
     * width, height.
     *
     * <p>Split out of {@link #caretRect()} because the accessible tree asks for the same rectangle
     * on every damaged frame of the one widget whose blink guarantees a damaged frame twice a
     * second, and the whole-{@code Rect} form cannot be asked that often: it produces a box. This
     * writes into a buffer instead, so the caller can compare four floats against the four it is
     * holding and produce nothing when the caret has not moved.
     *
     * @param out four floats to write x, y, width and height into
     * @return whether there is a caret box at all; {@code false} before the first layout
     */
    private boolean caretBoxLocal(float[] out) {
        if (width() <= 0) {
            return false; // not laid out yet
        }
        // Its own resolve: the scene also calls this from the async blink chain, where there
        // is no enclosing measure/paint pass to thread tokens down from.
        SizeTokens t = Theme.current().tokensFor(this);
        TextMetrics metrics = textRuler().measure("Hg", t.body());
        float left = contentLeft(t);
        float liveWidth = (preedit.isEmpty() ? displayLine(t) : composedLine(t))
                .metrics().width();
        float localX = originX(t, liveWidth) + caretDisplayX(t);
        out[0] = Math.max(left, Math.min(localX, left + innerWidth(t)));
        out[1] = textTop(metrics) - Strokes.INK_BLEED;
        out[2] = Strokes.CARET;
        out[3] = metrics.height() + 2 * Strokes.INK_BLEED;
        return true;
    }

    /**
     * The held caret box, in this widget's own coordinates: the one value the IME's rectangle and
     * the accessible tree's are both built from, refreshed only when one of its four floats moves.
     *
     * <p>That the two come from here rather than from two expressions is the invariant worth
     * pinning. An assistive technology draws its cursor around the box this hands over and the
     * platform places a candidate window under the box {@link #caretRect()} hands over, and a field
     * whose two answers drifted would put them in different places on the same line.
     *
     * <p>Package-private for the same reason it is held: a subclass that publishes the text facet
     * itself hands over this rectangle as one of that facet's arguments, and a second caller
     * strengthens the invariant rather than weakening it. What it answers on a masked field is
     * already mask geometry, because it is measured from the display line the mask replaced.
     *
     * @return the box, or {@code null} before the first layout
     */
    Rect heldCaretBox() {
        if (!caretBoxLocal(caretScratch)) {
            caretBox = null;
            return null;
        }
        if (caretBox == null || caretBox.x() != caretScratch[0] || caretBox.y() != caretScratch[1]
                || caretBox.width() != caretScratch[2]
                || caretBox.height() != caretScratch[3]) {
            caretBox = new Rect(caretScratch[0], caretScratch[1],
                    caretScratch[2], caretScratch[3]);
        }
        return caretBox;
    }

    @Override
    protected Rect caretRect() {
        Rect local = heldCaretBox();
        return local == null ? null
                : new Rect(localToSceneX() + local.x(), localToSceneY() + local.y(),
                        local.width(), local.height());
    }

    /**
     * Raises the Cut/Copy/Paste/Select All menu at a point in this widget's own coordinates.
     *
     * <p>Protected so a subclass with different clipboard rules can suppress or replace it;
     * {@link PasswordField} does not need to, because {@link #allowClipboardCopy()} already greys
     * the two rows that would let a secret out.
     */
    protected void showContextMenu(float localX, float localY) {
        TextContextMenu.showAt(this, contextMenuHost(), localX, localY);
    }

    /**
     * Raises the same menu for a request that carries no point: the Menu key or Shift+F10,
     * where the caret is the only place the user can have meant. Protected for the same
     * reason as its pointer twin, and a subclass suppressing one has to suppress both.
     */
    protected void showContextMenuForFocus() {
        // At the caret, not at a corner of the box: on a wide field the two are far apart, and
        // the caret is the only place the request can have meant.
        Rect caret = caretRect();
        showContextMenu(caret.x() - localToSceneX(),
                caret.y() + caret.height() - localToSceneY());
    }

    private TextContextMenu.Host contextMenuHost() {
        return new TextContextMenu.Host() {
            @Override
            public boolean hasSelection() {
                return model.hasSelection();
            }

            @Override
            public boolean isEditable() {
                return isEnabled();
            }

            @Override
            public boolean allowsCopy() {
                return allowClipboardCopy();
            }

            @Override
            public boolean isEmpty() {
                return model.text().isEmpty();
            }

            @Override
            public String clipboardText() {
                return clipboard().get();
            }

            @Override
            public void cut() {
                copySelection(true);
                ensureCursorVisible(Theme.current().tokensFor(TextField.this));
                invalidate();
            }

            @Override
            public void copy() {
                copySelection(false);
            }

            @Override
            public void paste() {
                String pasted = clipboard().get();
                if (!pasted.isEmpty()) {
                    fireIfChanged(() -> model.insert(pasted));
                    ensureCursorVisible(Theme.current().tokensFor(TextField.this));
                    invalidate();
                }
            }

            @Override
            public void selectAll() {
                model.selectAll();
                invalidate();
            }
        };
    }

    /** Notifies the change listener with the current text; for subclasses that edit the model directly. */
    protected void fireChange() {
        onChange.accept(model.text());
    }

    /** Runs {@code edit} and fires onChange only if the text actually changed. */
    private void fireIfChanged(Runnable edit) {
        String before = model.text();
        edit.run();
        if (!before.equals(model.text())) {
            fireChange();
        }
    }

    // -------------------------------------------------------- accessibility

    /**
     * The string the accessible tree publishes, and the counter that says when it moved.
     *
     * <p><b>{@link TextEditModel#textVersion()} alone is the wrong witness here</b>, which is the
     * one thing about this widget a survey written from the outside could not have known. A preedit
     * does not touch the model, so a composing field keyed on the model's counter would publish the
     * string it had before the composition opened and would go on publishing it for the whole
     * composition, with a caret offset pointing inside text the tree does not carry. So the widget
     * holds its own string and its own counter, and the counter moves when <em>this</em> string
     * does.
     *
     * <p>While composing the string is the composed line's own {@link ShapedText#text()} — the very
     * String the single shaping was built from, not a second splice of the same pieces — so the
     * tree and the pixels cannot describe different text. The preedit is compared by identity, as
     * {@link #composedLine} compares it and for the same reason: this widget is the only writer of
     * that field and writes the String the event carried, so the comparison can only rebuild more
     * often than strictly necessary, which is the cheap direction to be wrong in.
     *
     * <p><b>The counter moves when the string does and not when the rebuild does</b>, which is one
     * {@code equals} on the rebuild path and is what the tree means by a witness. Bumping it on
     * every rebuild is correct and not enough: {@link TextEditModel#setText} bumps the model's
     * counter for a value equal to the one already there, which a widget bound to a colour or a
     * number writes back on every change of it, and the tree would then publish a whole snapshot
     * per such write while reporting no event, since the difference compares the strings before it
     * reports one. A neighbouring widget's own promise that a damaged frame says nothing is what
     * found it.
     *
     * <p>Package-private, so a subclass that publishes the facet itself takes <em>this</em>
     * reference rather than calling {@link TextEditModel#text()} again: that call builds a fresh
     * {@code String} every time, and on a masked field a second copy of the value is a second copy
     * of the secret. {@link #accessibleTextRevision()} is the counter that goes with it, and this
     * call is what refreshes both.
     *
     * @return the text as a reader is told it, held across frames
     */
    String accessibleText() {
        boolean composing = !preedit.isEmpty();
        long version = model.textVersion();
        int cursor = model.cursor();
        // The cursor is part of the key only while composing, and it has to be: the preedit is
        // spliced in AT the cursor, so moving the caret through a composition moves the same
        // characters to another place in the same string.
        if (accessibleTextVersion != version || accessibleTextComposing != composing
                || (composing && (accessibleTextPreedit != preedit
                        || accessibleTextCursor != cursor))) {
            String rebuilt = composing
                    ? composedLine(Theme.current().tokensFor(this)).text()
                    : model.text();
            accessibleTextVersion = version;
            accessibleTextComposing = composing;
            accessibleTextCursor = cursor;
            accessibleTextPreedit = preedit;
            if (!rebuilt.equals(accessibleText)) {
                accessibleText = rebuilt;
                accessibleTextRevision++;
            }
        }
        return accessibleText;
    }

    /**
     * The counter {@link #accessibleText()} was last rebuilt against: the witness that string is
     * handed to the tree with.
     *
     * <p><b>Ask {@link #accessibleText()} first</b>, because that call is what refreshes both. Read
     * on its own this answers the counter of the string the last call produced, which is the
     * previous one whenever the text has moved since.
     *
     * @return the witness, moving only when the string does
     */
    long accessibleTextRevision() {
        return accessibleTextRevision;
    }

    /**
     * One text field: what it is, what is in it, where the caret and the selection are, and — when
     * there is one — the trailing button as a child node of its own.
     *
     * <p>The name is the placeholder, handed over as the {@link I18nString} this widget holds and
     * never as a resolved string, and an unset one is {@link I18nString#EMPTY}, which names nothing
     * and leaves the walk's tooltip default somewhere to go. The order that falls out is the right
     * one and costs no application a line: an explicit name wins, then a bound {@code Label}'s
     * caption, then the placeholder — the field's own visible word for what goes in it — and then
     * the tooltip, which becomes the description instead when the placeholder already named the
     * node.
     *
     * <p>No description of its own. The validation message beside a field is a {@code Label} the
     * application owns and this widget holds no string for it; what {@link #setValidation} holds is
     * a colour.
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.TEXT_FIELD);
        a.name(placeholder, Accessible.NameFrom.PLACEHOLDER);
        // Always editable, and never read-only: this class has no read-only mode, and a disabled
        // field is still an editable control that happens to be disabled. Conflating the two tells
        // a reader the text can never be typed into, which is a different and false fact.
        a.state(Accessible.State.EDITABLE);
        // ERROR alone, and not "validation() != NONE": SUCCESS is the valid-input state, and
        // publishing INVALID there would announce a field that just passed as failing. WARNING and
        // INFO carry no bit either -- the platform flag is a boolean, so folding this widget's
        // four-way vocabulary into it loses the distinction the vocabulary exists to draw, and the
        // message that draws it is an application Label reachable through a description or a
        // declared relation.
        a.state(Accessible.State.INVALID, validation == Validation.ERROR);
        // The field raises the Cut/Copy/Paste/Select All menu from the pointer and from the
        // keyboard, so the node owes both halves of that fact. Without them a reader has no route
        // to any of those four operations at all.
        a.state(Accessible.State.HAS_POPUP);
        // The single-argument form; the variable-argument one allocates an array per call. FOCUS
        // and SCROLL_INTO_VIEW arrive free from the walk. No PRESS: pressing a text field is not an
        // activation, and the caret placement a click performs has no verb in the vocabulary.
        a.action(Accessible.Action.SHOW_MENU);
        publishText(a);
        if (trailingIcon != null) {
            SizeTokens t = Theme.current().tokensFor(this);
            a.child(TRAILING_BUTTON);
            // The hit region's own two expressions, which are the painted region's: the icon's
            // centred square is smaller than what a click lands in, and a node whose box is the
            // glyph would send a platform hit test past the edges of a control it can operate.
            // They mirror, so the button is at x = 0 in a right-to-left subtree.
            a.bounds(trailingRegionX(t), 0, trailingWidth(t), height());
            a.role(Accessible.Role.BUTTON);
            // By source, and null when the unnamed overloads made it: an operable control is never
            // deleted, so it publishes nameless rather than not at all, which is what makes the
            // omission visible instead of silent.
            a.name(trailingName, Accessible.NameFrom.CONTENT);
            a.action(Accessible.Action.PRESS);
            a.endChild();
            // Not focusable, and that is the truth rather than an omission: Tab steps over this
            // button and only the pointer reaches it. Enabled, visible and showing are written onto
            // it by the walk, from the field's own.
        }
        // The leading icon is not a node. Its setter takes a glyph and a mirroring flag, there is
        // nothing to name it by and no operation behind it, so it is decoration.
    }

    /**
     * The text facet: the contents, the caret with its side, the selection, and the caret's box.
     *
     * <p><b>Nothing is published at all when the content may not leave the widget.</b>
     * {@link #allowClipboardCopy()} is already the widget's own answer to that question — a
     * password field says no until it is revealed — and a subclass inherits this hook the moment it
     * exists, so without the gate the first masked field to be bound would publish its secret as
     * plain text under the role of a plain field. {@link PasswordField} now answers that question
     * for itself — it publishes its <em>mask</em> and the mask's own offsets over whatever this
     * writes — so the gate is no longer an interim; it is the floor under the next subclass to
     * refuse the clipboard, which is told nothing rather than something it must not be told until
     * its own hook says otherwise. The display line is never the source of this string under any
     * circumstances: for a masked field that value <em>is</em> the secret, because its index space
     * has to be the model's.
     *
     * <p>While composing, the model's own text and caret would describe a different string from the
     * one on the screen — the preedit is spliced in at the caret and the caret sits inside it — so
     * the composed view is published whole: the spliced string, the caret at
     * {@link #composedCaretIndex()} with the upstream side {@link #paintComposing} draws it on, and
     * a collapsed selection, because a composition paints no selection band.
     *
     * <p>The selection is the model's and not the paint's. The band is drawn only while focused,
     * but every operation on a selection reads the model, and {@link #onFocusLost} clears it
     * anyway, so the two can differ only for a programmatic {@code model().selectAll()} on a field
     * nobody is in.
     *
     * @param a the node being described
     */
    private void publishText(Accessibility a) {
        if (!allowClipboardCopy()) {
            return;
        }
        boolean composing = !preedit.isEmpty();
        int caret = composing ? composedCaretIndex() : model.cursor();
        ShapedText.Affinity affinity = composing
                ? ShapedText.Affinity.UPSTREAM : model.caretAffinity();
        int selectionStart = composing ? caret : model.selectionStart();
        int selectionEnd = composing ? caret : model.selectionEnd();
        // The line count is asked of the model rather than written as 1, so the two cannot drift if
        // a subclass ever holds a model that is not single-line. The caret box only while focused:
        // an unfocused field draws no caret, and the facet documents null as "no caret to draw",
        // which confines the whole of that geometry to one field per window.
        a.text(accessibleText(), accessibleTextRevision, caret, affinity,
                selectionStart, selectionEnd, model.lineCount(),
                isFocused() ? heldCaretBox() : null, false);
    }

    /**
     * Raises the context menu, replaces the contents, moves the caret or sets the selection, each
     * through the path the user's own gesture takes.
     *
     * <p>No enabled check of its own, following the same reasoning {@code ComboBox} states: the
     * node acted on is this widget, so the scene's gate has already walked this field and every
     * ancestor for enabled, checked that it is showing, that the window is not modal-blocked and
     * that it is inside the layer that owns input.
     *
     * @param action what is being asked
     * @param arg    the text for {@code SET_TEXT} and the range for the other two
     * @return whether this widget did it
     */
    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        switch (action) {
            case SHOW_MENU -> {
                if (width() <= 0) {
                    return false; // no caret to raise it at until the first layout
                }
                // Focus first, exactly as the right-click branch does and for the reason it gives:
                // the menu's Cut and Paste act on this field.
                requestFocus();
                showContextMenuForFocus();
                return true;
            }
            case SET_TEXT -> {
                if (!(arg instanceof Accessible.Argument.OfText replacement)) {
                    return false;
                }
                // NEVER setText(String): that one is silent, so a reader replacing the value would
                // change the text and tell the application nothing. Select-all-then-insert is what
                // a user does, it keeps the undo history setText would clear, and it handles the
                // empty case correctly -- an insert of "" with a selection deletes it, and with no
                // selection does nothing. The model sanitizes a single line, so a string carrying
                // a newline comes back with a space in its place and a bridge has to re-read.
                fireIfChanged(() -> {
                    model.selectAll();
                    model.insert(replacement.text());
                });
                ensureCursorVisible();
                resetBlink();
                invalidate();
                return true;
            }
            case SET_CARET -> {
                if (!(arg instanceof Accessible.Argument.OfRange range)
                        || range.start() != range.end()) {
                    return false; // a caret is a collapsed range; anything else is not this verb
                }
                return placeCaret(range.start(), range.start());
            }
            case SET_SELECTION -> {
                if (!(arg instanceof Accessible.Argument.OfRange range)) {
                    return false;
                }
                return placeCaret(range.start(), range.end());
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Puts the caret at {@code end}, with a selection back to {@code start} when the two differ:
     * the click-then-shift-click gesture, which is the widget's own path to both.
     *
     * <p>An offset outside the text is <b>refused</b> and never clamped to a neighbour, because a
     * client that asked for character forty of a ten-character field has misunderstood something
     * and a caret quietly placed at ten hides that. Both offsets are aligned to a grapheme
     * boundary first: {@link TextEditModel#cursor()} documents itself as always on one, and a
     * bridge counting UTF-16 units can name a point inside a cluster.
     */
    private boolean placeCaret(int start, int end) {
        if (!preedit.isEmpty()) {
            // Refused outright while an IME composition is open, because the offsets a client is
            // holding are not offsets into the string this widget would place them in. The facet
            // publishes the COMPOSED line -- the committed text with the preedit spliced in at the
            // cursor -- and the model counts the committed buffer alone; the two agree up to the
            // splice and diverge after it. Placed anyway, a caret asked for one position past the
            // preedit lands short by its length, silently, because the shorter buffer's own bounds
            // check passes; and since the composed line is keyed on the cursor, the same call
            // re-splices the preedit at the new position and the text under composition visibly
            // jumps. A refusal a client can see beats either. The composition owns the caret until
            // it commits, which is what every platform's input method contract already says.
            return false;
        }
        int length = model.length();
        if (start < 0 || start > length || end < 0 || end > length) {
            return false;
        }
        model.setCursor(model.alignToGrapheme(start), false);
        model.setCursor(model.alignToGrapheme(end), true);
        ensureCursorVisible();
        resetBlink();
        invalidate();
        return true;
    }

    /**
     * Presses the trailing button, if there is one and this field is enabled.
     *
     * @param key    the synthetic child's key
     * @param action what was asked
     * @param arg    unused; the press takes no argument
     * @return whether this widget did it
     */
    @Override
    protected boolean onSyntheticAction(long key, Accessible.Action action,
                                        Accessible.Argument arg) {
        if (key != TRAILING_BUTTON || action != Accessible.Action.PRESS) {
            return false;
        }
        // Deliberately not the pointer branch's trailingArmed: that shade is cleared by a RELEASE
        // or an EXIT, a press from an assistive technology has neither, and the button would stay
        // drawn pressed for the rest of the session.
        return pressTrailing();
    }

    /**
     * Runs the trailing button's action, holding the enabled guard both routes to it share.
     *
     * @return whether it ran, which is the same answer both routes need
     */
    private boolean pressTrailing() {
        if (trailingIcon == null || !isEnabled()) {
            return false;
        }
        onTrailing.run(); // the coupled action (e.g. clear/submit)
        return true;
    }

    // ---------------------------------------------------------------- blink

    /**
     * Cursor blink via a self-rescheduling {@link Ui#postDelayed} rather than a
     * per-frame ticker, so a focused field lets the event loop sleep between
     * blinks instead of pinning it at the frame rate.
     */
    private void resetBlink() {
        cursorVisible = true;
        invalidateCaret();
        scheduleBlink(++blinkGeneration);
    }

    private void scheduleBlink(int generation) {
        Ui.postDelayed(() -> {
            if (generation != blinkGeneration || !isFocused()) {
                return; // superseded by a newer phase, or focus was lost
            }
            cursorVisible = !cursorVisible;
            invalidateCaret();
            scheduleBlink(generation);
        }, Math.round(BLINK_SECONDS * 1000));
    }

    /** Damages just the caret column: a blink repaints ~1×line-height, not the whole field. */
    private void invalidateCaret() {
        Rect caret = caretRect(); // scene coordinates, clamped inside the field
        if (caret != null && scene() != null) {
            // Local-coords invalidate: the damage then clamps against clipping
            // ancestors: a caret scrolled out of a viewport damages nothing.
            // Margin for AA + hairline snap. Locked: growing it per step inflates per-blink
            // damage and defeats the partial rendering this is here to enable.
            invalidate(caret.x() - localToSceneX() - Strokes.DAMAGE_MARGIN,
                    caret.y() - localToSceneY() - Strokes.DAMAGE_MARGIN,
                    caret.width() + 2 * Strokes.DAMAGE_MARGIN,
                    caret.height() + 2 * Strokes.DAMAGE_MARGIN);
        } else {
            invalidate();
        }
    }

    @Override
    protected void onFocusGained() {
        focusFade.to(1);
        resetBlink();
        // Tabbed into: take the contents, so the first keystroke replaces them and a copy needs
        // no extra gesture. Clicked into: leave them, because the click already chose a caret.
        // The neighbouring Spinner has made the same distinction for typing since it shipped.
        if (focusArrivedByTraversal()) {
            model.selectAll();
        }
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
        blinkGeneration++; // stop the pending blink
        cursorVisible = true;
        model.clearSelection();
        preedit = ""; // drop any in-progress composition
        preeditCaret = 0;
        preeditFocusStart = 0;
        preeditFocusEnd = 0;
        composed = null; // and the shaping of it: nothing may hold a dead composition's line
        invalidate();
    }
}
