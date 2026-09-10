# ADR 043: A frame repaints what changed, and every exception is named

- **Status:** Proposed, 2026-09-10. Nothing here is implemented beyond the two prerequisites §3
  records as already landed. §9 says what has to be true before the flag is flipped, and §11 what
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
| focus into a field | 100.0% of the window per frame | 2.0% |
| focus to the next field | 100.0% | 2.0% |
| a day cursor step, popup open | 100.0% | 50.4% |

Measured over a nine-hundred-by-seven-hundred window holding two pickers and eight text fields,
counting the area of the clip the scene applies before anything is drawn. Three things fell out of
it, and only the first was expected.

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

**Not done, and this is the blocker: a widget that depends on pixels it does not own.** ADR 019 §6
records it exactly: a shape filled from its backdrop keeps showing the older backdrop if content
behind it changes while it does not itself invalidate. It does not bite over a video that repaints
every frame, and it does not bite over static content; it bites in between. The fix is named in
that record's §7 and has never been built: *a widget that declares itself backdrop-dependent, whose
rect joins the damage list whenever the damage intersects it*.

**The surface is one class.** `BackdropPanel` is the only widget in the toolkit that fills from
what is behind it; `BackdropEffect` and `Canvas.fillBackdropRoundRect` are the plumbing under it.
So §7.3 is not a research project, it is a predicate and a loop:

```java
protected boolean paintsFromBackdrop() { return false; }   // Widget
```

and, where the scene composes the frame's damage, one pass that adds the scene-space rect of every
backdrop-dependent widget whose rect intersects the damage already collected. Bounded by the number
of such widgets, which is the number an application chose to put on screen, and zero in almost
every application.

## 4. What every widget owes under this default

One sentence, and it is already the contract: **declare, in `paintOutset()`, everything you paint
outside your own box, and call `invalidate()` for everything you change.**

The second half is where the interesting failures are, and this work produced one. Measured above:
a day cursor step in an open calendar repaints **50.4%** of the window with the flag on, because
`CalendarView` invalidates itself whole when the cursor moves one cell. Nothing is wrong with the
picture — it is correct and wasteful, which is the failure mode this mode has. Under the current
default it costs nothing, because the frame was full anyway; under the proposed one it is the
difference between a cell and half a screen. That is the shape of the migration work, and §9 puts
it before the flip rather than after.

## 5. What stays full-frame, for ever

Not everything should be partial, and the paths that are not are not a backlog:

- **A layout pass.** A widget that moved cannot damage where it used to be.
- **An overlay pushed or removed.** A whole layer appeared or vanished, and the layout is dirty
  anyway.
- **A front painter, a palette change, a size-step change** — anything that changes what every
  widget draws rather than what one of them draws. ADR 002 says it in its own words about the size
  axis: *"A step change produces exactly one full frame, which is the only correct answer."*
- **The first frame**, and the frame after the flag itself is toggled.

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

1. **§3's backdrop predicate**, or the mode is wrong for every application using `BackdropPanel`
   and the glass palettes.
2. **`CalendarView` damages cells rather than itself** (§4), and the same question asked of the
   other widgets whose cursors move inside a large box: `ListView`, `Table`, `PopupMenu`'s panel,
   `TabbedPane`'s strip. Each is a measurement, not an opinion.
3. **A demo scene running with the mode on**, in the gallery capture, so that every future capture
   exercises it. The captures are the only pass over the whole widget set that happens on every
   build.
4. **A clip-asserting test per interactive widget**, or at least per widget with a moving cursor;
   the shape is in `PartialRenderingTest` already.

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
