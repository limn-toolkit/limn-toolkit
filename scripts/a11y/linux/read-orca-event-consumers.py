#!/usr/bin/env python3
"""Print, from the Orca installed on this machine, the code that consumes the events a toolkit sends.

Run it ON THE MACHINE; it starts nothing, reads no bus and needs no session. The Linux bridge shapes
its text, caret, window, announcement, active-descendant and children-changed signals after what
the platform's reader does with each field (detail1, detail2, any_data, the source path), so this
prints, line-numbered and uncut, every function of the installed Orca package whose name is given
(or the default list below), wherever in the package it is defined, plus every line of the package
that names one of the event types those functions are registered for. A name that is defined nowhere
is listed as such, so an absence is read and not assumed.

    read-orca-event-consumers.py [FUNCTION ...]

The default names cover Orca 50 (snake_case) and the Orca 4x series (camelCase) alike.
"""
import ast
import hashlib
import os
import platform
import subprocess
import sys
import time

DEFAULT_NAMES = [
    # event handlers of the default script
    "_on_text_inserted", "_on_text_deleted", "_on_caret_moved", "_on_text_selection_changed",
    "_on_window_activated", "_on_window_deactivated", "_on_window_created",
    "_on_window_destroyed", "_on_focused_changed", "_on_active_changed",
    "_on_announcement", "_on_active_descendant_changed", "_on_children_added",
    "_on_children_removed", "_on_expanded_changed",
    # the event manager's filters
    "_ignore_text_events", "_ignore_children_changed", "_ignore_by_spam_filter",
    "_ignore_active_descendant_or_selection", "_ignore_state_changed", "_get_priority",
    # the presentability predicates the handlers call
    "is_presentable_text_deletion", "is_presentable_text_insertion",
    "is_presentable_caret_moved_event", "is_presentable_active_descendant_change",
    "get_text_event_reason", "_get_text_event_reason", "inserted_text", "deleted_text",
    # Orca 4x spellings
    "onTextInserted", "onTextDeleted", "onCaretMoved", "onWindowActivated",
    "onWindowDeactivated", "onWindowCreated", "onWindowDestroyed", "onFocusedChanged",
    "onAnnouncement", "onActiveDescendantChanged", "onChildrenAdded", "onChildrenRemoved",
    "_ignore", "_isDuplicateEvent", "_getScriptForEvent", "insertedText", "deletedText",
]

EVENT_TYPES = [
    "object:text-changed", "object:text-caret-moved", "object:announcement",
    "window:activate", "window:deactivate", "window:create", "window:destroy",
    "object:active-descendant-changed", "object:children-changed", "state-changed:defunct",
    "state-changed:collapsed", "state-changed:expandable",
]


def say(text=""):
    print(text)


def run(cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=False).stdout.strip()
    except OSError as e:
        return f"({cmd[0]}: {e})"


def main():
    names = sys.argv[1:] or DEFAULT_NAMES
    say("==== machine")
    say("date -u: " + time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    say("uname: " + " ".join(platform.uname()[:3]) + " " + platform.machine())
    if os.path.exists("/etc/os-release"):
        with open("/etc/os-release") as f:
            say("".join(line for line in f if line.startswith("PRETTY_NAME")).strip())
    if run(["sh", "-c", "command -v rpm"]):
        say("$ rpm -q orca at-spi2-core python3")
        say(run(["rpm", "-q", "orca", "at-spi2-core", "python3"]))
    else:
        say("$ dpkg -l orca at-spi2-core python3 | grep ^ii")
        say(run(["sh", "-c", "dpkg -l orca at-spi2-core python3 | grep ^ii"]))
    try:
        import orca  # noqa: F401
        root = os.path.dirname(orca.__file__)
    except Exception as e:  # pragma: no cover - a machine without Orca
        say(f"orca is not importable by python3 here ({e}): nothing to read")
        return 0
    say("orca package directory: " + root)

    files = []
    for directory, _, entries in os.walk(root):
        for entry in sorted(entries):
            if entry.endswith(".py"):
                files.append(os.path.join(directory, entry))
    files.sort()

    found = {name: [] for name in names}
    sources = {}
    for path in files:
        with open(path, "rb") as f:
            data = f.read()
        text = data.decode("utf-8", "replace")
        sources[path] = (hashlib.sha256(data).hexdigest(), text.splitlines())
        try:
            tree = ast.parse(text)
        except SyntaxError:
            continue
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name in found:
                found[node.name].append((path, node.lineno, node.end_lineno))

    say()
    say("==== functions, by name, wherever the package defines them")
    for name in names:
        if not found[name]:
            say(f"---- {name}: defined nowhere in the package")
            continue
        for path, start, end in found[name]:
            digest, lines = sources[path]
            say(f"---- {name}: {os.path.relpath(path, root)} lines {start}-{end} (sha256 {digest})")
            for number in range(start, end + 1):
                say(f"{number}: {lines[number - 1]}")

    say()
    say("==== every line naming one of the event types (uncut)")
    for path in files:
        digest, lines = sources[path]
        for number, line in enumerate(lines, 1):
            if any(kind in line for kind in EVENT_TYPES):
                say(f"{os.path.relpath(path, root)}:{number}: {line}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
