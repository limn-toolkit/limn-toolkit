# ADR 022: A file chooser is another program, so the frame before it is the one that matters

- **Status:** Accepted, 2026-08-06. The two movable parts are implemented in
  `LwjglBackend.presentBeforeBlocking` and `TinyFdDialogs.warmNative`; §6 is explicitly *not*
  decided.
- **Date:** 2026-08-06
- **Scope:** what actually happens when application code calls `FileDialogs`, what that costs, which
  parts of the cost can be moved and which cannot, and what an asynchronous form would first have to
  solve.
- **Audience:** whoever is about to promise a caller that the file dialog will stop freezing the
  application, and whoever has to explain, meanwhile, why it does.

**Verdict: partially fixable.** The modal wait is not removable inside a synchronous SPI, and the
per-call and first-call native costs are not removable through tinyfiledialogs at all. Two things
were removable and are done: the *wrong* frozen frame, and the library load on the first call.

---

## 0. The explanation that was on file, and what the binary says

The received explanation for the freeze was Cocoa and the main thread: macOS requires an
`NSApplication` panel on thread 0, the Limn UI thread *is* thread 0, therefore the dialog can only
block. It is a tidy story and it is wrong, which matters because it points every fix in the wrong
direction (at the thread) instead of at the three things that actually pin the call.

`liblwjgl_tinyfd.dylib` as LWJGL 3.4.1 ships it (arm64, 119,720 bytes) has exactly one dependency.
`otool -L` prints its own install name and then, as the whole list:

```
	/usr/lib/libSystem.B.dylib
```

No AppKit. No CoreFoundation. `nm -u` lists no `_objc_msgSend` and no Objective-C runtime symbol of
any kind: the object cannot call into Cocoa because it has nothing to call it with. Its only
process primitives are `_popen` and `_pclose`; there is no `fork`, `execve`, `posix_spawn` or
`system`. And the string pool carries `osascript`, `choose file `, `choose folder `, the
`tell application "System Events"` block, and (in the same binary) `zenity`, `kdialog`, `yad`,
`Xdialog` and `command -v `.

That is the whole mechanism, visible in two minutes: **the chooser is a command handed to a child
process through a pipe, and one line of its standard output is the answer.** The panel is drawn by
another program.

---

## 1. Decision

1. **The synchronous shape stands.** `FileDialogs` keeps blocking the UI thread. §6 says what an
   asynchronous form would have to solve first, and does not decide it.
2. **Every entry point presents before it blocks.** `LwjglBackend.presentBeforeBlocking` draws each
   window's pending frame and re-presents it into both buffers, immediately before the native call.
   §4.
3. **The native library is linked on a worker at backend construction**, not on the click.
   `TinyFdDialogs.warmNative`. It buys back the library load and nothing else. §3.
4. **The cost is stated on the member.** `FileDialogs` says that the process draws nothing while a
   panel is up, that the panel belongs to another program on macOS and Linux, and that the first
   call in a process is roughly a quarter of a second dearer than the rest.
5. **The return path is asserted, not described.** `BlockingNativeCallTest`. §5.

---

## 2. The mechanism, per platform

**macOS.** tinyfd builds an AppleScript command (`choose file`, `choose file name`,
`choose folder`, with `with prompt`, `default location` and `of type {…}` clauses) into one fixed
1024-byte buffer, runs it with `popen("osascript …")` and reads back a POSIX path. Everything about
the panel is that other process's: its appearance, its placement, its sidebar, its keyboard
shortcuts. Nothing on this side can reach into it, which is also why the macOS panel matches its
type list by uniform type identifier rather than by filename, and why `MacFilterPatterns` exists at
all.

