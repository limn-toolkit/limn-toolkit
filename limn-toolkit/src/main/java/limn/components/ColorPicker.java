package limn.components;

import limn.animation.Transition;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.LinearGradient;
import limn.graphics.RoundRect;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Picks a colour the way a graphics application does: a saturation/value field
 * for the current hue, a hue ramp beside it, a before/after swatch, a hex field,
 * and numeric channels in whichever model the user thinks in: RGB, HSV or CMYK.
 * Every channel is a line of its own: the letter, a rail showing what that
 * channel does, and the number. Alpha, when it is offered, is one more such line.
 *
 * <p><b>Hue and saturation live on the widget</b> rather than being re-derived from the
 * colour. Grey has no hue and black has no saturation, so a colour alone cannot say
 * where the cursor was: dragging the value down to black and back up would otherwise
 * return red. What was last chosen survives the trip.
 *
 * <p><b>Alpha is a mode.</b> {@link #setAlphaEnabled} removes the whole alpha line, rail
 * and number together, and {@link #color()} then always returns an opaque colour. Alpha
 * is the last line, so losing it shortens the picker rather than rearranging it.
 *
 * <p><b>Two opposite answers about direction live in this one widget, and unifying them
 * would be wrong.</b> The rails are value axes laid along the reading axis, so their sweep,
 * their thumb, their pointer and their horizontal arrows all turn round together when the
 * picker reads right to left. The saturation/value field and the hue ramp do not turn round:
 * the first is a colour space, whose white corner is a convention every artist has met and
 * not a side of the page, and the second is vertical, which no direction touches. The ramp
 * still changes sides, because the row holding it is what places it.
 *
 * <p>The answer is a display-range {@link Color}. An application authoring light rather
 * than pixels needs a multiplier beside this widget, not inside it: the swatch cannot
 * show what such a multiplier does, since normalising an over-range colour for display
 * divides it straight back out.
 *
 * <p>Not a dialog; put it in one with {@link Dialog#setContent}, or inline it in a
 * panel. {@code final}, unlike most components here: it is a composite of four painted
 * parts wired to one model, and a subclass would be overriding paint and layout it does
 * not own. Compose one instead.
 */
public final class ColorPicker extends Widget {

    /**
     * Which numeric model the channel row is showing.
     *
     * <p>There is no HSL, on purpose. It is a different model from HSV, not another
     * spelling of it (its saturation disagrees with HSV's everywhere except the
     * extremes), so offering both means the numbers change when you switch tabs
     * while the colour has not, which reads as the picker losing your colour. One
     * cylindrical model, and it is the one this picker's own field is built on and
     * the one every graphics and game tool speaks: HSV, spelled HSB by Photoshop
     * and the macOS picker (Brightness and Value are the same axis).
     */
    public enum Format {
        /** Red, green, blue in 0–255. */
        RGB,
        /** Hue in degrees, saturation and value in percent, the axes of the field itself. */
        HSV,
        /** Cyan, magenta, yellow, key in percent. Naive, not colour-managed. */
        CMYK
    }

    // Every extent here (field, ramp, rails, thumb, gaps, checker square) comes from
    // the resolved SizeTokens row; strokes and the hit floor stay locked. None of it is
    // read at construction, where a widget has no parent: the tree is built once and its
    // spacing re-applied from applyStep each pass.

    /** The six hue sectors a two-stop gradient has to be built from. */
    private static final Color[] HUE_STOPS = {
            Color.rgb(0xFF0000), Color.rgb(0xFFFF00), Color.rgb(0x00FF00),
            Color.rgb(0x00FFFF), Color.rgb(0x0000FF), Color.rgb(0xFF00FF),
            Color.rgb(0xFF0000),
    };
    /**
     * Alternating squares behind a translucent colour, so alpha is visible as alpha.
     *
     * <p><b>Deliberately outside the theme, unlike every other colour in this widget set.</b> The
     * checkerboard is not decoration, it is the convention that means "this is transparent", the
     * same two greys in every image editor a user has met. A palette that tinted it would be
     * saying something about the colour on top of it, which is the one thing this control must
     * not do, and a brand-coloured checkerboard reads as content rather than as absence.</p>
     */
    private static final Color CHECKER_LIGHT = Color.rgb(0x9AA0A8);
    private static final Color CHECKER_DARK = Color.rgb(0x6B7178);

    private final Column root = new Column();
    private final Row rampsRow = new Row();
    private final Row identityRow = new Row();
    /** The tab contents' top padding, re-applied per pass like every other gap. */
    private final List<Padding> tabPads = new ArrayList<>();
    private final SaturationValueField field = new SaturationValueField();
    private final HueRamp hueRamp = new HueRamp();
    private final AlphaRail alphaRail = new AlphaRail();
    private final Preview preview = new Preview();
    private final TextField hexField = new TextField();
    /**
     * The three notations, as tabs. A {@link TabbedPane} rather than a
     * {@link SegmentedControl} even though the picker is the one keeping the rows
     * in step: what changes under the switch is a whole block of controls, the
     * pane is what makes it reachable from the keyboard (Enter dives into the
     * channels), and it degrades into chevrons and a popup where a segmented track
     * would only squeeze its labels; a picker in a narrow inspector column is a
     * real place for it to end up.
     */
    private final TabbedPane formatTabs = new TabbedPane();
    private final List<ChannelGroup> groups = new ArrayList<>();
    private final Spinner alphaField = new Spinner(0, 100, 1);
    private final Row alphaRow = new Row();

    private float hue;
    private float saturation;
    private float value = 1f;
    private float alpha = 1f;
    private boolean alphaEnabled = true;
    private Format format = Format.RGB;

    /** The colour the picker opened on, drawn beside the current one. */
    private Color original = Color.WHITE;

    private Consumer<Color> onChange = color -> { };
    private Consumer<Color> onCommit = color -> { };
    /** Guards the field round-trip: writing a field must not re-parse it as an edit. */
    private boolean syncing;
    /** The same guard one level up: moving the tab must not be read back as a switch. */
    private boolean switchingFormat;
    /**
     * True while the hex field's own listener is running, so its text is not
     * rewritten from under the caret. "112233FF" parses to an opaque colour whose
     * canonical form is the shorter "112233"; rewriting mid-word would delete the
     * two characters just typed and leave the caret somewhere else, which is
     * exactly what it felt like.
     */
    private boolean editingHex;

    /** A picker starting on opaque white, with the alpha line offered. */
    public ColorPicker() {
        setFocusable(true);
        root.crossAlignment(Flex.CrossAlignment.STRETCH);

        // The field and the hue ramp, and nothing else: alpha is a channel like
        // the others and lives with them below, not as a second column here.
        rampsRow.crossAlignment(Flex.CrossAlignment.STRETCH);
        rampsRow.add(Expanded.of(field, 1));
        rampsRow.add(new TokenBox(SizeTokens::colorRampW, null, hueRamp));
        root.add(new TokenBox(null, SizeTokens::colorFieldH, rampsRow));

        // "#" as a label rather than part of the text: the field then holds only
        // digits, so its length is the colour's, nothing has to defend against a
        // user deleting the hash, and the caret never lands before it.
        identityRow.crossAlignment(Flex.CrossAlignment.CENTER);
        // One control tall and two wide: the swatch sits beside the hex field and
        // reads as the same row of furniture, at every step.
        identityRow.add(new TokenBox(t -> 2 * t.controlHeight(), SizeTokens::controlHeight,
                preview));
        identityRow.add(new Label("#").setMuted(true));
        hexField.onChange(this::hexTyped);
        identityRow.add(Expanded.of(hexField, 1));
        root.add(identityRow);

        for (Format each : Format.values()) {
            ChannelGroup group = new ChannelGroup(each);
            groups.add(group);
            // The pane owns the rows from here: which one is laid out and painted
            // is its answer, and the picker's job shrinks to keeping the numbers
            // in the showing one true (see setFormat).
            //
            // Padded, because a tabbed pane hands its content the space under the
            // strip and nothing else: unpadded, the first channel's rail sits on
            // the strip's own rule and reads as part of it. The gap is the one the
            // rest of the picker is spaced by, so the block keeps its rhythm.
            Padding padded = new Padding(Insets.NONE, group.lines);
            tabPads.add(padded);
            formatTabs.addTab(ColorPickerStrings.format(each), padded);
        }
        formatTabs.onSelect(index -> {
            // Ignored while setFormat is the one moving the tab; see there.
            if (!switchingFormat) {
                setFormat(Format.values()[index]);
            }
        });
        root.add(formatTabs);

        alphaField.setSnapToStep(false);
        alphaField.onChange(percent -> {
            if (!syncing) {
                alpha = (float) (percent / 100.0);
                changed();
            }
        });
        // The same line as a channel, because that is what alpha is here: letter,
        // rail, stepper, on the columns the block above already established. It
        // sits under the model rows rather than inside them because it belongs to
        // no model: the three tabs re-notate the colour, and none of them
        // re-notates its alpha.
        alphaRow.crossAlignment(Flex.CrossAlignment.CENTER);
        alphaRow.add(new TokenBox(SizeTokens::fieldIcon, null,
                new Label(ColorPickerStrings.CHANNEL_ALPHA).setMuted(true)));
        alphaRow.add(Expanded.of(alphaRail, 1));
        alphaRow.add(alphaField);
        root.add(alphaRow);

        add(root);
        setFormat(Format.RGB);
        syncFields();
    }

    // --- value ---------------------------------------------------------------

    /** The chosen colour. Opaque unless {@link #setAlphaEnabled} says otherwise. */
    public Color color() {
        return Color.hsv(hue, saturation, value, alphaEnabled ? alpha : 1f);
    }

    /**
     * Sets the colour <em>and</em> the "before" swatch: this is what opening the
     * picker on an existing colour means. Use {@link #setColor} to move the
     * selection without moving the comparison.
     */
    public ColorPicker setInitialColor(Color color) {
        setColor(color);
        original = color();
        preview.invalidate();
        return this;
    }

    /**
     * Moves the selection. Hue and saturation are taken from {@code color} only
     * when it has them: a grey has no hue to read, and overwriting the current one
     * with zero would swing the field to red for no reason the artist can see.
     */
    public ColorPicker setColor(Color color) {
        Ui.checkUiThread();
        adopt(color);
        alpha = color.a();
        syncFields();
        invalidateAll();
        return this;
    }

    /**
     * Moves hue, saturation and value onto {@code color} under the rules {@link
     * #setColor} documents, and touches nothing else. Split out because the channel
     * rows need those rules without the rest: a row of numbers in a colour model has
     * no alpha in it, so reading one back must not overwrite the alpha the user set
     * beside it, which it did whenever {@link #setAlphaEnabled} was off, because the
     * colour the row builds is opaque by construction there.
     */
    private void adopt(Color color) {
        if (color.saturation() > 0) {
            hue = color.hue();
        }
        if (color.value() > 0) {
            saturation = color.saturation();
        }
        value = color.value();
    }

    /**
     * Whether the picker offers alpha at all (default {@code true}). Turning it off
     * hides the ramp and the field and makes {@link #color()} opaque, for the many
     * things being coloured that cannot be translucent.
     */
    public ColorPicker setAlphaEnabled(boolean enabled) {
        Ui.checkUiThread();
        this.alphaEnabled = enabled;
        alphaRow.setVisible(enabled);
        markNeedsLayout();
        syncFields();
        invalidateAll();
        return this;
    }

    /** Whether the alpha line is offered; when off, {@link #color()} is always opaque. */
    public boolean isAlphaEnabled() {
        return alphaEnabled;
    }

    /**
     * Which numeric model the channel rows show. The visual field is always HSV.
     *
     * <p>The one entry point, whichever end it came from: a tab the user clicked
     * arrives here through {@link TabbedPane#onSelect}, and this pushes the
     * selection back the other way for a caller that set it. That round trip is
     * why the guard exists: unlike a segmented control, a tabbed pane reports a
     * <em>programmatic</em> selection too, so without it one switch would run
     * {@link #syncFields} twice: once from the notification and once here.
     */
    public ColorPicker setFormat(Format format) {
        Ui.checkUiThread();
        this.format = format;
        switchingFormat = true;
        try {
            formatTabs.setSelectedIndex(format.ordinal());
        } finally {
            switchingFormat = false;
        }
        markNeedsLayout();
        syncFields();
        return this;
    }

    /** The notation the channel rows are currently showing. */
    public Format format() {
        return format;
    }

    /**
     * Fires on every move: a picker shows its answer live, it does not wait for OK. That makes a
     * delivery a preview: see {@link #onCommit} for the one that is a decision.
     *
     * @throws NullPointerException if {@code listener} is null, as everywhere else in this set
     */
    public ColorPicker onChange(Consumer<Color> listener) {
        Ui.checkUiThread();
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Fires once with the settled colour when a drag ends, the difference between a preview and a
     * decision, and the same split {@code Slider.onChange}/{@code Slider.onCommit} makes. This is
     * where an undo entry is closed, or a value written to a document.
     *
     * @throws NullPointerException if {@code listener} is null
     */
    public ColorPicker onCommit(Consumer<Color> listener) {
        Ui.checkUiThread();
        this.onCommit = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // --- layout --------------------------------------------------------------

    @Override
    protected Size onMeasure(Constraints constraints) {
        applyStep(Theme.current().tokensFor(this));
        Size size = root.measure(constraints.loosened());
        return constraints.constrain(size.width(), size.height());
    }

    /**
     * Pushes the resolved row into everything that was built once: the gaps down
     * the column, the gap along a channel line, and the tab contents' top pad.
     * Every setter guards on equality, so re-applying an unchanged step from
     * inside a measure pass does not schedule another one.
     */
    private void applyStep(SizeTokens t) {
        float gap = t.colorGap();
        root.gap(gap);
        rampsRow.gap(gap);
        identityRow.gap(gap);
        alphaRow.gap(gap);
        for (Padding pad : tabPads) {
            pad.setInsets(new Insets(gap, 0, 0, 0));
        }
        for (ChannelGroup group : groups) {
            group.lines.gap(t.spacingSmall());
            for (Row line : group.lineRows) {
                line.gap(gap);
            }
        }
    }

    @Override
    protected void onLayout() {
        applyStep(Theme.current().tokensFor(this));
        root.measure(Constraints.tight(width(), height()));
        root.layoutBox(0, 0, width(), height());
    }

    // --- editing -------------------------------------------------------------

    private void changed() {
        syncFields();
        invalidateAll();
        onChange.accept(color());
    }

    private void invalidateAll() {
        field.invalidate();
        hueRamp.invalidate();
        alphaRail.invalidate();
        preview.invalidate();
    }

    private void hexTyped(String text) {
        if (syncing) {
            return;
        }
        editingHex = true;
        try {
            applyHex(text);
        } finally {
            editingHex = false;
        }
    }

    /**
     * Applies a hex string if it parses. Package-private because it is what the
     * field's listener calls and what the test drives; typing is otherwise only
     * reachable through a focused text field, which turns an assertion about
     * parsing into an assertion about focus.
     *
     * @return whether the text was a colour
     */
    boolean applyHex(String text) {
        Color parsed = Color.fromHex(text);
        if (parsed == null) {
            return false; // half-typed; leave the selection where it is
        }
        setColor(alphaEnabled ? parsed : parsed.withAlpha(1f));
        onChange.accept(color());
        return true;
    }

    /** The hue the field is showing, independent of the current saturation or value. */
    float hue() {
        return hue;
    }

    /**
     * The channel spinner at {@code index} in the row currently showing.
     * Package-private for the same reason {@link #applyHex} is: reaching a stepper
     * through the scene means deriving its arrow column's coordinates inside a
     * composite, which turns an assertion about a colour model into an assertion
     * about layout.
     */
    Spinner channel(int index) {
        return groups.get(format.ordinal()).fields.get(index);
    }

    /**
     * The rail beside that spinner. Package-private so a test can press on the
     * real geometry: for a control whose whole contract is "the pointer lands on
     * the value it points at", the geometry <em>is</em> the thing under test.
     */
    Widget rail(int index) {
        return groups.get(format.ordinal()).tracks.get(index);
    }

    /** The alpha rail, for the same reason {@link #rail} is package-private. */
    Widget alphaRail() {
        return alphaRail;
    }

    /**
     * The saturation/value field, for the same reason again. It is the widget a test has
     * to reach to prove the plane did <em>not</em> turn round, and deriving its box from
     * the column's arithmetic would make that assertion one about the column.
     */
    Widget saturationValueField() {
        return field;
    }

    /** The hue ramp, for {@link #saturationValueField}'s reason. */
    Widget hueRamp() {
        return hueRamp;
    }

    /** The before/after swatch, for {@link #saturationValueField}'s reason. */
    Widget preview() {
        return preview;
    }

    /**
     * The format tabs. Package-private so a test can switch from the pane's side,
     * the half {@link #setFormat} does not exercise, and the half a user's click
     * actually takes.
     */
    TabbedPane tabs() {
        return formatTabs;
    }

    /** Pushes the model into every field. Silent: a programmatic set fires nothing. */
    private void syncFields() {
        syncing = true;
        try {
            Color current = color();
            if (!editingHex) {
                // Digits only: the "#" is a label beside the field, not text in it.
                hexField.setText(current.toHex().substring(1));
            }
            alphaField.setValue(Math.round(alpha * 100));
            for (ChannelGroup group : groups) {
                if (group.format == format) {
                    group.read(current);
                }
            }
        } finally {
            syncing = false;
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed()) {
            return;
        }
        // Arrows nudge the field. A step of 1/255 matches what the RGB channels can
        // express, so a nudge is never a change the numbers cannot show.
        //
        // None of the four is mirrored, unlike a rail's: the field is a colour space and
        // not a reading axis, so white stays in the same corner of it in both directions
        // and Left still walks saturation down. Mirroring the key alone would send it the
        // opposite way from the gradient and the cursor it walks along.
        float step = (event.modifiers() & Keys.MOD_SHIFT) != 0 ? 10 / 255f : 1 / 255f;
        switch (event.key()) {
            case Keys.LEFT -> saturation = clamp01(saturation - step);
            case Keys.RIGHT -> saturation = clamp01(saturation + step);
            case Keys.UP -> value = clamp01(value + step);
            case Keys.DOWN -> value = clamp01(value - step);
            default -> {
                return;
            }
        }
        changed();
        event.consume();
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : Math.min(1f, v);
    }

    // --- the numeric channels ------------------------------------------------

    /**
     * One model's worth of channels, each a line of letter, gradient rail and spinner.
     * The rail shows that channel swept end to end with the others held.
     *
     * <p><b>Each rail is its own focus stop</b>: it is a slider, and the stepper beside
     * it does not make it exempt from being reachable. Tab runs rail, number, rail,
     * number down the block; arrows move the focused rail by one unit of what its number
     * shows, ten with Shift or a page key, Home/End to its ends. Pressing a rail focuses
     * it, so arrows afterwards move the channel just dragged rather than the
     * saturation/value field.
     */
    private final class ChannelGroup {

        private final Format format;
        /** One line per channel, stacked; see the class comment for why not side by side. */
        private final Column lines = new Column();
        private final List<Spinner> fields = new ArrayList<>();
        private final List<ChannelTrack> tracks = new ArrayList<>();
        private final List<Row> lineRows = new ArrayList<>();
        /**
         * The colour the numbers on screen were dialled in for, or {@code null} when
         * they were derived from one. Only the CMYK group keeps it, because CMYK is
         * four axes onto a three-axis colour: a colour cannot say which quadruple of
         * inks made it, the same way a grey cannot say which hue made it. Cleared the
         * moment the colour stops matching, so the row repairs itself from anywhere.
         */
        private Color separationFor;

        ChannelGroup(Format format) {
            this.format = format;
            I18nString[] labels = ColorPickerStrings.channels(format);
            lines.crossAlignment(Flex.CrossAlignment.STRETCH);
            for (int i = 0; i < labels.length; i++) {
                Spinner spinner = new Spinner(0, maxOf(format, i), 1);
                // The model owns the value, so the spinner must not snap it onto
                // its own grid: the same reason every other bound field opts out.
                spinner.setSnapToStep(false);
                spinner.onChange(v -> {
                    if (!syncing) {
                        write();
                    }
                });
                fields.add(spinner);
                ChannelTrack track = new ChannelTrack(i);
                tracks.add(track);

                // The rail takes everything the letter and the stepper leave, and
                // the stepper keeps its natural width: the same three digits in
                // every model, so the rails end on one column down the block.
                Row line = new Row();
                line.crossAlignment(Flex.CrossAlignment.CENTER);
                lineRows.add(line);
                line.add(new TokenBox(SizeTokens::fieldIcon, null,
                        new Label(labels[i]).setMuted(true)));
                line.add(Expanded.of(track, 1));
                line.add(spinner);
                lines.add(line);
            }
        }

        /** Repaints the tracks; their gradients are built from the row and the colour. */
        private void invalidateTracks() {
            for (ChannelTrack track : tracks) {
                track.invalidate();
            }
        }

        /**
         * The channel swept end to end with the others held. Reads the row rather
         * than the colour, so it shows what the spinners actually mean, which for
         * CMYK is not recoverable from the colour (see {@link #separationFor}).
         */
        private final class ChannelTrack extends Rail {

            private final int channel;

            ChannelTrack(int channel) {
                this.channel = channel;
            }

            @Override
            protected float fraction() {
                Spinner spinner = fields.get(channel);
                double span = spinner.max() - spinner.min();
                return span <= 0 ? 0
                        : clamp01((float) ((spinner.value() - spinner.min()) / span));
            }

            /** One of the spinner's own units: 1 of 255 reds, 1 of 360 degrees. */
            @Override
            protected float unitFraction() {
                Spinner spinner = fields.get(channel);
                double span = spinner.max() - spinner.min();
                return span <= 0 ? 0 : (float) (1 / span);
            }

            /**
             * Rounded to what the spinner beside it can show: a drag must never
             * leave the rail and the number disagreeing. Silent when it rounds to
             * the value already there, so a drag along one step is one change and
             * not sixty.
             */
            @Override
            protected void moveTo(float t) {
                Spinner spinner = fields.get(channel);
                double target = Math.round(spinner.min() + t * (spinner.max() - spinner.min()));
                if (target != spinner.value()) {
                    spinner.setValue(target);
                    write();
                }
            }

            @Override
            protected void paintRail(Canvas canvas, SizeTokens t, float w, float top,
                                     boolean rtl) {
                float rail = t.colorRailH();
                if (format == Format.HSV && channel == 0) {
                    // Hue is the one channel a two-stop gradient cannot express: it
                    // is a circle through six primaries, so it goes in bands like
                    // the ramp does. The bands overlap by a point to close the
                    // seam, which is safe only because these stops are opaque:
                    // over a translucent one the overlap would compose twice and
                    // draw the seam it was meant to hide.
                    float band = w / (HUE_STOPS.length - 1);
                    for (int i = 0; i < HUE_STOPS.length - 1; i++) {
                        float low = i / (float) (HUE_STOPS.length - 1);
                        float high = (i + 1) / (float) (HUE_STOPS.length - 1);
                        // The band walk is untouched and only its coordinate is
                        // reflected: band i covers the same slice of the channel in
                        // both directions, and reading right to left that slice is
                        // measured back from the far edge, low end outermost, so it
                        // agrees with the thumb resting on the same fraction.
                        float x = rtl ? w - (i + 1) * band : i * band;
                        // The overlapping point goes toward the band drawn next,
                        // which is the side the sweep continues on: it is meant to
                        // be covered by that band, and on the outside of the last
                        // one it would hang past the rail instead of closing a seam.
                        canvas.fillRect(rtl ? x - 1 : x, top, band + 1, rail,
                                new LinearGradient(x, 0, x + band, 0,
                                        sweep(rtl ? high : low),
                                        sweep(rtl ? low : high)));
                    }
                } else {
                    // Value zero belongs on the leading edge, under the thumb that
                    // rests there when the channel is at its minimum.
                    canvas.fillRect(0, top, w, rail,
                            new LinearGradient(0, 0, w, 0,
                                    sweep(rtl ? 1 : 0), sweep(rtl ? 0 : 1)));
                }
            }

            /**
             * This channel at {@code t} of its range, the others as the row has
             * them. Always opaque: a channel rail answers "what does this channel do
             * to the colour", and the alpha rail below answers the other question.
             */
            private Color sweep(float t) {
                return switch (format) {
                    case RGB -> new Color(
                            channel == 0 ? t : (float) (fields.get(0).value() / 255.0),
                            channel == 1 ? t : (float) (fields.get(1).value() / 255.0),
                            channel == 2 ? t : (float) (fields.get(2).value() / 255.0), 1f);
                    case HSV -> Color.hsv(
                            channel == 0 ? t * 360 : (float) fields.get(0).value(),
                            channel == 1 ? t : (float) (fields.get(1).value() / 100.0),
                            channel == 2 ? t : (float) (fields.get(2).value() / 100.0), 1f);
                    case CMYK -> Color.cmyk(
                            channel == 0 ? t : (float) (fields.get(0).value() / 100.0),
                            channel == 1 ? t : (float) (fields.get(1).value() / 100.0),
                            channel == 2 ? t : (float) (fields.get(2).value() / 100.0),
                            channel == 3 ? t : (float) (fields.get(3).value() / 100.0), 1f);
                };
            }
        }

        /** Shows {@code color} in this model. */
        void read(Color color) {
            switch (format) {
                case RGB -> {
                    fields.get(0).setValue(Math.round(color.r() * 255));
                    fields.get(1).setValue(Math.round(color.g() * 255));
                    fields.get(2).setValue(Math.round(color.b() * 255));
                }
                case HSV -> {
                    // From the widget's own hue and saturation, not the colour's:
                    // black reads back as hue 0 and would move the field.
                    fields.get(0).setValue(Math.round(hue));
                    fields.get(1).setValue(Math.round(saturation * 100));
                    fields.get(2).setValue(Math.round(value * 100));
                }
                case CMYK -> {
                    // The row the user dialled in stands until the colour moves out
                    // from under it. Re-deriving it unconditionally is what used to
                    // swallow their edit: toCmyk does full grey-component replacement
                    // (k = 1 - max(r,g,b)), so every derived row has min(C,M,Y) = 0,
                    // and one more point of ink on a channel already sitting at zero
                    // has nowhere to be stored: it came back in whichever channel
                    // renormalisation could carry it, which is exactly what it looked
                    // like: the press landing on a different spinner.
                    //
                    // Exact equality, not a tolerance: both sides are this widget's
                    // own color(), built from the same three floats, so a row that is
                    // still true compares equal bit for bit, while a colour that has
                    // genuinely moved (and whose row must therefore be rebuilt)
                    // never does. Alpha is left out because it does not touch r/g/b:
                    // changing it is not a reason to renormalise the inks.
                    if (separationFor != null
                            && separationFor.r() == color.r()
                            && separationFor.g() == color.g()
                            && separationFor.b() == color.b()) {
                        return;
                    }
                    separationFor = null;
                    float[] cmyk = color.toCmyk(new float[4]);
                    for (int i = 0; i < 4; i++) {
                        fields.get(i).setValue(Math.round(cmyk[i] * 100));
                    }
                }
                default -> {
                }
            }
            // Only where the row actually moved: CMYK's early return above means the
            // colour did not move either, so the tracks it would repaint are the ones
            // already on screen.
            invalidateTracks();
        }

        /**
         * Reads the spinners back into the selection.
         *
         * <p>None of the three branches goes through {@link #setColor}: the numbers
         * the user typed are the answer, and a round trip through the colour is
         * exactly what loses them. What each model has to keep for itself differs
         * (HSV its hue and saturation, CMYK its whole separation), but the reason is
         * the same one everywhere in this widget: a model with axes the colour cannot
         * carry has to hold them, or the user's last edit is the one that vanishes.
         */
        private void write() {
            switch (format) {
                // Nothing to keep: RGB is the colour, so re-deriving the row from it
                // is exact and the spinners can safely be rebuilt from the answer.
                case RGB -> adopt(new Color((float) (fields.get(0).value() / 255.0),
                        (float) (fields.get(1).value() / 255.0),
                        (float) (fields.get(2).value() / 255.0), 1f));
                // The three spinners ARE the model in the model that has one. Going
                // through the built colour would drop the hue at zero saturation, and
                // the field would jump to red while the number said otherwise, and
                // would drop the saturation at zero value, so typing V down to 0 and
                // back up handed you white instead of the colour you started from.
                case HSV -> {
                    hue = (float) fields.get(0).value();
                    saturation = (float) (fields.get(1).value() / 100.0);
                    value = (float) (fields.get(2).value() / 100.0);
                }
                // Adopt the colour the inks make, then remember which colour these
                // four numbers stand for, before syncFields re-reads the row inside
                // this same frame, since that read is what would otherwise renormalise
                // it back (see read).
                //
                // It must be color() and NOT the colour handed to adopt, however much
                // the two look like the same value: adopt stores hue/saturation/value
                // and color() rebuilds through Color.hsv, so the two differ by an
                // RGB->HSV->RGB round trip that is not bit-exact for most of the rows
                // these fields can dial in. read compares bit for bit on purpose, so
                // storing the pre-adopt colour makes that comparison miss and
                // renormalise a row that was still true, the swallowed edit the guard
                // exists to prevent. Six tests in ColorPickerTest fail on that edit.
                case CMYK -> {
                    adopt(Color.cmyk((float) (fields.get(0).value() / 100.0),
                            (float) (fields.get(1).value() / 100.0),
                            (float) (fields.get(2).value() / 100.0),
                            (float) (fields.get(3).value() / 100.0), 1f));
                    separationFor = color();
                }
            }
            syncFields();
            invalidateAll();
            onChange.accept(color());
        }
    }

    /**
     * A horizontal rail with a thumb: the body shared by the channel rails and
     * the alpha rail under them. A subclass says where the value is, what to do
     * when the pointer moves it, and what the sweep looks like.
     *
     * <p>Its box is taller than the rail it paints, up to the toolkit's minimum
     * hit target: a 10 pt rail is a thing to look at, and a 10 pt drag target is a
     * thing to miss. Height is what a pointer has least of here: every rail has
     * another one a few points above and below it.
     */
    private abstract class Rail extends Widget {

        private boolean dragging;
        private final Transition focusFade =
                new Transition(this).duration(Theme.current().animFocus)
                        .easing(Theme.current().animEasing);

        Rail() {
            setFocusable(true);
            setCursor(limn.backend.Cursor.POINTER);
        }

        /** Where the value sits in its range, as {@code [0,1]}. */
        protected abstract float fraction();

        /** Puts the value at {@code t} of its range; {@code t} is already clamped. */
        protected abstract void moveTo(float t);

        /**
         * One unit of what the number beside this rail shows, as a fraction of the
         * range: a point of ink, a degree of hue, one of 255 reds. The keyboard
         * step is this and not a fixed fraction of the rail, so an arrow moves the
         * value by exactly what the reader can see change.
         */
        protected abstract float unitFraction();

        /**
         * Paints the sweep across the rail band: full width, {@code railHeight} tall.
         *
         * <p>{@code rtl} is the direction the pass already resolved, handed down rather
         * than read again here: a sweep painted for one direction under a thumb placed
         * for the other leaves the low end of the channel at one end of the rail and the
         * thumb resting on it at the other, which is the one way this control can lie.
         */
        protected abstract void paintRail(Canvas canvas, SizeTokens t, float w, float top,
                                          boolean rtl);

        /**
         * Tall enough for the thumb's focus ring, and never under the hit floor.
         * Both terms are locked: the ring and its gap are absolute, and
         * so is the 24 pt target, so a dense step keeps a reachable rail.
         */
        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = Theme.current().tokensFor(this);
            float needed = t.colorThumbH() + 2 * Strokes.FOCUS_GAP_SLIDER + Strokes.FOCUS_RING;
            return constraints.constrain(
                    constraints.hasBoundedWidth() ? constraints.maxWidth() : t.fieldIcon(),
                    Math.max(Strokes.MIN_HIT_TARGET, needed));
        }

        /**
         * How far the thumb's centre is held off each end of the box, the way a
         * slider's knob is: a value resting at zero is the ordinary case (it is most
         * of a CMYK row), and a thumb centred on the edge would hang half outside the
         * rail and read as a chipped corner. A magnitude applied identically at both
         * ends, so it carries no direction of its own; {@link #thumbCentreX} is where
         * one end becomes a coordinate.
         */
        private float travelInset(SizeTokens t) {
            return t.colorThumbW() / 2;
        }

        private float travelWidth(SizeTokens t) {
            return Math.max(1, width() - t.colorThumbW());
        }

        /**
         * Physical x of the thumb's centre for the current {@link #fraction}.
         *
         * <p>The fraction is a distance travelled from the end the range starts at,
         * which is the leading edge, so this is the single expression that turns it
         * into a coordinate: reading right to left the same distance is measured back
         * from the box's right edge, and the travel arithmetic above is untouched.
         * {@link #pick} is this expression inverted, so a press lands on the value it
         * points at in both directions.
         */
        private float thumbCentreX(SizeTokens t, boolean rtl) {
            float along = travelInset(t) + fraction() * travelWidth(t);
            return rtl ? width() - along : along;
        }

        @Override
        protected void onPaint(Canvas canvas) {
            SizeTokens t = Theme.current().tokensFor(this);
            float w = width();
            float h = height();
            if (w < 1 || h < 1) {
                return;
            }
            // Resolved once for the whole pass and handed to both halves of the rail: the
            // sweep and the thumb are one picture, and two resolutions that disagreed
            // inside one paint would draw value zero at one end and rest the thumb on the
            // other. The clip and the outline below span the box and know no direction.
            boolean rtl = layoutDirection() == LayoutDirection.RTL;
            float rail = t.colorRailH();
            float top = (h - rail) / 2;
            float radius = Math.min(rail / 2, t.radiusSmall());
            canvas.save();
            canvas.clipRoundRect(RoundRect.of(0, top, w, rail, radius));
            paintRail(canvas, t, w, top, rtl);
            canvas.restore();
            // The rail's own outline IS the focus ring, thickening into the accent
            // as the fade runs: one stroke, so nothing is drawn outside the box and
            // the animation survives (a ternary here would delete it).
            float focus = focusFade.value();
            canvas.drawRoundRect(0.5f, top + 0.5f, w - 1, rail - 1, radius,
                    Strokes.BORDER + (Strokes.FOCUS_RING - Strokes.BORDER) * focus,
                    Theme.current().outline.lerp(Theme.current().focusRing, focus));
            paintThumb(canvas, t, thumbCentreX(t, rtl), h / 2, focus);
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (!event.isPressed() || !isEnabled()) {
                return;
            }
            // Both axes, like Slider: a rail is horizontal here, and a keyboard user
            // reaching for Up on a slider should not have to know that.
            //
            // Only the horizontal half turns round with the direction. Left and Right
            // name the end of the range they physically point at, so reading right to
            // left Left is the way the value grows; Up and Down name the value itself
            // and mean the same thing on any page. That is why the two arms are split
            // apart rather than swapped: swapping them wholesale would invert the
            // vertical half too, which nothing asked for.
            boolean rtl = layoutDirection() == LayoutDirection.RTL;
            float unit = unitFraction();
            float step = (event.modifiers() & Keys.MOD_SHIFT) != 0 ? 10 * unit : unit;
            switch (event.key()) {
                case Keys.LEFT -> moveTo(clamp01(fraction() + (rtl ? step : -step)));
                case Keys.RIGHT -> moveTo(clamp01(fraction() + (rtl ? -step : step)));
                case Keys.DOWN -> moveTo(clamp01(fraction() - step));
                case Keys.UP -> moveTo(clamp01(fraction() + step));
                // Page, Home and End name the value and never a side of the screen, so
                // they are the same key in both directions.
                case Keys.PAGE_DOWN -> moveTo(clamp01(fraction() - 10 * unit));
                case Keys.PAGE_UP -> moveTo(clamp01(fraction() + 10 * unit));
                case Keys.HOME -> moveTo(0);
                case Keys.END -> moveTo(1);
                default -> {
                    return;
                }
            }
            event.consume();
        }

        @Override
        protected void onFocusGained() {
            focusFade.to(1);
        }

        @Override
        protected void onFocusLost() {
            focusFade.to(0);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            switch (event.type()) {
                case PRESS -> {
                    if (event.button() == Keys.MOUSE_LEFT && isEnabled()) {
                        dragging = true;
                        // Press jumps, like every other track in this widget: the
                        // rail is the value's whole range on screen, so pointing at
                        // a value is a way of asking for it.
                        pick(event);
                    }
                }
                case DRAG -> {
                    if (dragging) {
                        pick(event);
                    }
                }
                case RELEASE -> {
                    if (dragging) {
                        dragging = false;
                        event.consume();
                        onCommit.accept(color());
                    }
                }
                default -> {
                }
            }
        }

        private void pick(MouseEvent event) {
            SizeTokens t = Theme.current().tokensFor(this);
            // The inverse of thumbCentreX, resolved once for this event: the pointer
            // arrives as a physical x, and the direction is what turns it back into a
            // distance travelled from the end the range starts at.
            boolean rtl = layoutDirection() == LayoutDirection.RTL;
            float local = sceneToLocalX(event.x());
            float along = rtl ? width() - local : local;
            moveTo(clamp01((along - travelInset(t)) / travelWidth(t)));
            event.consume();
        }
    }

    /**
     * Alpha, on the same line shape as a channel. Over a checkerboard, because
     * "transparent" drawn on a panel is indistinguishable from "the colour of that
     * panel", the one thing an alpha control has to make unmistakable.
     */
    private final class AlphaRail extends Rail {

        @Override
        protected float fraction() {
            return clamp01(alpha);
        }

        /** One percent, which is what the number beside it counts in. */
        @Override
        protected float unitFraction() {
            return 0.01f;
        }

        /** Whole percent, so the rail and the stepper beside it never disagree. */
        @Override
        protected void moveTo(float t) {
            float snapped = Math.round(t * 100) / 100f;
            if (snapped != alpha) {
                alpha = snapped;
                changed();
            }
        }

        @Override
        protected void paintRail(Canvas canvas, SizeTokens t, float w, float top,
                                 boolean rtl) {
            // The checkerboard has no ends to swap: it is a texture meaning "absence",
            // and its phase is not a position anything reads.
            paintChecker(canvas, t, 0, top, w, t.colorRailH());
            Color solid = Color.hsv(hue, saturation, value, 1);
            // Alpha zero is the low end of the same axis the thumb rides, so the
            // transparent end of the sweep travels to the leading edge with it.
            canvas.fillRect(0, top, w, t.colorRailH(),
                    new LinearGradient(0, 0, w, 0,
                            rtl ? solid : solid.withAlpha(0),
                            rtl ? solid.withAlpha(0) : solid));
        }
    }

    private static double maxOf(Format format, int channel) {
        return switch (format) {
            case RGB -> 255;
            case HSV -> channel == 0 ? 360 : 100;
            case CMYK -> 100;
        };
    }

    // --- the painted parts ---------------------------------------------------

    /**
     * Draws the alternating squares that make a translucent colour legible. Shared with
     * {@link ColorPickerButton}, whose chip shows the same colour and has to say "this is
     * see-through" the same way; two patterns for one meaning would read as two meanings.
     */
    static void paintChecker(Canvas canvas, SizeTokens t,
                                     float x, float y, float w, float h) {
        // The square is spacingSmall: a transparency pattern is furniture, and it
        // has to thin out with the controls or a dense picker reads as tartan.
        float square = t.spacingSmall();
        canvas.fillRect(x, y, w, h, CHECKER_LIGHT);
        for (int row = 0; row * square < h; row++) {
            for (int col = row % 2; col * square < w; col += 2) {
                float cx = x + col * square;
                float cy = y + row * square;
                canvas.fillRect(cx, cy, Math.min(square, x + w - cx),
                        Math.min(square, y + h - cy), CHECKER_DARK);
            }
        }
    }

    /**
     * A ring rather than a dot: it has to stay visible on whatever it sits on, and
     * a two-tone ring is legible against both ends of the field.
     */
    private static void paintCursor(Canvas canvas, float cx, float cy, float radius) {
        canvas.drawCircle(cx, cy, radius + 1, Strokes.PICKER_CURSOR, Color.rgba(0x000000, 0.55f));
        canvas.drawCircle(cx, cy, radius, Strokes.PICKER_CURSOR, Color.WHITE);
    }

    /**
     * The thumb on a channel rail: a white capsule standing proud of the rail at
     * both ends, with a dark rim under it so it reads on any hue, including the
     * white end of a saturation sweep, where an unrimmed white thumb disappears.
     *
     * <p>Filled rather than a ring like {@link #paintCursor}: a ring shows the
     * colour it sits on, which is what the field's cursor is for. A channel rail
     * answers "how far along", so the thumb's job is to be seen, and the colour
     * under it is already the one in the swatch.
     */
    private static void paintThumb(Canvas canvas, SizeTokens t,
                                   float cx, float cy, float focus) {
        float thumbW = t.colorThumbW();
        float h = t.colorThumbH();
        float x = cx - thumbW / 2;
        float y = cy - h / 2;
        float radius = thumbW / 2;
        canvas.fillRoundRect(x, y, thumbW, h, radius, Color.rgba(0x000000, 0.45f));
        canvas.fillRoundRect(x + 1, y + 1, thumbW - 2, h - 2, radius - 1, Color.WHITE);
        if (focus > 0.001f) {
            // Around the thumb, the way a Slider rings its knob, and not around
            // the rail, which was tried first and cannot work: the rail's outline
            // sits on a saturated gradient, so an accent ring on it is one more
            // coloured line among the colours the control exists to show.
            float ringW = thumbW + 2 * Strokes.FOCUS_GAP_SLIDER;
            float ringH = h + 2 * Strokes.FOCUS_GAP_SLIDER;
            canvas.drawRoundRect(cx - ringW / 2, cy - ringH / 2, ringW, ringH, ringW / 2,
                    Strokes.FOCUS_RING, Theme.current().focusRing.withAlpha(focus));
        }
    }

    /**
     * A bar across a ramp at {@code y}, outlined so it reads on any hue.
     *
     * <p><b>Built the way {@link #paintThumb} is</b>, a dark pill with a white one inset inside
     * it, because it is the same control wearing a different axis: the rails put a thumb on a
     * horizontal track, this puts one on a vertical track, and a reader who sees square corners
     * on one and a pill on the other reads two controls where there is one.
     *
     * <p>It also stays inside the ramp's box, which is the other half of the same fix. It used to
     * be drawn from {@code -1} to {@code w + 1} with square corners: the overhang fell outside the
     * widget and was clipped away, so the ends came out cut flat against the ramp's rounded
     * silhouette. The vertical centre is clamped for the same reason. Hue 0 and hue 360 put the
     * bar exactly on an edge, where half of it was outside the box and vanished, so the two hues a
     * reader is most likely to pick deliberately were the two the marker showed worst.
     */
    private static void paintMarker(Canvas canvas, float w, float rampH, float y) {
        float h = MARKER_H;
        float radius = h / 2;
        float cy = Math.max(h / 2, Math.min(y, rampH - h / 2));
        canvas.fillRoundRect(0, cy - h / 2, w, h, radius, Color.rgba(0x000000, 0.45f));
        canvas.fillRoundRect(1, cy - h / 2 + 1, w - 2, h - 2, radius - 1, Color.WHITE);
    }

    /**
     * The marker bar's thickness. Not a size token: it is the weight of a pointer on a track,
     * like the pens in {@link Strokes}, and it reads the same at every step for the same reason
     * they do.
     */
    private static final float MARKER_H = 6;

    /**
     * The hue ramp's press/drag/release loop, reporting a [0,1] position. Still a
     * helper rather than folded into {@link HueRamp}: it is the vertical twin of
     * {@link Rail}'s loop, and the pair reads better side by side than one of them
     * would inlined.
     */
    private boolean trackVertical(Widget ramp, MouseEvent event, boolean dragging,
                                  Consumer<Float> apply) {
        switch (event.type()) {
            case PRESS, DRAG -> {
                if (event.type() == MouseEvent.Type.DRAG && !dragging) {
                    return false;
                }
                float h = Math.max(1, ramp.height());
                apply.accept(clamp01(ramp.sceneToLocalY(event.y()) / h));
                event.consume();
                return true;
            }
            case RELEASE -> {
                if (dragging) {
                    event.consume();
                    onCommit.accept(color());
                }
                return false;
            }
            default -> {
                return dragging;
            }
        }
    }

    /** Fills whatever the layout gives it; the fallbacks only matter unbounded. */
    private abstract static class Painted extends Widget {
        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = Theme.current().tokensFor(this);
            return constraints.constrain(
                    constraints.hasBoundedWidth() ? constraints.maxWidth() : t.colorRampW(),
                    constraints.hasBoundedHeight() ? constraints.maxHeight() : t.colorFieldH());
        }
    }

    /**
     * Saturation left→right, value bottom→top, for the current hue, in both directions.
     *
     * <p><b>Nothing in here mirrors</b>, which is the opposite of the rails a few lines up
     * and is deliberate: this is a colour space rather than a reading axis. White in the
     * top-left corner is the picker every artist has already learnt, its cursor and its
     * press are an inverse pair on that same unmirrored axis, and the arrow keys that walk
     * it are the picker's own and stay put with it.
     */
    private final class SaturationValueField extends Painted {

        private boolean dragging;

        SaturationValueField() {
            setCursor(limn.backend.Cursor.CROSSHAIR);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            float w = width();
            float h = height();
            if (w < 1 || h < 1) {
                return;
            }
            float radius = Theme.current().tokensFor(this).radiusSmall();
            canvas.save();
            canvas.clipRoundRect(RoundRect.of(0, 0, w, h, radius));
            // White → the pure hue across, then transparent → black down. Two
            // two-stop gradients, because that is what the toolkit's Paint offers,
            // and they compose into the standard field exactly.
            canvas.fillRect(0, 0, w, h,
                    new LinearGradient(0, 0, w, 0, Color.WHITE, Color.hsv(hue, 1, 1, 1)));
            canvas.fillRect(0, 0, w, h, new LinearGradient(0, 0, 0, h,
                    Color.rgba(0x000000, 0f), Color.BLACK));
            canvas.restore();
            canvas.drawRoundRect(0.5f, 0.5f, w - 1, h - 1, radius, 1, Theme.current().outline);
            paintCursor(canvas, saturation * w, (1 - value) * h, 6);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            switch (event.type()) {
                case PRESS -> {
                    dragging = true;
                    pick(event);
                }
                case DRAG -> {
                    if (dragging) {
                        pick(event);
                    }
                }
                case RELEASE -> {
                    if (dragging) {
                        dragging = false;
                        event.consume();
                        onCommit.accept(color());
                    }
                }
                default -> {
                }
            }
        }

        private void pick(MouseEvent event) {
            float w = Math.max(1, width());
            float h = Math.max(1, height());
            saturation = clamp01(sceneToLocalX(event.x()) / w);
            value = clamp01(1 - sceneToLocalY(event.y()) / h);
            changed();
            event.consume();
        }
    }

    /**
     * The full spectrum, top to bottom.
     *
     * <p>Not a direction site at all: every coordinate that varies here is a y, the bands
     * and the marker span the whole width, and the drag loop reads nothing but the pointer's
     * y. The ramp does change sides when the picker reads right to left, and that is the row
     * holding it doing the placing; the painting inside this box is the same either way.
     */
    private final class HueRamp extends Painted {

        private boolean dragging;

        @Override
        protected void onPaint(Canvas canvas) {
            float w = width();
            float h = height();
            if (w < 1 || h < 1) {
                return;
            }
            float radius = Theme.current().tokensFor(this).radiusSmall();
            canvas.save();
            canvas.clipRoundRect(RoundRect.of(0, 0, w, h, radius));
            float band = h / (HUE_STOPS.length - 1);
            for (int i = 0; i < HUE_STOPS.length - 1; i++) {
                float y = i * band;
                canvas.fillRect(0, y, w, band + 1,
                        new LinearGradient(0, y, 0, y + band, HUE_STOPS[i], HUE_STOPS[i + 1]));
            }
            canvas.restore();
            canvas.drawRoundRect(0.5f, 0.5f, w - 1, h - 1, radius, 1, Theme.current().outline);
            paintMarker(canvas, w, h, hue / 360f * h);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            dragging = trackVertical(this, event, dragging, t -> {
                hue = t * 360f;
                changed();
            });
        }
    }

    /**
     * Before and after, side by side. The comparison is what turns "is this the
     * colour I wanted" into a glance instead of a memory test, and it is the part
     * of a professional picker people miss first when it is absent.
     */
    private final class Preview extends Painted {

        @Override
        protected void onPaint(Canvas canvas) {
            float w = width();
            float h = height();
            if (w < 2 || h < 1) {
                return;
            }
            SizeTokens t = Theme.current().tokensFor(this);
            float radius = t.radiusSmall();
            canvas.save();
            canvas.clipRoundRect(RoundRect.of(0, 0, w, h, radius));
            paintChecker(canvas, t, 0, 0, w, h);
            // An ordered pair, so it reads in reading order: the colour the picker
            // opened on first, the one it is showing now after it. Not a colour space
            // like the field, and not an axis like a rail: a before and an after, which
            // is a sentence, and a sentence that ran backwards would say the new colour
            // was the one being compared against. The checkerboard under both halves is
            // a texture and stays where it is.
            boolean rtl = layoutDirection() == LayoutDirection.RTL;
            canvas.fillRect(rtl ? w / 2 : 0, 0, w / 2, h, original);
            canvas.fillRect(rtl ? 0 : w / 2, 0, w / 2, h, color());
            canvas.restore();
            canvas.drawRoundRect(0.5f, 0.5f, w - 1, h - 1, radius, 1, Theme.current().outline);
        }
    }
}
