# Video

What a contributor needs before changing the video path, and what the Javadoc deliberately leaves
out because a user of the artifact does not need it. The decisions are in ADRs 007 to 017; this is
the shape of the thing and the ways it goes wrong.

## The layers, and which way they may see each other

```
limn.video (limn-toolkit)     the vocabulary: a frame, a source, a decoder facade, two clocks,
                              a player, and the surface SPI. Depends on nothing.
limn.components.VideoView     a widget that borrows one of those and draws it.
limn-video                    pure-Java decoders. No native, no third-party dependency.
limn-video-ffmpeg             the only native in the repository. Depends on limn-toolkit alone.
limn-backend-lwjgl            the device side of the surface SPI, and the audio engine.
```

Two edges must stay absent and neither is enforced by `checkArchitecture`, which greps import lines
and can therefore see a forbidden *package* and never a forbidden *dependency*: `limn-components`
must not gain an edge to either video module, and `limn-video` must not gain one to
`limn-video-ffmpeg`. `settings.gradle.kts` and the module build files are where that is written
down, and they are the only place it exists.

## The four lifetimes, which is where most defects live

**A picture is borrowed, and the producer owns the memory.** `readFrame()` fills a pooled slot and
`frame()` lends it; `release()` hands the slot back, exactly once, from any thread. Java holds a
slot index and never an address: for the FFmpeg decoder the release crosses the boundary as an
integer and the `av_frame_unref` happens on the far side.

The three ways to get this wrong, all of which are quiet:

- **Never released.** The pool loses a slot. Nothing throws; reads start answering `PENDING`, and
  once every slot is out the stream appears to stop. Symptom: a picture that freezes while the
  decoder is demonstrably still running.
- **Released twice.** In tests the pooled recycler throws; against a real decoder it is a double
  `av_frame_unref` and therefore a crash somewhere else entirely, later.
- **Held across a `close()`.** The storage is gone and the frame is a window onto it. Copy before
  closing.

Every consumer on the path releases where the picture leaves it: the view in the paint that
uploaded it, or (for one it read ahead and the clock then dropped) where the drop happens, since
no paint will ever see that one. The player releases when a picture is dropped, shown, drained or
seeked past.

**A hardware picture is a handle, and releasing it hands a buffer back to a decoder that is about to
refill it.** This is the fourth lifetime and it is the one with no counterpart anywhere else in the
toolkit. A picture from a software decoder is samples the surface *copies*; a picture from
VideoToolbox is an IOSurface the surface *binds*, so the conversion reads the decoder's own memory,
and `glDrawArrays` only queues that read. The gap between queueing it and the device performing it
is where the whole problem lives:

```
upload(frame)   binds the IOSurface, queues the conversion         nothing has been read yet
frame.release() the slot is free; the decoder may write into it    the read is still queued
                → the conversion samples the NEXT picture
```

and the corruption surfaces frames later, possibly in another window, nowhere near the release.

**The rule is: drain the device before returning from `upload`, and do not hold the picture longer.**
Both would work and the choice was deliberate. Holding the frame until the batch that used it has
been drawn keeps a decoder's buffer pinned for a whole frame; on unified memory that is one slot of
a small pool, and on a discrete GPU it is VRAM the decoder sized its pool on the assumption of
getting back. Draining costs one fence per hardware picture and pins nothing. ADR 014 §7's
measurement was taken on unified memory, which is the case that would have made holding look
harmless, and the decision was made against the hardware it was *not* taken on.

The consequence for the layer above is that **nothing changed there**: the view still releases in
the paint that uploaded, because by then the device has finished. That is the property the drain
buys, and it is why the rule is stated on `VideoSurface.upload` rather than left to each caller.

Two failure modes to recognise, since neither throws:

- **Released before the drain.** A picture that is a frame or two stale, intermittently, worse under
  load, and correct every time you stop to look at it (because a readback or a breakpoint
  synchronises the device and hides it). The test that pins it primes the command queue first, so
  that "has the conversion run yet" is not a coin toss.
