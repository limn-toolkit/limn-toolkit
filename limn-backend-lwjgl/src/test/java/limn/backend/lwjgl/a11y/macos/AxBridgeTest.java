package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the bridge assembles out of the pieces below it: the links it hands the platform, the boxes
 * it converts, and what a publish decides to push.
 *
 * <p>The Objective-C calls are skipped on a machine with no AppKit, which is where these run — so
 * what is asserted is the assembly and never the conversation with a reader. That conversation is
 * the guest's to verify, and it is the one thing this phase still owes.
 */
class AxBridgeTest {

    /** A window with a group, and a button inside the group: three levels, which is the point. */
    private static AccessibleTree aNestedWindow(int buttons) {
        Accessibility a = new Accessibility();
        a.beginWalk(480, 320, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 480, 320);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 20, 60, 200, 200);
        a.role(Accessible.Role.GROUP);
        a.name(I18nString.literal("A group"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, false, false);
        for (int i = 0; i < buttons; i++) {
            a.begin(1002 + i, 1, Locale.ENGLISH, 40, 96 + 40L * i, 160, 40);
            a.role(Accessible.Role.BUTTON);
            a.name(I18nString.literal("Button " + i), Accessible.NameFrom.CONTENT);
            a.action(Accessible.Action.PRESS);
            a.inherited(true, true, true, true, false);
            a.end();
        }
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    /** The same window, with one node holding the keyboard. */
    private static AccessibleTree aNestedWindowWithFocus(long focusedId) {
        Accessibility a = new Accessibility();
        a.beginWalk(480, 320, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 480, 320);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 20, 60, 200, 200);
        a.role(Accessible.Role.GROUP);
        a.name(I18nString.literal("A group"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, false, false);
        a.begin(1002, 1, Locale.ENGLISH, 40, 96, 160, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Button 0"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, false);
        a.state(Accessible.State.FOCUSED, true);
        a.end();
        a.end();
        a.end();
        return a.publish(focusedId, 0, 0, 1f, true);
    }

    @Test
    void aMachineWithNoAppKitGetsNoBridgeAtAll() {
        assertSame(AccessibilityBridge.NONE, AxBridge.openIfEnabled(0),
                "a zero window handle is refused even where the platform is present, because the "
                        + "backend answers zero on a platform it has not been taught");
    }

    @Test
    void thisPlatformIsTheOneThatAsksForAPrimingPublish() {
        assertTrue(AxBridge.withoutThePlatform().needsPrimingPublish());
    }

    @Test
    void nothingIsListeningUntilSomethingHasAsked() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        bridge.publish(aNestedWindow(2), false);
        assertFalse(bridge.isListening(),
                "there is no UiaClientsAreListening here; the gate is 'someone has asked'");
        bridge.entered();
        assertTrue(bridge.isListening());
    }

    @Test
    void theWindowRootIsNotVendedAndItsChildrenAreWhatGetsPushed() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(2);
        bridge.publish(tree, false);
        // One element, for the group. AppKit already vends the window, and a second one inside it
        // would be announced twice with two sets of window actions (§2.2).
        assertEquals(1, bridge.pushedElements().length);
        assertEquals(1, bridge.elementCount(), "and only the pushed level is minted up front");
    }

