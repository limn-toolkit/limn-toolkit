# ADR 013: A seek moves the timeline, the soundtrack follows it, and the clock is told rather than left to infer

- **Status:** Accepted, 2026-08-04. Implemented as phase 7 of the video player: `VideoStreamSource.seek`,
  `AudioStreamSource.seek`, `Playback.seek`, `VideoClock.seekTo`, `MediaPlayer.seek`, `VideoView.seek`,
  and `seekVideo`/`seekAudio` in the shim.
- **Date:** 2026-08-04
- **Scope:** what "seek to t" promises about the next picture, what happens to sound that is already
  queued in a device, and who decides that a timeline moved rather than ran away.
- **Audience:** whoever writes a transport control, a second decoder that can seek, or a second
  audio engine. Extends ADR 009 (the view borrows and paces), ADR 010 (the player owns a thread and
  takes the audio) and ADR 012 (a container carries both tracks). **Unlike phase 6a, this phase
  edits phases 1 to 5**, because a seek cannot be bolted to the side of the thing it belongs in.

---

## 0. The measurement this rests on

`limn.sound` had no repositioning of any kind. `AudioStreamSource` could be rewound to its start and
nothing else; `Playback` could be stopped, paused, resumed and asked where it was. There was no way
to move a sounding track to a time, and (the half that is easy to miss) **no way to tell the engine
to throw away what it had already handed to the device.**

That second half is what decides this ADR. The shipped engine keeps a fixed number of buffers queued
on each streaming source and refills them from a service thread on a timer. A seek that repositions
only the decoder leaves every queued buffer to play first, so the sound continues from the old
position for the depth of the queue and then jumps. Measured against the shipped constants that is
around a tenth of a second of wrong audio per seek: inaudible as a click, unmistakable as a scrub
that lands late and then snaps.

So "seek the audio" is two operations that must happen in one: reposition the source, and discard
what the device is already holding. Anything that does only the first is a seek that sounds broken.

---

## 1. Decision

1. **`AudioStreamSource` gains a seek**, defaulted to unsupported, and `Playback` gains one that
   means *discard what is queued and resume from there*. The engine performs both as one operation.
2. **The engine rebases its reported position synchronously and re-primes asynchronously.** The
   position a seeked track reports is the seek target from the instant the call returns; the decode
   that refills the device happens on the service thread, because that is the only thread allowed to
   touch a stream's decoder.
3. **`VideoStreamSource.seek` takes a mode**, `KEYFRAME` or `EXACT`, because those are the two
   different costs a container has and a scrubber needs both.
4. **`VideoClock` is told that the timeline moved**, and is told *where* rather than being left to
   read it from the master. A seek is no longer counted as a jump, so a player no longer drops its
   master on the first scrub.
5. **A container dedupes the two tracks' seeks.** One demuxer position serves both, so a seek to the
   same target asked for by each track in turn is one `av_seek_frame` and no repeated audio.
6. **The transport bar is the demo's, not the toolkit's.**

---

## 2. How the soundtrack follows a seek: the three routes, and why the first

### The route taken: the SPI gains a seek, and the engine flushes

`AudioStreamSource.canSeek()`/`seek(long micros)` and `Playback.canSeek()`/`seek(long micros)`.
The engine's half is: stop the source, unqueue and delete every buffer it holds, rebase the played-
frame count to the target, and mark the stream for re-priming; the service thread then repositions
the decoder and refills.

**What it costs the engine.** A flag and a target on the per-stream state, one extra pass over the
live streams per service tick, and one guard: a stream that has just been flushed has an empty queue
and a stopped source, which is exactly the shape the service loop reaps as "fully drained". Without
that guard the first seek silently kills the track, which is the whole reason it is called out here
rather than left in the code to be discovered.

**What it costs an application that implemented `AudioStreamSource` itself.** Nothing. Both new
members have defaults: `canSeek()` answers false and `seek` throws, so an existing implementation
compiles unchanged and reports honestly that it cannot do this. An engine asked to seek a track that
cannot is a no-op that says so through `Playback.canSeek()`, not an exception on a service thread
nobody is catching on.

