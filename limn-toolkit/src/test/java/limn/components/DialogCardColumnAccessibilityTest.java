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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Dialog}'s card column becomes in the accessible tree, which is nothing, and what
 * the deletion leaves behind, which is the one decision the column exists to take: which of the
 * dialog's controls are on screen.
 *
 * <p>The column is a {@code Widget} that overrides {@code onMeasure} and {@code onLayout} and
 * nothing else. It declares no role, no name, no action and no state, it is never focusable, it
 * can never acquire a tooltip, it does not clip and it does not paint, so ADR 039 §1.6's predicate
 * deletes it in silence and hoists its two children into the card's place in tree order: the
 * scroll view over the body first, the action row second. The class needs no accessibility code
 * and this test adds none. Transparency here is per class in effect and not per instance, unlike
 * the public wrappers: the class is private and final, the dialog builds the one instance inside
 * its own constructor, and nothing the dialog exposes reaches it, so the naming hatch the padding
 * and the rows keep does not exist for it.
 *
 * <p>§1.6 names it among the scaffolding deleted "without a line of accessibility code", and the
 * verdict and the line count are both right. What the framing hides is that this is not a box like
 * {@code Row} or {@code Padding}. The column is the one widget that holds both the body's wanted
 * height and the card's budget, so it alone computes the cap that turns the body's
 * {@link ScrollView} into a viewport &mdash; measuring the body itself rather than the scroll view,
 * which answers any bounded height with that height &mdash; and it alone keeps the buttons outside
 * that viewport. Three things therefore outlive the node and reach a reader through the surviving
 * children's boxes: the clip edge that decides {@code SHOWING} for every body node, the
 * {@code spacingMedium} gutter between the body's viewport and the footer, and the footer's origin
 * inside the card and never inside the scrolled half, which is why the dialog's only way out
 * publishes {@code SHOWING} however tall the body is.
 *
 * <p>One thing the step's own plan had wrong, and which the widget decides: the gutter is not the
 * distance from the last body node to the first button. A bound scene lays its root out tight,
 * the panel and the padding hand the column the whole box, and {@code onLayout} gives the body's
 * viewport every point above the footer, so with a short body the last label sits far above the
 * buttons. The gutter is the distance from the viewport's bottom edge to the footer's top, which
 * is what the third case reads; the two coincide only in a native window sized from a loose
 * measure, which {@link DialogTest} pins on the measure itself.
 *
 * <p>Everything below builds a dialog through its public API, binds the panel the way
 * {@link DialogTest} drives it headless, and reads the tree the scene published. Nodes are found
 * by name and never by index or parent, so the file survives the dialog panel's and the scroll
 * view's own steps landing above them; nothing constructs a node and nothing calls a hook.
 */
class DialogCardColumnAccessibilityTest extends AccessibleComponentTestBase {

    /**
     * {@code spacingMedium} at the default {@link ControlSize#MEDIUM} step. Twelve and not nine:
     * the ramp is 6 / 9 / 12 / 16 / 20 across five steps, so nine is the SMALL step's number, and
     * a test that carried it would pass only on a dialog one step denser than the default.
     */
    private static final float MEDIUM_AT_MEDIUM = 12;

    /** The same token at the wide end of the ramp. */
    private static final float MEDIUM_AT_XLARGE = 20;

    /**
     * A leaf with a fixed preferred size and an application-supplied name: content that survives
     * the predicate, so the clip edge the column leaves behind has a node to be read from.
     */
    private static final class Box extends Widget<Box> {
        private final float wide;
        private final float tall;

        Box(String name, float wide, float tall) {
            this.wide = wide;
            this.tall = tall;
            setAccessibleName(name);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(wide, tall);
        }
    }

    private Dialog dialog;

    /** Binds the dialog's panel, which is the scene root a headless dialog has. */
    private void build(Dialog under) {
        dialog = under;
        bind(dialog.contentRoot());
    }

    /** @return the card's box, which is the panel's: the column's own is not published */
    private Widget<?> card() {
        return dialog.contentRoot();
    }

