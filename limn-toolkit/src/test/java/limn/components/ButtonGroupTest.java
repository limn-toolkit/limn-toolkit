package limn.components;

import limn.testing.StubWindow;
import limn.scene.Change;
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
        List<String> heard = new ArrayList<>();
        for (RadioButton member : List.of(a, b, c)) {
            member.observeChanges((source, change) -> {
                if (change.aspect() == Change.Aspect.VALUE) { // the roving focus announces too
                    heard.add(member.text() + "=" + member.isSelected() + "/" + change.origin());
                }
            });
        }

        // The user's select, through the seam a click and a key enter: the handlers run.
        a.select(Change.Origin.USER);
        assertTrue(a.isSelected());
        assertFalse(b.isSelected());
        assertFalse(c.isSelected());
        assertEquals(0, group.selectedIndex());
        assertEquals(0, groupIndex.get());
        assertEquals(List.of("a=true"), log);
        assertEquals(List.of("A=true/USER"), heard);

        log.clear();
        heard.clear();
        b.select(Change.Origin.USER);
        assertFalse(a.isSelected());
        assertTrue(b.isSelected());
        assertEquals(1, group.selectedIndex());
        assertEquals(1, groupIndex.get());
        // The leaving radio is notified false, the entering one true, and the two are announced
        // to the watchers in that order before either handler runs.
        assertEquals(List.of("a=false", "b=true"), log);
        assertEquals(List.of("A=false/USER", "B=true/USER"), heard);

        log.clear();
        heard.clear();
        groupIndex.set(-1);
        c.select(); // the public verb is a caller's write: the watchers hear it, nobody handles it
        assertTrue(c.isSelected());
        assertEquals(2, group.selectedIndex());
        assertEquals(-1, groupIndex.get(), "the group's handler answers the user, and code moved it");
        assertEquals(List.of(), log, "and so do the members' handlers");
        assertEquals(List.of("B=false/CODE", "C=true/CODE"), heard);
    }

    @Test
    void reselectingTheCurrentRadioIsANoOp() {
        RadioButton a = new RadioButton("A");
        RadioButton b = new RadioButton("B");
        AtomicInteger groupFires = new AtomicInteger();
        ButtonGroup group = new ButtonGroup().add(a).add(b).onSelect(i -> groupFires.incrementAndGet());

        b.select(Change.Origin.USER);
        assertEquals(1, groupFires.get());
        b.select(Change.Origin.USER); // already selected: radios do not toggle off, and nothing re-fires
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
        List<String> heard = new ArrayList<>();
        b.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.VALUE) {
                heard.add(b.isSelected() + "/" + change.origin());
            }
        });
        group.setSelectedIndex(1);
        log.clear();
        heard.clear();

        group.clearSelection();

        assertEquals(-1, group.selectedIndex());
        assertFalse(b.isSelected(), "the leaving member is deselected");
        assertEquals(List.of("false/CODE"), heard, "its watchers hear a caller empty the group");
        assertEquals(List.of(), log, "its handler answers the user, and no user did this");
        assertEquals(-2, groupIndex.get(), "nor does the group's");

        heard.clear();
        group.clearSelection();
        assertEquals(List.of(), heard, "clearing an already-empty group says nothing");
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

    /**
     * The tab stop is decided among the enabled members, so a member's flag moving can move it.
     * It used to be decided only when a member joined or the selection moved: a holder disabled
     * afterwards left the group with no focusable member at all, and Tab skipped a group whose
     * other members still worked.
     */
    @Test
    void disablingTheHolderHandsTheTabStopToTheNextEnabledMember() {
        RadioButton a = new RadioButton("A");
        RadioButton b = new RadioButton("B");
        RadioButton c = new RadioButton("C");
        ButtonGroup group = new ButtonGroup().add(a).add(b).add(c);
        Scene scene = bound(a, b, c);
        assertEquals(List.of("A"), tabOrder(scene), "the first enabled member holds it");

        scene.requestFocus(a);
        a.setEnabled(false);

        assertEquals(List.of("B"), tabOrder(scene), "the next enabled member takes it over");
        assertFalse(a.isFocusable());
        assertTrue(b.isFocusable());

        // The selected member holds the stop only while it is enabled; the selection itself stays.
        group.setSelectedIndex(2);
        assertEquals(List.of("C"), tabOrder(scene));
        c.setEnabled(false);
        assertEquals(List.of("B"), tabOrder(scene), "passed over exactly as when joining");
        assertEquals(2, group.selectedIndex(), "and still selected");
        c.setEnabled(true);
        assertEquals(List.of("C"), tabOrder(scene), "and back the moment it is enabled again");
    }

    /** Disabling the focused holder revokes its focus and hands nothing to the new holder. */
    @Test
    void theNewHolderIsNotHandedTheFocusItsPredecessorLost() {
        RadioButton a = new RadioButton("A");
        RadioButton b = new RadioButton("B");
        new ButtonGroup().add(a).add(b);
        Scene scene = bound(a, b);
        scene.requestFocus(a);
        assertTrue(a.isFocused());

        a.setEnabled(false);

        assertTrue(b.isFocusable(), "the stop moved");
        assertFalse(b.isFocused(), "the focus did not: nothing steals it, the next Tab reaches it");
        assertFalse(a.isFocused());
    }

    /** A group with no enabled member has no tab stop, and gets one back with its first member. */
    @Test
    void reEnablingAMemberOfAFullyDisabledGroupRestoresTheTabStop() {
        RadioButton a = new RadioButton("A");
        RadioButton b = new RadioButton("B");
        new ButtonGroup().add(a).add(b);
        Scene scene = bound(a, b);
        a.setEnabled(false);
        b.setEnabled(false);
        assertEquals(List.of(), tabOrder(scene), "nothing to reach");

        b.setEnabled(true);

        assertEquals(List.of("B"), tabOrder(scene));
        assertTrue(b.isFocusable());
        assertFalse(a.isFocusable());
    }

    /** A column of the radios, bound and rendered once, so that focus traversal has a tree. */
    private Scene bound(RadioButton... radios) {
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        for (RadioButton radio : radios) {
            column.add(radio);
        }
        Scene scene = new Scene(column);
        scene.setTextRuler(ComponentTestBase.RULER);
        scene.bind(new StubWindow());
        scene.renderFrame(new FakeCanvas(200, 200));
        return scene;
    }

    /** The captions Tab reaches from nothing, in order, until it wraps: the group's tab stops. */
    private static List<String> tabOrder(Scene scene) {
        List<String> order = new ArrayList<>();
        scene.requestFocus(null);
        for (int i = 0; i < 32; i++) {
            scene.focusTraverse(false);
            limn.scene.Widget focused = scene.focusedWidget();
            if (focused == null) {
                break;
            }
            String caption = ((RadioButton) focused).text();
            if (order.contains(caption)) {
                break; // wrapped
            }
            order.add(caption);
        }
        return order;
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
