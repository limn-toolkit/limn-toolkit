# ADR 020: Background work is a job with a lifecycle, and cancelling it stops the delivery rather than the thread

- **Status:** Accepted, 2026-08-06. Implemented in `limn.concurrent` as `Work`, `Job`, `Progress`
  and the `Ui.work` / `UiRuntime.work` entry points. `Ui.async` is unchanged.
- **Date:** 2026-08-06
- **Scope:** how application code runs something off the UI thread when the request can be
  superseded, is slow enough to be worth a progress bar, or can outlive whoever asked for it. What
  cancellation is allowed to promise, where a failure goes when nobody is listening, and how a
  background job is told that its requester has gone.
- **Audience:** whoever writes a loader, a search field or a thumbnailer, or is about to ask why
  cancelling a job does not stop the thread it is running on.

---

## 0. What `Ui.async` could not do

`Ui.async(Supplier<T>)` runs a supplier on the worker pool and completes a stage on the UI thread.
That is the whole of the "click, fetch, update a label" path and it stays. What it has no way to
express is a request that can be *withdrawn*.

The failure is easiest to see in a search field. Type three characters, get three calls; each one
completes onto the same list, and the list ends up showing whichever query happened to **finish**
last rather than the one the user **asked** last. A slow first query and a fast third produce a list
that is stale the moment it appears, and no amount of care at the call site fixes it, because the
call site has no handle on the first two. `CompletableFuture.cancel` is not that handle: by its own
contract it does not stop a task an executor has already started, and here it would complete the
stage exceptionally on the cancelling thread: a UI mutation off the frame, from a method whose name
promises the opposite.

Three smaller gaps sit alongside it. A `Supplier` cannot throw a checked exception, so every body
that opens a file wraps and rethrows before it does anything else. A stage delivers exactly once, so
there is nowhere to put "37% of the file". And a stage knows nothing about the widget it completes
onto, so a panel taken off screen while its load was in flight is still written to when it lands.

These are one gap, not four: **background work in a GUI has a lifecycle, and a stage does not model
one.** So `Work` describes the work, `Job` is the handle to the running instance, and `Progress` is
what the body sees from the inside.

---

## 1. Decision

1. **`Ui.work(body)` returns a builder; `start()` returns a `Job`.** The body runs on the worker
   pool and every callback runs on the UI thread, posted through the same queue as everything else.
2. **Cancellation is cooperative and never interrupts.** `cancel()` sets a flag. §2.
3. **A cancelled job delivers nothing**: not a result, not a failure, not a progress value, and not
   a delivery that was already on the UI queue when the cancel happened. Every posted delivery
   re-checks the flag as it runs, not only as it is posted.
4. **Failure is a callback, and an unhandled failure is loud.** §3.
5. **Progress is one coalesced `double`,** not a queue of published chunks. §4.
6. **"The requester has gone" is a `BooleanSupplier` the caller supplies.** §5.
7. **A result that will not be delivered is handed back for disposal,** on a worker thread. §6.

`Ui.async` keeps its signature and its behaviour. It is the right tool when the answer is wanted
unconditionally and there is nothing to withdraw.

---

## 2. Why cancellation is cooperative

The tempting implementation is `Future.cancel(true)`: interrupt the worker and be done. It was
rejected on three grounds, and the first one is decisive on its own.

**Interruption only unblocks code that agreed in advance to be unblocked.** It sets a flag and
unparks the thread if it happens to be sitting in one of the JDK's interruptible operations. A
decoder parked inside a native read is not sitting in one. The thread stays exactly where it was
until the native call returns on its own, so the mechanism does nothing for the case it was reached
for (a slow open on a network volume, a hardware decoder waiting on a surface) while giving the
caller the impression that something was stopped.

**Across a native boundary an interrupt is worse than useless.** A native library that is midway
through its own state machine has not been told anything; if the Java side then abandons it, what it
owns is leaked or left half-initialised. And the flag outlives the task: on a pooled thread, an
interrupt nobody clears is still set when the *next* job starts there, so cancelling one load can
make an unrelated one fail later, somewhere else, with a stack trace that names neither.

**And it answers the wrong question.** What a GUI needs from cancellation is not "the CPU stops"; it
is "the screen does not change". The stale-search bug is a delivery bug. A flag plus a re-check at
every delivery point gives that guarantee completely and gives it for free, including for the case
where the body has already finished and its result is sitting in the queue.

