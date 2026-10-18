# ADR 071: A widget's opacity fades it as one picture

- **Status:** Accepted and implemented, 2026-10-17. Proposed the same day: the owner decided
  that Limn gets an opacity on the widget, applied to the widget and everything under it as one
  picture, and asked for care with the time and the memory it costs. He answered the eight
  questions of section 3 on 2026-10-17, seven with the recommendation and the second with a rule
  of his own (a direct set announces every change, a fade only its start and its end), and the
  probe of section 0 was then run on the lab's three guests (section 0.1), in line with this
  Mac's. Built the same day: the two measurements section 5 left to the code are in section 6.1,
  in line with section 0 once the composite had a program of its own, and section 6.2 says where
  the build departs from the text above and why.
- **Date:** 2026-10-17
- **Scope:**
  - an opacity on every widget, how it combines with an ancestor's, and what it does not change
    (layout, input, focus, the accessible tree);
  - how the renderer draws a widget whose opacity is between 0 and 1: an offscreen layer, its
    size, its lifetime and its memory;
  - what a partial frame repaints when an opacity changes, and how it repaints a faded subtree;
  - what a backdrop effect reads inside a faded subtree, and over one;
  - which of the toolkit's own fades move onto it.
- **New public API:** `Widget.setOpacity(float)` and `Widget.opacity()`;
  `Widget.fadeTo(float, double, Runnable)` and `Widget.fadeTo(float)`;
  `Change.Aspect.OPACITY`; `Canvas.saveLayer(x, y, width, height, opacity)`, closed by
  `restore()`.
- **Compatibility:** `Canvas.setOpacity` multiplies the current opacity instead of replacing it
  (section 2.3); every caller in the repository is accounted for there. Otherwise additive for
  applications. Inside the repository the in-scene fades of six surfaces change how they are
  drawn (section 2.7), which changes what they look like halfway through a fade, and only
  there.

---

## 0. What was measured, before anything was proposed

The proposal rests on one question: what does drawing a subtree into a layer and compositing it
with one alpha cost, against drawing it straight into the window with the alpha on every draw,
which is how the in-scene fades work today. A probe outside the repository answered it on the
real renderer: `GlCanvas` itself, drawing into an RGBA8 framebuffer the size of the window, with
a layer made of an RGBA8 texture and a framebuffer object, and a composite of one textured quad
with premultiplied blending. It ran on a CGL context with no window, once on this Mac's GPU and
once on Apple's software renderer, which is what a Mac without a GPU (the lab's macOS guest)
draws with.

**The scene.** A window of 1280 × 800 points at scale 2 (2560 × 1600 pixels) holding a form: a
toolbar of eight buttons, a list of 26 rows with an icon each, and a table of 30 rows by 5 text
columns. Over it either (a) a popup of 300 × 400 points with two shadow layers, a bordered
panel, 16 rows of an icon and two texts, one row highlighted and a scroll thumb, its layer
696 × 896 pixels because the shadow reaches 24 points past the panel; or (b) a second whole
page, the full window, as a page transition would fade.

**Each mode, per frame:** `OPAQUE`, the subtree at 1, drawn directly; `OPAQUE'`, the same frame
again, which is the floor every other row is read against; `DIRECT`, the subtree drawn directly
with `canvas.setOpacity(0.5)`; `LAYER`, the subtree drawn into a kept layer and composited at
0.5; `CREATE`, as `LAYER` with the layer's texture and framebuffer made and deleted every frame;
`NESTED`, as `LAYER` with a second group inside the popup (its rows) composited at 0.8. The modes
are interleaved frame by frame and rotated, so that the GPU's clock changes land on every mode
alike: run in blocks, two runs of the same frame differed by a factor of two (0.321 and
0.159 ms of GPU), which would have been read as a difference between modes. Medians of 300
frames after 60 warm ones on the GPU, of 40 after 5 on the software renderer, two rounds each;
a frame is timed from its first command to a `glFinish`, the GPU's own time by a
`GL_TIME_ELAPSED` query.

**On the GPU (Apple M5 Max, OpenGL 4.1 over Metal),** milliseconds per frame, round 1 / round 2:

| Subtree | Mode | CPU | GPU | Frame |
|---|---|---|---|---|
| popup, partial frame (the pass is the popup and its shadow, what a fade frame repaints) | OPAQUE | 0.225 / 0.226 | 0.302 / 0.298 | 0.564 / 0.564 |
| | OPAQUE' | 0.222 / 0.225 | 0.301 / 0.298 | 0.560 / 0.563 |
| | DIRECT | 0.222 / 0.225 | 0.300 / 0.299 | 0.561 / 0.567 |
| | LAYER | 0.259 / 0.265 | 0.327 / 0.326 | 0.598 / 0.598 |
| | CREATE | 0.348 / 0.354 | 0.331 / 0.329 | 0.625 / 0.629 |
| | NESTED | 0.293 / 0.297 | 0.358 / 0.356 | 0.645 / 0.649 |
| popup, whole frame | DIRECT | 0.482 / 0.434 | 0.608 / 0.580 | 1.115 / 1.007 |
| | LAYER | 0.516 / 0.475 | 0.637 / 0.616 | 1.142 / 1.048 |
| | CREATE | 0.640 / 0.599 | 0.635 / 0.612 | 1.184 / 1.087 |
| whole window | OPAQUE | 0.625 / 0.618 | 0.797 / 0.785 | 1.431 / 1.400 |
| | OPAQUE' | 0.615 / 0.615 | 0.797 / 0.783 | 1.419 / 1.393 |
| | DIRECT | 0.609 / 0.612 | 0.798 / 0.783 | 1.420 / 1.407 |
| | LAYER | 0.649 / 0.651 | 0.921 / 0.906 | 1.552 / 1.534 |
| | CREATE | 1.124 / 1.127 | 0.981 / 0.967 | 1.686 / 1.677 |

**On the software renderer (Apple Software Renderer, 4.1 APPLE-24.0.2),** which runs every
command on the CPU as it is issued, so the CPU, GPU and frame times are one number:

