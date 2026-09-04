# ADR 040. A handler answers the user, and a watcher hears everything

- **Status:** Proposed, 2026-09-03. Nothing here is implemented. §0 is what a three-part sweep of
  every registration, every fan-out and every prospective consumer in the repository established,
  together with a benchmark of the eight shapes a fan-out could take, before any of it was decided.
  §1 is the decision; §3 breaks published API.
- **What in this record is exact, and what is a survey.** **Exact**: the rule (§1.2), the origin
  definition and its three clauses (§1.3), the API (§1.4), the ordering and the containment (§1.6),
  the thread, allocation and lifetime rules (§1.7), the four obligations on a component author
  (§1.11), and the guarantee and non-guarantee lists of §2. Each of those is wrong for every widget
  at once if it is wrong at all, each is stated once rather than per component, and §3.5 is what
  checks it. **Survey**: §0.12's list of silent state, §1.5's seam table, §3.1's component-by-
  component reading and §3.6's counts. Those four are one kind of thing — a reading of the tree —
  and they are held to one standard rather than two: they size the work and locate the seams, they
  have carried errors of shape that only opening a file found (§3.6 tabulates the known ones), and
  each row is settled in the implementation against the component's own code. **A seam table is not
  less binding than the rule; it is less certain of the source.** The rule says what every seam must
  do, and the table says which seams a reading of the tree has found so far.
- **Date:** 2026-09-03
- **Compatibility.** Nine releases, `v0.1.0` through `v0.7.0`, are on Maven Central under
  `io.github.limn-toolkit`, and `README.md` carries the badge and the one-dependency install
  snippet, so a stranger can already compile against `Slider.onChange`, `Fonts.addChangeListener`
  and every other type this record changes. What is true is narrower than "nothing depends on this"
  and is still the whole justification: **the owner knows of no consumer, and a `0.x` line promises
  no compatibility.** On that ground this record changes and removes rather than deprecating, and
  §3.7 says what it would owe instead once the line is `1.0`.
- **Scope:** who may be told that a widget changed; whether a change made by code is announced at
  all, and to whom; what a notification carries; in what order and on which thread it arrives; what
  happens when a listener throws; what it costs when nobody is listening; and how a listener stops
  listening. The accessibility tree, the platform bridges, roles, actions and the accessor gap are
  not here — they are ADR 039's subject. That record builds its tree by diffing snapshots and does
  not subscribe to this channel (039 §9.3); what this record owes it is the convergence its §9.1
  asked for — one from-user funnel per widget, one rule about what a programmatic write does, and a
  registration that admits more than one subscriber.
- **Companion, and it lands in this commit.** ADR 039, *An accessible tree is a snapshot, and the
  platform reads it on its own thread*, is in `docs/adr/` as Proposed, 2026-09-04, with its own row
  in the index. It is named throughout below and is the addressee of five open items — §6.1 to §6.4
  and §6.16 — and its §9 answers four of them: **§6.2** by an action set whose every verb is
  performed by the widget itself and therefore enters that widget's own from-user funnel, which is
  `USER` in this record's vocabulary; **§6.3** by the four `text()`/`textSource()` accessors that
  gate a `NAME` announcement; **§6.4** by a bridge each window hands to its own scene, so no scene
  registry is needed, and by an `attach` that raises a window-closed and a window-opened event when
  a scene is replaced on a live window; and **§6.1**'s four non-`Widget` types by publishing them as
  synthetic children declared by the widget that owns the surface. The fifth, **§6.16**, it answers
  the other way: its snapshot compares a name by source, locale *and* epoch, so a subtree locale move
  re-resolves every name under it with no subscription at all — which confirms the sentence §6.16
  already carries, that the gap costs that bridge nothing, and leaves the gap itself open here for
  the consumers who do pay for it. Each of the four is answered in
  design and none of them is implemented, so this record's open items stay open and name where their
  answer lives. **Neither record waits on the other**, and nothing in §1 is conditional on
  039: the channel is complete and useful without a bridge — it is what a two-way binding, an
  inspector, a test and the toolkit's own `MediaControls` use.
- **Where the two records describe the same bridge differently, ADR 039 governs.** §1.10 below sets
  out how a screen-reader bridge could use this channel: one registration per scene, patching the
  one node a change names. ADR 039 §9.3 says that is not the bridge it builds — its snapshot is
  rebuilt by a walk inside the frame and its events are the difference between two of them, because
  this channel carries no per-widget geometry by design, because the whole tree has to be assembled
  anyway for a reader on a thread that may not touch a widget, and because its transparency rule
  decides a node's *existence* from facts about a subtree, which no single named change can settle.
  **So §1.10 is what this channel makes possible, and ADR 039 is what a bridge is built from.** What
  that costs is one thing and that record states it: a press performed with the mouse leaves no
  difference between two snapshots, so `INVOKED` is the one aspect a diffing bridge cannot see, and
  the subscription is there if it ever wants it.
- **Audience:** whoever implements the toolkit half; whoever next writes a component and owes it the
  four obligations of §1.11; and whoever reads `Checkbox.setChecked` beside `ComboBox.setSelectedIndex`
  and wants to know which of the two was lying.

**Verdict in one sentence.** The fluent `onX` slot stays and finally means something — *the
application's one response to the user operating this widget* — a second channel is added on which
any number of parties watch **every** change to a widget whatever moved it, and the origin travels
on the notification rather than being decided at registration.

---

## 0. What was measured before anything was decided

### 0.1 There are six notification patterns, and only one admits a second subscriber

The registration sweep found, in one repository: **twenty-seven fluent single slots** on the
components (`onAction`, `onChange`, `onSelect`, `onCommit`, `onActivate`, `onSubmit`,
`onRatioChange`, `onPointClick`, `onPointHover`, `onClose`, `onRootLeading`, `onRootTrailing`,
`onDispose`, `onClick`, `setOnRefresh`), each of them a method that assigns one field over a value
initialised to a no-op lambda, and **every one of the twenty-seven silently displaces whatever was
registered before it** — which is the defect, and which §0.5 leans on for `Viewport3D.onDispose`.
Twenty-seven is the number §0.6's null-policy split closes on independently: twenty-two reject null,
two substitute a no-op, three store it unchecked. Four of the twenty-seven sit on types that are not
`Widget`s (§0.14) and two report a lifecycle rather than a change, which is what §1.1 separates them
by. **`onToggle` looks like a twenty-eighth and is not a registrar at all**: it is a `MenuItem`
field set once by the static `check(label, checked, onToggle)` factory (`MenuItem.java:62-70`) over
the same no-op lambda, with no method anywhere to call a second time, so it is the one listener in
the components that *cannot* be displaced, and it belongs with the `Menu`/`MenuItem` factories §0.6
treats separately. Then: **four** list-with-a-handle observers on `Scene` (`observePresses`,
`observeWindowBlur`, `observeWindowClosed`, `addShortcutHandler`); **four** process-wide
copy-on-write listener lists on the `Fonts`, `ControlSize`, `LayoutDirection` and `I18n` axes;
**eleven** static install/uninstall slots holding *strategies*; `Work`'s **five** builder slots,
snapshotted at `start()`; and `Dialog`'s `CompletableFuture<String>` result. Of these, the last is
the only notification in the repository that more than one party can subscribe to, and it can be
subscribed to once per dialog.

The strategy slots are not notifications and are correct as they are: one crash handler, one text
ruler, one image decoder is the right number, and an identity-checked uninstall is the right way to
give it back. `Work`'s slots describe a job that is started once. Everything else in that list is a
notification, and everything else in that list is either a single slot or a fan-out written a
different way from its neighbour.

### 0.2 The single slot buys nothing, measured

Eight fan-out shapes were benchmarked on Zulu OpenJDK 21.0.12 on aarch64 with `-XX:+UseSerialGC`,
four distinct listener classes so the call site is megamorphic, allocation read from the exact
per-thread counter the repository's own `AllocationProbe` uses (least of 6 × 100 000, the same
"least, not mean" rule and for the same reason), and reported with escape analysis **off**, because
that is the number to design against — the EA-on column moved between identical runs.

| shape | n=0 | n=1 | n=3 | n=8 |
|---|---|---|---|---|
| single slot, `field.accept(v)` | — | 1.86 ns / 0 B | — | — |
| plain array swapped on registration | 1.47 ns / 0 B | **1.91 ns / 0 B** | **5.84 ns / 0 B** | **14.21 ns / 0 B** |
| indexed `for` over an `ArrayList` | 1.07 ns / 0 B | 1.86 ns / 0 B | 6.96 ns / 0 B | 16.54 ns / 0 B |
| `removeIf` over a cached predicate | 1.56 ns / 0 B | 2.38 ns / 0 B | 7.30 ns / 0 B | 15.96 ns / 0 B |
| reused snapshot array | 1.45 ns / 0 B | 2.91 ns / 0 B | 9.05 ns / 0 B | 21.20 ns / 0 B |
| `CopyOnWriteArrayList` iteration | 4.78 ns / 24 B | 5.05 ns / 24 B | 7.23 ns / 24 B | 17.06 ns / 24 B |
| `for (x : List.copyOf(list))` | 2.72 ns / 32 B | 11.71 ns / 80 B | 16.20 ns / 120 B | 50.41 ns / 152 B |
| the same, behind an `isEmpty` guard | 1.69 ns / 0 B | 11.66 ns / 80 B | 15.71 ns / 120 B | 52.46 ns / 152 B |

**A one-listener fan-out costs the same as a single slot: 0.05 ns and no bytes.** There is no
performance argument for a single slot at any rate this toolkit notifies at, and the decision to
keep one (§1.1) is therefore made on meaning and not on cost. Two shapes the repository already uses
are eliminated by the same table: `List.copyOf` per notification is the only shape that allocates on
the notify path and the only one that gets three times slower as the list grows, because it copies
twice and then allocates an iterator; copy-on-write iteration allocates its iterator at every size
including zero. A plain array swapped on registration is fastest at every size, allocates nothing
with escape analysis on or off, and reproduces exactly the snapshot reentrancy semantics the press
observer already depends on. It is copy-on-write without the concurrency machinery a
UI-thread-confined mechanism does not need.

Footprint and registration, same JVM: a null field is 4 bytes, a one-element `Runnable[]` is 24, an
`ArrayList` that has had one listener added is 80 because of its default capacity-ten array, an empty
`CopyOnWriteArrayList` is 56, one lambda capturing one field is 32. An `ArrayList` add-and-remove at
three entries is 2.95 ns and no allocation; a copy-on-write add-and-remove at two hundred is 34.6 ns
and 1 640 bytes, and 73.6 ns if the add is an `addIfAbsent` miss. `ListView` mounts and unmounts
cells from inside `onLayout` on every frame while scrolling, so a per-widget subscription living on a
process-wide copy-on-write list would pay that on every frame of every scroll. **Those two numbers
decide where a subscription may live long before taste does**, and they are why §1.7 puts the bridge
on one registration per scene and a null field on every widget.

The weak-wrapper purge was measured too, so it can be dismissed with a number instead of an
intuition: `CopyOnWriteArrayList.remove` copies the array, so the self-purge is quadratic — at 4 000
dead entries, 4 000 array copies, 8 002 000 reference moves and about a millisecond. One entry per
`Scene` ever constructed, purged on the next change. Not a problem at any scale this toolkit reaches.

### 0.3 The repository holds two opposite conventions, and neither is written down as a rule

Every value-bearing widget suppresses notification when code writes the value — `Checkbox.setChecked`,
`Slider.setValue`, `Spinner.setValue`, `TextField.setText`, `TextArea.setText`, `ColorPicker.setColor`,
`ColorPickerButton.setColor`, `SplitPane.setRatio`, `MenuItem.setChecked` — and every
selection-bearing widget announces it: `ComboBox`, `TabbedPane`, `SegmentedControl` and `ButtonGroup`
all fire from `setSelectedIndex`, `ListView` fires from `setSelectedIndex`, `clearSelection` and
`refresh`, and `RadioButton.select` fires whoever called it.

The sharper reading is that the line is not value versus selection. **The widgets that kept a plain
setter suppressed; the widgets that only ever exposed a verb — `select()`, `toggle()`, `activate()`,
`clear()` — did not.** `RadioButton` has no silent setter and always fires; `Checkbox` has both a
setter and a verb and fires only from the verb. Nobody chose that. It is where two spellings
happened to land.

**`SearchField.clear()` is the sharpest case, and it does not sit beside a silent setter — it calls
one.** Read out of the file: it is
`if (!text().isEmpty()) { setText(""); fireChange(); }` (`SearchField.java:36-41`). It does not sit
one line away from the inherited `setText` — **it calls it**, and then calls `TextField`'s
`protected fireChange()` to announce what the setter would not. So the verb is a caller of the
silent setter plus a hand-written announcement, which is the whole inconsistency compressed into
four lines, and it is why §1.5's seam for it cannot be *"add an origin to `clear`"* and leave the
body alone: a `clear(USER)` that still routed through the announcing `setText` of §3.1 would
announce twice, once `CODE` from the setter and once `USER` from the verb. It also means
`fireChange()` — a `protected` method whose javadoc invites subclasses to call it — has a caller
inside the toolkit, which §3.1's *"`fireChange` is deleted on both"* has to answer for. Two further
facts fall out of the same read: `clear()` calls no `Ui.checkUiThread()`, unlike every registrar
around it, and it already carries §1.2's idempotence guard in the shape of its `isEmpty` test.

Four of the five selection widgets carry the same warning verbatim — that the unchanged-value early
return is what keeps two controls bound to each other from recursing, so do not remove it. That
sentence is the entire argument for their half of the convention; it is stated four times and
abstracted nowhere. `SelectionContractTest` is the one place the rule is executable: it drives all
five through a common `Choice` interface so *"a sixth widget cannot quietly answer `setSelectedIndex`
its own way"*, and its javadoc names the idempotence guard as the load-bearing clause and the reason
the second case is safe, since one UI thread is no protection at all against *nested* reentry.
Whatever this record decides, that test either grows to cover the value widgets or it goes; it cannot
stand while half the set does the opposite.

### 0.4 The inconsistency has already been paid for, four times, in this repository

`ThemeEditor` carries a `syncing` flag threaded through eleven sites. `syncFromBuilder` brackets four
setters — a text field's `setText`, a checkbox's `setChecked`, a colour well's `setColor` and a combo
box's `setSelectedIndex` — of which **exactly one can fire**. The other three are documented as
silent and were guarded anyway. `syncCornerControl` and `setApplyLive` then guard a slider's
`setValue` and a checkbox's `setChecked`, both incapable of firing. Eleven guarded sites, one of them
load-bearing, is the cost of an unstated rule, measured.

`ColorPicker` carries two reentrancy flags for one problem: `switchingFormat` guards a tabbed pane
that notifies, and `syncing` guards a text field, a spinner and an alpha field that do not — dead
code, kept because the convention cannot be relied on. Alongside them sits a comment asserting that,
unlike a segmented control, a tabbed pane reports a programmatic selection too. **That comment is
false**: `SegmentedControl.setSelectedIndex` fires `onSelect` as well. The author had to learn the
rule empirically, per widget, and mis-remembered it.

In the demo, three sliders each need their starting value written twice — once as `setValue(30)` and
once as `new Label("30")` — because `setValue` announces nothing and the label has no other way to
learn where the slider starts. A segmented control four lines from a radio group does it the opposite
way for the opposite reason.

And in the test tree the displacement has already happened: `SliderTest`'s fixture registers
`slider.onChange(changed::set)` at line 51, and one test method registers `slider.onChange(changes::add)`
at line 114, silently destroying it. Nothing fails only because that method never reads the fixture's
reference.

### 0.5 The single slot is being consumed by the toolkit itself, ten times

`ColorPicker` takes the hex field's `onChange`, the format tabs' `onSelect`, the alpha field's
`onChange` and each channel spinner's `onChange`; `MediaControls` takes its bar's `onChange` and
`onCommit` and its volume slider's `onChange`; `ColorPickerButton` takes the picker's `onChange`;
`Dialog` takes each button's `onAction`; `MenuBar` takes the popup menu's `onClose`. Every one of
those children is constructed by its owner and never handed out, so nothing is displaced today — but
the invariant that keeps it true is *no toolkit class ever exposes a child whose slot it took*, and
nothing states it and nothing checks it.

The sweep read this as a live collision on `MenuBar`. The source says it is not one: `MenuBar`
builds its own `PopupMenu` (`MenuBar.java:554`) and never returns it, so the application could not
reach that slot in any case. What is true is narrower and still worth fixing — there is no way at
all to learn that a menu-bar menu closed, because the popup is unreachable, and `PopupMenu.onClose`
fires for a programmatic `close()` as well as a dismissal, which is neither documented nor an
accident of a shared path.

One slot is worse than a missed announcement. `Viewport3D.onDispose` holds GPU cleanup — typically a
retained scene's `Scene3D::dispose`, run on detach with the GL context current — and the demo
registers it thirteen times, eight in one file. A second registration on one viewport silently drops
a GPU free, with no diagnostic and no test that could see it.

### 0.6 Nothing about registration is uniform on any axis

`Ui.checkUiThread()` is called by every component `onX` except `MediaControls.setOnRefresh`, and by
none of `PopupMenu`'s three registrars, none of the `Menu`/`MenuItem` factories, none of the eight
process-axis `add`/`removeChangeListener` methods, none of the three `Scene.observe*` methods, not
`Scene.setFrontPainter`, and none of `Work`'s five. **Twenty-five registration methods mutate
UI-confined state with no thread assertion.**

Null policy splits three ways: twenty-two registrars throw, two substitute a no-op
(`SplitPane.onRatioChange`, `ColorPickerButton.onChange`), three store whatever they are given and
null-check at every firing site (`Viewport3D.onDispose`, `Viewport3D.onClick`,
`MediaControls.setOnRefresh`), and `Work`'s five treat null as a documented distinct behaviour.
`ColorPickerTest` asserts there is one answer and names it the loud one; there are three. The reason
the no-op substitution existed at all is that it was the only way to unregister — **a channel that
returns a handle removes the question instead of answering it a third time.**

Callback types disagree with each other and with the allocation discipline: `ListView` uses
`IntConsumer` for a selected index while `ComboBox`, `TabbedPane`, `SegmentedControl` and
`ButtonGroup` use `Consumer<Integer>` for the same job; `Slider` boxes a float on every drag frame,
`SplitPane` boxes a float, `Spinner` boxes a double. Two fields contradict their own setters:
`TabbedPane.onSelect` assigns a field named `onChange`, `Chart.onPointHover` assigns one named
`onHover`.

Exception policy splits five ways across ten mechanisms — abort the rest; contain and continue;
contain and evict the thrower; contain and honour the crash handler's shutdown verdict; and a hook
per crashed task. **Exactly one per-listener `try` exists in the repository**, in `Scene`'s
window-close loop, and it states its rule: *the remaining observers still run*. The four process-wide
axes contain nothing at all, so one throwing listener aborts every other scene's relayout and escapes
into the application call that changed the setting.

### 0.7 The reentrancy rule is implemented four times and written down twice

*A registration made during a notification does not receive that notification.* It is what stops a
menu item whose action opens a second menu from having the second menu dismissed by the press that
opened it. Its partner — *an unregistration made during a notification still runs for that
dispatch* — is what lets a combo box and a popup menu both close on one press. The press observers
get both free from `List.copyOf`; the axes get both free from copy-on-write; `Scene.addTicker`
implements the first deliberately with `pendingTickers` and a comment; `UiRuntime.drain` implements
it with a counted snapshot and a comment.

`Scene.windowClosed` gets it wrong: it walks `List.copyOf(windowCloseObservers)` and then calls
`clear()`, so an observer registered during the walk is dropped unread. That is reachable through
`Dialog` — a close observer completes a future, a synchronous `thenAccept` on that future resolves a
second in-scene dialog, and the second dialog's freshly registered unhook is cleared before it ever
runs while its real-time ticker never runs either, because a closed window renders no frames. The
result is exactly the abandoned completion `observeWindowClosed` exists to prevent.

### 0.8 Nothing has replay, and the one place that needed it wrote the catch-up by hand

`ThemeEditor.onAttached` does not only subscribe to `Fonts`; it also compares and catches up, because
a catalog change that landed while the editor was detached reached nobody. **Subscription is not
synchronisation.** A design that adds subscriptions without saying what a late subscriber is owed
reproduces that bug once per subscriber, and a bridge subscribing on attach is exactly a late
subscriber.

ADR 002 §5.4 records the companion trap. The metrics listener had to move out of `bind()` and into
the `Scene` constructor, because an unbound scene — every component test, a combo box's popup scene,
a dialog's modal scene before it binds — was unreachable from any global change, which made the
`setProcessDefault(MEDIUM)` reset in `@BeforeEach` *"a silent no-op on every headless scene"*. Any
subscription this record adds inherits that: it must work on an unbound scene.

### 0.9 Polling cannot be the mechanism, and two required changes produce no frame

`Scene.needsAnotherAnimationFrame()` returns true whenever `tickers` is non-empty and scene time is
not frozen, and `tickAnimations` then calls `scheduleFrame()`. A watcher registered as a ticker would
therefore pin every window it watches at full frame rate for the whole session an assistive
technology is attached; the method's own comment names what that spends — *idling at zero when
nothing moves is the property the whole event-driven loop is built on* — and the backend confirms it
by sleeping in `glfwWaitEvents`.

The cheap variant, a hook at the end of a frame that was going to happen anyway, fails on a different
count: `Widget.setTooltip` and `Widget.setFocusable` assign a field and return, producing no frame at
all, and `setFocusable` is the roving-focus mechanism `ButtonGroup.applyRovingFocus` and
`TabbedPane.selectTab` both drive — exactly UI Automation's `IsKeyboardFocusable`.

Four further categories cannot be recovered from any snapshot however often it is sampled:
**stateless events**, which have no field to diff; **text deltas**, where a per-frame diff of
`"abcabc"` into `"abc"` gives no defensible offset and `TextEditModel` carries a 4 MiB undo budget;
**ordering inside one call**, where `ButtonGroup.select` performs a member change, a second member
change, a roving-focus reassignment, a focus request and a group notification and a diff sees only
the end state; and **transient state** that does not survive the frame, such as `Button.isArmed`.

### 0.10 The platform layer decides who may read what, and from where

Of the 2 191 provider calls counted in the Windows spike, **2 031 arrived on arbitrary RPC threads**
attached under us, up to three live at once, with no lock taken on our behalf. Every getter in this
toolkit is unsynchronized and every mutator asserts the UI thread across 244 call sites. A provider
thread must therefore never read a widget; a bridge has to publish an immutable snapshot written on
the UI thread and read from anywhere, and `Ui` has no synchronous run-and-wait primitive with which a
provider thread could ask instead. That fixes the payload question before it is asked: **a
notification's job is to say what changed on one node, not to send anyone back to the widget from a
thread that may not read it.** And because all three platforms cache — Orca tracks focus purely from
`StateChanged:focused` and never polls — a missed notification is not a late announcement but a
permanently wrong cache.

### 0.11 The prior art agrees on which column predicts a good outcome

**This is the one part of §0 that is not a measurement.** It is four toolkits read out of their
public documentation and, for the last column, out of what their accessibility layers are made of;
nothing here was benchmarked, instrumented or run. It is read as corroboration for a decision §0.1
to §0.10 already force — those ten subsections are what decide two channels, and this table is why
the second one carries accessibility rather than a third — and no cost, shape or guarantee below
rests on it. Where it is load-bearing at all is the fourth column, and the claim there is a historical
reading rather than a measurement: it is offered as an argument, and someone who reads those toolkits
differently should argue with the argument and not with a number.

| | several listeners? | a programmatic change notifies? | how the echo is stopped | accessibility on the same channel? |
|---|---|---|---|---|
| JavaFX | properties yes, `setOnX` no | yes, always | an application guard flag, or bindings | no |
| Swing | yes | yes for change and item events, no for action events | `setValueIsAdjusting`, guard flags | **no — a parallel accessible context** |
| AppKit | action no, key-value observing yes | action no, observing yes | two channels, by design | **no — a separate post call** |
| GTK | yes | yes | the toolkit blocks the handler | **yes** |

