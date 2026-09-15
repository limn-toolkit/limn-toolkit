#!/usr/bin/env python3
"""Performs a verb of a Limn application through AT-SPI, from outside the process, and prints
what the bridge answered.

This is the client half of a DoAction: walk-the-probe.py shows what a reader is TOLD, and this shows
what a reader can DO. The two are different evidence. A tree can be perfect while every action on it
answers false -- which is what happened once, when the bridge handed its object handler a host field
of its own that nothing ever wrote -- and no walk, and nothing the provider prints about itself,
could have seen it. The probe prints "Save pressed, through the toolkit's own path" when the press
reaches the widget, so the reply here and that line in the probe's output are the whole of the
check.

Any node with an Action interface, not only a button: a combo box's `expand` is what opens a real
native popup window from outside the process, which is how the Ubuntu after-lane check gets a popup
to walk without a screen reader and without touching the guest's keyboard.

It uses libatspi's own typelib, which is what Orca uses, so a press this makes is a press Orca
could make.

usage: press-the-probe.py [application-name-substring] [node-name] [times] [role-substring] [verb]

The role defaults to "button" and the verb to the node's first, which is what a press was before
the other two arguments existed.
"""
import sys
import time

import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi  # noqa: E402

WANTED = sys.argv[1] if len(sys.argv) > 1 else "Limn"
NAME = sys.argv[2] if len(sys.argv) > 2 else "Save"
TIMES = int(sys.argv[3]) if len(sys.argv) > 3 else 3
ROLE = sys.argv[4] if len(sys.argv) > 4 else "button"
VERB = sys.argv[5] if len(sys.argv) > 5 else None
MAX_DEPTH = 8


def find(node, name, depth=0):
    if (node.get_name() or "") == name and ROLE in node.get_role_name():
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

target = find(app, NAME)
if target is None:
    print(f"no {ROLE} named {NAME!r} under {app.get_name()!r}")
    sys.exit(3)

count = Atspi.Action.get_n_actions(target)
verbs = [Atspi.Action.get_action_name(target, i) for i in range(count)]
print(f"{target.get_role_name()} {NAME!r} actions={verbs}")
index = 0
if VERB is not None:
    if VERB not in verbs:
        print(f"{NAME!r} does not offer {VERB!r}: it offers {verbs}")
        sys.exit(3)
    index = verbs.index(VERB)
pressed = 0
for _ in range(TIMES):
    done = Atspi.Action.do_action(target, index)
    print(f"DoAction({verbs[index] if verbs else index}) -> {done}")
    pressed += 1 if done else 0
    time.sleep(0.5)
sys.exit(0 if pressed == TIMES else 1)