| Subtree | OPAQUE | OPAQUE' | DIRECT | LAYER | CREATE | NESTED |
|---|---|---|---|---|---|---|
| popup, partial frame | 139.0 / 138.7 | 138.6 / 139.2 | 139.2 / 138.4 | 141.1 / 140.3 | 140.5 / 140.9 | 142.2 / 142.1 |
| popup, whole frame | 223.1 / 223.2 | 223.1 / 222.8 | 222.8 / 222.8 | 217.4 / 217.5 | 216.7 / 217.7 | 218.0 / 218.7 |
| whole window | 187.5 / 185.6 | 186.6 / 185.3 | 187.8 / 185.7 | 205.0 / 203.3 | 206.0 / 203.8 | — |

**Making a layer.** On the GPU, `glTexImage2D`, the framebuffer and its completeness check take
0.013 ms for the popup's size and 0.010 ms for the window's: the driver allocates later. The
first clear of a new layer takes 0.22 ms (popup) and 0.46 ms (window), against 0.15 ms for a
kept popup layer's clear. On the software renderer both are under 0.07 ms.

**What it costs in memory.** A layer is four bytes a pixel: the popup's 696 × 896 is 2.38 MiB,
the window's 2560 × 1600 is 15.63 MiB, and a 1280 × 800 window at scale 1 is 3.9 MiB. For
scale, the backdrop copy every window that draws a glass keeps (ADR 019 §4) is the same 15.63 MiB
at this size, and a window's image textures are kept within 256 MiB (CN-8,
`Images.DEFAULT_TEXTURE_BUDGET`).

**What it buys.** The probe read one pixel, on the popup's highlighted row, inside its icon: the
icon over the row's highlight over the panel over the table. Behind the popup that pixel is
`244, 245, 248`; the popup drawn opaque makes it `91, 123, 213`; the popup at one half, as one
picture, must make it `168, 184, 231`. Drawn per draw it came out `157, 177, 229` (GPU) and
`158, 177, 229` (software): the highlight and the panel under the icon show through it, each at
half. Drawn through a layer it came out `167, 184, 231` and `168, 184, 231`, within one step of
exact; the step is the layer's own 8-bit rounding.

**What the numbers say.**

- **A popup's fade frame,** which under partial rendering repaints the popup and its shadow,
  costs 0.04 ms of CPU and 0.03 ms of GPU more through a layer on this GPU, about 6% of that
  frame; on the software renderer 1.9 ms more, 1.4% of it. Both are above the floor (OPAQUE
  against OPAQUE' differ by at most 0.012 ms on the GPU and 0.9 ms on the software renderer, in
  any row) and both are small.
- **A whole window's fade** costs 0.12 ms more of GPU on this GPU (15% of that frame's GPU time,
  the composite of four million pixels and the layer's clear) and 17.2 to 17.6 ms more on the
  software renderer, 9% of a frame that already takes 186 ms there.
- **A second level of nesting** costs about what the first does again: 0.03 ms of CPU and
  0.03 ms of GPU for a popup-sized group, 1.1 to 1.8 ms on the software renderer.
- **Making the layer every frame instead of keeping it** costs 0.09 to 0.12 ms of CPU for the
  popup and 0.48 ms for the window on the GPU, and nothing measurable on the software renderer.
  A fade lasts about twelve frames: keeping the layer across them is worth it, keeping it after
  is not (section 2.5).
- **On the software renderer a popup drawn through a layer took 5.4 ms less** than drawn
  directly in a whole frame, in both rounds. It is measured and not explained, and nothing
  below relies on it.

What the probe does not cover, said so that it is not read into it: the cost of re-basing the
canvas's clip and transform into a layer, which the probe got for free by starting a frame on
the layer, and a glass inside a group (section 2.6), whose cost is estimated. Neither can be
measured before the code exists; section 5 measures both when it is built. The guests' drivers
were the third gap, and section 0.1 closes it.

### 0.1 On the lab's guests (2026-10-17)

The same probe, the same scene and the same interleaved method, run on the three guests after
the owner's decisions, as section 5 required before the status could move. A guest's OpenGL is
Parallels' own, translated to this Mac's GPU (Windows and Fedora) or Apple's software renderer
(the macOS guest has no GPU), so these numbers are about each driver's path, not about other
hardware.

**Windows 11 ARM64** (Parallels' driver over the M5 Max, OpenGL 4.1; a native ARM64 JDK 21; a
hidden GLFW window in the console session, started by a one-shot interactive task). Its
`glFinish` returns before the GPU is done, so the frame column equals the CPU column there, and
the GPU's own time is the timer query's:

| Subtree | Mode | CPU | GPU |
|---|---|---|---|
| popup, partial frame | OPAQUE | 0.155 / 0.153 | 0.122 / 0.129 |
| | OPAQUE' | 0.154 / 0.151 | 0.121 / 0.127 |
| | DIRECT | 0.154 / 0.152 | 0.121 / 0.127 |
| | LAYER | 0.156 / 0.155 | 0.210 / 0.217 |
| | CREATE | 0.439 / 0.456 | 0.551 / 0.580 |
| | NESTED | 0.158 / 0.155 | 0.273 / 0.286 |
| popup, whole frame | DIRECT | 0.321 / 0.308 | 0.192 / 0.192 |
| | LAYER | 0.322 / 0.308 | 0.287 / 0.283 |
| whole window | OPAQUE | 0.513 / 0.498 | 0.269 / 0.260 |
| | OPAQUE' | 0.510 / 0.494 | 0.250 / 0.245 |
| | DIRECT | 0.507 / 0.493 | 0.244 / 0.235 |
| | LAYER | 0.506 / 0.495 | 0.345 / 0.338 |
| | CREATE | 0.915 / 0.902 | 1.373 / 1.355 |

A layer costs no CPU there (within 0.003 ms) and 0.09 ms of GPU for the popup, 0.10 ms for the
window; a second level 0.06 ms more. Making the layer every frame costs 0.3 ms of CPU and
0.35 ms of GPU for the popup, 0.4 ms and 1.0 ms for the window: the driver allocates as the
texture is made (0.55 ms and 1.03 ms for `glTexImage2D` and the framebuffer), which is the case
for keeping the layer through a fade.

**Fedora 44 KDE under Wayland** (virgl over the M5 Max, OpenGL 4.0 core, Mesa 26.2.3; JDK 25;
a hidden GLFW window in the running session):

