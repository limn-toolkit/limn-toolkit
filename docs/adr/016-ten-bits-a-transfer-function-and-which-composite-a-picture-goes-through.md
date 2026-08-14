# ADR 016: Ten bits, a transfer function, and which composite a picture goes through

- **Status:** Accepted, 2026-08-04. Implemented as phase 8b of the video player:
  `PixelFormat.I420_10LE`/`I444_10LE`, `VideoColor.Transfer`, the depth-taking coefficient
  accessors, `YuvConverter`, `video_convert.frag`, `GlVideoSurface`, the Y4M reader and writer, the
  synthetic generator and the shim's format map.
- **Date:** 2026-08-04
- **Scope:** what a picture with more than eight bits per sample looks like when it crosses the SPI,
  how the decode matrix changes with the depth, what target the conversion writes into, and which
  composite path the result takes.
- **Audience:** whoever adds a layout, writes a second backend, or implements the HDR path this
  stops short of. Extends ADR 007, whose §6 said "not 10-bit, and not HDR" and whose §3 forecast
  that the two are separate questions. Touches ADR 004 by deciding **not** to use its path, and adds
  one capability note to ADR 003.

---

## 0. The two findings that decide the shape of this work

**Finding 1: the depth and the transfer function are different questions, and conflating them is
the whole trap.** A 10-bit BT.709 recording is ordinary display-referred video that happens to carry
four times as many codes; a BT.2020 PQ one holds absolute luminance behind a curve and is not
viewable at all until that curve is inverted. Routing a picture by its bit depth would send the
first through a tonemap it must not have, and routing by nothing at all would show the second washed
out. So depth decides the *textures*, and the transfer function decides the *path*.

**Finding 2: ten bits into an eight-bit target is decorative.** The obvious implementation (widen
the planes, widen the matrix, keep the RGBA8 conversion target) produces a picture that is
bit-identical to decoding at eight bits, because the last thing the conversion does is quantize to
the target it writes into. Everything upstream of that would be correct, tested, and pointless. The
target format is therefore part of this decision and not an implementation detail.

---

## 1. Decision

1. **Two 10-bit layouts: `I420_10LE` and `I444_10LE`.** Samples are codes in `[0..1023]`, stored
   right-justified in a little-endian 16-bit word. §2 says why those two and not the others.
2. **`VideoColor` gains a `Transfer` (`SDR`, `PQ`, `HLG`), and an unsignalled transfer means SDR.**
   §3.
3. **Every folded coefficient accessor takes the bit depth**, and the no-argument forms are gone.
   §4.
4. **The conversion target follows the depth**: `RGBA8` for an 8-bit picture, `RGB10_A2` for a
   10-bit one. Both are normalized textures the composite samples identically, so the composite
   gains no branch at all. §5.
5. **The path follows the transfer, not the depth.** Every display-referred picture, at any depth,
   takes ADR 007's ordinary image path. A picture that is not display-referred is **refused**, at
   open by the source and again at upload by the surface. §6.
6. **Plane textures are `R16`/`RG16` with `GL_UNSIGNED_SHORT`, and there is no eight-bit fallback.**
   §7.

## 2. Which layouts, and why the ones that are missing are missing

`I420_10LE` is what HEVC Main 10, VP9 Profile 2 and AV1 Main 10 hand back, and it is by a wide
margin the commonest way a file carries more than eight bits. `I444_10LE` is what high-end
intermediates and screen capture produce, and it costs nothing beyond the constant because the
geometry is `I444`'s and the samples are `I420_10LE`'s.

**4:2:2 is absent at every depth, and that is a subsampling question rather than a depth one.** This
toolkit has no 4:2:2 layout at eight bits either. Adding one is a chroma geometry with a shift of 1
horizontally and 0 vertically, which every consumer already derives from `PixelFormat`. So it is a
small change, and it is simply not this phase's.

**12-bit and 16-bit are absent because nothing a consumer owns produces them.** The arithmetic
already generalises (`VideoColor`'s accessors are defined for `[8..16]` and the plane geometry
comes from `bytesPerSample`), so the cost of adding one is a constant and a sampler normalisation,
whenever a producer for it appears.

**Big-endian variants are absent and are refused by name.** No decoder on any platform this ships to
produces one, and a BE picture read as LE is uniform noise rather than a subtle error, so the shim
names the format it will not map instead of guessing.

