# ADR 007: Video crosses the SPI as planar YCbCr, and the matrix runs on the device

- **Status:** Accepted, 2026-08-03. Implemented as phase 2 of the video player: `VideoSurface`
  and its facade in `limn-toolkit`, `GlVideoSurface` and a conversion program of its own in
  `limn-backend-lwjgl`.
- **Date:** 2026-08-03
- **Scope:** what a decoded picture looks like when it crosses Limn's SPI, where the colour
  matrix is applied, and how the result reaches the screen.
- **Audience:** whoever writes a decoder, a second backend, or the ES 3.0 port. Extends
  ADR 001 (GL 3.3 core, and the GLSL subset a shader may use); does not touch ADR 004, and
  §3 says why not.

---

## 0. The two findings that decide the shape of this work

**Finding 1: every decoder that could ever sit behind this SPI already holds planar YCbCr,
and none of them holds RGBA.** Software decoders for H.264, VP9 and AV1 hand back I420;
hardware decoders and capture devices hand back NV12; screen capture and lossless
intermediates hand back I444. An SPI defined in RGBA does not remove that conversion, it
relocates it: every implementation would have to convert on the CPU before returning, and
each would do it slightly differently.

**Finding 2: the conversion is the expensive part, and it is the part a GPU is built for.**
A 1080p 4:2:0 picture is 3.1 MB of samples and 8.3 MB of RGBA. Converting on the CPU costs a
per-pixel matrix over two million pixels and then moves 8.3 MB across the bus, thirty times a
second: a quarter of a gigabyte per second of upload for a single stream, before any
compositing. The same matrix in a fragment shader runs on the pixels the display is about to
show, from 3.1 MB of upload.

---

## 1. Decision

1. **A frame crosses the SPI as planar YCbCr**: one `ByteBuffer` per plane, row spacing in
   **bytes**, geometry derived from `PixelFormat` and the frame's own dimensions. I420, NV12
   and I444; 8-bit.
   *Amended by ADR 014, 2026-08-05: …or an opaque device handle the backend knows how to bind, and
   a consumer that cannot bind one asks for the download instead. A hardware decoder's picture is
   memory a device owns, so `VideoFrame` carries a kind and a `long` beside its planes,
   `plane(int)` refuses on a handle rather than answering plausibly, and `toPlanar()` reads one
   back. Decisions 2 to 5 are untouched, because what a hardware decoder hands back is the same
   NV12 the conversion program already read; only where its input textures come from changed.*
2. **The colour matrix is applied on the device**, in a program of its own, writing an RGBA8
   picture the 2D composite draws as an ordinary quad.
3. **The coefficients are uploaded as uniforms, from `VideoColor`.** Not one of the six is
   spelled in GLSL. The shader also never spells the chroma neutral or the luma offset: it
   scales samples back to code space and subtracts the uniforms, so there is exactly one
   table in the project and both consumers read it.
4. **The converted picture is display-referred**, and takes the composite's ordinary image
   path rather than its HDR-surface path.
5. **Plane samples are addressed by integer texel index**, not by a normalized coordinate.

---

## 2. Why a program of its own, and not a branch of the canvas shader

The canvas program binds exactly one texture, and the batch draws as much of a frame as it can
in a single `glDrawElements`, a property that is most of the measured difference between this
toolkit and the one it is benchmarked against. A converter needs three textures at once, so a
fourth branch in the canvas fragment shader would mean three texture units bound for every
glyph in the toolkit.

The cost of the choice is one offscreen draw per uploaded picture, over exactly the pixels
that picture occupies, plus one RGBA8 texture per stream. The batch itself is untouched: the
converted picture arrives at it as a texture like any other and costs it a texture switch,
exactly as a drawn image does: not a special case, and not free either.

## 3. Why the ordinary image path, and not the HDR-surface path (ADR 004)

Both are `GpuSurface`s composited by the same call, and it is tempting to give them the same
shape kind. They must not have it. A 3D target holds premultiplied **linear scene-referred**
light and the composite owes it a display transform. A decoded picture is already
display-referred and gamma-encoded; that is what the samples in the file *are*. Sending it
through the HDR branch tonemaps and re-encodes an image that has already been encoded once.
The result is not subtle and it is not a darkening: with the transform the composite ships
today, mid grey lands most of the way to white and only the top of the range comes down, so
the picture arrives milky and low-contrast, which is exactly how a wrong colour matrix reads,
and why the distinction is asserted with pixels rather than described in a comment.

The corollary for the future: a 10-bit HDR video path is **not** a wider version of this one.
Its samples would be scene-referred after their own transfer function, and it belongs on the
HDR branch. Phase 8 decides that; nothing here forecloses it.

