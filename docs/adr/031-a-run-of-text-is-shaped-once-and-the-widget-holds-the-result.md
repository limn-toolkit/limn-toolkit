# ADR 031. A run of text is shaped once, and the widget holds the result

- **Status:** **Accepted and implemented**, closed 2026-08-31, and **completed 2026-08-31** by the
  follow-up in §8. §7 records what implementing the six phases settled: both open questions in §6
  are closed there, one prediction in §0 turned out one letter too strong, and one promise in §1
  was not kept — `drawText(String, …)` was not made to wrap the shaped form, so a complex script
  was drawn correctly only where a widget holds a `ShapedText`, which three widgets do. **§8 is
  that promise kept**, and it also closes the largest item in §7.1; read §7 as the state of the
  six phases and §8 as what the seventh changed. **§8.2 is the review of §8**: three defects it
  introduced or exposed, each recorded as cause rather than symptom. §7.1 lists what is still not
  done, because a document that overstates what shipped is worse than one that was never closed.
- **Date:** 2026-08-30
- **Scope:** how Limn draws scripts that need contextual forms, ligatures, reordering and mark
  attachment (Arabic, Hebrew, Devanagari, Thai), how bidirectional text is ordered, and what
  caret, selection, hit-testing, ellipsis and wrapping become once a prefix is no longer a
  measurable thing. Layout mirroring is **not** here: it is a toolkit-wide ADR of its own, not yet written.
- **Audience:** whoever implements it. Every claim below is tied to a file:line in this repo or
  to a measurement recorded in §0, which says how it was taken.

---

## 0. What was measured before anything was decided

**Finding 1: the block is one layer thick, and everything above it inherits the shape of that
block.** The atlas is keyed by code point (`GlyphAtlas.java:105`, `:118`), the rasterizer is asked
for a code point (`StbFont.java:367,375`), the metrics are per code point (`:272`), the paint loop
walks code points (`GlCanvas.java:799`), and the face fallback is resolved per code point
(`FontStore.java:801`). One code point in, one glyph out, in logical order. Every property a
complex script needs — a glyph that depends on its neighbours, two code points becoming one glyph,
a glyph drawn before the code point that precedes it — is unrepresentable in that pipeline, not
merely unimplemented.

**Finding 2: the consumers are not merely LTR-assuming, they are prefix-assuming, and that is the
harder half.** `TextField.indexAt` binary-searches monotone prefix widths (`TextField.java:306`,
via `prefixWidth` at `:301`), `TextArea.indexAtContent` does the same (`TextArea.java:430`), and
`Label` does it twice more for ellipsis and hard-break wrapping (`Label.java:468,535`). All four
rest on "the width of the first N code points is the width of the first N code points **of the
whole string**". Under shaping that is false: the same prefix in isolation shapes into different
glyphs than it does inside its line. This cannot be patched by making the measurement
direction-aware; the operation itself has to be replaced.

**Finding 3: HarfBuzz is already a first-class LWJGL artifact, and stb rasterizes what it
returns.** `org.lwjgl:lwjgl-harfbuzz:3.4.1` is on Central: 0.3 MB of Java, and 0.5–0.7 MB of
native per platform. Its binding carries `hb_blob_create`, `hb_face_create`, `hb_font_create`,
`hb_buffer_add_utf16`, `hb_shape` and `hb_buffer_get_glyph_infos/positions` — enough to shape from
the very `ByteBuffer` `StbFont` already holds, with no FreeType anywhere. On the other side,
`stbtt_MakeGlyphBitmap`, `stbtt_GetGlyphBitmapBox` and `stbtt_GetGlyphHMetrics` all take a **glyph
index**. So the shaper is added and nothing is thrown away: HarfBuzz decides *which* glyphs and
*where*, stb still draws them.

Verified end to end against Noto Sans Arabic, Hebrew and Devanagari, on this machine, before this
ADR was written:

| Sample | In | Out | What it proves |
| --- | --- | --- | --- |
| `العربية` | 7 code points | 10 glyphs | every glyph id differs from the naive cmap id: contextual forms |
| `क्ष` | 3 code points | **1 glyph** | a conjunct ligature: three characters, one glyph |
| `हिन्दी` | 6 code points | 5 glyphs | drawn cluster order `0 0 2 4 4`: the i-matra is drawn **before** its consonant |
| `שָׁלוֹם` | 7 code points | 7 glyphs | RTL visual order, marks at advance 0 placed by GPOS |

The Devanagari line is the decisive one. A pipeline that emits glyphs in code-point order cannot
produce it at any level of effort.

**Finding 4: bidi and line breaking cost no dependency at all.** `java.text.Bidi` and
`java.text.BreakIterator` are in **`java.base`** (not `java.desktop`) and compile under
`--release 17`; both were checked against the pinned JDK 21 toolchain. Neither matches the banned
prefixes in `checkArchitecture` (`build.gradle.kts:176` bans `java.awt.`, `javax.swing.`,
`org.eclipse.swt.`). The Unicode Bidirectional Algorithm is therefore already in the platform,
tested, and free: `new Bidi("Total: 42 ريال (SAR)", DIRECTION_DEFAULT_LEFT_TO_RIGHT)` returns the
three runs at levels 0/1/0 that reordering needs. Nothing here justifies ICU4J, which in any case
carries no OpenType layout engine and could not shape Devanagari against a font.

**Finding 5: for Latin, this changes almost nothing, and exactly what it changes is enumerable.**
The worry that turning on a shaper reflows every existing screen was measured rather than assumed.
stb_truetype already reads GPOS pair kerning, not only the legacy `kern` table — which is what
Roboto has (68 KB of GPOS, 11 KB of GSUB, **no `kern` table at all**). Comparing HarfBuzz's total
advance against the advance `GlCanvas.drawText` computes today, in Roboto font units:

