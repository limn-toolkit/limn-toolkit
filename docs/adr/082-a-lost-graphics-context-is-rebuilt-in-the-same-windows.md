# ADR 082: A lost graphics context is rebuilt in the same windows

- **Status:** **Proposed 2026-10-29**, at the owner's request after a native image of the Showcase
  was found black and unresponsive on the Windows guest when he resumed its virtual machine; every number in §0 was
  measured that day on that guest, with him suspending and resuming it thirteen times; the step
  that brings the system's OpenGL back (§1.3, step 1) follows his suspicion that something old was
  still drawing, which the measurements confirmed. §6 holds the questions.
- **Date:** 2026-10-29
- **Scope:** what the toolkit does when the graphics context behind its windows stops drawing
  while the process runs: a virtual machine resumed, a driver reset or updated, a GPU removed. It
  covers detecting it, rebuilding the windows' drawing in the same native windows, and choosing
  between the system's OpenGL and ANGLE to rebuild it on.
- **Changes:** ADR 080 and the fallback of `AngleFallback`, which take ANGLE only at startup and
  for the whole process (`Gl.java`: "one profile per process"); this record lets a running process
  move onto it.
- **Compatibility:** no API change is proposed (§6, question 3). In behaviour, an application that
  stayed black until killed draws again.

## 0. What happened, and what was measured

### 0.1 The incident

On 2026-10-29 the owner resumed the Windows guest, suspended overnight, and found the
Showcase's native executable black; it closed only from the Task Manager, and Windows logged an
"Application Hang" ("Top level window is idle") for it at 13:35:55. The executable was the one
built from bbdc066b, started by him at 00:21. At that resume the guest's clock came back six hours
ahead and was set back at 13:35:02.

### 0.2 Measured on the Windows guest

The virtual machine's OpenGL there is 4.1 compatibility, forwarded to the host's Metal,
without `KHR_robustness` or `ARB_robustness`. Each row was one suspension of about a minute, made
by the owner, with the probes kept in `.claude/pending/2026-10-29/graphics-loss/` and a sampler
reading each window's composed pixel and the screen's.