**What it costs the caller.** A seeked track is silent from the request until the service thread's
next pass, which is one service period plus one decode. That is a real gap and it is stated rather
than hidden: a scrub already sounds like a gap, and the alternative to the gap is playing audio from
the wrong place while the pictures are at the right one.

### The route rejected: re-open the track from the container at the new position

`playStream` transfers ownership and closes the source on every path, so a track that has started
cannot be repositioned by stopping it; it has to be re-opened, and only whoever opened the container
can do that. A player holds an `AudioStreamSource` it did not open and has no way to ask for
another.

Taking this route means `MediaPlayer.setAudio` stops taking a track and starts taking a *factory* (a
supplier the player calls again per seek), which changes the ownership rule ADR 010 settled, makes
every application that has one track write a factory that ignores its argument, and makes a seek
cost a new device source, a new admission against the engine's four streaming slots, and a fresh
prime. It is also strictly less general: it works only where a container exists, and the seek is
still not sample-accurate, because a re-opened track starts at whatever the container's audio index
lands on.

### The route rejected: seek the pictures only, and drop the master

Free, already implemented, and what looping does today. It is also not a video player: after the
first scrub the sound is permanently somewhere else, and because dropping a master is one-way by
design (ADR 010 §5) it stays that way for the rest of the session. A transport control whose first
use permanently desynchronises the file is worse than no transport control.

---

## 3. Why a mode on the video seek, and what each promises

A container seeks to a keyframe. Landing anywhere else means decoding forward from that keyframe and
throwing the pictures away, which is not a refinement of the same operation; it is a different cost
by up to a whole group of pictures.

Both are wanted, by the same widget, seconds apart. **Dragging a scrub bar wants the cheap one**:
one keyframe per drag update, which is what makes dragging feel immediate rather than pausing on
each pixel. **Letting go wants the exact one**, because the thumb is where the viewer says the video
should be. Expressing that as one operation with a per-call mode is smaller than two methods and
makes the cost visible at the call site, which a mode-less `seek` would hide.

- **`KEYFRAME`**: the next picture's presentation time is at or before the target, as close as the
  source can get without decoding pictures it will discard. A source whose every picture is
  independently decodable (a raw format, a generator) lands exactly, and says so by behaving that
  way rather than by advertising a precision it would have to keep true.
- **`EXACT`**: the next picture is the first whose presentation time is at or after the target.

`EXACT` overshoots by less than one picture interval rather than undershooting, because the rule
that undershoots ("the last picture at or before the target") requires the decoder to hold a picture
back to know it has gone far enough, and a held-back picture is a second lifetime to get right on
the one path where a mistake is a leaked pooled slot. Under a picture late is not visible on a scrub;
a leaked slot is a player that stops.

**A seek past the end is a legitimate way to reach the end**, not an error: the source positions
where it was asked and the next read reports the end. Clamping to the duration inside the SPI was
rejected because a source that does not know its duration cannot clamp, and one that clamps
sometimes is worse than one that never does.

## 4. Why the clock is told where the timeline went, rather than reading it

`VideoClock` already treats a large master move as a seek and drops the master; that is deliberate,
so a looping soundtrack does not hold every picture until it catches up. A real seek takes exactly
that path, which means that before this phase the first scrub cost the video its audio master
permanently. The clock could not tell "I was seeked" from "the master ran away" because nothing ever
told it.

`seekTo(long positionMicros)` is what tells it, and the argument is not decoration. The obvious
shape (re-read the master and re-anchor on whatever it says) is wrong here, because the audio
engine's reposition is not instantaneous from the caller's point of view: reading the master at the
moment of the request captures the *old* position, and the move to the new one is then scored as a
jump a few milliseconds later. So the clock takes the position it is being moved to as fact,
including as the master's new reading, and the caller is responsible for having moved the master
there. That responsibility is discharged by the engine rebasing its position synchronously, which is
why §1.2 is a decision and not an implementation note.

