package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Row} becomes in the accessible tree, which is nothing, and what survives it being
 * nothing.
 *
 * <p>{@code Row} is ten lines around {@code Flex}, and neither of them declares a role, a name, an
 * action or a state, neither overrides {@code onPaint}, neither clips, and neither is ever focusable
 * of its own accord. So ADR 039 §1.6's predicate deletes a row in silence and hoists its children
 * into its parent's place, and the whole answer needs no code in the class.
 *
 * <p>It still needs these tests, because the deletion leaves behind the one thing that matters
 * most: <b>a row publishes no node and a row is the boxes</b>. The main-axis reflection, the split
 * between the physical and the logical alignment vocabularies, the resolved flex share and the
 * baseline sweep are the published {@code x} and {@code width} of every control underneath, and the
 * accessible tree is their second reader after paint. A row whose mirroring expression was
 * flattened would hand a screen reader every control's box on the wrong side of a mirrored window,
 * with the row itself still correctly absent and every layout test still green. §7's "the tree is
 * the controls, not the boxes" invites the opposite conclusion; the geometry is exactly what needs
 * checking, and §7.2's own open item — every row's bounds claim — is the same observation from the
 * other side.
 *
 * <p><b>A row and a column are not interchangeable here</b>, though the survey lists them in one
 * cell. They are one class body parameterised by a boolean, and the boolean picks which axis
 * mirrors: on a row the main axis reflects, so the first child ends up rightmost; on a column the
 * cross axis does. {@code LEFT} and {@code RIGHT} are two behaviours distinct from
 * {@code START}/{@code END} on a row and collapse onto them on a column, and {@code BASELINE} runs a
 * second geometry sweep on a row and is ignored on a column. Neither one's evidence transfers, so
 * {@code Column} keeps its own step.
 *
 * <p><b>Transparency here is per instance and not per class</b>, the same correction
 * {@code PaddingAccessibilityTest} recorded. Row is public and non-final and carries the whole of
 * Widget's naming surface, and the walk applies the tooltip name and the application's overrides
 * before it evaluates the predicate, so naming, tooltipping or roling one materialises a node over
 * the row's own rectangle with every child unchanged underneath. That is the supported way to ask
 * for a named region or a {@code TOOL_BAR} over a button strip, and it is why there is no such
 * subclass and none is wanted.
 *
 * <p>Deliberately not re-tested here: the generic predicate, which {@code AccessibleTreeTest} pins,
 * and the mechanism-level claim that reading order is tree order, which
 * {@code AccessibleGeometryTest} pins against a hand-written strip that copies the reflection. This
 * file re-asks that one against the real expression.
 *
 * <p>Everything below drives Row's public setters on a bound scene; nothing constructs a node.
 */
class RowAccessibilityTest extends AccessibleTestBase {

    /** A probe whose baseline sits above its bottom edge, the way a control carrying text does. */
    private static final class Ascent extends Probe {
        private final float baseline;

        Ascent(String name, float height, float baseline) {
            super(Accessible.Role.BUTTON, name);
            this.prefHeight = height;
            this.baseline = baseline;
        }

        @Override
        protected float baselineOffset() {
            return baseline;
        }
    }

    private static Probe probe(String name, float width) {
        Probe probe = new Probe(Accessible.Role.BUTTON, name);
        probe.prefWidth = width;
        return probe;
    }

    private static Probe tall(String name, float height) {
        Probe probe = new Probe(Accessible.Role.BUTTON, name);
        probe.prefHeight = height;
        return probe;
    }

