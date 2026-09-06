#!/bin/zsh
# Runs IN THE GUEST. Starts LiveProbe -- the RENDERED probe, real widgets painted by the backend --
# in the console session, and leaves it up so a person can look at it and a client can read it.
#
# The console session is not the ssh session: a window created over ssh gets no window server, so
# the provider goes through `sudo launchctl asuser 501`. Clients are run without it on purpose,
# because root is accessibility-trusted whatever TCC says (ADR 039 §13.1).
#
# usage: guest-probe.sh [seconds-to-wait-for-READY]   (the probe closes itself after ~24 steps)
set -u
HERE=/Users/activities/limn-a11y7
JAVA=/Users/activities/jdk21/Contents/Home/bin/java
JAR=$HERE/limn-a11y-macos-probe.jar
LOG=$HERE/liveprobe.log

pkill -f 'limn-a11y-macos-probe.jar' 2>/dev/null
sleep 1
rm -f "$LOG"

# -XstartOnFirstThread is not optional: AppKit opens a window from the main thread or from nowhere.
sudo launchctl asuser 501 "$JAVA" -XstartOnFirstThread -jar "$JAR" >"$LOG" 2>&1 &
LAUNCHER=$!

for i in {1..${1:-90}}; do
    grep -q 'bridge:' "$LOG" 2>/dev/null && break
    sleep 1
done
PID=$(pgrep -f 'limn-a11y-macos-probe.jar' | head -1)
echo "=== LiveProbe pid=${PID:-none} ==="
cat "$LOG"
echo "(still running; log at $LOG)"
