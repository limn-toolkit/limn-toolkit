# ADR 048: A window closes by its policy, and an application ends by asking once

- **Status:** Accepted and implemented, 2026-09-29. Proposed on 2026-09-25 as a sketch, written at
  the owner's request after that day's exit crash (a Mac on software OpenGL aborted on every exit).
  Decided after §4's measurements, with the scope the owner set on 2026-09-29 (§1.5). Built as
  `ClosePolicy`, `CloseRequest`, `CloseRequestHandler`, `QuitRequest`, `QuitHandler`, `LoopLifetime`
  and `LoopEnd` in `limn.backend`, one shared coordinator (`limn.backend.internal.Ending`) and
  `MacQuitCommand`. `ApplicationEndingTest` covers the rules over headless windows.
  `ApplicationEndingProcessTest` covers the real backend in child JVMs; its five portable modes pass
  on macOS, Windows 11, Fedora on Wayland and Ubuntu on X11, and its macOS Quit command mode passes
  on the Mac.
- **Date:** 2026-09-25, decided 2026-09-29
- **Scope:** how an application ends by its own action. That covers what happens when a user or a
  program closes a window, per-window close policies, one request to end the whole application (the
  application's Quit, and macOS's Quit command for it), and the event loop's lifetime. What the
  operating system announces (a session ending, a logout, a signal) is out of scope (§1.5).
- **Compatibility:** breaking. `NativeWindow.setCloseRequestHandler(BooleanSupplier)` becomes an
  asynchronous handler, and `Backend.runEventLoop()` returns why it ended. The road map puts it in
  the API-shape release (0.10), before the desktop integration (0.14) that builds on it.

## 0. What existed

Read in the code on 2026-09-25, and measured live on 2026-09-29 (§4).

- **One synchronous hook.** `NativeWindow.setCloseRequestHandler(BooleanSupplier)` was consulted when
  *the user* asked a window to close (the close button, Alt-F4, Cmd-W). `false` vetoed. A program's
  `requestClose()` bypassed it. A modal over the window did not block it, on purpose: "quit" is the
  one question an application must still be able to ask. An application that wanted to confirm with
  a dialog had to veto, show the dialog, and call `requestClose()` itself from the answer. The demo's
  `FilesScene` did exactly that, with a flag to keep a second click from opening a second dialog.
- **Closing was disposing.** A window that closed was destroyed; there was no hidden state to return to.
- **The loop ended with the last window.** `LwjglBackend.runEventLoop` returned when no window was left
  or `stop()` was called. The JVM ends because every thread Limn starts is a daemon (`Threads.daemon`).
- **Nothing was application-wide.** There was no request to end the application. On macOS, Cmd-Q and
  the Quit menu item were GLFW's: its application delegate turns `applicationShouldTerminate:` into a
  close request on every window and cancels the termination. Measured (§4): the windows are asked
  newest first, and a veto in one leaves the others already closed.

## 1. Decision

### 1.1 A window has a close policy and, optionally, a handler that answers later

What a user's close does, once it is allowed, is the window's **policy**: `ClosePolicy.DISPOSE`
(the default, and the old behaviour), `HIDE`, or `END_APPLICATION`, which starts §1.2 instead of
closing one window. Whether the close is allowed is up to the window's **handler**, if it has one.

```java
window.setClosePolicy(ClosePolicy.HIDE);
window.setCloseRequestHandler(request -> {
    if (!document.isDirty()) {
        request.allow();
        return;
    }
    new Dialog("Save changes?", "…").addButton("Discard", "discard").addPrimaryButton("Save", "save")
            .show(scene).thenAccept(answer -> {
                if (answer != null) { save(answer); request.allow(); } else request.refuse();
            });
});
```

The handler receives a `CloseRequest`, which says which window is asked and why (`USER_CLOSE` or
`APPLICATION_END`), and is answered exactly once with `allow()` or `refuse()`, now or later. A second
answer throws. While a request is unanswered, a second close gesture on the same window asks nothing
new, so a double click on the close button opens one dialog. A program's `requestClose()` still
bypasses both policy and handler, so a program that has decided can always close.

