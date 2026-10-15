# Changelog

What changed between releases, and what to change in your code when you move. Newest first.

Each section is named after the release it follows. It is complete on the day the next version is
tagged, and no release has to edit this file to rename it; the release on GitHub and the version on
Maven Central say which number that was.

## After 0.9.0

### Fixed

- The empty part of a `Slider`'s and a `RangeSlider`'s rail and of a `ProgressBar`'s groove
  shows on every surface. It was painted in the raised surface's tone, which in the Light theme
  is the page's own (#EDF0F6 on #EEF0F4), so on a page the rail vanished and a slider at its
  middle read as one at its end; it is now the text's ink, faint, on any theme. A disabled
  slider's fill takes the disabled text's grey, darker than the rail it fills, where it was
  lighter.
- A disabled multiple-choice `ComboBox` shows its chips. Each was filled with the disabled
  fill, the tone of the field under it, so the captions floated apart with no chip around
  them; disabled, a chip takes the same faint ink as an empty rail.
- A scroller at an end of an axis passes the wheel on. A `ScrollView`, a `TextArea` and the
  scrolling strips of a `TabbedPane` and a `SegmentedControl` took every detent while they
  could scroll at all, so one inside a scrolling page stopped the wheel at its top or its end
  and the page never moved; each takes a detent only when it moved now, as a list, a table and
  a tree already did, at the start and the end of both axes.
  A gesture stays with the scroller it moved, as in browsers and on macOS: a flick that brings
  a list to its top, a trackpad's inertia included, does not carry on into the page, and the
  next gesture, after a pause, does. On macOS a new swipe is known by the phase AppKit gives
  it, so it passes on even inside the inertia of the swipe before; `WindowInput` gains the
  `scrolled` that carries it, for a backend that can tell.
- A `ScrollView` scrolled before its first layout opens where it was scrolled to: the offset
  was clamped against content not yet measured, to the top, and lost.
- A checkbox's focus ring sits concentric with its box at every density: its edges fell
  between two pixels and the renderer moved it half a pixel to one side. The ring and the
  radio button's lie beyond a clear gap now, and both reach 3pt past the indicator.
- A tab is closed by its close button and dragged under the pointer: the header read a
  pointer event's coordinates as its own where they are the scene's.
- A video in a window that closed frees its decoder. A `VideoView` gave its pictures back only
  when it was removed from its scene, a window closing removed nothing, and a decoder is freed
  when the last picture it handed out comes back: a window closed with a video in it kept the
  whole decoder, whatever the application then closed. A closing window detaches its widgets
  now; see Changed.
- A `MediaPlayer` whose stream has ended sleeps until a seek or a start: its decoding thread
  woke fifty times a second for as long as the player was left open. And one the application
  never closes is closed when the backend ends, with a warning that names it; nothing else
  ever stopped its thread.
- A sound the application has let go of is let go by the engine too. The engine kept the last
  sixty-four clips it had played, samples and all, whoever still had them. And a sound that is
  not streamed because four streams are already open says so in a warning, where it said
  nothing: a stream paused or looping stays open until it is stopped.
- A font made anew as a program runs is remembered as any other. Past 4,096 fonts the memo of
  which face a font resolves to stopped taking new ones, kept the old for good, and resolved
  every later font the long way at each measure and each draw. And the pairs of glyphs a face
  has been asked the kerning of are held to a ceiling, where varied text in a script of
  thousands of glyphs added to them without end.
- A window whose renderer throws as it closes is still destroyed, and a shader whose second
  half is refused gives back its first.
- A widget removed from a window is collected in a window nobody reads. The accessibility walk
  keeps who answers for each node, and where no screen reader is running the tree is walked once
  and not again, so every widget of a window's first frame stayed for the life of the window,
  removed or not, with whatever it held.
- On Windows, with a screen reader running, what stood for a control that has left the window is
  let go when it leaves, and freed once the reader lets go of it too. It was kept until the
  window closed, which for an application's main window is never.
- On macOS a window that is closed is freed. Every window an application had closed stayed in
  the system's overview of windows as an empty frame with its title: the toolkit's own calls to
  the system were handed references that nothing released, since the event loop had no
  autorelease pool of its own. Each turn of the loop has one now.
- A window that had the keyboard hands it on when it closes, to the window that owns it or to
  the one in front. On macOS the application was left with no window to type in, and its menu
  bar fell back to the one an application with no window shows, until a window was clicked.
- A calendar or a menu drawn in the scene gives the keyboard back as it starts to close, and
  leaves what a screen reader reads at the same moment. The focus came back when the fade ended:
  until then a key typed went to a calendar that was closing, and VoiceOver, standing on a layer
  about to be destroyed, read a neighbour of the field before the field.
- On Windows, a tool that keeps an element it found is answered from a current tree for as long
  as it reads. Two seconds after the element was found every property read through it was the
  value of that moment, and a table scrolled through its scroll pattern stayed at the percent it
  had and took neither a step nor a percent. A small scroll step moves a line and a large one a
  page; a small one moved a page and a large one was refused.
- A date picker's year chooser, and its month chooser, name each cell for good: paging a block of
  years made the same cells carry the next block's years, and a screen reader standing on one
  could speak the year it had been first.
- On macOS a program that reads the screen once, a window arranger or a script, no longer makes
  the application build its accessible tree on every changed frame for the rest of the run: the
  tree is kept current while the system says a screen reader or the switch scanner is on, and
  for ten seconds after anything asks.
- Orca 50.2 reads what a combo box holds: "Mountain ranges Atlas, combo box". A combo box that is
  not editable is named on AT-SPI by the value it shows, its caption reached through
  `labelled-by`, as GTK 3's and Qt's are; Orca 50.2 speaks a focused combo's label and name and
  never its value, so it had been heard as its caption alone. Orca 46.1 reads it as before.
