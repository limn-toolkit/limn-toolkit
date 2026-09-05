package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Widget;
import limn.scene.layout.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link Dialog}'s action row becomes in the accessible tree, which is nothing, and the one
 * thing it says on the way out, which is which of its buttons is the default.
 *
 * <p>The row is a {@code Row} with one {@code onMeasure} that pushes the {@code gapButtonRow}
 * token. Nothing in that chain declares a role, a name, an action or a state, nothing overrides
 * {@code onPaint}, nothing clips and nothing is focusable of its own accord, so ADR 039 §1.6's
 * predicate deletes it in silence and hoists the buttons into the card's place. A reader hears
 * "Cancel, button; OK, button, default" and no box between them.
 *
 * <p>§1.6 lists the row among the scaffolding deleted "without a line of accessibility code", and
 * that half is wrong for this one. §7's Button row promises {@code DEFAULT} "when it is a dialog's
 * default"; Button's own hook refuses to declare it, because the fact is the dialog's and the
 * button holds no such notion; and the walk lets only a widget's <em>direct</em> parent add to a
 * child's node, whether or not that parent survives as a node. The direct parent is the row, not
 * the panel, so the deleted row is the one place the default button can be marked, and it carries
 * a child hook to do it. What that hook must guarantee is the invariant a reader depends on: the
 * button announced as default is the one Return answers with.
 *
 * <p>The rest of what the deletion leaves behind is geometry. The row is the published {@code x}
 * of every dialog button: {@code END} alignment packs them to the trailing edge, the gutter is
 * {@code gapButtonRow} and not {@code spacingSmall} (the two agree from MEDIUM up and part ways
 * below it), and a right-to-left subtree reflects the boxes while reading order stays
 * {@code addButton} order.
 *
 * <p>Everything below builds a dialog through its public API, binds the panel the way
 * {@link DialogTest} drives it headless, and reads the tree the scene published; nothing
 * constructs a node and nothing calls a hook.
 */
class DialogActionRowAccessibilityTest extends AccessibleComponentTestBase {

    /** {@code gapButtonRow} at the default {@link ControlSize#MEDIUM} step. */
    private static final float GAP_AT_MEDIUM = 6;

    /**
     * The same token at the dense end of the ramp, where it is one point wider than
     * {@code spacingSmall}: the number that tells the two tokens apart.
     */
    private static final float GAP_AT_XSMALL = 4;

    private Dialog dialog;

    /** Binds the dialog's panel, which is the scene root a headless dialog has. */
    private void build(Dialog under) {
        dialog = under;
        bind(dialog.contentRoot());
    }

    /** @return a Cancel-then-OK dialog, OK primary */
    private static Dialog cancelAndOk() {
        return new Dialog("Confirm", "Proceed with the test action?")
                .addButton("Cancel", "cancel")
                .addPrimaryButton("OK", "ok");
    }

    /** @return the row the dialog keeps its buttons in, found by type since it is private */
    private Row actionRow() {
        Row found = firstRow(dialog.contentRoot());
        assertNotNull(found, "the dialog no longer keeps its buttons in a Row");
        return found;
    }

