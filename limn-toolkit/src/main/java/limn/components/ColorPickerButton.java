package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.RoundRect;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A colour, shown as a chip, that opens a {@link ColorPicker} in a dialog when clicked:
 * the control an application reaches for when it needs a colour from the user but cannot
 * give up a panel to a whole picker.
 *
 * <pre>{@code
 * ColorPickerButton accent = new ColorPickerButton(Color.rgb(0xF59E0B));
 * accent.onChange(colour -> { shape.setFill(colour); shape.invalidate(); });
 * }</pre>
 *
 * <p><b>Cancel is a change, not a silence.</b> The picker updates the chip and calls
 * {@link #onChange} on every move, so an application sees the colour live; dismissing the
 * dialog puts the previous colour back and reports <em>that</em> as a change too. The
 * button's own {@link #color()} is therefore always the answer, and a caller that simply
 * applies what it is handed is correct with no bookkeeping of its own, which is the whole
 * reason the listener fires on the way back. Keep the listener cheap: it runs on every
 * frame of a drag.
 *
 * <p>The caption defaults to the colour's hex and follows it. {@link #setText} replaces it
 * with anything else, including nothing: a button with an empty caption is chrome and a
 * chip, which is the form a dense inspector column wants.
 *
 * <p>Not a {@link Button} subclass: what it draws is a value, not a label, and the two
 * disagree about almost every line of paint. It behaves like one (hover, focus ring,
 * Enter and Space) because a control that opens a dialog should.
 *
 * <p><b>Reading right to left</b>, the chip and its caption swap sides together: the chip is
 * the leading item of the pair and the caption follows it inwards. Nothing else moves. The
 * box is the same size in both directions, the chip is the same square, and the colour inside
 * it has no reading axis of its own.
 */
public class ColorPickerButton extends Widget {

    private Color color;
    /** {@code null} means "the hex, kept in step with the colour". */
    private I18nString text;
    private boolean alphaEnabled = true;
    private I18nString dialogTitle = ComponentStrings.COLOR_TITLE;
    private Consumer<Color> onChange = colour -> {
    };

    private final Transition hover =
            new Transition(this).duration(Theme.current().animHover).easing(Theme.current().animEasing);
    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);
    private boolean armed;    // mouse press in progress
    private boolean keyArmed; // Space/Enter held
    /**
     * The dialog while it is up. A {@link Dialog} is single-use, so this is not a cache:
     * it is what stops a second click during the fade-in from raising a second picker over
     * the first, each with its own idea of what the colour was before.
     */
    private Dialog open;
    /** Passed to every dialog this button raises; see {@link #setPickerDisplayMode}. */
    private DisplayMode pickerDisplayMode = DisplayMode.NATIVE_WINDOW;

    /** A button on opaque white. */
    public ColorPickerButton() {
        this(Color.WHITE);
    }

    /** A button on {@code initial}. */
    public ColorPickerButton(Color initial) {
        this.color = Objects.requireNonNull(initial, "initial");
        setFocusable(true);
        setCursor(Cursor.POINTER);
    }

    // --- value ---------------------------------------------------------------

    /** The colour on the chip. Opaque unless {@link #setAlphaEnabled} says otherwise. */
    public Color color() {
        return color;
    }

    /**
     * Sets the colour without notifying, the setter an application calls when <em>it</em>
     * is the source of the change, so a listener that writes back does not loop. The
     * picker uses the notifying path instead.
     */
    public ColorPickerButton setColor(Color value) {
        Ui.checkUiThread();
        Objects.requireNonNull(value, "value");
        apply(alphaEnabled ? value : value.withAlpha(1f));
        return this;
    }

    /**
     * Whether the picker offers alpha at all (default {@code true}). Turning it off makes
     * {@link #color()} opaque immediately, and drops the alpha line from the dialog, for
     * the many things being coloured that cannot be translucent.
     */
    public ColorPickerButton setAlphaEnabled(boolean enabled) {
        Ui.checkUiThread();
        this.alphaEnabled = enabled;
        if (!enabled && color.a() < 1f) {
            apply(color.withAlpha(1f));
        }
        return this;
    }

    /** Whether the alpha line is offered; when off, {@link #color()} is always opaque. */
    public boolean isAlphaEnabled() {
        return alphaEnabled;
    }

    /**
     * Called whenever the colour changes: on every move of the picker, and once more with
     * the previous colour if the dialog is dismissed. Never called by {@link #setColor}.
     */
    public ColorPickerButton onChange(Consumer<Color> listener) {
        Ui.checkUiThread();
        this.onChange = listener == null ? colour -> {
        } : listener;
        return this;
    }

    // --- caption -------------------------------------------------------------

    /** The caption as it currently reads: the colour's hex unless one was set. */
    public String text() {
        return text != null ? text.get() : hex();
    }

    /**
     * The colour written the way the default caption writes it, rebuilt only when the colour has
     * moved.
     *
     * <p>{@link Color#toHex()} is a pair of format calls, and both callers are hot. The paint pass
     * asks three or four times a frame &mdash; to measure, to place the baseline, to decide the
     * chip's advance and to draw &mdash; and the accessible tree is walked over every widget
     * whenever anything at all is damaged, which for this button is every frame of a hover fade, a
     * focus fade and a picker drag. A hex built inside the describe hook would allocate one string
     * per damaged frame in order to conclude that nothing had moved, which is the failure the
     * cached form of {@link Accessibility#name(String, long, Accessible.NameFrom)} exists to
     * prevent.
     *
     * <p>Validated against the colour rather than cleared where the colour is written: a cache
     * that has to be cleared at every mutation is one new assignment away from speaking a colour
     * that is no longer on the chip. {@link Color} is a record, so the comparison is four float
     * compares and allocates nothing.
     *
     * <p><b>The colour is the whole key.</b> Not the locale and not the translation epoch, unlike
     * the picker's own spoken swatch: this string passes through no bundle and no
     * {@link java.text.MessageFormat}, and {@code %02X} is one of the conversions
     * {@link java.util.Formatter} leaves unlocalized, so no language move can change a digit of it.
     *
     * @return the hex the chip's caption reads
     */
    private String hex() {
        if (hexText == null || !color.equals(hexFrom)) {
            hexFrom = color;
            hexText = color.toHex();
            hexRevision++;
        }
        return hexText;
    }

    /** The colour {@link #hexText} was built from; {@code null} until the first call. */
    private Color hexFrom;

    /** The memo itself; {@code null} until the first call, which is what makes that call build. */
    private String hexText;

    /**
     * Bumped every time the memo above refills, and by nothing else, so that the tree can decide
     * the hex is unchanged without producing it. Compared as two {@code long}s.
     */
    private long hexRevision;

    /** Replaces the caption with a fixed string; {@code ""} leaves chrome and the chip. */
    public ColorPickerButton setText(String value) {
        return setText(I18nString.literal(Objects.requireNonNull(value, "value")));
    }

    /** Replaces the caption with a value that follows the UI language. */
    public ColorPickerButton setText(I18nString value) {
        Ui.checkUiThread();
        this.text = Objects.requireNonNull(value, "value");
        markNeedsLayout();
        return this;
    }

    /** Puts the caption back to the colour's hex, which is where it starts. */
    public ColorPickerButton setTextFromColor() {
        Ui.checkUiThread();
        this.text = null;
        markNeedsLayout();
        return this;
    }

    /** The dialog's title; {@code "Colour"} in the UI language unless set. */
    public ColorPickerButton setDialogTitle(I18nString value) {
        Ui.checkUiThread();
        this.dialogTitle = Objects.requireNonNull(value, "value");
        return this;
    }

    /** Chaining form of {@link #setControlSize}; {@code setControlSize} is {@code void}. */
    public ColorPickerButton withControlSize(ControlSize size) {
        setControlSize(size);
        return this;
    }

    // --- the dialog ----------------------------------------------------------

    /** Whether the picker dialog is up; a second click while it is does nothing. */
    public boolean isPickerOpen() {
        return open != null;
    }

    /** The dialog while it is up, else null; headless tests drive its buttons. */
    Dialog openDialog() {
        return open;
    }

    /**
     * Raises the picker, as a click would. Public so a menu item or a keyboard shortcut
     * elsewhere can open the same dialog this button opens.
     *
     * @throws IllegalStateException if this button is not in a scene
     */
    public void openPicker() {
        Ui.checkUiThread();
        if (open != null) {
            return;
        }
        Color before = color;

        ColorPicker picker = new ColorPicker();
        picker.setAlphaEnabled(alphaEnabled);
        picker.setInitialColor(before);
        picker.onChange(this::change);
        // The picker is the dialog's focus stop, and a picker names itself from nowhere: no
        // application can reach this one to caption it, so it takes the dialog's title, by
        // reference, and a reader landing in the dialog hears the same words the title bar
        // carries rather than a colour chooser called nothing.
        picker.setAccessibleName(dialogTitle);

        Dialog dialog = new Dialog(dialogTitle, I18nString.EMPTY);
        // A TokenBox, not a SizedBox: the width is a size token, and a number read here
        // would be the step this button happens to sit at when the dialog opens rather
        // than the one the card resolves to.
        dialog.setContent(new TokenBox(SizeTokens::colorDialogW, null, picker))
                .addButton(ComponentStrings.CANCEL, "cancel")
                .addPrimaryButton(ComponentStrings.OK, "ok")
                .setCancelResult("cancel")
                .setDisplayMode(pickerDisplayMode);
        open = dialog;
        dialog.show(this).thenAccept(result -> {
            open = null;
            if (!"ok".equals(result)) {
                change(before);
            }
        });
    }

    /**
     * Asks for the picker to be raised in a window of its own or as an overlay inside this
     * button's window; see {@link DisplayMode}. Default {@link DisplayMode#NATIVE_WINDOW}.
     *
     * <p>The reason to choose {@code IN_SCENE} here is usually not the platform. A colour picker
     * is the one dialog whose whole job is to change what is behind it, so an application that
     * shows the result live (a theme editor, a drawing tool) wants the picker and the thing it
     * is recolouring in one window and one screenshot. A native window is also invisible to
     * anything that captures the owner window, which is why the toolkit's own gallery raises this
     * one in scene.
     *
     * <p>Takes effect on the next {@link #openPicker()}; a picker already up is not moved.
     *
     * @see Dialog#setDisplayMode
     */
    public ColorPickerButton setPickerDisplayMode(DisplayMode mode) {
        this.pickerDisplayMode = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /** @return the presentation the next picker will be asked for. */
    public DisplayMode pickerDisplayMode() {
        return pickerDisplayMode;
    }

    /** Sets the colour and tells the listener, the picker's path, and Cancel's. */
    private void change(Color value) {
        if (apply(value)) {
            onChange.accept(color);
        }
    }

    /**
     * @return whether anything moved. The caption is the colour's hex by default, so a
     *         change of colour is a change of text, but only a change of its <em>length</em>
     *         can move the box, and this runs on every frame of a drag. Marking layout
     *         unconditionally here is how a colour drag turns into a relayout of the whole
     *         tree per frame.
     */
    private boolean apply(Color value) {
        if (color.equals(value)) {
            return false;
        }
        // hex() is still the outgoing colour's here, because the assignment below has not run
        // yet, so this reads exactly as the two format calls it replaces did -- and one of those
        // pairs per frame of a drag goes away.
        boolean resized = text == null && hex().length() != value.toHex().length();
        color = value;
        if (resized) {
            markNeedsLayout();
        } else {
            invalidate();
        }
        return true;
    }

    // --- layout and paint ----------------------------------------------------

    /**
     * Horizontal room the chip claims, its gap to the caption included. A magnitude and never
     * an x: it is the same number in both directions, and the side it is measured from is the
     * paint's decision alone.
     */
    private float chipAdvance(SizeTokens t) {
        return t.iconBox() + (text().isEmpty() ? 0 : t.gapIcon());
    }

    /**
     * What the shaper should fall back to for a caption with nothing strong in it, given the
     * direction this pass already resolved.
     *
     * <p>A fallback and not an imposition: the first-strong rule still decides everything a
     * strong character can decide, so the default caption &mdash; a hex, which is Latin and
     * digits &mdash; still reads left to right inside a right-to-left form, and only where it
     * is placed changes. What this settles is the caption that says nothing either way, whose
     * right answer is the direction of the interface around it.
     *
     * <p>Takes the resolved direction rather than reading it again, so that one paint cannot
     * place the caption for one direction and shape it for the other.
     */
    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        TextMetrics metrics = textRuler().measure(text(), t.body());
        return constraints.constrain(
                metrics.width() + chipAdvance(t) + 2 * t.padH(),
                t.resolvedHeight(metrics.lineHeight()));
    }

    /** The baseline BASELINE rows align on, the expression {@link #onPaint} draws with. */
    @Override
    protected float baselineOffset() {
        SizeTokens t = Theme.current().tokensFor(this);
        TextMetrics metrics = textRuler().measure(text(), t.body());
        return (height() - metrics.height()) / 2 + metrics.ascent();
    }

    /** The focus ring is drawn outside the box; damage has to know. See {@link Button}. */
    @Override
    protected float paintOutset() {
        return Strokes.FOCUS_RING_OUTSET;
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        float w = width();
        float h = height();
        float radius = t.radiusMedium();
        // The one resolution of the direction in this pass. The chip and the caption are a
        // single decision in two statements, and two reads that disagreed would put the chip on
        // one side of the button with its caption on the other.
        boolean rtl = isRightToLeft();

        // Secondary-button chrome: the control is a button, and the colour is its value.
        // Filling the whole face with the colour instead would make hover and pressed
        // unstateable: the two states would have to be shown IN the value being chosen.
        Color fill = !isEnabled() ? theme.disabledFill
                : isArmed() ? theme.outline
                : theme.surface.lerp(theme.surfaceRaised, hover.value());
        canvas.fillRoundRect(0, 0, w, h, radius, fill);
        float inset = Strokes.HALF_PIXEL_INSET; // lands the 1pt stroke on one device pixel
        canvas.drawRoundRect(inset, inset, w - 2 * inset, h - 2 * inset, radius,
                Strokes.BORDER, theme.outline);

        float chip = t.iconBox();
        // The chip is the leading item of the pair, so its left edge is one pad in from the
        // left reading one way and one chip further than that from the right reading the other.
        paintChip(canvas, t, theme, rtl ? w - t.padH() - chip : t.padH(),
                (h - chip) / 2, chip, chip);

        String caption = text();
        if (!caption.isEmpty()) {
            Font font = t.body();
            ShapedText line = textRuler().shape(caption, font,
                    ShapedText.Direction.of(caption, neutralBase()));
            TextMetrics metrics = line.metrics();
            // drawText places a line by its LEFT edge whichever way the line itself runs, so
            // the caption's leading x is expressed as one: the chip's advance in from the
            // trailing pad, less the line's own width.
            float captionX = rtl
                    ? w - t.padH() - chipAdvance(t) - metrics.width()
                    : t.padH() + chipAdvance(t);
            canvas.drawText(line, captionX, (h - metrics.height()) / 2 + metrics.ascent(),
                    isEnabled() ? theme.text : theme.disabledText);
        }

        float focus = focusFade.value();
        if (focus > 0.001f) {
            float gapOut = Strokes.FOCUS_GAP_BUTTON;
            canvas.drawRoundRect(-gapOut, -gapOut, w + 2 * gapOut, h + 2 * gapOut,
                    radius + gapOut, Strokes.FOCUS_RING, theme.focusRing.withAlpha(focus));
        }
    }

    /**
     * The value itself: the chosen colour over the same transparency checker the picker
     * draws, so a translucent answer reads as translucent in both places.
     *
     * <p>A disabled button dims the chip toward its own fill rather than swapping it for a
     * grey. The colour is the control's value, and a value that vanishes when the control
     * is switched off is a control that looks empty rather than unavailable.
     *
     * <p>Works entirely inside the box it is handed, and takes {@code x} rather than deciding
     * one: which side the chip sits on is the caller's decision, and a swatch has no reading
     * axis to make it here.
     */
    private void paintChip(Canvas canvas, SizeTokens t, Theme theme,
                           float x, float y, float w, float h) {
        float radius = t.radiusSmall();
        canvas.save();
        canvas.clipRoundRect(RoundRect.of(x, y, w, h, radius));
        if (color.a() < 1f) {
            ColorPicker.paintChecker(canvas, t, x, y, w, h);
        }
        canvas.fillRect(x, y, w, h,
                isEnabled() ? color : color.lerp(theme.disabledFill, 0.6f));
        canvas.restore();
        canvas.drawRoundRect(x + Strokes.HALF_PIXEL_INSET, y + Strokes.HALF_PIXEL_INSET,
                w - 1, h - 1, radius, Strokes.BORDER, theme.outline);
    }

    /** @return whether the button currently renders in its pressed state */
    boolean isArmed() {
        return armed || keyArmed;
    }

    // --- input ---------------------------------------------------------------

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
                    openPicker();
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
                openPicker();
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

    // --- accessibility -------------------------------------------------------

    /**
     * What this button is to an assistive technology: one {@link Accessible.Role#BUTTON} carrying
     * a popup, offering a press, and named by whichever of its two captions it is painting.
     *
     * <p><b>A button and not a colour chooser.</b> The chooser is the {@link ColorPicker} the
     * press raises, which has a node of its own in whichever tree it is mounted in. What this
     * widget does is open a dialog, which is what a button with a popup does.
     *
     * <p><b>The name is the caption, and the caption is two different things.</b> A caption set
     * through {@link #setText} is an {@link I18nString} this widget holds, so it is handed over by
     * reference and the walk compares the reference, the language and the translation epoch. The
     * default caption is the colour's hex, which has no source to compare because it is formatted,
     * so it is handed over from {@link #hex()} with the counter that memo was filled against. Both
     * are {@link Accessible.NameFrom#CONTENT}: on the default path that is exact, since the hex is
     * literally the painted caption, and with an empty caption it is the closest of the five, the
     * same divergence the picker's own swatch and the split pane's divider already record. One
     * provenance for both branches also keeps a {@code setText("")} and a
     * {@link #setTextFromColor()} from churning the attribute one platform writes the name into.
     *
     * <p><b>An empty caption is still named, by the colour.</b> {@code setText("")} is a supported
     * configuration &mdash; chrome and a chip, which is what a dense inspector column wants
     * &mdash; and the widget still paints the chip there, so the colour is its whole content. A
     * focusable node with no name at all is the one thing the gallery check refuses, and this is
     * the answer that costs no application a line.
     *
     * <p><b>The hex moves to the description when a caption displaced it, and is published exactly
     * once either way.</b> What that costs, said plainly: a captioned button with a tooltip loses
     * the tooltip, because the walk gives a tooltip to the description only when nothing else took
     * it. The trade is deliberate &mdash; a caption already identifies the control, and the colour
     * is published nowhere else in this widget's tree, so without it a reader of a captioned
     * colour well can never learn the colour. An application that disagrees calls
     * {@code setAccessibleDescription}, which is applied after this hook and wins.
     *
     * <p><b>{@link Accessible.State#HAS_POPUP} always, and not only while the picker is up.</b> It
     * is the bit that tells a reader this is not a plain button before anything has happened, and
     * a bit that moved with the dialog would republish this node twice per picker.
     *
     * <p><b>No facet of any kind, and the survey's row is wrong to want one.</b> "Value text is
     * the hex" cannot be implemented: {@link Accessibility#valueText} writes the text and leaves
     * the node without a number, the publish step builds a value facet only where a number is
     * present, and the difference raises a value event only when a number moves &mdash; so such a
     * text is dropped in silence on all three platforms. And there is no honest number to pair
     * with it, because a colour has no scalar. The hex belongs in the name, which is where it is.
     *
     * <p><b>No relation.</b> Presented in scene, the dialog's overlay is a parentless root whose
     * inheritance host is this button, so the walk publishes the popup relation there and its
     * mirror here; declaring it again would put two of the same relation on this node, since
     * relations are not de-duplicated. Presented in a window of its own &mdash; the default
     * &mdash; the picker is in another scene and another tree, and the {@link Dialog} the survey's
     * row names is not a widget at all, so a relation naming it could only ever be dropped.
     *
     * <p>Declared here is all a reader is told. The box, the language, the enabled, visible,
     * showing, focusable and focused bits and the focus and scroll-into-view verbs are the walk's;
     * {@link #paintOutset()} is how far the focus ring's ink reaches for damage and is never the
     * operable rectangle. The armed visual is not published, for {@link Button}'s reasons: no
     * platform maps it, it would republish the tree twice per click, and a press from an assistive
     * technology never arms. The chip is not a node either &mdash; the whole box is one hit
     * target, a node for it would say what the name already says, and leaving it out keeps this
     * hook free of {@link #layoutDirection()} entirely while the box mirrors for free.
     *
     * @param a the node being described
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.BUTTON);
        // get() is a per-language memo and allocates nothing when the language and the epoch have
        // not moved, so asking whether the caption is empty is free on every damaged frame.
        if (text != null && !text.get().isEmpty()) {
            a.name(text, Accessible.NameFrom.CONTENT);
            // hex() first, because the counter is only current once the memo has been asked.
            a.description(hex(), hexRevision);
        } else {
            a.name(hex(), hexRevision, Accessible.NameFrom.CONTENT);
        }
        a.state(Accessible.State.HAS_POPUP);
        // The single-argument form; the variable-argument one allocates an array per call.
        a.action(Accessible.Action.PRESS);
    }

    /**
     * Raises the picker an assistive technology asked for, through {@link #openPicker()} &mdash;
     * the same call a left click and an Enter or Space release make, so the dialog, its Cancel and
     * OK, and the revert that reports the previous colour through {@link #onChange} are all
     * exactly what the pointer would have got.
     *
     * <p>No enabled check of its own, unlike {@link Button}'s. The node acted on here is this
     * widget, so the scene's own gate has already walked this button and every ancestor for
     * {@code isEnabled()}, checked that it is showing, that the window is not modal-blocked and
     * that it is inside the layer that owns input. This widget's click and key paths rely on
     * exactly that gate and carry no guard either, and a reader's press that was stricter than the
     * pointer's would be a second rule to keep in step.
     *
     * <p>The refusal while a picker is already up is about truthfulness rather than safety:
     * {@link #openPicker()} returns silently in that case, and a {@code true} here is published as
     * an invocation, so acknowledging a press that did nothing would tell a reader something
     * happened. The scene's own reachability test refuses first in both presentations &mdash; in
     * scene the dialog's overlay owns input, natively the owner window is modal-blocked &mdash; so
     * this is the second line and not the first.
     *
     * @param verb what was asked
     * @param arg  ignored; a press carries none
     * @return whether this widget did it
     */
    @Override
    protected boolean onAccessibilityAction(Accessible.Action verb, Accessible.Argument arg) {
        if (verb != Accessible.Action.PRESS || open != null) {
            return false;
        }
        openPicker();
        return true;
    }
}
