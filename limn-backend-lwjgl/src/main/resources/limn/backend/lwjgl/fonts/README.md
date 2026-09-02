# Fonts

One face lives here now: **Limn Menu Symbols** (`LimnMenuSymbols.ttf`, ≈3 KB, SIL OFL 1.1) —
the twenty key symbols a shortcut hint is written with (⌘ ⌥ ⌃ ⇧ ⏎ ⌫ ⇥ ⎋ …), authored by this
project via `scripts/generate-menu-symbols.py` and versioned with it, because its glyphs must
stay in step with what the menu code expects to draw.

Every other face this backend renders with arrives as a **Maven artifact** from the
[limn-fonts](https://github.com/limn-toolkit/limn-fonts) repository, where each font versions
with the font instead of with this toolkit (ADR 036), under the classpath path `limn/fonts/`:

| Artifact | Role | Arrives |
| --- | --- | --- |
| `limn-fonts-roboto` | Roboto Regular/Bold/Italic/Bold-Italic — the default UI family and last resort | **with the backend** (required runtime dependency; FontStore refuses to start without it) |
| `limn-fonts-noto-scripts` | Arabic, Hebrew, Devanagari, Thai (Regular + Bold) | with the backend (default runtime dependency; ADR 006/032) |
| `limn-fonts-noto-cjk` | Han + Kana + Hangul, ≈16 MB | the application opts in |
| `limn-fonts-noto-emoji` | colour emoji, ≈10 MB | the application opts in |

`limn-fonts-all` (published from this repository, versioned with it) names every fallback at
the versions this toolkit was tested with, for a build that wants one line instead of three.

`FontStore` looks for each face under `limn/fonts/` first and under this directory's old path
(`limn/backend/lwjgl/fonts/`) second, so a region-variant CJK file placed at the documented old
location keeps working. Absent fallbacks degrade gracefully — Roboto covers Latin/Greek/Cyrillic
and everything else renders `.notdef` — and the log names the artifact that would fix it.
