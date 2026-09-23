package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
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
 * What a {@link TokenRow} becomes in the accessible tree, which is nothing, and what the deletion
 * leaves behind, which is the one number this class exists to contribute: the distance between
 * every pair of sibling controls, resolved from the spacing token rather than held as a literal.
 *
 * <p>The class is one field and one {@code onMeasure} over {@code Row}, which is a constructor over
 * abstract {@code Flex}. Nothing in that chain declares a role, a name, a description, an action or
 * a state, nothing overrides {@code onPaint} — so the paints-and-says-nothing warning stays silent —
 * nothing clips, nothing sets a tooltip and nothing is focusable of its own accord. So ADR 039
 * §1.6's predicate deletes a token row in silence and hoists its children into its parent's place,
 * and the class needs no accessibility code.
 *
 * <p>It still needs these tests, because a row is the boxes, and on this class the gap between the
 * boxes is derived and not held. {@code Tokens.spacingFor} is resolved inside {@code onMeasure}
 * against the step in force with a parent in place, and pushed through {@code Flex}'s silent form
 * so it is in force for the layout of the very same pass. The consequence, which
 * {@code RowAccessibilityTest} cannot pin because it is false on the superclass: the public
 * {@code Flex.gap(float)} is not a literal here. It requests a layout pass whose measure step
 * overwrites the value with the token again, so an application's literal never reaches the tree,
 * and {@code row.gap(24)} moving the second child — a true statement about {@code Row} — is not
 * true of a {@code TokenRow}. §7's cell has no way to say that the gap is derived rather than held.
 *
 * <p><b>The deletion is not geometrically a no-op</b>, which separates this widget from
 * {@code TokenBox} in the same cell: what survives is a gap and an alignment, and under a
 * right-to-left subtree the gap reflects with the boxes while reading order does not. The gap is
 * applied only between visible children, so hiding one removes its gutter as well as its width.
 *
 * <p><b>Transparency here is per instance and not per class</b>, and it can be overridden from
 * either side. From below, an application that names, tooltips or roles an instance materialises a
 * {@code GROUP} over the row's own rectangle with the children unchanged underneath and nothing
 * re-keyed. From above, the walk asks a widget's parent to add what only it knows before it
 * evaluates the predicate, so a token row used as a pooled list cell is given its role, its
 * selection and its key by the list's own hook and survives; that is the list's step and needs
 * nothing here. The same fact cuts the other way: a dialog or a toolbar wrapping controls in a
 * token row cannot annotate them through {@code onAccessibilityChild}, because the walk asks the
 * row, and the row declares nothing. Those controls describe themselves.
 *
 * <p>Deliberately not re-tested here, because none of it is this class's: the alignment
 * vocabularies, the flex shares, the floors, the baseline sweep and its clamp
 * ({@code RowAccessibilityTest}); the generic predicate ({@code AccessibleTreeTest}); tooltip-as-name
 * and focus-alone-is-not-a-verb ({@code RowAccessibilityTest}, {@code TokenBoxAccessibilityTest}).
 * What is left to this class is the half that is its own: the gap being derived, pushed from inside
 * {@code onMeasure}, and counted over visible children only.
 *
 * <p>Everything below drives public constructors and setters on a bound scene and reads the tree
 * the scene published; nothing constructs a node and nothing calls a hook.
 */
class TokenRowAccessibilityTest extends AccessibleComponentTestBase {

    /** {@code spacingMedium} at the default {@link ControlSize#MEDIUM} step. */
    private static final float MEDIUM_AT_MEDIUM = 12;

    /** The same token at the ends of the ramp. */
    private static final float MEDIUM_AT_XSMALL = 6;
    private static final float MEDIUM_AT_XLARGE = 20;

    /** The other two roles at the default step, so a test confusing the roles fails. */
    private static final float SMALL_AT_MEDIUM = 6;
    private static final float LARGE_AT_MEDIUM = 20;

    /** Every leaf below is this wide, so a published x is a count of leaves and gaps. */
    private static final float LEAF_WIDTH = 60;

    /**
     * A leaf with a fixed preferred size and an application-supplied name: something inside the
     * row that survives the predicate, so the gap the row leaves behind has two nodes to be read
     * between.
     */
    private static final class Box extends Widget<Box> {
        Box(String name) {
            setAccessibleName(name);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(LEAF_WIDTH, 40);
        }
    }

    private static TokenRow rowOf(Tokens.Role role, String... names) {
        TokenRow row = new TokenRow(role);
        for (String name : names) {
            row.add(new Box(name));
        }
        return row;
    }

