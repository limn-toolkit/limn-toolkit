# Text and input

Background for `TextField`, `TextArea`, `PasswordField` and `Spinner`. What happens between a
`String` and a glyph is [text-shaping.md](text-shaping.md); this is what the widgets standing on top
of it have to obey.

## Every horizontal coordinate comes from a held shaped line

Caret x, click mapping and the selection band all used to be the width of the first *n* characters
of the string. Under a shaper that width is not the width of anything on screen — inside their line
those characters join, ligate, kern and reorder differently than they do alone — so the operation
was deleted rather than made direction-aware. Every question is now asked of a `ShapedText` the
widget holds: `caretX` for the caret, `hitTest` for a click, `selection` for the band,
`caretLeft`/`caretRight` for the arrow keys. (`Spinner`'s inline editor is the one deliberate
hold-out; its own section below states the condition that keeps it legal.)

The two consequences a user sees are worth stating because they are the acceptance test. A click in
mixed text lands on the character under the pointer, either side of a direction boundary. And a
selection that crosses one paints as the several boxes it really covers, with untouched text between
them, because the characters between its two ends in the string are drawn outside its visual range.

## The held line, and what makes it stale

`ShapedText.matches` is the general staleness test — text, `Font`, ruler epoch — and it compares the
text by identity first. That is the right idiom for a widget holding a `String`, which is why
`Label` uses it. It is the wrong one here: `model.text()` and `model.lineText(line)` build a fresh
`String` every call, so `matches` would miss its identity fast path and pay a character scan on
every paint, on every blink and on every frame of a drag. So these widgets key on
`TextEditModel.textVersion()` instead, plus the same `Font` and the same epoch — the model's own
answer to "did the text change", one comparison, and a caret move does not bump it.

`TextArea` holds a **window** of shaped lines covering what the next paint will draw, plus a
one-slot spill for a line reached from off screen (the caret's line while it is still scrolled away,
the line under a drag that left the viewport). Bounded by the viewport and never by the document:
holding every line of a long buffer would be a second copy of the text, and the viewport is the only
part any repaint touches. Slots fill lazily, which is what makes a keystroke cost one shaping rather
than two — the edit drops the window, the scroll clamp shapes the caret's line into its slot, and the
paint that follows finds it there.

**The horizontal scroll extent is the trap in that arrangement**, and it is the one question here
that has to look at every line of the buffer rather than at the viewport. It cannot ask `measure`
for them. A shaping ruler answers `measure` by shaping into a bounded, process-wide memo, and a scan
over a whole document is the worst client such a memo can have: it touches every line in the same
cyclic order, so past the memo's depth it misses on every line every time; it re-runs on every
keystroke, because the edit drops the cached extent and the scroll clamp that follows reads it
straight back; and on its way through it evicts the captions belonging to widgets that did nothing,
which then repaint cold. So the scan asks `TextRuler.scanWidth` — a width that is cheap, remembers
nothing, and is explicitly not promised to equal the shaped one.

That last clause is the residue, and it has teeth. A Hebrew line with Latin spaces in it shapes
wider than a per-code-point sum, per seam, so the gap grows with the line. An extent built from
scanned widths alone stops short of where the shaping puts the caret, the clamp refuses the scroll
`ensureCursorVisible` asks for, and the caret is painted outside the clip and simply vanishes. So
the extent is the larger of the two answers the widget has, floored by the widest line it has
actually shaped — which is enough precisely because reaching a line is what shapes it, and a line
nobody has been near can only make the extent too small for text nobody can see yet.

## The caret is an index and a side

An index on a direction boundary is **two** points on the line: the character before it and the
character after it are drawn nowhere near each other, and which one the next keystroke lands at
depends on what is typed. So the caret is a `(charIndex, Affinity)` pair, and the side lives in
`TextEditModel` beside the cursor rather than in the widget. Two reasons, and the second is the one
that bites: every method that writes the cursor would otherwise need a paired assignment at each of
its call sites, and `undo` restores a cursor — an undo that put a correct index back on yesterday's
side draws the caret at the far end of the run, one keystroke later, with nothing to trace it to.

A caller that stores no side asks `caretAt` and gets both positions; drawing the strong one full
height and the weak one as a smaller mark is the usual answer, and drawing only one is a decision to
lie in the mixed case.

## Left is visual, and Ctrl+Left is not

`moveVisualLeft`/`moveVisualRight` take the shaped line and step one cluster **left or right on the
screen**, whatever direction the text under them runs. Everything else in the model — Home, End,
word movement, Backspace, Delete, Page, Up and Down — is **logical**, a step through the string,
because each of them has to name a contiguous range: `Shift+Home` makes a selection and a selection
is one range of the buffer.

