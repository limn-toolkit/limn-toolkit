# Changelog

What changed between releases, and what to change in your code when you move. Newest first.

Each section is named after the release it follows. It is complete on the day the next version is
tagged, and no release has to edit this file to rename it; the release on GitHub and the version on
Maven Central say which number that was.

## After 0.9.0

### Fixed

- With partial rendering, a `Transform` that an in-place layout moved (a sibling shown or hidden, a
  label growing, a child hidden inside it, its wrapper hidden) left its old picture on screen and did
  not draw the new one until a whole frame, in perspective too and for a picture zoomed past the
  view it scrolls in; both places are now repainted, and a `Transform` the layout did not move is
  not repainted at all.
- A container taken out of its window, or moved to another, in the middle of a fade or a motion
  runs its `onArrive` (and a flip's swap) only once it is out of that window, so a fade asked for
  there follows the container instead of being stepped by the old window's frames; a focus or pointer
  handler that throws during the removal, an overlay push or a popup's close no longer drops them.
- An `onArrive`, or a flip's `atEdge`, that throws no longer freezes the container's later fades and
  motions; a fade asked for before the throw, and motions beside it, still arrive.
- A `Popover`, a combo box's, date picker's or tab strip's list, and a `PopupMenu` shown at a widget
  (a field's own context menu among them) close as an adjustment when the widget they hang from
  goes: taken out of the window (by itself, with a container, with a dialog it was in, with the
  window, with a page `Scene.setRoot` switches away, even to be put back elsewhere), hidden (itself,
  with a container, a tab page or a folded expander), or drawn as nothing by a `Transform` (a face
  turned away, a scale of 0); and at once when a popover they were opened from closes. Each stayed
  over the window holding the keyboard and eating the next click, or said it was open with nothing
  shown, and a context menu's rows acted on a field that had left. A control asked to open while
  hidden or on its way out stays closed, and a date picker no longer commits a date from a calendar
  that was never shown. A menu shown at a point of the scene hangs from no widget. A row of a
  `ListView`, `Table` or `Tree` that a `refresh()` or a `setItems`/`setRows` keeps because its item is
  still in the list does not leave: a menu or a popover on it stays open through a timer's refreshes
  and acts on that item, and the row shows the item afresh once the popup has closed. A field's own
  menu in a list's or a tree's row still closes on a refresh, the keyboard going back to the list. A
  `MenuBar`'s dropdown closed so is announced as the bar's own adjustment, not as the person's.
- A press beside an in-scene popup that is fading out reaches what is beneath it; a press on the
  fading card, such as the second click of a double click on an item, stays the popup's and never
  reaches the control under the item. Closing a popover opened from another gives the keyboard
  back at once, pressing beside an open editable combo box's list leaves the keyboard in its field,
  and window shortcuts work while a popup fades out.
- An exception thrown by a popup's or a popover's `onClose`, or by a focus handler, while another
  popup closes, while a widget is added, removed or hidden, or while an overlay is pushed or
  removed, no longer leaves a layer over the window, a popup window open or the change half done (an
  overlay off the stack but still in the scene, the gap of a removed row left in its column); it is
  rethrown once everything has run. An `OutOfMemoryError` or a `StackOverflowError` leaves at once,
  and no application code runs after it, a fade's `onArrive` included, except as a window closes:
  the close runs every step of its teardown, and every `onArrive`, whatever one of them throws.
- A popup's or a popover's `onClose` that moves the widget being removed leaves it where it put it:
  back into a column of the same window or of another, onto another window's overlays, or as the
  root of a new scene, the widget is in that scene, told `onDetached` in the window it left before
  `onAttached` in the new one, with the focus the handler gave it, and its popovers open again; an
  overlay the handler pushes again stays on top with its keyboard. It was left in the tree in no
  scene, taking clicks while no popover opened from its buttons, or told nothing of the move, so
  what it registered in one window was given back to the other.
- A form that asks for the keyboard on one of its fields from its own `onAttached` gets it there,
  after the field's own `onAttached`, whether it is added to a window, made a scene's root, pushed
  as an overlay or a popup, or shown in a `Dialog` or a `Popover`: the push no longer moves on to
  the next stop, which typed a name into the email field. The keyboard lands only on a widget in the
  scene, so an overlay pushed while an `onAttached` in it throws no longer hands it to a widget the
  walk never reached.
- A face a `Transform` stops drawing that a popup's `onClose` draws again (its scale put back to 1)
  keeps its focus. A layout that tips a plane a rounding error past edge-on closes its popovers once
  the pass is over: a popover's `onClose` that took the card out of its column ran inside that
  column's layout and lost the frame to an `IndexOutOfBoundsException`.
- A `Popover` holding nothing focusable puts a screen reader on its named group, focusable and
  focused, in the scene as in a window of its own; no warning about a private class is logged.
- A control that arrives in a clicked `Card`'s content answers again once it is moved to the footer,
  taken out of the card or left in a card that is no longer clicked, and a card put back into a
  window keeps its controls out of reach at once; the card keeps no hold on a control it gave back.
- An `Avatar` squeezed to a point or so across draws its tinted circle without letters instead of
  throwing on every frame, and an `AvatarGroup` no longer cuts initials under the overlap: each
  covered circle centres them on the part that shows, all at one size for the whole group (the
  label's, or the smaller size its widest pair needs).
- `setEmptyContent` and `setLoadingContent` on `ListView`, `Table` and `Tree` refuse, with an
  `IllegalArgumentException`, a content already in another container or one that holds the widget;
  a widget that leaves the window lets its content go, so a rebuilt page can give the same label
  to a fresh list. One already on the page was accepted, the next frame was lost to an exception and
  the content was then drawn twice on every frame. A list waiting for a content another list holds
  shows it once it is let go, also after it has asked for another content held elsewhere; it
  watched the first holder and stayed blank.
- A list, table or tree that is hidden, on a tab page that is not selected, or on a face a
  `Transform` does not draw no longer announces "Loading Orders" or "Orders, 12 items"; it stays busy.
  The announcement names a widget labelled by a caption bound with `setLabelFor`.
- `Card.setFooter` and the part setters check before they change anything, so a refused call leaves
  the card as it was, and all throw a documented `IllegalArgumentException`.
- In a masked `TextField` under a right-to-left layout, Arabic-Indic or Persian digits no longer show
  their groups reversed, and a Cut the field refuses no longer writes the clipboard.
- An edit before the end of a masked `TextField` no longer corrupts its value. The field fitted
  the whole edited text afresh, so a literal the edit shifted could be read as a digit or a letter
  and value characters fell off the end while `isMaskComplete()` stayed true: under
  `+1 (000) 000-0000`, Home then `9` on `+1 (555) 123-4567` gave `+1 (915) 551-2345`; `****-****`
  took its own dash after a Backspace; a quoted `9` became a digit. An edit is now made to the
  value: the characters it took go, what was typed or pasted goes in where it landed, and the
  literals stay where the pattern has them. An undo or a redo puts back exactly the value it had.
- A `Dialog` no longer deletes on Return when its destructive button comes first or stands alone.
  It opens with the focus on its default button, or on a field of its content, and Return on a
  focused destructive button gives the default answer; a dialog whose only buttons are destructive
  does nothing on Return, and Escape still cancels. Space, a click and a screen reader's press
  still press the button, in a window of its own and in the scene.
- A popup opened from a control inside a `Popover` shown in a window of its own reaches screen
  readers: a `ComboBox`'s list, a `DatePicker`'s calendar, a `TextField`'s edit menu, a combo in a
  popover opened from a popover. Each is published in the owner window's tree under the control
  that opened it, laid out, with its cursor moves and a reader's actions; it was in no tree, and
  the combo said it was expanded over no list. This was the default on Windows, macOS and X11; an
  in-scene popover, Wayland's, was not affected. A combo list drawn in the scene
  (`setDisplayMode(IN_SCENE)`) and a dialog kept in the scene, shown from inside such a popover, are
  published too; on screen they are still confined to the popover's window.
- One element with a negative size no longer freezes the tree a screen reader sees: every later
  change failed to publish. It is published with no size, and later changes are published.
- A masked `TextField` no longer reports an edit that changed nothing: typing or pasting into a
  full field, typing a literal the field steps over, or typing a selected literal over itself ran
  `onChange`, sent a text change to watchers and a caret move to screen readers, and left an undo
  step that did nothing. A caret that moved is reported as a selection change, and nothing else is.
  A key the field refuses no longer clears Redo, nor drops the oldest undo step of a full history.
  Stepping over a literal never leaves the caret between a character and the mark drawn on it.
- A widget laid back in perspective by a `Transform` and dragged past the horizon no longer takes
  NaN: a `Slider` or a `RangeSlider` dragged there took NaN as its value and committed it on
  release. The scene withholds a drag whose point is not on the widget's plane, so the widget stays
  where the pointer last was on it, the release arrives there, and the drag goes on when the
  pointer comes back, and a release whose pointer is off the plane, or whose plane turned while the
  button was held, lands at the last point the pointer had on it. Every drag handler, the toolkit's
  and an application's, has this without a guard of its own; only a widget of which nothing can be
  seen (its plane exactly edge-on, or its whole box behind the eye) still gets its release at a point
  that is not a number.
- A pulse made of `fadeTo` or `slideTo` calls chained in `onArrive` no longer throws
  `StackOverflowError` out of the first call on the UI thread when every step goes straight to its
  target: for a person who asked the system for less motion, before the container is in a shown
  window, or for zero seconds.
- A `DatePicker` closed and opened again before its close had ended, in one handler or within the
  close's fade, no longer shows an empty popup while `isOpen()` answers true.
- The Showcase video page's soundtrack button follows a change of language, its "Generated
  soundtrack" and its audio-track line ("Audio 1 of 2: eng") alike; it kept the language it was
  built in.
- The Showcase's Video page opens on its sample film, Big Buck Bunny, with its credit under it, and
  opens it in the background. It showed colour bars whichever entry was picked, and froze the click
  that reached the page for up to half a second while the film opened. The bars are the picker's
  second entry, and the page opens on them where the video library will not load; a page left
  before its film opened opens it when it is back. The lab's `--scene video` still opens on its
  bars, and its entry that writes a clip of its own is captioned "MP4". The page closes its player
  when it leaves the window, for another page or with the window, and opens the film again where it
  had reached, playing if it was, when it is back: a page left with its film open held the player
  until the process ended, and the backend closed it with a warning that it had been left open.
- The Showcase's Dialogs page no longer opens "Save changes to Harbor Supply?" by itself a moment
  after "Edit customer…": the question comes when the user cancels the form after changing a field,
  over the form still open, and its Cancel goes back to the form as it was left.
- A dialog with a second one open over it no longer shows a dark square at each corner of its card.
  The veil that dims a window a modal locks, and the scene under an in-scene dialog, was painted
  over the whole frame, so a window clear round a rounded card took half-opaque black there; clear
  pixels stay clear now, and opaque ones darken as before (see Changed).
- Cut, Copy and Paste from a text field's or a text area's own context menu, and Paste from an
  application's Edit menu, act on the selection the person had when the menu opened where the menu
  is drawn in the scene (Wayland, a fullscreen window, or as asked): Copy left the clipboard as it
  was, Cut cut nothing and Paste went in beside the selected word, with every row enabled. The
  selection is kept for them when a listener moves the caret, or sets the text the field already
  holds, as the field loses the keyboard; a different text ends it, so a menu never acts on
  characters nobody selected.
- On X11, files dragged from a file manager built on GTK 4 are followed by the pointer: the widget
  under it is asked as they hover, a page scrolls under them at its edge, and they are offered as
  files alone and as a copy alone, so the source never deletes what it moved. Such a program sends
  its later positions, or nothing at all, to another window than the one this toolkit answers drags
  on; the pointer is now read at each turn while the drag is over the window with a button held. A
  release over a place that refuses, after a rest, calls the drag off, as Escape does; a window of
  this program's left for another of its windows is told the drag left; an `onDrop` that throws
  tells the source the drop was refused, where it left it waiting. A drag of this program's own,
  and one from a program built on GTK 3, are answered as before. Tried on Ubuntu under X11.
- An SVG whose style sheet or class lists are built to be slow no longer freezes the UI thread on
  its first draw. One element listing 200,000 class names (1.5 MB) took 17 s to read; a rule
  `.k.aaa…` against a class `k aaa…` took 10 s at 600 KB and nearly three minutes at 2.4 MB; a rule
  naming 10,000 classes took 6 s, one naming 200,000 attribute conditions 20 s, and a rule naming an
  id megabytes long 27 s, even when a near-identical id never matched. None was refused, because the
  reader's work limit counted each selector tried as one step whatever it compared. The reader now
  looks each element's names up once against the names the sheets use, then compares numbers, and
  its work limit counts what each comparison costs: each of these files is read in under a second.
  What the reader works out while matching is dropped once reading ends, where it could stay with the
  picture for its whole life at up to thirty times the file, and a style sheet of more than 100,000
  selectors is refused before it is read: a sheet of a million (7 MB) exhausted a 512 MB heap.
- An SVG sprite sheet, themed drawing or chart styled by rules such as `#icon-12 path`, `.dark path`,
  `.dot.series-3` or `.series-3 .dot` is read again: each such rule was tried on every path in the
  file, and an ordinary 112 KB sheet of 400 symbols was refused as damaged and drew nothing. Such a
  rule is now tried only inside the symbol, theme or series it names: sheets of up to 3,000 symbols
  and charts of up to 200 series over 100,000 points, in either spelling, read in well under a
  second.
- `ColorPicker`'s alpha line is spaced from the last channel line as the channel lines are spaced from one
  another; it stood 2 pt further down at the default size.
- `MediaControls` and `ColorPicker` speak the interface's language. The transport's six names
  (play, pause, the scrub bar, mute, unmute, volume) and the words a screen reader hears from the
  picker (its hex field, hue ramp, saturation and value plane and axes, and before/after swatch)
  were declared in English and translated into nothing, so under pt-BR, Arabic or Japanese a
  reader said "Play" and "Mute"; they are in all 21 shipped languages now. A test refuses any key
  the toolkit declares that a shipped language does not carry, except the colour notations, their
  channel letters and the palettes' own names, which stay as written.
- The empty part of a `Slider`'s and a `RangeSlider`'s rail and of a `ProgressBar`'s groove
  shows on every surface. It was painted in the raised surface's tone, which in the Light theme
  is the page's own (#EDF0F6 on #EEF0F4), so on a page the rail vanished and a slider at its
  middle read as one at its end; it is now the text's ink, faint, on any theme. A disabled
  slider's fill takes the disabled text's grey, darker than the rail it fills, where it was
  lighter.
- An untranslated `PluralString` takes the form English uses for the count. It fell back to the
  English of the form the language in effect chose, and Portuguese counts 0 as "one": an
  untranslated count of none read "0 order".
- Bold text is bold in every script. A code point the primary face lacks was drawn by its
  fallback face's Regular whatever the weight asked for, so a bold Arabic, Hebrew, Hindi, Thai,
  Japanese, Korean or Chinese name sat regular beside bold Latin; a bold primary now takes the
  fallback's own Bold, and a family with none, the CJK face, is drawn with a synthetic bold that
  spreads each glyph by about a twenty-fourth of its size and keeps its advance.
- A line break in a wrapped `Label`'s text is a line on screen. The text was wrapped as one
  paragraph, so a break that fitted the width was shaped as nothing and the two lines ran
  together ("Hello,A reminder"); each piece between two breaks is wrapped on its own now, and an
  empty one is a blank line.
- A menu asked for with the pointer opens at the pointer. `ContextMenus.attach`, a `TextField`
  and a `TextArea` passed the mouse event's point, which is the scene's, as their own, so the
  menu landed as far from the pointer as the widget was from the window's corner; the keyboard
  route was right.
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
  `scrolled` that carries it, for a backend that can tell. A modal that opens in the middle of a
  gesture takes the rest of it, where the scroller beneath kept scrolling under its scrim until
  the inertia ended.
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
- Play on a `MediaPlayer` with a soundtrack no longer holds the UI thread while the audio device
  opens: `start()` hands the track to the engine on a thread of its own, not the application's
  worker pool, and returns at once (190 ms on the UI thread before, 0.2 to 7 ms now, measured on an
  Apple Silicon Mac; 458 ms in a Showcase session on the first play of its film). The player is
  `PLAYING` from the press, so a transport's button says Pause at once, the first picture is shown
  and the others wait for the sound so the two begin together, a quarter of a second at most: a
  device slower than that finds the pictures running on the wall clock, and the track joins them
  where they are when it arrives (one that cannot be moved there is let go, and the video plays on
  silently). A pause, a seek, a stop or a close made meanwhile reaches the track as it arrives and
  before it is heard: the engine starts it silent, and it takes its gain last. `audio()` answers
  `Playback.NONE` until it arrives.
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
  removed or not, with whatever it held. A form whose caption names one of its fields goes with
  it too, a date picker or an editable combo box among them: the walk, and the tree it builds,
  kept the caption's target until a later walk reached it.
- On Windows, with a screen reader running, what stood for a control that has left the window is
  let go when it leaves, and freed once the reader lets go of it too. It was kept until the
  window closed, which for an application's main window is never. What is handed to the reader
  is counted under the object's own lock, so a control leaving as the reader asks for it is
  answered as gone, never with what is about to be freed.
- On macOS a window that is closed is freed. Every window an application had closed stayed in
  the system's overview of windows as an empty frame with its title: the toolkit's own calls to
  the system were handed references that nothing released, since the event loop had no
  autorelease pool of its own. Each turn of the loop has one now.
- A window that had the keyboard hands it on when it closes, to the window that owns it or to
  the one in front. On macOS the application was left with no window to type in, and its menu
  bar fell back to the one an application with no window shows, until a window was clicked.
- A combo box's list, a calendar or a menu drawn in the scene gives the keyboard back as it
  starts to close, and leaves what a screen reader reads at the same moment. The focus came back
  when the fade ended: until then a key typed went to a list or a calendar that was closing, and
  VoiceOver, standing on a layer about to be destroyed, read a neighbour of the field before the
  field.
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
- An in-scene `Dialog` answered while it is still fading in closes. Its fade in went on
  stepping up beside the fade out stepping down, the two cancelled each other, and the dialog
  stayed on screen with its veil half drawn, its result never completed and the window locked
  behind it. The card's fade is now the dialog's: the veil and the slide follow it, and the
  fade out runs from where the fade in had got to.
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
  text and focus. They stayed as windows over an owner that had changed under them. The dialog moves
  also where its window is wider than its card by the shadow the toolkit draws round it, where the
  move threw and left a dialog in neither place, and the window it left keeps no keyboard on a
  widget it no longer holds.
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
- The demo and the theme editor, run with `jbang` or resolved by Maven, download one platform's
  SVG renderer natives, as they already did LWJGL's. Their POMs cut only LWJGL out of the
  backend's dependencies, so all six slices of `limn-lunasvg-natives` arrived on every machine.
- On the module path, the error for LWJGL's native libraries not loading says which of two
  causes it was, with this machine's classifier (`natives-macos-arm64`): the natives modules were
  never resolved, and here is the `--add-modules` line, or another platform's jar won the module
  name, and here is the jar. Every platform's natives jar of one LWJGL module declares the same
  module name, and the backend brings all six, so on an Apple Silicon Mac the Intel Mac's jar
  could be the one loaded, and the `--add-modules` line the error advised could not help.
- A Gradle build that asks for the toolkit's documentation, as an IDE does, gets the javadoc jars:
  the published Gradle module metadata lists them beside the sources jars. They were published,
  but only Maven's naming convention found them.
- The `limn-toolkit` POM no longer says it depends on nothing: it names the WebP decoder it needs
  at run time and the JSpecify annotations it compiles against.
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
- A second click on a `DatePicker`'s calendar button closes the calendar where it is a window of
  its own (macOS, Windows, X11): the press closed it and the click opened it again.
- `Scene.setLocale` on a scene whose window has closed keeps the languages in use counted right:
  a language could stay loaded for ever, or be let go while another window still showed it.
- A legacy or three-letter language code writes the digits of the language it stands for: `pes`
  and `prs` Persian digits, `arb-EG` Arabic-Indic ones, `npi` and `mar` Devanagari. Java keeps
  these codes as they are, and they all answered Latin digits.
- No line appears at the edge of a `BackdropPanel` when something inside it repaints. The panel's
  rectangle went into the repaint at its layout position, a fraction of a point in, where every
  other repaint rectangle is whole device pixels, so the pixel its edge cut was cleared and painted
  back only in part, with the window's background mixed in: a thin line ran down the panel's side
  through the picture under it, on a Retina screen, at window widths that put a centred panel off
  the grid, and stayed until something repainted that pixel whole.
- A `BackdropPanel` repaints with the margin its effect reads around it, a blur's radius or a
  pane's depth, and repaints when something in that margin changes. It repainted its own box only,
  so a change just outside a frosted panel left it showing the old blur, and a partial repaint let
  the blur read that margin as the last frame had finished it, with whatever was drawn over it: a
  scroll bar over the end of a frosted toolbar came out blurred into the glass.
- A child is not hit where its parent cuts it away: content running on under a `ScrollView`'s
  reserved scroll-bar gutter took the press, the hover and the cursor meant for the gutter
  whenever the bar had faded. A widget that clips its children now cuts their hit test where it
  cuts their paint.
- `hitTest` at a point that is not a number answers `null`; a `ListView`, a `Tree`, a `Table`, a
  `SplitPane` and a `ScrollBar` answered themselves.
- A press over an `Expander`'s content while it slides shut lands on what is beneath it, as over
  a hidden widget; it always landed on the content's parent.
- An in-scene `Dialog`'s card slides in as a picture moved, through a `Transform`, and neither the
  slide nor the veil asks for an accessible walk on each frame of the fade; the card was laid at a
  new place inside every frame's paint and the tree walked again for it. A drag still moves the card
  for real.
- A control inside a disabled or hidden container is out of reach on every path. Code calling
  `requestFocus`, a dialog giving the focus back as it closed and a menu bar letting go of the
  keyboard could put the focus on a button inside a disabled form, and Enter then ran its action;
  the control also kept its enabled look there.
- A window clears to the palette in effect. `Theme.light().apply()` before the first window drew
  light controls on a dark canvas, and a window opened after a switch, or never handed to `apply`,
  mixed the two palettes.
- A theme file's eight-digit tone loads opaque, except the scrim's: `focusRing = #5B9DFF80` drew a
  half-transparent ring on lists and an opaque one on buttons.
- A table under Arabic, Egyptian Arabic or Persian writes every figure in one set of digits: a
  `Column.of` formatter is handed the locale the toolkit writes numbers in, where the bare locale
  wrote `ar` in Arabic-Indic digits.
- A message bound with `Label.setDescriptionFor` to a `DatePicker` describes its field, where a
  screen reader is; it described a group that publishes no node, and the reader heard nothing.
- A `Label` whose icon comes or goes lays out in place, as a change of its text does, instead of
  laying out and repainting the whole window.

### New

- `Popover.detach()` lets a button go; a button opens one popover and a popover hangs from one
  button: `attachTo` on a button that has one detaches the other, `attachTo` on another button
  moves it (only the old button hears it collapse), and a detached popover still opens from
  `showFor`.
- In limn-test, `HeadlessWindow` exposes `textInput` and `preeditCaret`, `screenPositionRequests()`,
  `requestedScreenX()` and `requestedScreenY()`, `RowsSubject.empty(Widget, Label)` checks a list's,
  table's or tree's empty content, and `HeadlessBackend.setCompositorShadowsPopups(boolean)` says
  whether its compositor casts a frameless window's shadow, so a test can play a desktop that casts
  none, where the toolkit draws its own in a margin of the window. Also new since 0.9.0 and named
  here: `Avatar.setInitials` and `setColorIndex`; `Popover.setAlignment`, `setDisplayMode` and
  `setAccessibleName`; and `isLoading`, `isShowingEmptyContent` and `isShowingLoadingContent` on
  `ListView`, `Table` and `Tree`, which answer what the widget shows as of its last layout.
- Part of a window fades as one picture in an `Opacity` container (ADR 071), which wraps one
  widget as `Padding` does: `new Opacity(panel)`. `setOpacity(float)` sets how opaque the child and
  everything under it are drawn, from 0 to 1, and `opacity()` answers it; `fadeTo(target, seconds,
  onArrive)` moves it there eased as the toolkit's popups fade, and `fadeTo(target)` over the
  theme's `animWindow`, in a finite number of seconds. Between 0 and 1 the child is drawn into an offscreen picture laid over the
  window once, so the parts of a panel do not show through each other halfway; a container inside
  another multiplies its opacity; at 1 nothing extra is drawn and at 0 nothing is drawn. Under a
  blend mode a parent's paint left in force, the container's child is one picture blended once at
  every opacity, 1 included, so a fade arriving at 1 does not jump. The container is nothing else:
  it measures and lays out as its child alone, a screen reader does not see it, and a widget that is
  in none pays nothing. It is a picture and not a presence: at 0 the child is still laid out, takes
  a click and the keyboard, and is read by a screen reader, and `setVisible(false)` is what takes
  it away, which a fade out does in its `onArrive`. While the system asks for less motion, or with
  no window to fade in, a fade goes straight to its target and runs its `onArrive` after the seconds
  it was asked for, or on the next turn of the UI thread for zero seconds, never inside `fadeTo`; the
  next step of a sequence or a pulse is asked for there, keeps its pace and lets the UI thread sleep
  between steps, and runs for as long as the code asks, so a pulse stops itself when what it stands
  for is over or its container is no longer showing. A newer `fadeTo` or a `setOpacity` stops one in
  flight, or drops an `onArrive` still waiting, which never runs; a container taken out of its window mid-fade arrives
  as it leaves, and runs its `onArrive` then. A watcher of the container hears
  `Change.Aspect.OPACITY` each time `setOpacity` changes the value and twice for a fade, as it
  starts and as it ends. Measured on an Apple M5 Max, a popup's fade frame costs 0.04 ms of CPU and
  0.02 ms of GPU more than the same frame opaque; on Apple's software renderer about 3 ms of a
  150 ms frame. A window keeps the pictures only while consecutive frames use them, and its
  performance monitor counts them.
- `Canvas.saveLayer(x, y, width, height, opacity)`, closed by `restore()`, draws what is painted
  until the restore as one picture composited with the opacity: what a widget that paints
  overlapping parts of its own uses to fade them together, as `Chart` fades its tooltip panel.
  Inside it drawing starts from `BlendMode.NORMAL` and the picture is blended in the mode in force
  when it opened, at every opacity: at 1 under `NORMAL` it opens nothing, under another mode it
  opens a picture. A canvas that cannot make pictures, a recording or measuring one, falls back to
  a `save()` and a per-draw `setOpacity`, which its default does, each draw in the mode in force.
- The Showcase's Backdrop panel page fades its floating panel with an Opacity slider and its
  percentage, beside the Coverage slider that sets how much tone the glass lays down, and a button
  fades the panel out and back with `fadeTo`, through an `Opacity` container round the panel. The Animations guide has a section on fading part of
  a window.
- The Packaging guide shows how to keep one platform's natives, with Gradle (a component metadata
  rule keyed on the classifier, for the machine running the build or for a target named with
  `-PlimnTarget`) and with Maven (the backend's LWJGL and SVG natives excluded and declared again
  with one classifier, a profile per system choosing it). The one-line install still brings all
  six platforms: 24.8 MB of jars, measured at this release, where one platform's are 10.3 to
  11.5 MB. An application on the module path needs it, because there every platform's natives
  jar of one LWJGL module has the same module name; the guide has the `--add-modules` line too.
- Access letters come from the translated text (ADR 070). An `&` before a letter marks it in a
  menu title or item, a `Button`, a `Checkbox`, a `RadioButton` and a `Label` that names a
  control (`setLabelFor`), so each translation picks its own: "&File", "&Arquivo", "ファイル(&F)";
  `&&` is an ampersand, and one before a space is text. Alt and the letter press a button, flip a
  box, select a radio or bring the keyboard to a label's control, within the window or the
  topmost modal and before the menu bar; several with one letter take turns with the keyboard.
  The letters are drawn while Alt is held and in a menu opened from the keyboard, on Windows and
  Linux, and never on macOS, which has none: there the mark goes, a trailing "(&F)" with it, and
  no binding is published. `MenuItem.setMnemonic` and `MenuBar.addMenu(title, char, menu)` stay,
  and the text's mark wins over them. `I18nString.withoutAccessLetter()` reads a marked string
  as the words a control shows, for a sentence, a status line or a log that quotes it: "&Open"
  is "Open", "R&&D" is "R&D", and "名前(&N)" is "名前" on every system, "プロパティ(&P)…" keeping
  its ellipsis; it resolves when read, so it follows the language. A reader is told the letter's
  chord. Where Windows is set
  to underline access keys, `SystemPreferences.underlineAccessKeys()` says so and the letters
  stay drawn; a tray menu's native rows take the letter where the text marked it. The model
  carries the access key apart from the accelerator (`ActionFacet.accessKey`, set with
  `Accessibility.accessKey` and kept, as `keyBinding` is, on a node that offers a verb); Windows
  serves both, as UI Automation's AccessKey and AcceleratorKey, which it never served before, and
  Linux as `GetKeyBinding`'s "Alt+S;;Ctrl+S", one alone where a node has one; a field named by a
  marked caption is named by its words and carries the caption's key, unless the caption is
  hidden, disabled or in a hidden section. A letter of any script is published ("Alt+Ф",
  "Alt+É", "Alt+מ"), and a popup menu row publishes its letter beside its accelerator and is
  named by its words. On macOS a Windows-style "(&P)" before an ellipsis goes and the ellipsis
  stays ("プロパティ…"). Alt and a label's letter bring the keyboard to the first part of a
  control that takes none itself (a `DatePicker`, a `RangeSlider`, an `Expander`), and the focus
  a letter moves is the user's (`Change.Origin.USER`). Controls sharing a letter take turns, a
  radio that is not its group's stop among them, and a letter shared in a window is logged once,
  at `WARNING`, so a translator's collision is found in development. A key that prints nothing
  (Home, Insert, an F key) presses no letter, though its code is a Latin letter's; the window's
  letters answer while a list that leaves the keyboard in its field, an editable combo box's, is
  open; a menu opened with the pointer underlines its letters while Alt is held in the window
  that opened it; letting go of Alt during a drag takes the underlines away. Pressing Alt
  repaints only what draws a letter, and nothing where the system keeps them drawn; turning
  Windows' "underline access keys" on or off redraws every open window.
- A video shows a poster before it plays (ADR 069). `VideoView.setPoster(Poster)` takes
  `Poster.firstPicture()` (the default), `Poster.at(Duration)` for the picture at a time,
  `Poster.image(Image)` for a picture of the application's, or `Poster.none()`; playback starts
  at the beginning whichever it is. A view showing a `MediaPlayer` nobody has started showed the
  bar colour and now shows the first picture: the view cues it on the player with
  `MediaPlayer.cue(long)`, which decodes one picture with no sound and no clock and leaves the
  player idle, and `isCued()` says whether that picture is still on its way. A poster that
  cannot be decoded leaves the player idle and play starts from the beginning; a time past the
  end shows nothing, on a looping player too. `Poster.At` and `Poster.Still` are made by those
  factories alone, and `Poster.At.time()` answers a `Duration`, to the microsecond.
- `ScrollView.setBarInsets(Insets)` sets the bars' tracks in from the ends of their
  sides, for content that scrolls under something laid over it, a bar of glass across the top:
  the thumb starts below it and keeps its proportion of the shorter track.
- Tabs that close and move. `TabbedPane.setTabsClosable(true)` makes the tabs added after it
  closable, `isTabsClosable()` reads it, and `setTabClosable(index, value)` makes one tab so: it
  draws a close button while selected or under the pointer and closes by it, by a middle click,
  and by Ctrl+W (Cmd+W on macOS) while the keyboard is in the pane. Pressing a tab's close button
  leaves the keyboard where it was, as a browser's does, so a close refused from a dialog gives it
  back to the page being typed in. `onTabClose` is handed a `TabCloseRequest` to `allow()` or
  `refuse()`, now or later, and a tab is not asked again while its request is unanswered; the
  request is sealed, made by Limn alone, and a test closes the tab as the user does. Closing the
  selected tab selects the next, or the one before when it was the last. A tab is
  dragged along the bar, the others sliding out of its way, and moved by Ctrl+Shift+Page Up
  and Page Down (Cmd on macOS), which a screen reader hears as its new position; `onTabMove`
  hears the user's moves. `removeTab`, `moveTab`, `tabContent` and `indexOfTab` do the same
  from code, announced as `CHILDREN` with the origin of whoever asked. A tab closed while it is
  dragged, by the keyboard or by `removeTab`, ends the drag and takes its page with it, and a
  drag across another tab's close tells `onTabMove` places in the order that is left.
- `Expander`, a section that opens and closes: a header with an arrow that turns, a title, an
  optional icon and an optional summary, over content that slides out below it, at once when
  the user asked for less motion (`Theme.animExpand`). Click, Space and Enter open and close it,
  `setExpanded` from code, `onChange` hears the user. While it slides shut, its content is still
  drawn, not greyed, but answers nothing: a press, Tab and an access letter pass it by,
  nothing inside takes the keyboard, which goes to the header if it was inside, and a screen
  reader finds its nodes with no verb on them and is refused one it sends; the content's own
  enabled state is never changed. `ExpanderGroup` keeps at most one of its sections open,
  and `onSelect` hears the user open or close one, with the open section's index or -1, as
  `ButtonGroup.onSelect` does; a section out of the scene it was in is out of its group
  until it is back, as a radio is out of its `ButtonGroup`. A screen reader hears a button,
  expanded or collapsed, that controls the content.
- `RangeSlider`, a lower and an upper value on one track: two thumbs that stop at each other or
  at `setMinimumGap`, each a Tab stop moved by the keyboard, a press on the track taking the
  nearer one. `setValues`, `lower()`, `upper()`, `setStep`, `onChange` for every step and
  `onCommit` for the range let go at, which a key or a reader's step at the end of a thumb's
  travel does not call. A gap that is not a whole number of steps pushes a value to the next
  step past it, so the values stay on the step's grid, and a value that is not a number is
  refused. A screen reader hears two sliders, Minimum and Maximum.
- A mask on `TextField`: `setMask("000.000-00")`, where `0` takes a digit, `A` a letter, `*`
  any character, and the rest are literals the field writes as the next position fills, and
  those that end the pattern once its last position is filled (`(00)` reads `(12)`). Text
  between single quotes is literal, so `0`, `A` and `*` can be too: `setMask("+'0' 000")` shows
  `+0 123`. Two single quotes are an apostrophe, as in `SimpleDateFormat`, and a quote never
  closed is refused with an `IllegalArgumentException` that names the pattern. Pasting with or
  without the literals gives the same text, and no digit is lost to a literal that is the same
  digit: under `+55 (00) 00000-0000`, `setText("51987654321")` keeps the area code 51 and reads
  `+55 (51) 98765-4321`, and under `+'0' 000` a 0 typed first is the first digit. A text is read
  with its literals when it carries one no position would take (`+`, a space); a pattern whose
  literals are all characters its positions take (`'55'00000`) reads a value that begins with
  them as carrying them, so set it from its formatted text. An edit anywhere is made to the
  value, so no literal is ever taken as a value character, and a field whose positions are all
  filled takes nothing more: select characters and type over them to replace them; a Backspace or Delete with nothing selected steps over a literal to the character
  beyond it, and one undo takes an edit and its literals back. Literals typed by hand show as
  they are typed: under `'DE'00 0000` the `D`, then the `E`, then the digits, and `DE89` and
  `89` give the same value; Backspace takes a run the text ends in back one character at a time
  (`123.4` loses the 4, then the dot). While focused, the positions still to fill show as a guide
  after the text, and the caret at the end stands where the next character will appear, past
  the guide's literals (after `DE` in an empty field, after the dot of `123.___`); the input
  method's window and a click past the end go there too. `unmaskedText()` and
  `isMaskComplete()` read the value. A screen reader hears the field's text, literals included,
  and not the pattern, which is syntax ("A A dash zero zero zero zero"); to say what the field
  expects, give it a tooltip or `setAccessibleDescription`.
- `Widget.moveChild` reorders a child without taking it out of the tree, so its focus, hover
  and press stay, and lays a parent that clips its children out alone. `remove` and `moveChild`
  take who asked, which the one `CHILDREN` change they make carries.
- `Widget.accessibleLabelAccessKey()`, beside `accessibleLabelText()`: the chord of the access
  letter a caption widget of an application's own hands the control it labels.
- In `limn-test`, `SceneDriver.scrolled(dx, dy, x, y, gestureBegins)` drives a wheel event that
  begins a gesture; `ValueSubject.valueNodes()` and `valueNode()` run the value contract over one
  of several value nodes, each thumb of a `RangeSlider`; and `PopupOwnerSubject.disclosesInPlace()`
  runs the popup-owner contract over a disclosure, an `Expander`'s header, which says no
  `HAS_POPUP`.
- In `limn-test`, `TestPreferences` puts the system's preferences in place for an application's
  own test, which cannot build a `SystemPreferences` itself: `new TestPreferences()` in a `try`,
  then `colorScheme(ColorScheme.DARK)`, `reduceMotion(true)`, `increaseContrast(true)` or
  `underlineAccessKeys(true)`, each reported at once as a backend would, and `close()` puts back
  what was there. UI thread, inside a `HeadlessUi`.
- On macOS `-Dlimn.macos.agent=true`, or the environment variable `LIMN_MACOS_AGENT`, makes a
  run an agent, with no icon in the Dock and no menu bar of its own, whatever its package says;
  the variable is inherited by whatever the process starts. The build sets it for its test runs,
  so they do not take the keyboard.
- `SECURITY.md` says where a vulnerability goes: the repository's private reporting, never a
  public issue. The issue templates ask a defect for its version, system and steps, and a
  feature for the need behind it; a question goes to Discussions.
- Animated images. An `Image` may be animated: `isAnimated()`, `frameCount()`, `frame(i)`,
  each frame an image, `frameMillis(i)`, `durationMillis()` and `loopCount()`. Its own pixels
  are its first frame's, so whatever draws an image without playing it draws a still picture.
  An `ImageView` plays one by itself, with `setPlaying`, `setSpeed`, `setLoop` (as the image
  says, without end, once, a number of times, or forward and back), `seekFrame` and
  `onFinished`, which refuses a second handler as every handler slot does;
  `limn.animation.ImageAnimation` is the same clockwork for a widget of an application's own, its
  `Loop` saying how many `turns()` it makes and whether it `bounces()`, equal to another of the
  same. An animated GIF is read as one, where only its first frame was, with the
  times of its frames and the turns it asks for. WebP is read, still and animated, which
  nothing here read before, by a decoder written in Java that the toolkit now depends on,
  `org.glavo:webp` (Apache-2.0), its one dependency at run time: an application adds nothing, a
  modular one finds it on its module path beside the toolkit, and none of it is loaded until a
  WebP is decoded. `Images.fromSheet` makes one of a sheet of
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
  icon's box. A tray plays one in each of its pictures, KDE's monochrome one included, each
  frame written once, and `TrayPicture` says which frame it draws. An `ImageCursor` of an
  animated image plays, counted once in the backend's cache of cursors however many frames it
  has. A menu, the system's or the toolkit's, shows the first
  frame, and so does a window's own icon; the wish for less motion is not applied.
- SVG is drawn by lunasvg, in place of NanoSVG: gradients and patterns, clipping and masks,
  markers, style sheets, CSS custom properties with their fallbacks, and text in the bundled
  faces and the system's, where NanoSVG drew shapes and little else. It ships as the
  `limn-lunasvg-natives` payload, versioned with lunasvg, which the backend depends on with all
  six platforms' jars, so an application adds nothing; `lwjgl-nanovg` is gone. lunasvg and the
  plutovg it draws with are MIT. Every SVG is read by the toolkit first, in Java: within
  ceilings, with no script, event or reference outside the file reaching the renderer, and a
  damaged one refused with `MalformedContentException`. No animation moves a reference, an
  image's `#…` reference is dropped, and every reference is checked again before the renderer
  is given the text, so an SVG read from bytes shows no picture from the disk. The work of
  reading one is bounded too: `var()` substitution writes at most four characters for each byte
  of the file, a style sheet's rule is tried only on the elements its last compound can name (by
  id, class, type or attribute) and the tries a file may make grow with its size, and working out
  when chained SMIL animations begin is counted, so a file of a few hundred bytes can neither run
  the heap out nor hold its thread for seconds, while an export of hundreds of thousands of paths
  under hundreds of class rules is read in under a second.
- `SvgIcon` takes any aspect, fitted into its box, says its own `width()` and `height()`, and
  reads a file with `load(path)`, as `Images.load` and `Sounds.load` do, whose pictures beside it
  in its folder are shown.
  `ImageView` shows an `Icon` as well as an `Image` (`new ImageView(icon)`, `setIcon`), drawn at
  the view's own size and density, so an illustration stays sharp. `Images.decode` reads an SVG,
  drawn at its own size.
- Animated SVG plays: SMIL's `animate`, `animateTransform` and `set`, chains of animations that
  begin at another's beginning or end among them, and CSS's `@keyframes` with the `animation`
  properties. An animated `SvgIcon` is an animated icon, at 24 frames a second unless
  `withFrameRate(n)` or `-Dlimn.svg.frameRate=n` says otherwise; `Images.decode` of one is an
  animated image. A transform CSS gives an element is drawn too. An `animation` declaration
  whose count is no number is dropped, as a browser drops it, and an animated icon the renderer
  refuses part-way through rests on its first frame.
- `ButtonGroup.remove(radio)` takes a radio out of its group.
- `Ui.postDelayed` returns a `Subscription` that withdraws the task: a timer armed for
  something that can go away first is cancelled there, where it held all it captured until it
  fell due.
- `Images.loadPinned` and `Images.fromResourcePinned`, with `unpin` and `unpinResource`, and
  the same four on `Sounds`: a shared load that is kept whatever the budget below, for what an
  application wants at hand for as long as it runs. Pins are counted: a load pinned twice stays
  pinned until it is unpinned twice, so one part of an application cannot take away another's.
  `setSharedBudget` and `sharedBudget` move and read that budget.
- `BackdropPanel.frosted(content)`: frosted glass that follows the theme. What is behind the
  panel is blurred and laid over with the palette's surface tone, light over a light palette and
  dark over a dark one, and it changes with the palette by itself. `setTint(colour, amount)`
  leans it toward a colour of the application's, and `setCover(share)` says how much of the tone
  is laid over what is behind; a value that is not a number is refused. A panel given its
  effects by hand is as before.
- `BackdropEffect.reach()`: how far outside its shape an effect reads what is behind it, in
  points (a blur's radius, a pane's thickness, a pixelation's cell, nothing for a wash or a tube).
  A widget of your own that paints from its backdrop says the same of itself with
  `Widget.backdropReach()`, and partial rendering repaints it with that margin.
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
  `Widget.onCanEdit` and `onEdit`. A text field's Cut, Copy and Paste chosen from a menu act on the
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
- Drag and drop between widgets. A widget answers `onDragStart` with a `Drag` (what it carries as a
  `Transfer`, the actions it allows, what to hear at the end); the widget under the pointer accepts
  in `onDragOver`, marks where the drop would land, and takes it in `onDrop`. The modifier keys
  choose between copy, move and link as the platform does, and Escape cancels. Whatever scrolls
  through a `ScrollBar` under the pointer (a scroll view, a list, a table, a tree, a text area, or a
  widget of your own built on a bar) scrolls toward the edge the pointer rests near, faster the
  nearer the edge, so a drop area out of sight comes into view: the innermost that can still move
  first, the next one out once the pointer is in its band too; past the window's edge a drag inside
  the window scrolls faster the farther past it. The band and the speed are the window's points
  under a `Transform`'s scale or tilt, a list, a table or a tree of rows of mixed heights glides
  instead of leaping, and a listener that throws during a step stops no later scroll. It runs under
  less motion too, being the user's scroll. The keyboard does the same: Ctrl+Shift+D (Cmd+Shift+D on
  macOS, `Scene.setDragKey`) picks up on the focused widget, Tab walks the widgets that accept, the
  arrow keys choose the place, Enter drops, and a screen reader hears each step in the 21 languages.
- A drag leaves its window, on Windows, macOS, X11 and Wayland: it is the system's own drag, to
  and from other programs, with the picture of what is dragged following the pointer over them,
  and what another program drags in is answered while it hovers. `Drag.withinWindow()` keeps
  one from being offered outside. `-Dlimn.drag.trace=true` prints each step of a drag handed to
  the system. A drop area that an auto-scroll brings under a pointer at rest is asked again with
  what the system now allows, and the system is told the new answer, sent only to the drag it was
  worked out for, so a release without a move drops there on X11 and Wayland too, a file from a
  file manager or a drag of this program's own (tried on KDE under Wayland and on Ubuntu under
  X11); `WindowPeer.dragAnswerChanged` tells a backend of your own of such an answer.
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
  in the list, in a chip and in the field of a combo holding one, and
  `setItemIcon(icon, item -> mirroring)` says of each item whether its icon means a direction, so
  a back arrow is drawn flipped where the combo reads right to left, as `Button`, `Label` and
  `Expander` say of their one icon.
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
- `NumberFormats.currency(currency, decimals)` writes money with other decimals than the
  currency's own, such as the whole amounts a chart's axis wants: `currency(usd, 0)` writes
  `US$ 815.800` in Brazilian Portuguese. It keeps the separators that go with the digits shown,
  which the platform's own currency format did not: under a bare Arabic it wrote `815٬800`.
