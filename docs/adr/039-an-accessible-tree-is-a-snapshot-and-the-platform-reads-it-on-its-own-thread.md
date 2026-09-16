# ADR 039. An accessible tree is a snapshot, and the platform reads it on its own thread

- **Status:** Accepted, 2026-09-04; **implemented 2026-09-07** — all of §14, the three bridges live
  and read by NVDA, VoiceOver and Orca on the four guests. Closes the "No screen reader
  bridge" bullet the README carries in ten languages, and delivers ADR 006 §5's promise that
  accessibility labels are `I18nString`s. The work is a new `limn.accessibility` package, four
  hooks on `Widget`, one `default` member on `NativeWindow`, three bridges — **in
  `limn-backend-lwjgl`, as this line first said, after a round trip worth recording**: they were cut
  into per-platform artifacts so that an application would pay only for the one it ships on, and
  brought back when that stopped being true. What was being paid for was the native payload, and
  there is none: two of the three reach their platform through LWJGL's JNI trampoline and import
  nothing but `org.lwjgl.system`, which the backend already carries for all six targets, and the
  third is pure Java over unix sockets. Thirty-four classes with no binaries are not an artifact's
  worth of weight, and the split cost a build exception and a seam an application could forget to
  connect —, and a short list of corrections to seams that were never observed before and turn out not to fire
  (§8). §11 is what the first cut deliberately does not do; §14 is the order the work lands in. It
  lands as
  `docs/adr/039-an-accessible-tree-is-a-snapshot-and-the-platform-reads-it-on-its-own-thread.md`,
  with its `docs/adr/README.md` row landing beside it, and every section below is written to be read
  from there rather than from a draft.
- **What implementing phases 1 to 3 found, and where this record is wrong about the code.** Every
  one of these was settled the way the record asks for: the code won, and the sentence it
  contradicts is named rather than quietly edited around. Nothing here changes a decision.
  **Finding 10 is wrong on both halves.** `TextEditModel` already carries the change counter §8
  asks it to gain — `textVersion()`, a `long` bumped by every buffer mutation and by nothing else —
  and a damage record beside it, `lineDamage()`. No counter was added; the tree compares
  `textVersion()`. **§5.3's structural funnels are already covered by the damage ones.**
  `setSceneRecursively` reaches `markNeedsLayout`, and that, `setFocus`, `pushOverlay` and
  `removeOverlay` all end in `requestRender()`, so the node flag is set in one place plus the four
  funnels that deliberately buy a frame without declaring damage. **`MenuItem#activate()` is
  already reachable from `MenuSurface`**: both are in `limn.components`, and package-private was
  never the obstacle §8 describes. **§14's phase 1 cannot prove what it promises**, because §2
  carries per-interface tables and no per-role ones and §1.12 puts the role tables in the bridges;
  the enum is pinned against §1.12's own list instead. **§14's phase 3 names tests phase 4 owns** —
  live mutation over twelve components, `ListView` recycling, a playing `VideoView` — so those
  properties are pinned against purpose-built widgets driving the same hooks, and each becomes a
  component case in its own pipeline step. And **§6's zero-allocation promise was not true of the
  frame it was written against**: `Widget#paintChildren` walked its list with an enhanced `for`,
  an iterator per widget per frame, which is the cost `Scene#paintFramePass`'s own overlay loop
  already carries a comment about avoiding. It is indexed now, and the promise is measured.
- **Date:** 2026-09-04
- **Companion, and it lands in this commit.** ADR 040, *A handler answers the user, and a watcher
  hears everything*, is in `docs/adr/` as Proposed, 2026-09-03, with its own row in the index, and it
  names this record throughout. It was still being drafted when §9 was first written, so §9 is now a
  reconciliation rather than a list of requests: it says which of this record's four requirements ADR
  040 answered, answers the five open items ADR 040 addressed here — its §6.1 to §6.4 in design, and
  §6.16 by not needing it — and states plainly the one place the two records describe the same bridge
  differently and which of them governs it (§9.3). **Neither record waits on the other.**
- **Scope:** what an accessible node is, how a widget supplies one, which events exist and how they
  are coalesced, which thread each platform calls on and how that call reaches toolkit state, how
  the backend hands over a bridge, where popups and dialogs sit in the tree, what any of it costs
  when nothing is listening, and how it is tested. Braille, speech and magnification are the
  assistive technology's; this document's job is to give it something true to read. System
  accessibility *settings* — high contrast, reduced motion, a system text scale — are a different
  decision with a different shape and are not here.
- **Audience:** whoever writes the three bridges, and whoever adds a widget and has to say what it
  is. Every number in §0 was measured by the three platform spikes, on a named machine on a named
  day, and each is cited by the run that produced it rather than by a file. A constant that is not in
  this document is deliberate: the spikes established that platform constants are read off the
  machine under test, never recalled, and §12.3 keeps that rule.
- **Where the evidence is, and where it is not.** The three spikes were throwaway probes built
  outside this repository, and they do not land. What this record carries is their verdicts, each
  attributed to a named machine on a named day so that a doubtful one can be **re-measured rather
  than re-read**. The raw transcripts are deliberately not committed: they are megabytes of log from
  guests that no longer exist, carrying those guests' paths and user ids, and a record whose evidence
  is a file nobody can open is no better off than one with no citation at all. What does land is the
  half that has to be run again — the lab suite of §12.2, one directory per platform under
  `scripts/a11y/`, brought in by each bridge's own phase (§14). **No path outside this repository is
  cited anywhere below**, and none should be added: a record that points at a scratch directory stops
  being checkable the day after it is written.
- **Compatibility.** Nine releases, `v0.1.0` through `v0.7.0`, are on Maven Central under
  `io.github.limn-toolkit`, and `README.md` carries the badge and the one-dependency install snippet,
  so a stranger can already compile against the types §8 changes. What is true is narrower than
  "nothing depends on this" and is still the whole justification: **the owner knows of no consumer,
  and a `0.x` line promises no compatibility.** On that ground, where an existing API fights the tree
  the API changes rather than gaining a deprecated twin; §8 lists every such change and says why, and
  every migration §8 forces is inside this repository. ADR 040 §3.7 says what the same reasoning
  would owe once the line is `1.0`, and it applies here unchanged.
- **What in this document is a specification, and what is a survey.** The model (§1.2–§1.4), the
  identity rule (§1.3), the threading contract (§3) and the per-platform interface mapping (§2) are
  specifications: each is wrong for every widget at once if it is wrong at all, and each is written
  to be implemented as it stands. **§7's per-widget table is a survey** — the starting point each
  component's own pipeline step begins from and corrects, not a list to be implemented literally.
  §7 says so in its own words, §7.2 records the corrections already known, and §14's phase 4 is the
  pipeline that settles them one component at a time. Holding the two to the same standard is how
  this record would become untrustworthy in both directions at once.

---

## 0. What was measured before anything was decided

Three spikes were built and run: a working UI Automation provider on Windows 11 ARM64, a working
AT-SPI2 application on Ubuntu 24.04 GNOME, and a working `NSAccessibilityElement` subclass on
macOS 26.6.2. All three are Java at `--release 17` with no native code of our own. Their verdicts
are settled, and this design is built on them rather than around them.

The macOS spike was **re-run on an unlocked guest after this ADR's first draft, and then verified a
second time by an independent agent that wrote its own client from scratch and set out to break the
first one's conclusions.** Both reached the same place; the verifier corrected three of the first
agent's claims and cleared the confound that would have made all of them worthless. Two results
moved as a consequence: the end-to-end client path is proven and the pump behaviour is measured.
Findings 4, 4a, 4b and 5 are the current state, §2.2 and §3.2 are written on it, and §13 records
which of its open items that closed and which narrower ones it opened. Where a paragraph below reads
as an argument against a design this document no longer holds, that is deliberate: the discarded
design was reasonable on the evidence available, and the measurement that discarded it is the
interesting part.

**One thing must be said before any macOS number is quoted anywhere: this was one button in a probe,
not the toolkit's own tree.** The provider published a single element, under one window, and never
mutated it. Everything Finding 4 establishes about the *mechanism* is measured; everything about a
tree that changes is items 20 to 23 of §13.

### Finding 1: on Windows the platform calls us on its own threads, several at once, holding no lock

Across two client passes the provider took 2191 calls. 2031 of them arrived on UI Automation RPC
threads that LWJGL's callback trampoline attaches to the JVM as daemons — up to three live at once
— and 2007 arrived while the main thread was demonstrably not inside `glfwPollEvents`. Nothing took
a lock on our behalf. The hookup is the one exception: `WM_GETOBJECT` arrived on the GLFW main
thread ten times out of ten, every time inside the pump, because a sent message is delivered only
when the owning thread enters a message-retrieval call.

Provider state must therefore be safe to read from an arbitrary thread, without the UI thread's
cooperation and without a lock the UI thread also takes. That one measurement decides the shape of
everything below.

### Finding 2: on Windows a stalled message pump is an accessibility outage, not a frame drop

A deliberate experiment abandoned the pump for forty seconds. During the stall the provider took
one call. A fresh client started mid-stall wrote its header and then could not complete `FindFirst`
for the top-level window at all; the instant the pump resumed it ran to completion and invoked the
button. Already-attached clients keep reaching the provider on RPC threads; a client trying to
*attach* cannot, because both `WM_GETOBJECT` and the HWND host provider's own properties travel as
sent messages.

The toolkit already owns the instrument for this: the slow-handler and slow-task budgets, each 8 ms,
each logging a WARNING and counting. Those budgets are now also the accessibility budget.

### Finding 3: on Linux the platform never calls us, and describing two objects cost 46 round trips

AT-SPI2 is a wire protocol, not a C API. A pure-Java D-Bus client over
`java.net.UnixDomainSocketAddress` served `libatspi` — the library Orca is built on — and Orca
itself, with no JNI, no libffi, no LWJGL and an empty classpath. Over a full run there were 51
inbound calls from four client connections, every handler on our own reader thread; the main thread
was never re-entered. A handler must never block and must never make a blocking call on the same
connection, because the reply would arrive on the thread parked waiting for it.

The cost measurement that decides the data structure: `libatspi` made 46 synchronous round trips to
describe **two** objects, at roughly 0.67 ms each. That is why `org.a11y.atspi.Cache.GetItems`
exists, returning `a((so)(so)(so)iiassusau)` — reference, application, parent, index-in-parent,
child-count, interfaces, name, role, description, states — for the whole tree in one message. A real
tree must be able to answer it from something already assembled.

Three smaller facts from the same run. Both guests' buses are `unix:path=`, so Java's inability to
open an abstract socket did not bite. The registry writes `org.a11y.atspi.Application.Id` back to us
immediately after `Socket.Embed`, so `Properties.Set` must be answered before the tree is queried.
And `Accessible.GetState` is `au` of exactly two `uint32`, low word first — a real GTK application
answers `au 2 0 0`, and a struct of two would be wrong.

### Finding 4: on macOS the platform calls us on the thread we are already on, and only while that thread is in the pump

*This finding changed after the first draft of this ADR. The macOS spike was re-run on an unlocked
guest on 2026-09-03/04, and then re-run again by an independent verifier with its own client. The
two things the draft carried as unproven are now measured, and the three claims the verifier
falsified are corrected in place rather than left standing. The runs behind it are the re-run on the
unlocked arm64 guest on 2026-09-03/04 — a verification pass, a threading pass, a notification pass
and an unprivileged pass — and the independent verifier's sweeps with a client of its own on
2026-09-04. The first pass's own transcript is superseded by both and nothing here rests on it. Every
one of these is reproducible from `scripts/a11y/macos/` once phase 7 lands it (§12.2), which is the
form this record's macOS evidence takes.*

Every callback into Java — the runtime-added `accessibilityPerformPress` and the
`accessibilityTitle` and `accessibilityLabel` implementations — ran on the process main thread,
which under `-XstartOnFirstThread` is the Java UI thread (`pthread_main_np()` returned 1). No
thread-attach problem arose and no locking is needed, because a macOS accessibility callback *is*
the UI thread.

**The end-to-end path is now proven.** A separate-process client using the ordinary API
(`AXUIElementCreateApplication` → `AXChildren` → `AXUIElementPerformAction`) found an element named
`Probe` with role `AXButton` **nested under the window AppKit vends**, pressed it, and the press
arrived back inside Java on the main thread — logged by the Java implementation *after* the
provider announced `READY`, so the self-test's own direct message send cannot be mistaken for it.
`AXUIElementCopyElementAtPosition` found the same element (`CFEqual`) through the application
element and through the system-wide element.

**Nothing in the provider had to change to make that work**, and the list of what turned out to be
unnecessary is the useful half of the result: `setAccessibilityChildren:` on the GLFW content view
was sufficient on its own to surface the element under the *window's* children; the runtime subclass
answered role, label, title and `isAccessibilityElement` exactly as built;
`setAccessibilityFrameInParentSpace:` was the right call and surfaced correctly in screen space;
`accessibilityHitTest:` was never needed, because AppKit hit-tests from the frame itself; and the
window needed to answer nothing at all. Five suspected fixes, none of them required.

**The root confound was caught and cleared, and that is why the result can be believed.** Everything
in the lab runs through `sudo launchctl asuser 501`, which runs as **root**, and root is
accessibility-trusted whatever TCC says — so every `trusted=true` the first pass recorded was
suspect, and with it every downstream measurement. The verifier re-ran the identical client from a
plain shell as an ordinary `uid=501` user with no `sudo`: same find, same press, Java's press counter
advanced. The grant is real and not an artifact of running as root.

**And the pump hypothesis is now a measurement rather than a hypothesis.** Latency tracks the loop's
sleep, and past a threshold accessibility degrades to the point of failing:

| loop mode | a full by-name find (median) | outcome |
| --- | --- | --- |
| `glfwWaitEventsTimeout(5)` | **0.8–0.9 ms** | every find succeeded |
| `glfwPollEvents` + 16 ms sleep | **130–224 ms** | every find succeeded |
| `glfwPollEvents` + 250 ms sleep | **~1.5 s** | degraded, and every find still completed |
| `glfwPollEvents` + 2 s sleep | — | **usually `kAXErrorCannotComplete`**; across the verifier's sweeps one traversal did succeed, in 17.6 s, and the failures were consistent at ~19 s |

The provider's own counters say why: in **every** mode the run ended `axReadsWhileSleeping=1`, and
that one is the provider's startup self-read. Every client-caused read arrived while the loop was
inside the pump. In the 2 s mode nothing reached Java for 36 seconds while the client's finds timed
out, and then one pump served the whole backlog in about a millisecond — which also rules out any
out-of-process attribute cache: every value a client ever received was produced by our own code
being called.

**Two caveats the verifier established, and this record carries both rather than the tidier story.**
The first agent reported that a traversal *never* succeeds at a 2 s pump interval, with failure
latencies of 10 s and 26 s; the independent sweeps found one success and a consistent ~19 s failure,
so **the categorical claim is false** and the last row says "usually" for that reason. The design
consequence survives the correction untouched — seventeen seconds is an outage a blind user
experiences as a window with no content, and a design cannot be built on the one run that got
through. The second caveat bites harder on how these numbers may be used: **the traversal time is a
property of the client, not of the bridge.** Two clients measured against one unchanged provider
disagreed, so no figure in that table may be quoted as a cost of this bridge, in a benchmark, in a
budget, or to a reader. What the table measures is the *relationship* between the main thread's
sleep and a client's ability to finish, and that relationship is what §3.2 is built on.

Two consequences this design is built on. Idling in `glfwWaitEvents` is the *best* state, not a
risk — it is where a screen reader is served in under a millisecond. And **long synchronous work on
the UI thread is an accessibility outage on macOS exactly as it is on Windows**, with a measured
cliff somewhere between 250 ms and 2 s. The toolkit's 8 ms slow-handler and slow-task budgets sit
two orders of magnitude inside it, which is the argument for making them the accessibility budget
on this platform too.

The provider mechanism is proven: `objc_allocateClassPair` on `NSAccessibilityElement`,
`class_addMethod` with an LWJGL libffi implementation, the press callback firing for real and
returning `BOOL=true`, `NSAccessibilityPostNotification` delivering to a real `AXObserver` built on
`AXObserverCreate` — the machinery VoiceOver itself is built on — in another process, carrying the
correct element and the updated value, and — the one that decides §2.2's shape —
**`setAccessibilityChildren:` on the GLFW content view being sufficient on its own** to place a
Java-built element under the window a screen reader walks.

**One asymmetry in notification delivery, measured, and the bridge must respect it.**
`AXValueChanged` is delivered to an observer registered on the element **or** on the application
element; `AXFocusedUIElementChanged` arrives **only** on the application-element registration. A
bridge that posted focus per element would post into silence. AppKit's own encoding for `accessibilityPerformPress` is `B16@0:8`, read out
of the running AppKit rather than recalled, so the return is a C `bool` and a `char` would be wrong.

### Finding 4a: on macOS, three things AppKit does for us that a bridge would otherwise do wrong

All three come out of the same run, and each deletes code this design had planned to write.

**AppKit hit-tests our elements from their frames.** The probe never implemented
`accessibilityHitTest:` — the switch stayed off — and `AXUIElementCopyElementAtPosition` still
returned the element, `CFEqual` to the one found by name, through both the application element and
the system-wide element. Hit testing on macOS is a consequence of publishing correct frames, not a
method to implement.

**AppKit converts the frame.** `setAccessibilityFrameInParentSpace:` with `(40, 40, 160, 48)` in the
content view's own coordinates surfaced to the client as `AXFrame = rect(240, 412, 160, 48)` in
screen coordinates, for a window at `rect(200, 172, 400, 328)`. The bottom-left origin, the title
bar, and the flip against the screen are all AppKit's. A bridge that computes a screen rectangle
itself is doing arithmetic it can hand back.

**AppKit vends the window, and the walk shows exactly what it vends:** `AXWindow/AXStandardWindow`
with the window's title, `AXRaise`, its close, full-screen and minimize buttons, and an
`AXStaticText` for the title. That is no longer an assumption about what AppKit usually does — it is
this window, dumped. **The window itself needed to answer nothing of ours**: no method was added to
it, no attribute was overridden on it, and the element still surfaced under its children.

### Finding 4b: what the macOS run still does not cover

Recorded here because §13 is built from it and because a spike's silence is easy to read as
success. VoiceOver itself was never run; every client was a purpose-built `AXUIElement` consumer.
The tree was one element under one window and never mutated, so adding and removing elements while
a client is attached, destroying an element a client holds, and focus movement between elements are
all untested — and the spike names the last of those the obvious crash vector. No text, no value
setting and no selection were exercised, which is where a real bridge does its hardest work. The
guest runs a pt-BR system and **no non-ASCII string ever crossed the boundary**, which matters more
here than in most designs, because publishing localized names is half of what this ADR is for.
It is arm64 only, on macOS 26.6.2, on one machine.

### Finding 5: AppKit maps label and title to different attributes, and a bridge that guesses is silently wrong

`-accessibilityTitle` becomes `AXTitle`; `-accessibilityLabel` becomes `AXDescription`. The spike's
first client matched `AXTitle` only and had to be rewritten to match either. A model carrying one
undifferentiated "name" leaves the bridge guessing, and the wrong guess is not an error — it is an
element a screen reader cannot find.

The re-run supplied the sharpest possible evidence for this, by making the mistake itself. The
spike's threading instrumentation hooked `-accessibilityLabel`, and reported **zero** client reads
across an entire five-mode sweep — not because nothing was reading, but because a by-name walk asks
for `AXTitle` and never asks for `AXDescription`. Every threading number in Finding 4 depends on
that hook being moved. A bridge making the same choice publishes a name into an attribute the
client walking the tree does not read, and observes silence that looks exactly like an empty
application.

### Finding 6: the toolkit has no accessibility surface, and its observation seams are the wrong shape

A case-insensitive search of both modules for `accessib`, `a11y`, `UIAutomation`, `NSAccessibility`
and `AT-SPI` finds only prose. `Widget` has no id, name, role or description; its only free text is
`tooltip()`, an `I18nString` resolved under the widget's own locale, whose backing value has no
getter. `Scene` is `final`, so no bridge subclasses it and every scene-level funnel must be a change
to `Scene` itself. `Widget` is abstract and subclassable, so per-widget seams stay available where
scene-level ones do not.

What is missing, precisely. There is no focus observer: `Scene#setFocus` is private and is the
single funnel, and `Widget#notifyFocus` is the package-private per-widget one. There is no tree
observer: `Widget#onAttached` and `#onDetached` are protected, firing top-down and bottom-up
respectively, and `Widget#setSceneRecursively` and `Scene#onWidgetDetached` are the two funnels,
both package-private. There is no geometry observer: `layoutBox` is `public final` and writes the
four fields with no hook, and `moveChild` writes `x` and `y` behind even that. And `Scene#root()` is
not the whole tree — overlays are a second root set, so an in-scene dialog or menu is invisible to
anything walking the root alone.

**And there is no state observer at all.** `Widget#invalidate()` calls `Scene#damageWidget`, which
adds a clipped damage rectangle when partial rendering is on and calls `scheduleFrame()`. It does
not set `layoutDirty`, it does not call `markLayoutDirty`, and it tells nothing else.
`Checkbox#setChecked`, `Slider#apply`, every text edit and every other state-bearing setter in
`limn.components` reaches exactly that path and no other. A design that hung its accessibility flag
off the attach, focus, overlay and layout funnels — as the first draft of this one did — would raise
no event for a checkbox toggle, a slider move or a keystroke, which is three quarters of what a
screen reader exists to report. `Scene#damageWidget`, `#damageWidgetRegion`, `#damage(Rect)` and
`#markContainedLayout` are the funnel every repaint actually goes through, and §1.1 hangs the flag
there.

Every component change callback is a single-slot replace, so a bridge registering on one silently
unregisters the application's handler. That is the state of the code today and it is what the first
draft of this ADR reasoned from; ADR 040 has since decided to change it, and §9.3 says why this
design's shape did not change with it. `Widget#setInheritanceHost` — the only link from a popup,
menu or dialog root back to its opener, and the link every one of them already sets — is write-only.

### Finding 7: the actuation surface is uneven, and four entries are traps

`Checkbox#toggle()` is public and does not check `isEnabled()`, so an accessibility toggle mapped
straight onto it flips a disabled checkbox and fires the application's handler. `Slider#setValue` is
silent by design and has no user-equivalent, so an accessibility set cannot notify the application
through the toolkit at all. `Button`'s action is private with no `click()`. `MenuItem#activate()` is
package-private, and `MenuItem#hasSubmenu()` is false for an *empty* submenu while `isSelectable()`
stays true, so an action advertised from `isSelectable()` alone does nothing on those rows. And
`Scene#requestFocus` tests the widget's own `isVisible()` and its ancestry, not `isShowing()`, so an
accessibility focus request can land in a hidden subtree.

### Finding 8: the coordinate chain exists, and its two ends are UI-thread-confined

`Widget#localToSceneX/Y` sum to the parentless root. `NativeWindow#screenX/screenY` give the content
origin in native screen coordinates; `#logicalToScreenFactor()` is documented as the multiplier from
logical points to native screen coordinates — 1.0 on macOS, the monitor scale on Windows and X11 —
and already folds in `overrideContentScale`. That is exactly what each platform wants: UI Automation
takes physical screen pixels, NSAccessibility takes points, AT-SPI2 takes pixels. One multiplier,
three correct answers.

Two constraints ride with it. `LwjglWindow#screenX`, `#screenY` and `#setScreenPosition` each open
with a UI-thread check, so a foreign thread cannot ask a window where it is. And under
`supportsAbsolutePositioning() == false` — Wayland — `screenX/screenY` answer `0, 0` and the whole
chain silently yields scene-relative numbers.

### Finding 9: after layout, bounds are already physical, and reading order is already logical

ADR 032 decided there is no mirror transform anywhere: mirroring is a placement decision inside each
container's `onLayout`. The consequence is large and easy to miss — after layout `x/y/width/height`
are always physical, left-origin parent coordinates, in RTL as in LTR. Screen bounds need no
direction handling at all. Reading order, conversely, must be `children()` order, which is paint
order, which is `Scene#focusTraverse`'s depth-first order, which is logical. A tree built by sorting
nodes on x would be right in LTR and backwards in RTL.

### Finding 10: text indices disagree between the toolkit's own two models

`TextEditModel` indexes in UTF-16 `char` offsets, and its caret is a `(charIndex, Affinity)` pair,
not a bare index, because an index on a direction boundary is two points on the line.
`PreeditEvent#caret()` and `#blockSizes()` are in code points. `NSRange` is UTF-16; AT-SPI2's `Text`
counts characters. Whichever unit the tree standardises on has to be stated once and converted at
exactly one boundary, or emoji and CJK extension text produce silently wrong carets.

`TextEditModel` also has no change counter and no damage record. A text event's insert and delete
offsets therefore cannot be read off the model; they have to be computed.

### Finding 11: what a client would find in the demo today, and what CI can gate

The demo's de-facto status bar is a muted `Label` whose text is replaced by hand, and nothing marks
it as anything. Icon-only buttons — the tool bar's, the media transport's, the tabbed pane's strip
buttons, a text field's trailing button — carry a tooltip or nothing. `ListView` rows are real
widgets that are pooled and rebound, so accessible identity cannot be widget identity. Menus are a
parallel model: `Menu` and `MenuItem` are not widgets, and the tree cannot be derived from the widget
tree alone.

CI runs on `ubuntu-latest` only, under `xvfb-run`, with no session bus and no AT-SPI registry, and
there is no macOS or Windows runner in any workflow. `checkArchitecture` scans test sources too and
forbids `org.lwjgl.*` outside `limn-backend-lwjgl` by import line. `StubWindow` and `RecordingWindow`
both answer a zero screen origin and a unit factor, so no headless double as it stands can support an
assertion about a real screen rectangle.

### Finding 12: neither spike needed a native shim, and the one dependency that could replace one costs 458 KB and a logging facade

The Windows provider is 25 vtable slots served by 16 libffi closures over five call interfaces, in
806 lines of Java, using nothing outside `org.lwjgl.system`. The Linux client is 719 lines for the
D-Bus layer and 65 KB of class files with an empty classpath, validated by 104 offline assertions
including seven golden messages captured off the wire from the reference implementation. The
evaluated alternative, `com.github.hypfvieh:dbus-java` 5.2.0 on its native-unixsocket transport, is
468,798 bytes across four jars, and `dbus-java-core`'s `module-info` requires `org.slf4j`, so a
toolkit that ships no logging facade would gain one.

Against that, ADR 037 prices the alternative shape exactly: the one native shim this project owns
needs its own repository, a five-runner matrix, six classifier jars, a consumer rehearsal, Central
credentials and an ABI handshake — and ADR 028 §4 already calls a JNI payload in
`limn-backend-lwjgl` "the first native payload" there.

### Finding 13: one wake that does not fire, and one callback that tells nobody

`UiRuntime#post` enqueues and then wakes the native loop **only when the caller is not the UI
thread**. That is right for every caller that exists today: a UI-thread post happens inside a drain
or inside an input dispatch, and the loop re-reads `nanosUntilNextDeadline()` — which returns `0`
while immediate work is queued — before it sleeps again. It is wrong for exactly one shape: code
running on the UI thread *while the loop is parked inside the pump*, that is not itself a GLFW
event. A macOS accessibility callback is that shape and is the only one in the process.
`LwjglBackend#runEventLoop` parks in `glfwWaitEvents()` with no timeout whenever no window has asked
for a frame, and an AX request serviced by the run loop inside `nextEventMatchingMask:` produces no
`NSEvent`, so the pump does not return and the queued task waits for unrelated input.

**The obvious fix — wake unconditionally — is not free, and the count that would have justified it
is wrong.** The toolkit and the backend hold 22 `Ui.post`/`Ui.postDelayed` call sites between them,
not 14, and they are not all event-shaped. `TextField` and `TextArea` blink the caret through a
*self-rescheduling* `postDelayed` whose javadoc says why — "so a focused field lets the event loop
sleep between blinks instead of pinning it at the frame rate" — and `Spinner`, `VideoView`,
`MediaControls` and `Scene`'s tooltip dwell all re-arm themselves the same way (an earlier draft
counted `ScrollBar` among them; its one delayed post is a hold-expiry check armed at most once, and
its track press pages once with no repeat — §7.2). An
unconditional wake makes every one of those posts write a native event from inside the drain the
loop is already awake for. §8 takes the narrower fix instead.

`LwjglWindow#windowMovedTo` is the whole of the window-position callback: it updates `lastScreenX/Y`
and moves child popups. It requests no frame and notifies nothing else. The content-scale callback
beside it does set `frameRequested`. A window drag therefore changes every screen rectangle this
design publishes, and produces no frame in which to say so.

### Finding 14: four components whose real shape the tree has to match

**Every `Scene` overlay is modal.** `pushOverlay`'s contract is "a full-scene modal layer painted on
top of everything: it captures all input and confines focus"; hit-testing never reaches the content
beneath it, `focusTraverse` collects from `inputRoot()`, and `requestFocus` refuses a widget outside
it. So "what a modal blocks" is already computed exactly, once, and is one private method away.

**`PopupMenu.MenuSurface` is the size of the whole scene.** Its `onMeasure` returns the maximum
constraints; its `onLayout` sets its bounds to the scene in the in-scene mounting, and it is the
popup window's root in the native one. The menu a user sees is `Column`, which holds `x`, `y`, `w`,
`visibleH`, per-item `top[]` and `hgt[]`, and `highlight` — a plain `int`. The rectangle of a menu,
the rectangle of a row, and the identity of the row the user is on are all in `Column`, and none of
them is in the widget's box.

**`ListView` mounts cells directly.** `Map<Integer, Widget> mounted` is data index → child widget,
and `Adapter#rowAt`/`#recycle` pool them. There is no per-row wrapper widget, and this design does
not add one.

**`PasswordField`'s masked line carries the secret, and its index space is the secret's.**
`shapeDisplay` returns `ShapedText.uniform` over **the model's own text**, so the caret, the click
mapping and the selection band need no translation — the class's javadoc calls the count-preserving
substitution the old mask string needed "not a thing that can go wrong any more, because there is no
second string". The dots are then *drawn*, one per caret stop, never typeset. Two consequences a
facet must respect, and they pull in opposite directions. The painted mask is one cell per grapheme
cluster, so its length is `caretCount() - 1` and an astral character is one dot where the model has
two `char`s — publishing model offsets beside a dot-per-grapheme string would run the caret past the
end of it. And `shapeDisplay`'s result carries the secret as its `text()`; the class marks that line
`TRAP` in its own source, because the only guarded call in the file is the one that would paint it.
Anything reading that line for the tree has to take its caret stops and never its text (§7).

---

## 1. Decision

### 1.1 The tree is an immutable whole-window snapshot, published by the UI thread, read by anyone

The toolkit builds `limn.accessibility.AccessibleTree` — every node of the scene root and its
overlays — and publishes it through a `volatile` reference. Bridges read it from whatever thread the
platform gave them, with no lock and no hop.

Finding 1 and Finding 3 decide this together, and it is the only shape that serves both. On Windows
the platform calls us on RPC threads while the UI thread sleeps; there is nothing to synchronise
with, and the alternative — posting to the UI thread and waiting — has no upper bound, because the
UI thread parks with no drain for the whole life of a native file dialog. On Linux the platform never
calls us at all, but `Cache.GetItems` wants the entire tree in one message, and 46 round trips for
two objects is what pulling per property costs.

The correctness argument for staleness is short: **a snapshot goes stale only while the UI thread is
running, and the UI thread publishes before it goes back to sleep.** The publish happens inside the
frame, after `layoutPass` and `updateHover` — bounds are settled there and hover has already moved
whatever state it moves — and before the paint passes. Every change that matters to an assistive
technology already schedules a frame, so the frame is the flush point and no new scheduling is
invented. An application that mutates without invalidating gets no accessibility event, which is the
same defect ADR 023 already names and already tests.

**The frame is the flush point because damage is the trigger.** Finding 6 is the reason this has to
be said explicitly: the accessibility dirty flag rides `Scene#damageWidget`, `#damageWidgetRegion`,
`#damage(Rect)` and `#markContainedLayout` — the funnel `Widget#invalidate()` goes through and
therefore the funnel every value, state, text, caret and selection change in `limn.components` goes
through — together with the structural funnels that do not damage anything (`setSceneRecursively`,
`setFocus`, `pushOverlay`/`removeOverlay`, `markLayoutDirty`, `onWidgetDetached`) and the explicit
`Widget#invalidateAccessible()` for a change that is neither painted nor structural. Hanging the flag
on the structural funnels alone would mean a checkbox toggle raises nothing, because a toggle is a
repaint and nothing else.

**Damage is a coarse trigger, so the publish step compares before it publishes.** A caret blink, a
hover ripple and a chart tween all damage something and change no accessible fact. The walk
therefore writes into a **scratch buffer the scene owns and reuses**, comparing each field against
the published snapshot as it writes; if nothing differs, there is no snapshot, no publish and no
event. Only a difference costs the immutable copy a reader may hold. Names are compared by *source* —
the `I18nString` reference, the node's locale and `I18n.epoch()` — and the previously resolved string
is carried over when all three match, so a repaint does not re-resolve every name in the window. §6
prices each state and §12.1 measures it.

**A field may only be compared by something cheaper than producing it, and text is where that rule
bites.** Deciding that a text node is unchanged must not require materialising its text — and today
it would: `TextEditModel#text()` is `buffer.toString()`, a fresh `String` on every call, so a caret
blink in a window with three text fields would allocate three strings per frame to conclude that
nothing changed. That is not a rounding error against §6's promise, it is a direct contradiction of
it, and it is the shape of defect `AccessiblePublishCostTest` exists to catch. So the same
compare-by-source rule that covers names covers text: `TextEditModel` gains a **revision counter**, a
`long` bumped by every mutating operation (§8), the published node carries the revision its string
came from, and the walk compares two `long`s. The string is produced only when the revision moved —
which is also the only frame on which a `TEXT_CHANGED` diff is wanted. `PasswordField`'s masked line
is derived from the same model and rides the same counter. The general rule, for whoever adds the
next facet: **if a field's comparison allocates, the field needs a cheaper witness, and a monotonic
counter on the model is usually it.**

**A derived string has no source to compare, and a formatted name or value text is where that
bites.** `I18nString#get()` caches its resolution by epoch and locale, so comparing a name by source
costs a reference comparison and re-resolves nothing. But `I18nString#format(Object…)` is documented
as *never* cached — "the arguments vary per call, and caching them would be caching the wrong thing" —
and `Spinner`'s value text is a `String.format` through `I18n.localizeDigits`. A widget that formats
*inside* `onAccessibility` has therefore already allocated by the time any comparison runs, and the
quiet frame stops being free: a spinner, a progress bar and a slider with a read-out would allocate a
string each per damaged frame to conclude that nothing changed. That is `TextEditModel#text()`'s
defect in a different costume, so it takes the same cure. **A derived string is never built in the
walk. The widget hands over the string it is already holding, together with the witness it cached it
against** — `a.name(String cached, long witness)` and `a.valueText(String cached, long witness)`,
compared as two `long`s and never as two strings. `Spinner` already keeps exactly that cache for
painting, keyed on the value, the locale and the epoch, so that `onMeasure` cannot drift from
`paintValue`; §8 gives that cache a counter and gives every widget that formats the same shape. A
name that is *not* derived stays an `I18nString` and is compared by source, which is every name in
`limn.components` except these. §6 states the promise in exactly those two cases and no wider, and
`AccessiblePublishCostTest` carries a `Spinner` for the same reason it carries a `TextField`.

**The facets are records in the published tree and columns in the scratch buffer, and that
distinction is what makes the quiet frame free.** A facet is immutable because a reader on an RPC
thread holds it (§1.2); but a `new ToggleFacet(...)` per node per walk would allocate on exactly the
frames §6 promises allocate nothing, and a caret blink in a window of two hundred nodes would then
be two hundred short-lived records. So the `Accessibility` builder's facet setters take the facet's
*fields* — `a.toggle(state)`, `a.value(v, min, max, step, text)` — and write them into the scratch
buffer's parallel columns, where they are compared like every other field. The immutable records are
materialised in step 6 of §5.3, in the copy, and only for nodes that carry them. A widget's
`onAccessibility` therefore never constructs a facet, which is also one less thing for it to get
wrong.

**Both platforms that can be asked before a frame exists may build one on the spot.** On macOS an AX
callback *is* the UI thread (Finding 4); on Windows `WM_GETOBJECT` arrives on the UI thread inside
the pump, 10 times out of 10 (Finding 1). At those two points, and only there, a bridge may call
`Host#republishNow()` (§5.2) and answer from what it gets — exact freshness for one build per burst.
This is not a nicety on Windows: it is the only way the first client is answered at all (§3.1). Such
a build walks and publishes; it does **not** lay out and does not render, so it is safe from inside a
native callback and keeps the reentrancy rule §12.1 asserts. **Such a build requests a frame whenever
it published anything**, whether or not the layout under it was dirty, because a reentrant publish
defers registry work to that frame and something has to buy it (§5.2, §5.3). If layout happens to be
dirty at that moment the bounds it publishes are the last laid-out ones, so the corrected tree and
its `BOUNDS_CHANGED` arrive on the same frame it has already asked for. The data structure and the
reader code are identical on all three platforms.

### 1.2 A node carries a role, a name with provenance, states, bounds, and typed facets

A node is `role` + `name` + `nameFrom` + `description` + `locale` + `states` + `bounds` +
`relations` + zero or more **facets**. A facet is a small immutable record describing one behaviour:
`ToggleFacet`, `ValueFacet`, `SelectionFacet`, `SelectionItemFacet`, `ExpandFacet`, `TextFacet`,
`ScrollFacet`, `WindowFacet`, `ActionFacet`.

Facets exist because the three platforms disagree about where behaviour lives, and the disagreement
is not cosmetic. UI Automation has no "checked" property: checked-ness lives inside
`IToggleProvider::get_ToggleState`, reached through `GetPatternProvider`. AT-SPI2 has no toggle
interface: checked-ness is a state bit read by `Accessible.GetState`, and the verb is a row in
`Action.GetActions`. NSAccessibility has neither: it is `accessibilityValue` answering `@0`/`@1`/`@2`
plus `accessibilityPerformPress`. A flat boolean forces the UIA bridge to fabricate a pattern; a
pattern list forces the AT-SPI bridge to fabricate a state. `GetPatternProvider` and
`Accessible.GetInterfaces` are mirror images of one another, and one facet set answers both.

The facet is the single source and each of the three views is computed from it, so a state bit that a
facet also expresses is derived, never stored twice. `State` therefore holds only what all three
platforms carry as a flag: `ENABLED`, `FOCUSABLE`, `FOCUSED`, `VISIBLE`, `SHOWING`, `SELECTABLE`,
`SELECTED`, `CHECKED`, `MIXED`, `PRESSED`, `EXPANDED`, `HAS_POPUP`, `READ_ONLY`, `EDITABLE`,
`MULTI_LINE`, `PASSWORD`, `INVALID`, `REQUIRED`, `BUSY`, `MODAL`, `ACTIVE`, `DEFAULT`, `HORIZONTAL`,
`VERTICAL` — with `CHECKED`, `MIXED`, `EXPANDED`, `SELECTED` and `READ_ONLY` derived from their
facets. **Corrected while implementing:** `READ_ONLY` derives from the text facet *or* the value
facet, and `ValueFacet` carries a `readOnly` field for it. It had none, so a progress bar's range
was indistinguishable from a slider's: the facet's presence is what advertises a set on every
platform, `state(READ_ONLY)` was dropped as derived, and a bridge built from §2's rows would have
vended a writable `RangeValue` whose set landed in the inherited hook, refused in silence.

**Amendment, 2026-09-14: a derived state is refused by the setter, not dropped by it.**
`Accessibility#state` used to return in silence for the five facet-derived bits and the five the
publish step inherits, on the reasoning that one fact keeps one home. The silence was the defect:
`CalendarView`'s chooser marked its cell on show `CHECKED` for months and no reader ever heard it,
because nothing said the call did nothing (MODEL-NEW-1, settled facet-state-setter). The setter now
throws `IllegalArgumentException` naming the facet or the step that owns the bit, the way `action`
already refuses a verb that takes an argument, so the next dead line fails the first test that runs
it. The two dead calls found by the audit are gone; `AccessibleModelTest` pins the refusal for every
owned state and that a widget's own state still lands.

**Amendment, 2026-09-14: `EXPANDABLE` joins the closed list, derived from the expand facet's
presence.** The list above says `EXPANDED` derives from `ExpandFacet`; what it could not say was that
a node *can* open, which is a fact AT-SPI2 carries as a state of its own (`EXPANDABLE`, bit 9, and
`COLLAPSED`, bit 5, read off the Fedora KDE 44 guest, libatspi 2.60.6, on 2026-09-13; CRIT-9) and
which Orca 50.2 speaks as "collapsed" on a closed row — nothing, until then, distinguished a tree row
that is closed from a leaf that cannot open (L3). Windows and macOS already carry the fact through
the ExpandCollapse pattern and the disclosure attribute, and change nothing here. The rule is one
rule for every node (decision 41): **every node with an `ExpandFacet` is `EXPANDABLE`** — menu
titles, submenu rows, combo boxes, tree rows and the calendar's title alike — and a widget can no
more set it than it can set `EXPANDED`. The Linux bridge maps `EXPANDABLE` to bit 9 and derives
`COLLAPSED` = `EXPANDABLE` ∧ ¬`EXPANDED` in its state set (`AtspiStates`); the state-changed
event for the derived bit is the Linux lane's (phase 3). `STATE_CHANGED(EXPANDABLE)` comes from the
diff like every other bit. The demo transcript prints it, so every golden line carrying an expand
facet gained the word. The closed list of states, for `AccessibleModelTest` to read as it reads
§1.12's roles:

```text
ENABLED, FOCUSABLE, FOCUSED, VISIBLE, SHOWING, SELECTABLE, SELECTED, CHECKED, MIXED, PRESSED,
EXPANDED, EXPANDABLE, HAS_POPUP, READ_ONLY, EDITABLE, MULTI_LINE, PASSWORD, INVALID, REQUIRED,
BUSY, MODAL, ACTIVE, DEFAULT, HORIZONTAL, VERTICAL
```

**Amendment, 2026-09-14: the facet list is closed, checked, and twelve long.** The nine facets the
first paragraph names were nine when it was written; ADR 041 added `TableFacet` and `CellFacet`
without touching the sentence, and nothing read it (MODEL-NEW-5). Today a twelfth joins:
`HierarchyFacet(level, row, rowCount)`, a tree row's depth from one and its flat index among the rows
the outline shows open (decision 4; D1's facet half). It exists beside `SelectionItemFacet` rather
than inside it because the two answer different platforms — UI Automation's `Level` and AT-SPI2's
`level` attribute want the depth and the spoken *n of m* wants the siblings, while AppKit's
`accessibilityIndex` wants the flat row — and a virtualized outline cannot rebuild the flat index
from structure, because the rows above a realized one may not be in the snapshot at all. What
`SelectionItemFacet` counts is now written on the facet: **the numbers a screen reader speaks**, each
widget saying which set — a tree row counts its siblings (the Tree lane changes its numbering; until
then it still counts the visible rows), a calendar day counts the days of its month (decision 37; the
dates lane), and a zero publishes nothing on any platform (semantics 6): a radio outside a group is
"0 of 0" in the model and silent at the reader. `Tree` publishes the new facet on every row today;
nothing else does yet. The closed list of facets, which `AccessibleModelTest` compares with the
`*Facet` records in `limn.accessibility`:

```text
ToggleFacet, ValueFacet, SelectionFacet, SelectionItemFacet, ExpandFacet, TextFacet, ScrollFacet,
WindowFacet, TableFacet, CellFacet, HierarchyFacet, ActionFacet
```

**Amendment, 2026-09-15 (decision 36's carrier): `CellFacet` gains a sort direction, and the closed
list does not move.** A sorted column's direction was published only as a localized phrase in the
header's description, which the three bridges each read and could not use: two of them carry a
direction as an enumeration (Orca's `sort` object attribute, `AXSortDirection`) and would have had
to parse a translated sentence back into one. So `CellFacet` becomes
`(int row, int column, Sort sort)` with `Sort` = `NONE | ASCENDING | DESCENDING`, meaningful on a
header cell (row `-1`) and `NONE` everywhere else — **no new facet and no new `State`**, so the list
above and §1.1's state list stand as they are. `Accessibility#cell(row, column, sort)` declares it
and the two-argument call is the same call with `NONE`; the differ compares it, so a column turned
round republishes even when nothing else about the node moved.

The phrase **stays** in the description beside it, and that is not duplication: a bridge reads the
snapshot on a platform's own thread with no locale scope open and could not build a phrase there, and
Windows's carrier *is* a phrase (`ItemStatus`, File Explorer's convention, plus `HelpText` because
NVDA 2024.4.2 has no `ItemStatus` handler and speaks a description). The enumeration serves the two
platforms that want a fact, the phrase the one that wants words, and each bridge takes the one its
platform asks for. No new string: `TableStrings.SORTED_ASCENDING` and `SORTED_DESCENDING` already
ship in all 21 locales. Orca's fourth value, `other`, is left out: nothing in the toolkit sorts that
way, and a value no widget can produce is one no bridge could be tested against.

*(Amended 2026-09-15, the fix round's integration: all three bridges now read it, each the way its
platform asks — Windows `ItemStatus` from the description beside `HelpText` (§2.1), macOS
`AXSortDirection` 0/1/2 (§2.2's table, ADR 041 §7), Linux the `sort` object attribute (§2.3). Each
lane had logged its own mapping as owed, because this carrier landed on the model's branch while the
three ran; none of the three could compile against it there.)*

**Amendment, 2026-09-14: a `ValueFacet` can say it holds no number, and a value event is raised
when the text moves.** A date segment nobody has typed into has a range, a displayed text and no
number; the facet had no way to say so, so `DateField` published the minimum as if it were typed
(DATES-NEW-8), and the differ compared numbers only, so a segment filled with a digit that happened
to be its minimum — or a combo option renamed at the same index — raised nothing (CRIT-4).
`ValueFacet` gains `empty` (decision 16): an empty value keeps `min`, `max`, `step` and `text`,
carries the minimum as its number for the two platforms whose value interfaces have no "none" (UIA
`RangeValue.Value`, AT-SPI `CurrentValue` — the bridge answers the minimum, which is now what the
facet holds), and says empty through the flag, the text and the events. `Accessibility#emptyValue`
declares it; `#value` declares a number. `VALUE_CHANGED` is raised when the number, the text or the
emptiness moved; the event carries the two numbers and a bridge that speaks the text reads it off
the tree it is handed with the event, which is the tree the event came from (§1.10). Windows raising
the `Value` string property as well as `RangeValue` for a node that vends both is the Windows lane's
(CRIT-4's second half); `DateField` publishing empty is the dates lane's, with the spoken word of
decision 53.

**Amendment, 2026-09-14: a selection member belongs to a container, or declares that it does not.**
The container of a node carrying `SelectionItemFacet` is the nearest ancestor with a
`SelectionFacet`, reached by climbing from its published parent only through synthetic ancestors
that lack one (semantics 1 of the 2026-09-13 pass; resolved once at publish, read by the differ's
`SELECTION_CHANGED` target, UIA `get_SelectionContainer` and `GetSelection`, AT-SPI `Selection`
membership and macOS `AXSelectedChildren` — the bridge readings are phase 3's, the differ's target is
M3's). A `RadioButton` has none: a `ButtonGroup` is not a node, and its published parent is whatever
layout ancestor survived transparency — a column, a tab panel, the window — on which the differ laid
its `SELECTION_CHANGED` and Windows raised `ElementSelected` (CRIT-6). So `SelectionItemFacet` gains
`containerless`, `Accessibility#containerlessSelectionItem` declares it, `RadioButton` declares it
grouped and standalone alike, and a containerless member raises no `SELECTION_CHANGED` anywhere: its
own `STATE_CHANGED(SELECTED)` is the whole of the announcement. Its numbers are unchanged — position
and size of the group, or `0`/`0` standalone — and **a position, size or level of zero publishes
nothing on any platform** (semantics 6: UIA `VT_EMPTY`, no AT-SPI attribute, no AppKit override),
which is the bridge lanes' to apply and this record's to state; the §7 `RadioButton` row is
corrected accordingly.

**Amendment, 2026-09-14: `SelectionFacet` carries the container's shape and no cursor.** It used
to carry an `activeDescendant`, resolved by the publish step per container as the first `ACTIVE`
node anywhere in that container's subtree. That put the cursor in the wrong home twice over: a
combo's overlay layer and its list, or a menu surface and each of its columns, each resolved the
same row and each raised its own `ACTIVE_DESCENDANT_CHANGED` for one arrow key (MODEL-NEW-3); a
`DateField`, which holds no selection, had nowhere to put its caret segment at all (D3); and a
list nobody was in handed whatever container enclosed it a cursor nobody had. The cursor is now
the tree's fact — `AccessibleTree#activeDescendant()`, §1.10's amendment of the same day — and
the facet is `(multiSelectable, required)`. No bridge read the field (verified by grep before it
went); the transcript prints `active=` on the focused node's line instead of the container's.

`ENABLED` and `READ_ONLY` are separate bits and are never conflated. Every platform separates them —
UIA has `IsEnabled` against `ValuePattern.IsReadOnly`, AT-SPI2 has `SENSITIVE`/`ENABLED` against
`READ_ONLY`/`EDITABLE`, AppKit has `accessibilityEnabled` against the text attributes — and merging
them makes a disabled field announce as read-only and leaves a genuinely read-only enabled field
inexpressible.

**Amended 2026-09-15 (semantics 5, the disabled axis; fix round 2d): the bits are still separate,
and a node that is not `ENABLED` publishes `READ_ONLY` too.** The two remain two bits: a genuinely
read-only enabled field publishes `READ_ONLY` alone, and nothing reads `ENABLED` off `READ_ONLY` or
the other way. But a node that is not `ENABLED` publishes no setter (§1.5's amendment of this date),
and `READ_ONLY` — on a text, and the `ValueFacet`'s read-only flag it derives from on a value — is
the only way the snapshot withdraws `SET_TEXT` and `SET_VALUE` (§1.9). So a disabled field, and one
under a disabled ancestor, now publishes `READ_ONLY` beside its `EDITABLE`, as a field outside the
layer that owns input has since fix round 2c (§1.13), and both come back off together when it is
enabled again. The cost this paragraph named is real and is accepted for the snapshot to be the one
authority: a reader may say "read only" beside "unavailable" on a disabled field. Whether one does
on each platform is a phase-5 live-reader question.

**Amended 2026-09-15 (fix round 2e; the 2d review's third finding): the amendment above is
withdrawn, and the first paragraph stands as written.** A node that is not `ENABLED` keeps its true
`READ_ONLY`, and its value its true writability: a disabled field publishes `EDITABLE` without
`READ_ONLY`, as it did before fix round 2d, and so does a field outside the layer that owns input,
as it did before fix round 2c. What withdraws the setter is the other bit. Semantics 5 is amended
instead — a writable `ValueFacet` or a `TextFacet` without `READ_ONLY` implies its setter only on a
node that is `ENABLED` (§1.5's amendment of this date; `AccessibleNode#accepts`) — and every bridge
refuses a setter on a node without that bit while answering read-only from the facet. `ENABLED` is
already the operable bit on both axes the scene refuses on: the walk publishes it clear on a
disabled widget, under a disabled ancestor, on a synthetic child its owner narrowed, and on every
node outside the layer that owns input (§1.13). No second bit is added. The reader cost the
amendment above accepted goes with it.

`VISIBLE` and `SHOWING` are also separate, because offscreen is not invisible: a scrolled-away list
row is visible and not showing. UI Automation takes that as `IsOffscreen`; AT-SPI2 takes it as
`STATE_SHOWING` off while `STATE_VISIBLE` stays on. `Widget#isShowing()` is the predicate that
already does the clipping walk.

**`ENABLED`, `FOCUSABLE` and `VISIBLE` are inherited down the walk and are not read off the widget,
and getting that wrong is the difference between a tree that agrees with the keyboard and one that
lies.** `Widget#isEnabled()`, `#isFocusable()` and `#isVisible()` each answer that widget's **own
flag** and consult no ancestor — only `#isShowing()` walks the chain. But `Scene#focusTraverse`
prunes: its `collectFocusable` returns at the first ancestor that is not visible or not enabled, so
disabling a form's container removes every control inside it from the Tab order. A tree that read the
three predicates directly would publish every one of those controls as `ENABLED` and `FOCUSABLE` —
a screen reader would offer the whole disabled form, announce each field as operable, and have each
invocation refused by §1.9's gate with nothing said about why. It is §1.13's modal defect again,
arrived at from inside the widget tree instead of from an overlay.

So the walk carries two booleans down and ANDs them: a node is `ENABLED` only if it and every
ancestor answer `isEnabled()`, `VISIBLE` only if it and every ancestor answer `isVisible()`, and
`FOCUSABLE` only if it answers `isFocusable()` **and** the carried enabled-and-visible flag is still
true — which is `collectFocusable`'s prune, stated as a state rule. `SHOWING` stays `isShowing()`,
which already walks. The carried flags are exactly where §1.13's modal subtraction lands too, so the
two rules are one mechanism and one traversal, and `AccessibleFocusOrderTest`'s equality becomes an
identity rather than a coincidence that holds until someone disables a `Row`.

**Amendment, 2026-09-14: a synthetic child may narrow `ENABLED`, and only narrow it.** The rule
above carries the owner's enabled bit down onto every synthetic child, which is right for a menu row
inside a disabled menu and wrong for a day the calendar's bounds refuse inside an enabled calendar,
or a scroll chevron with nothing left to scroll: those published `ENABLED` and carried no verb, and a
reader arrowing onto one heard an operable cell that did nothing (DATES-NEW-6, decision 30;
SegmentedControl's dead chevron). `Accessibility#disabled()` is the answer, in `#offScreen()`'s
shape and for its reason: a declaration from inside the synthetic child, clear by default and
cleared for every child, that the publish step ANDs into the inherited bit — so a child can be less
enabled than its owner and never more, and the owner's bit stays the keyboard's. A disabled child
still carries no verb of its own accord; that stays the widget's to leave out, as the refused day
leaves out `SELECT`, and a bridge reads the absence (semantics 5). `CalendarView` and
`SegmentedControl` using it are the widget lanes' changes; `AccessibleModelTest` pins the narrowing
both ways. *Corrected later the same day:* the first cut ANDed the child's flag with the *widget's* bit alone, so a synthetic child nested inside a disabled synthetic child published `ENABLED`; it narrows through the nesting now — a cell of a refused row is refused with it — and `AccessibleModelTest` pins that too. `SegmentedControl`'s dead chevron uses it (and keeps its `PRESS`, as a disabled `Button` does). *Corrected 2026-09-15:* the dead chevron no longer keeps its `PRESS` — a disabled synthetic item carries no verb (semantics 5), the refused day's shape; the verb had been kept only so that a pattern set Windows froze on first read (W2) would not lose Invoke when the side came alive, and phase 3 rebuilds pattern sets on a change. *Recorded the same day (the widgets review):* the narrowing does not reach a widget a container hangs under a disabled synthetic node with `Accessibility#under` — the widget takes its enabled bit from the widget tree, as the scene's gate does — and no widget combines the two (`Table` is the only caller of `under` and never disables a row; the refused day, month and chevron hang no widget), so the first widget that does owes the walk and the gate that narrowing in the same change.

The whole node is a value. It holds no `Widget` reference and no `Runnable`, because a UI Automation
client can hold an element for minutes and a snapshot that pinned a detached subtree through it would
be a leak the garbage collector cannot see.

### 1.3 Identity is minted over the widget tree, never over the published tree, and survives frames

Screen readers hold references: UI Automation caches by a `RuntimeId` integer array, AT-SPI2 by a
D-Bus object path, NSAccessibility by a retained Objective-C object. Identity is therefore a
first-class property of the model, not a bridge's problem.

**The rule that decides everything else in this section: a node's identity is a function of the
widget tree alone, and never of the published tree.** An earlier draft keyed a node by
`(published parent id, local key)`, and that was a fatal defect rather than an infelicity. §1.6's
transparency verdict is computed from *runtime state* — a `Column` that gains a name through
`setAccessibleName`, a `Stack` that is made focusable, a group that acquires a non-default state
stops being transparent and starts being published. Under a published-parent key, the frame in which
that happens re-keys **every node in the subtree beneath it**, because each of their published
parents changed. A screen reader experiences that as every element it is holding becoming invalid at
once, in the middle of reading — and it is caused by a property change on an ancestor the user never
touched. Identity has to be independent of the accident of which ancestors are currently interesting
enough to publish.

So: every node has a process-wide `long id` from one monotonic counter, minted on first publication
and keyed by **(owner id, local key)**, where the **owner** is the widget or synthetic node that
*declared* this node — a widget-tree fact that no transparency verdict, no hoisting and no overlay
push can change. There are three sources for the local key, in this order:

1. **A key the parent widget chose for this child widget**, supplied from `onAccessibilityChild`
   through `Accessibility#key(long)`, scoped by that parent widget's own id. A container that
   recycles its children owns their identity and nothing else can.
2. **A key the owner chose for a synthetic child** — a model index for an indexed child, a menu
   item's minted serial for a menu row, a series index for a chart series — scoped by the owner's
   own id. Synthetic children nest, and each level scopes under the level above, so a menu row inside
   a submenu column is keyed the same way at any depth.
3. **A per-widget serial** from a `WeakHashMap<Widget, Long>` in the tree builder, created when a
   bridge first attaches. This is the default and covers every ordinary widget, and it is scoped by
   nothing at all: the widget object *is* the key.

**Rule 3 is what makes the rule at the top of this section true**, because a widget's own serial
depends on no ancestor whatsoever. Rules 1 and 2 need a scope, and they take it from the *owner's*
id, which by rule 3 is itself ancestor-independent — so the whole chain is. Three consequences worth
stating, because each is a case an earlier draft got wrong:

- **A transparency flip re-keys nothing.** A container that starts or stops being published changes
  the tree's *shape*, which is a `STRUCTURE_CHANGED`, and changes no identifier at all. That is the
  whole point.
- **Moving a widget in the widget tree keeps its identity**, because it is the same object. A screen
  reader is told the structure changed and finds the element it was holding still valid, which is
  what it would want.
- **The `ListView` case is unaffected by the change**, because a pooled cell's identity was never its
  own serial — it is the data index its owner assigns, scoped by the list's serial, and the list's
  serial does not move.

**Rule 1 is what makes `ListView` correct, and it exists because rule 3 cannot be.** Finding 14: the
list mounts pooled cells directly as its own children, `mounted` is data index → widget, and there is
no per-row wrapper widget — this design does not invent one, because §7.1 admits only widgets and
synthetic children and a synthetic node with widget children would be a third kind. So `ListView`
keys each mounted cell by its **data index**: the cell recycled from row 3 to row 9 is minted row 9's
identifier, and a cell that returns to row 3 gets row 3's back out of the intern table. A model that
keyed a node by the widget it came from would tell the assistive technology that row 3 had become
row 9; keying by the pooled widget's serial is precisely that model.

The same rule retires the one place an object was standing in for a key. A menu row is keyed by a
`long` minted on the `MenuItem` at construction, not by its identity hash: `System.identityHashCode`
is 32 bits and collides, and the collision here is two menu rows becoming one element (§8).

**There is no packed ordinal and no reserved zero.** An identifier is never derived by putting a
child's index in the low bits of its owner's identifier: index 0 would then be indistinguishable from
the owner, and that identifier is the whole of identity on every platform — the `RuntimeId`, the
object path, the key of the macOS element map — so the collision would be a merged element rather
than a cosmetic clash.

Path keys are interned in a bounded per-owner table so that a row scrolled away and back keeps its
identifier. Eviction is least-recently-published and emits `NODE_DESTROYED`, so a client holding an
evicted element sees it go away rather than go wrong. The `WeakHashMap` means a widget never meeting
an assistive technology allocates nothing, and a detached-then-reattached widget keeps its identity
because it is the same object.

`AccessibleIdentityTest` pins the rule directly, and it is the test this section exists for: a scene
in which a transparent ancestor is given a name mid-run publishes a *different shape* and the **same
identifiers** for every node below it, and the emitted events are structural rather than a wave of
destructions.

Each bridge derives its own form: `SAFEARRAY(VT_I4){UiaAppendRuntimeId, hi, lo}`, which UI Automation
prefixes with the HWND's own runtime id — the spike confirmed that contract;
`/org/a11y/atspi/accessible/<id>`; and an `NSAccessibilityElement` subclass instance carrying the id,
recovered through a map from its pointer, which is the shape the Windows spike already used to
recover a Java object from a COM interface pointer.

A node that leaves the tree does not vanish from the platform's view. Its Windows element survives
until its refcount drops and answers `UIA_E_ELEMENTNOTAVAILABLE` meanwhile; its AT-SPI path answers
`org.freedesktop.DBus.Error.UnknownObject` after a `Cache.RemoveAccessible` signal; its macOS element
is posted `NSAccessibilityUIElementDestroyedNotification` and released.

**Amendment, 2026-09-14: "process-wide" is now true by construction, and the number says which
window.** The paragraph above promised one monotonic counter for the process and the code kept one
per scene, so two windows minted the same identifiers and a relation could not name a node in another
window at all — which §1.11 and §5.4 require of a native popup's `POPUP_FOR`, and which the
2026-09-13 audit found unbuilt (CRIT-2, LINUX-NEW-8). The counter stays per scene, not for any
threading reason — every scene walks on the one UI thread (`Scene.perform` posts through
`Ui.post`; `UiRuntime.isUiThread`), which is also what lets a walk read another scene's last walk
in `resolve()` and `expectMirror` without a lock — but because the tag makes a shared counter
unnecessary: `mint()` stays a local increment, with nothing to contend for and no registry to keep
in step. What changes is the shape of the number. **Every builder takes a tag when it is created,
one per scene for the life of the process, and every identifier it mints is that tag above a
forty-two-bit serial.** So an identifier names a node in any window of the process and never
collides across scenes, and `AccessibleTree#sceneTag()` and `#holds(id)` answer "is this one of
mine?" from the high bits alone — no registry, nothing kept in step with a publish, nothing allocated
on a frame. `find(id)` stays per tree: `holds` says which tree to ask, `find` says whether the node
is in that snapshot. A hand-written identifier (a test's `1000`) has tag `0` and belongs to no
scene. The bridges' derived forms are unchanged in shape — a `long` split into two `int`s, a decimal
path, a map key — and only grow in magnitude. `AccessibleModelTest` pins both halves: two scenes
minting side by side never hand out one number twice, and a relation naming a node published in
another scene is kept where one naming nothing published anywhere is still dropped.

**Amendment, 2026-09-14: rule 1 is answered before the child describes itself, everything inside a
keyed child follows its key, and a widget may hang under a synthetic node.** Three corrections to
the rule above, decided together (decisions 3 and 33 of 2026-09-13; MODEL-NEW-2, MODEL-NEW-9,
MODEL-NEW-10, TABLE-NEW-1, TABLE-NEW-7).

- **The key comes from a hook that runs first.** Rule 1's key was supplied from
  `onAccessibilityChild`, which runs after the child's own hook, so the walk began every keyed node
  under the widget's serial and re-keyed it afterwards. Two things went wrong under that order.
  The child's name was looked up under the serial, which the previous frame never published under,
  so every keyed row missed, scanned the whole previous walk and re-resolved on every walk — correct
  output, quadratic cost, invisible from outside. And a synthetic child the cell declared in its own
  hook was interned under the serial, because `child(key)` reads the owner's identifier before the
  re-keying. So the parent now answers a child's identity from `onAccessibilityChildIdentity`,
  which runs *before* the child's hooks, and `Accessibility#key(long)` refuses to be called from
  anywhere else. The node is begun under its final identifier; `AccessibleIdentityTest` proves the
  carry-over by counting it, since no output can show it.
- **Everything inside a keyed child takes its identity from the key.** The paragraph above said a
  cell recycled from row 3 to row 9 is minted row 9's identifier, and that held only for the cell's
  own node: a label inside a composite cell, or a synthetic child it declared, kept one identifier
  across rows and told a reader row 3's badge had been renamed rather than replaced. Now a synthetic
  child of a keyed node is scoped under the keyed identifier (which the first correction gives for
  free), and a widget descendant of a keyed node is identified as (nearest keyed ancestor's
  identifier, its own serial) through the same intern table. A widget moved out of a keyed subtree
  changes identifier, which is the rule stated rather than an exception to it: its identity was the
  row's, and it left the row. `AccessibleIdentityTest` pins the composite case both ways.
- **A widget may be a child of a synthetic node when its owning container names that node as its
  host row.** The sentence "a synthetic node with widget children would be a third kind" is
  withdrawn (§7.1 records the same). The identity hook lets the parent say
  `Accessibility#under(rowKey)`: the child is begun under that synthetic node of the parent's own
  walk, its identity is scoped under the row's identifier rather than the parent's — so `Table`'s
  widget cell is keyed by its column alone, and the packed (row, column) key it needed while the
  table's node scoped it is gone — and among the row's cells it takes its place by column when it
  and they carry a `CellFacet`. Its verbs stay its own: the walk still routes an action on it to the
  widget, never to the owner's synthetic hook, because what changed is where the node hangs and not
  who performs for it. A key is therefore unique among the host's children of *both* kinds: a widget
  cell keyed 2 and a synthetic cell keyed 2 under one row would be one element. Naming a row the
  parent did not declare in this walk is a defect the walk reports, as `endChild` without a child
  is, and not a child quietly published elsewhere. `TableAccessibilityTest` pins the row, the order
  and the recycled identity.

**Amendment, 2026-09-14: the intern table is bounded by what is live, and eviction raises
nothing.** The paragraph above said "bounded", "least-recently-published" and "emits
`NODE_DESTROYED`", and each was wrong in a way that mattered (MODEL-NEW-7). The table held 4096
pairs and, when full, dropped every pair stamped with the oldest generation — which, on the walk
that overflowed, was every pair of the previous publish, including the ones that walk had not
reached yet. A table realizing nine rows over five hundred columns therefore minted fresh
identifiers on every frame and published on every frame with nobody touching anything. Now a pair
is stamped with the walk that looked it up, and when the table is full it drops only the pairs
neither this walk nor the previous one asked for — a row scrolled away two frames ago or longer —
and grows when there are none, because a pair dropped then is one this walk is about to ask for
again. Growth is an allocation, and it happens only on a walk that is minting, which is a walk that
publishes; a quiet frame finds every pair and allocates nothing, which
`AccessiblePublishCostTest` and `Table`'s quiet-table ratchet keep true. And eviction emits nothing,
as the code always said: a node that left the tree was destroyed by the difference between two
trees when it left, and the entry existed only so its identifier could come back.
`AccessibleModelTest` walks five thousand keyed rows twice and asserts the same identifiers and no
`STRUCTURE_CHANGED`; `TableAccessibilityTest` does the same through a wide table.

### 1.4 The snapshot stores links, not child lists

Each node holds `parent`, `firstChild`, `lastChild`, `nextSibling` and `previousSibling` as array
indices into the tree. `IRawElementProviderFragment::Navigate` takes `NextSibling` and
`PreviousSibling` from a node the client is already holding, and a children-list model makes every
step scan the parent. The links cost four ints per node and remove that scan on every platform.

### 1.5 A widget supplies its node through four protected hooks

```java
protected void onAccessibility(Accessibility a) { }
protected void onAccessibilityChild(Widget child, Accessibility a) { }
protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) { return false; }
protected boolean onSyntheticAction(long key, Accessible.Action action, Accessible.Argument arg) { return false; }
```

They follow `onPaint`, `onMeasure` and `onKeyEvent` exactly, and each runs on the UI thread inside
`I18n.pushScope(locale())`, the way `tooltip()` already does.

**Amendment, 2026-09-14: five hooks, because identity is answered before description.** A fifth
hook, `onAccessibilityChildIdentity(Widget child, Accessibility a)`, runs before the child's own
hook and is the only place a parent may call `Accessibility#key(long)` and `#under(long)`; the
identity key moved there from `onAccessibilityChild`, for the reasons §1.3's amendment of the same
day gives. `onAccessibilityChild` keeps everything else it wrote — role, position, selected state —
and refuses the key; and the identity hook refuses everything but the key and the host, because
the node open while it runs is the parent's, so a role or a name written from it would have landed
there silently (`AccessibleModelTest` pins both refusals). The paragraph below still reads "and its
identity key" for `ListView`'s `onAccessibilityChild`, as it was decided; since this amendment the
key is the identity hook's, and the rest of that sentence stands.

`onAccessibility` fills in this widget's node and declares its synthetic children.
`onAccessibilityChild` lets a container add what only it knows about a child — `ListView` gives a
mounted row cell the role `LIST_ITEM`, its selected state, its position in the set **and its identity
key** (§1.3); `TabbedPane` numbers its headers; `ContextMenus.ContextRegion` declares its own node
and writes nothing onto its child. **Corrected while implementing:** the wrapper was to have put
`HAS_POPUP` and `ActionFacet{SHOW_MENU}` on the child it wraps, and that is undispatchable — the
walk records a node's owner as the widget it came from and the scene dispatches strictly to that
owner, so the verb would reach an arbitrary application widget whose hook answers false. Every
`onAccessibilityChild` in the toolkit writes facts; none writes a verb, and §7.2 records why. The `Accessibility` builder is allocated
once per publish and reused down the walk, and its setters write primitives into the scratch buffer's
columns rather than taking constructed records (§1.1), so describing a node allocates nothing at all.

The two action hooks are separate deliberately, and the separation is the point: an action addressed
to the widget itself and an action addressed to a synthetic child cannot be distinguished by a
sentinel key, because a model index of zero is a legitimate key. **The widget performs its own
action.** That is what makes an assistive technology's set-value tell the application: `Slider`
reaches its own private user-equivalent path with the from-user flag set, `Checkbox` keeps its own
enabled guard, `Button` reaches its private action, and `MenuItem` is activated through the owner
that already knows whether the row is selectable. No component gains a public `click()` that would
have to re-derive a guard the component already has.

**Amendment, 2026-09-14: the verb vocabulary is thirteen, and what a node accepts is what it
publishes.** Three rules, decided on 2026-09-13 and 2026-09-14, written here because §1.5 is where
the hooks that publish and perform a verb are decided.

*The selection verbs (decision 10).* `SELECT` means what a click means: make this node the
selection, whatever was selected before, in a single- or a multi-select container alike. A new
thirteenth parameterless verb, `ADD_TO_SELECTION`, means the platforms' "add to selection" — keep
what is selected and add this — and `DESELECT` means remove this and keep the rest. A container
publishes `SELECT` on a member where its mode allows it, `ADD_TO_SELECTION` on an *unselected*
member and `DESELECT` on a *selected* one **only when it is multi-selectable**, so a reader is never
offered an add on a list that can hold one. Each platform's "add" entry point tries
`[ADD_TO_SELECTION, SELECT]` in that order and its "remove" tries `[DESELECT]` (semantics 5): UIA
`ISelectionItemProvider::AddToSelection`, AT-SPI `Selection.SelectChild` and
`Table.AddRowSelection`, macOS `setAccessibilitySelected:`. The verb exists from this amendment; the
publishers are the widget lanes' (`Table` first, then `ListView` and `Tree` under decision 20), and
the platform mappings are the bridge lanes' — until then Windows still routes `AddToSelection` to
`SELECT`.

*`FOCUS` on an item (decision 11).* On a widget `FOCUS` is the walk's free verb below. On an item
inside a container it means *move the cursor here without selecting*, and it is published only where
the cursor and the selection are separate things — a tree row, a table cell, a calendar day, a date
segment, a menu row, a combo option. Where they are one thing (a list row, a segmented control's
segment) it is refused, because a focus that selected would be `SELECT` under another name, and a
reader that offered both would be offering one action twice. Only `SELECT` and `FOCUS` move a
cursor; `EXPAND` and `COLLAPSE` act like the triangle and leave it (decision 20).

*What a node accepts (semantics 5; CRIT-1's answer, decision 2).* The published snapshot is the only
synchronous authority a bridge has — `Host#perform` answers true after the membership check and
posts, and a later refusal on the UI thread reaches no platform (§1.9). So **a node accepts exactly
the parameterless verbs in its `ActionFacet`, published by its current state, plus `SET_VALUE` where
a writable `ValueFacet` exists and `SET_TEXT`/`SET_CARET`/`SET_SELECTION` where a non-read-only
`TextFacet` exists; no facet implies any other verb, and every bridge refuses the rest synchronously.**
A widget that answers a synonym publishes the synonym: a menu title that opens on `EXPAND` as well as
`SHOW_MENU` says so in its action list (the widget lanes' change, with the goldens), and a check row
that toggles publishes `TOGGLE`. The routing of a verb a container claims on a widget child is the
next amendment; the ratchet that performs every unpublished verb on every gallery node and asserts
nothing moved is the one after it.

**Amendment, 2026-09-14: six hooks, because a container may claim a verb on a widget child and
the walk routes it (decision 7; T3, W6, TREE-MISS-8).** A sixth hook,
`onAccessibilityChildAction(Widget child, long key, Action action, Argument arg)`, is the verb
counterpart of `onAccessibilityChild`. From that describe hook, and from nowhere else, a container
calls `Accessibility#delegate(Action)` for a parameterless verb of the child that is the
container's to perform — `ListView` delegates `SELECT` on every mounted row, `Tree` on every row
while its selection mode is not `NONE` (the first users; the rest of decision 20's row verb set is
the widget lanes'). The walk publishes the verb in the child's `ActionFacet` like any other, so the
platform sees it on the row it addresses and NVDA's own "select this item" reaches the element it
is sent to (§11's reversal of the same day), and records the container as that verb's performer.
The node stays the child's: `Scene#perform` gates the verb on the child's node exactly as it gates
every verb — showing, enabled through every ancestor, not modal-blocked, inside the input root —
and then, with one more check that the container is still the child's parent, routes it to the
container's hook with the child and the identity key the container gave it in
`onAccessibilityChildIdentity` (`0` when it gave none). The child's other verbs still reach its own
`onAccessibilityAction`; a delegated verb never does. Four refusals, all loud in the way
`Accessibility#action`'s misuse already is (an exception in a test; inside a walk it goes through
`Crashes.dispatch(ACCESSIBILITY, …)`, which logs and keeps the previous tree): a verb both claim —
the child in its own hook, the container by delegating it — because a cell that is a `Button`
keeps its `PRESS` and a container that claimed the same verb would have stolen it in silence, so
one verb with two performers is refused rather than routed to either (the conflict §7.2's
"`ListView` per-row actuation" row named, settled); a delegation from outside
`onAccessibilityChild`; a verb that takes an argument; and the free-verb case below. The sentence
above — "every `onAccessibilityChild` in the toolkit writes facts; none writes a verb" — stands
for verbs *written* with `action`, which a child hook still must not do because the scene would
dispatch them to the child; `delegate` is the route that was missing.

*The free verbs, as an explicit rule.* `FOCUS` and `SCROLL_INTO_VIEW` on a **focusable widget
node** are the walk's: the publish step offers them after both describe hooks ran
(`Accessibility#freeVerbs`), the scene performs them through `requestFocus()` and
`revealInView()`, and no hook — the widget's own or a container's — is ever asked. A container may
therefore not delegate either on a focusable child: the walk refuses, because the scene would
already perform the verb and a second performer is the conflict above. On a child that is not
focusable — a plain row cell — neither verb is free, so a container may delegate `FOCUS` there
where decision 11 allows it (a tree row, where the cursor and the selection are separate things)
and it reaches the container like any delegated verb. Pinned by `AccessibleModelTest`
(`aDelegatedVerbIsPublishedOnTheChildAndAVerbBothClaimIsRefused`), `AccessibleActionTest` (the
routing, the key, the gate on the child and on the container still holding it),
`ListViewAccessibilityTest` and `TreeAccessibilityTest` (a row's `SELECT` selects that row, from
the user, once).

**Amendment, 2026-09-15, superseded the same day by decision 66 (the amendment below): a delegated
verb is gated on the container, not the child.** Recorded whole because it is the step that found the
defect; what it got right is that the gate belongs on the container, and what decision 66 corrected
the same day is the bit it read the container with — `SHOWING` rather than visibility through the
ancestry, so a container merely scrolled away refused a verb it had published instead of revealing
and performing it. Read the paragraph with that correction applied throughout. "Gates the verb on the child's node exactly as it gates
every verb" above made the kept cursor row of decision 22 — published outside the viewport, not
`SHOWING`, still `ACTIVE` — a row whose `SELECT`, `ADD_TO_SELECTION`, `DESELECT`, `EXPAND`,
`COLLAPSE` and `FOCUS` (`Tree`) and `SELECT` (`ListView`) `Host#perform` accepted and the posted
task then dropped: the reader was told yes and nothing happened, which semantics 5 forbids (the
published list is the only refusal a platform sees); the widgets lane had exempted `SCROLL_INTO_VIEW` alone.
A delegated verb is the container's to perform, so `Scene#perform` now refuses it when the
container is not showing or the child is hidden by its own flag, and no longer when the child is
merely clipped out of the container; a delegated `SCROLL_INTO_VIEW` keeps its visibility gate
(§1.9's free-verb exception), and every other gate — enabled through every ancestor, not
modal-blocked, inside the input root, the container still the child's parent — is unchanged. A
`ListView`'s `SELECT` on the row already selected reveals it, as `Tree`'s does. Pinned by
`AccessibleActionTest` (a clipped child of a showing container performs; a hidden child and a
child of a container clipped away do not), `TreeAccessibilityTest` and
`ListViewAccessibilityTest` (the kept row's verbs, wheeled out of the box).
**Amendment, 2026-09-14: a composite says which child carries a label bound to it (decision 55;
DATES-NEW-12).** A form's caption is bound to the widget the application holds — a `DatePicker` —
and the node a reader arrives at is the field inside it, so `Label#setLabelFor(picker)` named an
unfocusable group and left the focused field nameless. One more answer hook on `Widget`, beside
`accessibleLabelText()`: `protected Widget accessibleLabelTarget()`, `this` by default. A composite
answers the descendant that carries a label bound to it; the walk then names that descendant from
the label (`NameFrom.LABEL`) with the `LABELLED_BY` relation back, resolves the label's own
`LABEL_FOR` to the same node (the relation resolver is handed the kind for this one reason), and
leaves the composite's node without either, so a group that declares nothing else is transparent.
The answer may redirect again — a composite inside a composite — and the walk follows the chain;
a descendant with a caption of its own keeps its own over one sent down. Two refusals, loud the
way every walk refusal is (the scene dispatches the walk's exception as an accessibility crash and
keeps the previous tree): a target outside the composite's own subtree, and a chain that never
ends. Nothing is inferred: the redirect is the composite's declaration, as the binding is the
application's. The carrier is decided per walk without allocation — a small reused stack of the
captions in flight down the tree. The consumer is the dates lane's: a single `DatePicker` answers
its field; a range picker keeps the caption on the group and names its fields "Start date" and
"End date" (decision 55). Pinned by `LabelForAccessibilityTest` (the redirect, the chain, the
child's own caption winning, the stranger refused).

**Amendment, 2026-09-14: the verb policy is ratcheted over the gallery.** `limn-demo`'s
`VerbPolicyRatchetTest` performs, on every node of every `AccessibilityGallery` entry, every
parameterless verb the node does not publish, through the same `Host#perform` a bridge uses, and
asserts that nothing moved: every window's transcript is the same afterwards and the scene
announced no change. A node that accepts an unpublished verb is listed there by the item that
owns the fix, and an entry whose node no longer accepts anything unpublished is refused as stale,
so the list can only shrink. Its first run found three shapes: `MenuBar` titles and a `PopupMenu`
submenu row accept `EXPAND` and `PRESS` as synonyms of `SHOW_MENU` (CRIT-1; the widget lane
publishes them, per the rule above — done later the same day: the titles and the rows publish
`EXPAND` while closed and `COLLAPSE` while open, refuse `PRESS`, and their three lines are
struck; a check row publishes `TOGGLE`, which no gallery entry opens a menu to show), and every
`Table` cell and column header accepts `SELECT`,
which only the row publishes — and reads the cell's key as a row index, so a select on cell (0, 1)
selects row 1 (TABLE-NEW-13, the Table lane's). Two expectations of the pass were not borne out and
are not listed: the open `DatePicker`'s group refuses everything under its modal overlay (the
closed picker is the dates lane's gallery entry to add, and the ratchet covers it the moment it
exists), and `CalendarView`'s title refuses `EXPAND` rather than accepting it — WINDOWS-NEW-10 is
a bridge vending a pattern for a verb the node does not publish, which semantics 5 closes on the
bridge side.

**Amendment, 2026-09-15: beneath the layer that owns input the walk publishes no verb, and the
ratchet runs twice.** The rule above had a hole the widgets could not close one by one: the scene
refuses every verb on a node outside its input layer (§1.9), while the node still published the
verbs its own state gave it — a menu-bar title under an in-scene cascade `COLLAPSE` or `SHOW_MENU`,
a `ComboBox` or a `DatePicker` field under its in-scene list or calendar `COLLAPSE`, the owner of a
native dialog every verb it had — each answered "accepted" from the snapshot and dropped. §1.13's
amendment of this date moves the rule to the walk, and the per-widget gate that had covered one
case of it (`DateField`'s segments and its `COLLAPSE` beneath the picker's in-scene popup, ADR 042
§8) is gone, its disabled half kept. `VerbPolicyRatchetTest` performs its complement a second time
with every `ComboBox`, `DatePicker`, `MenuBar` and `ColorPickerButton` in a gallery entry presented
in the scene and a menu bar's first menu opened, and in both runs holds §1.13's rule on the entry's
window at rest — outside the `MODAL` subtree, and anywhere in a window a native modal blocks, no
node publishes a verb, a writable value or editable text — with the entries whose second run has
an overlay open named in the test, so the rule cannot pass over a scene with nothing covering it.
**Amended 2026-09-15 (fix round 2e):** the setter half reads "accepts no `SET_VALUE` and no
`SET_TEXT`" (`AccessibleNode#accepts`), not "publishes no writable value or editable text": a node
there keeps the writability it has, and what the ratchet holds is its missing `ENABLED` (the last
amendment of this section).

**Amendment, 2026-09-15 (semantics 5, the disabled axis; fix round 2d): a node that is not
`ENABLED` publishes no verb, no delegated claim, no key binding and no setter.** The same hole on
the other axis: the scene refuses every verb on a disabled widget and on anything under a disabled
ancestor (§1.9's ancestor loop), and such a node still published what its state gave it —
`components.txt`'s `button "Disabled" [disabled] actions: press` and `check box "Locked (disabled)"
[disabled, checked] toggle=on actions: toggle`. The rule is now one test in one place: the walk calls
`Accessibility#inoperableAt` on every node whose published `ENABLED` is clear — the widget's node
when the flag the bit is published from is false (disabled, under a disabled ancestor, under an
overlay host that is disabled, or outside the layer that owns input), and each synthetic child after
its bit is settled, which also covers a child its owner narrowed through `Accessibility#disabled`
(a refused day, a dead chevron) and one nested under such a child. The routing table for delegated
verbs is read before, so a claim is withdrawn from the snapshot and not from the scene. What the
published `ENABLED` reads is at least as narrow as what §1.9's gate reads, so the published list is
never wider than what the scene performs. Local gates that only restated the rule are gone:
`DateField`'s disabled segment gate and its enabled test on `COLLAPSE`/`EXPAND`, `CalendarView`'s
enabled tests on the title, the paging buttons, the days and the chooser cells (a refused day and a
dead month now declare their verb and are narrowed; the `FOCUS` test on a refused day stays, because
in `NONE` no day is narrowed), `SegmentedControl`'s dead-chevron branch, and `DatePicker`'s calendar
button; hooks keep their own guards. `components.txt` moved on exactly those two lines; no other
transcript holds a disabled node outside a blocked window. Pinned by
`AccessibleModalTest.aDisabledNodePublishesNoVerbNoClaimNoKeyBindingAndNoSetter` and the disabled
cases of the component tests. **Corrected the same day (the 2d review):** "under an overlay host that
is disabled" is struck. An overlay's host is neither the overlay nor an ancestor of it, and the
keyboard and the pointer never read it; §1.9's amendment of this date gives the reason and the rule.
**Amended the same day (fix round 2e):** "no setter" no longer means the walk rewrites a facet. The
node's value and text keep their writability, and the node accepts no setter because it is not
`ENABLED` (the last amendment of this section); `Accessibility#inoperableAt` withdraws the verbs,
the delegated claims and the key binding and nothing else.

**Amendment, 2026-09-15 (fix round 2d): the ratchet holds both directions and a fade-out frame.**
The central rule only withdraws, so a walk that withdrew every verb, with a gate refusing every one,
passed both it and the complement. `VerbPolicyRatchetTest` now also asserts, in the run with the
surfaces in the scene, that a node inside the open layer publishes a verb that moves something when
performed; holds the central rule on every window an entry ends up with and not only its first; and
for a combo's list and a dialog with no controls of its own, in both runs, closes the surface through
the verb it publishes and samples one frame of the fade-out, where nothing still drawn of the surface
may publish a verb but the scene's free pair on a focusable widget (a dialog's own controls stay
operable through its fade and are not held to this). The first sample found the closing combo list still
offering its options' verbs in a window of its own and `CANCEL` on its in-scene layer, both fixed
the same day (§7's `ComboBox.PopupPanel` and `ComboBox.ScenePopup` rows). **Amended the same day
(the 2d review):** the sample recognised a fading surface by a `DIALOG` or `LIST_ITEM` node, so a
surface with neither was out of its reach; it now recognises the surface by where it was at rest —
the `MODAL` node's subtree, or a window beyond the first — and two more entries are sampled with
only their dismissal held (no `CANCEL` and no `COLLAPSE` on the closing surface), because the
controls inside keep performing their verbs through the fade: an open `DatePicker`, whose in-scene
layer published `CANCEL` through its fade (§7's `DatePicker.ScenePopup` row), and an open
`ColorPickerButton`.

**Amendment, 2026-09-15 (fix round 2e), widened the same day by decision 66: a facet implies its
setter only on a node that is `ENABLED` — and, from the decision-66 amendment below, `VISIBLE` too.**
Read "`ENABLED`" as "`ENABLED` and `VISIBLE`" everywhere in this paragraph, including its
"marks exactly the nodes §1.9's gate refuses on" clause; the reasoning below is unchanged by the
widening, which added an axis rather than replacing one.
Semantics 5 above says a writable `ValueFacet` implies `SET_VALUE` and a non-read-only `TextFacet`
implies `SET_TEXT`, `SET_CARET` and `SET_SELECTION`. Fix rounds 2c and 2d kept that sentence and
took the setters off an inoperable node by publishing its value read-only and its text `READ_ONLY`,
which conflated the two bits §1.2 keeps apart and reversed §7's `TextField` warning ("never
`READ_ONLY` from disabled"): a reader would say "read-only" of a field that is only disabled, or
only behind a dialog. The sentence now reads: **such a facet implies its setter only on a node that
is `ENABLED`.** Whether a node is operable is read off the snapshot
alone and needs nothing new. For the input-layer axis four carriers were weighed — a new `State`
(an inert bit), a flag on `AccessibleNode`, an empty action list, and `ENABLED` itself — and
`ENABLED` is taken. §1.13 has
cleared it on every node outside the layer that owns input since the record was written, and the
walk of fix round 2d publishes it clear on a disabled widget, under a disabled ancestor and on a
narrowed synthetic child, so it marks exactly the nodes §1.9's gate refuses on; it allocates nothing
and adds nothing to the platforms' state mappings, where a new `State` would join §1.2's closed list
and need a truthful mapping on all three; a flag on the node would be a second name for the same
fact; and an empty action list cannot be the fact, because a node with no verb may still take a
setter. `AccessibleNode#accepts` is the rule in one call — membership in the `ActionFacet` for a
parameterless verb, the facet and `ENABLED` for a setter — and the toolkit's own readings use it:
`VerbPolicyRatchetTest` holds §1.13's rule as "accepts no `SET_VALUE` and no `SET_TEXT`", and the
component tests assert it. `Accessibility#inoperableAt` keeps withdrawing the verbs, the delegated
claims and the key binding, and no longer touches a facet. §1.9's gate is unchanged: it already
refused every setter on a disabled widget, under a disabled ancestor and outside the layer, and a
comment there now says the missing bit stands for that refusal. Every bridge refuses a setter on a
node without `ENABLED` and answers read-only and settable from the facet: Windows's `Value.SetValue`
and `RangeValue.SetValue` answer `UIA_E_INVALIDOPERATION` (0x80131509) there, a minimal refusal
marked for phase 3's full candidate gate; Linux serves no `Value` or `EditableText` interface and
macOS installs no `setAccessibilityValue:` yet, so neither can post a setter, and phase 3 owes each
the same refusal when it serves them. `kitchen-dialog.txt` moved back on the 28 lines fix round 2c
gave `read-only` (§1.13's amendment of this date); no other transcript holds an inoperable node with
a value or a text. Pinned by
`AccessibleModalTest.everythingOutsideTheOpenLayerPublishesNoVerbAndNoSetter` and
`aDisabledNodePublishesNoVerbNoClaimNoKeyBindingAndNoSetter`, which now also send both setters and
assert the scene performed neither;
`TextFieldAccessibilityTest.aDisabledFieldIsStillEditableNeverReadOnlyAndAcceptsNoText` and
`aFieldBeneathAnInSceneOverlayIsNeverReadOnlyAndAcceptsNoText`; `SliderAccessibilityTest`'s disabled
case; and `UiaSetterRefusalTest` in the backend.

**Amendment, 2026-09-15 (after fix round 2e): a caret move and a selection are reading.** Semantics 5
tied `SET_CARET` and `SET_SELECTION` to a non-read-only `TextFacet`, and `AccessibleNode#accepts`
copied that, so a reader could not move the caret or select in text it may read but not write,
while every text widget performs both through its own caret whether or not it may be written
(`TextAccessibility#perform`) and §1.9's gate never asks. Corrected: **`SET_TEXT` needs a `TextFacet`
without `READ_ONLY`; `SET_CARET` and `SET_SELECTION` need any `TextFacet`; all three need `ENABLED`.**
`AccessibleModalTest.aReadOnlyTextTakesACaretAndASelectionButNoTextAndADisabledOneTakesNone` pins it,
and was red with the round-2e rule.

**Amendment, 2026-09-15 (decision 66, phase-3 fix round): the operable bit is `ENABLED` *and*
`VISIBLE`, and a delegated verb on a container that is only scrolled away is revealed rather than
refused.** §1.9's amendment of this date gives the rule and the reasoning; four facts recorded in
*this* section are reversed by it, and this is where they were written.

1. **The delegated-showing amendment above is superseded.** "`Scene#perform` now refuses it when the
   container is not showing" read the right question through the wrong bit. A container merely
   clipped out of a viewport is visible through its ancestry, and a verb published on its child is
   one the snapshot promised a reader; refusing it on arrival is the silent drop semantics 5 forbids,
   which is the very thing that amendment was written to close on the child's side. The gate now
   reads **visibility through the ancestry** for every verb — free, delegated and plain alike — and
   where the node is visible and not showing it **reveals and then performs**: `revealInView()` on
   the container for a verb the container claimed, which is exactly the box whose `isShowing()` that
   amendment refused on. A container on the glass holding a child outside its own viewport still
   reveals nothing, so decision 22's kept cursor row is expanded and selected where it stands, and
   only a container that is itself off the glass moves. What stays refused is what nobody can see: a
   child hidden by its own flag, and now also a child of a container that is not visible. The test
   the paragraph above names was renamed with the policy —
   `AccessibleActionTest.aDelegatedVerbOnAChildOfAContainerClippedAwayIsRefused` is now
   `…IsRevealedAndPerformed` and asserts the row was selected where it used to assert that nothing
   was — and `aDelegatedVerbOnAChildOfAnInvisibleContainerIsRefusedAndPublishesNothing` is its twin,
   holding the other half. A delegated `SCROLL_INTO_VIEW` keeps its visibility gate, and it is no
   longer an exception to anything: every verb has that gate now.
2. **"Such a facet implies its setter only on a node that is `ENABLED`" now reads "only on a node
   that is `ENABLED` and `VISIBLE`."** `AccessibleNode#accepts` reads both bits through one private
   `isOperable()`, and §1.9's gate re-reads the same fact on the live widget, so the snapshot and the
   scene stay one reading of one fact. A slider in a tab nobody selected keeps its writable value and
   its true `READ_ONLY` — the two bits §1.2 keeps apart are still apart — and accepts no
   `SET_VALUE`. The amendment directly above travels with it: `SET_CARET` and `SET_SELECTION` need
   any `TextFacet` and the operable bit, which is now both states, because moving a caret is reading
   and there is nothing to read in text nobody can see. **Not `SHOWING`:** a node that is visible and
   merely clipped keeps every setter, because the scene reveals it and performs.
3. **The four-carriers rationale keeps its conclusion and loses one clause.** `ENABLED` is still the
   carrier chosen for the input-layer axis, for the three reasons given there: a new `State` would
   join §1.2's closed list and need a truthful mapping on all three platforms, a flag on the node
   would be a second name for one fact, and an empty action list cannot be the fact because a node
   with no verb may still take a setter. What no longer holds is "so it marks exactly the nodes
   §1.9's gate refuses on": since this date the gate refuses on visibility too, and it is `ENABLED`
   **and** `VISIBLE` together that mark exactly that set. The third axis needed no carrier of its
   own — `VISIBLE` has been published on every node from the widget's own predicate since the first
   walk (§1.2, and "what a widget gets for free" below) — which is why it cost nothing the other two
   had not already paid.
4. **The free verbs' exception is gone; only their reveal is still theirs.** "The gate in §1.9 needs
   one exception for them, and only one … so the two are gated on visibility instead" (below)
   describes the gate as it was: now every verb is gated on visibility, so `FOCUS` and
   `SCROLL_INTO_VIEW` are no longer exceptional there. What remains theirs alone is the reveal —
   `SCROLL_INTO_VIEW` *is* the reveal and `requestFocus()` reveals on arrival — so the gate leaves
   them to it rather than revealing twice.

The withdrawal happens in the same place as the other two axes, which is the point of it:
`AccessibleWalk` calls `Accessibility#inoperableAt` on `!ownEnabled || !ownVisible` for the widget's
own node and on `!isEnabledAt(i) || !ownVisible` for every synthetic child it drew, reading
visibility from the **owner** because a child may narrow enabled (`Accessibility#disabled`) and
showing (`#offScreen`) and there is no narrowing of visible. `Accessibility#inoperableAt`'s and
`#isEnabledAt`'s contract javadoc name the third axis as of this amendment. Pinned by the tests
§1.9's amendment of this date lists, and ratcheted over the gallery by
`VerbPolicyRatchetTest.everyPublishedVerbMovesSomething`.

**What a widget gets for free, with no override at all:** bounds from `x/y/width/height`; `ENABLED`,
`FOCUSABLE`, `FOCUSED`, `VISIBLE` and `SHOWING` from the existing predicates; `locale()` for the
node's language; children from `children()` in tree order; the `FOCUS` and `SCROLL_INTO_VIEW`
actions when it is focusable, because `requestFocus()` and `revealInView()` exist for every widget;
and a name from `tooltipSource()` when nothing else supplied one.

**Free means the scene performs them, not that some widget will.** Both were advertised on every
focusable node from the first walk and performed nowhere: the action hook's default refuses, no
component wrote the two lines, and so a reader's request for focus was accepted and dropped
everywhere in the toolkit, and every `ScrollIntoView` failed. They are the walk's verbs, so the
answer is the scene's, taken instead of the widget's hook rather than after it — a widget that
answered for an action it never declared would silently swallow a verb the tree had promised, and
both routes are the scene's own in any case, since `requestFocus()` and `revealInView()` are final.
The gate in §1.9 needs one exception for them, and only one: a node is refused when it is not
showing, and `SCROLL_INTO_VIEW` is asked for precisely because the node is scrolled out of view. So
the two are gated on visibility instead — the same walk without the clip, which still refuses a
widget inside a hidden container, exactly as Tab does.

**Two free-name defaults carry most of the demo without an application change.** A node with no name
takes its tooltip, which names every icon-only control in the toolkit — the media transport buttons,
the tool-bar buttons, the tabbed pane's strip buttons. A `TextField` with no name takes its
placeholder. Both sources already exist and both resolve under the widget's own locale, which the
walk has to open for itself around the default: the hooks run inside that scope and the default runs
after them, and the first tooltip-named control tested under a subtree language recorded that
language on its node and spoke the process's. A default is also only as current as the tooltip is,
and a tooltip is not a standing fact of a widget: the transport's were written on its paint
heartbeat, which runs after the tree of the same frame is published, so the name of the mute button
reached a reader one frame after the change it named until the bar wrote the tooltip where the
state changes (§7.2).

**The application's controls**, all on `Widget`, all public and final:

```java
public void setAccessibleName(I18nString name)          // and a String overload
public void setAccessibleDescription(I18nString text)   // and a String overload
public void setAccessibleRole(Accessible.Role role)
public void setAccessibleIgnored(boolean ignored)
public void invalidateAccessible()
```

An application-set name always wins over one derived in `onAccessibility`, so renaming a component
never requires subclassing it. These live behind one nullable reference field on `Widget`, `null`
until an application names the widget — the shape `cursor`, `imageCursor`, `tooltip` and
`inheritanceHost` already have. One reference per widget is the whole per-widget memory cost of this
ADR. The four setters go through `invalidateAccessible()` themselves, because a name, a description,
a role or an ignored flag written by an application is exactly the change that paints nothing.

**`invalidateAccessible` sets the node flag *and buys the frame that reads it*, for the same reason
`announce` does.** The flag is consumed in the publish step and the publish step runs inside a frame;
the state this record describes everywhere else is a parked loop with no frame pending (§6). A method
that only set the flag would therefore do nothing at all on the quiet window — the change would
surface on the next unrelated repaint, or never — and a call that marks something pending without
buying a frame is a no-op with a comforting name. So it sets the flag unconditionally, which costs
one store and is what keeps switching a bridge on mid-session free of an audit (§6), and it calls the
scene's frame primitive `scheduleFrame()` — never `requestRender()`, which would declare damage this
change does not have (§5.2, §8) — **only when a bridge is attached and listening**. With nothing
listening the flag is still set and no frame is spent, which is the same bargain `announce` and
`Host#requestRestamp` strike and keeps §6's promise exact. `Widget#setTooltip` and
`Widget#setFocusable` are the two toolkit setters that gain the call (§8); an application's own
unpainted state change is the case it exists for.

An announcement is raised on the scene, because it is a message to the user rather than a property of
a box:

```java
public void announce(I18nString text, Accessible.Politeness politeness)
```

`POLITE` waits for the assistive technology to finish; `ASSERTIVE` interrupts. All three platforms
carry the distinction and it decides whether a user is cut off mid-sentence, so it is not a boolean.

**`announce` must buy the frame that drains it, and an earlier draft did not.** The queue is drained
in the publish step, and the steady state this record describes everywhere else is a parked loop with
no frame pending (§6): a screen reader is reading, nothing is repainting, and there is no next frame.
An announcement enqueued into that state is never spoken — the application's one mechanism for
saying something out loud goes silent exactly when the interface is quiet, which is when it is most
likely to be used. So `announce` enqueues and then calls the scene's frame primitive directly, the
same hand-obedience to ADR 023 that `requestRepublish` and `requestRestamp` need (§5.2), and for the
same reason: marking something pending without buying a frame is a no-op with a comforting name. It
buys the frame only when a bridge is attached and listening; with nothing listening the entry is
still enqueued and still bounded, so an application's diagnostics do not depend on a reader being
present, but no frame is spent on speech nobody will hear.

**Amendment, 2026-09-15 (phase-3 fix round, brief item 4): there is something to press.** Every
other fact in this record is reachable by walking a tree, and an announcement is not: it is the
application speaking, so a live run has to make it speak. Nothing in the toolkit or the demo called
`announce`, so the three bridges' announcement paths — a UIA notification, an AT-SPI
`Announcement`, an `NSAccessibility` announcement posted on the window (§2.1–2.3) — had no scene to
be heard on, and phase 5's "VoiceOver hearing an announcement posted on the window" had nothing to
press. The accessibility gallery gains an `Announcements` entry: two buttons whose handlers call
`announce`, one `POLITE` and one `ASSERTIVE`, with the reader script `announcement` pressing both.
Both levels, because the three platforms map them to different values and a run that heard one would
leave the other unread. `ReaderStepsTest` holds it twice over: the script's two presses change
nothing in any tree, so the "every step changes something or announces something" rule passes on the
announcement alone and a scene that stopped announcing fails as a silent step, and
`theAnnouncementEntrySpeaksBothPolitenessLevels` names the two strings and the two levels.

### 1.6 Transparent and ignored are different, and both are needed

**Transparent** means no node and children hoisted into the parent in place. **Ignored** means no
node and no children.

Transparency is the default, and it is a predicate rather than a per-class opt-in: a node whose role
is `GROUP`, whose name and description are empty, which offers no action and carries no state beyond
the defaults is removed and its children hoisted. That single rule deletes `Row`, `Column`, `Flex`,
`Stack`, `Padding`, `SizedBox`, `Expanded`, the four `Token*` wrappers, `BackdropPanel`,
`SplitPane`'s panes, `ComboBox`'s scene-popup wrapper and `Dialog`'s card column and action row from
the tree without a line of accessibility code in any of them. A screen reader hears the controls
rather than the scaffolding.

Because `FOCUSABLE` is not a default state, a focusable widget survives the predicate. A focusable
widget with no declared role is a defect: it gets `UNKNOWN` and the toolkit logs a WARNING once per
class, in the same shape as the canvas-depth warning `paintWidget` already logs. `limn.components`
may never produce one, and §12.1's coverage test enforces it.

**The predicate is evaluated on the widget's own declared facts, never on the inherited bits.** With
`ENABLED`, `VISIBLE` and `FOCUSABLE` carried down the walk (§1.2), a `Row` inside a disabled form
would otherwise carry a non-default state — not enabled — and survive a predicate that asks whether
anything beyond the defaults is set. Disabling one container would then materialise a `GROUP` node
for every scaffold box beneath it, and re-shape the tree on a property change that is not about
structure at all. So transparency asks only about the widget's own role, its own name and
description, its own actions and the states it declared for itself; the inherited bits are applied
to the nodes that survive, afterwards. A disabled form is a form whose controls announce as disabled,
not a form that grows a skeleton.

**A widget that paints and is deleted by the predicate is warned about, once per class.** A
non-focusable widget that draws its own content — a custom gauge, a sparkline, a HUD — is a `GROUP`
with no name, no action and no non-default state, so the rule deletes it and the interface it drew
is simply absent from the tree with nothing said. That is not hypothetical: `limn-demo`'s own
`PerfFooter extends Widget`, paints a thirty-bar chart, and is exactly that shape. The predicate
therefore asks one more question before it deletes: does this class declare `onPaint` anywhere
between itself and `Widget`? The answer is computed once per class into a `ClassValue` on the delete
path only, and when it is yes the toolkit logs a WARNING naming the class and the three one-line
fixes — `setAccessibleName`, `setAccessibleRole`, or `setAccessibleIgnored(true)` to say the drawing
is decorative and mean it. It stays a warning rather than a node, because a name is the only thing
that would make such a node useful and only the application has one.

**Unless the class says the drawing is decoration.** `Widget#paintsDecoration()` is a protected
`boolean`, false by default, in the shape of `clipsChildren()`: the `ClassValue` answers *does this
class draw*, which is all reflection can see, and the widget answers *is what it draws information*,
which nothing else can. `BackdropPanel` is the case the paragraph above did not consider, and the
one class in its own list of deletions that overrides `onPaint` — a wash or a blur over siblings it
holds no reference to. Without the seam it names a *toolkit* class in an application's log, for a
picture that means nothing, and recommends `setAccessibleIgnored(true)`, which would take the whole
child subtree with it and delete the controls the glass sits behind: this section's own rule against
ignoring an operable control, violated by this section's own advice. The seam is deliberately not
that flag. Declaring the painting decoration changes nothing about the tree — the node is removed
exactly as it would have been and the children hoist into its place — and only the warning goes
away. It is not an escape hatch for a widget that paints information: that widget is named or given
a role, and there is no third answer that leaves the picture out and keeps the user informed.

Ignored is what an application reaches for on a spacer image or a decorative rule, and what a
component reaches for on a mark it draws that carries no information. **A control that can be
operated is never ignored, and an operation is never deleted with the box that carried it.** A text
field's trailing button and a search field's clear button are operable — the setter takes an icon and
a `Runnable`, and the search field wires it to `clear()` — so they are real nodes with real names.
Marking an interactive control decorative is not a deferral, it is hiding it. The one wrapper that
carried an operation without carrying geometry of its own, `ContextMenus.ContextRegion`, was
offered here as this rule's worked example and **is no longer one**. Moving the action onto the
wrapped child publishes an operation the scene dispatches to a widget that refuses it, which is
worse than an absent verb: the platform reports a failure rather than an absence. The region keeps
a node of its own instead — which is what this rule asks for — and its rectangle is the child's
anyway, because it measures and lays out to exactly its content's box. §7.2 records the whole of
it.

### 1.7 Names are `I18nString`s resolved under the subtree's locale, and they carry their provenance

ADR 006 §5 decided that accessibility labels are `I18nString`s. ADR 035 decided that the locale is a
property of the subtree and that the pass carries it. Both apply here unamended: the builder resolves
a name inside `I18n.pushScope(node.locale())`, because it runs outside any pass that would put that
locale in scope. A name read from a bridge thread, outside any scope, would answer in the process
locale; the snapshot holds the already-resolved string, so no bridge ever resolves anything. The tree
carries the locale epoch and `I18n.epoch()` it resolved under, and a move in either re-resolves every
name in the tree exactly once — the same rare, spurious invalidation ADR 035 already accepted, paid
on the same rare events.

`nameFrom` records where the name came from: `CONTENT` (the widget's own painted text), `LABEL`
(another widget, through a `LABELLED_BY` relation), `TOOLTIP`, `PLACEHOLDER`, `EXPLICIT`. Finding 5
is why it exists. On macOS a `CONTENT` name is published as `accessibilityTitle` and any other as
`accessibilityLabel`, **never both**, because VoiceOver reads `AXTitle` and then `AXDescription` and
a name published in both is spoken twice for every control in the interface. On Windows and Linux the
name goes to `Name` either way, and a `LABEL` provenance additionally publishes `LabeledBy` and
`RELATION_LABELLED_BY`.

The node also carries its resolved `Locale`, which AT-SPI2 takes directly as `Accessible.Locale` and
UI Automation as `Culture`. That is ADR 035's motivating case — a Hebrew interface holding an LTR
code pane — arriving intact at the screen reader's pronunciation.

### 1.8 Bounds are scene points plus a window stamp, converted once per bridge

Every node's bounds are logical points in its own scene, which after layout are already physical and
left-origin in both directions (Finding 9). The tree additionally carries, captured on the UI thread
at publish time, the values a reader needs and cannot ask for: the window's `screenX` and `screenY`,
its `logicalToScreenFactor()`, its `supportsAbsolutePositioning()`, and the scene's own height,
which is the content view's height and is what the macOS bridge flips against.

That is not a convenience. `LwjglWindow#screenX` is UI-thread-confined; a UIA RPC thread or an
AT-SPI reader thread that tried to ask would throw. A window move or a content-scale change therefore
re-stamps the header and walks nothing, and the backend asks for that through the bridge (§5.2).

Each bridge converts once, in its own idiom. Windows and X11 multiply and add and are done. **macOS
does not convert to screen coordinates at all**, and Finding 4a is why: the spike published
`setAccessibilityFrameInParentSpace:` with `(40, 40, 160, 48)` in the content view's own
coordinates, and the client read back `rect(240, 412, 160, 48)` in screen coordinates. The bottom-left
origin, the title bar and the flip are AppKit's arithmetic. So the macOS bridge publishes each node's
box in the content view's space — the same x, and `contentHeight - (y + height)` for the y — and
touches neither `screenX`/`screenY` nor a display height. A bridge that flipped against the primary
display itself would be reimplementing, on the one platform that does it for us, the line this
document was about to call the classic source of "the element is in the wrong place". On Wayland
`supportsAbsolutePositioning()` is false and the origin is a placeholder, so the AT-SPI bridge
answers `ATSPI_COORD_TYPE_WINDOW` truthfully and answers a screen request with the window answer,
which is what GTK does there and for the same reason.

### 1.9 An action is a post that re-checks its own preconditions

The snapshot holds action identifiers, not callbacks, and **the call crosses in one direction: the
platform calls the bridge, the bridge calls the scene, and the scene posts.** That direction is not a
detail, because the map it needs is on the toolkit side: only the scene knows which widget or
synthetic child a node id stands for, and only the UI thread may read that map, since the UI thread
rebuilds it on every publish and a lookup racing a rebuild is a torn read in the one structure that
has to be exact. So actuation is a member of `AccessibilityBridge.Host` — the scene-side object every
bridge already holds — and not of the bridge (§5.2):

```java
boolean perform(long nodeId, Accessible.Action action, Accessible.Argument arg);
```

A bridge calls it from whatever thread the platform gave it. It resolves nothing itself: it checks
that the id is in the currently published snapshot — an immutable read, safe from any thread, and the
one refusal that can honestly be immediate — posts the identifier with `Ui.post`, and returns. The
id-to-`(owner widget, synthetic key)` map is read **inside the posted task, on the UI thread that owns
it**, which calls the widget's own `onAccessibilityAction` or `onSyntheticAction` hook (§1.5) and
raises `INVOKED` when a `PRESS` succeeded. **Nothing in `limn-toolkit` calls a bridge except the eight
members of §5.2 — `publish`, `emit`, `frameEnded`, `attach` and `detach`, which tell it something, and
`isListening`, `needsPrimingPublish` and `needsRootBeforeTheFirstFrame`, which only ask it something;
nothing in a bridge touches a
widget, a scene or a window; and everything a platform asks of the toolkit arrives through the four
`Host` members.** Those three clauses are the whole of the seam. The questions are named here
rather than elided because the scene asks the first of them on **every frame** (§5.3 step 2) and the
other two once per bind: they are the gate §6's whole cost argument rests on, they return a constant
on `NONE`, and a seam sentence that left them out would be describing a cheaper interface than the
one this record specifies. (This sentence said "six members" and named six until 2026-09-16, omitting
`frameEnded`, which `Scene` has called once a frame since MACOS-NEW-8; §3.1's measurement then added
the third question, and both are counted here now.) Every row of §2 that says "posted" means this call. All three
platforms accept the asynchrony:
`IInvokeProvider::Invoke` is defined as permitted to be asynchronous, `Action.DoAction` returns a
boolean, and `accessibilityPerformPress` returns a `BOOL`. The boolean means **accepted**, not
**done**, and an id that no longer resolves is one more precondition that fails on arrival rather
than a different answer to the platform.

**The post has to wake the loop, and today it does not always.** Finding 13: `Ui.post` wakes only
from a foreign thread, and a macOS AX callback is the UI thread with the loop parked in
`glfwWaitEvents`. Left alone, every VoiceOver action would sit in the queue until the user moved the
mouse — and Finding 4 now measures that the loop being parked there is the *normal* state while a
screen reader is reading, not an edge case. §8 makes a UI-thread post wake the loop whenever the loop
is parked, which is exactly this case and no other.

**There is no bounded wait anywhere**, and that is a decision rather than caution. On macOS the
platform's thread *is* the UI thread, so a post-and-wait is an instant self-deadlock, and the same
bridge code runs on all three. Beyond that, nothing here could bound such a wait honestly: the UI
thread parks with no drain for the whole life of a native file chooser on macOS and Linux (ADR 022)
and for the whole of a native resize drag on Windows and macOS. A bounded wait would either return a
lie after its timeout — which is exactly what the snapshot returns immediately, with no thread parked
and no timeout constant nobody can justify — or hang the screen reader.

On arrival the posted task re-checks what the snapshot cannot promise: that the node still exists,
that its widget is still attached, that it is enabled **and every ancestor is** — the same chain
§1.2 publishes, because `Widget#isEnabled()` answers only its own flag and a control inside a
disabled container would otherwise pass a gate the keyboard refuses — that `isShowing()` is true,
not `isVisible()`, because `Scene#requestFocus`'s own guard would let a focus request land in a
hidden subtree, and that the widget is reachable. Reachability is **two** tests, not one: the window must
not be modal-blocked, **and** the node must be inside the topmost overlay when there is one.
`NativeWindow#isModalBlocked()` answers `false` for the host of an in-scene modal by construction,
because the modal names its host as an owner exception, so a bridge gating on it alone would happily
invoke a button underneath a Wayland dialog — exactly the case ADR 028 created.

**Amendment, 2026-09-15:** "that `isShowing()` is true" is read on the **container** for a verb a
container claimed on a widget child (§1.5's amendment of the same date): the child must be visible
by its own flag and the container showing, because the container performs it and a kept cursor row
outside the viewport is not showing by construction.

**Amendment, 2026-09-15 (fix round 2c):** the two reachability tests are one method,
`Scene#accessibleInputLayer` — the top overlay, the root when none is open, or none while a native
modal blocks the window — which the gate asks and the walk asks too, so what §1.13 publishes as
operable and what this gate performs cannot be two readings of the overlay stack.

**Amendment, 2026-09-15 (fix round 2d):** the ancestor loop's refusal is published too. A node the
walk publishes without `ENABLED` — a widget whose own flag or an ancestor's is false, the contents of
an overlay whose host is disabled, anything outside the layer that owns input, and a synthetic child
its owner narrowed — carries no verb and no setter (§1.5's amendment of this date), so a platform is
refused synchronously where this gate would refuse on arrival. (**Amended the same day, fix round
2e:** no setter because it is not `ENABLED`, and not because a facet was rewritten read-only; §1.5's
last amendment.) **Corrected the same day:** the
sentence first written here had the direction backwards. The walk's bit climbed an overlay's
inheritance host and this loop stopped at the overlay, so the walk was the narrower of the two, and
the gate then performed verbs inside a disabled control's popup — a dialog shown from a button
disabled since — that the snapshot no longer offered: a node accepting what it does not publish. The
loop now climbs the chain the walk climbs, past the top of an overlay's subtree through its host,
parent then host, and not past a scene's root (a native popup's root is walked enabled), so the two
readings are one. Pinned by
`AccessibleActionTest.aVerbInsideAnOverlayWhoseHostIsDisabledIsNeitherPublishedNorPerformed`.

**Amendment, 2026-09-15 (the 2d review): an overlay's enabled axis is its own subtree's, on both
sides.** The correction above made the gate agree with the walk, and both were then wrong against
the input path: the keyboard's traversal and the pointer start at the layer that owns input and read
each widget's own flag from there down, and never climb an overlay's inheritance host. A `Dialog`
shown in the scene with `show(owner)` over an owner disabled before it opened — a Save button
disabled while its confirmation asks — was answered by Tab, Return and a click while its buttons
were published neither `ENABLED` nor `FOCUSABLE` and with no verb, its card had no `CANCEL`, and a
reader's press was refused. The disabled axis of semantics 5 is "a disabled widget or anything under
a disabled ancestor", and a host is neither. So the walk publishes an overlay's contents on the
overlay's own chain, as it already published a native popup's root, and this loop reads the owner's
parent chain alone again; the two readings stay one, and they are now the keyboard's too. A popup
that must go inert with its opener says so itself: `ComboBox.PopupPanel` narrows every option with
`Accessibility#disabled` while the combo is disabled, in both mountings (§7). The inheritance host
still carries size, direction, locale and the popup relation. The name of the test above is
superseded: pinned by
`AccessibleActionTest.aVerbInsideAnOverlayIsReadOnTheOverlaysOwnChainAsTheKeyboardReadsIt` and
`DialogPanelAccessibilityTest.overADisabledOwnerTheTreeTheKeyboardThePointerAndAReaderAgree`.

**Amendment, 2026-09-15 (decision 66): the showing test becomes a visibility test, and what is only
clipped is revealed and performed.** "That `isShowing()` is true, not `isVisible()`" stood from the
first draft, with the two free verbs exempted from it. It is wrong in both halves.

It is wrong about a control **scrolled out of a viewport**. That node is visible through its
ancestry — nothing hid it, a pane clipped it — it publishes the verbs it offers, `Host#perform`
answers yes from the snapshot, and this gate then dropped the verb in silence. A reader was shown
"Chapter 20" below the fold, the media bar's Volume slider, a colour picker's rails in a scrolled
panel, and could work none of them; the two verbs that were exempted are exactly the two that make
the case obvious, because `SCROLL_INTO_VIEW` is asked for precisely when the node is not showing.
So every verb takes the free verbs' path: the gate reads **visibility through the ancestry**, and
where the node is visible and not showing the scene **reveals it and then performs** — `revealInView()`
on the owner, or on the container for a verb it claimed, which is whose `isShowing()` the gate used
to refuse on. A container that is on the glass and holds a child outside its own viewport reveals
nothing, so decision 22's kept cursor row is still expanded and selected where it stands.

It is wrong about the other half too, by leaving it in the gate alone. A control **nobody can see** —
an unselected tab's contents, a collapsed panel, anything under a widget whose own visible flag is
false — was published with its verbs and its setters and refused on arrival, which is the same
broken promise. `VISIBLE` now joins `ENABLED` as the operable bit: the walk takes every verb off such
a node through the same `Accessibility#inoperableAt` as the disabled and input-layer axes (§1.5's
amendment of this date), and `AccessibleNode#accepts` implies a setter only on a node that is
`ENABLED` **and** `VISIBLE`. The snapshot and this gate are then one reading of one fact, which is
what semantics 5 asks for. The reveal itself is gated on `accepts`, because a reveal is a visible
effect and a verb the node never published must move nothing at all.

Pinned by `ScrollViewAccessibilityTest.aButtonScrolledOutOfTheViewportPublishesItsPressAndIsRevealedAndPressed`
and `aControlNobodyCanSeePublishesNoVerbAndNoSetterAndPerformsNothing`,
`TabbedPaneTabHeaderAccessibilityTest.anOverflowingStripRevealsAndSelectsTheTabItHasScrolledAway`,
`AccessibleActionTest.aDelegatedVerbOnAChildOfAContainerClippedAwayIsRevealedAndPerformed` and its
invisible-container twin, and ratcheted over the whole gallery by
`VerbPolicyRatchetTest.everyPublishedVerbMovesSomething` — the parameterless half's second direction,
which the setter pass has had since fix round 2e and which is where a published verb this gate
refuses in silence shows up. `VerbPolicyRatchetTest.SETTER_ALLOWLIST` is empty as of this amendment.

Parameterless verbs live in `ActionFacet`: `PRESS`, `TOGGLE`, `EXPAND`, `COLLAPSE`, `SELECT`,
`DESELECT`, `SHOW_MENU`, `INCREMENT`, `DECREMENT`, `SCROLL_INTO_VIEW`, `FOCUS`, `CANCEL`. Each
carries a localized name, because `Action.GetActions` returns `a(sss)` — name, description, key
binding — and the key-binding column is where an `Accelerator#display()` string belongs.

**Parameterised setters take the same `perform` call and are not published in the same list.** They
cannot be methods on a facet: a facet is an immutable record a reader holds on an RPC thread, and it
carries no widget and no callback (§1.2), so there is nothing on one to call. They are
`Accessible.Action` constants — `SET_VALUE`, `SET_TEXT`, `SET_CARET`, `SET_SELECTION` — carrying an
`Accessible.Argument`, itself a sealed value with no widget in it: `Argument.NONE`,
`Argument.OfValue(double)`, `Argument.OfText(String)`, `Argument.OfRange(int start, int end)`. So
**`Accessible.Action` is the dispatch vocabulary and `ActionFacet` publishes only its parameterless
subset**, and that split is not tidiness: AT-SPI2 cannot express a parameterised action in
`GetActions` at all and puts these on the `Value`, `Text` and `EditableText` interfaces instead, so
flattening them into one list makes `GetActions` unanswerable. What advertises a parameterised setter
is the facet's presence — a node with a `ValueFacet` is settable, a node with a `TextFacet` and
without `READ_ONLY` is editable — which is exactly what `IValueProvider::SetValue`,
`Value.CurrentValue` and `setAccessibilityValue:` ask about, and each bridge routes its own platform's
setter into `perform` with the matching `Argument`. **Amended 2026-09-15 (fix round 2e):** the
facet advertises its setter only on a node that is `ENABLED` (§1.5's last amendment); a bridge
refuses a setter on a node without that bit, and still answers `IsReadOnly` and settability from the
facet, so a disabled field is never reported read-only.

### 1.10 Every per-node event is the difference between two published snapshots

There is one event source for node events, and it is a diff. When a snapshot is published the builder
compares it with the previous one and emits `FOCUS_CHANGED`, `ACTIVE_DESCENDANT_CHANGED`,
`STRUCTURE_CHANGED`, `NAME_CHANGED`, `DESCRIPTION_CHANGED`, `STATE_CHANGED`, `VALUE_CHANGED`,
`SELECTION_CHANGED`, `TEXT_CHANGED`, `CARET_MOVED`, `TEXT_SELECTION_CHANGED`, `BOUNDS_CHANGED` and
`NODE_DESTROYED`.

Nothing in any component emits an event and this bridge subscribes to nothing. **The reason for that
has changed since the first draft and the decision has not**, so both halves are worth stating. The
original reason was Finding 6's: every component change callback is a single-slot replace, so a
bridge registering on one would silently unregister the application's handler. ADR 040, which lands
beside this record, removes exactly that hazard — it adds a second channel any number of parties may
watch, and guarantees an application's handler runs whatever a watcher does. So that argument is
spent, and §9 is where the two records are reconciled.

What survives it is three things, and they are why the diff stays. **Bounds.** ADR 040 deliberately
does not carry per-widget geometry — it emits one coarse `LAYOUT` change per pass, because hooking
`layoutBox` and `moveChild` would put that channel on the innermost loop of every scroll frame —
while all three platforms want a rectangle per node, so the walk that produces them happens every
frame something moves regardless. **The snapshot is not optional.** Windows and Linux read it from
threads that may not touch a widget at all (§3.1, §3.3) and `Cache.GetItems` wants the whole tree
pre-assembled (Finding 3), so once the walk is paid for the diff is the cheapest event source there
is. **And a node's existence is a whole-tree question**: §1.6's transparency predicate deletes a
node and hoists its children on facts about its own subtree, so "this widget's name changed" cannot
be patched into a published tree without re-deciding whether that widget is in it. Coalescing then
comes free, because a diff between consecutive snapshots is by construction one event per node per
publish and a publish happens at most once per frame; and a new event kind later is a new comparison
rather than a change to every component.

Four kinds of event are **raised, not diffed**, because no diff within one window could ever produce
them:

- `WINDOW_OPENED` and `WINDOW_CLOSED`, raised inside `attach` and `detach` — whose only callers are
  `Scene#bind` and `Scene#observeWindowClosed` — because on a rebind the bridge is the only object
  that knows there was an outgoing tree to close (§5.3). They are the two events the scene does not
  hand over through `emit`, and the reason is stated where the replacement rule is. Each window
  is its own `Scene` with its own snapshot and its own diff, so a native dialog, a menu window or a
  combo popup window appearing is invisible to any within-window comparison. This is how Orca learns
  a dialog appeared (`Event.Window.Create` and `Activate`), how NVDA learns it
  (`Window_WindowOpened`), and how VoiceOver follows it (`AXWindowCreated`).
- `WINDOW_ACTIVATED` and `WINDOW_DEACTIVATED`, from the scene's window-focus funnel.
- `ANNOUNCEMENT`, from `Scene#announce`. It is never coalesced, and it is drained at the top of the
  publish step — **before the re-present guard and before both dirty flags are consulted** — because
  it is the application speaking and not a property of any node. A frame that changes nothing in the
  tree still carries the announcements queued since the last one, and `announce` itself buys that
  frame (§1.5).
- `INVOKED`, raised by the action dispatcher after a `PRESS` succeeds, because UI Automation has
  `Invoke_Invoked` and the other two have nothing to raise. It is raised for a press **the assistive
  technology performed** and not for one the user performed with the mouse, because a press that
  changes no state leaves no difference between two snapshots to find. §11 states that loss and §9
  says why it is not bought back.

`TEXT_CHANGED`'s insert and delete offsets are computed from a common-prefix and common-suffix
comparison of the two published strings, because `TextEditModel` carries no change counter and no
damage record (Finding 10). At 60 Hz that is one insert or one delete per keystroke, which is what a
keystroke echo needs; two edits landing in one frame yield one contiguous replaced range covering
both, which every platform can carry and none can distinguish from the truth. `BOUNDS_CHANGED`
collapses to one window-level event past a threshold, which is what a scroll is. **Amended
2026-09-14:** `VALUE_CHANGED` is raised when a value's number, its text or its emptiness moved, not
only its number (§1.2's amendment of the same day; CRIT-4).

Events are handed to the bridge on the UI thread immediately after the snapshot they refer to, so
every event names a node the bridge can already resolve. **Handing over is not raising, on any of the
three.** A diff between two frames of a scrolling list or a dragged slider can be hundreds of nodes
wide, and every raise is a cross-process call: a bridge that raised them inline would spend the UI
thread's frame budget in `UiaRaiseAutomationEvent` or `NSAccessibilityPostNotification`, which is the
exact stall Finding 2 calls an accessibility outage. So all three bridges take the bounded queue the
Linux one needed anyway:

- The flush **enqueues**, and the queue has a fixed capacity. On overflow it collapses to a single
  invalidate-everything event for the window — `UiaRaiseStructureChangedEvent(ChildrenInvalidated)`,
  `NSAccessibilityLayoutChangedNotification`, `Cache.AddAccessible` for the root — rather than
  dropping events silently or growing without bound.
- **A collapse is a reconciliation, not just a signal, because `NODE_DESTROYED` is the one event a
  bridge cannot afford to lose.** The macOS bridge releases an element on `NODE_DESTROYED` and the
  Windows bridge drops its map entry there (§2.2, §3.4); an overflow that swallowed those
  per-node events would leak every element that went away in the same burst, and the burst that
  overflows is precisely the one that destroyed a lot of nodes. So a bridge handling the collapse
  **reconciles its element registry against the tree it has just been handed** — every id in the
  registry that is absent from the published tree is destroyed and released, in the platform's own
  idiom, before the invalidate-everything event is raised. That is stronger than replaying the
  dropped events: it is a full sweep, it needs no reserved capacity, and it is the same operation
  `attach` and `detach` need anyway (§5.3), so there is one implementation of it per bridge and not
  three. **It runs on the thread that handles the collapse** — the drain thread on Windows, the UI
  thread on macOS — which is also the thread that owns removal there (§3.4). On Linux there is
  nothing to sweep, because nothing is retained per node: the collapse is one `Cache.AddAccessible`
  for the root and the client re-reads. *(Amended 2026-09-15: not so. libatspi 2.60.6 reconciles
  a cached child list only from `ChildrenChanged` and a cached state only from `StateChanged`, so
  one root item re-reads nothing; the Linux bridge sends nothing for `INVALIDATED` and relies on
  the reserved tail's structure signals and says the focus again — §2.4's amendment of this date.)*
- **Windows and Linux drain on a thread of the bridge's own, and on Linux that is emphatically not
  the reader thread.** The Windows spike raised an event from an RPC thread and from inside `Invoke`
  and got `S_OK` both times, so a raise does not need the UI thread, and the Windows bridge starts
  one drain thread of its own. On Linux the reader thread is the thread that serves every inbound
  call (Finding 3), so draining onto it would park the whole provider on a full socket buffer — the
  exact mirror of the hazard Finding 3 identifies, and an earlier draft of this section walked into
  it by saying the bridge "already owns its socket writer" as though the reader and the writer were
  the same thread. They are two threads; §3.3 sets out the split. One drain thread per bridge, so
  events never reorder relative to one another.
- **macOS drains on the UI thread**, because `NSAccessibilityPostNotification` is an AppKit call and
  AppKit is main-thread-only. It gets a per-frame budget instead of a thread, and overflows into the
  same collapse. That budget is a policy, not a measurement, and §13 names the experiment that sets
  it.

**An event carries its own values, because by the time it is raised the snapshot it came from may be
two publishes old.** A queue means the drain and the publish are no longer the same moment: on
Windows and Linux the drain is another thread entirely, and on macOS a budgeted overflow carries work
into the next frame. So `AccessibleEvent` holds the node id, the property, and the old and new values
themselves — which is what `UiaRaiseAutomationPropertyChangedEvent` wants anyway, since it takes both
variants. A bridge that raised an event and then read "the current value" out of the live snapshot
would report the newest value under an older event, and the two would disagree for exactly as long as
the queue is deep. The one thing an event may not carry is a `Widget`, for §1.2's reason.

What each platform raises for each event is §2.4; when it raises it is here.

#### Amendment 2026-09-14 — the cursor is the focused node's, published once

**What was wrong.** The active descendant was a per-container fact: every node with a
`SelectionFacet` resolved the first `ACTIVE` node anywhere in its subtree, and the differ raised
`ACTIVE_DESCENDANT_CHANGED` on every such container whose answer moved. So one arrow key in an
in-scene combo raised two events (the overlay layer's and the list's) and one in a submenu raised
three (the surface's, the root column's, the submenu column's) — MODEL-NEW-3; a `DateField` holds
no selection, so the segment its caret moved onto was never announced as a cursor at all — D3; and
`Tree`, `ListView`, `Table` and `SegmentedControl` marked their cursor row `ACTIVE` whether or not
they held the keyboard, so a list inside another list's row handed the outer list its own selected
row as the outer list's cursor, and an unfocused widget could announce a cursor nobody was on.

**The rule (semantics 4 of the 2026-09-13 pass; decisions 1, 5 and 6).** `AccessibleTree` gains
`activeDescendant()`: the first node published `ACTIVE` strictly below `focused()` in reading
order, through any number of containers, never the window node's own `ACTIVE` (which says the
window is the desktop's) and never resolved through a `SelectionFacet`; and `effectiveFocus()`,
which is the active descendant when there is one and the focused node otherwise — the answer a
platform that puts its focus on the item (UIA `GetFocus` and `HasKeyboardFocus`, AppKit
`accessibilityFocusedUIElement`) reads, while Linux keeps its focus on `focused()` and sends
`ActiveDescendantChanged` with the item. Both are resolved once at publish, allocate nothing, and
are stored in the tree. **`ACTIVE` is published only while the owning widget holds the keyboard**
(the settled active-state gate: focused itself, or driven by a focused field or layer as
`CalendarView` under a `DatePicker` is), so an unfocused nested widget cannot hijack the cursor;
`Tree`, `ListView`, `Table` and `SegmentedControl` gained the gate `CalendarView`, `DateField` and
`RadioButton` already had. **Exactly one `ACTIVE_DESCENDANT_CHANGED` per publish**, on the focused
node, when its resolved cursor differs from the last publish's — a newly focused node whose cursor
differs from the previous focused node's included — carrying the cursor as of the previous publish
and now; none from an unfocused container, and none from a scene with nothing focused. A
`DateField`'s caret rolling onto the next segment after a typed digit therefore raises the event
on the field (decision 49); the widget's own quiet flag gates only its ADR 040 observers.

**Across a popup window (decision 5).** When the focused node's own subtree holds no `ACTIVE`
node and it, or an ancestor of it, is the `CONTROLLER_FOR` a native popup's root (§1.11), the
cursor is read off the popup scene's published tree — the first `ACTIVE` node below the root the
relation names — so the identifier `activeDescendant()` answers may belong to another window, and
`holds(long)` says which. The popup's walk tells its host to walk again whenever the cursor it
publishes moves, and only then, so the host's tree follows the popup's arrow keys without a host
walk per popup publish. The three reader behaviours this depends on are the live assumptions
decision 5 names and phase 5 measures.

#### Amendment 2026-09-14 — a selection change is the container's, and carries the members

**What was wrong.** `SELECTION_CHANGED` was raised once per member whose selected bit flipped, on
the member's published parent, carrying nothing. For a calendar day that parent is the synthetic
week `ROW`, which holds no selection — the grid does — so every platform was told a row's
selection moved (D2); a single-select move raised two identical events and a range band one per
day; a member that arrived selected in the same publish (End onto an unrealized list row, which
publishes a brand-new node) raised nothing at all, and neither did a selected member that left
the tree (MODEL-NEW-6); and Windows, raising `SelectionItem_ElementSelected` on the event's node,
raised an item event on a container.

**The rule (semantics 1 and decision 9 of the 2026-09-13 pass).** A member's container is
resolved once at publish and published on the node as `AccessibleNode#selectionContainer()`: the
nearest ancestor with a `SelectionFacet`, reached from the member's published parent by climbing
only through synthetic ancestors that lack one — a widget ancestor carrying the facet counts (the
tab strip for a tab header), a widget ancestor without one ends the climb with no container, and
a member that declared itself containerless (`RadioButton`, §1.2's amendment) is not climbed
for. A member with no container raises no `SELECTION_CHANGED`; its own `STATE_CHANGED(SELECTED)`
is the whole of its announcement. `SELECTION_CHANGED` is **one event per container per publish**,
addressed to the container, carrying `addedMembers()` — the members that entered its selection,
a member new in this publish included — `removedMembers()` — the members that left it, a member
gone from the tree included — and `multiSelectable()`, the container's own flag, which is what a
bridge chooses its platform event by: Windows raises `ElementSelected` for a single-select
container and `ElementAddedToSelection` / `ElementRemovedFromSelection` on the members of a
multi-select one, or `Selection_Invalidated` for a bulk change; Linux and macOS address the
container (§2.4's row is read accordingly; the bridge mappings are phase 3's). No `STATE_CHANGED`
is raised for a member new in the publish: a client reads a new node's states when it discovers
it. And a member that arrives selected under a container that is itself new in the publish — the
first publish of a scene, a popup opening with an option chosen — raises nothing either: the
container's arrival is reported by the structure change on its surviving ancestor, and its
selection is read with the rest of it (measured: the kitchen sink's first publish raised four
`SELECTION_CHANGED` for tab strips and segmented controls nobody had touched, until this clause).

#### Amendment 2026-09-14 — a structure change is per surviving parent, and says which children

**What was wrong.** `STRUCTURE_CHANGED` was raised once per node absent from the previous walk, on
that node's parent, carrying nothing — including when the parent was itself new, so an in-scene
combo opening with fifty options raised fifty-two events (one on the window, one on the new layer,
fifty on the new panel), which is what pushed a large popup toward the budget collapse
(MODEL-NEW-8); and nothing at all was raised for a node that kept its identifier and moved — a
table sorted with its rows' identifiers kept, a tree reordered by its model — nor on the parent a
node was removed from (MODEL-NEW-4). §2.4 promised `ChildAdded`/`ChildRemoved` and
`ChildrenChanged` with an index, and no bridge could build either from the event it got.

**The rule (settled structure-event-shape; decision 28's first half).** `STRUCTURE_CHANGED` is
**one event per surviving parent per publish**, addressed to the parent, carrying three lists of
`(child id, index, other parent)`: `addedChildren()` — the children under it now that were not,
each at its index among the parent's children now, naming the parent it came from when it moved
rather than appeared; `removedChildren()` — the children under it before that are not now, each
at its former index, naming the parent it went to when it moved rather than left the tree; and
`reorderedChildren()` — the surviving children whose rank among their surviving siblings moved,
each at its index now, so that an insertion or a removal between unchanged siblings is not a
move. A subtree that appears under a parent that is itself new is one added child on the nearest
surviving ancestor and nothing per node inside it; a subtree that leaves with its parent is one
removed child on the nearest surviving ancestor, beside every node's own `NODE_DESTROYED`, which
stands unchanged because two bridges release on it. The first publish of a scene, whose every
node is new under a root nobody published before, raises no structure change: the window's
opening is the bridge's own event (§1.10 above). Indices are the published tree's — a widget
cell hung under a row counts at its place by column — which is why the publish step links the
siblings before it takes the difference. The Linux `ChildrenChanged` add/remove with `detail1`
= index and `Cache.Add/RemoveAccessible`, and the Windows `UiaRaiseStructureChangedEvent` with
its change type, are what §2.4 now can be built from and are phase 3's; until then each bridge
raises what it raised, once per parent instead of once per node.

#### Amendment 2026-09-14 — the collapse keeps a reserved tail, a node that arrives focused says so, and a window's activation is diffed

**What was wrong.** Past `EVENT_BUDGET` the differ cleared every event and handed over one
`INVALIDATED`, the focus and selection events of that publish with the rest; the text above calls
`NODE_DESTROYED` the one event a bridge cannot afford to lose and says nothing of the focus
(CRIT-3). Measured before the fix on `KitchenSinkScene` through the headless bridge: the host's
publish when the kitchen dialog opens is 253 events (252 `STATE_CHANGED`, the modal block
clearing `ENABLED` and `FOCUSABLE` on every node), three under the budget; a tab switch is 60
to 101. Three more nodes in the kitchen sink and the dialog's `WINDOW_DEACTIVATED` would have
gone with the collapse. A node that arrived already holding the focus — a dialog's first field —
raised no `FOCUS_CHANGED` at all, because a new node's bits are not diffed (WINDOWS-NEW-12).
And `WINDOW_ACTIVATED`/`WINDOW_DEACTIVATED` were emitted from the scene's window-focus funnel
ahead of any walk, so Orca, reading the frame's state when the event arrived, found a frame
that "lacks active state" and suppressed the application (LINUX-NEW-15, LAB-NEW-2; the L4
baseline on Fedora heard exactly that).

**The rule (semantics 7 of the 2026-09-13 pass; decision 28; settled window-activation-order).**
A publish's events are two lists. The **budgeted** per-node events — names, descriptions, states,
values, text, bounds, `NODE_DESTROYED` — are bounded by `EVENT_BUDGET` and collapse past it to
one `INVALIDATED`, which a bridge answers by the sweep described above. The **reserved tail** is
outside the budget and follows the budgeted events, or the collapse, in a fixed order: the
per-parent `STRUCTURE_CHANGED` (the amendment above; decision 28's "structure survives the
collapse"), the final `FOCUS_CHANGED`, the single `ACTIVE_DESCENDANT_CHANGED`, the per-container
`SELECTION_CHANGED`, and the window activation events. A bridge that swept on `INVALIDATED`
therefore still hears where the user is; each bridge re-announcing its effective focus after its
own queue collapse stays its own obligation (§2.4's question). `FOCUS_CHANGED` is raised for the
node whose `FOCUSED` bit came on **or that arrived with it on**; a new node's other bits are
still read on discovery, not diffed. `WINDOW_ACTIVATED` and `WINDOW_DEACTIVATED` are derived by
the differ from the window node's `ACTIVE` bit — on, or arriving on, against off — named on
that node and handed over in the same publish as the tree that says so, never ahead of it; the
list of four raised-not-diffed kinds above is therefore three (`WINDOW_OPENED`/`WINDOW_CLOSED`,
`ANNOUNCEMENT`, `INVOKED`), and the scene's funnel now only buys the walk. The Linux bridge
sending the activation from the frame's path rather than the application's, and re-announcing
the focus after it, is phase 3's; with the event carrying the window node it already addresses
that node's path.

#### Amendment 2026-09-14 — what the cursor amendment (the first of this date, above) changed in §7 and beyond

The `ListView`, `Table`, `Tree` and `SegmentedControl` rows of §7 say their cursor row or cell is
`ACTIVE`; read "while the widget holds the keyboard". The `ComboBox.PopupPanel`, `ListView` and
`PopupMenu.MenuSurface` rows say "`SelectionFacet` with an active descendant"; read
"`SelectionFacet`", the cursor being the tree's. The "highlighted row" row under `PopupMenu` says
"active descendant on its column"; read "the surface's cursor, resolved by the tree from the
row's `ACTIVE`". Two lines outside §7 say the same old thing and carry a dated parenthetical
pointing here: §9.2's answer to ADR 040's §6.1 ("an active descendant on its column") and §12.1's
`AccessibleMenuTest` row ("the highlighted row as the column's active descendant"); read "the
surface's cursor" in both — a column has no cursor of its own since this amendment, and the one
`ACTIVE_DESCENDANT_CHANGED` per arrow key that row promises is still one, on the surface.

#### Amendment 2026-09-15 — macOS posts when the frame ends, not when the tree next changes

**What was wrong (MACOS-NEW-8).** "It gets a per-frame budget" above was true of the budget and not
of the moment. The macOS bridge drained its queue at the top of `publish`, and the scene publishes
only when the walk found a difference (§5.3 step 6), so the events of one change were posted inside
the publish of the *next* change, against a tree that had already moved on, and the last change
before a pause was not posted until something else changed. Announcements, which the scene emits
without publishing, waited the same way. `--scene tree-reader` steps three seconds apart were each
told to VoiceOver at the following step; the phase 7 probe hid it by changing the tree every tick.
Windows and Linux were never affected: their raises leave `emit` for a thread of their own.

**The rule.** The seam gains `AccessibilityBridge#frameEnded()` (§5.3's amendment of this date), called
at the end of every frame's accessibility step and never from a reentrant publish. The macOS bridge
drains there, after the frame's own events, and pays there whatever a reentrant publish deferred.
"Per frame" now means the frame that emitted the events.

### 1.11 A popup's contents are described where they actually live

ADR 028's two mountings survive into the accessibility tree unchanged, because pretending otherwise
forces a bridge to fabricate a relationship the platform does not have.

**A native popup, menu or dialog** is its own `NativeWindow` with its own `Scene`, so it publishes its
own tree and appears to the platform as its own top-level accessible window, announced by
`WINDOW_OPENED` and by focus moving into it. **An in-scene popup** is an overlay in the owner's scene,
so it appears as a subtree of that window's tree, parented to the scene root and carrying `MODAL`
when it is one.

The rule that makes both correct is: **the widget that draws a popup's contents is the widget that
describes them.** `ComboBox.PopupPanel` is the `LIST` and owns one `LIST_ITEM` per option; the
`ComboBox` itself carries only `COMBO_BOX`, `EXPANDED`, `HAS_POPUP` and the selected item's text.
`PopupMenu.MenuSurface` is the `MENU` and owns its rows. `Dialog`'s panel is the `DIALOG` and owns
its content. Because ADR 028 makes the panel either a window's scene root or an overlay's child, this
one rule produces exactly one description with correct coordinates in both mountings. Hanging
synthetic options off the `ComboBox` widget instead would produce either duplicates or bounds
measured against the wrong window's origin, and would break `ElementProviderFromPoint`,
`accessibilityHitTest:` and `GetAccessibleAtPoint` in the native mounting.

In both mountings the popup's root carries a `POPUP_FOR` relation to the node that opened it, and the
opener carries the mirror `CONTROLLER_FOR`. **In the model both directions exist; only one platform
publishes both** (ratified 2026-09-16 on the two bridges' own readings, and the reason is per
platform, in the CRIT-2 amendments below). **Linux** publishes the pair, `ATSPI_RELATION_POPUP_FOR`
and its mirror, so a client there walks either direction. **Windows** publishes the opener's
`ControllerFor` into the popup and nothing back, because UI Automation has no element-valued property
that carries "popup for" at all. **macOS** publishes `AXLinkedUIElements` from the opener only,
because the popup's root is the node AppKit's own window object stands for and there is no element of
ours to ask it on. The one-way link is enough for what depends on it: the cursor crossing into a
native popup resolves from the opener's side (semantics 4), never from the mirror. The opener is
already known: every popup, menu and dialog calls `Widget#setInheritanceHost` on its root today for
the size, direction and locale chain, and §8 makes that link readable.

**A relation target is resolved to the nearest published ancestor, and dropped when there is none.**
The inheritance host is an *axis-resolution* host, not an accessibility parent, and the two disagree
in a case that is not rare: `Dialog#inheritanceHostFor` returns the opener widget only when a
`show(Widget)` overload recorded one, and otherwise returns `owner.root()` — a `Column` or a `Stack`
that §1.6's predicate deletes. `PopupMenu` passes whatever anchor `beginOpen` was given, which may be
a `Padding` for the same reason. A relation naming a node that was never published is worse than no
relation: on Windows it is a `RuntimeId` for an element that answers `UIA_E_ELEMENTNOTAVAILABLE`, on
Linux an object path that answers `UnknownObject`, and on macOS a nil. So the builder walks up from
the host through the transparency deletions to the first node it actually published and uses that.
When the walk reaches nothing published — the host has left the tree — the relation is dropped and the
mirror is not emitted either.

**And a relation that resolves to the window root of the popup's own window is dropped rather than
published.** A `show(Scene)` dialog is that case: the walk lands on the scene root, which is already
the dialog's accessible ancestor, so `POPUP_FOR` would name exactly what `Navigate(Parent)`,
`accessibilityParent` and `Accessible.Parent` already say. No information, and one more thing that has
to stay true. Dropping it also removes, on every platform at once, the one place where this section's
own rule collided with §2.2: **macOS elides the window root**, because AppKit vends the window and a
second one would have VoiceOver announce two — so a relation naming that node resolved to a nil on
precisely the platform where a nil target is hardest to notice. The rule's worst case is now
unreachable in the mounting that produced it.

**What is left is the cross-window case, and it is real.** A native popup, menu or dialog is its own
window with its own tree, so its `POPUP_FOR` names a node in the *owner's* tree — and on macOS the
owner's window root is elided too. There the bridge answers with the object AppKit already vends for
that window, which is the same object the elision defers to and is reachable from the content view
the bridge holds; if §13.27's probe finds that AppKit will not let us name it that way, the bridge
drops the relation and does not emit the mirror, which is this rule applied rather than bent. Windows
and Linux have a real element for the owner's root either way and publish the pair unchanged.

**On Windows a popup HWND's fragment root answers `NULL` from `Navigate(Parent)`.** A fragment root
hosted in an HWND is placed in the UI Automation tree by its host provider — `UiaHostProviderFromHwnd`,
which the spike used and proved — and GLFW creates popups with no owner HWND, so there is no
cross-HWND fragment relationship to offer. Answering a foreign window's element from `Navigate(Parent)`
would build a tree that disagrees with UI Automation's own HWND tree: a client walking down from the
desktop would find the popup as a top-level, a client walking up from the popup would land somewhere
else, and the two paths would carry different `RuntimeId` prefixes, because UI Automation prefixes each
with its own HWND's runtime id. The logical link is the relation and only the relation.

**Amendment, 2026-09-14: the cross-window case is built, and this is how the two trees find each
other.** Until today the walk resolved a relation's target only among the nodes of its own scene, so
a native popup's root — a `ComboBox` list or a `DatePicker` calendar in a window of its own —
resolved its `POPUP_FOR` to nothing and dropped it, and the opener never carried the mirror; the
paragraph above about Windows and Linux publishing "the pair unchanged" described code that did not
exist (CRIT-2, 2026-09-13). Two things make it true now. First, identifiers are process-wide (§1.3,
amended the same day), so a target in another window is nameable at all. Second, the climb from a
target through `parent()` and `inheritanceHost()` is allowed to cross into another scene: at a step
that lands in a widget of another window, the walk asks **that scene's own last walk** for the
identifier it published for the widget (`Scene#accessibleIdOf`), and takes it. The popup's root
climbs through its inheritance host into the window that opened it and finds the opener's node
there; nothing in either bridge is consulted and nothing is allocated. The mirror is the other
half: a popup walk whose opener is not in its own scene tells the opener's scene to expect a
`CONTROLLER_FOR` on that widget, naming the popup's root, and marks it for a walk; the host publishes
the mirror on its next walk, resolving the root's identifier the same way, across scenes. When the
popup's window closes, its walk withdraws the mirror and marks the host again, so the opener stops
naming a window that is gone on the host's next publish rather than on its next unrelated change;
a host walk also drops an entry whose popup no longer publishes its root. The relation's target
therefore carries another window's tag, and a bridge routes it by `AccessibleTree#holds(id)` to the
tree that has it — what each platform then does with a foreign element (a `ControllerFor` array
entry from another HWND's provider, an AT-SPI `(bus name, path)` under the one application §2.3 is
to become, AppKit's own window object where the root is elided) is phase 3's, per platform. Pinned
by `NativePopupRelationTest` in the demo, where a `HeadlessBackend` opens the popup as a real
second window: the list names the combo, the combo names the list, the calendar's card names the
picker and the picker names the card; and a closed popup window leaves the picker with no mirror.

**Amendment, 2026-09-15 (CRIT-2, the Windows half): a foreign relation target is handed back as the
other window's element.** The 2026-09-14 amendment above left "what each platform then does with a
foreign element" to phase 3, per platform, and the Windows bridge did nothing with it: a target its
own tree did not hold answered no element, and the array was compacted — so a combo's
`ControllerFor` was `VT_EMPTY` while its popup was open, the one moment it is worth asking, and a
`LabeledBy` or `DescribedBy` held elsewhere was empty too. The bridge now asks the process's set of
open bridges which one holds the target (`AccessibleTree#holds`, the same routing the cursor already
used for decision 5) and hands back **that** window's element, through its *simple* interface, minted
and referenced under that bridge's guard, so the whole-registry empty cannot free it under the
caller. A target no open window holds is still left out rather than handed over as a `NULL` entry of
a `SAFEARRAY(VT_UNKNOWN)`, which `SafeArrayDestroy` would release one by one. `POPUP_FOR`, the
mirror the popup's own root carries, is answered by no property: UI Automation has no "popup for"
among its element-valued properties, and what a client follows from the popup back to its opener is
the opener's `ControllerFor`.

**This amends the settled list on that point, and is not a divergence left in a javadoc.** The
settlement of 2026-09-15 reads "Windows hands back a ControllerFor/PopupFor element from the other
HWND's provider"; the `PopupFor` half is unanswerable here, and the absence is read rather than
assumed. The platform's element-valued properties are `LabeledBy` (30018), `ControllerFor` (30104),
`DescribedBy` (30105), `FlowsTo` (30106) and `FlowsFrom` (30148), and no member of `UIA_PropertyIds`
carries "popup" in its name at all, read off the guest's own `UIAutomationCore.dll` 7.2.26100.9278
on 2026-09-13 (`readings/windows-dump-uia-typelib-all-members.txt`). Answering the opener as the
popup's own `ControllerFor` instead would say the popup controls the field that opened it, which is
the relation backwards; the one carrier of the link on this platform is the opener's `ControllerFor`,
which the amendment above delivers. AT-SPI's `ATSPI_RELATION_POPUP_FOR` and AppKit's own window
object are unaffected: the two platforms that have a carrier keep publishing the pair. Pinned as a
deliberate non-mapping by `UiaProviderTest.aPopupForIsCarriedByNoPropertyBecauseThePlatformHasNone`,
which gives a node a `POPUP_FOR` naming a widget its **own** window holds and reads every
element-valued property back empty — so a later lane that invents a carrier for it fails here and
comes back to this paragraph.

The cross-window half is pinned by
`UiaProviderTest.aRelationTargetAnotherWindowHoldsIsHandedBackAsThatWindowsElement`, which restates
`aRelationTargetAnotherWindowHoldsIsLeftOutRatherThanHandedOverAsNull` (2026-09-14), the case that
pinned the compaction. The live half — a client reading `ControllerFor` on the opener and reaching
the popup's element — is phase 5's.

**Amendment, 2026-09-15 (CRIT-2, the macOS half): the bridge answers a foreign target, and what it
answers for an elided root is the window.** Phase 3 built the model half above and the Linux half,
and left `AxBridge#linkedElementsOf` skipping any target its own tree does not hold — so a date
field's `CONTROLLER_FOR` on the calendar window it had opened came back as an empty
`AXLinkedUIElements`, and the popup was nameable from nowhere. It now routes the target the way
`focusedElement()` routes a cursor: through the process's set of open bridges, to the one whose
published tree holds it, minting there, because an element belongs to the window whose tree it
stands for. Where the target is that window's **root**, which §2.2 elides, the answer is the object
AppKit vends for that window — the content view's `-window`, whose `@16@0:8` was read with the other
messages on the macOS 26.6.2 guest — which is the paragraph above applied rather than bent. A target
no open window holds is still dropped. Pinned by
`AxBridgeTest.aRelationTargetAnotherWindowHoldsIsAnsweredThroughThatWindowsBridge`.

**What this does not buy on macOS, and why.** The mirror direction stays unreachable here: the
popup's root carries `POPUP_FOR` on the opener, and that root is exactly the node AppKit's own window
object stands for, so there is no element of ours for a client to ask it on. A reader walks the link
from the opener outwards only. Nothing is lost that this platform ever had, and §13.27's probe is
still what would decide whether AppKit can be made to carry the other direction.

### 1.12 The role enum is closed, and a role may not be added without a truthful mapping in all three tables

```
WINDOW, DIALOG, ALERT, GROUP, SCROLL_PANE, SCROLL_BAR, SPLIT_PANE, SPLITTER, TOOL_BAR,
MENU_BAR, MENU, MENU_ITEM, CHECK_MENU_ITEM, RADIO_MENU_ITEM, SEPARATOR,
BUTTON, TOGGLE_BUTTON, CHECK_BOX, SWITCH, RADIO_BUTTON, RADIO_GROUP,
LABEL, HEADING, IMAGE, VIDEO, CANVAS, CHART, CHART_SERIES,
PROGRESS_BAR, SLIDER, SPIN_BUTTON,
TEXT_FIELD, TEXT_AREA, PASSWORD_FIELD, SEARCH_FIELD,
COMBO_BOX, LIST, LIST_ITEM, TAB_LIST, TAB, TAB_PANEL,
COLOR_CHOOSER, TABLE, COLUMN_HEADER, ROW, CELL, TREE, TREE_ITEM, UNKNOWN
```

`TREE` and `TREE_ITEM` were added by ADR 044 §4 on 2026-09-13, at the price this section names:
a row in each of the three platform tables, a phrase, and twenty-one translations. The AT-SPI
numbers (65 and 91) and names were read off the Fedora 44 guest that day; the UI Automation ids
were already in `UiaIds` and the AppKit outline role and outline-row subrole already in the
checked-in dump, both from the table's pass. The widget had published `LIST` and `LIST_ITEM` for
a day, because a role with no reading cannot be published at all.

`TABLE`, `COLUMN_HEADER`, `ROW` and `CELL` were added by ADR 041 on 2026-09-08, with the rows
§2.1, §2.2 and §2.3 require and the constants read off each guest; that record's §7 is where the
mapping is argued.

Not a string, and not a per-platform constant: the three platforms disagree about the vocabulary, and
each bridge owns a table indexed by ordinal, so every degradation is visible in one file per platform.
The rule that keeps it honest is procedural: **a new role requires a row in §2.1, §2.2 and §2.3 that
names a real platform constant, and a bridge that would have to invent one is an argument for reusing
an existing role.** That is the discipline ADR 032 applied when it refused to classify five thousand
icons.

Three constants exist only because a platform would otherwise be lied to. `SWITCH` is not `CHECK_BOX`:
UI Automation has no switch control type and takes `CheckBox` plus a localized control type, AT-SPI2
has `ROLE_TOGGLE_BUTTON`, and AppKit has `AXCheckBox` with the switch subrole — three answers a single
`CHECK_BOX` would collapse. `HEADING` exists because a `Label` with the title typographic role is a
heading and every platform has one. `RADIO_MENU_ITEM` exists because the tabbed pane's overflow list is
a single-selection group built today out of check items, and reporting independent checkboxes is a lie
the enum can retire (§8).

### 1.13 What a modal blocks is published, not only enforced

Gating actuation is not enough. A screen reader user behind an open dialog is told, by every node
underneath it, `ENABLED` and `FOCUSABLE` and `SHOWING` — so the reader offers the whole background
interface, announces each control as operable, and the invocation is then refused by §1.9's gate with
no way to say why. The tree has to carry the fact, not just the dispatcher.

The scene already computes it exactly. Finding 14: every `Scene` overlay is modal by construction,
and `inputRoot()` — the top overlay, or the root when there is none — is what hit-testing,
`focusTraverse` and `requestFocus` are already confined to. So: **a published node outside
`inputRoot()` loses `ENABLED` and loses `FOCUSABLE`**, and the top overlay's own node carries `MODAL`.
Nothing else changes: those nodes stay in the tree, stay `VISIBLE` and stay `SHOWING`, because they
are genuinely on screen and a user may still want to read what is behind the dialog.

**This is the same mechanism as §1.2's inherited flags and shares its traversal**: the walk carries
an enabled-and-focusable flag down, the modal rule clears it for everything outside `inputRoot()`,
and a disabled ancestor clears it for its own subtree. Two rules, one boolean, one pass — and the
same invariant at the end of both, stated on the bit that can carry it: **the set of nodes published
`FOCUSABLE` is exactly the set `focusTraverse` can reach.** Not the enabled set, and the difference is
not pedantry. `Scene#collectFocusable` prunes at any widget that is not visible or not enabled and
then adds one only when `isFocusable()`, so a `Label`, a `ScrollBar` and a `Separator` inside
`inputRoot()` are `ENABLED` and are not tab stops — as they should be, since a screen reader reads far
more of a window than a keyboard can land on. `ENABLED` is what may be *operated*, `FOCUSABLE` is what
Tab reaches, the modal rule and the disabled-ancestor rule clear both together — which is why one
boolean carries them — and only the second is an equality.

All three platforms carry this on the bit we already have — `IsEnabled` false, `STATE_SENSITIVE` and
`STATE_ENABLED` off (which is what GTK does under a modal grab), `accessibilityEnabled` false — and
none of them has a separate "blocked" property to carry it better. The result is that the tree agrees
with the keyboard, which is the invariant §12.1 already enforces for reading order, applied to a
second axis. Modality on a *native* window is `NativeWindow#isModalBlocked()`, and since 2026-09-07
the walk publishes it the same way: while the owner is blocked, nothing in its tree is `ENABLED` or
`FOCUSABLE`, the window node included, and the whole of it stays `VISIBLE` and `SHOWING` — before
that the walk cleared the two states only outside the scene's own `inputRoot()`, so the owner of a
native dialog offered a reader a whole interface of operable controls that the gate then refused
with no way to say why. §1.9's action gate keeps both tests, because it also has to defend against
a snapshot that predates the modal. **Corrected while implementing:** that predicate is the *owner's* bit, and the window
node's `WindowFacet.modal` is the dialog's — whether the window blocks what it owns — so the walk
filling the facet from `isModalBlocked()` published a native dialog as non-modal and the window it
had frozen as the modal one. `NativeWindow#isModal()`, answered from the backend's modal stack, is
what the facet reads; the DialogPanel step's verification is what caught it.

**Amendment, 2026-09-15 (semantics 5; fix round 2c): what is outside the layer offers nothing
either.** "Nothing else changes" was wrong by one fact. Clearing `ENABLED` and `FOCUSABLE` told a
reader the background was not operable, and every node there still published the verbs its own
state gave it, which the platform is answered from (semantics 5) and §1.9's gate then drops: a
menu-bar title beneath an in-scene cascade offered `COLLAPSE`, the open `ComboBox` and `DatePicker`
fields beneath their in-scene popups `COLLAPSE` and a writable value, and the owner of a native
dialog every verb in its window. So a node outside the layer — the same `reachable` flag, read off
`Scene#accessibleInputLayer`, which the gate reads — publishes **no verb**, neither one it declared
nor one its container claimed on it (the key binding goes with the facet), and **none of the
setters its facets would imply**: its value is published read-only and its text `READ_ONLY`
(`Accessibility#inoperableAt`, called by the walk on the widget's node and every synthetic child it
drew, after the transparency test so a node that offered only verbs keeps its place). What the
node is and holds is untouched, and its states and verbs come back when the layer goes. The
`READ_ONLY` a text gains is the facet's only way to withdraw `SET_TEXT`; it sits beside the
`EDITABLE` a text field declares, which says what the control is rather than what it accepts now.
`kitchen-dialog.txt` moved on 112 lines, all in the blocked host window and every one of them this
alone: 84 lose their action list (40 buttons, 13 tabs, 11 chart series, 10 check boxes and
switches, 7 radio buttons, the three menu titles' `expand, show menu, focus`), and 28 lose it and
gain `read-only` on their value or text (6 combo boxes, 6 sliders, 6 spin buttons, 4 scroll bars,
and the 6 text surfaces, whose `show menu` goes). The dialog's own window did not move. Pinned
by `AccessibleModalTest.everythingOutsideTheOpenLayerPublishesNoVerbAndNoSetter`,
`MenuBarAccessibilityTest.anInSceneCascadeTakesEveryVerbOffTheBarAndPerformsTheOnesItPublishes`,
`ComboBoxAccessibilityTest`, `DatePickerAccessibilityTest` and the ratchet above.
**Amended 2026-09-15 (fix round 2e):** "its value is published read-only and its text `READ_ONLY`"
is withdrawn, and so is the `READ_ONLY` beside `EDITABLE` described after it. A node outside the
layer keeps its value's writability and its text's true `READ_ONLY`; it accepts no setter because it
is published without `ENABLED`, which this section has always cleared there and which a facet now
needs to imply its setter (§1.2's and §1.5's amendments of that date). `kitchen-dialog.txt` moved
back on exactly the 28 lines above, each losing `read-only` and nothing else: from its state list
and its value on the 22 with a value (6 combo boxes, 6 sliders, 6 spin buttons, 4 scroll bars), from
its state list on the 6 text surfaces (3 text fields, the password field, the text area and the
search field). None regains a verb. The three progress bars in that window keep `read-only`, which
is their own.

**Amended 2026-09-15 (fix round 2d): withdrawn from publication, still routed.** The claims a
container made on a widget child beneath the layer are withdrawn from the snapshot, not from the
walk's routing table, which §1.5 records on every walk, published or not. The first cut cleared
the claims before the table read them, so a reader that took a delegated `SELECT` off the snapshot
from before an overlay opened, and sent it after the overlay closed but before the next walk, was
dispatched to the child's own hook instead of the container's. The walk now records the routing
first and withdraws publication after; while the overlay is up §1.9's layer gate refuses the verb.
Pinned by `AccessibleActionTest.aDelegatedVerbSentAfterAnOverlayClosedStillReachesTheContainer`.

**Amended 2026-09-15 (decision 66): the third axis, and the place all three meet.** This section's
argument — a control the scene will not operate must say so in the tree, or the reader offers a whole
interface that does nothing — is not about modals. It is about every reason the scene refuses, and
there are three: the widget or an ancestor is disabled, the node lies outside the layer that owns
input, and **nobody can see it**. The third was still only enforced: a slider in a tab nobody
selected, a hidden media bar's Volume, a button under a widget whose visible flag is false, each
published `ENABLED` with its verbs and its writable value, and each refused on arrival with nothing
said about why — the defect of the first paragraph above, one axis over. The walk now withdraws
verbs from a node published without `VISIBLE` through the same `Accessibility#inoperableAt` call, and
`AccessibleNode#accepts` reads `ENABLED` and `VISIBLE` together (§1.9's amendment of this date).
`VISIBLE` and not `SHOWING`: a node merely clipped out of a scroll viewport keeps everything and is
revealed by the gate before it is performed. Ratcheted over the gallery by
`VerbPolicyRatchetTest.everyPublishedVerbMovesSomething`.

---

## 2. The three platforms, interface by interface

**Every row below that says *posted* means the one inbound path of §1.9**: the bridge calls
`Host#perform(nodeId, action, argument)` from whatever thread the platform gave it, and the scene
posts, resolves and re-checks on the UI thread. No bridge resolves a node id to a widget, on any
platform.

Constants below are named, not numbered, except where a spike read the number off the machine. The
rule §12.3 keeps: **AT-SPI2 roles and states are read from `Atspi-2.0.typelib`; AppKit selectors and
encodings are read from the running AppKit with `class_getInstanceMethod` and
`method_getTypeEncoding`; UI Automation ids are read from `uiautomationcore.h` or the interop assembly
on the guest.** A recalled constant is a defect that compiles.

### 2.1 Windows: UI Automation

| Interface / member | Answered from | Note |
| --- | --- | --- |
| `IRawElementProviderSimple::get_ProviderOptions` | `ProviderOptions_ServerSideProvider` | proven |
| `…::GetPropertyValue(id)` | role, name, description, states, bounds, locale, id, `IsDialog` | `VT_EMPTY` for anything unanswered is accepted; the spike saw UIA ask for ids we do not answer and not complain |
| `…::GetPropertyValue(IsControlElement)`, `…(IsContentElement)` | `true` for every published node | the spike had to answer both, and did, for both its elements. They are how UIA builds its control and content views, and a provider that leaves them `VT_EMPTY` is asking every client to guess which of its elements are worth showing |
| `…::GetPatternProvider(id)` | **the facet set** | Invoke ← `ActionFacet.PRESS`; Toggle ← `ToggleFacet`; RangeValue ← `ValueFacet` (its `IsReadOnly` from `ValueFacet.readOnly`); **Value ← `ValueFacet` or `TextFacet`**; Selection ← `SelectionFacet`; SelectionItem ← `SelectionItemFacet`; ExpandCollapse ← `ExpandFacet`; Scroll ← `ScrollFacet`; ScrollItem ← a scrollable ancestor; Window and Transform ← `WindowFacet`; Text ← `TextFacet` (phase 2, §11) |
| `…::get_HostRawElementProvider` | `UiaHostProviderFromHwnd` on the root, `NULL` on every child | proven |
| `IRawElementProviderFragment::Navigate` | the stored links (§1.4) | Parent, FirstChild, LastChild proven. A popup fragment root answers `NULL` for Parent (§1.11) |
| `…::GetRuntimeId` | `SAFEARRAY(VT_I4){UiaAppendRuntimeId, hi, lo}` | proven: UIA replaced the leading marker with the HWND's own runtime id, which is the contract |
| `…::get_BoundingRectangle` | `UiaRect` of four doubles, screen pixels, top-left | proven |
| `…::SetFocus` | posted `FOCUS` | proven; the root reports `IsKeyboardFocusable` false, so a client's `SetFocus` on the window throws client-side, which is expected |
| `…::get_FragmentRoot` | the window's root node | proven |
| `…::GetEmbeddedFragmentRoots` | `NULL` | in-scene popups are inside this fragment; native popups are their own HWND fragment root |
| `IRawElementProviderFragmentRoot::ElementProviderFromPoint` | deepest node containing the screen point, from the snapshot's bounds | proven. **Not** `Widget#hitTest`: it returns `null` for a disabled subtree at every level, and UI Automation expects to find a disabled button under the pointer |
| `…::GetFocus` | the focused node | proven |
| `IInvokeProvider::Invoke` | posted `PRESS`; raises `Invoke_Invoked` | proven, including raising the event from inside `Invoke` on an RPC thread |
| `IGridProvider` (`get_RowCount`, `get_ColumnCount`, `GetItem`) and `ITableProvider` (`GetRowHeaders`, `GetColumnHeaders`, `get_RowOrColumnMajor`) | `TableFacet`; ADR 041 §7 | `GetItem` answers a realized cell and `null` for a row the walk did not publish, the degradation §4.1 already accepts; column headers are the header group's children; row headers are none |
| `IGridItemProvider` (`get_Row`, `get_Column`, spans of one, `get_ContainingGrid`) and `ITableItemProvider` (`GetRowHeaderItems`, `GetColumnHeaderItems`) | `CellFacet`; ADR 041 §7 | the containing grid is the nearest ancestor with a `TableFacet`; the column header item is that grid's header group's child at the cell's column |
| `IToggleProvider`, `IRangeValueProvider`, `IValueProvider`, `ISelectionProvider`, `ISelectionItemProvider`, `IExpandCollapseProvider`, `IScrollProvider`, `IWindowProvider` | the matching facets | to be built; each is the same vtable-of-closures shape as the four proven ones |
| `UiaRaiseAutomationEvent`, `…PropertyChangedEvent`, `…StructureChangedEvent`, `…NotificationEvent` | the event flush | the first is proven returning `S_OK` from an RPC thread |
| `UiaClientsAreListening` | the listening gate | proven resolvable and callable |
| `UiaDisconnectProvider`; `UiaReturnRawElementProvider(hwnd, 0, 0, NULL)` on `WM_DESTROY` | teardown | both proven returning `S_OK`, process exiting cleanly |

**`IValueProvider` comes from `TextFacet` as well as from `ValueFacet`, and without that a Windows
text field vends no pattern at all.** `TextPattern` is deferred to phase 2 (§11) and a text widget
has no `ValueFacet` — it has never had a numeric value — so mapping `Value` from `ValueFacet` alone
leaves `TextField`, `TextArea`, `PasswordField` and `SearchField` with an empty pattern list on the
one platform where the pattern list is the whole of a control's behaviour. NVDA would find a named
element it cannot read the contents of, cannot set, and cannot report as read-only. So a node with a
`TextFacet` vends `IValueProvider`: `get_Value` is the facet's text — the mask, never the secret, on
a `PasswordField` — `get_IsReadOnly` is the `READ_ONLY` state, and `SetValue` is the whole-value set
§11 already scopes. The same hole existed on macOS, where `accessibilityValue` was mapped from
`ValueFacet` and `ToggleFacet` only; §2.2 adds `TextFacet` there for the same reason and the same
text.

An in-scene dialog is **not** given `WindowFacet`. Vending `IWindowProvider` from a node that is not
an HWND advertises `Close()`, `SetVisualState()`, `WaitForInputIdle()` and `CanMaximize` on an overlay
that has none of them. UI Automation's answer for a dialog rendered inside a window is
`UIA_IsDialogPropertyId`, and that is what the bridge publishes.

Two mechanical facts the spike paid for and the bridge inherits. `JNI.invoke*` names encode only the
pointer-sized and narrow arguments plus the return letter, and `SafeArrayCreateVector`'s 16-bit
`VARTYPE` has no matching overload, so ABI reasoning leaks into that one call site. And a `VARIANT`
must be zeroed whole — 24 bytes — before every write, because leaving `VT_EMPTY` over a stale payload
is a latent crash in a caller that trusts the union.

**Amended 2026-09-15 (phase 3, Windows; decision 1, semantics 4; W3, LAB-NEW-4): `GetFocus` and
`HasKeyboardFocus` answer where the user is.** The row above says `GetFocus` answers "the focused
node", and so did the code: the focused table, tree, list or calendar, never the row, cell, day or
segment its cursor is on, and `HasKeyboardFocus` was the node's own `FOCUSED` bit. Both now answer
the tree's effective focus (`AccessibleTree#effectiveFocus`, the active descendant or the focused
node): a focused table's cursor cell has the keyboard and the table does not, because NVDA 2024.4.2
takes a focus change only from a sender that answers `HasKeyboardFocus` true when it reads it, live,
after the event (readings/nvda-2024.4.2-uia.md §1). Where the cursor resolved into a native popup's
tree (decision 5), `GetFocus` hands over that window's fragment pointer from that window's own
provider, and that element is the one answering `HasKeyboardFocus` true. `HasKeyboardFocus` is
therefore answered by the provider from the tree, not by the per-node property table.
**Amended again 2026-09-15 (review of that change):** the popup window's own root answered
`GetFocus` with a null — nothing of its tree is focused — while its day answered `HasKeyboardFocus`
true, so one provider contradicted itself. A root whose tree has no effective focus of its own now
answers the node of its tree that another open window's effective focus names
(`UiaBridgeTest.aCursorInAnotherWindowsTreeIsRaisedAndAnsweredThroughThatWindowsProvider`). Whether
UI Automation and NVDA accept either answer — a focus element in another window's fragment tree, or
the focus on a window that is not the active one — is decision 5's live assumption, measured first in
phase 5.

**Amended 2026-09-15 (phase 3, Windows; decisions 9, 10; semantics 1 and 5; W1's Selection half,
WINDOWS-NEW-9, WINDOWS-NEW-11): `ISelectionProvider` is served, and the `SelectionItem` verbs are
candidate lists.** The row above lists `ISelectionProvider` as "to be built"; the pattern was
claimed from `SelectionFacet` and a client's `GetPatternProvider` got a null. `GetSelection` now
answers a `SAFEARRAY` of simple pointers to the realized selected members whose selection container,
resolved once at publish (`AccessibleNode#selectionContainer`), is this node — a selected row the
widget has not realized is not listed (§4.1) — and `get_CanSelectMultiple`/`get_IsSelectionRequired`
answer the facet. `get_SelectionContainer` answers that same resolved container, where it had
climbed to any ancestor with a selection. `Select` posts `SELECT`, `AddToSelection` the first of
`ADD_TO_SELECTION`, `SELECT` the node publishes, `RemoveFromSelection` `DESELECT`; a node publishing
none of its list is refused with `0x80131509` and nothing is posted (it had posted `SELECT` for
both of the first two, whatever the node published). Every `BOOL*` getter writes four bytes of `1`
or `0`, read on the guest (readings/windows-dump-uia-marshalling.txt); it had written a
`VARIANT_BOOL`'s two.

**Amended 2026-09-15 (phase 3, Windows; decision 39, semantics 5; W1's Scroll half,
WINDOWS-NEW-7): `IScrollProvider` is served, `ScrollItem` is the node's verb, and no node claims
`Window` or `Transform`.** The pattern row above says "Scroll ← `ScrollFacet`; ScrollItem ← a
scrollable ancestor; Window and Transform ← `WindowFacet`", and the interface row lists
`IScrollProvider` and `IWindowProvider` as "to be built"; the Scroll, Window and Transform patterns
were claimed and a client's `GetPatternProvider` got a null for each. As built:
- **Scroll's getters** answer the scroll facet as the platform's own `ScrollViewerAutomationPeer`
  does, read as IL on the guest (readings/windows-dump-uia-provider-conventions.txt §1): a percent is
  the facet's times 100 on an axis that scrolls and `UIA_ScrollPatternNoScroll` (−1, read 2026-09-13)
  on one that does not; a view size is a percent on either axis (100 for nothing to scroll); the two
  `BOOL*` flags are four bytes.
- **`Scroll(h, v)`** maps each `ScrollAmount` (read 2026-09-13) to that axis's `SCROLL_BAR` child's
  published stepping verb, posted on the bar: `SmallIncrement`/`SmallDecrement` →
  `INCREMENT`/`DECREMENT`; `LargeIncrement`/`LargeDecrement` → the page verbs decision 39 names "if
  published", which the model does not have, so a large step is refused; `NoAmount` leaves the axis.
  **`SetScrollPercent(h, v)`** posts `SET_VALUE` on the axis's bar, its own range scaled by the percent,
  where the bar accepts one (`AccessibleNode#accepts`); `NoScroll` leaves the axis. The refusals come in
  the peer's order: `UIA_E_ELEMENTNOTENABLED` (0x80040200, read 2026-09-13) for a node that is not
  `ENABLED`, then `0x80131509` for an axis asked to move that cannot scroll, then (percent only)
  `0x80131502`, the managed `ArgumentOutOfRangeException` (read 2026-09-15), for a percent outside
  0..100 or not a number, then `0x80131509` for an axis with no bar or a bar that does not publish the
  verb or take the value. Both axes pass every check before either is posted. The scroll bar's own
  step is one viewport (`ScrollBar`), so a client's small step moves a page; that is decision 39 as
  written, recorded for phase 5's client runs.
- **`ScrollItem`** is vended only on a node publishing `SCROLL_INTO_VIEW`, and `ScrollIntoView` posts
  it through the same candidate gate (semantics 5); it had been vended on any node with a scrollable
  ancestor and posted whatever the node published.
- **`Window` and `Transform`** are claimed by no node: the root answers `get_HostRawElementProvider`
  with the provider UI Automation made for the HWND, which serves both for the real window (the
  probe's `[Window,Transform]` came from it), and this bridge serves neither interface. An in-scene
  dialog still says `IsDialog`. `UiaPatternsTest.everyPatternANodeCanClaimIsOneThisBridgeServes` now
  fails for any claim with no interface behind it.

**Amended 2026-09-15 (review of the Windows phase-3 work): which items lose `ScrollItem`.** Vending
`ScrollItem` only with `SCROLL_INTO_VIEW` takes it from every item whose widget does not publish that
verb, and at this date that is most of them. `ListView` rows and `Tree` rows publish it (decision 20),
and a focusable widget gets it free from the walk, but a `Table`'s synthetic `ROW` publishes `SELECT`,
`ADD_TO_SELECTION`/`DESELECT` and `FOCUS` and its synthetic cells `FOCUS` only, and a `CalendarView`'s
day cells `SELECT` and `FOCUS`: none of them vends `ScrollItem` on Windows, so a client asking a table
row or cell, or a day, to scroll into view finds no pattern. Decision 20 says tree and list rows
publish `SCROLL_INTO_VIEW` "like Table", which reads as though a table row did; it does not. Owed to
the `Table` and dates widgets under decision 20 (ADR 041 §7, ADR 042), not to this bridge, which vends
the pattern the moment the verb is published.

**Amended 2026-09-15 (phase 3, Windows; decisions 2, 7, 20, semantics 5 as amended the same day;
W6, TREE-MISS-8, WINDOWS-NEW-10, CRIT-7): every verb and setter goes through
`AccessibleNode#accepts`.** The rows above say `SetFocus` "posted `FOCUS`" and `Invoke` "posted
`PRESS`", and the pattern slots posted `TOGGLE`, `EXPAND`, `COLLAPSE` and `SET_TEXT` the same way,
whatever the node published; fix round 2e's `refusedSetter` refused a setter on a node not `ENABLED`
and nothing else. As built: `Invoke` [`PRESS`], `Toggle` [`TOGGLE`], `Expand` [`EXPAND`], `Collapse`
[`COLLAPSE`], `SetFocus` [`FOCUS`], `ScrollIntoView` [`SCROLL_INTO_VIEW`] and the `SelectionItem` lists
post the first verb the node publishes on the snapshot of the call. `Value.SetValue` posts `SET_TEXT`
on a node with a text facet and `SET_VALUE` carrying the text on a value facet (a spinner's "07:30",
a combo's item), and `RangeValue.SetValue` posts `SET_VALUE`, each only where `accepts` says the
node takes it (a writable facet on an `ENABLED` node). A verb or setter the node does not accept is
refused synchronously and nothing is posted: with `UIA_E_ELEMENTNOTENABLED` (0x80040200) when the
node is not `ENABLED` — disabled, under a disabled ancestor, outside the layer that owns input — and
`0x80131509` when it is enabled and does not offer it. The not-enabled code for the **verbs** as well
as the setters (the addendum names the setters) is the platform's own order, read as IL on the guest
2026-09-15 (readings/windows-dump-uia-provider-conventions.txt §1b): `ButtonAutomationPeer.Invoke`,
`ToggleButtonAutomationPeer.Toggle`, `ExpanderAutomationPeer`'s and `TreeViewItemAutomationPeer`'s
`Expand`/`Collapse`, `SelectorItemAutomationPeer`'s three verbs, `TextBoxAutomationPeer.SetValue` and
`RangeBaseAutomationPeer.SetValue` all throw `ElementNotEnabledException` before anything else, and
`InvalidOperationException` only after. Not followed from that reading: `TextBoxAutomationPeer`
answers a read-only text box's `SetValue` with `ElementNotEnabledException` too; this bridge answers a
read-only but enabled node `0x80131509`, as semantics 5 words it. `Value.get_IsReadOnly` is the
`READ_ONLY` state, which the model derives from a value facet's `readOnly` (`Accessibility#value`), so
it already answered the facet's writability. The Expand/Collapse, Toggle and Value patterns are still
vended from their facets, because their state is what a reader reads; a verb delegated to a container
(a tree row's `EXPAND`) is posted on the row's id and the scene routes it (decision 7).

**Amended 2026-09-15 (review of the Windows phase-3 work): the not-enabled order is read for the
entry points named, and `SetFocus` and `ScrollIntoView` were read afterwards and differ between
providers.** The amendment above calls the not-enabled-first order "the platform's own order, read as
IL" for every verb; its reading covers the peers it lists and no `SetFocus` body, and its `ScrollItem`
listing named a type that does not implement the interface. Both were then read on the same guest
(`scripts/a11y/windows/dump-uia-focus-and-scroll-item.ps1`,
readings/windows-dump-uia-focus-and-scroll-item.txt, UIAutomationCore.dll 7.2.26100.9457, 4.8.9347
assemblies), and the platform's providers do not agree. The client-side proxies of the Win32 controls
keep the order: `ProxySimple`'s `IRawElementProviderFragment.SetFocus` throws
`ElementNotEnabledException` (0x80040200) when the window is not enabled and
`InvalidOperationException` (0x80131509) when the element is not keyboard-focusable; `ListViewItem`'s
and `WindowsTabItem`'s `ScrollIntoView` throw `ElementNotEnabledException` before
`InvalidOperationException` for a container that cannot scroll. WPF checks no enabled bit on either:
`ElementProxy.SetFocus` reaches `UIElementAutomationPeer.SetFocusCore`, which throws
`InvalidOperationException` when `UIElement.Focus()` refuses (as it does for a disabled element), and
`ListBoxItemAutomationPeer`, `DataGridItemAutomationPeer`, `TreeViewItemAutomationPeer` and the
list-box and tree-view item proxies scroll whatever the item's state. This bridge keeps
`UIA_E_ELEMENTNOTENABLED` first for both, the Win32 proxies' order and the one every other entry
point here follows, pinned by `UiaFragmentProviderTest.setFocusReachesTheToolkitOnlyWhereTheNodePublishesFocusAndSaysSoWhenTheNodeHasGone`
and `UiaPatternProvidersTest.scrollIntoViewIsPostedOnlyWhereTheNodePublishesIt`; which of the two
answers a client prefers is not read, and the choice is put to the owner with the verbs'.

**Amended 2026-09-15 (phase 3, Windows; decision 4, semantics 6; W4, CRIT-6): position, set size and
level are answered, and tree rows nest in navigation.** The `GetPropertyValue` row names neither
`PositionInSet` nor `Level`, and the `Navigate` row says "the stored links"; every selection item's
position answered `VT_EMPTY`, and a tree's rows, published flat under the tree, were all its
children. As built: `PositionInSet` (30152) and `SizeOfSet` (30153) are the `SelectionItemFacet`'s
numbers and `Level` (30154, read from UIAutomationCore.dll's type library 2026-09-13) the
`HierarchyFacet`'s, each an integer and `VT_EMPTY` for a zero. The level passes through unchanged:
the platform's base was read off native trees on the guest 2026-09-15
(`scripts/a11y/windows/read-native-tree-levels.ps1`, readings/windows-read-native-tree-levels.txt):
a Win32 tree view answers `Level` 1 for its root items, 2 and 3 below, and `PositionInSet`/`SizeOfSet`
one-based among siblings; a WPF 4.8 tree answers 0 for all three (UIA's default for a provider that
answers nothing). **`Navigate` nests `TREE_ITEM` rows**: a row with a positive level has as its parent
the nearest earlier sibling row of a lower level, or the node it hangs under when there is none (a
row whose parent row is not realized); a row's children are its own children, then the later sibling
rows whose parent that makes it; every other node keeps the stored links. NVDA 2024.4.2 counts a tree
item's `TreeItem` ancestors for its level and overwrites UIA's `Level` with that count
(readings/nvda-2024.4.2-uia.md §2), and both native trees nest their items in the raw view, so this is
what makes NVDA say the right level. Structure changes keep naming the stored parent (a removed row's
`ChildRemoved` goes to the tree), which a client re-reading the tree reconciles; `UiaFragmentTest`
holds navigation to one consistent tree reaching every node once, and `UiaTreeRowsTest` a real
`Tree`'s rows' ancestor counts to their published levels. **Recorded as seen:** through the COM client
the Win32 tree's items answered `ControlType` Tree and an empty `Name` in that reading, while the
managed client read them as `TreeItem`s with their names; it bears on no number used here.

**Amended 2026-09-15 (review of the Windows phase-3 work; decision 22): a row nests only through
unbroken row indices, and a row whose parent row is not published is heard a level too high up.**
The amendment above takes "the nearest earlier sibling row of a lower level" wherever it stands. A
`Tree` publishes only its mounted rows and the cursor row it keeps realized off screen while focused
(decision 22), so that row need not be the row's parent: with a root row kept as the cursor and the
viewport inside another root's children, those children nested under the cursor row. Now the search
walks back only while each earlier row carries the flat row index right above the one before it
(`HierarchyFacet#row`); at a gap, or on a row whose index is unknown (0), it stops and the row hangs
under its stored parent (`UiaFragmentTest.aRowNestsOnlyUnderARowItsUnbrokenRowIndicesReach`,
`UiaTreeRowsTest.theKeptCursorRowIsNeverTheParentOfTheRowsInTheViewport`). **The consequence a
reader hears:** once a `Tree` is scrolled so that a branch's own row is above the viewport, that
branch's visible child rows have no published parent row and hang under the tree, so NVDA 2024.4.2,
which counts `TreeItem` ancestors and overwrites `Level` with the count, says a lower level than the
published one (a level-2 row read as level 1; measured headlessly for a tree scrolled 1000 px into an
80-row branch, `UiaTreeRowsTest.aTreeScrolledIntoABranchNestsNoRowUnderARowThatIsNotItsParent`). The
`Level` property still answers the true number, for a client that reads it. A wrong level was
preferred to a wrong parent: the first is heard only for rows whose branch is scrolled away, the
second put rows under another branch at a plausible level. What would close it is the widget keeping
the ancestor rows of its first mounted row realized, as it keeps the cursor row; that is the `Tree`'s
to decide (ADR 044 §4), and phase 5 hears the degradation on `--scene tree-reader` scrolled into a
branch.

**Amended 2026-09-15 (phase 3, Windows; decision 8, semantics 2 and 3; WINDOWS-NEW-8, TABLE-NEW-11):
cells and headers are found by `CellFacet`.** The Grid/Table rows above say `GetItem` answers "a
realized cell" and "column headers are the header group's children", and the column header item is
"that grid's header group's child at the cell's column". As built until this date, `GetItem` matched a
`ROW` child by its `SelectionItemFacet` position (row + 1), so a calendar, whose week rows carry no
position, answered no day, and a row whose position is not its view index answered the wrong one;
the header group was the table's *first* `GROUP` child, so a footer or any other group ahead of it
was answered as the headers; and a cell's header item was the header at the cell's column index among
the group's children. Now: `GetItem(r, c)` answers the node with `CellFacet(r, c)` among the children
of the table's `ROW` children whose nearest table is this one (a widget cell under its synthetic row
included), null for a negative row or an unrealized one; `GetColumnHeaders` answers, in reading order,
every `CellFacet(-1, c)` child of the table's direct `GROUP` children, a footer's `-2` cells never; and
`GetColumnHeaderItems` answers the header whose `CellFacet` column is the cell's.

**Amended 2026-09-15/16 (phase 3, Windows, and the two fix rounds after it; decision 36): a sorted
header says its direction in `ItemStatus` beside `HelpText`.** Written in three passes — read first,
then carried, then made to raise — and folded here as one.

*The reading.* UI Automation has no sort-direction property (none in UIAutomationCore.dll's type
library, read 2026-09-13), so it was read off native headers on the Windows 11 guest (10.0.26200,
UIAutomationCore.dll 7.2.26100.9457, .NET Framework 4.8 (Release 533509, 4.8.09221; UIA and WPF
assemblies 4.8.9347, WinForms 4.8.9325); 2026-09-15,
`scripts/a11y/windows/read-native-sort-direction.ps1`, readings/windows-read-native-sort-direction.txt):
one column sorted each way and one not, every property id 30000-30200 read through the COM client.
**File Explorer's details view carries it in `ItemStatus` (30026)** of the sorted column's header — a
`SplitButton` (50031) of class `UIColumnHeader` under a `Header` — as a localized phrase ("Classificado
(Crescente)", "Classificado (Descrescente)" on that pt-BR guest, switching with `SortColumns` and
nothing else changing), and answers no `ItemStatus` on the others. **A WPF `DataGrid` (`SortDirection`),
a WinForms `DataGridView` (`SortGlyphDirection`) and a Win32 list view (header format flags) carry
nothing**: no property differs between their sorted and unsorted headers, and the managed peers' and
proxies' IL (same reading, part 1) reads no direction. So the platform's carrier is Explorer's, a
status phrase on the header. NVDA 2024.4.2 reads `ItemStatus` as a description only for an element of
class `UIColumnHeader` (readings/nvda-2024.4.2-uia.md), while it reads `HelpText` as every element's
description — which is why a move to `ItemStatus` **alone** would silence it on Limn's headers, and
why both are answered.

*What is answered.* `UiaProperties.valueOf` answers `ItemStatus` (30026) on a cell of the header row
whose `Sort` is not `NONE`, with **the node's description** — the localized phrase the model resolved
at publish, where a locale scope was open, which is the whole reason `CellFacet` kept the description
beside the enumeration the other two platforms read (§1.2's amendment of 2026-09-15). `HelpText`
(30013) needed no change: it already answers that same description, so the settled list's "`ItemStatus`
**and** `HelpText`" is true of one string published once, and NVDA keeps hearing exactly what it heard.
The header row is what makes a header: a footer cell (`-2`) and a data cell head no column, whatever
direction their facet carries.

*A choice and not a reading, marked where it is made:* **a busy sorted header answers the busy word
alone.** `ItemStatus` is one string and two facts want it; nothing on the guest reads on composing them
(Explorer's header was not busy), and joining two translated fragments with punctuation chosen on a
thread with no locale open would invent a sentence in twenty-one languages. Busy is the transient state
the property exists for, and the direction is not lost while it holds, because `HelpText` still carries
it. A live Narrator or Inspect run over a header that is both would settle it; phase 5. Pinned by
`UiaPropertiesTest.aSortedHeaderSaysItsDirectionInItsStatusAndInItsHelpTextAndBusyWinsOverBoth`.

*What raises it.* This record said for a day that nothing did — "a sort arrives as a publish and not as
a state change" — and that was half right. A sort that moves is not a state change and it is **not
silent**: it moves the header cell's description, and the differ emits a `DESCRIPTION_CHANGED` for
exactly that. The bridge raised `HelpText` alone, so a client that caches `ItemStatus` — the property
File Explorer's convention exists for — went on reading the direction the column used to be sorted in.
A `DESCRIPTION_CHANGED` on a **cell of the header row** now raises `ItemStatus` as well as `HelpText`,
through a second mapping named `UiaBridge.alsoChangedProperty`; it is the only change today that moves
two properties outside `raiseValue`.

*And what a change carries is what the getter answers* — the rule that replaced this amendment's own
first attempt at it (2026-09-16, the fix round's review). That attempt said an empty string is what
`ItemStatus` carries for "nothing to say". It is not: a node with no status answers `null`, written as
`VT_EMPTY`, which is what the getter's own comment says ("an item that is not busy has no status at all
rather than a status saying it is idle"). The `BUSY` mapping wrote `""` when busy cleared, and on a
header that is both busy and sorted that was the stale `ItemStatus` this whole amendment exists to
prevent, raised by the mapping itself: the client was told the status was `""` the moment busy cleared,
while `GetPropertyValue` answered "Sorted ascending". So on all three of its arms an `ItemStatus`
change carries what the getter answers — the busy word while busy holds; for a busy that clears, the
**not-busy** answer for that node (the sort phrase on a sorted header, nothing anywhere else); and for
a description, the description only where the getter reads it as a status. It is the not-busy answer
and not `GetPropertyValue` itself because the node in the published tree carries `BUSY` on the busy
side of the change, so asking the getter for the old value of a busy just set would answer the busy
word twice. The same rule closes the other gap in the pair of guards: the raise's guard is the header
row *while the getter also asks for a direction*, so a header cell whose description is its own — no
widget writes one today; `Table` writes only the sort phrase — raises a change from nothing to nothing
instead of announcing a status the element denies. The wider guard stays, because the change that
*ends* a sort leaves the facet at `NONE` and is exactly when a cached direction is most wrong; and
nothing is raised while `BUSY` holds, which is the getter's choice again: busy owns the one string
while it lasts. Pinned by
`UiaBridgeTest.aSortedHeadersDescriptionMovesTheStatusThatCarriesItAndADataCellsDoesNot`, red three
ways (the `ItemStatus` arm removed; the header-row guard dropped, so a data cell's own description is
raised as a status; the busy guard dropped), driving a description change on the footer cell and on the
unsorted column's header too, and by
`UiaPropertiesTest.whatAnItemStatusChangeCarriesIsWhatTheGetterAnswers` (red with the empty string put
back, and red with the description arm removed).

*What phase 5 still hears* is the composition choice — a header that is both busy and sorted — and not
whether the direction is announced at all.

**Amended 2026-09-15 (phase 3, Windows; WINDOWS-NEW-1, WINDOWS-NEW-3): `UiaRaiseNotificationEvent` and
`UiaRaiseStructureChangedEvent` are bound and raised.** The event-flush row lists both; neither was
bound, an `ANNOUNCEMENT` (node `0`) was mapped to the notification event id and then dropped at the
held-element gate, and a `STRUCTURE_CHANGED` went through `UiaRaiseAutomationEvent`, which carries no
type and no runtime id. Both entry points are bound optionally, outside `Uia.isAvailable`, with the
parameter lists read on the guest 2026-09-13 (readings/windows-dump-uia-entry-points.txt: ordinals 97
and 98, not forwarded). How §2.4 raises them is amended there.

### 2.2 macOS: NSAccessibility

| Attribute / action / notification | Answered from | Note |
| --- | --- | --- |
| `accessibilityRole`, `accessibilitySubrole` | role | role constants resolve by `dlsym` on AppKit; `NSAccessibilityButtonRole` proven to dereference to `AXButton` |
| `accessibilityRoleDescription` | localized from the role under the node's locale | **measured, and the reason is narrower than "AppKit's default is English-only"**: `NSAccessibilityRoleDescription()` localizes against the *calling process's* bundle localization, and a JVM launched from a jar has none — so on the phase 7 guest, whose entire desktop is pt-BR, VoiceOver said "checkbox", "text field" and "slider" in English while wrapping them in its own Portuguese ("Você está em um item do tipo campo de texto"). Deferring to AppKit is therefore not an option that works, on this or any non-English system, and the phrase has to be ours. It is **not yet implemented**: the catalog it needs does not exist, and the six roles §7 already degrades would want entries in it too |
| `accessibilityTitle` / `accessibilityLabel` | name, chosen by `nameFrom` | Finding 5. `CONTENT` → title; anything else → label; **never both** |
| `accessibilityHelp` | description | the tooltip lands here when it is not the name |
| `accessibilityValue`, `accessibilityMinValue`, `accessibilityMaxValue` | `ValueFacet`; `ToggleFacet` as `@0`/`@1`/`@2`; **`TextFacet` as its text** | three facets share one attribute, which is why they are separate facets rather than one field. A text node with no `TextFacet`-sourced value is a field VoiceOver cannot read (§2.1) |
| `setAccessibilityFrameInParentSpace:` | bounds in the **parent element's** space, y measured from its bottom | **proven end to end**: `(40,40,160,48)` in parent space read back as `rect(240,412,160,48)` on screen. AppKit owns the flip and the title bar (§1.8). `accessibilityFrame` is implemented too, answering the same box, for a client that asks the element directly. Struct-by-value both ways needs libffi; `CGRect` proven at size 32, alignment 8. **"Parent" is literal, and an earlier draft of this row said "the content view's space", which is true only of the root's own children**: the phase 7 probe run measured the offsets adding up through three levels — a child at `(20,20)` of a group at `(20,60)` of the view lands at the view's origin plus `(40,80)` — so a bridge hands each node its box **relative to its own parent node**, not relative to the view |
| `accessibilityParent`, `accessibilityChildren` | the snapshot links | **the top of the tree is pushed, everything below it is pulled** — see below. `setAccessibilityChildren:` on the content view is proven sufficient to place a Java-built element under the window a screen reader walks, and the push is **repeated whenever the root's children change**, because that array is a snapshot AppKit holds and an overlay opening changes it |
| `accessibilityFocusedUIElement` | the focused node's element | **not proven, and not obviously ours to answer**: our elements are not responders, and the spike never moved focus. §13 carries the experiment; until it runs the bridge posts `AXFocusedUIElementChanged` (which is delivered, and only at application level) and does not claim the attribute |
| `isAccessibilityElement` | `true` for every published node | transparent and ignored widgets never become nodes |
| `accessibilityEnabled`, `accessibilityFocused` / `setAccessibilityFocused:` | states | `setAccessibilityEnabled:` proven; a `BOOL` argument rides the low bits of a pointer-sized slot |
| `accessibilitySelectedChildren` | `SelectionFacet` | |
| `accessibilityRows`, `accessibilityColumns`, `accessibilityHeader`, `accessibilitySelectedRows`, `accessibilityRowCount`, `accessibilityColumnCount` | `TableFacet`; ADR 041 §7 | rows are the realized `ROW` children; columns are synthesised, one per header cell; the header is the table's first group child. Constants and selector encodings read off the guest before the bridge grew them |
| `accessibilityRowIndexRange`, `accessibilityColumnIndexRange` | `CellFacet`; ADR 041 §7 | a range of one at the cell's row and column |
| `accessibilityHitTest:` | **implemented on our own element class, and it must recurse to the bottom itself** | the spike measured that with no override at all `AXUIElementCopyElementAtPosition` found its one element, and concluded AppKit hit-tests from the frames. It does — **for one level only**. The phase 7 probe run put three grandchildren under a group and three points inside three different grandchildren all resolved to the *group*, which is the level that was pushed onto the content view; pushing `setAccessibilityChildren:` at every level as well changed nothing, so this is not stored-versus-pulled children. Overriding the selector on `LimnProbeElement` resolved all four probed points to the correct deepest node, at depth 3. **AppKit sends it exactly once**, to the element it already resolved from the pushed array, so the override walks the whole subtree rather than returning one level. The point arrives in screen space with a bottom-left origin — the same space `accessibilityFrame` answers in, so no flip of ours. It goes on our own class, so §13.16 stays withdrawn: nothing is installed on a class GLFW owns |
| `accessibilityIdentifier` | the node id, as a string | stable across frames by §1.3 |
| `accessibilityPerformPress`, `…Increment`, `…Decrement`, `…ShowMenu`, `…Pick`, `…Cancel`, `…Confirm` | `ActionFacet` | **press proven end to end**: an out-of-process client's `AXUIElementPerformAction(kAXPressAction)` arrived in Java on the main thread. AppKit's encoding is `B16@0:8`, so the return is a C `bool`. The other six are the same shape and are untested |
| `accessibilityNumberOfCharacters`, `accessibilitySelectedText`, `accessibilitySelectedTextRange`, `accessibilityStringForRange:`, `accessibilityRangeForLine:`, `accessibilityInsertionPointLineNumber` | `TextFacet` | `NSRange` is UTF-16, which is `TextEditModel`'s unit exactly |
| `accessibilityFrameForRange:` | **not answered in the first cut** | §11: there is no geometry seam behind it |
| `NSAccessibilityPostNotification` | the event flush | **proven delivered out of process** to a real `AXObserver`, carrying the updated value. `AXValueChanged` reaches an observer registered on the element *or* on the application element; `AXFocusedUIElementChanged` reaches **only** the application-element registration, so focus is posted at application level and never per element |
| `…PostNotificationWithUserInfo` with `AnnouncementRequested` | `ANNOUNCEMENT` | politeness rides `NSAccessibilityPriorityKey` |

**Amended 2026-09-15 (phase 3, the macOS bridge; the rows above stand as written and these
sentences say what the bridge now does where they differ).** *Where the user is* (decision 1,
semantics 4): `accessibilityFocusedUIElement` — answered on the content view since §13.22 — and
`isAccessibilityFocused` both answer from the tree's `effectiveFocus()`, the cursor item under the
focused widget when there is one, so the table under the keyboard answers false and its cursor cell
true. A cursor resolved into a native popup's tree (decision 5) is answered with the element the
popup window's own bridge mints, and the popup's view, with nothing of its own focused, answers the
same element; the bridges of a process's open windows find each other through one process-wide set,
entered on a publish and left on a detach. *Rows* (M2; semantics 1 and 2): an outline and a list
holding a selection answer `accessibilityRows`, `accessibilityVisibleRows` and
`accessibilitySelectedRows` from their realized members, whatever role a cell kept, and each member
answers `accessibilityIndex` zero-based — the hierarchy facet's flat row less one for an outline row,
its position in the set less one for a list row, `NSNotFound` when the number is unknown — because a
native `NSOutlineView` answered AXIndex 0, 1, 2… down its visible rows and no `AXRowCount` (read on
the macOS 26.6.2 guest, 2026-09-15, `scripts/a11y/macos/outline-probe.swift`). *The gate refuses
getters too*: AppKit honours a refused getter (read 2026-09-13, §6 of that day's macOS readings), so
the row selectors are refused on everything that is not a table, an outline or a list, the index on
everything that is not a row, and the two counts on everything that is not a table, instead of
answering nil, −1 or zero there. *Selection* (MACOS-NEW-2; semantics 1): a container's selection is
read off the attribute of its shape — `accessibilitySelectedRows` for an outline, a list or a table
of rows; `accessibilitySelectedCells` for a grid whose members are cells, a calendar's days;
`accessibilitySelectedChildren` for anything else holding one, a tab strip — each answered from the
selected members whose selection container it is, wherever they hang, and each refused where it is
not the container's shape, as the native outline answers `AXSelectedRows` and no
`AXSelectedChildren`. **Corrected 2026-09-16 (fix round 3b; the phase-3 critic's semantics-1
minor):** `accessibilitySelectedRows` did not read that rule — it answered the container's direct
`ROW` children carrying `SELECTED`, which names the same elements for every container Limn ships and
parts from the rule at the first whose rows hang under a synthetic body. It now walks the members
`accessibilitySelectedChildren` walks, narrowed to the members that are rows, so a calendar's
selected day stays under `AXSelectedCells` and a row that declares it belongs to no container
(`containerlessSelectionItem`) is none of the table's selected rows; the `setAccessibilitySelectedRows:`
write path reads the same set, so a client can write back what it read. `accessibilityRows` and
`accessibilityVisibleRows` still answer a table's `ROW` children by structure (ADR 041 §7): "through
synthetic ancestors" is a fact the model resolves once at publish and carries on a selection member
and nowhere else, so no bridge can apply it to a row that is a member of nothing. **That leaves the
two row listings able to disagree, and the correction does not close it:** for every container Limn
ships the selected rows are a subset of the rows, and in the synthetic-body shape they are not —
`accessibilitySelectedRows` names a row `accessibilityRows` does not, which is incoherent for a
client. Closing it needs a policy for `accessibilityRows` that no decision, ADR or reading settles
(which nodes a table's row listing descends through) or a model that carries syntheticness or a row
list into the snapshot; both are cross-bridge, Linux's `GetSelectedRows` half having the same shape,
and both are the orchestrator's. Open, named at `AxGrid#selectedRows` and asserted in
`AxGridTest.aTablesSelectedRowsAreTheMembersOfItsSelectionWhereverTheyHangUnderIt`, which pins
`AXSelectedRows` naming a row `AXRows` answers nothing for; nothing Limn ships is in that shape today. *Recorded the same
day (the macos-B review):* the native outline also answered
`AXSelectedCells` — the `AXCell` its selected row holds — and an outline or a list here deliberately
does not: a Limn row holds no cell element, its children being the application's own widgets, so the
answer would repeat the rows under a cell's attribute or name an arbitrary widget, and the native
outline told its selection only as `AXSelectedRowsChanged`. Whether VoiceOver reads a native outline's
selected cells at all is phase 5's to hear. *Disclosure* (M1): an outline row answers `isAccessibilityDisclosed` from its
expand facet, `accessibilityDisclosureLevel` as the hierarchy facet's level less one, and
`accessibilityDisclosedByRow` / `accessibilityDisclosedRows` by walking the outline's realized rows
while their flat row numbers run without a gap — a gap answers nothing rather than a grandparent —
because the native outline's rows answered AXDisclosureLevel 0 at the top, their parent row and the
rows one level down, on leaves too, and **no `AXExpanded`**; so `isAccessibilityExpanded` is answered
for every other node with an expand facet and refused on an outline row, and a level of zero refuses
the level getter (semantics 6). *Press and confirm* (MACOS-NEW-5; semantics 5): both map to the
candidates `PRESS`, `TOGGLE`, `SELECT`, `EXPAND`, `COLLAPSE` in that order, the first the node accepts
(`AccessibleNode#accepts`) posted, so a combo box, a menu title or a date field that publishes only the
one of `EXPAND`/`COLLAPSE` its state allows is pressed open or shut, and a node publishing no verb is
offered no press whatever facet it carries; `…Pick` stays absent (AxActions says why). *The setter
half* (MACOS-NEW-11; semantics 5): `setAccessibilityFocused:` YES posts `FOCUS`;
`setAccessibilitySelected:` YES `SELECT`, NO `DESELECT`; `setAccessibilityDisclosed:` (an outline row)
and `setAccessibilityExpanded:` (anything else that opens) YES `EXPAND`, NO `COLLAPSE`;
`setAccessibilityValue:` a string as `SET_TEXT` to a text, a number as `SET_VALUE` and a string as
`SET_VALUE` of text to a writable value — each posted only where `AccessibleNode#accepts` holds for
that verb, never waited for. **Settable is the gate's answer for the setter** (read on the guest
2026-09-13), so each of these is offered exactly where its write would post, AXDisclosing only on a
row that can open as the native outline's is, and every other `setAccessibility…` selector —
`NSAccessibilityElement`'s stored setters, which a client read as settable on every element, `AXRole`
included — is refused on every node. The setters are installed only together with the gate. *Corrected
the same day (the macos-B review; MACOS-NEW-11's last setter):* `setAccessibilitySelectedRows:` is
installed too, settable where a container's selection is its rows and a realized row takes a selection
verb. Read on the guest 2026-09-15 (`scripts/a11y/macos/selection-writes-probe.swift`), a native
outline's selection becomes exactly the rows written in either mode — one row replaces, two rows in a
multi-select outline become the selection, an empty array empties it, two rows in a single-select
outline are refused (`kAXErrorIllegalArgument`) — and `AXSelected` YES on a second row of a
multi-select outline **replaces** the selection too, so the `SELECT` it posts is right in both modes.
So one row written posts `SELECT`; several, or none, post the difference — `DESELECT` on each selected
row left out, `ADD_TO_SELECTION` on each written row not selected — and the write is refused whole
unless every row accepts its verb, or when it names an element that is not a row of the container. A
single-select row publishes no `DESELECT` (decision 20), so there an empty array, and `AXSelected` NO,
are refused where the native outline, which allows an empty selection, clears it.
*`AXScrollToVisible`* (new in macOS 26, and with no selector anywhere): read on the guest 2026-09-15
(`scripts/a11y/macos/scroll-to-visible-probe.swift`), an `NSAccessibilityElement` subclass answering
the legacy `accessibilityActionNames` has its perform of that name delivered to
`accessibilityPerformAction:`, while a custom action of that name is never run and a guessed
`accessibilityPerformScrollToVisible` never entered; and answering the names replaces AppKit's derived
list. So the bridge answers `accessibilityActionNames` with every action the node offers plus
scroll-to-visible where it accepts `SCROLL_INTO_VIEW`, and `accessibilityPerformAction:` posts the verb
a listed name means; the pair is installed together, and only with the gate.
*Table cells, rows and headers (MACOS-NEW-4, MACOS-NEW-9, MACOS-NEW-10; semantics 2 and 3; the
same day):* `accessibilityCellForColumn:row:` answers the node whose `CellFacet` is (row, column)
under one of the table's `ROW` children and whose nearest table ancestor is the table — a widget cell
under its row included — and never reads a selection position; a table row's `accessibilityIndex` is
its cells' row, so a calendar week, which carries no selection item, is found and numbered like any
row; the header of column c is the child with `CellFacet(−1, c)` of one of the table's direct group
children, matched by column, and a table with no such child — its header hidden, its footer shown —
has no `accessibilityHeader` and no column headers at all, where the row above said "the table's
first group child". A native `NSTableView` read on the guest (2026-09-15,
`scripts/a11y/macos/table-probe.swift`) answered no `AXHeader` without its header view and no
`AXColumnIndexRange` on its header buttons, so both are refused there, and the two index ranges are
answered on data cells only.
*Columns (M4; decision 34, the same day):* the row above said "columns are synthesised, one per
header cell", and the bridge answered an empty array beside a column count. The native `NSTableView`
read on the guest answers `AXColumns` and `AXVisibleColumns` with one `AXColumn` element per column,
lists them among the table's children after its rows, and each column answers `AXIndex`, `AXHeader`
(its header button; none on a headerless table), `AXRows` and `AXVisibleRows` (that column's cells, in
row order), `AXParent` (the table), `AXSelected` and a frame spanning the header and the rows, and no
`AXChildren`; `AXSelectedColumns` is an empty array. So the bridge vends the same: one column element
per shown column, an instance of a second runtime subclass of `NSAccessibilityElement` whose closures
answer those attributes from the table's cells and its header cell in that column, kept in a registry
of its own keyed by the table's identifier and the column — the toolkit gains no column role (§1.12)
— listed after the table's nodes among its children, its box pushed like a node's (the header cell's
span over the table's height). A column goes at a frame's end when its table has left the tree or no
longer shows it, and all at once on a rebind or a detach, demoted before it is released like every
element, never from a reentrant publish. Its role description is AppKit's own, `column`: no toolkit
phrase names a column (left for a later pass to translate). The native table also answered no
`AXRowCount`, `AXColumnCount` or `AXColumnHeaderUIElements`; the bridge keeps answering those three,
which carry the model's counts and a cell's header that the realized rows cannot, until a reader run
says otherwise.
*Release on `NODE_DESTROYED` (MACOS-NEW-1, the same day):* the paragraph below says the bridge releases
on `NODE_DESTROYED`, and until this date nothing called the registry's release, so every element a
client ever pulled stayed retained, answering nil. The release now happens at the end of the frame that
emitted the destruction, after that frame's posts, and only for a node still absent from the tree then:
an identifier keyed by a row that is destroyed and published again within the frame is the same node,
whose element a client may be using. It posts nothing, as the paragraph says.
*A value with no number (decision 16; CRIT-4's macOS half, the same day):* `accessibilityValue` answers
a value's displayed text when it has one and its number otherwise, and an empty `ValueFacet` — a date
segment nobody has typed into, which publishes its minimum for the platforms that must have a number —
answers its word, or nothing when it has no text, and never the minimum: `AXValue` is an object here and
demands no number. A change of the text or of the emptiness alone is a `VALUE_CHANGED` in the model, and
this bridge posts it as `ValueChanged` like any other; the row's "minValue / maxValue" are still not
installed.

**The macOS rows as built (dated 2026-09-15; MACOS-NEW-7).** The table at the head of this section is
kept as it was written, and the dated notes above say what changed item by item. This is the whole of
what the bridge installs at the end of phase 3, row by row, so that nothing a reader of this section is
told is a promise the code does not keep. Every selector named here is listed in `AxSelectors`, tied to
the committed AppKit dump by `AxConstantsTest`, and answered only where `isAccessibilitySelectorAllowed:`
(`AxGate`) allows it; everything else a row of the table above names is marked *not installed*.

| Attribute / action / notification | As built |
| --- | --- |
| `accessibilityRole`, `accessibilitySubrole` | `AxRoles`, resolved by `dlsym`; unchanged |
| `accessibilityRoleDescription` | **installed** (the row said "not yet implemented"): the toolkit's own phrase for the role under the node's locale (`RoleNames`); a column element keeps AppKit's own `column` |
| `accessibilityTitle` / `accessibilityLabel`, `accessibilityHelp`, `accessibilityIdentifier` | as the rows say |
| `accessibilityValue` | toggle 0/1/2, text, a value's displayed text else its number, an empty value its word (`AxValues`); **`accessibilityMinValue` / `accessibilityMaxValue` not installed** |
| `setAccessibilityFrameInParentSpace:` | pushed for every held element and column element on each ordinary publish and on mint; **`accessibilityFrame` is not installed** (the row said it was): AppKit answers it from the pushed box |
| `accessibilityParent`, `accessibilityChildren` | the snapshot links; a table's children end with its column elements; the root's children pushed, re-pushed when they change |
| `accessibilityFocusedUIElement`, `isAccessibilityFocused` | **installed** (the row said "not claimed"), on the content view's own subclass and on the element class, from the tree's effective focus, across a native popup's window |
| `setAccessibilityFocused:`, `setAccessibilitySelected:`, `setAccessibilityDisclosed:`, `setAccessibilityExpanded:`, `setAccessibilityValue:`, `setAccessibilitySelectedRows:` | installed with the gate, each posting the verb it means where `AccessibleNode#accepts` holds; every other stored setter refused. **`setAccessibilityFocused:` is refused on a row as well** — a native row carries no `AXFocused` at all, and offering it let VoiceOver's cursor sync drag the application's cursor back after every key (amendment 2026-09-16, end of this section) |
| `accessibilitySelectedChildren`, `accessibilitySelectedRows`, `accessibilitySelectedCells` | the one of the container's selection shape (children / rows / cells) |
| `accessibilityRows`, `accessibilityVisibleRows`, `accessibilityIndex` | tables, outlines and lists; a table row's index is its data cells' row, already zero-based (`NSNotFound` with no data cell); an outline row's is the hierarchy facet's flat row less one; a list row's is its position in the set less one |
| `accessibilityRowCount`, `accessibilityColumnCount` | tables only, the table facet's counts (a native table answers neither; kept, see the columns note) |
| `accessibilityColumns`, `accessibilityVisibleColumns`, `accessibilitySelectedColumns` | **one column element per shown column** (the row said "synthesised, one per header cell"), answering role, index, header, rows, visible rows and parent; selected columns empty |
| `accessibilityHeader`, `accessibilityColumnHeaderUIElements` | the group holding the header cells, matched by `CellFacet(−1, c)` (the row said "the first group child"); none on a headerless table |
| `accessibilityRowIndexRange`, `accessibilityColumnIndexRange`, `accessibilityCellForColumn:row:` | data cells; the cell found by its own cell facet |
| `isAccessibilityDisclosed`, `accessibilityDisclosureLevel`, `accessibilityDisclosedByRow`, `accessibilityDisclosedRows`, `isAccessibilityExpanded` | outline rows (zero-based level); expanded on everything else with an expand facet |
| `accessibilityHitTest:` | as the row says |
| `accessibilityPerformPress`, `…Confirm`, `…Increment`, `…Decrement`, `…ShowMenu`, `…Cancel` | installed with the gate; press and confirm map to `PRESS`, `TOGGLE`, `SELECT`, `EXPAND`, `COLLAPSE`; **`…Pick` is not installed** (the row listed it) |
| `accessibilityActionNames`, `accessibilityPerformAction:` | installed, for `AXScrollToVisible` |
| `accessibilityAttributeValue:`, `accessibilityAttributeNames` | installed, forwarding, for `AXElementBusy` (ADR 044 §2) |
| `accessibilityNumberOfCharacters`, `accessibilitySelectedText`, `accessibilitySelectedTextRange`, `accessibilityStringForRange:`, `accessibilityRangeForLine:`, `accessibilityInsertionPointLineNumber` | **not installed** (the row said "`TextFacet`"): a text is read through `accessibilityValue` alone; owed |
| `isAccessibilityModal` (a dialog's `AXModal`, the elided-root paragraph below) | **not installed**; owed |
| `NSAccessibilityPostNotification` | at the end of the frame that emitted it (§1.10's amendment), not "the event flush"; `…WithUserInfo` for an announcement, on the window |
| sort direction | `accessibilitySortDirection` on a header cell and nowhere else, `NONE`/`ASCENDING`/`DESCENDING` → 0/1/2 (amended 2026-09-15, the fix round's integration; the row's "not served" held only while the model had no carrier) |

macOS is the one platform that hands out real objects the system retains. The bridge allocates lazily
— beyond the root's own children, which the push below requires up front, an element exists only for a
node the platform has asked about — and keeps a map from node id to element so a client's retained
element stays the same object across publishes. That map is UI-thread-confined and checked (§3.4),
which is a simplification only this platform gets.

**It releases on three occasions, and on none of them from inside a callback (§3.2).** On
`NODE_DESTROYED`; on the reconciliation sweep that follows an
event-queue collapse, because a collapse is precisely the burst in which the per-node destructions
were dropped; and on `attach` replacing a live host or `detach`, because a rebind invalidates every
element at once and an earlier draft released nothing there at all (§1.10, §5.3).

**And it posts no destruction notification of its own, which is a correction the probe run forced.**
Every earlier draft of this section said "post `NSAccessibilityUIElementDestroyedNotification`
first, then release", by analogy with the other two platforms. The phase 7 probe measured that
AppKit already posts it: with our own post suppressed, a client holding the element still received
exactly one `AXUIElementDestroyed`, whether it had registered on the element, on the application
element, or on both. With our post added the client received **one more per matching registration**
— two notifications for one registration and three for two. So the explicit post is not the
mechanism by which a client learns; it is a duplicate whose count depends on how the client
registered. This is the same rule as the one two paragraphs down about window events, arrived at
from the other end: what AppKit already says, we do not say again. A live LWJGL `Callback` pins
its Java object through a JNI global reference until `free()`, which is the discipline
`LwjglWindow#destroy` already applies to the preedit callback.

**The top of the tree is pushed once; everything below it is pulled. And the window root is not
vended at all.** This is the part of the design the re-run changed most, so the reasoning is set out
in full rather than asserted.

*What the measurement settled.* An earlier draft of this ADR reasoned that a push model could not
work, because §6's honest listening gate on macOS is "someone has asked" — a flag set the first time
one of our own implementations is entered — and with `setAccessibilityChildren:` nothing of ours is
entered until elements have already been pushed, which needs a published tree, which the flag gates:
a cycle that never starts. It concluded that the bridge should add `accessibilityChildren`,
`accessibilityHitTest:` and `accessibilityFocusedUIElement` to GLFW's content view class, so that the
first ask would be both attach and gate. The spike then measured the opposite premise:
`setAccessibilityChildren:` on the content view **is** sufficient — `viaWindow=true`, the element
nested under `AXWindow` in the walk — and `accessibilityHitTest:` was never needed. Three
`class_addMethod` calls on a class GLFW owns are no longer worth their risk to buy something a proven
one-line push already buys.

*So the cycle is cut at the other end, and it costs one walk — on the first frame, not at bind.* The
bridge pushes the root's children onto the content view with `setAccessibilityChildren:`. That is the
whole push, and it is a handful of elements, not the tree: `accessibilityChildren` on *our own element
class* answers everything below, which is a pull, and the first such call is the gate.

**The push happens on the scene's first frame, and it happens again whenever the root's children
change.** Both halves were wrong in an earlier draft and each was wrong on its own.

*Not at bind.* `Scene#bind` does not lay out — `layoutDirty` starts true, `width` and `height` are
zero, and `layoutPass` runs only from the frame path — so a tree built there describes every widget
as a zero-size box at the origin, and the elements pushed from it would be a window full of nothing,
in the corner. §5.3 gives the scene a priming publish on its first frame instead, after `layoutPass`,
and §5.2 makes `republishNow()` refuse to describe a scene that has never laid out at all. Same one
walk per window, one frame later, against geometry that exists. (Amended 2026-09-16: "not at bind" is
about the *scene*, and stays true on every platform. Windows publishes the window's own node there —
one node, whose role, title and size are facts of the window and not of a layout — for the message
§3.1 describes; macOS neither needs nor gets it.)

*And not once.* The pushed array is a snapshot AppKit holds; nothing re-derives it. Pushing it once
and never again freezes the top of the tree at whatever the first frame contained, while the
published root's children genuinely change underneath — **an in-scene overlay is a child of the
root**, so opening a modal dialog or a menu adds one, and that is the single change a screen reader
user most needs to be told about. So every *ordinary* publish compares the root's child list with the
one last pushed and re-pushes when they differ (§5.3). A **reentrant** publish never does: it would
replace, from inside an AX callback, the array AppKit is walking (§3.2). Everything deeper is a pull
and needs no push at all.

That trades a strictly-honest gate for one tree walk per window on its first frame, and the trade is
worth naming: on macOS, unlike Windows, a window that is never touched by an assistive technology
still pays one describe pass in its life. It buys the elimination of the only piece of this design
that would have modified a class the windowing library owns.

*And AppKit already vends the window.* The walk shows exactly what: `AXWindow/AXStandardWindow` with
the title, `AXRaise`, the close, full-screen and minimize buttons and an `AXStaticText` for the
title (Finding 4a). Hanging our own `WINDOW`-role root off the content view would publish a window
inside a window, and VoiceOver would announce it twice and offer two sets of window actions. So the
window's root node is **elided on macOS**: its children are what gets pushed. The platform's own
object is already the answer, and a second one is a lie a client will act on.

**Eliding a node has three consequences, and all three are this bridge's, so they are listed here
rather than discovered.** Its `WindowFacet` maps to nothing — for the same reason an in-scene dialog
is given no `IWindowProvider` on Windows. **No event whose subject is the window root is posted by
us**: `WINDOW_OPENED`, `WINDOW_CLOSED` and a window-level `BOUNDS_CHANGED` are already AppKit's
`AXWindowCreated`, `AXUIElementDestroyed`, `AXMoved` and `AXResized` on the object it vends, and
posting ours beside them would double every one — which is §2.4's macOS column read together with
this paragraph. And **a relation whose target resolves to a window root cannot be named here**: §1.11
drops that relation outright in the in-scene mounting, on every platform, and in the cross-window
mounting this bridge answers with AppKit's own window object and otherwise drops it (§13.27). A
dialog, in scene or native, still carries `MODAL`, which the bridge publishes as `AXModal` on the
dialog's own element.

Because the content view class is shared by every GLFW window in the process, nothing here is
installed on it: the push is a message to one instance, and every implementation the bridge writes
lives on its own `NSAccessibilityElement` subclass, where `self` identifies the node through the
bridge's own map — the same recovery the Windows spike used to get a Java object back from a COM
interface pointer.

#### Amendment 2026-09-16 — a row's `AXFocused` is not settable, because a native row has none

**The setter half above, and the `setAccessibilityFocused:` row of the table, describe the setter as
installed and say nothing about what a screen reader does with it. This is what one did.** Phase 5,
macOS 26.6.2, VoiceOver, `readings/phase5-macos/`: with a reader attached, the tree reader script
speaks the wrong row at almost every step. A key moves Limn's cursor, and about 40 ms later a second
`AXSelectedRowsChanged` and a second `AXFocusedUIElementChanged` put it back on the row the reader
was still standing on. Four attempts never got past row index 2, and `Documents 2` ended the run
collapsed — a state the script never asks for, because the `LEFT` of three different steps all landed
on the row the cursor kept being returned to. Stop VoiceOver and it vanishes: the same jar and the
same script walk all 21 rows in order, in two repeats (`tree-noVO-1`, `tree-noVO-2`, the stop proved
by `caps.txt`). And an ordinary client's write reproduces the return trip on its own —
`cl-outline-1/probe.log`, `set AXFocused=true on row[3]: err=0 -> app focused is now: AXRow
'2026.pdf'`.

**The route is the setter this ADR installed, and the reason it was wrong was already in the
readings.** `offers` asked `AccessibleNode#accepts(FOCUS)` and nothing else, and a row does accept
`FOCUS` — correctly, because that is how the toolkit says "the cursor is here" on all three
platforms. What no one checked is whether a *native* element of that shape carries the attribute at
all. It does not. `readings/macos-outline-probe.txt`, taken on the guest on 2026-09-15 and already
cited three rows above for `AXIndex` and `AXDisclosing`: every row of a native `NSOutlineView`, in
every pass, answers `AXFocused=AXError(-25205)` — `kAXErrorAttributeUnsupported` — and
`settable=AXError(-25205)`, while the outline itself answers `AXFocused=1 settable=true`.
`readings/macos-table-probe.txt`: a native `NSTableView` row's `AXAttributeNames` are
`["AXIndex", …, "AXSelected", "AXFrame"]` with **no** `AXFocused`, while the table's do carry it.
**The view takes focus; its rows are selected.** A row that answered "settable" was inviting exactly
the write VoiceOver makes, and VoiceOver's cursor sync and the application's cursor then each
insisted on a different row, twenty times a script.

**So `setAccessibilityFocused:` is refused on a row** — `AxSetters.offers` reads
`node.accepts(FOCUS) && !grid.isRow(node)`, where a row is a table's `ROW` or a member of an
outline's or a list's selection, which is exactly the set AppKit calls `AXRows`. Refused through the
gate, so `AXUIElementIsAttributeSettable` answers false and a client is told rather than ignored, and
checked again on the write. **A reader loses nothing:** the native route to the cursor is
`setAccessibilitySelected:` on the row or `setAccessibilitySelectedRows:` on the container, both
already offered here, both posting `SELECT`, and `SELECT` on a Limn row moves the cursor to it. The
container itself stays focus-settable, as the native outline is.

**One asymmetry with the native shape is kept on purpose.** `isAccessibilityFocused` still answers on
a row, and answers true on the cursor row, where a native row refuses the getter too. It is kept
because decision 1 and semantics 4 make the cursor row the element
`accessibilityFocusedUIElement` names: a client that walks there and asks the row whether it is
focused is entitled to a truthful yes, and refusing the getter would make this bridge's own focus
answer unconfirmable. Reading the cursor is not what fights the application; writing it is.

**What this does not close.** The table script shows the same two-focus-change pattern from step 10
on — the step at which it starts moving a *row* — but there the element Limn publishes as focused is
the cell's own widget (`ATEVENT focused = AXCheckBox id=…164 'Visitada'`, put back to `…162`), which
is not a row and is legitimately focus-settable: a native `NSButton` in a cell view is. The write
itself was **not** captured for that path — no phase-5 run had the inbound trace on — so the route
there is inferred and no code was changed for it. It needs one live run with the inbound trace
enabled, to see whether the put-back on a table arrives as `setAccessibilityFocused:` and on which
element, before anything is decided.

### 2.3 Linux: AT-SPI2

We own a socket and a reader thread; there is no vtable and no callback. The bridge serves D-Bus
objects at `/org/a11y/atspi/accessible/<id>`, plus `…/root` and `…/cache`.

| Interface / member | Answered from | Note |
| --- | --- | --- |
| `Accessible` `Name`, `Description`, `Parent`, `ChildCount`, `Locale`, `AccessibleId`, `HelpText` | name, description, links, locale, id | `Locale` is why the node carries its own resolved locale rather than the window's |
| `Accessible.GetChildAtIndex`, `GetChildren`, `GetIndexInParent` | the snapshot links | |
| `Accessible.GetRole`, `GetRoleName`, `GetLocalizedRoleName` | role | numbers from the typelib; the localized name resolved under the node's locale |
| `Accessible.GetState` | states, as **`au` of exactly two `uint32`, low word first** | measured: a real GTK application answers `au 2 0 0` |
| `Accessible.GetAttributes` | `a{ss}` including `toolkit` | |
| `Accessible.GetInterfaces` | **the facet set**, as interface names | the mirror image of UIA's `GetPatternProvider`, and the second reason facets exist |
| `Accessible.GetRelationSet` | relations, as `a(ua(so))` | `LABELLED_BY`, `LABEL_FOR`, `DESCRIBED_BY`, `CONTROLLER_FOR`, `CONTROLLED_BY`, `MEMBER_OF`, `POPUP_FOR` |
| `Application` `ToolkitName`, `Version`, `AtspiVersion`, writable `Id`, `GetLocale` | fixed, plus the registry's write | measured: the registry sets `Id` immediately after `Embed`, so `Properties.Set` must already work |
| `Component.GetExtents`, `GetPosition`, `GetSize` | bounds converted, per `coord_type` | `COORD_TYPE_SCREEN` = 0, `COORD_TYPE_WINDOW` = 1; on Wayland the screen answer is the window answer, as GTK's is |
| `Component.Contains`, `GetAccessibleAtPoint` | a bounds walk over the snapshot | again not `Widget#hitTest`, for the disabled-node reason |
| `Component.GetLayer`, `GetMDIZOrder`, `GetAlpha` | `LAYER_WIDGET` / `LAYER_WINDOW`, 0, 1.0 | |
| `Component.GrabFocus` | posted `FOCUS` | |
| `Action.NActions`, `GetActions`, `GetName`, `GetDescription`, `GetLocalizedName`, `GetKeyBinding`, `DoAction` | `AccessibleNode#accepts` (the `ActionFacet`'s parameterless verbs) | `GetActions` is `a(sss)`; the third column is the key binding, where `Accelerator#display()` goes. *(Amended 2026-09-15, semantics 5 settled after phase 3: the listing and `DoAction` both ask `accepts`, as every other entry point on this bridge does. They read the facet directly until then — the same answer, and the one call here that did not read the toolkit's single authority, so a gate reaching `accepts` would have left `DoAction` on the old rule. Pinned on the source by `AtspiTreeTest.everyVerbThisBridgePostsIsPostedAtOnePlaceBehindAccepts`, because behaviour cannot tell the two apart while they agree.)* |
| `Value` `CurrentValue` (read/write), `MinimumValue`, `MaximumValue`, `MinimumIncrement` | `ValueFacet` | numeric only; a display form such as a spinner's `07:30` is published through `Text` |
| `Text`, `EditableText` | `TextFacet` | **offsets converted from UTF-16 to characters at this boundary and nowhere else**; `GetRangeExtents` is not answered in the first cut (§11) |
| `Selection` | `SelectionFacet` | |
| `Table` (`NRows`, `NColumns`, `GetAccessibleAt`, `GetColumnHeader`, `GetSelectedRows`, `GetRowAtIndex`, `GetColumnAtIndex`) | `TableFacet`; ADR 041 §7 — and, for **which rows are selected**, the selection container rule (semantics 1) | `GetAccessibleAt` answers a realized cell and the null object for a row the walk did not publish, the degradation §4.1 already accepts. *(Amended 2026-09-15, semantics 1's last divergence, ratified after the phase-3 fix round: `NSelectedRows`, `GetSelectedRows`, `IsRowSelected`, `IsSelected` and `GetRowColumnExtentsAtIndex` read the members the publish resolved to this table — `AccessibleNode#selectionContainer`, the one fact a snapshot carries about where a member hangs — and no longer the `SELECTED` bit of the table's direct `ROW` children. A row a widget draws under a body of its own is a member and was reported as unselected; a selected direct child that declares no container was reported as a selected row. `AddRowSelection`/`RemoveRowSelection` fall back to the same members, so a write names the row a read reports. Cell lookup is unchanged: semantics 2 still searches the table's row children, and `IsSelected`'s cell half still reads the located cell's own bit.)* |
| `TableCell` (`Position`, `RowColumnSpan`, `Table`, `ColumnHeaderCells`, `RowHeaderCells`) | `CellFacet`; ADR 041 §7 | the table is the nearest ancestor with a `TableFacet`; the column header cell is its header group's child at the cell's column |
| `Cache.GetItems` | the whole snapshot, **pre-marshalled** | measured: 46 round trips for two objects without it |
| `Cache.AddAccessible`, `RemoveAccessible` signals | `STRUCTURE_CHANGED`, `NODE_DESTROYED` | |
| `Event.Object` `StateChanged`, `ChildrenChanged`, `PropertyChange`, `TextChanged`, `TextCaretMoved`, `TextSelectionChanged`, `SelectionChanged`, `ActiveDescendantChanged`, `BoundsChanged`, `Announcement`; `Event.Window` `Activate`, `Deactivate`, `Create`, `Destroy`; `Event.Focus` `Focus` | the event flush | `Event.Focus.Focus` is deprecated and still what Orca listens for, so it is emitted beside the `StateChanged` |
| `Socket.Embed` on the a11y bus; `org.a11y.Status.IsEnabled` on the session bus | attach, and the listening gate | both proven |
| `org.freedesktop.DBus.Introspectable.Introspect` on every intermediate node | synthesised from the exported paths | not needed by `libatspi` or Orca, and needed by `busctl tree`; a bridge that cannot be browsed is much harder to debug |

**AT-SPI2 has exactly one application object per connection, and that decides the shape of the Linux
bridge.** Windows are children of the application, not applications of their own. Two windows each
doing their own `Socket.Embed` would put Limn on the desktop twice, as two applications named by
window title, and two objects at `/org/a11y/atspi/accessible/root` on one connection is a path
collision. Orca would treat a combo popup as a separate application. So the Linux bridge is
**process-wide behind per-window facades**: one connection, one reader thread, one application node,
one `frame` child per window, and per-window `accessibility()` returns a facade that registers that
window's subtree with it. It lives in `limn-backend-lwjgl` beside `LwjglBackend`, which already keeps
the window list, so no SPI member has to be invented to enumerate windows. Windows and macOS bridges
stay per window. This asymmetry is real, and it is the one place the three implementations do not have
the same shape.

**Process-wide is not the same as stateless, and §3.4 assigns every piece of it a thread.** Being
one connection with one application object means there is process-wide mutable state even though
there is no per-node registry: the table of window facades — the application's `frame` children,
written by the UI thread as scenes bind and detach and read by the reader thread on every `root`
query — each facade's own `volatile` published tree, the listening flag the reader thread writes, the
outbound queue the UI thread and the reader thread both feed, and the marshalled cache body below.
The rule that keeps it honest is the one §3.3 states for the sockets, applied to memory: **the reader
thread and the UI thread never mutate the same field**, and everything they share is either an
immutable value published through a `volatile` write or a concurrent queue. Node ids are process-wide
by §1.3, minted from one counter, so one application object can carry every window's subtree with no
possibility of a path collision between them — which is what makes this shape available at all.

The pre-marshalled `Cache.GetItems` body is the bridge's, not the toolkit's: `AccessibleTree` knows
nothing about D-Bus. The bridge keeps one marshalled buffer keyed on a process-wide publish counter
and rebuilds it lazily on the first `GetItems` after any window publishes. **The reader thread is the
only thread that touches that buffer** — it is the only thread that answers `GetItems` — so it needs
no lock; the counter is a `volatile long` the UI thread bumps.

Two rules from Finding 3 that are not negotiable in handler code. A handler must not block, and must
never make a blocking call on the same connection. And `NEGOTIATE_UNIX_FD` succeeds on both buses and
must **not** be sent: agreeing lets a peer send a message carrying a file descriptor, which
`java.nio` cannot receive, and AT-SPI2 never needs one.

#### Amendment 2026-09-15 — the one application is built, and it is named by the backend

**What was wrong.** The two paragraphs above described a shape the code did not have (LINUX-NEW-8).
Every native window opened its own a11y connection, did its own `Socket.Embed` and named its
application after its own title, so a DatePicker's calendar or a ComboBox's list — a native popup
window, titled "popup" by `WindowConfig.popup` — was a second application on the desktop called
"popup", and the `POPUP_FOR` its root carries named a field that `AtspiTree.relationSetOf` skipped
because another connection held it. Ids were already process-wide (§1.3, amended 2026-09-14).

**What the code does now.** `AtspiApplication` is the process's one application: one connection,
one application object at `…/root`, and one `frame` child per window **that has published a node
zero**, in the order the windows joined. Each window's `AtspiBridge` is the facade: its publish
stores its own `volatile` tree and tells the application; its detach removes it. The window table
is copy-on-write, written by the UI thread and iterated by the reader thread, as §3.4 prescribed.
Every path resolves through the window whose tree holds the id (`AccessibleTree#holds`, one tag
comparison per window), so `GetChildren`, `GetIndexInParent`, `Parent`, `Cache.GetItems`, `DoAction`
(performed by the host of the window that published the node) and a relation into another window
all answer across windows. The bus is still joined only once some window has a tree (the 2.60 trap
of §12.2); the frames present at the join are what the registry reads, and a frame that arrives or
leaves afterwards is announced from the application object as `ChildrenChanged` `add`/`remove`
with its index in `detail1` and its `(so)` as the value — the shape libatspi 2.60.6's
`cache_process_children_changed` updates a cached child list from (readings, 2026-09-13). The
connection is let go with the last window, so a later window registers with a tree again.

**Named by the backend (decision 56).** `Backend#setApplicationName(String)` names the application;
unset, `LwjglBackend` uses the title of the first window it created. Every window's bridge is opened
with that name and not with its own title. Windows and macOS read nothing from it.

**Not changed here, and whose it is.** `Cache.GetItems` is still built per request rather than
pre-marshalled against a publish counter; `Cache.AddAccessible`/`RemoveAccessible` for a frame and
`Event.Window` `Create`/`Destroy` are the events item of the Linux lane (LINUX-NEW-1, LINUX-NEW-2).
When and on which thread the join happens is §3.3's amendment of the same date.

#### Amendment 2026-09-15 — the descriptor rule is kept, and a message the reader cannot read no longer silences it

**What was wrong (LINUX-NEW-13).** The rule above was written and not followed: `DBus.Conn.auth`
sent `NEGOTIATE_UNIX_FD` "only to see the answer", and both buses agreed. Separately, the reader loop
guarded only the socket read, so a message whose body did not parse — a `h` in its signature, the
very type the agreement lets a peer send, or any header shape the parser did not expect — threw out
of the loop and ended `limn-a11y-dbus-reader` for good, while the application stayed embedded and
went on emitting signals nobody could answer a question about: the state at-spi2-core 2.60 hides
from the desktop.

**What the code does now.** The handshake is `AUTH EXTERNAL`, then `BEGIN`, and nothing between
(`DBus.Conn.saslCommands`); `h` is neither read nor written. The reader frames a message by its
lengths before parsing it, so an unparsable one leaves the stream at the next message: it is logged,
a method call among them that expects a reply and whose header could be read is answered
`org.freedesktop.DBus.Error.InvalidArgs`, and the loop goes on. Lengths that cannot be a message
(past the specification's 2^27 bytes) end the connection, since nothing after them can be found.
Whenever the reader or the writer stops without the connection having been closed, the connection
closes itself and tells its owner once (`DBus.Conn.onLost`); the application lets that join go and
asks every window for a publish, which joins again under the back-off of §3.3's amendment.

#### Amendment 2026-09-15 — the event rows of this table

Two rows above said more than the code sent, and the events item of the Linux lane changed what they
describe; §2.4's amendment of this date carries the shapes and their readings. **`Cache.AddAccessible`,
`RemoveAccessible`** are sent from the tail's `STRUCTURE_CHANGED`, per child, after the parent's
`ChildrenChanged` (`AddAccessible` for an arrival, `RemoveAccessible` for a child that left the tree);
`NODE_DESTROYED` sends `StateChanged defunct` from the node's own path instead. **The `Event.Object`
row's note is false**: `Event.Focus.Focus` is not emitted beside `StateChanged`, and Orca 50.2 does not
listen for it (its `Script.get_listeners` registers no `focus:` event). `Announcement` is now sent;
until this date it was not.

#### Amendment 2026-09-15 — `Table` and `TableCell` find a cell by its facet, and a header by its row

**What was wrong (LINUX-NEW-10, LINUX-NEW-11).** The `Table` row's "`GetAccessibleAt` answers a
realized cell" held only for a table whose rows carry a `SelectionItemFacet`: a row was matched by
its position in set, which a calendar's week rows do not publish, so every cell of every calendar
answered the null object. The `TableCell` row's "its header group's child at the cell's column" was
the table's first group child's child at that index, so a table with its header hidden and a footer
shown named the footer's totals as its column headers, and a partial footer the wrong column's.
`AddRowSelection` posted `SELECT` alone and `RemoveRowSelection` refused everything.

**What the bridge does now (semantics 2, 3 and 5 of the 2026-09-13 pass).** Cell (r, c) is the node
whose `CellFacet` is (r, c) and whose nearest `TableFacet` ancestor is the table, searched under the
table's `ROW` children (where a widget cell hangs under its synthetic row); a row's index is its cells'
`CellFacet` row, and `IsSelected`/`GetRowColumnExtentsAtIndex` also count a selected cell. The header
of column c is the child with `CellFacet(-1, c)` of one of the table's direct `GROUP` children; a
footer cell (row −2) is never one, and none means none. `AddRowSelection` posts the first of
[`ADD_TO_SELECTION`, `SELECT`] the row accepts and `RemoveRowSelection` [`DESELECT`], through
`AccessibleNode#accepts`, and answer false otherwise. `GetRowColumnSpan` stays `iiii`: libatspi 2.60.6
reads `=>iiii` although the ATK bridge's XML declares `biiii`
(readings/upstream-at-spi2-core-2.60.6-libatspi-interfaces.txt, readings/fedora-dbus-TableCell.xml).

#### Amendment 2026-09-15 — `Selection` is served, with the two indices its XML names

**What was wrong (L2).** The `Selection` row promised an interface no code defined: no container
listed it, `Properties.Get` refused `NSelectedChildren`, and every method answered `UnknownMethod`, so
libatspi's `get_n_selected_children` read −1 in every snapshot of the 2026-09-13 tree reader.

**What the bridge does now (semantics 1 and 5; settled atspi-selection-membership).** A node with a
`SelectionFacet` lists `org.a11y.atspi.Selection` in `GetInterfaces` and in its cache item. The
installed XML names two different indices (readings/fedora-dbus-Selection.xml), and each is kept:
`NSelectedChildren` (a property, `i`), `GetSelectedChild` and `DeselectSelectedChild` count the
container's **selected members** — the realized nodes the publish resolved to it
(`AccessibleNode#selectionContainer`), in reading order, wherever they hang, so a calendar's selected
day is found under its week row — while `SelectChild`, `IsChildSelected` and `DeselectChild` name the
container's **literal child** at that index, which may be a tree's scroll bar or a grid's week row and
is then selected by nothing. `SelectChild` posts the first of [`ADD_TO_SELECTION`, `SELECT`] the child
accepts, `DeselectChild`/`DeselectSelectedChild` [`DESELECT`]; `SelectAll` and `ClearSelection` answer
false, the model having no verb for either. A member scrolled away has no node and is not counted.
Orca 50.2 calls `get_n_selected_children` and `get_selected_child` only
(readings/fedora-orca-interface-calls.txt, Fedora KDE 44, 2026-09-15). The XML's `version` property is
not answered.

#### Amendment 2026-09-15 — `Value` is served, with its text, and a write is refused where the node refuses it

**What was wrong (LINUX-NEW-4, DATES-NEW-5).** The `Value` row promised an interface no code served:
a date segment, a spinner, a slider or a progress bar had a name and a role on Linux and no number.

**What the bridge does now (settled linux-value-text; decision 16; semantics 5).** A node with a
`ValueFacet` lists `org.a11y.atspi.Value`. `MinimumValue`, `MaximumValue`, `MinimumIncrement` and
`CurrentValue` are doubles and `Text` is the facet's display form or the empty string, as libatspi
2.60.6 reads them (readings/upstream-at-spi2-core-2.60.6-libatspi-interfaces.txt) and as the guest's
XML declares them (readings/fedora-dbus-Value.xml) — the row's "numeric only" is superseded, since the
installed interface carries a `Text` property and Orca 50.2 reads it with the number
(`ax_value.py`, readings/fedora-orca-interface-calls.txt). An empty value answers its minimum as
`CurrentValue`, the one place a number is mandatory, and its word through `Text`. `Properties.Set` of
`CurrentValue` — how libatspi writes one — posts `SET_VALUE` with the number where
`AccessibleNode#accepts` allows it (a writable facet on an `ENABLED` node) and is otherwise answered
`org.freedesktop.DBus.Error.Failed`, which reaches the caller's `GError`; so is a write to any other
`Value` property. A display form is also served through `Text` (its own amendment below).

**Read 2026-09-15 (review of the interfaces item): what a toolkit answers a refused write.** Neither
toolkit on the Fedora KDE 44 guest refuses a `CurrentValue` write with an error: GTK 3.24.52's ATK bridge
answers success on an insensitive spin button and on a level bar and moves both, and GTK 4.22.4 answers
success on both and leaves the level bar's number where it was
(readings/fedora-gtk3-interface-replies.txt and fedora-gtk4-interface-replies.txt, section 5,
`scripts/a11y/linux/read-gtk-interface-replies.py`). Answering `Failed` where `AccessibleNode#accepts` refuses stays this bridge's choice, made
knowingly against both: a success a widget then ignores is what a caller cannot detect. A write to a
read-only `Value` property is now answered `org.freedesktop.DBus.Error.PropertyReadOnly`, as the ATK
bridge answers it (GTK 4 answers `InvalidArgs`), and a name `Value` does not have, or a `CurrentValue`
that is not a number, `InvalidArgs`, as `Get` answers an unknown name; the error name `Failed` itself is
from readings/fedora-dbus-bus-facts.txt.

#### Amendment 2026-09-15 — `Text` is served, over a text and over a value's display form

**What was wrong (LINUX-NEW-4).** The `Text` row promised an interface no code served, while the
bridge already sent `TextChanged`, `TextCaretMoved` and `TextSelectionChanged` from nodes that
answered none of the questions those events invite (§2.4's "an event is half a conversation").

**What the bridge does now (settled linux-value-text).** `org.a11y.atspi.Text` is listed for a node with
a `TextFacet` and for a node with a `ValueFacet` whose display form is not empty (a date segment's
"15" or "empty", a spinner's "07:30"), which is read-only text with no caret and no selection; a
value whose number is the whole of it serves none. Every offset is a character, converted from the
model's UTF-16 units in `AtspiText` and nowhere else. Answered: `CharacterCount` and `CaretOffset`
(properties, `i`), `GetText` (−1 as the end), `GetCharacterAtOffset`, `GetStringAtOffset`,
`GetTextAtOffset`/`-BeforeOffset`/`-AfterOffset`, `GetNSelections`, `GetSelection`, attributes as
none over the whole text (`GetAttributeRun` is `a{ss}ii`, the shape libatspi 2.60.6 checks), and the
writes `SetCaretOffset` (`SET_CARET`), `AddSelection`/`SetSelection`/`RemoveSelection`
(`SET_SELECTION`, the model holding one selection) through `AccessibleNode#accepts`, offsets back in
units. **Boundaries:** words and sentences are `BreakIterator`'s under the node's locale; a line and a
paragraph are what a line feed delimits, because no facet carries soft wraps, so a wrapped line of a
text area reads as its paragraph; each boundary type has the shape its AT-SPI name gives
(`WORD_START` from a word's start to the next word's, `LINE_START` through the line feed, the
`_END` types from one end to the next), and a granularity is answered as libatspi 2.60.6's own
fallback reads it (WORD as `WORD_START`, SENTENCE as `SENTENCE_START`, LINE as `LINE_START`;
PARAGRAPH as `LINE_START` here) (readings/upstream-at-spi2-core-2.60.6-libatspi-interfaces.txt;
the enumerators from readings/fedora-atspi-constants-all.txt, Fedora KDE 44, 2026-09-13). **Not
answered, as §11 decided:** `GetCharacterExtents`, `GetRangeExtents`, `GetOffsetAtPoint` and
`GetBoundedRanges` are declined and `ScrollSubstringTo(Point)` answers false; Orca 50.2 calls the
first three for flat review and mouse review (readings/fedora-orca-interface-calls.txt), which stay
degraded.

**Read 2026-09-15 (review of the interfaces item): PARAGRAPH.** Answering PARAGRAPH as `LINE_START`
was a choice: libatspi 2.60.6's fallback maps it to no boundary. On the Fedora KDE 44 guest, over a
text view holding "one two. three four.\nfive six.\n\nseven", GTK 4.22.4 answers PARAGRAPH (and LINE
and SENTENCE) with what a line feed delimits, the line feed left out ("five six.", 21, 30), and GTK
3.24.52's ATK bridge answers PARAGRAPH `('', -1, -1)` at every offset while its LINE keeps the line feed
("five six.\n", 21, 31) (readings/fedora-gtk4-interface-replies.txt and
fedora-gtk3-interface-replies.txt, section 4, `scripts/a11y/linux/read-gtk-interface-replies.py`). The
bridge keeps PARAGRAPH as what a line feed delimits — GTK 4's unit, and a range where Orca 50.2 asks for one (`ax_text.py` calls PARAGRAPH,
readings/fedora-orca-interface-calls.txt) —
in the `LINE_START` shape its LINE already has, the line feed included as the ATK bridge includes it.
Pinned by `AtspiTreeTest.aTextIsReadInCharactersByOffsetGranularityAndBoundary`.

**`EditableText` (the same date).** Listed for a text published `EDITABLE` — a field that is only
disabled keeps both, as §1.2 requires. Every write is one `SET_TEXT` of the whole new string, built
from the published text in characters, through `AccessibleNode#accepts`: `SetTextContents` replaces it,
`InsertText` inserts the first `length` characters of what it is given (all of it when `length` is
negative or not less than its length, so a client counting UTF-8 bytes still inserts the whole string),
`DeleteText` removes a character range. On a `PASSWORD` node, whose facet holds the mask, only
`SetTextContents` is taken. `CutText` and `PasteText` answer false and `CopyText` does nothing: the
model has no clipboard verb. Orca 50.2 calls none of these (readings/fedora-orca-interface-calls.txt);
the signatures are libatspi 2.60.6's (`s=>b`, `isi=>b`, `ii=>b`, `i=>b`, `CopyText` `ii` with no reply
value).

**Corrected 2026-09-15 (review of the interfaces item): `InsertText`'s length is bytes, as read.** The
paragraph above took `length` as characters, a choice and not a reading, which misread a byte count that
covers part of a multibyte string (a length of 2 for "é😀x" inserted "é😀"). Read on the Fedora KDE 44
guest: GTK 3.24.52's ATK bridge counts UTF-8 bytes — `InsertText(1, "é😀x", n)` into "ab" leaves "ab"
for 1, "aéb" for 2 and 3, "aé😀b" for 6, and all of it for 7, 100 and −1, a character the count would
cut being left out (readings/fedora-gtk3-interface-replies.txt) — while GTK 4.22.4 ignores the length and
inserts everything (readings/fedora-gtk4-interface-replies.txt;
`scripts/a11y/linux/read-gtk-interface-replies.py`, 2026-09-15). The bridge now inserts the whole
characters that fit in `length` bytes, all of them for a negative length (`AtspiTree.prefixInBytes`), the
ATK bridge's unit, whose XML it serves.

#### Amendment 2026-09-15 — `GetAttributes` carries a row's level and place in its set

**What was wrong (L5).** The `GetAttributes` row answered `toolkit` alone, while Orca 50.2 reads a
tree item's level from `level` and a member's "n of m" from `posinset` and `setsize` before any
fallback (readings/fedora-orca-tree-level-position.txt), so no Limn tree item had a level on Linux
and a virtualized list's rows were counted among the realized siblings.

**What the bridge does now (decision 4, semantics 6, settled linux-level-carrier).** Beside `toolkit`,
`level` is `HierarchyFacet.level` and `posinset`/`setsize` are `SelectionItemFacet`'s position and size,
each as a decimal string, one-based as the model counts them and as Orca reads them; each is published
only when non-zero, so no node says "0 of 0". GTK 4.22.4 publishes `posinset` and `setsize` on its list
rows the same way (readings/fedora-gtk4-column-sort.txt, Fedora KDE 44, 2026-09-15). No event announces
a change to them (decision 43): Orca 50.2's `object:attributes-changed` handler only clears its cache
(readings/fedora-orca-interface-calls.txt). A header's sort direction is not an attribute yet; see the
sort amendment below. *(Amended 2026-09-15, the fix round's integration: it is now — `sort` on the
sorted column's header cell, and on no other node. That amendment's last paragraph says how.)*

#### Amendment 2026-09-15 — `GrabFocus`, `GetAccessibleAtPoint` and `Introspect` are answered

**What was wrong (LINUX-NEW-5; settled linux-adr-overstatements).** Three rows promised what nothing
handled: `Component.GrabFocus` and `GetAccessibleAtPoint` answered `UnknownMethod` — the latter is what
Orca 50.2's mouse review asks (`ax_component.py:124`, `WINDOW` coordinates;
readings/fedora-orca-interface-calls.txt) — and `Introspectable.Introspect` was handled on no path,
while `Atspi.node` and the XML blocks it assembles were referenced nowhere.

**What the bridge does now.** `GrabFocus` posts the first of [`FOCUS`] the node accepts (semantics 5:
every focusable widget publishes it as the walk's free verb, an item where decision 11 allows) and
answers false elsewhere. `GetAccessibleAtPoint` is the bounds walk the row names: the point is
converted once to the window's coordinates from the type asked (`SCREEN`, `WINDOW`, or `PARENT` — 2,
read 2026-09-13 off the Fedora typelib, readings/fedora-atspi-constants-all.txt — which
`GetExtents` and `Contains` now also answer, relative to the parent's box), children are tried last
first because a later sibling is drawn over an earlier one, only a `SHOWING` node is a hit, and the
deepest hit below the asked node is the answer, the null object when none; asked of the application
object, it answers the last-joined frame whose window holds the point. `Introspect` answers every path
this application exports: each intermediate path names its child down to `/org/a11y/atspi`,
`…/accessible` names the root and every node of every window, and a node's path lists exactly what
`GetInterfaces` answers, each with the XML a real toolkit's bridge declares — the blocks read off Ubuntu
24.04 for `Accessible`, `Application`, `Component`, `Action` and `Cache`, and those read off the Fedora
KDE 44 guest's at-spi2-atk 2.60.6 for `Selection`, `Value`, `Text`, `EditableText`, `Table` and
`TableCell` (readings/fedora-dbus-<Interface>.xml) — plus the three standard interfaces; a path that
names nothing is declined.

#### Amendment 2026-09-15 (review of the interfaces item) — `GetPosition` and `GetSize` are two out arguments

**What was wrong.** The `Component.GetExtents`, `GetPosition`, `GetSize` row was answered with one
shape for all three: `GetPosition` and `GetSize` replied a struct `(ii)`, on every node and on the
application object, since the bridge's first cut. libatspi 2.60.6 reads them as `u=>ii` and `=>ii`
(`atspi-component.c:196` and `:223`, readings/upstream-at-spi2-core-2.60.6-libatspi-interfaces.txt),
the installed XML declares two separate out arguments (readings/fedora-dbus-Component.xml), and a
struct where flat arguments are expected is refused, as `GetRowColumnSpan`'s was on the guest. The
GrabFocus amendment above amended the same rows and did not catch it.

**What the bridge does now.** Both answer `ii`, on a node and on the application object; `GetExtents`
stays `(iiii)`. GTK 3.24.52's ATK bridge (at-spi2-atk 2.60.6) and GTK 4.22.4 answer exactly these
signatures on the Fedora KDE 44 guest (readings/fedora-gtk3-interface-replies.txt and
fedora-gtk4-interface-replies.txt, `scripts/a11y/linux/read-gtk-interface-replies.py`, 2026-09-15
20:54–20:55 UTC). Both toolkits answer `UnknownMethod` for `Component` on their application object; this
bridge keeps answering it there, as the first cut decided (a client that asks the root for its extents
walks on).

#### Amendment 2026-09-15 (review of the interfaces item) — the `version` the served XML declares is answered

**What was wrong.** `Introspect` serves, for `Selection`, `Value`, `Text`, `EditableText`, `Table` and
`TableCell`, the XML read off the Fedora guest's ATK bridge, and each block declares a read-only
`version` property of type `u`; `Properties.Get` refused it as a property the object lacks
(`InvalidArgs`), and the Selection amendment above recorded it as not answered for want of a reading.

**What was read, and what the bridge does now.** GTK 3.24.52's ATK bridge (at-spi2-atk 2.60.6) answers
`version` with `u` 1 on every interface it serves; GTK 4.22.4, which declares no such property, answers
`InvalidArgs` (readings/fedora-gtk3-interface-replies.txt and fedora-gtk4-interface-replies.txt, section
2, `scripts/a11y/linux/read-gtk-interface-replies.py`, Fedora KDE 44, 2026-09-15); at-spi2-core 2.60.6's
`atspi-constants.h` defines every `ATSPI_*_VERSION` as 1. The bridge answers `u` 1
(`Atspi.INTERFACE_VERSION`) in `Get` and `GetAll` for those six interfaces where the node serves them,
the XML and the answer now coming from the same bridge. The blocks read off Ubuntu 24.04 (`Accessible`,
`Application`, `Component`, `Action`) declare no `version` and none is answered there.

#### Amendment 2026-09-15 — a header's sort direction: read on Fedora, and not carried yet

**What decision 36 asked.** A sortable header cell publishes its direction, and how each platform
carries one is read on the guest before any code.

**What was read.** A GTK 4 `Gtk.ColumnView` sorted ascending, descending, by another column and by
nothing on the Fedora KDE 44 guest (gtk4 4.22.4, at-spi2-core 2.60.6) carries **no** sort direction:
each title is a `filler` whose name is the column's, with `{toolkit: GTK}` alone as attributes, the same
states in every step, and no event naming the header (readings/fedora-gtk4-column-sort.txt,
`scripts/a11y/linux/read-gtk4-column-sort.py`, 2026-09-15). Orca 50.2 reads a table header's direction
from the object attribute **`sort`** — `ascending` → "sorted ascending", `descending` → "sorted
descending", `none` or absent → nothing, any other value → "sorted" (`ax_utilities_table.py:259-275`,
readings/fedora-orca-interface-calls.txt). So the carrier on this platform is that attribute, as ADR 041
§7's amendment of 2026-09-14 anticipated; no native toolkit on the guest was found sending it.

**Why nothing is mapped yet.** The model has no fact to map: `Table` carries the direction as the sorted
header's localized description (`TableStrings.SORTED_ASCENDING`/`SORTED_DESCENDING`, ADR 041 §7), and a
bridge cannot recover `ascending` from "Ordenado em ordem crescente". Carrying it needs a model
carrier — a facet or a state on the header cell — that the three bridges read alike, which is not this
bridge's to add alone while the Windows and macOS bridges are changed in parallel. Once it exists, this
bridge answers `sort` = `ascending`/`descending` on that header cell in `GetAttributes` (§2.3's
attributes amendment) and nothing when unsorted; until then a Linux reader hears the description.

**Amended 2026-09-15 (the phase-3 fix round's integration): the carrier exists, and `sort` is
answered.** The model fact the paragraph above waits on landed in the same round — `CellFacet.Sort`,
`NONE`/`ASCENDING`/`DESCENDING` on the header cell of the sorted column (§1.2's amendment of this
date, ADR 041 §7's) — but it landed on the model branch while the three bridge lanes were running,
so each logged its own mapping as owed rather than compiling against a type its branch did not have.
The mappings are the integration's, because none of them is a lane's judgement: each was specified
whole in its lane's log, against a reading that lane had already taken. Here, `AtspiTree.attributesOf`
publishes `sort` = `ascending` / `descending` on a cell in the header row whose direction is not
`NONE`. The key goes nowhere else: an unsorted header publishes no key rather than `sort=none` —
Orca reads the two the same and no GTK table on the guest publishes either — and a data cell or a
footer cell is not a header whatever its facet holds, which is the guard and not the model's
restraint, since `Table` sets a direction on the header alone. Orca's fourth value `other` is never
written, because `CellFacet.Sort` has no fourth value to write it from. The description stays where
it was, so a reader that ignores the attribute hears what it heard before. Pinned by
`AtspiTreeTest.theSortedColumnsHeaderSaysItsDirectionAndNoOtherCellSaysAnything`, which gives the
data cell and the footer cell a direction their facet has no business carrying. What a live Orca
does with the attribute is phase 5's.

### 2.4 The events, side by side

| Event | Windows | macOS | Linux |
| --- | --- | --- | --- |
| `FOCUS_CHANGED` | `AutomationFocusChanged`, plus a `HasKeyboardFocus` property change on both | `FocusedUIElementChanged`, **posted at application level** — measured: an observer registered on the element does not receive it | `StateChanged` detail `focused` 1 then 0, plus the deprecated `Event.Focus.Focus` |
| `ACTIVE_DESCENDANT_CHANGED` | `SelectionItem_ElementSelected` on the item | `SelectedChildrenChanged` | `ActiveDescendantChanged` |
| `STRUCTURE_CHANGED` | `UiaRaiseStructureChangedEvent` with `ChildAdded` / `ChildRemoved` / bulk / `ChildrenInvalidated` | `Created`, `UIElementDestroyed`, `LayoutChanged` | `Cache.AddAccessible` / `RemoveAccessible` plus `ChildrenChanged` detail `add` / `remove`, detail1 = index |
| `NAME_CHANGED`, `DESCRIPTION_CHANGED`, `STATE_CHANGED`, `VALUE_CHANGED` | `UiaRaiseAutomationPropertyChangedEvent` with the property id and both variants | `TitleChanged`, `ValueChanged` | `PropertyChange` detail `accessible-name` / `-description` / `-value`, or `StateChanged` |
| `SELECTION_CHANGED` | `SelectionItem_ElementSelected`, or `Selection_Invalidated` in bulk | `SelectedChildrenChanged` | `SelectionChanged` |
| `TEXT_CHANGED` | `Text_TextChanged` | `ValueChanged` on the text element | `TextChanged` detail `insert` / `delete`, detail1 = offset, detail2 = length, any_data = the text |
| `CARET_MOVED` | `Text_TextSelectionChanged` | `SelectedTextChanged` | `TextCaretMoved`, detail1 = offset |
| `TEXT_SELECTION_CHANGED` | `Text_TextSelectionChanged` | `SelectedTextChanged` | `TextSelectionChanged` |
| `INVOKED` | `Invoke_Invoked` | — (the action's return is the acknowledgement) | — (`DoAction`'s boolean is) |
| `BOUNDS_CHANGED` | `BoundingRectangle` property change, or one `LayoutInvalidated` in bulk | `Moved` / `Resized` on a window, `LayoutChanged` in bulk | `BoundsChanged` |
| `WINDOW_OPENED` / `WINDOW_CLOSED` | `Window_WindowOpened` / `Window_WindowClosed` | `WindowCreated` / `UIElementDestroyed` | `Event.Window` `Create` / `Destroy` |
| `WINDOW_ACTIVATED` / `WINDOW_DEACTIVATED` | focus change into the window | `MainWindowChanged`, `FocusedWindowChanged` | `Event.Window` `Activate` / `Deactivate` |
| `NODE_DESTROYED` | element answers `UIA_E_ELEMENTNOTAVAILABLE` | **release, and post nothing** — AppKit posts `UIElementDestroyed` on its own (§13.20) | `Cache.RemoveAccessible` |
| `ANNOUNCEMENT` | `UiaRaiseNotificationEvent` | `AnnouncementRequested` with a priority | `Event.Object` `Announcement` |

The two blanks are the model being honest: nothing is invented to fill a cell.

**Amended 2026-09-14 (the rows, read against §1.10's amendments of the same day; the platform
columns are the bridge lanes' to change in phase 3).** `ACTIVE_DESCENDANT_CHANGED` is raised on
the focused node only, once per publish, carrying the previous and current cursor; Windows moves
its focus to the cursor (`AutomationFocusChanged` on the item, `HasKeyboardFocus` true there)
and macOS answers the cursor as its focused element, Linux keeps the row as written.
`STRUCTURE_CHANGED` is one per surviving parent, carrying the added, removed and reordered
children with their indices, which is what the Windows `ChildAdded`/`ChildRemoved`/
`ChildrenReordered` and the Linux `ChildrenChanged` `detail1` index are built from.
`SELECTION_CHANGED` is one per container, carrying the members that entered and left and the
container's multi flag: Windows raises `ElementSelected` for a single-select container and
`ElementAddedToSelection`/`ElementRemovedFromSelection` on the members of a multi-select one,
`Selection_Invalidated` for a bulk change; Linux and macOS address the container.
`WINDOW_ACTIVATED`/`WINDOW_DEACTIVATED` name the window node and arrive with the tree; Linux
sends `Activate`/`Deactivate` from the frame's path. `FOCUS_CHANGED` is also raised for a node
that arrived holding the focus. After a collapse to `INVALIDATED` the structure, focus, cursor,
selection and window-activation events of that publish still follow it.

**Amended 2026-09-15 (phase 3, Windows; W3, WINDOWS-NEW-4, LAB-NEW-12, WINDOWS-NEW-2, CRIT-3): the
focus rows as built.** `FOCUS_CHANGED` and `ACTIVE_DESCENDANT_CHANGED` both raise
`AutomationFocusChanged`, and on the same element: the tree's effective focus as the snapshot has
it when the drain raises, never the event's own node, so the raise and the `HasKeyboardFocus` a
reader then reads always agree. The element is **minted if no client holds it** — a row the cursor
has just reached, a dialog's first field — while every other event keeps being raised only for a
held element (§13.28's cost argument). A cursor in a native popup's tree is raised on the popup
window's element, by the popup's bridge, under a guard its whole-registry empty also takes. Nothing
is suppressed as a repeat: NVDA drops a duplicate focus event itself, and a bridge-local memory would
silence the return to an element after the focus had been in another window. The model's
`INVALIDATED` (node `0`) is swept like the bridge's own queue collapse, and after either the bridge
re-raises the focus on the effective focus; the root-targeted `INVALIDATED` the sweep raises is
`LayoutInvalidated` and sweeps nothing. The `HasKeyboardFocus` property change the `FOCUS_CHANGED`
row promises is **not** raised: NVDA 2024.4.2 subscribes to no `HasKeyboardFocus` change (the same
reading, §3), and the mapping of the remaining unmapped events is a later item of the Windows lane.
`SELECTION_CHANGED`, as built: more than `InvalidateLimit` members entering and leaving (20, the
managed provider API's own constant, read on the guest 2026-09-15, and the comparison the
platform's `SelectorAutomationPeer` makes with it: twenty is still per member) is one
`Selection_Invalidated` on the container; otherwise a single-select container raises
`ElementSelected` on the member that entered (or `ElementRemovedFromSelection` on the one that left
when none entered) and a multi-select one `ElementAddedToSelection`/`ElementRemovedFromSelection` on
each member. Each is raised only for an element a client holds. NVDA 2024.4.2 speaks none of these
for a generic item (the same reading, §2); the reader hears the cursor through the focus rows above.

**Amended again 2026-09-15 (review of those focus rows; semantics 4, WINDOWS-NEW-6): remembered,
and the rows' two promises kept.** Three sentences of the amendment above are withdrawn. *"Nothing is
suppressed as a repeat"*: semantics 4 has each bridge remember the last effective focus it announced,
and without it one publish raised `AutomationFocusChanged` on the same element two or three times (a
focus arriving on a table with a cursor is `FOCUS_CHANGED` and `ACTIVE_DESCENDANT_CHANGED`; a publish
past the model's budget is `INVALIDATED` followed by both), each raise waiting for the reader
(§13.28). The memory is now the process's — the bridge and node last announced, since UI Automation
has one focus and a raise in another window moves it — and a focus event naming it again is skipped,
while the re-announcement after a collapse or the model's `INVALIDATED` raises whatever it names. It
is forgotten when a window has nothing focused, when a window is deactivated, and when its bridge
empties, so the return to an element after the focus was elsewhere is heard, which was the objection
to a bridge-local memory. *"The `HasKeyboardFocus` property change … is **not** raised"*: it is, as
the `FOCUS_CHANGED` row says, "on both" — `false` on the element the focus left when a client holds it
and its node remains, `true` on the one it reached — and only when the announced element changed; NVDA
2024.4.2 subscribes to none (reading §3), so no reader behaviour depends on it today. *"The mapping of
the remaining unmapped events is a later item"*: `WINDOW_ACTIVATED` is now the row's "focus change into
the window", the window's effective focus raised subject to the memory, and `WINDOW_DEACTIVATED`
raises nothing and forgets the memory; neither pays the event an ask is owed unless something was
raised (`UiaBridgeTest.theFocusAlreadyAnnouncedIsNotRaisedAgainButIsReannouncedAfterTheModelsInvalidated`,
`aFocusMoveTellsTheElementItLeftAndTheOneItReachedThatTheKeyboardMoved`,
`aWindowActivatedAgainRaisesTheFocusItHadBecauseTheDeactivationForgotIt`,
`aFocusRaisedInAnotherWindowMakesTheReturnHeard`). A raise from another window holds that window's
guard across the platform call, so a client whose focus handler synchronously asked the host's
`GetFocus` would wait for it; NVDA 2024.4.2's handler asks no such thing (reading §1), and phase 5
watches for it.

**Amended 2026-09-15 (phase-3 fix round; semantics 4 settled to one shape): the re-announcement is
raised at the tail's place, after the tail's structure events.** The amendment above left it raised
the instant the sweep finished — "after either the bridge re-raises the focus on the effective
focus" — which is *before* the `STRUCTURE_CHANGED`s the model reserves outside its budget and sends
next. Decision 28 and semantics 7 put the tail in one order, children first and then focus, cursor
and selection, and a reader told where the user is and only then told that the tree under it changed
re-reads and asks again. So the sweep now leaves the re-announcement **owed**, and the drain raises
it before the first tail event that is not a `STRUCTURE_CHANGED`, or when nothing more is waiting —
which is the case a collapse that moved only the tree's shape leaves, and the reason the debt is
never simply dropped. Linux reconciles at the same place and macOS posts focus last in the frame;
this is the third bridge joining them, and the semantics' three readings (integration log, phase-3
critic, contradiction 1) are now one. **Two collapses with no tail between them owe one
re-announcement, not two**: what the raise pays for is the element the sweep may have released under
the reader, one raise after the last sweep says it, and each raise waits for the reader's handler
(§13.28). Pinned by
`UiaBridgeTest.theFocusIsReannouncedAfterTheTailsStructureEventsAndNotBeforeThem`;
`theModelsInvalidatedSweepsOncePerEmitAndReannouncesTheFocus` was restated to follow each collapse
with a tail event, as the model's own always is.

**Where the second flush point is, and what it took to get it there (2026-09-15, corrected
2026-09-16).** It was first written as "when nothing more is waiting" — a queue-emptiness test made
on the drain thread while the user-interface thread was still offering the tail one event at a time,
so a drain reaching the top of its loop between the collapse and the first tail `STRUCTURE_CHANGED`
saw an empty queue and re-announced early: the very order the rule above fixes, in the one case
where the drain outruns the producer. That is closed (fix round 3b, Windows item 1) and neither half
of the trade it was weighed against was paid. **The second flush point is the publish boundary.**
The Windows bridge overrides `AccessibilityBridge#frameEnded` (§5.3) and hands the boundary over as
`UiaEvents.FRAME_END`, a marker in the same queue the frame's events went into, so it arrives behind
every one of them however fast the drain thread runs. The debt a collapse leaves is flushed when
that marker is taken and — unchanged — before the first tail event that is not a
`STRUCTURE_CHANGED`, which is still where a tail with a focus, a cursor or a selection in it pays.
Linux reaches the same boundary with no queue to cross (§2.3's `frameEnded` row) and macOS posts its
whole frame there, so all three bridges now flush at one boundary in one order.

**The cost that kept it from being taken blind is answered by the marker, and the guarantee is the
narrow one.** The fear was that a marker offered into the same bounded queue would be swallowed by
that queue's own collapse, leaving a bridge whose scene then runs no further frame owing a
re-announcement with nothing to flush it — a dropped debt traded for a race. `UiaEvents#endFrame`
ignores the collapsed flag (a collapse covers the events it swallowed, and it *raises* the debt this
marker flushes, so it may not swallow the marker), and when the queue has no room it collapses the
queue — the honest answer for a queue already over capacity — and puts the marker in behind the
collapse's own. A collapse can only happen while a frame is handing events over, so that frame's end
always follows it; a window whose scene then goes still has already been told; only a frame that
could leave a debt is marked, so an ordinary frame wakes the drain thread for nothing. **What is
guaranteed is not "the marker is never dropped"** (2026-09-16, the fix round's review — the first
statement of it said more than the code does): a collapse *clears* the queue, so a frame end already
waiting in it is discarded. What holds is that no frame ends without a marker going in, and that a
debt is cleared by the raise that pays it and by nothing else — the frame in which a collapse
happened owes one of its own (`emit` sets the flag on the refused offer), so it marks its end behind
that collapse and the drain flushes every debt still owed when it gets there. The count of markers a
drain sees can fall; the number of debts left with none cannot rise above zero.

Pinned by `UiaBridgeTest.theFocusIsReannouncedAtTheFramesEndAndNotTheMomentTheQueueRunsDry` (which
waits for the drain to be parked in its take — the one place the old emptiness test had certainly
already fired — and asserts nothing has been said yet),
`aCollapseWithNoTailAtAllIsStillFlushedByTheFramesEnd`, `UiaEventsTest`'s three marker cases, and
`aCollapseThatClearsAnEarlierFramesEndStillPaysTheDebtAtItsOwn`, which holds the drain inside the
first sweep so the loss is certain, shows one marker waiting before the second frame's collapse and
one after it, and then shows the re-announcement raised at the second frame's end (red with
`endFrame` returning early while collapsed: "and the frame that collapsed marks its own end behind
it ==> expected: <2> but was: <1>"); `UiaEventsTest.aFullQueueCollapsesRatherThanDropTheFramesEnd`
asserts the same arithmetic on the queue alone. **Phase 5 no longer listens for an early focus**;
what it still hears is the order itself.

**Amended 2026-09-15 (phase 3, Windows; WINDOWS-NEW-6's remainder): `CARET_MOVED` and
`BOUNDS_CHANGED` as built.** `CARET_MOVED` is `Text_TextSelectionChanged`, as its row says, handled
together with `TEXT_SELECTION_CHANGED` (the settled unmapped-and-window-level-events item): the model
emits the two for one field one after the other, and a `TEXT_SELECTION_CHANGED` right behind a raised
`CARET_MOVED` on the same node is not raised again. Both are raised only for a held element, and no
element serves `TextPattern` yet (§2.1, §11), so what a client can do with the event is re-read the
value; NVDA 2024.4.2 maps it to its `caret` event on the focus only (reading §3); phase 5 hears
whether that says anything over a `ValuePattern` field. **`BOUNDS_CHANGED` keeps no mapping, per node
and in bulk**, where the row says a `BoundingRectangle` change or one `LayoutInvalidated`: NVDA
2024.4.2 subscribes to no `BoundingRectangle` change (reading §3) and handles `LayoutInvalidated` only
for Windows search suggestions (§6), while each raise waits for the reader's handler (§13.28), which
during a scroll or a drag is one wait per frame for nobody. Both forms now say so in the trace; the
bulk one (node `0`) returned silently before, and neither pays the event an ask is owed
(`UiaBridgeTest.aCaretMoveIsTheTextSelectionChangeAndItsPairIsRaisedOnce`,
`aBoundsChangeIsRaisedNeitherPerNodeNorInBulkAndSaysSo`).

**Amended 2026-09-15 (phase 3, Windows; WINDOWS-NEW-1, WINDOWS-NEW-3): `ANNOUNCEMENT` and
`STRUCTURE_CHANGED` as built.** `ANNOUNCEMENT` is `UiaRaiseNotificationEvent` on the root's element,
minted if no client holds it: kind `Other` (4) and processing by politeness, `ASSERTIVE` →
`ImportantMostRecent` (1), `POLITE` → `All` (2), the enumerators read 2026-09-13; the text and an
empty activity id travel as `BSTR`s freed after the call, the activity id being what WinForms' own
`AccessibleObject.RaiseAutomationNotification` passes (read as IL 2026-09-15,
readings/windows-dump-uia-provider-conventions.txt §4). NVDA 2024.4.2 consumes notifications from any
element that resolves to a window, while its focus is in this process, cancelling speech first for
`ImportantMostRecent` and queueing `All` (readings/nvda-2024.4.2-uia.md §4). `STRUCTURE_CHANGED` is
`UiaRaiseStructureChangedEvent` in the shape the platform's own `AutomationPeer.UpdateChildrenInternal`
raises, read as IL the same day (§3 of that reading): past the limit — `ItemsInvalidateLimit` (5) for a
container of items (a node with a selection or table facet), `InvalidateLimit` (20) otherwise, which
is what `ItemsControlAutomationPeer` and `AutomationPeer` pass — one `ChildrenBulkRemoved` (4),
`ChildrenBulkAdded` (3) or `ChildrenInvalidated` (2) on the parent with the parent's runtime id;
otherwise `ChildRemoved` (1) on the parent with each removed child's runtime id, then `ChildAdded` (0)
on each added child's own element with its own; and one `ChildrenReordered` (5) on the parent for a
publish that moved surviving children, which the peer has no case for. It is raised only when a client
holds the parent's element, an added child's element being minted for its `ChildAdded`; the parent is
the model's, so a nested tree row removed (§2.1's navigation) is reported to the tree. **NVDA 2024.4.2
subscribes to no structure change** (readings/nvda-2024.4.2-uia.md §5): the event is for the clients
that do (Narrator, Inspect, a .NET client), and phase 5 counts it with one
(`UiaBridgeTest.anAnnouncementIsRaisedOnTheRootEvenBeforeAnyClientHeldIt`,
`aStructureChangeIsRaisedAsThePlatformsOwnPeerRaisesIt`,
`aStructureChangeIsRaisedWhenTheParentIsHeldAndMintsTheChildItAdds`).

**Amended 2026-09-15 (review of the Windows phase-3 work): where the two structure-change choices
come from.** The Windows brief asked for `ChildrenBulkAdded`/`ChildrenBulkRemoved` for the model's
coalesced per-parent event; the amendment above raises single `ChildAdded`/`ChildRemoved` events up to
the limit and a bulk change only past it. That is an interpretation, taken because the platform's own
`UpdateChildrenInternal` raises a coalesced change of few children that way (readings/
windows-dump-uia-provider-conventions.txt §3), and it is put to the owner in the lane log. And the
`ChildrenReordered` runtime id is not a free choice: `UpdateChildrenInternal` has no case for a move,
but the same listing holds two client-side proxies that raise `ChildrenReordered` (5), each on its
element with that element's own runtime id — `EventManager.HandleStructureChangedEventWindow` for
WinEvent 32772 (its `MakeRuntimeId()`) and `MSAAEventDispatcher.MaybeFireStructureChangeEvent`'s
default branch (the runtime id of the provider made for the event's object) — which is the shape raised
here on the parent. That the element is the container whose children moved rests on 32772 being the
Win32 reorder event, whose header name was not read.

**Amended 2026-09-15 (phase 3, Windows; CRIT-4's Windows half, the settled value-text-event item,
T7's Windows reading): `VALUE_CHANGED` and `BUSY` as built.** The property-change row's "the
property id" is, for `VALUE_CHANGED`, the property of **each pattern the node vends that the change
moved**: `RangeValue.Value` (30047) with both numbers where the node vends `RangeValue` and the
event's number moved, then `Value.Value` (30045) with the string `get_Value` answers wherever the
node vends `Value`. A change whose number stood moved the text or the emptiness, so a date segment
filled with its minimum raises the string alone; a number that moved raises the string too, because
the event carries no text to compare and a `Value` vended from a value facet is the number's spoken
form. The old string travels as an empty variant: a COM client's `HandlePropertyChangedEvent`
receives only the new value (UIAutomationCore.dll's type library, read 2026-09-13). Until this
amendment one property was raised, `RangeValue.Value` for any node with a number, so a spinner's
"07:30" and a segment's "empty" were never raised as strings, though NVDA 2024.4.2 reads a control
that vends both from `Value` (measured on the guest 2026-09-07, §13.19) and maps both properties
to its `valueChange` (readings/nvda-2024.4.2-uia.md §3); whether it then speaks a change raised as
both once or twice is phase 5's to hear. An event that moved nothing a vended pattern carries raises nothing and pays nothing an ask
is owed (`UiaBridgeTest.aValueChangeRaisesThePropertyOfEachVendedPatternItMovedOn`,
`aValueChangeIsRaisedOnTheHeldElementAsEachPropertyThatMoved`). **`STATE_CHANGED` for `BUSY`** stays
an `ItemStatus` (30026) property change carrying the localized busy phrase and then an empty string,
answered by `get_ItemStatus` the same way; **NVDA 2024.4.2 has no handler for it**: `ItemStatus`
maps to its `UIA_itemStatus` event, which nothing in NVDAObjects handles, and it reads `ItemStatus`
only as the description of an element whose class name is `UIColumnHeader` (readings/
nvda-2024.4.2-uia.md, "`event_UIA_itemStatus`"), so a busy tree row is silent to it on Windows until
a fallback is decided after phase 5's reader run (T7).

#### Amendment 2026-09-15 — the Linux column, as the bridge sends it

Read against what a real toolkit on the Fedora guest sends and what Orca 50.2 does with each field
(readings/upstream-gtk-4.22.4-atk-adaptor-2.60.6-event-shapes.txt, fetched on the host for the
guest's gtk4 4.22.4 and at-spi2-core 2.60.6; readings/fedora-orca-event-consumers.txt and
ubuntu-orca-event-consumers.txt, `scripts/a11y/linux/read-orca-event-consumers.py` on both guests,
2026-09-15). Each paragraph names the row it changes; the table above is left as written.

**`ACTIVE_DESCENDANT_CHANGED` (L1).** `ActiveDescendantChanged` from the focused node's path, with
the new descendant's `(so)` reference as the value and its index in its parent in `detail1` (the ATK
bridge's `active_descendant_event_listener`; GTK 4.22.4 sends no such event). The value was an `i`
0, which libatspi 2.60.6 turns into no `any_data`, and Orca drops the event without one. The
reference and the index are the ones `GetChildAtIndex` and `GetIndexInParent` answer for the same
node, in whichever window holds it (decision 5's popup option is an ordinary reference on the one
connection); a cursor that went away names the null object with `detail1` −1.

**`FOCUS_CHANGED`.** `StateChanged` `focused` 1 on the node gaining it and 0 on the node losing it,
both from the `STATE_CHANGED` the difference raises; `FOCUS_CHANGED` itself sends nothing, and the
deprecated `Event.Focus.Focus` the row names is not sent — Orca 50.2's `Script.get_listeners`
registers no `focus:` event (readings/fedora-orca-event-handlers.txt). The row overstated it
(settled linux-adr-overstatements). *(Corrected 2026-09-15, the review of linux-B: "both from the
`STATE_CHANGED` the difference raises; `FOCUS_CHANGED` itself sends nothing" was false for a node
that arrives already focused — a dialog's first field, a popup's list, a cell widget realized under
the cursor. The difference raises no `STATE_CHANGED` for a new node and only the tail's
`FOCUS_CHANGED` (semantics 7), so such a node was never said focused, while the node losing the focus
was. `FOCUS_CHANGED` now sends `StateChanged` `focused` 1 from its node, after the tail's structure
signals have put the node in a client's cache, and a surviving node's gain, already sent by its
`STATE_CHANGED` in the same publish, is not sent a second time. Commit 26769b3's message repeats the
false sentence.)*

**`WINDOW_ACTIVATED` / `WINDOW_DEACTIVATED`, `WINDOW_OPENED` / `WINDOW_CLOSED` (LINUX-NEW-2,
LINUX-NEW-15, LAB-NEW-2).** `Event.Window` `Activate`/`Deactivate` from the frame's own path (the
window node the difference names, arriving after the publish whose tree marks it `ACTIVE`, so its
`state-changed:active` precedes it as GTK 4.22.4's does), with the window's name as the string value
(the ATK bridge's convention; GTK sends "0"). After `Activate` the focused node's `focused` 1 and
the cursor's `ActiveDescendantChanged` are sent again from that tree, unless the same publish
already sent them: Orca 50.2's `_on_window_activated` moves its locus to the frame, and its 0.1 s
same-type filter would drop a second copy. *(Corrected 2026-09-15, the review of linux-B: the
exception and its reason are wrong; see the correction at the end of the `INVALIDATED` paragraph
below.)* A frame that arrives after the join sends `Create` from
its path after the application's `ChildrenChanged add`; one that leaves sends `Destroy` from its path
before the `remove`. The frames the registry read at the join send no `Create`. The model's
`WINDOW_OPENED`/`WINDOW_CLOSED`, which nothing raises, map to the same members from the node they
name. Node zero's events (`BOUNDS_CHANGED` for a wide scroll) are sent from the frame, never from
the application object, and `BoundsChanged` carries the node's screen extents as `(iiii)`, the
rectangle libatspi makes an `AtspiRect` of, instead of an `i` that arrived as nothing.

**`ANNOUNCEMENT` (LINUX-NEW-3).** `Announcement` as the installed interface declares it,
`(s, i politeness, i, v, a{sv})` (readings/fedora-dbus-Event.Object.xml): empty detail, `detail1` =
`Atspi.Live` (`POLITE` 1, `ASSERTIVE` 2, read 2026-09-13 off the Fedora typelib), `detail2` 0, and
the text as a string value, which is the only `any_data` Orca 50.2's `_on_announcement` presents —
GTK 4.22.4's `gtk_at_spi_context_announce` fills it the same way. Sent from the frame of the window
whose scene said it. It was mapped to nothing.

**`TEXT_CHANGED`, `CARET_MOVED`, `TEXT_SELECTION_CHANGED` (LINUX-NEW-14).** A replacement is a
`TextChanged` `delete` carrying the removed text, then an `insert` carrying the inserted text; each
has `detail1` = the start and `detail2` = the length, both in characters, and the changed text
itself as the value — GTK 4.22.4's `gtk_at_spi_context_update_text_contents` and the ATK bridge's
text listeners send exactly that, and Orca 50.2 speaks `any_data` as the inserted string and drops
an insertion longer than 1000. It was one `insert` of the longer length at the UTF-16 offset carrying
the whole new text. The model's range, compared unit by unit, may start or end inside a surrogate
pair; it is widened to whole characters before it is converted. `TextCaretMoved` carries the caret's
offset in characters in `detail1`, read off the published `TextFacet` (it was always 0; Orca
compares it with the last cursor position). `TextSelectionChanged` carries an empty string.

**`STRUCTURE_CHANGED`, `NODE_DESTROYED` (LINUX-NEW-1, LAB-NEW-3; decision 28).** Per child of the
event's surviving parent, `ChildrenChanged` from the parent's path with the child's `(so)` as the
value and its index in `detail1` (its former index for a removal): removals first, highest index
first, each followed by `Cache.RemoveAccessible` `(so)` when the child left the tree; then additions
and reorders in ascending index, each addition followed by `Cache.AddAccessible` with the item
`Cache.GetItems` lists for it (`((so)(so)(so)iiassusau)`). That order is libatspi 2.60.6's
arithmetic: `remove` takes a child out by reference, `add` removes it and inserts it at `detail1`,
`AddAccessible` overwrites the parent's slot at the item's index, and `RemoveAccessible` disposes the
object (readings/upstream-at-spi2-core-2.60.6-libatspi.txt). A child that moved between parents is
removed from one and added to the other and never removed from the cache. `NODE_DESTROYED` sends
`StateChanged` `defunct` 1 from the node's own path, as GTK 4.22.4 does before unregistering a
context, and a node path no window holds any more answers `GetState` with `DEFUNCT` (6, read
2026-09-13 off the Fedora typelib) while declining everything else — Orca 50.2 ignores an event from
a source that is `DEFUNCT` or whose name cannot be read (readings/fedora-orca-dead-object.txt). It
was one `ChildrenChanged` with an empty detail and an `i` per new node, and a `remove` from the
destroyed node's own dead path, whose `int` crashed Orca's `_ignore_children_changed`. A frame
arriving or leaving after the join gets the same `AddAccessible`/`RemoveAccessible` after its
`ChildrenChanged` from the application object, which closes what §2.3's amendment of this date left
to this item. No container publishes `MANAGES_DESCENDANTS`: decision 28 keeps clients' child caches
correct instead.

**`INVALIDATED` and the reserved tail (L6; decision 28; semantics 4 and 7).** The table has no
`INVALIDATED` row; on Linux it sends nothing of its own — the bridge holds no per-node state to sweep,
and the one `Cache.AddAccessible` for the root that §2 once prescribed would reconcile no client's
cached children or states (libatspi 2.60.6 updates a cached child list only from `ChildrenChanged`
and a state only from `StateChanged`). What a client's cache needs arrives in the tail that follows:
the structure signals above, then the cursor, the selection and the window's activation. The
`focused` changes the collapse swallowed are said again from the tree at once: `StateChanged
focused` 1 on the focused node and the cursor's `ActiveDescendantChanged`, unless the same publish
already sent them. Every signal of a tail event, of a frame's arrival or departure, and of focus said
again is offered to the connection as the tail kind, which `Outbound.SIGNAL_BOUND`'s ordinary backlog
never refuses (`Outbound.TAIL_BOUND`, sixteen times it, bounds the tail alone so a writer that never
writes is still not a leak). An ordinary signal the connection does refuse is followed, once per
publish, by the focus and cursor said again as the tail kind: the bridge's own queue collapse is
answered like the model's. *(Corrected 2026-09-15, the review of linux-B, together with the
`Activate` sentence of the window paragraph above. Three things were wrong. First, "said again
from the tree at once" put the focus and cursor before the tail's structure signals and before
`Activate`, against decision 28's order. When the frame's `state-changed:active` was collapsed
too, `Activate` then moved Orca 50.2's locus to the frame, and nothing brought it back: LINUX-NEW-15
again, on the collapse path. Second, the memory of what had been said was cleared on every publish,
so every publish wider than the budget repeated the focus and cursor even when neither had moved.
Third, a collapse never sent `focused` 0 for the node that lost the focus, and libatspi 2.60.6's
`cache_process_state_changed` clears only the bit an event names. What the bridge does instead:
each window remembers, across publishes, the focus and cursor it last announced (semantics 4).
`INVALIDATED` and a refused signal only mark a reconcile as owed. The reconcile runs before the first
tail event after the structure signals, or, when the publish carries none, before the window's next
publish replaces its tree. It sends only what differs from the memory: `focused` 0 for the node last
announced when that node still stands, `focused` 1 for the node now focused, and the cursor when it
differs or the focus was just said. The window paragraph's exception, "unless the same publish
already sent them … its 0.1 s same-type filter would drop a second copy", misread Orca. That filter
is never reached by a `focused` 1 from a focused source (`_ignore_by_focus_state`, event_manager.py
324-330). What decides the case is which event moves the locus to the frame. The frame's own
`state-changed:active` 1 makes it the active window with the frame as locus (`_on_active_changed`,
default.py 792-822, readings/fedora-orca-focus-manager.txt, Fedora KDE 44, Orca 50.2,
2026-09-15), and an `Activate` for a window that is already active returns without touching the
locus (default.py 1386-1390, readings/fedora-orca-event-consumers.txt). So after `Activate` the focus and cursor are said again only
when they were not said after the frame's `active` 1 in the same publish, or when that `active` 1
was not sent at all. Two identical copies waiting in Orca's queue together are handled once:
`_is_obsoleted_by` drops the earlier for the later, matching same type and same source
(readings/fedora-orca-event-queue.txt). Orca's queue is ordered by `_get_priority` before arrival;
the numeric values of its constants were not read.)*

*(Amended 2026-09-15, semantics 4 settled for the three bridges after phase 3. Two sentences above
change. "It sends only what differs from the memory" is now true of an ordinary publish only: **an
owed reconcile — one the model's `INVALIDATED` or a refused signal asked for — says the focus and
the cursor again whether or not they moved.** Linux was the only bridge that sent nothing when the
focus had not moved, while Windows re-raises and macOS re-posts unconditionally after their own
sweeps; and a focus that did not move is precisely the case where the client is standing on a node
whose state changes it lost in the collapse. The repeat costs a message and no speech: Orca 50.2's
`set_locus_of_focus` returns at once when the locus is already that object (focus_manager.py
278-281, readings/fedora-orca-focus-manager.txt). The memory stays, and is what keeps an ordinary
publish quiet. And "when the publish carries none, before the window's next publish replaces its
tree" becomes **at the end of the frame** (`AccessibilityBridge#frameEnded`, §5.3): a collapse whose
tail holds nothing after its structure signals used to wait for a next publish that a window going
still never makes, so the re-announcement the semantics ask for never happened at all. The publish
path stays as the later net for a refusal that comes after the frame has ended. Order is unchanged
and is semantics 7's: the structure signals first, then focus, then the cursor, then selection and
the window's activation. Pinned by
`AtspiApplicationTest.aCollapseWhoseTailIsStructureAloneSaysTheFocusAgainWhenTheFrameEnds` and
`aCollapseSaysTheFocusAndTheCursorAgainAtTheFramesEndEvenWhenNeitherMoved`.)*

*(Amended again 2026-09-15, the review of that change; semantics 4's other half. "Each window
remembers, across publishes, the focus and cursor it last announced" was one memory per window,
where the settlement asks for **one memory per process** — the platform focus is one — and the
unconditional re-say the amendment above added was gated on nothing, so a collapse or a refusal in a
**background** frame put a `focused` 1 on the bus for a window nobody is in, a case that had stayed
silent while it only sent differences. Both halves close together. The memory is now
`AtspiApplication`'s: the window that holds it, the node, and the cursor, cleared when that window
detaches and on a new join (Windows keeps the same pair in `UiaBridge.ANNOUNCED`). And **only the
frame the desktop has active reconciles at all**: the model publishes `ACTIVE` on the window node of
the scene whose window has the keyboard (`AccessibleWalk`, `Scene#isWindowFocused`), a native popup
that takes the focus included, and a frame without it holds the node the user would *return* to and
not where the user is — which is what Orca 50.2 says of such an event in as many words, "[frame]
lacks active state", then "unable to find active window" (readings/fedora-l4-baseline/summary.md,
LAB-NEW-2). A window that is not active therefore says nothing here: it neither repeats nor
contradicts what the active frame announced. One consequence is new and deliberate: when the active
frame re-says the focus, the `focused` 0 for the node the process last announced goes out **from the
window that holds that node**, which may be another frame — libatspi's `cache_process_state_changed`
clears only the bit an event names, so a focus that crossed windows used to leave `FOCUSED` cached on
a node of each. Pinned by
`AtspiApplicationTest.aBackgroundFramesCollapseSaysNothingAndTheActiveOnesClearsTheOneFocusAnnounced`.)*

**`STATE_CHANGED` for `EXPANDED` and `EXPANDABLE` (L3; decision 27; semantics 9).** Both reach the
bus as `StateChanged` `expanded` / `expandable` (bits 10 and 9) from the difference, and whenever the
bit this platform derives from them — `COLLAPSED` (5), published as `EXPANDABLE` without `EXPANDED` —
moved with the flip, a `StateChanged` `collapsed` follows it: libatspi 2.60.6 sets or clears only
the bit an event names, so without it a client that cached a closed branch and heard `expanded` 1
held both. Its former value is read off the node in the tree the window published before
(`AtspiBridge.previousTree`), because a publish may flip both bits at once. GTK 4.22.4 sends
`expandable` and `expanded` only (whether its state set carries `COLLAPSED` was not read).

**`VALUE_CHANGED` (settled linux-value-text; added 2026-09-15 with the `Text` interface, §2.3's
amendment of this date).** `PropertyChange` `accessible-value` with the new number as a `d`, as
before; and when the number stood still while the display form moved — a date segment filled with its
minimum goes from "empty" to "1" — and the node serves that form as its `Text` (a `ValueFacet` and no
`TextFacet`), a `TextChanged` `delete` of the former form and an `insert` of the new one follow it,
whole string for whole string, read off the node in the window's previous and current trees. A change
that moved the number sends the property change alone, and a node with a text of its own raises its
own `TEXT_CHANGED`.

**A change that moves the interfaces a node serves re-sends its cache item (amended 2026-09-15, review
of the interfaces item).** What a node serves is not fixed by its role: `Text` is served for a value
only while its display form is not empty (§2.3's `Text` amendment), `Action` only while the node has a
verb, which it loses with `ENABLED` — beneath an overlay too — and `EditableText` only while it is
`EDITABLE`. A client keeps a node's interfaces from its cache item, and libatspi 2.60.6's
`add_accessible_from_iter` overwrites them, with the name, role, description and states, from a later
`Cache.AddAccessible` for a node it already holds (readings/upstream-at-spi2-core-2.60.6-libatspi.txt,
atspi-misc.c 578-698); nothing sent one, so a client could lack `Text` on a segment that had just
gained a word, or `Action` on a button enabled again. Now a `VALUE_CHANGED` or `STATE_CHANGED` whose node
serves a different set of interfaces than in the window's previous tree (`AtspiTree.interfaceBitsOf`)
also sends that node's `AddAccessible`: after the state change or the property change, and in a
value's text echo between the `delete` (said while `Text` was still listed) and the `insert` (said once
it is listed). A publish that moves several bits of one node sends the same item after each, and a
modal that withdraws the verbs of every node beneath it sends an item per node whose `Action` went,
beside the `enabled` 0 each already sends. Keeping the list stable per role or facet was the other way
offered; it would have listed `Text` with an empty string on every slider and progress bar, which no
toolkit on the guest does (GTK 4.22.4's level bar serves `Value` alone,
readings/fedora-gtk4-interface-replies.txt). Not observed live: the gallery's date segments always
carry a word. Pinned by `AtspiEventsTest.aChangeThatMovesTheInterfacesANodeServesSendsItsCacheItemAgain`.

**Amended 2026-09-15 (phase 3, the macOS column).** `ACTIVE_DESCENDANT_CHANGED` posts
`FocusedUIElementChanged` **at application level**, as `FOCUS_CHANGED` does, and no longer
`SelectedChildrenChanged`: the cursor is the focused element here, so a client told of it asks where
the focus went. A frame posts at most one of the two, after everything else that frame posts. After
a sweep of the element registry — the bridge's own queue collapse, or the model's `INVALIDATED`,
which is now swept the same way (semantics 7) — the focus change is posted again whenever anything
anywhere is focused, because the sweep may have released what a reader stood on.
`SELECTION_CHANGED` is posted on its container as the notification of that container's selection
attribute: `SelectedRowsChanged` for an outline, a list or a table of rows — which is what a native
`NSOutlineView` posted on itself for a row selected through `AXSelected` and through
`AXSelectedRows`, with no `SelectedChildrenChanged` beside it (read on the macOS 26.6.2 guest,
2026-09-15, `scripts/a11y/macos/outline-probe.swift`) — `SelectedCellsChanged` for a grid of cells,
and `SelectedChildrenChanged` for anything else. `STATE_CHANGED` of `EXPANDED` on an outline row is
`RowExpanded` or `RowCollapsed` on the row, plus one `RowCountChanged` per outline per frame on the
outline, as the native outline posted them when its row's `AXDisclosing` was set; on anything else it
stays `ValueChanged`. *Corrected the same day (the macos-B review; M3 correction f):* a
member's `STATE_CHANGED` of `SELECTED` is not posted when its container is posted the selection change
in the same frame, and `STATE_CHANGED` of `ACTIVE` is posted nowhere — no attribute is read back off
it, and where it matters the focused node's `ACTIVE_DESCENDANT_CHANGED` is the focus change — because
one arrow in a focused `Tree` posted four `ValueChanged` on the rows beside `SelectedRowsChanged` and
the focus change, where the native outline delivered only `AXSelectedRowsChanged` to an observer that
also asked for `AXValueChanged`. One cursor move now posts exactly those two. *And the row count
(M1 correction 2):* `RowCountChanged` is no longer derived from an outline row's `EXPANDED` flip
alone. Every publish compares each held table's, outline's and list's row count — the table facet's
count, the hierarchy facet's row count, the set size — with the snapshot before, and the frame's end
posts one `RowCountChanged` on each container whose count moved and which is still in the tree, once
per container however many publishes or openings moved it: a lazy load landing under a row already
open, a refresh, and a list growing or shrinking are row-count changes too; a scroll, which changes
which rows are realized and not how many there are, is none. Only the disclosure trigger was read on
the native outline; the others follow from the same attribute.
*And the window's own events (MACOS-NEW-3, the same day):* `ANNOUNCEMENT` names no node and
`INVALIDATED` names none either, and a `STRUCTURE_CHANGED` of the root names the node this bridge elides,
so all three were posted on an element no client held — which is to say never. An announcement is now
`AnnouncementRequested` posted **on the window**, carrying `NSAccessibilityAnnouncementKey` (its text)
and `NSAccessibilityPriorityKey` (10 polite, 90 assertive) as user info; `INVALIDATED` and a change of
the root's children are one `LayoutChanged` on the window per frame. The window, because on the macOS
26.6.2 guest (2026-09-15, `scripts/a11y/macos/announcement-probe.swift`) a notification posted on the
window reached both an observer registered on the window and one registered on the application, one
posted on `NSApp` only the application's, and one posted on the content view nobody's. Whether VoiceOver
speaks an announcement posted there is phase 5's to hear. `NODE_DESTROYED` releases the element at the
frame's end and still posts nothing (§2.2's note of the same date).

**The macOS column as built (dated 2026-09-15; MACOS-NEW-7).** The table above is kept as written; this
is what the bridge posts at the end of phase 3, where the macOS cells above say otherwise.
`FOCUS_CHANGED` and `ACTIVE_DESCENDANT_CHANGED`: one `FocusedUIElementChanged` per frame at application
level, last. `STRUCTURE_CHANGED`: `LayoutChanged` on the held parent — not `Created` or
`UIElementDestroyed`, which AppKit posts itself — and on the window for the elided root's children.
`NAME_CHANGED` `TitleChanged`; `DESCRIPTION_CHANGED` `LayoutChanged`; `STATE_CHANGED` `ValueChanged`,
except `BUSY` (`AXElementBusyChanged`), `ACTIVE` (nothing), `SELECTED` on a member whose container is told
the selection in the same frame (nothing) and `EXPANDED` on an outline row (`RowExpanded` /
`RowCollapsed` and the outline's `RowCountChanged`); `VALUE_CHANGED` and `TEXT_CHANGED` `ValueChanged`;
`SELECTION_CHANGED` the container's shape; `CARET_MOVED` and `TEXT_SELECTION_CHANGED`
`SelectedTextChanged`; a row container whose count moved `RowCountChanged`. `BOUNDS_CHANGED`: **nothing**
(the cell said `Moved`/`Resized` and a bulk `LayoutChanged`; the boxes are pushed instead).
`WINDOW_OPENED`/`CLOSED`/`ACTIVATED`/`DEACTIVATED`: **nothing** (the cells named AppKit's own
notifications, which AppKit posts for the window it vends). `NODE_DESTROYED`: release at the frame's end,
nothing posted. `INVOKED`: nothing. `ANNOUNCEMENT`: `AnnouncementRequested` on the window with its text and
priority. `INVALIDATED`: `LayoutChanged` on the window, the registry swept, the focus change posted again.
Every post, an announcement's user info and a re-push's children array are made inside an autorelease
pool the bridge pushes when a publish or a frame's end starts its platform work and pops before it
returns: neither is an accessibility callback, so no pool of AppKit's is on the stack, and on the
`-XstartOnFirstThread` main thread what they autoreleased was never freed — an announcement's objects
were still alive 120 polled frames later, about five blocks a frame, and none with the pool (read on the
macOS 26.6.2 guest, 25G83, 2026-09-15, `scripts/a11y/macos/AutoreleaseProbe.java`; the macos-C review).

**Amendment, 2026-09-15 (semantics 4, one shape on all three bridges): the macOS bridge keeps the
memory too.** Phase 3 left three readings of "each bridge remembers the last effective focus it
announced": Windows one memory per process, Linux one per window sending only differences, and macOS
none at all — it posted one `FocusedUIElementChanged` per frame that drained a focus or cursor event,
and again after every sweep. The lane argued that as not a defect, because the post names no element
and the client asks `accessibilityFocusedUIElement`, which is answered live. The orchestrator settled
one shape instead, and this bridge now holds it: **one memory for the whole process**, because the
platform focus is one; a focus or cursor event resolving to the node already announced posts nothing;
the model's `INVALIDATED` and the bridge's own queue collapse re-announce whatever they name, because
the sweep may have released the element the reader stood on; and the memory is forgotten when nothing
is focused in any open window, on `WINDOW_DEACTIVATED` (which still posts nothing of ours) and when
the bridge that owns it detaches.

Two consequences worth stating. The answer is resolved exactly as `focusedElement()` resolves it, so
a cursor that lives in another window's tree is remembered as **that** window's node and a second
window asking about the same cursor does not announce it twice. And a focus event over a tree that
stamps no focus now posts nothing, where before it posted: there is nowhere to send a reader, and
Windows' `raiseFocus` has always behaved this way. Pinned by
`AxFocusTest.anEffectiveFocusAlreadyAnnouncedIsNotAnnouncedAgainUntilASweepAsksForIt` and
`aDeactivatedWindowAndAnEmptyFocusBothForgetWhatWasAnnounced`. The tail's order is unchanged and is
semantics 7's: structure first, focus last in the frame.

**What the forgetting buys here is not what it buys on Windows** (corrected 2026-09-15, the fix
round's review of the paragraph above, which had carried Windows' sentence — *"so that a return is
announced however little moved while away"* — across to this platform). That sentence is true on
Windows because §2.4's Windows cell for `WINDOW_ACTIVATED`/`WINDOW_DEACTIVATED` **is** the focus
change: UI Automation has no window activation event of its own, so `UiaBridge` raises the focus on
the return and the cleared memory is what lets that raise be heard. This bridge posts nothing for
either event and is right not to: the macOS cell is AppKit's own `MainWindowChanged` and
`FocusedWindowChanged`, the null mapping is deliberate and pinned
(`AxNotificationsTest.theWindowEventsAreAppKitsOwnAndNotOurs`), and a client that wants to know
where the user now is asks `accessibilityFocusedUIElement`, which is answered live. So a bare return
— activation back, nothing moved — announces nothing here, and adding a post for it would be
inventing a notification no reading asked for. What the forgetting does buy is the **next focus
event** after the return: a node that arrives holding the focus is a `FOCUS_CHANGED` even when it is
the node announced before (this section's 2026-09-14 amendment, WINDOWS-NEW-12), so a window whose
content was rebuilt while the user was in another application says where the user is again instead
of being silenced by a memory made while VoiceOver's cursor was in another process entirely. The
test pins both halves: the activation posts nothing and leaves the memory empty, and the focus event
after it is announced though it names what was announced before.

**An event is half a conversation, and the other half is a question this table does not name.**
Three platforms, three live runs, and the same failure on two of them: a reader is told that
something changed, it then asks a question of its own, and if nobody answers that question the
event buys nothing at all. The silence is indistinguishable from never having posted.

| Platform | What the reader asks after a focus event | Status |
| --- | --- | --- |
| Windows | `GetFocus` on `IRawElementProviderFragmentRoot` | answered from the start (phase 6) |
| Linux | whether any window has `ACTIVE` | **the phase 5 defect**: Orca suppressed *every* announcement over a correct tree with correct events, because no window claimed to be active |
| macOS | `accessibilityFocusedUIElement` | **the phase 7 defect**: VoiceOver stood on the first node and never moved, having been told each time that the focus had changed |

So the rule is not "raise the event" but "raise the event and be able to answer what it invites",
and the two are written in different files by different people. A reviewer of a fourth bridge
should look for the question before looking for the event.

#### Amendment 2026-09-16 — the reader does not only ask after a focus event; it writes back

**The table directly above asks what a reader wants to *read* after a focus event. Phase 5 on macOS
added the other half: what it wants to *write*.** VoiceOver keeps its own cursor and the keyboard
focus in step, so when this bridge posts `FocusedUIElementChanged` for a cursor the application moved
itself, VoiceOver answers by writing `AXFocused` on the element its cursor is still on — the previous
row — about 40 ms later. The `FOCUS_CHANGED` row's macOS column is therefore incomplete as written:
posting `FocusedUIElementChanged` at application level is correct and is not the defect, but **on
macOS a focus event is a round trip, and what the bridge offers as settable decides whether the
return leg lands.** Measured, `readings/phase5-macos/`: two `AXSelectedRowsChanged` and two
`AXFocusedUIElementChanged` per key, the second of each undoing the first, on every step of every
tree script, and none of it with VoiceOver stopped. §2.2's amendment of this date has the full
measurement and the fix — a row is no longer focus-settable, because a native row carries no
`AXFocused`.

**The general rule, which a fourth bridge should read before it installs a setter.** An event says
what changed; the attribute set says what a reader may change back. Where the two overlap on the same
state, the reader and the application are two writers of one value, and the platform arbitrates by
recency rather than by authority. So a setter is not safe merely because the node accepts the verb:
it is safe where a *native* element of that shape carries the attribute, because that is what tells
the reader whether this is a value it owns. Neither half is visible headlessly — a unit test proves
the write lands, and only a live reader makes the write.

**And the reason both defects survived so long is worth more than either of them.** Neither is
visible on a widget that has a second thing to announce. On macOS the check box appeared to be
followed correctly for four runs, because its *value* changed at the same moment its focus did and
the value change was announced; the button was the only widget in the probe with nothing to say but
its own name, and it was the only one that looked broken. A probe whose every step also changes a
value cannot see this class of defect at all. §12.2's lab suites therefore drive focus and value
**separately**, and a widget with no value is not a poor test case but the only one that works.

---

## 3. The threading contract, per platform

The three platforms differ in **who calls** and **on which thread**, and a single answer would be
wrong twice.

### 3.1 Windows

**Who calls.** UI Automation, on RPC threads it creates and LWJGL's callback trampoline attaches to
the JVM as daemons. Measured: up to three live at once, 2031 of 2191 calls, 2007 of them while the UI
thread was not in the pump. No lock is taken on our behalf. The one exception is `WM_GETOBJECT`, which
arrives on the UI thread inside `glfwPollEvents`.

**How a call reaches toolkit state.** It does not. Every provider method answers from the published
snapshot, with no hop and no wait. `AccessibleTree` is immutable and published through a `volatile`
write of a fully constructed object, so the Java memory model gives every reader a consistent view
with no lock on either side.

**Where the first snapshot comes from, which is the one thing a per-frame publish cannot supply.**
`UiaClientsAreListening()` is false on every frame drawn before a client attaches — that is what it
is for — and the loop sleeps in `glfwWaitEvents` and renders only windows that asked for a frame. So
at the instant `WM_GETOBJECT` arrives there has never been a publish (no longer so since 2026-09-16:
the bind publishes the window's own node, which is what the amendment below is), and a bridge that could only
wait for the next frame would return a root provider with nothing behind it. Worse, the calls that
follow are immediate and synchronous: the spike recorded 160 provider calls on the main thread inside
`UiaReturnRawElementProvider` itself — `get_ProviderOptions`, `get_HostRawElementProvider`,
`Navigate(Parent)`, a `QueryInterface` burst — before that function returned.

~~So the `WM_GETOBJECT` handler calls `Host#republishNow()` before it returns the root provider.~~
**Measured false on 2026-09-07, and the opposite is what works.** The handler was allowed to publish
there — the message arrives on the UI thread inside the pump — but a reader cannot take it: the
publish diffs the priming tree against the live one and its events are raised while the reader is
still inside its own call for the root, and NVDA then announced the window and never anything in
it. A publish merely *requested* for the next frame did the same. What reads every value is the
handler that hands over the tree it already has — the priming publish (§5.3) is a truthful tree —
and opens the gate (`noteAsked`), so that the first frame something moves publishes and raises it
outside anyone's call. From then on the per-window gate below decides; the flag it replaced
"starts set and is cleared only by a publish" no longer, because the walk is no longer the flag's. A republish requested from an RPC thread (`Host#requestRepublish()`)
goes the asynchronous way, because by then a tree exists and one frame of staleness is the trade
§1.1 already made.

**Amended 2026-09-16: "the tree it already has" was the empty one, and that is what the reader
heard.** The paragraph above is right that nothing is published inside the message and wrong about
what the handler had to hand over. The priming publish of §5.3 is the *first frame's*, and on the
Windows 11 guest the first frame lands 305–523 ms after the bind while the client's two asks arrive
50–141 ms after it — both inside the gap, every time. `rootElement()` answers `0` while
`tree().nodeCount() == 0` and the window procedure then falls through, so the window tells the client
it has no provider at all; a client told that does not ask again, `AdviseEventAdded` for
`UIA_AutomationFocusChangedEventId` never reaches the root, and every focus event raised for the rest
of that window's life returns `S_OK` into a subscription nobody made, while the same client keeps
receiving the bridge's other events through its global handler and keeps getting an answer to every
question it asks. Ten runs of one build split four spoken and six silent on exactly that line: one
`0` before the first `65536` and the reader speaks, two `0`s and it is silent, ten runs out of ten.
What separated them was not our own latency but when the client's *second* ask arrived — 55–149 ms in
the silent runs, answered within 2 ms, against 248 and 267 ms in two of the speaking ones, while the
other two speaking runs carry no second client ask at all. Four runs with the
reader started after the first publish spoke four times out of four. It is also what the 2026-09-07
paragraph above was watching — "announced the window and never anything in it" is the *symptom of
answering nothing*, and at a base rate of one speaking run in fourteen, two runs could not tell the
two causes apart. The rule that nothing is published inside the message stands; what changes is that
a tree now exists before the message can arrive. `bind` publishes the window's own node and nothing
under it — role, title, the size the window already has, all true before any layout, under the
identifier every later walk reuses — for a bridge that answers
`AccessibilityBridge#needsRootBeforeTheFirstFrame()`, which is this platform and no other (§5.2).
Evidence: `.claude/pending/2026-09-13/readings/phase5-windows-diagnosis/evidence-table.txt`.

**What the snapshot costs.** One allocation per publish, sized to the node count, on frames where the
tree was dirty **and** a client is listening. Idle frames publish nothing. The arrays are never reused
between generations, because a reader may still hold the previous one; immutability plus the garbage
collector is the whole lifetime story. §12.1 requires the per-publish cost to be measured with an
`AllocationProbe` before this ships, and the idle-frame cost to be asserted at exactly zero.

**When the UI thread is blocked by a modal or a file dialog.** Already-attached clients keep reading
the snapshot and keep getting answers, because their calls never touch the UI thread. Actions they
post queue up and run when the drain resumes. A client *attaching* during the block is the open
question: `TinyFdDialogs`' own javadoc says that on Windows — and only on Windows — the chooser is a
Win32 common dialog that "pumps its own message loop on this thread", and a thread pumping messages
delivers sent messages, which is what `WM_GETOBJECT` is. That is an inference from two documented
facts, not a measurement, and this design does not assert it: §13 carries it as an open item with the
experiment. A native resize drag definitely does block the pump, because GLFW's own poll does not
return during one.

**When the event pump stalls.** Measured in Finding 2: an already-attached client keeps working, a new
one cannot attach at all, and the whole subtree is unreachable until the pump resumes. The rule for
the toolkit is the rule it already has — keep handlers and tasks inside the 8 ms budget — and the
WARNING that budget already logs is now also an accessibility warning.

### 3.2 macOS

**Who calls.** AppKit, on the process main thread, which under `-XstartOnFirstThread` is the Java UI
thread. Measured directly, twice, and once from an ordinary unprivileged client so that the
lab's `sudo` is not the reason (Finding 4).

**How a call reaches toolkit state.** It is already there. No hop, no lock, no wait — a macOS
accessibility callback *is* the UI thread, and that is the largest structural difference between this
platform and the other two.

The bridge still answers from the snapshot rather than from live widgets, for one reason worth
stating: the callback arrives inside `glfwWaitEvents`, at a point where no Java code expects re-entry
into the widget tree. Reading a snapshot means an AX request never re-enters layout, never observes a
half-applied mutation, and shares its correctness argument with the other two bridges. What macOS
does, at the top of a callback with the dirty flag set, is call `Host#republishNow()` — one build per
burst, guarded by the snapshot version, buying exact freshness. That build walks and describes; it
does not mutate the tree and it does not lay out, and §12.1 asserts both.

**And it publishes reentrantly, which on this platform is the difference between a fresh answer and a
crash.** The callback is standing on elements this bridge vended and AppKit is holding. An ordinary
publish would run the release sweep and free the ones absent from the new tree — including,
plausibly, the element being asked — re-push `setAccessibilityChildren:` under a walk already in
progress, and drain notifications from inside a notification callback. So `republishNow()` hands the
tree over as `publish(tree, reentrant = true)`, and a reentrant publish stores it and does nothing
else; every deferred obligation is paid by the next frame, which the same call has already bought
(§5.2, §5.3). This is the one rule that macOS's callback-is-the-UI-thread property makes *harder*
rather than easier, because on this platform there is no other thread whose absence would have
stopped it.

**A rebuild there runs application code somewhere the toolkit has never run it before, and it is
contained like everywhere else.** `onAccessibility` is application code the moment an application
subclasses a widget, and on this path it runs inside a libffi closure invoked by AppKit, under a
`glfwWaitEvents` frame. Every other place application code runs has a contained crash phase —
`FRAME`, `INPUT`, `TASK`, `TICKER`, `DECODE`, `EVENT_POLL`, `WINDOW_CLOSE` — and an uncontained
exception here would unwind through Objective-C, where the behaviour is undefined and the symptom
is a process that dies with no Java stack. So `CrashPhase` gains `ACCESSIBILITY`, `republishNow()` wraps the walk in
the same `Crashes.dispatch` contract the frame uses, and a crashed describe pass leaves the previous
snapshot published and answers from it. The same containment covers the Windows `WM_GETOBJECT` build
and the per-frame publish, which are the same walk on different threads.

**A callback that schedules work must also wake the loop it is standing inside.** An AX action posts
a task and a rebuild may request a frame, and neither wakes the pump today (Finding 13), so both
would wait for unrelated input — a VoiceOver press that lands when the user next moves the mouse is
indistinguishable from a press that was ignored. §8 makes every scheduling path wake the loop when
the loop is parked, which for a UI-thread caller is exactly the re-entrant case and cannot race.

**When the UI thread is blocked.** Accessibility stops completely, for the duration. A `tinyfd`
chooser on macOS runs `osascript` through `popen` and reads one line; no GLFW callback fires, no frame
is drawn, no posted task runs, and no AX callback is answered either. That is the correct outcome
rather than a defect: the panel on screen is another program drawing its own window, and VoiceOver is
talking to *it* (ADR 022). The same is true during a native resize drag, and it is the same window in
which the application is already frozen.

**The pump rule is measured, and it is the same rule Windows imposes.** Finding 4: AX requests reach
Java only while the loop is inside GLFW's event pump — every client-caused read in every loop mode
arrived there, and in the worst mode nothing reached Java for 36 seconds and then one pump served the
whole backlog at once. The consequences for this design are three, and none of them costs anything
today.

`glfwWaitEvents` is the *right* idle state, not a hazard: it is where a by-name find completes in
0.9 ms. `LwjglBackend#runEventLoop` already parks there, so the loop needs no change for
accessibility's sake — only the wake of §8, so that work scheduled from inside a callback does not
wait for the next unrelated event.

An ordinary frame loop is fine and a stalled one is not, with a cliff rather than a slope: at 16 ms
between pumps a screen reader's traversal costs a couple of hundred milliseconds, at 250 ms it costs
about a second and a half and still completes, and somewhere between 250 ms and 2 s it stops
completing *reliably* — `kAXErrorCannotComplete`, which a screen reader presents to its user as a
window with no content. **Not "never": the verifier saw one traversal get through at a 2 s pump
interval, in 17.6 seconds**, and the record says so because a design must not rest on a categorical
claim that is false. It rests on the weaker and sufficient one — seventeen seconds and a
coin-flip is not an interface. That is the same failure Finding 2 measured on Windows, arrived at
from the other direction, and it means the toolkit's existing 8 ms slow-handler and slow-task budgets
are the accessibility budget on this platform too, with two orders of magnitude of headroom.

**None of those latencies is a cost of this bridge and none may be quoted as one.** Two clients
measured against one unchanged provider disagreed, so the numbers characterise the client that took
them. What transfers to this design is the relationship — main-thread sleep governs whether a client
can finish at all — and nothing finer.

*What the spike deliberately did not settle*: **why** nothing was served during 36 seconds of
two-second pumps — whether the AX server stops forwarding to a process it has marked unresponsive, or
the requests never reach the run-loop mode `glfwPollEvents` services. The observation is
reproducible; the mechanism is not established, and §13 keeps it there rather than letting a guess
into the rationale.

### 3.3 Linux

**Who calls.** Nobody. We open a socket and read messages we chose to read, on our own reader thread.
Measured: 51 inbound calls from four client connections, every handler on that thread, the main thread
never re-entered.

**How a call reaches toolkit state.** Through the snapshot, from the reader thread, with no hop. The
two hard rules, both from the measurement: a handler must not block, and must never make a blocking
call on the same connection, because the reply would arrive on the thread parked waiting for it.
Reading an immutable snapshot satisfies both trivially, which is the point.

**There are two threads on this platform, a reader and a writer, and the split is not an
implementation detail.** An earlier draft put the outbound drain on "the bridge's own thread", which
on Linux reads as the reader thread — and that is the same mistake as blocking in a handler, made
from the other side. A blocking write on a full send buffer parks whichever thread performs it, and
the reader thread is the one thread that serves every inbound call from every client; parking it
stops the entire provider until the peer drains, which is a hazard a well-behaved client cannot even
detect it is causing.

So: **the reader thread never writes to the connection, and the writer thread never reads from it.**
The reader parses a message, computes its answer from the published snapshot — which is why the
snapshot has to be a snapshot — and enqueues the reply. The writer performs every write on the
connection: replies and signals both, in enqueue order, which is what keeps D-Bus's own ordering
guarantees intact. The UI thread's event flush enqueues onto the same queue and blocks on nothing.
A direct write from the UI thread would park *it* instead, which is the third version of the same
bug.

The queue's bound applies to **signals only**. A reply may never be dropped or collapsed: a client
that made a method call is waiting for exactly one answer, and swallowing it hangs that client rather
than degrading it. So an overflow collapses the pending signals into one invalidate-everything with
the registry reconciliation of §1.10, and the replies ride through untouched.

**`Cache.GetItems`** is answered from the pre-marshalled body the bridge builds lazily on the first
request after a publish. Without it, describing two objects cost 46 round trips; a real tree of a few
hundred nodes would cost thousands.

**When the UI thread is blocked, and when the pump stalls.** Nothing happens to accessibility. The
reader thread is ours, the snapshot is already published, and every read is answered. Only actions
queue. This is the one place Linux is strictly better off than the other two, and it falls straight out
of owning the transport.

#### Amendment 2026-09-15 — the join has a thread of its own, and a failed one costs nothing twice

**What was wrong.** "The UI thread blocks on nothing" was not true of the join (LINUX-NEW-12). The
first publish with a tree called the join inline: the session bus's `Hello` and `GetAddress`, the
accessibility bus's `Hello` and the registry's `Embed`, each waiting up to 15 s for its reply, on the
user-interface thread. A step that failed returned without closing the accessibility connection it
had opened — a socket and its reader and writer threads — and the next publish, one frame later,
did it all again.

**What the code does now.** The first publish that has a tree starts one daemon thread,
`limn-a11y-atspi-join`, which joins and ends; a publish while it runs starts nothing (a
compare-and-set), and events emitted before it completes are dropped as before. Every step runs
inside a `try` that closes the accessibility connection unless the join completed, and a socket
whose handshake fails is closed before the error leaves `DBus.Conn.open`. A failed join is tried
again only after a back-off — one second, doubling per consecutive failure, capped at sixty — and
only when a later publish asks, so an idle window with a broken bus spends nothing. The joiner writes
the joined state as one atomic reference together with the frames the registry read at the join, and
the user-interface thread brings its per-window bookkeeping up to that state on its next publish
(§2.3's amendment of this date). The registration rule is unchanged: nothing joins before some window
has a tree.

**Corrected 2026-09-15 (the linux-A review): the retry is asked for, not waited for.** "Only when a
later publish asks" left an idle window off the desktop: a scene publishes only when its tree is dirty,
so after one failed join a window nothing changes — the window decision 29 is about — never asked
again, and a publish that fell inside the back-off was dropped with nothing to repeat it; and because
a successful join reset the failure count, a connection lost right after its join was rejoined as fast
as the scene published. Now the joiner thread that failed **waits out the back-off itself**, holding
the join flag so no publish starts another meanwhile, and then asks every attached window for a
publish (`Host#requestRepublish`), which tries again; the switch turning off, or the last window
leaving, interrupts the wait and nobody is asked. A joined connection lost within sixty seconds of its
join counts as a failure and is waited out the same way on a thread of its own; one that held longer
is rejoined at once and resets the count. The numbers (one second doubling to sixty; sixty seconds to
count as held) are policy, not platform constants. Pinned by
`AtspiRegistrationTest.aFailedJoinWaitsOutItsBackOffAndThenAsksAnIdleWindowToPublishWithNoFrameOfItsOwn`
and `AtspiApplicationTest.aConnectionLostSoonAfterItsJoinIsAFailureAndWaitsOutTheBackOffBeforeAnyoneIsAsked`
and `theSwitchTurningOffEndsABackOffAndNobodyIsAsked`.

#### Amendment 2026-09-15 — a call is answered even when its handler fails

**What was wrong (LINUX-NEW-9).** "A client that made a method call is waiting for exactly one
answer" was honoured only when the handler returned. A handler that threw — a member called with too
few arguments or the wrong types indexes past the body or fails a cast — was logged by the reader
loop and answered with nothing, and libatspi waits out a newly added application's call timeout (up
to 15 s) before it pings and declares the process hung. A reply whose body did not match its own
signature failed to marshal inside the send, with the same result.

**What the code does now.** `DBus.Conn.replyFor` is the one step between a call and its reply, and it
never throws and never answers nothing: the handler's reply; `UnknownMethod` when there is no handler
or it declines; `org.freedesktop.DBus.Error.InvalidArgs` when the handler failed on the call's
arguments (an index out of bounds, a failed cast); `org.freedesktop.DBus.Error.Failed` for any other
exception, with its text. A reply that cannot be marshalled is replaced by a `Failed` error for the
same call. `NO_REPLY_EXPECTED` still gets no reply.

**Corrected 2026-09-15 (the linux-A review):** "any other exception" was literal — `Exception` alone —
so a handler that overflowed the stack or failed an assertion ended the reader thread and the
connection, and the application joined again for every such call. `StackOverflowError`,
`AssertionError` and `LinkageError` are answered `Failed` like an exception; only the errors that say
the virtual machine itself is failing still end the reader, and the join's back-off (the correction
above) paces the rejoin. Pinned by
`DBusConnectionTest.aHandlerThatOverflowsTheStackIsAnsweredAndNeitherTheReaderNorTheConnectionEnds`.
The three error names are read, not remembered: each is a string in the installed libdbus on both
guests, and each bus answers `InvalidArgs` to a call with arguments of the wrong type and
`UnknownMethod` to a member it lacks (`readings/fedora-dbus-bus-facts.txt`, dbus-broker 37;
`readings/ubuntu-dbus-bus-facts.txt`, dbus-daemon 1.14.10; 2026-09-15,
`scripts/a11y/linux/read-dbus-bus-facts.py`), which also read the 2^27-byte message limit the reader
refuses past on the session and accessibility buses of both.

### 3.4 The bridge's own mutable state, and which thread owns each piece

The snapshot is immutable and needs no thread. Everything else a bridge keeps is mutable, is the
bridge's own, and needs an owner named here rather than chosen three times by whoever implements
first. The **registry** — the map from a node id to the platform object that stands for it — is the
one that bites hardest, and on Windows an unassigned one is not survivable: the provider is entered
from up to three RPC threads at once, with **no lock taken on our behalf** (Finding 1), and an element
is created the first time a client navigates to a node. Two RPC threads reaching the same unvisited
node concurrently would create two objects for one id under a plain `HashMap`, hand each client a
different element for the same node, and — in the map itself — race a resize.

**Every mutable structure in every bridge, and its owner.** Nothing is left to symmetry, because the
three platforms genuinely differ:

| Structure | Windows | macOS | Linux |
| --- | --- | --- | --- |
| the published tree | `volatile` field, written by the UI thread, read by any RPC thread | `volatile` field, written and read by the UI thread | one `volatile` field **per window facade**, written by the UI thread, read by the reader thread |
| id → platform element, and pointer → object | two `ConcurrentHashMap`s; minted by `computeIfAbsent` from any RPC thread; removed as below | one plain `HashMap`, UI thread only, `Ui.checkUiThread()` on every touch | **none**: an object path *is* the id, so the reader thread parses it and resolves against the published tree |
| element removal | the **drain thread**, as it raises each `NODE_DESTROYED` and as it runs a collapse's sweep; plus the two whole-registry empties below | the **UI thread**, which is also the drain thread here | nothing to remove |
| the whole-registry empty on `attach`-over-a-live-host and on `detach` | the **UI thread**, after the drain thread has been stopped and joined, so the two never race | the UI thread | drop the facade's tree reference; nothing else |
| the outbound event queue | bounded; producer the UI thread, single consumer the drain thread | bounded; the UI thread at both ends, under a per-frame budget (§1.10) | bounded **signals only**; producers the UI thread and the reader thread, single consumer the writer thread (§3.3) |
| the last-pushed root child array | — | the UI thread, compared and re-pushed in the frame step (§5.3) | — |
| the listening gate | `UiaClientsAreListening()` asked once per frame on the UI thread, plus this window's own: a subscription count written by RPC threads through the root's `AdviseEventAdded`/`Removed`, and an ask stamp plus an owed-event flag written by the UI thread inside `WM_GETOBJECT` and cleared by the drain thread on the first raise that reaches a held element (§13.5) | a `volatile boolean` set the first time one of our implementations is entered — which is the UI thread either way | a `volatile boolean` written by the reader thread on `Socket.Embed` and on `PropertiesChanged`, read by the UI thread |
| the process-wide window table (§2.3) | — | — | copy-on-write; **written by the UI thread** at a facade's `attach`/`detach`, read by the reader thread on every `root` query |
| the pre-marshalled `Cache.GetItems` body | — | — | **the reader thread alone** builds and holds it; a `volatile long` publish counter written by the UI thread is what invalidates it |
| D-Bus serials and the socket | — | — | the **writer thread** performs every write and assigns every signal serial; the reader thread performs every read; neither does the other's (§3.3) |

Three rules make that table small enough to keep in one head. **`ConcurrentHashMap.remove` is the
whole of the ownership protocol on Windows**: the drain thread removes each entry as it announces it,
the UI thread's whole-registry empty removes all of them, the two are kept apart by stopping the drain
first, and `remove` returning non-null is still what decides who releases — so a double removal is a
removal and no ordering argument has to be re-derived at each call site. Removal is not
release, since the COM object survives until its refcount drops and answers
`UIA_E_ELEMENTNOTAVAILABLE` meanwhile, which is exactly the behaviour §1.3 promises a client holding a
stale element. **The sweep runs where the drain runs**, which is the drain thread on Windows, the UI
thread on macOS and nowhere on Linux, because the sweep exists for the collapse and the collapse is
the drain's to handle (§1.10, §5.3) — an earlier draft put the sweep in the publish step and the
per-node removal on the drain thread, which is two threads removing from one map for two different
reasons and one of them holding no element to raise from. **And macOS's simplicity is real and worth
taking**: every AX callback is the UI thread (Finding 4), every publish is the UI thread and the
notification drain is the UI thread by AppKit's own rule, so a plain `HashMap` and an assertion beat
a concurrent map written out of symmetry.

**No bridge's registry is ever touched by a widget, and no widget is ever reachable from one**, for
§1.2's reason: an element a client holds for minutes would otherwise pin a detached subtree.

**Amended 2026-09-15 (phase 3, Windows; W2): a Windows element's pattern interfaces follow the
snapshot, and the registry row above is unchanged by it.** The object behind an element was built
with the pattern list of the first ask and kept it: a pattern the node gained later (a Tree's
`Invoke` once it has a cursor row, `ExpandCollapse` on a leaf that gained children, `SelectionItem`
on a calendar cell first seen in a chooser) was answered with a null for as long as a client held
the element, and one it lost stayed answerable to `QueryInterface`. The object now reserves a field
for every pattern interface a node may vend and asks `UiaPatterns.supports` against the tree of the
moment on every query and every hand-over: a pattern served now is built the first time it is
wanted (once, under the object's own lock, from whichever RPC thread asks first), and one not served
now is refused to a new query. **Nothing is re-minted**: the registry entry, the identity pointer,
the reference count and every pointer already handed out stay what they were, so the root handed to
`UiaReturnRawElementProvider` is still the one `UiaDisconnectProvider` disconnects, and a pointer to
a withdrawn interface still reaches live closures (whose slots answer from the snapshot) until the
whole-registry empty frees them. Retiring the element and minting a successor was the alternative;
it would have put a second writer on the id map, two objects behind one runtime id, and an exemption
for the root, which is why it was not taken (`UiaObjectTest`, `UiaBridgeTest`). **And a second
amendment the same day (W3):** an element may be minted by **another window's drain thread**, when
that window's focused field has its cursor in this window's tree (decision 5); minting was already
any thread's, so the id map is unchanged, and the whole-registry empty now takes a per-bridge guard
that such a raise also holds, so it never frees an element a raise from outside is standing on.
**Amended again 2026-09-15 (review of that change):** the guard covered the raise and not the answer.
A host window's `GetFocus`, answering a cursor that lives in the popup's tree, minted and referenced
the popup's element on the host's RPC thread with no lock, and the popup's detach does not fence that
call, because it arrives through the host's provider, which is still connected. That hand-over now
takes the popup bridge's guard too, and answers nothing once the popup has left the process's set of
open bridges (its detach leaves the set before it empties)
(`UiaBridgeTest.aHandOverToAnotherWindowsGetFocusWaitsForThatWindowsEmpty`). A bridge's own RPC
calls are not changed by this: they arrive through its own provider, whose root the empty
disconnects first, and whatever race that leaves between an RPC thread and the empty is the one they
already had, not a new one.

**Note 2026-09-15 (Linux rows).** The listening gate is a `volatile boolean` written by the status
thread that watches `org.a11y.Status` (§6's amendment of this date), not by the reader thread on
`Socket.Embed`. The joined state is one atomic reference written by the short-lived joiner thread and
cleared by whichever thread lets the connection go (§3.3's amendment of this date).

### 3.5 What all three share

- The tree is read from a snapshot, never from live widgets, on every platform.
- Every mutation enters through `Host#perform` and becomes a `Ui.post` that resolves its node and
  re-checks its preconditions on arrival, and that wakes the loop it may be standing inside (§1.9).
  No bridge holds the id-to-widget map, on any platform, because no bridge may read it.
- Every event is **handed over** on the UI thread immediately after the snapshot it refers to, and
  raised from a bounded queue — never raised inline, on any platform (§1.10).
- No bridge ever calls a `Widget`, `Scene` or `NativeWindow` getter from a foreign thread. The toolkit
  will not catch the mistake: thread confinement is enforced on property setters, tree mutation, focus
  moves and overlay pushes, and **not** on getters, `layoutBox`, `measure`, `invalidate`,
  `revealInView`, `requestRender` or the observer registrars. A bridge that reads live state
  off-thread gets a silent data race, not an exception, so the discipline has to be the bridge's own
  and §13 keeps it on the risk list.

---

## 4. Where a neutral abstraction would force a bridge to lie

Each item is a place where a tidier model would compile, ship, and be misread by a screen reader.

1. **Checked-ness has three homes** — a UIA pattern, an AT-SPI2 state bit plus an action row, an
   AppKit value. *Answer: `ToggleFacet` is the source; all three views are derived from it.*
2. **A value can have two forms and both are wanted at once.** A spinner in time mode holds 450 and
   displays `07:30`; UI Automation wants `IRangeValueProvider::get_Value` to say 450 *and*
   `IValueProvider::get_Value` to say `07:30`, from the same element. *Answer: `ValueFacet` carries the
   number, the range, the step and the formatted text, the last resolved under the node's locale.*
3. **A name without provenance is a coin flip on macOS** (Finding 5). *Answer: `nameFrom`, and the
   bridge picks the attribute — never both.*
4. **Text offsets have no universal unit** (Finding 10). *Answer: the facet is UTF-16, matching both
   `TextEditModel` and `NSString` exactly, and the AT-SPI bridge converts at that one boundary, with a
   test over astral-plane text.*
5. **A caret is not an index.** `TextEditModel#caret()` is a `(charIndex, Affinity)` pair because an
   index on a direction boundary is two points on the line. *Answer: `TextFacet` carries the affinity,
   and each bridge drops it only where the platform has nowhere to put it.*
6. **Screen bounds have three frames** — physical pixels top-left, points bottom-left, pixels top-left
   or window-relative on request. *Answer: publish scene points plus the window stamp, and convert once
   per bridge (§1.8) — which on macOS turns out to mean converting into the content view's space and
   letting AppKit finish, because it demonstrably does.*
7. **Sibling navigation is a first-class operation on Windows.** *Answer: the snapshot stores links
   (§1.4).*
8. **Identity is the platform's, not ours**, and a widget-keyed model breaks the moment `ListView`
   recycles a cell — while a *published*-parent-keyed model breaks the moment any ancestor stops
   being transparent. *Answer: `(owner id, local key)` over the **widget** tree, minted once, stable
   across frames and across every transparency verdict (§1.3).*
9. **Linux wants the whole tree in one message**, measured at 46 round trips for two objects without
   it. A pull-per-property model is not slow, it is unusable. *Answer: an array-backed snapshot the
   bridge marshals in one pass.*
10. **Modality is not a window property**, and it is not only an actuation question either.
    `isModalBlocked()` is false for the host of an in-scene modal by construction, and a background
    published as enabled invites a user into an interface that will refuse them. *Answer: the modal is
    a node with `MODAL` state, everything outside `inputRoot()` publishes without `ENABLED` and
    without `FOCUSABLE` (§1.13), and the action gate is still two tests (§1.9).*
11. **Popups mount two ways and pretending otherwise breaks Windows**, because GLFW creates popups
    with no owner HWND. *Answer: §1.11 — separate top-level or subtree, with `POPUP_FOR` and
    `CONTROLLER_FOR` carrying the logical link in both, and `Navigate(Parent)` answering `NULL`.*
12. **Actions and setters are different things on Linux**, because `GetActions` is a list of
    parameterless triples. *Answer: the parameterless verbs are published in `ActionFacet`; a
    parameterised setter is an `Accessible.Action` constant carrying an `Accessible.Argument`,
    dispatched through the same `Host#perform` and **not** published in that list. A facet is an
    immutable record with no widget and no callback in it, so nothing is ever a method on one; what
    advertises a setter is the facet's presence — a `ValueFacet` is settable, a `TextFacet` without
    `READ_ONLY` is editable — which is the question `SetValue`, `Value.CurrentValue` and
    `setAccessibilityValue:` actually ask (§1.9).*
13. **Announcements have a politeness** and it decides whether a user is interrupted. *Answer:
    `Politeness`, not a boolean.*
14. **Language is per node, not per process** — `Accessible.Locale`, `Culture`. *Answer: the node
    carries its resolved locale, and its name was resolved under it.*
15. **Offscreen is not invisible.** *Answer: both `VISIBLE` and `SHOWING`, from `isVisible()` and
    `isShowing()`.*
16. **Enabled is not read-only.** *Answer: separate bits, never derived from one another.*
17. **Hit testing must find disabled nodes**, and `Widget#hitTest` refuses a disabled subtree at every
    level. *Answer: the bridges walk the snapshot's bounds and never delegate to `hitTest`.*
18. **Reading order is not visual order**, because after layout bounds are physical in both directions.
    *Answer: tree order, which is paint order, which is `focusTraverse` order — and §12.1 asserts the
    equality.*

### 4.1 What the neutral model gives up, stated here rather than discovered later

- **UI Automation's `IItemContainerProvider` and `IVirtualizedItemProvider`.** Minting identifiers on
  demand serves `Navigate` correctly but cannot serve `FindItemByProperty` over unrealized rows, so a
  client searching a very long list walks it.
- **AT-SPI2's `Collection` interface**, which Orca uses for bulk queries and will fall back from —
  correct, and slower.
- **The `Table` interfaces.** No table widget exists, and defining a facet before there is something to
  describe would be guessing. A chart's data is what actually wants one (§11).
- **Two-dimensional values.** The colour picker's saturation-and-value field genuinely has one, and
  neither this model nor any of the three platforms can carry it; it is a `CANVAS` with a described
  value.

---

## 5. The backend SPI

### 5.1 `NativeWindow` gains one `default` member

```java
/** The platform accessibility bridge for this window, or {@link AccessibilityBridge#NONE}. */
default AccessibilityBridge accessibility() {
    return AccessibilityBridge.NONE;
}
```

`default` here is a decision on the merits, not a compatibility dodge — with no known consumer an
abstract member would be a mechanical edit to three test doubles and the backend, and no source
outside this repository would notice either way. It is `default` because
`NONE` is the *correct* behaviour for a window that has no accessibility: a test double, an embedded
surface, and any future backend on its first day all want exactly zero cost and no code, and getting
that by writing nothing is better than getting it by writing a stub in each.

### 5.2 `limn.backend.AccessibilityBridge`

```java
public interface AccessibilityBridge {

    AccessibilityBridge NONE = new AccessibilityBridge() { };

    /** Whether any assistive technology is listening. Called at most once per frame. */
    default boolean isListening() { return false; }

    /**
     * Whether this bridge needs one tree on the scene's first frame even though nothing is
     * listening yet, because its own listening gate cannot open until it has elements to
     * offer. True on macOS (§2.2) and, since 2026-09-05, on Windows; false on Linux, which can
     * ask its desktop whether anything is reading and so pays no walk for a window an assistive
     * technology never touches.
     */
    default boolean needsPrimingPublish() { return false; }

    /**
     * Whether this bridge is owed the window's own node the moment a scene binds, because it
     * can be asked whether this window has accessibility at all before the first frame runs.
     * True on Windows and nowhere else (§3.1, amended 2026-09-16): there the question arrives
     * as a message, and a window that answers "nothing here" is not asked a second time. What
     * it is owed is the window node alone — role, title, the size the window already has — and
     * never the scene, which at a bind has no boxes.
     */
    default boolean needsRootBeforeTheFirstFrame() { return false; }

    /**
     * Hands over a fresh whole-window snapshot. UI thread.
     *
     * <p>{@code reentrant} is true when the scene built this tree from inside the platform's
     * own callback, with the platform on the stack holding elements this bridge vended:
     * {@code WM_GETOBJECT} on Windows, an AX callback on macOS. A reentrant publish stores the
     * tree and answers from it, and must <b>not</b> destroy or release a registry entry, must
     * not re-push the root's children, and must not drain the event queue (§5.3). Everything it
     * defers is owed by the next ordinary frame, which the scene has already asked for.
     */
    default void publish(AccessibleTree tree, boolean reentrant) { }

    /**
     * One coalesced event, naming a node in the currently published tree. UI thread, and
     * enqueued rather than raised (§1.10).
     */
    default void emit(AccessibleEvent event) { }

    /**
     * Hands the bridge the scene-side half, replacing any host it already held: a scene
     * can be bound over a live window, and the outgoing one never learns it was (§5.3).
     *
     * <p>This is where the window pair is raised, because the caller cannot raise it: an
     * implementation empties its element registry and raises {@code WINDOW_CLOSED} for the
     * host it is replacing, if there was one, then raises {@code WINDOW_OPENED} for the
     * incoming one. On a first attach only the second is raised.
     */
    default void attach(Host host) { }

    /** The window is going away: raises {@code WINDOW_CLOSED}, then empties (§5.3). */
    default void detach() { }

    /**
     * The frame's accessibility step is over: everything this frame had to say was emitted.
     * UI thread; run however the step returned, and never from {@code republishNow()}.
     *
     * <p>Added 2026-09-15 (§5.3). It is the publish boundary, which is more than a drain
     * point: macOS posts its frame's notifications here, Linux flushes an owed
     * re-announcement here (§2.3), and Windows hands the boundary into its own queue behind
     * that frame's events (§2.4). The no-op default is for a bridge with neither obligation.
     */
    default void frameEnded() { }

    /** What the scene gives a bridge: the three ways to ask for a tree, and the one way to act. */
    interface Host {

        /**
         * Asks for a fresh tree on the next frame, and buys the frame. Safe from any thread;
         * for a client attaching while the scene is idle.
         */
        void requestRepublish();

        /**
         * Asks for the published tree to be re-stamped with the window's current origin,
         * scale and positioning support, and buys the frame. Nothing in the tree changed;
         * where it is on the screen did. Safe from any thread.
         */
        void requestRestamp();

        /**
         * Rebuilds and publishes now, and returns the tree. UI thread only, and only from
         * inside the platform's own pump: {@code WM_GETOBJECT} on Windows, an AX callback on
         * macOS. Walks and describes; never lays out, never renders. Publishes reentrantly, so
         * the bridge defers every registry obligation to the next frame (§5.3) — and therefore
         * <b>requests a frame whenever it published anything</b>, whatever the layout was
         * doing, because a deferred obligation needs a frame that is going to happen. It
         * requests one for the never-laid-out case below too. The only call that requests
         * nothing is the one that published nothing because nothing was dirty, and that call
         * defers nothing either. Returns the currently published tree unchanged — which on a
         * scene that has never laid out is the empty tree, or, for a bridge that asked for a
         * root before the first frame, the window's own node (2026-09-16) — rather than
         * describing geometry that does not exist yet.
         */
        AccessibleTree republishNow();

        /**
         * Performs one action on one node. Safe from any thread, and the only path there is
         * from a platform into toolkit state (§1.9). Returns whether the action was
         * <b>accepted</b> — the id was in the published tree and a task was posted — never
         * whether it is done: every real precondition is re-checked on the UI thread when
         * the task arrives.
         */
        boolean perform(long nodeId, Accessible.Action action, Accessible.Argument arg);
    }
}
```

Seven members on the bridge and four on the host — six until `frameEnded` joined them on 2026-09-15
(§5.3), which is the eleventh member of the seam counting `NONE` — and every bridge member is a
no-op or a constant on `NONE`, and none returns anything the toolkit has to interpret. Everything
platform-shaped stays behind it.

**`perform` is on the host and not on the bridge, and an earlier draft had it the other way round.**
That was not an infelicity, it was a path that does not exist: `perform` declared on the bridge is
implemented by the backend, and the backend cannot implement it, because the map from a node id to its
owning widget is the scene's and may be read only on the UI thread (§1.9) — so nothing in the toolkit
ever called it and nothing in a bridge could have answered it. Actions are half of what a screen
reader does with a tree, so the direction is stated once and holds everywhere: **inbound calls go
platform → bridge → host; outbound calls go scene → bridge.** A bridge still holds exactly one object,
and it is the host it was handed at `attach`.

`needsPrimingPublish` is the whole of the macOS and Windows asymmetry in §5.3's per-frame step, and
`needsRootBeforeTheFirstFrame` the whole of the Windows one at the bind itself: the scene asks each
once at bind and never has to know why the answer differs.

`requestRepublish` and `requestRestamp` are the two pieces of plumbing that have to obey ADR 023 by
hand: a posted task buys no frame, so each is `Ui.post(() -> { …flag…; scene.scheduleFrame(); })`.
Written the obvious way either would mark something dirty and then wait for a frame that never comes.

**They are two methods and not one because they set different flags, and the difference is the whole
saving.** `requestRepublish` sets the node flag, which costs the walk and the diff of §5.3.
`requestRestamp` sets only the header flag, which costs neither: the node array is republished
unchanged with a new stamp. A window drag would otherwise re-walk the entire tree on every callback
the compositor sends, to discover that nothing in it changed — the per-node bounds are scene-local
(§1.8) and a move does not touch one of them.

**And that saving is why both of them buy their frame through `Scene#scheduleFrame()` rather than
through `requestRender()`.** This is subtle enough that an earlier draft got it wrong in the one way
that makes the optimisation dead code: `Scene#requestRender()` is on §5.3's list of node-flag setters
— deliberately, because it is the public "something changed, repaint everything" call and an
application that mutates state and reaches for it must not go silent. So a `requestRestamp` written
as *set the header flag, then `requestRender()`* sets the node flag on its way out, and the walk it
exists to avoid runs anyway, every time. `scheduleFrame()` is the scene's frame primitive — it runs
the render requester and dirties nothing — and it is what these two, and `announce` (§1.5), must
call. The rule generalises: **inside the toolkit, buying a frame and declaring damage are separate
acts, and accessibility plumbing wants the first without the second.**

`republishNow` is what makes a first client answerable on Windows and a callback exact on macOS
(§3.1, §3.2). It checks the UI thread, contains crashes under `CrashPhase.ACCESSIBILITY`, returns the
current tree unchanged when nothing is dirty, and **requests a frame whenever it published
anything.**

**That last clause is the strong form, and the design needs the strong form rather than the narrower
one an earlier draft carried.** The narrow reading — a frame only when it published against a layout
that was already dirty — is the one a reader reaches for, because a stale box is the visible reason
to want another frame. But the reentrancy rule below depends on the strong one: a reentrant publish
stores the tree and **defers** the release sweep, the re-push of the root's children and the drain of
the event queue to the next ordinary frame, and on a window whose layout is perfectly clean — which
is the normal state while a screen reader reads a quiet interface (§6) — the narrow form leaves those
three obligations owed to a frame that never comes. So the frame is bought by the act of publishing,
not by the state of the layout. The never-laid-out call requests one too, because it exists precisely
to make the real tree arrive. The only call that requests nothing is the one that published nothing
because nothing was dirty, and it defers nothing that a frame would have to pay.

It is on the host and not on the bridge because only the scene can build a
tree, and it is a separate method from `requestRepublish` because the two differ in exactly the way
that matters: one of them is allowed to answer the platform *now*, and the other is the honest answer
everywhere else.

**It has one further precondition, and it closes a hole an earlier draft shipped: the scene must have
laid out at least once.** `Scene#bind` wires input, the frame callback and the render requester, and
it does **not** lay out: `layoutDirty` starts `true`, `width` and `height` start at zero, and
`layoutPass` runs only from the frame path. So a tree built at bind time is a tree whose every box is
zero-sized at the origin, and on macOS — where the first build was pushed onto the content view — it
was also a tree that no later publish replaced (§2.2). A screen reader attaching in that window would
have found a window whose contents are all a zero-size rectangle in the corner, which is worse than
finding nothing, because it looks like an answer.

So `republishNow()` asks whether the scene has ever laid out. If it has not, it walks nothing,
publishes nothing, requests a frame and returns the empty tree — there is no truthful thing to say
about a window whose widgets have no boxes. If layout has merely gone *dirty* since the last pass, it
publishes against the last settled boxes and requests a frame, as it already did: stale boxes from a
real layout are a bounded error that the next frame corrects, and zeros are not an error at all,
they are a fabrication. ~~On Windows the distinction costs nothing, because a window that a client can
attach to is a window that has painted, and a window that has painted has laid out. It bites only in
the interval between `bind` and the first frame, which is exactly where the macOS push used to be.~~
**Measured false on 2026-09-16, and it is the interval that cost the reader.** A client does not wait
for a window to paint: NVDA's `WM_GETOBJECT` reaches the window 36–116 ms after the bridge goes in
front of the window procedure, and the first frame publishes 305–523 ms after that — so the interval
between `bind` and the first frame is not where the distinction is cheap, it is where a client
decides, once and for the life of the window, whether to listen at all (§3.1). `republishNow()` still
answers no tree there, for the reason above, which is why the answer to the message cannot come from
it: `bind` publishes the window's own node instead, and that is the tree `republishNow()` then returns
unchanged.

### 5.3 How the scene feeds it

`Scene#bind(NativeWindow)` reads `window.accessibility()` once and calls `bridge.attach(host)`.
`Scene#observeWindowClosed` calls `detach()` — that observer list is the right hook because the
window's own input and frame-callback slots are single-occupancy and `bind` already takes them.

**The window pair is raised inside `attach` and `detach`, and not by their callers.** Every other
event in this record is handed to the bridge by the scene through `emit`, so putting these two
somewhere else needs its reason stated: **only the bridge can know whether there was an outgoing
tree.** A scene bound over a live window never learns it was replaced (below), so the outgoing scene
cannot raise its own `WINDOW_CLOSED`, and the incoming scene cannot raise it either, because nothing
tells it that a previous host existed. The one object that holds that fact is the bridge, which is
also the object whose element registry the replacement invalidates — so the announcement lives beside
the sweep that makes it true. `attach` therefore raises `WINDOW_CLOSED` for any host it is replacing
and then `WINDOW_OPENED` for the incoming one; on a first bind there is nothing to close and it
raises only the second. `detach` raises `WINDOW_CLOSED` and empties. That is what keeps a rebind from
announcing one window twice, which is exactly what a `bind` that raised the event itself, on top of
an `attach` that had to, would do. *Raises* here means what §2.4's row means everywhere else — each
bridge posts its platform's event, or posts nothing where the platform already posts its own, which
on macOS is both of these (§2.2).

**`bind` can happen twice on one window, and `attach` therefore replaces rather than adds.** A scene
can be bound over a live window; `Scene`'s own javadoc says so where it explains why the global
metrics listener is weak — "a scene replaced on a live window (a new scene bound over it) never
receives `windowClosed`" — and this is not a hypothetical corner. `limn-demo`'s gallery harness does
it once per shot, binding a freshly built scene over the same window shot after shot, under a
comment that already names the shape of the hazard: "bind installs a frame callback of its own, and
a callback set only at start-up is silently replaced by the first bind." That harness is the one
`AccessibleGalleryTest` is modelled on, so the case is not merely reachable, it is on the path this
ADR's widest test walks. So the outgoing scene never reaches `observeWindowClosed`, never calls
`detach()`, and the window's bridge — which is the same object either way — would end up holding two
hosts and having announced one window twice. `attach(Host)` is therefore specified as *replacing* any
host the bridge already had: it raises `WINDOW_CLOSED` for the outgoing tree, drops that host, and
raises `WINDOW_OPENED` for the incoming one. To a screen reader a new tree in the same window is a
whole-window structure change in any case, so nothing subtler is worth building. This is the second
half of ADR 040 §6.4 (§9), and it is the half that is a defect rather than a gap.

**Amended 2026-09-16: `bind` publishes one node on Windows, and only there.** Everything below still
holds — `attach` itself builds and pushes nothing, on any platform, and the priming publish is still
the first frame's — but between the two there is now a publish of the window's own node, for a bridge
that answers `needsRootBeforeTheFirstFrame()`. It is not a walk: no widget is described, because none
has a box yet, and what it carries is what the window can say about itself before it has ever laid
out. §3.1 has the measurement that made it necessary and the count of runs it decided; §5.3's step is
unchanged, and the first frame publishes the scene exactly as it did before.

**`attach` builds and pushes no tree, on any platform.** It takes the host, raises the window pair
above, empties the registry if it was replacing a live host — and stops there. An earlier draft had
the macOS bridge build and push its first tree here, and §5.2 explains why that could not work: at
`bind` the scene has never laid out. So `attach` is uniform — every bridge stores the host, nobody
has asked yet, and `isListening()` is false until they do — and the macOS push moves to the
**first frame**,
where the boxes are real. `bind` asks `bridge.needsPrimingPublish()` once and remembers the answer;
when it is yes the scene owes a *priming* publish, which the step below runs on its next frame
whatever the gate says. It is the same single walk the bind-time build was, paid at the same point in
the window's life, against geometry that exists — and it is asked for by the one bridge that needs
it rather than imposed on all three.

Per frame, in `renderFrameImpl`, immediately after `layoutPass` and the hover update and before the
paint passes, the scene runs one step:

0. **Drain the announcement queue** to the bridge, and only then consider returning. This is above
   the re-present guard and above both flags on purpose (§1.10): an announcement is the application
   speaking, not a property of a node, and a frame that changes nothing in the tree must still carry
   it. With nothing listening the drain discards, which is what the queue's bound means.
1. If this is a **re-present** frame, return. `renderFrameImpl` takes a `rePresent` flag whose whole
   contract is to redraw the same pixels into the other buffer; `tickAnimations`, the hover update,
   `updateModalScrim` and `updateTooltipFade` are all already skipped there, and a walk that can only
   ever conclude "nothing changed" belongs in the same list.
2. `live = bridge.isListening()`; if false **and no priming publish is owed**, return. One virtual
   call, and `NONE` returns a constant. A priming publish is owed only on the first frame after a
   bind and only when `bridge.needsPrimingPublish()` said so at bind — which is macOS and, since
   2026-09-05, Windows, both for the same reason: their honest gate is "someone has asked", and it
   cannot open before the platform has been handed something to ask about. Linux answers `false`,
   because its desktop can be asked directly, so §6's promise that it pays nothing for an untouched
   window is exact there. (Until 2026-09-16 this line read "macOS and nothing else", which Windows'
   `true` had already made wrong.)
3. If both accessibility flags are clear and nothing is owed, return.
4. If only the **header** flag is set, take the re-stamp path below and return.
5. Walk `root()` and the overlay stack into the **reused scratch buffer**, with one reused
   `Accessibility` builder, comparing each field against the published snapshot as it is written and
   carrying over a resolved name whose `I18nString`, locale and `I18n.epoch()` all match. The
   enabled, visible and modal flags are carried down this walk (§1.2, §1.13). The window's origin,
   scale factor, absolute-positioning flag and the scene's height are captured here, because only
   this thread may ask for them.
6. If nothing differed, clear both flags and return: no snapshot, no publish, no events.
7. Otherwise copy the scratch into an immutable tree — materialising the facet records here, and only
   here (§1.1) — turn the differences already found into the event list,
   `bridge.publish(tree, false)`, `bridge.emit(…)` per event, clear both flags and clear the priming
   debt. This is also where a bridge that keeps platform objects re-pushes the top of its tree and
   where its drain has a fresh tree to sweep against.

`republishNow()` (§5.2) is steps 5 to 7 with the rest skipped and with `bridge.publish(tree, true)`,
on a thread the platform gave us inside its own pump, subject to the never-laid-out precondition §5.2
states and to the reentrancy rule below.

**A bridge that keeps platform objects owes three things beyond storing the tree, and each has a
thread and a moment.** They are stated here rather than left to each bridge to rediscover, and each
names its owner from §3.4's table:

- **Release what went away**, on the thread that owns removal: the drain thread on Windows, the UI
  thread on macOS. Ordinarily the per-node `NODE_DESTROYED` events name exactly what went away and
  the drain releases each as it raises it. After a collapse they do not, so the drain sweeps instead:
  every registry entry whose id is absent from the currently published tree is destroyed in the
  platform's idiom (§1.10). The sweep is not a per-publish cost and does not belong in the frame
  step, which is where an earlier draft put it — two threads removing from one map, one of them
  holding no element to raise from.
- **Re-push the top of the tree when it changed**, on the UI thread, in the frame step, and never
  from a reentrant publish. On macOS the root's children are pushed onto the content view with
  `setAccessibilityChildren:`, and that array is *not* self-updating: pushing it
  once at the first publish and never again freezes the top of the tree at whatever the interface
  looked like then, while the published root's children genuinely change — an overlay opening is
  exactly that, and a modal dialog appearing is the case a screen reader user most needs to hear
  about. So the bridge compares the published root's child list with the one it last pushed and
  re-pushes when they differ. It is a handful of elements and a reference comparison per publish.
- **Empty on replacement**, on the UI thread with the drain stopped. `attach(Host)` replacing a
  live host, and `detach()`, both invalidate every element the bridge holds. Both run the same
  sweep — destroy and release everything in the registry, then start empty — because a rebind that
  raised `WINDOW_CLOSED` and left the old elements alive would leak the whole previous tree and leave
  a client holding elements that resolve against ids the new tree may reuse for something else. On
  Windows the drain thread is stopped and joined first, so the one thread that also removes is not
  running while this one empties the map (§3.4).

**None of the three may run from inside a reentrant publish, and that is a correctness rule rather
than a preference.** `republishNow()` is called from inside the platform's own callback with the
platform on the stack: `WM_GETOBJECT` on Windows, and on macOS an AX callback that is walking
elements this bridge vended and that AppKit is holding. A publish there that ran the release sweep
would destroy and release the very elements the caller is standing on — a message sent to a freed
`NSAccessibilityElement` after the callback returns, which is the crash §13.20 already names, arrived
at from inside our own code instead of from a client. A publish there that re-pushed
`setAccessibilityChildren:` would replace the array AppKit is in the middle of reading. And a publish
there that drained the queue would post notifications from inside a notification-delivering callback.
So `publish(tree, reentrant = true)` **stores the tree and answers from it, and does nothing else**:
new nodes get elements lazily on the very pulls the caller is about to make, and elements for nodes
that went away stay alive and answer as stale until the next ordinary frame — which is exactly what
§1.3 already promises a client holding an element that left the tree, and which the scene has already
asked for, because `republishNow()` requests a frame whenever it published anything. On Windows the
rule costs nothing at all: the reentrant publish there is the *first* one, and there is nothing to
sweep, re-push or drain.

**A window move takes the shorter path.** Only the header changed, so the scene re-stamps the previous
node array with the new origin and factor, publishes that, and emits one window-level
`BOUNDS_CHANGED`. No walk, no diff, and the node ids are the ones the client is holding.

**Amended 2026-09-15 (phase 3 fix round, brief item 5): the re-stamp path carries the reentrant flag
too.** The re-stamp is reached from both callers of the publishing half — step 4 of the frame's step,
and `Host#republishNow`, which takes it whenever the header flag alone is set — and it published with
`reentrant = false` on both. On the second that is wrong, and wrong in exactly the way this section
spends a paragraph forbidding: `republishNow` is called from inside the platform's own pump, its
contract above says in so many words that it "publishes reentrantly, so the bridge defers every
registry obligation", and its walk branch beside the re-stamp does pass `true`. A bridge told
`false` there may destroy elements the caller is standing on, re-push the top of its tree and drain
its queue from inside a notification-delivering callback — the three things the reentrant rule exists
to stop. The flag a re-stamp passes is now its caller's: `true` from `republishNow`, `false` from the
frame. The parameter never meant "something changed": a re-stamp that changed nothing returns before
the publish, and a bridge is told what it may touch rather than what moved.
`AccessibleLifecycleTest.aReentrantRestampIsHandedOverAsReentrantAsAReentrantWalk` and
`theFramesRestampIsNotReentrant` hold the two directions.

**Amended 2026-09-15 (phase 3, Windows; the facts above that phase 3 moved for this bridge).** Three
sentences of this section now read differently on Windows. *"After a collapse … the drain sweeps"*:
the drain sweeps after its own queue's collapse **and** after the model's `INVALIDATED` (node `0`),
which is the same loss of per-node events, and after either it re-raises the focus on the tree's
effective focus (§2.4's amendments of the same day). *"Empty on replacement … the drain thread is
stopped and joined first"*: still so, and the empty also holds the bridge's vend guard, which another
window's bridge takes to raise on, or hand over, one of this bridge's elements (a cursor followed into
a native popup, decision 5; §3.4 as amended). *"A window move … emits one window-level
`BOUNDS_CHANGED`"*: the scene still emits it, and the Windows bridge raises nothing for it, per node
or in bulk, and says so in its trace (§2.4's `BOUNDS_CHANGED` amendment: NVDA 2024.4.2 subscribes to
no bounds change, and every raise waits for the reader).

The **node** flag is set by:

- **`Scene#damageWidget`, `#damageWidgetRegion`, `#damage(Rect)`, `#requestRender()` and
  `#markContainedLayout`** — the funnels `Widget#invalidate()` goes through, and therefore the funnel
  behind every value, state, text, caret and selection change in the toolkit (Finding 6).
  `requestRender()` is in that list because it is the public "something changed, repaint everything"
  call, and an application that mutates state and reaches for it rather than for `invalidate()` must
  not go silent. This bullet is the one that matters, and it is coarse on purpose: step 5 is what
  keeps it honest.
- **The structural funnels that do not go through those entry points**: `Widget#setSceneRecursively`
  for attach and detach, `Scene#setFocus`, `Scene#pushOverlay` and `#removeOverlay`,
  `Scene#markLayoutDirty`, `Scene#onWidgetDetached`.
- **`Scene#layoutPass` itself, on the branch that runs a full pass.** **Corrected while
  implementing:** a window resize reaches neither list above — its input arm marks the layout dirty
  and schedules a frame without declaring damage, since a layout frame damages everything
  structurally — and a headless embedder calling `layoutPass` with a new size reaches nothing at all.
  Both moved every box a reader was holding and published none of them until an unrelated repaint
  set the flag. A resize is a walk and a diff, not a move's re-stamp: the boxes are scene-local and
  every one of them changed, so it arrives as `BOUNDS_CHANGED` — per node under the budget, one
  window-level event past it — with no `STRUCTURE_CHANGED` and no `NODE_DESTROYED`.
- **`Widget#invalidateAccessible()`**, for a change that is neither painted nor structural. **It is
  the one flag-setter in this list that also has to buy the frame that reads the flag**, because it
  is the one whose callers reach no other funnel: every entry point above is on the path of something
  that was already going to produce a frame — a repaint, a layout, a focus move, an overlay push —
  and this one is defined by not being. It sets the flag unconditionally and calls
  `Scene#scheduleFrame()` when a bridge is attached and listening (§1.5, §8), which is the same
  bargain `Scene#announce` and `Host#requestRestamp` strike: flag always, frame only when someone is
  there to read it. `Widget#setTooltip` gains the call, because today it neither invalidates nor
  relayouts and a description change would otherwise be silent. **`Widget#setFocusable` gains it
  too**, and it is the sharper case: the setter writes its field and does nothing else — no
  invalidate, no layout mark, no structural funnel — so it reaches none of the entry points above,
  and `FOCUSABLE` is both a published state and the bit that decides §1.6's transparency predicate.
  Without the call, making a scaffold widget focusable changes the tree's *shape* and raises nothing,
  and `AccessibleIdentityTest`'s second case cannot pass (§8, §12.1).

The **header** flag is set by `Host#requestRestamp()`, and the one caller that matters is the window
position callback. `LwjglWindow#windowMovedTo` today updates two fields and moves child popups and
requests nothing (Finding 13); it gains that call when a bridge is attached and listening, which is
what makes the shorter path run at all. The content-scale callback beside it already requests a frame
and sets the node flag instead, because a scale change can move text metrics and therefore boxes.

Hover deserves one sentence, because it is easy to miss: on a content frame with the pointer inside
and no button down, hover is recomputed from the pointer position, so a hover-derived state can change
with no pointer event at all. The publish step runs after that recomputation precisely so that it sees
the settled answer.

#### Amendment 2026-09-15 — every frame ends, and the end is where macOS posts

**What was wrong (MACOS-NEW-8).** The step above gave a bridge two moments, `publish` and `emit`,
and both happen only on a frame whose walk found a difference (step 6) or that re-stamped (step 4).
A bridge that posts on the user-interface thread therefore had no moment after a frame's events at
all: the macOS bridge drained at the top of the next publish, which is the next *change*. Two
consequences followed. Every notification was one change late (§1.10's amendment of this date).
And the reentrancy paragraph's "owed to the next ordinary frame" did not hold: `republishNow()`
clears the node flag it walked for, so the frame it asks for finds nothing dirty, returns at step 3,
and the deferred re-push, boxes and drain waited for an unrelated change.

**The rule.** `AccessibilityBridge` gains an eleventh member, with a no-op default (folded into the
§5.2 listing, which predated it):

```java
    /** The frame's accessibility step is over: everything this frame had to say was emitted. */
    default void frameEnded() { }
```

and the step gains a last line, run however steps 0 to 7 returned — after a re-present, with nothing
listening, with a clean tree, after a re-stamp, after a publish:

8. `bridge.frameEnded()`. One virtual call. Never from `republishNow()`.

**What the default buys is a bridge with neither of the two obligations** — and that is narrower
than this record first said. It read "`NONE` and every bridge that raises on a thread of its own
inherit the no-op" until 2026-09-16 (fix round 3b), which is no longer true of any of the three. A
bridge that raises elsewhere still *posts* nothing here, but the frame's end is also the **publish
boundary** the re-announcement of §2.4 is flushed at, and that is a fact a thread of the bridge's
own cannot see: Linux reads the marker on this thread (§2.3), and Windows hands it into its own
queue behind that frame's events (§2.4). So all three override it, each for its own reason, and the
default stands for a fourth bridge that owes neither.

On macOS `frameEnded` first pays what a reentrant publish deferred (the re-push of the root's
children and the boxes), then drains the queue: posts, the collapse's sweep, and — because the
sweep forgets what was pushed — the root's re-push at once rather than at the next publish. A frame
end with nothing queued and nothing deferred returns after one comparison and allocates nothing.
`publish` keeps the re-push and the boxes, so an element a notification names exists when it goes
out. Read "the next ordinary frame" in the reentrancy paragraph above, in §5.2's `publish` javadoc
and in §12.1's `AccessibleReentrancyTest` row as "the end of the next frame, whether or not it
publishes". `INVOKED`, emitted by a performed action outside any frame, is drained at the end of the
next frame; macOS posts nothing for it (§2.4).

Proven by `AxSceneTimingTest` (a real `Scene` over the platform's bridge with the platform left out:
a focus move posted after the frame emitted it, an announcement on a still window drained in its own
frame, and a reentrant publish's event posted by a frame that walked nothing), each red on the
earlier code, and `AxBridgeTest.aFrameEndWithNothingToSayAllocatesNothing`. The live check — an
`AXObserver` timestamping deliveries against a reader scene's step lines on the macOS guest — is
phase 5's.

### 5.4 Popups and dialogs, per platform

| | Windows | macOS | Linux (X11) | Linux (Wayland) |
| --- | --- | --- | --- | --- |
| combo list | own HWND, own tree, own fragment root; `POPUP_FOR` back to the combo | own `NSWindow`, own tree; `POPUP_FOR` | own `frame` under the one application object; `POPUP_FOR` | in-scene overlay: a subtree of the owner's tree |
| menu cascade | own HWND, focus-stealing, own tree | own window; in-scene under exclusive fullscreen | own `frame` | in-scene overlay |
| dialog, native | own HWND, `WindowFacet` with `MODAL` | own window, `AXModal` | own `frame`, `STATE_MODAL` | native here too — a window that is genuinely a window is unaffected |
| dialog, in scene | overlay subtree, `MODAL`, `UIA_IsDialogPropertyId`, **no `WindowFacet`** | same | same | same |
| tooltip | not published in the first cut (§11) | | | |

In every native mounting the popup's contents are described by the widget that draws them, in the
popup's own scene, so their bounds are measured against the window they are actually in (§1.11).

---

## 6. What this costs when nothing is listening

The promise is zero: no per-frame allocation and no tree walk until a bridge is attached **and** the
platform says someone is listening. Here is exactly what runs in each state.

**No bridge at all** — headless tests, `StubWindow`, `RecordingWindow`, any backend whose window
returns `NONE`. Per frame: one virtual call to `isListening()` on a constant object, returning `false`
from a `default` method. Nothing else — no walk, no builder, no map, no allocation. Per bind: two
further calls, `needsPrimingPublish()` and (since 2026-09-16) `needsRootBeforeTheFirstFrame()`, which
`NONE` also answers from `default`s, and which therefore never cause a walk. Per widget: one nullable reference field, never read.

**A bridge attached, nobody listening** — a Windows machine with no assistive technology running, a
GNOME session with accessibility off, a macOS process no client has queried. Per frame: one
`isListening()` call that reaches the platform, and then nothing.

- **Windows:** `UiaClientsAreListening()` for its negative only — false means nobody in the session
  listens to anything — and, when it is true, this window's own two facts: a client's event
  subscription covering it, told through `IRawElementProviderAdviseEvents` on the root, or a client
  having asked for it through `WM_GETOBJECT`, which is owed one event and then a short window
  (§13.5, measured 2026-09-07). One native call per frame per window, plus two volatile reads.
- **macOS:** a boolean the bridge sets the first time any implementation on its own element class is
  entered — `accessibilityChildren`, `accessibilityTitle`, `accessibilityRole`, any of them. There is
  no equivalent of `UiaClientsAreListening` — `AXIsProcessTrusted()` answers whether *we* may act as a
  client, which is a different question — so "someone has asked" is the honest gate. **This platform
  pays something before the gate opens** — as, since 2026-09-05, does Windows, whose per-window gate
  has the same shape; the sentence here read "this platform alone" until 2026-09-16 — one tree walk
  on the scene's **first frame**, to have elements to push onto the content view at all (§2.2). Not
  at bind, where the scene has never laid out and every box would be zero (§5.2). (The window's own
  node *is* published at bind on Windows, and only there: one node, no walk of the scene, for the
  message §3.1 describes.) After that walk and until a client touches one of those
  elements, a frame does nothing here either.
- **Linux:** `org.a11y.Status.IsEnabled` on the session bus, **watched** — a session-bus connection
  and one parked daemon thread per process from the first window's bridge on, however this bullet
  first read it (amended 2026-09-15, LINUX-NEW-7, below) — **and** a completed `Socket.Embed`. While
  accessibility is off, no accessibility-bus connection is opened. Once the switch has been on, this
  process stays embedded for its life (decision 67, below): the switch cannot say that a reader
  left.

#### Amendment 2026-09-15 — the Linux switch is watched, and that costs one parked thread per process

**What was wrong (LINUX-NEW-7).** The bullet above said "refreshed on `PropertiesChanged`"; the code
read `IsEnabled` once, when a window first asked for its bridge, on that thread, and answered `NONE`
for good when it was false — so an application started before Orca stayed unreadable, and one whose
reader quit kept its connection and its walks. "No thread is started" was true only because nothing
watched.

**The decision (29) and what it costs.** The switch is watched: **one session-bus connection and one
parked daemon thread per process** (`limn-a11y-atspi-status`), from the first window's bridge on,
**even when nothing is reading**. That thread is the whole idle cost on Linux beyond the per-frame
`isListening()`, which is one `volatile` read; it allocates nothing per frame and wakes only for a
message the bus routes to it, which is the switch's own announcement and nothing else. A process with
no session bus it can open gets no bridge and no thread. No accessibility-bus connection is opened
while the switch is off.

**What was read before it was built.** On Fedora KDE 44 (at-spi2-core 2.60.6, dbus-broker 37) and
Ubuntu 24.04 (at-spi2-core 2.52.0, dbus-daemon 1.14.10), 2026-09-15, with
`scripts/a11y/linux/read-a11y-status-signal.sh --flip`: at-spi-bus-launcher broadcasts
`org.freedesktop.DBus.Properties.PropertiesChanged` on `/org/a11y/bus` with
`("org.a11y.Status", {"IsEnabled": <b>}, [])` once per change and nothing for a value set again, and a
match on the well-known sender `org.a11y.Bus` receives it on both buses; the upstream function is
byte-identical in both versions. Not read: what toggles the switch when Orca starts and quits on each
desktop — the lab note of 2026-09-13 found it left on after a reader ran, so "tears down when Orca
exits" happens only where the desktop turns the switch off; phase 5 measures it.

**What the code does.** `AtspiStatusWatch` adds its match (`type='signal',sender='org.a11y.Bus',
path='/org/a11y/bus',interface='org.freedesktop.DBus.Properties',member='PropertiesChanged',
arg0='org.a11y.Status'`) **before** it reads the flag, so no change falls between the two; it follows
the signal on a connection with no threads of its own (`DBus.Conn.openOnThisThread`), and reopens a
lost connection after a back-off and reads the flag again. When the switch turns on, every attached
window — a scene bound while it was off included — is asked for a publish, which buys the frame an
idle window would never spend and joins; when it turns off, the application leaves the accessibility
bus and every window stops listening. §3.4's "listening gate" row for Linux is therefore written by
this status thread, not by the reader thread.

**Corrected 2026-09-15 (the linux-A review): the launcher is followed by its name, and nothing
polls.** Two sentences above were not true of the code. "Reopens a lost connection … so a restarted
launcher is followed" — the session connection does not end when at-spi-bus-launcher does, so a new
launcher's switch was never read. And "wakes only for a message the bus routes to it" — on a session
with no launcher the read failed, the connection was closed, and the watch reconnected on its back-off,
every sixty seconds for the life of the process. The watch now adds a second match **before** the
read, the bus's own `NameOwnerChanged` for `org.a11y.Bus`
(`type='signal',sender='org.freedesktop.DBus',path='/org/freedesktop/DBus',interface='org.freedesktop.DBus',member='NameOwnerChanged',arg0='org.a11y.Bus'`):
a new owner has its switch read again on the same connection; no owner turns the switch off; a read
answered with an error (no launcher) is off, and the thread parks on the connection until the name
gets an owner. The thread wakes for three things: the switch's announcement, its owner's arrival or
departure, and a stray call it answers. Only the session connection itself ending is retried after the
back-off. The signal's shape (sent by `org.freedesktop.DBus` from `/org/freedesktop/DBus`, signature
`sss`: name, old owner, new owner, empty for none) and its routing by `arg0` were read on both guests
(`readings/fedora-dbus-bus-facts.txt`, dbus-broker 37; `readings/ubuntu-dbus-bus-facts.txt`,
dbus-daemon 1.14.10; 2026-09-15, `scripts/a11y/linux/read-dbus-bus-facts.py`). Pinned by
`AtspiStatusWatchTest.aSessionWithNoLauncherParksOnItsConnectionAndFollowsTheLauncherByItsName`.

**Recorded 2026-09-15 (the linux-A review): "tears down with Orca" is not what this code can
deliver on either desktop read so far.** The bridge follows `IsEnabled` and nothing else. What was
read, without starting a reader (`scripts/a11y/linux/read-orca-switch-writes.sh`;
`readings/fedora-orca-switch-writes.txt`, Orca 50.2; `readings/ubuntu-orca-switch-writes.txt`, Orca
46.1): the only write either Orca makes to `org.a11y.Status` sets `IsEnabled` **true**, at start; no
path sets it false, and neither shutdown touches it. at-spi-bus-launcher clears nothing when the
screen reader is disabled (readings/upstream-at-spi-bus-launcher-2.52-2.60.txt). So once Orca has run,
the switch stays on until something else turns it off — the desktop's accessibility setting, or the
session ending — and the application stays joined and its scenes keep walking after Orca quits. The
embed half of decision 29 holds; the teardown half holds only for a switch turned off by the desktop.
**That question went to the owner and came back as decision 67, immediately below: the teardown half
is withdrawn.** Phase 5 therefore no longer measures what each desktop does to the switch when Orca
quits — the answer cannot change this bridge's behaviour.

#### Amendment 2026-09-15 — decision 67: once embedded, embedded for the life of the process

**The owner's answer to the question above: stay embedded, and say why.** The teardown half of
decision 29 is withdrawn. `AtspiApplication#enabled(boolean)` acts on the rising edge only — the
first `true` asks every window to publish and joins, and every `false` after it is ignored — so the
application keeps its connection, its frames and its walks until the process ends.

**The reading it rests on, from both guests, 2026-09-15**
(`scripts/a11y/linux/read-orca-switch-writes.sh`; `readings/fedora-orca-switch-writes.txt`, Orca 50.2
on Fedora KDE 44 with at-spi2-core 2.60.6; `readings/ubuntu-orca-switch-writes.txt`, Orca 46.1 on
Ubuntu 24.04 with at-spi2-core 2.52.0): the only write either Orca makes to `org.a11y.Status` sets
`IsEnabled` **true**, at start; neither shutdown path touches it, and at-spi-bus-launcher clears
nothing when the screen reader is disabled
(`readings/upstream-at-spi-bus-launcher-2.52-2.60.txt`). The bridge therefore cannot learn from this
switch that a reader has left, and the `false` it *can* receive means something else entirely — the
desktop's own accessibility setting turned off, or the bus's owner going away — which may happen
while a reader is still reading us on the connection the teardown would close. A teardown driven by
that flag is not "tears down with Orca"; it is "drops whoever is reading, for a reason unrelated to
them".

**The precedent: this is what GTK does.** `atk-bridge` is loaded once, when the toolkit sees the
switch on, and there is no path that unloads it or withdraws the application from the registry
because accessibility was switched off; a GTK window on either of these desktops stays readable for
its process's life. Limn now matches the platform's own behaviour rather than inventing a shutdown
no client expects.

**The idle cost this accepts**, beyond the parked status thread the amendment above measures: one
accessibility-bus connection with its reader and writer threads, kept for the life of the process
after the first `true`, and §5.3's walk on damaged frames for as long as the process lives. Both were
already the cost while a reader ran; what changes is that they are no longer given back when the
desktop's switch goes off. Nothing new is allocated per frame, and a process that never sees a `true`
still pays nothing at all.

**What the code does.** `AtspiApplication.enabled(boolean)` returns at once for a `false` and for a
`true` it has already seen; the joiner's "did the switch go off while I joined" check and the
back-off's went with it (the last window leaving still ends both, which is a different condition).
Pinned by `AtspiApplicationTest`'s
`theSwitchDecidesWhetherAWindowListensAndJoinsAndTurningItOnWakesEveryWindow` — the `false` leaves
the window listening and the application joined — and
`aSwitchTurnedOffWhileTheJoinRunsLeavesTheJoinAlone`.

**These are the gates, and the gate is never "a client asked us something recently."** A publish
conditioned on a recent inbound call inverts the contract on all three platforms: the platform events
are pushes a client waits on, and Orca in particular registers for `object:state-changed:focused` and
then calls nothing until it fires. Tab twice quickly with such a gate and the toolkit goes silent —
the client only asks after an event, and the event only fires after the client asks.

**A bridge attached, a client listening, nothing repainting** — the steady state while a screen
reader is running and the user is reading. There is no frame at all: the loop is parked in
`glfwWaitEvents` and no window has asked for one. Zero of everything.

**A bridge attached, a client listening, a frame that repainted and changed nothing accessible** —
a blinking caret, a hover ripple, a tween of something no facet reports. The flag rides damage
(§5.3), so this frame *does* reach the publish step: `isListening()`, then one walk into the scratch
buffer with a comparison per field, then nothing. No snapshot, no events, no allocation — names are
carried over by source identity rather than re-resolved, and the scratch arrays are the scene's and
are reused. This state is the price of hanging the flag on the funnel that catches everything, and it
is the state §12.1's `AccessiblePublishCostTest` has to pin: a repaint that changes no accessible
fact allocates zero bytes and publishes nothing.

**That last promise has exactly two preconditions, and they are §1.1's rules rather than hopes.** A
name is an `I18nString` the widget *holds*, compared by reference, locale and epoch, and resolved
through `I18nString#get()`, which caches its own resolution. And a **derived** string — a formatted
value, a password field's mask, a text field's contents — is handed over from the widget's own cache
together with the witness it was cached against, and is never built in the walk. A widget that calls
`I18nString#format` or `String.format` inside `onAccessibility` breaks this state and only this state:
everything it publishes stays correct, and the quiet frame starts allocating one string per such node
per damaged frame. Which is why `AccessiblePublishCostTest`'s scene carries a `Spinner` and a
`TextField` with a blinking caret rather than a screen of buttons.

**A bridge attached, a client listening, something actually changed** — the copy into the immutable
tree, sized to the node count, plus the events. This is the only state that allocates, and it happens
on frames where the interface changed, which is where an allocation is already expected. The sharp
edge, stated: dragging a slider with a screen reader watching rebuilds the tree once per frame. The
growth seam is per-node versioning and a partial patch; the first cut does not need it and should not
pay for it speculatively.

**A continuously-advancing value is the version of that edge nobody is dragging, and it needs a rule
rather than an exception.** `VideoView`'s position advances every frame of playback: published raw,
it makes the diff find a change on essentially every frame, and a whole tree copy per frame, for an
hour, with the user doing nothing at all. Nobody is holding a control — the interface is simply
playing — so none of the "an allocation is expected where the interface changed" argument applies.
The rule is: **a value that advances on its own is published at the resolution a user can act on, and
the widget does the rounding.** `VideoView` writes whole seconds and the `mm:ss` text the transport
already shows, so playback costs one tree copy a second rather than sixty; `ProgressBar`'s
determinate value rounds the same way. This needs no new API and no new facet parameter, because the
decision belongs where the knowledge is — a widget knows what its value means and the diff does not.

What that does **not** buy back is the walk. A playing video damages itself every frame, so the
publish step runs every frame and compares every node to conclude that one rounded value did not
move. Only the copy and the events are saved. That is the honest shape of the cost and it is
acceptable: the walk is bounded by the node count and allocates nothing, which is the state above
this one.

**The mechanism that keeps the flag honest.** The dirty flag is a plain boolean store from funnels that
already run, so it costs one store whether or not anything is listening. That is deliberate:
maintaining it unconditionally means switching a bridge on mid-session needs no audit of what was
missed, and one store is below the resolution of every allocation and frame test in the suite — which
`AccessibleIdleCostTest` measures rather than claims, at a thousand stores before one frame (§12.1,
§13.7). The walk it triggers, by contrast, is paid only when something is
listening, which is why the flag is cheap and the comparison is where the care goes.

**The one unconditional platform cost is the Windows WndProc subclass**, which makes every window
message cross a Java frame that compares an int and forwards. The spike saw no perceptible latency and
did not measure it; the benchmark did, on the Windows 11 guest on 2026-09-07 (§13.5), and the subclass
alone sits inside the run-to-run noise of the 750-widget form — 0.27 ms against 0.21 ms per animated
frame, idle CPU identical — so it stays unconditional, and the fallback that would have polled
`UiaClientsAreListening()` and installed it only once seen true is not taken: its race, a client
touching the window in the instant the flag flips and missing its first `WM_GETOBJECT`, buys nothing.
**What is not free on that machine is the gate itself.** `UiaClientsAreListening()` answers true with no
reader running whenever any process in the session holds a UI Automation event handler — seventeen
did, the virtualization tools among them — and a true gate buys the walk on every damaged frame:
+0.6 ms per animated frame and +1.3 MB on the form above, with nothing listening that a person could
hear. That is the "listening, nothing changed" state, paid by a machine nobody is reading; the walk is
bounded and allocation-free exactly as this section says, but the honest gate on Windows is weaker
than this section assumed, and the state it was written for is the rare one there.

**Announcements are the exception to "nothing when nothing listens."** `Scene#announce` allocates a
queue entry whether or not anyone is listening, because dropping it silently would make the
application's diagnostics depend on the reader's presence. It is bounded — one entry per call, drained
each frame at the top of the publish step and discarded there when nothing is listening — and an
application calling it per frame is a defect the slow-task budget already catches. It buys a frame
only when a bridge is attached and listening (§1.5), so an application that announces into an
unobserved process spends no frames at all.

---

## 7. Every widget, and what it becomes — a survey, not a specification

**Read this before the table, because the table has been mistaken for something it is not.** What
follows was swept from `limn/components`, `limn/components/chart` and `limn/scene/layout`, not from a
list, and it is written **from the outside**: from each widget's public shape, its documentation and
what it visibly does, not from a reading of its paint and hit-test code. That is the right way to
produce a survey of forty widgets and the wrong way to produce a specification for any one of them.

Every adversarial pass over this document has returned row-level corrections, and every one of them
has been real: a menu column's rows are placed inside a scrolled, clipped viewport and not at their
nominal offsets; a segmented control has overflow chevrons the row does not mention; a colour
picker's hue ramp is not the class the row assumes it is; a scroll bar hides itself by returning
early from `onPaint` rather than by becoming invisible, so the tree would publish a control that is
not on screen. There is no reason to think the next pass would return fewer. **A table of forty
widgets written from the outside will keep being wrong in details until each widget's mapping is
written against its own code**, and a table that keeps being corrected in review is not converging on
a specification — it is a survey being polished.

So the table is demoted, in its own words:

> **This table is the starting point each component's pipeline step begins from, and corrects. It is
> not a specification to be implemented literally.** Where a row and the widget's own source
> disagree, the source wins and the row was wrong. Implementing a row without reading the widget is
> the failure mode this paragraph exists to prevent.

It is not deleted, because it is how a reader sees the shape and the size of the work — which
widgets are scaffolding, which carry behaviour, where the hard ones are — and no list of principles
conveys that. It is kept honest by §7.2, which records the corrections already known, and by §14's
phase 4, which is a per-component pipeline of **map, then headless test, then adversarial verify**,
one component at a time. That pipeline is where each row becomes true.

**What must be exact is elsewhere, and is not softened by any of this.** The model (§1.2 to §1.4),
the threading contract (§3), the identity rule (§1.3) and the per-platform interface mapping (§2) are
specifications: a mistake in any of them is wrong for every widget at once and cannot be corrected
one component at a time. A mistake in a row here is wrong for one widget and is caught by that
widget's own pipeline step. The two kinds of statement are held to different standards on purpose,
and mixing them up is how a design document becomes untrustworthy in both directions.

"Transparent" means no node and children hoisted; "ignored" means no node and no children.

| Widget | Role | Facets and states | Synthetic children | Note |
| --- | --- | --- | --- | --- |
| `Row`, `Column`, `Flex`, `Stack`, `Padding`, `SizedBox`, `Expanded`, `TokenBox`, `TokenColumn`, `TokenPadding`, `TokenRow` | transparent | | | scaffolding; the tree is the controls, not the boxes |
| `BackdropPanel` | transparent | | | **Corrected in its framing, not in its verdict.** It sat in the row above and is not inert like the rest of it: it is the only one of the twelve that overrides `onPaint`, so §1.6's paints-and-deleted clause catches it and "scaffolding" hid that. It declares its painting decoration and is removed in silence, holding no state, no handler and no operation to lose. The tree costs nothing here; the log did, and the fix was a toolkit change rather than an application's. Worth an application knowing: the effect covers siblings the panel holds no reference to, so a redaction it paints hides nothing at all from a screen reader |
| `Label` | `LABEL`, or `HEADING` for the title typographic role | name from `textSource()`, `nameFrom=CONTENT` | — | gains `LABEL_FOR` when an application declares the relation. **Amended 2026-09-14:** the `LABEL_FOR` resolves to the widget the target says carries its label — a composite's inner field — where the target redirects (§1.5's amendment of the same day, decision 55) |
| `Button` | `BUTTON` | `ActionFacet{PRESS}`; `DEFAULT` when it is a dialog's default | — | name from `textSource()`, else the tooltip; the action reaches the private path through the widget's own hook |
| `Checkbox` box / switch | `CHECK_BOX` / `SWITCH` | `ToggleFacet`, `ActionFacet{TOGGLE}` | — | name from its own label, `nameFrom=CONTENT` — the field is a private `I18nString` with no getter today and §8 adds the `text()`/`textSource()` pair, because a focusable node with no name fails `AccessibleGalleryTest`. `toggle()` has no enabled guard of its own — the guard is the scene's, which never delivers an event to a disabled widget — so the accessibility path re-checks `isEnabled()` (§1.9) and `toggle()` gains the same guard (§8) |
| `RadioButton` | `RADIO_BUTTON` | **Corrected 2026-09-14: no `MEMBER_OF`** — this column named one "its group with position and size of set", and a `ButtonGroup` is not a node, so there was no target for the relation and none was ever published. `containerless` `SelectionItemFacet` (the position and the size of set ride on it) and `ActionFacet{SELECT}` | — | name from its own label, `nameFrom=CONTENT`, through the same pair §8 adds. Roving focus means only the holder is `FOCUSABLE`, which is correct and is what the reader should hear. **Amended 2026-09-14:** there is no `MEMBER_OF` and never was one published — a `ButtonGroup` is not a node and a relation's target must be one (`RadioButtonAccessibilityTest`); the facet is `containerless` (§1.2's amendment of the same day), so a selection change is the radio's own selected-state event and is laid on no layout node, and a standalone radio's `0`/`0` publishes no position anywhere |
| `ButtonGroup` | no node | | | not a widget and has no bounds; it contributes position and size of set to its members |
| `SegmentedControl` | `RADIO_GROUP` | `SelectionFacet`, `ScrollFacet` when it overflows | one `RADIO_BUTTON` per segment, keyed by index, named by its segment, **plus the two overflow chevrons** | **Corrected:** when the strip overflows it clips to a viewport with a chevron in each gutter, each of which scrolls by most of a viewport and is drawn disabled on the dead side. They are operable controls and may not be dropped (§1.6), and a segment scrolled outside the viewport is not `SHOWING`. Their keys, names and the dead-side disabled state are the pipeline step's. **Amended 2026-09-14 (decision 30; CRIT-6):** the dead chevron is published without `ENABLED` through `Accessibility#disabled()` (§1.2's amendment of the same day) and keeps its `PRESS`, the shape of the tabbed pane's real chevron buttons and of every disabled `Button`; until then it dropped the verb instead, because a widget had no route to a disabled synthetic child, and the verb's coming and going with the scroll was W2's frozen Invoke. A segment publishes `SELECT` alone and refuses `FOCUS`, because the selection is the cursor (decision 11); the group is the container a selection change names and the strip's cursor is the focused node's (§1.10's amendment). Pinned by `SegmentedControlAccessibilityTest`. **Amended 2026-09-15 (decision 30, semantics 5):** the dead chevron carries no verb as well — not `ENABLED` and no `PRESS`, the refused day's shape; the `PRESS` kept on 2026-09-14 was for W2's frozen pattern set, which phase 3 rebuilds, and a press sent to the dead side anyway is refused by the hook (the scroll clamps to nothing). Pinned by the same class. **Not `TAB_LIST`**: its own documentation says it owns no content — it takes labels and hands back an index — so there is no `TAB_PANEL` for a tab to select, and announcing "tab, 1 of 4" would offer page navigation that leads nowhere. Its segments are a `List<String>` and not `I18nString`s, so a segment name cannot follow the subtree locale as §1.7 requires of every other name; §8 gives it the `I18nString` list it should have had **Amended 2026-09-15 (fix round 2d):** the 2026-09-14 amendment's "the shape of every disabled `Button`" no longer holds: a disabled `Button` publishes no `PRESS` either (§1.5's amendment of that date), and the dead chevron's missing verb is now the walk's withdrawal from a narrowed child rather than a branch in the widget |
| `Slider` | `SLIDER` | `ValueFacet{min,max,step}`, `ActionFacet{INCREMENT,DECREMENT}`, `HORIZONTAL` | — | the hook reaches the private from-user path **and then the commit**, so a set from an assistive technology notifies the application the way a key press does (§9, §7.2). The facet's step is the field, `0` when continuous; the two verbs move by the keyboard's nudge and never mirror |
| `Spinner` | `SPIN_BUTTON` | `ValueFacet` with the display form as its text, `ActionFacet{INCREMENT,DECREMENT}` | the up half and the down half, keyed by their region numbers | the widget's `regionAt` answers exactly three things — value area, up, down — so there are no hour and minute *hit targets* to publish. **Corrected in its reasoning, not in its answer:** that is a fact about pointer input, and it does not by itself settle what a reader should be offered for a `07:30` value, which is a question about the inline edit model this row never read. The pipeline step decides, against that model. No `EDITABLE` state in the first cut for the same reason. **Amended 2026-09-14 (decision 5; CRIT-7's widget half):** `SET_VALUE`, implied by the writable facet, takes a text as well as a number — the text goes through the same `parse` a typed commit does (`42`, `1,5`, `7:30` on a time spinner, any known digit set) and what it cannot read is refused before the clamp, as a non-number is — so a platform's value pattern can hand over the string a client spoke (`Value.SetValue("42")` on Windows, once the Windows lane routes it to `SET_VALUE` of text). `ComboBox` took a text already. **Amended 2026-09-16 (decision 69; decision 30, semantics 5):** the half of the stepper column that cannot move the value any further is published without `ENABLED` through `Accessibility#disabled()` and therefore, by §1.5's withdrawal, with no `PRESS` — the upper half at `max`, the lower half at `min`, on the same `value < max` / `value > min` predicates `paintButtons` dims the arrow with, so the node says what the pixels say. (**Precise, amended the same day after review:** the paint's conditions are `enabled && value < max` and `enabled && value > min`, and the hook reads the bound half alone. The two agree because the publish step inherits the owner's `ENABLED` onto every synthetic child and `Accessibility#disabled()` may only narrow it — which `kitchen-dialog.txt` shows, a disabled spinner's arrows printing `[disabled, hidden, offscreen]` with no actions — so it is an inherited-state fact and not the identity the first wording asserted.) It is the refused calendar day's and `SegmentedControl`'s dead chevron's shape carried onto every stepper in the toolkit, twenty-two arrows in a colour picker among them, and it replaces the row's earlier silence and the widget's own "a reader infers the bound from the value against the bounds" (written 2026-09-05, corrected to an open question on 2026-09-15 by a05fe3f9, closed here). What is **not** changed: the spinner's own node keeps `INCREMENT` at `max` and `DECREMENT` at `min` — a value-bearing node stepping past the end of its own range is `ScrollBar`'s and a rail's shape, which `VerbPolicyRatchetTest` reads off the facet, and not an arrow. `Spinner#onSyntheticAction` now answers whether anything moved rather than always `true`, so a press arriving from a stale snapshot is no longer acknowledged with an `INVOKED`; the answer is read off the value because the commit of an open edit that precedes the step can move it too. **Amended the same day, after review:** the answer alone was not the refusal it was written as, and the hook now tests the two bounds itself, before `commitEdit` or anything else runs. Nothing between a platform and a synthetic hook rereads the node — `Scene#performAccessibleAction` gates a synthetic press on the **owner's** enabled chain (§1.5's withdrawal narrows the child, not the owner) and consults `AccessibleNode#accepts` only to decide whether to reveal — so with an edit open a press on the dead arrow reached the hook, committed the typed number on the way to a step it could not take, and `value != before` answered `true` for that commit: the value moved, the application's handler ran twice and the scene emitted the `INVOKED` this decision exists to stop. The four cases written with the decision could not see it, because with no edit open `commitEdit` has nothing to commit and `nudge`'s clamp hides the missing refusal; `SpinnerAccessibilityTest.anArrowAtItsBoundRefusesEvenWithAnEditOpen` pins it, and the answer stays as the second line of defence for a live arrow that clamps anyway. `VerbPolicyRatchetTest.movesNothingByDefinition` loses the fourth shape it carried for this — a `PRESS` under a `SPIN_BUTTON` at a bound, which its own comment called the loosest test in the file — and the gallery's spinners page gains one parked at its maximum, because until then no spinner anywhere in the gallery sat at a bound. **Amended the same day, after review:** at its minimum as well, because one fixture ratchets one predicate. Measured rather than argued, with the max fixture already in place: backing the *lower* arrow's narrowing out left `VerbPolicyRatchetTest` BUILD SUCCESSFUL, while the same sabotage on the upper one cost six of its 248; with both fixtures the lower one costs two ("Spinners" in each of the two passes) and the upper one still six (the spinners page and the colour picker's two entries). Pinned by `SpinnerAccessibilityTest` |
| `ProgressBar` | `PROGRESS_BAR` | `ValueFacet{0..1}` read-only, rounded to whole percent, or `BUSY` when indeterminate | — | same rounding rule as `VideoView`, for the same reason |
| `TextField` | `TEXT_FIELD` | `TextFacet`, `EDITABLE`, `INVALID` from `validation()` | the trailing button when one is set | **Corrected:** `setTrailingButton` takes an `Icon` and a `Runnable` and **no label**, so the synthetic child this row promises has nothing to be named from — an operable control that cannot be ignored (§1.6) and cannot be named either. §8 adds an `I18nString` name to the setter; the existing overloads keep working and give an unnamed button, which the gallery test then refuses. Name from the placeholder when nothing else supplies one. The `TextFacet` is also what vends `ValuePattern` on Windows and `accessibilityValue` on macOS, which is the whole of a text control's behaviour there until `TextPattern` lands (§2.1, §11). **Never `READ_ONLY` from disabled** — the widget has no read-only mode today, so the bit stays clear. **Amended 2026-09-15 (semantics 5, the disabled axis; fix round 2d):** reversed for a field that is not `ENABLED` — disabled, or under a disabled ancestor — which publishes `READ_ONLY` beside `EDITABLE` and no verb, because `READ_ONLY` is the text facet's only way to withdraw `SET_TEXT` (§1.2's and §1.5's amendments of that date); the same holds for `TextArea`. Pinned by `TextFieldAccessibilityTest` and `TextAreaAccessibilityTest`. **Amended 2026-09-15 (fix round 2e):** the reversal is withdrawn and the warning stands: a field that is not `ENABLED` — disabled, under a disabled ancestor, or beneath a layer that owns input — publishes `EDITABLE` without `READ_ONLY` and no verb, and accepts no `SET_TEXT` because it is not `ENABLED` (§1.2's and §1.5's amendments of that date); the same for `TextArea`. Pinned by `TextFieldAccessibilityTest.aDisabledFieldIsStillEditableNeverReadOnlyAndAcceptsNoText`, `aFieldBeneathAnInSceneOverlayIsNeverReadOnlyAndAcceptsNoText` and `TextAreaAccessibilityTest.aDisabledAreaIsStillEditableNeverReadOnlyAndAcceptsNoText` |
| `PasswordField` | `PASSWORD_FIELD` | `TextFacet` **masked** unless `isRevealed()`, `PASSWORD` | — | one mask character per grapheme cluster, never the secret. The published string is `caretCount() - 1` mask characters long, and **the facet's caret and selection are in the mask's own offsets**: a model offset `i` becomes `display.caretOrdinal(i)`, which is the shaped line's own inverse and needs no second rule for finding cluster boundaries. The model's offsets are the secret's UTF-16 offsets and would run past the end of a mask shorter by an astral character. **The shaped line is read for its caret stops and never for its `text()`, which *is* the secret** — the class marks that line `TRAP` in its own source (Finding 14), and this is the second reader of it |
| `SearchField` | `SEARCH_FIELD` | as `TextField`, plus `ActionFacet{PRESS}` for submit | the clear button | the clear button is operable and is named, not ignored |
| `TextArea` | `TEXT_AREA` | `TextFacet` with lines, `MULTI_LINE`, `ScrollFacet` | — | soft-wrap rows are reported as lines |
| `ComboBox` | `COMBO_BOX` | `ExpandFacet`, `HAS_POPUP`, `ValueFacet` text = the selected item | — | the options are **not** here; see the next row. **Amended 2026-09-14 (decision 5; CRIT-7):** `SET_VALUE` by number picks the item at that index and by text the item whose text is exactly that (never a prefix, unlike the type-ahead), both from the user; `ComboBoxAccessibilityTest` pins both, and the native popup's highlighted option is the field's cursor across the `CONTROLLER_FOR` relation (decision 5; `NativePopupCursorTest` in `limn-demo`). **Amended 2026-09-15 (semantics 5; fix round 2c):** while the list is down in the scene the field publishes no verb and its value read-only — the scene refuses every verb beneath the overlay, and until that day the open field published `COLLAPSE` there (§1.13's amendment of that date); in a window of its own it publishes `COLLAPSE`. Pinned by `ComboBoxAccessibilityTest`. **Amended 2026-09-15 (fix round 2e):** its value is no longer published read-only beneath the list; the field keeps a writable value and accepts no `SET_VALUE` there because it is not `ENABLED` (§1.5's amendment of that date) |
| ↳ `ComboBox.PopupPanel` | `LIST` | `SelectionFacet` with an active descendant | one `LIST_ITEM` per option, keyed by index, `SELECTED` on the chosen one | described in the scene it lives in, so its bounds are right in both mountings (§1.11). **Amended 2026-09-15 (decision 11, positive half):** each option publishes `SELECT` and `PRESS`, which commit, and `FOCUS`, which moves the highlight (the list's `ACTIVE` cursor) onto it the way the arrows do and commits nothing, because the highlight and the selection are separate fields here. Pinned by `ComboBoxPopupAccessibilityTest`. **Corrected 2026-09-15 (semantics 5; fix round 2c):** the three are published and performed on one condition, the list open and not fading out and the combo enabled; through the fade-out all three were published while `FOCUS` was refused and `SELECT` and `PRESS` answered done for a commit that did not happen (`PRESS` raised `INVOKED`), and on a combo disabled under its open list all three were published and refused. Pinned by `ComboBoxPopupAccessibilityTest.throughTheFadeOutNoOptionOffersAVerbAndNoneIsPerformed` and `aDisabledComboRefusesToBeCommitted` **Amended 2026-09-15 (fix round 2d):** in a window of its own the close asks the panel's scene to walk again, because the window's fade moves only its opacity and damages nothing, and until then the popup's tree kept the three verbs for the whole fade. Pinned by `NativePopupFadeOutTest` in `limn-demo`. **Amended 2026-09-15 (the 2d review):** while the combo is disabled every option is narrowed with `Accessibility#disabled`, so it publishes without `ENABLED` and with no verb in both mountings; the walk no longer reads that off the overlay's inheritance host (§1.9's amendment of this date). Pinned by `ComboBoxPopupAccessibilityTest.aDisabledComboRefusesToBeCommitted` |
| ↳ `ComboBox.ScenePopup` | transparent | | | the overlay wrapper. **Amended 2026-09-15 (semantics 5; fix round 2d):** the layer publishes `CANCEL` only while the list is open and its hook refuses it otherwise; through the fade-out after a close it published `CANCEL`, which `close()`'s guard dropped while the hook answered done. Pinned by `ComboBoxPopupAccessibilityTest` and the fade-out sample of `VerbPolicyRatchetTest` |
| `ListView` | `LIST` | `SelectionFacet` with an active descendant, `ScrollFacet` | — | rows are real pooled widgets mounted directly (Finding 14); `onAccessibilityChild` gives each mounted row `LIST_ITEM`, `SELECTED`, its data index as position in set, `rowCount()` as size of set, and **its data index as the identity key** (§1.3), which is what keeps a recycled cell from carrying row 3's identifier to row 9. **Corrected:** a row also needs `ActionFacet{PRESS}` mapped onto `ListView#activate()` — Enter activates the selected row and fires `onActivate`, and a row published with `SELECTED` and no verb is a list a screen reader user can move through and cannot use. Unmounted rows are not published (§11); a scroll never unrealizes the row holding the keyboard focus, which is published with its box outside the list's and without `SHOWING`, as the half-off row already is (§13.29). **Amended 2026-09-14:** each mounted row also carries `ActionFacet{SELECT}`, delegated by the list through `Accessibility#delegate` and performed by `ListView#onAccessibilityChildAction` through the same `USER` seam a click takes (§1.5's amendment of the same day; decision 7); `PRESS` stays on the list, for the reason §7.2 gives. **Amended 2026-09-14 (decisions 20, 22, 11):** the rest of the row verb set read against this widget — a row also carries `SCROLL_INTO_VIEW`, delegated only on a cell that is not focusable (a focusable one gets the walk's free pair, and a second performer is refused) and performed as a reveal in place; a row never carries `FOCUS`, because the selection is the cursor and a focus that selected would be `SELECT` under another name (decision 11); never `ADD_TO_SELECTION`/`DESELECT`, because the list selects one row and has no multi-select. And the selected row is kept realized while the list holds the keyboard — not `SHOWING`, still `ACTIVE`, across a `refresh()`, released when the keyboard leaves (decision 22; §11's amendment of the same day); the list's-own-node description of an unrealized selection is therefore reached only on an unfocused list and duplicates nothing. `ListViewFocusedRowTest` and `ListViewAccessibilityTest` pin all of it |
| `Table` | `TABLE` | `TableFacet`, `SelectionFacet` (multi-selectable in `MULTI`), `ScrollFacet`, `ActionFacet{PRESS}` while a row is selected | one `GROUP` for the header row with a `COLUMN_HEADER` child per shown column, each with `CellFacet(-1, column)` and the column's title as its name; one `ROW` per realized data row with `SelectionItemFacet` (view position, model row count) and a `CELL` child per shown column with `CellFacet(row, column)`, its formatted text as its name and the row's witness; the focus cell is `ACTIVE`; last, when a column has a footer, one `GROUP` for the footer row with a `CELL` child per footer cell, `CellFacet(-2, column)` | added by ADR 041 §7 on 2026-09-08, and written against the widget's own code rather than surveyed from outside. Cells and headers are synthetic children keyed by row and column (§1.3); a widget cell of a widget column is a real child published in its place. **Amended 2026-09-14:** the widget cell hangs under the synthetic `ROW` of its record, keyed by its column alone and identified through the row, and takes its place among the row's cells by column (§1.3's amendment, decision 3); until then it was a child of the `TABLE` after every row, which every bridge's row-and-column lookup missed. Unrealized rows are not published (§11) and the row holding the keyboard focus stays published wherever the viewport is (§13.29). **Amended 2026-09-14 (Table lane; decisions 10, 11, 32):** `ActionFacet{PRESS}` on the table whenever there is a focus cell, in every mode, opening the cursor row; each `ROW` carries `SELECT` (mode ≠ `NONE`), `ADD_TO_SELECTION` or `DESELECT` by its state in `MULTI`, and `FOCUS`; each `CELL` carries `FOCUS`; cells are keyed by row and column together and header cells distinctly, so a verb decodes the node it was published on (TABLE-NEW-13); ADR 041 §7's amendment of the same date has the rule. The focus cell's row is also kept and published off screen while the table holds the keyboard (decision 22; ADR 041 §2's amendment), its cells off screen with it, and the table declares the per-child clip so a widget cell under the header is not `SHOWING`. With a sortable column the header is a focus stop of the table (decision 36): while it holds the keyboard the table stays the focused node and the header cell under its column cursor is `ACTIVE` instead of a `CELL`; a sortable header cell carries `PRESS` and the sorted one the direction as its description (ADR 041 §4/§7 amendments). **Amended 2026-09-15 (decision 23, the node half):** a `ROW` is keyed by its record's row identity and not its model index, so its node and its cells' follow the record across a `refresh()` that inserted, removed or reordered, and a verb sent before it acts on the record it was published for or is refused when that record left the list (ADR 041 §3's amendment of that date) |
| `Tree` | `TREE` | `SelectionFacet` (multi-selectable in `MULTI`), `ScrollFacet`, `ActionFacet{PRESS}` on the lead row and `{EXPAND}`/`{COLLAPSE}` while that row can open | — | added by ADR 044 §4 on 2026-09-12. Rows are the application's own cell widgets mounted directly, as `ListView`'s are, so `onAccessibilityChild` gives each one `TREE_ITEM`, the node's stable identifier as the identity key (§1.3), `SelectionItemFacet` (row position, visible row count) and `ExpandFacet` where the row can open; the lead row is `ACTIVE`. **It published `LIST` and `LIST_ITEM` until 2026-09-13**, the day the AT-SPI numbers came off the guest; what it still owes is depth and position-in-level, which no facet carries, and the macOS disclosure attributes. The verbs sit on the tree and act on the lead row, because a child's hook writes facts and never verbs (the `ListView` row above); per-row verbs arrive with the synthetic `TREE_ITEM` rows. Unrealized rows are not published (§11) and the row holding the keyboard focus stays published wherever the viewport is (§13.29). **Amended 2026-09-14:** every row now also carries `HierarchyFacet(level, row, rowCount)` — its depth from one and its flat index among the open rows (§1.2's amendment of the same day) — so the depth this row said it owed is published; the sibling-relative `SelectionItemFacet` numbering of decision 4 and the macOS disclosure attributes are the Tree and macOS lanes'. **Amended 2026-09-14 (verbs):** "per-row verbs arrive with the synthetic `TREE_ITEM` rows" is withdrawn — the rows stayed the application's widgets, and a row's verb reaches the tree by delegation instead: each row carries `ActionFacet{SELECT}` while the selection mode is not `NONE`, performed by `Tree#onAccessibilityChildAction` through `selectOnly` from the user; `EXPAND`/`COLLAPSE` per row and the rest of decision 20's set are the Tree lane's. **Amended 2026-09-14 (Tree lane, phase 2):** what this row said before that lane is superseded in four places, and stands above as the record of the first cut. Each row carries its verbs **by state**: `SELECT` while the selection mode is not `NONE`, `ADD_TO_SELECTION` or `DESELECT` by membership in `MULTI`, `EXPAND` or `COLLAPSE` where it can open, and `FOCUS` and `SCROLL_INTO_VIEW` on a cell that cannot take the focus itself — every one delegated by the tree and performed by `Tree#onAccessibilityChildAction` through the gesture's own seam from the user, `FOCUS` moving the cursor without selecting and `EXPAND`/`COLLAPSE` acting like the triangle (decisions 7, 11, 20). The tree's own node publishes `ActionFacet{PRESS}` alone, acting on the **cursor** row in every mode (decision 32), and it is the cursor row — the keyboard row, a field of its own since decision 14 and not the selection's lead — that is `ACTIVE`. `SelectionItemFacet` numbers a row among its parent's children (decision 4; `HierarchyFacet` carries the depth and the flat index), so the outline position it counted until then is gone. A row is named from `Model.nameOf`, else its cell's own name, else the text of the cell's visible labels in reading order (ADR 044 §4's amendment of the same day). While the tree holds the keyboard its cursor row stays published wherever the viewport is, outside the box and without `SHOWING`, as the focused row does (decision 22), and is released by the first pass after the focus leaves. **Amended 2026-09-15:** `ADD_TO_SELECTION` and `DESELECT` toggle the row without moving the cursor or the range anchor — only `SELECT` and `FOCUS` move the cursor (decision 20); the command-click still moves both — and every verb the kept cursor row publishes is performed while it is outside the box, because the scene gates a delegated verb on the tree showing and not on the row (§1.5's amendment of the same date), with a reveal that scrolls to where the row stands (ADR 044 §3) |
| `CalendarView` | `TABLE` | `TableFacet(6, 7)` (8 columns with week numbers), `SelectionFacet` (multi-selectable in `RANGE`) | one `GROUP` for the weekday header with a `COLUMN_HEADER` child per column, each `CellFacet(-1, column)` and named with the **full** weekday rather than the narrow letter that is drawn; one `ROW` per week; one `CELL` per day with `CellFacet(row, column)`, the whole localized date as its name, `SelectionItemFacet` while it is selected or inside a range band (**corrected 2026-09-14, decision 37:** on every day cell while the selection mode is not `NONE`, selected or not, numbered as its day of the month over its own month's length in the drawn calendar — "15 of 30", a leading cell "31 of 31" — where it was the flat index over 42; a chooser cell keeps its index over the chooser's count), `ActionFacet{SELECT}` only while it is selectable, and `ACTIVE` on the keyboard cursor (**amended 2026-09-15, decision 11's positive half:** every day the bounds and the filter do not refuse also publishes `FOCUS`, in every selection mode, which moves the cursor there and selects nothing; a refused day carries neither verb; each chooser cell that leads somewhere publishes `FOCUS` beside `SELECT`, moving the chooser's cursor without descending or picking); two `BUTTON`s for the paging arrows; a `BUTTON` for the title with an `ExpandFacet` (expanded = not the finest view) and `PRESS` plus `EXPAND` on the finest view or `COLLAPSE` above it, accepting exactly those (**amended 2026-09-14**, settled calendar-title-verbs; refused days publish disabled, decision 30; a terminal chooser's cells carry `SelectionItemFacet`, decision 48) | added by ADR 042 §8 on 2026-09-09, written against the widget's own code. **It adds no role and no facet**: every one is ADR 041's, mapped on all three platforms the day before this widget existed, which is why a calendar could ship without reopening the bridges. Day cells are keyed by a flat 0..41 index and not by column, because a verb arrives carrying only the innermost key and two cells under different rows sharing one would be indistinguishable (**amended 2026-09-14, DT2:** the month chooser's rows and cells and the year chooser's each take a key range of their own, below the day grid's, so a month cell is never the node a day cell or a year cell was across a view change — a key is an identity, and a Windows element is built once with the interfaces its node had when first read; the two paging buttons are named for what they page in the view on show, and the cell holding the month or year on show says so in its name, decision 48). A day the bounds or the filter refuse carries no verb, which is the whole of how it says so (**corrected 2026-09-14, decision 30:** no longer the whole of it — the day is also published disabled, through the narrowing declaration, and the cursor stops on it; the children column says so, and this sentence was left standing when it was amended) |
| `DateField` | `GROUP` | **Corrected 2026-09-14 (decision 16; ADR 042 §12's first finding): no `ValueFacet` on the group** — this column said "text = the whole field as drawn", and `valueText` gives an *existing* value a display form, so a node with no value facet had nothing for it to land on and the whole date reached nobody; a minimum, a maximum and a step over a date would be three lies for a bridge to carry, and the segments carry the values, each over its own real range (`DateField.text()` is the whole date for an application that wants it). What the group does carry: `INVALID` with the reason as the description when it holds something unacceptable | one `SPIN_BUTTON` per **editable** segment, in the order the locale's own pattern writes them, each named from the toolkit's bundle ("Year", "Month", "Day", "Hour", "Minute", "Second", the day period), each with `ValueFacet` over that segment's own range and `ActionFacet{INCREMENT,DECREMENT}` reaching the path Up and Down reach (**amended 2026-09-15, decision 11's positive half:** plus `FOCUS`, which takes the focus and puts the caret in that segment as a click does, changing no value; **corrected the same day, semantics 5:** none of the three verbs, and the value published read-only, on a disabled field or on the field beneath its picker's in-scene overlay, where the scene drops every verb — ADR 042 §8; **amended 2026-09-15, fix round 2c:** the overlay half is the walk's rule for every node outside the input layer, §1.13, and the field keeps only the disabled half; **amended the same day, fix round 2d:** the disabled half is the walk's too, for every node that is not `ENABLED`, a field inside a disabled container included, §1.5; **amended the same day, fix round 2e:** the value is no longer published read-only in either case — it stays writable, and a segment that is not `ENABLED` accepts no `SET_VALUE`, §1.5), and `ACTIVE` on the one holding the caret (**amended 2026-09-14, decision 5:** not while a picker's popup in a window of its own holds the keyboard — the field keeps the focus there, and the caret's `ACTIVE` would shadow the cursor the walk reads off the popup's tree) | added by ADR 042 §8. The shape `Spinner`'s row leaves open for a clock, decided here: the caret is in one segment at a time, so a single text field publishing `31/12/2026` gives a reader no way to say which part it is in. An era segment is published and carries no verb (ADR 042 §3). **Amended 2026-09-14 (decisions 16, 38, 53):** no era segment is published — the era is a read-only piece of the drawn pattern and no node — and the year segment's value text carries it ("令和8"); an unfilled segment publishes the empty value with the spoken word "empty" as its text; the group carries no `ValueFacet`, which the facets column now states in its own place instead of leaving the reversed claim standing there |
| `DatePicker` | `GROUP` | `ExpandFacet` | one `BUTTON` for the trailing calendar affordance, carrying the same expanded state (**corrected 2026-09-14, DT4:** the button is the real widget child of the next row, not a synthetic one, and carries no expanded state) | added by ADR 042 §8. Its field (or the two ends of a period) are **real children** and describe themselves, which is the reason they are widgets rather than regions this class paints. **Amended 2026-09-14 (decisions 18, 55):** no `ExpandFacet` and no verb on the group; a single picker declares nothing and is no node (its field and button are hoisted; a bound caption names the field through `accessibleLabelTarget`), a range picker is a `GROUP` keeping the caption over two fields named "Start date"/"End date"; the field inside a picker publishes `ExpandFacet`, `HAS_POPUP` and `EXPAND`/`COLLAPSE` by state, and accepts exactly those (**corrected 2026-09-14:** `COLLAPSE` only where the field can take it — in a window of its own; while the popup is an overlay of the scene the field publishes its state and no verb, and the overlay's `CANCEL` closes) |
| ↳ `DatePicker.CalendarButton` | `BUTTON` | `ActionFacet{PRESS}` (**amended 2026-09-14:** the `ExpandFacet` it carried is gone — a plain press; the state is the field's) | — | the trailing affordance, and a **real widget** rather than the synthetic child it started as. Focus traversal in this toolkit visits widgets, so a painted button is one no keyboard user can reach however well it is described; it was found by a pair of eyes on the running program rather than by any test here, and the fix is structural rather than descriptive |
| ↳ `DatePicker.PopupPanel` | `GROUP` | | | named "Calendar"; the card the grid sits on. **Amended 2026-09-14 (decision 19):** at an hour granularity or finer the card also holds a time row, a real `DateField` named "Time of day" that is never focusable and publishes `ACTIVE` on its caret segment while the picker's Tab cycle has put the keyboard on it |
| ↳ `DatePicker.ScenePopup` | `GROUP` | `ActionFacet{CANCEL}` | | the in-scene overlay wrapper, declared for `ComboBox.ScenePopup`'s reason: it is the scene's only tab stop while the popup is open, so a node published with no role would be a focusable `UNKNOWN`. **Amended 2026-09-15 (semantics 5; the 2d review):** `CANCEL` is published and performed only while the calendar is open; through the fade-out after a close the layer published it, and its hook's `setOpen(false)` returned at once for a picker already closed while the hook answered done (ADR 042 §8's amendment of that date). Pinned by `DatePickerAccessibilityTest.throughTheFadeOutTheCalendarsLayerOffersNoCancel` and the fade-out sample of `VerbPolicyRatchetTest` |
| `TabbedPane` | transparent | | | the pane itself is scaffolding |
| ↳ `TabStrip` | `TAB_LIST` | `SelectionFacet` | — | |
| ↳ `TabHeader` | `TAB` | `SelectionItemFacet`, `ActionFacet{SELECT}` | — | name from the tab's own `I18nString` title, `nameFrom=CONTENT`. It is a private inner widget of `TabbedPane`, so it reads that title from inside its own package and needs no new accessor for this ADR — ADR 040 needs one for a different reason (§9) |
| ↳ selected content | `TAB_PANEL` | `LABELLED_BY` its header | — | an unselected panel's subtree is under a widget whose own `isVisible()` is false, so by §1.2's inherited rule it and everything in it publish without `VISIBLE` and without `SHOWING` — which is also what stops a hidden tab's controls from announcing as focusable |
| ↳ `StripButton` | `BUTTON` | `ActionFacet{PRESS}` | — | unnamed today; §8 gives it a name. **Amended 2026-09-15 (semantics 5; fix round 2d):** a chevron at the end of its travel is disabled by the pane and publishes no verb, where until that day it published `PRESS` the scene refused. Pinned by `TabbedPaneStripButtonAccessibilityTest` |
| `MenuBar` | `MENU_BAR` | — | one `MENU_ITEM` per title, `HAS_POPUP`, `ActionFacet{SHOW_MENU}`, keyed by index | **Amended 2026-09-14 (decision 2; CRIT-1):** a title publishes exactly the verbs it accepts, by its state — `ActionFacet{SHOW_MENU, EXPAND}` while closed and `{COLLAPSE}` while open — where until that day it published `SHOW_MENU` alone and accepted `EXPAND`, `COLLAPSE` and `PRESS` in silence, which §1.5's amendment of the same day forbids: the published list is the only refusal a platform can see. `PRESS` is refused now. Every title also carries `EXPANDABLE`, derived from the facet (decision 41; the state amendment in §1.2). Pinned by `MenuBarAccessibilityTest` and the gallery ratchet, whose CRIT-1 lines are struck; `kitchen-dialog.txt` moved on the three title lines and nothing else. **Amended 2026-09-15 (decision 2, "by state"):** the expanded bit, the verbs and the hook read one fact — this title is the open one *and* a cascade is on screen. Until then the bit read the popup and the verbs read the bar's open index, which is recorded before the popup is asked to show, so a title whose show was refused published `collapsed` with `{COLLAPSE}`; it now publishes `{SHOW_MENU, EXPAND}`, its `EXPAND` asks for the open again and its `COLLAPSE` is refused. A submenu row never had the split: its bit, its verbs and `chooseRow` all read the open column. Pinned by `MenuBarAccessibilityTest` (the refused show) and `MenuBarNativePopupTest` in `limn-demo` (a title already down, and a collapse, both of which need a cascade on screen outside the in-scene overlay's input gate). **Amended 2026-09-15 (decision 11, positive half):** every title also publishes `FOCUS` while no menu is down, an empty menu's title included (the arrows land on it), and performing it takes the keyboard and puts the bar's cursor on that title without opening it — choosing a title opens its menu, so the cursor is not the choice; while a menu is down the open title is the cursor and no title takes `FOCUS`. `kitchen-dialog.txt`'s three title lines gain `focus`. Pinned by `MenuBarAccessibilityTest`. **Amended 2026-09-15 (semantics 5; fix round 2c):** the verbs above are a title's while its cascade is a window of its own or no cascade is down; while a cascade is an overlay of the scene no title publishes any verb, because the scene refuses every verb beneath the overlay (§1.13's amendment of that date). `kitchen-dialog.txt`'s three title lines, in a window a native modal blocks, lose their actions. Pinned by `MenuBarAccessibilityTest.anInSceneCascadeTakesEveryVerbOffTheBarAndPerformsTheOnesItPublishes` and `MenuBarNativePopupTest` |
| `PopupMenu.MenuSurface` | `MENU` for the root column | `SelectionFacet` with the active descendant, `MODAL`, `ScrollFacet` on a column that scrolls | one node per open column and one per row of each, keyed by the `MenuItem`'s minted serial | **bounds come from `Column`, never from the widget box**, which is the whole scene either way (Finding 14). The root column's rectangle is the surface node's; each further column is a `MENU` node under the row that opened it, which is the nesting all three platforms expect. **Corrected:** a row's rectangle is *not* simply its `top[]`/`hgt[]` — a column has a `visibleH` that can be less than its content, a scroll offset, and a clip inset the rows are painted inside, so a long menu's row boxes must be offset by the scroll and rows outside `visibleH` published as not `SHOWING`. Taking `top[]` at face value puts a screen reader's cursor on rows that are scrolled away |
| `MenuItem` | as above | `ToggleFacet` for check and radio kinds; `ActionFacet{PRESS}` or `{SHOW_MENU}`; key binding from `Accelerator#display()` | — | the action is offered only when the row is selectable **and** is not a submenu with nothing in it, because `hasSubmenu()` is false for an empty submenu while `isSelectable()` stays true. **Amended 2026-09-14 (decision 2; CRIT-1):** a row publishes exactly the verbs it accepts, by its state — a submenu row `ActionFacet{SHOW_MENU, EXPAND}` while closed and `{COLLAPSE}` while open, a check row `{PRESS, TOGGLE}`, a command row `{PRESS}` — where until that day a submenu row published `SHOW_MENU` alone and accepted `EXPAND`, `COLLAPSE` and `PRESS` in silence, and every selectable row accepted `TOGGLE`; `PRESS` on a submenu row and `TOGGLE` on a command row are refused now. There is no radio kind in `MenuItem.Kind` (`COMMAND`, `CHECK`, `SUBMENU`, `SEPARATOR`), so the toggle facet is a check row's alone. Pinned by `PopupMenuAccessibilityTest` and the gallery ratchet. **Amended 2026-09-15 (decision 11, positive half):** every row the arrows can land on (`isSelectable()`: not a rule, not disabled; an empty submenu row included) also publishes `FOCUS`, and performing it moves the highlight onto that row without choosing it or opening its submenu, closing any column open below the row's own as the leading arrow does, because the cursor is the deepest column's. Pinned by `PopupMenuAccessibilityTest`. **Amended 2026-09-15 (semantics 5, the disabled axis; the 2d review):** a disabled row is narrowed with `Accessibility#disabled`, so it publishes without `ENABLED` as well as without verbs and key binding, where until then it published `ENABLED` and a reader heard a disabled Cut or Copy row as an ordinary row with nothing to do; a disabled submenu row loses `SHOW_MENU` and `EXPAND`, which `chooseItem` refused while the hook answered done, and the hook now refuses them too. The menu bar's cascades are these rows. No transcript holds a disabled row. Pinned by `PopupMenuAccessibilityTest.aRowThatCannotBeChosenCarriesNoVerbAndOnlyADisabledOneSaysItIsDisabled` and `aDisabledSubmenuRowSaysSoAndOpensNothing` |
| ↳ the highlighted row | | `SELECTED` on the row, active descendant on its column | — | `Column#highlight` is an `int` today and reaches the tree through the column's `SelectionFacet`; moving it raises `ACTIVE_DESCENDANT_CHANGED`, which is how a keyboard walk down a menu is announced at all. Without it a reader can see the rows and never learn which one the user is on |
| `Dialog` panel or overlay | `DIALOG` | `MODAL`, `ActionFacet{CANCEL}`; `WindowFacet` **only** when it is a real window | — | in scene it is a dialog node inside the owner's tree, with `UIA_IsDialogPropertyId` on Windows. **Corrected 2026-09-15 (semantics 5; fix round 2d):** once answered the dialog keeps its layer (or its window) through the fade-out, and there the card published `CANCEL`, which its hook refused, and its buttons `PRESS`, which was answered done and raised `INVOKED` for a resolution the dialog's own guard dropped. The card's `CANCEL` and its buttons' `PRESS` are now published and performed on one condition, that no answer is on its way out, in both mountings; the answer asks for the walk that withdraws them, because a native window's fade damages nothing. Pinned by `DialogPanelAccessibilityTest` |
| `ScrollView` | `SCROLL_PANE` | `ScrollFacet`; descendants gain `SCROLL_INTO_VIEW` | — | a node, not transparent: the scroll behaviour lives here, and eliding it deletes `ScrollPattern` and `ScrollItemPattern` from a scene that is mostly scrollable. **Corrected:** under a reserved gutter the pane clips its content to the viewport and its bars to the box, and `isShowing()` clipped against the box alone, so a descendant lying wholly inside the strip was published `SHOWING` under its own bar. `Widget` gained per-child clip accessors beside `clipsChildren()`, the showing test and the damage clamp read them, and the pane answers the viewport for its content and the box for its bars |
| `ScrollBar` | `SCROLL_BAR` | `ValueFacet{offset, 0, content − viewport, step = viewport}` in the model's own points, settable; `HORIZONTAL`/`VERTICAL` | `INCREMENT`, `DECREMENT` (a page each); `SET_VALUE` through the facet | a node, not decorative; not focusable, which is correct. **Corrected twice.** The row listed no verb, and the source has two pointer paths, a page toward the pointer and a thumb drag, so the node carries the two pages and accepts a set, all through one private path with the pointer's own clamp and reveal. And the row's first correction — that "under `ON_SCROLL` it hides by returning early from `onPaint`" — lumped three absences into one: the early return is on an opacity that is zero under `HIDDEN` at any time, zero under every policy when the content fits, and the fade value under `ON_SCROLL` and `AUTO`. The first two are structural, move only on a policy, content or size change, and are the two cases the node is ignored; the fade is a paint alpha and a hit-test gate, and the node stays through it, because the action gate (§1.9) never consults opacity and a reader's set reaches `onScrolled()`, which reveals the bar exactly as the wheel does. The "not on screen" dilemma is answered by that path and not by the tree; the fade cannot be read into the tree in any case, since `offScreen()` is for synthetic children and the state setter refuses `SHOWING` |
| `SplitPane` | `SPLIT_PANE` | — | — | |
| ↳ `Divider` | `SPLITTER` | `ValueFacet{ratio}`, `HORIZONTAL`/`VERTICAL` | — | `FOCUSABLE` only when the application made it so |
| ↳ `Pane` | transparent | | | |
| `Separator` | `SEPARATOR` | `HORIZONTAL`/`VERTICAL` | — | orientation from the laid-out box |
| `ToolBar` | `TOOL_BAR` | — | — | |
| `ContextMenus.ContextRegion` | transparent | `HAS_POPUP` and `ActionFacet{SHOW_MENU}` are put **on its own child**, through `onAccessibilityChild` | — | the region measures and lays out to exactly its content's box, so the child's node is the rectangle a user would right-click. The action is never lost with the wrapper (§1.6) |
| `ImageView` | `IMAGE` | name from the application or the tooltip | — | ignored when it has neither, because a nameless image is noise |
| `VideoView` | `VIDEO` | `ValueFacet{position,duration}`, **position rounded to whole seconds** | — | the rounding is not cosmetic: an unrounded position advances every frame, and the diff would then publish a whole tree copy per frame for the length of the film with the user doing nothing (§6) |
| `MediaControls` | `TOOL_BAR` | — | — | its icon buttons are real widgets and get `BUTTON`, described once on their shared private base and named by their tooltips; the mute's name follows the bar's muted state and not the play state, and is written where that state changes (§7.2) |
| ↳ position `Label` | `LABEL` | — | — | its text changes about once a second; an application that wants it spoken calls `announce` (§11) |
| `Viewport3D` | `CANVAS` | — | — | focusable with no key handling today, so the tree faithfully reports a tab stop that does nothing (§13) |
| `ColorPicker` | `COLOR_CHOOSER` | — | — | |
| ↳ `Rail`, `ChannelTrack`, `AlphaRail` | `SLIDER` | `ValueFacet`, `ActionFacet{INCREMENT,DECREMENT}` | — | `LABELLED_BY` the sibling label, declared by the picker |
| ↳ `HueRamp` | pipeline decides | | | **corrected:** it extends `Painted`, not `Rail`, so it has none of the rail machinery a `SLIDER` row assumes, and it is not focusable. It does drag to set the hue, so it is operable and may not simply be ignored (§1.6). Its mapping is its own pipeline step's, not this table's |
| ↳ `SaturationValueField` | `CANVAS` | — | — | two-dimensional value, which no platform carries (§4.1); its description names both channels |
| ↳ `Preview` | `IMAGE` | name is the colour in hex | — | |
| `ColorPickerButton` | `BUTTON` | `HAS_POPUP`, `ActionFacet{PRESS}`; value text is the hex | — | `CONTROLLER_FOR` the dialog once it exists |
| `Chart`, `CartesianChart`, `BarChart`, `LineChart`, `DonutChart` | `CHART` | name from `titleSource()`, description generated from the axes and series | one `CHART_SERIES` per series, named by `ChartSeries#nameSource()` | marks are not nodes in the first cut (§11). **Corrected for `DonutChart` by its step, in five places** (§7.2): the children are the legend entries — one per *slice*, keyed by category index, named by `labelSource(i)`, carrying a toggle read from the hidden-slice bit and the `TOGGLE` verb while the legend is interactive — and there is no node per series, because the donut draws only its first visible series; the description is that series' `nameSource()` by reference, or the name when there is no title, never a generated string; each child's box is the legend box the click is tested against, brought up to date by the hook because the region pass runs before paint and not before publish; the centre widget is a real child walked after them; and the hook lives on the concrete class, not on `Chart`. **Corrected for `BarChart` by its step, in six places** (§7.2): one `CHART_SERIES` per series, because every series is drawn whether or not the legend is, keyed by a serial the series carries for life and never by its position; placed at the legend row a click toggles it on, which stands still through the entry animation, and at the chart's own box when no row is drawn, never at the bars; carrying the model's visibility bit as a toggle and the `TOGGLE` verb only where a row exists and the legend is interactive; the chart node carrying which way the value axis runs as `HORIZONTAL` or `VERTICAL`; no description generated; and the hook on the bar chart alone, because one on the Cartesian base would describe the line chart before its step. **Corrected for `LineChart` by its step** (§7.2): the same description as the bar chart's, because the facts are the same for every chart with axes, declared by the line chart's own hooks over one body the Cartesian base now holds; the line's points and paths are not nodes because their buffers hold one series at a time and move on every frame of the entry animation |

### 7.1 Synthetic children, and the derivation rule

A synthetic child is a node whose owner draws it but never instantiated it as a widget: a menu column,
a menu row, a combo option, a menu-bar title, a segment, a spinner button, a chart series. It is
created through the `Accessibility` builder the owner is already holding, keyed by a `long` the owner
chooses, and it supplies its own local bounds because nothing else can. A synthetic child may nest —
a menu column owns its rows, and a row that opens a submenu owns that column — which is what lets one
widget describe a cascade.

**The rule, stated once: a node is either a widget or a synthetic child declared by a widget, and there
is no third kind.** That is the answer to menus being a parallel model. `Menu` and `MenuItem` are not
widgets and stay that way, because a menu is a model and `MenuSurface` is the widget that renders it;
what changes is that `MenuSurface` declares one synthetic child per row and reads the model from
inside its own package (§8).

It is also why `ListView` does **not** get a synthetic row node above its mounted cell, tempting as
that is: the cell is a widget, a synthetic node cannot have widget children without becoming a third
kind, and the problem the wrapper was invented to solve — identity across recycling — is solved
instead by letting the parent choose the child's key (§1.3).

This is not an afterthought. On every one of the three platforms these are the nodes an assistive
technology spends most of its time in: a menu with no rows is a menu a user cannot operate.

**Amendment, 2026-09-14: "no third kind" is withdrawn for one shape, and it is not a third kind.**
The owner decided (decision 3, 2026-09-13) that a widget may hang under a synthetic node when its
owning container names that node as its host row: `Table` names the synthetic `ROW` each widget cell
sits under, from the identity hook (§1.3, amended the same day), and the cell publishes among the
row's synthetic cells in column order. The node is still a widget — it describes itself, it keeps
its own verbs, and an action on it reaches the widget's hook and never the owner's synthetic one —
and the row is still a synthetic child of the table; what changed is only which node the widget's
node hangs under. The reason the rule was written stands: `Menu` and `MenuItem` stay models,
`MenuSurface` stays the widget that renders them, and nothing here invents a wrapper widget. The
`ListView` paragraph above also stands as written, because a list has no row node to hang a cell
under and needs none; what it needed — identity across recycling — the identity hook now gives to
everything inside the cell, not only to the cell.

### 7.2 Known corrections the pipeline must apply

These came out of adversarial review of the table above. The cheap ones are already folded into their
rows and are listed here so that nobody re-derives them; the rest are recorded as work, because
settling them means reading the widget and the table cannot settle them by being edited again. **This
list is not exhaustive and is not expected to be** — it is what one pass over forty rows found, and
its real function is to calibrate how much the table should be trusted.

*Carried into the rows already:*

| Widget | The correction |
| --- | --- |
| `PopupMenu` column | rows sit inside a scrolled, clipped viewport with its own `visibleH`; a row's box is not its raw `top[]`, and a scrolled-away row is not `SHOWING` |
| `SegmentedControl` | overflow chevrons exist, are operable, and have a dead side; an overflowed segment is not `SHOWING` |
| `ColorPicker.HueRamp` | extends `Painted`, not `Rail` — the `SLIDER` mapping assumed machinery it does not have |
| `ScrollBar` | hides by returning early from `onPaint`, not by becoming invisible, so visibility does not describe whether it is on screen. **Corrected by the step:** that early return covers three different absences and only one of them is the fade. The policy `HIDDEN` and content that fits are structural, leave the bar unoperable by anyone — the hit test refuses at every opacity and nothing paints — and are the two cases the node is ignored; the fade is transient, gates the pointer alone, and the node stays through it, because the scene's action gate never consults opacity and the bar's own from-the-user path reveals it on use. The row's "—" under actions was wrong: a track press pages and a thumb drag sets, so the node carries `INCREMENT` and `DECREMENT` and accepts `SET_VALUE`, and the step is one viewport, the only step the source has. The verbs are defined on the value, a magnitude in both layout directions, and never mirror; the pointer's paging sign on a mirrored horizontal bar is a screen-side mapping that stays with the pointer. Also outside this table: §0's Finding 13 and §8 count "the auto-repeat in `Spinner` and `ScrollBar`" among the self-rescheduling timers, and `ScrollBar` has none — a track press pages once, and its one delayed post is the hold-expiry check, armed at most once and re-armed only when activity extended the hold |
| `TabbedPane` hidden panel | handled by §1.2's inherited `VISIBLE`, which the table previously stated as a one-off for this row |
| every widget, in the builder | an empty resolved name carries **no provenance**: `Accessibility#name` writes `CONTENT` whatever the hook said, because a `TextField` with no placeholder was publishing `PLACEHOLDER` and telling one platform which attribute to put nothing in (found by the transcripts, 2026-09-07) |
| `TextField`, `TextArea` (and so `PasswordField`, `SearchField`) | no `HAS_POPUP`: that state says activating the control opens something, which is the combo's, the menu title's, the colour button's and the `ContextRegion`'s fact and not a plain field's; a context menu is the `SHOW_MENU` verb alone. Only the AT-SPI bridge mapped the bit, so the macOS probe saw it in the dump and never in speech |
| `Dialog` | the painted title `Label` is ignored: the `DIALOG` node carries the title as its `CONTENT` name where every platform reads one, and a heading beside it was the one fact heard twice. Ignored rather than `LABELLED_BY`, because a relation keeps the second node and moves the name to the *label* attribute on macOS, which the panel's own step had refused. Still duplicated, and open: the message is both the node's description and a `label` child, and in native mode the window node and the `DIALOG` node both carry the title (§1.11's two-node mounting) |
| `ImageView` | the row above says "ignored when it has neither" and the source says the opposite, and the source governs: a nameless picture is published as `IMAGE` with an empty name, on purpose, so that the invariant catches it and the application decides — a class-level ignore would silently overrule the application's name, caption and role, and on a photograph that has not loaded would make the node appear and vanish as pictures arrive |
| `TextField` trailing button | the setter takes no label, so the promised node has no name; §8 adds one |
| `ListView` | the row above — "a row also needs `ActionFacet{PRESS}` mapped onto `ListView#activate()`; selection without a verb is not usable" — is **this list's own `ContextRegion` defect, two lines below the entry that records it**, and §7's `ListView` row is wrong or silent in six places against the source besides; each is pinned by `limn.components.ListViewAccessibilityTest`. **Corrected by the step.** *The verb on a row is undispatchable:* a row is a **widget** child, so the walk records the application's own cell as the node's owner and the scene dispatches strictly there with no fallback to a parent — a child key changes identity, never ownership — where the hook is `Widget`'s and answers false. It is worse here than for the region, because `Host#perform` has already returned true to the platform from its snapshot check, so the platform is told the action was accepted and nothing happens: a silent failure rather than a reported one, and §1.5's settled rule ("every `onAccessibilityChild` in the toolkit writes facts; none writes a verb") is what the row breaks. *And "mapped onto `activate()`" is the wrong call even where it could be delivered:* `activate()` fires `onActivate(selectedIndex)`, so a press on row seven while row three is selected opens record three; the faithful analogue of the pointer gesture is select-then-activate, which is what a verb acting on the selection gets for free. So `PRESS` goes on the **list**, which owns it and can perform it, and only while `selectedIndex >= 0`, because a verb that can only no-op is what the scroll bar's step refused. *The row is silent on where a row's name comes from, and the common case has none:* a cell is an application's widget, the demo's paint their own text over five thousand rows, and the `LIST_ITEM` this hook writes **suppresses** §1.6's paints-and-says-nothing warning that would have been the application's only notice — so without asking the adapter the flagship case publishes five thousand nameless list items in silence. `Adapter#rowName`, added by §8 and until this step called by nothing anywhere, answers it, and its javadoc's "when its own widget is not mounted" was too narrow by exactly that case: it is asked once per realized row whose cell declared no name, and its contract gains the sentence that makes it affordable — hand back a string the adapter **holds**, because the comparison is by reference and one built per call allocates per damaged frame and republishes the tree every frame (**corrected 2026-09-14, D4:** the second half was false — the difference compares the resolved text, so equal text built afresh is no change and raises nothing; what a per-call string costs is one allocation and one resolution per realized row per walk, the zero-allocation promise broken and nothing else. `Tree.Model#nameOf`'s copy of the claim is the Tree lane's). *And silent on where an unmounted selected row's name goes*, which is the gap `rowName` was invented for: the list node's **description**, and only while that row is unrealized, since a realized one carries the name and `SELECTED` itself and a second copy is §1.7's twice-spoken name. Not `valueText`, which is written and dropped without a value facet, as the colour picker's step settled; and not a synthetic phantom row, which is declared before the widget children and would put a selection below the viewport ahead of every realized row. The known cost, written down: while that description stands, the walk's tooltip-as-description default has nowhere to go on a list carrying both an application name and a tooltip. *"`SelectionFacet`" does not say which shape, and the naive reading is wrong:* `required` is **false unconditionally**, not the tab strip's `selected >= 0`, because this class documents the opposite invariant to the combo's and the pane's — "a list is one of the two widgets in this set that genuinely has no-selection as a state", `clearSelection()` reaches it and `refresh()` returns to it on an emptied adapter. The active descendant is not declared, as everywhere: it is resolved in the copy from the first `ACTIVE` node in the subtree, and here the selection **is** the cursor — one highlight, unlike the combo's, with focus staying on the list while it moves — so the selected row is marked `ACTIVE` as well as selected, which the tab header's step refused for a reason that does not hold there (roving focus had already made the selected header the focused node). The model's known cost comes with it unchanged: a list nested in another list's row cell hands the outer list the inner one's selected row as its own cursor. *And silent on the scroll facet's derivation and on the half-point slop:* the facet is published unconditionally, as the scroll pane's and the strip's are, so a resize past the fitting point moves two booleans rather than making a facet appear and disappear — but with **no** slop, unlike the strip's, because this widget's own wheel gate and `scrollBy` clamp use the bare subtraction and really do move a list overflowing by three tenths of a point, where the bar declines to exist at all; a slop-gated boolean would advertise a refusal the widget does not make. No orientation, unlike the slider's and the strip's: a list is vertical by default on all three platforms, its value has no axis to run along, and the nearest widget in the toolkit — the combo's popup panel, also a vertical `LIST` with a scroll facet — declares none. *And the open bounds item, "`ListView`'s mounted cells", is now asked and needs no correction:* `placeDown` lays every cell out with `layoutBox` and `scrollBy` calls `moveChild`, which writes x and y immediately rather than deferring an offset to the next layout, so the free box is the painted rectangle at every instant and none of the menu column's bug is present; the default clip is the whole box, which is exactly what `paintChildren` clips rows to, so unlike `ScrollView` this widget owes no per-child clip accessors, and under a reserved strip the rows are laid out narrowed to the viewport and never extend into it. Two consequences are kept rather than fixed: a row scrolled half off the top publishes a box whose top is above the list's own, because that is where the row is and `SHOWING` is what says how much of it is on screen; and the selection ring is painted across the list's full width, which under a reserved strip is wider than the cell — a mark and not a node, so the row's box stays the box a click lands in. **What is in no row at all, and no existing test could have caught:** `children()` fell out of data order on any upward scroll. `Widget#add` appended, and the anchor's renormalisation mounts the rows **above** the anchor walking upward, so a hundred-point list scrolled down three rows and back left `children()` holding 3, 4, 2, 1, 0 — and `children()` is reading order and Tab order both, so a reader heard "4 of 5000, 5 of 5000, 3 of 5000" and a Tab through rows holding buttons walked them the same way. `AccessibleFocusOrderTest` cannot see it, because both sides of that invariant read `children()`. `Widget` gained `add(int, Widget)` with `add`'s existing guards — re-adding to reorder is not the alternative, since `remove` detaches the subtree and revokes the focus, hover and press inside it — and the list mounts into the position that keeps the bar at zero and the cells ascending. Its map of mounted cells became two parallel arrays in data order at the same time, which is what makes the insert position free and what lets both describe hooks run under the zero-allocation rule: a `Map<Integer, Widget>` boxes its key for any row above 127 and allocates an iterator per sweep, on hooks that run on every damaged frame |
| `ListView` per-row actuation | **raised rather than slipped in.** A reader cannot select or activate a *specific* row, and buying that back is bigger than a describe step, so it is recorded here for the owner. Two routes exist. **(a) A model change** (§1.3, §1.9): an `Accessibility#delegateActions()` callable only from `onAccessibilityChild`, after which the walk records the **parent** as the node's owner and the child key as the synthetic key, so a row's verb reaches `ListView#onSyntheticAction(rowIndex, …)`. It has a real conflict to settle first — a cell that is a `Button` declared `PRESS` in its own hook and would have it stolen — and §7's own preamble says the model is a specification that cannot be corrected one component at a time. **(b) A structural change**: a private `RowHost extends Widget` wrapping each adapter cell, pooled with it, so the row node belongs to a class of the list's own and can reach `selectClamped`/`activate` directly. §7.1 refuses "a synthetic row node above its mounted cell" for a reason that does not cover a real widget wrapper — a synthetic node cannot have widget children and a widget can — so this is genuinely open, at the cost of one widget and one layout level per visible row, a change to `recycle`'s contract, and its own tests for pooling and focus containment. The step took neither and shipped the complete, dispatchable mapping instead. What a reader has meanwhile is in §11. **Amended 2026-09-14:** route (a) taken, in per-verb form (decision 7) — `Accessibility#delegate` from `onAccessibilityChild` claims one verb at a time, the walk keeps the child as the node's owner and records the container as that verb's performer, and the scene routes it to the container's `onAccessibilityChildAction`; the conflict named here is settled by refusal rather than by theft — a cell that declared `PRESS` keeps it, and a container that delegated the same verb is refused by the walk. Every row carries `SELECT` now; §1.5's amendment of the same day has the rule and §11's bullet is reversed |
| `Spinner` time mode | the region test is about pointer hit-testing and does not settle what a reader is offered |
| `VideoView`, `ProgressBar` | a continuously-advancing value must be published rounded (§6) |
| `BackdropPanel` | it paints, alone among the twelve classes the scaffolding row lumped it with, so §1.6's paint warning fires on it: a toolkit class named in an application's log, recommending a flag that would delete the controls under the glass. §1.6 gained the decoration seam; the row gained one of its own |
| `Stack` | its children overlap by construction, alone among the twelve, and its own documentation calls it the base for overlays and modal dialogs. The verdict is right and the deletion costs nothing; what the scaffolding row hides is that every layer is then published as a reachable sibling of every other, so a scrim and a panel built by hand inside one leave the whole background operable to a screen reader and §1.9's gate accepts the invocation, because the control really is enabled and really is inside `inputRoot()`. §1.13's subtraction is computed from the scene's overlay stack and cannot see this. Occlusion is deliberately not modelled: §11 gained a bullet saying so and what to use instead, and the class documentation points at it |
| `MediaControls` mute button | the row said its icon buttons are "named by tooltips that already flip with the play state". The mute's name follows `muted`, which three paths write — the setter, `setVolume` reaching zero, and the volume slider's own change — and only a press on the button is one of them; a drag to zero renames it with nothing pressed. And "already flip" was true one frame late: the tooltip was written only inside the bar's `refresh()`, from the paint and the poll, and the tree of a frame is published before its paint, so the frame that reflected a change carried the old name and the new one arrived a frame later with its event. The bar writes the tooltip wherever `muted` moves now, and `Widget.setTooltip`'s own equality guard is what keeps that free. The row also does not decide `BUTTON` against `TOGGLE_BUTTON` for a two-state control; the source settles `BUTTON`, because the label is a flipping verb and a pressed bit beside "Unmute" would be heard twice. What the step found outside the widget: the walk's tooltip default and the application's name override were resolved outside the widget's locale scope the hooks run in, so a tooltip-named control in a subtree declaring another language recorded that language and spoke the process's; the walk opens the scope around its own naming now |
| `Slider` | the row said the hook "reaches the private from-user path" as if that were one call, and the source has two: `apply(raw, true)` fires `onChange` when the value moves, and every handled key then fires `onCommit`, moved or not. A hook that reached only the first would have left the toolkit's own consumer broken: `MediaControls`' scrub bar sets its drag flag in `onChange` and clears it only in `onCommit`, so a reader's `SET_VALUE` would have frozen the thumb off the playhead for the rest of playback and seeked only by throttled keyframe. The hook fires both, and a step at the end of the range commits the value held, as End at max does. `ValueFacet{min,max,step}` did not say which step: the field is `0` on a continuous slider while the keyboard nudges by one percent of the range, and the source settles that the facet carries the field, since it is the grid a set snaps onto, while `INCREMENT` and `DECREMENT` move by the nudge, in the sense Up and Down have, so they never mirror under a right-to-left layout where Left and Right do; the page step has no verb. The row was silent on orientation, which the class fixes as `HORIZONTAL`. `setStep` did not publish: it re-snapped through `apply`, which returns before invalidating when the value already sits on the new grid, so the step field changed and the tree never learned it; the setter flags the tree directly now, because a grid change paints nothing. And what nobody had written down: until this step a `Slider` was a focusable widget with no role, published `UNKNOWN` under its tooltip name with §1.6's once-per-class warning, which the transport's test had mistaken for a named group |
| `TokenBox` | it is not a `SizedBox` subclass and shares no code with one: its whole ancestry is `Widget`, and it only documents that `null` means unset as `SizedBox.UNSET` does, so it can be settled independently of `SizedBox` and of its three `Token*` siblings, which really do extend `Row`, `Column` and `Padding`. The verdict is right and the deletion is geometrically a no-op: `onLayout` hands the child the whole box at the origin, so the surviving node's rectangle is the deleted node's rectangle exactly, and "the tree is the controls, not the boxes" is backwards for it. **Corrected:** that is not unique to it. `SizedBox` and `Expanded` carry the identical `child.layoutBox(0, 0, width(), height())` and are named in the same row, so three of the twelve share the property; what separates them is where the box's number comes from — a fixed one, a flex share, a resolved token — and each therefore still owes its own step — the box a reader is given for a colour picker's rail *is* the wrapper's. And what it guarantees is *when* the extent is read — inside `onMeasure`, against the resolved step, with a parent in place — not what the extent returns: unlike `TokenPadding`'s closed `Tokens.Role`, the `Extent` is an application-supplied function that need not read its argument at all, so a row promising the published box follows the density ramp overclaims. The extent is a request besides, clamped by the incoming constraints and discarded outright by the tight root |

| `DonutChart` | the family row was written for a Cartesian chart and is wrong about the donut against its own source. **Corrected by the step.** *One node per series:* the donut draws its first visible series as the ring and leaves any other undrawn on purpose, so a node per series would publish a series that is nowhere on screen, while the things the widget paints, names in its legend and lets a click operate — the slices, one per category — went unmentioned; the children are the legend entries, keyed by category index, which is stable under `setSliceVisible`, `setSliceColors`, `setValues`, a rename, hover and animation, and re-means under `setLabels` exactly as a list's data index does, as a rename and never a re-key. *Named by `nameSource()`:* a slice is named by `labelSource(i)`; the series name is the tooltip's heading in this widget and is the chart node's description, or its name when there is no title. *A generated description:* a donut has no axes, and a string built in the hook has no source to compare, so it would allocate on every damaged frame and fail the publish-cost gate; the source offers the ring series' name by reference instead. *No action:* a click on a legend entry reaches `toggleLegendEntry`, which for a donut flips the slice, and §1.6 forbids deleting an operation with the box that carried it, so each slice node carries a toggle facet — read from the hidden-slice bit and never from the eased weight, which would republish on every frame of the fade — and offers `TOGGLE` only while the legend is interactive, because the pointer's path requires that flag; the setter that flips the flag paints nothing and now flags the tree itself. *Geometry:* the row says nothing, and the slice boxes are the legend boxes, written by the region pass before every paint and every pointer test but not before the publish step, which runs after layout and before paint, so a hook that read them as they stood would publish the previous frame's rectangles, and none at all on the first frame; the base now recomputes them on every layout and the hook re-runs the pass only when the legend's own cache says its entries changed, which is what keeps a quiet damaged frame free under a ruler whose shaping allocates. The row also names one hook for five classes; the coverage ratchet strikes a class only for a hook it declares itself, and a hook on `Chart` would have described the bar and the line chart before their own steps, so the hook is on the donut and `Chart` gained two read seams and nothing else. What a reader still loses is recorded in §11: the arcs are not nodes, so `onPointClick` is unreachable from a reader, and the numbers are not published |
| `BarChart` | the family row is wrong about the bar chart in six places against its own source. **Corrected by the step.** *One hook for five classes:* a hook on `Chart` would lie for the donut, whose legend entry is a slice, and one on `CartesianChart` would describe `LineChart` by inheritance before its step, which the coverage ratchet counts as a deferral nobody decided; the hook is the bar chart's own. *A description generated from the axes and series:* nothing in the chart, the Cartesian base or the axis holds such a string, an axis title is a plain `String` with no source behind it, and a string built in the hook has no source to compare, so it would allocate on every frame of the entry animation and every hover move and fail the publish-cost gate; nothing is generated, the walk's tooltip default and `setAccessibleDescription` remain. *Silent on the legend:* a click on a legend entry hides or shows a series while the legend is interactive, and §1.6 forbids deleting an operation with the box that carried it, so each series node carries the visibility bit as a toggle and offers `TOGGLE` through the same `toggleLegendEntry` the click reaches, only where a row exists and the flag is set, because that is the pointer's own precondition; the application is told nothing by either, which is what a legend click tells it. *No key named:* the casual key, the index, is not stable under `removeSeries`, which moves the second series onto the first's identifier; `ChartSeries` carries a serial for the life of the object, so a removed neighbour destroys one node and renames none. *No bounds:* the source has two per-series geometries, the bars, which fold the eased value in and move on every animation frame, and the legend row, which does not; the row is the published box, brought up to date by the seams the donut's step added, and a series with no row keeps the chart's box, because unlike a slice a lone series is drawn and is a node. *Silent on orientation:* the class exposes it, and the chart node carries `HORIZONTAL` or `VERTICAL` for the direction its values run. One thing the source settles that the mapping had not read: only a stacked chart fades a hidden series out of its column; grouped, the slot is gone on the next paint, so the fade that must not be published is a stacked chart's |
| `LineChart` | the mapping this step was handed had the line chart's source open and was still wrong in four places against it, and each is pinned by `LineChartAccessibilityTest`. **Corrected by the step.** *The hook on `CartesianChart`, to strike two names at once:* the coverage ratchet counts only a hook the concrete class declares, and the bar chart had already been described on its own by the time this step ran; the hook is the line chart's own, and the body it shares with the bar chart, which is identical fact for fact, lives on the Cartesian base as a package-private helper both hooks hand their builder to, so neither class keeps a verbatim copy and the base still declares no hook. *A series keyed by its index:* `removeSeries` renumbers the tail and would hand a reader the identifier of a removed series' neighbour; the key is the serial the series carries for life, as the bar chart's step settled. *A series with no legend row placed at the plot rectangle:* the plot is resolved by the paint and the pointer test, never by layout, so the hook would have had to resolve the scale and lay the plot out itself on every damaged frame to publish it, and a line chart is damaged on every frame of its entry animation and every hover move; the chart's own box is the family's answer, is honest about where a lone line is, and is free. *No state:* `setHorizontal` is public on the line chart too and its own point geometry turns with it, so the node carries `HORIZONTAL` or `VERTICAL` as the bar chart's does. The seams the mapping asked for, the entry count, the row bounds and an invalidation from `setLegendInteractive`, had already been added by the donut's step, and it was right about the rest: the title by reference, one node per series in paint order, the visibility bit as the toggle, the verb only where the pointer's gate is open, no generated description, and the marks deferred, for which the source gives the sharper reason that the five point buffers hold one series at a time and are keyed on the animation's progress, so a mark box would be an alias that moved on every frame of an entry nobody is touching. What a reader still loses is recorded in §11: the points are not nodes, so `onPointClick` is unreachable from a reader, and the values, the gaps and the axis ticks are not published |
| `TabbedPane` tab header | the row has the role, the facet and the name's provenance right and is wrong or silent in five places against the source, each pinned by `TabbedPaneTabHeaderAccessibilityTest`. **Corrected by the step.** *`ActionFacet{SELECT}`:* the widget has two distinct user gestures and both land in the private `selectTab`, the arrows selecting and staying on the strip while a left click and Enter select and move the keyboard into the panel; one verb would publish half of that and leave `accessibilityPerformPress` and `IInvokeProvider::Invoke` with nothing to route to, so the node offers `SELECT`, the path `setSelectedIndex` takes, which changes the tab and leaves the keyboard where it was, and `PRESS`, the click's own path, which changes it and dives into the panel. *"It reads that title from inside its own package and needs no new accessor":* the caption is the header's own private field and not a neighbour's, and the accessor the row defers to ADR 040 is already public on the pane, so §8's "it gains `tabTitleSource(int)`" was past tense before this step began. *§1.5's illustration that "`TabbedPane` numbers its headers" through `onAccessibilityChild`:* a header's parent is the strip, so the pane's child hook is never invoked with one, and the header holds its own index and reads the selection and the count off the enclosing instance, so there is nothing for any parent to hand down; a numbering hook would have to be the strip's. *Silent on the active bit,* which the standing "selection and cursor are one thing" rule would put on the selected header: roving focus makes that header the focused node whenever the strip owns the keyboard, so the bit would restate what the walk publishes for free, and a container takes the first active node anywhere in its subtree, so a pane nested in a list cell would hand that list one of its tabs — the hijack `RadioButton`'s step refused for the same model reason. *Silent on overflow,* which this list left open as a bounds question and which splits in two: the box needs no correction, because headers are real widgets the strip places with `layoutBox` and `scrollStripBy` moves them immediately rather than deferring to the next layout, so the free box is the painted rectangle at every instant; what the row misses is that a scrolled-away header publishes without `SHOWING` for free, from the clip walk `isShowing` already does, and §1.9's gate then refuses its `SELECT` — so the tabs a reader most needs are exactly the ones it cannot operate where they stand. No verb rescues them from here: `SCROLL_INTO_VIEW` is the walk's and is granted only to a focusable widget, and an unselected header is not one, so manufacturing one for this widget alone would invent an asymmetry the walk deliberately does not have. The two routes back are in the source — selecting a visible neighbour reveals it, and the list chevron opens a menu of every tab — which makes `StripButton`'s own step load-bearing, and there §1.5's claim that "the tabbed pane's strip buttons" are named by their tooltips is false against this source: the constructor sets a cursor and nothing else, so all three are nameless today. Also no relation, for a source-level reason rather than a stylistic one: `MEMBER_OF` would restate the tree the strip already makes, and a `CONTROLLER_FOR` the panel resolves to the nearest published ancestor of a content widget that is usually a `Padding` or a `Column` §1.6 deletes, so it would climb past the content and name an unrelated container — a confidently wrong relation rather than none. That pair was settled one row down, by the pane's own step: `MEMBER_OF` stays refused for the reason above, and `CONTROLLER_FOR` is published now that a panel declares `TAB_PANEL` whatever an application handed over and is therefore a node the link can land on |
| `TabbedPane` tab strip | the row has the role and the em-dash under synthetic children right, and is short in three places against the source; each is pinned by `TabStripAccessibilityTest`, and the mapping the step was handed was wrong in a fourth. **Corrected by the step.** *The facets column names only `SelectionFacet`:* the strip is a clipped viewport that `implements Scrollable`, carries a wheel handler and a `revealRect`, and is moved by two chevrons and by every selection's reveal, so it owes a `ScrollFacet` — the same omission this list already records for a segmented control's overflow and for the combo's popup panel. It is published unconditionally, as the scroll pane's is, so that resizing past the fitting point moves two booleans rather than making a facet appear and disappear; and it is derived from the widget's own `overflowing` predicate rather than from the subtraction alone, because that predicate carries the half-point slop below which every scrolling path here declines, and a strip within half a point of fitting must not advertise a movement it refuses to make. The percent is the offset over the maximum offset and is published **unflipped** right to left, the answer the scroll pane's step settled and for the same reason: the offset is a distance from the leading edge, mirroring lives in where the headers are placed, and a flipped percent would say a right-to-left strip resting on its first tab is scrolled to the end. *Silent on orientation,* which the class fixes as `HORIZONTAL` — one run at every width and in both directions, mirroring reflecting the run without turning its axis — exactly as this list records for the slider. *Silent on why the node exists at all,* which is the only hard question the widget raises: the strip declares nothing today, is never focusable and has no synthetic children, so §1.6's predicate deletes it and hoists the headers, and three facts refuse that — a tab list is the container a position in a set is read against, a selection change is addressed to the moving member's *published* parent so a deleted strip raises it on the window node, and the strip's own box under overflow is the one thing that tells a reader where the tabs are. (**Amended 2026-09-14:** since §1.10's selection amendment of that day a selection change is addressed to the member's *container* — the nearest ancestor carrying a `SelectionFacet`, climbed to through synthetic ancestors only — and no longer to its published parent; for a tab header the two are the same node, the strip, so `TabStripAccessibilityTest`'s pin stands unchanged, but the reason the strip must exist is now that it carries the facet the climb stops at: a deleted strip would leave a header with no container and no event at all, not one raised on the window.) **And the deletion would have been silent**, even though the widget paints information: `Widget#paintsItself()`'s `ClassValue` looks only for a declared `onPaint(Canvas)`, and the sliding selected-tab indicator is drawn from `onPaintOverlay`, so §1.6's paints-and-says-nothing warning never fires here — the accidental escape this record notes for `SplitPane`'s panes, hit by a class that really does draw. *What the mapping got wrong:* it asked for a second hook, `onAccessibilityChild`, marking the selected header `ACTIVE` so that the selection facet would have an active descendant. That is the same bit on the same node the tab header's own step had already refused, and neither reason has moved — only the selected header is a tab stop, so it is already the focused node whenever the strip holds the keyboard, and the publish step takes the first active node *anywhere* in a container's subtree, so a pane nested in a list cell would hand that list one of its tabs as its own cursor. The facet's active descendant stays zero and the step writes one hook. It is not thereby inert: single-selection and always-selected-while-there-are-tabs are the container's own shape, which is what the platforms' selection patterns read, and `required` is `selected >= 0` and not the combo's unconditional `true` — a combo may write that because its constructor refuses an empty item list, and this pane documents the opposite. *And the open bounds item, "the tab strip under overflow", is now asked and needs no correction:* the pane lays the strip out as exactly the viewport between the three overflow controls, so the walk's free box is the viewport by construction, and `scrollStripBy` re-runs `layoutBox` on every header the moment the offset moves rather than painting them at an offset, so none of the menu column's bug is present. Two exact invariants come out of it and the test pins both: the pane's width less the strip's is three times the strip's x left to right, and one and a half times it right to left |
| `TabbedPane` itself | "the pane itself is scaffolding" is the right verdict and the wrong framing: it reads as no code at all, and the class owes two things, both pinned by `TabbedPaneAccessibilityTest`. **Corrected by the step.** *It paints:* a hairline rule under the strip, so §1.6's paints-and-says-nothing warning fires on the pane the moment the predicate deletes it — a toolkit class in an application's log, recommending `setAccessibleIgnored(true)`, which here takes the tab list, the chevrons and every panel out of the tree together. That is the `BackdropPanel` case for the third time after `Dialog`'s scene overlay, and it takes the same seam. *It is the only object that knows which panel belongs to which tab:* a panel is whatever an application handed `addTab` and knows nothing about tabs, and a header's own parent is the strip, so no other hook can reach the pairing. So the pane is deleted and still carries an `onAccessibilityChild`, the `Dialog$ActionRow` shape, which writes onto the child's open slot and says nothing about the pane. *And the row for the selected content is wrong to give the pane both the role and the name outright:* the child hook runs after the child's own and before an application's overrides, so an unconditional `TAB_PANEL` replaces a `ScrollView` panel's `SCROLL_PANE` — deleting the `ScrollPattern` its own step settled — and an unconditional name replaces a `Label` panel's own caption. Both are conditional on the panel having declared neither; the `LABELLED_BY` is unconditional, because it is true whatever the panel turned out to be, and its mirror is the `CONTROLLER_FOR` the tab header's step had to refuse for want of a node to land on. The row is also silent that the role applies to **every** panel and not only the selected one: publishing a panel only while it is selected would raise a structure change and a destroyed node on every tab click, where §1.2's inherited `VISIBLE` already makes a hidden panel announce as not on screen for free |
| `ColorPicker` | the row is `COLOR_CHOOSER` and two dashes; the role is right, the dashes are right for the picker's own node, and it is wrong or silent in five places against the source, each pinned by `ColorPickerAccessibilityTest`. **Corrected by the step.** *It does not say the picker is focusable, and its own verdict depends on that:* `setFocusable(true)` in the constructor is the only reason the node survives §1.6's predicate, since the widget declares no name, no description, no action and no state of its own and overrides no `onPaint` — every pixel is drawn by four inner classes — so without it the chooser would be deleted in silence and its children hoisted onto the window. The role is therefore not a choice the row offers but the difference between a colour chooser and an `UNKNOWN` control naming a toolkit class in an application's log. *It reads as a leaf, and the widget is a twenty-node composite:* three painted parts, a caption and a hex field, a `TAB_LIST` of three `TAB`s, three panels of caption/rail/stepper triples and an alpha triple. The dashes under facets and actions are right for a reason the row does not give — the answer lives in the fields below, which are real widgets with nodes of their own, so a copy of it on the chooser would publish one fact twice. *It hides the only real work, which is that three of its controls cannot be named at all:* the channel steppers, the alpha stepper and the hex field are general-purpose components, their letters exist only in the sibling captions, and no `onAccessibilityChild` can ever reach them, because the walk offers a child to its **direct** parent alone and the picker's only direct child is the root column. So the naming is the constructor's: each caption takes its rail through `setLabelFor` and its stepper through `setAccessibleLabelledBy`, both handing over the held `I18nString` by reference under the subtree's language, both published `NameFrom.LABEL` with the relation back, and both leaving `setAccessibleName` free for an application; the hex field carries a word of its own instead, because the "#" beside it is a mark a reader speaks as "number sign". One consequence the row could not have foreseen: a named control survives the predicate whether or not it is focusable, so an unselected notation's lines now stay in the tree without `VISIBLE` and a tab click moves which of them are `SHOWING` and destroys nothing, where nameless rails were deleted and rebuilt on every click. *It does not say where the picker's name comes from, and the answer is nowhere:* no title, no tooltip, no placeholder, and the one place in the toolkit that builds a picker named the dialog around it and never the picker, which is where focus actually lands — **corrected 2026-09-07 by the gallery test's first run:** `ColorPickerButton` now names the picker after the dialog's title, by reference, so a language move re-resolves both. *And the neighbouring `ColorPickerButton` row's instinct, "value text is the hex", must not be carried across:* `Accessibility#valueText` writes the text and leaves `hasValue` false, the facet is built only where a number is present and `declaresNothing` does not count it, so such a text is dropped without a word; and the difference raises a value change only when a number moves, so even surviving it would republish the tree on every drag step and emit nothing. A colour has no honest scalar to pair it with. Two more owed here: the `ColorPicker.HueRamp` correction above is one of **three** — `SaturationValueField` and `Preview` extend the same `Painted`, are likewise never focusable, likewise override `onPaint` and likewise draw information rather than decoration, so all three are deleted by the predicate today and all three trip the paints-and-says-nothing warning, each pending its own step; and `ColorPicker#onKeyEvent` carries no `isEnabled()` guard, unlike `Rail#onKeyEvent`, and is safe only because `Scene#requestFocus` refuses a disabled widget — nothing here depends on that, since the picker declares no verb, but it is the trap waiting for whoever gives the saturation/value plane one. **Amended 2026-09-16 (decision 69):** the picker's own row keeps its two dashes — it publishes no synthetic child of its own — but the decision is heard here more than anywhere else in the toolkit, because the eleven steppers are eleven `Spinner`s and their twenty-two arrows now go quiet at a bound: an arrow that cannot move its channel any further publishes without `ENABLED` and with no `PRESS`, which is `Spinner`'s row above and needs nothing from this widget. It is not cosmetic here. An RGB channel at 0 or 255, a hue at 0, an alpha at 100 are ordinary resting states of a colour a user just picked, so on this screen a reader was being offered dead presses most of the time; `VerbPolicyRatchetTest` finds three of its gallery entries ("Colour picker", "Colour picker button, open", and the spinners page) red the moment the narrowing is backed out |
| `ColorPicker`'s rails | the row has the role and the two verbs right, and is wrong or silent in seven places against the source; the two concrete classes are pinned by `ColorPickerAlphaRailAccessibilityTest` and `ColorPickerChannelTrackAccessibilityTest`. **Corrected by their steps.** *One row for three classes:* `Rail` is abstract and is not in the coverage list at all, and its two subclasses carry different facts — an alpha snapped to a whole percent in the widget's own model, a channel in the units of the spinner beside it and adopted through `write()` under each notation's rules — while the ratchet counts only a hook a concrete class declares itself, so a hook on the base would have described the second rail by inheritance before its own step and left its name on the undescribed list while it behaved as described. Each declares its own and `Rail` stays hookless. *`LABELLED_BY` "declared by the picker" is impossible:* `onAccessibilityChild` is invoked on a widget's direct parent and a rail's parent is the `Expanded` that gives it the line's remaining width, so a hook written on the picker would never run; the relation and the `LABEL` name both come from the caption the constructor points at the rail with `Label#setLabelFor`, which the picker's own step had already had to add for exactly this reason. *`ValueFacet` does not say which numbers, and the widget offers two:* `fraction()` is the paint's `[0,1]` coordinate, and publishing it would tell a reader "0.2 of 0 to 1" while the stepper on the same line says 51; the channel facet is the spinner's own value, minimum and maximum, read off the object that clamps a set rather than re-derived, so it is 0–255 for a red, 0–360 for a hue and 0–100 for an ink and moves under the reader when the notation tab does. There is no value text on either rail: the number is the whole of it, the screen shows no unit, and a string built in the hook would have no source to compare and would cost one allocation per damaged frame on a widget whose focus fade damages it on every frame it runs for. *Silent on `SET_VALUE`,* which the writable facet advertises on every platform (§1.9) and which is the only verb here needing real work, because neither rail has a set-by-value path: the argument arrives in the domain the facet published and is turned back into a fraction before `moveTo`, so it lands on the same grid a drag lands on and the rail and the number beside it cannot end up disagreeing; a non-finite one is refused before the clamp, which would pass it through. *Silent on orientation and on mirroring, in the one widget whose documentation is about mirroring:* the class fixes `HORIZONTAL`, and `Rail#onKeyEvent` turns its Left and Right arm round under a right-to-left layout while leaving Up and Down alone, so the two steps — which are the Up and Down sense — must never mirror, and a hook built from the arrows would run backwards in Arabic and Hebrew. *Silent on the commit,* which this widget fires from a pointer release alone: its own keyboard commits nothing, and the verbs deliberately do not inherit that, as `Slider`'s step settled, because a request from a reader is a whole gesture with no release to follow and a step at the end of the range is a user choosing the value held. *And silent on the ten-node tree, which is the fact that decides how the channel rails are described at all:* the pane keeps every panel and lays out only the selected one, so ten `SLIDER` nodes are published at once and the seven belonging to the unselected notations carry neither `VISIBLE` nor `SHOWING` nor `FOCUSABLE`, hold the numbers their notation was last synchronised with, and have never been laid out, so their boxes are empty. None of that is to be "fixed": describing a rail only while its tab is selected would destroy and rebuild seven nodes on every tab click, §1.3's identity churn, and it is §1.9's showing gate rather than the hook's own enabled guard that stops a reader editing the colour through a notation that is not on screen. *And the open bounds item, "the colour picker's rails", is now asked and needs no correction:* `Expanded` hands the rail `(0, 0, width(), height())`, `Rail#onMeasure` floors the height at the reachable hit target rather than the painted band, and the box that results is exactly the rectangle `Rail#pick` inverts — the one a pointer must hit — with the thumb's travel inset a fact about the mark and not about the node |
| `ContextMenus.ContextRegion` | the row is wrong in its verdict *and* in its mechanism, and §1.5 and §1.6 carry the same claim in prose, so all three move together; pinned by `ContextRegionAccessibilityTest`. **Corrected by the step.** *"`HAS_POPUP` and `ActionFacet{SHOW_MENU}` on its own child, through `onAccessibilityChild`" is unimplementable:* the walk records the **owner** of every published node as the widget the node came from, and the scene dispatches an action strictly to that owner with no fallback to a parent — a child key changes a node's identity, never its ownership. A verb the region wrote onto its child would therefore be dispatched to the child, an arbitrary application widget whose action hook is `Widget`'s and answers false, so the row publishes a "show context menu" that is refused on all three platforms, every time. §1.6's own rule that an operation is never deleted with the box that carried it is worse off for that than for an absent verb, because the platform reports a failure instead of an absence. The precedent agrees: every `onAccessibilityChild` in the toolkit — `TabbedPane`'s panels, `Dialog$ActionRow`'s default bit, `ColorPicker`'s rails — writes **facts**, and none writes a verb. *And "instead of costing a node of its own" (§1.5) is false in the common case on its own terms:* a state and a verb written onto the child make that child survive §1.6's predicate, so attaching a menu to a `Column`, a `Row` or a `Padding` materialises exactly the node the row claims to avoid, one level deeper, and the thing materialised is a container carrying an operation nothing can perform. *The row also never considers a child that already speaks for itself:* `attach` takes any widget, and a `ComboBox`, a `ColorPickerButton`, a `MenuBar` title and a `TextField` all declare `HAS_POPUP` already, so writing the region's verb onto one either duplicates a state or loses the region's menu behind the child's own handler. *The one true sentence in the row is the argument against it:* the region measures to `content.measure(constraints)` and lays the content out at `(0, 0, width(), height())`, so the child's node and the region's node have identical rectangles and the choice is between two boxes that are the same box, one of which can perform the action. So the region declares its own node — `GROUP` by default, unnamed on purpose, `HAS_POPUP` unconditionally and `SHOW_MENU` — and writes nothing onto its child. *`HAS_POPUP` is not conditional on the supplier answering a menu,* for `ColorPickerButton`'s reason in the same words: `source` is an application callback that *builds* a `Menu` and is contracted to be asked at the moment of the gesture, so asking it from the describe hook would run application code inside the publish step and allocate a menu per damaged frame. *Two things the row could not have known.* The class's own javadoc says the keyboard route "works from the content's own focus by bubbling", which means a region wrapping content with nothing focusable in it has **no** keyboard route at all — the demo's `MenuScene` is exactly that shape, so its context menu is pointer-only today and this node is the only non-pointer route to it, which is what raises the stakes on the verb being dispatchable rather than merely published. And `ContextMenus.showAt` documents and implements an **anchor-local** point while `ContextRegion.onMouseEvent` passes `MouseEvent#x()/y()`, which `MouseEvent` and `PopupMenu.showAt(Widget, float, float)` both document as **scene** coordinates, so the region's own pointer menu opens offset by the region's scene origin — invisible only for a region at the scene origin, recorded for `TextField` and `TextArea` as well, and outside this step because it changes pointer behaviour and owes a test of its own. It is why the accessibility route passes genuine local coordinates |

*Left to the pipeline, as work:*

- **Every row's bounds claim.** The menu column was the row that happened to be examined; the same
  question — does this widget's painted geometry match the box the tree would publish — has not been
  asked of `ListView`'s mounted cells, the chart's series, the colour picker's rails, or the tab
  strip under overflow. `AccessibleGalleryTest`'s clipping-ancestor assertion catches the gross
  version of this and not the subtle one.
- **Every row's "operable" claim.** §1.6 forbids ignoring a control that can be operated, and the
  survey found three violations of it in one pass (the chevrons, the hue ramp, the trailing button).
  There is no reason to think it found all of them.
- **Every row's name source.** The two free defaults (tooltip, placeholder) were assumed to cover the
  icon-only controls; the trailing button shows the assumption failing. Each component's step checks
  its own controls against `AccessibleGalleryTest`'s every-focusable-node-is-named rule rather than
  against this table's promise that they are.
- **Every synthetic child's key.** The table names keys casually — "keyed by index", "by its region
  number" — and §1.3 now requires the key to be stable under everything the owner does to its
  children. That is a per-owner proof, not a table entry.

**The pipeline that settles them is §14's phase 4**: for each component, in order, *map* it against
its own source, *test* it headlessly with the invariants of §12.1, then *verify* it adversarially —
someone reading the produced tree against the widget's code and looking for the row that is still
wrong. One component at a time, ending green. The table is where each of those starts.

---

## 8. What this changes in the toolkit, and why nothing is worked around

The owner knows of no consumer and a `0.x` line promises no compatibility, so where the current API
fights the tree the API changes rather than gaining a deprecated twin — the header says what that
claim rests on and what it does not. Every change below is inside this repository, and the migration
is `limn.components`, `limn-theme-editor`, `limn-demo` and the tests.

**The tooltip gains its `I18nString`, and changing it says so.** `Widget#tooltipSource()` is added,
matching the `text()`/`textSource()` and `label()`/`labelSource()` pairs `Label`, `Button` and
`MenuItem` already set. ADR 006 §5 pre-decided that accessibility text is an `I18nString`, and the
tooltip is the natural description and the natural free name for an icon-only control; without the
getter, that text cannot be re-resolved when a locale epoch moves. `setTooltip` also gains a call to
`invalidateAccessible()`, because today it neither invalidates nor relayouts and a description change
would be silent.

**Three widgets get the label accessor `Button` and `Label` already have, and one of them gets a
different type for its labels.** `Checkbox` and `RadioButton` each hold their caption in a private
`I18nString text` with a setter and no getter — the same shape `tooltip` had before the paragraph
above — so `text()` and `textSource()` are added to both, matching the pair `Button` exposes. Strictly
this ADR could do without them, because `onAccessibility` is overridden *inside* each class and can
read its own field; they are added anyway for two reasons. A caption is the thing a name is derived
from, and ADR 006 §5's rule that accessibility text is an `I18nString` is unenforceable through a
field nothing can read. And ADR 040 §6.3 names these accessors as this record's to design, because a
watcher cannot announce a `NAME` for an aspect no public getter answers (§9).

`SegmentedControl` is the harder one and the change is bigger: its segments are a `List<String>`,
taken as literals in the constructor, so a segment's name cannot follow the subtree locale the way
every other name in this design does (§1.7). It gains an `I18nString` list — a second constructor and
a `segmentSource(int)` accessor — and the `List<String>` overload keeps working by wrapping each entry
in `I18nString.literal`, which is what `Checkbox`, `Button` and `Label` already do for their `String`
constructors. `TabbedPane` already takes `I18nString` titles and its tab header reads them from
inside its own package, so it needs nothing for this ADR; it gains `tabTitleSource(int)` for ADR
040's, which cannot announce an aspect no public accessor answers (§9.2).

**The inheritance host becomes readable.** `Widget#inheritanceHost()` is added. The link is written by
every popup, menu and dialog today and read by nothing, and it is the only path from a popup root back
to its opener — which is what `POPUP_FOR` and `CONTROLLER_FOR` are built on.

**The menu model gains a radio kind and a guarded activation.** `MenuItem.Kind` gains `RADIO`, because
the tabbed pane's overflow list is a single-selection group built out of check items today, and a tree
derived from `Kind` alone would report independent checkboxes. `MenuItem#activate()` becomes reachable
from `MenuSurface` under the guard §7 states. Neither `Menu` nor `MenuItem` becomes a `Widget`: the
derivation rule in §7.1 is the answer, not a type change.

**The list adapter gains a name.** `ListView.Adapter` gains
`default I18nString rowName(int index) { return null; }`, so a selected row that is not currently
mounted still has a name to announce, and so the later virtualization work has something to build on.
**Widened by the step:** it is asked for a *realized* row too, whenever that row's own cell declared
no name — which is the ordinary case, because a cell is an application's widget and the `LIST_ITEM`
the list writes onto it takes away §1.6's paints-and-says-nothing warning that would have been the
application's only notice. Its contract gains the sentence that makes it affordable: hand back a
string the adapter holds (§7.2).

**A container gains an ordered insert.** `Widget` gains `public void add(int index, Widget child)`,
with `add`'s existing guards. `add` appends, which is right for every container that builds its
children once; it is wrong for one that mounts them on demand from both ends, and `ListView` is
exactly that — the walk that renormalises its anchor after an upward scroll runs upward and realizes
the rows above it in descending order. `children()` is reading order and Tab order both, so an
appending list published its rows in the order the scroll happened to realize them (§7.2). Re-adding
a child to move it is not the alternative: `remove` detaches the subtree, which revokes the focus,
hover and press inside it.

**The headless doubles gain a screen.** `StubWindow` and `RecordingWindow` answer a zero origin and a
unit factor today, so no headless test can assert a real screen rectangle. Both gain a settable screen
origin and logical-to-screen factor; `StubWindow` already takes a `canPosition` flag for
`supportsAbsolutePositioning()`, and `RecordingWindow` gains that too. This is test infrastructure, and
it is what makes §12.1's scale and mirroring assertions possible at all.

**Three widgets gain a name they never had, and one setter gains a parameter.** The tabbed pane's
overflow strip buttons and the search field's clear button have no text and no tooltip, so they would
be unnamed nodes; they gain localized tooltips from `ComponentStrings`, which names them for a
sighted user at the same time. `TextField#setTrailingButton` is the third and is different in kind:
it takes an `Icon` and a `Runnable` and there is **nowhere to put a name**, so no tooltip can be
supplied for it and the application cannot name it either. It gains an overload taking an
`I18nString` name. The existing overloads stay and produce an unnamed button, which
`AccessibleGalleryTest` then refuses for any scene that uses one — which is the right pressure, since
the demo is where they are used.

**`TextEditModel` gains a revision counter.** A `long` bumped by every mutating operation, with a
`revision()` accessor. It exists for §1.1's compare-before-publish rule: `text()` is
`buffer.toString()`, so deciding that a text node is unchanged would allocate a fresh `String` per
text widget per damaged frame, which contradicts §6's zero-allocation promise for a repaint that
changed nothing and would fail `AccessiblePublishCostTest`. The counter is also the cheapest possible
answer to half of Finding 10's complaint that the model carries no change record — it does not give
the insert and delete offsets, which are still computed by comparison, but it does give "unchanged",
which is what the quiet frame needs.

**Every widget that formats a string for the screen gains the counter its cache already deserved.**
`I18nString#format` is documented as never cached, and `Spinner#format(double)` runs `String.format`
through `I18n.localizeDigits`, so a formatted name or value text has no witness the walk can compare
without building it (§1.1). `Spinner` already keeps the cache — `formattedFrom`, `formattedEpoch`,
`formattedLocale`, `formattedText`, kept so `onMeasure` cannot drift from `paintValue` — and what it
gains is a `long` bumped when that cache refills, plus the package-visible accessor that hands the
tree the cached string and the counter together. The same shape goes on every widget whose node
carries a derived string, which phase 4 settles one component at a time; the rule for whoever adds the
next one is §1.1's, and `AccessiblePublishCostTest` is what refuses a widget that formats in the walk.

**`Widget#setFocusable` gains `invalidateAccessible()`, and it is the sharper of the two entry points
in this list that reach nothing today** — `setTooltip` is the other, and this one is sharper for the
reason the rest of this paragraph gives. The setter checks the UI thread, writes its field and
returns — no
`invalidate()`, no `markNeedsLayout()`, no structural funnel — unlike `setEnabled`, which invalidates,
and `setVisible`, which re-lays-out. So none of §5.3's flag-setting entry points sees it, while
`FOCUSABLE` is both a published state and the bit §1.6's transparency predicate reads: making a
scaffold widget focusable changes what the tree *contains*, and today it would do so silently, on the
next unrelated repaint. One line, and `AccessibleIdentityTest`'s second case is what fails without
it.

**`Scene#scheduleFrame()` becomes the accessibility plumbing's frame primitive.** It does not become
public; it widens from private to package-private, so `Widget#invalidateAccessible` can reach it the
way every other `Widget`-to-`Scene` funnel does. What changes is that `Host#requestRepublish`, `Host#requestRestamp`, `Scene#announce`
and `Widget#invalidateAccessible` call it rather than `requestRender()`, because `requestRender()`
sets the accessibility node flag by design and would make the re-stamp path and the announcement path
pay for the walk they exist to avoid (§5.2). `invalidateAccessible` is in that list for the other
half of the same rule: it has a flag to set and no frame of its own, and a flag set with no frame
coming is not read (§1.5). Three of the four buy the frame only while a bridge is attached and
listening; `requestRepublish` is the exception, because its caller is a bridge that has just been
asked for a tree.

**The damage funnels learn to set one more flag.** `Scene#damageWidget`, `#damageWidgetRegion`,
`#damage(Rect)`, `#requestRender()` and `#markContainedLayout` each gain a store of the accessibility
node flag. They are package-private or `public` on `Scene` and their contracts do not change; what
changes is that the funnel every repaint goes through is now also the funnel every accessibility event
comes from (Finding 6, §5.3). This is the single most important edit in the ADR and it is five lines.
`Scene` also gains a second, separately-cleared header flag, which is what makes a window drag cost a
re-stamp instead of a walk (§5.2).

**A post from the UI thread wakes the loop while — and only while — the loop is parked.**
`UiRuntime#post` and `#postDelayed` wake only from a foreign thread today, and a macOS AX callback is
the UI thread with the loop asleep inside the pump (Finding 13). The obvious repair is to wake
unconditionally, and the first draft of this ADR took it on the strength of a call-site count that
turns out to be wrong: there are 22, not 14, and the caret blink in `TextField` and `TextArea`, the
auto-repeat in `Spinner`, the media position ticks and `Scene`'s tooltip dwell are all
*self-rescheduling timers* whose whole point — `TextField`'s javadoc says so — is to let the loop sleep
between them. Waking unconditionally makes each of those write a native event from inside a drain the
loop is demonstrably already awake for.

So both paths take the same narrow mechanism instead, and it is **two edits, one in each module**,
because the toolkit must not learn what a pump is.

*In `limn-toolkit`:* `UiRuntime#post` and `#postDelayed` drop the `if (!isUiThread())` around
`waker.wake()` and call the waker every time. That is a contract change, not a refactor —
`UiRuntimeTest.postFromBackgroundThreadWakesTheLoopButUiThreadPostDoesNot` asserts
`assertEquals(0, wakeUps.get(), "UI-thread post must not need a wake-up")` today, and its name says
what it believes. The test is rewritten with the belief: a UI-thread post now always reaches the
`Waker`, and *the `Waker` decides*. That is the right place for the decision, because the runtime
cannot tell a re-entrant caller inside a native pump from an ordinary one inside a drain, and the
backend can.

*In `limn-backend-lwjgl`:* `LwjglBackend` keeps a `volatile boolean` set around
`glfwWaitEvents`/`glfwWaitEventsTimeout`, and `wakeLoop()` posts an empty event when the caller is a
foreign thread — unconditionally, because that is where the race is real — or when that flag is set.
`LwjglWindow#requestFrame` gains the same call, because today it is UI-thread-checked and does
nothing but set `frameRequested`, so a frame asked for from inside a describe pass would also wait
for unrelated input.

For a UI-thread caller the flag cannot race: if it reads `true` the caller is re-entrant inside the
pump and the wake is exactly what is needed, and if it reads `false` the loop has not yet re-checked
`nanosUntilNextDeadline()` — which returns `0` while immediate work is queued — and will see the
work. The cost is a virtual call and a volatile read on the post path and nothing else.

**`CrashPhase` gains `ACCESSIBILITY`.** A describe pass runs application code — any widget subclass
can override `onAccessibility` — and on macOS it runs inside a libffi closure called by AppKit,
where an escaping Java exception unwinds into Objective-C. Every other place application code runs is
already contained and named — `FRAME`, `INPUT`, `TASK`, `TICKER`, `DECODE`, `EVENT_POLL`,
`WINDOW_CLOSE` — and this one becomes the eighth (§3.2).

**`Checkbox#toggle()` gains the enabled guard it never had.** Finding 7 called it a trap and it is:
the only reason a disabled checkbox does not toggle today is that the scene never delivers it an
event. The accessibility path re-checks `isEnabled()` on arrival regardless (§1.9), so this is
defence in depth rather than the mechanism — but a public method that flips a disabled control and
fires the application's handler is a defect with or without a screen reader.

**`MenuItem` gains a minted `long`.** A package-private serial from one counter, assigned at
construction, so a menu row has a key of the width identity is (§1.3). Nothing in the toolkit hashes
a `MenuItem` today; the serial exists because the obvious substitute — and the one an earlier draft of
this ADR reached for — is `System.identityHashCode`, which is 32 bits, and a collision here is two
menu rows becoming one element on all three platforms rather than a slow map.

**`LwjglWindow#windowMovedTo` tells somebody.** It updates two fields and moves child popups today and
requests nothing (Finding 13). It gains a `Host#requestRestamp()`, which is what makes the re-stamp
path in §5.3 run at all; without it every screen rectangle this design publishes is wrong from the
first window drag until something else happens to repaint. On macOS it is a no-op by construction,
because that bridge publishes parent-space boxes and AppKit re-derives the screen rectangle itself
(§1.8) — which is one more thing that platform does not have to be told.

**Nothing gains a public `click()`, and no accessor is widened for the bridge's sake.** Because the
hooks are `protected` on `Widget`, every private inner widget — the tab header, the menu surface, the
combo panel, the dialog panel, the divider, the media icon buttons, the scroll bar, the colour rails —
describes itself from inside its own package and reaches its own private geometry helpers. The long
list of accessors a bridge reading components from outside would have needed does not have to become
public API, and the enabled guards and from-user paths stay where they are.

**The single-slot listeners and the silent setters change, and ADR 040 decides how** (§9). Nothing
here depends on that outcome: the events are diffs, so this bridge subscribes to no component
callback, and the actions run through the widget's own hook, so nothing needs a public mutator that
notifies. What ADR 040 changes is the *argument*, not the design, and §1.10 and §9.3 say so rather
than leaving a spent reason in place.

---

## 9. ADR 040, its companion in this commit, and which of us owes what

**This section was written when ADR 040 was still being drafted and is now a reconciliation.** ADR
040, *A handler answers the user, and a watcher hears everything*, is in `docs/adr/` as Proposed,
2026-09-03, with its own row in the ADR index. **The two records land in one commit**, each carrying
a companion bullet that describes the other as it is at that moment: an earlier draft of this section
owed ADR 040 a correction, because its status block still called this record a draft outside the
repository, and that correction is made rather than owed — nothing about either record's description
of the other is deferred to a later chore, and there is no bullet left over to fix. ADR 040 keeps the
fluent `onX` slot and narrows it to mean one thing — the application's single response to the user
operating this widget — and adds a second channel, `Scene.observeChanges`, that any number of parties
may watch and that carries **every** change to a widget whatever moved it, tagged with an `Origin` of
`USER`, `CODE` or `ADJUSTMENT`. It addresses five open items to this record — its §6.1 to §6.4 and
§6.16 — and names it throughout. §9.2 answers the first four in design; §6.16 is the one this record
answers by not needing it, which §9.2's last paragraph says. Neither record waits on the other; what
follows is what each answered.

### 9.1 The four things this ADR required, and how ADR 040 answered them

1. **One user-equivalent mutation path per value-bearing widget, reachable from inside its own
   class.** Answered, and generalised: ADR 040 §1.5 makes it one private funnel per widget taking an
   `Origin`, entered by every public and every input path, and §1.11 makes it the first of four
   obligations on a component author, checked by a table-driven test. This ADR was never blocked on
   it — `Slider.apply(v, true)`, `Checkbox.toggle()` and the text widgets' `insertText` all exist
   today, which is why §1.5 can say the widget performs its own action — and after ADR 040 they are
   one shape rather than three spellings.
2. **The convention must converge.** Answered by ADR 040 §1.2's single rule and §3.1's
   component-by-component migration.
3. **Registration must admit more than one subscriber.** Answered by the watcher channel, and see
   §9.3: this ADR still does not subscribe, but the reason is no longer that it cannot.
4. **A change that is not painted must still be announceable.** Answered as a rule rather than a
   list: ADR 040's channel is not damage-derived at all, so a change that paints nothing announces
   exactly like one that does. This ADR's own flag *is* damage-derived (§1.1), so the hand fix in §8
   — `setTooltip` calling `invalidateAccessible()` — stays needed here whatever ADR 040 does; what
   it gains is that it is no longer the only mechanism in the repository for that case.

### 9.2 The five items ADR 040 addressed to this record

- **§6.1, the five public types that are not `Widget`s.** `ButtonGroup` ADR 040 answers itself.
  `Menu`, `MenuItem`, `PopupMenu` and `Dialog` are this record's, and §7.1 is the answer: a node is
  either a widget or a synthetic child declared by a widget, `MenuSurface` declares one per column
  and one per row and reads the model from inside its own package, and none of the four becomes a
  `Widget`. What ADR 040 could not carry — a menu's highlighted row, a check item's state — this ADR
  publishes as `SELECTED` on the row and an active descendant on its column (§7; since
  2026-09-14 the surface's cursor, §1.10's reading note), diffed like everything else. The hole ADR 040 names is a hole in *its* channel and not in the tree.
- **§6.2, an assistive technology cannot set a value as the user.** Answered by §1.9: the action set
  is `ActionFacet`'s twelve parameterless verbs (thirteen since §1.5's amendment of 2026-09-14) plus the parameterised constants `SET_VALUE`,
  `SET_TEXT`, `SET_CARET` and `SET_SELECTION`, every one of them dispatched through the one
  `Host#perform` call, and §1.5's rule that the widget performs its own action means each of them
  enters the widget's own from-user funnel. In ADR 040's vocabulary they are `USER`, and they reach the
  application's handler with no further decision, which is what its §6.2 asked for.
- **§6.3, four widgets that cannot announce a `NAME`.** Answered in §8: `Checkbox` and `RadioButton`
  gain `text()`/`textSource()`, `SegmentedControl` gains an `I18nString` segment list with
  `segmentSource(int)`, and `TabbedPane` — whose titles are already `I18nString`s — gains
  `tabTitleSource(int)` for ADR 040's sake, since this record's tab header reads the title from
  inside its own package and needs no accessor.
- **§6.4, something must enumerate scenes for a bridge, and say when one is replaced.** Half of it
  does not arise here and half of it was a defect this record had too. **The enumeration does not
  arise**: this bridge is not registered per scene by a third party, it is handed to a scene by its
  own window — `Scene#bind` reads `window.accessibility()` — so a combo popup, a menu surface and a
  modal dialog each reach their bridge through the window they were given, and no registry is
  needed. **The replacement is real**, and §5.3 now fixes it: `attach(Host)` replaces any host the
  bridge held, raising `WINDOW_CLOSED` for the outgoing tree and `WINDOW_OPENED` for the incoming
  one, because a scene bound over a live window never reaches `observeWindowClosed`.

**And §6.16, the fifth, is answered by not arising.** ADR 040 records that `Widget#setLocale` moves
the language every descendant's names resolve in while its channel emits only a single coarse
`LAYOUT`, so a watcher's cached names go stale with nothing to say so, and it says the gap costs this
bridge nothing. That is right, and here is the mechanism rather than the assurance: a published name
is compared by *source* — the `I18nString` reference, **the node's own resolved locale** and
`I18n.epoch()` — and a name is carried over only when all three match (§1.1, §1.7). A subtree locale
move changes the second for every node under it, so the next publish re-resolves exactly those names
and diffs a `NAME_CHANGED` for each, with no subscription and no new marker. What ADR 040 names is a
hole in its channel and not in this tree, and its consumers are the ones who pay for it.

One further item is worth answering the other way round. ADR 040 §6.6 asks whether
notifications emitted from inside a layout pass — `ListView` mounting cells in `onLayout`, `TabbedPane` calling `setVisible` on
its overflow buttons there — should be held to the end of the pass. **For this bridge the question
does not arise**: the publish step runs after `layoutPass` (§5.3), so the tree it walks is the
settled one and a mid-pass mutation is invisible to it by construction. That is not an argument
about ADR 040's channel, whose watchers include inspectors and bindings that have no frame to hide
behind; it is one consumer reporting that it is unaffected.

### 9.3 Where the two records describe the same bridge differently, and which governs

ADR 040 §1.10 sets out how a screen-reader bridge uses its channel: one registration per scene,
receiving `(source, change)` on the UI thread and updating "the one node of its own immutable
snapshot that the change names". **That is not the bridge this record builds, and this record is the
one that owns it.** The snapshot here is rebuilt by a walk inside the frame and the events are the
diff between two of them (§1.10), for three reasons ADR 040's channel does not remove: it carries no
per-widget geometry by design, only one coarse `LAYOUT` per pass, while every platform wants a
rectangle per node; the whole tree has to be assembled anyway for `Cache.GetItems` and for readers on
threads that may not touch a widget; and §1.6's transparency predicate decides a node's *existence*
from facts about its subtree, so a single named change cannot be patched into a published tree
without re-deciding whether its subject is in that tree at all. ADR 040 §1.10 should be read as
illustrative of what its channel makes possible, not as a description of this bridge.

What this record gives up by not subscribing is small, bounded and stated in §11: a press the user
performed with the mouse raises no `INVOKED`, because it leaves no difference between two snapshots.
The two other stateless aspects ADR 040 names, `COMMITTED` and `SUBMITTED`, cost nothing at all —
none of the three platforms has an event to map them onto, and §2.4's rule is that nothing is
invented to fill a cell. If one of them ever gains one, the channel is already there and the change
is a subscription rather than a redesign.

---

## 10. Dependencies

**None is taken.** All three bridges are built from what is already on the backend's classpath.

Windows and macOS need `org.lwjgl.system` and nothing else: `APIUtil.apiCreateLibrary` and
`SharedLibrary#getFunctionAddress` to resolve `UIAutomationCore.dll`, `OleAut32.dll`, AppKit and
libobjc; `JNI.invoke*` and `JNI.call*` to call by address; `Callback` and `Callback.Descriptor` with
`APIUtil.apiCreateCIF` to build the libffi closures that become COM vtable slots and Objective-C method
implementations; `User32.WindowProc` and `SetWindowLongPtr` to subclass the GLFW window procedure —
noting that LWJGL binds the wide entry points, so subclassing does not demote the window from Unicode,
and that `SetWindowLongPtr`'s first parameter is a last-error out-pointer rather than the HWND;
`ObjCRuntime.objc_allocateClassPair`, `class_addMethod` and `objc_registerClassPair` to define a class
at runtime. All of it is in modules already declared, and the backend already ships two precedents for
the idiom: `LwjglWindow#setAboveSystemChrome` sends an Objective-C message this way, and `IoSurfaces`
calls a nine-argument C function through libffi for exactly the reason `SafeArrayCreateVector` will
need it.

Linux needs nothing at all: `java.nio.channels.SocketChannel`, `java.net.UnixDomainSocketAddress` and a
hand-written D-Bus marshaller. The spike compiled with no classpath whatsoever.

**This paragraph is also the reason the bridges are not artifacts of their own, which they briefly
were.** They were cut out so that an application would pay only for the platform it ships on, and
the thing being paid for was a native payload — the argument that made the fonts and the FFmpeg
libraries their own artifacts, where the weight is megabytes of binaries per target. There is no
payload here. Two of the three reach their platform through LWJGL's JNI trampoline and import
nothing but `org.lwjgl.system`, which the backend already declares for all six native classifiers;
the third has no native code at all. What the split actually bought was thirty-four classes of Java
moving between jars, and what it cost was an exception in the architecture check and a seam an
application had to connect by hand — which §5.1 records being found disconnected. They live in
`limn-backend-lwjgl`, the backend opens the one this machine has, and `AccessibilityBridge.NONE` is
still what a platform without one answers.

### 10.1 The evaluated alternative, with its numbers

`com.github.hypfvieh:dbus-java` 5.2.0 on the `transport-native-unixsocket` module — the Java 16+
`SocketChannel` transport, which is the same mechanism the spike uses — is 468,798 bytes across four
jars: `dbus-java-core` at 385,860, the transport at 8,048, `slf4j-api` at 69,908 and a binding at
4,982. SLF4J is not optional: `dbus-java-core`'s `module-info` requires it, so a toolkit that ships no
logging facade today would acquire one. Its own recommended transport, `junixsocket`, is worse again:
it ships a native library per platform, to pass file descriptors AT-SPI2 never uses.

For it: a maintained, tested marshaller, introspection-driven proxies, signal handling and match rules,
and roughly seven hundred lines we would not write.

Against it: 458 KB and a mandatory logging dependency for one connection, one object table, about
twenty methods and no descriptor passing — against 719 lines and 65 KB of class files with an empty
classpath, already validated by 104 offline assertions including seven golden messages captured off the
wire. It models everything through reflection and generated interface proxies, which is a poor fit for
serving a tree that changes as the interface changes, and it is on a bugfix-only support footing into
winter 2026.

**Recommendation: keep the hand-rolled client.** This repository's habit is to add nothing, and the one
dependency it added recently — LWJGL's own modules — it added to avoid writing native code, which is
the opposite trade.

### 10.2 No native shim, on any of the three

All three spikes concluded the same way, and the strongest case is Linux's: there is no C API to call.
Windows' case is that the awkward parts — a hand-maintained `VARIANT` layout, IID bytes, one call site
where the ABI leaks because `JNI.invoke*` has no matching overload — are answered better by a small
typed layer in Java than by a new binary. macOS's case is that a type-encoding mistake is now detectable
at runtime: `class_getInstanceMethod` plus `method_getTypeEncoding` let a test assert every encoding
against the AppKit actually running, which is stronger than a compile-time check against whatever SDK a
shim was built with.

The honest case *for* a shim, from the same spikes: each `objc_msgSend` shape needs its `JNI.invoke*`
letters chosen by hand and a wrong choice is a silent ABI mismatch rather than a compile error, and a
bad type encoding surfaces as a crash inside AppKit with no Java frames. The runtime-encoding
assertion above largely answers both. What it does not answer is architecture: the macOS spike is
arm64, where `objc_msgSend_stret` is never needed and would be on x86_64 (§13.15). If Limn must ship
an x86_64 macOS backend, that is the one part of this decision the evidence does not cover.

Against all of that, ADR 037 prices the alternative exactly, and ADR 028 §4 already calls a JNI payload
in `limn-backend-lwjgl` "the first native payload" there.

---

## 11. What this deliberately is not

Each item says what a blind user loses, because a deferral without that sentence is not a decision.

- **Not range-to-rectangle geometry, on any platform.** The text facet carries the string, the caret
  offset with its affinity, the selection range and the caret rectangle — which is free, because
  `caretRect` already exists for the IME. It does not answer `accessibilityFrameForRange:`,
  AT-SPI2's `Text.GetRangeExtents` or UI Automation's `ITextRangeProvider::GetBoundingRectangles`.
  *Cost:* NVDA reads a field on focus and echoes typing, but review by character and braille cursor
  routing are degraded, and VoiceOver cannot draw its cursor around a range. The seam it needs is real
  and is named: a package-visible geometry accessor in `TextField` and `TextArea` that answers the boxes
  of an offset range from the shaped line it already holds — and it must hand out the shaped line, not a
  row index, because the row origin is computed from the line's measured width. That is phase-two work
  with its own tests; promising the answer without the seam would be the worse decision.
- **Not a UI Automation `TextPattern` in the first cut.** Windows text controls expose `ValuePattern`
  only — vended from the `TextFacet`, not from a `ValueFacet` a text widget does not have (§2.1), so
  "no `TextPattern`" means reduced, not absent. *Cost:* the same character-review gap, on the platform
  where it is felt most: NVDA reads and sets the whole value and reports read-only, and cannot review
  by character or route braille.
- **Not editable text from the assistive technology beyond a whole-value set.** A set on a text field
  works; insert-at-range does not. `TextEditModel` sanitizes a single-line value — every newline becomes
  a space — so a bridge echoing a set must re-read the text rather than assume the round trip.
- **Not list virtualization.** A list reports its true row count and publishes only realized rows.
  One exception, from §13.29: the row holding the keyboard focus stays realized until the focus
  leaves it, so a reader whose cursor follows the focus is not dropped to the window by a page; a
  reader's cursor on a row that holds no focusable widget is still not protected.
  **Amended 2026-09-14 (decision 22; B8's `ListView` copy):** the second exception is the
  cursor row — while the list itself holds the keyboard, its selected row is the reader's cursor
  (the one node below the focused list published `ACTIVE`, §1.10's cursor amendment) and stays
  realized wherever a wheel or a bar drag takes the viewport, not `SHOWING`, still `ACTIVE`,
  re-realized after a `refresh()`, and released by the first pass after the keyboard leaves the
  list. So "a reader's cursor on a row that holds no focusable widget is still not protected" no
  longer holds for a `ListView`; the `Table` and `Tree` copies are their lanes'. A row a reader
  addresses with `SCROLL_INTO_VIEW` — delegated by the list on a cell that cannot take the
  keyboard, since on one that can the walk's own free verb reveals it — is revealed where it
  stands without moving the selection, and the scene gates that delegated reveal on visibility
  rather than on showing, as it gates the free one, because the not-showing row is the one the
  verb is for.
  *Cost:* arrow-key navigation works, because moving the selection scrolls and realizes; jumping to an
  arbitrary row through the reader's own list navigation does not. Publishing every index instead would
  hand the reader thousands of anonymous items with no name and no bounds, and rebuild them all on every
  dirty frame; that is worse for the user, not better.
- **Not per-row *opening* in a list.** A `ListView` row carries `SELECT` and, on a cell that cannot
  take the focus itself, `SCROLL_INTO_VIEW`; what it does not carry is `PRESS`, so a reader can select
  a *particular* row and cannot open one — `PRESS` stays on the list and opens whichever row is
  selected (§7.2). The heading and the paragraph below it were written when the row carried **no verb
  at all**, and they are kept as the record of that cut and of why it was made; the amendment dated
  beneath them is what holds.
  **As first written (superseded 2026-09-14, below).** A `ListView` row is a node with a name, a
  position in the set and a selected bit, and it carries **no verb**: a reader cannot select or open a
  *particular* row.
  The reason is the model's rather than the widget's — a row is a widget child, so the scene dispatches
  an action on it to the application's own cell widget, which refuses it (§7.2). *Cost:* three routes
  remain and they cover the ordinary use. The list is focusable and every platform delivers real key
  events to the focused control, so Up, Down, Home, End, Page and Enter all work — which is what the
  bullet above already rests on; the list node's own `PRESS` opens whichever row is selected; and the
  scroll bar's `INCREMENT` and `DECREMENT` page the viewport. What is genuinely lost is the reader's
  own "click that item" on a row it has navigated to with the reader's cursor rather than with the
  application's selection. §7.2 records the two ways to buy it back and what each costs.
  **Amendment, 2026-09-14 — reversed (decision 7).** Two facts read on the guests made the
  deferral the wrong one. NVDA 2024.4.2 speaks a list or tree row only when platform focus reaches
  the row itself (`readings/nvda-2024.4.2-uia.md` §2: an `ElementSelected` on an item while the
  container holds focus is silent, and "for NVDA to speak a row as the user moves, the provider
  has to move UIA focus to the row"), so a reader's cursor really does sit on the row's element;
  and a verb a reader sends lands on the element it addresses — `SelectionItem.Select` is invoked
  on the row, never on the list — so a verb that lived only on the list's node was one no reader
  could send for a particular row. The route taken is §7.2's route (a) in per-verb form: the
  container delegates the verb from `onAccessibilityChild` (§1.5's amendment of the same day), the
  walk publishes it on the row and the scene routes it to the container's
  `onAccessibilityChildAction`. A `ListView` row carries `SELECT` now, and a `Tree` row does while
  its selection mode is not `NONE`; `PRESS` stays on the list, for the reason §7.2 gives, and the
  rest of decision 20's row verb set is the widget lanes'. *Cost, now:* what is still lost is the
  reader's own "open this row" — `PRESS` on a row — until those lanes land; the three routes above
  still work as before.
- **Not a scroll verb on a list whose bar is hidden.** A `ListView` or a `ScrollView` whose application
  chose `ScrollBar.Policy.HIDDEN` publishes a `ScrollFacet` saying the content scrolls, with no verb
  anywhere in the subtree to move it, because the paging verbs live on the bar's node and that node is
  ignored. *Cost:* a keyboard user of the focused list is unaffected — the arrows still scroll — and a
  reader driving the scroll through the platform's own scroll pattern cannot. It is a toolkit-wide
  question about where a scrolling container's verbs belong and not one widget's to answer alone.
- **Not tables, and therefore not chart data.** A chart publishes itself and its series — for a donut,
  its slices, which are what its legend names and a click toggles. Per-mark
  geometry is private, per-series buffers hold one series at a time and are overwritten on the next
  call, so a bridge caching them would be caching an alias. *Cost:* the numbers are unreachable; an
  application can put a data table beside the chart or set a description today, and the public data model
  already supports generating one.
- **Not an occlusion model.** A widget covered by an opaque sibling is published `ENABLED`,
  `FOCUSABLE` and `SHOWING`, because nothing in a published rectangle says whether what is drawn over
  it is opaque, and deducing it from geometry is the same guess §1.11's `LABELLED_BY` rule refuses.
  *Cost:* a scrim and a panel hand-rolled inside a `Stack` are not modal to a reader — the background
  is offered as operable and the invocation is accepted — where the same panel raised through
  `Scene#pushOverlay`, or mounted in the scene by `Dialog`, carries `MODAL` and takes `ENABLED` and
  `FOCUSABLE` from everything behind it (§1.13). Anything meant to block uses those; `Stack` is a
  placement rule and says nothing about input.
- **Not automatic naming from nearby labels.** A `LABELLED_BY` relation is declared, never inferred from
  geometry. *Cost:* a label placed before a text field does not become that field's name unless the
  application says so — softened by the placeholder and tooltip defaults, which cover the common form.
  Guessing here is how a screen reader ends up reading the wrong caption confidently.
- **Not a live-region model.** `Scene#announce` exists; nothing is announced automatically. *Cost:* a
  status label that updates silently stays silent until the application adds one call.
- **Not `Invoke_Invoked` for a press the user made.** The action dispatcher raises `INVOKED` for a
  press an assistive technology performed (§1.10); a mouse click on the same button raises nothing,
  because a press that changes no state leaves no difference between two snapshots. *Cost:* on
  Windows a client that watches `Invoke_Invoked` to confirm an activation hears it only for its own
  invocations — the state changes a press causes are all still reported, so this is a missing
  acknowledgement rather than a missing fact, and the other two platforms have no such event at all.
  ADR 040's channel carries it (§9.3), and buying it back is a subscription rather than a redesign.
- **Not MSAA.** `IAccessible` is refused with `E_NOINTERFACE`, as the spike did and as UI Automation
  accepted. *Cost:* a very old assistive technology, and some automation tools, see nothing.
- **Not tooltips as nodes.** The scene paints its own tooltip and its state is private. The tooltip's
  *text* is already the node's name or description, and a tooltip node appearing and disappearing on
  hover is noise for a user who is not using a pointer.
- **Not screen coordinates on Wayland.** They are absent from the protocol by design and GTK cannot
  produce them either. Window-relative extents are reported truthfully rather than zeros dressed as a
  position.
- **Not the system accessibility settings axis.** High contrast, reduced motion, a system text scale and
  a screen-reader-is-running flag are a different decision with a different shape, and putting them here
  would tangle a tree with a theme.
- **Not a second backend.** The SPI admits one; nothing but `limn-backend-lwjgl` implements it.
- **Not a change to the hit-target floor, the keyboard, or focus order.** Reading order is *defined* to
  equal the existing Tab order (§12.1). If the two ever disagree, the tree is wrong, not the keyboard.

---

## 12. Verification

### 12.1 Provable headlessly, and therefore in CI

Everything in `limn-toolkit`, with `SceneTestBase` and `ComponentTestBase`, `StubWindow`,
`RecordingWindow` and one new `RecordingAccessibilityBridge` whose `isListening()` a test controls and
which collects published trees and emitted events — the shape `RecordingWindow`'s IME fields already
have.

| Test | What it pins |
| --- | --- |
| `AccessibleCoverageTest` | reads the component source directories as declared Gradle inputs, finds every **transitive** `Widget` subclass — not the literal text `extends Widget` — and fails until each appears in §7's table with an expected role. A new widget cannot be added without saying what it is |
| `AccessibleGalleryTest` | **written 2026-09-07 evening**, in `limn-demo`, and not over the demo's own gallery: it runs over `limn.demo.a11y.AccessibilityGallery`, a gallery of components built for this purpose, each entry declaring the toolkit classes it covers and the roles it promises, labelled the way a reader needs (`Label.setLabelFor`, described pictures, titled dialogs), with a `main` so a reader can be pointed at it. Every entry, in both palettes, over every window it opens (a dialog in its own window, a popup): no node has role `UNKNOWN`; every `FOCUSABLE` node has a non-blank name; no two nodes share an id; and the clipping invariant as the model can state it — the published node carries no clip mark (`Widget#clipsChildren()` is consulted by the walk for `SHOWING` and never published) and "wholly inside" is false by design for a half-scrolled row (§7.2), so a `SHOWING` node must have a non-empty box overlapping the scene's and every `SHOWING` ancestor's. Every promised role must appear, which is what caught a headless window with no display and so no popup. Completeness both ways, by scanning the toolkit's sources for `onAccessibility` overrides: a hook-bearing class no entry covers fails, and so does an entry claiming a class with no hook. Its first run found the picker a colour well raises focusable and nameless — §7.2's own prediction — and the well now names it after its dialog's title. What it cannot catch: the demo's own usage, which the transcripts cover. It said until 2026-09-16 that it also could not catch "a synthetic child the model cannot publish disabled", naming the segment strip's dead chevron; that has not been true since 2026-09-14, when `Accessibility#disabled()` gave a widget the route (§1.2). The chevron is published without `ENABLED` and, since 2026-09-15, without a verb (semantics 5) — a disabled `BUTTON` with no verb, which is a shape the invariants do read |
| `AccessibleTranscriptTest` (`limn-demo`) | four scenes — `forms`, `components`, `kitchen-dialog` and, since 2026-09-14, the accessibility gallery's `table` (B5) — built as `--scene` builds them, bound to a headless window and backend, settled over the gallery's warm-up frames under the fixed ruler and the English locale, and their published trees written as transcripts: one line per node in tree order, role, name with provenance, description, spoken states, facets, verbs and relations by line, **no bounds**. Compared against goldens a reviewer chose by reading them aloud — true of the first three; `table.txt` was read line by line and not aloud when it was committed, and the reading aloud is owed (amended 2026-09-16) — rewritten only under `-Dlimn.a11y.transcripts.update=true` and failing otherwise with the differing lines. The kitchen scene is two trees, host and modal window |
| `AccessibleFocusOrderTest` | the invariant that keeps the tree honest: the published nodes carrying `FOCUSABLE`, in tree order, equal the sequence produced by repeated `focusTraverse` from nothing. Stated on that bit and not on `ENABLED`, which is a strictly larger set — every `Label`, `ScrollBar` and `Separator` is enabled and is not a tab stop (§1.13). Roving focus passes because only the holder is focusable, which is the same fact the tree reports |
| `AccessibleMirroringTest` | in RTL, tree order is unchanged and bounds decrease in x. The tree is not sorted by geometry |
| `AccessibleLocaleTest` | a subtree with a declared locale publishes its name in that language while the process locale is another, and a locale move re-resolves every name exactly once |
| `AccessibleScaleTest` | under a content-scale override and a non-zero window origin, a known widget box converts to a known screen rectangle — which is what the doubles' new screen fields (§8) exist for |
| `AccessibleEventTest` | two snapshots in, an expected event list out: one focus event per publish, one bounds event per scrolled frame, property changes collapsed per property, a text change carrying the right insert offset over astral-plane text, an announcement never dropped. **This test cannot fail for the defect that will actually happen**, because it constructs both snapshots itself — the next test is the one that guards the mechanism |
| `AccessibleLiveMutationTest` | the regression gate for §5.3, and the test whose absence let the first draft of this ADR ship a design in which a checkbox toggle raised nothing. For each state-bearing component — `Checkbox`, `Slider`, `Spinner`, `ProgressBar`, `TextField`, `TextArea`, `SearchField`, `ComboBox`, `ListView`, `TabbedPane`, `SegmentedControl`, `ScrollView` — it drives the **public setter** on a bound scene with a listening bridge, renders one frame, and asserts the expected event arrives with the expected node and value. Nothing in it constructs a snapshot. A funnel that stops setting the dirty flag fails it |
| `AccessibleQuietFrameTest` | the other half: a frame that damages a widget without changing any accessible fact — a caret blink, a hover ripple — publishes nothing and emits nothing; and a re-present frame does not even walk |
| `AccessibleModalTest` | with an overlay pushed, every node outside `inputRoot()` publishes without `ENABLED` and without `FOCUSABLE` while staying `VISIBLE` and `SHOWING`, the overlay's own node carries `MODAL`, and the set of nodes published `FOCUSABLE` equals the set `focusTraverse` can reach (§1.13) |
| `AccessibleMenuTest` | an open cascade publishes one `MENU` per open column with the column's rectangle and not the surface's, one row node per item with its own rectangle, the highlighted row as the column's active descendant (since 2026-09-14 the surface's cursor, §1.10's reading note), and an `ACTIVE_DESCENDANT_CHANGED` for each arrow key |
| `AccessibleRecyclingTest` | scrolling a `ListView` past its pool size and back leaves row 3's identifier on row 3, and never on the cell that visited row 9 |
| `AccessibleIdentityTest` | **the §1.3 regression gate.** Giving a transparent ancestor a name mid-run changes the tree's shape and **no identifier below it**; the events are structural, not a wave of `NODE_DESTROYED`. The same for making a scaffold widget focusable — which also fails outright until `setFocusable` invalidates (§8) — and for pushing an overlay above a subtree. A key derived from the published parent fails every case |
| `AccessibleInheritedStateTest` | disabling a container publishes every descendant without `ENABLED` and without `FOCUSABLE`, hiding one publishes every descendant without `VISIBLE`, and in both cases the `FOCUSABLE` set still equals what `focusTraverse` can reach — never the enabled set, which the scene under test makes larger on purpose by holding a `Label` and a `Separator`. And the transparency verdict does not move: a disabled form grows no `GROUP` nodes (§1.6) |
| `AccessibleLifecycleTest` (named `AccessibleFirstFrameTest` here until 2026-09-16; the behaviour was always in the lifecycle test) | a scene bound to a window publishes nothing at `bind` **unless its bridge asked for a root there** (the clause is new on 2026-09-16; for every other bridge the sentence is unchanged) — where it has never laid out — and publishes a tree with real boxes on its **first frame**, with `republishNow()` called between the two returning the empty tree rather than a tree of zero-size rectangles (§5.2). And the priming publish is paid only by a bridge that asked for it: a double answering `needsPrimingPublish() == false` and never listening receives nothing, ever. Since 2026-09-16 it also pins the other half: a bridge answering `needsRootBeforeTheFirstFrame()` is handed the window's own node at the bind — one node, the window's role, title and size — and the first frame's tree carries the **same** window identifier, because a root retired between the two would leave a client's subscription on an element that no longer exists (§3.1) |
| `AccessibleAnnounceTest` | `announce` on an idle scene with a listening bridge reaches the bridge without any other frame being scheduled by anything else — the frame `announce` itself bought; and an announcement is delivered on a frame where the tree did not change, and on a re-present frame |
| `AccessibleRegistryTest` | over `RecordingAccessibilityBridge`'s own id-keeping double: an event-queue collapse hands the bridge enough to release every node that went away, and a second scene bound over the same window leaves the double holding zero elements (§1.10, §5.3) |
| `AccessibleWindowMoveTest` | moving the window re-stamps the tree, keeps every node id, emits one window-level `BOUNDS_CHANGED`, and **walks no nodes** — the assertion that separates `requestRestamp` from `requestRepublish`, counted the way the measure-count tests count measures |
| `AccessibleWindowEventTest` | a bound scene raises `WINDOW_OPENED` and a closed one raises `WINDOW_CLOSED` — from inside `attach` and `detach`, which is asserted rather than assumed: the double records neither through `emit`, because neither arrives that way (§5.3) — and neither is derivable from a diff; and **a second scene bound over the same window** raises `WINDOW_CLOSED` for the first and `WINDOW_OPENED` for the second, leaving the bridge holding exactly one host — the case the outgoing scene never learns about |
| `AccessibleActionTest` | every case drives `Host#perform` from a **non-UI thread**, which is the path a bridge actually takes (§1.9), and asserts that the double never resolves a node itself: an action on a disabled, hidden, detached or modal-shadowed node does nothing and says so; an id absent from the published tree is refused synchronously; a toggle fires the application's handler exactly once and never on a disabled checkbox; a `SET_VALUE` on a slider notifies, which the public setter does not; nothing anywhere replaces an application's listener |
| `AccessibleSecretTest` | a password field that is not revealed never puts its text in a node — asserted over the *whole published tree*, name and description included, not just the facet, because the shaped line the offsets come from carries the secret as its `text()`; and its caret and selection offsets lie inside its published mask, over a secret containing an astral character, where the model's own offsets would not |
| `AccessibleIdleCostTest` | with no bridge: zero describe calls, zero frame requests, zero bytes per frame under an `AllocationProbe` copy in `limn.scene` — including across a `setTooltip`, a `setFocusable` and a direct `invalidateAccessible()`, each of which sets the flag and buys nothing. With a live bridge and a clean tree: the same. And, measured rather than inferred (§13.7): the reference field becomes an object on the first declaration and never before, and a thousand flag stores through the damage funnel before a frame cost that frame zero bytes, zero describe calls and one `isListening()` |
| `AccessibleInvalidateTest` | on an **idle** scene with a listening bridge — no frame pending, nothing repainting — a single `invalidateAccessible()` buys exactly one frame and that frame publishes the change, and so do `setTooltip` and `setFocusable` through it. The frame is bought through `scheduleFrame()` and not `requestRender()`, asserted the way `AccessibleWindowMoveTest` separates the two paths: nothing is damaged (§1.5, §5.2, §8) |
| `AccessiblePublishCostTest` | a publish of an N-node tree allocates a bounded amount and walks each node once, counted the way the existing measure-count tests count measures; and a damaged-but-unchanged frame allocates zero (§6). The second half is the one that will catch a regression, and it has two known ways to break: the first facet built inside `onAccessibility` rather than written into the scratch columns (§1.1), and the first field whose *comparison* allocates — so the scene under test carries a `TextField` with text in it and a blinking caret, which fails the moment someone compares `text()` instead of `revision()`, **and a `Spinner`, which fails the moment someone formats a value text in the walk instead of taking the widget's cached one with its witness** (§1.1, §6) |
| `AccessibleTickingValueTest` | a `VideoView` playing for a simulated minute publishes about sixty trees, not about three thousand six hundred: a value that advances on its own is published rounded, and the diff sees one change per second (§6) |
| `AccessibleEventBudgetTest` | a diff wider than the queue's capacity emits the collapse event and never more than the capacity, and the queue never grows |
| `AccessibleReentrancyTest` | a describe pass mutates nothing and lays out nothing; and a `republishNow()` standing in for a platform callback publishes with the reentrant flag, so the double **destroys no element, re-pushes no root child list and drains no event** while the caller is on the stack, requests the frame that pays those debts, and the next ordinary frame pays them (§3.2, §5.3) |

Four of those matter in five years. `AccessibleCoverageTest`, because it makes the build refuse a
widget nobody described. `AccessibleFocusOrderTest`, because it makes the build refuse a tree that
disagrees with the keyboard. `AccessibleLiveMutationTest`, because it is the only one that would have
caught this ADR's own worst mistake: a test that diffs two snapshots it constructed itself stays
green while nothing in the toolkit ever produces the second one. And `AccessibleIdentityTest`,
because identity churn is invisible from inside the toolkit — every tree it publishes is correct,
every event is correct, and the only symptom is a screen reader whose cursor keeps jumping back to
the top of the window for reasons its user cannot see.

Also provable headlessly, in `limn-backend-lwjgl`: the D-Bus marshaller. Signature parsing, alignment,
the two rules a naive implementation gets wrong — an array's length excludes the padding before its
first element, and a signature has a one-byte length and no alignment — round trips over every
implemented type, and the seven golden messages captured off the wire from the reference implementation.
Those catch a wrong-but-self-consistent reading of the specification, which is the failure mode a
hand-written marshaller actually has, and they need no bus.

### 12.2 Needs a real platform client, and therefore a lab

None of the three bridges can be exercised by the configured build: one Ubuntu runner under `xvfb-run`,
no session bus, no AT-SPI registry, no macOS or Windows runner anywhere. The bridges are verified by a
scripted lab suite run by hand against the guests.

**The suite lands in this repository; the transcripts do not** (§0). One directory per platform —
`scripts/a11y/windows/`, `scripts/a11y/macos/`, `scripts/a11y/linux/` — each brought in by that
platform's own phase (§14) and each carrying a README naming the guest it was last run against and
the day. The spikes' clients are what those directories start from, because they exist and they work.
The reason to commit the client rather than its output is that only a run against the *changed*
bridge is worth anything: a saved log from a probe that no longer exists says nothing about the code
that shipped, while a script anyone can re-run says everything.

| Platform | Client | Assertion |
| --- | --- | --- |
| Windows 11 ARM64 guest | `scripts/a11y/windows/walk-the-probe.ps1`, the spike's PowerShell `UIAutomationClient` walk, run into session 1 through a scheduled task with `/IT` (a shell over SSH is session 0, where a window kills the JVM), plus NVDA 2025.1 portable | find by name, control type, bounding rectangle, `InvokePattern.Invoke`, `SetFocus`, `ElementFromPoint`, and a focus event observed by NVDA |
| macOS guest | `scripts/a11y/macos/` holds the versioned half: the clients `axtree` (walk, hit-test, follow relations), `axoutline`, `axtable`, `axdates`, `axlife` (destroy an element under a client that holds it), `axbusy`, the probes, and `dump-appkit-constants.swift` (§12.3). Each runs on any machine of this OS and carries no fact about a particular guest. **The runners that bring a guest up — build, copy, start in the console session, drive the steps, photograph the caption strip — live outside the repository** (decision 17 of the 2026-09-13 pass); they carry a VM's address, its login and its home paths, which are lab notes and not repository facts. `vocap` exists because `screencapture` raises a consent dialog on every invocation and a dialog takes the foreground, which is what a reader announces — **the measurement destroying what it measures, which is the shape to watch for on every one of these guests** | the clients already pass against the spike's one-element provider; against the real bridge they must pass against a *tree*: find by name through `AXTitle` **or** `AXDescription`, `AXPress` arriving back in Java on the main thread, hit test through several nested levels, notifications to a real `AXObserver` (with focus observed only at application level), and the loop-mode sweep. Three assertions are new and are the ones this round's fixes created: **an overlay opening while a client is attached is visible to it**, which is the re-push of §2.2; a destroyed element is released and a stale message to it fails rather than crashing; and a scene rebound over the same window leaves no element alive. Every one of them is run as an ordinary user, never under `sudo`, because root is accessibility-trusted and a privileged run would be proving the wrong thing. What only VoiceOver can settle is the attributes a purpose-built client never asks for |
| Ubuntu GNOME, X11 and Wayland | `scripts/a11y/linux/walk-the-probe.py` through `libatspi`'s own typelib — so a tree it walks is a tree Orca sees — and a talking Orca read through `--debug-file`. `-Dlimn.a11y.linux.trace=true` logs every inbound call, which is what turned "the desktop will not list us" from a silence into a question | tree walk, find by name, `DoAction`, `Cache.GetItems` in one round trip, extents in both coordinate types, one application object with one child per window |
| Fedora KDE, X11 and Wayland | the same | **run 2026-09-06, and it did not behave the same.** Its at-spi2-core 2.60 reads an application as it registers and refuses to list one that answers "no children", which found a registration-order defect this bridge had had since it was written (§13.15). With that fixed, a client walks the tree and Orca speaks names, roles and states. What is still open is the rendered probe on a **Wayland surface**, below |

*(Amended 2026-09-16, decision 17 — where the boundary of "the suite lands in this repository" runs.
A **platform script** reads a platform and runs on any machine of that OS: the constants dumps, the
clients (`walk-the-probe.py`, `tree-check.py`, `table-check.py`, `date-check.py`, `events-check.py`,
`walk-the-tree.ps1`, `walk-the-dates.ps1`, `axtree`, `axoutline`, `axtable`, `axdates`, the Swift
probes) and the gallery's own `--reader` driver. Those are versioned, and this section's promise is
about them. A **lab runner** brings a particular guest up — its address, its login, its home paths,
the order a VM is built, copied to, started and photographed in. Those are not versioned and were
removed on 2026-09-16: the seven `scripts/a11y/macos/guest-*.sh` and
`scripts/a11y/linux/run-tree-reader.sh`, whose copies live outside the tree. The one script of that
set that carried no lab fact was kept and renamed `scripts/a11y/macos/selector-allowed-run.sh`, so
the `guest-` prefix no longer reads as "this is a lab runner". A constant's comment cites the guest
and its version, never a runner: the rule is enforced by
`ScriptsCarryNoLabFactsTest`, which refuses a private IPv4 literal, a lab login and a
`/Users/<name>` path anywhere under `scripts/`.)*

*(Amended 2026-09-15: the event shapes have a client of their own on both Linux guests.
`scripts/a11y/linux/events-check.py` prints every event a named application sends as libatspi hands
it over — detail1, detail2, source and `any_data` — and a source's cached children after a
`children-changed`; `EventShapesProbe` (backend test sources) drives each shape through a real
difference with no window. Run on Fedora KDE 44 and Ubuntu 24.04 the same day, identical on both, no
signal refused. What it does not replace is a talking Orca, which is phase 5's.)*

*(Amended 2026-09-15, LINUX-NEW-6: the Ubuntu row's "`-Dlimn.a11y.linux.trace=true` logs every inbound
call" was half of what a silent reader needs. The same flag now also names every signal the application
hands its connection — path, member, detail, both integers and the value — whether the connection
accepted it and, when it did not, how many it has refused so far; every event that sent nothing and
why (no mapping on this platform, already said in this publish, raised before the join); and what an
`INVALIDATED` leaves owed. It goes through one consumer, `AtspiTrace.trace`, read once per site and
formatting nothing when null, as `UiaWindow.say` does on Windows, so a test installs its own. The wire
trace `-Dprobe.trace=true` (`DBus.TRACE`, every message and SASL line on standard error) stays separate.
A string value is cut to 40 characters; a masked field's text changes carry the mask.)*

*(Amended 2026-09-15, review of the interfaces item: that last sentence covered the outbound lines only.
Once `EditableText` was served, the inbound line printed the plaintext a client sends in
`SetTextContents` or `InsertText` to a `PASSWORD` field. The inbound line now withholds the string
arguments of an `EditableText` call to a `PASSWORD` node, or to a path that names no node, and prints
their length in characters instead; the numbers and every other call are traced as before. Pinned by
`AtspiTreeTest.theTraceWithholdsTheTextAClientWritesIntoAPasswordField`.)*

*(Amended 2026-09-15, H3: the date widgets have a client on Linux. `scripts/a11y/linux/date-check.py`
reads, through libatspi, a calendar grid by row and column with its `Selection`, headers, cell states and
`posinset`/`setsize`; a date field's segments through `Value` and `Text`; and a picker's expand state and
popup relations, optionally performing an increment or opening the popup. Run on Fedora KDE 44 the same
day over the gallery's "Calendar grid", "Date field, segmented" and "Date picker, closed" entries, it read
every cell of the 6 × 8 grid by row and column, the selected day through `Selection`, "15 of 30" on a
day, each segment's number and text, an increment read back, and the field's expand states — the first
live reading of the `Selection`, `Value`, `Text` and attribute amendments of §2.3. The same run saw a
`SelectChild` on the gallery's picker answer false while an in-scene date picker held the input layer,
which is semantics 5 as the walk publishes it. What it does not replace is Orca speaking them, which is
phase 5's.)*

*(Amended 2026-09-15, the after-lane checks on at-spi2-core 2.52: the Ubuntu row's assertions were run
against the finished bridge, and the readings are in `readings/ubuntu-2.52-after-lane.txt`. Ubuntu
24.04.4, GNOME Shell 46.0 on X11, at-spi2-core 2.52.0-1build1, no screen reader started or running,
no `sudo`, over SSH, with the branch's own `limn-demo-all.jar`. **Registration:** the desktop lists
exactly one application for the process, with one frame per window — the shape §2.3 prescribes — so
the 2.60 registration order the Fedora guest forced is right on 2.52 as well, where a wrong one would
not have shown. **A native ComboBox popup:** `press-the-probe.py` performed the combo box's own
`expand` through `Action.DoAction` from outside the process — no keyboard touched on the guest — and
it answered true and opened a real popup window; `frames-check.py` then read one application with two
frames, the list's `POPUP_FOR` naming the combo box in the other frame and the combo box's
`CONTROLLER_FOR` naming the list, each resolved through the other frame; `walk-the-probe.py` read the
open list's five items with `press`/`select`/`focus` and the first `ACTIVE` and `SELECTED`, and the
combo box itself now `EXPANDED` and offering `collapse`. **A native DatePicker calendar:** the
gallery's `--reader date-picker` run (pt-BR, the documentation day, the entry's own presentation)
gave the same shape — two frames, the calendar panel's `POPUP_FOR` naming the "Delivery date" field
and the field's `CONTROLLER_FOR` naming the panel — and the frame left the application's child list
when the picker closed, through `children-changed:remove` from the application. So decision 5's
cross-window relations, which only Linux had implemented by the end of phase 3, are read by libatspi
2.52 as well as 2.60. `press-the-probe.py` gained the two arguments that made the combo box reachable
(a role substring and a verb name, defaulting to a button's first action, which is what it did
before). What this does not settle is what Orca 46.1 SAYS about any of it: that is phase 5's, and the
reader was deliberately left unstarted.)*

**One of these can plausibly move into CI, and it is worth trying.** The Linux bridge is pure Java and
pure D-Bus, and the Ubuntu runner can install `at-spi2-core` and run the whole probe under
`dbus-run-session`. If that works, one of the three platforms gains a real gate. It is listed as work,
not assumed.

### 12.3 The constants rule

Every platform constant used by a bridge is read off the machine under test and asserted in a test,
never recalled into source. The three recipes exist: AT-SPI2 roles, states and coordinate types from
`Atspi-2.0.typelib` and object paths from `strings` over `libatspi.so.0`, both of which produced every
constant the Linux spike shipped; AppKit selectors, role constants and type encodings from the running
AppKit, which is a test the bridge can run on any future macOS; and UI Automation ids from
`uiautomationcore.h` or the interop assembly on the guest. A constant that appears in a bridge without a
test that read it off the platform is a defect that compiles.

**macOS's recipe is `scripts/a11y/macos/dump-appkit-constants.swift`**, and running it before writing
the role table found two things a table copied from a header would have carried into the bridge.

*A subrole this record would have used does not exist.* There is no
`NSAccessibilitySeparatorSubrole` in this AppKit — the name is plausible, the neighbouring
`NSAccessibilityContentListSubrole` and `NSAccessibilityTimelineSubrole` are real, and a `SEPARATOR`
mapped to it would have resolved to a null pointer at run time on the one platform where a role is a
string rather than a number.

*And three constants this design needs cannot be read at all.*
`NSAccessibilityPriorityLow`, `…Medium` and `…High` are **not exported symbols**: unlike every role,
subrole and notification name, they are values of a C enum
(`NSAccessibilityPriorityLevel`) and there is nothing for `dlsym` to find. §2.2 says politeness rides
`NSAccessibilityPriorityKey`, and the key *is* exported; the number that goes under it is not. So
this is the one place in three platforms where the constants rule cannot be honoured, and the honest
form of it is the narrow one: the three numbers are written down as literals, in one place, with
this paragraph as the reason — and the dump script keeps listing them as unexported so that the
exception stays visible rather than becoming a habit.

#### Amendment 2026-09-15 — selectors are covered too, and a missing one no longer takes the window

**What was wrong (MACOS-NEW-6).** "Type encodings from the running AppKit" was true at run time and
checked nowhere before it. `AxConstantsTest` asserted the role and notification symbols against the
committed dump and no selector at all; the selectors were literals across `AxElementClass` and
`AxActions`, and the two f4bc544 added for `AXElementBusy` (`accessibilityAttributeValue:`,
`accessibilityAttributeNames`) were never in the committed dump. At run time a selector no class
declared made `AxObjC` throw inside the element class's constructor, and `Bridges.openFor` answered
`NONE` for it: one misspelt or withdrawn selector removed the window's accessibility, silently.

**The rule.** Every selector the bridge installs is listed once, in `AxSelectors`, and the element class
installs through one method that refuses an unlisted selector. `AxConstantsTest` asserts every listed
selector has an encoding in the dump's encodings section; `AxSelectorsTest` asserts, from the source,
that the element class installs exactly the list. The two legacy selectors were read on the macOS
26.6.2 guest on 2026-09-13 by this recipe (the readings' copy of the dump) and are carried in the test
as owed to the committed dump's one regeneration at the end of the macOS lane, which a companion
assertion forces to retire. At run time a listed selector the running AppKit declares nothing for is
skipped — never given a guessed encoding — and the bridge logs a warning naming it; the rest of the
window's accessibility is built. A failure to build the bridge for any other reason is logged at
`ERROR` before `NONE` is answered.

*Corrected the same day (the review of this amendment):* skipping each absent selector on its own could
install the action selectors without `isAccessibilitySelectorAllowed:`, and without that gate every
element offers every action (semantics 5) — confirmed through real AppKit on the development Mac, not
the guest. Selectors that mean something only together are now skipped together (`AxSelectors.REQUIRES`:
every action selector with the gate; the two legacy entry points with each other), and the warning names
what was withheld with what. The `ERROR` covers every `Throwable` from the whole open, AppKit's loading
and the content view included, not only the constructor's exceptions. Skipping rather than refusing to
build the class is the implementer's choice, awaiting the owner's. And a name in the dump says only that
a selector exists: each listed selector now also names the shape of the libffi closure it is installed
with (`AxSelectors.Kind`), the element class refuses a closure of another shape, `AxConstantsTest` holds
each shape against the encoding the dump read, and `AxSelectorsTest`'s source scan reads each install's
shape and refuses an install whose selector it cannot read.

*The regeneration, dated 2026-09-15 (the end of the phase-3 macOS lane):* the committed dump was
regenerated once, from `scripts/a11y/macos/dump-appkit-constants.swift` run on the macOS 26.6.2 guest
(25G83). Every line of the 2026-09-13 reading reappeared unchanged; the new dump adds the selectors the
bridge came to install in phase 3 — the legacy pair, disclosure, expanded, the setters, the named-action
perform, the column lists and the column class's installs, every one of the 64 install lines "in the
encodings table" — and the sort direction's attribute and C enum values (unknown 0, ascending 1,
descending 2, from the SDK the script was compiled against, without quotes). The selectors and symbols
the test carried as owed to it are retired, so each is now held against the committed dump like any
other.

#### Amendment 2026-09-15 — a platform fact two servers answer differently is a choice, and the choice is argued in the source

**What the phase-3 critic found on Linux.** Four facts this bridge answers were read on two toolkits
that disagree, and one was carried with no citation at all: AT-SPI `PARAGRAPH`'s boundary (GTK 4
answers a line feed, GTK 3 answers nothing), the `version` property (GTK 3 answers `1`, GTK 4 refuses
it), the error for a `Properties.Set` of a read-only property (GTK 3 `PropertyReadOnly`, GTK 4
`InvalidArgs`), the error for a `CurrentValue` write a node will not take (both toolkits answer
success, and this bridge refuses with `Failed`), and the object-path prefix
`/org/a11y/atspi/accessible`.

**The rule this adds to §12.3.** A constant read off one machine cites its reading, as it already
must. A fact the machines answer differently is not thereby unread: it is a **choice**, and the
comment beside it must say that the readings disagree, name both, and say why that half was taken.
The five sites now do, and `AtspiConstantsTest.thePlatformFactsWithoutOneReadingSayWhichHalfWasTakenAndWhy`
reads the source and fails when one of them loses its citation or its reasoning, so the next edit
cannot quietly drop either. The prefix gained a reading of its own the same day: GTK 3 through
at-spi2-atk 2.60.6 exports at exactly that prefix (`readings/fedora-gtk3-interface-replies.txt`),
while GTK 4 exports under `/org/gtk/application/<app>/a11y/<uuid>` and is read by the same clients —
so the prefix is this bridge's namespace, chosen to match the ATK bridge, and not a protocol
constant. A reference on this bus is an `(so)` pair a client follows without parsing.

*(Amended 2026-09-15, the review of that change. **A sixth site**: the application object answering
`org.a11y.atspi.Component` at all is a choice against both toolkits — GTK 3.24.52 through
at-spi2-atk 2.60.6 answers `UnknownMethod` on `/org/a11y/atspi/accessible/root` and logs
`impl_GetExtents: assertion 'ATK_IS_COMPONENT (user_data)' failed` doing it
(`readings/fedora-gtk3-interface-replies.txt` lines 3-9 and 33-36), GTK 4.22.4 answers
`UnknownMethod` because its application object serves no such interface
(`readings/fedora-gtk4-interface-replies.txt` lines 25-28), and the ATK bridge disagrees with itself
by answering that interface's `version` property `1` on the same root. This bridge answers the first
frame's box, because of the two halves it is the one that cannot cost a client anything; whether any
client is misled by a root that answers `Component` is a phase-5 reading and the condition for
reversing it. It was argued in `AtspiTree.applicationComponent`'s javadoc and added to the test's
list. **And the test asserts more of the rule than the word.** Its first cut required only a file
under `readings/` and the word "choice", both of which one site already carried before its argument
was written — so that entry could not have gone red for the defect it guards. A choice site must now
also name **both servers** it read, and, where the disagreement is over a name rather than a value,
the name it did **not** answer with. What a test cannot check is whether a reason is a good one; the
javadoc says so in as many words, so nobody reads a green run as a claim about the reasoning.)*

#### Amendment 2026-09-15 (the fix round) — the three macOS facts that were still without a reading

The phase-3 critic's completeness pass listed three things this bridge uses that no reading named.
Two of them turned out to be read already; one was not, and now is. The dump was **not** regenerated
again: nothing in the fix round adds an AppKit symbol or a selector, and `AxConstantsTest` stays
green against the 2026-09-15 dump.

- **`NSNotFound` as `AXIndex`** (`AxGrid.NOT_FOUND`, `Long.MAX_VALUE`) had no reading, and it is not
  an exported symbol for `dlsym` to find — it is `NSIntegerMax`, the constants rule's narrow case
  again. It is read instead through the platform's own behaviour, which is stronger:
  `scripts/a11y/macos/list-probe.swift` prints `NSNotFound` from the running Foundation
  (`9223372036854775807`, `0x7fffffffffffffff`, equal to `NSIntegerMax` on a 64-bit `NSInteger`) and
  hangs an `NSAccessibilityElement` answering `NSNotFound` for `accessibilityIndex` off a plain
  view's children — how this bridge vends every element — which an out-of-process client reads back
  as `AXIndex=9223372036854775807`, `objCType=q`. `readings/macos-list-probe.txt`.
- **`-[NSObject isKindOfClass:]` `B24@0:8#16` and `-[NSNumber stringValue]` `@16@0:8`** were read on
  the guest on 2026-09-15 with the other Foundation messages and are cited in `AxElementClass#isKindOf`'s
  javadoc; the critic's list was out of date. Re-read at the fix round's HEAD and byte-identical:
  `readings/macos-foundation-messages-probe-3.txt`. Not a defect.
- The same run settled the two macOS entries the critic filed as **choices between readings**: a
  native list's selection notification and `AXRowCountChanged` on a trigger that is not a
  disclosure. Both are now read on a native `NSTableView` rather than inferred from the outline;
  §2.2's macOS column and `AxNotifications` cite them.

#### Amendment 2026-09-16 (fix round 3b) — the fourth macOS fact without a filed reading, and a citation ratchet for the prose

The fix-round critic found one more, looser than the rule and not a value: the **fourth** fact this
bridge answers off a guest with no file named behind it, after the three the amendment above settled,
and the **fifth** entry of the ratchet's list below, whose other four already cited theirs.
`AxBridge#windowElement`
cited `-[NSView window]`'s encoding `@16@0:8` as "read on the guest with the other Foundation and AppKit
messages, 2026-09-15" and named no file — leaving `AxBridge.java` the one file of this bridge with no
`readings/` citation at all, on the very answer CRIT-2 had just made load-bearing (a relation naming
another window's elided root is answered with that window's object). The reading existed all along:
`readings/macos-foundation-messages-probe-2.txt` line 15, re-read byte-identical at the fix round's HEAD
as `-3.txt` line 27. The javadoc now names both.

**Why this needed a ratchet and not just an edit.** macOS's own guard, `AxConstantsTest`, holds every
selector and symbol against the committed dump — mechanically stronger than anything the other two
bridges have, and completely silent about prose, which is where a fact that lives in a guest's
Objective-C runtime rather than in AppKit's exported symbols has to be recorded. Windows and Linux each
landed a citation ratchet in the fix round that would have caught this; macOS had none.
`AxConstantsTest.everyFactReadOffAGuestRatherThanOffTheDumpCitesItsReading` is that ratchet, shaped like
Linux's: an explicit list of the sites answering from a guest rather than from the dump, each required to
carry a `readings/` file in the comment above it, and `windowElement` additionally required to quote the
encoding it read. A list and not a scan, because no assertion can tell a platform fact from a toolkit one
by reading the source; what it can do is hold the sites a reviewer has already found, so none of them
loses its citation again.

---

## 13. Risks and open edges

1. **~~The macOS end-to-end path is not re-verified.~~ Closed 2026-09-03/04**, by a re-run on an
   unlocked guest and then by an independent verifier with its own client: found by name under the
   window AppKit vends, pressed, the press logged inside Java on the main thread after `READY`,
   hit-tested through both the application and system-wide elements, and — the confound that would
   have voided all of it — **reproduced by an ordinary `uid=501` client with no `sudo`**, since the
   lab's `launchctl asuser` runs as root and root is accessibility-trusted whatever TCC says. What
   replaces this item is narrower and is items 20 to 23 below: the spike proved one static element,
   and this design publishes a mutating tree.
2. **~~The macOS pump hypothesis is unproven.~~ Closed, and it is now a constraint** rather than a
   risk: Finding 4's table. Two things about it stay open or restricted. Item 24 is *why* a
   two-second pump interval usually serves nothing rather than serving slowly. And the latencies in
   that table are **the client's, not the bridge's** — two clients against one provider disagreed —
   so they may not be quoted as a cost of this design, in §6, in a benchmark, or anywhere else.
3. **Abstract D-Bus sockets.** `java.net.UnixDomainSocketAddress` is filesystem-path only. Both guests
   answer `unix:path=`, and any systemd user session does, but a session started by `dbus-launch` yields
   an abstract socket, and older bus launchers used one when the runtime directory was unset. The parser
   throws a named error for that case rather than failing obscurely. If it appears in the field the fix is
   `junixsocket` or a relay — not a Limn-authored shim — and it should be measured on Fedora KDE before
   anyone writes code for it. The Ubuntu bus address also carries a `,guid=` suffix the parser must split
   off; Fedora's did not.
4. **A wrong call interface is a silent ABI bug on Windows.** Every vtable slot's signature is asserted
   twice, in the call interface and in the argument decoding, and nothing checks either against the real
   IDL. There is no runtime type-encoding oracle on Windows as there is on macOS. The mitigation is a
   small typed layer pairing each interface's call interface with its decode in one place, plus the golden
   client transcripts — and it stays a risk.
5. **~~`UiaClientsAreListening()` per frame is unmeasured, and so is the WndProc subclass.~~ Closed
   2026-09-07, by the measurement §6 named, and the number decided against the fallback.** On the
   Windows 11 ARM64 guest, `Bench --bench on` — the 750-widget form with ten animating progress bars —
   in three wirings, medians over three or four runs each: with no bridge at all
   (`-Dlimn.accessibility=off`, the control this needed and did not have) the animation frame is
   0.22 ms and idle CPU 2.0 %; with **the subclass alone** (`-Dlimn.bench.accessibility=subclass-only`:
   the platform bridge opened, which installs the subclass, and the scene handed `NONE`) 0.27 ms and
   2.1 %, the run-to-run spread on both sides being 0.16–0.27 ms and 1.7–2.7 %. Every window message
   crossing a Java frame costs less than the noise, so the polling fallback and the first-`WM_GETOBJECT`
   race it carries are not bought. `UiaClientsAreListening()` itself is 17 ns a call after warm-up
   (median of ten batches of a hundred thousand; 60 ns cold), which is nothing per frame. **The
   number worth keeping is the third one.** With the bridge simply attached, the animation frame was
   0.94 ms — the walk, not the subclass — because on that guest the gate answers *true with no reader
   running*: seventeen processes hold `uiautomationcore`, the Parallels control centre among them, and
   UI Automation counts any of them as a client. The walk is §6's accepted cost and it behaved as §6
   says, bounded and allocation-free; what the measurement corrects is the assumption that "nothing
   listening" is the common state on Windows. It is not, and 0.7 ms per damaged frame at 750 widgets
   is the cost an ordinary Windows desktop pays for a progress bar. The run also found what every live
   run on the guest had been ending in: `UiaDisconnectProvider` was called after the registry had
   freed the object it releases through, an access violation on every window close once a client had
   asked for the root, which is why the benchmark's numbers — printed after the event loop returns —
   had never been printed at all. The disconnect now comes first, and a test pins the order.
   **And the second half, the same evening: the gate can be per window.** He asked whether a true
   flag with no reader meant Windows could not be on demand, and it had not been looked into — the
   flag had simply been chosen. It is process-wide: true once *any* client in the session has
   registered *any* handler, and seventeen had. What UI Automation offers per window is
   `IRawElementProviderAdviseEvents` on the root, called with the event and its properties when a
   client's subscription covers this window and again when it is withdrawn. Read off the guest
   (§12.3), served on the root, counted; the reading was taken with the same drag probe twice: with
   **no reader** running, the flag true, no client asked for one element and **nothing advised**;
   with **NVDA** attached, one `AdviseEventAdded(AutomationFocusChanged)` arrived on an RPC thread
   before the first frame. So "someone subscribed to this window" is a fact the bridge can know,
   and it is false exactly where the flag is uselessly true. What it does not cover is a client that
   reads and never subscribes — an inspector, a test harness polling the tree — which would see the
   snapshot from its last `WM_GETOBJECT` and nothing newer. The gate's shape is therefore a
   decision and not a finding: subscriptions alone, or subscriptions plus a short window after a
   `WM_GETOBJECT`; the second serves the poller at the price of the time-based gate this record
   refused for Orca, and it refused it because Orca subscribes and then waits, which is the case
   the first shape covers. **Decided the same evening, the second shape, and built:** a subscription
   covering this window, or an ask — any `WM_GETOBJECT`, whichever object it names, because a reader
   announces a new foreground window by MSAA before it looks by UI Automation — which is owed one
   event and then two seconds. The owed event is what closed a circle the two-second window alone
   had opened: NVDA asks for the root three times when a window appears, reads a tree with nothing
   focused, and moves only on an event it can hear; a timer that closed before the first focus moved
   kept it at the window title forever. Measured, interleaved with the same benchmark and no reader:
   **the bridge attached now costs what no bridge costs**, 0.11 ms an animated frame and 0.8 % idle
   CPU on both sides, where the flag-gated walk had cost 0.8 ms. And with NVDA, the VM's window in
   front of the host's: `'Limn accessibility probe', 'janela'`, `'deslizante', '41'`, `'42'` …
   `'79'`, one per tick. Two things the runs found beside the gate: ~~a publish inside the ask, sync
   or requested, silences NVDA for good~~ — **withdrawn 2026-09-16: what silences NVDA for good is
   answering the ask with no provider, which is what a bridge with no tree did; those two runs could
   not tell that apart from the publish they were testing, at a base rate of one speaking run in
   fourteen (§3.1's amendment, and the ten traced runs behind it). The rule that nothing is published
   inside the message stands on its own grounds — a difference raised into a reader's own call is a
   reentrancy this record refuses everywhere — and is no longer carried by this measurement** — and
   every run made with the VM window behind
   another on the host was worthless, because Parallels then holds the guest's foreground with
   `prl_cc_fgproxy` and a reader follows the foreground — which is not a bridge fact and is in the
   lab notes rather than here.
6. **Two of three platforms have no CI coverage and will not get any.** Stated plainly rather than
   mitigated. The gate covers the toolkit half — the model, the tree, the events, the costs — which is
   where regressions will actually come from, because the bridges change rarely and the widgets change
   weekly.
7. **~~The per-widget and per-frame costs are asserted but not yet measured.~~ Closed 2026-09-07**, by
   two measurements in `AccessibleIdleCostTest`, and the two tests that were already there were not
   them: they measured a settled frame and a damaged frame under a bridge, and neither the field nor
   the store. `theFieldBecomesAnObjectOnTheFirstDeclarationAndNeverBefore` measures the per-widget
   cost by difference, interleaved and typical rather than least: a widget nobody described allocates
   itself and nothing for the tree, the first declaration is where the field becomes an object, a
   second and a third on the same widget add nothing, and re-declaring on a bound widget nobody
   listens to allocates nothing and buys no frame.
   `aThousandWidgetsDamagingThemselvesCostAFrameNothingWithNothingListening` measures the per-frame
   cost at a scale no single-widget test reaches: a thousand flag stores through the damage funnel
   before every frame, and the frame allocates zero bytes, enters no describe hook, and — with a
   bridge attached that nobody listens to — asks the platform exactly one `isListening()` and tells it
   nothing. Both were broken before they were believed: an eagerly allocated field failed the first at
   "bare 216, declared 216"; removing the not-listening return from the publish step failed the second
   with 62 describe calls per widget *while its allocation assertion still passed* — the walk allocates
   nothing, which is why bytes alone were never the measurement.
8. **Node identity churn.** The structural cause is fixed — §1.3 keys over the widget tree, so no
   ancestor's transparency verdict can re-key a subtree — and `AccessibleIdentityTest` guards it.
   What is left is the honest residue: an application that rebuilds its widget tree every frame mints
   identifiers forever, because every widget is a new object and rule 3 is keyed by the object. They
   are `long`s, so exhaustion is not the concern; the intern table growing is. Its bound is a policy,
   not a proof, and it wants measuring against a real screen reader on a real large list. A WARNING
   when a scene's table grows past a threshold between publishes is the cheap diagnostic.
9. **Per-node platform object lifetime.** A live LWJGL `Callback` pins its Java object until `free()`; a
   COM element survives until its refcount drops; an `NSAccessibilityElement` survives until AppKit
   releases it. There are four ways an element goes away — a per-node `NODE_DESTROYED`, the sweep
   after a queue collapse, a scene rebound over the same window, and `detach` — the first two on the
   thread that drains and the last two on the UI thread with the drain stopped (§3.4), and **none of
   them from inside a reentrant publish** (§5.3). Each is a different code path in each bridge. `AccessibleRegistryTest` covers the two that a headless double
   can reach; item 20 is the half no headless test can, and the collapse sweep is the one most likely
   to be written and never exercised, because reaching it requires a diff wider than the queue.
10. **A snapshot is up to one frame stale on Windows and Linux**, and arbitrarily stale while the UI
    thread is parked. That is the deliberate trade against a bounded wait that has no bound, and it is the
    decision a reader is most likely to want to undo.
11. **Bridges get no help from the toolkit if they read live state off-thread.** `Widget#invalidate()`
    and `Scene#requestRender()` are not thread-checked, so the mistake is a silent data race rather than
    an exception. Only discipline and review catch it.
12. **`Viewport3D` is focusable and handles no keys.** The tree will faithfully report a tab stop that
    does nothing. That is a pre-existing defect this work surfaces rather than fixes, and shipping the
    tree without saying so would be worse than saying it.
13. **A radio group has no container node.** `ButtonGroup` is not a widget and has no bounds, so the
    grouping is a relation plus position and size of set. UI Automation and AppKit would both prefer a
    container, and synthesising one from the union of member rectangles was rejected as too clever when the
    members need not be siblings. `SegmentedControl`, which *is* a widget, gets the container role.
14. **The list adapter's index must be stable.** An adapter that reorders rows without changing its
    count renames elements. Documented on the adapter, and unfixable from this side.
15. **Every ABI fact is proven on exactly one architecture, and they are not the same one.** The
    Windows spike ran an x64 JDK under emulation, so its facts hold for Win64 x64. The macOS spike is
    arm64 only, and the spike names the gap precisely: `objc_msgSend_stret` was never needed there and
    *would* be on x86_64, so the "one call path" claim in §10.2 is arm64's.

    **~~Fedora KDE and Wayland are untested.~~ Both were run on 2026-09-06, and each found
    something.** Fedora 44 KDE carries at-spi2-core 2.60 against Ubuntu 24.04's 2.52, and the newer
    registry reads an application *as it registers* rather than adding it and reading later. This
    bridge joined the accessibility bus in its factory, before any scene existed, so it had always
    registered with an empty tree — and 2.60 will not list an application that answers "no
    children": a hundred and thirty-two inbound calls, no error anywhere, and not one `libatspi`
    client would show it, Orca included. Registering before there is anything to show was wrong on
    both desktops and only one of them minded. The bus is joined on the first publish with a tree
    now, and Fedora KDE on Wayland walks correctly and Orca speaks `'Save' 'botão.'` and
    `'Wrap lines' 'caixa de seleção não selecionada.'`.

    **And what looked like a Wayland problem was one unanswered ping.** It is worth setting out,
    because the reasoning that nearly stopped at "Wayland" was reasonable and wrong. The
    measurements were:

    | guest | window | listed by `libatspi` |
    | --- | --- | --- |
    | Fedora 44 KDE, Wayland | none | yes |
    | Fedora 44 KDE, Wayland | a real Wayland window | **no** |
    | Ubuntu 24.04 GNOME, X11 | a real X11 window | yes |

    Which reads as a window-system fault and is not one. at-spi2-core **2.60.1** added *"detect
    unresponsive applications, and do not expose them as children of the desktop"*, and it decides
    that with `org.freedesktop.DBus.Peer.Ping` sent to `/`. This bridge resolved the object path
    before it looked at the interface, and `/` is neither the application root nor any node — so
    the ping fell through the lookup and was answered with nothing. Ubuntu's 2.52 does not ping.

    **The symptom is the part worth remembering.** Being hidden from the desktop's children is an
    omission, not a refusal: the registry went on talking to the application perfectly — 374
    inbound calls, a full `Cache.GetItems`, roles, states, no error anywhere — while no client could
    see it at all. Nothing in the conversation says what happened, which is why three runs pointed
    at the window instead. The fix is four lines and `AtspiTreeTest` holds it; the general lesson is
    §2.4's, again: what a platform asks *after* it is told something is where these live.

    **Wayland accessibility itself is not the problem, and the search that settled this said so
    first.** AT-SPI2 runs over D-Bus and is independent of the window system; Orca works on GNOME
    and on Plasma 6 Wayland, and what is genuinely missing is wlroots compositors, which have no
    AT-SPI2 integration at all. Nothing this record depends on is affected by that.

    **And testing this combination found one defect that is not this record's at all**, which is
    the argument for a guest per desktop rather than per operating system. Forcing the X11 platform
    on that guest hangs the application before it draws — GLFW's X11 backend waits for a
    `VisibilityNotify` on the window it has just mapped, rootless XWayland never sends one, and
    other events keep arriving so the wait never sleeps: a spin at 100% of a core, in
    `glfwCreateWindow` or in `glfwShowWindow` if the window was created hidden, because the map is
    what it waits on. It was reached by default, because the backend preferred X11 whenever
    `DISPLAY` was set and XWayland always sets it. **Any** Limn application on that desktop hung at
    startup, with no assistive technology involved. A Wayland session takes the Wayland platform now
    (ADR 028).

16. **~~Adding methods to GLFW's content view class is designed and not proven.~~ Withdrawn**, by
    deleting the design that needed it. The re-run measured that `setAccessibilityChildren:` on the
    content view is sufficient on its own and that `accessibilityHitTest:` was never needed, so §2.2
    now installs nothing on a class GLFW owns. `class_addMethod` is used only on our own runtime
    subclass of `NSAccessibilityElement`, which the spike proved twice.
17. **~~That the window's `AXWindow` comes from AppKit for a GLFW window is assumed, not observed.~~
    Closed:** the walk dumped it — `AXWindow/AXStandardWindow`, the window's title, `AXRaise`, the
    close, full-screen and minimize buttons, and an `AXStaticText` for the title. The elision in §2.2
    is now made against what this AppKit actually offers.
18. **Whether `WM_GETOBJECT` reaches us during a Win32 common dialog is unmeasured.** `TinyFdDialogs`'
    javadoc says the Windows chooser pumps its own message loop on this thread, and a thread that
    pumps delivers sent messages; that is an inference from two documented facts and this ADR asserts
    nothing more (§3.1). The experiment is small and the guest is already scripted: open the probe's
    file chooser, start a fresh client while the panel is up, and see whether it attaches. If it does
    not, the honest consequence is that a client attaching during a file dialog waits, which is what
    happens on the other two platforms anyway.
19. **~~The event queue's capacity and the macOS per-frame notification budget are policies with no
    measurement behind them.~~ Counted 2026-09-07, on both guests, with the reader attached.** The
    probe gained a 300-row list and two cycles for it — paging the list by a viewport per tick,
    moving the slider by one per tick, focus left alone — and a test-side wrapper that counts what
    the scene hands the bridge between two publishes and times what the bridge does with it.
    **Windows, NVDA attached:** 21 events per frame paging the list, of which 5 reach the platform
    (the rest name rows no client had asked for and return at the bridge's first line, ~300 ns
    each); 1 per frame dragging the slider; 27 on the first frame. No queue exists on Windows, so
    the number a capacity would be judged by is small and was never the question there — what the
    count found instead is §13.28, the raise that waits. It also found the slider silent: NVDA
    spoke `'deslizante'` and no number for forty changes, because the bridge vended `Value` beside
    `RangeValue` and answered it with `""`, which NVDA prefers to the number; with `Value` vended
    only from a spoken form, NVDA says `'deslizante', '40'` and then `'41'` … `'53'`, one per tick.
    A managed client subscribed to `RangeValue.Value` appeared to receive no property-changed
    event in twelve seconds of the same drag, while NVDA plainly did; explained the same evening,
    and the fault was the instrument's — a PowerShell script block registered as a UI Automation
    event handler is invoked on a thread with no runspace and never runs. The same subscription
    through a compiled C# delegate received six events in twelve seconds, the last
    `RangeValue.Value 48 → 49`, and its `AdviseEventAdded` / `AdviseEventRemoved` bracketed it on
    the root (§13.5).
    **macOS, VoiceOver's cursor on the widget being moved** (`LiveProbe -Dprobe.timing=true
    -Dprobe.focus=…`, 100 ms tick, 40 frames): paging the list by its five-row viewport is 21 events a
    frame — one `VALUE_CHANGED` on the list, then per row a `STRUCTURE_CHANGED` on the list, one on
    the row, and a `NODE_DESTROYED` each for the row that left and its label, four per row plus one —
    of which eleven post (`AXValueChanged` and ten `AXLayoutChanged`; destructions post nothing,
    AppKit does); the dragged slider is one event and one post. The drain of that frame was 164 µs
    at p50, 416 µs at p90, 1.67 ms at worst — 8 / 20 / 80 µs an event — and one
    `NSAccessibilityPostNotification` alone is 375 ns at p50 and 459 ns at p99 with VoiceOver on,
    83 / 125 ns with it off: the cost is the bookkeeping and the reader, not the post. No collapse in
    any run. **`AxEvents.CAPACITY` is now 256**, and 64 was wrong by the count's own arithmetic: at
    four events a row plus one, 64 is a fifteen-row page — an ordinary viewport — collapsing on
    every scroll and re-reading the window each time; 256 is a sixty-row page whose full drain at a
    pessimistic 30 µs an event, above the measured p90 with the reader on the list, is 7.7 ms,
    inside the 8 ms this item set the budget by. The collapse now logs a WARNING, which this item
    called the signal and which was silent. And the value half of the probe, never driven on any
    guest, was: with the cursor placed once and only values moving, VoiceOver read
    `verificado` / `desmarcado, Wrap lines, caixa de seleção` alternating and `50, controle
    deslizante → 60 → 70 → …`, no focus event in the run — and with no cursor on the widget it read
    the window title and nothing else, every notification posted. A reader speaks a changed value
    where its cursor stands; the probe's rule is unchanged, drive focus and value separately, and
    put the cursor where the value is.

Items 20 to 24 replace the two macOS items this round closed. They are narrower, and each is a thing
the spike's own "still unproven" section names — the spike published **one element, under one window,
in a tree that never changed**, and this design publishes a tree that changes every frame. Items 25
and 26 are not from the spike and are not about macOS; they are kept here because they are the same
kind of thing, a check that cannot fail for the defect it is aimed at. Item 27 is macOS's again and
comes from the last review pass, which found the one case where §1.11's relation rule and §2.2's
elision meet.

20. **~~A macOS element has never been destroyed while a client held it.~~ The destruction half is
    closed; the reentrancy half is not.** The spike retained
    everything and released nothing, and calls this the obvious crash vector. §1.3's lifetime rule —
    post `UIElementDestroyed`, then release — was the design's answer and it was untested.

    **What the run showed.** A client found the deepest node by identifier, held it, registered an
    `AXObserver`, and asked the provider to destroy it — writing the command itself, so the observer
    was demonstrably registered before the destruction rather than probably. The observer saw the
    destruction, a later `AXRole` read on the stale reference returned `-25202`
    (`kAXErrorInvalidUIElement`) rather than crashing, and a re-walk no longer found the node. The
    crash vector is real and the answer to it works.

    **But half of §1.3's rule is wrong on this platform, and the control run is what showed it.**
    Six runs, crossing our own post against which registration the client held:

    | our post | registered on | `AXUIElementDestroyed` received |
    | --- | --- | --- |
    | yes | the element | 2 |
    | yes | the application | 2 |
    | yes | both | 3 |
    | no | the element | 1 |
    | no | the application | 1 |
    | no | both | 1 |

    AppKit posts the destruction itself, exactly once, however the client registered. Ours arrives
    once **per matching registration** on top of that. So the bridge releases and says nothing, and
    §2.2 and §2.4 now say so.

    **The reentrancy half is closed too, and it moves the rule's justification rather than the
    rule.** Obeyed, the design does what it promises: a destruction driven from inside
    `-accessibilityChildren` is refused under the caller, logged as deferred, and discharged on the
    next ordinary frame, where it posts and releases. The walk that provoked it completes.

    **Violated, it did not crash — in four shapes, none of them.** The rule was carried as "the one
    that crashes if it is missed", and that is now an over-claim. Releasing was driven from inside a
    live AX callback against, in order: an unrelated grandchild; a child of the node whose children
    were being answered, which releases the `NSArray` that call is about to return; the same again
    over three runs; and finally the **receiver of the running message itself**, so the callback
    returned into a deallocated object. Every run completed the walk and exited normally.

    **The reason is worth more than the result, because it is about this whole boundary.** An
    out-of-process client never holds our pointer: an `AXUIElement` is a remote reference the AX
    server resolves, which is exactly why the destruction half above got a clean `-25202` instead of
    a crash. So the spike's "obvious crash vector" is obvious only for an *in-process* holder, and
    everything this record has measured is out of process.

    **The rule stays, and only its reason changes.** Releasing an object under a caller that holds
    it is undefined, and "did not crash in four shapes" is not "is safe" — it is the same class of
    claim §13.24 already refuses to let anyone build on. What must not survive is the sentence that
    told a reader the consequence would be loud: it would be silent, and a bridge that violated this
    would pass every run in this lab.
21. **A macOS tree has never been mutated, nor been more than one element deep.** Adding and removing
    children is the operation a screen reader's world is made of, and the spike's `AXChildren` array
    was set once and never touched. Two things ride on this. §5.3's per-frame publish is verified on
    the other two platforms only. And §2.2 declines to implement `accessibilityHitTest:` because
    AppKit hit-tested the spike's *one* element from its frame — whether it does the same through
    several levels of nested elements is the same probe run's second question, and implementing the
    selector is the fallback if it does not.

    **The depth half is answered, and it took the fallback.** The phase 7 probe
    (`scripts/a11y/macos/`) published three levels below the pushed array and an out-of-process
    client walked all of them: `-accessibilityChildren` on our own class answers every level below
    the root's children, so §2.2's "the top is pushed, everything below is pulled" is true **of the
    walk**. It is false of hit-testing. Three points, each inside a different grandchild, all
    resolved to the grandchild's *grandparent* — the level that was pushed — and pushing
    `setAccessibilityChildren:` at every level as well changed nothing, which rules out the obvious
    explanation that AppKit's default reads a stored array instead of sending the selector.
    Implementing `accessibilityHitTest:` on our own element class resolved every probed point to
    the correct deepest node. §2.2's row now carries the mechanics, including the one that decides
    how the override is written: AppKit sends it **once**, so the override recurses to the bottom
    itself rather than descending one level and being asked again. §13.16 stays withdrawn — the
    override is on our class, not on GLFW's.

    **The mutation half is answered too, and §2.2's re-push rule survives its own control run.**
    Against one live provider — mutating between walks, because restarting it between them would
    change the elements as well as the tree — three shapes were driven: a child added under a
    grandchild, a child added to the root, and the root's first child removed with its whole
    subtree. All three appeared correctly. Then the same three with the re-push suppressed:
    the deep add still appeared, because it is a pull, and **neither root-level change appeared at
    all** — the removed group was still walked, with its children, and the added one was not there,
    while the provider's own model had already changed. That is §2.2's "the pushed array is a
    snapshot AppKit holds and nothing re-derives it", demonstrated from both sides. It is also the
    exact shape of the bug this rule exists to prevent: an in-scene modal is a child of the root, so
    the failure a missing re-push produces is a dialog that opens and is never announced.
22. **~~`accessibilityFocusedUIElement` has no proven home on macOS.~~ Closed: it has one, and it is
    the content view.** Our elements are not responders,
    so it is not obvious that AppKit will ask us at all, and the spike never moved focus. Posting
    `AXFocusedUIElementChanged` at application level *is* proven to be delivered; being able to answer
    "where am I" afterwards is not. The experiment is one probe run: move focus between two elements,
    read `AXFocusedUIElement` off the application element, and see whether it needs an implementation
    on the content view's class after all — which would reopen item 16 for exactly one selector.

    **One fact is in, and it sharpens the worry rather than settling it.** `NSAccessibilityElement`
    does not declare `-accessibilityFocusedUIElement` at all; `NSView` does, with encoding
    `@16@0:8`. So the selector genuinely lives on the responder side of the hierarchy, which is why
    the probe reads its encoding by searching AppKit's classes rather than asking the one class our
    elements descend from.

    **And AppKit does not route the question to an element of ours: it asked them zero times.** With
    the selector installed on our element class alone, a full VoiceOver cycle entered it 0 times
    while VoiceOver — told each time that the focus had changed — landed on the first element and
    never moved. Installed on the content view instead it was entered 8 times in the same run, and
    the answers alternate `BUTTON`, `CHECK_BOX` with the probe's own focus cycle. So item 16 reopens
    for exactly one selector, as this item predicted — but **not** in the shape it was withdrawn in:
    `class_addMethod` on `GLFWContentView` would change every window in the process, so the bridge
    allocates a subclass *of* that class and re-points one instance's `isa` at it. The effect is one
    view wide and GLFW's own class is left as it was found.

    **What the same run settles about the actions**, which §2.2 could only claim for the spike's one
    element: an out-of-process client finds the button by name, and `AXUIElementPerformAction`
    arrives in Java as the widget's own `onAction`. The seven perform selectors are gated per node
    by `isAccessibilitySelectorAllowed:`, so the button advertises `AXPress` and `AXConfirm` and
    nothing else, and a node with no verb advertises none.
23. **~~No non-ASCII string has crossed the macOS boundary.~~ Closed by the phase 7 probe run.** The
    guest runs a pt-BR system and every name in the spike was ASCII. This ADR's entire naming model
    is `I18nString`s resolved under a subtree locale, so a bridge that mangles UTF-8 into `NSString`
    breaks the feature rather than a corner of it. The probe published `Salvar 𝄞` (U+1D11E, a
    surrogate pair in Java and four bytes in UTF-8) and `שלום עולם`, and an out-of-process client
    read both back with **identical code points** — printed as scalars in both places, because the
    glyphs are not evidence: an astral character that arrives as two replacement characters still
    looks like text, and a reversed right-to-left string looks the same either way.

    **What the control run adds is the part worth carrying forward.** The same probe run with the
    names deliberately encoded as ISO-8859-1 — the mistake a bridge makes by writing `getBytes()`
    and taking a default — did not produce mojibake. `stringWithUTF8String:` returns **nil** for
    invalid UTF-8, so `Formulário` and `Rodapé` arrived as nodes with **no name at all**, while the
    ASCII-only names passed. That is the failure mode to expect on this platform from an encoding
    bug: not a garbled name, a missing one — which is worth knowing precisely because a missing name
    is what §12.1's "every focusable node named" invariant is aimed at, and it would only catch this
    if it asserted over the string the platform hands back rather than the one the toolkit holds.
24. **The mechanism behind the 2 s cliff is not established.** The observation reproduces; the cause —
    whether the AX server stops forwarding to a process it has marked unresponsive, or the requests
    never reach the run-loop mode `glfwPollEvents` services — was not determined, and the failure
    latencies do not match the messaging timeout that was set. **It is also not a clean cliff:** the
    verifier's sweeps found one traversal completing at a 2 s interval, in 17.6 s, against a
    consistent ~19 s failure otherwise, which is a badly-behaved boundary rather than a threshold. It
    changes no decision here, because the rule either way is "do not block the UI thread". It is
    recorded so that nobody later builds a recovery strategy on a guess about which one it is, and
    so that nobody re-derives the categorical version of the claim the verifier already falsified.
25. **§7's table is a survey and will still be wrong in places when phase 4 starts.** This is stated
    as a risk rather than hidden as a caveat, because the failure mode is specific: someone
    implements a row literally, the headless invariants pass — a node exists, it has a name, its role
    is not `UNKNOWN` — and the widget is described incorrectly in a way no invariant can name. §7.2
    lists nine such rows found in a single review pass, which is the evidence for how many remain.
    The mitigation is phase 4's third step, adversarial verification per component, and it is
    labour rather than an assertion. Anyone tempted to skip it should read §7.2's list and ask which
    of those an invariant would have caught.
26. **~~`AccessibleGalleryTest` runs against eighty scenes and has no reference answer.~~ Closed
    2026-09-07, and the first half of the sentence was never true**: no test of that name exists in any
    module — this record named it in three places and phase 4 never wrote it — so its invariants were
    asserted gallery-wide by nothing, and there was not even a headless way to build a demo scene.
    What closes the item is the complement it asked for. `AccessibleTranscriptTest` in `limn-demo`
    builds `forms`, `components` and `kitchen-dialog` as `--scene` does, binds them to a window with no
    platform behind it (`limn.demo.a11y`, the repository's first headless construction of a demo
    scene), and compares a transcript of the published tree — one line per node in tree order: role,
    name with provenance, description, spoken states, facets, verbs, relations by line, and no
    rectangle — against a golden under `src/test/resources/limn/demo/a11y/` that was reviewed by being
    read aloud. A golden is never rewritten silently: `-Dlimn.a11y.transcripts.update=true` is the one
    switch, and without it a changed transcript fails with the differing lines. The first reading found
    what no invariant names — focusable text areas, sliders and spin buttons with empty names beside
    captions that name nothing, combo boxes named after their own value, a `PLACEHOLDER` provenance on
    a field with no placeholder, a demo button class arriving as an empty group, a modal whose title is
    heard twice — and those are §7.2's and the demo's to take. **The toolkit's share was taken the same
    evening**, four commits, each with the transcript lines it changed read and accounted for: the
    provenance, `HAS_POPUP` on every plain text control, the title heard twice, and one the reading had
    not named — the owner of a *native* modal published as fully operable (§1.13, §7.2). The demo's
    share — the empty names, since it never calls `Label.setLabelFor`; the combos; the empty status
    label; the three nameless pictures; its own button class — is left to the demo, by decision, until
    it is simplified. The gallery-wide invariant test was written the same evening, over a gallery
    built for it rather than over the demo (§12.1); two things it taught are worth a line: a
    headless window must answer a display or `PopupMenu` shows nothing and every invariant passes
    over the nothing, which is why an entry promises its roles; and the model cannot publish a
    synthetic child as disabled, so a segment strip's dead chevron is heard as a button that does
    nothing — a model edge, left open here.
27. **~~Whether a cross-window relation can name AppKit's own window object.~~ Closed by the phase 7
    probe run: it can, and the fallback is not needed.** §2.2 elides the window
    root on macOS because AppKit already vends the window, and §1.11 drops any relation that resolves
    to a window root in the in-scene mounting — which leaves exactly one case: a *native* popup or
    dialog whose `POPUP_FOR` names the owner window's root. The design answered it with the object
    AppKit vends for that window, and that was reasoning rather than measurement: no relation of any
    kind had crossed this boundary.

    The probe hung `accessibilityLinkedUIElements` — the general relation attribute here, and the
    one whose encoding the dump script records — on a button, with the window object as its single
    target. An out-of-process client read the attribute, **followed it, and got back a working
    element**: `linkedTo=[AXWindow'Limn phase 7 probe']`, resolved to its role and its title. The
    provider handed over the raw `NSWindow` it holds and the client received an `AXUIElement` it
    could query. What made this worth measuring rather than assuming is that presence and
    resolvability are different facts, so the client resolves every target it is given and reports
    the role it found rather than reporting that the attribute exists.
    **A second shape of the destruction half, found by the §13.19 run on 2026-09-07 and fixed:**
    closing a window with VoiceOver on was a SIGSEGV in `liblwjgl`. `Scene#windowClosed` detached
    the bridge, which freed its libffi closures; `glfwDestroyWindow` then pumps the run loop, and
    VoiceOver's `accessibilityFocusedUIElement` reached the content view still swizzled onto the
    bridge's class and called a freed IMP. A detach now gives the content view its class back and
    points every released element at `NSAccessibilityElement` *before* it frees anything; a test
    pins that order, and with the free first it fails. Reproducible before the fix by any Limn
    application closing a window under VoiceOver, so it was not a probe's problem.
28. **~~The Windows bridge raises on the UI thread, and a raise waits for the reader.~~ Closed
    2026-09-07, the same day, by the queue and the drain thread §1.10 and §3.4 had described all
    along**: `emit` offers to a bounded queue and `limn-a11y-uia-drain` raises; `NODE_DESTROYED`
    releases on that thread, a collapse sweeps and invalidates, and the whole-registry empty stops
    and joins it first. Same drag, same NVDA: the emit on the UI thread went from 2.5 ms median to
    26 µs, the raises took their 1–3 ms on the drain thread, and the five budget warnings of the
    inline run became one, which is the probe's own tick. What follows is the finding as it was
    made. Found by the §13.19 count on 2026-09-07. §1.10 says handing over is not raising, on any of the three, and that
    a bridge raising inline would spend the frame budget inside `UiaRaiseAutomationEvent`; the Windows
    bridge nevertheless raises straight through from `emit`, on the belief — written in its own
    javadoc — that the call returns without waiting for a client. With NVDA attached it does not:
    `UiaRaiseAutomationPropertyChangedEvent` for a slider took **2.5 ms median, 16 ms max**, a row event
    while paging a list 166 µs median with **one raise of 50 ms**, and `UiRuntime` logged "took 37 ms
    (budget 8 ms)" — the call returns after the client's handler has run, and NVDA's handler calls back
    into the provider before it returns. The count itself is small (21 events per frame paging a
    300-row list, 5 of them reaching the platform; 1 per frame dragging a slider), so capacity is not
    the Windows question and never was; **where** the raise happens is. The queue §1.10 asks for is
    therefore still owed on Windows, and it is a threading change — a raise thread of the bridge's
    own, with §3.4's answer for who owns the queue and what a raise may touch after the UI thread has
    moved on — not a constant. It was deferred to a pass of its own and then done the same day
    when he asked for it; the line above says what the run showed.
29. **~~Paging a list destroys the row the reader's cursor is on.~~ Closed 2026-09-07, in the
    `ListView` and not the bridge.** The row holding the keyboard focus is no longer recycled by a
    scroll or a page that moves it out of the viewport: `recycleExcept` spares the one mounted cell
    that `containsFocus`, it stays in `children()` in data order with its widget and its focus
    untouched, and `placeKeptOutside` lays it out wholly outside the viewport at the distance the
    scroll estimate puts it — so it is not painted, takes no click, and is published as the
    `LIST_ITEM` it is, with its box outside the list's and without `SHOWING`, which is what §1.2
    already says of a scroll pane's content and what this widget's own half-off-the-top row already
    did; no state or role was added. `ensureVisible` treats it as unrealized and takes the exact
    jump. It is released by the first realization pass that finds it outside the run without the
    focus, by `refresh()` and by an emptied adapter, which still release everything and drop the
    focus to the list as before; every other row is recycled exactly as it was.
    `ListViewFocusedRowTest` drives Page Down through the scene's key path with the focus in row
    one's button and asserts all of it against the published tree. **What remains:** the protection
    is the keyboard focus's, not the reader's cursor's — a reader standing on a row that holds no
    focusable widget is still not protected, because the toolkit cannot see where a reader's cursor
    is; §11 says so now. Two costs are kept: the spared row's box is an estimate under uneven
    heights, so a Shift+Tab that lands back on it is revealed by that estimate rather than exactly
    (an arrow key then reveals by selection, which is exact); and a Tab out of the kept row goes to
    the first realized row after it, as it always did across unrealized rows. What follows is the
    finding as it was made. Found by the same run on macOS.
    VoiceOver was reading `Linha 1, item de lista`; the page scroll released that row, the bridge
    posted nothing for it (correctly — AppKit posts `UIElementDestroyed` itself) and VoiceOver fell
    back to `Você está atualmente em janela`, then to the scroll bar. The tree is truthful: the row
    is gone. What a reader needs is for the row it stands on to stay alive across a scroll, or for
    the list to move its selection with the page so that the cursor has somewhere to land — either
    is the `ListView`'s decision and not the bridge's, and §11's "not list virtualization" did not
    foresee the cursor case. Open; the keyboard user is unaffected, because the arrows scroll by
    selection and a selected row is always realized.

---

## 14. The work, phase by phase

Each phase ends green with `./gradlew check` and names what it must prove before the next begins.

**Phase 1 — the model.** `limn.accessibility`: `Accessible` with its nested `Role`, `State`, `Action`,
`Politeness`, `NameFrom` and `Relation` enums; the facet records; `AccessibleNode`, `AccessibleTree`,
`AccessibleEvent`; and `Accessibility`, the reusable builder. No walker, no bridge, no widget changes.
*Proves:* the enums are closed and every constant has a row in all three tables of §2.

**Phase 2 — the toolkit seams.** The four `Widget` hooks, the four public setters, one nullable field,
`invalidateAccessible()` **and `Scene#announce`, each buying its own frame through
`scheduleFrame()`**; **the
node flag on the damage funnels and the structural funnels both, and the header flag beside it**;
`tooltipSource()`, `inheritanceHost()`, the `text()`/`textSource()` pairs on `Checkbox` and
`RadioButton`, `SegmentedControl`'s `I18nString` segments, `TabbedPane#tabTitleSource` and
`TextField#setTrailingButton`'s named overload; `TextEditModel#revision()`; the cache counter and
accessor on every widget that formats a string for the screen, `Spinner` first (§8); `setTooltip`'s
invalidation **and `setFocusable`'s**, which is the one entry point that reaches nothing at all
today; the screen fields on the two headless doubles; and the small corrections that are
defects on their own terms — the parked-loop wake for a UI-thread post and for a frame request, with
`UiRuntimeTest`'s UI-thread-post assertion rewritten to the new contract (§8),
`CrashPhase.ACCESSIBILITY`, `Checkbox#toggle()`'s enabled guard and `MenuItem`'s minted key. No tree
yet, and **not** `windowMovedTo`'s notification, which needs the `Host` phase 3 defines. *Proves:*
`AccessibleIdleCostTest` — with nothing listening, a frame is byte for byte what it was — and that a
post from the UI thread inside the pump runs without waiting for input.

**Phase 3 — the tree, the diff and the SPI.** The walk over root and overlays into the reused scratch
buffer, transparency and hoisting with its warning **evaluated on own-declared facts**, the modal
marking from `inputRoot()` and the inherited enabled/visible/focusable flags carried on the same
traversal, **identity minted over the widget tree** with owner-chosen keys and the intern table, the
compare-as-you-walk over the scratch buffer's columns with names and text compared by source, the
raised window events, the publish step in `renderFrameImpl` with its announcement drain above the
re-present guard and its first-frame priming publish, `republishNow()`'s never-laid-out precondition,
the re-stamp path and `windowMovedTo`'s call into it, the bounded event queue whose events carry
their own values and whose collapse is a reconciliation, **the action dispatcher — the id-to-owner map
the scene owns, read only on the UI thread, reached only through `Host#perform`** — the
`NativeWindow` default member, `AccessibilityBridge` with its four-member `Host`, its
reentrancy-flagged `publish` and its host-replacing `attach`, and `RecordingAccessibilityBridge`. *Proves:* the focus-order, identity, inherited-state, first-frame,
announce, registry, mirroring, locale, scale, event, live-mutation, quiet-frame, modal, recycling,
ticking-value, window-move, window-event, action, reentrancy, event-budget and publish-cost tests.

**Phase 4 — every component describes itself, one component at a time.** This is not "implement §7's
table"; §7 says in its own words that it is a survey. It is a **pipeline, run per component**, and
the component is not done until all three steps are:

1. **Map.** Read the widget's own source — its paint, its hit-test, its geometry, its state — and
   write its `onAccessibility` and `onAccessibilityChild` against that, correcting §7's row wherever
   the two disagree. The row is the starting point and the source is the authority.
2. **Test, headlessly.** The invariants of §12.1 applied to this component: every focusable node
   named, no `UNKNOWN` role, bounds inside the clipping ancestor, ids stable across the mutations
   this component performs on its own children, and a `AccessibleLiveMutationTest` case for each of
   its public setters.
3. **Verify, adversarially.** Someone reads the produced tree against the widget's code looking for
   the row that is still wrong — the operable control with no node, the box that is not where it is
   painted, the name that resolves to nothing. §7.2 is the standing list of what that step has found
   so far and is expected to grow.

Together with the three names and the menu-model changes §8 lists. *Proves:* `AccessibleCoverageTest`
and `AccessibleGalleryTest` — no `UNKNOWN` role anywhere, every focusable node named — and, per
component, that its row is now true rather than plausible. *(The second test was not written by
this phase; §13.26 records that, and it was written on 2026-09-07 over a gallery built for it —
§12.1 has the row.)*

**Phase 5 — the Linux bridge**, and `scripts/a11y/linux/` with it. The D-Bus client with the spike's
golden vectors, **the reader and writer threads with the rule that neither performs the other's
blocking operation** (§3.3), the process-wide application object with one `frame` per window and
§3.4's owner for every process-wide structure it implies, `Cache.GetItems` from the pre-marshalled
snapshot, the event signals with replies exempt from the queue's bound, and the enabled gate. It is
first because it needs no native code, it is the only one that might reach CI, and it exercises the
whole model against a real screen reader. *Proves:* Orca reads the demo on Ubuntu GNOME and Fedora
KDE, X11 and Wayland; and, if `dbus-run-session` works on the runner, a CI gate.

**Phase 6 — the Windows bridge**, and `scripts/a11y/windows/` with it. The WndProc subclass with the
synchronous build inside `WM_GETOBJECT` and its reentrant publish, the four proven interfaces plus the pattern providers the facets need — including
`IValueProvider` over a `TextFacet` — the runtime ids, `IsControlElement` and `IsContentElement`,
**the concurrent element registry with `computeIfAbsent` minting, removal on the drain thread, and
the two whole-registry empties on the UI thread with the drain stopped** (§3.4), the event raises off
that thread, the listening gate, and the typed layer that pairs
each call interface with its decode. *Proves:* a client attaching to an idle window gets a tree, NVDA
reads the demo, the golden client transcript matches, and the subclass benchmark is recorded.

**Phase 7 — the macOS bridge**, and `scripts/a11y/macos/` with it. First the probe run §13.20 to
§13.23 and §13.27 name — a mutating tree, a destroyed element, focus moving, a non-ASCII name, and a
relation naming AppKit's own window object — because the spike proved one static element
and four of this phase's decisions are only as good as that generalises. Then the runtime
`NSAccessibilityElement` subclass; the attribute and action implementations on it; the **first-frame**
walk and the `setAccessibilityChildren:` push, which are the attach and the gate together, **with the
re-push whenever the root's children change** (§2.2); lazy element allocation in a UI-thread-confined
registry, and release on `UIElementDestroyed`, on a collapse's reconciliation and on `attach`/`detach`
(§3.4, §5.3); **the reentrancy rule, whose violation this lab could not make crash and which is
therefore enforced by discipline rather than by a symptom (§13.20) —
a publish from inside an AX callback releases nothing, re-pushes nothing and drains nothing** (§3.2);
the notifications under their per-frame budget, focus posted at application level because that is the
only registration it reaches; parent-space frames rather than a screen flip; and the encoding
assertions read from the running AppKit. *Proves:* VoiceOver reads the demo — which is
the one thing every client in the spike is not.

**Phase 8 — the documentation.** `docs/design/accessibility.md` with the contributor's background and
the traps; the `docs/adr/README.md` row moved to its implemented status — the row itself lands with
this record, not here; the "what you get" paragraph in `README.md`; and the "no screen reader bridge"
bullet, which has to be rewritten in ten README files, in their own languages. *Then removed,
2026-09-08, with the whole "before you commit to it" section around it and the website's copy of
it, by the owner's decision that none of its three entries is a pending item any more — vertical
writing is not on the road map, and what a screen reader cannot yet do is in §11, the design
note and the guide, where a contributor or an application developer looks for it. In their place
the website's home gained a section of its own for languages and screen readers, whose evidence is
a transcript the capture writes beside every showcase picture — `Gallery` keeps the tree each
window's scene published and writes `Transcript.of` it at the still, the manifest promises the
file like a picture, and the guide gained `{% transcript <id> %}` and an accessibility page. The
guide's own worked examples are held to the gallery's four invariants, which found the form's
validated field and the layout's list and segmented control nameless.*

---

## 15. What is not decided here

Three real questions this ADR reached and did not answer, recorded so they are not silently decided by
whoever implements first.

- **Whether a live region is worth a second mechanism** beside `Scene#announce`. The demo's status
  labels and the media position label are the only evidence, and two call sites are not enough to design
  against.
- **How a chart is best read.** Series nodes are the first cut and a data table is the obvious second,
  but the right answer probably depends on what a user of a screen reader actually does with a chart, and
  nobody here has asked one.
- **Whether the window title should follow the scene.** Every demo scene opens a window with the same
  title, so a verification harness that finds a window by title finds the same name for all eighty. The
  setter exists and the caller holds the scene name; only a decision is missing, and it belongs with
  whoever writes the lab suite.