The consequence is deliberate and it is stated rather than discovered: **in right-to-left text, Left
and Ctrl+Left move the caret in opposite directions.** That is what Windows and GTK do, and it is
the lesser of the two evils against a word jump that cannot describe what it selected.

Visual movement takes and returns a position rather than an index for the same reason the caret
carries a side. An index on a boundary occupies two points, and two presses in a row have to leave
from the point the first press arrived at; an index-taking form has to guess, and whichever side it
guesses, the caret walks out of one run and then jumps to the far end of the line on the next press.
That is non-determinism across two keystrokes, not imprecision.

## Two edges the geometry does not cover by itself

**A click past the end of the line.** `hitTest` clamps an x outside the line to the nearest cluster,
which is what a drag past the end wants and is wrong for a click in the empty space beyond it: on a
line ending in the direction opposite the paragraph's, the cluster nearest the right edge is not the
last character. Both widgets compare against the line width first and send that case to the logical
end.

**The selection buffer.** `selection(start, end, out)` writes into a caller-owned buffer, because a
drag repaints at frame rate and the list form puts a list and a record per box on the floor each
time. Its bound is exactly `2 * runs().size()`, and it throws on a short buffer rather than writing
what fits: writing fewer boxes than the selection has paints a band over some of the user's text and
leaves the rest unhighlighted, which reads as a rendering glitch rather than as the sizing bug it is.
The growth therefore lives at the call, not one screen away from it.

## The IME composition is one shaping, not three

The committed text with the preedit spliced in at the caret is shaped **once**, as a single line.
Three measurements of three pieces would be three wrong numbers: Arabic and Indic join across
exactly the seams the splice cuts, so the preedit's width inside the line is not its width measured
alone, and the committed tail does not begin where the preedit's own advance ends.

The multi-box `selection` turns out to be exactly the primitive the preedit underline and the
converting block's highlight need — asked of a sub-range of that same shaping, so neither can drift
from the run it sits in.

## The subclass seam is `shapeDisplay` plus `paintDisplayText`

`shapeDisplay` is the one place a subclass changes what a field draws and how wide it is, and its
contract is that **its index space is the model's**. Everything on the returned value takes and
returns offsets into the text it was handed, with no translation anywhere in the component. That is
what deletes the old prefix arithmetic instead of moving it one layer down: an override returning a
line shaped from some other string, with its own boundaries, puts the caret on a neighbour of the
character it edits and a click one mark away from the pointer.

Three things follow, and each has burned somebody:

- `font` is half the key the held value is refreshed against, so an override that shapes in some
  *other* font makes that value lie about when it is stale.
- An override whose display form depends on state of its own — a reveal toggle — has to call
  `invalidateDisplayLine()` when that state changes. The key is text, font and epoch, and none of
  those moves when a flag does.
- `paintDisplayText` holds the only call in the component that hands a *display line* to the canvas.
  That single call is the whole of the guarantee a masking subclass rests on, so an override that
  paints its own marks must not delegate to `super` except on the branch where it is revealing.

Two things are deliberately outside that seam, and both are worth knowing before extending it. The
**placeholder** is not a display line, is not in the model's index space, and is drawn straight —
which is also why a masked field shows its hint in clear, a hint being nothing to hide. And the
**composed line** an IME is editing is drawn on its own path rather than through
`paintDisplayText`, because it is the committed text with the preedit spliced into it and so is not
the model's index space either. A field that must never be typeset therefore refuses text input
outright (`acceptsTextInput`), which is what keeps that second path from ever existing for it, and
is a stronger statement than masking would have been anyway: a composition would echo the secret in
clear between the marks, in the OS candidate window, and into the IME's learning dictionary.

## Why the password dot is drawn rather than typed

It was a per-step glyph table once (MIDDLE DOT at the dense steps, BULLET at MEDIUM,
BLACK CIRCLE at the display steps), on the theory that a dot carries no optical size of
its own and needs a per-step ink ratio. The theory was fine; the glyphs were not.

Measured as fractions of the em, that ramp's ink was 0.11 / 0.11 / 0.20 / 0.90 / 0.90.
Nothing in the bundled faces sits between BULLET and BLACK CIRCLE, so the ramp was a
4.5× cliff in its own middle. Against a type ramp spanning only 11→19 pt, the painted
dot came out at 1.2 pt at the smallest step and 17.1 pt at the largest: the mask dwarfed
the text it stood for at one end and nearly vanished at the other, and MEDIUM (the
identity step) held the smallest solid mark in the toolkit.

Worse, `U+25CF` is absent from the bundled last-resort face. Roboto has `U+00B7` and
`U+2022` and no BLACK CIRCLE, so the large-step mask resolved through the per-code-point
fallback chain, which loads in the background. The first frame of a LARGE or XLARGE
password field in a fresh process painted a row of `.notdef` boxes. That was captured,
not hypothesised.

