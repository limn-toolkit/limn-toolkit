# ADR 041: A table is columns over a list the application owns, and a cell is a value until it asks to be a widget

- **Status:** Accepted, 2026-09-08; §6 revised 2026-09-09 to rule out cell editing for good.
  Phase 1 implemented the same day, with one exception: the
  Windows bridge maps the four roles to control types but does not yet serve the Grid and Table
  patterns, because their interface identifiers and vtable orders have to be read off the Windows
  guest (`scripts/a11y/windows/dump-uia-interfaces.ps1`, already extended with the four) and the
  guest was not reachable when the rest landed. §10 says which phase each item lands in and what
  is deliberately left out of the first.
- **Date:** 2026-09-08
- **Scope:** the toolkit's first table widget: what its data model is, how it virtualizes on two
  axes, how rows are selected and columns sorted and resized, what a cell is, how it is read by a
  screen reader on three platforms, and what it does not do yet. Trees and tree-tables are not
  here; §9 says why.
- **Compatibility:** nothing published changes. `ListView` is untouched and stays the widget for
  rows that are widgets; this record adds a package beside it.

---

## 0. The starting point

The survey of 2026-09-08 put a table first among what the toolkit still owes the market, and the
repository already knew: ADR 039 §4.1 records that "no table widget exists, and defining a facet
before there is something to describe would be guessing", and reserved the `Table` interfaces of
all three platforms for the day one did.

What exists to build on is exact. `ListView` virtualizes one axis by an **anchor and a walk**: it
holds which row sits at which y, measures a handful of rows from there each frame, and never keeps
a global height table (its class comment). Its rows are widgets the application supplies through
an adapter, which is right for a list of cards and wrong for a grid: a widget per cell makes forty
rows of twenty columns eight hundred realized widgets, each with its own measure, layout, paint and
accessibility node, for cells that are one word each. `Label` shows the cheaper shape — a widget
that holds one `ShapedText` per line and re-shapes only when `ShapedText.matches` says the held one
is stale (ADR 031) — and `TextRuler.ellipsize` cuts a line to a width on the ruler that shaped it.
`ScrollGutters` settles the strips two scroll bars take, `ScrollBar` is the one bar every scrolling
widget shares, and `Scrollable.revealRect` is how a focused thing asks to be brought into view.

ADR 040 is still a proposal. Nothing here anticipates it: the table registers listeners the way
every widget does today, and the conversion to observers is that record's, all widgets at once.

---

## 1. The model is typed, and the application owns the rows

```java
Table<T> table = new Table<>(columns);
table.setRows(List<T> rows);        // the application's list, held by reference, never copied
table.refresh();                    // after the list's contents change
```

A `Column<T>` names a title (an `I18nString`, so a header follows the language), a function from a
row to a cell value, a formatter from that value to the text drawn, an alignment, a comparator, and
its widths. A column is a value the application builds once; the table holds the list of them.

**Why typed and not an adapter like `ListView`'s.** An adapter answers "the widget for row *i*",
and a table's questions are "the text of cell (*i*, *j*)", "how do rows compare on column *j*",
and "is (*i*, *j*) numeric, so it aligns to the end". A typed column answers all three from one
function the application already wrote. Every toolkit an adopter is coming from — JavaFX, Compose,
Qt, Swing's `TableModel` — puts the column, not the row, at the centre, and a table whose sort and
formatting had to be re-implemented per application would be a list with lines drawn on it.

**Why the rows are a `List<T>` held by reference.** Copying would make the model the table's, and
then every edit would need a change event the toolkit has not designed yet (ADR 040). Holding the
application's list and being told `refresh()` is the `ListView` contract, and it keeps a table over
a million rows free: `rowCount` is `rows.size()`, and nothing is read until it is on screen.

**A cell is a value until it asks to be a widget.** The default cell draws its formatted text
directly, with no widget: the table keeps, per realized row, one `ShapedText` per visible column,
re-shaped through the same `matches` test `Label` uses. A column may instead name a widget factory
(`Column.widget(title, row -> new Checkbox(...))`); those cells are real children, mounted and
recycled with the row, and they are what a button or a switch in a table is — the one kind of
in-row interaction this table has, because it does not edit cells (§6).

---

## 2. Two axes, one anchor, rows that are not widgets

The table keeps its own viewport rather than wrapping a `ListView`, because a list has one axis and
one child per row and a table has neither.

**Vertical is `ListView`'s anchor and walk**, re-stated over row slots instead of widgets: the
table holds the index of the row at the anchor and its top edge, walks down measuring rows until
the viewport is full, and keeps the mean measured height as its scroll estimate. A row's height is
the tallest of its cells — the text line box at the row's control size for a value cell, the
measured widget for a widget cell — so rows may differ in height, as the list's may.

