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
