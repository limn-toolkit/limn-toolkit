# Changelog

What changed between releases, and what to change in your code when you move. Newest first.

Each section is named after the release it follows. It is complete on the day the next version is
tagged, and no release has to edit this file to rename it; the release on GitHub and the version on
Maven Central say which number that was.

## After 0.7.0

### New

**Tables, dates and trees.**

- `Table<T>` puts typed columns over a list your application owns. The table sorts, or
  `onSortRequest` hands the click to your server. It has a footer that sums, averages or counts,
  and selection by record in `NONE`, `SINGLE` or `MULTI` mode. There is also a focus cell, widget
  columns, and a header that is a keyboard stop and sorts. A record is edited whole; cells are not
  editable.
- `DateField`, `DatePicker` and `CalendarView` hold an ISO value and draw it in the reader's own
  calendar and format: Gregorian, Japanese, Hijri and the others. The locale orders the segments.
  The calendar opens in a native popup or an in-scene card, and supports ranges, day marks,
  bounds, a refusal predicate, week numbers and a time row.
- `Tree<T>` is an outline over children you provide. A row may promise children before it can
  name them and load them off the UI thread; meanwhile it shows a "Loading…" line, then an
  "Empty" one if nothing arrives, and it announces both. Deep rows scroll sideways; the indent is
  never squeezed.

**Two channels on every widget.** An `onX` handler runs for the user alone.
`observeChanges` hears every change with its origin (`USER`, `CODE` or `ADJUSTMENT`), and every
registrar hands back a `Subscription`.

**Partial rendering is the default.** A frame repaints what changed, and every public widget is
held by a test to what its gestures repaint.

**Screen readers.**
- The three platform bridges publish the platform's focus idiom, selection and cursor events,
  structure changes, editable text on Linux, announcements (`Scene#announce`), descriptions
  (`Label.setDescriptionFor`) and a busy state.
- A new widget of a known shape (a toggle, a list of rows, a grid, a menu and six more) needs a
  host, a describe call and a contract test, and no bridge code.
- On macOS a list, a `ListView` or a combo box's open list, is published as a table, as AppKit's
  own lists are. VoiceOver says "table" for it and now speaks its selection.

**Languages.**
- `PluralString` and `PluralRules` write a counted sentence with one key per grammatical form.
  The rules are transcribed from CLDR and checked against it in the build.
- `NumberFormats` gains compact and currency formats.

**Tests.** The new `limn-test` module tests a UI with no display. `SceneDriver.drive(scene)`
clicks, types, presses keys and delivers any raw window input. With it come a headless runtime
and backend, a stub window and canvas, fixed text rulers, and the accessibility contracts the
toolkit's own widgets are held to. A widget can carry an id: `setId("save")`, then
`scene.find("save")` or `drive(scene).click("save")`.

**Smaller.**
- `ListView<T>` is a typed list over your items, with the three selection modes.
- A backend of your own keeps native modals with `limn.backend.ModalStack`: which window a modal
  blocks, and why the topmost one is always answerable.
- Scroll bars can reserve a strip instead of overlaying.
- A spinner can be set by text.
- A covered window on macOS stops rendering until it is uncovered.
- A table sorts a text column from keys extracted once, and a tree splices only the block that
  opened or closed.

### Breaking, and how to move

One breaking round, taken before 1.0.

