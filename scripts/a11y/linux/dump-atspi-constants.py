#!/usr/bin/env python3
"""Read AT-SPI2's role and state numbers off this machine's typelib.

Run this ON THE GUEST, not on a development machine, and paste what it prints into
AtspiRoles and AtspiStates. That is the whole point of it: these numbers decide what a
screen reader calls each control, a wrong one announces a slider as a menu item, and
the person who would notice is the one who cannot check it against the screen. So they
come from the same typelib libatspi and Orca read, and never from anyone's memory.

    python3 dump-atspi-constants.py            # everything Limn needs
    python3 dump-atspi-constants.py --all      # every enumerator the platform has

Needs python3-gi and gir1.2-atspi-2.0, which a desktop running Orca already has.
"""
import sys

try:
    from gi.repository import Atspi
except Exception as exc:                                    # noqa: BLE001
    sys.exit("cannot import Atspi (install python3-gi and gir1.2-atspi-2.0): %s" % exc)

# The toolkit's own role names, in limn.accessibility.Accessible.Role order, each paired with
# the AtspiRole enumerator it should map to. The pairing is a proposal a human checks; the
# NUMBER is what this script exists to read.
ROLES = [
    ("WINDOW", "FRAME"), ("DIALOG", "DIALOG"), ("ALERT", "ALERT"), ("GROUP", "PANEL"),
    ("SCROLL_PANE", "SCROLL_PANE"), ("SCROLL_BAR", "SCROLL_BAR"), ("SPLIT_PANE", "SPLIT_PANE"),
    ("SPLITTER", "SEPARATOR"), ("TOOL_BAR", "TOOL_BAR"), ("MENU_BAR", "MENU_BAR"),
    ("MENU", "MENU"), ("MENU_ITEM", "MENU_ITEM"), ("CHECK_MENU_ITEM", "CHECK_MENU_ITEM"),
    ("RADIO_MENU_ITEM", "RADIO_MENU_ITEM"), ("SEPARATOR", "SEPARATOR"), ("BUTTON", "PUSH_BUTTON"),
    ("TOGGLE_BUTTON", "TOGGLE_BUTTON"), ("CHECK_BOX", "CHECK_BOX"), ("SWITCH", "TOGGLE_BUTTON"),
    ("RADIO_BUTTON", "RADIO_BUTTON"), ("RADIO_GROUP", "PANEL"), ("LABEL", "LABEL"),
    ("HEADING", "HEADING"), ("IMAGE", "IMAGE"), ("VIDEO", "VIDEO"), ("CANVAS", "CANVAS"),
    ("CHART", "CHART"), ("CHART_SERIES", "PANEL"), ("PROGRESS_BAR", "PROGRESS_BAR"),
    ("SLIDER", "SLIDER"), ("SPIN_BUTTON", "SPIN_BUTTON"), ("TEXT_FIELD", "ENTRY"),
    ("TEXT_AREA", "TEXT"), ("PASSWORD_FIELD", "PASSWORD_TEXT"), ("SEARCH_FIELD", "ENTRY"),
    ("COMBO_BOX", "COMBO_BOX"), ("LIST", "LIST"), ("LIST_ITEM", "LIST_ITEM"),
    ("TAB_LIST", "PAGE_TAB_LIST"), ("TAB", "PAGE_TAB"), ("TAB_PANEL", "PAGE_TAB"),
    ("COLOR_CHOOSER", "COLOR_CHOOSER"), ("TABLE", "TABLE"), ("COLUMN_HEADER", "TABLE_COLUMN_HEADER"),
    ("ROW", "TABLE_ROW"), ("CELL", "TABLE_CELL"), ("UNKNOWN", "INVALID"),
]

STATES = [
    ("ENABLED", "ENABLED"), ("FOCUSABLE", "FOCUSABLE"), ("FOCUSED", "FOCUSED"),
    ("VISIBLE", "VISIBLE"), ("SHOWING", "SHOWING"), ("SELECTABLE", "SELECTABLE"),
    ("SELECTED", "SELECTED"), ("CHECKED", "CHECKED"), ("MIXED", "INDETERMINATE"),
    ("PRESSED", "PRESSED"), ("EXPANDED", "EXPANDED"), ("HAS_POPUP", "HAS_POPUP"),
    ("READ_ONLY", "READ_ONLY"), ("EDITABLE", "EDITABLE"), ("MULTI_LINE", "MULTI_LINE"),
    ("PASSWORD", "INVALID_ENTRY"), ("INVALID", "INVALID_ENTRY"), ("REQUIRED", "REQUIRED"),
    ("BUSY", "BUSY"), ("MODAL", "MODAL"), ("ACTIVE", "ACTIVE"), ("DEFAULT", "IS_DEFAULT"),
    ("HORIZONTAL", "HORIZONTAL"), ("VERTICAL", "VERTICAL"),
]


def dump(title, pairs, enum, kind):
    print("// ---- %s, read from this machine's typelib ----" % title)
    missing = []
    for ours, theirs in pairs:
        value = getattr(enum, theirs, None)
        if value is None:
            missing.append((ours, theirs))
            continue
        print("        KNOWN.put(Accessible.%s.%s, %d);%s// Atspi.%s.%s"
              % (kind, ours, int(value), " " * max(1, 34 - len(ours)), kind.title(), theirs))
    for ours, theirs in missing:
        print("        // %-16s NO SUCH ENUMERATOR HERE: Atspi.%s.%s — pick another, do not guess"
              % (ours, kind.title(), theirs))


def main():
    print("# at-spi2 constants from %s" % sys.platform)
    dump("roles", ROLES, Atspi.Role, "Role")
    print()
    dump("states", STATES, Atspi.StateType, "State")
    if "--all" in sys.argv:
        print("\n# every enumerator this machine has")
        for name in sorted(dir(Atspi.Role)):
            if name.isupper():
                print("Role.%-24s %s" % (name, int(getattr(Atspi.Role, name))))
        for name in sorted(dir(Atspi.StateType)):
            if name.isupper():
                print("State.%-23s %s" % (name, int(getattr(Atspi.StateType, name))))


if __name__ == "__main__":
    main()