    /** @return the body's viewport, found by type since the column that sizes it is private */
    private ScrollView viewport() {
        ScrollView found = find(card(), ScrollView.class);
        assertNotNull(found, "the dialog no longer scrolls its body in a ScrollView");
        return found;
    }

    /** @return the viewport's bottom edge in scene coordinates: the clip edge the column set */
    private float viewportBottom() {
        ScrollView scroll = viewport();
        return scroll.localToSceneY() + scroll.height();
    }

    private static <T extends Widget<?>> T find(Widget<?> at, Class<T> type) {
        if (type.isInstance(at)) {
            return type.cast(at);
        }
        for (Widget<?> child : at.children()) {
            T found = find(child, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** @return every button node, in tree order */
    private List<AccessibleNode> buttons() {
        List<AccessibleNode> found = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == Accessible.Role.BUTTON) {
                found.add(tree.node(i));
            }
        }
        return found;
    }

    /** @return where the node named {@code name} sits in tree order */
    private int indexOf(String name) {
        return tree().indexOf(node(name).id());
    }

    private static void assertInside(AccessibleNode node, Widget<?> box) {
        assertTrue(node.x() >= box.localToSceneX(), node.name() + " starts inside the card");
        assertTrue(node.y() >= box.localToSceneY(), node.name() + " starts inside the card");
        assertTrue(node.x() + node.width() <= box.localToSceneX() + box.width(),
                node.name() + " ends inside the card");
        assertTrue(node.y() + node.height() <= box.localToSceneY() + box.height(),
                node.name() + " ends inside the card");
    }

    // ------------------------------------------------------------------------------ no node

    /**
     * The column publishes no node, and its two children hoist into its place in the order they
     * were added: the body's controls before the buttons.
     *
     * <p>A role, a tooltip or a describe hook that said anything on the column would put a
     * nameless box between the card and every control in it; swapping the two {@code add} calls
     * would read the buttons before the content a user has not heard yet. The order is also the
     * depth-first focus order, so reading order equals Tab order here with no code.
     */
    @Test
    void theColumnIsNoNodeAndItsChildrenHoistInTreeOrder() {
        build(new Dialog("Title", "Message")
                .addButton("Cancel", "cancel")
                .addPrimaryButton("OK", "ok"));

        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            assertTrue(node.role() != Accessible.Role.GROUP || !node.name().isEmpty(),
                    "a nameless group is what the column must never publish: " + describe(tree));
        }
        assertEquals(Accessible.Role.BUTTON, node("Cancel").role(), describe(tree));
        assertEquals(Accessible.Role.BUTTON, node("OK").role(), describe(tree));
        assertTrue(indexOf("Title") < indexOf("Message"), "the title reads first: " + describe(tree));
        assertTrue(indexOf("Message") < indexOf("Cancel"),
                "the body reads before the buttons: " + describe(tree));
        assertTrue(indexOf("Cancel") < indexOf("OK"),
                "and the buttons in the order they were added: " + describe(tree));
    }

    // ------------------------------------------------------------------------------ the cap

    /**
     * A body taller than the card is capped to a viewport, and the footer sits below that
     * viewport, inside the card, whatever the body wants.
     *
     * <p>The scene lays the panel out tight to the window, so the padding hands the column a
     * bounded height and the cap binds. A node scrolled beyond the viewport is visible and not
     * showing, because the scroll view's clip is what {@code isShowing()} intersects against and
     * the column decided where that clip's edge is. The buttons are showing and inside the card:
     * folding them into the scrolling half, or dropping the cap so the scroll view answered with
     * the body's full height, would publish the dialog's only way out as off screen.
     */
    @Test
    void theFooterNeverScrollsAndTheBodyIsCapped() {
        Column content = new Column();
        content.add(new Box("filler", 60, 400));
        content.add(new Box("last", 60, 40));
        build(new Dialog("Title", "Message")
                .setContent(content)
                .addButton("Cancel", "cancel")
                .addPrimaryButton("OK", "ok"));

        AccessibleNode last = node("last");
        assertTrue(last.has(Accessible.State.VISIBLE), describe(tree()));
        assertFalse(last.has(Accessible.State.SHOWING),
                "scrolled beyond the viewport is not on screen: " + describe(tree()));
        assertTrue(last.y() >= viewportBottom(), "and its box really lies below the clip edge");
        assertTrue(node("filler").has(Accessible.State.SHOWING),
                "the top of the body is on screen: " + describe(tree()));

        List<AccessibleNode> buttons = buttons();
        assertEquals(2, buttons.size(), describe(tree()));
        for (AccessibleNode button : buttons) {
            assertTrue(button.has(Accessible.State.SHOWING),
                    button.name() + " is the way out and is on screen: " + describe(tree()));
            assertInside(button, card());
            assertTrue(button.y() >= viewportBottom(),
                    button.name() + " sits below the body's viewport, not inside it");
        }
    }

