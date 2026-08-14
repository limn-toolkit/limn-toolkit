# ADR 021: Opening a container is not a frame of work

- **Status:** Accepted, 2026-08-06. Implemented as `Videos.openAsync`, `Videos.warmUpAsync`, the
  `VideoDecoder.openStream(Path, Progress)` and `VideoDecoder.warmUp()` defaults, and the FFmpeg
  decoder's override of the latter. `Videos.open`, `canOpen` and `supports` are unchanged.
- **Date:** 2026-08-06
- **Scope:** why the video facade's open has an asynchronous form and its two probes do not, where
  that form lives, what happens to a container that was opened for a request nobody wants any more,
  and what cancelling an open can and cannot promise.
- **Audience:** whoever writes a player, a poster picker or another decoder, or is about to ask why
  cancelling an open does not stop it.

---

## 0. The measurement, and what it is made of

A stopwatch around `Videos.open` on the UI thread, in the demo's video tab, on the machine of
record: opening the demo's MP4 the first time is over half a second; opening the Y4M fixture is
longer still, most of it the demo writing the fixture before opening it; opening a synthetic source
that parses nothing is a few tens of milliseconds. At sixty hertz the first of those is more than
thirty dropped frames: a window that stops answering, on the click that asked for a video.

None of it is a surprise once the call is taken apart. Opening a container is file I/O, then
`avformat_find_stream_info`, which reads and **decodes real packets** because an MP4 header does not
state everything a decoder needs to know; then track enumeration, then opening a decoder, then, on
the hardware path, creating a platform decode device, which is a driver call. And the first time
anything at all touches video, the FFmpeg shim has to exist: the manifest resource is read, every
library's bytes are digested to name a cache directory, tens of megabytes are copied out of the jar,
the directory is moved into place atomically, each library is linked in dependency order, and a
native identity probe runs. That happens once per process, under a global lock, on whichever thread
asked first.

Which thread asks first is the part worth stating plainly, because it is not the one anybody would
guess. **Every** path into video reaches the library load, including the two that promise to be
cheap: `canOpen`, and the `supports` behind it. A file chooser merely asking whether a clip is
playable, so that it can decide whether to grey out a button, pays a jar extraction.

The toolkit's own rule already covered this: a facade that reads a file, decodes, or touches a native
library on the calling thread must offer an asynchronous form, or say in its own Javadoc why it does
not need one. `Videos` had neither. Its class documentation said the opposite (that every method
was safe to call from any thread because nothing here schedules UI work), which was true about UI
work and silent about the half second.

---

## 1. Decision

1. **`Videos.openAsync(Path)` returns an unstarted `Work<VideoStreamSource>`**, carrying its own
   `onDiscarded` that closes the source. The caller attaches handlers and starts it. §2.
2. **`canOpen` and `supports` stay synchronous and stay cheap.** They are answers a frame needs. §3.
3. **The probe runs inside the body**, not before the `Work` is returned. §3.
4. **The asynchronous form exists on the facade *and* as an SPI default**,
   `VideoDecoder.openStream(Path, Progress)`, delegating to `openStream(Path)`. §4.
5. **A cancelled open closes whatever it opened**, and cancelling stops the delivery rather than the
   open. §5.
6. **The first-call cost has a place to be paid**: `VideoDecoder.warmUp()`, a no-op default, and
   `Videos.warmUpAsync()` which runs it for every installed decoder on a worker. §6.
7. **Both forms share one probe-and-open path**, so the diagnostics cannot drift. The synchronous one
   passes a `Progress` that is never cancelled and whose reports go nowhere.

---

## 2. Why the open is the thing that moved

An open is the one call in this subsystem that is both unbounded and unavoidable. It is unbounded
because its cost belongs to the file (stream count, bitrate, whether the volume is cold, whether it
is a volume at all) and to the machine's first use of a native library. It is unavoidable because
there is no partial answer: nothing can be drawn, sized or laid out until the container has told the
application its dimensions and its pixel format.

It is also, unlike almost everything else in a decoder, **superseded constantly**. A media browser
whose selection follows the arrow keys opens a container per key press; a preview pane opens one per
hover. That is precisely the shape `Work` exists for: a job that can be cancelled, whose result is
refused when the requester has gone, and whose refused result is a resource that has to be closed.
So `openAsync` returns a `Work` rather than a stage, and returns it **unstarted**, which is the
convention the other asynchronous facades here already follow: the caller attaches `onSuccess`,
`onFailure` and `deliverIf` before anything is submitted, and nothing can complete against a
description that is still being built.

