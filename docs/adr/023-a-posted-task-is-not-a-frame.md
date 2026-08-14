# ADR 023: A posted task is not a frame

- **Status:** Accepted, 2026-08-06. Implemented in the LWJGL event loop, in `UiRuntime.drain`, and
  in the contracts on `Ui.post`, `Ui.postDelayed` and `Ui.async`. The demo's video transport moved
  from a ticker to a timer in the same change.
- **Date:** 2026-08-06
- **Scope:** why running a task on the UI thread used to repaint every window, why it no longer
  does, what makes that safe, what still repaints, and what an application author now owes.
- **Audience:** whoever writes an application on this toolkit (particularly anyone whose code posts
  to the UI thread), and whoever maintains the event loop.

---

## 0. What the loop used to do

Every iteration of the LWJGL loop drained the UI task queue, and if the drain had run anything at
all it asked **every window** for a frame. The comment on it said "posted tasks may have mutated
anything", which was true, and the conclusion drawn from it (therefore repaint all of it) was the
kind of blanket that looks free until something counts it.

It is not free. It makes *the act of running a task* the unit of repainting, when the toolkit's
whole invalidation model says the unit is the thing that changed. Three costs came out of that:

**A paused video held a window at the display's refresh rate.** Not because anything moved: the
demo's readouts under the picture were registered tickers, and a ticker asks for a frame every frame
whether or not what it drives has moved.

**Replacing those tickers with timers moved the cost rather than removing it.** A poll at ten hertz
is ten posted tasks a second, and each of them bought a full frame of every window. The frame rate
stopped being the display's and became the sum of the poll rates: cheaper, and still paid for
nothing.

**A poll armed from a paint sustained itself.** The paint arms the timer, the timer's task forces a
frame, the frame paints, the paint arms the timer. Nothing in that circle is a bug in isolation, and
each of the demo's three readouts had to be written around it with a gate at the arming site.

---

## 1. Decision

1. **The drain buys no frame.** The loop runs the tasks and then renders only the windows something
   asked a frame of.
2. **A task that mutates widget or scene state is responsible for invalidating what it touched.**
   This is now stated on `Ui.post`, `Ui.postDelayed`, `Ui.async` and their `UiRuntime` counterparts,
   and it is the contract change §4 is about.
3. **A task that *threw* still settles every window.** `UiRuntime.drain(Runnable)` runs a hook, once
   per crashed task, after containing and reporting the crash; the loop passes the call that
   requests a frame of every window. §3.
4. **`Widget`'s invalidation documentation says what actually happens**: it described a
   whole-scene-redraws-anyway model that predates damage tracking, which is exactly the belief that
   makes an author skip an `invalidate()`.

---

## 2. Why deleting it is safe, and how that was established

The blanket could only be removed if every posted task in the repository already invalidated what it
mutated. Every call site of `Ui.post`, `Ui.postDelayed`, `Ui.async` and `uiRuntime().post` across all
six modules' main sources was read, and none was found that mutates silently. No use of the UI
executor as a `CompletableFuture` executor, no `ScheduledExecutorService`, no `Timer`; the two video
modules contain no UI-queue hop at all, because a player's status is pulled by the view rather than
pushed at it.

That is a result about today's sources, and on its own it would be worth little; the next `Ui.post`
someone writes is not covered by it. What makes the change structural is *why* the audit came out
that way. There is no generic funnel: `Widget` has no observable-property mechanism, and each setter
invalidates by hand. But two structural funnels cover nearly everything.

- **Anything that changes size or tree structure invalidates by construction.** `Widget.add`,
  `Widget.remove` and `Widget.setVisible` all reach `markNeedsLayout()`, which marks the scene's
  layout dirty and requests a render, a full-scene repaint. There is no way to change layout
  without a frame.
- **Appearance-only changes invalidate in the setter**, in the base class where the state is shared
  (`Widget.setEnabled`) and by convention in each component's own setter.

What is left over is a task that writes a field directly, behind a setter's back. That is a real
surface, it is small, and it is now named in the contract on the members rather than being papered
over by the loop.

**The strongest evidence came from partial rendering.** The blanket called the *window's*
`requestFrame()` directly, which does not set the scene's full-damage flag. So with partial
rendering enabled, those frames already repainted only the previous frame's damage: nothing. The
toolkit has therefore been running as if the blanket were deleted for as long as that flag has
existed, and a silently-mutating task would already have been a visible bug under it. Deleting the
blanket changes behaviour only where partial rendering is off, which is the default. That also
means the partial-rendering and damage-debug toggles the demo already carries are a ready-made
harness for this, rather than something anyone had to build.