| Text | Today | HarfBuzz | Delta |
| --- | --- | --- | --- |
| `Waltz, bad nymph` | 16094 | 16094 | identical |
| `Théâtre` | 6981 | 6981 | identical |
| `Ação` | 4690 | 4690 | identical |
| `Ñandú` | 5990 | 5990 | identical |
| `Привет` | 7024 | 7024 | identical |
| `Ελληνικά` | 8565 | 8565 | identical |
| `office` | 5248 | 5074 | **−174** (`ffi` → one glyph) |
| `fi` | 1210 | 1135 | **−75** |

So the entire Latin/Greek/Cyrillic delta is the standard `liga` feature. That is a rendering
improvement, it is enumerable, and it is a feature flag rather than a fact of the design — which
is what makes the phase that introduces shaping verifiable instead of a leap.

**Finding 6: no bundled face can draw any of this.** The `cmap` of both shipped faces was read
directly. Roboto: 0/256 Arabic, 0/112 Hebrew, 0/128 Devanagari, 0/128 Thai. Noto Sans CJK: the
same four zeros. So faces are part of the work, not an afterthought — and they are cheap. Noto
Sans Arabic is 229 KB, Devanagari 239 KB, Hebrew 26 KB, against the 16 MB pan-CJK face and the
10 MB colour-emoji face the backend already carries.

---

## 1. Decision

1. **A shaped run is a value, not a measurement.** `limn.graphics.ShapedText` is produced once by
   the ruler and consumed by both layout and paint. It is the same move ADR 006 made for
   `I18nString`: replace a function resolved at the point of use with a type the widget stores,
   because the thing being computed is expensive, is needed twice, and has to stay consistent
   between the two uses.
2. **`drawText(String, …)` and `measure(String, Font)` survive unchanged** (`Canvas.java:317,324`,
   `TextRuler.java:32`), wrapping the shaped form. No existing caller changes, and no widget is
   forced to adopt `ShapedText` in the phase that introduces it.
3. **Shaping happens in the backend, behind the existing SPI.** HarfBuzz is confined to
   `limn-backend-lwjgl`, exactly as GLFW, OpenGL and stb already are, and enforced by the same
   `checkArchitecture` rule. Nothing above the backend learns the word HarfBuzz.
4. **An absent native degrades, it does not fail.** Where the HarfBuzz native cannot load, the
   ruler returns a `ShapedText` built by today's per-code-point walk. Latin, Greek, Cyrillic and
   CJK keep working; complex scripts render as they do today. Same contract as the FFmpeg decoder
   (ADR 011, ADR 030): a missing native narrows what the toolkit can do and never stops it.
5. **The atlas is keyed by glyph id.** `glyphKey` already reserves 21 bits for its last field
   (`GlyphAtlas.java:105`) and a glyph id fits in 16, so the packing survives untouched; only what
   the field *means* changes. `hasGlyph(int cp)` stays, because face **selection** is still a
   coverage question about a code point.
6. **Bidi is `java.text.Bidi` and line breaking is `java.text.BreakIterator`.** No new dependency,
   no hand-written UBA, and no table to keep current with Unicode.
7. **Prefix measurement is deleted, not adapted.** Every site in Finding 2 asks `ShapedText` a
   question instead: where is the caret for this index, which index is under this x, which boxes
   cover this range. This is also faster than what it replaces — today a single click costs
   O(log n) prefix measurements of O(n) each.

### 1.1 The type

```java
public final class ShapedText {                 // limn.graphics

    public String text();
    public Font font();
    public TextMetrics metrics();               // as today: width, ascent, descent, lineHeight
    public Direction baseDirection();           // what the paragraph resolved to
    public boolean isSimple();                  // one LTR run, no reordering: the fast paths stay legal

    // Geometry. Char indices are into text(); x is in logical points from the run origin.
    public Caret caretAt(int charIndex);        // strong and weak x: an index on a direction
                                                // boundary has two visual positions, and a caret
                                                // that shows only one lies about where typing lands
    public int indexAt(float x);                // hit test, grapheme-aligned
    public List<Span> selection(int start, int end);  // N boxes: a logical range is not one box

    // Navigation, in VISUAL order: what the arrow keys mean.
    public int nextCaret(int charIndex);
    public int previousCaret(int charIndex);

    public record Span(float x0, float x1) { }
    public record Caret(float strongX, float weakX, boolean split) { }
    public enum Direction { LTR, RTL }
}
```

`selection` returning a list rather than one rect is the part that must not be simplified away. A
selection that is contiguous in the string is not contiguous on screen the moment it crosses a
direction boundary, and a single-rect API would make the correct rendering unrepresentable.

### 1.2 The seam

```java
public interface TextRuler {
    TextMetrics measure(String text, Font font);                     // unchanged
    ShapedText shape(String text, Font font, Direction base);        // new
}
```

`measure` becomes `shape(...).metrics()` with the same memo it has today. `Canvas` gains
`drawText(ShapedText, float, float, Paint)`; the `String` overload shapes and draws, so a caller
that does not care never sees the type.

`Direction.AUTO` is deliberately absent from the enum and present as the *default* of the base
argument's resolution: the paragraph level comes from `java.text.Bidi`'s first strong character
rule unless a caller states otherwise. An enum constant meaning "decide later" would let a widget
store one.

### 1.3 Itemization, in order

Per `shape` call, inside the backend:

1. `java.text.Bidi` over the whole string → a level per character → runs of equal level.
2. Within a bidi run, split by script (`Character.UnicodeScript.of`), with common and inherited
   characters extending the run they follow rather than starting one.
3. Within a script run, resolve the face **once for the run** rather than per code point, then
   extend while that face has coverage. This replaces `FontStore.faceForCodepoint`
   (`FontStore.java:801`) on the shaping path; it stays for the degraded path.
4. Shape each (face, script, direction, language) run through HarfBuzz.
5. Concatenate in visual order by level (UBA rule L2).

Steps 2 and 3 are why the current per-code-point fallback cannot simply be kept: shaping a run
requires knowing the face **before** the glyphs exist, and a run split mid-word by a fallback
decision shapes as two words.

### 1.4 What is cached, and where

Two levels, for two different lifetimes:

