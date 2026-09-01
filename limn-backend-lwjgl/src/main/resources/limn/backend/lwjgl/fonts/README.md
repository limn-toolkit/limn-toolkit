# Fonts

The toolkit renders text with **stb_truetype** (monochrome coverage masks) and
shapes it with **HarfBuzz**. Faces are registered in `FontStore`, and any code
point the primary face lacks is resolved against an ordered fallback chain — **per
code point** by the measure and paint walks, and **once per run** by the shaper,
which has to know the face before any glyph exists.

## What's vendored (committed)

| Font | Faces | Covers | License |
| --- | --- | --- | --- |
| **Roboto** | Regular / Bold / Italic / Bold-Italic | Latin, Greek, Cyrillic (default UI family) | Apache 2.0 (`Roboto-LICENSE.txt`) |
| **Noto Sans CJK** (`NotoSansCJK-Regular.otf`, pan-CJK CFF ≈16 MB) | Regular | Han + Kana + **Hangul** + Latin/Greek/Cyrillic | SIL OFL 1.1 (`NotoSansCJK-LICENSE.txt`) |
| **Noto Color Emoji** (`NotoColorEmoji.ttf`, CBDT ≈10 MB) | n/a | Emoji, in **color** | SIL OFL 1.1 (`NotoColorEmoji-LICENSE.txt`) |
| **Noto Sans Arabic** (`NotoSansArabic-{Regular,Bold}.ttf`, ≈229+255 KB) | Regular / Bold | Arabic | SIL OFL 1.1 (`NotoSansScripts-LICENSE.txt`) |
| **Noto Sans Hebrew** (`NotoSansHebrew-{Regular,Bold}.ttf`, ≈26+26 KB) | Regular / Bold | Hebrew | SIL OFL 1.1 (`NotoSansScripts-LICENSE.txt`) |
| **Noto Sans Devanagari** (`NotoSansDevanagari-{Regular,Bold}.ttf`, ≈239+245 KB) | Regular / Bold | Devanagari | SIL OFL 1.1 (`NotoSansScripts-LICENSE.txt`) |
| **Noto Sans Thai** (`NotoSansThai-{Regular,Bold}.ttf`, ≈37+37 KB) | Regular / Bold | Thai | SIL OFL 1.1 (`NotoSansScripts-LICENSE.txt`) |
| **Limn Menu Symbols** (`LimnMenuSymbols.ttf`, ≈3 KB) | n/a | The twenty key symbols a shortcut hint is written with (⌘ ⌥ ⌃ ⇧ ⏎ ⌫ ⇥ ⎋ …) | SIL OFL 1.1 (`LimnMenuSymbols-LICENSE.txt`) |

The Noto faces are the **fallback** chain; Roboto stays the primary UI font (it
gives Bold/Italic cheaply; each extra Noto CJK weight would be another ≈16 MB). If
a Noto binary is missing, `FontStore` degrades gracefully (Roboto only, unknown
glyphs → `.notdef`). For CJK it tries a few candidate filenames, so a region
variant (`NotoSansSC-Regular.otf`, `NotoSansJP-Regular.ttf`, …) also works.

## The four complex scripts

Nothing else here covers a single character of them. Both faces that shipped before
them had their `cmap` read directly, and Roboto and Noto Sans CJK each answer **0
of 256** Arabic, **0 of 112** Hebrew, **0 of 128** Devanagari and **0 of 128** Thai.
Until these landed the shaper ran, resolved every Arabic run to Roboto, and shaped
it into a correctly ordered row of `.notdef` boxes.

They are 531 KB for all four, which is nearer Roboto Regular's 349 KB than the
pan-CJK face's 16 MB — so they are **not** in the background batch because they are
big. They are there because nothing at startup knows whether an application will
ever draw one of these scripts, and the moment that becomes known is the first code
point no resident face covers, which is already what kicks that batch. The fold-in
bumps the shaping epoch and re-notifies `Fonts`, so a line that shaped into boxes
re-shapes against the face that has now arrived.

Order in the chain is deliberate: **behind** the CJK face and ahead of the Roboto
last resort. These four carry Latin digits and punctuation as well as their own
script, and ahead of the CJK face they would start drawing the Latin of any line
whose primary lacks it — a visible change to text that has nothing to do with them.

Each is also a selectable family (`Noto Sans Arabic`, …), like `Noto Sans CJK`: an
application whose UI is Arabic wants one as its primary rather than as the thing
that rescues Roboto.

### Bold, and why not Italic

Each of the four also ships its **Bold**, from the same pinned upstream commit
(≈564 KB for all four — about the price of the Regulars again). It registers as a
lazy style variant when the family folds in, so it costs a parse only on first
use, and only when the family is chosen **as a primary**: the per-code-point
fallback chain stays Regular, because a rescue face is picked per code point with
no style in the key. Making the chain style-aware (bold Hebrew mid-line inside a
bold Latin paragraph) is a separate decision that would touch every fallback
script including CJK.

There is deliberately no Italic: upstream publishes 36 weight×width styles per
family and **no Italic for any of these scripts** (italic is a Latin-script
convention). The resolver drops italic before weight, so an italic request
renders upright — the same thing a browser does. The pan-CJK face stays
Regular-only; its Bold is another ≈16 MB.

One licence file covers all four. `notofonts.github.io` publishes a single SIL OFL
1.1 at the root of its `fonts/` tree for every family under it, so four copies of
the same 4374 bytes would be four files to keep in step instead of one.

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
tones) need the **colour font's own** GSUB, and this path never opens it — the
shaper the text faces go through cannot be pointed at a font stb refuses to load —
so they render as their components. ZWJ and variation selectors are treated as
zero-width.

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
