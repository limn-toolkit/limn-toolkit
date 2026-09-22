package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.a11y.ValueAccessibility;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.text.TextEditModel;
import limn.concurrent.Ui;
import limn.i18n.I18n;
import limn.i18n.LanguageWitness;
import limn.i18n.NumberingSystem;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Path2D;
import limn.graphics.RoundRect;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.lang.Checks;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Change;
import limn.scene.Widget;
import limn.scene.event.CharEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.regex.Pattern;

/**
 * A stepper input for a bounded value, in two modes:
 * <ul>
 *   <li>{@link Mode#NUMERIC}: {@code new Spinner(min, max, step)} shows a number
 *       (decimals inferred from the step);</li>
 *   <li>{@link Mode#TIME}: {@link #time()} shows {@code HH:MM}, where the up/down
 *       arrows adjust the focused field (hours by 60, minutes by the step) and
 *       Left/Right (or a click) move between the two fields.</li>
 * </ul>
 *
 * <p>Adjust with the up/down buttons or the keyboard:
 * <ul>
 *   <li>Up/Down, and Left/Right in numeric mode = ±step;</li>
 *   <li><b>Shift</b> + arrow = ±10 steps, the same as PageUp/PageDown;</li>
 *   <li><b>Alt</b> + arrow = one unit of the last <em>displayed</em> digit, for the
 *       values a step deliberately overshoots. Alt wins if Shift is also held;</li>
 *   <li>PageUp/PageDown = ±10 steps, Home/End = min/max.</li>
 * </ul>
 * Time mode ignores Shift and Alt: its two fields already are the coarse/fine split.
 * The value is clamped to {@code [min, max]}; {@link #onChange} fires on user changes.
 *
 * <p><b>The value can also be typed.</b> Type a digit and the spinner turns into a
 * one-line text field with the old value selected, so the first keystroke replaces it;
 * click into the value to place the caret instead. {@code Enter} commits, {@code Escape}
 * restores, and moving focus away commits. Text that does not parse commits nothing and
 * the value stands; a number outside the bounds is clamped.
 *
 * <p><b>Copy and paste work in both states.</b> With no edit in progress
 * {@code Ctrl/Cmd+C} copies the whole value as shown; while editing it copies the
 * selection, and {@code Ctrl/Cmd+X} cuts it. Pasting replaces the value when nothing is
 * being edited and inserts at the caret when something is. Within an edit,
 * {@code Ctrl/Cmd+Z} and {@code Ctrl/Cmd+Y} undo and redo the typing.
 *
 * <p>Stepping never goes away: Up/Down step even mid-edit, taking the typed text as the
 * number to step from, and Left/Right resume stepping (or switching time fields) once
 * the edit is committed. {@link #setEditable} turns typing off entirely.
 *
 * <p><b>A programmatic {@link #setValue} cancels an edit in progress</b>, because the
 * half-typed text is no longer about the number now in the field and committing it later
 * would silently undo whatever moved it.
 *
 * <p><b>The mouse wheel does not change the value</b>, and must not: a spinner in a
 * scrolling panel would otherwise swallow the wheel whenever the pointer crossed it,
 * stopping the scroll and editing a field the user was only scrolling past. The wheel
 * passes through to the scrollable ancestor.
 *
 * <p><b>Reading right to left</b> the stepper column and the value swap sides, and the arrows
 * that name the <em>value</em> swap with them: Left raises it and Right lowers it, because the
 * low end of a horizontal axis sits on the side reading starts from. Home and End do not swap;
 * they name {@code min} and {@code max}, which are not sides. Neither do time mode's Left and
 * Right: those pick the hours and the minutes of an {@code HH:MM} run, and a run of digits keeps
 * its own left-to-right order inside a right-to-left form, so the fields do not move and neither
 * does the key that selects one. The up and down arrows are drawn on the vertical axis and are
 * unchanged.
 *
 * <p>Sizes follow the widget's {@link limn.scene.ControlSize}. At {@code XSMALL} each
 * stepper half is 18 × 12 pt, below fine-motor comfort, so a dense form should treat the
 * keyboard as the primary way to adjust the value there.
 *
 * <pre>{@code
 * Spinner qty = new Spinner(0, 99, 1).setValue(1);
 * Spinner alarm = Spinner.time().setValue(7 * 60 + 30); // 07:30
 * }</pre>
 */
public final class Spinner extends Widget {

    /** Numeric value vs. an {@code HH:MM} time-of-day (value in minutes). */
    public enum Mode { NUMERIC, TIME }

    /** Press-and-hold auto-repeat: the pause before the first repeat, then its cadence. */
    private static final long HOLD_INITIAL_DELAY_MS = 350;
    private static final long HOLD_REPEAT_INTERVAL_MS = 55;
    /** Caret blink half-period, the same one {@link TextField} uses. */
    private static final double BLINK_SECONDS = 0.5;
    /**
     * What a typed or pasted number may look like. Deliberately narrower than
     * {@link Double#parseDouble}, which also takes {@code 1d}, {@code 0x1p3},
     * {@code Infinity} and {@code NaN}, none of which is a number anyone typed
     * into a spinner on purpose, and all of which would arrive through a paste.
     */
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");

    private final Mode mode;
    private final double min;
    private final double max;
    private final double step;
    private final int decimals;
    private double value;
    private boolean snapToStep = true;
    private int field; // TIME: 0 = hours, 1 = minutes
    private int hoverButton; // 0 none, 1 up, 2 down
    private int heldDir;     // press-and-hold direction: +1 up, -1 down, 0 idle
    private long holdToken;  // bumped to cancel a scheduled auto-repeat tick
    private DoubleConsumer onChange;
    private final Transition focusFade =
            new Transition(this).duration(Theme.of(this).animFocus).easing(Theme.of(this).animEasing);
    private final Path2D triangle = new Path2D();

    private boolean editable = true;
    /**
     * The edit in progress, or {@code null} when the spinner is a plain stepper.
     * A field rather than a flag plus a buffer: "is there an edit" and "what is in
     * it" are the same question, and every path that ends one drops the model.
     */
    private TextEditModel edit;
    /** Points the typed text is scrolled left by, so the caret stays in the box. */
    private float editScrollX;
    private boolean caretVisible;
    /** Bumped whenever the blink phase restarts; stale scheduled toggles no-op. */
    private int blinkGeneration;

    /** Numeric spinner over {@code [min, max]} stepping by {@code step} (&gt; 0). */
    public Spinner(double min, double max, double step) {
        this(Mode.NUMERIC, min, max, step);
    }

    /** A 24-hour {@code HH:MM} time spinner (00:00–23:59, 1-minute step). */
    public static Spinner time() {
        return time(0, 24 * 60 - 1, 1);
    }

    /** A time spinner bounded to {@code [minMinutes, maxMinutes]}, stepping the minutes field by {@code stepMinutes}. */
    public static Spinner time(int minMinutes, int maxMinutes, int stepMinutes) {
        if (minMinutes < 0 || maxMinutes >= 24 * 60) {
            throw new IllegalArgumentException(
                    "time bounds must be within [0, " + (24 * 60 - 1) + "] minutes, got ["
                            + minMinutes + ", " + maxMinutes + "]");
        }
        return new Spinner(Mode.TIME, minMinutes, maxMinutes, stepMinutes);
    }

    private Spinner(Mode mode, double min, double max, double step) {
        if (!(max > min)) {
            throw new IllegalArgumentException("max (" + max + ") must be > min (" + min + ")");
        }
        Checks.positive(step, "step");
        this.mode = mode;
        this.min = min;
        this.max = max;
        this.step = step;
        this.decimals = mode == Mode.TIME ? 0 : decimalsFor(step);
        this.value = min;
        this.field = 0;
        setFocusable(true);
        setCursor(Cursor.POINTER);
    }

    // ------------------------------------------------------------------- API

    /**
     * Sets the value (clamped + snapped). Announces {@code VALUE}/{@code CODE} when it moved and
     * reaches no handler: a caller's write is not the user operating the spinner. Cancels an
     * edit in progress; see the class comment.
     */
    public Spinner setValue(double newValue) {
        Ui.checkUiThread();
        cancelEdit();
        apply(newValue, Change.Origin.CODE);
        return this;
    }

    /**
     * Whether the value can be typed into (default {@code true}). Turning it off
     * leaves a pure stepper: no caret, no clipboard, and Left/Right keep stepping
     * (or switching time fields) as they always did.
     */
    public Spinner setEditable(boolean canType) {
        Ui.checkUiThread();
        this.editable = canType;
        if (!canType) {
            cancelEdit();
        }
        return this;
    }

    /** Whether the value can be typed as well as stepped. */
    public boolean isEditable() {
        return editable;
    }

