package limn.backend.lwjgl;

import limn.input.Keys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What needs no system of the menu bar on macOS: which accelerators the system runs, and how a key is spelled for it. */
class MacMenuBarTest {

    @Test
    void theSystemRunsAChordWithCommandOrControlAndAKeyThatTypesNothing() {
        assertTrue(MacMenuBar.systemRuns("S", Keys.MOD_SUPER));
        assertTrue(MacMenuBar.systemRuns("S", Keys.MOD_SUPER | Keys.MOD_SHIFT));
        assertTrue(MacMenuBar.systemRuns("K", Keys.MOD_CONTROL));
        assertTrue(MacMenuBar.systemRuns("F5", 0));
        assertTrue(MacMenuBar.systemRuns("Del", 0));
        assertTrue(MacMenuBar.systemRuns("Up", Keys.MOD_SHIFT));
    }

    @Test
    void theToolkitRunsAKeyThatTypesACharacterAloneOrWithOption() {
        assertFalse(MacMenuBar.systemRuns("G", 0));
        assertFalse(MacMenuBar.systemRuns("J", Keys.MOD_ALT));
        assertFalse(MacMenuBar.systemRuns("G", Keys.MOD_SHIFT));
        assertFalse(MacMenuBar.systemRuns("Space", 0));
        assertFalse(MacMenuBar.systemRuns("Enter", 0));
    }

    @Test
    void everyKeyTheToolkitNamesHasItsSpelling() {
        assertEquals("s", MacTray.keyEquivalent("S"));
        assertEquals(",", MacTray.keyEquivalent(","));
        assertEquals(String.valueOf((char) 0xF708), MacTray.keyEquivalent("F5"));
        assertEquals("\b", MacTray.keyEquivalent("Backspace"));
        assertEquals("", MacTray.keyEquivalent("Del"));
        assertEquals("\r", MacTray.keyEquivalent("Enter"));
        assertEquals("", MacTray.keyEquivalent("Up"));
        assertEquals("", MacTray.keyEquivalent("PgDn"));
        assertEquals(" ", MacTray.keyEquivalent("Space"));
        assertEquals("", MacTray.keyEquivalent("Key400"));
    }
}
