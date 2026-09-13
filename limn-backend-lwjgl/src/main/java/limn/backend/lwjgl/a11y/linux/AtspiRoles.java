package limn.backend.lwjgl.a11y.linux;

import limn.accessibility.Accessible;

import java.util.EnumMap;
import java.util.Map;

/**
 * What each of the toolkit's roles is called on this platform, in AT-SPI2's own numbering.
 *
 * <p><b>Every number here was read off a machine and none was written from memory.</b> They are
 * {@code AtspiRole} enumerators, and the names beside them are what {@code Atspi.role_get_name}
 * answers, taken from the GObject-introspection typelib on Ubuntu 24.04, at-spi2-core 2.52.0,
 * aarch64 — the same file {@code libatspi} and therefore Orca read. The script that produced them
 * is {@code scripts/a11y/linux/dump-atspi-constants.py}; run it on the guest rather than trusting
 * this table when a platform version moves.
 *
 * <p>That rule is not ceremony. A wrong role number does not fail: it announces a slider as a menu
 * item, in a voice the person relying on it has no way to check against the screen.
 *
 * <p><b>Where two of the toolkit's roles share one of the platform's, they share it because the
 * platform has no second word</b>, and the note says so rather than inventing a distinction. A
 * search field is an entry here; a switch is a toggle button; a chart series and a radio group are
 * both panels. The facets keep them apart where it matters — a splitter carries a value and the
 * separator it shares a number with does not.
 */
final class AtspiRoles {

    private AtspiRoles() {
    }

    private static final Map<Accessible.Role, Integer> NUMBER =
            new EnumMap<>(Accessible.Role.class);
    private static final Map<Accessible.Role, String> NAME = new EnumMap<>(Accessible.Role.class);

    private static void map(Accessible.Role role, int number, String name) {
        NUMBER.put(role, number);
        NAME.put(role, name);
    }

    static {
        map(Accessible.Role.WINDOW, 23, "frame");
        map(Accessible.Role.DIALOG, 16, "dialog");
        map(Accessible.Role.ALERT, 2, "alert");
        map(Accessible.Role.GROUP, 39, "panel");
        map(Accessible.Role.SCROLL_PANE, 49, "scroll pane");
        map(Accessible.Role.SCROLL_BAR, 48, "scroll bar");
        map(Accessible.Role.SPLIT_PANE, 53, "split pane");
        // No handle role exists here; the value facet is what separates it from a Separator.
        map(Accessible.Role.SPLITTER, 50, "separator");
        map(Accessible.Role.TOOL_BAR, 63, "tool bar");
        map(Accessible.Role.MENU_BAR, 34, "menu bar");
        map(Accessible.Role.MENU, 33, "menu");
        map(Accessible.Role.MENU_ITEM, 35, "menu item");
        map(Accessible.Role.CHECK_MENU_ITEM, 8, "check menu item");
        map(Accessible.Role.RADIO_MENU_ITEM, 45, "radio menu item");
        map(Accessible.Role.SEPARATOR, 50, "separator");
        map(Accessible.Role.BUTTON, 43, "push button");
        map(Accessible.Role.TOGGLE_BUTTON, 62, "toggle button");
        map(Accessible.Role.CHECK_BOX, 7, "check box");
        // The platform has no switch; a toggle button is what GTK publishes for one too.
        map(Accessible.Role.SWITCH, 62, "toggle button");
        map(Accessible.Role.RADIO_BUTTON, 44, "radio button");
        map(Accessible.Role.RADIO_GROUP, 39, "panel");
        map(Accessible.Role.LABEL, 29, "label");
        map(Accessible.Role.HEADING, 83, "heading");
        map(Accessible.Role.IMAGE, 27, "image");
        map(Accessible.Role.VIDEO, 107, "video");
        map(Accessible.Role.CANVAS, 6, "canvas");
        map(Accessible.Role.CHART, 80, "chart");
        map(Accessible.Role.CHART_SERIES, 39, "panel");
        map(Accessible.Role.PROGRESS_BAR, 42, "progress bar");
        map(Accessible.Role.SLIDER, 51, "slider");
        map(Accessible.Role.SPIN_BUTTON, 52, "spin button");
        map(Accessible.Role.TEXT_FIELD, 79, "entry");
        map(Accessible.Role.TEXT_AREA, 61, "text");
        map(Accessible.Role.PASSWORD_FIELD, 40, "password text");
        map(Accessible.Role.SEARCH_FIELD, 79, "entry");
        map(Accessible.Role.COMBO_BOX, 11, "combo box");
        map(Accessible.Role.LIST, 31, "list");
        map(Accessible.Role.LIST_ITEM, 32, "list item");
        map(Accessible.Role.TAB_LIST, 38, "page tab list");
        map(Accessible.Role.TAB, 37, "page tab");
        // NOT page tab, which the tab itself takes: two different things under one role is a tree
        // in which a reader cannot tell the header from the page it opens. The platform has no
        // tab-panel concept at all, so the neutral container is the honest answer.
        map(Accessible.Role.TAB_PANEL, 39, "panel");
        map(Accessible.Role.COLOR_CHOOSER, 9, "color chooser");
        // ADR 041 §7; the four numbers read off the Fedora 44 guest on 2026-09-08. The row is
        // TABLE_ROW and not LIST_ITEM: Orca speaks a table row's cells with their column headers
        // only when the row says it is one.
        map(Accessible.Role.TABLE, 55, "table");
        map(Accessible.Role.COLUMN_HEADER, 57, "table column header");
        map(Accessible.Role.ROW, 90, "table row");
        map(Accessible.Role.CELL, 56, "table cell");
        // ADR 044 §4; both numbers and both names read off the Fedora 44 guest on 2026-09-13,
        // at-spi2-core 2.60.6, aarch64, the same typelib the table's four came from. TREE_ITEM and
        // not LIST_ITEM is what makes Orca speak a row's expanded state as a tree's.
        map(Accessible.Role.TREE, 65, "tree");
        map(Accessible.Role.TREE_ITEM, 91, "tree item");
        map(Accessible.Role.UNKNOWN, 0, "invalid");
    }

    /**
     * The AT-SPI2 enumerator for {@code role}.
     *
     * @param role the toolkit's role
     * @return the platform's number, or {@code null} if one is ever added without a reading
     */
    static Integer of(Accessible.Role role) {
        return NUMBER.get(role);
    }

    /**
     * The platform's own name for {@code role}, for {@code GetRoleName}.
     *
     * @param role the toolkit's role
     * @return the AT-SPI role name, or {@code "invalid"} for a role with no reading
     */
    static String nameOf(Accessible.Role role) {
        return NAME.getOrDefault(role, "invalid");
    }
}
