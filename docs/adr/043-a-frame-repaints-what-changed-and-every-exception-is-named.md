# ADR 043: A frame repaints what changed, and every exception is named

- **Status:** Proposed, 2026-09-10; §9's first item, which was the blocker, landed the same day.
  The flag itself is not flipped. §9 says what has to be true before the flag is flipped, and §11 what
  is deliberately not in this step.
- **Date:** 2026-09-10
- **Scope:** whether `Scene.setPartialRendering` should be on by default; what a widget owes if it
  is; the one correctness hole that blocks it today and what closing it costs; and what stays
  full-frame for ever because it should.
- **Compatibility:** the default changes, which is a behaviour change for every application that
  never touched the flag. §10 says what an application does if it wants the old behaviour, and why
  that escape hatch has to exist even though nothing in this repository needs it.

---

## 0. What was measured, before anything was decided

The question arrived from use rather than from a profile. Driving the date widgets on 2026-09-09,
Dyorgio reported that the whole screen flickered on every focus change, and — this is the part
that made it a real report rather than an impression — that moving the calendar's own roving cursor
did **not** flicker. Two gestures, one flickering, one not, in the same window.

Instrumenting the scene's repaint clip headlessly gave the shape of it:

| gesture | with the flag off | with the flag on |
| --- | --- | --- |
| focus into a field, while the fade runs | 100.0% of the window per frame | 2.0% |
| the same, once it has settled | 100.0% | 0% — no frame is painted at all |
| a day cursor step inside an open popup | 100.0% | 0.5%, and the clip is the two cells |

Measured over a nine-hundred-by-seven-hundred window holding a picker and eight text fields,
counting the area of the clip the scene applies before anything is drawn. Three things fell out of
it, and only the first was expected.

**The third row is a correction, and the way it was wrong is worth keeping.** It first read 50.4%,
and that number was an artefact of the harness: it averaged four frames, two of which were the
popup's own opening, which marks the layout dirty and is a full frame by the structural invariant.
Averaged, "opening a popup" and "moving a cursor" came out as one number that described neither.
Two further attempts to measure it read 12.8% and 0.0%, both also artefacts — a focus fade still
running under the measurement, and then frames that painted *nothing* being scored as full. The
number above is from a probe that does one gesture in a fresh scene, prints per frame rather than
averaging, settles past every transition first, and prints the clip rectangle so the reader can
check it against the geometry: `114,126 44x72` is two day cells, stacked, which is what a Down key
changes. A measurement that cannot be checked against a rectangle is an opinion with a decimal
point.

**The flag is doing what it says.** Two per cent against a hundred is not a micro-optimization; it
is the difference between repainting a form and repainting a field.

**Focus changes were full-frame even with the flag on.** `Scene.setFocus` ended with a bare
`requestRender()`, which is a full frame by definition, so the one gesture that happens most in a
form was exempt from partial rendering entirely. Fixed in `2cd5ac6`, independently of this record:
what moves on a focus change is at most two boxes, and each is now damaged with the outset its
focus ring reaches into. That is a prerequisite for this question rather than part of its answer.

**And the flicker was never the repaint.** A single full-frame repaint does not flicker; eight in a
row do. A focus change starts a 0.14-second fade, which is roughly eight frames, and every one of
them repainted the whole window. The roving cursor starts no fade at all — its transition is
already at one — which is exactly why that gesture never flickered. The report was precise and the
cause was ordinary.

## 1. Decision

**`Scene.setPartialRendering` defaults to on.** An application that wants whole frames asks for
them; today it is the other way round, and nothing but inertia says it should be.

The argument is not the two per cent. It is that **the default should be the mode the toolkit is
correct in**, and the work of being correct in it has already been done widget by widget: every
`paintOutset()` override in the tree, every "declare your damage" note in ADR 002, every
`damageRow` in a popup panel, exists for a mode nothing switches on. Ten of the thirty-four widget
classes declare an outset, and each of those overrides was written because somebody found stale
pixels under a flag they had to enable by hand. That is an odd place to leave a toolkit: paying the
whole price of a discipline and then defaulting to the mode where it does not matter.