The last column is the one that predicts whether a toolkit's accessibility is any good, and the three
that answer no are the three where each component must remember to fire a second thing. AppKit is
also the closest match to the shape this repository already has: one target and one action, with
observation as a genuinely different mechanism, and a programmatic write that moves the observed
property without sending the action.

### 0.12 The state that changes with no notification at all, as far as a sweep can see

Twenty-four kinds, none of which any listener anywhere can hear. **This is an enumeration of the
kind §3.6 demotes, and it is held to that standard rather than to a better one**: it was assembled
by reading mutators and registrars, it cannot see a call graph or what a method does on its way to
returning, and every shape error §3.6 tabulates was found by opening a file instead of counting
matches. Nothing in §1 rests on it being complete — §1.3 uses it as the set of changes that produce
an `ADJUSTMENT`, which describes what the list holds and claims nothing about what it omits — and a
component-by-component read should be expected to find more. Read as a floor:

1. `Widget.setVisible` — revokes focus, hover and press in the subtree; silent.
2. `Widget.setEnabled` — same; silent, and a screen reader must announce it.
3. `Widget.setFocusable` — silent, and it produces no frame.
4. `Widget.setTooltip`, both overloads — silent, and the accessible description in every platform mapping.
5. `Widget.setControlSize`, `setLayoutDirection`, `setLocale`, `setInheritanceHost` — silent. All four are already `final`, all four already return early when handed the value they hold, and all four end in `markNeedsLayout()`; §3.1 answers all four, three of them by deciding they stay silent.
6. **Focus movement itself.** `Scene.setFocus` reaches only the protected `Widget.onFocusGained`/`onFocusLost` subclass hooks; `Scene.focusedWidget()` is poll-only. There is no `Scene.observeFocus` and no per-widget focus listener.
7. `Button` armed and pressed state; `isArmed()` is package-private.
8. `ComboBox.highlightedIndex` — the open popup's active descendant, moved by every arrow, Page, Home, End and by type-ahead.
9. `ComboBox` open/closed, `PopupMenu` open, `MenuBar.isOpen()`.
10. `Chart` series visibility flipped by a legend **click** — a user gesture that changes what the chart shows, with no `onLegendToggle`.
11. `Chart.hoveredLegend`.
12. `ProgressBar.setProgress`, `setIndeterminate`.
13. `PasswordField.setRevealed` — it changes bullets into plaintext.
14. `ScrollView.scrollTo`/`scrollBy`/`revealRect` and the offsets, whether from a wheel, a drag, a keyboard reveal or code; `ListView`'s scroll anchor likewise.
15. `ScrollBar` thumb position.
16. `TabbedPane.addTab` moving the selection from none to the first tab.
17. Caret and selection in `TextField`, `TextArea` and `Spinner`. `TextEditModel` has no listener at all, so **a screen reader cannot follow the caret**.
18. IME preedit: `TextField.onPreedit` fires nothing and `composingText()` is poll-only.
19. `TextField.setValidation`/`setError` — the accessible *invalid* state.
20. Every accessible **name**: `Button.setText`, `Button.setIcon`, `Label` text, `RadioButton.setText`, a checkbox's label, `setPlaceholder`.
21. `Transition`/`ColorTransition` completion; `isAnimating()` is poll-only.
22. The entire `MediaPlayer` pipeline: `state()`, `isEnded()`, `failure()`, `positionMicros()`, `underruns()` are all poll-only, which is why `MediaControls` polls at 100 ms.
23. `Sounds`/`Playback` completion.
24. `Dialog` has no `onShow`; only its result future.

