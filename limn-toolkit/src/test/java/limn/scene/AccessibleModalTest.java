package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a modal blocks is published, not only enforced.
 *
 * <p>Gating the actions is not enough. A user behind an open dialog is told, by every node
 * underneath it, that it is enabled and focusable and on screen — so the reader offers the whole
 * background interface, announces each control as operable, and every invocation is then refused
 * with no way to say why. The tree has to carry the fact.
 */
class AccessibleModalTest extends AccessibleTestBase {

    private Probe stop(String name) {
        Probe probe = new Probe(Accessible.Role.BUTTON, name);
        probe.setFocusable(true);
        return probe;
    }

    private List<String> publishedFocusOrder() {
        List<String> order = new ArrayList<>();
        for (AccessibleNode node : nodesWith(Accessible.State.FOCUSABLE)) {
            order.add(node.name());
        }
        return order;
    }

    @Test
    void everythingOutsideTheOpenLayerLosesEnabledAndFocusableAndKeepsBeingOnScreen() {
        Group root = new Group();
        root.add(stop("behind"));
        bind(root);
        frame();
        assertTrue(node("behind").has(Accessible.State.ENABLED));

        Group dialog = new Group();
        dialog.add(stop("confirm"));
        scene.pushOverlay(dialog);
        frame();

        AccessibleNode behind = node("behind");
        assertFalse(behind.has(Accessible.State.ENABLED),
                "a control behind a modal is not operable: " + describe(tree()));
        assertFalse(behind.has(Accessible.State.FOCUSABLE));
        assertTrue(behind.has(Accessible.State.VISIBLE),
                "it is genuinely on screen, and a reader may still want to read it");
        assertTrue(behind.has(Accessible.State.SHOWING));
        assertTrue(node("confirm").has(Accessible.State.ENABLED));
    }

    /**
     * The same rule for what a node offers to do (ADR 039 §1.13, amended 2026-09-15; semantics 5):
     * the scene refuses every verb outside the layer that owns input, and a platform is answered
     * from the snapshot, so a node there publishes no verb and none of the setters its facets
     * would imply -- its value read-only, its text {@code READ_ONLY} -- beneath an in-scene
     * layer and in a window a native modal blocks alike, and gets them back when the layer goes.
     */
    @Test
    void everythingOutsideTheOpenLayerPublishesNoVerbAndNoSetter() {
        Group root = new Group();
        Probe slider = stop("level");
        slider.value = 40.0;
        slider.actions = new Accessible.Action[] {Accessible.Action.INCREMENT};
        root.add(slider);
        Probe field = stop("notes");
        field.role = Accessible.Role.TEXT_FIELD;
        field.text = "draft";
        root.add(field);
        bind(root);
        frame();
        assertTrue(node("level").actions().has(Accessible.Action.INCREMENT), describe(tree()));
        assertFalse(node("level").value().readOnly());
        assertFalse(node("notes").has(Accessible.State.READ_ONLY));

        Group dialog = new Group();
        Probe confirm = stop("confirm");
        confirm.value = 1.0;
        confirm.actions = new Accessible.Action[] {Accessible.Action.PRESS};
        dialog.add(confirm);
        scene.pushOverlay(dialog);
        frame();

        assertNull(node("level").actions(),
                "a verb the scene refuses beneath the layer is not published: " + describe(tree()));
        assertTrue(node("level").value().readOnly(), "nor the SET_VALUE a writable value implies");
        assertTrue(node("level").has(Accessible.State.READ_ONLY));
        assertEquals(40.0, node("level").value().value(), "what it holds is still said");
        assertNull(node("notes").actions(), "not even the free verbs");
        assertTrue(node("notes").has(Accessible.State.READ_ONLY),
                "nor the SET_TEXT an editable text implies: " + describe(tree()));
        assertTrue(node("confirm").actions().has(Accessible.Action.PRESS),
                "the layer that owns input keeps every one");
        assertFalse(node("confirm").value().readOnly());

        scene.removeOverlay(dialog);
        frame();
        assertTrue(node("level").actions().has(Accessible.Action.INCREMENT),
                "closing the layer gives the operations back: " + describe(tree()));
        assertFalse(node("level").value().readOnly());
        assertFalse(node("notes").has(Accessible.State.READ_ONLY));

        window.modalBlocked = true;
        scene.requestRender();
        frame();
        assertNull(node("level").actions(),
                "a native modal blocks the whole window the same way: " + describe(tree()));
        assertTrue(node("level").value().readOnly());
        assertTrue(node("notes").has(Accessible.State.READ_ONLY));
    }

