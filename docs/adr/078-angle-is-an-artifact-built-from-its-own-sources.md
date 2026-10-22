# ADR 078: ANGLE is an artifact of its own, built from its own sources

- **Status:** Accepted, 2026-10-22. The repository `limn-angle-natives` is written and committed
  locally; its macOS arm64 slice was built on the development Mac the same day, and the other three
  are built by its workflows once it is pushed. The toolkit's side, the loader and the fallback, is
  ADR 003 §10.
- **Date:** 2026-10-22

## Context

ADR 003 gives a window whose machine has no usable OpenGL an OpenGL ES 3.0 context from ANGLE, on
Windows and on macOS. ANGLE is a native library, of two files a platform, a few megabytes each, with
a release cadence of its own. The toolkit already ships three payloads of that kind, and each lives
in a repository that versions with what it carries: the fonts (ADR 036), FFmpeg (ADR 037) and the SVG
renderer (ADR 066). A toolkit release re-uploads none of their bytes.

Three ways to obtain ANGLE were open:

- **A copy taken from an application that ships it** (a browser, an application built on a browser
  engine). Rejected: no provenance a release can state, a build configured for someone else's needs
  (every backend, a macOS floor of 12.0 in the one examined), and a redistribution question per
  application.
- **A C++ package manager's port.** Measured in ADR 003 §5: it builds on both Windows architectures
  and gave the 2.65 MB figure. Not chosen: it is a third party's rewrite of ANGLE's build, lags
  ANGLE, and linked zlib as a DLL of its own.
- **ANGLE's own build (gn and ninja through depot_tools) from a pinned commit.** Chosen by the owner,
  "exactly as for lunasvg and FFmpeg": upstream's build, the one upstream tests, and the only one
  that leaves out every backend Limn does not use by configuration rather than by patch.

## Decision

1. **A repository of its own**, `limn-angle-natives`, publishing
   `io.github.limn-toolkit:limn-angle-natives`: a main jar with ANGLE's licence and four classifiers,
   `natives-windows-x86_64`, `natives-windows-aarch64`, `natives-macos-x86_64` and
   `natives-macos-aarch64`. No Linux classifier: ADR 003 §2.3 leaves Linux to Mesa. Each classifier
   jar has a module name of its own (`limn.angle.natives.<os>.<arch>`), so the module path never
   sees two jars of one name.
2. **Versioned with ANGLE:** `2.1.<branch>.<n>`, where 2.1 is ANGLE's own version, `<branch>` the
   Chromium release branch the pinned commit heads, and `<n>` the repository's packaging. The first
   is `2.1.8078.0`: the head of `chromium/8078`, the Chromium release branch that was stable on
   2026-10-22. `angle.pin` holds the branch, the commit and the depot_tools commit; the build refuses
   a version that names another branch. The same release flow as the other payloads: a bump of
   `versions.properties` on `main` tags and publishes, and a human presses Publish.
3. **Only what Limn uses is built:** Direct3D 11 on Windows, Metal on macOS; no Vulkan, desktop
   OpenGL, SwiftShader, WebGPU, OpenCL or null backend, no tests, no capture. The D3D9 backend the
   package manager's build carried no longer exists in ANGLE at that commit.
4. **What a library must be, checked by the build before anything is packed:**
   - Windows: the C runtime linked in (no Visual C++ redistributable), and no import of a C runtime,
     of `d3d9.dll` or of a zlib DLL. `d3dcompiler_47.dll` is loaded by name and comes with Windows
     10 and 11 (ADR 003 §5).
   - macOS: install names `libEGL.dylib` and `libGLESv2.dylib`, the bare file names, because the
     windowing library opens `libEGL.dylib` by that name and macOS answers it with the image the
     loader already loaded by full path (measured, ADR 003 §10.2); nothing linked but the system's;
     a minimum macOS of 11.0, Limn's floor.
5. **Licences first:** the build lists every source file the compiler was given for the two
   libraries, grouped by the directory that states its licence (a README.chromium's `License:`
   field, or a LICENSE file), with the full text of each, into `THIRD_PARTY.txt` in the platform's
   jar, and fails on a licence it does not recognise as one a library may redistribute, and on any
   copyleft one.
6. **Tested where it runs:** a test makes an OpenGL ES 3.0 context through the built libraries,
   checks the extensions the toolkit's capability gates need and, on macOS, the IOSurface binding
   zero-copy video needs, on each platform's own runner: the Windows ones have no GPU and no OpenGL
   driver, which is the machine the fallback is for.
7. **The backend names the main jar and all four classifiers**, as it names the SVG renderer's, and
   loads them only on the way to a fallback (ADR 003 §10.3).

## Consequences

- An application adds nothing to run where OpenGL is missing; every default install carries about
  5 MB more (the Windows pair measured at 5.1 MB compressed from the package manager's build; the gn
  build without the backends it carried, and the macOS pair, are measured by the first release).
- Moving ANGLE is a decision with a reason, not a routine: the toolkit compiles only its own shaders,
  so the security fixes in ANGLE's handling of hostile shaders do not reach it. RELEASING.md there
  says how.
- The root build's natives table admits one payload that is not for every target, by name and with
  its platforms (`partialPayloads`), so a payload missing a platform by accident still fails it.
- A first build fetches several gigabytes and takes hours of CPU; it runs on the runners, never on
  a workstation by default.

## What the first build taught (2026-10-22, macOS arm64, on the development Mac)

- **depot_tools must be bootstrapped explicitly** when its self-update is off, or `gn` finds no Python.
- **Xcode's Metal toolchain** is a component of its own since Xcode 26, installed with
  `xcodebuild -downloadComponent MetalToolchain` (839 MB): ANGLE compiles its internal Metal shaders
  ahead of time whenever it builds for the machine it runs on, with no argument to turn that off.
- **macOS 11.0 needed one patch.** ANGLE's only availability errors at that floor were four uses of
  `kIOMainPortDefault`, introduced in macOS 12; `patches/angle-macos-11-main-port.patch` replaces
  them with `MACH_PORT_NULL`, the constant's value, which IOKit takes on every version. A build with
  warnings not fatal listed every error first, so the patch is known to be the whole of it.
- **Perfetto is left out** (`angle_enable_perfetto=false`): ANGLE's optional tracing compiled 115
  files of it and 134 of protobuf, and its licence field ("blessing", SQLite's dedication, beside
  BSD and Apache) was refused by the inventory, as it is meant to refuse what it does not recognise.
  Limn traces nothing through ANGLE.
- **The result:** `libGLESv2.dylib` 5.9 MB and `libEGL.dylib` 103 KB, arm64, install names the bare
  file names, linking only the system's, minimum macOS 11.0; compiled from ANGLE (BSD-3-Clause, 328
  files), abseil, libc++, libc++abi and astc-encoder (Apache-2.0), xxhash (BSD-2-Clause) and zlib.
  The repository's own test passed on it.

