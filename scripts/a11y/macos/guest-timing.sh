#!/bin/zsh
# Runs IN THE GUEST. ADR 039 §13.19, the cost half on macOS: what one NSAccessibilityPostNotification
# costs. Starts the hand-built provider (AxProbe) in the console session with -Dprobe.timing=true,
# which posts N timed notifications a few seconds after READY -- late enough for a reader that
# attaches when the window comes to the front to be attached -- then quits it.
#
# Run it with VoiceOver on for the number the record wants, and once more with VoiceOver off for
# the contrast: a post with nobody registered is not the same cost.
#
# usage: guest-timing.sh [N] [-D... for the provider]
set -u
HERE=/Users/activities/limn-a11y7
JAVA=/Users/activities/jdk21/Contents/Home/bin/java
FATJAR=/Users/activities/limn-a11y/limn-demo-all.jar
N=${1:-10000}
shift 2>/dev/null || true
EXTRA=("$@")
CMD=$HERE/axprobe.cmd
LOG=$HERE/timing.log

pkill -f 'AxProbe' 2>/dev/null
sleep 1
rm -f "$LOG"; : > "$CMD"

sudo launchctl asuser 501 "$JAVA" -XstartOnFirstThread \
    -Dprobe.seconds=120 -Dprobe.commands="$CMD" -Dprobe.timing=true -Dprobe.timingN="$N" \
    "${EXTRA[@]}" -cp "$FATJAR:$HERE/classes" AxProbe >"$LOG" 2>&1 &
LAUNCHER=$!

for i in {1..120}; do
    grep -q 'READY' "$LOG" 2>/dev/null && break
    sleep 0.5
done
if ! grep -q 'READY' "$LOG" 2>/dev/null; then
    echo "!!! provider never reported READY; log follows:"; cat "$LOG"; kill $LAUNCHER 2>/dev/null; exit 1
fi
PID=$(grep -m1 -o 'pid=[0-9]*' "$LOG" | head -1 | cut -d= -f2)
echo "=== provider READY, java pid=$PID; waiting for the timing ==="

for i in {1..240}; do
    grep -q 'post timing done' "$LOG" 2>/dev/null && break
    sleep 0.5
done
echo quit >> "$CMD"
sleep 2
grep -E 'post timing|post ns' "$LOG"
wait $LAUNCHER 2>/dev/null
