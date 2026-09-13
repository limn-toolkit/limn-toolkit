#!/bin/bash
# Runs IN THE FEDORA GUEST. ADR 044 §4's live run on Linux: Orca listening to the demo's tree while
# the demo drives its own arrows (`--scene tree-reader`), with libatspi snapshots taken at the
# steps that matter and Orca's own record of what it spoke.
#
# The snapshots wait for the step line the demo prints rather than sleeping a fixed time, because
# a JVM's start-up on this guest varies by seconds and a snapshot one step early reads the wrong
# row with complete confidence.
#
# Expects ~/limn-a11y/limn-demo-all.jar and ~/limn-a11y/tree-check.py beside this script.
# Leaves nothing running: the demo exits on its own and Orca is stopped if this started it.
#
# usage: run-tree-reader.sh
set -u
HERE=~/limn-a11y
LOG=$HERE/tree-reader.log
SPOKE=/tmp/orca-tree.txt

export XDG_RUNTIME_DIR=/run/user/1000 DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus
export DISPLAY=:0 WAYLAND_DISPLAY=wayland-0
export XAUTHORITY=$(tr "\0" "\n" < /proc/$(pgrep -x plasmashell | head -1)/environ \
    | sed -n "s/^XAUTHORITY=//p")
cd ~

HAD_ORCA=$(pgrep -x orca >/dev/null && echo yes || echo no)
rm -f "$SPOKE" "$LOG"
nohup orca --replace --debug-file="$SPOKE" >/tmp/orca-stdout.txt 2>&1 &
sleep 6

nohup java --enable-native-access=ALL-UNNAMED -jar "$HERE/limn-demo-all.jar" \
    --scene tree-reader --exit-after 62000 >"$LOG" 2>&1 &

waitstep() {
    for _ in $(seq 1 160); do
        grep -q -- "--- step $1 " "$LOG" 2>/dev/null && return 0
        sleep 0.5
    done
    echo "step $1 never printed"
    return 1
}
snap() { python3 "$HERE/tree-check.py" "$1" 2>&1 | grep -v -i warning; }

waitstep 1 && sleep 1 && snap "after step 1 DOWN: lands on Documents"
waitstep 3 && sleep 1 && snap "after step 3 LEFT: Reports closed"
waitstep 4 && sleep 1 && snap "after step 4 RIGHT: Reports open again"
waitstep 14 && sleep 2 && snap "after step 14 RIGHT: Remote opened and loaded"
waitstep 15
sleep 4

echo "=== what the demo did ==="
grep -E '^--- step|Exception|ERROR' "$LOG" | head -20
echo "=== what Orca spoke ==="
grep -a 'SPEECH OUTPUT' "$SPOKE" | head -100

for p in $(pgrep -f limn-demo-all.jar); do
    [ "$(cat /proc/$p/comm 2>/dev/null)" = java ] && kill "$p"
done
[ "$HAD_ORCA" = no ] && pkill -x orca
echo "=== strays ==="
pgrep -a -f limn-demo-all.jar | grep -v pgrep || echo none
