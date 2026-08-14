# ADR 014: Hardware decode is a seam, and on one platform the seam is now cut

- **Status:** **Accepted, 2026-08-05**. Route B, implemented as phase 8c on macOS: VideoToolbox in
  the shim, an `IOSurfaceRef` across the frame SPI, `CGLTexImageIOSurface2D` onto rectangle textures
  in `limn-backend-lwjgl`, a second fragment source, `PixelFormat.P010`, and route A kept as the
  fallback every consumer without a device takes. No `limn-video-macos` module; the FFmpeg decoder
  gained an accelerator and stayed a `VideoDecoder`.
- **Date:** 2026-08-04, amended and accepted 2026-08-05
- **Scope:** what a hardware-decoded picture actually is, why the frame SPI could not carry one, the
  three routes that could, which was taken, what it cost, and what is still one platform's answer.
- **Audience:** whoever implements this on Windows or Linux, or ports it to ES 3.0. §5 is what it did
  to ADR 007; §6 is the correction to this ADR's own first recommendation; §7 is the measurement and
  the caveat that measurement carries; §8 is the bill.

---

## 0. The finding that decides the shape of this work

**A hardware-decoded picture is not memory. It is a handle, and the handle is a different type on
every platform and a different type again depending on which graphics API the window is using.**

| Platform | What the decoder hands back | What binds it to a texture |
|---|---|---|
| macOS, VideoToolbox | `CVPixelBuffer` backed by an `IOSurface`; NV12 or its 10-bit sibling | `CGLTexImageIOSurface2D` onto a `GL_TEXTURE_RECTANGLE`, which is a **CGL** call, and rectangle textures are not in ES 3.0 |
| macOS, VideoToolbox, under ANGLE/Metal | the same `CVPixelBuffer` | an `MTLTexture` through a `CVMetalTextureCache`, reached from EGL by a different extension entirely |
| Windows, D3D11VA / Media Foundation | `ID3D11Texture2D`, usually a slice of a texture array; NV12 or P010 | `WGL_NV_DX_interop2` on desktop GL, a **vendor** extension, not universal |
| Windows, under ANGLE/D3D11 | the same `ID3D11Texture2D` | `EGL_ANGLE_d3d_texture_client_buffer`: an EGL call with no GL equivalent |
| Linux, VA-API | `VASurfaceID`, exported as DRM PRIME dma-buf file descriptors | `EGL_EXT_image_dma_buf_import` + `glEGLImageTargetTexture2DOES`, which is **EGL only**, so a GLX desktop context needs EGL anyway |

Read that table twice, because the second reading is the point: **the rows differ from each other,
and the macOS and Windows rows differ from themselves depending on ADR 003.** A seam designed
against any single cell of it fits exactly one implementation.

---

## 1. Why `VideoSurface.upload(VideoFrame)` could not take one as it stood

ADR 007 defines a frame as planar YCbCr the consumer reads: one `ByteBuffer` per plane, row spacing
in bytes, geometry from `PixelFormat`. Every word of that fails for a hardware picture.

- **There are no plane buffers.** The samples live in an allocation the decoder or the driver owns,
  in a tiled or otherwise opaque arrangement, in memory the CPU may not be able to address at all.
  `VideoFrame.plane(int)` has nothing to return, and a contract that says "direct or heap, whichever
  the producer bound" has no third answer.
- **The lifetime is not the pool's.** A `CVPixelBuffer` is reference counted by CoreVideo, a D3D11
  texture slice is recycled by the decoder's own array, a VA surface is owned by the VA display.
  ADR 012's slot-index handback is exactly the right shape for those, but the *thing* the slot
  refers to is a handle, not a mapping.
- **`upload` is defined as a copy.** It fills this surface's own plane textures from the frame's
  bytes. A hardware picture does not want to be copied into a texture; it wants to *be* one, which is
  a different verb.
- **And the handle has no type the toolkit may name.** `limn-toolkit` depends on nothing and may not
  see a codec type or a GL type. `CVPixelBufferRef`, `ID3D11Texture2D*` and `VASurfaceID` are all
  three of those at once.

What survives untouched is the part ADR 007 got right for the wrong reason: **a hardware decoder
hands back NV12 or P010, which is planar YCbCr**, so the conversion program stays exactly as it is.
What changes is only where its input textures come from.

## 2. Route A: download to the CPU, and keep every SPI

