#!/usr/bin/env python3
"""Reads the D-Bus facts the Linux bridge's own client depends on, from the bus and libdbus installed
on this machine rather than from memory of the specification.

Run it ON THE MACHINE, inside the user's graphical session environment (DBUS_SESSION_BUS_ADDRESS must
name the session bus as unix:path=...). It speaks the wire protocol itself, over a plain unix socket,
the way limn-backend-lwjgl's DBus.Conn does, so what it prints is what a client of that shape is sent.
It never owns a name anyone else uses and never touches org.a11y.*; every connection it opens is its
own and is closed before it exits.

  1. the error names the bridge answers with (org.freedesktop.DBus.Error.Failed, .InvalidArgs,
     .UnknownMethod): whether the installed libdbus carries each as a NUL-terminated string, and the
     name the bus itself replies with when a call has arguments of the wrong type;
  2. NameOwnerChanged as the bus introspects it, and as the bus routes it: a connection that matches
     on member='NameOwnerChanged' with arg0 naming a well-known name, while a second connection
     requests that name and releases it; a third connection matches without arg0, to show what the
     arg0 clause keeps away;
  3. the longest message the bus accepts: after Hello, a fixed 16-byte header whose lengths declare a
     message of exactly 2^27 bytes, and on another connection one of 2^27 + 8, and whether the bus
     drops the connection at once or waits for the rest.

usage: read-dbus-bus-facts.py
"""
import os
import platform
import select
import socket
import struct
import subprocess
import sys
import time

PROBE_NAME = "org.limn.reading.OwnerProbe"
BUS = ("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus")


def say(*parts):
    print(*parts, flush=True)


def pad(n, a):
    return (a - n % a) % a


class Writer:
    def __init__(self):
        self.b = bytearray()

    def align(self, a):
        self.b += b"\0" * pad(len(self.b), a)

    def y(self, v):
        self.b.append(v)

    def u(self, v):
        self.align(4)
        self.b += struct.pack("<I", v)

    def s(self, v):
        e = v.encode()
        self.u(len(e))
        self.b += e + b"\0"

    def g(self, v):
        e = v.encode()
        self.y(len(e))
        self.b += e + b"\0"


def marshal_call(serial, dest, path, iface, member, sig="", args=()):
    body = Writer()
    for code, value in zip(sig, args):
        {"s": body.s, "u": body.u}[code](value)
    fields = Writer()
    # fields are written as if they started at offset 16, which is 8-aligned, so offsets agree
    def field(code, typ, value):
        fields.align(8)
        fields.y(code)
        fields.g(typ)
        {"o": fields.s, "s": fields.s, "g": fields.g}[typ](value)
    field(1, "o", path)
    field(2, "s", iface)
    field(3, "s", member)
    field(6, "s", dest)
    if sig:
        field(8, "g", sig)
    head = bytearray(b"l\x01\x00\x01")
    head += struct.pack("<III", len(body.b), serial, len(fields.b))
    msg = head + fields.b
    msg += b"\0" * pad(len(msg), 8)
    return bytes(msg + body.b)


class Reader:
    def __init__(self, data, pos=0):
        self.d = data
        self.p = pos

    def align(self, a):
        self.p += pad(self.p, a)

    def y(self):
        v = self.d[self.p]
        self.p += 1
        return v

    def u(self):
        self.align(4)
        v = struct.unpack_from("<I", self.d, self.p)[0]
        self.p += 4
        return v

    def s(self):
        n = self.u()
        v = self.d[self.p:self.p + n].decode()
        self.p += n + 1
        return v

    def g(self):
        n = self.y()
        v = self.d[self.p:self.p + n].decode()
        self.p += n + 1
        return v

    def value(self, code):
        if code in "so":
            return self.s()
        if code == "g":
            return self.g()
        if code == "u":
            return self.u()
        if code == "y":
            return self.y()
        raise ValueError("this reading does not parse type %r" % code)


def parse(msg):
    kind = msg[1]
    body_len, serial, fields_len = struct.unpack_from("<III", msg, 4)
    r = Reader(msg, 16)
    end = 16 + fields_len
    fields = {}
    while r.p < end:
        r.align(8)
        code = r.y()
        typ = r.g()
        fields[code] = r.value(typ)
    body_start = end + pad(end, 8)
    sig = fields.get(8, "")
    br = Reader(msg, body_start)
    body = []
    try:
        for code in sig:
            body.append(br.value(code))
    except ValueError as e:
        body.append("<%s>" % e)
    names = {1: "METHOD_CALL", 2: "METHOD_RETURN", 3: "ERROR", 4: "SIGNAL"}
    return {"type": names.get(kind, kind), "serial": serial, "reply_serial": fields.get(5),
            "sender": fields.get(7), "destination": fields.get(6), "path": fields.get(1),
            "interface": fields.get(2), "member": fields.get(3), "error_name": fields.get(4),
            "signature": sig, "body": body}


