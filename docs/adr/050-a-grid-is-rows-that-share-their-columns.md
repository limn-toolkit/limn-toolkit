# ADR 050: A grid is rows that share their columns

- **Status:** Accepted, 2026-09-27. Implemented as `limn.scene.layout.Grid`, with its tests, the
  layout guide's new section and the guide's worked window using one for a collection's details.
- **Date:** 2026-09-27
- **Scope:** the sixth layout widget: what it does, the words it takes and from whom, what it
  measures to and what that costs, and the long list of what it deliberately does not do.
- **Compatibility:** additive. Nothing about `Row`, `Column`, `Wrap`, `Stack` or `Expanded`
  changes; the only edits to existing types are a sentence on `Flex.CrossAlignment` saying a grid
  reads it too, and the grid's name in `Container`'s and the package's lists of containers.

---

## 0. What existed

Two-axis alignment had no layout. A caption beside a field lines up with its own field through a
`Row`, and with the captions above and below it only if every caption has the same width, which
someone has to choose. The repository chose it three ways, each a workaround:

- `ColorPicker`'s channel lines are a `Row` each (letter, rail, stepper), and the letters line up
  down the block because each sits in a `TokenBox` of the `fieldIcon` token's width, a fixed track
  written as a sizing token.
- The theme editor pushes its controls to the trailing edge with spacers, so they line up on the
  right and nothing lines up on the left of them.
- The forms guide puts every caption above its field, and the layout guide's "two-column form"
  shape was a column of rows whose captions were given a fixed width by hand: the width a
  translation outgrows first.

The road map to 1.0 names Grid and Wrap as the layouts still owed. ADR 049 took Wrap and left the
grid open (its §7) while the owner reconsidered it.

## 1. Decision: a simplified grid, and nothing that duplicates the other layouts

The owner approved on 2026-09-27 a grid of `n` columns, and rejected a CSS-like grid as too
complex. His rule is the one the other layouts already follow: each is simple to operate, and none
does what another one does. So the grid does one thing a row, a column and a wrap cannot, which is
line cells up on both axes, and the whole of what he approved is:

- `Grid(n)`: the children go in in insertion order, row by row, so Tab and a screen reader follow
  the visual order with nothing extra;
- each column is as wide as its widest cell, and one column may take the leftover, as `Expanded`
  does;
- each row is as tall as its tallest cell, with baseline alignment;
- no cell spanning several tracks, no explicit position, no fixed tracks (`SizedBox` already
  exists for that);
- about the size of `Wrap`.

`Grid extends Container<Grid>` directly, public and not final, for the reasons ADR 049 §1 gives for
`Wrap`: ADR 046 §2 keeps the layout primitives open, and a class that shares no ancestor with
`Flex` below `Container` cannot be described by accident (§6).

## 2. Decision: a child's cell is its index

`new Grid(columns)` takes the column count once. Child `i` is in row `i / columns` and column
`i % columns`; the last row may be short. There is no setter for the count: the children are added
in rows of that many (caption, field, caption, field), and a different count would pair every one
of them differently, which is a different grid rather than a changed one. A setter can be added
later without breaking anything. There is no getter either, as there is none on `Flex`, `Wrap` or
`Stack`.

**A hidden child keeps its cell.** The alternative, the one `Row` and `Wrap` follow (a hidden child
takes no room, and the next one closes up), would move every child after a hidden caption one cell
back, and every field after it would stand beside the wrong caption. So the cell is decided by the
index alone, and what a hidden child gives up is its size: a row whose every cell is hidden takes
no height and no row gap, and a column whose every cell is hidden no width and no gap. Hiding a
caption and its field together, the usual way a form drops a question, therefore removes the row
cleanly, and hiding one of them leaves a hole where it was. `add(index, child)` does move every
later child one cell on, which is what inserting means.

An empty cell on purpose, the caption's cell beside a checkbox that carries its own text, is a
`SizedBox(0, 0)`: it publishes nothing to a reader and takes no room.

## 3. Decision: the words are the row's, the column's and the expanded child's

Each idea a grid has already has a word in Limn, and the grid uses it.

- **`gap(float)`** is the gutter between neighbouring columns, the gutter `Row.gap` and `Wrap.gap`
  are between neighbours along a line. **`rowGap(float)`** is the space between rows, formed the
  way `Wrap.runGap` is formed: the gap between lines is named after what the lines are called, a
  wrap's runs and a grid's rows. CSS's `column-gap` was the alternative for the first, and it would
  give the toolkit two names for the gutter along a line, which ADR 049 §2 refused for the wrap;
  `runGap` was the alternative for the second, and a grid's rows are not runs, which break where
  the width runs out.
