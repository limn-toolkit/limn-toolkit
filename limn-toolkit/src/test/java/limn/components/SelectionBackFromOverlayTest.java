package limn.components;

import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A text field or area that lost the keyboard to an overlay (a menu, a popover, a dialog in the
 * scene) takes back the selection it had when the keyboard comes back, but only when nothing
 * touched its caret or selection while the overlay was up. A menu item or a panel button that
 * selected everything, or put the caret somewhere, runs before the overlay gives the keyboard
 * back, and the field's record of what it had must not overwrite that: before, Select All from the
 * field's own context menu did nothing, and "Go to start" left a selection from the old anchor to
 * the new caret that the next key typed replaced, deleting text the user never selected.
 *
 * <p>Each case runs with the menus in windows of their own and in the scene; only the scene ever
 * showed it, because a menu in a window of its own leaves the field the keyboard.
 */
class SelectionBackFromOverlayTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    private HeadlessBackend backend;
    private Scene scene;
    private TextField field;
    private TextArea area;
    private DisplayMode menusBefore;

    @BeforeEach
    void rememberMenus() {
        menusBefore = PopupMenu.defaultDisplayMode();
    }

    @AfterEach
    void restoreMenus() {
        PopupMenu.setDefaultDisplayMode(menusBefore);
    }

    private void open(DisplayMode menus) {
        PopupMenu.setDefaultDisplayMode(menus);
        backend = new HeadlessBackend(runtime);
        HeadlessWindow window = backend.open("Document", 600, 400);
        field = new TextField().setText("hello world");
        area = new TextArea().setText("line one\nline two\nline three\nline four");
        Column column = new Column();
        column.add(field);
        column.add(area);
        scene = new Scene(column, clock::get);
        scene.setTextRuler(RULER);
        scene.bind(window);
        window.frame();
    }

    /** Frames every window over most of a second of the clock, so fades in and out finish. */
    private void settle() {
        for (int i = 0; i < 14; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
            runtime.drain();
            for (HeadlessWindow w : backend.windows()) {
                if (w.scene() != null && !w.isClosed()) {
                    w.frame();
                }
            }
        }
        runtime.drain();
    }

    /** Opens the focused widget's own context menu from the keyboard and runs its last row, Select All. */
    private void ownMenuSelectAll() {
        drive(scene).press(Keys.F10, Keys.MOD_SHIFT);
        settle();
        drive(scene).press(Keys.END);
        drive(scene).press(Keys.ENTER);
        settle();
    }

    /** Shows {@code menu} for the focused field and runs its first row from the keyboard. */
    private void runFirstRow(Menu menu, limn.scene.Widget<?> over) {
        ContextMenus.showForFocus(over, menu);
        settle();
        drive(scene).press(Keys.HOME);
        drive(scene).press(Keys.ENTER);
        settle();
    }

    /** Opens the focused widget's own context menu from the keyboard and runs row {@code row} (0 Cut, 1 Copy, 2 Paste). */
    private void ownMenuRow(int row) {
        drive(scene).press(Keys.F10, Keys.MOD_SHIFT);
        settle();
        drive(scene).press(Keys.HOME);
        for (int i = 0; i < row; i++) {
            drive(scene).press(Keys.DOWN);
        }
        drive(scene).press(Keys.ENTER);
        settle();
    }

    private HeadlessWindow window() {
        return backend.windows().get(0);
    }

    /**
     * On blur, writes the trimmed value to a model and lets the model's listener write it back
     * into the widget, as a two-way binding does: a different text, then the same text.
     */
    private static void trimAndEchoOnBlur(limn.scene.Widget<?> widget, java.util.function.Supplier<String> text,
            java.util.function.Consumer<String> set) {
        widget.observeChanges((source, change) -> {
            if (change.aspect() == limn.scene.Change.Aspect.FOCUS && !widget.isFocused()) {
                String model = text.get().strip();
                set.accept(model); // the binding writes the model
                set.accept(model); // and the model's listener echoes it back
            }
        });
    }

    /** Moves the caret to the start whenever {@code field} loses the keyboard, as a binding that shows a value's head does. */
    private static void caretToStartOnBlur(TextField field) {
        field.observeChanges((source, change) -> {
            if (change.aspect() == limn.scene.Change.Aspect.FOCUS && !field.isFocused()) {
                field.setCaretPosition(0);
            }
        });
    }

    // ---------------------------------------------------------------- Cut, Copy and Paste
    //
    // In the scene the menu takes the keyboard and the field lets its selection go with it, so the
    // rows of the field's own menu, built while the selection was there, acted on nothing: Copy
    // left the clipboard as it was, Cut cut nothing, and Paste went in at the caret beside the
    // word the person had selected. A menu in a window of its own leaves the field the keyboard,
    // and the same rows have always worked there; each case below runs in both.

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void copyFromTheFieldsOwnMenuCopiesTheSelectionAndLeavesItSelected(DisplayMode menus) {
        open(menus);
        window().clipboard().setText("CLIP");
        scene.requestFocus(field);
        field.select(6, 11);
        settle();
        ownMenuRow(1);
        assertEquals("world", window().clipboard().text(), menus + ": Copy copied what was selected");
        assertEquals("hello world", field.text(), menus + ": and changed nothing");
        assertSame(field, scene.focusedWidget(), menus + ": the keyboard is back in the field");
        assertEquals(6, field.selectionStart(), menus + ": still selected");
        assertEquals(11, field.selectionEnd(), menus + ": still selected");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void cutFromTheFieldsOwnMenuCutsTheSelection(DisplayMode menus) {
        open(menus);
        window().clipboard().setText("CLIP");
        scene.requestFocus(field);
        field.select(6, 11);
        settle();
        ownMenuRow(0);
        assertEquals("world", window().clipboard().text(), menus + ": Cut put what was selected on the clipboard");
        assertEquals("hello ", field.text(), menus + ": and took it out");
        assertEquals(6, field.caretPosition(), menus + ": the caret where it was");
        assertEquals(6, field.selectionEnd(), menus + ": with nothing selected");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void pasteFromTheFieldsOwnMenuReplacesTheSelection(DisplayMode menus) {
        open(menus);
        window().clipboard().setText("CLIP");
        scene.requestFocus(field);
        field.select(6, 11);
        settle();
        ownMenuRow(2);
        assertEquals("hello CLIP", field.text(), menus + ": Paste replaced what was selected");
        assertEquals(10, field.caretPosition(), menus + ": the caret after what was pasted");
        drive(scene).type("!");
        settle();
        assertEquals("hello CLIP!", field.text(), menus + ": the next key goes after it, nothing deleted");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void cutCopyAndPasteFromTheAreasOwnMenuActOnTheSelection(DisplayMode menus) {
        open(menus);
        HeadlessWindow window = window();
        window.clipboard().setText("CLIP");
        scene.requestFocus(area);
        area.select(5, 8); // "one"
        settle();
        ownMenuRow(1);
        assertEquals("one", window.clipboard().text(), menus + ": Copy copied what was selected");
        assertEquals(5, area.selectionStart(), menus + ": still selected after Copy");
        assertEquals(8, area.selectionEnd(), menus + ": still selected after Copy");

        window.clipboard().setText("CLIP");
        ownMenuRow(2);
        assertEquals("line CLIP\nline two\nline three\nline four", area.text(), menus + ": Paste replaced it");

        area.select(10, 14); // "line" of the second line
        settle();
        ownMenuRow(0);
        assertEquals("line", window.clipboard().text(), menus + ": Cut put what was selected on the clipboard");
        assertEquals("line CLIP\n two\nline three\nline four", area.text(), menus + ": and took it out");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void anApplicationsCutCopyAndPasteItemsActOnTheSelection(DisplayMode menus) {
        open(menus);
        HeadlessWindow window = window();
        window.clipboard().setText("CLIP");
        scene.requestFocus(field);
        field.select(6, 11);
        settle();
        runFirstRow(new Menu().add(MenuItem.of(MenuItem.Role.COPY)), field);
        assertEquals("world", window.clipboard().text(), menus + ": the Copy role copied the selection");
        assertEquals(6, field.selectionStart(), menus + ": still selected after Copy");
        assertEquals(11, field.selectionEnd(), menus + ": still selected after Copy");

        window.clipboard().setText("CLIP");
        runFirstRow(new Menu().add(MenuItem.of(MenuItem.Role.PASTE)), field);
        assertEquals("hello CLIP", field.text(), menus + ": the Paste role replaced the selection");

        scene.requestFocus(area);
        area.select(5, 8);
        settle();
        runFirstRow(new Menu().add(MenuItem.of(MenuItem.Role.CUT)), area);
        assertEquals("one", window.clipboard().text(), menus + ": the Cut role cut the area's selection");
        assertEquals("line \nline two\nline three\nline four", area.text(), menus + ": and took it out");
    }

    /**
     * A listener that moves the caret when the field loses the keyboard (to show the head of a long
     * value) forgets the selection the keyboard would come back to, but not what the person had
     * selected when they opened the menu: Cut, Copy and Paste still act on it.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aCaretMovedOnBlurDoesNotTakeTheSelectionFromCutCopyAndPaste(DisplayMode menus) {
        open(menus);
        HeadlessWindow window = window();
        window.clipboard().setText("CLIP");
        caretToStartOnBlur(field);
        scene.requestFocus(field);
        field.select(6, 11);
        settle();
        runFirstRow(new Menu().add(MenuItem.of(MenuItem.Role.COPY)), field);
        assertEquals("world", window.clipboard().text(), menus + ": the Copy role copied the selection");

        field.select(6, 11);
        settle();
        ownMenuRow(0);
        assertEquals("hello ", field.text(), menus + ": the menu's Cut cut it");

        field.setText("hello world");
        field.select(6, 11);
        window.clipboard().setText("CLIP");
        settle();
        ownMenuRow(2);
        assertEquals("hello CLIP", field.text(), menus + ": the menu's Paste replaced it");
    }

    /**
     * A listener that sets the text the field already holds when it loses the keyboard (a binding
     * that writes its value back on blur) changes no character, so what the person had selected is
     * still there for Cut, Copy and Paste: before, setting the text, even the same text, ended the
     * commands' record, and in the scene they found nothing.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void theSameTextSetOnBlurDoesNotTakeTheSelectionFromCutCopyAndPaste(DisplayMode menus) {
        open(menus);
        HeadlessWindow window = window();
        window.clipboard().setText("CLIP");
        field.observeChanges((source, change) -> {
            if (change.aspect() == limn.scene.Change.Aspect.FOCUS && !field.isFocused()) {
                field.setText(field.text());
            }
        });
        area.observeChanges((source, change) -> {
            if (change.aspect() == limn.scene.Change.Aspect.FOCUS && !area.isFocused()) {
                area.setText(area.text());
            }
        });
        scene.requestFocus(field);
        field.select(6, 11);
        settle();
        ownMenuRow(1);
        assertEquals("world", window.clipboard().text(), menus + ": the menu's Copy copied the selection");

        field.select(6, 11);
        settle();
        runFirstRow(new Menu().add(MenuItem.of(MenuItem.Role.CUT)), field);
        assertEquals("world", window.clipboard().text(), menus + ": the Cut role cut the selection");
        assertEquals("hello ", field.text(), menus + ": and took it out");

        field.setText("hello world");
        field.select(6, 11);
        window.clipboard().setText("CLIP");
        settle();
        ownMenuRow(2);
        assertEquals("hello CLIP", field.text(), menus + ": the menu's Paste replaced it");

        scene.requestFocus(area);
        area.select(5, 8); // "one"
        settle();
        ownMenuRow(0);
        assertEquals("one", window.clipboard().text(), menus + ": the area's Cut cut its selection");
        assertEquals("line \nline two\nline three\nline four", area.text(), menus + ": and took it out");
    }

    /**
     * The other side of the case above: a listener that sets a different text on blur (one that
     * trims the value) changes what the selection's offsets point at, so the commands find nothing
     * to act on rather than a range of the new text the person never selected.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aDifferentTextSetOnBlurLeavesTheCommandsNothingToActOn(DisplayMode menus) {
        open(menus);
        HeadlessWindow window = window();
        window.clipboard().setText("CLIP");
        field.setText("  hello world");
        field.observeChanges((source, change) -> {
            if (change.aspect() == limn.scene.Change.Aspect.FOCUS && !field.isFocused()) {
                field.setText(field.text().strip());
            }
        });
        scene.requestFocus(field);
        field.select(8, 13); // "world"
        settle();
        ownMenuRow(1);
        if (menus == DisplayMode.IN_SCENE) {
            assertEquals("hello world", field.text(), menus + ": the listener trimmed the text as the menu took the keyboard");
            assertEquals("CLIP", window.clipboard().text(), menus + ": nothing of the new text was copied");
        } else {
            // A menu in a window of its own leaves the field the keyboard: no blur, the selection stands.
            assertEquals("world", window.clipboard().text(), menus + ": Copy copied the selection");
        }
    }

    /**
     * A two-way binding: on blur the field writes the trimmed value to the model (a different
     * text, which ends what the commands had), and the model's listener writes that value back
     * into the field (the same text as it now holds). The echo must not bring back offsets that
     * named characters of the text before the trim: before, Copy put "pply C" of "Harbor Supply
     * Co" on the clipboard where the person had selected "Supply", and Cut left "Harbor Suo".
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aTrimOnBlurEchoedBackByTheModelLeavesTheCommandsNothingToActOn(DisplayMode menus) {
        open(menus);
        HeadlessWindow window = window();
        trimAndEchoOnBlur(field, field::text, field::setText);
        trimAndEchoOnBlur(area, area::text, area::setText);
        for (String row : new String[] {"Copy", "Cut"}) {
            for (boolean inArea : new boolean[] {false, true}) {
                String what = menus + ", " + (inArea ? "area" : "field") + ", " + row;
                limn.scene.Widget<?> w = inArea ? area : field;
                if (inArea) {
                    area.setText("  Harbor Supply Co");
                } else {
                    field.setText("  Harbor Supply Co");
                }
                window.clipboard().setText("CLIP");
                scene.requestFocus(w);
                if (inArea) {
                    area.select(9, 15); // "Supply"
                } else {
                    field.select(9, 15);
                }
                settle();
                ownMenuRow(row.equals("Cut") ? 0 : 1);
                String text = inArea ? area.text() : field.text();
                if (menus == DisplayMode.IN_SCENE) {
                    assertEquals("Harbor Supply Co", text, what + ": trimmed as the menu took the keyboard, and nothing cut");
                    assertEquals("CLIP", window.clipboard().text(), what + ": nothing of the new text reached the clipboard");
                } else {
                    // A menu in a window of its own leaves the field the keyboard: no blur, the selection stands.
                    assertEquals("Supply", window.clipboard().text(), what + ": acted on the selection");
                }
            }
        }
    }

    /**
     * The trim on blur, then the same value set again while the menu is open (a refresh from the
     * model): the record the trim ended stays ended, and Copy and Cut find nothing.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void theSameTextSetLaterWhileTheMenuIsOpenDoesNotReviveARecordATrimEnded(DisplayMode menus) {
        open(menus);
        HeadlessWindow window = window();
        field.setText("  Harbor Supply Co");
        field.observeChanges((source, change) -> {
            if (change.aspect() == limn.scene.Change.Aspect.FOCUS && !field.isFocused()) {
                field.setText(field.text().strip());
            }
        });
        for (int row : new int[] {1, 0}) {
            field.setText("  Harbor Supply Co");
            window.clipboard().setText("CLIP");
            scene.requestFocus(field);
            field.select(9, 15); // "Supply"
            settle();
            drive(scene).press(Keys.F10, Keys.MOD_SHIFT);
            settle();
            field.setText(field.text().strip()); // the refresh, after the trim on blur in the scene
            settle();
            drive(scene).press(Keys.HOME);
            for (int i = 0; i < row; i++) {
                drive(scene).press(Keys.DOWN);
            }
            drive(scene).press(Keys.ENTER);
            settle();
            assertEquals("Harbor Supply Co", field.text(), menus + ", row " + row + ": nothing cut");
            assertEquals("CLIP", window.clipboard().text(), menus + ", row " + row + ": nothing copied");
        }
    }

    /**
     * The echo of a value shorter than the offsets the person selected in: Cut and Copy must not
     * be offered for a record that names characters the text no longer has, nor throw from the
     * menu's action (before, both rows were enabled and both threw IndexOutOfBoundsException).
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void anEchoShorterThanTheOldSelectionOffersNoCommandAndNeverThrows(DisplayMode menus) {
        open(menus);
        HeadlessWindow window = window();
        trimAndEchoOnBlur(field, field::text, field::setText);
        trimAndEchoOnBlur(area, area::text, area::setText);
        for (boolean inArea : new boolean[] {false, true}) {
            String what = menus + ", " + (inArea ? "area" : "field");
            limn.scene.Widget<?> w = inArea ? area : field;
            if (inArea) {
                area.setText("            Co");
            } else {
                field.setText("            Co");
            }
            window.clipboard().setText("CLIP");
            scene.requestFocus(w);
            if (inArea) {
                area.select(12, 14); // "Co"
            } else {
                field.select(12, 14);
            }
            settle();
            drive(scene).press(Keys.F10, Keys.MOD_SHIFT);
            settle();
            boolean canCopy = inArea ? area.onCanEdit(limn.scene.EditCommand.COPY) : field.onCanEdit(limn.scene.EditCommand.COPY);
            boolean canCut = inArea ? area.onCanEdit(limn.scene.EditCommand.CUT) : field.onCanEdit(limn.scene.EditCommand.CUT);
            if (menus == DisplayMode.IN_SCENE) {
                assertEquals(false, canCopy, what + ": Copy is not offered for a record of the old text");
                assertEquals(false, canCut, what + ": nor Cut");
                assertDoesNotThrow(() -> {
                    if (inArea) {
                        area.onEdit(limn.scene.EditCommand.COPY);
                        area.onEdit(limn.scene.EditCommand.CUT);
                    } else {
                        field.onEdit(limn.scene.EditCommand.COPY);
                        field.onEdit(limn.scene.EditCommand.CUT);
                    }
                }, what + ": the commands do not throw");
                assertEquals("Co", inArea ? area.text() : field.text(), what + ": nothing cut");
                assertEquals("CLIP", window.clipboard().text(), what + ": nothing copied");
            }
            drive(scene).press(Keys.ESCAPE);
            settle();
        }
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aPasswordFieldsLeftSelectionIsNotCopiedButIsPastedOver(DisplayMode menus) {
        PopupMenu.setDefaultDisplayMode(menus);
        backend = new HeadlessBackend(runtime);
        HeadlessWindow window = backend.open("Sign in", 600, 400);
        PasswordField secret = new PasswordField();
        secret.setText("hunter22");
        Column column = new Column();
        column.add(secret);
        column.add(new Button("Sign in"));
        scene = new Scene(column, clock::get);
        scene.setTextRuler(RULER);
        scene.bind(window);
        window.frame();
        window.clipboard().setText("CLIP");
        scene.requestFocus(secret);
        secret.select(0, 6);
        settle();
        runFirstRow(new Menu().add(MenuItem.of(MenuItem.Role.COPY)), secret);
        assertEquals("CLIP", window.clipboard().text(), menus + ": a secret never reaches the clipboard");
        secret.select(0, 6);
        settle();
        runFirstRow(new Menu().add(MenuItem.of(MenuItem.Role.PASTE)), secret);
        assertEquals("CLIP22", secret.text(), menus + ": Paste replaced the selected part");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void selectAllFromTheFieldsOwnMenuSelectsAll(DisplayMode menus) {
        open(menus);
        scene.requestFocus(field);
        field.select(6, 11);
        settle();
        ownMenuSelectAll();
        assertSame(field, scene.focusedWidget(), menus + ": the keyboard is back in the field");
        assertEquals(0, field.selectionStart(), menus + ": Select All selected all");
        assertEquals(11, field.selectionEnd(), menus + ": Select All selected all");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void selectAllFromTheAreasOwnMenuSelectsAll(DisplayMode menus) {
        open(menus);
        scene.requestFocus(area);
        area.select(5, 8);
        settle();
        ownMenuSelectAll();
        assertSame(area, scene.focusedWidget(), menus + ": the keyboard is back in the area");
        assertEquals(0, area.selectionStart(), menus + ": Select All selected all");
        assertEquals(area.text().length(), area.selectionEnd(), menus + ": Select All selected all");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void anApplicationsSelectAllItemSelectsAll(DisplayMode menus) {
        open(menus);
        scene.requestFocus(field);
        field.select(6, 11);
        settle();
        runFirstRow(new Menu().add(MenuItem.of(MenuItem.Role.SELECT_ALL)), field);
        assertEquals(0, field.selectionStart(), menus + ": the edit role selected all");
        assertEquals(11, field.selectionEnd(), menus + ": the edit role selected all");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void anItemThatMovesTheCaretLeavesNoSelectionBehind(DisplayMode menus) {
        open(menus);
        scene.requestFocus(field);
        field.select(6, 11);
        settle();
        TextField f = field;
        runFirstRow(new Menu().add(MenuItem.of("Go to start", () -> f.setCaretPosition(0))), field);
        assertEquals(0, field.selectionStart(), menus + ": the caret went to the start");
        assertEquals(0, field.selectionEnd(), menus + ": with nothing selected");
        drive(scene).type("X");
        settle();
        assertEquals("Xhello world", field.text(), menus + ": the next key is inserted, nothing deleted");
    }

    /**
     * The case a comparison of carets cannot see: the item puts the caret exactly where it already
     * was, at the end of the old selection. It still said "the caret goes here, nothing selected".
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void anItemThatPutsTheCaretWhereItWasStillWins(DisplayMode menus) {
        open(menus);
        scene.requestFocus(field);
        field.select(6, 11);
        settle();
        TextField f = field;
        runFirstRow(new Menu().add(MenuItem.of("Go to end", () -> f.setCaretPosition(11))), field);
        assertEquals(11, field.selectionStart(), menus + ": the caret at the end");
        assertEquals(11, field.selectionEnd(), menus + ": with nothing selected");
        drive(scene).type("!");
        settle();
        assertEquals("hello world!", field.text(), menus + ": the next key is appended, nothing deleted");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void anItemThatSelectsSomethingElseKeepsItsSelection(DisplayMode menus) {
        open(menus);
        scene.requestFocus(area);
        area.select(5, 8); // "one"
        settle();
        TextArea a = area;
        int two = area.text().indexOf("two");
        runFirstRow(new Menu().add(MenuItem.of("Find next", () -> a.select(two, two + 3))), area);
        assertEquals(two, area.selectionStart(), menus + ": the match is selected");
        assertEquals(two + 3, area.selectionEnd(), menus + ": the match is selected");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aMenuClosedWithNothingRunGivesTheSelectionBack(DisplayMode menus) {
        open(menus);
        scene.requestFocus(field);
        field.select(11, 6); // backwards
        settle();
        drive(scene).press(Keys.F10, Keys.MOD_SHIFT);
        settle();
        drive(scene).press(Keys.ESCAPE);
        settle();
        assertSame(field, scene.focusedWidget(), menus + ": the keyboard is back in the field");
        assertEquals(6, field.selectionStart(), menus + ": the selection is back");
        assertEquals(11, field.selectionEnd(), menus + ": the selection is back");
        assertEquals(6, field.caretPosition(), menus + ": backwards, as it was made");
    }

    @Test
    void aPopoversButtonThatMovesTheCaretAndClosesLeavesNoSelectionBehind() {
        open(DisplayMode.IN_SCENE);
        scene.requestFocus(area);
        area.select(5, 8); // "one"
        settle();
        Button go = new Button("Go");
        Popover popover = new Popover(go).setDisplayMode(DisplayMode.IN_SCENE);
        TextArea a = area;
        int lineFour = area.text().indexOf("line four");
        go.onAction(() -> {
            a.setCaretPosition(lineFour);
            popover.close();
        });
        popover.showFor(area);
        settle();
        drive(scene).click(go);
        settle();
        assertSame(area, scene.focusedWidget(), "the keyboard is back in the area");
        assertEquals(lineFour, area.selectionStart(), "the caret at the line asked for");
        assertEquals(lineFour, area.selectionEnd(), "with nothing selected");
        drive(scene).type("Z");
        settle();
        assertEquals("line one\nline two\nline three\nZline four", area.text(),
                "the next key is inserted, and the two lines between are still there");
    }

    @Test
    void aPopoverClosedWithNothingDoneGivesTheSelectionBack() {
        open(DisplayMode.IN_SCENE);
        scene.requestFocus(area);
        area.select(5, 8);
        settle();
        Popover popover = new Popover(new Button("Go")).setDisplayMode(DisplayMode.IN_SCENE);
        popover.showFor(area);
        settle();
        drive(scene).press(Keys.ESCAPE);
        settle();
        assertSame(area, scene.focusedWidget(), "the keyboard is back in the area");
        assertEquals(5, area.selectionStart(), "the selection is back");
        assertEquals(8, area.selectionEnd(), "the selection is back");
    }
}
