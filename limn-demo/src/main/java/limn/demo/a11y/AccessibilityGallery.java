package limn.demo.a11y;

import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.accessibility.Accessible;
import limn.accessibility.Accessible.Role;
import limn.components.Button;
import limn.components.ButtonGroup;
import limn.components.Checkbox;
import limn.components.ColorPicker;
import limn.components.ColorPickerButton;
import limn.components.ComboBox;
import limn.components.ContextMenus;
import limn.components.Dialog;
import limn.components.DisplayMode;
import limn.components.ImageView;
import limn.components.Label;
import limn.components.ListView;
import limn.components.MediaControls;
import limn.components.Menu;
import limn.components.MenuBar;
import limn.components.PasswordField;
import limn.components.PopupMenu;
import limn.components.ProgressBar;
import limn.components.RadioButton;
import limn.components.ScrollBar;
import limn.components.ScrollView;
import limn.components.SearchField;
import limn.components.SegmentedControl;
import limn.components.Separator;
import limn.components.Slider;
import limn.components.Spinner;
import limn.components.SplitPane;
import limn.components.TabbedPane;
import limn.components.date.CalendarView;
import limn.components.date.DateField;
import limn.components.date.DatePicker;
import limn.components.date.DayMark;
import limn.components.table.Table;
import limn.components.TextArea;
import limn.components.TextField;
import limn.components.Theme;
import limn.components.ToolBar;
import limn.components.VideoView;
import limn.components.Viewport3D;
import limn.components.chart.BarChart;
import limn.components.chart.ChartSeries;
import limn.components.chart.DonutChart;
import limn.components.chart.LineChart;
import limn.concurrent.Ui;
import limn.demo.Labelled;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.i18n.I18nString;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;

import java.util.List;
import java.util.function.Supplier;

/**
 * Every component with an accessibility surface, each in a small scene built the way a screen
 * reader user would find <em>correct</em>: every focusable control named the way the toolkit
 * intends — a caption bound with {@link Label#setLabelFor}, an application name on a picture, a
 * tooltip on an icon-only button, a title on a dialog — and nothing copied from the kitchen
 * sink, which is a showcase of what the widgets look like and not of what they say.
 *
 * <p>It is a reference in two directions. {@code AccessibleGalleryTest} in this module's tests
 * renders every entry headlessly in both palettes and asserts ADR 039 §12.1's four gallery-wide
 * invariants over the published trees, and asserts that every toolkit class overriding an
 * accessibility hook has an entry here — so a component cannot gain an accessible surface without
 * a scene that shows it used right. And {@link #main} opens the same entries in a real window
 * with a picker, so a reader on a guest can be pointed at exactly the scene the test read.
 *
 * <p>An entry names the classes it <b>covers</b>: the components whose hooks the scene exercises.
 * That list is what the completeness test matches against the toolkit's sources, and it holds
 * only classes that declare a hook — {@code ButtonGroup}, {@code Menu} and {@code MenuItem} are
 * models with no node of their own and are not listed, though the scenes use them.
 */
public final class AccessibilityGallery {

    /**
     * An entry built: the root widget, and what to do once it has been laid out — open the
     * combo, show the dialog, drop the menu — which cannot happen before a first frame gave the
     * anchor a box. {@code afterFirstFrame} runs on the UI thread with the root bound to a scene.
     *
     * @param root           the scene's root widget
     * @param afterFirstFrame what to do after the first frame; a no-op for a static entry
     * @param focus          the widget a reader run puts the keyboard in once the entry is laid
     *                       out, or {@code null} for an entry no run drives
     */
    public record Built(Widget root, Runnable afterFirstFrame, Widget focus) {

        /** A static entry: nothing to open. */
        public Built(Widget root) {
            this(root, () -> { });
        }

        /** An entry that opens something and names no widget for a reader run to focus. */
        public Built(Widget root, Runnable afterFirstFrame) {
            this(root, afterFirstFrame, null);
        }

        /**
         * A static entry a reader run drives.
         *
         * @param root  the scene's root widget
         * @param focus the widget the run puts the keyboard in after the first layout
         * @return the entry built
         */
        public static Built focusing(Widget root, Widget focus) {
            return new Built(root, () -> { }, java.util.Objects.requireNonNull(focus, "focus"));
        }
    }

    /**
     * One thing a person does at the keyboard during a reader run: a key with its modifiers, or a
     * character typed. Sent through the scene's own input path — the path a person's keys take —
     * so every platform hears the same sequence, and a Wayland session, which takes no injected
     * input, hears it too.
     *
     * @param key       the key code from {@link limn.input.Keys}, or {@code -1} for a typed character
     * @param modifiers {@code Keys.MOD_*} bits, where {@link #COMMAND} stands for the platform's
     *                  command modifier and is resolved when the step is sent
     * @param codepoint the character typed, or {@code -1} for a key
     * @param label     what the step does to the widget, as the step line prints it and a guest
     *                  recipe's snapshot label says it
     */
    public record Step(int key, int modifiers, int codepoint, String label) {

        /**
         * The command modifier, whichever the platform's is: {@code Accelerator.commandModifier()}
         * when the step is sent, so a script declares Cmd/Ctrl once and a test on Linux CI and a
         * run on the macOS guest each send their own.
         */
        public static final int COMMAND = 1 << 16;

        /**
         * @param key   the key code
         * @param label what it does
         * @return a key pressed and released with no modifier
         */
        public static Step press(int key, String label) {
            return new Step(key, 0, -1, label);
        }

        /**
         * @param key       the key code
         * @param modifiers {@code Keys.MOD_*} bits, {@link #COMMAND} included
         * @param label     what it does
         * @return a chord pressed and released
         */
        public static Step chord(int key, int modifiers, String label) {
            return new Step(key, modifiers, -1, label);
        }

        /**
         * @param character what is typed
         * @param label     what it does
         * @return a character typed, as an input method commits one
         */
        public static Step type(char character, String label) {
            return new Step(-1, 0, character, label);
        }