    @Test
    void aChildOfTheRootAnswersTheContentViewAsItsParent() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(1);
        bridge.publish(tree, false);
        // 0 is the content view here, which is what withoutThePlatform stands in with -- the point
        // is that it is not an element of ours, because AppKit was handed the view and expects it.
        assertEquals(0L, bridge.parentElementOf(tree.find(1001)));
        assertEquals(bridge.pushedElements()[0], bridge.parentElementOf(tree.find(1002)),
                "and a deeper node answers with its own parent's element");
    }

    @Test
    void everythingBelowThePushedLevelIsMintedOnlyWhenAsked() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(2);
        bridge.publish(tree, false);
        assertEquals(1, bridge.elementCount());
        long[] children = bridge.childElementsOf(tree.find(1001));
        assertEquals(2, children.length);
        assertEquals(3, bridge.elementCount(), "the pull is what mints them");
        assertArrayEquals(children, bridge.childElementsOf(tree.find(1001)),
                "and a second ask gets the same objects, so a client's reference stays valid");
    }

    @Test
    void thePushedElementsHaveTheirBoxesAfterTheVeryFirstPublish() {
        // The first live run of this bridge got the order wrong: frames were applied before the
        // push, so on the priming publish there was nothing holding an element yet and every node
        // reached the client as a zero-size rectangle at the origin. A walk reads that perfectly --
        // names, roles and identifiers were all correct -- and a hit test cannot resolve it at all,
        // which is why it took a client to see it (§13.21).
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(1);
        bridge.publish(tree, false);
        double[] box = bridge.lastFrameOf(1001);
        assertNotNull(box, "the pushed level has no box, so it is a zero-size rectangle to a client");
        assertTrue(box[2] > 0 && box[3] > 0, "a pushed element with no size cannot be hit-tested");
    }

    @Test
    void anElementMintedByAPullGetsItsBoxAtOnceRatherThanNextFrame() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(1);
        bridge.publish(tree, false);
        bridge.childElementsOf(tree.find(1001));
        assertNotNull(bridge.lastFrameOf(1002),
                "the moment a client asks for a node is the moment it reads it; a box that arrives "
                        + "on the next frame arrives after the answer");
    }

    @Test
    void aNodesBoxIsConvertedAgainstItsOwnParentAndNotTheScene() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(1);
        bridge.publish(tree, false);
        bridge.childElementsOf(tree.find(1001));   // mint the button
        bridge.publish(tree, false);               // and let the publish give it a box

        // The group: 200x200 at (20,60) in a 480x320 scene, so 60 up from the view's bottom.
        assertArrayEquals(new double[] { 20, 60, 200, 200 }, bridge.lastFrameOf(1001));
        // The button: 160x40 at (40,96), inside a group whose bottom edge is at 260. 260-136 = 124.
        // Against the SCENE it would be 320-136 = 184, which is the wrong answer a walk cannot see.
        assertArrayEquals(new double[] { 20, 124, 160, 40 }, bridge.lastFrameOf(1002));
    }

    @Test
    void theRootsChildrenArePushedAgainOnlyWhenTheyChange() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        bridge.publish(aNestedWindow(1), false);
        assertEquals(1, bridge.pushes());
        bridge.publish(aNestedWindow(1), false);
        assertEquals(1, bridge.pushes(),
                "an unchanged root costs no push; the array AppKit holds is still right");
        bridge.publish(aNestedWindow(2), false);
        assertEquals(1, bridge.pushes(),
                "and a change BELOW the root costs no push either, because it is a pull");
    }

    @Test
    void aReentrantPublishPushesNothingAtAll() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        bridge.publish(aNestedWindow(1), false);
        int before = bridge.pushes();
        bridge.publish(AccessibleTree.EMPTY, true);
        assertEquals(before, bridge.pushes(),
                "re-pushing from inside an AX callback replaces the array AppKit is walking (§3.2)");
        assertTrue(bridge.obligationsDeferred());
    }

    @Test
    void attachingOverALiveTreeInvalidatesEveryElement() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(2);
        bridge.publish(tree, false);
        bridge.childElementsOf(tree.find(1001));
        assertEquals(3, bridge.elementCount());
        bridge.attach(null);
        assertEquals(0, bridge.elementCount(),
                "a rebind invalidates them all at once, and an earlier draft released none of them");
        assertEquals(0, bridge.pushedElements().length, "so the next publish must push again");
    }

    @Test
    void aMessageToAnElementWhoseNodeIsGoneAnswersNothingRatherThanFailing() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(1);
        bridge.publish(tree, false);
        long element = bridge.pushedElements()[0];
        assertNotNull(bridge.nodeFor(element));
        bridge.publish(AccessibleTree.EMPTY, false);
        assertNull(bridge.nodeFor(element),
                "a client using a reference it held across a destruction is not an error");
    }

    @Test
    void anEmittedEventWaitsForTheNextOrdinaryFrame() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(1);
        bridge.publish(tree, false);
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
        assertEquals(1, bridge.queuedEvents(),
                "every post is a cross-process call; emit is not the place to make one");
        assertTrue(bridge.postedNotifications().isEmpty());
        bridge.publish(tree, false);
        assertEquals(List.of("NSAccessibilityFocusedUIElementChangedNotification"),
                bridge.postedNotifications());
    }

    @Test
    void aReentrantPublishPostsNothingAndKeepsTheEvents() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(1);
        bridge.publish(tree, false);
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
        bridge.publish(tree, true);
        assertTrue(bridge.postedNotifications().isEmpty(),
                "a post from inside an AX callback re-enters the platform on our own objects (§3.2)");
        assertEquals(1, bridge.queuedEvents(), "and the event is kept for the frame that follows");
    }

    @Test
    void anEventNamingANodeNoClientHasAskedAboutIsPostedOnNothing() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(1);
        bridge.publish(tree, false);
        // 1002 is below the pushed level, so no element exists for it until something pulls.
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.VALUE_CHANGED, 1002));
        bridge.publish(tree, false);
        assertTrue(bridge.postedNotifications().isEmpty(),
                "a notification about an object the platform has never seen reaches no registration");
    }

    @Test
    void theEventsThisPlatformIsNotToldAreNotPosted() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(1);
        bridge.publish(tree, false);
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.NODE_DESTROYED, 1001));
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.WINDOW_OPENED, 1000));
        bridge.publish(tree, false);
        assertTrue(bridge.postedNotifications().isEmpty(),
                "AppKit is already saying both, and ours would be a second copy of each");
    }

    @Test
    void aCollapsedQueueSweepsTheRegistryAndForcesAFreshPush() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(2);
        bridge.publish(tree, false);
        bridge.childElementsOf(tree.find(1001));
        assertEquals(3, bridge.elementCount());

        // A difference wider than the queue, then a tree those nodes are no longer in. The
        // per-node destructions went with the collapse, so the sweep is the only thing that can
        // release them.
        for (int i = 0; i <= AxEvents.CAPACITY; i++) {
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.VALUE_CHANGED, 1002));
        }
        bridge.publish(AccessibleTree.EMPTY, false);
        assertEquals(0, bridge.elementCount(),
                "a collapse is exactly the burst whose per-node destructions were dropped");
        assertEquals(0, bridge.pushedElements().length,
                "and the array AppKit holds names elements that were just released");
    }

    @Test
    void theFocusedElementIsAnsweredFromTheSnapshot() {
        // §13.22, and the live run is what settled it. Posting AXFocusedUIElementChanged is proven
        // to be delivered; being able to answer "where am I" afterwards was not, and a reader that
        // is told the focus moved and cannot find out where it went does nothing at all -- which is
        // exactly what VoiceOver did until this was answered.
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindowWithFocus(1002);
        bridge.publish(tree, false);
        assertEquals(bridge.childElementsOf(tree.find(1001))[0], bridge.focusedElement());
    }

    @Test
    void nothingFocusedIsAnsweredWithNilRatherThanWithSomethingNearby() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        bridge.publish(aNestedWindow(1), false);
        assertEquals(0L, bridge.focusedElement(),
                "answering the view itself would put the reader on a thing with no name");
    }

    @Test
    void aFocusedNodeThatLeftTheTreeIsNotVendedAsFocused() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        bridge.publish(aNestedWindowWithFocus(1002), false);
        bridge.publish(AccessibleTree.EMPTY, false);
        assertEquals(0L, bridge.focusedElement());
    }

    @Test
    void everyNodeInTheTreeIsCountedAsLiveForTheReconciliationSweep() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        bridge.publish(aNestedWindow(2), false);
        assertEquals(4, bridge.liveNodeIds().size());
    }
}