def socket_path():
    address = os.environ.get("DBUS_SESSION_BUS_ADDRESS", "")
    for part in address.split(";"):
        if part.startswith("unix:"):
            for kv in part[5:].split(","):
                if kv.startswith("path="):
                    return kv[5:]
    sys.exit("DBUS_SESSION_BUS_ADDRESS has no unix:path= transport: %r" % address)


class Conn:
    def __init__(self, label):
        self.label = label
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.connect(socket_path())
        self.serial = 1
        self.sock.sendall(b"\0AUTH EXTERNAL " + str(os.getuid()).encode().hex().encode() + b"\r\n")
        line = self.readline()
        if not line.startswith(b"OK "):
            raise RuntimeError("SASL: %r" % line)
        self.sock.sendall(b"BEGIN\r\n")
        self.unique = self.call(*BUS, "Hello")["body"][0]

    def readline(self):
        out = b""
        while not out.endswith(b"\r\n"):
            chunk = self.sock.recv(1)
            if not chunk:
                raise RuntimeError("EOF during SASL")
            out += chunk
        return out

    def recv_exact(self, n, timeout):
        data = b""
        deadline = time.monotonic() + timeout
        while len(data) < n:
            left = deadline - time.monotonic()
            if left <= 0 or not select.select([self.sock], [], [], left)[0]:
                return None
            chunk = self.sock.recv(n - len(data))
            if not chunk:
                raise EOFError
            data += chunk
        return data

    def read(self, timeout):
        head = self.recv_exact(16, timeout)
        if head is None:
            return None
        body_len, _, fields_len = struct.unpack_from("<III", head, 4)
        rest = self.recv_exact(fields_len + pad(fields_len, 8) + body_len, 5)
        return parse(head + rest)

    def send(self, dest, path, iface, member, sig="", args=()):
        serial = self.serial
        self.serial += 1
        self.sock.sendall(marshal_call(serial, dest, path, iface, member, sig, args))
        return serial

    def call(self, dest, path, iface, member, sig="", args=(), others=None):
        serial = self.send(dest, path, iface, member, sig, args)
        while True:
            m = self.read(10)
            if m is None:
                raise RuntimeError("no reply to %s within 10 s" % member)
            if m["reply_serial"] == serial:
                return m
            if others is not None:
                others.append(m)

    def drain(self, timeout):
        got = []
        while True:
            m = self.read(timeout)
            if m is None:
                return got
            got.append(m)

    def close(self):
        self.sock.close()


def show(m):
    return "%s sender=%s path=%s interface=%s member=%s error=%s signature=%r body=%r" % (
        m["type"], m["sender"], m["path"], m["interface"], m["member"], m["error_name"],
        m["signature"], m["body"])


def run(*cmd):
    say("$ " + " ".join(cmd))
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
        say((out.stdout + out.stderr).rstrip())
        say("(exit %d)" % out.returncode)
    except (OSError, subprocess.TimeoutExpired) as e:
        say("(not run: %s)" % e)


