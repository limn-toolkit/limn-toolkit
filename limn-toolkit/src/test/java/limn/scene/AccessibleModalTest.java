package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