And fourteen more where a callback exists and one mutator does not reach it: `Checkbox.setChecked`,
`Slider.setValue`, **`Slider.setStep`** (which silently *moves the value* by re-snapping it, and does
not say so), `Spinner.setValue`, **`Spinner.stepFromTyped`** (which can apply an entire typed user
edit with no notification at all when the following step is a no-op), `TextField.setText` and
`TextArea.setText` (both contradicted by their own `onChange` javadoc, which claims *"typed or
programmatic"*), `ColorPicker.setColor` and `setInitialColor` (silent and undocumented),
**`ColorPicker.setAlphaEnabled(false)`** and **`ColorPickerButton.setAlphaEnabled(false)`** (both
change the colour and say nothing, though the identical change made by dragging the alpha ramp
fires), `ColorPickerButton.setColor`, `SplitPane.setRatio`, and `MenuItem.setChecked`.

Five more fire with the widget half-updated: `TabbedPane.selectTab` announces before the focus
decision and before the reveal; `SegmentedControl.setSelectedIndex` announces with `bounds[]` and
both indicator transitions still describing the previous segment; `ButtonGroup.select` assigns
`current` before telling the leaving radio, so a listener on the leaver already reads the new group
index while both `isSelected()` flags are mid-swap; `TextField.insertText` and `onCharTyped` announce
before `ensureCursorVisible()`, so a listener reading `caretRect()` gets the pre-edit scroll offset —
which is exactly what an IME candidate window or a caret report needs; and `SplitPane.applyRatio`
announces after marking layout, so the ratio is new and the extents old.

### 0.13 The toolkit already separates the mutation from the notification, in five spellings

`Slider`, `Spinner` and `SplitPane` funnel every change through `apply(value, boolean fromUser)` and
fire inside `if (fromUser)`. `ColorPickerButton` splits an `apply` returning whether anything changed
from a `change` that notifies. `Checkbox` splits `setChecked` from `toggle`. `RadioButton` splits a
package-private `setSelectedSilently` from a package-private `fireChange` that its **group** calls.
The selection widgets funnel through a `select` or `commit` that always notifies.

**Every widget already computes the flag this record needs, at the point it is needed, and throws it
away. What is missing is a second subscriber, not a second seam** — with one important exception:
**six widgets have no seam at all.**
`SegmentedControl.choose`, the path every click and arrow key takes, calls the *public*
`setSelectedIndex` (`SegmentedControl.java:166-168`); `ListView.select(index, reveal)` carries a
reveal flag and no origin; `TabbedPane.selectTab(index, Focus)` carries a focus mode and no origin;
`ComboBox.commit(int)` is a second entry point beside `setSelectedIndex` rather than a shared funnel;
`RadioButton.select()` carries nothing; and `Checkbox.toggle()` carries nothing. §1.5 builds those
six seams and five more it found later, and it is the largest single piece of the implementation.

**`SegmentedControl.choose` is not the only path that reaches a public mutator from a gesture.
There are five, and they are enumerated rather than asserted because every one of them is visible
from the caller's side and invisible from the callee's.** Read out of the tree:

1. `SegmentedControl.choose(int)` calls the **public** `setSelectedIndex`
   (`SegmentedControl.java:166-168`) — every click and every arrow key.
2. `ButtonGroup.moveSelection(RadioButton, int)` ends in `candidate.select()`
   (`ButtonGroup.java:157`) — the **public** no-arg `select()`, the same method an application
   calls, on the arrow-key path for every radio group.
3. **`ListView.activate()` is public** (`ListView.java:293-299`), it is what the Enter key runs
   (`:722-725`, `consumeAnd(event, this::activate)`), and it is a third gesture reaching a public
   verb. Its javadoc says so outright — *"Fires `onActivate` for the selected row, as Enter does"* —
   which makes it the one public method in the repository whose entire stated contract is *run the
   handler*, and therefore the one this record's rule costs the most. §1.5 threads it and §3.7 lists
   what it costs.
4. `TabbedPane.applyFocus` calls the **public** `Widget.requestFocus()` (`TabbedPane.java:302, :304`)
   from `selectTab`, which every click and every arrow key on the header strip enters.
5. `ButtonGroup.moveSelection` calls the same public `requestFocus()` (`ButtonGroup.java:156`),
   immediately before it calls the public `select()`, so one arrow key crosses the public surface
   twice.

A design that reads a public mutator as `CODE` therefore labels every arrow-key selection in every
radio group `CODE`, silences the application's `onChange` on the one gesture radio groups exist for,
tells a screen reader that nobody moved, and — through 4 and 5 — reports every keyboard-driven focus
move in a tab strip and a radio group as a focus move made by code. §1.5's table threads all five.

**A note on 4 and 5, because they are a different shape from the first three.** `requestFocus()` is
`final` on `Widget` (`Widget.java:619-623`) and delegates to `Scene.requestFocus`, so there is no
per-widget seam to cut: the origin has to travel into the scene's focus funnel or the funnel has to
be entered by an origin-carrying package-private sibling. §1.5 takes the second, and §1.10's
*"`CODE` for `requestFocus()`"* is narrowed there to *`CODE` for the public `requestFocus()`, which
is not what a gesture calls*.

### 0.14 Five facts about the source that decide how §1 can be built

Read out of the tree, because each contradicts the reading a method list gives, and §1 rests on all
five.

**`ButtonGroup` is not a `Widget`.** `ButtonGroup.java:23` is `public final class ButtonGroup`, with
no superclass: it holds a `List<RadioButton>`, a `Consumer<Integer> onSelect` and a `current`, and it
has no box, no parent and no scene. It is the fifth member of the selection family and the only one
that is not a widget. `Menu` (`Menu.java:24`), `MenuItem` (`MenuItem.java:21`), `PopupMenu`
(`PopupMenu.java:70`) and `Dialog` (`Dialog.java:104`) are the same shape for the same reason. Five
public types, not four, are outside anything a `Widget`-sourced channel can carry, and one of them is
in the family this record's own contract test drives. `SelectionContractTest` already solved that
once: it reaches all five through a private `Choice` interface with `name`, `selectedIndex`,
`setSelectedIndex` and `onSelect`, which is the shape §3.5 reuses.

**The character delta of a text edit already exists, six times, inside `TextEditModel`.** Every
mutation reaches the buffer through one private `splice(start, end, value)` — its own javadoc says
*"Every mutator comes through here"* — and every public mutator computes the triple before calling
it: `insertInternal` has `start = selectionStart()`, `end = selectionEnd()` and `value`, and passes
exactly those to `record` and to `noteEditStart`; `backspace` has `(previous, cursor, "")`;
`deleteForward` has `(cursor, next, "")`; `deleteSelection` has `(selectionStart(), selectionEnd(),
"")`; `setText` has `(0, buffer.length(), sanitized)`; and `restore`, which serves both `undo` and
`redo`, has `(step.offset, replacedEnd, value)`. What the model does **not** do is let anything out:
it publishes `text()`, `textVersion()` and a line-granularity `LineDamage`, and it deliberately
answers `noteWholeEdit()` for `setText`, `undo` and `redo` because *line* damage there is unbounded —
a statement about lines, not about offsets. Above it, `TextField.fireIfChanged` compares two whole
`String`s and `TextArea.fireIfChanged` compares two `textVersion` longs, so neither widget can
re-derive what the model threw away. **The delta is free where it is computed and unavailable
everywhere else**, which is what §1.4 and §3.1 build on.

**`TextArea.fireChange` is not a notification method, and §3.1's *"`fireChange` is deleted on both"*
would therefore delete more than a notification.** Read out of the file (`TextArea.java:2139-2166`),
it is three statements: `noteTextChanged()`, then `goalX = Float.NaN`, then `onChange.accept(...)`.
`noteTextChanged()` is the horizontal-extent cache — it drops the shaped floor, splices `lineWidths`
from the model's `LineDamage` when the area is unwrapped and the damage is available, falls back to
invalidating the whole cached content width when it is not, and **calls `model.clearLineDamage()`**,
which makes this method the sole consumer of that damage. `goalX = Float.NaN` ends a vertical
cursor run. Neither has anything to do with telling anyone. `TextField.fireChange` really is one
statement (`TextField.java:1175-1177`) — which is the asymmetry, and the reason a rule read off one
file was wrong about the other. So the `TextArea` seam keeps its cache maintenance and its `goalX`
reset and loses only its last line, and the ordering matters in one direction: **the line damage is
cleared by the extent cache and the character damage by the announcement**, two independent
clears of two independent damages, and the announcement must come after the cache work so a watcher
that measures reads a cache that has already been updated.

**And `TextField.fireChange` is `protected`, with a javadoc inviting subclasses to call it** — *"for
subclasses that edit the model directly"* — and it has an in-repository caller in
`SearchField.clear()`. Deleting it is a published-API removal and not an internal tidy-up; §3.7 lists it.

**`MediaControls.onPaint` calls `refresh()` as its first statement** (`MediaControls.java:353-354`),
on every paint, and `refresh()` writes widget state: `bar.setEnabled`, `playPause.setEnabled`,
`playPause.setTooltip`, `mute.setTooltip`, `bar.setValue`, the clock `Label`'s `position.setText` at
`:414`, and two `setVisible`s inside `updateSoundCluster`. Under §3.1 each of those becomes an
announcement, so this is the path that would deliver notifications from inside a paint.

**How loud that path actually is has to be read out of the method, and reading it out shrinks it to
two writes.** `refresh()` is guarded throughout, by
design and by its own javadoc — *"Every write is guarded"*, because *"a bar that repainted the window
every poll over a paused picture would be the most expensive thing on screen"*. Site by site:
`playPause.setTooltip` sits inside `if (showingPaused == null || showingPaused != paused)` and
`mute.setTooltip` inside `if (showingMuted == null || showingMuted != muted)`, so neither can fire
except on a genuine flip; `updateSoundCluster`'s two `setVisible`s sit inside
`if (want != soundShown)` (`:279-284`); `syncVolume`'s `setValue` sits inside
`if (wanted != volume.value())` (`:290`), under a javadoc saying it is *guarded, because `setValue`
invalidates*; `applyGain` writes `handle.setGain` on a `Playback` and **no widget state at all**, so
it can never announce; and both `setEnabled` calls carry `Widget.setEnabled`'s own `!=` guard.
That leaves exactly two writes that announce on a steady tick of a playing video: **`bar.setValue`,
whenever the position advances, and `position.setText`** — and the clock is not once a frame either,
because `clock(micros)` truncates to whole seconds (`:449-453`) and formats `m:ss`, so at a
hundred-millisecond poll the string it is handed is the string it already holds on nine ticks in ten
and §1.2's guard eats those. The honest reading is therefore **one `VALUE` per tick and one `NAME`
per second**, not a firehose — and it is still two announcements from inside a paint, which is why
§1.7 moves the call anyway. The volume is not what forces the move; *a paint may not announce at
all* is.

Two further facts about that method decide how §1.7 can move it, and neither is visible in the
description *a paint that calls a refresher*. **`refresh()` ends by running the
application's callback** — `if (onRefresh != null) onRefresh.run();` at `:418-419` is its last
statement, so wherever `refresh()` is called from, `setOnRefresh`'s listener is called from there
too. And **`onPaint` is what arms the poll**: the `if (!polling && scene() != null && isShowing())`
block at `:363-366` is the tail of `onPaint`, and `poll()`'s own comment says so — *the paint that
brings it back arms it again*. The poll stops itself the moment the transport stops showing, and a
paint is the only thing that restarts it, because `Widget` has `onAttached` and `onDetached` hooks
and a `final`, poll-only `isShowing()`, and **nothing anywhere notifies that a widget became
shown**. The arming and the refresh are two separate statements in one method, though, so they
separate cleanly: §1.7 moves `refresh()` and leaves the arming block exactly where it is.

**And re-arming on a paint is not `MediaControls`' private trick but a named toolkit-wide pattern,
which is why §1.7 keeps it.** `Widget.isShowing()`'s own javadoc (`Widget.java:436-447`) says
animations use it *to pause while their widget is inside a hidden container (an unselected tab) or
clipped out of view*, and closes on *the repaint that reveals the widget re-arms them (the
established re-arm-on-paint pattern)*. It is the toolkit's standing answer to the missing
became-shown hook, and moving one widget off it would be a decision about one widget; §1.7 takes the
narrower change instead and moves only the call that announces.

**That javadoc also says what *showing* means, and it is larger than it looks.** It is not the
visible flag over the ancestor chain plus attachment. `isShowing()` walks the chain for `visible`,
requires a scene, **and then intersects this widget's box with every clipping ancestor's, returning
false when the widget is fully clipped away** (`Widget.java:448-478`). So a widget stops and starts
showing by **scrolling**, with no flag written anywhere, and it depends on laid-out geometry rather
than on any field a mutator sets. That is what §6.13 has to answer to, and it is why the answer there
is larger than an ancestor walk.

**`limn-backend-lwjgl` calls the two methods §1.9 removes.** `FontStore` holds
`private final Runnable onFontsChanged = this::resolutionChanged` (`FontStore.java:151`), registers
it in its constructor (`:251`) and removes it in `close()` (`:1137`), and
`SystemFontCatalogTest` pairs the same two calls. The backend is not untouched by this record.

**And the pair is documented idempotent.** `Fonts.addChangeListener` is
`listeners.addIfAbsent(...)`, its javadoc reads *"idempotent per instance"*, and `Scene.bind` relies
on the pair being repeatable — it removes and re-adds `metricsListener` on all four axes with a
comment saying it is *"kept here so a rebind is still a no-op rather than a double-register"*.
`ThemeEditor.onAttached` and the demo's `FontPicker.onAttached` each add on attach and remove on
detach. A plain-array append has no `addIfAbsent`, so the de-duplication is a property §1.9 has to
replace rather than inherit.

---

## 1. Decision

### 1.1 Two channels, and one family that is neither

**The fluent single slot stays, unchanged in shape, and gains a meaning it did not have.** It is the
**handler**: the application's one response to *the user operating this widget*. It is registered
with `onX`, it returns `this`, it holds one listener, and a second registration is now an error
rather than a silent replacement (§3.2, which is where that policy is written; §1.4 describes the
watcher channel and does not restate it). One is the right number, because a widget has one
application and being operated has one response; if two parties both want to respond, one of them is
observing, and observation has its own channel.

**A second channel is added for observation.** It is the **watchers**: any number of parties may
watch any widget, with no coordination between them, no ability to disturb each other or the handler,
and no ability to be disturbed by them. It is registered with `observeChanges`, it returns a
`Subscription` that unregisters — a handle type of its own, so a handle can never be handed back to
a registrar as a listener (§1.7) — and it is notified for **every** change to the widget, whatever
moved it.

**A third family already exists and is named here so it is not confused with either.** A *lifecycle
observer* reports that something has happened once and will not un-happen: a window closed, a popup
closed, a viewport's GL resources must be released, a media heartbeat ticked. It is not widget state,
it has no origin and no aspect, and it takes the shape `Scene.observeWindowClosed` already has, with
one correction — `observeX(Runnable)` returning a `Subscription` rather than the `Runnable` it
returns today, for the reason §1.7 gives — any number of subscribers. `PopupMenu.onClose`,
`Viewport3D.onDispose` and `MediaControls.setOnRefresh` are moved into it (§3.1), which is where they
always belonged.

Two channels and not one, and the reason is the fourth column of §0.11. A toolkit with one channel of
two multiplicities and no signposting is JavaFX, where an application must guess which of
`selectedProperty().addListener` and `setOnAction` it wants. A toolkit whose accessibility rides a
third channel each component must remember to fire is Swing and AppKit, and the components that
forgot are the historical reason their coverage is patchy. Here there are exactly two, they answer
two different questions — *what did the user do to this widget*, and *what is now true of this
widget* — and everything that observes rather than responds is on the second one, **including the
toolkit itself and including anything that describes this tree to something outside it**.

### 1.2 The rule

> **The handler runs when, and only when, the user operated this widget. The watchers run for every
> change, whatever moved it.**

That is the whole rule. It is stated once, on `Change.Origin`, instead of twenty-seven times in
twenty-seven javadocs that do not agree, and it is decidable at the site that announces the change
without any judgement about what a method is called: a component knows whether it is inside
`onMouseEvent`, `onKeyEvent`, `onCharTyped` or `onPreedit`, or inside a public setter. That bit is
what `apply(value, boolean fromUser)` already computes in five widgets.

Three consequences follow, and all three are subtractions.

**Termination is structural, not guarded.** A handler's writes are not user input, so they reach no
second handler; two controls bound to each other through their handlers cannot recurse at all. That
retires the four verbatim warnings and the `StackOverflowError` they were defending against.

**The echo guards delete.** `ThemeEditor`'s `syncing` flag goes, all eleven sites, because none of
the four setters it brackets can now reach a handler. `ColorPicker`'s `switchingFormat` goes and its
`syncing` was already dead.

**`MediaControls` stops being a special case.** Its hundred-millisecond `bar.setValue` was always a
caller's write, so it reaches no handler and the transport does not scrub the film ten times a
second — and the watchers still hear the position move, with an origin that says nobody touched the
bar, which is what lets a bridge treat it as a throttled live region rather than a gesture.

**A rule still needs the idempotence clause, and it moves to where it is checkable.** *A mutator
announces only when the state it names actually moved.* This is not the same sentence as before: the
guard is on the **announcement**, not necessarily on the whole method, which matters because
`TextField.setText` also clears the undo history and moves the caret and must go on doing so when it
is handed the string it already holds. Three mutators have no such comparison today —
`TextField.setText` and `TextArea.setText` (both splice unconditionally through
`TextEditModel.setText`) and `ColorPicker.setColor` (whose `adopt` has no guard anywhere) — and
`Widget.setFocusable` has none either, which matters because `ButtonGroup.applyRovingFocus` calls
`setFocusable(false)` on every non-holder member on every selection change and would otherwise
announce N−1 changes that did not happen. All four gain the comparison, and the contract test of §3.5
pins it for every mutator in the set.

### 1.3 `Origin`, and why it has three values

- **`USER`** — a person operated this control: a pointer, a key, an IME commit, or an assistive
  technology performing the control's action on the person's behalf. **This is the only origin that
  reaches the handler.**
- **`CODE`** — a caller wrote the state this mutator names. `setValue`, `setChecked`, `setText`,
  `setSelectedIndex`, `setColor`, `setVisible`, and a verb invoked from code.
- **`ADJUSTMENT`** — the widget moved this itself, because something else changed. A step grid
  re-snapping a value, a list refreshing past its selection, a tab pane taking its first tab, an
  alpha channel being disabled under a colour, focus revoked because the widget holding it was
  hidden.

`CODE` and `ADJUSTMENT` are treated identically by the rule, and the distinction still earns its keep
three ways: it names six changes that are silent defects today, so the list of mutators that produce
an `ADJUSTMENT` *is* the defect list of §0.12; it is the difference a bridge uses to decide between
announcing an interaction and announcing a state update; and a two-way binding uses it to break an
echo.

**The rule is over the aspect, not over the method's name, because a name decides nothing for a
verb.** §0.3 found that the whole inconsistency this record is about originates in a verb family —
`select()`, `toggle()`, `activate()`, `clear()` — and a rule reading *"`CODE` when the mutator's own
name is the property that changed"* is simply undecidable for all four: none of those four names a
property. So:

> **A change is `CODE` when its aspect is the one the call's own contract names, and `ADJUSTMENT`
> when the call moved some other aspect as a consequence.**

A verb's contract names exactly one aspect and that is what makes it decidable: `toggle()` names the
checkbox's value, so `VALUE`/`CODE`; `SearchField.clear()` names its text, so `TEXT`/`CODE`;
`RadioButton.select()` names the group's selection, so `VALUE`/`CODE`; `ListView.activate()` names
its invocation, so `INVOKED`/`CODE`. And the consequences fall out the same way: `setStep` names the
range, so `RANGE`/`CODE`, and the value it re-snaps is a different aspect, so `VALUE`/`ADJUSTMENT`;
`ListView.refresh()` names its children and the selection it collapses is `SELECTION`/`ADJUSTMENT`;
`addTab` names children and the first selection it takes is `SELECTION`/`ADJUSTMENT`;
`setAlphaEnabled(false)` names alpha and the colour it moves is `VALUE`/`ADJUSTMENT`;
`setVisible(false)` names visibility and the focus it revokes is `FOCUS`/`ADJUSTMENT`.

**The rule is over a verb an application can reach, and a verb it cannot reach is not `CODE` by
default.** `MenuItem.activate()` is the case that forces the clause: reading it as `INVOKED`/`CODE`
on the strength of its name is the mistake the rule invites, and this clause closes it. `activate()`
is **package-private** (`MenuItem.java:237`) and has exactly three callers, all of them input
handlers: `PopupMenu`'s `chooseItem`, reached from `clickAt` (`PopupMenu.java:1272, :1312-1325`);
`PopupMenu`'s `Keys.ENTER, Keys.SPACE` branch (`:1394-1405`); and `MenuBar.runAccelerator`, reached
from a key chord (`MenuBar.java:478, :501-509`). There is no path from outside the package at all.
Labelling it `CODE` would mean, under §1.2, that **a menu click runs no action** — every menu in the
toolkit silently dead, and the record's own rule the cause. So:

> **A mutator that is not public has no `CODE` reading to default to. Its origin is the origin its
> callers hand it, and if every caller is an input handler it takes `USER` and nothing else.**

`MenuItem.activate` therefore becomes `activate(Origin)` and all three callers pass `USER`; §1.5 has
the seam and §3.1 the row. The same clause covers `RadioButton.fireChange`, `setSelectedSilently`
and every other package-private seam this record cuts, and it is what makes the seam table of §1.5
the list of *entry points* rather than a list of method names.

**One clause is not mechanical, and there is exactly one compound it applies to.** A radio group
spells one change in two aspects — each member's `VALUE` and the group's `SELECTION` — and splitting
its origin would say a member moved by itself when the caller moved it deliberately. So: *a compound
whose members' aspects are the toolkit's spelling of one change carries one origin throughout*, and
the only such compound in this repository is `ButtonGroup` with its `RadioButton`s. Wherever that
swap is entered — `group.setSelectedIndex`, `group.clearSelection`, `radio.select()`, a click, an
arrow key — all three announcements carry the entry point's origin. That is one named exception over
one named compound, which is a thing a record can carry; a rule that answered nothing for four verbs
is not.

`USER` needs no such clause because it is decided at the seam and not by the aspect: a component
knows whether it is inside `onMouseEvent`, `onKeyEvent`, `onCharTyped` or `onPreedit`. `CODE` versus
`ADJUSTMENT` is not self-enforcing that way; §3.5's contract test pins the exact pair for every
public mutator, which is what makes a mislabelling a failing test rather than a review comment, and
§6.9 records what it still cannot make impossible.

### 1.4 The API, exactly

All of it lives in `limn.scene`, beside `Widget` and `Scene`, and **not** in `limn.scene.event`,
which is the input package and describes something a widget *consumes*. A notification is not an
input event. **One type is the exception and it is deliberate**: the `Subscription` handle goes in
`limn.concurrent`, because it is handed back by registrars in four packages and two of them do not
depend on `limn.scene` at all — the reasoning is under its declaration below, and it is §1.9's
reasoning for `Listeners`.

```java
package limn.scene;

/**
 * What changed on a widget, and what moved it. Every value but a text edit is interned, so a
 * notification allocates nothing; a text edit allocates one record per edit, and not one per
 * watcher.
 */
public sealed interface Change {

    /** What about the widget changed. Closed: a widget with a state outside this list has nothing
     *  a platform accessibility API can be told.
     *
     *  <p>The list is in three parts, and the parts are not decoration — the accessor obligation
     *  below applies to the first part only.
     *
     *  <p><b>State aspects</b> name something the widget still is after the notification, and a
     *  public accessor answers every one of them. <b>Event aspects</b> name something that
     *  happened and is over; nothing answers them, by construction, and the notification is the
     *  whole of the information. <b>{@code LAYOUT}</b> is neither: it is one marker per layout
     *  pass, sourced at the scene's root, and what it says is <i>re-read the bounds you hold</i>. */
    enum Aspect {
        // state
        VALUE, RANGE, SELECTION, TEXT, ACTIVE, EXPANDED,
        NAME, DESCRIPTION, VALIDITY,
        ENABLED, VISIBLE, FOCUSABLE, FOCUS,
        CHILDREN,
        // event
        INVOKED, COMMITTED, SUBMITTED,
        // pass marker
        LAYOUT
    }

    /** What moved it; see the rule on this enum. */
    enum Origin { USER, CODE, ADJUSTMENT }

    Aspect aspect();

    Origin origin();

    default boolean fromUser() {
        return origin() == Origin.USER;
    }

    /**
     * The interned value for this pair. Never allocates.
     *
     * @throws IllegalArgumentException for {@link Aspect#TEXT}, which only {@link #edit} can
     *         make: {@code TEXT} is the one aspect a watcher may narrow on, so it must never
     *         arrive as a {@link State}.
     */
    static Change of(Aspect aspect, Origin origin) { /* 17 x 3 table, built once */ }

    /** A text edit, with the offsets a platform text-changed event wants. Allocates one record. */
    static TextEdit edit(Origin origin, int offset, int removed, int inserted) { … }

    /** Every aspect but a text edit. {@code aspect()} is never {@link Aspect#TEXT}. */
    final class State implements Change { /* private constructor; aspect(); origin() */ }

    /** A text edit. {@code aspect()} is always {@link Aspect#TEXT}. */
    final class TextEdit implements Change {
        public int offset();
        public int removed();
        public int inserted();
    }
}

/** A watcher. Called on the UI thread, from inside the change it describes. */
@FunctionalInterface
public interface ChangeObserver {
    void changed(Widget source, Change change);
}

/** The float callback the JDK does not have, for the two widgets whose value is a float. */
@FunctionalInterface
public interface FloatConsumer {
    void accept(float value);
}
```

`Change` is **sealed with two implementations rather than one class with three accessors that return
−1 on seventeen of its eighteen aspects**. A partial type whose accessors answer wrongly on almost
every value it can hold is the shape ADR 006 finding 5 refused when it replaced the `tr()` convention
with a type, and it is not reintroduced here to save one class.

**Two implementations mean nothing unless the discriminator is total, so `of` refuses `TEXT`.** A
watcher narrows the same way a bridge does — `if (change instanceof Change.TextEdit edit)` — and a
`Change.of(Aspect.TEXT, origin)` that compiled and returned a `State` would give that watcher a
`ClassCastException` at its own site, with no diagnostic anywhere near the component that announced
it. So `TEXT` is not in `of`'s table and the table is fifty-one values rather than fifty-four;
`Change.edit` is the only way to make one, and `aspect() == TEXT` and `instanceof TextEdit` are the
same question. The throw is at the announcing site and names the aspect.

```java
package limn.concurrent;

/**
 * What a registration hands back: the one way to stop listening. A distinct type and not a
 * {@link Runnable}, because a {@code Runnable} handle returned from a {@code Runnable}-taking
 * registrar can be handed straight back to it and registers the unsubscriber as a listener —
 * which compiles, runs, and is silent. Cancelling twice is a no-op.
 */
@FunctionalInterface
public interface Subscription {
    void cancel();
}
```

**`Subscription` is in `limn.concurrent` and not in `limn.scene`, and that is settled here because
five registrars in four packages return it.** An earlier draft declared it beside `Widget` and
`Scene`, which are its two most conspicuous registrars and are not its only ones: §1.9 gives the same
return type to five process-wide axes, and only two of the five live in `limn.scene`. `Fonts` is
`limn.graphics`, `I18n` is `limn.i18n`, `Theme` is `limn.components`. **Two of those packages do not
depend on `limn.scene` at all today** — no file under `limn/graphics/` or `limn/i18n/` imports it,
and the dependency runs the other way — so declaring the handle there would make a shared vocabulary
type the reason `limn.graphics` first depends on the widget layer, to hand back a two-line interface
with no scene in it. `limn.concurrent` is where the dependencies already point: `I18n` imports it
today, so do `limn.graphics`'s `SvgIcon` and `Images`, and nothing in `limn.concurrent` imports
`limn.scene`. It is also exactly the argument §1.9 makes for putting `Listeners` there, and the two
types are one mechanism — the handle a fan-out hands back and the storage it hands back from —
so they belong on the same shelf, which is `Ui`'s: UI-thread-confined, reachable from every layer
that registers anything.

```java
public abstract class Widget {

    /**
     * Watches every change to this widget, from every origin, without touching whatever handler
     * the application registered. Returns a handle that unregisters; cancelling it twice is a
     * no-op. The subscription belongs to the widget and survives detach and re-attach. UI thread.
     */
    public final Subscription observeChanges(ChangeObserver observer);

    /**
     * Announces a change that has already settled, and then runs the handler if the origin is
     * USER: the last statement of an ordinary mutator, after the state, the layout marks and any
     * focus move. Exactly {@code announceChange(change)} followed by {@code runHandler(change)} —
     * the two halves in one call, which is what every seam but a compound's wants. UI thread.
     */
    protected final void notifyChange(Change change);

    /**
     * The announcing half alone: this widget's watchers, then its scene's watchers, and no
     * handler at any origin. For a compound whose members must all be announced before any
     * handler runs (§1.6), which is its only caller in this repository. A seam that calls this
     * owes a matching runHandler for the same change, or it has silently disabled the
     * application's handler.
     */
    protected final void announceChange(Change change);

    /**
     * The handler half alone: runs this widget's handler for a change this widget has already
     * announced. It takes the whole change rather than its aspect, because the origin is what
     * decides whether the handler runs at all: it is a no-op unless {@code change.origin()} is
     * USER, which is what lets a compound seam call it for every member without branching.
     */
    protected final void runHandler(Change change);

    /**
     * The same as notifyChange, for a text edit, taking the edit's shape rather than a built value
     * so that a keystroke on a tree nobody watches constructs nothing. The three numbers come from
     * the editing model, which is the only thing that has them; see below. There is no
     * announce-only sibling: no compound in this repository spans a text edit.
     *
     * <p>It reaches the handler exactly as notifyChange does, and without a Change to hand to
     * runHandler: the handler half needs only the aspect and the origin, and both are already
     * here. So after every watcher has run this calls {@code handleUserChange(Aspect.TEXT)}
     * when {@code origin} is USER, and nothing when it is not. The {@code Change.TextEdit} is
     * built only if there is a watcher to receive it, which is the whole point of taking the
     * shape rather than a value — a user keystroke on a widget nobody watches still reaches the
     * application's handler and still allocates nothing on this path.
     */
    protected final void notifyTextEdit(Change.Origin origin, int offset, int removed, int inserted);

    /**
     * Runs this widget's application handler for a change it has just announced. Called by
     * runHandler, and so by notifyChange, only for a USER origin and after every watcher has run.
     * A component overrides it once and switches on the aspect; a component with no handler does
     * not override it. An override that does not recognise the aspect must call {@code super},
     * because a subclass of a component that has a handler inherits that component's dispatch
     * through this method.
     */
    protected void handleUserChange(Change.Aspect aspect) { }
}

public final class Scene {

    /**
     * Watches every change to every widget in this scene's tree, overlays included, from every
     * origin. One registration serves a whole window, and it works on an unbound scene.
     * Returns a handle that unregisters. UI thread.
     */
    public Subscription observeChanges(ChangeObserver observer);
}
```

`handleUserChange` is the answer to a question the two-channel shape otherwise cannot answer: a
single `notifyChange(Change)` cannot itself call a typed slot such as an `IntConsumer` or a
`FloatConsumer`. Hand-writing "announce, then fire the handler" at twenty-seven sites would put the
ordering guarantee of §1.6 back into a convention a component author can forget, which is the failure
this record is about. One override per component, switching on the aspect, keeps the ordering in the
base class, costs one virtual call on the `USER` path only, and allocates nothing.

**And it is why the text path has a handler at all, which an earlier draft left unsaid.** `runHandler`
takes a whole `Change`, and `notifyTextEdit` deliberately has none: it takes the edit's *shape* so
that a keystroke on a widget nobody watches builds nothing. Read together, the two look as though a
`TEXT` change could reach no handler — the one aspect where a missing route would be silent and
constant, since typing into a `TextField` is the most ordinary thing an application listens for. The
route exists, and it is `handleUserChange`'s signature that provides it: **the handler half needs the
aspect and the origin and nothing else**, and `notifyTextEdit` is handed both. So its sequence is the
same three steps every other seam has — settle, announce, handle — with only the middle one
conditional on a watcher: build the `Change.TextEdit` and deliver it if anything is watching, then,
if the origin is `USER`, call `handleUserChange(Aspect.TEXT)`. `TextField`'s override reads `text()`
and hands it to `onChange`, exactly as `Slider`'s reads `value()`. What the text path does **not**
have is an announce-only sibling, and that is the deliberate absence stated in the javadoc: no
compound in this repository spans a text edit, so nothing needs the halves separated here. **The
general form of the rule, for whoever adds the next shape-taking seam:** the handler half is reached
from an aspect and an origin, never from a constructed `Change`, which is precisely why
`handleUserChange` takes an `Aspect` and not a `Change`.

**`notifyChange` is split into two halves, because the compound rule cannot be expressed without
the split.** §1.3's compound clause requires a radio group's swap to announce the leaver's `VALUE` and the
enterer's `VALUE` **before either member's handler runs**, and §1.6 states that order as a
guarantee. One indivisible `notifyChange` per widget cannot produce it: `leaver.notifyChange(VALUE,
USER)` runs the leaver's handler as its last act, before the enterer has announced anything. The
alternative was to change the compound rule to announce-then-handle per member in turn, and that was
weighed and refused, because the leaver's handler may mutate a third widget and that mutation would
be announced before the enterer's `VALUE` — a consequence announced before half its cause, which is
precisely the inversion §1.6's handler-last rule exists to prevent. So the halves are separable.
`notifyChange` remains the whole of the API for every ordinary seam and is what §1.11's obligations
are written against; `announceChange` and `runHandler` exist for **one** named caller, the
`ButtonGroup` seam, and §3.5's contract test asserts that nothing else in the repository calls
either half alone. **Both halves take the whole `Change` and not an aspect**, because the origin is
what `runHandler` decides on: a seam that has announced a `CODE` change may hand the same value to
`runHandler` and get a no-op, which is what lets a compound seam treat all three origins alike
instead of branching once per member.

**Both halves are `protected` on a `limn.scene` type, and the compound that needs them lives in
`limn.components` and is not a `Widget`** — so `ButtonGroup` cannot call them at all. That is not a
new constraint and it already has the answer the toolkit uses today: `ButtonGroup` reaches its
members through package-private methods on `RadioButton`, `setSelectedSilently` and `fireChange`
(`ButtonGroup.java:126-130`). It goes on doing exactly that. `RadioButton` gains two package-private
one-liners — `announceSelection(Change.Origin)` and `runSelectionHandler(Change.Origin)` — that
wrap the two protected halves, and `ButtonGroup`'s seam calls those four times in the order §1.6
fixes. The same
shape serves `MenuItem`, whose handler slots are its own fields and need no `Widget` method at all.
Naming this here rather than leaving it to the implementation matters, because what the compound
seam costs is two new package-private methods on `RadioButton` — the base-class API being
unreachable from the class that owns the compound — and not a line at one site.

**It carries no payload, and that is affordable because the widget answers.** An override reads its
own state: `Slider` passes `value()`, `ComboBox` passes `selectedIndex()`, `TextField` passes
`text()`. That was checked against the three handlers whose payload is not the widget's obvious
value, and two of the three are answered outright: `Chart.onPointClick` and `Chart.onPointHover` both
want the `ChartPoint`, and `Chart.hoveredPoint()` is public and is assigned *before* either fires —
`hovered = picked;` precedes `onHover.accept(picked)`, and the click path reads the same field — so
both overrides read `hoveredPoint()` and nothing is lost. The third is real:
**`Viewport3D.onClick` takes a `Ray` built as `rayAt(lastX, lastY)` from two private fields**, and a
3D pick is not widget state — it has no aspect in a closed enum whose purpose is what a platform
accessibility API can be told, and inventing a `lastClickRay()` accessor to feed one would be a wart
on one widget to preserve a symmetry nothing reads. So `Viewport3D.onClick` stays exactly the slot it
is, fired from `onPointerEvent` where it is fired today, and the site hand-writes the two lines
§1.6's order requires: `notifyChange(INVOKED/USER)` first, then the slot. **It calls the whole
`notifyChange` and not the announcing half**: `Viewport3D` overrides no `handleUserChange`, so the
handler half is an empty virtual call, and routing the one hand-written site through the ordinary
method leaves the split halves with the single caller their existence is justified by. **That is the
one site in the repository where the ordering is a convention rather than a base-class guarantee**,
it is named here, and §3.5's contract test asserts the order at it. One named site is a thing a
record can carry; twenty-seven unnamed ones are the failure it is about.

**Overriding is inheritable, so the base-class guarantee is a `super` obligation.** `SearchField` and
`PasswordField` both extend `TextField`, which will override `handleUserChange` to reach `onChange`
for a `TEXT` edit; a subclass that overrode it for its own aspect and did not call `super` would
silently delete its parent's handler dispatch. Neither of the two needs an override —
`SearchField.clear()` is a `CODE` write and its `onSubmit` is a stateless event on its own seam, and
`PasswordField.setRevealed` is a `CODE` write that reaches no handler — so nothing in the repository
is on that edge today. The obligation is stated on the method, and §3.5's contract test drives every
`Widget` subclass including the two, so a future override that forgets `super` fails the build. It is
a weaker guarantee than "cannot be forgotten", and calling it that would be an overstatement; what it
is, is one obligation on one method with a test behind it, rather than an ordering rule spread over
twenty-seven call sites with nothing behind it. §6.11 is where the residue is recorded.

**There are exactly two registration points and notifications do not bubble.** A change on a widget
reaches that widget's watchers, and the watchers of the scene it is in — with no ancestor in between,
and with nothing at all on the scene side when it is in no scene (§1.7). Bubbling would make every
notification cost the depth of the tree and would leave *source* ambiguous; the scene registration is
the whole-tree channel and is what anything watching many widgets uses.

**A notification does not carry the new value.** It says what changed; the widget says what it is
now. That keeps `Change` one type instead of one per widget, keeps every aspect free of boxing, and
makes the accessor the single source of truth — at the price of an obligation this record accepts and
states:

> **No widget announces a *state* aspect that no public accessor answers.**

**The word *state* is load-bearing, and dropping it makes the rule false on this record's own
enum.** Stated without it, the rule forbids `INVOKED`, `COMMITTED` and `SUBMITTED` outright: a
button has no `wasInvoked()`, a slider no `wasCommitted()`, a search field no `wasSubmitted()`, and
none of the three could ever have one, because what they name is over by the time anyone could ask.
Yet §3.1 has `Button` announcing `INVOKED`, `Slider` announcing `COMMITTED` and `SearchField`
announcing `SUBMITTED`, and §1.10 leans on all three as *the four categories no snapshot can
produce* — the whole reason the channel exists rather than a poll. A rule that forbids the aspects
the record is built on is not a strict rule; it is a wrong one. So the obligation is scoped to the
state aspects, where it is both true and useful, and the two carve-outs are named rather than
implied:

- **The three event aspects are exempt by construction.** `INVOKED`, `COMMITTED` and `SUBMITTED`
  report a thing that happened. There is nothing to answer them and nothing a watcher would ask; the
  notification is the whole of the information, and a watcher that missed one has missed it, which
  is exactly why §1.10 calls a missed notification a permanently wrong cache. The corresponding
  obligation on these is different and is stated where it belongs, on the seam: they are announced
  once per occurrence and never re-announced.
- **`LAYOUT` is exempt as a pass marker.** It is not a property of the root it is sourced at, and no
  accessor answers *"a pass ran"*. What answers it is the bounds every widget already publishes,
  which is what §1.10 says the watcher re-reads.

For the state aspects the rule bites and is meant to. Four widgets cannot report their own label
text today (§0.12), so until the accessibility record supplies those accessors, those four announce
no `NAME`. That coupling is deliberate and is §6.3. Checked against the rest of the enum, everything else is
answered: `ACTIVE` by `ComboBox.highlightedIndex()`
(`ComboBox.java:207-210`), `VALIDITY` by `TextField.validation()` (`TextField.java:245-248`),
`DESCRIPTION` by `Widget.tooltip()`, `FOCUS` by `isFocused()`, `CHILDREN` by `children()`. The one
survivor is `Chart.hoveredLegend`, which is private with no accessor (`Chart.java:144`) — so a
legend *hover* announces nothing under this rule, while a legend *click* announces `CHILDREN`
because it flips a series' visibility, which `ChartSeries` answers. §3.1's `Chart` row says so.

**Where a text edit's three numbers come from, since nothing publishes them today.** §0.14 found the
triple computed at six sites inside `TextEditModel` and discarded at all six, and found that neither
wrapping widget can re-derive it: `TextField` compares two whole `String`s and `TextArea` compares
two `textVersion` longs. So `TextEditModel` gains a **character damage** beside the line damage it
already keeps, in the same idiom and at the same six sites:

```java
/** Whether a mutation has landed since the last {@link #clearCharDamage()}. */
public boolean hasCharDamage();
/** Where the damaged range begins, in chars. */ public int damageOffset();
/** How many chars it replaced. */             public int damageRemoved();
/** How many chars it inserted. */             public int damageInserted();
/** Forgets it: the consumer has announced. */ public void clearCharDamage();
```

Three ints and a boolean on the model, no allocation, and no new computation: `insertInternal` already
holds `(selectionStart(), selectionEnd(), value)` and passes exactly those to `record` and
`noteEditStart`; `backspace` holds `(previous, cursor, "")`; `deleteForward` `(cursor, next, "")`;
`deleteSelection` `(selectionStart(), selectionEnd(), "")`; `setText` `(0, buffer.length(),
sanitized)`; and `restore`, which serves both `undo` and `redo`, `(step.offset, replacedEnd, value)`.
`setText`, `undo` and `redo` answer `noteWholeEdit()` for *lines* because a line range there is
genuinely unbounded, and that says nothing about offsets: all three know their character range
exactly, and all three report it.

Two mutations before a consumer clears **compose to a covering range** — the lowest offset, and
`removed`/`inserted` widened to span both — which is what `lineDamage` already does when it widens to
the whole document, and which is truthful for a platform text-changed event because over-reporting a
replaced region is the coarse answer all three platforms accept. It cannot arise on the widgets'
own paths, where `fireIfChanged` brackets exactly one model call, but `TextArea`'s javadoc says the
model may be mutated directly, so it has to be specified rather than assumed away — specified and
unexercised, which is §6.12.

`TextField.fireIfChanged` and `TextArea.fireIfChanged` then read the four accessors, call
`notifyTextEdit`, and clear — which also deletes `TextField`'s two full document copies per edit, the
cost `TextArea` already stopped paying by comparing versions instead. **This is the model owning the
delta**, and it is a decision this record takes rather than defers to the accessibility record:
`notifyTextEdit` is unimplementable without it, so deferring the owner while depending on the answer
would be a gap and not a deferral. What stays deferred is what a *reader*
of a document needs — ranges, attributed runs, and reading a document by line or word — which is a
larger surface than one edit's offsets and is §5's entry.

**The source is a `Widget`, and that excludes five public types on purpose.** `ChangeObserver.changed`
takes the source as its first parameter — `Change` itself carries only an aspect and an origin, which
is what lets fifty-one of its values be interned — and the parameter's type is `Widget`. §0.14 found
that `ButtonGroup`, `Menu`, `MenuItem`, `PopupMenu` and `Dialog` are plain classes with no box, no
parent and no scene. **None of them can be a source, and none of them should be**: a watcher-channel
source is a node in a widget tree, and an object that is in no tree has no node for any consumer to
attach a change to. So those five announce nothing on the watcher channel, they keep their handler
slots, and §1.5 says how a `ButtonGroup`'s change reaches watchers anyway — through the two
`RadioButton`s that are widgets. What is genuinely lost is §6.1.

### 1.5 The thirteen seams that have to be written, and the three that only widen

Every announcement comes from one place per entry point, and that place takes an origin. **Eleven
seams do not exist and have to be cut; two more are notification with no funnel behind it and have to
be built rather than widened; three exist, take a `boolean fromUser` and widen it to an `Origin`.**
Sixteen groups of entry points in all, of which **thirteen are a seam somebody has to write** and
three are a parameter change. Cutting the eleven is the bulk of the change.

**The heading counts it that way because an earlier one counted it the other way and the source
refuted it.** *Eleven to cut and five that already exist* was written from a method list on which
five widgets look alike; opening the two colour widgets found that only three of the five have a
funnel to keep, and the paragraph below this table is where that reading is corrected. §3.6 carries
it as one of the survey's known errors. A section heading that still said *five already exist* would
be reasserting, in the place a reader looks first, exactly what its own body disproves.

**Where the eleven come from, because the count is a finding and not an accounting rule.** Six are
the widgets §0.13 catches with no seam at all, and they are visible from a method list: the mutator
is there and the origin is not. Two more, `SearchField` and `ButtonGroup`, are visible from one
file each. The last three are visible only from the caller's side — `ListView.activate()`, which
§0.13's enumeration lists as the third gesture through a public verb; `MenuItem.activate()`, whose
absence would silently kill every menu (§1.3); and the **focus funnel**, which two components enter
through the public `Widget.requestFocus()` on a gesture (§0.13, items 4 and 5). Eleven is what
reading the call graphs has found, which is the most an enumeration can be (§3.6).

| widget | today | the seam |
|---|---|---|
| `Checkbox` | `setChecked` and `toggle()` are separate paths | `private void apply(boolean value, Origin origin)`; `setChecked` passes `CODE`, `toggle()` passes `CODE`, the click and Space/Enter paths pass `USER` |
| `RadioButton` | `select()` is the only mutator and carries nothing | `void select(Origin origin)` package-private, with the public `select()` passing `CODE`; the click and key paths pass `USER`; `setSelectedSilently`/`fireChange` collapse into it |
| `ButtonGroup` | `select(RadioButton)` carries no origin, and `moveSelection` reaches the **public** `select()` | `void select(RadioButton member, Origin origin)` threaded from the member, and `moveSelection` calls `candidate.select(USER)` — see below |
| `ComboBox` | `setSelectedIndex` and `commit(int)` are two entry points | one `private void select(int index, Origin origin)`; `setSelectedIndex` passes `CODE`, the popup pick and the Enter/Space path pass `USER` |
| `TabbedPane` | `selectTab(int, Focus)` | `selectTab(int, Focus, Origin)` |
| `SegmentedControl` | `choose(int)` calls the **public** `setSelectedIndex` | `private void select(int index, Origin origin)`; the public setter and `choose` both call it |
| `ListView` (selection) | `select(int, boolean reveal)` | `select(int index, boolean reveal, Origin origin)` |
| `ListView` (activation) | the **public** `activate()` is what the Enter key runs | `private void activate(Origin origin)`; the public `activate()` passes `CODE`, the Enter path passes `USER` |
| `SearchField` | `clear()` calls the inherited `setText` and then `fireChange()` | one `clear(Origin)` that routes through `TextField`'s origin-carrying text seam **once**, so the public `clear()` announces one `TEXT`/`CODE` and the trailing button one `TEXT`/`USER` — not the two announcements a `clear` layered over an announcing `setText` would produce |
| `MenuItem` | `activate()` is package-private and its three callers are all input handlers | `void activate(Origin origin)`; `PopupMenu.chooseItem`, `PopupMenu`'s Enter/Space branch and `MenuBar.runAccelerator` all pass `USER`, and no public path to it exists or is added |
| `Scene` focus | `Scene.requestFocus(Widget)`, entered from the **public** `Widget.requestFocus()` by `TabbedPane.applyFocus` and `ButtonGroup.moveSelection` | one `void requestFocus(Widget target, Origin origin)` package-private on `Scene`, with the public `Widget.requestFocus()` passing `CODE`, the click and Tab paths passing `USER`, and the two component gesture paths passing `USER` through a package-private `Widget` sibling |

**The two colour widgets do not keep a funnel, because they have none.** Five widgets look alike
from a method list — `Slider`, `Spinner`, `SplitPane`, `ColorPickerButton` and `ColorPicker` — and
*keep the funnel you have, with `boolean fromUser` replaced by `Origin origin`* is true of three of
them. Read out of the tree: `Slider.apply(float raw, boolean fromUser)` (`Slider.java:182`),
`Spinner.apply(double raw, double grid, boolean fromUser)` (`Spinner.java:350`) and
`SplitPane.applyRatio(float raw, boolean fromUser)` (`SplitPane.java:285`) each take the flag and
fire inside `if (fromUser)` — those three keep their funnel and the flag widens to an `Origin`.
The other two have no flag to widen:

- **`ColorPickerButton`** has *two* entry points and not one, which is what §0.13 records:
  `private boolean apply(Color)` returns whether anything moved and announces
  nothing (`ColorPickerButton.java:270`), and `private void change(Color)` calls `apply` and then
  notifies (`:257-261`). `setColor` calls the first, the private picker's handler calls the second.
  So the seam is **built**, not widened: one `private void apply(Color value, Origin origin)`, with
  `change` collapsing into it.
- **`ColorPicker`** has no funnel at all and three separate notifying sites. `setColor` calls
  `adopt` and announces nothing (`ColorPicker.java:254-262`); `changed()` is a private notifier the
  drag paths call (`:396-400`); and `applyHex` calls `setColor` and then `onChange.accept(color())`
  by hand (`:426-437`), which is a fourth spelling of the same thing and the reason its
  `switchingFormat`/`syncing` pair exists. Its seam is the largest of the sixteen, not a parameter
  change.

Counted honestly that is **eleven new seams, plus two built where a funnel was assumed, plus three
widened ones** — thirteen to write and three to widen, which is what this section's heading says and
what *five already exist* did not. The middle group is the one a shorter sentence loses into the
last, and losing it there is how the wrong heading was arrived at.

**Five gestures reach a public mutator, not one, and all five have to be cut off.** §0.13 now
enumerates them: `SegmentedControl.choose` → the public `setSelectedIndex`;
`ButtonGroup.moveSelection` → the public `select()`; `ListView`'s Enter key → the public
`activate()`; and `TabbedPane.applyFocus` and `ButtonGroup.moveSelection` → the public
`requestFocus()`. Left alone, the first three label the gesture each control exists for `CODE`, so
the application's handler goes silent on it and a screen reader is told nobody moved; the last two
report every keyboard-driven focus move as a focus move made by code, which is the one distinction
`FOCUS`/`USER` exists to draw. Each therefore routes through the origin-carrying seam in its table
row, and the five are cut in one commit because they are one mistake.

**`ButtonGroup` is not a `Widget`, so it announces nothing itself, and nothing is lost.** §0.14 has
the fact and §1.4 has the reason: the source of a change is a node in a widget tree, and a
`ButtonGroup` is in no tree — it has no box, no parent and no scene, and no bridge could ever give it
a node to hang a `SELECTION` on. Its change reaches the watcher channel entire, through the two
widgets that do have nodes: the leaver's `VALUE` and the enterer's `VALUE`, both carrying the entry
point's origin under §1.3's compound clause, in the order §1.6 fixes. Its `onSelect(Consumer<Integer>)`
stays exactly the handler it is, on the group, fired only for a `USER` origin and only after both
members have announced and both members' handlers have run. §3.1's row and §6.1 say what that costs
a bridge, which is a `RADIO_GROUP` node it must build from the members rather than read.

**How the group reaches its members' two halves, concretely, because *hand-written at that seam*
reads as a few lines and is not.** `announceChange` and `runHandler` are `protected` on
`limn.scene.Widget`; `ButtonGroup` is a plain class in `limn.components` and can call neither. It
already has the answer it needs: it drives its members through package-private methods on
`RadioButton` today — `setSelectedSilently` and `fireChange` (`ButtonGroup.java:126-130`) — and it
goes on doing that. `RadioButton` gains two package-private wrappers over the two halves, and the
group's seam is six ordered calls: settle both flags, `current` and the roving focus; then
`leaver.announceSelection(origin)`, `enterer.announceSelection(origin)`; then
`leaver.runSelectionHandler(origin)`, `enterer.runSelectionHandler(origin)`; then
`onSelect.accept(index)` **under an explicit `origin == USER` test**. The two member calls need no
test, because `runHandler` is a no-op at any other origin. The group's own slot needs one, because
`onSelect` is `ButtonGroup`'s own field rather than a `Widget` method, so no base class is holding
the rule for it.

**The four remaining non-`Widget` types reach their handlers the same way, and `MenuItem` is the one
that could not be left out.** `Menu`, `MenuItem`, `PopupMenu` and `Dialog` have handler slots and no
watcher channel (§1.4), so each fires its own slot from its own origin-carrying seam and announces
nothing. For three of them that is a restatement of what they do today. For `MenuItem` it is a
change and a necessary one: `activate()` is the only path to a menu item's `action` and `onToggle`,
its three callers are all input handlers, and a rule that read it as `CODE` would have stopped every
menu in the toolkit from doing anything (§1.3). It takes an `Origin`, all three callers pass `USER`,
and the check is one comparison at the top of a package-private method.

**What this record does not build is a public way to say `USER`.** No public method carries an origin
argument, because an application asserting that it is the user is a claim the toolkit cannot check
and nothing in this repository needs. The consequence is real and is recorded in §6: until ADR 039's
action set is implemented, an assistive technology that *sets a slider's value* rather than dragging
it reaches the watchers and not the application's handler.
Actions that a gesture can express — invoking a button, toggling a checkbox, choosing a row, picking
a tab — have no such gap, because a bridge synthesises them through the scene's existing input path,
which already refuses disabled and invisible widgets in `hitTest`. That is also the answer to the
finding that `Checkbox.toggle()` flips a *disabled* checkbox: under this record `toggle()` is a
`CODE` write spelled as a verb, exactly like `setChecked`, so it neither needs nor gains an
`isEnabled()` guard, and the entry point an assistive technology reaches is input, which is guarded
already.

### 1.6 Ordering, reentrancy and exceptions

**Ordering, within one change.** A mutator settles its own state, its layout marks, its focus move
and its reveal, and only then calls `notifyChange` for **its own** aspect — which delivers in this
order:

1. the widget's own watchers, in registration order;
2. the scene's watchers, in registration order;
3. the widget's handler, if the origin is `USER`.

The handler runs **last**, and that is load-bearing rather than arbitrary. A handler may do anything,
including mutating other widgets, and each of those mutations announces itself synchronously at the
moment it happens. If the handler ran first, every consequence of a change would be announced before
the change itself, and a bridge would see a label's new text before the checkbox that caused it. So:

> **A notification precedes anything a watcher or a handler does in response to it.**

**The scope of that sentence is exact, and it has to be: stated over *anything done in response to
it*, it would be false of this record's own migration, at every mutator that moves focus.** The
counter-examples are not
exotic; they are the two components §0.13 catches reaching the public `requestFocus()`.
`TabbedPane.selectTab` hides the outgoing panel, shows the incoming one, reassigns the roving focus
flag and calls `applyFocus` — and only then, under §3.1, announces its `SELECTION`. Every one of
those steps announces under this record, so a bridge hears `VISIBLE`, `VISIBLE`, `FOCUSABLE`,
`FOCUS`, `FOCUS` and *then* the `SELECTION` that caused all five. `ButtonGroup.moveSelection` is
starker still: it calls `setFocusable(true)` and `requestFocus()` **before** `select()`
(`ButtonGroup.java:153-157`, under a comment explaining why the order has to be that way), so the
focus lands on a member that is not yet selected and the `FOCUS` precedes both `VALUE`s.
`Widget.setVisible(false)` is the same shape in miniature: it revokes focus inside the `if`
(`Widget.java:485-494`) and announces `VISIBLE` after, so the revocation's `FOCUS`/`ADJUSTMENT`
comes first.

**What the rule is about is observer code, not the mutator's own settling.** What the record
needs — and what a bridge and a two-way binding both rely on — is that *observer code* never sees an
effect before its cause. Consequences the **mutator itself** performs while settling are a different
thing: they are part of the change, not a reaction to it, and holding them back would mean
announcing a `SELECTION` while the focus and visibility it implies have not yet moved, which is
precisely the half-updated announcement §0.12 lists five of and this record exists to stop. So the
within-one-call ordering is stated positively, in the shape this section already uses across
widgets:

> **Within one call, aspects are announced in the order they settled, and the aspect the call's own
> contract names is announced last.**

`selectTab` announces `VISIBLE`, `FOCUSABLE` and `FOCUS` and then `SELECTION`; `setVisible`
announces `FOCUS` and then `VISIBLE`; `setStep` announces `VALUE`/`ADJUSTMENT` and then
`RANGE`/`CODE`. **The owning aspect last is the signal that the call is finished**, and it is the
one a consumer that wants a settled widget waits for — the same rule as *"the widget that owns a
compound announces last"*, one scope down. A bridge reading focus before selection on a tab strip is
reading the order the platform conventions want anyway; what it must never read is a label's new
text before the checkbox whose handler wrote it, and that is what the narrowed rule still
guarantees.

**Ordering, across widgets.** A widget announces after **its own** state has settled, not after the
whole compound has. Each widget announces for itself, in the order the changes happened, and the
widget that owns a compound announces last, so a listener that needs the whole picture observes the
owner. `ButtonGroup` is the one place the toolkit orders everything to the end, because a half-swapped
group is unreadable: both members' flags, `current` and the roving-focus reassignment settle, then
the leaver's `VALUE`, then the enterer's `VALUE`, then — for a `USER` origin only — the leaver's
handler, the enterer's handler and the group's `onSelect`, in that order. The group has no
announcement of its own because it is not a `Widget` (§1.4, §1.5); it is last in the order all the
same, which is what a listener that wants the settled index observes.

**That order is why `notifyChange` has two halves.** Announce, announce, handler, handler is not
expressible as two calls of a method that announces and then handles: the first call would run the
leaver's handler before the enterer had announced. `announceChange` and `runHandler` (§1.4) are what
make the compound rule statable, and `ButtonGroup`'s seam is the only place in the repository that
calls them separately. An order the API cannot produce is not a guarantee, which is why the split is
in the API rather than the looser order in the rule.

That order also fixes a defect §0.12 records: today `ButtonGroup.select` assigns `current` before
telling the leaving radio, so a listener on the leaver already reads the new group index while both
`isSelected()` flags are mid-swap. Under this ordering nothing is announced until the swap is whole.

**Reentrancy.** Every watcher array is a plain array field, swapped on registration and on
unregistration. A dispatch in flight keeps walking the array it started with, so a registration made
during a notification takes effect on the next one and an unregistration made during a notification
still runs for the current one. Those are the two halves of the rule the repository already relies on
in four places, and the array swap gives them for nothing, which is why it is the storage.

A nested change — a watcher that writes to a widget — is announced synchronously, in full, before the
outer dispatch continues. There is no depth limit and no reentrancy flag anywhere. Termination has
two arguments and needs both: handler chains cannot recurse at all (§1.2), and a watcher chain
terminates because a mutator announces nothing when the state it names did not move. A binding whose
transform oscillates does not converge and does not terminate; that failure is loud, and a depth cap
would replace it with a silent truncation.

**Exceptions, one sentence for every mechanism in the repository.** Each watcher is invoked inside its
own `try`, a throw is logged and reported through `Crashes.report` under a new `CrashPhase.OBSERVER`,
**the remaining watchers still run, and the handler still runs.** The last clause is the one that
matters: a bridge or an inspector that throws must not be able to stop the application's own handler,
and today, on the press and blur observers and on all four process-wide axes, it could. A throwing
watcher is *not* evicted — that is the ticker's policy and it is right there, because a ticker fires
every frame and keeping a thrower would re-throw every frame; a watcher fires when something changes,
so containing and continuing is the window-close list's policy, which is the best-argued site in the
repository and now the only one. A throwing handler is contained where it is contained today: the
per-event input catch, or `UiRuntime`'s task catch on `Spinner`'s auto-repeat, which is the one
handler delivered from a timer chain.

### 1.7 Thread, allocation and lifetime

**Thread.** Registration, unregistration and delivery are all UI-thread, and all three assert it.
Delivery is synchronous, from inside the mutation, and is never posted: the UI thread is the only
thread on which the state being described may legally be read, and posting would break the ordering
guarantee of §1.6 and make *state settled first* unverifiable.

**Delivery never happens from inside a paint. It is enforced rather than promised, and one existing
call has to move for it to hold.**

**The reason is not that a throwing watcher would trip frame-crash recovery**, which is the reading
the containment rule invites and which does not survive it: `containFrameCrash` is reached only from
`renderFrame`'s `catch (Throwable)` around `renderFrameImpl` (`Scene.java:1867-1895`), so it sees
only an exception that **escapes the frame** — and §1.6 guarantees that no watcher's exception ever
does, because each watcher is invoked inside its own `try` and a throw is contained and reported
under `CrashPhase.OBSERVER`. Containment cannot be argued for in one section and assumed absent in
the next. Three reasons carry the rule instead:

1. **A paint is re-run when nothing changed.** `Scene.renderFrame(canvas, rePresent)` takes a
   re-present flag precisely because a window can be repainted without any content having moved —
   a resize, an occlusion redraw, a swap-chain re-present — and those frames are excluded from
   `metrics()` for that reason (`Scene.java:1849-1866`). A notification announced from a paint would
   therefore be announced on frames where nothing happened, and a bridge would emit a platform event
   for a change no one made. §1.2's *announce only when the state moved* guard eats the identical
   ones, but the guard is a property of a mutator; a paint is not a mutator, and the rule this
   section states is that the paint announces **nothing**, which is checkable, rather than *mostly
   nothing*, which is not.
2. **A watcher may legally mutate, and a mutation during a paint lands on a frame that cannot take
   it.** §2 says a notification is not an invalidation and that a watcher that mutates something
   else invalidates it itself. From inside a paint that invalidation arrives after layout has run and
   while the frame's damage bookkeeping is being consumed — the state `containFrameCrash`'s own
   comment calls untrustworthy, reached this time with no exception at all: the widget is drawn with
   the geometry of a layout that its own mutation was too late to affect, and the repaint that would
   fix it is requested against a frame already in flight.
3. **The ordering rule of §1.6 is not satisfiable from a paint.** A mutator must settle its state,
   its layout marks and its focus move *and then* announce. A mutator running inside a paint runs
   after the pass that would honour its layout mark, so the mark it sets cannot describe the frame it
   is in. The announcement would be truthful about the state and false about everything the state
   implies.

**The rule is enforced, because a guarantee about every widget in the process cannot rest on a test
over this repository's own components.** An application's `Widget` subclass can write widget state
from its own `onPaint` exactly as `MediaControls` does today, and no test here would see it. So a
paint raises a flag and every announcing method reads it. The flag is a depth counter on
the UI thread, raised by `Scene` around `paintFramePass` in a `try`/`finally`, in the shape
`beginPaintCull` and `endPaintCull` already have beside it (`Scene.java:2131-2166`); it is on the
thread and not on the `Scene` because a widget mutated during a paint need not belong to the scene
being painted, or to any scene at all, and a counter and not a boolean because nesting must not
clear it early. `notifyChange`, `announceChange` and `notifyTextEdit` throw `IllegalStateException`,
naming the aspect, when they are called while it is raised.

**The check sits *before* the nobody-is-watching early return, and an earlier draft had it after.**
After the return is the cheaper place and it is the wrong one, because it makes the enforcement
absent in exactly the state the mistake is made in. A component author writes the offending
`onPaint` while developing — with no watcher anywhere, since a watcher is something a bridge, an
inspector or a binding attaches later — so the check that would have named the mistake never runs,
the habit sets, and the first thing that ever trips it is a screen reader attaching to a shipped
application. A rule that stays silent until an assistive technology arrives and then throws has
converted an authoring error into a crash at the worst possible moment, and it would be read, fairly,
as accessibility support breaking the application. Enforcement that is absent while the mistake is
being made is not enforcement, so the counter is read first and the throw does not depend on anyone
listening.

**What that costs, stated rather than waved past.** Every announcing call now reads the counter
before it reads the watcher array, so the quiet path — a widget nobody watches, which is most widgets
most of the time — pays one more read and one more branch than it did: §4's opening figure goes from
two reference reads and a branch to a counter read, a branch, and then those. There is exactly one
UI thread in a process — it is the one `Ui.checkUiThread()` names, and both the paint and every
announcement are confined to it and assert it — so the counter is an `int` that thread alone writes
and reads, a field read rather than a `ThreadLocal` lookup, and nothing about it allocates. Against
the mutator's own work — a field write, a comparison, an `invalidate()` — it is not measurable, and
§6.10's end-to-end benchmark is where it would show up if it were. That is the price of a guarantee
that holds for a widget nobody is watching yet, which is the only kind of guarantee §2 can honestly
make about every widget in the process.

The throw escapes into `renderFrameImpl` and is contained by `containFrameCrash` like any other
frame exception — loud in the way §3.2's second
registration is loud, and the one exception in this design that is meant to reach the frame rather
than be contained short of it.

The one path in this repository that would trip it is §0.14's: `MediaControls.onPaint` calls
`refresh()` as its first statement, and `refresh()` writes widget state that this record turns into
announcements. The volume is small — §0.14 reads the guards and finds one `VALUE` per tick and one
`NAME` per second, not a write per paint — and it makes no difference, because the guarantee is that
a paint announces *nothing*. So `refresh()` comes out of `onPaint`, and `onPaint` paints. **Under the
check's placement above it would trip on an ordinary run with nobody watching at all**, which is the
point: the defect §0.14 found by reading the file is the defect the runtime would now report on the
first frame, rather than the first frame after a screen reader arrived.

**Only `refresh()` moves, and the arming stays, so the re-arm-on-paint pattern is kept.** `onPaint`
does two unrelated things (`MediaControls.java:353-366`): it calls `refresh()` as its first
statement, and it ends with `if (!polling && scene() != null && isShowing()) { polling = true;
poll(); }`. Only the first announces. The second sets a private boolean and posts a task — no widget
write, nothing to announce — so taking the first out leaves the second untouched: the poll goes on
stopping itself the moment the transport stops showing, and the next paint goes on arming it, which
is the pattern `Widget.isShowing()`'s javadoc names as established and the animations use (§0.14).
`MediaControls` therefore departs from none of its neighbours and runs no timer while it is hidden,
and §6.13's missing became-shown hook costs it nothing, because the paint is how it learns.

**What it does cost is latency, and that is the whole of it.** `refresh()` now runs from the poll
tick alone, so the controls catch up at the next tick rather than at the paint — at most 100 ms
later, and on a repaint that no tick follows, a window resize or an occlusion redraw, not until the
tick that does. §3.1's row states it beside the rest of that widget's migration.

§3.5's contract test asserts the same rule from the other end — **no announcement of any kind**,
`notifyChange`, `announceChange` or `notifyTextEdit` alike, is reachable from an `onPaint` frame —
so the guarantee has a runtime check for the widgets this record cannot see and a test for the ones
it can. Stating it without either would be the kind of clause §0.3 found written four times and
abstracted nowhere.

**A layout pass is not a paint, and delivery from inside one is not excluded.** The guarantee above
is about `onPaint` only, and it has to be, because three components write widget state from
`onLayout` today and go on doing so: `TabbedPane.onLayout` calls `setVisible` on three overflow
buttons and `setEnabled` on two chevrons (`TabbedPane.java:512-516`), under a comment saying they are
*guarded setters, safe inside layout*; and `ListView`, `ScrollView` and `ComboBox` each call
`refresh()` on a scroll bar from inside their own `onLayout`. Under §3.1 those become `VISIBLE` and
`ENABLED` announcements delivered mid-pass. The guards mean they fire only when the flag actually
flips — which is exactly what a resize drag does, frame after frame, at the overflow boundary — so
this is a real path and not a theoretical one. §6.6 is where it is weighed, for the state
aspects as well as for `CHILDREN`.

**Allocation when nobody is watching.** None. The paint-depth read and a branch, then two reference
reads and a branch. `notifyChange` returns from the announcing half when the widget's array is null
and the scene has none, and `notifyTextEdit` constructs no record at all in that case, so a keystroke
on a tree nobody watches allocates exactly what it allocates today. **What neither of them skips is
the handler**: a `USER` change still runs `handleUserChange`, which is a virtual call over an
interned enum constant and allocates nothing — the early return is an early return out of the
*fan-out*, never out of the call.

**Allocation when somebody is watching.** Zero, for every aspect except a text edit. `Change.of`
answers from a table of fifty-one interned values built once; the dispatch is an indexed walk of a
plain array, measured at 1.91 ns and no bytes for one watcher and 5.84 ns for three — that is the
walk, which is what §0.2 benchmarked; the whole of `notifyChange` is unmeasured and is §6.10. `Change.edit`
allocates one small record per edit — per keystroke, not per watcher, on a path that already
allocates one input event per keystroke.

**Footprint.** One null field per widget, four bytes; a one-element array when the first watcher
arrives, twenty-four. A ten-thousand-widget tree with nobody watching costs forty kilobytes of null
fields, and **a bridge that uses the scene registration adds nothing per widget at all**. That is the
number that puts the bridge on the scene channel and keeps per-widget registration for the handful of
places that want one widget.

**Lifetime.** A widget's watchers die with the widget, and the widget's array is the only thing
holding them, so **there are no weak references anywhere in this design and none are needed** —
unlike the process-wide axes, where a strong listener pins an abandoned tree. A scene's watchers are
cleared when the window closes, alongside the existing cleanup. **Detaching a widget does not
unsubscribe it**: a subscription is a property of the widget, not of its attachment, so a recycled
list cell keeps its watchers across the mount and unmount it performs on every frame of a scroll, and
pays no registration cost at all. And the scene channel works on an **unbound** scene, which is the
trap ADR 002 §5.4 recorded and paid for once already.

**The two channels therefore have different reach, and the difference is a decision rather than an
oversight.** A widget subscription is the widget's and survives detach; a scene subscription is the
scene's and reaches only what the scene holds now. So a widget that changes while detached notifies
its own watchers and no scene's — there is no scene to ask — and a recycled list cell that is
unmounted and then written to is exactly that case, several times a frame while a list scrolls. The
right reading is that **the scene channel is a channel over a tree, not over a set of widgets**: it
hears what is in the window, which is what a bridge describing a window wants, and it goes quiet
about a widget the window no longer contains, which is also what that bridge wants, since a detached
widget has no node. A consumer that must follow one particular widget wherever it goes registers on
the widget. §2 states the guarantee in exactly those terms, because stating it as *"reaches the
widget's watchers and its scene's watchers"* would be false for every detached widget in the process.

**The handle is the only way to unsubscribe, and it has a type of its own.** There is no
`removeObserver(observer)` and no null-means-remove overload on the watcher channel, which removes
the third answer to the null question rather than answering it: the reason a no-op substitution
existed on two registrars is that it was the only way to unregister, and a handle is a better way.

**The handle is a `Subscription`, not a `Runnable`, and that is a correction rather than a taste.**
Today `Scene.observeWindowBlur(Runnable)` returns a `Runnable` (`Scene.java:853-856`), and
`observeWindowClosed(Runnable)` does the same (`:2642-2645`); and `PopupMenu.observeClose`,
`Viewport3D.observeDispose`, `MediaControls.observeRefresh` and the five axis `observeChanges`
methods below would all take a `Runnable` and hand one back in exactly that shape, if nothing said
otherwise. In every one of them **the listener and the handle are the same type on the same
method**, so

```java
Runnable handle = viewport.observeDispose(scene3d::dispose);
viewport.observeDispose(handle);   // compiles; registers the unsubscriber as a dispose listener
```

is a program that compiles, runs, registers an unsubscriber as a listener, and then — on the first
dispose — unsubscribes something at a moment nobody chose, with no diagnostic anywhere. It is the
same class of trap as the displaced single slot this whole record is about, one layer down, and
closing that one while opening this one would be no progress at all. A one-method interface with a
`cancel()` closes it for every registrar at once, for the cost of one type: the argument position
takes a `Runnable`, `ChangeObserver`, `Consumer<Widget>` or `Predicate<KeyEvent>`, the return
position takes a `Subscription`, and the two can never be confused. It is also where the idempotence
promise belongs — *cancelling twice is a no-op* is a sentence about the handle, and §1.9 explains
why it cannot be a sentence about the removal. `Scene.observePresses` and `Scene.addShortcutHandler`
never had the trap, because their listener types are not `Runnable`; they change return type with
the rest, so the toolkit has one handle type and not two. §3.7 lists the break.

### 1.8 A late subscriber is owed nothing, and that is why the accessors matter

There is no replay, no catch-up and no priming call. A watcher registered now hears about changes
from now on. The alternative — a synthetic notification at subscription time — cannot be written
honestly, because nothing can enumerate *every* aspect of an arbitrary widget, and because a
synthetic change is indistinguishable from a real one to a listener that pushes an undo entry or
plays a sound. What a late subscriber does instead is **read the widget**, which is the same thing
the notification would have sent it to do. `ThemeEditor.onAttached`'s hand-written catch-up is
therefore not a workaround but the correct pattern, and this record names it as such: **subscribe,
then read.**

### 1.9 One fan-out shape, in one place, for all of it

Ten mechanisms, six shapes, five exception policies and two-and-a-half answers to reentrancy is a
sprawl no ADR should leave standing while it adds an eleventh. One utility, in `limn.concurrent`
beside `Ui` because it is UI-thread-confined and `limn.scene`, `limn.graphics`, `limn.i18n` and
`limn.components` must all reach it — the placement is already legal, since `limn.concurrent.Work`
imports `limn.backend.CrashPhase` today, and it is where `Subscription` goes for the same reason
(§1.4):

```java
package limn.concurrent;

/**
 * Copy-on-write listener arrays for UI-thread-confined fan-out. A holder is a plain {@code L[]}
 * field, null when empty, so an unused one costs four bytes and a dispatch allocates nothing.
 */
public final class Listeners {

    /**
     * {@code current} (may be null) with {@code listener} appended; {@code empty} supplies the
     * array type. Appends unconditionally: the same listener registered twice is registered
     * twice, and is notified twice. UI thread.
     */
    public static <L> L[] added(L[] current, L listener, L[] empty);

    /**
     * {@code current} (may be null, in which case null) without the <b>first</b> entry identical
     * to {@code listener}, or null when that empties it. Removes one registration, never two.
     * UI thread.
     */
    public static <L> L[] removed(L[] current, L listener);

    /** {@code current} without every entry of {@code taken}: the one-shot lists' removal. */
    public static <L> L[] removedAll(L[] current, L[] taken);

    /** Logs and reports under {@code phase}; the caller keeps going. */
    public static void failed(CrashPhase phase, Throwable error);
}
```

and the dispatch every site writes, identically:

```java
ChangeObserver[] snapshot = watchers;          // one read; null means nobody
if (snapshot != null) {
    for (int i = 0; i < snapshot.length; i++) {
        try {
            snapshot[i].changed(this, change);
        } catch (Throwable error) {
            Listeners.failed(CrashPhase.OBSERVER, error);
        }
    }
}
```

Static over an array rather than an object wrapping a list, because a `Listeners` instance per widget
would cost the twenty-four bytes a null field does not, on ten thousand widgets.

**A handle's idempotence is the handle's, not `removed`'s.** §1.4 promises that cancelling a handle
twice is a no-op, and `removed` does not give that: two cancels of one handle would take two
registrations off a listener registered twice. So the handle is a one-shot — it nulls the reference
it captured on its first `cancel()` and does nothing thereafter — and `removed` is specified narrowly
enough (*the first identical entry, one registration, never two*) that the two statements compose
instead of contradicting. That is also why `added` is documented as appending unconditionally rather
than left to be assumed either way.

**And that loses a de-duplication three callers rely on today, so the axes get it back as a handle.**
§0.14 has the fact: `Fonts.addChangeListener` is `listeners.addIfAbsent(...)` and its javadoc says
*"idempotent per instance"*, `Scene.bind` removes and re-adds `metricsListener` on all four axes with
a comment that it is *"kept here so a rebind is still a no-op rather than a double-register"*, and
`ThemeEditor` and the demo's `FontPicker` each pair an add on attach with a remove on detach. A plain
array has no `addIfAbsent` and this record does not add one, because de-duplicating by identity on a
`Runnable` is the wrong instrument: two subscriptions are two subscriptions, and a mechanism that
silently merges them is how a caller loses one it meant to keep. **A handle answers it exactly**: a
caller holds a handle rather than a reference, and a double-register is no longer expressible,
because a second `observeChanges` yields a second handle the caller must decide what to do with.
`Scene`'s own axis subscriptions are held by the listener that has to outlive the scene rather than
by the scene, for the reason two paragraphs below. `Scene.bind`'s defensive remove-and-re-add pair
goes, and with it the comment explaining why it was there.

**The three `Scene` observers keep their names and signatures and move onto this storage**, which
gives them per-observer containment, the empty fast path `observeWindowBlur` lacks, and the snapshot
semantics they already depend on, for free. `windowClosed` removes only the observers that were in
its snapshot instead of calling `clear()`, which closes the dropped-registration defect and the
dialog completion leak behind it. All three registrars and all three returned handles gain
`Ui.checkUiThread()`. `addShortcutHandler` stays a consuming chain rather than a fan-out — it stops at
the first handler that returns true — and gains per-handler containment in which a thrower is read as
*did not handle*, which is the policy `Work`'s `deliverIf` already states for the same shape.

**The four process-wide axes become five, and lose their second method.** `Fonts`, `ControlSize`,
`LayoutDirection` and `I18n` lose `addChangeListener`/`removeChangeListener` and gain one
`public static Subscription observeChanges(Runnable listener)` returning a handle, thread-checked on
registration and on the handle, contained per listener — which deletes the
keep-the-reference-and-pair-it-yourself bookkeeping that `ThemeEditor` and the demo's font picker
each hand-write, and the total absence of containment that lets one throwing listener abort every
other scene's relayout and escape into the call that changed the setting. **Removing those eight
methods reaches `limn-backend-lwjgl`**, which §0.14 found calling two of them from `FontStore` and
two more from a test; §3.4 says what changes there, and it is no longer "nothing".

**`Theme` becomes the fifth**, with the same signature, and `Theme.setCurrent` notifies. That is a
dozen lines on an implementation being written anyway, and leaving it out guarantees a fifth
hand-rolled copy of the font listener list. A theme change is answered with `invalidate()` and a
measurement change with `relayout()`, because a palette carries colours and corner radii and nothing
any widget measures from. **That restriction stays**, and it is what keeps a theme change cheap even
when the theme editor pushes one on every drag frame. Lifting it would oblige the theme listener to
relayout every scene, and that is a separate decision this record does not take.

**Two responses mean two listeners, because one `Runnable` registered on five axes cannot tell which
one fired.** `Scene`'s weak listener is a `Runnable` (`Scene.java:253-274`): `run()` takes no
argument and no axis hands it one, so a single instance on all five axes could only answer a theme
change and a font change identically, and two responses over one `run()` is not something the
signature can express. So `Scene` registers **two** instances of the
same class, each holding the scene in the same `WeakReference` and each carrying the response it
performs: one on the four measurement axes — `Fonts`, `ControlSize`, `LayoutDirection`, `I18n` —
answering `relayout()`, and one on `Theme` alone, answering `invalidate()`. Two wrapper objects per
scene rather than one, sixteen bytes and a second array slot, against a response that is right for
the axis that fired.

**The weak listener purges itself by identity today, and a handle-only channel would break that
purge unless the handle lives on the listener — so it does.** Read out of the source
(`Scene.java:253-274`): the listener holds the scene in a `WeakReference`, and when `run()` finds
the reference empty it calls `Fonts.removeChangeListener(this)` and the same on the three other
axes. **It unsubscribes itself, from inside its own callback, using nothing but its own identity.**
Take the removers away and give it a handle instead, and ask who holds the handle. If the answer is
*the `Scene`*, then by the time the purge is wanted the `Scene` has been collected and the handle
with it: the listener stays in every axis's array for the life of the process, one entry per `Scene`
ever constructed, and every axis change walks all of them.

**What that costs, stated exactly, because the obvious phrasing overstates it.** The abandoned
*tree* is not pinned — the scene is behind a `WeakReference` and is still collected, which is the
guarantee ADR 002 §5.4 actually bought. What is pinned is the wrapper object and its array slot, and
what is lost is the **bound** on the array: §0.2 measured the self-purge as quadratic but bounded —
*one entry per `Scene` ever constructed, purged on the next change*, about a millisecond at four
thousand dead entries — and without the purge there is no purge at all, so the array grows without
limit and every process-axis change walks the whole of it. A leak of listener records rather than of
trees, and unbounded rather than transient.

**The fix is one field on the listener, and it makes the mechanism better than it is today.** Each
listener holds its own handles:

```java
private static final class GlobalAxisListener implements Runnable {
    private final WeakReference<Scene> scene;
    private final Consumer<Scene> response;   // Scene::relayout, or Scene::invalidate
    private Subscription[] handles;           // assigned after this instance's registrations

    void armed(Subscription... registered) { this.handles = registered; }

    @Override public void run() {
        Scene target = scene.get();
        if (target != null) { response.accept(target); return; }
        Subscription[] taken = handles;
        if (taken == null) {
            return;             // a change delivered mid-registration; purged on the next one
        }
        handles = null;
        for (Subscription handle : taken) {
            handle.cancel();
        }
    }
}
```

`Scene`'s constructor builds both listeners, registers the first on the four measurement axes and
the second on `Theme`, and calls `armed` on each with its own handles — four and one. Three
properties come out of that and all three are improvements: the purge is **O(1) per axis** instead of
an identity scan of a copy-on-write list, which is what made §0.2's measurement quadratic in the
first place; the purge no longer depends on the listener being findable by identity, so it composes
with `Listeners.removed`'s *first identical entry, one registration* rule; and the `null` window
between the first registration and `armed` is closed by returning and letting the next change purge,
which is the same *purged on the next change* the javadoc already promises. **The holder of a handle
must outlive the thing the handle is meant to release, and for a self-purging weak listener the only
object that qualifies is the listener** — so the `Scene` holds neither set. §3.5's new test — drop a
`Scene`, force a collection, assert all five axes purged, both listeners gone — is what keeps this
honest, and it is a test the repository has never had for a mechanism ADR 002 already leans on.

### 1.10 How a screen-reader bridge could use this, and why it never touches a handler

**What follows is what this channel makes available to a bridge, not a description of the one being
built.** ADR 039 §9.3 assembles its tree by walking it inside the frame and diffing two published
snapshots rather than by patching one, and where the two records describe the same bridge that
record governs. The second half of this section — that a bridge never touches a handler — holds
either way, and so does everything below about which aspects a poll could not have produced.

One registration per scene, and nothing else. The bridge calls `Scene.observeChanges` once, receives
`(source, change)` on the UI thread with the widget's state settled, updates the one node of its own
immutable snapshot that the change names, and emits the platform event. It reads no widget from a
provider thread, because it reads no widget at all after the initial walk — the snapshot is what
serves reads, and the snapshot is written only here. It never calls `onAction`, `onChange` or
`onSelect`, so an application's handlers are untouched and unaware, and if the bridge throws, §1.6
guarantees the application's handler still runs.

**One registration per scene is not one registration per window**, and this record has already twice
cited the reason: a scene can be replaced on a live window, which is why `Scene`'s metrics listener
is weak and why the replaced scene never receives `windowClosed`. A bridge subscribed to the outgoing
scene would go on holding a handle to a tree nobody is showing and would hear nothing from the tree
that replaced it — the same failure ADR 002 §5.4 recorded on the metrics axis, reproduced one layer
up. So the registration is per scene and the **re-registration is the bridge's obligation**: it
subscribes to a scene when that scene becomes a window's, releases the handle when the scene is
replaced or the window closes, and treats a replacement as a fresh initial walk, because to the
platform a new tree in the same window is a structure change of the whole window in any case. Where
the bridge learns that a window's scene changed is the same gap as §6.4 — the toolkit has no
enumeration of a window's scenes and no notification when one is swapped. The two are one item, and
ADR 039 §5.3 answers both for its own bridge (§6.4).

The four categories no snapshot can produce arrive as aspects on this channel and nowhere else:
`INVOKED`, `COMMITTED` and `SUBMITTED` carry the stateless events; `Change.TextEdit` carries the text
delta with the offsets known at the point the edit was made; the ordering inside one call is the
delivery order of §1.6; and transient state that does not survive a frame is announced as it happens
rather than sampled. Structure arrives as `CHILDREN` on the parent, emitted from the funnels that
already serve `add`, `remove`, the scene constructor and the overlay push and pop. Focus arrives as
two notifications, `FOCUS` on the widget losing it and then `FOCUS` on the widget gaining it, emitted
from the one funnel every focus path already goes through — `USER` for a click or a Tab, `CODE` for
the **public** `Widget.requestFocus()`, `ADJUSTMENT` when focus is revoked by a hide, a disable, a
detach or an overlay closing.

**`CODE` for the public `requestFocus()` is not the same as `CODE` for every call of it**, and the
difference is §0.13's items 4 and 5: `TabbedPane.applyFocus` and `ButtonGroup.moveSelection` both
call that public method straight out of a gesture, so a rule reading the method rather than the entry
point would report every keyboard-driven focus move in a tab strip and a radio group as made by code.
§1.5's last table row cuts the seam — a package-private `Scene.requestFocus(Widget, Origin)` with an
origin-carrying `Widget` sibling for the two component paths — and the public no-arg method keeps
`CODE`, which is what it means when an application calls it.

**Geometry is the deliberate exception.** Per-widget bounds are not an aspect, because `layoutBox`
and `moveChild` are the innermost loop of every layout pass and of every scroll frame, and hooking
them is the one change that would put this channel on the hot path and make it scale with the tree.
Instead the layout pass emits a single `LAYOUT` change per pass that actually ran, sourced at the
scene's root, and a watcher that cares re-reads the bounds it holds. That is O(1) per frame instead
of O(widgets), and it is the coarse signal all three platforms already accept for this purpose.

Attaching mid-session, the bridge walks the tree and reads accessors, per §1.8. A missed notification
is a permanently wrong cache on all three platforms, so the mitigation is reconciliation rather than
retry: because the bridge owns the snapshot, it can re-diff a subtree and emit a coarse
structure-changed event — which works for every state aspect and never for the stateless ones, which
is precisely why this channel is the primary mechanism and not a supplement to a poll.

### 1.11 What a component author owes, which is four things and not one

The rule of §1.2 is one sentence and it is genuinely the whole rule *for a reader*. For a writer it
is not one method, and saying so would be the same kind of promise §0.3 found made four times in four
javadocs that did not agree. Collected here so the whole obligation is in one place:

1. **Announce from one seam per entry point, last, and only when something moved.** One private
   funnel takes an `Origin`, and every public and every input path goes through it (§1.5) — and
   *every* means the five gestures §0.13 catches crossing the public surface, not just the ones a
   method list would show. It announces as its last act, after the state, the layout marks, the
   focus move and the reveal; where the call moved other aspects on the way, those are announced in
   settling order and the call's own aspect last (§1.6). It announces nothing when the state it
   names did not move (§1.2). The guard is on the announcement and not on the method, because
   `TextField.setText` must still clear the undo history when it is handed the string it already
   holds.
2. **Label the origin.** `USER` when the seam was entered from `onMouseEvent`, `onKeyEvent`,
   `onCharTyped` or `onPreedit`, which the component knows; `CODE` when the aspect is the one the
   call's own contract names *and the call is one an application can make*; `ADJUSTMENT` when the
   call moved some other aspect as a consequence (§1.3). A package-private seam has no `CODE`
   reading to default to and takes the origin its callers hand it. Only `USER` is self-enforcing;
   §3.5 pins the rest.
3. **Announce no *state* aspect no public accessor answers.** A notification says what changed and
   the widget says what it is now, so a state aspect with no getter is one a watcher cannot act on
   (§1.4). The three event aspects — `INVOKED`, `COMMITTED`, `SUBMITTED` — are exempt by
   construction, and so is `LAYOUT`; the constraint is real for the rest and it costs four widgets
   their `NAME` (§6.3).
4. **Reach the handler through `handleUserChange`, and call `super` if you do not recognise the
   aspect.** One override per component, switching on the aspect, reading the widget's own accessors
   for the payload (§1.4). The ordering lives in the base class; the `super` call is the one link in
   it a subclass can break, and §3.5 drives every subclass to catch that.

Four obligations, all four checked by one table-driven test, against a repository that today has the
same four spread across twenty-seven javadocs, four verbatim warnings and no test at all. That is the
honest trade, and it is a better one than "one method to remember" would have been if it were true.

---

## 2. What is guaranteed, and what is not

Written in the two-list form `docs/design/background-work.md` sets, because the second list is the
harder and more useful half.

**Guaranteed.**

- Every change to a widget's observable state is announced to that widget's watchers exactly once,
  whatever caused it, after that widget's own state has settled — and to the watchers of **the scene
  the widget is in at that moment**, exactly once, if it is in one. A widget that changes while
  detached reaches its own watchers and no scene's; that is §1.7's decision, not an omission.
- A mutator that is handed the state it already holds announces nothing.
- The application's handler runs if and only if the origin is `USER`, after every watcher of the
  change it answers has run.
- **Within one call, aspects are announced in the order they settled and the aspect the call's own
  contract names is announced last** — so the last announcement of a call is the signal that the
  call is finished.
- **A notification precedes anything a watcher or a handler does in response to it.** It does not
  precede what the mutator itself did while settling: see the first non-guarantee below.
- Watchers of one widget run in registration order, on the UI thread, synchronously, inside the call
  that made the change, and **never from inside a paint** — which is enforced at the announcing site
  and not merely promised: an announcement made during a paint throws, **whether or not anything is
  watching**, so the rule is enforced while an application is being written and not only once a
  bridge has attached (§1.7).
- A throwing watcher stops neither the remaining watchers nor the handler, and does not reach the
  caller that made the change.
- A registration made during a notification does not receive that notification; an unregistration
  made during one still runs for it.
- A subscription survives detach and re-attach, and is released when the widget is collected.
- The scene channel works on an unbound scene.
- `aspect() == TEXT` and `instanceof Change.TextEdit` are the same question, in both directions.

**Not guaranteed, and each of these is a decision.**

- **A mutator's own side effects are announced before the change that caused them.** A call that
  moves focus, hides a panel or reassigns a roving-focus flag on its way to its own aspect announces
  each of those first, because each settled first: `TabbedPane.selectTab` emits `VISIBLE`,
  `FOCUSABLE` and two `FOCUS` before its `SELECTION`, and `ButtonGroup.moveSelection` moves the focus
  before either member's `VALUE`. That is the settling-order guarantee above seen from the other
  side, and it is deliberate — holding them back would mean announcing a selection while the focus it
  implies had not yet moved. A consumer that wants the whole call waits for the owning aspect, which
  is last. §1.6.
- **No ordering between watchers of different widgets**, beyond the order the changes happened in.
- **A notification is not a frame.** It is delivered from inside the mutation, before any layout or
  paint the mutation asked for, so a watcher that reads geometry reads the geometry of the last pass:
  a segmented control reports its new index against the previous segment's bounds, a split pane its
  new ratio against the old extents, and both say so in their javadoc. This is ADR 023's rule
  restated for notifications.
- **Not from inside a paint is not the same as not from inside a pass.** The guarantee above is
  about `onPaint`, and it is exact. A **layout** pass is a different matter: `ListView` mounts cells,
  `TabbedPane` shows and hides its overflow buttons and three components refresh a scroll bar, all
  from inside `onLayout`, so `CHILDREN`, `VISIBLE` and `ENABLED` can all be delivered mid-pass, and a
  watcher that mutates the tree from one corrupts the pass it is inside. §6.6.
- **A notification is not an invalidation.** The mutator invalidated what it changed before it
  announced; a watcher that mutates something else invalidates that itself, exactly as a posted task
  does.
- **Nothing is retroactive.** A late subscriber learns the current state by reading it.
- **Nothing is coalesced.** Two changes in one call are two notifications; a value that moves and
  moves back announces twice. A bridge that wants one announcement per drag has `COMMITTED`.
- **A compound change over several widgets does not settle them all before the first notification.**
  `ButtonGroup` is the one exception and it is stated.
- **Five public types announce nothing on the watcher channel.** `ButtonGroup`, `Menu`, `MenuItem`,
  `PopupMenu` and `Dialog` are not `Widget`s, so they cannot be a source (§1.4). A radio group's
  change is fully recoverable from its members; the other four are §6.1.
- **A binding that does not converge does not terminate.** The idempotence clause ends every binding
  whose transform settles; one that oscillates recurses until the stack ends. That failure is loud,
  and a depth cap would replace it with a silent truncation.
- **`CODE` versus `ADJUSTMENT` is pinned by a test, not by the compiler.** §1.3.

---

## 3. What changes in this repository

**Nothing is deprecated: things are changed and removed.** That is a choice about published API and
not a claim that none exists. Nine releases sit on Maven Central, `README.md` tells a stranger to add
one dependency, and a stranger who did can already compile against `Slider.onChange(Consumer<Float>)`,
`Fonts.addChangeListener`, `Viewport3D.onDispose` and the rest of what changes below. What justifies
breaking them is narrower and sufficient: **the owner knows of no consumer, and a `0.x` line promises
no compatibility** — the version is the whole announcement, and this repository has made no other.

Deprecation was weighed against that and refused on its own terms, not by assuming the question away.
A deprecation cycle here would mean carrying `Consumer<Float>` beside `FloatConsumer` and
`addChangeListener` beside `observeChanges` through a release, and the cost is not the two extra
methods: it is that the *meaning* of `onChange` changes under this record whether or not its
signature does. A `Slider.onChange` that keeps compiling and stops firing for `setValue` is a silent
behaviour change, which is worse for a hypothetical consumer than a compile error, and no
`@Deprecated` annotation can warn about it. Given a break either way, the honest form of the break is
the loud one. **Once the line is `1.0` this argument expires**, and §3.7 says what would replace it.

The migration is otherwise internal, and what follows is a **survey of it, not an inventory**. §3.1
reads component by component and §3.6 counts by tree, and both are here to size the work and locate
the seams; neither is exact and §3.6 says why not, in its own words. **What is exact is listed in
the status block** — the rule, the origin definition, the API, the ordering, the containment, the
thread, allocation and lifetime rules, the obligations and the two lists of §2 — and **§1.5's seam
table is not among them**, for the reason §3.1 is not: it is a reading of the tree, and three of its
rows described a method's shape wrongly until the file was opened (§3.6's correction table). A row
below is settled when the component's own file is opened and its call graph read, which is the unit
of the implementation and the unit `NotificationContractTest` pins.

