package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.ToggleFacet;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The regression gate for the publish step, and the test whose absence let a design ship in which
 * a checkbox toggle raised nothing.
 *
 * <p>Nothing here constructs a tree. Every case mutates a widget through the path an application
 * takes, renders one frame, and asserts the event arrives with the right node and the right value.
 * A funnel that stops setting the dirty flag fails it; a test that diffs two trees it built itself
 * would stay green while nothing in the toolkit ever produced the second one.
 */
class AccessibleLiveMutationTest extends AccessibleTestBase {

    private Probe probe;

    private void bindProbe() {
        Group root = new Group();
        probe = new Probe(Accessible.Role.CHECK_BOX, "Wrap lines");
        probe.setFocusable(true);
        probe.toggle = ToggleFacet.State.OFF;
        root.add(probe);
        bind(root);
        frame();
        bridge.events.clear();
    }

    /** A toggle is a repaint and nothing else, which is why the flag rides the damage funnel. */
    @Test
    void aStateChangeThatOnlyRepaintsRaisesItsEvent() {
        bindProbe();

        probe.toggle = ToggleFacet.State.ON;
        probe.invalidate();
        frame();

        AccessibleEvent event = bridge.first(AccessibleEvent.Type.STATE_CHANGED);
        assertNotNull(event, "a toggle must reach a screen reader: " + bridge.events);
        assertEquals(Accessible.State.CHECKED, event.state());
        assertEquals(Boolean.TRUE, event.newValue());
        assertEquals(node("Wrap lines").id(), event.nodeId());
        assertEquals(ToggleFacet.State.ON, node("Wrap lines").toggle().state());
    }

    @Test
    void aValueChangeCarriesTheValueItWasAndTheValueItIs() {
        bindProbe();
        probe.value = 10.0;
        probe.invalidate();
        frame();
        bridge.events.clear();

        probe.value = 40.0;
        probe.invalidate();
        frame();

        AccessibleEvent event = bridge.first(AccessibleEvent.Type.VALUE_CHANGED);
        assertNotNull(event, "" + bridge.events);
        assertEquals(10.0, event.oldValue());
        assertEquals(40.0, event.newValue());
    }

    @Test
    void aTextEditCarriesWhereItLandedAndWhatItReplaced() {
        bindProbe();
        probe.text = "hello";
        probe.textWitness = 1;
        probe.invalidate();
        frame();
        bridge.events.clear();

        probe.text = "hello world";
        probe.textWitness = 2;
        probe.invalidate();
        frame();

        AccessibleEvent event = bridge.first(AccessibleEvent.Type.TEXT_CHANGED);
        assertNotNull(event, "" + bridge.events);
        assertEquals(5, event.offset());
        assertEquals(0, event.removed());
        assertEquals(6, event.inserted());
        assertEquals("hello world", event.newValue());
    }

    /**
     * The offsets are UTF-16 code units, stated once and converted nowhere else. A test over
     * astral-plane text is what keeps a later change from quietly counting code points.
     */
    @Test
    void textOffsetsCountCodeUnitsAcrossAnAstralCharacter() {
        bindProbe();
        probe.text = "a😀b";       // a, one emoji as a surrogate pair, b
        probe.textWitness = 1;
        probe.invalidate();
        frame();
        bridge.events.clear();

        probe.text = "a😀Xb";
        probe.textWitness = 2;
        probe.invalidate();
        frame();

        AccessibleEvent event = bridge.first(AccessibleEvent.Type.TEXT_CHANGED);
        assertNotNull(event, "" + bridge.events);
        assertEquals(3, event.offset(), "the emoji is two code units, so the insert is at three");
        assertEquals(1, event.inserted());
    }

