# Changelog

What changed between releases, and what to change in your code when you move. Newest first.

Each section is named after the release it follows. It is complete on the day the next version is
tagged, and no release has to edit this file to rename it; the release on GitHub and the version on
Maven Central say which number that was.

## After 0.9.0

### Fixed

- A menu's separators at its start or end, or right after another separator, are no longer drawn,
  as `Menu.addSeparator` always promised, so a menu built with conditions shows no double or
  dangling rule. The model keeps them.
- A submenu row disabled while it was highlighted no longer opens from the keyboard.
- Editable text reads a lone carriage return, the classic Mac OS line break, as a line break;
  it was deleted, joining the lines around it.
- A chart whose values reach near the largest double no longer throws on every frame.
- A chart whose value-axis labels are wider than the chart itself (a long custom format) draws: the labels are cut with an ellipsis, upright to at
  most 35% of the chart's width, as a sideways chart's category labels already were, and sideways
  also to the room between two ticks. The chart used to give that width to the labels and draw
  nothing at all.
- A glTF texture or material that names an image or texture the file does not have is refused
  with an `IllegalArgumentException` when the file is read, rather than failing later in the
  texture decode; a texture with no image at all, which glTF allows, loads as no texture.
- A `RenderTarget` disposed twice no longer deletes a GL object the driver has since given to
  something else.
- A `ListView` given new items, or a `Table` new rows, before its first layout opens at the top, as
  `setItems` and `setRows` say. A selection set earlier scrolled the new ones to its old index.
- A glTF triangle list whose index count is not a multiple of three loads without its unfinished
  last triangle, which a draw skips anyway. A mesh without normals threw
  `ArrayIndexOutOfBoundsException`.
- A subtitle window drops every cue that ended long ago. One long cue near the front kept all the
  short ones after it, and the window grew with the whole script.
- Stopping a backend forgets the shared loads of `Images.loadShared` and `Sounds.loadShared`. A load
  still in flight left a future that never completes for the next backend to ask for the same file.
- The process-wide list behind `FrameMetrics.processInstances()` is pruned whenever a scene is
  made, not only when that method is called. It kept an entry for every popup ever opened.
- A `Table` sorted by the table itself re-sorts when its language changes, since a text sort is the
  language's order (German puts "ä" before "z", Swedish after it). The cursor stays on its row.
- A `Table`'s footer strip goes when the only column with a footer is hidden, and comes back when it
  is shown. It stayed reserved and empty until a refresh.
- A `Column.minWidth` set after the user dragged the column holds the dragged width too.
- A range `DatePicker` clears the period in its grid when one end is cleared.
- VoiceOver's point lookup answers the sibling painted on top where two overlap, and skips a child
  that is not showing, as Windows and AT-SPI already did.
- An MP3 whose ID3v2 tag claims more bytes than the file holds is refused before anything is
  allocated. Sixteen bytes made the decoder allocate 268 MB, and a looping stream again per loop.
- A WAV with no format chunk, no channels or no data throws `IllegalArgumentException` as a
  malformed file, not `UnsupportedOperationException`.
- A window on a monitor faster than the primary animates at that monitor's rate. The loop paced
  every window to the primary's refresh, so a window on a 144 Hz monitor beside a 60 Hz primary
  ran at 60 fps with judder. The rate follows the window when it is dragged to another monitor, and
  where the window's monitor cannot be known (Wayland) the fastest monitor's rate is used.
- An animation on a 120 Hz ProMotion display runs at 120 fps; it ran at about 111. A macOS swap
  sometimes returns early and makes up for it on the next, and the loop took each early one for a
  swap that had not waited and slept. It now sleeps only after two such swaps in a row, which a
  swap that really does not wait always gives.
- An Ogg Vorbis file is decoded to the audio it holds, whatever length its last page claims. A
  quarter second claiming 2^30 stereo frames threw, and a smaller false length made the decoder
  allocate gigabytes.
- A malformed glTF is refused with an `IllegalArgumentException` that names what was wrong (the
  key, the type it had to be, the list an index missed), as the loader always promised. Truncated
  or mistyped files escaped as `ClassCastException`, `NullPointerException`,
  `StringIndexOutOfBoundsException` and others; a node naming a mesh the file does not have is
  now refused when it is read instead of failing when the scene is built.
- `TextField` and `TextArea` leave every key to the input method while a composition is under way.
  On X11 Enter, Backspace, the arrows and shortcuts reached the field during a composition and
  acted on the text already committed; macOS and Windows never delivered them.
- The `ColorPicker`'s cursor ring no longer leaves a stale arc when it moves away from an edge or
  a corner of the colour square. It paints past the square by design, centred on the exact colour,
  and now says so to the repaint.
