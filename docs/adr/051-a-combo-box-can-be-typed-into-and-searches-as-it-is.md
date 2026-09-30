# ADR 051: A combo box can be typed into, and searches as it is

- **Status:** Implemented, 2026-09-30, with the owner's six answers below and a seventh (§7).
  Where the build departs from §4, §7 says so.
- **Date:** 2026-09-30
- **Scope:** `ComboBox` becomes generic over its items, and gains an editable mode: typing filters
  the list, the items may come from a list or from a search the application runs in the background,
  and what the field accepts is the application's choice. What a screen reader is told, and what is
  left out on purpose.
- **Compatibility:** breaking for `ComboBox`, which is free before 1.0 (no consumers yet): it
  becomes `ComboBox<T>`, and the migration inside the repository is part of the change. Everything
  else is additive.

---

## 0. What existed

`ComboBox` chooses one of a fixed list of strings: Space, Enter or Down opens the list, the arrows
move a highlight, Enter commits, letters jump by type-ahead. Its list is a window of its own grafted
into the field's tree (ADR 039 §1.11 as amended), or an overlay where a window cannot be placed. It
cannot be typed into, it cannot hold anything but text, and it cannot show items it does not
already have. The road map to 1.0 names "an editable ComboBox with a filter" as the forms theme's
item, and the market assessment of 2026-09-08 as the control a business application misses most:
the "Customer" field of a sales order, where the user types a few letters of a name among forty
thousand and picks one.

## 1. The owner's decisions (2026-09-30)

Each asked with the "Customer" field as the scenario, with a recommendation.

1. **An option of `ComboBox`, not a widget of its own.** `setEditable(true)`, as Swing's
   `JComboBox` and JavaFX's `ComboBox` have it, where a developer looks for it first. (The
   recommendation was a separate widget; the owner chose the familiar shape.)
2. **Items are objects, in both modes.** `ComboBox<T>`, with a function that gives an item its
   text; a search for customers answers customers, and the application receives the customer it
   picked, not its name. The choose-only combo takes the same shape, so the two modes are one
   class with one type.
3. **What is typed and not in the list: both modes.** By default an editable combo accepts only an
   item: leaving the field with text that names none reverts it to the item it held, and a reader
   is told the text was not accepted, as a `Spinner` tells a refused number. An application that
   wants free text (a "City" that may be new, a tag) turns it on and says how a text becomes an
   item.
4. **The filter: "contains", ignoring case and accents, by default; the application may give its
   own.** "sao" finds "São Paulo Alimentos" and "Padaria São João", the items whose text starts
   with the typed text first. A predicate replaces the rule for an application whose items are not
   found by their text alone (a customer found by a tax number as well as a name).
5. **The items: a list in memory, or a search the application runs in the background.** Both in
   the first cut. A search is a function from the typed text to a `CompletionStage` of items,
   which the application writes with `Ui.async`; the list says it is loading while one runs, and an
   answer for a text the user has typed past is dropped.
6. **No inline completion.** The text stays as typed. The list opens filtered with its first item
   highlighted, and Enter picks it. Completing into the field changes the text under the caret,
   which a screen reader reads as an edit the user did not make and an input method has to fight;
   ARIA's combobox pattern treats it as an option, not a default.

## 2. The shape

```java
ComboBox<Customer> customer = new ComboBox<>(Customer::name)   // how an item reads; editable
        .setSearch(text -> Ui.async(() -> customers.find(text)))
        .onSelect(picked -> order.setCustomer(picked));
```

- `new ComboBox<>(items)` for items that read as themselves (a `String` as it is, an `I18nString`
  in the UI language, anything else by `toString()`), `new ComboBox<>(items, text)` for items that
  read as a function says, `ComboBox.localized(List<I18nString>)` as before, and
  `new ComboBox<>(text)` for a combo whose items come from a search, editable from the start. The text function
  may answer a `String` or an `I18nString`; an `I18nString` follows the language. A choose-only
  combo needs at least one item; a combo that searches may start with none.
- `selectedItem()` answers the item itself, `selectedText()` what it reads as; `setSelectedItem(T)`;
  `onSelect(Consumer<T>)` replaces `onSelect(IntConsumer)` and hands over the item picked.
  `selectedIndex()` and `setSelectedIndex(int)` stay for a list in memory, and answer -1 and refuse
  while a search supplies the items, where an index names nothing lasting.