- **`crossAlignment(Flex.CrossAlignment)`** places a cell within its row's height, with the row's
  meaning for every constant, the baseline clamp included. **Its default is `BASELINE`**, where
  `Row` and `Wrap` default to `START`. The owner's approval describes the row as aligned by
  baseline, and a grid is where a caption stands beside the field it names: under `START` a
  caption sits at the top of the taller field beside it, its text above the field's, in every
  form anyone builds. A cell with no text aligns its bottom edge, the base `baselineOffset()`'s fallback.
- **`columnAlignment(int column, Flex.CrossAlignment)`** places the cells of one column within its
  width, with the meaning `Column.crossAlignment` gives each constant across a column: `START`, the
  default, and `END` follow the reading direction, `CENTER` centres, `STRETCH` makes each cell as
  wide as the column, and `BASELINE` places as `START`, as it does on a column. It is per column
  rather than per grid, because the common grid wants two answers at once (captions at their own
  width, fields stretched, or captions set against their fields with `END`), and per column
  rather than per cell, because a cell that wants to differ from its column is one `Row` away.
- **`expandedColumn(int)`** names the one column that takes the leftover width, `-1` for none (the
  default), after the `Expanded` it stands in for. It is one column, not a weight per column: the
  owner's approval says one column, and a form never wants two columns splitting the leftover.

The setters are `Wrap`'s: each checks the UI thread, carries an equality guard so re-applying a
value costs no layout pass, clamps a negative gap to `0`, refuses `NaN` and infinity, refuses a
`null` alignment, and throws `IndexOutOfBoundsException` for a column the grid does not have.

**An `Expanded` child is refused when it is added**, as a wrap refuses one. What a grid leaves over
belongs to a column and not to a cell, and an `Expanded` in one row of a column says nothing about
the rows above and below it; accepted, it would be laid out as the widget it wraps. The message
names `expandedColumn`.

## 4. Decision: what it measures to

**Columns.** Every visible cell is asked one question first, `0..maxWidth` by `0..maxHeight` of
what the grid was given, the whole width and never a share of it, so a cell's answer does not
depend on its neighbours and the columns are sized from the answers in one sweep. A column is as
wide as its widest answer. The expanded column is different: given a bound, it is whatever the
other columns and the gaps leave of it, `max(0, width - others - gaps)`, more than its cells need
when the grid has more room and less when it has less, and its cells are not asked the open
question at all, because their answers have no say in that width. With no bound on the width,
which is what `Scene.pack()` and a horizontally scrolling parent give, it is sized from its cells
like any other column. So a grid with an expanded column fills the width it is given, as a row with
an `Expanded` child does, and one without is as wide as its columns and gaps.

