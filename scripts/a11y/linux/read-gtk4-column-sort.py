#!/usr/bin/env python3
"""Read, off this machine's GTK 4, how a sorted column view header is told to an AT-SPI client.

Decision 36 of the 2026-09-13 pass has a table's header cells publish their sort direction, and asks
first how each platform carries one. On Linux the answer is whatever a real toolkit on the desktop
sends, so this starts a GTK 4 window holding a Gtk.ColumnView of two sortable columns, sorts it by
the first column ascending, then descending, then by nothing, and after each step prints, through
libatspi, every accessible of that window: role (name and number), name, description, the state
set, the object attributes, the interfaces and the action names. It also listens to every object:
event the window sends while each step happens, so whether a sort change raises anything (and what)
is read and not assumed.

Run it ON THE MACHINE, inside a graphical session (WAYLAND_DISPLAY or DISPLAY, and the session bus
exported); it opens one small window for about ten seconds and closes it. It starts no screen
reader and changes no setting.

    read-gtk4-column-sort.py            # the reading
    read-gtk4-column-sort.py --window   # (internal) the GTK side, driven over stdin

The GTK side runs in a child process: a libatspi client in the process that owns the accessibles
would call itself synchronously and hang.
"""
import os
import platform
import subprocess
import sys
import time

APP_NAME = "limn-gtk4-sort-reading"
COLUMNS = ["Name", "Age"]
ROWS = [("Carol", "41"), ("Alice", "29"), ("Bob", "35")]


def window_side():
    import gi
    gi.require_version("Gtk", "4.0")
    from gi.repository import Gio, GLib, Gtk, GObject

    GLib.set_prgname(APP_NAME)
    GLib.set_application_name(APP_NAME)
    Gtk.init()

    class Row(GObject.Object):
        def __init__(self, cells):
            super().__init__()
            self.cells = cells

    store = Gio.ListStore(item_type=Row)
    for cells in ROWS:
        store.append(Row(cells))

    view = Gtk.ColumnView()
    columns = []
    for index, title in enumerate(COLUMNS):
        factory = Gtk.SignalListItemFactory()
        factory.connect("setup", lambda f, item: item.set_child(Gtk.Label()))
        factory.connect("bind", lambda f, item, i=index: item.get_child().set_label(
            item.get_item().cells[i]))
        column = Gtk.ColumnViewColumn(title=title, factory=factory)

        def compare(a, b, _data, i=index):
            return (a.cells[i] > b.cells[i]) - (a.cells[i] < b.cells[i])

        column.set_sorter(Gtk.CustomSorter.new(compare, None))
        view.append_column(column)
        columns.append(column)
    sort_model = Gtk.SortListModel(model=store, sorter=view.get_sorter())
    view.set_model(Gtk.SingleSelection(model=sort_model))

    window = Gtk.Window(title=APP_NAME)
    window.set_default_size(320, 200)
    window.set_child(view)
    window.present()

    loop = GLib.MainLoop()

    def on_line(channel, _condition):
        line = channel.readline()
        if not line:
            loop.quit()
            return False
        words = line.split()
        if words[0] == "asc":
            view.sort_by_column(columns[int(words[1])], Gtk.SortType.ASCENDING)
        elif words[0] == "desc":
            view.sort_by_column(columns[int(words[1])], Gtk.SortType.DESCENDING)
        elif words[0] == "none":
            view.sort_by_column(None, Gtk.SortType.ASCENDING)
        elif words[0] == "quit":
            loop.quit()
            return False
        print("window: did", line.strip(), flush=True)
        return True

    GLib.io_add_watch(GLib.IOChannel.unix_new(sys.stdin.fileno()), GLib.PRIORITY_DEFAULT,
                      GLib.IOCondition.IN | GLib.IOCondition.HUP, on_line)
    print("window: presented", flush=True)
    loop.run()
    window.destroy()


def run(cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=False).stdout.strip()
    except OSError as e:
        return "(%s)" % e


def packages():
    names = ["gtk4", "at-spi2-core", "python3-gobject"]
    if run(["sh", "-c", "command -v rpm"]):
        return run(["rpm", "-q"] + names)
    if run(["sh", "-c", "command -v dpkg-query"]):
        return run(["dpkg-query", "-W", "-f=${Package} ${Version}\n", "libgtk-4-1", "at-spi2-core",
                    "python3-gi"])
    return "(no rpm or dpkg-query)"


