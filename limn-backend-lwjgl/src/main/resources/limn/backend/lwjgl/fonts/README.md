# Fonts

The toolkit renders text with **stb_truetype** (monochrome coverage masks). Faces
are registered in `FontStore`, and any code point the primary face lacks is
resolved **per code point** against an ordered fallback chain.

## What's vendored (committed)

| Font | Faces | Covers | License |
| --- | --- | --- | --- |
| **Roboto** | Regular / Bold / Italic / Bold-Italic | Latin, Greek, Cyrillic (default UI family) | Apache 2.0 (`Roboto-LICENSE.txt`) |
| **Noto Sans CJK** (`NotoSansCJK-Regular.otf`, pan-CJK CFF ≈16 MB) | Regular | Han + Kana + **Hangul** + Latin/Greek/Cyrillic | SIL OFL 1.1 (`NotoSansCJK-LICENSE.txt`) |
| **Noto Color Emoji** (`NotoColorEmoji.ttf`, CBDT ≈10 MB) | n/a | Emoji, in **color** | SIL OFL 1.1 (`NotoColorEmoji-LICENSE.txt`) |
| **Limn Menu Symbols** (`LimnMenuSymbols.ttf`, ≈3 KB) | n/a | The twenty key symbols a shortcut hint is written with (⌘ ⌥ ⌃ ⇧ ⏎ ⌫ ⇥ ⎋ …) | SIL OFL 1.1 (`LimnMenuSymbols-LICENSE.txt`) |

The Noto faces are the per-code-point **fallback** chain; Roboto stays the primary
UI font (it gives Bold/Italic cheaply; each extra Noto CJK weight would be another
≈16 MB). If a Noto binary is missing, `FontStore` degrades gracefully (Roboto only,
unknown glyphs → `.notdef`). It tries a few candidate filenames per slot, so a
region variant (`NotoSansSC-Regular.otf`, `NotoSansJP-Regular.ttf`, …) also works.

### Updating / re-fetching

```
./scripts/fetch-fonts.sh --force            # re-download the Noto faces from upstream
./scripts/generate-menu-symbols.py --force  # rebuild the menu key symbols
```

Use the **full pan-CJK** `NotoSansCJK*` for Han **and** Hangul; the region-only
`Noto Sans JP/SC/KR` subsets from Google Fonts cover just their own script.

## Emoji (color)

Emoji render in **color** from `NotoColorEmoji.ttf`. stb_truetype can't open it
(bitmap-only, no outlines), so `ColorBitmaps`/`ColorEmojiFont` parse its `cmap`,
`hmtx` and CBDT tables directly (code point → glyph → advance + PNG) and route
each PNG through the existing image pipeline (`StbImageDecoder` + `drawImage`).
The monochrome Noto Emoji is **not** used: the two fonts cover the same emoji, so
the color font is the single source (it even adds newer emoji the mono lacked).

Single code points only: multi-code-point sequences (ZWJ 👨‍👩‍👧, flags, skin
tones) need GSUB shaping (not implemented) and render as their components. ZWJ and
variation selectors are treated as zero-width.

> Deliberately **no GNU Unifont** last-resort face: the fallback chain is
> Noto-only by request.

## Menu key symbols

A macOS menu writes a shortcut as an unbroken run of symbols. **No font in the
ordinary stack has them**, not Roboto, and no single Noto face either: measured
across the twenty code points, Noto Sans Symbols 2 has nine, Noto Sans Symbols
eight (including the Control caret the other lacks) and Noto Sans Math the last
three. `scripts/generate-menu-symbols.py` merges exactly those glyphs into one
small face, which is why this one is **generated and committed** rather than
fetched: the heavy fallbacks arrive on a background parse, and a menu opened in
the first moments of a run would draw boxes where its hints belong.

Two of the twenty (the Home and End arrows) are in the color emoji font as
well, and the emoji path is consulted before any fallback face. `FontStore`
gives this face precedence for the code points it owns, or a hint would show two
full-color pictograms mid-row. That precedence is deliberately scoped to this
face and is **not** a general text-presentation rule.

## License note

Roboto is Apache 2.0; the Noto faces are SIL OFL 1.1. Both may be embedded and
redistributed in an application; keep the license files alongside the fonts and
do not sell the fonts on their own.
