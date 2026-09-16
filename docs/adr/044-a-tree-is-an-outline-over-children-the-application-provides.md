# ADR 044: A tree is an outline over children the application provides, and a row may promise children before it can name them

- **Status:** Proposed, 2026-09-12. Phase 1 is the widget and its three-platform accessibility;
  §8 says what is deliberately not in it and §9 what each later phase is.
- **Date:** 2026-09-12
- **Scope:** the toolkit's first tree: what its model is, how a row is expanded and how children
  that are not there yet arrive, how it virtualizes, what the keyboard does, how a screen reader
  reads it on three platforms, and what it does not do. Columns are not here — a `TreeTable` is
  §9's, not this record's.
- **Compatibility:** nothing published changes. `ListView` and `Table` are untouched; this record
  adds a package beside them. Two accessibility roles are added to a closed enum, which costs the
  three platform tables §4 accounts for.

---

## 0. The starting point

The market survey of 2026-09-08 put a tree at the top of the component gaps that survived the
table, and [ADR 041](041-a-table-is-columns-over-a-list-the-application-owns.md) §9 reserved the
question rather than answering it: *"A `Tree` and a `TreeTable` are wanted, and they will reuse the
row engine … When the tree arrives, what the two share moves into a package-private engine, and
that is the day to decide its shape."*

This is that day, and the shape decided here is **not** the one that sentence anticipated. §3 says
why, and what that costs.

What exists to build on: `ListView` (rows are pooled widgets over a flat `Adapter`) and `Table`
(rows are slots over a flat `List<T>`, with an anchor-and-walk virtualizer, a focus cell and a
header band — all of it private to `Table.java`). Neither takes an abstract row source: `ListView`
asks `rowCount()`/`rowAt(int)`, `Table` holds `List<T>` and a permutation. A tree's order is a
traversal, and its row count changes when a row opens.

## 1. Decision: phase 1 is an outline, not a tree-table

`limn.components.tree.Tree<T>` is **one column of rows at a depth**: an indent, a disclosure
triangle where a row can open, and whatever the application's cell draws. No column headers, no
column model, no horizontal virtualization.

**Amendment, 2026-09-12: the outline scrolls sideways, which is not the same thing as horizontal
virtualization.** The sentence above rules out realizing part of a row's width; it was read once
as ruling out a horizontal offset too, and that reading cost a real defect. Depth charges an
indent per level and never gives it back, so past some depth the cell carrying the name begins
beyond the far edge of the box. The shipped answer clamped the indent, which makes a deep tree
lie about its own shape — level twelve drawn where level eight sits, flattening exactly the
structure someone navigating deeply is reading. The content is now as wide as the deepest row
needs (`max(viewport, depth × indent + band + menuMinWidth)`) and the box scrolls over it, with
the horizontal bar, the wheel convention and the mirrored offset all the table's. Every mounted
row is still laid out at its full width: nothing about a row's width is virtualized.

The consequence is worth stating because it cannot be had both ways: **widening the content moves
where a cell ellipsizes**, from the edge of the box to the edge of the content. A tree deep
enough to scroll sideways is a tree whose long names run on until the content ends. Where nothing
is deep the maximum is the viewport, the content is exactly the box, and the cell widths and the
ellipsis are unchanged — which is what keeps the shallow case identical to what this record
originally specified.

**Amendment, 2026-09-14: the model may declare the deepest cell's width, and the keyboard reveals a
deep row sideways.** The `menuMinWidth` in the formula above was a guess borrowed from a menu
token (168 points at the default size), and a model whose rows are a name and a badge had no way
to say its cells need more (T4). Decision 50 (`tree-deep-cell-width`) adds a defaulted
`Model.maxCellWidth()`: a positive, finite answer replaces the guess, so the content is
`max(viewport, depth × indent + band + maxCellWidth)` and the deepest open row's cell is exactly
that wide; zero, the default, keeps `min(viewport, menuMinWidth)` and today's behaviour. The declared
width is a cap and not a demand: it is taken as `min(maxCellWidth, viewport − band)`, what the box
gives a root row past its triangle, so a flat tree whose model declares more than its box is still
exactly the box and a deep tree's outline grows by its indent alone (a first cut took the declared
width whole, and one row under a 300-point declaration in a 220-point box scrolled sideways; found
in review later the same day, `TreeTest.aDeclaredWidthWiderThanTheBoxNeverScrollsAFlatTreeSideways`).
The undeclared guess keeps its viewport cap unchanged, so in a box narrower than the band plus
`menuMinWidth` (190 points at the default size) a flat tree still overhangs by up to the band, as it
did before this date. Cells are
still laid out to the content's far edge, so a shallower row is one indent wider per level — the
renders of `--scene tree-deep` (`renders/tree/deep-after-*`) show what that costs a row ending in a
button, and decision 50 lets him move to "cap and unstretched cells" on them. Separately, the
reveal that follows the cursor used to pass a zero-width rectangle and never moved sideways, so End
onto a deep row left its name past the box's edge (TREE-NEW-5): a keyboard, reader or caller move
now also reveals the row's triangle band plus the leading part of its cell (as wide as the deepest
cell is promised, never wider than the viewport), by the least that shows it, measured from the
viewport's own edge past a reserved strip. A pointer press does not reveal sideways, because the
pointer is already on a visible part of the row.

