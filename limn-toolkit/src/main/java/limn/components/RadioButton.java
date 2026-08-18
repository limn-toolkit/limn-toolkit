package limn.components;

import limn.animation.Easing;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.i18n.I18nString;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A single-choice control: a ring that fills with a dot when selected. Radios in
 * a {@link ButtonGroup} are mutually exclusive: selecting one deselects the rest.
 * Unlike a {@link Checkbox}, clicking a selected radio does <em>not</em> turn it
 * off. Selected by click, or Space/Enter when focused. Colours from {@link Theme},
 * extents from the {@link SizeTokens} row resolved on this widget, pens from
 * {@link Strokes}.
 *
 * <p>The ring rides {@code indicator}, the <em>same</em> token as {@link Checkbox}'s
 * box, so a form mixing the two keeps every label on one optical column at every
 * step.
 *
 * <h2>This row is under the 24 pt pointer target</h2>
 * The row is {@code max(indicator, lineHeight)} rather than the control-height ramp, and the
 * indicator wins that max at every step (18 pt at MEDIUM), which is below the 24 pt target
 * of WCAG 2.2 SC 2.5.8 (AA) on the axis that decides it: a label widens a target, it never
 * heightens it. A radio's pointer target is exactly the ring-and-label box it paints.
 *
 * <p>The standard's own <em>Spacing</em> exception is what an application relies on: an
 * undersized target conforms while a 24 pt circle centred on it clears every neighbour. A
 * lone radio already satisfies that; a group (the normal case) does not until the pitch
 * reaches 24, which is what {@link Tokens#toggleColumnGap(limn.scene.Widget)} gives. Stack
 * a {@link ButtonGroup}'s radios on that gap, not a tighter one.
 */
public class RadioButton extends Widget {

    /**
     * The ring stroke is centred one point inside the indicator box, leaving its outer
     * ink edge 0.25 pt clear of the measured width. An alignment correction for a locked
     * pen, so it is locked too: it has no five-column row.
     */
    private static final float RING_ALIGN_INSET = 1;

    private I18nString text;
    private boolean selected;
    private ButtonGroup group; // null = standalone
    private Consumer<Boolean> onChange = value -> {
    };
    /** 0 = empty, 1 = full dot; eased toward the state. */
    private final Transition progress =
            new Transition(this, 0).duration(Theme.current().animFade).easing(Easing.LINEAR);
    private final Transition hover =
            new Transition(this).duration(Theme.current().animHover).easing(Theme.current().animEasing);
    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);

    /** A radio with a fixed label; see the {@link I18nString} constructor for localized text. */
    public RadioButton(String text) {
        this(I18nString.literal(Objects.requireNonNull(text, "text")));
    }

    /** A radio button whose label follows the UI language; see {@link I18nString}. */
    public RadioButton(I18nString text) {
        this.text = Objects.requireNonNull(text, "text");
        setFocusable(true);
        setCursor(Cursor.POINTER);
    }

    /** Fires with {@code true} when this radio becomes selected, {@code false} when a sibling takes over. */
    public RadioButton onChange(Consumer<Boolean> listener) {
        Ui.checkUiThread();
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** Replaces the label with a fixed string. UI thread only. */
    public RadioButton setText(String newText) {
        Ui.checkUiThread();
        this.text = I18nString.literal(Objects.requireNonNull(newText, "newText"));
        markNeedsLayout();
        return this;
    }

    /** Whether this radio is the selected one in its group. */
    public boolean isSelected() {
        return selected;
    }

    /**
     * Selects this radio (idempotent). In a group, deselects the previously
     * selected sibling and notifies the group; standalone, just selects itself.
     */
    public void select() {
        Ui.checkUiThread();
        if (selected) {
            return; // radios never toggle off by re-selecting
        }
        if (group != null) {
            group.select(this); // deselects siblings, sets this, fires listeners
        } else {
            setSelectedSilently(true);
            onChange.accept(true);
        }
    }

    // -------------------------------------------------- ButtonGroup coordination
    void attachToGroup(ButtonGroup owner) {
        this.group = owner;
    }

    /** Updates the visual state without firing listeners (the group drives notification). */
    void setSelectedSilently(boolean value) {
        if (selected == value) {
            return;
        }
        selected = value;
        progress.to(value ? 1 : 0);
        invalidate();
    }

    void fireChange(boolean value) {
        onChange.accept(value);
    }

    // ---------------------------------------------------------------- layout
    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        TextMetrics metrics = textRuler().measure(text.get(), t.body());
        float width = t.indicator() + (text.get().isEmpty() ? 0 : t.gapLabel() + metrics.width());
        float height = Math.max(t.indicator(), metrics.lineHeight());
        return constraints.constrain(width, height);
    }

    @Override
    protected float baselineOffset() {
        // The empty-text guard is load-bearing, and it is Checkbox's shape on purpose: this
        // control and Checkbox BOX are in declared lockstep, so an unlabelled radio must
        // report the same reference as an unlabelled box or a BASELINE row containing both
        // drops one of them by several points. Widget.baselineOffset()'s contract is that a
        // widget with no text aligns on its bottom edge.
        if (text.get().isEmpty()) {
            return super.baselineOffset();
        }
        SizeTokens t = Theme.current().tokensFor(this);
        TextMetrics metrics = textRuler().measure(text.get(), t.body());
        return (height() - metrics.height()) / 2 + metrics.ascent();
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        float ring = t.indicator();
        float top = (height() - ring) / 2;
        float p = progress.value();
        float cx = ring / 2;
        float cy = top + ring / 2;
        Color ringInk = !isEnabled() ? theme.disabledFill
                : p > 0 ? theme.primary
                : theme.outline.lerp(theme.primaryHover, hover.value());
        canvas.drawCircle(cx, cy, ring / 2 - RING_ALIGN_INSET, Strokes.INDICATOR_BORDER, ringInk);
        if (p > 0.05f) {
            Color dot = (isEnabled() ? theme.primary : theme.disabledText).withAlpha(p);
            // Resolved here, per frame: a step change mid-transition retargets the dot
            // instead of easing on toward the radius the old step wanted.
            canvas.fillCircle(cx, cy, (ring / 2 - t.indicatorInset()) * p, dot);
        }
        if (!text.get().isEmpty()) {
            TextMetrics metrics = textRuler().measure(text.get(), t.body());
            Color ink = isEnabled() ? theme.text : theme.disabledText;
            canvas.drawText(text.get(), ring + t.gapLabel(),
                    (height() - metrics.height()) / 2 + metrics.ascent(), t.body(), ink);
        }
        float focus = focusFade.value();
        if (focus > 0.001f) {
            // 1.5 here is unchanged by D3: this side was already the wider of the two gaps, and
            // Checkbox came up to meet it. RING_ALIGN_INSET pulls the ring's outer ink 0.25pt
            // inside the box, so the focus ink starts a full 1pt clear of it.
            canvas.drawCircle(cx, cy, ring / 2 + Strokes.FOCUS_GAP_INDICATOR,
                    Strokes.FOCUS_RING_THIN, theme.focusRing.withAlpha(focus));
        }
    }

    /**
     * The focus circle is the only thing that paints outside the box: radius
     * {@code ring/2 + }{@link Strokes#FOCUS_GAP_INDICATOR} with a centred
     * {@link Strokes#FOCUS_RING_THIN} pen puts its outer ink 2.25pt past the indicator, which is
     * flush with the widget's left edge and (since the row <em>is</em> the indicator at every
     * step) with its top and bottom. {@link limn.scene.Scene} assumes only 1pt of AA feather, so
     * without this the fading ring left stale pixels under partial rendering. Deliberately the
     * same expression as {@link Checkbox}'s: the two are in declared lockstep and must damage the
     * same rectangle, or a form column mixing them repaints unevenly.
     */
    @Override
    protected float paintOutset() {
        return Strokes.FOCUS_GAP_INDICATOR + Strokes.FOCUS_RING_THIN / 2;
    }

    // ----------------------------------------------------------------- input
    @Override
    protected void onMouseEvent(MouseEvent event) {
        switch (event.type()) {
            case ENTER -> hover.to(1);
            case EXIT -> hover.to(0);
            case PRESS -> event.consume();
            case CLICK -> {
                if (event.button() == Keys.MOUSE_LEFT) {
                    event.consume();
                    select();
                }
            }
            default -> {
            }
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed()) {
            return;
        }
        if ((event.key() == Keys.SPACE || event.key() == Keys.ENTER) && !event.isRepeat()) {
            event.consume();
            select();
            return;
        }
        if (group == null) {
            return; // a standalone radio has nothing to arrow between
        }
        // Both axes, deliberately: a group may be laid out in a row or a column and the widget
        // cannot see which, so every platform that implements this accepts all four.
        switch (event.key()) {
            case Keys.UP, Keys.LEFT -> {
                event.consume();
                group.moveSelection(this, -1);
            }
            case Keys.DOWN, Keys.RIGHT -> {
                event.consume();
                group.moveSelection(this, 1);
            }
            default -> {
            }
        }
    }

    @Override
    protected void onFocusGained() {
        focusFade.to(1);
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
    }
}