- `enterFullscreen()` with no mode enters macOS's own fullscreen, the green button's Space, and
  leaves the display in its mode. It asked GLFW for the current mode, which a Retina display does
  not list, so a MacBook Pro switched from 1728 × 1117 at scale 2 to 1920 × 1200 at scale 1 and
  the UI thread stalled 595 ms; now the window fills its Space with the UI thread held under 100 ms.
  `isFullscreen()` also reports the fullscreen a person enters with the green button or
  Control-Command-F. A named mode (`enterFullscreen(width, height, rate)` or a `Resolution`) still
  switches the display, on every platform, for games. Windows and X11 already kept their mode.
- A focus ring at the edge of a layout is no longer bitten off when something beside the layout
  repaints: every layout that does not clip now declares the ring's reach, so a repaint that lands
  just outside it paints it too.
- No colour shows outside a rounded border's corners any more. Every bordered surface filled the
  whole box and stroked its border half a point in with the same radius, so at the corners the fill
  reached past the border: most visible on the `ColorPicker`'s gradients and the
  `ColorPickerButton`'s swatch, where saturated pixels sat outside the grey outline.
- A partial repaint that cuts across a rounded surface draws exactly what a whole frame draws
  there. The rounded clip took its corners from the repainted rectangle, so dark arcs appeared
  inside a rounded field or ramp wherever a repaint region crossed it.
- A focus ring or the `ColorPicker`'s cursor at the side edge of a dialog's body, or at its bottom
  when the body fits, is drawn whole; the body clipped it although nothing scrolled there.
- An in-scene `Dialog` stops repainting once it has settled; it redrew the strip where its scroll
  bar lives on every frame, so the loop never idled while one was open.
- `DateField` and `SearchField` leave every key to the input method while a composition is under
  way, as `TextField` and `TextArea` do; Enter ran a search and the arrows stepped a date segment.
- Charts and tables write the separators and minus sign that go with the digits they show: under
  a language whose default digits the toolkit does not write (bare Arabic, Bengali and some 40
  others) a number mixed Latin digits with Arabic separators or kept trailing zeros. 1,023 of the
  1,069 locales are unchanged byte for byte.
- `Stack.alignment` and `Expanded.atLeast` check the UI thread and refuse null or a non-finite
  number, and `Flex.gap` refuses NaN and infinity, as the other layout setters do.
- A `Spinner` being edited refuses a step over text it cannot read the way Enter refuses it: Up,
  Down, Page Up, Page Down or a screen reader's increment over `1.2.3`, German `1.234` or an emptied
  field flashes the border, says "“1.234” not accepted, still 7", and leaves the value where it was.
  The step went ahead from the old value and wrote over what was typed with no sign. The arrow
  buttons still commit the text first and then step.
- A value an assistive technology sets as text and a `Spinner` refuses flashes the border, as a
  refused typed number does; it was only announced.
- A locale's script decides its digits where CLDR says it does, and three-letter language codes
  are read: Sindhi and Kashmiri in Devanagari (`sd-Deva`, `ks-Deva`) write Latin digits, not Arabic
  ones; Punjabi in Arabic script (`pa-Arab`) writes Persian ones; Central Kurdish (`ckb`) writes
  Arabic-Indic digits, Mazanderani and Northern Luri Persian ones, Bhojpuri and Rajasthani
  Devanagari. Fifteen locales were answered wrong.
- `I18nString.format` and `PluralString.format` write a number argument in the digits the toolkit
  shows, with the separators that go with them. Under bare Arabic a sentence mixed a total in
  Arabic-Indic digits with Latin ones everywhere else on screen.
- A `ComboBox`'s list, a `DatePicker`'s calendar, a modal `Dialog` and the `ColorPickerButton`'s
  picker open inside a window that is fullscreen, as `PopupMenu` already did and `DisplayMode`
  promised. A window of their own minimized an owner fullscreen on a monitor as soon as it took the
  focus; over macOS's own fullscreen the owner already covers the screen, and on a Mac without a GPU
  the colour picker as a window held the UI for up to 3.7 s against 0.7 s drawn inside.
  `displayMode()` says which happened; a non-modal dialog still opens a window of its own, as does a
  dialog on Wayland.
