# ADR 079: A pinch is a zoom, and the widget under the pointer takes it

- **Status:** Proposed, 2026-10-22. Section 1 holds the owner's questions, each with a
  recommendation; section 6 holds the measurements to take before any of it is built.
- **Date:** 2026-10-22
- **Scope:** the pinch on a trackpad (two fingers moving apart or together) reaching a Limn widget as
  a zoom, on macOS, Windows and Linux under X11 and Wayland. It also covers Command or Control with
  the wheel, which applications treat as the same request. The record covers the event, how the
  scene hands it out, where each system's backend gets it, and the two widgets that take it first.
- **New public API (proposed), in `limn-toolkit`:** `MouseEvent.Type.ZOOM` and
  `MouseEvent.scale()`. In the SPI, `WindowInput.zoomed(scale, x, y, gestureBegins)`. Nothing else.
- **Compatibility:** additive. `MouseEvent.Type` gains a constant, which its Javadoc already allows.
  A widget that ignores `ZOOM` behaves exactly as it does today.
- **Relates to:** [ADR 078](078-a-document-is-widgets-cut-into-pages-and-every-page-leaves-as-pdf.md)
  (the print preview, the first widget to take a pinch).

---

## 0. Why, and what exists

On a laptop the trackpad is the pointer, and the pinch has been how a person zooms on one for more
than a decade. The same holds on a phone, where everyone learned it. Business applications zoom in
many places: a document before printing, a picture, a drawing, a chart, a map, a 3D part. A person
who pinches in a Limn window and sees nothing happen concludes that the program is broken, not that
the toolkit lacks a feature. The owner asked for it on 2026-10-22, after pinching in the print
preview and getting nothing.

The pinch passes the rule that Limn offers what the systems have in common. All three have it
natively on a trackpad:
- macOS sends magnification events to the view under the pointer.
- On Windows, a precision touchpad's pinch reaches an application that does not use Direct
  Manipulation as Control with the wheel.
- Linux has the gesture events of XInput 2.4 under X11, and the `zwp_pointer_gestures_v1` protocol
  under Wayland.

What exists, read in the tree on 2026-10-22:

- **The toolkit has no gesture and no touch input.** `MouseEvent.Type` is `ENTER, EXIT, MOVE, DRAG,
  PRESS, RELEASE, CLICK, WHEEL, MOTION`. Nothing in the backend reads a magnification, a gesture or
  a touch.
- **GLFW gives none.** The GLFW that LWJGL 3.4.3 bundles has no gesture callback on any system. A
  pinch on the host's trackpad reaches a Limn window as nothing at all.
- **Two widgets zoom today, and both read the wheel.**
  - `PrintPreview`, since `5245b7f7`, zooms with the wheel while Command or Control is held, and with
    Command or Control and `+`, `-` and `0`.
  - `Viewport3D` zooms its camera with the plain wheel when it has a camera controller.
  - Neither sees a pinch.
- **The backend already reaches past GLFW where a gesture would arrive:**
  - On macOS, `MacInputContextGate` moves each window's view into one subclass of GLFW's content
    view, made once for the process. A method that answers the magnification can be added to that
    same class.
  - On Wayland, `transfer/Wl` and `WaylandClipboard` bind their own `wl_seat` and protocols outside
    the core (`xdg_activation_v1`). They do it on an event queue of their own, on the display GLFW
    opened, with no native code of ours. A second `wl_pointer` for the gestures fits the same
    machinery.
  - On X11, `X11Clipboard` opens a display connection of its own and reads its events on a thread.
  - On Windows, the accessibility bridge (`UiaWindow`) already replaces each window's window
    procedure. A `WM_GESTURE` would arrive there, if it is ever wanted (question 4).
- **The lab has no real trackpad on three of the four guests.** Read on 2026-10-22:
  - Fedora KDE 44 has only the Parallels Virtual Mouse, which offers pointer capabilities and no
    gestures (`libinput list-devices`), plus `/dev/uinput`.
  - Ubuntu 24.04 lists the same mouse. It has Xorg 21.1.12 (XInput 2.4) and xf86-input-libinput
    1.4.0.
  - Windows 11 shows no precision touchpad: no `PrecisionTouchPad\Status` key. It does have a
    "HID-compliant digitizer" whose part in a pinch is unknown.
  - The host is a Mac with a real trackpad, which makes it the one place a real pinch is certain.