        /** @return the keys as a step line names them: {@code SHIFT+TAB}, {@code CMD+UP}, {@code '5'} */
        public String keys() {
            if (codepoint >= 0) {
                return "'" + Character.toString(codepoint) + "'";
            }
            StringBuilder out = new StringBuilder();
            if ((modifiers & COMMAND) != 0) {
                out.append("CMD+");
            }
            if ((modifiers & limn.input.Keys.MOD_CONTROL) != 0) {
                out.append("CTRL+");
            }
            if ((modifiers & limn.input.Keys.MOD_ALT) != 0) {
                out.append("ALT+");
            }
            if ((modifiers & limn.input.Keys.MOD_SHIFT) != 0) {
                out.append("SHIFT+");
            }
            return out.append(keyName(key)).toString();
        }

        /** @return the modifier bits sent, with {@link #COMMAND} resolved for this platform */
        public int resolvedModifiers() {
            int bits = modifiers & ~COMMAND;
            return (modifiers & COMMAND) != 0
                    ? bits | limn.components.Accelerator.commandModifier() : bits;
        }

        /**
         * Sends this step to a scene: the press and the release with the input batch that
         * dispatches them, or the character and its batch. UI thread.
         *
         * @param scene the scene the entry is bound to
         */
        public void sendTo(Scene scene) {
            if (codepoint >= 0) {
                scene.charTyped(codepoint);
            } else {
                scene.keyEvent(key, true, false, resolvedModifiers());
                scene.keyEvent(key, false, false, resolvedModifiers());
            }
            scene.inputBatchEnded();
        }

        private static String keyName(int key) {
            return switch (key) {
                case limn.input.Keys.DOWN -> "DOWN";
                case limn.input.Keys.UP -> "UP";
                case limn.input.Keys.LEFT -> "LEFT";
                case limn.input.Keys.RIGHT -> "RIGHT";
                case limn.input.Keys.HOME -> "HOME";
                case limn.input.Keys.END -> "END";
                case limn.input.Keys.PAGE_UP -> "PAGE_UP";
                case limn.input.Keys.PAGE_DOWN -> "PAGE_DOWN";
                case limn.input.Keys.TAB -> "TAB";
                case limn.input.Keys.ENTER -> "ENTER";
                case limn.input.Keys.ESCAPE -> "ESCAPE";
                case limn.input.Keys.SPACE -> "SPACE";
                case limn.input.Keys.DELETE -> "DELETE";
                case limn.input.Keys.BACKSPACE -> "BACKSPACE";
                case limn.input.Keys.F4 -> "F4";
                default -> key >= limn.input.Keys.A && key <= limn.input.Keys.Z
                        ? Character.toString(key) : "KEY" + key;
            };
        }
    }

    /**
     * What a reader run drives on an entry (decision 24): the short name the driver and the guest
     * recipes use, and the steps in order. The widget the run focuses first is the entry's
     * {@link Built#focus}.
     *
     * @param id    the name {@code --reader} takes, stable for the recipes written against it
     * @param steps the steps, each changing what is published or announcing something
     */
    public record ReaderScript(String id, List<Step> steps) {

        /** @throws IllegalArgumentException for an empty script or a blank id */
        public ReaderScript {
            if (id.isBlank() || steps.isEmpty()) {
                throw new IllegalArgumentException("a reader script has an id and steps: " + id);
            }
            steps = List.copyOf(steps);
        }
    }

    /**
     * One named scene.
     *
     * @param name      what the entry is called, in the picker and in a failure message
     * @param covers    the component classes whose accessibility hooks the scene exercises
     * @param publishes the roles the scene promises to put in the tree — what makes a cover
     *                  claim checkable: an entry that says it shows an open menu and publishes
     *                  no {@code MENU} has shown nothing, and the invariants alone would pass it
     * @param factory   builds the scene afresh each time, so a palette or a locale set before
     *                  the call is what the scene is built under
     * @param reader    what a reader run drives on it, or {@code null} for an entry no run drives
     */
    public record Entry(String name, List<Class<?>> covers, List<Accessible.Role> publishes,
                        Supplier<Built> factory, ReaderScript reader) {

        /** An entry no reader run drives. */
        public Entry(String name, List<Class<?>> covers, List<Accessible.Role> publishes,
                     Supplier<Built> factory) {
            this(name, covers, publishes, factory, null);
        }

        /** @return a fresh build of this entry */
        public Built build() {
            return factory.get();
        }
    }

    /** The picker's width in the runnable window, in points. */
    private static final float PICKER_WIDTH = 240;

    /** The runnable window, in logical points. */
    private static final int WIDTH = 960;
    private static final int HEIGHT = 640;

    /**
     * A transparent square, for every control that wants an icon: what the icon looks like is
     * not an accessible fact, and a real glyph would drag a rasteriser into a headless test.
     */
    private static final Icon BLANK_ICON = (pixels, dark) ->
            new Image(pixels, pixels, new byte[pixels * pixels * 4]);

    /** A transparent picture, for the same reason. */
    private static final Image BLANK_PICTURE = new Image(16, 16, new byte[16 * 16 * 4]);

    /**
     * The language every date entry is built in (settled reader-scene-clock; LAB-NEW-13), as its
     * today is {@link limn.demo.DocumentationDay}'s: a cell's name, a segment's name and the
     * week's first day are the locale's, and a guest's process locale is not the
     * host's. English (United States), because the captions these entries carry are English.
     */
    static final java.util.Locale READER_LOCALE = java.util.Locale.US;

    private AccessibilityGallery() {
    }

