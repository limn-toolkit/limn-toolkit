# ADR 025: A layout can be contained

- **Status:** Accepted, 2026-08-11. Implemented as `Widget.markNeedsContainedLayout()`, the
  contained pass in `Scene`, and `ListView.scrollBy`, which is the only caller so far.
- **Date:** 2026-08-11
- **Scope:** why a layout frame is a full repaint, why scrolling a list should not be one, what a
  container has to prove to be excused, and what it costs when the proof fails.
- **Audience:** whoever maintains the layout and damage model, and anyone tempted to reach for the
  new call from a widget that has not earned it.

---

## 0. The rule this carves out of

A scene damages the whole window on any frame that lays out. The reason is stated where the
decision is made and is not a convention:

> A layout pass can move any widget without it invalidating its old bounds: layout frames are
> always full, which is a structural invariant, not a convention for every layoutDirty producer
> to remember.

It is a good rule. A widget moved by its parent never hears about it, so it cannot damage the
rectangle it just vacated, and the only correct answer available to the scene is everything.

## 1. What it cost

Scrolling a virtualised list is the most common heavy interaction a desktop application has, and
it is a layout: which rows exist changes as the viewport moves, and mounting a row is `add()`,
recycling one is `remove()`, and both ask for a layout. So every wheel detent repainted the
window, doing exactly the work the damage machinery was built to avoid, and making a window that
also held a video or an animation pay the full composite per tick.

The cost was not hypothetical and is now a test: `ListScrollDamageTest` scrolls a list inset
inside a larger window and asserts the frame stays partial and the damage stays inside the list.
Before this change the same assertions failed on `cleared`, the signature of a full frame.

## 2. What a container has to prove

The rule assumes two things it cannot check. A contained layout is the case where both are
checked, by the scene, on every pass:

**The widget clips its children.** Then nothing the pass moved can paint outside the widget's own
box, so damaging that box is enough. A widget that does not clip is refused.

**Its own measured size comes out unchanged.** The scene re-measures it against the constraints
its parent last gave it (both are already cached on the widget, which is what makes this cheap)
and compares with the size the parent placed it at. If the size moved, the parent's layout is
stale and no amount of damage inside this widget fixes that, so it is refused.

Neither is a promise the caller makes. `markNeedsContainedLayout()` is a request, and the answer
is decided by the scene against the widget in front of it.

## 3. What happens when the proof fails

**It escalates to a full pass, in the same frame.** The contained requests are drained before the
full-damage decision precisely so that a refusal is visible to it. So the failure mode of asking
wrongly is a repaint that would have happened anyway, never a stale pixel, which is the only
outcome that would have made this trade a bad one.

Four things escalate: a widget that has left the scene, one that does not clip, one that has never
been measured, and one whose size moved.

## 4. The part that is not obvious

Mounting and recycling inside the pass goes through `add()` and `remove()`, which call
`markNeedsLayout()`, which sets the scene dirty. Left alone, the pass would schedule the very full
frame it exists to avoid, on the second frame rather than the first, which is worse than not
having tried.

So the scene holds the widget whose subtree is being laid out, and a layout request that
originates *inside* that subtree is recognised as work the pass in progress is already doing. A
request from anywhere else escalates as it always did. This is the one piece of state that makes
the whole thing work, and it is why the pass is on the scene rather than something a widget could
have arranged for itself.

## 5. What was considered and rejected

**Giving `ListView` a private fast path.** It cannot have one: the escalation test needs the
constraints the parent used, the damage needs the ancestor clip chain, and the suppression needs
to know what is being laid out. All three live on the scene.

**Making the whole layout model incremental.** A dirty-subtree layout with proper invalidation of
vacated bounds is the general answer and a much larger change. This is the narrow case where the
general machinery is not needed because the container's box does not move at all.

**Leaving it alone and documenting the cost.** Tempting, and it is what the finding that raised
this originally settled for on its first half. The measurement is what changed the answer: a full
window repaint per wheel detent is not a rounding error on a scene with a video in it.

## 6. What an author owes

Nothing, unless writing a container that virtualises its children. If you are: clip them, keep
your own measure independent of which of them are mounted, and call
`markNeedsContainedLayout()`. If your measure does depend on the mounted set, you will get a full
pass every time and the call is only a slower way to spell the old one.