**Horizontal is a band of columns.** Column widths are resolved once per layout from each column's
preferred width, minimum and weight (§5), so the x of every column is a prefix sum over a list that
is rarely longer than thirty. The band of columns that intersect the viewport is what is shaped and
painted; a column scrolled out of view costs nothing, and a row realized while it was out of view
shapes its text the first time it enters. The header is pinned: it scrolls with the columns and not
with the rows.

**Both scroll bars are the shared `ScrollBar`**, resolved through `ScrollGutters` exactly as
`ScrollView` resolves them, so a table and a scroll view reserve, overlay and fade their bars the
same way and swap sides together under a right-to-left direction.

**What a realized row holds** is one slot in two parallel arrays kept in data order, as
`ListView.mountedRows` is: the row's index, its measured height, its shaped cells, and the widget
children of its widget columns. The rules `ListView` learnt the hard way carry over unchanged: the
mounted run is contiguous by construction, `children()` is the bars and then the widget cells in
data order, and the row holding the keyboard focus is kept realized outside the viewport for as
long as it holds it (ADR 039 §13.29).

---

## 3. Selection is by row, and the keyboard has a cell

Three modes: `NONE`, `SINGLE` and `MULTI`. Selection is a set of **model** indices — not view
positions, so a sort does not change what is selected — held in a `BitSet`, with the lead row the
last one the user acted on. `MULTI` is the platform's own grammar: a click selects one, Shift-click
selects the range from the lead, the command modifier toggles one, and Ctrl+A or Cmd+A selects all.
`onSelect` fires once per change with no argument; `selectedRows()` answers the set and
`selectedRow()` the lead, and both are model indices.

Separately from the selection, the table always has a **focus cell** — a row and a column — that
the arrow keys move. It is what Left and Right mean in a table, and it is what a screen reader's
cursor stands on: a reader walks a table cell by cell, and a table whose keyboard moved only by row
would leave the reader and the sighted user on different things. The focus cell's row is the lead row; moving it
with an unshifted arrow moves the selection with it in `SINGLE` and `MULTI`, as every desktop
table does, and does nothing to the selection in `NONE`.

Enter, and a double click on a row, fire `onActivate` with the lead row, the "open this" gesture
`ListView` has. Cell selection — a rectangle of cells, as a spreadsheet has — is not in this
record; §10.

---

## 4. The toolkit sorts, and the application may take that over

A click on a sortable header cycles ascending, descending, none; the same header again reverses.
The table keeps a **permutation**: an `int[]` from view position to model index, rebuilt from the
column's comparator on `setSort`, on `refresh` and on nothing else. The application's list is never
reordered, which is what keeps selection stable across a sort and keeps the model the
application's.

The comparator defaults from the value: strings through `I18n.collator()` under the table's own
locale (ADR 034), `Number`s numerically, `Comparable`s by themselves, and anything else by its
formatted text. A column may name its own. A column with no comparator and no comparable value is
not sortable, and its header says so by refusing the click rather than sorting by something
nobody asked for.

`onSortRequest(column, order)` replaces the permutation: when it is set, a header click tells the
application and sorts nothing, and the application reorders its list — a server page, a database
query — and calls `refresh()`. This is the hook a table over data the client does not hold needs,
and it is one setter rather than a second table class.

Filtering is the application's: a filtered `List<T>` is one line of stream code, and a filter API
in the toolkit would have to choose between predicate, text and column semantics for a case each
application answers differently.

---

## 5. Columns have widths and weights, and the header resizes them

Each column has a preferred width, a minimum, and a weight. Layout gives every column its
preferred width, then distributes what is left of the viewport among the weighted columns in
proportion, exactly as `Expanded` distributes a row's leftover; columns with weight zero keep their
preferred width, and a table whose preferred widths exceed the viewport scrolls horizontally rather
than crushing anything below its minimum.

Dragging the divider at a header's trailing edge resizes that column; the cursor is
`RESIZE_EW` over the band, the band is thin to look at and thick to hit, as `SplitPane`'s divider
is. A resized column's width is held on the column object, so it survives a `refresh` and is what
`Column.width()` answers. Columns may be hidden and shown. Reordering columns by drag is not in
this record: it wants the internal drag-and-drop the toolkit does not have, and §10 records it.

**The footer is a summary row**, pinned under the rows the way the header is pinned over them,
and it exists as soon as one column has something for it: a fixed text, one of the aggregates a
numeric column offers (sum, average, min, max), the row count any column may carry, or a value the
application computes from all the rows and the column formats as its cells. It is computed on
`setRows` and `refresh` and never per frame, because a sum over a million rows is a cost to pay
once per change and not once per damaged pixel. Added to the first phase on 2026-09-09 at the
owner's request; a free-form area under the rows was considered and refused, because a `Column`
and a `Label` already give an application that and a table would add nothing to it.

