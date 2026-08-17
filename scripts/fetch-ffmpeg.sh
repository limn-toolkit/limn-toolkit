#!/usr/bin/env bash
#
# Installs the FFmpeg decoder's native payload by taking it out of the PUBLISHED JAR on Maven
# Central. For a developer who wants MP4 playback locally without a C toolchain and without the
# minute scripts/build-ffmpeg.sh takes.
#
# Why the jar and not a release asset: the binaries exist in exactly one place by design — inside
# the artifact an application depends on. They are not in this repository, and the release builds
# them rather than storing them (.github/workflows/natives.yml), so the jar is the only
# distribution there is. Taking them from there also means what you run locally is byte-for-byte
# what a user of the toolkit gets, rather than a similar build from a different day.
#
# The jar's own SHA-1, published beside it by Central, is checked before anything is unpacked.
# These libraries are mapped into the process by the JVM's loader; "whatever that URL served" is
# not a good enough answer for that.
#
# Usage:  ./scripts/fetch-ffmpeg.sh [version] [--force]
#         version defaults to the latest release Central knows about.
#
# The alternative, and the only route to the `full` profile the writer tests need:
#         ./scripts/build-ffmpeg.sh --profile full
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/limn-video-ffmpeg/native/dist/player"
GROUP_PATH="io/github/limn-toolkit"
ARTIFACT="limn-video-ffmpeg"
BASE="https://repo1.maven.org/maven2/${GROUP_PATH}/${ARTIFACT}"

VERSION=""
FORCE=""
for argument in "$@"; do
  case "$argument" in
    --force) FORCE="--force" ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
    *) VERSION="$argument" ;;
  esac
done

if [[ -d "$DEST" && "$FORCE" != "--force" ]]; then
  echo "✓ a payload is already present in ${DEST#"$ROOT/"}; pass --force to replace it"
  exit 0
fi

need() {
  command -v "$1" >/dev/null 2>&1 || { echo "✗ this script needs $1 on PATH" >&2; exit 2; }
}
need curl
need unzip

# Central's own metadata rather than a number written here: a pin in this file is a pin that goes
# stale one release after somebody remembers to update it.
if [[ -z "$VERSION" ]]; then
  echo "· asking Central for the latest ${ARTIFACT}"
  METADATA="$(curl -fsSL --max-time 60 "${BASE}/maven-metadata.xml" || true)"
  VERSION="$(printf '%s' "$METADATA" | sed -n 's:.*<release>\(.*\)</release>.*:\1:p' | head -1)"
  if [[ -z "$VERSION" ]]; then
    cat >&2 <<'MESSAGE'
✗ no published release found on Maven Central.

  Nothing has been published under io.github.limn-toolkit:limn-video-ffmpeg yet, so there is no
  jar to take a payload out of. Until the first release exists, build it instead:

    ./scripts/build-ffmpeg.sh              the shipped decode-only library, about a minute
    ./scripts/build-ffmpeg.sh --profile full   + the encoders the writer tests need

  Or leave it out entirely: the decoder reports itself unavailable, its tests skip, and every
  other part of the toolkit works.
MESSAGE
    exit 1
  fi
fi

JAR="${ARTIFACT}-${VERSION}.jar"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "↓ ${JAR}"
curl -fSL --retry 3 --max-time 600 -o "$TMP/$JAR" "${BASE}/${VERSION}/${JAR}"

# Central publishes a digest beside every artifact; a jar that does not match it is not the one
# that was published, and unpacking it into a directory Gradle packages from would be the worst
# possible place to find that out.
EXPECTED="$(curl -fsSL --max-time 60 "${BASE}/${VERSION}/${JAR}.sha1" | tr -d '[:space:]')"
if command -v shasum >/dev/null 2>&1; then
  ACTUAL="$(shasum -a 1 "$TMP/$JAR" | cut -d' ' -f1)"
else
  ACTUAL="$(sha1sum "$TMP/$JAR" | cut -d' ' -f1)"
fi
if [[ -z "$EXPECTED" || "$ACTUAL" != "$EXPECTED" ]]; then
  echo "✗ ${JAR} does not match the digest Central publishes for it" >&2
  echo "  expected ${EXPECTED:-<none>}" >&2
  echo "  got      $ACTUAL" >&2
  exit 1
fi

# Unpacked beside the target and moved in only once it verifies, so a bad download cannot be left
# where Gradle would package it. Only the native tree is taken: the jar's classes come from the
# build, and copying them here would put two copies of the same code on the classpath.
mkdir -p "$TMP/unpacked"
# `|| true` because unzip exits 11 when its pattern matches nothing, which under `set -e` would
# end the script on the one failure that most deserves an explanation. The directory check below
# is what decides, and it gives that explanation.
unzip -q "$TMP/$JAR" 'limn/video/ffmpeg/native/*' -d "$TMP/unpacked" || true
if [[ ! -d "$TMP/unpacked/limn/video/ffmpeg/native" ]]; then
  echo "✗ ${JAR} carries no native payload — is this a version built before the natives shipped?" >&2
  exit 1
fi

rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
mv "$TMP/unpacked" "$DEST"

echo "  installed ${VERSION} into ${DEST#"$ROOT/"}"
ls "$DEST/limn/video/ffmpeg/native" | sed 's/^/    /'
echo
echo "Nothing here is committed: the tree ignores it. Rebuild to pick it up:"
echo "  ./gradlew :limn-demo:run"