- A double click in a `TextField` or a `TextArea` selects the word under the pointer, and a triple
  click the line (the whole text of a field); a drag that follows extends by whole words or lines,
  as every platform's text control does. A click only placed the caret.
- A disabled `TextField`'s placeholder is drawn in the disabled ink: it read brighter than the
  disabled text beside it.
- Orca reads the field again when a popup's cursor leaves it and the focus stays: an editable
  combo's Enter was heard as "return" and nothing more, and a context menu closed with Escape said
  nothing of the button the keyboard was back on: Orca had stayed on the closed list's row.
- On Linux a one-line editable field says it is single-line, as GTK 3's entries do. Orca 50.2 read
  a value a field took after Return, the pick of an editable combo, a second time as typed text.
- On X11, a combo box's list, a date picker's calendar and a menu in a window of their own no
  longer take the keyboard from the window that opened them. GNOME focused them as they appeared,
  and Orca read the application's window again every time one closed. They are now windows the
  window manager leaves alone, as GTK's and Qt's menus are.
- VoiceOver counts a menu's items without its separators ("menu, 4 itens"), as it counts
  AppKit's own.
- A menu in a window of its own follows the pointer only when the pointer moves. On Windows a
  submenu opened from the keyboard over a pointer resting on the menu closed again at once,
  because Windows reports a window that grows under a still cursor as a mouse move; the highlight
  jumped to the row under the pointer, and NVDA read that row instead of the submenu's first.
- Leaving a menu bar with Escape, or with F10 pressed again, gives the keyboard back to the
  widget that had it when Alt or F10 moved it to the bar, as Windows, GTK and Qt do. It went
  nowhere, so a keyboard user who opened the bar from a field had to Tab back into the form.
- A menu bar drawn in the scene keeps the keyboard when a dropdown reached with Left or Right is
  closed with Escape. The dropdown walked to had saved the one walked from, still fading, as
  where the keyboard went back to, so nothing was focused after it closed.
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

- `ScrollView.setBarInsets(Insets)` sets the bars' tracks in from the ends of their
  sides, for content that scrolls under something laid over it, a bar of glass across the top:
  the thumb starts below it and keeps its proportion of the shorter track.
- Tabs that close and move. `TabbedPane.setTabsClosable(true)` makes the tabs added after it
  closable, and `setTabClosable(index, value)` one tab: it draws a close button while selected
  or under the pointer and closes by it, by a middle click, and by Ctrl+W (Cmd+W on macOS) while
  the keyboard is in the pane. `onTabClose` is handed a `TabCloseRequest` to `allow()` or
  `refuse()`, now or later, and a tab is not asked again while its request is unanswered;
  closing the selected tab selects the next, or the one before when it was the last. A tab is
  dragged along the bar, the others sliding out of its way, and moved by Ctrl+Shift+Page Up
  and Page Down (Cmd on macOS), which a screen reader hears as its new position; `onTabMove`
  hears the user's moves. `removeTab`, `moveTab`, `tabContent` and `indexOfTab` do the same
  from code, announced as `CHILDREN` with the origin of whoever asked.
- `Expander`, a section that opens and closes: a header with an arrow that turns, a title, an
  optional icon and an optional summary, over content that slides out below it, at once when
  the user asked for less motion (`Theme.animExpand`). Click, Space and Enter open and close it,
  `setExpanded` from code, `onChange` hears the user. `ExpanderGroup` keeps at most one of its
  sections open. A screen reader hears a button, expanded or collapsed, that controls the
  content.
- `RangeSlider`, a lower and an upper value on one track: two thumbs that stop at each other or
  at `setMinimumGap`, each a Tab stop moved by the keyboard, a press on the track taking the
  nearer one. `setRange`, `lower()`, `upper()`, `setStep`, `onChange` for every step and
  `onCommit` for the range let go at. A screen reader hears two sliders, Minimum and Maximum.
- A mask on `TextField`: `setMask("000.000-00")`, where `0` takes a digit, `A` a letter, `*`
  any character, and the rest are literals the field writes as the next position fills. Pasting
  with or without the literals gives the same text, Backspace after a literal takes the
  character before it, and one undo takes an edit and its literals back. While focused, the
  positions still to fill show as a guide after the text. `unmaskedText()` and
  `isMaskComplete()` read the value; a screen reader hears the pattern as the description.
- `Widget.moveChild` reorders a child without taking it out of the tree, so its focus, hover
  and press stay, and lays a parent that clips its children out alone. `remove` and `moveChild`
  take who asked, which the one `CHILDREN` change they make carries.
- `SECURITY.md` says where a vulnerability goes: the repository's private reporting, never a
  public issue. The issue templates ask a defect for its version, system and steps, and a
  feature for the need behind it; a question goes to Discussions.
- Animated images. An `Image` may be animated: `isAnimated()`, `frameCount()`, `frame(i)`,
  each frame an image, `frameMillis(i)`, `durationMillis()` and `loopCount()`. Its own pixels
  are its first frame's, so whatever draws an image without playing it draws a still picture.
  An `ImageView` plays one by itself, with `setPlaying`, `setSpeed`, `setLoop` (as the image
  says, without end, once, a number of times, or forward and back), `seekFrame` and
  `onFinished`; `limn.animation.ImageAnimation` is the same clockwork for a widget of an
  application's own. An animated GIF is read as one, where only its first frame was, with the
  times of its frames and the turns it asks for. WebP is read, still and animated, which
  nothing here read before, by a decoder written in Java that the toolkit now depends on,
  `org.glavo:webp`: an application adds nothing, and none of it is loaded until a WebP is
  decoded. `Images.fromSheet` makes one of a sheet of
  sprites and `Images.animation` of frames from anywhere. And `ImageFormat.GIF` is written:
  every frame of an animated image, or a still picture, in the 256 colours the format holds;
  `GifEncoder.INSTANCE` is its encoder, beside `PngEncoder.INSTANCE`.
