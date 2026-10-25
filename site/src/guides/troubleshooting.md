---
title: "Troubleshooting"
description: "What the common startup and runtime messages mean and what to do about each: a window that will not open, a machine without a usable OpenGL, missing fonts or natives, JDK warnings, thread errors, an exception in a handler, a slow machine."
---

Most failures name their own fix: the messages below were written to be read by whoever hits them.
This page collects them by symptom, in the order you are likely to meet them.

## The window never opens

**On macOS, the message ends with "on macOS the JVM must run with -XstartOnFirstThread".** The
JVM started on a thread AppKit will not accept. Add the flag to the macOS launch only, since
Windows and Linux JVMs refuse it; [Packaging](/docs/packaging/) shows where it goes for each kind
of launcher.

**On Linux, the message says "neither DISPLAY nor WAYLAND_DISPLAY is set".** There is no display
server: a shell over SSH, a container, a CI job. Give it one with `xvfb-run`, or run the program
from a desktop session.

**The message says "Limn needs an OpenGL 3.3 core context".** The machine's own OpenGL gave the
window nothing, or nothing new enough. Where the refusal is one it can route around, a Windows
machine with no OpenGL driver or one older than 3.3, a Mac with no accelerated renderer or no
OpenGL at all, the backend has already tried the fallback it carries before it says so: OpenGL ES
3.0 from ANGLE, over Direct3D 11 on Windows and over Metal on macOS, and on such a Mac the system's
software renderer after that. When the message goes on "or OpenGL ES 3.0 from ANGLE, which failed
too", it gives that step's reason as well. On Windows it then names the way out: the graphics
driver from the GPU's maker or, where none offers OpenGL 3.3 (a virtual machine, a remote-desktop
session), Microsoft's free OpenCL, OpenGL, and Vulkan Compatibility Pack. An application opened
from its icon has no console to print that to, so it first shows Windows' own error dialog, with
buttons that open the pack in the Store or install it with winget; the error is thrown afterwards,
whatever is clicked. Linux has no ANGLE fallback: a machine with no GPU driver runs on Mesa's
software rasteriser, so the fix there is the driver, or Mesa.

Two system properties change what happens on such a machine:

- `-Dlimn.backend.errorDialog=false` never shows the dialog, and the error is thrown as before.
  A test run or a service, where nobody would see a dialog, wants it.
- `-Dlimn.backend.angle=off` refuses the ANGLE fallback; `force` takes it on a machine whose own
  OpenGL works, to see the application as it runs there; `auto`, the default, takes it only when
  the system's OpenGL gives no context.

**Anything else.** Ask the machine what it has. Once a backend exists, `backend.graphicsInfo()`
reports the windowing platform, the driver and the graphics context it actually obtained. When
the constructor itself threw, `LwjglBackend.startupFailure(error.getMessage())` builds the same
report without one, including the platforms the loaded GLFW was built with, which tells a machine
running neither X11 nor Wayland apart from a broken library. Print either from a diagnostic flag
or put it in an error dialog, so that a bug report from a machine you cannot reach carries it.

## "The default UI font is not on the classpath"

