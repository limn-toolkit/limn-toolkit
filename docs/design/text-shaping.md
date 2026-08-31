# Text shaping

Background for `limn.graphics.ShapedText`, `TextRuler.shape`, and the backend that implements
them over HarfBuzz. Read this before changing anything that turns characters into positioned
glyphs, and before adding a widget that wants to know where a character sits on screen. The
decision, and what was measured before it was taken, is
[ADR 031](../adr/031-a-run-of-text-is-shaped-once-and-the-widget-holds-the-result.md).

## There is one text path, and the `String` overload is the front door to it

`drawText(ShapedText, …)` walks glyphs a shaper has already chosen and already placed, and every
glyph the toolkit paints comes out of that one walk. `drawText(String, …)` shapes through the
installed `TextRuler` and calls it; `measureText` and `TextRuler.measure` answer from the same
shaping. **A caption is drawn correctly wherever it is drawn**, whether or not the widget drawing it
holds a value, and it is laid out to the width it is painted at because both are one number.

For most of the work that introduced shaping it was two paths. That never reached a release, and
what it cost while it stood is written down anyway, because it is the argument against splitting
them again. A widget that held a value drew Arabic joined; a widget that handed the canvas a
`String` drew the same words through a code-point walk, as unjoined letterforms in string order —
legible enough to look like a font problem, and not text — so a screen was correct in the few
widgets that hold a shaped line and wrong in every caption around them. The two loops had also begun
to drift where they overlapped: one summed advances read at the atlas's quantized size and corrected
the drift with a per-glyph ratio, the other used unquantized positions, and `measure` was a third
answer again. One loop cannot drift from itself.

**Holding a `ShapedText` is now an optimization rather than a correctness question.** A widget that
holds one pays nothing per frame; a widget that hands over a `String` pays a memo lookup on the
ruler, some tens of nanoseconds. What holding buys beyond that is the geometry — where a caret goes,
which index is under an x, which boxes cover a range — and no `String` API can answer those at all.

## The value, and why the widget holds it rather than the ruler

`ShapedText` is immutable, is produced only by `TextRuler.shape`, and answers every geometric
question a widget used to answer with a prefix measurement: where an index draws, which index is
under an x, which boxes cover a range, where a budget runs out. That is not an optimization of
prefix measurement, it is a replacement for it. Under a shaper the width of the first *n*
characters of a string is not the width of anything on screen — inside their line those characters
join, ligate, kern and reorder differently than they do alone — so a prefix width is not a slower
way to place a caret, it is a wrong one. The type exists so the wrong operation is unavailable.

Holding it is the other half. Shaping is the expensive part of drawing text and the answer is
needed twice per frame, once to lay out and once to paint, and the two have to agree. `matches`
is the whole staleness test: the text, the `Font` (a value, so a control-size step or a theme
change is caught by the same comparison), and the ruler's epoch. A widget whose text arrives as a
freshly built `String` every call — `model.text()`, `model.lineText(line)` — keys on its own
version counter instead, because `matches` compares the text by identity first and would otherwise
pay a character scan on every paint, every blink and every frame of a drag.

**Nothing above the backend names HarfBuzz**, which is the same confinement GLFW, OpenGL and stb
already live under and enforced by the same architecture rule. What crosses the seam is a value
whose accessors return nothing but an `int`, a `float`, a `String`, a `Font`, a `TextMetrics` or a
record of those — a face is an `int` the producing ruler assigns and only that ruler interprets.
That is structural rather than a rule someone has to remember: it is what lets the type live in a
module that cannot import a graphics library, and it is what makes holding one across frames safe,
because keeping a value alive cannot keep a font file mapped.

## Two axes, and substituting one for the other is the bug

*Visual* questions speak in x on the screen: `caretAt`, `caretX`, `hitTest`, `selection`,
`caretLeft`, `caretRight`. *Logical* questions speak in advance consumed by a range of the string:
`advanceTo`, `indexForAdvance`, `fitEnd`. A wrap budget and an ellipsis budget are logical, which
is why they still mean something on a line whose glyphs are not in string order — a sum does not
care what order it was added in. Where `isSimple()` holds the two axes coincide, and that is
exactly why using one for the other is invisible until somebody types Hebrew into the field.

