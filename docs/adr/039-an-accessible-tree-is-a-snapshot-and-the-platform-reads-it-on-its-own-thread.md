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

`ENABLED` and `READ_ONLY` are separate bits and are never conflated. Every platform separates them —
UIA has `IsEnabled` against `ValuePattern.IsReadOnly`, AT-SPI2 has `SENSITIVE`/`ENABLED` against
`READ_ONLY`/`EDITABLE`, AppKit has `accessibilityEnabled` against the text attributes — and merging
them makes a disabled field announce as read-only and leaves a genuinely read-only enabled field
inexpressible.

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
raises `INVOKED` when a `PRESS` succeeded. **Nothing in `limn-toolkit` calls a bridge except the six
members of §5.2 — `publish`, `emit`, `attach` and `detach`, which tell it something, and
`isListening` and `needsPrimingPublish`, which only ask it something; nothing in a bridge touches a
widget, a scene or a window; and everything a platform asks of the toolkit arrives through the four
`Host` members.** Those three clauses are the whole of the seam. The two questions are named here
rather than elided because the scene asks the first of them on **every frame** (§5.3 step 2) and the
second once per bind: they are the gate §6's whole cost argument rests on, they return a constant on
`NONE`, and a seam sentence that left them out would be describing a cheaper interface than the one
this record specifies. Every row of §2 that says "posted" means this call. All three
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
setter into `perform` with the matching `Argument`.

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
collapses to one window-level event past a threshold, which is what a scroll is.

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
  for the root and the client re-reads.
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
opener carries the mirror `CONTROLLER_FOR`, so a client walking either direction finds the other. The
opener is already known: every popup, menu and dialog calls `Widget#setInheritanceHost` on its root
today for the size, direction and locale chain, and §8 makes that link readable.

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

### 1.12 The role enum is closed, and a role may not be added without a truthful mapping in all three tables

```
WINDOW, DIALOG, ALERT, GROUP, SCROLL_PANE, SCROLL_BAR, SPLIT_PANE, SPLITTER, TOOL_BAR,
MENU_BAR, MENU, MENU_ITEM, CHECK_MENU_ITEM, RADIO_MENU_ITEM, SEPARATOR,
BUTTON, TOGGLE_BUTTON, CHECK_BOX, SWITCH, RADIO_BUTTON, RADIO_GROUP,
LABEL, HEADING, IMAGE, VIDEO, CANVAS, CHART, CHART_SERIES,
PROGRESS_BAR, SLIDER, SPIN_BUTTON,
TEXT_FIELD, TEXT_AREA, PASSWORD_FIELD, SEARCH_FIELD,
COMBO_BOX, LIST, LIST_ITEM, TAB_LIST, TAB, TAB_PANEL,
COLOR_CHOOSER, UNKNOWN
```

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
| `accessibilityHitTest:` | **implemented on our own element class, and it must recurse to the bottom itself** | the spike measured that with no override at all `AXUIElementCopyElementAtPosition` found its one element, and concluded AppKit hit-tests from the frames. It does — **for one level only**. The phase 7 probe run put three grandchildren under a group and three points inside three different grandchildren all resolved to the *group*, which is the level that was pushed onto the content view; pushing `setAccessibilityChildren:` at every level as well changed nothing, so this is not stored-versus-pulled children. Overriding the selector on `LimnProbeElement` resolved all four probed points to the correct deepest node, at depth 3. **AppKit sends it exactly once**, to the element it already resolved from the pushed array, so the override walks the whole subtree rather than returning one level. The point arrives in screen space with a bottom-left origin — the same space `accessibilityFrame` answers in, so no flip of ours. It goes on our own class, so §13.16 stays withdrawn: nothing is installed on a class GLFW owns |
| `accessibilityIdentifier` | the node id, as a string | stable across frames by §1.3 |
| `accessibilityPerformPress`, `…Increment`, `…Decrement`, `…ShowMenu`, `…Pick`, `…Cancel`, `…Confirm` | `ActionFacet` | **press proven end to end**: an out-of-process client's `AXUIElementPerformAction(kAXPressAction)` arrived in Java on the main thread. AppKit's encoding is `B16@0:8`, so the return is a C `bool`. The other six are the same shape and are untested |
| `accessibilityNumberOfCharacters`, `accessibilitySelectedText`, `accessibilitySelectedTextRange`, `accessibilityStringForRange:`, `accessibilityRangeForLine:`, `accessibilityInsertionPointLineNumber` | `TextFacet` | `NSRange` is UTF-16, which is `TextEditModel`'s unit exactly |
| `accessibilityFrameForRange:` | **not answered in the first cut** | §11: there is no geometry seam behind it |
| `NSAccessibilityPostNotification` | the event flush | **proven delivered out of process** to a real `AXObserver`, carrying the updated value. `AXValueChanged` reaches an observer registered on the element *or* on the application element; `AXFocusedUIElementChanged` reaches **only** the application-element registration, so focus is posted at application level and never per element |
| `…PostNotificationWithUserInfo` with `AnnouncementRequested` | `ANNOUNCEMENT` | politeness rides `NSAccessibilityPriorityKey` |

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
walk per window, one frame later, against geometry that exists.

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
| `Action.NActions`, `GetActions`, `GetName`, `GetDescription`, `GetLocalizedName`, `GetKeyBinding`, `DoAction` | `ActionFacet` | `GetActions` is `a(sss)`; the third column is the key binding, where `Accelerator#display()` goes |
| `Value` `CurrentValue` (read/write), `MinimumValue`, `MaximumValue`, `MinimumIncrement` | `ValueFacet` | numeric only; a display form such as a spinner's `07:30` is published through `Text` |
| `Text`, `EditableText` | `TextFacet` | **offsets converted from UTF-16 to characters at this boundary and nowhere else**; `GetRangeExtents` is not answered in the first cut (§11) |
| `Selection` | `SelectionFacet` | |
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
at the instant `WM_GETOBJECT` arrives there has never been a publish, and a bridge that could only
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
     * offer. True only on macOS (§2.2); false everywhere else, so no other platform pays a
     * walk for a window an assistive technology never touches.
     */
    default boolean needsPrimingPublish() { return false; }

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
         * scene that has never laid out is the empty tree — rather than describing geometry
         * that does not exist yet.
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