- `Backend.requestQuit()` ends the application by asking once: the quit handler
  (`Backend.setQuitHandler`) first, then every open window's close handler, the window used last
  first and one at a time. A refusal anywhere keeps every window open; once all agree, every window
  closes and `runEventLoop()` returns `LoopEnd.QUIT`. A dialog or menu open as a window of its own
  is not asked and closes with its owner. The `QuitRequest` its handler is handed is sealed, made by
  Limn alone; a test asks the handler with `requestQuit()`.
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
  guide shows the few lines that do. It is a final class an application reads and compares, with
  no public constructor and no record pattern, so that a preference a system adds later can join
  it in a 1.x release; `SystemPreferences.NONE` is the value of a system that says nothing.
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
- `Widget.localToSceneBounds(x, y, width, height)`: a rectangle of a widget's space as its
  bounds in the scene, what a popup's anchor, a drag picture or a caret rectangle needs.
- `Change.Aspect` says that a minor release may add a constant: a `switch` that names every
  aspect keeps a `default`.
- Part of a window moves, scales or turns over in a `Transform` container (ADR 072), which wraps
  one widget as `Opacity` does and changes where it is drawn without moving anything layout placed:
  `setTranslation(x, y)`, whose x runs toward the end of the reading direction, `setScale(s)` and
  `setScale(sx, sy)` about an origin that `setOrigin(fractionX, fractionY)` measures from the start
  edge, the middle by default, and `reset()`. `slideTo` and `scaleTo` move there eased over the
  theme's `animWindow` or a given time, and `flipTo(Transform.Flip, atEdge)` turns the child over,
  drawn flat, running `atEdge` when it is edge-on, where the application swaps what it shows;
  `FORWARD` and `BACKWARD` follow the reading direction, `UP` and `DOWN` do not. The child is drawn,
  clicked and read by a screen reader where it is moved to, a reader told on a set and when a motion
  arrives, not on the frames between; a move or a scale is drawn straight into the window with
  every clip inside exact, a frame of it repaints where the child was and where it is and lays
  nothing out, and at rest the container costs nothing. A newer motion or a set stops one in flight,
  whose `onArrive` never runs; a container taken out of its window mid-motion arrives as it leaves;
  under less motion every motion goes straight to its target and runs its `onArrive` after the
  seconds it was asked for (the next turn for zero), and a flip swaps, its `atEdge`, on the next turn
  so a press answers at once; no callback ever runs inside the call, a swap already made stays made,
  and a container that leaves its scene runs a waiting `onArrive` then. A watcher
  hears `Change.Aspect.TRANSFORM` on every set that changes a value and twice for a motion.
  Measured on an Apple M5 Max, the first scale of a card full of text rasterizes its glyphs at each
  size it passes, about 0.25 ms of CPU a frame, and a repeat costs what the still card costs.