| Subtree | Mode | CPU | GPU | Frame |
|---|---|---|---|---|
| popup, partial frame | OPAQUE | 0.175 / 0.168 | 0.336 / 0.327 | 0.312 / 0.305 |
| | OPAQUE' | 0.171 / 0.165 | 0.335 / 0.327 | 0.310 / 0.298 |
| | DIRECT | 0.173 / 0.166 | 0.334 / 0.328 | 0.310 / 0.299 |
| | LAYER | 0.180 / 0.174 | 0.358 / 0.354 | 0.375 / 0.365 |
| | CREATE | 0.402 / 0.391 | 0.382 / 0.371 | 0.713 / 0.693 |
| | NESTED | 0.181 / 0.178 | 0.393 / 0.387 | 0.414 / 0.405 |
| popup, whole frame | DIRECT | 0.336 / 0.329 | 0.435 / 0.438 | 0.521 / 0.521 |
| | LAYER | 0.345 / 0.341 | 0.462 / 0.481 | 0.601 / 0.605 |
| whole window | OPAQUE | 0.536 / 0.528 | 0.612 / 0.612 | 0.757 / 0.751 |
| | OPAQUE' | 0.524 / 0.515 | 0.606 / 0.606 | 0.756 / 0.740 |
| | DIRECT | 0.522 / 0.511 | 0.607 / 0.605 | 0.745 / 0.725 |
| | LAYER | 0.529 / 0.514 | 0.771 / 0.751 | 0.808 / 0.784 |
| | CREATE | 1.747 / 1.730 | 0.938 / 0.890 | 2.612 / 2.576 |

A popup's fade frame costs 0.065 ms more through a layer (0.025 ms of GPU, under 0.01 ms of
CPU); a whole window's 0.06 ms of frame and 0.15 ms of GPU; a second level 0.04 ms. Making the
layer every frame costs 0.33 ms per popup frame and 1.8 ms per window frame, for the same
reason as on Windows.

**macOS 27 without a GPU** (Apple Software Renderer, 4.1 APPLE-24.0.2; JDK 21; a CGL context
with no window, over SSH), milliseconds per frame, which here is the CPU's:

| Subtree | OPAQUE | OPAQUE' | DIRECT | LAYER | CREATE | NESTED |
|---|---|---|---|---|---|---|
| popup, partial frame | 143.5 / 136.9 | 143.4 / 136.8 | 143.8 / 137.2 | 145.0 / 139.4 | 145.1 / 139.4 | 146.8 / 140.5 |
| popup, whole frame | 230.9 / 221.7 | 231.2 / 222.0 | 231.2 / 222.6 | 226.5 / 216.2 | 226.0 / 216.9 | 227.5 / 218.2 |
| whole window | 189.9 / 183.4 | 189.6 / 183.4 | 189.4 / 183.9 | 208.2 / 201.6 | 208.1 / 201.9 | — |

A popup's fade frame costs 1.2 to 2.2 ms more through a layer (0.9 to 1.6% of it), a whole
window's 17.7 to 18.8 ms (about 10%), a second level 1.1 to 1.8 ms; making the layer every frame
costs nothing measurable, as on the host's software run. The whole-frame popup is again faster
through a layer, by 4.7 and 6.4 ms, and still not explained. The guest's numbers are within a
few per cent of the host's software run, which is expected: the renderer is the same, on cores
of the same machine.

**The pixel** came out as on the host: per draw `157, 177, 229` (Windows, Fedora) and
`158, 177, 229` (macOS), through a layer `167, 184, 231` and `168, 184, 231`, against an exact
`168, 184, 231`.

**Against the floor:** OPAQUE and OPAQUE' differ by at most 0.004 ms of CPU and 0.019 ms of GPU
on Windows, 0.011 ms of frame and 0.006 ms of GPU on Fedora, 0.3 ms on the macOS guest. Every
cost read above is over its guest's floor, and every one is in line with this Mac's: a fraction of a millisecond
per fade frame where there is a GPU, and on the renderer with none a share of the frame close to
the host's own software run.

## 1. Why

A fade today is drawn in one of two ways, and neither is the one the owner asked for.

- **A popup in a window of its own** fades the window: `Scene.fadeWindow` moves
  `NativeWindow.setOpacity`, and the compositor blends the whole window, frame and content, as
  one picture. That is group opacity, done by the system, and it costs this process nothing.
- **A surface drawn in the scene** (where a window cannot be placed, as on Wayland, or in front
  of a fullscreen owner) multiplies the canvas's opacity on every draw: `AnchoredPopup.fadeOf`
  read in `onPaint` and `paintChildren` by the combo box's list, the date picker's calendar and
  the tab list, `sceneFade` in `PopupMenu`, `fade` in `Dialog`'s card, `tooltipAlpha` in the
  scene's tooltip, and the chart's tooltip panel. Every draw is then half-transparent on its
  own, so whatever a later draw covers shows through it: the panel's own shadow darkens the
  panel, the highlight shows through the row's icon (section 0), the card's border shows through
  its buttons. Halfway through a fade, a popup in the scene is not the popup at half; it is all
  of its layers at half each.

There is also a narrower defect in the same mechanism. `Canvas.setOpacity` sets the state's
opacity rather than multiplying it, so a widget inside a fading panel that set an opacity of its
own would replace the fade rather than combine with it. No widget in the toolkit does today; an
application's could. Section 2.3 decides it: the call multiplies.

And there is a use the toolkit cannot offer at all: an application that wants to fade a part of
its own window in or out, a panel that appears, a page that replaces another. The owner sees
fades as the main use.

## 2. Decision

### 2.1 The API

- **`Widget.setOpacity(float opacity)`**, fluent like `setVisible`, and **`opacity()`**. 1 by
  default. A number past either end is clamped to it and NaN is refused with
  `IllegalArgumentException`, as `BackdropPanel.setCover` does. UI thread only; called from
  inside a paint it throws, as an announcement made there does, because a paint is re-run per
  pass and must not move what it paints.
- **It multiplies.** A widget at 0.5 inside one at 0.5 shows at 0.25, and not by arithmetic on
  draws: the inner one is a picture composited into the outer one's picture. `opacity()` returns
  the widget's own value; nothing needs the product, and so no getter for it is added.
- **It is a picture, not a presence.** It moves no layout and takes nothing from input, focus or
  the accessible tree, at any value, 0 included (decision 1).