**Less width than the columns need.** The expanded column gives way first, down to nothing, as an
`Expanded` child does; no other column shrinks, and a grid narrower than them runs past its edge,
as a row of children that are not flexible does. There is no floor for the expanded column. Its
width is what the others leave, so a floor would have to take width from them, and that is the
shrink order this record does not take. Where a form stops being usable, the window's minimum size
says so (the layout guide's advice for every layout).

**Rows.** A cell whose final width differs from its first answer is asked again at that width, so
its row is as tall as the cell will be where it is placed: a stretched cell narrower than its
column is given the column's width, a cell of an expanded column narrower than itself is given at
most the column, and every cell of the expanded column under a bound is asked for the first time.
The height stays open in each of those questions. A row is as tall as its tallest cell at its
final width, and the grid as tall as its visible rows and the gaps between them.

**What that costs, pinned in `GridMeasureCountTest`.** One measure per cell per pass in the common
cases: a grid in a loose parent (measured against the parent's bound and laid out at its own
width, the shape that misses a cache when layout asks against the box), a grid that is the root,
and an expanded, stretched column, whose cells are asked only at the column's width, as an
`Expanded` child is asked only at its share. Layout keeps the measure's constraints, its widest
answer, its column widths and their total, and asks each cell the question measure asked last, so
the one-answer cache in `Widget.measure` hands it back. `STRETCH` across costs a second measure of
each cell narrower than its column, and `STRETCH` down a row a second measure of each cell shorter
than its row (a cell stretched both ways, and short of both, costs three), because the size it is
stretched to exists only once its neighbours have answered. The widest cell of a stretched column
and the tallest of a stretched row are not asked again.

The column widths are kept, and not recomputed, when layout runs at exactly the width measure
summed. A grid measured with no bound sizes its expanded column from its cells, and is then laid
out at the total; subtracting the other columns back out of that total is not exact in floats
(`((0.1 + 7.1) + 0.3) - (0.1 + 7.1)` is `0.3000002`), and a column one float wider than its widest
cell would stretch that cell and ask it a question it has not answered. The test uses those widths.

**No leftover goes anywhere else.** A grid given more width than its columns and no expanded
column keeps its columns against the edge reading starts from, and a grid given more height than
its rows keeps its rows at the top; where the block sits is the parent's decision, as ADR 049 §6
decides for a wrap's runs.

## 5. Direction and the accessible tree

In a right-to-left subtree the first column is at the right edge and the columns follow leftwards.
The column starts are logical distances and only the final coordinate is reflected, the way
`Flex` and `Wrap` do it, so the gaps, every column alignment and the expanded column are one piece
of arithmetic in both directions: `START` in a column is its right edge, `END` its left, and the
expanded column reaches the left edge. The rows go down in both directions, and `crossAlignment`
is vertical and does not mirror.

The grid declares no role, name, action or state, does not paint, clip or take focus, so it
publishes no node and its cells take its place (`AccessibleCoverageTest` lists it as transparent).
Their reading order and their Tab order are the order they were added in, which is the grid's own
order, row by row, so a caption is read just before the field beside it and nothing has to be told
what the order is; a right-to-left subtree moves the boxes and not the order. A column that widens
is a `BOUNDS_CHANGED` for every cell it pushes and never a rebuild. Naming, tooltipping or giving a
role to an instance materialises a group over the grid's box, which is how a form section asks to
be announced as one. A caption still names its field with `Label.setLabelFor`; standing beside it
in a grid is a layout fact and names nothing, as standing above it in a column is.

## 6. What it deliberately does not do

- **No spanning.** A cell is one column wide and one row tall. A heading across a form is a
  `Column` with the heading and then the grid.
- **No explicit placement.** No row or column index on a child, no named areas; the order is the
  insertion order, and that is also why the reading order needs no help.
- **No fixed or fractional tracks.** No `fr`, no track sizes, no minimum or maximum per column. A
  column of a fixed width is a `SizedBox` around one of its cells, and a cell's own bounds
  (`setMinWidth`, `setMaxWidth`) act inside a grid as they do in a row.
- **No second expanded column, and no weights.** §3.
- **No column-first flow.** The children fill rows; a grid read down its columns would read in an
  order the screen does not show.
- **No alignment of the whole block** (CSS's `justify-content` and `align-content`): §4's last
  paragraph.
- **No per-cell alignment.** A column has one alignment; a cell that differs is wrapped in a `Row`.
- **No intrinsic minimum.** Limn has no min-content measure, so a column is sized from each cell's
  natural width against the whole width: a caption that wraps asks for its whole text on one line
  and may take the grid's width. Give such a caption a maximum width.
- **No clipping.** Like a row, a grid over-subscribed on either axis paints past its box.
- **The baseline limitation is the row's.** A row is as tall as its tallest cell, which can be less
  than a baseline-aligned line needs, and the clamp stops a shift short rather than leave the row,
  the limitation `Flex.alignBaselines` documents for a row.
- **No migration in this change.** `ColorPicker`'s channel lines (letter, expanded rail, stepper:
  a grid of three with the rail's column expanded, and the `TokenBox` gone), the theme editor's
  rows, and the forms guide's captions-above-fields are the candidates §0 names, each a change of
  its own with its own pixels to review. Only the layout guide's "two-column form" shape moved,
  because it described a workaround this record replaces.

## 7. How it is held

`GridTest` pins the cells (row by row, a short last row, an insertion, a hidden child keeping its
cell, an empty row and an empty column taking no room), the column and row sizes, the expanded
column growing, shrinking to nothing and sizing its row from its cells at its width, the no-bound
size, the float case of §4, every column and cross alignment with `BASELINE` as the default and
its clamp, the refusal of `Expanded` and the setters. `GridMeasureCountTest` pins one measure per
cell per pass in a loose parent in both directions, as the root, for an expanded stretched column
and at the natural width, and the two exceptions of §4. `ContainerMirroringTest` holds the
reflection, the column alignments turning around and the expanded column reaching the other edge;
`GridAccessibilityTest` the tree, the reading and Tab order in both directions, the
move-not-rebuild and the group; `SizeBoundsTest` and `ScenePackTest` the bounds and the packed
size; `VisibilityDamageTest` that a hidden caption repaints its own cell and nothing else, and that
a hidden row moves the rows under it and climbs to the column that absorbs it. `UiThreadContractTest`
reads `rowGap`, `columnAlignment` and `expandedColumn` as mutators.

Each of these was run against a defect planted in the widget, and failed: a column as wide as its
last cell instead of its widest, every column as wide as the widest cell anywhere, children filling
column by column, no reflection, the columns reflected but a cell's place in its column left
physical, no leftover for the expanded column, a gap before the first column, `START` as the
default, the baseline shift unclamped, no baseline sweep, `CENTER` against the grid's height
instead of the row's, layout asking against the box, layout re-deriving the measured columns by
subtraction, the expanded column's cells asked the open question under a bound, `STRETCH` asking
every cell again across and down, empty rows and empty columns keeping their gaps, hidden children
giving up their cells, an `Expanded` accepted, a setter without its equality guard, and `rowGap`
without its thread check.