| What was running | After the resume | 
| --- | --- |
| The Showcase on the JVM and the native executable side by side | Both black, both answering messages (`IsHungAppWindow` false, `WM_NULL` answered), both closed normally by `WM_CLOSE`; still black after `InvalidateRect` and after a resize, which draws a whole frame. The JVM's UI thread idle in `glfwWaitEvents`. A **new process** drew at once. Not native-image: the JVM is the same |
| OpenGL: clear to a colour, read one pixel back, swap | No error (`glGetError` 0, no reset status to ask), and **the pixel reads back `000000`** in every context made before the resume |
| A new WGL context on the same window's DC | Made current, then `GL_INVALID_FRAMEBUFFER_OPERATION` and garbage read back |
| A new GLFW window in the same process | Reads its colours back correctly, **and is black on the screen** |
| `glfwTerminate`, `glfwInit`, two new windows | They flash white as they are made and are black from their first frame |
| ANGLE on Direct3D 11 (the Showcase with `-Dlimn.backend.angle=force`) | Black; a resize left a white area; GLFW reported `EGL_CONTEXT_LOST` ("The application must destroy all contexts and reinitialise") on every `eglMakeCurrent` |
| ANGLE through EGL directly, on a GLFW window that has no context of GLFW's | `eglSwapBuffers` returned false with `EGL_CONTEXT_LOST`; destroying the context and surface, `eglTerminate`, and building display, surface and context again **on the same window** drew again, 76 ms after the loss |
| OpenGL first on a GLFW window; on a pixel reading back black, GLFW's context let go (not destroyed), LWJGL's GL binding destroyed, ANGLE through EGL on the same window | **Drew again**, 170 ms after the loss was read |
| `PrintWindow` with `PW_RENDERFULLCONTENT`, and the screen's pixel, on each probe window | Matched the colour drawn before the resume; after it, `000000` for the old windows **and for the new one whose OpenGL read its colours back**, the black the owner saw |
| Everything of OpenGL torn down (windows destroyed, LWJGL's binding destroyed, `glfwTerminate`), modules listed | `opengl32.dll` left the process by its own count; **the driver's OpenGL library stayed loaded**, alone. That is what had survived every attempt above (the owner's suspicion: something old still drawing) |
| The same, then that library freed once by hand, and a new window | **The system's OpenGL presents again** |
| The same without freeing it | The process ended during the reload |
| One window kept with the toolkit's own context; the context deleted, the driver's OpenGL library and its Direct3D user-mode driver freed, a new context on the same window | No context could be made: the window's pixel format belongs to the driver that was unloaded |
| The context on a child window covering the top-level; on the loss the child destroyed (which unloaded the Direct3D user-mode driver), the OpenGL library freed, a new child and context | **Presents again**, the top-level window untouched |
| **The top-level window living as the toolkit's do, with GLFW's context; on the loss GLFW's context deleted, LWJGL's binding destroyed, `opengl32.dll` and the driver's library freed once each, then a child window with a new context** | **Presents again** (screen pixel), `opengl32.dll` reloaded at the same address, so the pointers GLFW kept stay valid. Without freeing the two libraries: black |

Two more facts from the same day. Restarting the display adapter (`pnputil /restart-device`, close to
a driver reset) killed the JVM and the executable alike inside the virtual machine's OpenGL
driver, during `SwapBuffers`: nothing in the process can answer that. A jump of the guest's clock
by +19 h and −6 h with nothing suspended changed nothing. The hang of §0.1 was not reproduced: in
thirteen suspensions of one to five minutes the windows went black and stayed responsive.

### 0.3 What the measurements decide

- On this driver nothing in OpenGL says the context is gone. A colour read back says it for the
  contexts that existed; for a context made afterwards it lies.
- OpenGL does not come back while the driver's OpenGL library stays loaded: the new context, the
  new window and GLFW started over all kept it. It comes back once that library is unloaded and a
  surface that never belonged to it is drawn on, a child window, with the top-level window kept.
  A process that started over would also draw, and the owner ruled that out.
- ANGLE says it (`EGL_CONTEXT_LOST`, `GL_EXT_robustness`), and comes back in the same native window
  once EGL is built again; and a window whose OpenGL died takes ANGLE afterwards.
- What the system composed for a window (`PrintWindow`) is what the user sees, so it can verify a
  rebuild where OpenGL's own read-back cannot.

## 1. Decision

### 1.1 The windows stay; their drawing is rebuilt

A loss never closes, recreates or moves a native window. Its handle is where the screen reader's
provider, the system's drag and drop, the input method, the tray's owner and the menus are
attached, and a new window would drop all of them and the position the user gave it. What is
rebuilt is the drawing: the context, its surface on the same window, and every GPU object.

### 1.2 Detecting a loss

- **ANGLE, and a system OpenGL with robustness:** contexts are made asking to lose the context on
  reset, and the reset status is read once a frame; ANGLE's `EGL_CONTEXT_LOST` from a swap or a
  make-current counts the same. Reading the status costs nothing and stalls nothing.
- **A system OpenGL without robustness**, as the virtual machine's: a colour read back from one pixel, which
  stalls the GPU and so is never taken every frame. It is taken when something says the machine
  may have been away: the wall clock moving more than a few seconds further than the monotonic one
  between two turns of the loop (310 s against no gap, on one resume), the system's resume
  (`WM_POWERBROADCAST`), a display change (`WM_DISPLAYCHANGE`), a session connecting or
  disconnecting (`WM_WTSSESSION_CHANGE`); and on the first frame after the window is restored from
  minimized.

### 1.3 Rebuilding: the system's OpenGL first, then ANGLE

As the owner asked, the toolkit first tries to stay where it was:

1. **On the system's OpenGL, through a child surface:** every context deleted, GLFW's included;
   LWJGL's binding destroyed; `opengl32.dll` and the driver's OpenGL library freed if they are
   still in the process; then, in each top-level window, a child window covering its client area,
   disabled so that the input stays with the top-level, and a new context on it; every GPU object
   rebuilt; one whole frame drawn in a colour the toolkit chooses and the screen's pixel read where
   the window shows. The screen's colour is the verdict, not OpenGL's read-back, which §0.2 shows
   lying; `PrintWindow` read a child's static background at times, so it is used only for a window
   that is covered, with a child class that paints nothing. Until a loss, nothing of this exists:
   windows draw on GLFW's context as today.
2. **On ANGLE, when the first fails or cannot be verified:** the toolkit's OpenGL binding is
   destroyed (LWJGL frees its tables only then, which is what allows the switch), ANGLE is brought
   up through EGL on each existing window, and the process stays on ANGLE from then on (§6,
   question 2).
3. **When ANGLE was the context lost:** EGL is torn down and built again on the same windows, as
   measured.

If none succeeds the toolkit logs why at `SEVERE`, keeps the windows and keeps trying at each of the
signals of §1.2. It never ends the process.

On macOS the same ladder holds with ANGLE on Metal (`CALayer` as EGL's window); on Linux, which
has no ANGLE in this toolkit, only step 1 exists, verified by reading back, since X11 and Wayland
have no `PrintWindow` the toolkit can trust without measuring it (§6, question 1).

### 1.4 What is rebuilt, and from what

Eighteen classes of the backend make GPU objects. After a loss their handles are forgotten
without a GL call, since the objects are gone with the context, and each is made again the next
time it is needed:

- shader programs, compiled again (ANGLE's program cache keyed by the renderer it was compiled for);
- the glyph atlas, rasterised again from the fonts on demand;
- image textures, uploaded again from the `Image`'s pixels, which it keeps (`ImageTextureCache`);
- meshes, from the vertex data `GlMesh` keeps; shadow maps, layers, backdrops and render targets,
  which are made per frame or per size and need nothing kept;
- video textures, from the next decoded picture; a picture decoded in hardware (IOSurface on macOS,
  a Direct3D texture under ANGLE) is re-imported from the decoder's next frame.

The scene is untouched: the next frame is a whole frame (buffer age 0), as after a resize.

### 1.5 The application

Nothing is asked of it: the toolkit owns every GPU object an application can reach through its API
(images, surfaces from `Expr`, 3D content, video). A loss and its outcome are logged at `WARNING`,
with the route taken.

## 2. Alternatives considered

- **Restarting the process,** which §0.2 shows works: ruled out by the owner.
- **A new native window:** measured black on this driver while its OpenGL library stayed loaded
  (§0.2), and it drops every attachment §1.1 lists.
- **A child surface from the start, on every window:** measured working, and it would make step 1
  of §1.3 only a matter of replacing the child; not chosen, because it changes every window on
  every machine (transparency, opacity, the input method's position, the pinch) for a case most
  never meet, when §0.2 shows the child can come at the loss.
- **Freeing the driver's library by hand** is the least comfortable part of step 1: the toolkit
  unloads, behind `opengl32.dll`, a library `opengl32.dll` loaded, once each library's count says
  nothing else holds it. It is done only after every context is deleted, and step 2 stands behind
  it if a driver takes it badly.
- **Going to ANGLE directly, without trying the system's OpenGL:** simpler, and on this driver
  just as good. Not chosen because the owner asked for the system's OpenGL first, and because on
  drivers with robustness a reset context is meant to be replaced in place, keeping the faster and
  more capable path; §1.3 tries it and verifies it, so a driver that lies costs one failed attempt.
- **Doing nothing:** an application in a virtual machine, the way many corporate desktops run on a
  Mac, goes black at every resume until killed.

## 3. What it costs

- **Rebuildable GPU objects (§1.4):** the largest part. Eighteen classes learn to forget their
  handles and make them again; the renderer gains a generation number that every cached handle is
  checked against. Testable on any machine by forcing a loss: destroying the context under a live
  scene and asking for a frame.
- **Contexts and child windows the toolkit makes itself:** GLFW makes a window and its context
  together and cannot give an existing window another one, so the rebuilt contexts (WGL on a child
  window, EGL on the top-level) are the toolkit's, through LWJGL's WGL and EGL bindings, with
  GLFW's context deleted under it; the swap interval, the pixel format and the window's opacity
  and transparency are then the toolkit's to set on the child. Unloading the two libraries is a
  few lines; proving it on more than one driver is §4's.
- **Moving a running process from OpenGL to OpenGL ES:** `Gl.use(GlesGl)` after start, the shaders
  generated again for GLSL ES (`GlslCodegen` already writes both), every capability cache cleared.
- **Detection:** a clock comparison per loop turn, three window messages on Windows, the reset
  status once a frame, one pixel read back only on a signal.
- **The verdict:** the screen's pixel inside the window after one frame, or `PrintWindow` for a
  covered window; Windows only.

## 4. Verification, when built

- On the Windows guest, with the owner suspending it: the Showcase on the system's OpenGL, on ANGLE
  by force, and the native executable, each drawing again after the resume without a click, with
  the route in the log; a screen reader still reading it and a drag still working afterwards.
- Forcing a loss in tests on every system, which the check runs: the scene draws the same pixels
  after a rebuild as before.
- On a physical Windows machine, a driver reset (the graphics driver restarted with
  Windows+Ctrl+Shift+B) and, if one is at hand, a GPU switched off in the device manager.
- A remote desktop session connected to and disconnected from the guest (§6, question 5).

## 5. What this is not

- A driver that crashes the process during a reset, as the virtual machine's did on an adapter restart:
  nothing in the process runs afterwards.
- The hang of §0.1: not reproduced. A rebuild removes the black window that led to it; whether the
  process also stopped answering after thirteen hours suspended stays unknown, and a run left
  suspended overnight is part of §4.

## 6. Questions for the owner

1. **Which systems.** Windows is measured; macOS rarely loses a context on sleep but does on an
   external GPU removed; Linux with one vendor's proprietary driver loses GPU memory on suspend unless
   the driver is told to keep it. **Recommendation:** build §1.2–§1.4 for all three, the ANGLE step for
   Windows and macOS, and measure macOS and Linux on the guests before claiming them.
2. **Once on ANGLE, stay there?** **Recommendation:** yes, for the rest of the process: the
   system's OpenGL already failed once in it, and switching back would need its own verification
   and a second rebuild.
3. **An event for the application?** **Recommendation:** none: it owns no GPU object the toolkit
   does not rebuild; the log says what happened.
4. **When to look without a signal.** **Recommendation:** only on the signals of §1.2; a periodic
   read-back would stall the GPU on machines that never lose anything.
5. **Remote desktop.** Connecting to the guest from the Mac needs a client there and his Windows
   password, both his. **Recommendation:** one run in stage 0, before building, since a session
   change is the most common way a corporate desktop changes GPU.
