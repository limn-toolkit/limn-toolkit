# ADR 018: An image leaves through an encoder, and a readback names its colour space

- **Status:** Accepted, 2026-08-05. Implemented on `main`.
- **Date:** 2026-08-05
- **Scope:** getting an image *out* of Limn: an encoder SPI and facade in `limn.graphics`,
  a readback capability on GPU surfaces, and what happens to the window screenshot path.
- **Extends:** ADR 004, which decides that a render target holds scene-referred linear
  light and that the display transform runs once in the 2D composite. That decision is
  what makes readback two operations rather than one.
- **Audience:** whoever implements or changes it. Claims are tied to types and members
  that exist, or marked as unverified with the machine needed to settle them.

---

## 0. The three findings this answers

**Finding 1: the image facade is one-way.** `Images` decodes: `decode`, `load`,
`fromResource` and their async forms, over a single installed `ImageDecoder`. There is no
encoder, no `save`, and no interface to implement one. An application that *produces* an
image (a chart it drew, a crop a user made, a diagram generated from data) has nowhere
to put it.

**Finding 2: nothing can read pixels back from the GPU.** `Graphics3D.createTarget`
hands out a `RenderTarget`; `Canvas.drawSurface` composites it into the scene. That is a
complete render-to-texture path with no exit: `GpuSurface` exposes `widthPx`, `heightPx`,
`resize` and `dispose`, and nothing that yields pixels. A thumbnail of a 3D preview, a
reference image for a rendering test, an export of what a viewport shows: none of them
was reachable.

**Finding 3: the one export path was a screenshot facility wearing the wrong clothes.**
`NativeWindow.captureNextFrame(Path)` → `GpuRenderer.captureFramebuffer(Path)` → a private
`writePng` in `GlRenderer`: whole-window only, PNG only, file only, with the format and the
vertical flip hard-coded inside the renderer. Right feature, wrong layer. The moment a
second thing needed to write a PNG, the repository would have had two independent PNG
writers, and one of them would have drifted.

`ImageDecoder` is not the pattern to copy for the fix: it is a single volatile slot, one
decoder, no negotiation. `Videos`/`VideoDecoder` is: an ordered list, `name()` for
diagnostics, a cheap honest `supports()`, first match wins, install/uninstall with a
deliberate null asymmetry, and a failure that names every provider it asked in order.
Encoding has more than one plausible provider by construction (formats differ by library,
not by platform), so it takes that shape.

Reshaping `ImageDecoder` to match is **not** part of this work. It is noted, not done: the
decoder has exactly one implementation and no caller has ever needed two.

---

## 1. Decision

1. **An encoder is an SPI, not a method.** `ImageEncoder` (`name`, `supports`, `encode`
   into an `OutputStream`), installed into an ordered list on `Images`, probed in
   installation order, first match wins. Nothing accepting the request is an
   `UnsupportedOperationException` naming every encoder asked, in order.
2. **A format is a value, not an enum.** `ImageFormat` wraps an IANA media type;
   `ImageFormat.PNG` is a constant. See §3.1.
3. **PNG is written by the toolkit, in Java.** `PngEncoder` lives in `limn-toolkit` on
   `java.util.zip` and is installed by `Images` at class-initialisation time, so encoding
   works with no backend, no window and no GL context. See §3.2.
4. **Reading a surface back is a capability**, `ReadableSurface`, with **two differently
   named methods returning two different types**: the colour-space choice is structural.
   `RenderTarget` extends it, so every render target can be read. See §2.
5. **The window screenshot path is expressed in terms of the above.**
   `GpuRenderer.captureFramebuffer(Consumer<Image>)` and
   `NativeWindow.captureNextFrame(Consumer<Image>)` are the primitives; the `Path`
   overloads are `default` methods over them plus `Images.save`. There is exactly one PNG
   writer in the repository, and `GlRenderer` no longer writes files.

---

## 2. The part that is Limn's rather than generic: which picture a readback returns

ADR 004 says a `RenderTarget`'s contents are linear light, premultiplied, scene-referred,
and that exposure → ACES → sRGB encode runs once, in the 2D composite, when the surface is
drawn. So a readback has two defensible answers and they are **different pictures**:

- the numbers the pass wrote, correct for further processing or for a test that asserts
  on the pass's own output, and containing values above 1.0;
- what the user saw, correct for a screenshot, a thumbnail, an export.

A flag with a default would let a caller get the wrong one by not thinking about it, which
is the failure mode ADR 004 §0 finding 2 describes in the previous tense. So:

| | returns | contents |
| --- | --- | --- |
| `readDisplayReferred` | `Image` | RGBA8, sRGB-encoded, **straight** alpha, top-down; exposure, ACES and encode applied once |
| `readSceneReferred` | `ScenePixels` | RGBA float, linear, **premultiplied**, top-down; no exposure, no tonemap, nothing clamped |

