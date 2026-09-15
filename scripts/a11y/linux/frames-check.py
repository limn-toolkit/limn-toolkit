#!/usr/bin/env python3
"""Reads what libatspi makes of a Limn process's windows: one application, its frames, and where
their relations lead.

Run it ON THE MACHINE, in the graphical session's environment, with a Limn process up. ADR 039 §2.3
has one AT-SPI application per process with every window a frame beneath it, so a native popup (a
ComboBox's list, a DatePicker's calendar) is a frame of the same application and its POPUP_FOR names
a field in the other frame. This prints, through libatspi's own typelib (what Orca uses):

  1. how many desktop applications match the name (one is the shape; two is a window registered as
     an application of its own), and for each its frames, with every relation's targets resolved to
     role, name, the frame that holds them and the application that owns them;
  2. with a listening time, every object:children-changed event whose source is such an
     application, with detail1 and the child the event carries, and the child list libatspi holds
     for the application afterwards.

usage: frames-check.py [application-name-substring] [seconds-to-listen]
"""
import sys

import gi

gi.require_version("Atspi", "2.0")
from gi.repository import Atspi, GLib  # noqa: E402

WANTED = sys.argv[1] if len(sys.argv) > 1 else "Limn"
LISTEN = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0


def nick(kind):
    return getattr(kind, "value_nick", str(kind))


def label(node):
    if node is None:
        return "None"
    try:
        return "%s %r" % (node.get_role_name(), node.get_name() or "")
    except Exception as e:  # a defunct object answers nothing
        return "<unreadable: %s>" % e


def frame_of(node):
    at = node
    while at is not None:
        parent = at.get_parent()
        if parent is not None and parent.get_role_name() == "application":
            return at
        at = parent
    return None


def matching_apps():
    desktop = Atspi.get_desktop(0)
    apps = []
    for i in range(desktop.get_child_count()):
        app = desktop.get_child_at_index(i)
        if app is not None and WANTED.lower() in (app.get_name() or "").lower():
            apps.append(app)
    return apps


def describe(app):
    count = app.get_child_count()
    print("application %s: %d frame(s)" % (label(app), count))
    for i in range(count):
        frame = app.get_child_at_index(i)
        print("  frame %d: %s index_in_parent=%d" % (i, label(frame), frame.get_index_in_parent()))
        stack = [frame]
        while stack:
            node = stack.pop()
            for rel in node.get_relation_set() or []:
                kind = rel.get_relation_type()
                for t in range(rel.get_n_targets()):
                    target = rel.get_target(t)
                    holder = frame_of(target) if target is not None else None
                    owner = target.get_application() if target is not None else None
                    print("    %s %s (%d) -> %s in frame %s of application %s" % (
                        label(node), nick(kind), int(kind), label(target), label(holder),
                        label(owner)))
            for c in range(node.get_child_count()):
                child = node.get_child_at_index(c)
                if child is not None:
                    stack.append(child)


apps = matching_apps()
print("applications matching %r: %d" % (WANTED, len(apps)))
for app in apps:
    describe(app)

if LISTEN > 0:
    def on_event(event):
        source = event.source
        try:
            if source is None or source.get_role_name() != "application":
                return
            if WANTED.lower() not in (source.get_name() or "").lower():
                return
        except Exception:
            return
        child = event.any_data if isinstance(event.any_data, Atspi.Accessible) else None
        print("event %s from %s detail1=%d detail2=%d child=%s" % (
            event.type, label(source), event.detail1, event.detail2, label(child)))
        sys.stdout.flush()

    listener = Atspi.EventListener.new(on_event)
    listener.register("object:children-changed")
    loop = GLib.MainLoop()
    GLib.timeout_add(int(LISTEN * 1000), loop.quit)
    print("listening %.1f s" % LISTEN)
    sys.stdout.flush()
    loop.run()
    listener.deregister("object:children-changed")
    print("after listening:")
    for app in matching_apps():
        describe(app)

sys.exit(0 if apps else 1)