    /** @return every entry, in the order the picker lists them */
    public static List<Entry> entries() {
        return List.of(
                new Entry("Labels and headings", List.of(Label.class),
                        List.of(Role.LABEL, Role.HEADING),
                        AccessibilityGallery::labels),
                new Entry("Buttons", List.of(Button.class),
                        List.of(Role.BUTTON),
                        AccessibilityGallery::buttons),
                new Entry("Check boxes and switches", List.of(Checkbox.class),
                        List.of(Role.CHECK_BOX, Role.SWITCH),
                        AccessibilityGallery::checkboxes),
                new Entry("Radio buttons in a group", List.of(RadioButton.class),
                        List.of(Role.RADIO_GROUP, Role.RADIO_BUTTON),
                        AccessibilityGallery::radios),
                new Entry("Segmented control", List.of(SegmentedControl.class),
                        List.of(Role.RADIO_GROUP, Role.RADIO_BUTTON),
                        AccessibilityGallery::segmented),
                new Entry("Sliders", List.of(Slider.class),
                        List.of(Role.SLIDER),
                        AccessibilityGallery::sliders),
                new Entry("Spinners", List.of(Spinner.class),
                        List.of(Role.SPIN_BUTTON),
                        AccessibilityGallery::spinners),
                new Entry("Progress bars", List.of(ProgressBar.class),
                        List.of(Role.PROGRESS_BAR),
                        AccessibilityGallery::progress),
                new Entry("Text field with a placeholder", List.of(TextField.class),
                        List.of(Role.TEXT_FIELD),
                        AccessibilityGallery::textFieldWithPlaceholder),
                new Entry("Text field with a value", List.of(TextField.class),
                        List.of(Role.TEXT_FIELD, Role.BUTTON),
                        AccessibilityGallery::textFieldWithValue),
                new Entry("Password field", List.of(PasswordField.class),
                        List.of(Role.PASSWORD_FIELD, Role.SWITCH),
                        AccessibilityGallery::passwordField),
                new Entry("Search field", List.of(SearchField.class),
                        List.of(Role.SEARCH_FIELD),
                        AccessibilityGallery::searchField),
                new Entry("Text area", List.of(TextArea.class, ScrollBar.class),
                        List.of(Role.TEXT_AREA, Role.SCROLL_BAR),
                        AccessibilityGallery::textArea),
                new Entry("Combo box, closed", List.of(ComboBox.class),
                        List.of(Role.COMBO_BOX),
                        () -> comboBox(false)),
                new Entry("Combo box, open", List.of(ComboBox.class),
                        List.of(Role.COMBO_BOX, Role.LIST, Role.LIST_ITEM),
                        () -> comboBox(true)),
                new Entry("List view with rows", List.of(ListView.class, ScrollBar.class),
                        List.of(Role.LIST, Role.LIST_ITEM, Role.SCROLL_BAR),
                        AccessibilityGallery::listView),
                new Entry("Table with a header and rows", List.of(Table.class),
                        List.of(Role.TABLE, Role.COLUMN_HEADER, Role.ROW, Role.CELL,
                                Role.SWITCH),
                        AccessibilityGallery::table, ReaderScripts.TABLE),
                // TREE and TREE_ITEM since the AT-SPI numbers came off the Fedora guest on
                // 2026-09-13 (ADR 044 §4). The static outline; the entry after it is the one a
                // reader run drives.
                new Entry("Tree, one branch open", List.of(limn.components.tree.Tree.class),
                        List.of(Role.TREE, Role.TREE_ITEM),
                        AccessibilityGallery::tree),
                new Entry("Tree with branches that load", List.of(limn.components.tree.Tree.class),
                        List.of(Role.TREE, Role.TREE_ITEM, Role.BUTTON),
                        AccessibilityGallery::treeThatLoads, ReaderScripts.TREE_LOADING),
                new Entry("Calendar grid", List.of(CalendarView.class),
                        List.of(Role.TABLE, Role.COLUMN_HEADER, Role.ROW, Role.CELL, Role.BUTTON),
                        AccessibilityGallery::calendar, ReaderScripts.CALENDAR),
                new Entry("Date field, segmented", List.of(DateField.class),
                        List.of(Role.GROUP, Role.SPIN_BUTTON),
                        AccessibilityGallery::dateField, ReaderScripts.DATE_FIELD),
                new Entry("Date picker, open", List.of(DatePicker.class),
                        List.of(Role.GROUP, Role.SPIN_BUTTON, Role.BUTTON, Role.TABLE, Role.CELL),
                        AccessibilityGallery::datePicker),
                // Closed, so the field a reader arrives at is checked as it stands in a form:
                // named by the caption bound to the picker, carrying the popup state and the
                // verb that opens it (decisions 18 and 55, 2026-09-14; DATES-NEW-12). The open
                // entry above never checked it, because everything under its overlay is not
                // published focusable.
                new Entry("Date picker, closed", List.of(DatePicker.class),
                        List.of(Role.GROUP, Role.SPIN_BUTTON, Role.BUTTON),
                        AccessibilityGallery::datePickerClosed, ReaderScripts.DATE_PICKER),
                new Entry("Tabbed pane", List.of(TabbedPane.class),
                        List.of(Role.TAB_LIST, Role.TAB, Role.TAB_PANEL),
                        AccessibilityGallery::tabbedPane),
                new Entry("Menu bar", List.of(MenuBar.class),
                        List.of(Role.MENU_BAR, Role.MENU_ITEM),
                        AccessibilityGallery::menuBar),
                new Entry("Context menu region", List.of(ContextMenus.class),
                        List.of(Role.GROUP),
                        AccessibilityGallery::contextRegion),
                new Entry("Popup menu, open", List.of(PopupMenu.class),
                        List.of(Role.MENU, Role.MENU_ITEM),
                        AccessibilityGallery::popupMenu),
                new Entry("Dialog, in its own window", List.of(Dialog.class),
                        List.of(Role.DIALOG),
                        () -> dialog(false)),
                new Entry("Dialog, in the scene", List.of(Dialog.class),
                        List.of(Role.DIALOG),
                        () -> dialog(true)),
                new Entry("Scroll view with offscreen content",
                        List.of(ScrollView.class, ScrollBar.class),
                        List.of(Role.SCROLL_PANE, Role.SCROLL_BAR),
                        AccessibilityGallery::scrollView),
                new Entry("Split pane", List.of(SplitPane.class),
                        List.of(Role.SPLIT_PANE, Role.SPLITTER),
                        AccessibilityGallery::splitPane),
                new Entry("Separators", List.of(Separator.class),
                        List.of(Role.SEPARATOR),
                        AccessibilityGallery::separators),
                new Entry("Tool bar", List.of(ToolBar.class),
                        List.of(Role.TOOL_BAR, Role.BUTTON, Role.SEPARATOR),
                        AccessibilityGallery::toolBar),
                new Entry("Image with a description", List.of(ImageView.class),
                        List.of(Role.IMAGE),
                        AccessibilityGallery::images),
                new Entry("Video with its controls", List.of(VideoView.class, MediaControls.class),
                        List.of(Role.VIDEO, Role.TOOL_BAR),
                        AccessibilityGallery::video),
                new Entry("3D viewport", List.of(Viewport3D.class),
                        List.of(Role.CANVAS),
                        AccessibilityGallery::viewport),
                new Entry("Colour picker", List.of(ColorPicker.class),
                        List.of(Role.COLOR_CHOOSER, Role.SLIDER, Role.TAB_LIST),
                        AccessibilityGallery::colorPicker),
                new Entry("Colour picker button, closed", List.of(ColorPickerButton.class),
                        List.of(Role.BUTTON),
                        () -> colorPickerButton(false)),
                new Entry("Colour picker button, open",
                        List.of(ColorPickerButton.class, ColorPicker.class, Dialog.class),
                        List.of(Role.BUTTON, Role.DIALOG, Role.COLOR_CHOOSER),
                        () -> colorPickerButton(true)),
                new Entry("Charts", List.of(BarChart.class, LineChart.class, DonutChart.class),
                        List.of(Role.CHART, Role.CHART_SERIES),
                        AccessibilityGallery::charts));
    }