- **The widget holds its `ShapedText`**, invalidated by the epoch it already watches for fonts and
  control size. This is where the win is: a `Label` that is not changing re-shapes never.
- **The backend memoizes `shape` in a small LRU** keyed by `(text, faceId, quantized size,
  direction)`. This catches the transient callers — a chart axis rebuilding its labels each frame
  — that have nowhere to hold a value.

Shaping on every frame with neither would be a regression against today's advance caches, and is
the single most likely way for this work to be slower than what it replaces.

---

## 2. The work, phase by phase

Each phase ends in something that can be demonstrated and tested on its own.

**Phase 1 — the atlas learns glyph ids.** `GlyphAtlas` and `StbFont` re-keyed from code point to
glyph index; `stbtt_MakeCodepointBitmap` → `stbtt_MakeGlyphBitmap`, and the metric caches with it.
No visible change; `StbFontTest`, `GlyphMapTest` and the golden screenshots stay green as they are.

**Phase 2 — the seam.** `ShapedText`, `TextRuler.shape`, the HarfBuzz implementation, both caches,
and the degraded path. `drawText(String, …)` routes through it. Verification is Finding 5: every
golden screenshot unchanged except where an f-ligature falls, and those reviewed one by one before
being re-pinned.

**Phase 3 — bidi and the RTL faces.** Itemization per §1.3; Noto Sans Arabic and Hebrew added to
`scripts/fetch-fonts.sh` with a pinned commit and a SHA-256, like every other vendored face.
Arabic and Hebrew render correctly. The caret does not yet.

**Phase 4 — caret, selection, hit-testing.** `TextField`, `TextArea` and `TextEditModel` moved onto
`ShapedText`; logical versus visual movement, the split caret, multi-box selection, word movement
through `BreakIterator`. `Label.ellipsize` and `wrapText` rewritten on the same type, which also
retires the per-code-point hard break that is the only way unspaced CJK wraps today.

**Phase 5 — Devanagari and Thai.** The two faces; `hi`, already translated and today rendering
boxes, starts rendering. Thai needs `BreakIterator` for line breaking because it has no spaces.

**Phase 6 — languages, docs, site.** `ar` and `he` bundles for the four toolkit domains and the
kitchen sink; `docs/design/i18n.md`'s "What renders today" table, `docs/design/text-and-input.md`,
and `site/src/guides/text-and-languages.md`.

---

## 3. Cost

- **+3.5 MB** of HarfBuzz natives across the six platforms the backend declares
  (`limn-backend-lwjgl/build.gradle.kts:21`), plus 0.3 MB of Java.
- **+~0.5 MB** of faces for all four scripts. Against 27 MB of faces already shipped, both are
  noise; they are stated because a dependency added quietly is a dependency nobody weighed.
- One new public type and one new method on two published interfaces. Permanent.

---

## 4. What this deliberately is not

- **Not mirroring.** Direction as a layout axis, `Insets` leading/trailing, mirrored ink, arrow-key
  semantics and popup anchoring belong to the mirroring ADR, which is deliberately not written yet. Correct Arabic text inside an unmirrored layout is a
  real intermediate state, and shipping it is better than shipping neither half.
- **Not vertical writing.** Mongolian and CJK vertical layout need a second axis through the whole
  layout system, not a direction flag.
- **Not per-run font features, not variable-font axes.** `ShapedText` has no feature list in v1;
  `liga` and the script's required features are what the shaper is asked for. Adding a feature
  argument later breaks nothing.
- **Not justification, not hyphenation, not kinsoku.** Phase 4 replaces a hard break with a real
  line break; it does not make the line beautiful.
- **Not collation, not locale-aware case mapping.** Unchanged from ADR 006 §4.

---

## 5. Risks and open edges

- **Phase 4 is where toolkits go wrong for years.** Bidi caret behaviour is subtle and looks fine
  in a screenshot while being wrong. It must be pinned by tests over known cases — logical order
  in, expected visual caret positions out — not verified by looking at it.
- **The two caches are load-bearing, not an optimization.** Without them this is slower than what
  it replaces on every frame that draws text, which is every frame.
- **Cluster-to-index mapping is the whole contract.** HarfBuzz reports clusters as offsets into
  what it was handed; every run boundary shifts that origin, and an off-by-one there is a caret
  that lands one character away from the click, everywhere, forever.
- **`isSimple()` is a promise that has to stay true.** It exists so the hot LTR paths keep their
  fast route, which means every future change to itemization has to keep it honest or the fast
  route silently becomes the wrong route.
- **A face resolved per run changes what mixed strings look like.** Today a single Cyrillic
  character inside a Latin word can come from a different face than its neighbours; per-run
  resolution is more correct and is a visible change in a case nobody has screenshotted.

---

## 6. Open questions

- Does `ShapedText` cache its glyph ids across a content-scale change, or re-shape? Shaping is
  scale-independent in font units; positioning under hinting is not. The cheap answer is to key the
  cache by quantized size and re-shape; the correct answer needs a measurement that has not been
  taken.
- Whether `PasswordField`'s drawn mask should bypass shaping entirely. It almost certainly should —
  it draws circles, not text — but the code path currently runs through the same ruler.

---

## 7. What the implementation settled (deltas from the text above)

- **Finding 5 held exactly, and it is a permanent test now rather than a measurement.** The eight
  rows are re-asserted against the shipping ruler in the font units they were recorded in — the
  two that move, `office` 5248 → 5074 and `fi` 1210 → 1135, pinned as deltas as well as as
  totals — and the claim is widened from eight samples to every string the toolkit's own bundles
  ship: four bundle families across the fourteen locales whose script Roboto draws, at least two
  hundred distinct strings, plus the Greek UI vocabulary no bundle supplies. It is a **set
  equality** and not a tolerance — the set of strings whose width shaping moves is exactly the set
  with an f-ligature in it, to the float at an em size and to 0.005 pt at 11, 13, 16, 17.5, 20 and
  32 pt — and requiring the ligature side to *differ* is what makes it an acceptance test instead
  of a regression net: a one-sided check would pass a build where shaping had quietly stopped
  happening. Two things the ADR's probe did not know. Roboto's `liga` covers `f_i`, `f_l`, `f_f_i`
  and `f_f_l` and **not** `f_f`, so `Öffnen` and `Effekt` do not move, and a predicate treating a
  bare `ff` as an opportunity would fail this suite on real German UI text. And the Russian and
  Ukrainian strings that embed `GPU` itemize into three runs and shape as three separate HarfBuzz
  calls, then land on today's number to the float — the only evidence here that itemization costs
  no width by itself.

