# ADR 010: The player owns a thread and a ring, borrows the video, and takes the audio

- **Status:** Accepted, 2026-08-04. Implemented as phase 5 of the video player: `limn.video.MediaPlayer`,
  `limn.video.AudioMasterClock`, the `Sounds.stream` overload for an already-open source, and
  `VideoView.setPlayer`.
- **Date:** 2026-08-04
- **Scope:** where decoding happens once it leaves the paint path, what times the pictures when
  there is sound, and who closes each of the two streams a player holds.
- **Audience:** whoever adds seek and a transport bar (phase 7), a second decoder (phase 6), or a
  second player. Extends ADR 009, which decided what a view is responsible for; the seam it named
  (`clock()` and `setClock`) is what this uses.

---

## 0. The measurement this rests on

A video slaved to an audio position is only as good as that position. The shipped engine does not
read one, it **composes** one: frames its service thread has unqueued, plus the device's offset into
what is still queued. Those two halves move at different moments (the service thread wakes on a
timer, the device advances continuously), so whether the sum ever goes backwards is a property of
the bookkeeping and not something to assume.

Measured over a stream sampled finer than a display refresh: **no backward step at all**. Unqueuing
a buffer adds exactly the frames it removes from the queue-relative offset, so the composition is
continuous by construction.

The position does not advance smoothly, though: it moves in steps of a mixer period, and **that
period is the device's rather than the engine's**. Measured on one machine, the same code stepped
about twice as coarsely through OpenAL Soft's null backend as through the audio hardware beside it,
one of those coarser than a 60 Hz refresh and the other finer. So a caller polling per frame will
sometimes see the same reading twice in a row and cannot know in advance whether it will. Nothing
may be built on a particular step size; what is built on is that an unchanged reading is neither a
stall nor a seek, which is why the clock's timers are dated from the last reading that *changed*.

