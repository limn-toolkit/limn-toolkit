package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.input.Keys;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An editable combo box (ADR 051): typing filters the list with its first row highlighted, the
 * arrows walk it, Enter picks, Escape closes the list and then puts the selected item's text back,
 * and leaving the field picks the item the text names or puts the text back. The field keeps the
 * keyboard while the list is up, drawn in the scene here: that is the presentation a Wayland
 * session and a fullscreen window get, and the one that needed the scene to leave the keyboard
 * beneath a popup.
 */
class EditableComboBoxTest extends AccessibleComponentTestBase {

    private static final List<String> CUSTOMERS =
            List.of("São Paulo Alimentos", "Padaria São João", "Atlas Ferragens");

    private ComboBox<String> combo;
    private TextField after;
    private final List<String> picked = new ArrayList<>();

    private void build() {
        combo = new ComboBox<>(CUSTOMERS).setEditable(true).setDisplayMode(DisplayMode.IN_SCENE);
        combo.onSelect(picked::add);
        after = new TextField();
        Column root = new Column();
        root.add(combo);
        root.add(after);
        bind(root);
        scene.setTextRuler(RULER);
        frame();
        scene.requestFocus(editor());
        frame();
    }

    private TextField editor() {
        TextField field = (TextField) combo.children().get(0);
        assertNotNull(field);
        return field;
    }

    private void type(String text) {
        text.codePoints().forEach(cp -> drive(scene).charTyped(cp));
        drive(scene).inputBatchEnded();
        frame();
    }

    private void press(int key) {
        drive(scene).keyEvent(key, true, false, 0);
        drive(scene).keyEvent(key, false, false, 0);
        drive(scene).inputBatchEnded();
        frame();
    }

    private void clearText() {
        editor().setText("");
        frame();
    }

    @Test
    void typingFiltersTheListWithWhatStartsWithTheTextFirst() {
        build();
        clearText();
        type("sao");
        assertTrue(combo.isOpen(), "typing opens the list");
        assertArrayEquals(new int[] {0, 1}, combo.rowsForTest(),
                "accents and case aside, the item that starts with it first");
        assertEquals(0, combo.highlightedIndex(), "the first row is highlighted");
        type(" j");
        assertArrayEquals(new int[] {1}, combo.rowsForTest());
        assertSame(editor(), scene.focusedWidget(), "the field keeps the keyboard while the list is up");
        assertEquals("sao j", editor().text(), "no inline completion: the text stays as typed");
    }

    @Test
    void enterPicksTheHighlightedItemAndPutsItsTextInTheField() {
        build();
        clearText();
        type("joao");
        press(Keys.ENTER);
        assertFalse(combo.isOpen());
        assertEquals(1, combo.selectedIndex());
        assertEquals("Padaria São João", editor().text());
        assertEquals(List.of("Padaria São João"), picked);
    }

    @Test
    void escapeClosesTheListAndThenPutsTheSelectedTextBack() {
        build();
        clearText();
        type("xyz");
        assertTrue(combo.isOpen());
        assertEquals(0, combo.rowsForTest().length, "nothing found");
        press(Keys.ESCAPE);
        assertFalse(combo.isOpen());
        assertEquals("xyz", editor().text(), "the first Escape keeps the text");
        press(Keys.ESCAPE);
        assertEquals("São Paulo Alimentos", editor().text(), "the second puts the selection back");
        assertTrue(picked.isEmpty());
    }

    @Test
    void leavingTheFieldPicksTheItemTheTextNames() {
        build();
        clearText();
        type("atlas ferragens");
        scene.requestFocus(after);
        frame();
        assertEquals(2, combo.selectedIndex(), "compared as the filter compares");
        assertEquals("Atlas Ferragens", editor().text());
        assertEquals(List.of("Atlas Ferragens"), picked);
    }

    @Test
    void leavingTheFieldWithTextThatNamesNoItemPutsTheSelectedTextBack() {
        build();
        clearText();
        type("Padaria Nova");
        scene.requestFocus(after);
        frame();
        assertEquals(0, combo.selectedIndex());
        assertEquals("São Paulo Alimentos", editor().text());
        assertTrue(picked.isEmpty());
        assertFalse(combo.isOpen());
    }