- An open menu, `ComboBox` list or `DatePicker` calendar that is a window of its own closes, choosing
  nothing, when its owner enters or leaves fullscreen, as the platforms' own menus do; a native modal
  `Dialog` (the `ColorPickerButton`'s picker too) moves into the fullscreen window with its typed
  text and focus. They stayed as windows over an owner that had changed under them.
- A fill inside a rounded border no longer shows past it, and no background shows between them, at
  fractional positions: the renderer moved the border to whole device pixels and left the fill where
  it was. 88 such pixels in 16 positions at 1x, 1.25x and 2x read 0 on every machine measured.
- A `ScrollView` that scrolls both ways and, in one layout, moves and starts to overflow repaints
  the focus ring it drew beside its old place; the ring stayed on screen.
- A locale's text direction comes from its script, then its region, then its language, as CLDR
  says: Punjabi in Pakistan (`pa-PK`), Uzbek in Afghanistan and Kurdish in Iraq read right to left,
  Sindhi in India left to right. 78 locales were answered by their language alone.
- A legacy or three-letter language code (`fra`, `deu`, `pes`, `heb`, `tha`, …) is read as the
  language it stands for everywhere the toolkit decides by language: plural forms, translations,
  the first day of the week, dates and the names in them, sorting, upper and lower case, numbers,
  where a line may break, and the language a Linux screen reader is given. `fra` wrote "2026 Mar 1"
  for "1 mars 2026", `tur` upper-cased "istanbul" without its dotted İ, and `tha` broke Thai
  mid-word.
- A region named by its UN number (`de-276`, `ar-818`, `pa-586`) is read as the region the number
  stands for, for its week, its digits and its direction; Java keeps the number, and nothing
  recognised it. A language named alone by a three-letter code takes the week of the region it
  most likely means: Central Kurdish (`ckb`) starts on Saturday as Iraq does, where 4,304 such
  languages had the United States' week.
- Text in the Garay and Sidetic scripts runs right to left.
- `jbang` can run the demo and the theme editor from Maven Central again. Their POMs named, for
  every platform, a `lwjgl-egl` natives jar LWJGL does not publish, so 0.9.0 of both failed to
  resolve anywhere (`Could not find artifact org.lwjgl:lwjgl-egl:jar:natives-macos-arm64:3.4.3`);
  the fix reaches Central with the next release, and 0.9.0 stays as it is.
- A retired two-letter region (`SU`, `YU`, `ZR`, `AN`, …) is read as the region ICU reads it as, for
  its week, digits and direction.
- A key pressed while a menu bar walks from one menu to the next reaches the next menu: Right twice
  in quick succession walks two menus; the second press was dropped.
- A window blocked by a modal dialog, and one holding a dialog drawn inside it, costs no more to draw
  than it did unblocked on a machine without a GPU: the dimming layer took as long as every widget
  of the window together (366 ms at 2604 × 1850 on the software renderer, now 10 ms), and a partial
  repaint drew it over the whole window.
- Rounded borders and fills cost a fraction of what they did on a machine without a GPU: a form of
  fields and buttons drew a full frame in 361 ms on macOS's software renderer and now does in 110 ms,
  a card with a border in 6 ms instead of 104. The shape is drawn in pieces, so the per-pixel work
  runs only where its edge can be; on a GPU the frame is still one draw call and its pixels are the
  same but for rounding in about 0.01% of them.
- Linux screen readers get the application's language from `Application.GetLocale`, which answered
  nothing.
- `limn-test`'s headless window has a clipboard: a copy in a text field of a scene bound to one
  threw `NullPointerException`.
- A window gets the keyboard back as soon as an item of its menu is chosen, so a Tab pressed right
  after moves its focus; the key went to the fading menu and was lost, and on macOS the window did
  not get the keyboard back at all. A menu that is closing takes no more input, so a second Enter
  or click during its fade no longer runs the item twice, and a key typed while a dismissed menu
  fades reaches the window.
- Leaving fullscreen on GNOME brings the window back at the size and place it had; it came back
  maximized, or 37 points shorter, depending on where the desktop had first put it.
- A `DateField` tells the input method where the segment being typed is, so a Japanese or Chinese
  candidate window opens under it. It had no anchor.
- A legacy or three-letter language code writes the digits of the language it stands for: `pes`
  and `prs` Persian digits, `arb-EG` Arabic-Indic ones, `npi` and `mar` Devanagari. Java keeps
  these codes as they are, and they all answered Latin digits.

### New

- `Wrap`, a layout that places its children along a run in reading order and starts a new run
  below when the next one does not fit: tags, filters, a toolbar that must not overflow. It takes
  `Row`'s `gap`, `mainAlignment` and `crossAlignment`, adds `runGap` between runs, mirrors in a
  right-to-left subtree, and refuses an `Expanded`, which means nothing there.
- `Grid`, a layout of a fixed number of columns filled row by row in the order children are
  added, so Tab and a screen reader follow what is on screen. Each column is as wide as its widest
  cell, one column can take the leftover width (`expandedColumn`), and each row is as tall as its
  tallest cell, aligned by baseline by default: a caption beside its field lines up. There are no
  spanning cells, explicit positions or fixed tracks; a `SizedBox` gives a cell a size.
- `Images.setTextureBudget` moves the bytes of image textures each window keeps, counted as the
  GPU holds them, mipmaps included. The default is 256 MiB (`Images.DEFAULT_TEXTURE_BUDGET`); past
  it the pictures drawn least recently are released at the end of a frame, never one the frame
  drew. The cache used to release a picture only after 300 frames unused, so an application
  showing a new photo each frame could hold gigabytes.
- `paintOverflow(float)` on every layout and wrapper (`Row`, `Column`, `Stack`, `Wrap`, `Grid`,
  `Padding`, `Expanded`, `SizedBox`, `TokenBox`) says how far its children may paint past it, and
  `paintOverflowFromTheme()` goes back to the default: the theme's focus-ring reach
  (`Theme.focusRingReach()`, 3 pt). A child that paints farther, as the `ColorPicker`'s cursor ring
  does, is declared on the layouts above it.
- `ScrollView.paintOverflow(float)` and `paintOverflowFromTheme()`: a scroll view lets its content
  paint that far past the viewport on an axis it does not scroll, or one whose content fits, so a
  focus ring at the edge of a dialog's body is drawn whole. The default is the theme's focus-ring
  reach; a dialog's body uses its card's padding.
- `Canvas.fillRoundRectWithBorder`, `drawRoundRectBorder`, `fillRoundRectInsideBorder` and
  `clipRoundRectInsideBorder` draw a bordered rounded surface the way every widget now does: the
  border concentric with the box and the fill kept under the border's centre line.
- `I18n.observeUnresolvedKeys` tells an application which keys no bundle translates, so a typo or a
  file that fell behind stops showing its English silently. Each key is reported once per
  language, as an `UnresolvedKey`; with no listener, each is logged once. English locales, and a
  plural form the language covers with its own `other`, report nothing.
- `GltfLoader.setElementBudget` moves the ceiling on what one glTF load may allocate, for the whole
  process. The default stays 2^26 elements (`GltfLoader.DEFAULT_ELEMENT_BUDGET`), which refuses a
  mesh without normals of about 2.8 million triangles; the ceiling is the defence against a small
  hostile file asking for gigabytes, so raise it for models you trust.
- The public API says where null is allowed: every exported package is `@NullMarked` (JSpecify),
  and a parameter or result that may be null is `@Nullable`, so a nullness checker or an IDE warns
  where a call passes or ignores one. `org.jspecify:jspecify` 1.0.1 comes with `limn-toolkit`, the
  backend, `limn-test` and `limn-video-ffmpeg` as an API dependency (`requires static transitive`
  in their modules), and nothing needs it at run time.
- `I18n.canonical(Locale)` answers a locale the way the toolkit reads it, with legacy and
  three-letter language codes and numbered or retired regions replaced as ICU replaces them
  (`jpn` is `ja`, `prs-IR` is `fa-IR`, `de-276` is `de-DE`), so an application that picks fonts,
  resources or layout by language decides as the toolkit does.
- `StubWindow` in `limn-test` has a public `fullscreen` field, which `enterFullscreen` and
  `exitFullscreen` set and report to the scene, so a test can open a surface over a fullscreen
  window or change fullscreen under one.
- `Scene.observeWindowFullscreen(Consumer<Boolean>)` tells an application when its window enters or
  leaves fullscreen, however it happened: a call, the green button or its menu on macOS. A backend
  reports it through `WindowInput.windowFullscreenChanged`.
- `NumberFormats.scientific(maxDecimals)` writes a number in scientific notation in the current
  language's terms: `1.2E25` in English, `1,2E25` in German, `1,2×10^25` in Swedish, with the
  language's own digits and minus sign.

### Changed

- The `ColorPicker`'s hue thumb is the channel rails' thumb laid across the ramp: it stands proud
  of the ramp on both sides, and it is hollow, with the hue it marks in its middle. It was a thin
  bar inside the ramp that covered the hue it pointed at.
- A number a user types into a `Spinner` and the spinner refuses is announced to screen readers,
  politely, in the spinner's language: "“1.234” not accepted, still 7". A value an assistive
  technology sets as text and the spinner refuses is announced the same way, since no platform can
  report the refusal itself. The sentence is translated into the 21 shipped languages.
- A WAV or Ogg Vorbis file whose sample rate is above 384 kHz is refused with
  `UnsupportedOperationException`: past about 480 kHz OpenAL plays it slower than it should, and a
  rate of zero or one that overflowed is refused as malformed. The audio engine answers
  `Playback.NONE` for such a stream instead of throwing, as it does for any source it cannot play,
  so a film with such a soundtrack still shows its pictures.
- `Resolution`, `Display` and `NativeWindow.enterFullscreen` document their sizes as screen
  coordinates, which is what they always were: pixels on Windows and Linux, points on macOS, where
  a HiDPI mode's framebuffer is that size times `contentScale()`. They claimed physical pixels.
- A `Spinner` writes and reads its number in the language in effect: German shows `0,50`,
  Swedish a true minus sign (`−1,5`), Arabic and Persian their own separator, never grouped.
  What a user types is read the same way, with the old tolerances kept (a leading `+`, the hyphen
  for a minus, a comma or, where the language does not group with points, a point for the
  separator). German `1.234` is refused instead of read as a little over one, and a refused
  number now flashes the field's border in the danger colour before the value reverts.
- An hour typed or stepped into an empty twelve-hour `DateField` takes the half of the day nearest
  to now: at 9 AM "1230" is half past noon, and at 1 AM "1130" is the evening just gone. It used to
  take the clock's own half, which made that 9 AM entry half past midnight.
- `Insets` and `SizedBox` refuse a size that is NaN or infinite with an `IllegalArgumentException`
  that names the side or the axis. NaN insets made a `Padding` measure NaN, and a `SizedBox` took
  NaN as a free dimension and infinity as "as large as allowed". To fill the space, leave the
  dimension `SizedBox.UNSET` under a stretching parent, or use `Expanded`.
- A table `Column`'s setters (`width`, `minWidth`, `weight`, `visible`, `sortable`, `comparator`,
  `align`, `footer`, and `footerSum` through `footerCount`) check the UI thread, as every other
  public mutator does. Build columns on the UI thread; with no backend started a setter throws
  `IllegalStateException`. The static factories (`Column.text`, `numeric`, …) do not check.
