package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Padding} becomes in the accessible tree, which is nothing, and what survives it
 * being nothing.
 *
 * <p>Padding declares no role, no name, no action and no state of its own, and it paints nothing,
 * so ADR 039 §1.6's predicate deletes it in silence and hoists its child into its place. That is
 * the whole of the answer and it needs no code in the class. It still needs these tests, because
 * everything interesting about the deletion is what it does <em>not</em> delete: the insets do not
 * vanish with the node, they survive as the child's origin, and under a right-to-left subtree that
 * origin is {@code insets.right()} rather than {@code insets.left()}. The accessible tree is the
 * second reader of the class's one mirroring expression, after paint, and the first that could
 * catch it being flattened back to {@code left()} with every layout test still green.
 *
 * <p><b>Transparency here is per instance and not per class.</b> Unlike a private pane that can
 * never acquire a tooltip, Padding is public and non-final and carries the whole of Widget's naming
 * surface, so an application that names, tooltips or roles one gets a node: a {@code GROUP} over the
 * outer, padded rectangle, with its child unchanged underneath. That is the intended escape hatch
 * for an application that wants a named region and does not want a wrapper class for it, and the
 * last tests are what keep a future "Padding is always deleted" shortcut honest.
 *
 * <p><b>Focusing one is not a fourth verb.</b> Those three each supply something the node can be
 * published as; {@code setFocusable} supplies nothing, so the widget survives on the predicate's
 * focusable branch alone and is published as {@code UNKNOWN} with no name, which §12.1 refuses on
 * both counts and the walk warns about. It materialises a node, but one the application still owes
 * a role and a name.
 *
 * <p>Two things deliberately are not re-tested here, because they are the mechanism's rather than
 * this widget's: the generic predicate, which {@code AccessibleTreeTest} pins, and the resolution of
 * a relation whose target was deleted, which {@code AccessibleRelationTest} pins with its own probe.
 * That second one matters to Padding — §1.11 names it as a plausible popup anchor — and the
 * deletion does not break it, since the walk resolves a target upwards to the nearest published
 * ancestor. It is covered where it belongs and not duplicated here.
 *
 * <p>Everything below drives Padding's public constructor and setters on a bound scene; nothing
 * constructs a node.
 */
class PaddingAccessibilityTest extends AccessibleTestBase {

    /** Distinct on all four sides, so a test that confuses two of them fails. */
    private static final Insets ASYMMETRIC = new Insets(4, 30, 8, 10);

    @Test
    void thePaddingIsNoNodeAndItsChildHoistsIntoTheWindow() {
        Padding padding = new Padding(ASYMMETRIC, new Probe(Accessible.Role.BUTTON, "ok"));
        bind(padding);
        frame();

        AccessibleTree tree = tree();
        assertEquals(2, tree.nodeCount(),
                "a window and a button, and no box in between: " + describe(tree));
        assertEquals(0, node("ok").parent(), "the button hoists into the window's place");
        for (int i = 1; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            assertTrue(node.role() != Accessible.Role.GROUP || !node.name().isEmpty(),
                    "a nameless group between the window and the control is the scaffolding this "
                            + "widget exists to not publish: " + describe(tree));
        }
    }

    @Test
    void scaffoldingDepthDoesNotReachTheTree() {
        Widget root = new Probe(Accessible.Role.BUTTON, "ok");
        for (int i = 0; i < 10; i++) {
            root = new Padding(ASYMMETRIC, root);
        }
        bind(root);
        frame();

        assertEquals(2, tree().nodeCount(),
                "ten wrappers are still one control: " + describe(tree()));
    }

    /**
     * The insets outlive the node they belonged to, as the child's laid-out origin, and they mirror.
     *
     * <p>{@code leadingInset()} is private and its only other reader is {@code onLayout}, so a
     * simplification back to {@code insets.left()} would leave a mirrored interface's reader cursor
     * on the wrong side of every padded control with nothing else failing.
     */
    @Test
    void theInsetsSurviveAsTheChildsBoxAndMirror() {
        Padding padding = new Padding(ASYMMETRIC, new Probe(Accessible.Role.BUTTON, "ok"));
        bind(padding);
        frame();

        AccessibleNode child = node("ok");
        assertEquals(padding.localToSceneX() + 10, child.x(), "the left inset, left to right");
        assertEquals(padding.localToSceneY() + 4, child.y());
        assertEquals(padding.width() - 40, child.width());
        assertEquals(padding.height() - 12, child.height());

        padding.setLayoutDirection(LayoutDirection.RTL);
        frame();

        AccessibleNode mirrored = node("ok");
        assertEquals(padding.localToSceneX() + 30, mirrored.x(),
                "mirrored, the content starts from the right inset");
        assertEquals(padding.localToSceneY() + 4, mirrored.y(), "and the vertical insets do not");
        assertEquals(padding.width() - 40, mirrored.width());
        assertEquals(padding.height() - 12, mirrored.height());
    }