- **§1.1's caret was wrong in one structural way, and the implementation corrected it.** An `int`
  index is not a caret. An index on a direction boundary has *two* visual positions, and any fixed
  rule for choosing between them breaks one of the two traversal directions: the caret walks left
  out of one run and then, on the next press, jumps to the far end of the line, because an index
  does not say which of the two points the previous press arrived at. That is non-determinism
  across two keystrokes, not imprecision. So the shipped API addresses a caret by a `Position` —
  a char index and an `Affinity`, the side of it the caret is on. `hitTest` returns one,
  `caretLeft`/`caretRight` take and return one in place of the ADR's `nextCaret`/`previousCaret`,
  and `indexAt` survives as the form that throws the side away, honest in its own javadoc about
  going exactly as far as one keystroke.
  The side then has to live in the **edit model** beside the cursor rather than in the widget —
  every method that writes the cursor would otherwise need a paired assignment at three call sites
  apiece — and `TextEditModel`'s undo `Snapshot` carries it, because an undo that restored a
  correct index on yesterday's side draws the caret a whole run away, one keystroke later, with
  nothing to trace it to. `Caret` changed shape for the same reason in miniature: it carries the
  two positions and *derives* `split()`, `strongX()` and `weakX()`, so no field can contradict
  them.

- **The type needed a second axis §1.1 had no vocabulary for.** Every method in the sketch is
  visual — an x on the screen. A wrap budget and an ellipsis budget are not: they are sums of
  advance over a range of the string, which is order-independent and therefore still means
  something on a line whose glyphs are not in string order. So `advanceTo`, `indexForAdvance` and
  `fitEnd` are a documented second axis, and where `isSimple()` holds the two coincide — which is
  exactly why substituting one for the other is invisible until somebody types Hebrew into the
  field. The other additions are of a piece: the caret-stop table (`caretCount`, `caretIndex`,
  `caretOrdinal`) so a test can *enumerate* bidi caret behaviour rather than probe x values and
  hope to hit one; `matches`, so the staleness test is one call and not three field comparisons at
  each call site; a `selection` overload that fills a caller's buffer, for the drag that repaints
  at frame rate; and `uniform` (below).

- **§6's first open question is closed by contract, not by the measurement it asked for.** Content
  scale is excluded: `ShapedText` states it in the list of what makes a held value stale,
  `TextRuler.epoch()` states that it must **not** move for it, and the backend's memo key is
  (text, font, direction) with no device size in it, pinned by a test that asserts the key cannot
  be reached from a content scale at all. The mechanism that makes the promise keepable is that
  the shaper's scale is set to the face's own upem, so positions come back in font units and one
  multiply converts a whole run. The ADR's own cheap answer — key the cache by quantized size and
  re-shape — was rejected rather than deferred: it would miss the memo for every string in the
  process at exactly the moment a window crossed a monitor boundary, which is the worst moment
  available.

- **§1.4's invalidation was one input short, and the missing one had to be invented.** The ADR said
  a widget's held value would be invalidated by "the epoch it already watches for fonts and control
  size". That epoch cannot see the three things that actually matter here: a family rebound
  underneath `Font.DEFAULT_FAMILY` leaves the `Font` equal to itself, a background face arriving
  changes what a string shapes to, and an eviction *closes* a face whose glyph ids a held value
  still names. `TextRuler.epoch()` covers all three, is drawn from one process-wide counter
  (two rulers numbering independently would eventually both answer the same number, and a value
  shaped by one would then report itself current under the other), and is the third thing `matches`
  tests. The idiom is also not universal, which the ADR assumed it would be: `Label` holds a
  `String` and uses `matches`, while `TextField` and `TextArea` take their text from a model that
  builds a fresh `String` on every call, so they key on the model's version counter instead —
  `matches` compares text by identity first, and would otherwise pay a full character scan on every
  paint, every blink and every frame of a drag.

- **§6's second open question is closed the way it guessed: `PasswordField` bypasses shaping, and
  the reason is that its marks are drawn circles rather than glyphs.** `ShapedText.uniform` builds
  the masked line from one multiplication, with no glyphs, so the secret never reaches a shaper nor
  the memo a shaper keeps. The dividend was larger than the question implied. The mask *string* is
  gone entirely: the value's index space **is** the model's, so the count-preserving substitution
  the old code carried a paragraph of warning about is no longer a thing that can go wrong, and the
  caret, the click mapping, the selection band and the painted dots all come out of that one piece
  of arithmetic — the mark count is `caretCount() - 1` rather than a width divided by an advance.
  Cells are grapheme clusters rather than code points, so an astral character is one dot and the
  mask no longer leaks that it was astral. The one cost is that a display form with state of its
  own must say so: the held line's key is text, font and epoch, and cannot see a reveal toggle.

