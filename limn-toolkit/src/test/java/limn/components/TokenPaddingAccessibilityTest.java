package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link TokenPadding} becomes in the accessible tree, which is nothing, and what the
 * deletion leaves behind, which is the one thing this widget exists to contribute: an offset that
 * follows the resolved {@link ControlSize} step.
 *
 * <p>TokenPadding declares no role, no name, no action and no state of its own, it is never
 * focusable, it carries no tooltip and it paints nothing, so ADR 039 §1.6's predicate deletes it in
 * silence — no warning is logged, because the paints-and-says-nothing guard only fires for a class
 * that declares {@code onPaint} — and hoists its child into the padding's own place. The class
 * therefore needs no accessibility code. It still needs these tests, because everything worth
 * knowing about the deletion is what the deletion does <em>not</em> remove. Unlike a pane that
 * clips, nothing survives here as a clip; what survives is the child's origin, and that origin is
 * derived from {@code Tokens.spacingFor} inside the measure pass rather than held as a literal. So
 * the published box tracks the density ramp with no code, and a size step reaches a screen reader
 * as the content having moved rather than as the page having been rebuilt.
 *
 * <p><b>Transparency here is per instance and not per class.</b> TokenPadding is public and
 * non-final and carries the whole of Widget's naming surface, so an application that names, roles
 * or tooltips one gets a node: a {@code GROUP} over the outer, padding-inclusive rectangle, with the
 * content unchanged underneath at the inset offset. That is the supported way to ask for a named
 * region without writing a wrapper class, and the fourth test is what keeps a future "TokenPadding
 * is always deleted" shortcut honest. Focusing alone is not a fourth verb, for the reason
 * {@code PaddingAccessibilityTest} pins on the superclass: it supplies nothing to publish, so the
 * node survives as {@code UNKNOWN} and unnamed.
 *
 * <p>The generic predicate is not re-tested here — {@code AccessibleTreeTest} pins it, and
 * {@code PaddingAccessibilityTest} pins what a plain {@link limn.scene.layout.Padding}'s deletion
 * leaves behind, including the mirroring of asymmetric insets. What is left to this class is the
 * half that is its own: the insets being derived, uniform, and pushed from inside {@code onMeasure}.
 *
 * <p>Everything below drives public constructors and setters on a bound scene and reads the tree the
 * scene published; nothing constructs a node and nothing calls a hook.
 */
class TokenPaddingAccessibilityTest extends AccessibleComponentTestBase {

    /** {@code spacingLarge} at the default {@link ControlSize#MEDIUM} step. */
    private static final float LARGE_AT_MEDIUM = 20;

    /** The same token at the ends of the ramp. */
    private static final float LARGE_AT_XSMALL = 12;
    private static final float LARGE_AT_XLARGE = 32;

    /** {@code spacingSmall} at the default step, so a test confusing the roles fails. */
    private static final float SMALL_AT_MEDIUM = 6;

    /**
     * A leaf with a fixed preferred size and an application-supplied name: something inside the
     * padding that survives the predicate, so the offset the padding leaves behind has a node to be
     * read from.
     */
    private static final class Box extends Widget {
        Box() {
            setAccessibleName("content");
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(60, 40);
        }
    }

    /**
     * The padding publishes no node at all and its child hangs directly under the window.
     *
     * <p>A role, a name or even an empty describe hook added here would re-materialise a nameless
     * box between the window and every page's content — the scaffolding §1.6 exists to delete — and
     * cost a reader one level of nesting per padded section.
     */
    @Test
    void thePaddingIsNoNodeAndItsChildHoistsIntoItsPlace() {
        bind(new TokenPadding(Tokens.Role.LARGE, new Box()));

        AccessibleTree tree = tree();
        assertEquals(2, tree.nodeCount(),
                "a window and the content, and no box in between: " + describe(tree));
        assertEquals(0, node("content").parent(),
                "the content hoists into the padding's own place, under the window");
        for (int i = 1; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            assertTrue(node.role() != Accessible.Role.GROUP || !node.name().isEmpty(),
                    "a nameless group is what a padding must never publish: " + describe(tree));
        }
    }

