package limn.a11y.windows;

import limn.backend.AccessibilityBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the binding to {@code uiautomationcore} degrades to silence rather than to a throw.
 *
 * <p>This runs on every machine this repository is built on, and almost none of them are Windows —
 * which is the case worth asserting. A binding that threw when the library was absent would make
 * this module unusable in a cross-platform application's own test suite, and a binding that
 * resolved half its entry points would defer the failure to a thread belonging to a screen reader.
 */
class UiaBindingTest {

    @Test
    void aMachineWithNoUiAutomationAnswersNoRatherThanThrowing() {
        // Not "false": on the Windows guest this is true, and the assertion that matters is the
        // implication below, which holds on both.
        boolean available = Uia.isAvailable();

        assertFalse(available && !Uia.isAvailable(),
                "availability is resolved once and does not move under the caller");
        assertTrue(available || !Uia.clientsAreListening(),
                "with no library there is nothing listening, and asking must not throw: a caller "
                        + "on a machine that has no UI Automation is the ordinary case, not an "
                        + "error");
    }

    @Test
    void theGateCanBeAskedRepeatedlyBecauseItIsAskedEveryFrame() {
        for (int i = 0; i < 1000; i++) {
            Uia.clientsAreListening();
        }
    }

    /**
     * The provider does not exist yet, so nothing may claim it does. When it lands, this case is
     * the one that has to change, and changing it is the milestone.
     */
    @Test
    void openIfEnabledAnswersNothingWhileThereIsNoProvider() {
        assertSame(AccessibilityBridge.NONE, UiaBridge.openIfEnabled(0),
                "a bridge that reported itself listening would make the scene walk and diff its "
                        + "whole tree every frame for a client with no way to ask for any of it");
        assertSame(AccessibilityBridge.NONE, UiaBridge.openIfEnabled(0x1234L));
    }
}