### 3.1 The rule applied, component by component

| widget | today | after |
|---|---|---|
| `Button` | `onAction` fires from a click and a key; `setText` and `setIcon` are silent, though both `text()` and `textSource()` exist | `onAction` unchanged; announces `INVOKED`/`USER`. Both `setText` overloads announce `NAME`/`CODE` and both `setIcon` overloads announce `NAME`/`CODE` too, because a button whose label is an icon has no other name to give — which is §0.12's item 20 closed for the widget that can close it, since §1.4 forbids announcing a `NAME` no accessor answers and this one answers |
| `Label` | `setText` and `setIcon` silent; it has no handler and never did | both `setText` overloads and both `setIcon` overloads announce `NAME`/`CODE`; it gains no handler and no `handleUserChange` override, because nothing a user does to a label changes it. It is in this table only because it is the other half of item 20 and because `MediaControls`' clock is one, and it announces about once a second rather than once a frame — the clock string is truncated to whole seconds, so §1.2's guard eats nine ticks in ten (§0.14, §1.7) |
| `Checkbox` | `setChecked` silent, `toggle()` fires, `toggle()` ignores `isEnabled` | `setChecked` and `toggle()` both announce `VALUE`/`CODE` and reach no handler; a click or Space announces `VALUE`/`USER` and reaches `onChange`; the `isEnabled` finding dissolves, because a write may move a disabled widget's state and always could |
| `RadioButton` | `select()` is the only mutator and always fires | the public `select()` announces `VALUE`/`CODE`; a click, and an arrow key through `ButtonGroup.moveSelection`, announce `VALUE`/`USER` and reach `onChange`; `setSelectedSilently`/`fireChange` collapse into the origin-carrying seam |
| `ButtonGroup` | `setSelectedIndex` and `clearSelection` fire; `current` is assigned before the leaving member is told; `moveSelection` reaches the **public** `select()` (`:157`) **and the public `requestFocus()` (`:156`) one line before it**, under a comment explaining that the order has to be that way | **it is not a `Widget`, so it announces nothing of its own** (§1.4, §1.5). The whole swap — both flags, `current`, the roving focus — settles first; then the leaver's `VALUE` and the enterer's `VALUE`, carrying the entry point's origin under §1.3's compound clause; then the two members' handlers, which `runHandler` runs only for `USER`, and then the group's `onSelect` under an explicit `USER` test, because that slot is the group's own field and no base class holds the rule for it. That order is only expressible because §1.4 splits `notifyChange` into `announceChange` and `runHandler`, and the group reaches both through two new package-private wrappers on `RadioButton`, the same way it reaches `setSelectedSilently` today. `moveSelection` passes `USER` to both the focus seam and the selection seam, so an arrow key goes on reaching `onSelect` — which it does today, through the public `select()` — and its focus move is `FOCUS`/`USER`. **A watcher hears the `FOCUS` before either `VALUE`**, because `moveSelection` moves focus first by design; §2's first non-guarantee |
| `Slider` | `setValue` silent; `setStep` silently moves the value; `Consumer<Float>` boxes per drag frame | `setValue` announces `VALUE`/`CODE`; `setStep` announces `RANGE`/`CODE` and, when the re-snap moves the value, `VALUE`/`ADJUSTMENT`, and its javadoc says it can; a drag announces `VALUE`/`USER` per step and `COMMITTED`/`USER` on release; `onChange`/`onCommit` take `FloatConsumer` |
| `Spinner` | `setValue` silent; a typed edit can apply with no notification at all; auto-repeat re-reads the value after the callback | `setValue` announces `VALUE`/`CODE`; the typed adoption and its step announce **one** `VALUE`/`USER` measured against the pre-edit value, which closes the hole; auto-repeat compares against what it applied rather than re-reading, so a handler that writes the value back no longer ends the user's press-and-hold; `onChange` takes `DoubleConsumer` |
| `TextEditModel` | knows every edit's character range at six sites and publishes none of them; `TextField` re-derives by comparing two whole `String`s | gains `hasCharDamage`/`damageOffset`/`damageRemoved`/`damageInserted`/`clearCharDamage` beside the line damage it already keeps (§1.4), set at the six sites that already compute the triple, composing to a covering range if two edits land before a read. Three ints and a boolean; no allocation, and no new computation |
| `TextField` / `TextArea` | `setText` silent, `insertText` fires, the registration javadoc claims both; the fire seam is `protected` on one and `private` on the other | `setText` announces a `TextEdit`/`CODE` and reaches no handler; `insertText` announces `TextEdit`/`CODE`; typing, pasting, undo and redo announce `TextEdit`/`USER` and reach `onChange`; each reads the model's character damage and clears it, which also retires `TextField.fireIfChanged`'s two whole-document copies per edit; caret and selection moves announce `SELECTION`; an IME preedit announces a `TextEdit`; validity announces `VALIDITY`; the javadoc is corrected to the rule; both gain the announcement guard `TextEditModel.setText` does not have; **`fireChange` is not simply deleted on both, because on one of the two it maintains a cache** — `TextField.fireChange` really is one statement (`TextField.java:1175-1177`) and its body becomes the inherited `notifyTextEdit`, but `TextArea.fireChange` is three (`TextArea.java:2139-2143`): `noteTextChanged()`, which splices the horizontal-extent cache from the model's `LineDamage` and is the sole caller of `clearLineDamage()`, then `goalX = Float.NaN`, which ends a vertical cursor run, and only then the notification. `TextArea` keeps the first two, in that order, and replaces the third; so the line damage is cleared by the extent cache and the character damage by the announcement, two independent clears, with the announcement last so a watcher that measures reads a cache already updated. `TextField.fireChange` is `protected` with a javadoc inviting subclasses to call it and has an in-repository caller in `SearchField.clear()`, so removing it is a published-API removal (§3.7) and the `protected`/`private` asymmetry is settled by both seams becoming private and the announcement living in the base class; and `setPlaceholder`, both overloads, announces `DESCRIPTION`/`CODE`, which is §0.12 item 20's last piece that has an accessor to answer it (`placeholder()`, `TextField.java:155`) |
| `SearchField` | `clear()` **calls** the inherited `setText("")` and then `TextField`'s `protected fireChange()` (`SearchField.java:36-41`); it takes no `Ui.checkUiThread()`; the `isEmpty` test in front of it is already §1.2's guard | one `clear(Origin)` that enters `TextField`'s origin-carrying text seam **once** and announces one `TEXT` — `CODE` from the public `clear()`, `USER` from the trailing button. Not a `clear` layered over an announcing `setText`, which would announce twice; and not a `clear` that calls `fireChange`, which §3.1's `TextField` row deletes. It gains the thread check its neighbours have, and its guard stays where it is |
| `ComboBox` | `setSelectedIndex` fires; open, close and the popup highlight are silent | `setSelectedIndex` announces `SELECTION`/`CODE` and reaches no handler; a pick from the popup is `USER` and reaches `onSelect`; `open`/`close` announce `EXPANDED`; the popup highlight announces `ACTIVE`, which is the active descendant a reader follows with the arrow keys |
| `TabbedPane` | `setSelectedIndex` fires; announces before the focus and reveal decisions; `addTab` taking the first tab is silent; the field contradicts the method; **`applyFocus` calls the public `Widget.requestFocus()`** (`:302, :304`) out of every click and arrow key on the header strip | `setSelectedIndex` is `CODE`; the announcement moves to the end of `selectTab`, after the focus decision and the reveal; `addTab`'s implicit first selection announces `SELECTION`/`ADJUSTMENT`; the field is renamed to match its method; `applyFocus` routes through the origin-carrying focus seam (§1.5) and passes the entry point's origin, so an arrow key's focus move is `FOCUS`/`USER` and not `FOCUS`/`CODE`. **A watcher therefore hears `VISIBLE`, `VISIBLE`, `FOCUSABLE`, `FOCUS`, `FOCUS`, then `SELECTION`** — the panel swap and the focus move settle before the selection they belong to is announced, which is §1.6's settling order and §2's first non-guarantee, and the `SELECTION` last is the signal the call is done |
| `ListView` | `setSelectedIndex`, `clearSelection` and a shrinking `refresh` all fire; **`activate()` is public** (`:293-299`) and is what the Enter key runs (`:722-725`) | the first two are `CODE`; `refresh` announces `CHILDREN`/`CODE` and, when the selection collapsed, `SELECTION`/`ADJUSTMENT`; a click is `USER`; the documented fact that rows are unmounted when `refresh` announces stays documented; `onActivate`'s javadoc drops the double activation, because no click-count path exists anywhere in the file. **The Enter path routes through the new `activate(Origin)` seam and passes `USER`, so it still reaches `onActivate`; the public no-arg `activate()` passes `CODE`, announces `INVOKED`/`CODE` and no longer runs the handler** — which is this record's single largest behavioural break, because that method's whole documented contract is *"Fires `onActivate` for the selected row, as Enter does"*. Its javadoc is rewritten to say what it now does; an application that wants its own open-the-row code run calls that code. §3.7 lists it |
| `SegmentedControl` | `choose` calls the public setter; announces with the bounds and both indicator transitions still describing the previous segment | gains the seam of §1.5; `setSelectedIndex` is `CODE`, a click or arrow key is `USER`; the announcement moves after the layout mark, and the javadoc states that geometry is a pass behind |
| `SplitPane` | `setRatio` silent; announces after marking layout; a null listener is silently swapped for a no-op | `setRatio` announces `VALUE`/`CODE`; the geometry lag is stated; `onRatioChange` takes `FloatConsumer`, and the no-op substitution becomes §3.2's one policy — **null clears the slot** — so the one behaviour it had that anyone could have relied on, unregistering, is kept and is now spelled the same way everywhere |
| `ColorPicker` | `setColor`/`setInitialColor` silent and undocumented; `setAlphaEnabled(false)` changes what `color()` answers and says nothing; `onCommit` unreachable from the keyboard; two reentrancy flags, one dead; a false javadoc sentence. **And no funnel at all**: `setColor` calls `adopt` and announces nothing (`:254-262`), `changed()` is a private notifier the drag paths call (`:396-400`), and `applyHex` calls `setColor` and then `onChange.accept(color())` by hand (`:426-437`) — three spellings of one announcement, which is why its seam is built rather than widened (§1.5) | the two writes announce `VALUE`/`CODE`, with the guard `adopt` lacks; `setAlphaEnabled(false)` announces `VALUE`/`ADJUSTMENT` when it moves the colour; the hex field, the alpha field and the channel spinners reach `COMMITTED` and `onCommit`, closing the keyboard hole; both flags are deleted because none of the guarded calls can now reach a handler, and the false sentence goes with them |
| `ColorPickerButton` | `setColor` silent by contract; `setAlphaEnabled(false)` moves the colour silently; a null listener is silently swapped for a no-op. **Two entry points, not a funnel**: `apply(Color)` returns whether anything moved and announces nothing, `change(Color)` calls `apply` and notifies (`:257-261`), and `setColor` calls the first while the private picker's handler calls the second | both announce; null clears the slot per §3.2, which is the same behaviour the substitution had and one fewer policy; it keeps taking its private picker's handler, which is now exactly right — what it wants is *the user moved the picker* |
| `MenuItem` | `setChecked` silent; `activate()`, the only path to `action` and `onToggle`, is package-private (`:237`) and has three callers, all input handlers: `PopupMenu.chooseItem` from a click (`PopupMenu.java:1272, :1312`), `PopupMenu`'s Enter/Space branch (`:1394`), and `MenuBar.runAccelerator` from a key chord (`MenuBar.java:478, :501`) | `setChecked` is a `CODE` write that reaches no handler, so the "no public notifying mutator" finding dissolves. **`activate()` becomes `activate(Origin)`, still package-private, and all three callers pass `USER`, so a menu click, an Enter and an accelerator all run the item's action and its `onToggle` exactly as today.** Without that row the rule of §1.2 reads `activate()` as `CODE` on the strength of its name and stops every menu in the toolkit from doing anything, with no compiler error and no failing test (§1.3, §1.5). The factories gain `Ui.checkUiThread()`. `MenuItem` is **not** a `Widget`, so it announces nothing on the watcher channel — see §6.1 |
| `PopupMenu` | three registrars with no thread check; `onClose` fires for a programmatic close, undocumented | all three gain thread checks; `onClose` becomes `observeClose(Runnable)` returning a handle — a lifecycle observer, not a handler — which fires for every close and admits `MenuBar` and an application at once. `PopupMenu` is **not** a `Widget` (§6) |
| `MenuBar` | takes its own popup's `onClose` | takes a handle from `observeClose` and releases it; announces `EXPANDED` itself, since it *is* a `Widget` |
| `MediaControls` | `setOnRefresh`: no thread check, null default, no null check, the only `setOnX` in the set; `onPaint` calls `refresh()` as its first statement, and `refresh()` writes widget state — though every write is guarded, so on a steady tick only `bar.setValue` and the clock `Label`'s `setText` actually move (§0.14); and `onPaint` is also what arms the 100 ms poll | `setOnRefresh` becomes `observeRefresh(Runnable)` returning a handle, non-null, thread-checked. It keeps running as `refresh()`'s last statement, which it is today (`:418-419`), so it fires **wherever `refresh()` fires** — which after this change is each poll tick, and no longer once per paint as well; that is what its javadoc already promises an injected widget, *the same heartbeat*, and a tick is a truer heartbeat than a paint rate was. **`refresh()` leaves `onPaint` and the arming block stays** (§1.7): `onPaint`'s tail still arms the 100 ms poll when the transport is showing, the poll still stops itself when it is not, and the paint that brings the transport back still restarts it, so this widget keeps the re-arm-on-paint pattern its neighbours use and runs no timer while it is hidden. One cost, and it is latency: `refresh()` runs from the tick alone, so the controls catch up at the next tick rather than at the paint — at most 100 ms — and a repaint that no tick follows, a window resize or an occlusion redraw, no longer refreshes on its own. Its three handler registrations on its private children need no change at all, because a write no longer reaches a handler and its hundred-millisecond position write was always a write |
| `Viewport3D` | `onDispose` is a single slot holding GPU cleanup; `onClick` and `onDispose` accept null; `onClick` takes a `Ray` built from two private pointer fields | `onDispose` becomes `observeDispose(Runnable)` returning a handle — the one registrar where a list is obviously right and a slot obviously wrong; `captureNext` is a documented one-shot and is untouched. **`onClick` is the one handler in the repository whose payload no accessor answers and whose gesture has no aspect** (§1.4), so it stays the slot it is, fired from `onPointerEvent`, with `notifyChange(INVOKED/USER)` hand-written immediately before it — the single named site where §1.6's order is a convention rather than a base-class guarantee, asserted by §3.5. Null clears it, per §3.2 |
| `Chart` (in `limn.components.chart`, which is why §3.5's enumeration has to reach subpackages) | a legend click flips a series' visibility and announces nothing; the hover field contradicts its method | the legend click announces `CHILDREN`/`USER`; hover announces `ACTIVE`/`USER`; the field is renamed. Both handlers take a `ChartPoint` and both are served by `handleUserChange` without a payload, because `hoveredPoint()` is public and `hovered` is assigned before either fires today |
| `ProgressBar` | nothing | `setProgress` announces `VALUE`, `setIndeterminate` announces `RANGE` |
| `PasswordField` | `setRevealed` silent, and it turns bullets into plaintext | `setRevealed` announces `VALUE`/`CODE`, because what changed is what the field shows of its own value, which is the thing a reader would speak |
| `ScrollView` / `ListView` scroll | silent from every origin | the offset announces `VALUE`, `USER` from a wheel or drag and `CODE` or `ADJUSTMENT` from `scrollTo` and `revealRect` |
| every `Widget` | `setVisible`, `setEnabled`, `setFocusable`, `setTooltip` all silent | each announces its aspect as a `CODE` write, which is what makes `setFocusable` and `setTooltip` observable at all, since neither produces a frame; `setVisible`, `setEnabled` and both `setTooltip` overloads become `final` — **four published methods that are non-final today** (`Widget.java:485, :506, :603, :608`), which §3.7 lists as a source break for any external subclass that overrides them; `setFocusable` is already `final` (`:526`) and is not one of the four — since a subclass override that forgot `super` would silently reopen the hole, and `setFocusable` and `setTooltip` gain the comparison they lack, which `setVisible` and `setEnabled` already have (`Widget.java:485-494`, `:506-514`). **`setTooltip`'s comparison is by value and not by reference**, and that is not a nicety: `setTooltip(String)` delegates to the `I18nString` overload through `I18nString.literal(text)` (`:603-605`), which builds a fresh instance on every call, so a `!=` guard would never hold and every repeated `setTooltip("Play")` would announce a `DESCRIPTION` that did not change. `I18nString` answers this already — it defines `equals` over its key and its English text (`I18nString.java:172-180`) — so the guard is `Objects.equals`, and that is the specification and not an implementation detail, because the obvious spelling is the wrong one. Two of those four are also written from inside `onLayout` — `TabbedPane` shows and hides three overflow buttons and enables and disables two chevrons there — so this row is where a notification first arrives mid-pass, at `CODE`, and §6.6 is where that is weighed. **And `setVisible` and `setEnabled` both revoke focus inside their own guard** (`:488-491`, `:509-512`), so each announces the revocation's `FOCUS`/`ADJUSTMENT` before its own `VISIBLE` or `ENABLED`: the settling order of §1.6, in the smallest case there is |
| `Widget`'s four inherited-axis setters | `setControlSize`, `setLayoutDirection`, `setLocale` and `setInheritanceHost` are silent (§0.12 item 5) | **three of them stay silent, and that is a decision rather than an omission.** All four are `final`, all four already carry §1.2's guard, and all four bump an axis epoch and end in `markNeedsLayout()` (`Widget.java:708-716`, `:747-765`, `:845-854`, `:952-967`) — `setInheritanceHost` bumping all three epochs, since the host link carries all three axes. What they move is what the subtree *measures* from, and no aspect in §1.4's closed enum names a control size, a direction or a host link, because none of the three is a thing a platform accessibility API is told about a node; what a consumer has to re-read is geometry, and the pass emits exactly one `LAYOUT` at the root for that (§1.10). `setInheritanceHost` has no getter at all, so §1.4's accessor obligation would forbid a state aspect for it even if one existed. **`setLocale` is the one that leaves a hole**: a subtree locale also moves the language every descendant's `I18nString`s resolve in (ADR 035), so every name, description and formatted value under it changes at once, and this channel has no coarse *re-read the text* marker to match the one it has for bounds — §6.16 |
| `Scene` | focus movement reaches only two protected subclass hooks; `requestFocus(Widget)` is public on `Scene` and reached through the public `final Widget.requestFocus()` (`Widget.java:619-623`), which two components call straight out of a gesture (§0.13) | the focus funnel announces `FOCUS` on the widget losing it and then on the widget gaining it, with the origin of §1.10; it gains a package-private `requestFocus(Widget, Origin)` so the click and Tab paths pass `USER`, `TabbedPane` and `ButtonGroup` pass the gesture's origin through a package-private `Widget` sibling, and the public no-arg method keeps `CODE`; the layout pass announces one `LAYOUT` at the root |
| `ThemeEditor` | `onChange` fires for `setToken`, `setCornerScale`, `revert` and `load` — the one coherent rule in the repository, on the wrong channel | those four announce `VALUE`/`CODE` and reach no handler; a user edit on a well or the corner slider announces `VALUE`/`USER` and reaches `onChange`; **nothing is lost**, because an application that drives the editor from a preset menu or an undo stack and wants to hear its own writes watches the editor instead of holding its handler; the `syncing` flag and its eleven sites are deleted |

Three of those rows overrule something written down, and each deserves its sentence.
`ListView.refresh`'s javadoc says the selection move is announced *"because a listener showing the
selected record would otherwise still be showing a deleted one"* — that listener now watches, in one
line, and hears the same change with an origin that tells it the list moved by itself. `ThemeEditor`
is the module the sweep nominated as having the one coherent rule, and this record overrules it for
the same reason: its rule obliges every caller driving the editor to defend against an echo, and the
module that paid eleven guard sites for the toolkit's inconsistency is not the one to copy on this
point. And `SearchField.clear()` stops being an anomaly by acquiring an origin, and not by picking between
two behaviours: it has one, built out of a silent setter and a hand-written `fireChange`, and what
replaces it is a single route through the text seam that announces once (§0.3, §1.5).

### 3.2 Types, names and null policy

The four selection handlers that box an index take `IntConsumer`, matching the one that already did.
`Slider` and `SplitPane` take `FloatConsumer`, which keeps `Math.round(v)` an `int` and keeps
`other.setValue(v)` compiling — a `DoubleConsumer` would have widened both and is refused for that
reason. `Spinner` takes `DoubleConsumer`. `Checkbox` and `RadioButton` keep `Consumer<Boolean>`,
because `Boolean.valueOf` is cached so the boxing allocates nothing and a new interface would buy
only symmetry; the boxing that mattered was `Float`, `Double` and `Integer`, which have no cache on
the values these widgets produce.

Every registration method in the components, on `Scene`, on `PopupMenu`, on the `Menu`/`MenuItem`
factories, on `Scene.setFrontPainter` and on the five process-wide axes asserts the UI thread.
`Work`'s five are deliberately left alone (§5).

**One null policy on the handler slots: `null` clears the slot, and a non-null listener registered
over an occupied one throws `IllegalStateException` naming the method.** That is the loud failure ADR
006 finding 5 asks for, at the cost of one branch per registrar and no new type, and it catches
`SliderTest`'s live displacement on the first run. Re-binding a handler over its life is
`w.onAction(null).onAction(next)`, and a widget whose handler must change often holds a mutable field
and registers `() -> current.run()`; both are stated in the javadoc.

**That policy replaces all three of today's answers, and §3.1's rows say so in the same words.** The
three that stored null unchecked — `Viewport3D.onDispose`, `Viewport3D.onClick`,
`MediaControls.setOnRefresh` — keep the null check they already have at their firing sites, because a
cleared slot *is* the null those sites already test for; two of the three stop being handler slots at
all and become lifecycle observers with handles (§1.1), so only `Viewport3D.onClick` is left needing
it. The two that substituted a no-op — `SplitPane.onRatioChange` and `ColorPickerButton.onChange` —
have **no** null check to stop doing, because a substituted no-op is never null; what they gain is a
firing site that tests the field, and what they keep is the one behaviour a caller could have relied
on, which is that passing null unregisters. The five split three and two, in opposite directions,
and §3.1's rows say it in these words.

`TabbedPane.onSelect` stops assigning a field called `onChange`; `Chart.onPointHover` stops assigning
one called `onHover`.

### 3.3 The defects this record closes on the way past

`Scene.windowClosed` removes only its snapshot's observers instead of clearing the list, closing the
dropped-registration defect and the dialog completion leak behind it. The press, blur and shortcut
loops gain the per-observer containment the window-close loop already has, and so do the five
process-wide axes. The blur loop gains the empty fast path its sibling has. `Fonts.setDefaultFamily`
and `Fonts.installCatalog` gain the thread check their three sibling axes already have, and `Fonts`'
listener field is spelled like theirs.

`PopupMenu` gains the detach hook it cannot have as a non-`Widget` in the only way available: its
`observeClose` handle is released by `close()` as today, and `MenuBar` and `ComboBox` release theirs;
the eleven-line handle-release block and `closeUnlessRefocused`, each written twice byte for byte in
`ComboBox` and `PopupMenu`, collapse into one helper while both files are open.

### 3.4 The applications

**`limn-demo`.** Every registration whose callback *reflects* the widget's state moves to
`observeChanges`, and every registration that *performs* something stays a handler. Concretely: the
list scene's detail pane follows a selection and moves; the three sliders whose labels mirror their
value move, and each one's duplicated starting literal is deleted in favour of one call that reads
the slider and then watches it; the segmented control's hand-written literal goes the same way; the
radio group that relies on a programmatic selection to write its first label moves too, and stops
depending on a notification that no longer arrives. The thirteen `Viewport3D.onDispose` registrations
become `observeDispose` and keep their handles. `VideoScene`'s one `setOnRefresh` becomes
`observeRefresh`. Everything driving a button, a menu or a dialog stays exactly where it is: 43 of
the demo's 106 widget-slot registrations are `onAction`, and not one of them moves.

**`limn-theme-editor`.** The `syncing` flag and its eleven sites delete; `syncFromBuilder`,
`syncCornerControl` and `setApplyLive` become ordinary code. Its own `onChange` keeps its name and
changes meaning as §3.1 says. Its hand-written `Fonts` catch-up on attach stays, because §1.8 says
that is the correct pattern, and it moves onto `Fonts.observeChanges` and keeps a handle instead of
pairing an add with a remove by hand.

**`limn-backend-lwjgl`.** Four call sites, not none, and §0.14 has them: `FontStore` holds
`private final Runnable onFontsChanged = this::resolutionChanged`,
registers it in its constructor and removes it in `close()`, and that `SystemFontCatalogTest` pairs
the same two calls — all four on the `Fonts.addChangeListener`/`removeChangeListener` pair §1.9
removes. The change is small and mechanical: the field becomes a `Runnable` **handle** captured from
`Fonts.observeChanges(this::resolutionChanged)` at the same point in the constructor, and `close()`
runs the handle instead of calling a remover. It is strictly less bookkeeping than the store does
today, and it is the shape §1.9 gives every other subscriber. The bridge itself still waits on the
accessibility record; what does not wait is this. §3.6's table counts four for this tree and not
five, because the fifth name match is that field declaration and not a call.

**`site/` — the published documentation, which an enumeration built out of source trees does not
see.** It is Astro, published to GitHub Pages, and a *consumer* of this repository that Gradle does not know
exists (`site/README.md`), which is exactly how it stayed invisible to an enumeration built out of
source trees. Two hand-written guides carry registration call sites —
`site/src/guides/forms.md` and `site/src/guides/lists-and-scrolling.md` — and they are the sources:
`site/src/content/docs/docs/` is generated from them by `scripts/sync-docs.mjs`, so the guides are
edited and the collection follows. `src/generated/snippets.json` and `gallery.json` are generated
from `limn-demo` and follow it, so they need nothing beyond a regeneration once the demo compiles.
The ten `README` files — `README.md` and its nine translations — each carry one `onAction` snippet
(`README.md:160`), which does not change
shape under this record but is the first Limn code a stranger reads and belongs in the table for the
next record that changes it. **Nothing here blocks the implementation and all of it blocks the
release**, and one line shows why: `lists-and-scrolling.md:43` teaches
`list.onSelect(index -> detail.show(people.get(index)))` — a detail pane *reflecting* a selection,
which is precisely the registration §3.4 moves to `observeChanges` in the demo, published as the
recommended way to do it. Its neighbour on the next line, `list.onActivate(index -> open(...))`, is
a handler that performs and stays exactly as written; so is `forms.md:43`'s
`email.onChange(text -> model.setEmail(text))` and `forms.md:108`'s `submit.onAction`. One line in
four, then — which is the same ratio §3.4 finds in the demo, and the same judgement call per line.

### 3.5 The tests

`SelectionContractTest` becomes `NotificationContractTest`, and it is the artefact this record leans
on hardest. It is table-driven over every `Widget` subclass in **`limn.components` and its
subpackages**, **enumerated from the compiled classes rather than listed by hand**, so a component
added without a row fails rather than being quietly absent — the precedent for reading the tree in a
check is `checkArchitecture`, which already scans every source file for forbidden imports.

**The subpackages are not a detail.** `Chart` is `limn.components.chart.Chart`, and §3.1 gives it
three new announcements; an enumeration reading one package would have excluded the one component
this record adds the most to, and would have reported a green build for it. The same widening covers
`limn.components.text`, where `TextEditModel`'s character damage lives.

**And five public types are not `Widget`s, so an enumeration over `Widget` cannot reach them.**
`ButtonGroup` is in the selection family this test drives and is not in the tree, which is exactly the
problem `SelectionContractTest` already solved: it reaches all five members through a private
`Choice` interface — `name`, `selectedIndex`, `setSelectedIndex`, `onSelect` — with one adapter per
widget. `NotificationContractTest` keeps that shape for the family and adds a second, smaller adapter
for the four menu and dialog types, which have handler slots and no watcher channel: for those the
test asserts the handler half only, and asserts that they announce nothing, which is the guarantee of
§2 and would otherwise be untested. So the enumeration is the floor and the adapters are the
exception, and both are visible in one file rather than one being silently absent.

For each widget and each public mutator it asserts **both halves**:

- a setter announces to the watchers exactly once, with the expected aspect and origin, and **does
  not reach the handler**;
- the equivalent gesture, driven through the input dispatch, announces once with `USER` **and**
  reaches the handler, in that order;
- a mutator handed the state it already holds announces nothing and reaches nobody;
- a two-way binding written on the watcher channel settles instead of recursing, and so does a
  binding between two different widgets;
- a second watcher disturbs neither the first nor the handler;
- **no announcement of any kind is reachable from an `onPaint`** — `notifyChange`,
  `announceChange` and `notifyTextEdit` alike — driven by painting every widget in the table with a
  watcher attached and asserting silence, which `MediaControls` fails today (§0.14). The test is the
  half of §1.7's guarantee that names the offender; the other half is the runtime check, and one
  assertion pins that too — a widget that announces from inside a paint throws, driven by a fixture
  that does it on purpose **and with no watcher registered anywhere**, which is the placement
  decision of §1.7 asserted as behaviour rather than left in prose: with the check after the early
  return this assertion passes silently and the guarantee is empty;
- **and the layout case is pinned the other way round**: a `TabbedPane` laid out across its overflow
  boundary is asserted to announce `VISIBLE` and `ENABLED` *from inside the pass*, so §2's
  non-guarantee is a recorded fact rather than a warning, and the day someone decides to hold those
  to the end of the pass (§6.6) the assertion is what tells them they changed it;
- **a subclass reaches its parent's handler.** Every `Widget` subclass in the enumeration is driven
  through its parent's gestures too, so a `handleUserChange` override that forgot `super` fails
  rather than silently deleting the dispatch it inherited (§1.11, obligation 4);
- **`Viewport3D` announces `INVOKED` before it calls `onClick`**, which is the one site where that
  order is hand-written and not inherited (§3.1);
- **`Change.of(Aspect.TEXT, …)` throws**, and every `TEXT` change a component announces is a
  `Change.TextEdit`, so a watcher may narrow on it (§1.4);
- **the compound order is asserted as an order and not as a set.** A radio group entered from a
  click, an arrow key, `group.setSelectedIndex`, `radio.select()` and `clearSelection` records the
  exact sequence a scene watcher receives and asserts leaver `VALUE`, enterer `VALUE`, leaver
  handler, enterer handler, `onSelect` — the ordering that one indivisible `notifyChange` per widget
  could not produce, and the reason §1.4 has two halves;
- **`announceChange` and `runHandler` have exactly one caller between them**, the `ButtonGroup`
  seam, asserted the way `checkArchitecture` asserts a forbidden import: a second caller is a
  component that has taken the ordering out of the base class, which is the failure this record is
  about;
- **every menu path still runs its item.** A click on a menu item, Enter on a highlighted item and a
  matching accelerator each assert the item's `action` ran and, for a check item, that `onToggle`
  received the new value — the assertion that fails the moment `MenuItem.activate()` is read as
  `CODE`, and the one that keeps every menu in the toolkit alive (§1.3);
- **a gesture that crosses a public mutator is still `USER`**, driven for all five of §0.13's paths:
  a segmented-control click, a radio-group arrow key's selection *and* its focus move, a tab strip's
  arrow key focus move, and Enter on a list row — each asserted to reach the handler and to announce
  `USER`, because each of the five is a route that a rule reading method names labels `CODE`;
- **a registration handle cannot be registered.** `Subscription` is not `Runnable`, so the trap of
  §1.7 does not compile; the test that pins it is a compile-fail fixture in the same shape the
  repository already uses for its architecture checks, or, failing that, one assertion per registrar
  that the returned type is `Subscription`.

The four tests that pin the current silence — `SliderTest`'s two, `SpinnerTest`'s and
`TextFieldTest`'s — are rewritten to pin it on the handler and to assert the watcher heard it, so
they now pin both sides instead of one. `SliderTest`'s silent displacement is repaired by making the
per-test listener a watcher, which is the failure this whole record is about, fixed in the place it
actually occurred; had it not been, the new `IllegalStateException` would have failed it on the first
run.

New tests, none of which has an equivalent today: a registration made during a dispatch is not called
by it, and an unregistration made during a dispatch still is; a throwing watcher stops neither the
next watcher nor the handler, and reports exactly one crash under `CrashPhase.OBSERVER`; registration
and delivery off the UI thread throw; **a notification with no watcher and a notification with three
watchers both allocate nothing, asserted with `AllocationProbe` over a synthetic drag and a synthetic
keystroke run**, which is the repository's own instrument and its own least-of-N rule; a text edit
allocates one record per edit and not one per watcher; a scene-level watcher hears a change on an
**unbound** scene; a detached and re-attached widget keeps its watchers; a scene-level watcher hears
a change in an overlay. **`TextEditModel`'s character damage is pinned edit by edit** — a type, a
type over a selection, a Backspace at a grapheme boundary, a paste, a `setText`, an undo and a redo,
each asserted to report the exact offset, removed and inserted the buffer actually moved, and two
edits before a read asserted to compose to a range that covers both — because those seven numbers are
what `Change.TextEdit` carries and a wrong one is a screen reader reading the wrong span aloud. The
press and blur observers gain their first tests at the same time, and so does the weak-listener claim
ADR 002 §5.4 leans on and nothing has ever verified — drop a `Scene`, force a collection, assert
both axis listeners gone and all five axes purged.

### 3.6 The size of it — a survey, and what a survey is for

**This table is a survey and not a work list, and the migration is not listed here in full.**
Reading the source, rather than counting matches, has already turned up in the enumeration alone: a
published tree missing from the table; three entries missing from §3.7's break list; and half a
dozen rows in §3.1 and §1.5 that described a method's shape wrongly — a funnel that does not exist
on two widgets, a verb that calls the setter it was said to sit beside, a package-private method
treated as public, a public method treated as package-private, and a one-line notifier that is three
statements and maintains a cache. Every one of those was found by opening the file. **None of them
could have been found by counting matches, and a further reading should be expected to find more**,
because a grep over method names cannot see a call graph, an access modifier, or what else a method
does on its way to notifying.

So the enumeration is demoted, on purpose, to what it can honestly be:

> **The rule is what this record is exact about. The survey is what it is approximate about, and it
> is here to size the work and to find the seams — not to be implemented row by row.**

What must be exact, and is: the **two channels** and which question each answers (§1.1); the **one
origin rule** and how a component decides it, including the clause for a non-public seam (§1.2,
§1.3); the **ordering** — settling order, owning aspect last, watchers before handler, the compound
(§1.6); the **containment** — per-watcher `try`, remaining watchers run, the handler runs (§1.6);
the **thread, allocation and lifetime** rules (§1.7); and the guarantee and non-guarantee lists of
§2, which are the contract a consumer reads. Those are stated once, they are checkable, and §3.5 is
what checks them.

**§1.5's seam table is on the survey side of that line, and the status block says so.** It is the
list of entry points a reading of the tree found, and three of its rows were wrong about a method's
shape until the file was opened — the two colour widgets' funnels, `MenuItem.activate`'s access
modifier, `ListView.activate`'s. What is exact about §1.5 is the *rule* it applies: one seam per
entry point, taking an `Origin`, entered by every public and every input path, which §1.11 states as
an obligation on a component author rather than as a count. The eleven, the two and the three are
what has been found so far.

**Where each row is actually settled is the implementation, one component at a time, against that
component's own code** — read the file, find every entry point that reaches the state, decide the
origin at each, cut one seam, and add the component's row to `NotificationContractTest`. That is the
unit of work, and it is a unit the table cannot express, because the table's row for a component is a
number and the component's work is a call graph.

The counts, then, under a stated rule — **a call site, not a declaration and not a javadoc
reference** — and read as an upper bound:

| tree | registration call sites | how derived |
|---|---|---|
| `limn-toolkit/src/main` | ~52 | name match, not re-derived |
| `limn-toolkit/src/test` | ~164 | name match, not re-derived |
| `limn-demo/src/main` | 117 | re-derived under the rule (was 118) |
| `limn-theme-editor/src/main` | ~19 | name match, not re-derived |
| `limn-theme-editor/src/test` | ~1 | name match, not re-derived |
| `limn-backend-lwjgl/src` | 4 | re-derived under the rule (was 5) |
| **`site/` — the published documentation** | **~9** | **a tree Gradle does not build; see below** |
| `README.md` and its nine translations | 10 | one `onAction` snippet each; counted, not estimated |
| **total** | **~376** | four rows unverified |

**The `site/` row is the one a source-tree enumeration misses, and it is missed in the direction
that matters most: it is the tree a stranger reads.** `site/README.md` says it plainly — Astro, *published to
GitHub Pages*, a consumer of this repository that Gradle does not know exists — which is exactly why
a Gradle-shaped enumeration walked past it. It carries registration call sites in hand-written prose:
`site/src/guides/forms.md` and `site/src/guides/lists-and-scrolling.md` are the sources, and
`site/src/content/docs/docs/` is generated from them by `scripts/sync-docs.mjs`, so the guides are
what is edited and the collection follows. `site/src/generated/snippets.json` and `gallery.json` are
generated from the demo and follow it. And the ten `README` files each carry
`new Button("Close").onAction(window::requestClose)` (`README.md:160`), which is the first Limn code
a stranger sees and is a handler that does not change — but it is a site, it was uncounted, and the
next thing this record changes might land in it.

**The demo and the backend rows were re-derived and both were wrong the same way**, which is where
the counting rule came from. The demo was 118 because the twelfth non-slot match is
`{@link Scene#setFrontPainter}` in `PointerLayer`'s class comment (`:26`) and the only call is at
`:59`; its 117 is 106 fluent widget slots — the number §3.4 quotes — plus eight `addTicker`s, one
`setFrontPainter` and the `Fonts` add-and-remove pair, none of which is a handler. The backend was 5
because the fifth match is the field declaration
`private final Runnable onFontsChanged = this::resolutionChanged` (`FontStore.java:151`), leaving the
four calls §3.4 names. **The four rows marked *not re-derived* carry the same kind of match and have
not been checked**; nothing in this record turns on their value.

**Known corrections carried elsewhere, listed here so the survey's error rate is visible rather than
implied.** Each is a place where a method's name said one thing and its body did another, or where
the shape of the repository was taken from what Gradle builds; the cheap ones are already fixed in
the rows above and in §3.1, and this list is what a reader should assume more of:

| the reading that is wrong | what the source says | carried into |
|---|---|---|
| `SearchField.clear()` fires "one line away from" the inherited `setText` | it **calls** `setText("")` and then `TextField`'s `protected fireChange()` (`SearchField.java:36-41`) | §0.3, §1.5, §3.1 |
| five widgets "keep the funnel they have" | three do; `ColorPickerButton` has two entry points and `ColorPicker` has none and three notifying sites | §1.5, §3.1 |
| `MenuItem.activate()` is `INVOKED`/`CODE` | package-private (`:237`) with three callers, all input handlers; `CODE` would kill every menu | §1.3, §1.5, §3.1 |
| `ListView.activate()` unmentioned | **public** (`:293`), run by the Enter key (`:724`), the third gesture through a public verb | §0.13, §1.5, §3.1, §3.7 |
| `requestFocus` is a `CODE` path | `TabbedPane` and `ButtonGroup` call the public method straight out of a gesture | §0.13, §1.5, §1.10, §3.1 |
| `fireChange` is deleted on both text widgets | `TextArea.fireChange` also maintains the horizontal-extent cache and clears the line damage (`:2139-2166`) | §0.14, §3.1 |
| a break list is what §3 changed on purpose | it also carries what §3 changed as a side effect: four `final`s, a deleted `protected` method, the public verbs, and the handle type | §3.7 |
| the trees are the ones Gradle builds | the published `site/` tree and the ten READMEs are consumers Gradle does not know about | this section |
| `README.md` has ten translations beside it | it has **nine**; the ten files are `README.md` and those nine, which is ten `onAction` snippets and not eleven (`ls README*.md`) | this section, §3.4 |

Most of those ~376 sites do not move at all: a handler that performs something keeps its name, its
shape and its lambda. What changes across the set is four functional types, one new handle type, two
field names, three renamed registrars, five null policies, and the callbacks that were reflecting
state rather than responding to a gesture — which is where the work is, and which is a reading of
each site rather than a mechanical rewrite. The test tree is the largest single consumer and it is
also where the behaviour is pinned, so it is where the change will be felt first.

### 3.7 What breaks for anyone who did depend on it, and what this would owe at 1.0

Listed rather than assumed away, because §3's preamble now rests on *no known consumer* rather than
on *no possible consumer*, and the two are different claims. **This list has grown at every revision
and was short by at least four entries when the third round read it**, which is worth saying out
loud beside §3.6's demotion: a break list assembled by recalling what §3 changed will always miss
what §3 changed as a side effect. Compiling against `v0.7.0`:

**Source breaks — the compiler says so.**

1. **Four callback types change**: `Consumer<Float>` to `FloatConsumer` on `Slider` and `SplitPane`,
   `Consumer<Double>` to `DoubleConsumer` on `Spinner`, `Consumer<Integer>` to `IntConsumer` on the
   four selection widgets.
2. **Eight methods are removed**: `addChangeListener`/`removeChangeListener` on `Fonts`,
   `ControlSize`, `LayoutDirection` and `I18n`.
3. **Three registrars are renamed**: `PopupMenu.onClose`, `Viewport3D.onDispose` and
   `MediaControls.setOnRefresh` become `observeClose`, `observeDispose` and `observeRefresh`, each
   returning a handle.
4. **Every registration's return type changes from `Runnable` to `Subscription`**
   (`limn.concurrent.Subscription`, §1.4), which reaches
   `Scene.observePresses`, `Scene.observeWindowBlur`, `Scene.observeWindowClosed` and
   `Scene.addShortcutHandler` — four methods that this record otherwise leaves alone, and whose
   handles a consumer may be holding in a `Runnable` field today. It is a break taken deliberately,
   to close the trap of §1.7 for every registrar at once rather than for the new ones only.
5. **Four `Widget` methods become `final`**: `setVisible`, `setEnabled` and both `setTooltip`
   overloads (`Widget.java:485, :506, :603, :608`). Any subclass outside this repository that
   overrides one stops compiling. `setFocusable` is already `final` and is not one of the four.
6. **`TextField.fireChange()` is removed**, a `protected` method whose javadoc invites subclasses to
   call it — *"for subclasses that edit the model directly"* — and which `SearchField.clear()` calls
   inside the toolkit (§0.14). A subclass that drives the model itself and announces through it loses
   the only announcement seam it had.
7. **A second registration on a handler slot throws** where it used to replace (§3.2).

**Behavioural breaks — nothing says so, which is why they are listed separately.**

8. **Every widget that fires a handler from a programmatic selection stops doing so**: `ComboBox`,
   `TabbedPane`, `SegmentedControl`, `ListView`, `RadioButton` and `ButtonGroup`, plus
   `ThemeEditor`'s four writes. This is the whole of §3.1 and is what §4 states precisely.
9. **Four public verbs stop reaching their handler**, which item 8's wording does not cover because
   none of them is a selection: **`ListView.activate()`** — the sharpest of the four, because firing
   `onActivate` is its entire documented contract and after this record it announces
   `INVOKED`/`CODE` and runs nothing — and `Checkbox.toggle()`, `RadioButton.select()` and
   `SearchField.clear()`, each of which announces its aspect at `CODE` and reaches no handler.
10. **`MediaControls` changes when it catches up.** `refresh()` leaves `onPaint` and runs from the
    100 ms poll alone, while the poll arms and stops exactly as it does today, on a paint (§1.7,
    §3.1). So the transport refreshes at the next tick rather than at the paint itself, at most
    100 ms later, and a repaint that no tick follows no longer refreshes it. An injected refresh
    listener rides that tick, which is the heartbeat its javadoc always promised.

At `1.0` this argument expires and the answer changes shape rather than getting more careful: the
callback types and the renames would land behind overloads and `@Deprecated` for one minor line, and
the behavioural half — a handler that stops firing for a programmatic write — cannot be deprecated at
all, so it would have to ride a major version. That is a reason to take this record now, while the
version number is still the whole announcement, and not a reason to soften it.

---

## 4. Cost

**Steady state, nobody watching:** the paint-depth read and a branch, then two reference reads and a
branch, per mutation — and no allocation. The counter read is first and unconditional by decision,
not by accident: §1.7 explains why an enforcement that skipped it here would not be one. One null
field per widget, four bytes, forty kilobytes on a ten-thousand-widget tree. A keystroke, a drag
sample and a frame all allocate exactly what they allocate today.

**Steady state, one watcher:** the fan-out itself is 1.91 ns and no bytes, against the single slot's
1.86 and no bytes; three watchers, 5.84 ns and no bytes. Registration and unregistration copy a
one-to-three element array, at human rate.

**Those figures are the array walk and nothing else, and the delivery path is longer than the array
walk.** §0.2 benchmarked eight fan-out shapes, which is what decided the storage; it did not
benchmark this design's whole `notifyChange`, which on the `USER` path is the paint-depth read of
§1.7, two null checks, two array walks (the widget's and the scene's), a virtual
`handleUserChange` call, a `switch` over an enum and the slot invocation, plus a per-listener `try`
at each visit. Nothing in that list is expensive and
none of it allocates, and the shape decisions above do not depend on the total. But **a number
measured for one thing must not be quoted for another**, so the honest statement is: the storage is
measured, the whole path is not, and §6.10 names the experiment — the same `AllocationProbe` and
least-of-N harness §0.2 used, over `notifyChange` end to end at zero, one and three watchers with and
without a handler, run before the implementation is called done rather than after.

**The one path that allocates:** a text edit, one small record per edit, on a path that already
allocates one input event per keystroke.

**A bridge attached:** one record read and one platform event per change. The honest cost is
structural churn — a list scrolling with a watcher attached announces a `CHILDREN` change per cell
mount and unmount, which at a screenful of cells is a few dozen notifications per frame carrying
interned values and allocating nothing, but doing real work in the bridge. That is named in §6 rather
than hidden, and the coarse alternative — announcing the list's `CHILDREN` once per pass instead of
per cell — is available if it proves to matter.

**Against today:** twenty-four fewer places where a second registration silently discards the first —
§0.1's twenty-seven fluent slots, less the three that become handle-returning lifecycle observers
(`PopupMenu.onClose`, `Viewport3D.onDispose`, `MediaControls.setOnRefresh`), each of the remaining
twenty-four throwing under §3.2's one policy. Twenty-four is the count of displaceable slots;
nineteen would be the count of one assignment idiom, which is a different question.
Eleven guard sites deleted from `limn-theme-editor` and two from `ColorPicker`; four literal pairs
deleted from `limn-demo`; eight `add`/`removeChangeListener` methods replaced by five
`observeChanges`; four callbacks that stop boxing on a hot path; and one exception policy instead of
five.

**With nothing watching anywhere, the cost is what it was, and the behaviour is not.** The cost claim
is the real one and is the first paragraph of this section: a counter read and two reference reads,
two branches, and not a byte. The behaviour claim has to be stated the other way round, because "unchanged
except where §3.1 changes it" is close to vacuous when §3.1 is the point of the record. Precisely:
**every widget that fires a handler from a programmatic write stops doing so** — `ComboBox`,
`TabbedPane`, `SegmentedControl`, `ListView`, `RadioButton` and `ButtonGroup`, which is the entire
selection half of §0.3, plus `ThemeEditor`'s four writes, **plus the four public verbs §3.7 item 9
names**, of which `ListView.activate()` is the one whose whole contract was to fire the handler. In
the other direction the handler channel
gains exactly one thing, a deliberate defect fix named in §3.1: `ColorPicker.onCommit` becomes
reachable from the hex field, the alpha field and the channel spinners, which is the keyboard hole.
Nothing else starts firing a handler that does not fire one today — `ButtonGroup.onSelect` already
reaches an arrow key through `moveSelection`, and §1.5's seam is what stops this record from taking
that away, not something that adds it. Everything else is additive on the *watcher* channel only: the
aspects of §0.12 that nothing could hear.

And the existing suite does not prove that; it asserts the opposite. `SelectionContractTest`'s own
contract is *"a programmatic set fires the listener"*, driven against all five members of the family,
so the change turns it red by design. That is why §3.5 rewrites it rather than running it: the
evidence for this paragraph is `NotificationContractTest` asserting both halves for every widget, and
an appeal to a suite this record replaces would have been no evidence at all.

---

## 5. What this deliberately is not

- **Not a change to the strategy slots**, in either of the two spellings they come in. Eleven are an
  `install`/`uninstall` pair with an identity-checked removal — `Crashes`, `TextRulers`,
  `Images`' decoder, `Sounds`' engine and decoder, `SvgIcon`'s rasterizer, `Fonts`' catalog and
  loader, `Ui`'s runtime, `Graphics3D`'s and `VideoSurfaces`' providers — which is §0.1's eleven, and
  they keep the pair. A handful more are a **single setter** on an object whose owner is the only
  caller: `VideoClock.setMaster`, `VideoFrame`'s `setDownloader`, and the backend window's frame
  callback and content-scale listener. The two kinds are listed apart because `install`/`uninstall`
  is not true of the four setters and did not need to be: what makes them all out of scope is that
  one answer is the right number for a strategy, however it is spelled, and none of them is a
  notification. Nothing here resembles them, which is the reason the
  sink a smaller design would have installed is not built: it would have looked exactly like
  `Crashes.install` and behaved oppositely.
- **Not a change to `Work`.** Its five builder slots describe a job that is started once and
  snapshotted at `start()`, their null values are documented distinct behaviours rather than errors,
  and the builder is not obviously UI-confined. Single-slot is right there for a different reason
  than §1.1's, and nothing about it is touched.
- **Not a merge of `Dialog`'s result into this channel.** It returns a value, on a
  `CompletableFuture`, which already admits more than one subscriber.
- **Not a property object per attribute.** JavaFX's `slider.valueProperty()` allocates on first call
  and costs an object per observable attribute; this toolkit asserts per-frame allocation. The aspect
  names the property and the accessor answers it.
- **Not a mutable event object reused per dispatch.** It would carry the old value and a text delta
  for free, and it would come with *do not retain this* — a rule that fails silently, which ADR 006
  finding 5 refused once already.
- **Not handler blocking.** GTK's `g_signal_handler_block` is what a toolkit offers when its
  notifications carry no provenance; `Origin` answers the same question as a value on the
  notification rather than as a mode that can be left switched on.
- **Not asynchronous, coalesced, batched or prioritised.** Delivery is synchronous and in the order
  the changes happened. Coalescing a burst is a watcher's business, and any batching a bridge wants
  must not be implemented by holding the UI thread, because a long non-pumping operation is an
  accessibility outage on Windows and not merely a dropped frame.
- **Not weak.** No watcher is held weakly anywhere in this design, and §1.7 says why none needs to
  be. Weakness stays where it was always the answer to a different problem: the process-wide axes,
  where a scene replaced on a live window never receives `windowClosed` — and §1.9 says how that
  listener's self-purge survives the move to handles, which is by holding its own.
- **Not replay.** §1.8.
- **Not per-widget geometry.** §1.10.
- **Not text ranges.** `Change.TextEdit` carries one edit's offset and lengths, which is what a
  platform text-changed event carries. Ranges, attributed runs and the reading of a document by line
  or word are the accessibility record's subject. **Who owns the delta is not deferred any more**: it
  is `TextEditModel`, by §1.4, because `notifyTextEdit` is unimplementable otherwise and because the
  model is the only thing that has the numbers. Leaving the owner to ADR 039 while depending on the
  answer here would be a gap and not a deferral.
- **Not the accessor gap.** Four widgets cannot report their own label text, `setInheritanceHost` has
  no getter, `Scene.topOverlay()` is package-private, and `hitTest` refuses disabled subtrees so it
  cannot serve as *element at point*. Every one blocks a bridge before notification is reached, and
  every one is ADR 039's.
- **Not a public way to claim a user origin.** §1.5, and its cost is §6.2.
- **Not a lifting of the palette restriction.** `Theme` gains a notification; a palette still carries
  colours and corner radii and nothing any widget measures from, and §1.9 says what lifting it would
  cost.
- **Not a rewrite of the nine internal back-channels.** `ButtonGroup` to `RadioButton`, `ScrollView`
  to `ScrollBar` in its four copies, `Chart` to `ChartSeries`, `ComboBox` to its popup panel and the
  rest are private couplings between classes that own both sides; they may move onto this channel
  later or stay. What this record does require is the invariant nothing was checking: **the toolkit
  never takes a handler slot on a widget it hands out.** There is exactly one place where that was
  ambiguous, and §3.1 settles it.

---

## 6. Open items and known costs

1. **Five public types are not `Widget`s, so they announce nothing — and one of the five costs
   nothing.** `ButtonGroup`, `Menu`, `MenuItem`, `PopupMenu` and `Dialog` are plain classes with no
   box, no parent and no scene (§0.14), and a watcher-channel source is typed `Widget` — as a
   parameter of `ChangeObserver.changed`, not as a field of `Change`, which carries only an aspect
   and an origin so that fifty-one of its values can be interned. **`ButtonGroup` is fully covered
   anyway**: §1.5 routes a group's whole change through the two `RadioButton`s, which are widgets and
   do have nodes, so a bridge builds a `RADIO_GROUP` node from its members and never needs the group
   object — which is what it would have to do regardless, since a group that is in no tree can hold
   no node. **The other four are a real hole.** A menu's highlighted item, a check item's state and a
   popup's open state reach nothing; what does reach the channel is coarse, since a popup's surface
   *is* a widget in an overlay or in its own scene, so opening and closing one announces `CHILDREN`
   on the host scene's overlay root, and `MenuBar` announces `EXPANDED` for itself. Closing it means
   either giving those four widget-backed representation or giving the channel a second kind of
   source. **ADR 039 §7.1 closes it for the tree and not for this channel**: a node there is either
   a widget or a synthetic child declared by a widget, so a menu surface declares one node per column
   and one per row and reads the model from inside its own package, none of the four becomes a
   `Widget`, and a menu's highlighted row and a check item's state are published and diffed like
   everything else. The hole named here is a hole in this channel; it costs a watcher that is not
   that bridge.

   **What is not open is how those four reach their own handlers, and it is worth saying because
   announcing nothing is not the same as doing nothing.** `MenuItem.activate()` is the only path to
   a menu item's `action` and `onToggle`, and its three callers are all input handlers. Read as an
   `INVOKED`/`CODE` example, with no seam-table row to say otherwise, it would leave every menu in
   the toolkit clicking and doing nothing under §1.2, with no compiler error and no failing test.
   It takes an `Origin` instead, and its three callers pass `USER` (§1.3, §1.5, §3.1), and §3.5
   asserts that a click, an Enter and an accelerator each still run the item. What these four cannot
   be *read* from is this channel; whether they still work is not in question.
2. **An assistive technology cannot set a value as the user.** §1.5. ADR 039 §1.9 decides the
   action set — twelve parameterless verbs plus four parameterised ones, each dispatched so that the
   widget performs its own action and therefore enters its own from-user funnel — so the answer
   exists in design and waits on that record's implementation. Until then, an assistive technology
   that drags a slider or types into a field reaches the handler through synthesised input, and one
   that *sets* a value through a platform pattern reaches the watchers only, so an application whose
   response to a slider lives in `onChange` does not run. The
   alternative — an `Origin`-taking public overload on every value mutator — was refused here because
   it invites an application to assert something the toolkit cannot check, and because the shape of
   `increment`, `decrement` and `setValueAs` is decided by what a platform action set needs and not
   by this record. When those verbs land they are `USER` and they reach the handler with no further
   decision required.
3. **`NAME` cannot be announced by four widgets** until `Checkbox`, `RadioButton`, `SegmentedControl`
   and `TabbedPane` gain the `text()` and `textSource()` pair `Button` and `Label` already have,
   because §1.4 forbids announcing an aspect no accessor answers. ADR 039 §8 supplies all four —
   `text()`/`textSource()` on `Checkbox` and `RadioButton`, an `I18nString` segment list with
   `segmentSource(int)` on `SegmentedControl`, and `tabTitleSource(int)` on `TabbedPane` — because
   their provenance is what a platform name attribute needs; but the dependency runs one way only.
   **This record does not wait on them**: it lands with those four announcing no `NAME`, which is
   exactly what they do today, and the day the accessors exist the announcement is
   four lines and a row in §3.5's table. If the accessibility record never lands, the gap is four
   widgets whose label a watcher cannot follow — a permanent limit on what a bridge could later read,
   and not a defect in the channel.
4. **Something must enumerate scenes for a bridge, and say when one is replaced.** A combo box's
   popup, a popup menu's surface and a modal dialog each live in their own scene, so one registration
   per scene means someone has to find them. The repository keeps a process-wide weak registry of
   every live scene, but it yields `FrameMetrics` rather than `Scene`, so it cannot serve. **The
   second half is the harder one**: a scene can be replaced on a live window — the fact
   `Scene`'s weak metrics listener exists for, and which this record cites in §1.9 and §5 — and
   nothing announces that, so a bridge holding a handle to the outgoing scene would hear nothing from
   the tree that replaced it and would go on holding a tree nobody shows. §1.10 makes the
   re-registration the bridge's obligation and this item is where the toolkit has to give it
   something to hang that on. **ADR 039 answers both halves for its own bridge and neither for this
   channel**: there is no enumeration to build, because a window hands its bridge to its scene
   rather than a third party finding scenes to subscribe to, and the replacement is handled by an
   `attach` that raises a window-closed for the outgoing tree and a window-opened for the incoming
   one (039 §5.3, §9.2). What is left here is the general case: anything that wants to follow a
   window's scenes without being handed them by the window has nothing to ask.
5. **Structural churn while scrolling**, per §4.
6. **Notifications during a layout pass, which are not only `CHILDREN`.** `ListView` mounts and
   unmounts cells from inside `onLayout`, so a subscribed bridge receives child notifications
   mid-pass, and a watcher that mutated the tree from one would corrupt the pass. **The state
   aspects reach it too, and not only `CHILDREN`**: `TabbedPane.onLayout` calls `setVisible` on three
   overflow buttons and `setEnabled` on two chevrons (`TabbedPane.java:512-516`) under a comment
   reading *guarded setters, safe inside layout*, and `ListView`, `ScrollView` and `ComboBox` each
   refresh a scroll bar from inside their own `onLayout`. So a resize drag that crosses the overflow
   boundary emits `VISIBLE` and `ENABLED` from inside the pass, and the source comment's word *safe*
   is a claim about a toolkit in which those setters told nobody. The guards keep the volume low —
   nothing is announced on a pass that changes no flag — and no announcement is lost, so this is a
   question of when a bridge hears rather than whether. Whether these and the `CHILDREN` case should
   instead be held to the end of the pass is the first thing a bridge will find out, and the answer
   is a measurement rather than a guess. What this record does decide is that they carry
   the origin §1.3's rule gives them, and the two halves differ. `ListView`'s cell mounting is
   `CHILDREN`/`ADJUSTMENT`, because the list announces it from its own funnel and the children moved
   as a consequence of a layout rather than because a caller named them. `TabbedPane`'s five setters
   are `VISIBLE`/`CODE` and `ENABLED`/`CODE`, because the aspect is the one each call's contract
   names, which is what §1.3 says decides it — and because `setVisible` and `setEnabled` are
   `Widget`'s, so a parent adjusting a private child during a pass has no way to say *I moved this
   as a consequence* without a package-private origin-taking overload this record does not add. That
   is a real limit of the rule, stated here rather than papered over: the origin is honest about who
   wrote the flag and silent about why.
7. **The media pipeline still has no notifications of its own.** `MediaControls.observeRefresh`
   exists because `MediaPlayer`'s `state()`, `isEnded()`, `failure()`, `positionMicros()` and
   `underruns()` are all poll-only, which is why the toolkit polls itself at 100 ms. When the player
   gains notifications the heartbeat goes, and a bridge gets a media live region without a poll.
8. **`Spinner` has no commit**, though it has a press-and-hold repeat and a typed commit and both
   `Slider` and `ColorPicker` have one. Named, not fixed here.
9. **`CODE` versus `ADJUSTMENT` is pinned by a test and not by the compiler.** A component author who
   labels one as the other produces a notification that is correct in aspect and wrong in provenance,
   which a bridge and an echo filter both act on. §3.5's contract test asserts the exact pair for
   every public mutator in `limn.components` **and its subpackages**, which makes the mistake a
   failing build inside this repository; it cannot make it impossible for a component written outside
   one. §1.3's compound clause is the one part of the labelling that is a judgement rather than a
   derivation, and it applies to exactly one compound — a radio group — which is small enough to
   carry and is named rather than left implicit. §1.3's other clause — *a non-public seam has no
   `CODE` reading to default to* — is a derivation and not a judgement, but it is the one a reader
   skims past: `MenuItem.activate()` reads as `CODE` on the strength of its name even to a reader
   who has the clause that forbids it in front of them, because the clause is about the entry point
   and the name is about the method.
10. **The delivery path has not been benchmarked end to end.** §0.2 measured eight fan-out shapes and
    that is what chose the storage; it did not measure this design's `notifyChange`, which adds a
    second array walk for the scene, the paint-depth read of §1.7, a virtual call, a `switch` and
    the slot invocation on the `USER` path. Nothing in §1 depends on the total, and §4 quotes the fan-out figures for the fan-out and
    for nothing else. **The experiment**:
    the same harness as §0.2 — Zulu 21.0.12 on aarch64, `-XX:+UseSerialGC`, four listener classes so
    the site is megamorphic, allocation from `AllocationProbe`'s per-thread counter, least of
    6 × 100 000, escape analysis off — over `notifyChange` at zero, one and three watchers, with and
    without a handler, and over `notifyTextEdit` on a synthetic keystroke run. Run before the
    implementation is called done, and this record's §4 updated with what it says.
11. **The `super` obligation on `handleUserChange` is a test, not a type.** §1.11's fourth obligation
    is the one link in the ordering a subclass can break, and Java gives no way to make an override
    mandatory-chaining. `SearchField` and `PasswordField` are the two live subclasses and neither
    needs an override today, so nothing is on the edge now; §3.5 drives every subclass through its
    parent's gestures so a future one that forgets fails the build. Making it impossible would mean
    a final `handleUserChange` dispatching to a per-class registry, which costs a map lookup on every
    `USER` change to remove a mistake that one assertion catches.
12. **Composed character damage is specified and unexercised.** §1.4's covering-range rule for two
    edits landing before a consumer reads cannot arise on `TextField`'s or `TextArea`'s own paths,
    where `fireIfChanged` brackets exactly one model call; it exists because `TextArea`'s javadoc
    says the model may be mutated directly. §3.5 pins the composition, but nothing in the repository
    produces it, so the rule is asserted rather than exercised by real use. If a widget is later
    written that batches edits, the covering range is what its watchers will get, and whether that is
    coarse enough to matter is a question for that widget.
13. **Nothing reports that a widget became shown, and the toolkit's answer is a repaint.**
    `Widget` has `onAttached` and `onDetached` and a `final`, poll-only `isShowing()`. A widget's own
    visibility is one flag; *showing* is that flag over the whole ancestor chain, **plus attachment,
    plus the widget's laid-out box surviving intersection with every clipping ancestor's**
    (`Widget.java:448-478`) — so a widget also stops and starts showing by being **scrolled**, with no
    flag written anywhere and nothing to hook. What stands in for the hook is the re-arm-on-paint
    pattern `Widget.isShowing()`'s javadoc names: the repaint that reveals a widget restarts whatever
    that widget paused. `MediaControls` keeps that pattern under §1.7 and needs nothing here, at the
    price of one poll interval of latency; what the pattern cannot serve is a consumer that must act
    on becoming shown *without* painting, and this record adds none.

    **This record's channel is part of a fix and not most of it.** Under §3.1 an ancestor's
    `setVisible` announces `VISIBLE`, attachment shows up as
    `CHILDREN`, and a scroll announces `VALUE` on the scroll view — all on the scene channel — so the
    three ways of becoming shown are all *reported*. But recomputing the answer from them is not an
    ancestor walk: the clip half is geometry, and §2 says a watcher reads the geometry of the last
    pass, so a widget recomputing `isShowing()` from a scroll notification reads offsets that the
    pass those offsets asked for has not yet applied. The honest statement is that the channel makes
    a becoming-shown notification **buildable** — the events all exist — and that the thing that would
    actually answer it is a hook in the layout pass, where the boxes are final, rather than a watcher.
    That would be a second thing the pass emits beside the single root `LAYOUT` of §1.10, and it is
    not designed here.
    The measurement that would decide whether it is worth building is how often `VISIBLE`, `CHILDREN`
    and a scroll `VALUE` land on a scene during ordinary use, and nothing has counted that.
14. **The survey of §3.6 is approximate and four of its eight rows have never been re-derived.**
    That is not a defect to fix before this record is accepted — §3.6 says what the survey is for and
    what it is not — but it is a standing liability and it belongs on this list rather than only in
    the section that admits it. Reading the source has already turned up, in the enumeration alone,
    a published tree missing from the table, four break entries missing from §3.7, and eight rows
    describing a method's shape wrongly; the shape errors are carried into §3.1 and §1.5 and
    tabulated at the end of §3.6, and **the honest expectation is that a further reading finds
    more**. What bounds the
    damage is that no decision in §1 or §2 rests on a count, and that `NotificationContractTest`
    enumerates the components from the compiled classes rather than from this table (§3.5), so a
    component this record failed to mention still fails the build if it announces wrongly. What the
    table cannot do is find a *call graph* — every one of the eight shape errors was a method whose
    name said one thing and whose body did another — and that is why §3.6 puts the settling of each
    row in the implementation, against the component's own code.
15. **Two components reach the focus funnel through a public method from a gesture, and the seam
    §1.5 cuts for them is the least-exercised part of this record.** `TabbedPane.applyFocus` and
    `ButtonGroup.moveSelection` both call the public `final Widget.requestFocus()`, so the origin has
    to travel by a package-private sibling into `Scene`'s funnel (§1.5's last row). That is the only
    seam in the set that crosses a package boundary in the toolkit's own code rather than staying
    inside one component, and it is the one whose shape is most likely to change when it is written.
    What is decided here is the requirement — a keyboard-driven focus move is `FOCUS`/`USER` and the
    public `requestFocus()` is `FOCUS`/`CODE` — and not the exact spelling of the sibling.