def reading_side():
    import gi
    gi.require_version("Atspi", "2.0")
    from gi.repository import Atspi, GLib

    print("# read-gtk4-column-sort.py")
    print("# machine: %s" % platform.platform())
    print("# packages:\n#   " + packages().replace("\n", "\n#   "))
    print("# read at (machine clock, UTC): %s" % time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime()))
    print()

    events = []

    def on_event(event):
        source = event.source
        try:
            application = source.get_application()
            if application is None or application.get_name() != APP_NAME:
                return  # another application of the desktop (a clock, a panel)
            who = "%s %r" % (source.get_role_name(), source.get_name())
        except Exception as e:  # a source that died
            who = "(%s)" % e
        data = event.any_data
        events.append("%s d1=%s d2=%s any_data=%r from %s" % (
            event.type, event.detail1, event.detail2, data, who))

    listener = Atspi.EventListener.new(on_event)
    for kind in ["object:state-changed", "object:attributes-changed", "object:property-change",
                 "object:children-changed", "object:visible-data-changed",
                 "object:selection-changed", "object:active-descendant-changed"]:
        listener.register(kind)

    child = subprocess.Popen([sys.executable, os.path.abspath(__file__), "--window"],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    print(child.stdout.readline().strip())

    def pump(seconds):
        context = GLib.MainContext.default()
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            context.iteration(False)
            time.sleep(0.01)

    def find_app():
        desktop = Atspi.get_desktop(0)
        for i in range(desktop.get_child_count()):
            app = desktop.get_child_at_index(i)
            if app is not None and app.get_name() == APP_NAME:
                return app
        return None

    app = None
    for _ in range(50):
        pump(0.1)
        app = find_app()
        if app is not None and app.get_child_count() > 0:
            break
    if app is None:
        print("no application named %s on the desktop; nothing read" % APP_NAME)
        child.stdin.write("quit\n")
        child.stdin.flush()
        child.wait(timeout=10)
        return 1

    def describe(node, depth):
        role = node.get_role()
        try:
            attributes = node.get_attributes()
        except Exception as e:
            attributes = "(%s)" % e
        states = sorted(Atspi.StateType(s).value_nick for s in node.get_state_set().get_states())
        interfaces = node.get_interfaces()
        actions = []
        if "Action" in interfaces:
            for a in range(Atspi.Action.get_n_actions(node)):
                actions.append(Atspi.Action.get_action_name(node, a))
        print("%s%s (%d) %r desc=%r states=%s attributes=%s interfaces=%s actions=%s" % (
            "  " * depth, node.get_role_name(), int(role), node.get_name(), node.get_description(),
            states, attributes, interfaces, actions))
        for i in range(node.get_child_count()):
            kid = node.get_child_at_index(i)
            if kid is not None and depth < 14:
                describe(kid, depth + 1)

    def step(label, command):
        events.clear()
        if command:
            child.stdin.write(command + "\n")
            child.stdin.flush()
            print(child.stdout.readline().strip())
        pump(1.5)
        print("==== %s" % label)
        print("---- events while it happened (%d)" % len(events))
        for line in events:
            print("  " + line)
        print("---- the window's accessibles")
        Atspi.Accessible.clear_cache(app)
        for i in range(app.get_child_count()):
            describe(app.get_child_at_index(i), 0)
        print()

    step("unsorted, as presented", None)
    step("sorted by column 0 (Name) ascending", "asc 0")
    step("sorted by column 0 (Name) descending", "desc 0")
    step("sorted by column 1 (Age) ascending", "asc 1")
    step("sorted by nothing", "none")

    child.stdin.write("quit\n")
    child.stdin.flush()
    child.wait(timeout=10)
    for kind in ["object:state-changed", "object:attributes-changed", "object:property-change",
                 "object:children-changed", "object:visible-data-changed",
                 "object:selection-changed", "object:active-descendant-changed"]:
        listener.deregister(kind)
    print("# window process exit status %s" % child.returncode)
    return 0


if __name__ == "__main__":
    if "--window" in sys.argv:
        window_side()
    else:
        sys.exit(reading_side())
