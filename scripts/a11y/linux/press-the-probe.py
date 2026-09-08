#!/usr/bin/env python3
"""Presses a button of a Limn application through AT-SPI, from outside the process, and prints
what the bridge answered.

This is the client half of a DoAction: walk-the-probe.py shows what a reader is TOLD, and this shows
what a reader can DO. The two are different evidence. A tree can be perfect while every action on it
answers false -- which is what happened once, when the bridge handed its object handler a host field
of its own that nothing ever wrote -- and no walk, and nothing the provider prints about itself,
could have seen it. The probe prints "Save pressed, through the toolkit's own path" when the press
reaches the widget, so the reply here and that line in the probe's output are the whole of the
check.

It uses libatspi's own typelib, which is what Orca uses, so a press this makes is a press Orca
could make.

usage: press-the-probe.py [application-name-substring] [button-name] [times]
"""
import sys
import time

import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi  # noqa: E402

WANTED = sys.argv[1] if len(sys.argv) > 1 else "Limn"
BUTTON = sys.argv[2] if len(sys.argv) > 2 else "Save"
TIMES = int(sys.argv[3]) if len(sys.argv) > 3 else 3
MAX_DEPTH = 8


def find(node, name, depth=0):
    if (node.get_name() or "") == name and "button" in node.get_role_name():
        return node
    if depth >= MAX_DEPTH:
        return None
    for i in range(node.get_child_count()):
        child = node.get_child_at_index(i)
        if child is not None:
            found = find(child, name, depth + 1)
            if found is not None:
                return found
    return None


desktop = Atspi.get_desktop(0)
app = None
# The probe joins the bus on its first publish, a few seconds after the window opens.
for _ in range(30):
    for i in range(desktop.get_child_count()):
        candidate = desktop.get_child_at_index(i)
        if candidate is not None and WANTED.lower() in (candidate.get_name() or "").lower():
            app = candidate
            break
    if app is not None:
        break
    time.sleep(1)
if app is None:
    print(f"no application matching {WANTED!r} on the accessibility bus")
    sys.exit(2)

button = find(app, BUTTON)
if button is None:
    print(f"no button named {BUTTON!r} under {app.get_name()!r}")
    sys.exit(3)

count = Atspi.Action.get_n_actions(button)
verbs = [Atspi.Action.get_action_name(button, i) for i in range(count)]
print(f"{button.get_role_name()} {BUTTON!r} actions={verbs}")
pressed = 0
for _ in range(TIMES):
    done = Atspi.Action.do_action(button, 0)
    print(f"DoAction({verbs[0] if verbs else 0}) -> {done}")
    pressed += 1 if done else 0
    time.sleep(0.5)
sys.exit(0 if pressed == TIMES else 1)
