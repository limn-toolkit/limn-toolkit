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

    /** A window whose root has two children, where {@link #aNestedWindow} has one. */
    private static AccessibleTree aWindowWithTwoGroups() {
        Accessibility a = new Accessibility();
        a.beginWalk(480, 320, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 480, 320);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        for (int i = 0; i < 2; i++) {
            a.begin(1001 + 10L * i, 0, Locale.ENGLISH, 20, 60 + 100L * i, 200, 80);
            a.role(Accessible.Role.GROUP);
            a.name(I18nString.literal("Group " + i), Accessible.NameFrom.CONTENT);
            a.inherited(true, true, true, false, false);
            a.end();
        }
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
        // Focus is the tree's stamp (the focusedId handed to publish), not a bit a describe
        // hook may write: the builder refuses FOCUSED, and the bridge answers from the stamp.
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        a.end();
        return a.publish(focusedId, 0, 0, 1f, true);
    }

    /** Attaches a recording trace to a bridge, the way the live probe does, and hands back its lines. */
    private static List<String> traced(AxBridge bridge) {
        List<String> lines = new java.util.ArrayList<>();
        bridge.trace(lines::add);
        return lines;
    }

    /** The notification symbols a trace saw posted, in order. */
    private static List<String> posted(List<String> trace) {
        return trace.stream().filter(line -> line.startsWith("posted "))
                .map(line -> line.substring("posted ".length())).toList();
    }

    @Test
    void aMachineWithNoAppKitGetsNoBridgeAtAll() {
        assertSame(AccessibilityBridge.NONE, AxBridge.openIfEnabled(0),
                "a zero window handle is refused even where the platform is present, because the "
                        + "backend answers zero on a platform it has not been taught");
    }

    /**
     * MACOS-NEW-6's "never silently": {@code Bridges.openFor} answers {@code NONE} for anything thrown
     * at it and says nothing, so whatever an open throws — an {@link Error} from a native link as much
     * as an exception from the constructor — is said here before it becomes {@code NONE}.
     */
    @Test
    void anOpenThatThrowsAnythingSaysSoAndAnswersNone() {
        List<java.util.logging.LogRecord> logged = new java.util.ArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                logged.add(record);
            }

            @Override public void flush() {
            }

            @Override public void close() {
            }
        };
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(AxBridge.class.getName());
        logger.addHandler(capture);
        try {
            UnsatisfiedLinkError link = new UnsatisfiedLinkError("no libffi closure for you");
            assertSame(AccessibilityBridge.NONE, AxBridge.openedOrSaid(() -> {
                throw link;
            }), "an Error is still a window that opens without accessibility");
            IllegalStateException refused = new IllegalStateException("objc_allocateClassPair failed");
            assertSame(AccessibilityBridge.NONE, AxBridge.openedOrSaid(() -> {
                throw refused;
            }));
            assertEquals(2, logged.size(), "and each is said, once");
            assertEquals(java.util.logging.Level.SEVERE, logged.get(0).getLevel());
            assertSame(link, logged.get(0).getThrown(), "naming what was thrown");
            assertSame(refused, logged.get(1).getThrown());

            logged.clear();
            AxBridge opened = AxBridge.withoutThePlatform();
            assertSame(opened, AxBridge.openedOrSaid(() -> opened));
            assertSame(AccessibilityBridge.NONE, AxBridge.openedOrSaid(() -> AccessibilityBridge.NONE),
                    "a machine with no AppKit answers NONE by design");
            assertTrue(logged.isEmpty(), "and neither an open nor a deliberate NONE is an error");
        } finally {
            logger.removeHandler(capture);
        }
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

    /** The nested window, with the group's first button labelled by and described by its siblings. */
    private static AccessibleTree aNestedWindowWithRelations() {
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
        a.role(Accessible.Role.LABEL);
        a.name(I18nString.literal("Email"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, false, false);
        a.end();
        a.begin(1003, 1, Locale.ENGLISH, 40, 136, 160, 40);
        a.role(Accessible.Role.TEXT_FIELD);
        a.name(I18nString.literal("Email"), Accessible.NameFrom.LABEL);
        a.relation(Accessible.Relation.LABELLED_BY, 1002L);
        a.relation(Accessible.Relation.DESCRIBED_BY, 1004L);
        a.relation(Accessible.Relation.POPUP_FOR, 1000L);  // the elided window root: not vended
        a.inherited(true, true, true, true, false);
        a.end();
        a.begin(1004, 1, Locale.ENGLISH, 40, 176, 160, 40);
        a.role(Accessible.Role.LABEL);
        a.name(I18nString.literal("Enter an address"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        a.end();
        a.resolveRelations((kind, target) -> (Long) target);
        return a.publish(0, 0, 0, 1f, true);
    }

    /**
     * What {@code accessibilityLinkedUIElements} answers: every relation's target as an element,
     * minted on the ask like a child is, and never the window root, which AppKit vends itself.
     */
    @Test
    void aNodesRelationsAreItsLinkedElementsMintedOnTheAsk() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindowWithRelations();
        bridge.publish(tree, false);
        assertEquals(1, bridge.elementCount(), "only the pushed level exists before anyone asks");

        long[] linked = bridge.linkedElementsOf(tree.find(1003));
        assertEquals(2, linked.length,
                "the caption and the message; the relation to the window root names no element of ours");
        assertEquals(3, bridge.elementCount(), "both were minted by the ask");
        long[] kids = bridge.childElementsOf(tree.find(1001));
        assertEquals(kids[0], linked[0], "the caption's element is the one the group vends for it");
        assertEquals(kids[2], linked[1], "and so is the message's");

        assertEquals(0, bridge.linkedElementsOf(tree.find(1004)).length,
                "a node that declares no relation links to nothing");
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
    void detachingDemotesEveryElementAndRestoresTheViewBeforeItFreesTheClosures() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(2);
        bridge.publish(tree, false);
        bridge.childElementsOf(tree.find(1001));
        assertEquals(3, bridge.elementCount());
        bridge.detach();
        List<String> teardown = bridge.teardown();
        // Every element first: a client that still holds one must land on NSAccessibilityElement's
        // own answers, because the closures it would otherwise reach are about to be freed.
        assertEquals(List.of("element demoted", "element demoted", "element demoted"),
                teardown.subList(0, 3), "each of the three minted elements is demoted, and before "
                        + "anything else goes");
        // Then the view, then -- and only then -- the closures. VoiceOver asks the content view
        // where the focus is while glfwDestroyWindow pumps the run loop, which is after this
        // detach; the closure that answered was freed, and the ask was a SIGSEGV in liblwjgl.
        assertEquals(List.of("children taken back", "view restored", "closures freed"),
                teardown.subList(3, teardown.size()),
                "the view gets its class back before the closure behind it is freed");
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
    void anEmittedEventIsPostedWhenItsFrameEndsAndNeverByAPublish() {
        // Restated by MACOS-NEW-8. This case used to be anEmittedEventWaitsForTheNextOrdinaryFrame
        // and asserted that the next publish posted it; a scene publishes only when its tree
        // changed, so "the next publish" was "the next change", and a still window never told the
        // last thing that happened. The frame's end is where it goes out now.
        AxBridge bridge = AxBridge.withoutThePlatform();
        List<String> trace = traced(bridge);
        AccessibleTree tree = aNestedWindow(1);
        bridge.publish(tree, false);
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
        assertEquals(1, bridge.queuedEvents(),
                "every post is a cross-process call; emit is not the place to make one");
        assertTrue(posted(trace).isEmpty());
        bridge.publish(tree, false);
        assertTrue(posted(trace).isEmpty(), "a publish posts nothing: its own events are not out yet");
        bridge.frameEnded();
        assertEquals(List.of("NSAccessibilityFocusedUIElementChangedNotification"),
                posted(trace));
        assertEquals(0, bridge.queuedEvents());
        bridge.frameEnded();
        assertEquals(1, posted(trace).size(), "and a frame that said nothing posts nothing");
    }

    @Test
    void aFrameEndWithNothingToSayAllocatesNothing() {
        // It runs on every frame now, the quiet ones included, so it has to cost what
        // AccessibleIdleCostTest asks of a frame with a live bridge and a clean tree: nothing.
        org.junit.jupiter.api.Assumptions.assumeTrue(limn.testing.AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        AxBridge bridge = AxBridge.withoutThePlatform();
        bridge.publish(aNestedWindow(2), false);
        bridge.frameEnded();
        Runnable quietFrameEnd = bridge::frameEnded;
        assertEquals(0, limn.testing.AllocationProbe.leastAllocatedBy(quietFrameEnd, 60),
                "a frame that emitted nothing costs no memory to end");
    }

    @Test
    void aReentrantPublishPostsNothingAndKeepsTheEvents() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        List<String> trace = traced(bridge);
        AccessibleTree tree = aNestedWindow(1);
        bridge.publish(tree, false);
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
        bridge.publish(tree, true);
        assertTrue(posted(trace).isEmpty(),
                "a post from inside an AX callback re-enters the platform on our own objects (§3.2)");
        assertEquals(1, bridge.queuedEvents(), "and the event is kept for the frame that follows");
        bridge.frameEnded();
        assertEquals(List.of("NSAccessibilityFocusedUIElementChangedNotification"), posted(trace),
                "which posts it when it ends, whether or not it published");
        assertFalse(bridge.obligationsDeferred(), "and pays the re-push and the boxes there too");
    }

    @Test
    void theFrameAfterAReentrantPublishPushesTheRootItDeferred() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        bridge.publish(aNestedWindow(1), false);
        int before = bridge.pushes();
        // The root's children changed inside an AX callback: a second group is a new root child.
        bridge.publish(aWindowWithTwoGroups(), true);
        assertEquals(before, bridge.pushes(), "never from inside the callback (§3.2)");
        bridge.frameEnded();
        assertEquals(before + 1, bridge.pushes(),
                "the frame it asked for publishes nothing when the tree is clean, and pushes anyway");
        assertEquals(2, bridge.pushedElements().length);
    }

    @Test
    void anEventNamingANodeNoClientHasAskedAboutIsPostedOnNothing() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        List<String> trace = traced(bridge);
        AccessibleTree tree = aNestedWindow(1);
        bridge.publish(tree, false);
        // 1002 is below the pushed level, so no element exists for it until something pulls.
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.VALUE_CHANGED, 1002));
        bridge.frameEnded();
        assertEquals(0, bridge.queuedEvents(), "it was drained");
        assertTrue(posted(trace).isEmpty(),
                "a notification about an object the platform has never seen reaches no registration");
    }

    @Test
    void theEventsThisPlatformIsNotToldAreNotPosted() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        List<String> trace = traced(bridge);
        AccessibleTree tree = aNestedWindow(1);
        bridge.publish(tree, false);
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.NODE_DESTROYED, 1001));
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.WINDOW_OPENED, 1000));
        bridge.frameEnded();
        assertEquals(0, bridge.queuedEvents(), "both were drained");
        assertTrue(posted(trace).isEmpty(),
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
        bridge.frameEnded();
        assertEquals(0, bridge.elementCount(),
                "a collapse is exactly the burst whose per-node destructions were dropped");
        assertEquals(0, bridge.pushedElements().length,
                "and the array AppKit holds names elements that were just released");
    }

    @Test
    void aCollapseOverALiveTreePushesTheRootAgainWhenTheFrameEndsAndNotAtTheNextChange() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(2);
        bridge.publish(tree, false);
        int before = bridge.pushes();
        for (int i = 0; i <= AxEvents.CAPACITY; i++) {
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.VALUE_CHANGED, 1002));
        }
        bridge.frameEnded();
        assertEquals(1, bridge.collapses());
        assertEquals(before + 1, bridge.pushes(),
                "the sweep forgot what was pushed; on a still window the next publish may never come");
        assertEquals(1, bridge.pushedElements().length);
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
    void anActionWithNoHostIsRefusedRatherThanDroppedSilently() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        bridge.publish(aNestedWindow(1), false);
        assertFalse(bridge.perform(1002, Accessible.Action.PRESS),
                "between a detach and an attach the scene that owned the widget is gone");
    }

    @Test
    void anActionGoesStraightToTheHostAndAnswersWhatItAnswers() {
        boolean[] asked = {false};
        AxBridge bridge = AxBridge.withoutThePlatform();
        bridge.attach(new AccessibilityBridge.Host() {
            @Override public void requestRepublish() { }
            @Override public void requestRestamp() { }
            @Override public AccessibleTree republishNow() { return AccessibleTree.EMPTY; }
            @Override public boolean perform(long nodeId, Accessible.Action action,
                                             Accessible.Argument arg) {
                asked[0] = nodeId == 1002 && action == Accessible.Action.PRESS
                        && arg == Accessible.Argument.NONE;
                return asked[0];
            }
        });
        bridge.publish(aNestedWindow(1), false);
        assertTrue(bridge.perform(1002, Accessible.Action.PRESS));
        assertTrue(asked[0], "the bridge resolves nothing itself; the scene re-checks every "
                + "precondition on its own thread (§1.9)");
    }

    /**
     * CRIT-5: a long session with nobody tracing retains nothing per event, per post and per focus
     * ask. VoiceOver asks for the focused element continuously, and the production bridge once kept
     * a String for every one of those asks, every emitted event and every post, for the life of the
     * process.
     *
     * <p>Read off the bridge's own object graph rather than off an accessor, so that a list added
     * back under any name is caught: every collection and map reachable from the bridge through
     * this package's own classes must be the size it was before the session.
     */
    @Test
    void aLongSessionWithTheTraceOffRetainsNothingPerEventPostOrAsk() throws Exception {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindowWithFocus(1002);
        bridge.publish(tree, false);
        bridge.childElementsOf(tree.find(1001));   // mint the button, so value changes are posted
        bridge.focusedElement();
        java.util.Map<String, Integer> before = retainedSizes(bridge);
        for (int i = 0; i < 100_000; i++) {
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1002));
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.VALUE_CHANGED, 1002));
            bridge.publish(tree, false);
            bridge.frameEnded();
            bridge.focusedElement();
        }
        assertEquals(before, retainedSizes(bridge),
                "100 000 frames of two events, their posts and a focus ask each grew the bridge");
    }

    /**
     * CRIT-5's other half: with nobody tracing, the two paths VoiceOver and the scene drive hardest
     * build nothing at all — not a line kept, and not a line built and dropped. The retention walk
     * above cannot see a String made and thrown away; this can.
     */
    @Test
    void withTheTraceOffAnEmitAndAFocusAskBuildNoLine() {
        org.junit.jupiter.api.Assumptions.assumeTrue(limn.testing.AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindowWithFocus(1002);
        bridge.publish(tree, false);
        bridge.childElementsOf(tree.find(1001));   // the focused button has its element
        AccessibleEvent event = AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1002);
        // Grow the queue's backing array once, so that a measured emit only stores a reference;
        // the probe's attempts stay far below the capacity, so the queue never collapses either.
        for (int i = 0; i < AxEvents.CAPACITY - 1; i++) bridge.emit(event);
        bridge.frameEnded();
        assertEquals(0, bridge.queuedEvents());

        Runnable emit = () -> bridge.emit(event);
        assertEquals(0, limn.testing.AllocationProbe.leastAllocatedBy(emit, 60),
                "an emit with no trace attached builds no line");
        assertEquals(61, bridge.queuedEvents(), "and it did enqueue, so the measurement measured it");

        // A focus ask is not free, and not because of the trace: the registry is a HashMap<Long, Long>,
        // so the lookup boxes the node id and captures its minting method reference, measured at 40
        // bytes on this host whatever the trace is. So the ask is held to exactly what that same
        // lookup costs on a registry of its own, and a line built with nobody to take it is on top.
        AxElements registry = new AxElements(Thread.currentThread(), new AxElements.Factory() {
            private long next = 0x1000;

            @Override public long newElement(long nodeId) {
                return next += 0x10;
            }

            @Override public void release(long element) {
            }
        });
        registry.elementFor(1002);
        Runnable ask = bridge::focusedElement;
        Runnable lookup = () -> registry.elementFor(1002);
        long[] typical = limn.testing.AllocationProbe.typicalAllocatedByEach(ask, lookup, 61);
        assertEquals(typical[1], typical[0],
                "a focus ask with no trace attached allocates only its registry lookup, no line");
        assertTrue(bridge.focusedElement() != 0, "and it answered an element, not the none path");

        AxBridge nobodyFocused = AxBridge.withoutThePlatform();
        nobodyFocused.publish(aNestedWindow(1), false);
        Runnable askNone = nobodyFocused::focusedElement;
        assertEquals(0, limn.testing.AllocationProbe.leastAllocatedBy(askNone, 60),
                "and the answer 'nothing is focused' builds no line either");
    }

    /**
     * Every collection's and map's size, every character sequence's length and every array's length
     * reachable from {@code root} through this package's classes. A sink of any of those shapes that
     * grows per event, post or ask changes one of these numbers.
     */
    private static java.util.Map<String, Integer> retainedSizes(Object root) throws Exception {
        java.util.Map<String, Integer> sizes = new java.util.TreeMap<>();
        collectSizes(root, root.getClass().getSimpleName(), sizes,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        assertFalse(sizes.isEmpty(), "the walk found no collection at all, so it proves nothing");
        return sizes;
    }

    private static void collectSizes(Object object, String path, java.util.Map<String, Integer> sizes,
                                     java.util.Set<Object> seen) throws Exception {
        if (object == null || !seen.add(object)) return;
        for (Class<?> type = object.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(object);
                String at = path + "." + field.getName();
                if (value instanceof java.util.Collection<?> collection) {
                    sizes.put(at, collection.size());
                } else if (value instanceof java.util.Map<?, ?> map) {
                    sizes.put(at, map.size());
                } else if (value instanceof CharSequence text) {
                    sizes.put(at, text.length());
                } else if (value != null && value.getClass().isArray()) {
                    sizes.put(at, java.lang.reflect.Array.getLength(value));
                } else if (value != null
                        && value.getClass().getPackageName().equals(AxBridge.class.getPackageName())) {
                    collectSizes(value, at, sizes, seen);
                }
            }
        }
    }

    @Test
    void theTraceSaysWhatWasEmittedPostedAndAnsweredWhileOneIsAttached() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindowWithFocus(1002);
        bridge.publish(tree, false);
        List<String> trace = traced(bridge);
        long focused = bridge.focusedElement();
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1002));
        bridge.frameEnded();
        bridge.trace(null);
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1002));
        bridge.frameEnded();
        bridge.focusedElement();
        assertEquals(List.of("focused 1002=BUTTON@" + Long.toHexString(focused),
                        "emitted FOCUS_CHANGED#1002",
                        "posted NSAccessibilityFocusedUIElementChangedNotification"), trace,
                "the live probe's log is these lines, and nothing reaches it once it is detached");
    }

    @Test
    void theTeardownRecordIsOnlyTheLastDetachs() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aNestedWindow(2);
        bridge.publish(tree, false);
        bridge.childElementsOf(tree.find(1001));
        // A collapse sweeps the registry during the session; that is not a teardown and is not kept.
        for (int i = 0; i <= AxEvents.CAPACITY; i++) {
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.VALUE_CHANGED, 1002));
        }
        bridge.publish(AccessibleTree.EMPTY, false);
        bridge.frameEnded();
        assertEquals(0, bridge.elementCount());
        assertTrue(bridge.teardown().isEmpty(), "a sweep in a live session is not a teardown");
        assertNull(bridge.lastFrameOf(1002), "and a released element's box goes with it");
        bridge.publish(tree, false);
        bridge.detach();
        bridge.detach();
        assertEquals(List.of("children taken back", "view restored", "closures freed"),
                bridge.teardown(), "each detach starts the record again, so it never grows");
    }

    @Test
    void everyNodeInTheTreeIsCountedAsLiveForTheReconciliationSweep() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        bridge.publish(aNestedWindow(2), false);
        assertEquals(4, bridge.liveNodeIds().size());
    }
}