- **§5's "a face resolved per run changes what mixed strings look like" was real, and it has
  teeth.** `measure` walks code points and resolves a face per character; `shape` itemizes into
  runs and lets a `COMMON` character extend the run it follows. So the space between two Hebrew
  words is measured in Roboto and shaped in Noto Sans Hebrew, and the two faces disagree about how
  wide a space is: measured with the vendored faces when this ADR was closed, at 16 pt, Roboto's
  space is 3.97 pt against Noto Sans Hebrew's 4.32 pt. A line of Hebrew words shapes wider than it
  measures by **about 0.19 pt per added word seam**: one seam gains 0.03 pt, fourteen gain 2.52,
  and a 199-character line measures 1586.46 pt against 1593.76 shaped. A fraction of a point, and
  it **accumulates linearly**, which is what makes it a bug rather than a hairline. It broke
  `TextArea`'s horizontal scroll extent, because that widget scans the whole document for its
  widest line and therefore must `measure` rather than shape: one `ShapedText` per line of the
  buffer is a second copy of the text, and the extent is the one question that has to look at every
  line. An extent short by that much is not a rounding error — `ensureCursorVisible` asks for a
  scroll the clamp refuses, and the caret is painted outside the clip and simply vanishes when the
  user presses End. The fix is that the extent is the larger of the measured scan and the widest
  line the widget has **actually shaped**, which is exactly enough because reaching a line is what
  shapes it. The widget test pins it in whole points with a ruler built to disagree by one point
  per space: 59 characters, 19 spaces, 571 measured against 590 shaped, against a clip clearance
  of 1 pt. The backend test pins only the direction and the growth — a one-seam gap above zero, an
  eleven-seam gap more than five times it — and deliberately no amount, because the amount is two
  faces' opinion of a space and would move with either of them.

- **The largest gap in the delivery was a promise §1 made about the `String` overload**, and §8
  closes it; what follows is the state this ADR was closed in, kept because the shape of the
  mistake is the argument for §8. Decision 2
  and Phase 2 said `drawText(String, …)` would survive by *wrapping the shaped form*. It does not:
  it is still the per-code-point walk, re-keyed from code points to glyph indices by Phase 1 and
  otherwise untouched, and `measure` is still the per-code-point sum beside it. Keeping the two
  pairs apart is what let this land without touching a single existing caller, and the price is
  that **a complex script is drawn correctly exactly where a widget holds a `ShapedText`**:
  `Label`, `TextField` and `TextArea` do. Everywhere else — `Button`, `Checkbox`, `RadioButton`,
  `ComboBox`, `MenuBar`, `PopupMenu`, `SegmentedControl`, `TabbedPane`, `Spinner`, and
  `TextField`'s own placeholder, which is drawn through the string path while the field's content
  is not — an Arabic caption comes out as unjoined letterforms in string order: legible enough to
  read as a font problem, and not text. The `ar` and `he` bundles ship anyway, and that has to be
  stated here rather than inferred: they are correct as translations and are drawn correctly by the
  three widgets that hold a value, which is not most of a screen. Moving a widget across costs it
  the same three things each time — hold a value, draw it, invalidate it on text, font and epoch.

- **Phase 2's verification plan named a suite that does not exist.** There are no golden
  screenshots in this repository; `scripts/screenshot.sh` drives the demo to produce images for the
  site, and nothing in `check` compares them. So the acceptance was rebuilt out of two tests that
  do run there: the advance parity above, which is exact and reviewable as numbers, and a paint
  test on a real GL context that renders one string through both overloads and compares the
  framebuffers byte for byte — for the shaped value, for the degraded one, and for a value with a
  zero-width format character in it. Phase 1's other prediction went the same way: `StbFontTest`
  and `GlyphMapTest` did not "stay green as they are", because both asserted the code-point key and
  had to be rewritten. Decision 5's packing claim did hold untouched, and the 21-bit field is now
  pinned against a maximum glyph index spilling into the size field above it. What the six phases
  added in total is eight test classes and 114 tests, of which the fifty-seven over `ShapedText`
  itself and the twelve bidi editing conformance cases need no native, no GPU and no font file.

- **Finding 3's Arabic row was one letter too strong.** Ten glyphs from seven code points,
  confirmed against the vendored face, and three of the ten turn out to be dot components that GPOS
  places and no cmap lookup can produce at all. But **nine** of the ten leave the cmap behind, not
  ten: alef joins only to its right, so a word-initial alef has nothing to join to and keeps its
  isolated form, which is precisely the glyph the cmap gives. That is not shaping failing to
  happen. The table's other three rows held exactly as measured: the ksha conjunct is three code
  points and one glyph, `हिन्दी` is six code points and five glyphs in drawn cluster order
  `0 0 2 4 4`, and pointed shalom is seven and seven with three zero-advance marks that cost no
  caret stop.

- **A HarfBuzz script tag is the capitalised ISO 15924 code, and getting it wrong is silent.**
  `deva` is not a registered script, so HarfBuzz selects the generic shaper and Devanagari comes
  back unreordered and unligated with no error raised anywhere — the code-point pipeline this ADR
  was written to replace, reinstated invisibly. It cost this work a wrong result once. The tags now
  come from the `HB_SCRIPT_*` constants and never from a hand-written string, and a test exhibits
  both spellings through one face rather than describing the trap.

- **The degraded path is per run and lives on the interface, not per value and in the backend.**
  Decision 4 imagined the ruler handing back a value built by today's walk where the native cannot
  load. What shipped is finer and does more work: `NO_GLYPH` is a **per-cluster** sentinel, so a
  face HarfBuzz will not open degrades alone inside an otherwise shaped line, and a colour-emoji
  cluster — a bitmap strike with its own advance, which never was a glyph — takes the same branch
  with the same one test in the paint loop. A whole-value "is this shaped" flag could not express
  either case. And `shape` is a **default method** on `TextRuler`, so a fake ruler with no native
  inherits a value that is already bidi-reordered and correctly clustered: that is what lets the
  bidi caret conformance suite run in `limn-toolkit` with no window, no GPU and no font file, which
  is the answer to §5's first risk. Bidi costs no native at all, so with no shaper the *order*
  stays right and what is lost is joining, conjuncts and mark attachment.

- **The IME was not in the ADR, and it turned out to be a shaping question.** A preedit spliced
  into the committed text at the caret has to be shaped **once**, as one line: Arabic and Indic
  join across exactly the seams the splice cuts, so three measurements of three pieces are three
  wrong numbers, and the committed tail does not begin where the preedit's own advance ends. The
  multi-box `selection` turned out to be precisely the primitive the preedit underline and the
  converting block's highlight both need, asked of a sub-range of that same shaping, so neither can
  drift from the run it sits in.

