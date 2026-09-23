# ADR 046: The public API ends where a module says, and a backend owes what the SPI says

- **Status: ACCEPTED, 2026-09-22.** Decisions 118 to 124 (batch 28 of the 2026-09-22 review) are what
  this record writes down; the owner took the recommended option on each of the seven and asked for
  the whole round before 0.8.0, in one record, written before the code. All seven sections are built,
  in the order §8 gives, with `check` green after each; the notes marked *Built as* and *Amended
  while building it* say where the building moved the record.
- **Date:** 2026-09-22
- **Scope:** where the published API of `limn-toolkit` ends, what an application author can call on a
  widget and a scene, and what a backend must provide. Seven changes, each breaking, each cheaper now
  than after 1.0.
- **Compatibility:** none owed (no consumers). Every caller in this repository — the demo, the theme
  editor, the site's examples, the tests — migrates in the same round, and the guides and READMEs with
  them.

## 0. What was measured before anything was decided

The 2026-09-22 review read the API at `main` e793bb37 with the widgets' own guides as the statement of
intent (`.claude/pending/2026-09-22/BACKLOG.md`, ids API-1 to API-7). What it found, in its own
numbers:

- **No module boundary.** No `module-info.java`, no `Automatic-Module-Name`. So every public class is
  API, including helpers that exist only to be shared between the toolkit's own packages:
  `limn.lang.Checks` (used from 17 packages across four modules), `limn.concurrent.Listeners`,
  `ChangeListeners`, `SharedLoads`, `Threads`, `limn.io.Closeables`, the seven shape helpers of
  `limn.components.a11y`, and `limn.components.text.TextEditModel`, which `TextField` also hands to
  subclasses as `protected final model`. No package is split across modules.
- **35 concrete widgets are open for subclassing**, so every protected hook — `onPaint`,
  `handleUserChange`, `onSyntheticAction`, `paintsFromBackdrop`, `showContextMenu` — is a contract. In
  the repository only the `TextField` family and test probes extend a concrete widget.
- **Every widget has public `add` and `remove`** (`Widget`), and no widget overrides them:
  `button.add(label)` compiles, and removing one of a table's children takes a row out from under
  its virtualization.
- **The scene is a backend's input sink in public.** `Scene` implements `WindowInput`, so an application
  sees `keyEvent`, `mouseButton`, `windowResized`, `windowClosed`, `inputBatchEnded` beside `setRoot`;
  about 133 test files drive scenes through them, which makes them the toolkit's UI test driver in
  all but name, and that driver is not published.
- **A backend's duties are not on the SPI.** `LwjglBackend` fills eleven static slots the `Backend`
  interface never names (the UI runtime, the text ruler, the font catalog and loader, the image
  decoder, the SVG rasterizer, 3D, video surfaces, the audio engine and decoder); forgetting the ruler
  makes every text measure zero, silently. Defaults that do nothing stand where a real backend must
  act: `Canvas.drawSurface` (3D and video draw blank), `NativeWindow.setCloseRequestHandler` (an
  "unsaved changes" veto is lost), the three IME methods (CJK input breaks);
  `supportsAbsolutePositioning` answers `true`, the unsafe answer. Partial rendering assumes exactly
  two buffers, and `mouseButton` carries no click count, so a double click is 400 ms in two widgets.
- **`WindowConfig` is a record of eleven components, six of them booleans**, and the README teaches the
  positional form. A record's canonical constructor is always public, so any new field breaks every
  caller.
- **`Theme` is 36 `public final` fields**, read about 200 times as `Theme.current().x`, and there is
  no per-subtree resolution, while locale, direction and control size already resolve per subtree.

## 1. Decision: internal packages, and a module that exports only the API (decision 118)

Helpers move to `*.internal` packages — `limn.internal.lang` (`Checks`), `limn.concurrent.internal`
(`Listeners`, `ChangeListeners`, `SharedLoads`, `Threads`), `limn.io.internal` (`Closeables`),
`limn.components.internal.a11y` (the seven shape helpers), `limn.components.text.internal`
(`TextEditModel`, `CodePoints`, `TextAccessibility`). Each published module gains a `module-info`:
`limn.toolkit` exports its API packages and exports the internal ones only to the modules that need
them (`limn.backend.lwjgl`, `limn.video.ffmpeg`, `limn.themeeditor`, `limn.test`). On the class path —
jbang, the fat jar, most applications — a `module-info` is ignored, so nothing changes there; on the
module path the compiler enforces the boundary; and on either path a package named `internal` says
what it is. `TextField`'s `model` field stops being protected (§2 makes the question moot).