- **`setVisible(false)` wins.** A hidden widget is not painted whatever its opacity, and keeps
  its opacity for when it is shown again.
- **`fadeTo(float target, double seconds, Runnable onArrive)` and `fadeTo(float target)`**
  (decision 3) move the opacity to `target` over `seconds`, or the theme's window animation for
  the short form, eased as `Scene.fadeWindow` eases, on the scene's ticker only while it moves.
  A newer `fadeTo` or a `setOpacity` supersedes one in flight. While the system asks for less
  motion, or with no scene to tick, or for zero seconds, it goes straight to the target and
  `onArrive` runs at once. `onArrive` runs once, when the fade arrives, and not when it is
  superseded.
- **A watcher hears an opacity change by the owner's rule** (decision 2), through a new aspect,
  `Change.Aspect.OPACITY`, origin `CODE`:
  - a `setOpacity` that changes the value announces it, every time;
  - a `fadeTo` announces twice: when the fade starts, and when it arrives or is superseded.
    Its steps in between go through a path that sets the value and damages the widget without
    announcing, so a fade of any length wakes a watcher twice and never once a frame;
  - a `fadeTo` that goes straight to its target is a `setOpacity`: one announcement if the value
    changed, none if it did not;
  - nothing is announced for a value set to what it already is;
  - the accessibility step does not react to the aspect, since a reader reads nothing of it
    (section 2.8).

### 2.2 What other toolkits do with an opacity of 0

| | Laid out | Takes the pointer | Takes focus | In the accessible tree |
|---|---|---|---|---|
| A web page's `opacity: 0` | yes | yes | yes | yes |
| The open Java desktop toolkit's node opacity | yes | yes ("a visible node with any opacity still receives mouse events") | yes | yes |
| One open cross-platform mobile toolkit's opacity widget | yes | yes | yes | no, unless asked to keep it |
| **Limn (decision 1)** | yes | yes | yes | yes |

All three draw the subtree as one picture between 0 and 1, and all three skip the intermediate
picture at 1 and draw nothing at 0, which is what section 2.3 decides. The mobile toolkit is
the one that takes a fully transparent subtree out of what a reader hears; question 1 asked
whether Limn should do the same, and for input too, and the owner kept the web page's rule.

### 2.3 How it is drawn

- **At 1, nothing changes**: no layer, no cost, the frame is the frame of today.
- **At 0, nothing is drawn**: `paintWidget` returns before the subtree, as it does for a widget
  that misses the clip. The widget is still there (section 2.1).
- **Between, the subtree is drawn into a layer and the layer is composited with the opacity.**
  `Widget.paintWidget` opens it around `onPaint`, `paintChildren` and `onPaintOverlay` and closes
  it after, in the `finally` that already trims the canvas back to its depth.
- **The layer covers what the widget can paint, as far as it can be seen:** its box grown by
  `1 + paintOutset()`, the same number partial rendering grows its damage by, in device pixels,
  intersected with the canvas's current clip (a pass, a scroll view's viewport, the window), and
  rounded outward to whole device pixels so that every draw inside keeps the pixel grid it has
  in the window. Under a rotation it is the transformed box's bounding box. A widget that paints
  past what it declares is already a partial-rendering defect (stale pixels); inside a group it
  is also clipped at the layer's edge in a whole frame, which makes the defect visible sooner.
- **The layer is 8-bit RGBA, premultiplied**, as the window's own framebuffer is: compositing it
  loses at most one step of 255 (section 0), and a 16-bit layer would double the memory for a
  precision the window cannot show.
- **Inside the layer the canvas works as it does in the window.** The canvas flushes what is
  queued, binds the layer, moves its device origin to the layer's corner (the root transform and
  the device clip of `DeviceClip` are re-based by a whole number of pixels) and turns the
  partial-rendering scissor off, since the layer is already the visible part of the pass. On
  closing it flushes, binds the target it came from, and adds one quad that samples the layer
  with the opacity, as an ordinary batched draw under the clip of the state it was opened in.
  Each group therefore costs two batch breaks and one quad, which is what section 0 measured.
- **Text keeps its antialiasing**: the glyph atlas is coverage, not subpixel colour, so a glyph
  drawn into a transparent layer and composited is the same glyph.
- **A canvas that cannot make layers** (the recording and measuring canvases of the tests,
  `NoopCanvas`) falls back to today's per-draw multiplication, which is wrong in the way
  section 1 says and right for counting what is drawn.
- **The layer is public:** `Canvas.saveLayer(x, y, width, height, opacity)`, closed by
  `restore()`, is what `Widget.paintWidget` calls and what a widget that paints overlapping
  parts of its own calls (decision 4). Its default implementation is `save()` and the per-draw
  multiplication of the point above, so every canvas has it; its Javadoc carries section 0's
  prices.