## 2. What partial rendering already is

Stated here because the decision rests on it, and it is spread across `Scene` today.

- Damage is a **list of up to eight rectangles**, not one bounding box, so a progress bar at the top
  and a status line at the bottom do not conspire to repaint everything between them. Rectangles
  whose union wastes little area merge on arrival; disjoint hot spots stay separate passes.
- `Widget.invalidate()` records that widget's scene-space bounds inflated by `1 + paintOutset()`.
  **`paintOutset()` is the whole contract a widget has with this mode**: what it paints beyond its
  own box.
- A frame repaints the union of **this frame's damage and the previous frame's**, because under
  double buffering the back buffer holds the frame from two presents ago.
- `requestRender()` and every layout, overlay and tooltip path stay full-frame: anything not routed
  through `invalidate()` is conservatively everything.

That last line is the design's own safety net, and it is why this change is smaller than it sounds.
A layout pass is already always a full frame — "a structural invariant, not a convention" (ADR 002)
— because a widget that moved cannot invalidate bounds it no longer has.

## 3. The prerequisites, and which are already done

**Done: focus damage** (§0, `2cd5ac6`). Before it, the most common gesture in any form bypassed the
mode entirely.

**Done: the focus-ring audit.** A blanket repaint is exactly what hides an under-declared outset,
so removing one means checking what was hiding behind it. Of the eleven widgets that draw with
`Theme.focusRing` and declare no `paintOutset()`, every one either morphs its own border colour on
its own edge (`TextField`, `TextArea`, `Spinner`, `ComboBox`, `ListView`, `SplitPane`) or insets the
ring inside its box (`TabbedPane`); `Slider` reserves the ring inside its measured height by
construction (`sliderPad()` is `knobHover + FOCUS_GAP_SLIDER + BORDER`). Nothing was relying on the
blanket.

**Done 2026-09-10, and it was the blocker: a widget that depends on pixels it does not own.**
ADR 019 §6 recorded it exactly: a shape filled from its backdrop keeps showing the older backdrop if content
behind it changes while it does not itself invalidate. It does not bite over a video that repaints
every frame, and it does not bite over static content; it bites in between. The fix is named in
that record's §7 and has never been built: *a widget that declares itself backdrop-dependent, whose
rect joins the damage list whenever the damage intersects it*.

**The surface was one class.** `BackdropPanel` is the only widget in the toolkit that fills from
what is behind it; `BackdropEffect` and `Canvas.fillBackdropRoundRect` are the plumbing under it.
So §7.3 was not a research project, and what landed is a predicate and a loop:

```java
protected boolean paintsFromBackdrop() { return false; }   // Widget
```

A widget answering `true` registers with its scene on the one funnel a subtree joins and leaves a
scene through, so the registry cannot drift from the tree; and where the frame composes its damage,
one pass adds the scene-space rect of every registered widget the damage reaches, repeating until
nothing new is added — a panel over a panel is one reach away from a second. The rect goes through
the same ancestor-clipping walk `invalidate()` uses, so a panel scrolled out of a viewport adds
nothing, and it is widened *before* the frame stores its damage, so the other buffer gets it too.
Inert where there are none, which is almost every scene: one emptiness check per frame.

**Reaching is over-approximated on purpose**: what stales the picture is a change *behind* it, and
the test is any intersection. Front from back needs paint order, which the damage list does not
carry; over-approximating costs a repaint of something already being painted over and can never
miss one, which is the right way round for a correctness rule.

## 4. What every widget owes under this default

One sentence, and it is already the contract: **declare, in `paintOutset()`, everything you paint
outside your own box, and call `invalidate()` for everything you change.**

The second half is where the interesting failures are, and this work produced two, both in code
written this month by somebody who had read the contract.

`CalendarView` invalidated **itself** when the cursor moved one cell: correct, and wasteful, which
is this mode's failure mode. It now damages the cell the cursor left and the cell it arrived at,
and falls back to the whole grid exactly where the whole grid does change — a range band redrawing
between its ends, and a page turn. Measured after: `114,126 44x72`, two cells.