    /** @return every entry a reader run drives, in the order {@link #entries()} lists them */
    public static List<Entry> readerEntries() {
        return entries().stream().filter(entry -> entry.reader() != null).toList();
    }

    /**
     * @param id a reader script's id, as {@code --reader} takes it
     * @return the entry that script drives
     * @throws IllegalArgumentException when no entry has a script of that id
     */
    public static Entry readerEntry(String id) {
        for (Entry entry : readerEntries()) {
            if (entry.reader().id().equals(id)) {
                return entry;
            }
        }
        throw new IllegalArgumentException("no reader script named \"" + id + "\"; the scripts are "
                + readerEntries().stream().map(entry -> entry.reader().id()).toList());
    }

    /**
     * Presents every surface under {@code root} that can float above the page — a combo's list,
     * a date picker's calendar, a menu bar's cascade, a colour button's dialog — in {@code mode}:
     * what a reader run's {@code --presentation} asks, and what the verb ratchet's second run
     * does. Set before the scene is bound, so whatever opens later opens there.
     *
     * @param root the entry's root
     * @param mode where those surfaces open
     */
    public static void present(Widget root, DisplayMode mode) {
        if (root instanceof ComboBox combo) {
            combo.setDisplayMode(mode);
        } else if (root instanceof DatePicker picker) {
            picker.setDisplayMode(mode);
        } else if (root instanceof MenuBar bar) {
            bar.setDisplayMode(mode);
        } else if (root instanceof ColorPickerButton button) {
            button.setPickerDisplayMode(mode);
        }
        for (Widget child : root.children()) {
            present(child, mode);
        }
    }

    /**
     * @param name an entry's name
     * @return the entry
     * @throws IllegalArgumentException when no entry has that name
     */
    public static Entry entry(String name) {
        for (Entry entry : entries()) {
            if (entry.name().equals(name)) {
                return entry;
            }
        }
        throw new IllegalArgumentException("no gallery entry named \"" + name + "\"");
    }

    // ------------------------------------------------------------------------------ the entries

    private static Built labels() {
        Column page = page();
        page.add(new Label("Account").setRole(Label.Role.TITLE));
        page.add(new Label("A heading is a label with the title role; a reader offers it as a "
                + "landmark to jump to.").setWrap(true));
        page.add(new Label("Secondary text").setMuted(true));
        return new Built(page);
    }

    private static Built buttons() {
        Column page = page();
        page.add(new Button("Save"));
        page.add(new Button("Discard").setSecondary(true));
        // Icon only: the tooltip is what a reader hears, so an icon-only button always has one.
        Button iconOnly = new Button("").setIcon(BLANK_ICON);
        iconOnly.setTooltip("Refresh");
        page.add(iconOnly);
        Button disabled = new Button("Publish");
        disabled.setEnabled(false);
        page.add(disabled);
        return new Built(page);
    }

    private static Built checkboxes() {
        Column page = page();
        page.add(new Checkbox(Checkbox.Variant.BOX, "Send me a copy"));
        page.add(new Checkbox(Checkbox.Variant.BOX, "Remember this device").setChecked(true));
        page.add(new Checkbox(Checkbox.Variant.SWITCH, "Notifications"));
        page.add(new Checkbox(Checkbox.Variant.SWITCH, "Dark mode").setChecked(true));
        Checkbox locked = new Checkbox(Checkbox.Variant.BOX, "Managed by your organisation")
                .setChecked(true);
        locked.setEnabled(false);
        page.add(locked);
        return new Built(page);
    }

    private static Built radios() {
        Column page = page();
        RadioButton small = new RadioButton("Small");
        RadioButton medium = new RadioButton("Medium");
        RadioButton large = new RadioButton("Large");
        new ButtonGroup().add(small).add(medium).add(large).setSelectedIndex(1);
        // The column holding the members is what the caption names, and a named column is a
        // node; saying what kind is what makes a reader announce "Size, radio group".
        Column group = new Column();
        group.gap(6);
        group.add(small);
        group.add(medium);
        group.add(large);
        group.setAccessibleRole(Role.RADIO_GROUP);
        page.add(Labelled.above("Size", group));
        return new Built(page);
    }

    private static Built segmented() {
        Column page = page();
        page.add(Labelled.above("Period",
                new SegmentedControl(List.of("Day", "Week", "Month")).setSelectedIndex(0)));
        // Narrow enough to overflow, so the two chevrons and a scrolled-away segment are on show.
        // The slot sits in a row of its own, because the page column would stretch it to the
        // page's width; and the caption is bound to the control, not to the box around it.
        SegmentedControl wide = new SegmentedControl(List.of(
                "January", "February", "March", "April", "May", "June"));
        Row slot = new Row();
        slot.add(new SizedBox(160, SizedBox.UNSET, wide));
        page.add(Labelled.above("Month, overflowing its slot", wide, slot));
        return new Built(page);
    }

    private static Built sliders() {
        Column page = page();
        page.add(Labelled.above("Volume", new Slider(0, 100).setValue(30)));
        page.add(Labelled.above("Brightness, in steps", new Slider(0, 10).setStep(1).setValue(5)));
        Slider locked = new Slider(0, 100).setValue(40);
        locked.setEnabled(false);
        page.add(Labelled.above("Contrast, locked", locked));
        return new Built(page);
    }

    private static Built spinners() {
        Column page = page();
        page.add(Labelled.above("Quantity", new Spinner(0, 99, 1).setValue(1)));
        page.add(Labelled.above("Opacity", new Spinner(0, 1, 0.25).setValue(0.5)));
        page.add(Labelled.above("Departure", Spinner.time().setValue(7 * 60 + 30)));
        return new Built(page);
    }

