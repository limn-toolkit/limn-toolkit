# ADR 045: A widget is one of ten shapes, and the bridge translates the shape

- **Status: PROPOSED, 2026-09-21.** Phases 0 to 4 and 6 of §7 are in the tree, and phase 7's
  records; phase 5 (the bridges) and phase 8 (the reader round) are open, and the record is
  accepted when they close. Each phase's measure is written beside the floor in §0 and in §3
  as it closes. Decisions 89 to 97 of the 2026-09-13
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

*Changes the dump is allowed to show, by decision:* none; the dump is byte-identical after
phases 1 to 6.

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

**`RowsAccessibility`** (phase 3) is two halves, because the describe hook runs on every damaged
frame under the zero-allocation rule and a verb does not. `describeRow` is a static function over
the facts of one row — selected, its position and count, whether it opens and is open, whether it
is the cursor and the widget has the keyboard, and whether its rows can be pressed, be the cursor
and be revealed — and it publishes the membership, the open state, the cursor mark and the verbs
those facts add up to, by state: `SELECT` where there is a selection, `ADD_TO_SELECTION` or
`DESELECT` by membership where it is multiple, `EXPAND` or `COLLAPSE` by state, `PRESS`, `FOCUS`
and `SCROLL_INTO_VIEW` where the widget says its rows carry them. `performOnRow` takes a `Host`
the widget implements over the mechanisms it already had — `selectOnly`, `toggle`, `pick`,
`focusCell`, `revealNode`, `commit`, `choose`, `selectTab` — and holds the rules of what a verb
does: a client's `SELECT` calls `host.select(row, false)` and `ADD_TO_SELECTION` and `DESELECT`
call `host.toggleSelection(row, false)`, which is where decision 79 and decision 20's exemption
are now written, once; `FOCUS` is refused where the cursor is the selection (decision 11) and
where the row cannot be the cursor (a refused day); `EXPAND` and `COLLAPSE` are refused by
state; `PRESS` is refused where rows have no activation of their own. The two identities of
§7's first risk cost one parameter: `Offer.DELEGATED` for a widget child (a list's or a tree's
row, whose verb the container performs) and `Offer.OWNED` for a synthetic child (a table's row,
a calendar's day, a segment, an option) or a widget that performs its own verb (a tab header).

Seven widgets are on it: `Tree`, `Table`, `ListView` and `CalendarView` first, then
`ComboBox`'s option list, `TabbedPane`'s headers and `SegmentedControl`. The dump of §0 is
byte-identical after each, and `check` is green. **The grep the plan asked for:** the rule "a
client's `SELECT` does not move the cursor" is written in one place, `performOnRow`, where
before it was in `Tree#onAccessibilityChildAction`, `Table#onSyntheticAction` and
`CalendarView#onSyntheticAction`; the `moveCursor` parameter stays on each widget's own
mechanism (`selectOnly`, `toggle`, `pick`), where the pointer and the keyboard pass true. The
row verbs are published by `describeRow` and nowhere else for those seven, with two exceptions
found by the same grep and left for §8: `RadioButton` (a containerless member) and
`CalendarView`'s month and year chooser cells, which publish `SELECT` and `FOCUS` of their own.

*The measure:* hook lines per widget, floor against phase 3 — `Tree` 151 → 78, `Table` 298 →
249, `ListView` 123 → 102, `CalendarView` 245 → 230, `ComboBox` 235 → 223, `TabbedPane` 87 → 82,
`SegmentedControl` 73 → 72; 2,384 → 2,208 across the toolkit, against a helper of 278 lines of
which most is the record of the rules. The lines that left the widgets are the rules; what stays
in each is what is its own — a tree's hierarchy and busy state, a table's cells and header, a
calendar's numbering and refused days, a combo's inert options.

**Phase 6 wrote the other four** in the same mould, and put thirteen more widgets on them, the
dump byte-identical after each: `ValueAccessibility` (`Slider`, which until then used the
builder's one shape-like method, `Spinner`, `ScrollBar`, `SplitPane`'s divider, `ProgressBar`;
`INCREMENT` and `DECREMENT` by the widget's step, `SET_VALUE` from a finite number and refused
otherwise, a read-only or disabled value refusing everything — the NaN guard that four widgets
each carried is now one line); `ToggleAccessibility` (`Checkbox`, the series of `DonutChart` and
`CartesianChart`); `LeafActionAccessibility` (`Button`; `ColorPickerButton`, which is a leaf that
owns a popup: `HAS_POPUP` is its fact and the press is the leaf's); `PopupOwnerAccessibility`
(`ComboBox`, `DateField`, and in its menu form `ContextMenus.ContextRegion`; `EXPAND` and
`COLLAPSE` one at a time by state, refused in the other). `TextAccessibility` was already
written once and stays where it is. **`MENU` and `GRID` have no helper yet** (§8): the menu's
costs a Linux exception that lives in the bridge and a reader round, both phase 5's and phase
8's; the grid's container half is thirty lines of `Table` and twenty of `CalendarView` whose
shared part — a header at row −1 that sorts, a cell found by its facet — is small next to what
is each widget's own.

*The measure after phase 6:* hook lines 2,384 → 2,082 across the toolkit; five helpers, 589
lines, most of it the record of the rules.

## 4. Decision: a contract per shape, in the test fixtures

A contract is a list of named cases over a *subject*: the widget built fresh, bound headlessly,
read through the tree the scene published, driven through the verbs a platform would send, and
asked through its own API what the tree cannot say. It lives in `limn-toolkit`'s test fixtures
(`limn.testing.a11y`) and carries no test framework: a case (`ContractCase`) is a name and a body
that throws `AssertionError` with the tree in the message, and a widget's contract test is one
`@TestFactory` method turning the list into dynamic tests (`ContractTests`, in the toolkit's
tests). The mechanics — a stub window handing out a recording bridge, a frozen clock, a verb
performed from a thread that is not the UI thread — are `AccessibleComponentTestBase`'s, moved
into the fixtures as `AccessibleHarness` (and `StubWindow` with it, which twenty-five tests now
import), so that a contract can run over a widget from any module and, one day, from an
application's own widget of a known shape.

**`RowsContract`** (phase 2) holds a `RowsSubject` to ten cases: the members of one selection,
named in order; the four invariants of ADR 039 §12.1 (`AccessibleInvariants`, moved out of the
gallery test so that the gallery and the contracts hold a tree to the same rules), unfocused and
focused; decisions 10, 11, 20, 79, 80 and 81, one case each, named after the decision; a row
keeps its id while the selection and the cursor move; and the cursor is published only while
the widget has the keyboard. The four things a subject answers are the shape's variants (§3):
whether the cursor is the selection or separate from it, how rows beyond the box are reached,
whether there is a multiple selection to enter, and — added when the calendar's day cells showed
that a separate cursor does not imply an activation — whether a row can be activated at all.
`ListView` (cursor is the selection, single, scrolls itself) and `Tree` (cursor separate,
multiple, rows that open, scrolls itself) run it:
twenty dynamic tests, green at the first run once the subjects were named and boxed. The widgets
already held every rule, which is what phases 3 and 4 rely on.

Two things the contract settled that the plan had not named. **A member may classify as `GRID`**:
a calendar's day cell carries a selection membership *and* a cell facet, so the rules of
decisions 79 to 81 that `CalendarView` wrote with `moveCursor` are the selection's, not the row
role's, and the rows helper of §3 serves a grid's selectable cells as it serves a table's rows.
**Two gaps are refused, not passed over:** containerless members (a plain group of
`RadioButton`s, whose members carry a membership and whose group carries no selection facet, by
the builder's own `containerlessSelectionItem`) fail the contract with a message naming the gap
until a case is written for them; and "what a node accepts is exactly what it publishes" is not
a case, because `VerbPolicyRatchetTest` already holds the other half (nothing unpublished moves)
and this half cannot be held without knowing which published verb is a legitimate no-op.

**Phase 4 put the other rows widgets on the contract** — `Table`, `CalendarView`,
`TabbedPane`, `SegmentedControl` — sixty dynamic tests over six subjects, and the contract
learned three things from them that two subjects had not taught it: a table's cursor is a cell
under the row, so the cursor is the member the active node sits under; a tab strip's rows hold
the keyboard themselves, so the contract gives such a widget the keyboard through the walk's
`FOCUS` on its selected row; and selecting a tab announces the panels' visibility and the
roving focus besides the selection, so decision 79's case holds the selection to *once, from
the user* and the cursor to *not moved* rather than the announcement to *alone*. `ComboBox`'s
option list is not a subject: it lives in a window of its own, which the harness does not open.

**What phase 4 did not do, and why.** The plan asked that each widget's own test lose what the
contract now covers, with `TreeAccessibilityTest` and `TableAccessibilityTest` below half as a
goal to record rather than force. Six candidate cases were read across four widgets, and five
carry claims the contract does not hold: which events the container raises and how many, the
lead row, the range anchor a `Shift` extends from, what `NONE` leaves on a row, that a verb a
row did not publish does nothing. One was a strict subset and left
(`aRowCarriesTheSelectVerbTheListDelegatedAndTheListPerformsIt`, covered by the cases for
decisions 10, 79 and 80). The per-widget tests are, mostly, the widget's own facts written over
the shared rules, and the shared rules were a small part of each; what the contract buys is
not the old tests' lines but the *next* widget's, which gets ten cases for the price of a
subject. The record's measure: `TreeAccessibilityTest` 1,932 lines and `TableAccessibilityTest`
1,514 at the floor, and the same after phase 4; `ListViewAccessibilityTest` 919 →      886.

**Phase 6 wrote the other contracts:** `ValueContract` (six cases; `Slider`, `Spinner`,
`ScrollBar`, `SplitPane`, `ProgressBar`), `ToggleContract` (five; `Checkbox` in both variants),
`LeafActionContract` (five; `Button`), `PopupOwnerContract` (six; `ComboBox`, bound in a window
that cannot position so the list opens in the scene where the tree can see it) and
`TextContract` (five; `TextField`, `TextArea`) — 148 dynamic tests over 18 subjects in all. One
thing they settled that the rows contract had not met: **a refusal is read as an effect, never
as the host's answer**, because `Host#perform` answers from the snapshot whether the node exists
and posts the verb, so the hook's own refusal is invisible to the caller (ADR 039 §1.9); what a
refused verb owes is that nothing moved and nobody was told.

## 5. Decision: one adapter per shape in each bridge, and the exceptions stay declared

*Filled in as phase 5 closes.* ADR 039 §4.2's exceptions stay where they are, as exceptions of a
shape's adapter (the Linux menu-row exception becomes the `MENU` adapter's), never of a widget.
The role tables stay closed and per platform.

## 6. What this changes in ADR 039

§7 of ADR 039, the survey of every widget, is marked historic and stops being parsed (decision
93): `AccessibleCoverageTest` no longer reads the record, and the requirement it enforced becomes
"every widget that overrides a hook classifies in exactly one shape" plus the gallery entry
`AccessibleGalleryTest` already demands. The list of widgets absent from the survey, with the
reason each was not in it, went with the test: it was the survey's, and the front gate's own
list of what is left (`everyWidgetIsEitherDescribedOrOnTheListOfWhatIsLeft`) stays. ADR 039
gains one paragraph pointing here (decision 95).

*Applied 2026-09-21:* `AccessibleCoverageTest` no longer reads the record; §7 of ADR 039 carries
the paragraph that says so; the design note's "Adding a widget" is rewritten around shapes.

## 7. Phases, and what each one proved

| Phase | What | Closed |
|---|---|---|
| 0 | The floor above, and the dump | 2026-09-21 |
| 1 | `Shape.of`, `ShapeTest`, `ShapeCoverageTest`: every gallery node classifies; the tables of §1 | 2026-09-21 |
| 2 | `AccessibleHarness`, `AccessibleInvariants`, `RowsContract` with ten cases; `ListView` and `Tree` under it, twenty dynamic tests | 2026-09-21 |
| 3 | `RowsAccessibility`; `Tree`, `Table`, `ListView`, `CalendarView`, then `ComboBox`, `TabbedPane`, `SegmentedControl` on it; dump identical; the decision-79 rule in one place | 2026-09-21 |
| 4 | `Table`, `CalendarView`, `TabbedPane`, `SegmentedControl` under the contract, sixty dynamic tests; one duplicated case removed, the rest kept and the reason written | 2026-09-21 |
| 5 | One adapter per shape in each bridge; the 151 reads counted again | — |
| 6 | `VALUE`, `TOGGLE`, `LEAF_ACTION`, `POPUP_OWNER` helpers and contracts; `TEXT`'s contract; `MENU` and `GRID` owed (§8) | 2026-09-21 |
| 7 | The design note's pipeline rewritten around shapes; §6 applied; the record stays PROPOSED until phases 5 and 8 | partly, 2026-09-21 |
| 8 | One reader round per platform over the gallery's scripted entries | — |
| 9 | The measure beside the floor; the backlog; memory | — |

## 8. What this found and did not take

- The gallery publishes no `CHECK_MENU_ITEM`, `RADIO_MENU_ITEM`, `TOGGLE_BUTTON` or `ALERT`
  node, so no golden and no reader has heard one; `PopupMenu` can publish the first.
- `PopupMenu`'s panel carries a selection facet above its menu's (§1.3).
- The plan's table of widgets per shape was wrong about `RadioButton`, `DatePicker` and
  `TabbedPane` (§1.2); the record's table is the pinned one.
- **`MENU` has no helper and no contract yet.** `MenuBar` and `PopupMenu` publish their rows in
  their own hooks (124 and 42 lines); the price rule says the shape costs three adapters, a
  contract and a reader round, and the Linux exception (`AtspiRoles.isMenuRow`) is the
  adapter's, so the shape is taken with phase 5 and heard in phase 8.
- **`GRID` has no helper.** Its rows are the rows helper's; its container half (a `TableFacet`,
  headers at row −1 with a sort, cells found by their facet) is written in `Table` and
  `CalendarView`, and a `GridContract` is owed with the helper.
- `RadioButton` publishes its `SELECT` outside the rows helper because its members are
  containerless (§4); the rows contract refuses it for the same reason. One case for
  containerless members, and the helper's `describeRow` over it, close both.
- `CalendarView`'s month and year chooser cells are a second set of selectable cells in the
  same widget, publishing `SELECT` and `FOCUS` of their own (decision 85 keeps the chooser
  unmeasured on a guest and deliberately unfixed); they go on the helper when the chooser is
  measured.
- **Found by the rows contract, not fixed:** a `CalendarView` inside a `ScrollView` publishes
  its week rows and day cells beyond the pane as `SHOWING`, which the four invariants refuse
  (a row "is showing and lies wholly outside its showing ancestor"); its synthetic children are
  not narrowed by the scrolling ancestor's clip as a widget child would be. The subject runs
  at its natural size until this is decided, and decision 81's reveal case waits with it.
- Picking a leading or trailing day pages the calendar to that day's month, which is documented
  and tested (`pickingALeadingOrTrailingDayPagesTheGrid`) and which the contract's subject
  had to be built around: the members it mapped by name are another month's after such a pick.
- A `CalendarView` in `RANGE` mode publishes its container as multi-selectable and offers no
  `ADD_TO_SELECTION` or `DESELECT` on a day, because a range is a band and not a set; the
  helper names its selection `SINGLE` for that reason, and the container's facet is the
  widget's own. Whether a range should say "multiple" to a reader is a decision to ask.

## 9. Dependencies

ADR 039 (the model, the hooks, the walk, the three bridges); ADR 041 §3 (a widget cell under a
synthetic row); ADR 044 §3 and §9 (the shared rows engine is owed for layout; this record is the
accessibility side of the same consolidation and does not take the layout engine).
