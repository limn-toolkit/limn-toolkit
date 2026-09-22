# ADR 046: The public API ends where a module says, and a backend owes what the SPI says

- **Status: PROPOSED, 2026-09-22.** Decisions 118 to 124 (batch 28 of the 2026-09-22 review) are what
  this record writes down; the owner took the recommended option on each of the seven and asked for
  the whole round before 0.8.0, in one record, written before the code.
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

## 2. Decision: concrete widgets are final, `TextField` is sealed (decision 119)

Every concrete widget class is `final`. `TextField` is `sealed` and permits its own family
(`PasswordField` and whatever else in the toolkit extends it). A widget of one's own extends `Widget`,
which is the documented route and keeps its hooks. Test probes that subclassed a concrete widget move
to composition. Opening a class later is compatible; closing it is not.

## 3. Decision: only a container adds and removes children in public (decision 120)

`Widget.add` and `Widget.remove` become `protected`. A new abstract `Container extends Widget` makes
them public, and `Column`, `Row`, `Stack` and `Padding` extend it, as do layouts an application writes.
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

## 5. Decision: the backend contract is written on the SPI (decision 122)

- A record, `BackendServices`, names the eleven services, and one core call, `BackendServices.install`,
  installs them all at once; a missing one is a constructor argument left null, refused on the spot.
- `Canvas.drawSurface`, `NativeWindow.setCloseRequestHandler`, `setImeEnabled`, `setPreeditCaretRect`,
  `resetPreedit` and `supportsAbsolutePositioning` lose their defaults. The defaults that are a truthful
  "this platform cannot" (opacity, cursors, fullscreen) keep theirs.
- `FrameInfo` gains `bufferAge`: how many frames old the back buffer's contents are, 0 when unknown.
  Partial rendering repaints what changed across that many frames and the whole window at 0, instead of
  assuming two buffers.
- `WindowInput.mouseButton` gains a click count. The backend counts, with the platform's own
  double-click interval (`[NSEvent doubleClickInterval]` on macOS, `GetDoubleClickTime` on Windows, 500
  ms elsewhere, GLFW having none), and `Table` and `Tree` read the count instead of timing 400 ms
  themselves.

## 6. Decision: `WindowConfig` is a final class with a factory and withers (decision 124)

`WindowConfig.of(title, width, height)` and immutable withers (`resizable(false)`, `visible(false)`,
`transparent(true)`, and one per remaining option) replace the eleven-component record. A new option is
a new wither, and no caller breaks. The README and the guides move to `of`.

## 7. Decision: the theme is read through accessors, resolved by the widget (decision 123)

`Theme`'s fields become private with accessors of the same name (`theme.background()`), and
`Widget.theme()` becomes the one resolver a widget paints from. Today it answers the process-wide theme;
it is the seam a per-subtree theme can be added behind later without breaking anyone, as the locale axis
was. `Theme.apply(Scene...)` joins it, the one call that switches the palette, invalidates each scene
and applies the theme's font (decision 117's rule, as API). The guides and the class Javadoc teach that
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