- A long animation is decoded as it plays. One whose frames together are within
  `Images.setAnimationBudget`, 32 MiB unless set, is decoded whole when it is loaded; a larger
  one keeps its file's bytes and decodes a few frames ahead of the one shown, on the worker
  pool, and a frame that is late is waited for on the frame before it. Among the shared
  loads each frame is an entry of its own, kept and let go by the shared budget and decoded
  again from the file when it is wanted and gone, and `loadPinned` keeps every frame.
  `Image.frameIfReady` is what a player asks in place of `frame`.
- Animated PNG is read and written. An animated PNG decodes to an animated image, each frame
  laid on the ones before as the file says, where only its first picture was read; each
  frame's pixels are read by the installed decoder, so nothing new is carried. `PngEncoder`
  writes an animated image as one, every frame without loss, each after the first holding only
  the rectangle that changed.
- An image view playing asks for a frame of its window only when a frame of its picture is
  due, where it asked for every frame of the display while it played.
- Animated icons. A `BitmapIcon` of an animated image is an animated icon: `Icon.isAnimated()`,
  `frameCount()`, `frameMillis(i)`, `loopCount()` and `image(pixelSize, dark, frame)`, whose
  frame 0 is what `image(pixelSize, dark)` answered; the images of one icon play alike, or it
  is refused. Every component that draws icons plays one by itself, at the image's times and on
  the scene's clock: buttons, labels, tabs, a field's icons, a combo's, list, tree and table
  cells. It starts at the paint that shows it, waits where nobody sees it, rests on its last
  frame, and asks for a frame of its window only when one of its frames is due, repainting the
  icon's box. A tray plays one in each of its pictures, and `TrayPicture` says which frame it
  draws. An `ImageCursor` of an animated image plays, counted once in the backend's cache of
  cursors however many frames it has. A menu, the system's or the toolkit's, shows the first
  frame, and so does a window's own icon; the wish for less motion is not applied.
- SVG is drawn by lunasvg, in place of NanoSVG: gradients and patterns, clipping and masks,
  markers, style sheets, CSS custom properties with their fallbacks, and text in the bundled
  faces and the system's, where NanoSVG drew shapes and little else. It ships as the
  `limn-lunasvg-natives` payload, versioned with lunasvg, which the backend depends on with all
  six platforms' jars, so an application adds nothing; `lwjgl-nanovg` is gone. Every SVG is read
  by the toolkit first, in Java: within ceilings, with no script, event or reference outside the
  file reaching the renderer, and a damaged one refused with `MalformedContentException`.
- `SvgIcon` takes any aspect, fitted into its box, says its own `width()` and `height()`, and
  reads a file with `fromFile(path)`, whose pictures beside it in its folder are shown.
  `ImageView` shows an `Icon` as well as an `Image` (`new ImageView(icon)`, `setIcon`), drawn at
  the view's own size and density, so an illustration stays sharp. `Images.decode` reads an SVG,
  drawn at its own size.
- Animated SVG plays: SMIL's `animate`, `animateTransform` and `set`, chains of animations that
  begin at another's beginning or end among them, and CSS's `@keyframes` with the `animation`
  properties. An animated `SvgIcon` is an animated icon, at 24 frames a second unless
  `withFrameRate(n)` or `-Dlimn.svg.frameRate=n` says otherwise; `Images.decode` of one is an
  animated image. A transform CSS gives an element is drawn too.
- `ButtonGroup.remove(radio)` takes a radio out of its group.
- `Ui.postDelayed` returns a `Subscription` that withdraws the task: a timer armed for
  something that can go away first is cancelled there, where it held all it captured until it
  fell due.
- `Images.loadPinned` and `Images.fromResourcePinned`, with `unpin` and `unpinResource`, and
  the same four on `Sounds`: a shared load that is kept whatever the budget below, for what an
  application wants at hand for as long as it runs. `setSharedBudget` and `sharedBudget` move
  and read that budget.
- `BackdropPanel.frosted(content)`: frosted glass that follows the theme. What is behind the
  panel is blurred and laid over with the palette's surface tone, light over a light palette and
  dark over a dark one, and it changes with the palette by itself. `setTint(colour, amount)`
  leans it toward a colour of the application's, and `setCover(share)` says how much of the tone
  is laid over what is behind. A panel given its effects by hand is as before.
- A widget's id is what a tool that drives the application from outside finds it by:
  `Widget.setId` is published as UI Automation's `AutomationId` on Windows, the accessibility
  identifier on macOS and the accessible id on Linux, where each carried a number of the
  toolkit's own. `AccessibleNode.identifier()` answers it. A widget given no id keeps the number.
- `HeadlessWindow.takeFrameRequests()`, in `limn-test`: how many frames a scene asked its window
  for since the last call. A test frames a screen until it answers zero and has shown that the
  screen costs nothing while nobody touches it. The toolkit holds itself to it, and to counts of
  work that are the same on every machine, in `check`: a list, a table and a tree of a hundred
  thousand rows cost a frame what ten thousand do.
- A menu item can have an icon: `MenuItem.setIcon` shows a picture before the label, in a
  popup menu and a menu bar's menus, which keep the same room on every item of a column once one
  has an icon, and in the menu a system draws for a tray. A tintable icon takes the label's
  colour.
- A window can be minimized, maximized, restored and brought in front: `NativeWindow.minimize()`,
  `maximize()`, `restore()`, `isMinimized()`, `isMaximized()` and `toFront()`, which shows a hidden
  window, restores a minimized one and gives it the keyboard. `observeState` tells a listener each
  time the window is shown or hidden, minimized, maximized, restored, moved or resized, by the
  application or by the user, with a `WindowState` of its state and its `bounds`; `state()` and
  `bounds()` answer the same at any time. Each has a default for a window with no desktop under
  it.