Both halves are asserted rather than described (the monotonicity in the backend's suite against
whichever device the build opens, the coarse-step case in the clock's), so a regression in either is
a failing test rather than a picture that judders.

---

## 1. Decision

1. **`MediaPlayer` lives in `limn-toolkit`, in `limn.video`.** It takes a `VideoStreamSource` and an
   `AudioStreamSource` and knows no decoder, which the module graph enforces rather than a rule:
   the toolkit depends on nothing.
2. **One decode thread per player**, started at `start()` and **joined** at `close()`. Not a shared
   pool.
3. **The video stream is borrowed and the audio track is taken.** The player never closes the video
   stream; it owns the audio track until the engine takes it, and closes it if the engine never
   does.
4. **A master is dropped the moment the pictures cannot follow it** (when it stalls and the handle
   says it is no longer playing, and when it moves off their timeline), and the wall clock carries
   on from the position already reached.
5. **`VideoView` takes a stream or a player**, and everything that can have two answers has one: the
   player's.
6. **A screenshot run starts no audio device**, and therefore no player.

---

## 2. Why the toolkit, and why not a module of its own

A player needs `VideoStreamSource` and `AudioStreamSource`, and the toolkit publishes both. It must
*not* see a decoder, which is the constraint that rules out `limn-video`; and `limn-components` is
ruled out from the other side, because a player is not a widget and putting it there would make it
unreachable from anything that is not drawing.

That leaves the toolkit or a new module. A new module would have to depend on the toolkit and be
depended on by the demo, and would contain one class whose every type is already published one layer
down. The module boundary in ADR 008 exists to keep a *native payload* out of the base modules;
there is no payload here, so there is nothing for a boundary to hold back.

`limn.video` reaching into `limn.sound` is a new edge and it is one-way: sound knows nothing about
video, and nothing here makes it. The alternative (an interface in `limn.video` that some other
layer implements over a `Playback`) buys a decoupling nobody asked for and costs an application the
one line that is the whole point.

## 3. Why a thread each, when twelve thumbnails is the obvious counter-example

`readFrame` may block for a whole decode and the SPI promises no bound on it. A pool of any size
therefore has a failure mode that a thread each does not: one stream whose read never returns (a
network source, a stalled disk, a decoder waiting on a device) occupies a pool thread for as long as
it likes, and when enough of them do, the streams that were fine stop too. What a user sees is
thumbnails that never appear, with no error anywhere, and the cause is in a component none of them
belong to.

The cost of the answer is stated rather than hidden. **Twelve videos on a page are twelve threads**,
and the expensive part of that is the twelve concurrent decodes, not the twelve threads: a parked
platform thread is a stack reservation and a scheduler entry, and twelve 1080p decodes are twelve
cores' worth of work whatever runs them. A page that wants twelve *thumbnails* rather than twelve
*videos* wants twelve still pictures, which is one decode each and no player at all.

The seam that would allow a pool later is already there and is not spelled as one: a player can be
told it owns no thread, and its decode loop is one public non-blocking step. That is what the
deterministic tests drive, and it is what a host with its own scheduler would drive.

## 4. Why the two streams are owned differently, and why that is not an inconsistency

**Video is borrowed**, exactly as ADR 009 decided for the view, and for the same reasons: a stream
may outlive a player, be shown in two places, or be handed to a second player. Whoever opened it
closes it.

**Audio is taken**, because the audio engine takes it. `playStream` transfers ownership and closes
the source on every path including the failures: no device, a channel count it will not mix, a full
admission queue, a source with no frames in it. A player that also closed it would be closing it
twice, and the second close would land on a decoder a streaming thread was still reading. So the
player closes the track in exactly one case: when it still has it, because playback never started.

The facade had a gap here and it is now closed. `Sounds.stream` took only a `Path`, and a video's
audio track has no path: it is already open, because something else demultiplexed it. The overload
that takes an open source states the ownership on the member and honours it on the one path that
does not reach an engine at all, where the facade owes the close itself.

What makes the borrow safe is the join. **`close()` returns only once the decode thread has finished
the read it was in**, so closing the stream next is safe and closing it first is a decode against a
torn down decoder. The decode thread is deliberately *not* interrupted: `VideoStreamSource` promises
nothing about interruption, and a decoder woken out of a read could leave its input somewhere it
cannot describe. A stream whose read never returns therefore hangs the close, and that is a defect
in the stream: a timeout here would turn a promise into a guess, and the guess would be wrong
exactly when it mattered.

## 5. Why a master is dropped rather than followed

`VideoClock` already has the policy for a master that stops advancing: after the stall window the
wall clock takes over from the position reached. What it cannot decide is whether the master is
coming back, because that is a question about the *device*, not about the arithmetic. So the player
asks: once, when the clock says it has stalled, and never per picture, because reading a streaming
engine takes its monitor and a design that asks several times a frame pays for it several times a
frame.

The second case is a master that is not stalled but is somewhere else. A looping track reports
in-track time, so at each wrap its position falls back to near zero: a move of the whole track,
which the clock counts as a seek. Nothing in this phase can seek the pictures to meet it (that is
phase 7), and a clock left following it holds every picture until the track has played back up to
where the video already is, which reads as a frozen picture and is not one. Handing the timeline to
the wall clock is worse than being in time and better than every alternative available here.

Both are one-way. A track that has diverged does not come back onto the pictures' timeline by
itself, and re-installing a master on a hope would produce a second jump.

**A handle that never sounded is not treated as a master at all.** A machine with no audio device
yields one whose position is a constant zero, and following it costs a stall window of held pictures
before the wall clock takes over, on precisely the machines least able to afford a hitch. That case
is identifiable at admission, so it is identified, and the video plays at the right rate from the
first picture. Anything that *does* sound and then turns out to be inert is left to the stall
detector, because there is nothing to distinguish it from a device that died.

## 6. What looping costs when there is sound

The pictures' timeline restarts at a loop and the track's does not (or does, at its own length,
which is not the video's). One clock cannot be on two timelines, so **the master governs the first
pass only**: at the video's loop the player drops it and paces the rest on the wall clock. The
soundtrack keeps playing.

The alternatives were weighed and are worse. Refusing to loop with a track makes a widget's most
ordinary setting conditional on something it did not choose. Keeping the master produces the freeze
of §5 for the length of the track, or a burst of dropped pictures at the other wrap order. Rewinding
the track with the pictures is a seek, which does not exist yet and is the thing that would actually
fix this: phase 7 restarts both together, and this ADR is what it will be replacing.

## 7. Why the view takes either, and why the player wins every question

A view that only took a player would need one to show a single picture, which means a thread and a
ring to show a thumbnail, and would break the repository's visual verification: `--video-frame`
hands the view a stream holding exactly one picture, and a player in that path is a decode thread
deciding what a screenshot captures. A view that only took a stream would put the decode back on the
paint thread, which is the thing this phase exists to move.

So it takes either, and setting one clears the other, because two things pacing one picture is two
clocks disagreeing about when it is due. Everything with two possible answers resolves to the
player's: `clock()`, `isEnded()`, `failure()`, `isLooping()`, and `restart()`, which drives the
player rather than the stream behind it. `setClock` throws while a player is installed rather than
being quietly ignored, because a caller left holding a clock that paces nothing has no way to find
out.

The upload is unchanged and stays where ADR 009 put it: in `onPaint`, once, guarded by dropping the
reference. **A player hands over a picture; it does not upload one.** Uploading needs the context
current and the contract is one upload per surface per frame, and a decode thread has neither.

## 8. Why a screenshot starts no audio device

A player times its pictures by where the soundtrack has reached, so which picture a capture catches
depends on the device, the mixer period and the machine's load. That is the definition of a
non-reproducible screenshot, in the one mode this repository uses to verify anything visual.

The gate is the whole player and not merely its sound, because an unmastered player is still a
decode thread racing the first paint. Without one the video tab takes exactly the path phase 4
shipped, and `--video-frame` produces byte-identical captures for the reason it always did: the
stream it is handed contains one picture and there is no clock in the path at all. The cost is that
the player is not visually verified, which is correct: it has nothing to look at. It is verified by
its tests and exercised by every live run of the tab.

## 9. What this deliberately is not

- **Not seek.** `reset()` is still the only repositioning that exists, and `restart()` is still what
  exposes it. Every §6 and §7 compromise is one that seek would settle properly.
- **Not a transport bar.** The demo's controls are a demo's.
- **Not an end-of-audio notification.** There is none in `limn.sound` to consume, and the player
  needs none: it asks whether the track is still playing once, when the clock says it has stopped
  advancing.
- **Not a second admission policy.** Four streaming slots is the engine's number, and a fifth track
  is refused there. A player is told the same way a caller is (the handle is the null one), and it
  plays the video without sound rather than refusing to play it.
- **Not a change to `VideoClock`.** Every absence above is policy it already had. The one thing it
  still cannot express is `isPaused()`, which a view driven by a bare clock would use to stop asking
  for pictures; a player knows its own state, so nothing here needs it.