*Built as, and amended while building it:*

- **The text helpers** went to `limn.components.internal.text`, beside `internal.a11y`, and not
  to `limn.components.text.internal`. The text package held nothing else.
- **The model accessor.** `TextField.model()` and `TextArea.model()` were public and returned the
  model, the only way an application or the demo could select text or place the caret. Both now
  answer only inside their package. The two widgets gained `caretPosition`, `selectionStart`,
  `selectionEnd`, `selectedText`, `setCaretPosition`, `select(anchor, caret)` and `selectAll`.
  A programmatic move is announced as `SELECTION` at `CODE` and snaps to a grapheme boundary.
- **Four modules have a `module-info`**: `limn.toolkit`, `limn.test`, `limn.video.ffmpeg` and
  `limn.backend.lwjgl`. The theme editor and the demo name themselves with
  `Automatic-Module-Name` instead. The theme editor's program compiles against a backend that its
  POM names for running only, and the demo is an application run from the class path.
- **The backend** first got an automatic name too, because jlayer, its MP3 decoder, has no
  module name. That failed on the first run: an automatic module cannot say it needs LWJGL, so
  nothing resolved LWJGL. It is now a real module that requires the LWJGL modules. It reads the
  class path for jlayer, through `--add-reads` at compile time and `Module.addReads` at run time.
- **Resources had to change**, which this record did not foresee. On the module path a class's
  resource lookup stays inside its module, so `Images.fromResource` and the like could no longer
  reach an application's files.
  - `Resources`' class form now falls back to the class loader.
  - New stream forms of `Resources` let a module open its own encapsulated resources and hand
    them over.
  - `PropertyBundle.family(String)` reads the toolkit's own catalogs from its module first.
  - A new `PropertyBundle.family(String, Function)` takes the owning module's own lookup.
  - The backend opens its shaders, fonts and catalog itself. Its catalog moved from
    `limn/i18n`, a package the toolkit owns, to `limn/backend/lwjgl/i18n`. On the module path
    the old folder was a split package, which stops the JVM from starting.
- **`/api/`** is still one tree of packages, and it no longer shows the internal ones.
- **Proved from a consumer**: a module of its own, run with `java --module-path` on macOS on
  2026-09-22.
  - Headless through `limn-test`, nine checks passed. They covered driving the scene, the
    toolkit's catalog, the application's catalogs and images in each placement, and the export
    boundary both ways.
  - With a window, it drew Roboto, Arabic, CJK and emoji from the font jars. It also drew the
    toolkit's icons, the pt-BR placeholder, and both of the application's images.
  - The font jars, the FFmpeg native jars, jlayer and LWJGL's native jars must be on the class
    path. The packaging guide says so and why.
- **What that run found elsewhere.** The frame pacer's skip for a covered window (decision 113)
  also skipped a window owed a capture, so `captureNextFrame` on a covered window never
  answered. A window with a capture pending now draws.
- **Left for the repositories that own them**:
  - The `limn-fonts` jars have no module name and nothing requires them, so on the module path
    they are never loaded. Resolving one (`--add-modules limn.fonts.roboto`) resolves them all.
    A first draft of this note said the shared `limn/fonts` folder was a split package; it is
    not, because an automatic module's packages come from its classes, and these jars have none.
  - The `limn-ffmpeg-natives` main jar and its classifier jars all derive the automatic name
    `limn.ffmpeg.natives`. Two of them in one module-path folder stop the JVM at startup.
  - The theme editor documents `/limn/i18n/themeeditor` for an application's translation. A
    named application module cannot use that folder, because the toolkit owns `limn.i18n`.

## 2. Decision: concrete widgets are final, `TextField` is sealed (decision 119)

