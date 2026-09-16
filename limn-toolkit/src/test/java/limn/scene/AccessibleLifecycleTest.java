package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.testing.RecordingAccessibilityBridge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a tree exists, when a window opens and closes, and what a bridge is left holding.
 *
 * <p>The first frame is the interesting moment. Binding a scene wires the input, the frame callback
 * and the invalidation, and it does not lay out: every box is zero and every widget is at the
 * origin. A tree built there would describe a window whose entire contents are a zero-size
 * rectangle in the corner, which is worse than describing nothing at all, because it looks like an
 * answer.
 */
class AccessibleLifecycleTest extends AccessibleTestBase {

    private Group sceneWithAButton() {
        Group root = new Group();
        root.add(new Probe(Accessible.Role.BUTTON, "Save"));
        return root;
    }

    @Test
    void bindingPublishesNothingAndTheFirstFramePublishesRealBoxes() {
        Group root = sceneWithAButton();
        bridge = new RecordingAccessibilityBridge();
        bridge.listening = true;
        window = new RecordingWindow();
        window.accessibility = bridge;
        scene = new Scene(root, nanos::get);
        scene.bind(window);

        assertTrue(bridge.published.isEmpty(), "at bind there are no boxes to describe");
        assertSame(AccessibleTree.EMPTY, bridge.host.republishNow(),
                "and asking for one on the spot answers with nothing rather than with zeros");

        frame();

        assertEquals(1, bridge.published.size());
        assertEquals(20f, node("Save").height(), "and the boxes are real: " + describe(tree()));
    }

    @Test
    void aBridgeThatAsksForAPrimingTreeGetsOneAndOneThatDoesNotGetsNothing() {
        Group root = sceneWithAButton();
        bridge = new RecordingAccessibilityBridge();
        bridge.listening = false;
        bridge.needsPriming = true;
        window = new RecordingWindow();
        window.accessibility = bridge;
        scene = new Scene(root, nanos::get);
        scene.bind(window);
        frame();

        assertEquals(1, bridge.published.size(),
                "the one bridge whose own gate cannot open until it has elements to offer");
        frame();
        assertEquals(1, bridge.published.size(), "and it is owed exactly one, not one per frame");

        Group other = sceneWithAButton();
        bind(other, false);
        frame();
        assertTrue(bridge.published.isEmpty(),
                "a bridge that did not ask pays no walk for a window nobody ever touches");
    }

    /**
     * The one platform that is asked whether this window has accessibility at all, in a message,
     * before the first frame can run — and that does not ask twice when it is told no. What it is
     * handed at the bind is the window and nothing under it: the widgets have no boxes yet, and the
     * priming publish on the first frame is still the one that describes them.
     */
    @Test
    void aBridgeAskedForARootBeforeTheFirstFrameIsHandedTheWindowAloneAtTheBind() {
        Group root = sceneWithAButton();
        bridge = new RecordingAccessibilityBridge();
        bridge.listening = false;
        bridge.needsPriming = true;
        bridge.needsRootAtBind = true;
        window = new RecordingWindow();
        window.accessibility = bridge;
        window.title = "A window";
        window.logicalWidth = 400;
        window.logicalHeight = 300;
        scene = new Scene(root, nanos::get);
        scene.bind(window);

        assertEquals(1, bridge.published.size(), "the bind owed this bridge a root");
        AccessibleTree atBind = bridge.published.get(0);
        assertEquals(1, atBind.nodeCount(), "the window alone: " + describe(atBind));
        assertEquals(Accessible.Role.WINDOW, atBind.root().role());
        assertEquals("A window", atBind.root().name());
        assertEquals(400f, atBind.root().width(),
                "the window's own size, which it has had since it was created");
        assertEquals(300f, atBind.root().height());
        long windowNode = atBind.root().id();

        frame();

        assertEquals(2, bridge.published.size(), "and the first frame still publishes the scene");
        assertEquals(20f, node("Save").height(), "with real boxes: " + describe(tree()));
        assertEquals(windowNode, tree().root().id(),
                "the contents arrived under a different window node than the one a client was "
                        + "handed at the bind, so whatever subscribed to that one is subscribed to "
                        + "an element that no longer exists");
    }