`DatePicker` was worse and less visible. Every key it forwarded to the grid called a
`repaintPopup()` that did two things it should not: it marked the popup's layout dirty, and a
layout pass is a full frame by the structural invariant; and it invalidated the in-scene overlay,
which is **the size of the whole scene**. So each arrow key threw away the half per cent the grid
had just asked for and repainted the window. It now asks only the popup *window* for a frame, and
only because a second window has a frame loop of its own that this scene's damage does not reach.

Both were invisible under the current default, because the frame was full anyway. That is the
argument of §6 in one paragraph.

## 5. What stays full-frame, for ever

Not everything should be partial, and the paths that are not are not a backlog:

- **A layout pass.** A widget that moved cannot damage where it used to be.
- **An overlay pushed or removed.** A whole layer appeared or vanished, and the layout is dirty
  anyway.
- **A front painter, a palette change, a size-step change** — anything that changes what every
  widget draws rather than what one of them draws. ADR 002 says it in its own words about the size
  axis: *"A step change produces exactly one full frame, which is the only correct answer."*
- **The first frame**, and the frame after the flag itself is toggled.
- **A capture harness that paints over the window through `setFrontPainter`**, whose contract says
  in as many words that it relies on whole frames (§9.3).

## 6. Why not simply leave it opt-in

Three answers, in increasing order of how much they matter.

**Because nobody opts in.** The flag has existed for as long as the damage list has, and this
repository turns it on in exactly two places, both of them tools: the kitchen sink's debug toggle
and the benchmark. No demo scene enables it, so no capture, no gallery run and no manual pass has
ever exercised it — which is how a focus change stayed full-frame long enough to be found by
somebody looking at a flicker.

**Because a default nobody uses rots.** Every widget written from here on will be correct in the
mode its author ran, and its author ran the default. The `CalendarView` step in §4 was written this
month, by somebody who had read `paintOutset`'s contract, and it still over-invalidates — because
nothing in the loop showed it.

**Because the cheaper mode is the honest one.** A toolkit whose windows repaint entirely on every
caret blink is spending a laptop's battery to avoid an audit it has already mostly done.

## 7. What could go wrong, and how it is caught

**Stale pixels**, from an under-declared outset or a missing `invalidate()`. Caught by the
damage-debug overlay, which washes each frame's fresh damage in magenta and works with the mode on
or off (off shows what *would* be repainted). It is already built, already in the kitchen sink and
the perf scene, and it is what turned this report into a measurement.

**A backdrop showing history**, until §3's predicate lands. This is the blocker, not a risk.

**A regression nothing sees**, because the suite runs headless and a stale pixel is a picture. The
answer is the one `PartialRenderingTest` already models: assert the *clip*, not the picture. A test
that drives a gesture and asserts the repaint region is small is a test that fails when a widget
starts over-invalidating, and it costs a recording canvas.

## 8. Cost

The flip itself is one line. The work is §9's list, and most of it is auditing rather than writing.

The runtime cost of the mode is one union per frame, a list of at most eight rectangles, and a clip
per pass — against, in a form, ninety-eight per cent of the pixels not drawn.

## 9. What has to be true before the flag is flipped

1. ~~**§3's backdrop predicate**~~ — **done 2026-09-10**, with a test that fails by exactly the
   symptom it prevents: with the pass removed, the repaint region holds the widget that changed
   and not the panel made of it.
