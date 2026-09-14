#!/usr/bin/env python3
"""Read AT-SPI2's role and state numbers off this machine's typelib.

Run this ON THE GUEST, not on a development machine, and paste what it prints into
AtspiRoles and AtspiStates. That is the whole point of it: these numbers decide what a
screen reader calls each control, a wrong one announces a slider as a menu item, and
the person who would notice is the one who cannot check it against the screen. So they
come from the same typelib libatspi and Orca read, and never from anyone's memory.

    python3 dump-atspi-constants.py            # everything Limn needs, and what it will need
    python3 dump-atspi-constants.py --all      # every enumerator the platform has

Needs python3-gi and gir1.2-atspi-2.0, which a desktop running Orca already has.
"""
import sys

try:
    import gi
    gi.require_version("Atspi", "2.0")
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
    ("ROW", "TABLE_ROW"), ("CELL", "TABLE_CELL"), ("TREE", "TREE"), ("TREE_ITEM", "TREE_ITEM"),
    ("UNKNOWN", "INVALID"),
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

# The relations the toolkit publishes, paired with the AtspiRelationType enumerator each maps
# to. Atspi.java carries these read from the enum's declaration in atspi-constants.h at the
# guest's release; this prints what the guest's own typelib says, which must agree.
RELATIONS = [
    ("LABELLED_BY", "LABELLED_BY"), ("LABEL_FOR", "LABEL_FOR"), ("DESCRIBED_BY", "DESCRIBED_BY"),
    ("CONTROLLER_FOR", "CONTROLLER_FOR"), ("CONTROLLED_BY", "CONTROLLED_BY"),
    ("MEMBER_OF", "MEMBER_OF"), ("POPUP_FOR", "POPUP_FOR"),
]

# Enumerators the toolkit does not map yet, but whose numbers a pending change needs: a closed
# branch that must not read as a leaf (EXPANDABLE, COLLAPSED), a node that is gone (DEFUNCT), a
# container whose children are not all published (MANAGES_DESCENDANTS), and a tree item's parent
# and children when a reader asks by relation (NODE_CHILD_OF, NODE_PARENT_OF). There is no toolkit
# name to pair them with, so each prints as an Atspi.java constant.
PENDING_STATES = ["EXPANDABLE", "COLLAPSED", "DEFUNCT", "MANAGES_DESCENDANTS"]
PENDING_RELATIONS = ["NODE_CHILD_OF", "NODE_PARENT_OF"]

# Whole enums a pending interface needs every value of: an announcement's politeness (Live), and
# what Text, its events and Component's ScrollTo take as arguments. Each is printed whole, because
# a client may send any of them, and an enum this typelib does not have is named, not assumed.
WHOLE_ENUMS = [
    ("Live", "LIVE"), ("TextBoundaryType", "TEXT_BOUNDARY"), ("TextGranularity", "TEXT_GRANULARITY"),
    ("TextClipType", "TEXT_CLIP"), ("CoordType", "COORD"), ("ScrollType", "SCROLL"),
]


def enumerators(enum):
    """Every (name, value) an enum has, aliases included, in value order."""
    members = getattr(enum, "__members__", None)
    if members is not None:
        pairs = [(name, int(member)) for name, member in members.items()]
    else:
        pairs = [(name, int(getattr(enum, name))) for name in dir(enum) if name.isupper()]
    return sorted(pairs, key=lambda pair: (pair[1], pair[0]))


def dump_pending(title, names, enum, enum_name, prefix):
    print("// ---- %s, read from this machine's typelib ----" % title)
    missing = []
    for name in names:
        value = getattr(enum, name, None)
        if value is None:
            missing.append(name)
            continue
        constant = "%s_%s" % (prefix, name)
        print("    static final int %s = %d;%s// Atspi.%s.%s"
              % (constant, int(value), " " * max(1, 34 - len(constant)), enum_name, name))
    for name in missing:
        print("    // %-30s NO SUCH ENUMERATOR HERE: Atspi.%s.%s — do not guess" % (name, enum_name, name))


def dump_whole(enum_name, prefix):
    enum = getattr(Atspi, enum_name, None)
    print("// ---- Atspi.%s, every enumerator, read from this machine's typelib ----" % enum_name)
    if enum is None:
        print("    // NO SUCH ENUM HERE: Atspi.%s — this typelib does not have it; do not guess" % enum_name)
        return
    for name, value in enumerators(enum):
        constant = "%s_%s" % (prefix, name)
        print("    static final int %s = %d;%s// Atspi.%s.%s"
              % (constant, value, " " * max(1, 34 - len(constant)), enum_name, name))


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


def provenance():
    """Which typelib and which libatspi answered, so a pasted number carries where it came from."""
    try:
        path = gi.Repository.get_default().get_typelib_path("Atspi")
    except Exception as exc:                                # noqa: BLE001
        path = "unknown (%s)" % exc
    try:
        version = ".".join(str(part) for part in Atspi.get_version())
    except Exception as exc:                                # noqa: BLE001
        version = "unknown (%s)" % exc
    print("# typelib %s, libatspi %s, pygobject %s" % (path, version, gi.__version__))


def main():
    print("# at-spi2 constants from %s" % sys.platform)
    provenance()
    dump("roles", ROLES, Atspi.Role, "Role")
    print()
    dump("states", STATES, Atspi.StateType, "State")
    print()
    dump("relations", RELATIONS, Atspi.RelationType, "Relation")
    print()
    dump_pending("states Limn does not map yet", PENDING_STATES, Atspi.StateType, "StateType", "STATE")
    print()
    dump_pending("relations Limn does not map yet", PENDING_RELATIONS, Atspi.RelationType,
                 "RelationType", "RELATION")
    for enum_name, prefix in WHOLE_ENUMS:
        print()
        dump_whole(enum_name, prefix)
    if "--all" in sys.argv:
        print("\n# every enumerator this machine has")
        for name in sorted(dir(Atspi.Role)):
            if name.isupper():
                print("Role.%-24s %s" % (name, int(getattr(Atspi.Role, name))))
        for name in sorted(dir(Atspi.StateType)):
            if name.isupper():
                print("State.%-23s %s" % (name, int(getattr(Atspi.StateType, name))))
        for name in sorted(dir(Atspi.RelationType)):
            if name.isupper():
                print("Relation.%-20s %s" % (name, int(getattr(Atspi.RelationType, name))))
        for enum_name, _ in WHOLE_ENUMS:
            enum = getattr(Atspi, enum_name, None)
            if enum is None:
                print("%s: NO SUCH ENUM HERE" % enum_name)
                continue
            for name, value in sorted(enumerators(enum)):
                print("%s.%-*s %s" % (enum_name, max(1, 28 - len(enum_name)), name, value))


if __name__ == "__main__":
    main()
