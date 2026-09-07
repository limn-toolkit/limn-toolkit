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

Four `protected` hooks on `Widget`, and a widget usually overrides one or two:

| Hook | Answers |
| --- | --- |
| `onAccessibility(Accessibility a)` | what *this* widget is: role, name, states, facets |
| `onAccessibilityChild(Widget child, Accessibility a)` | what a *child* is, when the parent knows better than the child does |
| `onAccessibilityAction(action, arg)` | performing a verb this widget declared |
| `performSyntheticAction(key, action, arg)` | performing a verb on something the widget *paints* rather than parents |

`Accessibility` is a reusable builder, filled in and read by the walk; it is not a node you keep.
Everything a widget says is declared — a role, a name, a `LABELLED_BY` relation — and **nothing is
inferred from geometry**. A label drawn next to a field does not become that field's name because it
is nearby; that is how a reader ends up confidently saying the wrong thing.

**Behaviour is carried by typed facets**, not by the role. `ToggleFacet`, `ValueFacet`, `TextFacet`,
`SelectionFacet`, `ScrollFacet`, `ExpandFacet`, `ActionFacet`, `WindowFacet`. The three platform
vocabularies disagree about where behaviour lives — a *pattern* on Windows, a state bit plus an
action row on Linux, an attribute on macOS — so each bridge derives its own view from the same
facet. A widget that publishes a `ToggleFacet` gets `IToggleProvider`, `AXValue` and the AT-SPI
`CHECKED` state without knowing that any of them exist.

**Two free verbs come from the walk, not from the widget.** Every focusable node advertises `FOCUS`
and `SCROLL_INTO_VIEW`, and the scene performs them itself through `requestFocus()` and
`revealInView()`. Do not implement them in a hook: the walk is their authority, and a widget that
answered for a verb it never declared would silently swallow one the tree had already promised.

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
   mutation case per public setter.
3. **Verify adversarially.** Someone reads the produced tree against the widget's code looking for
   the row that is still wrong: the operable control with no node, the box that is not where it is
   painted, the name that resolves to nothing. §7.2 is the standing list of what that has found.

`AccessibleCoverageTest` holds the gate: every widget is either described or named on the list of
what is left, so adding one and describing nothing fails the build. The list is not a place to park
a widget you did not want to do — it is there so that what is left is *counted*, and the phase that
emptied it is finished.

## Identity

Ids are minted over the **widget tree**, never over the published tree, and survive frames. A node
that keeps its id across a mutation is a node a reader keeps its cursor on; a node that gets a new
one is a node the reader treats as newly created, which sounds like the interface flickering. This
is why a list that recycles rows must key its rows by their *content*, and why the identity tests
mutate a tree and assert what did **not** change.

## How a frame publishes

The publish is a step in the frame, run after layout and the hover update and before the paint
passes — bounds are settled there, and every change that matters already buys a frame, so no new
scheduling was invented.

Two dirty flags, and they mean different things: the **node** flag says the tree's shape or contents
may have changed and buys a walk; the **header** flag says only the window's stamp moved and buys a
re-stamp, which is much cheaper and is what a window drag produces. Announcements drain *above* both
flags and above the re-present guard, because an announcement is the application speaking rather
than a property of a node — a frame that changes nothing in the tree still says what was queued.

`Scene#announce` is the only way anything is spoken that is not a node. There is no live-region
model; a status label that updates silently stays silent until an application adds the call.

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
platform has been handed something to ask about. A bridge that is not listening allocates nothing,
walks nothing and starts no thread.

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
| **Windows** / UI Automation | COM through LWJGL's JNI trampoline and libffi; a WndProc subclass answering `WM_GETOBJECT` | the platform's, several at once, while the UI thread sleeps | `UiaClientsAreListening()` |
| **macOS** / NSAccessibility | the Objective-C runtime through LWJGL; a runtime `NSAccessibilityElement` subclass with libffi closures | every callback **is** the UI thread | something has asked; opened by the first-frame walk and the children push, which are the attach and the gate together |
| **Linux** / AT-SPI2 | **no native code at all** — D-Bus over a unix socket, in pure Java | a reader thread that answers and never blocks, a writer thread that performs every write | `org.a11y.Status.IsEnabled` on the session bus |

`PlatformBridge` holds the little they genuinely share: the published snapshot and the host. The
event queue, the element registry and the listening gate stayed apart on purpose — each platform has
a different right answer, and a shared one would make a bridge lie.

### The constants rule

**Every platform constant is read off the running platform and asserted in a test**, never recalled
and never copied from a header. Each bridge has a `*ConstantsTest`, and `scripts/a11y/*/` has the
dump script that produced what it checks against: control type ids and interface slot orders on
Windows, role and state names on Linux, and on macOS the `dlsym`'d role symbols plus selector type
encodings read with `class_getInstanceMethod` and `method_getTypeEncoding`.

`AxRoles` therefore holds **symbol names** and not strings: `"NSAccessibilityButtonRole"`, resolved
at run time. The one place the rule cannot be honoured is `NSAccessibilityPriority{Low,Medium,High}`
— a C enum, not exported symbols — and those literals carry a comment saying so.

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

The guests, their logins and their per-platform startup incantations are lab notes and not
repository facts, so they are not here.

## What is deliberately absent

ADR 039 §11 is the full list with the cost of each stated in terms of what a blind user loses. The
ones most likely to be mistaken for bugs: no range-to-rectangle text geometry on any platform (so
character review and braille cursor routing are degraded); no UI Automation `TextPattern`; list rows
carry no verb of their own; no data table behind a chart; no occlusion model, so a scrim hand-rolled
inside a `Stack` is not modal to a reader where `pushOverlay` and `Dialog` are; no MSAA; and the
system accessibility *settings* — high contrast, reduced motion, a system text scale — which are a
different decision with a different shape and would tangle a tree with a theme.