What this costs is stated rather than hidden: **a cancelled body keeps running and keeps a worker
busy until it returns.** That is why `Progress.isCancelled()` exists and why a loop-shaped body is
expected to ask between units of work. A body that never asks is uncancellable in the CPU sense and
perfectly cancellable in the sense that matters. The job is skipped entirely if the pool has not
reached it yet, which is the common case for the third keystroke in a row.

---

## 3. Why failure is a callback rather than an exception on a stage

A `CompletionStage` carries a failure in a channel that only exists if somebody reads it. Attach
nothing and the throwable is simply gone: no thread dies, no log line, no report. The UI shows the
spinner it started with, and the only evidence is the absence of the update. This repository has
already decided against that shape once: a task posted to the UI queue that throws is logged at
ERROR and reported to the crash handler as a task crash, precisely so that "my click handler threw"
is visible while it is being written rather than after it ships.

So a body's failure gets the same treatment: `onFailure` on the UI thread if one is registered, and
otherwise a log at ERROR plus a task-phase crash report. Silence is not one of the options.

Two smaller things come with the callback. The throwable arrives **unwrapped**: a stage hands its
dependents a `CompletionException` around the cause, so every consumer writes the same three-line
unwrap, and the one that forgets prints a wrapper name instead of the real one. And the body may
**throw checked exceptions**, because `Body.run` declares `throws Exception`; the `try`/`catch`/wrap
prologue that a `Supplier` forces on every loader stops being written.

The one deliberate hole: **a cancelled job does not report its failure either.** A body usually
fails *because* it was cancelled (a stream closed underneath it, a partial read refused), and
turning a withdrawn request into an ERROR line and a crash report would make cancellation noisy in
exactly the applications that use it most. That path logs at DEBUG. The rule is uniform: once
withdrawn, a job says nothing at all.

---

## 4. Why progress is one coalesced double

The reference design for this is SwingWorker's `publish`/`process` pair: the body publishes chunks
of an arbitrary type, a timer coalesces them, and the UI receives a `List` of everything that
accumulated since the last delivery. It is a general intermediate-results channel, and generality is
what makes it the wrong default here.

It **allocates on the hot path**: a varargs array per publish, a list that grows with every chunk
the UI has not consumed, and a new list handed over per delivery. A parser that publishes per row
over a large file spends real memory on values that exist only to be counted. It **delivers history
nobody wants**: a progress bar needs the newest number, and receiving the previous four hundred
along with it is work at both ends. And it **forces a type parameter** on every user of the API for
a payload that is, in the overwhelming majority of cases, one number between zero and one.

So the channel is exactly one number. The newest value lives in an `AtomicLong` as raw bits, and a
single **pre-allocated** delivery runnable is posted only when no delivery is already outstanding.
Reporting therefore costs a store and a compare-and-set, plus (at most once per frame) one queue
node. A body may report per iteration of its tightest loop without thinking about it, which is the
property that makes people actually report.

The coalescing buys an ordering guarantee that is worth stating because it is what a progress bar
needs and is not obvious: **the last value reported before the body returns is always delivered, and
always before the success callback.** It falls out of two facts (the completion is posted after the
body has returned, and the UI queue is FIFO) plus one detail that is easy to get backwards. The
delivery clears its "a delivery is queued" flag *before* it reads the value. Reading first and
clearing afterwards opens a window in which the body's final report lands in a slot that the
outgoing delivery has already read past and that no new delivery is ever posted for: the bar sticks
at 99% for the rest of the application's life. The comment at that line says so.

What is given up: a body that wants to stream intermediate *results* (rows appearing in a table as
they parse) is not served by a double, and should not be. That is a second feature with its own
back-pressure question, and §7 leaves it out rather than half-answering it here.

---

## 5. Why the "requester is gone" guard is a predicate and not a widget

The delivery guard exists because the common leak in a GUI is not memory, it is a callback landing
on something nobody is looking at any more: a panel closed while its load was in flight, a row
recycled under a thumbnail request, a dialog dismissed before its save returned.

The obvious API is to hand the job the thing that asked: `deliverIf(widget)`, and let the runtime
ask whether it is still attached to a scene. It was rejected because of where the code lives.
`limn.concurrent` sits at the bottom of the toolkit; the widget tree sits above it and depends on
it. A parameter of a scene type would reverse that arrow, which costs three things at once: the
concurrency runtime could no longer be tested without building a scene, background work would become
a scene-layer concept that a file loader or a decoder could not use without dragging the widget tree
in behind it, and the toolkit's one structural rule (the base module depends on nothing) would have
been spent on a convenience.