- A window put in fullscreen with `enterFullscreen()`, without a mode, stays fullscreen when another
  application takes the focus, on Windows and Linux, as a browser's fullscreen does: Alt-Tab brings
  the other application in front of it instead of minimizing it. A fullscreen at a mode you name, the
  path for games, still minimizes when it loses the focus.
- A `MenuBar` takes the whole width it is offered, as a native menu bar and Limn's own tab strip do,
  so in a plain `Column` it spans the window with its bottom rule; it used to stop after its last
  title unless the column stretched it. Past the titles the strip is not the bar's: a press there
  reaches what is beneath and takes no focus. Where the width is unbounded, as in a `Row`, it stays
  as wide as its titles.
- `DatePicker.displayMode()` answers the presentation asked for while the picker is closed, as
  `ComboBox.displayMode()` does. It answered null.
- A chart writes values of 1e15 and more in scientific notation by default, on its value axis and
  in its tooltip and the names a screen reader hears: `0, 2E299, … 1E300` rather than 401
  characters a tick, and `1.235E20` rather than `123,456,000,000,000,000,000`. The axis picks the
  decimals its tick spacing needs; ticks too close together for six of them keep every digit. A
  format set with `ChartAxis.setFormat` or `Chart.setValueFormat` is used as before.

- `limn-fonts-all` names Noto Color Emoji 2.057.0 and the Noto Sans script faces 1.0.3. The emoji
  cover Unicode 18.0; of the four script families only Devanagari moved (2.006 to 2.007, the same
  glyphs, rehinted), and text lays out as before. An application that names the two artifacts
  itself moves them to those versions.