- `setEditable(boolean)`: the field becomes text the user types into, with caret, selection, the
  clipboard and input methods, as a `TextField`'s.
- `setFreeText(Function<String, T>)`: how a text that names no item becomes one; `null`, the
  default, accepts items only.
- `setFilter(BiPredicate<T, String>)`: whether an item matches the typed text; `null` restores the
  default of decision 4. Applies to a list in memory; a search does its own finding.
- `setSearch(Function<String, CompletionStage<List<T>>>)`: `null` returns to the list in memory.

*Built so far* (2026-09-30): step 1 of §6, `ComboBox<T>` with the repository migrated; step 2,
the editable field. The field is a text field inside the combo (`ComboBox.Editor`, a permitted
subclass of `TextField`), so the caret, the selection, the clipboard, undo and the input method
are a text field's own, and the tree reads a combo box holding a text field, which is the shape of
Windows' editable combo and GTK's combo with an entry. The list never takes the keyboard: in a
window of its own it is grafted as before and its highlight is the tree's cursor while the field
keeps the focus (`SceneAccess.showCursorOf`); drawn in the scene it is an overlay that leaves the
keyboard with the field (`SceneAccess.pushPopupLeavingKeyboard`), so a Wayland session keeps the
caret and the input method too. The owner considered drawing a second editor over the field inside
the popup window (2026-09-30) and kept one editor in the owner's window, since the second would move
the keyboard between windows at every opening, the noise the menus had just been rid of. Steps 3 and
4 followed the same day: free text and the refusal, in the spinner's sentence; and the search, with
its delay, its dropped answers, its loading, nothing-found and failure lines (text in the list, not
options) and the count told politely, in four new sentences across the 21 catalogues. A filter over
a list in memory that finds nothing shows the nothing-found line too, without announcing it at
every keystroke.

## 3. Behaviour, where the decisions above do not already say it

These are the toolkit's defaults, chosen here and changeable by a later record.

- **Typing opens the list.** A character typed into a closed editable combo opens it, filtered.
  Down opens it unfiltered when the text is the selected item's own, filtered otherwise.
- **Enter** picks the highlighted item and closes the list. With the list closed it commits what
  the field holds, as leaving the field does.
- **Escape** closes the list and keeps the text; a second Escape, with the list closed, puts back
  the selected item's text.
- **Leaving the field** (Tab, a click elsewhere) commits: an item whose text equals what is typed
  (compared as the filter compares, case and accents aside) is picked; otherwise free text is
  converted when it is on, and refused and reverted when it is off. An empty field clears the
  selection when free text is on, and reverts when it is off.
- **A search waits 200 ms** after the last keystroke before it asks, so that a word typed quickly is
  one request, and the answer for anything but the text now in the field is dropped.
- **Nothing found** is a line in the list, not an item: "Nenhum resultado", not selectable, and
  announced politely once per search that finds nothing.
- **A failed search** leaves the list saying so in one line and logs the cause; the field stays
  editable.

## 4. What a screen reader is told