    private List<String> readingOrder() {
        List<String> order = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 1; i < tree.nodeCount(); i++) {
            order.add(tree.node(i).name());
        }
        return order;
    }

    /** @return the distance between the first named box's right edge and the second's left */
    private float gapBetween(String first, String second) {
        AccessibleNode left = node(first);
        return node(second).x() - (left.x() + left.width());
    }

    /**
     * The row publishes no node at all and its children hang directly under the window.
     *
     * <p>A hook landing on {@code TokenRow}, on {@code Row} or on {@code Flex} — even an empty one —
     * would re-materialise a nameless group between every control strip and its parent, the
     * scaffolding §1.6 exists to delete, and cost a reader one level of nesting per strip.
     */
    @Test
    void theRowIsNoNodeAndItsChildrenHoistIntoItsPlace() {
        bind(rowOf(Tokens.Role.MEDIUM, "first", "second"));

        AccessibleTree tree = tree();
        assertEquals(3, tree.nodeCount(),
                "a window and two boxes, and no strip in between: " + describe(tree));
        assertEquals(0, node("first").parent(), "the first hoists into the row's own place");
        assertEquals(0, node("second").parent(), "and so does the second");
        for (int i = 1; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            assertTrue(node.role() != Accessible.Role.GROUP || !node.name().isEmpty(),
                    "a nameless group is what a token row must never publish: " + describe(tree));
        }
    }

    /**
     * The distance between sibling boxes is the resolved token, at both ends of the ramp, and an
     * application's literal cannot override it.
     *
     * <p>The gap is pushed by {@code gapSilently} from inside {@code onMeasure}, so it is in force
     * for the layout of the very same pass. Pushing it after layout instead would publish the
     * previous frame's gap; swapping in the invalidating {@code gap(float)} would re-enter layout
     * from inside a measure pass; dropping the push would freeze whatever literal the application
     * last set. Each is invisible on screen for a frame and permanently wrong in the tree.
     *
     * <p>The step is driven through the scene and the widget, never {@code setProcessDefault},
     * which is a static global that would leak into every other test in the run.
     */
    @Test
    void theGapBetweenSiblingBoxesIsTheResolvedTokenAndNotAnAppsLiteral() {
        TokenRow row = rowOf(Tokens.Role.MEDIUM, "first", "second");
        bind(row);

        assertEquals(MEDIUM_AT_MEDIUM, gapBetween("first", "second"),
                "spacingMedium at the default step");

        scene.setControlSize(ControlSize.XSMALL);
        frame();
        assertEquals(MEDIUM_AT_XSMALL, gapBetween("first", "second"),
                "the step reaches the tree with no app code");

        scene.setControlSize(ControlSize.XLARGE);
        frame();
        assertEquals(MEDIUM_AT_XLARGE, gapBetween("first", "second"));

        row.setControlSize(ControlSize.MEDIUM);
        frame();
        assertEquals(MEDIUM_AT_MEDIUM, gapBetween("first", "second"),
                "a step declared on the widget itself wins over the scene's");

        row.gap(100);
        frame();
        assertEquals(MEDIUM_AT_MEDIUM, gapBetween("first", "second"),
                "the measure pass overwrites an app's literal, so the tree follows the token");
    }

    /**
     * The role picks the token, and no two roles are the same number at the default step.
     *
     * <p>Two rows share one scene inside a {@link Column}, which is transparent too, so all four
     * boxes hoist into the window and each row's gutter reads straight off its own pair. A loose
     * width from the column is the shape a token row has in a real form, where the tight root
     * constraint of the other tests is not.
     */
    @Test
    void theRolePicksTheToken() {
        Column column = new Column();
        column.add(rowOf(Tokens.Role.SMALL, "tight-a", "tight-b"));
        column.add(rowOf(Tokens.Role.LARGE, "wide-a", "wide-b"));
        bind(column);

        assertEquals(SMALL_AT_MEDIUM, gapBetween("tight-a", "tight-b"), "spacingSmall");
        assertEquals(LARGE_AT_MEDIUM, gapBetween("wide-a", "wide-b"), "spacingLarge");
        assertNotEquals(SMALL_AT_MEDIUM, MEDIUM_AT_MEDIUM);
        assertNotEquals(MEDIUM_AT_MEDIUM, LARGE_AT_MEDIUM);
    }

    /**
     * A density change moves the boxes and destroys nothing.
     *
     * <p>The row is not in the tree, so there is no node to add or remove and nothing for identity
     * to be minted over: a step change is a bounds change on the children and no more. The
     * alternative — a screen reader experiencing the whole strip as torn down and rebuilt because
     * the user changed a density preference — is the failure this pins. The quiet frame after it
     * is the second half: a walk that allocated or compared something fresh per frame would keep
     * talking after the move.
     */
    @Test
    void aStepChangeMovesTheBoxesAndDestroysNothing() {
        bind(rowOf(Tokens.Role.MEDIUM, "first", "second"));
        long first = node("first").id();
        long second = node("second").id();
        float wasAt = node("second").x();
        bridge.events.clear();

        scene.setControlSize(ControlSize.XLARGE);
        frame();

        assertEquals(first, node("first").id(), "identity is not the step");
        assertEquals(second, node("second").id());
        assertNotEquals(wasAt, node("second").x(), "and the second moved");
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
     * An application's literal costs a layout pass and no snapshot.
     *
     * <p>This is not {@code Row}'s equality-guard case, where the setter itself is a no-op. Here
     * {@code gap(100)} is a real change at the setter: it marks layout dirty and buys a full pass.
     * The measure step then re-pushes the token, every box lands where it was, and it is the
     * publish step's no-change early return — not the setter — that keeps the reader quiet. A
     * wasted pass is a layout concern; what this pins is that it costs neither a snapshot nor an
     * event.
     */
    @Test
    void anAppsLiteralCostsALayoutPassAndNoSnapshot() {
        TokenRow row = rowOf(Tokens.Role.MEDIUM, "first", "second");
        bind(row);
        bridge.published.clear();

        row.gap(100);
        frame();

        assertTrue(bridge.published.isEmpty(), "the walk found nothing moved, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and said nothing: " + bridge.events);
    }

    /**
     * The gap mirrors with the boxes and the reading order does not.
     *
     * <p>{@code Flex.onLayout} walks a logical cursor and reflects only the final coordinate, so
     * under a right-to-left subtree the first child is rightmost and the token-sized gutter sits on
     * its left, between it and the second. Computing the reflection off the cursor before the gap
     * is added, or applying the gap on the wrong side, would hand a reader a strip whose gutters
     * are on the far side of every control, with the row itself still correctly absent.
     */
    @Test
    void theGapMirrorsWithTheBoxesAndTheReadingOrderDoesNot() {
        TokenRow row = rowOf(Tokens.Role.MEDIUM, "first", "second");
        bind(row);

        assertEquals(List.of("first", "second"), readingOrder());
        assertEquals(0f, node("first").x());
        assertEquals(LEAF_WIDTH + MEDIUM_AT_MEDIUM, node("second").x());

        row.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(List.of("first", "second"), readingOrder(),
                "reading order is logical and does not turn around");
        assertEquals(canvas.width() - LEAF_WIDTH, node("first").x(),
                "and the boxes are physical, so the first is now rightmost");
        assertEquals(canvas.width() - LEAF_WIDTH - MEDIUM_AT_MEDIUM - LEAF_WIDTH,
                node("second").x());
        assertEquals(MEDIUM_AT_MEDIUM, gapBetween("second", "first"),
                "the token-sized gutter reflected with them");
    }

    /**
     * Hiding a child removes its gap as well as its width, and the hidden one is still a node —
     * one a reader is holding — published without {@code VISIBLE} and without {@code SHOWING}.
     *
     * <p>{@code Flex} counts its gaps over visible children and advances its cursor past visible
     * children only, so the third box lands one leaf and one gutter in, not two of each. Counting
     * over {@code children().size()} instead, or advancing the cursor for an invisible child,
     * would leave a token-sized hole where nothing is.
     */
    @Test
    void hidingAChildRemovesItsGapToo() {
        TokenRow row = new TokenRow(Tokens.Role.MEDIUM);
        Box hidden = new Box("second");
        row.add(new Box("first"));
        row.add(hidden);
        row.add(new Box("third"));
        bind(row);
        assertEquals(2 * (LEAF_WIDTH + MEDIUM_AT_MEDIUM), node("third").x());
        bridge.events.clear();

        hidden.setVisible(false);
        frame();

        assertEquals(LEAF_WIDTH + MEDIUM_AT_MEDIUM, node("third").x(),
                "one leaf and one gutter, not two of each");
        AccessibleNode gone = node("second");
        assertTrue(!gone.has(Accessible.State.VISIBLE) && !gone.has(Accessible.State.SHOWING),
                "hidden is a state, not a removal: " + describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing a reader is holding went away: " + bridge.events);
    }

    /**
     * The escape hatch: naming an instance materialises it as a group over the row's own box, with
     * the children unchanged underneath at their token-sized gutter, and re-keys nothing.
     *
     * <p>Identity is minted over the widget tree and never over the published one, which is what
     * makes this safe: an application labelling a strip must not invalidate every identifier a
     * client is holding inside it. This is also what keeps a future "TokenRow is always deleted"
     * shortcut honest: the walk applies the application's overrides before it evaluates the
     * predicate, and a shortcut that skipped them would swallow the name.
     */
    @Test
    void namingTheRowMaterialisesAGroupOverItsOwnBoxAndReKeysNothing() {
        TokenRow row = rowOf(Tokens.Role.MEDIUM, "first", "second");
        bind(row);
        long first = node("first").id();
        long second = node("second").id();

        row.setAccessibleName("Actions");
        frame();

        AccessibleNode named = node("Actions");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(row.localToSceneX(), named.x(), "the row's own box");
        assertEquals(row.localToSceneY(), named.y());
        assertEquals(row.width(), named.width());
        assertEquals(row.height(), named.height());
        assertEquals(tree().indexOf(named.id()), node("first").parent(), describe(tree()));
        assertEquals(tree().indexOf(named.id()), node("second").parent());
        assertEquals(first, node("first").id(), "naming the strip is not renaming what is in it");
        assertEquals(second, node("second").id());
        assertEquals(MEDIUM_AT_MEDIUM, gapBetween("first", "second"),
                "and the children still sit at the token gutter inside it");

        row.setAccessibleName((String) null);
        frame();

        assertEquals(3, tree().nodeCount(), "and it goes away again: " + describe(tree()));
        assertEquals(first, node("first").id());
        assertEquals(second, node("second").id());
    }
}
