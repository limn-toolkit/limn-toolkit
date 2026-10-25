package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.internal.AccessibilityAccess;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A menu's separator answers as AppKit's own: an {@code AXMenuItem} with no title that is not
 * enabled, which VoiceOver leaves out of "menu, 4 itens" (read off an {@code NSMenu} on the guest,
 * 2026-09-30). It answered {@code AXGroup}, and VoiceOver counted it among the items. A separator
 * between controls stays a divider.
 */
@ExtendWith(PlatformFreeBridges.class)
class AxMenuSeparatorTest {

    /** WINDOW 1000 > [MENU 1001 > MENU_ITEM 1002, SEPARATOR 1003, MENU_ITEM 1004]; SEPARATOR 1005. */
    private static AccessibleTree tree() {
        Accessibility a = new Accessibility();
        AccessibilityAccess.beginWalk(a, 400, 300, Locale.ENGLISH);
        AccessibilityAccess.begin(a, 1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("w"), Accessible.NameFrom.EXPLICIT);
        AccessibilityAccess.inherited(a, true, true, true, false, false);
        int menu = open(a, 1001, 0, Accessible.Role.MENU);
        open(a, 1002, menu, Accessible.Role.MENU_ITEM);
        a.end();
        open(a, 1003, menu, Accessible.Role.SEPARATOR);
        a.end();
        open(a, 1004, menu, Accessible.Role.MENU_ITEM);
        a.end();
        a.end();
        open(a, 1005, 0, Accessible.Role.SEPARATOR);
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    private static int open(Accessibility a, long id, int parent, Accessible.Role role) {
        int index = AccessibilityAccess.begin(a, id, parent, Locale.ENGLISH, 0, 0, 10, 10);
        a.role(role);
        if (role != Accessible.Role.SEPARATOR) {
            a.name(I18nString.literal(role + " " + id), Accessible.NameFrom.CONTENT);
        }
        AccessibilityAccess.inherited(a, true, true, true, false, false);
        return index;
    }

    @Test
    void aSeparatorInAMenuIsAMenuItemAndADividerElsewhereIsNot() {
        AxBridge bridge = PlatformFreeBridges.make();
        AccessibleTree tree = tree();
        bridge.publish(tree, false);
        AccessibleNode inMenu = tree.find(1003);
        AccessibleNode between = tree.find(1005);
        assertEquals(Accessible.Role.MENU, bridge.parentRoleOf(inMenu));
        assertTrue(AxRoles.isMenuSeparator(inMenu.role(), bridge.parentRoleOf(inMenu)));
        assertFalse(AxRoles.isMenuSeparator(between.role(), bridge.parentRoleOf(between)),
                "a rule between controls keeps the divider's AXGroup");
        assertFalse(AxRoles.isMenuSeparator(Accessible.Role.MENU_ITEM, Accessible.Role.MENU));
        assertNull(bridge.parentRoleOf(tree.root()), "the root has no parent");
        assertEquals("NSAccessibilityGroupRole", AxRoles.of(Accessible.Role.SEPARATOR).roleSymbol());
    }
}