- **Line breaking became locale-sensitive, which ADR 006 §4 had ruled out.** Phase 4 said
  `BreakIterator` and did not say under which locale; the answer is the UI language and not `ROOT`,
  because the JDK's Thai dictionary is reachable only through a Thai locale — twelve characters of
  Thai offer two break opportunities under `th` and none at all under `en`. Two smaller
  consequences of the same shape: a greedy breaker walks one shaping with `fitEnd` and has to take
  one cluster anyway when not even one fits, and an ellipsis has to re-shape what it kept and may
  have to cut again, because `advanceTo` is a budget and not a promise about a substring — the
  forms on both sides of a cut change when the cut is made, and a `Label` that overflows its box by
  a hair is a `Label` whose ellipsis is clipped.

- **Where the four faces sit in the fallback chain is load-bearing, and §2 did not say.** They join
  behind the pan-CJK face and ahead of the Roboto last resort, because all four carry Latin digits
  and punctuation as well as their own script: ahead of the CJK face they would start drawing the
  Latin of any line whose primary lacks it. They also load lazily, in the same background batch as
  the CJK and colour-emoji faces, and the reason is the trigger rather than the size — 531 KB for
  all four is nearer Roboto Regular's 349 KB than the pan-CJK face's 16 MB, but nothing at startup
  knows whether an application will ever draw one of these scripts, and the moment that becomes
  known is the first code point no resident face covers. Their arrival bumps the epoch, so a line
  that shaped into `.notdef` boxes re-shapes against the face that has now arrived, which is the
  same curing the CJK face already relied on.

- **§3's costs held; §3's API line did not.** 3.47 MB of HarfBuzz natives across the six
  classifiers and 0.31 MB of Java, against the predicted 3.5 and 0.3; 531 KB of faces against the
  predicted ~0.5 MB, with Finding 6's readings intact (Arabic 229 KB, Devanagari 239 KB, Hebrew
  26 KB) plus the Thai face at 37 KB, which Finding 6 counted as a script and never priced. "One
  new public type and one new method on two published interfaces" was the understatement:
  `TextRuler` also gained `epoch()` and a second `shape` overload; `Canvas`'s new method is a
  `default` one, because an abstract method on a published interface breaks every implementation
  outside this repository as well as every recording canvas inside it; `ShapedText` carries
  `Position`, `Affinity`, `Caret`, `Span`, `Run`, `Direction` and a `Builder`; `TextEditModel`
  gained `caret()`, `setCaret`, `moveVisualLeft` and `moveVisualRight`; and `TextField`'s three
  protected display hooks (`displayText`, `displayPrefix`, `displayWidth`) were **replaced** by
  `shapeDisplay` and a `ShapedText`-taking `paintDisplayText`. That last one is a source-breaking
  change for a subclass outside this repository, and "no existing caller changes" did not cover it.

### 7.1 What is still not done

Stated so that each absence reads as a decision rather than an oversight.

- **Layout mirroring**, exactly as §4 scoped it out: direction as a layout axis, leading and
  trailing insets, mirrored ink, arrow-key semantics, popup anchoring. Correct right-to-left text
  inside an unmirrored layout is what ships, and it is a real intermediate state rather than half a
  feature. `ShapedText.Direction.of` takes the neutral fallback as a *parameter* for this reason:
  the right answer for a string with no strong character at all (`"42"`, `"(...)"`) is the
  direction of the surrounding interface, nothing in the drawing package owns that yet, and that
  parameter is the seam where mirroring attaches when it is written.
  **It is the largest item on this list now that §8 has closed the one at the bottom of it**, and
  the user-facing copy was rewritten to say so in every language the README ships: "complex scripts
  stop at the text widgets" was the trade-off a reader had to weigh before §8, and the one left to
  weigh is that a correctly drawn Arabic screen is still laid out left to right.
- **Vertical writing.** Unchanged: a second axis through the layout system, not a direction flag.
- **Per-run font features and variable-font axes**, and **no shaping language either**. The shaper
  is told the script and the direction and nothing else, so the forms are the script's defaults and
  `liga` plus the script's required features are what a run gets. §1.3 listed language as one of a
  run's four properties and it is not passed; the epoch contract already reserves the invalidation
  a language switch would need, which is the only part that would have been expensive to retrofit.
- **Justification, hyphenation, kinsoku.** Phase 4 replaced a hard break with a real line break; it
  did not make the line beautiful.
- **Collation and locale-aware case mapping.** Unchanged from ADR 006 §4.
- **Bold and italic in the four new scripts.** One Regular face each, so a bold Arabic caption
  resolves to that same regular face and nothing synthesizes a weight.
- **Soft wrap in `TextArea`**, still absent as it was before this work; `Label` is the only widget
  that breaks lines.
- ~~**The `String` draw path**, above — the one that decides how much of a right-to-left UI is
  drawn correctly today, and the first thing to fix for anyone who ships an `ar` or `he` locale.~~
  **Done, in §8.**

---

## 8. Decision 2, kept

Dated 2026-08-31, after §7 was written. This section is not a new decision: it is §1 decision 2 and
Phase 2 implemented, and §7.1's first outstanding item closed. It is recorded here rather than in a
new ADR because nothing about the decision changed — only whether it had been carried out.

**What was done.** `GlCanvas.drawText(String, …)` shapes through the installed `TextRuler` and calls
`drawText(ShapedText, …)`; `GlCanvas.measureText` and `ShapingRuler.measure` answer from the same
shaping. The per-code-point paint loop is deleted, so there is one glyph-emitting loop
(`GlCanvas.java:834`) rather than two, and its one fallback — `drawClusterCharacters` for a
`NO_GLYPH` cluster or an unresolvable face — is reached identically from both overloads. Every
`Button`, `Checkbox`, `ComboBox`, `MenuBar`, `PopupMenu`, `SegmentedControl`, `TabbedPane`,
`Spinner`, chart label and `TextField` placeholder now draws Arabic joined and Hebrew ordered.

