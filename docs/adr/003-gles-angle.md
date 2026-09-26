# ADR 003: GL ES 3.0 as the authored profile, ANGLE as the portable context

- **Status:** Proposed; **deferred, not started** (recorded 2026-09-03). §4's facade does not exist
  in the tree, and of §7's phases only Phase 0 does; §8's questions 1, 2, 4 and 5 are as open as
  the day they were written. The one thing §9.1 scheduled ahead of the port — the Wayland track,
  step 4 — landed on its own as ADR 028, because it fixed a defect that existed then, and nothing
  since has needed a context OpenGL could not give. What restarts this is exactly what §9 names: a
  Windows 10/11 guest that answers questions 2 and 4 (R1 and R4 on D3D11), and then §9.1's step 5
  — commit to Phase 1's call-site diff or re-scope the plan to what ANGLE turns out to do. Extends
  ADR 001 route (a); supersedes nothing.
- **Revised 2026-09-03, from a lab that measured what "no usable OpenGL" actually is** on a
  macOS guest, two Linux guests and a Windows guest, and read GLFW's own sources at the commit the
  shipped natives were built from. **The status does not move.** What the lab changed is the
  *value* of this port for one population, not its readiness. It replaced an assumption with a
  number on Windows (§3), split one macOS case into the two different propositions it always was
  and closed the nearer one without ANGLE and without any binary payload (§2.2, §3, §4.3;
  committed as a0fa90c), verified the Linux claim §2.3 had only asserted, and handed §9 a machine
  of the shape §3's first item names — a working GPU and no usable GL driver — though on macOS
  rather than Windows. **Nothing about ANGLE itself was settled**: no ANGLE library was obtained,
  placed or loaded on any machine, so R1, R4 and questions 1, 2, 4 and 5 stand exactly where they
  stood. Every figure below was measured on a machine unless it says otherwise; where it is
  reasoning rather than a measurement, it says so in the same breath.
- **Date:** 2026-07-30, the day §9.1's starting sequence was agreed; revised 2026-09-03.
- **Requirements this answers** (as stated by the project owner):
  1. Keep doing everything the backend does today; no feature may be traded for portability.
  2. Reach as many machines as possible: Windows (Windows XP forward if at all possible; state
     what actually is), macOS (early Intel if at all possible), Linux (old X **and** Wayland).
- **Audience:** whoever implements it. Every claim below is either tied to a file:line in this
  repo, or explicitly marked as unverified with the machine needed to settle it. The revision adds
  a third kind: a claim tied to a machine in the lab or to GLFW's own sources at the commit the
  shipped natives were built from, which is named as measured where it was run and as reasoning
  where it was not.

---

## 0. The three findings that decide the shape of this work

**(a) The renderer is not what limits where Limn runs.** For every target OS the binding
constraint is the JVM or the LWJGL/GLFW natives, and both are stricter than any GL requirement.
Windows XP and early Intel Macs are unreachable *before* the question of OpenGL is asked (§2).
Rendering portability buys machines that are already inside the JVM's floor but have a bad, old
or missing OpenGL driver: a large population on Windows, a small one elsewhere (§3).

What "bad, old or missing" means was measured on 2026-09-03, and it is **three conditions rather
than one**, each wanting a different remedy. On macOS an adequate implementation exists and GLFW
refuses it, because its Cocoa path demands acceleration of every pixel format it asks for; the
remedy is to stop refusing, and it costs no binary. On Linux an implementation exists and GLFW
already takes it, because its GLX and EGL paths never ask about acceleration at all; there is
nothing to remedy. On Windows nothing good enough exists in the box, and the remedy is to *supply*
an implementation. Only the third is what this record is about (§3).

**(b) The codebase is already almost entirely inside GL ES 3.0.** 322 GL call sites across 13
files use **79 distinct entry points and 68 constants**, and only **three** entry points fall
outside ES 3.0 core (§1.2). The shaders were written to the intersection subset on purpose
(ADR 001's portability rule is quoted in the shader sources themselves), and the 3D PBR shaders
already compile to `#version 300 es` from the neutral IR, with a test asserting the two profiles
differ only in the header (`GlslCodegenTest:66-73`). This is not a rewrite. It is a binding
switch, three small fixes and a packaging problem.

**(c) The two hard problems are not GL at all.** They are: translucent windows under an
ANGLE/EGL swapchain (the entire popup/dialog look depends on it, and it is unverified here), and
**Wayland cannot position windows**, which invalidates the "popup = separate top-level window at
absolute screen coordinates" model the toolkit is built on, including the popup-follow work that
just landed. Both are addressed in §5 and §6, and neither is discovered by porting shaders.

---

## 1. Requirement 1: parity

### 1.1 What must survive, stated as behaviour

The port is not allowed to cost any of these. Each is listed with the mechanism that carries it,
because that is what a reviewer has to re-check afterwards:

| Behaviour | Mechanism today | ES 3.0 status |
|---|---|---|
| Translucent undecorated windows (popups, glass dialogs) | `GLFW_TRANSPARENT_FRAMEBUFFER` + straight-alpha output | **Risk R1**: depends on the EGL config and the platform swapchain, not on GL |
| One GL context per window, no sharing | `glfwCreateWindow(share=NULL)`, per-window `GlRenderer` | EGL contexts are per-surface; unchanged |
| Per-window vsync (the multi-window pacer) | `glfwSwapInterval` re-asserted per frame after `makeContextCurrent` | `eglSwapInterval` is per-surface; **verify R4** |
| Partial rendering (damage rects) | `glScissor` + `glClear` under scissor (`GlCanvas:210,235`) | core ES 3.0 |
| Real `clearRect` for translucent popups | `glClear` with scissor, writing alpha 0 | core ES 3.0 |
| Glyph atlas | `GL_R8` texture, `glTexSubImage2D`, `GL_UNPACK_ALIGNMENT 1` | core ES 3.0 (sized R8 + format RED) |
| Images / icons | `GL_RGBA8`, mipmaps, `GL_LINEAR_MIPMAP_LINEAR` | core ES 3.0 |
| 3D render-to-texture with MSAA | MSAA renderbuffers + 1:1 `glBlitFramebuffer` resolve (`GlRenderTarget:97-105`) | core ES 3.0; the blit is same-size, color-only, `NEAREST`, which is inside ES 3.0's resolve restrictions |
| Shadow maps | `GL_DEPTH_COMPONENT24` texture, depth-only FBO | core ES 3.0 |
| PBR / IBL | std140 UBOs, `glUniformBlockBinding`, SH9 irradiance, analytic sky | core ES 3.0: **no cubemaps anywhere**, which removes ES's seamless-cubemap gap |
| sRGB | encoded **in the shader**, not via `GL_FRAMEBUFFER_SRGB` | core ES 3.0 (the desktop-only enum is not used; verified: zero occurrences) |
| Screenshot capture | `glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)` from `GL_BACK` | core ES 3.0 (the one guaranteed format combination) |
| GPU frame timers | `GL_TIME_ELAPSED` queries (`GlRenderer:82-120`) | **not in ES 3.0** (gap G2) |
| IME preedit | LWJGL's patched GLFW (`glfwSetPreeditCursorRectangle`) | window-system side; a context change does not touch it |

### 1.2 The three gaps

Everything else in the inventory (Appendix A) is core GL ES 3.0. The exceptions:

| # | Site | Desktop call | ES 3.0 replacement | Cost |
|---|---|---|---|---|
| G1 | `GlShadowMap:36` | `glDrawBuffer(GL_NONE)` | `glDrawBuffers(int[]{GL_NONE})` (the plural form is core ES 3.0) | one line |
| G2 | `GlRenderer:70-120` | `glBeginQuery/glEndQuery/glGetQueryObjectui64(GL_TIME_ELAPSED)` | `EXT_disjoint_timer_query` when present; otherwise report "no GPU time" | capability gate + a degraded footer gauge |
| G3 | `GlRenderer` | `glGetQueryObjecti` (signed variant) | `glGetQueryObjectuiv` | one line, folded into G2 |

G2 is the only one with a user-visible consequence: on a context without the extension the perf
footer's GPU gauge has nothing to show. The gate already half exists (`GlRenderer:70` reads
`caps.OpenGL33 || caps.GL_ARB_timer_query`), so the shape of the code does not change, only the
capability it asks about.

