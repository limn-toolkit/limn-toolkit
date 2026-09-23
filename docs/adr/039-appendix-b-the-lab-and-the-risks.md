# ADR 039, appendix B: the lab, and the risks and open edges

*§12.2 and §13 of [ADR 039](039-an-accessible-tree-is-a-snapshot-and-the-platform-reads-it-on-its-own-thread.md), moved here unchanged on 2026-09-23 so that the record
itself can be read in one sitting. The section numbers are the ones the rest of the repository
cites.*

*A path under `readings/` names a raw transcript from the verification lab. Those files are kept
outside this repository; each finding cited to one is stated here in full.*

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
signal refused. What it does not replace is a talking Orca, which is phase 5's.)* *(Heard
2026-09-16: Orca 50.2 on Fedora 44 spoke all six reader scripts, with no Orca traceback in eight
runs against six at the 2026-09-14 baseline, and Orca 46.1 on Ubuntu 24.04 read five runs the same
day without one; closed.)*

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
phase 5's.)* *(Heard 2026-09-16: Orca 50.2 on Fedora 44 spoke the calendar, date-field and
date-picker scripts end to end, the picker's day included ('9 de setembro de 2026, hoje.'). Orca
46.1 on Ubuntu 24.04 spoke the calendar the same day. Closed.)*

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
reader was deliberately left unstarted.)* *(Not heard on this guest, 2026-09-16: phase 5's Ubuntu
run repeated the two-frame client walk and drove Orca 46.1 over the calendar, the table and the
announcement scenes, not over a picker or a combo box. Orca 50.2 on Fedora 44 followed the cursor
into the picker's popup and spoke the day the same day. The Orca 46.1 half is still open.)*

**One of these can plausibly move into CI, and it is worth trying.** The Linux bridge is pure Java and
pure D-Bus, and the Ubuntu runner can install `at-spi2-core` and run the whole probe under
`dbus-run-session`. If that works, one of the three platforms gains a real gate. It is listed as work,
not assumed.


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
    nothing — a model edge, left open here. *(Closed 2026-09-14: `Accessibility#disabled()` lets a
    synthetic child narrow `ENABLED`, per the amendment of that date in §1.2.)*
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
