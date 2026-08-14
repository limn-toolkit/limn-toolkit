package limn.components;

import limn.input.Keys;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RadioButton mutual exclusion + group/per-radio notifications. */
class ButtonGroupTest extends ComponentTestBase {

    @Test
    void selectingOneDeselectsSiblingsAndNotifies() {
        RadioButton a = new RadioButton("A");
        RadioButton b = new RadioButton("B");
        RadioButton c = new RadioButton("C");
        List<String> log = new ArrayList<>();
        a.onChange(sel -> log.add("a=" + sel));
        b.onChange(sel -> log.add("b=" + sel));
        c.onChange(sel -> log.add("c=" + sel));
        AtomicInteger groupIndex = new AtomicInteger(-1);
        ButtonGroup group = new ButtonGroup().add(a).add(b).add(c).onSelect(groupIndex::set);

        a.select();
        assertTrue(a.isSelected());
        assertFalse(b.isSelected());
        assertFalse(c.isSelected());
        assertEquals(0, group.selectedIndex());
        assertEquals(0, groupIndex.get());
        assertEquals(List.of("a=true"), log);

        log.clear();
        b.select();
        assertFalse(a.isSelected());
        assertTrue(b.isSelected());
        assertEquals(1, group.selectedIndex());
        assertEquals(1, groupIndex.get());
        // The leaving radio is notified false, the entering one true.
        assertEquals(List.of("a=false", "b=true"), log);
    }

    @Test
    void reselectingTheCurrentRadioIsANoOp() {
        RadioButton a = new RadioButton("A");
        RadioButton b = new RadioButton("B");
        AtomicInteger groupFires = new AtomicInteger();
        ButtonGroup group = new ButtonGroup().add(a).add(b).onSelect(i -> groupFires.incrementAndGet());

        b.select();
        assertEquals(1, groupFires.get());
        b.select(); // already selected: radios do not toggle off, and nothing re-fires
        assertTrue(b.isSelected());
        assertEquals(1, groupFires.get());
    }

    @Test
    void programmaticSelectByIndex() {
        RadioButton a = new RadioButton("A");
        RadioButton b = new RadioButton("B");
        RadioButton c = new RadioButton("C");
        ButtonGroup group = new ButtonGroup().add(a).add(b).add(c);

        group.setSelectedIndex(2);
        assertTrue(c.isSelected());
        assertEquals(2, group.selectedIndex());
        assertThrows(IndexOutOfBoundsException.class, () -> group.setSelectedIndex(99),
                "an index that is not a member is a caller's bug, not a request for the nearest");
        assertEquals(2, group.selectedIndex(), "and the refused call moved nothing");
    }

    /**
     * The state a group starts in and can be put back into. A radio group offers no un-choose
     * gesture (clicking the selected member again does nothing), so this is the only route back,
     * and a form's Reset needs one.
     */
    @Test
    void clearSelectionEmptiesTheGroupAndSaysSo() {
        RadioButton a = new RadioButton("A");
        RadioButton b = new RadioButton("B");
        List<String> log = new ArrayList<>();
        a.onChange(sel -> log.add("a=" + sel));
        b.onChange(sel -> log.add("b=" + sel));
        AtomicInteger groupIndex = new AtomicInteger(-2);
        ButtonGroup group = new ButtonGroup().add(a).add(b).onSelect(groupIndex::set);
        group.setSelectedIndex(1);
        log.clear();

        group.clearSelection();

        assertEquals(-1, group.selectedIndex());
        assertFalse(b.isSelected(), "the leaving member is deselected");
        assertEquals(List.of("b=false"), log, "its own onChange hears it");
        assertEquals(-1, groupIndex.get(), "and the group reports the empty selection");

        groupIndex.set(-2);
        group.clearSelection();
        assertEquals(-2, groupIndex.get(), "clearing an already-empty group says nothing");
    }