Six members on the bridge and four on the host; every bridge member is a no-op or a constant on
`NONE`, and none returns anything the toolkit has to interpret. Everything platform-shaped stays
behind it.

**`perform` is on the host and not on the bridge, and an earlier draft had it the other way round.**
That was not an infelicity, it was a path that does not exist: `perform` declared on the bridge is
implemented by the backend, and the backend cannot implement it, because the map from a node id to its
owning widget is the scene's and may be read only on the UI thread (§1.9) — so nothing in the toolkit
ever called it and nothing in a bridge could have answered it. Actions are half of what a screen
reader does with a tree, so the direction is stated once and holds everywhere: **inbound calls go
platform → bridge → host; outbound calls go scene → bridge.** A bridge still holds exactly one object,
and it is the host it was handed at `attach`.

`needsPrimingPublish` is the whole of the macOS asymmetry in §5.3's per-frame step: the scene asks
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
they are a fabrication. On Windows the distinction costs nothing, because a window that a client can
attach to is a window that has painted, and a window that has painted has laid out. It bites only in
the interval between `bind` and the first frame, which is exactly where the macOS push used to be.

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
   bind and only when `bridge.needsPrimingPublish()` said so at bind — which is macOS and nothing
   else, so §6's promise that Windows and Linux pay nothing for an untouched window is exact.
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
from a `default` method. Nothing else — no walk, no builder, no map, no allocation. Per bind: one
further call to `needsPrimingPublish()`, which `NONE` also answers from a `default`, and which
therefore never causes a walk. Per widget: one nullable reference field, never read.

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
  alone pays something before the gate opens:** one tree walk on the scene's **first frame**, to have
  elements to push onto the content view at all (§2.2). Not at bind, where the scene has never laid
  out and every box would be zero (§5.2). After that walk and until a client touches one of those
  elements, a frame does nothing here either.