- On Linux a window comes in front when the user asked. A window brought in front from the
  tray icon, and a program `window.launcher()` starts or hands an address to, now carry the proof
  a Linux desktop asks for before it changes the window in front: an activation token under
  Wayland, the time of the user's action under X11. Before, a browser that was already running
  took the page behind the window that asked, and on KDE under X11 and on GNOME under Wayland a
  window asked in front from its tray icon stayed behind. Call the launcher from the user's key
  or button, since that is what the desktop vouches for.
- A malformed file is refused with one exception, `limn.io.MalformedContentException`, in every
  loader: pictures, sounds, video in Y4M, models, fonts, vector icons and themes. **This changes
  what is thrown**: the glTF loader, the theme format and an SVG threw `IllegalArgumentException`,
  the Y4M reader `IllegalStateException` and a font one or the other, and code that caught those
  for a broken file catches the new one. A file that cannot be read is still an I/O failure, a
  format nothing decodes still an `UnsupportedOperationException`, and a wrong argument still an
  `IllegalArgumentException`. The exception says the format's name and, where known, the line or
  the offset. What the video library cannot open still arrives as `FfmpegException`.
- Hardened against damaged and hostile input. Every reader the toolkit writes in Java is now run
  under a mutation fuzz in `check`, which found and fixed: a bitmap and a list of files from the
  Windows clipboard that threw an index error, a dragged block whose reading threw at the caller,
  a message off the session bus that could ask for two gigabytes, nest without limit or never
  end, an MP3 whose damaged frames threw what its decoder never declared, and a glTF mesh whose
  arrays disagreed going out as the caller's own exception. A sound decoded whole stops at
  2^28 samples, a vector icon at 8192 pixels a side, a glyph at 4096 by 4096, and a block read
  whole from another program at 512 MiB on every system. A font is still for a source you
  trust: the library that draws its outlines checks nothing, and the guide now says so.
- On macOS a `MenuBar` is shown in the system's menu bar. The bar of the window the user is
  working in is the one shown, and the widget takes no room in its scene; a window with no bar, a
  dialog, or no window shows the application's menu and a Window menu. It is the default, and a
  change for every application: `bar.setPlacement(MenuBar.Placement.IN_WINDOW)` keeps a bar in
  its window, and `-Dlimn.macos.systemMenuBar=false` keeps them all. In the system's bar **the
  system runs an item's accelerator before the focused widget sees the key**, the reverse of the
  order in a window. `MenuItem.setRole` says what an item is to the system: `ABOUT` and `SETTINGS`
  move to the application's menu under the system's wording, and `QUIT` gives way to the
  system's Quit, which asks `requestQuit()` as `Cmd+Q` and the Dock do. `setWindowMenu` and
  `setHelpMenu` name the menus the system adds its own items to, the list of windows and the
  search field, and a Window menu is given where none is named. The application's name in bold
  comes from its package, or from `new LwjglBackend("Name")` or `-Dlimn.application.name` for
  one that is not packaged. An application packaged as an agent (`LSUIElement`) is now left one,
  with no icon in the Dock, and keeps its bar in the window. A fullscreen that covers the monitor
  brings the bar back into the window while it lasts.
- Editing commands: `MenuItem.of(MenuItem.Role.COPY)`, and likewise `UNDO`, `REDO`, `CUT`,
  `PASTE` and `SELECT_ALL`, makes an item with its translated label and the platform's shortcut
  that acts on whatever widget has the keyboard and is greyed while that widget cannot, in a
  menu bar, a context menu or a tool bar's menu, on every system. `Scene.canEdit` and
  `Scene.edit` are the same for a button, and a widget of your own takes part through
  `Widget.onCanEdit` and `onEdit`. A text field's Cut and Copy chosen from a menu find the
  selection the field had before the menu took the keyboard, and the field has the keyboard back
  after.
- On macOS a file panel's text fields now take cut, copy, paste and select all by their
  shortcuts, which they did not, and the window that opened a panel has the keyboard again when
  the panel closes, where before nothing typed reached it until it was clicked.
- A tray icon: `Tray.create(backend, "id")` in the new package `limn.desktop` keeps an icon in
  the system's place for one, the notification area on Windows, the menu bar on macOS and the
  panel's tray on a Linux desktop, with a title, a tooltip and a `Menu` that **the system
  draws**, read each time it is about to open, with each item's icon and accelerator. `onActivate` is the click that means "this
  application"; without it any click opens the menu. `setMonochromeIcon` takes a shape that is
  shown in the bar's own colour, black or white as the Windows task bar and the macOS menu bar
  are, following a change while the application runs, and on KDE by the panel itself; `setAttention` shows the icon with a dot
  the toolkit composes, over the monochrome icon too. `isAvailable()` says whether the system
  took the icon and `onAvailabilityChange` when that changes, as it does on a GNOME desktop
  whose tray extension is turned on while the application runs. It is the toolkit's own code on
  the three systems, with no library: the shell's notification icon on Windows, a status item
  on macOS, and on Linux the desktop's status-item protocol over the session bus, with no GTK.
  On Windows a process now tells the system it accepts dark menus, so a menu the system draws
  for it is dark when the user's applications are; `-Dlimn.windows.darkMenus=false` leaves the
  system's default. A backend of your own gains `BackendPeer.createTray`, with a default for a backend with no
  desktop. The guide has the menu, the two icons, "close to the tray" as a recipe over the
  window's close policy, and what to do on a desktop with no tray; the demo has a page with a
  switch for each.