**P010 is absent, and this is the interesting one.** It is NV12 at ten bits and it is what every
*hardware* decoder produces. It is left out for two reasons that reinforce each other. Its samples
are left-justified in the 16-bit word: the code is in the top ten bits, not the bottom ten. So it
needs a **different sampler normalisation** from the one every layout here shares, which is exactly
the kind of second constant that goes wrong quietly. And nothing in this repository produces one:
the only producer is hardware decode, which is ADR 014's subject and is not implemented. A layout
with no producer is a normalisation nothing exercises.

> *Answered by ADR 014, 2026-08-05: `PixelFormat.P010` exists, because hardware decode is now the
> producer this paragraph said it lacked. Both halves of the worry survived contact intact.*
>
> *The normalisation is genuinely a second constant, and it is genuinely quiet: the scale is the
> storage maximum divided by 64 rather than the storage maximum, which **fits the existing
> `u_sampleScale` uniform**; so the shader's uniform set did not move, the conversion program
> needed not one new line, and there is no compile error, no size mismatch and no plane-count change
> to catch a mistake. A P010 plane read as an `I420_10LE` one has the right strides, the right byte
> widths and a picture 64 times too bright.*
>
> *What guards it is therefore only what §8 says guards the 10-bit table: a test, written first and
> watched to fail. The justification is carried on the layout as `PixelFormat.codeShift()` and
> applied by `componentAt`/`putComponent`, so every CPU consumer works in codes and meets the shift
> nowhere; the device is the one consumer that does not come through those, and it is the one the
> tests point at.*

## 3. What an unsignalled transfer means, and why it is a choice

`Transfer.SDR` covers every characteristic a stream can signal that is not PQ or HLG: BT.709,
BT.601, sRGB, gamma 2.2, gamma 2.8, and "unspecified". They differ from one another by less than the
difference between two displays and, decisively, **none of them changes what a consumer must do**,
which is nothing. Collapsing them gives a caller a distinction it can act on instead of one it
cannot.

**An unsignalled transfer therefore means SDR, and that is a decision rather than an absence.**
Nearly every file that signals nothing is ordinary display-referred video. A picture assumed SDR
that is really PQ is visibly wrong the instant it is shown; in this toolkit it is refused before it
is shown at all, because the assumption is checked rather than merely made. The opposite default
would make every untagged file wrong, all the time, and quietly. So the fallback is the one that is
right almost always and loud when it is not.

The transfer is read from the container and carried separately from the matrix, because a file may
signal PQ while signalling no matrix at all. Reporting such a file as "unsignalled colour" would
lose the one fact about it that stops it being shown wrong.

## 4. Why the coefficients take a depth, and why there is still one table

Studio black is 16 at eight bits and 64 at ten. The chroma neutral is 128 and 512. The studio luma
gain is 255/219 and 1023/876; **those two are not the same number**, because studio levels
scale as `level << (n-8)` while the output span is `(1 << n) - 1`: four times 255 is 1020 and the
ten-bit maximum is 1023. Three parts in a thousand, which is invisible by eye and fatal to an exact
comparison.

So the folded coefficients are depth-dependent, and the question was where to put the depth.

- **A second table per depth** was refused for the reason ADR 007 §1.3 gives about the shader: two
  tables drift, silently, and the difference reads as a display profile rather than a bug.
- **No-argument accessors meaning eight bits** were refused because that is precisely the silent
  failure: a 10-bit picture decoded through the 8-bit table is four times too bright with black four
  times too high, and nothing anywhere says so.
- **Taking the depth as an argument** was taken. The eight-bit literals stay exactly as they are and
  a single scale factor carries them to any depth, a factor that is exactly 1 at eight bits, so
  every existing number is bit-identical rather than merely close. Omitting the depth is now a
  compile error.

The cost is a source-breaking change to a public API of a pre-1.0 toolkit, and it is the point: the
argument exists so that the mistake cannot be made silently.

**The 10-bit table is asserted from the recommendations' own spans, not from the 8-bit one scaled.**
Deriving it by multiplying by four is the plausible wrong answer, and a test that derived it the
same way the code does would pass on it.

## 5. Why RGB10_A2, and why the composite needed no change

