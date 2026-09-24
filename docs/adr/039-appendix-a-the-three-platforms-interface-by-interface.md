# ADR 039, appendix A: the three platforms, interface by interface

*§2.1 to §2.4 of [ADR 039](039-an-accessible-tree-is-a-snapshot-and-the-platform-reads-it-on-its-own-thread.md), moved here unchanged on 2026-09-23 so that the record
itself can be read in one sitting. The section numbers are the ones the rest of the repository
cites.*

*A path under `readings/` names a raw transcript from the verification lab. Those files are kept
outside this repository; each finding cited to one is stated here in full.*

### 2.1 Windows: UI Automation

| Interface / member | Answered from | Note |
| --- | --- | --- |
| `IRawElementProviderSimple::get_ProviderOptions` | `ProviderOptions_ServerSideProvider` | proven |
| `…::GetPropertyValue(id)` | role, name, description, states, bounds, locale, id, `IsDialog` | `VT_EMPTY` for anything unanswered is accepted; the spike saw UIA ask for ids we do not answer and not complain |
| `…::GetPropertyValue(IsControlElement)`, `…(IsContentElement)` | `true` for every published node | the spike had to answer both, and did, for both its elements. They are how UIA builds its control and content views, and a provider that leaves them `VT_EMPTY` is asking every client to guess which of its elements are worth showing |
| `…::GetPatternProvider(id)` | **the facet set** | Invoke ← `ActionFacet.PRESS`; Toggle ← `ToggleFacet`; RangeValue ← `ValueFacet` (its `IsReadOnly` from `ValueFacet.readOnly`); **Value ← `ValueFacet` or `TextFacet`**; Selection ← `SelectionFacet`; SelectionItem ← `SelectionItemFacet`; ExpandCollapse ← `ExpandFacet`; Scroll ← `ScrollFacet`; ScrollItem ← a scrollable ancestor; Window and Transform ← `WindowFacet`; Text ← `TextFacet` (phase 2, §11) |
| `…::get_HostRawElementProvider` | `UiaHostProviderFromHwnd` on the root, `NULL` on every child | proven |
| `IRawElementProviderFragment::Navigate` | the stored links (§1.4) | Parent, FirstChild, LastChild proven. A popup fragment root answers `NULL` for Parent (§1.11) |
| `…::GetRuntimeId` | `SAFEARRAY(VT_I4){UiaAppendRuntimeId, hi, lo}` | proven: UIA replaced the leading marker with the HWND's own runtime id, which is the contract |
| `…::get_BoundingRectangle` | `UiaRect` of four doubles, screen pixels, top-left | proven |
| `…::SetFocus` | posted `FOCUS` | proven; the root reports `IsKeyboardFocusable` false, so a client's `SetFocus` on the window throws client-side, which is expected |
| `…::get_FragmentRoot` | the window's root node | proven |
| `…::GetEmbeddedFragmentRoots` | `NULL` | in-scene popups are inside this fragment; native popups are their own HWND fragment root |
| `IRawElementProviderFragmentRoot::ElementProviderFromPoint` | deepest node containing the screen point, from the snapshot's bounds | proven. **Not** `Widget#hitTest`: it returns `null` for a disabled subtree at every level, and UI Automation expects to find a disabled button under the pointer |
| `…::GetFocus` | the focused node | proven |
| `IInvokeProvider::Invoke` | posted `PRESS`; raises `Invoke_Invoked` | proven, including raising the event from inside `Invoke` on an RPC thread |
| `IGridProvider` (`get_RowCount`, `get_ColumnCount`, `GetItem`) and `ITableProvider` (`GetRowHeaders`, `GetColumnHeaders`, `get_RowOrColumnMajor`) | `TableFacet`; ADR 041 §7 | `GetItem` answers a realized cell and `null` for a row the walk did not publish, the degradation §4.1 already accepts; column headers are the header group's children; row headers are none |
| `IGridItemProvider` (`get_Row`, `get_Column`, spans of one, `get_ContainingGrid`) and `ITableItemProvider` (`GetRowHeaderItems`, `GetColumnHeaderItems`) | `CellFacet`; ADR 041 §7 | the containing grid is the nearest ancestor with a `TableFacet`; the column header item is that grid's header group's child at the cell's column |
| `IToggleProvider`, `IRangeValueProvider`, `IValueProvider`, `ISelectionProvider`, `ISelectionItemProvider`, `IExpandCollapseProvider`, `IScrollProvider`, `IWindowProvider` | the matching facets | to be built; each is the same vtable-of-closures shape as the four proven ones |
| `UiaRaiseAutomationEvent`, `…PropertyChangedEvent`, `…StructureChangedEvent`, `…NotificationEvent` | the event flush | the first is proven returning `S_OK` from an RPC thread |
| `UiaClientsAreListening` | the listening gate | proven resolvable and callable |
| `UiaDisconnectProvider`; `UiaReturnRawElementProvider(hwnd, 0, 0, NULL)` on `WM_DESTROY` | teardown | both proven returning `S_OK`, process exiting cleanly |

**`IValueProvider` comes from `TextFacet` as well as from `ValueFacet`, and without that a Windows
text field vends no pattern at all.** `TextPattern` is deferred to phase 2 (§11) and a text widget
has no `ValueFacet` — it has never had a numeric value — so mapping `Value` from `ValueFacet` alone
leaves `TextField`, `TextArea`, `PasswordField` and `SearchField` with an empty pattern list on the
one platform where the pattern list is the whole of a control's behaviour. NVDA would find a named
element it cannot read the contents of, cannot set, and cannot report as read-only. So a node with a
`TextFacet` vends `IValueProvider`: `get_Value` is the facet's text — the mask, never the secret, on
a `PasswordField` — `get_IsReadOnly` is the `READ_ONLY` state, and `SetValue` is the whole-value set
§11 already scopes. The same hole existed on macOS, where `accessibilityValue` was mapped from
`ValueFacet` and `ToggleFacet` only; §2.2 adds `TextFacet` there for the same reason and the same
text.

An in-scene dialog is **not** given `WindowFacet`. Vending `IWindowProvider` from a node that is not
an HWND advertises `Close()`, `SetVisualState()`, `WaitForInputIdle()` and `CanMaximize` on an overlay
that has none of them. UI Automation's answer for a dialog rendered inside a window is
`UIA_IsDialogPropertyId`, and that is what the bridge publishes.

Two mechanical facts the spike paid for and the bridge inherits. `JNI.invoke*` names encode only the
pointer-sized and narrow arguments plus the return letter, and `SafeArrayCreateVector`'s 16-bit
`VARTYPE` has no matching overload, so ABI reasoning leaks into that one call site. And a `VARIANT`
must be zeroed whole — 24 bytes — before every write, because leaving `VT_EMPTY` over a stale payload
is a latent crash in a caller that trusts the union.

**Amended 2026-09-15 (phase 3, Windows; decision 1, semantics 4; W3, LAB-NEW-4): `GetFocus` and
`HasKeyboardFocus` answer where the user is.** The row above says `GetFocus` answers "the focused
node", and so did the code: the focused table, tree, list or calendar, never the row, cell, day or
segment its cursor is on, and `HasKeyboardFocus` was the node's own `FOCUSED` bit. Both now answer
the tree's effective focus (`AccessibleTree#effectiveFocus`, the active descendant or the focused
node): a focused table's cursor cell has the keyboard and the table does not, because NVDA 2024.4.2
takes a focus change only from a sender that answers `HasKeyboardFocus` true when it reads it, live,
after the event (readings/nvda-2024.4.2-uia.md §1). Where the cursor resolved into a native popup's
tree (decision 5), `GetFocus` hands over that window's fragment pointer from that window's own
provider, and that element is the one answering `HasKeyboardFocus` true. `HasKeyboardFocus` is
therefore answered by the provider from the tree, not by the per-node property table.
**Amended again 2026-09-15 (review of that change):** the popup window's own root answered
`GetFocus` with a null — nothing of its tree is focused — while its day answered `HasKeyboardFocus`
true, so one provider contradicted itself. A root whose tree has no effective focus of its own now
answers the node of its tree that another open window's effective focus names
(`UiaBridgeTest.aCursorInAnotherWindowsTreeIsRaisedAndAnsweredThroughThatWindowsProvider`). Whether
UI Automation and NVDA accept either answer — a focus element in another window's fragment tree, or
the focus on a window that is not the active one — is decision 5's live assumption, measured first in
phase 5. *(Heard 2026-09-22 on Windows 11 with NVDA 2024.4.2: with the native popup open, NVDA said
'popup', 'janela' and then the focused day, '9 de setembro de 2026, hoje', and followed the cursor
through the grid; closed. On 2026-09-16 only the provider half could be measured — the keyboard
stayed in the field and the focused element was the popup's day — because closing the popup with
NVDA attached hung the demo, which the teardown amendment of 2026-09-22 below removed.)*

**Amended 2026-09-15 (phase 3, Windows; decisions 9, 10; semantics 1 and 5; W1's Selection half,
WINDOWS-NEW-9, WINDOWS-NEW-11): `ISelectionProvider` is served, and the `SelectionItem` verbs are
candidate lists.** The row above lists `ISelectionProvider` as "to be built"; the pattern was
claimed from `SelectionFacet` and a client's `GetPatternProvider` got a null. `GetSelection` now
answers a `SAFEARRAY` of simple pointers to the realized selected members whose selection container,
resolved once at publish (`AccessibleNode#selectionContainer`), is this node — a selected row the
widget has not realized is not listed (§4.1) — and `get_CanSelectMultiple`/`get_IsSelectionRequired`
answer the facet. `get_SelectionContainer` answers that same resolved container, where it had
climbed to any ancestor with a selection. `Select` posts `SELECT`, `AddToSelection` the first of
`ADD_TO_SELECTION`, `SELECT` the node publishes, `RemoveFromSelection` `DESELECT`; a node publishing
none of its list is refused with `0x80131509` and nothing is posted (it had posted `SELECT` for
both of the first two, whatever the node published). Every `BOOL*` getter writes four bytes of `1`
or `0`, read on the guest (readings/windows-dump-uia-marshalling.txt); it had written a
`VARIANT_BOOL`'s two.

**Amended 2026-09-15 (phase 3, Windows; decision 39, semantics 5; W1's Scroll half,
WINDOWS-NEW-7): `IScrollProvider` is served, `ScrollItem` is the node's verb, and no node claims
`Window` or `Transform`.** The pattern row above says "Scroll ← `ScrollFacet`; ScrollItem ← a
scrollable ancestor; Window and Transform ← `WindowFacet`", and the interface row lists
`IScrollProvider` and `IWindowProvider` as "to be built"; the Scroll, Window and Transform patterns
were claimed and a client's `GetPatternProvider` got a null for each. As built:
- **Scroll's getters** answer the scroll facet as the platform's own `ScrollViewerAutomationPeer`
  does, read as IL on the guest (readings/windows-dump-uia-provider-conventions.txt §1): a percent is
  the facet's times 100 on an axis that scrolls and `UIA_ScrollPatternNoScroll` (−1, read 2026-09-13)
  on one that does not; a view size is a percent on either axis (100 for nothing to scroll); the two
  `BOOL*` flags are four bytes.
- **`Scroll(h, v)`** maps each `ScrollAmount` (read 2026-09-13) to that axis's `SCROLL_BAR` child's
  published stepping verb, posted on the bar: `SmallIncrement`/`SmallDecrement` →
  `INCREMENT`/`DECREMENT`; `LargeIncrement`/`LargeDecrement` → the page verbs decision 39 names "if
  published", which the model does not have, so a large step is refused; `NoAmount` leaves the axis.
  **`SetScrollPercent(h, v)`** posts `SET_VALUE` on the axis's bar, its own range scaled by the percent,
  where the bar accepts one (`AccessibleNode#accepts`); `NoScroll` leaves the axis. The refusals come in
  the peer's order: `UIA_E_ELEMENTNOTENABLED` (0x80040200, read 2026-09-13) for a node that is not
  `ENABLED`, then `0x80131509` for an axis asked to move that cannot scroll, then (percent only)
  `0x80131502`, the managed `ArgumentOutOfRangeException` (read 2026-09-15), for a percent outside
  0..100 or not a number, then `0x80131509` for an axis with no bar or a bar that does not publish the
  verb or take the value. Both axes pass every check before either is posted. The scroll bar's own
  step is one viewport (`ScrollBar`), so a client's small step moves a page; that is decision 39 as
  written, recorded for phase 5's client runs. *(Measured 2026-09-16 on Windows 11 with a UI
  Automation client over the gallery's table, and worse than a page: the first `SmallIncrement` moved
  the view from 0 % to 95.65 % and the second to 100 %; from there every `SmallDecrement` and every
  vertical `SetScrollPercent` was accepted and moved nothing, and `LargeIncrement` was refused. A
  defect, not a confirmation of decision 39; no later record says it was fixed. Still open.)*
- **`ScrollItem`** is vended only on a node publishing `SCROLL_INTO_VIEW`, and `ScrollIntoView` posts
  it through the same candidate gate (semantics 5); it had been vended on any node with a scrollable
  ancestor and posted whatever the node published.