- Elevation: a surface says how far it is lifted, `Elevation.FLAT`, `RAISED`, `OVERLAY` or
  `MODAL`, and the theme says what that looks like. A theme holds a shadow of two layers for
  each level (`Theme.elevation`, `Theme.Builder.elevation`, and `elevation.raised`,
  `elevation.overlay` and `elevation.modal` in a `.limntheme` file, five numbers each), darker
  by default in a dark palette. `Surface` is a container that draws its level's shadow under
  its fill, for a panel that slides over content or a card with no edge, and
  `Canvas.drawShadow` draws one layer under any rounded rectangle. The toolkit's own surfaces
  cast theirs: a menu, the list of a combo box, a calendar and a tooltip cast `OVERLAY`'s, and
  a dialog drawn in the scene `MODAL`'s. A popup or a frameless dialog that is a window of its own
  keeps the system's shadow where the compositor casts one (macOS) and draws the toolkit's
  where it casts none (Windows, X11, Wayland), in a transparent margin of the window; a press
  in a popup's margin closes it. The theme
  editor edits each level's five numbers and shows the three shadows in its preview.
- `window.launcher()` hands an address or a file to the system: `openAddress` opens a web page
  in the browser or a `mailto:` in the mail program, `openFile` a file in the program registered
  for its type, and `showInFolder` shows a file selected in the file manager. Each returns at
  once and answers on the UI thread whether the system took it. An address must be `http`,
  `https` or `mailto` unless the application allows another scheme once with
  `Launcher.allowScheme`, and a `file:` address is never taken, so text that came from data
  cannot start a program. `NativeWindow` gains `launcher()` with no default; a window of your own
  answers `Launcher.NONE` or builds one over a `LauncherPeer`. The demo's `--open` and `--show`
  ask it once from the command line.
- `limn.transfer`: what a copy carries. A `Transfer` offers one item in several `DataFormat`s at
  once (`TEXT`, `HTML`, `IMAGE`, `FILES`, and formats of the application's own: `DataFormat.of`
  names one and says how it becomes bytes, `DataFormat.inProcess` carries the object itself), a
  value can be lazy (`withLazy`: made once, when somebody asks), and what is there to take is an
  `Offer` that lists its formats at once and reads a value asynchronously.
  `Clipboard.set(Transfer)` and `Clipboard.content()` carry them, and `onChange` hears the
  content change.
- Drag and drop between widgets. A widget answers `onDragStart` with a `Drag` (what it carries
  as a `Transfer`, the actions it allows, what to hear at the end); the widget under the pointer
  accepts in `onDragOver`, marks where the drop would land, and takes it in `onDrop`. The
  modifier keys choose between copy, move and link as the platform does, Escape cancels, and a
  widget that scrolls under the wheel scrolls when the pointer rests near its edge. The keyboard
  does the same: Ctrl+Shift+D (Cmd+Shift+D on macOS, `Scene.setDragKey`) picks up on the focused
  widget, Tab walks the widgets that accept, the arrow keys choose the place, Enter drops, and a
  screen reader hears each step in the 21 languages.
- A drag leaves its window, on Windows, macOS, X11 and Wayland: it is the system's own drag, to
  and from other programs, with the picture of what is dragged following the pointer over them,
  and what another program drags in is answered while it hovers. `Drag.withinWindow()` keeps
  one from being offered outside. `-Dlimn.drag.trace=true` prints each step of a drag handed to
  the system.
- `ListView` and `Tree` drag their rows. `setDragEnabled(true)` lets the user drag them out,
  the selected ones together, as `ListView.ITEMS` or `Tree.NODES` for the program's own widgets
  and as text for any other (`setDragContent` says otherwise). `onReorder` lets them be moved
  within the widget, by a drag or with Alt+Shift and an arrow: Up and Down in a list, and in a
  tree Left out one level and Right into the sibling before; dropped on a tree's node they go
  into it, near its edge before or after it, and a closed node the pointer rests on opens.
  `onMoveOut` hears that they were moved somewhere else, and `onDrop` with `setDropFormats`
  takes what is dropped between two rows, from another list, another widget or another
  program; `setDropAction` has a drop be a copy, or a move, wherever the source allows it, and
  `setDragWithinWindow(true)` keeps the rows from being offered to another program. What is dragged lifts out of its place, room opens where it would land and the rows
  slide to it, at once under reduced motion.
- `ImageView.setDragEnabled(true)`: the picture is dragged out of its view with its own
  transparency, to a drop area of the program or to another program.
  `setDragFileName("logo.png")`, and `Transfer.withImageAsFile` for a drag of the
  application's own, offer it as a file too, for the targets that take a picture only as one,
  the desktop and a folder among them: a PNG written to the temporary directory when a target
  asks for files (on macOS at each drag) and deleted when the program ends. Not done unless
  asked, since it writes to disk. `setDragWithinWindow(true)` keeps the picture in its window.
- What is dragged shows under the pointer as it is painted. `Drag.preview(width, height, holdX,
  holdY, painter)` has the source paint the dragged thing alone on a transparent surface, so a
  card keeps its rounded corners and a picture its own transparency, inside the window and over
  any other program; `Drag.preview(x, y, width, height)` shows a part of the source widget as it
  stands. `Canvas.readBack` reads a part of the frame back with its transparency.
- `Table`: the user moves a column by dragging its header (the whole column follows along the
  header and the others make room) or with Alt+Shift+Left and Right
  (`setColumnReorderingAllowed(false)` turns it off), the application with
  `moveColumn(column, index)`, and `columns()` answers the order as it stands.
  `setFrozenColumns(n)` keeps the first columns in place while the others scroll under them.
  `onColumnsChange` is told when the user moved or resized a column, which is when to save the
  layout.
- The clipboard carries all of it to and from other programs on Windows, macOS, X11 and Wayland:
  a picture pasted from a screenshot tool, files copied in the file manager, a table copied to a
  spreadsheet as HTML. A lazy value is made when a program first pastes it. On Windows and macOS
  what was copied stays on the clipboard after the application leaves. Where the native
  clipboard does not open, text still reaches other programs and the rest pastes within the
  application; `-Dlimn.clipboard=glfw` asks for that.

