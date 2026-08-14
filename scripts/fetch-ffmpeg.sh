#!/usr/bin/env bash
#
# Fetches the FFmpeg decoder's native payload (the `player` profile, all six desktop slices)
# into the directory the jar packages from. This is the path for a developer who wants MP4
# playback locally without spending an hour on scripts/build-ffmpeg.sh.
#
# The binaries are NOT in this repository, deliberately: nothing that this project did not write
# lives in git. They are produced by the release build, published as a release asset, and
# verified here against a digest pinned below, the same discipline scripts/fetch-fonts.sh uses,
# and for the same reason. These libraries are mapped into the process by stb's neighbour, the
# JVM's own loader; "whatever that URL serves today" is not a good enough answer for that.
#
# Nothing else needs this. Without a payload the decoder reports itself unavailable, its tests
# skip the way the GL-backed ones do, and every other part of the toolkit is unaffected.
#
# Usage:  ./scripts/fetch-ffmpeg.sh [--force]
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/limn-video-ffmpeg/native/dist/player"
FORCE="${1:-}"

# The release the payload comes from, and the digest of that release's archive.
#
# Both are empty until the first release exists. That is a state this script refuses to guess its
# way out of: pointing at "latest" would make two developers' checkouts differ with nothing to
# show for it, and skipping the digest would leave the verification to whoever controls a URL.
RELEASE=""
ARCHIVE_SHA256=""

ARCHIVE="limn-video-ffmpeg-natives-player.tar.gz"

if [[ -z "$RELEASE" || -z "$ARCHIVE_SHA256" ]]; then
  cat >&2 <<'MESSAGE'
fetch-ffmpeg: no release is pinned yet.

  The payload comes from the release build, and no release has been published from this
  repository. Until one exists there are two ways to get a working decoder:

    ./scripts/build-ffmpeg.sh          build it here (needs a C toolchain; takes a while)
    (nothing)                          leave it out; the decoder reports itself unavailable,
                                       its tests skip, and everything else works

  When the first release lands, pin it: set RELEASE and ARCHIVE_SHA256 at the top of this file
  to that release's tag and the digest of its archive, in the same commit.
MESSAGE
  exit 1
fi

digest_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    sha256sum "$1" | cut -d' ' -f1
  fi
}

if [[ -d "$DEST" && "$FORCE" != "--force" ]]; then
  echo "✓ a payload is already present in ${DEST#"$ROOT/"}; pass --force to replace it"
  exit 0
fi

URL="https://github.com/${REPO_SLUG:-limn-toolkit/limn-toolkit}/releases/download/$RELEASE/$ARCHIVE"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "↓ $ARCHIVE ($RELEASE)"
curl -fSL --retry 3 --max-time 600 -o "$TMP/$ARCHIVE" "$URL"

actual="$(digest_of "$TMP/$ARCHIVE")"
if [[ "$actual" != "$ARCHIVE_SHA256" ]]; then
  echo "✗ $ARCHIVE is not what the pin says it is" >&2
  echo "  expected $ARCHIVE_SHA256" >&2
  echo "  got      $actual" >&2
  exit 1
fi

# Unpacked beside the target and moved in only once it verifies, so a bad download cannot be left
# where Gradle would package it.
mkdir -p "$TMP/unpacked"
tar -xzf "$TMP/$ARCHIVE" -C "$TMP/unpacked"
rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
mv "$TMP/unpacked" "$DEST"
echo "  installed into ${DEST#"$ROOT/"}"
echo
echo "Rebuild to pick it up:  ./gradlew :limn-demo:run"