def section_machine():
    say("==== machine")
    say("date -u:", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    say("uname:", platform.system(), platform.release(), platform.machine())
    try:
        with open("/etc/os-release") as f:
            for line in f:
                if line.startswith("PRETTY_NAME"):
                    say(line.strip())
    except OSError:
        pass
    if subprocess.run(["sh", "-c", "command -v rpm"], capture_output=True).returncode == 0:
        run("rpm", "-q", "dbus-broker", "dbus-daemon", "dbus-libs")
    else:
        run("sh", "-c", "dpkg -l dbus-daemon dbus-broker libdbus-1-3 | grep ^ii")
    run("sh", "-c", "ps -o pid=,args= -u $(id -u) | grep -E 'dbus-(broker|daemon)' | grep -v grep")
    say("session bus socket:", socket_path())


def section_error_names():
    say("")
    say("==== 1. error names")
    wanted = [b"org.freedesktop.DBus.Error.Failed", b"org.freedesktop.DBus.Error.InvalidArgs",
              b"org.freedesktop.DBus.Error.UnknownMethod"]
    candidates = ["/usr/lib64/libdbus-1.so.3", "/usr/lib/libdbus-1.so.3"]
    candidates += ["/usr/lib/%s/libdbus-1.so.3" % d for d in os.listdir("/usr/lib")
                   if d.endswith("-linux-gnu")]
    for lib in candidates:
        if not os.path.exists(lib):
            continue
        real = os.path.realpath(lib)
        with open(real, "rb") as f:
            data = f.read()
        say("installed libdbus: %s -> %s (%d bytes)" % (lib, real, len(data)))
        for name in wanted:
            at = data.find(b"\0" + name + b"\0")
            say("  %s as a NUL-terminated string: %s" % (
                name.decode(), "offset %d" % (at + 1) if at >= 0 else "ABSENT"))
    c = Conn("args")
    reply = c.call(*BUS, "GetNameOwner", "u", (42,))
    say("the bus's reply to GetNameOwner called with a uint32 where it takes a string:")
    say("  " + show(reply))
    reply = c.call(*BUS, "NoSuchMemberOfTheBus")
    say("the bus's reply to a member it does not have:")
    say("  " + show(reply))
    c.close()


def section_name_owner_changed():
    say("")
    say("==== 2. NameOwnerChanged")
    c = Conn("introspect")
    xml = c.call(BUS[0], BUS[1], "org.freedesktop.DBus.Introspectable", "Introspect")["body"][0]
    lines = xml.splitlines()
    for i, line in enumerate(lines):
        if 'name="NameOwnerChanged"' in line or 'name="GetNameOwner"' in line:
            j = i
            while j < len(lines):
                say("  " + lines[j].strip())
                if "</signal>" in lines[j] or "</method>" in lines[j]:
                    break
                j += 1
    c.close()

    rule = ("type='signal',sender='org.freedesktop.DBus',path='/org/freedesktop/DBus',"
            "interface='org.freedesktop.DBus',member='NameOwnerChanged',arg0='%s'" % PROBE_NAME)
    rule_all = ("type='signal',sender='org.freedesktop.DBus',path='/org/freedesktop/DBus',"
                "interface='org.freedesktop.DBus',member='NameOwnerChanged'")
    watcher = Conn("watcher")
    say("watcher %s AddMatch %s -> %s" % (watcher.unique, rule,
                                         show(watcher.call(*BUS, "AddMatch", "s", (rule,)))))
    everything = Conn("everything")
    say("second watcher %s AddMatch %s -> %s" % (everything.unique, rule_all,
                                                show(everything.call(*BUS, "AddMatch", "s",
                                                                     (rule_all,)))))
    owner = Conn("owner")
    say("owner connection:", owner.unique)
    say("owner RequestName(%s, DO_NOT_QUEUE=4) -> %s" % (
        PROBE_NAME, show(owner.call(*BUS, "RequestName", "su", (PROBE_NAME, 4)))))
    time.sleep(0.5)
    say("owner ReleaseName(%s) -> %s" % (PROBE_NAME, show(owner.call(*BUS, "ReleaseName", "s",
                                                                       (PROBE_NAME,)))))
    time.sleep(0.5)
    owner.close()
    time.sleep(0.5)
    say("what the watcher WITH arg0 received (1 s after the owner closed):")
    for m in watcher.drain(1.0):
        say("  " + show(m))
    say("what the watcher WITHOUT arg0 received:")
    for m in everything.drain(1.0):
        say("  " + show(m))
    watcher.close()
    everything.close()


def section_length():
    say("")
    say("==== 3. the longest message the bus accepts")
    for total in (1 << 27, (1 << 27) + 8):
        c = Conn("length")
        # NameAcquired follows Hello unasked; read it (and anything else queued) before the probe,
        # so every byte that follows the header is the bus's answer to the header.
        for m in c.drain(0.5):
            say("  (before the header, the bus sent: %s)" % show(m))
        fields_len = 0
        body_len = total - 16
        head = b"l\x01\x00\x01" + struct.pack("<III", body_len, c.serial, fields_len)
        started = time.monotonic()
        c.sock.sendall(head)
        outcome = "the bus waits for the rest: no EOF within 3 s"
        try:
            deadline = started + 3
            while time.monotonic() < deadline:
                ready = select.select([c.sock], [], [], deadline - time.monotonic())[0]
                if not ready:
                    break
                chunk = c.sock.recv(4096)
                if not chunk:
                    outcome = "the bus closed the connection after %.0f ms" % (
                        (time.monotonic() - started) * 1000)
                    break
                outcome = "the bus sent %d unexpected bytes and kept the connection" % len(chunk)
        except OSError as e:
            outcome = "the connection failed after %.0f ms: %s" % (
                (time.monotonic() - started) * 1000, e)
        say("a fixed header declaring a %d-byte message (body %d, header fields 0): %s" % (
            total, body_len, outcome))
        c.close()


def main():
    if not os.environ.get("DBUS_SESSION_BUS_ADDRESS"):
        sys.exit("DBUS_SESSION_BUS_ADDRESS is not set: run inside the graphical session's environment")
    section_machine()
    section_error_names()
    section_name_owner_changed()
    section_length()


if __name__ == "__main__":
    main()