    /** Binds {@code row} inside a box of exactly this size, for the tests that need one. */
    private void bindSized(float width, float height, Row row) {
        Group box = new Group();
        box.add(new SizedBox(width, height, row));
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

    @Test
    void theRowIsNoNodeAndItsChildrenHoistIntoTheWindow() {
        Row row = new Row();
        row.add(probe("first", 40));
        row.add(probe("second", 40));
        row.add(probe("third", 40));
        bind(row);
        frame();

        AccessibleTree tree = tree();
        assertEquals(4, tree.nodeCount(),
                "a window and three buttons, and no strip in between: " + describe(tree));
        for (String name : List.of("first", "second", "third")) {
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
     * The shape a real form produces: rows inside rows, with a flex wrapper somewhere in the chain.
     * Every one of them is deleted, so depth in the widget tree is not depth in the published one.
     */
    @Test
    void scaffoldingDepthDoesNotReachTheTree() {
        Widget<?> inner = probe("ok", 40);
        for (int i = 0; i < 9; i++) {
            Row wrapper = new Row();
            wrapper.add(inner);
            inner = wrapper;
        }
        Row root = new Row();
        root.add(Expanded.of(inner));
        bind(root);
        frame();

        assertEquals(2, tree().nodeCount(),
                "ten rows and a flex wrapper are still one control: " + describe(tree()));
        assertEquals(0, node("ok").parent());
    }

    /**
     * The main-axis reflection, re-asked against {@code Flex.onLayout} itself.
     *
     * <p>In a mirrored interface the tree order is unchanged and the boxes run the other way, so
     * the first child has the largest {@code x}. A tree sorted on {@code x} would be right in one
     * direction and backwards in the other, which a screen reader user experiences as the interface
     * being read out of order. Flattening the placement to the bare cursor, or computing the
     * reflection off the wrong axis, leaves every layout assertion in the suite green.
     */
    @Test
    void mirroringMovesTheBoxesAndNotTheReadingOrder() {
        Row row = new Row();
        row.add(probe("first", 40));
        row.add(probe("second", 40));
        row.add(probe("third", 40));
        bind(row);
        frame();

        assertEquals(List.of("first", "second", "third"), readingOrder());
        assertEquals(0f, node("first").x());
        assertEquals(40f, node("second").x());
        assertEquals(80f, node("third").x());

        row.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(List.of("first", "second", "third"), readingOrder(),
                "reading order is logical and does not turn around");
        assertEquals(160f, node("first").x(), "and the boxes are physical, so they do");
        assertEquals(120f, node("second").x());
        assertEquals(80f, node("third").x());
        assertEquals(40f, node("first").width(), "a reflection is not a resize");
        assertEquals(40f, node("third").width());
    }

    /**
     * The two alignment vocabularies, which pair up two-by-two in each direction and are four
     * distinct behaviours on a row.
     *
     * <p>{@code START} and {@code END} name where reading starts and ends and turn around with the
     * subtree; {@code LEFT} and {@code RIGHT} name a side of the box and keep naming it. The cursor
     * is a logical distance that the placement reflects, so the physical arms ask for the logical
     * end that reflects onto the side they name — and collapsing them to a bare {@code 0} and
     * {@code free} merges the two vocabularies the enum's own javadoc goes to some length to keep
     * apart, with nothing else in the suite noticing.
     */
    @Test
    void thePhysicalAlignmentsKeepNamingASideAndTheLogicalOnesTurnAround() {
        Row row = new Row();
        row.add(probe("one", 40));
        bind(row);
        frame();

        row.mainAlignment(Flex.MainAlignment.START);
        frame();
        assertEquals(0f, node("one").x(), "START is where reading starts");
        row.setLayoutDirection(LayoutDirection.RTL);
        frame();
        assertEquals(160f, node("one").x(), "which is the right edge in a mirrored subtree");

        row.mainAlignment(Flex.MainAlignment.END);
        frame();
        assertEquals(0f, node("one").x(), "END turns around with it");
        row.setLayoutDirection(LayoutDirection.LTR);
        frame();
        assertEquals(160f, node("one").x());

        row.mainAlignment(Flex.MainAlignment.LEFT);
        frame();
        assertEquals(0f, node("one").x(), "LEFT names a side of the box");
        row.setLayoutDirection(LayoutDirection.RTL);
        frame();
        assertEquals(0f, node("one").x(), "and keeps naming it whichever way the subtree reads");

        row.mainAlignment(Flex.MainAlignment.RIGHT);
        frame();
        assertEquals(160f, node("one").x(), "and so does RIGHT");
        row.setLayoutDirection(LayoutDirection.LTR);
        frame();
        assertEquals(160f, node("one").x());
    }

    /**
     * The resolved share is literally the box the tree publishes, because the share is a tight
     * constraint and {@link Expanded} lays its child out at its own full size.
     *
     * <p>{@code resolveFlexShares} is the single copy shared by measure and layout on purpose. If
     * the two resolved the split separately again, a reader would be handed a rectangle the control
     * is not drawn under — the published box and the painted box are the same box here only because
     * a row neither clips nor transforms.
     */
    @Test
    void theResolvedShareIsTheBoxTheTreePublishes() {
        Probe narrow = probe("a", 40);
        Probe wide = probe("b", 40);
        Row row = new Row();
        row.add(Expanded.of(narrow));
        row.add(Expanded.of(wide, 3));
        bind(row);
        frame();

        assertEquals(0f, node("a").x());
        assertEquals(50f, node("a").width(), "one part in four of 200, not the 40 it asked for");
        assertEquals(50f, node("b").x());
        assertEquals(150f, node("b").width(), "three parts in four");
    }

    /**
     * Floors that cannot all fit over-subscribe the row, and the tree says so rather than tidying
     * it away.
     *
     * <p>Three hundred points of declared floor in a two-hundred-point row is {@code atLeast}'s
     * documented outcome: every child freezes at its floor in the first round, the pool goes
     * negative, and the children paint past the container's edge. The published boxes agree with
     * the painted ones, which is the truthful answer; a "fix" that clamped the shares back inside
     * the box would put a reader's cursor where the control is not.
     *
     * <p>Recorded rather than pinned: a child pushed past the <em>scene's</em> edge still publishes
     * {@code SHOWING}. {@code isShowing()} walks clipping ancestors and not the viewport, and a
     * {@link Flex} does not clip, so an over-subscribed row is the most likely producer of that.
     * It is the walk's question and not this widget's.
     */
    @Test
    void floorsThatDoNotFitOverflowIntoTheTreeAndNeverGoNegative() {
        Row row = new Row();
        row.add(Expanded.of(probe("a", 40)).atLeast(100));
        row.add(Expanded.of(probe("b", 40)).atLeast(100));
        row.add(Expanded.of(probe("c", 40)).atLeast(100));
        bind(row);
        frame();

        float[] expectedX = {0f, 100f, 200f};
        String[] names = {"a", "b", "c"};
        for (int i = 0; i < names.length; i++) {
            AccessibleNode node = node(names[i]);
            assertEquals(expectedX[i], node.x(), names[i] + " freezes at its floor");
            assertEquals(100f, node.width());
            assertTrue(node.width() >= 0 && node.height() >= 0,
                    "a negative rectangle is unrecoverable at the bridge: " + node.bounds());
            assertTrue(node.has(Accessible.State.SHOWING),
                    "a row does not clip, so nothing here is hidden: " + describe(tree()));
        }
    }

    /**
     * Hiding a child re-divides the leftovers among the survivors, and the hidden one is still a
     * node — one a reader is holding — published without {@code VISIBLE} and without
     * {@code SHOWING}.
     *
     * <p>Its coordinates are deliberately not asserted: {@code Flex.onLayout} skips an invisible
     * child entirely, so the box it keeps is the one it last had. The state and not the geometry is
     * what says it is gone, and a later reader should not file the stale rectangle as a bug.
     */
    @Test
    void hidingAChildRespacesTheSurvivorsAndTheHiddenOneIsPublishedNotVisible() {
        Probe hidden = probe("b", 35);
        Row row = new Row();
        row.mainAlignment(Flex.MainAlignment.SPACE_BETWEEN);
        row.add(probe("a", 35));
        row.add(hidden);
        row.add(probe("c", 35));
        row.add(probe("d", 35));
        bind(row);
        frame();

        assertEquals(0f, node("a").x());
        assertEquals(110f, node("c").x());
        assertEquals(165f, node("d").x());
        bridge.clear();

        hidden.setVisible(false);
        frame();

        assertEquals(0f, node("a").x(), "the first is still against the edge reading starts from");
        assertEquals(82.5f, node("c").x(), "and the gap the hidden one left is re-divided");
        assertEquals(165f, node("d").x(), "the last is still against the other edge");
        AccessibleNode gone = node("b");
        assertTrue(!gone.has(Accessible.State.VISIBLE) && !gone.has(Accessible.State.SHOWING),
                "hidden is a state, not a removal: " + describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);
    }

    /**
     * The baseline sweep runs after every box is set, and the tree reads the boxes afterwards.
     *
     * <p>The three cross alignments give three different answers for the same two children, so a
     * test that confused them would fail. The clamp is the second half: a row too short for the
     * shift stops short of perfect alignment rather than pushing a child out of its parent's
     * rectangle, and paint is the clamp's only other reader.
     */
    @Test
    void aBaselineRowShiftsTheChildrenAfterTheirBoxesAreSet() {
        Row row = new Row();
        row.crossAlignment(Flex.CrossAlignment.BASELINE);
        row.add(tall("shallow", 20));
        row.add(tall("deep", 30));
        bindSized(200, 30, row);

        assertEquals(10f, node("shallow").y(), "shifted down onto the deepest baseline");
        assertEquals(0f, node("deep").y());

        row.crossAlignment(Flex.CrossAlignment.START);
        frame();
        assertEquals(0f, node("shallow").y(), "START aligns boxes and not text");
        assertEquals(0f, node("deep").y());

        row.crossAlignment(Flex.CrossAlignment.CENTER);
        frame();
        assertEquals(5f, node("shallow").y(), "and CENTER is a third answer again");
        assertEquals(0f, node("deep").y());
    }

    /** The clamp: a shift that would leave the box is given up rather than published. */
    @Test
    void aBaselineShiftIsClampedInsideTheRowsOwnBox() {
        Row row = new Row();
        row.crossAlignment(Flex.CrossAlignment.BASELINE);
        row.add(new Ascent("shallow", 20, 18));
        row.add(new Ascent("deep", 30, 10));
        bindSized(200, 30, row);

        assertEquals(0f, node("deep").y(),
                "eight points of shift would put its bottom edge outside the row");
        for (String name : List.of("shallow", "deep")) {
            AccessibleNode node = node(name);
            assertTrue(node.y() >= 0f && node.y() + node.height() <= 30f,
                    name + " is outside its parent's rectangle: " + node.bounds());
        }
    }

    /**
     * Re-laying a deleted row moves its children and does not re-key them: a reader holding a
     * button keeps holding it. A direction flip is the strongest form, because it moves every child
     * at once.
     */
    @Test
    void movingAChildDoesNotRekeyIt() {
        Row row = new Row();
        row.add(probe("first", 40));
        row.add(probe("second", 40));
        row.add(probe("third", 40));
        bind(row);
        frame();
        long first = node("first").id();
        long third = node("third").id();
        bridge.clear();

        row.gap(24);
        frame();

        assertEquals(64f, node("second").x(), "it moved");
        assertEquals(first, node("first").id(), "identity is not a coordinate");
        assertEquals(third, node("third").id());
        assertNotNull(bridge.first(AccessibleEvent.Type.BOUNDS_CHANGED), "" + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was added or removed: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), "" + bridge.events);
        bridge.clear();

        row.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(first, node("first").id(), "a direction flip is a move and not a rebuild");
        assertEquals(third, node("third").id());
        assertNotNull(bridge.first(AccessibleEvent.Type.BOUNDS_CHANGED), "" + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED), "" + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), "" + bridge.events);
    }

