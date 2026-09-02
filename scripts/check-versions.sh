#!/usr/bin/env bash
#
# The documentation must not say which version the toolkit is.
#
# The version lives in versions.properties and reaches a reader two ways, neither of them a
# literal in a file: the site substitutes `{{version}}` at deploy time from the release it is
# documenting, and the READMEs — which GitHub renders raw, with no build between the file and
# the reader — write `x.y.z` and let the Maven Central badge at the top say the number. A literal
# would be a thing to edit at every release, and a release must not need a commit.
#
# Two checks, over every tracked .md, .astro, .ts, .mdx (the generated site collection excepted):
#
#   1. no coordinate of a module published from HERE carries a literal version — neither
#      io.github.limn-toolkit:<module>:1.2.3 nor <artifactId>limn-<module></artifactId> beside a
#      literal <version>;
#   2. every coordinate of an artifact that versions on its OWN cadence (the fonts, the FFmpeg
#      payload, the icon pack — literals by design, they are pins) says what the version catalog
#      says, so a catalog bump cannot leave a guide naming yesterday's payload.
#
# The publish workflow's verify job runs this before anything is built; so does tag-releases.
set -euo pipefail
cd "$(dirname "$0")/.."

GROUP="io.github.limn-toolkit"
# The modules whose version is the toolkit's. Spelled out rather than discovered from settings,
# because the two aggregator POMs are the point: they version with the toolkit too.
OWN="limn-toolkit|limn-backend-lwjgl|limn-video-ffmpeg|limn-theme-editor|limn-video-ffmpeg-natives-all|limn-fonts-all"

# The pinned families, and the catalog entry each must agree with.
pin_of() {
  case "$1" in
    limn-fonts-roboto)        echo limn-fonts-roboto ;;
    limn-fonts-noto-cjk)      echo limn-fonts-noto-cjk ;;
    limn-fonts-noto-emoji)    echo limn-fonts-noto-emoji ;;
    limn-fonts-noto-scripts)  echo limn-fonts-noto-scripts ;;
    limn-ffmpeg-natives)      echo limn-ffmpeg-natives ;;
    limn-icons-tabler)        echo limn-icons-tabler ;;
    *) echo "" ;;
  esac
}
catalog() {
  sed -n "s/^$1 = \"\\(.*\\)\"\$/\\1/p" gradle/libs.versions.toml | head -1
}

status=0
while IFS= read -r file; do
  [ -f "$file" ] || continue
  # 1. Literal toolkit versions.
  if grep -nE "$GROUP:($OWN):[0-9]+\.[0-9]+\.[0-9]+" "$file" >/dev/null; then
    echo "✗ $file names a literal toolkit version (use x.y.z in a README, {{version}} in a guide):" >&2
    grep -nE "$GROUP:($OWN):[0-9]+\.[0-9]+\.[0-9]+" "$file" | sed 's/^/    /' >&2
    status=1
  fi
  if perl -0777 -ne "exit 1 unless /<artifactId>($OWN)<\/artifactId>\s*<version>[0-9]+\.[0-9]+\.[0-9]+/" "$file"; then
    echo "✗ $file names a literal toolkit version in a Maven <version> (use x.y.z or {{version}})" >&2
    status=1
  fi
  # 2. Pins must agree with the catalog.
  while IFS= read -r hit; do
    [ -n "$hit" ] || continue
    artifact="${hit#"$GROUP":}"; artifact="${artifact%%:*}"
    version="${hit#"$GROUP":"$artifact":}"; version="${version%%:*}"
    entry="$(pin_of "$artifact")"
    [ -n "$entry" ] || continue
    expected="$(catalog "$entry")"
    if [ "$version" != "$expected" ]; then
      echo "✗ $file names $artifact:$version but gradle/libs.versions.toml pins $expected" >&2
      status=1
    fi
  done < <(grep -oE "$GROUP:limn-(fonts-[a-z-]+|ffmpeg-natives|icons-tabler):[0-9][0-9.]*[0-9]" "$file" | sort -u)
done < <(git ls-files -- '*.md' '*.astro' '*.ts' '*.mdx' | grep -v '^site/src/content/docs/')

if [ "$status" -ne 0 ]; then
  echo "::error::the documentation carries a version it must not, or a pin the catalog disagrees with (see above)" >&2
  exit 1
fi
echo "the documentation names no toolkit version, and every pin agrees with the catalog"
