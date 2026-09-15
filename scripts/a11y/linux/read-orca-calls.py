#!/usr/bin/env python3
"""Print every line of the installed Orca that matches one of the given patterns, with its file and line.

Run it ON THE MACHINE; it starts nothing, reads no bus and needs no session. The Linux bridge answers
the Text, Selection, Value, EditableText and Component questions a reader asks and the object
attributes it reads, and which of those a reader really calls, with which arguments, is a fact of the
installed reader, not of anyone's memory of its repository. read-orca-event-consumers.py prints
functions whose names are known; this finds the names: every line of the installed Orca package that
matches a regular expression (Python syntax), uncut, with the two lines before and after it, grouped
by pattern. A pattern that matches nothing is listed as such, so an absence is read and not assumed.

    read-orca-calls.py PATTERN [PATTERN ...]
"""
import hashlib
import os
import platform
import re
import subprocess
import sys
import time


def run(cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=False).stdout.strip()
    except OSError as e:
        return "(%s)" % e


def orca_package_dir():
    out = run([sys.executable, "-c", "import orca, os; print(os.path.dirname(orca.__file__))"])
    return out if out and os.path.isdir(out) else None


def main(patterns):
    if not patterns:
        print(__doc__.strip().splitlines()[-1].strip(), file=sys.stderr)
        return 2
    root = orca_package_dir()
    print("# read-orca-calls.py")
    print("# machine: %s" % platform.platform())
    owner = run(["sh", "-c", "rpm -q orca 2>/dev/null || dpkg-query -W -f='${Package} ${Version}' orca"])
    print("# orca package: %s" % owner)
    print("# orca python package: %s" % root)
    print("# read at (machine clock, UTC): %s" % time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime()))
    if root is None:
        print("no importable orca package; nothing read")
        return 1
    files = []
    for directory, _dirs, names in os.walk(root):
        for name in sorted(names):
            if name.endswith(".py"):
                files.append(os.path.join(directory, name))
    files.sort()
    digest = hashlib.sha256()
    sources = {}
    for path in files:
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
        digest.update(path.encode() + b"\0" + text.encode())
        sources[path] = text.splitlines()
    print("# %d files, sha256 over their paths and contents: %s" % (len(files), digest.hexdigest()))
    for pattern in patterns:
        regex = re.compile(pattern)
        print()
        print("==== /%s/" % pattern)
        count = 0
        for path in files:
            lines = sources[path]
            for number, line in enumerate(lines, 1):
                if regex.search(line):
                    count += 1
                    rel = os.path.relpath(path, root)
                    lo, hi = max(1, number - 2), min(len(lines), number + 2)
                    print("-- %s:%d" % (rel, number))
                    for n in range(lo, hi + 1):
                        print("%s%5d  %s" % (">" if n == number else " ", n, lines[n - 1]))
        if count == 0:
            print("(matches nothing in the installed package)")
        else:
            print("(%d matching lines)" % count)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
