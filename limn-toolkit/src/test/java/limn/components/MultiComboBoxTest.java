package limn.components;

import limn.input.Keys;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A combo box holding many items (ADR 052 §3): the list stays open while Space, Enter or a click
 * toggle its rows, Ctrl+A (Cmd+A) takes every item, the selection is answered in the list's order,
 * and an editable one's text only ever filters. Drawn in the scene, the presentation a headless
 * scene can hold.
 */
class MultiComboBoxTest extends AccessibleComponentTestBase {

    private static final List<String> STATUSES =
            List.of("Aberto", "Faturado", "Enviado", "Cancelado", "Devolvido");

    private ComboBox<String> combo;
    private TextField after;
    private int handled;

    private void build(boolean editable) {
        combo = new ComboBox<>(STATUSES).setDisplayMode(DisplayMode.IN_SCENE)
                .setSelectionMode(SelectionMode.MULTI).setEditable(editable);
        combo.onSelect(() -> handled++);
        after = new TextField();
        Column root = new Column();
        root.add(combo);
        root.add(after);
        bind(root);
        scene.setTextRuler(RULER);
        frame();
        scene.requestFocus(editable ? editor() : combo);
        frame();
    }

    private TextField editor() {
        return (TextField) combo.children().get(0);
    }

    private void type(String text) {
        text.codePoints().forEach(cp -> drive(scene).charTyped(cp));
        drive(scene).inputBatchEnded();
        frame();
    }

    private void press(int key) {
        press(key, 0);
    }

    private void press(int key, int modifiers) {
        drive(scene).keyEvent(key, true, false, modifiers);
        drive(scene).keyEvent(key, false, false, modifiers);
        drive(scene).inputBatchEnded();
        frame();
    }

    @Test
    void aComboChoosesAtLeastOneItemAndHoldsManyOnlyOverAListInMemory() {
        ComboBox<String> plain = new ComboBox<>(STATUSES);
        assertThrows(IllegalArgumentException.class, () -> plain.setSelectionMode(SelectionMode.NONE));

        ComboBox<String> searching = new ComboBox<String>(s -> s)
                .setSearch(text -> CompletableFuture.completedFuture(List.of()));
        assertThrows(IllegalStateException.class, () -> searching.setSelectionMode(SelectionMode.MULTI));

        ComboBox<String> free = new ComboBox<>(STATUSES).setEditable(true).setFreeText(s -> s);
        assertThrows(IllegalStateException.class, () -> free.setSelectionMode(SelectionMode.MULTI));

        ComboBox<String> many = new ComboBox<>(STATUSES).setSelectionMode(SelectionMode.MULTI);
        assertThrows(IllegalStateException.class, () -> many.setFreeText(s -> s));
        assertThrows(IllegalStateException.class,
                () -> many.setSearch(text -> CompletableFuture.completedFuture(List.of())));
    }

    @Test
    void switchingStartsEmptyAndSwitchingBackKeepsTheFirst() {
        ComboBox<String> box = new ComboBox<>(STATUSES).setSelectedIndex(2);
        box.setSelectionMode(SelectionMode.MULTI);
        assertArrayEquals(new int[0], box.selectedIndices(),
                "holding many starts empty: the one item held before was nobody's choice");
        box.setSelectedIndices(4, 1);
        assertEquals(List.of("Faturado", "Devolvido"), box.selectedItems(), "in the list's order");
        assertEquals(1, box.selectedIndex(), "the first of them");
        box.setSelectionMode(SelectionMode.SINGLE);
        assertEquals(1, box.selectedIndex());
        assertArrayEquals(new int[] {1}, box.selectedIndices());

        box.setSelectionMode(SelectionMode.MULTI);
        box.clearSelection();
        assertNull(box.selectedItem());
        box.setSelectionMode(SelectionMode.SINGLE);
        assertEquals(0, box.selectedIndex(), "a combo that chooses one always holds one");
    }

