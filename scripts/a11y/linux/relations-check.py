#!/usr/bin/env python3
"""Asks a running Limn form what libatspi asks about a field's relations: the relation set, with
each target resolved and read back, and the description the walk copied beside it.

The Linux sibling of scripts/a11y/macos/axrelations.swift and
scripts/a11y/windows/walk-the-relations.ps1. Run it ON THE GUEST, in the session's environment,
with the demo up (`java -jar limn-demo-all.jar --scene form`). A relation is only worth publishing
if a client can FOLLOW it, so every target is resolved to its role and name rather than reported as
present, and the caption's own mirror is read back from the other end.

usage: relations-check.py [field-name]
"""
import sys, gi
gi.require_version("Atspi", "2.0")
from gi.repository import Atspi

wanted = sys.argv[1] if len(sys.argv) > 1 else "Email"


def find(node, pred):
    if pred(node):
        return node
    for i in range(node.get_child_count()):
        r = find(node.get_child_at_index(i), pred)
        if r:
            return r
    return None


def nick(kind):
    return getattr(kind, "value_nick", str(kind))


desk = Atspi.get_desktop(0)
app = None
for i in range(desk.get_child_count()):
    a = desk.get_child_at_index(i)
    if a and "Limn" in (a.get_name() or ""):
        app = a
if app is None:
    sys.exit("no Limn application on the bus")
field = find(app, lambda n: n.get_role_name() in ("entry", "text", "password text")
             and (n.get_name() or "") == wanted)
if field is None:
    sys.exit("no field named %r" % wanted)

print("field:", field.get_role_name(), repr(field.get_name()),
      "description:", repr(field.get_description()))
print("invalid_entry:", field.get_state_set().contains(Atspi.StateType.INVALID_ENTRY))
rels = field.get_relation_set()
print("relations:", len(rels))
for rel in rels:
    kind = rel.get_relation_type()
    targets = [rel.get_target(i) for i in range(rel.get_n_targets())]
    print("  %s (%d) -> %s" % (nick(kind), int(kind),
                               [(t.get_role_name(), t.get_name()) for t in targets]))
    for t in targets:
        back = [(nick(r.get_relation_type()),
                 [r.get_target(j).get_name() for j in range(r.get_n_targets())])
                for r in t.get_relation_set()]
        print("    %r answers relations %s" % (t.get_name(), back))