    @Test
    void aCaretMoveAndASelectionChangeAreDifferentEvents() {
        bindProbe();
        probe.text = "hello";
        probe.textWitness = 1;
        probe.invalidate();
        frame();
        bridge.events.clear();

        probe.caret = 3;
        probe.invalidate();
        frame();
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.CARET_MOVED));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.TEXT_SELECTION_CHANGED));

        bridge.events.clear();
        probe.selectionStart = 1;
        probe.selectionEnd = 3;
        probe.invalidate();
        frame();
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.TEXT_SELECTION_CHANGED));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.CARET_MOVED));
    }

    @Test
    void aNameChangeCarriesBothStrings() {
        bindProbe();

        probe.setAccessibleName(I18nString.literal("Soft wrap"));
        frame();

        AccessibleEvent event = bridge.first(AccessibleEvent.Type.NAME_CHANGED);
        assertNotNull(event, "" + bridge.events);
        assertEquals("Wrap lines", event.oldValue());
        assertEquals("Soft wrap", event.newValue());
    }

    @Test
    void movingTheFocusRaisesAFocusEventAndTheTreesHeaderFollowsIt() {
        Group root = new Group();
        Probe first = new Probe(Accessible.Role.BUTTON, "first");
        first.setFocusable(true);
        Probe second = new Probe(Accessible.Role.BUTTON, "second");
        second.setFocusable(true);
        root.add(first);
        root.add(second);
        bind(root);
        frame();
        bridge.events.clear();

        scene.requestFocus(second);
        frame();

        assertEquals(1, bridge.countOf(AccessibleEvent.Type.FOCUS_CHANGED));
        assertEquals(node("second").id(),
                bridge.first(AccessibleEvent.Type.FOCUS_CHANGED).nodeId());
        assertEquals(node("second").id(), tree().focused());
    }

    @Test
    void aWidgetLeavingTheTreeIsDestroyedAndOneArrivingIsAStructureChange() {
        Group root = new Group();
        Probe first = new Probe(Accessible.Role.BUTTON, "first");
        root.add(first);
        bind(root);
        frame();
        long id = node("first").id();
        bridge.events.clear();

        root.add(new Probe(Accessible.Role.BUTTON, "second"));
        frame();
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED));

        bridge.events.clear();
        root.remove(first);
        frame();
        AccessibleEvent destroyed = bridge.first(AccessibleEvent.Type.NODE_DESTROYED);
        assertNotNull(destroyed, "" + bridge.events);
        assertEquals(id, destroyed.nodeId());
    }

    @Test
    void movingAWidgetRaisesItsNewBoxAndKeepsItsIdentifier() {
        Group root = new Group();
        Probe probeOne = new Probe(Accessible.Role.BUTTON, "one");
        root.add(probeOne);
        bind(root);
        frame();
        long id = node("one").id();
        bridge.events.clear();

        probeOne.prefHeight = 60;
        probeOne.markNeedsLayout();
        frame();

        AccessibleEvent bounds = bridge.first(AccessibleEvent.Type.BOUNDS_CHANGED);
        assertNotNull(bounds, "" + bridge.events);
        assertEquals(id, bounds.nodeId());
        assertEquals(id, node("one").id(), "the same widget keeps the same element");
        assertEquals(60f, node("one").height());
    }

    /** An application's unpainted change reaches the tree through the one call that exists for it. */
    @Test
    void anUnpaintedChangeReachesTheTreeThroughInvalidateAccessible() {
        bindProbe();

        probe.setTooltip("Wrap long lines at the window edge");
        frame();

        assertEquals("Wrap long lines at the window edge", node("Wrap lines").description());
        assertNotNull(bridge.first(AccessibleEvent.Type.DESCRIPTION_CHANGED), "" + bridge.events);
    }

    @Test
    void anInvalidateOnAnIdleSceneBuysExactlyOneFrameAndThatFrameCarriesTheChange() {
        bindProbe();
        window.frameRequests = 0;

        probe.setAccessibleName("Renamed");

        assertEquals(1, window.frameRequests,
                "a flag set with no frame coming is a no-op with a comforting name");
        assertNull(bridge.first(AccessibleEvent.Type.NAME_CHANGED), "not until the frame runs");
        frame();
        assertNotNull(bridge.first(AccessibleEvent.Type.NAME_CHANGED));
    }
}