Between the request and the first picture after it, the position is the target exactly. It does not
free-run forward during the re-buffer: a transport whose thumb creeps while the video is still
finding the frame is reporting a position nothing is at.

## 5. Why the player hands the reposition to its decode thread

`VideoStreamSource` promises that reads, resets and the close are serialized on one thread. The
existing `restart()` honours that by stopping and joining the decode thread first: acceptable once,
and not acceptable per scrub.

So a seek is a *request*: the UI thread records the target, releases every picture in the ring, and
notifies; the decode thread performs the reposition at the top of its next pass, which is after
whatever read it was inside has returned. That answers the case that matters (a seek arriving while
the decode thread is blocked in a whole decode) without interrupting a decoder, which ADR 010 §4
already ruled out.

**A picture read before the seek must not reach the ring after it.** The decode thread reads a seek
epoch before the read and compares it after, and releases the picture instead of enqueueing it when
they differ. That is one integer and it is the difference between a scrub that stutters backwards
once per seek and one that does not.

**The ring is released by the thread that asks, before the reposition.** Every borrowed picture goes
back exactly once, in the same call that requested the seek, because a pool whose slots are all held
answers "not yet" forever and a seek that starved the pool would look like a seek that hung.

**A seek while paused hands over exactly one picture.** A paused clock holds everything by
definition, which is right for a pause and wrong for a scrub: the viewer dragging a paused video is
asking to see where they are. So the first picture after a seek is handed over even while paused,
and the hold resumes behind it.

## 6. Why one container's two tracks seek once

Both tracks of a container share one demuxer position, and after this phase both can be asked to
seek: the video by the player's decode thread, the audio by the engine's service thread, in either
order and without coordination. Two naive seeks to the same target would mean two repositions, and
the second would strand the packets the first had already handed to the other track: audible as a
fraction of a second of sound repeating after every scrub.

The shim therefore records where the container was last placed and which track has taken that
placement up. The first track to ask moves the container; the second finds its target already
current, flushes only its own decoder, and reads on. No cross-thread ordering is required, no lock
is held across both tracks, and either arrival order produces the same result. That is the property
worth having, because the arrival order is decided by an audio device's timer.

**The audio track then discards decoded samples up to the target**, so a seek is sample-accurate on
the audio side even though the container landed on a video keyframe before it. That is what makes
the sound and the picture arrive together rather than merely near each other.

## 7. Why the transport bar is the demo's

ADR 009 §6 says the view is deliberately not a transport bar, and nothing in this phase changed the
reason. A public component would be a size-axis participant, a theme consumer and a keyboard target,
and it would be the first component in this toolkit whose subject is another component: a thing to
be laid out beside a view, wired to a player, and kept in step with both.

It would also have to pick the scrub policy for every application: how often a drag re-seeks,
whether it seeks at all before release, whether it seeks at keyframes, whether it pauses while
dragging. Those are the choices that make a scrubber feel right or wrong, they differ per
application, and freezing them into a component means every application either accepts them or does
not use it.

So the Kitchen Sink builds one out of a slider, a button and a label, which is the argument: the
toolkit's job was to make a seek cheap enough and precise enough that a transport is a few dozen
lines of application code. If a second consumer appears, a component is a later decision with
evidence behind it rather than a guess with an ADR behind it.

**One thing the transport needed that was missing: a pause.** ADR 009 §6 left pausing reachable only
through the clock, on the grounds that it was the clock's contract and not a control. That was right
while nothing was controlling it. A transport needs one answer for both shapes (with a player the
player is paused, so the sound freezes with the picture; with a bare stream the view's own pacing
is), and it needs the case a clock cannot answer at all, a stream with no presentation times, which
never reaches the clock and would otherwise run straight through the pause. So `setPaused`/`isPaused`
join `restart`, `seek`, `isEnded` and `isLooping` as things the view resolves to the player when one
is driving it.

