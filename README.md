# Limn UI

A GUI toolkit for desktop Java built **from scratch**: no `java.awt`, no
`javax.swing`, no SWT. Its own graphics stack on top of LWJGL 3 (GLFW + OpenGL
3.3 core + stb), with a high-level API inspired by Flutter/Compose.

**[limn-toolkit.github.io/limn-toolkit](https://limn-toolkit.github.io/limn-toolkit)** —
the guide, the component gallery with every widget captured from a real build, and the
full API reference.

> Backend decision and post-OpenGL/macOS migration routes:
> [docs/adr/001-backend.md](docs/adr/001-backend.md)

## Architecture

```
limn-demo              kitchen-sink application + --screenshot mode
 └─ limn-components     Label, Button, TextField, Checkbox, ComboBox, Slider,
                          Spinner, TabbedPane, Dialog, ListView, ScrollView,
                          ScrollBar, MenuBar, PopupMenu, ImageView, Theme…
                          limn.components.chart (bar/line/donut charts)
     └─ limn-toolkit    limn.graphics    (2D Canvas, Paint, Path, text)
                          limn.scene       (widgets, events, focus, layout:
                                              Column/Row/Stack/Padding/SizedBox)
                          limn.concurrent  (Ui.post / Ui.async)
                          limn.sound       (AudioClip/Sounds, audio SPI)
                          limn.backend     (SPI: Backend / NativeWindow /
                                              Display / GpuRenderer)
                          limn.video       (VideoFrame/VideoStreamSource,
                                              MediaPlayer, video SPI)
                             ▲ implements the SPI
         limn-backend-lwjgl  GLFW + OpenGL 3.3 + stb + OpenAL

limn-demo also depends on the opt-in modules, which nothing else does; each is
an application's choice, not the toolkit's, so an application that wants none of
them pays for none of them:
 ├─ limn-icons-tabler   the Tabler icon set as an icon pack (limn.graphics.Icon)
 ├─ limn-theme-editor   a screen for AUTHORING a Theme: every tone on a colour
 │                        well, a preview, and a WCAG report. What comes out is a
 │                        Theme; an application that only wears one never ships
 │                        the screen that built it
 ├─ limn-video          Y4M reader + synthetic generator, pure Java, no native,
 │                        no third-party dependency at all
 └─ limn-video-ffmpeg   H.264/AAC in MP4, via a trimmed LGPL FFmpeg behind a JNI
                          shim. The only native payload here; it is NOT committed
                          and NOT built by Gradle (see scripts/build-ffmpeg.sh),
                          so it is simply absent until you build it
```

> `ScrollView`/`ScrollBar` are **components** (`limn.components`), not layout
> primitives of `limn.scene`, because they depend on the theme (the shared
> `ScrollBar`). The `limn.scene` layouts are Column/Row/Stack/Padding/SizedBox.

Only `limn-backend-lwjgl` may import `org.lwjgl.*`; AWT/Swing/SWT are forbidden
in every module. Both rules are enforced at build time by the
`checkArchitecture` task (wired into `check`).

## Requirements

- JDK 17+ (the build uses `--release 17`)
- GPU/driver with OpenGL 3.3+ (on macOS: 4.1 via Apple's compatibility layer)

## Build and tests

```sh
./gradlew build          # compiles, runs tests and the architecture check
./gradlew test           # tests only
```

## Running the demo

The demo opens the **kitchen sink** screen (all components) by default:

```sh
./gradlew :limn-demo:run                          # macOS/Linux
gradlew.bat :limn-demo:run                         # Windows
./gradlew :limn-demo:run --args="--scene tabs"     # another scene
```

The demo itself is the list; asking for a scene that does not exist prints every
name it does accept, which is the one copy that cannot go stale:

```sh
./gradlew :limn-demo:run --args="--scene ?"
```

A few worth starting with: `kitchen-light` (the default), `kitchen` (dark),
`list` (a virtualized list of 5,000 cards), `menu` (menu bar + context menu),
`files` (native dialogs, OS drag-and-drop, window icon and close veto),
`theme-editor` (build a palette and see it applied live), `icons`, `video`,
`charts`, `gltf`.

## Menus (`MenuBar`, `PopupMenu`, `Menu`)

`Menu`/`MenuItem` are the model (command, **checkable** item, submenu,
separator, enabled/disabled). `MenuBar` is the bar drawn in-scene (part of the
window chrome) with keyboard navigation (←→ switch menus, ↓/Enter opens).

A `PopupMenu` presents a `Menu` (context menu / dropdown) with **cascading
submenus** and check marks, always as a **native window** (undecorated,
floating and transparent, like the ComboBox popup) that can overflow the
owner window like an OS menu. It **steals keyboard focus** while open (arrows
navigate, Enter picks, Esc closes) and goes away when you click back on the
owner window, choose an item, or press Esc. The `MenuBar` dropdowns are native
windows too.

The menu is positioned relative to the **screen** and **never overflows the
visible area**: each column flips (opens upward / to the left) and then clamps
against the monitor's *work area* (`window.display().workArea()`); it always
stays on whichever screen the pointer is on. A single window is sized to the
bounding box of the cascade and re-fitted as submenus open/close.

`setModal(true)` makes the popup **block the owner window** (window-modal,
enforced by the backend as in `Dialog`): clicking the background is ignored +
beep, and the menu only closes on a choice or Esc. It stays native, with no
in-scene overlay.

Demo examples: the kitchen sink (default) has a **MenuBar** at the top
(File/Edit/View, with submenus and checkable items) and a **Menus** tab with a
dropdown off a button and a context menu (right click); there is also the
dedicated `--scene menu`.

## OS files and window extras (`FileDialogs`, file drop, icon/limits/close veto)

- **Native file dialogs**: `Backend.fileDialogs()` returns the neutral
  `limn.backend.FileDialogs` SPI (`openFile`, `openFiles`, `saveFile`,
  `chooseFolder`, with a `Filter` of glob patterns), implemented with
  **tinyfiledialogs** via LWJGL (no AWT): the platform's own chooser on
  Windows/macOS/Linux, blocking the UI thread while open (system-modal, like
  any native app). Headless backends return `FileDialogs.NONE`: every dialog
  resolves empty, as if cancelled.
- **OS drag-and-drop (in)**: files dragged from Finder/Explorer arrive as a
  `FileDropEvent` (paths + scene position) dispatched **bubbling from the
  widget under the pointer**; a drop target overrides `Widget.onFileDrop` and
  `consume()`s. Plumbed as `WindowInput.filesDropped` (GLFW drop callback), so
  it is queued/dispatched with the same per-frame ordering as every event.
- **Window extras**: `NativeWindow.setIcon(Image...)` (title bar/taskbar icon;
  no-op on macOS, which uses the app bundle's icon), `setSizeLimits(minW, minH,
  maxW, maxH)` in logical points (interactive resizes only), and
  `setCloseRequestHandler(BooleanSupplier)`: consulted on **user** close
  attempts (close button / Alt-F4 / Cmd-W); return `false` to veto, e.g. to
  show an "unsaved changes" dialog and close later via `requestClose()`, which
  always bypasses the veto.

Demo: `--scene files` exercises all of it (dialog buttons + a drop area + the
close-confirm switch), and the same content is the kitchen sink's **Files** tab
(`--scene kitchen-files` opens it for capture).

## Displays and resolutions (`Display`, `Resolution`)

Screen access is **normalized** into `limn.backend` value types, so every class
reads the information the same way instead of poking at the platform:

- `Resolution(width, height, refreshRate)` is a video mode (refresh `0` =
  unspecified);
- `ScreenRect(x, y, width, height)` is a rectangle in screen coordinates
  (bounds and work area);
- `Display` is a monitor: `id()`/`name()`, `isPrimary()`, `currentResolution()`,
  `availableResolutions()`, `bounds()`, `workArea()` (monitor minus the
  taskbar/dock) and `contentScale()`.

`Backend.displays()` / `primaryDisplay()` enumerate; and **a window knows which
display it is on** via `NativeWindow.display()` (the monitor containing the
window's center), reflected in `window.display().equals(someDisplay)`.
Fullscreen reuses these types: `enterFullscreen(Resolution)` in addition to the
integer form; and native popups clamp to `display().workArea()`. The demo lists
the displays at startup (name, current mode, mode count, work area, scale).

### Per-OS notes

- **macOS**: the `run` task injects `-XstartOnFirstThread` automatically
  (required for GLFW/Cocoa). From a distribution (`installDist`/`distZip`),
  export the flag: `JAVA_OPTS=-XstartOnFirstThread bin/limn-demo`. It is
  macOS-only, which is why it is not written into the start scripts.
- **Windows**: nothing special; use `gradlew.bat`. The natives
  (`natives-windows`) come via the LWJGL classifier.
- **Linux (X11/Wayland)**: nothing display-specific. Without a display (CI),
  use `scripts/screenshot.sh`, which falls back to `xvfb-run` +
  `LIBGL_ALWAYS_SOFTWARE=1` (Mesa/llvmpipe).

### Screenshot mode (visual verification without interaction)

```sh
./gradlew :limn-demo:run --args="--screenshot build/screenshots/kitchen.png --scene kitchen"
# options: --scene <name>  --scale <1.0|1.25|1.5|2.0>  --exit-after <ms>
scripts/screenshot.sh build/screenshots/kitchen.png   # with xvfb/llvmpipe fallback on Linux
```

Renders with an invisible window, reads the pixels via `glReadPixels` and
writes a PNG (`limn.graphics.Images`, pure Java, no native writer). On Linux
without a display, the script uses
`xvfb-run` + `LIBGL_ALWAYS_SOFTWARE=1` (Mesa/llvmpipe).

## Components (limn-components)

`Label` (measured ellipsis, wrap, icon), `Button` (states, icon,
primary/secondary), `Checkbox` (box and animated switch), `TextField`,
`PasswordField`, `TextArea` (draggable scrollbars), `ComboBox` (popup in a
translucent window), `ImageView` (icons/images), `ProgressBar` (determinate and
indeterminate), `TabbedPane` (tabs with left/center/right alignment),
`ListView` (virtualized list, below; uses the shared `ScrollBar`), `Dialog`,
`ColorPicker` and `ColorPickerButton`, and the `Theme`.

### Themes

Fifteen palettes ship (`Theme.builtins()`), and an application can build its own
without forking anything:

```java
Theme mine = Theme.builder("Ocean", true)
        .background(Color.rgb(0x0B1A24))
        .surface(Color.rgb(0x11242F))
        .primary(Color.rgb(0x4FD1C5))
        .onPrimary(Color.rgb(0x04141A))
        .deriveAccentStates()      // the ramp the built-in palettes were built from
        .deriveDisabled()
        .build();
Theme.setCurrent(mine);
```

`Theme.builder(name, dark)` starts from a working palette, `theme.toBuilder()`
from an existing one, and `Theme.Token` enumerates every tone so that
code treating them alike does not have to name all of them.

A palette carries **colour plus one metric**: `cornerScale`, how round the corners
are (`0` square, `1` the shipped ramp, up to `Theme.MAX_CORNER_SCALE`). Spacing and
type stay process-wide and identical in every palette. The boundary is not
arbitrary: a theme switch is a repaint, never a relayout, so a palette may only
carry a metric nothing *measures* from, and a radius is the only one that
qualifies.

`ThemeFormat.write` / `ThemeFormat.parse` move a palette through text, so a theme
a designer built can ship as a file beside the jar:

```
# Limn theme
name = Ocean
dark = true
cornerScale = 1.0
primary = #4FD1C5
```

Loading one back needs nothing from the editor module; that split is why the
format exists:

```java
Theme.setCurrent(ThemeFormat.load(Path.of("themes/ocean.limntheme")));

try (InputStream in = App.class.getResourceAsStream("/themes/ocean.limntheme")) {
    Theme.setCurrent(ThemeFormat.load(in));   // a theme shipped inside the jar
}
```

The screen that *authors* one lives in **`limn-theme-editor`**, which nothing
depends on. See that module's `ThemeEditor`, and
`./gradlew :limn-demo:run --args="--scene theme-editor"`.

`limn.components.chart` adds **charts**: `BarChart` (grouped or stacked,
vertical or horizontal), `LineChart` (curves, area fills, markers, gaps) and
`DonutChart` (or a pie, with a real widget in the hole). Feed one labels and a
`ChartSeries` and it picks its colours from a validated palette, rounds the
scale outward, draws a legend, animates the values in, reports the datum under
the pointer in a tooltip and calls back on click, every one of which is a
setter away from being changed. See `--scene charts`.

`Dialog` has **two display modes** (`setDisplayMode`). `NATIVE_WINDOW` is the
default: a separate floating native window, with `WindowStyle` (incl.
`DECORATED` with the OS frame) and optional `setAlwaysOnTop`. `IN_SCENE` is
**internal**, an overlay drawn inside the owner window: scrim + card, no
extra window. It is **draggable**: dragging the card body (where
buttons/labels don't consume the click) moves the native window
(`setScreenPosition`) or the internal card, which is the drag affordance an OS
title bar would provide. Three modality scopes as a native window: `show()`
(window-modal), `showToolkitModal()` (toolkit-modal) and `showNonModal()`
(non-modal). In `IN_SCENE`, the overlay blocks the window's own content and the
backend blocks the **sibling windows** (via `pushSceneModal`): `show` locks the
owner window's popups, `showToolkitModal` locks them all; the owner window
stays interactive (it hosts the overlay). By default the internal modal is a
real modal: clicking the scrim is **ignored + beep** (identical to the native
modal, via `Backend.signalModalBlocked`); `setDismissOnScrim(true)` turns it
into a dismissable overlay (closes on outside click). Resolves via
`CompletionStage`, with no nested event loop.

`Button` focus is drawn **outside** the component (a ring with 1px of
clearance, never over the fill).

Windows support **exclusive fullscreen**: `NativeWindow.enterFullscreen(w,h,hz)`
(pass dimensions ≤ 0 to keep the monitor's current resolution, with no mode
switch; or a specific resolution, e.g. 1280×720, which does a mode switch) /
`exitFullscreen()` / `isFullscreen()`.

## Virtualized list (`ListView`)

`ListView` recycles its rows like a `JTable`/`RecyclerView`: only the rows in
the viewport get a widget, so a million rows cost the same as twenty (layout
and paint are `O(visible)`, proven by a test with 1,000,000 rows → ≤ 9
materialized widgets). It supports **variable heights** (e.g. small headers
between larger cards) **without a global cache or binary search**: it keeps an
**anchor** (which row sits where) and walks a few rows from it per frame,
measuring on demand; the scrollbar thumb is estimated from the average height.

You control caching through the `Adapter`: the list asks for `rowAt(index)`
(the already-populated widget; it may be a reused instance of yours) and hands
it back via `recycle(widget)` when it scrolls off-screen, so you do per-type
pooling and rebinding, or create from scratch (simpler). The list only manages
the tree/positioning. Rows can contain **interactive, focusable** widgets
(buttons, images, switches). The wheel scrolls; the list is focusable and
↑↓/Home/End/PgUp/PgDn move a highlighted selection (auto-scrolling), Enter
activates, click selects; a cell that scrolls away holding focus returns focus
to the list. Demo: `--scene list` (and the **List** tab of the kitchen sink)
with headers + cards of different heights.

## Scrollbar (`ScrollBar`)

A **single** scrollbar (`limn.components.ScrollBar`) shared by every scroller
(`ListView`, `TextArea` and `ScrollView`), so they all have the same look and
behavior. It is **themed** (the thumb uses `Theme.textMuted`, visible in both
light, where the thumb is dark, and dark themes), **draggable** (drag the thumb
or click the track to page) and **thin when idle / wider on approach** (ready to
grab). Policy (`setPolicy`): `HIDDEN`, `ALWAYS`, `ON_SCROLL` (appears while
scrolling and fades) or `AUTO` (also while the pointer is active over the host).
The bars are **overlays** (they don't reserve a lane that would clip content):
the `TextArea` uses `ALWAYS`; `ListView`/`ScrollView`, `AUTO`.

## Audio (limn.sound)

The `limn.sound` package follows the same dependency inversion as the rest: the
API is neutral (`AudioClip`, `Sounds`, the `AudioEngine`/`AudioDecoder` SPIs)
in `limn-toolkit`; `limn-backend-lwjgl` provides the **OpenAL** engine and the
file decoder, installed at startup (mirrors `limn.graphics.Images`).

- **Synthesis**: `AudioClip.tone(freq, duration, amplitude)` generates a sine
  with a raised-cosine envelope (no clicks). It is the primitive behind the
  system **beep**, which is a `tone(...)` played through this package.
- **Loading**: `Sounds.load(path)`/`fromResource(...)`/`decode(bytes)` decode
  **WAV** (PCM 8/16/24-bit and float32, mono/stereo), **Ogg Vorbis** (via
  stb_vorbis) and **MP3** (via JLayer, pure Java, no natives; note: JLayer is
  LGPL-licensed). MP4/AAC are **not** supported: no bundled decoder handles
  them.
- **Streaming**: `Sounds.stream(path, options)` plays long tracks (OGG, MP3,
  16-bit PCM WAV) decoded incrementally on a service thread: ring buffers
  instead of a whole decoded clip on the heap.
- **Playback**: `Sounds.play(clip[, gain[, loop]])` or
  `Sounds.play(clip, PlayOptions...)` (pitch, pan, 3D position, mixer bus,
  steal priority) mixes multiple voices through a source pool, caches the
  device buffer by `AudioClip` identity (replaying doesn't re-upload) and
  returns a `Playback` (stop/pause/resume/gain/pitch/pan/position/isPlaying).
  Bus and master gains (`Sounds.setBusGain`/`setMasterGain`) apply live.
  Best-effort: with no audio device (headless/CI) it becomes a silent no-op.

Demo: the kitchen sink's **Actions** tab has "Play sound (synthesis)", the 3
dialog scopes, the "Internal (in-scene)" / "Always on top" / "Decorated"
switches and the "Fullscreen (native)" / "Fullscreen 1280×720" buttons.

**Video**: `VideoView` plays a `VideoStreamSource` through `MediaPlayer`, which
paces the pictures against the soundtrack when there is one. Decoders are the
**application's** choice, not the backend's; nothing is installed until you call
`Videos.installDecoder`. `limn-video` ships a pure-Java Y4M reader and a synthetic
generator; `limn-video-ffmpeg` adds H.264/AAC in MP4 behind a JNI shim, and is
absent unless it has been built, in which case it says so rather than failing. See
`docs/design/video.md`, and the `video` and `kitchen-video` scenes.

## Features

- **Foundation**: multi-module Gradle build, GLFW window + clear color,
  event-driven render loop, `Ui.post`/`Ui.postDelayed`/`Ui.async`/
  `Ui.checkUiThread` with tests, HiDPI detection (fractional content scale),
  `--screenshot` mode, architecture check in the build.
- **2D Canvas**: immediate-mode `Canvas` API (rects, round rects with
  per-corner radii, circles/ellipses, lines, `Path2D` with Béziers,
  linear/radial gradients, opacity, clip, save/restore/transform); geometry
  batcher with one dynamic VBO and **one draw call per frame**; analytic AA via
  SDF + `fwidth` in the fragment shader (GLSL 330 restricted to the subset
  portable to ES 3.00); fringe AA for path fills; **pixel snapping** of thin
  strokes (a crisp 1px logical line at 1.0/1.25/1.5/2.0, pixel-verified);
  screenshot scenes with `--scale`.
- **Backdrop effects**: a shape drawn out of the pixels behind it, via
  `Canvas.fillBackdropRoundRect` with a closed set of `BackdropEffect`s:
  `Clear` (refraction at the rim, from the shape's own SDF gradient, with
  optional chromatic dispersion), `Wash` (saturation) and `Pixelate`
  (redaction: one sample per cell). No blur and one sample per pixel, so it is
  affordable over video; the demo's video transport is one. Costs a batch
  break and a copy of its own bounds; degrades to a flat tinted panel on a
  renderer without support. See
  [ADR 019](docs/adr/019-a-shape-can-be-made-of-what-is-behind-it.md).
- **Image export**: `Images.encode`/`Images.save` over an ordered list of
  installed `ImageEncoder`s (first match wins; an unclaimed request names every
  encoder it asked). PNG is built in and **pure Java**: no backend, no window
  and no GL context, so a headless test can write a reference image, and the
  bytes are deterministic. Pixels come off the GPU through `ReadableSurface`,
  whose two reads are named apart because they are different pictures:
  `readDisplayReferred` (what the screen showed) and `readSceneReferred` (the
  linear, unclamped light the pass wrote). `Viewport3D.captureNext` and
  `NativeWindow.captureNextFrame` are the two ready-made ones. See
  [ADR 018](docs/adr/018-an-image-leaves-through-an-encoder-and-a-readback-names-its-colour-space.md).
- **Text**: stb_truetype + dynamic glyph atlas (shelf packing, 1024² GL_R8
  pages, cached per font/physical-size/glyph, rasterized at
  `fontSize × scale`, **bitmaps never scaled**); `drawText` (baseline, full
  code points with surrogates, legacy kerning) and `measureText`
  (non-quantized logical metrics, scale-independent); text shares the same
  batch as shapes (an atlas page switch is the only batch-breaker); embedded
  **Roboto** font (Apache 2.0, license next to the TTF); `--scene text` scene
  with specimens at 4 scales. Out of scope for v1 (documented): complex
  shaping/RTL/ligatures/font fallback.
- **Scene (widgets, events, focus, layout)**: base `Widget` class (bounds,
  visibility, enabled, focus, event-driven invalidation), immutable events
  with bubbling and `consume()` (mouse
  enter/exit/move/drag/press/release/click/wheel, keyboard, char),
  **coalescing** (consecutive moves collapse, scroll accumulates, clicks and
  keys are never dropped), hit-testing, click synthesis, hover, focus with
  **Tab traversal** in layout order and click-to-focus, slow-handler
  instrumentation (8 ms budget), synthetic RELEASE for the pointer **and every
  key still held** when the window loses focus (no key stuck down after an
  alt-tab); animation tickers on **two clocks**: every `dt` clamped to
  `Scene.MAX_TICK_SECONDS` (0.25) so a GC pause or a window drag cannot hand a
  simulation four seconds at once, and **scene time** (`setTimeScale`,
  `setPaused`) for what the app animates while the toolkit's own chrome and
  lifecycle fades stay on wall time, so a paused app still closes its dialogs;
  a paused scene stops asking for frames and idles at zero; two-phase layout
  `measure(constraints)` → `layout(bounds)` with caching; **Column/Row**
  layouts (gap, alignment, justify, flex via `Expanded`/`Spacer`), **Stack**,
  **Padding** and **SizedBox** (the scroll container is the `ScrollView`
  component, above); deterministic per-frame order: input → `Ui.post` →
  animation → layout → render; interactive demo scene `--scene widgets`.
- **Components (Theme, Label, Button, Checkbox)**: central `Theme` (colors,
  radii, typography, spacing; light/dark built in, runtime switching, with zero
  hardcoded colors in components); `Label` with `textOverflow=ELLIPSIS` by
  real glyph measurement (binary search over code points, surrogate-safe), H/V
  alignment and optional word wrap; `Button` with
  normal/hover/pressed/disabled/focused states, `onAction(Runnable)` and
  Enter/Space activation; `Checkbox` in classic-box and switch variants with a
  slide animation interpolated by the frame clock (Scene tickers); `TextRuler`
  for text measurement in the layout phase (deterministic fake in tests).
- **Components (TextField, PasswordField, TextArea, ComboBox)**: pure,
  testable `TextEditModel` (cursor/selection by **grapheme cluster**: combining
  marks and ZWJ emoji never split; sticky column; line-start index so line
  lookups never rescan the buffer; bounded **undo/redo** with coalesced
  typing/deleting runs); `TextField` with blinking cursor, mouse and
  Shift+arrow selection, Home/End, Ctrl/Cmd+A/C/V/X via the **GLFW clipboard**
  (mocked in tests), Ctrl/Cmd+Z / Shift+Ctrl/Cmd+Z / Ctrl+Y undo/redo, placeholder
  and horizontal scroll; `PasswordField` whose dot is **drawn, not typeset**
  (one fraction of the body font at every control size, so it needs no glyph
  coverage) with a reveal toggle (copy/cut blocked while masked);
  multiline `TextArea` with **draggable**
  vertical and horizontal scrollbars and wheel support, arrow-key navigation
  across lines; **a `ComboBox` whose popup is a translucent native window**
  (`GLFW_TRANSPARENT_FRAMEBUFFER`, undecorated, floating, doesn't steal
  focus): the theme's rounded corners composited over the desktop,
  pixel-verified (corners alpha=0, panel alpha≈0.94).
- **Modal Dialog, Images/Icons, ProgressBar**: modal `Dialog` in a **separate
  native window**, with configurable `WindowStyle`: `DECORATED` (uses the OS
  frame, opaque), `UNDECORATED_OPAQUE` (solid card) or
  `UNDECORATED_TRANSLUCENT` (glassy panel with see-through rounded corners;
  default). `show(scene)` returns `CompletionStage<Result>`, **no nested event
  loop**; modality is enforced by the backend (locked windows ignore input,
  are **dimmed by a scrim** and play a feedback **sound** on a blocked click,
  via OpenAL/LWJGL). Scope: `show(scene)` is **window-modal** (locks only the
  owner window); `showToolkitModal(scene)` locks all. Modals stack (only the
  top is usable; non-modals are released after the stack closes). Closes via
  ESC/button. **`Image`/icon** support: `Canvas.drawImage` API (with tint for
  icons that recolor with the theme) via stb_image + per-window GL textures;
  `ImageView` widget (fit COVER/CONTAIN/FILL/NONE) and icons on `Button` and
  `Label`. Horizontal `ProgressBar`, determinate and indeterminate (animated
  sweep). Multiple windows and light/dark theme.
- **TabbedPane + kitchen sink**: `TabbedPane`, tabs with a selection
  indicator, optional icon, keyboard navigation and **left/center/right
  horizontal alignment** (`--scene tabs`); **kitchen sink** screen
  (`--scene kitchen`) gathering a dozen tabs' worth of components under one
  toolbar, with a theme picker and a "Load data" button that fires a ~2 s
  `Ui.async` showing a spinner (indeterminate ProgressBar); the UI stays fluid
  (animations running, fields editable) during the background work. The widget
  sets it does *not* carry each have a scene of their own; `--scene ?` lists
  them. Screenshots of the named scenes generated and inspected (`kitchen`,
  `kitchen-light`, `tabs`, `textfield-selected`, `ellipsis`, `textarea-scroll`,
  open dialog, loading).

## Threading in one sentence

The UI thread is the main thread (GLFW/Cocoa require it); heavy work goes to
the worker pool with `Ui.async(supplier).thenAccept(uiUpdate)`: the callback
returns to the UI thread, and any widget mutation off it throws
`IllegalStateException`.

## License

Limn is open source under the **Apache License 2.0**. See [LICENSE](LICENSE).
Commercial use, modification and redistribution are permitted; the license
includes an explicit patent grant. Third-party components bundled with or
resolved by Limn (fonts, LWJGL, JLayer) keep their own licenses, listed in
[NOTICE](NOTICE).

Two practical notes for downstream users:

- `limn-toolkit` and `limn-components` have **no dependencies** beyond the JDK:
  Apache 2.0 is the whole story there.
- MP3 support in `limn-backend-lwjgl` comes from JLayer (**LGPL 2.1**), kept as
  an isolated jar behind the `AudioDecoder` SPI. Exclude that one dependency if
  your distribution needs to avoid LGPL obligations; WAV and OGG keep working.