**The merge dropped one thing and hoisted another, and both are consequences rather than tidying.**
The deleted loop carried a per-glyph size-ratio correction for pen drift, and it is **gone rather
than moved**: it existed because the pen summed advances read at the atlas's *quantized* size, and a
shaped value carries unquantized positions computed at the exact font size, so there is no
quantization left for it to correct. That the two loops had drifted far enough for one of them to
need a correction the other did not — with `measure` a third answer beside them — is the argument
against ever splitting them again, and one loop cannot drift from itself. Going the other way, the
empty-clip and `MIN_DEVICE_FONT_SIZE` early-outs are **duplicated into the `String` overload** rather
than left to the loop below it, because a shaping now sits between the two: both decide from canvas
state alone that nothing will be drawn, and §8.1's cold frame is precisely what a frame that draws
nothing would otherwise pay to find that out. Everything else the old loop carried crossed
unchanged and still carries the comment saying why — the colour-emoji strike, the degraded no-native
path, the snap-versus-rotated split, the clip and batch state, and `PasswordField`'s arithmetic,
which never reaches a shaper at all (§7).

**The canvas reaches the ruler through `limn.graphics.TextRulers`**, the registry `LwjglBackend`
already installs into (`LwjglBackend.java:115`), and that is the seam rather than a ruler of the
canvas's own for one reason: a widget lays out through the registry and paints through the canvas,
and the point of routing both through the shaper is that those two passes agree. One instance means
one memo — the string measured during layout is already shaped when the paint asks for it — and one
epoch, so a face arriving between the passes invalidates both or neither. A test that installs a
different ruler gets that ruler's idea of the text on screen as well as in the layout, which is the
honest behaviour; `TextRuler.NONE` is the single exception, because a painter given zero widths
would stack a whole string at the origin, and a canvas always has a `FontStore` it can shape with.

**`measure` answering from `shape` cures §7's accumulating seam gap at its root.** The 0.19 pt per
Hebrew word seam is still a fact about two faces and a fallback rule, and is still pinned in the
backend — against `FontStore.measure`, which is where the fact lives — but it can no longer reach a
widget through this ruler, because the number layout is built from and the number paint walks are
the same number. `TextArea`'s `shapedWidthFloor` **stays**, and the reason is narrower than it was:
`TextRuler` does not promise that `measure` agrees with `shape`, and its own default `shape` cannot
honour it, losing the kern at every cluster seam. A widget does not know which ruler it has, and
the failure mode of assuming is a caret painted outside the clip. (**§8.2 widens that reason back
out**: the scan that feeds the extent no longer asks `measure` at all, because a document-wide scan
through a shared memo is a cliff, so the gap is deliberate on the shipping ruler too.)

**The parity suites needed an unshaped baseline, and now name it as one.** `ShapedAdvanceParityTest`
compared `shape` against `measure`; with `measure` shaped that is a value compared with itself, and
the suite would have reported that shaping changes nothing while having stopped being a comparison.
Every such baseline is taken from `FontStore.measure` — the per-code-point walk itself — through a
helper called `unshaped`, with the reason at the call site. `GlShapedTextPaintTest` had the same
problem in pixels: it held the two overloads to the same framebuffer and there is now one loop
behind both. Its comparisons were re-aimed at the two seams that can still disagree — the glyph
route against the `NO_GLYPH` route, and the string overload against a *different installed ruler*,
which is what proves the canvas asks the registry rather than deciding for itself.

### 8.1 What it costs, measured rather than asserted

On this machine (Apple Silicon, JDK 21.0.12), 188 shipped UI strings of mean length 12.1 at 13 pt,
each measured once for layout and drawn once: best of 50 frames after 3000 warm-up frames, each path
in its own JVM so neither pays for the other's allocation or shares its call sites.

| | old | new |
| --- | --- | --- |
| steady frame, memo warm | 0.025 ms | **0.004 ms** |
| cold frame, memo empty | 0.036 ms | 0.49 ms |

**The steady frame got faster, by about 0.02 ms.** That was not the expected result and it is worth
saying why it happens: a memo hit is a hash lookup at some 10–30 ns, and the per-code-point walk it
replaces is a dozen cmap and kerning lookups at some 65 ns. Shaping every measurement is cheaper
than measuring every measurement, once the shaping is memoized, because the memo is what the walk
never had. Run in one process with both paths compiled the margin narrows to about 2× rather than
6× — shared call sites go bimorphic — and the direction of the result does not change under
reversal of the measurement order.

**The cold frame is 13× slower**, +0.45 ms once: the first frame of a window, and the frame after a
face arrives and bumps the epoch. Shaping proper is some 2.6 µs a string, most of it HarfBuzz and
the `java.text.Bidi` the itemizer builds. That is the honest price of this change and it is paid
once per string per epoch.

The lever that would move it was found and deliberately not pulled, which is worth recording so the
next person does not have to find it again. Some 166 ns of that cost is the itemizer's own
`new Bidi(...)`, and it could be skipped for a paragraph containing no right-to-left character under
a left-to-right base. Unlike the `Direction.of` scan below, that `Bidi` does not answer a question —
it produces the **run structure the whole itemizer is built on**, so a fast path there is a second
itemizer rather than a second route to one value, and a second itemizer that disagrees with the
first about a run boundary is a wrong line with nothing to compare it against. It is a change with
its own tests, not a clause in this one.

Only the ruler work is timed, because it is the whole delta: downstream of it the atlas lookup and
the quad emission are one quad per glyph either way, and timing those needs a GL context.

That table was not the first reading. Two things stood between, and both are the interesting part.

**A memo sized for transient callers is not a memo sized for every measurement.** §1.4 sized it at
64 entries for the chart axis that has nowhere to hold a value. With `measure` routed through
`shape`, every string on the screen arrives twice a frame, and an access-ordered LRU walked
cyclically over more strings than it holds misses *every* time: 188 captions against 64 entries cost
**0.49 ms a frame, 2600 ns per string**, re-shaping the whole screen twice — 20× the old path where
512 entries are 6× cheaper than it. The failure is a cliff and not a slope, which is why the
capacity is now derived from a screen's working set rather than from what a chart needs.

