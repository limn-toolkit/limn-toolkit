package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the bridge assembles out of the pieces below it: which interfaces a node's element serves,
 * and what attaching and detaching do to the registry.
 *
 * <p>The platform calls this makes are no-ops on a machine with no UI Automation, which is where
 * these run — so what is asserted is the assembly and never the conversation with a client. That
 * conversation is the guest's to verify, and it is the one thing phase 6 still owes.
 */
class UiaBridgeTest {

    private static AccessibleTree aWindowWith(Accessible.Role role, boolean pressable) {
        return aWindowWith(role, pressable, false);
    }

    /** The same window, with the control (1001) holding the keyboard focus when asked. */
    private static AccessibleTree aWindowWith(Accessible.Role role, boolean pressable,
                                              boolean focused) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 20, 160, 40);
        a.role(role);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        if (pressable) {
            a.action(Accessible.Action.PRESS);
        }
        a.inherited(true, true, true, true, focused);
        a.end();
        a.end();
        return a.publish(focused ? 1001 : 0, 0, 0, 1f, true);
    }

    @Test
    void aMachineWithNoUiAutomationGetsNoBridgeAtAll() {
        assertSame(AccessibilityBridge.NONE, UiaBridge.openIfEnabled(0),
                "and a zero window handle is refused even where the platform is present");
        if (!Uia.isAvailable()) {
            assertSame(AccessibilityBridge.NONE, UiaBridge.openIfEnabled(0x1234),
                    "one call, and never a registry or an element");
        }
    }

    @Test
    void theContextTheProviderSlotsReadAnswersThePublishedTree() {
        // Not a hypothetical. This method used to read `published` directly; a refactor moved the
        // field into a base class and rewrote every read as a call to the accessor, which turned
        // this one into a call to itself. Nothing here noticed, because no test had ever asked the
        // bridge's own context anything -- and the first Windows run after it died in a
        // StackOverflowError inside the message pump.
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            AccessibleTree tree = aWindowWith(Accessible.Role.BUTTON, true);
            bridge.publish(tree, false);
            assertSame(tree, bridge.contextForTests().tree(),
                    "every provider slot answers from this, so it is the first thing to break");
        } finally {
            bridge.detach();
        }
    }

    @Test
    void theRootServesTheFragmentRootAndNoOtherNodeDoes() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);

            UiaObject root = bridge.objectFor(1000);
            UiaObject button = bridge.objectFor(1001);

            assertNotNull(root);
            assertNotEquals(0L,
                    root.pointerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT),
                    "a client asks the root what is under a point and what has the keyboard");
            assertEquals(0L,
                    button.pointerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT),
                    "and a second fragment root inside one window is two windows to a client");
        } finally {
            bridge.detach();
        }
    }

    @Test
    void everyNodeServesTheTwoInterfacesEveryNodeHas() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);

            for (long nodeId : new long[] {1000, 1001}) {
                UiaObject object = bridge.objectFor(nodeId);
                assertNotEquals(0L,
                        object.pointerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_SIMPLE), "" + nodeId);
                assertNotEquals(0L,
                        object.pointerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT), "" + nodeId);
            }
        } finally {
            bridge.detach();
        }
    }

    /**
     * The pattern interfaces are on the same object as everything else, so a client that queried
     * for the toggle interface and one that queried for the fragment interface are holding one
     * thing and can tell -- which is what the IUnknown identity rule is for.
     */
    @Test
    void aNodeServesOneInterfacePerPatternItVends() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);

            UiaObject button = bridge.objectFor(1001);
            assertNotEquals(0L, button.pointerFor(UiaInterfaces.INVOKE_PROVIDER),
                    "a button can be pressed, so it vends Invoke");
            assertEquals(0L, button.pointerFor(UiaInterfaces.TOGGLE_PROVIDER),
                    "and it cannot be toggled, so it does not");
        } finally {
            bridge.detach();
        }
    }

    @Test
    void aNodeWithNoVerbServesNoPatternInterface() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aWindowWith(Accessible.Role.LABEL, false), false);

            UiaObject label = bridge.objectFor(1001);
            assertEquals(0L, label.pointerFor(UiaInterfaces.INVOKE_PROVIDER));
            assertEquals(2, label.pointers().size(),
                    "the simple interface and the fragment interface, and nothing else");
        } finally {
            bridge.detach();
        }
    }

    /** A window holding one CELL (1001), carrying a selection item or not: a calendar's day. */
    private static AccessibleTree aWindowWithACell(boolean selectionItem) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 20, 40, 40);
        a.role(Accessible.Role.CELL);
        a.name(I18nString.literal("15"), Accessible.NameFrom.CONTENT);
        if (selectionItem) {
            a.selectionItem(false, 15, 30);
        }
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    /**
     * W2: a pattern the node gains after a client first asked for its element is served on the
     * same element. Before 2026-09-15 the interface list was fixed at the first ask, so a Tree
     * minted before its cursor row existed answered Invoke with a null for as long as the client
     * held it.
     */
    @Test
    void aPatternANodeGainsAfterItsElementWasMintedIsServedOnTheSameElement() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, false), false);
            UiaObject before = bridge.objectFor(1001);
            long identity = before.pointer();
            assertEquals(0L, bridge.contextForTests().patternProviderFor(1001, UiaIds.INVOKE_PATTERN),
                    "no PRESS yet, so no Invoke");

            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);

            assertNotEquals(0L,
                    bridge.contextForTests().patternProviderFor(1001, UiaIds.INVOKE_PATTERN),
                    "the node publishes PRESS now, so the element a client holds serves Invoke");
            assertSame(before, bridge.objectFor(1001), "on the same object");
            assertEquals(identity, bridge.objectFor(1001).pointer(), "under the same identity");
            assertEquals(1, bridge.elementCount(), "and nothing was minted beside it");
        } finally {
            bridge.detach();
        }
    }

    /**
     * W2's calendar shape: a day cell first seen as a chooser cell (no selection item) and seen
     * again as a day regains SelectionItem, with its runtime id, which is its node id, unchanged.
     */
    @Test
    void aCellSeenFirstWithoutASelectionItemServesOneOnceItHasIt() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aWindowWithACell(false), false);
            UiaObject cell = bridge.objectFor(1001);
            assertEquals(0L, cell.pointerFor(UiaInterfaces.SELECTION_ITEM_PROVIDER));

            bridge.publish(aWindowWithACell(true), false);

            assertNotEquals(0L, cell.pointerFor(UiaInterfaces.SELECTION_ITEM_PROVIDER),
                    "the day view's cell is selectable again through the element already held");
            assertNotEquals(0L, bridge.contextForTests().patternProviderFor(1001,
                    UiaIds.SELECTION_ITEM_PATTERN));
            assertSame(cell, bridge.objectFor(1001));
        } finally {
            bridge.detach();
        }
    }

    /**
     * W2, the set shrinking (phase 3 addendum (c)): a button that stops publishing PRESS (disabled,
     * or under an overlay) stops vending Invoke on the element a client already holds. Before, the
     * element kept answering the interface it was built with.
     */
    @Test
    void aPatternANodeLosesIsWithdrawnFromTheElementAClientHolds() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);
            UiaObject button = bridge.objectFor(1001);
            assertNotEquals(0L, button.pointerFor(UiaInterfaces.INVOKE_PROVIDER));

            bridge.publish(aWindowWith(Accessible.Role.BUTTON, false), false);

            assertEquals(0L, button.pointerFor(UiaInterfaces.INVOKE_PROVIDER),
                    "no PRESS published, so no Invoke on the held element");
            assertEquals(0L,
                    bridge.contextForTests().patternProviderFor(1001, UiaIds.INVOKE_PATTERN));
            assertSame(button, bridge.objectFor(1001), "and it is still the same element");
        } finally {
            bridge.detach();
        }
    }

    @Test
    void anElementIsMintedOnceAndFoundAgain() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);

            assertSame(bridge.objectFor(1001), bridge.objectFor(1001));
            assertEquals(1, bridge.elementCount(), "the root has not been asked for yet");
            bridge.objectFor(1000);
            assertEquals(2, bridge.elementCount());
        } finally {
            bridge.detach();
        }
    }

    @Test
    void aNodeThatIsNotInTheTreeHasNoElement() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);

            assertNull(bridge.objectFor(9999));
            assertEquals(0, bridge.elementCount(), "and nothing was minted for it");
        } finally {
            bridge.detach();
        }
    }

    /**
     * §3.4's whole-registry empty. Over a live host the elements stand for a tree about to be
     * replaced, and one that outlived its host would answer about a node from another window.
     */
    @Test
    void attachingOverALiveHostAndDetachingBothEmptyTheRegistry() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);
            bridge.objectFor(1000);
            bridge.objectFor(1001);
            assertEquals(2, bridge.elementCount());

            bridge.attach(null);
            assertEquals(0, bridge.elementCount(), "attaching over a live host empties it");

            bridge.objectFor(1001);
            assertEquals(1, bridge.elementCount());
            bridge.detach();
            assertEquals(0, bridge.elementCount(), "and so does detaching");
            assertEquals(0, bridge.tree().nodeCount(), "which also drops the snapshot");
        } finally {
            bridge.detach();
        }
    }

    @Test
    void publishingSwapsTheSnapshotWhole() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            AccessibleTree first = aWindowWith(Accessible.Role.BUTTON, true);
            AccessibleTree second = aWindowWith(Accessible.Role.CHECK_BOX, false);

            bridge.publish(first, false);
            assertSame(first, bridge.tree());
            bridge.publish(second, false);
            assertSame(second, bridge.tree(),
                    "a client reading on another thread sees one or the other and never half of "
                            + "either");
        } finally {
            bridge.detach();
        }
    }

    @Test
    void aWindowNobodyIsReadingCostsOneCall() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            assertEquals(Uia.clientsAreListening(), bridge.isListening());
            assertTrue(bridge.needsPrimingPublish(),
                    "a client that attached while this window was idle got the empty tree, and "
                            + "nothing else will prompt a walk until something moves");
            assertEquals(0, bridge.elementCount(), "and asking cost no element");
        } finally {
            bridge.detach();
        }
    }

    @Test
    void theRootProviderIsDisconnectedBeforeTheRegistryFreesIt() {
        // UiaDisconnectProvider releases the platform's references, and a release is a call
        // through the object's own vtable -- closures this bridge made. Freed first, the call
        // lands in freed trampoline memory: an access violation on every window close once a
        // client had asked for the root, which is how the benchmark and two probe runs ended on
        // the guest. The platform call is a no-op here; the order is what this pins.
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = new java.util.ArrayList<>();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);
            // What WM_GETOBJECT does: mints the root's element and remembers it for disconnect.
            // The two arguments are the message's own and are passed straight through to a
            // platform call that is a no-op here; the window procedure checked them, not this.
            bridge.answerGetObject(0, 0);
            assertEquals(1, bridge.elementCount(), "the root was asked for, so it exists");
            bridge.detach();
            String disconnect = trace.stream()
                    .filter(line -> line.startsWith("disconnected root provider"))
                    .findFirst()
                    .orElse(null);
            assertNotNull(disconnect, "the root provider was never disconnected: " + trace);
            assertTrue(disconnect.endsWith("alive=true"),
                    "disconnected after the closures it calls through were freed: " + trace);
            assertTrue(trace.stream().anyMatch(line -> line.startsWith("freed 1 objects")),
                    "the registry was never emptied: " + trace);
        } finally {
            UiaWindow.trace = before;
        }
    }

    /** Waits up to two seconds for a trace line that satisfies {@code what}. */
    private static String awaitTrace(java.util.List<String> trace,
                                     java.util.function.Predicate<String> what) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            synchronized (trace) {
                for (String line : trace) {
                    if (what.test(line)) {
                        return line;
                    }
                }
            }
            Thread.onSpinWait();
        }
        return null;
    }

    private static java.util.List<String> synchronizedTrace() {
        return java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    }

    /**
     * §13.28: a raise waits for the reader's handler, so it may not run on the thread that
     * handed the event over. The platform call is a no-op here; where it runs is what this pins.
     */
    @Test
    void anEventIsRaisedOnTheBridgesOwnThreadAndNotTheOneThatEmittedIt() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true, true), false);
            bridge.objectFor(1001);
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            String raised = awaitTrace(trace, line -> line.startsWith("raised FOCUS_CHANGED"));
            assertNotNull(raised, "the event was never raised: " + trace);
            assertTrue(raised.endsWith("on limn-a11y-uia-drain"),
                    "raised on the emitting thread, which the reader would have parked: " + raised);
            assertNotEquals(Thread.currentThread(), bridge.drainThreadForTests());
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    @Test
    void aWindowNobodyEmitsForStartsNoThread() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);
            bridge.objectFor(1001);
            assertNull(bridge.drainThreadForTests());
        } finally {
            bridge.detach();
        }
    }

    @Test
    void aDestroyedNodesElementIsReleasedOnTheDrainThread() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);
            bridge.objectFor(1001);
            assertEquals(1, bridge.elementCount());
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.NODE_DESTROYED, 1001));
            assertNotNull(awaitTrace(trace, l -> l.startsWith("released element for destroyed node 1001")),
                    "never released: " + trace);
            assertEquals(0, bridge.elementCount());
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * §1.10: a collapse is a reconciliation. The registry is swept against the published tree
     * before the client is told to re-read, so an element for a node that left in the swallowed
     * burst is released rather than leaked.
     */
    @Test
    void aCollapseSweepsElementsWhoseNodesHaveLeftAndInvalidatesTheRoot() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        // The trace consumer runs inside the drain thread's raise, so it is where a reader's
        // slowness is stood in for: each raise takes a few milliseconds here, as it does with
        // NVDA attached, and the producer runs ahead of it into the bound.
        UiaWindow.trace = line -> {
            trace.add(line);
            if (line.startsWith("raised ")) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);
            bridge.objectFor(1000);
            bridge.objectFor(1001);
            // The button leaves: a tree with only the window. Its NODE_DESTROYED is among what
            // the overflow swallows.
            bridge.publish(aWindowWithout(), false);
            // Name changes on the held root: each one a real raise, so each one slow. (A focus
            // change raised nothing here since 2026-09-15: this window has no focus to raise.)
            for (int i = 0; i <= UiaEvents.CAPACITY + 8; i++) {
                bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.NAME_CHANGED, 1000,
                        "A window", "A window " + i));
            }
            assertNotNull(awaitTrace(trace, l -> l.equals("collapse: swept 1 elements")),
                    "the registry was not swept: " + trace);
            assertNotNull(awaitTrace(trace, l -> l.startsWith("raised INVALIDATED for node 1000")),
                    "the root was not invalidated: " + trace);
            assertEquals(1, bridge.collapses());
            assertEquals(1, bridge.elementCount(), "the window's element stays; the button's went");
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * §3.4: the whole-registry empty runs after the drain thread has been stopped and joined, so
     * the two removers never race and a raise in flight is not left holding a freed element.
     */
    @Test
    void detachingStopsTheDrainBeforeItFreesAnything() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true, true), false);
            bridge.objectFor(1001);
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            assertNotNull(awaitTrace(trace, l -> l.startsWith("raised FOCUS_CHANGED")));
            Thread drain = bridge.drainThreadForTests();
            assertNotNull(drain);
            bridge.detach();
            assertFalse(drain.isAlive(), "the drain thread outlived the bridge");
            java.util.List<String> lines;
            synchronized (trace) {
                lines = new java.util.ArrayList<>(trace);
            }
            int stopped = lines.indexOf("drain stopped");
            int freed = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("freed ")) {
                    freed = i;
                }
            }
            assertTrue(stopped >= 0, "the drain was never stopped: " + lines);
            assertTrue(freed > stopped, "freed before the drain stopped: " + lines);
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            assertNull(bridge.drainThreadForTests(), "a late emit started a thread nobody stops");
        } finally {
            UiaWindow.trace = before;
        }
    }

    private static AccessibleTree aWindowWithout() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    /**
     * The spike for §13.5's second half: the root, and only the root, tells UI Automation it can
     * be told who subscribes, and what it is told is counted. Whether a reader's subscription
     * actually reaches it is the guest's to answer, not this test's.
     */
    @Test
    void theRootAloneServesAdviseEventsAndCountsWhatItIsTold() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);
            UiaObject root = bridge.objectFor(1000);
            UiaObject button = bridge.objectFor(1001);
            assertNotEquals(0, root.pointerFor(UiaInterfaces.ADVISE_EVENTS),
                    "the root is where a client's subscription lands");
            assertEquals(0, button.pointerFor(UiaInterfaces.ADVISE_EVENTS),
                    "a child is not a window and is not advised");
            assertEquals(0, bridge.advisedEvents());

            java.util.Map<String, org.lwjgl.system.CallbackI> slots =
                    UiaProvider.adviseEventsSlots(bridge.contextForTests());
            UiaCom.PIP added = (UiaCom.PIP) slots.get("AdviseEventAdded");
            UiaCom.PIP removed = (UiaCom.PIP) slots.get("AdviseEventRemoved");
            assertEquals(UiaIds.S_OK, added.invoke(0, UiaIds.AUTOMATION_FOCUS_CHANGED, 0));
            assertEquals(UiaIds.S_OK, added.invoke(0, UiaIds.AUTOMATION_FOCUS_CHANGED, 0));
            assertEquals(2, bridge.advisedEvents(), "two subscriptions stand");
            assertEquals(UiaIds.S_OK, removed.invoke(0, UiaIds.AUTOMATION_FOCUS_CHANGED, 0));
            assertEquals(1, bridge.advisedEvents(), "one was withdrawn");
        } finally {
            bridge.detach();
        }
    }

    @Test
    void aSafeArrayOfPropertyIdentifiersIsReadAndAnythingElseIsEmpty() {
        // One dimension, four-byte elements, three values: the shape a property-changed
        // subscription hands over. Laid out by hand in native memory exactly as Win32 does.
        long array = org.lwjgl.system.MemoryUtil.nmemCallocChecked(1, 32);
        long data = org.lwjgl.system.MemoryUtil.nmemCallocChecked(3, 4);
        try {
            org.lwjgl.system.MemoryUtil.memPutShort(array, (short) 1);
            org.lwjgl.system.MemoryUtil.memPutInt(array + 4, 4);
            org.lwjgl.system.MemoryUtil.memPutAddress(array + 16, data);
            org.lwjgl.system.MemoryUtil.memPutInt(array + 24, 3);
            org.lwjgl.system.MemoryUtil.memPutInt(data, UiaIds.RANGE_VALUE_VALUE);
            org.lwjgl.system.MemoryUtil.memPutInt(data + 4, UiaIds.TOGGLE_STATE);
            org.lwjgl.system.MemoryUtil.memPutInt(data + 8, UiaIds.NAME);
            assertEquals(java.util.List.of(UiaIds.RANGE_VALUE_VALUE, UiaIds.TOGGLE_STATE, UiaIds.NAME),
                    java.util.Arrays.stream(UiaProvider.int32sOf(array)).boxed().toList());
            org.lwjgl.system.MemoryUtil.memPutShort(array, (short) 2);
            assertEquals(0, UiaProvider.int32sOf(array).length, "two dimensions is not this shape");
            assertEquals(0, UiaProvider.int32sOf(0).length, "and no array is no properties");
        } finally {
            org.lwjgl.system.MemoryUtil.nmemFree(data);
            org.lwjgl.system.MemoryUtil.nmemFree(array);
        }
    }

    /**
     * The gate is per window (§13.5): the process-wide flag keeps its negative, and its positive
     * is decided by a subscription covering this window or a recent ask for its root.
     */
    @Test
    void theGateIsThisWindowsOwnTwoFactsOnceTheSessionFlagIsTrue() {
        long now = 10_000_000_000L;
        long never = Long.MIN_VALUE / 2;
        assertFalse(UiaBridge.listening(false, 3, now, now),
                "nobody in the session listens to anything, whatever this window thinks");
        assertFalse(UiaBridge.listening(true, 0, never, now),
                "the flag alone is seventeen processes and no reader");
        assertTrue(UiaBridge.listening(true, 1, never, now),
                "a subscription covering this window");
        assertTrue(UiaBridge.listening(true, 0, now - 1_000_000_000L, now),
                "asked for the root a second ago, by something that never subscribed");
        assertFalse(UiaBridge.listening(true, 0, now - 3_000_000_000L, now),
                "asked three seconds ago and never again: the poller has gone");
    }

    @Test
    void answeringGetObjectStampsTheAskOnTheBridgesClock() {
        long[] nanos = {5_000_000_000L};
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234, () -> nanos[0]);
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);
            assertTrue(bridge.lastAskedForTests() < 0, "nobody has asked yet");
            bridge.noteAsked();
            assertEquals(5_000_000_000L, bridge.lastAskedForTests(),
                    "any WM_GETOBJECT is a client entering, whatever object it named");
            nanos[0] += 500_000_000L;
            bridge.answerGetObject(0, 0);
            assertEquals(5_500_000_000L, bridge.lastAskedForTests(), "and so is the UIA root ask");
            nanos[0] -= 500_000_000L;
            nanos[0] += 1_000_000_000L;
            assertTrue(UiaBridge.listening(true, bridge.advisedEvents(),
                    bridge.lastAskedForTests(), nanos[0]), "still within the window");
            nanos[0] += 2_000_000_000L;
            assertFalse(UiaBridge.listening(true, bridge.advisedEvents(),
                    bridge.lastAskedForTests(), nanos[0]), "and now outside it");
        } finally {
            bridge.detach();
        }
    }

    /**
     * A reader that looked at the window and then waited must hear the next change however long
     * it takes: NVDA asks when a window appears and moves only on an event it can hear, so a gate
     * that closed on a timer before the first focus moved kept NVDA at the window title forever.
     */
    @Test
    void anAskIsOwedOneEventHoweverLongItTakesAndThenTheWindowCloses() {
        long[] nanos = {5_000_000_000L};
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234, () -> nanos[0]);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true, true), false);
            bridge.noteAsked();
            nanos[0] += 60_000_000_000L;
            assertTrue(UiaBridge.listening(true, 0, bridge.lastAskedForTests(), nanos[0],
                    bridge.owesAnEvent()), "a minute later and still owed");
            bridge.objectFor(1001);
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            assertNotNull(awaitTrace(trace, l -> l.startsWith("raised FOCUS_CHANGED")));
            assertFalse(bridge.owesAnEvent(), "paid");
            assertFalse(UiaBridge.listening(true, 0, bridge.lastAskedForTests(), nanos[0],
                    bridge.owesAnEvent()), "and with no subscription and no fresh ask, closed");
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * A property change on a node no client asked for pays nothing: nobody holds an element to be
     * told, and a client that asks later reads what is current (ADR 039 §13.28's cost argument).
     * Until 2026-09-15 this case was a focus change, which is no longer skipped (the next case).
     */
    @Test
    void aPropertyChangeOnANodeNobodyHoldsPaysNothing() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);
            bridge.noteAsked();
            bridge.objectFor(1000);
            bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.NAME_CHANGED, 1001,
                    "Save", "Save as"));
            // A marker behind it on the held root, so the skip is known to have been drained.
            bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.NAME_CHANGED, 1000,
                    "A window", "A window"));
            assertNotNull(awaitTrace(trace, l -> l.startsWith("raised NAME_CHANGED for node 1000")));
            assertFalse(bridge.holdsElementFor(1001), "nothing was minted for it");
            synchronized (trace) {
                assertTrue(trace.stream().noneMatch(l -> l.contains("for node 1001")),
                        "and nothing was raised on it: " + trace);
            }
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * WINDOWS-NEW-4, LAB-NEW-12: a focus change on a node no client has navigated to is raised, and
     * its element minted for it. A focus subscriber hears focus anywhere in the window, and a
     * control that has just taken the focus -- a dialog's first field, a row the cursor reached --
     * is exactly the element nobody has asked for yet. Before 2026-09-15 it was skipped.
     */
    @Test
    void aFocusChangeOnANodeNobodyHoldsIsRaisedAndMintsItsElement() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true, true), false);
            bridge.noteAsked();
            assertFalse(bridge.holdsElementFor(1001));
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            assertNotNull(awaitTrace(trace, l -> l.startsWith("raised FOCUS_CHANGED for node 1001")),
                    "the focus change was raised: " + trace);
            assertTrue(bridge.holdsElementFor(1001), "on an element minted for it");
            assertFalse(bridge.owesAnEvent(), "and it is the change a client that asked was owed");
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * Since ADR 039 §1.10's amendment of 2026-09-14 a window's activation names the window node,
     * which every client that asked holds. On this platform it is the focus change into the window
     * (§2.4), so in a window where nothing is focused it raises nothing; and nothing raised is
     * nothing paid: the event an ask is owed is cleared by a raise that reached the client, never
     * by one that fell through to silence (WINDOWS-NEW-6). Before the guard this cleared the flag
     * and traced "raised WINDOW_ACTIVATED"; until 2026-09-15's review it traced "unmapped".
     */
    @Test
    void aWindowActivationWithNothingFocusedRaisesNothingAndPaysNothing() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);
            bridge.noteAsked();
            bridge.objectFor(1000);
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.WINDOW_ACTIVATED, 1000));
            assertNotNull(awaitTrace(trace, l -> l.equals("no focus to raise for WINDOW_ACTIVATED")),
                    "the event reached the drain and found no focus to raise: " + trace);
            assertTrue(bridge.owesAnEvent(),
                    "an event this platform maps to nothing is not the one owed");
            synchronized (trace) {
                assertTrue(trace.stream().noneMatch(l -> l.startsWith("raised ")),
                        "nothing was raised, so nothing says it was: " + trace);
            }
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * A client's ask hands over the tree the bridge has and publishes nothing, requests nothing.
     * Measured with NVDA: a publish inside the ask, and even one requested for the next frame,
     * left it announcing the window and never anything in it; the tree it already had, plus the
     * gate the ask opens, read every value.
     */
    @Test
    void answeringGetObjectHandsOverWhatItHasAndTouchesTheHostNotAtAll() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        int[] requested = {0};
        int[] publishedNow = {0};
        AccessibleTree priming = aWindowWith(Accessible.Role.BUTTON, true);
        AccessibilityBridge.Host host = new AccessibilityBridge.Host() {
            @Override public void requestRepublish() { requested[0]++; }
            @Override public void requestRestamp() { }
            @Override public AccessibleTree republishNow() {
                publishedNow[0]++;
                return priming;
            }
            @Override public boolean perform(long nodeId, Accessible.Action action,
                                             Accessible.Argument arg) { return false; }
        };
        try {
            bridge.attach(host);
            bridge.publish(priming, false);
            bridge.answerGetObject(0, 0);
            assertEquals(0, requested[0], "nothing requested inside the reader's call");
            assertEquals(0, publishedNow[0], "and nothing published there either");
            assertEquals(1, bridge.elementCount(), "the root handed over is the tree it had");
        } finally {
            bridge.detach();
        }
    }

    // ---- where the user is (decision 1, semantics 4; W3, WINDOWS-NEW-2, CRIT-3)

    /**
     * A window with a focused TABLE (1001) whose ROW (1002) holds an ACTIVE CELL (1003) when
     * {@code cursor} is true, and a second row (1004) with a cell (1005) holding it otherwise.
     */
    private static AccessibleTree aFocusedTable(boolean cursorOnFirstRow) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int table = a.begin(1001, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.TABLE);
        a.table(2, 1);
        a.selection(false, false);
        a.inherited(true, true, true, true, true);
        for (int r = 0; r < 2; r++) {
            int row = a.begin(1002 + 2L * r, table, Locale.ENGLISH, 0, 20 * r, 400, 20);
            a.role(Accessible.Role.ROW);
            a.inherited(true, true, true, false, false);
            a.begin(1003 + 2L * r, row, Locale.ENGLISH, 0, 20 * r, 400, 20);
            a.role(Accessible.Role.CELL);
            a.cell(r, 0);
            a.state(Accessible.State.ACTIVE, (r == 0) == cursorOnFirstRow);
            a.inherited(true, true, true, false, false);
            a.end();
            a.end();
        }
        a.end();
        a.end();
        return a.publish(1001, 0, 0, 1f, true);
    }

    /**
     * W3: the element that has the keyboard is the cursor cell of the focused table, and the table
     * does not have it -- NVDA 2024.4.2 takes a focus change only from a sender that says so.
     */
    @Test
    void theCursorCellOfAFocusedTableHasTheKeyboardAndTheTableDoesNot() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        try {
            bridge.publish(aFocusedTable(true), false);
            UiaProvider.Context context = bridge.contextForTests();
            assertTrue(context.hasKeyboardFocus(1003));
            assertFalse(context.hasKeyboardFocus(1001), "the focused widget, whose cursor is below");
            assertFalse(context.hasKeyboardFocus(1005));
        } finally {
            bridge.detach();
        }
    }

    /**
     * W3: a cursor move inside the focused table raises the focus change on the new cell, minting
     * it, where before it raised nothing (ACTIVE_DESCENDANT_CHANGED had no mapping) and traced
     * that it had.
     */
    @Test
    void aCursorMoveRaisesTheFocusChangeOnTheNewCursorCell() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aFocusedTable(true), false);
            bridge.noteAsked();
            bridge.publish(aFocusedTable(false), false);
            bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED,
                    1001, 1003L, 1005L));
            assertNotNull(awaitTrace(trace,
                    l -> l.startsWith("raised ACTIVE_DESCENDANT_CHANGED for node 1005 in ")),
                    "raised on the cell the cursor reached: " + trace);
            assertTrue(bridge.holdsElementFor(1005), "minted for it");
            assertEquals(1005, UiaBridge.announcedFocusForTests());
            assertFalse(bridge.owesAnEvent());
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * WINDOWS-NEW-2, CRIT-3: the model's own collapse to INVALIDATED is swept like this queue's,
     * once per emit (the root-targeted INVALIDATED the sweep raises does not sweep again), and the
     * focus is re-announced after it, where before the event was dropped at node 0.
     */
    @Test
    void theModelsInvalidatedSweepsOncePerEmitAndReannouncesTheFocus() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aFocusedTable(true), false);
            bridge.objectFor(1000);
            bridge.objectFor(1005);
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true, true), false);
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.INVALIDATED, 0));
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.INVALIDATED, 0));
            assertNotNull(awaitTrace(trace, l -> l.equals("collapse: swept 1 elements")),
                    "the cell's element went with its node: " + trace);
            java.util.function.Predicate<String> reannounced = l -> l.startsWith(
                    "raised after the model's INVALIDATED for node 1001 in ");
            assertNotNull(awaitTrace(trace, l -> reannounced.test(l)
                    && trace.stream().filter(reannounced).count() == 2),
                    "the focus re-announced after each: " + trace);
            synchronized (trace) {
                assertEquals(2, trace.stream().filter(l -> l.startsWith("collapse: swept")).count(),
                        "one sweep per emit, and none from the root's own INVALIDATED: " + trace);
                assertEquals(2, trace.stream()
                        .filter(l -> l.startsWith("raised INVALIDATED for node 1000")).count(),
                        "which is raised as the root's LayoutInvalidated: " + trace);
            }
            assertTrue(bridge.holdsElementFor(1000), "the root's element stays");
            assertFalse(bridge.holdsElementFor(1005), "the gone cell's does not");
            assertTrue(bridge.holdsElementFor(1001), "and the re-announced button's was minted");
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * Decision 5: a focused field's cursor resolved into its native popup's tree. The focus change
     * is raised on the popup window's element, minted by the popup's bridge; that element says it
     * has the keyboard and the field does not; and the host root's GetFocus answers the popup's
     * fragment pointer. Two bridges, two trees, as two windows have.
     */
    /**
     * Two windows as decision 5 has them: a host whose focused field (its cursor resolved across
     * the popup relation) points at the ACTIVE day of a native popup's own tree.
     */
    private record TwoWindows(AccessibleTree hostTree, AccessibleTree popupTree, long field,
                              long popupRoot, long day) {

        static TwoWindows aFieldWithItsCursorInAPopup() {
            Accessibility popupWalk = new Accessibility();
            long popupRoot = popupWalk.mint();
            long day = popupWalk.mint();
            popupWalk.beginWalk(200, 200, Locale.ENGLISH);
            popupWalk.begin(popupRoot, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 200, 200);
            popupWalk.role(Accessible.Role.WINDOW);
            popupWalk.inherited(true, true, true, false, false);
            popupWalk.begin(day, 0, Locale.ENGLISH, 10, 10, 20, 20);
            popupWalk.role(Accessible.Role.CELL);
            popupWalk.state(Accessible.State.ACTIVE, true);
            popupWalk.inherited(true, true, true, false, false);
            popupWalk.end();
            popupWalk.end();
            AccessibleTree popupTree = popupWalk.publish(0, 0, 0, 1f, true);

            Accessibility hostWalk = new Accessibility();
            long hostRoot = hostWalk.mint();
            long field = hostWalk.mint();
            hostWalk.beginWalk(400, 300, Locale.ENGLISH);
            hostWalk.begin(hostRoot, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
            hostWalk.role(Accessible.Role.WINDOW);
            hostWalk.inherited(true, true, true, false, false);
            hostWalk.begin(field, 0, Locale.ENGLISH, 10, 10, 200, 30);
            hostWalk.role(Accessible.Role.TEXT_FIELD);
            hostWalk.inherited(true, true, true, true, true);
            hostWalk.end();
            hostWalk.end();
            hostWalk.foreignActiveDescendant(day);
            AccessibleTree hostTree = hostWalk.publish(field, 0, 0, 1f, true);
            assertEquals(day, hostTree.effectiveFocus(), "the fixture: the cursor is the popup's day");
            return new TwoWindows(hostTree, popupTree, field, popupRoot, day);
        }
    }

    @Test
    void aCursorInAnotherWindowsTreeIsRaisedAndAnsweredThroughThatWindowsProvider() {
        TwoWindows windows = TwoWindows.aFieldWithItsCursorInAPopup();
        AccessibleTree popupTree = windows.popupTree();
        AccessibleTree hostTree = windows.hostTree();
        long field = windows.field();
        long day = windows.day();

        UiaBridge host = UiaBridge.withoutTheGate(0x1234);
        UiaBridge popup = UiaBridge.withoutTheGate(0x5678);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        java.util.List<UiaObject> made = new java.util.ArrayList<>();
        long out = org.lwjgl.system.MemoryUtil.nmemAllocChecked(8);
        try {
            popup.publish(popupTree, false);
            host.publish(hostTree, false);
            host.noteAsked();

            host.emit(AccessibleEvent.property(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED,
                    field, 0L, day));
            assertNotNull(awaitTrace(trace, l -> l.startsWith("raised ACTIVE_DESCENDANT_CHANGED for node "
                    + day + " in another window in ")), "raised through the popup: " + trace);
            assertTrue(popup.holdsElementFor(day), "on the popup bridge's element, minted there");
            assertFalse(host.holdsElementFor(day), "and nothing of the popup's minted in the host");
            assertFalse(host.owesAnEvent());

            assertTrue(popup.contextForTests().hasKeyboardFocus(day),
                    "the popup's day has the keyboard, as NVDA reads it live");
            assertFalse(host.contextForTests().hasKeyboardFocus(field), "and the field does not");

            UiaObject root = UiaObject.create(java.util.List.of(new UiaObject.Served(
                    UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT,
                    UiaProvider.fragmentRootSlots(host.contextForTests()))), () -> { });
            made.add(root);
            int getFocus = 3 + UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT.slots()
                    .indexOf("GetFocus");
            assertEquals(UiaIds.S_OK, org.lwjgl.system.JNI.invokePPI(root.pointer(), out,
                    UiaCom.slotOf(root.pointer(), getFocus)));
            assertEquals(popup.objectFor(day).pointerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT),
                    org.lwjgl.system.MemoryUtil.memGetAddress(out),
                    "the host's GetFocus answers the popup window's own fragment pointer");

            // And the popup window's own root, asked directly, agrees with the HasKeyboardFocus its
            // day answers (review of windows-A: it answered a null, nothing of its tree focused).
            UiaObject popupRoot = UiaObject.create(java.util.List.of(new UiaObject.Served(
                    UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT_ROOT,
                    UiaProvider.fragmentRootSlots(popup.contextForTests()))), () -> { });
            made.add(popupRoot);
            org.lwjgl.system.MemoryUtil.memPutAddress(out, 0xAAAAL);
            assertEquals(UiaIds.S_OK, org.lwjgl.system.JNI.invokePPI(popupRoot.pointer(), out,
                    UiaCom.slotOf(popupRoot.pointer(), getFocus)));
            assertEquals(popup.objectFor(day).pointerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT),
                    org.lwjgl.system.MemoryUtil.memGetAddress(out),
                    "the popup's own GetFocus names the day that has the keyboard");
            assertEquals(0, host.contextForTests().cursorFromAnotherWindow(),
                    "and the host, whose cursor went out, is named by no other window's");
        } finally {
            org.lwjgl.system.MemoryUtil.nmemFree(out);
            UiaWindow.trace = before;
            host.detach();
            popup.detach();
            made.forEach(UiaObject::free);
        }
    }

    /**
     * §3.4, review of windows-A: the host's GetFocus reaches the popup bridge's registry from the
     * host's RPC thread, through the host's provider, which the popup's detach does not disconnect.
     * So it waits for the popup's guard, the one its whole-registry empty frees under, exactly as a
     * focus raise from the host's drain does; and once the popup has left the open set it hands
     * over nothing. Before, it minted and referenced an element with no lock at all.
     */
    @Test
    void aHandOverToAnotherWindowsGetFocusWaitsForThatWindowsEmpty() throws Exception {
        TwoWindows windows = TwoWindows.aFieldWithItsCursorInAPopup();
        UiaBridge host = UiaBridge.withoutTheGate(0x1234);
        UiaBridge popup = UiaBridge.withoutTheGate(0x5678);
        try {
            popup.publish(windows.popupTree(), false);
            host.publish(windows.hostTree(), false);
            long[] handed = {-1};
            Thread asker = new Thread(() -> handed[0] =
                    host.contextForTests().elementInAnotherWindowFor(windows.day()),
                    "a host RPC thread");
            Thread.State seen;
            synchronized (popup.vendGuardForTests()) {
                asker.start();
                long deadline = System.nanoTime() + 2_000_000_000L;
                do {
                    seen = asker.getState();
                    Thread.onSpinWait();
                } while (seen != Thread.State.BLOCKED && seen != Thread.State.TERMINATED
                        && System.nanoTime() < deadline);
                assertEquals(-1, handed[0], "nothing was handed over while the popup's empty "
                        + "could be running");
            }
            assertEquals(Thread.State.BLOCKED, seen,
                    "the hand-over waits for the guard the popup's empty frees under");
            asker.join(2_000);
            assertEquals(popup.objectFor(windows.day())
                            .pointerFor(UiaInterfaces.RAW_ELEMENT_PROVIDER_FRAGMENT), handed[0],
                    "and then hands over the popup's own fragment pointer");

            popup.detach();
            assertEquals(0, host.contextForTests().elementInAnotherWindowFor(windows.day()),
                    "a popup that has left the open set hands over nothing");
        } finally {
            host.detach();
            popup.detach();
        }
    }

    /** @return the trace lines, copied, that satisfy {@code what} */
    private static java.util.List<String> linesOf(java.util.List<String> trace,
                                                  java.util.function.Predicate<String> what) {
        synchronized (trace) {
            return trace.stream().filter(what).toList();
        }
    }

    /**
     * Semantics 4, review of windows-A: the bridge remembers the effective focus it announced.
     * A focus arriving on a table with a cursor is a FOCUS_CHANGED and an
     * ACTIVE_DESCENDANT_CHANGED on one element, and a publish past the model's budget is an
     * INVALIDATED followed by both; each raise waits for the reader (§13.28), so the element is
     * raised once per such run: the repeat is skipped, the re-announcement after the sweep is not.
     * Before, one publish raised AutomationFocusChanged on the same cell two or three times.
     */
    @Test
    void theFocusAlreadyAnnouncedIsNotRaisedAgainButIsReannouncedAfterTheModelsInvalidated() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aFocusedTable(true), false);
            bridge.objectFor(1000);
            // The focus arrives on the table, whose cursor is the first cell.
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED,
                    1001, 0L, 1003L));
            // A publish past the budget: the model's INVALIDATED, then its reserved tail.
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.INVALIDATED, 0));
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED,
                    1001, 0L, 1003L));
            bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.NAME_CHANGED, 1000, "", "end"));
            assertNotNull(awaitTrace(trace, l -> l.startsWith("raised NAME_CHANGED for node 1000")));
            assertEquals(java.util.List.of(
                            "raised FOCUS_CHANGED for node 1003",
                            "focus on node 1003 already announced, not raised again for ACTIVE_DESCENDANT_CHANGED",
                            "raised after the model's INVALIDATED for node 1003",
                            "focus on node 1003 already announced, not raised again for FOCUS_CHANGED",
                            "focus on node 1003 already announced, not raised again for ACTIVE_DESCENDANT_CHANGED"),
                    linesOf(trace, l -> l.contains("node 1003") && !l.contains("HasKeyboardFocus"))
                            .stream().map(l -> l.replaceFirst(" in \\d+ us on .*", "")).toList(),
                    "one raise on arrival and one re-announcement, every repeat skipped: " + trace);
            assertEquals(1003, UiaBridge.announcedFocusForTests());
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * §2.4's FOCUS_CHANGED row, until the review of windows-A promised and not raised: a focus move
     * is also a HasKeyboardFocus change on the element it left, when a client holds it, and on the
     * one it reached. A re-announcement of the same element moves nothing and raises neither.
     */
    @Test
    void aFocusMoveTellsTheElementItLeftAndTheOneItReachedThatTheKeyboardMoved() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aFocusedTable(true), false);
            bridge.objectFor(1000);
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.NAME_CHANGED, 1000, "", "a"));
            assertNotNull(awaitTrace(trace, l -> l.startsWith("raised NAME_CHANGED for node 1000")));
            bridge.publish(aFocusedTable(false), false);
            bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED,
                    1001, 1003L, 1005L));
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.INVALIDATED, 0));
            bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.NAME_CHANGED, 1000, "a", "b"));
            assertNotNull(awaitTrace(trace, l -> linesOf(trace,
                    x -> x.startsWith("raised NAME_CHANGED for node 1000")).size() == 2));
            assertEquals(java.util.List.of(
                            "raised HasKeyboardFocus true for node 1003",
                            "raised HasKeyboardFocus false for node 1003",
                            "raised HasKeyboardFocus true for node 1005"),
                    linesOf(trace, l -> l.startsWith("raised HasKeyboardFocus")),
                    "arrival on 1003; the move to 1005 on both; the re-announcement on neither: "
                            + trace);
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * §2.4's WINDOW_ACTIVATED row, "focus change into the window": a deactivation forgets the
     * announced focus, so the activation that follows raises the window's effective focus even
     * though nothing moved inside it; a second focus event on it is then a repeat again.
     */
    @Test
    void aWindowActivatedAgainRaisesTheFocusItHadBecauseTheDeactivationForgotIt() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true, true), false);
            bridge.objectFor(1000);
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.WINDOW_ACTIVATED, 1000));
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.WINDOW_DEACTIVATED, 1000));
            bridge.noteAsked();
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.WINDOW_ACTIVATED, 1000));
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.NAME_CHANGED, 1000, "", "end"));
            assertNotNull(awaitTrace(trace, l -> l.startsWith("raised NAME_CHANGED for node 1000")));
            assertEquals(java.util.List.of(
                            "raised FOCUS_CHANGED for node 1001",
                            "focus on node 1001 already announced, not raised again for WINDOW_ACTIVATED",
                            "WINDOW_DEACTIVATED for node 1000 raises nothing and forgets the announced focus",
                            "raised WINDOW_ACTIVATED for node 1001",
                            "focus on node 1001 already announced, not raised again for FOCUS_CHANGED"),
                    linesOf(trace, l -> (l.contains("node 1001") || l.startsWith("WINDOW_"))
                            && !l.contains("HasKeyboardFocus"))
                            .stream().map(l -> l.replaceFirst(" in \\d+ us on .*", "")).toList(),
                    "the return to the window is heard once: " + trace);
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * The memory is the process's: a focus raised in another window moves UI Automation's focus
     * there, so the next focus event back here is raised although this window announced the same
     * element last. A bridge-local memory would have silenced that return.
     */
    @Test
    void aFocusRaisedInAnotherWindowMakesTheReturnHeard() {
        UiaBridge first = UiaBridge.withoutTheGate(0x1234);
        UiaBridge second = UiaBridge.withoutTheGate(0x5678);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            first.publish(aWindowWith(Accessible.Role.BUTTON, true, true), false);
            second.publish(aFocusedTable(true), false);
            first.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            assertNotNull(awaitTrace(trace, l -> l.startsWith("raised FOCUS_CHANGED for node 1001")));
            second.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            assertNotNull(awaitTrace(trace, l -> l.startsWith("raised FOCUS_CHANGED for node 1003")));
            first.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
            assertNotNull(awaitTrace(trace, l -> linesOf(trace,
                    x -> x.startsWith("raised FOCUS_CHANGED for node 1001")).size() == 2),
                    "the return to the first window was raised: " + trace);
            assertTrue(linesOf(trace, l -> l.startsWith("focus on node")).isEmpty(), "" + trace);
        } finally {
            UiaWindow.trace = before;
            first.detach();
            second.detach();
        }
    }

    /**
     * §2.4's CARET_MOVED row and the settled unmapped-and-window-level-events item: a caret move is
     * Text_TextSelectionChanged, handled together with TEXT_SELECTION_CHANGED, so the pair the
     * model emits for one field one after the other is raised once, while a selection move alone
     * is still raised. Until the review of windows-A a caret move traced "unmapped".
     */
    @Test
    void aCaretMoveIsTheTextSelectionChangeAndItsPairIsRaisedOnce() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aWindowWith(Accessible.Role.TEXT_FIELD, false), false);
            bridge.objectFor(1000);
            bridge.objectFor(1001);
            bridge.noteAsked();
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.CARET_MOVED, 1001));
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.TEXT_SELECTION_CHANGED, 1001));
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.TEXT_SELECTION_CHANGED, 1001));
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.CARET_MOVED, 1001));
            bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.NAME_CHANGED, 1000, "", "end"));
            assertNotNull(awaitTrace(trace, l -> l.startsWith("raised NAME_CHANGED for node 1000")));
            assertEquals(java.util.List.of(
                            "raised CARET_MOVED for node 1001",
                            "TEXT_SELECTION_CHANGED for node 1001 raised with its CARET_MOVED",
                            "raised TEXT_SELECTION_CHANGED for node 1001",
                            "raised CARET_MOVED for node 1001"),
                    linesOf(trace, l -> l.contains("node 1001")).stream()
                            .map(l -> l.replaceFirst(" in \\d+ us on .*", "")).toList(),
                    "three raises for four events: " + trace);
            assertFalse(bridge.owesAnEvent());
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    /**
     * WINDOWS-NEW-6's remainder: a rectangle that moved is raised neither per node nor in bulk
     * (node 0), and both say so; before, the bulk one returned at node 0 without a trace line.
     * Nothing raised is nothing paid.
     */
    @Test
    void aBoundsChangeIsRaisedNeitherPerNodeNorInBulkAndSaysSo() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);
            bridge.objectFor(1000);
            bridge.objectFor(1001);
            bridge.noteAsked();
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.BOUNDS_CHANGED, 1001));
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.BOUNDS_CHANGED, 0));
            assertNotNull(awaitTrace(trace, l -> l.equals("unmapped BOUNDS_CHANGED for node 0")),
                    "the bulk change reached the drain and said what became of it: " + trace);
            assertEquals(java.util.List.of("unmapped BOUNDS_CHANGED for node 1001",
                    "unmapped BOUNDS_CHANGED for node 0"), linesOf(trace, l -> true));
            assertTrue(bridge.owesAnEvent(), "nothing raised, nothing paid");
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }

    // ---- selection (decisions 9, 10; semantics 1; WINDOWS-NEW-5, W1's Selection half)

    private static String raisesOf(AccessibleEvent event) {
        StringBuilder out = new StringBuilder();
        for (long[] raise : UiaBridge.selectionRaises(event)) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(raise[0]).append('@').append(raise[1]);
        }
        return out.toString();
    }

    /**
     * WINDOWS-NEW-5: a single-select container's move raises ElementSelected on the member that
     * entered, never on the container; before 2026-09-15 it raised ElementSelected on the
     * container (the event's node), once.
     */
    @Test
    void aSingleSelectMoveRaisesElementSelectedOnTheMemberThatEntered() {
        assertEquals(UiaIds.SELECTION_ITEM_ELEMENT_SELECTED + "@1003",
                raisesOf(AccessibleEvent.selection(1001, false, new long[] {1003},
                        new long[] {1002})));
        assertEquals(UiaIds.SELECTION_ITEM_ELEMENT_REMOVED_FROM_SELECTION + "@1002",
                raisesOf(AccessibleEvent.selection(1001, false, new long[0], new long[] {1002})),
                "a selection cleared: the member that left, told it left");
    }

    /** Decision 9: a multi-select container's change names every member that entered and left. */
    @Test
    void aMultiSelectChangeRaisesAddedAndRemovedOnEachMember() {
        assertEquals(UiaIds.SELECTION_ITEM_ELEMENT_ADDED_TO_SELECTION + "@1003, "
                        + UiaIds.SELECTION_ITEM_ELEMENT_ADDED_TO_SELECTION + "@1004, "
                        + UiaIds.SELECTION_ITEM_ELEMENT_REMOVED_FROM_SELECTION + "@1002",
                raisesOf(AccessibleEvent.selection(1001, true, new long[] {1003, 1004},
                        new long[] {1002})));
    }

    /**
     * Past InvalidateLimit (20, read 2026-09-15) the change is bulk: one Selection_Invalidated on the
     * container. Twenty is still per member, as the platform's own SelectorAutomationPeer decides.
     */
    @Test
    void moreThanTheInvalidateLimitIsOneSelectionInvalidatedOnTheContainer() {
        long[] twenty = new long[20];
        for (int i = 0; i < twenty.length; i++) {
            twenty[i] = 2000 + i;
        }
        assertEquals(20, UiaBridge.selectionRaises(
                AccessibleEvent.selection(1001, true, twenty, new long[0])).size());
        assertEquals(UiaIds.SELECTION_INVALIDATED + "@1001",
                raisesOf(AccessibleEvent.selection(1001, true, twenty, new long[] {1002})));
        assertEquals(UiaIds.SELECTION_INVALIDATED + "@1001",
                raisesOf(AccessibleEvent.selection(1001, false, twenty, new long[] {1002})),
                "whatever the container's multi flag");
    }

    /** A LIST (1001, multi when asked) holding two rows, 1002 selected and 1003 not. */
    private static AccessibleTree aList(boolean multi, boolean secondSelected) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int list = a.begin(1001, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.LIST);
        a.selection(multi, false);
        a.inherited(true, true, true, true, false);
        a.child(2);
        a.role(Accessible.Role.LIST_ITEM);
        a.selectionItem(true, 1, 2);
        a.endChild();
        a.child(3);
        a.role(Accessible.Role.LIST_ITEM);
        a.selectionItem(secondSelected, 2, 2);
        a.endChild();
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    /**
     * W1's Selection half: a container with a SelectionFacet serves ISelectionProvider; and a
     * SELECTION_CHANGED is raised on the member elements a client holds, the rest paying nothing.
     */
    @Test
    void aSelectionContainerServesSelectionAndItsChangeIsRaisedOnTheHeldMembers() {
        UiaBridge bridge = UiaBridge.withoutTheGate(0x1234);
        java.util.List<String> trace = synchronizedTrace();
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            AccessibleTree tree = aList(true, true);
            bridge.publish(tree, false);
            assertNotEquals(0L, bridge.objectFor(1001).pointerFor(UiaInterfaces.SELECTION_PROVIDER),
                    "a list with a selection facet serves ISelectionProvider");
            assertNotEquals(0L,
                    bridge.contextForTests().patternProviderFor(1001, UiaIds.SELECTION_PATTERN));
            long first = tree.node(2).id();
            long second = tree.node(3).id();
            bridge.objectFor(second);
            bridge.noteAsked();

            bridge.emit(AccessibleEvent.selection(1001, true, new long[] {first, second},
                    new long[0]));
            assertNotNull(awaitTrace(trace, l -> l.startsWith("raised SELECTION_CHANGED as event "
                    + UiaIds.SELECTION_ITEM_ELEMENT_ADDED_TO_SELECTION + " for node " + second
                    + " of container 1001 in ")), "raised on the held member: " + trace);
            synchronized (trace) {
                assertTrue(trace.stream().noneMatch(l -> l.contains("for node " + first + " ")),
                        "and not on the member nobody holds: " + trace);
            }
            assertFalse(bridge.holdsElementFor(first), "which was not minted for it");
            assertFalse(bridge.owesAnEvent());
        } finally {
            UiaWindow.trace = before;
            bridge.detach();
        }
    }
}
