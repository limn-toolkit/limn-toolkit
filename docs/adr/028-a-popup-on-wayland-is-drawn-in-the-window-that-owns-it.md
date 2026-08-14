# ADR 028: A popup on Wayland is drawn in the window that owns it

- **Status:** Accepted, 2026-08-12. Implemented in `NativeWindow.supportsAbsolutePositioning()`,
  `PopupMenu`, `ComboBox` and `LwjglWindow`.
- **Date:** 2026-08-12
- **Scope:** how a dropdown, a menu and a tooltip reach the screen where the window system will
  not say where a window is; and what it would take to give them a window of their own after all.
- **Audience:** whoever maintains the popup components, and anyone who reads "Wayland" in a bug
  report and reaches for a GLFW build.

---

## 1. The problem, as it presented itself

Three components draw outside their own frame: a dropdown's list, a menu cascade, a tooltip. All
three did it the same way: create an undecorated, floating, transparent native window and put it
at an absolute screen position under the anchor.

On a Fedora 44 KDE guest running a real Wayland session, a dropdown opened near the middle of the
display, unanchored, **wearing a KDE title bar with minimise and close buttons**, despite the
window being asked for no decoration. Both halves have one cause. GLFW creates only *toplevels*,
and a Wayland toplevel is placed by the compositor and decorated by it.

This is not a defect awaiting a fix upstream. Absolute window position is absent from the Wayland
protocol by design; GLFW documents `glfwSetWindowPos` as unimplementable there and answers
`GLFW_FEATURE_UNAVAILABLE` for the whole family of position calls. It cannot become available.

## 2. What was decided

**A popup is drawn in the owner's scene when the owner's window cannot be positioned.** The
window answers `supportsAbsolutePositioning()`; the components pick their presentation from it.

Three consequences, each deliberate:

- **The application chooses nothing and is told nothing.** There is no mode, no flag, no warning.
  An application did not pick the compositor its user runs, and one that failed or degraded
  loudly on a majority-Wayland desktop would be punishing the wrong party. `ComboBox` and
  `PopupMenu` present the same API either way.
- **A window that is genuinely a window is unaffected.** A document, a dialog, a palette has no
  position to agree with, so letting the compositor place it is the right outcome and not a
  fallback. Only content that must line up with something *inside* another window asks the
  question. `Dialog` therefore still opens natively on Wayland, as it should.
- **The predicate is public.** An application that genuinely needs a detached window (and there
  is no good answer for one) can ask and decide for itself rather than discover it by looking.

**It is not an error.** Errors are for programming mistakes: pushing a modal twice is the
application's fault and throws (ADR 026 §2). A compositor without absolute positioning is the
environment, and an environment is not a bug to be reported to the person running it.

## 3. What it costs

The overlay is clamped to the **owner window**; the native presentation clamps to the **display's
work area**. A dropdown near the bottom of a small window therefore shows fewer rows and starts
scrolling sooner. That is the entire difference, and it is the reason this is a fallback rather
than the default everywhere.

Rows, keys, type-ahead, scrolling and the size step are one implementation with two mountings:
`PopupPanel` is a window's scene root in one and an overlay's child in the other. Two would drift
and only one of them would be looked at. `limn.backend.inScenePopups` forces the fallback on any
platform for exactly that reason: a presentation that can only be seen on one desktop is one that
breaks on the other three and is noticed a release later.

## 4. Why not give the popup a real Wayland popup

Wayland's own popup primitive is better than the model this toolkit was built on. `xdg_popup` is
positioned by an `xdg_positioner` that carries the anchor rectangle, the gravity and the
constraint adjustment, so the compositor does the flip and slide against the screen edge; and
`xdg_popup.grab` gives click-outside dismissal. Both are things the toolkit hand-rolls.

It cannot be reached from here. Four facts, each checked rather than assumed:

1. **LWJGL exposes three Wayland handles and no more**: `glfwGetWaylandDisplay`,
   `glfwGetWaylandWindow` and `glfwGetWaylandMonitor` (a `wl_display`, a `wl_surface`, a
   `wl_output`). There is no binding for libwayland-client anywhere in LWJGL.
2. **`xdg_surface.get_popup` needs the parent's `xdg_surface`**, which GLFW keeps private. Passing
   null is legal only if a parent is supplied "using some other protocol" before the first commit,
   and the protocol that does that, `xdg_foreign`, parents *toplevels*, which are exactly the
   thing that cannot be positioned.
3. **A `wl_surface` may hold one role.** GLFW's surface is already an `xdg_toplevel`, so it can be
   turned into neither an `xdg_popup` nor a `wl_subsurface`: the protocol raises `bad_surface`.
   A popup therefore needs a `wl_surface` GLFW did not create, and with it an EGL surface, a GL
   context and a frame loop of its own. That is a second rendering path, not a shim.
4. **Java 17 is the floor** (`options.release`), and the Foreign Function & Memory API is final in
   22. Protocol marshalling would be a JNI library: the first native payload in
   `limn-backend-lwjgl`, for two Linux architectures, in a module whose natives are supplied
   entirely by LWJGL today.

The nearer miss is `wl_subsurface`, and it is worth recording because it looks like the answer.
Its position is relative to the parent and, in the protocol's words, a sub-surface is not clipped
to the parent's area, which is precisely the limitation of §3. Its parent argument is a
`wl_surface`, the one handle LWJGL does expose. It fails on fact 3 alone: the child still has to
be a surface of our own making. It would also give up what `xdg_popup` provides, because a
subsurface has no positioner and no grab, and with no window position there is nothing to clamp
a screen edge against.

**A GLFW build is not the cheap path and would not be the shortest one.** It buys what a raw
Wayland client would buy, at the cost of owning window-system natives for six platforms that
LWJGL supplies today. The FFmpeg work in this repository is the evidence for what that costs: four
platforms, a toolchain trap on each, and one payload that reported itself green while being unable
to load.

## 5. What would change this

Any one of these, and only these:

- GLFW exposing the parent `xdg_surface` (an upstream change, small, and outside our control).
- The Java floor moving to 22+, which turns the marshalling from a native payload into ordinary
  code and removes fact 4 entirely.
- Evidence that the clamp in §3 hurts real applications. It is a bound on how far a list may
  overflow its window, not on whether the control works.

Until one of them happens, the in-scene presentation is the answer rather than a stopgap, and
`limn.backend.platform=x11` remains available for an application that prefers X11 semantics.