Under a right-to-left direction the first column is at the right edge and the horizontal scroll
starts there, because mirroring is a placement decision and never a transform (ADR 032). Numeric
columns align to the **end** of the cell, which is the left under RTL, and their digits are
localized when formatted and nowhere else (ADR 033).

---

## 6. Cells are not edited in place, and will not be

The table does not edit cells, and this is a decision rather than a phase: no `Column.editor`,
no editor mounted in a cell's box on Enter or a double click, no `EDITABLE` state on a cell.
Decided by the owner on 2026-09-09, after the first phase had left the seam open.

**Why.** In-place cell editing is a spreadsheet's interaction, and it reads as one everywhere
else: a field that appears where a value was, a commit that happens on a keystroke the user did
not think of as a save, a validation error with nowhere to stand, a change to one cell of a record
whose other fields depend on it. Modern interfaces do not put it in front of a user, and every
toolkit that ships it also ships its edge cases — what a Tab does mid-edit, what a sort does to a
row being edited, what a screen reader is told when a cell becomes a field — which are the cost of
a feature the interface should not have had. A table is for reading, comparing, sorting and
choosing; editing a record wants the record whole, with its labels, its validation and its own
Save.

**What to do instead**, and each is already in the toolkit:

- **A dialog or a panel for the record.** Activate a row (Enter, a double click, or a
  `Column.widget` button) and open a `Dialog`, or a form beside or below the table, that shows the
  whole record as a form — every field labelled, validated as forms are (`TextField.Validation`),
  saved on an explicit action. On save, change the application's list and call `refresh()`; the
  selection is by model row and stays where it was.
- **Master and detail.** Keep the form open in a `SplitPane` beside the table, bound to the lead
  row through `onSelect`, so the user moves through records with the arrow keys and edits each in
  a form that never moves. This is the shape a settings screen or an admin screen usually wants.
- **A control in a widget column** for the one-gesture cases: a switch to flag a row, a button to
  open or delete it, a checkbox to include it. A `Column.widget` cell is a real widget with its own
  focus, its own accessibility and its own listener, and it is the whole of in-row interaction this
  table offers.
- **A bulk action over the selection** where the same change applies to many rows: select in
  `MULTI`, act once, `refresh()`.

**What stays from the seam.** The focus cell stays, because a screen reader walks a table cell by
cell (§3). The widget-cell seam stays, because it is what the third alternative is. Nothing that
was reserved for an editor remains reserved.

---

## 7. What a screen reader is told

ADR 039 §1.12 closes the role enum and says a new role needs a truthful mapping in all three
platform tables, read off the platforms and never recalled. This record adds four roles and two
facets, and each bridge's constants for them are read from its guest before the bridge is written.

**Roles.** `TABLE` for the widget; `COLUMN_HEADER` for each header cell; `ROW` for each realized
data row; `CELL` for each cell of a realized row and for each cell of the footer. The header row
and the footer row are each a `GROUP`, which every platform has, rather than a fifth role; a
footer cell's `CellFacet` carries a row of `-2`, as a header cell's carries `-1`.

**Facets.** `TableFacet(rowCount, columnCount)` on the table node: the model's counts, not the
tree's, for the same reason `SelectionItemFacet` carries the model's size of set. `CellFacet(row,
column)` on each cell and header cell: the row as shown (the view position, since that is what a
user counting rows sees; `-1` for a header cell) and the shown column's index. The column's header
— what a reader speaks before a cell's value — is **found by structure, not carried**: it is the
child at the cell's column of the table's header group, which is the table's first `GROUP` child.
Carrying its identifier would make a facet a bridge reads on its own thread depend on a resolution
that happens after the walk, which is what relations are for and cells are too many to be. A `ROW`
carries `SelectionItemFacet` exactly as a list row does, with the view position as position in set
and the model's row count as size of set, and a `SELECT` action; the table carries
`SelectionFacet` with the mode's multi-selectability.

**Names.** A cell's name is its formatted text, `nameFrom = CONTENT`, handed over as the cached
string with the row's witness so a quiet frame costs nothing. A header's name is the column's
title `I18nString`. A row has no name of its own: all three platforms compose a row from its cells
when the cells are named, and a name here would be spoken twice.

**Publishing.** Only realized rows are published, as `ListView`'s are (ADR 039 §11), and the row
that holds the keyboard focus stays published wherever a scroll has taken the viewport. The focus
cell is the node published `ACTIVE`, so the table's active descendant is a cell and not a row — a
reader that follows the active descendant lands on the value under the cursor. A `PRESS` on the
table activates the lead row; `SELECT` on a row selects it.

**Per platform**, what the facets become:

| Platform | Table node | Cell node | Header cell |
| --- | --- | --- | --- |
| Windows | `DataGrid` control type; `IGridProvider` (`RowCount`, `ColumnCount`, `GetItem`) and `ITableProvider` (`GetColumnHeaders`, `RowOrColumnMajor`) alongside the existing `ISelectionProvider` and `IScrollProvider` | `DataItem`; `IGridItemProvider` (`Row`, `Column`, spans of 1, `ContainingGrid`) and `ITableItemProvider` (`GetColumnHeaderItems`) | `HeaderItem` |
| macOS | `NSAccessibilityTableRole`; `accessibilityRows`, `accessibilityColumns`, `accessibilityHeader`, `accessibilitySelectedRows`, `accessibilityRowCount`, `accessibilityColumnCount` | `NSAccessibilityCellRole`; `accessibilityRowIndexRange`, `accessibilityColumnIndexRange` | the header group's children, each `NSAccessibilityCellRole` under `accessibilityHeader` |
| Linux | `ROLE_TABLE`; `org.a11y.atspi.Table` (`NRows`, `NColumns`, `GetAccessibleAt`, `GetColumnHeader`, `GetSelectedRows`) alongside `Selection` | `ROLE_TABLE_CELL`; `org.a11y.atspi.TableCell` (`Position`, `RowColumnSpan`, `Table`, `ColumnHeaderCells`) | `ROLE_TABLE_COLUMN_HEADER` |

`GetItem`, `GetAccessibleAt` and `accessibilityCellForColumn:row:` are answered for realized rows
and refuse for the rest: the neutral model mints no identifier for a node the walk did not
publish (ADR 039 §4.1), and a client asking for row 40,000 of a table showing rows 1 to 30 is
told there is no such element rather than handed one that vanishes. This is the same degradation
§4.1 already accepted for `FindItemByProperty` and for AT-SPI's `Collection`, and it is written
down here rather than discovered.

---

## 8. Events, and what changes when ADR 040 lands

`onSelect(Runnable)`, `onActivate(IntConsumer)` and `onSortRequest(BiConsumer)` are single-cast
setters returning the table, as every other widget's are. ADR 040 will convert them with the rest;
this record adds no observer of its own, because one widget with a different registration style is
the inconsistency that record exists to remove.

---

## 9. Not a tree, and not shaped for one

A `Tree` and a `TreeTable` are wanted, and they will reuse the row engine — an anchor over rows of
uneven height, a focus cell, a header band — and add what a tree needs: a depth per row, an
expand facet per row, and an order that is a traversal rather than a list. None of that is in this
record, and the table is not given a `depth` hook it does not use: a widget shaped around a
sibling that does not exist yet is the chart hierarchy the coverage test refused (the chart hooks
hoisted to `CartesianChart` and reverted, 2026-09-08). When the tree arrives, what the two share
moves into a package-private engine, and that is the day to decide its shape.

---

## 10. What lands when, and what is deliberately left out of the first phase

**Phase 1, this record:** typed model (§1), two-axis virtualization with value cells (§2), row
selection in three modes and the focus cell (§3), sorting with the permutation and the request
hook (§4), column widths, weights, hiding and header resize (§5), the widget-cell seam without any
built-in use (§1, §6), striped rows, the four roles and two facets in the model and in the three
bridges (§7), a gallery entry with a compiled snippet, a guide page, an accessibility gallery
entry with its transcript, and the tests every widget in this set carries: coverage, quiet frames
that allocate nothing, mirroring, recycling identity.

**Phase 2:** column reordering once internal drag-and-drop exists, frozen leading columns, and a
`Tree` that shares the engine (§9). Cell editing is not a phase; §6 says why.

**Not decided here:** cell selection as a rectangle; grouping; a filter API; multi-column
sort; row drag; export. Each is a request the toolkit has not had, and
each would be guessed at rather than designed.

---

## 11. Verification

- `TableTest`: layout realizes only what fits where the scroll says; a table of a million rows
  realizes what fits; the permutation sorts without moving the model; selection survives a sort;
  the three modes and their modifiers; widths, weights and a header drag; RTL places column one at
  the right edge.
- `TableAccessibilityTest`: the roles and facets of §7 on a realized table; a cell keeps its
  identifier across a scroll away and back; the active descendant is the focus cell; `PRESS` and
  `SELECT` reach the widget.
- `TableAccessibilityTest`'s quiet-frame case under `AllocationProbe`: a damaged frame that moved
  nothing publishes no snapshot and no event, and describing it costs no more memory than painting
  it already does — every name is a string a slot holds, handed over with the row's witness.
- The three bridges' constants tests, extended by the guests' readings; the accessibility
  gallery's transcript, read aloud before it is committed; and a live run on each guest through
  the probe scripts already in `scripts/a11y/`.