- `Accessibility.syntheticBoxAt` and `placeSyntheticAt`, for the publish step: a synthetic child of
  a widget under a `Transform` is published where it is drawn, scaled with its owner.
- The Showcase's Motion page has a two-sided card that turns over with `flipTo`, and the Animations
  guide a section on moving part of a window.
- A `Transform` rotates and skews its child: `setRotation(degrees)` turns it clockwise about its
  origin, the same way in either reading direction, as a clock does, and `rotateTo(degrees)` turns it
  there over the theme's `animWindow` or a given time, by the rules of `slideTo`; `setMatrix(Mat4)`
  draws it through any finite matrix, a skew made with the new `Mat4.affine2D(a, b, c, d, e, f)` or
  a perspective of its own. A turned child is drawn upright into a picture of its own and laid at the angle: every clip
  inside stays exact, a scroll view inside skips what scrolled away, the edges are smooth, text reads
  a little softer, and a frosted glass inside reads the window as it would upright. The picture costs
  what a fade of the same size costs while the child is turned; measured on an Apple M5 Max, nothing
  a GPU shows, and about 3 ms of a frame on the software renderer.
- `Canvas.saveLayer(x, y, width, height, opacity, Mat4)` opens a layer in a space of its own,
  composited through the transform under the clip in force; a canvas without layers applies the
  transform's flat part straight.