    /**
     * Two setters given the values they already hold, and the two different ways the tree stays
     * quiet.
     *
     * <p>{@code gap} carries an equality guard, written so that a token-driven subclass re-applying
     * its gap inside a measure pass does not loop; the same guard is what stops such a subclass
     * republishing the whole tree sixty times a second to say nothing changed. {@code mainAlignment}
     * has no such guard and gets there the expensive way: it buys a full layout pass and a full
     * tree walk, and it is the publish step's no-change early return rather than the setter that
     * keeps the reader quiet. A wasted frame is a layout concern; what this pins is that neither
     * one costs a snapshot or an event.
     */
    @Test
    void aSetterGivenTheValueItAlreadyHoldsPublishesNothing() {
        Row row = new Row();
        row.gap(8);
        row.add(probe("first", 40));
        row.add(probe("second", 40));
        bind(row);
        frame();
        bridge.clear();

        row.gap(8);
        frame();

        assertTrue(bridge.published.isEmpty(), "no change, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        row.mainAlignment(Flex.MainAlignment.START);
        frame();

        assertTrue(bridge.published.isEmpty(), "the walk found nothing moved");
        assertTrue(bridge.events.isEmpty(), "and said nothing: " + bridge.events);
    }

    /**
     * The escape hatch: an application that wants the strip named, described or given a role gets a
     * node for it, over the row's own box, with no code in Row and no new identity for anything it
     * holds.
     */
    @Test
    void namingARowMaterialisesTheOuterBoxAndRekeysNothing() {
        Row row = new Row();
        row.add(probe("cut", 40));
        row.add(probe("copy", 40));
        bind(row);
        frame();
        long cut = node("cut").id();

        row.setAccessibleName("Actions");
        frame();

        AccessibleNode named = node("Actions");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(row.localToSceneX(), named.x(), "the row's own box");
        assertEquals(row.localToSceneY(), named.y());
        assertEquals(row.width(), named.width());
        assertEquals(row.height(), named.height());
        AccessibleNode inside = node("cut");
        assertEquals(tree().indexOf(named.id()), inside.parent(), describe(tree()));
        assertEquals(cut, inside.id(), "naming the strip is not renaming the buttons");

        row.setAccessibleName((String) null);
        frame();

        assertEquals(3, tree().nodeCount(), "and it goes away again: " + describe(tree()));
        assertEquals(cut, node("cut").id());
    }

    /**
     * A tooltip is a name, and the walk applies it before it evaluates the predicate, so a
     * tooltipped row is a node. This is the case §7's "transparent" cell does not admit, and the
     * reason it is a default rather than a property of the class.
     */
    @Test
    void aTooltipOnARowIsANodeToo() {
        Row row = new Row();
        row.add(probe("cut", 40));
        bind(row);
        frame();

        row.setTooltip("Actions");
        frame();

        AccessibleNode named = node("Actions");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(Accessible.NameFrom.TOOLTIP, named.nameFrom());
        assertEquals(3, tree().nodeCount(), describe(tree()));
    }

    /**
     * How an application asks for a toolbar over a button strip: a role and a name on the row it
     * already has. Pinned so that nobody answers the same question with a {@code Toolbar} subclass
     * or a hook on {@code Flex}, either of which would be inherited by every row in the toolkit.
     */
    @Test
    void aRolledRowIsHowAToolBarIsAskedFor() {
        Row row = new Row();
        row.add(probe("cut", 40));
        row.add(probe("copy", 40));
        bind(row);
        frame();

        row.setAccessibleRole(Accessible.Role.TOOL_BAR);
        row.setAccessibleName("Edit");
        frame();

        AccessibleNode bar = node("Edit");
        assertEquals(Accessible.Role.TOOL_BAR, bar.role());
        assertEquals(tree().indexOf(bar.id()), node("cut").parent(), describe(tree()));
        assertEquals(tree().indexOf(bar.id()), node("copy").parent());
    }

    /**
     * Focusing a row alone is not a fourth verb, and this is what it really does.
     *
     * <p>The other three each supply something the node can be published as. {@code setFocusable}
     * supplies nothing: the row survives the predicate through the focusable branch alone, with no
     * role and no name to its name, so the walk publishes {@code UNKNOWN} and warns. §12.1 refuses
     * both of those, so what this pins is a half-materialised node the application still owes a
     * role and a name — not a supported way to ask for a focusable region.
     */
    @Test
    void focusingARowAloneMaterialisesANodeThatIsNotYetSayable() {
        Row row = new Row();
        row.add(probe("cut", 40));
        bind(row);
        frame();

        row.setFocusable(true);
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
    }
}