- **Never drained because the frame is planar.** Not a failure: an uploaded picture is a copy and
  needs no fence. The branch is on `VideoFrame.kind()`, and putting the fence on both paths would
  cost a synchronisation per picture on the path that has been correct since phase 2.

**The video stream is borrowed and the audio track is taken.** Whoever opened the stream closes it,
after the player is closed. The player's `close()` returns only once its decode thread has finished
the read it was inside, which is what makes closing next safe. The audio track is the opposite,
because handing a source to the audio engine transfers it on every path including the failures.

**A container is closed by its video track.** Closing `FfmpegMedia.video()` closes the container;
closing `audio()` only tells the demultiplexer nobody is reading that track. A call arriving after
the container is closed is answered rather than punished, because the engine's streaming thread can
genuinely be inside a read when an application closes what a player has finished with.

**A soundtrack is one of several, and the one that is open is a generation.** A container lists
every audio stream it holds and decodes exactly one (`av_find_best_stream`'s answer by default,
or whichever `audio(int)` was told to take). `audio(int)` is not a getter: it transfers the source it
returns, the same way `audio()` does, so the second call while the first source is still with the
engine is the case the design is about. What it does is *end* the old track rather than invalidate
it: every audio entry point carries the generation its caller was handed, and a call from a
superseded one reads as 0 frames, which is what the end of a track means to the engine, so the
engine stops and closes it on its own.

Three ways that goes wrong, and none of them throws:

- **A stale release unclaims the live track.** The engine closes every source it is given, so a
  superseded track's close arrives *after* the replacement. A release that ignored the generation
  would tell the demultiplexer nobody is reading the track somebody is listening to. The reader
  that is alone still gets its packets (the track it asks for is pulled for it directly), so the
  symptom is not silence in a test; it is sound that disappears in proportion to how far ahead the
  *pictures* are, which in a player is all of it. A test that does not run the video past the switch
  passes with the guard removed.
- **The demux filter follows the media type instead of the selection.** Then a film's other
  languages are queued rather than freed, and memory grows for the length of the film. The filter is
  on the selected stream index, and every other audio stream lands in the branch that frees.
- **A track change leaves the shared seek placement standing.** `placeDemuxer` lets one container
  position serve two consumers, and what makes that safe for the one that has not asked yet is that
  its packets from there were queued while the other read forward. A newly selected track has no
  such queue, since it was not the selected one while the pictures ran past. So a selection marks the
  audio side as having consumed the placement, and the new track's next seek really moves the
  container. Without it, a language switch followed by a seek plays from wherever the demultiplexer
  had reached.

Only one track is decoded at a time, and that is a decision: N open decoders are N codec contexts,
N queues and N positions to keep coherent across a seek, for samples nobody reads; the audio engine
mixes one source per player.

## Subtitles, and the one direction that would have been ruinous

**A cue is asked for by position and never read like a picture.** `SubtitleCues.activeAt(t)` answers
what is on screen now; the shim decodes and the Java window remembers. The shape is different from
the two tracks above because the data is: a picture is megabytes borrowed from a pool and presented
at an instant, and a cue is a short immutable string that occupies an interval.

**The subtitle side never demultiplexes, and everything else about it follows from that.** Video and
audio each run the demux loop when their own queue is empty. A subtitle track is silent between
lines, so a subtitle side that did the same would answer a question about *now* by reading forward
through the whole gap: minutes of a film, queueing or dropping every packet on the way. So the
demux loop queues subtitle packets it meets, exactly as it does for audio, and the read drains only
what is there. Two consequences, both of which look like bugs if you do not know them:

- **A container whose video nobody reads produces no cues**, however long it is polled.
- **A track selected after something has already read forward has missed what it read past.** An
  unselected track's packets are freed as they are met and the demuxer does not go back for them.
  In the demo this was the difference between a `--video-frame` capture with a subtitle in it and
  one without, because `FrozenSource` decodes to its picture inside its own constructor. Select
  first, read second.

