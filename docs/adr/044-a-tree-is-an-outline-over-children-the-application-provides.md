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

**A row may promise children before it can name them.** The provider has an asynchronous form:
the application hands back a `Work<List<T>>` ([ADR 020](020-background-work-is-a-job-with-a-lifecycle.md)),
the row shows a busy state while it runs, and the children arrive on the UI thread. Cancelling is
the job's, so collapsing a row that is still loading cancels it rather than leaving a thread to
deliver into a row nobody is looking at. This is in phase 1 deliberately: a tree whose first real
use is a file system or a remote catalogue and cannot load on demand is a tree that has to be
rewritten by its second caller.

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

## 5. Keyboard

Up and Down walk visible rows. **Right opens a closed row and steps into an open one; Left closes
an open row and steps to the parent of a closed one** — the convention every desktop tree uses,
and the reason a tree cannot simply inherit the table's Left/Right, which move a focus cell.
Home and End go to the first and last visible row, the page keys move a viewport, Enter activates,
and Space toggles selection where the mode allows it. In a right-to-left subtree the two arrows
swap, as `Table`'s already do.

## 6. Selection

`NONE`, `SINGLE` and `MULTI`, as `Table` has them, but over **nodes** rather than over indices
into a list: a selection survives an expand that renumbers every row below it, and a collapse
leaves a selected descendant selected and unrealized rather than silently dropping it.

## 7. Damage

Expanding a row moves every row below it, so the frame damages the viewport from that row down and
not the window. That ceiling is what `DamageContractTest` will hold the widget to, with the
expand/collapse gesture named in its row ([ADR 043](043-a-frame-repaints-what-changed-and-every-exception-is-named.md)).

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

## 9. Phases

1. **This record.** The outline, the provider with lazy children, keyboard, selection, damage,
   the two roles on three platforms, depth and position-in-level, the gallery and guide entries,
   and the live reader runs.
2. **`TreeTable`**, and with it the package-private engine ADR 041 §9 reserved: the three copies
   of the anchor walk collapse, and that record decides the shape with three callers in hand.
3. Reordering by drag, once internal drag-and-drop exists.