- A combo box's list, a date picker's calendar or a tab list open in a window of their own follow
  their anchor when a `Transform` above it is set or a motion of it arrives.
- A `Transform` turns its child in depth: `setRotation3D(axis, degrees)` or `setRotation3D(Quat)`
  turns its plane about an axis through the origin, x to the right, y down and z toward the reader,
  and `setPerspective(distance)` looks at it from that many points in front of the origin, so the
  near edge is drawn larger than the far one; with a perspective set, `flipTo` turns the child over in
  depth instead of flat. `setBackfaceVisible(false)` hides a child turned past a quarter, which is
  otherwise drawn mirrored; a mirror made by a negative scale still faces the reader. A turn of
  exactly a quarter draws nothing and takes no click, and what a turn brings past the eye is not
  drawn. A frosted glass inside a child seen in perspective shows its own tint and none of the window.
  Measured on an Apple M5 Max, the perspective's composite costs nothing a GPU shows and 2 to 3 ms of
  a frame on the software renderer. With a graphics processor the picture is drawn at its near
  edge's size; with none it is drawn at the child's own size, so the near edge reads a little softer
  while it is turned and a card turned 30 or 75 degrees costs some 3 ms more a frame, or less, than
  the card drawn straight, where the near edge's size cost 45 and 62 ms. A picture seen at less than
  half its size somewhere, a page laid far back, is laid through mipmaps, so its fine detail is
  averaged instead of flickering as it turns, and a change inside it repaints the whole picture: a
  few hundredths of a millisecond a frame on the GPU, about 9 ms, 3% of the frame, on the software
  renderer; a turn that does not shrink its picture past two to one costs nothing more.