**The epoch is what makes a scrub safe.** `placeDemuxer` (the only place the container really moves)
clears the subtitle queue, bumps `subtitleEpoch` and records that the decoder owes a flush. Every
read reports the epoch, including the reads that produce nothing, because a seek has to reach the
window whether or not a cue follows it. The flush is deferred through a flag rather than done there
for a lock-order reason: `placeDemuxer` runs holding the demuxer and may not take another lock. The
subtitle side is the fourth mutex and its rule is the others': it may take the demuxer, and the
demuxer is never held while taking it.

One artefact cannot be removed and is documented rather than worked around: a cue that *straddles*
the seek target is recovered only if its packet lies after the point the container landed on.

**Everything a text subtitle decoder produces is ASS, whatever the file was**: all four go through
`ff_ass_add_rect`, so `rect->ass` is `readorder,layer,Style,Name,ML,MR,MV,Effect,Text` and
`rect->text` is empty. The shim strips it to plain text on the far side of the boundary and the raw
line is never published, because an application that receives markup it cannot interpret draws it.
`\N` becomes a line break; `\n` and `\h` become a space. ADR 017 §3 has what that loses.

**A bitmap track is listed and refused by name.** `text()` comes from the codec descriptor and not
from the linked decoder list, so the refusal still names the real reason in a build someone compiled
`pgssub` into. A track that opens and shows nothing is the outcome that was being avoided.

Nothing is selected when a container opens, whatever the file marks as default: whether a viewer
wants subtitles is not a fact about the file.

## Threads

| Thread | What it may touch |
| --- | --- |
| UI | every widget, the clock, `MediaPlayer` except `decodeStep`, `VideoSurface.upload` |
| Whichever thread opens | `VideoDecoder.supports` / `openStream`, `FfmpegMedia.open`, and the source that open produced, which nothing else has seen yet |
| A player's decode thread | `readFrame`, `seek`, `reset` on the video stream; nothing else |
| The audio engine's service thread | `readFrames`, `seek`, `reset`, `close` on the audio track |
| Whichever thread paints | `FfmpegMedia.subtitles()` and everything on the `SubtitleCues` it returns |
| Any | `VideoFrame.release` and `VideoFrame.toPlanar`, and the player's volatile status reads |

**The open is a thread and not a phase, which is why it has its own row.** `Videos.open` runs on
whoever called it: the UI thread in setup code, and a freeze there for the length of the open, which
on a real container is a header read, a probe that decodes packets, an index, a decoder, and on the
first call a native library extraction. `Videos.openAsync` runs the same probe-and-open on a worker
and delivers the source on the UI thread; `VideoDecoder.openStream(Path, Progress)` is the overload
it calls, and the `Progress` is the only cancellation there is, because nothing interrupts a decoder
sitting in a native read. `canOpen` stays on the caller's thread and stays cheap, because a control
deciding whether to enable itself has to be answered inside a frame. So the one first-call cost it
cannot promise away, a decoder linking its native library, is moved by `Videos.warmUpAsync()`, which
an application starts once after installing its decoders.

That fits the ownership rule below rather than bending it. A source that has just been opened has no
owner: it was produced on one thread, no other has touched it, and no decode thread exists for it
yet. Handing it over is therefore free, and it happens exactly once (on the UI thread, in
`onSuccess`), after which the rule takes effect and the stream belongs to the decode thread from
`start()` to `close()`. The handover the async open needs is the one the job already provides: the
result crosses to the UI thread and is published there, so the thread that opened the container never
touches it again.

The path with no owner at all is the one to keep in mind: a job cancelled, or a `deliverIf` that
answers false, while the container was being opened. The source exists and nobody received it, so
`openAsync` attaches an `onDiscarded` that closes it, on a worker, which is legal precisely because
nothing else has ever seen it, and necessary because a close can block.

**Selecting an audio track is a fourth caller and not a fifth thread.** It is whichever thread
holds the container (the UI thread in an application), and it runs while the engine's service
thread may be inside a refill. Nothing in the table above changes: the selection takes the same
audio lock a read takes, so it waits for the read in flight, and the read that comes after it finds
a generation that is no longer its own.

The rule that is easy to break: **the stream belongs to the decode thread from `start()` to
`close()`**. That is why a seek is a request the decode thread picks up rather than a call the UI
thread makes, and why `restart()` (which does call the stream directly) stops and joins first.

