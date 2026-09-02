#!/usr/bin/env bash
#
# The version this repository's documentation tells a reader to depend on.
#
# The published version comes from the tag and from nowhere else (see .github/workflows/
# publish.yml). The documentation cannot read the tag: ten READMEs are rendered raw by GitHub with
# no build step between the file and the reader, so the coordinates in them are literals, and a
# literal is a thing that gets forgotten. It was forgotten once already — the READMEs described
# 0.3.0's shape while carrying 0.2.0's number, and `limn-video-ffmpeg:0.2.0:natives-macos-aarch64`
# was a coordinate that had never existed, so copying it produced a resolution failure rather than
# an old library.
#
# Two modes, one implementation, and that is the point of the file rather than a convenience:
#
#   set-version.sh 0.4.0            rewrites every coordinate to 0.4.0
#   set-version.sh --check 0.4.0    writes nothing, exits non-zero if any disagrees
#
# The publish workflow's `verify` job runs --check before the six native builds start, so a
# forgotten bump costs twenty seconds and not a release. A script that could rewrite but not
# verify would be a faster way to forget; two programs that did one each would drift.
#
# What it rewrites, in every tracked .md, .astro, .ts and .mdx:
#
#   * a Gradle coordinate, io.github.limn-toolkit:<artifact>:<version>, whatever the artifact
#   * the <version> of a Maven <dependency> whose <artifactId> starts with limn-
#
# The files are DISCOVERED rather than listed. A list would be the same thing being forgotten one
# level up: the day a guide gains a coordinate, a list stops covering it and says nothing.
# site/src/content/docs/ is skipped because sync-docs.mjs writes it from src/guides/ and it is
# gitignored; rewriting it there would last until the next `pnpm sync:docs`.
set -euo pipefail

GROUP="io.github.limn-toolkit"

usage() {
    cat >&2 <<'USAGE'
usage: scripts/set-version.sh [--check] <version>

  <version>   a release version: 1.2.3, or 1.2.3-rc1 (also -alpha1, -beta1)
  --check     report disagreements and exit 1; change nothing
USAGE
}

MODE="set"
if [ "${1:-}" = "--check" ]; then
    MODE="check"
    shift
fi

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
    usage
    exit 2
fi

# The same expression the publish workflow accepts, so this cannot pass something that would be
# rejected at the tag, or reject something the tag allows.
if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|rc)[0-9]+)?$'; then
    echo "'$VERSION' is not a release version (expected 1.2.3, or 1.2.3-rc1)" >&2
    exit 2
fi

cd "$(dirname "$0")/.."

rewrite() {
    GROUP="$GROUP" VERSION="$VERSION" perl -0777 -pe '
        my $group = quotemeta($ENV{GROUP});
        my $version = $ENV{VERSION};
        my $semver = qr/\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)\d+)?/;
        # Artifacts that version on their OWN cadence rather than the toolkit one: the fonts
        # with the font (ADR 036), the FFmpeg payload with FFmpeg (ADR 037). A doc naming
        # limn-ffmpeg-natives:7.1.5.0 is naming FFmpeg 7.1.5, and stamping the toolkit version
        # over it would fabricate a coordinate that never existed. The two aggregator POMs
        # (limn-fonts-all, limn-video-ffmpeg-natives-all) are NOT exempt: they are published
        # from here and version with the toolkit. (No apostrophes in here: this whole program
        # is one single-quoted bash string.)
        my $own_cadence = qr/limn-fonts-(?:roboto|noto-[A-Za-z-]+)|limn-ffmpeg-natives/;
        s{($group:(?!$own_cadence:)[A-Za-z0-9._-]+:)$semver}{$1$version}g;
        s{(<artifactId>limn-(?!fonts-(?:roboto|noto-)|ffmpeg-natives<)[A-Za-z0-9._-]+</artifactId>\s*<version>)[^<]*(</version>)}
         {$1$version$2}gsx;
    ' "$1"
}

# Best-effort file:line for the report. The authority on whether a file is wrong is whether
# rewriting changes it; this only says where to look, and the Maven case spans two lines, so a
# bare <version> line is enough to name.
offending_lines() {
    GROUP="$GROUP" VERSION="$VERSION" perl -ne '
        my $group = quotemeta($ENV{GROUP});
        my $version = $ENV{VERSION};
        my $semver = qr/\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)\d+)?/;
        my $own_cadence = qr/limn-fonts-(?:roboto|noto-[A-Za-z-]+)|limn-ffmpeg-natives/;
        if (/$group:(?!$own_cadence:)[A-Za-z0-9._-]+:($semver)/ && $1 ne $version) {
            s/^\s+//; print "  $ARGV:$.: $_";
        } elsif (m{<version>($semver)</version>} && $1 ne $version) {
            s/^\s+//; print "  $ARGV:$.: $_";
        }
    ' "$1"
}

changed_files=0
changed_coordinates=0

while IFS= read -r file; do
    [ -f "$file" ] || continue
    updated="$(rewrite "$file")"
    if [ "$updated" = "$(cat "$file")" ]; then
        continue
    fi
    changed_files=$((changed_files + 1))
    here="$(offending_lines "$file" | wc -l | tr -d ' ')"
    changed_coordinates=$((changed_coordinates + here))
    if [ "$MODE" = "check" ]; then
        offending_lines "$file"
    else
        printf '%s\n' "$updated" > "$file"
        echo "  $file"
    fi
done < <(git ls-files -- '*.md' '*.astro' '*.ts' '*.mdx' | grep -v '^site/src/content/docs/')

if [ "$MODE" = "check" ]; then
    if [ "$changed_files" -eq 0 ]; then
        echo "the documentation says $VERSION"
        exit 0
    fi
    echo "::error::the documentation does not say $VERSION: $changed_coordinates coordinate(s) in $changed_files file(s) disagree. Run scripts/set-version.sh $VERSION and commit the result." >&2
    exit 1
fi

if [ "$changed_files" -eq 0 ]; then
    echo "nothing to do: the documentation already says $VERSION"
else
    echo "set $VERSION in $changed_coordinates coordinate(s) across $changed_files file(s)"
fi
