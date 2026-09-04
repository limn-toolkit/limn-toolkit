package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.scene.layout.Column;
import limn.scene.layout.Flex;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Column} becomes in the accessible tree, which is nothing, and what survives it
 * being nothing.
 *
 * <p>{@code Column} is eight lines around {@code Flex}, and neither declares a role, a name, an
 * action or a state, neither overrides {@code onPaint}, neither clips, and neither is ever
 * focusable of its own accord. So ADR 039 §1.6's predicate deletes a column in silence — the
 * paints-and-says-nothing warning does not fire, which is what separates it from
 * {@code BackdropPanel} — and hoists its children into its parent's place. That is the whole of
 * the answer and it needs no code in the class.
 *
 * <p>It still needs these tests, because the deletion re-attributes a column's geometry rather
 * than removing it. The gap survives as the distance between consecutive sibling boxes, the main
 * alignment as the first child's origin, and the cross alignment as each child's horizontal origin
 * and, under {@code STRETCH}, its width. §7's "the tree is the controls, not the boxes" is right in
 * its verdict and misleading in its framing: nothing about a column's geometry is deleted, and the
 * accessible tree is its second reader after paint.
 *
 * <p><b>A column is not a row seen sideways, which is why it has a step of its own.</b> They are
 * one class body parameterised by a boolean, and the boolean picks which axis mirrors. On a row the
 * <em>main</em> axis reflects; on a column the <em>cross</em> axis does, and a column is the only
 * exerciser of that expression in the toolkit. The consequence for a reader is the one thing worth
 * carrying away from this file: a column's tree order is always visual top to bottom, in every
 * direction, while every child's {@code x} turns around. A sweep that reflected the wrong axis, or
 * flattened the cross placement back to a physical left, would pin a whole form's labels to the
 * left inside a right-to-left interface and leave every assertion in this suite green except these.
 *
 * <p><b>A column is not a viewport.</b> {@code Flex.onMeasure} clamps the column's own size through
 * its constraints and keeps advancing the cursor, and {@code Flex} does not override
 * {@code clipsChildren}, so an over-subscribed column publishes child boxes outside its own
 * rectangle and still {@code SHOWING}. That is the honest answer and it is §7.2's "every row's
 * bounds claim" settled for this widget. It is also the opposite of {@code PopupMenu}'s private
 * inner {@code Column}, which is not a {@link Widget} at all and does clip, scroll and hold a
 * highlight: the survey uses the bare name for both and never disambiguates, so its later
 * {@code Column} rows describe a scrolled viewport this class does not have.
 *
 * <p><b>Transparency here is per instance and not per class</b>, the correction
 * {@code PaddingAccessibilityTest} and {@code RowAccessibilityTest} already recorded. Column is
 * public and non-final and carries the whole of Widget's naming surface, and the walk applies the
 * tooltip name and the application's overrides before it evaluates the predicate, so naming,
 * tooltipping or roling one materialises a {@code GROUP} over the column's own rectangle with every
 * child unchanged underneath. That is the supported way to ask for a named region, and it is the
 * case §7's flat "transparent" cell does not admit. {@code setFocusable(true)} alone is not a
 * fourth verb: it survives the predicate on the focusable branch with nothing to publish, which is
 * a defect the walk warns about and not a hatch.
 *
 * <p>Deliberately not re-tested here: the generic predicate, which {@code AccessibleTreeTest} pins;
 * the paints-and-says-nothing seam, which {@code AccessiblePaintWarningTest} pins and for which
 * this widget is the silent case rather than the warned one; the resolution of a relation past a
 * deleted target, which {@code AccessibleRelationTest} pins with its own probe; and Flex's
 * measure-count budget, which {@code FlexMeasureCountTest} pins. The bookkeeping — that this class
 * is settled as transparent and that {@code Flex} above it stays hookless — is
 * {@code AccessibleCoverageTest}'s own two tests and is not duplicated here.
 *
 * <p>Everything below drives Column's public constructor and setters on a bound scene; nothing
 * constructs a node.
 */