- The guides and the demo name `limn-icons-tabler` 3.48.0.0: 36 more icons, none removed or moved,
  so code that names a constant compiles unchanged.

## After 0.8.0

### Fixed

- On Linux, a combo box's list, a menu or any other popup in a window of its own could come up empty
  but for its highlighted row, and flicker between black and transparent on Wayland. Partial
  rendering took the window's back buffer to be two frames old, and drivers change how many buffers
  they use in the middle of a run. The backend now asks the driver, on X11 (`GLX_EXT_buffer_age`)
  and on Wayland (`EGL_EXT_buffer_age`), and a frame repaints the whole window when the age is
  unknown or older than the last eight presents. macOS and Windows have no such question and keep
  the two-frame assumption, which held there: every frame of the same popup came out whole in seven
  runs on a macOS guest and six on a Windows one.
- On a Mac without an accelerated OpenGL renderer (a virtual machine, a CI runner), an application
  aborted every time it ended. The software OpenGL context kept a closed window's view alive until
  the process ended, and the view still answered input-method questions through a closure into a
  Java runtime that was shutting down. The view gets its own class back when its window closes.
- `-Dlimn.backend.macSoftwareGl=true`, which forces the macOS software OpenGL path, now runs it on
  Apple's software renderer. On a Mac with a GPU it used to build that path's context on the GPU,
  so what it showed of speed and correctness was not what a virtual machine gets.
- The demo's kitchen window drew black on Wayland, all but its status bar. Its frame callback, like
  the reader driver's and the gallery's, forwarded the re-present flag and the GPU sample to the scene
  and dropped the back buffer's age, which on Wayland is three or four.

### Changed

- Video plays on FFmpeg 9.0.2, up from 7.1.5: `limn-ffmpeg-natives` 9.0.2.0. An application that
  names a `natives-<os>-<arch>` classifier itself changes its version to `9.0.2.0`;
  `limn-video-ffmpeg-natives-all` moves on its own. Nothing else changes, and nothing in the
  shim did: the same codecs and containers, no GPL component (FFmpeg 8.0 removed libpostproc),
  the same tests passing. On Linux the payload is about 0.5 MB bigger in the jar and software
  decoding about 4.5% faster, because FFmpeg now lets GCC vectorize; macOS and Windows grew by
  a few percent at most.