Two smaller worries turned out to be nothing. **Latency**: the drain runs before the render pass in
the same iteration, so a task that invalidates still paints in that iteration: the frame is not
deferred, it is simply not unconditional. A task that only calls `requestClose()` still works, since
closed windows are swept at the top of every iteration and the post's waker already broke the native
sleep. **The first frame**: a window is created with a frame already pending and the native refresh
callback re-requests one, so the initial paint never depended on the blanket.

---

## 3. Why the crash path keeps its frame

The blanket was doing one thing quietly that nobody had written down: repainting after a task that
*threw*. A task that fails part-way has applied part of its mutation and invalidated none of it, and
the toolkit contains that crash (it is logged, reported to the crash registry, and the drain
continues), so without a repaint the window keeps showing a state that no longer matches the tree,
until something unrelated asks for a frame.

That case keeps its frame, and deliberately keeps the *whole* of it: the extent of a half-applied
mutation is not knowable from the catch site. This is the same trade the scene already makes for an
input handler that throws, and it is written the same way rather than as a second idiom.

**Where the seam goes is the interesting part.** The catch belongs to `UiRuntime`, which is the only
thing that sees the exception, but `UiRuntime` is in `limn-toolkit` and knows nothing about windows;
it has a waker and a task queue. The response belongs to the loop, which is the only thing that
knows which surfaces exist and can repaint all of them. Neither half can be moved to the other
without inventing a second render-request path in the toolkit or letting exceptions escape the
drain, so the two stay where they are and meet at one parameter: `drain(Runnable onTaskCrash)`. The
no-argument `drain()` remains, delegating with an empty hook, so every test and every other caller
is unaffected, and the callback shape means the loop cannot forget to check a flag.

---

## 4. What an application author must now do that they did not before

This is a **contract change**, and it is the reason this ADR exists rather than a commit message.
`Ui.post`, `Ui.postDelayed` and `Ui.async` promised nothing about invalidation, and an application
that posted a mutation without invalidating it worked, entirely by accident, because the loop
repainted everything anyway. After this change that application stops repainting.

The rule is one sentence: **a task that mutates widget or scene state must invalidate what it
touched.** In practice almost nobody has to do anything, because going through a setter or a tree
mutation is already invalidating; the code that has to change is the code that writes a field
directly, or that changes a value some custom `onPaint` reads from outside the tree. That sentence
is now on each of those three methods, where someone reading the API sees it without a repository to
consult.

The upside is the reason to accept the break. A timer is now genuinely free when nothing changes,
which makes polling the right tool for watching something that rarely moves, and a poll that
*does* find a change pays for exactly the frames it changes something on. The demo's video tab is
the worked example: its readouts and its transport are timers, every write they make is guarded
against an unchanged value, and a paused picture with all of them running costs zero frames.

---

## 5. What this deliberately is not

- **Not damage tracking by default.** Partial rendering stays off by default; this changes whether a
  frame happens, not how much of it is repainted.
- **Not an observable-property mechanism on `Widget`.** A funnel that invalidated every field write
  would remove the residual surface §2 names, and would also make every setter pay for a mechanism
  the toolkit has been fine without. The contract on three methods is the cheaper answer.
- **Not a change to how crashes are contained.** A task that throws is still logged, still reported
  to the crash registry, still does not abort the drain, and still does not count toward the loop's
  consecutive-crash backstop. It gained a repaint and nothing else.
- **Not a claim that the demo has no full-rate paths left.** A playing video is paced by its own
  ticker, which is what a moving picture is for.

---

## 6. Verification

`PostedTaskFrameCostTest` (`limn-toolkit`), against a scene bound to a recording window with the
JUnit thread playing the UI thread and an injected clock:

- a posted task that mutates a widget's field without invalidating requests **no** frame, and the
  same task through a guarded setter requests exactly one: the second assertion is there so the
  first cannot pass by the harness being dead;
- a self-re-arming timer polling a window on which nothing moves runs fifty times and costs **zero**
  frames;
- the same timer, once its value starts changing, buys exactly the frames it changes something on;
- the settle hook runs for a task that threw and for no other, the drain continues past the crash,
  and a normal drain leaves the window untouched.

**Not covered by a test**: the event loop's own wiring, that it drains with the settle hook and asks
for nothing else. Running it needs GLFW on the process's first thread and a real window, and
constructing a backend inside that test suite would terminate GLFW for every other test in the JVM.
What the tests pin is that the drain hands the loop no reason to repaint; that the loop acts on
nothing else is one line, read.