2. ~~**`CalendarView` damages cells rather than itself**~~ — **done 2026-09-10**, along with the
   `DatePicker` defect it uncovered (§4), and **the other four are now done too**. Each was boxed
   strictly inside a larger window so that "the widget" and "the window" are different rectangles
   — without that they are the same number and the question cannot be asked.

   | widget | a selection or cursor step damaged | now |
   | --- | --- | --- |
   | `PopupMenu`'s panel | the two rows | already precise, through its own `damageRow` |
   | `TabbedPane` | the whole pane, on every frame of the indicator slide | 21.6% → 2.1% |
   | `ListView` | the whole list, `322x222` for one arrow | `322x50` — **2.1 rows of 9.2** |
   | `Table` | the whole table, `322x222` for one arrow | `322x66` — **2.1 rows of 6.9** |

   `TabbedPane`'s cause is worth naming because it is a trap rather than an oversight: the
   indicator's two transitions were constructed as `new Transition(this)`, so an animation drawn
   **in the strip** damaged the pane — the strip and the whole page under it — once per frame for
   the length of the slide. Binding them to the strip is a one-word change. **Any animation bound
   to a widget larger than what it draws has that shape**, and §9.2.1 is the same trap where no
   such child widget exists to bind to.

   ### 9.2.1 What reading the two most-used widgets closely actually found

   Three defects, not one, and the one the list opened with was the smallest.

   **A selection step repainted the widget.** `ListView.select` and `Table`'s four selection seams
   all ended in `invalidate()`. Fixed by damaging the row band left and the row band arrived at;
   `Table` takes the difference between a snapshot of its selection and the result rather than
   reasoning per call site, because its four seams change between one row and every row and only
   the difference knows which. Past six changed rows it takes the table, which is the honest
   answer for a select-all.

   **A keyboard scroll repainted the window** — and this was the larger number. `ensureVisible`
   and `scrollToOffset` in both widgets asked for `markNeedsLayout()`, which a scene answers with
   full damage, while the wheel path next to them had already been changed to
   `markNeedsContainedLayout()` for exactly the reason that applies to all three: a scroll changes
   which rows are mounted and where they sit, and both are inside a box the widget clips and whose
   own size a scroll cannot move. So the wheel had stopped paying and the Page key, the End key,
   any arrow that ran off an edge, and every drag of the scrollbar were still paying a whole
   window. The contract makes this safe by construction: the scene re-measures and falls back to a
   full pass if the size moved, so getting it wrong costs a frame rather than correctness.

   **A focus arrival repainted the list eleven times.** `ListView`'s focus fade is a
   `new Transition(this)` — the `TabbedPane` trap again — but there is no child widget to bind it
   to, because the ring is drawn by the list itself across the selected row. `Transition` gains
   `damages(Runnable)`, an opt-in narrowing for exactly this case, and the list names its selected
   row's band. **Eleven whole-list frames become two plus nine row bands**; the two that remain are
   `Scene.setFocus` damaging the newly focused widget's box, which it must, because it cannot know
   that this widget's focus draws inside one row.

   **A row band has to be clamped to its own widget.** Found by the measurement, not by reading:
   damage is clipped by every *ancestor* that clips its children, and a widget is not its own
   ancestor, so a row whose box a pending reveal has already pushed out of the viewport painted a
   band onto whatever sat below the list. Both helpers clamp.

   `SelectionDamageTest` is the first instance of §9.4 and asserts the clip in rows rather than
   pixels, so the numbers survive a change of theme; each assertion was checked by backing its own
   fix out and watching it go red.

