package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.scene.layout.Stack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Stack} becomes in the accessible tree, which is nothing, and what survives it
 * being nothing.
 *
 * <p>{@code Stack} is an alignment enum, one private field with a fluent setter, {@code onMeasure}
 * and {@code onLayout}. It declares no role, no name, no action and no state, it overrides no
 * {@code onPaint}, it holds no clip and it is never focusable of its own accord — so ADR 039 §1.6's
 * predicate deletes it in silence and hoists every layer into the stack's own place. That is the
 * whole of the answer and it needs no code in the class.
 *
 * <p>It still needs these tests, because a stack is the one container in the toolkit whose children
 * <em>overlap by construction</em>, and three of the four things the deletion leaves behind are
 * about that overlap.
 *
 * <p><b>Reading order and visual precedence are deliberately opposite here.</b> Children hoist in
 * {@code children()} order, which is add order, which is the order they are painted, bottom layer
 * first; {@code Widget#hitTest} walks the same list backwards so the pointer lands on the top layer.
 * A reader therefore meets the background before the overlay laid over it. That is the intended
 * answer — reading order is tree order and never z-order or geometry, per ADR 032 and §11 — and
 * this is the only place in the toolkit where the two orders disagree, so nothing else would notice
 * a walk "fixed" to publish the top-most layer first.
 *
 * <p><b>Nothing here is occluded, and that is a limit rather than a bug in the walk.</b> A layer
 * that completely covers another subtracts no {@code ENABLED}, no {@code FOCUSABLE}, no
 * {@code VISIBLE} and no {@code SHOWING} from it: §1.13's subtraction is computed from the scene's
 * overlay stack, and everything inside a stack is inside {@code scene.root()} and therefore
 * reachable however many layers sit on top. §1.9's action gate will not refuse a covered control
 * either, because it genuinely is enabled and genuinely inside the layer that owns input. So an
 * application that builds a scrim and a panel inside a {@code Stack} — which the class's own
 * documentation invites, and which the demo's widgets scene does — hands a screen reader user the
 * whole background as operable. The answer is not an occlusion model in {@code Stack}: guessing
 * from geometry which sibling hides which is exactly what §11 rules out for {@code LABELLED_BY},
 * and it would be guessed from rectangles that say nothing about opacity. The answer is
 * {@code Scene#pushOverlay} and {@code Dialog}'s in-scene mounting, which carry {@code MODAL} and
 * §1.13's subtraction; {@link #aCoveredLayerIsStillPublishedOperableAndAnOverlayIsNot()} pins both
 * halves side by side so the difference is written down rather than remembered.
 *
 * <p><b>Transparency here is per instance and not per class</b>, the correction
 * {@code PaddingAccessibilityTest}, {@code RowAccessibilityTest} and {@code ColumnAccessibilityTest}
 * already recorded. Stack is public and non-final and carries the whole of Widget's naming surface,
 * and the walk applies the tooltip name and the application's overrides before it evaluates the
 * predicate, so naming, tooltipping or roling one materialises a {@code GROUP} over the stack's own
 * box — the maximum of its visible children — with every layer unchanged underneath.
 * {@code setFocusable(true)} alone is not a fourth verb: it supplies nothing to publish, so the node
 * survives on the predicate's focusable branch as {@code UNKNOWN} and unnamed, which §12.1 refuses
 * on both counts and the walk warns about.
 *
 * <p>Two things are recorded here and deliberately not asserted. {@code onMeasure} and
 * {@code onLayout} both skip a child whose {@code isVisible()} is false, so a hidden layer is never
 * re-laid and keeps whatever box it last had — or a zero one, if it was hidden before its first
 * layout. That is shared with {@code Row}, {@code Column} and {@code Flex} and is not Stack's to
 * fix, and a later reader should not file the stale rectangle as a bug. And {@code alignment} has no
 * equality guard where {@code Padding#setInsets} has one, so re-setting the same value buys a layout
 * and a walk; that is a layout-cost question rather than an accessibility one, and what belongs here
 * is only that the tree layer absorbs it.
 *
 * <p>Deliberately not re-tested here: the generic predicate, which {@code AccessibleTreeTest} pins;
 * the modal subtraction in general, which {@code AccessibleModalTest} pins; the mirroring switch in
 * layout coordinates, which {@code ContainerMirroringTest} pins; and the bookkeeping that this class
 * is settled as transparent, which is {@code AccessibleCoverageTest}'s own two tests.
 *
 * <p>Everything below drives Stack's public constructor and setters on a bound scene; nothing
 * constructs a node.
 */
class StackAccessibilityTest extends AccessibleTestBase {

    private static Probe probe(String name) {
        return new Probe(Accessible.Role.BUTTON, name);
    }

    /** A layer that fills whatever box it is given, for the tests about covering. */
    private static Probe cover(String name) {
        Probe probe = probe(name);
        probe.prefWidth = 1000;
        probe.prefHeight = 1000;
        probe.setFocusable(true);
        return probe;
    }

    private List<String> readingOrder() {
        List<String> order = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 1; i < tree.nodeCount(); i++) {
            order.add(tree.node(i).name());
        }
        return order;
    }

    /**
     * The deletion itself: a stack of layers is a window and the layers, with no box in between,
     * and <em>every</em> layer hoists rather than one.
     *
     * <p>The wrappers settled before this one held a single child each, so a hoist that re-parented
     * siblings to each other, or a predicate that asked whether the widget has children rather than
     * whether the builder does, would have passed all of them. A stack is where that shows.
     */
    @Test
    void theStackIsNoNodeAndEveryLayerHoistsIntoTheWindow() {
        Stack stack = new Stack();
        stack.add(probe("background"));
        stack.add(probe("panel"));
        stack.add(probe("badge"));
        bind(stack);
        frame();

        AccessibleTree tree = tree();
        assertEquals(4, tree.nodeCount(),
                "a window and three layers, and no stack in between: " + describe(tree));
        for (String name : List.of("background", "panel", "badge")) {
            assertEquals(0, node(name).parent(), name + " hoists into the window's place");
        }
        for (int i = 1; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            assertTrue(node.role() != Accessible.Role.GROUP || !node.name().isEmpty(),
                    "a nameless group between the window and the layers is the scaffolding this "
                            + "widget exists to not publish: " + describe(tree));
        }
    }

    /**
     * The layers read bottom-up, which is the opposite of what the pointer answers, and that is the
     * intended answer rather than an oversight.
     *
     * <p>Reading order is tree order — §11 defines it to equal Tab order, and ADR 032 settles that
     * neither follows geometry — so a stack reads its background before the overlay laid over it
     * while a click at the same point lands on the overlay. Sorting the hoisted children by z-order
     * or by rectangle would make this one container read "correctly" and would put every other
     * container's reader cursor out of step with the keyboard.
     */
    @Test
    void theLayersReadInAddOrderWhichIsTheOppositeOfWhatIsOnTop() {
        Probe background = probe("background");
        Probe panel = probe("panel");
        Probe badge = probe("badge");
        Stack stack = new Stack();
        stack.add(background);
        stack.add(panel);
        stack.add(badge);
        bind(stack);
        frame();

        assertEquals(List.of("background", "panel", "badge"), readingOrder(),
                "tree order is add order: " + describe(tree()));
        assertEquals(node("background").x(), node("badge").x(),
                "and it is not an ordering by geometry, because the layers share an origin");
        assertEquals(node("background").y(), node("badge").y());
        assertSame(badge, stack.hitTest(2, 2),
                "while the pointer answers the last layer added, which reads last");
    }

    /**
     * A layer covered by another is still published enabled, focusable, visible and showing — and
     * the same panel pushed as a scene overlay is not. The contrast is the whole finding.
     *
     * <p>There is no occlusion model in the tree, and this is the honest statement of it rather
     * than a memory. An assistive technology offers the covered control, and §1.9's gate accepts
     * the invocation when it arrives, because the control really is enabled and really is inside
     * the layer that owns input. Anything meant to <em>block</em> what is under it goes through
     * {@code Scene#pushOverlay} or {@code Dialog}'s in-scene mounting, which is what the second half
     * exercises: the same panel, one layer up, takes {@code ENABLED} and {@code FOCUSABLE} away from
     * the background and carries {@code MODAL} itself.
     *
     * <p>An occlusion heuristic added later has to change this test deliberately, which is the point
     * of writing it down.
     */
    @Test
    void aCoveredLayerIsStillPublishedOperableAndAnOverlayIsNot() {
        Probe under = probe("under");
        under.setFocusable(true);
        Stack stack = new Stack();
        stack.add(under);
        stack.add(cover("glass"));
        bind(stack);
        frame();

        AccessibleNode covered = node("under");
        assertTrue(covered.has(Accessible.State.ENABLED),
                "a covered control is operable, and the tree says so: " + describe(tree()));
        assertTrue(covered.has(Accessible.State.FOCUSABLE));
        assertTrue(covered.has(Accessible.State.VISIBLE));
        assertTrue(covered.has(Accessible.State.SHOWING),
                "a stack contributes no clip, so nothing in it is off screen");
        assertEquals(node("glass").x(), covered.x(), "and the glass is over it, not beside it");
        assertEquals(node("glass").y(), covered.y());
        assertTrue(node("glass").width() >= covered.width()
                        && node("glass").height() >= covered.height(),
                "the cover really does cover: " + describe(tree()));
        assertTrue(nodesWith(Accessible.State.MODAL).isEmpty(),
                "and nothing inside the widget tree is modal: " + describe(tree()));

        Group overlay = new Group();
        Probe blocking = probe("dialog");
        blocking.setFocusable(true);
        overlay.add(blocking);
        scene.pushOverlay(overlay);
        frame();

        AccessibleNode behind = node("under");
        assertFalse(behind.has(Accessible.State.ENABLED),
                "the same panel one layer up does subtract, and this is where modality lives: "
                        + describe(tree()));
        assertFalse(behind.has(Accessible.State.FOCUSABLE));
        assertTrue(behind.has(Accessible.State.VISIBLE), "it is still genuinely on screen");
        assertTrue(behind.has(Accessible.State.SHOWING));
        assertEquals(1, nodesWith(Accessible.State.MODAL).size(),
                "exactly one layer owns input: " + describe(tree()));
    }

    /**
     * The alignment outlives the node it belonged to, as each layer's scene origin, and the logical
     * half of it mirrors while the physical half does not.
     *
     * <p>{@code ContainerMirroringTest} pins the two switches in layout coordinates, so this is not
     * their only reader the way Padding's leading inset was. What it adds is that the origin reaches
     * the <em>published</em> rectangle at all: a stack publishes no node, so a walk that reported a
     * hoisted child's box in stack-local coordinates would leave every layered control's reader
     * cursor at the window origin with every layout test in the suite green.
     */
    @Test
    void theAlignmentSurvivesAsEachLayersOriginAndMirrors() {
        Stack stack = new Stack();
        stack.alignment(Stack.Alignment.TOP_RIGHT);
        stack.add(probe("badge"));
        bind(stack);
        frame();

        AccessibleNode badge = node("badge");
        assertEquals(stack.localToSceneX() + stack.width() - badge.width(), badge.x(),
                "TOP_RIGHT names a corner of the box, and the box is the scene's");
        assertEquals(stack.localToSceneY(), badge.y());

        stack.setLayoutDirection(LayoutDirection.RTL);
        frame();

        AccessibleNode physical = node("badge");
        assertEquals(stack.localToSceneX() + stack.width() - physical.width(), physical.x(),
                "a physical constant keeps naming the same corner: " + describe(tree()));
        assertEquals(stack.localToSceneY(), physical.y());

        stack.alignment(Stack.Alignment.CENTER_END);
        frame();

        AccessibleNode logical = node("badge");
        assertEquals(stack.localToSceneX(), logical.x(),
                "END is the left edge reading right to left");
        assertEquals(stack.localToSceneY() + (stack.height() - logical.height()) / 2, logical.y(),
                "and the vertical half of the constant has no reading order to follow");

        stack.setLayoutDirection(LayoutDirection.LTR);
        frame();

        AccessibleNode flipped = node("badge");
        assertEquals(stack.localToSceneX() + stack.width() - flipped.width(), flipped.x(),
                "and it turns around with the direction");
        assertEquals(stack.localToSceneY() + (stack.height() - flipped.height()) / 2, flipped.y());
    }

    /**
     * Re-aligning a deleted stack moves its layers and does not re-key them: a reader holding a
     * control keeps holding it.
     *
     * <p>Identity is the widget's and never the wrapper's placement (§1.3). Keyed off anything the
     * layout touches, moving an overlay from one corner to another would invalidate every element a
     * reader is holding underneath it, in the middle of reading, because of something the user never
     * touched.
     */
    @Test
    void changingTheAlignmentMovesTheLayersWithoutRekeyingThem() {
        Stack stack = new Stack();
        stack.add(probe("background"));
        stack.add(probe("badge"));
        bind(stack);
        frame();
        long background = node("background").id();
        long badge = node("badge").id();
        float wasX = node("badge").x();
        bridge.clear();

        stack.alignment(Stack.Alignment.BOTTOM_RIGHT);
        frame();

        assertTrue(node("badge").x() > wasX, "it moved: " + describe(tree()));
        assertEquals(background, node("background").id(), "identity is not a coordinate");
        assertEquals(badge, node("badge").id());
        assertNotNull(bridge.first(AccessibleEvent.Type.BOUNDS_CHANGED), "" + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was added or removed: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);
    }

    /**
     * The one scaffolding setter in the toolkit with no equality guard, reaching the tree and
     * costing it nothing.
     *
     * <p>{@code alignment} assigns and calls {@code markNeedsLayout()} unconditionally, where
     * {@code Padding#setInsets} and {@code Flex#setGap} compare first, so re-setting the same value
     * buys a layout pass and a walk. The difference is taken by value rather than by dirty flag, so
     * the walk concludes that nothing moved and hands the bridge neither a snapshot nor an event.
     * The guard is worth having for the layout it saves and is not this pipeline's to add; what is
     * pinned here is that its absence never reaches an assistive technology.
     */
    @Test
    void settingTheSameAlignmentPublishesNothing() {
        Stack stack = new Stack();
        stack.alignment(Stack.Alignment.CENTER);
        stack.add(probe("background"));
        stack.add(probe("badge"));
        bind(stack);
        frame();
        bridge.clear();

        stack.alignment(Stack.Alignment.CENTER);
        frame();

        assertTrue(bridge.published.isEmpty(), "no change, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);
    }

    /**
     * Hiding the top layer — which is how an application toggles an overlay it built by hand —
     * keeps its node and takes {@code VISIBLE} and {@code SHOWING} away from it.
     *
     * <p>§1.2's inherited rule, on the widget people actually toggle. The node stays, its identity
     * stays, and its siblings are untouched, because a stack places every child against its own box
     * and has no cursor for a hidden one to disturb. Its rectangle is deliberately not asserted:
     * {@code onMeasure} and {@code onLayout} both skip an invisible child, so the box it keeps is the
     * last one it was given, and a layer hidden before its first layout keeps a zero one. That is
     * shared with {@code Row}, {@code Column} and {@code Flex} and is not Stack's to fix.
     */
    @Test
    void hidingALayerKeepsItsNodeAndTakesAwayVisibleAndShowing() {
        Probe overlay = probe("badge");
        Stack stack = new Stack();
        stack.add(probe("background"));
        stack.add(overlay);
        bind(stack);
        frame();
        long id = node("badge").id();
        float backgroundX = node("background").x();
        bridge.clear();

        overlay.setVisible(false);
        frame();

        AccessibleNode gone = node("badge");
        assertFalse(gone.has(Accessible.State.VISIBLE),
                "hidden is a state, not a removal: " + describe(tree()));
        assertFalse(gone.has(Accessible.State.SHOWING));
        assertEquals(id, gone.id(), "and not a new element either");
        assertEquals(backgroundX, node("background").x(),
                "a stack has no cursor, so hiding a layer moves nothing");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);
    }

    /**
     * The escape hatch: an application that wants the stack named gets a node for it, over the
     * stack's own box, with no code in Stack and no new identity for anything it holds — and a
     * tooltip is a name, so it gets one that way too.
     *
     * <p>The walk applies the tooltip and the application's overrides before it evaluates the
     * predicate, which is why transparency here is a default rather than a property of the class and
     * why there is no {@code NamedStack} subclass and none is wanted.
     */
    @Test
    void namingAStackMaterialisesItsOwnBoxAndRekeysNothing() {
        Stack stack = new Stack();
        stack.add(probe("background"));
        stack.add(probe("badge"));
        bind(stack);
        frame();
        long background = node("background").id();

        stack.setAccessibleName("Player");
        frame();

        AccessibleNode named = node("Player");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(stack.localToSceneX(), named.x(), "the stack's own box");
        assertEquals(stack.localToSceneY(), named.y());
        assertEquals(stack.width(), named.width());
        assertEquals(stack.height(), named.height());
        assertEquals(tree().indexOf(named.id()), node("background").parent(), describe(tree()));
        assertEquals(tree().indexOf(named.id()), node("badge").parent(),
                "every layer goes under it, and none under another: " + describe(tree()));
        assertEquals(background, node("background").id(),
                "naming the stack is not renaming the layers");

        stack.setAccessibleName((String) null);
        frame();

        assertEquals(3, tree().nodeCount(), "and it goes away again: " + describe(tree()));
        assertEquals(background, node("background").id());

        stack.setTooltip("Playback");
        frame();

        AccessibleNode tooltipped = node("Playback");
        assertEquals(Accessible.Role.GROUP, tooltipped.role());
        assertEquals(Accessible.NameFrom.TOOLTIP, tooltipped.nameFrom());
        assertEquals(4, tree().nodeCount(), describe(tree()));
        assertEquals(background, node("background").id(),
                "and a tooltip is not a rebuild either");
    }

    /**
     * Focusing one is not the fourth verb, and this is what it really does.
     *
     * <p>The three that are verbs each supply something the node can be published as.
     * {@code setFocusable} supplies nothing: the stack survives the predicate on the focusable
     * branch alone, with no role and no name, so the walk publishes {@code UNKNOWN} and warns. §12.1
     * refuses both of those, so what this pins is a half-materialised node the application still owes
     * a role and a name — and it materialises above every layer, which is what makes it worth
     * catching here rather than nowhere.
     */
    @Test
    void focusingAStackAloneMaterialisesANodeThatIsNotYetSayable() {
        Stack stack = new Stack();
        stack.add(probe("background"));
        stack.add(probe("badge"));
        bind(stack);
        frame();

        stack.setFocusable(true);
        frame();

        AccessibleNode unknown = null;
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).role() == Accessible.Role.UNKNOWN) {
                unknown = tree().node(i);
            }
        }
        assertNotNull(unknown,
                "it is not deleted -- the predicate's focusable branch keeps it" + describe(tree()));
        assertEquals("", unknown.name(),
                "but nothing named it, and focus is not a name" + describe(tree()));
        assertEquals(tree().indexOf(unknown.id()), node("background").parent(),
                "and the layers are underneath it: " + describe(tree()));

        stack.setAccessibleRole(Accessible.Role.GROUP);
        stack.setAccessibleName("Player");
        frame();

        assertEquals(Accessible.Role.GROUP, node("Player").role(),
                "the two verbs that do supply something are what finish it" + describe(tree()));
    }
}
