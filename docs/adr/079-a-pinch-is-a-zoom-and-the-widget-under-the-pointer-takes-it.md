# ADR 079: A pinch is a zoom, and the widget under the pointer takes it

- **Status:** Accepted and implemented, 2026-10-23. Accepted 2026-10-22: the owner took the recommendation of all seven questions of
  section 1 the same day. Stage 0, the measurements of section 6, was taken the same day on the host
  and three guests (section 8): every system's source works as section 2 says, and one blocker
  outside this record was found on the way and fixed (section 8.6). Stage 1 built on 2026-10-23
  (section 9): the event, the scene's dispatch, `PrintPreview`, `Viewport3D`, macOS and Windows.
  Stage 2, X11 and Wayland, built and verified the same day with the virtual touchpad (section 10).
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
  - Fedora KDE 44 has only the hypervisor's virtual mouse, which offers pointer capabilities and no
    gestures (`libinput list-devices`), plus `/dev/uinput`.
  - Ubuntu 24.04 lists the same mouse. It has Xorg 21.1.12 (XInput 2.4) and xf86-input-libinput
    1.4.0.
  - Windows 11 shows no precision touchpad: no `PrecisionTouchPad\Status` key. It does have a
    "HID-compliant digitizer" whose part in a pinch is unknown.
  - The host is a Mac with a real trackpad, which makes it the one place a real pinch is certain.

## 1. Questions for the owner

**Answered 2026-10-22: every recommendation taken.**

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
   - It is repeated on the macOS guest to see whether the hypervisor passes the gesture through.
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

## 8. Stage 0, measured on 2026-10-22

The probes are throwaway programs and were not committed. They ran in the scratch folder of the
session that wrote this section, and on the guests under `limn-pinch` (Linux) and
`limn-print\pinch` (Windows).

### 8.1 macOS, on the host

The probe put `-magnifyWithEvent:` and `-smartMagnifyWithEvent:` on GLFW's content view class
(`GLFWContentView`) with `class_addMethod`. The class inherits both from `NSResponder`, so the method
added overrides them. The owner pinched in three rounds, and in the third the window drew the zoom.

- **The method is called.** It was called 153, 126 and 126 times.
- **Every pinch has a clear start and end.** It starts with phase *began* (1), continues with
  *changed* (4) and ends with *ended* (8). The *ended* event carries a magnification of its own.
  - Events arrive every 16.9 ms (the median of 122; 12.8 ms at the 10th percentile, 20.9 ms at the
    90th).
  - The magnification is the change since the last event. Multiplied as 1 + m over each gesture,
    the nine pinches came to factors from 0.16 to 5.01.
- **No scroll ever arrived inside a pinch.** That held even when the owner moved his hand across
  the trackpad while pinching (a pinch of 1.9 s). The two-finger scroll came in separate blocks,
  as GLFW's scroll, before and after.
- **The two-finger double tap arrives** as `-smartMagnifyWithEvent:`. It is a separate event with
  no magnification, and question 7 leaves it out.

The macOS guest was not measured: on the host, a real pinch already answers the question.

### 8.2 Windows, on the ARM64 guest

The hypervisor presents itself to the guest as a pen digitizer, not a touchpad: `SM_DIGITIZER` is 0x88
and `SM_MAXIMUMTOUCHES` is 1. A plain window logged every message. The owner pinched in two runs,
and in the second the window drew the zoom from Control with the wheel, which he saw follow his
fingers.

- **Every pinch arrived the same way.** First `WM_KEYDOWN` of Control, then a run of
  `WM_MOUSEWHEEL` with `MK_CONTROL` set and `GetKeyState(VK_CONTROL)` down, then `WM_KEYUP` of
  Control. There were five pinches, of 24 to 49 wheel messages each.
- **The two-finger scroll came separately,** as wheel messages without Control.
- **No `WM_GESTURE`, `WM_TOUCH` or touchpad `WM_POINTER` arrived.**

So the Windows column needs no native code: question 2's offer of Control with the wheel as a zoom
takes it, and a GLFW window sees the same messages. A real precision touchpad is documented to do
the same for an application that does not use Direct Manipulation; that was not measured, since the
lab has none.

### 8.3 Linux, X11, on the Ubuntu 24.04 guest

The setup was an X session with Xorg 21.1.12 and xf86-input-libinput 1.4.0. The pinch came from a
virtual touchpad with two contacts, through `/dev/uinput`, which the owner opened to the user with
`setfacl` until the next reboot (question 6). libinput classified it as a clickpad (100 × 62 mm) and
Xorg listed it as a slave pointer.

- **The server answered XInput 2.4.**
- **Selection from a second connection works.** A connection of the probe's own selected
  `XI_GesturePinchBegin`, `Update` and `End` on GLFW's window, and the server accepted it (no error
  after `XSync`).
- **The pinch reached GLFW's window,** once the pointer was over it:
  - a begin, 37 updates and an end per pinch;
  - two fingers;
  - `scale` cumulative since the begin, from 1.07 to 3.66 opening and down to 0.23 closing;
  - `delta_scale` always 0, so the change between events is the ratio of two scales.