**Linux.** The same non-Windows branch of the same C file, compiled into the same binary shown
above. It picks a helper at runtime with `command -v` (zenity, kdialog, yad, Xdialog, or one of
zenity's forks) and runs it through `popen` exactly as on macOS. A machine with none of them
installed has no file chooser, and tinyfd says so in its own strings.

**Windows is the exception**, and it is a different animal rather than a variation. There the
chooser is a Win32 common dialog running in this process, on the calling thread, pumping its own
nested modal message loop. Content keeps repainting because that loop keeps dispatching paints, and
for the same reason, application code can re-enter while the call is on the stack. This is the one
platform where the toolkit owns the panel; it is also the only one where the LWJGL binding has
anything to configure, setting `tinyfd_winUtf8` in its class initializer so the dialog speaks UTF-8.
(That last is verified from the binding's bytecode; the Windows native itself was not inspected
here.)

---

## 3. What actually pins the call, and what it costs

Not the thread. Three other things:

- **tinyfd returns a pointer into a `static` C buffer** and keeps its cached state in C statics. It
  is not reentrant, and two concurrent choosers would hand the second caller the first one's answer.
- **On Windows the chooser genuinely belongs to its owner window's thread**, so "run it somewhere
  else" is not available there even in principle.
- **Nothing stops a second dialog opening on top of the first.** A loop left running during a
  chooser would need a modal gate that the toolkit's modal stack does not currently express for a
  window it does not own.

The costs, measured on the machine of record: Apple Silicon, macOS (Darwin 25.6.0), 2026-08-06,
medians of eight runs each, timed around the child process from a parent that was already warm.

| | Median | When it is paid |
| --- | --- | --- |
| `osascript` running a script that does nothing | 66 ms | **every call**, before a panel exists |
| `sh -c "command -v osascript"` | 7.8 ms | once per process (cached in a C static) |
| `osascript -e 'set osver to system version of (system info)'` | 165 ms | once per process (same) |
| class-initializing the LWJGL binding: extract + `dlopen` | 6 ms | once per JVM |

For scale, `sh -c true` on the same machine is 7.7 ms: locating the helper costs a shell spawn and
essentially nothing more. The version probe costs twenty times that, because it is a whole AppleEvent
round trip to `System Events`.

So a **first** chooser is about 175 ms dearer than a later one, on top of the ~66 ms every chooser
pays before anything is on screen. Of that, 6 ms (the library load) is the only part reachable
from Java, and `warmNative` moves it to a worker at startup. **The 165 ms version probe cannot be
warmed.** It runs inside the blocking call, in the native, guarded by a static this side cannot
reach; the only exposed entry points that reach it are the ones that put something in front of the
user. Do not describe the warm-up as pre-warming the dialog.

The toolkit's own contribution to the command is the macOS UTI expansion: a base32 of a twenty-byte
string per pattern. It is nanoseconds, and it is a correctness fix, not a cost.

---

## 4. Where the frame goes, and why it is a backend concern

The blocked call sits in the loop's **input** phase: after `glfwPollEvents` returns, before
`UiRuntime.drain()`, before the render pass. So while the chooser is up, no GLFW callback can
fire, and the two paths that normally keep a window alive while a native call is on the stack, the
framebuffer-size callback's immediate render and the window-refresh callback, are both invoked by
GLFW *from inside a poll*. Neither can run. The compositor keeps the last swapped surface, so the
window does not blank; it freezes.

It froze on the **wrong picture**. A button dispatches `RELEASE` (which clears its armed state and
invalidates) and then `CLICK`, which runs the action that opens the chooser, in the *same* dispatch,
before the drain and before the render phase. The one frame that would have shown the button
released is exactly the frame that never gets drawn. The user is left staring, for as long as they
browse, at a button still pressed.

A caller cannot fix this with `Ui.post`, because the loop order is poll → input → drain → render: a
posted dialog still opens before the frame is drawn. It has to be the backend, and the smallest seam
that works is one call at the top of each `TinyFdDialogs` entry point, paired with the UI-thread
check that was already there.

Two details of `presentBeforeBlocking` are deliberate:

- **Both buffers are filled.** After a render the swapped-out buffer holds the previous frame, and a
  layer-backed compositor can surface it between presents. The toolkit already re-presents once a
  render burst settles, for exactly that reason; a multi-second freeze is the case where getting it
  wrong is most visible.
- **No window takes vsync.** The loop grants a blocking swap to one window per iteration to pace a
  render burst. There is no next iteration to pace, so blocking on a vblank here would only delay
  the panel.

A paint that throws is contained the way the loop's frame phase contains one, and the chooser opens
regardless: a broken repaint must not swallow the dialog the caller asked for.

---

## 5. The return path heals itself, and that is a guarantee

Nothing needs resetting after a chooser returns, and this is not an observation about today's code:
`BlockingNativeCallTest` pins each part. A dialog, from this side, is a stretch of wall time with no
frames in it, ended by a focus event; a clock that jumps ten seconds and a
`windowFocusChanged(false)` reproduce it without needing a user or a second process.

- Losing focus synthesizes a key release for **every** key held, and cancels the pointer state, so
  the next move is a move and not a resumed drag. The physical key-up and mouse-up happen in the
  other program and never arrive.
- The modifier mirror is cleared on focus loss and is replaced wholesale by the next press's native
  mask, so a modifier pressed while the panel was up is not missed either.
- A `Transition` that spanned the block lands **exactly** on its target rather than past it: `t` is
  clamped to 1. And because the frame `dt` is clamped to `Scene.MAX_TICK_SECONDS`, a transition
  longer than that clamp resumes where it was instead of teleporting through the states it exists to
  show.
- Every timer that fell due during the block runs in **one** drain, in deadline order, behind
  whatever was already queued, and the loop is free to sleep again afterwards.

---

## 6. What is not decided here: an asynchronous file dialog

This ADR does not decide whether `FileDialogs` should gain a callback-shaped form, and deliberately
does not sketch one. Naming the preconditions is more useful than an API that cannot yet be honest.

Three things have to be solved first, and none of them is "move the call to a worker":

1. **The static buffer.** tinyfd's result is a pointer into a `static` C buffer and its probes are C
   statics. Two dialogs in flight are undefined regardless of which threads they are on. Whatever is
   built has to serialize the native call itself, not merely the caller.
2. **The Windows chooser is thread-bound.** It runs a nested modal message loop on its owner
   window's thread. On Windows the work cannot leave the UI thread at all, so an asynchronous form is
   a genuinely different implementation there, not the same one with a different signature, and a
   nested loop means application code can re-enter during a call that has already returned control.
3. **There is no modal gate.** A frame loop that keeps running during a chooser makes every button
   in the application clickable again, including the one that opens a chooser. The modal stack
   blocks input for windows the toolkit owns; a panel drawn by another process is not one of them, so
   something has to be added before the loop may keep running.

Whoever takes this on owns those three. Until then, the honest position is the one the member now
states: it blocks, this is what it costs, and here is which frame you are looking at while it does.

---

## 7. Verification

- **The mechanism**, re-checkable in two minutes on any macOS machine with the LWJGL tinyfd native
  in hand: `otool -L` for the single link against `libSystem`, `nm -u` for `_popen`/`_pclose` and
  the absence of every Objective-C symbol, `strings` for the AppleScript and the Linux helper names
  sharing one binary, and `javap -c` on `TinyFileDialogs` for the class initializer that loads the
  library and sets `tinyfd_winUtf8` on Windows.
- **The return path**: `BlockingNativeCallTest` (`limn-toolkit`), one test per bullet in §5; held
  keys come back released, the held button does too so the next move is a move, the modifier mirror
  is cleared and then replaced by the next press's mask, a short transition lands exactly on its
  target while a long one resumes instead of jumping, and the whole timer backlog runs in one drain.
- **The presented frame** is not covered by a test. Asserting it needs a real GL context, a real
  window and a real chooser with a real user; the seam is instead kept to one call at the top of each
  entry point, where its absence is visible by reading.
- **The costs** in §3 are a measurement on one machine on one date, not a promise. They are here to
  justify a shape, and they are the reason the member says "on the order of a quarter of a second"
  rather than a figure.