A `BooleanSupplier` says the same thing and knows nothing: `deliverIf(view::isAttached)`,
`deliverIf(dialog::isShowing)`, `deliverIf(() -> generation == latestGeneration)`. The last of those
is the one a widget type could not have expressed at all: "still the current request", which is a
fact about the application rather than about the scene graph.

It is asked **on the UI thread, immediately before each delivery**, which is what makes it legal for
it to read widget state, and is also the reason §6 exists, because the decision is therefore taken
somewhere the disposal must not happen. It is asked once per delivery, so it must be cheap and must
not block, and a predicate that throws is read as "gone": a broken guard drops and disposes rather
than delivering into a half-dismantled view.

Declining a delivery is deliberately **not** cancellation. The body is not stopped and the job
finishes normally; only the delivery is refused. Merging the two would mean a predicate that
answered `false` for one frame silently killed work that was about to be wanted again.

---

## 6. The discard rule, and the ordering that forces it

A body that returns an open stream, a mapped file or a decoded picture and then finds its delivery
refused has handed its resource to nobody. That is a leak with no owner, and it appears only on the
cancel path, which is the path least likely to be exercised by hand. So `onDiscarded` receives any
value that will not be delivered, and it is the price of returning a resource from a body.

**It runs on a worker thread, not the UI thread.** Closing a file, unmapping a buffer or releasing a
native picture can block, and the UI thread is the one place in the process where nothing may.

That forces an unobvious flow, and it is worth naming because the shorter version is wrong. Whether
a value is discarded depends on `deliverIf`, and `deliverIf` may only be asked on the UI thread. So
the result **crosses to the UI thread, is refused there, and crosses back to the pool to be
closed**, rather than the runtime asking the predicate off the UI thread, which would put widget
reads on a worker. The one shortcut taken is the case that needs no question: a job already
cancelled when its body returns is known undeliverable on the spot, and disposes on the thread that
produced the value without the round trip.

Two consequences accepted. A pool that has already shut down cannot take the disposal, and there the
value is closed on the calling thread rather than leaked; the wrong thread is better than the wrong
outcome. And disposal does **not** depend on a success handler being registered: a withdrawn value is
disposed either way, because a facade that attaches the disposer on its caller's behalf cannot know
what its caller registered, and a guard that only guards the callers who did not need it is not one.
What is left undisposed is the value that was *delivered* with nobody to take it: nothing was
withdrawn there, and a body whose result nobody wants should not be returning a resource.

---

## 7. What this deliberately is not

- **Not a replacement for `Ui.async`.** An unconditional fetch whose answer is always wanted is
  fewer lines as a stage, and stages compose with each other in ways a job does not.
- **Not interruption**, and not a way to stop a body that does not check. §2.
- **Not a stream of intermediate results.** §4.
- **Not a scheduler.** No priorities, no per-key deduplication, no "replace the job with this tag".
  A view that wants one-in-flight cancels the one it holds; that is two lines and it is explicit.
- **Not a timeout.** A job that never returns is a bug in the body, and a timeout would hide it
  while leaving the worker occupied anyway.
- **Not a bound on the pool.** The worker pool is the runtime's, sized once at startup; a job takes
  a thread from it exactly as `Ui.async` does.

---

## 8. Verification

`WorkTest`, with the JUnit thread playing the UI thread and frames pumped by hand, so the ordering
assertions are about what a frame loop sees rather than about timing:

- the body runs off the UI thread and every callback lands on it;
- a job cancelled before the pool reaches it never runs its body at all, pinned with a one-thread
  FIFO pool and a barrier task, not with a sleep;
- a cancelled job delivers nothing, including a progress delivery that was already on the queue when
  the cancel happened;
- `deliverIf` is asked on the UI thread, once per delivery, and `false` drops all of them;
- a dropped result reaches `onDiscarded`, on a thread that is not the UI thread;
- three reports between two frames deliver once and carry the newest value;
- the last value reported before the body returns arrives, and arrives immediately before the
  success callback;
- a checked exception reaches `onFailure` unwrapped and never reaches `onSuccess`; with no handler
  registered it is reported as a task crash;
- `start()` twice throws, and `start()` works from a thread that is not the UI thread.

---

## 9. What the first caller changed

The demo's video tab was rewritten against this API and the facades built over it (the first code
that *used* it rather than specified it). Three things came back.

### 9.1 One shape for the warm-ups, and it is the unstarted one

