#!/bin/zsh
# Runs IN THE GUEST. The phase 7 exit criterion: VoiceOver reading the demo.
#
# It starts the rendered probe in the console session, then photographs VoiceOver's caption panel
# on a timer and stacks the distinct panels into one strip. The panel is the only way to read what
# VoiceOver says -- its process is not accessibility-inspectable and its speech is not in the
# unified log -- and vocap exists rather than /usr/sbin/screencapture because screencapture raises
# a consent dialog on every invocation, and a dialog takes the foreground, which is precisely what
# a screen reader announces.
#
# VoiceOver must already be running and past its Quick Start dialog.
#
# usage: guest-voiceover.sh [captures] [seconds-between] [-D... for the probe]
set -u
HERE=/Users/activities/limn-a11y7
SHOTS=${1:-18}
GAP=${2:-2}
shift 2 2>/dev/null || true
EXTRA=("$@")
LOG=$HERE/liveprobe.log

pkill -f 'limn-a11y-macos-probe.jar' 2>/dev/null
rm -f "$HERE"/vo-*.png
sleep 1

sudo launchctl asuser 501 /Users/activities/jdk21/Contents/Home/bin/java -XstartOnFirstThread \
    "${EXTRA[@]}" -jar "$HERE/limn-a11y-macos-probe.jar" >"$LOG" 2>&1 &

for i in {1..60}; do
    grep -q 'bridge:' "$LOG" 2>/dev/null && break
    sleep 1
done
PID=$(grep -m1 '^pid=' "$LOG" | cut -d= -f2)
echo "=== probe pid=${PID:-none} ==="

for i in $(seq -w 1 "$SHOTS"); do
    sudo launchctl asuser 501 sudo -u activities "$HERE/vocap" VoiceOver 80 200 "$HERE/vo-$i.png" \
        >/dev/null 2>&1
    sleep "$GAP"
done

sudo launchctl asuser 501 sudo -u activities "$HERE/vomontage" "$HERE/vo-strip.png" $HERE/vo-[0-9]*.png
echo "=== what the bridge posted ==="
grep -E '^--- step|posted since|listening=' "$LOG" | tail -20