- `ComboBox.setEditable(true)`: a combo box the user types into. Typing filters the list, with the
  items whose text contains what was typed, ignoring case and accents, and those that start with it
  first; Down and Up walk it, Enter picks, Escape closes it and a second Escape puts the selected
  item's text back, and leaving the field picks the item its text names. The field keeps the
  keyboard while the list is up, and a screen reader stands on the field and hears the highlighted
  option as its cursor.
- `ComboBox.setFilter((item, text) -> …)` replaces the rule an editable combo filters by, and
  `setFreeText(text -> item)` lets it take a text that names no item, which becomes the value when
  the field is left; without it such a text is put back and a screen reader is told it was refused.
- `ComboBox.setSearch(text -> …)` and `new ComboBox<>(Customer::name)`: an editable combo whose items
  come from a search the application runs off the user-interface thread (`Ui.async`). It asks
  200 ms after the last keystroke, shows "Loading…", "No results" or "Search failed" as a line of
  the list, drops an answer for a text the user has typed past, and tells a screen reader how many
  were found.
- `ComboBox.setSelectionMode(SelectionMode.MULTI)`: a combo box that holds many items, with
  `ListView`'s words for the rest (`selectedItems`, `selectedIndices`, `isSelected`,
  `setSelectedIndices`, `setSelectedItems`, `selectAll`, `clearSelection`). The list stays down while
  a click, Space or Enter checks and unchecks its rows, and Ctrl+A (Cmd+A) checks every item; the
  closed field shows a chip per item in the list's order, as many as fit on its line, then a count
  of the rest ("+2"). An editable one's text only filters, and hides the chips while it is there. A
  screen reader hears the count and every name as the field's value ("3 selected: Open, Invoiced,
  Shipped"), in the 21 shipped languages. Not with a search or with free text.
- `ComboBox.setChipRenderer(item -> widget)` draws those chips with a widget of the application's,
  drawn and never operated; `ComboBox.setItemIcon(item -> icon)` puts an icon before an item's text
  in the list, in a chip and in the field of a combo holding one.
- `ComboBox.setEmptySelectionAllowed(true)`: a combo holding one item may hold none. Its list starts
  with an option standing for nothing, whose value is `null`, worded and drawn by the text and icon
  functions asked for `null`; an editable one emptied by hand takes it.
- `ComboBox.setPlaceholder(…)`: a muted hint the field shows only while it reads nothing (none held,
  an empty option without words, an emptied text), which also names the combo for a screen reader.
- `Accessible.State.AUTOCOMPLETES`: a field that filters or completes from a list it controls,
  which it names with `CONTROLLER_FOR` while the list is shown. AT-SPI carries it as
  `supports-autocompletion`; UI Automation and AppKit have no such flag and read the relation.
- `AccessibleEvent.Type.MENU_OPENED` and `MENU_CLOSED`: a menu entering or leaving the accessible
  tree, raised before the focus moves into it. Windows raises UI Automation's `MenuOpened` and
  `MenuClosed`, macOS posts `AXMenuOpened` and `AXMenuClosed`, and AT-SPI, which has no such signal,
  is told nothing.
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
- `Backend.requestQuit()` ends the application by asking once: the quit handler
  (`Backend.setQuitHandler`) first, then every open window's close handler, the window used last
  first and one at a time. A refusal anywhere keeps every window open; once all agree, every window
  closes and `runEventLoop()` returns `LoopEnd.QUIT`. A dialog or menu open as a window of its own
  is not asked and closes with its owner.
- `NativeWindow.setClosePolicy(ClosePolicy)` says what an allowed user's close does: dispose the
  window (the default), hide it (`HIDE`), or end the application (`END_APPLICATION`).
- `Backend.setLoopLifetime(LoopLifetime.UNTIL_QUIT)` keeps the loop running with no window open,
  as a tray application needs, until `requestQuit()` or `stop()`.
- `SystemPreferences` tells an application the appearance, reduced motion and increased contrast the
  user chose for every application: read with `SystemPreferences.current()`, followed with
  `observeChanges`, once per change. The backend reads them from AppKit on macOS, the registry and
  system parameters on Windows, and the desktop portal on Linux, with GNOME's animation switch where
  the portal has no reduced-motion key. A desktop that says nothing, as Ubuntu's default appearance
  does, answers `ColorScheme.NO_PREFERENCE`. The toolkit does not switch the theme; the theming
  guide shows the few lines that do.
- `Motion` answers how long the toolkit's motion takes given what the user asked for, for an
  animation that drives its own clock.
- The file choosers are the platform's own and no longer stop the application:
  `window.fileDialogs()` (on `NativeWindow`, which replaces `Backend.fileDialogs()`) opens a sheet
  on macOS (`NSOpenPanel`, `NSSavePanel`), a dialog the window owns on Windows (`IFileOpenDialog`,
  `IFileSaveDialog`) and the desktop's chooser through the XDG desktop portal on Linux, which also
  works inside Flatpak and Snap. While one is up, every window keeps drawing and timers keep
  running. A window shows one chooser at a time; a second request answers empty at once. On a
  Linux desktop with no portal the old tinyfd chooser remains, and there it still blocks.
- A key event says what the active keyboard layout types at the key: `KeyEvent.layoutCodePoint()`,
  and `KeyEvent.shortcutKey()`, the Latin letter or digit printed on the keycap, or the physical key
  when the layout prints none there. Every built-in shortcut matches the keycap, so Ctrl+Z on a
  French AZERTY keyboard is the key printed Z (it was the key printed W), and a Cyrillic layout
  keeps Ctrl+Z where it is, as native applications do. Alt and F10 in a menu bar stay physical.