    /**
     * Every popup, menu and dialog is created hidden, bound, positioned, and only then shown, so a
     * walk at the bind is describing a window that is not on screen. §1.1's snapshot is worth
     * publishing early only while what it says is true when it is said.
     */
    @Test
    void theBindTreeOfAWindowNotYetShownSaysItIsNotShowing() {
        bridge = new RecordingAccessibilityBridge();
        bridge.needsPriming = true;  // the Windows bridge answers yes to both
        bridge.needsRootAtBind = true;
        window = new RecordingWindow();
        window.accessibility = bridge;
        window.visible = false; // as PopupMenu, ComboBox, Dialog and DatePicker all bind it
        window.logicalWidth = 200;
        window.logicalHeight = 100;
        scene = new Scene(sceneWithAButton(), nanos::get);
        scene.bind(window);

        AccessibleNode root = bridge.published.get(0).root();
        assertFalse(root.has(Accessible.State.VISIBLE),
                "a window bound before it is shown was published as if it were on screen");
        assertFalse(root.has(Accessible.State.SHOWING));

        window.visible = true;
        frame();

        assertTrue(tree().root().has(Accessible.State.VISIBLE),
                "and the first frame after the show has to carry the change");
    }

    /**
     * The bind publish runs a whole difference, and a difference that is computed and thrown away
     * is a difference nobody hears. `WINDOW_ACTIVATED` is reserved when the window node first
     * arrives `ACTIVE`, and only then — so a first frame that finds it active already reserves
     * nothing, and on Windows that is the event that raises focus into the window.
     */
    @Test
    void theBindPublishDoesNotSwallowTheWindowsOwnActivation() {
        bridge = new RecordingAccessibilityBridge();
        bridge.needsPriming = true;  // the Windows bridge answers yes to both
        bridge.needsRootAtBind = true;
        window = new RecordingWindow();
        window.accessibility = bridge;
        window.logicalWidth = 400;
        window.logicalHeight = 300;
        scene = new Scene(sceneWithAButton(), nanos::get);
        // An embedder that knows the window has focus before it binds the scene into it.
        scene.windowFocusChanged(true);
        scene.inputBatchEnded();
        scene.bind(window);
        frame();

        assertEquals(1, bridge.countOf(AccessibleEvent.Type.WINDOW_ACTIVATED),
                "the window became active and nothing said so");
    }

    @Test
    void aBridgeThatDidNotAskForARootIsHandedNothingAtTheBind() {
        Group root = sceneWithAButton();
        bridge = new RecordingAccessibilityBridge();
        bridge.listening = true;
        bridge.needsRootAtBind = false;
        window = new RecordingWindow();
        window.accessibility = bridge;
        window.logicalWidth = 400;
        window.logicalHeight = 300;
        scene = new Scene(root, nanos::get);
        scene.bind(window);

        assertTrue(bridge.published.isEmpty(),
                "two platforms answer their own 'is anyone listening' and pay nothing before a "
                        + "frame; only the one that is asked in a message is owed a root");
    }