- On a Windows machine with no OpenGL 3.3 driver (a virtual machine, a remote-desktop session, a GPU
  whose driver is Direct3D only), the error an application gets at its first window, and the
  demo's `--gl-info`, now say what to install: the GPU maker's driver, or Microsoft's free
  "OpenCL, OpenGL, and Vulkan Compatibility Pack" (`winget install
  Microsoft.OpenCLGLVulkanCompatibilityPack`), which provides OpenGL over Direct3D 12 and is used
  only where no other driver is present.
- An application opened from its icon on such a machine (`javaw.exe`, a jpackage launcher, a
  shortcut) now shows Windows' own error dialog before that error, in the language of the process,
  because it has no console and the message reached nobody. Its buttons open the Compatibility Pack
  in the Microsoft Store and, where winget is installed, run the winget command in a command window
  the person can watch. The error is thrown afterwards as before, whatever is clicked. It appears
  once per process and never in a process with a console; `-Dlimn.backend.errorDialog=false` turns
  it off, and the build sets that for its own tests.
- `Scene.renderFrame(Canvas, boolean, float)` is gone. A frame callback of your own passes the
  backend's `FrameInfo` whole, `scene.renderFrame(renderer.canvas(), frame)`, so a field the backend
  adds later cannot be left behind; the overload that is gone is the one that dropped the age.
- `limn-backend-lwjgl` depends on `lwjgl-egl` as well, at the same LWJGL version. It is classes
  only: it binds the system's EGL library and adds no natives jar.

- `limn-theme-editor` is a program, not a library. Run it
  (`jbang theme-editor@limn-toolkit/limn-toolkit`), save a `.limntheme` file, and load that file
  with `ThemeFormat`, from `limn-toolkit`; no application depends on the editor. Its package is out
  of the API reference, and the guides no longer show it as a dependency.

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

**Screen readers.** New in this release, on Windows (UI Automation), macOS (NSAccessibility) and
Linux (AT-SPI).
- The three platform bridges publish the platform's focus idiom, selection and cursor events,
  structure changes, editable text on Linux, announcements (`Scene#announce`), descriptions
  (`Label.setDescriptionFor`) and a busy state.
- A new widget of a known shape (a toggle, a list of rows, a grid, a menu and six more) needs a
  host, a describe call and a contract test, and no bridge code.
- On macOS a list, a `ListView` or a combo box's open list, is published as a table, as AppKit's
  own lists are: VoiceOver says "table" for it and speaks its selection.
- On macOS VoiceOver is told a table is focused, as AppKit's own tables are, so it reads the row
  the selection moves to, says when the selection empties and counts a select-all. A move along a
  row is said as the cell and its column.
- On macOS a polite announcement is posted at VoiceOver's high priority, because at a lower one
  VoiceOver holds it back behind its own hint for several seconds, as when a tree's branch
  finishes loading. It interrupts what VoiceOver is saying.
- A combo box's list and a date picker's calendar that open in a window of their own are read by a
  screen reader as part of the window that opened them, under their field, as a native drop-down
  list is: no popup window is announced when one opens, nor the main window again when it closes.
  A popup of your own does the same with `Scene.graftPopup`, called before its scene is bound, as
  long as the keys its window receives go on to the control that opened it. A click can make that
  window the key one; the combo's list and the calendar hand those keys on.
