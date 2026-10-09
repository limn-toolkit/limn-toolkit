# ADR 054: A drag carries a transfer between widgets, windows and applications, and the keyboard can do it too

- **Status:** Accepted, 2026-10-08, with the owner's answers below. Steps 3 and 4 of §6 are
  built: the scene's driver with the keyboard's drag, and the table's columns; §7 says where
  they depart from the text. Steps 5 and 6 are built: the lists, the tree and the picture, and
  the system's driver on macOS, Windows, X11 and Wayland, each measured on its system. Step 7 is
  not.
- **Date:** 2026-10-08
- **Scope:** drag and drop for every widget: a source, a target with feedback while the pointer is
  over it, the platform's own drag so that it leaves the window; a keyboard way of doing the same;
  `ListView`, `Tree` and `ImageView` draggable as they come; `Table` phase 2 (columns reordered by
  drag and by key, leading columns frozen).
- **Compatibility:** breaking for `Widget.onFileDrop` and `FileDropEvent`, which become
  `onDrop` with `DataFormat.FILES`; breaking for the incubating `WindowInput.filesDropped`. Free
  before 1.0. The rest is additive.
- **Builds on:** [ADR 053](053-what-is-copied-or-dragged-is-a-transfer-and-the-clipboard-carries-more-than-text.md)
  (`Transfer`, `Offer`). Closes the phase 2 of
  [ADR 041](041-a-table-is-columns-over-a-list-the-application-owns.md) §10.

---

## 0. What existed

Each widget handled its own `DRAG` (a slider, a divider, a scroll bar) and nothing carried data
from one widget to another. Files dragged from the system arrived as one `FileDropEvent` at the
moment of the drop: GLFW tells nothing while they hover, so no widget could show where they would
land. Nothing could be dragged out of the window.

## 1. The owner's decisions (2026-10-08)

1. **Reach:** between widgets, between the application's windows, **and to and from other
   applications**. (Recommended: the window only. He was told it roughly doubles the theme.)
2. **Keyboard:** **both** a drag mode of the toolkit that works for any drag, and direct
   shortcuts on the widgets that come ready.
3. **Draggable as they come**, besides the table's columns: **`ListView` rows, `Tree` nodes, and
   `ImageView` when dragging is enabled on it**. Not tabs, not table rows.

Stated and not vetoed: frozen columns are set by the application; a column is dragged only within
its zone (frozen or not); the application reads and writes the column order and hears it change.

## 2. The shape

```java
// limn.scene.Widget
protected @Nullable Drag onDragStart(DragStartEvent event) { return null; }
protected void onDragOver(DragEvent event) { }      // event.accept(DropAction) or nothing
protected void onDragExit(DragEvent event) { }
protected void onDrop(DropEvent event) { }          // event.offer(), event.done(DropAction)

// limn.transfer
public enum DropAction { COPY, MOVE, LINK }

public final class Drag {
    public static Drag of(Transfer content);
    public Drag allow(DropAction first, DropAction... more);   // default COPY
    public Drag image(Image image, float hotX, float hotY);    // default: the widget as painted
    public Drag withinWindow();                                // never handed to the system
    public Drag onEnd(Consumer<@Nullable DropAction> result);  // null: cancelled or refused
}
```

- **The source answers a question.** A press that moves four points on a widget asks
  `onDragStart`, bubbling from the widget under the pointer, exactly once. `null` (the default)
  leaves the gesture to the `DRAG` events it has always been. A `Drag` starts one.
- **The target accepts or says nothing.** `onDragOver` arrives at the widget under the pointer
  and bubbles; it carries the point, the modifiers, the `Offer` (formats only, until the drop),
  the actions the source allows, and the source widget when the drag began in this process. A
  widget that calls `accept` is the target: it draws its own feedback (an insertion line, a
  highlighted row) and gets `onDragExit` when it stops being one. With nobody accepting, the
  pointer shows that nothing can be dropped.
- **The action follows the platform's modifiers** (Ctrl copies and Shift moves on Windows and
  Linux, Option copies on macOS), within what the source allows and the target accepted.
- **The drop reads, then reports.** `onDrop` reads from `event.offer()` (asynchronously when the
  source is another program) and calls `event.done(action)`; the source's `onEnd` then learns
  what happened, and removes the item when it was moved.
