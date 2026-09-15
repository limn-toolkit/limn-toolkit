#!/usr/bin/env python3
"""Prints every accessibility event a named application sends, as libatspi hands it to a client.

Run it ON THE MACHINE, in the graphical session's environment, while the application runs. It
registers, through libatspi's own typelib (what Orca uses), for the event types a screen reader
follows -- active-descendant, children-changed, state-changed, text-changed, text-caret-moved,
announcement, selection-changed, bounds-changed and the window events -- and prints, for each one
whose source belongs to an application whose name contains the given text:

  the event type, detail1, detail2, the source (role, name, and its path on the bus), and any_data as
  libatspi made it: an accessible (role, name, index in parent, whether its state set holds DEFUNCT),
  a string, a rectangle, or nothing.

After a children-changed event it also prints the source's child count and children as libatspi's
cache answers them, which is what a long-lived client like Orca reads.

A signal libatspi refuses (a signature it does not accept) produces no line here and a warning on
standard error, so keep standard error with the output.

usage: events-check.py [application-name-substring] [seconds-to-listen]
"""
import sys

import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi, GLib  # noqa: E402

WANTED = (sys.argv[1] if len(sys.argv) > 1 else "Limn").lower()
LISTEN = float(sys.argv[2]) if len(sys.argv) > 2 else 20.0

TYPES = [
    "object:active-descendant-changed",
    "object:children-changed",
    "object:state-changed",
    "object:text-changed",
    "object:text-caret-moved",
    "object:text-selection-changed",
    "object:announcement",
    "object:selection-changed",
    "object:bounds-changed",
    "window:activate",
    "window:deactivate",
    "window:create",
    "window:destroy",
]


def safe(call, default="?"):
    try:
        return call()
    except Exception as e:  # a defunct or unreachable object
        return "%s(%s)" % (default, type(e).__name__)


def label(node):
    if node is None:
        return "None"
    return "%s %r" % (safe(node.get_role_name), safe(node.get_name))


def path_of(node):
    return safe(lambda: node.get_path() if hasattr(node, "get_path") else node.path, "path?")


def defunct(node):
    return safe(lambda: node.get_state_set().contains(Atspi.StateType.DEFUNCT))


def belongs(event):
    app = safe(lambda: event.source.get_application(), None)
    name = safe(lambda: app.get_name(), "") if app is not None and not isinstance(app, str) else ""
    return isinstance(name, str) and WANTED in name.lower()


def data_of(event):
    value = event.any_data
    if value is None:
        return "any_data=None"
    if isinstance(value, Atspi.Accessible):
        return "any_data=accessible %s index_in_parent=%s defunct=%s" % (
            label(value), safe(value.get_index_in_parent), defunct(value))
    if isinstance(value, str):
        return "any_data=str %r" % value
    if isinstance(value, Atspi.Rect):
        return "any_data=rect (%d, %d, %d, %d)" % (value.x, value.y, value.width, value.height)
    return "any_data=%s %r" % (type(value).__name__, value)


def on_event(event):
    if not belongs(event):
        return
    print("%.3f %s d1=%d d2=%d source=%s %s" % (
        GLib.get_monotonic_time() / 1e6, event.type, event.detail1, event.detail2,
        label(event.source), data_of(event)))
    if event.type.startswith("object:children-changed"):
        source = event.source
        count = safe(source.get_child_count)
        names = []
        if isinstance(count, int):
            for i in range(count):
                names.append(label(safe(lambda i=i: source.get_child_at_index(i), None)))
        print("      cached children of %s: %s %s" % (label(source), count, names))
    sys.stdout.flush()


def main():
    listener = Atspi.EventListener.new(on_event)
    for kind in TYPES:
        listener.register(kind)
    GLib.timeout_add(int(LISTEN * 1000), lambda: (Atspi.event_quit(), False)[1])
    print("listening %.1f s for applications named like %r" % (LISTEN, WANTED))
    sys.stdout.flush()
    Atspi.event_main()
    for kind in TYPES:
        listener.deregister(kind)
    print("done")


if __name__ == "__main__":
    main()
