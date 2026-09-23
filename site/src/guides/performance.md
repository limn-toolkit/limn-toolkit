---
title: "Performance"
description: "What a frame costs and what makes it cost more: partial rendering, layout versus repaint, long lists, held text, work off the UI thread, and the numbers a scene keeps about itself."
---

A Limn window does no work while nothing changes. The loop waits for input, for a widget that
asked to be repainted, or for an animation that is running, and renders nothing in between. So
performance is mostly about the frames that do happen, and about keeping each one small.

## A frame repaints what changed

Partial rendering is on by default. A widget that calls `invalidate()` records its box as
damage, and the next frame repaints only the damaged regions, so a blinking caret repaints a
caret and not the window. `invalidate(x, y, width, height)` narrows it further, to the part of
the widget that changed.

A layout pass is a different cost: `markNeedsLayout()` measures and places the tree again and
repaints the whole window. Mark it only when a size really changed. When a widget's own size
changes for a reason of its own, `markNeedsLayoutInPlace()` re-measures only the ancestors whose
size moved and repaints only what moved. A `Label` whose text changes does exactly that, so a
status line that ticks every second repaints the label and not the screen around it.

`scene.setDamageDebug(true)` washes each frame's damage in magenta. Two kinds of mistake show up
at once: a whole window flashing on a small change is a layout, or an `invalidate` too wide, where
something narrower would do; a region that never flashes although it changed is a widget that
forgot to invalidate, and it shows stale pixels.

Animations stop themselves while their widget is not showing: in an unselected tab, a collapsed
panel, or scrolled out of view. The repaint that reveals the widget starts them again. On macOS,
a window covered by others stops rendering until it is uncovered, and every window is paced to
the display's refresh rate rather than rendering as fast as it can.

## Measuring

Every scene keeps numbers about itself. `scene.metrics()` holds, each as a rolling `Metric` with
its latest value, its average and its maximum:

| Metric | What it is |
| --- | --- |
| `fps()` | frames per second |
| `frameTime()` | CPU time per frame, in milliseconds: the tick, the layout and the paint |
| `eventTime()` | time spent dispatching input, in milliseconds |
| `gpuTime()` | GPU time per frame, where the driver can measure it |
| `paintedWidgets()` | how many widgets the frame painted |
| `damageRects()` | how many regions the frame repainted |

`paintedWidgets()` and `damageRects()` are the pair to watch: under partial rendering the
painted count should fall to the handful of widgets under the change. Sample `totalFrames()` once
a second and divide by the elapsed time for an average that does not itself keep the window
rendering. The demo's performance screen draws all of these as live charts, with switches for
partial rendering and the damage wash: run the demo with `--scene perf`.

An input handler that runs longer than 8 ms is logged as a warning that names the widget and
how long it took, because for that long the window did not respond.
`scene.setSlowHandlerBudgetMillis(…)` changes the budget.

## Long lists

`ListView`, `Table` and `Tree` build rows only where the viewport reaches, plus the row the
keyboard is on, so their cost follows the size of the window and not the size of the data. A
million rows cost what twenty do, on screen and under a screen reader. Scrolling far is landed
by estimate rather than by measuring every row on the way there.

A table sorts a text column from collation keys it extracts once per sort, rather than comparing
strings through a collator on every comparison. A tree that opens or closes a branch splices
that branch's rows in or out, and leaves the rest of the rows alone.

## Text

Shaping, which turns a string into positioned glyphs, is most of what text costs. Every
component holds its shaped lines and shapes again only when the text, the font, the direction or
the ruler changes, so an unchanged label costs nothing to repaint. A widget that draws text
itself does the same with a `ShapedText`; [Text and languages](/docs/text-and-languages/#if-you-draw-text-yourself)
shows how.

## Work off the UI thread

Anything that takes real time runs on a worker, or the window stops responding for as long as
it takes: a file, a network call, a decode. `Ui.work(…)` runs it and delivers the result back on
the UI thread. For images, `Images.loadShared(…)` and `Images.fromResourceShared(…)` read and
decode on a worker and share one result between every caller asking for the same file, and
`Images.decodeAsync(…)` decodes bytes you already hold. The first `Sounds.isAvailable()` may
open the audio device, which takes a moment; `Sounds.warmUpAsync()` asks on a worker instead.
[Background work](/docs/background-work/) has the whole pattern.

## Your own widgets

A widget's `onPaint` and `onAccessibility` run on every frame that touches it, so neither should
allocate: keep the paths, the strings and the shaped text you draw in fields, and rebuild them
only when what they depend on changes. The toolkit holds its own components to that: a test
fails if a full frame of a form at rest allocates a kilobyte. [Your own
widgets](/docs/custom-widgets/) says which of `invalidate`, `markNeedsLayout` and
`markNeedsLayoutInPlace` a change calls for.
