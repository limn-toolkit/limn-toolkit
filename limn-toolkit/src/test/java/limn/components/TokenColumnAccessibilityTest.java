package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link TokenColumn} becomes in the accessible tree, which is nothing, and what the deletion
 * leaves behind, which is the one thing this class adds to {@code Column}: where the gap comes
 * from.
 *
 * <p>The class is one field and one {@code onMeasure} over {@code Column}, which is a constructor
 * over abstract {@code Flex}. Nothing in that chain declares a role, a name, a description, an
 * action or a state, nothing overrides {@code onPaint} — so the paints-and-says-nothing warning
 * stays silent — nothing clips, nothing sets a tooltip and nothing is focusable of its own accord.
 * So ADR 039 §1.6's predicate deletes a token column in silence and hoists its children into its
 * parent's place, in {@code children()} order, and the class needs no accessibility code.
 *
 * <p>It still needs these tests, because a column's geometry is re-attributed by the deletion
 * rather than removed — the gap survives as the vertical distance between consecutive sibling
 * boxes — and on this class that gap is derived and not held. {@code Tokens.spacingFor} is resolved
 * inside {@code onMeasure} against the step in force with a parent in place, and pushed through
 * {@code Flex}'s silent form so it is in force for the layout of the very same pass. The
 * consequence, which {@code ColumnAccessibilityTest} cannot pin because it is false on the
 * superclass: the public {@code Flex.gap(float)} is not a literal here. It requests a layout pass
 * whose measure step overwrites the value with the token again, so an application's number never
 * reaches the tree, and a size-step change reaches a reader as every child having moved, with no
 * application code and nothing torn down. §7's cell has no way to say that the gap is derived
 * rather than held, and its flat "transparent" reads as a property of the class when it is a
 * default per instance: naming, tooltipping or roling one materialises a {@code GROUP} over the
 * column's own rectangle with the children unchanged underneath and nothing re-keyed.
 *
 * <p>Deliberately not re-tested here, because none of it is this class's — it reads neither
 * {@code layoutDirection()} nor visibility, and it does not clip: the cross-axis mirror, the hidden
 * child closing its gap, and the over-subscribed column publishing boxes outside its own rectangle
 * and still {@code SHOWING}, all pinned on the superclass by {@code ColumnAccessibilityTest}; the
 * generic predicate ({@code AccessibleTreeTest}); the paints-and-says-nothing seam
 * ({@code AccessiblePaintWarningTest}, for which this class is the silent case); and the
 * bookkeeping that this class, {@code Column} and {@code Flex} stay hookless, which is
 * {@code AccessibleCoverageTest}'s own two tests. What is left to this class is the half that is
 * its own: the gap being the token, pushed from inside {@code onMeasure}, tracking the step.
 *
 * <p>Everything below drives public constructors and setters on a bound scene and reads the tree
 * the scene published; nothing constructs a node and nothing calls a hook.
 */
class TokenColumnAccessibilityTest extends AccessibleComponentTestBase {

    /** {@code spacingMedium} at the default {@link ControlSize#MEDIUM} step. */
    private static final float MEDIUM_AT_MEDIUM = 12;

    /** The same token at the ends of the ramp. */
    private static final float MEDIUM_AT_XSMALL = 6;
    private static final float MEDIUM_AT_XLARGE = 20;

    /** The other two roles at the default step, so a test confusing the roles fails. */
    private static final float SMALL_AT_MEDIUM = 6;
    private static final float LARGE_AT_MEDIUM = 20;

    /** Every leaf below is this tall, so a published y is a count of leaves and gaps. */
    private static final float LEAF_HEIGHT = 40;

    /**
     * A leaf with a fixed preferred size and an application-supplied name: something inside the
     * column that survives the predicate, so the gap the column leaves behind has two nodes to be
     * read between.
     */
    private static final class Box extends Widget<Box> {
        Box(String name) {
            setAccessibleName(name);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(60, LEAF_HEIGHT);
        }
    }

    private static TokenColumn columnOf(Tokens.Role role, String... names) {
        TokenColumn column = new TokenColumn(role);
        for (String name : names) {
            column.add(new Box(name));
        }
        return column;
    }

