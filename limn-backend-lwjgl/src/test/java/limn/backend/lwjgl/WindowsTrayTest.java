package limn.backend.lwjgl;

import limn.backend.spi.TrayRow;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the Windows tray works out before it asks the system anything. */
class WindowsTrayTest {

    private static TrayRow row(String label, char mnemonic) {
        return new TrayRow(1, TrayRow.Kind.COMMAND, label, true, false, mnemonic, List.of());
    }

    @Test
    void anIconsNumberIsSixteenBitsNeverZeroAndTheSameEachRun() {
        for (String id : new String[] {"orders", "limn-showcase", "a", "x".repeat(64)}) {
            int number = WindowsTray.numberOf(id);
            assertTrue(number >= 1 && number <= 0xFFFF, id + " -> " + number);
            assertEquals(number, WindowsTray.numberOf(id));
        }
        assertEquals(41274, WindowsTray.numberOf("orders"), "a release that changed this would lose the user's choice");
        assertTrue(WindowsTray.numberOf("orders") != WindowsTray.numberOf("limn-showcase"));
    }

    @Test
    void theStructureIsAsTheSystemLaysItOut() {
        // NOTIFYICONDATAW, 64-bit: the tooltip's 128 units end where the state begins, and the
        // version shares its place with the timeout after the balloon's 256.
        assertEquals(WindowsTray.DATA_TIP + WindowsTray.TIP_UNITS * 2, 296);
        assertEquals(296 + 4 + 4 + 256 * 2, WindowsTray.DATA_VERSION);
        assertEquals(WindowsTray.DATA_VERSION + 4 + 64 * 2 + 4 + 16 + 8, WindowsTray.DATA_SIZE);
    }

    @Test
    void aTooltipIsCutToItsRoomAndNeverThroughAPair() {
        assertEquals("Orders\0", new String(WindowsTray.tip("Orders"), StandardCharsets.UTF_16LE));
        byte[] cut = WindowsTray.tip("x".repeat(300));
        assertEquals(WindowsTray.TIP_UNITS * 2, cut.length);
        String pair = "x".repeat(126) + "😀";
        String kept = new String(WindowsTray.tip(pair), StandardCharsets.UTF_16LE);
        assertEquals("x".repeat(126) + "\0", kept, "half a character is not shown");
    }

    @Test
    void anAmpersandInALabelIsTheTextsAndTheAccessLetterIsMarked() {
        assertEquals("Save && close", WindowsTray.label(row("Save & close", (char) 0)));
        assertEquals("&Open", WindowsTray.label(row("Open", 'O')));
        assertEquals("E&xit", WindowsTray.label(row("Exit", 'X')));
        assertEquals("Name Ctrl", WindowsTray.label(row("Name\tCtrl", (char) 0)));
    }

    @Test
    void anAcceleratorGoesInTheMenusSecondColumn() {
        TrayRow row = new TrayRow(1, TrayRow.Kind.COMMAND, "Quit", true, false, 'Q', List.of(), null, "Q",
                limn.input.Keys.MOD_CONTROL);
        assertEquals("&Quit\tCtrl+Q", WindowsTray.label(row));
        // MENUITEMINFOW, 64-bit: the item's bitmap is its last member.
        assertEquals(WindowsTray.ITEM_INFO_BITMAP + 8, WindowsTray.ITEM_INFO_SIZE);
    }

    @Test
    void onMacOsAKeyIsALowerCaseLetterOrAFunctionKeysCharacter() {
        assertEquals("q", MacTray.keyEquivalent("Q"));
        assertEquals(",", MacTray.keyEquivalent(","));
        assertEquals(String.valueOf((char) 0xF708), MacTray.keyEquivalent("F5"));
        assertEquals("", MacTray.keyEquivalent("Home"), "a key with no such form is not shown");
        assertEquals("", MacTray.keyEquivalent(""));
        assertEquals(1L << 20 | 1L << 17,
                MacTray.modifierMask(limn.input.Keys.MOD_SUPER | limn.input.Keys.MOD_SHIFT));
    }

    @Test
    void darkMenusAreDeclaredFromTheBuildThatTakesAModeUnlessTurnedOff() {
        assertFalse(WindowsDarkMenus.wanted(17763, null), "the number is another function there");
        assertFalse(WindowsDarkMenus.wanted(0, null));
        assertTrue(WindowsDarkMenus.wanted(18362, null));
        assertTrue(WindowsDarkMenus.wanted(26100, "true"));
        assertFalse(WindowsDarkMenus.wanted(26100, "false"));
    }
}