**A budget is not a promise about a substring.** `advanceTo` says how much of *this* line's width a
prefix accounts for; it does not say how wide that prefix would be shaped on its own, and under a
shaper the two differ. So anything that cuts a line — an ellipsis, a wrapper — decides *where* from
the whole paragraph's shaping and then re-shapes both pieces before painting them, and may find the
cut piece a hair wider or narrower than the budget promised. The joining forms change at the cut, a
ligature that spanned it is gone, and so is the kerning at the seam.

**A mark placed under or beside a character is a visual question too, however small it looks.** A
mnemonic underline used to be positioned from a prefix width and a prefix-plus-one width, which
names the k-th drawn character's x only while drawing walks the string in logical order — true right
up until the `String` overload began shaping, and false for every right-to-left title afterwards,
with the mark landing a whole word away under a different letter. Both edges now come off the line's
own shaping, widened from the caret stop the index falls on to the next one rather than from index
to index plus one: a ligature is one cluster with one stop, so the character's own bounds may not
exist, and the smallest thing on the line that can be marked is the cluster it is drawn in.

`isSimple()` is derived by the builder from the runs and glyphs it was actually fed, never asserted
by its producer. Itemization can change; a flag that outlived the itemization that set it would
send every fast path in the toolkit down the wrong route for the one string that needed the slow
one.

## Itemization, in the order the pieces depend on each other

Inside one `shape` call:

1. `java.text.Bidi` over the whole string gives an embedding level per character, and the runs of
   equal level, in logical order.
2. Each bidi run splits by script. `COMMON` (spaces, digits, most punctuation) and `INHERITED`
   (combining marks) extend the run they land in rather than opening one — a comma between two
   Arabic words belongs to the Arabic run, and a mark belongs to its base.
3. Each script run resolves a face **once** and extends while that face covers what follows.
4. Each (face, script, direction) run goes to HarfBuzz, with the **whole string** as context and
   only the run as the item, so joining forms survive a boundary the itemizer drew for a font
   change.
5. `ShapedText.Builder` takes the runs in logical order and does rule L2, the reordering.

Steps 2 and 3 cannot be reordered and cannot be replaced by the per-character fallback the
code-point walk uses: shaping needs the face **before** any glyph exists, and a run split mid-word
by a per-character decision shapes as two words.

Step 4 is where the caret is won or lost. HarfBuzz reports clusters as offsets into the buffer it
was handed, and every run boundary moves that origin; passing the whole string as context makes the
offsets absolute by construction, and `Builder.glyph` rejects an offset outside the open run, so a
regression that reintroduced the shift fails at the call that made it instead of moving every caret
in the toolkit by one character.

Step 5 is deliberately in `limn-toolkit` and not in the backend. It is the highest-risk arithmetic
here and it needs no native, no GPU and no font file to test, so bidi caret geometry — the part
that looks right in a screenshot while being wrong — is pinned against known cases with a fake
ruler. It is also the single door both fillers come through, which is what stops the shaped path
and the degraded one disagreeing about where a caret goes.

## A face resolved once per run is a visible difference, not an internal one

A per-character fallback can draw one Cyrillic character inside a Latin word from a different face
than its neighbours. The shaped path cannot: the run holds one face throughout. Per-run resolution
is the more correct of the two and it changes what mixed strings look like, so a screen that mixes
scripts inside a word is a screen to look at after touching itemization. The per-character rule is
not gone — it is what a glyphless cluster is drawn by, and what `FontStore.measure` still does one
layer under the ruler — but nothing lays out or paints a whole line through it any more.

It also means **a per-code-point sum and a shaped width are allowed to disagree**, and they do. A
Hebrew line with Latin spaces in it shapes wider than that sum, per seam, so the gap grows with the
line. This is why the backend's `measure` answers from its `shape` rather than beside it: the
number a widget lays out from and the number it paints from are one number, and the divergence
cannot reach a widget through this ruler at all.

It can still reach one through another ruler, and the interface says so rather than promising
otherwise: `TextRuler`'s own default `shape` measures a cluster at a time and loses the kern at
every cluster seam, so any ruler inheriting it disagrees with its own `measure` by construction. A
widget does not know which kind it has. `TextArea` therefore still floors its horizontal extent with
the widest line it has actually shaped — the failure mode of assuming is a caret painted outside the
clip, and the floor is enough precisely because reaching a line is what shapes it.

