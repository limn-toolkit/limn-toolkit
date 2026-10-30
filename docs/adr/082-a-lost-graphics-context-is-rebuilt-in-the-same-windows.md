# ADR 082: A lost graphics context is rebuilt in the same windows

- **Status:** **Accepted 2026-10-29 and built 2026-10-30** (§8): the owner took every
  recommendation of §6 and asked for question 5's measurement first, which §7 records. Proposed the same day, at the owner's request after a native image of the Showcase
  was found black and unresponsive on the Windows guest when he resumed its virtual machine; every number in §0 was
  measured that day on that guest, with him suspending and resuming it some thirty times; the step
  that brings the system's OpenGL back (§1.4, step 1) follows his suspicion that something old was
  still drawing, which the measurements confirmed. §6 holds the questions.
- **Date:** 2026-10-29
- **Scope:** what the toolkit does when the graphics context behind its windows stops drawing
  while the process runs: a virtual machine resumed, a driver reset or updated, a GPU removed. It
  covers detecting it, rebuilding the windows' drawing in the same native windows, and choosing
  between the system's OpenGL and ANGLE to rebuild it on.
- **Changes:** who makes the OpenGL contexts: GLFW today, the toolkit on all three systems under
  this record (§1.1), GLFW keeping the windows. And ADR 080 and the fallback of `AngleFallback`,
  which take ANGLE only at startup and for the whole process (`Gl.java`: "one profile per
  process"); this record lets a running process move onto it.
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
| One window recovering at every suspension, the owner suspending it again and again, some in quick succession, on the x64 JDK (emulated on that ARM guest) and the ARM64 one side by side | The processes died at a reload whenever `opengl32.dll` came back **at another address**: WGL was being called through pointers LWJGL resolved at the first load (each crash report's address inside the library's old range). With every WGL entry point looked up again after each load: four recoveries on each JDK, the library moving twice, quick suspensions included; the ARM64 JDK back in 0.6 to 1.7 s, the emulated one in 1.1 to 5.5 s |
| The same windows closed at the end | **Both processes crashed in `glfwDestroyWindow`**: GLFW deletes the context it made through the `wglDeleteContext` it looked up at the first load, in the library's old range |
| **GLFW making the windows with no context (`GLFW_NO_API`), the probe making every WGL context itself (3.3 core forward-compatible through `wglCreateContextAttribsARB`, vsync, a second window sharing the first's objects), entry points looked up after every load** | **Every loss recovered on both JDKs, main and popup together, about 2.2 to 2.6 s including a 2 s settle, the library moving on the first reload; and closing the windows ended both processes cleanly**, no crash report |
| The same with no settle: rebuilt at once on the loss, verified by a pixel read back on the new context (it cannot lie here, the old driver being gone), tried again 0.1 s later if not | **Every loss back on the first attempt, 0.3 to 0.8 s after it was read**, on both JDKs, the screen showing the colours again the next second |
| The Ubuntu guest (virgl over the same host GPU, Mesa, X11), the Fedora guest (virgl, Mesa 26, KDE on Wayland, where the robust context asked for was refused) and the macOS guest (the toolkit already on ANGLE over Metal, the system's OpenGL offering no pixel format there), the Showcase and a probe on each, suspended and resumed by the owner | **No loss on any**: the colours read back right through every resume, Mesa's reset status (it has `ARB_robustness`) stayed 0, nothing flickered. The clock comparison of §1.3 fired on Ubuntu (the wall clock off the monotonic one by six hours, then by five seconds) |
| A child surface of the system's static class | White while the window was dragged: the class paints its background. A class of the toolkit's own (no background, transparent to hit testing, drawing again on `WM_PAINT`) did not |

Two more facts from the same day. Restarting the display adapter (`pnputil /restart-device`, close to
a driver reset) killed the JVM and the executable alike inside the virtual machine's OpenGL
driver, during `SwapBuffers`: nothing in the process can answer that. A jump of the guest's clock
by +19 h and −6 h with nothing suspended changed nothing. The hang of §0.1 was not reproduced: in
some thirty suspensions of a few seconds to five minutes the windows went black and stayed responsive.

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
- Unloading and loading `opengl32.dll` again moves it, so nothing may call it through a pointer
  kept from an earlier load: not LWJGL's WGL class and not GLFW, which keeps its own for the
  contexts it makes and calls them when a window is destroyed. A context the toolkit makes itself,
  on a window GLFW made without one, has no such pointer anywhere.

## 1. Decision

### 1.1 GLFW makes the windows, the toolkit makes the contexts

On all three systems, as the owner asked for one design rather than one per system, GLFW makes
every window with no context (`GLFW_NO_API`) and the toolkit makes and owns every OpenGL context:
WGL on Windows (`wglCreateContextAttribsARB`, 3.3 core forward-compatible, shared between the
windows of the process), NSOpenGL on macOS (where `MacSoftwareGl` already makes its own), and EGL,
or GLX on an X11 server without EGL, on Linux. It looks up the entry points it calls each time the
library is loaded and keeps none across a reload. The swap interval, the pixel format, a
transparent window's alpha, and which context is current are the toolkit's from then on, on every
machine and not only after a loss. ANGLE was already the toolkit's own EGL; it stays so.

### 1.2 The windows stay; their drawing is rebuilt

A loss never closes, recreates or moves a native window. Its handle is where the screen reader's
provider, the system's drag and drop, the input method, the tray's owner and the menus are
attached, and a new window would drop all of them and the position the user gave it. What is
rebuilt is the drawing: the context, its surface on the same window, and every GPU object.

### 1.3 Detecting a loss

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

### 1.4 Rebuilding: the system's OpenGL first, then ANGLE

As the owner asked, the toolkit first tries to stay where it was:

1. **On the system's OpenGL, through a child surface:** at once, with no settle (the owner did
   not want one, and §0.2 measured none needed); every context deleted; LWJGL's binding
   destroyed; `opengl32.dll` and the driver's OpenGL library freed if they are still in the
   process; then, in each top-level window, a child window covering its client area, transparent
   to hit testing so that the input stays with the top-level, and a new context on it; every GPU
   object rebuilt, with every entry point looked up again; one whole frame drawn in a colour the
   toolkit chooses and read back. The read-back is trusted here because the driver that lied in
   §0.2 has left the process; where the window shows, the screen's pixel confirms it, and
   `PrintWindow`, which read a child's static background at times, is not used. The child is of
   the toolkit's own class: no background, drawing the current frame again on `WM_PAINT`. Until a
   loss there is no child: the toolkit's context is on the top-level window. A rebuild that does
   not verify is tried again a tenth of a second later, since a machine suspended again in the
   middle of one is the case that needs it.
2. **On ANGLE, when the first fails or cannot be verified:** the toolkit's OpenGL binding is
   destroyed (LWJGL frees its tables only then, which is what allows the switch), ANGLE is brought
   up through EGL on each existing window, and the process stays on ANGLE from then on (§6,
   question 2).
3. **When ANGLE was the context lost:** EGL is torn down and built again on the same windows, as
   measured.

If none succeeds the toolkit logs why at `SEVERE`, keeps the windows and keeps trying at each of the
signals of §1.3. It never ends the process.

On macOS the same ladder holds with ANGLE on Metal (`CALayer` as EGL's window); on Linux, which
has no ANGLE in this toolkit, only step 1 exists, verified by reading back, since X11 and Wayland
have no `PrintWindow` the toolkit can trust without measuring it (§6, question 1).

### 1.5 What is rebuilt, and from what

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

### 1.6 The application

Nothing is asked of it: the toolkit owns every GPU object an application can reach through its API
(images, surfaces from `Expr`, 3D content, video). A loss and its outcome are logged at `WARNING`,
with the route taken.

## 2. Alternatives considered

- **Restarting the process,** which §0.2 shows works: ruled out by the owner.
- **A new native window:** measured black on this driver while its OpenGL library stayed loaded
  (§0.2), and it drops every attachment §1.2 lists.
- **A child surface from the start, on every window:** measured working, and it would make step 1
  of §1.4 only a matter of replacing the child; not chosen, because it changes every window on
  every machine (transparency, opacity, the input method's position, the pinch) for a case most
  never meet, when §0.2 shows the child can come at the loss.
- **Freeing the driver's library by hand** is the least comfortable part of step 1: the toolkit
  unloads, behind `opengl32.dll`, a library `opengl32.dll` loaded, once each library's count says
  nothing else holds it. It is done only after every context is deleted, and step 2 stands behind
  it if a driver takes it badly.
- **Going to ANGLE directly, without trying the system's OpenGL:** simpler, and on this driver
  just as good. Not chosen because the owner asked for the system's OpenGL first, and because on
  drivers with robustness a reset context is meant to be replaced in place, keeping the faster and
  more capable path; §1.4 tries it and verifies it, so a driver that lies costs one failed attempt.
- **Doing nothing:** an application in a virtual machine, the way many corporate desktops run on a
  Mac, goes black at every resume until killed.

## 3. What it costs

- **Rebuildable GPU objects (§1.5):** the largest part. Eighteen classes learn to forget their
  handles and make them again; the renderer gains a generation number that every cached handle is
  checked against. Testable on any machine by forcing a loss: destroying the context under a live
  scene and asking for a frame.
- **The toolkit's own contexts on three systems (§1.1):** the second largest part, and the one
  that touches every machine. What GLFW does for the toolkit's contexts today is done by the
  toolkit: on Windows the pixel format with alpha, `wglCreateContextAttribsARB` through a first
  throw-away context, sharing, `wglSwapIntervalEXT`, the transparent window through the system's
  compositor; on macOS NSOpenGL, of which `MacSoftwareGl` already holds most; on Linux EGL on X11
  and Wayland (`wl_egl_window`) and GLX where EGL is missing. The probe of §0.2 did the Windows
  part in about two hundred lines. Every capability GLFW gave (sRGB, the double buffer, the
  context's version) gets a test on each system, since a mistake there shows on machines that
  never lose anything.
- **Child windows and the driver's libraries (Windows):** a window class, one child per window at
  a loss, two libraries unloaded. Proving it on more than one driver is §4's.
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

1. **Which systems recover.** The owner decided the contexts are the toolkit's on all three
   (§1.1). A loss happened only on the Windows guest; the Ubuntu and macOS guests lost nothing at a
   resume (§0.2), so the lab has no loss to test a rebuild against there. macOS loses a context
   when an external GPU is removed; Linux with one vendor's proprietary driver loses GPU memory on
   suspend unless the driver is told to keep it. **Recommendation:** detection and rebuilding
   (§1.3–§1.5) written once for all three, with Linux's reset status read every frame since Mesa
   has it; tested on macOS and Linux by forcing a loss in tests (§4), and claimed for real
   hardware there only once a machine that loses one has been run.
2. **Once on ANGLE, stay there?** **Recommendation:** yes, for the rest of the process: the
   system's OpenGL already failed once in it, and switching back would need its own verification
   and a second rebuild.
3. **An event for the application?** **Recommendation:** none: it owns no GPU object the toolkit
   does not rebuild; the log says what happened.
4. **When to look without a signal.** **Recommendation:** only on the signals of §1.3; a periodic
   read-back would stall the GPU on machines that never lose anything.
5. **Remote desktop.** Connecting to the guest from the Mac needs a client there and his Windows
   password, both his. **Recommendation:** one run in stage 0, before building, since a session
   change is the most common way a corporate desktop changes GPU.

## 7. The owner's answers, 2026-10-29

"Siga as recomendações, pode implementar", with question 5 measured first:

1. Detection and rebuilding written once for the three systems; macOS and Linux tested by forced
   losses and claimed on real hardware only after a machine that loses a context has been run.
2. Once on ANGLE after a loss, the process stays there.
3. No event for the application; the log says what happened.
4. Looked for only on the signals of §1.3, never periodically.
5. **Measured the same day.** Remote desktop was switched on in the Windows guest for the run and
   off again after it. The owner connected from the Mac with the system vendor's client, and
   session 1 moved from the console to the remote session at 17:03:07 and back at 17:04:01, by
   the session manager's log. Four windows of the probe that owns its contexts (two processes,
   x64 and ARM64) and the Showcase on GLFW's contexts ran through both moves: **no loss** in
   either probe, the colours on screen afterwards, and the Showcase logged only its new monitor
   scale. On this guest a session change does not lose a context; a resume does. During the
   remote session `PrintWindow` read black for every probe window while the owner saw them all
   drawing in the remote window, which is one more reason §1.4 does not take its verdict from it.


## 8. Built, 2026-10-29 and 2026-10-30

On the branch `claude/adr-082-graphics-loss`, in five steps, each seen on the guests before the next:

1. **One seam for the window's context** (`WindowContext`): make current, present, the swap interval,
   the drawable changing, release.
2. **Every GL object forgettable without a GL call** (`contextLost` on the renderer, the canvas, the
   atlas, the image cache, the 3D and video contexts and the application's meshes, textures, render
   targets and video surfaces), made again on first use in the next context. The 3D and video
   contexts stay the same objects, because the application's handles check their owner by identity.
   A mesh now keeps a copy of its indices and a 3D texture a copy of its pixels, the memory §3
   expected. `GlContextLossTest` and `Gl3DContextLossTest` draw in a real second context.
3. **The backend's own contexts** (§1.1): `WglContext` on Windows, `MacSoftwareGl` making the
   accelerated NSOpenGL context on macOS, `EglContext` on Linux and for ANGLE on Windows.
   - On X11 a window GLFW makes with no context gets the default visual, with no alpha, measured; at
     the owner's choice GLFW makes it with an EGL context that only chooses the visual and is never
     made current, and the backend's context, of that configuration, draws on GLFW's surface. On
     Wayland the window has no context of GLFW's.
   - macOS's ANGLE windows are still GLFW's: macOS has no rebuild (below).
4. **Noticing and rebuilding** (`ContextLossWatch`, once a turn of the loop): the clock comparison,
   then a read-back on each window's own context; a reset status read once a frame where the context
   is robust, asked for on WGL, EGL and ANGLE; ANGLE's `EGL_CONTEXT_LOST`. The ladder of §1.4 with no
   settle: the system's OpenGL ten times, a tenth of a second apart, then ANGLE, and ANGLE made again
   when it is the one lost.
   - **A trap the first run found:** the OpenGL runtime subclasses a window when its pixel format is
     set (`opengl32.dll+0x3ced0`), and takes itself out of the chain as it is unloaded only while it
     is the outermost procedure. The UI Automation bridge's procedure sat above it, so the unloaded
     library's address stayed in the chain and both processes crashed creating the child. The
     bridge now steps aside around the unload and back after it; a procedure still above the
     window class's own afterwards would be put right and logged, which no run needed.
5. **Seen** on the Windows guest with the x64 and the ARM64 JDK, the owner suspending it: five
   losses, each back on the first attempt in 136 to 493 ms, closed cleanly. `OwnContextTest` checks
   on every system that the context is the backend's and draws; on Windows its child took all three
   steps (the system's OpenGL made again, the move onto ANGLE, ANGLE made again) on both JDKs; on
   the Ubuntu guest (X11) and the Fedora guest (Wayland) the EGL context was made again in 13 to
   15 ms. The Showcase drew with each kind of context, its translucent popups included.
6. **The native image** (GraalVM, the Showcase on the Windows guest): five losses, each back on the
   first attempt in 132 to 1002 ms, the window drawing again and closed by the owner with its close
   button (exit code 0). Two earlier runs of the same build ended on their own after a rebuild, with
   no report from the system, before the exit code was recorded; none has since, recorded. A rebuild
   can be asked for with no loss, `-Dlimn.backend.rebuildContextAfter=<seconds>`, by the same path,
   to check a build without suspending a machine; the log says when each window has drawn its first
   frame on the new context.
   - **Not this decision's:** after some resumes the guest's title bars come back with the icon and
     no title, the system's Character Map's as well as the Showcase's; the window's text is intact,
     and it returns when the window is activated.

**Not built, or owed.**
- The system's resume, display and session messages of §1.3: the clock, the reset status and
  ANGLE's report are what notice a loss; a session change was measured to lose nothing (§7).
- The read-back on a window restored from minimized.
- A rebuild on macOS, and the macOS ANGLE windows' own context; a loss there is said in the log.
- Real hardware: a driver reset on a physical Windows machine, a Linux machine that loses its
  contexts on suspend. The Showcase on the backend's NSOpenGL context was seen right by the owner
  on a physical Mac, with no loss.
- The thirteen-hour hang of §0.1, still not reproduced.