- **Escape cancels**, as does the platform's own cancel. A scroll view under the pointer scrolls
  when the pointer rests near its edge, so a long list can be dragged across.
- **`onFileDrop` is gone.** Files from the file manager are an `onDrop` whose offer has `FILES`,
  now with `onDragOver` before it.

### Two drivers behind one model

- **The scene drives** a drag marked `withinWindow()`, any drag under a backend that has no native
  drag (the headless one, so every behaviour here is tested without a desktop), and the keyboard
  mode (§3). The image is painted by the scene over its content.
- **The system drives** every other drag: `WindowPeer.startDrag` hands it the transfer, the
  actions and the image, and the window's native drop target reports enter, move, exit and drop
  back through `WindowInput`, whether the drag began here or in another program. A drag that began
  in this process keeps its `Transfer` at hand, so in-process formats cross between two windows of
  the application without being encoded.

| | source | target |
|---|---|---|
| Windows | `DoDragDrop` with an `IDataObject` and `IDropSource` of ours; it runs its own loop, so frames are produced from inside the target callbacks | `RegisterDragDrop` with an `IDropTarget` of ours, in place of GLFW's `WM_DROPFILES` |
| macOS | `beginDraggingSessionWithItems:` from the content view | the dragging-destination methods of GLFW's content view replaced with ours |
| X11 | the XDND source protocol, run by us on our connection (ADR 053 §3) | `XdndProxy` on the window pointing at our hidden window, so XDND messages come to us and not to GLFW |
| Wayland | `wl_data_device.start_drag` on our device, with the serial of our `wl_pointer` | the `enter`/`motion`/`leave`/`drop` of our device |

Measured on KWin, 2026-10-08 (`WlProbe`): a drag between two processes and onto the dragging
window itself, with hover events and all formats; GLFW's own device gets the same events and
still reads `text/uri-list` on a drop, which is harmless once our proxies have their own queue
(ADR 053 §3). X11, Windows and macOS are not measured yet; each is measured as its step starts,
and a step that cannot be made to work comes back to the owner as a decision, not as a silence.

## 3. The keyboard

**The drag mode.** On a focused widget, the drag key (Ctrl+Shift+D, Cmd+Shift+D on macOS;
`Scene.setDragKey`) asks `onDragStart` as a pointer would, at the focused item. From there Tab and
Shift+Tab walk only the widgets that accept the transfer, the arrow keys move the place inside the
target (the row it will go before, the node it will go into), Enter drops and Escape cancels. Each
step is announced: what was picked up and the three keys, each target and place, the outcome. It
works within the window: no platform offers a keyboard drag to another program, and the clipboard
is the way there.

**Direct keys on the ready-made widgets**, the convention of word processors' outlines:

- `Table`: Alt+Shift+Left and Right move the focused cell's column one place.
- `ListView` that reorders: Alt+Shift+Up and Down move the selected rows one place.
- `Tree` that reorders: Alt+Shift+Up and Down among siblings; Left moves the node out one level,
  Right moves it into the sibling before it.

Each is announced ("Total, column 3 of 6").

## 4. The widgets

**The application owns the rows** (ADR 041 §1, ADR 044), so a widget never rearranges them: it
asks.

- **`ListView<T>`**: `setDragEnabled(true)` makes the selected rows a drag (an in-process format
  of the items and their text; `setDragContent(Function<List<T>, Transfer>)` replaces it).
  `onReorder((items, index) -> …)` accepts its own rows and makes them droppable between rows;
  `onDrop((offer, index) -> …)`, with `setDropFormats(…)`, accepts anything else, including
  another list's rows, which is the two-list picker.
- **`Tree<T>`**: the same three, with a place that is a parent and an index: dropped on a node
  goes into it, near its upper or lower edge goes before or after; a node is never offered a
  place inside itself or its descendants; a closed node under a resting pointer opens.
- **`ImageView`**: `setDragEnabled(true)` offers its picture as `IMAGE`, with the picture as the
  drag image. Off by default: most pictures in a form are decoration.