    /** Whether the user is typing into the value right now. */
    public boolean isEditing() {
        return edit != null;
    }

    /** The current value, always within {@code [min, max]}. Minutes since midnight in time mode. */
    public double value() {
        return value;
    }

    /**
     * Whether values are snapped onto the step grid ({@code min + k * step}).
     * On by default: a spinner that owns its value keeps it tidy.
     *
     * <p>Turn it off when the spinner <em>displays</em> a value it does not own:
     * a property inspector bound to a document, a field showing a number that
     * came from a file or another tool. Snapping would silently misreport such a
     * value ({@code 1200} shown as {@code 1201} on a grid anchored at 1), and
     * rewrite it the moment the user nudges the field. Stepping still moves by
     * {@code step}; it just does so from the value actually held.
     */
    public Spinner setSnapToStep(boolean snap) {
        Ui.checkUiThread();
        this.snapToStep = snap;
        return this;
    }

    /** Whether committed values are rounded to a multiple of the step. */
    public boolean snapsToStep() {
        return snapToStep;
    }

    /** Lower bound, inclusive. */
    public double min() {
        return min;
    }

    /** Upper bound, inclusive. */
    public double max() {
        return max;
    }

    /** Whether this spinner shows a number or a time. */
    public Mode mode() {
        return mode;
    }