Uploading is the UI thread's and is once per surface per frame, because the composite samples a
surface's texels when the batch draws rather than when the quad is queued. A partial frame paints
one pass per damage rectangle, so `onPaint` runs more than once per frame; the guard is that the
first pass releases the picture and drops the reference, and a later pass finds nothing to upload.

## The clock, and the three things that look like each other

`VideoClock` answers one question (show this picture, hold it, or throw it away) from the
presentation time, an optional master position and a wall clock. It has no thread and no state
beyond a few longs.

Three situations produce a master position that is not what it appears to be, and telling them
apart is the whole of the policy:

- **A coarse device.** The engine reports in steps of its own mixer period, which on one machine
  was measurably twice as coarse through the null backend as through the hardware beside it. A
  caller polling per display frame sees the same reading twice. Nothing may be built on a step size;
  the stall and jump timers are dated from the last reading that *changed* for exactly this reason.
- **A device that stopped.** After `MASTER_STALL_MICROS` of an unchanged reading the clock declares
  the master stalled and drives the timeline from the wall clock. The player then asks the handle
  once (not per picture, because reading a streaming engine takes its monitor) whether it is still
  playing, and drops it if not.
- **A position that moved on its own.** A looping track wraps to near zero, which is a move of the
  whole track. The clock counts it as a jump and the player drops the master, one way.

A seek is the fourth and it must not be read as the third. `seekTo` is what says so, and it takes
the new position rather than polling the master, because an audio engine repositions
asynchronously, so polling at the moment of the request captures the position being left and the
move to the new one is scored as a jump a few milliseconds later. The engine therefore rebases the
position it reports *synchronously* and refills the device afterwards; those two halves being on
opposite sides of the call is deliberate and is the load-bearing part.

## Seeking, end to end

```
MediaPlayer.seek(t, mode)         UI: release the ring, record the request, move the clock,
                                      tell the soundtrack
  → decode thread, next pass      video.seek(t, mode)
  → readFrame                     the exact mode's discarding happens HERE, not in seek
  → Playback.seek(t)              engine: stop, unqueue, rebase the position   [synchronous]
  → the engine's service thread   decoder.seek(t), re-prime                    [one period later]
```

Things that follow from that shape and are not obvious:

- **A seek is cheap and the next read is not.** An exact seek arms a discard threshold that
  `readVideo` applies as it decodes; timing `seek()` measures the wrong call.
- **A picture read before the seek must not reach the ring after it.** The decode thread compares a
  seek epoch across the read and releases the picture instead of enqueueing it.
- **The ring is released by the thread that asks, before the reposition.** A pool with every slot
  held answers `PENDING` forever, so a seek that starved it would look like a seek that hung.
- **One container, two tracks, one demuxer position.** Both tracks are asked to seek, by two threads
  in an order nobody controls. The shim records where the container was placed and which track has
  taken it up, so the second finds its target current and flushes only its own decoder. Two real
  seeks would strand the packets the first had queued for the other track: heard as a fraction of a
  second of sound repeating after every scrub. A track *change* is the exception and is handled by
  the selection, above.
- **The audio side then discards decoded samples up to the target**, entering the frame that
  straddles it at a sample offset. Without that the sound starts wherever the video's keyframe
  structure happened to land.
- **A seek while paused hands over one picture** and then holds again. A paused clock holds
  everything, which is right for a pause and wrong for a scrub.

### Scrubbing