The two failures `open` throws become deliveries. No decoder installed at all is an
`IllegalStateException`; decoders installed of which none claims the input is an
`UnsupportedOperationException` **still naming every decoder asked, in order**, because that message
is the only way anyone ever finds out which decoders existed and in what sequence they were
consulted, and it would have been the easiest thing in the world to lose while moving the loop onto a
worker. Both now arrive at `onFailure`, on the UI thread, next to the code that will put them on
screen, rather than as a stack trace on a pool thread, which is where an unhandled body failure
would otherwise land.

---

## 3. Why `canOpen` and `supports` did not move, and where the probe runs

The obvious symmetry would be an asynchronous `canOpen`, and it is wrong. `canOpen` answers a
question a frame is holding: whether to enable a control, whether to draw a poster, whether this
dropped file is one this application takes. An answer that arrives on a later frame is not that
answer; the caller would have to render a third state, "asking", for a question that is a string
comparison and twelve bytes. The SPI contract for `supports` says exactly this and has always said
it: cheap, never throws, no container parse, no index, no network.

What the contract could not say is that a decoder's *first* `supports` may link a native library,
because that is not a property of the probe. That is the asymmetry §6 fixes, and it is fixed by
moving the cost rather than by making the probe asynchronous.

The same fact decides where `openAsync` runs its probe. The tempting shape is to select the decoder
on the caller's thread (it is cheap, after all) and give the `Work` a body that only opens. On a
build carrying the FFmpeg native that shape pays the entire jar extraction **on the caller's thread,
before the `Work` is even returned**, which is the bug the method exists to remove, hiding inside the
method that removes it. So the probe is inside the body: `openAsync` returns having asked no decoder
anything, and a test pins that nothing is probed until `start()`.

---

## 4. Why the asynchronous form is on the facade *and* in the SPI

Either alone would have been half a mechanism.

**Facade only** (`Videos.openAsync` calling the existing synchronous `openStream` on a worker) is
what most toolkits ship, and it gives every decoder the thread without giving any decoder a say. A
decoder that could abandon an open cheaply has no way to learn that nobody wants it any more: the
job's cancellation flag stops at the facade, and the worker spends the rest of the open producing a
container that will be closed on arrival. It is correct and it is uninformed.

**SPI only** (an abstract asynchronous `openStream`) would have been worse in a different way. It
breaks every decoder that exists, including the pure-Java ones whose opens are genuinely one header
line and have nothing to report or abandon, and it pushes thread management into implementations
that should not have any: a decoder would each have to decide what a worker pool is, and an
application would have as many answers as it has decoders.

So the facade owns the thread and the SPI owns the seam. `VideoDecoder.openStream(Path, Progress)`
is a **default** that delegates to `openStream(Path)` (a decoder written before it keeps compiling
and keeps working, which a test pins), and overriding it is how a decoder does better than "run the
old call somewhere else". Its contract states what the one-argument form never did: which thread it
is on, that it may block, that it should consult `progress.isCancelled()` where abandoning is cheap,
that reporting progress is optional, and that returning null while cancelled is correct rather than a
failure. The one-argument form gained the sentences it was missing too, because a contract that says
`supports` must be cheap and says nothing at all about `openStream` is a contract that invites an
implementation to spend a second there and feel compliant.

The FFmpeg decoder does **not** override it, and that is the honest outcome rather than an omission:
its open is a single call across the native boundary with no interior this side can reach, so an
override could only check the flag before a call it was about to make anyway. It gets the worker
thread from the facade, and that is the whole of the win available to it.

---

## 5. What a cancelled open does with the container it may already have opened

Cancellation cannot stop an open, and pretending otherwise would be the more dangerous design. A
thread inside `avformat_find_stream_info` is inside a native read; interrupting it sets a Java flag
that nothing in that call stack ever looks at, and abandoning the library midway through its own
state machine leaks or half-initialises whatever it owns. So the flag is advisory, the open runs to
completion, and what cancellation actually guarantees is that **nothing is delivered**.

Which leaves the container that was opened for nobody. It is a file handle, a demuxer, a decoder and
a slot pool, and on the cancel path there is no caller to close it, the path least likely to be
exercised by hand, so a leak there survives a long time. `openAsync` therefore returns a description
that **already carries an `onDiscarded` that closes the source**. A caller does not have to know it
is there, which is the point; a caller that replaces it takes the job over knowingly, and the Javadoc
says so.

The close happens on a worker, which is both required and safe here. Required, because closing a
container can block and the UI thread is the one place in this process where nothing may. Safe,
because a source that was opened and never delivered has been touched by exactly one thread and has
no decode thread yet: the ownership rule that binds a stream to its decode thread from `start()` to
`close()` has not begun to apply. The handover it does need happens exactly once, on the UI thread,
in `onSuccess`.