Every concrete component class is `final`. `TextField` is `sealed` and permits its own family
(`PasswordField` and `SearchField`). *Amended while building it:* the layout primitives — `Column`,
`Row`, `Stack`, `Padding` and the token-spaced `TokenColumn`, `TokenRow`, `TokenPadding` — stay open,
because extending a layout to measure and place children is the extension a layout exists for, and
the toolkit and the demo extend them themselves (`BackdropPanel`, `Dialog`'s action row, the demo's
font and icon scenes); `Container` is where a new layout starts. Of the probes, most only read a
protected hook the component overrides in its own package and now call it directly; the chart's
geometry is read by reflection, the shaping count is taken at a counting ruler, and paint is left
out by a parent that paints no children. Sealing `TextField` took away the one route the forms guide taught for
submitting on Enter — a subclass overriding the key hook — so `TextField.onSubmit` joins it, the
`SearchField` handler moved up a level: Enter hands the handler the text, is left alone without one
(a dialog's default button still answers it), and is never taken while an input method composes. A widget of one's own extends `Widget`,
which is the documented route and keeps its hooks. Test probes that subclassed a concrete widget move
to composition. Opening a class later is compatible; closing it is not.

## 3. Decision: only a container adds and removes children in public (decision 120)

`Widget.add` and `Widget.remove` become `protected`. A new abstract `Container extends Widget` makes
them public, and `Column`, `Row` (through `Flex`) and `Stack` extend it, as do layouts an application
writes. *Amended while building it:* `Padding` is not a container after all — it wraps the one child it
is constructed with, and a second one added from outside would never be laid out, the defect
`TokenBoxAccessibilityTest` had recorded for the same shape — so it stays a plain widget. Nothing in the
repository added to a `Padding` from outside.
A widget that manages its own children (Table, Tree, TabbedPane, SplitPane, the date widgets) keeps
them to itself.

## 4. Decision: the scene's input is private, and `limn-test` is the driver (decision 121)

`Scene` stops implementing `WindowInput`. It hands the window a private adapter in `bind`, and the
input and lifecycle methods (`keyEvent`, `charInput`, `mouseMove`, `mouseButton`, `scroll`,
`windowResized`, `windowClosed`, `inputBatchEnded` and the rest) leave its public surface.
`layoutPass` and `renderFrame` **stay public**: rendering a scene into a canvas of one's own is a
feature (the gallery's captures are built on it), not plumbing. A new published module, `limn-test`,
carries what an application needs to test its own UI without a display: `HeadlessUi`, `StubWindow`, a
headless `Backend`, `NoopCanvas`, the fixed text rulers, and a `SceneDriver` that clicks, types, presses
keys and renders frames through the scene's adapter (reached by a qualified export). The repository's
own tests use it, which is how the driver stays honest.
*Built as:* the adapter is a private inner class of `Scene`, and `limn.scene.internal.SceneAccess` lends
it out; only `Scene`'s own static initializer can install that hook, so whoever loads first cannot
replace it. `SceneDriver.drive(scene)` is itself a `WindowInput` for the raw calls, and adds gestures
that are a whole user action each, batch included: `click(widget)`, `click(x, y)`, `type`, `press`,
`moveTo`, `scroll`. Rendering stays on `Scene`, as above, so the driver does not render. The module
is the old test fixtures moved (package `limn.testing`, no JUnit), plus the demo's `HeadlessBackend`
and `HeadlessWindow` and the driver. `RepositoryRoot`, which only a test inside this checkout needs,
stays behind as the toolkit's one unpublished fixture, in `limn.testfixtures`. The demo's own program
depends on `limn-test`, because its gallery films, captures and accessibility gallery replay input into
scenes of their own. About 1,400 call sites in 140 test files moved from `scene.keyEvent(…)` to
`drive(scene).keyEvent(…)`. The testing guide is new, and its example is compiled and run.

## 5. Decision: the backend contract is written on the SPI (decision 122)

- A record, `BackendServices`, names the services, and one core call, `BackendServices.install`,
  installs them all at once. *Amended while building it:* they are ten, not eleven (the font catalog's
  change notifier is the font store's own business), and five are required — the UI runtime, the text
  ruler, the font catalog and loader, the image decoder, the SVG rasterizer — and refused on the spot
  when null, while 3D, video surfaces and audio take `null` for "this backend has none", which is a
  truthful answer for a machine without an audio device and which the toolkit already reports as
  unavailable rather than failing.
- `Canvas.drawSurface`, `NativeWindow.setCloseRequestHandler`, `setImeEnabled`, `setPreeditCaretRect`,
  `resetPreedit` and `supportsAbsolutePositioning` lose their defaults. The defaults that are a truthful
  "this platform cannot" (opacity, cursors, fullscreen) keep theirs.
- `FrameInfo` gains `bufferAge`: how many frames old the back buffer's contents are, 0 when unknown.
  Partial rendering repaints what changed across that many frames and the whole window at 0, instead of
  assuming two buffers.
- `WindowInput.mouseButton` gains a click count. The backend counts, with the platform's own
  double-click interval (`[NSEvent doubleClickInterval]` on macOS, `GetDoubleClickTime` on Windows, 500
  ms elsewhere, GLFW having none), and `Table` and `Tree` read the count instead of timing 400 ms
  themselves. *Built as:* `MouseEvent.clickCount()`; a count of 0 from a backend that does not count
  (the test doubles, a headless window) is counted by the scene on its own clock with the 400 ms the
  two widgets used, so nothing that drove a double click before stops being one. Even counts pair up,
  so a triple click activates a row once, as the timers did.

## 6. Decision: `WindowConfig` is a final class with a factory and withers (decision 124)

`WindowConfig.of(title, width, height)` and immutable withers (`resizable(false)`, `visible(false)`,
`transparent(true)`, and one per remaining option) replace the eleven-component record. A new option is
a new wither, and no caller breaks. The README and the guides move to `of`.

## 7. Decision: the theme is read through accessors, resolved by the widget (decision 123)

`Theme`'s fields become private with accessors of the same name (`theme.background()`; `dark`
becomes `isDark()`, because `Theme.dark()` is already the dark palette's factory), and
`Theme.of(widget)` becomes the one resolver a widget paints from. *Amended while building it:* the
record first said `Widget.theme()`, which cannot be — `Widget` is `limn.scene`, and no file there
names a `limn.components` type, the layering `Theme.setCurrent` already explains — so the resolver
is a static on `Theme` taking the widget, with the same effect. Today it answers the process-wide theme;
it is the seam a per-subtree theme can be added behind later without breaking anyone, as the locale axis
was. `theme.apply(Scene...)` joins it, the one call that switches the palette, repaints each scene and
applies the theme's font (decision 117's rule, as API); `Theme.setCurrent` stays as the cheaper
half for a colour well that switches the palette on every frame of a drag. The guides and the class Javadoc teach that
call and nothing else.

## 8. Order, and what proves each step

Smallest first, so each commit is a green `check`: §6 `WindowConfig`; §7 the theme (and DOC-2's text);
§3 the container; §2 final widgets; §5 the backend contract; §4 the scene's input and `limn-test`; §1
the internal packages and `module-info`, last, when nothing else moves. The site builds and its examples
compile at each step; the README and the guides change in the step that changes what they teach.

## 9. What this does not do

No per-subtree theme (§7 makes room for it). No change to the ADR 040 channel contract: `onX` stays one
slot. The mediums of the review (API-8 to API-14) stay in the backlog: the three list widgets' naming,
the date widgets' `onSelect`, the fluent-versus-void setters, the unchecked threads, the watcher slips.

## 10. Dependencies

ADR 040 (the two channels, untouched); ADR 043 (partial rendering, which §5's `bufferAge` corrects);
ADR 002, 032, 035 (the inherited axes §7's resolver joins).

## 11. The review's medium API items, decided after this record was accepted

The owner then asked for every open item of the 2026-09-22 review, one at a time (batch 30). The
ones that change API are recorded here as they land.

- **API-8, the three list-shaped widgets (decision 136).** One `limn.components.SelectionMode`
  (`NONE`, `SINGLE`, `MULTI`) for `Table`, `Tree` and `ListView`; `CalendarView` keeps its own,
  which chooses days and has `RANGE`. `Tree.scrollBy(dx, dy)` replaces `scrollBy(dy)` and
  `scrollHorizontallyBy(dx)`. `ListView` is `ListView<T>` over a `List<T>` the application owns
  (`setItems`, `refresh()`), with three ways to make a row: a function of the item, a pool that
  binds a recycled widget (`ListView.pooled`), or a function plus a recycle callback for rows of
  several kinds. The untyped `Adapter` is gone. It gains the three selection modes with the
  gestures the table and the tree share, the cursor apart from the selection outside `SINGLE`,
  `onSelect(Runnable)` as they have it, and a double click that activates. Activation stays an
  index, as the table's does: equal items are two rows. `SINGLE` behaves and looks exactly as
  before, which the unchanged test suite and accessibility transcripts hold; the rows contract now
  runs with `MULTI` entered. A live reader run over a multiple-selection list is owed.
- **API-9, the date widgets' handlers (decision 137).** One handler each, a `Runnable` that reads
  what it needs: `DatePicker.onSelect` and `CalendarView.onSelect` (a day in `SINGLE`, a closed
  period in `RANGE`; the first click of a period still reaches nothing) and `DateField.onChange`,
  which now runs for the time of day too. `CalendarView.onSelectRange` and
  `DateField.onTimeChange` are gone. The picker's handler used to be handed the start of a period
  and nothing that said the time had moved. `DatePicker.setRange` checks the UI thread and
  announces once, with the whole period, where it announced twice with a half-set period between.
- **API-10, chaining (decision 138).** `Widget<W extends Widget<W>>`: a widget names itself
  (`Button extends Widget<Button>`), and the seventeen setters every widget has return `W`, so
  `new Button("OK").setEnabled(false)` is a `Button`. The abstract bases that are extended
  (`Container`, `Flex`, `Chart`, `CartesianChart`) take the same parameter, so a `Column`'s
  `gap(8)` is a `Column`; a concrete open class (`Column`, `Row`, `Stack`, `Padding`, `TextField`)
  hands its own type down. A widget used only as a type is `Widget<?>`. The four `withControlSize`
  methods, which existed only to chain a `void` setter, are gone. The owner chose this over a written
  rule with `void` base setters, with the cost stated: every declaration in the repository moved, an
  application's own widget writes `extends Widget<MyWidget>`, and an anonymous class, which cannot
  name itself, goes through a named abstract class. The verbs and `Flex`'s missing getters were the
  other option and stay as they are. The rewrite missed four setters, found later by reading a
  guide's sample against the source: `Widget.setCursor` and `setImageCursor` still returned
  `void`, and `CartesianChart.setStacked` and `setHorizontal` returned `CartesianChart<?>`. They
  return `W` now, and a test fails for any public setter of a self-typed base that does not.
- **API-11, the UI-thread rule (decision 139).** A test now reads the sources and fails for any
  public mutator of the widgets, the layouts and the scene that neither checks the UI thread nor
  hands straight to a method that does; the two exceptions it allows are getters whose names start
  with a verb. Its first run found 73 such methods, not the six the review named, in 25 classes
  (the dialog's 17, the date picker's 9, the scene's 7, the media controls' 8 among them), and each
  now checks first. `Clipboard` states the rule: UI thread only.
- **API-12, what a watcher hears (decision 140).** A radio a pre-selected newcomer displaces says
  `VALUE`/`ADJUSTMENT`; a value or a validity that `DateField.setGranularity` trimmed, and a month
  `CalendarView.setClock` moved, are `ADJUSTMENT`, the widget's own consequence, not `CODE`;
  `CalendarView.setDateFilter` with the same filter is no change (a different one is announced
  even if it happens to answer alike, since the calendar cannot know without asking every day);
  `setAccessibleName` announces `NAME`/`CODE` when the name moves; and `TextField.setTrailingButton`
  replaces the button whole, as configuration and not a handler slot, and announces
  `CHILDREN`/`CODE`.
- **API-13, the small inconsistencies (decision 141).** `Checkbox(String)` and
  `Checkbox(I18nString)` make a box; `Checkbox.variant()`, `Slider.step()`, `Table.isStriped()`,
  `isShowHeader()` and `scrollbarPolicy()` read back what their setters wrote; the two boolean
  getters without `is` are `TextArea.isSoftWrap()` and `Spinner.isSnapToStep()`; and five Javadoc
  samples that guarded delivery with an `isAttached` no widget has now use `isShowing`.
  `ComboBox.popupWindow()` and `PopupMenu.popupWindow()` stay public, documented as supported,
  because an application capturing its own windows needs the popup's, which the owner window's
  capture does not contain; the demo's capture mode is that application. Three items the review
  grouped here are designs, not slips, and are their own questions: the OpenGL-shaped backend
  types, where `ModalStack` belongs, and the system's dark-mode and reduced-motion preferences.
- **API-14, the gaps (decision 142).** A widget has an id for the code that looks it up:
  `Widget.setId`, `Scene.find(id)` and its typed form, and `SceneDriver.click(id)`. An id is not
  shown, not read by an assistive technology and not required to be unique. The fake backend the
  review asked for already ships as `limn-test`'s `HeadlessBackend`. In-app drag and drop, a
  clipboard beyond text, settings persistence and nullness annotations are features, not slips,
  and wait for a record of their own after 0.8.0.