`CVPixelBufferLockBaseAddress`, or a copy into a D3D11 staging texture and `Map`, or `vaDeriveImage`
plus `vaMapBuffer`. Point a `VideoFrame`'s planes at the mapping and nothing above the shim knows
anything happened.

**What it costs.** One full read-back per picture, out of memory that is usually uncached and
sometimes across a bus. It is the reason hardware decode is routinely *slower* than software decode
for ordinary 1080p content when the picture has to come back to the CPU: the decode is free and the
transfer is not, and the same bytes then go straight back to the GPU as an upload.

**What it buys.** Battery and CPU on high resolutions and high bit rates, where the decode itself is
the expensive part; 4K HEVC is a different arithmetic from 1080p H.264. And on a machine whose CPU
cannot decode 4K at all, it is the difference between playing and not.

**What it forecloses: nothing.** Not one line of any SPI changes, ADR 007 is untouched, and every
existing test still describes the path. It is the only route that can be implemented per platform,
independently, without a design decision preceding it.

**And it was the route to measure first**, because the question behind everything else was whether
it is fast enough on its own. §7 is that measurement, and the answer it gave was *not quite*: route A
is worth having over software decode at both resolutions, and its download is a quarter of the
remaining CPU at 1080p and half of it at 4K. So it stayed (as the fallback every consumer without a
device takes) and it did not settle the question.

## 3. Route B: the frame SPI carries a device handle

`VideoFrame` gains a kind and an opaque handle beside its planes; `VideoSurface.upload` learns to
bind rather than copy when it sees one.

**What it costs.** ADR 007's central sentence stops being true (§5), and the toolkit acquires a type
whose meaning it cannot state: a `long` that is a `CVPixelBufferRef` on one platform, an
`ID3D11Texture2D*` on another and a dma-buf file descriptor on a third (plus, per §0, a *different*
binding call for the same handle depending on whether the window is desktop GL or EGL). Whoever holds
a frame must now also know which of those it is, and the backend must refuse the ones it cannot
bind, at a point where the picture is already decoded and the alternative is a black rectangle.

It also puts a lifetime across the boundary that the slot index was designed to avoid: the surface
now holds a texture derived from the decoder's allocation, and ADR 007 §7 already records that
freeing a texture has to be sequenced against the batch. Doing that for memory a decoder owns, on a
thread that is not the decode thread, is the hardest lifetime in the subsystem.

**What it buys.** Zero copy, on the platform and context where the interop exists. **This is the
route that was taken**; §8 is what each of the costs above actually turned out to be.

**What it forecloses.** The frame stops being a thing a consumer can simply read, so every consumer
that is not the GL backend (`YuvConverter`, `Y4mWriter`, a test, a future encoder) needs a
fallback path to the download route anyway. Route B does not remove route A; it adds to it. That
turned out to be one method, `VideoFrame.toPlanar()`, and the fact that a consumer must ask for it
rather than receive it silently is the point: a whole picture across a bus is not something to do
by accident.

## 4. Route C: a platform module that never produces a frame

`limn-video-macos` owns VideoToolbox and the interop, and publishes a **surface** rather than a
stream of frames: it produces the device-side picture directly and the widget draws it.

**What it costs.** A second decode path with its own seek, its own clock handling, its own end and
failure states, and none of the tests that hold the FFmpeg path to the reference converter, because
there is no frame to compare. It also needs a way for a widget to accept a surface it did not create,
which is a component-level change ADR 009 did not anticipate.

**What it buys.** Zero copy without touching a single existing SPI, and the freedom for each platform
module to use whatever primitive that platform actually has, which, per §0, is the one property all
three platforms would otherwise fight over.

**What it forecloses.** Sharing anything. Two decoders that produce different things cannot be
swapped by an install order, which is what ADR 011 §5 relies on for the patent argument: `Videos` is
an ordered list precisely so the operating system's decoder can lead. A module that does not
implement `VideoDecoder` is not in that list.

## 5. What this did to ADR 007

**Route B was taken, so ADR 007's first decision is amended and the other four are not.**

> 1. A frame crosses the SPI as planar YCbCr: one `ByteBuffer` per plane […]

now reads: *…or an opaque device handle the backend knows how to bind, and a consumer that cannot
bind one asks for the download instead.* `VideoFrame` carries a `Kind` and a `long`; `plane(int)`
fails on a handle rather than answering plausibly; `toPlanar()` is the download, and it is what
`YuvConverter`, `Y4mWriter` and every test reach for when handed a picture they cannot read.