3. ~~**A demo scene running with the mode on**~~ — **done 2026-09-10.** The demo application
   runs **every** scene with the mode on, set where it binds its scene, so every manual pass and
   every `--screenshot` run exercises it; the same scene captured both ways is byte-identical.

   The **gallery capture harness deliberately does not**, and that is a finding rather than an
   omission. `Scene.setFrontPainter` documents that what a front painter draws outside the damaged
   region is clipped away, and that "the capture harness renders whole frames and so does not have
   to" mark damage itself — the harness draws its pointer layer through exactly that seam. A
   capture harness is a fifth member of §5's list: it paints over the whole window on somebody
   else's schedule.

   **What the mode would cost there is now measured, and the measurement took a harness fix
   first.** The A/B could not be run at all, because two consecutive runs of the capture command
   *with no change between them* differed in 880 of 4578 images. A difference of that size says
   nothing about anything, and the first attempt — 880 with the mode on against 24 with it off —
   was comparing noise against a lucky run.

   The cause was the harness, not the films. 852 of the 880 were frames of the filmed theme-editor
   entry, and frame `f000` already differed, before any animation: the two runs captured it at
   **4096×2800 and 2048×1400**. `Gallery` read one monitor scale from its probe window and sized
   the showcase window with it, and the two windows are created separately, so on a machine with a
   Retina panel and an external monitor beside it they can open on displays of different scale.
   One scale, two framebuffers, one of them wrong by a factor of two.

   **Fixed by choosing the monitor rather than discovering it** (`WindowConfig.on(Display)`,
   §9.3.1 below). Two runs now differ in **12 of 4633**, and those twelve are the kitchen sink's
   live performance footer — FPS 293 against 361, memory 666 MB against 634 — which is a
   wall-clock reading painted into the picture and cannot be reproducible by construction. That
   is the noise floor, and it is a named set rather than a number.

   **Against that floor the A/B reads:** the mode on differs from the mode off in **27 of 4633**
   images. Twelve are the floor, the same twelve by name. The remaining fifteen are frames
   `f204`–`f218` of one film — one contiguous run, one box of `1183,641 10x101` device pixels held
   still across all fifteen, and **a maximum channel difference of 1 in 255**. The box is the two
   rounded caps of a scrollbar thumb over the frames its fade lasts. Not a stale pixel: a stale
   pixel is a large difference that persists, and this is a rounding difference that begins and
   ends with an animation.

   So the honest statement, which is no longer a shrug: **across one pass over the whole widget
   set, partial rendering changes one antialiased edge by one level for the length of one fade.**
   The harness still stays full-frame, for §5's reason and not for that one.

   ### 9.3.1 The harness fix, because the next person will hit the same wall

   GLFW's `glfwCreateWindow` takes a monitor, and it is not the answer: passing one means
   *fullscreen on that monitor*, verified here — the window came back 1920×1200 with
   `glfwGetWindowMonitor` set. The windowed answer is the pair of hints GLFW 3.4 added,
   `GLFW_POSITION_X` / `GLFW_POSITION_Y` (this tree runs GLFW 3.5.1 through LWJGL 3.4.3), and
   position is the whole of it: **a window's content scale is the scale of the monitor it is
   created on**, so the monitor has to be chosen at construction and no arithmetic afterwards can
   undo a framebuffer that came out the wrong size.

   Two things were measured on a three-monitor Mac (built-in at 2×, two externals at 1×) before
   any of this was written, and both matter:

   - A **hidden** window hinted onto the **primary** monitor opens there, at that monitor's scale,
     at creation: `pos=(40,40) fb=800x600 scale=2.00` for a 400×300 request. This is the case the
     harness needs and it works.
   - A hidden window hinted onto a **secondary** monitor does **not** — macOS keeps it on the
     primary, and it only moves once `glfwSetWindowPos` is called or the window is shown. So
     "always the primary display" is not a simplification here, it is the supported case.

   Wayland has no window positions at all; `LwjglBackend.canPositionWindows()` already knew that
   and the hints are not asked for there.

   Two earlier attempts to fix this by resizing instead of placing **hung the harness**, and the
   reason is worth keeping: `LwjglWindow.setSize` ends in `renderNow(true)` for a resizable
   window, so a corrective resize before the capture loop starts renders inside a loop that is not
   running yet. Placement avoids the question by never needing the correction.