**Different return types, deliberately.** The brief this work came from allowed either
refusing scene-referred data in an 8-bit format or clamping it and documenting the clamp.
Neither is needed: `ScenePixels` is a distinct type, no encoder accepts it, and so there is
**no path from scene-referred light to a PNG at all**. The silent clamp cannot be written.
The cost is one small public value type (a `float[]` and its dimensions) that nothing
encodes, which is the honest description of what scene-referred readback is for.

Three consequences worth stating because each is a wrong edit waiting to happen:

- **A window framebuffer is already display-referred.** The composite ran into it. Reading
  one back and applying the transform again is the mirror-image bug, and it is why the
  window path is `GpuRenderer.captureFramebuffer` rather than a `ReadableSurface`.
- **Exposure belongs to the display transform.** `RenderTarget.exposure()` is applied by
  `readDisplayReferred` and must not be applied by `readSceneReferred`.
- **The CPU and the shader must agree.** `ColorSpace.displayTransform` is the CPU mirror of
  `displayTransform()` in `canvas.frag`. Changing the curve on one side only makes an
  exported image differ from the window it came from, with nothing to point at. Stated on
  both members; **not asserted by a test** (see §6).

---

## 3. The decisions the brief left open, and why they went this way

### 3.1 A format is a media type, not an enum

A closed enum shuts the door on a provider adding a format, and a format is contributed by
whoever is installed. A bare `String` gives up type safety and lets `"png"` and `"PNG"` and
`"image/png"` all be different things. `ImageFormat` is the middle: a record over one
lower-cased, validated media type, so equality is well defined, an encoder compares with
`equals` rather than parsing, and a new format is one `new ImageFormat("image/jpeg")`.

The suggested file extension is deliberately **not** a component: it would have been a
second identity for the same format, and two callers spelling it `"jpg"` and `"jpeg"` would
have produced unequal values for one format. Nothing in this design needs it, because:

**the format is never inferred from a file name.** `Images.save(image, format, file)` takes
both. Inferring from the suffix turns a mistyped name into a file whose contents disagree
with its extension, which nothing downstream can detect. This is the one place the design
is deliberately less convenient than `ImageIO`.

### 3.2 The built-in PNG encoder is pure Java in the toolkit, not stb in the backend

`stb_image_write` is already a backend dependency, so the backend route would have added no
new dependency and less code. It was rejected on one requirement: **encoding must not need
a window or a GL context**. A backend-installed encoder is installed by backend startup; a
toolkit test that wants to produce a reference image, or an asset tool that generates a
sprite sheet, would have had to start a backend to write a file, and `limn-toolkit`'s
tests cannot start one at all.

The secondary reason is determinism. stb's output is a property of the native library
version; `PngEncoder` picks its filters from the pixels alone and pins the deflate level
rather than taking `Deflater.DEFAULT_COMPRESSION`, whose value is a property of the runtime.