- `Mat4.perspectiveAt(distance)`: a perspective seen from an eye in front of the plane z = 0, in the
  plane's own units.
- The Showcase's two-sided card turns over in depth, and the Animations guide shows a turn in depth.
- `DateFormats.date`, `time` and `dateTime`, beside `NumberFormats`: the language's date and time
  in the toolkit's digits, where the platform writes every date in ASCII digits.
- `I18n.numberLocale()`: the locale the toolkit writes numbers in, for `String.format` and any
  format of your own.
- `Column.of(title, value, format)` with a one-argument format, such as `DateFormats.date(FormatStyle.SHORT)`.
- `Scene.setBackground(null)` hands a scene back to the palette.
- `Field`, a field of a form around any control: a caption above it that names it to a screen
  reader, a line under it that shows the helper text or, while a state is set, the message in
  the state's colour with a mark before it, and `setRequired`, an asterisk after the caption and
  the required state on every system. The line keeps its height once shown, so a form does not
  move on the first error. A text field, a text area, a combo box, a spinner, a date field and a
  date picker draw the state on their border, and a date that refuses a day says why on the line
  by itself: while a control refuses its value, its reason shows, and is read, ahead of the helper
  text and of an `INFO` or `SUCCESS` message, which comes back once the value is accepted; an
  application's `ERROR` or `WARNING` message still wins, and one set with `NONE` is not shown and
  counts as none. A screen reader hears the caption as the control's name and the line as its
  description, on the part the keyboard lands on; required is `IsRequiredForForm` on Windows,
  `AXRequired` on macOS and `REQUIRED` on Linux, and an error is `IsDataValidForForm` false on
  Windows and `INVALID_ENTRY` on Linux.
- `Tone` and `Label.setTone`: a text colour said by what it means (`NEUTRAL`, `ACCENT`, `INFO`,
  `SUCCESS`, `WARNING`, `DANGER`) and asked of the palette on every paint, so a change of palette
  re-colours it. `Tone.colorIn(theme)` answers the colour for a widget of your own. `ACCENT` is
  `Theme.primaryText()`, so an accent label or badge reads at 4.5:1 in every built-in palette.
