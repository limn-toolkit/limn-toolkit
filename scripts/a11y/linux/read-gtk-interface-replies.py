#!/usr/bin/env python3
"""Read, off this machine's GTK 3 (through the ATK bridge) or GTK 4 (its own AT-SPI code), the raw D-Bus
replies a toolkit gives to the calls the Limn bridge answers by choice rather than by a reading.

Four questions, each read with raw D-Bus messages on the accessibility bus so the reply's own signature
and error name are printed, not what a binding makes of them:

1. Component.GetPosition and GetSize: the reply signature ("ii", two out arguments, or "(ii)", one
   struct), on a widget and on the application object.
2. The "version" property of every interface each widget serves: its value and type, or the error.
3. EditableText.InsertText's length: what a text field holds after SetTextContents("ab") and then
   InsertText(1, "é\U0001F600x", length) for several lengths. "é" is 1 character and 2 UTF-8
   bytes, the emoji 1 character and 4 bytes, so a length of 2 inserts "é" if it counts bytes and
   "é\U0001F600" if it counts characters.
4. Text.GetStringAtOffset with PARAGRAPH (4), beside LINE (3) and SENTENCE (2), over a multi-line text
   view, at several offsets.
5. Properties.Set(org.a11y.atspi.Value, ...) refused: CurrentValue on an insensitive spin button and on a
   level bar, and MinimumValue on an enabled spin button: the reply type and error name, and the value
   read back.

Run it ON THE MACHINE, inside a graphical session (WAYLAND_DISPLAY or DISPLAY, and the session bus
exported). It opens one small window for a few seconds and closes it; it starts no screen reader and
changes no setting.

    read-gtk-interface-replies.py 3|4          # the reading, against a GTK 3 or a GTK 4 window
    read-gtk-interface-replies.py 3|4 --window # (internal) the GTK side, driven over stdin

The GTK side runs in a child process: a client in the process that owns the accessibles would call
itself synchronously and hang.
"""
import os
import platform
import subprocess
import sys
import time

ENTRY_TEXT = "aé\U0001F600b"
VIEW_TEXT = "one two. three four.\nfive six.\n\nseven"
INSERTED = "é\U0001F600x"
LENGTHS = [1, 2, 3, 6, 7, 100, -1]
INTERFACES = ["Accessible", "Component", "Action", "Selection", "Value", "Text", "EditableText",
              "Table", "TableCell", "Application"]


def app_name(major):
    return "limn-gtk%s-replies-reading" % major


def window_side(major):
    import gi
    gi.require_version("Gtk", "%s.0" % major)
    from gi.repository import GLib, Gtk

    name = app_name(major)
    GLib.set_prgname(name)
    GLib.set_application_name(name)
    if major == "4":
        Gtk.init()
    else:
        Gtk.init([])

    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
    entry = Gtk.Entry()
    entry.set_text(ENTRY_TEXT)
    view = Gtk.TextView()
    view.get_buffer().set_text(VIEW_TEXT)
    disabled = Gtk.SpinButton.new_with_range(0, 10, 1)
    disabled.set_value(3)
    disabled.set_sensitive(False)
    enabled = Gtk.SpinButton.new_with_range(0, 10, 1)
    enabled.set_value(4)
    level = Gtk.LevelBar.new_for_interval(0, 1)
    level.set_value(0.5)
    widgets = [entry, view, disabled, enabled, level]
    for w in widgets:
        if major == "4":
            box.append(w)
        else:
            box.pack_start(w, False, False, 0)

    window = Gtk.Window(title=name)
    window.set_default_size(320, 240)
    if major == "4":
        window.set_child(box)
        window.present()
    else:
        window.add(box)
        window.show_all()

    loop = GLib.MainLoop()

    def on_line(channel, _condition):
        line = channel.readline()
        if not line or line.strip() == "quit":
            loop.quit()
            return False
        return True

    GLib.io_add_watch(GLib.IOChannel.unix_new(sys.stdin.fileno()), GLib.PRIORITY_DEFAULT,
                      GLib.IOCondition.IN | GLib.IOCondition.HUP, on_line)
    print("window: presented", flush=True)
    loop.run()


def run(cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=False).stdout.strip()
    except OSError as e:
        return "(%s)" % e


def packages():
    names = ["gtk3", "gtk4", "at-spi2-core", "at-spi2-atk", "python3-gobject"]
    if run(["sh", "-c", "command -v rpm"]):
        return run(["rpm", "-q"] + names)
    if run(["sh", "-c", "command -v dpkg-query"]):
        return run(["dpkg-query", "-W", "-f=${Package} ${Version}\n", "libgtk-3-0t64", "libgtk-4-1",
                    "at-spi2-core", "libatk-bridge2.0-0t64", "python3-gi"])
    return "(no rpm or dpkg-query)"