    private static Row firstRow(Widget at) {
        if (at instanceof Row row) {
            return row;
        }
        for (Widget child : at.children()) {
            Row found = firstRow(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Button firstButton(Widget at) {
        if (at instanceof Button button) {
            return button;
        }
        for (Widget child : at.children()) {
            Button found = firstButton(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** @return the names of every button node, in tree order */
    private List<String> buttonReadingOrder() {
        List<String> order = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == Accessible.Role.BUTTON) {
                order.add(tree.node(i).name());
            }
        }
        return order;
    }

    /** @return the distance between the first named node's right edge and the second's left */
    private float gapBetween(String left, String right) {
        AccessibleNode first = node(left);
        return node(right).x() - (first.x() + first.width());
    }

    private String resultNow() {
        return dialog.result().toCompletableFuture().getNow("<open>");
    }

    private void pressReturn() {
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.keyEvent(Keys.ENTER, false, false, 0);
        scene.inputBatchEnded();
    }

    // ------------------------------------------------------------------------------ no node

    /**
     * The row publishes no node, and the buttons hang side by side in its place, in the order
     * they were added.
     *
     * <p>A hook landing on {@code Flex} or {@code Row}, or the row acquiring a name, would put a
     * nameless group between the card and its buttons and cost a reader one level of nesting.
     * The reading order is the tree's, and the tree's is {@code addButton} order; the END
     * alignment that packs the buttons rightward is not allowed to turn it around.
     */
    @Test
    void theRowIsNoNodeAndTheButtonsHoistIntoTheCardsPlace() {
        build(cancelAndOk());

        AccessibleNode cancel = node("Cancel");
        AccessibleNode ok = node("OK");
        assertEquals(Accessible.Role.BUTTON, cancel.role(), describe(tree()));
        assertEquals(Accessible.Role.BUTTON, ok.role(), describe(tree()));
        assertEquals(cancel.parent(), ok.parent(),
                "both buttons hoist into the same place: " + describe(tree()));
        assertEquals(List.of("Cancel", "OK"), buttonReadingOrder(),
                "reading order is the order the buttons were added");
        AccessibleTree tree = tree();
        for (int at = ok.parent(); at != AccessibleNode.NONE; at = tree.node(at).parent()) {
            Accessible.Role role = tree.node(at).role();
            assertTrue(role != Accessible.Role.GROUP && role != Accessible.Role.UNKNOWN,
                    "a nameless box between the card and its buttons is the scaffolding the "
                            + "row exists to not publish: " + describe(tree));
        }
    }

    // ------------------------------------------------------------------------------ the boxes

    /**
     * The buttons' boxes are the row's doing: packed to the card's trailing edge, one
     * {@code gapButtonRow} apart, centred on one line below the body, and reflected under a
     * right-to-left subtree with the reading order unchanged.
     *
     * <p>The gutter is asserted at two steps because the number that separates
     * {@code gapButtonRow} from {@code spacingSmall} only appears below MEDIUM: at XSMALL the row's
     * token is 4 where the spacing token is 3. A row that pushed the wrong token, or pushed it
     * after the layout it was meant for, would pass at the default step and publish the wrong
     * box on every dense dialog.
     */
    @Test
    void theButtonsSitAtTheTrailingEdgeOneGutterApartAndMirrorWithoutReordering() {
        build(cancelAndOk());
        Row row = actionRow();

        AccessibleNode cancel = node("Cancel");
        AccessibleNode ok = node("OK");
        assertEquals(row.localToSceneX() + row.width(), ok.x() + ok.width(),
                "END packs the last button against the card's trailing edge");
        assertEquals(GAP_AT_MEDIUM, gapBetween("Cancel", "OK"), "gapButtonRow at MEDIUM");
        assertEquals(cancel.y(), ok.y(), "one line, centred on the cross axis");
        assertEquals(row.localToSceneY() + (row.height() - ok.height()) / 2, ok.y());
        assertTrue(ok.y() > 0, "below the body, not over it");
        assertTrue(cancel.has(Accessible.State.SHOWING), describe(tree()));
        assertTrue(ok.has(Accessible.State.SHOWING), describe(tree()));

        dialog.setControlSize(ControlSize.XSMALL);
        frame();
        assertEquals(GAP_AT_XSMALL, gapBetween("Cancel", "OK"),
                "the dense step's gutter is the row's own token and not spacingSmall's 3");

        scene.setLayoutDirection(LayoutDirection.RTL);
        frame();
        row = actionRow();
        ok = node("OK");
        assertEquals(List.of("Cancel", "OK"), buttonReadingOrder(),
                "reading order is logical and does not turn around");
        assertEquals(row.localToSceneX(), ok.x(),
                "and the boxes are physical, so END is now the left edge and OK is leftmost");
        assertEquals(GAP_AT_XSMALL, gapBetween("OK", "Cancel"),
                "with the gutter reflected between them");
    }

    // ------------------------------------------------------------------------------ the default

    /**
     * Exactly the first primary button carries {@code DEFAULT}, and it is the button Return
     * answers with.
     *
     * <p>The bit is written from a field the dialog sets in the same branch as its default
     * result, so the two cannot disagree; what this pins is that they agree at the tree, where a
     * reader sees them. A second primary button is not a second default, and a dialog with only
     * secondary buttons has none and does not answer Return at all.
     */
    @Test
    void theFirstPrimaryButtonIsTheDefaultAndTheOneReturnAnswersWith() {
        build(new Dialog("Confirm", "Proceed?")
                .addButton("Cancel", "cancel")
                .addPrimaryButton("OK", "ok")
                .addPrimaryButton("Also", "also"));

        List<AccessibleNode> marked = nodesWith(Accessible.State.DEFAULT);
        assertEquals(1, marked.size(), "one default: " + describe(tree()));
        assertEquals("OK", marked.get(0).name(), "the first primary, not the last");
        assertFalse(node("Cancel").has(Accessible.State.DEFAULT), describe(tree()));
        assertFalse(node("Also").has(Accessible.State.DEFAULT), describe(tree()));

        assertEquals("<open>", resultNow(), "sanity: nothing answered yet");
        pressReturn();
        assertEquals("ok", resultNow(),
                "Return answers with the button the tree announced as the default");
    }

    @Test
    void aDialogWithOnlySecondaryButtonsHasNoDefault() {
        build(new Dialog("Confirm", "Proceed?")
                .addButton("Cancel", "cancel")
                .addButton("OK", "ok"));

        assertEquals(List.of(), nodesWith(Accessible.State.DEFAULT),
                "no primary button, so no default: " + describe(tree()));
        pressReturn();
        assertEquals("<open>", resultNow(), "and nothing for Return to answer with");
    }

    // ------------------------------------------------------------------------------ the action

    /**
     * A press from an assistive technology on a hoisted button answers the dialog exactly as a
     * click does.
     *
     * <p>The row adds no guard and the hoisting keeps the button's wiring: the press goes through
     * Button's own hook to the action the dialog wired, which resolves the stage.
     */
    @Test
    void aPressOnAHoistedButtonResolvesTheDialog() throws InterruptedException {
        build(cancelAndOk());
        long ok = node("OK").id();

        assertTrue(perform(ok, Accessible.Action.PRESS, Accessible.Argument.NONE));

        assertEquals("ok", resultNow(), "the press reached the dialog's own resolve");
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.INVOKED), "" + bridge.events);
    }

    // ------------------------------------------------------------------------------ cost

    /**
     * A frame that damages a button and changes nothing publishes nothing.
     *
     * <p>The default bit is a reference comparison and a bit write, and the row holds no string
     * to format, so a redescribed button lands on the same node as before and the publish step's
     * no-change return keeps the reader quiet. A row that derived the fact freshly per frame, or
     * a hook that named anything, would republish here.
     */
    @Test
    void aDamagedFrameThatChangesNothingPublishesNothing() {
        build(cancelAndOk());
        assertEquals(1, nodesWith(Accessible.State.DEFAULT).size(), describe(tree()));
        Button button = firstButton(dialog.contentRoot());
        assertNotNull(button);
        bridge.published.clear();

        button.invalidate();
        frame();

        assertTrue(bridge.published.isEmpty(), "damage that moved nothing costs no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        frame();

        assertTrue(bridge.published.isEmpty(), "and a quiet frame after it is still free");
        assertTrue(bridge.events.isEmpty(), "" + bridge.events);
    }

    /**
     * The default button keeps its identity and its bit across a move.
     *
     * <p>A step change and a direction flip move every button box; neither is a rebuild, and the
     * default is a fact fixed when the button was added, so a reader holding the OK node keeps
     * holding the same default OK node after both.
     */
    @Test
    void movingTheButtonsRekeysNothingAndKeepsTheDefault() {
        build(cancelAndOk());
        long ok = node("OK").id();
        float wasAt = node("OK").x();
        bridge.events.clear();

        dialog.setControlSize(ControlSize.XLARGE);
        scene.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertNotEquals(wasAt, node("OK").x(), "it moved");
        assertEquals(ok, node("OK").id(), "identity is not a coordinate");
        assertTrue(node("OK").has(Accessible.State.DEFAULT), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was added or removed: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), "" + bridge.events);
    }
}
