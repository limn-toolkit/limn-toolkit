#!/usr/bin/env bash
# Generates a screenshot of the Limn UI demo without interaction.
#
# Usage: scripts/screenshot.sh [output.png] [--scene <name>] [--scale <factor>]
#
# On Linux without a display (CI), uses xvfb-run + Mesa/llvmpipe (software rendering).
set -euo pipefail

cd "$(dirname "$0")/.."

# The first argument is the output PNG only if it is not an option (--scene etc.).
OUT="build/screenshots/limn.png"
if [[ $# -gt 0 && "$1" != --* ]]; then
    OUT="$1"
    shift
fi

ARGS="--screenshot \"$OUT\" $*"

if [[ "$(uname)" == "Linux" && -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
    export LIBGL_ALWAYS_SOFTWARE=1
    if ! command -v xvfb-run >/dev/null; then
        echo "error: no display and xvfb-run not installed (apt install xvfb)" >&2
        exit 1
    fi
    exec xvfb-run -a ./gradlew --console=plain :limn-demo:run --args="$ARGS"
fi

exec ./gradlew --console=plain :limn-demo:run --args="$ARGS"