def reading_side(major):
    from gi.repository import Gio, GLib

    name = app_name(major)
    print("# read-gtk-interface-replies.py %s" % major)
    print("# machine: %s" % platform.platform())
    print("# packages:\n#   " + packages().replace("\n", "\n#   "))
    print("# read at (machine clock, UTC): %s" % time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime()))
    print()

    session = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    address = session.call_sync("org.a11y.Bus", "/org/a11y/bus", "org.a11y.Bus", "GetAddress", None,
                                GLib.VariantType.new("(s)"), Gio.DBusCallFlags.NONE, 5000,
                                None).unpack()[0]
    bus = Gio.DBusConnection.new_for_address_sync(
        address, Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT
        | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)

    def send(dest, path, iface, member, sig=None, args=None):
        """One call; the reply as (signature, python body) or ("ERROR", error name + text)."""
        message = Gio.DBusMessage.new_method_call(dest, path, iface, member)
        if sig is not None:
            message.set_body(GLib.Variant("(%s)" % sig, tuple(args)))
        try:
            reply, _serial = bus.send_message_with_reply_sync(
                message, Gio.DBusSendMessageFlags.NONE, 5000, None)
        except GLib.GError as e:
            return "ERROR", "(no reply: %s)" % e.message
        if reply.get_message_type() == Gio.DBusMessageType.ERROR:
            body = reply.get_body()
            text = body.unpack()[0] if body is not None and body.n_children() > 0 else ""
            return "ERROR", "%s: %s" % (reply.get_error_name(), text)
        body = reply.get_body()
        return reply.get_signature(), (body.unpack() if body is not None else ())

    def prop(dest, path, iface, key):
        sig, body = send(dest, path, "org.freedesktop.DBus.Properties", "Get", "ss",
                         ["org.a11y.atspi." + iface, key])
        if sig == "ERROR":
            return "ERROR " + body
        return body[0]

    child = subprocess.Popen([sys.executable, os.path.abspath(__file__), major, "--window"],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    print(child.stdout.readline().strip())

    app = None
    for _ in range(60):
        time.sleep(0.1)
        sig, body = send("org.a11y.atspi.Registry", "/org/a11y/atspi/accessible/root",
                         "org.a11y.atspi.Accessible", "GetChildren")
        if sig == "ERROR":
            continue
        for dest, path in body[0]:
            if prop(dest, path, "Accessible", "Name") == name:
                app = (dest, path)
        if app is not None:
            sig, body = send(app[0], app[1], "org.a11y.atspi.Accessible", "GetChildren")
            if sig != "ERROR" and len(body[0]) > 0:
                break
    if app is None:
        print("no application named %s on the desktop; nothing read" % name)
        child.stdin.write("quit\n")
        child.stdin.flush()
        child.wait(timeout=10)
        return 1
    time.sleep(1.0)
    dest = app[0]
    print("application: %s %s" % app)

    nodes = []

    def walk(path, depth):
        if depth > 14 or len(nodes) > 400:
            return
        sig, body = send(dest, path, "org.a11y.atspi.Accessible", "GetInterfaces")
        interfaces = [] if sig == "ERROR" else list(body[0])
        sig, role = send(dest, path, "org.a11y.atspi.Accessible", "GetRoleName")
        nodes.append((path, depth, "" if sig == "ERROR" else role[0], interfaces))
        sig, body = send(dest, path, "org.a11y.atspi.Accessible", "GetChildren")
        if sig != "ERROR":
            for _d, kid in body[0]:
                walk(kid, depth + 1)

    walk(app[1], 0)

    def short(interfaces):
        return [i.replace("org.a11y.atspi.", "") for i in interfaces]

    def text_of(path):
        sig, body = send(dest, path, "org.a11y.atspi.Text", "GetText", "ii", [0, -1])
        return None if sig == "ERROR" else body[0]

    print("==== the window's accessibles")
    for path, depth, role, interfaces in nodes:
        print("%s%s %s %s" % ("  " * depth, path, role, short(interfaces)))
    print()

    print("==== 1. Component.GetPosition(u) and GetSize() reply signatures")
    component = [n for n in nodes if "org.a11y.atspi.Component" in n[3]]
    targets = [(app[1], "application object")] + [(n[0], n[2]) for n in component[:4]]
    for path, what in targets:
        for coord in [0, 1]:
            sig, body = send(dest, path, "org.a11y.atspi.Component", "GetPosition", "u", [coord])
            print("  %s (%s) GetPosition(%d): signature %r body %r" % (path, what, coord, sig, body))
        sig, body = send(dest, path, "org.a11y.atspi.Component", "GetSize")
        print("  %s (%s) GetSize(): signature %r body %r" % (path, what, sig, body))
        sig, body = send(dest, path, "org.a11y.atspi.Component", "GetExtents", "u", [0])
        print("  %s (%s) GetExtents(0): signature %r body %r" % (path, what, sig, body))
    print()

    print("==== 2. Properties.Get(<interface>, \"version\") on every interface served")
    seen = set()
    for path, _depth, role, interfaces in [(app[1], 0, "application", INTERFACES)] + nodes:
        for iface in short(interfaces):
            if iface not in INTERFACES or (role, iface) in seen:
                continue
            seen.add((role, iface))
            raw = Gio.DBusMessage.new_method_call(dest, path, "org.freedesktop.DBus.Properties",
                                                  "Get")
            raw.set_body(GLib.Variant("(ss)", ("org.a11y.atspi." + iface, "version")))
            reply, _s = bus.send_message_with_reply_sync(raw, Gio.DBusSendMessageFlags.NONE,
                                                         5000, None)
            if reply.get_message_type() == Gio.DBusMessageType.ERROR:
                shown = "ERROR %s: %s" % (reply.get_error_name(), reply.get_body().unpack()[0])
            else:
                variant = reply.get_body().get_child_value(0).get_variant()
                shown = "%r (variant type %s)" % (variant.unpack(), variant.get_type_string())
            print("  %s %s %s.version: %s" % (path, role, iface, shown))
    print()

    print("==== 3. EditableText.InsertText(1, %r, length) after SetTextContents(\"ab\")" % INSERTED)
    editable = [n for n in nodes if "org.a11y.atspi.EditableText" in n[3]]
    entry = None
    for n in editable:
        t = text_of(n[0])
        if t is not None and "\n" not in t:
            entry = n[0]
            print("  the field: %s %s, text before %r" % (n[0], n[2], t))
            break
    if entry is None:
        print("  no single-line editable text found")
    else:
        for length in LENGTHS:
            sig, body = send(dest, entry, "org.a11y.atspi.EditableText", "SetTextContents", "s",
                             ["ab"])
            set_reply = (sig, body)
            time.sleep(0.2)
            sig, body = send(dest, entry, "org.a11y.atspi.EditableText", "InsertText", "isi",
                             [1, INSERTED, length])
            time.sleep(0.2)
            after = text_of(entry)
            print("  length %d: SetTextContents %r, InsertText %r %r, text now %r (%s)" % (
                length, set_reply, sig, body, after,
                None if after is None else " ".join("U+%04X" % ord(c) for c in after)))
    print()

    print("==== 4. Text.GetStringAtOffset over the multi-line view (granularity SENTENCE 2, LINE 3, "
          "PARAGRAPH 4)")
    view = None
    for n in nodes:
        if "org.a11y.atspi.Text" in n[3]:
            t = text_of(n[0])
            if t is not None and "\n" in t:
                view = n[0]
                print("  the view: %s %s, text %r" % (n[0], n[2], t))
                break
    if view is None:
        print("  no multi-line text found")
    else:
        for offset in [0, 5, 9, 20, 21, 25, 30, 31, 32, 34, 36]:
            for granularity in [2, 3, 4]:
                sig, body = send(dest, view, "org.a11y.atspi.Text", "GetStringAtOffset", "iu",
                                 [offset, granularity])
                print("  offset %d granularity %d: %r %r" % (offset, granularity, sig, body))
    print()

    print("==== 5. Properties.Set on org.a11y.atspi.Value")
    values = [n for n in nodes if "org.a11y.atspi.Value" in n[3]]
    for path, _depth, role, _interfaces in values:
        before = prop(dest, path, "Value", "CurrentValue")
        sensitive = send(dest, path, "org.a11y.atspi.Accessible", "GetState")
        print("  %s %s CurrentValue %r, GetState %r" % (path, role, before, sensitive))
        for key, number in [("CurrentValue", 7.0), ("MinimumValue", 1.0), ("NoSuchProperty", 1.0)]:
            sig, body = send(dest, path, "org.freedesktop.DBus.Properties", "Set", "ssv",
                             ["org.a11y.atspi.Value", key, GLib.Variant("d", number)])
            time.sleep(0.2)
            print("    Set(%s, d %s): %r %r; CurrentValue now %r, MinimumValue now %r" % (
                key, number, sig, body, prop(dest, path, "Value", "CurrentValue"),
                prop(dest, path, "Value", "MinimumValue")))
    print()

    child.stdin.write("quit\n")
    child.stdin.flush()
    child.wait(timeout=10)
    print("# window process exit status %s" % child.returncode)
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in ("3", "4"):
        print(__doc__)
        sys.exit(2)
    if "--window" in sys.argv:
        window_side(sys.argv[1])
    else:
        sys.exit(reading_side(sys.argv[1]))
