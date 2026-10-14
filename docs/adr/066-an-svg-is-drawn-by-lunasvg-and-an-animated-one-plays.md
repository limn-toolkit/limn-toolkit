# ADR 066: An SVG is drawn by lunasvg, and an animated one plays

- **Status:** Accepted, 2026-10-14, not built. The owner chose the renderer, that animation
  comes now, and the four questions of section 9.
- **Date:** 2026-10-14
- **Scope:** how the toolkit reads and draws an SVG, still or animated: the renderer and how it
  ships, the API that hands an SVG to the screen, the animation it plays, and what a damaged or
  hostile file meets.
- **New public API (proposed):** `SvgIcon` of any aspect and animated; an `ImageView` that shows
  an icon as well as an image; `Images.decode` of an SVG; an `SvgIcon`'s frame rate, and the system property
  `limn.svg.frameRate`; the native payload `limn-lunasvg-natives`.
- **Compatibility:** every `SvgIcon` that draws today draws the same or better. `lwjgl-nanovg`
  leaves the backend.

---

## 1. Why

An SVG reaches the toolkit today as an icon and nothing else: `SvgIcon` rasterizes a file into
a square of the size asked for, through NanoSVG, which comes inside LWJGL's `nanovg` module.
There is no SVG picture of any shape, drawn sharp at the size and density of the view it sits in,
and an SVG that moves stands still.

NanoSVG was chosen because it cost nothing: its bytes already shipped. It was never measured
against what an application is given to show. It is measured now.

## 2. What was measured

Three renderers, against two kinds of file, on the development Mac (arm64).

- **The resvg test suite**, 1,742 files, each testing one feature with the picture expected of
  it. A test counts as right when no more than 5% of the pixels either picture paints differ by
  more than 40 levels, both laid on white. Counting over the whole picture instead had let a test
  pass with its text missing, and was dropped.
- **310 files of the kind an application ships:** 111 illustrations of empty states and help
  pages from an open-source development platform's design system, 99 colour emoji from an open
  emoji font's sources, and 100 interface icons, each with resvg's picture as the expected one.

| | NanoSVG (today) | ThorVG, SVG only | lunasvg 3.5.0 |
|---|---|---|---|
| Suite, right | 23% | 41% | **45%** |
| Shapes | 78% | 86% | 94% |
| Gradients and patterns | 40% | 69% | 82% |
| Clipping and masks | 8% | 56% | 75% |
| Structure (`use`, `symbol`, `style`, `svg`) | 28% | 52% | 59% |
| Painting (strokes, opacity, markers, blending) | 33% | 50% | 66% |
| Filters | 10% | 31% | 22% |
| Files an application ships, right | 65% | 97% | **100%** |
| Of them, illustrations | 8% | 93% | 100% |
| One file, read and drawn at 200 to 256 px | 0.25–0.35 ms | 0.35 ms | 0.4 ms |
| Size, arm64, stripped | none new | 345 KB | 398 KB, with text |
| 6,000 damaged copies | not measured | 0 crashes, 43 runs over 5 s | **0 crashes, 0 runs over 5 s** |

**Corrected 2026-10-14, as it was built: the illustrations' row measures nothing.** Their
expected pictures were resvg's, and resvg does not read CSS custom properties either: it drew
them in black, lunasvg and ThorVG drew them in black too, and the three blacks matched, while
NanoSVG, which drew them otherwise, was counted wrong. A browser draws them in their fallback
colours. The toolkit now resolves `var()` itself, in Java, before the renderer sees a file
(section 6), and lunasvg then draws them as a browser does. The suite's rows, the emoji, the
icons and the fuzz are untouched by this, and so is the choice of lunasvg; the real files of
section 7 are compared with a browser's pictures, not resvg's.

Text scores near nothing for all three because the suite's pictures use fonts of its own, which
were not loaded; the illustrations failed NanoSVG because they paint through CSS variables
(`fill="var(--name, #e7e4f2)"`), and with every variable replaced by its fallback NanoSVG drew
90% of them right. lunasvg was measured built for release, without assertions: built with them,
five of the 6,000 copies stopped the process at an assertion.

