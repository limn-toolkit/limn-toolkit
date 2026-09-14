#!/usr/bin/env bash
# Copy AT-SPI2's D-Bus interface definitions out of the machine's own ATK bridge.
#
# Run this ON THE GUEST. The distributions do not install at-spi2-core's xml/*.xml; the only copy
# of each interface's methods, properties and signals on the machine is the introspection string
# compiled into libatk-bridge (atk-adaptor's introspection data), which is what a GTK application
# answers Introspect() with. So a signature Limn serves is read from that string, and never from
# anyone's memory of the upstream file.
#
#     extract-atspi-introspection.sh OUT_DIR [LIBRARY] [INTERFACE ...]
#
# OUT_DIR    where <prefix>-<Interface>.xml files go (created if absent); PREFIX env sets <prefix>,
#            default "dbus"
# LIBRARY    the binary to read; default: libatk-bridge-2.0.so.0 as ldconfig resolves it
# INTERFACE  short names after org.a11y.atspi.; default: Selection Value Text EditableText
#            Event.Object Cache
#
# Each file is one comment naming the binary, its owning package and checksum and the command,
# then the string exactly as the binary holds it. The compiled string has no newlines, so the
# interface is one line. Needs binutils (strings) and coreutils; reads only.
set -euo pipefail

if [ $# -lt 1 ]; then
    sed -n '2,20p' "$0" >&2
    exit 2
fi
out_dir=$1
shift

library=${1:-}
if [ -n "$library" ] && [ -f "$library" ]; then
    shift
else
    library=$(ldconfig -p | awk '/libatk-bridge-2\.0\.so\.0 / { print $NF; exit }')
    if [ -z "$library" ]; then
        echo "no libatk-bridge-2.0.so.0 known to ldconfig; pass the library path" >&2
        exit 1
    fi
fi
real=$(readlink -f "$library")

if [ $# -eq 0 ]; then
    set -- Selection Value Text EditableText Event.Object Cache
fi

command -v strings >/dev/null || { echo "strings (binutils) is not installed; not installing it" >&2; exit 1; }

if command -v rpm >/dev/null && owner=$(rpm -qf "$real" 2>/dev/null); then
    :
elif command -v dpkg >/dev/null && owner=$(dpkg -S "$real" 2>/dev/null); then
    :
else
    owner="unknown (no rpm or dpkg owner)"
fi
sum=$(sha256sum "$real" | awk '{ print $1 }')
prefix=${PREFIX:-dbus}
mkdir -p "$out_dir"

end_re='</interface>[[:space:]]*$'
status=0
for iface in "$@"; do
    full="org.a11y.atspi.$iface"
    start="<interface name=\"$full\">"
    # Exactly one string in the binary may start with the tag; strings -w keeps the spaces in it.
    matches=$(strings -w -n 16 "$real" | grep -F "$start" | grep -c '^<interface name=' || true)
    if [ "$matches" -ne 1 ]; then
        echo "$full: $matches strings start with the tag in $real; not guessing which" >&2
        status=1
        continue
    fi
    body=$(strings -w -n 16 "$real" | grep -F "$start" | grep '^<interface name=')
    # Some compiled strings keep the source file's trailing indentation after the closing tag.
    if ! [[ $body =~ $end_re ]]; then
        echo "$full: the string does not end with </interface>; not writing a partial copy" >&2
        status=1
        continue
    fi
    file="$out_dir/$prefix-$iface.xml"
    {
        printf '<!--\n'
        printf '  %s, verbatim as compiled into %s\n' "$full" "$real"
        printf '  (resolved from %s)\n' "$library"
        printf '  package: %s\n' "$owner"
        printf '  sha256 of the binary: %s\n' "$sum"
        printf '  host: %s; kernel: %s; read at (guest clock, UTC): %s\n' \
            "$(hostname)" "$(uname -r)" "$(LC_ALL=C date -u)"
        printf '  extracted by scripts/a11y/linux/extract-atspi-introspection.sh with\n'
        printf '  strings -w -n 16 <binary> | grep -F %s\n' "'$start'"
        printf '  No newline exists inside the compiled string; the interface below is one line.\n'
        printf -- '-->\n'
        printf '%s\n' "$body"
    } > "$file"
    echo "$full -> $file ($(printf '%s' "$body" | wc -c) bytes)"
done
exit $status
