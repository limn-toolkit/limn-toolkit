#!/bin/zsh
# Runs IN THE macOS GUEST. The M1 correction 5 reading: starts selector-allowed-probe's provider in
# the GUI session as the logged-in user (not root: root is AX-trusted whatever TCC says, and the
# provider's side does not need it), waits for its own pid line, lets it stand for a quiet spell so
# asks nobody of ours made are visible as such, runs the reader unprivileged, and kills the provider
# by that pid. Both logs are appended to by one writer each, with a marker between the phases.
#
# Expects selector-allowed-probe beside this script, already built.
#
# usage: selector-allowed-run.sh [quiet-seconds] [refused-selector ...]
set -u
HERE=${0:A:h}
QUIET=${1:-3}
shift $(( $# > 0 ? 1 : 0 ))
REFUSED=("$@")
(( ${#REFUSED} == 0 )) && REFUSED=(accessibilityDisclosureLevel)
SERVE=$HERE/selector-allowed-serve.log
READ=$HERE/selector-allowed-read.log

rm -f "$SERVE" "$READ"
# >> on both sides: the provider and the markers below write the same file, and O_APPEND is what
# keeps one from overwriting the other.
sudo launchctl asuser "$(id -u)" sudo -u "$(id -un)" "$HERE/selector-allowed-probe" serve 90 \
    "${REFUSED[@]}" >>"$SERVE" 2>&1 &

PID=""
for _ in {1..60}; do
    PID=$(grep -o 'serving pid=[0-9]*' "$SERVE" 2>/dev/null | head -1 | cut -d= -f2)
    [ -n "$PID" ] && break
    sleep 0.5
done
echo "=== provider pid=${PID:-none} ==="
[ -z "$PID" ] && { cat "$SERVE"; exit 1; }

sleep "$QUIET"
echo "=== $(date -u +%H:%M:%S)Z quiet spell over; reader starts ===" >>"$SERVE"
"$HERE/selector-allowed-probe" read "$PID" AXDisclosureLevel AXDisclosing AXRole >>"$READ" 2>&1
echo "=== $(date -u +%H:%M:%S)Z reader done ===" >>"$SERVE"
sleep 1

kill "$PID" 2>/dev/null
sleep 1
ps -p "$PID" >/dev/null 2>&1 && { echo "provider still up; SIGKILL"; kill -9 "$PID"; }
echo "=== provider log ==="; cat "$SERVE"
echo "=== reader log ==="; cat "$READ"
echo "=== strays ==="
ps -axo pid=,args= | grep 'selector-allowed-probe' | grep -v grep || echo none
