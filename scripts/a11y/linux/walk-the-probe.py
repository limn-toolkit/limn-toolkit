#!/usr/bin/env python3
"""Walks a Limn application's AT-SPI tree from outside the process, and prints what it finds.

This is the Linux sibling of scripts/a11y/windows/walk-the-probe.ps1 and the macOS axtree: a client
that reads what the bridge publishes, rather than the bridge reporting on itself. Both defects a
live reader has ever found on this platform were things the provider believed and the desktop did
not, so what the provider prints about itself is never the evidence.

It uses libatspi's own typelib, which is what Orca uses, so a tree this walks is a tree Orca sees.

usage: walk-the-probe.py [application-name-substring] [max-depth]
"""
import sys

import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi  # noqa: E402

WANTED = sys.argv[1] if len(sys.argv) > 1 else "Limn"
MAX_DEPTH = int(sys.argv[2]) if len(sys.argv) > 2 else 8


def describe(node, depth):
    role = node.get_role_name()
    name = node.get_name() or ""
    # The states are the half a tree walk alone cannot show is wrong: the Linux run's first defect
    # was a window that never published ACTIVE, over a tree that was otherwise perfect.
    states = sorted(s.value_nick for s in node.get_state_set().get_states())
    interesting = [s for s in states
                   if s in ("active", "focused", "focusable", "checked", "enabled", "showing",
                            "visible", "selected", "expanded", "modal")]
    line = f"{'  ' * depth}{role} '{name}' [{' '.join(interesting)}]"
    try:
        actions = Atspi.Action.get_n_actions(node)
        if actions:
            line += " actions=[" + ",".join(
                Atspi.Action.get_action_name(node, i) for i in range(actions)) + "]"
    except Exception:
        pass
    try:
        extents = node.get_extents(Atspi.CoordType.SCREEN)
        if extents.width or extents.height:
            line += f" rect({extents.x},{extents.y},{extents.width},{extents.height})"
    except Exception:
        pass
    print(line)


def walk(node, depth=0):
    describe(node, depth)
    if depth >= MAX_DEPTH:
        return
    for i in range(node.get_child_count()):
        child = node.get_child_at_index(i)
        if child is not None:
            walk(child, depth + 1)


desktop = Atspi.get_desktop(0)
found = 0
for i in range(desktop.get_child_count()):
    app = desktop.get_child_at_index(i)
    if app is None:
        continue
    if WANTED.lower() in (app.get_name() or "").lower():
        found += 1
        walk(app)
print(f"applications matching {WANTED!r}: {found}")
sys.exit(0 if found else 1)
