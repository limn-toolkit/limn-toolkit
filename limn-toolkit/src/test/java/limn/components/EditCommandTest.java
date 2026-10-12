package limn.components;

import limn.input.Keys;
import limn.scene.EditCommand;
import limn.scene.Scene;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The six editing commands, asked of the focused widget and done by it, and the items made for them. */
class EditCommandTest extends ComponentTestBase {

    private TextField field;
    private Scene scene;
    private TextFieldTest.MockClipboard clipboard;

    private void build(TextField widget) {
        field = widget;
        scene = new Scene(field);
        scene.setTextRuler(RULER);
        clipboard = new TextFieldTest.MockClipboard();
        scene.setClipboard(clipboard);
        scene.layoutPass(240, 32);
        scene.requestFocus(field);
    }

    private void type(String text) {
        text.codePoints().forEach(drive(scene)::charTyped);
        drive(scene).inputBatchEnded();
    }

    @Test
    void aTextFieldDoesEachCommandAsItsChordDoes() {
        build(new TextField());
        type("limn");
        assertFalse(scene.canEdit(EditCommand.COPY), "nothing is selected");
        assertFalse(scene.canEdit(EditCommand.PASTE), "the clipboard is empty");
        assertTrue(scene.canEdit(EditCommand.SELECT_ALL));
        assertTrue(scene.canEdit(EditCommand.UNDO));
        assertFalse(scene.canEdit(EditCommand.REDO));

        assertTrue(scene.edit(EditCommand.SELECT_ALL));
        assertTrue(scene.canEdit(EditCommand.COPY));
        assertTrue(scene.edit(EditCommand.COPY));
        assertEquals("limn", clipboard.value);

        assertTrue(scene.edit(EditCommand.CUT));
        assertEquals("", field.text());
        assertTrue(scene.edit(EditCommand.PASTE));
        assertEquals("limn", field.text());

        assertTrue(scene.edit(EditCommand.UNDO));
        assertEquals("", field.text());
        assertTrue(scene.canEdit(EditCommand.REDO));
        assertTrue(scene.edit(EditCommand.REDO));
        assertEquals("limn", field.text());
    }

    @Test
    void aCommandNobodyCanDoIsNotDone() {
        build(new TextField());
        assertFalse(scene.edit(EditCommand.COPY), "an empty field copies nothing");
        scene.requestFocus(null);
        for (EditCommand command : EditCommand.values()) {
            assertFalse(scene.canEdit(command), command + " with nothing focused");
            assertFalse(scene.edit(command));
        }
    }

    @Test
    void aPasswordFieldNeitherCopiesNorCuts() {
        build(new PasswordField());
        type("secret");
        scene.edit(EditCommand.SELECT_ALL);
        assertFalse(scene.canEdit(EditCommand.COPY));
        assertFalse(scene.canEdit(EditCommand.CUT));
        assertFalse(scene.edit(EditCommand.COPY));
        assertEquals("", clipboard.value);
    }

    @Test
    void aDisabledFieldDoesNothing() {
        build(new TextField());
        type("limn");
        scene.edit(EditCommand.SELECT_ALL);
        field.setEnabled(false);
        scene.requestFocus(field);
        assertFalse(scene.canEdit(EditCommand.CUT));
        assertFalse(scene.canEdit(EditCommand.COPY));
    }

    @Test
    void aListThatSelectsSeveralSelectsAll() {
        ListView<String> list = new ListView<String>(Label::new).setItems(List.of("a", "b", "c"));
        Scene listScene = new Scene(list);
        listScene.setTextRuler(RULER);
        listScene.layoutPass(200, 200);
        listScene.requestFocus(list);
        assertFalse(listScene.canEdit(EditCommand.SELECT_ALL), "a list that selects one");
        list.setSelectionMode(SelectionMode.MULTI);
        assertTrue(listScene.canEdit(EditCommand.SELECT_ALL));
        assertTrue(listScene.edit(EditCommand.SELECT_ALL));
        assertEquals(3, list.selectedIndices().length);
        assertFalse(listScene.canEdit(EditCommand.COPY), "a list copies nothing");
    }