    @Test
    void refusedTextIsPutBackAndTheReaderIsToldWhatTheFieldHoldsAgain() {
        build();
        clearText();
        type("Padaria Nova");
        bridge.events.clear();
        scene.requestFocus(after);
        frame();
        List<String> said = new ArrayList<>();
        for (limn.accessibility.AccessibleEvent event : bridge.events) {
            if (event.type() == limn.accessibility.AccessibleEvent.Type.ANNOUNCEMENT) {
                said.add(String.valueOf(event.newValue()));
            }
        }
        assertEquals(List.of(ComponentStrings.SPINNER_REFUSED.format("Padaria Nova", "São Paulo Alimentos")),
                said, "the spinner's sentence, politely, once");
    }

    @Test
    void freeTextBecomesTheValueAndAnEmptyFieldClearsIt() {
        build();
        combo.setFreeText(text -> text.toUpperCase(java.util.Locale.ROOT));
        clearText();
        type("Padaria Nova");
        scene.requestFocus(after);
        frame();
        assertEquals(-1, combo.selectedIndex(), "a value the list does not hold");
        assertEquals("PADARIA NOVA", combo.selectedItem(), "what the application made of the text");
        assertEquals("Padaria Nova", editor().text(), "the field keeps the text as typed");
        assertEquals(List.of("PADARIA NOVA"), picked);

        scene.requestFocus(editor());
        clearText();
        scene.requestFocus(after);
        frame();
        assertEquals(null, combo.selectedItem(), "emptied, with free text on: no value");
        assertEquals("", editor().text());

        scene.requestFocus(editor());
        type("atlas ferragens");
        scene.requestFocus(after);
        frame();
        assertEquals(2, combo.selectedIndex(), "a text that names an item still picks the item");
    }

    @Test
    void downOpensEveryItemWhenTheTextIsTheSelectedOnes() {
        build();
        press(Keys.DOWN);
        assertTrue(combo.isOpen());
        assertArrayEquals(new int[] {0, 1, 2}, combo.rowsForTest());
        press(Keys.DOWN);
        assertEquals(1, combo.highlightedIndex());
        press(Keys.ENTER);
        assertEquals("Padaria São João", editor().text());
    }

    @Test
    void anApplicationsPredicateReplacesTheRule() {
        record Customer(String code, String name) { }
        List<Customer> customers = List.of(new Customer("11", "Ana"), new Customer("22", "Bruno"));
        ComboBox<Customer> box = new ComboBox<>(customers, Customer::name).setEditable(true)
                .setFilter((customer, text) -> customer.code().startsWith(text) || customer.name().contains(text));
        box.setDisplayMode(DisplayMode.IN_SCENE);
        bind(box);
        scene.setTextRuler(RULER);
        frame();
        scene.requestFocus(box.children().get(0));
        ((TextField) box.children().get(0)).setText("");
        type("22");
        assertArrayEquals(new int[] {1}, box.rowsForTest(), "found by its code, which it does not read as");
        press(Keys.ENTER);
        assertEquals("Bruno", box.selectedItem().name());
    }

    @Test
    void theHighlightedOptionIsTheCursorWhileTheFieldHoldsTheFocus() {
        build();
        clearText();
        type("sao");
        AccessibleTree tree = tree();
        AccessibleNode focused = tree.node(tree.indexOf(tree.focused()));
        assertEquals(Accessible.Role.TEXT_FIELD, focused.role(), "the reader's focus is the field");
        AccessibleNode cursor = tree.node(tree.indexOf(tree.effectiveFocus()));
        assertEquals(Accessible.Role.LIST_ITEM, cursor.role());
        assertEquals("São Paulo Alimentos", cursor.name(), "and its cursor the highlighted option");
        press(Keys.DOWN);
        tree = tree();
        assertEquals("Padaria São João", tree.node(tree.indexOf(tree.effectiveFocus())).name());
    }

    @Test
    void theComboCaptionNamesTheField() {
        combo = new ComboBox<>(CUSTOMERS).setEditable(true);
        Label caption = new Label("Cliente").setLabelFor(combo);
        Column root = new Column();
        root.add(caption);
        root.add(combo);
        bind(root);
        scene.setTextRuler(RULER);
        frame();
        AccessibleTree tree = tree();
        boolean found = false;
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.TEXT_FIELD) {
                assertEquals("Cliente", node.name());
                found = true;
            }
        }
        assertTrue(found, "the editor is published as a text field");
    }
}
