# ADR 086: A backend without a display draws with the same canvas

- **Status:** **Accepted**, 2026-11-03, at the owner's word ("Também sem tela"), after the measure of
  section 1; built the same day on the branch of ADR 085.
- **Date:** 2026-11-03
- **Scope:** where `Images.render` and the pictures a printed page needs are drawn when no window of
  the application's can draw them, and whether the backend starts on a machine with no display.
- **New public API:** `Backend.canOpenWindows()` (a default: true); the system property
  `limn.backend.headless` (`auto`, the default; `true`; `false`), named by
  `LwjglBackend.HEADLESS_PROPERTY`.
- **Changed behaviour:** a machine with no display used to stop the backend at its start
  ("glfwInit() failed"); it now starts without one, opens no window and no tray, and draws.
  `-Dlimn.backend.headless=false` keeps the old failure.
- **Relates to:** [ADR 085](085-the-canvas-is-the-toolkits-whole-2d-api-measured-against-the-platforms-own.md)
  (question 4, and section 4.8's "canvas over an image on any thread"; `Images.render` of its section
  15); [ADR 003](003-gles-angle.md) (ANGLE, the fallback this uses); [ADR 082](082-a-lost-graphics-context-is-rebuilt-in-the-same-windows.md)
  (the contexts the toolkit owns).

---

## 0. The question

After ADR 085, `Images.render` drew with the context of a window of the application's, so it failed
with none open, and a machine with no display (a server, a container, a CI job, a Windows service)
could not start the backend at all. ADR 085 had set the gap down as "a canvas over an image on any
thread, without a window", which "needs a CPU rasterizer", a second engine whose edges would differ
from the screen's. The owner asked whether that was so: would an invisible window not do, and are
the software fallbacks the toolkit already uses (ANGLE, the system's software OpenGL) not rendering
on the processor already?

They are. A software OpenGL (Apple's software renderer, Mesa's llvmpipe, Direct3D's WARP under
ANGLE) runs the same canvas program as a GPU: the same shaders, the same coverage, the same pixels
but for the rounding a different rasterizer may give an edge. The question was never how to rasterize
without a GPU; it was how to get a context without a window, and, apart from it, how to draw off the
UI thread. A second rasterizer is not needed for the first, and this record does not propose one.

## 1. What was measured

A throwaway probe asked each system for a context with no window shown, four ways: a hidden window of
the windowing library; its null platform, which needs no display, with an EGL context; the same with
OSMesa; and ANGLE's EGL with a one-pixel pbuffer and no window.

| Where | Hidden window | With no window at all |
| --- | --- | --- |
| macOS, the user's session | GPU (Apple M5 Max, OpenGL 4.1 on Metal) | a CGL context, no window (the device tests already use it); EGL and OSMesa are not installed |
| Fedora, its desktop | GPU (virgl) | the null platform and EGL: GPU (virgl) |
| Fedora, no `DISPLAY` nor `WAYLAND_DISPLAY` | the windowing library does not start: "Failed to detect any supported platform" | the null platform and EGL: the GPU (virgl, through the render node `/dev/dri/renderD128`); with `LIBGL_ALWAYS_SOFTWARE=1`, llvmpipe, OpenGL 4.6 core |
| Windows, the user's session | GPU (Parallels' driver) | — |
| Windows, session 0 (a service's, or SSH's) | the windowing library starts and its events run; making the window's OpenGL context ends the process (an access violation in `ntdll`) | ANGLE's pbuffer: OpenGL ES 3.0 on Direct3D 11, on the GPU |

OSMesa is installed on none of them. Not measured: macOS with no user session at all.

## 2. Decision

- **No second rasterizer.** A picture with no window is drawn by the same `GlCanvas`, through a
  context the backend owns, on the same profile as the windows.
- **The backend's own context** (`OffscreenContext`), made on the first picture no window can draw
  and kept until the backend stops, with a canvas of its own:
  - macOS: CGL, accelerated, else Apple's software renderer by its id;
  - Linux: a hidden window of the windowing library, which with no display runs on its null platform
    and asks EGL for an OpenGL 3.3 core context;
  - Windows: a hidden window's OpenGL context where the process draws with the system's OpenGL;
    ANGLE's context on a pbuffer where it draws with ANGLE, and where it has no display. If a window
    moves the process onto ANGLE after the context was made, the context is forgotten and made again
    on ANGLE.
  A picture is still drawn first in the frame's own context inside a frame, then in an open window's;
  the backend's context comes last.
- **A backend without a display.** `limn.backend.headless`: `true` starts so anywhere; `false` never
  (a machine with no display fails to start, as before); `auto`, the default, starts so on Linux with
  neither `DISPLAY` nor `WAYLAND_DISPLAY`, in Windows' session 0 (read through
  `ProcessIdToSessionId`), and on any machine where the windowing library finds no display to start
  on. Such a backend runs the windowing library's null platform (its own on Windows, whose events run
  in session 0), skips the system's menu bar on macOS, answers `canOpenWindows()` false, refuses a
  window with an `IllegalStateException` that says why and how to change it, answers no tray
  (`TrayPeer.NONE`), and draws pictures and documents. Its event loop runs with no window when told to
  (`LoopLifetime.UNTIL_QUIT`), as a tray application's does.
- **Not now: drawing off the UI thread.** A context can be made current on another thread, but the
  canvas reads the font store, the shaper and the glyph atlas, which are the UI thread's. A picture is
  drawn on the UI thread, and a document's bytes are written on a worker as before.

## 3. As built

`OffscreenContext`, `WindowsSession`, `EglContext.onAnglePbuffer`, `AngleFallback.takeWithoutWindows`;
`LwjglBackend` decides, starts, refuses and draws as section 2 says; `Backend.canOpenWindows()`.

**Verified** with `HeadlessDrawing`, a program that starts the backend, opens no window, draws an
image with `Images.render` (a disc, an eraser's hole in it) and writes a document whose layer an
eraser crosses (a Porter-Duff layer, which the PDF holds as a picture the backend drew), then checks
the pixels and the file:

| Where | Mode | Context it drew with | Result |
| --- | --- | --- | --- |
| this Mac | forced without a display | CGL, Apple M5 Max | OK |
| this Mac | with its display, no window | CGL, Apple M5 Max | OK |
| Fedora | no display (auto) | virgl, Mesa 26.2.4 | OK |
| Fedora | no display, software only | llvmpipe | OK |
| Fedora | its desktop, no window | virgl | OK |
| Windows 11 | session 0 over SSH (auto) | ANGLE on Direct3D 11 | OK |
| Windows 11 | the user's session, no window | Parallels' driver, OpenGL 4.1 | OK |

`HeadlessBackendTest` runs the program in a JVM of its own both ways on the build machine, and holds
which machines start without a display by themselves.

**A lost device** (ADR 082). The backend's context is lost with the windows' at a virtual machine's
resume, and on Windows the windows' contexts are made again with the system's OpenGL unloaded from the
process, which a context left holding it would keep loaded. So the loss watch lets the backend's
context go at every sign that the machine was away, windows open or none, and before every rebuild;
the context is made again on the next picture, and ANGLE's display with it where no window holds it.
`OffscreenAcrossRebuild`, a program run on Windows 11 in the user's session with
`-Dlimn.backend.rebuildContextAfter=1`, draws a picture with no window open, opens a window, has every
context made again by the path a loss takes, and draws a second picture after the window closes: the
windows' contexts were made again on the first attempt, `opengl32.dll` unloaded, and both pictures
were right.