The backend needs Roboto, which arrives with it as a dependency, so the one way to lose it is a
build that excludes `limn-fonts-roboto`: remove the exclusion. On the module path the font jars are
named modules the backend requires, and they load from there as they do from the class path;
[Packaging](/docs/packaging/#on-the-module-path) has the details.

## "LWJGL's native libraries did not load"

On the module path, LWJGL's `natives` jars are modules that nothing requires, so they are never
loaded unless named, and every platform's jar of one LWJGL module has the same module name, so the
first on the path wins, whichever platform it is for. The message says which of the two happened:
it names this machine's classifier, and either the `--add-modules` line that loads the natives or
the other platform's jar that was loaded instead. Keep only this platform's natives, as
[Packaging](/docs/packaging/#natives-for-one-platform) shows, or put them on the class path.

## Warnings at startup on JDK 24 and later

Two groups of warnings appear, and neither stops the program:

- "A restricted method in java.lang.System has been called", naming `System::load`. LWJGL loads
  its libraries that way, and so does the backend itself, for ANGLE and the SVG renderer. Add
  `--enable-native-access=ALL-UNNAMED` on the class path. On the module path, name the modules
  instead: `--enable-native-access=org.lwjgl,limn.backend.lwjgl`, adding `limn.video.ffmpeg` if
  you use the FFmpeg decoder, which loads its own native library too.
- "A terminally deprecated method in sun.misc.Unsafe has been called", from LWJGL. Add
  `--sun-misc-unsafe-memory-access=allow`.

The JDK says restricted methods will be blocked in a future release unless native access is
enabled, so the first flag is worth adding before that release, not after.

## "Not on the Limn UI thread"

A widget, a layout or a scene was touched from another thread. Every one of their public
mutators checks, so the error points at the call that did it. Hand the work back with
`Ui.post(…)`, or run it with `Ui.work(…)`, whose callbacks arrive on the UI thread;
[Background work](/docs/background-work/) has the whole pattern.

"No UiRuntime installed" is the same mistake made earlier: something used `Ui` before a backend
was constructed. Construct the backend first; it is what makes its thread the UI thread.

## "Already has a handler"

A handler slot such as `onAction` or `onChange` holds one handler, and a second registration
throws rather than silently replacing the first. Pass `null` to clear the slot before setting a
new one, or register with `observeChanges`, which takes any number of watchers.

## An exception in one of your handlers

An exception thrown by your code inside the event loop, in a click handler, a posted task, a
ticker or a frame, does not end the application. The toolkit catches it where it happened, logs
it at `ERROR`, and goes on: the event is dropped, the task is skipped, a ticker that threw is
removed, the frame is drawn again. A frame that throws every time is retried with a pause rather
than at full speed, so a bug that repeats does not spin the processor.

To send those exceptions somewhere of your own, a crash report, a log file or a dialog, install a
`CrashHandler` once at startup:

```java
Crashes.install((phase, error) -> {
    crashLog.record(phase, error);   // your own report, file or dialog
    return true;                     // keep running; false asks for an orderly shutdown
});
```

It runs on the UI thread, inside the turn that caught the exception, so it keeps short and never
throws. Its answer is followed where the exception would otherwise have ended the loop, a frame
or the system's event poll, and only heard where the toolkit contains it anyway, one event or one
task, so decide by what was thrown rather than by the phase.

## "A paint may not announce a change"

Something changed a widget from inside `onPaint`. A paint runs whenever the scene needs pixels,
whether or not anything changed, so it may only draw. Move the change to where its cause is: an
input handler, a setter, `Ui.postDelayed`. [Your own widgets](/docs/custom-widgets/) has the
rest of the rules a widget follows.

## It runs, but slowly

Check `graphicsInfo()` first. A renderer named `llvmpipe` is Mesa's software rasteriser. A
`contextApi()` that reads `EGL, ANGLE on Direct3D 11 (fallback)` or `EGL, ANGLE on Metal
(fallback)` is the OpenGL ES fallback, taken because the system's OpenGL gave no context; any
other context created through EGL or OSMesa on a desktop platform came from a translation layer
rather than the machine's own driver. All of them happen on virtual machines and remote desktops.
The toolkit works there, and every frame costs more, so the fix is the machine's driver and not
the application; `-Dlimn.backend.angle=force` takes the same path on a machine whose own OpenGL
works, to see the application as such a user does. If the driver is the real one,
[Performance](/docs/performance/) says what a frame costs and how to keep it small.

## A popup opens inside the window on Linux

Under Wayland, an application cannot place a window of its own at a screen position, so combo box
lists, menus and date pickers draw inside the owner window there instead. It is the same popup,
with the same keys, clamped to the window rather than to the screen. The same program under X11
gets real popup windows.

On any platform, a fullscreen window draws its popups and its modal dialogs inside itself too:
a window fullscreen at a named mode is minimized as soon as a second window takes the focus, and
one made fullscreen with `enterFullscreen()` already covers the screen, so nothing is lost by
drawing inside it. A popup already
open as a window when the window goes fullscreen closes, and a modal dialog moves inside.

## Video or sound is unavailable

`FfmpegVideoDecoder.isAvailable()` is false when the FFmpeg native library did not load, and
`unavailableReason()` says why in one sentence: no build for this operating system, a build for
another processor, or a temporary directory that cannot be written to. The usual fix is the
`limn-ffmpeg-natives` classifier for the platform, or the bundle for every platform; [Images and
media](/docs/images-and-media/) has both. `Sounds.isAvailable()` is false when no audio device
could be opened, and its first call may take a moment while it opens one, so ask it off the UI
thread with `Sounds.warmUpAsync()`. The rest of the application runs either way.