- **The pointer's position matters.** In the first try nothing arrived on the window because the
  pointer was over the dock: a pinch goes to the window under the pointer, as a wheel does.
- **The scroll seen once is not the pinch's.** One run had GLFW receive 124 wheel events during and
  after the pinches. Two repeats of the same run had none, and the run with the root selected as
  well had none. They are recorded as not from the pinch: their source is not known, and the owner
  was using the host's trackpad at the time.

### 8.4 Linux, Wayland, on the Fedora KDE 44 guest

The same virtual touchpad was used. KWin advertises `zwp_pointer_gestures_v1` at version 3, and
`wl_seat` at version 10.

- **The probe used the backend's own pieces.** It used `Wl`, the same queue and wrapper that
  `WaylandClipboard` uses, a `wl_seat` bound again at version 5, and its own `wl_pointer`. It
  described the gestures protocol in memory, as `xdg_activation_v1` is.
- **The pinch reached that pointer while GLFW held its own.** `begin` named GLFW's surface and two
  fingers. There were 72 updates over two pinches, with `scale` cumulative since the begin (1.14 to
  3.66, then 0.96 to 0.64).
- **No GLFW scroll arrived.**

Ubuntu's GNOME session under Wayland was not measured, because the guest runs its X session.
Changing that is the owner's call; GNOME's compositor is documented to advertise the protocol.

### 8.5 What this changes in section 2

- The macOS, X11 and Wayland sources are confirmed. On Windows the system's own Control-wheel is
  the source, as proposed.
- The factor each backend hands the scene comes from a different measure on each system:
  - **macOS:** 1 + magnification per event;
  - **X11 and Wayland:** this event's cumulative scale over the previous one, with 1 at a begin;
  - **Windows:** 1.1 to the power of the wheel's detents, through question 2.
- The virtual touchpad worked as question 6 hoped. It is how stage 2 verifies Linux. On Ubuntu it
  needs the owner's `setfacl` again after a reboot.

### 8.6 A blocker found on the way: the backend's callbacks under Java 25

The Fedora guest's only JDK is 25. Under it, LWJGL 3.4.3 loads its implementation of native
callbacks made with the platform's foreign-function API. That implementation refuses the backend's
generic callbacks, `Ffi.callback` and `Wl.on`, with `UnsupportedOperationException: The binder must
be parameterized with an interface`.

The Wayland probe ran only with LWJGL's base classes put first on the class path. The same callbacks
carry the macOS menu bar, the tray, the printer, the clipboard and drag on all three systems, and
the whole Wayland data path. Every source this record adds would need one too, on macOS, X11 and
Wayland.

It is outside this record and was proposed as a task of its own. Stage 1 waits for it, or builds
its callbacks typed, as `MacInputContextGate` does.

**Fixed on 2026-10-22, before stage 1.** The fix is in commit `57c14862`:
- every typed callback interface carries `@FunctionalInterface`;
- `Ffi.callback` and `Wl.on` have one typed shape per signature in `FfiShapes`;
- the accessibility range getter writes into a structure handed in.

Verified as follows:
- every shape answers a native call on Java 25 and 21, on macOS and Linux;
- the Showcase opens on Java 25;
- the Wayland clipboard read another program's text under Java 25;
- the backend's suite passes on both versions.

`FfiShapesTest` keeps both rules for every new callback.

## 9. Stage 1, built on 2026-10-23

### 9.1 The event and the scene

- **`MouseEvent.Type.ZOOM`** is added at the end of the enum.
  - `MouseEvent.zoom(x, y, scale, modifiers)` makes one, and refuses a factor that is not positive
    and finite.
  - `scale()` answers 1 for every other type.
- **`WindowInput.zoomed(scale, x, y, gestureBegins)`** is a default that does nothing.
  - The scene's own input and the test driver override it.
  - A dialog's closed window keeps ignoring it, as it ignores everything else.
- **The scene queues zoom events as it queues the wheel's.** Consecutive ones merge into their
  product, never across the start of a gesture.
- **`dispatchZoom` hands a zoom out from the widget under the pointer.**
  - It keeps the gesture with the widget that consumed its first event, until the next begins or
    until a pause of the wheel's 150 ms.
  - The latch is let go when that widget leaves the tree or a modal opens, as the wheel's is.
- **The wheel with the zoom's key is offered as a zoom first.**
  - The factor is 1.1 per detent.
  - It becomes the `WHEEL` it was only when nothing consumed it.
  - The key is Command or Control alone on macOS, Control alone elsewhere, with Shift allowed.
    Alt with Control is a scroll.
  - A widget therefore zooms with one handler, and Windows needs no native code.

### 9.2 The widgets

- **`PrintPreview`** handles `ZOOM` around the pointer, with the arithmetic it already had for the
  wheel. Its own wheel-and-modifier code is gone, and it keeps its keys.
- **`Viewport3D` with a camera controller** handles `ZOOM` as `zoom(10 ln s)`. A controller zooms by
  `e^(0.1 a)`, so one detent offered as a zoom moves the camera as one detent of the wheel does.