    /**
     * Insets wider than the box hand the tree a zero-area rectangle and never a negative one.
     *
     * <p>Both clamps in {@code onLayout} are load-bearing from here: a negative width is not an
     * exception anywhere in Java, and it reaches UIA, AT-SPI or NSAccessibility as a rectangle no
     * platform has an answer for. Worth recording rather than pinning: such a node still publishes
     * {@code SHOWING}, because {@code isShowing()} only answers no under an ancestor that clips and
     * Padding does not clip. That is a question for the clip-owning widgets' own steps.
     */
    @Test
    void aContentBoxSmallerThanTheInsetsIsNeverNegative() {
        Padding padding = new Padding(Insets.all(150), new Probe(Accessible.Role.BUTTON, "ok"));
        bind(padding);
        frame();

        AccessibleNode child = node("ok");
        assertTrue(child.width() >= 0 && child.height() >= 0,
                "a negative rectangle is unrecoverable at the bridge: " + child.bounds());
        assertEquals(0, child.width());
        assertEquals(0, child.height());
        assertEquals(padding.localToSceneX() + 150, child.x());
        assertEquals(padding.localToSceneY() + 150, child.y());
    }

    /**
     * Re-laying a deleted wrapper moves its child and does not re-key it: a reader holding the
     * control keeps holding it.
     */
    @Test
    void changingTheInsetsMovesTheChildWithoutRekeyingIt() {
        Padding padding = new Padding(ASYMMETRIC, new Probe(Accessible.Role.BUTTON, "ok"));
        bind(padding);
        frame();
        long before = node("ok").id();
        bridge.clear();

        padding.setInsets(Insets.all(24));
        frame();

        assertEquals(before, node("ok").id(), "the child's identity is not the wrapper's insets");
        assertNotNull(bridge.first(AccessibleEvent.Type.BOUNDS_CHANGED),
                "it moved: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was added or removed: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);
    }

    /**
     * Padding's own equality guard, reaching the tree. It is there so that a token-driven subclass
     * re-applying its insets on every measure does not loop; the same guard is what stops such a
     * subclass republishing the whole tree sixty times a second to say nothing changed.
     */
    @Test
    void anInsetsSetToTheSameValuePublishesNothing() {
        Padding padding = new Padding(Insets.all(24), new Probe(Accessible.Role.BUTTON, "ok"));
        bind(padding);
        frame();
        bridge.clear();

        padding.setInsets(Insets.all(24));
        frame();

        assertTrue(bridge.published.isEmpty(), "no change, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);
    }

    /**
     * The escape hatch: an application that wants the region named gets a node for it, over the
     * outer box, with no code in Padding and no new identity for what it wraps.
     */
    /**
     * Focusing one is not the fourth escape hatch, and this is what it really does.
     *
     * <p>The other three verbs supply something the node can be published as. {@code setFocusable}
     * supplies nothing: the widget survives the predicate through the focusable branch alone, with
     * no role and no name to its name, so the walk publishes {@code UNKNOWN} and warns. §12.1
     * refuses both of those -- no {@code UNKNOWN} role anywhere, every focusable node named -- so
     * what this pins is a half-materialised node that an application still owes a role and a name,
     * and not a supported way to ask for a focusable region.
     */
    @Test
    void focusingAPaddingAloneMaterialisesANodeThatIsNotYetSayable() {
        Padding padding = new Padding(ASYMMETRIC, new Probe(Accessible.Role.BUTTON, "ok"));
        bind(padding);
        frame();

        padding.setFocusable(true);
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

        padding.setAccessibleRole(Accessible.Role.GROUP);
        padding.setAccessibleName("Sidebar");
        frame();

        AccessibleNode named = node("Sidebar");
        assertEquals(Accessible.Role.GROUP, named.role(),
                "the two verbs that do supply something are what finish it" + describe(tree()));
    }

    @Test
    void namingAPaddingMaterialisesTheOuterBoxAndRekeysNothing() {
        Padding padding = new Padding(ASYMMETRIC, new Probe(Accessible.Role.BUTTON, "ok"));
        bind(padding);
        frame();
        long child = node("ok").id();

        padding.setAccessibleName("Sidebar");
        frame();

        AccessibleNode named = node("Sidebar");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(padding.localToSceneX(), named.x(), "the outer, padded box, not the content's");
        assertEquals(padding.localToSceneY(), named.y());
        assertEquals(padding.width(), named.width());
        assertEquals(padding.height(), named.height());
        AccessibleNode inside = node("ok");
        assertTrue(inside.x() > named.x() && inside.y() > named.y()
                        && inside.x() + inside.width() < named.x() + named.width()
                        && inside.y() + inside.height() < named.y() + named.height(),
                "the gutter is the difference between the two boxes: " + describe(tree()));
        assertEquals(tree().indexOf(named.id()), inside.parent());
        assertEquals(child, inside.id(), "naming the wrapper is not renaming the control");

        padding.setAccessibleName((String) null);
        frame();

        assertEquals(2, tree().nodeCount(), "and it goes away again: " + describe(tree()));
        assertEquals(child, node("ok").id());
    }

    /**
     * A tooltip is a name, and the walk applies it before the predicate is evaluated, so a
     * tooltipped Padding is a node. This is the concrete case the survey's row does not admit and
     * the reason its "transparent" cell is a default rather than a property of the class.
     */
    @Test
    void aTooltipOnAPaddingIsANodeToo() {
        Padding padding = new Padding(ASYMMETRIC, new Probe(Accessible.Role.BUTTON, "ok"));
        bind(padding);
        frame();

        padding.setTooltip("Filters");
        frame();

        AccessibleNode named = node("Filters");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(Accessible.NameFrom.TOOLTIP, named.nameFrom());
        assertEquals(3, tree().nodeCount(), describe(tree()));
    }
}