- A popup that belongs to a control (your own, like a combo's list, a menu or a calendar) opens with
  `Scene.pushPopup` instead of `pushOverlay`: the page beneath it then stays enabled for a screen
  reader, as a native drop-down leaves its field, where a dialog disables what is behind it. An
  open combo list's selection is its highlight, and its layer is named by the field's caption.

**Languages.**
- `PluralString` and `PluralRules` write a counted sentence with one key per grammatical form.
  The rules are transcribed from CLDR and checked against it in the build.
- `ChartFormats` is now `limn.i18n.NumberFormats`, for any number, and gains currency formats
  and `number(maxDecimals)` for values closer together than a hundredth.

**Tests.** The new `limn-test` module tests a UI with no display. `SceneDriver.drive(scene)`
clicks, types, presses keys and delivers any raw window input. With it come a headless runtime
and backend, a stub window and canvas, fixed text rulers, and the accessibility contracts the
toolkit's own widgets are held to. A widget can carry an id: `setId("save")`, then
`scene.find("save")` or `drive(scene).click("save")`.

**Smaller.**
- `ListView<T>` is a typed list over your items, with the three selection modes.
- A backend of your own keeps native modals with `limn.backend.ModalStack`: which window a modal
  blocks, and why the topmost one is always answerable.
- Every widget takes a minimum and a maximum size: `setMinWidth`, `setMaxWidth`, `setMinHeight`,
  `setMaxHeight`, each read back by its getter. A bound narrows what the parent allows and never
  overrides it. A flexible child takes a ceiling beside its floor, `Expanded.atMost`, and along an
  open axis a flexible child now asks for its floor instead of nothing.
- A window's size limits can be part of its configuration: `WindowConfig.minSize(w, h)` and
  `maxSize(w, h)`, read back with `window.sizeLimits()`.
- `scene.pack()` sizes the window to its content's natural size, within those limits and the
  display's work area.
- Scroll bars can reserve a strip instead of overlaying.
- A spinner can be set by text.
- A covered window on macOS stops rendering until it is uncovered.
- A table sorts a text column from keys extracted once, and a tree splices only the block that
  opened or closed.

**Fixed.**
- On macOS a window no longer changes the system's keyboard input source. Turning the input
  method off whenever the focus left a text field selected a Latin layout for the whole system,
  and turning it on did not bring back Japanese, Chinese or Korean, so their typists chose their
  input method again after every click on a button, in every application. A control that takes
  no text now simply has no input context, as a native one does.
- Setting a window's title, size or position after the user closed it, as work that completes
  late does, is ignored; it used to abort the process.
- Closing an FFmpeg stream while a `VideoView` still shows one of its pictures no longer crashes
  the JVM: the decoder is freed when the last picture it handed out is released.
- A `Display` whose monitor was unplugged answers what it last read instead of reading freed
  memory.
- A 3D material rebuilt every frame compiles its shader once, not once a frame.
- A `MediaPlayer` seeked away from its end, or from a read that failed, no longer ends or reports
  that stale failure afterwards.
- A transport bar's heartbeat and a paused video's poll stop when their window closes.
- A chart's default tick labels have as many decimals as the tick spacing needs: ticks a thousandth
  apart used to read "0, 0, 0, 0".
- A chart's bars and lines stay inside its plot, an infinite value is a gap instead of breaking the
  whole chart, and a single pinned axis end stays where it was pinned.
- An SVG icon the rasterizer refuses draws nothing, logged once, instead of failing every paint
  until the window stopped repainting.
- Moving the pointer over a menu row whose submenu is open leaves the columns beyond it as they
  are; the smallest jitter used to close them.
- An image whose header claims more than 2^28 pixels is refused before it is decoded, instead of
  asking for gigabytes.
- A glTF model whose nodes are not trees, or sit deeper than 1024, is refused, and one load
  creates at most 2^26 array elements.
- Stopping a streamed track and starting another no longer frees the first one's decoder while
  the audio thread is still reading it.
- A focusable `SplitPane` divider is met by Tab, and by a screen reader, between its two panes;
  it came after the second pane's whole subtree.
- A drag on a combo box's scrollbar no longer leaves the arrows dead in its list, and a row chosen
  by pointer gives the keyboard back to the field's window.
- A key a widget acts on ends the wheel scrolling still arriving, so a trackpad's inertia no
  longer carries a list away from the row an arrow key just scrolled into view.

### Breaking, and how to move

One breaking round, taken before 1.0.

| 0.7 | Now |
| --- | --- |
| a setter in code ran the `onX` handler (`slider.setValue(v)` ran `onChange`) | `onX` runs for the user alone; `widget.observeChanges(change -> …)` hears every change, with its origin |
| a second `onX` registration replaced the first | it throws; pass `null` to clear the slot first, or use `observeChanges`, which takes any number |
| `I18n`, `Fonts`, `ControlSize` or `LayoutDirection` `.addChangeListener(r)` / `.removeChangeListener(r)` | `.observeChanges(r)`, which returns a `Subscription`; `cancel()` it to stop. `Theme` has one too |
| `viewport.onDispose(r)` | `viewport.observeDispose(r)` |
| `mediaControls.setOnRefresh(r)` | `mediaControls.observeRefresh(r)` |
| `popupMenu.onClose(r)` | `popupMenu.observeClose(r)`, which returns a `Subscription` |
| `Runnable stop = scene.observePresses(…)`, and `observeWindowBlur`, `observeWindowClosed`, `addShortcutHandler`; then `stop.run()` | each returns a `Subscription`; `cancel()` it |
| `onSelect(Consumer<Integer>)` on `ButtonGroup`, `ComboBox`, `SegmentedControl` and `TabbedPane` | `IntConsumer`; a lambda is unchanged |
| `ChartFormats.number()`, `.compact()`, `.percent(…)` … in `limn.components.chart` | `NumberFormats.number()`, `.compact()`, `.percent(…)` … in `limn.i18n` |
| `slider.onChange(Consumer<Float>)`, `spinner.onChange(Consumer<Double>)`, `splitPane.onRatioChange(Consumer<Float>)` | `FloatConsumer` and `DoubleConsumer`; a lambda is unchanged |
| `new WindowConfig("Title", 480, 320, …)` | `WindowConfig.of("Title", 480, 320)`, then withers: `.resizable(false)`, `.visible(false)`, `.transparent(true)` … |
| `Theme.current().background` (a field) | `Theme.of(widget).background()` in a widget, `theme.background()` elsewhere; `dark` is `isDark()` |
| `Theme.setCurrent(t)` plus a relayout | `t.apply(scene)` switches the palette, applies its font, clears an opaque scene to the palette's background and repaints |
| `anyWidget.add(child)` | only a `Container` (`Column`, `Row`, `Stack`, your own layout) adds and removes children in public |
| `class MyButton extends Button` | concrete widgets are `final`; extend `Widget`, or compose; `TextField` is sealed |
| `class MyWidget extends Widget`, `Widget w`, `List<Widget>` | `class MyWidget extends Widget<MyWidget>`, `Widget<?> w`, `List<Widget<?>>`; every setter now chains (`new Button("OK").setEnabled(false)` is a `Button`) |
| `button.withControlSize(size)` | `button.setControlSize(size)` |
| overriding `TextField`'s key hook to submit on Enter | `field.onSubmit(text -> …)` |
| `field.model().selectAll()`, `field.model().setCursor(…)` | `field.selectAll()`, `field.select(anchor, caret)`, `field.setCaretPosition(i)`, `caretPosition()`, `selectedText()` |
| `textArea.softWrap()`, `spinner.snapsToStep()` | `isSoftWrap()`, `isSnapToStep()` |
| `new ListView(new ListView.Adapter() { rowCount / rowAt / recycle / rowName })` | `new ListView<>(item -> cell)` with `setItems(list)`, or `ListView.pooled(create, bind)`, or `new ListView<>(cellFor, recycle)`; `setItemName(item -> name)` |
| `listView.onSelect(index -> …)` | `listView.onSelect(() -> … listView.selectedIndex())`; `MULTI` through `setSelectionMode` |
| `scene.keyEvent(…)`, `scene.mouseButton(…)`, `scene.inputBatchEnded()` … in a test | `SceneDriver.drive(scene).press(Keys.ENTER)`, `.click(widget)`, `.type("…")`, or the raw calls on the driver (`limn-test`) |
| a `Graphics3D.Provider` implementing `renderDemoScene`, or `Graphics3D.renderDemoScene(…)` | gone: a `Viewport3D` with no renderer draws its cube through the public renderer, so a provider owes targets, meshes, textures and passes only |
| a backend filling the toolkit's static slots | `new BackendServices(…).install()`; `Canvas.drawSurface`, the close-request handler, the IME calls and `supportsAbsolutePositioning` must be implemented |
| `WindowInput.mouseButton(…)` with no count | a click count; `MouseEvent.clickCount()`; the platform's own double-click interval |
| `FrameInfo` without a buffer age | `bufferAge`, 0 when unknown |

**Behaviour.**
- `ChartSeries.value(i)` answers `NaN`, a gap, for an infinite value as for an explicit `NaN`;
  `values()` still hands back what was set.
- Partial rendering is on by default. A widget of your own that changes what it draws without
  calling `invalidate()` now leaves stale pixels where it used to be repainted with the rest of
  the frame: call `invalidate()` when its picture changes, or `scene.setPartialRendering(false)`
  to go back to whole frames.
- A plain `ar` locale now writes Latin digits, as native applications do. The regions that use
  Arabic-Indic digits (`ar-EG` and 22 others) keep them.
- Every public mutator of a widget, a layout or the scene now checks that it runs on the UI
  thread. A write from a worker, which was a silent race, now throws.

**The module path.** `limn.toolkit`, `limn.backend.lwjgl`, `limn.video.ffmpeg` and `limn.test`
are named modules, and `limn.themeeditor` is an automatic one. The font jars and the FFmpeg native
jars name themselves as modules, which the backend and the video decoder require. LWJGL's native
jars and jlayer need a word each, and the Packaging guide gives it, with how an application module
lets the toolkit read its own resources.

### Known, and left for later

- NVDA says nothing at a select-all, as it says nothing at one in Windows' own lists; VoiceOver
  and Orca do. It says nothing either when Space takes a table's row out of the selection while the
  cursor is on one of the row's cells, as it says nothing in a WPF DataGrid.
- VoiceOver is given a sorted header's direction and does not read it out.
- A date picker's year chooser keys its cells by position, so paging a block of years may make a
  reader speak a stale year first.
- On Windows, a UI Automation client's `SetFocus` on a column header, or on a row of a list or a
  tree in `SINGLE` (where the cursor is the selection), is refused; the container's is accepted.
