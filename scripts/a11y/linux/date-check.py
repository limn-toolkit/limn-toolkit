#!/usr/bin/env python3
"""Asks a running Limn application's date widgets what libatspi asks them: the calendar grid by row and
column, a date field's segments through Value and Text, and a picker's expand state and its popup.

The Linux sibling of tree-check.py and table-check.py for CalendarView, DateField and DatePicker (H3;
ADR 042 §8 says what a reader is meant to find). Run it ON THE MACHINE, in the graphical session's bus
environment, with the application up — for instance the demo's accessibility gallery opened on one
entry: `java -cp limn-demo-all.jar limn.demo.a11y.AccessibilityGallery Calendar grid` (or
`Date field, segmented`, `Date picker, closed`, `Date picker, open`). It starts nothing and, unless asked,
changes nothing.

    date-check.py APPLICATION_NAME [--increment] [--open]

APPLICATION_NAME  the application's name on the desktop, matched exactly (the gallery's is
                  "Limn accessibility gallery")
--increment       perform the first spin button's "increment" action and read its value back
--open            perform the first "expand" action a field offers, wait, and walk every frame of the
                  application again, so a native popup's calendar is read where it lives

For every grid (a table whose rows hold cells): its shape, its Selection (count and members), each
column header, then every cell by row and column with its name, the states a reader speaks, its
TableCell position and its posinset/setsize. For every spin button: Value (current, minimum, maximum,
increment, text), Text (the string and the caret), its actions and states. For every group of spin
buttons: its name, description, states and relations. For every node that can expand: its name, role,
expand states and actions, and its popup relations. Exits 1 when the application is not found and 2
when it shows no date widget at all.
"""
import sys
import time
import warnings

import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi, GLib  # noqa: E402

# Action.get_action_name is deprecated in libatspi 2.60 and still the one name every version has.
warnings.filterwarnings("ignore", category=DeprecationWarning)

SPOKEN_STATES = ["focused", "active", "selected", "sensitive", "enabled", "expandable", "expanded",
                 "collapsed", "has-popup", "invalid-entry", "read-only", "editable", "showing"]


def states_of(node):
    try:
        present = {Atspi.StateType(s).value_nick for s in node.get_state_set().get_states()}
    except GLib.GError as e:
        return "(%s)" % e.message
    return [s for s in SPOKEN_STATES if s in present]


def safe(call, default=None):
    try:
        return call()
    except GLib.GError as e:
        return "(error: %s)" % e.message
    except Exception as e:  # a binding that does not accept the reply
        return default if default is not None else "(%s: %s)" % (type(e).__name__, e)


def actions_of(node):
    if "Action" not in (safe(node.get_interfaces, []) or []):
        return []
    return [Atspi.Action.get_action_name(node, i) for i in range(Atspi.Action.get_n_actions(node))]


def attributes_of(node):
    attributes = safe(node.get_attributes, {}) or {}
    if not isinstance(attributes, dict):
        return attributes
    return {k: v for k, v in attributes.items() if k in ("level", "posinset", "setsize", "sort")}


def relations_of(node):
    out = []
    for relation in safe(node.get_relation_set, []) or []:
        kind = relation.get_relation_type().value_nick
        for i in range(relation.get_n_targets()):
            target = relation.get_target(i)
            out.append("%s -> %s %r" % (kind, safe(target.get_role_name), safe(target.get_name)))
    return out


def find_application(name, seconds=20):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        desktop = Atspi.get_desktop(0)
        for i in range(desktop.get_child_count()):
            app = desktop.get_child_at_index(i)
            if app is not None and safe(app.get_name) == name and app.get_child_count() > 0:
                return app
        time.sleep(0.25)
    return None


def walk(node, depth=0, limit=30):
    yield node, depth
    if depth >= limit:
        return
    for i in range(safe(node.get_child_count, 0) or 0):
        kid = node.get_child_at_index(i)
        if kid is not None:
            yield from walk(kid, depth + 1, limit)


def is_grid(node):
    if node.get_role() != Atspi.Role.TABLE:
        return False
    for i in range(node.get_child_count()):
        row = node.get_child_at_index(i)
        if row is not None and row.get_role() == Atspi.Role.TABLE_ROW:
            return True
    return False