A drawn circle has no quantisation and needs no font: one ratio, and the type ramp
supplies the size. 0.36 em is where the faces normally used for password fields put
their bullet: Verdana's BULLET is 0.365 em, Helvetica's and Arial's BLACK CIRCLE 0.430,
the macOS system font's BULLET 0.191 and its BLACK CIRCLE 0.789.

### And the secret is never shaped

The masked display line comes from `ShapedText.uniform`: one cell per grapheme cluster, all the same
advance, no glyphs, one multiplication. Two things make that the right shape rather than a shortcut.

The content never reaches `shape`, which is the whole reason the override exists — a shaper resolves
faces and memoizes what it was asked, and that is a place a secret comes to rest.

And there is no second string. The mask used to be a real string of BULLETs, and the substitution
had to be exactly count-preserving or the caret drifted from the character it edited; an astral
character had to mask to one dot, and counting chars instead of code points would both break the
agreement and leak that the character was astral. A uniform line has the model's own index space, so
that entire class of bug is not expressible. The mark count is `caretCount() - 1` and the *i*-th mark
is centred at `(i + 0.5) * advance`, so no width is ever divided by an advance to recover a count,
and the geometry the caret uses is the arithmetic the ink uses.

**The trap that replaces the old one:** the masked line carries the secret as its `text()`, because
its index space has to be the model's. It must never reach `canvas.drawText`.

One thing that has nothing to do with shaping belongs beside it, because it is the same argument
about what geometry leaks: while masked, word-wise jumps and word-wise deletes degrade to
per-character ones. A caret that hopped word boundaries would let anyone watching count the words
inside the secret and read off their lengths, which the uniform dot pitch is otherwise careful to
give away nothing about.

## `Spinner` still measures prefixes, and the condition that makes that legal

Its inline editor places the caret and maps a click by measuring prefixes, linearly: a deliberate
hold-out, not an oversight. What makes it legal is not that the field is short. It is that the
widget formats what it displays and accepts only what it can format — digits, a sign, a separator,
a colon — so nothing in it joins, ligates or reorders, a prefix width really is a width, and a
handful of characters does not repay a binary search.

Paste is the hole in that argument, and it is deliberately unfiltered: a clipboard holding `12 px`
arrives as typed, and `Enter` keeps the number or `Escape` puts the old one back, because a paste
silently rewritten would be a field deciding what its user meant. So a pasted run of Arabic can sit
in the edit buffer with the caret placed by arithmetic that does not describe it — a transient state
in a widget whose *committed* value can never hold such a character, which is why it has not been
paid for.

What would end that is widening the accepted set, which is exactly what ADR 006 §2.4 proposes: a
locale separator, a locale's own digits, the sv-SE minus sign. That change moves the caret onto a
shaped line as part of itself, not afterwards.

## Lines, and the wrapping that is not here

A `ShapedText` is one line and carries no `\n`: splitting a paragraph is the widget's job, and a
line is the unit everything above is stated in. `TextArea` breaks only where the buffer does — there
is no soft wrap, long lines scroll horizontally, and a line is a paragraph. Soft wrap would make the
line count a function of the box, which is a different widget and a different scroll model, not a
flag.

Greedy wrapping lives in `Label`, over `BreakIterator` under the UI language, and the rule it obeys
is the one any future soft wrap here has to obey too: the paragraph is shaped once to decide *where*
to cut, and each emitted line is re-shaped to decide what is drawn. `advanceTo` is a budget and not
a promise about a substring — the joining forms change at the cut, a ligature that spanned it is
gone, and so is the kerning at the seam.

## The TextArea content inset

Horizontal and vertical insets are different tokens on purpose. Horizontally the area
uses the same `fieldPadH` a `TextField` insets by, so a field and an area stacked in a
form put their first character on the same column; before that they were 4 pt apart at
MEDIUM and the ragged left edge showed in every form. Vertically it keeps `areaPad`,
which is a text margin rather than a vertical-centring pad; nothing outside the widget
has to agree with it.

The preferred box is derived per step to hold a roughly constant amount of visible
content rather than a constant box. Spending one column on the wider horizontal pad
tilts the ramp slightly, because `fieldPadH` climbs faster than `areaPad` did. Restoring
a flat column count would mean re-deriving the box, not changing the widget.

## Scrollbars do not scale

`ScrollBar.thickness()` is 15 pt at every size step, and the bars overlay content rather
than insetting it. At the dense steps the bar therefore floats over more live text than
at MEDIUM: 7 pt against 3 for the vertical bar in a `TextArea`. This is the accepted
cost of one scrollbar geometry process-wide. Where the last column matters at a dense
step, put a trailing margin in the surrounding layout.