The reason is that the two hard parts of a tree — an order that is a traversal and a row that can
open — are separable from the two hard parts of a table, which are columns and a focus cell, and
a first record that took all four would decide the cheap half badly. A `TreeTable` is §9.

## 2. The model: the application owns the nodes

A tree is given **roots** and a **children provider**; it never owns a node type of its own. As
with `Table`'s `List<T>`, the application's objects stay the application's:

- `Tree<T>` holds `List<T> roots` and a `Function<T, List<T>> children`.
- A row is a **leaf** when a predicate says so, and not when its child list is empty: a directory
  that has not been read is not a file.
- Identity is the node object's own `equals`/`hashCode`, and expansion is a `Set<T>`. An
  application whose nodes are records gets identity by value for free; one whose nodes are
  mutable gets reference identity, which is what a tree over a live model wants.

**Amendment, 2026-09-14: the shipped model is the `Tree.Model` interface, and the bullets above
are the record's first sketch of it (TREE-NEW-12).** `Tree<T>` is constructed over one
`Model<T>` with three methods only the application can answer — `roots()`, `children(node)`,
`cellFor(node)` — and four with defaults: `isLeaf(node)` (children known and empty), `load(node)`
(`null`: nothing to fetch), `recycle(cell)` (nothing) and `nameOf(node)` (`null`: the cell names
its row; a name from here wins over the cell's). There is no `Function<T, List<T>>` and no
separate leaf predicate; the identity rule below stands as written. Later the same day a fifth default
joined them, `maxCellWidth()` (§1's amendment of that date).

**Amendment, 2026-09-14: a node is unique within a tree, and the tree says so.** Identity by
`equals` was written above as a gift to records and a convenience to live objects; what it also
does is fold two equal nodes in two places into one — one selection, one expansion, one accessible
identifier, and under partial rendering a second row whose wash goes stale while the first is
damaged (TREE-NEW-7). The guide's own example produced that shape: every folder read off a disk
answered a `classes` and a `reports`. Decision 15 (`tree-node-identity`) keeps `equals` as the
identity and makes the contract explicit instead of changing it: **a node must be unique by
`equals` within a tree.** Building the visible rows refuses a duplicate as soon as both are
visible, with an `IllegalStateException` naming the node and the remedy; a model whose values
repeat under different parents gives its nodes path identity, which is what the guide's `Entry`
does now (its path is the record's identity, its name is what the row shows). Path identity was
weighed and not taken: it would have changed the public meaning of `setSelected`, `expand`,
`isExpanded` and every identifier, for every model, to solve a shape the application can name.

**A row may promise children before it can name them.** The provider has an asynchronous form:
the application hands back a `Work<List<T>>` ([ADR 020](020-background-work-is-a-job-with-a-lifecycle.md)),
the row shows a busy state while it runs, and the children arrive on the UI thread. Cancelling is
the job's, so collapsing a row that is still loading cancels it rather than leaving a thread to
deliver into a row nobody is looking at. This is in phase 1 deliberately: a tree whose first real
use is a file system or a remote catalogue and cannot load on demand is a tree that has to be
rewritten by its second caller.

**Amendment, 2026-09-13: what the busy state looks like, decided by him.** Phase 1a recorded that a
row was loading and drew nothing for it, so an open row whose load had not landed looked exactly
like an open row with no children. That is the statement a failed load closes its row to avoid.
He chose all three of the following, from renders:

- **A spinner where the open triangle goes**: a three-quarter arc with the triangle's pen, colour
  and width, so nothing beside it moves when the children land. It turns once a second on wall
  time, as the progress bar's sweep does, because the load does not pause with the scene's clock.
  It damages only its band, and the damage ratchet holds a turning frame to 0.5% of the tree.
- **And a muted "Loading…" line** one level in, where the children will go. The words come from
  the tree's own catalog (`limn.tree.loading`, with the twenty-one translations). The line is the
  tree's widget and not a node: it is not counted as a visible row, it is never handed to the
  model to recycle, the arrows walk past it, and a click on it lands on the row it belongs to.
- **Shown at once**, with no delay before a fast load would show it.

To a reader the row is `BUSY`, and the line is not an item at all, so a reader never stands where
the cursor cannot go, and "2 of 5" counts nodes. On Linux, AT-SPI's busy bit was already mapped.
UI Automation has no busy bit, so on Windows `BUSY` is the item's `ItemStatus` string. That is a
phrase in the node's own language, from a new `StateNames` catalog (`limn.state.busy`, twenty-one
translations), and a property-changed event is raised when the row starts and stops. No busy state
had been mapped on Windows for any widget before this, so the progress bar's indeterminate `BUSY`
now reaches it too.

AppKit's NSAccessibility protocol has no busy property either. So on macOS, `BUSY` is the legacy
`AXElementBusy` attribute, served by overriding `accessibilityAttributeValue:` and
`accessibilityAttributeNames` on the element class, with every other attribute forwarded to the
implementation `NSAccessibilityElement` already has. `AXElementBusyChanged` is posted when the
state moves. Both names are `CFSTR` macros in HIServices with no symbol behind them, which makes
them the constants rule's second exception after the announcement priorities: they were read off
this build's SDK, not recalled. On the development Mac, `scripts/a11y/macos/axbusy.swift` read
the demo (`--scene tree-loading`, with the load lengthened) through the AX API: Remote answered
`AXElementBusy` 1 while loading and 0 once its children landed, every row listed the attribute,
and roles, subroles and titles still came through the forward. No reader has spoken any of this
on either platform: VoiceOver was not run, and the Windows guest was not up.

**Amendment, 2026-09-14: a row whose load finds nothing stays open, over an "Empty" line.** The
loading line used to vanish when an empty list landed and leave an open triangle over nothing,
which is the statement a failed load closes its row to avoid (TREE-NEW-13). Decision 45
(`tree-empty-load`, not the recommendation, which was to make such a row a leaf) keeps the row an
open branch, the way Finder shows an empty folder, and puts a discreet muted **"Empty"** line one
level in where the children would be — the loading line's place, style and rules: the tree's own
widget, not a node, never recycled to the model, walked past by the arrows, a click on it lands
on its row, and to a reader not an item (the row publishes the expanded state and no children,
and is no longer `BUSY`). The words are `limn.tree.empty`, in the twenty-one tree locale files.
For consistency the same line is shown under an eager branch the model calls a non-leaf over an
empty list (`isLeaf` answering `false` for an empty folder, the guide's `Entry` shape); that half
was offered to him on the renders (`renders/tree/`) to confirm. Right on such a row stays on it
(§5). A cached empty answer opens onto the line at once, without a second load.

## 3. Decision: the tree walks its own rows, and the shared engine is owed rather than taken

ADR 041 §9 said the tree's arrival is when the shared part becomes a package-private engine. It is
not being taken now, and that is a decision rather than an omission:

- `Table`'s virtualizer is private to a 2331-line class that ships, is read by three screen
  readers and has its own contract tests. Extracting it *and* writing the first tree in one pass
  puts a refactor of a shipped widget and a new widget's design on the same commit.
- What a tree needs from it is the anchor-and-walk over rows of uneven height and the
  keep-the-focused-row rule ([ADR 039](039-an-accessible-tree-is-a-snapshot-and-the-platform-reads-it-on-its-own-thread.md) §13.29).
  Both are small and both are already written twice (`ListView` and `Table`), so a third
  copy is the cost being accepted here, with its size known.

**The debt is named, not implied:** when `TreeTable` lands (§9), the three copies collapse into one
package-private engine, and that record decides its shape with three real callers in hand instead
of one imagined one. Until then, a change to the anchor rule has to be made in three places, and
this paragraph is what tells the next person that.

**Amendment, 2026-09-14: the tree under a parent that gives it no height, and the wheel at its
ends (decision 44, `rows-unbounded-height`; the same rule in `ListView` and `Table`).** Under an
unbounded height the tree is `visibleRows` (default 8, `setVisibleRows`) seed rows tall — the size
step's `listRowSeed`, never the mean of the rows it happens to have mounted. It shipped answering
the mean, which is the seed before the first pass and the rows' own height after it, so its first
contained layout inside a column moved its size, escalated to the parent, and every later scroll
that mounted rows of another height moved it again (T5). The wheel is consumed only where the
tree moved: a detent that finds the tree at either end of its scroll, on the axis the detent
points along, is left for the scroller that holds it, as a tree whose content fits already left
every detent; and one event carrying both axes — a trackpad's diagonal flick — scrolls both
(TABLE-NEW-12, the same code in the table), where before the sideways half was taken and the
vertical half dropped. Shift turns a notch sideways only when the event has no sideways half
of its own, as the scroll pane and the table do: macOS delivers Shift and a notch as scrollX
already, and a first version of this rewrite that read the sideways axis from scrollY whenever
Shift was held scrolled nothing for that event and passed it to the parent (found in review later
the same day). Where the tree's own end is, the wheel reads off the scroll estimate, whose mean
row height is now taken over the rows the pass placed and not every mounted cell: the cursor row
kept realized out of view while the tree holds the keyboard (decision 22), averaged in at another
height, put the estimate's end off the real one, so a taller kept row walled the wheel at the end
and a shorter one stopped the tree short of its last row (also found in review). Headless,
`TreeTest` holds all of it over a tree inside a `ScrollView`; a trackpad's momentum events over
the same fixture are phase 5's live check.

**Amendment, 2026-09-15: a reveal of the kept cursor row scrolls to where it stands.** The kept
row is laid out at the viewport's edge, not at its place in the outline, and the reveal took a
mounted row's box as its position, so `SCROLL_INTO_VIEW`, `SELECT` or `FOCUS` on that row, or Space
on it, scrolled one row's height and left it outside the box — reachable since the scene stopped
refusing a delegated verb on a row that is not showing (ADR 039 §1.5's amendment of this date). A
mounted row whose box does not overlap the viewport is now revealed from the anchor's estimate,
as an unmounted row is. Pinned by `TreeAccessibilityTest.aRevealOfTheKeptCursorRowBringsItBackIntoTheBox`.

**Amendment, 2026-09-15 (review of the above): the reveal of a row outside the box is settled by
the next pass, from measured heights.** "Revealed from the anchor's estimate" is superseded: the
estimate counts the rows between the anchor and the row in average rows, and the average is taken
over the rows in the box, so over rows of uneven height the scroll stopped short and the row stayed
out (ten rows of eighty points between two runs of twenty left row 2 out above the box). A row
whose box does not overlap the viewport, mounted or not, is now handed to the next layout pass,
which sets the anchor on the row itself — its top on the box's top when it lies above, its bottom on
the box's bottom when it lies below (its top on the top when it is taller than the box) — so the
distance is exact whatever the rows between measure. Nothing moves until that pass, so a later
reveal of a row in the box, or a scroll, replaces an unsettled one: End then Home in one input batch
ends on the first row. Pinned by `TreeAccessibilityTest.aRevealOverRowsOfUnevenHeightLandsTheKeptRowAtTheEdgeItComesInFrom`
and `theLaterOfTwoRevealsInOneBatchIsTheOneThatLands`.

## 4. Accessibility: two new roles, and what they cost

A tree publishes `TREE`, with one `TREE_ITEM` per realized row carrying `ExpandFacet`, its depth,
and its position among its siblings. Unrealized rows are not published, as ADR 039 §11 already
accepts for lists and tables.

Two constants join a closed enum, which ADR 039 §1.12 prices at three platform tables, a phrase
and twenty-one translations. Where they come from:

- **Windows** — `UIA_TreeControlTypeId` and `UIA_TreeItemControlTypeId` are already in `UiaIds`,
  read off the guest for the table's pass; `ExpandCollapsePattern` is already served.
- **macOS** — `NSAccessibilityOutlineRole` and `NSAccessibilityOutlineRowSubrole` are already in
  the checked-in AppKit dump, taken on this build for the table's pass. **What is missing there is
  not a constant but a consumer**: no `ExpandFacet` reader exists under `a11y/macos`, so
  `AXDisclosing` and `AXDisclosureLevel` are net-new work in this phase.
- **Linux** — the AT-SPI numbers are **not** in the tree and must not be written from memory:
  `AtspiRoles`' own rule is that every number was read off a machine, by
  `scripts/a11y/linux/dump-atspi-constants.py`, and `AtspiConstantsTest` fails a role with no
  number. Phase 1 therefore has one gate outside this repository: run that script on the guest and
  paste what it answers.

**Depth and position-in-level do not exist in the facet model** — `SelectionItemFacet` is flat and
model-numbered — so phase 1 adds them, and each of the three bridges gains the one property that
carries them. That is the largest single piece of this record's accessibility work, and it is the
piece a reader actually hears: "level 3, 2 of 5" is how a tree is navigated without sight.

**Amendment, 2026-09-14: depth and position-in-level exist in the facet model now, and the
sentence above is history.** The model gained `HierarchyFacet(level, row, rowCount)` (ADR 039
§1.2, amended 2026-09-14): the level from one, the row's flat one-based index among the open rows,
and how many there are, published on every `TREE_ITEM` and never on the loading line. With it,
`SelectionItemFacet` on a tree item counts **siblings** — position among the parent's children,
size the parent's child count — which is the "2 of 5" a reader speaks and what "its position among
its siblings" at the top of this section always meant; until this date the row's place in the whole
outline stood in for it (decision 4 of 2026-09-13, `tree-hierarchy-facet`). What each bridge does
with the two facets is phase 3's: Windows publishes UIA `Level` (30154, read off the guest
2026-09-13) plus `PositionInSet`/`SizeOfSet` and nests `TreeItem` elements, because NVDA 2024.4.2
derives level from `TreeItem` ancestors; Linux publishes the `level`/`posinset`/`setsize` object
attributes Orca 50.2 reads first; macOS answers `accessibilityDisclosureLevel` from the level and
`accessibilityIndex` from the row. A zero in any of the three publishes nothing on any platform.
**Amended 2026-09-15 (phase 3, Windows): built as planned.** `Level`, `PositionInSet` and
`SizeOfSet` are answered from the two facets, and navigation nests each `TreeItem` under the nearest
earlier row of a lower level. The level passes through unchanged: a native Win32 tree view answers
`Level` 1 for its root items, read on the guest 2026-09-15 (ADR 039 §2.1, amended the same day,
`UiaTreeRowsTest`).

**Amended 2026-09-15 (review of the Windows phase-3 work): the nesting holds only while a row's
ancestor rows are published.** Navigation nests a row under an earlier row only through unbroken
flat row indices, so the cursor row kept realized off screen (decision 22) is never taken for the
parent of the viewport's rows. A branch scrolled so that its own row is above the viewport leaves its
visible child rows with no published parent row: they hang under the tree, and NVDA 2024.4.2, which
counts `TreeItem` ancestors, says a lower level for them than the one this widget publishes. This
widget could close it by keeping the ancestor rows of its first mounted row realized, as it keeps the
cursor row; that is not decided here (ADR 039 §2.1, amended the same day).

**Amendment, 2026-09-15: the macOS half is built, on the platform's own bases.** A native
NSOutlineView was read through the AX API on the macOS 26.6.2 guest first
(`scripts/a11y/macos/outline-probe.swift`): its rows answer `AXIndex` and `AXDisclosureLevel` from
**zero**, `AXDisclosing` (settable only on a row that can open), `AXDisclosedByRow` and
`AXDisclosedRows`, and no `AXExpanded`; the outline answers `AXRows` and no `AXRowCount`; opening a
row posts `AXRowExpanded` on the row and `AXRowCountChanged` on the outline, and selecting one
`AXSelectedRowsChanged` on the outline. The bridge now vends the same: the outline's rows are its
realized items, a row's index is the hierarchy row less one and its disclosure level the level less
one, and the cursor row is the focused element. So the 2026-09-13 run's "the outline answers no
`AXRows`, and the rows answer no disclosure" is history; what VoiceOver speaks for it is phase 5's.

**Amendment, 2026-09-14: a tree item always has a name.** The L4 baseline on the Fedora guest read
every row of the reader scene as `name=''`: the demo's cells are composites — an icon, a label and
a count or a button in a `Row` — and the text sat in a `Label` child, so Orca's name generator
yielded nothing for the item and never spoke a row (TREE-ROW-NAME). A `TREE_ITEM` is now named in
this order: the model's `nameOf` where it gives one; else the cell's own name, which a `Label` cell
has; else the text of the cell's visible labels in reading order, joined by a space, so the demo's
folder reads as "Documents 2". The derived name is kept per mounted cell and compared before it is
rebuilt, so a quiet frame allocates nothing. All three bridges answer a node's name from the same
field (UIA `Name`, AT-SPI `Name`, AppKit `accessibilityLabel`/`accessibilityTitle`), so what
Windows and macOS got for those rows was the same empty string, by reading; what each reader now
speaks is phase 5's.

The live reader run on all three guests is part of phase 1 and not a follow-up, for the reason
ADR 041 and ADR 042 both record: a headless tree says what the toolkit published, not what a
reader speaks.

**Phase 1 lands in two steps, and the reason is the gate above** (decided 2026-09-12). The three
role tests each walk the whole enum — `AtspiConstantsTest` fails a role with no number, and there
is no exemption list for roles, only for states — so adding `TREE` and `TREE_ITEM` before the
AT-SPI reading turns `check` red. **1a** is therefore the widget publishing with the vocabulary
that already exists: `LIST` with a `LIST_ITEM` per realized row, carrying `ExpandFacet`, the
selection item and the verbs, which all three platforms say truthfully today. **1b** is the two
roles, depth and position-in-level, the macOS `ExpandFacet` consumer, and the live runs — one
pass, once a guest is up. What 1a publishes is not a placeholder to be deleted: it is what the
row says, minus the word "tree".

**Amendment, 2026-09-13: the roles landed, and the first live runs found that no reader follows the
cursor through the tree, on any platform.** `TREE` = 65 and `TREE_ITEM` = 91 and their names were
read off the Fedora 44 guest (at-spi2-core 2.60.6, aarch64) and mapped to UI Automation's Tree and
TreeItem and to AppKit's outline role and outline-row subrole, with a phrase and twenty-one
translations. Every client reads them: libatspi sees `tree` and `tree item`, UI Automation `Tree`
and `TreeItem`, the AX API an `AXOutline` described as "árvore" over rows described as "item de
árvore" — and VoiceOver spoke those words. Depth, position-in-level and the macOS disclosure
consumer were still owed at that date; the model half landed 2026-09-14 (the amendment above) and
the bridge half is phase 3's.

The runs used `--scene tree-reader`, which puts the focus in the tree and drives fifteen arrows
through the scene's own key path three seconds apart, so all three platforms hear the same
sequence; each client snapshots at the demo's printed step lines. What they found, none of it
visible headless and most of it not caused by the tree:

- **Linux.** Orca's locus of focus stayed on the frame. It subscribed to
  `state-changed:active`, `:selected`, `:expanded`, `active-descendant-changed` and
  `selection-changed`, and received none of them after start-up, while libatspi snapshots taken in
  the same seconds show those bits moving. The cause is not established; the desktop's flag, which
  is this bridge's gate, was on. Separately: `EXPANDABLE` is not mapped, so a closed branch reads
  exactly like a leaf (EXPANDABLE 9 and COLLAPSED 5 were read the same day); `ActiveDescendantChanged`
  is sent with an integer where AT-SPI's `any_data` is the new descendant's object reference; and
  the Selection interface is not served.
- **Windows.** A client reads ExpandCollapse and SelectionItem correctly at first, but an item's
  pattern set goes stale when rows above it are hidden or shown: after closing Reports, the two
  rows that moved up carried ExpandCollapse and the two branches below lost it — the offset of the
  two hidden rows exactly. The Tree itself serves no pattern. NVDA never had the demo in the
  foreground (it read the launching terminal), so what it speaks for a tree is still untested.
- **macOS.** VoiceOver announced the outline as a table and then one row, "item de árvore,
  archive-01.zip, texto, (1 de 1)", unchanged while the lead moved through six rows: it does not
  follow the selection, the outline answers no `AXRows`, and the rows answer no disclosure.
- **All three.** Two seconds after Remote's load was due, none of the three clients saw its
  children in the tree.

The versioned half of those recipes is the platform clients: `scripts/a11y/linux/tree-check.py`,
`scripts/a11y/windows/walk-the-tree.ps1` and `scripts/a11y/macos/axoutline.swift`, each run against
the demo started by the gallery's `--reader` driver. The runners that brought a particular guest up
around them are not in this repository (decision 17 of the 2026-09-13 pass, ADR 039 §12.2): they
carried a VM's address, login and home paths, and their copies live outside the tree. Until the
clients are re-run against the fixed bridges, the tree is readable by a client and not yet navigable
by a person using a screen reader.

**Amendment, 2026-09-15: the scene those runs drove is the accessibility gallery's now.**
`TreeScene.reader()` and the fifteen arrows inline in `Main` are gone (decision 24 of the
2026-09-13 pass). `--scene tree-reader` is kept as a spelling of `--reader tree-loading`, the one
reader driver, which builds the gallery entry "Tree with branches that load" alone in a window
titled "Limn accessibility gallery", focuses the tree and sends its declared steps three seconds
apart. Its first rows and cells are the old scene's, in the same order, so steps 1 to 15 are the
same arrows landing on the same rows and the recipes' step numbers keep their meaning; steps 16
to 21 add Trash, whose load finds nothing, and Empty folder. What changed for a recipe: the
window title (it was "Limn UI: Kitchen Sink"), the step line (`--- step N KEYS - label
focus=Widget`, still starting `--- step N `), the language (pt-BR unless `--locale` says
otherwise, the guests' reader language), and a default exit five seconds after the last step,
so an `--exit-after 62000` left in a recipe ends the run before step 21. The long tail of 24
archive rows and the fourteen-level chain are not in the gallery entry: they were there for the
captures, and the runs never reached them. `ReaderStepsTest` holds every step headlessly.

**Later the same day: two of those findings have one cause, and it was the widget's.** A realized
row's cell was bound to its row's index, and nothing re-bound it when opening, closing or loading
a row moved the rows below. The cell at an old index went on drawing and naming the node that used
to be there, so opening a row after the tree was on screen drew `a, b, c, b, c` for `a, a.1, a.2,
b, c`. Every per-row fact published by position then sat on the wrong row by exactly the rows
inserted or hidden. That is the Windows offset above, and in a model whose cells name their rows,
as the demo's do, it is also why Remote's children never appeared. The first tests built their
trees already open, which is why none of them saw it. Cells now follow their nodes, and
`TreeTest` and `TreeAccessibilityTest` read both shapes headless. The guests have not re-run, so
whether this was all of either finding is still unconfirmed.

## 5. Keyboard

Up and Down walk visible rows. **Right opens a closed row and steps into an open one; Left closes
an open row and steps to the parent of a closed one** — the convention every desktop tree uses,
and the reason a tree cannot simply inherit the table's Left/Right, which move a focus cell.
Home and End go to the first and last visible row, the page keys move a viewport, Enter activates,
and Space toggles selection where the mode allows it. In a right-to-left subtree the two arrows
swap, as `Table`'s already do.

**Amendment, 2026-09-14: Right into an open row steps only onto a child.** The step-in asked only
that a next row existed and was not the loading line, so Right on an open row with nothing under
it stepped onto a sibling or an ancestor's sibling (TREE-MISS-1). It now steps only onto a row one
level deeper; on a row still loading or open over the "Empty" line (§2) it stays where it is.

## 6. Selection

`NONE`, `SINGLE` and `MULTI`, as `Table` has them, but over **nodes** rather than over indices
into a list: a selection survives an expand that renumbers every row below it, and a collapse
leaves a selected descendant selected and unrealized rather than silently dropping it.

**Amendment, 2026-09-14: the cursor is not the selection, and `MULTI` has the table's gestures.**
The sentence above named the modes and not the gestures, and the widget shipped with one field
that was at once the keyboard row, the node `onSelect` reported and what Enter activated; in
`NONE` it never moved. Decisions 14, 31, 32 and 46 of this date settle the shape, and this is
exactly what is in:

- **Two fields.** `cursorNode()` is the row the keyboard is on: it moves with the arrows in every
  mode, `NONE` included, is `ACTIVE` to a reader while the tree holds the focus, and is what
  Enter, a double click and a reader's `PRESS` activate — in `NONE` too, since it is the one row the
  user pointed at. `leadNode()` is the selection's lead, the node selected last and still selected,
  never one outside the set; `onSelect` takes no node, and the application reads `selectedNodes()`.
  A row toggled off keeps the cursor and loses the lead, which falls back to the node selected most
  recently that is still selected. The cursor is announced as `ACTIVE` before the selection it moved
  with, as `Table`'s focus cell is (ADR 040 §7.2).
- **`MULTI`.** The command modifier — `Accelerator.commandModifier()`, Command on macOS and Control
  elsewhere, not a fixed Super bit — toggles one row. Shift+click and Shift with Up, Down, Page Up,
  Page Down, Home and End select the visible rows between the anchor and the target in traversal
  order, **replacing** the selection: a selected node hidden under a closed branch is not between
  two visible rows and leaves, exactly as a plain click drops it, while a collapse alone still keeps
  it (the paragraph above stands). The anchor is a node, set by every plain click, arrow or toggle,
  so an expand that renumbers the rows does not move it; hidden by a collapse it stands for nothing
  and the next range is its target alone. Ctrl+A or Cmd+A and `selectAll()` take every open row and
  no hidden one. `setSelectedNodes(...)` and `clearSelection()` are the caller's writes, announced
  once as `CODE`, and refuse a set the mode cannot hold.
- **Double click** activates the cursor row within the table's 400 ms window, like Enter; an
  application that wants it to open the branch does that in `onActivate`.
- **Row verbs** (decision 20): each `TREE_ITEM` publishes what it accepts by state — `SELECT` unless
  the mode is `NONE`, `ADD_TO_SELECTION` or `DESELECT` in `MULTI`, `EXPAND` or `COLLAPSE` where it can
  open, and `FOCUS` and `SCROLL_INTO_VIEW` — performed by the tree through the delegation hook (ADR
  039 §1.5, amended 2026-09-14). `FOCUS` moves the cursor without selecting, `EXPAND` and `COLLAPSE`
  act like the triangle and move nothing. The tree's own node publishes `PRESS` alone.

**Amendment, 2026-09-14: the cursor row wears a focus ring.** The tree painted the selection wash
and nothing else, so keyboard focus arriving was invisible, and so was the cursor wherever the
selection was not on it — in `NONE`, on a row toggled off in `MULTI`, on any row of a multiple
selection (TREE-MISS-5). Decision 52 (`tree-focus-mark`) asked for three marks rendered before
choosing: the list's ring that fades in with focus across the row, the table's thin ring across
the cursor row, and a thin ring around the cell only, with the indent and the triangle outside it.
All three were rendered in light, dark, right to left, scrolled sideways, `MULTI` with the cursor
outside the selection, and `NONE` (`renders/tree/focus-mark/`). The third, the one the research
recommended, is implemented so the branch is whole: `FOCUS_RING_THIN` in `theme.focusRing` at
`radiusSmall`, around the cursor row's cell inset by its own weight, only while the tree holds the
keyboard, clipped to the viewport where the cell runs past it. It stays inside the band a cursor
move already damaged, and the damage ratchet's shares for the tree row did not move (DOWN 6%,
click 12%, command-click 6%, measured twice). His pick on the renders may replace it.

**Amendment, 2026-09-15: a reader's add or remove leaves the cursor where it is.** The row-verb
bullet above sent `ADD_TO_SELECTION` and `DESELECT` through the command-click's toggle, and that
seam lands the cursor and the range anchor on the row it toggles, so a reader adding a row to the
selection was moved onto it and its next Shift range ran from there. Decision 20 and cross-bridge
semantics 5 say only `SELECT` and `FOCUS` move a cursor. The two verbs now change the membership,
the lead and the row's damage and nothing else — no cursor move, no anchor move, no reveal, no
`ACTIVE` announcement — while the command-click and Space keep moving both, because the pointer and
the keyboard are where the user is. Pinned by
`TreeAccessibilityTest.addingOrRemovingARowLeavesTheCursorAndTheAnchorWhereTheyWere`.

## 7. Damage

Expanding a row moves every row below it, so the frame damages the viewport from that row down and
not the window. That ceiling is what `DamageContractTest` will hold the widget to, with the
expand/collapse gesture named in its row ([ADR 043](043-a-frame-repaints-what-changed-and-every-exception-is-named.md)).

**Amendment, 2026-09-14: an expand repaints the tree's box, not a band, and the ratchet holds it
to that.** The paragraph above promised a band from the opened row to the foot of the viewport;
what the widget does is ask for a contained layout, and ADR 043's contract for one is that the
damage is the widget's bounds (`Widget.markNeedsContainedLayout`). `DamageContractTest`'s tree row
measured `RIGHT` on the first row at **101%** of the box — the whole of it plus the one-point
feather every damage carries — in two identical runs on this date, and its ceiling of 1.05 says so.
A band would need a contained layout that takes a rectangle, which is an ADR 043 mechanism change
and not a tree-local one; it was weighed and not taken (TREE-NEW-4, settled `tree-expand-damage`).
What a selection move damages is the moved rows' bands, clamped to the rows' viewport and never
into a reserved scroll-bar strip, exactly as the spinner's damage already was (TREE-MISS-7, the
same date; `ReservedBarStripTest` holds it).

**Later the same day: a collapse and a load landing are the box too, and were the window.** The
ratchet's first collapse gesture (`LEFT`) repainted the whole window, and so did a load landing,
measured once the harness could land one: both take cells out of the tree the moment their rows
vanish — the hidden rows' cells, the loading line's — and a child removed outside a layout pass
declares a global layout, which is a full frame by ADR 002's invariant (the same removal inside a
pass over the tree's subtree is absorbed). The cells whose rows vanished are now kept as children
until the tree's next pass releases them, so the collapse, the landing and a reorder are the
contained layout they always asked for: 101% each, in two identical runs, and the ratchet holds
them there with `LEFT` and "the load lands" beside `RIGHT`.

## 8. What phase 1 is not

- **No columns.** §9.
- **No in-place editing, ever.** ADR 041 §6 settled it for the table and the argument is the same
  one: a tree reads, and a record is edited whole.
- **No drag to reorder**, which waits on internal drag-and-drop, as the table's column reordering
  does.
- **No checkbox tri-state cascade.** A checkbox in a row is the application's widget; a tree that
  owned a tri-state parent rule would own a data model it does not have.
- **No filtering or search.** A filtered tree is a different traversal, and it should be one the
  application computes.
- **No horizontal virtualization**, which survives the 2026-09-12 amendment to §1: the outline
  scrolls sideways, but every mounted row is laid out at the content's full width. A row whose
  own width is realized in parts waits for a caller that needs it.

## 9. Phases

1. **This record.** The outline, the provider with lazy children, keyboard, selection, damage,
   the two roles on three platforms, depth and position-in-level, the gallery and guide entries,
   and the live reader runs.
2. **`TreeTable`**, and with it the package-private engine ADR 041 §9 reserved: the three copies
   of the anchor walk collapse, and that record decides the shape with three callers in hand.
3. Reordering by drag, once internal drag-and-drop exists.