Animation was read from 198 files of two open collections of loading indicators, 60 animated by
SMIL and 138 by CSS. What they move is little: geometry (`r`, `cx`, `cy`, `x`, `y`, `width`,
`height`), opacity, `rotate`, `translate`, `scale`, a stroke's dash, eased by key splines or
timing functions. None of the three renderers plays either kind.

## 3. Decision: the renderer

**lunasvg draws every SVG,** as the owner chose: the most faithful of the three on the suite and
on real files, and the only one that neither crashed nor hung on damaged ones. It is MIT, C++17,
and draws with plutovg, by the same author, also MIT. NanoSVG goes, and `lwjgl-nanovg` with it:
140 to 240 KB a platform that the toolkit no longer ships.

**The payload is an artifact of its own, `limn-lunasvg-natives`, versioned after the lunasvg it
carries,** as ADR 037 made the video decoder's: its own repository and build, a JNI shim, six
`natives-<os>-<arch>` classifiers, a version whose first three numbers are lunasvg's
(`3.5.0.0`), and an ABI number the shim and the Java shake hands on before anything else is
called. The backend depends on the shim jar and on the six classifiers as it does on LWJGL's
natives, so an application adds nothing to get SVG.

**Built for release:** without assertions, which on a damaged file stop the process.

**With one patch to plutovg,** as the owner chose on 2026-10-14 when the payload's first run on
Linux failed its fuzz. glibc's `rand()` reached a damaged copy the macOS sequence had not: a
dashed circle at x = 9,999,912. plutovg flattens a curve by halving it until it reads flat; that
far out a float's step is about 1.0, the halves of a curve one step wide are the curve itself,
and it never read flat, so the process spun on macOS and grew until killed on Linux. lunasvg
3.5.0 is the newest release and plutovg's main branch has the same code. The patch gives each
curve a budget of 65,536 splits, after which what is left is drawn as straight lines; the case
draws in 3 ms. It lives in the payload's `patches/`, is applied to the verified archive, says
in its header what it changes and why, and goes when a lunasvg that no longer needs it is
packaged; the case is a seed of the fuzz and a test that times out without the patch. Patched,
three seeds of 6,000 cases each on Linux and the CI's seed on macOS found nothing.

**SVG text has the fonts the toolkit bundles and the system's,** as the owner chose: the bundled
families are registered with lunasvg, and lunasvg looks up the rest among the fonts installed on
the machine. A file that names an installed font gets it; the same file can draw differently on
a machine without it, which the owner accepted for fidelity to what was drawn.

**No file or network is read on lunasvg's own account** (section 6): what a file refers to, the
toolkit reads, or nobody does.

**The SPI changes shape.** `SvgRasterizer` turns bytes into an image once. An animation keeps a
document and changes it between frames, so the SPI becomes a document: opened from sanitized
bytes, told an attribute's new value, drawn into a buffer of a given size, closed. A still SVG
opens, draws and closes.

## 4. Decision: an SVG on the screen

- **`SvgIcon` takes any aspect.** It draws into the box it is given, fitted and centred, as an
  icon's picture is today, instead of assuming a square.
- **An `ImageView` shows an icon as well as an image,** as the owner chose: `new
  ImageView(SvgIcon.fromResource(...))` draws the icon at the view's own size and density each
  time either changes, and `fit`, tint and dragging behave as for an image, so an illustration
  stays sharp at any size and on any screen. No new type.
- **`Images.decode` reads an SVG** at its own size, for the code that wants pixels.
- **An `SvgIcon` drawn at a size is cached at that size,** as today, within the shared loads'
  budget (ADR 064).

## 5. Decision: an animated SVG plays

**An SVG with SMIL or CSS animation is an animated icon,** in the sense of ADR 065's addendum:
it plays by itself in every component that draws icons and in an `ImageView`, on the scene's
time, asking for a frame of the window only when a frame of its own is due. The wish for less
motion is not applied, as the owner settled for every animated picture.

**The toolkit reads the animation itself, in Java, and lunasvg draws each moment.** Every
animated value is computed at the moment drawn and set on the document; the document is drawn.

- **SMIL:** `animate`, `animateTransform` and `set`; `from`, `to`, `by` and `values`; `dur`,
  `begin` as a time, `repeatCount`, `repeatDur`, `fill`; `keyTimes`, `keySplines` and
  `calcMode` linear, discrete and spline.
