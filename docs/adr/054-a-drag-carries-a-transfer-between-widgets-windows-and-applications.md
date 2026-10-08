# ADR 054: A drag carries a transfer between widgets, windows and applications, and the keyboard can do it too

- **Status:** Accepted, 2026-10-08, with the owner's answers below. Step 3 of §6 is built: the
  scene's driver and the keyboard's drag; §7 says where it departs from the text. The widgets
  (steps 4 and 5) and the system's driver (step 6) are not.
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
- **The picture under the pointer is the one the source gives** (`Drag.image`); with none there
  is only the pointer's shape, an arrow over a widget that accepts and the "not allowed" sign
  elsewhere. "The widget as painted" is left to the widgets of step 5, which know which rows
  they are dragging.
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