    /**
     * The name and the shape the rest of the set uses. This group used to spell the same operation
     * {@code select(int)}, returning void, which also collided with the package-private
     * {@code select(RadioButton)} the radios call: two overloads, one public and one not, for two
     * different things.
     */
    @Test
    void selectingByIndexIsNamedAndShapedLikeTheRestOfTheSet() {
        ButtonGroup group = new ButtonGroup().add(new RadioButton("A")).add(new RadioButton("B"));

        assertSame(group, group.setSelectedIndex(1), "the setter chains, as every sibling's does");
        assertEquals(1, group.selectedIndex());
    }

    /**
     * A group is one tab stop. Every radio used to be its own, so a settings form of six groups
     * of four options cost twenty-four Tab presses to cross instead of six.
     */
    @Test
    void onlyOneMemberOfAGroupIsATabStop() {
        RadioButton a = new RadioButton("A");
        RadioButton b = new RadioButton("B");
        RadioButton c = new RadioButton("C");
        ButtonGroup group = new ButtonGroup().add(a).add(b).add(c);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(a);
        column.add(b);
        column.add(c);
        Scene scene = new Scene(column);
        scene.setTextRuler(ComponentTestBase.RULER);
        scene.layoutPass(200, 200);

        assertTrue(a.isFocusable(), "with nothing selected the first enabled member holds it");
        assertFalse(b.isFocusable());
        assertFalse(c.isFocusable());

        group.setSelectedIndex(2);
        assertTrue(c.isFocusable(), "the tab stop follows the selection");
        assertFalse(a.isFocusable());
    }

    /** Arrows move the selection and the focus together, and wrap: Windows and GTK behaviour. */
    @Test
    void arrowKeysMoveThroughTheGroupAndWrap() {
        RadioButton a = new RadioButton("A");
        RadioButton b = new RadioButton("B");
        RadioButton c = new RadioButton("C");
        ButtonGroup group = new ButtonGroup().add(a).add(b).add(c);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(a);
        column.add(b);
        column.add(c);
        Scene scene = new Scene(column);
        scene.setTextRuler(ComponentTestBase.RULER);
        scene.layoutPass(200, 200);
        group.setSelectedIndex(0);
        scene.requestFocus(a);

        arrow(scene, Keys.DOWN);
        assertEquals(1, group.selectedIndex());
        assertTrue(b.isFocused(), "the focus travels with the selection");

        arrow(scene, Keys.DOWN);
        arrow(scene, Keys.DOWN);
        assertEquals(0, group.selectedIndex(), "the end wraps to the start");

        arrow(scene, Keys.UP);
        assertEquals(2, group.selectedIndex(), "and the start wraps to the end");
    }

    /** A member that cannot be used is stepped over rather than selected and left inert. */
    @Test
    void arrowsSkipDisabledMembers() {
        RadioButton a = new RadioButton("A");
        RadioButton b = new RadioButton("B");
        RadioButton c = new RadioButton("C");
        b.setEnabled(false);
        ButtonGroup group = new ButtonGroup().add(a).add(b).add(c);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(a);
        column.add(b);
        column.add(c);
        Scene scene = new Scene(column);
        scene.setTextRuler(ComponentTestBase.RULER);
        scene.layoutPass(200, 200);
        group.setSelectedIndex(0);
        scene.requestFocus(a);

        arrow(scene, Keys.DOWN);
        assertEquals(2, group.selectedIndex(), "B is disabled, so Down lands on C");
    }

    /** A radio with no group must leave the arrows alone for whatever else wants them. */
    @Test
    void aStandaloneRadioIgnoresTheArrows() {
        RadioButton lone = new RadioButton("only");
        Scene scene = new Scene(lone);
        scene.setTextRuler(ComponentTestBase.RULER);
        scene.layoutPass(200, 40);
        scene.requestFocus(lone);

        arrow(scene, Keys.DOWN);
        assertFalse(lone.isSelected(), "an arrow is not a selection gesture outside a group");
    }

    private static void arrow(Scene scene, int key) {
        scene.keyEvent(key, true, false, 0);
        scene.keyEvent(key, false, false, 0);
        scene.inputBatchEnded();
    }
}