- **CSS:** `@keyframes` and the `animation` properties (name, duration, timing function with
  `cubic-bezier` and `steps`, delay, iteration count, direction, fill mode), over `transform`
  and the properties the corpus moves: opacity and the fill and stroke opacities, the geometry
  properties, the stroke's dash; `transform-origin` and `transform-box`.
- **A beginning at another animation's beginning or end,** offset (`begin="0;spin.end-0.25s"`),
  as the owner chose on 2026-10-14 once it was measured, building this: 24 of the 58 SMIL files
  of the collections chain their parts so, and without it they played once and stopped. Every
  instant each animation begins at is worked out to thirty seconds, a beginning while it plays
  restarting it, and a chain that comes round again is a loop of its own period. Of several
  animations of one attribute, the one that began last wins, as SMIL ranks them.
- **Out:** `animateMotion`, a user's event as a beginning (`click`, `focus`), `accumulate`, CSS
  transitions, and scripting, never. `additive="sum"` is read for transforms.

**Frames are moments at a fixed rate,** so that an animated SVG is an animated image like any
other: **24 a second unless set,** as the owner chose, the rate of film. The rate is the
application's to set and never the file's: neither SMIL nor CSS has a frame rate, both measure
time, and an attribute of the toolkit's own in the file was refused as too much. An application
sets it on an icon, `svgIcon.withFrameRate(60)`, and the default for every SVG with the system
property `limn.svg.frameRate`; the icon's own wins over the property. The
frames cover the animation's period, the least common multiple of its durations and delays,
kept no longer than 30 seconds; one that never repeats plays its length once and rests.

## 6. Decision: a damaged or hostile file

As ADR 061 asks of everything the toolkit reads itself:

- **The toolkit parses the XML first, in Java,** with the parser made safe (no DTD, no
  entities, no external anything), within ceilings: the file's bytes, the elements, the depth,
  the `use` references followed, the animations. Whatever it refuses leaves as
  `MalformedContentException`.
- **It hands lunasvg sanitized text:** animation elements and declarations removed, since the
  toolkit plays them; every element that is animated given an id the toolkit chose; no
  reference outside the file, since lunasvg reads a path it is given; and every CSS custom
  property resolved, the value an ancestor's `style` or a sheet's rule for the whole document
  declares, or else the fallback, since lunasvg reads no `var()`.
- **A picture beside a file read from disk is read by the toolkit,** as the owner chose: an
  image's `href` relative to the SVG's own folder, resolved there, refused if it leads out of
  that folder, read within the same ceilings as any picture and handed to lunasvg inside the
  text as data. An SVG from a resource or from bytes has no folder, so only its embedded pictures
  draw. A network address is never read, from any SVG.
- **The Java side is fuzzed in `check`.** The native side is guarded, as ADR 061 settled for
  the native decoders, and was fuzzed once here (section 2); the fuzz goes with the payload's
  repository, run on each lunasvg it packages.

## 7. Tests

- **The suite as a ratchet:** a subset of the resvg tests per feature in `check`, each feature's
  share of right pictures held at what was measured, so that a payload or a change that draws
  worse fails.
- **The real files**, a sample of each kind with the picture expected of it.
- **The animation engine** against moments computed by hand for every SMIL and CSS feature of
  section 5, and the indicator collections drawn at moments compared with a browser's.
- **The budget of ADR 062:** an animated icon of ten frames a second asks for ten frames of its
  window a second and repaints its box only.

## 8. Not done

- **Filters beyond what lunasvg draws** (it draws 22% of the suite's).
- **Text laid out as the toolkit lays it out:** SVG text is lunasvg's, with the bundled fonts,
  and does not shape every script the toolkit's own text does.
- **SVG fonts, `foreignObject`, scripting, links.**
- **Pictures referred to outside the file.**

## 9. What the owner settled, 2026-10-14

1. **lunasvg, with native binaries,** replaces NanoSVG (section 3).
2. **Animated SVG comes now** (section 5).
3. **An `ImageView` shows an icon,** and no new type of vector picture (section 4).
4. **SVG text has the system's fonts too,** beside the bundled ones (section 3).
5. **A file from disk may refer to a picture beside it,** inside its own folder (section 6).
6. **24 frames a second unless set,** on an icon by the application or for every SVG by a
   system property; nothing in the file, since no standard attribute says it (section 5).