- `PreeditEvent` names the part of a composition the input method is converting:
  `targetStart()` and `targetLength()`, in code points, beside the caret.
- A window can be created with its owner: `WindowConfig.popup(owner, width, height)` for a popup
  drawn outside the owner's frame, `WindowConfig.ownedBy(owner)` for a window of its own that closes
  and moves with the owner, `atInOwner(x, y)` to place either in the owner's logical points, and
  `title(String)` to name a popup for assistive technologies. The backend registers the owner when
  it creates the window and forgets it when the window closes.

### Changed

- A `Tree` keeps the children a load produced for as long as their row is open, and lets them
  go when it closes: opening the row again loads again. It kept the children of every node
  ever opened until `refresh()`, which over a model with no end, a disk, is everything the user
  has ever looked into. A model that wants a second opening to cost nothing keeps what it
  loaded and answers it from `children`.
- A radio that is out of the scene it was in is out of its `ButtonGroup` until it is back: the
  group does not hold it, `members()` does not list it, and the others no longer count it in
  the "2 of 4" a screen reader speaks. It takes the place it was added at when it returns, and
  gives way if the group chose another meanwhile. A form rebuilt against the same group left
  it every radio of every form before.
- The file `Transfer.withImageAsFile` writes goes in one directory made for the run, under the
  system's temporary directory. The file of the transfer before is deleted when the next is
  written and the directory when the program ends, where each file stayed, in a directory of
  its own, until the program ended in good order.
- The shared loads of `Images` and `Sounds` are kept within a budget. `loadShared` and
  `fromResourceShared` kept every picture and every clip they had ever loaded until the
  application ended, so one that shows a picture per record grew without limit. What landed is
  kept while it fits a budget, a quarter of the most heap the process may use for pictures and
  an eighth for sounds; past it the one asked for longest ago is let go, every file before any
  resource of the application's own. What is let go and still shown or held is the same object
  when asked for again, and nothing is read twice while somebody holds it. `setSharedBudget`
  moves the budget, `Long.MAX_VALUE` being what it was before, and a load made with
  `loadPinned` is never let go.
- A window that closes takes its widgets out of the scene: each widget's `onDetached` runs, as
  it does when the widget is removed. What a widget took in `onAttached` from something that
  outlives its window is given back there, and a window closing ran no such hook, so whatever
  held a widget of a closed window held the window's whole scene. A scene bound to another
  window afterwards has its widgets attached again.
- `ListView`, `Table` and `Tree` walk their rows with one engine, and behave alike where they
  did not. A row brought into view from outside it stops at the edge it came from, by its
  measured height: a list put it at the top whichever side it came from, so a row moved or
  selected just below the box threw the rows above it out of sight. Page Down and Page Up move
  the view with the cursor in all three, which keeps its place on screen, as it did in the
  table. All three tell a watcher of a scroll, a `VALUE` change with its origin, as the table
  did. A list given no height by its layout is eight rows tall, as the other two are, where it
  was six; `setVisibleRows` says otherwise. A scroll that follows a reveal not yet shown takes
  its place, and a tree scrolled by more than two boxes lands by its estimate instead of
  measuring every row on the way.
- A table's header sorts when it is clicked, on the release, and no longer on the press: a press
  that travels now moves the column.
- A popup menu in a window of its own leaves the keyboard focus on the window that opened it, as
  Windows' and macOS's own menus do, and is read by screen readers as part of that window. NVDA and
  VoiceOver no longer announce a window as a menu opens and the application's window again as it
  closes, nor a "Menu" group before the first row; VoiceOver reads a submenu opened from the
  keyboard and reads a menu's first row once. The owner's keys go to the open menu. A menu opened
  with `setModal(true)` still takes the focus, as it blocks its owner.
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
- A window's close handler answers later: `setCloseRequestHandler` takes a `CloseRequestHandler`,
  which is handed a `CloseRequest` to `allow()` or `refuse()` once, now or from a dialog's result,
  instead of a `BooleanSupplier` answered on the spot. The old way to confirm with a dialog (veto,
  show the dialog, call `requestClose()` from the answer) becomes answering the request from the
  answer; a second click on the close button while the question is open asks nothing new. To
  move, turn `() -> { …; return ok; }` into `request -> { …; if (ok) request.allow(); else
  request.refuse(); }`. The handler is also asked when the application ends.
- `Backend.runEventLoop()` returns why it ended: `LAST_WINDOW_CLOSED`, `STOPPED` or `QUIT`. Code
  that ignores it compiles as before; a backend of your own returns it.
- On macOS, Cmd-Q, the application menu's Quit and the Dock's Quit make the same request as
  `requestQuit()`. They used to close every window whose handler agreed, newest first, even when
  another refused, so a refusal left the application with some windows gone.
- While the user has asked the system for less motion, animations finish at once: a `Transition`
  jumps to its target, and menus, combo box lists, date pickers and dialogs open and close without
  fading. The indeterminate `ProgressBar` sweep and a `Tree`'s loading spinner keep moving, since
  they say that work goes on, but in steps, four times a second.
- The demo opens on an application, Limn Showcase, where it opened on a window of sixteen tabs: a
  screen of orders (a table that sorts and filters, an order opened in a panel that slides in
  beside it, a dialog a new one is made in, a refresh that works off the UI thread) and a navigation into a
  gallery of every component by what it is for, the media and the 3D viewport last. Its frame,
  its orders, its dialogs and its menus are translated into the 21 languages the old window was;
  each page of the gallery has a button that opens where the site documents it; the performance
  footer is shown from the View menu. `--scene app` names it, the `--scene kitchen*` names are
  gone, and every scene of the lab is still opened alone by `--scene`. The sample film the demo
  plays has its soundtrack.
- The demo opened with no scene named follows the system's light or dark appearance until a theme
  is picked.
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