`glReadBuffer(GL_BACK)` and `glReadBuffer(GL_NONE)` both stay: ES 3.0 defines exactly those two
values for the default framebuffer, which is all this code uses.

### 1.3 Shaders

Four hand-written pairs plus one generated family:

| Source | Lines | State |
|---|---|---|
| `canvas.vert` / `canvas.frag` | 42 / 127 | Authored to the intersection subset under ADR 001's rule, quoted at the top of both files. Uses `texture()`, `fwidth`, `gl_FragCoord`, `smoothstep`, layout-qualified attributes, one `out`, all of which are core ES 3.00 |
| `cube.vert` / `cube.frag` | 18 / 12 | Same subset (demo viewport) |
| `mesh_lit`, `mesh_unlit` | 31 total | Same subset, and carry the rule as a comment |
| PBR / shadow / sky | generated | `GlslCodegen` already emits both profiles; `GlslCodegenTest:66-73` asserts the bodies are byte-identical apart from `TargetProfile`'s header |

The port for the hand-written four is therefore **a header swap**, not an edit: replace
`#version 330 core` with `TargetProfile.GLSL_ES_300.header` (`#version 300 es` + `precision highp
float; precision highp int;`). Do it at load time in `ShaderProgram.fromResources` so the file on
disk keeps one version of the truth, and reuse the existing enum rather than defining "what ES
3.00 means" a second time.

Two properties make this work, and both must be preserved by anyone editing shaders later:

- **No `layout(binding = …)` anywhere** (verified: zero occurrences). ES 3.00 has no binding
  qualifier for uniform blocks or samplers; the code assigns them through
  `glGetUniformBlockIndex` + `glUniformBlockBinding` and `glUniform1i`, which is the portable way
  and already what it does.
- **No desktop-only builtins.** `fwidth` is core in ES 3.00 (it needed an extension only in ES
  2.0); `gl_FragCoord`, `texture()`, `smoothstep`, `mix` are core.

### 1.4 Constants are not a portability problem

The 68 GL constants used are numerically identical between desktop GL and ES for every value in
the inventory (`GL_TEXTURE_2D` is `0x0DE1` in both). Only **functions** need dispatching. That
halves the mechanical work in §4.2 and means the constants can live in one plain `int` holder
shared by both paths.

---

## 2. Requirement 2: reach, and its real floor

Every layer below has to accept the machine before the renderer is even consulted:

```
JDK 17 runtime  →  LWJGL 3.4.3 natives  →  GLFW 3.5.1  →  window system  →  EGL/WGL/CGL/GLX  →  driver
```

The two middle versions were LWJGL 3.4.1 and GLFW 3.4 when this was written, and the tables below
still name the older ones where the claim was verified against them. The current pair is not a
guess: each native jar carries the GLFW commit it was built from, all six classifiers carry the
same one, and that commit's own CMake project version is 3.5.1 — which is character for character
the version string the backend logs. Nothing in the paths this record depends on differs between
the two; the pixel-format code quoted throughout §3 is identical in both trees.

### 2.1 Windows

| Layer | Floor | Note |
|---|---|---|
| JDK 17 (`options.release = 17`) | **Windows 10 / Server 2016** supported | Java 8 was the last JDK that ran on XP, and only unofficially |
| LWJGL 3 natives | Windows 7+ | documented; XP was never a LWJGL 3 target |
| GLFW 3.4 | Windows 7+ | |
| ANGLE (current) | Windows 10+ tested | ANGLE follows Chromium's support policy, which dropped Win7/8.1 in early 2023 *(unverified: check the ANGLE build you actually ship)* |
| ANGLE D3D11 backend | feature level 9_3 hardware | this is the reach win: GPUs far older than any GL 3.3 driver |

**Verdict on Windows XP: not reachable, and OpenGL is not why.** A JDK that runs on XP tops out
at Java 8; LWJGL 3 does not target XP; GLFW 3.4 does not target XP. Reaching XP is not a port, it
is a second product: Java 8 bytecode, a different windowing/GL binding (LWJGL 2, JOGL or custom
JNI), an ANGLE build old enough to still contain the D3D9 renderer *(removed from current ANGLE,
date unverified)*, and (the expensive part) **GL ES 2.0 instead of 3.0**, which has no uniform
blocks, so the entire std140 Frame/Lights/Material design of the 3D subsystem would need a second
uniform path. The 2D canvas would survive ES 2.0 with modest work; the 3D would not. Recommend
recording XP as out of scope rather than half-supporting it.

**Practical Windows floor after this port:** Windows 10+ supported; Windows 7 SP1 / 8.1 likely
working but unsupported (JDK 17 runs there in practice). The gain over today is not the OS
version; it is every Win10/11 machine whose OpenGL driver is missing, stuck at 1.1 (RDP, VM,
Citrix), or ARM (no GL driver at all), all of which D3D11 covers. "Stuck at 1.1" was an assumption
when that sentence was written and is a measurement now; §3 records what the in-box generic
implementation offers, and why nothing short of shipping an implementation reaches it.

### 2.2 macOS

| Layer | Floor |
|---|---|
| JDK 17 x86_64 | macOS 10.13+ *(11+ for aarch64)* |
| LWJGL 3.4 natives | 10.13+ era toolchain |
| ANGLE Metal backend | macOS 10.14+ (Metal 2) |
| Apple's own GL | 4.1, deprecated since 10.14, still shipping |
| Apple's software renderer, where no accelerated one exists | also 4.1 core (`4.1 APPLE-23.1.1`), measured in a VM |

**Verdict on early Intel Macs: not reachable, and again not because of GL.** A 2006–2008 Intel
Mac tops out at macOS 10.6–10.11; no JDK 17 exists for it. The realistic floor is 2012-era
hardware on 10.13+, and on all of that hardware Apple's GL 4.1 already satisfies GL 3.3.

**A Mac can have no usable GL driver, and that case is now closed without ANGLE.** Measured in a
macOS 26.6.2 arm64 guest: Metal has a real paravirtual device, and OpenGL has only `Apple
Software Renderer`, which reports `kCGLRPAccelerated = 0`. GLFW's Cocoa path puts
`NSOpenGLPFAAccelerated` into every pixel format it asks AppKit for, unconditionally and first, so
every request is unsatisfiable and `glfwCreateWindow` fails with `GLFW_FORMAT_UNAVAILABLE` — "NSGL:
Failed to find a suitable pixel format". No window hint, init hint or environment variable reaches
that attribute; the hint sweep on the guest failed on every knob and succeeded only with
`GLFW_NO_API`. Behind the refusal is a full **4.1 core** context in which a real `#version 330
core` program compiles, links and draws, so what the machine offers was always ample for the 3.3
core this toolkit asks for. Limn now takes it: on a macOS window whose creation fails that way,
the backend retries for the window alone and builds the `NSOpenGLContext` itself, without the
attribute (a0fa90c; see §4.3 and `MacSoftwareGl`). Verified live in the guest — kitchen sink,
forms, a second native window for a modal dialog, vsync throttling against the virtual display.

