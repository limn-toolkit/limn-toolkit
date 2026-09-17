# Accessibility

Background for `limn.accessibility` and the three platform bridges: what a screen reader is
actually handed, what a contributor writes when adding a widget, and the traps — most of which a
green test suite cannot see, because they are about what a platform does with what it is given.

The decision, its alternatives and its per-platform interface tables are
[ADR 039](../adr/039-an-accessible-tree-is-a-snapshot-and-the-platform-reads-it-on-its-own-thread.md);
this note is what you want before changing any of it. What an *application* does with the API is
Javadoc, on the members.

## The one idea

**An accessible tree is an immutable whole-window snapshot.** The user-interface thread walks the
scene after layout and produces one; a bridge holds it in a `volatile` field; the platform reads it
from whatever thread it likes, and never touches a widget. Every per-node event is the *difference*
between two snapshots, computed by the same walk that produced the second one, so nothing has to
remember to raise anything.

That single choice is what makes three very different platforms tractable. Windows calls a provider
from several threads at once while the user-interface thread sleeps; Linux never calls at all and is
answered over D-Bus by a reader thread; on macOS the platform's thread *is* the user-interface
thread. A snapshot is safe to read from all three positions, and nothing else is.

The corollary is the rule that surprises people: **a bridge never asks a widget a question.** If a
reader needs to know something, it has to already be in the snapshot.

## Describing a widget

Six `protected` hooks on `Widget`, and a widget usually overrides one or two:

| Hook | Answers |
| --- | --- |
| `onAccessibility(Accessibility a)` | what *this* widget is: role, name, states, facets |
| `onAccessibilityChildIdentity(Widget child, Accessibility a)` | *who* a child is, before it describes itself: the key a pooling container owns, and the synthetic row a table hangs a widget cell under |
| `onAccessibilityChild(Widget child, Accessibility a)` | what a *child* is, when the parent knows better than the child does — and, through `Accessibility#delegate`, which of the child's verbs are the parent's to perform |
| `onAccessibilityAction(action, arg)` | performing a verb this widget declared |
| `onAccessibilityChildAction(child, key, action, arg)` | performing a verb this widget *delegated* onto a child: a list's `SELECT` on a row that is the application's own cell, published on the row where a reader addresses it and routed here with the key the list gave that row |
| `performSyntheticAction(key, action, arg)` | performing a verb on something the widget *paints* rather than parents |

And one answer hook beside them, `accessibleLabelTarget()`: a composite whose keyboard lands on an
inner control — a date picker — answers that control, and a `Label` bound to the composite names the
control a reader arrives at rather than a group it never lands on (decision 55).

`Accessibility` is a reusable builder, filled in and read by the walk; it is not a node you keep.
Everything a widget says is declared — a role, a name, a `LABELLED_BY` or `DESCRIBED_BY` relation — and **nothing is
inferred from geometry**. A label drawn next to a field does not become that field's name because it
is nearby; that is how a reader ends up confidently saying the wrong thing.

