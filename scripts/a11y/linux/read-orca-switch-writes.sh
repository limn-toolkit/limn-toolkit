#!/usr/bin/env bash
# Read, from the Orca installed on this machine, what Orca itself does to the desktop's accessibility
# switch (org.a11y.Status IsEnabled and ScreenReaderEnabled) when it starts and when it quits.
#
# Run it ON THE MACHINE; it starts nothing, reads no bus and needs no session. The Linux bridge follows
# IsEnabled (decision 29: "the bridge embeds and tears down with Orca"). Whether the bridge ever tears
# down when Orca quits therefore depends on something turning IsEnabled off, and Orca is the first
# candidate. This prints Orca's package and version, every line of its installed Python that names
# IsEnabled, ScreenReaderEnabled, org.a11y.Status, toolkit-accessibility or screen-reader-enabled, and
# the functions around the writes, plus the shutdown function, so a reader can see whether any path
# sets the switch false.
#
#     read-orca-switch-writes.sh
set -uo pipefail

say() { printf '%s\n' "$*"; }

say "==== machine"
say "date -u: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
say "uname: $(uname -srm)"
[ -r /etc/os-release ] && grep PRETTY_NAME /etc/os-release
if command -v rpm >/dev/null 2>&1; then
    say "\$ rpm -q orca at-spi2-core"; rpm -q orca at-spi2-core 2>&1
else
    say "\$ dpkg -l orca at-spi2-core | grep ^ii"; dpkg -l orca at-spi2-core 2>/dev/null | grep '^ii'
fi

dir=$(python3 -c 'import orca, os; print(os.path.dirname(orca.__file__))' 2>/dev/null)
if [ -z "$dir" ]; then
    say "orca is not importable by python3 here: nothing to read"
    exit 0
fi
say "orca package directory: $dir"

say ""
say "==== every line naming the switch, its properties or its settings keys"
grep -rn --include='*.py' -E 'IsEnabled|ScreenReaderEnabled|org\.a11y\.Status|toolkit-accessibility|screen-reader-enabled' "$dir"

say ""
say "==== every call that sets a property on org.a11y.Status, with the argument it passes"
grep -rn --include='*.py' -E '\.Set\(.*org\.a11y\.Status' "$dir"
say "---- callers of the settings manager's setAccessibility (older Orca), if any"
grep -rn --include='*.py' -E 'setAccessibility\(' "$dir"

say ""
say "==== the shutdown function(s)"
for f in $(grep -rln --include='*.py' -E '^def shutdown' "$dir"); do
    start=$(grep -n -E '^def shutdown' "$f" | head -1 | cut -d: -f1)
    say "---- $f from line $start"
    awk -v s="$start" 'NR>=s { if (NR>s && /^def /) exit; print NR": "$0 }' "$f"
done
