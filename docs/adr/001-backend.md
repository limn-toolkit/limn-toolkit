# ADR 001. Native backend: LWJGL 3 (GLFW + OpenGL 3.3 core + stb)

- **Status:** Accepted
- **Date:** 2026-07-05
- **Scope:** choice of the native binding layer and graphics API for Limn UI

## Context

Limn UI is a GUI toolkit for desktop Java built from scratch, with no
`java.awt`, `javax.swing` or SWT in any layer. We need:

1. window creation, input (keyboard/mouse/basic IME), monitors and clipboard
   on Windows, macOS and Linux (X11 and Wayland);
2. a GPU API for accelerated 2D rendering with high-quality antialiasing;
3. TrueType font rasterization with a glyph atlas;
4. a **Java 17** baseline, which rules out any FFM/Panama-based solution
   (finalized only in Java 22).

## Decision

Use **LWJGL 3** as the native binding layer:

| Responsibility | Library (via LWJGL) |
| --- | --- |
| Windows, input, monitors, content scale, clipboard, event loop | **GLFW** |
| Rendering | **OpenGL 3.3 core profile** (forward-compatible on macOS) |
| Font rasterization | **stb_truetype** |
| PNG writing (screenshots) | **stb_image_write** |

Adopted version: LWJGL **3.4.1** (BOM), with natives per classifier
(`natives-windows`, `natives-macos`, `natives-macos-arm64`, `natives-linux`,
`-arm64` variants).

### Why LWJGL 3 + GLFW and not SDL3?

- **Zero homegrown JNI and zero transitive dependencies**: LWJGL delivers
  mature GLFW/GL/stb bindings in a single ecosystem, with natives published on
  Maven Central for the five OS/architecture combinations we need. SDL3 on
  Java 17 would require a less-maintained third-party JNI binding (e.g.
  libsdl4j), or FFM (ruled out by the 17 baseline).
- **GLFW covers what v1 needs**: multiple windows, per-monitor content scale
  with a change callback (fractional HiDPI), UTF-8 clipboard, basic IME via
  char callbacks, native Wayland since 3.4.
- **stb_truetype** gives full control of the glyph pipeline (rasterize at
  physical `fontSize × scale`, atlas indexed by pixel size), a hard
  requirement of the project. FreeType (also available in LWJGL) remains a
  future upgrade if hinting/quality demand it; the swap stays confined to the
  backend.
- SDL3's strong point (slightly better IME/Wayland) doesn't offset the cost of
  the extra JNI binding today; the SPI described below allows a
  `limn-backend-sdl3` in the future without touching the upper modules.

### Why OpenGL 3.3 core?

It is the real greatest common denominator: Windows/Linux have GL ≥ 3.3 on any
GPU from the last decade, and macOS offers at most **GL 4.1** (deprecated, see
below). We restrict ourselves strictly to 3.3 core features (no compute
shaders, no `glClipControl`, no debug callbacks), so we never depend on
anything beyond what macOS exposes.

## OpenGL deprecation on macOS: architectural mitigation

Apple has deprecated OpenGL since Mojave (2018); it remains functional (on top
of Metal on Apple Silicon), but could be removed in any future release.
**Mandatory** mitigations already applied to the design:

1. **Total confinement**: no class outside `limn-backend-lwjgl` imports
   `org.lwjgl.*` (neither GLFW nor GL). Widgets/layout/components use only the
   `Canvas` API of `limn-toolkit`. The `checkArchitecture` Gradle task (wired
   into `check`) fails the build on a violation, together with the global ban
   on AWT/Swing/SWT.
2. **Dependency inversion**: the SPI (`limn.backend.Backend`, `NativeWindow`,
   `GpuRenderer`) lives in `limn-toolkit`; `limn-backend-lwjgl` implements it.
   The conceptual diagram remains `toolkit → backend`, but at compile time it
   is the backend that depends on `limn-toolkit`; the upper modules only see
   interfaces.
3. **Portable shaders**: all shaders are written in GLSL 330 using only the
   subset trivially portable to **GLSL ES 3.00** (no `gl_ClipDistance`, no
   desktop-only types/qualifiers; explicit precision will be added in the ES
   port). A header comment in each shader records the restriction.

### Migration routes enabled (without touching upper modules)

- **(a) ANGLE, the cheapest route.** GLFW can create a **GL ES 3.0 context
  via EGL** (`GLFW_CONTEXT_CREATION_API = EGL`, `GLFW_CLIENT_API =
  OPENGL_ES_API`) on top of ANGLE's libraries (GL ES over Metal). Migration =
  bundle the ANGLE dylibs + port shaders to GLSL ES 3.00 (mechanical, given
  rule 3) + adjust context creation in the backend. Nothing above the SPI
  changes. **Costed in detail in ADR 003**: the shader half is confirmed
  mechanical, and the real work turned out to be the LWJGL binding split, the
  ANGLE supply chain, and two problems that are not GL at all (translucent
  swapchains, Wayland window positioning).
- **(b) Vulkan + MoltenVK via LWJGL.** Reimplement `GpuRenderer` as a new
  module (`limn-backend-lwjgl-vk`), keeping GLFW for windows
  (`glfwCreateWindowSurface`). More expensive, higher performance ceiling; the
  SPI and the geometry batcher are already designed not to leak GL-only
  concepts (no exposed global state, opaque resources).

## Threading model (summary; detailed in the `limn.concurrent` code)

- **Single UI thread = the process main thread** (a GLFW/Cocoa requirement; on
  macOS the JVM needs `-XstartOnFirstThread`). It runs event polling,
  dispatch, layout, animation and rendering.
- **Event-driven** loop: sleeps in `glfwWaitEvents()` /
  `glfwWaitEventsTimeout()` (never busy-waits); `Ui.post` from another thread
  wakes the loop with `glfwPostEmptyEvent()`.
- Heavy application work goes to the worker pool via `Ui.async(Supplier)`,
  with callbacks completing on the UI thread. Widget mutation off the UI
  thread throws `IllegalStateException` (`Ui.checkUiThread()`).

## Consequences

- **Positive**: zero dependencies beyond LWJGL; a single render path for all
  three OSes; official natives on Maven Central; a text pipeline fully under
  our control (a HiDPI requirement); SPI ready for ANGLE/Vulkan/SDL3.
- **Negative / accepted risks**: OpenGL on macOS is a dead end with a planned
  exit (routes a/b); GLFW has limited IME (basic composition via callbacks,
  with complex shaping/RTL already out of the v1 scope); we will have to write
  the 2D renderer (batcher, analytic/SDF AA) from scratch, which is exactly
  the point of the project.