Finding 2 rules out keeping RGBA8. Of the formats that hold more than eight bits per channel:

| | bytes/px | colour-renderable in GL 3.3 | in ES 3.0 core | composite change |
|---|---|---|---|---|
| `RGBA8` | 4 | yes | yes | none (but §0 finding 2) |
| **`RGB10_A2`** | **4** | **yes** | **yes** | **none** |
| `RGBA16F` | 8 | yes | needs `EXT_color_buffer_float` | a second branch, or ADR 004's |

`RGB10_A2` is the answer to a question the others answer worse. It is the same four bytes a pixel as
today, it is normalized (so the composite samples it exactly as it samples any other image and
gains not one instruction), and it holds every code a 10-bit picture decodes to. The two alpha bits
are enough for a decoded picture, which is opaque by definition.

**What ten bits buys on an eight-bit display, stated honestly.** The window's framebuffer is eight
bits, so the extra precision is not *seen*. What it buys is that the conversion no longer quantizes
twice (once into the target and once into the window), and that the path is depth-correct end to
end, which is what makes a future float target or a 10-bit swapchain a format change rather than a
re-plumb. The visible payoff today is that 10-bit files **play at all** and play with the right
brightness, where before they were refused by the reader and unmapped by the shim.

## 6. Why the ordinary path, and what the HDR path would actually need

ADR 007 §3 sends a decoded picture through the composite's ordinary image path because it is already
display-referred, and §3 of that ADR forecast that "a 10-bit HDR video path is not a wider version of
this one". Both halves of that survive, and the second half is the one this ADR has to be precise
about: **it is the transfer function that decides, not the depth.**

- **Display-referred, at any depth → the ordinary image path.** A 10-bit BT.709 file is ordinary
  video. Sending it through ADR 004's branch would tonemap and re-encode an image that is already
  encoded, which ADR 007 §3 describes exactly: mid grey lands most of the way to white and the
  picture arrives milky. Being 10-bit changes nothing about that.
- **PQ or HLG → ADR 004's linear `RGBA16F` path, and it is not implemented.** Such a picture is
  refused instead, by name, in two places: `FfmpegMedia.open` refuses the file, and
  `GlVideoSurface.upload` refuses the picture. Refusing at open is the idiom this subsystem already
  uses for a display matrix that is not a quarter turn: say what cannot be done rather than
  approximate it.

**What the HDR path would need, so the size of what is being deferred is on the record.** Not one of
these is optional and none of them is in this phase:

1. The inverse of the transfer, in the conversion program: PQ's is a rational power function, HLG's
   is the SDR curve below half scale and logarithmic above it, plus a system gamma that depends on
   the display's peak luminance.
2. **A primaries conversion.** HDR content is BT.2020 and a desktop display is not. A BT.2020 picture
   shown through an sRGB display's primaries is oversaturated in a way that looks deliberate, and no
   part of this toolkit carries a primaries matrix today.
3. A luminance decision. PQ is absolute, in cd/m², and the composite's exposure is a scene-referred
   multiplier. Something has to say what 1000 nits maps to, and that is a policy, not arithmetic.
4. An `RGBA16F` conversion target, which under ES 3.0 needs `EXT_color_buffer_float` (ADR 003 §4.4,
   ADR 004 §5).
5. A way for the display to be told, if the platform can show HDR at all; on macOS that is an
   EDR-capable layer and not a GL state.

That is a colour-management phase. Doing it badly is worse than saying so, and `Transfer` exists so
that the day it is done, the switch is a branch on a value the SPI already carries.

## 7. The upload, and the ES 3.0 gap this leaves open

A 10-bit plane is a `GL_R16` texture uploaded as `GL_UNSIGNED_SHORT`. Three consequences bite, and
each is the kind that produces a plausible-looking wrong picture:

- **Components decide the channel count and bytes decide the type, and the two stopped agreeing.** A
  10-bit luma plane is two bytes per sample and one channel; NV12's chroma is two bytes per sample
  and two channels. Code that derived the format from `bytesPerSample` was correct while a sample
  was either one byte or two-channel, and is wrong the moment it is neither.
- **`GL_UNPACK_ROW_LENGTH` counts samples and a stride counts bytes**, and they now differ by a
  factor of two for every 10-bit plane. A stride that is not a whole number of samples cannot be
  expressed as a row length at all, and such a plane is staged tight: the same branch NV12 already
  used, reached for a second reason.