**Behaviour is carried by typed facets**, not by the role. `ToggleFacet`, `ValueFacet`, `TextFacet`,
`SelectionFacet`, `SelectionItemFacet`, `ScrollFacet`, `ExpandFacet`, `ActionFacet`, `WindowFacet`,
since ADR 041 `TableFacet` and `CellFacet`, and since ADR 039's amendment of 2026-09-14
`HierarchyFacet` (a tree row's level and flat row index). The three platform
vocabularies disagree about where behaviour lives — a *pattern* on Windows, a state bit plus an
action row on Linux, an attribute on macOS — so each bridge derives its own view from the same
facet. A widget that publishes a `ToggleFacet` gets `IToggleProvider`, `AXValue` and the AT-SPI
`CHECKED` state without knowing that any of them exist.

**A facet's *presence* is a fact of its own, and two states are derived from it.** A node carrying an
`ExpandFacet` is `EXPANDABLE` whether it is open or closed, and a node carrying a
`SelectionItemFacet` is `SELECTABLE` whether it is selected or not (the second since 2026-09-16, when
three live readers found that nothing in either module had ever published it: Orca discards an
unnamed row that is "not focusable, selectable, or expandable" as layout-only, so a select-all over
five selected rows said nothing at all). Neither bit is a widget's to set — `Accessibility#state`
refuses both and names the facet that owns them — and neither costs a bridge anything: Linux reads
them as state bits, Windows as patterns, macOS as attributes or as a settable value.

**Two free verbs come from the walk, not from the widget.** Every focusable node advertises `FOCUS`
and `SCROLL_INTO_VIEW`, and the scene performs them itself through `requestFocus()` and
`revealInView()`, *instead of* asking the widget's hook rather than after it. Do not implement them
in a hook: the walk is their authority, and a hook is never asked about them — a container may not
delegate either onto a focusable child for the same reason. And **no hook's answer reaches the
platform**: a bridge accepts or refuses a verb synchronously from the published snapshot, then
posts, and the `false` a hook answers later is bookkeeping nobody hears. So the only refusal a
reader can see is the published one — a node accepts exactly the parameterless verbs it publishes
plus the setters its writable facets imply while it is enabled and visible
(`AccessibleNode#accepts`), and a widget that accepts a synonym publishes it. On a node the scene
will not operate the walk withdraws every verb for you, and there are **three** reasons it will not,
all read the same way: the widget or an ancestor is disabled (a synthetic child you declared
`disabled()` included), the node is outside the layer that owns input (beneath an overlay of the
scene, or in a window a native modal blocks), or **nobody can see it** — its own visible flag or an
ancestor's is false, which is what an unselected tab's contents and a collapsed panel are. Setters go
the same way, while the value and the text keep the writability they really have. Never publish a
disabled field read-only: disabled and read-only are different facts.

**Scrolled away is not hidden.** The third reason is visibility and never "has pixels on the glass":
a control clipped out of a scroll viewport keeps every verb and every setter, and the scene reveals
it and then performs, the way the two free verbs have always worked. Publish what your control
offers and let the scene decide where it is; a widget that withheld a verb because it was scrolled
out of view would be hiding a control the reader can reach.

Do not gate a verb on whether your widget is enabled, on whether it is on screen, or on where your
popup is drawn; gate it on the rest of your own state. The one exception is a popup's contents: an overlay
of the scene and a window of its own are walked on their own chain, enabled whatever the control
that opened them, exactly as the keyboard reaches them, so a popup whose contents must go inert with
its opener guards on the opener's own flag and says so, narrowing its rows with `disabled()` as the
combo's list does, or closes.

**Transparent and ignored are different, and getting them the wrong way round deletes controls.**
Transparent is no node with the children **hoisted into the parent's place**; ignored is no node
**and no children**. Transparency is not an opt-in but a predicate — a widget whose role is `GROUP`,
whose name and description are empty, which offers no action and declares no state beyond the
defaults is removed — which is what deletes every scaffold box in the toolkit without a line of
accessibility code in any of them. It is evaluated on the widget's **own declared facts** and never
on the bits inherited down the walk, or disabling one form would materialise a node for every box
inside it. Ignored is for a spacer or a decorative rule, and **a control that can be operated is
never ignored**.

The predicate has a companion trap: a widget that paints its own content and declares nothing is
exactly the shape the predicate deletes, so the interface it drew is simply absent with nothing
said. Before deleting, the walk asks whether the class declares `onPaint`, and warns once per class
naming the three one-line fixes. A class whose drawing really is decoration says so with
`paintsDecoration()` — which changes nothing about the tree and only silences the warning. It is not
an escape hatch for a widget that paints information; that widget gets a name or a role, and there
is no third answer.

**Synthetic children** are for things a widget paints instead of parenting — a chart's series, a
segmented control's segments. They get identity from an owner-chosen key, and their verbs go to
`performSyntheticAction`.

**A name is an `I18nString`, resolved under the subtree's locale, and it carries its provenance.**
Where the name came from is part of the node, because macOS maps `accessibilityTitle` and
`accessibilityLabel` to different attributes and a bridge that guesses is silently wrong on exactly
one platform.

### Adding a widget: the pipeline, not the table

ADR 039 §7 is a *survey*, not a specification. Its row for your widget is a starting point and the
widget's own source is the authority. The order that works:

1. **Map.** Read the paint, the hit test, the geometry and the state, and write the hooks against
   what is there — correcting §7's row wherever the two disagree.
2. **Test headlessly.** Every focusable node named, no `UNKNOWN` role, bounds inside the clipping
   ancestor, ids stable across the mutations the widget performs on its own children, and a live
   mutation case per public setter. A widget that starts describing itself differently also changes
   a golden transcript in `limn-demo` (`AccessibleTranscriptTest`), and that change is read aloud
   before it is accepted — the transcripts are the only reference answer for what the demo should
   sound like.
3. **Verify adversarially.** Someone reads the produced tree against the widget's code looking for
   the row that is still wrong: the operable control with no node, the box that is not where it is
   painted, the name that resolves to nothing. §7.2 is the standing list of what that has found.

`AccessibleCoverageTest` holds the gate: every widget is either described or named on the list of
what is left, so adding one and describing nothing fails the build. The list is not a place to park
a widget you did not want to do — it is there so that what is left is *counted*, and the phase that
emptied it is finished.

## Where the reader's cursor is

A window has one focused node, and inside a list, a table, a tree, a menu or a date field the user
moves a *cursor* the platform focus never follows. The model publishes both and resolves them into
one answer: `AccessibleTree#activeDescendant()` is the first `ACTIVE` node strictly below
`focused()` in reading order, and `effectiveFocus()` is that descendant or, where there is none, the
focused node itself. `ACTIVE` is published only while the owning widget holds the keyboard, so an
unfocused list cannot claim the cursor, and exactly one `ACTIVE_DESCENDANT_CHANGED` per publish goes
out, on the focused node, when its resolved descendant moves.

**Each platform then does the native thing with it, and they do not agree**, which is why the model
answers the question rather than the bridges:

| | What the platform focus is | What the reader is told |
| --- | --- | --- |
| **Windows** | the item | `GetFocus` and `HasKeyboardFocus` answer `effectiveFocus()`, and `AutomationFocusChanged` is raised on it — NVDA reads `HasKeyboardFocus` live at that moment, so the tree must already say so |
| **macOS** | the item | `accessibilityFocusedUIElement` and `isAccessibilityFocused` answer it; `FocusedUIElementChanged` is posted at application level |
| **Linux** | the widget | `focused` stays on `focused()`; the item travels as `ActiveDescendantChanged` carrying its `(so)` reference |

**On macOS the reader also writes focus back, so a row is not focus-settable** (measured 2026-09-16,
ADR 039 §2.2's amendment of that date). VoiceOver keeps its cursor and the keyboard focus in step: a
moment after the application moves its own cursor, VoiceOver writes `AXFocused` on the row its cursor
is still on. A native `NSOutlineView` row carries no `AXFocused` at all — it answers
`kAXErrorAttributeUnsupported` for the value and for its settability, and a native `NSTableView`
row's attribute names carry `AXSelected` and no `AXFocused` — so the bridge refuses
`setAccessibilityFocused:` on a row and leaves it to the container, as AppKit does. Rows are
*selected*, and a reader moves the cursor by writing `AXSelected` on the row or `AXSelectedRows` on
the container, which post `SELECT`. The *getter* still answers on a row, because
`accessibilityFocusedUIElement` names the cursor row and a client that walks there must get a
truthful answer. Nothing changes for Windows or Linux, where the reader does not write focus back.

**And on macOS a client is now told the truth before it writes** (2026-09-16, ADR 039 §2.2). Refusing
a setter at the gate stops the write, but until this round it did not stop AppKit *advertising* the
attribute as settable: `AXUIElementIsAttributeSettable` discards the gate's refusal for any setter the
element's class implements, so every element read as settable for all six — a leaf row's
`AXDisclosing` included, where a native outline row reports `false`. AppKit's fall-back after that
refusal is the legacy `accessibilityIsAttributeSettable:`, which the bridge now answers from the same
gate, so what a client is told and what the bridge will do are one answer. It needed no extra
Objective-C classes: one class answers per element, exactly as a native `NSOutlineView` reports a leaf
and a branch differently from its one row class.

Two consequences a widget author feels. A focused list, table or tree keeps its cursor row realized
even when a scroll takes it off screen, so the descendant never resolves to nothing; and where the
cursor is inside a native popup window, it is resolved across the opener's relation into that
window's tree, which is why node ids are process-wide.

## Identity

Ids are minted over the **widget tree**, never over the published tree, and survive frames. A node
that keeps its id across a mutation is a node a reader keeps its cursor on; a node that gets a new
one is a node the reader treats as newly created, which sounds like the interface flickering. This
is why a list that recycles rows must key its rows by their *content*, and why the identity tests
mutate a tree and assert what did **not** change. It is also why a list that recycles must not
recycle the row the keyboard is in: a reader whose cursor follows the focus is standing on that
node, and a scroll that deletes it drops the reader to the window.

The rule is easy to state and was not followed everywhere. `CalendarView` keyed its day cells,
week rows and week-number cells by their **position in the grid** until 2026-09-16, so paging a
month renamed forty-two nodes rather than retiring and minting them — and that is precisely the
"newly created" case above, read in reverse: a client told that a node it holds is now called
something else believes the *thing* changed its name. NVDA 2024.4.2, subscribed to the focused
element's name, spoke the incoming month's date before the focus had moved, in 6 of 7 paging
events; Orca read a row mid-burst of 42 renames and said "1 de outubro" beside "4 de setembro". A
day cell is keyed by its epoch day now. **The lesson is the ordering:** a synthetic child's key is
as much an identity as a widget's, and "what it shows" is the only thing safe to key it by.

## How a frame publishes

The publish is a step in the frame, run after layout and the hover update and before the paint
passes — bounds are settled there, and every change that matters already buys a frame, so no new
scheduling was invented.

Two dirty flags, and they mean different things: the **node** flag says the tree's shape or contents
may have changed and buys a walk; the **header** flag says only the window's stamp moved and buys a
re-stamp, which is much cheaper and is what a window drag produces. Announcements drain *above* both
flags and above the re-present guard, because an announcement is the application speaking rather
than a property of a node — a frame that changes nothing in the tree still says what was queued.

**The step has a last line, and it is a boundary rather than a drain.** `frameEnded()` runs however
the step returned — after a re-present, with nothing listening, with a clean tree, after a re-stamp,
after a publish — and it means "everything this frame had to say has been emitted". All three
bridges use it and none for the same reason: macOS posts its whole frame's notifications there,
Linux flushes a re-announcement owed by a queue collapse, and Windows hands the boundary into its
own queue behind that frame's events so a drain thread cannot outrun the producer. It is never
called from `republishNow()`, which is a reentrant publish and not a frame.

`Scene#announce` is the only way anything is spoken that is not a node. There is no live-region
model; a status label that updates silently stays silent until an application adds the call.

**A surface opened in a window of its own runs on its opener's clock.** A combo's list, a popup
menu, a dialog and a date picker's calendar build a scene of their own, and that scene takes the
clock the opener's scene has. It matters because the last frame of such a surface's fade-out is what
destroys its window: under an injected clock a scene of its own advanced by the microseconds the
frames really took while the opener was told seconds had passed, so the fade never ended and the
window never closed — a headless test then walks a popup that is not there any more.

An action from a reader arrives on a platform thread, is checked against the published tree, and is
then **posted to the user-interface thread**, where it re-checks its own preconditions before doing
anything. The tree a reader is holding is always at least one frame old, so "the button you asked me
to press" may no longer be enabled, or there.

## What it costs when nothing is listening

Nothing, by construction, and it is asserted rather than claimed: `AccessibleIdleCostTest` renders a
frame with nothing listening and requires it to allocate nothing and to be byte for byte what it
was — and a frame with a live bridge and a clean tree to allocate nothing either. The gate is per
platform and each one is the most honest question that platform can answer — Linux can ask the
desktop whether assistive technology is running at all; Windows and macOS can only know that
something has asked, which is why both owe a **priming publish**: the gate cannot open before the
platform has been handed something to ask about. A bridge that is not listening allocates nothing
and walks nothing — after the bind, that is: on Windows the bind itself publishes the window's own
node, one node and no walk of the scene, because there a client asks whether the window has
accessibility at all in a message that arrives long before the first frame and is not repeated
(2026-09-16). It starts no thread either, with one deliberate exception: Linux's switch moves while
an application runs, so the process keeps one session connection and one parked thread watching it
(`AtspiStatusWatch`), and a window opened before the screen reader becomes readable when it starts.

## The three bridges

They live in `limn-backend-lwjgl`, under `limn.backend.lwjgl.a11y`, one package each. They were
briefly separate artifacts and were brought back; ADR 039 §10 records why, and the short version is
that the split was paying for a native payload that does not exist. Do not re-propose it without a
bridge that acquires one.

`Bridges.openFor(nativeHandle, applicationName)` picks by platform and answers
`AccessibilityBridge.NONE` for anything it cannot open, so an application gets accessibility without
wiring anything. `setAccessibility` remains for an application installing its own, or refusing.

| | Reaches the platform by | Threads | Gate |
| --- | --- | --- | --- |
| **Windows** / UI Automation | COM through LWJGL's JNI trampoline and libffi; a WndProc subclass answering `WM_GETOBJECT` | the platform's, several at once, while the UI thread sleeps; plus a drain thread of the bridge's own that raises, because a raise waits for the reader's handler | `UiaClientsAreListening()` for its negative; then this window's own: a subscription (`IRawElementProviderAdviseEvents` on the root) or an ask, owed one event |
| **macOS** / NSAccessibility | the Objective-C runtime through LWJGL; a runtime `NSAccessibilityElement` subclass with libffi closures | every callback **is** the UI thread | something has asked; opened by the first-frame walk and the children push, which are the attach and the gate together |
| **Linux** / AT-SPI2 | **no native code at all** — D-Bus over a unix socket, in pure Java | a reader thread that answers and never blocks, a writer thread that performs every write; a short-lived joiner; one parked status thread per process | `org.a11y.Status.IsEnabled` on the session bus, watched through `PropertiesChanged` |

`PlatformBridge` holds the little they genuinely share: the published snapshot and the host. The
event queue, the element registry and the listening gate stayed apart on purpose — each platform has
a different right answer, and a shared one would make a bridge lie.

**Linux is one application per process; the other two are per window.** AT-SPI2 has one application
object per connection, so `AtspiApplication` owns the one connection and every window's
`AtspiBridge` is a facade that registers its tree as a frame beneath it — a native popup included,
which is how its `POPUP_FOR` reaches the field in the other window. The application is named by
`Backend#setApplicationName`, or the first window's title; a window's own title never renames it.

**Relations cross to every platform in that platform's own form.** A node's relations are the
model's, resolved to published nodes before a bridge sees them (a target that was never
published is dropped there, not here). On Linux they are the relation set, one entry per type in
AT-SPI2's numbering (`AtspiRelations`); on Windows the three element-valued properties, a label
as one element and a description or a controlled element as an array, each handed out through
the simple interface the property declares; on macOS the general linked-elements attribute,
because AppKit names no describing element as such and the description's text travels as help
instead (`AxBridge` resolves the targets, `AxElementClass` vends the array). What every reader
speaks is the same either way: the caption as the name and the message as the description are
copied into the node by the walk, so the relations are for a client that wants the element
itself. Each bridge has a relation check under `scripts/a11y/` that reads the demo's form
scene (`--scene form`) through the platform's own client and resolves every target: the
Linux one asks libatspi for the relation set, the Windows one reads LabeledBy through the
managed client and DescribedBy through the core call the managed layer itself uses, since its
public API has no identifier for it, and the macOS one follows the linked elements. All three
resolved the caption and the message on 2026-09-09.

### The constants rule

**Every platform constant is read off the running platform and asserted in a test**, never recalled
and never copied from a header. Each bridge has a `*ConstantsTest`, and `scripts/a11y/*/` has the
dump script that produced what it checks against: control type ids and interface slot orders on
Windows, role and state names on Linux, and on macOS the `dlsym`'d role symbols plus selector type
encodings read with `class_getInstanceMethod` and `method_getTypeEncoding`.

`AxRoles` therefore holds **symbol names** and not strings: `"NSAccessibilityButtonRole"`, resolved
at run time. The one place the rule cannot be honoured is `NSAccessibilityPriority{Low,Medium,High}`
— a C enum, not exported symbols — and those literals carry a comment saying so.

**A fact two servers answer differently is a choice, and the choice is argued in the source.** Some
of what a bridge must decide has no single reading to cite: GTK 3 and GTK 4 disagree about AT-SPI's
paragraph boundary and about whether a property is read-only; both refuse a value the bridge has to
answer something for. A citation is then not available and inventing one is worse than admitting it,
so the comment says the word *choice*, names both servers, names what each answers, and says what
would reverse it. The ratchets read the argument and not just the number:
`AtspiConstantsTest` requires a reading or a marked choice at every constant,
`UiaConstantsTest` requires the citation inside the method that uses it, and
`AxConstantsTest` holds every selector and symbol against the committed dump **and** requires a
`readings/` file in the comment above each fact answered off a guest rather than off that dump —
prose being exactly where a fact that lives in a runtime rather than in an exported symbol has to be
recorded, and exactly what a mechanical dump check is silent about.

### Role phrases

`limn.accessibility.RoleNames` is the toolkit's own word for each role, in every shipped language,
and it exists because platforms disagree about whether they have one. macOS answers
`accessibilityRoleDescription` for **every** role, because AppKit localizes its own phrase against
the *calling process's* bundle and a JVM has none — a Portuguese desktop otherwise hears "checkbox"
and "slider" in English inside VoiceOver's Portuguese. Windows answers with one only for the roles
UI Automation cannot name, and lets the platform speak for the rest, which is what makes a reader
sound like every other application on the machine. Linux answers `GetLocalizedRoleName` with
AT-SPI2's own word and none of ours: Orca localizes it itself.

Three conventions this had to learn, and any new catalogue will meet them again: keys must be
`limn.*`-namespaced or `ShippedTranslationsTest` reports every file as orphans; there is **no English
base `.properties`** — the English lives in source as the per-key fallback; and the domain must be
named in that test's class-loading probe. Two roles may not share a phrase **within one language**,
which only a per-file test catches: Portuguese wants "separador" for both *separator* and *tab*, and
Arabic "قائمة" for both *menu* and *list*.

Phrases resolve under the **node's** locale through `I18n.resolve(key, english, locale)`, not through
a scope: bridges answer on platform threads, and `pushScope` is thread-confined.

## The traps

These are the defects that shipped past green suites. Each is a rule, and each has a test now.

**An event is half a conversation.** A reader is told something changed, and then asks a question of
its own. If nobody answers the question, the event buys nothing and the silence is indistinguishable
from never having posted. Two of the three platforms shipped this defect:

| After a focus event, the reader asks | |
| --- | --- |
| Windows | `GetFocus` on the fragment root |
| Linux | whether any window claims `ACTIVE` — Orca suppressed *every* announcement over a correct tree with correct events |
| macOS | `accessibilityFocusedUIElement` — VoiceOver stood on the first node forever, having been told each time that focus had moved |

So the rule is not "raise the event" but "raise the event and be able to answer what it invites", and
those are two different files written by two different people. Reviewing a bridge, look for the
question before you look for the event.

**A widget with no value is the only test case that works.** Both focus defects above survived
because they are invisible on a widget that has a second thing to announce: the check box appeared
to be followed correctly for four runs because its *value* changed at the same moment its focus did.
The button — with nothing to say but its own name — was the only widget that looked broken. Drive
focus and value **separately**, always, and treat a valueless widget as the good case rather than
the poor one.

**Order matters more than correctness in the platform calls.** On macOS the frames must be applied
*after* the children push, because the push is what mints the elements; the other way round, the
entire tree reaches a client as a zero-sized rectangle at a fixed origin — which walks perfectly and
hit-tests not at all. On Linux the accessibility bus must be joined on the first publish that has a
non-empty tree: at-spi2-core 2.60 reads an application *as* it registers and never adds one that
answers "no children", while 2.52 adds first and reads later, so registering early looked correct on
Ubuntu for every run this bridge ever had.

**Being hidden is an omission, not a refusal.** at-spi2-core 2.60.1 detects "unresponsive"
applications and drops them from the desktop's children, deciding it with a `DBus.Peer.Ping` sent to
`/` — neither the root path nor a node. An unanswered ping produced a perfect conversation, hundreds
of inbound calls, no error anywhere, and no client that could see the application at all. When a
platform goes quiet, ask what it is refusing to say, not what it is complaining about.

**The reentrancy rule is enforced by discipline, because it does not crash.** A publish from inside
an accessibility callback releases nothing, re-pushes nothing and drains nothing. On macOS every
callback is the user-interface thread, which is what lets the element registry be a plain `HashMap`
with a thread assertion — and it is also why reentrancy is the trap. The lab could not make the
violation crash in four different shapes; it is still wrong, and the rule stands on the reasoning
rather than on a symptom.

**Ask the windowing platform, not the operating system.** `Platform.get()` says LINUX for Wayland and
X11 alike, and `glfwGetX11Window` on a Wayland session answers `GLFW_PLATFORM_UNAVAILABLE` rather
than zero. `nativeHandle()` switches on `glfwGetPlatform()`. Wayland has no window handle to give and
answers zero, which is correct: AT-SPI2 is D-Bus and needs none.

**The session flag is not a gate on Windows.** `UiaClientsAreListening()` is true on an ordinary
desktop with no reader — seventeen processes held a UI Automation handler on the guest, Parallels'
tools among them — so a bridge gated on it walks every damaged frame for nobody. The per-window
answer is `IRawElementProviderAdviseEvents` on the root, plus the ask itself. And **never publish
inside `WM_GETOBJECT`**: hand over the tree you have. A publish there raises the difference into the
reader's own call, which is a reentrancy the design refuses everywhere. (The clause that used to
follow — "and NVDA never spoke again" — was withdrawn on 2026-09-16: what silenced NVDA was
*answering that message with no provider at all*, which is what a window with no tree did for the
first 300-500 ms of its life. **So have a tree before the message can arrive**: the bind publishes
the window's own node.)

**A raise is not a fire-and-forget on Windows.** `UiaRaise*` returns after every subscribed
client's handler has run, and NVDA's handler calls back into the provider before it returns —
2.5 ms median, 50 ms once, on whatever thread raised. That is why the Windows bridge has a drain
thread and `emit` only enqueues; anything that raises inline is spending the frame budget inside
the reader.

**Wayland accessibility is fine.** It is the reasonable guess and it is wrong. AT-SPI2 is
window-system independent, and Orca works on GNOME and Plasma 6 under Wayland. What genuinely lacks
AT-SPI2 support are the wlroots compositors. Do not re-derive the theory.

**A refactor can introduce a self-call no test asks about.** Rewriting every read of the snapshot
field into a call to a new accessor turned one read — inside an anonymous `Host` with a `tree()` of
its own — into infinite recursion. Every test passed; the guest died three seconds in. No test had
ever asked the bridge's *own* host anything, which is now a test.

## What only a lab can prove

Green tests prove the model. They cannot prove that a reader speaks, and every defect above was
found by a person listening to one. `scripts/a11y/` holds what makes that repeatable: a rendered
probe per platform with a shared scene, an out-of-process client that walks the tree the way the
platform's own clients do, and the constants dumps.

**Use the platform's own client library, not a hand-rolled one.** The Linux walker goes through
`libatspi`'s typelib precisely so that a tree it can walk is a tree Orca can walk; a bespoke D-Bus
client would have agreed with our bridge about a shape neither of them shares with the reader.

**A rendered probe, not a hand-published tree.** A hand-published tree tests the bridge and nothing
underneath it, and both defects a live reader has ever found here were underneath it. Build the
window early. A probe that reads correctly and shows nothing has been rejected twice, for the same
reason both times: a blank window is impossible to look at and believe, even when it is honest.

**A platform script is versioned; a lab runner is not.** A script that reads a platform and runs on
any machine of that OS is here: the constants dumps, the out-of-process clients, the probes, and the
demo's own `--reader` driver. A runner that brings one guest up — its address, its login, its home
paths, the order a VM is built, copied to, started and photographed in — is a lab note and lives
outside this repository, because a fact about one machine rots without anyone noticing and is
nobody's to re-run. Where a constant's comment needs to say where a number came from, it cites the
guest and its version, never the runner that typed it. `ScriptsCarryNoLabFactsTest` is what keeps
the line: it refuses a private IPv4 literal, a lab login and a `/Users/<name>` path anywhere under
`scripts/`.

## What is deliberately absent

ADR 039 §11 is the full list with the cost of each stated in terms of what a blind user loses. The
ones most likely to be mistaken for bugs: no range-to-rectangle text geometry on any platform (so
character review and braille cursor routing are degraded); no UI Automation `TextPattern`; **a row
carries the verbs its container gives it, and how many that is differs by container** (ADR 039 §11's
"not per-row actuation in a list" was reversed on 2026-09-14). A `Tree` row and a `Table` row each
carry their own by-state set — `SELECT`, `ADD_TO_SELECTION` or `DESELECT`, `EXPAND` or `COLLAPSE`
(tree only), `FOCUS`, `SCROLL_INTO_VIEW` — so a reader addresses a particular row and the container
performs it. A `ListView` row carries `SELECT`, plus `SCROLL_INTO_VIEW` where its cell cannot take
the keyboard, and no `FOCUS`, because there the cursor *is* the selection and a verb that moved one
without the other would be a distinction the widget does not have. Activation stays on the
container in all three: `PRESS` is published where there is a cursor and acts on the cursor row, so
a reader opens the row it is standing on and not an arbitrary one. No data table behind a chart; no
occlusion model, so a scrim hand-rolled
inside a `Stack` is not modal to a reader where `pushOverlay` and `Dialog` are; no MSAA; and the
system accessibility *settings* — high contrast, reduced motion, a system text scale — which are a
different decision with a different shape and would tangle a tree with a theme.