    @Test
    void anAncestorAnswersWhenTheFocusedWidgetCannot() {
        ListView<String> list = new ListView<String>(Label::new).setItems(List.of("a", "b"))
                .setSelectionMode(SelectionMode.MULTI);
        Column page = new Column();
        page.add(list);
        Scene listScene = new Scene(page);
        listScene.setTextRuler(RULER);
        listScene.layoutPass(200, 200);
        assertTrue(listScene.canEdit(EditCommand.SELECT_ALL, list), "asked from a widget the caller names");
        assertFalse(listScene.canEdit(EditCommand.SELECT_ALL, null));
    }

    @Test
    void anItemOfAnEditingRoleIsTheCommand() {
        MenuItem copy = MenuItem.of(MenuItem.Role.COPY);
        assertEquals(ComponentStrings.TEXT_MENU_COPY.get(), copy.label());
        assertEquals(MenuItem.Role.COPY, copy.role());
        assertNotNull(copy.accelerator());
        assertEquals(Keys.C, copy.accelerator().key());
        assertEquals(ComponentStrings.EDIT_UNDO.get(), MenuItem.of(MenuItem.Role.UNDO).label());
        assertEquals(ComponentStrings.EDIT_REDO.get(), MenuItem.of(MenuItem.Role.REDO).label());

        build(new TextField());
        type("limn");
        EditTarget.opening(scene);
        assertFalse(copy.isEnabled(), "nothing selected when the menu opened");
        scene.edit(EditCommand.SELECT_ALL);
        EditTarget.opening(scene);
        assertTrue(copy.isEnabled());
        copy.activate(limn.scene.Change.Origin.USER);
        assertEquals("limn", clipboard.value);

        copy.setEnabled(false);
        EditTarget.opening(scene);
        assertFalse(copy.isEnabled(), "the application's own flag still disables it");
    }

    @Test
    void aMenuBarsCopyFindsTheSelectionTheFieldHadBeforeTheBarTookTheKeyboard() {
        TextField text = new TextField();
        MenuBar bar = new MenuBar().addMenu("Edit", new Menu().add(MenuItem.of(MenuItem.Role.COPY)));
        Column page = new Column();
        page.add(bar);
        page.add(text);
        scene = new Scene(page);
        scene.setTextRuler(RULER);
        clipboard = new TextFieldTest.MockClipboard();
        scene.setClipboard(clipboard);
        scene.layoutPass(240, 120);
        text.setText("limn");
        scene.requestFocus(text);
        text.selectAll();

        scene.requestFocus(bar); // as a press on the bar does
        assertFalse(text.selectedText().equals("limn"), "the field lets its selection go with the keyboard");
        EditTarget.opening(scene);
        MenuItem copy = MenuItem.of(MenuItem.Role.COPY);
        assertTrue(copy.isEnabled(), "and the bar's Copy still finds it");
        copy.activate(limn.scene.Change.Origin.USER);
        assertEquals("limn", clipboard.value);
        assertEquals(text, scene.focusedWidget(), "the field has the keyboard back");
        assertEquals("limn", text.selectedText());
    }

    @Test
    void aRoleIsRefusedWhereItMeansNothing() {
        assertThrows(IllegalArgumentException.class, () -> MenuItem.of(MenuItem.Role.ABOUT));
        assertThrows(IllegalStateException.class,
                () -> MenuItem.of("Copy", () -> { }).setRole(MenuItem.Role.COPY));
        assertThrows(IllegalStateException.class,
                () -> MenuItem.check("Wrap", false, on -> { }).setRole(MenuItem.Role.SETTINGS));
        assertThrows(IllegalStateException.class,
                () -> MenuItem.submenu("More", new Menu()).setRole(MenuItem.Role.ABOUT));
        assertThrows(IllegalStateException.class, () -> MenuItem.of(MenuItem.Role.CUT).setRole(MenuItem.Role.NONE));
        assertEquals(MenuItem.Role.ABOUT, MenuItem.of("About", () -> { }).setRole(MenuItem.Role.ABOUT).role());
    }
}