    @Test
    void theSettersAreACallersWritesAndReachNoHandler() {
        build(false);
        combo.setSelectedItems(List.of("Enviado", "Aberto"));
        assertEquals(List.of("Aberto", "Enviado"), combo.selectedItems());
        combo.selectAll();
        assertEquals(STATUSES, combo.selectedItems());
        combo.clearSelection();
        assertTrue(combo.selectedItems().isEmpty());
        assertEquals(0, handled);
        assertThrows(IllegalArgumentException.class, () -> combo.setSelectedItems(List.of("Pago")));

        ComboBox<String> single = new ComboBox<>(STATUSES);
        assertThrows(IllegalArgumentException.class, () -> single.setSelectedIndices(0, 1));
        assertThrows(IllegalStateException.class, single::clearSelection);
        single.selectAll();
        assertEquals(0, single.selectedIndex(), "selectAll holds one where one is held");
    }

    @Test
    void spaceAndEnterToggleTheHighlightedRowAndTheListStaysOpen() {
        build(false);
        press(Keys.SPACE);
        assertTrue(combo.isOpen());
        assertEquals(0, combo.highlightedIndex(), "nothing selected: the first row");
        press(Keys.SPACE);
        assertTrue(combo.isOpen(), "a toggle leaves the list down");
        press(Keys.DOWN);
        press(Keys.DOWN);
        press(Keys.ENTER);
        assertTrue(combo.isOpen());
        assertEquals(List.of("Aberto", "Enviado"), combo.selectedItems());
        assertEquals(2, handled, "one handler call per toggle");
        press(Keys.UP);
        press(Keys.UP);
        press(Keys.SPACE);
        assertEquals(List.of("Enviado"), combo.selectedItems(), "a second toggle takes it out");
        press(Keys.ESCAPE);
        assertFalse(combo.isOpen());
        assertEquals(List.of("Enviado"), combo.selectedItems(), "Escape keeps what was toggled");
        assertEquals("Enviado", combo.selectedText());
    }

    @Test
    void theListOpensOnTheFirstSelectedItem() {
        build(false);
        combo.setSelectedIndices(3, 1);
        press(Keys.DOWN);
        assertEquals(1, combo.highlightedIndex());
    }

    @Test
    void theCommandKeyWithATakesEveryItem() {
        build(false);
        press(Keys.SPACE);
        press(Keys.A, Accelerator.commandModifier());
        assertEquals(STATUSES, combo.selectedItems());
        assertEquals(1, handled, "one change, the user's");
        assertTrue(combo.isOpen());
    }

    @Test
    void theTextsAreJoinedInTheListsOrder() {
        build(false);
        combo.setSelectedIndices(2, 0);
        assertEquals("Aberto, Enviado", combo.selectedText());
    }

    @Test
    void anEditableOnesTextFiltersAndEnterTogglesAndEmptiesIt() {
        build(true);
        assertEquals("", editor().text(), "holding many, the field's text is only a filter");
        type("fat");
        assertTrue(combo.isOpen());
        assertArrayEquals(new int[] {1}, combo.rowsForTest());
        press(Keys.ENTER);
        assertEquals(List.of("Faturado"), combo.selectedItems());
        assertTrue(combo.isOpen(), "the list stays down");
        assertEquals("", editor().text(), "the filter goes with the toggle");
        assertArrayEquals(new int[] {0, 1, 2, 3, 4}, combo.rowsForTest(), "every item shows again");
        assertEquals(1, combo.highlightedIndex(), "on the item just toggled");
        assertSame(editor(), scene.focusedWidget(), "the field keeps the keyboard");
        assertEquals(1, handled);
    }

    @Test
    void anEditableOneCommitsNothingTypedAndLeavingEmptiesTheText() {
        build(true);
        combo.setSelectedIndices(0);
        type("Enviado");
        scene.requestFocus(after);
        frame();
        assertEquals(List.of("Aberto"), combo.selectedItems(), "a text naming an item is a filter still");
        assertEquals("", editor().text());
        assertFalse(combo.isOpen());
        assertEquals(0, handled);
    }

    @Test
    void anEditableOnesEscapeClosesTheListAndThenEmptiesTheText() {
        build(true);
        type("can");
        press(Keys.ESCAPE);
        assertFalse(combo.isOpen());
        assertEquals("", editor().text(), "the chips come back when the list goes");
        press(Keys.ENTER);
        assertTrue(combo.isOpen(), "Enter with the list closed shows it");
        assertTrue(combo.selectedItems().isEmpty());
    }
}