    private static Built progress() {
        Column page = page();
        page.add(Labelled.above("Upload", new ProgressBar().setProgress(0.4f)));
        page.add(Labelled.above("Connecting", new ProgressBar().setIndeterminate(true)));
        return new Built(page);
    }

    private static Built textFieldWithPlaceholder() {
        Column page = page();
        TextField name = new TextField().setPlaceholder("First and last name");
        page.add(Labelled.above("Name", name));
        return new Built(page);
    }

    private static Built textFieldWithValue() {
        Column page = page();
        TextField email = new TextField().setText("ada@example.com");
        page.add(Labelled.above("Email", email));
        // A trailing button is an operable control, so it is given a name of its own.
        TextField city = new TextField().setText("Lisbon");
        city.setTrailingButton(BLANK_ICON, I18nString.literal("Clear city"), () -> city.setText(""));
        page.add(Labelled.above("City", city));
        TextField code = new TextField().setText("12345").setValidation(TextField.Validation.ERROR);
        page.add(Labelled.above("Postal code", code));
        return new Built(page);
    }

    private static Built passwordField() {
        Column page = page();
        PasswordField password = new PasswordField();
        password.setText("correct horse");
        Checkbox reveal = new Checkbox(Checkbox.Variant.SWITCH, "Show password");
        reveal.onChange(password::setRevealed);
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(Expanded.of(password, 1));
        row.add(reveal);
        page.add(Labelled.above("Password", password, row));
        return new Built(page);
    }

    private static Built searchField() {
        Column page = page();
        SearchField search = new SearchField();
        search.setText("invoices");
        page.add(Labelled.above("Search", search));
        return new Built(page);
    }

    private static Built textArea() {
        Column page = page();
        TextArea notes = new TextArea();
        notes.setText("""
                Line one of the notes.
                Line two, which is long enough that it runs past the right edge of the field \
                and gives the horizontal bar something to do.
                Line three.
                Line four.
                Line five.
                Line six.
                Line seven.
                Line eight.""");
        page.add(Labelled.above("Notes", notes, new SizedBox(SizedBox.UNSET, 120, notes)));
        return new Built(page);
    }

    private static Built comboBox(boolean open) {
        Column page = page();
        ComboBox combo = new ComboBox(List.of("Portuguese", "English", "French", "German"));
        combo.setSelectedIndex(1);
        page.add(Labelled.above("Language", combo));
        return new Built(page, open ? combo::open : () -> { });
    }

    private static Built listView() {
        Column page = page();
        ListView list = new ListView(new Rows(
                "Alps", "Andes", "Atlas", "Carpathians", "Caucasus", "Himalayas", "Pyrenees",
                "Rockies", "Urals", "Zagros"));
        list.setSelectedIndex(2);
        page.add(Labelled.above("Mountain ranges", list, new SizedBox(SizedBox.UNSET, 160, list)));
        return new Built(page);
    }

    /**
     * A tree with one branch open, a closed branch beside it and a leaf: the three states a row
     * can be in, which is what a reader has to be able to tell apart.
     */
    private static Built tree() {
        Column page = page();
        record Node(String name, List<Node> kids) {
        }
        List<Node> roots = List.of(
                new Node("Europe", List.of(new Node("Alps", List.of()),
                        new Node("Pyrenees", List.of()))),
                new Node("Asia", List.of(new Node("Himalayas", List.of()))),
                new Node("Africa", List.of()));
        limn.components.tree.Tree<Node> tree = new limn.components.tree.Tree<>(
                new limn.components.tree.Tree.Model<Node>() {
                    @Override
                    public List<Node> roots() {
                        return roots;
                    }

                    @Override
                    public List<Node> children(Node node) {
                        return node.kids();
                    }

                    @Override
                    public limn.scene.Widget cellFor(Node node) {
                        return new limn.components.Label(node.name());
                    }
                });
        tree.expand(roots.get(0));
        tree.setSelected(roots.get(0).kids().get(0));
        page.add(Labelled.above("Mountains by region", tree,
                new SizedBox(SizedBox.UNSET, 180, tree)));
        return new Built(page);
    }