4. **A clip-asserting test per interactive widget**, or at least per widget with a moving cursor.
   **Started, not finished**: the shape is in `PartialRenderingTest`; `SelectionDamageTest`
   (§9.2.1) and `FadeDamageTest` (§9.4.1) now cover seven widgets, alongside the existing
   `ListScrollDamage`, `CaretDamage`, `MenuDamage`, `TooltipDamage` and `BackdropDamage` tests.
   What has none is every other widget with a moving cursor.

   ### 9.4.1 The five large animators, measured

   §9.2.1 asked which of the forty `new Transition(this)` in this toolkit animate something
   smaller than their owner, and named five worth reading. All five were measured, each boxed
   420×320 inside a 500×400 window, at a real 60 Hz tick, as a share of **the widget** — the share
   of a window is whatever the window happens to be, and says more about the test than the widget.

   | widget | what the animation draws | before | after |
   | --- | --- | --- | --- |
   | `SplitPane` | the divider's hover, drag and focus | **6.2%**, 10 frames | already right |
   | `ScrollBar` | the bar's own fade and thumb width | **its own box**, 2.7% of the window | already right |
   | `TextArea` | the border, morphing 1 → 2 pt | 103%, 11 frames | 103% × 2 + **14.0% × 9** |
   | `CalendarView` | one focus ring on one cell | 104.5%, 11 frames | 104.5% × 2 + **2.5% × 9** |
   | `Chart` | the tooltip panel fading | **101%, 14 frames** | **open** |

   **Two were already right, and one of them is the pattern.** `SplitPane`'s three transitions
   belong to its `Divider`, an inner widget that *is* what moves, so a pointer arriving on it
   repaints a 26-point band. That is the first thing to reach for and it needs no new API.
   `ScrollBar` looks like a violation and is not: its fade and its thumb's widening cover the
   whole bar, so its own box is the right answer, and the measurement confirms it damages that and
   not the host around it. It is also the case that says why the harness matters — stretched to
   fill the test box it read 101% of a 420-wide "bar", which measures the harness. Laid out thin,
   the way one is used, it reads 15 points wide.

   **Two were narrowed** through `Transition.damages(Runnable)`, which exists for the case
   `SplitPane` does not have: no child widget to own the animation, because the moving part is
   drawn by the parent. `CalendarView` names the cell its roving ring is on. `TextArea` names four
   strips — a rim — because a focus fade morphs its border and leaves every line of text
   identical; the strips are as thick as the corner radius plus the stroke, which covers the
   rounded corners outright rather than by an argument about how far an arc bulges inward, and
   they stay four rectangles because the scene merges damage whose union wastes little and a rim's
   union is the whole widget.

   **The two frames that remain in both** are `Scene.setFocus` damaging the newly focused widget's
   box, doubled by the double-buffer union. That is correct and not worth removing: a scene cannot
   know that this widget's focus draws inside one row, and what *does* change wholesale on the
   focus flip — a caret appearing, a selection colouring — needs exactly that frame.

   **`Chart` is measured and deliberately not fixed**, which is a different thing from not
   noticed. Its tooltip fade repaints the whole chart for fourteen frames each way, and worse, the
   hover path invalidates the whole chart on **every pointer move** while a mark is hovered — its
   own comment says why: *"the panel follows the pointer even within one mark"*. The fix is not
   the mechanical one it looks like. The panel's rectangle is computed inside `paintTooltip` from
   text measurement and would have to be hoisted out so damage and paint agree; and hovering
   changes more than the panel — `BarChart` lifts the hovered category, `LineChart` draws a
   crosshair the full height of the plot and grows its markers, `CartesianChart` paints a band
   behind the category — so each subclass would have to declare the region its hover affects.
   Guessing that region short leaves stale pixels, which is the one failure mode of this mode that
   no assertion can see. It is worth doing and it is its own piece of work.

## 10. The escape hatch, and why it stays

`setPartialRendering(false)` remains, and remains public. An application that composites something
the toolkit does not know about — a video wall, a custom `frontPainter`, a backdrop-dependent
widget of its own before §3's predicate reaches its API — needs the blunt mode, and finding out
that it needs it should not require a fork. What changes is which way round the default sits.

## 11. Deliberately not in this step

- **Damage in the accessibility walk.** Publishing a tree is not painting; ADR 039 already
  separates them and nothing here touches that.
- **More than eight damage rectangles.** The cap is a tuning question and the evidence for changing
  it does not exist yet.
- **Sub-widget damage as a general facility.** §9.2 asks four widgets to damage what moved; it does
  not propose a framework for it. A widget that needs finer damage has `invalidate(x, y, w, h)`
  today.
- **Anything about the fade that made the flicker visible.** Eight frames of a 0.14-second
  transition is the right number of frames; what was wrong was what each one repainted.

## 12. Verification

Whatever this becomes, it is not verifiable by the headless suite alone: a stale pixel is
invisible to an assertion about a clip, and visible immediately to a person with the damage overlay
on. So the evidence has to be both — the clip assertions of §9.4, and a pass over the demo's scenes
with `setDamageDebug(true)` and the mode on, looking for anything that fails to wash magenta when
it changes. The scenes that matter most are the ones with a backdrop: `glass`, `kitchen-dialog`,
and the media transport over a video.