    /**
     * The other axis of the same rule (semantics 5; ADR 039 §1.5, amended 2026-09-15): the scene
     * refuses every verb on a disabled widget and under a disabled ancestor, so a node published
     * without {@code ENABLED} offers no verb, no verb its container claimed on it, no key binding
     * and no setter — by its own flag, by an ancestor's, and on a synthetic child its enabled
     * owner narrowed, and one nested under that child. What it is and holds stays, and enabling
     * it gives everything back.
     */
    @Test
    void aDisabledNodePublishesNoVerbNoClaimNoKeyBindingAndNoSetter() {
        Group root = new Group();
        Probe slider = stop("level");
        slider.value = 40.0;
        slider.actions = new Accessible.Action[] {Accessible.Action.INCREMENT};
        root.add(slider);
        Probe field = stop("notes");
        field.role = Accessible.Role.TEXT_FIELD;
        field.text = "draft";
        root.add(field);
        Claiming list = new Claiming();
        Keyed row = new Keyed("row");
        list.add(row);
        root.add(list);
        Days days = new Days();
        root.add(days);
        bind(root);
        frame();
        assertTrue(node("level").actions().has(Accessible.Action.INCREMENT), describe(tree()));
        assertTrue(node("row").actions().has(Accessible.Action.SELECT),
                "the claim" + describe(tree()));
        assertEquals("Ctrl+K", node("row").actions().keyBinding(), describe(tree()));
        assertTrue(node("open day").actions().has(Accessible.Action.SELECT), describe(tree()));
        assertNull(node("refused day").actions(),
                "a child its owner narrowed offers nothing though it declared SELECT"
                        + describe(tree()));
        assertFalse(node("refused day").has(Accessible.State.ENABLED), describe(tree()));
        assertNull(node("hour of a refused day").actions(),
                "nor does one nested under it" + describe(tree()));

        slider.setEnabled(false);
        field.setEnabled(false);
        list.setEnabled(false);
        days.setEnabled(false);
        frame();

        assertNull(node("level").actions(), "its own flag: no verb" + describe(tree()));
        assertTrue(node("level").value().readOnly(), "and no SET_VALUE" + describe(tree()));
        assertEquals(40.0, node("level").value().value(), "what it holds is still said");
        assertNull(node("notes").actions(), describe(tree()));
        assertTrue(node("notes").has(Accessible.State.READ_ONLY), "no SET_TEXT" + describe(tree()));
        assertTrue(row.isEnabled(), "the fixture leaves the row's own flag alone");
        assertNull(node("row").actions(),
                "an ancestor's flag: no verb, no claim, no key binding" + describe(tree()));
        assertNull(node("open day").actions(), "a child of a disabled owner" + describe(tree()));

        slider.setEnabled(true);
        field.setEnabled(true);
        list.setEnabled(true);
        days.setEnabled(true);
        frame();
        assertTrue(node("level").actions().has(Accessible.Action.INCREMENT), describe(tree()));
        assertFalse(node("level").value().readOnly());
        assertFalse(node("notes").has(Accessible.State.READ_ONLY));
        assertEquals(java.util.Set.of(Accessible.Action.PRESS, Accessible.Action.SELECT),
                node("row").actions().actions(), describe(tree()));
        assertEquals("Ctrl+K", node("row").actions().keyBinding());
        assertTrue(node("open day").actions().has(Accessible.Action.SELECT));
    }

    /** A widget with a verb and the key that performs it. */
    private static final class Keyed extends Probe {
        Keyed(String name) {
            super(Accessible.Role.LIST_ITEM, name);
            actions = new Accessible.Action[] {Accessible.Action.PRESS};
        }

        @Override
        protected void onAccessibility(limn.accessibility.Accessibility a) {
            super.onAccessibility(a);
            a.keyBinding("Ctrl+K");
        }
    }

    /** A container that claims SELECT on each child. */
    private static final class Claiming extends Group {
        @Override
        protected void onAccessibility(limn.accessibility.Accessibility a) {
            a.role(Accessible.Role.LIST);
        }

        @Override
        protected void onAccessibilityChild(Widget child, limn.accessibility.Accessibility a) {
            a.delegate(Accessible.Action.SELECT);
        }
    }

    /**
     * A widget drawing two days, the second narrowed as a refused day is, with a child nested
     * under it; both declare SELECT.
     */
    private static final class Days extends Probe {
        Days() {
            super(Accessible.Role.TABLE, "days");
        }