And it reaches one **deliberately**, through the third width on the interface. `scanWidth` is for a
caller measuring far more text than it will draw — a scroll extent that must look at every line of a
document — and it is allowed to be the cheap per-code-point answer, because a width that agrees
exactly with the shaping is precisely the width that costs a shaping. It is the one width a ruler
must answer without remembering: see the memo below for why a document-wide scan cannot be allowed
to decide what a shared cache holds. Everything else asks `measure`, which shapes.

## Two caches, for two lifetimes

The widget holds its own `ShapedText` and re-shapes only when `matches` says so. That is where the
win is: a `Label` nobody is touching re-shapes never.

The backend memoizes `shape` in an access-ordered LRU behind that, and since `measure` and the
`String` draw path both go through `shape`, **every string on the screen arrives here twice a
frame** — once to lay out and once to paint. It is keyed by text, font and base direction, and
**not** by device size: shaped positions are unquantized logical points, so a window dragged to a
2× display re-rasterizes glyph bitmaps and re-shapes nothing, and a quantized device size in that
key would miss the memo for every string in the process at exactly that moment.

Its capacity has to cover a screen's working set, and the failure below that is a cliff rather than
a slope: an access-ordered LRU walked cyclically over more strings than it holds misses *every*
time, so a memo one caption short of a screen re-shapes the whole screen, twice a frame. Sizing it
for the transient callers alone was measurably that cliff, which ADR 031 §8.1 records.

**It is not a document cache, and no capacity would make it one.** A scan over every line of a long
buffer walks its keys cyclically past any depth the memo is given, so raising the number only moves
the cliff; and because the memo is shared, the scan evicts the strings that *are* on the screen, so
one widget's scan becomes every other widget's cold repaint. Editing a long document re-runs that
scan on every keystroke. That is why a scan of that kind does not come here at all: `scanWidth` is
the width taken without shaping and without remembering, and a scan of that kind is required to
ask it.

Neither cache is optional. Without them this is slower than what it replaces on every frame that
draws text, which is every frame.

**Resolving the base direction sits in front of the memo, so it is on the hot path too.** Every
`shape(text, font)` asks `Direction.of` before it can build a key, which made that method, and not
the shaping, the dominant per-string cost of a text frame once `measure` started coming through
here: two `java.text.Bidi` constructions, against a memo lookup an order of magnitude cheaper. It
now
walks the string to the first character that decides anything — rule P2 as written — and falls back
to the `Bidi` pair only where the rule is subtle, which is a paragraph carrying an embedding, an
override or an isolate — **or a code point this JVM has no directionality for at all**, which is a
third kind of subtlety and the one a corpus is least likely to contain. Unicode gives an unassigned
code point the default of the range it sits in, so every hole in a right-to-left range is strong to
the algorithm while `Character.getDirectionality` reports nothing; skipping it would substitute
"unassigned in this JDK" for "not strong" and resolve a line of a newly-encoded right-to-left script
as left-to-right. The scan and the rule it replaced are held to the same answer over every ordered
triple of a corpus with one code point per directionality class, both isolate families, the
paragraph separator, and two unassigned code points — one from a default-right-to-left range and one
from a default-left-to-right one, because a corpus built one-per-class structurally cannot express
the class that has no class. A shortcut through a Unicode algorithm is a second rule unless
something keeps it honest.

## The epoch is what a held value cannot see for itself

`TextRuler.epoch()` moves when the ruler would shape the same string differently: a family
registered, the catalog replaced as system enumeration finishes, the default family switched, a
face folded in or evicted. It must **not** move for a content-scale change, for the reason above.
Epochs are drawn from one process-wide counter rather than numbered per ruler, because `matches`
compares a held stamp against whatever ruler it is handed and two rulers numbering independently
would eventually both answer the same number.

The memo compares the epoch on the way *in* rather than being cleared when the change happens, so a
resolution change costs one counter increment and the first lookup afterwards throws the whole
generation away.

## The base direction is an input to shaping, not only to placement

A paragraph direction is easy to file under "where the line goes". It is not: it changes what the
line *is*.

