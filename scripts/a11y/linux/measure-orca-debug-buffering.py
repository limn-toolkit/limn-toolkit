#!/usr/bin/env python3
"""How much of Orca's debug log can still be in memory when someone reads the file.

Run this ON THE GUEST, with the same python3 Orca runs under. Orca's launcher opens its debug
file as open(path, "w", encoding="utf-8") and orca/debug.py writes each line with
writelines([text, "\\n"]) and never flushes, so whatever this interpreter holds back for a file
opened that way is what a reader of the log cannot see until Orca exits cleanly. This opens a
scratch file the same way in each directory given, writes Orca-shaped lines one at a time, and
reports how many bytes had been written when the first one reached the file.

    python3 measure-orca-debug-buffering.py [DIR ...]     # default: /tmp and the current directory

Writes one scratch file per directory and removes it; changes nothing else.
"""
import io
import os
import sys
import tempfile

LINE = "12:34:56.789012 - EVENT MANAGER: Queued object:selection-changed priority: 3, counter: 42"


def measure(directory):
    handle, path = tempfile.mkstemp(prefix="limn-orca-buffer-", suffix=".txt", dir=directory)
    os.close(handle)
    try:
        log = open(path, "w", encoding="utf-8")                    # as /usr/bin/orca opens it
        written = 0
        lines = 0
        try:
            while os.stat(path).st_size == 0 and written < 16 * 1024 * 1024:
                log.writelines([LINE, "\n"])                         # as orca/debug.py writes
                written += len(LINE) + 1
                lines += 1
            on_disk = os.stat(path).st_size
            blksize = os.stat(path).st_blksize
            chunk = getattr(log, "_CHUNK_SIZE", "n/a")
        except OSError as exc:
            print("%s: line %d failed after %d bytes had been accepted without error, file size %d: %s"
                  % (directory, lines + 1, written, os.stat(path).st_size, exc))
            try:
                log.close()
            except OSError:
                pass
            return
        finally:
            if not log.closed:
                log.close()
        print("%s: st_blksize %s, TextIOWrapper._CHUNK_SIZE %s; the file was still empty after "
              "%d bytes (%d lines of %d bytes); line %d put %d bytes on disk"
              % (directory, blksize, chunk, written - (len(LINE) + 1), lines - 1, len(LINE) + 1,
                 lines, on_disk))
    finally:
        os.unlink(path)


def main():
    print("# python %s, io.DEFAULT_BUFFER_SIZE %d" % (sys.version.split()[0], io.DEFAULT_BUFFER_SIZE))
    for directory in sys.argv[1:] or ["/tmp", os.getcwd()]:
        try:
            measure(directory)
        except OSError as exc:
            # Orca's debug.py swallows this same error and stops logging without a word, so a
            # directory that refuses the write is itself a finding, not a failed measurement.
            print("%s: the write failed: %s" % (directory, exc))


if __name__ == "__main__":
    main()
