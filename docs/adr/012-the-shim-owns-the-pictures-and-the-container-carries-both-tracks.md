# ADR 012: The native lives in a module of its own, the shim owns the pictures, and a container type carries the second track

- **Status:** Accepted, 2026-08-04. Implemented as phase 6a of the video player: the
  `limn-video-ffmpeg` module, `limn_ffmpeg.c`, `FfmpegLibrary` and `FfmpegMedia`.
- **Date:** 2026-08-04
- **Scope:** where the first native in this repository lives, what crosses the boundary per picture,
  how it is found and loaded, and where a container's audio track arrives when the SPI that opens it
  returns video only.
- **Audience:** whoever adds a second native decoder, ports this to another platform, or wires a
  player to a real file. Extends ADR 008 (a decoder module exists so a codec dependency has
  somewhere to land) and ADR 011 (what may be inside it). Uses ADR 007's frame shape and ADR 010's
  ownership rules unchanged: **phases 1 to 5 received no edits in this phase, which is what the
  ordering was for.**

---

## 1. Decision

1. **A new module, `limn-video-ffmpeg`**, depending on `limn-toolkit` and nothing else.
   `limn-video` keeps its native-free, dependency-free sentence.
2. **Gradle never compiles C.** The library comes from a script, is not committed, and is absent on
   most machines. Everything is written so that absence is ordinary.
3. **The shim is a player handle of about a dozen entry points**, not a binding. Java never sees an
   `AVFrame`, an `AVPacket` or a pointer.
4. **`FramePool` is not reused. A sibling is written beside it**, because reusing it would put a
   copy back on the one path that exists to avoid one.
5. **The module publishes a container type** whose accessors return the two toolkit SPI types, and
   the application wires them into a player. No signature above this module changed.
6. **The library is extracted from the jar to a content-addressed directory** and loaded from there,
   with `java.library.path` and an explicit directory as the two routes in front of it.

## 2. Why a module, when `limn-video` already exists for decoders

ADR 008 drew this boundary for this arrival, and the sentence it left in `limn-video`'s build file
(that it carries no native and no third-party dependency at all) was the thing to be protected. A
decoder with 3.3 MB of shared libraries put into that module would have made it false, and an
application that plays a Y4M would have started carrying a codec.

The cost is a fifth module for one package. The alternative was to make every consumer of the
pure-Java decoders pay for a native they do not use, which is exactly the failure ADR 008 was
written to prevent, one module later.

**`settings.gradle.kts` is the guard and `checkArchitecture` is not.** That task greps import lines,
so it sees a forbidden *package* and can never see a forbidden *dependency*: nothing in it would
notice `limn-components` growing an edge to a codec module. The two edges that must stay absent are
therefore written down where the graph is declared, and that is the only place they exist.

## 3. What crosses per picture, and why it is not an allocation

FFmpeg hands back `data[]` and `linesize[]`: planes and byte strides, which is already the exact
shape the SPI publishes. So the no-copy path is `NewDirectByteBuffer` over the decoder's own memory,
and nothing is repacked. What that costs instead is a *binding* problem, and it is the substance of
this section.

A decoded picture lands on whichever buffer libavcodec's own pool had free, so a slot does **not**
keep the same address from one picture to the next. Pointing a frame's planes once at construction
is therefore wrong. But rebinding per picture is also wrong: `VideoFrame.Writer.setPlane` stores a
read-only view, so rebinding three planes thirty times a second is ninety allocations a second, and
the toolkit's whole frame design exists to avoid exactly that.

The answer is a **binding epoch** per slot, which the shim increments only when that slot's plane
addresses actually moved. Java keeps the epoch it last bound at and rebinds only on a difference,
which happens for the first few pictures, while the decoder's pool is still growing, and then never.
The direct buffers themselves are cached **by address**, shared across slots, because the set of
addresses a decoder uses is bounded by its own pool and a per-slot cache would thrash between them.

Steady state is therefore: one call, filling a `long[]` allocated once, carrying a timestamp, an
epoch and three strides. `AllocationProbe` asserts it at zero bytes, and the warm-up is skipped
before measuring because measuring during it would measure the wrong thing and pass.

## 4. Why `FramePool` is a sibling and not a reuse

`FramePool` allocates its own direct buffers. That is right for a decoder that computes samples in
Java and wrong for one whose pictures already live in memory it owns: reusing it means copying every
picture into buffers Java allocated (3.1 MB per picture at 1080p, thirty times a second) to save
about eighty lines.

So the free list stays where the memory is, on the far side of the boundary, and what lives on this
side is only the bookkeeping that turns a slot index into a `VideoFrame`. It is small enough that
naming it `NativeFrames` and putting it next to nothing else was tempting; it is a class of its own
so that the decision is visible in the file list rather than buried in a stream implementation.

**`VideoFrame.Recycler` was designed for this and is used as designed.** Phase 1 said a native
producer's implementation is one call passing the slot integer across the boundary. It is exactly
that, and no address ever crosses back.

## 5. Where the second track arrives

Nothing in `limn.video` models a container. `VideoDecoder.openStream` returns video and only video,
which is right for a decoder facade and leaves a real MP4's soundtrack with nowhere to go.

Four options were available and three were rejected:

- **Widen the SPI** so a decoder can return both. Rejected: it edits phase 1, which this phase
  exists not to do, and it makes every decoder that has no audio implement an accessor that returns
  nothing.