- **`Table<T>`**, phase 2:
  - `moveColumn(column, index)`, `columnOrder()`, and `onColumnsChange` after a move, a resize or
    a column shown or hidden, which is what an application saves and restores.
  - Dragging a header past four points moves the column (a press near its trailing edge still
    resizes); a line shows where it lands; `setColumnReorderingAllowed(false)` turns it off. This
    drag is `withinWindow()`: a column is not data for another program.
  - `setFrozenColumns(n)`: the first `n` shown columns stay while the others scroll under a
    divider; at the trailing side under RTL. A column moves only within its zone.
  - A reader hears the header's new position; the columns' order in the accessibility tree is
    the order on screen.

## 5. What is left out

- Dragging selected text out of a text control, tabs, table rows (not chosen).
- A drag image on a system where it costs a subsystem of its own is decided in its step: the
  pointer's shape is the floor everywhere.
- Touch, and the screen readers' own drag commands (VoiceOver's "mark for drag and drop").

## 6. The order of the work, and when it is done

1. `limn.transfer` and the reshaped clipboard, headless (ADR 053).
2. The native clipboards: Windows, macOS, X11, Wayland.
3. The scene's driver: events, feedback, auto-scroll, Escape, the keyboard mode and its
   announcements; `onFileDrop` migrated.
4. `Table` phase 2, the first consumer.
5. `ListView`, `Tree`, `ImageView`.
6. The system's driver on the four systems.
7. A demo scene, the gallery entries and reader scripts, the live runs, the guide, the CHANGELOG.

Done when: both records are implemented on the three platforms (four systems); a drag between two
lists and a column move are done by keyboard and heard on NVDA, VoiceOver and Orca; a file, a
picture and text are dragged in from and out to a native program on each system.

## 7. As built so far

Step 3, the scene's driver (`Scene`, `limn.scene.event`, `limn.transfer.Drag`), with
`SceneDragTest`:

- **`DropEvent` has `reject()`, not `done(action)`.** A drop is carried out with the action the
  widget accepted unless it refuses before returning, and the source's `onEnd` hears it then. A
  read of the offer may complete later; the source of a move has already let go by then, which
  is right for a drag within the process, whose values are at hand, and is what step 6 has to
  keep true for another program's.
- **No `onDragExit` follows `onDrop`.** The widget clears what marked the place in one or the
  other, never both.
- **What is dragged is shown under the pointer as it is painted.** The owner tried a first build
  that showed a plain rectangle and asked for the item itself. `Drag.preview(x, y, width, height)`
  names the part of the source widget that is dragged (a card, a row, a header), and the scene
  paints the source again there, clipped to that part, at three quarters of its opacity, with the
  pointer holding the point it pressed. With no part named, a small widget is shown whole and a
  large one around the point of the press; `Drag.image` shows a picture instead and
  `Drag.noPreview()` nothing. That painting is exempt from the partial frame's cull, which skips
  a widget whose own place is not being repainted: with the cull on, the picture vanished whole
  over a target that was repainting itself (his second report).
- **A keyboard drag is described by the source and placed by the target**: `Drag.describedAs`
  names what is carried, `DragEvent.describePlace` says where it would land, and a target that
  says nothing is announced by its accessible name, or as "Place 2 of 5". The arrows arrive as
  `stepX`/`stepY` on the same `onDragOver`, and the widget keeps its own place.
- **The first target of a keyboard drag is the source itself** when it accepts its own content:
  moving a row within its list needs no Tab.
- **Auto-scroll is a wheel event** sent to the accepting widget while the pointer rests within
  24 points of one of its edges, every 60 ms: whatever scrolls under a wheel scrolls under a
  drag, with no interface of its own.
- **`Scene.setDragKey(key, modifiers)`** takes the two integers of `Keys`: an `Accelerator`
  belongs to the components, above the scene.
- **Files from the system** still arrive from GLFW at the moment of the drop only: the scene asks
  the widget under the point with one `onDragOver` and hands them over at once. The hover comes
  with step 6.
- The announcements are a new catalogue, `scene_*.properties`, in the 21 languages.

Step 4, the table's columns (`Table`, `TableColumnsTest`):

- **`columns()` is the order**, and `moveColumn(column, index)` changes it: there is no separate
  `columnOrder()`. The rows are realized again after a move, since they keep their texts and
  their cell widgets by column index, and the keyboard's cell stays with its column.
- **A header sorts on the click, not on the press**, so that a press that travels can move the
  column instead.