16. **A subtree's locale moves every name under it, and the channel says only `LAYOUT`.**
    `Widget.setLocale` (ADR 035) changes the language every descendant's `I18nString`s resolve in,
    so a watcher's cached names, descriptions and formatted values all go stale together — and the
    only thing this channel emits is `markNeedsLayout`'s eventual single root `LAYOUT`, which says
    *re-read the bounds* and nothing about text. `setInheritanceHost` has the same reach, since the
    host link carries the locale beside the two measurement axes. Announcing a `NAME` per descendant
    from one setter is O(subtree) work bolted onto a call that is already O(subtree), and it would
    announce names for the four widgets §6.3 says have no accessor to answer them. The process-wide
    case is already served — `I18n` is one of the five axes of §1.9 and anything may watch it — so
    what is missing is the subtree case, and the answer is either a second coarse marker beside
    `LAYOUT` or a re-read triggered the way §1.10 has a consumer re-walk when a scene is replaced.
    **It costs the screen-reader bridge nothing**, because ADR 039 does not subscribe to this
    channel: its snapshot carries the locale epoch and `I18n.epoch()` each name resolved under, and
    a move in either re-resolves every name in the tree (039 §1.7). Like §6.1, this is a hole in
    this channel and not in that tree, and what it costs is a two-way binding, an inspector or an
    application watcher that follows a widget's text.
