package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.internal.a11y.LeafActionAccessibility;
import limn.components.internal.a11y.PopupOwnerAccessibility;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.internal.lang.Checks;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Push button with the full state set (normal, hover, pressed, disabled,
 * focused) and an {@link #onAction(Runnable)} fired by click or by
 * Enter/Space while focused. All visuals come from the {@link Theme}. Keep
 * actions short (the slow-handler instrumentation will flag violations);
 * heavy work goes through {@code Ui.async}.
 *
 * <p>Sizes follow the {@link ControlSize} resolved on this widget: every metric comes from
 * the {@link SizeTokens} row, every weight from {@link Strokes}. Tokens are resolved once per
 * pass into a local and threaded down; resolving twice inside one pass is how measure and
 * paint end up disagreeing about where the label sits.
 *
 * <p>A caption alone, or an icon alone, is centred, so a text-only button draws the same
 * coordinates whichever way its subtree reads. An icon beside a caption is
 * {@linkplain #setIcon(Icon) leading}: it stands from the edge reading starts from by the inset a
 * text field's or a combo box's leading icon keeps, so icons line up down a form, and the caption
 * is centred in the rest of the button inside the button's own padding, which at the width the
 * button asks for puts it the icon gap away. Reading right to left the whole of that is mirrored.
 * Whether the glyph drawn inside the square turns around as well is the caller's to say &mdash;
 * see {@link Icon.Mirroring}.
 *
 * <p><b>An icon with no caption makes a square button</b>: as wide as it is tall, which is the
 * control height of its {@link ControlSize}, 24, 28, 32, 40 or 50 points, the icon in the middle.
 * That is the width it asks for, and like any widget's it is the parent's to grant: a button that
 * should be wider or narrower says so with {@link #setMinWidth} or {@link #setMaxWidth}, both
 * together for an exact width, and the icon stays in the middle of the box it is given. Those
 * bounds are the one way to say it, for this button as for any other widget; with none set, the
 * square is the default. At the smallest step the square is 24 points, the pointer target this
 * toolkit keeps every control at or above. A button with a caption, beside an icon or not, asks
 * for its caption's width and padding as before.
 */
public final class Button extends Widget<Button> {

    {
        // Alt and the letter, found by the scene; not API: the letter is written in the text.
        limn.scene.internal.SceneAccess.ownAccessLetter(this, limn.scene.internal.AccessLetterOwner.of(
                this::accessLetter, this::accessLetterFocus, this::accessLetterPressed));
    }

    private I18nString text;
    /** The caption read for its access letter: "&amp;Save" shows "Save", with S as the letter. */
    private final AccessLetters.Held marked = new AccessLetters.Held();
    private Icon icon;
    // Whether the icon's own glyph turns around when the interface does, which is a different
    // question from which end of the block its square takes. NEVER by default, which is what
    // keeps every existing setIcon call meaning exactly what it meant.
    private Icon.Mirroring iconMirroring = Icon.Mirroring.NEVER;
    // ONE shaping of text.get(), held so the measure pass and the paint pass that follows it
    // shape the caption once between them and cannot disagree about its width. Refreshed
    // through ShapedText.matches, whose identity fast path on the string costs nothing here
    // because I18nString memoizes it.
    private ShapedText caption;
    private Style style = Style.PRIMARY;
    private boolean destructive;
    private Runnable action;
    // The one condition, beside the enabled bit the walk and the scene own, under which PRESS is
    // published and performed; null for always. Package-private: a Dialog withdraws its buttons'
    // press while its answer fades out, where the press would run a handler whose own guard
    // drops it (2026-09-15, semantics 5).
    private java.util.function.BooleanSupplier pressAccepted;
    // Whether Return presses the button while it has the focus. Package-private: a Dialog's
    // destructive button lets Return through to the dialog, whose answer to it is the default
    // button's or none, so that Return never deletes (2026-10-19). Space still presses it.
    private boolean pressedByReturn = true;
    // Hover and focus-ring fades, animated through the shared Transition.
    private final Transition hover =
            new Transition(this).duration(Theme.of(this).animHover).easing(Theme.of(this).animEasing);
    private final Transition focusFade =
            new Transition(this).duration(Theme.of(this).animFocus).easing(Theme.of(this).animEasing);
    private boolean armed;    // mouse press in progress
    private boolean keyArmed; // Space/Enter held: separate from the mouse so a
                              // key-up whose key-down never reached us can't fire

    /** A button with a fixed caption; see the {@link I18nString} constructor for localized text. */
    public Button(String text) {
        this(I18nString.literal(Objects.requireNonNull(text, "text")));
    }

    /** A button whose caption follows the UI language; see {@link I18nString}. */
    public Button(I18nString text) {
        this.text = Objects.requireNonNull(text, "text");
        setFocusable(true);
        setCursor(Cursor.POINTER); // click hint; disabled buttons aren't hit-tested → arrow
    }

    /**
     * Sets a leading icon, drawn tinted to the label color. {@code null} clears it. Leading is
     * the end of the caption block reading starts from, so the square trades ends with the
     * caption when the button reads right to left; the glyph inside it is drawn as authored.
     * Use {@link #setIcon(Icon, Icon.Mirroring)} for an icon that means a direction.
     */
    public Button setIcon(@Nullable Icon newIcon) {
        return setIcon(newIcon, Icon.Mirroring.NEVER);
    }

    /**
     * A leading icon that says whether its glyph turns around when the interface does. Only the
     * code that placed an icon knows whether its arrow means "back" or "download", which is why
     * this is a flag here and never a classification inside the toolkit.
     *
     * @param mirroring {@link Icon.Mirroring#NEVER} unless this glyph is directional; the
     *                  square's own end of the block moves either way
     */
    public Button setIcon(@Nullable Icon newIcon, Icon.Mirroring mirroring) {
        Ui.checkUiThread();
        Objects.requireNonNull(mirroring, "mirroring");
        if (icon == newIcon && iconMirroring == mirroring) {
            return this;
        }
        this.icon = newIcon;
        this.iconMirroring = mirroring;
        markNeedsLayout();
        // A button whose label is an icon has no other name to give, so the icon is a NAME too.
        notifyChange(Change.of(Change.Aspect.NAME, Change.Origin.CODE));
        return this;
    }

    /**
     * How much a button asks to be pressed, of the three looks a row of buttons is drawn in. The
     * box is the same at every style: padding, height, radius and focus ring are the step's, so a
     * {@link #TERTIARY} button lines up with the others and keeps a full target.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Style {
        /** The one action a view or a dialog is for: filled in the palette's accent. */
        PRIMARY,
        /** Any other action beside it: the surface's own fill, an outline, the text colour. */
        SECONDARY,
        /**
         * A quiet action that should not compete (a tool bar's, a "Cancel" beside a "Delete"): no
         * fill and no border at rest, the caption in colour, and a tint of it under the pointer.
         */
        TERTIARY
    }

    /**
     * Which of the three looks this button is drawn in; {@link Style#PRIMARY} by default.
     *
     * @param newStyle the look; never null
     * @return this button
     */
    public Button setStyle(Style newStyle) {
        Ui.checkUiThread();
        Objects.requireNonNull(newStyle, "style");
        if (style == newStyle) {
            return this;
        }
        this.style = newStyle;
        // Paint only: the box is the same at every style, which is the point of the three.
        invalidate();
        return this;
    }

    /** @return the look this button is drawn in */
    public Style style() {
        return style;
    }

    /**
     * Marks the button as one that destroys something ("Delete order"), drawn in the palette's
     * danger colour at whichever {@link Style} it has: a primary one is filled in it, a secondary
     * and a tertiary one write their caption in it.
     *
     * <p>Nothing is published for it: no platform carries the notion to a reader, and the caption
     * is what says what the button does. A dialog's destructive button is never its default one;
     * see {@link Dialog#addDestructiveButton(I18nString, String)}.
     *
     * @param value whether the button destroys something
     * @return this button
     */
    public Button setDestructive(boolean value) {
        Ui.checkUiThread();
        if (destructive == value) {
            return this;
        }
        this.destructive = value;
        invalidate();
        return this;
    }

    /** @return whether the button is drawn as one that destroys something */
    public boolean isDestructive() {
        return destructive;
    }

    /** How far a hovered button's state moves: toward the text colour, or a tint's alpha. */
    private static final float STATE_HOVER = 0.08f;
    /** How far a pressed button's state moves, twice the hover's. */
    private static final float STATE_PRESSED = 0.16f;

    /**
     * The caption's colour while enabled, by style and by whether the button destroys something.
     * One method, because the tertiary look's tint is the same colour as its caption.
     */
    private Color ink(Theme theme) {
        return switch (style) {
            case PRIMARY -> theme.onPrimary();
            case SECONDARY -> destructive ? theme.danger() : theme.text();
            // The accent as text, the one place the colour that says "this is an action" is kept
            // once the fill is gone: primary itself where it reads as text on every surface, and
            // moved toward the text colour where it does not (11 of the 15 built-in palettes,
            // whose primary is solved for a fill and falls under 4.5:1 on a raised surface).
            case TERTIARY -> destructive ? theme.danger() : theme.primaryText();
        };
    }

    /**
     * Horizontal room the icon claims, its gap to the label included: one expression so the
     * measure and paint sides cannot drift apart and silently mis-centre the content.
     *
     * <p>{@code iconBox} is integral at every step, which is what keeps the icon
     * rasterization cache's key count bounded: a fractional box would key a new
     * bitmap per step and per content scale.
     */
    private float iconAdvance(SizeTokens t) {
        if (icon == null) {
            return 0;
        }
        return t.iconBox() + (shown().text().isEmpty() ? 0 : t.gapIcon());
    }

    /** Whether the content is an icon and a caption, the one shape whose two sides differ. */
    private boolean iconBesideCaption() {
        return icon != null && !shown().text().isEmpty();
    }

    /** Whether the content is an icon and nothing else, the shape that asks for a square. */
    private boolean iconAlone() {
        return icon != null && shown().text().isEmpty();
    }

    /**
     * The room on the side reading starts from. Beside a caption the icon stands from the edge as
     * a field's or a combo's leading icon does ({@code fieldPadH}), and the caption keeps the
     * button's own padding on its side: the caption's wide padding on the icon's side too set the
     * icon nearly twice as far in as the same icon in a combo beside it. A caption alone keeps
     * {@code padH} on both sides; an icon alone asks for a square instead and is centred in it, so
     * no padding of this kind reaches its measure.
     */
    private float leadingPad(SizeTokens t) {
        return iconBesideCaption() ? t.fieldPadH() : t.padH();
    }

    /**
     * The application's response to the user pressing the button: a click, Space or Enter while
     * focused, an assistive technology's press. Not called while disabled. A press is announced
     * to the {@linkplain #observeChanges watchers} as {@code INVOKED} first.
     *
     * @param newAction the handler, or {@code null} to clear the slot
     * @return this button
     * @throws IllegalStateException if a handler is already registered
     */
    public Button onAction(@Nullable Runnable newAction) {
        Ui.checkUiThread();
        this.action = Checks.handlerSlot(action, newAction, "Button.onAction");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.INVOKED) {
            if (popover != null) {
                // The press opens the popover attached to this button, or closes it while open,
                // before the application's own handler hears the press.
                if (popover.isOpen()) {
                    popover.close();
                } else {
                    popover.open();
                }
            }
            if (action != null) {
                action.run();
            }
            return;
        }
        super.handleUserChange(aspect);
    }

    /**
     * The popover {@link Popover#attachTo} gave this button, as the owner shape drives it, or
     * {@code null}: the button then says it opens a popup and whether it is open, and offers
     * expand and collapse in place of press.
     */
    private PopupOwnerAccessibility.@Nullable Host popover;

    void attachPopover(PopupOwnerAccessibility.@Nullable Host host) {
        this.popover = host;
        invalidateAccessible();
    }

    /** The attached popover opened or closed: said to a watcher as the expanded state moving. */
    void notifyExpanded() {
        notifyChange(Change.of(Change.Aspect.EXPANDED, Change.Origin.CODE));
    }

    /** The user pressed: the watchers hear {@code INVOKED} and then the handler runs. */
    private void invoke() {
        notifyChange(Change.of(Change.Aspect.INVOKED, Change.Origin.USER));
    }

    /**
     * Publishes and performs {@link Accessible.Action#PRESS} only while {@code condition} holds.
     * Read by both accessibility hooks, so the verb a reader is offered and the verb this button
     * performs cannot disagree; the pointer and the keyboard are not gated by it. Package-private,
     * for {@link Dialog}.
     *
     * @param condition read on the UI thread whenever the node is described or a press arrives
     */
    void acceptPressWhile(java.util.function.BooleanSupplier condition) {
        this.pressAccepted = Objects.requireNonNull(condition, "condition");
    }

    private boolean pressAccepted() {
        return pressAccepted == null || pressAccepted.getAsBoolean();
    }

    /**
     * Leaves Return to whatever holds this button: while focused it no longer consumes the key,
     * which then bubbles to its ancestors as a key nothing wanted. Space, a click and an
     * assistive technology's press are unchanged. For a dialog's destructive button, whose
     * dialog answers Return with its default button or not at all.
     */
    void refuseReturn() {
        this.pressedByReturn = false;
    }

    /**
     * The caption as it currently reads, an access letter's {@code &} mark taken out ("&amp;Save"
     * reads "Save"); see {@link #textSource()} for the value behind it.
     */
    public String text() {
        return shown().text();
    }

    /** The caption in the language in effect, read for its access letter. */
    private limn.i18n.internal.AccessMarks.Parsed shown() {
        return marked.of(text.get());
    }

    private char accessLetter() {
        return shown().letter();
    }

    private Widget<?> accessLetterFocus() {
        return this;
    }

    /** Alt and the letter press the button, as a click does, and leave the keyboard where it is. */
    private void accessLetterPressed() {
        LeafActionAccessibility.perform(leafHost, Accessible.Action.PRESS);
    }

    /** The localizable value this button holds, which a language change re-resolves. */
    public I18nString textSource() {
        return text;
    }

    /** Replaces the caption with a fixed string. UI thread only. */
    public Button setText(String newText) {
        Ui.checkUiThread();
        Objects.requireNonNull(newText, "newText");
        if (text.isLiteral() && text.get().equals(newText)) {
            return this;
        }
        return setText(I18nString.literal(newText));
    }

    /** Replaces the caption with a value that follows the UI language. UI thread only. */
    public Button setText(I18nString newText) {
        Ui.checkUiThread();
        Objects.requireNonNull(newText, "newText");
        if (text.equals(newText)) {
            return this; // unchanged: no layout pass (= full-window damage)
        }
        this.text = newText;
        markNeedsLayout();
        notifyChange(Change.of(Change.Aspect.NAME, Change.Origin.CODE));
        return this;
    }

    /** @return whether the button currently renders in its pressed state */
    boolean isArmed() {
        return armed || keyArmed;
    }

    /**
     * The caption as one shaped line: the value the measure pass, the baseline and the paint all
     * ask, so the three cannot answer differently. Re-shaped only when {@link ShapedText#matches}
     * says the held one is no longer the answer, which is the whole invalidation test &mdash; the
     * text, the {@link Font} (a value, so a control-size step or a theme change is caught by the
     * same comparison), the paragraph direction, and the ruler epoch that moves when a face is
     * evicted or the default family changes.
     *
     * <p>Shaped rather than measured, and that is the point of holding it: a caption whose base
     * direction the button decides cannot be asked of a measurement that has nowhere to put one.
     * The base is resolved here, once, and handed to both halves of the test above: asking
     * whether the held value is current and shaping a replacement have to be asking about the
     * same direction, or the check passes for a value the shape call would not have produced.
     */
    private ShapedText caption(TextRuler ruler, Font f) {
        String value = shown().text();
        ShapedText.Direction base = ShapedText.Direction.of(value, neutralBase());
        if (caption == null || !caption.matches(value, f, base, ruler)) {
            caption = ruler.shape(value, f, base);
        }
        return caption;
    }


    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.of(this).tokensFor(this);
        // A size, not an x: the caption claims the same width from either end and so does the
        // icon, so this box is the same in both directions and a container laying a button out
        // never sees the direction at all.
        TextMetrics metrics = caption(textRuler(), t.body()).metrics();
        // resolvedHeight is max(controlHeight, lineHeight + 2*padV): the box binds at every
        // step, which is what absorbed the undocumented +4 this used to carry.
        float height = t.resolvedHeight(metrics.lineHeight());
        // An icon alone asks for a square: its own height, which is the step's control height
        // unless a face taller than the floor grew it. The caption's padding on both sides of
        // an 18 pt icon made a 58 by 32 slab of it at MEDIUM. A bound set on the button wins.
        float width = iconAlone() ? height
                : leadingPad(t) + iconAdvance(t) + metrics.width() + t.padH();
        return constraints.constrain(width, height);
    }

    /** The baseline BASELINE rows align on, the very expression {@link #onPaint} draws with. */
    @Override
    protected float baselineOffset() {
        SizeTokens t = Theme.of(this).tokensFor(this);
        // The same expression the paint draws with, for exactly the reason this method exists: two
        // opinions on one band are two numbers a BASELINE row can be aligned to. Nothing read off
        // it here is horizontal, so this expression is the same in both directions.
        return textRuler().centredBaseline(t.body(), height());
    }

    /**
     * The focus ring is drawn OUTSIDE the box: centred {@link Strokes#FOCUS_GAP_BUTTON} out
     * with a {@link Strokes#FOCUS_RING} pen, so its outer ink reaches
     * {@link Strokes#FOCUS_RING_OUTSET}, and damage has to know that. {@code Scene} inflates a
     * widget's damage rect by {@code 1 + paintOutset()}, so without this override the fade-out
     * of a ring on the toolkit's most-used widget sheds stale pixels under partial rendering.
     *
     * <p>Declares reach only; it moves no ink. Checkbox, RadioButton and Label carry the same
     * kind of override for the same reason.
     */
    @Override
    protected float paintOutset() {
        return Strokes.FOCUS_RING_OUTSET;
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.of(this);
        SizeTokens t = theme.tokensFor(this);
        float h = hover.value();
        Color ink = isEnabled() ? ink(theme) : theme.disabledText();
        switch (style) {
            case PRIMARY -> {
                Color fill;
                if (!isEnabled()) {
                    fill = theme.disabledFill();
                } else if (destructive) {
                    // The palette has no hover or pressed danger, so both are danger moved toward
                    // the text colour: darker in a light palette and lighter in a dark one, as the
                    // accent's own hover and pressed tones move. The ink is onPrimary, whose
                    // contrast on danger every built-in palette is tested for.
                    fill = isArmed() ? theme.danger().lerp(theme.text(), STATE_PRESSED)
                            : theme.danger().lerp(theme.text(), STATE_HOVER * h);
                } else {
                    fill = isArmed() ? theme.primaryPressed()
                            : theme.primary().lerp(theme.primaryHover(), h);
                }
                canvas.fillRoundRect(0, 0, width(), height(), t.radiusMedium(), fill);
            }
            case SECONDARY -> {
                Color fill = !isEnabled() ? theme.disabledFill()
                        : isArmed() ? theme.outline()
                        : theme.surface().lerp(theme.surfaceRaised(), h);
                canvas.fillRoundRectWithBorder(0, 0, width(), height(), t.radiusMedium(), fill,
                        Strokes.BORDER, theme.outline());
            }
            case TERTIARY -> {
                // No fill at rest and none disabled: the state is a tint of the caption's own
                // colour, so it reads as the same button under the pointer rather than as a
                // second look. Nothing is drawn while the tint is zero, so a resting tool bar of
                // these costs no fill.
                if (isEnabled()) {
                    float alpha = isArmed() ? STATE_PRESSED : STATE_HOVER * h;
                    if (alpha > 0) {
                        Color tint = ink(theme);
                        canvas.fillRoundRect(0, 0, width(), height(), t.radiusMedium(),
                                tint.withAlpha(tint.a() * alpha));
                    }
                }
            }
        }
        float focus = focusFade.value();
        if (focus > 0.001f) {
            // Focus ring drawn OUTSIDE the button with a 1px gap, never over the fill (a 2px
            // stroke centered 2px out leaves 1px clear). Both weights are pixel-locked, and the
            // radius stays DERIVED from the fill's so the ring is concentric at every step.
            // Gated on the fade value (not isFocused) so the fade-out keeps rendering.
            float gapOut = Strokes.FOCUS_GAP_BUTTON;
            canvas.drawRoundRect(-gapOut, -gapOut, width() + 2 * gapOut, height() + 2 * gapOut,
                    t.radiusMedium() + gapOut, Strokes.FOCUS_RING, theme.focusRing().withAlpha(focus));
        }
        Font font = t.body();
        ShapedText line = caption(textRuler(), font);
        TextMetrics metrics = line.metrics();

        // Resolved ONCE for the whole pass. Which end of the block the icon takes and which end
        // the caption takes are two answers to one question, and two resolutions that disagreed
        // inside one paint would draw the caption over the icon.
        boolean rtl = isRightToLeft();

        float advance = iconAdvance(t);
        float block = advance + metrics.width();
        // A caption alone or an icon alone is CENTRED and stays exactly where it is under
        // mirroring: a centre is the same number in both directions, which is what makes a
        // text-only button — the overwhelming majority — render identically either way.
        float blockX = (width() - block) / 2;
        float textX;
        if (iconBesideCaption()) {
            // An icon beside a caption stands at the field inset from the edge reading starts
            // from, wherever the button's width puts its other end, so it lines up with the icon
            // of a field or a combo above or below it; the caption is centred in what is left
            // between the icon's gap and the button's own padding, which at the width the button
            // asks for is exactly that gap. Mirrored as a whole reading right to left.
            float lead = leadingPad(t) + advance;
            float room = Math.max(0, width() - lead - t.padH() - metrics.width());
            blockX = rtl ? width() - leadingPad(t) - block : leadingPad(t);
            textX = rtl ? width() - lead - metrics.width() - room / 2 : lead + room / 2;
        } else {
            // The caption takes the rest of the block; with no icon the advance is zero.
            textX = rtl ? blockX : blockX + advance;
        }
        if (icon != null) {
            float iconSize = t.iconBox();
            // Leading, written as a coordinate: the block's own left edge reading left to right,
            // and its far end less the square reading right to left.
            float iconX = rtl ? blockX + block - iconSize : blockX;
            // The flag says what the icon MEANS and the axis says which way this button reads;
            // neither alone is the answer, and the flip itself is Icon.paint's, about the square
            // it was just handed.
            icon.paint(canvas, iconX, (height() - iconSize) / 2, iconSize, ink, theme.isDark(),
                    rtl && iconMirroring == Icon.Mirroring.IN_RTL);
        }
        float baseline = textRuler().centredBaseline(font, height());
        canvas.drawText(line, textX, baseline, ink);
        if (AccessLetters.shownIn(this)) {
            MenuInk.underlineMnemonic(canvas, line, shown().index(), textX, baseline, ink);
        }
    }

    /**
     * What this button is to an assistive technology: one {@link Accessible.Role#BUTTON} named by
     * the caption it holds and offering {@link Accessible.Action#PRESS}, and nothing else.
     *
     * <p>The name is the held {@link I18nString} handed over by reference, never {@link #text()}:
     * the walk compares the reference, the locale and the translation epoch, so a hover or focus
     * fade that damages this button on every frame concludes that nothing moved without
     * allocating. It is handed over even when the caption is empty, because the walk names a
     * nameless node from its tooltip and describes a named one with it, which is how an icon-only
     * button is named by the tooltip it should have and a captioned one keeps the tooltip as its
     * description. The role is unconditional so that a disabled button stays in the tree and is
     * heard as disabled rather than vanishing.
     *
     * <p>Declared here is all a reader is told. The focus and scroll-into-view verbs, the enabled,
     * visible, showing, focusable and focused states and the box are the walk's; the pressed
     * visual is not published, because no platform maps it and a press from an assistive
     * technology never arms; whether this is a dialog's default button is the dialog's fact and
     * not this widget's, which holds no such notion; and the icon is paint with no name and no
     * operation, so it is not a node.
     *
     * @param a the node being described
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.BUTTON);
        // The caption as shown, its access letter's mark taken out, diffed by the witness that
        // moves when the shown text does; and the letter's chord, where it can be said.
        limn.i18n.internal.AccessMarks.Parsed caption = shown();
        a.name(caption.text(), marked.witness(), Accessible.NameFrom.CONTENT);
        a.accessKey(marked.binding());
        if (popover != null) {
            // A button that opens a popover is a POPUP_OWNER ahead of a leaf (ADR 045 §3), the
            // shape a tab strip's list button has: HAS_POPUP, the expand facet, and EXPAND or
            // COLLAPSE by state in place of PRESS.
            PopupOwnerAccessibility.describe(a, popover.isOpen());
            return;
        }
        // The LEAF_ACTION shape, written once (ADR 045 §3): PRESS exactly while accepted.
        LeafActionAccessibility.describe(a, pressAccepted());
    }

    /**
     * Performs a press an assistive technology asked for, exactly as a click or a Space release
     * does: the same private action slot, so the application is notified the same way. Any other
     * verb is refused, and so is a press while this button is disabled, since the click and key
     * paths rely on the scene never delivering to a disabled widget and this one keeps the guard
     * itself.
     *
     * <p>It does not arm, invalidate or request focus. A pointer click gets click-to-focus from
     * the scene and not from this widget, a reader that wants focus has the focus verb, and a
     * press that pulled focus out of a text field onto a tool-bar button would be worse than one
     * that leaves it.
     *
     * @param verb what was asked
     * @param arg  ignored; a press carries none
     * @return whether the action ran
     */
    @Override
    protected boolean onAccessibilityAction(Accessible.Action verb, Accessible.Argument arg) {
        if (popover != null) {
            return isEnabled() && PopupOwnerAccessibility.perform(popover, verb);
        }
        return LeafActionAccessibility.perform(leafHost, verb);
    }

    /** The button's mechanisms as the leaf shape drives them. */
    private final class LeafHost implements LeafActionAccessibility.Host {
        @Override
        public boolean acceptsPress() {
            return isEnabled() && pressAccepted();
        }

        @Override
        public void press() {
            invoke();
        }
    }

    private final LeafHost leafHost = new LeafHost();

    @Override
    protected void onMouseEvent(MouseEvent event) {
        switch (event.type()) {
            case ENTER -> hover.to(1);
            case EXIT -> {
                hover.to(0);
                armed = false;
                invalidate();
            }
            case PRESS -> {
                if (event.button() == Keys.MOUSE_LEFT) {
                    armed = true;
                    invalidate();
                    event.consume();
                }
            }
            case RELEASE -> {
                armed = false;
                invalidate();
                event.consume();
            }
            case CLICK -> {
                if (event.button() == Keys.MOUSE_LEFT) {
                    event.consume();
                    invoke();
                }
            }
            default -> {
            }
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (event.key() != Keys.ENTER && event.key() != Keys.SPACE) {
            return;
        }
        if (event.key() == Keys.ENTER && !pressedByReturn) {
            // Not consumed, so it reaches the dialog as a key nothing focused wanted.
            return;
        }
        if (event.isPressed() && !event.isRepeat()) {
            keyArmed = true;
            invalidate();
            event.consume();
        } else if (!event.isPressed()) {
            boolean fire = keyArmed;
            keyArmed = false;
            invalidate();
            event.consume();
            if (fire) {
                invoke();
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
        armed = false;
        keyArmed = false;
        invalidate();
    }
}