- `TextArea.validation()`.
- `Card` holds one record (a customer, an invoice, a report to open) and every part of it is optional:
  `setMedia` (a widget, or an `Icon` the card draws), a header of `setTitle` over `setSubtitle` with
  `setHeaderPrefix` before them and `setHeaderSuffix` after, `setContent`, and `setFooter(actions...)`
  at the trailing edge. A part not set, or hidden, takes no room and no gap. `setStyle` is
  `ELEVATED` (the default, the theme's raised shadow, with a hairline round it in a dark palette),
  `OUTLINED`, `FILLED` (the palette's surface, set off from the page by its tone alone) or `PLAIN`
  (nothing drawn of its own, the parts standing on the page); `setOrientation(HORIZONTAL)` puts the media in a column at the leading side
  with the footer across the whole card; `setMediaFit` is `NATURAL`, `STRETCH` or `COVER`, the last out
  to the card's edges with its own corners, and `setMediaAspectRatio` says how tall a stretched
  picture is. Given more height than it needs, a card gives it to its content, so the footers of a
  row of cards line up. Right to left, everything turns round.
- A card can be clicked: `onActivate(handler)` makes a click anywhere above its footer, and Enter or
  Space while it holds the keyboard, open what it stands for, while the footer's buttons are clicked
  as themselves and a press between them does nothing; a card with no footer is one click as a
  whole. It is one Tab stop, shows its hover and press over that part alone, draws a line above the
  footer and a focus ring that never crosses it, and pads the footer's actions alike on all four
  sides. Muted text under the hover keeps 4.5:1 in every built-in palette, in all four looks. A screen reader hears it as a button named by the
  title and described by the subtitle (in a group with the footer's actions when there is one);
  what else it shows is said with `setAccessibleDescription` on the card. A control placed in the
  part that is clicked is refused with `IllegalArgumentException`; one that arrives later is logged
  and answers nothing. Actions go in the footer.
- `Card.setTitleLines(int)` and `Card.setSubtitleLines(int)` (1 by default) let a card's title and
  subtitle break across more lines, the last ending with an ellipsis, so a long name reads whole on
  a narrow card; a screen reader hears the whole title either way.
- `Label.setMaxLines(int)` (1 by default) breaks a label's text across up to that many lines, at the
  opportunities wrapping uses in the UI language, and ends the last with a measured ellipsis when
  more text remains (or lets it run to the edge under `Overflow.CLIP`). Above 1 it holds whether or
  not `setWrap` is on; at 1, `setWrap(true)` still means as many lines as the text needs, so no label
  changes unless you ask. The lines are counted, not fitted: a label given less height clips.
- `SizeTokens.cardPad`, the card's padding at each step: 10, 12, 16, 20, 24.
- `Widget.isSelfEnabled()` reads the flag `setEnabled` sets, whatever the ancestors say, beside
  `isEnabled()`, the state of the whole ancestry: `w.setEnabled(!w.isSelfEnabled())` toggles a
  control, inside a disabled form too.
- `Wrap.gapsSilently(gap, runGap)`, for a `Wrap` subclass that sets its gutters from its own measure,
  as `Flex` already allowed for its gap.
- `Badge`, a short status beside or inside something else (ADR 075): a pill tinted in a `Tone`
  with a dot or an icon in the tone before the word, the word in the palette's text colour so it
  reads at every tone; `Badge.ofCount(n)` shows a number in the language's digits and "99+" past
  99, and `Badge.dot(name)` is a dot alone with a name a reader hears in its place. A reader
  hears static text; a badge in a table's or a tree's row is read in the row's name.
- `Avatar` and `AvatarGroup`: a person as a circle of their picture or their initials (the first
  grapheme of the first and the last word, upper-cased in the avatar's locale) over a chart-palette
  colour of their own, the same for the same name everywhere; a silhouette with neither. A group
  overlaps them by a quarter and ends in "+k" past `setMaxShown` (four by default), drawn at the
  size of the group's initials and centred on its capitals as they are, the hidden names in its
  tooltip. An avatar is an image named by the person; a group is one group, described by every
  name, the hidden ones included. A member's initials are read in its own language, and the
  group's one size, its description and its tooltip follow a window's or a subtree's language.
- `ListView`, `Table` and `Tree` show an empty content of yours whenever they hold no rows:
  `setEmptyContent(content[, message])` or `setEmptyMessage("…")`, centred in the rows' viewport
  (a table keeps its header and footer), its buttons after the widget in the Tab order, the
  message describing the widget to a reader. `setLoading(true)` shows a loading content in the
  same place (a moving bar over "Loading…", or yours with `setLoadingContent`), marks the widget
  busy and tells a reader "Loading Orders", and `setLoading(false)` tells how many rows arrived.
- `Popover`, a panel of controls hanging from a widget: `showFor(anchor)` or `attachTo(button)`,
  below the anchor unless above has more room, lined up with its leading or trailing edge. It takes
  the keyboard (its first focusable widget, Tab cycling inside, the focus back on close), closes
  on a press outside, Escape, a lost keyboard or `close()`, heard by `onClose` with the origin, and
  is not modal. In a window of its own the owner keeps the desktop's keyboard and its keys,
  characters and input-method composition are delivered into the popover, so a field in it takes
  a Japanese or a Chinese composition as any field does. An attached button says it opens a popup
  and whether it is open.
- `Button.Style` (`PRIMARY`, `SECONDARY`, `TERTIARY`) with `setStyle` and `style()`, and
  `setDestructive(true)` for a button that deletes or discards, drawn in the palette's danger
  colour at any style. A tertiary button has no fill or border until the pointer is over it, and
  its caption is `Theme.primaryText()`. The box is the same at every style.
- `Dialog.addDestructiveButton`, a secondary button in the danger colour that is never the
  dialog's default: Return gives the safe answer.
- `Dialog.onCancel(handler)` asks before the user cancels a dialog, so a form with unsaved changes
  can ask "Save changes?" over itself. The handler is handed a `Dialog.CancelRequest` for Escape, a
  click on the scrim of a dialog that dismisses there, a button whose result is the cancel result,
  the system closing the dialog's own window and a screen reader's dismiss, `reason()` saying which,
  and answers it once with `allow()` or `refuse()`, now or from another dialog's result; holding it
  asks nothing new, a second answer throws, and a handler that throws has refused. Any other answer,
  a Save, is `request.dialog().dismiss(result)`, which settles the request. `dismiss` from code, the
  default button, any other button and the application ending never ask. The request is sealed,
  made by Limn alone; a test presses the key or the button. A dialog answers Escape and Return on
  the key's press alone and takes their repeats, so a key held down asks once.
- `Theme.primaryText()`, the accent as text: `primary` where it reads at 4.5:1 on the canvas, a
  card and a popover, and otherwise moved toward the text colour only until it does. Derived, not a
  key of the theme file.
- The systems Limn runs on are written down and checked. The install guide's "Supported systems"
  table gives the oldest per platform: Windows 10 on x64, Windows 10 on ARM or Windows 11 on
  ARM64, macOS 11 Big Sur on Apple silicon and Intel, and Linux with glibc 2.28 on x86_64 (Debian
  10, Ubuntu 20.04, RHEL 8) or 2.35 on ARM64 (Ubuntu 22.04, Debian 12), under X11 or Wayland;
  each README carries the short form. The numbers are what the shipped native libraries record
  about themselves, and the build now reads every one of them, for all six platforms, so a
  dependency that would raise a floor fails the build instead of dropping a system. The guide
  also names the Windows features that need a later build of Windows 10 (dark system menus from
  1903, headings with their level from 1809, a tray icon sized per monitor from 1607) and what
  each falls back to.
- `Scene.setRoot(widget)` swaps a scene's root, bound to a window or not, and is the way to switch
  pages in a window. The old root leaves as a removed widget does (told `onDetached`, its popups
  closed, the keyboard, the hover, a press and a drag the scene drives taken out of it, screen
  readers told the content changed) and is then free for any container, push or scene; the new one
  is refused as a new scene's root is, before anything moves. When the keyboard was in the old page,
  in a popup of it too, it goes to the new page's first stop, unless the new page asked for one of
  its widgets as it joined. A dialog shown on the scene, or a menu shown at a point of it, moves to
  the new root; one the application hung from a widget stays with that widget. A drag already
  handed to the system goes on, its drop going to the new root. A `setRoot` asked from inside the
  change waits for it and is then made, the last one winning (a fade's or a motion's `onArrive`
  that ends as its page leaves included), and a chain that has not settled after the root given
  and 64 more stops with an `IllegalStateException` that names it, carrying what the pages'
  handlers threw on the way.
- `ScrollBar.Model.scrollBy(delta)`, by default `setOffset(offset() + delta)`: a model whose offset
  is an estimate, as a list's over rows of different heights is, moves by what is on screen instead.
  A drag's auto-scroll moves a bar this way.
- The Showcase's 3D page has a product configurator: a desk lamp built in code from its parts,
  turned with the pointer under a studio sky, whose colour swatches, finish and light switch blend
  its material over a third of a second (a cut under less motion); a click on a part opens its name
  and price in a popover at the click, and a price list and a total follow the finish and say to a
  screen reader what a click says. A shader scene computes molten rock, a zoom into the Mandelbrot
  set and a glowing cloud on the GPU per pixel every frame, in an application's surface written in
  the portable shader IR, held still while the system asks for less motion; on a machine without a
  GPU it is too slow to use. The environment's reflections and the glTF model are back in the page's
  scene picker, and its render scale applies to every scene, the shadows included.
- A shader surface iterates with `Expr.Loop` in `limn.render3d.shader`. `Expr.Loop.of(initial,
  maxIterations, next)`, and the same with an `until` asked before each turn, hands `next` the state
  as a node of the initial value's type and takes the next state back; `Expr.Loop.of(List,
  maxIterations, next[, until])` carries several values, read with `loop.value(i)`, so a ray can
  gather a colour and the distance it went. Every turn a pixel takes across a whole surface is held
  to `Expr.Loop.MAX_ITERATIONS`, 1024, counted as the generated shader runs it (loops side by side
  or chained add, a loop in another's turn that reads its state multiplies, the diffuse response
  counts once for each of eight lights), so no surface can hang the GPU. A loop that can end early
  may not sample a texture at an implicit level of detail or take a derivative, which the shading
  languages leave undefined there; a state is read only inside its loop, under the name the factory
  gives it, and a turn may not read the state of a loop already built. It compiles to both GLSL
  profiles the toolkit targets. There is no `let` and no helper function: a Java variable holding a
  node is computed once, and a Java method that builds nodes is already a function.

### Changed

- On a masked `TextField`, an edit that would move a character into a position that does not take
  it is refused and does nothing, as typing into a full field does; before, the characters after it
  closed up and those landing on the wrong kind were dropped (`ABC-1234-XY`, Backspace among the
  digits, gave `ABC-124`). Typing or pasting over part of the value overwrites the selected
  positions one by one and moves nothing; a key goes to the first selected position; a paste drops
  its excess only where a value character follows, and at the end of the value fills the free
  positions too; a selection of the whole value is replaced as in any field. A refused key keeps
  the selection, in the direction it was made.
- A masked `TextField` lays its text, its guide and its placeholder out left to right in every
  language, from the left edge of its box, with its groups (the stretches between literals) in
  written order whatever the digits' script; inside a group each script keeps its own direction,
  and its icons and trailing button still follow the layout direction. Under a right-to-left layout
  a card read `1112 1111 1111 4111`.
- A child a `Transform` draws as nothing (a back face turned away with `setBackfaceVisible(false)`,
  a scale that is 0 on either axis, a plane exactly edge-on) is out of reach as a hidden child is:
  Tab, access letters and the pointer pass it by, the focus, the hover and a held press are taken
  out of it, screen readers are told it is hidden, and it comes back by itself. A scale that arrives
  at 0 takes the focus; turn a card over with `flipTo` to keep it. A set, a motion's frame or a
  layout pass that turns a plane edge-on closes its popups and takes its focus once it is over; for
  a layout pass, when the next frame renders, which `Scene.layoutPass` says for a caller that lays
  out alone.
- A widget drawing in `BlendMode.MULTIPLY` or `ADDITIVE` inside an `Opacity` container below 1, or
  inside any `Canvas.saveLayer`, a turned picture of a `Transform` included, blends with what the
  group itself drew, and is drawn as itself where the group drew nothing, as an isolated group of a
  web page is (`ADDITIVE` adds coverage as well as light). Before, `MULTIPLY` drew nothing below 1.
  At 1 there is no group and the mode blends with the window, so a fade's last frame jumps, as a web
  page's does. `MULTIPLY` there is drawn in two passes: draws that cannot cover a pixel in common
  share them (200 heat-map cells a pixel apart take 9 draw calls), and each draw that can takes two
  of its own. Shapes less than a device pixel apart, touching bars or tiles, each take their own,
  since beside a corner both can cover one pixel: ten touching bars take 21 draw calls a frame while
  their group fades.
- A validation message reads in the language of the control it describes: a `Field` draws its
  message, or the control's own reason, in the control's language, laid out in place as a new
  message is, and `Field.validationMessage()` returns that text: the application's `ERROR` or
  `WARNING` message, else the control's own reason while it refuses its value (a `DateField`
  refusing a day), else an `INFO` or `SUCCESS` message; one set with `NONE` counts as none.
  `label()`, `helperText()` and `validationMessage()` return the text as it reads, and
  `labelSource()`, `helperTextSource()` and `validationMessageSource()` the `I18nString`.
- Any of Limn's enums may gain constants in a 1.x minor. The promise in the README and the install
  guide and the Javadoc of every public enum say so: a `switch` over one keeps a `default` branch.
- `Change.Aspect.ENABLED` is announced on every descendant whose `isEnabled()` changed when a
  container is disabled or enabled: the container first, then the descendants in tree order. One
  disabled by its own flag, or under another disabled container, hears nothing, since its state did
  not move. A watcher that should react to the control's own flag alone compares `isSelfEnabled()`
  with what it last saw.
- Every DRAG and RELEASE sent to the widget that holds the pointer carries a point of that widget's
  own plane, except the release of a widget of which nothing can be seen (its plane exactly edge-on,
  or its whole box behind the eye), and `MouseEvent` says so.
- A `Dialog`'s first focus is its default button, even when a button such as Cancel was added
  before it, so Return answers what the filled button says; a field in its content still takes the
  first focus.
- An in-scene `Dialog` or popup closed while the system asks for less motion, or in a scene with no
  window, is removed and completes its result on the next turn of the UI thread, not inside the call
  that closed it. An `onArrive` that throws is logged by the UI queue on its own turn instead of
  being thrown out of `fadeTo`.
- Typing the literal under the caret in the middle of a masked `TextField` steps over it, as it does
  at the end.
- In limn-test, `HeadlessWindow.frame()` drains the UI queue, then draws that window (several
  windows drain once each, where a desktop drains once a turn), and `AccessibleHarness.frame()` and
  each step of `advanceTime` do the same. An `AccessibleHarness` makes its scene clock the clock the
  runtime's timers fall due on, so `advanceTime` runs a `Ui.postDelayed` timer, or a fade's waiting
  `onArrive`, as its time passes, with motion and under less motion alike, and
  `HeadlessBackend.windows()` can be iterated while a frame opens or closes a window. A test whose
  posted tasks or timers must not run until it says so sees them run on the next frame or
  `advanceTime`.
- In limn-test, `HeadlessBackend.windows()` lists the windows that have not closed: a window leaves
  it, and the order the backend decides an ending by, as it closes, so the fixture no longer keeps a
  closed popup's scene and its page alive (a leak check over native popups found pages only the
  fixture held). A test that needs a window after it closed keeps its own reference, which still
  answers `isClosed()`.
- The in-scene fades of a `ComboBox`'s list, a `DatePicker`'s calendar, a `TabbedPane`'s list of
  every tab, a `PopupMenu`'s cascade, an in-scene `Dialog`'s card, the hover tooltip and a chart's
  tooltip panel draw their surface as one picture: halfway through, a highlight no longer shows
  through a row's icon, a shadow no longer darkens its panel, and a card's border no longer shows
  through its buttons. The dialog's veil is still one fill whose alpha fades. Each step of such a
  fade repaints the surface and its shadow, where the list's, the menu's and the dialog's
  repainted the window. A popup in a window of its own fades through the window's opacity, as
  before.
- `Work.Counting`, in the toolkit's test fixtures, counts a `saveLayer` as one drawing command:
  its picture's composite.
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
- The list of every tab, opened from the strip's list button when the tabs do not fit, is a list
  of its own and no longer a menu (ADR 068): a row per tab in the strip's order, the shown tab
  marked, each tab's icon in a column of its own when any tab has one, and on a closable tab a
  close button at the row's end under the pointer or the cursor. Up, Down, Home and End move the
  cursor, Enter or Space shows the tab, a typed letter goes to the next tab whose caption starts
  with it, Delete or Ctrl+W (Cmd+W on macOS) closes the tab under the cursor through
  `onTabClose` and the list stays open without it, Escape or Tab closes the list. Alt+Down on the
  focused tab opens it while the strip overflows; the list button stays out of the tab order, so
  the strip is still one stop. A tab chosen from it takes the keyboard when the keyboard was in
  the pane, and closing the focused tab from it leaves the keyboard on the tab now shown. It
  closes when the tabs fit again and when the pane leaves its window, takes in a tab
  added or moved while it is open, and chooses and closes nothing while the pane is disabled;
  its closing is announced with the origin of what closed it. A screen reader hears a list named
  "All tabs" whose selected row follows the cursor, the tab shown described as "Current tab", and
  a closable row's button as "Close tab".
- A combo box's list and a date picker's calendar open, place, fade and close alike (ADR 068).
  Right to left, a calendar in a window of its own lines up with the picker's leading edge, the
  right one, as its in-scene layer already did, where it hung from the left edge; a combo box's
  list drawn in the scene gives the keyboard back as its fade starts, as the calendar's did; and
  a list or a calendar drawn in the scene is placed by the window's rule: below unless it does
  not fit and above has more room, eight points from the scene's edge.
- A popup window, a combo box's list, a date picker's calendar, the list of every tab or a menu,
  opens faster: it draws with the GL entry points its window already resolved, where every new
  window looked them all up again. On a Mac that lookup was about 20 ms, and a popup's window
  went from about 25 ms to 5 ms, opened and closed one at a time as a list or a menu is. This is
  the default on macOS, Linux and Windows. Two contexts share the entry points only when they
  report the same vendor, renderer, version, context flags, profile and extensions, and
  `-Dlimn.backend.reuseGlCapabilities=false` turns the sharing off for a driver that misbehaves.
- The `ColorPicker` shows its swatch and hex field first, above the saturation and value field,
  so the colour before and after stays in sight while a drag or a channel changes it; a short
  window scrolled them out of sight. A reader meets them first too.
- A tab that holds the keyboard is marked by a rule along its top in the focus colour, the
  selection's rule mirrored, where a ring around the whole tab crossed the selection's rule and
  read as a text field.
- On Linux the bridge sends the name, description, value and open state of the node the focus
  comes to in that node's cache item, with the focus, rather than as events, holds a publish's
  signals until its frame has ended, however many there are, and no longer repeats the focus as
  the cursor the list had: Orca 50.3 handled a batch's focus before its changes and read a combo
  box again for each one.
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
  request.refuse(); }`. The handler is also asked when the application ends. `CloseRequest` is
  sealed, made by Limn alone; a test asks the handler with `HeadlessWindow.userClose()`.
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
  gone, and every scene of the lab is still opened alone by `--scene`, in the language asked for.
  The sample film the demo plays has its soundtrack. What a control did is said in the status
  line and to a screen reader, politely; menus, buttons and the tray menu carry access letters
  in every language; and a language chosen while it runs reaches every line of a page already
  built.
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
- The guides and the demo name `limn-icons-tabler` 3.48.0.1: 36 more icons than 3.46, none removed
  or moved, so code that names a constant compiles unchanged; and the jar names itself as the
  module `limn.icons.tabler`, so an application on the module path can require it, which with
  3.48.0.0 it could not (the jar stayed on the class path).
- A name, a description or a tooltip written on a composite whose caption already went to a part
  inside it (a `DatePicker`'s field, an editable `ComboBox`'s text, a clicked `Card`'s button) now
  goes to that part too, the node a reader reaches; what the part was given itself still wins.
- A `ListView`'s, a `Table`'s or a `Tree`'s `refresh()` and a new set of rows repaint the widget
  and not the window, and so does a child joining or leaving any widget that clips its children.
- Every single-line control centres its text on its capitals instead of its line box: in a field, a
  combo box, a button, a date field, a spinner, a tab, a menu row and the rest the capitals stood
  0.1 to 0.6 pt above the centre on average, and up to a device pixel at the worst position on the
  pixel grid. `TextRuler.capHeight(font)` and `TextRuler.centredBaseline(font, height)` are what
  they paint with and report as their baseline, for a widget of your own to centre a line the same
  way. A `Label` and a `TextArea` keep their lines at the top.
- An icon beside its text stands one gap from it everywhere, `SizeTokens.gapIcon`, now 4, 5, 6, 8 or
  10 pt by size where it was 5, 6, 8, 9 or 11: in a button, a field, a combo box, its chips and list
  rows, a menu row and a label alike, where a button's icon stood some 2 pt further from its caption
  than a combo's. A button's icon beside a caption stands from the button's edge as a
  field's leading icon does, so icons line up down a form, stretched buttons included, and the
  caption is centred in what is left; such a button asks for 8 pt less width at the default size. A
  caption alone and an icon alone stay centred, and a `ColorPickerButton`'s chip beside a caption
  follows the same rule.
- A period `DatePicker` (`DatePicker.ofRange()`) packs its two dates and the dash at the start of
  the field, in the direction it reads, the room left over after the second date; the two dates
  split the box in halves whatever they took, the dash in the middle and the second date at the far
  end. A click in the room left over lands in the second date, and a box too narrow for the packed
  line still splits in halves.
- A `Dialog` in a window of its own refuses the system's close of that window and takes it as
  Escape: it asks `onCancel`, and closes by fading out as its other answers do, its result
  completing after the fade. It vanished at once.
- The veil that dims a window a modal locks, and the scene under an in-scene dialog, keeps clear
  pixels clear and leaves opaque ones as painting over did, and dims a partly covered pixel by
  darkening it and covering more of what is behind: a translucent locked window dims the desktop
  seen through it again, glass at 0.6 showing a tenth of the desktop where painting over showed a
  fifth, and a pixel two thirds covered or more becomes opaque under a half veil. On a canvas
  without that blend, a locked window's veil is multiplied in and a dialog's painted over.
- A text field or a text area that a closing overlay gives the keyboard back to (a dialog's
  question, a popover, a menu drawn in the scene) has its selection back, the caret at the same end,
  when nothing set its caret, its selection or its text meanwhile, as it does when the overlay is a
  window of its own; an item that selected all or moved the caret keeps what it did. Tabbing in
  still selects all, and a click places the caret. An input method's composition is not kept.
- `SegmentedControl.onSelect` is documented as what it always was, the user's choice:
  `setSelectedIndex` reaches the control's watchers and not that handler.
- The GLSL generator writes a node used more than once once, into a local, so a shader grows with
  its IR and not with every path through it: a surface of four octaves of noise draws a frame in 1.3
  s where it took 5.4 s on Apple's software renderer, the GPU unchanged, and the built-in material
  samples its base-colour texture once. A loop asks its exit before the work only its next state
  needs.
- The shader IR is a value, as its Javadoc said: `Expr.Lit.of(1).equals(Expr.Lit.of(1))`, a literal
  copies its values in and out, and every node equals its twin built apart. Equality, hashing, a
  node's type and `toString` visit a node shared by many parents once, `toString` writing it `#n=`
  the first time and `#n` after, where a chain that reuses its last step took time exponential in
  its length and forty squarings never finished.

### Breaking, and how to move

- `SizeTokens` is a final class with no public constructor instead of a record, so a token a widget
  needs can join it in a 1.x minor. Every reading method (`tokens.body()`, `SizeTokens::fieldIcon`)
  and `equals`, `hashCode` and `toString` work as before, but nothing outside builds or deconstructs
  one, and `SizeTokens.TABLE` is gone: use `SizeTokens.of(step)`, `SizeTokens.MEDIUM` or
  `Theme.tokensFor(widget)`.
- `Scene.fadeWindow`, `fadeWindowIn` and `fadeWindowOut` no longer run their callback inside the call
  when the fade goes straight there: it runs after the seconds asked for when no window is bound or
  the window already has that opacity, and on the next turn of the UI thread for zero seconds. A
  newer call before then drops it, and a window that closes first runs it as it closes. Code that
  read a result right after the call reads it on a later turn; in a test, frame the window, or move
  the clock given to `new HeadlessUi(clock)` past the seconds.
- `Canvas.setOpacity` multiplies the opacity in force instead of replacing it, so an opacity set
  inside a fading surface or a layer combines with it. Code that set it once on a fresh `save()`
  draws as before. Code that multiplied by hand, `setOpacity(canvas.opacity() * fade)`, drops the
  product, `setOpacity(fade)`, or its fade is squared. Code that meant to replace an opacity in
  force divides by it.
- `Change.Aspect` has `OPACITY` and `TRANSFORM`. A `switch` over the aspects that names every one,
  with no `default`, needs a case for each to compile; any of Limn's enums may gain constants in a
  1.x minor, so give such a switch a `default`.
- `DateField.validationMessage()` returns the message as it reads (`String`, in the field's own
  language) instead of the `I18nString`, as `Field.validationMessage()` does; `validationMessageSource()`
  returns the `I18nString`. Code that called `.get()` on it drops the `.get()`; code that read the key
  calls `validationMessageSource()`.
- An `&` before a letter or a digit in the text of a menu title or item, a `Button`, a
  `Checkbox`, a `RadioButton` or a `Label` bound with `setLabelFor` marks its access letter and
  is not shown (ADR 070): "R&D" shows "RD" with the D underlined, and `text()` answers "RD".
  Write a literal one `&&` ("R&&D"). An `&` before a space or at the end is text, as before.
- `Ui.postDelayed` and `UiRuntime.postDelayed` return a `Subscription` where they returned
  nothing: source code compiles as it did, but a library compiled against 0.9.0 that calls them
  fails with `NoSuchMethodError` until it is compiled again.
- `Slider.setValue` throws `IllegalArgumentException` for a value that is not a number, where it
  stored NaN past a clamp that cannot clamp it; a value past either end is still clamped. Code
  that could pass NaN checks first.
- `ActionFacet` has a third component, `accessKey`. The two-component constructor stays, so
  `new ActionFacet(actions, keyBinding)` compiles as before; a record pattern over it names three.
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
- `Widget.sceneToLocalX(float)`, `sceneToLocalY(float)`, `localToSceneX()` and `localToSceneY()`
  are gone: a widget converts whole points, since a local x can depend on the scene's x and y
  both. `sceneToLocal(x, y)` and `localToScene(x, y)` answer a `limn.math.Vec2`, and
  `localToSceneBounds(x, y, width, height)` a `limn.graphics.Rect`. `w.sceneToLocalX(e.x())` and
  `w.sceneToLocalY(e.y())` become one `Vec2 p = w.sceneToLocal(e.x(), e.y())` read as `p.x()` and
  `p.y()`; `w.localToSceneX() + a` and `w.localToSceneY() + b` become `w.localToScene(a, b)`; a
  widget's box in the scene is `w.localToSceneBounds(0, 0, w.width(), w.height())`. Where no point
  of the widget is drawn the answer is NaN, which the usual `p.x() >= 0 && p.x() < w.width()` test
  already counts as outside.
- `Widget.hitTest` is final, and checks for every widget that it is visible, enabled and in reach
  and that the point is inside it. A class that overrode it renames the override `onHitTest`,
  which is protected, deletes those checks, and asks a child with `hitTestChild(child, x, y)`
  where it wrote `child.hitTest(x - child.x(), y - child.y())`; `hitTestChild` refuses a widget
  that is not a child. A call of `hitTest` compiles and answers as before, but for the three
  fixes under "Fixed" (a point that is not a number, an inert widget, a point a parent clips away).
- `Widget.isEnabled()` answers for the widget and every ancestor, as `isShowing()` does for
  visibility; `setEnabled` still sets the widget's own flag. Code that read `isEnabled()` to save
  and restore a flag reads `isSelfEnabled()` instead; disable a form's container rather than
  each field.
- `Theme.apply(Scene...)` is `Theme.apply()`: every window repaints by itself. Drop the arguments.
  `Theme.setCurrent` repaints every window too, and still leaves the font alone.
- A `Scene` no longer starts at a fixed dark colour: drop `scene.setBackground(Theme.current().background())`.
  A colour you set yourself is kept across a switch.
- `Theme.Builder` and `ThemeFormat` drop the alpha of every tone but `scrim`. Put a translucent
  colour in your own painting instead of in a tone.
- `Column.of`'s two-argument formatter is handed `I18n.numberLocale()` instead of the table's bare
  locale. The language is the same; only the digits, and in a few cases the separators, change.
  A date formatter there switches to `Column.of(title, value, DateFormats.date(style))`.
- `TextField.Validation` is `limn.components.Validation`, with the same five constants: replace
  `TextField.Validation` with `Validation` and import `limn.components.Validation`.
- A caption above a control and a message bound under it are one `Field` now:
  `new Field("Email", email)`, then `field.setValidation(Validation.ERROR, "Enter an address")`
  where you set the text field's state and the message label's text, and `field.clearValidation()`
  where you emptied them. A message coloured with `setColor(theme.danger())` is `setTone(Tone.DANGER)`.
- `Button.setSecondary(boolean)` is gone: `setSecondary(true)` is `setStyle(Button.Style.SECONDARY)`,
  and `setSecondary(false)` is the default, `Button.Style.PRIMARY`.
- A `Button` holding an icon and no caption is square, as wide as its control height (24, 28, 32,
  40 or 50 pt by size), its icon centred; it was the caption's padding on both sides of the icon, 58
  by 32 at the default size. A `ColorPickerButton` with an empty caption is square the same way, its
  chip inset by half of what the shorter side of its box leaves. Captioned buttons keep their width.
  To keep the old width, give the button `setMinWidth` of 38, 48, 58, 72 or 88 by size;
  `setMaxWidth` makes one narrower.
- A widget that is a scene's root, or an overlay on a scene's stack, is refused with an
  `IllegalArgumentException` by every container's `add`, by `Scene.pushOverlay` and `pushPopup` and
  by `new Scene`, whether the scene is another or the one it is pushed on, before anything moves; so
  is a widget that has a parent, by the pushes and `new Scene` as by `add`. Taken, it stayed named
  as its first scene's root or on its stack, laid out at that window's size; pushed twice, the first
  `removeOverlay` walked it out of the scene while it stayed on top; pushed with a parent, it was
  laid out and drawn by both. Take it off first: `removeOverlay`, `Scene.setRoot` with another root
  (or its window closing), or `remove` from its parent.
- A container's `add` of a widget that already has a parent throws `IllegalArgumentException`, as
  the pushes and `new Scene` do, where it threw `IllegalStateException`. Code that caught the old
  type for this catches the new one.
- `Scene.bind` of a scene whose window closed throws `IllegalStateException` when its root was taken
  since (by a container, a push or another scene), and leaves off its stack an overlay that another
  scene or a container took; it walked them all back in, so a widget was in two places at once. Give
  such a scene a new root with `setRoot` before binding it again.
- `Expr` permits a new node, `Expr.Loop`: a `switch` over the shader IR that names every node, with
  no `default`, needs a case for it. A `Material.Surface` input whose name starts with `ir_` is
  refused with an `IllegalArgumentException`, since the generator names its own variables so, and so
  is a surface reading a reference so named that no loop binds; rename the input.

### Known, and left for later

- Of a drag between programs. Under Wayland there is no link, the protocol having copy and
  move only; with no key held it is the target's preference that decides between those two,
  not the source's; and at a fractional scale the picture that follows the pointer is rounded
  to a whole one. On X11 a program built on GTK 4 talks to the window itself from its second
  message on and goes round the channel this toolkit answers drags on: its files are followed
  by the pointer, asked for as they hover, offered as files alone and as a copy alone, and
  taken at the drop, but anything else it drags, text for one, does not arrive. Programs built
  on GTK 3 and the other toolkits tried are answered in full.

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
- Of a masked `TextField`. Right to left, the guide of the positions still to fill is drawn on
  the wrong side of the text, since the two are drawn as two runs and not shaped as one line.

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

- Video plays on FFmpeg 9.0.2, up from 7.1.5: `limn-ffmpeg-natives` 9.0.2.1. An application that
  names a `natives-<os>-<arch>` classifier itself changes its version to `9.0.2.1`;
  `limn-video-ffmpeg-natives-all` moves on its own. Nothing else changes, and nothing in the
  shim did: the same codecs and containers, no GPL component (FFmpeg 8.0 removed libpostproc),
  the same tests passing. On Linux the payload is about 0.5 MB bigger in the jar and software
  decoding about 4.5% faster, because FFmpeg now lets GCC vectorize; macOS and Windows grew by
  a few percent at most. On macOS it plays from macOS 11 Big Sur: every payload before 9.0.2.1,
  the 7.1.5 that 0.9.0 named among them, was built for macOS 26 Tahoe by mistake, and an older
  Mac could not load it.
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
