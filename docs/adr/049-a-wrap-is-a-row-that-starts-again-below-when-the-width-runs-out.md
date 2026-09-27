# ADR 049: A wrap is a row that starts again below when the width runs out

- **Status:** Accepted, 2026-09-27. Implemented as `limn.scene.layout.Wrap`, with its tests, the
  layout guide's new section and the guide's worked window using one.
- **Date:** 2026-09-27
- **Scope:** the fifth layout widget: what it is called, what it measures to, what it takes from
  `Row` and what it refuses, and what it deliberately leaves undone. Grid is not decided here (§7).
- **Compatibility:** additive. Nothing about `Row`, `Column`, `Stack` or `Expanded` changes; the
  only edit to an existing type is a sentence on each of `Flex`'s two alignment enums saying that a
  wrap reads them too.

---

## 0. What existed

A set of children whose number or length is not the designer's to decide (tags, filters, the chips
a user adds, a strip of buttons whose labels are translated) had two homes, and neither held it. A
`Row` runs off its own edge: a `Flex` does not clip and does not wrap, which its own documentation
says of an over-subscribed row. The layout guide's grid, rows of `Expanded` children, fixes the
number of columns and stretches every child to a column's width, which is right for tiles and wrong
for chips that should keep their own width. The road map to 1.0 names Grid and Wrap as the layouts
still owed; this record is the Wrap half.

## 1. Decision: a container of its own, not a flag on `Row`

`Wrap extends Container<Wrap>` directly. A `row.wrap(true)` flag was the other shape, and it was
turned down for three reasons. The flag would be meaningless on `Column`, which shares the class
body. Almost nothing in `Flex` survives it: the flexible split and its floors and ceilings have no
leftover to work on (§4), and a row measures its fixed children with an open width where a wrap
must bound it (§3), so the flag would be a second layout inside the first, behind a boolean. And the
accessibility coverage entry for a transparent layout stands on its ancestry staying hookless: a
class that shares no ancestor with `Flex` below `Container` cannot be described by accident.

It is public and not final, like `Row`, `Column` and `Stack`: ADR 046 §2 keeps the layout
primitives open because extending a layout is what a layout is for. It has no protected
silent setter of the `gapSilently` kind; a token-spaced subclass is not wanted yet, and adding
one later is compatible.

## 2. Decision: the vocabulary is the row's

A run is laid out as a row lays out its children, so the wrap uses the row's words for the row's
ideas, and a wrap whose children fit on one line places them exactly where a `Row` with the same
settings would (a test holds that for every `MainAlignment`).

- **`gap(float)`** is the gutter between neighbours on a run, the same word `Flex.gap` uses for
  the same gutter. Flutter's `spacing` and CSS's `column-gap` were the alternatives; either would
  give the toolkit two names for one idea. CSS's shorthand, where `gap` sets both axes, was not
  followed either, because then `gap` would mean something different on a wrap than on a row.
- **`runGap(float)`** is the space between runs, named after `gap` so the pair reads as one family.
- **`mainAlignment(Flex.MainAlignment)`** places each run on its own, on the two vocabularies the
  enum already carries: `START`, `END` and `SPACE_BETWEEN` follow the reading direction, `LEFT`
  and `RIGHT` name a side of the box. No `Wrap`-specific enum: Flutter's `WrapAlignment` adds
  `spaceAround` and `spaceEvenly`, which `Flex` does not have, and one layout gaining them alone
  would split the vocabulary. `SPACE_BETWEEN` spreads every run, the last one included, which is
  what CSS and Flutter both do, and puts a run of one child where reading starts, as a row does.
- **`crossAlignment(Flex.CrossAlignment)`** places a child within the height of its run, which is
  the height of the run's tallest child. `START`, `CENTER` and `END` place the box. `BASELINE`
  runs the row's second sweep per run, with the row's clamp: a run is as tall as its tallest
  child and no taller, so a shift that would leave the run is given up rather than overlap the
  next one. `STRETCH` is supported and priced (§3).

The setters take the row's shapes and one step more. Each returns the wrap and checks the UI
thread; a negative gap clamps to `0` as `Flex.gap`'s does, while `NaN` and infinity are refused,
which `Flex.gap` does not do today (§6). Every setter carries an equality guard, so re-applying a
value costs no layout pass; `Flex.mainAlignment` has none and buys one. There are no getters, as
there are none on `Flex` or `Stack`.

## 3. Decision: every child is measured once, against the whole width

The wrap asks each visible child one question, `0..maxWidth` by `0..maxHeight` of what the wrap
itself was given, and never the room left on a run. That is what keeps the count at one measure per
child per pass: the answer does not depend on where the child lands, so breaking into runs needs no
second question, and layout asks the question measure asked and gets the cached answer (the
`measuredWith` reason `Flex` records, with the extra condition that the box be no narrower than the
widest child). The price is that a child whose text could wrap into the tail of a run is not
squeezed there; it goes whole to the next run.