`Videos.warmUpAsync()` returned an unstarted `Work<Void>`; `Sounds.warmUpAsync()` returned a started
`CompletableFuture<Boolean>`. A caller warming both facades wrote two different things one line
apart. Worse, the first shape invites a specific mistake: `Videos.warmUpAsync();` on a line of its
own compiles, reads exactly like a warm-up, and warms nothing.

Making the warm-ups start themselves is the tempting fix, and a warm-up is the call most nearly
entitled to it: no result anybody waits for, nothing to withdraw, nothing holding a resource; the
builder is ceremony over a fire-and-forget. It was rejected because of how the mistake it would fix
is actually made. Nobody drops a `Work` after reading that member's Javadoc; they drop it because
they learned the rule at another call site and carried it here. The toolkit's asynchronous facades
are otherwise uniform (the call returns a description, the caller attaches handlers, `start()` runs
it), and one exception turns "does this one need `start()`?" into a question asked per member, which
is a better way of producing the same dropped call somewhere it costs more than a cold decoder.

So both are `Work` and both are unstarted. `Sounds.warmUpAsync()` becomes `Work<Boolean>`, which also
gets it what a bare stage had no way to offer: the answer guarded by `deliverIf`, and a cancel for a
splash torn down while a Bluetooth output is still waking. `Videos.warmUpAsync()` keeps the progress
it reports across the installed decoders, which a started form has nowhere to hand a caller.
`SoundsAsyncTest` asserts the half that has to stay true: an unstarted warm-up never reaches the
engine.

### 9.2 The contract moved onto the members

§2's guarantee (no callback runs after `cancel()` returns) holds for a cancel made on the UI
thread; from another thread a delivery already executing finishes. That qualification was
discoverable only in this repository. A published Javadoc jar carries no design note and no ADR, so
it now sits on `Job.cancel` in the form a caller acts on, together with the others in that category:
`isDone()` flipping on the worker thread while its delivery is still queued, the absence of any
ordering between jobs, at most one terminal callback per job, and a `deliverIf` answering true
covering exactly that delivery and not the next frame. `WorkTest` gained the assertion the
single-terminal-callback claim needed to be a guarantee rather than a sentence.

What stays outside the members is the reasoning: why cooperative, why one double, why the discard
round trip. The member says what is promised; the note says why the thing is shaped that way.

### 9.3 No `Work.map`

The tab wanted a value richer than the facade produces (a video stream plus the container it came
out of and that container's audio track) and could not write
`Videos.openAsync(file).map(Opened::new)`. It adapted inside its handlers instead. That is a real
caller asking rather than an imagined one, so the request was taken seriously and is still declined.

The obstacle is ownership, and it is not an implementation detail. `Videos.openAsync` returns a
description already carrying an `onDiscarded` that closes the source, because a withdrawn open must
not leak a container. After a `map` the value in flight is the mapped one, and the disposer
registered upstream is typed for a value that no longer travels. Both possible defaults are wrong.
Drop the upstream disposer, and the facade's leak guard disappears the moment a caller adds one word
to the chain (silently, on the cancel path, the path least likely to be exercised by hand). Keep it
and dispose both, and a mapper that *wraps* the original, which is precisely what the demo's value
does, closes the same source twice; a source closed twice is a decoder torn down under a thread still
reading it. Which of the two is correct depends on whether the mapper took ownership, and that is a
fact only its author knows and no signature can carry.

The other three questions have the same shape. If the mapper itself throws, a value already exists
and must not leak, so the upstream disposer has to survive the map after all. If the map succeeds and
the delivery is then refused, the mapped value and possibly the original both need disposing, and
again only the mapper's author knows which. And the thread is a choice between two defects: on the
worker it keeps the UI thread free and makes a mapper touching widget state a violation the compiler
cannot see (and a mapper is exactly where an application is tempted to build a view model), while
on the UI thread it runs inside the frame, which is the freeze this package exists to prevent, and
where "decode the thumbnail while you are there" is exactly what somebody will put.

Against four questions the API cannot answer for the caller, `map` buys one lambda. The adaptation
the tab wanted is a wrapping expression inside `onSuccess`: on the UI thread, after `deliverIf` has
been asked, written by the code that knows who owns what. And where the richer value has to be
*produced* on the worker (the demo's container case, where the audio track comes out of the same
demultiplex), the answer already exists and is one call: `Ui.work` with a body that returns the whole
record and a single `onDiscarded` that owns all of it. That is what the tab does, and it is correct
for the reason a mapped chain could not be: one place decides what the value is, and one place
closes it.

So: no `map`, and no `flatMap` behind it. It belongs with §7's list of what this deliberately is not.
