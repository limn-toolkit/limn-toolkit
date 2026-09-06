package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