There is one more state to name, because the SPI now blesses it: a decoder that notices the
cancellation and returns null. That is not a failure and produces no delivery at all. Returning null
*without* being cancelled is a defect in the decoder, and the facade raises it as one, naming the
decoder, rather than letting null through to a success handler, where it would fail later,
somewhere else, with nothing to point at.

---

## 6. The first call, and why the warm-up is not on `Videos` alone

The library load has to be payable somewhere other than the first probe, and the constraint that
shapes the answer is structural: `limn-toolkit` must not depend on `limn-video-ffmpeg`, and
`limn-video`'s pure-Java decoders have nothing to warm. A method on the facade that knew about the
FFmpeg module would invert the one dependency edge this repository refuses to bend; a method only on
`FfmpegVideoDecoder` would make every application name a module in its startup path and would say
nothing about the next decoder that acquires a first-call cost.

So it is one more SPI default. `VideoDecoder.warmUp()` does nothing, is called on a worker, may
block, is idempotent and **must not throw**; `FfmpegVideoDecoder` overrides it with the one line that
links the library. `Videos.warmUpAsync()` returns an unstarted job that runs it for every installed
decoder in probe order, reporting a fraction as it goes, and an application starts it once after
installing its decoders. This is the pattern the LWJGL backend already uses for the tinyfd native at
construction, generalised by one step because video decoders are installed by the application rather
than by the backend.

A warm-up failure is deliberately **not** a job failure and is logged at DEBUG. The reason is that
failing to warm leaves the decoder in exactly the state it would have been in had nobody warmed it
(which is a fully supported state, reported properly by the next `supports`), so raising it would
make an optimisation nobody asked for the loudest thing in the log, on precisely the machines that
have no native and are meant not to care. This is the same judgement the file dialog's warm-up
makes, for the same reason, and it is the one place where the rule that a failure is never silent
gives way: nothing was requested, so nothing is owed an answer.

---

## 7. What this deliberately is not

- **Not an asynchronous `canOpen`.** §3.
- **Not interruption.** §5, and ADR 020 §2 for why nothing here interrupts a worker.
- **Not a cache.** Two `openAsync` calls for the same path open the container twice. Deduplicating
  by path is right for an immutable decoded image and wrong for a stateful stream that its owner
  seeks and closes.
- **Not a change to `Videos.open`.** The synchronous form stays, it is the right call in setup code
  and in tests, and it now says on itself what it costs and on which thread.
- **Not an asynchronous `FfmpegMedia.open`.** That entry point is a module's own, below the facade,
  and its callers reach it precisely because they want the container rather than a video track,
  which is also why it cannot have the facade's shape. `openAsync` can attach a disposer because
  the value it carries has one obvious owner; a container has several tracks, closing it closes
  all of them,
  and which of them a caller took is a fact only the call site holds. So the job belongs to the
  caller, and what the member owes is the reason: it gained the threading and cost sentences it was
  missing, plus the statement that there is no asynchronous form and what to write instead: a body
  returning the caller's own record and one disposer over the whole of it. The demo's video tab is
  that, and §9.3 of ADR 020 is the same conclusion reached from the other end.

---

## 8. Verification

`VideosAsyncTest` (`limn-toolkit`), with the JUnit thread playing the UI thread and frames pumped by
hand:

- the open runs on a worker and the source arrives on the UI thread, still open;
- nothing is probed until `start()`: the description asks no decoder anything;
- no decoder installed, and no decoder accepting, both arrive through `onFailure` on the UI thread,
  the second still naming every decoder asked in order;
- what the accepting decoder threw arrives unwrapped, and no later decoder is tried;
- a cancelled open closes the source it produced, on a thread that is not the UI thread, pinned with
  a decoder held inside the open until the test has cancelled, which is also the shape of an open
  that cannot be interrupted;
- a delivery refused by `deliverIf` closes it too;
- the two-argument `openStream` is the overload the facade calls, and it is handed the job's own
  `Progress`;
- a decoder that implements only the one-argument form still opens;
- a decoder that abandons while cancelled produces neither a success nor a failure, and one that
  returns null without being cancelled is reported as the defect it is;
- `warmUpAsync` warms every installed decoder off the UI thread and finishes at a progress of 1, and
  a decoder whose warm-up throws neither stops the decoder behind it nor fails the job.

Not covered by a test: the measurement in §0, which is one machine on one date and is here to justify
a shape rather than to be a promise; and the FFmpeg library extraction itself, which needs a native
this repository does not commit and which its own tests skip for that reason.
