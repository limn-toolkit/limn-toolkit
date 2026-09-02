#!/usr/bin/env bash
#
# Fetches the demo's display faces — the only fonts this repository still carries but does not
# author. The UI faces (Roboto, the Noto fallbacks) moved to the limn-toolkit/limn-fonts
# repository, where each is a Maven artifact versioning with the font (ADR 036); their pins and
# this same fetch-and-verify mechanism moved with them.
#
# Everything here lands under limn-demo/, which is published as no library: a face that exists
# to make one screenshot must not add a byte to what an application ships. A checkout has them
# already; this is how they were first obtained and how a pin bump refreshes them.
#
# Each font is pinned to an upstream commit and verified against the SHA-256 below before it
# lands. What ships is then the same bytes on every machine and every day. These fonts are also
# parsed by stb, which is C, so "whatever the branch serves today" is not a good enough answer
# for what gets mapped into the process. Moving a pin means changing the commit and the digest
# together and running `--scene fonts`.
#
# Usage:  ./scripts/fetch-fonts.sh [--force]
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO_FONTS="limn-demo/fonts"
FORCE="${1:-}"

# name | destination path, relative to the repository root | URL (pinned commit) | SHA-256
FONTS=(
  "Comic Neue Regular (demo only: the site mosaic's illustrative tile)|$DEMO_FONTS/ComicNeue-Regular.ttf|https://raw.githubusercontent.com/crozynski/comicneue/ef5be72411141d01f0b865df8edb47e552c11c3c/Fonts/TTF/ComicNeue/ComicNeue-Regular.ttf|a0ee5a37c8b27c4db0700137d928598b1e23b0089e1546a8961909176b779360"
  "Comic Neue Bold (demo only)|$DEMO_FONTS/ComicNeue-Bold.ttf|https://raw.githubusercontent.com/crozynski/comicneue/ef5be72411141d01f0b865df8edb47e552c11c3c/Fonts/TTF/ComicNeue/ComicNeue-Bold.ttf|3e7e5fccfd7e0788f317b43312151c1bd5cf058c9697a8d83eac3939050bd61e"
  "Comic Neue licence (SIL OFL 1.1)|$DEMO_FONTS/ComicNeue-LICENSE.txt|https://raw.githubusercontent.com/crozynski/comicneue/ef5be72411141d01f0b865df8edb47e552c11c3c/OFL.txt|7c38a22e5878e60fe423360553e63dd7be23d29f1f60336034935dbfc96e8320"
  # The mosaic's other faces, from google/fonts at one pinned commit. Inter is published there
  # only as a variable font: stb applies no variations, so what renders is the file's default
  # instance, which for this one is Regular. That is the face the mosaic wants, but it is a
  # property of the file rather than a choice this script can make: replacing the pin with a
  # build whose default instance is another weight changes the tile with nothing else to show it.
  "Inter Regular, from the variable font (demo only)|$DEMO_FONTS/Inter-Variable.ttf|https://raw.githubusercontent.com/google/fonts/038b637da7b3fd956a4ed93ffc607c3d5e4ce172/ofl/inter/Inter%5Bopsz,wght%5D.ttf|29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031"
  "Inter licence (SIL OFL 1.1)|$DEMO_FONTS/Inter-LICENSE.txt|https://raw.githubusercontent.com/google/fonts/038b637da7b3fd956a4ed93ffc607c3d5e4ce172/ofl/inter/OFL.txt|5b9321a4298cfeb6b34354164a1c3afc3db114569984c502b9b35d988fd58c57"
  "Silkscreen Regular, a pixel face (demo only)|$DEMO_FONTS/Silkscreen-Regular.ttf|https://raw.githubusercontent.com/google/fonts/038b637da7b3fd956a4ed93ffc607c3d5e4ce172/ofl/silkscreen/Silkscreen-Regular.ttf|c845473330b94c2079ce9af01c51ac8ba2d99c24f4d14c039843bbb8e642ebd8"
  "Silkscreen licence (SIL OFL 1.1)|$DEMO_FONTS/Silkscreen-LICENSE.txt|https://raw.githubusercontent.com/google/fonts/038b637da7b3fd956a4ed93ffc607c3d5e4ce172/ofl/silkscreen/OFL.txt|86c5e9c9382cdcc5948704fdfe60f2aa164a719746931219a42736ecd9cefbd3"
  "Archivo Black Regular, a heavy display face (demo only)|$DEMO_FONTS/ArchivoBlack-Regular.ttf|https://raw.githubusercontent.com/google/fonts/038b637da7b3fd956a4ed93ffc607c3d5e4ce172/ofl/archivoblack/ArchivoBlack-Regular.ttf|dd9a89a019b4849f66ab75455fe7bdf931311042cbb0f0f97acc061539703180"
  "Archivo Black licence (SIL OFL 1.1)|$DEMO_FONTS/ArchivoBlack-LICENSE.txt|https://raw.githubusercontent.com/google/fonts/038b637da7b3fd956a4ed93ffc607c3d5e4ce172/ofl/archivoblack/OFL.txt|3173acd82f8c6159b5b1037b539fcbd4edff68e65c2ea8b9412b5a5ca97b08ff"
)

digest_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    sha256sum "$1" | cut -d' ' -f1
  fi
}

status=0
for entry in "${FONTS[@]}"; do
  IFS='|' read -r name file url sha <<< "$entry"
  target="$ROOT/$file"
  mkdir -p "$(dirname "$target")"
  if [[ -f "$target" && "$FORCE" != "--force" ]]; then
    if [[ "$(digest_of "$target")" == "$sha" ]]; then
      echo "✓ $name already present ($file)"
      continue
    fi
    echo "✗ $file is present but is not the pinned build; pass --force to replace it" >&2
    status=1
    continue
  fi
  echo "↓ $name → $file"
  # Downloaded beside the target and moved in only once it verifies: a font that
  # fails the digest must not be left where FontStore would load it.
  curl -fSL --retry 3 --max-time 300 -o "$target.part" "$url"
  actual="$(digest_of "$target.part")"
  if [[ "$actual" != "$sha" ]]; then
    echo "✗ $file is not what the pin says it is" >&2
    echo "  expected $sha" >&2
    echo "  got      $actual" >&2
    rm -f "$target.part"
    status=1
    continue
  fi
  mv "$target.part" "$target"
  echo "  saved $(du -h "$target" | cut -f1)"
done
if [[ $status -ne 0 ]]; then
  echo "Some fonts were not fetched." >&2
  exit $status
fi
echo "Done. Rebuild the demo to pick them up: ./gradlew :limn-demo:run --args=\"--scene fonts\""