Decisions 2 to 5 (the matrix on the device, the coefficients as uniforms, the display-referred
output, integer texel addressing) survive without a word changed, because a hardware decoder hands
back the same NV12 the conversion program already read. What changed is only where its input
textures come from, and that turned out to be one branch in `upload` and one extra fragment source.

**ADR 016's P010 note is now live and is answered.** `PixelFormat.P010` exists, its codes are
left-justified, and the layout carries that as `codeShift()` so that `componentAt`/`putComponent`
are still the one place a wide sample's spelling lives. On the device it fits the **existing**
`u_sampleScale` uniform (the scale is the storage maximum divided by 64), so the shader's uniform
set did not move. That is the pleasant part and the dangerous part at once: **nothing structural
guards it.** A P010 plane read as an ordinary 10-bit one has the right plane count, the right
strides, the right byte widths and a picture 64 times too bright. The tests are the only guard, and
they were written before the constant was.

## 6. ADR 003 is a risk here and not a dependency: the correction

**This ADR's first recommendation was that hardware decode should wait behind ADR 003's ANGLE spike.
That was wrong, and this section is the argument rather than the deletion.**

What the original said was true as far as it went: §0's table has two rows per platform, the second
is ANGLE's, and the interop primitive on a GL 3.3 desktop context and on an ES 3.0/EGL one are not
the same call, not the same extension, and on macOS not even the same graphics API. A seam cut
against desktop GL and then ported to ANGLE has its interop half cut twice.

Three things make that a risk to be priced rather than an order to be obeyed.

- **ANGLE is not required by any route, and EGL is not ANGLE.** Nothing in route B needs an EGL
  context; it needs *an* interop primitive, and desktop GL on macOS has one that works today.
- **The default context is the one that exists.** ADR 003 §3's own corollary is that native desktop
  GL stays the default where it works, which is every macOS machine this ships to. A seam that only
  works under a port nobody has started is worth less than one that works under the context
  everybody has.
- **The cost of being wrong is bounded and local.** If ANGLE ever happens, what is re-cut is
  `IoSurfaces` and one fragment source. `VideoFrame`'s kind-and-handle, the download fallback, the
  lifetime rule, `PixelFormat.P010` and the shim's whole VideoToolbox path are context-independent
  and would be re-used unchanged. The second fragment source is a *file* precisely so that the port
  can decline to load it rather than discover a permanently uncompilable branch inside the one
  shader every window needs.

Against that, waiting had a cost the original section did not price: a decoder that the operating
system provides is ADR 011 §5's strongest patent argument, and it would have stayed unbuilt behind
a port that is `Proposed` and unstarted.

**The cheerful note survives and is still true.** The EGL row is the portable one:
`EGL_EXT_image_dma_buf_import` on Linux and `EGL_ANGLE_d3d_texture_client_buffer` on Windows are
both EGL, so if the port happens the three platforms converge on `eglCreateImage` plus one
platform-specific client buffer. That is an argument for doing Windows and Linux *after* ANGLE. It
is not an argument for having done macOS after it, because macOS's non-EGL answer exists and its
EGL answer would have been a third thing again (`CVMetalTextureCache`).

## 7. Settled by the measurement: 2026-08-04

Route A was measured before any of this was built, which was §7's original recommendation and is the
one part of it that held. M3 Pro, macOS 26.6, FFmpeg 7.1.5 arm64, `/usr/bin/time -l`, interleaved,
median of five, over synthetic `testsrc2` clips at realistic bit rates: H.264 1920x1080 30fps
8.02 Mbit/s 900 frames, HEVC 3840x2160 30fps 25.11 Mbit/s 900 frames.

| clip | variant | real | user | sys | CPU | maxRSS |
|---|---|---|---|---|---|---|
| 1080p | software    | 0.65 | 3.00 | 0.14 | 3.14 s | 110 MB |
| 1080p | route A     | 4.16 | 0.60 | 0.56 | 1.16 s | 120 MB |
| 1080p | decode only | 4.04 | 0.31 | 0.62 | 0.93 s |  75 MB |
| 4K    | software    | 2.84 | 14.27| 0.22 | 14.49 s| 532 MB |
| 4K    | route A     | 7.16 | 1.74 | 0.59 | 2.33 s | 548 MB |
| 4K    | decode only | 6.24 | 0.51 | 0.69 | 1.20 s | 347 MB |