The cost that matters is not the seek, it is the number of them: a slider fires per pixel. A drag
across a bar is several hundred updates and a decode per update is unusable however fast one decode
is. So a transport seeks at a bounded rate in `KEYFRAME` while the thumb moves and once in `EXACT`
when it is let go. Any transport has to make that choice, and for a long time that argument kept a
transport out of the component set; it was re-taken the other way once the policy proved stable in
the demo. `MediaControls` now owns it — play/pause, the scrub policy and the clock, over any
`VideoView` (`setControlsVisible` on the view, or compose the bar yourself). What still has no
component is everything the toolkit cannot choose: volume (an application's audio wiring), audio
tracks and subtitles (an application's container) — the bar takes them as injected widgets through
its slots instead. The bar also owns the direction decision: it reads left to right in either
direction by default, as a declaration the application can re-declare or clear
(see direction-axis.md).

### Pausing, and the widget that undid it

A pause has more than one author. The application pauses when a tab is hidden, the viewer pauses
with a button, and a player can be paused directly by code that holds it. They all reach the same
state, and nothing in that state records who caused it.

That is a trap with a specific shape, and this repository has already fallen into it: a widget that
paused a player when the picture left the screen also resumed it every frame the picture was on
screen, so the transport's own Pause button lasted less than one frame and looked dead. **A
component that pauses something on a condition may only resume what it paused itself**, which means
carrying a flag saying so; "is it paused?" is not the same question as "did I pause it?".

The view is where the several answers are reconciled, the same way it reconciles the ended and
failed flags: with a player it pauses the player, so the sound freezes with the picture, and without
one it pauses its own pacing. The half that is easy to leave out is the stream carrying no
presentation times at all: such a stream never reaches the clock, so a pause implemented purely as
`clock.setPaused` does nothing to it, and it goes on showing a picture per repaint through the
pause.

## Hardware decode, and the two shapes a picture now has

**A picture is either planar samples a consumer can read or an opaque handle it cannot**, and
`VideoFrame.kind()` says which. That is the whole of the SPI change, and everything awkward about it
follows from one fact: a hardware decoder's picture lives in memory the CPU may not be able to
address, in a layout it does not choose, owned by a pool it does not control.

`plane(int)` **throws** on a handle. It does not return an empty buffer or the planes the slot held
last, because those would be a wrong picture on the screen instead of an exception on the one line
that can explain it. What a consumer does instead depends on what it is:

- **The GL backend binds it.** `CGLTexImageIOSurface2D` makes a texture *be* the decoder's memory,
  and nothing crosses the bus at all.
- **Everything else asks for it back**, with `toPlanar()`. That is a whole picture copied out of the
  decoder's memory (3.1 MB at 1080p, 12.4 MB at 4K), so it is a method a consumer calls rather than
  something that happens to it. `YuvConverter`, `Y4mWriter` and every test are on this side.

Three things about it go wrong quietly, and all three are on the producer's side.

**An accelerator says whether it will take a stream only when it meets a picture.** `get_format`
runs on the first decode and not on `avcodec_open2`, and an accelerator that then declines makes
libavcodec fall back to software in silence. So a shim that reported what it *asked for* would
promise NV12 and deliver I420, and every read afterwards would be refused with a message about the
stream changing format when nothing changed. The shim therefore decodes one picture at open, checks
what came back and re-opens without the device if it has to. `isHardwareDecoding()` is the answer;
`Hardware.PREFER` is only the question. This is not hypothetical: Apple Silicon's VideoToolbox has
no MPEG-4 Part 2 decoder, so that path runs on any Mac of that generation the moment a test asks for
one.

**The layout is the accelerator's and not the container's.** An MPEG-4 or H.264 file codes planar
4:2:0 and VideoToolbox hands back NV12; a 10-bit one codes `I420_10LE` and VideoToolbox hands back
P010. So the `PixelFormat` a stream reports is decided *after* the accelerator has been confirmed,
and every decoded surface is checked against it by pixel-format type; a decoder that produced
something else is refused rather than reinterpreted.

**P010's codes are left-justified and nothing about its geometry says so.** It is NV12's plane
count, NV12's subsampling and NV12's strides doubled; the difference is that the ten bits live in
the *top* ten of the sixteen-bit word. Read as an ordinary 10-bit layout it produces a picture 64
times too bright with every dimension, stride and plane count correct. The shift is carried on
`PixelFormat.codeShift()` and applied by `componentAt`/`putComponent`, so a consumer that reads
samples through the layout works in codes and never meets it; the device sampler is the one consumer
that does not, and its scale is the storage maximum divided by 64, which **fits the uniform that
was already there**, so no compile error and no size mismatch guards it either. The tests are the
guard. There is nothing else.

## The pure-Java decoders

`Y4mSource` walks to a seek target rather than computing its position. A `FRAME` line may carry
parameters, so the pictures are **not** at a fixed pitch and the arithmetic that would reach one in
a single move lands on rubbish for the one file that uses that feature. The walk reads a line and
moves the channel per picture, which transfers nothing. It is also why `durationMicros()` is
unknown: counting pictures means reading the whole input.

`SyntheticSource` is a pure function of the picture index, so a seek is arithmetic and a stream is
reproducible from its spec alone. Both invert a *truncating* division to turn a time back into an
index, which is subtle enough to live in one place (`FrameIndex`) rather than in two.

Neither carries audio, so the player's no-master path (which is the common case, not the corner) is
what they exercise.

## The native, and what is normal about it missing

Gradle never compiles C. The library comes from `scripts/build-ffmpeg.sh`, is not committed, and is
absent on any machine that has not run it. Everything is written so that absence is ordinary:
`VideoDecoder.supports` answers false, the tests skip the way the GL-backed tests skip, and the demo
shows a sentence in the picture's own box rather than an empty rectangle.

Three things about the loader are worth knowing before touching it. The extraction directory is
named after a digest of the libraries' own bytes, because naming it after a version would load
yesterday's library after a rebuild without a version bump, which is the bug that is hardest to
believe while looking at it. The architecture asked about is always the JVM's, never the machine's:
an x86_64 JDK under Rosetta on Apple Silicon has `uname -m` saying `arm64` and only `os.arch` naming
a library it can load.

And that digest, being taken over bytes that ship in the application, names a path any other account
on the machine can work out offline. So the extraction never happens directly under
`java.io.tmpdir`, which on most Unix systems is a `/tmp` everyone can write to. It happens one level
down, in a per-user directory created `rwx------` and checked, before anything is loaded from it,
for being a real directory this user owns that nobody else can write into. The whole "one extraction
wins the rename, the loser uses the winner's copy" argument depends on it: it is sound between two
of this user's processes and is trusting a stranger's files anywhere else.

The shim locks with three mutexes (the demuxer, the video side, the audio side) because four
threads reach a handle. The order is the entire rule: video or audio may take the demuxer, the
demuxer is never held while taking either, and the two are never held at once.

## Depth and colour: the four numbers that move together, and the one that does not

A sample is a **code**, and almost every quiet failure on this path is a code read in the wrong
space. Four things change with the bit depth and one deliberately does not.

| Moves with the depth | Value at 8 / 10 bits |
| --- | --- |
| Studio black | 16 / 64 (`16 << (n-8)`) |
| Chroma neutral | 128 / 512 (`1 << (n-1)`) |
| Studio luma gain | 255/219 / **1023/876** |
| Bytes per component | 1 / 2, little-endian, right-justified (**except P010, which is left-justified**) |
| **The matrix's luma weights** | **the same: kr and kb are the recommendation's and have no depth** |

The third row is the one that catches people. Studio levels are *defined* as `level << (n-8)` while
the output span is `(1 << n) - 1`, and four times 255 is 1020 rather than 1023. So the ten-bit gain
is **not** the eight-bit gain, and multiplying the eight-bit table by four is a wrong answer that is
wrong by three parts in a thousand: invisible by eye and fatal to any exact comparison.

The consequence for the code is that `VideoColor`'s coefficient accessors take a bit depth and there
is no no-argument form. That is not a style preference: a ten-bit picture decoded through the
eight-bit table is four times too bright with black four times too high, and nothing anywhere says
so. The argument exists so the mistake is a compile error.

Two more places the depth is easy to lose, both of which produce a plausible picture rather than an
obvious one:

- **Components decide a channel count; bytes decide a type.** They agreed while a sample was either
  one byte or two channels. A ten-bit luma plane is two bytes and one channel, so anything deriving
  a texture format from `bytesPerSample` is now wrong for it and right for NV12.
- **A row length is in samples and a stride is in bytes.** They differ by two for every ten-bit
  plane, and a stride that is not a whole number of samples cannot be expressed as a row length at
  all; such a plane is staged tight, the same branch NV12 already used, reached for a second
  reason.

**A transfer function is a different question from a depth, and routing by the wrong one is the
trap.** A ten-bit BT.709 file is ordinary display-referred video; a BT.2020 PQ one is not viewable
until its curve is inverted. So the depth decides the *textures* (`R16` planes and an `RGB10_A2`
conversion target) and `VideoColor.Transfer` decides the *path*. Nothing here inverts a curve, so a
picture that is not display-referred is refused twice: by `FfmpegMedia.open`, and again by the
surface. Refusing at open is the same idiom rotation uses for a display matrix that is not a quarter
turn.

The reason the conversion target follows the depth at all is worth stating, because the version
without it looks finished: widen the planes, widen the matrix, keep an RGBA8 target, and the output
is bit-identical to decoding at eight bits, because the last thing the conversion does is quantize
into the target. Everything upstream would be correct and pointless.

## What is in the native, and what a test can say about it

It also decodes four text subtitle formats (`mov_text` out of an MP4 and `subrip`, `ass` and
`webvtt` out of a Matroska or WebM) for **896 bytes, all four together**, which is three orders of
magnitude below anything else in ADR 015 §2's table. That number is small enough to look like a
configure flag that was silently dropped, so it was checked against the linked library rather than
against the build script, which is what §2 of that ADR asks for anyway.

The shipped build decodes h264, hevc, vp9, vp8, aac, opus and vorbis out of MP4 and Matroska/WebM,
and every one of those was measured on its own before it was added (ADR 015 §2). It also carries two
hardware accelerators, `h264_videotoolbox` and `hevc_videotoolbox`, for about a thirtieth of a
megabyte; VideoToolbox is a system framework, so what is linked is Apple's.

**`--enable-videotoolbox` on its own switches on no accelerator at all**, only the framework;
`--disable-everything` had turned every hwaccel off and only `--enable-hwaccel=<name>` turns one back
on. Such a build attaches a device, decodes in software and looks correct from the outside: the
same shape of trap as the Opus one below, met a second time and caught the same way, by asserting
what the *linked library* holds rather than what the configure line said.

**Of those, this repository can encode exactly none.** FFmpeg's H.264 encoder is x264 and is GPL;
there is no native HEVC, VP9 or AV1 encoder at all. So the round trip that proves the MPEG-4 path
cannot be written for a single codec phase 8 added, and the evidence comes in three tiers instead:
the round trip over the path this repository owns, linkage read out of the linked library, and real
files behind `-Dlimn.video.test.clips`. Each is written down with what it does not cover.

The second tier earned its place the day it was written: `--enable-decoder=opus` was in the configure
line and Opus was **not** in the library, because FFmpeg had disabled it for a missing dependency
(`swresample`) and said so only in a warning line inside a build log. A configure flag is a claim; a
linked symbol is a fact.

**AV1 is absent and it is not an oversight.** FFmpeg's own `av1` decoder is a hardware-accelerator
wrapper that refuses outright when no accelerator is attached, and `--disable-everything` leaves
none. Software AV1 means libdav1d: a second external library with a meson toolchain and a cross
build per architecture. Adding the flag would produce a build that advertises AV1 and fails on the
first file.

## Rotation

Carried as metadata and applied by the widget as a transform on the quad it was already drawing.
Two consequences bite:

- **A quarter turn swaps the measured size.** A view that measured the stored size would letterbox a
  sideways picture inside a landscape box, which looks exactly as wrong as not rotating at all.
- **The letterbox compares displayed dimensions.** Solving it in stored ones and rotating afterwards
  puts the picture outside the box on the axis the rotation swapped.

Anything that is not a quarter turn reports zero rather than the nearest right angle. A display
matrix can flip and shear, and a mirrored recording shown unmirrored is a defect nobody attributes
to the toolkit.

## Verifying a change

- `--video-frame n` freezes every view on one picture by handing it a stream that holds exactly that
  picture, with no clock in the path. **Two runs must hash the same**; check by hashing, not by
  looking. `--scene video` and `--scene kitchen-video` are the two that hold a view.
- A screenshot run starts no audio device and therefore no player, because which picture a capture
  catches would otherwise depend on the mixer period and the machine's load.
- The Kitchen Sink's Video tab is a couple of hundred points tall and its panel is the last thing
  that fits. A control added below the picture lands past the fold and a capture cannot show it,
  which is why the transport is over the picture and why that tab's picture box is shorter than the
  standalone scene's.
- **The tab offers a short list of the sources; `--scene video` offers all of them.** The list is
  `VideoScene.TAB_SOURCES`, named rather than sliced, and the entry it opens on must be one a
  capture can rely on: generated in memory, on screen in the frame the tab is built. An entry
  that encodes or demultiplexes a file first puts "Opening …" in every screenshot of that tab.
- The FFmpeg tests need `./scripts/build-ffmpeg.sh --profile full`; the shipped `player` profile has
  no encoder, and a test that needs a file to read cannot run against it. `--shim-only` recompiles
  the C in a second against an FFmpeg that is already built.
- **The writer's subtitle cues are contiguous and each names its own index** (`T0 C3`), so at every
  instant of a clip exactly one cue is on screen and "a scrub left a stale cue up" is a wrong string
  rather than a judgement about an interval. Its first cue of each track carries override tags and a
  hard break and the rest do not, which makes the markup rule assertable in both directions from one
  file. **The cues are timed in whole milliseconds**, which is what a tx3g sample is timed in, so ten
  pictures at 30 per second is 333 ms and not 333⅓: a test that multiplies a nominal cue length
  lands in the wrong cue about six cues in, and reads as a subtitle off-by-one rather than as its own
  arithmetic. `FfmpegTests.cueStartMicros` is where that is stated.
- **The clip writer is what makes a multi-track file exist**, since no media is committed. Each of
  its tracks gets a language tag and a tone of its own (440 Hz for the first channel of the first
  track, an octave up per channel and an odd multiple per track, so no two frequencies coincide),
  and a test that asked for one track and is hearing another sees a wrong *frequency* rather than a
  level it has to interpret. The writer refuses a combination whose tones would pass the Nyquist
  frequency instead of clamping them, because two tracks clamped to the same tone would make the
  test that tells them apart pass by accident.
- **The languages a container records were measured, not assumed.** An MP4 written with no language
  on a track reads back as `und` (the ISO 639-2 undetermined code) and not as an absent tag, so
  "absent" and "und" both have to mean no language, and both arrive as null. The linked build has a
  Matroska *de*muxer and no Matroska muxer, so a Matroska file's tags can only be checked against
  a real one behind `-Dlimn.video.test.clips`.
- The audio track picker in the Video tab, like the soundtrack switch beside it, is absent from
  every capture: a screenshot run starts no audio device and therefore no player, and there is
  nothing to change the track of. What a capture does show is the listing: the facts line under
  the picture names every track a container holds.
- **The zero-copy path needs no decoder and no media.** The backend's suite writes its own
  IOSurfaces, in the two layouts VideoToolbox produces, so the binding, the rectangle sampler,
  P010's normalisation, the orientation and the release discipline are all testable on any Mac.
  What is *not* testable without a real clip is a hardware decode end to end, because the only codec
  both this repository can encode and an accelerator could take is MPEG-4 Part 2 and Apple Silicon
  no longer decodes it. So those tests skip there and say so rather than passing quietly.
- **The P010 entry must be indistinguishable from the 10-bit one beside it.** They are the same ramp
  in two justifications, so comparing the two captures' picture areas is the check; a difference of
  any size at all is the shift being applied twice or not at all.
- Depth is what a capture cannot show, because a window's framebuffer has eight bits of its own. The
  Kitchen Sink's 10-bit entry therefore makes the *code space* visible rather than the precision:
  the gradient steps one code per column and wraps at the end of it, so at eight bits it sawtooths
  back to black two and a half times across 640 columns and at ten bits it climbs once and does not
  wrap. Count the ramps; do not look for smoothness.