## 4. Why integer texel addressing

Chroma for pixel *x* is sample *x >> shift*. Nearest-neighbour filtering at the normalized
coordinate `(x + 0.5) / width` picks `floor((x + 0.5) × chromaWidth / width)` instead, and
those two disagree whenever the width is odd: at width 5, pixel 3 lands on chroma sample 2
where the reference converter says sample 1. The disagreement is a coloured stripe down one
edge of the picture and nowhere else, so it survives every uniform-colour test that would
otherwise catch a chroma bug.

Integer addressing removes the question rather than answering it. Plane textures are still
created with nearest filtering, because that is the contract a sampled path would have to
honour, and because a filter setting nobody relies on is a filter setting nobody checks.

## 5. What this costs, and what it forbids

- **Strides are in bytes and row lengths are in samples.** A device wants the second; the SPI
  publishes the first. For a one-byte sample they coincide; for NV12's two-byte interleaved
  chroma sample they do not, and an odd byte stride cannot be expressed as a row length at
  all. That plane is copied tight instead, which is the only copy in the path.
- **Unpack alignment must be 1.** The default is 4, and every plane whose byte width is not a
  multiple of four (most odd widths) is read one skewed row at a time otherwise.
- **A plane may end at its last sample.** The row length bounds a texture upload to exactly
  that, so such a plane uploads whole and without a copy; a size check computed as
  `stride × rows` would reject a buffer that reads perfectly well.
- **Heap planes cannot be uploaded where they lie.** A read-only heap buffer has no address to
  hand a device, so it is staged through a direct buffer the surface owns and reuses. The
  policy is stated on the SPI: a producer that hands over direct memory pays no copy, and one
  that does not pays one, and neither allocates per picture.
- **NV12 is not three single-channel textures.** Its chroma plane is two bytes per sample, so
  it is a two-channel texture and the program reads both components of one fetch.

## 6. What this deliberately is not

- **Not chroma interpolation.** Chroma is replicated, matching the reference converter
  exactly. Interpolating additionally requires knowing where chroma samples sit relative to
  luma, which a stream signals and which the SPI does not yet carry.
- **Not 10-bit, and not HDR.** `PixelFormat` reports a bit depth so the arithmetic has one
  place to change, and nothing else here assumes eight bits, but no 10-bit constant exists.
  *Answered by ADR 016, 2026-08-04: 10-bit layouts exist, the coefficient accessors take the
  depth, and the conversion target follows it. HDR does not: §3's forecast held, and what
  decides the composite path is the transfer function rather than the depth, so a
  display-referred 10-bit picture takes this ADR's ordinary path unchanged and a PQ or HLG
  one is refused rather than shown.*
- **Not a widget.** A surface is uploaded and drawn into a rectangle the caller chooses.
  Measuring, letterboxing and aspect ratio are a later phase's.
- **Not a decode thread.** Uploading is UI-thread work by contract and asserted as such; the
  producer side is a later phase's, and this is the seam it will hand pictures to.

## 7. Risks and what only hardware settles

- **Rounding at the last bit.** The reference converter rounds halves up in double precision;
  a device computes in single precision and the framebuffer rounds to nearest with ties
  unspecified. Where the exact arithmetic lands more than a few hundredths of a code from a
  tie there is one right answer and the device is held to it exactly; within that band either
  neighbouring code is accepted. On the ES 3.0 port the guaranteed precision of a
  high-precision float is coarser, and that band is what absorbs it.
- **The conversion runs where the picture is uploaded, and the composite samples the texture
  when the batch draws.** Those are not the same moment, and nothing in between is the caller's
  to control, so uploading one surface twice between two draws of it in a single frame leaves
  the earlier draw showing whichever picture was resident when the batch flushed. The contract
  is therefore one upload per surface per frame, stated on the SPI. Making it deterministic
  instead costs a batch flush per upload (a draw call per frame in every window with video in
  it) and buys a sequence a player has no reason to perform. Freeing a texture is the one case
  that is not left to chance: a resize or a disposal flushes first, because a quad already
  queued against the old texture would otherwise be drawn after its name had been recycled.
  *Extended by ADR 014 §8, 2026-08-05: a picture bound from a decoder's own memory rather than
  copied into this surface's reverses that. The conversion **reads** memory the surface does not
  own, the draw only queues that read, and releasing the frame gives the memory back to be
  refilled, so an upload that binds waits for the device before it returns. Draining rather than
  holding the picture longer is deliberate: holding pins a decoder's buffer for a whole frame, and
  on a discrete GPU that is VRAM its pool was sized on the assumption of getting back.*
- **A device with a smaller texture limit than the picture.** Refused with the limit in the
  message, rather than left as a blank rectangle.