    @Test
    void bindingRaisesAWindowOpenedAndClosingRaisesAWindowClosed() {
        Group root = sceneWithAButton();
        bridge = new RecordingAccessibilityBridge();
        bridge.listening = true;
        window = new RecordingWindow();
        window.accessibility = bridge;
        scene = new Scene(root, nanos::get);
        scene.bind(window);

        assertEquals(1, bridge.countOf(AccessibleEvent.Type.WINDOW_OPENED));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.WINDOW_CLOSED));

        scene.windowClosed();
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.WINDOW_CLOSED));
        assertTrue(bridge.elements.isEmpty(), "and it is left holding nothing");
    }

    /**
     * A scene bound over a live window never learns it was replaced, so the outgoing one cannot
     * raise its own close and the incoming one does not know there was anything to close. The
     * bridge is the one object that holds that fact, which is why the window pair is raised there
     * and beside the sweep that makes it true.
     */
    @Test
    void asecondSceneBoundOverTheSameWindowClosesTheFirstAndLeavesOneHost() {
        bind(sceneWithAButton());
        frame();
        assertFalse(bridge.elements.isEmpty());
        bridge.events.clear();

        Scene replacement = new Scene(sceneWithAButton(), nanos::get);
        replacement.bind(window);

        assertEquals(List.of(AccessibleEvent.Type.WINDOW_CLOSED, AccessibleEvent.Type.WINDOW_OPENED),
                bridge.events.stream().map(AccessibleEvent::type).toList());
        assertEquals(2, bridge.attachments);
        assertTrue(bridge.elements.isEmpty(),
                "a rebind invalidates every element at once; leaving them alive would leak the "
                        + "whole previous tree and let a client resolve against identifiers the "
                        + "new tree may reuse");
    }

    /**
     * An announcement buys the frame that delivers it, and only while something is listening. The
     * steady state while a screen reader reads a quiet interface is a loop parked with no frame
     * pending: an announcement queued into that state would never be spoken, which is the
     * application's one way of saying something out loud going silent exactly when it is most
     * likely to be used.
     */
    @Test
    void anAnnouncementBuysItsOwnFrameAndArrivesOnAFrameThatChangedNothing() {
        bind(sceneWithAButton());
        frame();
        window.frameRequests = 0;
        bridge.events.clear();
        int published = bridge.published.size();

        scene.announce("Export finished", Accessible.Politeness.POLITE);
        assertEquals(1, window.frameRequests, "it bought the frame that drains it");

        frame();

        AccessibleEvent spoken = bridge.first(AccessibleEvent.Type.ANNOUNCEMENT);
        assertNotNull(spoken, "" + bridge.events);
        assertEquals("Export finished", spoken.newValue());
        assertEquals(Accessible.Politeness.POLITE, spoken.politeness());
        assertEquals(published, bridge.published.size(),
                "nothing in the tree moved, and the announcement arrived anyway");
    }

    @Test
    void anAnnouncementArrivesOnARePresentFrameToo() {
        bind(sceneWithAButton());
        frame();
        bridge.events.clear();

        scene.announce("Half way", Accessible.Politeness.POLITE);
        rePresentFrame();

        assertEquals(1, bridge.countOf(AccessibleEvent.Type.ANNOUNCEMENT),
                "an announcement is the application speaking, not a property of a node");
    }

    /**
     * A publish from inside the platform's own callback stores the tree and does nothing else: the
     * platform is standing on the elements a sweep would release, on the array a re-push would
     * replace, and inside the callback a drain would post from.
     */
    @Test
    void aReentrantPublishDefersEverythingAndBuysTheFrameThatPaysIt() {
        Group root = sceneWithAButton();
        bind(root);
        frame();
        long id = node("Save").id();
        window.frameRequests = 0;

        // What a platform callback does: something changed, and it cannot wait for a frame.
        root.children().get(0).setAccessibleName("Save the document");
        AccessibleTree fresh = bridge.host.republishNow();

        assertEquals("Save the document", fresh.find(id).name(), "the answer is exact");
        assertTrue(bridge.reentrant.get(bridge.reentrant.size() - 1),
                "and it was handed over with the platform on the stack");
        assertTrue(window.frameRequests >= 1,
                "a deferred obligation needs a frame that is going to happen");
    }

    @Test
    void aReentrantPublishThatFindsNothingDirtyPublishesNothingAndBuysNoFrame() {
        bind(sceneWithAButton());
        frame();
        int published = bridge.published.size();
        window.frameRequests = 0;

        AccessibleTree same = bridge.host.republishNow();

        assertEquals(published, bridge.published.size());
        assertEquals(0, window.frameRequests,
                "the one call that asks for nothing is the one that defers nothing");
        assertSame(tree(), same);
    }

    /**
     * The re-stamp branch of {@code republishNow} is the same call as the walk branch beside it,
     * and hands the tree over the same way. A restamp is four numbers rather than a walk, which is
     * why it was the branch that went unread: the bridge is still handed the tree from inside the
     * platform's own pump, standing on the elements a sweep would release (brief item 5,
     * 2026-09-15).
     */
    @Test
    void aReentrantRestampIsHandedOverAsReentrantAsAReentrantWalk() {
        bind(sceneWithAButton());
        frame();

        // The window moved, which changes no box in the tree, only where the tree is -- and then
        // the platform asks from inside its own callback, before the frame that would restamp.
        window.setScreenPosition(300, 120);
        bridge.host.requestRestamp();
        runtime.drain();     // requestRestamp is safe from any thread, so it posts the flag
        bridge.reentrant.clear();
        window.frameRequests = 0;

        AccessibleTree stamped = bridge.host.republishNow();

        assertEquals(1, bridge.reentrant.size(), "the restamp published");
        assertTrue(bridge.reentrant.get(0),
                "and it was handed over with the platform on the stack, like the walk branch");
        assertEquals(300, stamped.screenX(), "the answer is the moved window's");
        assertTrue(window.frameRequests >= 1,
                "a deferred obligation needs a frame that is going to happen");
    }

    /** The frame's own re-stamp is nobody's callback, and the bridge may sweep on it. */
    @Test
    void theFramesRestampIsNotReentrant() {
        bind(sceneWithAButton());
        frame();

        window.setScreenPosition(300, 120);
        bridge.host.requestRestamp();
        runtime.drain();
        bridge.reentrant.clear();
        frame();

        assertEquals(1, bridge.reentrant.size(), "the restamp published");
        assertFalse(bridge.reentrant.get(0),
                "on the scene's own frame nothing of the platform's is on the stack");
    }

    @Test
    void aDescribePassNeitherLaysOutNorMutatesTheTree() {
        Group root = sceneWithAButton();
        bind(root);
        frame();
        int childrenBefore = root.children().size();
        float boxBefore = root.children().get(0).height();

        root.children().get(0).setAccessibleName("Renamed");
        bridge.host.republishNow();

        assertEquals(childrenBefore, root.children().size());
        assertEquals(boxBefore, root.children().get(0).height());
    }

    /**
     * A difference wider than the budget stops being a list of events and becomes "everything
     * changed", which a bridge answers by sweeping what it holds against the tree it was handed.
     * That is stronger than replaying what was dropped, and it is the operation a rebind needs
     * anyway — which is why there is one of it per bridge and not three. What follows the
     * collapse is the reserved tail (ADR 039 §1.10, amended 2026-09-14): here the one structure
     * change on the window that names the four hundred rows that left it.
     */
    @Test
    void adifferenceWiderThanTheBudgetCollapsesAndTheBridgeCanStillReleaseWhatWentAway() {
        Group root = new Group();
        for (int i = 0; i < 400; i++) {
            root.add(new Probe(Accessible.Role.BUTTON, "button " + i));
        }
        bind(root);
        frame();
        assertEquals(401, bridge.elements.size(), "a window and four hundred buttons");
        bridge.events.clear();

        for (int i = 0; i < 400; i++) {
            root.remove(root.children().get(root.children().size() - 1));
        }
        frame();

        assertEquals(AccessibleEvent.Type.INVALIDATED, bridge.events.get(0).type(),
                "the collapse comes first: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "four hundred destructions are what the collapse stands for: " + bridge.events);
        assertEquals(2, bridge.events.size(),
                "the collapse and the tail, not four hundred: " + bridge.events);
        AccessibleEvent structure = bridge.events.get(1);
        assertEquals(AccessibleEvent.Type.STRUCTURE_CHANGED, structure.type());
        assertEquals(tree().root().id(), structure.nodeId(), "on the window, which survived");
        assertEquals(400, structure.removedChildren().size(),
                "naming every child that left it: " + structure);
        assertEquals(1, bridge.elements.size(),
                "and the sweep released every element that went away");
    }

    /**
     * CRIT-3: the collapse used to take the focus with it. Three hundred labels becoming
     * visible is six hundred state changes, well past the budget, and a button taking the
     * focus in the same frame — a dialog opening, a tab switching — was one of the events
     * thrown away with them, so the bridge swept and never learned where the user was. The
     * final focus change is in the reserved tail, outside the budget, and arrives after the
     * collapse.
     */
    @Test
    void aFocusMoveInTheSameFrameAsACollapseIsStillAnnounced() {
        Group root = new Group();
        Probe button = new Probe(Accessible.Role.BUTTON, "OK");
        button.setFocusable(true);
        root.add(button);
        List<Probe> labels = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            Probe label = new Probe(Accessible.Role.LABEL, "label " + i);
            label.setVisible(false);
            labels.add(label);
            root.add(label);
        }
        bind(root);
        frame();
        assertEquals(302, tree().nodeCount(), "the hidden labels are published, hidden");
        bridge.events.clear();

        for (Probe label : labels) {
            label.setVisible(true);
        }
        scene.requestFocus(button);
        frame();

        assertEquals(AccessibleEvent.Type.INVALIDATED, bridge.events.get(0).type(),
                "six hundred state changes collapse: " + bridge.events.size() + " events");
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.FOCUS_CHANGED),
                "and the focus that moved in the same frame survives the collapse: "
                        + bridge.events);
        assertEquals(node("OK").id(), bridge.first(AccessibleEvent.Type.FOCUS_CHANGED).nodeId());
        assertTrue(bridge.events.indexOf(bridge.first(AccessibleEvent.Type.FOCUS_CHANGED)) > 0,
                "after the collapse, so a bridge that swept on it still hears the focus");
        assertEquals(node("OK").id(), tree().focused());
    }
}