    /**
     * The child's published origin is the resolved token, at both ends of the ramp and for both
     * roles, and an application's literal cannot override it.
     *
     * <p>The insets are pushed by {@code setInsetsSilently} from inside {@code onMeasure}, so they
     * are in force for the layout of the very same pass. Pushing them after layout instead would
     * publish the previous frame's box, and swapping in the invalidating {@code setInsets} would
     * re-enter layout from inside a measure pass. Both are invisible on screen for a frame and
     * permanently wrong in the tree.
     *
     * <p>The step is driven through the scene and the widget, never {@code setProcessDefault},
     * which is a static global that would leak into every other test in the run.
     */
    @Test
    void theChildsBoxFollowsTheResolvedStepAndNotAnAppsLiteral() {
        TokenPadding padding = new TokenPadding(Tokens.Role.LARGE, new Box());
        bind(padding);

        assertEquals(LARGE_AT_MEDIUM, insetX(padding), "spacingLarge at the default step");
        assertEquals(LARGE_AT_MEDIUM, insetY(padding), "uniform on all four edges");

        scene.setControlSize(ControlSize.XSMALL);
        frame();
        assertEquals(LARGE_AT_XSMALL, insetX(padding), "the step reaches the tree with no app code");

        scene.setControlSize(ControlSize.XLARGE);
        frame();
        assertEquals(LARGE_AT_XLARGE, insetX(padding));

        padding.setControlSize(ControlSize.MEDIUM);
        frame();
        assertEquals(LARGE_AT_MEDIUM, insetX(padding), "a step declared on the widget itself wins");

        padding.setInsets(Insets.all(100));
        frame();
        assertEquals(LARGE_AT_MEDIUM, insetX(padding),
                "the measure pass overwrites an app's literal, so the tree follows the step");
    }

    /** The role picks the token, and the two are not the same number at any step. */
    @Test
    void asmallerRolePublishesASmallerOffset() {
        TokenPadding padding = new TokenPadding(Tokens.Role.SMALL, new Box());
        bind(padding);

        assertEquals(SMALL_AT_MEDIUM, insetX(padding), "spacingSmall at the default step");
        assertNotEquals(LARGE_AT_MEDIUM, SMALL_AT_MEDIUM);
    }

    /**
     * A density change moves the content and destroys nothing.
     *
     * <p>The padding is not in the tree, so there is no node to add or remove and nothing for
     * identity to be minted over: a step change is a bounds change on the content and no more. The
     * alternative — a screen reader experiencing the whole page as torn down and rebuilt because
     * the user changed a density preference — is the failure this pins.
     */
    @Test
    void aStepChangeMovesTheBoxAndDestroysNothing() {
        TokenPadding padding = new TokenPadding(Tokens.Role.LARGE, new Box());
        bind(padding);
        long before = node("content").id();
        float wasAt = node("content").x();
        bridge.events.clear();

        scene.setControlSize(ControlSize.XLARGE);
        frame();

        assertEquals(before, node("content").id(), "the content's identity is not the step");
        assertNotEquals(wasAt, node("content").x(), "and it moved");
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
     * The escape hatch: naming an instance materialises it as a group over the outer box, and
     * re-keys nothing underneath.
     *
     * <p>Identity is minted over the widget tree and never over the published one, which is what
     * makes this safe: an application labelling a region must not invalidate every identifier a
     * client is holding inside it. The group's box is the padded rectangle and not the content's,
     * because a region that reports an area smaller than it covers puts its own gutter outside
     * itself.
     */
    @Test
    void namingThePaddingMaterialisesItAndReKeysNothing() {
        TokenPadding padding = new TokenPadding(Tokens.Role.LARGE, new Box());
        bind(padding);
        long child = node("content").id();

        padding.setAccessibleName("Page");
        frame();

        AccessibleNode named = node("Page");
        assertEquals(Accessible.Role.GROUP, named.role());
        assertEquals(padding.localToSceneX(), named.x(), "the outer box, padding included");
        assertEquals(padding.localToSceneY(), named.y());
        assertEquals(padding.width(), named.width());
        assertEquals(padding.height(), named.height());

        AccessibleNode inside = node("content");
        assertEquals(tree().indexOf(named.id()), inside.parent(), describe(tree()));
        assertEquals(child, inside.id(), "naming the region is not renaming what is in it");
        assertEquals(LARGE_AT_MEDIUM, inside.x() - named.x(),
                "and the content still sits at the token offset inside it");
    }

    /**
     * The offset does not mirror, because all four edges are equal by construction.
     *
     * <p>{@code Padding.leadingInset()} picks {@code insets.right()} under a right-to-left subtree,
     * and that choice is real and is pinned where it belongs, on Padding itself. Here it is a no-op,
     * and saying so in one line is what tells the next hand that giving this widget per-edge roles
     * or an asymmetric variant would make the published box mirror.
     */
    @Test
    void uniformInsetsDoNotMirror() {
        TokenPadding padding = new TokenPadding(Tokens.Role.LARGE, new Box());
        bind(padding);
        float ltr = insetX(padding);

        padding.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(ltr, insetX(padding), "uniform insets are the same from either side");
        assertEquals(LARGE_AT_MEDIUM, insetX(padding));
    }

    /** @return the gap between the padding's own box and its content's, across */
    private float insetX(TokenPadding padding) {
        return node("content").x() - padding.localToSceneX();
    }

    /** @return the same gap, down */
    private float insetY(TokenPadding padding) {
        return node("content").y() - padding.localToSceneY();
    }
}