def report_grid(table, where):
    print("== grid %r in %s" % (safe(table.get_name), where))
    print("   interfaces:", safe(table.get_interfaces))
    rows = safe(lambda: Atspi.Table.get_n_rows(table), -1)
    columns = safe(lambda: Atspi.Table.get_n_columns(table), -1)
    print("   NRows/NColumns:", rows, columns)
    if "Selection" in (safe(table.get_interfaces, []) or []):
        count = safe(lambda: Atspi.Selection.get_n_selected_children(table), -1)
        members = []
        for i in range(count if isinstance(count, int) and count > 0 else 0):
            child = safe(lambda: Atspi.Selection.get_selected_child(table, i))
            members.append(safe(child.get_name) if child is not None else None)
        print("   Selection: n_selected_children=%s selected=%s" % (count, members))
    else:
        print("   Selection: not served")
    if isinstance(columns, int):
        headers = []
        for c in range(max(0, columns)):
            header = safe(lambda: Atspi.Table.get_column_header(table, c))
            headers.append(safe(header.get_name) if header is not None else None)
        print("   column headers:", headers)
    if isinstance(rows, int) and isinstance(columns, int):
        for r in range(max(0, rows)):
            for c in range(max(0, columns)):
                cell = safe(lambda: Atspi.Table.get_accessible_at(table, r, c))
                if cell is None or isinstance(cell, str):
                    print("   (%d,%d) %s" % (r, c, cell))
                    continue
                position = safe(lambda: Atspi.TableCell.get_position(cell))
                print("   (%d,%d) %r desc=%r states=%s position=%s attributes=%s actions=%s" % (
                    r, c, safe(cell.get_name), safe(cell.get_description), states_of(cell),
                    position, attributes_of(cell), actions_of(cell)))


def report_spin(spin):
    interfaces = safe(spin.get_interfaces, []) or []
    value = "(Value not served)"
    if "Value" in interfaces:
        value = "current=%s min=%s max=%s increment=%s text=%r" % (
            safe(lambda: Atspi.Value.get_current_value(spin)),
            safe(lambda: Atspi.Value.get_minimum_value(spin)),
            safe(lambda: Atspi.Value.get_maximum_value(spin)),
            safe(lambda: Atspi.Value.get_minimum_increment(spin)),
            safe(lambda: Atspi.Value.get_text(spin)))
    text = "(Text not served)"
    if "Text" in interfaces:
        text = "text=%r caret=%s count=%s" % (
            safe(lambda: Atspi.Text.get_text(spin, 0, -1)),
            safe(lambda: Atspi.Text.get_caret_offset(spin)),
            safe(lambda: Atspi.Text.get_character_count(spin)))
    print("   spin button %r states=%s actions=%s attributes=%s" % (
        safe(spin.get_name), states_of(spin), actions_of(spin), attributes_of(spin)))
    print("     Value: %s" % value)
    print("     Text:  %s" % text)


def report(app, label):
    print("######## %s: application %r, %d frame(s)" % (label, safe(app.get_name), app.get_child_count()))
    found = 0
    first_spin = None
    first_expand = None
    for f in range(app.get_child_count()):
        frame = app.get_child_at_index(f)
        if frame is None:
            continue
        where = "frame %d %r" % (f, safe(frame.get_name))
        print("---- %s states=%s relations=%s" % (where, states_of(frame), relations_of(frame)))
        for node, _depth in walk(frame):
            role = node.get_role()
            if is_grid(node):
                found += 1
                report_grid(node, where)
            elif role == Atspi.Role.PANEL or role == Atspi.Role.GROUPING:
                spins = [node.get_child_at_index(i) for i in range(node.get_child_count())]
                spins = [s for s in spins if s is not None and s.get_role() == Atspi.Role.SPIN_BUTTON]
                if spins:
                    found += 1
                    print("== field %r (%s) desc=%r states=%s relations=%s actions=%s in %s" % (
                        safe(node.get_name), safe(node.get_role_name), safe(node.get_description),
                        states_of(node), relations_of(node), actions_of(node), where))
                    for spin in spins:
                        report_spin(spin)
                        first_spin = first_spin or spin
            present = states_of(node)
            if isinstance(present, list) and "expandable" in present and not is_grid(node):
                print("== expandable %s %r states=%s actions=%s relations=%s in %s" % (
                    safe(node.get_role_name), safe(node.get_name), present, actions_of(node),
                    relations_of(node), where))
                if first_expand is None and "expand" in actions_of(node):
                    first_expand = node
    return found, first_spin, first_expand


def perform(node, name):
    actions = actions_of(node)
    if name not in actions:
        print("   no %r action on %r (actions %s)" % (name, safe(node.get_name), actions))
        return False
    done = safe(lambda: Atspi.Action.do_action(node, actions.index(name)))
    print("   DoAction(%r) on %r answered %s" % (name, safe(node.get_name), done))
    return done is True


def main(argv):
    if len(argv) < 2:
        print(__doc__.strip().splitlines()[0], file=sys.stderr)
        print("usage: date-check.py APPLICATION_NAME [--increment] [--open]", file=sys.stderr)
        return 1
    name = argv[1]
    app = find_application(name)
    if app is None:
        print("no application named %r with a frame on the desktop" % name)
        return 1
    found, first_spin, first_expand = report(app, "as found")
    if "--increment" in argv and first_spin is not None:
        print("######## --increment")
        if perform(first_spin, "increment"):
            time.sleep(0.5)
            Atspi.Accessible.clear_cache(first_spin)
            report_spin(first_spin)
    if "--open" in argv and first_expand is not None:
        print("######## --open")
        if perform(first_expand, "expand"):
            time.sleep(1.5)
            Atspi.Accessible.clear_cache(app)
            found_open, _, _ = report(app, "after expand")
            found = max(found, found_open)
    if found == 0:
        print("no calendar grid and no field of spin buttons in %r" % name)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