## 1. Questions for the owner

1. **The event's shape.**
   - **(a) recommended:** `MouseEvent.Type.ZOOM`, with `scale()` as the factor since the last zoom
     event: above 1 zooms in, below 1 zooms out. Its `x()` and `y()` are the point to zoom around.
     It rides the same dispatch as the wheel, from the widget under the pointer outwards, so a widget
     that does not consume it lets it bubble.
   - **(b):** a `GestureEvent` class with a `Widget.onGestureEvent` hook. That class would be ready
     for a rotate or a swipe, but it is a second input path that every container would have to learn.
2. **Command or Control with the wheel is offered as a zoom first.**
   - **Recommended: yes.** The scene offers such a wheel event along the path as a `ZOOM` (scale
     1.1 per detent, a fraction of it for a trackpad's fractions). Only if nothing consumes the zoom
     does the same event go out as the `WHEEL` it is today.
   - This gives a widget one way to zoom instead of three. It means a Windows precision touchpad's
     pinch, which arrives as Control with the wheel, works without native code.
   - It leaves every widget that reads Control with the wheel for something else unchanged. The
     only such widget today is `PrintPreview`, which would move to `ZOOM`.
   - Command is the macOS key. Control is the key elsewhere, and on macOS too, as browsers take it.
3. **Linux below the gesture floors goes without a pinch.**
   - **Recommended: yes, silently.** X11 needs XInput 2.4: an X server of 21.1 or newer and
     xf86-input-libinput 1.2 or newer. Ubuntu 22.04 and 24.04 and Debian 12 have both. RHEL 8,
     Ubuntu 20.04 and Debian 10 under X11, which are inside the glibc floor, have neither.
   - There, the wheel with Control and the keys still zoom. Nothing is logged for a missing gesture.
   - Wayland needs the compositor to advertise `zwp_pointer_gestures_v1`. GNOME, KDE and the
     wlroots compositors do.
4. **A touchscreen's pinch on Windows (`WM_GESTURE` with `GID_ZOOM`).**
   - **Recommended: later, with touch as its own record.** A touchscreen brings the tap, the drag
     that scrolls and the absence of hover, and those deserve one decision together. The lab has no
     touchscreen to measure it on.
   - The only part of it this record would build is the window procedure the accessibility bridge
     already replaces.
5. **Which widgets take it now.**
   - **Recommended:** `PrintPreview`, around the pinch's point, and `Viewport3D` with a camera
     controller, as its wheel zoom.
   - An `ImageView` or a chart that zooms does not exist yet. Each takes the pinch when it gains a
     zoom.
6. **How Linux is verified.**
   - The guests have no trackpad. **Recommended:** a test program creates a virtual touchpad through
     `/dev/uinput` for the length of a run and removes it after. libinput then turns its contacts
     into real gesture events.
   - This is a change to the guest while the run lasts, and it needs a root user on Ubuntu, whose
     sudo asks for a password. It is the owner's decision.
   - The alternative is a real Linux laptop.
7. **Rotation, the two-finger double tap ("smart zoom") and the three-finger swipe.**
   - **Recommended: out.** Business applications rarely rotate anything, and the swipe is the
     system's own gesture on all three systems.

## 2. The decision, as proposed

**The event.**
- A pinch becomes a stream of `ZOOM` events at the pointer.
- Each event carries the factor since the one before it. The backend turns the system's own measure
  into that factor: on macOS a magnification added to 1, on X11 and Wayland the ratio of the scale
  since the gesture began to the previous one.
- The first event of a gesture says so, as the wheel's first event already does
  (`scrolled(..., gestureBegins)`).
- The scene keeps the gesture with the widget that consumed its first event, as it already latches
  a wheel stream. A pinch that started over the preview stays with the preview even when the
  pointer leaves it.

**What a widget does with it.** It multiplies its zoom by `scale()` and keeps the point at `x()` and
`y()` where it is. `PrintPreview` already does this for the wheel (`zoomBy`), so it changes its input
and not its arithmetic.

**The wheel as a zoom (question 2).**
- The scene's `dispatchWheel` gains one step before its loop: a wheel event with the zoom's
  modifier is first offered as a `ZOOM` along the same path.
- A consumed zoom latches the stream as a consumed wheel does.
- An unconsumed one falls through to the wheel unchanged.

**Where each system's pinch comes from.**

| System | Source | Where in the backend | Floor |
|---|---|---|---|
| macOS | `-magnifyWithEvent:`, its `magnification` and its `phase` | a method added to the subclass of GLFW's content view that `MacInputContextGate` already makes | macOS 11 (the method dates from 10.5) |
| Windows, precision touchpad | Control with the wheel, synthesized by the system for an application without Direct Manipulation | none: question 2 makes it a `ZOOM` in the scene | Windows 10 |
| Linux, X11 | `XI_GesturePinchBegin/Update/End`, selected on GLFW's window from a display connection of ours | a reader beside `X11Clipboard`'s, on its own thread, posting to the UI thread | X server 21.1, xf86-input-libinput 1.2 |
| Linux, Wayland | `zwp_pointer_gesture_pinch_v1` on a `wl_pointer` of our own seat | `Wl`'s machinery, the interface described in memory as `xdg_activation_v1` already is | the compositor advertises `zwp_pointer_gestures_v1` |

**What is not built.**
- A touchscreen (question 4).
- Rotation, the smart zoom and swipes (question 7).
- Any change to the two-finger scroll, which stays the wheel it is today.

## 3. What the user sees

- Pinching over the print preview zooms it around the fingers, as the wheel with Command already
  does. The page's "Custom" segment and percentage follow it, through the `VALUE` change the preview
  already announces.
- Pinching over a 3D viewport with a camera controller moves the camera in and out.
- Pinching anywhere else does nothing, as in a native application whose content does not zoom.
- On Windows, a pinch over a widget that does not zoom reaches the widget as the Control-wheel it is
  today.

## 4. Accessibility

A pinch is a pointer gesture, and it is never the only way to zoom:
- a widget that takes it also takes Command or Control with `+`, `-` and `0` when focused, as
  `PrintPreview` already does;
- nothing is announced for a pinch beyond the `VALUE` change the zoom already makes.

## 5. Size

Estimated, in lines of Java:

| Part | Lines |
|---|---|
| The event, the dispatch and its latch, with tests | 300 |
| `PrintPreview` and `Viewport3D` | 60 |
| macOS | 120 |
| X11 | 250 |
| Wayland | 250 |
| Windows | 0 if measurement 2 finds Control with the wheel; about 100 if it finds a gesture message instead |

The probes of section 6 are not shipped.

## 6. Measurements before any code

1. **macOS, on the host.** Does `-magnifyWithEvent:` reach the subclass of GLFW's view? With what
   phases? Does any scroll event arrive during the same pinch?
   - There is no public way to synthesize a pinch, so this needs the owner's fingers on the
     trackpad.
   - It is repeated on the macOS guest to see whether Parallels passes the gesture through.
2. **Windows, on the guest.** What does a pinch on the host's trackpad become inside the guest? A
   probe logs every message its window gets: `WM_MOUSEWHEEL` with the Control key down, `WM_GESTURE`,
   or `WM_POINTER` from the digitizer.
   - The owner pinches.
   - If it is Control with the wheel, the Windows column is done. Otherwise it needs a real laptop
     with a precision touchpad.
3. **X11, on the Ubuntu guest under its X session.**
   - Does the server answer XInput 2.4?
   - Does selecting the pinch events from a second connection on GLFW's window succeed, or is it
     refused (`BadAccess`)?
   - Do events arrive for a pinch made by a virtual touchpad (question 6)?
4. **Wayland, on Fedora KDE and on Ubuntu's GNOME session.** Is `zwp_pointer_gestures_v1`
   advertised? Do pinch events reach a `wl_pointer` of our own seat while GLFW holds its own, for the
   same surface, with the same virtual touchpad?
5. **Everywhere.** One pinch must give one stream of `ZOOM` events and no wheel in between. The
   scene latch must hold the stream when the pointer leaves the widget mid-pinch.

## 7. Stages

1. **Stage 0:** the measurements of section 6. Each one can strike a column of the table in section
   2 before code is written for it.
2. **Stage 1:**
   - the event, the scene's dispatch, latch and wheel offer;
   - `PrintPreview` and `Viewport3D`;
   - macOS;
   - Windows if measurement 2 holds.
3. **Stage 2:** X11 and Wayland, each verified with the virtual touchpad or a laptop.
