package limn.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The toolkit-facing half of the seam: what a bridge holds, and what a reentrant publish defers. */
class AxBridgeTest {

    /** A host that records nothing and answers nothing: this half of the bridge never calls back. */
    private static final class SilentHost implements AccessibilityBridge.Host {
        @Override public void requestRepublish() { }
        @Override public void requestRestamp() { }
        @Override public AccessibleTree republishNow() { return AccessibleTree.EMPTY; }
        @Override public boolean perform(long nodeId, Accessible.Action action, Accessible.Argument arg) {
            return false;
        }
    }

    @Test
    void aBridgeWithNoHostHoldsTheEmptyTreeRatherThanNull() {
        assertSame(AccessibleTree.EMPTY, new AxBridge().tree());
    }

    @Test
    void thisPlatformIsTheOneThatAsksForAPrimingPublish() {
        // §2.2: the listening gate cannot open before the platform has elements to ask about, and
        // it is the only platform where that is true. A false here silently costs the whole feature.
        assertTrue(new AxBridge().needsPrimingPublish());
    }

    @Test
    void attachReplacesTheHostRatherThanAccumulating() {
        AxBridge bridge = new AxBridge();
        SilentHost first = new SilentHost();
        SilentHost second = new SilentHost();
        bridge.attach(first);
        assertSame(first, bridge.host());
        bridge.attach(second);
        assertSame(second, bridge.host(),
                "a scene can be bound over a live window and the outgoing host never learns it was");
    }

    @Test
    void detachDropsTheHostAndTheTree() {
        AxBridge bridge = new AxBridge();
        bridge.attach(new SilentHost());
        bridge.publish(AccessibleTree.EMPTY, false);
        bridge.detach();
        assertNull(bridge.host());
        assertSame(AccessibleTree.EMPTY, bridge.tree());
    }

    @Test
    void aReentrantPublishStoresTheTreeAndDefersEverythingElse() {
        AxBridge bridge = new AxBridge();
        bridge.attach(new SilentHost());
        assertFalse(bridge.obligationsDeferred());
        bridge.publish(AccessibleTree.EMPTY, true);
        assertTrue(bridge.obligationsDeferred(),
                "a publish from inside an AX callback owes the next ordinary frame its registry work");
    }

    @Test
    void theNextOrdinaryPublishClearsWhatAReentrantOneDeferred() {
        AxBridge bridge = new AxBridge();
        bridge.attach(new SilentHost());
        bridge.publish(AccessibleTree.EMPTY, true);
        bridge.publish(AccessibleTree.EMPTY, false);
        assertFalse(bridge.obligationsDeferred(),
                "the scene asks for a frame whenever a reentrant publish published anything, so the "
                        + "deferred work has a frame to happen on and must not survive it");
    }

    @Test
    void detachClearsADeferredObligationRatherThanLeavingItForATreeThatIsGone() {
        AxBridge bridge = new AxBridge();
        bridge.attach(new SilentHost());
        bridge.publish(AccessibleTree.EMPTY, true);
        bridge.detach();
        assertFalse(bridge.obligationsDeferred());
    }
}
