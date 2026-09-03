package limn.themeeditor;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ColorPickerButton;
import limn.components.DisplayMode;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.ScrollGutters;
import limn.components.ScrollView;
import limn.components.SizeTokens;
import limn.components.Slider;
import limn.components.Separator;
import limn.components.TextField;
import limn.components.Theme;
import limn.components.ThemeFormat;
import limn.components.TokenColumn;
import limn.components.TokenPadding;
import limn.components.TokenRow;
import limn.components.Tokens;
import limn.concurrent.Ui;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Fonts;
import limn.i18n.I18nString;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The screen an application embeds so that its users can build a palette of their own:
 * every tone of a {@link Theme} on a colour well, a preview that shows all of them at once,
 * and a legibility report that names the unreadable pair before anyone ships it.
 *
 * <pre>{@code
 * ThemeEditor editor = new ThemeEditor(Theme.current());
 * editor.onChange(theme -> settings.put("theme", ThemeFormat.write(theme)));
 * }</pre>
 *
 * <p><b>It applies what it edits, by default.</b> The palette is process-wide, so the
 * honest preview of a change is the application wearing it, including this editor, which
 * re-skins under the user's hands. {@link #setApplyLive} turns that off for an application
 * that cannot afford it; the {@link ThemePreview} beside the tones shows the edited palette
 * either way, because it paints from the value rather than from {@link Theme#current()}.
 *
 * <p><b>What it does not decide is where a palette lives.</b> Copy and Paste move one
 * through the clipboard as {@link ThemeFormat} text and need nothing from the platform; a
 * file is an application's business, and {@link ThemeEditorFiles} is the optional half that
 * opens a chooser for one.
 *
 * <p>UI thread only, like every widget here.
 */
public final class ThemeEditor extends Widget {

    /** The three groups the builder can work out for itself. */
    private enum Derivation { ACCENT, DISABLED, SEMANTIC }

    /**
     * A group of tones, as the editor stacks them, and the derivation that fills it in.
     *
     * @param derivation {@code null} where there is nothing to derive: the surfaces and
     *                   the ink are the palette's actual input, and a button offering to
     *                   invent them would be offering to invent the palette
     */
    private record Section(I18nString title, Derivation derivation, List<Theme.Token> tokens) {
    }

    /**
     * Every tone, grouped the way a palette is reasoned about. Every token appears
     * exactly once; {@code ThemeEditorTest} asserts it, which is what stops a token added
     * to {@link Theme} from being editable nowhere.
     */
    private static final List<Section> SECTIONS = List.of(
            new Section(ThemeEditorStrings.SECTION_SURFACES, null, List.of(
                    Theme.Token.BACKGROUND, Theme.Token.SURFACE, Theme.Token.SURFACE_RAISED)),
            new Section(ThemeEditorStrings.SECTION_ACCENT, Derivation.ACCENT, List.of(
                    Theme.Token.PRIMARY, Theme.Token.PRIMARY_HOVER, Theme.Token.PRIMARY_PRESSED,
                    Theme.Token.ON_PRIMARY)),
            new Section(ThemeEditorStrings.SECTION_TEXT, null, List.of(
                    Theme.Token.TEXT, Theme.Token.TEXT_MUTED)),
            new Section(ThemeEditorStrings.SECTION_CHROME, Derivation.DISABLED, List.of(
                    Theme.Token.OUTLINE, Theme.Token.FOCUS_RING,
                    Theme.Token.DISABLED_FILL, Theme.Token.DISABLED_TEXT, Theme.Token.SCRIM)),
            new Section(ThemeEditorStrings.SECTION_SEMANTIC, Derivation.SEMANTIC, List.of(
                    Theme.Token.DANGER, Theme.Token.SUCCESS, Theme.Token.WARNING,
                    Theme.Token.INFO)));

    private final TokenColumn root = new TokenColumn(Tokens.Role.MEDIUM);
    private final TextField nameField = new TextField();
    private final Checkbox darkToggle =
            new Checkbox(Checkbox.Variant.SWITCH, ThemeEditorStrings.DARK);
    private final Checkbox liveToggle =
            new Checkbox(Checkbox.Variant.BOX, ThemeEditorStrings.APPLY_LIVE);
    /**
     * The base list's first entry, which is not a palette: it is what the control shows for
     * a palette that did not come from a built-in: one loaded from a file, or one already
     * edited away from the one it started as. Without it the control has no way to say
     * "none of these" and has to name one, which is a small lie told every time.
     */
    private static final int BASE_CUSTOM = 0;

    private final ComboBox baseChoice;
    private final Slider cornerSlider = new Slider(0, Theme.MAX_CORNER_SCALE);
    private final Label cornerReadout = new Label("");
    /**
     * The font picker's row, rebuilt in place when the catalog grows. A {@link ComboBox}'s items
     * are fixed at construction, so a growable list is a new control each time; the row is what
     * stays put in the tree.
     */
    private final TokenRow fontRow = new TokenRow(Tokens.Role.MEDIUM);
    private ComboBox fontChoice;
    private final Label fontNote = new Label("");
    /**
     * The families the picker is currently offering, parallel to {@link #fontChoice}'s items,
     * with {@link Font#DEFAULT_FAMILY} at index 0. Held rather than re-read from {@code Fonts}
     * because the catalog can grow between building the list and reading a selection out of it;
     * an index into a list that has since changed would pick a different font than the one
     * clicked.
     */
    private List<String> fontFamilies = List.of(Font.DEFAULT_FAMILY);
    /**
     * Rebuilds the font picker when the catalog changes. The backend enumerates the operating
     * system in the background, so an editor built during startup sees the bundled families only
     * and would offer a two-item list forever without this.
     */
    private final Runnable fontCatalogListener = this::rebuildFontChoice;
    private final Map<Theme.Token, ColorPickerButton> wells = new EnumMap<>(Theme.Token.class);
    /** Handed to every colour well; see {@link #setPickerDisplayMode}. */
    private DisplayMode pickerDisplayMode = DisplayMode.NATIVE_WINDOW;
    private final ThemePreview preview;
    private final TokenColumn report = new TokenColumn(Tokens.Role.SMALL);
    private final Label status = new Label("");

    private Theme.Builder builder;
    /** What {@link #revert()} goes back to, and what the editor opened on. */
    private Theme original;
    /**
     * Which built-in the combo shows, held rather than derived.
     *
     * <p>Deriving it from the palette was a bug: picking a base keeps the user's <em>name</em>,
     * so the result never equals the built-in it came from, {@code indexOf} answered -1, and
     * the combo snapped back to the first entry on every pick: the palette changed, the
     * control denied it. "Start from" is a thing you did, not a thing the palette is.
     */
    private int baseIndex;
    /** The palette the application was wearing when this editor was built. */
    private final Theme themeAtStart = Theme.current();
    /** What the report is currently showing, so an unchanged verdict rebuilds nothing. */
    private List<ThemeAudit.Finding> shownFindings = List.of();
    private boolean applyLive = true;
    private Consumer<Theme> onChange = theme -> {
    };
    /** Guards the round trip: writing a control must not be read back as an edit. */
    private boolean syncing;

    /** An editor on the palette the application is currently wearing. */
    public ThemeEditor() {
        this(Theme.current());
    }

    /** An editor on {@code theme}, which it neither owns nor mutates. */
    public ThemeEditor(Theme theme) {
        Objects.requireNonNull(theme, "theme");
        this.original = theme;
        this.builder = theme.toBuilder();
        this.baseIndex = baseIndexOf(theme);
        this.preview = new ThemePreview(theme);
        this.baseChoice = ComboBox.localized(baseItems());

        nameField.setPlaceholder(ThemeEditorStrings.NAME_PLACEHOLDER);
        nameField.onChange(text -> {
            if (!syncing) {
                builder.name(text.isBlank() ? ThemeEditorStrings.NAME_PLACEHOLDER.get() : text);
                edited();
            }
        });
        darkToggle.onChange(dark -> {
            if (!syncing) {
                builder.dark(dark);
                edited();
            }
        });
        liveToggle.setChecked(true).onChange(this::setApplyLive);
        baseChoice.onSelect(index -> {
            if (!syncing) {
                pickBase(index);
            }
        });
        rebuildFontChoice();

        root.crossAlignment(Flex.CrossAlignment.STRETCH);
        root.add(header());
        root.add(Separator.horizontal());
        root.add(Expanded.of(body(), 1));
        root.add(Separator.horizontal());
        root.add(footer());
        add(root);

        syncFromBuilder();
    }

    /**
     * Subscribes to the font catalog while this editor is on screen. Here rather than in the
     * constructor because {@link Fonts} holds its listeners strongly: an editor that subscribed
     * once and never unsubscribed would be kept alive by the toolkit for the life of the process,
     * and would go on rebuilding a control in a tree nobody is showing.
     */
    @Override
    protected void onAttached() {
        Fonts.addChangeListener(fontCatalogListener);
        // A change that landed while this editor was off screen reached nobody: the listener was
        // not subscribed. That is the ordinary shape of the background enumeration, which is
        // kicked by the first listing (this editor's construction) and lands some frames later,
        // possibly after a settings page has been closed again; an editor that only subscribed
        // here would reopen offering the bundled families for the life of the process. Compared
        // rather than rebuilt unconditionally so the first attach, and every attach where nothing
        // moved, leaves the control the constructor built in place.
        if (!offeredFamilies().equals(fontFamilies)) {
            rebuildFontChoice();
        }
    }

    @Override
    protected void onDetached() {
        Fonts.removeChangeListener(fontCatalogListener);
    }

    // --- the value -----------------------------------------------------------

    /** The palette as edited. A fresh value each call; the editor holds a builder. */
    public Theme theme() {
        return builder.build();
    }

    /**
     * Loads a palette in, replacing what is being edited <em>and</em> what {@link #revert()}
     * goes back to. Does not notify: the application is the source of this change.
     */
    public ThemeEditor setTheme(Theme theme) {
        Ui.checkUiThread();
        Objects.requireNonNull(theme, "theme");
        this.original = theme;
        this.builder = theme.toBuilder();
        baseIndex = baseIndexOf(theme);
        syncFromBuilder();
        pushLive();
        return this;
    }

    /**
     * Sets one tone, by exactly the path choosing it on its well takes: the well moves, the
     * preview and the report follow, the palette goes live if it is meant to, and
     * {@link #onChange} is told. The entry point for an application that drives the editor
     * from somewhere else: a preset menu, a colour sampled off an image, an undo stack.
     */
    public ThemeEditor setToken(Theme.Token token, Color colour) {
        Ui.checkUiThread();
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(colour, "colour");
        builder.set(token, colour);
        syncFromBuilder();
        edited();
        return this;
    }

    /**
     * Sets how round the corners are (see {@link Theme#cornerScale}) by the same path the
     * slider takes, {@link #onChange} included. The value is clamped to
     * {@code [0, }{@link Theme#MAX_CORNER_SCALE}{@code ]}.
     */
    public ThemeEditor setCornerScale(float value) {
        Ui.checkUiThread();
        builder.cornerScale(value);
        syncCornerControl();
        edited();
        return this;
    }

    /**
     * Replaces every tone with a built-in's, keeping the name: what picking one in the
     * "start from" control does, and the path the control's own bug lived on. Package-private
     * rather than public: an application that wants this calls {@code setTheme} with the
     * built-in it means, and says for itself what happens to the name.
     */
    void pickBase(int index) {
        if (index == BASE_CUSTOM) {
            return; // "Custom" is what the control reports, not something to start from
        }
        String kept = builder.name();
        setTheme(Theme.builtins().get(index - 1).toBuilder().name(kept).build());
        baseIndex = index; // after setTheme, which recomputes it from the palette
        syncFromBuilder();
        edited();
    }

    /** Puts back the palette this editor opened on, or the last one given to {@link #setTheme}. */
    public ThemeEditor revert() {
        Ui.checkUiThread();
        this.builder = original.toBuilder();
        syncFromBuilder();
        edited();
        return this;
    }

    /** Whether anything has changed since the editor opened or was last loaded. */
    public boolean isModified() {
        return !original.equals(theme());
    }

    /**
     * Called after every edit, with the palette as it now stands, including the one
     * {@link #revert()} restores. Not called by {@link #setTheme}. Keep it cheap: a colour
     * well reports on every frame of a drag, and so does this.
     */
    public ThemeEditor onChange(Consumer<Theme> listener) {
        Ui.checkUiThread();
        this.onChange = listener == null ? theme -> {
        } : listener;
        return this;
    }

    /**
     * Whether an edit is installed process-wide as it is made (default {@code true}).
     *
     * <p>Turning it on installs what is being edited at once; turning it off leaves
     * {@link Theme#current()} where it stands rather than putting anything back.
     * {@link #detachLive()} is what puts the application back, and nothing calls it for
     * you: a widget cannot tell being removed from a scene apart from being moved to
     * another one.
     */
    public ThemeEditor setApplyLive(boolean value) {
        Ui.checkUiThread();
        this.applyLive = value;
        if (liveToggle.isChecked() != value) {
            boolean wasSyncing = syncing;
            syncing = true;
            try {
                liveToggle.setChecked(value);
            } finally {
                syncing = wasSyncing;
            }
        }
        pushLive();
        return this;
    }

    /**
     * Asks for the colour pickers to open in a window of their own or as an overlay inside this
     * editor's window; see {@link DisplayMode}. Default {@link DisplayMode#NATIVE_WINDOW}.
     *
     * <p>Worth choosing here more than almost anywhere else, because of what this screen is:
     * every tone the picker changes is being repainted live in the window behind it, and a native
     * picker floats over that window rather than inside it. An application that records, streams
     * or screenshots its own theming screen (the toolkit's own gallery does) sees the picker at
     * all only in scene.
     *
     * <p>Applies to every well, including the ones already built, so it can be called after
     * construction. Pickers already open are not moved.
     */
    public ThemeEditor setPickerDisplayMode(DisplayMode mode) {
        Ui.checkUiThread();
        this.pickerDisplayMode = Objects.requireNonNull(mode, "mode");
        for (ColorPickerButton well : wells.values()) {
            well.setPickerDisplayMode(mode);
        }
        return this;
    }

    /** @return the presentation the wells will raise their next picker in. */
    public DisplayMode pickerDisplayMode() {
        return pickerDisplayMode;
    }

    /** Whether edits are installed process-wide as they are made. */
    public boolean isApplyLive() {
        return applyLive;
    }

    /**
     * Puts back the palette the application was wearing when this editor was built, what a
     * "close without keeping this" button calls. Does nothing if the palette has not moved.
     */
    public void detachLive() {
        Ui.checkUiThread();
        if (Theme.current() != themeAtStart) {
            Theme.setCurrent(themeAtStart);
            // The typeface is process-wide and outlives the palette that asked for it, so putting
            // the palette back without this leaves the application wearing a font it never chose.
            themeAtStart.applyFontFamily();
            refreshScene();
        }
    }

    // --- the report ----------------------------------------------------------

    /** What is illegible in the palette as it stands, the same list the panel shows. */
    public List<ThemeAudit.Finding> audit() {
        return ThemeAudit.of(theme());
    }

    // --- the clipboard -------------------------------------------------------

    /** Puts the palette on the clipboard as {@link ThemeFormat} text. */
    public void copyToClipboard() {
        Ui.checkUiThread();
        clipboard().set(ThemeFormat.write(theme()));
        status.setText(ThemeEditorStrings.COPIED);
    }

    /**
     * Reads a palette off the clipboard, if what is there is one.
     *
     * @return whether it was. When it was not, the reason lands in the editor's own status
     *         line rather than in an exception: a paste is a guess by definition, and a
     *         wrong guess is not a failure the application has to handle
     */
    public boolean pasteFromClipboard() {
        Ui.checkUiThread();
        String text = clipboard().get();
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            load(ThemeFormat.parse(text));
            status.setText("");
            return true;
        } catch (IllegalArgumentException malformed) {
            status.setText(ThemeEditorStrings.PASTE_FAILED.format(malformed.getMessage()));
            return false;
        }
    }

    /** The well a tone is edited on, the seam a headless test drives the editor through. */
    ColorPickerButton wellFor(Theme.Token token) {
        return wells.get(token);
    }

    /** The "start from" control, so a test can read back what it is showing. */
    ComboBox baseChoice() {
        return baseChoice;
    }

    /** The shape slider, so a test can drive it the way a pointer does. */
    Slider cornerSlider() {
        return cornerSlider;
    }

    /**
     * The font control as it stands, so a test can tell a rebuilt picker from the one it had.
     * A new instance each time the catalog moves; see {@link #fontRow}.
     */
    ComboBox fontChoice() {
        return fontChoice;
    }

    /** The families the picker offers, in its order, so a test reads the catalog through the control. */
    List<String> offeredFontFamilies() {
        return fontFamilies;
    }

    /** The number beside the shape slider, so a test can read what it is showing. */
    Label cornerReadout() {
        return cornerReadout;
    }

    /** Adopts {@code theme} as an edit; used by paste and by {@link ThemeEditorFiles}. */
    void load(Theme theme) {
        this.builder = theme.toBuilder();
        syncFromBuilder();
        edited();
    }

    /** The line the editor reports its own outcomes on. */
    void setStatus(String text) {
        status.setText(text);
    }

    /** The line the editor reports its own outcomes on. */
    void setStatus(I18nString text) {
        status.setText(text);
    }

    // --- editing -------------------------------------------------------------

    /** One edit: refresh what is derived from it, install it, and tell the listener. */
    private void edited() {
        Theme theme = theme();
        preview.setTheme(theme);
        buildReport(theme);
        pushLive(theme);
        onChange.accept(theme);
    }

    private void pushLive() {
        if (applyLive) {
            pushLive(theme());
        }
    }

    private void pushLive(Theme theme) {
        if (!applyLive) {
            return;
        }
        Theme.setCurrent(theme);
        // Safe on the hot path, and only because Fonts.setDefaultFamily returns immediately when
        // the name has not moved: this runs on every frame of a colour drag, and the call it makes
        // is the one act in the toolkit that can read a font file inside the next frame. A palette
        // whose family did change pays that once, on the frame the picker was used.
        theme.applyFontFamily();
        refreshScene();
    }

    /**
     * Repaints the window in the new palette.
     *
     * <p><b>A repaint, not a relayout</b>, and that is load-bearing rather than an
     * optimisation: this runs on every frame of a colour drag, and marking the root for
     * layout would re-measure the whole tree sixty times a second. It is safe because a
     * palette carries no metric at all: spacing, radii and type come from the process-wide
     * size table, which {@code ThemeBuilderTest} asserts. If a palette ever gains a metric,
     * this line has to become {@code markNeedsLayout} and the drag has to be throttled.
     *
     * <p>The clear colour is the one thing that must be assigned rather than repainted: it
     * was copied out of the palette when the scene was built. A translucent one is left
     * alone: a scene that cleared to transparent did so to let the desktop through, and
     * that is not a palette's decision to overrule.
     */
    private void refreshScene() {
        Scene scene = scene();
        if (scene == null) {
            return;
        }
        if (scene.background().a() >= 1f) {
            scene.setBackground(Theme.current().background);
        }
        scene.root().invalidate();
    }

    /** Writes every control from the builder without reading any of them back as an edit. */
    private void syncFromBuilder() {
        syncing = true;
        try {
            nameField.setText(builder.name());
            darkToggle.setChecked(builder.isDark());

            for (Map.Entry<Theme.Token, ColorPickerButton> entry : wells.entrySet()) {
                entry.getValue().setColor(builder.get(entry.getKey()));
            }
            baseChoice.setSelectedIndex(baseIndex);
        } finally {
            syncing = false;
        }
        syncCornerControl();
        // The list itself can need rebuilding, not just the selection: a palette loaded from a
        // file may name a family that is not in the catalog and therefore not yet an entry.
        rebuildFontChoice();
        Theme theme = theme();
        preview.setTheme(theme);
        buildReport(theme);
    }

    // --- the tree ------------------------------------------------------------

    private Widget header() {
        TokenRow row = new TokenRow(Tokens.Role.MEDIUM);
        row.crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(new Label(ThemeEditorStrings.NAME));
        row.add(Expanded.of(nameField, 1));
        row.add(darkToggle);
        row.add(new Label(ThemeEditorStrings.START_FROM).setMuted(true));
        row.add(baseChoice);
        return row;
    }

    private Widget body() {
        TokenRow row = new TokenRow(Tokens.Role.LARGE);
        row.crossAlignment(Flex.CrossAlignment.STRETCH);
        // Vertical only, both here and in the report. A ScrollView that also scrolls
        // sideways hands its child an unbounded width, and a Label given unbounded width
        // never wraps: the findings would run off the right edge instead of stacking.
        //
        // RESERVED, not the OVERLAY default: this is a form, and ScrollGutters says why a
        // form wants a strip of its own. Every row here ends in a control the reader is
        // aiming at (a well, a slider, a dropdown), so a bar floating over the trailing
        // edge floats over the hit target, not over slack.
        //
        // The inset goes INSIDE the viewport, and it is the half the reserved strip cannot
        // do: a strip keeps the bar off the trailing edge, but the clip is still the
        // viewport's own, so a control focused at the top or bottom of the run loses the
        // ring it paints outside its box. Only pixels inside the content put room there.
        row.add(Expanded.of(new ScrollView(new TokenPadding(tokenColumn()), false, true)
                .setBarLayout(ScrollGutters.Layout.RESERVED), 3));
        row.add(Expanded.of(sidePanel(), 2));
        return row;
    }

    private Widget tokenColumn() {
        TokenColumn column = new TokenColumn(Tokens.Role.MEDIUM);
        column.crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label(ThemeEditorStrings.SECTION_SHAPE)
                .setRole(Label.Role.LABEL).setStrong(true));
        column.add(shapeRow());
        column.add(new Label(ThemeEditorStrings.SECTION_TYPE)
                .setRole(Label.Role.LABEL).setStrong(true));
        column.add(typeRow());
        for (Section section : SECTIONS) {
            column.add(new Label(section.title()).setRole(Label.Role.LABEL).setStrong(true));
            for (Theme.Token token : section.tokens()) {
                column.add(tokenRow(token));
            }
            if (section.derivation() != null) {
                column.add(deriveButton(section.derivation()));
            }
        }
        return column;
    }

    /**
     * The one metric a palette carries. A single slider rather than three numbers per size
     * step: the fifteen radii in the table are one ramp, and what a palette author is
     * choosing is where on it to sit: square, the shipped ramp, or something softer.
     */
    private Widget shapeRow() {
        cornerSlider.onChange(value -> {
            if (!syncing) {
                setCornerScale(value);
            }
        });
        syncCornerControl();

        TokenRow row = new TokenRow(Tokens.Role.MEDIUM);
        row.crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(new Label(ThemeEditorStrings.CORNER_SCALE));
        row.add(Expanded.of(cornerSlider, 1));
        row.add(cornerReadout.setMuted(true));
        return row;
    }

    /**
     * The typeface the palette asks for. A picker rather than a text field, because a name
     * typed by hand is a name that silently falls back on every machine but one, and the note
     * beside it says so when the palette carries a family this machine does not have, which is
     * the normal state of a palette authored somewhere else.
     */
    private Widget typeRow() {
        fontRow.crossAlignment(Flex.CrossAlignment.CENTER);
        return fontRow;
    }

    /**
     * Rebuilds the picker from the current catalog, keeping the palette's own family selected,
     * including when that family is not installed, which is why the list can carry an entry the
     * catalog does not.
     *
     * <p>Runs on every catalog change, which in practice is twice: once at construction against
     * the bundled families, and once when the background enumeration of the operating system
     * lands, or, if the editor was off screen at that moment, when it is next attached. Anything
     * the user has chosen in between survives, because the selection is written back from the
     * builder rather than from the old control.
     */
    private void rebuildFontChoice() {
        fontFamilies = offeredFamilies();

        List<I18nString> items = new ArrayList<>(fontFamilies.size());
        for (String family : fontFamilies) {
            items.add(Font.DEFAULT_FAMILY.equals(family)
                    ? ThemeEditorStrings.FONT_DEFAULT
                    : I18nString.literal(family));
        }

        ComboBox replacement = ComboBox.localized(items);
        replacement.onSelect(index -> {
            if (!syncing) {
                pickFont(index);
            }
        });
        for (Widget child : List.copyOf(fontRow.children())) {
            fontRow.remove(child);
        }
        fontChoice = replacement;
        fontRow.add(new Label(ThemeEditorStrings.FONT_FAMILY));
        fontRow.add(Expanded.of(fontChoice, 1));
        fontRow.add(fontNote.setMuted(true));
        syncFontControl();
    }

    /**
     * What the picker should be offering right now: the default, then the catalog as it stands,
     * then the palette's own family if the catalog lacks it. A palette naming a family this
     * machine does not have still has to be representable in the control, or opening the file
     * would silently rewrite the palette to the default.
     */
    private List<String> offeredFamilies() {
        List<String> families = new ArrayList<>();
        families.add(Font.DEFAULT_FAMILY);
        families.addAll(Fonts.available());
        String chosen = builder.fontFamily();
        if (!families.contains(chosen)) {
            families.add(chosen);
        }
        return List.copyOf(families);
    }

    /** Writes the picker and its note from the builder, without reading either back as an edit. */
    private void syncFontControl() {
        boolean wasSyncing = syncing;
        syncing = true;
        try {
            String chosen = builder.fontFamily();
            int index = fontFamilies.indexOf(chosen);
            fontChoice.setSelectedIndex(Math.max(0, index));
        } finally {
            syncing = wasSyncing;
        }
        String chosen = builder.fontFamily();
        boolean missing = !Font.DEFAULT_FAMILY.equals(chosen) && !Fonts.available().contains(chosen);
        fontNote.setText(missing ? ThemeEditorStrings.FONT_MISSING : I18nString.EMPTY);
    }

    /** One pick from the font control, by exactly the path an edit takes. */
    private void pickFont(int index) {
        Ui.checkUiThread();
        if (index < 0 || index >= fontFamilies.size()) {
            return;
        }
        builder.fontFamily(fontFamilies.get(index));
        edited();
    }

    /**
     * Writes the slider and its readout from the builder, without reading either back as an
     * edit. The readout is the radius the scale lands on at the default step, the number a
     * designer thinks in, not the multiplier that produced it.
     */
    private void syncCornerControl() {
        boolean wasSyncing = syncing;
        syncing = true;
        try {
            cornerSlider.setValue(builder.cornerScale());
        } finally {
            syncing = wasSyncing;
        }
        // The scale applied to the default row, rather than building a whole palette to read
        // one number back out of it: this runs on every frame of a shape drag.
        float radius = SizeTokens.MEDIUM.radiusMedium() * builder.cornerScale();
        cornerReadout.setText(ThemeEditorStrings.CORNER_SCALE_VALUE.format(
                String.format(java.util.Locale.ROOT, "%.1f", radius)));
    }

    private Widget tokenRow(Theme.Token token) {
        ColorPickerButton well = new ColorPickerButton(token.read(original));
        well.setDialogTitle(ThemeEditorStrings.of(token));
        // Alpha off for every tone but one. The rest are surfaces, or ink drawn on one: a
        // translucent tone composites against whatever happens to be behind it, which is the
        // one thing a palette cannot know, and the audit's contrast maths would be measuring
        // a colour that never appears on screen. The scrim is the exception because
        // compositing is its whole job, and an opaque one would hide what it means to block.
        well.setAlphaEnabled(token == Theme.Token.SCRIM);
        well.setPickerDisplayMode(pickerDisplayMode);
        well.onChange(colour -> {
            if (!syncing) {
                builder.set(token, colour);
                edited();
            }
        });
        wells.put(token, well);

        TokenRow row = new TokenRow(Tokens.Role.MEDIUM);
        row.crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(Expanded.of(new Label(ThemeEditorStrings.of(token)), 1));
        row.add(well);
        return row;
    }

    /**
     * The "work this group out for me" button. It names the derivation rather than holding
     * the builder: {@link #setTheme} and paste replace that field, and a button still
     * pointing at the old one would derive into a palette nobody is editing any more.
     */
    private Button deriveButton(Derivation derivation) {
        I18nString caption = switch (derivation) {
            case ACCENT -> ThemeEditorStrings.DERIVE_ACCENT;
            case DISABLED -> ThemeEditorStrings.DERIVE_DISABLED;
            case SEMANTIC -> ThemeEditorStrings.DERIVE_SEMANTIC;
        };
        Button button = new Button(caption).setSecondary(true);
        button.withControlSize(ControlSize.SMALL);
        button.onAction(() -> {
            derive(derivation);
            syncFromBuilder();
            edited();
        });
        return button;
    }

    private void derive(Derivation derivation) {
        switch (derivation) {
            case ACCENT -> builder.deriveAccentStates();
            case DISABLED -> builder.deriveDisabled();
            case SEMANTIC -> builder.deriveSemanticStates();
        }
    }

    private Widget sidePanel() {
        TokenColumn column = new TokenColumn(Tokens.Role.MEDIUM);
        column.crossAlignment(Flex.CrossAlignment.STRETCH);
        column.add(new Label(ThemeEditorStrings.PREVIEW).setRole(Label.Role.LABEL).setStrong(true));
        column.add(preview);
        column.add(new Label(ThemeEditorStrings.LEGIBILITY)
                .setRole(Label.Role.LABEL).setStrong(true));
        report.crossAlignment(Flex.CrossAlignment.STRETCH);
        // RESERVED for the same reason, one category over: the report is a table of
        // findings whose text wraps to the full width, so an overlay bar lands on the last
        // word of every line it crosses rather than on margin.
        column.add(Expanded.of(new ScrollView(new TokenPadding(report), false, true)
                .setBarLayout(ScrollGutters.Layout.RESERVED), 1));
        return column;
    }

    private Widget footer() {
        TokenRow row = new TokenRow(Tokens.Role.MEDIUM);
        row.crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(liveToggle);
        row.add(Expanded.of(status.setMuted(true).setOverflow(Label.Overflow.ELLIPSIS), 1));
        row.add(new Button(ThemeEditorStrings.REVERT).setSecondary(true).onAction(this::revert));
        row.add(new Button(ThemeEditorStrings.PASTE).setSecondary(true)
                .onAction(this::pasteFromClipboard));
        row.add(new Button(ThemeEditorStrings.COPY).onAction(this::copyToClipboard));
        return row;
    }

    /**
     * Rebuilds the findings list, but only when the verdict has actually moved. This runs
     * on every frame of a colour drag, and most frames change no finding at all; rebuilding
     * thirty labels each time would put a tree edit in the middle of a drag.
     */
    private void buildReport(Theme theme) {
        List<ThemeAudit.Finding> findings = ThemeAudit.of(theme);
        boolean sameVerdict = findings.equals(shownFindings);
        shownFindings = findings;
        if (sameVerdict && !report.children().isEmpty()) {
            return;
        }
        for (Widget child : new ArrayList<>(report.children())) {
            report.remove(child);
        }
        if (findings.isEmpty()) {
            report.add(new Label(ThemeEditorStrings.LEGIBILITY_CLEAN).setMuted(true).setWrap(true));
            return;
        }
        int errors = 0;
        int warnings = 0;
        int notes = 0;
        for (ThemeAudit.Finding finding : findings) {
            switch (finding.level()) {
                case ERROR -> errors++;
                case WARNING -> warnings++;
                case INFO -> notes++;
            }
        }
        report.add(new Label(ThemeEditorStrings.LEGIBILITY_COUNTS.format(
                Integer.toString(errors), Integer.toString(warnings), Integer.toString(notes)))
                .setMuted(true).setWrap(true));
        for (ThemeAudit.Finding finding : findings) {
            // Coloured from the palette being EDITED, not from the one the label would
            // otherwise read: an editor whose warnings are invisible in the palette that
            // caused them is the joke it sounds like.
            Color ink = switch (finding.level()) {
                case ERROR -> theme.danger;
                case WARNING -> theme.warning;
                case INFO -> theme.textMuted;
            };
            // One line per finding, clipped with an ellipsis and the whole of it on the
            // tooltip. Wrapping instead would let a single finding take three lines of a
            // panel that has to hold twenty of them, and the part that matters (which pair,
            // how far off) is at the front of the line either way.
            String line = finding.describe();
            Label label = new Label(line).setColor(ink).setOverflow(Label.Overflow.ELLIPSIS);
            label.setTooltip(line);
            report.add(label);
        }
    }

    /** "Custom", then every built-in, so the control can say "none of these". */
    private static List<I18nString> baseItems() {
        List<I18nString> names = new ArrayList<>(Theme.builtins().size() + 1);
        names.add(ThemeEditorStrings.BASE_CUSTOM);
        for (Theme theme : Theme.builtins()) {
            names.add(theme.displayName());
        }
        return names;
    }

    /** Which entry of {@link #baseItems()} {@code theme} is, or {@link #BASE_CUSTOM}. */
    private static int baseIndexOf(Theme theme) {
        int found = Theme.builtins().indexOf(theme);
        return found >= 0 ? found + 1 : BASE_CUSTOM;
    }

    // --- layout --------------------------------------------------------------

    @Override
    protected Size onMeasure(Constraints constraints) {
        Size size = root.measure(constraints);
        return constraints.constrain(size.width(), size.height());
    }

    @Override
    protected void onLayout() {
        root.measure(Constraints.tight(width(), height()));
        root.layoutBox(0, 0, width(), height());
    }
}