- **`Canvas.setOpacity` multiplies** the current state's opacity instead of replacing it, and
  says so, so that an opacity set inside a fading group or a layer combines with it. Nothing
  relies on replacing: every caller in the repository sets it once on a fresh `save()` whose
  opacity is 1 (the six fades, the drag picture, the demo's test card, `RoundRectPiecesTest`),
  where multiplying gives the same value, or multiplies by hand already
  (`canvas.setOpacity(canvas.opacity() * fade)` in `Chart`, two places in the demo's
  `AnimationsScene` and one in its `VideoScene`), and those four drop the hand multiplication
  in the same change, or they would square it.

### 2.4 What a partial frame repaints

- **An opacity change damages the widget's box grown by its outset**, what `invalidate()`
  damages, and nothing more: no layout, since nothing moves. Going to 0, coming from 0, and the
  frame at which the layer starts or stops (1 to below 1 and back) are the same damage. Setting
  the value the widget already has damages nothing.
- **A pass repaints a group correctly by the rule it already follows.** A pass clears its
  rectangle and repaints everything that reaches it, in order (ADR 043 §2). A group reached by
  the pass makes its layer the size of the group's part of the pass, draws into it every
  descendant that reaches the pass, the rest being culled as today, and composites it inside the
  pass. Nothing outside the pass is read or written, so what the last frames left there stays
  right, and a frame of eight passes opens the group's layer up to eight times, each the size of
  its own pass.
- **A fade frame costs one popup's repaint**, as it does today: the in-scene fades already damage
  their panel every tick. Section 0's partial row is that frame.

### 2.5 The layers' memory

- **One pool of layer textures per window**, in the canvas, next to the backdrop copy, counted
  in `stats()` so the performance monitor shows it.
- **A texture is taken when a group opens and given back when it closes**, so siblings reuse one
  texture, and what is held at once is one layer per level of nesting on the path being drawn:
  never more than the nesting depth times the window's size.
- **A taken texture may be larger than asked.** It is drawn into in part, with the viewport and
  the composite's texture coordinates set to the part, so a fade whose layer shrinks or grows by
  a pixel does not reallocate.
- **Between frames a pooled texture is kept only while frames keep using it.** A frame that
  opens no group gives every pooled texture back to the driver when it ends; a fade of twelve
  frames makes its layer once, and the frame after the fade frees it. A static half-transparent
  group repainted now and then pays the making again each time it is repainted, 0.1 to 0.5 ms
  (section 0), and holds nothing in between. There is no knob (decision 8).
- **A closed window keeps nothing**, as ADR 064 requires of everything a window holds, and a
  framebuffer resize lets go of textures that no longer fit.

### 2.6 Backdrop effects and groups

A backdrop effect (ADR 019) copies what the frame has already drawn under its shape and draws
the copy through its effect. Inside a layer, what has already been drawn is only the group's own
content: the window under the group is in another target, and an empty layer is transparent.

- **A glass inside a group reads what it would read if the group were opaque:** the target the
  group is composited into, with each open layer's content so far laid over it in order. The
  copy is taken from the outermost target and each layer is drawn over it once, into the copy,
  for the effect's bounds and its reach; the glass then draws into its own layer as before. At
  an opacity of 1 there is no layer and this is today's reading, so a frosted surface fading in
  goes from nothing to itself without a jump. The cost is one more textured quad per open layer
  per glass, over the effect's bounds; it is estimated here and measured when it is built
  (section 5). Decision 5 chose it over what a web page does.
- **A group over a glass** changes nothing for the glass: the glass was drawn before the group,
  and the group's composite is one more draw over it.
- **A glass over a fading group** reads the window with the group composited in it, as it reads
  anything else drawn before it, and partial rendering repaints it on each fade frame because
  the group's damage reaches it (`paintsFromBackdrop`, ADR 019 §6).
- **A `BackdropPanel` that is itself given an opacity** draws its glass inside its own layer, and
  so reads through as the first point says.

### 2.7 The toolkit's own fades

- **Every in-scene fade moves onto the widget's opacity:** `AnchoredPopup` sets its layer's
  opacity on each tick instead of the three panels reading `fadeOf` in their paint (combo box,
  date picker, tab list); `PopupMenu`'s cascade; `Dialog`'s card, its veil staying a single
  fill whose alpha fades, since one fill cannot show through itself; and the scene's tooltip,
  through `Canvas.saveLayer` since it is not a widget. The panels lose their fading branches.
  Decision 6.
- **The chart's tooltip panel** is drawn by the chart, not a widget, and moves onto
  `Canvas.saveLayer` (decision 4).
- **The fades of popups in windows of their own stay on the window's opacity.** The compositor
  does it as one picture already, with the frame, the shadow margin and the translucent corners,
  and it costs this process nothing; a layer there would cost a window-sized layer per frame to
  do what is already done. Under Wayland, where GLFW sets no window opacity, a popup is drawn in
  the scene (ADR 028) and so fades through section 2.3; a dialog in a window of its own there
  appears and goes without a fade, as it does today. Decision 7.
- **The drag picture and the modal scrim** are each one draw at an alpha and stay as they are.

### 2.8 Readers

Opacity is a picture and nothing else: the accessible tree, its states, its bounds and the
shapes of ADR 045 do not read it, and no bridge changes. A widget at 0 is announced as it is
announced at 1; an application that means to take something away hides it (decision 1).

## 3. The owner's decisions, 2026-10-17

The eight questions as they were put, each with what was recommended and what it costs, and
under each what the owner decided. Seven took the recommendation; the second is a rule of his
own, which section 2.1 carries.

1. **An opacity of 0: still there, or gone for input and readers?**
   - **Decided:** still there. Laid out, hit, focusable and read at every value;
     `setVisible(false)` wins.
   - *Recommended:* still there, at every value, as a web page and the open Java toolkit do:
     laid out, hit, focusable, read. One rule (opacity is a picture; `setVisible` is presence)
     and nothing that changes on the last frame of a fade.
   - *Cost:* an application that fades something out and forgets to hide it leaves a control the
     user cannot see and can still click or tab into, and a reader still names. Question 3's
     `onArrive` is where the hiding goes, and the Javadoc says so.
   - *The alternative,* at exactly 0 no pointer, no focus and no node (the mobile toolkit's
     readers rule, extended to input): safer for the forgetful, but a widget's presence then
     depends on a float compared with zero, focus is lost on the last frame of a fade-out and a
     reader hears a node appear on the first frame of a fade-in.
2. **Does a watcher hear an opacity change?**
   - **Decided, neither option as put:** a `setOpacity` that changes the value announces it, every
     time, through `Change.Aspect.OPACITY` with origin `CODE`; a `fadeTo` announces only twice,
     when it starts and when it arrives or is superseded, and its steps go through a path that
     does not announce (section 2.1). The cost the recommendation feared, a watcher woken on every
     frame of every fade, does not arise, and a watcher still hears every change it asked for.
   - *Recommended:* no `Change.Aspect` for it. It is a property of the picture, as
     `BackdropPanel`'s tint and cover are, and those announce nothing; announcing it would wake
     every watcher of the widget and its ancestors on every frame of every fade.
   - *Cost:* an application that wants to follow an opacity follows what sets it.
   - *The alternative,* an `OPACITY` aspect with origin `CODE`: one interned change per frame per
     fading widget, and a rule that the accessibility step ignores it.
3. **An animated form on the widget?**
   - **Decided:** yes, `fadeTo(target, seconds, onArrive)` and `fadeTo(target)` as recommended:
     the toolkit's easing, straight to the target while the system asks for less motion, a newer
     call superseding one in flight.
   - *Recommended:* yes, since fades are the use the owner named:
     `Widget.fadeTo(float target, double seconds, Runnable onArrive)` and the short
     `fadeTo(float target)` with the theme's window animation, eased as `Scene.fadeWindow` eases,
     on the scene's ticker only while it moves, honouring the system's wish for less motion
     (`Motion.duration`), a newer call superseding one in flight, `onArrive` run once when it
     settles (and at once headless). Without it an application drives `setOpacity` from a
     `Transition` by hand, which has no per-frame callback for it.
   - *Cost:* one method pair on `Widget` and a ticker per fading widget, the same machinery the
     window fade already is.
   - *The alternative,* `setOpacity` alone in this record, and an animation API later.
4. **Is the layer a public `Canvas` method?**
   - **Decided:** yes, `Canvas.saveLayer(x, y, width, height, opacity)` closed by `restore()`,
     with the per-draw fallback.
   - *Recommended:* yes, `Canvas.saveLayer(x, y, width, height, opacity)` closed by `restore()`,
     with a default that falls back to multiplying the opacity, so a widget that paints overlapping
     parts of its own (the chart's tooltip panel, an application's custom widget) fades them as
     one picture too. `Canvas` is public and a backend implements it, so the method has to be on
     it for `Widget` to call; the choice is only whether its Javadoc invites applications in.
   - *Cost:* one more method in the 1.0 contract, documented with section 0's prices.
   - *The alternative:* the method kept out of the public contract (an internal hook the backend
     implements), widget opacity the only way to a layer, the chart's tooltip per draw.
5. **What does a glass inside a fading group read?**
   - **Decided:** what it would read if the group were opaque.
   - *Recommended:* what it would read if the group were opaque (section 2.6), so a frosted
     surface fades without changing what it shows through.
   - *Cost:* an extra quad over the glass's bounds per open layer per frame, and the code that
     composes the copy from more than one target.
   - *The alternative,* a web page's rule: a group is a root for the effects inside it, which
     then read only the group's own content and show nothing of the window behind. Cheaper and
     simpler, and a frosted popup fading in would show unblurred content through its glass for
     the whole fade and blur it at the last frame.
6. **Do the in-scene fades move onto widget opacity now?**
   - **Decided:** yes, the six of section 2.7 now; the dialog's veil stays one fill.
   - *Recommended:* yes, all of section 2.7's list in the same round as the feature, so the one
     mechanism is exercised by the toolkit's own popups from the first day and the
     shows-through defect of section 1 goes.
   - *Cost:* section 0's partial row per fade frame: 0.04 ms of CPU and 0.03 ms of GPU on this
     GPU, 1.9 ms on the software renderer; a pass over those surfaces' damage and fade tests;
     the readers' runs are not owed, since nothing they read moves (section 2.8).
   - *The alternative:* the feature for applications only, the toolkit's fades left per draw.
7. **Do window fades stay on the window's opacity?**
   - **Decided:** yes, on `NativeWindow.setOpacity`.
   - *Recommended:* yes (section 2.7).
   - *Cost:* none; a dialog in a window of its own on Wayland keeps appearing without a fade.
8. **How long does a layer stay?**
   - **Decided:** kept only while consecutive frames use it, freed at the end of a frame that
     opens no group, no public knob.
   - *Recommended:* while consecutive frames use it, given back at the end of a frame that opens
     no group (section 2.5), no knob. The most a window holds is bounded by the window and the
     nesting depth, and nothing is held after a fade.
   - *Cost:* a group that stays half-transparent and is repainted now and then makes its layer
     each time, 0.1 to 0.5 ms on this GPU.
   - *The alternative,* a budget like `Images.setTextureBudget`: textures kept across idle
     frames, dropped oldest first past the budget. Faster for that static group and holding
     up to the budget for as long as the window is idle, since a window that draws no frames
     drops nothing.

## 4. Not in this record

- **Other group effects** (a blur of the group, a blend mode for the group, a mask). The layer
  is what they would be built on, and none is asked for.
- **Caching a group's picture between frames** so that a fade composites without redrawing the
  subtree. A fade changes only the alpha, so a cache would turn each fade frame into one quad;
  it would also hold the layer for as long as the picture is valid and need to know when any
  descendant changes. Section 0 shows the redraw is cheap enough not to need it now.
- **Opacity in the theme or the editor's file.** Nothing in a theme is a widget's opacity.
- **The disabled look.** Widgets dim themselves by colour, not by opacity, and stay so.

## 5. Tests, and what is measured

- **Pixels, on a real device:** section 0's pixel as a test (a group at one half equals the
  opaque picture mixed with the background, within one step, where the per-draw picture is
  eleven steps off in red); a nested group; a group under a rotation; a group cut by a scroll
  view's clip; a partial pass through a group equal to a whole frame (render both and compare
  every pixel, as the rounded clip was checked against a cut pass); a glass inside a group at
  opacity 1 equal to the same glass with no group, and continuous at 0.99.