    /**
     * A folder tree the way a file browser holds one, for the reader run that replaced
     * {@code --scene tree-reader}'s scene (decision 24): two folders open, a closed one with
     * children, a folder whose children have to be fetched ("Remote"), one whose fetch finds nothing
     * ("Trash", decision 45's "Empty" line) and one that is empty from the start ("Empty folder").
     * The first rows are the ones that scene had, in its order, so the step numbers the 2026-09-13
     * guest recipes wait on still land where their labels say. A row's cell is an application's
     * composite — a label with an icon, a count against the trailing edge, and an "Open" button on
     * a document — because a row that names itself from such a cell is what the Fedora baseline
     * found silent (TREE-ROW-NAME). A fetch takes {@code limn.demo.treeLoadMillis} (600 ms unless
     * set), long enough for the busy row to be announced before the next step.
     */
    private static Built treeThatLoads() {
        Column page = page();
        record File(String name, List<File> kids) {
            static File leaf(String name) {
                return new File(name, List.of());
            }
        }
        File documents = new File("Documents", List.of(
                new File("Reports", List.of(
                        File.leaf("Q3 regional revenue and headcount, consolidated (final).pdf"),
                        File.leaf("2026.pdf"))),
                File.leaf("meeting notes from the Tuesday planning session.md")));
        File media = new File("Media", List.of(File.leaf("clip.mp4"),
                File.leaf("cover artwork, 4000 by 4000, before the crop.png")));
        File remote = new File("Remote", List.of());
        File trash = new File("Trash", List.of());
        File empty = new File("Empty folder", List.of());
        java.util.Map<File, List<File>> fetched = java.util.Map.of(
                remote, List.of(File.leaf("index.json"), File.leaf("manifest.json"),
                        new File("thumbnails", List.of(File.leaf("01.png"), File.leaf("02.png")))),
                trash, List.of());
        List<File> roots = List.of(documents, media, remote, trash, empty);
        limn.components.tree.Tree<File> tree = new limn.components.tree.Tree<>(
                new limn.components.tree.Tree.Model<File>() {
                    @Override
                    public List<File> roots() {
                        return roots;
                    }

                    @Override
                    public List<File> children(File file) {
                        // A folder nobody has read answers null, which keeps its triangle.
                        return fetched.containsKey(file) ? null : file.kids();
                    }

                    @Override
                    public boolean isLeaf(File file) {
                        return file != empty && limn.components.tree.Tree.Model.super.isLeaf(file);
                    }

                    @Override
                    public limn.concurrent.Work<List<File>> load(File file) {
                        List<File> found = fetched.getOrDefault(file, List.of());
                        return Ui.work(progress -> {
                            try {
                                Thread.sleep(Long.getLong("limn.demo.treeLoadMillis", 600));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return found;
                        });
                    }

                    @Override
                    public Widget cellFor(File file) {
                        Label text = new Label(file.name()).setIcon(BLANK_ICON);
                        Row row = new Row();
                        row.gap(8).crossAlignment(Flex.CrossAlignment.CENTER);
                        row.add(Expanded.of(text));
                        if (!file.kids().isEmpty()) {
                            row.add(new Label(String.valueOf(file.kids().size())).setMuted(true));
                        } else if (file.name().endsWith(".pdf")) {
                            row.add(new Button("Open").setSecondary(true));
                        }
                        return row;
                    }
                });
        tree.setSelectionMode(limn.components.tree.Tree.SelectionMode.MULTI);
        tree.expand(documents);
        tree.expand(documents.kids().get(0));
        page.add(Labelled.above("Files", tree, new SizedBox(SizedBox.UNSET, 320, tree)));
        return Built.focusing(page, tree);
    }

    /**
     * A table as an application uses one: several rows selected in {@code MULTI}, a footer
     * summarising two columns, and a widget column whose switches are named — "Visited" is
     * what a reader hears for the control, and it says which column it stands in without the
     * header (B5 of the 2026-09-13 pass, settled as table-golden-scene; its transcript is the
     * committed golden {@code table.txt}).
     */
    private static Built table() {
        Column page = page();
        record Range(String name, String continent, int summit, boolean visited) {
        }
        Table<Range> table = new Table<>(List.of(
                limn.components.table.Column.text("Range", Range::name).width(120).weight(1)
                        .footerCount(),
                limn.components.table.Column.text("Continent", Range::continent).width(150),
                limn.components.table.Column.numeric("Summit", Range::summit).width(100)
                        .footerMax(),
                limn.components.table.Column.<Range>widget("Visited", range ->
                        new Checkbox(Checkbox.Variant.SWITCH, "Visited")
                                .setChecked(range.visited())).width(140).sortable(false)));
        table.setRows(List.of(
                new Range("Alps", "Europe", 4808, true),
                new Range("Andes", "South America", 6961, false),
                new Range("Atlas", "Africa", 4167, false),
                new Range("Carpathians", "Europe", 2655, true),
                new Range("Caucasus", "Europe", 5642, false),
                new Range("Himalayas", "Asia", 8849, false),
                new Range("Pyrenees", "Europe", 3404, true),
                new Range("Rockies", "North America", 4401, false),
                new Range("Urals", "Europe", 1895, false),
                new Range("Zagros", "Asia", 4409, false)));
        table.setSelectionMode(Table.SelectionMode.MULTI);
        table.setSelectedRows(0, 2, 3); // the lead in view, so nothing scrolls before it is read
        page.add(Labelled.above("Mountain ranges", table,
                new SizedBox(SizedBox.UNSET, 240, table)));
        return Built.focusing(page, table);
    }

    /**
     * The month grid, wearing what a form asks of it: a bound, a filter and a mark, so a reader can
     * be checked against a day that is refused as well as against one that is not.
     */
    private static Built calendar() {
        Column page = page();
        CalendarView calendar = new CalendarView();
        calendar.setVisibleMonth(java.time.LocalDate.of(2026, 9, 9));
        calendar.setSelectedDate(java.time.LocalDate.of(2026, 9, 15));
        calendar.setShowWeekNumbers(true);
        calendar.setMinDate(java.time.LocalDate.of(2026, 9, 2));
        calendar.setDateFilter(day -> day.getDayOfWeek() != java.time.DayOfWeek.SUNDAY);
        calendar.setDayMarks(day -> day.getDayOfMonth() == 21
                ? DayMark.of(Theme.current().danger, I18nString.literal("holiday"))
                : null);
        page.add(Labelled.above("Delivery date", calendar));
        return Built.focusing(pinnedForReaders(page), calendar);
    }

    /**
     * Three fields: one that is only a date, one that carries a clock as well, and one left empty,
     * so a reader is checked against the date's segments, the clock's after them, and a segment
     * that holds no number (decisions 16 and 53: its value is a word).
     */
    private static Built dateField() {
        Column page = page();
        DateField date = new DateField();
        date.setDate(java.time.LocalDate.of(2026, 9, 9));
        page.add(Labelled.above("Invoice date", date));
        DateField moment = new DateField().setGranularity(DateField.Granularity.MINUTE);
        moment.setDateTime(java.time.LocalDateTime.of(2026, 9, 9, 14, 30));
        page.add(Labelled.above("Appointment", moment));
        page.add(Labelled.above("Due date", new DateField()));
        return Built.focusing(pinnedForReaders(page), date);
    }

    /** The picker with its calendar open, in the scene so the whole tree is in one window. */
    private static Built datePicker() {
        Column page = page();
        DatePicker picker = new DatePicker();
        picker.setDate(java.time.LocalDate.of(2026, 9, 9));
        picker.setDisplayMode(limn.components.DisplayMode.IN_SCENE);
        page.add(Labelled.above("Start date", picker));
        // Opened after the first layout, for the reason every open-popup entry here is: the overlay
        // hangs from the picker's place in the scene, and a picker that has not been laid out has
        // none yet.
        return new Built(pinnedForReaders(page), picker::open);
    }

    /**
     * A single picker and a period, both closed and both captioned: the caption names the single
     * picker's field, and the period's group with its two ends named for themselves.
     */
    private static Built datePickerClosed() {
        Column page = page();
        DatePicker picker = new DatePicker();
        picker.setDate(java.time.LocalDate.of(2026, 9, 9));
        page.add(Labelled.above("Delivery date", picker));
        DatePicker stay = DatePicker.ofRange();
        stay.setRange(new limn.components.date.DateRange(
                java.time.LocalDate.of(2026, 9, 14), java.time.LocalDate.of(2026, 9, 25)));
        page.add(Labelled.above("Stay", stay));
        return Built.focusing(pinnedForReaders(page), picker.field());
    }

    /**
     * Pins {@link limn.demo.DocumentationDay} on every date widget under {@code root} (a calendar
     * names its today cell ", today" and a field steps an empty segment from today, so an entry on
     * the real clock spoke differently on each guest and each day) and declares
     * {@link #READER_LOCALE} on {@code root} itself, which every descendant inherits. In the
     * entries rather than in whatever runs them, so the gallery window a reader is pointed at,
     * the headless tests and a driver all build the same tree; these scenes are not published as
     * samples, so the pinned clock is copied into nobody's application.
     */
    private static Widget pinnedForReaders(Widget root) {
        root.setLocale(READER_LOCALE);
        limn.demo.DocumentationDay.pin(root);
        return root;
    }

    private static Built tabbedPane() {
        Column page = page();
        TabbedPane tabs = new TabbedPane();
        Column general = new Column();
        general.gap(8);
        general.add(new Checkbox(Checkbox.Variant.SWITCH, "Open at login"));
        tabs.addTab("General", general);
        Column privacy = new Column();
        privacy.gap(8);
        privacy.add(new Checkbox(Checkbox.Variant.BOX, "Share usage statistics"));
        tabs.addTab("Privacy", privacy);
        tabs.addTab("About", new Label("Version 1.0").setWrap(true));
        page.add(new SizedBox(SizedBox.UNSET, 200, tabs));
        return new Built(page);
    }

    private static Built menuBar() {
        Column page = page();
        Menu file = new Menu();
        file.addItem("New", () -> { });
        file.addItem("Open…", () -> { });
        file.addSeparator();
        file.addItem("Quit", () -> { });
        Menu view = new Menu();
        view.addCheck("Status bar", true, shown -> { });
        MenuBar bar = new MenuBar();
        bar.addMenu("File", 'F', file);
        bar.addMenu("View", 'V', view);
        page.add(bar);
        page.add(new Label("Alt or F10 moves to the menu bar.").setMuted(true));
        return new Built(page);
    }

    private static Built contextRegion() {
        Column page = page();
        Widget region = ContextMenus.attach(
                new Label("Right-click, or press the menu key, for options").setWrap(true),
                AccessibilityGallery::editingMenu);
        region.setAccessibleName("Draft");
        page.add(region);
        return new Built(page);
    }

    private static Built popupMenu() {
        Column page = page();
        Button anchor = new Button("Options");
        page.add(anchor);
        return new Built(page, () -> new PopupMenu(editingMenu()).showAnchored(anchor,
                anchor.localToSceneX(), anchor.localToSceneY(), anchor.width(), anchor.height()));
    }

    private static Menu editingMenu() {
        Menu menu = new Menu();
        menu.addItem("Cut", () -> { });
        menu.addItem("Copy", () -> { });
        menu.addItem("Paste", () -> { });
        menu.addSeparator();
        Menu transform = new Menu();
        transform.addItem("Upper case", () -> { });
        transform.addItem("Lower case", () -> { });
        menu.addSubmenu("Transform", transform);
        return menu;
    }

    private static Built dialog(boolean inScene) {
        Column page = page();
        Button opener = new Button("Discard draft…");
        page.add(opener);
        return new Built(page, () -> {
            Dialog dialog = new Dialog("Discard the draft?",
                    "The text you typed will be lost. This cannot be undone.")
                    .addButton("Keep", "keep")
                    .addPrimaryButton("Discard", "discard")
                    .setCancelResult("keep");
            if (inScene) {
                dialog.setDisplayMode(DisplayMode.IN_SCENE);
            }
            dialog.show(opener);
        });
    }

    private static Built scrollView() {
        Column page = page();
        Column tall = new Column();
        tall.gap(8).crossAlignment(Flex.CrossAlignment.STRETCH);
        for (int i = 1; i <= 24; i++) {
            tall.add(new Button("Chapter " + i));
        }
        ScrollView scroll = new ScrollView(tall);
        scroll.setAccessibleName("Chapters");
        page.add(new SizedBox(SizedBox.UNSET, 180, scroll));
        return new Built(page);
    }

    private static Built splitPane() {
        Column page = page();
        Column left = new Column();
        left.gap(8);
        left.add(new Label("Folders").setRole(Label.Role.TITLE));
        left.add(new Button("Inbox"));
        Column right = new Column();
        right.gap(8);
        right.add(new Label("Messages").setRole(Label.Role.TITLE));
        right.add(new Button("Reply"));
        SplitPane split = SplitPane.horizontal(pad(left), pad(right)).setRatio(0.35f)
                .setDividerFocusable(true);
        page.add(new SizedBox(SizedBox.UNSET, 200, split));
        return new Built(page);
    }

    private static Built separators() {
        Column page = page();
        page.add(new Label("Above the rule"));
        page.add(Separator.horizontal());
        page.add(new Label("Below the rule"));
        Row row = new Row();
        row.gap(12);
        row.add(new Label("Left"));
        row.add(Separator.vertical());
        row.add(new Label("Right"));
        page.add(new SizedBox(SizedBox.UNSET, 32, row));
        return new Built(page);
    }

    private static Built toolBar() {
        Column page = page();
        ToolBar bar = new ToolBar();
        bar.addItem(iconButton("Bold"));
        bar.addItem(iconButton("Italic"));
        bar.addSeparator();
        bar.addItem(iconButton("Insert link"));
        page.add(bar);
        return new Built(page);
    }

    private static Built images() {
        Column page = page();
        // #region guide:a11y-image
        ImageView logo = new ImageView(BLANK_PICTURE).setFit(ImageView.Fit.CONTAIN)
                .setPreferredSize(96, 64);
        logo.setAccessibleName(new I18nString("about.logo", "Limn logo"));
        logo.setAccessibleDescription(new I18nString("about.logoDescription",
                "A monogram on a rounded square"));
        page.add(logo);
        Label caption = new Label("Illustration repeated by the caption beside it");
        ImageView decorative = new ImageView(BLANK_PICTURE).setPreferredSize(48, 48);
        // A picture the text beside it already describes is decoration, and is left out.
        decorative.setAccessibleIgnored(true);
        // #endregion
        Row row = new Row();
        row.gap(12).crossAlignment(Flex.CrossAlignment.CENTER);
        row.add(decorative);
        row.add(caption);
        page.add(row);
        return new Built(page);
    }

    private static Built video() {
        Column page = page();
        VideoView video = new VideoView().setPreferredSize(320, 180).setControlsVisible(true);
        video.setAccessibleName("Sample clip");
        page.add(video);
        return new Built(page);
    }

    private static Built viewport() {
        Column page = page();
        Viewport3D viewport = new Viewport3D().setPreferredSize(320, 200);
        viewport.setAccessibleName("Model preview");
        viewport.setAccessibleDescription("A cube; drag to orbit, scroll to zoom");
        page.add(viewport);
        return new Built(page);
    }

    private static Built colorPicker() {
        Column page = page();
        ColorPicker picker = new ColorPicker().setColor(Color.rgb(0x22C55E));
        picker.setAccessibleName("Accent colour");
        page.add(picker);
        return new Built(page);
    }

    private static Built colorPickerButton(boolean open) {
        Column page = page();
        ColorPickerButton button = new ColorPickerButton(Color.rgb(0xF59E0B));
        button.setText("Highlight");
        button.setDialogTitle(I18nString.literal("Highlight colour"));
        page.add(button);
        return new Built(page, open ? button::openPicker : () -> { });
    }

    private static Built charts() {
        Column page = page();
        BarChart bars = new BarChart();
        bars.setTitle("Revenue by quarter");
        bars.setLabels("Q1", "Q2", "Q3", "Q4");
        bars.addSeries(ChartSeries.of("Direct", 120, 145, 132, 168));
        bars.addSeries(ChartSeries.of("Partner", 80, 92, 105, 99));
        page.add(new SizedBox(SizedBox.UNSET, 160, bars));
        LineChart lines = new LineChart();
        lines.setTitle("Latency");
        lines.setLabels("00", "06", "12", "18");
        lines.addSeries(ChartSeries.of("p50", 24, 26, 30, 27));
        lines.addSeries(ChartSeries.of("p99", 62, 71, 88, 74));
        page.add(new SizedBox(SizedBox.UNSET, 160, lines));
        DonutChart donut = new DonutChart();
        donut.setTitle("Traffic sources");
        donut.setLabels("Direct", "Search", "Social");
        donut.addSeries(ChartSeries.of("Sessions", 42, 31, 27));
        page.add(new SizedBox(SizedBox.UNSET, 160, donut));
        return new Built(page);
    }

    // ------------------------------------------------------------------------------ the helpers

    /** A page: a padded column that stretches its rows, which is what every entry sits in. */
    private static Column page() {
        Column column = new Column();
        column.gap(12).crossAlignment(Flex.CrossAlignment.STRETCH);
        return column;
    }

    private static Widget pad(Widget content) {
        return new Padding(Insets.all(12), content);
    }


    /** An icon-only button, named by its tooltip, which is what an icon-only button always has. */
    private static Button iconButton(String name) {
        Button button = new Button("").setIcon(BLANK_ICON).setSecondary(true);
        button.setTooltip(name);
        return button;
    }

    /**
     * Rows that paint their own text and declare nothing, which is the flagship list case: the
     * name a reader hears comes from {@link ListView.Adapter#rowName}, handed back by reference.
     */
    private static final class Rows implements ListView.Adapter {
        private final String[] texts;
        private final I18nString[] names;
        private final Cell[] cells;

        Rows(String... texts) {
            this.texts = texts;
            this.names = new I18nString[texts.length];
            for (int i = 0; i < texts.length; i++) {
                this.names[i] = I18nString.literal(texts[i]);
            }
            this.cells = new Cell[texts.length];
        }

        @Override
        public int rowCount() {
            return names.length;
        }

        @Override
        public Widget rowAt(int index) {
            if (cells[index] == null) {
                cells[index] = new Cell(texts[index]);
            }
            return cells[index];
        }

        @Override
        public I18nString rowName(int index) {
            return names[index];
        }
    }

    /** A row that paints a string and says nothing about itself; the list names it. */
    private static final class Cell extends Widget {
        private final String text;

        Cell(String text) {
            this.text = text;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), 28);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            canvas.drawText(text, 12, height() / 2 + 5, theme.body, theme.text);
        }
    }

    // ------------------------------------------------------------------------------ the window

    /**
     * Opens the gallery in a real window: the entries down the left in a list, the chosen one on
     * the right. For pointing a screen reader at one entry on a guest, and nothing more.
     *
     * <p>Run from the demo's runtime classpath — {@code ./gradlew :limn-demo:accessibilityGallery},
     * or {@code java -cp limn-demo-all.jar limn.demo.a11y.AccessibilityGallery} on a machine
     * holding the release jar; on macOS the JVM needs {@code -XstartOnFirstThread}, as the demo
     * does. An entry's name as the one argument opens the window on that entry.
     *
     * @param args optionally the name of the entry to open on
     */
    public static void main(String[] args) {
        List<Entry> entries = entries();
        int initial = 0;
        if (args.length > 0) {
            initial = entries.indexOf(entry(String.join(" ", args)));
        }
        try (Backend backend = new LwjglBackend()) {
            NativeWindow window = backend.createWindow(
                    WindowConfig.of("Limn accessibility gallery", WIDTH, HEIGHT));

            Column holder = new Column();
            holder.crossAlignment(Flex.CrossAlignment.STRETCH);
            ListView picker = new ListView(new Rows(
                    entries.stream().map(Entry::name).toArray(String[]::new)));
            picker.setAccessibleName("Entries");
            Row root = new Row();
            root.crossAlignment(Flex.CrossAlignment.STRETCH);
            root.add(new SizedBox(PICKER_WIDTH, SizedBox.UNSET, picker));
            root.add(Expanded.of(new ScrollView(pad(holder)), 1));

            Scene scene = new Scene(root);
            picker.onSelect(index -> show(holder, entries.get(index)));
            picker.setSelectedIndex(initial);
            scene.bind(window);
            window.show();
            backend.runEventLoop();
        }
    }

    /** Replaces what the holder shows with a fresh build of {@code entry}, and opens it. */
    private static void show(Column holder, Entry entry) {
        for (Widget old : List.copyOf(holder.children())) {
            holder.remove(old);
        }
        Built built = entry.build();
        holder.add(built.root());
        // One frame later, so the entry has a box to anchor a popup or a dialog to.
        Ui.postDelayed(built.afterFirstFrame(), 100);
    }
}
