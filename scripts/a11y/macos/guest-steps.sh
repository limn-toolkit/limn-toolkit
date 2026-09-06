#!/bin/zsh
# Runs IN THE GUEST. One provider, several client walks with commands driven between them -- which
# is the only way to see a MUTATION (§13.21): a tree that is walked once tells you nothing about a
# tree that changes, and restarting the provider between walks would change the elements too.
#
# The provider goes into the console session through `sudo launchctl asuser 501`; the clients
# deliberately do not, because root is accessibility-trusted whatever TCC says (§13.1).
#
# usage: STEPS="walk|cmd:add root extra|walk" guest-steps.sh [seconds] [-D... for the provider]
set -u
HERE=/Users/activities/limn-a11y7
JAVA=/Users/activities/jdk21/Contents/Home/bin/java
FATJAR=/Users/activities/limn-a11y/limn-demo-all.jar
SECS=${1:-90}
shift 2>/dev/null || true
EXTRA=("$@")
CMD=$HERE/axprobe.cmd
LOG=$HERE/probe.log

pkill -f 'AxProbe' 2>/dev/null
sleep 1
rm -f "$LOG"; : > "$CMD"

sudo launchctl asuser 501 "$JAVA" -XstartOnFirstThread \
    -Dprobe.seconds="$SECS" -Dprobe.commands="$CMD" "${EXTRA[@]}" \
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

STEP_LIST=(${(s:|:)STEPS})
n=0
for step in $STEP_LIST; do
    n=$((n + 1))
    case "$step" in
        walk*)
            echo
            echo "--- step $n: walk ---"
            # Only our own subtree is interesting; the client prunes AppKit's menu bar itself, and
            # the window's own buttons are AppKit's too.
            "$HERE/axtree" "$PID" 8 | grep -E "id='|deepest"
            ;;
        hit:*)
            echo
            echo "--- step $n: ${step} ---"
            HIT_POINTS=(${=step#hit:})
            "$HERE/axtree" "$PID" 0 "${HIT_POINTS[@]}" | grep -E '^hitTest'
            ;;
        life:*)
            echo
            echo "--- step $n: ${step} ---"
            # The client writes the destroy command itself, so the observer is demonstrably
            # registered before the destruction rather than probably.
            LIFE_ARGS=(${=step#life:})
            "$HERE/axlife" "$PID" "${LIFE_ARGS[1]}" "$CMD" 8 "${LIFE_ARGS[2]:-both}"
            ;;
        cmd:*)
            echo
            echo "--- step $n: ${step#cmd:} ---"
            echo "${step#cmd:}" >> "$CMD"
            sleep 1.5
            ;;
        *) echo "!!! unknown step: $step" ;;
    esac
done

echo quit >> "$CMD"
sleep 2
echo
echo "=== provider log ==="
cat "$LOG"
wait $LAUNCHER 2>/dev/null