- **Linux:** `org.a11y.Status.IsEnabled` on the session bus, read once when the first window opens and
  refreshed on `PropertiesChanged`, **and** a completed `Socket.Embed`. When accessibility is off, no
  a11y bus connection is opened and no thread is started.

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
| `Label` | `LABEL`, or `HEADING` for the title typographic role | name from `textSource()`, `nameFrom=CONTENT` | — | gains `LABEL_FOR` when an application declares the relation |
| `Button` | `BUTTON` | `ActionFacet{PRESS}`; `DEFAULT` when it is a dialog's default | — | name from `textSource()`, else the tooltip; the action reaches the private path through the widget's own hook |
| `Checkbox` box / switch | `CHECK_BOX` / `SWITCH` | `ToggleFacet`, `ActionFacet{TOGGLE}` | — | name from its own label, `nameFrom=CONTENT` — the field is a private `I18nString` with no getter today and §8 adds the `text()`/`textSource()` pair, because a focusable node with no name fails `AccessibleGalleryTest`. `toggle()` has no enabled guard of its own — the guard is the scene's, which never delivers an event to a disabled widget — so the accessibility path re-checks `isEnabled()` (§1.9) and `toggle()` gains the same guard (§8) |
| `RadioButton` | `RADIO_BUTTON` | `SelectionItemFacet`, `ActionFacet{SELECT}`, `MEMBER_OF` its group with position and size of set | — | name from its own label, `nameFrom=CONTENT`, through the same pair §8 adds. Roving focus means only the holder is `FOCUSABLE`, which is correct and is what the reader should hear |
| `ButtonGroup` | no node | | | not a widget and has no bounds; it contributes position and size of set to its members |
| `SegmentedControl` | `RADIO_GROUP` | `SelectionFacet`, `ScrollFacet` when it overflows | one `RADIO_BUTTON` per segment, keyed by index, named by its segment, **plus the two overflow chevrons** | **Corrected:** when the strip overflows it clips to a viewport with a chevron in each gutter, each of which scrolls by most of a viewport and is drawn disabled on the dead side. They are operable controls and may not be dropped (§1.6), and a segment scrolled outside the viewport is not `SHOWING`. Their keys, names and the dead-side disabled state are the pipeline step's. **Not `TAB_LIST`**: its own documentation says it owns no content — it takes labels and hands back an index — so there is no `TAB_PANEL` for a tab to select, and announcing "tab, 1 of 4" would offer page navigation that leads nowhere. Its segments are a `List<String>` and not `I18nString`s, so a segment name cannot follow the subtree locale as §1.7 requires of every other name; §8 gives it the `I18nString` list it should have had |
| `Slider` | `SLIDER` | `ValueFacet{min,max,step}`, `ActionFacet{INCREMENT,DECREMENT}`, `HORIZONTAL` | — | the hook reaches the private from-user path **and then the commit**, so a set from an assistive technology notifies the application the way a key press does (§9, §7.2). The facet's step is the field, `0` when continuous; the two verbs move by the keyboard's nudge and never mirror |
| `Spinner` | `SPIN_BUTTON` | `ValueFacet` with the display form as its text, `ActionFacet{INCREMENT,DECREMENT}` | the up half and the down half, keyed by their region numbers | the widget's `regionAt` answers exactly three things — value area, up, down — so there are no hour and minute *hit targets* to publish. **Corrected in its reasoning, not in its answer:** that is a fact about pointer input, and it does not by itself settle what a reader should be offered for a `07:30` value, which is a question about the inline edit model this row never read. The pipeline step decides, against that model. No `EDITABLE` state in the first cut for the same reason |
| `ProgressBar` | `PROGRESS_BAR` | `ValueFacet{0..1}` read-only, rounded to whole percent, or `BUSY` when indeterminate | — | same rounding rule as `VideoView`, for the same reason |
| `TextField` | `TEXT_FIELD` | `TextFacet`, `EDITABLE`, `INVALID` from `validation()` | the trailing button when one is set | **Corrected:** `setTrailingButton` takes an `Icon` and a `Runnable` and **no label**, so the synthetic child this row promises has nothing to be named from — an operable control that cannot be ignored (§1.6) and cannot be named either. §8 adds an `I18nString` name to the setter; the existing overloads keep working and give an unnamed button, which the gallery test then refuses. Name from the placeholder when nothing else supplies one. The `TextFacet` is also what vends `ValuePattern` on Windows and `accessibilityValue` on macOS, which is the whole of a text control's behaviour there until `TextPattern` lands (§2.1, §11). **Never `READ_ONLY` from disabled** — the widget has no read-only mode today, so the bit stays clear |
| `PasswordField` | `PASSWORD_FIELD` | `TextFacet` **masked** unless `isRevealed()`, `PASSWORD` | — | one mask character per grapheme cluster, never the secret. The published string is `caretCount() - 1` mask characters long, and **the facet's caret and selection are in the mask's own offsets**: a model offset `i` becomes `display.caretOrdinal(i)`, which is the shaped line's own inverse and needs no second rule for finding cluster boundaries. The model's offsets are the secret's UTF-16 offsets and would run past the end of a mask shorter by an astral character. **The shaped line is read for its caret stops and never for its `text()`, which *is* the secret** — the class marks that line `TRAP` in its own source (Finding 14), and this is the second reader of it |
| `SearchField` | `SEARCH_FIELD` | as `TextField`, plus `ActionFacet{PRESS}` for submit | the clear button | the clear button is operable and is named, not ignored |
| `TextArea` | `TEXT_AREA` | `TextFacet` with lines, `MULTI_LINE`, `ScrollFacet` | — | soft-wrap rows are reported as lines |
| `ComboBox` | `COMBO_BOX` | `ExpandFacet`, `HAS_POPUP`, `ValueFacet` text = the selected item | — | the options are **not** here; see the next row |
| ↳ `ComboBox.PopupPanel` | `LIST` | `SelectionFacet` with an active descendant | one `LIST_ITEM` per option, keyed by index, `SELECTED` on the chosen one | described in the scene it lives in, so its bounds are right in both mountings (§1.11) |
| ↳ `ComboBox.ScenePopup` | transparent | | | the overlay wrapper |
| `ListView` | `LIST` | `SelectionFacet` with an active descendant, `ScrollFacet` | — | rows are real pooled widgets mounted directly (Finding 14); `onAccessibilityChild` gives each mounted row `LIST_ITEM`, `SELECTED`, its data index as position in set, `rowCount()` as size of set, and **its data index as the identity key** (§1.3), which is what keeps a recycled cell from carrying row 3's identifier to row 9. **Corrected:** a row also needs `ActionFacet{PRESS}` mapped onto `ListView#activate()` — Enter activates the selected row and fires `onActivate`, and a row published with `SELECTED` and no verb is a list a screen reader user can move through and cannot use. Unmounted rows are not published (§11); a scroll never unrealizes the row holding the keyboard focus, which is published with its box outside the list's and without `SHOWING`, as the half-off row already is (§13.29) |
| `TabbedPane` | transparent | | | the pane itself is scaffolding |
| ↳ `TabStrip` | `TAB_LIST` | `SelectionFacet` | — | |
| ↳ `TabHeader` | `TAB` | `SelectionItemFacet`, `ActionFacet{SELECT}` | — | name from the tab's own `I18nString` title, `nameFrom=CONTENT`. It is a private inner widget of `TabbedPane`, so it reads that title from inside its own package and needs no new accessor for this ADR — ADR 040 needs one for a different reason (§9) |
| ↳ selected content | `TAB_PANEL` | `LABELLED_BY` its header | — | an unselected panel's subtree is under a widget whose own `isVisible()` is false, so by §1.2's inherited rule it and everything in it publish without `VISIBLE` and without `SHOWING` — which is also what stops a hidden tab's controls from announcing as focusable |
| ↳ `StripButton` | `BUTTON` | `ActionFacet{PRESS}` | — | unnamed today; §8 gives it a name |
| `MenuBar` | `MENU_BAR` | — | one `MENU_ITEM` per title, `HAS_POPUP`, `ActionFacet{SHOW_MENU}`, keyed by index | |
| `PopupMenu.MenuSurface` | `MENU` for the root column | `SelectionFacet` with the active descendant, `MODAL`, `ScrollFacet` on a column that scrolls | one node per open column and one per row of each, keyed by the `MenuItem`'s minted serial | **bounds come from `Column`, never from the widget box**, which is the whole scene either way (Finding 14). The root column's rectangle is the surface node's; each further column is a `MENU` node under the row that opened it, which is the nesting all three platforms expect. **Corrected:** a row's rectangle is *not* simply its `top[]`/`hgt[]` — a column has a `visibleH` that can be less than its content, a scroll offset, and a clip inset the rows are painted inside, so a long menu's row boxes must be offset by the scroll and rows outside `visibleH` published as not `SHOWING`. Taking `top[]` at face value puts a screen reader's cursor on rows that are scrolled away |
| `MenuItem` | as above | `ToggleFacet` for check and radio kinds; `ActionFacet{PRESS}` or `{SHOW_MENU}`; key binding from `Accelerator#display()` | — | the action is offered only when the row is selectable **and** is not a submenu with nothing in it, because `hasSubmenu()` is false for an empty submenu while `isSelectable()` stays true |
| ↳ the highlighted row | | `SELECTED` on the row, active descendant on its column | — | `Column#highlight` is an `int` today and reaches the tree through the column's `SelectionFacet`; moving it raises `ACTIVE_DESCENDANT_CHANGED`, which is how a keyboard walk down a menu is announced at all. Without it a reader can see the rows and never learn which one the user is on |
| `Dialog` panel or overlay | `DIALOG` | `MODAL`, `ActionFacet{CANCEL}`; `WindowFacet` **only** when it is a real window | — | in scene it is a dialog node inside the owner's tree, with `UIA_IsDialogPropertyId` on Windows |
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
| `ListView` | the row above — "a row also needs `ActionFacet{PRESS}` mapped onto `ListView#activate()`; selection without a verb is not usable" — is **this list's own `ContextRegion` defect, two lines below the entry that records it**, and §7's `ListView` row is wrong or silent in six places against the source besides; each is pinned by `limn.components.ListViewAccessibilityTest`. **Corrected by the step.** *The verb on a row is undispatchable:* a row is a **widget** child, so the walk records the application's own cell as the node's owner and the scene dispatches strictly there with no fallback to a parent — a child key changes identity, never ownership — where the hook is `Widget`'s and answers false. It is worse here than for the region, because `Host#perform` has already returned true to the platform from its snapshot check, so the platform is told the action was accepted and nothing happens: a silent failure rather than a reported one, and §1.5's settled rule ("every `onAccessibilityChild` in the toolkit writes facts; none writes a verb") is what the row breaks. *And "mapped onto `activate()`" is the wrong call even where it could be delivered:* `activate()` fires `onActivate(selectedIndex)`, so a press on row seven while row three is selected opens record three; the faithful analogue of the pointer gesture is select-then-activate, which is what a verb acting on the selection gets for free. So `PRESS` goes on the **list**, which owns it and can perform it, and only while `selectedIndex >= 0`, because a verb that can only no-op is what the scroll bar's step refused. *The row is silent on where a row's name comes from, and the common case has none:* a cell is an application's widget, the demo's paint their own text over five thousand rows, and the `LIST_ITEM` this hook writes **suppresses** §1.6's paints-and-says-nothing warning that would have been the application's only notice — so without asking the adapter the flagship case publishes five thousand nameless list items in silence. `Adapter#rowName`, added by §8 and until this step called by nothing anywhere, answers it, and its javadoc's "when its own widget is not mounted" was too narrow by exactly that case: it is asked once per realized row whose cell declared no name, and its contract gains the sentence that makes it affordable — hand back a string the adapter **holds**, because the comparison is by reference and one built per call allocates per damaged frame and republishes the tree every frame. *And silent on where an unmounted selected row's name goes*, which is the gap `rowName` was invented for: the list node's **description**, and only while that row is unrealized, since a realized one carries the name and `SELECTED` itself and a second copy is §1.7's twice-spoken name. Not `valueText`, which is written and dropped without a value facet, as the colour picker's step settled; and not a synthetic phantom row, which is declared before the widget children and would put a selection below the viewport ahead of every realized row. The known cost, written down: while that description stands, the walk's tooltip-as-description default has nowhere to go on a list carrying both an application name and a tooltip. *"`SelectionFacet`" does not say which shape, and the naive reading is wrong:* `required` is **false unconditionally**, not the tab strip's `selected >= 0`, because this class documents the opposite invariant to the combo's and the pane's — "a list is one of the two widgets in this set that genuinely has no-selection as a state", `clearSelection()` reaches it and `refresh()` returns to it on an emptied adapter. The active descendant is not declared, as everywhere: it is resolved in the copy from the first `ACTIVE` node in the subtree, and here the selection **is** the cursor — one highlight, unlike the combo's, with focus staying on the list while it moves — so the selected row is marked `ACTIVE` as well as selected, which the tab header's step refused for a reason that does not hold there (roving focus had already made the selected header the focused node). The model's known cost comes with it unchanged: a list nested in another list's row cell hands the outer list the inner one's selected row as its own cursor. *And silent on the scroll facet's derivation and on the half-point slop:* the facet is published unconditionally, as the scroll pane's and the strip's are, so a resize past the fitting point moves two booleans rather than making a facet appear and disappear — but with **no** slop, unlike the strip's, because this widget's own wheel gate and `scrollBy` clamp use the bare subtraction and really do move a list overflowing by three tenths of a point, where the bar declines to exist at all; a slop-gated boolean would advertise a refusal the widget does not make. No orientation, unlike the slider's and the strip's: a list is vertical by default on all three platforms, its value has no axis to run along, and the nearest widget in the toolkit — the combo's popup panel, also a vertical `LIST` with a scroll facet — declares none. *And the open bounds item, "`ListView`'s mounted cells", is now asked and needs no correction:* `placeDown` lays every cell out with `layoutBox` and `scrollBy` calls `moveChild`, which writes x and y immediately rather than deferring an offset to the next layout, so the free box is the painted rectangle at every instant and none of the menu column's bug is present; the default clip is the whole box, which is exactly what `paintChildren` clips rows to, so unlike `ScrollView` this widget owes no per-child clip accessors, and under a reserved strip the rows are laid out narrowed to the viewport and never extend into it. Two consequences are kept rather than fixed: a row scrolled half off the top publishes a box whose top is above the list's own, because that is where the row is and `SHOWING` is what says how much of it is on screen; and the selection ring is painted across the list's full width, which under a reserved strip is wider than the cell — a mark and not a node, so the row's box stays the box a click lands in. **What is in no row at all, and no existing test could have caught:** `children()` fell out of data order on any upward scroll. `Widget#add` appended, and the anchor's renormalisation mounts the rows **above** the anchor walking upward, so a hundred-point list scrolled down three rows and back left `children()` holding 3, 4, 2, 1, 0 — and `children()` is reading order and Tab order both, so a reader heard "4 of 5000, 5 of 5000, 3 of 5000" and a Tab through rows holding buttons walked them the same way. `AccessibleFocusOrderTest` cannot see it, because both sides of that invariant read `children()`. `Widget` gained `add(int, Widget)` with `add`'s existing guards — re-adding to reorder is not the alternative, since `remove` detaches the subtree and revokes the focus, hover and press inside it — and the list mounts into the position that keeps the bar at zero and the cells ascending. Its map of mounted cells became two parallel arrays in data order at the same time, which is what makes the insert position free and what lets both describe hooks run under the zero-allocation rule: a `Map<Integer, Widget>` boxes its key for any row above 127 and allocates an iterator per sweep, on hooks that run on every damaged frame |
| `ListView` per-row actuation | **raised rather than slipped in.** A reader cannot select or activate a *specific* row, and buying that back is bigger than a describe step, so it is recorded here for the owner. Two routes exist. **(a) A model change** (§1.3, §1.9): an `Accessibility#delegateActions()` callable only from `onAccessibilityChild`, after which the walk records the **parent** as the node's owner and the child key as the synthetic key, so a row's verb reaches `ListView#onSyntheticAction(rowIndex, …)`. It has a real conflict to settle first — a cell that is a `Button` declared `PRESS` in its own hook and would have it stolen — and §7's own preamble says the model is a specification that cannot be corrected one component at a time. **(b) A structural change**: a private `RowHost extends Widget` wrapping each adapter cell, pooled with it, so the row node belongs to a class of the list's own and can reach `selectClamped`/`activate` directly. §7.1 refuses "a synthetic row node above its mounted cell" for a reason that does not cover a real widget wrapper — a synthetic node cannot have widget children and a widget can — so this is genuinely open, at the cost of one widget and one layout level per visible row, a change to `recycle`'s contract, and its own tests for pooling and focus containment. The step took neither and shipped the complete, dispatchable mapping instead. What a reader has meanwhile is in §11 |
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
| `TabbedPane` tab strip | the row has the role and the em-dash under synthetic children right, and is short in three places against the source; each is pinned by `TabStripAccessibilityTest`, and the mapping the step was handed was wrong in a fourth. **Corrected by the step.** *The facets column names only `SelectionFacet`:* the strip is a clipped viewport that `implements Scrollable`, carries a wheel handler and a `revealRect`, and is moved by two chevrons and by every selection's reveal, so it owes a `ScrollFacet` — the same omission this list already records for a segmented control's overflow and for the combo's popup panel. It is published unconditionally, as the scroll pane's is, so that resizing past the fitting point moves two booleans rather than making a facet appear and disappear; and it is derived from the widget's own `overflowing` predicate rather than from the subtraction alone, because that predicate carries the half-point slop below which every scrolling path here declines, and a strip within half a point of fitting must not advertise a movement it refuses to make. The percent is the offset over the maximum offset and is published **unflipped** right to left, the answer the scroll pane's step settled and for the same reason: the offset is a distance from the leading edge, mirroring lives in where the headers are placed, and a flipped percent would say a right-to-left strip resting on its first tab is scrolled to the end. *Silent on orientation,* which the class fixes as `HORIZONTAL` — one run at every width and in both directions, mirroring reflecting the run without turning its axis — exactly as this list records for the slider. *Silent on why the node exists at all,* which is the only hard question the widget raises: the strip declares nothing today, is never focusable and has no synthetic children, so §1.6's predicate deletes it and hoists the headers, and three facts refuse that — a tab list is the container a position in a set is read against, a selection change is addressed to the moving member's *published* parent so a deleted strip raises it on the window node, and the strip's own box under overflow is the one thing that tells a reader where the tabs are. **And the deletion would have been silent**, even though the widget paints information: `Widget#paintsItself()`'s `ClassValue` looks only for a declared `onPaint(Canvas)`, and the sliding selected-tab indicator is drawn from `onPaintOverlay`, so §1.6's paints-and-says-nothing warning never fires here — the accidental escape this record notes for `SplitPane`'s panes, hit by a class that really does draw. *What the mapping got wrong:* it asked for a second hook, `onAccessibilityChild`, marking the selected header `ACTIVE` so that the selection facet would have an active descendant. That is the same bit on the same node the tab header's own step had already refused, and neither reason has moved — only the selected header is a tab stop, so it is already the focused node whenever the strip holds the keyboard, and the publish step takes the first active node *anywhere* in a container's subtree, so a pane nested in a list cell would hand that list one of its tabs as its own cursor. The facet's active descendant stays zero and the step writes one hook. It is not thereby inert: single-selection and always-selected-while-there-are-tabs are the container's own shape, which is what the platforms' selection patterns read, and `required` is `selected >= 0` and not the combo's unconditional `true` — a combo may write that because its constructor refuses an empty item list, and this pane documents the opposite. *And the open bounds item, "the tab strip under overflow", is now asked and needs no correction:* the pane lays the strip out as exactly the viewport between the three overflow controls, so the walk's free box is the viewport by construction, and `scrollStripBy` re-runs `layoutBox` on every header the moment the offset moves rather than painting them at an offset, so none of the menu column's bug is present. Two exact invariants come out of it and the test pins both: the pane's width less the strip's is three times the strip's x left to right, and one and a half times it right to left |
| `TabbedPane` itself | "the pane itself is scaffolding" is the right verdict and the wrong framing: it reads as no code at all, and the class owes two things, both pinned by `TabbedPaneAccessibilityTest`. **Corrected by the step.** *It paints:* a hairline rule under the strip, so §1.6's paints-and-says-nothing warning fires on the pane the moment the predicate deletes it — a toolkit class in an application's log, recommending `setAccessibleIgnored(true)`, which here takes the tab list, the chevrons and every panel out of the tree together. That is the `BackdropPanel` case for the third time after `Dialog`'s scene overlay, and it takes the same seam. *It is the only object that knows which panel belongs to which tab:* a panel is whatever an application handed `addTab` and knows nothing about tabs, and a header's own parent is the strip, so no other hook can reach the pairing. So the pane is deleted and still carries an `onAccessibilityChild`, the `Dialog$ActionRow` shape, which writes onto the child's open slot and says nothing about the pane. *And the row for the selected content is wrong to give the pane both the role and the name outright:* the child hook runs after the child's own and before an application's overrides, so an unconditional `TAB_PANEL` replaces a `ScrollView` panel's `SCROLL_PANE` — deleting the `ScrollPattern` its own step settled — and an unconditional name replaces a `Label` panel's own caption. Both are conditional on the panel having declared neither; the `LABELLED_BY` is unconditional, because it is true whatever the panel turned out to be, and its mirror is the `CONTROLLER_FOR` the tab header's step had to refuse for want of a node to land on. The row is also silent that the role applies to **every** panel and not only the selected one: publishing a panel only while it is selected would raise a structure change and a destroyed node on every tab click, where §1.2's inherited `VISIBLE` already makes a hidden panel announce as not on screen for free |
| `ColorPicker` | the row is `COLOR_CHOOSER` and two dashes; the role is right, the dashes are right for the picker's own node, and it is wrong or silent in five places against the source, each pinned by `ColorPickerAccessibilityTest`. **Corrected by the step.** *It does not say the picker is focusable, and its own verdict depends on that:* `setFocusable(true)` in the constructor is the only reason the node survives §1.6's predicate, since the widget declares no name, no description, no action and no state of its own and overrides no `onPaint` — every pixel is drawn by four inner classes — so without it the chooser would be deleted in silence and its children hoisted onto the window. The role is therefore not a choice the row offers but the difference between a colour chooser and an `UNKNOWN` control naming a toolkit class in an application's log. *It reads as a leaf, and the widget is a twenty-node composite:* three painted parts, a caption and a hex field, a `TAB_LIST` of three `TAB`s, three panels of caption/rail/stepper triples and an alpha triple. The dashes under facets and actions are right for a reason the row does not give — the answer lives in the fields below, which are real widgets with nodes of their own, so a copy of it on the chooser would publish one fact twice. *It hides the only real work, which is that three of its controls cannot be named at all:* the channel steppers, the alpha stepper and the hex field are general-purpose components, their letters exist only in the sibling captions, and no `onAccessibilityChild` can ever reach them, because the walk offers a child to its **direct** parent alone and the picker's only direct child is the root column. So the naming is the constructor's: each caption takes its rail through `setLabelFor` and its stepper through `setAccessibleLabelledBy`, both handing over the held `I18nString` by reference under the subtree's language, both published `NameFrom.LABEL` with the relation back, and both leaving `setAccessibleName` free for an application; the hex field carries a word of its own instead, because the "#" beside it is a mark a reader speaks as "number sign". One consequence the row could not have foreseen: a named control survives the predicate whether or not it is focusable, so an unselected notation's lines now stay in the tree without `VISIBLE` and a tab click moves which of them are `SHOWING` and destroys nothing, where nameless rails were deleted and rebuilt on every click. *It does not say where the picker's name comes from, and the answer is nowhere:* no title, no tooltip, no placeholder, and the one place in the toolkit that builds a picker named the dialog around it and never the picker, which is where focus actually lands — **corrected 2026-09-07 by the gallery test's first run:** `ColorPickerButton` now names the picker after the dialog's title, by reference, so a language move re-resolves both. *And the neighbouring `ColorPickerButton` row's instinct, "value text is the hex", must not be carried across:* `Accessibility#valueText` writes the text and leaves `hasValue` false, the facet is built only where a number is present and `declaresNothing` does not count it, so such a text is dropped without a word; and the difference raises a value change only when a number moves, so even surviving it would republish the tree on every drag step and emit nothing. A colour has no honest scalar to pair it with. Two more owed here: the `ColorPicker.HueRamp` correction above is one of **three** — `SaturationValueField` and `Preview` extend the same `Painted`, are likewise never focusable, likewise override `onPaint` and likewise draw information rather than decoration, so all three are deleted by the predicate today and all three trip the paints-and-says-nothing warning, each pending its own step; and `ColorPicker#onKeyEvent` carries no `isEnabled()` guard, unlike `Rail#onKeyEvent`, and is safe only because `Scene#requestFocus` refuses a disabled widget — nothing here depends on that, since the picker declares no verb, but it is the trap waiting for whoever gives the saturation/value plane one |
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
  publishes as `SELECTED` on the row and an active descendant on its column (§7), diffed like
  everything else. The hole ADR 040 names is a hole in *its* channel and not in the tree.