**One thing was missing from the slider and it was not a video concern.** A slider reported every
value the thumb passed through and never reported which one the user settled on, so anything
expensive bound to it ran once per pixel. That is a gap in the control rather than a need of this
phase, because a filter preview or a request has it too. So it was closed on the control, and the
transport is the first thing to use it.

## 8. What a seek costs, and what makes scrubbing usable

A keyframe seek is a container index lookup, two codec flushes and two queue clears, with no
decoding at all. An exact seek is that plus every picture between the keyframe and the target,
decoded and discarded, which for a typical file is a fraction of a second of work and for one with a
long group of pictures is more.

What makes dragging usable is therefore not the seek being fast; it is **not seeking per pixel**.
The demo's scrub bar seeks at a bounded rate while the thumb is moving, in `KEYFRAME` mode, and once
in `EXACT` mode when it is let go. A drag across the whole bar is a handful of seeks rather than
several hundred, and the last one is the one that decides where the video is.

## 9. Why rotation is carried, not applied

Every video a phone records is stored in its sensor's orientation with a display matrix beside it
saying how far to turn it. A decoder that ignores that is not slightly wrong: it plays a portrait
recording on its side, which is the single most visible defect a player can have on real files, and
it was one before this phase.

Three places could fix it and two are wrong. **Rotating the samples in the decoder** costs a full
copy of every picture and destroys the property ADR 007 was written for, that a picture crosses as
the planes the decoder already had. **Rotating on the device inside the surface** puts a display
concern into the upload path, where the same picture shown in two views would have to agree about
it.

So the angle is metadata: the source reports it, and the widget applies it as a transform when it
draws (free, since the quad is already going through one). The two consequences are stated where
they bite. A quarter turn **swaps the measured size**, because a portrait recording asks for a
portrait box, and a view that measured the stored size would letterbox a sideways picture inside a
landscape box and look exactly as wrong as not rotating at all. And the letterbox arithmetic
compares the *displayed* ratio, not the stored one.

A source that reports anything other than a quarter turn is refused rather than approximated: an
arbitrary display matrix can shear and flip, and a player that quietly rounded one to the nearest
right angle would show a picture that is subtly wrong with nothing anywhere saying so.

## 10. Why variable frame rate needs nothing new, and is asserted anyway

The clock has timed pictures by their own presentation times since phase 1, and the nominal rate a
source reports is documented as what to expect rather than what will arrive. So a variable-rate file
was already correct, but "already correct" is a claim, and the SPI's arithmetic has two places
where a rate could creep back in: a seek expressed in pictures rather than in time, and a duration
divided out of a rate. Neither exists, and a stream whose pictures are deliberately unevenly spaced
is in the suite so that neither can come back.

The one thing that genuinely needs saying is that **a seek target is a time and never a picture
index**. An index is only meaningful for a source with a fixed rate, which is the minority of real
files, and an API that took one would work on every test clip in this repository and on almost
nothing a user owns.

## 11. What this deliberately is not

- **Not a seek on the audio engine's clip voices' behalf.** A clip is decoded whole and already
  supports being repositioned; the engine implements the same two members there because a `Playback`
  that silently ignored a seek would be a hole in the interface, not because video needs it.
- **Not an end-of-seek notification.** A caller that wants to know the pictures have arrived watches
  the position or the picture, both of which it already has.
- **Not frame stepping.** Seeking to the next picture's presentation time is not a frame step, and a
  real one needs the decoder to say what the next presentation time is. Nothing here promises it.
- **Not a change to how a master is dropped.** A master that genuinely runs away is still dropped,
  one-way, for ADR 010 §5's reasons. What changed is only that a seek is no longer mistaken for one.
- **Not a flip or a shear.** The display matrix can express both; this carries a quarter-turn angle
  and refuses the rest, because a player that renders a mirrored recording unmirrored is wrong in a
  way nobody will attribute to the toolkit.
- **Not hardware decode, and not 10-bit.** Still phase 8.