    // ------------------------------------------------------------------------------ the gutter

    /**
     * The gap between the body's viewport and the footer is {@code spacingMedium} at the resolved
     * step, re-derived on every layout.
     *
     * <p>A literal captured in the constructor, where no step exists yet, or a gutter remembered
     * from the measure pass, would leave the buttons where the previous step put them. With one
     * button the row's {@code CENTER} cross alignment makes the button's top the footer's top
     * exactly, so the button node is the footer's edge.
     */
    @Test
    void theGutterIsTheMediumTokenAndFollowsTheStep() {
        build(new Dialog("Title", "Message").addPrimaryButton("OK", "ok"));

        assertEquals(MEDIUM_AT_MEDIUM, node("OK").y() - viewportBottom(),
                "spacingMedium at the default step");
        assertEquals(Tokens.spacingFor(card(), Tokens.Role.MEDIUM), node("OK").y() - viewportBottom());

        scene.setControlSize(ControlSize.XLARGE);
        frame();

        assertEquals(MEDIUM_AT_XLARGE, node("OK").y() - viewportBottom(),
                "the step reaches the tree with no code on the column");
        assertEquals(Tokens.spacingFor(card(), Tokens.Role.MEDIUM), node("OK").y() - viewportBottom());
    }

    // ------------------------------------------------------------------------------ the cost

    /**
     * A density change moves the buttons and destroys nothing.
     *
     * <p>The column is not in the tree, so there is no node to add or remove and nothing for
     * identity to be minted over: a step change is a bounds change on the controls and no more,
     * and the frame after it is free. A hook that allocated, or a rebuild of the card on a
     * density change, would fail here.
     */
    @Test
    void aStepChangeMovesTheButtonsAndDestroysNothing() {
        build(new Dialog("Title", "Message")
                .addButton("Cancel", "cancel")
                .addPrimaryButton("OK", "ok"));
        long cancel = node("Cancel").id();
        long ok = node("OK").id();
        float wasAt = node("OK").y();
        bridge.events.clear();

        scene.setControlSize(ControlSize.XLARGE);
        frame();

        assertEquals(cancel, node("Cancel").id(), "identity is not the step");
        assertEquals(ok, node("OK").id());
        assertNotEquals(wasAt, node("OK").y(), "and the buttons moved");
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

    // ------------------------------------------------------------------------------ mirroring

    /**
     * The column contributes no mirroring: a right-to-left card leaves every body node's and
     * button node's {@code y} where it was.
     *
     * <p>Both children are laid out at the column's full width from its origin and the class
     * reads no layout direction, so whatever moves across is the action row's and the row's, and
     * is pinned on their own steps.
     */
    @Test
    void aRightToLeftCardMovesNothingDown() {
        build(new Dialog("Title", "Message")
                .addButton("Cancel", "cancel")
                .addPrimaryButton("OK", "ok"));
        float title = node("Title").y();
        float message = node("Message").y();
        float cancel = node("Cancel").y();
        float ok = node("OK").y();

        card().setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(title, node("Title").y());
        assertEquals(message, node("Message").y());
        assertEquals(cancel, node("Cancel").y());
        assertEquals(ok, node("OK").y());
    }
}
