#!/usr/bin/env bash
#
# Fetches the font files this repository carries but does not author: the
# broad-coverage Noto fallbacks that back the CJK/emoji script fallback, the four
# Noto faces the complex scripts are shaped against, and the demo's own display
# face. This is how they are first obtained and how they are refreshed; a checkout
# has them already. Without any of them the toolkit still runs: Roboto covers
# Latin/Greek/Cyrillic and unknown glyphs render as .notdef boxes.
#
# Each font is pinned to an upstream commit and verified against the SHA-256
# below before it lands. What ships is then the same bytes on every machine and
# every day. These fonts are also parsed by stb, which is C, so "whatever the
# branch serves today" is not a good enough answer for what gets mapped into the
# process. Moving a pin means changing the commit and the digest together and
# running `--scene fonts`.
#
# Where each file lands is part of its entry, and the difference is deliberate.
# The Noto fallbacks go into the backend's resources, so every application built
# on Limn carries them. Anything for the demo goes under limn-demo/, which is
# published as no library: a face that exists to make one screenshot must not add
# a byte to what an application ships.
#
# Usage:  ./scripts/fetch-fonts.sh [--force]
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_FONTS="limn-backend-lwjgl/src/main/resources/limn/backend/lwjgl/fonts"
DEMO_FONTS="limn-demo/fonts"
FORCE="${1:-}"

# name | destination path, relative to the repository root | URL (pinned commit) | SHA-256
FONTS=(
  "Noto Sans CJK (pan-CJK: Han + Kana + Hangul + Latin/Greek/Cyrillic), tag Sans2.004|$BACKEND_FONTS/NotoSansCJK-Regular.otf|https://raw.githubusercontent.com/notofonts/noto-cjk/523d033d6cb47f4a80c58a35753646f5c3608a78/Sans/OTF/Japanese/NotoSansCJKjp-Regular.otf|68a3fc98800b2a27b371f2fb79991daf3633bd89309d4ffaa6946fd587f375b5"
  "Noto Color Emoji (CBDT color bitmaps), tag v2.051|$BACKEND_FONTS/NotoColorEmoji.ttf|https://raw.githubusercontent.com/googlefonts/noto-emoji/8998f5dd683424a73e2314a8c1f1e359c19e8742/fonts/NotoColorEmoji.ttf|72a635cb3d2f3524c51620cdde406b217204e8a6a06c6a096ff8ed4b5fd6e27b"
  # The four complex scripts, from notofonts.github.io at one pinned commit. These go into the
  # BACKEND, beside the CJK and emoji faces and for the same reason: the shaper resolves a face per
  # run out of FontStore's fallback chain, so a face that is not in every application's chain is a
  # face no application can shape against. Without them the shaper works and has nothing to shape
  # with — the cmap of both faces shipped before these was read directly, and Roboto and Noto Sans
  # CJK each cover 0 of 256 Arabic, 0 of 112 Hebrew, 0 of 128 Devanagari and 0 of 128 Thai.
  #
  # 531 KB for all four, against the pan-CJK face's 16 MB and the colour emoji font's 10 MB.
  #
  # hinted/ttf, and only half of that is a choice. Not the variable font published beside it:
  # stb applies no variations, so it would rasterize the default instance of a file whose whole
  # reason to exist is the instances it does not draw. Hinted over unhinted is not a rendering
  # decision at all — stb executes no TrueType bytecode, so the instructions it carries are never
  # run — it is upstream's default distribution build, which is the one worth pinning.
  "Noto Sans Arabic Regular (Arabic: contextual forms, ligatures, RTL)|$BACKEND_FONTS/NotoSansArabic-Regular.ttf|https://raw.githubusercontent.com/notofonts/notofonts.github.io/3a06b1c521155492df224d33464b3c7b2852d861/fonts/NotoSansArabic/hinted/ttf/NotoSansArabic-Regular.ttf|bdff3e5659d67e67def05b33f749683b9376ae819d65d3dd62ac4640b3aaef48"
  "Noto Sans Hebrew Regular (Hebrew: RTL, GPOS-placed points)|$BACKEND_FONTS/NotoSansHebrew-Regular.ttf|https://raw.githubusercontent.com/notofonts/notofonts.github.io/3a06b1c521155492df224d33464b3c7b2852d861/fonts/NotoSansHebrew/hinted/ttf/NotoSansHebrew-Regular.ttf|cdefaf8efd47045f6820928eba84db5bed7557539328952b5f828315485e02ee"
  "Noto Sans Devanagari Regular (Devanagari: conjuncts, matra reordering)|$BACKEND_FONTS/NotoSansDevanagari-Regular.ttf|https://raw.githubusercontent.com/notofonts/notofonts.github.io/3a06b1c521155492df224d33464b3c7b2852d861/fonts/NotoSansDevanagari/hinted/ttf/NotoSansDevanagari-Regular.ttf|306b53ecfb182a504dd8a7446093c316387d2fd8dc350d0792ed1753fe0996cd"
  "Noto Sans Thai Regular (Thai: mark stacking, and no spaces to break at)|$BACKEND_FONTS/NotoSansThai-Regular.ttf|https://raw.githubusercontent.com/notofonts/notofonts.github.io/3a06b1c521155492df224d33464b3c7b2852d861/fonts/NotoSansThai/hinted/ttf/NotoSansThai-Regular.ttf|61cf814eec46b294d6ea4401ac295d0cecd5207bd2331dcc5a15e7301d30ee44"
  # ONE licence for the four: notofonts.github.io publishes a single SIL OFL 1.1 at the root of
  # its fonts/ tree that covers every family under it, so four copies of the same 4374 bytes
  # would be four files to keep in step rather than one. The CJK and emoji faces come from other
  # repositories and keep their own.
  "Noto Sans script faces licence (SIL OFL 1.1, covers all four)|$BACKEND_FONTS/NotoSansScripts-LICENSE.txt|https://raw.githubusercontent.com/notofonts/notofonts.github.io/3a06b1c521155492df224d33464b3c7b2852d861/fonts/LICENSE|f2095b08bed08b23a6fe26112fcd679a2bee3f002eef077eb05d215ed1051bd8"
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