- **§6.2, an assistive technology cannot set a value as the user.** Answered by §1.9: the action set
  is `ActionFacet`'s twelve parameterless verbs plus the parameterised constants `SET_VALUE`,
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
  *Cost:* arrow-key navigation works, because moving the selection scrolls and realizes; jumping to an
  arbitrary row through the reader's own list navigation does not. Publishing every index instead would
  hand the reader thousands of anonymous items with no name and no bounds, and rebuild them all on every
  dirty frame; that is worse for the user, not better.
- **Not per-row actuation in a list.** A `ListView` row is a node with a name, a position in the set
  and a selected bit, and it carries **no verb**: a reader cannot select or open a *particular* row.
  The reason is the model's rather than the widget's — a row is a widget child, so the scene dispatches
  an action on it to the application's own cell widget, which refuses it (§7.2). *Cost:* three routes
  remain and they cover the ordinary use. The list is focusable and every platform delivers real key
  events to the focused control, so Up, Down, Home, End, Page and Enter all work — which is what the
  bullet above already rests on; the list node's own `PRESS` opens whichever row is selected; and the
  scroll bar's `INCREMENT` and `DECREMENT` page the viewport. What is genuinely lost is the reader's
  own "click that item" on a row it has navigated to with the reader's cursor rather than with the
  application's selection. §7.2 records the two ways to buy it back and what each costs.
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
| `AccessibleGalleryTest` | **written 2026-09-07 evening**, in `limn-demo`, and not over the demo's own gallery: it runs over `limn.demo.a11y.AccessibilityGallery`, a gallery of components built for this purpose, each entry declaring the toolkit classes it covers and the roles it promises, labelled the way a reader needs (`Label.setLabelFor`, described pictures, titled dialogs), with a `main` so a reader can be pointed at it. Every entry, in both palettes, over every window it opens (a dialog in its own window, a popup): no node has role `UNKNOWN`; every `FOCUSABLE` node has a non-blank name; no two nodes share an id; and the clipping invariant as the model can state it — the published node carries no clip mark (`Widget#clipsChildren()` is consulted by the walk for `SHOWING` and never published) and "wholly inside" is false by design for a half-scrolled row (§7.2), so a `SHOWING` node must have a non-empty box overlapping the scene's and every `SHOWING` ancestor's. Every promised role must appear, which is what caught a headless window with no display and so no popup. Completeness both ways, by scanning the toolkit's sources for `onAccessibility` overrides: a hook-bearing class no entry covers fails, and so does an entry claiming a class with no hook. Its first run found the picker a colour well raises focusable and nameless — §7.2's own prediction — and the well now names it after its dialog's title. What it cannot catch: the demo's own usage, which the transcripts cover; and a synthetic child the model cannot publish disabled (a segment strip's dead chevron is a `BUTTON` with no verb) |
| `AccessibleTranscriptTest` (`limn-demo`) | three demo scenes — `forms`, `components`, `kitchen-dialog` — built as `--scene` builds them, bound to a headless window and backend, settled over the gallery's warm-up frames under the fixed ruler and the English locale, and their published trees written as transcripts: one line per node in tree order, role, name with provenance, description, spoken states, facets, verbs and relations by line, **no bounds**. Compared against goldens somebody has read aloud, rewritten only under `-Dlimn.a11y.transcripts.update=true` and failing otherwise with the differing lines. The kitchen scene is two trees, host and modal window |
| `AccessibleFocusOrderTest` | the invariant that keeps the tree honest: the published nodes carrying `FOCUSABLE`, in tree order, equal the sequence produced by repeated `focusTraverse` from nothing. Stated on that bit and not on `ENABLED`, which is a strictly larger set — every `Label`, `ScrollBar` and `Separator` is enabled and is not a tab stop (§1.13). Roving focus passes because only the holder is focusable, which is the same fact the tree reports |
| `AccessibleMirroringTest` | in RTL, tree order is unchanged and bounds decrease in x. The tree is not sorted by geometry |
| `AccessibleLocaleTest` | a subtree with a declared locale publishes its name in that language while the process locale is another, and a locale move re-resolves every name exactly once |
| `AccessibleScaleTest` | under a content-scale override and a non-zero window origin, a known widget box converts to a known screen rectangle — which is what the doubles' new screen fields (§8) exist for |
| `AccessibleEventTest` | two snapshots in, an expected event list out: one focus event per publish, one bounds event per scrolled frame, property changes collapsed per property, a text change carrying the right insert offset over astral-plane text, an announcement never dropped. **This test cannot fail for the defect that will actually happen**, because it constructs both snapshots itself — the next test is the one that guards the mechanism |
| `AccessibleLiveMutationTest` | the regression gate for §5.3, and the test whose absence let the first draft of this ADR ship a design in which a checkbox toggle raised nothing. For each state-bearing component — `Checkbox`, `Slider`, `Spinner`, `ProgressBar`, `TextField`, `TextArea`, `SearchField`, `ComboBox`, `ListView`, `TabbedPane`, `SegmentedControl`, `ScrollView` — it drives the **public setter** on a bound scene with a listening bridge, renders one frame, and asserts the expected event arrives with the expected node and value. Nothing in it constructs a snapshot. A funnel that stops setting the dirty flag fails it |
| `AccessibleQuietFrameTest` | the other half: a frame that damages a widget without changing any accessible fact — a caret blink, a hover ripple — publishes nothing and emits nothing; and a re-present frame does not even walk |
| `AccessibleModalTest` | with an overlay pushed, every node outside `inputRoot()` publishes without `ENABLED` and without `FOCUSABLE` while staying `VISIBLE` and `SHOWING`, the overlay's own node carries `MODAL`, and the set of nodes published `FOCUSABLE` equals the set `focusTraverse` can reach (§1.13) |
| `AccessibleMenuTest` | an open cascade publishes one `MENU` per open column with the column's rectangle and not the surface's, one row node per item with its own rectangle, the highlighted row as the column's active descendant, and an `ACTIVE_DESCENDANT_CHANGED` for each arrow key |
| `AccessibleRecyclingTest` | scrolling a `ListView` past its pool size and back leaves row 3's identifier on row 3, and never on the cell that visited row 9 |
| `AccessibleIdentityTest` | **the §1.3 regression gate.** Giving a transparent ancestor a name mid-run changes the tree's shape and **no identifier below it**; the events are structural, not a wave of `NODE_DESTROYED`. The same for making a scaffold widget focusable — which also fails outright until `setFocusable` invalidates (§8) — and for pushing an overlay above a subtree. A key derived from the published parent fails every case |
| `AccessibleInheritedStateTest` | disabling a container publishes every descendant without `ENABLED` and without `FOCUSABLE`, hiding one publishes every descendant without `VISIBLE`, and in both cases the `FOCUSABLE` set still equals what `focusTraverse` can reach — never the enabled set, which the scene under test makes larger on purpose by holding a `Label` and a `Separator`. And the transparency verdict does not move: a disabled form grows no `GROUP` nodes (§1.6) |
| `AccessibleFirstFrameTest` | a scene bound to a window publishes nothing at `bind` — where it has never laid out — and publishes a tree with real boxes on its **first frame**, with `republishNow()` called between the two returning the empty tree rather than a tree of zero-size rectangles (§5.2). And the priming publish is paid only by a bridge that asked for it: a double answering `needsPrimingPublish() == false` and never listening receives nothing, ever |
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
| Windows 11 ARM64 guest | `scripts/a11y/windows/client.ps1`, the spike's PowerShell `UIAutomationClient` walk, run into session 1, plus NVDA 2025.1 portable | find by name, control type, bounding rectangle, `InvokePattern.Invoke`, `SetFocus`, `ElementFromPoint`, and a focus event observed by NVDA |
| macOS guest | `scripts/a11y/macos/`: `guest-build.sh` and `guest-probe.sh` bring the rendered probe up in the console session, `guest-steps.sh` drives a sequence of walks and mutations against one live provider, and `guest-voiceover.sh` photographs VoiceOver's caption panel on a timer and stacks the distinct phrases into one strip. The clients are `axtree` (walk, hit-test, follow relations), `axlife` (destroy an element under a client that holds it) and `dump-appkit-constants.swift` (§12.3). `vocap` exists because `screencapture` raises a consent dialog on every invocation and a dialog takes the foreground, which is what a reader announces — **the measurement destroying what it measures, which is the shape to watch for on every one of these guests** | the four scripts already pass against the spike's one-element provider; against the real bridge they must pass against a *tree*: find by name through `AXTitle` **or** `AXDescription`, `AXPress` arriving back in Java on the main thread, hit test through several nested levels, notifications to a real `AXObserver` (with focus observed only at application level), and the loop-mode sweep. Three assertions are new and are the ones this round's fixes created: **an overlay opening while a client is attached is visible to it**, which is the re-push of §2.2; a destroyed element is released and a stale message to it fails rather than crashing; and a scene rebound over the same window leaves no element alive. `unprivileged.sh` runs every one of them as an ordinary user, because root is accessibility-trusted and the lab's `sudo` would otherwise be doing the work. What only VoiceOver can settle is the attributes a purpose-built client never asks for |
| Ubuntu GNOME, X11 and Wayland | `scripts/a11y/linux/walk-the-probe.py` through `libatspi`'s own typelib — so a tree it walks is a tree Orca sees — and a talking Orca read through `--debug-file`. `-Dlimn.a11y.linux.trace=true` logs every inbound call, which is what turned "the desktop will not list us" from a silence into a question | tree walk, find by name, `DoAction`, `Cache.GetItems` in one round trip, extents in both coordinate types, one application object with one child per window |
| Fedora KDE, X11 and Wayland | the same | **run 2026-09-06, and it did not behave the same.** Its at-spi2-core 2.60 reads an application as it registers and refuses to list one that answers "no children", which found a registration-order defect this bridge had had since it was written (§13.15). With that fixed, a client walks the tree and Orca speaks names, roles and states. What is still open is the rendered probe on a **Wayland surface**, below |

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
   `'79'`, one per tick. Two things the runs found beside the gate: a publish inside the ask, sync or
   requested, silences NVDA for good (§3.1, corrected); and every run made with the VM window behind
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