The cost is a PNG writer to maintain (about 200 lines: IHDR/IDAT/IEND, the five row filters
with the specification's minimum-sum heuristic, and `java.util.zip` for deflate and CRC).
It is bounded, it is covered by tests against an independent decoder, and it replaced a
writer that already existed inside the renderer.

**How far determinism goes, exactly.** Same image, same options, same JVM → identical
bytes; a test asserts it. Across JDKs the deflate payload is `java.util.zip`'s, so a
checked-in reference file belongs to a pinned toolchain, and a test that must survive a JDK
upgrade compares decoded pixels rather than bytes. Stated on `PngEncoder` so nobody
discovers it from a red build.

### 3.3 The SPI streams

`encode(Image, options, OutputStream)`, not `-> byte[]`. It costs nothing, it is harder to
use wrong, and `PngEncoder` genuinely streams: deflated output is emitted as IDAT chunks of
at most 64 KB, so peak extra memory is that buffer rather than the whole file. `Images`
offers the `byte[]` and `Path` conveniences over it.

### 3.4 Sub-rectangle readback ships now

`readSceneReferred(x, y, w, h)` and `readDisplayReferred(x, y, w, h)` are the abstract
methods; the whole-surface forms are `default`s over them. A rectangle is one argument
group on a `glReadPixels` that had to be written anyway, and adding it later would have
meant either four more methods or a source-incompatible change to two.

### 3.5 `saveAsync` exists; async *readback* does not

`Images.saveAsync` mirrors `loadAsync` on the `Ui` worker pool. It is not premature: at
framebuffer sizes the encode is long enough to be a visible hitch inside a frame, and the
split point is exactly right: the readback is a GPU operation that cannot leave the UI
thread, and everything after it is CPU work on a finished `Image`.

Asynchronous *readback* (a PBO ring, so the CPU never waits for the GPU) is **not**
implemented. It is a real optimisation and a real complication (a frame of latency the
caller must be told about), and nothing here needs it yet. What it must not do meanwhile is
be documented away: `ReadableSurface` says on the member that the read is a synchronous GPU
stall which profiles as "the renderer got slow".

### 3.6 `captureNextFrame` was refactored in the same change

Not left as a follow-up. Leaving it would have meant shipping the second PNG writer this
ADR exists to prevent, for however long the follow-up took. The `Path` overloads survive as
`default` methods, so every existing caller (the demo's `--screenshot` machinery above all)
is untouched.

---

## 4. What changed that is visible

- **A transparent window's screenshot is no longer premultiplied.** `GlRenderer` used to
  write the framebuffer bytes out verbatim; the canvas blends premultiplied, and `Image` is
  straight alpha by contract, so the capture now divides alpha back out. For an opaque
  window (alpha 255 everywhere) this is exactly a no-op. For the transparent-window gadget
  it is a correction: partly transparent pixels were being darkened.
- **`RenderTarget` gained two abstract methods.** Any implementation outside this
  repository stops compiling. There is one inside it.
- **`GpuRenderer` and `NativeWindow` each gained one abstract method** and turned their
  `Path` method into a `default`. Test doubles implement the `Consumer` form instead.
- **`Viewport3D.captureNext(Consumer<Image>)`** joins the two halves at the component
  level: it defers to the next paint (the only place the window's GL context is current),
  reads the target display-referred, and hands over an `Image` ready for `Images.encode`.

---

## 5. Rejected

- **`ImageIO`'s shape.** Not available (it is `javax.imageio`, and this toolkit imports no
  AWT), but it is also the catalogue of what not to repeat: `ServiceLoader` discovery with
  an unspecified order, `write` returning `false` when no writer is found, writers that
  silently drop what they cannot represent, parameters through a class hierarchy, disk
  caching on by default. Each has a counterpart here: explicit `install`, a throw that
  names the providers, `supports` as a declaration the facade enforces, a record for
  options, and no hidden state.
- **A colour-space flag on one readback method.** §2.
- **Scene-referred pixels in an `Image`.** §2; the type system does the work instead.
- **An `sRGB` chunk in the PNG.** `Image` does not carry a colour space, so writing one
  would assert something the type does not know. Metadata generally is out of scope: no
  text chunks, no profiles, no timestamps, which is also what keeps the output
  deterministic.
- **Encoding into an offscreen 2D canvas.** There is still no way to render arbitrary 2D
  widget content into a `GpuSurface`; readback covers the surfaces that exist plus the
  window. That gap is unchanged by this ADR and is the obvious next thing someone will want.

---

## 6. Risks and what is not asserted

- **The CPU display transform can drift from `canvas.frag`.** The two implementations are
  named at each other in Javadoc, and `GlSurfaceReadbackTest` checks the CPU path against
  `ColorSpace.displayTransform`, which is the same function, so it proves the plumbing
  (un-premultiply, exposure, flip) and not the agreement. Asserting the agreement means
  compositing a known surface through the real shader and comparing with a readback of the
  same target; that is a worthwhile test and it is not written. *Needs a GPU; the existing
  headless-GL harness is the place for it.*
- **`glReadPixels` of an RGBA16F attachment as `GL_FLOAT`** is core in GL 3.3 and works on
  the macOS 4.1 driver (the readback tests run there, MSAA included). *Unverified under
  ANGLE / GL ES 3.0, where the format is behind `EXT_color_buffer_float` already. ADR 004
  §5 owns that gate; this adds no new one.*
- **Encode cost is not budgeted.** Nothing measures how long a framebuffer-sized PNG takes,
  and the demo prints a figure rather than asserting one. `saveAsync` exists precisely
  because that figure is not small.

---

## 7. Definition of done, as implemented

- Encoder SPI, facade and PNG encoder in `limn-toolkit`; readback capability there too.
- Backend implements readback on `GlRenderTarget` and framebuffer capture in `GlRenderer`;
  no `org.lwjgl` moved anywhere it was not already. `checkArchitecture` passes.
- Tests: `ImageEncodingTest` (round trip through a PNG reader written independently for the
  tests, determinism, row order, straight alpha at alpha 0, multi-chunk payloads, the
  failure message naming every provider) with **no backend running**; `PngRoundTripTest`
  decoding the output with stb_image, which shares nothing with the encoder;
  `GlSurfaceReadbackTest` on a real device for row order, un-premultiply, the MSAA resolve,
  rectangle bounds and reading a disposed target.
- A Kitchen Sink tab (`Export`, also `--scene export`): a picture drawn in code, encoded,
  decoded and shown beside the original, plus a window capture and a 3D viewport capture
  through the same round trip. It writes nothing to disk.
