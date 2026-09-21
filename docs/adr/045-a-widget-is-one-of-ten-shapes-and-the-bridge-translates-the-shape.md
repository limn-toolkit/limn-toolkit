# ADR 045: A widget is one of ten shapes, and the bridge translates the shape

- **Status: PROPOSED, 2026-09-21.** Phases 0 and 1 of §7 are in the tree (the floor and the
  classification); the record is accepted when §7's phases through 7 are, and each phase's
  measure is written beside the floor in §0 as it closes. Decisions 89 to 97 of the 2026-09-13
  pass (batch 25) are what this record writes down.
- **Date:** 2026-09-21
- **Scope:** the cost of adding a widget to the accessibility pipeline ADR 039 built, and the
  one change that lowers it: naming, in the model, the small number of *shapes* a published node
  can have, and writing each shape once on the widget's side, once per bridge, and once as a
  contract, so that verification follows the shape and not the widget. Nothing published changes.
  The role enum stays closed. The builder and the walk do not grow.
- **Compatibility:** none owed (no consumers). The published tree of every gallery entry is held
  byte-identical to §0's dump through every phase, except where a numbered decision changed
  something and §0 lists it.

## 0. What was measured before anything was decided

At 48051d19, `main` after the twenty-third batch of the 2026-09-13 pass, taking the `Tree`
(born 2026-09-12) as the sample of what a widget costs:

| Where | Measure |
|---|---|
| `Tree.java` | 3,110 lines; 151 inside its five hooks; 280 from its accessibility banner to the end |
| `TreeAccessibilityTest` against `TreeTest` | 1,932 against 2,643 |
| Bridge tests named after it (`AtspiTreeTest`, `AxOutlineSceneTest`, `UiaTreeRowsTest`) | 2,417 lines |
| Commits since it was born | 417, of which 345 touch an accessibility path |
| Commits touching ADR 039 since then | 214 (267 in the record's life; it is 7,112 lines with 32 dated amendments) |
| `*AccessibilityTest` in the toolkit | 65 files, 41,303 lines |
| Hook lines across the toolkit's components | 2,384 in 38 files |
| Bridge sources under `limn-backend-lwjgl/.../a11y` | 51 files, 20,415 lines; 30 import `limn.accessibility` |
| Bridge tests under the same | 68 files, 23,103 lines |
| Batch 23, one rule written three times | 38 files, 709 insertions |
| Reads of a role or a shape facet in the bridges outside the role tables | 151: `AxGrid` 58, `AtspiTree` 34, `UiaPatternProviders` 23, `UiaFragment` 8, nine other files 28 |
| Goldens | 4 files, 331 lines; gallery 41 entries, 6 with a reader script |
| `./gradlew check`, every task run | 13 s, 5,240 tests |

The batch-23 lane is the case in one line: decision 79 (a `SELECT` from a reader selects and does
not move the cursor) had to be written in `Tree`, `Table` and `CalendarView`, each `selectOnly` or
`pick` gaining a `moveCursor` parameter, because each of the three re-derives what a row is and
what a reader may do to it. The bridges re-derive the same thing from the other side, once per
platform, from role plus facets, in the 151 places counted above.

What was there to build on: `limn.components.text.TextAccessibility`, the one shape already
written once (a `Host` that `TextField` and `TextArea` implement); the builder's one shape-like
method, `Accessibility.slider(...)`; the twelve facets and the accessors of `AccessibleNode`,
which are enough to derive a shape without a widget; the gallery, its goldens, and
`VerbPolicyRatchetTest`; and ADR 039 §4.2's declared exceptions, which this record keeps as the
mechanism and feeds with shapes rather than widgets.

**The dump.** Before anything moved, the tree of every gallery entry was written to text
(`AccessibleTreeDumpTest`, on request, with `-Dlimn.a11y.trees.dir`): 41 entries, both palettes,
2,822 lines, identical between light and dark. Every phase from 3 on ends with a fresh dump and a
`diff` against it, and the diff is empty unless a line below says otherwise.

*Changes the dump is allowed to show, by decision:* none yet.

## 1. Decision: ten shapes, derived from the node, in a fixed order

A shape is **derived** from a published node's role and facets by one pure function,
`limn.accessibility.Shape.of(AccessibleNode)`, and is never a field on the node (decision 89). A
field would be one more thing for three platform tables to translate and for a reader on a guest
to be shown, and it could disagree with the facets beside it; the derivation costs the bridges
nothing and is tested in the model. What a field would have caught — a widget that declares one
shape and publishes another — the shape's contract (§4) catches.

The ten, in the order that decides a node with the facts of two (decisions 90 and 94):

| Shape | Claimed by | Roles it covers today |
|---|---|---|
| `MENU` | the role family, before any facet | `MENU_BAR`, `MENU`, `MENU_ITEM`, `CHECK_MENU_ITEM`, `RADIO_MENU_ITEM` |
| `GRID` | `TableFacet`; the roles `TABLE`, `COLUMN_HEADER`, `CELL`; a `ROW` that is in no selection | `Table`, `CalendarView` |
| `ROWS` | `SelectionFacet` or `SelectionItemFacet`; the roles `LIST`, `TREE`, `TAB_LIST`, `RADIO_GROUP`, `LIST_ITEM`, `TREE_ITEM`, `TAB`, `RADIO_BUTTON` | `ListView`, `Tree`, the rows of `Table` and `CalendarView`, `ComboBox`'s list, `TabbedPane`'s strip, `SegmentedControl`, `RadioButton` |
| `POPUP_OWNER` | `HAS_POPUP`; `ExpandFacet`; the role `COMBO_BOX` | `ComboBox`, `DateField`'s and `DatePicker`'s group, `ColorPickerButton`, `ContextMenus.ContextRegion`, `TabbedPane`'s overflow button, `CalendarView`'s month title |
| `TOGGLE` | `ToggleFacet`; the roles `CHECK_BOX`, `SWITCH`, `TOGGLE_BUTTON`, `CHART_SERIES` | `Checkbox`, chart series |
| `VALUE` | `ValueFacet`; the roles `SLIDER`, `SPIN_BUTTON`, `PROGRESS_BAR`, `SCROLL_BAR`, `SPLITTER` | `Slider`, `Spinner`, `ProgressBar`, `ScrollBar`, `SplitPane`, `DateField`'s segments, `ColorPicker`'s rails |
| `TEXT` | `TextFacet`; the roles `TEXT_FIELD`, `TEXT_AREA`, `PASSWORD_FIELD`, `SEARCH_FIELD` | `TextField`, `TextArea`, `PasswordField`, `SearchField` |
| `LEAF_ACTION` | a `PRESS` verb; the role `BUTTON` | `Button`, the buttons inside `Spinner`, `MediaControls`, `TextField`, `DatePicker`, `TabbedPane` |
| `STATIC` | the roles `LABEL`, `HEADING`, `ALERT` | `Label` |
| `SURFACE` | everything else with a role | `Dialog`, `ScrollView`, `ToolBar`, `Viewport3D`, `ImageView`, `VideoView`, charts, `Separator`, `ColorPicker`, layouts |

`UNCLASSIFIED` is the eleventh constant and only an `UNKNOWN` role reaches it, which the walk
already refuses to publish; it exists so that the classification is total and the ratchet has
something to refuse.

### 1.1 Why a family of roles claims a shape as well as its facet

The plan this record came from said "precedence by facet, role second". The first run over the
gallery corrected it in two places, and the correction is the rule: **a shape must not change with
state.** A disabled button's verbs are withdrawn by the walk, so by verb alone it would stop being
a leaf while disabled; an indeterminate progress bar carries no `ValueFacet`, so by facet alone it
would stop being a value while busy. Each role family in the table above is a closed set from the
closed enum, so this is not the per-widget `if` the record is removing; it is the enum saying what
it always said.

### 1.2 The tie-breaks, each with the gallery node that made it necessary

- **A check menu item carrying a toggle is a menu row**, and a menu title carrying a popup and a
  selection membership is one too: the family is decided before any facet (decision 94).
- **A table's row is a `ROWS` member under a `GRID` container.** It carries a selection
  membership and no cell. A calendar's week row carries neither and is a `GRID` row. So `GRID` is
  `ROWS` whose members carry cells: the rows helper (§3) serves `Table` and the grid contract adds
  the cell rules on top.
- **A widget hung under a synthetic row (decision 3) keeps its own shape.** `Table` writes a
  `CellFacet` onto the switch in its "Visited" column; that facet is the switch's *position*,
  which a grid adapter reads on any node, and the switch is a `TOGGLE`. Only `CELL` and
  `COLUMN_HEADER` are grid members by their cell.
- **A combo box is the owner of its popup ahead of its value.** It publishes its index in its
  options as a `ValueFacet`, which is what the plan's table did not know; `HAS_POPUP` and the
  `ExpandFacet` are decided ahead of toggle, value and text.
- **An `ExpandFacet` without `HAS_POPUP` is the in-place form of the same fact**: a calendar's
  month title discloses its year chooser where it stands, and is a `POPUP_OWNER`. A tree item
  carries the same facet and is a `ROWS` member because rows are decided first.
- **A search field is a text ahead of its `PRESS`.** The verb is the one thing a leaf has, and
  every earlier shape may carry it (a tree, a list, a header sorting on press).
- **A radio button is a `ROWS` member, not a `TOGGLE`**, as the plan's table had it: it publishes
  a membership of its group's selection (`containerlessSelectionItem`), and a segment of
  `SegmentedControl` publishes the same. The plain group of `RadioButton`s publishes no
  `SelectionFacet` of its own, by the builder's containerless design, and is a `ROWS` container
  by role.
- **A `DatePicker` is a popup owner through its `GROUP`**, which carries `HAS_POPUP` and the
  expand facet; its button is a `LEAF_ACTION`. The plan's table named the button.

### 1.3 What the gallery publishes, role by role

`ShapeCoverageTest` in `limn-demo` walks every node of every entry and pins the table below;
`ShapeTest` in the toolkit pins the bare-role table and each tie-break over nodes built with the
builder alone. Three roles land in two or three shapes, each by what the node carries:

| Role | Shapes seen |
|---|---|
| `BUTTON` | `LEAF_ACTION`; `POPUP_OWNER` (the overflow button, the colour picker button, the calendar's month title) |
| `ROW` | `ROWS` (a table's); `GRID` (a calendar's week) |
| `GROUP` | `SURFACE`; `POPUP_OWNER` (a date field, a date picker, a context region); `ROWS` (one case, below) |

Every other role seen in the gallery lands in exactly one shape, the one its row in §1's table
names. `CHECK_MENU_ITEM`, `RADIO_MENU_ITEM`, `TOGGLE_BUTTON` and `ALERT` are published by no
gallery entry; `ShapeTest` classifies them bare, and the gallery's gap is §8's.

**The one case the classification found and did not fix:** `PopupMenu`'s own panel, a `GROUP`,
carries the `SelectionFacet` its menu rows are members of (`selection=single active=…`), so it
classifies as a `ROWS` container above a `MENU` that also carries a selection. That is a fact of
how the menu publishes today, pinned as such; whether the selection belongs on the panel or on the
menu is answered when the `MENU` helper is extracted (§3), and the dump will show the change if
one is decided.

## 2. Decision: the price of a widget, and when a reader has to hear it

**A new shape** costs three bridge adapters (§5), one contract (§4), one widget-side helper (§3),
and a reader round on every platform (decision 92). **A new widget of a known shape** costs its
hooks, the shape's `Host`, the shape's contract run over it, a gallery entry, and a golden; it
ships without a reader having heard it. **A widget that deviates from its declared shape** — a
verb the shape does not publish, a facet the shape does not carry, an exception declared for it
in a bridge — pays the reader round again.

The cost accepted (decision 92): phase 5 of the 2026-09-13 pass found 21 defects only a real
reader could find, and every one was a defect of shape — a row's setter VoiceOver wrote back, a
grid's pattern NVDA never asked for — and not of widget. A widget of a known shape adds no way for
a reader to be wrong that its shape's round did not already exercise.

## 3. Decision: the widget-side helpers live in one package

`limn.components.a11y` (decision 91), not beside each widget as `TextAccessibility` is and never
in `limn.accessibility`, which is the core and imports no component (2026-09-06). One helper per
shape that has behaviour to share — `ROWS` first, then `VALUE`, `TOGGLE`, `POPUP_OWNER`,
`LEAF_ACTION`, `MENU`; `TEXT` already has one; `STATIC`, `GRID`'s container part and `SURFACE`
are the walk's — each with a `Host` interface the widget implements, in `TextAccessibility`'s
mould. The rules of decisions 10, 11, 20, 79, 80 and 81 are written in the rows helper and nowhere
else, which a `grep` for `moveCursor` and the row verbs proves when phase 3 closes.

*Filled in as phases 3 and 6 close.*

## 4. Decision: a contract per shape, in the test fixtures

*Filled in as phases 2 and 4 close.*

## 5. Decision: one adapter per shape in each bridge, and the exceptions stay declared

*Filled in as phase 5 closes.* ADR 039 §4.2's exceptions stay where they are, as exceptions of a
shape's adapter (the Linux menu-row exception becomes the `MENU` adapter's), never of a widget.
The role tables stay closed and per platform.

## 6. What this changes in ADR 039

§7 of ADR 039, the survey of every widget, is marked historic and stops being parsed (decision
93): `AccessibleCoverageTest` no longer reads the record, and the requirement it enforced becomes
"every widget that overrides a hook classifies in exactly one shape" plus the gallery entry
`AccessibleGalleryTest` already demands. The list of widgets absent from the survey with their
reasons, which lived in the test, stays. ADR 039 gains one paragraph pointing here (decision 95).

*Applied in phase 7.*

## 7. Phases, and what each one proved

| Phase | What | Closed |
|---|---|---|
| 0 | The floor above, and the dump | 2026-09-21 |
| 1 | `Shape.of`, `ShapeTest`, `ShapeCoverageTest`: every gallery node classifies; the tables of §1 | 2026-09-21 |
| 2 | A contract per shape in `testFixtures` | — |
| 3 | The `ROWS` helper; `Tree`, `Table`, `ListView`, `CalendarView`, then `ComboBox`, `TabbedPane`, `SegmentedControl`; dump identical | — |
| 4 | Each `ROWS` widget on the contract; its test shrunk to what is its own | — |
| 5 | One adapter per shape in each bridge; the 151 reads counted again | — |
| 6 | `VALUE`, `TOGGLE`, `POPUP_OWNER`, `LEAF_ACTION`, `MENU` helpers and contracts; `TEXT`'s contract | — |
| 7 | This record accepted; the design note's pipeline rewritten around shapes; §6 applied | — |
| 8 | One reader round per platform over the gallery's scripted entries | — |
| 9 | The measure beside the floor; the backlog; memory | — |

## 8. What this found and did not take

- The gallery publishes no `CHECK_MENU_ITEM`, `RADIO_MENU_ITEM`, `TOGGLE_BUTTON` or `ALERT`
  node, so no golden and no reader has heard one; `PopupMenu` can publish the first.
- `PopupMenu`'s panel carries a selection facet above its menu's (§1.3).
- The plan's table of widgets per shape was wrong about `RadioButton`, `DatePicker` and
  `TabbedPane` (§1.2); the record's table is the pinned one.

## 9. Dependencies

ADR 039 (the model, the hooks, the walk, the three bridges); ADR 041 §3 (a widget cell under a
synthetic row); ADR 044 §3 and §9 (the shared rows engine is owed for layout; this record is the
accessibility side of the same consolidation and does not take the layout engine).