| 0.7 | Now |
| --- | --- |
| a setter in code ran the `onX` handler (`slider.setValue(v)` ran `onChange`) | `onX` runs for the user alone; `widget.observeChanges(change -> …)` hears every change, with its origin |
| a second `onX` registration replaced the first | it throws; pass `null` to clear the slot first, or use `observeChanges`, which takes any number |
| `I18n`, `Fonts`, `ControlSize` or `LayoutDirection` `.addChangeListener(r)` / `.removeChangeListener(r)` | `.observeChanges(r)`, which returns a `Subscription`; `cancel()` it to stop. `Theme` has one too |
| `viewport.onDispose(r)` | `viewport.observeDispose(r)` |
| `mediaControls.setOnRefresh(r)` | `mediaControls.observeRefresh(r)` |
| `slider.onChange(Consumer<Float>)`, `spinner.onChange(Consumer<Double>)`, `splitPane.onRatioChange(Consumer<Float>)` | `FloatConsumer` and `DoubleConsumer`; a lambda is unchanged |
| `new WindowConfig("Title", 480, 320, …)` | `WindowConfig.of("Title", 480, 320)`, then withers: `.resizable(false)`, `.visible(false)`, `.transparent(true)` … |
| `Theme.current().background` (a field) | `Theme.of(widget).background()` in a widget, `theme.background()` elsewhere; `dark` is `isDark()` |
| `Theme.setCurrent(t)` plus a relayout | `t.apply(scene)` switches the palette, applies its font and repaints |
| `anyWidget.add(child)` | only a `Container` (`Column`, `Row`, `Stack`, your own layout) adds and removes children in public |
| `class MyButton extends Button` | concrete widgets are `final`; extend `Widget`, or compose; `TextField` is sealed |
| `class MyWidget extends Widget`, `Widget w`, `List<Widget>` | `class MyWidget extends Widget<MyWidget>`, `Widget<?> w`, `List<Widget<?>>`; every setter now chains (`new Button("OK").setEnabled(false)` is a `Button`) |
| `button.withControlSize(size)` | `button.setControlSize(size)` |
| overriding `TextField`'s key hook to submit on Enter | `field.onSubmit(text -> …)` |
| `field.model().selectAll()`, `field.model().setCursor(…)` | `field.selectAll()`, `field.select(anchor, caret)`, `field.setCaretPosition(i)`, `caretPosition()`, `selectedText()` |
| `textArea.softWrap()`, `spinner.snapsToStep()` | `isSoftWrap()`, `isSnapToStep()` |
| `new ListView(new ListView.Adapter() { rowCount / rowAt / recycle / rowName })` | `new ListView<>(item -> cell)` with `setItems(list)`, or `ListView.pooled(create, bind)`, or `new ListView<>(cellFor, recycle)`; `setItemName(item -> name)` |
| `listView.onSelect(index -> …)` | `listView.onSelect(() -> … listView.selectedIndex())`; `MULTI` through `setSelectionMode` |
| `Table.SelectionMode`, `Tree.SelectionMode` | `limn.components.SelectionMode`, shared by `Table`, `Tree` and `ListView` |
| `tree.scrollBy(dy)`, `tree.scrollHorizontallyBy(dx)` | `tree.scrollBy(dx, dy)` |
| `datePicker.onSelect(date -> …)`, `calendar.onSelectRange(range -> …)`, `dateField.onTimeChange(time -> …)` | `onSelect(() -> …)` or `onChange(() -> …)`, reading `date()`, `range()` or `dateTime()` |
| `scene.keyEvent(…)`, `scene.mouseButton(…)`, `scene.inputBatchEnded()` … in a test | `SceneDriver.drive(scene).press(Keys.ENTER)`, `.click(widget)`, `.type("…")`, or the raw calls on the driver (`limn-test`) |
| `limn.lang.Checks`, `limn.concurrent.Listeners` … | moved to packages named `internal`, which no module exports; not API |
| a `Graphics3D.Provider` implementing `renderDemoScene`, or `Graphics3D.renderDemoScene(…)` | gone: a `Viewport3D` with no renderer draws its cube through the public renderer, so a provider owes targets, meshes, textures and passes only |
| a backend filling the toolkit's static slots | `new BackendServices(…).install()`; `Canvas.drawSurface`, the close-request handler, the IME calls and `supportsAbsolutePositioning` must be implemented |
| `WindowInput.mouseButton(…)` with no count | a click count; `MouseEvent.clickCount()`; the platform's own double-click interval |
| `FrameInfo` without a buffer age | `bufferAge`, 0 when unknown |

**Behaviour.**
- A plain `ar` locale now writes Latin digits, as native applications do. The regions that use
  Arabic-Indic digits (`ar-EG` and 22 others) keep them.
- Every public mutator of a widget, a layout or the scene now checks that it runs on the UI
  thread. A write from a worker, which was a silent race, now throws.

**The module path.** `limn.toolkit`, `limn.backend.lwjgl`, `limn.video.ffmpeg` and `limn.test`
are named modules, and `limn.themeeditor` is an automatic one. The font jars, the FFmpeg native
jars, jlayer and LWJGL's native jars stay on the class path. The Packaging guide says why, and
how an application module lets the toolkit read its own resources.

### Known, and left for later

- NVDA says nothing at a select-all, as it says nothing at one in Windows' own lists; VoiceOver
  and Orca do. It says nothing either when Space takes a table's row out of the selection while the
  cursor is on one of the row's cells, as it says nothing in a WPF DataGrid.
- VoiceOver is given a sorted header's direction and does not read it out.
- A date picker's year chooser keys its cells by position, so paging a block of years may make a
  reader speak a stale year first.
- On Windows, a UI Automation client's `SetFocus` on a column header, or on a row of a list or a
  tree in `SINGLE` (where the cursor is the selection), is refused; the container's is accepted.
