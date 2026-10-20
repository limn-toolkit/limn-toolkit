package limn.components;

import limn.components.internal.FieldControls;
import limn.concurrent.Subscription;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.graphics.Path2D;
import limn.i18n.I18nString;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.internal.SceneAccess;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A field of a form around one control: a caption above it, a line under it, and the two states a
 * form has, required and invalid. The control is any widget: a {@link TextField}, a
 * {@link ComboBox}, a date picker, a check box, or a row of an amount and its currency.
 *
 * <pre>{@code
 * TextField email = new TextField();
 * Field field = new Field("Email", email)
 *         .setHelperText("We never share it")
 *         .setRequired(true);
 * email.onChange(text -> field.setValidation(text.contains("@")
 *         ? Validation.NONE : Validation.ERROR, "Enter an address like ada@example.com"));
 * }</pre>
 *
 * <p><b>The caption names the control.</b> It is drawn above the control in the step's label font,
 * muted, and a screen reader hears it as the control's name, on the part the keyboard lands on
 * (a date picker's field, an editable combo's text). An {@code &} in it marks the access letter
 * that brings the keyboard to the control, as a caption bound with {@link Label#setLabelFor} does.
 * A field built with no caption keeps everything else: a check box that must be ticked keeps its
 * own words and gains a line and the states. A caption beside its control, rather than above, is a
 * {@code Grid(2)} of a bound {@link Label} and a field with no caption.
 *
 * <p><b>The line under the control says one thing.</b> While the state is not
 * {@link Validation#NONE} and a message is set, it is the message, in the state's {@link Tone} and
 * with the state's glyph before it, so the state never depends on colour alone; otherwise the
 * helper text, muted; otherwise nothing. A date field or a date picker that refuses what it holds,
 * a day out of range say, shows its own message in the error tone while the application has set
 * none. A screen reader hears the line as the control's description, whichever it shows.
 *
 * <p><b>The line keeps its height.</b> A field given helper text, or a state once, reserves its line
 * from then on, so a form does not move when the first error appears or the last one is fixed. A
 * field given neither reserves nothing.
 *
 * <p><b>The state is the application's, and a required field validates nothing.</b>
 * {@link #setRequired} draws an asterisk after the caption, in the danger colour, and tells a
 * reader the field is required in the system's own words; whether an empty required field is an
 * error, and when to say so, is the application's, set with {@link #setValidation}. The state
 * colours the control's border where the control draws one (a text field, a text area, a combo box,
 * a spinner, a date field and a date picker; a field around a date field or a picker that refuses a
 * day stays red whatever state is set), and only {@link Validation#ERROR} reaches a reader as a
 * state, as invalid.
 *
 * <p>A field takes no focus and handles no key: Tab goes to the control. Reading right to left, the
 * caption and the line start at the right edge, the asterisk follows the caption's end and the
 * glyph leads the message.
 */
public final class Field extends Widget<Field> {

    private final Widget<?> control;
    private final Widget<?> placed;
    private final Label caption;
    private final Label mark;
    private final Label line;
    private @Nullable I18nString label;
    private @Nullable I18nString helper;
    private @Nullable I18nString message;
    private Validation validation = Validation.NONE;
    private boolean required;
    /** Whether the line keeps its height: helper text was given, or a state was set once. */
    private boolean reserves;
    /** Hears a control that validates itself say its validity moved, while it is in a scene. */
    private @Nullable Subscription validityWatch;

    /**
     * A field whose caption is {@code label}, around {@code control}.
     *
     * @param label   the caption, which follows the language
     * @param control what is named and laid out
     */
    public Field(I18nString label, Widget<?> control) {
        this(Objects.requireNonNull(label, "label"), control, control);
    }

    /**
     * A field whose caption is a fixed string, around {@code control}.
     *
     * @param label   the caption
     * @param control what is named and laid out
     */
    public Field(String label, Widget<?> control) {
        this(I18nString.literal(Objects.requireNonNull(label, "label")), control, control);
    }

    /**
     * A field around a control that sits inside a wrapper: a field beside a switch, a sized box. The
     * caption names {@code control} and the states are published on it, while {@code placed} is what
     * is laid out under the caption.
     *
     * @param label   the caption, or {@code null} for none
     * @param control what is named, described and given the states
     * @param placed  what is laid out: {@code control} itself, or a widget that holds it
     * @throws IllegalArgumentException when {@code placed} does not hold {@code control}
     */
    public Field(@Nullable I18nString label, Widget<?> control, Widget<?> placed) {
        this.control = Objects.requireNonNull(control, "control");
        this.placed = Objects.requireNonNull(placed, "placed");
        if (!holds(placed, control)) {
            throw new IllegalArgumentException("a field lays out the control or a widget that holds"
                    + " it, and " + placed.getClass().getName() + " does not hold the "
                    + control.getClass().getName() + " it would name");
        }
        caption = new Label(I18nString.EMPTY).setRole(Label.Role.LABEL).setMuted(true);
        // Drawn and never read: the state says "required" on every system, in its own words, and
        // the control's name stays the caption's words alone.
        mark = new Label("*").setRole(Label.Role.LABEL).setTone(Tone.DANGER).setAccessibleIgnored(true);
        line = new Label(I18nString.EMPTY).setRole(Label.Role.LABEL).setWrap(true);
        add(caption);
        add(mark);
        add(placed);
        add(line);
        applyLabel(label);
        refreshLine();
        publishStates();
    }

    private static boolean holds(Widget<?> placed, Widget<?> control) {
        for (Widget<?> at = control; at != null; at = at.parent()) {
            if (at == placed) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------ the parts

    /** The control the caption names and the states are published on. */
    public Widget<?> control() {
        return control;
    }

    /**
     * The caption as it currently reads, or {@code null} for none: its text in the language in
     * effect, an access letter's {@code &} mark taken out ("&amp;Name" reads "Name"); see
     * {@link #labelSource()} for the value behind it.
     */
    public @Nullable String label() {
        return label == null ? null : caption.text();
    }

    /** The localizable value behind {@link #label()}, or {@code null} for none. */
    public @Nullable I18nString labelSource() {
        return label;
    }

    /**
     * Replaces the caption; {@code null} takes it away, and the control keeps whatever names it
     * otherwise (a check box its own words). UI thread only.
     *
     * @param newLabel the caption, or {@code null}
     * @return this field
     */
    public Field setLabel(@Nullable I18nString newLabel) {
        Ui.checkUiThread();
        if (Objects.equals(label, newLabel)) {
            return this;
        }
        applyLabel(newLabel);
        markNeedsLayout();
        return this;
    }

    /** {@link #setLabel(I18nString)} with a fixed string. */
    public Field setLabel(String newLabel) {
        return setLabel(I18nString.literal(Objects.requireNonNull(newLabel, "newLabel")));
    }

    private void applyLabel(@Nullable I18nString newLabel) {
        label = newLabel;
        if (newLabel == null) {
            caption.setLabelFor(null);
            caption.setVisible(false);
        } else {
            caption.setText(newLabel);
            caption.setLabelFor(control);
            caption.setVisible(true);
        }
        mark.setVisible(required && newLabel != null);
    }

    /**
     * The helper text in the language in effect, or {@code null} for none; see
     * {@link #helperTextSource()} for the value behind it.
     */
    public @Nullable String helperText() {
        return helper == null ? null : helper.get();
    }

    /** The localizable value behind {@link #helperText()}, or {@code null} for none. */
    public @Nullable I18nString helperTextSource() {
        return helper;
    }

    /**
     * Sets the line under the control while no message stands in its place: how to fill the field,
     * what it expects ("Eleven digits, as on the card"). Shown with the focus elsewhere too, which a
     * text field's placeholder and a mask's guide are not, and read to a screen reader as the
     * control's description. From the first helper text on, the line keeps its height. UI thread
     * only.
     *
     * @param text the helper text, or {@code null} for none
     * @return this field
     */
    public Field setHelperText(@Nullable I18nString text) {
        Ui.checkUiThread();
        if (Objects.equals(helper, text)) {
            return this;
        }
        helper = text;
        if (text != null) {
            reserve();
        }
        refreshLine();
        return this;
    }

    /** {@link #setHelperText(I18nString)} with a fixed string. */
    public Field setHelperText(String text) {
        return setHelperText(I18nString.literal(Objects.requireNonNull(text, "text")));
    }

    /** Whether the field is required; see {@link #setRequired}. */
    public boolean isRequired() {
        return required;
    }

    /**
     * Marks the field as one that must be filled in: an asterisk after the caption, in the danger
     * colour, and the required state on the control, which each system's reader says in its own
     * words. Nothing is validated: an empty required field is an error when the application says so.
     * UI thread only.
     *
     * @param newRequired whether the field is required
     * @return this field
     */
    public Field setRequired(boolean newRequired) {
        Ui.checkUiThread();
        if (required == newRequired) {
            return this;
        }
        required = newRequired;
        mark.setVisible(newRequired && label != null);
        markNeedsLayout();
        publishStates();
        return this;
    }

    /** The state the application set; see {@link #setValidation(Validation, I18nString)}. */
    public Validation validation() {
        return validation;
    }

    /**
     * The message the application set with the state, in the language in effect, or {@code null}
     * for none; see {@link #validationMessageSource()} for the value behind it.
     */
    public @Nullable String validationMessage() {
        return message == null ? null : message.get();
    }

    /** The localizable value behind {@link #validationMessage()}, or {@code null} for none. */
    public @Nullable I18nString validationMessageSource() {
        return message;
    }

    /**
     * Sets the state alone, with no message: the border and the reader's state follow it, and the
     * line shows the helper text; see {@link #setValidation(Validation, I18nString)}.
     *
     * @param state the state
     * @return this field
     */
    public Field setValidation(Validation state) {
        return setValidation(state, (I18nString) null);
    }

    /**
     * Sets what the application decided about the value the control holds, and what to say about
     * it. The state colours the control's border where it draws one; the message, while the state is
     * not {@link Validation#NONE}, takes the line under the control in the state's tone, with the
     * state's glyph; {@link Validation#ERROR} is the invalid state to a screen reader, which hears
     * the message as the control's description. From the first state on, the line keeps its height.
     * Called from the application's handler or watcher, when it chooses: on change, on leaving the
     * field, on submitting. UI thread only.
     *
     * @param state      the state
     * @param newMessage what to say, or {@code null} for nothing: the helper text stays on the line
     * @return this field
     */
    public Field setValidation(Validation state, @Nullable I18nString newMessage) {
        Ui.checkUiThread();
        Objects.requireNonNull(state, "state");
        if (state == validation && Objects.equals(message, newMessage)) {
            return this;
        }
        boolean moved = state != validation;
        validation = state;
        message = newMessage;
        reserve();
        if (moved) {
            FieldControls.showBorder(control, state);
            publishStates();
        }
        refreshLine();
        if (moved) {
            notifyChange(Change.of(Change.Aspect.VALIDITY, Change.Origin.CODE));
        }
        return this;
    }

    /** {@link #setValidation(Validation, I18nString)} with a fixed string. */
    public Field setValidation(Validation state, String newMessage) {
        return setValidation(state, I18nString.literal(Objects.requireNonNull(newMessage, "newMessage")));
    }

    /**
     * Takes the state and the message away: the border is the control's own again, and the line
     * shows the helper text, or stays empty at its height. UI thread only.
     *
     * @return this field
     */
    public Field clearValidation() {
        return setValidation(Validation.NONE);
    }

    // ---------------------------------------------------------------------------- the line

    private void reserve() {
        if (!reserves) {
            reserves = true;
            markNeedsLayout();
        }
    }

    /** What the line shows, decided in one place: the message, the control's own, the helper, nothing. */
    private void refreshLine() {
        I18nString text;
        Validation shown;
        I18nString own = message == null ? FieldControls.ownMessage(control) : null;
        if (validation != Validation.NONE && message != null) {
            text = message;
            shown = validation;
        } else if (own != null) {
            text = own;
            shown = Validation.ERROR;
            reserve(); // the control spoke: the line it took keeps its height, as a message's does
        } else if (helper != null) {
            text = helper;
            shown = Validation.NONE;
        } else {
            text = I18nString.EMPTY;
            shown = Validation.NONE;
        }
        line.setText(text);
        line.setTone(shown == Validation.NONE ? null : toneOf(shown));
        line.setMuted(shown == Validation.NONE);
        line.setIcon(shown == Validation.NONE ? null : Glyph.of(shown));
        boolean says = !text.get().isEmpty();
        // The line is the control's description while it says something, and nothing to a reader
        // while it is empty: an empty static node and an empty description are both noise.
        line.setDescriptionFor(says ? control : null);
        line.setAccessibleIgnored(!says);
        line.setVisible(reserves);
    }

    /** The tone a state's line is drawn in. */
    static Tone toneOf(Validation state) {
        return switch (state) {
            case NONE -> Tone.NEUTRAL;
            case ERROR -> Tone.DANGER;
            case WARNING -> Tone.WARNING;
            case SUCCESS -> Tone.SUCCESS;
            case INFO -> Tone.INFO;
        };
    }

    private void publishStates() {
        SceneAccess.fieldControl(control, true, required, validation == Validation.ERROR);
    }

    // ------------------------------------------------------------------------- the control

    @Override
    protected void onAttached() {
        super.onAttached();
        // A control that validates itself says when its validity moves, and the line follows it;
        // heard only while this field is in a scene, so a field kept aside holds no subscription.
        validityWatch = control.observeChanges((widget, change) -> {
            if (change.aspect() == Change.Aspect.VALIDITY) {
                refreshLine();
            }
        });
        refreshLine();
    }

    @Override
    protected void onDetached() {
        if (validityWatch != null) {
            validityWatch.cancel();
            validityWatch = null;
        }
        super.onDetached();
    }

    // ---------------------------------------------------------------------------- layout

    /**
     * How far the control may paint past this field's box: the reach of the theme's focus ring,
     * since the control is laid out to the field's own edges.
     */
    @Override
    protected float paintOutset() {
        return Theme.of(this).focusRingReach();
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.of(this).tokensFor(this);
        Constraints loose = Constraints.loose(constraints.maxWidth(), Constraints.UNBOUNDED_LIMIT);
        float headWidth = 0;
        if (caption.isVisible()) {
            headWidth = caption.measure(loose).width() + markSpace(t, loose);
        }
        Size controlSize = placed.measure(loose);
        float width = constraints.constrainWidth(Math.max(headWidth, controlSize.width()));
        // The control again at the width it will be given, which a control whose height follows
        // its width (a wrapping text, a wrap of chips) answers differently.
        float controlHeight = placed.measure(Constraints.loose(width, Constraints.UNBOUNDED_LIMIT)).height();
        return constraints.constrain(width, headHeight(t, width) + controlHeight + lineHeight(t, width));
    }

    /** The room the asterisk takes after the caption, its gap included, or 0 while it is hidden. */
    private float markSpace(SizeTokens t, Constraints loose) {
        return mark.isVisible() ? markGap(t) + mark.measure(loose).width() : 0;
    }

    /** Half the label gap: the asterisk belongs to the caption it follows. */
    private static float markGap(SizeTokens t) {
        return t.gapLabel() / 2;
    }

    /** The caption's line and the gap under it, or 0 with no caption. */
    private float headHeight(SizeTokens t, float width) {
        if (!caption.isVisible()) {
            return 0;
        }
        return caption.measure(Constraints.loose(width, Constraints.UNBOUNDED_LIMIT)).height() + t.gapLabel();
    }

    /** The gap over the line and the line, at least one line of the label font, or 0 when not reserved. */
    private float lineHeight(SizeTokens t, float width) {
        if (!line.isVisible()) {
            return 0;
        }
        return t.gapLabel() + line.measure(Constraints.loose(width, Constraints.UNBOUNDED_LIMIT)).height();
    }

    @Override
    protected void onLayout() {
        SizeTokens t = Theme.of(this).tokensFor(this);
        float width = width();
        Constraints loose = Constraints.loose(width, Constraints.UNBOUNDED_LIMIT);
        boolean rtl = isRightToLeft();
        float y = 0;
        if (caption.isVisible()) {
            float markWidth = mark.isVisible() ? mark.measure(loose).width() : 0;
            float room = Math.max(0, width - (mark.isVisible() ? markGap(t) + markWidth : 0));
            Size head = caption.measure(Constraints.loose(room, Constraints.UNBOUNDED_LIMIT));
            float captionWidth = Math.min(head.width(), room);
            // From the leading edge: the caption, then the asterisk after its end.
            caption.layoutBox(rtl ? width - captionWidth : 0, 0, captionWidth, head.height());
            if (mark.isVisible()) {
                float markX = rtl ? width - captionWidth - markGap(t) - markWidth
                        : captionWidth + markGap(t);
                mark.layoutBox(markX, 0, markWidth, head.height());
            }
            y = head.height() + t.gapLabel();
        }
        float controlHeight = placed.measure(Constraints.loose(width, Constraints.UNBOUNDED_LIMIT)).height();
        placed.layoutBox(0, y, width, controlHeight);
        y += controlHeight;
        if (line.isVisible()) {
            y += t.gapLabel();
            line.layoutBox(0, y, width, line.measure(loose).height());
        }
    }

    // ---------------------------------------------------------------------------- the glyphs

    /**
     * The mark before a message, one per state, drawn as strokes in the line's own colour, since the
     * toolkit carries no icon set: a circle with a bar for an error, a triangle for a warning, a
     * circle with a check for success, a circle with an "i" for a note. None turns around in a
     * right-to-left line; the line places it at the leading edge.
     */
    static final class Glyph implements limn.graphics.Icon {

        private static final Glyph ERROR = new Glyph(Validation.ERROR);
        private static final Glyph WARNING = new Glyph(Validation.WARNING);
        private static final Glyph SUCCESS = new Glyph(Validation.SUCCESS);
        private static final Glyph INFO = new Glyph(Validation.INFO);
        /** Never drawn: the paint below draws strokes, and asks for no bitmap. */
        private static final Image NONE = new Image(1, 1, new byte[4]);

        final Validation state;
        /** Reset and refilled on each paint, on the UI thread, so a frame allocates no path. */
        private final Path2D path = new Path2D();

        private Glyph(Validation state) {
            this.state = state;
        }

        static Glyph of(Validation state) {
            return switch (state) {
                case ERROR -> ERROR;
                case WARNING -> WARNING;
                case SUCCESS -> SUCCESS;
                case INFO, NONE -> INFO;
            };
        }

        @Override
        public Image image(int pixelSize, boolean dark) {
            return NONE;
        }

        @Override
        public void paint(Canvas canvas, float x, float y, float size, @Nullable Color tint, boolean dark) {
            Color ink = tint != null ? tint : Color.BLACK;
            // The drawing lives in the middle of the square at the size of the text's cap height,
            // so it reads at the text's weight and does not swell with the icon square's bump.
            float s = size * 0.72f;
            float left = x + (size - s) / 2;
            float top = y + (size - s) / 2;
            float cx = left + s / 2;
            float pen = Strokes.ARROW_PEN;
            float r = s / 2 - pen / 2;
            switch (state) {
                case WARNING -> {
                    path.reset();
                    Path2D triangle = path
                            .moveTo(cx, top + pen / 2)
                            .lineTo(left + s - pen / 2, top + s - pen / 2)
                            .lineTo(left + pen / 2, top + s - pen / 2)
                            .close();
                    canvas.drawPath(triangle, pen, ink);
                    canvas.drawLine(cx, top + s * 0.38f, cx, top + s * 0.64f, pen, ink);
                    canvas.fillCircle(cx, top + s * 0.79f, pen * 0.6f, ink);
                }
                case SUCCESS -> {
                    canvas.drawCircle(cx, top + s / 2, r, pen, ink);
                    path.reset();
                    Path2D check = path
                            .moveTo(left + s * 0.29f, top + s * 0.52f)
                            .lineTo(left + s * 0.44f, top + s * 0.67f)
                            .lineTo(left + s * 0.72f, top + s * 0.37f);
                    canvas.drawPath(check, pen, ink);
                }
                case INFO, NONE -> {
                    canvas.drawCircle(cx, top + s / 2, r, pen, ink);
                    canvas.fillCircle(cx, top + s * 0.31f, pen * 0.6f, ink);
                    canvas.drawLine(cx, top + s * 0.45f, cx, top + s * 0.73f, pen, ink);
                }
                case ERROR -> {
                    canvas.drawCircle(cx, top + s / 2, r, pen, ink);
                    canvas.drawLine(cx, top + s * 0.27f, cx, top + s * 0.55f, pen, ink);
                    canvas.fillCircle(cx, top + s * 0.71f, pen * 0.6f, ink);
                }
            }
        }
    }
}