- **The API:** clamping, NaN refused, the UI thread, the paint refusal, 0 skipping the subtree,
  1 opening no layer (counted), `setVisible` winning; at 0 the widget still hit, focused and in
  the accessible tree.
- **Announcements:** a `setOpacity` that changes the value announces one `OPACITY` change and
  one that does not announces none; a `fadeTo` of any number of frames announces exactly two, a
  superseded one its own end and the new one its start; a `fadeTo` under reduced motion announces
  one and runs `onArrive` at once; the accessible tree is not republished by any of them.
- **`Canvas.setOpacity` multiplies:** an opacity set inside a layer or under another combines
  with it; the four hand multiplications are gone and their pictures unchanged.
- **Damage:** an opacity change damages the box and outset and no layout (`DamageContractTest`'s
  rules); the same value damages nothing.
- **Memory:** the pool after a fade is empty one frame later; siblings take one texture; a closed
  window frees it (ADR 064's churn); `stats()` reports it.
- **The budget of ADR 062:** a group's layer and composite counted among the drawing commands,
  and held not to grow with the subtree.
- **The toolkit's fades** (decision 6): each surface's fade tests on the new mechanism, and the
  shows-through pixel on each.
- **Measured on the guests:** section 0's probe on the Windows and Fedora guests and the macOS
  guest without a GPU, done on 2026-10-17 (section 0.1) and in line with this Mac's. Two
  measurements need the code and are owed by the change that builds it, on the same guests and
  by the same interleaved method: the cost of re-basing the clip and transform into a layer,
  against section 0's LAYER rows, and a glass inside a fading group against the same glass with
  none. Either coming out of line with section 0 reopens this record rather than being absorbed
  by the build.

## 6. Built, 2026-10-17

### 6.1 The two measurements section 5 owed

Run on this Mac, on the built canvas, by section 0's method: the same window, page, popup and
window-sized page, the modes interleaved frame by frame and rotated, medians of 300 frames after
60 warm ones on the GPU and of 40 after 5 on the software renderer, two rounds, OPAQUE against
OPAQUE' as the floor. Each mode drew with a canvas of its own, so that a canvas's pool saw what a
fade gives it, consecutive frames that open the same layer, whatever the interleaving. The modes
section 0 did not have:

- **REAL**, the subtree drawn through `GlCanvas.saveLayer`, the built path: no frame of its own,
  the canvas's clip and transform as they are, the picture from the canvas's pool, against
  section 0's **LAYER**, which started each layer as a frame of its own (run again here beside
  it); **NESTED_REAL** with the rows in a second `saveLayer` at 0.8.
