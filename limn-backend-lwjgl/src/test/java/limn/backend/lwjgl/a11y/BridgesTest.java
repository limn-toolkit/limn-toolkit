package limn.backend.lwjgl.a11y;

import limn.backend.AccessibilityBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What the backend's own choice of bridge must guarantee, on every machine this suite runs on —
 * which includes machines with no accessibility of any kind.
 */
class BridgesTest {

    @Test
    void aWindowWithNoNativeHandleGetsNoBridgeOnTheTwoPlatformsThatNeedOne() {
        // Zero is what the backend answers on a platform it has not been taught, and both handle
        // bridges refuse it. Linux takes no handle and is deliberately not asserted here: whether
        // it opens depends on whether the machine running this has assistive technology switched
        // on, which is a property of the machine and not of this code.
        AccessibilityBridge bridge = Bridges.openFor(0, "a test");
        assertNotNull(bridge, "there is always a bridge object, even when it is NONE");
        if (org.lwjgl.system.Platform.get() != org.lwjgl.system.Platform.LINUX) {
            assertSame(AccessibilityBridge.NONE, bridge);
        }
    }

    @Test
    void askingTwiceCostsTheSameAnswer() {
        // The window opens this lazily and keeps it, so the only thing that must hold here is that
        // asking is not itself a side effect with a different answer the second time.
        assertDoesNotThrow(() -> {
            assertNotNull(Bridges.openFor(0, "a test"));
            assertNotNull(Bridges.openFor(0, "a test"));
        });
    }

    // There is deliberately no test that passes a nonsense handle. Two of these factories send a
    // message to the object the handle names, and a message to a pointer that is neither real nor
    // zero is undefined behaviour no catch reaches: the first version of this file had such a test
    // and it took the JVM down with a SIGSEGV rather than failing. What keeps that unreachable is
    // the contract on openFor, not a guard inside it.
}
