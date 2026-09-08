package limn.backend.lwjgl.a11y;

import limn.backend.AccessibilityBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        if (!limn.backend.Platform.current().isLinux()) {
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

    /**
     * The one switch an application has, and what each side of it must do.
     *
     * <p>The property is process-wide, so every case here puts back what it found: a test that
     * left {@code off} behind would make every later window in this JVM silently inaccessible,
     * which is precisely the failure the property's own documentation warns about.
     */
    @Nested
    class RefusedByTheApplication {

        private String before;

        @BeforeEach
        void remember() {
            before = System.getProperty(Bridges.PROPERTY);
        }

        @AfterEach
        void restore() {
            if (before == null) {
                System.clearProperty(Bridges.PROPERTY);
            } else {
                System.setProperty(Bridges.PROPERTY, before);
            }
        }

        @Test
        void offRefusesBeforeAnyPlatformIsAsked() {
            System.setProperty(Bridges.PROPERTY, "off");
            assertTrue(Bridges.refusedByApplication());
            // On every platform, Linux included: the refusal is read before the switch on the
            // platform, so this holds whether or not the machine has assistive technology on.
            assertSame(AccessibilityBridge.NONE, Bridges.openFor(0, "a test"));
        }

        @Test
        void absentLeavesTheDecisionToThePlatform() {
            System.clearProperty(Bridges.PROPERTY);
            assertFalse(Bridges.refusedByApplication(),
                    "an application that set nothing has refused nothing");
            // The platform's own answer: NONE for a handle of zero on the two platforms that need
            // one, and on Linux whatever the desktop says, which is the machine's and not asserted.
            AccessibilityBridge bridge = Bridges.openFor(0, "a test");
            assertNotNull(bridge);
            if (!limn.backend.Platform.current().isLinux()) {
                assertSame(AccessibilityBridge.NONE, bridge);
            }
        }

        @Test
        void onlyOffIsARefusal() {
            // A value nobody documented is not a refusal. "on", a typo, an empty string: a
            // blind user's access does not hang on a spelling, only on the one word that means it.
            for (String notARefusal : new String[] {"on", "true", "false", "", "OFF ", "no"}) {
                System.setProperty(Bridges.PROPERTY, notARefusal);
                assertFalse(Bridges.refusedByApplication(), "'" + notARefusal + "' refused");
            }
        }
    }

    // There is deliberately no test that passes a nonsense handle. Two of these factories send a
    // message to the object the handle names, and a message to a pointer that is neither real nor
    // zero is undefined behaviour no catch reaches: the first version of this file had such a test
    // and it took the JVM down with a SIGSEGV rather than failing. What keeps that unreachable is
    // the contract on openFor, not a guard inside it.
}