- **`Window` and `Transform`** are claimed by no node: the root answers `get_HostRawElementProvider`
  with the provider UI Automation made for the HWND, which serves both for the real window (the
  probe's `[Window,Transform]` came from it), and this bridge serves neither interface. An in-scene
  dialog still says `IsDialog`. `UiaPatternsTest.everyPatternANodeCanClaimIsOneThisBridgeServes` now
  fails for any claim with no interface behind it.

**Amended 2026-09-15 (review of the Windows phase-3 work): which items lose `ScrollItem`.** Vending
`ScrollItem` only with `SCROLL_INTO_VIEW` takes it from every item whose widget does not publish that
verb, and at this date that is most of them. `ListView` rows and `Tree` rows publish it (decision 20),
and a focusable widget gets it free from the walk, but a `Table`'s synthetic `ROW` publishes `SELECT`,
`ADD_TO_SELECTION`/`DESELECT` and `FOCUS` and its synthetic cells `FOCUS` only, and a `CalendarView`'s
day cells `SELECT` and `FOCUS`: none of them vends `ScrollItem` on Windows, so a client asking a table
row or cell, or a day, to scroll into view finds no pattern. Decision 20 says tree and list rows
publish `SCROLL_INTO_VIEW` "like Table", which reads as though a table row did; it does not. Owed to
the `Table` and dates widgets under decision 20 (ADR 041 §7, ADR 042), not to this bridge, which vends
the pattern the moment the verb is published.

**Paid 2026-09-17 (decision 81).** A `Table` row and a `CalendarView` day cell publish
`SCROLL_INTO_VIEW` now, so both vend `ScrollItem` here with no change to this bridge, exactly as the
paragraph above said they would. The table row performs it as `ensureVisible`; the day cell reveals
its own rectangle through the scrolling ancestors, because a calendar's grid does not scroll by
itself. Synthetic *cells* of a table still publish `FOCUS` alone and still vend no `ScrollItem`:
revealing a cell is revealing its row, and the row is addressable.

**Amended 2026-09-15 (phase 3, Windows; decisions 2, 7, 20, semantics 5 as amended the same day;
W6, TREE-MISS-8, WINDOWS-NEW-10, CRIT-7): every verb and setter goes through
`AccessibleNode#accepts`.** The rows above say `SetFocus` "posted `FOCUS`" and `Invoke` "posted
`PRESS`", and the pattern slots posted `TOGGLE`, `EXPAND`, `COLLAPSE` and `SET_TEXT` the same way,
whatever the node published; fix round 2e's `refusedSetter` refused a setter on a node not `ENABLED`
and nothing else. As built: `Invoke` [`PRESS`], `Toggle` [`TOGGLE`], `Expand` [`EXPAND`], `Collapse`
[`COLLAPSE`], `SetFocus` [`FOCUS`], `ScrollIntoView` [`SCROLL_INTO_VIEW`] and the `SelectionItem` lists
post the first verb the node publishes on the snapshot of the call. `Value.SetValue` posts `SET_TEXT`
on a node with a text facet and `SET_VALUE` carrying the text on a value facet (a spinner's "07:30",
a combo's item), and `RangeValue.SetValue` posts `SET_VALUE`, each only where `accepts` says the
node takes it (a writable facet on an `ENABLED` node). A verb or setter the node does not accept is
refused synchronously and nothing is posted: with `UIA_E_ELEMENTNOTENABLED` (0x80040200) when the
node is not `ENABLED` — disabled, under a disabled ancestor, outside the layer that owns input — and
`0x80131509` when it is enabled and does not offer it. The not-enabled code for the **verbs** as well
as the setters (the addendum names the setters) is the platform's own order, read as IL on the guest
2026-09-15 (readings/windows-dump-uia-provider-conventions.txt §1b): `ButtonAutomationPeer.Invoke`,
`ToggleButtonAutomationPeer.Toggle`, `ExpanderAutomationPeer`'s and `TreeViewItemAutomationPeer`'s
`Expand`/`Collapse`, `SelectorItemAutomationPeer`'s three verbs, `TextBoxAutomationPeer.SetValue` and
`RangeBaseAutomationPeer.SetValue` all throw `ElementNotEnabledException` before anything else, and
`InvalidOperationException` only after. Not followed from that reading: `TextBoxAutomationPeer`
answers a read-only text box's `SetValue` with `ElementNotEnabledException` too; this bridge answers a
read-only but enabled node `0x80131509`, as semantics 5 words it. `Value.get_IsReadOnly` is the
`READ_ONLY` state, which the model derives from a value facet's `readOnly` (`Accessibility#value`), so
it already answered the facet's writability. The Expand/Collapse, Toggle and Value patterns are still
vended from their facets, because their state is what a reader reads; a verb delegated to a container
(a tree row's `EXPAND`) is posted on the row's id and the scene routes it (decision 7).

**Amended 2026-09-15 (review of the Windows phase-3 work): the not-enabled order is read for the
entry points named, and `SetFocus` and `ScrollIntoView` were read afterwards and differ between
providers.** The amendment above calls the not-enabled-first order "the platform's own order, read as
IL" for every verb; its reading covers the peers it lists and no `SetFocus` body, and its `ScrollItem`
listing named a type that does not implement the interface. Both were then read on the same guest
(`scripts/a11y/windows/dump-uia-focus-and-scroll-item.ps1`,
readings/windows-dump-uia-focus-and-scroll-item.txt, UIAutomationCore.dll 7.2.26100.9457, 4.8.9347
assemblies), and the platform's providers do not agree. The client-side proxies of the Win32 controls
keep the order: `ProxySimple`'s `IRawElementProviderFragment.SetFocus` throws
`ElementNotEnabledException` (0x80040200) when the window is not enabled and
`InvalidOperationException` (0x80131509) when the element is not keyboard-focusable; `ListViewItem`'s
and `WindowsTabItem`'s `ScrollIntoView` throw `ElementNotEnabledException` before
`InvalidOperationException` for a container that cannot scroll. WPF checks no enabled bit on either:
`ElementProxy.SetFocus` reaches `UIElementAutomationPeer.SetFocusCore`, which throws
`InvalidOperationException` when `UIElement.Focus()` refuses (as it does for a disabled element), and
`ListBoxItemAutomationPeer`, `DataGridItemAutomationPeer`, `TreeViewItemAutomationPeer` and the
list-box and tree-view item proxies scroll whatever the item's state. This bridge keeps
`UIA_E_ELEMENTNOTENABLED` first for both, the Win32 proxies' order and the one every other entry
point here follows, pinned by `UiaFragmentProviderTest.setFocusReachesTheToolkitOnlyWhereTheNodePublishesFocusAndSaysSoWhenTheNodeHasGone`
and `UiaPatternProvidersTest.scrollIntoViewIsPostedOnlyWhereTheNodePublishesIt`; which of the two
answers a client prefers is not read, and the choice is put to the owner with the verbs'.

**Amended 2026-09-15 (phase 3, Windows; decision 4, semantics 6; W4, CRIT-6): position, set size and
level are answered, and tree rows nest in navigation.** The `GetPropertyValue` row names neither
`PositionInSet` nor `Level`, and the `Navigate` row says "the stored links"; every selection item's
position answered `VT_EMPTY`, and a tree's rows, published flat under the tree, were all its
children. As built: `PositionInSet` (30152) and `SizeOfSet` (30153) are the `SelectionItemFacet`'s
numbers and `Level` (30154, read from UIAutomationCore.dll's type library 2026-09-13) the
`HierarchyFacet`'s, each an integer and `VT_EMPTY` for a zero. The level passes through unchanged:
the platform's base was read off native trees on the guest 2026-09-15
(`scripts/a11y/windows/read-native-tree-levels.ps1`, readings/windows-read-native-tree-levels.txt):
a Win32 tree view answers `Level` 1 for its root items, 2 and 3 below, and `PositionInSet`/`SizeOfSet`
one-based among siblings; a WPF 4.8 tree answers 0 for all three (UIA's default for a provider that
answers nothing). **`Navigate` nests `TREE_ITEM` rows**: a row with a positive level has as its parent
the nearest earlier sibling row of a lower level, or the node it hangs under when there is none (a
row whose parent row is not realized); a row's children are its own children, then the later sibling
rows whose parent that makes it; every other node keeps the stored links. NVDA 2024.4.2 counts a tree
item's `TreeItem` ancestors for its level and overwrites UIA's `Level` with that count
(readings/nvda-2024.4.2-uia.md §2), and both native trees nest their items in the raw view, so this is
what makes NVDA say the right level. Structure changes keep naming the stored parent (a removed row's
`ChildRemoved` goes to the tree), which a client re-reading the tree reconciles; `UiaFragmentTest`
holds navigation to one consistent tree reaching every node once, and `UiaTreeRowsTest` a real
`Tree`'s rows' ancestor counts to their published levels. **Recorded as seen:** through the COM client
the Win32 tree's items answered `ControlType` Tree and an empty `Name` in that reading, while the
managed client read them as `TreeItem`s with their names; it bears on no number used here.

**Amended 2026-09-15 (review of the Windows phase-3 work; decision 22): a row nests only through
unbroken row indices, and a row whose parent row is not published is heard a level too high up.**
The amendment above takes "the nearest earlier sibling row of a lower level" wherever it stands. A
`Tree` publishes only its mounted rows and the cursor row it keeps realized off screen while focused
(decision 22), so that row need not be the row's parent: with a root row kept as the cursor and the
viewport inside another root's children, those children nested under the cursor row. Now the search
walks back only while each earlier row carries the flat row index right above the one before it
(`HierarchyFacet#row`); at a gap, or on a row whose index is unknown (0), it stops and the row hangs
under its stored parent (`UiaFragmentTest.aRowNestsOnlyUnderARowItsUnbrokenRowIndicesReach`,
`UiaTreeRowsTest.theKeptCursorRowIsNeverTheParentOfTheRowsInTheViewport`). **The consequence a
reader hears:** once a `Tree` is scrolled so that a branch's own row is above the viewport, that
branch's visible child rows have no published parent row and hang under the tree, so NVDA 2024.4.2,
which counts `TreeItem` ancestors and overwrites `Level` with the count, says a lower level than the
published one (a level-2 row read as level 1; measured headlessly for a tree scrolled 1000 px into an
80-row branch, `UiaTreeRowsTest.aTreeScrolledIntoABranchNestsNoRowUnderARowThatIsNotItsParent`). The
`Level` property still answers the true number, for a client that reads it. A wrong level was
preferred to a wrong parent: the first is heard only for rows whose branch is scrolled away, the
second put rows under another branch at a plausible level. What would close it is the widget keeping
the ancestor rows of its first mounted row realized, as it keeps the cursor row; that is the `Tree`'s
to decide (ADR 044 §4), and phase 5 hears the degradation on `--scene tree-reader` scrolled into a
branch. *(Not reached by phase 5, 2026-09-16: NVDA 2024.4.2 spoke 'nível 1' to 'nível 3' by nesting
over the gallery's tree, including after a collapse hid rows, but no reader script scrolls a tree,
so a branch scrolled above the viewport was never put to it. Still open.)*

**Amended 2026-09-15 (phase 3, Windows; decision 8, semantics 2 and 3; WINDOWS-NEW-8, TABLE-NEW-11):
cells and headers are found by `CellFacet`.** The Grid/Table rows above say `GetItem` answers "a
realized cell" and "column headers are the header group's children", and the column header item is
"that grid's header group's child at the cell's column". As built until this date, `GetItem` matched a
`ROW` child by its `SelectionItemFacet` position (row + 1), so a calendar, whose week rows carry no
position, answered no day, and a row whose position is not its view index answered the wrong one;
the header group was the table's *first* `GROUP` child, so a footer or any other group ahead of it
was answered as the headers; and a cell's header item was the header at the cell's column index among
the group's children. Now: `GetItem(r, c)` answers the node with `CellFacet(r, c)` among the children
of the table's `ROW` children whose nearest table is this one (a widget cell under its synthetic row
included), null for a negative row or an unrealized one; `GetColumnHeaders` answers, in reading order,
every `CellFacet(-1, c)` child of the table's direct `GROUP` children, a footer's `-2` cells never; and
`GetColumnHeaderItems` answers the header whose `CellFacet` column is the cell's.

**Amended 2026-09-15/16 (phase 3, Windows, and the two fix rounds after it; decision 36): a sorted
header says its direction in `ItemStatus` beside `HelpText`.** Written in three passes — read first,
then carried, then made to raise — and folded here as one.

*The reading.* UI Automation has no sort-direction property (none in UIAutomationCore.dll's type
library, read 2026-09-13), so it was read off native headers on the Windows 11 guest (10.0.26200,
UIAutomationCore.dll 7.2.26100.9457, .NET Framework 4.8 (Release 533509, 4.8.09221; UIA and WPF
assemblies 4.8.9347, WinForms 4.8.9325); 2026-09-15,
`scripts/a11y/windows/read-native-sort-direction.ps1`, readings/windows-read-native-sort-direction.txt):
one column sorted each way and one not, every property id 30000-30200 read through the COM client.
**File Explorer's details view carries it in `ItemStatus` (30026)** of the sorted column's header — a
`SplitButton` (50031) of class `UIColumnHeader` under a `Header` — as a localized phrase ("Classificado
(Crescente)", "Classificado (Descrescente)" on that pt-BR guest, switching with `SortColumns` and
nothing else changing), and answers no `ItemStatus` on the others. **A WPF `DataGrid` (`SortDirection`),
a WinForms `DataGridView` (`SortGlyphDirection`) and a Win32 list view (header format flags) carry
nothing**: no property differs between their sorted and unsorted headers, and the managed peers' and
proxies' IL (same reading, part 1) reads no direction. So the platform's carrier is Explorer's, a
status phrase on the header. NVDA 2024.4.2 reads `ItemStatus` as a description only for an element of
class `UIColumnHeader` (readings/nvda-2024.4.2-uia.md), while it reads `HelpText` as every element's
description — which is why a move to `ItemStatus` **alone** would silence it on Limn's headers, and
why both are answered.

*What is answered.* `UiaProperties.valueOf` answers `ItemStatus` (30026) on a cell of the header row
whose `Sort` is not `NONE`, with **the node's description** — the localized phrase the model resolved
at publish, where a locale scope was open, which is the whole reason `CellFacet` kept the description
beside the enumeration the other two platforms read (§1.2's amendment of 2026-09-15). `HelpText`
(30013) needed no change: it already answers that same description, so the settled list's "`ItemStatus`
**and** `HelpText`" is true of one string published once, and NVDA keeps hearing exactly what it heard.
The header row is what makes a header: a footer cell (`-2`) and a data cell head no column, whatever
direction their facet carries.

*A choice and not a reading, marked where it is made:* **a busy sorted header answers the busy word
alone.** `ItemStatus` is one string and two facts want it; nothing on the guest reads on composing them
(Explorer's header was not busy), and joining two translated fragments with punctuation chosen on a
thread with no locale open would invent a sentence in twenty-one languages. Busy is the transient state
the property exists for, and the direction is not lost while it holds, because `HelpText` still carries
it. A live Narrator or Inspect run over a header that is both would settle it; phase 5. *(Not
reached by phase 5, 2026-09-16 to 2026-09-22: no run made a header busy and sorted at once, and no
Narrator or Inspect run was made over one. Still open.)* Pinned by
`UiaPropertiesTest.aSortedHeaderSaysItsDirectionInItsStatusAndInItsHelpTextAndBusyWinsOverBoth`.

*What raises it.* This record said for a day that nothing did — "a sort arrives as a publish and not as
a state change" — and that was half right. A sort that moves is not a state change and it is **not
silent**: it moves the header cell's description, and the differ emits a `DESCRIPTION_CHANGED` for
exactly that. The bridge raised `HelpText` alone, so a client that caches `ItemStatus` — the property
File Explorer's convention exists for — went on reading the direction the column used to be sorted in.
A `DESCRIPTION_CHANGED` on a **cell of the header row** now raises `ItemStatus` as well as `HelpText`,
through a second mapping named `UiaBridge.alsoChangedProperty`; it is the only change today that moves
two properties outside `raiseValue`.

*And what a change carries is what the getter answers* — the rule that replaced this amendment's own
first attempt at it (2026-09-16, the fix round's review). That attempt said an empty string is what
`ItemStatus` carries for "nothing to say". It is not: a node with no status answers `null`, written as
`VT_EMPTY`, which is what the getter's own comment says ("an item that is not busy has no status at all
rather than a status saying it is idle"). The `BUSY` mapping wrote `""` when busy cleared, and on a
header that is both busy and sorted that was the stale `ItemStatus` this whole amendment exists to
prevent, raised by the mapping itself: the client was told the status was `""` the moment busy cleared,
while `GetPropertyValue` answered "Sorted ascending". So on all three of its arms an `ItemStatus`
change carries what the getter answers — the busy word while busy holds; for a busy that clears, the
**not-busy** answer for that node (the sort phrase on a sorted header, nothing anywhere else); and for
a description, the description only where the getter reads it as a status. It is the not-busy answer
and not `GetPropertyValue` itself because the node in the published tree carries `BUSY` on the busy
side of the change, so asking the getter for the old value of a busy just set would answer the busy
word twice. The same rule closes the other gap in the pair of guards: the raise's guard is the header
row *while the getter also asks for a direction*, so a header cell whose description is its own — no
widget writes one today; `Table` writes only the sort phrase — raises a change from nothing to nothing
instead of announcing a status the element denies. The wider guard stays, because the change that
*ends* a sort leaves the facet at `NONE` and is exactly when a cached direction is most wrong; and
nothing is raised while `BUSY` holds, which is the getter's choice again: busy owns the one string
while it lasts. Pinned by
`UiaBridgeTest.aSortedHeadersDescriptionMovesTheStatusThatCarriesItAndADataCellsDoesNot`, red three
ways (the `ItemStatus` arm removed; the header-row guard dropped, so a data cell's own description is
raised as a status; the busy guard dropped), driving a description change on the footer cell and on the
unsorted column's header too, and by
`UiaPropertiesTest.whatAnItemStatusChangeCarriesIsWhatTheGetterAnswers` (red with the empty string put
back, and red with the description arm removed).

*What phase 5 still hears* is the composition choice — a header that is both busy and sorted — and not
whether the direction is announced at all. *(Still unheard: no phase-5 run, 2026-09-16 to
2026-09-22, made a header busy and sorted at once, so the composition choice is open. The direction
alone was heard on 2026-09-16: NVDA 2024.4.2, Orca 50.2 and Orca 46.1 each said 'Ordenado em ordem
crescente' on the sort.)*

**Amended 2026-09-15 (phase 3, Windows; WINDOWS-NEW-1, WINDOWS-NEW-3): `UiaRaiseNotificationEvent` and
`UiaRaiseStructureChangedEvent` are bound and raised.** The event-flush row lists both; neither was
bound, an `ANNOUNCEMENT` (node `0`) was mapped to the notification event id and then dropped at the
held-element gate, and a `STRUCTURE_CHANGED` went through `UiaRaiseAutomationEvent`, which carries no
type and no runtime id. Both entry points are bound optionally, outside `Uia.isAvailable`, with the
parameter lists read on the guest 2026-09-13 (readings/windows-dump-uia-entry-points.txt: ordinals 97
and 98, not forwarded). How §2.4 raises them is amended there.

**Amended 2026-09-22 (decision 108, P5W-3): a window's teardown withdraws its provider, disconnects
every element a client was handed, and frees an object only when the platform holds no reference
on it.** The teardown row above ("`UiaDisconnectProvider`; `UiaReturnRawElementProvider(hwnd, 0, 0,
NULL)` on `WM_DESTROY`", "both proven") described what the probe did, not what the bridge did: the
bridge disconnected the root alone, never returned NULL for the window, and freed every vended
object outright at the registry's empty. With NVDA attached, closing a date picker's native popup
was measured on 2026-09-16 as a hang and on 2026-09-22 as a crash — the process dying in `jvm.dll`
at one offset, about 30 ms after `freed 14 objects`, in every run (`readings/p5w3-windows/`). Three
changes, each measured on the guest before the next was made: disconnecting the other thirteen
objects moved the crash 240 ms later and into "module unknown" (a freed trampoline); returning
NULL for the HWND changed nothing; and freeing by reference count closed it. The count is what
COM gives an object for this: after the disconnects, an object the platform still references is
retired rather than freed, answers nothing, and is freed on the user-interface thread once the
platform's last `Release` has come — which it did, for six of the seven kept, 2.8 s later
(`date-picker-native-fix-4`, `freed 7 objects, kept 7 … freed 6 retired objects the platform had
let go of`). One that never comes is a bounded leak, the failure to prefer. The native date-picker
script then ran its eleven steps under NVDA and the demo exited on its own; `UiaBridgeTest` pins
the order (withdraw, disconnect root, disconnect the rest, then free only the unreferenced) and the
deferred free. `UiaWindow` also answers `WM_DESTROY` with the NULL return, for a window destroyed
under a still-attached bridge; the usual order is the scene's detach while the window is alive.

### 2.2 macOS: NSAccessibility

| Attribute / action / notification | Answered from | Note |
| --- | --- | --- |
| `accessibilityRole`, `accessibilitySubrole` | role | role constants resolve by `dlsym` on AppKit; `NSAccessibilityButtonRole` proven to dereference to `AXButton` |
| `accessibilityRoleDescription` | localized from the role under the node's locale | **measured, and the reason is narrower than "AppKit's default is English-only"**: `NSAccessibilityRoleDescription()` localizes against the *calling process's* bundle localization, and a JVM launched from a jar has none — so on the phase 7 guest, whose entire desktop is pt-BR, VoiceOver said "checkbox", "text field" and "slider" in English while wrapping them in its own Portuguese ("Você está em um item do tipo campo de texto"). Deferring to AppKit is therefore not an option that works, on this or any non-English system, and the phrase has to be ours. It is **not yet implemented**: the catalog it needs does not exist, and the six roles §7 already degrades would want entries in it too |
| `accessibilityTitle` / `accessibilityLabel` | name, chosen by `nameFrom` | Finding 5. `CONTENT` → title; anything else → label; **never both** |
| `accessibilityHelp` | description | the tooltip lands here when it is not the name |
| `accessibilityValue`, `accessibilityMinValue`, `accessibilityMaxValue` | `ValueFacet`; `ToggleFacet` as `@0`/`@1`/`@2`; **`TextFacet` as its text** | three facets share one attribute, which is why they are separate facets rather than one field. A text node with no `TextFacet`-sourced value is a field VoiceOver cannot read (§2.1) |
| `setAccessibilityFrameInParentSpace:` | bounds in the **parent element's** space, y measured from its bottom | **proven end to end**: `(40,40,160,48)` in parent space read back as `rect(240,412,160,48)` on screen. AppKit owns the flip and the title bar (§1.8). `accessibilityFrame` is implemented too, answering the same box, for a client that asks the element directly. Struct-by-value both ways needs libffi; `CGRect` proven at size 32, alignment 8. **"Parent" is literal, and an earlier draft of this row said "the content view's space", which is true only of the root's own children**: the phase 7 probe run measured the offsets adding up through three levels — a child at `(20,20)` of a group at `(20,60)` of the view lands at the view's origin plus `(40,80)` — so a bridge hands each node its box **relative to its own parent node**, not relative to the view |
| `accessibilityParent`, `accessibilityChildren` | the snapshot links | **the top of the tree is pushed, everything below it is pulled** — see below. `setAccessibilityChildren:` on the content view is proven sufficient to place a Java-built element under the window a screen reader walks, and the push is **repeated whenever the root's children change**, because that array is a snapshot AppKit holds and an overlay opening changes it |
| `accessibilityFocusedUIElement` | the focused node's element | **not proven, and not obviously ours to answer**: our elements are not responders, and the spike never moved focus. §13 carries the experiment; until it runs the bridge posts `AXFocusedUIElementChanged` (which is delivered, and only at application level) and does not claim the attribute |
| `isAccessibilityElement` | `true` for every published node | transparent and ignored widgets never become nodes |
| `accessibilityEnabled`, `accessibilityFocused` / `setAccessibilityFocused:` | states | `setAccessibilityEnabled:` proven; a `BOOL` argument rides the low bits of a pointer-sized slot |
| `accessibilitySelectedChildren` | `SelectionFacet` | |
| `accessibilityRows`, `accessibilityColumns`, `accessibilityHeader`, `accessibilitySelectedRows`, `accessibilityRowCount`, `accessibilityColumnCount` | `TableFacet`; ADR 041 §7 | rows are the realized `ROW` children; columns are synthesised, one per header cell; the header is the table's first group child. Constants and selector encodings read off the guest before the bridge grew them |
| `accessibilityRowIndexRange`, `accessibilityColumnIndexRange` | `CellFacet`; ADR 041 §7 | a range of one at the cell's row and column |
| `accessibilityHitTest:` | **implemented on our own element class, and it must recurse to the bottom itself** | the spike measured that with no override at all `AXUIElementCopyElementAtPosition` found its one element, and concluded AppKit hit-tests from the frames. It does — **for one level only**. The phase 7 probe run put three grandchildren under a group and three points inside three different grandchildren all resolved to the *group*, which is the level that was pushed onto the content view; pushing `setAccessibilityChildren:` at every level as well changed nothing, so this is not stored-versus-pulled children. Overriding the selector on `LimnProbeElement` resolved all four probed points to the correct deepest node, at depth 3. **AppKit sends it exactly once**, to the element it already resolved from the pushed array, so the override walks the whole subtree rather than returning one level. The point arrives in screen space with a bottom-left origin — the same space `accessibilityFrame` answers in, so no flip of ours. It goes on our own class, so §13.16 stays withdrawn: nothing is installed on a class GLFW owns |
| `accessibilityIdentifier` | the node id, as a string | stable across frames by §1.3 |
| `accessibilityPerformPress`, `…Increment`, `…Decrement`, `…ShowMenu`, `…Pick`, `…Cancel`, `…Confirm` | `ActionFacet` | **press proven end to end**: an out-of-process client's `AXUIElementPerformAction(kAXPressAction)` arrived in Java on the main thread. AppKit's encoding is `B16@0:8`, so the return is a C `bool`. The other six are the same shape and are untested |
| `accessibilityNumberOfCharacters`, `accessibilitySelectedText`, `accessibilitySelectedTextRange`, `accessibilityStringForRange:`, `accessibilityRangeForLine:`, `accessibilityInsertionPointLineNumber` | `TextFacet` | `NSRange` is UTF-16, which is `TextEditModel`'s unit exactly |
| `accessibilityFrameForRange:` | **not answered in the first cut** | §11: there is no geometry seam behind it |
| `NSAccessibilityPostNotification` | the event flush | **proven delivered out of process** to a real `AXObserver`, carrying the updated value. `AXValueChanged` reaches an observer registered on the element *or* on the application element; `AXFocusedUIElementChanged` reaches **only** the application-element registration, so focus is posted at application level and never per element |
| `…PostNotificationWithUserInfo` with `AnnouncementRequested` | `ANNOUNCEMENT` | politeness rides `NSAccessibilityPriorityKey` |

**Amended 2026-09-15 (phase 3, the macOS bridge; the rows above stand as written and these
sentences say what the bridge now does where they differ).** *Where the user is* (decision 1,
semantics 4): `accessibilityFocusedUIElement` — answered on the content view since §13.22 — and
`isAccessibilityFocused` both answer from the tree's `effectiveFocus()`, the cursor item under the
focused widget when there is one, so the table under the keyboard answers false and its cursor cell
true. A cursor resolved into a native popup's tree (decision 5) is answered with the element the
popup window's own bridge mints, and the popup's view, with nothing of its own focused, answers the
same element; the bridges of a process's open windows find each other through one process-wide set,
entered on a publish and left on a detach. *Rows* (M2; semantics 1 and 2): an outline and a list
holding a selection answer `accessibilityRows`, `accessibilityVisibleRows` and
`accessibilitySelectedRows` from their realized members, whatever role a cell kept, and each member
answers `accessibilityIndex` zero-based — the hierarchy facet's flat row less one for an outline row,
its position in the set less one for a list row, `NSNotFound` when the number is unknown — because a
native `NSOutlineView` answered AXIndex 0, 1, 2… down its visible rows and no `AXRowCount` (read on
the macOS 26.6.2 guest, 2026-09-15, `scripts/a11y/macos/outline-probe.swift`). *The gate refuses
getters too*: AppKit honours a refused getter (read 2026-09-13, §6 of that day's macOS readings), so
the row selectors are refused on everything that is not a table, an outline or a list, the index on
everything that is not a row, and the two counts on everything that is not a table, instead of
answering nil, −1 or zero there. *Selection* (MACOS-NEW-2; semantics 1): a container's selection is
read off the attribute of its shape — `accessibilitySelectedRows` for an outline, a list or a table
of rows; `accessibilitySelectedCells` for a grid whose members are cells, a calendar's days;
`accessibilitySelectedChildren` for anything else holding one, a tab strip — each answered from the
selected members whose selection container it is, wherever they hang, and each refused where it is
not the container's shape, as the native outline answers `AXSelectedRows` and no
`AXSelectedChildren`. **Corrected 2026-09-16 (fix round 3b; the phase-3 critic's semantics-1
minor):** `accessibilitySelectedRows` did not read that rule — it answered the container's direct
`ROW` children carrying `SELECTED`, which names the same elements for every container Limn ships and
parts from the rule at the first whose rows hang under a synthetic body. It now walks the members
`accessibilitySelectedChildren` walks, narrowed to the members that are rows, so a calendar's
selected day stays under `AXSelectedCells` and a row that declares it belongs to no container
(`containerlessSelectionItem`) is none of the table's selected rows; the `setAccessibilitySelectedRows:`
write path reads the same set, so a client can write back what it read. `accessibilityRows` and
`accessibilityVisibleRows` still answer a table's `ROW` children by structure (ADR 041 §7): "through
synthetic ancestors" is a fact the model resolves once at publish and carries on a selection member
and nowhere else, so no bridge can apply it to a row that is a member of nothing. **That leaves the
two row listings able to disagree, and the correction does not close it:** for every container Limn
ships the selected rows are a subset of the rows, and in the synthetic-body shape they are not —
`accessibilitySelectedRows` names a row `accessibilityRows` does not, which is incoherent for a
client. Closing it needs a policy for `accessibilityRows` that no decision, ADR or reading settles
(which nodes a table's row listing descends through) or a model that carries syntheticness or a row
list into the snapshot; both are cross-bridge, Linux's `GetSelectedRows` half having the same shape,
and both are the orchestrator's. Open, named at `AxGrid#selectedRows` and asserted in
`AxGridTest.aTablesSelectedRowsAreTheMembersOfItsSelectionWhereverTheyHangUnderIt`, which pins
`AXSelectedRows` naming a row `AXRows` answers nothing for; nothing Limn ships is in that shape today. *Recorded the same
day (the macos-B review):* the native outline also answered
`AXSelectedCells` — the `AXCell` its selected row holds — and an outline or a list here deliberately
does not: a Limn row holds no cell element, its children being the application's own widgets, so the
answer would repeat the rows under a cell's attribute or name an arbitrary widget, and the native
outline told its selection only as `AXSelectedRowsChanged`. Whether VoiceOver reads a native outline's
selected cells at all is phase 5's to hear. *(Not heard in phase 5, 2026-09-16: the macOS run
measured that Limn's own outline neither lists nor answers `AXSelectedCells` (`kAXErrorNoValue`),
and put no native outline under VoiceOver. Still open.)* *Disclosure* (M1): an outline row answers `isAccessibilityDisclosed` from its
expand facet, `accessibilityDisclosureLevel` as the hierarchy facet's level less one, and
`accessibilityDisclosedByRow` / `accessibilityDisclosedRows` by walking the outline's realized rows
while their flat row numbers run without a gap — a gap answers nothing rather than a grandparent —
because the native outline's rows answered AXDisclosureLevel 0 at the top, their parent row and the
rows one level down, on leaves too, and **no `AXExpanded`**; so `isAccessibilityExpanded` is answered
for every other node with an expand facet and refused on an outline row, and a level of zero refuses
the level getter (semantics 6). *Press and confirm* (MACOS-NEW-5; semantics 5): both map to the
candidates `PRESS`, `TOGGLE`, `SELECT`, `EXPAND`, `COLLAPSE` in that order, the first the node accepts
(`AccessibleNode#accepts`) posted, so a combo box, a menu title or a date field that publishes only the
one of `EXPAND`/`COLLAPSE` its state allows is pressed open or shut, and a node publishing no verb is
offered no press whatever facet it carries; `…Pick` stays absent (AxActions says why). *The setter
half* (MACOS-NEW-11; semantics 5): `setAccessibilityFocused:` YES posts `FOCUS`;
`setAccessibilitySelected:` YES `SELECT`, NO `DESELECT`; `setAccessibilityDisclosed:` (an outline row)
and `setAccessibilityExpanded:` (anything else that opens) YES `EXPAND`, NO `COLLAPSE`;
`setAccessibilityValue:` a string as `SET_TEXT` to a text, a number as `SET_VALUE` and a string as
`SET_VALUE` of text to a writable value — each posted only where `AccessibleNode#accepts` holds for
that verb, never waited for. ~~**Settable is the gate's answer for the setter** (read on the guest
2026-09-13), so each of these is offered exactly where its write would post, AXDisclosing only on a
row that can open as the native outline's is~~ — **measured false on 2026-09-16, and the sentence is
wrong in both halves.** `AXUIElementIsAttributeSettable` does ask `isAccessibilitySelectorAllowed:`,
and when the class itself *implements* the setter **AppKit discards the NO and reports settable
anyway**; the gate's refusal is honoured only for a selector the class does not implement. Proved
twice: inside Limn every element of the node class — a leaf row, a table row, a static text with no
actions — answers settable for all five installed setters while the **column** element, whose class
installs none, answers no to all five; and outside Limn, with four `NSAccessibilityElement`
subclasses in one window, the subclass that overrides the setters reports settable against a gate
logged saying NO, and the one that inherits them reports not settable against the same NO. What the
gate really decides is **delivery**: a refused write returns `AXError(0)` and the setter is never
entered (0 setter lines in the probe; a leaf's `AXDisclosing=YES` opened nothing, a branch's
`AXDisclosing=NO` really closed it, 9 rows to 5). So the rule is enforced where it matters and what
is wrong is what a client is **told before it writes** — a divergence from native AppKit, where a
leaf row answers `AXDisclosing settable=false` and a row answers `AXFocused AXError(-25205)`. The
2026-09-13 reading was not wrong about what it saw: every element it read left the setters to
`NSAccessibilityElement`, which is the one case AppKit honours. Readings:
`macos-gate-setter-probe-serve.txt`, `macos-axgate-outline.txt`, `macos-axwrite-outline.txt`.

***Fixed the same day, 2026-09-16 (decision 78), and the mechanism was measured before anything was
built.*** The rule is **`settable` = the modern gate's YES OR the legacy
`accessibilityIsAttributeSettable:`'s YES**: AppKit asks `isAccessibilitySelectorAllowed:` first and
a YES there ends the question, and a NO there is where it falls back to the legacy selector *when the
element answers it* — with nothing to fall back to it reports settable anyway for any setter the
class implements, which is all six of ours. So neither answer alone can say no, and the element now
answers the legacy one **from the same `AxGate`** that decides delivery. A client is therefore told
exactly what it may write. Read on the macOS 26.6.2 guest (25G83) 2026-09-16, seven elements in one
window with every ask logged (`readings/macos-settable-mechanism-serve.txt`,
`macos-settable-mechanism-read.txt`, probe kept beside them): the element whose gate refused nothing
was asked the legacy selector for **no** setter attribute at all; the element whose gate refused two
was asked for exactly those two, answered no to both, and only then did a client read `no`; and the
attributes with no modern setter behind them — `AXPosition`, `AXElementBusy` — are asked of it on
every element, which is why an attribute the bridge does not map answers **false** rather than
defaulting to true.

**The Objective-C classes did not have to multiply, and the approval to multiply them was not
needed.** The decisive element in that reading is one class with two instances differing in nothing
but their nodes' answers: the leaf reports `AXDisclosing settable=no` and the branch `settable=YES`,
both reporting `AXFocused settable=no`. That is precisely what a native `NSOutlineView` does with its
one row class — leaves `row#2` and `row#5` false, collapsed branch `row#4` and open branches `row#0`
and `row#1` true (`readings/macos-outline-probe.txt`) — and it is the case per-shape classes could
not have expressed at all, since leaf and branch are the same shape. The **column** class is left
untouched: it installs no setters, so a client already read `no` for all of them. Runtime cost: still
two Objective-C classes for the whole process, one new libffi closure, and six attribute-name
constants read off AppKit once at class construction and turned into text there rather than per ask.
Nothing in §3.2's reentrancy rules is touched — the closure calls `source.entered()`, reads the
published snapshot and returns a `BOOL`, posting nothing and waiting for nothing.

***Read live on the guest*** with this branch's own jar, by the same `axgate` and `axwrite` probes
that produced the old answers, so the two readings differ only in the jar
(`readings/macos-settable-outline-new.txt`, `macos-settable-table-new.txt`,
`macos-settable-write-new.txt`, against `macos-axgate-outline.txt`, `macos-axgate-table.txt`,
`macos-axwrite-outline.txt`):

| element | old (main) | new |
| --- | --- | --- |
| static text `Arquivos` | `Focused=YES Selected=YES Disclosing=YES Expanded=YES Value=YES` | all `no` |
| outline `Arquivos` | all five `YES` | `Focused=YES`, the other four `no` |
| outline branch `Documents 2` | all five `YES` | `Selected=YES Disclosing=YES`, the rest `no` |
| outline leaf `Q3 regional revenue…` | all five `YES` | `Selected=YES`, **`Disclosing=no`**, the rest `no` |
| table `Cordilheiras` | all five `YES` | `Focused=YES`, the other four `no` |
| table row `Alps` | all five `YES` | `Selected=YES`, the rest `no` |
| table cell `Alps` | all five `YES` | `Focused=YES`, the rest `no` — as a native cell reports `AXSelected` not settable |
| column | all five `no` | all five `no`, byte-identical: its class installs no setters, so a client already read the truth |

The leaf-versus-branch pair is the point, and the live run shows it sharper than the lab probe did:
`Media 2` and `Remote` are *collapsed* branches, so they print `disclosing=0` exactly as the leaf
does, and they answer `Disclosing=YES` while the leaf answers `no` — same class, same printed state,
different answer, because one can open and the other cannot. That is what a native `NSOutlineView`
does (`row#4 'Pictures'` `AXDisclosing=0 settable=true`, `row#2 'Q1'` `AXDisclosing=0
settable=false`). **The write path is unchanged**, which the write probe prints in its own words: the
leaf's line now reads `write AXDisclosing (settable said no) -> AXError(0)` where it read
`(settable said SETTABLE)`, and the rows stay at 9 either way, while the branch control still really
closes, 9 rows to 5.

And every other `setAccessibility…` selector —
`NSAccessibilityElement`'s stored setters, which a client read as settable on every element, `AXRole`
included — is refused on every node. The setters are installed only together with the gate. *Corrected
the same day (the macos-B review; MACOS-NEW-11's last setter):* `setAccessibilitySelectedRows:` is
installed too, settable where a container's selection is its rows and a realized row takes a selection
verb. Read on the guest 2026-09-15 (`scripts/a11y/macos/selection-writes-probe.swift`), a native
outline's selection becomes exactly the rows written in either mode — one row replaces, two rows in a
multi-select outline become the selection, an empty array empties it, two rows in a single-select
outline are refused (`kAXErrorIllegalArgument`) — and `AXSelected` YES on a second row of a
multi-select outline **replaces** the selection too, so the `SELECT` it posts is right in both modes.
So one row written posts `SELECT`; several, or none, post the difference — `DESELECT` on each selected
row left out, `ADD_TO_SELECTION` on each written row not selected — and the write is refused whole
unless every row accepts its verb, or when it names an element that is not a row of the container. A
single-select row publishes no `DESELECT` (decision 20), so there an empty array, and `AXSelected` NO,
are refused where the native outline, which allows an empty selection, clears it.
*`AXScrollToVisible`* (new in macOS 26, and with no selector anywhere): read on the guest 2026-09-15
(`scripts/a11y/macos/scroll-to-visible-probe.swift`), an `NSAccessibilityElement` subclass answering
the legacy `accessibilityActionNames` has its perform of that name delivered to
`accessibilityPerformAction:`, while a custom action of that name is never run and a guessed
`accessibilityPerformScrollToVisible` never entered; and answering the names replaces AppKit's derived
list. So the bridge answers `accessibilityActionNames` with every action the node offers plus
scroll-to-visible where it accepts `SCROLL_INTO_VIEW`, and `accessibilityPerformAction:` posts the verb
a listed name means; the pair is installed together, and only with the gate.
*Table cells, rows and headers (MACOS-NEW-4, MACOS-NEW-9, MACOS-NEW-10; semantics 2 and 3; the
same day):* `accessibilityCellForColumn:row:` answers the node whose `CellFacet` is (row, column)
under one of the table's `ROW` children and whose nearest table ancestor is the table — a widget cell
under its row included — and never reads a selection position; a table row's `accessibilityIndex` is
its cells' row, so a calendar week, which carries no selection item, is found and numbered like any
row; the header of column c is the child with `CellFacet(−1, c)` of one of the table's direct group
children, matched by column, and a table with no such child — its header hidden, its footer shown —
has no `accessibilityHeader` and no column headers at all, where the row above said "the table's
first group child". A native `NSTableView` read on the guest (2026-09-15,
`scripts/a11y/macos/table-probe.swift`) answered no `AXHeader` without its header view and no
`AXColumnIndexRange` on its header buttons, so both are refused there, and the two index ranges are
answered on data cells only.
*Columns (M4; decision 34, the same day):* the row above said "columns are synthesised, one per
header cell", and the bridge answered an empty array beside a column count. The native `NSTableView`
read on the guest answers `AXColumns` and `AXVisibleColumns` with one `AXColumn` element per column,
lists them among the table's children after its rows, and each column answers `AXIndex`, `AXHeader`
(its header button; none on a headerless table), `AXRows` and `AXVisibleRows` (that column's cells, in
row order), `AXParent` (the table), `AXSelected` and a frame spanning the header and the rows, and no
`AXChildren`; `AXSelectedColumns` is an empty array. So the bridge vends the same: one column element
per shown column, an instance of a second runtime subclass of `NSAccessibilityElement` whose closures
answer those attributes from the table's cells and its header cell in that column, kept in a registry
of its own keyed by the table's identifier and the column — the toolkit gains no column role (§1.12)
— listed after the table's nodes among its children, its box pushed like a node's (the header cell's
span over the table's height). A column goes at a frame's end when its table has left the tree or no
longer shows it, and all at once on a rebind or a detach, demoted before it is released like every
element, never from a reentrant publish. Its role description is AppKit's own, `column`: no toolkit
phrase names a column (left for a later pass to translate). The native table also answered no
`AXRowCount`, `AXColumnCount` or `AXColumnHeaderUIElements`; the bridge keeps answering those three,
which carry the model's counts and a cell's header that the realized rows cannot, until a reader run
says otherwise.
*Release on `NODE_DESTROYED` (MACOS-NEW-1, the same day):* the paragraph below says the bridge releases
on `NODE_DESTROYED`, and until this date nothing called the registry's release, so every element a
client ever pulled stayed retained, answering nil. The release now happens at the end of the frame that
emitted the destruction, after that frame's posts, and only for a node still absent from the tree then:
an identifier keyed by a row that is destroyed and published again within the frame is the same node,
whose element a client may be using. It posts nothing, as the paragraph says.
*A value with no number (decision 16; CRIT-4's macOS half, the same day):* `accessibilityValue` answers
a value's displayed text when it has one and its number otherwise, and an empty `ValueFacet` — a date
segment nobody has typed into, which publishes its minimum for the platforms that must have a number —
answers its word, or nothing when it has no text, and never the minimum: `AXValue` is an object here and
demands no number. A change of the text or of the emptiness alone is a `VALUE_CHANGED` in the model, and
this bridge posts it as `ValueChanged` like any other; the row's "minValue / maxValue" are still not
installed.

**The macOS rows as built (dated 2026-09-15; MACOS-NEW-7).** The table at the head of this section is
kept as it was written, and the dated notes above say what changed item by item. This is the whole of
what the bridge installs at the end of phase 3, row by row, so that nothing a reader of this section is
told is a promise the code does not keep. Every selector named here is listed in `AxSelectors`, tied to
the committed AppKit dump by `AxConstantsTest`, and answered only where `isAccessibilitySelectorAllowed:`
(`AxGate`) allows it; everything else a row of the table above names is marked *not installed*.

| Attribute / action / notification | As built |
| --- | --- |
| `accessibilityRole`, `accessibilitySubrole` | `AxRoles`, resolved by `dlsym`; unchanged |
| `accessibilityRoleDescription` | **installed** (the row said "not yet implemented"): the toolkit's own phrase for the role under the node's locale (`RoleNames`); a column element keeps AppKit's own `column` |
| `accessibilityTitle` / `accessibilityLabel`, `accessibilityHelp`, `accessibilityIdentifier` | as the rows say |
| `accessibilityValue` | toggle 0/1/2, text, a value's displayed text else its number, an empty value its word (`AxValues`); **`accessibilityMinValue` / `accessibilityMaxValue` not installed** |
| `setAccessibilityFrameInParentSpace:` | pushed for every held element and column element on each ordinary publish and on mint; **`accessibilityFrame` is not installed** (the row said it was): AppKit answers it from the pushed box |
| `accessibilityParent`, `accessibilityChildren` | the snapshot links; a table's children end with its column elements; the root's children pushed, re-pushed when they change |
| `accessibilityFocusedUIElement`, `isAccessibilityFocused` | **installed** (the row said "not claimed"), on the content view's own subclass and on the element class, from the tree's effective focus, across a native popup's window |
| `setAccessibilityFocused:`, `setAccessibilitySelected:`, `setAccessibilityDisclosed:`, `setAccessibilityExpanded:`, `setAccessibilityValue:`, `setAccessibilitySelectedRows:` | installed with the gate, each posting the verb it means where `AccessibleNode#accepts` holds; every other stored setter refused. **`setAccessibilityFocused:` is refused on a row as well** — a native row carries no `AXFocused` at all, and offering it let VoiceOver's cursor sync drag the application's cursor back after every key (amendment 2026-09-16, end of this section) |
| `accessibilitySelectedChildren`, `accessibilitySelectedRows`, `accessibilitySelectedCells` | the one of the container's selection shape (children / rows / cells) |
| `accessibilityRows`, `accessibilityVisibleRows`, `accessibilityIndex` | tables, outlines and lists; a table row's index is its data cells' row, already zero-based (`NSNotFound` with no data cell); an outline row's is the hierarchy facet's flat row less one; a list row's is its position in the set less one |
| `accessibilityRowCount`, `accessibilityColumnCount` | tables only, the table facet's counts (a native table answers neither; kept, see the columns note) |
| `accessibilityColumns`, `accessibilityVisibleColumns`, `accessibilitySelectedColumns` | **one column element per shown column** (the row said "synthesised, one per header cell"), answering role, index, header, rows, visible rows and parent; selected columns empty |
| `accessibilityHeader`, `accessibilityColumnHeaderUIElements` | the group holding the header cells, matched by `CellFacet(−1, c)` (the row said "the first group child"); none on a headerless table |
| `accessibilityRowIndexRange`, `accessibilityColumnIndexRange`, `accessibilityCellForColumn:row:` | data cells; the cell found by its own cell facet |
| `isAccessibilityDisclosed`, `accessibilityDisclosureLevel`, `accessibilityDisclosedByRow`, `accessibilityDisclosedRows`, `isAccessibilityExpanded` | outline rows (zero-based level); expanded on everything else with an expand facet |
| `accessibilityHitTest:` | as the row says |
| `accessibilityPerformPress`, `…Confirm`, `…Increment`, `…Decrement`, `…ShowMenu`, `…Cancel` | installed with the gate; press and confirm map to `PRESS`, `TOGGLE`, `SELECT`, `EXPAND`, `COLLAPSE`; **`…Pick` is not installed** (the row listed it) |
| `accessibilityActionNames`, `accessibilityPerformAction:` | installed, for `AXScrollToVisible` |
| `accessibilityAttributeValue:`, `accessibilityAttributeNames` | installed, forwarding, for `AXElementBusy` (ADR 044 §2) |
| `accessibilityNumberOfCharacters`, `accessibilitySelectedText`, `accessibilitySelectedTextRange`, `accessibilityStringForRange:`, `accessibilityRangeForLine:`, `accessibilityInsertionPointLineNumber` | **not installed** (the row said "`TextFacet`"): a text is read through `accessibilityValue` alone; owed |
| `isAccessibilityModal` (a dialog's `AXModal`, the elided-root paragraph below) | **not installed**; owed |
| `NSAccessibilityPostNotification` | at the end of the frame that emitted it (§1.10's amendment), not "the event flush"; `…WithUserInfo` for an announcement, on the window |
| sort direction | `accessibilitySortDirection` on a header cell and nowhere else, `NONE`/`ASCENDING`/`DESCENDING` → 0/1/2 (amended 2026-09-15, the fix round's integration; the row's "not served" held only while the model had no carrier) |

macOS is the one platform that hands out real objects the system retains. The bridge allocates lazily
— beyond the root's own children, which the push below requires up front, an element exists only for a
node the platform has asked about — and keeps a map from node id to element so a client's retained
element stays the same object across publishes. That map is UI-thread-confined and checked (§3.4),
which is a simplification only this platform gets.

**It releases on three occasions, and on none of them from inside a callback (§3.2).** On
`NODE_DESTROYED`; on the reconciliation sweep that follows an
event-queue collapse, because a collapse is precisely the burst in which the per-node destructions
were dropped; and on `attach` replacing a live host or `detach`, because a rebind invalidates every
element at once and an earlier draft released nothing there at all (§1.10, §5.3).

**And it posts no destruction notification of its own, which is a correction the probe run forced.**
Every earlier draft of this section said "post `NSAccessibilityUIElementDestroyedNotification`
first, then release", by analogy with the other two platforms. The phase 7 probe measured that
AppKit already posts it: with our own post suppressed, a client holding the element still received
exactly one `AXUIElementDestroyed`, whether it had registered on the element, on the application
element, or on both. With our post added the client received **one more per matching registration**
— two notifications for one registration and three for two. So the explicit post is not the
mechanism by which a client learns; it is a duplicate whose count depends on how the client
registered. This is the same rule as the one two paragraphs down about window events, arrived at
from the other end: what AppKit already says, we do not say again. A live LWJGL `Callback` pins
its Java object through a JNI global reference until `free()`, which is the discipline
`LwjglWindow#destroy` already applies to the preedit callback.

**The top of the tree is pushed once; everything below it is pulled. And the window root is not
vended at all.** This is the part of the design the re-run changed most, so the reasoning is set out
in full rather than asserted.

*What the measurement settled.* An earlier draft of this ADR reasoned that a push model could not
work, because §6's honest listening gate on macOS is "someone has asked" — a flag set the first time
one of our own implementations is entered — and with `setAccessibilityChildren:` nothing of ours is
entered until elements have already been pushed, which needs a published tree, which the flag gates:
a cycle that never starts. It concluded that the bridge should add `accessibilityChildren`,
`accessibilityHitTest:` and `accessibilityFocusedUIElement` to GLFW's content view class, so that the
first ask would be both attach and gate. The spike then measured the opposite premise:
`setAccessibilityChildren:` on the content view **is** sufficient — `viaWindow=true`, the element
nested under `AXWindow` in the walk — and `accessibilityHitTest:` was never needed. Three
`class_addMethod` calls on a class GLFW owns are no longer worth their risk to buy something a proven
one-line push already buys.

*So the cycle is cut at the other end, and it costs one walk — on the first frame, not at bind.* The
bridge pushes the root's children onto the content view with `setAccessibilityChildren:`. That is the
whole push, and it is a handful of elements, not the tree: `accessibilityChildren` on *our own element
class* answers everything below, which is a pull, and the first such call is the gate.

**The push happens on the scene's first frame, and it happens again whenever the root's children
change.** Both halves were wrong in an earlier draft and each was wrong on its own.

*Not at bind.* `Scene#bind` does not lay out — `layoutDirty` starts true, `width` and `height` are
zero, and `layoutPass` runs only from the frame path — so a tree built there describes every widget
as a zero-size box at the origin, and the elements pushed from it would be a window full of nothing,
in the corner. §5.3 gives the scene a priming publish on its first frame instead, after `layoutPass`,
and §5.2 makes `republishNow()` refuse to describe a scene that has never laid out at all. Same one
walk per window, one frame later, against geometry that exists. (Amended 2026-09-16: "not at bind" is
about the *scene*, and stays true on every platform. Windows publishes the window's own node there —
one node, whose role, title and size are facts of the window and not of a layout — for the message
§3.1 describes; macOS neither needs nor gets it.)

*And not once.* The pushed array is a snapshot AppKit holds; nothing re-derives it. Pushing it once
and never again freezes the top of the tree at whatever the first frame contained, while the
published root's children genuinely change underneath — **an in-scene overlay is a child of the
root**, so opening a modal dialog or a menu adds one, and that is the single change a screen reader
user most needs to be told about. So every *ordinary* publish compares the root's child list with the
one last pushed and re-pushes when they differ (§5.3). A **reentrant** publish never does: it would
replace, from inside an AX callback, the array AppKit is walking (§3.2). Everything deeper is a pull
and needs no push at all.

That trades a strictly-honest gate for one tree walk per window on its first frame, and the trade is
worth naming: on macOS, unlike Windows, a window that is never touched by an assistive technology
still pays one describe pass in its life. It buys the elimination of the only piece of this design
that would have modified a class the windowing library owns.

*And AppKit already vends the window.* The walk shows exactly what: `AXWindow/AXStandardWindow` with
the title, `AXRaise`, the close, full-screen and minimize buttons and an `AXStaticText` for the
title (Finding 4a). Hanging our own `WINDOW`-role root off the content view would publish a window
inside a window, and VoiceOver would announce it twice and offer two sets of window actions. So the
window's root node is **elided on macOS**: its children are what gets pushed. The platform's own
object is already the answer, and a second one is a lie a client will act on.

**Eliding a node has three consequences, and all three are this bridge's, so they are listed here
rather than discovered.** Its `WindowFacet` maps to nothing — for the same reason an in-scene dialog
is given no `IWindowProvider` on Windows. **No event whose subject is the window root is posted by
us**: `WINDOW_OPENED`, `WINDOW_CLOSED` and a window-level `BOUNDS_CHANGED` are already AppKit's
`AXWindowCreated`, `AXUIElementDestroyed`, `AXMoved` and `AXResized` on the object it vends, and
posting ours beside them would double every one — which is §2.4's macOS column read together with
this paragraph. And **a relation whose target resolves to a window root cannot be named here**: §1.11
drops that relation outright in the in-scene mounting, on every platform, and in the cross-window
mounting this bridge answers with AppKit's own window object and otherwise drops it (§13.27). A
dialog, in scene or native, still carries `MODAL`, which the bridge publishes as `AXModal` on the
dialog's own element.

Because the content view class is shared by every GLFW window in the process, nothing here is
installed on it: the push is a message to one instance, and every implementation the bridge writes
lives on its own `NSAccessibilityElement` subclass, where `self` identifies the node through the
bridge's own map — the same recovery the Windows spike used to get a Java object back from a COM
interface pointer.

#### Amendment 2026-09-16 — a row's `AXFocused` is not settable, because a native row has none

**The setter half above, and the `setAccessibilityFocused:` row of the table, describe the setter as
installed and say nothing about what a screen reader does with it. This is what one did.** Phase 5,
macOS 26.6.2, VoiceOver, `readings/phase5-macos/`: with a reader attached, the tree reader script
speaks the wrong row at almost every step. A key moves Limn's cursor, and about 40 ms later a second
`AXSelectedRowsChanged` and a second `AXFocusedUIElementChanged` put it back on the row the reader
was still standing on. Four attempts never got past row index 2, and `Documents 2` ended the run
collapsed — a state the script never asks for, because the `LEFT` of three different steps all landed
on the row the cursor kept being returned to. Stop VoiceOver and it vanishes: the same jar and the
same script walk all 21 rows in order, in two repeats (`tree-noVO-1`, `tree-noVO-2`, the stop proved
by `caps.txt`). And an ordinary client's write reproduces the return trip on its own —
`cl-outline-1/probe.log`, `set AXFocused=true on row[3]: err=0 -> app focused is now: AXRow
'2026.pdf'`.

**The route is the setter this ADR installed, and the reason it was wrong was already in the
readings.** `offers` asked `AccessibleNode#accepts(FOCUS)` and nothing else, and a row does accept
`FOCUS` — correctly, because that is how the toolkit says "the cursor is here" on all three
platforms. What no one checked is whether a *native* element of that shape carries the attribute at
all. It does not. `readings/macos-outline-probe.txt`, taken on the guest on 2026-09-15 and already
cited three rows above for `AXIndex` and `AXDisclosing`: every row of a native `NSOutlineView`, in
every pass, answers `AXFocused=AXError(-25205)` — `kAXErrorAttributeUnsupported` — and
`settable=AXError(-25205)`, while the outline itself answers `AXFocused=1 settable=true`.
`readings/macos-table-probe.txt`: a native `NSTableView` row's `AXAttributeNames` are
`["AXIndex", …, "AXSelected", "AXFrame"]` with **no** `AXFocused`, while the table's do carry it.
**The view takes focus; its rows are selected.** A row that answered "settable" was inviting exactly
the write VoiceOver makes, and VoiceOver's cursor sync and the application's cursor then each
insisted on a different row, twenty times a script.

**So `setAccessibilityFocused:` is refused on a row** — `AxSetters.offers` reads
`node.accepts(FOCUS) && !grid.isRow(node)`, where a row is a table's `ROW` or a member of an
outline's or a list's selection, which is exactly the set AppKit calls `AXRows`. Refused through the
gate, so `AXUIElementIsAttributeSettable` answers false and a client is told rather than ignored, and
checked again on the write. **A reader loses nothing:** the native route to the cursor is
`setAccessibilitySelected:` on the row or `setAccessibilitySelectedRows:` on the container, both
already offered here, both posting `SELECT`, and `SELECT` on a Limn row moves the cursor to it. The
container itself stays focus-settable, as the native outline is.

**Amendment, 2026-09-16, later the same day: this did not close the loop, and the sentence above says
why.** The fix was re-measured against the very runs that found the defect, with a jar carrying it
(`ac9f7cc4`, `main` `10a1575d`) and VoiceOver attached, twice. **Nine unrequested focus moves before,
nine after** — not one fewer — the same nine steps, reverting at +37…+69 ms, and the script still
stops at row index 2. Total notifications 76 before, 77 and 76 after.

What *is* fixed is real and was verified: a client writing `AXFocused` on a row no longer moves the
cursor, where on the old jar it did. But **VoiceOver never used that route.** Every one of the
eighteen reverts begins with `AXSelectedRowsChanged` on the `AXOutline` about 7 ms after our own
move, with focus following some 33 ms later, and the probe shows `setAccessibilitySelectedRows:` on
the container still taking — the setter this section's own paragraph above kept on purpose, reasoning
that "a reader loses nothing" because "`SELECT` on a Limn row moves the cursor to it".

That clause is the defect, and it is not a bridge's to fix. **On AppKit, writing a selection does not
move the keyboard focus** — the view keeps it, and the rows are merely selected; that is the same
native fact this amendment used to refuse `AXFocused` on a row. On Limn, a `SELECT` on a row moves the
cursor. So a reader that mirrors its own cursor into the selection — which is what VoiceOver's cursor
sync does, legitimately, through the native route — drags the application's cursor with it, and
closing the `AXFocused` door changed nothing at all.

**So the open question is a model question and it is stated here rather than answered:** should a
`SELECT` that arrives from a *client write* move the cursor, when the same verb from a key press must?
Answering it by refusing `setAccessibilitySelectedRows:` would diverge from a native outline in the
very way §4.2's second exception was written to avoid. Evidence:
`readings/phase5-hear-the-fixes/macos-findings.txt`, beside `readings/phase5-macos/tree-2`, `tree-3`
and the VoiceOver-stopped controls `tree-noVO-1`, `-2`. Not yet run, and it is the cheap control that
would close the last inference: this jar with VoiceOver **stopped**.

**Answered 2026-09-17 (decision 79), in the model and not in this bridge: it does not.** A `SELECT`
that arrives from a client write selects the row and **leaves the cursor where it was**; a key and a
pointer go on moving it, because the pointer is where the user is and a client write is not. The
three widgets that had one seam for both now take a `moveCursor` argument — `Tree`, `Table` and
`CalendarView`, each in the private mutator its verb hook calls, which was never the seam the key and
the click enter — so no gesture changed. `ListView` is deliberately out of it: there the cursor *is*
the selection (§7.2), and separating them would invent a distinction the widget does not have.

*Every setter on this page stays exactly as it was.* `setAccessibilitySelectedRows:` is still
offered, still maps to `SELECT`, and still changes the selection; what it no longer does is drag the
application's cursor behind VoiceOver's. So the third exception §4.2 would have needed is not taken,
and the reason the amendment above gave for not taking it — that refusing a setter a native outline
offers is the divergence §4.2 exists to prevent — stands.

**What it cost, and how that was paid** (decision 80): `PRESS` sat on the container and opened the
*cursor* row (decision 32), so a reader that selected row 5 and pressed would have opened row 2 — the
same defect one step to the left. Each row now publishes `PRESS` and performs it **on itself**, which
is also what VoiceOver and NVDA really do: they activate the element their own cursor is on, not a
container. The container keeps its `PRESS` and its meaning for Enter and for a client that addresses
it. `Tree#onActivate` and `Table#onActivate` are handed the row that was opened rather than reading
the cursor at dispatch time, which is the one API change either widget takes from this.

*The control this amendment asked for is still owed*: this jar with VoiceOver **stopped**. It would
say whether the nine unrequested moves were wholly the mirror's; the change above removes the route
they travelled, and only a live run says so out loud.

**One asymmetry with the native shape is kept on purpose.** `isAccessibilityFocused` still answers on
a row, and answers true on the cursor row, where a native row refuses the getter too. It is kept
because decision 1 and semantics 4 make the cursor row the element
`accessibilityFocusedUIElement` names: a client that walks there and asks the row whether it is
focused is entitled to a truthful yes, and refusing the getter would make this bridge's own focus
answer unconfirmable. Reading the cursor is not what fights the application; writing it is.

**What this does not close.** The table script shows the same two-focus-change pattern from step 10
on — the step at which it starts moving a *row* — but there the element Limn publishes as focused is
the cell's own widget (`ATEVENT focused = AXCheckBox id=…164 'Visitada'`, put back to `…162`), which
is not a row and is legitimately focus-settable: a native `NSButton` in a cell view is. The write
itself was **not** captured for that path — no phase-5 run had the inbound trace on — so the route
there is inferred and no code was changed for it. It needs one live run with the inbound trace
enabled, to see whether the put-back on a table arrives as `setAccessibilityFocused:` and on which
element, before anything is decided.

**Amended 2026-09-22 (decision 112): a row select that is VoiceOver's stale cursor is refused.**
Decision 79 stopped a client's `SELECT` from moving the cursor; it did not stop the selection. With
a write trace on the bridge (`-Dlimn.a11y.ax.trace=true`, which logs every verb a client performs),
VoiceOver was measured writing `AXSelected` YES — `setAccessibilitySelected:` on a row, not the
container's `setAccessibilitySelectedRows:` the earlier paragraphs suspected — on a row that is not
the cursor's, 38–90 ms after the application's own change and once 689 ms after a load: "Himalayas"
on the table script's step 8, so that the `SPACE` of step 9 left two rows selected, and "Documents
2" after eight of the tree script's twenty-one steps, the first row VoiceOver's own cursor had
settled on. It is the cursor sync of P5M-1 mirroring a cursor that did not follow ours. The bridge
now refuses a client's `SELECT` on a member of the same selection container as the row the user is
in, other than that row, when it arrives less than a second (`AxBridge.STALE_SELECT_NANOS`) after
the bridge was handed a focus, cursor, state or structure change — the last two because an open or
a close moves no cursor and VoiceOver answers it all the same. The cursor's own row is never
refused, and a reader's own select a second later is taken. Heard on the guest the same day against
the phase-10 jar: the table's step 9 no longer adds a row, and the tree script refused 13 writes in
each of two runs, accepted none, and read every row in order with no stray "Documents 2"
(`readings/d112-macos/summary.txt`). It is §4.2's Exception 3. Decision 110, which removed the
container's setter on the earlier suspicion, was built, measured to change nothing, and reverted.

**Amended 2026-09-23: on a table of rows the element VoiceOver is told is focused is the table, and a
move along a row is announced.** Measured first on the guest (macOS 26.6.2) against a native
`NSTableView` of the gallery table's four columns, driven through the table script's row steps
(`scripts/a11y/macos/table-steps-probe.swift`): NSApp's focused element is the table at every step;
VoiceOver reads the whole row when the selection moves, says "Nenhuma linha selecionada" when it
empties and the count at a select-all, and writes nothing. Limn told VoiceOver that the cell under the
cursor was focused, as the rows above decided, and VoiceOver was silent at the deselection, at the
jump to the last row and at select-all, and wrote `AXFocused` on the table at each of those steps
(`readings/list-multi-macos`, `final-table-1`). The bridge now reports the table wherever the
effective focus is a data cell — a cell with a row, or the widget in one — of a table whose selection
members are rows: `accessibilityFocusedUIElement`, `isAccessibilityFocused` and the focus change all
name the table, and the cell keeps its own element for VoiceOver's cursor to reach. A native table's
cursor is a row and has no column; to keep Limn's, a move to another cell of the same row, which
changes no selection and so no focus, is said as one announcement carrying the cell's name, a
toggle's state in the catalogue's word (`StateNames#ofToggle`) and its column's header. So is the cell
the keyboard lands on when it comes back from the table's own header: the focus then climbs onto an
element that already holds VoiceOver's cursor, and VoiceOver says nothing of that move. The
announcement is assertive, because it answers the key just pressed and VoiceOver spoke only the first
of the polite ones it was posted as (`hyb-table-1` against `hyb-table-2`). A header cell keeps the
focus, and so does a calendar day, whose grid selects cells and whose cursor is apart from the
selection. Heard the same day (`hyb-table-3`): all thirteen steps of the script are spoken —
"Caucasus, Cordilheira" back from the header, "Europe, Continente", "5.642, Cume", "Visitada,
desativado", "Nenhuma linha selecionada", the rows in full, "5 linhas selecionadas" — and the one
client write left is `AXFocused` on the table after the sort, the element already focused. macOS only:
NVDA and Orca follow the cell, and nothing measured on Windows or Linux asks otherwise. Pinned by
`AxTableSceneTest`.

### 2.3 Linux: AT-SPI2

We own a socket and a reader thread; there is no vtable and no callback. The bridge serves D-Bus
objects at `/org/a11y/atspi/accessible/<id>`, plus `…/root` and `…/cache`.

| Interface / member | Answered from | Note |
| --- | --- | --- |
| `Accessible` `Name`, `Description`, `Parent`, `ChildCount`, `Locale`, `AccessibleId`, `HelpText` | name, description, links, locale, id | `Locale` is why the node carries its own resolved locale rather than the window's |
| `Accessible.GetChildAtIndex`, `GetChildren`, `GetIndexInParent` | the snapshot links | |
| `Accessible.GetRole`, `GetRoleName`, `GetLocalizedRoleName` | role | numbers from the typelib; the localized name resolved under the node's locale |
| `Accessible.GetState` | states, as **`au` of exactly two `uint32`, low word first** | measured: a real GTK application answers `au 2 0 0` |
| `Accessible.GetAttributes` | `a{ss}` including `toolkit` | |
| `Accessible.GetInterfaces` | **the facet set**, as interface names | the mirror image of UIA's `GetPatternProvider`, and the second reason facets exist |
| `Accessible.GetRelationSet` | relations, as `a(ua(so))` | `LABELLED_BY`, `LABEL_FOR`, `DESCRIBED_BY`, `CONTROLLER_FOR`, `CONTROLLED_BY`, `MEMBER_OF`, `POPUP_FOR` |
| `Application` `ToolkitName`, `Version`, `AtspiVersion`, writable `Id`, `GetLocale` | fixed, plus the registry's write | measured: the registry sets `Id` immediately after `Embed`, so `Properties.Set` must already work |
| `Component.GetExtents`, `GetPosition`, `GetSize` | bounds converted, per `coord_type` | `COORD_TYPE_SCREEN` = 0, `COORD_TYPE_WINDOW` = 1; on Wayland the screen answer is the window answer, as GTK's is |
| `Component.Contains`, `GetAccessibleAtPoint` | a bounds walk over the snapshot | again not `Widget#hitTest`, for the disabled-node reason |
| `Component.GetLayer`, `GetMDIZOrder`, `GetAlpha` | `LAYER_WIDGET` / `LAYER_WINDOW`, 0, 1.0 | |
| `Component.GrabFocus` | posted `FOCUS` | |
| `Action.NActions`, `GetActions`, `GetName`, `GetDescription`, `GetLocalizedName`, `GetKeyBinding`, `DoAction` | `AccessibleNode#accepts` (the `ActionFacet`'s parameterless verbs) | `GetActions` is `a(sss)`; the third column is the key binding, where `Accelerator#display()` goes. *(Amended 2026-09-15, semantics 5 settled after phase 3: the listing and `DoAction` both ask `accepts`, as every other entry point on this bridge does. They read the facet directly until then — the same answer, and the one call here that did not read the toolkit's single authority, so a gate reaching `accepts` would have left `DoAction` on the old rule. Pinned on the source by `AtspiTreeTest.everyVerbThisBridgePostsIsPostedAtOnePlaceBehindAccepts`, because behaviour cannot tell the two apart while they agree.)* |
| `Value` `CurrentValue` (read/write), `MinimumValue`, `MaximumValue`, `MinimumIncrement` | `ValueFacet` | numeric only; a display form such as a spinner's `07:30` is published through `Text` |
| `Text`, `EditableText` | `TextFacet` | **offsets converted from UTF-16 to characters at this boundary and nowhere else**; `GetRangeExtents` is not answered in the first cut (§11) |
| `Selection` | `SelectionFacet` | |
| `Table` (`NRows`, `NColumns`, `GetAccessibleAt`, `GetColumnHeader`, `GetSelectedRows`, `GetRowAtIndex`, `GetColumnAtIndex`) | `TableFacet`; ADR 041 §7 — and, for **which rows are selected**, the selection container rule (semantics 1) | `GetAccessibleAt` answers a realized cell and the null object for a row the walk did not publish, the degradation §4.1 already accepts. *(Amended 2026-09-15, semantics 1's last divergence, ratified after the phase-3 fix round: `NSelectedRows`, `GetSelectedRows`, `IsRowSelected`, `IsSelected` and `GetRowColumnExtentsAtIndex` read the members the publish resolved to this table — `AccessibleNode#selectionContainer`, the one fact a snapshot carries about where a member hangs — and no longer the `SELECTED` bit of the table's direct `ROW` children. A row a widget draws under a body of its own is a member and was reported as unselected; a selected direct child that declares no container was reported as a selected row. `AddRowSelection`/`RemoveRowSelection` fall back to the same members, so a write names the row a read reports. Cell lookup is unchanged: semantics 2 still searches the table's row children, and `IsSelected`'s cell half still reads the located cell's own bit.)* |
| `TableCell` (`Position`, `RowColumnSpan`, `Table`, `ColumnHeaderCells`, `RowHeaderCells`) | `CellFacet`; ADR 041 §7 | the table is the nearest ancestor with a `TableFacet`; the column header cell is its header group's child at the cell's column |
| `Cache.GetItems` | the whole snapshot, **pre-marshalled** | measured: 46 round trips for two objects without it |
| `Cache.AddAccessible`, `RemoveAccessible` signals | `STRUCTURE_CHANGED`, `NODE_DESTROYED` | |
| `Event.Object` `StateChanged`, `ChildrenChanged`, `PropertyChange`, `TextChanged`, `TextCaretMoved`, `TextSelectionChanged`, `SelectionChanged`, `ActiveDescendantChanged`, `BoundsChanged`, `Announcement`; `Event.Window` `Activate`, `Deactivate`, `Create`, `Destroy`; `Event.Focus` `Focus` | the event flush | `Event.Focus.Focus` is deprecated and still what Orca listens for, so it is emitted beside the `StateChanged` |
| `Socket.Embed` on the a11y bus; `org.a11y.Status.IsEnabled` on the session bus | attach, and the listening gate | both proven |
| `org.freedesktop.DBus.Introspectable.Introspect` on every intermediate node | synthesised from the exported paths | not needed by `libatspi` or Orca, and needed by `busctl tree`; a bridge that cannot be browsed is much harder to debug |

**AT-SPI2 has exactly one application object per connection, and that decides the shape of the Linux
bridge.** Windows are children of the application, not applications of their own. Two windows each
doing their own `Socket.Embed` would put Limn on the desktop twice, as two applications named by
window title, and two objects at `/org/a11y/atspi/accessible/root` on one connection is a path
collision. Orca would treat a combo popup as a separate application. So the Linux bridge is
**process-wide behind per-window facades**: one connection, one reader thread, one application node,
one `frame` child per window, and per-window `accessibility()` returns a facade that registers that
window's subtree with it. It lives in `limn-backend-lwjgl` beside `LwjglBackend`, which already keeps
the window list, so no SPI member has to be invented to enumerate windows. Windows and macOS bridges
stay per window. This asymmetry is real, and it is the one place the three implementations do not have
the same shape.

**Process-wide is not the same as stateless, and §3.4 assigns every piece of it a thread.** Being
one connection with one application object means there is process-wide mutable state even though
there is no per-node registry: the table of window facades — the application's `frame` children,
written by the UI thread as scenes bind and detach and read by the reader thread on every `root`
query — each facade's own `volatile` published tree, the listening flag the reader thread writes, the
outbound queue the UI thread and the reader thread both feed, and the marshalled cache body below.
The rule that keeps it honest is the one §3.3 states for the sockets, applied to memory: **the reader
thread and the UI thread never mutate the same field**, and everything they share is either an
immutable value published through a `volatile` write or a concurrent queue. Node ids are process-wide
by §1.3, minted from one counter, so one application object can carry every window's subtree with no
possibility of a path collision between them — which is what makes this shape available at all.

The pre-marshalled `Cache.GetItems` body is the bridge's, not the toolkit's: `AccessibleTree` knows
nothing about D-Bus. The bridge keeps one marshalled buffer keyed on a process-wide publish counter
and rebuilds it lazily on the first `GetItems` after any window publishes. **The reader thread is the
only thread that touches that buffer** — it is the only thread that answers `GetItems` — so it needs
no lock; the counter is a `volatile long` the UI thread bumps.

Two rules from Finding 3 that are not negotiable in handler code. A handler must not block, and must
never make a blocking call on the same connection. And `NEGOTIATE_UNIX_FD` succeeds on both buses and
must **not** be sent: agreeing lets a peer send a message carrying a file descriptor, which
`java.nio` cannot receive, and AT-SPI2 never needs one.

#### Amendment 2026-09-15 — the one application is built, and it is named by the backend

**What was wrong.** The two paragraphs above described a shape the code did not have (LINUX-NEW-8).
Every native window opened its own a11y connection, did its own `Socket.Embed` and named its
application after its own title, so a DatePicker's calendar or a ComboBox's list — a native popup
window, titled "popup" by `WindowConfig.popup` — was a second application on the desktop called
"popup", and the `POPUP_FOR` its root carries named a field that `AtspiTree.relationSetOf` skipped
because another connection held it. Ids were already process-wide (§1.3, amended 2026-09-14).

**What the code does now.** `AtspiApplication` is the process's one application: one connection,
one application object at `…/root`, and one `frame` child per window **that has published a node
zero**, in the order the windows joined. Each window's `AtspiBridge` is the facade: its publish
stores its own `volatile` tree and tells the application; its detach removes it. The window table
is copy-on-write, written by the UI thread and iterated by the reader thread, as §3.4 prescribed.
Every path resolves through the window whose tree holds the id (`AccessibleTree#holds`, one tag
comparison per window), so `GetChildren`, `GetIndexInParent`, `Parent`, `Cache.GetItems`, `DoAction`
(performed by the host of the window that published the node) and a relation into another window
all answer across windows. The bus is still joined only once some window has a tree (the 2.60 trap
of §12.2); the frames present at the join are what the registry reads, and a frame that arrives or
leaves afterwards is announced from the application object as `ChildrenChanged` `add`/`remove`
with its index in `detail1` and its `(so)` as the value — the shape libatspi 2.60.6's
`cache_process_children_changed` updates a cached child list from (readings, 2026-09-13). The
connection is let go with the last window, so a later window registers with a tree again.

**Named by the backend (decision 56).** `Backend#setApplicationName(String)` names the application;
unset, `LwjglBackend` uses the title of the first window it created. Every window's bridge is opened
with that name and not with its own title. Windows and macOS read nothing from it.

**Not changed here, and whose it is.** `Cache.GetItems` is still built per request rather than
pre-marshalled against a publish counter; `Cache.AddAccessible`/`RemoveAccessible` for a frame and
`Event.Window` `Create`/`Destroy` are the events item of the Linux lane (LINUX-NEW-1, LINUX-NEW-2).
When and on which thread the join happens is §3.3's amendment of the same date.

#### Amendment 2026-09-15 — the descriptor rule is kept, and a message the reader cannot read no longer silences it

**What was wrong (LINUX-NEW-13).** The rule above was written and not followed: `DBus.Conn.auth`
sent `NEGOTIATE_UNIX_FD` "only to see the answer", and both buses agreed. Separately, the reader loop
guarded only the socket read, so a message whose body did not parse — a `h` in its signature, the
very type the agreement lets a peer send, or any header shape the parser did not expect — threw out
of the loop and ended `limn-a11y-dbus-reader` for good, while the application stayed embedded and
went on emitting signals nobody could answer a question about: the state at-spi2-core 2.60 hides
from the desktop.

**What the code does now.** The handshake is `AUTH EXTERNAL`, then `BEGIN`, and nothing between
(`DBus.Conn.saslCommands`); `h` is neither read nor written. The reader frames a message by its
lengths before parsing it, so an unparsable one leaves the stream at the next message: it is logged,
a method call among them that expects a reply and whose header could be read is answered
`org.freedesktop.DBus.Error.InvalidArgs`, and the loop goes on. Lengths that cannot be a message
(past the specification's 2^27 bytes) end the connection, since nothing after them can be found.
Whenever the reader or the writer stops without the connection having been closed, the connection
closes itself and tells its owner once (`DBus.Conn.onLost`); the application lets that join go and
asks every window for a publish, which joins again under the back-off of §3.3's amendment.

#### Amendment 2026-09-15 — the event rows of this table

Two rows above said more than the code sent, and the events item of the Linux lane changed what they
describe; §2.4's amendment of this date carries the shapes and their readings. **`Cache.AddAccessible`,
`RemoveAccessible`** are sent from the tail's `STRUCTURE_CHANGED`, per child, after the parent's
`ChildrenChanged` (`AddAccessible` for an arrival, `RemoveAccessible` for a child that left the tree);
`NODE_DESTROYED` sends `StateChanged defunct` from the node's own path instead. **The `Event.Object`
row's note is false**: `Event.Focus.Focus` is not emitted beside `StateChanged`, and Orca 50.2 does not
listen for it (its `Script.get_listeners` registers no `focus:` event). `Announcement` is now sent;
until this date it was not.

#### Amendment 2026-09-15 — `Table` and `TableCell` find a cell by its facet, and a header by its row

**What was wrong (LINUX-NEW-10, LINUX-NEW-11).** The `Table` row's "`GetAccessibleAt` answers a
realized cell" held only for a table whose rows carry a `SelectionItemFacet`: a row was matched by
its position in set, which a calendar's week rows do not publish, so every cell of every calendar
answered the null object. The `TableCell` row's "its header group's child at the cell's column" was
the table's first group child's child at that index, so a table with its header hidden and a footer
shown named the footer's totals as its column headers, and a partial footer the wrong column's.
`AddRowSelection` posted `SELECT` alone and `RemoveRowSelection` refused everything.

**What the bridge does now (semantics 2, 3 and 5 of the 2026-09-13 pass).** Cell (r, c) is the node
whose `CellFacet` is (r, c) and whose nearest `TableFacet` ancestor is the table, searched under the
table's `ROW` children (where a widget cell hangs under its synthetic row); a row's index is its cells'
`CellFacet` row, and `IsSelected`/`GetRowColumnExtentsAtIndex` also count a selected cell. The header
of column c is the child with `CellFacet(-1, c)` of one of the table's direct `GROUP` children; a
footer cell (row −2) is never one, and none means none. `AddRowSelection` posts the first of
[`ADD_TO_SELECTION`, `SELECT`] the row accepts and `RemoveRowSelection` [`DESELECT`], through
`AccessibleNode#accepts`, and answer false otherwise. `GetRowColumnSpan` stays `iiii`: libatspi 2.60.6
reads `=>iiii` although the ATK bridge's XML declares `biiii`
(readings/upstream-at-spi2-core-2.60.6-libatspi-interfaces.txt, readings/fedora-dbus-TableCell.xml).

#### Amendment 2026-09-15 — `Selection` is served, with the two indices its XML names

**What was wrong (L2).** The `Selection` row promised an interface no code defined: no container
listed it, `Properties.Get` refused `NSelectedChildren`, and every method answered `UnknownMethod`, so
libatspi's `get_n_selected_children` read −1 in every snapshot of the 2026-09-13 tree reader.

**What the bridge does now (semantics 1 and 5; settled atspi-selection-membership).** A node with a
`SelectionFacet` lists `org.a11y.atspi.Selection` in `GetInterfaces` and in its cache item. The
installed XML names two different indices (readings/fedora-dbus-Selection.xml), and each is kept:
`NSelectedChildren` (a property, `i`), `GetSelectedChild` and `DeselectSelectedChild` count the
container's **selected members** — the realized nodes the publish resolved to it
(`AccessibleNode#selectionContainer`), in reading order, wherever they hang, so a calendar's selected
day is found under its week row — while `SelectChild`, `IsChildSelected` and `DeselectChild` name the
container's **literal child** at that index, which may be a tree's scroll bar or a grid's week row and
is then selected by nothing. `SelectChild` posts the first of [`ADD_TO_SELECTION`, `SELECT`] the child
accepts, `DeselectChild`/`DeselectSelectedChild` [`DESELECT`]; `SelectAll` and `ClearSelection` answer
false, the model having no verb for either. A member scrolled away has no node and is not counted.
Orca 50.2 calls `get_n_selected_children` and `get_selected_child` only
(readings/fedora-orca-interface-calls.txt, Fedora KDE 44, 2026-09-15). The XML's `version` property is
not answered.

#### Amendment 2026-09-15 — `Value` is served, with its text, and a write is refused where the node refuses it

**What was wrong (LINUX-NEW-4, DATES-NEW-5).** The `Value` row promised an interface no code served:
a date segment, a spinner, a slider or a progress bar had a name and a role on Linux and no number.

**What the bridge does now (settled linux-value-text; decision 16; semantics 5).** A node with a
`ValueFacet` lists `org.a11y.atspi.Value`. `MinimumValue`, `MaximumValue`, `MinimumIncrement` and
`CurrentValue` are doubles and `Text` is the facet's display form or the empty string, as libatspi
2.60.6 reads them (readings/upstream-at-spi2-core-2.60.6-libatspi-interfaces.txt) and as the guest's
XML declares them (readings/fedora-dbus-Value.xml) — the row's "numeric only" is superseded, since the
installed interface carries a `Text` property and Orca 50.2 reads it with the number
(`ax_value.py`, readings/fedora-orca-interface-calls.txt). An empty value answers its minimum as
`CurrentValue`, the one place a number is mandatory, and its word through `Text`. `Properties.Set` of
`CurrentValue` — how libatspi writes one — posts `SET_VALUE` with the number where
`AccessibleNode#accepts` allows it (a writable facet on an `ENABLED` node) and is otherwise answered
`org.freedesktop.DBus.Error.Failed`, which reaches the caller's `GError`; so is a write to any other
`Value` property. A display form is also served through `Text` (its own amendment below).

**Read 2026-09-15 (review of the interfaces item): what a toolkit answers a refused write.** Neither
toolkit on the Fedora KDE 44 guest refuses a `CurrentValue` write with an error: GTK 3.24.52's ATK bridge
answers success on an insensitive spin button and on a level bar and moves both, and GTK 4.22.4 answers
success on both and leaves the level bar's number where it was
(readings/fedora-gtk3-interface-replies.txt and fedora-gtk4-interface-replies.txt, section 5,
`scripts/a11y/linux/read-gtk-interface-replies.py`). Answering `Failed` where `AccessibleNode#accepts` refuses stays this bridge's choice, made
knowingly against both: a success a widget then ignores is what a caller cannot detect. A write to a
read-only `Value` property is now answered `org.freedesktop.DBus.Error.PropertyReadOnly`, as the ATK
bridge answers it (GTK 4 answers `InvalidArgs`), and a name `Value` does not have, or a `CurrentValue`
that is not a number, `InvalidArgs`, as `Get` answers an unknown name; the error name `Failed` itself is
from readings/fedora-dbus-bus-facts.txt.

#### Amendment 2026-09-15 — `Text` is served, over a text and over a value's display form

**What was wrong (LINUX-NEW-4).** The `Text` row promised an interface no code served, while the
bridge already sent `TextChanged`, `TextCaretMoved` and `TextSelectionChanged` from nodes that
answered none of the questions those events invite (§2.4's "an event is half a conversation").

**What the bridge does now (settled linux-value-text).** `org.a11y.atspi.Text` is listed for a node with
a `TextFacet` and for a node with a `ValueFacet` whose display form is not empty (a date segment's
"15" or "empty", a spinner's "07:30"), which is read-only text with no caret and no selection; a
value whose number is the whole of it serves none. Every offset is a character, converted from the
model's UTF-16 units in `AtspiText` and nowhere else. Answered: `CharacterCount` and `CaretOffset`
(properties, `i`), `GetText` (−1 as the end), `GetCharacterAtOffset`, `GetStringAtOffset`,
`GetTextAtOffset`/`-BeforeOffset`/`-AfterOffset`, `GetNSelections`, `GetSelection`, attributes as
none over the whole text (`GetAttributeRun` is `a{ss}ii`, the shape libatspi 2.60.6 checks), and the
writes `SetCaretOffset` (`SET_CARET`), `AddSelection`/`SetSelection`/`RemoveSelection`
(`SET_SELECTION`, the model holding one selection) through `AccessibleNode#accepts`, offsets back in
units. **Boundaries:** words and sentences are `BreakIterator`'s under the node's locale; a line and a
paragraph are what a line feed delimits, because no facet carries soft wraps, so a wrapped line of a
text area reads as its paragraph; each boundary type has the shape its AT-SPI name gives
(`WORD_START` from a word's start to the next word's, `LINE_START` through the line feed, the
`_END` types from one end to the next), and a granularity is answered as libatspi 2.60.6's own
fallback reads it (WORD as `WORD_START`, SENTENCE as `SENTENCE_START`, LINE as `LINE_START`;
PARAGRAPH as `LINE_START` here) (readings/upstream-at-spi2-core-2.60.6-libatspi-interfaces.txt;
the enumerators from readings/fedora-atspi-constants-all.txt, Fedora KDE 44, 2026-09-13). **Not
answered, as §11 decided:** `GetCharacterExtents`, `GetRangeExtents`, `GetOffsetAtPoint` and
`GetBoundedRanges` are declined and `ScrollSubstringTo(Point)` answers false; Orca 50.2 calls the
first three for flat review and mouse review (readings/fedora-orca-interface-calls.txt), which stay
degraded.

**Read 2026-09-15 (review of the interfaces item): PARAGRAPH.** Answering PARAGRAPH as `LINE_START`
was a choice: libatspi 2.60.6's fallback maps it to no boundary. On the Fedora KDE 44 guest, over a
text view holding "one two. three four.\nfive six.\n\nseven", GTK 4.22.4 answers PARAGRAPH (and LINE
and SENTENCE) with what a line feed delimits, the line feed left out ("five six.", 21, 30), and GTK
3.24.52's ATK bridge answers PARAGRAPH `('', -1, -1)` at every offset while its LINE keeps the line feed
("five six.\n", 21, 31) (readings/fedora-gtk4-interface-replies.txt and
fedora-gtk3-interface-replies.txt, section 4, `scripts/a11y/linux/read-gtk-interface-replies.py`). The
bridge keeps PARAGRAPH as what a line feed delimits — GTK 4's unit, and a range where Orca 50.2 asks for one (`ax_text.py` calls PARAGRAPH,
readings/fedora-orca-interface-calls.txt) —
in the `LINE_START` shape its LINE already has, the line feed included as the ATK bridge includes it.
Pinned by `AtspiTreeTest.aTextIsReadInCharactersByOffsetGranularityAndBoundary`.

**`EditableText` (the same date).** Listed for a text published `EDITABLE` — a field that is only
disabled keeps both, as §1.2 requires. Every write is one `SET_TEXT` of the whole new string, built
from the published text in characters, through `AccessibleNode#accepts`: `SetTextContents` replaces it,
`InsertText` inserts the first `length` characters of what it is given (all of it when `length` is
negative or not less than its length, so a client counting UTF-8 bytes still inserts the whole string),
`DeleteText` removes a character range. On a `PASSWORD` node, whose facet holds the mask, only
`SetTextContents` is taken. `CutText` and `PasteText` answer false and `CopyText` does nothing: the
model has no clipboard verb. Orca 50.2 calls none of these (readings/fedora-orca-interface-calls.txt);
the signatures are libatspi 2.60.6's (`s=>b`, `isi=>b`, `ii=>b`, `i=>b`, `CopyText` `ii` with no reply
value).

**Corrected 2026-09-15 (review of the interfaces item): `InsertText`'s length is bytes, as read.** The
paragraph above took `length` as characters, a choice and not a reading, which misread a byte count that
covers part of a multibyte string (a length of 2 for "é😀x" inserted "é😀"). Read on the Fedora KDE 44
guest: GTK 3.24.52's ATK bridge counts UTF-8 bytes — `InsertText(1, "é😀x", n)` into "ab" leaves "ab"
for 1, "aéb" for 2 and 3, "aé😀b" for 6, and all of it for 7, 100 and −1, a character the count would
cut being left out (readings/fedora-gtk3-interface-replies.txt) — while GTK 4.22.4 ignores the length and
inserts everything (readings/fedora-gtk4-interface-replies.txt;
`scripts/a11y/linux/read-gtk-interface-replies.py`, 2026-09-15). The bridge now inserts the whole
characters that fit in `length` bytes, all of them for a negative length (`AtspiTree.prefixInBytes`), the
ATK bridge's unit, whose XML it serves.

#### Amendment 2026-09-15 — `GetAttributes` carries a row's level and place in its set

**What was wrong (L5).** The `GetAttributes` row answered `toolkit` alone, while Orca 50.2 reads a
tree item's level from `level` and a member's "n of m" from `posinset` and `setsize` before any
fallback (readings/fedora-orca-tree-level-position.txt), so no Limn tree item had a level on Linux
and a virtualized list's rows were counted among the realized siblings.

**What the bridge does now (decision 4, semantics 6, settled linux-level-carrier).** Beside `toolkit`,
`level` is `HierarchyFacet.level` and `posinset`/`setsize` are `SelectionItemFacet`'s position and size,
each as a decimal string, one-based as the model counts them and as Orca reads them; each is published
only when non-zero, so no node says "0 of 0". GTK 4.22.4 publishes `posinset` and `setsize` on its list
rows the same way (readings/fedora-gtk4-column-sort.txt, Fedora KDE 44, 2026-09-15). No event announces
a change to them (decision 43): Orca 50.2's `object:attributes-changed` handler only clears its cache
(readings/fedora-orca-interface-calls.txt). A header's sort direction is not an attribute yet; see the
sort amendment below. *(Amended 2026-09-15, the fix round's integration: it is now — `sort` on the
sorted column's header cell, and on no other node. That amendment's last paragraph says how.)*

**Amendment 2026-09-16 (phase 5, decision 77): Orca reads all three and speaks only the level,
because it ships position-in-set off.** "As Orca reads them" above is true of the *read* and says
nothing about the *speech*, and the difference was measured on the Fedora 44 guest on 2026-09-16
(`readings/phase5-fedora/`, runs `l4-1` and `l5-posinset`). `level` is spoken with the setting
untouched — `'nível de árvore 1'` through `'nível de árvore 3'` over the gallery's tree. `posinset`
and `setsize` are **not**, although the node's own dump carries `posinset:1, level:1, setsize:5`:
Orca 50.2's `speak-position-in-set` setting defaults to `False`, so `_generate_position_in_list`
returns nothing. Flipped at runtime over Orca's own D-Bus service — in memory, with nothing written
to the owner's dconf, and verified — the run that repeats those steps says `'1 de 5'`, `'2 de 2'`
and `'2 de 5'`, and a calendar day says `'15 de 30'` (`rs-calendar`). Orca 46.1 on
Ubuntu 24.04 has no `org.gnome.Orca.Service` D-Bus module, so it cannot be flipped there at all and
was not. **Nothing in this bridge changes**: the attributes are right, published as this amendment
says, and what varies is a setting of another product. What changes is what may be *promised* — a
document that says a Linux user hears "n of m" is wrong unless it names the setting, which is
decision 77 and the guides lane's to carry.

#### Amendment 2026-09-15 — `GrabFocus`, `GetAccessibleAtPoint` and `Introspect` are answered

**What was wrong (LINUX-NEW-5; settled linux-adr-overstatements).** Three rows promised what nothing
handled: `Component.GrabFocus` and `GetAccessibleAtPoint` answered `UnknownMethod` — the latter is what
Orca 50.2's mouse review asks (`ax_component.py:124`, `WINDOW` coordinates;
readings/fedora-orca-interface-calls.txt) — and `Introspectable.Introspect` was handled on no path,
while `Atspi.node` and the XML blocks it assembles were referenced nowhere.

**What the bridge does now.** `GrabFocus` posts the first of [`FOCUS`] the node accepts (semantics 5:
every focusable widget publishes it as the walk's free verb, an item where decision 11 allows) and
answers false elsewhere. `GetAccessibleAtPoint` is the bounds walk the row names: the point is
converted once to the window's coordinates from the type asked (`SCREEN`, `WINDOW`, or `PARENT` — 2,
read 2026-09-13 off the Fedora typelib, readings/fedora-atspi-constants-all.txt — which
`GetExtents` and `Contains` now also answer, relative to the parent's box), children are tried last
first because a later sibling is drawn over an earlier one, only a `SHOWING` node is a hit, and the
deepest hit below the asked node is the answer, the null object when none; asked of the application
object, it answers the last-joined frame whose window holds the point. `Introspect` answers every path
this application exports: each intermediate path names its child down to `/org/a11y/atspi`,
`…/accessible` names the root and every node of every window, and a node's path lists exactly what
`GetInterfaces` answers, each with the XML a real toolkit's bridge declares — the blocks read off Ubuntu
24.04 for `Accessible`, `Application`, `Component`, `Action` and `Cache`, and those read off the Fedora
KDE 44 guest's at-spi2-atk 2.60.6 for `Selection`, `Value`, `Text`, `EditableText`, `Table` and
`TableCell` (readings/fedora-dbus-<Interface>.xml) — plus the three standard interfaces; a path that
names nothing is declined.

#### Amendment 2026-09-15 (review of the interfaces item) — `GetPosition` and `GetSize` are two out arguments

**What was wrong.** The `Component.GetExtents`, `GetPosition`, `GetSize` row was answered with one
shape for all three: `GetPosition` and `GetSize` replied a struct `(ii)`, on every node and on the
application object, since the bridge's first cut. libatspi 2.60.6 reads them as `u=>ii` and `=>ii`
(`atspi-component.c:196` and `:223`, readings/upstream-at-spi2-core-2.60.6-libatspi-interfaces.txt),
the installed XML declares two separate out arguments (readings/fedora-dbus-Component.xml), and a
struct where flat arguments are expected is refused, as `GetRowColumnSpan`'s was on the guest. The
GrabFocus amendment above amended the same rows and did not catch it.

**What the bridge does now.** Both answer `ii`, on a node and on the application object; `GetExtents`
stays `(iiii)`. GTK 3.24.52's ATK bridge (at-spi2-atk 2.60.6) and GTK 4.22.4 answer exactly these
signatures on the Fedora KDE 44 guest (readings/fedora-gtk3-interface-replies.txt and
fedora-gtk4-interface-replies.txt, `scripts/a11y/linux/read-gtk-interface-replies.py`, 2026-09-15
20:54–20:55 UTC). Both toolkits answer `UnknownMethod` for `Component` on their application object; this
bridge keeps answering it there, as the first cut decided (a client that asks the root for its extents
walks on).

#### Amendment 2026-09-15 (review of the interfaces item) — the `version` the served XML declares is answered

**What was wrong.** `Introspect` serves, for `Selection`, `Value`, `Text`, `EditableText`, `Table` and
`TableCell`, the XML read off the Fedora guest's ATK bridge, and each block declares a read-only
`version` property of type `u`; `Properties.Get` refused it as a property the object lacks
(`InvalidArgs`), and the Selection amendment above recorded it as not answered for want of a reading.

**What was read, and what the bridge does now.** GTK 3.24.52's ATK bridge (at-spi2-atk 2.60.6) answers
`version` with `u` 1 on every interface it serves; GTK 4.22.4, which declares no such property, answers
`InvalidArgs` (readings/fedora-gtk3-interface-replies.txt and fedora-gtk4-interface-replies.txt, section
2, `scripts/a11y/linux/read-gtk-interface-replies.py`, Fedora KDE 44, 2026-09-15); at-spi2-core 2.60.6's
`atspi-constants.h` defines every `ATSPI_*_VERSION` as 1. The bridge answers `u` 1
(`Atspi.INTERFACE_VERSION`) in `Get` and `GetAll` for those six interfaces where the node serves them,
the XML and the answer now coming from the same bridge. The blocks read off Ubuntu 24.04 (`Accessible`,
`Application`, `Component`, `Action`) declare no `version` and none is answered there.

#### Amendment 2026-09-15 — a header's sort direction: read on Fedora, and not carried yet

**What decision 36 asked.** A sortable header cell publishes its direction, and how each platform
carries one is read on the guest before any code.

**What was read.** A GTK 4 `Gtk.ColumnView` sorted ascending, descending, by another column and by
nothing on the Fedora KDE 44 guest (gtk4 4.22.4, at-spi2-core 2.60.6) carries **no** sort direction:
each title is a `filler` whose name is the column's, with `{toolkit: GTK}` alone as attributes, the same
states in every step, and no event naming the header (readings/fedora-gtk4-column-sort.txt,
`scripts/a11y/linux/read-gtk4-column-sort.py`, 2026-09-15). Orca 50.2 reads a table header's direction
from the object attribute **`sort`** — `ascending` → "sorted ascending", `descending` → "sorted
descending", `none` or absent → nothing, any other value → "sorted" (`ax_utilities_table.py:259-275`,
readings/fedora-orca-interface-calls.txt). So the carrier on this platform is that attribute, as ADR 041
§7's amendment of 2026-09-14 anticipated; no native toolkit on the guest was found sending it.

**Why nothing is mapped yet.** The model has no fact to map: `Table` carries the direction as the sorted
header's localized description (`TableStrings.SORTED_ASCENDING`/`SORTED_DESCENDING`, ADR 041 §7), and a
bridge cannot recover `ascending` from "Ordenado em ordem crescente". Carrying it needs a model
carrier — a facet or a state on the header cell — that the three bridges read alike, which is not this
bridge's to add alone while the Windows and macOS bridges are changed in parallel. Once it exists, this
bridge answers `sort` = `ascending`/`descending` on that header cell in `GetAttributes` (§2.3's
attributes amendment) and nothing when unsorted; until then a Linux reader hears the description.

**Amended 2026-09-15 (the phase-3 fix round's integration): the carrier exists, and `sort` is
answered.** The model fact the paragraph above waits on landed in the same round — `CellFacet.Sort`,
`NONE`/`ASCENDING`/`DESCENDING` on the header cell of the sorted column (§1.2's amendment of this
date, ADR 041 §7's) — but it landed on the model branch while the three bridge lanes were running,
so each logged its own mapping as owed rather than compiling against a type its branch did not have.
The mappings are the integration's, because none of them is a lane's judgement: each was specified
whole in its lane's log, against a reading that lane had already taken. Here, `AtspiTree.attributesOf`
publishes `sort` = `ascending` / `descending` on a cell in the header row whose direction is not
`NONE`. The key goes nowhere else: an unsorted header publishes no key rather than `sort=none` —
Orca reads the two the same and no GTK table on the guest publishes either — and a data cell or a
footer cell is not a header whatever its facet holds, which is the guard and not the model's
restraint, since `Table` sets a direction on the header alone. Orca's fourth value `other` is never
written, because `CellFacet.Sort` has no fourth value to write it from. The description stays where
it was, so a reader that ignores the attribute hears what it heard before. Pinned by
`AtspiTreeTest.theSortedColumnsHeaderSaysItsDirectionAndNoOtherCellSaysAnything`, which gives the
data cell and the footer cell a direction their facet has no business carrying. What a live Orca
does with the attribute is phase 5's. *(Not settled by phase 5, 2026-09-16. On Fedora 44 Orca 50.2
received the sorted header with `sort:ascending` among its attributes and, on the sort, spoke
'Ordenado em ordem crescente', which is the header's description in Limn's own pt-BR catalog; Orca
46.1 on Ubuntu 24.04 spoke the same phrase. Nothing in either run is Orca speaking from the
attribute itself, so what it does with the attribute alone is still open.)*

#### Amendment 2026-09-16 — a menu title will not publish the expand axis here, and that is a
declared exception

**Decided 2026-09-16 (decision 72); built 2026-09-17 under decision 82, and §4.2's Exception 1 is
the account of what was built — including the two divergences this amendment did not know about,
the role and the missing nesting of a cascade under its title.** §1.2's rule is one rule for every node — every node carrying an `ExpandFacet` is
`EXPANDABLE`, menu titles included (decision 41) — and decision 41 was taken on the assumption that
a native menu publishes that axis. **On this desktop it does not.** Read on the Fedora 44 KDE guest
on 2026-09-16 (`readings/phase5-fedora/native-gtk3-menu-states.txt`, `menu-states.py`,
`gtk3-menu-window.py`): a native GTK 3 menu title carries **no `expandable`, no `expanded` and no
`collapsed`**, open or closed. What an open menu carries is `selected`, its children become
`showing`, and **every** menu node carries `selectable`. So the rule as written makes a Limn menu
title sound unlike every other menu on the desktop, to the reader that desktop ships.

**What is decided:** on Linux a menu title stops publishing the expand axis over AT-SPI and uses
`selected` plus the children's `showing` instead. **Windows and macOS keep `EXPANDABLE`**, where the
ExpandCollapse pattern and the disclosure attribute are what a native menu really does carry, and
the model keeps publishing the state for every node with the facet, as §1.2 says. So the translation
diverges on one platform, deliberately, and this is the first of the two declared exceptions
**§4.2** lists together. **What is not decided here** is which nodes count as "a menu title": the
reading covers a menu bar's titles and their submenu rows on GTK 3, and a combo box, a tree row and
the calendar's title are not menus and are untouched. That is the Linux lane's to settle when it
builds this, against the same reading.

**Settled on building it, 2026-09-17: by the role.** `AtspiRoles.isMenuRow` answers for
`MENU_ITEM`, `CHECK_MENU_ITEM` and `RADIO_MENU_ITEM`, which is exactly the menu bar's titles and a
cascade's submenu rows, and nothing else — a bridge reads nodes, not widgets, and a combo box, a
tree row and the calendar's title carry none of those roles, so they keep the axis with no test of
their own. A menu row **without** a submenu carries no expand facet to begin with, so the predicate
changes nothing for it. What the exception covers and what it leaves measured-but-open — the action
names, the role itself, and a cascade that is not nested under its title here — is §4.2's Exception 1.

#### Amendment 2026-09-22 (decision 109, P5U-1) — a window whose first publish beats its join says its startup again

**What was wrong.** The join is requested by the first publish that has a tree and lands on a
thread of its own (§3.3's amendment of 2026-09-15), so it can never precede that publish, and every
event of that publish — the frame's `active`, its `Activate`, the `focused` of the control the user
starts in — was dropped as every event before the join is. Nothing re-sent them: a later publish
emits only what moved, and those bits had not. Measured on the Ubuntu guest on 2026-09-16, the
announcement scene lost the whole burst six times in six: Orca said the first announcement, "Salvo",
before it had ever named the window, named "Limn accessibility gallery" only off the Tab that
followed, and never named the "Salvar" button that held the focus
(`readings/phase5-ubuntu/…-u-rs-ann-1`, and again on 2026-09-22 with the phase-10 jar as the
control, `readings/p5u1-ubuntu/ann-p10-1`).

**What the code does now.** An event dropped for want of a join marks its window
(`AtspiBridge.startupOwed`, set in `emit`'s not-joined branch); the first frame after the joined
state is visible — `frameEnded`, or `publishing` if a publish comes first — says the window's
startup again from the tree as it stands: the frame's `active` 1, then `Activate`, whose reconcile
(§2.3 above, `afterTheLocusMoved`) says the focus and the cursor after it, in the order a window that
activates later sends them (decision 28). Once, and only for a frame that holds `ACTIVE`: an
inactive frame owes nothing, its focus being where the user would return to. A join that lands
before the publish's own events go out — the other side of the race — drops nothing, marks nothing
and says nothing twice; `AtspiApplicationTest` pins both sides with a deferred join.

**Heard.** Ubuntu 24.04, Orca 46.1, 2026-09-22, the announcement scene with the fixed jar against
the phase-10 jar in the same Orca session: "Limn accessibility gallery frame." and "Salvar push
button." spoken as the window appears and before any key, then "Salvo" at the first step; with the
control jar, "Salvo" first and the window's name only at the Tab (`readings/p5u1-ubuntu/`).

### 2.4 The events, side by side

| Event | Windows | macOS | Linux |
| --- | --- | --- | --- |
| `FOCUS_CHANGED` | `AutomationFocusChanged`, plus a `HasKeyboardFocus` property change on both | `FocusedUIElementChanged`, **posted at application level** — measured: an observer registered on the element does not receive it | `StateChanged` detail `focused` 1 then 0, plus the deprecated `Event.Focus.Focus` |
| `ACTIVE_DESCENDANT_CHANGED` | `SelectionItem_ElementSelected` on the item | `SelectedChildrenChanged` | `ActiveDescendantChanged` |
| `STRUCTURE_CHANGED` | `UiaRaiseStructureChangedEvent` with `ChildAdded` / `ChildRemoved` / bulk / `ChildrenInvalidated` | `Created`, `UIElementDestroyed`, `LayoutChanged` | `Cache.AddAccessible` / `RemoveAccessible` plus `ChildrenChanged` detail `add` / `remove`, detail1 = index |
| `NAME_CHANGED`, `DESCRIPTION_CHANGED`, `STATE_CHANGED`, `VALUE_CHANGED` | `UiaRaiseAutomationPropertyChangedEvent` with the property id and both variants | `TitleChanged`, `ValueChanged` | `PropertyChange` detail `accessible-name` / `-description` / `-value`, or `StateChanged` |
| `SELECTION_CHANGED` | `SelectionItem_ElementSelected`, or `Selection_Invalidated` in bulk | `SelectedChildrenChanged` | `SelectionChanged` |
| `TEXT_CHANGED` | `Text_TextChanged` | `ValueChanged` on the text element | `TextChanged` detail `insert` / `delete`, detail1 = offset, detail2 = length, any_data = the text |
| `CARET_MOVED` | `Text_TextSelectionChanged` | `SelectedTextChanged` | `TextCaretMoved`, detail1 = offset |
| `TEXT_SELECTION_CHANGED` | `Text_TextSelectionChanged` | `SelectedTextChanged` | `TextSelectionChanged` |
| `INVOKED` | `Invoke_Invoked` | — (the action's return is the acknowledgement) | — (`DoAction`'s boolean is) |
| `BOUNDS_CHANGED` | `BoundingRectangle` property change, or one `LayoutInvalidated` in bulk | `Moved` / `Resized` on a window, `LayoutChanged` in bulk | `BoundsChanged` |
| `WINDOW_OPENED` / `WINDOW_CLOSED` | `Window_WindowOpened` / `Window_WindowClosed` | `WindowCreated` / `UIElementDestroyed` | `Event.Window` `Create` / `Destroy` |
| `WINDOW_ACTIVATED` / `WINDOW_DEACTIVATED` | focus change into the window | `MainWindowChanged`, `FocusedWindowChanged` | `Event.Window` `Activate` / `Deactivate` |
| `NODE_DESTROYED` | element answers `UIA_E_ELEMENTNOTAVAILABLE` | **release, and post nothing** — AppKit posts `UIElementDestroyed` on its own (§13.20) | `Cache.RemoveAccessible` |
| `ANNOUNCEMENT` | `UiaRaiseNotificationEvent` | `AnnouncementRequested` with a priority | `Event.Object` `Announcement` |

The two blanks are the model being honest: nothing is invented to fill a cell.

**Amended 2026-09-14 (the rows, read against §1.10's amendments of the same day; the platform
columns are the bridge lanes' to change in phase 3).** `ACTIVE_DESCENDANT_CHANGED` is raised on
the focused node only, once per publish, carrying the previous and current cursor; Windows moves
its focus to the cursor (`AutomationFocusChanged` on the item, `HasKeyboardFocus` true there)
and macOS answers the cursor as its focused element, Linux keeps the row as written.
`STRUCTURE_CHANGED` is one per surviving parent, carrying the added, removed and reordered
children with their indices, which is what the Windows `ChildAdded`/`ChildRemoved`/
`ChildrenReordered` and the Linux `ChildrenChanged` `detail1` index are built from.
`SELECTION_CHANGED` is one per container, carrying the members that entered and left and the
container's multi flag: Windows raises `ElementSelected` for a single-select container and
`ElementAddedToSelection`/`ElementRemovedFromSelection` on the members of a multi-select one,
`Selection_Invalidated` for a bulk change; Linux and macOS address the container.
`WINDOW_ACTIVATED`/`WINDOW_DEACTIVATED` name the window node and arrive with the tree; Linux
sends `Activate`/`Deactivate` from the frame's path. `FOCUS_CHANGED` is also raised for a node
that arrived holding the focus. After a collapse to `INVALIDATED` the structure, focus, cursor,
selection and window-activation events of that publish still follow it.

**Amended 2026-09-15 (phase 3, Windows; W3, WINDOWS-NEW-4, LAB-NEW-12, WINDOWS-NEW-2, CRIT-3): the
focus rows as built.** `FOCUS_CHANGED` and `ACTIVE_DESCENDANT_CHANGED` both raise
`AutomationFocusChanged`, and on the same element: the tree's effective focus as the snapshot has
it when the drain raises, never the event's own node, so the raise and the `HasKeyboardFocus` a
reader then reads always agree. The element is **minted if no client holds it** — a row the cursor
has just reached, a dialog's first field — while every other event keeps being raised only for a
held element (§13.28's cost argument). A cursor in a native popup's tree is raised on the popup
window's element, by the popup's bridge, under a guard its whole-registry empty also takes. Nothing
is suppressed as a repeat: NVDA drops a duplicate focus event itself, and a bridge-local memory would
silence the return to an element after the focus had been in another window. The model's
`INVALIDATED` (node `0`) is swept like the bridge's own queue collapse, and after either the bridge
re-raises the focus on the effective focus; the root-targeted `INVALIDATED` the sweep raises is
`LayoutInvalidated` and sweeps nothing. The `HasKeyboardFocus` property change the `FOCUS_CHANGED`
row promises is **not** raised: NVDA 2024.4.2 subscribes to no `HasKeyboardFocus` change (the same
reading, §3), and the mapping of the remaining unmapped events is a later item of the Windows lane.
`SELECTION_CHANGED`, as built: more than `InvalidateLimit` members entering and leaving (20, the
managed provider API's own constant, read on the guest 2026-09-15, and the comparison the
platform's `SelectorAutomationPeer` makes with it: twenty is still per member) is one
`Selection_Invalidated` on the container; otherwise a single-select container raises
`ElementSelected` on the member that entered (or `ElementRemovedFromSelection` on the one that left
when none entered) and a multi-select one `ElementAddedToSelection`/`ElementRemovedFromSelection` on
each member. Each is raised only for an element a client holds. NVDA 2024.4.2 speaks none of these
for a generic item (the same reading, §2); the reader hears the cursor through the focus rows above.

**Amended again 2026-09-15 (review of those focus rows; semantics 4, WINDOWS-NEW-6): remembered,
and the rows' two promises kept.** Three sentences of the amendment above are withdrawn. *"Nothing is
suppressed as a repeat"*: semantics 4 has each bridge remember the last effective focus it announced,
and without it one publish raised `AutomationFocusChanged` on the same element two or three times (a
focus arriving on a table with a cursor is `FOCUS_CHANGED` and `ACTIVE_DESCENDANT_CHANGED`; a publish
past the model's budget is `INVALIDATED` followed by both), each raise waiting for the reader
(§13.28). The memory is now the process's — the bridge and node last announced, since UI Automation
has one focus and a raise in another window moves it — and a focus event naming it again is skipped,
while the re-announcement after a collapse or the model's `INVALIDATED` raises whatever it names. It
is forgotten when a window has nothing focused, when a window is deactivated, and when its bridge
empties, so the return to an element after the focus was elsewhere is heard, which was the objection
to a bridge-local memory. *"The `HasKeyboardFocus` property change … is **not** raised"*: it is, as
the `FOCUS_CHANGED` row says, "on both" — `false` on the element the focus left when a client holds it
and its node remains, `true` on the one it reached — and only when the announced element changed; NVDA
2024.4.2 subscribes to none (reading §3), so no reader behaviour depends on it today. *"The mapping of
the remaining unmapped events is a later item"*: `WINDOW_ACTIVATED` is now the row's "focus change into
the window", the window's effective focus raised subject to the memory, and `WINDOW_DEACTIVATED`
raises nothing and forgets the memory; neither pays the event an ask is owed unless something was
raised (`UiaBridgeTest.theFocusAlreadyAnnouncedIsNotRaisedAgainButIsReannouncedAfterTheModelsInvalidated`,
`aFocusMoveTellsTheElementItLeftAndTheOneItReachedThatTheKeyboardMoved`,
`aWindowActivatedAgainRaisesTheFocusItHadBecauseTheDeactivationForgotIt`,
`aFocusRaisedInAnotherWindowMakesTheReturnHeard`). A raise from another window holds that window's
guard across the platform call, so a client whose focus handler synchronously asked the host's
`GetFocus` would wait for it; NVDA 2024.4.2's handler asks no such thing (reading §1), and phase 5
watches for it. *(No phase-5 record names this wait, 2026-09-16 to 2026-09-22. The one stall seen
with NVDA attached, closing the native date picker on 2026-09-16, was traced on 2026-09-22 to the
popup's teardown freeing objects the platform still held (§2.1's amendment of that date), not to a
guard held across a raise; after that fix the native picker ran its eleven steps under NVDA with
focus raised into the popup and back. Still open as a named check.)*

**Amended 2026-09-15 (phase-3 fix round; semantics 4 settled to one shape): the re-announcement is
raised at the tail's place, after the tail's structure events.** The amendment above left it raised
the instant the sweep finished — "after either the bridge re-raises the focus on the effective
focus" — which is *before* the `STRUCTURE_CHANGED`s the model reserves outside its budget and sends
next. Decision 28 and semantics 7 put the tail in one order, children first and then focus, cursor
and selection, and a reader told where the user is and only then told that the tree under it changed
re-reads and asks again. So the sweep now leaves the re-announcement **owed**, and the drain raises
it before the first tail event that is not a `STRUCTURE_CHANGED`, or when nothing more is waiting —
which is the case a collapse that moved only the tree's shape leaves, and the reason the debt is
never simply dropped. Linux reconciles at the same place and macOS posts focus last in the frame;
this is the third bridge joining them, and the semantics' three readings (integration log, phase-3
critic, contradiction 1) are now one. **Two collapses with no tail between them owe one
re-announcement, not two**: what the raise pays for is the element the sweep may have released under
the reader, one raise after the last sweep says it, and each raise waits for the reader's handler
(§13.28). Pinned by
`UiaBridgeTest.theFocusIsReannouncedAfterTheTailsStructureEventsAndNotBeforeThem`;
`theModelsInvalidatedSweepsOncePerEmitAndReannouncesTheFocus` was restated to follow each collapse
with a tail event, as the model's own always is.

**Where the second flush point is, and what it took to get it there (2026-09-15, corrected
2026-09-16).** It was first written as "when nothing more is waiting" — a queue-emptiness test made
on the drain thread while the user-interface thread was still offering the tail one event at a time,
so a drain reaching the top of its loop between the collapse and the first tail `STRUCTURE_CHANGED`
saw an empty queue and re-announced early: the very order the rule above fixes, in the one case
where the drain outruns the producer. That is closed (fix round 3b, Windows item 1) and neither half
of the trade it was weighed against was paid. **The second flush point is the publish boundary.**
The Windows bridge overrides `AccessibilityBridge#frameEnded` (§5.3) and hands the boundary over as
`UiaEvents.FRAME_END`, a marker in the same queue the frame's events went into, so it arrives behind
every one of them however fast the drain thread runs. The debt a collapse leaves is flushed when
that marker is taken and — unchanged — before the first tail event that is not a
`STRUCTURE_CHANGED`, which is still where a tail with a focus, a cursor or a selection in it pays.
Linux reaches the same boundary with no queue to cross (§2.3's `frameEnded` row) and macOS posts its
whole frame there, so all three bridges now flush at one boundary in one order.

**The cost that kept it from being taken blind is answered by the marker, and the guarantee is the
narrow one.** The fear was that a marker offered into the same bounded queue would be swallowed by
that queue's own collapse, leaving a bridge whose scene then runs no further frame owing a
re-announcement with nothing to flush it — a dropped debt traded for a race. `UiaEvents#endFrame`
ignores the collapsed flag (a collapse covers the events it swallowed, and it *raises* the debt this
marker flushes, so it may not swallow the marker), and when the queue has no room it collapses the
queue — the honest answer for a queue already over capacity — and puts the marker in behind the
collapse's own. A collapse can only happen while a frame is handing events over, so that frame's end
always follows it; a window whose scene then goes still has already been told; only a frame that
could leave a debt is marked, so an ordinary frame wakes the drain thread for nothing. **What is
guaranteed is not "the marker is never dropped"** (2026-09-16, the fix round's review — the first
statement of it said more than the code does): a collapse *clears* the queue, so a frame end already
waiting in it is discarded. What holds is that no frame ends without a marker going in, and that a
debt is cleared by the raise that pays it and by nothing else — the frame in which a collapse
happened owes one of its own (`emit` sets the flag on the refused offer), so it marks its end behind
that collapse and the drain flushes every debt still owed when it gets there. The count of markers a
drain sees can fall; the number of debts left with none cannot rise above zero.

Pinned by `UiaBridgeTest.theFocusIsReannouncedAtTheFramesEndAndNotTheMomentTheQueueRunsDry` (which
waits for the drain to be parked in its take — the one place the old emptiness test had certainly
already fired — and asserts nothing has been said yet),
`aCollapseWithNoTailAtAllIsStillFlushedByTheFramesEnd`, `UiaEventsTest`'s three marker cases, and
`aCollapseThatClearsAnEarlierFramesEndStillPaysTheDebtAtItsOwn`, which holds the drain inside the
first sweep so the loss is certain, shows one marker waiting before the second frame's collapse and
one after it, and then shows the re-announcement raised at the second frame's end (red with
`endFrame` returning early while collapsed: "and the frame that collapsed marks its own end behind
it ==> expected: <2> but was: <1>"); `UiaEventsTest.aFullQueueCollapsesRatherThanDropTheFramesEnd`
asserts the same arithmetic on the queue alone. **Phase 5 no longer listens for an early focus**;
what it still hears is the order itself. *(Not reached, 2026-09-16: no reader script collapses the
queue — on Fedora the largest publish any script made was 23 signals against a budget of 256, and
no Windows script produced a collapse tail under NVDA — so the order is still unheard. Still open.)*

**Amended 2026-09-15 (phase 3, Windows; WINDOWS-NEW-6's remainder): `CARET_MOVED` and
`BOUNDS_CHANGED` as built.** `CARET_MOVED` is `Text_TextSelectionChanged`, as its row says, handled
together with `TEXT_SELECTION_CHANGED` (the settled unmapped-and-window-level-events item): the model
emits the two for one field one after the other, and a `TEXT_SELECTION_CHANGED` right behind a raised
`CARET_MOVED` on the same node is not raised again. Both are raised only for a held element, and no
element serves `TextPattern` yet (§2.1, §11), so what a client can do with the event is re-read the
value; NVDA 2024.4.2 maps it to its `caret` event on the focus only (reading §3); phase 5 hears
whether that says anything over a `ValuePattern` field. *(Not exercised, 2026-09-16: over the
date field on Windows no `Text_TextSelectionChanged` was raised at all, the segments vending `Value`
and `RangeValue` and no `Text`, so NVDA was never given one to say. Still open.)*
**`BOUNDS_CHANGED` keeps no mapping, per node
and in bulk**, where the row says a `BoundingRectangle` change or one `LayoutInvalidated`: NVDA
2024.4.2 subscribes to no `BoundingRectangle` change (reading §3) and handles `LayoutInvalidated` only
for Windows search suggestions (§6), while each raise waits for the reader's handler (§13.28), which
during a scroll or a drag is one wait per frame for nobody. Both forms now say so in the trace; the
bulk one (node `0`) returned silently before, and neither pays the event an ask is owed
(`UiaBridgeTest.aCaretMoveIsTheTextSelectionChangeAndItsPairIsRaisedOnce`,
`aBoundsChangeIsRaisedNeitherPerNodeNorInBulkAndSaysSo`).

**Amended 2026-09-15 (phase 3, Windows; WINDOWS-NEW-1, WINDOWS-NEW-3): `ANNOUNCEMENT` and
`STRUCTURE_CHANGED` as built.** `ANNOUNCEMENT` is `UiaRaiseNotificationEvent` on the root's element,
minted if no client holds it: kind `Other` (4) and processing by politeness, `ASSERTIVE` →
`ImportantMostRecent` (1), `POLITE` → `All` (2), the enumerators read 2026-09-13; the text and an
empty activity id travel as `BSTR`s freed after the call, the activity id being what WinForms' own
`AccessibleObject.RaiseAutomationNotification` passes (read as IL 2026-09-15,
readings/windows-dump-uia-provider-conventions.txt §4). NVDA 2024.4.2 consumes notifications from any
element that resolves to a window, while its focus is in this process, cancelling speech first for
`ImportantMostRecent` and queueing `All` (readings/nvda-2024.4.2-uia.md §4). `STRUCTURE_CHANGED` is
`UiaRaiseStructureChangedEvent` in the shape the platform's own `AutomationPeer.UpdateChildrenInternal`
raises, read as IL the same day (§3 of that reading): past the limit — `ItemsInvalidateLimit` (5) for a
container of items (a node with a selection or table facet), `InvalidateLimit` (20) otherwise, which
is what `ItemsControlAutomationPeer` and `AutomationPeer` pass — one `ChildrenBulkRemoved` (4),
`ChildrenBulkAdded` (3) or `ChildrenInvalidated` (2) on the parent with the parent's runtime id;
otherwise `ChildRemoved` (1) on the parent with each removed child's runtime id, then `ChildAdded` (0)
on each added child's own element with its own; and one `ChildrenReordered` (5) on the parent for a
publish that moved surviving children, which the peer has no case for. It is raised only when a client
holds the parent's element, an added child's element being minted for its `ChildAdded`; the parent is
the model's, so a nested tree row removed (§2.1's navigation) is reported to the tree. **NVDA 2024.4.2
subscribes to no structure change** (readings/nvda-2024.4.2-uia.md §5): the event is for the clients
that do (Narrator, Inspect, a .NET client), and phase 5 counts it with one
(`UiaBridgeTest.anAnnouncementIsRaisedOnTheRootEvenBeforeAnyClientHeldIt`,
`aStructureChangeIsRaisedAsThePlatformsOwnPeerRaisesIt`,
`aStructureChangeIsRaisedWhenTheParentIsHeldAndMintsTheChildItAdds`). *(Counted 2026-09-16 on
Windows 11 with a UI Automation client subscribed over the gallery's tree: two `ChildRemoved` at a collapse
and two `ChildAdded` at the re-expand; closed for those two. `ChildrenReordered` on a sort was not
covered and is still open.)*

**Amended 2026-09-15 (review of the Windows phase-3 work): where the two structure-change choices
come from.** The Windows brief asked for `ChildrenBulkAdded`/`ChildrenBulkRemoved` for the model's
coalesced per-parent event; the amendment above raises single `ChildAdded`/`ChildRemoved` events up to
the limit and a bulk change only past it. That is an interpretation, taken because the platform's own
`UpdateChildrenInternal` raises a coalesced change of few children that way (readings/
windows-dump-uia-provider-conventions.txt §3), and it is put to the owner in the lane log. And the
`ChildrenReordered` runtime id is not a free choice: `UpdateChildrenInternal` has no case for a move,
but the same listing holds two client-side proxies that raise `ChildrenReordered` (5), each on its
element with that element's own runtime id — `EventManager.HandleStructureChangedEventWindow` for
WinEvent 32772 (its `MakeRuntimeId()`) and `MSAAEventDispatcher.MaybeFireStructureChangeEvent`'s
default branch (the runtime id of the provider made for the event's object) — which is the shape raised
here on the parent. That the element is the container whose children moved rests on 32772 being the
Win32 reorder event, whose header name was not read.

**Amended 2026-09-15 (phase 3, Windows; CRIT-4's Windows half, the settled value-text-event item,
T7's Windows reading): `VALUE_CHANGED` and `BUSY` as built.** The property-change row's "the
property id" is, for `VALUE_CHANGED`, the property of **each pattern the node vends that the change
moved**: `RangeValue.Value` (30047) with both numbers where the node vends `RangeValue` and the
event's number moved, then `Value.Value` (30045) with the string `get_Value` answers wherever the
node vends `Value`. A change whose number stood moved the text or the emptiness, so a date segment
filled with its minimum raises the string alone; a number that moved raises the string too, because
the event carries no text to compare and a `Value` vended from a value facet is the number's spoken
form. The old string travels as an empty variant: a COM client's `HandlePropertyChangedEvent`
receives only the new value (UIAutomationCore.dll's type library, read 2026-09-13). Until this
amendment one property was raised, `RangeValue.Value` for any node with a number, so a spinner's
"07:30" and a segment's "empty" were never raised as strings, though NVDA 2024.4.2 reads a control
that vends both from `Value` (measured on the guest 2026-09-07, §13.19) and maps both properties
to its `valueChange` (readings/nvda-2024.4.2-uia.md §3); whether it then speaks a change raised as
both once or twice is phase 5's to hear. *(Heard 2026-09-16 on Windows 11, NVDA 2024.4.2: stepping
the date field's year segment up raised `RangeValue.Value` and then `Value.Value` for the one change,
and NVDA said '2027' once; the clock's minute and an empty segment filled from today were each
spoken once too. Closed.)* An event that moved nothing a vended pattern carries raises nothing and pays nothing an ask
is owed (`UiaBridgeTest.aValueChangeRaisesThePropertyOfEachVendedPatternItMovedOn`,
`aValueChangeIsRaisedOnTheHeldElementAsEachPropertyThatMoved`). **`STATE_CHANGED` for `BUSY`** stays
an `ItemStatus` (30026) property change carrying the localized busy phrase and then an empty string,
answered by `get_ItemStatus` the same way; **NVDA 2024.4.2 has no handler for it**: `ItemStatus`
maps to its `UIA_itemStatus` event, which nothing in NVDAObjects handles, and it reads `ItemStatus`
only as the description of an element whose class name is `UIColumnHeader` (readings/
nvda-2024.4.2-uia.md, "`event_UIA_itemStatus`"), so a busy tree row is silent to it on Windows until
a fallback is decided after phase 5's reader run (T7). *(**Confirmed live and closed, 2026-09-16.**
The prediction above was written from NVDA's source and was then measured on the guest: the bridge
raises `ITEM_STATUS` `none→"ocupado"` and back with `hr=0x0(S_OK)`, NVDA receives and queues every
one — `handlePropertyChangeEvent: queuing NVDA UIA_itemStatus event` — and speaks none. Six raises
over three load lengths, five received, zero spoken, with the prediction filed before the runs
(`readings/phase5-windows-fix/busy-short-1`, `busy-long-1`, `t7-prediction.txt`). The fallback is
**decision 73**: the widget announces a lazy load's start and its empty end through `Scene#announce`,
because an announcement is the one route all three readers were measured speaking through that day.
The `ItemStatus` mapping stays exactly as this row describes it — it is right, and it is a client's
to read.)*

#### Amendment 2026-09-15 — the Linux column, as the bridge sends it

Read against what a real toolkit on the Fedora guest sends and what Orca 50.2 does with each field
(readings/upstream-gtk-4.22.4-atk-adaptor-2.60.6-event-shapes.txt, fetched on the host for the
guest's gtk4 4.22.4 and at-spi2-core 2.60.6; readings/fedora-orca-event-consumers.txt and
ubuntu-orca-event-consumers.txt, `scripts/a11y/linux/read-orca-event-consumers.py` on both guests,
2026-09-15). Each paragraph names the row it changes; the table above is left as written.

**`ACTIVE_DESCENDANT_CHANGED` (L1).** `ActiveDescendantChanged` from the focused node's path, with
the new descendant's `(so)` reference as the value and its index in its parent in `detail1` (the ATK
bridge's `active_descendant_event_listener`; GTK 4.22.4 sends no such event). The value was an `i`
0, which libatspi 2.60.6 turns into no `any_data`, and Orca drops the event without one. The
reference and the index are the ones `GetChildAtIndex` and `GetIndexInParent` answer for the same
node, in whichever window holds it (decision 5's popup option is an ordinary reference on the one
connection); a cursor that went away names the null object with `detail1` −1.

**`FOCUS_CHANGED`.** `StateChanged` `focused` 1 on the node gaining it and 0 on the node losing it,
both from the `STATE_CHANGED` the difference raises; `FOCUS_CHANGED` itself sends nothing, and the
deprecated `Event.Focus.Focus` the row names is not sent — Orca 50.2's `Script.get_listeners`
registers no `focus:` event (readings/fedora-orca-event-handlers.txt). The row overstated it
(settled linux-adr-overstatements). *(Corrected 2026-09-15, the review of linux-B: "both from the
`STATE_CHANGED` the difference raises; `FOCUS_CHANGED` itself sends nothing" was false for a node
that arrives already focused — a dialog's first field, a popup's list, a cell widget realized under
the cursor. The difference raises no `STATE_CHANGED` for a new node and only the tail's
`FOCUS_CHANGED` (semantics 7), so such a node was never said focused, while the node losing the focus
was. `FOCUS_CHANGED` now sends `StateChanged` `focused` 1 from its node, after the tail's structure
signals have put the node in a client's cache, and a surviving node's gain, already sent by its
`STATE_CHANGED` in the same publish, is not sent a second time. Commit 26769b3's message repeats the
false sentence.)*

**`WINDOW_ACTIVATED` / `WINDOW_DEACTIVATED`, `WINDOW_OPENED` / `WINDOW_CLOSED` (LINUX-NEW-2,
LINUX-NEW-15, LAB-NEW-2).** `Event.Window` `Activate`/`Deactivate` from the frame's own path (the
window node the difference names, arriving after the publish whose tree marks it `ACTIVE`, so its
`state-changed:active` precedes it as GTK 4.22.4's does), with the window's name as the string value
(the ATK bridge's convention; GTK sends "0"). After `Activate` the focused node's `focused` 1 and
the cursor's `ActiveDescendantChanged` are sent again from that tree, unless the same publish
already sent them: Orca 50.2's `_on_window_activated` moves its locus to the frame, and its 0.1 s
same-type filter would drop a second copy. *(Corrected 2026-09-15, the review of linux-B: the
exception and its reason are wrong; see the correction at the end of the `INVALIDATED` paragraph
below.)* A frame that arrives after the join sends `Create` from
its path after the application's `ChildrenChanged add`; one that leaves sends `Destroy` from its path
before the `remove`. The frames the registry read at the join send no `Create`. The model's
`WINDOW_OPENED`/`WINDOW_CLOSED`, which nothing raises, map to the same members from the node they
name. Node zero's events (`BOUNDS_CHANGED` for a wide scroll) are sent from the frame, never from
the application object, and `BoundsChanged` carries the node's screen extents as `(iiii)`, the
rectangle libatspi makes an `AtspiRect` of, instead of an `i` that arrived as nothing.

**`ANNOUNCEMENT` (LINUX-NEW-3).** `Announcement` as the installed interface declares it,
`(s, i politeness, i, v, a{sv})` (readings/fedora-dbus-Event.Object.xml): empty detail, `detail1` =
`Atspi.Live` (`POLITE` 1, `ASSERTIVE` 2, read 2026-09-13 off the Fedora typelib), `detail2` 0, and
the text as a string value, which is the only `any_data` Orca 50.2's `_on_announcement` presents —
GTK 4.22.4's `gtk_at_spi_context_announce` fills it the same way. Sent from the frame of the window
whose scene said it. It was mapped to nothing.

**`TEXT_CHANGED`, `CARET_MOVED`, `TEXT_SELECTION_CHANGED` (LINUX-NEW-14).** A replacement is a
`TextChanged` `delete` carrying the removed text, then an `insert` carrying the inserted text; each
has `detail1` = the start and `detail2` = the length, both in characters, and the changed text
itself as the value — GTK 4.22.4's `gtk_at_spi_context_update_text_contents` and the ATK bridge's
text listeners send exactly that, and Orca 50.2 speaks `any_data` as the inserted string and drops
an insertion longer than 1000. It was one `insert` of the longer length at the UTF-16 offset carrying
the whole new text. The model's range, compared unit by unit, may start or end inside a surrogate
pair; it is widened to whole characters before it is converted. `TextCaretMoved` carries the caret's
offset in characters in `detail1`, read off the published `TextFacet` (it was always 0; Orca
compares it with the last cursor position). `TextSelectionChanged` carries an empty string.

**`STRUCTURE_CHANGED`, `NODE_DESTROYED` (LINUX-NEW-1, LAB-NEW-3; decision 28).** Per child of the
event's surviving parent, `ChildrenChanged` from the parent's path with the child's `(so)` as the
value and its index in `detail1` (its former index for a removal): removals first, highest index
first, each followed by `Cache.RemoveAccessible` `(so)` when the child left the tree; then additions
and reorders in ascending index, each addition followed by `Cache.AddAccessible` with the item
`Cache.GetItems` lists for it (`((so)(so)(so)iiassusau)`). That order is libatspi 2.60.6's
arithmetic: `remove` takes a child out by reference, `add` removes it and inserts it at `detail1`,
`AddAccessible` overwrites the parent's slot at the item's index, and `RemoveAccessible` disposes the
object (readings/upstream-at-spi2-core-2.60.6-libatspi.txt). A child that moved between parents is
removed from one and added to the other and never removed from the cache. `NODE_DESTROYED` sends
`StateChanged` `defunct` 1 from the node's own path, as GTK 4.22.4 does before unregistering a
context, and a node path no window holds any more answers `GetState` with `DEFUNCT` (6, read
2026-09-13 off the Fedora typelib) while declining everything else — Orca 50.2 ignores an event from
a source that is `DEFUNCT` or whose name cannot be read (readings/fedora-orca-dead-object.txt). It
was one `ChildrenChanged` with an empty detail and an `i` per new node, and a `remove` from the
destroyed node's own dead path, whose `int` crashed Orca's `_ignore_children_changed`. A frame
arriving or leaving after the join gets the same `AddAccessible`/`RemoveAccessible` after its
`ChildrenChanged` from the application object, which closes what §2.3's amendment of this date left
to this item. No container publishes `MANAGES_DESCENDANTS`: decision 28 keeps clients' child caches
correct instead.

**`INVALIDATED` and the reserved tail (L6; decision 28; semantics 4 and 7).** The table has no
`INVALIDATED` row; on Linux it sends nothing of its own — the bridge holds no per-node state to sweep,
and the one `Cache.AddAccessible` for the root that §2 once prescribed would reconcile no client's
cached children or states (libatspi 2.60.6 updates a cached child list only from `ChildrenChanged`
and a state only from `StateChanged`). What a client's cache needs arrives in the tail that follows:
the structure signals above, then the cursor, the selection and the window's activation. The
`focused` changes the collapse swallowed are said again from the tree at once: `StateChanged
focused` 1 on the focused node and the cursor's `ActiveDescendantChanged`, unless the same publish
already sent them. Every signal of a tail event, of a frame's arrival or departure, and of focus said
again is offered to the connection as the tail kind, which `Outbound.SIGNAL_BOUND`'s ordinary backlog
never refuses (`Outbound.TAIL_BOUND`, sixteen times it, bounds the tail alone so a writer that never
writes is still not a leak). An ordinary signal the connection does refuse is followed, once per
publish, by the focus and cursor said again as the tail kind: the bridge's own queue collapse is
answered like the model's. *(Corrected 2026-09-15, the review of linux-B, together with the
`Activate` sentence of the window paragraph above. Three things were wrong. First, "said again
from the tree at once" put the focus and cursor before the tail's structure signals and before
`Activate`, against decision 28's order. When the frame's `state-changed:active` was collapsed
too, `Activate` then moved Orca 50.2's locus to the frame, and nothing brought it back: LINUX-NEW-15
again, on the collapse path. Second, the memory of what had been said was cleared on every publish,
so every publish wider than the budget repeated the focus and cursor even when neither had moved.
Third, a collapse never sent `focused` 0 for the node that lost the focus, and libatspi 2.60.6's
`cache_process_state_changed` clears only the bit an event names. What the bridge does instead:
each window remembers, across publishes, the focus and cursor it last announced (semantics 4).
`INVALIDATED` and a refused signal only mark a reconcile as owed. The reconcile runs before the first
tail event after the structure signals, or, when the publish carries none, before the window's next
publish replaces its tree. It sends only what differs from the memory: `focused` 0 for the node last
announced when that node still stands, `focused` 1 for the node now focused, and the cursor when it
differs or the focus was just said. The window paragraph's exception, "unless the same publish
already sent them … its 0.1 s same-type filter would drop a second copy", misread Orca. That filter
is never reached by a `focused` 1 from a focused source (`_ignore_by_focus_state`, event_manager.py
324-330). What decides the case is which event moves the locus to the frame. The frame's own
`state-changed:active` 1 makes it the active window with the frame as locus (`_on_active_changed`,
default.py 792-822, readings/fedora-orca-focus-manager.txt, Fedora KDE 44, Orca 50.2,
2026-09-15), and an `Activate` for a window that is already active returns without touching the
locus (default.py 1386-1390, readings/fedora-orca-event-consumers.txt). So after `Activate` the focus and cursor are said again only
when they were not said after the frame's `active` 1 in the same publish, or when that `active` 1
was not sent at all. Two identical copies waiting in Orca's queue together are handled once:
`_is_obsoleted_by` drops the earlier for the later, matching same type and same source
(readings/fedora-orca-event-queue.txt). Orca's queue is ordered by `_get_priority` before arrival;
the numeric values of its constants were not read.)*

*(Amended 2026-09-15, semantics 4 settled for the three bridges after phase 3. Two sentences above
change. "It sends only what differs from the memory" is now true of an ordinary publish only: **an
owed reconcile — one the model's `INVALIDATED` or a refused signal asked for — says the focus and
the cursor again whether or not they moved.** Linux was the only bridge that sent nothing when the
focus had not moved, while Windows re-raises and macOS re-posts unconditionally after their own
sweeps; and a focus that did not move is precisely the case where the client is standing on a node
whose state changes it lost in the collapse. The repeat costs a message and no speech: Orca 50.2's
`set_locus_of_focus` returns at once when the locus is already that object (focus_manager.py
278-281, readings/fedora-orca-focus-manager.txt). The memory stays, and is what keeps an ordinary
publish quiet. And "when the publish carries none, before the window's next publish replaces its
tree" becomes **at the end of the frame** (`AccessibilityBridge#frameEnded`, §5.3): a collapse whose
tail holds nothing after its structure signals used to wait for a next publish that a window going
still never makes, so the re-announcement the semantics ask for never happened at all. The publish
path stays as the later net for a refusal that comes after the frame has ended. Order is unchanged
and is semantics 7's: the structure signals first, then focus, then the cursor, then selection and
the window's activation. Pinned by
`AtspiApplicationTest.aCollapseWhoseTailIsStructureAloneSaysTheFocusAgainWhenTheFrameEnds` and
`aCollapseSaysTheFocusAndTheCursorAgainAtTheFramesEndEvenWhenNeitherMoved`.)*

*(Amended again 2026-09-15, the review of that change; semantics 4's other half. "Each window
remembers, across publishes, the focus and cursor it last announced" was one memory per window,
where the settlement asks for **one memory per process** — the platform focus is one — and the
unconditional re-say the amendment above added was gated on nothing, so a collapse or a refusal in a
**background** frame put a `focused` 1 on the bus for a window nobody is in, a case that had stayed
silent while it only sent differences. Both halves close together. The memory is now
`AtspiApplication`'s: the window that holds it, the node, and the cursor, cleared when that window
detaches and on a new join (Windows keeps the same pair in `UiaBridge.ANNOUNCED`). And **only the
frame the desktop has active reconciles at all**: the model publishes `ACTIVE` on the window node of
the scene whose window has the keyboard (`AccessibleWalk`, `Scene#isWindowFocused`), a native popup
that takes the focus included, and a frame without it holds the node the user would *return* to and
not where the user is — which is what Orca 50.2 says of such an event in as many words, "[frame]
lacks active state", then "unable to find active window" (readings/fedora-l4-baseline/summary.md,
LAB-NEW-2). A window that is not active therefore says nothing here: it neither repeats nor
contradicts what the active frame announced. One consequence is new and deliberate: when the active
frame re-says the focus, the `focused` 0 for the node the process last announced goes out **from the
window that holds that node**, which may be another frame — libatspi's `cache_process_state_changed`
clears only the bit an event names, so a focus that crossed windows used to leave `FOCUSED` cached on
a node of each. Pinned by
`AtspiApplicationTest.aBackgroundFramesCollapseSaysNothingAndTheActiveOnesClearsTheOneFocusAnnounced`.)*

**`STATE_CHANGED` for `EXPANDED` and `EXPANDABLE` (L3; decision 27; semantics 9).** Both reach the
bus as `StateChanged` `expanded` / `expandable` (bits 10 and 9) from the difference, and whenever the
bit this platform derives from them — `COLLAPSED` (5), published as `EXPANDABLE` without `EXPANDED` —
moved with the flip, a `StateChanged` `collapsed` follows it: libatspi 2.60.6 sets or clears only
the bit an event names, so without it a client that cached a closed branch and heard `expanded` 1
held both. Its former value is read off the node in the tree the window published before
(`AtspiBridge.previousTree`), because a publish may flip both bits at once. GTK 4.22.4 sends
`expandable` and `expanded` only (whether its state set carries `COLLAPSED` was not read).

**`VALUE_CHANGED` (settled linux-value-text; added 2026-09-15 with the `Text` interface, §2.3's
amendment of this date).** `PropertyChange` `accessible-value` with the new number as a `d`, as
before; and when the number stood still while the display form moved — a date segment filled with its
minimum goes from "empty" to "1" — and the node serves that form as its `Text` (a `ValueFacet` and no
`TextFacet`), a `TextChanged` `delete` of the former form and an `insert` of the new one follow it,
whole string for whole string, read off the node in the window's previous and current trees. A change
that moved the number sends the property change alone, and a node with a text of its own raises its
own `TEXT_CHANGED`.

**A change that moves the interfaces a node serves re-sends its cache item (amended 2026-09-15, review
of the interfaces item).** What a node serves is not fixed by its role: `Text` is served for a value
only while its display form is not empty (§2.3's `Text` amendment), `Action` only while the node has a
verb, which it loses with `ENABLED` — beneath an overlay too — and `EditableText` only while it is
`EDITABLE`. A client keeps a node's interfaces from its cache item, and libatspi 2.60.6's
`add_accessible_from_iter` overwrites them, with the name, role, description and states, from a later
`Cache.AddAccessible` for a node it already holds (readings/upstream-at-spi2-core-2.60.6-libatspi.txt,
atspi-misc.c 578-698); nothing sent one, so a client could lack `Text` on a segment that had just
gained a word, or `Action` on a button enabled again. Now a `VALUE_CHANGED` or `STATE_CHANGED` whose node
serves a different set of interfaces than in the window's previous tree (`AtspiTree.interfaceBitsOf`)
also sends that node's `AddAccessible`: after the state change or the property change, and in a
value's text echo between the `delete` (said while `Text` was still listed) and the `insert` (said once
it is listed). A publish that moves several bits of one node sends the same item after each, and a
modal that withdraws the verbs of every node beneath it sends an item per node whose `Action` went,
beside the `enabled` 0 each already sends. Keeping the list stable per role or facet was the other way
offered; it would have listed `Text` with an empty string on every slider and progress bar, which no
toolkit on the guest does (GTK 4.22.4's level bar serves `Value` alone,
readings/fedora-gtk4-interface-replies.txt). Not observed live: the gallery's date segments always
carry a word. Pinned by `AtspiEventsTest.aChangeThatMovesTheInterfacesANodeServesSendsItsCacheItemAgain`.

**Amended 2026-09-15 (phase 3, the macOS column).** `ACTIVE_DESCENDANT_CHANGED` posts
`FocusedUIElementChanged` **at application level**, as `FOCUS_CHANGED` does, and no longer
`SelectedChildrenChanged`: the cursor is the focused element here, so a client told of it asks where
the focus went. A frame posts at most one of the two, after everything else that frame posts. After
a sweep of the element registry — the bridge's own queue collapse, or the model's `INVALIDATED`,
which is now swept the same way (semantics 7) — the focus change is posted again whenever anything
anywhere is focused, because the sweep may have released what a reader stood on.
`SELECTION_CHANGED` is posted on its container as the notification of that container's selection
attribute: `SelectedRowsChanged` for an outline, a list or a table of rows — which is what a native
`NSOutlineView` posted on itself for a row selected through `AXSelected` and through
`AXSelectedRows`, with no `SelectedChildrenChanged` beside it (read on the macOS 26.6.2 guest,
2026-09-15, `scripts/a11y/macos/outline-probe.swift`) — `SelectedCellsChanged` for a grid of cells,
and `SelectedChildrenChanged` for anything else. `STATE_CHANGED` of `EXPANDED` on an outline row is
`RowExpanded` or `RowCollapsed` on the row, plus one `RowCountChanged` per outline per frame on the
outline, as the native outline posted them when its row's `AXDisclosing` was set; on anything else it
stays `ValueChanged`. *Corrected the same day (the macos-B review; M3 correction f):* a
member's `STATE_CHANGED` of `SELECTED` is not posted when its container is posted the selection change
in the same frame, and `STATE_CHANGED` of `ACTIVE` is posted nowhere — no attribute is read back off
it, and where it matters the focused node's `ACTIVE_DESCENDANT_CHANGED` is the focus change — because
one arrow in a focused `Tree` posted four `ValueChanged` on the rows beside `SelectedRowsChanged` and
the focus change, where the native outline delivered only `AXSelectedRowsChanged` to an observer that
also asked for `AXValueChanged`. One cursor move now posts exactly those two. *And the row count
(M1 correction 2):* `RowCountChanged` is no longer derived from an outline row's `EXPANDED` flip
alone. Every publish compares each held table's, outline's and list's row count — the table facet's
count, the hierarchy facet's row count, the set size — with the snapshot before, and the frame's end
posts one `RowCountChanged` on each container whose count moved and which is still in the tree, once
per container however many publishes or openings moved it: a lazy load landing under a row already
open, a refresh, and a list growing or shrinking are row-count changes too; a scroll, which changes
which rows are realized and not how many there are, is none. Only the disclosure trigger was read on
the native outline; the others follow from the same attribute.
*And the window's own events (MACOS-NEW-3, the same day):* `ANNOUNCEMENT` names no node and
`INVALIDATED` names none either, and a `STRUCTURE_CHANGED` of the root names the node this bridge elides,
so all three were posted on an element no client held — which is to say never. An announcement is now
`AnnouncementRequested` posted **on the window**, carrying `NSAccessibilityAnnouncementKey` (its text)
and `NSAccessibilityPriorityKey` (10 polite, 90 assertive) as user info; `INVALIDATED` and a change of
the root's children are one `LayoutChanged` on the window per frame. The window, because on the macOS
26.6.2 guest (2026-09-15, `scripts/a11y/macos/announcement-probe.swift`) a notification posted on the
window reached both an observer registered on the window and one registered on the application, one
posted on `NSApp` only the application's, and one posted on the content view nobody's. Whether VoiceOver
speaks an announcement posted there is phase 5's to hear. *(Heard 2026-09-16 on macOS 26.6.2:
`AnnouncementRequested` arrived on the window with priorities 10 and 90, and VoiceOver spoke 'Salvo'
and 'Interrompido, nada foi salvo'; closed.)* `NODE_DESTROYED` releases the element at the
frame's end and still posts nothing (§2.2's note of the same date).

**The macOS column as built (dated 2026-09-15; MACOS-NEW-7).** The table above is kept as written; this
is what the bridge posts at the end of phase 3, where the macOS cells above say otherwise.
`FOCUS_CHANGED` and `ACTIVE_DESCENDANT_CHANGED`: one `FocusedUIElementChanged` per frame at application
level, last. `STRUCTURE_CHANGED`: `LayoutChanged` on the held parent — not `Created` or
`UIElementDestroyed`, which AppKit posts itself — and on the window for the elided root's children.
`NAME_CHANGED` `TitleChanged`; `DESCRIPTION_CHANGED` `LayoutChanged`; `STATE_CHANGED` `ValueChanged`,
except `BUSY` (`AXElementBusyChanged`), `ACTIVE` (nothing), `SELECTED` on a member whose container is told
the selection in the same frame (nothing) and `EXPANDED` on an outline row (`RowExpanded` /
`RowCollapsed` and the outline's `RowCountChanged`); `VALUE_CHANGED` and `TEXT_CHANGED` `ValueChanged`;
`SELECTION_CHANGED` the container's shape; `CARET_MOVED` and `TEXT_SELECTION_CHANGED`
`SelectedTextChanged`; a row container whose count moved `RowCountChanged`. `BOUNDS_CHANGED`: **nothing**
(the cell said `Moved`/`Resized` and a bulk `LayoutChanged`; the boxes are pushed instead).
`WINDOW_OPENED`/`CLOSED`/`ACTIVATED`/`DEACTIVATED`: **nothing** (the cells named AppKit's own
notifications, which AppKit posts for the window it vends). `NODE_DESTROYED`: release at the frame's end,
nothing posted. `INVOKED`: nothing. `ANNOUNCEMENT`: `AnnouncementRequested` on the window with its text and
priority. `INVALIDATED`: `LayoutChanged` on the window, the registry swept, the focus change posted again.
Every post, an announcement's user info and a re-push's children array are made inside an autorelease
pool the bridge pushes when a publish or a frame's end starts its platform work and pops before it
returns: neither is an accessibility callback, so no pool of AppKit's is on the stack, and on the
`-XstartOnFirstThread` main thread what they autoreleased was never freed — an announcement's objects
were still alive 120 polled frames later, about five blocks a frame, and none with the pool (read on the
macOS 26.6.2 guest, 25G83, 2026-09-15, `scripts/a11y/macos/AutoreleaseProbe.java`; the macos-C review).

**Amended 2026-09-23: a polite announcement is posted at the high priority too, because VoiceOver has
no priority that waits.** "10 polite, 90 assertive" above assumed low meant "spoken when whatever is
being read finishes". Measured with the gallery's loading tree (`--reader tree-loading`, macOS 26.6.2):
"Remote, 3 itens", posted low when Remote's fetched children arrive, reached VoiceOver while it was
still saying "linha 7 expandida"; VoiceOver queued it, moved its own cursor to the outline — a native
`NSOutlineView` makes it do the same (`scripts/a11y/macos/outline-steps-probe.swift`, `lazy`) — and
spoke its hint for the outline, about eight seconds long. Until the hint ended no focus change in the
tree was spoken, and the queued announcement came out at the script's step 17: steps 15 to 17 silent in
seven runs of seven, at low and at medium (50). Taking `AXElementBusyChanged` away, posting
`RowExpanded` again as the native outline does when its children arrive, and posting the focus change
again after the recount changed nothing; posting the announcement high did — all twenty-one steps spoken
in two runs of two (`readings/list-multi-macos`, `hyb-tree-1`, `hyb-tree-2`, `texp-*`). It surfaced
when a row container stopped being told `LayoutChanged` (ADR 045 §8), which had made VoiceOver re-read
and drop the queued announcement unsaid. The cost is that a polite announcement now interrupts what
VoiceOver is saying: "Carregando Remote" and "Remote, 3 itens" take the place of "linha 7 expandida",
and an application's polite announcement can cut a reading short. NVDA and Orca keep their polite
level, which waits and was heard waiting.

**Amendment, 2026-09-15 (semantics 4, one shape on all three bridges): the macOS bridge keeps the
memory too.** Phase 3 left three readings of "each bridge remembers the last effective focus it
announced": Windows one memory per process, Linux one per window sending only differences, and macOS
none at all — it posted one `FocusedUIElementChanged` per frame that drained a focus or cursor event,
and again after every sweep. The lane argued that as not a defect, because the post names no element
and the client asks `accessibilityFocusedUIElement`, which is answered live. The orchestrator settled
one shape instead, and this bridge now holds it: **one memory for the whole process**, because the
platform focus is one; a focus or cursor event resolving to the node already announced posts nothing;
the model's `INVALIDATED` and the bridge's own queue collapse re-announce whatever they name, because
the sweep may have released the element the reader stood on; and the memory is forgotten when nothing
is focused in any open window, on `WINDOW_DEACTIVATED` (which still posts nothing of ours) and when
the bridge that owns it detaches.

Two consequences worth stating. The answer is resolved exactly as `focusedElement()` resolves it, so
a cursor that lives in another window's tree is remembered as **that** window's node and a second
window asking about the same cursor does not announce it twice. And a focus event over a tree that
stamps no focus now posts nothing, where before it posted: there is nowhere to send a reader, and
Windows' `raiseFocus` has always behaved this way. Pinned by
`AxFocusTest.anEffectiveFocusAlreadyAnnouncedIsNotAnnouncedAgainUntilASweepAsksForIt` and
`aDeactivatedWindowAndAnEmptyFocusBothForgetWhatWasAnnounced`. The tail's order is unchanged and is
semantics 7's: structure first, focus last in the frame.

**What the forgetting buys here is not what it buys on Windows** (corrected 2026-09-15, the fix
round's review of the paragraph above, which had carried Windows' sentence — *"so that a return is
announced however little moved while away"* — across to this platform). That sentence is true on
Windows because §2.4's Windows cell for `WINDOW_ACTIVATED`/`WINDOW_DEACTIVATED` **is** the focus
change: UI Automation has no window activation event of its own, so `UiaBridge` raises the focus on
the return and the cleared memory is what lets that raise be heard. This bridge posts nothing for
either event and is right not to: the macOS cell is AppKit's own `MainWindowChanged` and
`FocusedWindowChanged`, the null mapping is deliberate and pinned
(`AxNotificationsTest.theWindowEventsAreAppKitsOwnAndNotOurs`), and a client that wants to know
where the user now is asks `accessibilityFocusedUIElement`, which is answered live. So a bare return
— activation back, nothing moved — announces nothing here, and adding a post for it would be
inventing a notification no reading asked for. What the forgetting does buy is the **next focus
event** after the return: a node that arrives holding the focus is a `FOCUS_CHANGED` even when it is
the node announced before (this section's 2026-09-14 amendment, WINDOWS-NEW-12), so a window whose
content was rebuilt while the user was in another application says where the user is again instead
of being silenced by a memory made while VoiceOver's cursor was in another process entirely. The
test pins both halves: the activation posts nothing and leaves the memory empty, and the focus event
after it is announced though it names what was announced before.

**An event is half a conversation, and the other half is a question this table does not name.**
Three platforms, three live runs, and the same failure on two of them: a reader is told that
something changed, it then asks a question of its own, and if nobody answers that question the
event buys nothing at all. The silence is indistinguishable from never having posted.

| Platform | What the reader asks after a focus event | Status |
| --- | --- | --- |
| Windows | `GetFocus` on `IRawElementProviderFragmentRoot` | answered from the start (phase 6) |
| Linux | whether any window has `ACTIVE` | **the phase 5 defect**: Orca suppressed *every* announcement over a correct tree with correct events, because no window claimed to be active |
| macOS | `accessibilityFocusedUIElement` | **the phase 7 defect**: VoiceOver stood on the first node and never moved, having been told each time that the focus had changed |

So the rule is not "raise the event" but "raise the event and be able to answer what it invites",
and the two are written in different files by different people. A reviewer of a fourth bridge
should look for the question before looking for the event.

#### Amendment 2026-09-16 — the reader does not only ask after a focus event; it writes back

**The table directly above asks what a reader wants to *read* after a focus event. Phase 5 on macOS
added the other half: what it wants to *write*.** VoiceOver keeps its own cursor and the keyboard
focus in step, so when this bridge posts `FocusedUIElementChanged` for a cursor the application moved
itself, VoiceOver answers by writing `AXFocused` on the element its cursor is still on — the previous
row — about 40 ms later. The `FOCUS_CHANGED` row's macOS column is therefore incomplete as written:
posting `FocusedUIElementChanged` at application level is correct and is not the defect, but **on
macOS a focus event is a round trip, and what the bridge offers as settable decides whether the
return leg lands.** Measured, `readings/phase5-macos/`: two `AXSelectedRowsChanged` and two
`AXFocusedUIElementChanged` per key, the second of each undoing the first, on every step of every
tree script, and none of it with VoiceOver stopped. §2.2's amendment of this date has the full
measurement and the fix — a row is no longer focus-settable, because a native row carries no
`AXFocused`.

**The general rule, which a fourth bridge should read before it installs a setter.** An event says
what changed; the attribute set says what a reader may change back. Where the two overlap on the same
state, the reader and the application are two writers of one value, and the platform arbitrates by
recency rather than by authority. So a setter is not safe merely because the node accepts the verb:
it is safe where a *native* element of that shape carries the attribute, because that is what tells
the reader whether this is a value it owns. Neither half is visible headlessly — a unit test proves
the write lands, and only a live reader makes the write.

**And the reason both defects survived so long is worth more than either of them.** Neither is
visible on a widget that has a second thing to announce. On macOS the check box appeared to be
followed correctly for four runs, because its *value* changed at the same moment its focus did and
the value change was announced; the button was the only widget in the probe with nothing to say but
its own name, and it was the only one that looked broken. A probe whose every step also changes a
value cannot see this class of defect at all. §12.2's lab suites therefore drive focus and value
**separately**, and a widget with no value is not a poor test case but the only one that works.

---