**What ANGLE is worth on macOS: insurance against removal.** The paragraph above and this one used
to be one item, and they are two propositions with two urgencies. A Mac whose GL *driver* is
unusable is handled today, for free, by not demanding acceleration. ANGLE remains the escape route
for the day Apple *removes* GL — a different event, not yet scheduled, and the one against which a
translation layer is the only cover. The record's older second reason, a way around Apple driver
bugs, is untouched by this lab and neither confirmed nor closed by it: what was measured is a
driver *refused*, not a driver at fault, and a hand-built context does not route around a bug in a
working one. macOS is also the one backend this project can test on the developer's machine, which
makes it the right *bring-up* platform even though it is now the weakest *business* case of the
three.

### 2.3 Linux

| Layer | Floor |
|---|---|
| JDK 17 | glibc 2.17+ (RHEL 7 / Ubuntu 14.04 era) |
| GLFW X11 backend | libX11 + **XRandR 1.3** (2009), Xinerama, Xcursor, Xi |
| GLFW Wayland backend | **already in the shipped binary** (verified, see below). One `libglfw.so` carries X11 and Wayland; the platform is chosen at runtime. Needs libwayland-client/cursor/egl and libdecor present on the guest |
| Mesa | GL 3.3 on essentially all hardware since 2012, and **llvmpipe** in software for the rest |

**Old X: the limit is GLFW's XRandR 1.3 requirement, not GL.** An X server older than ~2009 is
out regardless of renderer. Everything newer already has Mesa, so on Linux ANGLE adds almost
nothing: where GL 3.3 is missing, `LIBGL_ALWAYS_SOFTWARE=1` (llvmpipe) already works and is
already used by `scripts/screenshot.sh` in CI.

**That last clause was an assertion when it was written; it is a measurement now.** Run on
2026-09-03 with the unmodified fat jar, on two distributions and three windowing paths: Ubuntu
24.04 on X11 gives **OpenGL 4.5 core** under `llvmpipe (LLVM 20.1.2, 128 bits)`, and Fedora KDE 44
gives **4.6 core** on XWayland and on native Wayland alike. Each is *higher* than the accelerated
driver on the same machine, which is virgl at 4.0, because Mesa's software rasteriser implements
more of the spec than the paravirtual one does; all four of §4.4's capability gates answer yes
under it, and a live window renders. Not one line of Limn changed for any of it. The reason is in
GLFW rather than in Mesa: its GLX and EGL paths reject a config on three grounds each — colour
type, window bit, doublebuffer or renderable API — and never read `GLX_CONFIG_CAVEAT` or
`EGL_CONFIG_CAVEAT` at all, while the shared closest-match scorer they both feed has no
acceleration field to filter on. The `True` passed to `glXCreateNewContext` is GLX's *direct*
flag, not an acceleration one; `glxinfo` on the same machine one variable apart reports `direct
rendering: Yes` with `Accelerated: no`, which is precisely the combination GLX accepts and Cocoa
rejects. **Use the right switch:** `LIBGL_ALWAYS_SOFTWARE=1` forces software everywhere tried;
`GALLIUM_DRIVER=llvmpipe` on its own is ignored on the Ubuntu guest, the only one it was tried on,
because virgl is a real Gallium driver selected for the virtio-gpu device and that variable does
not override the choice.

**Reasoning, not a measurement:** that a Linux box with *no* driver at all falls back to llvmpipe
by itself. Both guests have virgl, so software rendering was *forced* with the variable rather than
discovered. Mesa's swrast fallback with no DRI driver present is standard behaviour and
`swrast_dri.so` is installed on both guests, but nobody here has run Limn on a Linux machine that
genuinely has none.

**Wayland is not a future concern; the shipped binary already selects it.** Verified on the dev
machine by resolving the Linux artifact and inspecting it: `lwjgl-glfw-3.4.1-natives-linux.jar`
contains exactly one library, `libglfw.so`, and that library has the Wayland backend compiled in
(63 Wayland strings, the `libwayland-client`/`-cursor`/`-egl` loaders, `libdecor-0.so.0`, xdg
shell). GLFW 3.4 chooses the platform at **runtime** (from `XDG_SESSION_TYPE`/`WAYLAND_DISPLAY`
unless the `GLFW_PLATFORM` init hint says otherwise), and LWJGL exposes the whole API
(`GLFW_PLATFORM_WAYLAND`, `GLFW_PLATFORM_X11`, `glfwGetPlatform`, `glfwPlatformSupported`).
Re-confirmed on a live session on 2026-09-03 rather than from the jar: on Fedora KDE 44 the
shipped library names itself `3.5.1 Wayland X11 GLX Null EGL OSMesa`, and asking for the Wayland
platform yields a native Wayland window at 4.6 core under llvmpipe (4.0 under virgl, unforced).
The paragraph below describes that session as it was before the two options were implemented; the
toolkit now selects X11 there unless `limn.backend.platform` says otherwise.

The consequence is a **present-tense defect, not a port item**: on a Wayland session today, this
toolkit runs natively on Wayland, and every popup goes to the wrong place. Which is worse than it
sounds, because the failure is silent: the same binary behaves correctly on the X11 session next
to it.

**The blocker is the popup architecture, not the context.** The Wayland protocol has no absolute
window positioning: a client cannot ask for a surface at screen (x, y), and GLFW's Wayland
backend accordingly does not implement `glfwSetWindowPos`. That breaks four things in this
codebase, all of which are built on placing a *separate top-level window* next to an anchor
widget:

- `ComboBox.createPopupWindow` (`ComboBox.java:253`)
- `PopupMenu` cascade windows (`PopupMenu.java:409`)
- native `Dialog` centring (`Dialog.java:420`)
- the just-landed popup-follow (`ChildPopups.moveBy`), which becomes a no-op with nothing to
  move, and correctly so

Two options, not exclusive, and the first is now a one-line stopgap rather than a strategy:

1. **Force X11 and let XWayland handle it**: `glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11)`
   before `glfwInit`. Every mainstream compositor ships XWayland, so this restores today's
   behaviour on a Wayland session immediately, and it makes the choice explicit instead of
   letting an environment variable decide how the app behaves. Cost: no native Wayland
   integration (fractional scaling, HDR, per-surface colour management later).
   **Implemented**, with one correction the first attempt earned: the condition is whether X11 can
   be *reached*, not whether GLFW was built with it. `glfwPlatformSupported` answers the second
   (always true, both backends are in the one `libglfw.so`), and selecting X11 without a display
   makes `glfwInit` fail outright, so the application does not start at all. `DISPLAY` is the
   signal. `limn.backend.platform` overrides in both directions.
2. **Route popups through the in-scene overlay path when the window system cannot position
   windows.** The toolkit already has this for two of the three: `PopupMenu.presentInScene`
   and `Dialog`'s `IN_SCENE` display mode. **`ComboBox` has no in-scene
   fallback**, so its dropdown would not appear at all. That is the one genuine component-level
   piece of work Wayland requires, and it is independent of ANGLE.

   **Not optional: verified on a Fedora 44 KDE guest.** The popup-as-native-window model does not
   merely place badly on Wayland; it cannot be expressed there at all through GLFW. A dropdown
   opened under Wayland arrives near the centre of the screen, unanchored, **and wearing a KDE
   title bar with minimise and close buttons**, despite `WindowConfig.popup()` asking for no
   decoration. Both halves have the same cause: GLFW creates only *toplevels*, and a Wayland
   toplevel is positioned by the compositor and decorated by it. The primitive that would be
   correct, `xdg_popup`, is not exposed by GLFW at all.
   `GLFW_WAYLAND_DISABLE_LIBDECOR` was tried and does not help: it moves decoration from
   libdecor's fallback frame to KWin's server-side one, and on a session where libdecor does
   initialise it would remove the one path that can honour an undecorated request. So there is no
   backend-level fix to find here, and option 2 is the whole answer rather than the tidy one.

   **Implemented.** The seam is `NativeWindow.supportsAbsolutePositioning()`, default `true` so
   every backend written before Wayland keeps its behaviour without knowing the question exists;
   the LWJGL backend answers it from `glfwGetPlatform()` read *after* init rather than from the
   platform hint it asked for, because the hint is a request and a machine with no X server ends
   up on Wayland whatever was preferred. `PopupMenu` folds it into the fullscreen check it
   already had (both are "no window is available to put the menu in"), and `ComboBox` gained the
   in-scene presentation it never had. One decision point, as proposed, rather than three ad-hoc
   checks. `limn.backend.inScenePopups` forces it on any platform, so the fallback can be looked
   at somewhere other than Wayland; a presentation that renders on one desktop only is one that
   breaks on the other three and is noticed a release later.

   Option 1 is therefore no longer load-bearing: it is what an app *chooses* when it prefers X11
   semantics, not the only thing that works. What remains is that the in-scene list is clamped to
   the owner window instead of the display; see [popups-and-dialogs](../design/popups-and-dialogs.md).
   Removing that limit means creating a real `xdg_popup`, which GLFW does not expose; the raw
   `wl_display`/`wl_surface` are reachable through LWJGL, so it can be done without forking GLFW,
   but it would add the first native payload to `limn-backend-lwjgl`.