class ColumnAccessibilityTest extends AccessibleTestBase {

    private static Probe probe(String name) {
        return new Probe(Accessible.Role.BUTTON, name);
    }

    private static Probe wide(String name, float width) {
        Probe probe = probe(name);
        probe.prefWidth = width;
        return probe;
    }

    /** Binds {@code column} inside a box of exactly this size, for the tests that need one. */
    private void bindSized(float width, float height, Column column) {
        Group box = new Group();
        box.add(new SizedBox(width, height, column));
        bind(box);
        frame();
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
     * The deletion itself: a column of controls is a window and the controls, with no grouping box
     * in between.
     *
     * <p>Anything that gave {@code Flex} a role, a state or a describe hook would re-materialise
     * such a box between the window and very nearly every control in the toolkit, and it would do
     * so by inheritance, without a line of it appearing in this class.
     */
    @Test
    void theColumnIsNoNodeAndItsChildrenHoistIntoTheWindow() {
        Column column = new Column();
        column.add(probe("a"));
        column.add(probe("b"));
        column.add(probe("c"));
        bind(column);
        frame();

        AccessibleTree tree = tree();
        assertEquals(4, tree.nodeCount(),
                "a window and three buttons, and no stack in between: " + describe(tree));
        for (String name : List.of("a", "b", "c")) {
            assertEquals(0, node(name).parent(), name + " hoists into the window's place");
        }
        for (int i = 1; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            assertTrue(node.role() != Accessible.Role.GROUP || !node.name().isEmpty(),
                    "a nameless group between the window and the controls is the scaffolding this "
                            + "widget exists to not publish: " + describe(tree));
        }
    }

    /**
     * Reading order is placement order, and on a column the two cannot come apart.
     *
     * <p>Children hoist in {@code children()} order, which is the order they are placed along the
     * main axis and the order {@code Scene}'s focusable collection produces, so §11's "reading
     * order equals Tab order" holds here by construction. Nothing in {@code Flex} reorders for a
     * column: the one caller that moves a child after placement is the baseline sweep, and it is
     * guarded on the horizontal case. A hoist that walked the children backwards would put a
     * reader's cursor out of step with the keyboard, which §11 settles as the tree being wrong.
     */
    @Test
    void readingOrderIsPlacementOrderAndSurvivesTheHoist() {
        Column column = new Column();
        column.add(probe("a"));
        column.add(probe("b"));
        column.add(probe("c"));
        bind(column);
        frame();

        assertEquals(List.of("a", "b", "c"), readingOrder());
        assertTrue(node("a").y() < node("b").y() && node("b").y() < node("c").y(),
                "and it is the order down the screen: " + describe(tree()));
    }

    /**
     * The gap outlives the node it belonged to, as the distance between two sibling boxes, and the
     * main alignment outlives it as the first child's origin.
     *
     * <p>This is the Padding-insets invariant in its {@code Flex} form: the container's own
     * rectangle never reaches the tree, so the only place its spacing can still be read is the
     * published coordinates of the controls it held.
     */
    @Test
    void theGapSurvivesAsTheDistanceBetweenSiblingBoxes() {
        Column column = new Column();
        column.gap(12);
        column.add(probe("a"));
        column.add(probe("b"));
        column.add(probe("c"));
        bind(column);
        frame();

        assertEquals(column.localToSceneY(), node("a").y(),
                "START puts the first child against the column's own leading edge");
        assertEquals(12f, node("b").y() - (node("a").y() + node("a").height()),
                "the gap is the distance between the boxes: " + describe(tree()));
        assertEquals(12f, node("c").y() - (node("b").y() + node("b").height()));
    }

    /**
     * Re-gapping a deleted column moves its children and does not re-key them: a reader holding a
     * button keeps holding it.
     *
     * <p>Identity keyed off anything the layout touches would invalidate every element a reader
     * holds the moment a token-driven container re-gapped itself on a size-step change.
     */
    @Test
    void changingTheGapMovesTheChildrenWithoutRekeyingThem() {
        Column column = new Column();
        column.add(probe("a"));
        column.add(probe("b"));
        column.add(probe("c"));
        bind(column);
        frame();
        long a = node("a").id();
        long b = node("b").id();
        long c = node("c").id();
        bridge.clear();

        column.gap(24);
        frame();

        assertEquals(44f, node("b").y(), "it moved");
        assertEquals(a, node("a").id(), "identity is not a coordinate");
        assertEquals(b, node("b").id());
        assertEquals(c, node("c").id());
        assertNotNull(bridge.first(AccessibleEvent.Type.BOUNDS_CHANGED), "" + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was added or removed: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);
    }

    /**
     * {@code Flex.setGap}'s equality guard, reaching the tree.
     *
     * <p>The guard is there so that a container deriving its gap from resolved size tokens inside
     * its own measure pass does not loop; the same guard is what stops that container republishing
     * the whole snapshot every frame to say that nothing moved. This is the one case in this file
     * that touches the allocation rule, and the direct analogue of
     * {@code PaddingAccessibilityTest.anInsetsSetToTheSameValuePublishesNothing}.
     */
    @Test
    void aGapSetToTheValueItAlreadyHadPublishesNothing() {
        Column column = new Column();
        column.gap(16);
        column.add(probe("a"));
        column.add(probe("b"));
        bind(column);
        frame();
        bridge.clear();

        column.gap(16);
        frame();

        assertTrue(bridge.published.isEmpty(), "no change, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);
    }

    /**
     * A column's cross axis mirrors and its reading order does not, which is the whole of what
     * distinguishes this widget from a row.
     *
     * <p>{@code ContainerMirroringTest} already pins the expression at the layout level, so this is
     * not its only reader the way Padding's leading inset was. What it adds is that the deletion
     * carries the mirrored origin all the way through to the published box: a column publishes no
     * node, so the reflection reaches an assistive technology only as the {@code x} of every control
     * underneath, and a reader whose cursor is drawn on the wrong side of the window is how the
     * failure is experienced.
     */
    @Test
    void aColumnsCrossAxisMirrorsAndItsReadingOrderDoesNot() {
        Column column = new Column();
        column.crossAlignment(Flex.CrossAlignment.START);
        column.add(wide("a", 40));
        column.add(wide("b", 60));
        column.add(wide("c", 80));
        bind(column);
        frame();

        assertEquals(List.of("a", "b", "c"), readingOrder());
        assertEquals(0f, node("a").x(), "START is the edge reading starts on");
        assertEquals(0f, node("b").x());
        assertEquals(0f, node("c").x());
        float[] tops = {node("a").y(), node("b").y(), node("c").y()};

        column.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(List.of("a", "b", "c"), readingOrder(),
                "a column's main axis is not a site, so tree order is untouched");
        assertEquals(column.width() - 40, node("a").x(), "and its cross axis is");
        assertEquals(column.width() - 60, node("b").x(),
                "each child is placed against the edge, not boxed: " + describe(tree()));
        assertEquals(column.width() - 80, node("c").x());
        assertEquals(tops[0], node("a").y(), "a cross-axis reflection moves nothing vertically");
        assertEquals(tops[1], node("b").y());
        assertEquals(tops[2], node("c").y());
    }

    /**
     * Hiding a child closes the gap around it and leaves its node in the tree, published without
     * {@code VISIBLE} and without {@code SHOWING}.
     *
     * <p>{@code Flex} skips an invisible child in measure, in layout and in the gap count, which
     * invites the wrong inference that it is gone. It is gone from the layout only: the walk still
     * publishes a node for it, and the state rather than the geometry is what says it is not on
     * screen. Its coordinates are deliberately not asserted, because the box it keeps is the last
     * one it was given and a later reader should not file that stale rectangle as a bug.
     */
    @Test
    void aHiddenChildKeepsItsNodeAndItsSiblingsCloseTheGap() {
        Probe hidden = probe("b");
        Column column = new Column();
        column.gap(10);
        column.add(probe("a"));
        column.add(hidden);
        column.add(probe("c"));
        bind(column);
        frame();

        assertEquals(0f, node("a").y());
        assertEquals(30f, node("b").y());
        assertEquals(60f, node("c").y());
        bridge.clear();

        hidden.setVisible(false);
        frame();

        assertEquals(0f, node("a").y(), "the first is still against the leading edge");
        assertEquals(30f, node("c").y(),
                "and the third moves up by the hidden one's height and one gap");
        AccessibleNode gone = node("b");
        assertTrue(!gone.has(Accessible.State.VISIBLE) && !gone.has(Accessible.State.SHOWING),
                "hidden is a state, not a removal: " + describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);
    }

    /**
     * An over-subscribed column publishes child boxes outside its own rectangle, and they are still
     * {@code SHOWING}, because a column is not a viewport.
     *
     * <p>{@code onMeasure} clamps the column's own size through its constraints and the placement
     * cursor keeps walking, so the last children are laid out past the bottom edge; {@code Flex}
     * does not clip, so {@code isShowing()} finds no clipping ancestor to answer no for. The
     * published boxes agree with the painted ones, which is the truthful answer and the one §7.2's
     * bounds-claim item asks for. Clamping them back inside the box would put a reader's cursor
     * where the control is not, and copying the menu column's scroll-and-clip rule onto this class
     * would describe a viewport it does not have.
     */
    @Test
    void anOverflowingColumnPublishesChildBoxesOutsideItsOwnRectangleAndStillShowing() {
        Column column = new Column();
        column.gap(5);
        for (String name : List.of("a", "b", "c", "d", "e")) {
            column.add(probe(name));
        }
        bindSized(200, 50, column);

        assertEquals(50f, column.height(), "the column itself was clamped to the box");
        AccessibleNode last = node("e");
        assertEquals(100f, last.y(), "and the cursor kept walking past the bottom edge");
        assertTrue(last.y() + last.height() > column.localToSceneY() + column.height(),
                "the overflowing child's box lies outside the column's own: " + last.bounds());
        assertTrue(last.has(Accessible.State.SHOWING),
                "a column does not clip, so nothing here is hidden: " + describe(tree()));
        assertTrue(last.width() >= 0 && last.height() >= 0,
                "a negative rectangle is unrecoverable at the bridge: " + last.bounds());
    }

    /**
     * The escape hatch: an application that wants the column named gets a node for it, over the
     * column's own box, with no code in Column and no new identity for anything it holds — and a
     * tooltip is a name, so it gets one that way too.
     *
     * <p>The walk applies the tooltip and the application's overrides before it evaluates the
     * predicate, which is why transparency here is a default rather than a property of the class
     * and why there is no {@code NamedColumn} subclass and none is wanted.
     */
    @Test
    void namingAColumnMaterialisesItsOwnBoxAndRekeysNothing() {
        Column column = new Column();
        column.add(probe("a"));
        column.add(probe("b"));
        bind(column);
        frame();
        long a = node("a").id();

        column.setAccessibleName("Sidebar");
        frame();

        AccessibleNode named = node("Sidebar");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(column.localToSceneX(), named.x(), "the column's own box");
        assertEquals(column.localToSceneY(), named.y());
        assertEquals(column.width(), named.width());
        assertEquals(column.height(), named.height());
        AccessibleNode inside = node("a");
        assertEquals(tree().indexOf(named.id()), inside.parent(), describe(tree()));
        assertEquals(tree().indexOf(named.id()), node("b").parent());
        assertEquals(a, inside.id(), "naming the column is not renaming the buttons");

        column.setAccessibleName((String) null);
        frame();

        assertEquals(3, tree().nodeCount(), "and it goes away again: " + describe(tree()));
        assertEquals(a, node("a").id());

        column.setTooltip("Filters");
        frame();

        AccessibleNode tooltipped = node("Filters");
        assertEquals(Accessible.Role.GROUP, tooltipped.role());
        assertEquals(Accessible.NameFrom.TOOLTIP, tooltipped.nameFrom());
        assertEquals(4, tree().nodeCount(), describe(tree()));
        assertEquals(a, node("a").id(), "and a tooltip is not a rebuild either");
    }
}