**What it says.** Hardware decode is worth having at both resolutions on CPU time (a third at
1080p, a sixth at 4K), and route A's download is not free even here: it is a quarter of the total
CPU at 1080p and half of it at 4K, and it is the entire difference between "route A" and "decode
only". Wall clock is *worse* for hardware at both sizes, which is the accelerator running at its own
pace rather than as fast as a saturated CPU can; for a player that is irrelevant, because a player
wants 30 pictures a second and not as many as possible.

**Package power is not in the table and no proxy stands in for it.** `powermetrics` needs a `sudo`
that was not available on this machine. The row is absent rather than estimated.

### The caveat, which belongs here and not in a footnote

**These numbers are a floor and not a result.** They were taken on unified memory, which is route
A's *best possible case*: `CVPixelBufferLockBaseAddress` maps the same physical pages the decoder
wrote and moves nothing at all. On a discrete GPU route A crosses the bus twice per picture (VRAM
to RAM in the slow direction, then RAM back to VRAM as an upload), and the picture occupies both
memories at once while it does. Nothing in this table would predict that machine.

What is hardware-independent is the byte count, and it is the honest way to state the cost:

| picture | bytes | at 30 fps |
|---|---|---|
| 1080p NV12 | 3.11 MB | 93 MB/s |
| 4K NV12 | 12.44 MB | 373 MB/s |
| 4K P010 | 24.88 MB | 746 MB/s |

**Route A pays each of those twice**: once coming back and once going out again. Route B pays them
zero times. That is why the measurement did not settle the routes on its own: it settled that
hardware decode is worth having, and the arithmetic above settled that route A is a fallback rather
than an answer.

**And it is what decided the lifetime rule** (§8). Given the choice between holding a picture longer
and draining the device before releasing it, holding pins a decoder's buffer, which on unified
memory is one slot of a small pool and on a discrete GPU is VRAM the decoder sized its pool on the
assumption of getting back. The decision was made against the hardware the measurement was *not*
taken on.

## 8. What was built, and what it cost

**The shim gained an accelerator, not a module.** A VideoToolbox device context, a `get_format` that
selects `AV_PIX_FMT_VIDEOTOOLBOX`, and `CVPixelBufferGetIOSurface` called on the C side so that Java
receives the handle GL actually wants and `limn-backend-lwjgl` never links CoreVideo. The slot and
binding-epoch machinery of ADR 012 §3 was extended rather than duplicated: a slot is already an
integer over memory the far side owns, and this is that with a handle instead of three addresses.

**An accelerator is attached before it can be asked whether it will take the stream, so the shim
decodes one picture at open.** `get_format` runs on the first picture, not at `avcodec_open2`, and
an accelerator that then declines (an unsupported profile, a codec it has in its table and not in
its silicon) makes libavcodec fall back to software in silence. Without the probe, `open` would
have reported NV12 and delivered I420, and every read would have been refused with a message about
the stream changing format when nothing changed. `isHardwareDecoding()` therefore reports what
happened and never what was asked for.

**The backend binds and does not copy.** `CGLTexImageIOSurface2D`, reached through
`org.lwjgl.system.libffi` because LWJGL 3.4.1 has no `JNI.invoke…` overload for its nine-argument
signature (still `org.lwjgl`, still inside the one module allowed to see it). It forces
`GL_TEXTURE_RECTANGLE`: `GL_TEXTURE_2D` returns `CGLError 10008`, measured. Hence the second
fragment source, because a rectangle sampler is a different keyword, `texelFetch` on one takes no
level argument, and GLSL ES 3.00 has no `sampler2DRect` at all.

**The fourth lifetime, which is the part that was dangerous.** ADR 007 §7 already sequenced texture
*deletion* against the batch. Binding makes the reverse true as well: the conversion reads the
decoder's own memory, `glDrawArrays` only queues that read, and `release()` hands the buffer back to
be refilled. Extending §7 rather than inventing a second mechanism, and deciding it by §7's caveat
above: **drain the device before returning from `upload`, and do not extend the possession.** The
cost is one fence per picture. The alternative pins VRAM a decoder is entitled to.

**What it cost, itemised.**