The direction decides which bidi level a **boundary neutral** takes — the space at the seam between
a right-to-left run and what follows it. That level decides which run the neutral extends, and that
decides which face measures it. Under a left-to-right base the trailing space of an Arabic word is a
separate run at level 0 and is measured in the Latin face; under a right-to-left base it joins the
Arabic run and is measured in the Arabic one. The two faces disagree about how wide a space is, so
the line comes out a fraction of a point different.

Two properties of that matter, and they pull opposite ways. **It is bounded by the neutrals at the
paragraph's edge, and linear in them.** An interior neutral does not move at all — it already
extends the run it follows under either base — and neither does a leading one; but a *trailing* run
of neutrals sits at the edge in its entirety, so every one of them changes face with the base and
the difference is one face-difference apiece. Real lines carry a handful at most, which is why the
effect stays sub-point in practice; it is not constant, and an earlier reading of this that said so
was generalising the interior case. And **it is not zero**, which is why `ShapedText.matches`
compares the direction and why
`Widget.measure` keys its cache on the resolved one. A widget that held a line across a direction
change and was told it was current would keep drawing yesterday's direction: right-looking, and
wrong in every geometry query asked of it.

The memo in front of the shaper keys on the direction for the same reason, and always did.

`matches` is not sufficient on its own, because several widgets key their held lines by hand rather
than calling it — see [text-and-input.md](text-and-input.md) for why, and
[direction-axis.md](direction-axis.md) for what a hand-written key then owes.

## When the native is absent, and when a face is

`HarfBuzzShaper.isAvailable()` answers once for the life of the process. Where it says no, every
run falls back to a per-grapheme-cluster walk that carries the advance the code-point path would
have measured for it, kerning included, and reports each cluster as `NO_GLYPH` so painting falls
back to the path those positions came from. Bidi still runs: `java.text.Bidi` costs no native at
all, so **order stays correct even with no shaper**, and what is lost is what a shaper does: joining
forms, conjuncts, mark attachment, marks that should have had no advance of their own, and the
ligatures a Latin line would otherwise get. Carrying the walk's own advances rather than the
interface's per-cluster default is what makes the degraded width exact rather than approximate: a
Latin line comes out at precisely the width it had before any of this, not at one a hair wider at
every kerned pair.

The same filler covers one run at a time, which is what a face HarfBuzz declines to open becomes.
Both fillers reach the builder with the same kind of run and the same absolute cluster offsets, so
whichever of them produced a line, its caret geometry came out of the same arithmetic.

`NO_GLYPH` is one sentinel doing two jobs with one branch in the paint loop, and that is why it is
per cluster rather than a flag on the value: a colour-emoji cluster is a bitmap strike with its own
advance and always was, so a line can be partly shaped and partly not, which a whole-value flag
cannot express.

The four complex-script faces are vendored binaries like every other Noto face here, and optional
in the same way. A checkout where `scripts/fetch-fonts.sh` has never run has nothing to draw Arabic,
Hebrew, Devanagari or Thai with, and gets a correctly ordered row of `.notdef` boxes: a pipeline
that works with nothing to work with. They load in the same background batch as the CJK and colour
emoji faces, and the trigger rather than their size is the reason — 531 KB for all four is nearer
Roboto Regular than the pan-CJK face — because nothing at startup knows whether an application will
ever draw one of these scripts, and the moment that becomes known is the first code point no
resident face covers. Their arrival bumps the epoch, so a line that shaped into boxes re-shapes
against the face that has now arrived.

Their place in the chain is behind the CJK face and ahead of the Roboto last resort, and that is
load-bearing: all four carry Latin digits and punctuation as well as their own script, and ahead of
the CJK face they would start drawing the Latin of any line whose primary lacks it.

## What is deliberately not here

- **No layout direction.** `ShapedText.Direction` is the direction of a *run of text*, and there is
  no third constant meaning "decide later" — stating a direction and declining to state one are
  different `shape` overloads, so nothing can store the question in a field. Direction as a layout
  axis is a separate ADR that has not been written.
- **No shaping language.** The shaper is told the script and the direction and not the language,
  so the forms are the script's defaults. The epoch contract already reserves the invalidation this
  needs, which is the only part that would have been expensive to add later.
- **No feature list.** `liga`, `kern` and the script's required features are what the default gets;
  `ShapedText` carries no feature argument, and adding one later breaks nothing.
- **No justification, no hyphenation, no kinsoku, no vertical writing.**