The runs are broken greedily in child order. A child goes on the current run while the run, a gap
and the child fit, an exact fit included; otherwise it starts the next run, unless the current run
is empty, which is how a child wider than the wrap takes a run of its own at the wrap's width
instead of leaving an empty run before it. Measure and layout share one break routine, for the
reason `Flex` shares its flexible split: a wrap that measured one set of runs and laid out another
would report a height its children do not have. The run's extent is accumulated with the very
expression the break compares, because `(a + gap) + b` and `a + (gap + b)` can round to different
floats, and a wrap laid out at its own natural width would then push its last child onto a run of
its own. The test for it uses widths found to disagree.

The wrap measures to its widest run and to its runs' heights with the gaps between them. With no
bound on the width it is one run, as long as a row of the same children: that is the natural size
`Scene.pack()` measures, so packing a bare wrap asks for one line, which the work area then caps
and the second measure breaks at that width. A maximum width on the wrap is how a packed window
gets a wrap of several lines. A child's own bounds act as they do in a row, and the parent wins: a
child whose minimum is wider than the wrap is given the wrap's width.

`STRETCH` asks a second question, of a child shorter than its run only: the run's height is known
once the run is formed, so the child is measured again at its own width and the run's height. The
tallest child of a run is not asked again. Every pass therefore costs a stretched short child two
measures, and the test pins both numbers.

## 4. Decision: an `Expanded` child is refused when it is added

A run has no leftover to share. What one run leaves over is the room the next child did not fit
into, and it belongs to `mainAlignment`. The alternatives were worse. Accepted and ignored, an
`Expanded` would be laid out as the widget it wraps, and a spacer would be a zero-width child
with a gap on each side, both silently. Honoured per run, a flexible child raises questions a
different layout would have to answer (does it fill its run, or move to the next when it is
squeezed?). So `add` and `add(index, …)` throw `IllegalArgumentException`, and the message names
`mainAlignment` and `SizedBox`. Only a direct child is checked, as nothing checks an `Expanded`
inside a `Padding` today.

## 5. Direction and the accessible tree

In a right-to-left subtree every run starts at the right edge: the cursor stays logical and only
the final coordinate is reflected, the way `Flex` does it, so the gap and every alignment are one
piece of arithmetic in both directions. Runs go down in both directions, and the cross axis is
vertical and does not mirror.

The wrap declares no role, name, action or state, does not paint, clip or take focus, so it
publishes no node and its children take its place (`AccessibleCoverageTest` lists it as
transparent). Their reading order and their Tab order are the order they were added in, run after
run, in both directions. A child that changes run because the width or the gap changed is a
`BOUNDS_CHANGED` and never a rebuild. Naming, tooltipping or giving a role to an instance
materialises a group over the wrap's box, the hatch a row has.

## 6. What it deliberately does not do

- **No alignment of the runs themselves** (CSS's `align-content`, Flutter's `runAlignment`). A
  wrap measures to its content's height, so where the block of runs sits is its parent's decision:
  a `Stack` or a column's alignment places it. Given more height than its runs, as the scene's
  root or a stretched child is, a wrap keeps its runs at the top.
- **No vertical wrap**, a column that continues in a second column. Nothing in the repository asks
  for one, and it would need a height-bounded measure with a horizontal overflow of its own.
- **No `SPACE_AROUND` or `SPACE_EVENLY`**, for §2's reason.
- **No balanced breaking.** The break is greedy, as in CSS and Flutter, so a short last run is the
  expected shape and not a defect.
- **No limit on runs and no "and 3 more" chip.** That is an application's widget built on a wrap.
- **No clipping.** Like a row, a wrap given less height than its runs paints past its box; a child
  wider than the wrap is the one overflow the break itself prevents.
- **The baseline limitation is the row's.** A run's height is its tallest child's, which can be
  less than a baseline-aligned line needs, and the clamp stops a shift short rather than leave the
  run.
- **`Flex.gap` still lets `NaN` through**, where `Wrap.gap` refuses it. Aligning the row is a
  separate change and was left out of this one.

## 7. Grid is not in this record

The road map names "Grid and Wrap" together. The owner is reconsidering a simplified Grid, so this
record decides Wrap alone and decides nothing about a grid; the layout guide keeps its "grid,
without a grid widget" section, and points to a wrap for tiles that should keep their own width.

## 8. How it is held

`WrapTest` pins the breaks (the exact fit, a child wider than the wrap, no bound, hidden children,
the rounding case of §3), the per-run alignments, the cross alignments with `STRETCH` and
`BASELINE`, the refusal of `Expanded` and the setters. `WrapMeasureCountTest` pins one measure per
child per pass in a loose parent and under a tight one, in both directions, and `STRETCH`'s two.
`ContainerMirroringTest` holds the reflection and the two vocabularies; `WrapAccessibilityTest` the
tree, the reading and Tab order and the move-not-rebuild; `SizeBoundsTest` and `ScenePackTest` the
bounds and the packed size; `VisibilityDamageTest` that a hidden chip repaints what the runs moved,
inside the wrap when it keeps its size and one level up when it loses a run. `UiThreadContractTest`
reads `runGap` as a mutator. Each of these was run against a defect planted in the widget (the
break at `>=`, no break at all, no reflection, the physical arms made logical, the extent summed in
the other order, layout asking against the box, `STRETCH` asking every child, `CENTER` against the
wrap's height, the wrap filling its bound, `runGap` without its thread check) and failed.
