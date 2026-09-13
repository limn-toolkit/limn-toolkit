#!/bin/zsh
# Runs IN THE macOS GUEST. ADR 044 §4's live run on macOS: VoiceOver listening to the demo's tree
# while the demo drives its own arrows (`--scene tree-reader`), with axoutline snapshots at the
# steps that matter and VoiceOver's caption panel photographed on a timer, which is the only place
# its speech can be read (see guest-voiceover.sh for why vocap and not screencapture).
#
# VoiceOver must already be running and past its Quick Start dialog, and the guest unlocked.
# Expects limn-demo-all.jar, axoutline, vocap and vomontage beside this script.
#
# usage: guest-tree-reader.sh [captures] [seconds-between]
set -u
HERE=/Users/activities/limn-a11y7
JAVA=/Users/activities/jdk21/Contents/Home/bin/java
LOG=$HERE/tree-reader.log
SHOTS=${1:-30}
GAP=${2:-2}

rm -f "$LOG" "$HERE"/tvo-[0-9]*.png
sudo launchctl asuser 501 "$JAVA" -XstartOnFirstThread -jar "$HERE/limn-demo-all.jar" \
    --scene tree-reader --exit-after 62000 >"$LOG" 2>&1 &

# The launchctl wrapper shares the java command line, so the pid is the one whose command is java.
PID=""
for _ in {1..60}; do
    PID=$(ps -axo pid=,comm= | awk '$2 ~ /java$/ {print $1}' | while read p; do
        ps -o args= -p "$p" | grep -q 'tree-reader' && echo "$p"; done | head -1)
    [ -n "$PID" ] && break
    sleep 0.5
done
echo "=== demo pid=${PID:-none} ==="

waitstep() {
    for _ in {1..240}; do
        grep -q -- "--- step $1 " "$LOG" 2>/dev/null && return 0
        sleep 0.5
    done
    echo "step $1 never printed"
    return 1
}

# The captions run on their own timer for the whole sequence, in the background of the snapshots.
(
    for i in $(seq -w 1 "$SHOTS"); do
        sudo launchctl asuser 501 sudo -u activities "$HERE/vocap" VoiceOver 80 200 \
            "$HERE/tvo-$i.png" >/dev/null 2>&1
        sleep "$GAP"
    done
) &
CAPS=$!

waitstep 1 && sleep 1 && "$HERE/axoutline" "$PID" "after step 1 DOWN: lands on Documents"
waitstep 3 && sleep 1 && "$HERE/axoutline" "$PID" "after step 3 LEFT: Reports closed"
waitstep 4 && sleep 1 && "$HERE/axoutline" "$PID" "after step 4 RIGHT: Reports open again"
waitstep 14 && sleep 2 && "$HERE/axoutline" "$PID" "after step 14 RIGHT: Remote opened and loaded"
waitstep 15
wait $CAPS

sudo launchctl asuser 501 sudo -u activities "$HERE/vomontage" "$HERE/tvo-strip.png" \
    "$HERE"/tvo-[0-9]*.png
echo "=== what the demo did ==="
grep -E '^--- step|Exception|ERROR' "$LOG" | head -20
[ -n "$PID" ] && sudo kill -9 "$PID" 2>/dev/null
echo "=== strays ==="
ps -axo pid=,args= | grep 'tree-reader' | grep -v grep || echo none