- **Payload: +0.03 MB per architecture** in the shipped `player` profile (4.77 to 4.80 MB on
  arm64 and 6.60 to 6.63 MB on x86_64), both under one percent. VideoToolbox is a system framework
  and is linked, not bundled; what grows is the two hwaccel wrappers. ADR 015 §2's table gains a row
  and none of its conclusions move.
- **A licence question that is not one.** `--enable-gpl` and `--enable-nonfree` are still absent and
  `LicenceTest` is unchanged. An accelerator is Apple's implementation on Apple's side of the line,
  which is ADR 011 §5's argument for wanting one.
- **A configure trap, met for the second time.** `--enable-videotoolbox` switches on the
  *framework* and not one accelerator; `--disable-everything` had turned every hwaccel off and only
  `--enable-hwaccel=<name>` turns one back on. A build with the framework and no accelerator attaches
  a device, decodes in software and looks correct from outside. ADR 015 §2's "a configure flag is a
  claim" now applies to hardware configurations too, and they are asserted out of the linked library.
- **A sixth `PixelFormat`, and a justification nothing structural checks.** §5.
- **A second fragment source to keep in step with the first.** `VideoShaderTest` is parameterised
  over both and additionally asserts that the two are the same conversion modulo the sampler, and
  that its own checks fail when a coefficient is typed into either.
- **A fence per hardware picture.** §7's caveat is why.

**What the evidence is, honestly.** The zero-copy half (the binding, the rectangle sampler, P010's
normalisation, the orientation, and the release discipline) is tested on any Mac against IOSurfaces
the backend's own suite writes in the two layouts VideoToolbox produces; it needs no decoder and no
media. The decoder half has the three tiers ADR 015 §3 defines, and one of them is thinner than it
looks: **Apple Silicon's VideoToolbox has no MPEG-4 Part 2 decoder**, which is the only codec both
this repository can encode and an accelerator could take, so the end-to-end hardware round trip
skips there and runs only where that decoder exists. What runs everywhere in its place is the
assertion that `open` reports the shape the pictures actually have, which is exactly the failure
that absence produced, and the reason the probe exists.

## 9. What this deliberately does not decide

- **Anything about Windows or Linux.** Phase 6b, and §0 is why naming a module is not the same as
  knowing what is inside it: `WGL_NV_DX_interop2` is a vendor extension and VA-API's route is EGL
  only. Neither is reachable from what was built here, and both would extend the same `Kind`.
- **Route C.** It is not foreclosed and it is not needed: `Videos` is still an ordered list of
  `VideoDecoder` and an operating system's decoder is an entry in it, which is what ADR 011 §5's
  install-order argument depends on.
- **Whether the default should be hardware.** It is (`FfmpegMedia.Hardware.PREFER`) on the ADR 004
  §5 ground that a branch nobody exercises rots. An application that wants samples and not handles
  asks for `OFF` and pays nothing.
- **The HDR question.** Unchanged by any of this: a P010 picture is refused by transfer function
  exactly as a 10-bit planar one is, and ADR 016 §6 still lists the five things an HDR path needs.

## 10. What exists today, so the starting point is not misremembered

- The shipped build carries **two** hardware accelerators, `h264_videotoolbox` and
  `hevc_videotoolbox`, and nothing else; the `full` profile adds `mpeg4_videotoolbox` for the round
  trip and it ships nowhere.
- `VideoFrame` carries a slot index, a kind and a handle, and `plane(int)` refuses on a handle.
- `VideoSurface.upload` copies for a planar picture and binds for a handle-backed one, on the UI
  thread either way.
- `Videos` is an ordered list of `VideoDecoder`, and the operating system's decoder is inside one of
  its entries rather than beside them.

## Sources

- `libavcodec/videotoolbox.c`, `libavcodec/d3d11va.c`, `libavcodec/vaapi_decode.c` in the
  ffmpeg-7.1.5 tree the build script pins: the three handle types in §0 are the ones those files
  hand back
- ADR 003 §3, §4.3 and §7, for the context-selection order §6 argues with
- ADR 007 §1 and §7, for the sentence route B amended and the texture-lifetime rule §8 extends
- ADR 011 §5, for the patent argument that makes an operating system's decoder worth having
- ADR 015 §2 and §3, for the payload table §8 adds to and the three tiers of evidence it reports in
- ADR 016 §2, whose "P010 is absent, and this is the interesting one" is what §5 answers
