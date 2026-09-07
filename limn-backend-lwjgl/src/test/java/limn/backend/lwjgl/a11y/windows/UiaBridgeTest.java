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
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
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
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);
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
            for (int i = 0; i <= UiaEvents.CAPACITY + 8; i++) {
                bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1000));
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
            bridge.publish(aWindowWith(Accessible.Role.BUTTON, true), false);
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
}