### 9.3 macOS

`MacPinch` adds `-magnifyWithEvent:` to the class that `MacInputContextGate` moves every window's
view into, once for the process, with the encoding read from AppKit.

- Each window registers a target. The target hands `1 + magnification` to its input at GLFW's
  cursor, with `phase` began as the start of a gesture.
- A window whose view the gate could not move has no pinch. The wheel with Command still zooms in it.
- The callback is typed and functional, so it is made under Java 25 as well.

### 9.4 Verified

Tests, all passing:
- `SceneZoomTest`, nine tests:
  - delivery under the pointer;
  - the product of queued events and a new gesture never merged into the one before;
  - the latch held across a move and let go by a new gesture or a pause;
  - the wheel with Control as a zoom where a widget zooms, and as a zoom then a scroll where none
    does;
  - the plain wheel and Alt with Control as scrolls only;
  - the factor's bounds.
- `PrintPreviewZoomTest`, three tests: a pinch, the wheel with Control, and the keys each zoom the
  preview, announced as the user's change of its value.
- The suites of `limn-toolkit`, `limn-test`, the backend and the demo.

On the host, the owner pinched over the Showcase's print preview: the pages zoomed with the fingers
and the page's percentage followed.

**But the zoom was not centred on the pointer.** The sheet stayed against the left edge, as if the
zoom were always around x = 0. Two defects of the preview caused it, both older than the pinch,
since the wheel and the keys had them too:
- its scroll view scrolled up and down only, so a sheet wider than the view was cut on the right;
- a zoom marked the preview for layout but not the sheets inside the scroll view, which kept their
  last measure, so the sideways offset that keeps the point under the pointer was clamped to 0.

The scroll view now scrolls both ways, and the sheets are marked whenever their size changes, with
the zoom or with the pages. A test checks the sideways scroll: 584 points after a pinch of 2 at
x = 600 on a sheet fitted to the width. The owner then confirmed the zoom stays centred on the
pointer.

Not run: the Windows guest with this build. Measurement 2 showed the pinch arriving there as Control
with the wheel, which section 9.1 offers as a zoom; the scene tests drive exactly that sequence.

## 10. Stage 2, built on 2026-10-23

### 10.1 Wayland

`WaylandClipboard` binds `zwp_pointer_gestures_v1` from the registry it already reads, at version 1,
with its interfaces described in memory, as `xdg_activation_v1` is. It binds it before the seat's
capabilities arrive, so the pinch is made on the clipboard's own `wl_pointer`, on its queue, and
destroyed with that pointer.

- **Begin** finds the window by its surface in the registry the drags already keep.
- **Each update** hands the window's input the ratio of the compositor's cumulative scale to the
  last one. The point is where the pointer is: the listener now also reads the position from the
  pointer's `enter`.
- The new event signature `ppiipi` has its shape in `FfiShapes`. The file was regenerated by the
  script that wrote it, which added only that shape.

### 10.2 X11

`X11Pinch` lives on the clipboard's connection and thread, as `X11Drag` does.

- **Once:** it asks for `XInputExtension` and announces XInput 2.4. Without libXi, or on a server
  older than 2.4, it reads nothing.
- **Per window:** it selects `XI_GesturePinchBegin`, `Update` and `End` on each of the program's
  windows.
- **Per event:** the clipboard's event loop hands it the generic events, and it reads the event
  window, the point and the cumulative scale.
- Every call into Xlib is made on that thread.

### 10.3 A defect the measurement found

The first X11 run ended its closing pinch at ×0.270, where the server's scale was ×0.228. The trace
(`-Dlimn.pinch.trace=true`) showed every update arriving, down to 0.2278.

- **The cause.** Both backends hand the zoom to the window's input in a task posted to the UI
  thread. That is outside the window library's poll, whose end is what drains a scene's input, so
  the last updates waited in the queue for the next event.
- **The fix.** Each posted zoom now ends its batch.

### 10.4 Verified

The virtual touchpad of question 6 opened its fingers from 0.3 to 1.4 of the pad, then closed them
again. The receiver was a Limn window of the demo's fat jar with one widget that logs every `ZOOM`.

| | Zoom events | Opening, the product | Closing, the ratio | System's | Wheel events |
|---|---|---|---|---|---|
| X11, Ubuntu 24.04, Java 21 | 74 | 3.665 | 0.2278 | 3.665, 0.2278 | 0 |
| Wayland, KDE on Fedora 44, Java 25 | 72 | 3.664 | 0.2265 | 3.664, 0.2266 | 0 |

- **The pointer must be over the window,** as for the wheel. A run with the pointer on the title
  bar heard nothing, which is the window manager's and not a defect.
- **The Wayland run is also the Java 25 run of the new callback.**
- **Not measured:**
  - GNOME under Wayland, because the Ubuntu guest runs its X session;
  - a real Linux trackpad;
  - RHEL 8, Ubuntu 20.04 and Debian 10, whose X servers are older than 2.4 and read no pinch by
    question 3.

