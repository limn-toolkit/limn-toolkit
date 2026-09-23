package limn.components;

import limn.accessibility.Accessible;
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.AccessibleHarness;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typed {@link ListView}: items the application owns, the cell forms, and the three selection
 * modes with the gestures {@code Table} and {@code Tree} share (API-8, decision 136).
 */
class ListViewSelectionTest extends ComponentTestBase {

    private static final float ROW = 20;
    private static final List<String> FRUIT = List.of("apple", "banana", "cherry", "date", "elder",
            "fig", "grape", "honeydew");

    /** A row a fixed height tall that paints nothing and cannot take the keyboard. */
    private static final class Cell extends Widget {
        String text;

        Cell(String text) {
            this.text = text;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), ROW);
        }
    }

    private ListView<String> list;
    private Scene scene;
    private final List<String> heard = new ArrayList<>();
    private int handled;

    private void mount(ListView<String> built, SelectionMode mode) {
        mount(built, mode, FRUIT);
    }

    private void mount(ListView<String> built, SelectionMode mode, List<String> items) {
        list = built;
        list.setItems(items);
        list.setSelectionMode(mode);
        list.onSelect(() -> handled++);
        list.observeChanges((w, change) -> heard.add(change.aspect() + "/" + change.origin()));
        scene = new Scene(list);
        scene.setTextRuler(RULER);
        scene.layoutPass(200, ROW * FRUIT.size());
        scene.renderFrame(new limn.testing.NoopCanvas(200, ROW * FRUIT.size()));
        scene.requestFocus(list);
        heard.clear();
        handled = 0;
    }

    private void mount(SelectionMode mode) {
        mount(new ListView<>(Cell::new), mode);
    }

    private static float rowY(int index) {
        return index * ROW + ROW / 2;
    }

    private void click(int index, int modifiers) {
        drive(scene).mouseMoved(10, rowY(index));
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, modifiers, 10, rowY(index));
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, modifiers, 10, rowY(index));
        drive(scene).inputBatchEnded();
    }

    // ------------------------------------------------------------------ items and cells

    @Test
    void theItemsAreTheApplicationsAndTheSelectionNamesThem() {
        mount(SelectionMode.SINGLE);
        click(2, 0);
        assertEquals(2, list.selectedIndex());
        assertEquals("cherry", list.selectedItem());
        assertEquals(List.of("cherry"), list.selectedItems());
        assertEquals(1, handled, "a click is the user's, and reaches onSelect");

        list.setItems(List.of("x", "y"));
        assertEquals(-1, list.selectedIndex(), "a new list is a new world");
        assertNull(list.selectedItem());
        assertEquals(1, handled, "and the selection it dropped reaches no handler");
        assertTrue(heard.contains("SELECTION/ADJUSTMENT"), "but is heard: " + heard);
    }

    @Test
    void aPooledListBindsRecycledWidgetsInsteadOfMakingNewOnes() {
        AtomicInteger made = new AtomicInteger();
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            many.add("item " + i);
        }
        ListView<String> pooled = ListView.pooled(() -> {
            made.incrementAndGet();
            return new Cell("");
        }, (cell, item) -> cell.text = item);
        pooled.setItems(many);
        Scene s = new Scene(new Column());
        Column root = (Column) s.root();
        root.add(new SizedBox(200, 100, pooled));
        s.setTextRuler(RULER);
        s.layoutPass(200, 100);
        s.renderFrame(new limn.testing.NoopCanvas(200, 100));
        int first = made.get();
        for (int i = 0; i < 50; i++) {
            pooled.scrollBy(ROW);
            s.renderFrame(new limn.testing.NoopCanvas(200, 100));
        }
        assertTrue(made.get() <= first + 2, "scrolling fifty rows made " + (made.get() - first)
                + " widgets; a pool binds the ones that scrolled out");
    }

    // ------------------------------------------------------------------ MULTI

    @Test
    void theCommandModifierTogglesAndShiftSelectsARange() {
        mount(SelectionMode.MULTI);
        int command = Accelerator.commandModifier();
        click(1, 0);
        click(3, command);
        assertArrayEquals(new int[]{1, 3}, list.selectedIndices());
        assertEquals(3, list.selectedIndex(), "the lead is the row selected last");

        click(5, Keys.MOD_SHIFT);
        assertArrayEquals(new int[]{3, 4, 5}, list.selectedIndices(), "Shift runs from the anchor, 3");

        click(5, command);
        assertArrayEquals(new int[]{3, 4}, list.selectedIndices());
        assertEquals(4, list.selectedIndex(), "a row toggled off hands the lead back");
        assertEquals(5, list.cursorIndex(), "and keeps the cursor");
        assertEquals(List.of("date", "elder"), list.selectedItems());
    }

    @Test
    void theKeysExtendToggleAndTakeEverything() {
        mount(SelectionMode.MULTI);
        click(2, 0);
        drive(scene).press(Keys.DOWN, Keys.MOD_SHIFT);
        drive(scene).press(Keys.DOWN, Keys.MOD_SHIFT);
        assertArrayEquals(new int[]{2, 3, 4}, list.selectedIndices());

        drive(scene).press(Keys.SPACE);
        assertArrayEquals(new int[]{2, 3}, list.selectedIndices(), "Space toggles the cursor row");

        drive(scene).press(Keys.A, Accelerator.commandModifier());
        assertEquals(FRUIT.size(), list.selectedIndices().length);

        drive(scene).press(Keys.DOWN);
        assertArrayEquals(new int[]{5}, list.selectedIndices(), "a plain arrow selects one row");
    }

    @Test
    void narrowingTheModeKeepsWhatTheNewModeCanHold() {
        mount(SelectionMode.MULTI);
        click(1, 0);
        click(4, Accelerator.commandModifier());
        list.setSelectionMode(SelectionMode.SINGLE);
        assertArrayEquals(new int[]{4}, list.selectedIndices(), "SINGLE keeps the lead");
        assertEquals(4, list.cursorIndex(), "and the cursor is the selection again");
        list.setSelectionMode(SelectionMode.NONE);
        assertArrayEquals(new int[]{}, list.selectedIndices());
        assertEquals(2, heard.stream().filter("SELECTION/ADJUSTMENT"::equals).count(), heard.toString());
    }

    @Test
    void aRefreshThatShortensTheListDropsTheRowsPastItsEnd() {
        List<String> items = new ArrayList<>(FRUIT);
        mount(new ListView<>(Cell::new), SelectionMode.MULTI, items);
        click(1, 0);
        click(6, Accelerator.commandModifier());
        heard.clear();
        items.subList(4, items.size()).clear();
        list.refresh();
        assertArrayEquals(new int[]{1}, list.selectedIndices());
        assertEquals(1, list.selectedIndex());
        assertTrue(heard.contains("SELECTION/ADJUSTMENT"), heard.toString());
    }

    // ------------------------------------------------------------------ NONE

    @Test
    void inNoneTheArrowsMoveTheCursorAndSelectNothing() {
        mount(SelectionMode.NONE);
        drive(scene).press(Keys.DOWN);
        drive(scene).press(Keys.DOWN);
        assertEquals(1, list.cursorIndex());
        assertEquals(-1, list.selectedIndex());
        assertEquals(0, handled);
        assertTrue(heard.contains("ACTIVE/USER"), "the cursor move is said: " + heard);
    }

    // ------------------------------------------------------------------ activation

    @Test
    void aDoubleClickOpensTheRowAsEnterDoes() {
        mount(SelectionMode.SINGLE);
        List<Integer> opened = new ArrayList<>();
        list.onActivate(opened::add);
        drive(scene).mouseMoved(10, rowY(3));
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 10, rowY(3), 1);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, 10, rowY(3), 1);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 10, rowY(3), 2);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, 10, rowY(3), 2);
        drive(scene).inputBatchEnded();
        assertEquals(List.of(3), opened);
    }

    // ------------------------------------------------------------------ assistive technology

    @Test
    void inMultiAReadersFocusMovesTheCursorAndSelectsNothing() {
        ListView<String> multi = new ListView<>(Cell::new);
        multi.setItems(FRUIT);
        multi.setSelectionMode(SelectionMode.MULTI);
        multi.setItemName(limn.i18n.I18nString::literal);
        multi.setAccessibleName("Fruit");
        AccessibleHarness h = new AccessibleHarness(runtime, new SizedBox(200, 200, multi));
        multi.setSelectedIndex(1);
        h.frame();
        long cherry = h.node("cherry").id();
        assertTrue(h.node("cherry").actions().has(Accessible.Action.FOCUS),
                "FOCUS is offered where the cursor is not the selection");
        assertTrue(h.perform(cherry, Accessible.Action.FOCUS, Accessible.Argument.NONE));
        assertEquals(2, multi.cursorIndex());
        assertArrayEquals(new int[]{1}, multi.selectedIndices(), "FOCUS moves no selection");

        long date = h.node("date").id();
        assertTrue(h.perform(date, Accessible.Action.ADD_TO_SELECTION, Accessible.Argument.NONE));
        assertArrayEquals(new int[]{1, 3}, multi.selectedIndices());
        assertEquals(2, multi.cursorIndex(), "ADD_TO_SELECTION leaves the cursor");
        assertFalse(h.node("cherry").has(Accessible.State.SELECTED));
        assertTrue(h.node("date").has(Accessible.State.SELECTED));
    }
}
