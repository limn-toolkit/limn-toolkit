package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.internal.a11y.RowsAccessibility;
import limn.animation.Easing;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.i18n.I18nString;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.internal.lang.Checks;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Change;
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
 * <p>The ring sits on the row's <em>leading</em> edge with the label running away from it, so
 * both swap sides with the {@link limn.scene.LayoutDirection} resolved on this widget. The ring
 * is a circle and the dot is concentric with it, so nothing inside the indicator has a side to
 * reflect; only the column the pair occupies moves.
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
public final class RadioButton extends Widget<RadioButton> {

    /**
     * The ring stroke is centred one point inside the indicator box, leaving its outer
     * ink edge 0.25 pt clear of the measured width. An alignment correction for a locked
     * pen, so it is locked too: it has no five-column row.
     */
    private static final float RING_ALIGN_INSET = 1;

    private I18nString text;
    private boolean selected;
    private ButtonGroup group; // null = standalone
    private Consumer<Boolean> onChange;
    /** 0 = empty, 1 = full dot; eased toward the state. */
    private final Transition progress =
            new Transition(this, 0).duration(Theme.of(this).animFade).easing(Easing.LINEAR);
    private final Transition hover =
            new Transition(this).duration(Theme.of(this).animHover).easing(Theme.of(this).animEasing);
    private final Transition focusFade =
            new Transition(this).duration(Theme.of(this).animFocus).easing(Theme.of(this).animEasing);

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
        this.onChange = Checks.handlerSlot(onChange, listener, "RadioButton.onChange");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.VALUE) {
            if (onChange != null) {
                onChange.accept(selected);
            }
            return;
        }
        super.handleUserChange(aspect);
    }

    /**
     * @return the caption beside the indicator, resolved in this widget's own language. The
     *         accessible tree takes this as the control's name.
     */
    public String text() {
        return text.get();
    }

    /**
     * @return the localizable string behind {@link #text()}. The "is it set here" reader, matching
     *         the pair {@link Button} and {@link Label} expose.
     */
    public I18nString textSource() {
        return text;
    }

    /** Replaces the label with a fixed string. UI thread only. */
    public RadioButton setText(String newText) {
        return setText(I18nString.literal(Objects.requireNonNull(newText, "newText")));
    }

    /**
     * Replaces the label with one that follows the UI language. UI thread only.
     *
     * @param newText the new caption
     * @return this radio button
     * @throws NullPointerException if {@code newText} is {@code null}
     */
    public RadioButton setText(I18nString newText) {
        Ui.checkUiThread();
        Objects.requireNonNull(newText, "newText");
        if (newText.equals(text)) {
            return this;
        }
        this.text = newText;
        markNeedsLayout();
        notifyChange(Change.of(Change.Aspect.NAME, Change.Origin.CODE));
        return this;
    }

    /** Whether this radio is the selected one in its group. */
    public boolean isSelected() {
        return selected;
    }

    /**
     * Selects this radio (idempotent). In a group, deselects the previously selected sibling
     * and tells the group; standalone, just selects itself. A {@code CODE} write spelled as a
     * verb: it announces {@code VALUE}/{@code CODE} on every member it moves and reaches no
     * handler, exactly as {@link ButtonGroup#setSelectedIndex} does.
     */
    public void select() {
        Ui.checkUiThread();
        select(Change.Origin.CODE);
    }

    /**
     * The seam every selection of this radio goes through, with the origin of whoever asked: the
     * public verb passes {@code CODE}; the click, the key, the arrow through the group and an
     * assistive technology's select pass {@code USER}. A radio group is the one compound whose
     * members' aspects are the toolkit's spelling of one change, so one origin runs through the
     * whole swap.
     */
    void select(Change.Origin origin) {
        Ui.checkUiThread();
        if (selected) {
            return; // radios never toggle off by re-selecting
        }
        if (group != null) {
            group.select(this, origin); // deselects siblings, sets this, announces both
        } else {
            setSelectedSilently(true);
            notifyChange(Change.of(Change.Aspect.VALUE, origin));
        }
    }

    // -------------------------------------------------- ButtonGroup coordination

    /**
     * Joins {@code owner}, and lets it re-decide its one tab stop whenever this member is enabled
     * or disabled.
     *
     * <p>The group chooses its holder among the enabled members and hears about a member's flag
     * from nowhere but this watcher: without it, a holder disabled after it was chosen left the
     * group with no focusable member while the others still worked, so Tab skipped the whole
     * group, and a group whose members had all been disabled never regained a tab stop when one
     * came back. This used to be a {@code setEnabled} override; the change channel is what a
     * component uses to hear its own state move now that the inherited setters are final.
     */
    void attachToGroup(ButtonGroup owner) {
        this.group = owner;
        observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.ENABLED) {
                owner.memberEnabledChanged(this);
            }
        });
    }

    /** Updates the visual state without announcing (the group drives the announcement). */
    void setSelectedSilently(boolean value) {
        if (selected == value) {
            return;
        }
        selected = value;
        progress.to(value ? 1 : 0);
        invalidate();
    }

    /**
     * The announcing half of this member's {@code VALUE}, for the group: both members of a swap
     * are announced before either handler runs, an order one indivisible {@code notifyChange}
     * per member cannot produce. The base class's two halves are {@code protected} on
     * {@code limn.scene.Widget} and the group is not a widget, so it reaches them through these
     * two package-private lines, the way it already drives {@link #setSelectedSilently}.
     */
    void announceSelection(Change.Origin origin) {
        announceChange(Change.of(Change.Aspect.VALUE, origin));
    }

    /** The handler half, a no-op at any origin but {@code USER}. */
    void runSelectionHandler(Change.Origin origin) {
        runHandler(Change.of(Change.Aspect.VALUE, origin));
    }

    /** Moves focus here as part of a gesture the group is handling, or of a swap it settles. */
    void focusFrom(Change.Origin origin) {
        requestFocus(origin);
    }

    // ---------------------------------------------------------------- layout
    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.of(this).tokensFor(this);
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
        SizeTokens t = Theme.of(this).tokensFor(this);
        TextMetrics metrics = textRuler().measure(text.get(), t.body());
        return (height() - metrics.height()) / 2 + metrics.ascent();
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.of(this);
        SizeTokens t = theme.tokensFor(this);
        // Resolved once for the whole pass, here rather than in a constructor, where this widget
        // has no parent yet and every answer is the process default. The ring, the dot, the label
        // and the focus circle are all composed from this one answer: two resolutions that
        // disagreed inside one paint would put the dot outside the ring it belongs in.
        ShapedText.Direction neutral = neutralBase();
        boolean rtl = neutral == ShapedText.Direction.RTL;
        float ring = t.indicator();
        float top = (height() - ring) / 2;
        float p = progress.value();
        // The indicator is flush with the LEADING edge of the row, which reading right to left is
        // the right one. Reflected once, at the coordinate: the ring stroke, the dot and the focus
        // circle all read this centre, and reflecting any of them a second time would move it
        // twice and leave the dot outside its own ring.
        float cx = rtl ? width() - ring / 2 : ring / 2;
        float cy = top + ring / 2;
        Color ringInk = !isEnabled() ? theme.disabledFill()
                : p > 0 ? theme.primary()
                : theme.outline().lerp(theme.primaryHover(), hover.value());
        canvas.drawCircle(cx, cy, ring / 2 - RING_ALIGN_INSET, Strokes.INDICATOR_BORDER, ringInk);
        if (p > 0.05f) {
            Color dot = (isEnabled() ? theme.primary() : theme.disabledText()).withAlpha(p);
            // Resolved here, per frame: a step change mid-transition retargets the dot
            // instead of easing on toward the radius the old step wanted.
            canvas.fillCircle(cx, cy, (ring / 2 - t.indicatorInset()) * p, dot);
        }
        String label = text.get();
        if (!label.isEmpty()) {
            TextMetrics metrics = textRuler().measure(label, t.body());
            Color ink = isEnabled() ? theme.text() : theme.disabledText();
            // The row's own direction is the shaper's NEUTRAL FALLBACK and never an imposition: a
            // Latin label in a right-to-left form still reads left to right, because a strong
            // character outranks the fallback. What it decides is the label that has no strong
            // character of its own -- a bare number, a symbol -- which is the one case the string
            // cannot answer and the surrounding interface can.
            ShapedText line = textRuler().shape(label, t.body(),
                    ShapedText.Direction.of(label, neutral));
            // Placed against the width onMeasure reserved, which for this label is also the shaped
            // ink's own: Checkbox now reserves and places from the shaped line directly, and the
            // two still mirror against the same number because the only text whose base the
            // fallback decides is text with no strong character at all -- and such a string is one
            // run, so its width does not depend on which base decided it. The lockstep the two
            // controls are in survives the difference in spelling; the lemma under it is asserted
            // in the backend's own shaping tests, because a direction-blind fake cannot see it.
            // A line is placed by its LEFT edge in either direction, so the mirrored label starts
            // a whole label width back from the gap it ends at.
            float labelX = rtl
                    ? width() - ring - t.gapLabel() - metrics.width()
                    : ring + t.gapLabel();
            canvas.drawText(line, labelX,
                    (height() - metrics.height()) / 2 + metrics.ascent(), ink);
        }
        float focus = focusFade.value();
        if (focus > 0.001f) {
            // 1.5 here is unchanged by D3: this side was already the wider of the two gaps, and
            // Checkbox came up to meet it. RING_ALIGN_INSET pulls the ring's outer ink 0.25pt
            // inside the box, so the focus ink starts a full 1pt clear of it.
            canvas.drawCircle(cx, cy, ring / 2 + Strokes.FOCUS_GAP_INDICATOR,
                    Strokes.FOCUS_RING_THIN, theme.focusRing().withAlpha(focus));
        }
    }

    /**
     * The focus circle is the only thing that paints outside the box: radius
     * {@code ring/2 + }{@link Strokes#FOCUS_GAP_INDICATOR} with a centred
     * {@link Strokes#FOCUS_RING_THIN} pen puts its outer ink 2.25pt past the indicator, which is
     * flush with the widget's <em>leading</em> edge &mdash; the left one reading left to right and
     * the right one reading right to left &mdash; and (since the row <em>is</em> the indicator at
     * every step) with its top and bottom. One outset covers every side, so the edge the indicator
     * moves to is already inside the damage and the direction is not an input here.
     * {@link limn.scene.Scene} assumes only 1pt of AA feather, so
     * without this the fading ring left stale pixels under partial rendering. Deliberately the
     * same expression as {@link Checkbox}'s: the two are in declared lockstep and must damage the
     * same rectangle, or a form column mixing them repaints unevenly.
     */
    @Override
    protected float paintOutset() {
        return Strokes.FOCUS_GAP_INDICATOR + Strokes.FOCUS_RING_THIN / 2;
    }

    // ---------------------------------------------------------- accessibility

    /**
     * What this radio is to an assistive technology: one {@link Accessible.Role#RADIO_BUTTON},
     * named by the caption it holds, a member of its group's selection with its one-based position
     * and the group's size, offering {@link Accessible.Action#SELECT}, and nothing else.
     *
     * <p>The name is the held {@link I18nString} handed over by reference, never {@link #text()}:
     * the walk compares the reference, the locale and the translation epoch, so a hover or focus
     * fade that damages this row on every frame concludes that nothing moved without allocating.
     * It is handed over even when the caption is empty, because the walk names a nameless node
     * from its tooltip and describes a named one with it; a caption-less radio with no tooltip
     * publishes an empty name, which is the application's to fix and not this widget's to invent.
     *
     * <p>The selection facet reads the {@code selected} field and never the eased dot, and its two
     * numbers come from the group through readers that touch its list and allocate nothing; the
     * public {@link ButtonGroup#members()} is a copy per call and would cost one per damaged frame.
     * A standalone radio has no set and says so with zeros, which a bridge publishes as nothing.
     * There is no group node for the numbers to hang under, because a {@link ButtonGroup} is not a
     * widget and has no box, and there is no relation to it for the same reason: a relation's
     * target has to be a published node. So the facet says the member has <b>no container</b>: a
     * radio's selection change is its own selected-state event and is never laid on whatever layout
     * node happens to be its published parent, and a bridge asked for its selection container
     * answers none. The radio is deliberately not marked {@link Accessible.State#ACTIVE}: the
     * publish step takes the first active node in a container's subtree as that container's active
     * descendant, and a radio inside a list cell would hijack the list's.
     *
     * <p>Only select is offered. A radio never toggles off, so there is no deselect, and the only
     * route to no selection is {@link ButtonGroup#clearSelection()} from code. The box, the focus
     * and scroll-into-view verbs on the group's one tab stop, and the enabled, visible, showing,
     * focusable and focused states are the walk's; roving focus is already expressed through
     * the focusable flag, whose setter invalidates the tree itself.
     *
     * @param a the node being described
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.RADIO_BUTTON);
        a.name(text, Accessible.NameFrom.CONTENT);
        // A containerless member of the ROWS shape (decision 107, 2026-09-22): the group is a
        // ButtonGroup and not a widget, so there is no container node, and the membership and
        // the one verb are the helper's, by the rules every row obeys.
        RowsAccessibility.describeContainerlessRow(a, RowsAccessibility.Selection.SINGLE,
                selected, group == null ? 0 : group.indexOf(this) + 1,
                group == null ? 0 : group.size());
    }

    /**
     * Performs a select an assistive technology asked for, exactly as a click or a Space press
     * does: through the seam at {@code USER}, so a group deselects the previous member, both
     * radios' {@link #onChange} handlers and the group's own listener are notified the same way,
     * and the tab stop moves with the selection. Selecting the member that is already selected
     * is accepted and does nothing, because the state asked for holds. Any other verb is refused,
     * including a press, which this node does not offer, and so is a select while this widget is
     * disabled: the seam carries no such guard of its own, because it is also the path
     * {@link ButtonGroup#setSelectedIndex} takes to restore a disabled form's state, and the
     * answer here has to be truthful.
     *
     * <p>The state change that results is what a reader hears: the group's swap invalidates both
     * members, the next frame republishes, and the difference raises the selected-state event on
     * each. No invocation event is raised for a select, on any platform.
     *
     * @param action what was asked
     * @param arg    ignored; a select carries none
     * @return whether the select ran, or already held
     */
    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        // By the rows shape's rules (decision 107): SELECT selects, everything else is refused,
        // and the cursor is the selection here, so no FOCUS is offered or performed.
        return RowsAccessibility.performOnRow(rowsHost, this, action);
    }

    /**
     * This radio's mechanisms as the rows shape drives them: a select at {@code USER} through
     * the group's seam, refused while disabled because the seam carries no guard of its own.
     */
    private final RowsAccessibility.Host<RadioButton> rowsHost = new RowsAccessibility.Host<>() {
        @Override
        public RowsAccessibility.Selection selection() {
            return RowsAccessibility.Selection.SINGLE;
        }

        @Override
        public boolean cursorIsTheSelection() {
            return true;
        }

        @Override
        public boolean rowsActivate() {
            return false;
        }

        @Override
        public boolean isSelected(RadioButton row) {
            return row.selected;
        }

        @Override
        public boolean select(RadioButton row, boolean moveCursor) {
            if (!row.isEnabled()) {
                return false;
            }
            row.select(Change.Origin.USER);
            return true;
        }

        @Override
        public void moveCursor(RadioButton row) {
            throw new UnsupportedOperationException("the cursor is the selection");
        }

        @Override
        public void reveal(RadioButton row) {
            row.revealInView();
        }
    };

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
                    select(Change.Origin.USER);
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
            select(Change.Origin.USER);
            return;
        }
        if (group == null) {
            return; // a standalone radio has nothing to arrow between
        }
        // Both axes, deliberately: a group may be laid out in a row or a column and the widget
        // cannot see which, so every platform that implements this accepts all four.
        //
        // Resolved once for this event, and read by the horizontal pair alone. LEFT and RIGHT name
        // a side of the screen, so reading right to left LEFT is the later member; UP and DOWN
        // name a side of the page, which no reading direction moves. That is why these are four
        // arms and not two: a horizontal key sharing an arm with a vertical one cannot mirror
        // without dragging the vertical axis around with it, and a group laid out in a column
        // would then walk backwards for nothing.
        boolean rtl = isRightToLeft();
        switch (event.key()) {
            case Keys.UP -> stepSelection(event, -1);
            case Keys.DOWN -> stepSelection(event, 1);
            case Keys.LEFT -> stepSelection(event, rtl ? 1 : -1);
            case Keys.RIGHT -> stepSelection(event, rtl ? -1 : 1);
            default -> {
            }
        }
    }

    /**
     * Consumes an arrow and walks the group by {@code step}. Only reached from
     * {@link #onKeyEvent} past its own null check, so the group is never null here.
     */
    private void stepSelection(KeyEvent event, int step) {
        event.consume();
        group.moveSelection(this, step);
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