**Where it lives: `NativeWindow`, not `Scene`** (§3's first question). The handler it replaces is
already there. The window is what a user closes. A window with no scene (a video window, a
backend-level test) must be closable too. And ADR 046's two-layer line (G1, decided 2026-09-29) puts
`NativeWindow` in the application layer the 1.0 contract freezes.

### 1.2 One request ends the application, and nothing closes until everything agreed

`Backend.requestQuit()` asks to end the application. The application calls it (a Quit item, a
window whose policy is `END_APPLICATION`), and on macOS so does the system's Quit command for it
(§1.4). It is answered once, in this order:

1. The **quit handler**, `Backend.setQuitHandler`, is asked first. It is application-wide, for what
   belongs to no window (a download still running), and a refusal ends the request there.
2. Then each open **top-level** window, **front to back**: the window the user worked in last is
   asked first, so a dialog it opens appears where the user is looking. The order is the one GLFW
   did not have (it asked newest first). A window with no handler agrees. A window whose handler
   refuses ends the request, and every window stays open.
3. Once every window has agreed, all of them close together, owned windows included, and
   `runEventLoop()` returns `LoopEnd.QUIT`.

Asking is **sequential**: the next window is asked only after the one before it has answered, so at
most one question is on screen. A request made while one is still being answered joins it rather
than starting a second round. A window that closes while the request waits counts as having agreed.
A window with an unanswered request of its own (the user clicked its close button) is not asked
twice: its answer to that request counts.

**Owned windows are not asked** (§3's third question). A window registered with its owner through
`registerChildPopup` (a native dialog, a menu, a dropdown, a date picker) speaks through its owner
and closes with it. A native `Dialog` is such a window. Its result completes as cancelled when its
window closes, as it did before when the user closed the owner.

**A modal open during the request does not refuse it by itself** (§3's fourth question). The
window it blocks is asked like any other, and its handler, which knows about the modal
(`isModalBlocked()`), decides. That is the rule the close button already followed: quit is the
question an application must still be able to ask.

**The reason is only whose request it is** (§3's fifth question): `USER_CLOSE` for one window's
close, `APPLICATION_END` for §1.2. "The system is shutting down" is a system event and is out of
scope (§1.5).

### 1.3 The loop's lifetime is the application's choice

`Backend.setLoopLifetime(LoopLifetime)` chooses between `UNTIL_LAST_WINDOW` (the default, and the old
behaviour) and `UNTIL_QUIT`. With `UNTIL_QUIT` the loop runs with no window open and ends only
through §1.2 or `stop()`. A tray application (0.14) needs that. A hidden window is an open window in
either lifetime.

`runEventLoop()` returns a `LoopEnd`: `LAST_WINDOW_CLOSED`, `STOPPED` or `QUIT`. An application that
ignores it compiles as before.

### 1.4 macOS's Quit command is the application's Quit

Cmd-Q, the Quit item of the application menu, the Dock's Quit and any quit Apple event reach
AppKit's `terminate:`, and from it the application delegate's `applicationShouldTerminate:`. The
backend gives GLFW's delegate *object* a subclass of its class that overrides that one method. This
is the class swap the accessibility bridge and `MacInputContextGate` already use on views, so GLFW's
class itself is never edited. The override answers `NSTerminateCancel` at once and posts
`requestQuit()` to the UI thread, so the loop is free while the windows are asked.

`NSTerminateLater`, which the sketch proposed, is not usable: AppKit then runs a nested run loop
inside `terminate:` until the reply comes, Limn's own loop is stuck inside it, and the UI task that
would reply never runs. That was measured as a hang in every run (§4).

Windows and Linux have no application-wide Quit command. There, the application's own Quit item
calls `requestQuit()`.

### 1.5 System events are another record

The owner's decision, 2026-09-29: this record is about the application ending by its own action.
What the operating system announces is a system listener and belongs to a record of its own, if
one is ever wanted: a session ending (`WM_QUERYENDSESSION`, logout on macOS, the Linux session
manager and portal `Inhibit`), `SIGTERM`, `SIGINT` and `SIGHUP`. The sketch's §1.4 items for them are
withdrawn here. What was measured about them stays in §4, for that record.

One consequence is declared rather than fixed: a macOS logout asks the application to quit through
the same `terminate:`, and the backend answers `NSTerminateCancel` to it, as GLFW did before. So a
Limn application still interrupts a logout, and then runs §1.2.

## 2. Alternatives considered

- **Keep the one handler and document the patterns.** The cheapest. It leaves the partial quit on
  macOS, the veto-then-`requestClose` dance for every confirmation dialog, and no tray lifetime.
- **A policy value `ASK` instead of a handler beside the policy.** This was the sketch's shape. It
  couples "may it close" to "what closing does", so a window could not both ask and hide. Kept
  apart, a window can.
- **Ask every window at once and collect the answers.** Faster when nobody has a question. But when
  two windows do, two dialogs compete for the user, which is the confusion §1.2 exists to remove.
- **Copy Swing's `setDefaultCloseOperation` verbatim.** Familiar, but its EXIT calls `System.exit`,
  which skips the native teardown the 2026-09-25 crash lived in.
- **Replace `applicationShouldTerminate:` on GLFW's class.** Measured to work, but it edits a class
  GLFW owns, process-wide. Swapping the delegate object's class touches only that object.

## 3. The questions the sketch left open, and where each is answered

| Question | Answer |
| --- | --- |
| The policy on `NativeWindow` or on `Scene` | `NativeWindow` (§1.1) |
| The order windows are asked in | Front to back, one at a time (§1.2) |
| Whether an owned window is asked before its owner | It is not asked; its owner speaks for it (§1.2) |
| What a modal open during the request does | Nothing by itself; the window's handler decides (§1.2) |
| Whether the request carries a reason | Only `USER_CLOSE` or `APPLICATION_END` (§1.2) |
| The shutdown hook's bound | Out of scope with the signals (§1.5) |

## 4. Measured on 2026-09-29

A probe opened three windows, A, B and C, in that order, on the Mac host (GPU, and Apple Software
Renderer forced), the Windows 11 guest, Fedora KDE on Wayland and Ubuntu GNOME on X11.

- **macOS, GLFW's own Quit.** `terminate:`, whether sent in-process as the Quit item sends it (its
  action reads `terminate:` with a nil target) or from outside as a quit Apple event, asks C, B, A,
  synchronously inside `terminate:`. With B refusing, A and C close anyway. The partial quit is real.
- **macOS, taking the method.** An `applicationShouldTerminate:` of the backend's receives both
  routes, and GLFW works on. Answering `NSTerminateCancel` returns at once. Answering
  `NSTerminateLater` hung until killed (15 s), with the reply's UI task never run, in both reply
  cases.
- **macOS, two routes kept for a system-events record.** A handler of our own for the quit Apple
  event can `suspendCurrentAppleEvent` and answer 300 ms later from a UI task with the loop free,
  both agreeing and refusing (`errn` −128). Retargeting the Quit item to an object of ours reaches
  it with the loop free. The logout's `why?` reason was not seen.
- **Windows.** `WM_QUERYENDSESSION` and `WM_ENDSESSION`, sent from session 1, reach GLFW's window
  procedure on the UI thread. GLFW agrees (1) and ignores the end; the application is never told.
  `taskkill` without `/F` posts `WM_CLOSE` to one window only, the most recent, which GLFW treats as
  a user close of that window.
- **Signals, on all three systems.** SIGTERM and SIGHUP exit with 143 and 129. The shutdown hooks
  run, no window is destroyed on the UI thread, and no crash report is written, on software GL too.
  A hook that posts "close everything" to the UI thread closes the backend in 3 to 20 ms. With the
  UI thread busy, it gives up at its bound and the process still exits cleanly. A job started in the
  background by a non-interactive shell ignores SIGINT, which the JVM respects.

The probe and the raw logs are kept outside the repository.

## 5. Consequences

- The desktop integration of 0.14 (tray, the native macOS menu bar with its Quit item) builds on
  §1.2 and §1.3 instead of inventing its own ending.
- The ending gains tests of its own: the request's order and refusals over headless windows, and a
  child JVM that sends macOS's `terminate:` and watches the process end, as `ClosedWindowTest` and
  `SoftwareGlExitTest` watch theirs.