**Resolving the base direction sits in front of the memo, so it became the cost.** `Direction.of`
built two `java.text.Bidi` objects per call, and every `shape(text, font)` calls it before it can
build a key: **318 ns per string, against 12 ns for the lookup behind it** — the whole of the
regression, and none of it shaping. It now walks the string to the first character that decides
anything, which is rule P2 as written, and defers to the `Bidi` pair only for a paragraph carrying an
embedding, an override or an isolate initiator, because P2's subtlety is that it skips what lies
between an isolate and its matching PDI. That is 5 ns. The scan is held to the rule it replaced over
every ordered triple of a corpus with one code point per directionality class, both isolate families
and astral strong characters on each side; the case that a hand-written list of fixtures missed, and
this corpus caught, is the **paragraph separator** — rule P1 ends the first paragraph at a newline
and P2 runs over that one only, so a scan that read past it called `"42\nabc"` left-to-right where
the rule calls it neutral.

**A methodological note, because it changed a conclusion.** 300 warm-up frames were not enough. The
old path converges there and the new one does not — its inline tree is deeper, running
`measure` → the default `shape` → the ruler's `shape` → `Direction.of` → a hash lookup — and at 300
frames it measured 0.038 ms against the old path's 0.026, which reads as a 1.5× regression. At 3000
it is 0.004 ms. A benchmark that warms both sides the same number of iterations is not warming them
equally, and the number it produces is the JIT's schedule rather than the code's cost.

### 8.2 Three corrections, found in review of §8

Dated 2026-08-31. All three are consequences of §8 rather than of the six phases: making
`drawText(String, …)` shape moved two things that had been true because it did not, and a third was
in the optimization §8.1 had to add to pay for it. Each is stated as cause, not symptom.

**A mark placed from a prefix width stopped naming the character it marks.** The mnemonic underline
took its two edges from `measure(prefix)` and `measure(prefix + 1)`, which is the x of the k-th
drawn character only while drawing walks the string left to right in logical order. Once the title
is shaped, an Arabic or Hebrew menu title paints its first letter at the *right* end of the run, and
the rule was drawn at the left end — measured on this machine at 13 pt against the vendored Arabic
face, 18.2 pt away, under the last letter of the word instead of the first. Interior indices were
wrong in width as well as position, because a letter shaped at the end of a prefix takes its final
form and the same letter inside the whole word takes its medial one. Both edges now come off the
line's own shaping (`MenuInk.java:68`) — the same call the canvas makes, into the same memo, at a
hit. The span is the cluster's, taken from the caret stop the index falls on to the next stop rather
than from index to index plus one: a ligature is one cluster with one stop, so under the old
arithmetic an `F` mnemonic on an `Office` menu marked a zero-width nothing, and there is nothing
narrower than a cluster on the line to mark. It collapses to the previous numbers on Latin, which
`MenuInkTest` pins alongside the right-to-left and ligature cases.

**A document-wide scan may not run through a bounded, shared shape memo.** `TextArea.contentWidth`
scans every line to size the horizontal extent, and with `measure` answering from `shape` that scan
became a scan of *shapings*: it walks its keys cyclically, so past `MEMO_ENTRIES` it misses on every
line every time, and it re-runs on every keystroke, because an edit drops the cached extent and the
`ensureCursorVisible` that follows reads it straight back. Measured on this machine (13 pt, ~57-char
prose lines, best of 30 after warm-up), the scan alone: 1000 lines **4.30 ms** against **0.27 ms**
unshaped, 5000 lines **21.98 ms** against **1.38 ms** — 16× either way, and 22 ms per character typed
is a dropped frame and a half. The eviction is the other half of the cost, and it lands on widgets
that did nothing: 180 unrelated captions repaint in 0.008 ms warm and **0.324 ms** after one
memo-routed 1000-line scan, 40×, on every keystroke. Raising the capacity does not fix this, it
moves the cliff, so the fix is that such a scan does not come to the memo at all.
`TextRuler.scanWidth` (`TextRuler.java:80`) is the width for a caller measuring far more strings than
it draws: cheap, memo-free, and explicitly not promised to equal the shaped width. The backend
answers it from `FontStore.measure` (`ShapingRuler.java:174`), the per-code-point walk that is still
there one layer down; every other width still comes from `shape`. `TextArea` (`TextArea.java:383`)
asks it for the scan, and `shapedWidthFloor` — which §8 kept as a defence against rulers in general —
is now load-bearing for the shipping one too, which is the honest trade and is documented at both
ends. Below the memo's depth the old route is the *faster* one (a memo hit beats the walk, as §8.1
found), so this is a cost paid on short documents to remove a cliff on long ones.

**The first-strong scan skipped what this JVM has no directionality for, which is not the same as
what has none.** `Direction.of`'s hand scan (§8.1) fell through `default:` for
`DIRECTIONALITY_UNDEFINED`, so it disagreed with the two-`Bidi` rule it replaced for every unassigned
code point in a default-right-to-left range: Unicode gives an unassigned code point the default of
the range it sits in and `java.text.Bidi` honours that. Sweeping all 0x110000 code points against
the rule found 0 disagreements among assigned code points and 4908 single-character ones among
unassigned. Garay is the live case rather than a hypothetical — an RTL script added in Unicode 16
and therefore unassigned in this JDK — and a line of it resolved as a left-to-right paragraph:
caret at the wrong edge, Home and End on the wrong side. The scan now defers to `Bidi` there
(`ShapedText.java:1647`), for the same reason it already defers on an embedding or an isolate: it may
answer only where it knows what `Bidi` knows. The conformance corpus could not have caught this,
and the reason is worth recording: it is built as one representative code point per directionality
class, and an unassigned code point has no class, so the corpus was structurally incapable of
containing the one input class where the two answers differ. It now carries two — U+05EB from a
default-`R` range and U+0378 from a default-`L` one, with an assertion that both are still
unassigned, since a JDK that assigns either silently reopens the hole.
