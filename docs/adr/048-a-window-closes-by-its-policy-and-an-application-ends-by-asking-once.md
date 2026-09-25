# ADR 048: A window closes by its policy, and an application ends by asking once

- **Status:** Proposed, 2026-09-25, as a sketch. Written at the owner's request after the exit crash
  of the same day (a Mac on software OpenGL aborted on every exit), to be evaluated and decided when
  it is implemented, not before. Nothing here is built. §4 lists what has to be measured first.
- **Date:** 2026-09-25
- **Scope:** what happens when a user or a program closes a window, and when an application ends:
  per-window close policies, one request to end the whole application, the event loop's lifetime,
  and the ways a process is told to end from outside (the macOS Quit command, a session ending, a
  signal).
- **Compatibility:** breaking where it replaces `NativeWindow.setCloseRequestHandler`; the road map
  puts it in the API-shape release (0.9), before the desktop integration (0.13) that depends on it.

## 0. What exists today

Read in the code on 2026-09-25, not in the guides, which say one line about it ("`runEventLoop()`
blocks until the last window closes").

- **One hook.** `NativeWindow.setCloseRequestHandler(BooleanSupplier)` is consulted when *the user*
  asks a window to close (the close button, Alt-F4, Cmd-W); `false` vetoes. A program's
  `requestClose()` bypasses it. A modal over the window does not block it, on purpose: "quit" is the
  one question an application must still be able to ask (`LwjglWindow`'s close callback).
- **Closing is disposing.** A window that closes is destroyed; there is no hidden state to return to.
- **The loop ends with the last window.** `LwjglBackend.runEventLoop` returns when no window is left
  or `stop()` is called; the application's `close()` then destroys what remains, and `main` returns.
  The JVM ends because every thread Limn starts is a daemon (`Threads.daemon`); an application thread
  that is not keeps the process alive, as in Swing.
- **Nothing is application-wide.** There is no request to end the application. On macOS, Cmd-Q and
  the Quit menu item are GLFW's: by GLFW's source (not measured here), its application delegate turns
  `applicationShouldTerminate:` into a close request on *every* window and cancels the termination,
  so each window's handler is asked in an order nobody chose, and a veto in the third window leaves
  the first two already closed. On Windows and Linux there is no such command at all.
- **Nothing answers the outside.** A session ending (`WM_QUERYENDSESSION` on Windows, the logout on
  macOS, the session manager on Linux), a `SIGTERM`, and a `System.exit` from another thread are
  unspecified and untested. The exit crash of 2026-09-25 lived exactly there: the teardown path that
  only a process ending exercises.

What an application does today, mapped onto the close operations of the toolkits it may come from:
dispose is the default; hide is `hide()` and `false` in the handler; do-nothing is `false`; exit is
`backend.stop()` in the handler.

## 1. Decision (proposed)

### 1.1 A window has a close policy

`NativeWindow` (or the `Scene` bound to it, to be decided in §3) carries a policy for a *user* close,
chosen from a closed set: **dispose** (the default, today's behaviour), **hide**, **ask** (a handler
answers, as today's `BooleanSupplier` does, possibly after a dialog), and **end the application**
(§1.2). A program's close keeps bypassing the policy.

### 1.2 One request ends the application

`Backend` gains a request to end the application, made by the application (a Quit menu item) or on
its behalf (the macOS Quit command, a session ending). It is answered **once**, in a defined order:

1. an application-level handler is asked first, and may cancel the whole request;
2. then each window's policy, front to back (or in the order the application registered, §3); a
   window whose policy is **ask** answers, and a refusal cancels the request *for every window not
   yet closed* and reports which window refused;
3. when every window agreed, they close together, the loop ends, and `runEventLoop` returns a value
   that says why (last window closed, `stop()`, an end-of-application request, the session).

The partial quit of §0 is what this removes: no window closes until all have agreed.

### 1.3 The loop's lifetime is the application's choice

"The loop ends with the last window" becomes one of two lifetimes: **until the last window closes**
(the default) and **until the application ends it**, which a tray application needs (0.13) — it
lives with no window open and ends through §1.2.

### 1.4 The outside is answered through the same request

- macOS: the backend owns `applicationShouldTerminate:` instead of GLFW's delegate, and answers it
  through §1.2 (`NSTerminateLater` while windows are asked, then `replyToApplicationShouldTerminate:`).
- Windows: `WM_QUERYENDSESSION` / `WM_ENDSESSION` go through §1.2, with the platform's time limit.
- Linux: the session manager's end-of-session request, where the desktop offers one (portal
  `Inhibit`, the XSMP or GNOME session protocol); to be read, §4.
- `SIGTERM` and `SIGINT`: a shutdown hook asks the UI thread to run §1.2 without the chance to
  cancel, and waits a bounded time for the windows to be destroyed on the UI thread, so the native
  teardown runs before the process does.
- `System.exit` from application code is documented as the one way to skip all of this, with what it
  skips.

## 2. Alternatives considered

- **Keep the one handler and document the patterns** (§0's mapping). Cheapest; leaves the partial
  quit on macOS, no tray lifetime and nothing for a session ending.
- **Copy Swing's `setDefaultCloseOperation` verbatim.** Familiar, but its EXIT calls `System.exit`,
  which skips the native teardown this record exists to keep.
- **Let SDL decide.** If the SDL spike ends in a backend change, `SDL_EVENT_QUIT` and
  `SDL_EVENT_TERMINATING` arrive as events; they would feed §1.2, not replace it.

## 3. Open questions (for the implementation)

- Does the policy live on `NativeWindow` (the backend's object) or on `Scene` (what applications
  hold)? An application rarely touches `NativeWindow` except to create it.
- The order of §1.2 step 2: front to back, creation order, or an order the application gives.
- Whether an owned window (`PopupKind.OWNED_WINDOW`, a non-modal dialog) is asked before its owner.
- What a modal dialog open during an end request does: answered first, or the request refused.
- Whether the end request can carry a reason the application shows ("the system is shutting down").
- The shutdown hook's time bound, and what it does when the UI thread is busy or hung.

## 4. What must be measured before deciding

- GLFW's Cmd-Q behaviour on macOS, live: which windows are asked, in what order, and what a veto
  does (§0 is read from GLFW's source only).
- Whether the backend can own `applicationShouldTerminate:` without breaking GLFW's delegate.
- `WM_QUERYENDSESSION` delivery to a GLFW window on the Windows guest, and the time Windows allows.
- What the Fedora and Ubuntu desktops send an application at logout.
- A `SIGTERM` to a running demo on each platform: exit status, crash reports, and whether windows
  were destroyed on the UI thread.

## 5. Consequences

- The desktop integration of 0.13 (tray, macOS menu bar with its Quit item, notifications) builds on
  §1.2 and §1.3 instead of inventing its own ending.
- The exit path gains tests of its own, in child JVMs as `ClosedWindowTest` and
  `SoftwareGlExitTest` do: the defect of 2026-09-25 was invisible to every test that did not watch a
  process end.
