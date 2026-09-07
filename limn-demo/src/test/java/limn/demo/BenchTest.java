package limn.demo;

import limn.backend.AccessibilityBridge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The benchmark's third accessibility configuration, checked where there is no window: the
 * platform's bridge is opened for what the open installs, and the scene sees none of it.
 */
class BenchTest {

    private static final AccessibilityBridge OPENED = new AccessibilityBridge() {
    };

    @Test
    void subclassOnlyOpensThePlatformBridgeAndHandsTheWindowNone() {
        int[] opens = {0};
        List<AccessibilityBridge> installed = new ArrayList<>();
        assertTrue(Bench.applyAccessibilityMode(Bench.SUBCLASS_ONLY, () -> {
            opens[0]++;
            return OPENED;
        }, installed::add));
        assertEquals(1, opens[0], "opened once, for the subclass the open installs");
        assertEquals(1, installed.size());
        assertSame(AccessibilityBridge.NONE, installed.get(0),
                "the scene must see NONE, or the listening gate buys a walk per damaged frame"
                        + " and the number is the walk's and not the subclass's");
    }

    @Test
    void anyOtherValueLeavesTheWindowAsTheBackendWiredIt() {
        for (String mode : new String[] {null, "", "off", "on", "subclass", "SUBCLASS-ONLY"}) {
            int[] opens = {0};
            List<AccessibilityBridge> installed = new ArrayList<>();
            assertFalse(Bench.applyAccessibilityMode(mode, () -> {
                opens[0]++;
                return OPENED;
            }, installed::add), "'" + mode + "' applied");
            assertEquals(0, opens[0], "'" + mode + "' opened the bridge early");
            assertTrue(installed.isEmpty(), "'" + mode + "' installed something");
        }
    }
}
