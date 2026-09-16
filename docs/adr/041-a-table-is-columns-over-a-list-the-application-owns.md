# ADR 041: A table is columns over a list the application owns, and a cell is a value until it asks to be a widget

- **Status:** Accepted, 2026-09-08; §6 revised 2026-09-09 to rule out cell editing for good.
  Phase 1 implemented, and read by a real client on each guest on 2026-09-09: libatspi on Fedora
  (`Table` and `TableCell`, cell by row and column, column header, selected rows, a row selected
  from outside), the AX API on macOS (rows, header, selected rows, cell by column and row, index
  ranges, a cell's column header) and UI Automation on Windows (Grid, Table, GridItem and
  TableItem, row and column counts, cell by row and column, column headers). Two defects only a
  live client could find are recorded in §7.1. §10 says which phase each item lands in and what
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

**Amended 2026-09-14 (B7 of the 2026-09-13 pass).** ADR 040 was accepted on 2026-09-03 and
implemented on 2026-09-09, and the table moved onto it: `onSelect`, `onActivate` and
`onSortRequest` are single handler slots under `Checks.handlerSlot`, reached through
`handleUserChange` for the user's gestures alone, and `observeChanges` hears every change with
its origin (§8's amendment of this date; ADR 040 §7.2 records the table's seams). The sentence
above stands as the record of what was true when it was written.

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
then every edit would need a change event the toolkit has not designed yet (ADR 040; **amended
2026-09-14:** designed since — the origin-labelled `Change` and `observeChanges` — and the list
is still held by reference for the second reason, which stands on its own). Holding the
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
with the rows. (**Corrected 2026-09-14, B3:** "costs nothing" is loose — `mount` reads and holds
the *text* of every column of a realized row, hidden and out-of-view ones included, and a `CELL`
node is published for every shown column of every realized row, off screen when out of view; what
a column out of view saves is the shaping and the painting, which is what the sentence meant.
Pinned by `TableTest.aWideTableScrollsSidewaysAndShapesAndPaintsOnlyTheColumnsInView`,
`theFocusCellBringsItsColumnIntoView` and
`TableAccessibilityTest.aColumnScrolledAwayIsPublishedOffScreen`; the mirrored half by
`TableMirroringTest`, the class §10 promised and B2 found missing.)

**Both scroll bars are the shared `ScrollBar`**, resolved through `ScrollGutters` exactly as
`ScrollView` resolves them, so a table and a scroll view reserve, overlay and fade their bars the
same way and swap sides together under a right-to-left direction.

**What a realized row holds** is one slot in two parallel arrays kept in data order, as
`ListView.mountedRows` is: the row's index, its measured height, its shaped cells, and the widget
children of its widget columns. The rules `ListView` learnt the hard way carry over unchanged: the
mounted run is contiguous by construction, `children()` is the bars and then the widget cells in
data order, and the row holding the keyboard focus is kept realized outside the viewport for as
long as it holds it (ADR 039 §13.29).