- **The sampler normalises by the texel's width and the coefficients are per code.** A 10-bit code
  right-justified in a 16-bit texel normalises to `code/65535` while its code space ends at 1023, so
  the shader carries two scale uniforms and not one. Using either where the other belongs is a factor
  of 64, a black picture or a white one, which is at least loud.

**`GL_R16` is required by GL 3.3 core and is not in GL ES 3.0 core.** ES has `R16F`, `R16I` and
`R16UI`, but normalized 16-bit needs `EXT_texture_norm16`. That is a **fourth capability gate for
ADR 003 §4.4**, and it has a different answer from the other three: where the extension is absent,
the port moves the planes to `R16UI` and the sampler to `usampler2D`, which is core ES 3.0 and
core GL 3.3 and removes the normalisation constant entirely because `texelFetch` then returns the
code. It is a better shape and it was not adopted here for one reason: it changes the eight-bit
path too, which is pinned to literal outputs and working, for a benefit no desktop context needs.

**There is deliberately no fallback to eight bits**, for ADR 004 §5's reason: a branch nobody
exercises rots, and dropping to eight bits silently is the exact failure this whole phase exists to
remove.

## 8. How the widening is kept honest

Widening the oracle and widening the shader is one change, and a golden-pixel test that passes
because both sides moved the same way is worse than no test. Four things stop that here, and they
are independent of each other:

1. **The forward encode is derived from the recommendations' published luma weights and studio
   spans**, never from the decode table. A round trip through it at ten bits lands within a code of
   where it started.
2. **The eight-bit path did not move.** Its outputs are pinned to literal integers by tests written
   in phase 1, its coefficients are pinned to literal doubles, and the depth scale factor is exactly
   1 at eight bits. A ten-bit picture whose codes are an eight-bit picture's shifted up must decode
   to the same colour; only one of the two sides moved, so agreeing is evidence.
3. **The 10-bit coefficients are asserted from the spans**, not from the 8-bit numbers scaled.
4. **The shader may not spell a number.** The literal `255.0` left it when a picture stopped always
   having eight bits, and the test that forbade coefficients now forbids the code range too.

One subtlety that fell out of (2) and is worth stating because it looks like a bug: **the factor
between the two output spaces is the range's, not a constant.** Studio levels are *defined* as
`level << (n-8)`, so a shifted studio code is exactly the same colour and the outputs differ by
1023/255. Full range has no such definition (a shifted full-range code lands three parts in a
thousand short), so there the factor is exactly 4.

## 9. What this costs

- **A source-breaking API change**: `VideoColor`'s seven coefficient accessors take a bit depth, and
  `YuvConverter.convertPixel` takes one too. §4 is why.
- **`convertPixel` now answers in the picture's code space** rather than always in `[0..255]`, so it
  is the matrix alone and `toRgba8` is that plus one scale. A caller that assumed 255 gets 1023.
- **A ten-bit picture costs twice the bus bandwidth of an eight-bit one**: 3.1 MB a picture at
  1080p 4:2:0 becomes 6.2 MB. That is the format's, not this design's, and it is still less than the
  8.3 MB an RGBA conversion would move.
- **The conversion target is the same four bytes a pixel**, so nothing about memory changes there.
- **A fourth capability gate for the ES 3.0 port**, §7.

## 10. What this deliberately is not

- **Not HDR.** §6 lists the five things it would need and none of them is here. What exists is the
  vocabulary to say a picture is HDR and the refusal that follows.
- **Not a primaries conversion.** BT.2020 *matrix* coefficients have existed since phase 1 and are
  about decoding luma and chroma; BT.2020 *primaries* are about which red is red, and nothing here
  carries them.
- **Not 4:2:2, not 12-bit.** §2, with the reason for each. *P010 was on that list until ADR 014
  gave it a producer; §2 records what its arrival cost.*
- **Not a change to the composite.** The one property that made this cheap is that `RGB10_A2` is an
  ordinary normalized texture: `canvas.frag` is untouched and `drawSurface` still emits
  `KIND_IMAGE`.
- **Not hardware decode.** ADR 014, which was `Proposed` when this was written and is now
  `Accepted`, and which needed exactly one thing from here: P010.
