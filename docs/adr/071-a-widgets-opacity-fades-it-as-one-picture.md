# ADR 071: A widget's opacity fades it as one picture

- **Status:** Proposed, 2026-10-17. The owner decided that Limn gets an opacity on the widget,
  applied to the widget and everything under it as one picture, and asked for care with the
  time and the memory it costs. This record is written before any of it is built: section 0 is
  what was measured, section 2 what is proposed, and section 3 the eight choices that are his,
  each with a recommendation and its cost.
- **Date:** 2026-10-17
- **Scope:**
  - an opacity on every widget, how it combines with an ancestor's, and what it does not change
    (layout, input, focus, the accessible tree);
  - how the renderer draws a widget whose opacity is between 0 and 1: an offscreen layer, its
    size, its lifetime and its memory;
  - what a partial frame repaints when an opacity changes, and how it repaints a faded subtree;
  - what a backdrop effect reads inside a faded subtree, and over one;
  - which of the toolkit's own fades move onto it.
- **New public API (proposed):** `Widget.setOpacity(float)` and `Widget.opacity()`;
  `Widget.fadeTo(float, double, Runnable)` if question 3 is answered yes;
  `Canvas.saveLayer(...)`, closed by `restore()`, if question 4 is answered yes.
- **Compatibility:** additive for applications. Inside the repository the in-scene fades of six
  surfaces change how they are drawn (section 2.7), which changes what they look like halfway
  through a fade, and only there.

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
0.159 ms of GPU), which would have been read as a difference between modes. Medians of 300 frames after 60 warm
ones on the GPU, of 40 after 5 on the software renderer, two rounds each; a frame is timed from
its first command to a `glFinish`, the GPU's own time by a `GL_TIME_ELAPSED` query.

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

What the probe does not cover, said so that it is not read into it: the Windows and Linux
guests' drivers, where the composite's fill rate may weigh more than on this GPU; the cost of
re-basing the canvas's clip and transform into a layer, which the probe got for free by starting
a frame on the layer; and a glass inside a group (section 2.6), whose cost is estimated, not
measured. Section 5 measures all three on the guests before the status moves.

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
application's could.

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
  the widget's own value; nothing needs the product, and so no getter for it is proposed.
- **It is a picture, not a state.** It moves no layout, takes nothing from input, focus or the
  accessible tree, and announces nothing to a watcher; questions 1 and 2 put the two halves of
  that to the owner.
- **`setVisible(false)` wins.** A hidden widget is not painted whatever its opacity, and keeps
  its opacity for when it is shown again.

### 2.2 What other toolkits do with an opacity of 0

| | Laid out | Takes the pointer | Takes focus | In the accessible tree |
|---|---|---|---|---|
| A web page's `opacity: 0` | yes | yes | yes | yes |
| The open Java desktop toolkit's node opacity | yes | yes ("a visible node with any opacity still receives mouse events") | yes | yes |
| One open cross-platform mobile toolkit's opacity widget | yes | yes | yes | no, unless asked to keep it |
| **Proposed for Limn** | yes | yes | yes | yes |

All three draw the subtree as one picture between 0 and 1, and all three skip the intermediate
picture at 1 and draw nothing at 0, which is what section 2.3 proposes. The mobile toolkit is
the one that takes a fully transparent subtree out of what a reader hears; question 1 is
whether Limn should do the same, and for input too.

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
  (section 0), and holds nothing in between. Question 8 asks whether that rule, or a budget
  like the images', is the right one.
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
  per glass, over the effect's bounds; it is estimated here and measured in section 5. Question
  5 puts this against what a web page does.
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
  through the layer directly since it is not a widget. The panels lose their fading branches.
  Question 6.
- **The chart's tooltip panel** is drawn by the chart, not a widget: it moves only if
  `Canvas.saveLayer` is public (question 4), and otherwise stays per draw.
- **The fades of popups in windows of their own stay on the window's opacity.** The compositor
  does it as one picture already, with the frame, the shadow margin and the translucent corners,
  and it costs this process nothing; a layer there would cost a window-sized layer per frame to
  do what is already done. Under Wayland, where GLFW sets no window opacity, a popup is drawn in
  the scene (ADR 028) and so fades through section 2.3; a dialog in a window of its own there
  appears and goes without a fade, as it does today. Question 7.
- **The drag picture and the modal scrim** are each one draw at an alpha and stay as they are.

