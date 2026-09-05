package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.ScrollFacet;
import limn.accessibility.SelectionFacet;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link SegmentedControl} becomes in the accessible tree: one horizontal radio group, one
 * radio button per segment, and the two scroll arrows while the strip overflows.
 *
 * <p>ADR 039 §7's row for this widget gets the role and the existence of the chevrons right and is
 * wrong or silent in five places, and the cases here are shaped by them. It calls the segments a
 * {@code List<String>} that cannot follow the subtree language, which phase 2 already fixed — the
 * captions are {@code I18nString}s this widget holds, so a name here is handed over by reference
 * and costs nothing. It asks for the dead chevron to be published <em>disabled</em>, which no
 * widget can do: the builder refuses the five states the publish step owns, and the walk overwrites
 * every synthetic node's enabled bit with its owner's, so the honest form is an absent verb. It
 * makes the scroll facet conditional, where the sibling strip already publishes one unconditionally
 * and reports nowhere-to-go. It says nothing about where a segment's rectangle comes from, which is
 * this widget's whole difficulty. And it never says which node is active, without which arrowing
 * along the strip is silent about where the user is.
 *
 * <p>Every case drives the control's public API, the scene's own input, or stands where a bridge
 * stands. Nothing constructs a node and nothing calls a hook.
 */
class SegmentedControlAccessibilityTest extends AccessibleComponentTestBase {

    /** Comparing ratios computed as floats and widened. */
    private static final double EPS = 1e-6;

    /** The canvas the base binds against, and the width a stretching parent hands the control. */
    private static final float SCENE_WIDTH = 400;

    /** One 10&nbsp;pt label plus its two gutters, floored at the hit target: every segment here. */
    private static final float SEG =
            Math.max(Strokes.MIN_HIT_TARGET, 10 + 2 * SizeTokens.MEDIUM.segPadH());

    /** Three of those, which is the whole track. */
    private static final float TRACK = 3 * SEG;

    /** Narrower than the track, so the strip becomes a scrolled viewport with two chevrons. */
    private static final float NARROW = 100;

    /** The height the overflow fixture pins, so the gutter rule has a second term to bind on. */
    private static final float HEIGHT = 40;

    /** {@code min(height, trackWidth / 4)}: the control's own rule for a chevron gutter. */
    private static final float GUTTER = Math.min(HEIGHT, NARROW / 4);

    private static final float VIEW_WIDTH = NARROW - 2 * GUTTER;

    /** What the strip can travel while overflowing: the content past the viewport. */
    private static final float MAX_OFFSET = TRACK - VIEW_WIDTH;

    private SegmentedControl seg;
    private Column root;

    @AfterEach
    void restoreLanguage() {
        I18n.setLocale(Locale.ENGLISH);
    }

    // ------------------------------------------------------------------------------ the fixture

    /**
     * Binds {@code built} under a listening bridge with the deterministic ruler installed
     * <em>before</em> the first layout.
     *
     * <p>The base's own {@code bind} is not used, and the reason is the one the menu bar's step
     * recorded: a ruler swapped in afterwards marks the scene's layout dirty but not each widget's
     * measure cache, which is keyed on the step, the direction, the language and the constraints
     * and not on the ruler, so the control would keep the width it measured under the process
     * default while every later walk used this one.
     */
    private void bindUnderRuler() {
        bridge = new RecordingBridge();
        window = new StubWindow();
        window.accessibility = bridge;
        canvas = new FakeCanvas(SCENE_WIDTH, 300);
        scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.bind(window);
        frame();
        bridge.events.clear();
    }

    /**
     * Three one-glyph segments in a column that stretches its children, which is the common parent
     * and the one that makes the centred track worth asserting: the control is handed the whole
     * 400 and its track takes 126 of it.
     */
    private void bindFitting() {
        seg = new SegmentedControl(List.of("A", "B", "C"));
        root = new Column();
        root.crossAlignment(Flex.CrossAlignment.STRETCH);
        root.add(seg);
        bindUnderRuler();
    }

    /** The same three segments in a box too narrow for them, so the strip clips and scrolls. */
    private void bindOverflowing() {
        seg = new SegmentedControl(List.of("A", "B", "C"));
        root = new Column();
        root.add(new SizedBox(NARROW, HEIGHT, seg));
        bindUnderRuler();
    }