    /**
     * The application's response to the user changing the value: a step, a typed number, an
     * assistive technology's request. Never for {@link #setValue}; to hear every change whatever
     * caused it, {@linkplain #observeChanges watch} the spinner instead.
     *
     * @param listener the handler, or {@code null} to clear the slot
     * @return this spinner
     * @throws IllegalStateException if a handler is already registered
     */
    public Spinner onChange(DoubleConsumer listener) {
        Ui.checkUiThread();
        this.onChange = Checks.handlerSlot(onChange, listener, "Spinner.onChange");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.VALUE) {
            if (onChange != null) {
                onChange.accept(value);
            }
            return;
        }
        super.handleUserChange(aspect);
    }

    /** @return the value as it is displayed ({@code "07:30"} in time mode, a number otherwise). */
    public String text() {
        return formatted();
    }

    /**
     * The current value rendered, memoized on the value it was rendered from.
     *
     * <p>Not an optimisation of the arithmetic, but of the fact that this is asked from
     * {@link #onPaint}, and the widget repaints on every frame of a focus fade and on every hover
     * and press with the number unchanged. {@code String.format} is the most expensive way in the
     * library to render two digits: a locale lookup, a pattern parse and boxing, and in numeric
     * mode the pattern itself is built by concatenation and re-parsed on each call.
     *
     * <p>Validated against the value it was built from rather than cleared by whatever moved it.
     * The value moves several ways (a step, a drag, a typed commit, a programmatic set), and a
     * cache that has to be invalidated at each of them is one new path away from painting a stale
     * number, which is the worst thing this widget could do. {@code mode} and {@code decimals}
     * are final, so the value, the i18n epoch and the effective locale are the whole input — the
     * epoch because the numbering system rides the locale (ADR 033), and the locale itself
     * because this widget's subtree can now hold its own (ADR 035), which moves the digits
     * without moving the epoch; a memo that could see neither would repaint yesterday's digits
     * under either kind of switch.
     *
     * <p>The layout direction is deliberately <em>not</em> part of that key, and adding it would
     * be cargo. What is memoized is a {@link String}: the digits a number renders as are the same
     * digits in either direction. The direction belongs to the geometry that places this string
     * and to the shaping of text being typed, both of which are resolved fresh in the pass that
     * uses them and cached nowhere.
     */
    private String formatted() {
        boolean languageMoved = formattedLanguage.moved();
        if (formattedText == null || formattedFrom != value || languageMoved) {
            formattedFrom = value;
            formattedText = format(value);
            formattedRevision++;
            if (mode == Mode.TIME) {
                // The two fields are measured and drawn separately (each is independently
                // highlighted while focused), so the split is memoized with the whole.
                int colon = formattedText.indexOf(':');
                formattedHours = formattedText.substring(0, colon);
                formattedMinutes = formattedText.substring(colon + 1);
            }
        }
        return formattedText;
    }

    /**
     * Bumped every time the cache above refills, and by nothing else.
     *
     * <p>It exists so that the accessible tree can decide that this spinner's displayed value is
     * unchanged without producing it. The display form goes through a format call, which is
     * documented as never cached, so a walk that built the string in order to compare it would
     * allocate one string per damaged frame to conclude that nothing had moved — the exact defect
     * a zero-allocation quiet frame is a promise against. Compared as two {@code long}s instead.
     */
    private long formattedRevision;

    /**
     * The displayed value and the counter it was cached against, for whoever has to compare it
     * cheaply. Package-private because the tree reads it and an application reads {@link #text()}.
     *
     * @return the counter behind {@link #text()}; meaningless except compared with itself
     */
    long formattedRevision() {
        formatted();
        return formattedRevision;
    }

    /** {@code NaN} until the first render, and never equal to a value, so the first call builds. */
    private double formattedFrom = Double.NaN;
    private final LanguageWitness formattedLanguage = new LanguageWitness();
    private String formattedText;
    private String formattedHours;
    private String formattedMinutes;

    /**
     * Renders any value exactly as {@link #text()} renders the current one, shared
     * so {@link #onMeasure} cannot drift from what {@link #paintValue} draws.
     */
    private String format(double v) {
        // Rendered in ASCII first (Locale.US pins the separator story ADR 006 chose), then the
        // digits take the locale's numbering system: the one format-time seam ADR 033 allows.
        if (mode == Mode.TIME) {
            int m = (int) Math.round(v);
            return I18n.localizeDigits(String.format(Locale.US, "%02d:%02d", m / 60, m % 60));
        }
        return I18n.localizeDigits(String.format(Locale.US, "%." + decimals + "f", v));
    }

    // --------------------------------------------------------------- stepping

    /**
     * The one seam every value change goes through: clamps + snaps, and announces {@code VALUE}
     * with {@code origin} only on a real change.
     *
     * @return whether the value moved
     */
    private boolean apply(double raw, Change.Origin origin) {
        return apply(raw, step, origin);
    }

    /**
     * As {@link #apply(double, Change.Origin)}, but snapping onto {@code grid} instead of
     * the step. A fine nudge passes its own smaller grid: every step-grid point is
     * also a fine-grid point, so the value stays deterministic and a later coarse
     * step re-aligns to the coarse grid.
     */
    private boolean apply(double raw, double grid, Change.Origin origin) {
        if (!settle(raw, grid)) {
            return false;
        }
        notifyChange(Change.of(Change.Aspect.VALUE, origin));
        return true;
    }

    /**
     * The clamp and the snap without the announcement: what {@link #stepFromTyped} uses to
     * adopt the typed number before it steps, so the adoption and the step are announced as the
     * one change the user made.
     *
     * @return whether the value moved
     */
    private boolean settle(double raw, double grid) {
        double clamped = Math.max(min, Math.min(max, raw));
        double snapped;
        if (mode == Mode.TIME) {
            snapped = Math.max(min, Math.min(max, Math.rint(clamped)));
        } else if (!snapToStep) {
            snapped = clamped; // the caller's value is authoritative, see setSnapToStep
        } else {
            snapped = Math.max(min, Math.min(max, min + Math.round((clamped - min) / grid) * grid));
            // The exact bounds are always reachable even when the step grid does
            // not divide the range; otherwise End/Up could never reach max.
            if (clamped >= max) {
                snapped = max;
            } else if (clamped <= min) {
                snapped = min;
            }
        }
        if (snapped == value) {
            return false;
        }
        value = snapped;
        invalidate();
        return true;
    }

    /** The increment for one step: hours field bumps by 60 in time mode. */
    private double increment() {
        return mode == Mode.TIME && field == 0 ? 60 : step;
    }

    /**
     * One user step of {@code steps} increments: every caller is a gesture -- a key, a held arrow,
     * a press on the stepper, an assistive technology -- so this is {@code USER} and nothing else.
     *
     * @return whether the value moved, which the auto-repeat reads rather than re-reading the
     *         value, so a handler that writes the value back cannot end a press-and-hold
     */
    private boolean nudge(double steps) {
        return apply(value + steps * increment(), Change.Origin.USER);
    }

    /**
     * The finest increment worth offering: one unit of the last digit the value is
     * <em>displayed</em> with.
     *
     * <p>Not {@code step / 10}, which is the tempting definition and the wrong one:
     * with {@code step = 0.05} the field shows two decimals, so a 0.005 nudge would
     * change nothing on screen, and widening every spinner by a digit to show it
     * would be a heavy price for one modifier. Anchoring to the display instead
     * means a fine step is always visible, and where the step is already at display
     * resolution ({@code step = 1}, {@code step = 0.01}) it simply coincides with a
     * plain one: no surprise, nothing to explain.
     */
    private double fineIncrement() {
        return Math.min(increment(), Math.pow(10, -decimals));
    }

    /**
     * One arrow press: {@code ±step}, {@code ×10} with Shift, or {@link
     * #fineIncrement()} with Alt (Alt wins if both are held).
     *
     * <p>Shift and Alt exist because {@code PageUp}/{@code PageDown} are not on
     * every keyboard: a laptop reaches them through {@code Fn}, which is a poor
     * home for a gesture repeated all day.
     *
     * <p>Time mode ignores both: its fields already <em>are</em> the coarse/fine
     * split, hours against minutes.
     */
    private void nudgeFromKey(int direction, int modifiers) {
        if (mode == Mode.TIME) {
            nudge(direction);
            return;
        }
        if ((modifiers & Keys.MOD_ALT) != 0) {
            double unit = fineIncrement();
            apply(value + direction * unit, unit, Change.Origin.USER);
        } else if ((modifiers & Keys.MOD_SHIFT) != 0) {
            nudge(direction * 10L);
        } else {
            nudge(direction);
        }
    }

    // ----------------------------------------------------------------- typing

    /**
     * Starts an edit on the value as it is shown. {@code replacing} selects it
     * all, which is what typing a digit means: the first keystroke replaces the
     * old number instead of landing in the middle of it.
     */
    private void beginEdit(boolean replacing) {
        if (edit != null || !editable || !isEnabled()) {
            return;
        }
        edit = new TextEditModel(true);
        edit.setText(text());
        if (replacing) {
            edit.selectAll();
        } else {
            edit.moveEnd(false);
        }
        editScrollX = 0;
        resetBlink();
        invalidate();
    }

    /**
     * Ends the edit, keeping the typed number when there is one. Text that does
     * not parse is dropped whole rather than half-applied: {@code 1.2.3} has no
     * value to take, and a field that guessed at one would be worse than a field
     * that refuses.
     */
    private void commitEdit() {
        if (edit == null) {
            return;
        }
        String typed = edit.text();
        edit = null;
        blinkGeneration++; // stops the blink chain: it checks this before toggling
        Double parsed = parse(typed);
        if (parsed != null) {
            apply(parsed, Change.Origin.USER);
        }
        invalidate();
    }

    /** Ends the edit and keeps nothing. */
    private void cancelEdit() {
        if (edit == null) {
            return;
        }
        edit = null;
        blinkGeneration++;
        invalidate();
    }

    /**
     * The number in {@code typed}, or {@code null} when there is none.
     *
     * <p>A comma reads as a decimal point when there is no point: most of the
     * world types one, this widget renders the other, and a field that silently
     * dropped {@code 1,5} would be blaming the user for a format it chose itself.
     */
    private Double parse(String typed) {
        // Every known digit set folds to ASCII first, whatever system is active: the editor is
        // seeded from the localized display, and a paste must survive a locale switch (ADR 033).
        String text = I18n.toAsciiDigits(typed).strip();
        if (text.isEmpty()) {
            return null;
        }
        if (mode == Mode.TIME) {
            int colon = text.indexOf(':');
            if (colon <= 0 || colon == text.length() - 1) {
                return null;
            }
            try {
                int hours = Integer.parseInt(text.substring(0, colon).strip());
                int minutes = Integer.parseInt(text.substring(colon + 1).strip());
                // Minutes past 59 are a typo, not an hour: 7:75 means nothing, and
                // rolling it into 8:15 would be the field inventing an intent.
                if (hours < 0 || minutes < 0 || minutes > 59) {
                    return null;
                }
                return (double) (hours * 60 + minutes);
            } catch (NumberFormatException notATime) {
                return null;
            }
        }
        if (text.indexOf('.') < 0) {
            text = text.replace(',', '.');
        }
        return NUMBER.matcher(text).matches() ? Double.valueOf(text) : null;
    }

    /** Whether a typed character could be part of a value in this mode. */
    private boolean acceptsChar(int codepoint) {
        // Any known system's digits, not only the active one: an Arabic keyboard types
        // U+0660–0669, and gating on ASCII alone locked that keyboard out entirely.
        if (NumberingSystem.digitValue(codepoint) >= 0) {
            return true;
        }
        return mode == Mode.TIME
                ? codepoint == ':'
                : codepoint == '-' || codepoint == '+' || codepoint == '.' || codepoint == ',';
    }

    /**
     * Steps from what is <em>typed</em> rather than from what was there. The number
     * on screen is the one the user is looking at, so a step that ignored it would
     * jump somewhere nobody asked for.
     *
     * <p>The adoption and the step are <b>one</b> change, announced once as {@code VALUE}/{@code
     * USER} and measured against the value before the edit: the typed number is settled
     * silently, the step announces if it moved past that, and if it did not -- a step at a bound
     * over a number that was itself new -- the adoption is announced in its place. A typed edit
     * whose following step was a no-op used to apply with no notification at all.
     */
    private void stepFromTyped(Runnable step) {
        double before = value;
        Double typed = parse(edit.text());
        if (typed != null) {
            settle(typed, this.step);
        }
        double adopted = value;
        step.run();
        if (value == adopted && value != before) {
            notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.USER));
        }
        edit.setText(text());
        edit.selectAll();
        editScrollX = 0;
        afterEditChange();
    }

    private void afterEditChange() {
        ensureCaretVisible(Theme.of(this).tokensFor(this));
        resetBlink();
        invalidate();
    }

    /**
     * Scrolls the typed text just enough to keep the caret inside the value area.
     *
     * <p>Everything here is a coordinate in the <em>text's</em> own space, where it always was:
     * the caret, the window and the width the window has to hold. Only the last line, which turns
     * "where the window has to start" back into a scroll offset, knows a direction — and the
     * clamp below it keeps its form, because {@link #editScrollX} is a distance travelled from
     * the leading edge in both directions and so is never negative.
     *
     * <p>Both coordinates come off the one shaped line, which is what makes them coordinates in
     * the same space: the caret is where that line draws it, and the width is the width of what
     * it draws. A caret placed by measuring a prefix and a run painted from a shaped line agree
     * only while nothing reorders, and a formatted negative value in a right-to-left form
     * reorders.
     */
    private void ensureCaretVisible(SizeTokens t) {
        if (edit == null) {
            return;
        }
        ShapedText line = editLine(t);
        float pad = t.spacingMedium();
        float visible = Math.max(1, valueWidth(t) - 2 * pad);
        float caret = line.caretX(edit.caret());
        float total = line.metrics().width();
        float view = editViewStart(total, visible);
        float wanted = view;
        if (caret - wanted > visible) {
            wanted = caret - visible;
        }
        if (caret < wanted) {
            wanted = caret;
        }
        if (wanted != view) {
            editScrollX = isRightToLeft() ? total - visible - wanted : wanted;
        }
        editScrollX = Math.max(0, Math.min(editScrollX, Math.max(0, total - visible)));
    }

    /**
     * The text being typed, shaped for the paragraph this spinner reads in, so that the visual
     * arrow keys have a line to step over. A typed value is usually a run of digits, whose visual
     * order is its logical one and over which a visual step and a logical one agree; a paste is
     * not filtered, so it is the case where they do not.
     *
     * <p><b>Every horizontal coordinate the editor has comes from here</b> — the caret, the
     * click, the selection band and the run's own width — which is the rule
     * {@code docs/design/text-and-input.md} states for the text widgets and which this one used to
     * be the stated exception to. What made the exception legal was that a spinner formats what it
     * shows and accepts only characters that neither join nor reorder, so the width of a prefix
     * really was a distance on the screen. A base direction ends that: a leading minus sign is a
     * neutral at the paragraph's edge, so in a right-to-left form it takes the paragraph's own
     * level and is drawn after the digits, and {@code -42} is painted {@code 42-}. The prefix is
     * still a width; it is no longer the distance to anything.
     *
     * <p>Shaped fresh in the pass that asks rather than held in a field: the ruler memoizes
     * shaping, so the several questions one paint asks cost one shaping between them, and a field
     * would need a key carrying the resolved direction and would hold a zero-width line for any
     * spinner whose first shaping happened while it was detached.
     *
     * @param t the size row resolved for this pass, so that two passes cannot shape in two fonts
     */
    private ShapedText editLine(SizeTokens t) {
        String typed = edit.text();
        return shapeText(typed, t.body());
    }

    /**
     * The caret a click at {@code px} in the text's own space asks for, side included.
     *
     * <p>Side included because an index alone is a caret that jumps the next time it moves: where
     * a run of digits meets a sign that reordered away from it, one index is two points on the
     * line, and which of them the click meant is what {@link ShapedText#hitTest} resolved. It also
     * never has to be snapped to a grapheme boundary the way the linear search over prefix widths
     * did — caret stops are the shaper's own cluster boundaries, and there is nothing between two
     * of them to land in, which is what a pasted surrogate pair used to need aligning out of.
     */
    private ShapedText.Position caretAt(float px, SizeTokens t) {
        ShapedText line = editLine(t);
        // Past the right edge is the LOGICAL end of the line, not the nearest cluster to it: on a
        // line ending in the direction opposite the paragraph's -- a negative value read right to
        // left ends in its minus sign -- those are different characters, and hitTest's clamp is
        // the answer to a drag past the end rather than to this.
        return px > line.metrics().width()
                ? new ShapedText.Position(edit.length(), ShapedText.Affinity.UPSTREAM)
                : line.hitTest(px);
    }

    /**
     * Caret blink on a self-rescheduling {@link Ui#postDelayed}, like
     * {@link TextField}'s, so a focused spinner lets the loop sleep between blinks.
     * It repaints the whole box rather than the caret's column: a spinner is a
     * two-inch control, and the damage rect would be most of it either way.
     */
    private void resetBlink() {
        caretVisible = true;
        invalidate();
        scheduleBlink(++blinkGeneration);
    }

    private void scheduleBlink(int generation) {
        Ui.postDelayed(() -> {
            if (generation != blinkGeneration || edit == null || !isFocused()) {
                return; // superseded, committed, or the focus moved on
            }
            caretVisible = !caretVisible;
            invalidate();
            scheduleBlink(generation);
        }, Math.round(BLINK_SECONDS * 1000));
    }

    // ------------------------------------------------------ press-and-hold repeat

    /** Arms auto-repeat in {@code dir} (+1 up / −1 down) after the initial pause. */
    private void startHold(int dir) {
        heldDir = dir;
        long token = ++holdToken;
        Ui.postDelayed(() -> repeatHold(token), HOLD_INITIAL_DELAY_MS);
    }

    /** One auto-repeat tick; re-arms itself until released, detached or at a bound. */
    private void repeatHold(long token) {
        if (token != holdToken || heldDir == 0 || !isEnabled() || !isShowing()) {
            return; // released, superseded, disabled or off-screen: stop the chain
        }
        if (!nudge(heldDir)) {
            heldDir = 0; // reached the bound: nothing left to repeat
            return;
        }
        Ui.postDelayed(() -> repeatHold(token), HOLD_REPEAT_INTERVAL_MS);
    }

    /** Ends auto-repeat and invalidates any scheduled tick. */
    private void stopHold() {
        heldDir = 0;
        holdToken++;
    }

    // --------------------------------------------------------------- geometry



    /**
     * The value area's width: the whole box less the stepper column. A magnitude and not a
     * position, which is what lets {@link #onMeasure} and {@link #ensureCaretVisible} keep asking
     * it the question they always asked; {@link #valueLeft} turns it into an x.
     */
    private float valueWidth(SizeTokens t) {
        return Math.max(0, width() - t.spinnerButtonW());
    }

    /**
     * Physical left edge of the value area. The stepper column sits on the side reading ends on,
     * so reading left to right the value starts at the box's own left edge and reading right to
     * left it starts where that column ends. Every coordinate in the value area is composed from
     * this and {@link #valueWidth}, so the clip, the text, the caret and the hit test cannot
     * disagree about which side is which.
     */
    private float valueLeft(SizeTokens t) {
        return isRightToLeft() ? t.spinnerButtonW() : 0;
    }

    /**
     * Where a run of {@code runWidth} points puts its <b>left</b> edge, which is what
     * {@code drawText} places against: one pad in from the side reading starts on, measured
     * against the value area so the number never slides under the arrows in either direction.
     */
    private float runOriginX(SizeTokens t, float runWidth) {
        float pad = t.spacingMedium();
        return isRightToLeft() ? valueLeft(t) + valueWidth(t) - pad - runWidth : pad;
    }

    /**
     * {@link #runOriginX} for the text being typed, which also carries {@link #editScrollX}.
     * Reading left to right the leading edge is the left one and the scroll pulls the run back;
     * reading right to left it is the right one and the same positive magnitude pushes the run
     * forward. Zero is the leading edge either way, which is what lets the clamp in
     * {@link #ensureCaretVisible} keep its form.
     *
     * <p>The one expression a pointer coordinate is turned back through as well, so a click and
     * the caret it places cannot land on different characters: a sign error here is invisible in
     * a screenshot and wrong in every click.
     */
    private float editOriginX(SizeTokens t, float textWidth) {
        return isRightToLeft()
                ? runOriginX(t, textWidth) + editScrollX
                : runOriginX(t, textWidth) - editScrollX;
    }

    /**
     * Where the visible window starts <b>in the text's own space</b>: what {@link #editScrollX}
     * means once the direction has been applied. It grows with the scroll in one direction and
     * shrinks with it in the other, which is the whole of what {@link #ensureCaretVisible} has to
     * know about a direction.
     */
    private float editViewStart(float textWidth, float visible) {
        return isRightToLeft() ? textWidth - visible - editScrollX : editScrollX;
    }

    /**
     * 0 = value area, 1 = up button, 2 = down button. Takes the resolved row so a click
     * can never be classified against a different step than the one that painted.
     */
    private int regionAt(SizeTokens t, float localX, float localY) {
        // The value area ends at the stepper column reading left to right, and begins at it
        // reading right to left: one boundary, named from the side it is on.
        boolean inValue = isRightToLeft() ? localX >= valueLeft(t) : localX < valueWidth(t);
        if (inValue) {
            return 0;
        }
        return localY < height() / 2 ? 1 : 2;
    }

    /** The one text anchor: paint and {@link #baselineOffset()} must never disagree. */
    private float baselineFor(TextMetrics metrics) {
        return (height() - metrics.height()) / 2 + metrics.ascent();
    }

    @Override
    protected float baselineOffset() {
        SizeTokens t = Theme.of(this).tokensFor(this);
        return baselineFor(textRuler().measure("Hg", t.body()));
    }

    // ------------------------------------------------------------------ paint

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.of(this).tokensFor(this);
        float pad = t.spacingMedium(); // the same pad the value is drawn against, one at each end
        // Measure the WIDEST value this spinner can ever show, not the current one:
        // the box must not resize as the user steps, and the value must not slide
        // under the stepper column (the box clip only stops it at the outer corner,
        // so a long value used to be bisected by the button divider).
        //
        // For a fixed decimal count the longest rendering is always at a bound (the
        // integer part grows with |v| and the minus sign can only appear at a
        // negative min), so the two extremes bound every value in between. This
        // assumes digits share an advance, true for the toolkit's fonts; the
        // trailing pad absorbs sub-point variance if they ever do not.
        // Shaped rather than measured, and for the same reason paint shapes: a width taken from
        // a measurement has nowhere to put a base direction, so a box sized that way and a value
        // painted from a shaped line would be answering two different questions. The extremes are
        // shaped for this spinner's own direction, exactly as the value it will draw is.
        TextMetrics atMin = shapeText(format(min), t.body()).metrics();
        TextMetrics atMax = shapeText(format(max), t.body()).metrics();
        float valueWidth = Math.max(atMin.width(), atMax.width());
        float needed = pad + valueWidth + pad + t.spinnerButtonW();
        // spinnerWidth stays a floor, so narrow ranges keep the step's preferred size.
        return constraints.constrain(Math.max(t.spinnerWidth(), needed),
                t.resolvedHeight(atMax.lineHeight()));
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.of(this);
        SizeTokens t = theme.tokensFor(this);
        boolean enabled = isEnabled();
        float w = width();
        float h = height();
        float focus = focusFade.value();
        float radius = t.radiusMedium(); // one resolution: fill, clip and border must agree

        Color fill = enabled ? theme.surface() : theme.disabledFill();
        canvas.fillRoundRect(0, 0, w, h, radius, fill);

        // Clip the content to the rounded box so the button hover fill and the
        // divider lines never spill past the corners.
        canvas.save();
        canvas.clipRoundRect(RoundRect.of(0, 0, w, h, radius));
        paintValue(canvas, theme, t, enabled, focus);
        paintButtons(canvas, theme, t, enabled);
        canvas.restore();

        // Border LAST, on top, so a divider reaching the edge sits under it and
        // the (thicker, focused) border stays clean. The width is an expression, not
        // a branch: the stroke thickens continuously as the focus fade runs.
        float half = Strokes.HALF_PIXEL_INSET;
        canvas.drawRoundRect(half, half, w - 2 * half, h - 2 * half, radius,
                Strokes.BORDER + (Strokes.FOCUS_RING - Strokes.BORDER) * focus,
                theme.outline().lerp(theme.focusRing(), focus));
    }

    private void paintValue(Canvas canvas, Theme theme, SizeTokens t, boolean enabled, float focus) {
        Font font = t.body();
        Color ink = enabled ? theme.text() : theme.disabledText();
        float baseline = baselineFor(textRuler().measure("Hg", font));

        if (edit != null) {
            paintTypedValue(canvas, theme, t, font, baseline, ink);
            return;
        }
        // Resolved ONCE for this pass and handed to every line it shapes: two resolutions inside
        // one paint would draw the three fields of a clock face against two different paragraphs.
        if (mode == Mode.TIME) {
            formatted(); // fills the two field strings below
            // Three lines rather than one, because they are three draws: the two fields are
            // highlighted independently and the colon is drawn in a different ink. Each carries
            // its own width, so the run is composed from the widths of the things in it.
            ShapedText hh = shapeText(formattedHours, font);
            ShapedText colon = shapeText(":", font);
            ShapedText mm = shapeText(formattedMinutes, font);
            float hhW = hh.metrics().width();
            float colonW = colon.metrics().width();
            float mmW = mm.metrics().width();
            // Only the ORIGIN of the run moves: hh:mm is a run of digits, which keeps its
            // left-to-right order inside a right-to-left paragraph, so the hours stay left of
            // the minutes and the colon stays between them whichever way the form reads.
            float hhX = runOriginX(t, hhW + colonW + mmW);
            float colonX = hhX + hhW;
            float mmX = colonX + colonW;
            // Highlight the active field while focused.
            if (focus > 0.001f) {
                float fx = field == 0 ? hhX : mmX;
                float fw = field == 0 ? hhW : mmW;
                float padX = t.spinnerFieldPadX();
                float inset = t.spinnerFieldInset(); // shared with the divider: one optical margin
                canvas.fillRoundRect(fx - padX, inset, fw + 2 * padX, height() - 2 * inset,
                        t.radiusSmall(), theme.primary().withAlpha(0.20f * focus));
            }
            canvas.drawText(hh, hhX, baseline, ink);
            canvas.drawText(colon, colonX, baseline, theme.textMuted());
            canvas.drawText(mm, mmX, baseline, ink);
        } else {
            // The one place a spinner's value is a string with no strong character and no
            // structure around it, so the fallback is the whole of what decides its direction.
            ShapedText shown = shapeText(text(), font);
            canvas.drawText(shown, runOriginX(t, shown.metrics().width()), baseline, ink);
        }
    }

    /**
     * The text being typed, with its selection and caret. Clipped to the value
     * area rather than to the box: the stepper column is not part of the field,
     * and a long number sliding under the arrows would look like a rendering bug
     * rather than like text that ran out of room.
     */
    private void paintTypedValue(Canvas canvas, Theme theme, SizeTokens t, Font font,
                                 float baseline, Color ink) {
        TextMetrics metrics = textRuler().measure("Hg", font);
        float inkTop = baseline - metrics.ascent();
        // The one shaped line this pass draws, and the one every coordinate below is asked of.
        ShapedText line = editLine(t);
        float originX = editOriginX(t, line.metrics().width());

        canvas.save();
        canvas.clipRect(valueLeft(t), 0, valueWidth(t), height());
        if (edit.hasSelection()) {
            // The N boxes the selection really covers rather than the one box between two prefix
            // widths: a selection that spans a reordering -- the digits of a negative value read
            // right to left, without its sign -- is not contiguous on the screen, and painted as
            // one box it would cover a character it does not hold.
            for (ShapedText.Span span : line.selection(edit.selectionStart(), edit.selectionEnd())) {
                canvas.fillRect(originX + span.x0(), inkTop - Strokes.INK_BLEED, span.width(),
                        metrics.height() + 2 * Strokes.INK_BLEED,
                        theme.primary().withAlpha(0.35f));
            }
        }
        canvas.drawText(line, originX, baseline, ink);
        // No caret over a selection: the highlight already says where the next
        // keystroke lands, and a caret at one of its ends only argues with it.
        if (caretVisible && isFocused() && !edit.hasSelection()) {
            float caretX = originX + line.caretX(edit.caret());
            canvas.drawLine(caretX, inkTop - Strokes.INK_BLEED,
                    caretX, inkTop + metrics.height() + Strokes.INK_BLEED,
                    Strokes.CARET, ink);
        }
        canvas.restore();
    }

    private void paintButtons(Canvas canvas, Theme theme, SizeTokens t, boolean enabled) {
        boolean rtl = isRightToLeft();
        // The stepper column sits on the side reading ends on, so reading right to left it is the
        // LEFT column: its own left edge is the box's, and the seam it shares with the value is
        // its right edge. The seam is not the column's left edge in both directions; drawing it
        // there would put the divider on the outer border and leave the value unfenced.
        float bx = rtl ? 0 : width() - t.spinnerButtonW();
        float columnEnd = rtl ? t.spinnerButtonW() : width();
        float seam = rtl ? columnEnd : bx;
        float mid = height() / 2;
        float inset = t.spinnerFieldInset();
        canvas.drawLine(seam, inset, seam, height() - inset, Strokes.BORDER, theme.outline());
        canvas.drawLine(bx, mid, columnEnd, mid, Strokes.BORDER, theme.outline());

        boolean canUp = enabled && value < max;
        boolean canDown = enabled && value > min;
        paintButton(canvas, theme, t, bx, 0, mid, true, hoverButton == 1, canUp);
        paintButton(canvas, theme, t, bx, mid, mid, false, hoverButton == 2, canDown);
    }

    private void paintButton(Canvas canvas, Theme theme, SizeTokens t, float bx, float top, float h,
                             boolean up, boolean hovered, boolean active) {
        if (hovered && active) {
            // The inset IS the divider width, so the fill can never cover a divider.
            float in = Strokes.SPINNER_HOVER_INSET;
            canvas.fillRect(bx + in, top + in, t.spinnerButtonW() - 2 * in, h - 2 * in,
                    theme.surfaceRaised());
        }
        // Centred in the column, pointing up or down: a mark on the vertical axis, and the one
        // thing in this widget that a mirrored layout leaves exactly where it found it. It
        // travels only because the column it is centred in does.
        float cx = bx + t.spinnerButtonW() / 2;
        float cy = top + h / 2;
        float s = t.arrowHalf();
        triangle.reset();
        if (up) {
            triangle.moveTo(cx - s, cy + s / 2).lineTo(cx, cy - s / 2).lineTo(cx + s, cy + s / 2);
        } else {
            triangle.moveTo(cx - s, cy - s / 2).lineTo(cx, cy + s / 2).lineTo(cx + s, cy - s / 2);
        }
        Color color = !active ? theme.disabledText() : hovered ? theme.text() : theme.textMuted();
        canvas.drawPath(triangle, Strokes.ARROW_PEN, color);
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void onMouseEvent(MouseEvent event) {
        // Resolved once for the whole event: a second resolution could classify the click
        // against a different step than the one that painted the stepper column.
        SizeTokens t = Theme.of(this).tokensFor(this);
        switch (event.type()) {
            case MOVE, ENTER -> {
                int region = regionAt(t, sceneToLocalX(event.x()), sceneToLocalY(event.y()));
                if (region != hoverButton) {
                    hoverButton = region;
                    invalidate();
                }
            }
            case EXIT -> {
                if (hoverButton != 0) {
                    hoverButton = 0;
                    invalidate();
                }
            }
            case PRESS -> {
                if (event.button() != Keys.MOUSE_LEFT || !isEnabled()) {
                    return;
                }
                float lx = sceneToLocalX(event.x());
                int region = regionAt(t, lx, sceneToLocalY(event.y()));
                if (region == 1 || region == 2) {
                    // Reaching for the arrows is leaving the text: commit first, so
                    // the step lands on the number the user typed and not on the one
                    // it replaced.
                    commitEdit();
                    int direction = region == 1 ? 1 : -1;
                    nudge(direction);
                    startHold(direction); // hold to keep stepping
                } else if (editable) {
                    beginEdit(false);
                    if (edit != null) {
                        float textX = lx - editOriginX(t, editLine(t).metrics().width());
                        edit.setCaret(caretAt(textX, t),
                                (event.modifiers() & Keys.MOD_SHIFT) != 0);
                        afterEditChange();
                    }
                } else if (mode == Mode.TIME) {
                    selectFieldAt(t, lx);
                }
                event.consume();
            }
            case DRAG -> {
                if (edit != null) {
                    // The row resolved at the top of this handler, not a second one of its own:
                    // a drag that measured its origin against a different step than the press did
                    // would slip a character at the moment the pointer started moving.
                    float lx = sceneToLocalX(event.x());
                    float textX = lx - editOriginX(t, editLine(t).metrics().width());
                    edit.setCaret(caretAt(textX, t), true);
                    afterEditChange();
                    event.consume();
                }
            }
            case RELEASE -> {
                if (heldDir != 0) {
                    stopHold();
                    event.consume();
                }
            }
            // No WHEEL case, deliberately. A spinner inside a scrolling panel (a property
            // inspector is the canonical one) used to swallow the wheel whenever the pointer
            // happened to cross it: the panel stopped scrolling AND the value changed, neither
            // of which the user asked for. Mouse events bubble until something consumes them
            // (Scene:1327), so not handling the wheel is exactly what lets it reach the
            // ScrollView ancestor. Every other wheel consumer in the toolkit scrolls something;
            // this was the only one that mutated a value.
            //
            // Focus-gating it instead (Qt's approach) was considered and rejected: in a
            // property grid you click a field to edit it and then scroll the panel with the
            // pointer still over it, so the bug would survive in the most common flow. Browsers
            // reached the same conclusion for <input type=number>, and AppKit steppers never
            // accepted the wheel at all.
            default -> {
            }
        }
    }

    private void selectFieldAt(SizeTokens t, float localX) {
        Font font = t.body();
        formatted(); // the same two strings the paint measured, rather than a second rendering
        // Shaped for this spinner's own direction, so the boundary a click is compared against is
        // composed from the same widths the paint composed the run's origin from.
        float hhColonW = shapeText(formattedHours, font).metrics().width()
                + shapeText(":", font).metrics().width();
        float mmW = shapeText(formattedMinutes, font).metrics().width();
        // Measured from the run's own origin, which is where the paint put it. The comparison
        // does not flip with it: inside a run of digits the hours stay left of the minutes, which
        // is the same reason Left and Right do not swap the two fields.
        float boundary = runOriginX(t, hhColonW + mmW) + hhColonW;
        int newField = localX < boundary ? 0 : 1;
        if (newField != field) {
            field = newField;
            invalidate();
        }
    }

    @Override
    protected void onCharTyped(CharEvent event) {
        if (!editable || !isEnabled()) {
            return;
        }
        int codepoint = event.codepoint();
        if (!acceptsChar(codepoint)) {
            return; // a letter is not part of a number: nothing to start an edit for
        }
        beginEdit(true);
        if (edit == null) {
            return;
        }
        edit.insertCodePoint(codepoint);
        afterEditChange();
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed() || !isEnabled()) {
            return;
        }
        int mods = event.modifiers();
        // Ctrl+Alt is AltGr, which is how several layouts type a digit or a comma;
        // treating it as a shortcut would swallow the character on its way in.
        boolean altGr = (mods & Keys.MOD_CONTROL) != 0 && (mods & Keys.MOD_ALT) != 0;
        boolean shortcut = (mods & Keys.MOD_SUPER) != 0
                || (!altGr && (mods & Keys.MOD_CONTROL) != 0);
        if (shortcut) {
            // A shortcut this widget does not know stays unconsumed rather than
            // falling through to the stepper: Cmd+Home is not End.
            if (handleShortcut(event.key(), (mods & Keys.MOD_SHIFT) != 0)) {
                event.consume();
            }
            return;
        }
        if (edit != null && handleEditKey(event.key(), mods)) {
            event.consume();
            return;
        }
        boolean handled = true;
        // Which way the value axis runs across the screen, resolved once for this key press.
        // Right raises the value reading left to right and lowers it reading right to left, the
        // same mirror a horizontal slider takes: the low end is on the side reading starts from,
        // and a stepper whose arrows disagreed with the rail beside it would be worse than either.
        int rightStep = isRightToLeft() ? -1 : 1;
        switch (event.key()) {
            case Keys.UP -> nudgeFromKey(1, event.modifiers());
            case Keys.DOWN -> nudgeFromKey(-1, event.modifiers());
            case Keys.PAGE_UP -> nudge(10);
            case Keys.PAGE_DOWN -> nudge(-10);
            // Home and End name min and max, which are values and not sides, so they are the
            // same key in both directions.
            case Keys.HOME -> apply(min, Change.Origin.USER);
            case Keys.END -> apply(max, Change.Origin.USER);
            case Keys.LEFT -> {
                if (mode == Mode.TIME) {
                    // Not mirrored: the hours and the minutes of an hh:mm run do not swap places
                    // in a right-to-left form, so the key that picks the hours does not either.
                    setField(0);
                } else {
                    nudgeFromKey(-rightStep, event.modifiers());
                }
            }
            case Keys.RIGHT -> {
                if (mode == Mode.TIME) {
                    setField(1);
                } else {
                    nudgeFromKey(rightStep, event.modifiers());
                }
            }
            default -> handled = false;
        }
        if (handled) {
            event.consume();
        }
    }

    private void setField(int newField) {
        if (field != newField) {
            field = newField;
            invalidate();
        }
    }

    /** The keys that belong to the text while there is text being typed. */
    private boolean handleEditKey(int key, int mods) {
        boolean shift = (mods & Keys.MOD_SHIFT) != 0;
        // Resolved once for this keystroke: the visual arrows step over a line, and a line shaped
        // in one size row cannot answer for a caret placed against another.
        SizeTokens tokens = Theme.of(this).tokensFor(this);
        switch (key) {
            // Left and Right are VISUAL while there is text: they are named for a direction on
            // the screen, so they step one cluster that way on the line actually drawn, whatever
            // the characters under them do. Not mirrored — a mirror would step the caret twice.
            // Home and End stay LOGICAL, which is what makes Shift+Home one contiguous range of
            // the string, and in reordered text that means Left and Home can move opposite ways.
            case Keys.LEFT -> edit.moveVisualLeft(editLine(tokens), 0, shift);
            case Keys.RIGHT -> edit.moveVisualRight(editLine(tokens), 0, shift);
            case Keys.HOME -> edit.moveHome(shift);
            case Keys.END -> edit.moveEnd(shift);
            case Keys.BACKSPACE -> edit.backspace();
            case Keys.DELETE -> edit.deleteForward();
            case Keys.ENTER -> {
                commitEdit();
                return true;
            }
            case Keys.ESCAPE -> {
                cancelEdit();
                return true;
            }
            // Stepping never goes away, it just steps from what is typed.
            case Keys.UP -> {
                stepFromTyped(() -> nudgeFromKey(1, mods));
                return true;
            }
            case Keys.DOWN -> {
                stepFromTyped(() -> nudgeFromKey(-1, mods));
                return true;
            }
            case Keys.PAGE_UP -> {
                stepFromTyped(() -> nudge(10));
                return true;
            }
            case Keys.PAGE_DOWN -> {
                stepFromTyped(() -> nudge(-10));
                return true;
            }
            default -> {
                return false;
            }
        }
        afterEditChange();
        return true;
    }

    /** Select-all, the clipboard and undo: the four every text control answers. */
    private boolean handleShortcut(int key, boolean shift) {
        switch (key) {
            case Keys.A -> {
                beginEdit(true);
                if (edit == null) {
                    return false;
                }
                edit.selectAll();
                afterEditChange();
                return true;
            }
            case Keys.C -> {
                return copy(false);
            }
            case Keys.X -> {
                return copy(true);
            }
            case Keys.V -> {
                return paste();
            }
            case Keys.Z -> {
                return undo(shift);
            }
            case Keys.Y -> {
                return undo(true);
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Copies, or cuts. With no edit in progress there is no selection to speak of,
     * so the whole value goes: a user who has not touched the field means "this
     * number" by Ctrl+C, and making them select it first would be ceremony. Cutting
     * needs a selection, because there is no such thing as a spinner with no value.
     */
    private boolean copy(boolean cut) {
        if (edit == null) {
            if (cut) {
                return false;
            }
            clipboard().set(text());
            return true;
        }
        if (!edit.hasSelection()) {
            return false;
        }
        clipboard().set(edit.selectedText());
        if (cut) {
            edit.deleteSelection();
            afterEditChange();
        }
        return true;
    }

    /**
     * Pastes at the caret, or over the whole value when nothing is being edited,
     * which is what pasting into an untouched field means everywhere else.
     *
     * <p>What arrives is not filtered. A clipboard holding {@code 12 px} shows up
     * as typed, and {@code Enter} then keeps the number or {@code Escape} puts the
     * old one back; a paste silently rewritten to {@code 12} would be a field
     * deciding what its user meant.
     */
    private boolean paste() {
        if (!editable || !isEnabled()) {
            return false;
        }
        String pasted = clipboard().get();
        if (pasted.isEmpty()) {
            return false; // an empty clipboard must not eat a selection
        }
        beginEdit(true);
        if (edit == null) {
            return false;
        }
        edit.insert(pasted);
        afterEditChange();
        return true;
    }

    private boolean undo(boolean redo) {
        if (edit == null) {
            return false; // the value's own history belongs to whoever owns the value
        }
        if (redo ? edit.redo() : edit.undo()) {
            afterEditChange();
        }
        return true;
    }

    @Override
    protected void onFocusGained() {
        focusFade.to(1);
    }

    @Override
    protected void onFocusLost() {
        // Focus leaving is the third answer, beside Enter and Escape, and the one
        // people give most often: they type and click elsewhere. Committing here is
        // what stops a typed number from being quietly thrown away.
        commitEdit();
        focusFade.to(0);
    }

    // ---------------------------------------------------------- accessibility

    /**
     * The keys of the two halves of the stepper column, which are {@link #regionAt}'s own region
     * numbers rather than numbers of their own: the node an assistive technology presses and the
     * region a click is classified into are then the same thing named once.
     */
    private static final long UP_BUTTON = 1;
    private static final long DOWN_BUTTON = 2;

    /**
     * One {@code SPIN_BUTTON} carrying the value, its bounds, what one increment moves it by and
     * the clock face or the number as it is shown, over the two halves of the stepper column.
     *
     * <p><b>No name.</b> This widget holds no {@link limn.i18n.I18nString} at all — no caption, no
     * placeholder, no title — so there is nothing to hand over by reference, and a string
     * synthesised here would be built once per damaged frame to conclude that nothing had moved.
     * The name arrives from outside through the three routes that already exist:
     * {@code setAccessibleName}, a {@code Label} pointed at it with {@code setLabelFor}, or the
     * tooltip, which the walk takes when nothing else supplied one. The colour picker's eleven
     * steppers are named the second way. Focusable and nameless is therefore the intended state of
     * a bare spinner, and it is legal: the walk warns about a focusable widget with no <em>role</em>
     * and never about one with no name.
     *
     * <p><b>The published step is what one increment moves the value by</b>, which is
     * {@link #increment()} — the {@code step} field in numeric mode, and 60 in time mode while the
     * arrows are on the hours. That is a deliberate divergence from {@code Slider}, whose step is
     * documented as the grid a set snaps onto, and it is not a slip: the two readings differ here,
     * because time mode snaps onto whole minutes whatever the step and {@link #setSnapToStep} can
     * turn the grid off while the increment stays {@code step}. It is also the only allocation-free
     * way the tree carries which of the two fields the arrows are on. What is given up is that a
     * field switch raises no event — the difference emits a value change only when the value moves
     * — so a reader that re-reads the node hears the new step and one that only listens does not.
     * Because nothing published reads {@code snapToStep} or {@code editable}, neither setter owes
     * an {@code invalidateAccessible()}; a later step that published the snap grid instead would
     * create that debt.
     *
     * <p><b>No {@code EDITABLE} state</b>, which is not a deferral: {@code editable} gates the
     * inline text editor alone, and the value stays settable through {@code SET_VALUE} and
     * steppable through both verbs when it is off, so the bit would tell a reader that a value the
     * same node accepts a set for cannot be edited. No {@code HAS_POPUP} and no {@code SHOW_MENU}
     * either, unlike {@code TextField}: this widget has no context menu, its clipboard is reached
     * from Ctrl/Cmd+C, X and V alone, and a reader therefore has no route to copy the value. That
     * is a stated cost rather than an oversight. And no orientation, which the class fixes for
     * neither axis.
     *
     * <p><b>Nothing here reads the hover region, the held direction or the focus fade.</b> The
     * fade damages this widget on every frame it runs for and the hover moves on every pointer
     * crossing, so a hook that published either would re-copy the whole tree for a mouse the reader
     * is not using.
     *
     * <p><b>An arrow that cannot move the value any further says so</b> (decision 69, 2026-09-16,
     * which is decision 30 and semantics 5 carried from the refused calendar day and
     * {@code SegmentedControl}'s dead chevron onto every stepper in the toolkit): at {@code max}
     * the upper half is narrowed with {@link limn.accessibility.Accessibility#disabled()} and at
     * {@code min} the lower half is, and the publish step then withdraws the {@code PRESS} each
     * declares, so a reader says "unavailable" instead of offering an act that does nothing. What
     * stood here until 2026-09-15 was that a declared {@code ENABLED} is ignored so a reader must
     * infer the bound from the value against the bounds — the first half untrue since
     * {@code Accessibility#disabled()} arrived on 2026-09-14, the second half an inference the
     * owner has now declined to ask a reader to make. It is heard on every spinner in the toolkit,
     * twenty-two arrows in a colour picker among them.
     *
     * <p>The two predicates are the bound halves of {@code paintButtons}' own, {@code value < max}
     * and {@code value > min}, read from the same fields in the same pass: the arrow a reader is
     * told is unavailable is exactly the one drawn in {@code disabledText}, and the node cannot say
     * operable where the pixels say dead. The paint's conditions are {@code enabled && value < max}
     * and {@code enabled && value > min}, and the {@code enabled} half is deliberately not repeated
     * here: it is the publish step's, which inherits the owner's {@code ENABLED} down onto every
     * synthetic child and lets this method narrow and never widen
     * ({@link limn.accessibility.Accessibility#disabled()}), so a disabled spinner's two arrows are
     * published without {@code ENABLED} and without their {@code PRESS} whatever the value — which
     * the kitchen dialog's transcript shows, four {@code button "Increase" [disabled, ...]} rows
     * with no actions. So the two conditions agree by inheritance, not by being the same
     * expression, and this hook has only the bound to read. They are also exactly right about the verb, because
     * nothing here wraps: {@link #nudge} goes through {@link #settle}, which clamps to the bound
     * and returns false when the value did not move, and reaches {@code max} from anywhere below
     * it whatever the snap grid. A spinner whose own node is {@code INCREMENT}-at-{@code max} is
     * left alone by this: that is a value-bearing node stepping past the end of its own range, the
     * shape {@code VerbPolicyRatchetTest} reads off the facet for a scroll bar and a rail too, and
     * not an arrow.
     *
     * <p><b>The tree does not model the inline edit.</b> There is no text facet, so the node always
     * publishes the committed value and its display form: while the user is typing, what a reader
     * hears is the last committed number and lags the screen until Enter, Escape or focus loss;
     * there is no caret, no selection and no way to review the half-typed text; and a reader's
     * {@code SET_VALUE} cancels the edit outright. Building the facet properly needs the caret box,
     * the caret offset with its side, the selection and a witness for the editor's text, none of
     * which this widget exposes today.
     *
     * <p>The two arrow boxes are derived from the two expressions {@link #regionAt} is written in
     * terms of rather than from what {@code paintButtons} draws, so the published rectangle is the
     * hit region by construction and stays truthful when a parent squeezes this widget narrower
     * than the column — {@link #onMeasure}'s floor is a request, and a spinner that was never laid
     * out has width zero. They mirror with the column, which reading right to left is the one at
     * {@code x = 0}. The lower box takes the remainder of the height rather than a second half, so
     * the two tile the column exactly and the lower one matches the open-ended half
     * {@code regionAt} tests. No {@code offScreen}: the column is inside this widget's own rounded
     * clip whenever the widget is on screen.
     *
     * <p>Two nameless buttons per spinner is a real cost, and twenty-two of them in a colour
     * picker's tree, where a native stepper is one element. They are kept because each is a
     * separately hit-testable region with its own box, its own hover and its own disabled paint,
     * and an operable box is not deleted.
     *
     * <p>The box, the language, the enabled, visible, showing, focusable and focused bits and the
     * focus and scroll-into-view verbs are the walk's.
     *
     * @param a the node being described
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.SPIN_BUTTON);
        // The raw value, which in time mode is minutes since midnight, and the display form handed
        // over from the paint's own memo with the counter it was filled against. NEVER format(v)
        // or text() built here: the format call is documented as never cached, so a string made in
        // this hook would be one allocation per damaged frame spent deciding nothing had moved.
        // The VALUE shape, written once (ADR 045 §3): the number, its bounds, its step and the
        // two step verbs; SET_VALUE is advertised by the writable facet's presence and is never
        // listed. The display form is this widget's own, beside it.
        ValueAccessibility.describe(a, value, min, max, increment(), false);
        a.valueText(text(), formattedRevision());

        // Resolved ONCE for the whole hook, the rule this widget already states for its event
        // handlers: an index into the five cached rows, and not an allocation.
        SizeTokens t = Theme.of(this).tokensFor(this);
        float w = Math.max(0, width());
        float columnW = Math.min(t.spinnerButtonW(), w);
        float columnX = isRightToLeft() ? 0 : w - columnW;
        float mid = height() / 2;
        a.child(UP_BUTTON);
        a.bounds(columnX, 0, columnW, mid);
        a.role(Accessible.Role.BUTTON);
        // Named by the toolkit, because nothing else can: a synthetic child is not a widget, so
        // setAccessibleName, a bound caption and the tooltip default all land on the spinner's own
        // node and none of them reaches these two. Constants the class holds and never strings
        // built here, so a spinner damaged by its own repeat allocates nothing to say what it is.
        a.name(ComponentStrings.SPINNER_INCREMENT, Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        if (!(value < max)) {
            // paintButtons' own predicate, negated: the half drawn in disabledText is the half
            // published without ENABLED, and the publish step then takes the PRESS above off it
            // (decision 69; decision 30, semantics 5). Written as !(value < max) and not
            // value >= max so that a NaN bound -- which the constructor cannot produce and a
            // future setter might -- narrows rather than publishing a live arrow.
            a.disabled();
        }
        a.endChild();
        a.child(DOWN_BUTTON);
        a.bounds(columnX, mid, columnW, height() - mid);
        a.role(Accessible.Role.BUTTON);
        a.name(ComponentStrings.SPINNER_DECREMENT, Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        if (!(value > min)) {
            a.disabled();
        }
        a.endChild();
    }

    /**
     * The widget's own two steps and a set, each reaching the path the <em>keyboard</em> reaches,
     * because Up and Down are the keys an assistive technology's increment and decrement stand for.
     *
     * <p>So a step mid-edit goes through {@link #stepFromTyped} exactly as Up does: the number on
     * screen is adopted silently, the step is the one reported change, and the edit stays open with
     * the editor re-seeded from the committed value, so the published value and the typed text
     * agree afterwards. The arrow buttons take the other path and end the edit instead; see
     * {@link #onSyntheticAction}, which is the finding this widget produced — the two gestures are
     * two different private paths, and each verb reaches the one its own box reaches.
     *
     * <p><b>Neither step mirrors.</b> {@code onKeyEvent} turns Left and Right round under a
     * right-to-left layout and leaves Up and Down alone, and these two are the Up and Down sense:
     * a direction of the value, not a side of the box. A hook built out of the mirrored horizontal
     * pair would run backwards in Arabic and Hebrew.
     *
     * <p>{@code SET_VALUE} takes a number or a text (decision 5; CRIT-7's widget half,
     * 2026-09-14). A text is read by {@link #parse}, the same reader a typed commit goes
     * through — {@code 42}, {@code 1,5} with the comma most of the world types, {@code 7:30} on a
     * time spinner, any known digit set — because a platform's value pattern hands over the
     * string a client spoke or typed ({@code Value.SetValue("42")} on Windows, once the bridge
     * routes it here), and a reader that could only set by number could not set at all. It
     * refuses anything that is not a number, in either form, <em>before</em> the clamp, which
     * is where the real defect would be: {@code Math.max}/{@code Math.min} pass {@code NaN}
     * through untouched and {@code Math.rint} keeps it, so an unguarded set would store it and
     * publish it forever. It then cancels the edit for the reason {@link #setValue}'s own
     * documentation gives — the half-typed text is no longer about the number now in the field —
     * and applies through the from-the-user path, never through {@code setValue}, which is the
     * silent one and would move the value while telling the application nothing.
     *
     * <p>The enabled check is the one the key path keeps for itself; the scene has already
     * re-checked the ancestor chain, showing and modality before the call arrives. What a reader
     * hears is the value change the next publish's difference produces; no verb here is
     * acknowledged with an invocation, on any platform.
     *
     * @param action what was asked
     * @param arg    the value for {@code SET_VALUE}; ignored by the two steps
     * @return whether the request ran
     */
    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        return ValueAccessibility.perform(valueHost, action, arg);
    }

    /**
     * The spinner's mechanisms as the value shape drives them (ADR 045 §3): a step is the key's
     * path, a set reads a text through the same {@link #parse} a typed commit does, cancels the
     * edit and applies from the user.
     */
    private final class ValueHost implements ValueAccessibility.Host {
        @Override
        public boolean isEnabled() {
            return Spinner.this.isEnabled();
        }

        @Override
        public void step(int direction) {
            stepAsAKeyWould(direction);
        }

        @Override
        public double numberOf(Accessible.Argument arg) {
            if (arg instanceof Accessible.Argument.OfText text) {
                Double parsed = parse(text.text());
                return parsed != null && Double.isFinite(parsed) ? parsed : Double.NaN;
            }
            return Accessible.Argument.finiteValueOf(arg);
        }

        @Override
        public void set(double asked) {
            cancelEdit();
            apply(asked, Change.Origin.USER);
        }
    }

    private final ValueHost valueHost = new ValueHost();

    /**
     * One step in {@code direction}, which is what {@code nudgeFromKey(direction, 0)} is in both
     * modes: no Shift and no Alt is a plain step, and time mode ignores both anyway.
     */
    private void stepAsAKeyWould(int direction) {
        if (edit != null) {
            stepFromTyped(() -> nudge(direction));
        } else {
            nudge(direction);
        }
    }

    /**
     * A press on one half of the stepper column, reaching the <em>pointer</em> path, because each
     * half's published box is the box a click lands in.
     *
     * <p>{@link #commitEdit} first, which is verbatim what a press on region 1 or 2 does and for
     * the reason it gives: reaching for the arrows is leaving the text, so the step lands on the
     * number the user typed rather than on the one it replaced. That is the whole difference from
     * the parent node's two verbs, which adopt the typed number silently and leave the edit open.
     *
     * <p><b>{@link #startHold} is deliberately not armed.</b> A press from an assistive technology
     * has no release to stop it, so an armed auto-repeat would step the value every 55&nbsp;ms
     * until it hit a bound, with nothing the user could do about it.
     *
     * <p>The scene raises the invocation for a press that returned true, which is the
     * acknowledgement a press owes; the value change reaches a reader as the next publish's
     * difference.
     *
     * <p><b>An arrow at its bound is refused here too</b> (decision 69, 2026-09-16), by the same
     * two bounds the hook publishes and before {@code commitEdit} or anything else runs. Such an
     * arrow publishes no verb, so a press can only arrive from a platform working off a stale
     * snapshot — but nothing between that platform and this method rereads the node:
     * {@code Scene#performAccessibleAction} gates a synthetic press on the <em>owner's</em> enabled
     * chain, which a narrowed child does not narrow. Until 2026-09-16 the refusal was left to the
     * answer alone, and with an edit open there was nothing to refuse it: the press committed the
     * typed number on the way to a step it could not take, so the value moved, the application's
     * handler ran and the scene spoke an {@code INVOKED} for a dead arrow.
     *
     * <p><b>The answer is then whether anything moved</b>, kept as the second line of defence for
     * a live arrow that clamps anyway (a NaN bound, a snap grid that lands on the value it left).
     * It is read off the value rather than off {@link #nudge} alone because {@link #commitEdit}
     * above it can move the value too: reaching for the arrows is leaving the text, verbatim what a
     * click on this box does, and a press that adopted a typed number and then could not step did
     * change something. {@code SegmentedControl}'s dead chevron answers the same way, by whether
     * the scroll moved.
     *
     * @param key    which half, and any other key is refused
     * @param action what was asked, which for these two is a press and nothing else
     * @param arg    unused: a press carries no argument
     * @return whether the request ran
     */
    @Override
    protected boolean onSyntheticAction(long key, Accessible.Action action,
                                        Accessible.Argument arg) {
        if (!isEnabled() || action != Accessible.Action.PRESS) {
            return false;
        }
        int direction;
        if (key == UP_BUTTON) {
            direction = 1;
        } else if (key == DOWN_BUTTON) {
            direction = -1;
        } else {
            return false;
        }
        // The publish step's own two bounds, read again here and BEFORE any side effect, because
        // nothing upstream checks them for a synthetic child: Scene#performAccessibleAction gates
        // a synthetic press on the OWNER's enabled chain and never consults the child's withdrawn
        // verb, so a platform working off a stale snapshot reaches this method whatever the node
        // said. Without this line a press on the dead arrow with an edit open committed the typed
        // number on its way to a step it could not take -- the value moved, the application's
        // handler was called, and `value != before` answered true for the commit alone, which is
        // the INVOKED decision 69 exists to stop a reader speaking of a dead arrow.
        if (direction > 0 ? !(value < max) : !(value > min)) {
            return false;
        }
        double before = value;
        commitEdit();
        nudge(direction);
        return value != before;
    }

    /** Decimal places needed to render {@code step} exactly (0–6). */
    private static int decimalsFor(double step) {
        for (int d = 0; d <= 6; d++) {
            double scaled = step * Math.pow(10, d);
            if (Math.abs(scaled - Math.rint(scaled)) < 1e-9) {
                return d;
            }
        }
        return 2;
    }
}