### 2.4 Summary of the answer to requirement 2

| Target asked for | Verdict | Binding constraint |
|---|---|---|
| Windows XP | **No** | JDK (Java 8 max), LWJGL 3, GLFW (all before GL) |
| Windows 7 / 8.1 | Unsupported but workable | JDK 17 unsupported there; ANGLE build must predate the Chromium Win7 drop |
| Windows 10/11, incl. ARM and RDP/VM | **Yes, and this is where ANGLE pays** | none after the port |
| Early Intel Mac (2006–2011) | **No** | JDK 17 needs macOS 10.13+ |
| Mac 2012+ on 10.13+ | Yes (already works on native GL) | ANGLE = insurance against removal; the driver-bug case is unmeasured |
| Mac with no accelerated GL renderer (a VM, and whatever else has that shape) | **Yes, since a0fa90c** | none; the context is built by hand, no ANGLE and no binary payload (§2.2) |
| Linux, old X (≥ XRandR 1.3, glibc 2.17) | Yes (already works via Mesa) | GLFW's X requirements |
| Linux, accelerated driver bypassed (`LIBGL_ALWAYS_SOFTWARE=1`) | Yes, unmodified, at 4.5/4.6 core under llvmpipe (measured with software forced; a genuinely driverless box is reasoning — §2.3) | none |
| Linux, Wayland | Yes; popups render in-scene (§2.3 option 2) | protocol, not GL |

---

## 3. Where the effort actually pays

Ranked, so implementation order can follow value rather than curiosity:

1. **Windows without a usable GL driver**: ANGLE over D3D11. The only case left in this ranking
   where a machine goes from "cannot run Limn" to "runs Limn", and the only one that needs ANGLE
   to do it. It is also a large population: OEM installs with no GL ICD, Windows on ARM,
   remote-desktop and VM sessions.

   **What such a machine actually offers is now measured, not assumed.** The ranking rested on the
   claim that a Windows box without a vendor driver has nothing usable; on 2026-09-03 that claim
   acquired a number. Windows ships a generic implementation inside `opengl32.dll` itself, selected
   per pixel format, and forcing one of those formats onto a real window in the logged-on session
   — the macOS manoeuvre of §2.2 transposed — yields `Microsoft Corporation`, `GDI Generic`,
   **`1.1.0`**, with exactly three extensions (`GL_WIN_swap_hint`, `GL_EXT_bgra`,
   `GL_EXT_paletted_texture`) and no `wglCreateContextAttribsARB`, `wglChoosePixelFormatARB`,
   `glCreateShader`, `glGenVertexArrays` or `glGenFramebuffers`. Twelve of the guest's generic
   formats can back a window and all twelve report the same. It fails Limn's requirement by three
   independent margins: the version is 1.1.0 against 3.3; essentially every entry point in
   Appendix A is post-1.1 and absent; and `wglCreateContextAttribsARB`, the *only* call by which a
   core-profile context can be requested, does not exist there, so a 3.3 core context cannot even
   be asked for. This is the same `opengl32.dll` on every Windows install, so it is not a gap that
   better hardware closes.

   The consequence for this record is the one that matters: **the remedy on Windows is to supply
   an implementation, not to change how the context is asked for.** GLFW does refuse the generic
   formats, in both branches of its WGL path — skipping `WGL_NO_ACCELERATION_ARB` in the modern
   one, and `PFD_GENERIC_FORMAT` without `PFD_GENERIC_ACCELERATED` in the legacy one, which is
   Microsoft's own definition of "supported by the generic implementation only" — and it is right
   to. Removing that refusal only exposes the next wall: Limn's forward-compatible core hints then
   meet GLFW's check for `WGL_ARB_create_context`, which the generic implementation also fails, and
   removing *that* leaves a GL 1.1.0 context on which the first shader compile fails. A
   `WinSoftwareGl` beside `MacSoftwareGl` would therefore turn a clear startup error into a worse
   failure later, and must not be written. §5 records the two things that do reach this machine.

   *Reasoning, not a measurement:* that GLFW's enumeration ends at zero usable formats on a
   genuinely ICD-less box. The 1.1.0 result above was reached by pixel-format index on a machine
   that also has a working ICD; that a machine with only generic formats leaves `usableCount == 0`
   and raises "WGL: The driver does not appear to support OpenGL" is read from GLFW's source, not
   run.
2. **macOS after Apple removes GL**: ANGLE over Metal. Insurance with a real expiry risk. This item
   used to be read as covering any Mac whose GL was unusable, which folded two propositions into
   one. They are separate now, because the nearer one is closed:
   - *A Mac whose GL driver is unusable* — an implementation present and refused — needs no ANGLE,
     no ES path and no shipped binary. It is handled by building the context without demanding
     acceleration (§2.2), which is committed and running. It has left this ranking entirely.
   - *A Mac with no OpenGL at all*, the day Apple withdraws it, is what ANGLE insures against.
     Nothing on that machine can be un-refused, because there would be nothing behind the refusal.
     No expiry date is known, which is why the item stays second rather than first.
3. **Linux**: mostly redundant with Mesa, and measurably so (§2.3): the same machine with software
   forced runs the unmodified toolkit at a *higher* GL version than its accelerated driver offers.
   Worth having only because it falls out of the same code path; do not spend bring-up time here
   first.

The corollary: the port must **keep native desktop GL as the default** where it works. Routing
every machine through ANGLE would trade a working driver for a translation layer on the majority
of installs, plus 10–20 MB of binaries for all of them.

---

## 4. Architecture

### 4.1 The seam stays inside the backend

`checkArchitecture` already forbids `org.lwjgl.*` outside `limn-backend-lwjgl`, so nothing above
the SPI can observe the profile. No toolkit or component change is required by the *rendering*
port (the Wayland popup work in §2.3 is a separate, component-level change).

### 4.2 The binding problem, and the cheapest correct answer

LWJGL exposes desktop GL and ES as unrelated classes with identical method names
(`org.lwjgl.opengl.GL33C` vs `org.lwjgl.opengles.GLES30`), backed by different function-pointer
tables loaded from different libraries. There is no way to call one through the other.

Options considered:

| Option | Verdict |
|---|---|
| ANGLE-only: delete the desktop path | Rejected on §3's corollary; it also makes Linux worse than today |
| Duplicate source sets (one per profile) | Rejected because two copies of `Gl3DContext` (132 call sites) will diverge |
| Reflection / `MethodHandle` per call | Rejected because of the 322 sites in paint paths |
| **A `GlApi` interface with two one-line implementations** | **Recommended** |