**Amended 2026-09-14 (decision 22 of the 2026-09-13 pass; TABLE-NEW-9, TABLE-NEW-10).** Two rows
are kept outside the viewport, not one: the row whose widget cell holds the keyboard focus, as
above, and — while the table itself holds the keyboard — the focus cell's row, which a wheel or
a bar drag used to recycle, emptying a reader's cursor until the next arrow key (B8). The cursor
row is realized by every layout while the table is focused, so a `refresh()` or a sort that
unmounted everything realizes it again wherever its record went; the first pass after the focus
leaves releases it. A kept row is published where the layout put it, outside the rows' viewport,
so the `ROW` and a widget cell inside it agree on a box, and its cells are published off screen
with it (TABLE-NEW-9: the bit is per node, and nothing is inherited from a synthetic parent, so
a kept row's cells were `SHOWING` over the header band). And the table now declares the per-child
clip `Widget#clipX` and its three siblings ask for (TABLE-NEW-10): its widget cells are clipped to
the rows' viewport and its bars to the box, which is what `paintChildren` clips to, so a switch
scrolled under the header or the footer, or lying in a reserved gutter, is neither `isShowing()`
nor published `SHOWING`, a reader cannot toggle it, and a point on the header does not resolve to
it; the accessors allocate nothing. Pinned by `TableFocusedRowTest` (six cases, the widget-column
quiet-frame ratchet among them). The rule exists in three copies (ADR 044 §3); `ListView` and
`Tree` take theirs in their own lanes.

**Amended 2026-09-14 (TABLE-NEW-12 and decision 44 of the 2026-09-13 pass; T5's Table copy).**
The wheel took one axis: any non-zero `scrollX` made the event sideways and dropped its
`scrollY`, so a trackpad swipe that was not perfectly vertical scrolled nothing on a table whose
columns fit and only sideways on one that did not (TABLE-NEW-12). The axes are taken
independently now, as `ScrollView` takes them, each clamped on its own; Shift still turns a
*plain* vertical wheel into a horizontal one for a mouse with one wheel, and only when the event
carries no `scrollX`, so a tilt wheel and Shift cannot drive one axis twice. And a detent is
consumed only when an offset moved (decision 44): at either end of an axis, or on a table that
fits, it is left for the scroller that holds the table, where before the wheel was consumed
whenever the rows overflowed and a table inside a scroll pane was a wall once it had scrolled to
its end. Under an unbounded height the table prefers its header, its footer and
`setVisibleRows` (default 8) rows of the step's **seed** height, the token, never the realized
average: the average moves as rows of another height scroll in, and a measured size that moves
under a contained layout re-lays out the parent, so a table in a scroll pane jittered. A bounded
height from the parent still wins. Pinned by
`TableTest.aWheelTakesBothAxesAndShiftTurnsAPlainWheelSideways`,
`aWheelAtEitherEndOfTheTablePassesToTheScrollerThatHoldsIt` and
`theUnboundedHeightIsTheSeedsAndSetVisibleRowsChangesIt`. What a headless test cannot show: a
trackpad's momentum events arriving after the table hits its end chain into the pane by the
same rule, and whether that feels right is the live check decision 44 names.

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

**Amended 2026-09-14 (decision 32 of the 2026-09-13 pass).** Enter and a double click open the
**cursor row** — the focus cell's row — and so does a reader's `PRESS` on the table; `onActivate`
receives that row, which is the lead in `SINGLE` and may differ from it in `MULTI` after a toggle
or a Shift range. The sentence above stands as the record of what was decided; §7's amendment of
the same date carries the change and its tests.

**Amended 2026-09-14 (decisions 23 and 40 of the 2026-09-13 pass).** The focus cell and the
range anchor are view positions, and until this date a sort left both where they stood: the
permutation moved the records and the cursor stayed on view row *n*, so "the focus cell's row is
the lead row" was false after every sort, Down selected whatever record the sort had put under
the cursor, and a Shift range extended from a row the user never touched (TABLE-NEW-2). Both now
go with their records through the permutation, in `applySort`, and the move is announced as
`ACTIVE`/`ADJUSTMENT` before the `CHILDREN` announcement, as a consequence of the sort. After a
sort the focus row is revealed with the least scroll that shows it — at the foot of the viewport
when it moved down, at the top when it moved up, not at all when it stayed in view — as every
other write that moves the focus cell does (decision 40); a row below the realized run is now
placed as the last row in view by every reveal, where before it was placed first. Pinned by
`TableTest.aSortCarriesTheFocusCellAndTheRangeAnchorWithTheirRecords`,
`TableTest.aSortRevealsTheFocusRowWithTheLeastScroll` and
`TableAccessibilityTest.aSortKeepsTheCursorOnTheRecordItWasOn`. The renders the owner reviews
this against are `renders/table/sort-before.png` and `sort-after.png` of the pass. The range
sentence above is also imprecise: the range extends from the row last clicked, toggled or
selected — `rangeAnchor` — which is the lead only until a Shift extension moves the lead to the
range's end and leaves the anchor where it was; that is the platform's grammar, and the words are
what changes.

**Amended 2026-09-15 (fix round; the critic's Page Down finding).** "Placed as the last row in view
by every reveal" reached Page Down: from the top of a long table the first press moved the cursor a
page and scrolled by one row, leaving it at the foot; on a viewport showing part of the next row it
scrolled by that part alone. Page Down and Page Up page now: the cursor moves a page of rows and,
when it was on screen, the view moves by the same rows, so the cursor keeps its place in the view
(clamped at either end); a cursor that was off screen is revealed as any move reveals it. The sort,
End and code keep the least-scroll reveal. Pinned by
`TableTest.pageDownAndPageUpMoveTheViewAPageWithTheCursor`, on whole rows and on a part row.

**Amended 2026-09-14 (decision 23: a row is its record).** "Selection is a set of model indices"
held across a `refresh()` by number, so the documented `onSortRequest` recipe — reorder the list,
call `refresh()` — and any insert, remove or reorder before a `refresh()` moved the selection onto
whatever records now stood at those numbers (TABLE-NEW-3), and §6's "the selection is by model
row and stays where it was" held only for in-place edits. The selection, the lead, the focus
cell and the range anchor are now **followed by record**: model indices between two refreshes,
records across one. A record is the row itself, by `equals`, unless `Table.rowKey(Function)`
names its identity (`rowKey(Order::id)`); keys are taken when a row enters one of the four,
because the list is the application's and has already changed when `refresh()` runs, and records
with equal keys are told apart by occurrence — the third equal record stays the third, and an
insert of an equal record above it makes it the fourth, which is what "occurrence" can say. A
selected record the list no longer holds leaves the selection with one
`SELECTION`/`ADJUSTMENT`; a vanished lead hands the lead to the last selected row; a vanished
focus row or anchor keeps its position, clamped. A focus row that moved is announced as
`ACTIVE`/`ADJUSTMENT`; a `refresh()` that answers a sort request also reveals it with the least
scroll, as the table's own sort does, and any other `refresh()` keeps the scroll position it
promises. Costs, written down: `refresh()` with
something to follow reads the rows once, stopping at the last record found (nothing to follow,
nothing read); selecting a row without a `rowKey` reads the rows before it once, to place it
among equal records; `selectAll()` reads every row once for its key. Reader verbs still name a
row by the model index the snapshot published, performed on the UI thread after that frame on
the record at that index then. Pinned by `TableTest.aServerSortKeepsTheSelectionOnItsRecords`,
`refreshFollowsTheRecordsThroughAnInsertARemoveAndAReorder`,
`equalRecordsAreToldApartByOccurrenceAndARowKeyNamesIdentity` and
`TableAccessibilityTest.refreshKeepsTheCursorOnTheRecordItWasOn`. §4's "keeps selection stable
across a sort" and §6's sentence are true again by this rule rather than by index; §4's
"rebuilt ... on `setSort`, on `refresh` and on nothing else" was already loose — `setRows`
resorts too.

**Amended 2026-09-15 (fix round; decision 23, the node half).** "Reader verbs still name a row by
the model index the snapshot published" above was half of decision 23 left undone: a `ROW` was
keyed by its model index, so after an insert above, a published row's node named another record,
and a verb sent from the snapshot before the `refresh()` acted on whatever record stood at that
index when it arrived. A row is now keyed by its record's **row identity**: model row *m* is
published as *base + m* unless an override names it, which keeps a row's node across a scroll away
and back and across a sort (neither moves a model index). `refresh()` follows the rows the last
publish described — the only rows a reader holds a node of — by the same key and occurrence the
selection uses (keys taken at mount, the occurrence read the first time a row is described, one
pass for all rows that need it, nothing on a quiet frame): if each is where it was, nothing
changes; otherwise each found record keeps its identity as an override at its new row, every
other row takes a fresh range past every identity issued (so a verb for a record that left the
list is refused rather than landing on the row that took its place), and a record the list no
longer holds takes its identity with it. `setRows` issues a fresh range. A cell's key carries the
row identity (`CELL_KEY | identity << 20 | column`, identities below 2^40, the kind bits moved to
60–62) and a widget cell hangs `under` it. Without a reader nothing is described, so nothing is
followed and nothing is read. Pinned by
`TableAccessibilityTest.aRowNodeFollowsItsRecordAndAVerbActsOnTheRecordItWasPublishedFor` and
`equalRecordsKeepTheirNodesByOccurrence`.

**Amended 2026-09-15 (review of the fix round).** The amendment above held for one `refresh()`
per frame and for the rows of the last publish only. Two corrections. *Followed until replaced:* a
refresh that moved a followed record keeps following it at its new row until the next describe
publishes, so a second `refresh()` before the frame follows it again; until then the first refresh
forgot it, and an insert, `refresh()`, insert, `refresh()` left "Person 3"'s node on "Person 2".
*Never another record:* "the only rows a reader holds a node of" was wrong — a bridge may keep an
element it built from an earlier publish, and a row a reader was shown before scrolling away kept
an identity that a refresh leaving the rows in view in place did not follow, so after a reorder
out of view it named whichever record took that row. The table now notes when a published row
leaves the followed set (a scroll releases it, or a describe after a refresh leaves a followed row
out), and the next refresh then retires every identity it does not follow: an identity once
published names its record or nothing (semantics 8). A refresh with nothing left behind and every
followed record in place still changes nothing. The high-water mark moves with the identities
handed out rather than with the list's length, so retiring costs identities up to the deepest row
shown and not a list's length of them. Pinned by
`TableAccessibilityTest.aRowNodeFollowsItsRecordAcrossTwoRefreshesBeforeAFrame` and
`anIdentityOncePublishedNeverNamesAnotherRecord`.

**Amended 2026-09-15 (review of the fix round, the cost of an occurrence).** "The occurrence read
the first time a row is described, one pass for all rows that need it" was a read of every row
above the deepest newly described one, on every frame that described one, so a reader scrolling
to the bottom of 5,000 rows without a `rowKey` read 1,676,647 keys. An occurrence is now read
from an index kept for the list as it stands: each row's key hashed once and chained to the
nearest row before it with the same hash, its occurrence one more than the nearest such row
whose key is equal. The index grows as far down as a reader has been shown, is dropped by
`setRows`, `refresh()` and `rowKey`, and costs three `int`s per row read plus a hash table of two
to four more; the same walk reads 4,991 keys. With a `rowKey` nothing is read, as before; a quiet
frame reads nothing either way. The selection's own occurrences (§3's decision-23 amendment,
"selecting a row without a `rowKey` reads the rows before it once") are unchanged. Pinned by
`TableAccessibilityTest.aReaderScrollingToTheBottomReadsEachRowOnceForItsOccurrence` and
`equalRecordsAreToldApartFromUnequalOnesThatHashAlike`.

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

**Amended 2026-09-14 (TABLE-NEW-4 of the 2026-09-13 pass; settled as table-sort-request-slot).**
`onSortRequest` assigned its field directly — the one component registrar outside `Work` that
skipped `Checks.handlerSlot`, while ADR 040 §7 counted it among the thirty slots — and the slot
changing hands sorted nothing: a handler set over a toolkit sort left the permutation in place
until the next `refresh()`, and clearing it left the rows in whatever order the application had
put them, under a header still showing a sort. It is one slot now (`null` clears, a second
handler throws), and the change of hands re-runs the sort seam when the header shows an order:
setting a handler drops the permutation at once, so the rows show in the application's order,
and clearing it re-applies the table's own sort on the column the header shows — the focus cell
and the anchor go with their records as they do through any sort, and both are announced
`CHILDREN`/`CODE`, a caller's write that reaches no handler. Pinned by
`TableTest.theSortRequestSlotIsOneSlotAndChangingHandsReSortsAtOnce`.

**Amended 2026-09-14 (decision 36 of the 2026-09-13 pass, TABLE-SORT-KEYS).** Sorting was the
pointer's alone: "a click on a sortable header" was the only way to it. With a shown column that
can be sorted, the header is now a **focus stop of its own**, before the rows: Tab into the table
enters at the header, Tab again at the rows, Shift+Tab walks the reverse (`Widget#focusArrivedBackward`
was added for that mirror), and the table stays the one focusable widget — the stop is a state of
it, so the tree's focused node is the table in both stops. On the header, Left and Right move a
**column cursor** (swapped under RTL, as the focus cell's are), Home and End go to the ends, Space
sorts the column under it cycling ascending, descending and the model's order exactly as a click
does (and reaches `onSortRequest` the same way), Down hands the keyboard to the rows, and the
other row keys do nothing rather than move rows under a cursor that is not in them. A header
click sorts and leaves the keyboard in the rows, remembering the column for the next Tab into the
header; a click on a row takes the keyboard back from the header. Without a sortable shown column
there is no stop. The cursor is drawn as the same thin ring the focus cell wears, inset in the
header cell, and the focus cell's ring is not drawn while the header holds the keyboard; the
owner reviews it from `renders/table/header-focus-{dark,light,rtl}.png` of the pass, and the
mark may change. `Table.isHeaderFocused()` and `headerColumn()` answer the state. Pinned by
`TableTest.theHeaderIsAFocusStopOfItsOwnAndTheKeyboardSortsFromIt`; what a reader is told is in
§7's amendment of the same date.

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

**Amended 2026-09-14 (B6 and TABLE-NEW-5 of the 2026-09-13 pass; settled as
table-hidden-columns).** "Hidden and shown" was true of the band and false of a widget column:
`mount` built a widget for every widget column, hidden or not, and the never-laid-out widget was
a child — a Tab stop nobody could see, and a published node whose `CellFacet` column was the
table's column count, which the Windows GridItem pattern handed out as an out-of-range column
(B6). A hidden widget column now builds nothing. A column's visibility is read by the next
layout, which compares the shown set with the previous layout's and re-mounts the realized rows
when it differs, releasing a hidden column's widgets and building a shown one's; `refresh()` is
the call that asks for that layout, and a widget that held the keyboard hands it back to the
table as a recycled row's always has. And the focus cell, which was a shown index alone, follows
its **column** (TABLE-NEW-5): hiding the column it stands on moves it to the nearest shown
column — the one before on a tie — announced as `ACTIVE`/`ADJUSTMENT`, where before it kept an
index no column matched, drew no ring and made no cell `ACTIVE` until a Left or Right re-clamped
it; hiding a column before it shifts the index and moves nothing a reader stands on, so nothing
is announced. Pinned by `TableTest.aHiddenWidgetColumnBuildsNothingAndIsNoTabStop`,
`TableAccessibilityTest.aHiddenColumnPublishesNoCell` and
`TableAccessibilityTest.hidingTheFocusColumnKeepsACursorOnTheNearestShownColumn`.
**Amended again 2026-09-14 (review of the same pass).** The header's column cursor (§4's
decision 36 amendment) was left on a plain index clamp, so hiding its column put it on
whichever column the index named next — an unsortable one included — and hiding a column
before it slid it a column along, neither announced. It follows its column by the rule above;
while the header holds the cursor, that cursor's move is the one announced, and a layout that
takes the header's stop away under it (the header hidden, or the last sortable column) hands the
cursor to the focus cell and announces `ACTIVE`/`ADJUSTMENT` once. Pinned by
`TableAccessibilityTest.hidingTheHeadersColumnKeepsItsCursorOnTheNearestShownColumn`.
And "re-mounts the realized rows" above was more than the rule needed: every widget cell was
rebuilt on any change to the shown set, hiding a value column included, and a switch holding the
keyboard handed it to the table. The layout now releases only a hidden widget column's widgets
and builds only a newly shown one's, each in its place among the children, and every other widget
cell stays, a focused one included; `markNeedsLayout()` is the call that asks for that layout.
`refresh()` still rebuilds every realized row, as `ListView`'s does and for its reason — a mounted
widget is bound to a record the list may no longer hold — so a widget cell holding the keyboard
still hands it to the table there; `Column.visible` says both. Pinned by
`TableTest.aColumnShownOrHiddenLeavesEveryOtherWidgetCellAndTheKeyboardWhereTheyAre`.
**Amended 2026-09-15 (fix round; decision 22, the widget-cell half).** "`refresh()` still
rebuilds every realized row ... a widget cell holding the keyboard still hands it to the table"
is withdrawn: `ListView`'s reason does not reach a table, whose rows follow their records
(decision 23, §3's amendments). A `refresh()` — with or without a change to the shown set — and a
sort release every realized row except the one whose widget cell holds the keyboard; that row is
found again by its record (key and occurrence when known, else the equal key nearest where it
stood) and kept, re-bound to the row that shows the record now, its widgets children and the
focus where it was. It is released, and the keyboard handed to the table, only when the list no
longer holds its record or its own column is hidden. Its widgets are the ones built for the record;
its value cells are re-read. Pinned by
`TableTest.aRefreshOrASortKeepsTheWidgetCellThatHoldsTheKeyboardWhileItsRecordStays` and the tail
of `aColumnShownOrHiddenLeavesEveryOtherWidgetCellAndTheKeyboardWhereTheyAre`.

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
  `Column.widget` button — **corrected 2026-09-14, TABLE-NEW-8:** a button in a widget cell
  consumes its own press, so the table's `onActivate` does not fire and the row is not selected;
  the button opens the record through its own action, capturing the row from the factory that
  built it) and open a `Dialog`, or a form beside or below the table, that shows the
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
child at the cell's column of the table's header group, which is the table's first `GROUP` child
(**amended 2026-09-14, TABLE-NEW-11; settled as header-group-rule:** *first* held only while the
header is shown — with `setShowHeader(false)` and a footer, the footer group is the first `GROUP`
child, and every bridge handed out footer cells as column headers. The rule is: the header cell
of column *c* is the child with `CellFacet(-1, c)` of one of the table's direct `GROUP` children,
and a footer cell, row `-2`, never; a headerless table has no header group rather than an empty
one. The three bridges' lookups move to that rule in phase 3; the model already publishes it).
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
that holds the keyboard focus stays published wherever a scroll has taken the viewport
(**amended 2026-09-14:** read literally this held only for a row whose *widget cell* held the
focus; since decision 22 the focus cell's row is also kept and published, off screen, while the
table holds the keyboard — §2's amendment of the same date). The focus
cell is the node published `ACTIVE`, so the table's active descendant is a cell and not a row — a
reader that follows the active descendant lands on the value under the cursor (**amended
2026-09-14:** true of a widget column's cell too since B1 — the control's own node, hung under
its row by decision 3, is `ACTIVE` when the focus cell is on it; until then the ring was drawn
there and the active descendant fell to nothing). A `PRESS` on the
table activates the lead row; `SELECT` on a row selects it.

**Amended 2026-09-14 (decisions 10, 11 and 32 of the 2026-09-13 pass; TABLE-NEW-13).** The
verbs are the published ones and no other (ADR 039 §1.5's refusal contract of the same date). A
`ROW` publishes `SELECT` wherever a row can be selected (the click), `ADD_TO_SELECTION` on an
unselected row and `DESELECT` on a selected one only in `MULTI` (the command-click, through the
same toggle seam), and `FOCUS`, which moves the focus cell to that row and selects nothing; a
`CELL` publishes `FOCUS` alone, which puts the focus cell on it. `PRESS` on the table is published
whenever there is a focus cell, in every mode, and opens the **cursor row** — the focus cell's row,
which is the lead in `SINGLE`, may differ from it in `MULTI` after a toggle or a Shift range, and
is the only row there is in `NONE` — exactly as Enter and a double click do; `onActivate` receives
that row. A cell's synthetic key carries its row as well as its column, and a header cell's says
it is one: until this date a cell was keyed by its column alone, the table read any key below
the row count as a row index, and a reader's select on cell (0, 1) selected row 1 (TABLE-NEW-13,
found by the verb ratchet). Pinned by `TableAccessibilityTest.aRowOffersTheVerbsItsStateAllowsAndTheTablePerformsThem`,
`focusOnACellOrARowMovesTheCursorAndSelectsNothing`, `aCellAndAColumnHeaderRefuseTheSelectOnlyARowPublishes`,
`aPressOnTheTableOpensTheCursorRow` and `TableTest.enterAndADoubleClickActivateTheCursorRow`.

**Amended 2026-09-15 (fix round; decision 20 and semantics 5).** `ADD_TO_SELECTION` and
`DESELECT` on a row change that row's membership and the lead, and leave the focus cell and the
range anchor where they stand, scrolling nothing: only `SELECT` and `FOCUS` move a cursor. They
share the toggle seam with the command-click and Space, which still move both to the row (a
gesture is made where the cursor goes; a reader's verb is not). An amendment of 2026-09-14 that
recorded the verbs moving the cursor as an open reading is withdrawn: decision 20 and semantics 5
settle it. Pinned by `aRowOffersTheVerbsItsStateAllowsAndTheTablePerformsThem` (the verbs) and
`TableTest.multiSelectionFollowsThePlatformsGrammar` (the gesture).

**Amended 2026-09-14 (decision 36; the header's stop, from the reader's side).** While the header
holds the keyboard the table is still the focused node and its cursor is the header cell under
the column cursor — that `COLUMN_HEADER` is `ACTIVE`, and no `CELL` is — so the effective focus of
ADR 039 §1.10's amendment lands on the column title and one `ACTIVE_DESCENDANT_CHANGED` says so;
Tab back to the rows returns it to the focus cell. A header cell of a sortable column publishes
`PRESS`, which sorts as a click does; the header of the column the rows are ordered on carries
the direction as its **description** (`TableStrings.SORTED_ASCENDING` / `SORTED_DESCENDING`, the
`table` string domain, 21 locales), and the others describe nothing. **Left for phase 3:** a
sort-direction facet or state once the three platforms' carriers of one have been read on the
guests (UIA has none native to a header item beyond a property a provider may expose; AT-SPI an
object attribute; AX `AXSortDirection` on a column) — until then the description is the carrier,
and a bridge maps nothing special. Pinned by
`TableAccessibilityTest.theHeadersColumnCursorIsTheCursorWhileTheHeaderHoldsTheKeyboard`.

**Amended 2026-09-15 (decision 36's carrier, settled after phase 3): the facet, and the description
beside it.** The item left for phase 3 above is closed, and the three readings came back disagreeing
about the *shape* rather than the fact: Windows wants a phrase (`ItemStatus`, File Explorer's
convention, and `HelpText` too because NVDA 2024.4.2 has no `ItemStatus` handler and speaks a
description), Linux Orca's `sort` object attribute, macOS `AXSortDirection` — an enumeration on two
platforms and words on the third. So the model carries both, and neither replaces the other.
`CellFacet` gains `Sort` (`NONE`, `ASCENDING`, `DESCENDING`; ADR 039 §1.2's amendment of this date),
declared through `Accessibility#cell(row, column, sort)`, and `Table` sets it on the header cell of
the column the rows are ordered on; every other cell, header or not, carries `NONE`. The description
stays exactly as it was. That is the point of keeping it: a bridge reads the snapshot on a
platform's own thread where no locale scope is open, so a phrase has to be resolved at publish, and
an enumeration is what the other two want rather than a translated sentence they would parse back.
No new string — `SORTED_ASCENDING` and `SORTED_DESCENDING` already ship in 21 locales — and Orca's
fourth value `other` is left out, because nothing in this widget sorts that way. The three bridges'
mappings are each their own lane's. Pinned by
`TableAccessibilityTest.aSortedHeaderCarriesItsDirectionOnItsCellFacetAndKeepsThePhraseInItsDescription`
and, for the differ, `AccessibleLiveMutationTest.aSortDirectionThatIsTheOnlyChangePublishesATree`.

**Per platform**, what the facets become:

| Platform | Table node | Cell node | Header cell |
| --- | --- | --- | --- |
| Windows | `DataGrid` control type; `IGridProvider` (`RowCount`, `ColumnCount`, `GetItem`) and `ITableProvider` (`GetColumnHeaders`, `RowOrColumnMajor`) alongside the existing `ISelectionProvider` and `IScrollProvider` | `DataItem`; `IGridItemProvider` (`Row`, `Column`, spans of 1, `ContainingGrid`) and `ITableItemProvider` (`GetColumnHeaderItems`) | `HeaderItem` |
| macOS | `NSAccessibilityTableRole`; `accessibilityRows`, `accessibilityColumns`, `accessibilityHeader`, `accessibilitySelectedRows`, `accessibilityRowCount`, `accessibilityColumnCount` | `NSAccessibilityCellRole`; `accessibilityRowIndexRange`, `accessibilityColumnIndexRange` | the header group's children, each `NSAccessibilityCellRole` under `accessibilityHeader` |
| Linux | `ROLE_TABLE`; `org.a11y.atspi.Table` (`NRows`, `NColumns`, `GetAccessibleAt`, `GetColumnHeader`, `GetSelectedRows`) alongside `Selection` | `ROLE_TABLE_CELL`; `org.a11y.atspi.TableCell` (`Position`, `RowColumnSpan`, `Table`, `ColumnHeaderCells`) | `ROLE_TABLE_COLUMN_HEADER` |

**Amended 2026-09-14 (B7).** The Windows row's "alongside the existing `ISelectionProvider` and
`IScrollProvider`" overstates what is served: `UiaPatternProviders.interfaceFor` has no entry for
the Selection or the Scroll pattern, so no container — the table included — vends either, while
`UiaPatterns.supports` still answers true for both and `patternProvider` returns `S_OK` with a
null provider. A Windows client reads the table's selection through the rows' SelectionItem
pattern and its scroll not at all. A shared bridge defect the bridges lanes own, not this
record's promise fulfilled; the row stands as the intent.
**Amended 2026-09-15 (phase 3, Windows; W1).** Both are now served: `ISelectionProvider` since
b5d5f59 and `IScrollProvider` since the Windows lane's scroll commit, and `UiaPatterns.supports`
claims no pattern `interfaceFor` cannot serve (`UiaPatternsTest.everyPatternANodeCanClaimIsOneThisBridgeServes`).
The table's scroll reads its scroll facet and scrolls through its own scroll bar's published verbs
(ADR 039 §2.1, amended the same day).

**Amended 2026-09-15 (phase 3, the macOS row; MACOS-NEW-4, MACOS-NEW-9, MACOS-NEW-10).** The macOS
bridge's lookups follow the header-group rule and ADR 039's semantics 2: a cell is found by its own
`CellFacet` under the table's rows (the widget cell under its row included), a row's `AXIndex` is its
cells' row, and the header of column *c* is matched by `CellFacet(-1, c)` among the table's direct
groups, so a headerless table answers no `accessibilityHeader`. The row's "header cell" column is
still what the bridge vends as written: `COLUMN_HEADER` is `NSAccessibilityButtonRole` with the
sort-button subrole, which is what a native `NSTableView` read on the guest vends for its header
cells (2026-09-15, `scripts/a11y/macos/table-probe.swift`), not `NSAccessibilityCellRole`.
The row's `accessibilityColumns` is now true (M4, decision 34, the same day): one `AXColumn` element per
shown column, as the native table vends, answering its index, its header cell and its cells; ADR 039
§2.2's note of the same date says how they are kept.
The macOS row as built, the same day (MACOS-NEW-7): table node `NSAccessibilityTableRole` with
`accessibilityRows`, `accessibilityVisibleRows`, `accessibilitySelectedRows`, `accessibilityColumns` and
`accessibilityVisibleColumns` (column elements), `accessibilityHeader` (the header group, none when the
header is hidden), `accessibilityRowCount` and `accessibilityColumnCount`, and the parameterized cell
lookup; cell node `NSAccessibilityCellRole` with the two index ranges and its column header; header cell
`NSAccessibilityButtonRole` / `NSAccessibilitySortButtonSubrole` under `accessibilityHeader`. **Sort
direction, read and not served:** a native header button answers `AXSortDirection` as
`AXUnknownSortDirection`, `AXAscendingSortDirection` or `AXDescendingSortDirection`, and an element whose
`accessibilitySortDirection` (`q16@0:8`) answers 1 or 2 reads ascending or descending (read on the macOS
26.6.2 guest, 2026-09-15, `table-probe.swift`); the bridge maps nothing yet, because the direction is
carried only as the sorted header's localized description and a bridge cannot read a direction out of a
translation. The facet or state this record left for phase 3 is the model's to add.

**Amended 2026-09-15 (phase-3 fix round; semantics 2 and 3, the minor splits closed on Windows).**
The three bridges' cell lookups agreed on the rule and disagreed on two details, which the phase-3
critic listed and the orchestrator settled to one shape. On Windows: **the climb from a cell to its
table starts at the cell's parent**, where it started at the cell itself, so a table nested inside a
cell of another was its own containing grid and the outer table's `GetItem` could not find it at all
(`UiaPatternProviders.cellAt` rejects a cell whose table is not the one asked); Linux's `belongsTo`
and macOS's `tableAtOrAbove` started at the parent already. And **a cell's column header is answered
for data cells and footer cells and never for the header cell itself**, which answered itself, so a
client walking `GetColumnHeaderItems` from a header walked back to where it started; Linux excluded
the header already, macOS answers data cells only, and the footer half is this bridge's, where the
model's footer row summarises the column above it. The table-level list was already the union over
every direct `GROUP` child carrying `CellFacet(-1, c)`, which is the settled rule. Neither detail
changes anything for today's `Table`, which nests no table in a cell; both are pinned, by
`UiaPatternProvidersTest.aNestedTablesOwnCellBelongsToTheTableAboveItAndNotToItself` and
`aCellsHeaderItemsAreNoneForTheRowAndTheHeaderOfItsColumnAndNoneForAHeader`.

**Amended 2026-09-15 (semantics 2 and 3, the three splits closed after phase 3).** The paragraph
above describes the macOS lookups as phase 3 built them, and the three bridges read three of their
corners differently; the orchestrator settled one reading and this bridge now holds it. A **row's
`AXIndex`** is its cells' row only where that cell's nearest table is the table the row is a row of,
so a row that carries a table facet of its own — a nested table — has no index rather than its
nested cells' row numbers (`cellAt` already applied that rule, and `index` did not). A **table's
`accessibilityColumnHeaderUIElements`** is the union over every direct group child that carries a
`CellFacet(-1, c)`, not the first such group alone; `accessibilityHeader` still names one group, the
first, since AppKit asks there for one element. And a **cell's own column header** is answered for
data cells and footer cells and never for a header cell, which would answer itself; a footer cell —
the summary a column pins under its rows — was told nothing about its column here. None of the three
changes what today's `Table` publishes, which is one header group, no nested tables, and a footer
whose cells now say which column they summarise. Pinned by `AxGridTest`'s
`aRowWhoseCellsBelongToANestedTableHasNoIndexOfItsOwn`,
`aTablesColumnHeadersAreTheUnionOverEveryGroupThatHoldsOne` and the restated
`aDataCellsColumnHeaderIsTheHeaderGroupsChildAtItsColumnAndOtherRowsHaveNone`.

**Amended 2026-09-15 (the fix round's integration): `AXSortDirection` is served, and the paragraph
above's "the bridge maps nothing yet" is closed.** The model's carrier landed in the same round
(`CellFacet.Sort`, the amendment of this date above), but on the model branch while the three bridge
lanes were running, so each lane logged its own mapping as owed rather than compiling against a type
its branch did not have; the mappings are the integration's, and each was written out whole in its
lane's log against a reading that lane had already taken. On macOS: `accessibilitySortDirection`
(`q16@0:8`, `@protocol NSAccessibility` required, answered by `NSAccessibilityElement` — the
committed dump, lines 207, 303 and 399) answers `NONE` → 0, `ASCENDING` → 1, `DESCENDING` → 2, the
`NSAccessibilitySortDirection` numbers the dump reads off the SDK (lines 138-140) and records in the
same breath as exported by no symbol of this AppKit, so they are literals under ADR 039 §12.3's
narrow exception. `AxGate` offers the attribute on a **header cell** and on nothing else, which is
the probe's own shape rather than a rule invented here: each of the native table's three sort buttons
listed `AXSortDirection` in its `AXAttributeNames`, and the table, its columns and its rows each
answered `AXError(-25205)` for it (readings/macos-table-probe.txt lines 221-291). So a header of an
unsorted column answers `AXUnknownSortDirection` rather than refusing, as two of the probe's three
headers did, and `AxGate.NOT_ON_A_COLUMN` gains the selector so a column element cannot answer
`NSAccessibilityElement`'s stored 0 where the native column answers an error. The dump script's
`installedByTheBridge` gains the selector; the committed dump is **not** regenerated, because the
encoding was already in its table. Pinned by
`AxGridTest.aSortedColumnsHeaderAnswersItsDirectionAndAnUnsortedOneAnswersUnknown` and
`AxGateTest.aHeaderACellLookupAndARangeAreOfferedOnlyWhereTheyHaveAnAnswer`. What a live VoiceOver
speaks for it is phase 5's, and `AxNotifications` still has no row saying "the sort changed" — the
model announces it as a change on the header — which is worth one look in the same run.

### 7.1 What the live clients found

Two defects, both invisible to the headless tests because both are about what the platform's
own client reads rather than what the bridge writes.

- **AT-SPI answers several out arguments, not one struct.** `GetRowColumnSpan` returns `iiii` and
  `GetRowColumnExtentsAtIndex` returns `biiiib`, four and six out values; a reply whose signature
  was `(iiii)` was refused by libatspi on Fedora with "expected iiii". A property such as
  `Position` is a struct, `(ii)`, which is the opposite rule and the reason the first draft was
  wrong.
- **UI Automation reads the declared interface, not the object.** A grid's `GetItem`, a cell's
  `get_ContainingGrid`, an item's `get_SelectionContainer` and every element of a headers array are
  declared `IRawElementProviderSimple`; a fragment pointer handed back through one of them made
  the .NET client fail its cast, while the same pointer through `Navigate`, declared as the
  fragment, was right. The bridge now hands out the simple interface where that is the declared
  type. `get_SelectionContainer` had the same defect since ADR 039 and was corrected with it.

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

**Amended 2026-09-14.** ADR 040 landed on 2026-09-09 and the table moved onto it: the three are
single handler slots under `Checks.handlerSlot`'s one-null policy (`onSortRequest`'s since
TABLE-NEW-4, §4's amendment of this date), reached through `handleUserChange` for the user's
gestures alone, and `observeChanges` hears every change with its origin; ADR 040 §7.2 records
the table's seams. The sentence above stands as the record of what was decided.

---

## 9. Not a tree, and not shaped for one

A `Tree` and a `TreeTable` are wanted, and they will reuse the row engine — an anchor over rows of
uneven height, a focus cell, a header band — and add what a tree needs: a depth per row, an
expand facet per row, and an order that is a traversal rather than a list. None of that is in this
record, and the table is not given a `depth` hook it does not use: a widget shaped around a
sibling that does not exist yet is the chart hierarchy the coverage test refused (the chart hooks
hoisted to `CartesianChart` and reverted, 2026-09-08). When the tree arrives, what the two share
moves into a package-private engine, and that is the day to decide its shape.

**Superseded 2026-09-14 by ADR 044 §3.** The tree arrived and walks its own rows; the shared
engine is owed until a `TreeTable` asks for it, and the row rules — the kept cursor row, the
seed height, the wheel handed on at the ends — exist in three copies (`ListView`, `Table`,
`Tree`) that ADR 044 §3 names and each widget's lane changes in step.

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

**Amended 2026-09-14 (B7).** Phase 2's "a `Tree` that shares the engine" did not happen that way:
§9's supersession. Of the first phase's promised tests, "mirroring" arrived only on this date as
`TableMirroringTest` (B2), and "an accessibility gallery entry with its transcript" was completed
the same day (B5: the entry gained `MULTI`, a footer and a named switch column, and its
transcript is the committed golden `limn-demo/src/test/resources/limn/demo/a11y/table.txt`).
Landed since by the 2026-09-13 pass, none of them a phase: a row is its record (§3), the header's
focus stop (§4), the kept cursor row and the per-child clip (§2), hidden columns (§5), the
two-axis wheel handed on at the ends and the seed height (§2), the sort-request slot (§4), and
the verbs by state (§7).

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

**Amended 2026-09-14 (B7).** The list above is the first phase's. Since then: `TableTest` also
holds the record-following `refresh()`, the sort carrying the cursor, the header stop, the
sort-request slot, the two-axis wheel and the hand-off to the scroller at either end, the seed
height, hidden widget columns, and horizontal scrolling with column virtualization;
`TableAccessibilityTest` the verbs by state, the header's cursor, the `ACTIVE` widget cell,
hidden columns, columns published off screen and the unflipped horizontal percent;
`TableFocusedRowTest` (six cases) the two kept rows and the clip; `TableMirroringTest` (eight
cases) the right-to-left placement; and `DamageContractTest`'s Table row the wheel on both axes
and a horizontal focus move, under ceilings measured on 2026-09-14. The gallery's transcript is
committed as `limn-demo/src/test/resources/limn/demo/a11y/table.txt` on 2026-09-14, read line by
line and explained in its commit, but **not read aloud**: the read-aloud this section asks for
before a transcript is committed is the owner's, and is owed before phase 5's live run (this
amendment first said it had been done; it had not, and the sentence was corrected the same day).
Still owed: a live screen-reader run over the table on each guest (B9, phase 5) — the 2026-09-09
runs were client walks through the probe scripts, and no reader has yet spoken a Limn table;
its recipe should include Shift+Tab into the header, Right, Space, Right into the switch column,
and a wheel away from the cursor row.

**Amended 2026-09-15 (fix round; decision 36's damage rows).** `DamageContractTest`'s Table row
now also drives the header's stop: Shift+Tab into the header, Right and Left on it (each the header
band, measured at 14% of the box in two identical runs; ceiling 20%) and Space sorting the column
under its cursor (101%, the box plus the damage margin; ceiling 105%). Putting Space under the
contract found every sort — a header click, Space, `setSort` — asking for a full layout and so
repainting the whole window; a sort cannot change the table's size, and it asks for a contained
layout now. `aMountedWidgetsBarsHaveFadedBeforeTheFirstGesture` holds the harness to measuring
each row from rest, bars faded.
