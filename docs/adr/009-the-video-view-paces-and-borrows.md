# ADR 009: The view paces its own pictures, and it borrows the stream rather than owning it

- **Status:** Accepted, 2026-08-03. Implemented as phase 4 of the video player: `limn.components.VideoView`
  and the demo's Video tab, which now uses it.
- **Date:** 2026-08-03
- **Scope:** what the widget between a stream and the screen is responsible for: timing, the stream's
  lifetime, and the box.
- **Audience:** whoever writes the player (phase 5), a transport bar (phase 7), or a second view.
  Extends ADR 007, which decided what a picture looks like when it crosses the SPI, and ADR 008,
  which decided where the thing producing it lives.

---

## 1. Decision

1. **The view paces its own pictures with `VideoClock`**, free-running on the wall clock, holding at
   most one decoded picture. It therefore never names a successor and the clock never drops.
2. **The caller opens the stream and the caller closes it.** The view never calls `close()`: not on
   replacement, not on detach, not ever. It owns its device-side picture and any picture it is
   holding, and releases both.
3. **The view measures to the stream's own size at one point per pixel**, to a preferred size when
   one is set, and to nothing at all with no stream; the constraints then clamp, and the aspect ratio
   is restored by the letterbox rather than by the measure.
4. **The picture is uploaded from `onPaint`, once, by dropping the reference to it**, and drawn from
   the same call.

---

## 2. Why the view times pictures, when a player is a phase away

Without a policy, a view shows one picture per repaint and the playback rate is the *display's*: a
30-per-second stream runs at four times speed on a 120 Hz monitor and at half speed on a throttled
one. That is not a missing player, it is a wrong widget, and it is visible to anyone who opens the
Kitchen Sink.

`VideoClock` is pure arithmetic with no thread, no queue and no clock of its own, and its
single-candidate form is a handful of lines: decode one picture ahead, ask, hold or show. What phase
5 adds is a *master* for that clock and a decode thread behind the stream, and neither changes a
signature here: `clock()` is the seam, and it exists because installing an audio position on the
view's own clock is what slaving video to audio will mean.

The cost is stated rather than hidden: **the view cannot drop.** Dropping requires naming the
picture queued behind the candidate, which requires holding two, which is a queue, and a queue with
a policy for what to do when it is empty, when the pool is exhausted, and when a seek invalidates it.
That is a player. A view that has fallen behind therefore catches up by showing pictures as fast as
it is repainted rather than by skipping any, which is self-limiting, needs no bound of its own, and
degrades into "a moment of fast-forward" instead of "a moment of stutter".

A stream with no timing at all (`PTS_UNKNOWN`) is not paced: the clock refuses arithmetic on the
sentinel, so the view shows one picture per repaint and says so.

## 3. Why the view does not close the stream

A widget that closed the stream it was given would be right exactly once, for an application that
opens one stream, shows it in one view, and throws both away together. It would be wrong for
`setSource(a); setSource(b); setSource(a)`, wrong for the same stream in a main view and a thumbnail,
wrong for a stream the application wants to keep reading after the view is gone, and wrong for a view
that is detached and re-attached, which is one tab click.

The alternative that was considered and rejected is an ownership flag: `setClosesSource(true)`. It
is one boolean and one branch, and it makes the answer to "who closes this" a property of a widget
rather than of the code that opened it. The rule is worth more than the convenience: **whoever opened
it closes it**, and the view says so in the first paragraph of its documentation.

What the view *does* own is real and is released: the device-side surface goes to
`Scene.disposeLater` on detach, because freeing device memory needs the owning context and a detach
is not inside a frame, and any picture it is holding is released there too.

The corollary is that the view must expose the one repositioning `VideoStreamSource` itself defines.
`restart()` is that, and it is not a transport control smuggled in early: the view holds state the
caller cannot reach (the pacing anchor, the held picture, the ended and failed flags), so
`source.reset()` alone would leave the view out of step with the stream it is showing.

## 4. Why measuring does not preserve the aspect ratio

Every metadata accessor on a stream is final at open, which exists so that a view can be laid out
before a picture is decoded. So the size is answerable, and the only question is what to do when the
constraints cannot give it.

Preserving the ratio during measure (asking for 225 points of height when the width was clamped to
400) was rejected. A parent that stretches an axis has already decided that axis, and a measure that
argues with it produces a box that is neither what the parent asked for nor what the picture wants,
in a layout the widget cannot see. Clamping is the toolkit's ordinary answer and the letterbox is
where the ratio comes back, on the one pass that knows both the box and the picture.

**With no stream the view asks for nothing**, rather than for a plausible default box. A guessed
16:9 rectangle is a lie that survives until the stream opens and then jumps; `setPreferredSize` is
how an application that wants the box reserved says so, in the size it actually wants.

**The measured size follows the stream's declared size and the letterbox follows the picture's.**
They are the same number until a stream changes resolution mid-play, and then the difference is the
point: the box does not move, and the picture re-letterboxes inside it.

## 5. Why the upload is in the paint, and why dropping the reference is the guard

ADR 007 §7 makes one upload per surface per frame a contract, because the composite samples a
surface's texels when the batch draws rather than when the quad is queued.

A partial frame paints **one pass per damage rectangle**, so `onPaint` runs more than once in a
frame. Uploading there is nevertheless correct, and the guard is that the picture is released and its
reference dropped by the first pass: a later pass finds nothing to upload and queues a second quad
against the same texels. A new picture can only appear from the periodic callback, which runs once at
the top of a frame, before any damage is consumed.

The upload could instead have been done in that callback, which runs exactly once per frame with the
context current. It was not, for one reason: the first picture would then arrive a frame after the
first paint, and the repository's visual verification renders as few frames as it can. A view that
needs two frames to show anything is a view that screenshots blank.

## 6. What this deliberately is not

- **Not a player.** No decode thread, no audio, no queue, no seek. One picture is decoded on the UI
  thread per repaint at most, and the documentation says so rather than implying otherwise.
- **Not a transport bar.** `restart()` exists because ownership forced it; play, pause, scrub and a
  timeline are phase 7's. Pausing is reachable today only through `clock().setPaused`, which is the
  clock's contract and not a control.
- **Not a chroma-siting or colour control.** The picture is what the stream says it is; the view
  chooses a rectangle.
- **Not size-axis aware.** A decoded picture has no metric row. The two notices (no GPU backend, and
  a decode that threw) do read the row resolved on the widget, because they are chrome and every
  other component's chrome does.
