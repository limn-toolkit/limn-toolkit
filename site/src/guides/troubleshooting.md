---
title: "Troubleshooting"
description: "What the common startup and runtime messages mean and what to do about each: a window that will not open, missing fonts or natives, JDK warnings, thread errors, a slow machine."
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
loaded. The message ends with the `--add-modules` line that loads them for your platform. The
other fix is to put those jars on the class path.

## Warnings at startup on JDK 24 and later

Two groups of warnings appear, both from LWJGL, and neither stops the program:

- "A restricted method in java.lang.System has been called", naming `System::load`. Add
  `--enable-native-access=ALL-UNNAMED` on the class path. On the module path, name the modules
  instead: `--enable-native-access=org.lwjgl`, adding `limn.video.ffmpeg` if you use the FFmpeg
  decoder, which loads its own native library.
- "A terminally deprecated method in sun.misc.Unsafe has been called". Add
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

## "A paint may not announce a change"

Something changed a widget from inside `onPaint`. A paint runs whenever the scene needs pixels,
whether or not anything changed, so it may only draw. Move the change to where its cause is: an
input handler, a setter, `Ui.postDelayed`. [Your own widgets](/docs/custom-widgets/) has the
rest of the rules a widget follows.

## It runs, but slowly

Check `graphicsInfo()` first. A renderer named `llvmpipe` is Mesa's software rasteriser, and a
context created through EGL or OSMesa on a desktop platform came from a translation layer rather
than the machine's own driver. Both happen on virtual machines and remote desktops. The toolkit
works there, and every frame costs more, so the fix is the machine's driver and not the
application. If the driver is the real one, [Performance](/docs/performance/) says what a frame
costs and how to keep it small.

## A popup opens inside the window on Linux

Under Wayland, an application cannot place a window of its own at a screen position, so combo box
lists, menus and date pickers draw inside the owner window there instead. It is the same popup,
with the same keys, clamped to the window rather than to the screen. The same program under X11
gets real popup windows.

## Video or sound is unavailable

`FfmpegVideoDecoder.isAvailable()` is false when the FFmpeg native library did not load, and
`unavailableReason()` says why in one sentence: no build for this operating system, a build for
another processor, or a temporary directory that cannot be written to. The usual fix is the
`limn-ffmpeg-natives` classifier for the platform, or the bundle for every platform; [Images and
media](/docs/images-and-media/) has both. `Sounds.isAvailable()` is false when no audio device
could be opened, and its first call may take a moment while it opens one, so ask it off the UI
thread with `Sounds.warmUpAsync()`. The rest of the application runs either way.