Concretely: 79 methods on the interface, two implementations that each delegate one line, and a
mechanical rewrite of 322 call sites from `GL33C.glFoo(…)` to `gl.glFoo(…)`. Constants do not
participate (§1.4); they stay as a plain `int` holder, which is why the interface is 79 methods
and not 147. One implementation is selected once per process at startup and stored on the
backend; the call sites see a monomorphic interface and the JIT devirtualizes. There is no
per-vertex dispatch here: the batcher issues one `glDrawElements` per batch, not per shape.

Work map (call sites per file, the whole rewrite surface):

```
132  Gl3DContext        38  GlRenderTarget      30  GlBatch        23  ShaderProgram
 19  GlMesh             17  GlShadowMap         16  ImageTextureCache
 13  GlyphAtlas         12  GlRenderer          11  GlTexture      8  GlCanvas
  2  GlRenderPass        1  LwjglWindow
```

### 4.3 Context selection and fallback

One profile per process: mixing a desktop GL window with an ES window is legal but doubles the
capability handling for no benefit.

Startup order, first that yields a context wins:

1. **Native desktop GL 3.3 core**: today's path, unchanged.
2. **macOS only, and only when path 1 fails because no *accelerated* pixel format matched**: let
   GLFW make the window again, this time with `GLFW_CLIENT_API = GLFW_NO_API`, and build the
   `NSOpenGLContext` by hand without `NSOpenGLPFAAccelerated`. **Implemented** (a0fa90c,
   `MacSoftwareGl`). It needs no binary payload, no ANGLE, no ES path and no new dependency, and it
   is the whole remedy for the population §2.2 describes; GLFW still owns the window, its view and
   every callback, and the context alone changes hands — make-current, swap, the swap interval,
   plus telling it when the view resizes (GLFW resizes only drawables it owns) and releasing it at
   destroy. The accelerated context is still asked for first on every platform, macOS included;
   only a machine already *seen* to refuse stops being asked (`LwjglWindow:208`, the retry guarded
   at `:213`), because a menu that reopens a hundred times must not spend a hundred failed
   creations. `limn.backend.macSoftwareGl` forces the path on hardware that works, so it is not
   code nobody can look at until it is the only thing between a user and a window.
   *Amended 2026-09-25:* until that day the forced path ran on the GPU. Leaving out
   `NSOpenGLPFAAccelerated` only stops demanding acceleration, and a Mac with a GPU matches it
   anyway: forced on an M5 Max, the context reported `Apple M5 Max` while the log said "software
   fallback". Forced, the context now names `kCGLRendererGenericFloatID` and reports `Apple Software
   Renderer`; the natural path, on a Mac that has only that renderer, is unchanged. What the
   software renderer costs was measured the same day with the heavy-screen benchmark on the macOS
   guest (2× drawable, 4 cores): animation at 54 frames a second, scrolling a full window at 1.97,
   one core saturated — rasterising 3.5 million pixels a frame on one thread, not a pacing stall.

   Short of patching GLFW there was nothing else (§2.2), and all three values of
   `GLFW_CONTEXT_CREATION_API` fail on such a Mac — NSGL finds no format, and EGL and OSMesa report
   their libraries missing, because neither ships with macOS. A second route *was* measured and not
   taken: deleting that one attribute from GLFW's own request renders at indistinguishable frame
   rates, which would be a one-line fix if it were a configuration switch rather than a fork.
3. **ANGLE**, if its libraries are present next to the application:
   `GLFW_CONTEXT_CREATION_API = GLFW_EGL_CONTEXT_API`, `GLFW_CLIENT_API = GLFW_OPENGL_ES_API`,
   `GLFW_CONTEXT_VERSION_MAJOR/MINOR = 3/0`, plus the `GLFW_ANGLE_PLATFORM_TYPE` init hint:
   `D3D11` on Windows, `METAL` on macOS, `VULKAN` then `OPENGL` on Linux.
   **Verified in this repo:** LWJGL 3.4.1 ships these constants
   (`GLFW_ANGLE_PLATFORM_TYPE_{D3D9,D3D11,VULKAN,METAL,OPENGL,OPENGLES}` are present in
   `lwjgl-glfw-3.4.1.jar`, and in the 3.4.3 jar the tree resolves now), so no patched GLFW is
   needed.
4. **Software**: Mesa llvmpipe (Linux: environment only, and nothing else is needed there — §2.3;
   Windows: the drop-in `opengl32.dll` of §5), still through path 1.
5. Fail with the existing explicit message rather than a black window
   (`LwjglWindow:229` already does this).

**The failure this chain branches on is not one code.** Cocoa raises `GLFW_FORMAT_UNAVAILABLE`
when no pixel format matches, which is what step 2 keys on and why its guard is macOS-only twice
over. WGL raises `GLFW_API_UNAVAILABLE` with "WGL: The driver does not appear to support OpenGL",
a different code for a different condition — no *implementation* rather than no *format*. Anything
added to this chain for Windows must branch on that one, and must not reuse step 2's, which will
never arrive there.

The chosen path must be logged with `GL_VENDOR` / `GL_RENDERER` / `GL_VERSION`, because "why is
it slow on that machine" is otherwise unanswerable from a bug report. `--gl-info` (§7, Phase 0)
prints all three plus the platform and how the context was created, and names step 2 when it is
taken: a context GLFW did not create reports itself as the application's own rather than as none.

New dependencies: `lwjgl-opengles` and `lwjgl-egl` (bindings only: LWJGL ships **no** ANGLE
natives; see §5). `GLES.createCapabilities()` replaces `GL.createCapabilities()` on the ES path.

### 4.4 Capability gates

Four (the third added by ADR 004, the fourth by ADR 016):

- **Timer queries** (G2): `EXT_disjoint_timer_query` on ES; keep the existing
  `caps.GL_ARB_timer_query` check for desktop. The footer must render a dash, not a zero.
- **MSAA sample count**: already queried via `GL_MAX_SAMPLES` and clamped; ANGLE reports lower
  maxima on some backends, which the existing clamp absorbs.
- **Float color rendering** (ADR 004): the 3D offscreen target is `RGBA16F`, which is
  colour-renderable in GL 3.3 core but only *texture*-complete in ES 3.0; rendering to it
  needs `EXT_color_buffer_float`. Present on effectively all ES 3.0-class hardware and
  exposed by ANGLE on every backend above *(unverified: the Phase 0b probe should record
  it alongside `EXT_disjoint_timer_query`)*. There is deliberately no RGBA8 fallback
  (ADR 004 §5): where the extension is genuinely absent, `limn.render3d` does not run,
  which is the same answer ADR 001 gives for GL below 3.3.
- **Normalized 16-bit textures** (ADR 016): a 10-bit video plane is uploaded as `R16`, which
  GL 3.3 core requires and which ES 3.0 core does **not** have; there it needs
  `EXT_texture_norm16`. This gate differs from the other three in having a real
  alternative rather than a degraded mode: where the extension is absent the planes move
  to `R16UI` and the sampler to `usampler2D`, both core in ES 3.0 and in GL 3.3, which
  removes the sample-scale uniform entirely because `texelFetch` then returns the code.
  That shape was not adopted on the desktop only because it would also change the 8-bit
  path, which is pinned to literal outputs and needs nothing. *(Unverified under ANGLE:
  the Phase 0b probe should record it alongside the other three.)*

All four are reported by `--gl-info`, and on 2026-09-03 all four answered yes on Mesa's software
rasteriser on both Linux guests — the poorest context Limn can actually run on, and it is not poor.
The MSAA clamp is the only gate that moved, and it moved on one guest only: on Ubuntu llvmpipe
reports a maximum of 4 samples against virgl's 8 there, while on Fedora it reports 8, the same as
virgl. The existing clamp absorbs the difference silently, which is the behaviour it was written
for. None of that says anything about ANGLE, where all four remain unmeasured.

---

## 5. Distribution

**There is no official standalone ANGLE release.** The binaries come from one of: a Chromium or
Electron distribution, a from-source build with `depot_tools` (hours, and a toolchain per OS), or
a community CI build. This is a supply-chain decision, not a build step. Pick one and pin it,
because "which ANGLE" determines the Windows floor (§2.1).

| OS | Files | Approx. size | Notes |
|---|---|---|---|
| Windows | `libEGL.dll`, `libGLESv2.dll` | 10–20 MB | plus `d3dcompiler_47.dll` (~4 MB) if targeting Windows 7, where it is not present in the OS |
| macOS | `libEGL.dylib`, `libGLESv2.dylib` | 10–20 MB | must sit where the loader finds them (next to the app binary, or `@rpath`) |
| Linux | `libEGL.so`, `libGLESv2.so` | 10–20 MB | usually unnecessary, since Mesa is already there |

GLFW loads these **by name at runtime** when the ANGLE hints are set, so placement follows the
same platform rules as the Mesa discussion: on Windows the executable's directory (i.e. the
jpackage launcher's directory, not the jar's), on macOS the app bundle. Licence: ANGLE is
BSD-3-Clause, so it becomes one more entry in `NOTICE`, which already carries a third-party audit.

Recommended packaging: ship ANGLE **only in the Windows artifact** initially (§3's ranking), and
keep macOS/Linux artifacts on native GL until the insurance is actually needed.

**There is a second way to reach the same Windows population, and it deserves a decision rather
than a footnote.** Mesa's llvmpipe can be shipped as a drop-in `opengl32.dll` next to the launcher,
found before `System32` by the loader's search order — the true analogue of what Apple's software
renderer is on macOS, at a full GL 4.5. Its virtue over ANGLE is that the Java side does nothing at
all: GLFW's modern WGL filter rejects only a format that *admits* `WGL_NO_ACCELERATION_ARB`, and a
software implementation reporting full acceleration passes it untouched, so Limn's existing 3.3
core path works unchanged. **Zero call-site diff, against Phase 1's 322.** Its weaknesses are real:
the DLL must match the *JVM's* architecture rather than the OS's (the Windows ARM64 guest runs an
x64 JDK under emulation, and would catch that trap), it must be opt-in or it shadows every working
driver — which makes it a fix nobody can apply until after the failure — and it is one more
`NOTICE` entry, MIT. **Untested:** nothing was installed on any lab machine to try it; the case for
it is reasoned from GLFW's source and from what a Mesa Windows build is, not run. Weigh it against
ANGLE before Phase 3, because if it suffices, most of this record is unnecessary for the population
that motivates it.

---

## 6. Risks

Each with the failure mode and the verification that settles it, not a severity guess.

| # | Risk | Failure mode | Settled by |
|---|---|---|---|
| R1 | Transparent framebuffer under EGL/ANGLE | Popups, glass dialogs and the cube gadget lose their see-through corners, or the window turns opaque black | **Phase 0b's spike, before any refactor**: a ~50-line probe, no dependency on the port. macOS/Metal on the dev machine today; Windows/D3D11 needs a Windows machine or VM (§7). Confirm afterwards with `--scene forms-popup` and `--gadget shot`. **Untouched by the 2026-09-03 lab**, and not to be read as half-answered by it: translucency *was* exercised on the macOS hand-built context, which carries surface opacity as a context parameter, but that is NSGL rather than an ANGLE swapchain and says nothing about this risk |
| R2 | Wayland window positioning | Dropdowns/menus appear in the wrong place or not at all — **live and silent while it lasted**, because the shipped `libglfw.so` selects Wayland by itself on a Wayland session | Protocol-level fact, not a question. **Closed**, by §9.1's step 4 run to the end: reproduced against an X11 control, the `GLFW_PLATFORM_X11` stopgap shipped, and the in-scene path ComboBox lacked built — all of it recorded in [ADR 028](028-a-popup-on-wayland-is-drawn-in-the-window-that-owns-it.md). What is left is the in-scene list's clamp to the owner window (§2.3) |
| R3 | ANGLE binary sourcing | No reproducible build; a Chromium bump silently changes the Windows floor | Pin a specific build + record its provenance in `NOTICE` before writing code against it |
| R4 | Per-surface swap interval under ANGLE | The multi-window vsync pacer (6 cubes at 52 fps) regresses to serialized refresh/N | `--gadget 6` under ANGLE, comparing the measured fps to the numbers already recorded for the native path |
| R5 | Timer queries absent | Perf footer GPU gauge blank | Accepted; gate + dash (§4.4) |
| R6 | GLFW + EGL on macOS is a less-travelled path | `glfwCreateWindow` fails or the context is ES 2.0 | Same run as R1 (it fails loudly, not subtly). Measured on the dev Mac and in the macOS guest: with no ANGLE present, `GLFW_CONTEXT_CREATION_API = EGL` fails at "EGL: Library not found" before any config is considered, because macOS ships no `libEGL.dylib`. That is the loud failure this row expects, and it confirms the path is only ever reachable by shipping the binaries of §5 |
| R7 | Shader divergence after the header swap | Pixel differences between profiles that only appear on one backend | The screenshot suite is the regression net: capture every scene under both profiles and compare. `GlslCodegenTest` already pins the generated pair |

---

## 7. Work plan

**Ordered by risk, not by dependency.** The natural order (probe, refactor, port, bring up) puts
the largest diff (Phase 1, 322 call sites) *before* the question that decides whether the port is
worth doing at all (R1: does a translucent window survive an ANGLE swapchain?). That is backwards.
Phase 0b answers it first, and it can be answered without touching a single line of the existing
GL code, because a spike does not need the facade, the shaders or the batcher.

**Phase 0: capability probe. Done.** A `--gl-info` mode printing vendor/renderer/version/extensions
and the chosen path. *Done when* it prints correctly under native GL and under llvmpipe — which it
did on 2026-09-03, on the three machines the lab notes record: the Windows guest under its native
GL, and both Linux guests under native GL and under llvmpipe alike. It also reports the windowing
platform, which §9.1's step 4 asked for, and the four capability gates of §4.4. One blemish worth
tidying, and it is a *no-display* problem rather than a *no-driver* one: the mode is documented as
printing the failure *as* the report, but it only catches a window-creation failure, so on a
machine with no display server at all a `glfwInit` failure escapes as an uncaught exception with an
LWJGL stack trace instead. Nothing about rendering; everything about what a bug report can be asked
to contain.

**Phase 0b: ANGLE spike (throwaway).** A standalone probe in the demo module, in the idiom
already used for the popup-follow verification: create a GLFW window with
`GLFW_CONTEXT_CREATION_API = GLFW_EGL_CONTEXT_API`, `GLFW_CLIENT_API = GLFW_OPENGL_ES_API`,
version 3.0 and the `GLFW_ANGLE_PLATFORM_TYPE` init hint; also request
`GLFW_TRANSPARENT_FRAMEBUFFER`; clear to a half-transparent colour, read the pixels back and
print the alpha. Roughly 50 lines, deleted afterwards. It answers, before anything is refactored:

- Does GLFW create an ANGLE context on this platform at all (R6)?
- **Does the transparent framebuffer survive (R1)?** This is the answer that gates the value of
  the whole port, since popups, glass dialogs and the desktop gadget are built on it.
- What do `GL_VENDOR/RENDERER/VERSION` and the extension list say? In particular, is
  `EXT_disjoint_timer_query` present (G2)?
- Does `eglSwapInterval` behave per-surface with two windows (R4)?

*Done when* those four answers are recorded here, per platform reached. On the dev machine that is
macOS/Metal today; the same probe binary answers Windows/D3D11 the day a Windows machine exists,
which is why it is worth writing as a probe rather than as an experiment in a scratch buffer.
**If R1 fails on D3D11, stop and re-scope**: an ANGLE path that cannot do translucent windows is
a different, much smaller feature (3D and opaque windows only), and that verdict is worth having
before Phase 1's diff, not after.

**Phase 1: the facade, no behaviour change.** Extract constants, introduce `GlApi` with the
desktop implementation only, rewrite the 322 call sites. *Done when* the full screenshot suite is
pixel-identical to `main` and 773 tests stay green. This is the largest diff and the lowest risk;
keep it a commit of its own so a bisect can cross it cheaply.

**Phase 2: profile-correct sources and the three gaps.** Header swap through `TargetProfile` in
`ShaderProgram.fromResources`; G1, G2, G3. *Done when* every shader still compiles under desktop
GL (the ES header is exercised by unit test only, until Phase 3) and the screenshots are
unchanged.

**Phase 3: the ES path.** Add `lwjgl-opengles`/`lwjgl-egl`, the ES `GlApi` implementation, the
ANGLE hints and the fallback chain of §4.3. *Done when* the demo runs under
`ANGLE_PLATFORM_TYPE=METAL` on the dev machine and the screenshot suite matches the native
captures within antialiasing tolerance, including the translucency scenes, which by now are a
regression check rather than an open question, Phase 0b having settled the mechanism.

**Phase 4: platform bring-up.** Windows/D3D11, then Linux/Vulkan. Needs machines; see §9 for
which machine answers which question, and why the pacer number from a VM does not count. *Done
when* the same screenshot comparison passes on each and R4's pacer number is recorded on real
hardware.

**Phase 5: packaging.** jpackage image with ANGLE for Windows, `NOTICE` entries, README section,
and the documented fallback order.

**Wayland (independent track, ran first and is done; see §9.1 step 4):** reproduce, stopgap
(`GLFW_PLATFORM_X11`), then `supportsAbsolutePositioning()` plus a ComboBox in-scene popup. It
neither blocked nor depended on the ANGLE work, and it went first because it repaired behaviour
that was broken on any Wayland session rather than enabling something new. All three steps landed
as ADR 028; what is left of it is the clamp §2.3 names.

---

## 8. Open questions (need hardware or a decision this document cannot make)

**The 2026-09-03 lab moved none of these.** It obtained no ANGLE binary, placed none on any
machine and loaded none, so 1, 2, 4 and 5 read today exactly as they read the day they were
written; the only one it touched is 3, which was already answered and is now answered from a live
session instead of from a jar. What the lab did settle is what lies *underneath* these questions —
where a portable context is actually needed, and where something cheaper already suffices (§3) —
and that changes which answers would be worth having, not what the answers are.

1. **Which ANGLE build?** Determines the Windows floor and the D3D9 question. Requires a
   provenance decision before Phase 3.
2. **Does `GLFW_TRANSPARENT_FRAMEBUFFER` survive on the D3D11 backend?** Chromium composites
   differently from a GLFW window; this is the single riskiest unknown (R1).
3. ~~Does LWJGL 3.4.1 ship a Wayland GLFW build?~~ **Answered: yes, in the same binary.** The
   Linux artifact holds one `libglfw.so` with both backends compiled in, and GLFW 3.4 picks the
   platform at runtime; LWJGL exposes `GLFW_PLATFORM`, `glfwGetPlatform` and
   `glfwPlatformSupported`. Verified from the dev Mac by resolving the artifact and inspecting
   it (no Linux machine was needed):
   ```bash
   # resolve org.lwjgl:lwjgl-glfw:3.4.1:natives-linux into the Gradle cache, then:
   unzip -Z1 lwjgl-glfw-3.4.1-natives-linux.jar | grep '\.so$'   # → only linux/x64/.../libglfw.so
   strings libglfw.so | grep -iE 'libwayland|libdecor|xdg_'      # → the Wayland backend is in it
   ```
   The consequence is bigger than the question: the toolkit already runs natively on Wayland
   sessions today, with misplaced popups (§2.3) — which ADR 028 then fixed. Re-confirmed on a real
   Wayland session on 2026-09-03, where the shipped library names itself `3.5.1 Wayland X11 GLX
   Null EGL OSMesa`, the window platform is Wayland when asked for, and the context is 4.6 core
   under llvmpipe.
4. **Is `eglSwapInterval` honoured per-surface by ANGLE/D3D11** the way the pacer assumes (R4)?
5. **Windows 7 target or not?** Answering "no" removes both the ANGLE-version constraint and the
   `d3dcompiler_47.dll` shipping question.

---

## 9. Machines, and what each one can actually settle

Written down because the temptation is to treat "get a Windows box" as one task, when the
machines answer different questions and one of them answers a question no other can.

| Machine | Settles | Cannot settle |
|---|---|---|
| **The dev Mac (present)** | Phases 0–3 end to end; R1/R6/G2 on the Metal backend; the whole facade refactor and its pixel regression net | Anything about D3D11, which is where the port's value is |
| **Windows 10/11 VM on this Mac** | Open questions 2 and 4; R1 and R4 on D3D11; the DLL-placement half of packaging; the fallback chain end to end. **And, measured 2026-09-03, what lies behind GLFW's refusal on Windows**: the guest's own generic pixel formats can be forced onto a real window by index, and every one of them yields `GDI Generic 1.1.0` (§3). That is the figure §3's ranking rested on, obtained with no new hardware | Performance numbers, because a virtual GPU with synthetic vsync makes the pacer's fps meaningless; and driver diversity, since a VM has exactly one virtual driver. **Also not the motivating population as the demo sees it**: a Parallels guest on Apple Silicon reports `Parallels using Apple M5 Max`, version `4.1 Metal - 90.5`, which is byte for byte the host Mac's own string, because the hypervisor forwards to the host driver rather than implementing or translating GL. So it cannot settle whether ANGLE/D3D11 comes up where no ICD exists, nor whether Limn renders correctly through it. What it *can* no longer be said to leave open is what the in-box generic implementation offers, since that implementation is present on this machine and reachable by pixel-format index — on a machine that also has a working ICD, which is exactly §3's caveat |
| **The macOS guest (present, and new since this table was written)** | **The machine shape §3's first item names — a working GPU and no usable GL driver — on the wrong OS**, and a shape this table had no row for: Metal reports a real paravirtual device, while OpenGL there is only `Apple Software Renderer` at `kCGLRPAccelerated = 0`. It settles the general proposition that on such a machine everything depends on what the platform's own software fallback is worth, and that this varies by OS: 4.1 on macOS is ample, 1.1 on Windows is worthless, 4.5/4.6 on Linux is more than enough. It also settles that GLFW's Cocoa refusal is unreachable by configuration, and it is where the §4.3 step 2 fallback was verified live | Anything about D3D11 or about Windows, which is where the port's value is; and how common its own shape is — one guest, one macOS version, one virtualiser. Whether other virtualisers or headless Macs present the same "Metal device, software GL only" shape, and whether the refusal always arrives as `GLFW_FORMAT_UNAVAILABLE`, which the fallback keys on, is unmeasured. Also: the frame rates taken there are a probe's trivial triangle, not real scenes, so what the whole toolkit costs on a software renderer — text atlases, blending, blur, the multi-window pacer, video, and a 2× Retina drawable — has no numbers |
| **Windows 7 VM** | Open question 5, empirically and cheaply. Expect it to fail at the **JVM**, not at GL: JDK 17's supported floor is Windows 10, and if it will not boot there the answer is "no Win7", which then deletes the ANGLE-version constraint and the `d3dcompiler_47.dll` question | Nothing about the ANGLE path itself if the JVM stops first, which is the likely and still useful outcome |
| **Real Windows hardware with a weak/old GPU** | The actual motivation of the port: an OEM machine whose OpenGL ICD is missing or stuck at 1.1 while D3D11 works. **Still the machine required**, and the 2026-09-03 measurements narrow rather than replace it: they say what such a box offers, not whether ANGLE reaches it | n/a |
| **Linux VM with switchable Wayland/X11 sessions** | The Wayland track end to end: the X11 session is the control the Wayland captures are compared against; also the llvmpipe path and ANGLE's Vulkan backend. **Two of the three are done** — the Wayland track landed as ADR 028, and the llvmpipe path was measured on 2026-09-03 across two distributions and three windowing paths (§2.3). ANGLE's Vulkan backend is untried | Windows questions. And a *genuinely* driverless Linux box: both guests have virgl, so software rendering was forced with an environment variable rather than discovered (§2.3) |

**Order of value: Windows 10/11 VM first.** It converts three open questions into answers and it
is the same environment as one of the populations being targeted. Windows 7 is worth an
afternoon *as a scope decision*, not as a development target: a negative result there is a
constraint removed, which is progress.

That guest now exists and has been used for everything except the thing it was wanted for. What it
still owes this record is a single run: ANGLE's two libraries placed beside the launcher and the
Phase 0b spike started in the logged-on session. Until that happens, none of §8's ANGLE questions
moves, however much else the machine has answered.

### 9.1 Agreed starting sequence (decided 2026-07-30)

Not a suggestion: this is the order the work starts in, and the reason each step precedes the
next. Two guests, both with **OpenSSH**, both provisioned by the project owner:

1. **A Windows 10/11 VM** on the development Mac (a Windows 7 VM may follow, for the §8.5 scope
   test and nothing else). The build must run *inside* the guest for the native-classifier reason
   above. **A shell is not sufficient on Windows** (*verified on a Windows 11 ARM64 guest under
   Parallels*): an OpenSSH session is a service session with no interactive desktop, an OpenGL ICD
   has nothing to bind to there, and the driver dereferences null and takes the JVM down with it.
   That happens identically for a hidden window and a visible one, at every context version and
   profile down to a bare `glfwCreateWindow` with no version hints, while the same call with
   `GLFW_NO_API` succeeds, so the failure is the session, not the window and not the profile.
   Compiling and testing over SSH is fine; anything that creates a context must be *started* in
   the logged-on session, which a one-shot scheduled task with `/IT` does. `--screenshot` is then
   unattended as intended: the invisible window was never the problem. Register the task with
   `-AllowStartIfOnBatteries`, learned the expensive way on 2026-09-03: without it the default
   power settings leave the task queued forever whenever the *host* Mac is on battery, and a task
   that never starts looks exactly like a probe that hangs. A locked desktop, by contrast, does not
   stop such a task — two of them ran to completion in the logged-on session with `LogonUI` up.
2. **A Linux VM with a Wayland session** (any mainstream compositor: GNOME or KDE on Fedora or
   Ubuntu). It has to be able to switch between the Wayland and X11 sessions at the login screen,
   because half of what it proves is the *difference* between the two.
3. **Phase 0b's spike runs on every backend reached**: macOS/Metal here, Windows/D3D11 and
   Linux/Vulkan in the guests. One probe binary, three answers.
4. **The Wayland track runs in parallel**, and starts before the ANGLE work because it fixes a
   defect that exists **today** rather than enabling something new (§2.3). Three steps, each
   verifiable in the Linux guest:
   - **Reproduce.** Run the demo on the Wayland session and capture `--scene forms-popup`,
     `--scene menu` and a native dialog. Record what actually happens to a popup whose owner
     cannot be positioned: misplaced, at the origin, or invisible. Then run the same captures on
     the X11 session of the same machine as the control. Also record `glfwGetPlatform()` in the
     `--gl-info` output from Phase 0, so a bug report says which platform it ran on.
   - **Stopgap.** `GLFW_PLATFORM_X11` init hint, verified to restore the X11 behaviour under
     XWayland. This is the shippable answer while the real one is built.
   - **Solve.** `NativeWindow.supportsAbsolutePositioning()` plus a `ComboBox` in-scene popup, so
     the three popup components pick window-or-overlay from one predicate. *Done when* the three
     captures on the Wayland session match the X11 controls.
5. **Only then is the ANGLE port committed to.** With R1 answered on the backend that matters,
   either the plan proceeds as written from Phase 1, or it is re-scoped to whatever ANGLE turns
   out to be able to do. Phase 1's 322-call-site diff is not started before that answer exists.

The Linux guest is cheap to justify twice over: besides Wayland it is where the llvmpipe path
(`LIBGL_ALWAYS_SOFTWARE=1`) and the Vulkan ANGLE backend get exercised, and `scripts/screenshot.sh`
already knows how to run headless there with `xvfb-run`.

**What makes a VM usable for verification rather than just for looking:** the loop this project
already relies on is build → `--screenshot` → read the PNG. In a guest that needs (1) a JDK 17
and the repo reachable (shared folder or clone); (2) the build run **inside** the guest, because
`limn-backend-lwjgl/build.gradle.kts` selects LWJGL native classifiers from the *host* platform,
so a Mac-side build produces a jar set with no Windows natives; (3) SSH or any shell into the
guest, so captures can be produced non-interactively and copied back for inspection.
`--screenshot` renders through an invisible window, so nothing needs a human watching the guest's
desktop; but on Windows the capture must still be *launched into* the logged-on session (§9.1),
because the shell an SSH server hands out is a session without one.

---

## Appendix A: GL entry points in use (79)

Everything here is core GL ES 3.0 **except the three marked**.

```
glActiveTexture glAttachShader glBeginQuery* glBindBuffer glBindBufferBase glBindFramebuffer
glBindRenderbuffer glBindTexture glBindVertexArray glBlendFuncSeparate glBlitFramebuffer
glBufferData glBufferSubData glCheckFramebufferStatus glClear glClearColor glCompileShader
glCreateProgram glCreateShader glDeleteBuffers glDeleteFramebuffers glDeleteProgram
glDeleteQueries glDeleteRenderbuffers glDeleteShader glDeleteTextures glDeleteVertexArrays
glDepthFunc glDetachShader glDisable glDrawArrays glDrawBuffer** glDrawElements glEnable
glEnableVertexAttribArray glEndQuery* glFramebufferRenderbuffer glFramebufferTexture2D
glGenBuffers glGenFramebuffers glGenQueries glGenRenderbuffers glGenTextures glGenVertexArrays
glGenerateMipmap glGetInteger glGetIntegerv glGetProgramInfoLog glGetProgrami
glGetQueryObjecti*** glGetQueryObjectui64* glGetShaderInfoLog glGetShaderi
glGetUniformBlockIndex glGetUniformLocation glLinkProgram glPixelStorei glPolygonOffset
glReadBuffer glReadPixels glRenderbufferStorage glRenderbufferStorageMultisample glScissor
glShaderSource glTexImage2D glTexParameteri glTexSubImage2D glUniform1f glUniform1i glUniform2f
glUniform3f glUniform3fv glUniform4f glUniformBlockBinding glUniformMatrix3fv glUniformMatrix4fv
glUseProgram glVertexAttribPointer glViewport
```

`*` needs `EXT_disjoint_timer_query` (G2)  ·  `**` use the plural `glDrawBuffers` (G1)  ·
`***` use `glGetQueryObjectuiv` (G3)

## Appendix B: how this inventory was produced

So it can be re-run after any change, rather than trusted:

```bash
# entry points
grep -rho "\bgl[A-Z][A-Za-z0-9]*(" limn-backend-lwjgl/src/main/java | sort -u
# constants
grep -rho "GL33C\.GL_[A-Z0-9_]*\|\bGL_[A-Z0-9_]*" limn-backend-lwjgl/src/main/java | sed 's/GL33C\.//' | sort -u
# call sites per file (the Phase 1 work map)
for f in $(grep -rl org.lwjgl.opengl limn-backend-lwjgl/src/main/java); do
  echo "$(grep -o '\bgl[A-Z][A-Za-z0-9]*(' $f | wc -l) ${f##*/}"; done | sort -rn
```
