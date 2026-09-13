#!/usr/bin/env python3
"""Reads the demo's tree (`--scene tree-reader` or `--scene tree`) through libatspi, the library
Orca reads, and prints what ADR 044 §4 owes a reader: the tree's role, each realized row's role
and the states a tree item is spoken by, and which row the keyboard is on.

The Linux sibling of scripts/a11y/windows/walk-the-tree.ps1 and the macOS axtree, and for the same
reason the table got table-check.py: what the bridge believes about itself is never the evidence,
and a role number that is wrong fails silently, in a voice the person relying on it cannot check.

usage: tree-check.py [label]      (the label is printed, so several snapshots can share one log)
"""
import sys

import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi  # noqa: E402

LABEL = sys.argv[1] if len(sys.argv) > 1 else "snapshot"
SPOKEN = ("expandable", "expanded", "collapsed", "selected", "focused", "active", "showing",
          "focusable", "multiselectable")


def states(node):
    return [s.value_nick for s in node.get_state_set().get_states() if s.value_nick in SPOKEN]


def text_of(node, depth=0):
    """The first non-empty name under a row: its cell's label, which is what a reader reads."""
    name = node.get_name() or ""
    if name or depth > 4:
        return name
    for i in range(node.get_child_count()):
        child = node.get_child_at_index(i)
        if child is not None:
            found = text_of(child, depth + 1)
            if found:
                return found
    return ""


def find(node, role_name, depth=0):
    if node.get_role_name() == role_name:
        return node
    if depth > 12:
        return None
    for i in range(node.get_child_count()):
        child = node.get_child_at_index(i)
        if child is not None:
            hit = find(child, role_name, depth + 1)
            if hit is not None:
                return hit
    return None


desktop = Atspi.get_desktop(0)
tree = None
for i in range(desktop.get_child_count()):
    app = desktop.get_child_at_index(i)
    if app is not None and "limn" in (app.get_name() or "").lower():
        tree = find(app, "tree")
        if tree is not None:
            break

print(f"=== {LABEL} ===")
if tree is None:
    print("NO NODE WITH ROLE 'tree' -- the bridge is not publishing the role, or no Limn app is up")
    sys.exit(1)

try:
    selected = Atspi.Selection.get_n_selected_children(tree)
except Exception as exc:  # noqa: BLE001
    selected = f"n/a ({type(exc).__name__})"
print(f"{tree.get_role_name()} (role {int(tree.get_role())}) states={states(tree)} "
      f"children={tree.get_child_count()} selected={selected}")
lead = None
for i in range(tree.get_child_count()):
    row = tree.get_child_at_index(i)
    if row is None:
        continue
    st = states(row)
    if "showing" not in st:
        continue
    if "active" in st or "focused" in st:
        lead = row
    print(f"  {row.get_role_name()} (role {int(row.get_role())}) '{text_of(row)}' {st}")
print("lead:", "none" if lead is None else f"'{text_of(lead)}' {states(lead)}")