    /** @return the control's node */
    private AccessibleNode group() {
        return node(Accessible.Role.RADIO_GROUP);
    }

    /** @return every node the control published under itself, in tree order */
    private List<AccessibleNode> children() {
        return childrenOf(group());
    }

    /** @return the three segment nodes, in tree order */
    private List<AccessibleNode> segments() {
        List<AccessibleNode> found = new ArrayList<>();
        for (AccessibleNode child : children()) {
            if (child.role() == Accessible.Role.RADIO_BUTTON) {
                found.add(child);
            }
        }
        return found;
    }

    /**
     * What the toolkit calls a chevron, resolved the way the tree resolves it rather than as an
     * English literal, so this file passes where the process does not happen to speak English.
     *
     * @param back whether to name the arrow that scrolls back
     * @return the resolved name
     */
    private static String chevronName(boolean back) {
        Locale enclosing = I18n.pushScope(I18n.processLocale());
        try {
            return back ? ComponentStrings.SEGMENT_PREVIOUS.get()
                    : ComponentStrings.SEGMENT_NEXT.get();
        } finally {
            I18n.popScope(enclosing);
        }
    }

    /** @return every event raised so far against {@code id}, in order */
    private List<AccessibleEvent> eventsFor(long id) {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.nodeId() == id) {
                found.add(event);
            }
        }
        return found;
    }

    private static void assertFacet(ScrollFacet expected, ScrollFacet actual, String why) {
        assertNotNull(actual, why);
        assertEquals(expected.horizontalPercent(), actual.horizontalPercent(), EPS,
                "horizontal percent: " + why);
        assertEquals(expected.verticalPercent(), actual.verticalPercent(), EPS,
                "vertical percent: " + why);
        assertEquals(expected.horizontalViewSize(), actual.horizontalViewSize(), EPS,
                "horizontal view size: " + why);
        assertEquals(expected.verticalViewSize(), actual.verticalViewSize(), EPS,
                "vertical view size: " + why);
        assertEquals(expected.horizontallyScrollable(), actual.horizontallyScrollable(),
                "horizontally scrollable: " + why);
        assertEquals(expected.verticallyScrollable(), actual.verticallyScrollable(),
                "vertically scrollable: " + why);
    }

    // ------------------------------------------------------------------------------ what it is

    @Test
    void aControlThatFitsIsOneHorizontalRadioGroupOverThreeRadioButtons() {
        bindFitting();

        AccessibleNode node = group();
        assertEquals("", node.name(),
                "a radio group's name is the question it asks, which only the application has; "
                        + "every segment carries a real one" + describe(tree()));
        assertTrue(node.has(Accessible.State.HORIZONTAL),
                "the strip is a row at every width and in both directions" + describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));
        assertEquals(new SelectionFacet(false, true, segments().get(0).id()), node.selection(),
                "one index that swaps, and always exactly one selected: the constructor refuses "
                        + "an empty segment list and there is nothing to clear to"
                        + describe(tree()));
        assertNull(node.value(), describe(tree()));
        assertNull(node.text(), describe(tree()));
        assertNull(node.toggle(), describe(tree()));
        assertNull(node.selectionItem(),
                "it holds a selection and is not a member of one" + describe(tree()));
        assertFacet(new ScrollFacet(0, 0, 1, 1, false, false), node.scroll(),
                "three segments fit, and a fitting strip says nowhere to go rather than making a "
                        + "whole facet appear on the next resize" + describe(tree()));
        assertEquals(Set.of(Accessible.Action.FOCUS, Accessible.Action.SCROLL_INTO_VIEW),
                node.actions().actions(),
                "the two the walk grants a focusable widget, and no verb of its own: what is "
                        + "operable here is each segment" + describe(tree()));

        List<AccessibleNode> children = children();
        assertEquals(3, children.size(), "no chevrons while it fits" + describe(tree()));
        assertEquals(List.of("A", "B", "C"),
                children.stream().map(AccessibleNode::name).toList(),
                "in reading order, which is tree order" + describe(tree()));
        for (int i = 0; i < children.size(); i++) {
            AccessibleNode child = children.get(i);
            assertEquals(Accessible.Role.RADIO_BUTTON, child.role(), describe(tree()));
            assertEquals(i + 1, child.selectionItem().positionInSet(), describe(tree()));
            assertEquals(3, child.selectionItem().sizeOfSet(), describe(tree()));
            assertEquals(Set.of(Accessible.Action.SELECT), child.actions().actions(),
                    "select and no press, exactly as RadioButton answers: the two radio surfaces "
                            + "must answer alike or a bridge's table special-cases one of them"
                            + describe(tree()));
        }
    }

    @Test
    void theSelectedSegmentIsAlsoTheCursorAndTheGroupResolvesItAsSuch() {
        bindFitting();

        List<AccessibleNode> children = segments();
        assertEquals(List.of(children.get(0)), nodesWith(Accessible.State.SELECTED),
                "the first segment is selected at construction" + describe(tree()));
        assertEquals(List.of(children.get(0)), nodesWith(Accessible.State.ACTIVE),
                "and is the cursor too: the arrows call the same private choose() the pointer "
                        + "does, so the selection IS the cursor and there is no second highlight "
                        + "for a reader to be told about" + describe(tree()));
        assertEquals(children.get(0).id(), group().selection().activeDescendant(),
                "resolved in the copy from that bit, which is the only route to an "
                        + "active-descendant event when the strip is walked" + describe(tree()));
    }

    @Test
    void aControlInsideAnInvisibleSubtreeStillNamesItsSegments() {
        // Flex skips invisible children in layout, so this control never lays out and its edge
        // array stays null while the walk still walks it. A hook that indexed it unguarded would
        // throw inside the publish step of any application with a hidden tab holding one.
        seg = new SegmentedControl(List.of("A", "B", "C"));
        Column hidden = new Column();
        hidden.add(seg);
        hidden.setVisible(false);
        root = new Column();
        root.crossAlignment(Flex.CrossAlignment.STRETCH);
        root.add(hidden);
        bindUnderRuler();

        AccessibleNode node = group();
        assertFalse(node.has(Accessible.State.VISIBLE), describe(tree()));
        assertFalse(node.has(Accessible.State.SHOWING), describe(tree()));
        assertEquals(List.of("A", "B", "C"),
                children().stream().map(AccessibleNode::name).toList(),
                "how many segments there are and which one is selected do not depend on a layout "
                        + "having run, and gaining them on the first one would be a structure "
                        + "change for nothing" + describe(tree()));
        assertEquals(0, node.width(),
                "the honest box of a widget that was never laid out" + describe(tree()));
        assertEquals(node.bounds(), children().get(0).bounds(),
                "with no geometry to publish a synthetic child inherits its owner's box"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------ where it is

    @Test
    void aSegmentsBoxIsItsCellOnTheCentredTrackAndNotAShareOfTheWidgetBox() {
        bindFitting();

        AccessibleNode node = group();
        assertEquals(SCENE_WIDTH, node.width(),
                "a stretching parent hands the control the whole row" + describe(tree()));
        float trackLeft = seg.localToSceneX() + (SCENE_WIDTH - TRACK) / 2;

        List<AccessibleNode> children = segments();
        for (int i = 0; i < children.size(); i++) {
            AccessibleNode child = children.get(i);
            assertEquals(trackLeft + i * SEG, child.x(), EPS,
                    "the track takes only what the segments need and centres itself in the box, "
                            + "so a box derived from the widget's own left edge or from an even "
                            + "division puts a reader on the wrong segment" + describe(tree()));
            assertEquals(SEG, child.width(), EPS, describe(tree()));
            assertEquals(node.y(), child.y(), describe(tree()));
            assertEquals(node.height(), child.height(), describe(tree()));
        }
        assertEquals(trackLeft, children.get(0).x(), EPS, describe(tree()));
        assertEquals(trackLeft + TRACK,
                children.get(2).x() + children.get(2).width(), EPS,
                "contiguous, and their union is exactly the track: the margins either side select "
                        + "nothing and belong to whatever is behind this control" + describe(tree()));
        assertTrue(children.get(0).x() > node.x(), describe(tree()));
    }

    @Test
    void thePublishedBoxIsTheBoxAClickLandsIn() {
        bindFitting();
        AccessibleNode second = segments().get(1);

        float x = second.x() + second.width() / 2;
        float y = second.y() + second.height() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();

        assertEquals(1, seg.selectedIndex(),
                "the hook calls the widget's own logical-to-physical mapping rather than "
                        + "re-deriving one, so the rectangle a reader is given and the rectangle "
                        + "the hit test answers for cannot drift apart" + describe(tree()));
    }

    @Test
    void rightToLeftTheOrderHoldsAndTheBoxesRun() {
        bindFitting();
        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        List<AccessibleNode> children = segments();
        assertEquals(List.of("A", "B", "C"),
                children.stream().map(AccessibleNode::name).toList(),
                "tree order is the reading order and does not mirror" + describe(tree()));
        assertTrue(children.get(0).x() > children.get(1).x(), describe(tree()));
        assertTrue(children.get(1).x() > children.get(2).x(),
                "only the coordinates mirror: the first segment is the rightmost, which a hook "
                        + "that walked the edge array as physical x would get right in one "
                        + "direction and silently wrong in the other" + describe(tree()));
        float trackLeft = seg.localToSceneX() + (SCENE_WIDTH - TRACK) / 2;
        assertEquals(trackLeft + TRACK - SEG, children.get(0).x(), EPS, describe(tree()));
    }

    // ------------------------------------------------------------------------------ overflow

    @Test
    void anOverflowingStripPublishesTwoChevronsInTheGuttersAClickLandsIn() {
        bindOverflowing();

        List<AccessibleNode> children = children();
        assertEquals(5, children.size(), describe(tree()));
        assertEquals(List.of(chevronName(true), "A", "B", "C", chevronName(false)),
                children.stream().map(AccessibleNode::name).toList(),
                "reading order: the arrow that scrolls back, the segments, the arrow that scrolls "
                        + "on" + describe(tree()));

        AccessibleNode back = children.get(0);
        AccessibleNode forward = children.get(4);
        assertEquals(Accessible.Role.BUTTON, back.role(), describe(tree()));
        assertEquals(Accessible.Role.BUTTON, forward.role(), describe(tree()));
        assertNull(back.selectionItem(),
                "an arrow is not a member of the selection" + describe(tree()));
        assertEquals(seg.localToSceneX(), back.x(), EPS, describe(tree()));
        assertEquals(GUTTER, back.width(), EPS, describe(tree()));
        assertEquals(seg.localToSceneX() + NARROW - GUTTER, forward.x(), EPS, describe(tree()));
        assertEquals(GUTTER, forward.width(), EPS,
                "the zone the widget's own chevron test maps, not the smaller glyph drawn inside "
                        + "it: the box published is the box a click lands in" + describe(tree()));
        assertEquals(HEIGHT, back.height(), EPS, describe(tree()));

        assertNull(back.actions(),
                "the strip is resting on its first segment, so there is nothing to scroll back "
                        + "to. A dead side carries no verb, because a widget cannot publish a "
                        + "synthetic child disabled by any route: the builder refuses the five "
                        + "states the publish step owns and the walk overwrites the enabled bit "
                        + "with the owner's" + describe(tree()));
        assertEquals(Set.of(Accessible.Action.PRESS), forward.actions().actions(),
                describe(tree()));
    }

    @Test
    void rightToLeftTheTwoChevronsSwapGuttersAndTheOrderHolds() {
        bindOverflowing();
        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        List<AccessibleNode> children = children();
        assertEquals(List.of(chevronName(true), "A", "B", "C", chevronName(false)),
                children.stream().map(AccessibleNode::name).toList(),
                "the arrow that scrolls back is still first in reading order" + describe(tree()));
        assertEquals(seg.localToSceneX() + NARROW - GUTTER, children.get(0).x(), EPS,
                "and it sits in the gutter reading starts from, which is the right one here"
                        + describe(tree()));
        assertEquals(seg.localToSceneX(), children.get(4).x(), EPS, describe(tree()));
    }

    @Test
    void aSegmentScrolledOutOfTheViewportIsVisibleAndNotShowing() {
        bindOverflowing();

        List<AccessibleNode> children = segments();
        assertEquals(3, children.size(),
                "every segment is published whatever the scroll, or the set would renumber "
                        + "itself every time the strip moved" + describe(tree()));
        assertTrue(children.get(0).has(Accessible.State.SHOWING), describe(tree()));
        assertTrue(children.get(1).has(Accessible.State.SHOWING),
                "a partly visible cell is on screen: the negated paint skip is strict, so a cell "
                        + "abutting an edge still shows, which is what the pixels do"
                        + describe(tree()));
        assertTrue(children.get(2).has(Accessible.State.VISIBLE),
                "it is not hidden, it is outside the viewport" + describe(tree()));
        assertFalse(children.get(2).has(Accessible.State.SHOWING), describe(tree()));
        assertTrue(group().has(Accessible.State.SHOWING),
                "the control itself is on screen throughout" + describe(tree()));
        assertEquals(3, children.get(2).selectionItem().positionInSet(), describe(tree()));
    }

    @Test
    void theScrollFacetIsTheViewportOverTheContentAndFollowsTheWidgetsOwnClamp() {
        bindOverflowing();

        assertFacet(new ScrollFacet(0, 0, VIEW_WIDTH / TRACK, 1, true, false),
                group().scroll(),
                "the fraction of the strip the viewport shows, and the offset over the widget's "
                        + "own maximum; computing it from the track instead would overstate how "
                        + "much is on screen by the two gutters" + describe(tree()));

        scene.scrolled(0, -10, seg.localToSceneX() + NARROW / 2, seg.localToSceneY() + HEIGHT / 2);
        scene.inputBatchEnded();
        frame();

        ScrollFacet moved = group().scroll();
        assertTrue(moved.horizontalPercent() > 0,
                "read from the field the wheel moved and not from a cached value"
                        + describe(tree()));
        assertEquals(VIEW_WIDTH / TRACK, moved.horizontalViewSize(), EPS,
                "scrolling moves where the viewport sits, not how big it is" + describe(tree()));
    }

    // -------------------------------------------------------------------------- what it does

    @Test
    void aSelectFromAPlatformThreadTellsTheApplicationExactlyAsAClickDoes() throws Exception {
        bindFitting();
        List<Integer> fired = new ArrayList<>();
        seg.onSelect(fired::add);
        long groupId = group().id();
        AccessibleNode third = segments().get(2);

        assertTrue(perform(third.id(), Accessible.Action.SELECT, Accessible.Argument.NONE),
                "the identifier is in the published tree, so the host accepts it");
        frame();

        assertEquals(List.of(2), fired,
                "the hook reaches the same private choose() a click reaches, so the application "
                        + "is told once and in the same way; anything else would select silently");
        assertEquals(2, seg.selectedIndex());
        List<AccessibleNode> after = segments();
        assertEquals(List.of(after.get(2)), nodesWith(Accessible.State.SELECTED),
                describe(tree()));
        assertEquals(List.of(after.get(2)), nodesWith(Accessible.State.ACTIVE), describe(tree()));
        assertEquals(after.get(2).id(), group().selection().activeDescendant(), describe(tree()));

        List<AccessibleEvent> selections = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.SELECTION_CHANGED) {
                selections.add(event);
            }
        }
        assertFalse(selections.isEmpty(), "moving the selection is a selection change: "
                + bridge.events);
        for (AccessibleEvent event : selections) {
            assertEquals(groupId, event.nodeId(),
                    "raised on the container a platform reads a selection off: " + bridge.events);
        }
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "and the cursor moved once: " + bridge.events);
    }

    @Test
    void selectingTheSegmentThatIsAlreadySelectedIsAcceptedAndTellsNobody() throws Exception {
        bindFitting();
        List<Integer> fired = new ArrayList<>();
        seg.onSelect(fired::add);

        perform(segments().get(0).id(), Accessible.Action.SELECT, Accessible.Argument.NONE);
        frame();

        assertEquals(List.of(), fired,
                "the early return the source marks as what keeps two bound controls from "
                        + "recursing; a select still reveals, which is why it is accepted");
        assertEquals(0, seg.selectedIndex());
        assertEquals(List.of(), eventsFor(group().id()), bridge.events.toString());
    }

    @Test
    void selectingAScrolledAwaySegmentBringsItIntoView() throws Exception {
        bindOverflowing();
        AccessibleNode away = segments().get(2);
        assertFalse(away.has(Accessible.State.SHOWING), describe(tree()));

        perform(away.id(), Accessible.Action.SELECT, Accessible.Argument.NONE);
        frame();

        AccessibleNode now = segments().get(2);
        assertEquals(away.id(), now.id(), "the same node, keyed by an index fixed at construction"
                + describe(tree()));
        assertTrue(now.has(Accessible.State.SHOWING),
                "select reveals through the same pending-reveal the pointer sets, which is why no "
                        + "segment needs a scroll-into-view verb of its own" + describe(tree()));
        assertEquals(1, group().scroll().horizontalPercent(), EPS,
                "and the strip has run to its end" + describe(tree()));
    }

    @Test
    void aChevronPressScrollsByMostOfAViewportAndTheDeadSideDoesNothing() throws Exception {
        bindOverflowing();
        long back = children().get(0).id();
        long forward = children().get(4).id();
        float firstSegmentX = segments().get(0).x();

        perform(back, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();
        assertEquals(0, group().scroll().horizontalPercent(), EPS,
                "the strip is resting on its first segment: the arrow carries no verb and the "
                        + "hook refuses the press, rather than reporting a scroll it clamped away"
                        + describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a refused press is not an invocation: " + bridge.events);
        assertEquals(List.of(), eventsFor(group().id()), bridge.events.toString());

        perform(forward, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        float step = 0.75f * VIEW_WIDTH;
        assertEquals(step / MAX_OFFSET, group().scroll().horizontalPercent(), EPS,
                "three quarters of a viewport, the widget's own chevron step" + describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "and a press that moved something is one: " + bridge.events);
        assertEquals(firstSegmentX - step, segments().get(0).x(), EPS,
                "every segment's box moved with it" + describe(tree()));
        assertTrue(segments().get(2).has(Accessible.State.SHOWING),
                "and the last one came into view" + describe(tree()));

        perform(back, Accessible.Action.PRESS, Accessible.Argument.NONE);
        frame();

        assertEquals(0, group().scroll().horizontalPercent(), EPS,
                "the sign is the arrow's logical identity and the offset is logical too, so the "
                        + "same expression takes it back in both directions" + describe(tree()));
        assertEquals(firstSegmentX, segments().get(0).x(), EPS, describe(tree()));
    }

    @Test
    void everyOtherVerbIsRefusedOnEverySyntheticChild() throws Exception {
        bindOverflowing();
        List<Integer> fired = new ArrayList<>();
        seg.onSelect(fired::add);
        long segment = segments().get(1).id();
        long forward = children().get(4).id();

        for (Accessible.Action action : List.of(Accessible.Action.PRESS,
                Accessible.Action.TOGGLE, Accessible.Action.EXPAND,
                Accessible.Action.SET_VALUE)) {
            perform(segment, action, Accessible.Argument.NONE);
        }
        perform(forward, Accessible.Action.SELECT, Accessible.Argument.NONE);
        frame();

        assertEquals(List.of(), fired,
                "a segment answers select alone, and the branch that scrolls answers press alone: "
                        + "a hook that switched loosely would turn a stray verb into a selection");
        assertEquals(0, seg.selectedIndex());
        assertEquals(0, group().scroll().horizontalPercent(), EPS, describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    @Test
    void aDisabledAncestorRefusesTheSelectBeforeTheHookRuns() throws Exception {
        bindFitting();
        List<Integer> fired = new ArrayList<>();
        seg.onSelect(fired::add);
        long third = segments().get(2).id();
        root.setEnabled(false);
        frame();

        perform(third, Accessible.Action.SELECT, Accessible.Argument.NONE);
        frame();

        assertEquals(List.of(), fired,
                "the scene walks the owner and every ancestor for the enabled flag before the "
                        + "hook is called, which is why the hook carries no guard of its own");
        assertEquals(0, seg.selectedIndex());
    }

    // ------------------------------------------------------------------------- what it costs

    @Test
    void movingThePointerAcrossTheStripPublishesNothing() {
        bindFitting();
        int published = bridge.published.size();
        AccessibleNode node = group();
        float y = node.y() + node.height() / 2;

        for (AccessibleNode child : segments()) {
            scene.mouseMoved(child.x() + child.width() / 2, y);
            scene.inputBatchEnded();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "hover is a pointer affordance and not an accessible fact; publishing it would "
                        + "republish the whole tree on every mouse move: " + bridge.events);
        assertEquals(List.of(), bridge.events, bridge.events.toString());
    }

    @Test
    void anAnimatingIndicatorPublishesOneTree() {
        bindFitting();
        int published = bridge.published.size();

        seg.setSelectedIndex(2);
        for (int i = 0; i < 11; i++) {
            frame();
        }

        assertEquals(published + 1, bridge.published.size(),
                "the pill slides and the focus ring fades over every one of those frames, and "
                        + "each of them damages the control; nothing a reader is told is derived "
                        + "from an animating value, so the tree changes exactly once: "
                        + bridge.events);
    }
}
