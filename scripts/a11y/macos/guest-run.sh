#!/bin/zsh
# Runs IN THE GUEST. Starts the probe in the CONSOLE session -- an ssh session is not one, and a
# window created there gets no window server (see the lab notes) -- waits for READY, then drives
# the clients.
#
# The provider needs `sudo launchctl asuser 501`; the CLIENTS deliberately do not get it. Root is
# accessibility-trusted whatever TCC says, so a client run as root proves nothing about the
# permission an ordinary application would need (ADR 039 §13.1).
#
# usage: guest-run.sh [seconds]
set -u
HERE=/Users/activities/limn-a11y7
JAVA=/Users/activities/jdk21/Contents/Home/bin/java
FATJAR=/Users/activities/limn-a11y/limn-demo-all.jar
SECS=${1:-120}
# Extra -D flags for the provider, e.g. -Dprobe.mangle=true for the deliberate-failure run.
shift 2>/dev/null || true
EXTRA=("$@")
CMD=$HERE/axprobe.cmd
LOG=$HERE/probe.log

pkill -f 'AxProbe' 2>/dev/null
sleep 1
rm -f "$LOG"; : > "$CMD"

sudo launchctl asuser 501 "$JAVA" -XstartOnFirstThread \
    -Dprobe.seconds="$SECS" -Dprobe.selftest=true -Dprobe.commands="$CMD" "${EXTRA[@]}" \
    -cp "$FATJAR:$HERE/classes" AxProbe >"$LOG" 2>&1 &
LAUNCHER=$!

for i in {1..120}; do
    grep -q 'READY' "$LOG" 2>/dev/null && break
    sleep 0.5
done
if ! grep -q 'READY' "$LOG" 2>/dev/null; then
    echo "!!! provider never reported READY; log follows:"; cat "$LOG"; kill $LAUNCHER 2>/dev/null; exit 1
fi
PID=$(grep -m1 -o 'pid=[0-9]*' "$LOG" | head -1 | cut -d= -f2)
echo "=== provider READY, java pid=$PID ==="
cat "$LOG"

echo
echo "=== CLIENT axtree $PID 8 (unprivileged, uid 501) ==="
# The hit-test positions are the CENTRES of the two deepest elements, in the top-left screen space
# a client works in. §13.21 asks whether AppKit still hit-tests through nesting with no
# accessibilityHitTest: of ours, and only a position over a NESTED element can answer that.
# zsh does not word-split an unquoted expansion; ${=...} is how you ask it to.
HIT_POINTS=(${=HITS:-})
"$HERE/axtree" "$PID" 8 "${HIT_POINTS[@]}"

echo quit >> "$CMD"
sleep 2
echo
echo "=== provider log after the client ran ==="
cat "$LOG"
wait $LAUNCHER 2>/dev/null