- **A glass inside a fading group:** a frosted band over the popup's rows (two blur passes and a
  tint, 280 × 120 points) in the popup's partial frame, **GLASS** with no group and
  **GLASS_GROUP** in a group at one half, and the same band filled flat as their control,
  **PLAIN** and **PLAIN_GROUP**, so that (GLASS_GROUP − GLASS) − (PLAIN_GROUP − PLAIN) is what
  composing the glass's copy through the open layer costs.

**First run: out of line, and why.** With the picture composited by the canvas's own program (a
textured quad in the batch), the software renderer took 180.8 ms for the popup's fade frame
against 145.9 ms for LAYER and 445.5 ms for the window against 212.4 ms, and the GPU 0.02 to
0.05 ms more than LAYER on the popup's frame. The canvas program runs its clip and its branches
on every pixel of the picture, which on a renderer that runs fragment shaders on the CPU is the
whole cost, as it was for the scrim before the flat program (`GlCanvas.emitFlatRect`). The
composite now has a program of its own, `canvas_composite`, which samples the picture and
multiplies it by the opacity and nothing else, as one draw call of its own as section 0's was;
the numbers below are with it.

**On the GPU (Apple M5 Max, OpenGL 4.1 over Metal),** milliseconds, round 1 / round 2 (the GPU's
clock moved between rounds, and within a round every mode saw the same clocks):

| Subtree | Mode | CPU | GPU | Frame |
|---|---|---|---|---|
| popup, partial frame | OPAQUE | 0.225 / 0.222 | 0.308 / 0.308 | 0.573 / 0.566 |
| | OPAQUE' | 0.224 / 0.221 | 0.309 / 0.314 | 0.572 / 0.574 |
| | DIRECT | 0.227 / 0.221 | 0.311 / 0.310 | 0.580 / 0.570 |
| | LAYER | 0.267 / 0.261 | 0.331 / 0.333 | 0.608 / 0.603 |
| | REAL | 0.269 / 0.261 | 0.331 / 0.329 | 0.609 / 0.601 |
| | NESTED_REAL | 0.301 / 0.294 | 0.364 / 0.363 | 0.661 / 0.650 |
| popup, whole frame | OPAQUE | 0.480 / 0.414 | 0.591 / 0.188 | 1.089 / 0.691 |
| | LAYER | 0.528 / 0.467 | 0.622 / 0.196 | 1.135 / 0.721 |
| | REAL | 0.537 / 0.466 | 0.621 / 0.196 | 1.145 / 0.729 |
| whole window | OPAQUE | 0.597 / 0.543 | 0.287 / 0.219 | 0.899 / 0.786 |
| | OPAQUE' | 0.597 / 0.544 | 0.287 / 0.219 | 0.897 / 0.786 |
| | LAYER | 0.636 / 0.582 | 0.319 / 0.255 | 0.948 / 0.833 |
| | REAL | 0.644 / 0.583 | 0.324 / 0.253 | 0.954 / 0.835 |
| glass band, partial frame | PLAIN | 0.218 / 0.207 | 0.315 / 0.112 | 0.570 / 0.349 |
| | PLAIN' | 0.218 / 0.209 | 0.316 / 0.112 | 0.567 / 0.350 |
| | PLAIN_GROUP | 0.257 / 0.243 | 0.337 / 0.120 | 0.601 / 0.362 |
| | GLASS | 0.306 / 0.292 | 0.420 / 0.141 | 0.684 / 0.383 |
| | GLASS_GROUP | 0.380 / 0.371 | 0.470 / 0.166 | 0.740 / 0.445 |

**On the software renderer (Apple Software Renderer, 4.1 APPLE-24.0.2),** one number per frame:

| Subtree | OPAQUE | OPAQUE' | DIRECT | LAYER | REAL | NESTED_REAL |
|---|---|---|---|---|---|---|
| popup, partial frame | 146.6 / 149.6 | 146.7 / 149.4 | 146.7 / 149.7 | 148.7 / 152.1 | 149.4 / 153.0 | 151.2 / 154.9 |
| popup, whole frame | 231.7 / 239.5 | 231.5 / 239.7 | 231.3 / 240.7 | 225.7 / 234.1 | 226.4 / 234.6 | — |
| whole window | 195.2 / 201.4 | 195.2 / 201.3 | — | 213.8 / 219.8 | 212.8 / 219.9 | — |

| Glass band, partial frame | PLAIN | PLAIN' | PLAIN_GROUP | GLASS | GLASS_GROUP |
|---|---|---|---|---|---|
| software renderer | 147.5 / 150.3 | 147.2 / 150.1 | 150.4 / 152.3 | 215.5 / 218.7 | 220.9 / 224.0 |

**The pixel** came out `167, 184, 231` on the GPU and `168, 184, 231` on the software renderer
through the built layer, against an exact `168, 184, 231` and `157, 177, 229` per draw, as section
0 had it.