### 2.8 Readers

Opacity is a picture and nothing else: the accessible tree, its states, its bounds and the
shapes of ADR 045 do not read it, and no bridge changes. A widget at 0 is announced as it is
announced at 1; an application that means to take something away hides it (question 1).

## 3. Questions for the owner

Each with what is recommended and what it costs.

1. **An opacity of 0: still there, or gone for input and readers?**
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
   - *Recommended:* no `Change.Aspect` for it. It is a property of the picture, as
     `BackdropPanel`'s tint and cover are, and those announce nothing; announcing it would wake
     every watcher of the widget and its ancestors on every frame of every fade.
   - *Cost:* an application that wants to follow an opacity follows what sets it.
   - *The alternative,* an `OPACITY` aspect with origin `CODE`: one interned change per frame per
     fading widget, and a rule that the accessibility step ignores it.
3. **An animated form on the widget?**
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
   - *Recommended:* yes, `Canvas.saveLayer(x, y, width, height, opacity)` closed by `restore()`,
     with a default that falls back to multiplying the opacity, so a widget that paints overlapping
     parts of its own (the chart's tooltip panel, an application's custom widget) fades them as
     one picture too. `Canvas` is public and a backend implements it, so the method has to be on
     it for `Widget` to call; the choice is only whether its Javadoc invites applications in.
   - *Cost:* one more method in the 1.0 contract, documented with section 0's prices.
   - *The alternative:* the method kept out of the public contract (an internal hook the backend
     implements), widget opacity the only way to a layer, the chart's tooltip per draw.
5. **What does a glass inside a fading group read?**
   - *Recommended:* what it would read if the group were opaque (section 2.6), so a frosted
     surface fades without changing what it shows through.
   - *Cost:* an extra quad over the glass's bounds per open layer per frame, and the code that
     composes the copy from more than one target.
   - *The alternative,* a web page's rule: a group is a root for the effects inside it, which
     then read only the group's own content and show nothing of the window behind. Cheaper and
     simpler, and a frosted popup fading in would show unblurred content through its glass for
     the whole fade and blur it at the last frame.
6. **Do the in-scene fades move onto widget opacity now?**
   - *Recommended:* yes, all of section 2.7's list in the same round as the feature, so the one
     mechanism is exercised by the toolkit's own popups from the first day and the
     shows-through defect of section 1 goes.
   - *Cost:* section 0's partial row per fade frame: 0.04 ms of CPU and 0.03 ms of GPU on this
     GPU, 1.9 ms on the software renderer; a pass over those surfaces' damage and fade tests;
     the readers' runs are not owed, since nothing they read moves (section 2.8).
   - *The alternative:* the feature for applications only, the toolkit's fades left per draw.
7. **Do window fades stay on the window's opacity?**
   - *Recommended:* yes (section 2.7).
   - *Cost:* none; a dialog in a window of its own on Wayland keeps appearing without a fade.
8. **How long does a layer stay?**
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

## 5. Tests, and what is measured before the status moves

- **Pixels, on a real device:** section 0's pixel as a test (a group at one half equals the
  opaque picture mixed with the background, within one step, where the per-draw picture is
  eleven steps off in red); a nested group; a group under a rotation; a group cut by a scroll
  view's clip; a partial pass through a group equal to a whole frame (render both and compare
  every pixel, as the rounded clip was checked against a cut pass); a glass inside a group at opacity 1 equal to the same glass
  with no group, and continuous at 0.99.
- **The API:** clamping, NaN refused, the UI thread, the paint refusal, 0 skipping the subtree,
  1 opening no layer (counted), `setVisible` winning.
- **Damage:** an opacity change damages the box and outset and no layout (`DamageContractTest`'s
  rules); the same value damages nothing.
- **Memory:** the pool after a fade is empty one frame later; siblings take one texture; a closed
  window frees it (ADR 064's churn); `stats()` reports it.
- **The budget of ADR 062:** a group's layer and composite counted among the drawing commands,
  and held not to grow with the subtree.
- **The toolkit's fades,** if question 6 is yes: each surface's fade tests on the new mechanism,
  and the shows-through pixel on each.
- **Measured on the guests before Accepted:** section 0's probe on the Windows and Fedora guests
  and the macOS guest without a GPU, the cost of re-basing the clip and transform (absent from
  the probe), and a glass inside a group.