        @Override
        protected void onAccessibility(limn.accessibility.Accessibility a) {
            super.onAccessibility(a);
            a.child(1);
            a.role(Accessible.Role.CELL);
            a.name(limn.i18n.I18nString.literal("open day"));
            a.action(Accessible.Action.SELECT);
            a.endChild();
            a.child(2);
            a.role(Accessible.Role.CELL);
            a.name(limn.i18n.I18nString.literal("refused day"));
            a.action(Accessible.Action.SELECT);
            a.disabled();
            a.child(3);
            a.role(Accessible.Role.BUTTON);
            a.name(limn.i18n.I18nString.literal("hour of a refused day"));
            a.action(Accessible.Action.PRESS);
            a.endChild();
            a.endChild();
        }
    }

    @Test
    void theOpenLayerItselfCarriesModal() {
        Group root = new Group();
        root.add(stop("behind"));
        bind(root);
        Group dialog = new Group();
        dialog.add(stop("confirm"));
        scene.pushOverlay(dialog);
        frame();

        List<AccessibleNode> modal = nodesWith(Accessible.State.MODAL);
        assertEquals(1, modal.size(), "exactly one layer owns input: " + describe(tree()));
        assertEquals(0, modal.get(0).parent(), "an in-scene layer is a subtree of its own window");
    }

    @Test
    void theFocusableSetStillEqualsWhatTheKeyboardCanReach() {
        Group root = new Group();
        root.add(stop("behind"));
        root.add(new Probe(Accessible.Role.LABEL, "a label"));
        bind(root);
        Group dialog = new Group();
        dialog.add(stop("confirm"));
        dialog.add(stop("cancel"));
        scene.pushOverlay(dialog);
        frame();

        assertEquals(List.of("confirm", "cancel"), publishedFocusOrder(), describe(tree()));

        scene.removeOverlay(dialog);
        frame();
        assertEquals(List.of("behind"), publishedFocusOrder());
        assertTrue(node("behind").has(Accessible.State.ENABLED),
                "closing the layer gives the background back");
    }

    /**
     * A native window that is modal says so on its own node, and the window it blocks does not.
     * The facet's bit is "blocks what it owns", which is the dialog's fact; {@code isModalBlocked()}
     * is the owner's. The walk filled the one from the other, so a native dialog published itself
     * non-modal and its frozen owner as the modal one, and nothing read the field to notice.
     */
    @Test
    void aModalNativeWindowSaysSoOnItsOwnNodeAndTheWindowItBlocksDoesNot() {
        Group root = new Group();
        root.add(stop("ok"));
        bind(root);
        assertFalse(tree().node(0).window().modal(), "a plain window blocks nothing");

        window.modal = true;
        scene.requestRender();
        frame();
        assertTrue(tree().node(0).window().modal(),
                "the dialog's own window: " + describe(tree()));

        window.modal = false;
        window.modalBlocked = true;
        scene.requestRender();
        frame();
        assertFalse(tree().node(0).window().modal(),
                "the owner is the one blocked, and it blocks nothing: " + describe(tree()));
    }

    /**
     * A window the user is in says so, because a client that cannot find an active window reads
     * nothing at all.
     *
     * <p>The state is the answer to "which window is the user in" on every platform, and its
     * absence is not a degradation: a screen reader given a perfect tree, correct names and a
     * stream of focus events still announces nothing, because it resolves the event against an
     * active window first and finds none. That is exactly what Orca did against this toolkit --
     * "[frame] lacks active state", then "unable to find active window" -- and nothing headless
     * could have shown it, which is why it is pinned here now.
     */
    @Test
    void aWindowTheUserIsInPublishesActiveAndOneThatIsNotDoesNot() {
        Group root = new Group();
        root.add(stop("ok"));
        bind(root);
        frame();

        assertFalse(tree().node(0).has(Accessible.State.ACTIVE),
                "nothing has said the window has the focus yet" + describe(tree()));

        scene.windowFocusChanged(true);
        scene.inputBatchEnded();
        frame();

        assertTrue(tree().node(0).has(Accessible.State.ACTIVE),
                "the window node, and not the control inside it" + describe(tree()));
        assertFalse(node("ok").has(Accessible.State.ACTIVE),
                "ACTIVE on a control means the container's active descendant, which is a "
                        + "different fact and must not be confused with this one" + describe(tree()));

        scene.windowFocusChanged(false);
        scene.inputBatchEnded();
        frame();

        assertFalse(tree().node(0).has(Accessible.State.ACTIVE),
                "and it goes when the window does" + describe(tree()));
    }
}
