package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.input.Keys;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A combo holding one item that may hold none: a first option standing for "nothing", whose value
 * is {@code null}, worded and drawn by the application's own text and icon functions asked for
 * {@code null}.
 */
class EmptyOptionComboBoxTest extends AccessibleComponentTestBase {

    record Status(String label) {
    }

    private static final Icon ANY = new NoIcon();
    private static final List<Status> STATUSES =
            List.of(new Status("Open"), new Status("Shipped"), new Status("Returned"));

    private ComboBox<Status> combo;
    private TextField after;
    private final List<Status> picked = new ArrayList<>();

    private void build(boolean editable) {
        combo = new ComboBox<>(STATUSES, s -> s == null ? "Any status" : s.label())
                .setItemIcon(s -> s == null ? ANY : null)
                .setDisplayMode(DisplayMode.IN_SCENE)
                .setEmptySelectionAllowed(true)
                .setEditable(editable);
        combo.onSelect(() -> picked.add(combo.selectedItem()));
        after = new TextField();
        Column root = new Column();
        root.add(combo);
        root.add(after);
        bind(root);
        scene.setTextRuler(RULER);
        frame();
        scene.requestFocus(editable ? combo.children().get(0) : combo);
        frame();
    }

    private void press(int key) {
        drive(scene).keyEvent(key, true, false, 0);
        drive(scene).keyEvent(key, false, false, 0);
        drive(scene).inputBatchEnded();
        frame();
    }

    @Test
    void theListStartsWithTheEmptyOptionAndChoosingItHoldsNull() {
        build(false);
        assertArrayEquals(new int[] {-1, 0, 1, 2}, combo.rowsForTest(), "the empty option first");
        assertEquals(0, combo.selectedIndex(), "turning it on moves nothing");
        press(Keys.SPACE);
        press(Keys.UP);
        press(Keys.ENTER);
        assertNull(combo.selectedItem());
        assertEquals(-1, combo.selectedIndex());
        assertEquals("Any status", combo.selectedText(), "worded by the text function for null");
        assertEquals(java.util.Arrays.asList((Status) null), picked);
        assertEquals(0, combo.selectedIndices().length);
        press(Keys.SPACE);
        assertEquals(-1, combo.highlightedIndex(), "the list opens on the empty option");
    }

    @Test
    void clearSelectionEmptiesItAndTurningItOffTakesTheFirstItem() {
        build(false);
        combo.setSelectedIndex(2);
        combo.clearSelection();
        assertNull(combo.selectedItem());
        combo.setEmptySelectionAllowed(false);
        assertEquals(0, combo.selectedIndex());
        assertArrayEquals(new int[] {0, 1, 2}, combo.rowsForTest());
        assertTrue(picked.isEmpty(), "writes of the caller's reach no handler");
    }

    @Test
    void aFunctionThatCannotTakeNullLeavesTheOptionBlankAndAReaderHearsNone() {
        combo = new ComboBox<>(STATUSES, Status::label).setDisplayMode(DisplayMode.IN_SCENE)
                .setItemIcon(s -> ANY).setEmptySelectionAllowed(true);
        Column root = new Column();
        root.add(combo);
        bind(root);
        scene.setTextRuler(RULER);
        frame();
        combo.clearSelection();
        assertEquals("", combo.selectedText());
        combo.open();
        frame();
        AccessibleTree tree = tree();
        List<String> options = new ArrayList<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.LIST_ITEM) {
                options.add(node.name());
            }
        }
        assertEquals(List.of(ComponentStrings.COMBO_NONE.get(), "Open", "Shipped", "Returned"), options);
    }

    @Test
    void anEditableOneEmptiedByHandTakesTheEmptyOptionInsteadOfRefusing() {
        build(true);
        TextField editor = (TextField) combo.children().get(0);
        editor.setText("");
        frame();
        scene.requestFocus(after);
        frame();
        assertNull(combo.selectedItem());
        assertEquals("", editor.text(), "nothing, in a text field, is empty text");
        assertEquals(java.util.Arrays.asList((Status) null), picked);
    }

    @Test
    void holdingManyTheListHasNoEmptyOption() {
        build(false);
        combo.setSelectionMode(SelectionMode.MULTI);
        assertArrayEquals(new int[] {0, 1, 2}, combo.rowsForTest());
        assertSame(null, combo.selectedItem());
    }

    /** Stands for an icon; never painted here. */
    private static final class NoIcon implements Icon {
        @Override
        public Image image(int pixelSize, boolean dark) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark,
                boolean mirrored) {
        }
    }
}