- The field is a `COMBO_BOX` with an editable text facet (ARIA 1.2's combobox with a text input;
  UI Automation's ComboBox with a Value pattern that is not read-only; `AXComboBox` with a settable
  value; AT-SPI's combo box with editable text), `EXPANDED` while the list is down, and its value
  the text in the field.
- The list stays grafted into the field's tree, and the highlighted item is the field's active
  descendant while the list is open, as the choose-only combo's is. The caret stays in the field.
- While a search runs, the list is `BUSY` and holds one "Carregando…" line, as a tree's loading
  branch does (ADR 044); the result count is announced politely when it arrives ("12 resultados").
- A refused text is announced as the spinner's refusal is, in the 21 shipped languages:
  "“Padaria Nova” não aceito, continua Padaria São João".
- Every widget the reader rule of ADR 045 covers is heard before it is called done: the editable
  combo is driven by a reader script on NVDA, VoiceOver and Orca with real keys, beside a native
  editable combo on each platform.

## 5. What this does not do

- No inline completion (decision 6); no multiple selection (a tag field is another widget); no
  grouping or headers in the list; no rich rows (an icon and two lines per item); no paging of a
  search beyond what the application returns.
- The choose-only combo keeps its type-ahead. An editable combo has none: its letters are text.

## 6. Order, and what proves each step

1. `ComboBox<T>` with the repository migrated, behaviour unchanged: every existing test and the
   gallery goldens pass untouched.
2. The editable field: text, caret, selection, clipboard, IME, with the list filtered by the
   default rule; headless tests for every rule of §3.
3. Free text and the refusal, with the sentence in the 21 languages.
4. The search: delay, stale answers dropped, loading, nothing found, failure; tests on a clock.
5. Accessibility contract (ADR 045 shape), a gallery entry "Combo box, editable" and its reader
   script; live runs on the three readers beside a native control.
6. The forms guide, the CHANGELOG, and this record's status.

## 7. What was built, and where it departs from this record (2026-09-30)

All six steps landed. What differs from the text above, each for a reason a test or a reader gave:

- **The reader stands on a text field, not on a combo box with a text facet.** The editor is
  `ComboBox.Editor`, a `TextField` permitted by the sealed class, published as `TEXT_FIELD` inside
  the `COMBO_BOX` node, and named by the combo's caption; the combo node names nothing while
  editable, since its item is the field's text (it was read as "Andes, combo box, Mountain ranges,
  edit"). That is the shape GTK 3 gives a combo with an entry, and the one NVDA, VoiceOver and Orca
  read as "Cordilheiras, edição, Andes", "Andes, Cordilheiras, editar texto" and "Cordilheiras
  editable combo box Andes".
- **The list never takes the keyboard.** In a window of its own it is grafted and its highlight is
  the tree's cursor (`SceneAccess.showCursorOf`); drawn in the scene it is an overlay that leaves
  the keyboard with the field (`SceneAccess.pushPopupLeavingKeyboard`), which the walk and the
  action gate treat as operable on both layers.
- **A seventh decision, the owner's (2026-09-30): the field says it autocompletes.** Orca 50.2 read
  the narrowed list twice at every keystroke, because its priority queue handles the cursor before
  the field's caret, which puts it back on the field, and the list's selection change then moved it
  to the row again. It reads such a change as the field's own business only when the field has
  `supports-autocompletion` and the list is `controlled-by` it. So `Accessible.State` gained
  `AUTOCOMPLETES` (ADR 039 §1.2 amended: the one state only one platform carries as a flag), and
  the field is `CONTROLLER_FOR` the list while it is shown, named back by the list's
  `CONTROLLED_BY` — the shape a browser gives an ARIA combo box. The owner chose it over a rule the
  Linux bridge would infer, and over leaving it known.
- **Two Linux bridge corrections the runs found.** A cursor that leaves a focus which stays now
  puts Orca back on the focus, said between the publish's text changes and its caret; Enter was
  heard as "return" and nothing else, and a context menu closed with Escape said nothing of its
  button. And a one-line editable field is `single-line` (derived, as GTK 3's entries are; GTK 4.22
  and Qt 6 publish neither bit): Orca 50.2 read the value an Enter put in the field a second time
  as typed text.
- **The native comparison was made on Linux only.** The state shapes of GTK 3, GTK 4.22 and Qt 6
  entries were read on the Fedora guest and decided the `single-line` derivation. No native
  editable combo was driven beside ours on Windows or macOS: NVDA and VoiceOver read ours word for
  word the same before and after the Linux changes, and none of the three findings was theirs.
- **Heard (real keys, pt-BR):** NVDA "caixa de combinação, recolhido / Cordilheiras, edição, Andes
  / a / Cordilheiras, lista, Alps, 1 de 6 / Atlas, 1 de 2 / Carpathians, 2 de 2 / Cordilheiras,
  edição, Carpathians"; VoiceOver "Andes, Cordilheiras, editar texto / Alps, item de lista / Atlas,
  item de lista / Carpathians, item de lista / Carpathians, Cordilheiras, editar texto"; Orca 46.1
  (Ubuntu X11, list in a window) "Cordilheiras editable combo box Andes / … List with 6 items. Alps
  / … List with 2 items. Atlas / Carpathians / Cordilheiras editable combo box Carpathians"; Orca
  50.2 (Fedora Wayland, list in the scene) the same, with one difference kept as known: the first
  Down after a letter reads the list's name and count again, since the caret put Orca on the field.