- **The pointer moves a column in the table's own way, not as a generic drag** (the owner, after
  trying the first build, which showed the header translucent under the pointer and a line where
  it would land): the column follows the pointer along the header only, wherever the pointer is
  up or down the window; it is the whole column, header and rows, opaque on a ground of its own
  between two edges; and the other columns make room as its middle passes theirs. The release
  drops it, Escape puts it back, and it stays within its group. This is the header's own press,
  which has the mouse, and no `Drag` at all; the scene's drag is how the keyboard does the same
  from the header, the others making room at each arrow.
- **`setFrozenColumns(n)`** keeps the first `n` shown columns: they are drawn after the scrolling
  ones, which are clipped to what is left of the viewport, a line marks where they end, and a
  point over them never reaches a column scrolled under them. Where they would leave no room,
  none is kept.
- **`onColumnsChange`** is told after the user moves a column, by drag or by Alt+Shift+Left and
  Right, and after the user resizes one. Never for a call.
- A move is announced as "Total, column 3 of 6", from the table's catalogue in the 21 languages;
  a keyboard drag of a header says the same of the place it would take.
- Not done: the focus ring of a cell scrolled half under the kept columns is drawn over them.

Step 6 on macOS (`WindowPeer.startDrag`, `WindowInput.dragOver`/`dragLeft`/`dropped`, `MacDrag`),
brought forward because the owner tests on a Mac and asked to drag out of the window; he dragged
a card of the demo to another program and files in from Finder:

- **A pointer drag that is not `withinWindow()` is handed to the system one frame after it
  begins.** The scene asks the window for its next frame, cuts the previewed part out of it where
  the source stands (its own picture held back for that frame), and gives the system that cut:
  see-through as the scene's own, and with nothing outside the corners when the part is rounded
  (`Drag.preview(x, y, width, height, cornerRadius)`; the frame is opaque, and a card carried the
  board's background in its corners once it left the window). If the system does not take it,
  the scene goes on driving it.
- **From then on the pointer is the system's.** The window's dragging destination reports enter,
  move, exit and drop to the scene, for this drag as for one begun in another program; for its
  own the scene keeps the transfer at hand, in-process formats and source included, and the
  source hears the end from the widget that took it, or from the system when another program did.
- **On macOS** the source is `beginDraggingSessionWithItems:` with the pasteboard items a copy
  writes (promised through the clipboard's provider class), and the destination is GLFW's content
  view with its four destination methods replaced and every kind of data registered. GLFW's own
  file drop no longer fires there: files arrive as any other drop, now with hover.
- **The picture is the dragged thing painted alone, on nothing** (the owner, after a first build
  that shaped a cut of the opaque frame by a corner radius: a picture with no background must
  keep its own transparency, so hand the widget a transparent surface and have it draw).
  `Drag.preview(width, height, holdX, holdY, painter)`: at the top of the next frame the scene
  clears a corner of it to nothing, has the painter draw there, reads it back with its alpha
  through `Canvas.readBack`, and paints the whole frame over the corner. The scene's own picture
  under the pointer is the same painter. The read is on the canvas and not on the renderer
  because a scene painted from an application's own frame callback is handed a canvas and
  nothing else: read through the renderer, the picture was missing in the demo and the drag went
  out with none, which only a traced run on his Mac showed (`-Dlimn.drag.trace=true`).
- **AppKit tracks a drag in a loop of its own**, inside the event GLFW was dispatching, so the
  application's loop does not turn and no frame is produced while a drag lasts. The destination
  paints the window itself after each answer; without it the widget that accepted marked where
  the drop would land and nothing showed it.
- **The pointer says the answer, and is put back.** Over a place that refuses it is the "not
  allowed" sign, which AppKit does not show by itself and the owner asked for as the scene's
  own drag has it; over one that accepts it is the system's own for the action (the arrow for a
  move, the arrow with a plus for a copy, which he first read as "can be dropped here": it means
  a copy, as in Finder, and a move keeps the plain arrow). When a drag leaves the window, is
  dropped or ends, the window sets its own pointer again, since AppKit keeps the last one set.
- Not done: Windows, X11 and Wayland. A drag whose source gives no painter still shows a cut of
  the opaque frame where the part stands.

Step 5 (`ListView`, `Tree`, `ImageView`; the demo's `--scene picker`):

- **A row is dragged with the rest of the selection when it is part of it**, and alone otherwise.
  A plain press on one of several selected rows keeps them all, since it may begin their drag, and
  selects that row alone at the click if no drag followed. In a tree a node goes with what is
  under it, so one under another that is dragged is not named twice.
- **Three handlers, and not the two of §4**, each of them the application's since the rows are:
  `onReorder` moves rows within the widget; `onDrop((event, index) -> …)` takes what comes from
  elsewhere and is handed the `DropEvent`, not the bare offer, because whether it is a copy or a
  move is the event's to say; and `onMoveOut(rows -> …)`, which §4 did not have, hears that rows
  dragged out were *moved* to another widget or program and removes them. A two-list picker is
  the three together. With `onMoveOut` a drag proposes a move, without it a copy.
- **The place handed to `onReorder` is counted without the rows moved**, so the handler is
  `removeAll(rows); addAll(index, rows)`. Afterwards the widget re-reads the items, and where it
  finds the rows it selects them, reveals them and says "Total, 3 of 12"; rows dropped where
  they already stand ask nothing. In a tree the place is a parent and an index, the node they
  went into opens, and it is named in what is said when it is a new one.
- **In a tree** the upper and lower quarter of a branch are before and after it and the rest is
  into it; a leaf is halves; after an open branch is its first place inside. A node is refused a
  place inside itself (the pointer shows it). A closed branch the pointer rests on for 0.7 s
  opens. The mark is a ring on the node a drop goes into and a line where it goes between two,
  starting at the row's own indent.
- **The keyboard:** Alt+Shift+Up and Down as §3 says, with rows apart gathered at the end they
  move toward; in a tree Left and Right move out one level and into the sibling before, swapped
  right to left. The scene's keyboard drag walks every place with Up and Down ("Before Total",
  "After Total", "Into Reports"), including the end of an open branch, which no row stands before.
- **What follows the pointer is the row's own widget**, painted alone on a ground of its own,
  with a second card behind and "3 items" when it stands for several. `ImageView` paints its
  picture as it shows it, at most 240 points on its longer side.
- A keyboard drag whose source gave no description is named by the source's accessible name.
- A drag does not begin on a scroll bar, on a tree's triangle, or on a focusable control inside
  a row.
- Strings: four in the components' catalogue and six in the tree's, in the 21 languages.
- Not done: a target cannot yet be told which action to prefer (a list dropped on proposes what
  the source does); the readers have not heard any of it (step 7).

Step 5 after the owner's runs of the picker scene on his Mac (list and tree alike):

- **What a pointer drags leaves its widget.** The rows are under the pointer, so they are not
  drawn in the list, and the rows below close over their places; a node goes with every row
  shown under it, which is also why a branch can no longer be offered a place inside itself.
  Called off, the rows come back once the others have slid off their places.
- **Room opens where the drop would land**, under the pointer, with the line in its middle; a
  drop into a node keeps the ring and opens none, and the keyboard's drag keeps the line alone.
  The room is **half** the height of what is dragged: a row is mostly its own margins and the
  rows on either side bring theirs, so room a whole row tall read to him as twice what the item
  needs. What is dropped takes the rest by pushing its neighbours apart.
- **Every change slides** (about a tenth of a second, none under reduced motion): rows closing
  up, making room, and rows moved by Alt+Shift and an arrow, where with nothing sliding a move
  down read as the whole list changing. The place is read with the rows as they stand without
  the lifted ones and without the room, so opening the room does not move its own answer.
- While rows are lifted the layout places rows past the viewport's edge by their height, or the
  foot of a scrolling list stood empty and the row that belonged there appeared at once.
- **On macOS** an observer of the main run loop paints the windows while a drag of ours lasts,
  AppKit's loop running in place of the application's: without it nothing slid until the pointer
  moved, and a mark stayed behind in the buffer not presented. And a source that allows a move
  names the generic operation, which is all AppKit keeps of the mask with Command held.
- WhatsApp shows a dragged picture without its transparency; it does the same with the PNG file
  dragged from Finder (his test), so it is that program's.

Step 6 on Windows, X11 and Wayland (2026-10-09), each measured on its guest with real pointer
events against a native program (a .NET form on Windows, a GTK 3 window on the two others): a
row to the other list and within its own, both through the system; a row's text out; a picture
out; two lines of text in, which became rows. The scripts are under `.claude/pending/2026-10-09/`.

- **Windows** (`WindowsDrag`): OLE. A window registers an `IDropTarget`; a drag out is
  `DoDragDrop` over the shell's data object, filled with what a copy puts on the clipboard, lazy
  values made as the drag begins. `DoDragDrop` returns when the drag is over, so it runs from a
  task posted to the UI thread and the window paints itself after each answer, as on macOS. The
  keys arrive with each call: Control a copy, Shift a move, both or Alt a link. The picture goes
  to the shell as a bitmap with its alpha, and is drawn over a window only if that window's
  destination passes each call to the shell's helper, which ours does.
- **X11** (`X11Drag`): XDND 5 on the clipboard's connection. GLFW's window answers XDND itself,
  for files and at the drop alone, on a connection nothing else can read; each window is given
  an `XdndProxy` naming the clipboard's hidden window, and is answered there. X has no drag to
  hand a session to, so as a source the thread asks where the pointer is sixty times a second,
  finds the window under it that speaks XDND, and tells it; the button let go drops, Escape
  calls it off. The picture is a window of its own with an alpha visual and no input region.
- **Wayland** (`WaylandClipboard`): the clipboard's own `wl_data_device`. `start_drag` with the
  serial of the button press, read by the pointer of ours; the picture is a surface drawn once
  from shared memory, which the compositor moves; as a destination the offer is accepted and its
  action set at each motion, and finished once what was dropped has been read. KWin chooses the
  action from the keys.
- GLFW's own file drop is not listened to where one of these is installed: files arrive as any
  other drop, and its word of the same drop would be a second one.
- Not done: the pointer's shape does not say the answer on X11 (it stays the window's); a link
  is not offered on Wayland (the protocol has copy, move and ask); a fractional scale rounds the
  picture's on Wayland; files dragged in from a file manager were not measured on these three
  (the path is the clipboard's, which was); Mutter is not measured.
- **A picture as a file, when asked** (the owner, after trying Paint and the desktops of the
  two Linux guests, which take a picture only as a file): `Transfer.withImageAsFile(name)` and
  `ImageView.setDragFileName(name)` offer the picture as a PNG file too, written to the
  temporary directory the first time a taker asks for files. Off unless asked, since it writes
  to disk. For it not to be written at every drag, **the Windows drag makes its data when it is
  asked for**: a data object of ours inside the shell's names the formats and renders each at
  `GetData`, where the first build rendered all of them as the drag began. X11 and Wayland ask
  late by nature; macOS asks for a drag's files as it begins, so there the file is written at
  each drag that has the option on.
  Measured: on Fedora the desktop, which did nothing with the picture alone, offers "copy here"
  and its picture widgets for the file. On Windows Paint answered "no effect" to the demo's
  picture with and without the file, **and to a plain file dragged from a .NET program**, at
  the point the script dropped on, so that script says nothing of Paint either way; the owner's
  hand is the measure there. Ubuntu's desktop was not measured.
- **On Wayland the picture is attached after the drag begins.** Attached before, the offset
  that holds it where the pointer pressed it was dropped, and it hung from its corner where the
  other systems hold it by the point pressed (the owner saw the difference from X.Org).
- **A window stood still for twenty seconds on Wayland** (the owner's Fedora guest): a picture
  with its file, dropped on the window it came from. GLFW's own data device answers a drop of
  files on its window by reading them at once, on the UI thread; the program asked was this one,
  whose sending thread asked the UI thread to make the lazy value, and waited. Two things now
  keep the UI thread out of it: a picture that is at hand is written as its file by whichever
  thread asks (`Transfer.needsUiThread` says which values are not like that), and a files value
  only the UI thread can make is made as a Wayland drag begins.
- **Paint takes nothing from the demo, with or without the file, and that is open.** Dragged by
  the owner's hand with the trace on: Paint asks for the shell's item list of the file (now
  offered, written as Explorer writes it) or for the bitmap, answers "copy", shows "Open", and at
  the drop answers "no effect"; it asks for nothing after the drop. The desktop takes the same
  drag and makes the file. A plain file dragged from a .NET program was refused by Paint as well,
  and a JPEG dragged from Explorer opens. Tried and taken back out, having changed nothing:
  declaring that the data may be taken after the drop (`IDataObjectAsyncCapability`). Not yet
  tried: a picture dragged from a browser into Paint, which would say whether any program but
  Explorer can.