### Breaking, and how to move

- `SvgRasterizer` is `SvgRenderer`, a document opened, measured, changed and drawn, and
  `SvgIcon.installRasterizer`, `uninstallRasterizer` and `isRasterizerInstalled` are
  `installRenderer`, `uninstallRenderer` and `isRendererInstalled`; `BackendServices` takes the
  renderer. Only a backend, or a test that installs its own, calls them.
- `new ImageView(null)` names which: `new ImageView((Image) null)`, now that a view takes an
  icon too.
- `Widget.onFileDrop` and `FileDropEvent` are gone: files dragged in from the system are a drop
  like any other. A widget accepts them in `onDragOver`
  (`if (event.offer().has(DataFormat.FILES)) event.accept();`) and reads them in `onDrop`
  (`event.offer().read(DataFormat.FILES).thenAccept(files -> …)`).
- `Clipboard.get()` and `set(String)` are `text()` and `setText(String)`, now that the clipboard
  holds more than text. A class that implements `Clipboard` for a test implements `text()`,
  `set(Transfer)`, `content()` and `onChange`, or is replaced by `Clipboard.inMemory()`.

- `ComboBox` is `ComboBox<T>`: its items are objects, read by a function or as themselves.
  `new ComboBox(List.of("a", "b"))` becomes `new ComboBox<>(List.of("a", "b"))`, and
  `new ComboBox<>(customers, Customer::name)` shows objects. `onSelect` takes a `Runnable`, as
  `ListView`'s, `Table`'s and `Tree`'s do, since a combo can now hold more than one item: the
  handler reads the selection, so `onSelect(i -> apply(values[i]))` becomes
  `onSelect(() -> apply(combo.selectedItem()))`, or reads `combo.selectedIndex()` where the index
  is still wanted. `selectedItem()` answers the item; what it reads as is `selectedText()`, which
  is what `selectedItem()` used to answer. `ComboBox.localized` answers a `ComboBox<I18nString>`.
- `FileDialogs`' methods return a `CompletionStage`, completed on the UI thread, and are reached
  through a window: `backend.fileDialogs().openFile(title, folder, filter)` becomes
  `window.fileDialogs().openFile(title, folder, filter).thenAccept(chosen -> …)`. An empty answer
  is still a cancel, and a headless backend still answers empty.
- The interfaces only a backend implements moved to `limn.backend.spi`, a package marked
  incubating: `WindowInput`, `FrameInfo`, `FrameCallback`, `GpuRenderer`, `BackendServices`,
  `ModalStack` and `Installed`. `NativeWindow` and `Backend` are sealed; a backend implements
  `WindowPeer` and `BackendPeer`, which carry what the toolkit asks of a platform (text input,
  the preedit caret, child popups, the frame callback, modal stacks). `WindowPeer.of(window)` and
  `BackendPeer.of(backend)` reach them from the application type. An application that only uses a
  backend changes nothing but imports.
- `setImeEnabled(boolean)` is `WindowPeer.setTextInput(TextInput)`: `NONE`, `CHARACTERS` or
  `COMPOSITION`. The scene asks for characters, not nothing, when the focus is not text, so a
  list's type-ahead works on a platform that sends no characters otherwise.
- `WindowInput.preeditChanged(text, caret, targetStart, targetLength)` replaces the form that took
  GLFW's styled blocks and focused block; `PreeditEvent`'s constructor takes the same four values.
- `WindowInput.keyEvent(key, layoutCodePoint, pressed, repeat, modifiers)` is the method a backend
  implements; the four-argument form remains as a US layout, for tests.
- `WindowInput.filesDropped(paths, x, y)` takes the point of the drop in logical points, and the
  files go to the widget there. GLFW's backend, which is told no point, reads the pointer at the
  drop.
- `ComboBox`, `DatePicker`, `PopupMenu` and the native `Dialog` register as their window's popups
  through `WindowConfig`; code that called `registerChildPopup` or `unregisterChildPopup` for its
  own windows passes the owner to `WindowConfig.popup` or `ownedBy` instead.

### Known, and left for later

- Of a drag between programs. Under Wayland there is no link, the protocol having copy and
  move only; with no key held it is the target's preference that decides between those two,
  not the source's; and at a fractional scale the picture that follows the pointer is rounded
  to a whole one. On X11 a program built on GTK 4 talks to the window itself from its second
  message on and goes round the channel this toolkit answers drags on: its files are taken
  all the same, at the drop and with no answer while they hover, and anything else it drags,
  text for one, does not arrive. Programs built on GTK 3 and the other toolkits tried are
  answered in full.

- In a combo holding many, VoiceOver reads each toggle as the field's new value followed by "text
  inserted", and now and then a lone "None" around the Escape that closes an editable one's list
  (two runs in three; its cause was not found); Orca 46.1 does not
  announce a toggle (it reads the field's value once the list closes). NVDA and Orca 50.2 read
  "selected" and "not selected" at each toggle.
- Orca 50.2 reads an editable combo's list with its count again ("Cordilheiras, List with 2 items,
  Carpathians") on the first Down after a letter is typed: the field's caret puts Orca back on the
  field as the letter lands, so the next move into the list is a move into it. Orca 46.1, NVDA and
  VoiceOver read the option alone.
- Orca reads a calendar's whole week, with "not selected" after each day, when the cursor moves
  to another week (Up, Down, a page): Orca speaks the full row of a table by default ("Speak full
  row in GUI tables") and says "not selected" of every selectable cell that is not, and a
  calendar's cursor is not its selection. Left and Right read the day alone. Turning that Orca
  setting off reads one day on every move.

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
- On Windows, a UI Automation client's `SetFocus` on a column header, or on a row of a list or a
  tree in `SINGLE` (where the cursor is the selection), is refused; the container's is accepted.
