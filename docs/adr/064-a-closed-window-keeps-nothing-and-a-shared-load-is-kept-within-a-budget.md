# ADR 064: A closed window keeps nothing, and a shared load is kept within a budget

- **Status:** Accepted and implemented, 2026-10-13.
- **Date:** 2026-10-13
- **Scope:** what stays in memory after a window closes or a widget is removed, and how long the
  shared loads of `Images` and `Sounds` keep what they loaded.
- **New public API:** `Images.loadPinned`, `fromResourcePinned`, `unpin`, `unpinResource`,
  `setSharedBudget`, `sharedBudget`, and the same six on `Sounds`.
- **Compatibility:** a window that closes runs `onDetached` on every widget of its scene, which
  it did not. A shared load can be let go and read again, where it was kept for the life of the
  backend.

---

## 1. Why

A window closed on macOS stayed in the system's overview of windows, and the cause was a
reference nothing released (the event loop's calls had no autorelease pool). The fix freed the
window on the system's side and showed two things. The window was still held on the toolkit's
side, by a list of its own. And a class the accessibility bridge registers per window was named
by the address of the window's view, so that once closed windows were really freed and
addresses came round again, the name was refused and the next window opened with no
accessibility: 58 windows of 60 on the guest.

A review of the whole project for the same family followed, in six parts, each finding read
again by hand and the two above run on the guest. This record holds the two decisions of it
that are the toolkit's design. The rest were defects with one right answer and are in the
change log.

## 2. A window that closes detaches its tree

`Widget.onAttached` and `onDetached` are where a widget takes and gives back what it needs
from outside itself. A widget removed from its parent was detached. A widget still in the tree
when its window closed was not: nothing ran. So everything a widget takes in `onAttached` from
something that lives longer than the window stayed taken, and held the widget, its scene and
all the scene shows:

| Widget | What it took | What a closed window left |
|---|---|---|
| `MenuBar` | a place in the process's list of language listeners | the whole scene, per closed window |
| `MenuBar` on macOS | the window's place in the system's menu bar | the window and its bar's widget |
| `VideoView` | pictures of a decoder | the decoder never freed, though the application closed its source |

Two widgets had already grown a guard of their own for it, which is a design saying the hook
was not being run where it should be.

**Decision.** `Scene`'s close runs the detach over the root and over every overlay, last, after
the focus is cleared, the bridge detached and the close observers run, each of which may still
ask a widget for its scene. A hook that throws is contained and reported as the other steps of
a close are. A scene bound to another window after its window closed has its tree attached
again, so `onAttached` runs a second time, as it does for a widget removed and added.

The backend does not rely on it for what it keeps by window: the macOS menu bar is told of a
closed window by the backend and forgets it, whether or not the bar's widget gave it back.

**Not done.** A widget is not detached when its window is hidden: a hidden window is shown
again as it was.

## 3. A shared load is kept within a budget

`Images.loadShared` and `fromResourceShared`, and the two of `Sounds`, answer every ask for a
source with the same future, and kept every one that landed until the backend closed. An
application that shows a picture per record, a photograph or an attachment or a thumbnail,
grew by each one it had ever shown. The owner's conditions for a limit: what was used longest
ago goes first, under a ceiling that can be set; an application's own resources outlast files;
and there is a way to say that something never leaves memory.

**Decision.**

- **A budget in bytes on the heap,** four a pixel for a picture and two a sample for a clip.
  It starts at a quarter of the most heap the process may use for pictures and an eighth for
  sounds, so that it follows the machine and the application's own setting and is no number
  that fills a small heap and is lost in a large one. `setSharedBudget` moves it; `0` keeps
  nothing, `Long.MAX_VALUE` lets nothing go, which is what there was before.
- **Past it, the one asked for longest ago is let go, every file before any resource.** A
  resource is let go only when no file is left to let go. Use is the asking, since that is
  what a cache of loads sees.
- **Letting go is not forgetting.** What was let go stays known, weakly, for as long as
  anybody holds it, and an ask for its source hands the same object back and reads nothing. The
  texture cache is keyed by the object, so a picture on screen is never decoded beside itself
  and never uploaded twice. Only what nobody holds is read again.
- **`loadPinned` and `fromResourcePinned` keep a load whatever the budget.** It counts nothing
  toward it and is never let go, until `unpin` or `unpinResource`. A source already shared is
  pinned from the ask that pins it, and is the same object. The intent is written where the
  resource is loaded, which is why it is a way of loading and not a mark put on an image later.
- A load in flight is never let go, a failed one is forgotten as before, and
  `clearSharedCache` forgets everything, the pinned too.

## 4. What was measured

On the macOS guest, sixty owned windows opened and closed, before and after:

| Build | Windows with no accessibility | Scenes and windows still on the heap |
|---|---|---|
| Before the event loop had a pool | 0 of 60 | 61 |
| With the pool, before this record | 58 of 60 | 3 |
| After | 0 of 60 | 1, the main window |

The same with an accessibility client reading every window while it was open: 57 readings
answered with a window's contents, no window refused, nothing kept.

On the Windows guest, forty windows: every scene of a closed window still reachable before, and
292 callbacks kept; none reachable after, and 12 callbacks, the main window's. A tour of every
page of the Showcase with a client reading the whole tree throughout, some 7,000 nodes read and
1,000 elements of nodes that left: 1,195 objects in the bridge's registry at the end before, and
200 after. What is let go is freed when the system lets go of it: 58 in one run, none in two
others, and under a screen reader 44 of the 83 that left, in each of four tours.

On the Ubuntu guest no scene of a closed window was reachable before or after.

## 5. What the owner decided of the rest

The review left questions that had no single right answer, and these are his:

- **A `Tree` lets go of loaded children when their row closes.** The tree needs them only
  while the row is open, to make its rows of; kept past that they were a cache nobody had
  bounded. Opening again loads again, and a model that wants otherwise keeps its own.
- **A radio leaves its `ButtonGroup` while it is out of its scene** and returns to its place,
  and `ButtonGroup.remove` takes one out for good.
- **A `MediaPlayer` sleeps at the end and is closed with the backend,** with a warning. A
  window closing does not close a player, which is the application's.
- **A clip is held weakly by the sound engine,** and a stream that is refused is a warning.
- **A transfer's file goes in one directory per run,** the one before deleted as the next is
  written and the directory at the end.
- **`Ui.postDelayed` returns a handle** that withdraws the task.

## 6. Declared limits

- On Windows an object let go of is freed when the system and the reader release it, and no
  sooner. Telling the system to disconnect each node's provider as the node left was measured
  under a screen reader on the guest, a few a frame on the UI thread: the same 44 objects were
  freed in a tour with it and without, of 83 that left, at about a millisecond a node. It is
  not done.
- A window's root provider is counted for the system each time the system asks the window for
  it, and whether every such count comes back was not settled: a close found between 16 and 34
  references on the root over some twenty hand-overs, most of them the reader's own. At worst
  it is one object for each window a reader asked, kept after the window closed.

- A menu or a dialog the application keeps after it closed still names the scene it was last
  shown over, one scene for each object kept, until it is shown again.
- A shader program is kept until its window closes, since a draw of a later frame may name it;
  a window that holds more than 256 says so once.
- A GPU object disposed outside its own window's frame is not refused. The toolkit's own
  widgets hand theirs to `Scene.disposeLater`.
- Two things were read as unbalanced on macOS and left: the pasteboard of a drag is retained
  for each drag that enters a window, and a file panel whose owner is destroyed under it may
  never answer. Neither could be shown to grow, and the second sits where a block freed early
  was a crash.