- **A second facade**, `Audios.openFrom(videoSource)`. Rejected: it needs the two calls to agree
  about which file they are talking about and gives no way to say so, and it demultiplexes twice.
- **Return the audio track through a side channel** on the video source: a cast, or an interface a
  caller tests for. Rejected: a caller that does not know to look gets silence with no way to find
  out why.
- **Publish a container type in the module that has one.** Taken. `FfmpegMedia.open` returns an
  object whose `video()` is a `VideoStreamSource` and whose `audio()` is an `AudioStreamSource`, and
  a player takes exactly those two. Nothing above this module needed a line.

**The ownership asymmetry is inherited rather than invented.** ADR 010 already settled that a player
borrows the video and takes the audio, because handing a source to the audio engine transfers it.
So: closing the video track closes the container (a caller that came through `Videos.open` has
never seen the container and closing what it was handed has to work), and **closing the audio track
does not**, because a soundtrack ending must not pull the decoder out from under the pictures. What
closing the audio track does instead is tell the demultiplexer nobody is reading it.

**A call arriving after the container is closed is answered, not punished.** The soundtrack can
outlive the player, so the engine's streaming thread can genuinely be inside a read when an
application closes the container. Reading video then reports the end and reading audio reports zero
frames, which is what the end of a track means to the engine. The alternative is a use-after-free in
a thread the application does not know exists.

## 6. Threads, and the honest version of "no locking needed"

It is tempting to say a shim driven by one serialized decode thread needs no locks. That is true of
the video path **in isolation and of nothing else**, because four threads reach a handle: the
player's decode thread, the audio engine's streaming thread, whichever thread released a picture,
and the thread that closes.

So the shim locks, with three mutexes rather than one: the demuxer, the video side, the audio side.
One would put a whole video decode in front of every audio refill. The order is: video or audio may
take the demuxer, the demuxer is never held while taking either, and the two are never held at once.
That is the entire rule and it is why there is no cycle.

Above it, one read-write lock per container: every call takes the read lock, so a decode that takes
ten milliseconds does not delay the picture being released beside it, and `close()` takes the write
lock, so nothing is inside libavcodec when the handle is freed.

## 7. How two tracks are pulled without one starving the other

One demux loop, two consumers, different threads, different rates. Whichever thread needs a packet
runs the loop and queues what it meets for the other track, so neither waits for the other to be
pulled and neither owns the demuxer.

The queue nobody is draining is bounded by packet count and by bytes, generously enough that a
normally interleaved file never approaches either; reaching the bound means a consumer has stopped,
which happens for real reasons. **At the bound the oldest packet of the full queue is dropped and
counted**, rather than the demuxing thread blocking.

Blocking was rejected in both directions and the asymmetry is the reason. A stalled video consumer
would stop the soundtrack; worse, `AudioStreamSource.readFrames` has no way to say "not now" (its
zero means end of stream), so an audio thread made to wait would either hang the engine or lie about
the track ending. Dropping is visible (the counters are readable and asserted) and self-correcting.

A track with no consumer at all is cheaper still: its packets are freed as they are demultiplexed
rather than queued, which is what keeps a container whose track the engine refused from accumulating
packets nobody will read.

## 8. The loader, and the four ways it goes wrong

Limn has never shipped a native. There is no extraction idiom to copy, no classifier convention, and
`checkArchitecture` forbids reusing LWJGL's `SharedLibraryLoader`: it does exactly what is wanted
and importing it outside the backend fails the build. So this is the first one, and it is designed
around the fact that **`VideoDecoder.supports` must never throw** and runs once per installed
decoder on every open.

`FfmpegLibrary.isAvailable()` therefore answers `false` for every failure and keeps a sentence
saying which. The four failures, and what each does:

| | What happens |
|---|---|
| No build for this platform | `false`, naming the platform looked for and the script that makes one |
| A build for another processor | `false`, and the message says *processor*: no operating system words this in a way a reader connects to their JDK, so the phrases each one does use are recognised and the answer is spelled out |
| Two applications extracting at once | Both write a private staging directory and rename it into place; one wins, the loser deletes its copy and uses the winner's. No lock file, so a killed process leaves nothing behind |
| A read-only extraction directory | `false`, naming the property that points somewhere else |

**The directory is named after a digest of the libraries' own bytes.** Naming it after a version
would leave an application rebuilt without a version bump loading yesterday's library for as long as
the temporary directory survived, which is the bug that is hardest to believe while looking at it.

**The architecture asked about is the JVM's, never the machine's.** This was not theoretical: the
machine this phase was built on runs an x86_64 JDK under Rosetta on Apple Silicon, so `uname -m`
says `arm64`, `os.arch` says `x86_64`, and only the second one names a library the JVM can load. The
build script asks the JVM for the same reason.

## 9. What this deliberately is not

- **Not a Gradle native build.** A toolkit build must not need a C compiler, and on any machine
  without one everything still compiles, the tests skip the way the GL-backed tests skip, and the
  demo runs with one source entry explaining itself.
- **Not Windows or Linux.** Both are phase 6b, and both need machines that do not exist yet. The
  script handles macOS and Linux and refuses Windows out loud.
- **Not signed or notarised.** Phase 6c, and it needs credentials.
- **Not seek.** `reset()` is still the only repositioning, and it is a seek to the start because
  that is what rewinding an MP4 is. Phase 7 owns the rest.
- **Not hardware decode, and not 10-bit.** Phase 8.