    private List<String> readingOrder() {
        List<String> order = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 1; i < tree.nodeCount(); i++) {
            order.add(tree.node(i).name());
        }
        return order;
    }

    /** @return the distance between the first named box's bottom edge and the second's top */
    private float gapBetween(String upper, String lower) {
        AccessibleNode above = node(upper);
        return node(lower).y() - (above.y() + above.height());
    }

    /**
     * The column publishes no node at all and its children hang directly under the window.
     *
     * <p>A hook landing on {@code TokenColumn}, on {@code Column} or on {@code Flex} — even an
     * empty one — would re-materialise a nameless group between the window and very nearly every
     * control in the toolkit, and it would do so by inheritance, without a line of it appearing in
     * this class.
     */
    @Test
    void theColumnIsNoNodeAndItsChildrenHoistIntoTheWindow() {
        bind(columnOf(Tokens.Role.MEDIUM, "a", "b", "c"));

        AccessibleTree tree = tree();
        assertEquals(4, tree.nodeCount(),
                "a window and three boxes, and no column in between: " + describe(tree));
        for (String name : List.of("a", "b", "c")) {
            assertEquals(0, node(name).parent(), name + " hoists into the column's own place");
        }
        for (int i = 1; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            assertTrue(node.role() != Accessible.Role.GROUP || !node.name().isEmpty(),
                    "a nameless group is what a token column must never publish: "
                            + describe(tree));
        }
    }

    /**
     * The distance between sibling boxes is the resolved token, at both ends of the ramp, and an
     * application's literal cannot override it.
     *
     * <p>The gap is pushed by {@code gapSilently} from inside {@code onMeasure}, so it is in force
     * for the layout of the very same pass and the published box is never a frame behind. Pushing
     * it after layout instead would publish the previous frame's gap; swapping in the invalidating
     * {@code gap(float)} would re-enter layout from inside a measure pass; dropping the push would
     * freeze whatever literal the application last set. Each is invisible on screen for a frame
     * and permanently wrong in the tree.
     *
     * <p>The step is driven through the scene and the widget, never {@code setProcessDefault},
     * which is a static global that would leak into every other test in the run.
     */
    @Test
    void theGapIsTheResolvedTokenAndNotAnAppsLiteral() {
        TokenColumn column = columnOf(Tokens.Role.MEDIUM, "a", "b");
        bind(column);

        assertEquals(MEDIUM_AT_MEDIUM, gapBetween("a", "b"), "spacingMedium at the default step");

        scene.setControlSize(ControlSize.XSMALL);
        frame();
        assertEquals(MEDIUM_AT_XSMALL, gapBetween("a", "b"),
                "the step reaches the tree with no app code");

        scene.setControlSize(ControlSize.XLARGE);
        frame();
        assertEquals(MEDIUM_AT_XLARGE, gapBetween("a", "b"));

        column.setControlSize(ControlSize.MEDIUM);
        frame();
        assertEquals(MEDIUM_AT_MEDIUM, gapBetween("a", "b"),
                "a step declared on the widget itself wins over the scene's");

        column.gap(100);
        frame();
        assertEquals(MEDIUM_AT_MEDIUM, gapBetween("a", "b"),
                "the measure pass overwrites an app's literal, so the tree follows the token");
    }

    /**
     * The role picks the token, and no two roles are the same number at the default step.
     *
     * <p>Three columns share one scene inside a plain {@link Column}, which is transparent too and
     * carries no gap of its own, so all six boxes hoist into the window and each column's gutter
     * reads straight off its own pair. The outer column hands each inner one a loose height, which
     * is the shape a token column has in a real form, where the tight root constraint of the other
     * tests is not.
     */
    @Test
    void theRolePicksTheToken() {
        Column outer = new Column();
        outer.add(columnOf(Tokens.Role.SMALL, "tight-a", "tight-b"));
        outer.add(columnOf(Tokens.Role.LARGE, "wide-a", "wide-b"));
        TokenColumn plain = new TokenColumn();
        plain.add(new Box("plain-a"));
        plain.add(new Box("plain-b"));
        outer.add(plain);
        bind(outer);

        assertEquals(SMALL_AT_MEDIUM, gapBetween("tight-a", "tight-b"), "spacingSmall");
        assertEquals(LARGE_AT_MEDIUM, gapBetween("wide-a", "wide-b"), "spacingLarge");
        assertEquals(MEDIUM_AT_MEDIUM, gapBetween("plain-a", "plain-b"),
                "the no-argument constructor is the MEDIUM role");
        assertNotEquals(SMALL_AT_MEDIUM, MEDIUM_AT_MEDIUM);
        assertNotEquals(MEDIUM_AT_MEDIUM, LARGE_AT_MEDIUM);
    }

    /**
     * A density change moves the children and destroys nothing.
     *
     * <p>The column is not in the tree, so there is no node to add or remove and nothing for
     * identity to be minted over: a step change is a bounds change on the children and no more.
     * The alternative — a screen reader experiencing the whole page as torn down and rebuilt
     * because the user changed a density preference — is the failure this pins. The quiet frame
     * after it is the second half: a walk that allocated or compared something fresh per frame
     * would keep talking after the move.
     */
    @Test
    void aStepChangeMovesTheChildrenAndDestroysNothing() {
        bind(columnOf(Tokens.Role.MEDIUM, "a", "b", "c"));
        long a = node("a").id();
        long b = node("b").id();
        long c = node("c").id();
        float bWasAt = node("b").y();
        bridge.events.clear();

        scene.setControlSize(ControlSize.XLARGE);
        frame();

        assertEquals(a, node("a").id(), "identity is not the step");
        assertEquals(b, node("b").id());
        assertEquals(c, node("c").id());
        assertEquals(bWasAt + (MEDIUM_AT_XLARGE - MEDIUM_AT_MEDIUM), node("b").y(),
                "the second moved down by the difference between the two steps' tokens");
        assertTrue(bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED) > 0,
                "the move is what a reader is told about: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was added or removed: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);

        bridge.events.clear();
        frame();

        assertTrue(bridge.events.isEmpty(),
                "a quiet frame after the step says nothing: " + bridge.events);
    }

    /**
     * An overwritten literal and a step that resolves to the number already in force each publish
     * nothing, through two different silences.
     *
     * <p>{@code gap(100)} is a real change at the setter: it marks layout dirty and buys a full
     * pass, the measure step re-pushes the token, every box lands where it was, and it is the
     * publish step's no-change early return that keeps the reader quiet. A scene that held no step
     * of its own and is handed {@code MEDIUM} is the same shape — the scene's field starts unset
     * and falls through to the default, so this is a real set that changes no number, not the
     * guard. Handing the scene a step it already holds is the guard itself, and never lays out at
     * all. A token-driven container republishing the whole snapshot to say nothing moved is
     * {@code AccessiblePublishCostTest}'s failure in miniature, and any of the three would be it.
     */
    @Test
    void anOverwrittenLiteralAndARepeatedStepPublishNothing() {
        TokenColumn column = columnOf(Tokens.Role.MEDIUM, "a", "b");
        bind(column);
        bridge.published.clear();

        column.gap(100);
        frame();
        assertTrue(bridge.published.isEmpty(), "the walk found nothing moved, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and said nothing: " + bridge.events);

        scene.setControlSize(ControlSize.MEDIUM);
        frame();
        assertTrue(bridge.published.isEmpty(),
                "a first step that resolves to the default's number moves nothing");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        scene.setControlSize(ControlSize.XLARGE);
        frame();
        bridge.published.clear();
        bridge.events.clear();

        scene.setControlSize(ControlSize.XLARGE);
        frame();
        assertTrue(bridge.published.isEmpty(), "the step the scene already holds is a no-op");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    /**
     * Reading order is placement order, and on a column the two cannot come apart.
     *
     * <p>Children hoist in {@code children()} order, which is the order they are placed down the
     * main axis and the order the scene's focus collection produces, so §11's "reading order
     * equals Tab order" holds here by construction. A hoist that walked the children backwards
     * would put a reader's cursor out of step with the keyboard, which §11 settles as the tree
     * being wrong.
     */
    @Test
    void readingOrderIsPlacementOrderAndSurvivesTheHoist() {
        bind(columnOf(Tokens.Role.MEDIUM, "1", "2", "3", "4"));

        assertEquals(List.of("1", "2", "3", "4"), readingOrder());
        for (int i = 1; i < 4; i++) {
            String upper = Integer.toString(i);
            String lower = Integer.toString(i + 1);
            assertTrue(node(upper).y() < node(lower).y(),
                    "and it is the order down the screen: " + describe(tree()));
            assertEquals(MEDIUM_AT_MEDIUM, gapBetween(upper, lower),
                    "with the token between every adjacent pair");
        }
    }

    /**
     * The escape hatch: naming an instance materialises it as a group over the column's own box,
     * with the children unchanged underneath at their token-sized gutter, and re-keys nothing —
     * and a tooltip is a name, so it gets one that way too.
     *
     * <p>Identity is minted over the widget tree and never over the published one, which is what
     * makes this safe: an application labelling a region must not invalidate every identifier a
     * client is holding inside it. This is also what keeps a future "TokenColumn is always
     * deleted" shortcut honest: the walk applies the application's overrides before it evaluates
     * the predicate, and a shortcut that skipped them would swallow the name.
     */
    @Test
    void namingTheColumnMaterialisesItsOwnBoxAndRekeysNothing() {
        TokenColumn column = columnOf(Tokens.Role.MEDIUM, "a", "b");
        bind(column);
        long a = node("a").id();
        long b = node("b").id();

        column.setAccessibleName("Sidebar");
        frame();

        AccessibleNode named = node("Sidebar");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(column.localToSceneX(), named.x(), "the column's own box");
        assertEquals(column.localToSceneY(), named.y());
        assertEquals(column.width(), named.width());
        assertEquals(column.height(), named.height());
        assertEquals(tree().indexOf(named.id()), node("a").parent(), describe(tree()));
        assertEquals(tree().indexOf(named.id()), node("b").parent());
        assertEquals(a, node("a").id(), "naming the region is not renaming what is in it");
        assertEquals(b, node("b").id());
        assertEquals(MEDIUM_AT_MEDIUM, gapBetween("a", "b"),
                "and the children still sit at the token gutter inside it");

        column.setAccessibleName((String) null);
        frame();

        assertEquals(3, tree().nodeCount(), "and it goes away again: " + describe(tree()));
        assertEquals(a, node("a").id());
        assertEquals(b, node("b").id());

        column.setTooltip("Filters");
        frame();

        AccessibleNode tooltipped = node("Filters");
        assertEquals(Accessible.Role.GROUP, tooltipped.role());
        assertEquals(Accessible.NameFrom.TOOLTIP, tooltipped.nameFrom());
        assertEquals(4, tree().nodeCount(), describe(tree()));
        assertEquals(a, node("a").id(), "and a tooltip is not a rebuild either");
    }
}
