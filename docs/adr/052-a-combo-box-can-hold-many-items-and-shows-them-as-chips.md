# ADR 052: A combo box can hold many items, and shows them as chips

- **Status:** Implemented, 2026-09-30, with the owner's answers below and the three he added
  after trying the demo (decision 8, the placeholder, the chips' height). §7 says where the build
  departs from the text.
- **Date:** 2026-09-30
- **Scope:** `ComboBox<T>` gains a multiple selection, shown in the closed field as a row of chips
  that ends in a count of the ones that did not fit; its items gain an optional icon; a chip's
  drawing can be replaced by the application. What a screen reader is told.
- **Compatibility:** breaking for `ComboBox.onSelect`, which becomes a `Runnable` as the other
  widgets that can hold more than one selection have it (`ListView`, `Table`, `Tree`,
  `CalendarView`, `DatePicker`); free before 1.0 (no consumers yet), and the migration inside the
  repository is part of the change. Everything else is additive.
- **Supersedes:** the "no multiple selection" and "no rich rows" lines of
  [ADR 051](051-a-combo-box-can-be-typed-into-and-searches-as-it-is.md) §5, for a list in memory.

---

## 0. What existed

After [ADR 051](051-a-combo-box-can-be-typed-into-and-searches-as-it-is.md) a `ComboBox<T>` holds
one item, chosen from a list or typed and filtered. A business form also asks for several: the
"Status do pedido" filter of a sales report, where the user wants Aberto, Faturado and Enviado at
once. No platform has a native multiple-choice drop-down (Windows and GTK none, AppKit's
`NSTokenField` is a token editor), so the reference for what a reader is told is ARIA's combobox
over a listbox that is `aria-multiselectable`.

## 1. The owner's decisions (2026-09-30)

Asked with an estimate (a summary text in the field about 0.7 of ADR 051's cost, removable chips
1.5 to 2 times it) and then by scenario, the "Status do pedido" filter with an icon per status.

1. **Simple chips, on one line.** Each selected item is a chip: its icon, when the combo has one,
   and its text. No remove button on a chip. What does not fit on the field's one line becomes a
   count, "+2". The application can replace how a chip is drawn.
2. **A mode of `ComboBox`**, not a widget of its own: `setSelectionMode(SelectionMode.MULTI)`, with
   `ListView`'s words for the rest, and `onSelect` a `Runnable` for both modes.
3. **Built now**, before the theme after forms, because it changes `onSelect`'s shape.
4. **Typing hides the chips.** In an editable multiple combo the field shows what is typed, which
   only filters the list; the chips come back when the text is empty again.
5. **Chips in the list's order**, never in the order they were picked, so they never trade places.
6. **A reader hears the count and the names**: "3 selecionados: Aberto, Faturado, Enviado",
   including the ones the "+N" hides from sight.
7. **No "select all" row.** Ctrl+A (Cmd+A) in the list takes every item; `selectAll()` and
   `clearSelection()` are the application's.

8. **A combo holding one item may hold none (2026-09-30, after trying the demo).** A setting puts
   a first, synthetic option in the list whose value is `null`; its text and icon come from the
   same functions as the items', asked for `null`, so the application words it as it words them.

## 2. The shape

```java
ComboBox<Status> status = new ComboBox<>(List.of(Status.values()), Status::label)
        .setSelectionMode(SelectionMode.MULTI)
        .setItemIcon(Status::icon)
        .onSelect(() -> report.filterBy(status.selectedItems()));
```

- `setSelectionMode(SelectionMode)`: `SINGLE`, the default, or `MULTI`. `NONE` is refused: a combo
  box that chooses nothing is a label. Switching to `MULTI` starts with nothing selected: the item
  a combo holding one must hold was not chosen by anyone, and a report filter that opened already
  filtering by it would be wrong. Switching back keeps the first selected, or the first item.
- `selectedItems()` and `selectedIndices()` answer in the list's order; `isSelected(int)`;
  `setSelectedItems(Collection<? extends T>)` and `setSelectedIndices(int...)` are a caller's
  writes; `selectAll()`, `clearSelection()`. In `MULTI`, `selectedIndex()` and `selectedItem()`
  answer the first selected, or -1 and `null`, as `ListView`'s do; `setSelectedIndex(i)` makes
  that item the whole selection.
- `onSelect(Runnable)`: the user changed the selection, in either mode. The handler reads what it
  needs. It replaces `onSelect(Consumer<T>)`.
- `setItemIcon(Function<? super T, ? extends Icon>)`: an item's icon, `null` for none, shown in
  the list's rows, in the chips and in a single combo's field. `null` as the function removes them.
- `setEmptySelectionAllowed(boolean)` (decision 8; Vaadin's name for the same setting): the list
  of a combo holding one item starts with an option standing for nothing. Choosing it, or
  `clearSelection()`, makes `selectedItem()` `null` and `selectedIndex()` -1. It reads as the text
  function answers for `null` and shows what the icon function answers for `null`; a function
  that cannot take `null` (a method reference on the item) leaves it blank, and a reader then
  hears the toolkit's "None". An editable combo shows the option's text in its field, as the
  other combo does its label (the owner's correction, 2026-09-30: the field first showed only the
  icon), and takes it when emptied by hand or when its text is typed back, instead of refusing. Turned off while nothing is held, the combo takes the first item. Holding many, the
  list has no such option: an empty selection is already a state.
- `setPlaceholder(String | I18nString)` (the owner's idea, 2026-09-30, taken with §4): a muted
  hint the field shows only while it reads nothing — a combo holding many with none selected, an
  empty option without words of its own, an editable combo's emptied text. It is never the value:
  an empty option with words shows them. It names the combo for a reader when set, as a text
  field's placeholder does, so a combo is no longer named by the item it happens to hold.
- `setChipRenderer(Function<? super T, ? extends Widget<?>>)`: the widget a chip is drawn with,
  asked once per item when it is first selected. It is drawn and never operated: it takes no
  pointer and no keyboard, and it is not in the accessible tree (a reader hears the field's
  value, §4). A renderer that answers a focusable widget is refused. `null` restores the default
  chip, a pill with the icon and the text.

## 3. Behaviour

- **The list stays open while items are toggled.** A click on a row, Space or Enter on the
  highlighted row toggle it, and the list stays down; Escape, Tab or a click outside closes it.
  The highlight opens on the first selected item, or the first row.
- **The field** shows the chips in the list's order, as many as fit whole, then a "+N" badge for
  the rest. When even the first does not fit beside the badge, it is shortened with an ellipsis.
  An empty selection shows an empty field.
- **Editable (`setEditable(true)`)**: typing filters the list as in ADR 051, and while there is
  text the field shows it instead of the chips. Enter toggles the highlighted row and empties the
  text, so the chips come back and the list shows every item again; leaving the field, or Escape
  with the list closed, empties it too. Nothing typed is ever committed: a text that names no
  item is simply a filter. While the chips are shown the caret is not drawn; the focus ring says
  where the keyboard is, and the first character typed puts the text and its caret back.
- **Ctrl+A (Cmd+A)** in the open list of a combo that is not editable selects every item. In an
  editable one the keyboard is in the text, where it selects the text, as in every field.
- **Not with a search or free text.** `MULTI` refuses a combo that has `setSearch` or
  `setFreeText`, and each of those refuses a `MULTI` combo: a selection held across searches, and
  a text turned into a new chip (a tag field), are each a record of their own when asked for.

## 4. What a screen reader is told

- The field stays a `COMBO_BOX`. Its value is the count and the names, "3 selecionados: Aberto,
  Faturado, Enviado", or "Nenhum selecionado", in the 21 shipped languages, with the language's
  own list separator; the number under it (the count) is read-only, since a value cannot be set as
  one text. An editable combo's field carries the same sentence as its description, since its
  value is the text typed.
- The list is multiselectable and not required. The highlight is the cursor, the active
  descendant a reader follows; an option's selection is whether it is checked, so the arrows say
  "não selecionado" on an option that is not, as a native multiple list does. A reader's
  `ADD_TO_SELECTION` and `DESELECT` toggle an option, `SELECT` makes it the whole selection,
  `PRESS` toggles it as a click does, and `FOCUS` moves the highlight.
- The chips and the badge are not in the tree: they repeat the value.
- Heard on NVDA, VoiceOver and Orca with real keys before this record is called implemented.

## 4a. Found in the owner's first try of the demo (2026-09-30)

- **An editable combo's list button did not open the list with a pointer** where the list is a
  window of its own (the Mac). The press opened the list, and the outside-press observer the
  opening registered heard the same press after its dispatch, took the text field inside the
  combo for "elsewhere" and closed the list. A press inside the combo is no longer "elsewhere".
  The keyboard path, which ADR 051's reader runs used, never met it; the in-scene tests neither.
- A press where the chips stand opens the list, as it does on a combo that is not editable.
- The "+N" badge is smaller than a chip: the label font, and inset within the chips' band.

## 4b. Heard (2026-09-30, real keys, pt-BR, gallery "Combo box, many" and "…, many, editable")

- **NVDA 2024.4.2:** "Cordilheiras, caixa de combinação, 2 selecionados: Andes, Carpathians,
  recolhido"; in the list "Atlas, não selecionado, 3 de 6", Space "selecionado" and then "não
  selecionado"; Escape "… 2 selecionados: Andes, Atlas". Editable: "Cordilheiras, edição, Nenhum
  selecionado", and after Enter "Cordilheiras, edição, 1 selecionado: Atlas".
- **Orca 50.2 (Fedora, list in the scene):** the list, "não selecionado", Space "selecionado" and
  "desmarcado"; editable "Nenhum selecionado" and "1 selecionado: Atlas". The non-editable field
  is read without its value, as the single combo is there too (a standing behaviour, measured beside
  it, not this record's).
- **Orca 46.1 (Ubuntu X11, list in a window):** the field "2 selecionados: Andes, Carpathians"
  before and "2 selecionados: Andes, Atlas" after; "Atlas. not selected."; a toggle is not announced.
- **VoiceOver:** the rows, and each toggle as the field's new value ("3 selecionados: Andes, Atlas,
  Carpathians") followed by "texto inserido". Two things left open: that suffix, and a lone
  "Nenhum" after Escape closes the editable one's list while it holds Atlas.
- The chips are one point shorter than a segment's pill above and below (the owner's call).

## 5. What this does not do

- No remove button on a chip, no keyboard walk between chips, no chips on more than one line.
- No multiple selection over a search, no free text turned into chips (§3).
- No "select all" row, no groups or headers in the list.

## 6. Order, and what proves each step

1. `onSelect(Runnable)`, the repository migrated: every existing test passes with its handler
   rewritten, and none changes what it asserts.
2. The item icon, in the rows, the field and the editor.
3. `MULTI`: the model, the keys, the pointer, the check marks; headless tests for every rule of §3.
4. The chips, the badge and the renderer; the editable interplay.
5. Accessibility (§4), the sentences in the 21 languages, a gallery entry and a reader script;
   live runs on the three readers.
6. The forms guide, the site gallery, the CHANGELOG, and this record's status.

## 7. What was built, and where it departs from this record (2026-09-30)

All six steps landed on the day. Where the build differs from §1 to §4:

- **Switching to `MULTI` starts empty** (§2 was corrected before the code): the item a combo
  holding one must hold was nobody's choice.
- **An editable combo shows the empty option's words in its field** (§2, the owner's correction):
  the first build left the text empty and showed only the icon.
- **The name of a combo with a placeholder is the placeholder**, for one item as for many, so a
  combo is no longer named by the item it holds once an application gives it a hint.
- **A pointer defect of ADR 051 was found and fixed here** (§4a), with two tests that fail
  without the fix; a disabled editable combo drew its text field as an enabled one, fixed too.
- **Heard on four guests** (§4b). Left open and in the CHANGELOG's known list: VoiceOver's "texto
  inserido" after a toggle and its lone "Nenhum" after Escape in the editable one; Orca 46.1's
  silent toggle; Orca 50.2 reading no value of a combo that is not editable, which predates this
  record.
- **Documentation:** the forms guide's "Choosing several" and "Nothing chosen", each with a sample
  `FormExample` compiles; a "ComboBox (many)" card on the components page; the `--scene combos`
  demo with every kind, enabled and disabled.