**Measurement 1, re-basing into a layer: nothing to re-base, and nothing measurable.** The build
re-bases neither the clip nor the transform (section 6.2): REAL against LAYER is +0.002 / 0.000 ms
of CPU, 0.000 / −0.004 ms of GPU and +0.001 / −0.002 ms of frame for the popup's fade frame, inside
the floor (OPAQUE against OPAQUE' at most 0.005 ms of CPU, 0.006 ms of GPU, 0.008 ms of frame),
and the same for the whole frame and the window. Over the opaque frame the built layer costs the
popup's fade frame +0.044 / +0.039 ms of CPU and +0.023 / +0.021 ms of GPU, section 0's +0.04 and
+0.03; a second level +0.03 of each, as section 0 found. On the software renderer REAL is 0.6 to
0.9 ms over LAYER on the popup's fade frame (0.4 to 0.6% of it, floor 0.1 to 0.2 ms), the clear of
the picture's part and one more flush, and within a millisecond of it on the whole frame and the
window; over the opaque frame +2.8 / +3.4 ms on the popup's fade frame, section 0's 1.9 ms being
this run's LAYER at +2.1 / +2.5.

**Measurement 2, a glass inside a fading group: in line with the estimate.** Composing the glass's
copy through the open layer, one composite per glass over the region it reads, costs the band's
two glass draws +0.035 / +0.043 ms of CPU and +0.028 / +0.017 ms of GPU on the GPU, and +2.5 /
+3.3 ms on the software renderer, 1.1 to 1.5% of a 216 ms frame, over what the group costs the
same band filled flat (+0.039 / +0.036 ms of CPU and +0.022 / +0.008 ms of GPU, +2.9 / +2.0 ms on
the software renderer). Section 2.6 estimated one more textured quad per open layer per glass over
the effect's bounds, and that is what it costs.

Neither reopens this record. They were run on this Mac's GPU and its software renderer, the
renderer the macOS guest draws with; the three guests of section 0.1 were not run again for the
build.

### 6.2 Where the build departs from the text above, and why

- **Nothing is re-based (section 2.3).** Every vertex, every clip and the backdrop copy stay in the
  window's device pixels; the batch is told the target's corner and size, and the programs take
  them as uniforms (`u_origin`, `u_viewport`, and `u_window` for the backdrop's coordinates), so a
  layer moves only the projection and the fragment's way back to the window. With no layer the
  offset is exactly zero and the fragment's position is `gl_FragCoord` bit for bit. The
  partial-rendering scissor is off while a layer is the target and kept for the window.
- **The composite is a program of its own (section 2.3, "as an ordinary batched draw"):** section
  6.1 says why. It draws under no clip, since the picture was drawn under the clip already and a
  second pass of it would square the antialiased edge.
- **Pictures are rounded up to 64 pixels**, capped at the framebuffer, so a fade one pixel larger
  takes the same one; at the end of a frame every picture that frame did not take goes back to
  the driver, which keeps decision 8's rule ("kept only while consecutive frames use it") per
  picture rather than per frame.
- **`AnchoredPopup` fades the panel it presents, not its layer (section 2.7).** The `Layer` widget
  covers the scene so that a press beside the card dismisses it; its opacity would make a picture,
  and a repaint, the size of the window on every frame of the fade. The panel is the card and its
  shadow (its `paintOutset`), so the picture and each frame's repaint are the card's.
- **`PopupMenu`'s cascade fades through `Canvas.saveLayer`**, over its columns and what their
  shadows reach, and not through the surface's widget opacity: in the scene that surface covers
  the scene, for the same reason. A step of its fade now repaints the cascade, where it repainted
  the window.
- **`Dialog`'s card** fades through its own `fadeTo` beside the dialog's own fade, which the veil's
  alpha and the card's slide follow and which ends the dialog; the veil stays one fill.
- **`fadeTo` runs on the scene's wall-time ticker** (`addRealTimeTicker`), as the window fades do:
  an `onArrive` often hides or removes something, and a paused scene must still be able to. "No
  scene to tick" is a widget in no scene or in a scene with no window, as `Scene.fadeWindow` has
  it; a fade on a widget that leaves its scene settles at its target at that scene's next frame
  and runs its `onArrive` then. `fadeTo(target)` takes the theme's `animWindow` through
  `limn.scene.internal.WindowAnimation`, which the theme installs as it does `LayoutOverflow`.
- **An opacity change's damage asks for no accessible walk** (`Scene.damageWidgetPicture`), since
  nothing the tree publishes reads it (section 2.8): a fade of twelve frames walked and compared
  the tree twelve times to find it unchanged.
- **The work budget of ADR 062** counts a layer as one drawing command (`Work.Counting`), and a
  group costs exactly one whatever it holds.

### 6.3 What section 5 asked for, and where it is

- Pixels on a real device (`GlLayerTest`): the record's pixel, 168 through a layer and 157 per
  draw; nesting; a rotation; a clip off the grid; 0 drawing nothing and 1 opening nothing; a glass
  inside a group at 0.99 within four steps of the glass with none, and a glass over the page inside
  a group reading the page. `OpacityPassSeamTest`: a partial frame through a fading panel, a fade
  step, and a glass inside a fading panel, each equal to the whole frame within two steps.
- The API, announcements and damage (`WidgetOpacityTest`): clamping, NaN, the UI thread, the paint
  refusal, 0 skipping the subtree and 1 opening no layer, `setVisible` winning; at 0 still laid
  out, hit, focused and in the tree; a set announcing every change and none for the same value, a
  fade of twelve frames announcing two, a superseded one its end and the new one its start, an
  instant one one; the tree neither republished nor walked; the box and outset damaged with no
  layout, the same value damaging nothing.
- `Canvas.setOpacity` multiplying, inside a layer and under another (`GlLayerTest`); the four hand
  multiplications are gone.
- Memory (`GlLayerTest`): siblings share one picture, counted in `stats()`; a frame with no layer
  frees it; a closed canvas deletes its textures.
- The budget (`WorkBudgetTest`): a group costs one command at ten rows and at forty;
  `FullFrameAllocationTest` holds a fading form to the same ceiling as a still one.
- The toolkit's fades (`InSceneFadesAsOnePictureTest`): each of the six and the chart's tooltip
  halfway through its fade is one layer at the fade's opacity with every draw inside at 1;
  `InScenePopupFadePixelTest`: on a real device a combo box list's highlighted row halfway through
  its fade is the opaque row mixed with what is beneath by the plain row's share, where the per-draw
  fade drew it 12.5 steps darker.
