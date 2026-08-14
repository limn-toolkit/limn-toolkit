# ADR 004: The 3D target is linear HDR, and the tonemap moves to the composite

- **Status:** Accepted, 2026-08-01. Implemented on `main` in the five §7 steps, one
  commit each. Extends ADR 001 (GL 3.3 core); adds one capability gate to ADR 003 §4.4.
- **Date:** 2026-08-01
- **Scope:** the colour space of `limn.render3d`'s offscreen target, and where the
  display transform runs.
- **Audience:** whoever implements it. Every claim below is tied to a file:line in this
  repo, or explicitly marked as unverified with the machine needed to settle it.

---

## 0. The two findings that decide the shape of this work

**Finding 1: the pass tonemaps before it blends, so transparency composites in the
wrong space.** `GlslCodegen.java:339-343` runs `tonemapACES` then `linearToSrgb` and
writes the premultiplied result; blending happens afterwards, in the RGBA8 target
allocated at `GlRenderTarget.java:46`. Two additive surfaces therefore sum their
*encoded* values, which is not the sum of the light they represent. The error is not
subtle at the top of the range: a bright source rolls onto the ACES shoulder before it
is added to anything, so overlapping bright transparency desaturates toward white
instead of accumulating. Any consumer that composites the same content in an HDR
buffer gets a different image, and the difference reads as a shader bug rather than as
a pipeline limit.

**Finding 2: the target has no declared colour space, and the programs already
disagree.** Six programs write colour into it, and they do not agree on what the
numbers in it mean:

| Program | Source | What it writes |
| --- | --- | --- |
| PBR | generated, `GlslCodegen.java:339-343` | exposure → ACES → sRGB encode |
| Skybox | `Gl3DContext.java:779-781` | exposure → ACES → sRGB encode |
| Unlit | `shaders/mesh_unlit.frag` | `u_color`, verbatim |
| SimpleLit | `shaders/mesh_lit.frag` | lambert × colour, no transform |
| Cube gadget | `shaders/cube.frag` | shade × colour, no transform |
| Debug lines | `Gl3DContext.java:982-989` | `v_color`, verbatim, straight alpha |
| `Material.Raw` | supplied by the caller | unknown by definition |

So an `Unlit` at `(1, 0, 0)` and a PBR surface lit to linear 1.0 do **not** land on the
same pixel value today, and mixing the two in one scene is already not colour-managed.
This is a live defect independent of finding 1, and it is why this work is larger than
a format swap: the fix is to *state* the invariant, and then make six programs obey it.

---

## 1. Decision

1. **The offscreen 3D target is `GL_RGBA16F`**, and its contents are **linear light,
   premultiplied, scene-referred**, not display-referred. This is the invariant that
   finding 2 says is missing; from here it is documented on `RenderTarget` and every
   program that writes into one is held to it.
2. **The display transform (exposure, ACES, sRGB encode) runs once, in the 2D
   composite**, where the target is sampled (`GlCanvas.java:713`). It leaves every 3D
   fragment shader.
3. **No RGBA8 fallback.** See §5.

### Why the composite and not a resolve pass

A separate fullscreen tonemap pass into a second RGBA8 texture would also work and
would keep `drawSurface` untouched. It is rejected because it costs a second
full-size texture and an extra pass per viewport per frame to arrive at the same
pixels the 2D pipeline is about to sample anyway. The composite already reads every
texel of the target exactly once; the transform is free there.

The cost of that choice is honest and should be stated: it puts a branch in
`canvas.frag`, which every widget in the toolkit renders through. §3 bounds it.

---

## 2. What this fixes and what it enables

**Fixes.** Additive and alpha transparency accumulate in linear light, so what the 3D
pass produces is comparable with any other renderer compositing the same scene in HDR.
The comparison currently cannot be made at all, which means a consumer whose promise is
"it looks the same elsewhere" has to publish the caveat instead.

**Enables.** A float target is the precondition for any bright-pass effect; bloom is
the obvious one. With this in place bloom is a few extra passes at the end of the 3D
pass (threshold, blur, add; after the resolve, before the composite samples the target)
rather than a new architecture. *Corrected by ADR 005 §0 finding 1: an earlier revision
placed bloom "in the composite step", but the composite is a per-fragment branch of a
batched quad shader and bloom is a neighbourhood operation over its own targets. It
cannot live there, and ADR 005 puts it where it now runs.* This ADR does **not**
implement bloom; ADR 005 did.

**Does not change.** The shadow depth pass (`depthProgram`) writes no colour. Picking,
meshes, materials as an API, and the 2D pipeline's own colour handling are untouched.

---

## 3. The work, file by file

### 3.1 Allocation: `limn-backend-lwjgl/.../GlRenderTarget.java`

- At `:46`, the resolve texture: `GL_RGBA8` → `GL_RGBA16F`, and the upload type
  `GL_UNSIGNED_BYTE` → `GL_HALF_FLOAT` (the data pointer stays null; the type must
  still be legal for the internal format).
- At `:60`, the MSAA colour renderbuffer: `GL_RGBA8` → `GL_RGBA16F`.
- At `:142-148`, in `stats()`: the resolve texture is 8 bytes/px, the MSAA colour
  renderbuffer 8 bytes/px/sample. Depth is unchanged. The perf monitor reads this;
  leaving it at 4 makes the footer lie by exactly the amount this ADR costs.

`resolve()` (`:97-105`) needs no change, because `glBlitFramebuffer` between two RGBA16F
attachments of equal size is a legal resolve. **Unverified on hardware; see §6.**

### 3.2 The programs: every colour writer outputs linear

- **PBR**, `GlslCodegen.java:339-343`: delete the `tonemapACES` and `linearToSrgb`
  calls; keep `o_color = vec4(color * alpha, alpha)` with `color` linear. The
  `tonemapACES` and `linearToSrgb` helpers move to `canvas.frag`; `srgbToLinear` stays,
  because texture decode is still the pass's job.
- **Skybox**, `Gl3DContext.java:779-781`: same deletion.
- **Unlit / SimpleLit / Cube**: these currently write authored sRGB values as if they
  were final pixels. They must decode: `o_color.rgb = srgbToLinear(u_color.rgb) * a`.
  This is a **visible change** to any scene using them, and it is the correction, not
  a regression: those surfaces were never colour-managed and read differently from a
  PBR surface of the same nominal colour.
- **Debug lines**, `Gl3DContext.java:982-989`: same decode. Note the comment at
  `:998-1002`: debug colours are authored *straight*, not premultiplied, and set their
  own blend state. Decoding must not disturb that; only the RGB transform changes.

### 3.3 The composite: `GlCanvas.java:713` and `shaders/canvas.frag`

`drawSurface` emits `KIND_IMAGE` (`:718`), which lands in the image branch of
`canvas.frag:78-84` (inside the textured ladder at `:68-87`) and expects a
**premultiplied sRGB** texel. A 3D surface now delivers premultiplied linear HDR, so it
needs its own kind (call it `KIND_HDR_SURFACE`) whose branch is the image branch plus:

```glsl
vec3 c = texel.rgb / max(texel.a, 1e-4);   // un-premultiply before a non-linear op
c = tonemapACES(c * u_exposure);
c = linearToSrgb(c);
o_color = vec4(c * texel.a * clipCoverage, texel.a * clipCoverage);
```

The un-premultiply is not optional and is the easiest thing to get wrong here: ACES is
non-linear, so applying it to `rgb` that has already been scaled by alpha tonemaps a
half-transparent bright surface differently from an opaque one of the same colour.

`kind` is dispatched on a float compared against `.5` boundaries (`canvas.frag:68-103`).
The highest value in use is the image mask at `>= 5.5`, so `6` is free: the new branch is
additive and touches no existing one.

### 3.4 Exposure crosses a module boundary

Exposure is set on the pass (`RenderPass.java:36`) and reaches the shader as
`u_cameraPos.w` (`GlslCodegen.java:126`). With the tonemap in the composite, the
*composite* needs it, and the composite is `limn.graphics`, not `limn.render3d`.

The cheapest correct answer: the exposure a pass was given is a property of the target
it rendered into, so `RenderTarget` carries it (`float exposure()`, set by the pass at
draw time) and `GlCanvas.drawSurface` reads it off the `GlRenderTarget` it already
downcasts at `:717`. No new public parameter on `Canvas.drawSurface`, and no new
concept in `limn.graphics`: a `GpuSurface` that is not a `RenderTarget` is composited
exactly as before.

### 3.5 Tests

- `GlslCodegenTest.java:59` asserts `frag.contains("vec3 tonemapACES")`. **This
  assertion inverts**: the generated fragment must no longer contain it. Replace it
  with the positive statement: the generated PBR fragment writes linear.
- Neighbours to re-read, not necessarily to change: `Render3DTest`, `ColorSpaceTest`,
  `DynamicMeshTest`.
- `ColorSpace.srgbToLinear` / `linearToSrgb` (`ColorSpace.java:20-28`) are the CPU
  reference for the same transfer function the shaders implement. They now describe
  what the *composite* does; the class javadoc's "re-encodes to sRGB on output" is
  about to be false in the pass and true in the composite, and must say so.

### 3.6 The demo

`Viewport3DScene` is the 3D tab of the Kitchen Sink (`KitchenSinkScene.java:279`), and
`GltfScene` and `CubeGadget` are the other consumers. The Kitchen Sink is this repo's
only visual regression net for GL state, so the change is not done until all three are
looked at, `CubeGadget` in particular, because it is the one using an unmanaged
shader that §3.2 changes the appearance of.

---

## 4. `Material.Raw`: an assumed, declared break

`Material.Raw` (`Material.java:159`) is public API taking caller-written GLSL. Its
javadoc already calls it non-portable and says the pass's uniform blocks are not bound
to it. It cannot be made to obey a new invariant automatically: an existing raw shader
that writes display-referred sRGB will, after this change, have those numbers read as
linear light and tonemapped a second time.

There is no mechanism that fixes this and no way to detect it. The decision is to
**declare it**: `Raw`'s javadoc gains the target's colour space as part of the
contract, and this ADR is the record that it changed. Anyone who has written one gets
a one-line rule (write linear) and a visibly wrong image if they do not.

This is acceptable only because the escape hatch is documented as an escape hatch. If
`Raw` were the ordinary path, this ADR would need a versioned material instead.

---

## 5. Portability: a third capability gate for ADR 003

ADR 001 fixes the desktop floor at GL 3.3 core, where `GL_RGBA16F` is a
colour-renderable internal format and multisampled float renderbuffers are core. No
desktop concern.

ADR 003 proposes GL ES 3.0 as the authored profile. **In ES 3.0, `RGBA16F` is
*texture*-complete but not colour-renderable in core**: rendering to it requires
`EXT_color_buffer_float`, which is why this ADR adds a **third** capability gate to
ADR 003 §4.4, which currently lists two (timer queries, MSAA sample count).

The extension is present on effectively all ES 3.0-class hardware and is exposed by
ANGLE on every backend ADR 003 considers, but it is an extension and this repo's rule
is to state such things rather than assume them. **Unverified: see §6.**

### No fallback, deliberately

The tempting shape is: query the extension, keep RGBA8 plus the per-fragment tonemap
when it is absent. Rejected, for the reason ADR 003 §4.3 gives about context selection
paths: a branch nobody exercises is a branch that rots. Concretely, a fallback here
means **two colour pipelines**: six programs that must be correct in both modes, a
composite with two branches, and a demo that only ever proves one of them. The
combinatorics are the cost, not the format.

If a target platform genuinely lacks float render targets, the honest answer is that
`limn.render3d` does not run there, the same answer ADR 001 already gives for GL below
3.3.

---

## 6. Risks and what only hardware settles

- **MSAA resolve on a float target.** `glBlitFramebuffer` from an RGBA16F multisample
  renderbuffer to an RGBA16F texture is legal in 3.3, but this repo has never done it.
  If it misbehaves on the macOS GL 4.1 driver, the fallout lands on MSAA rather than on
  the format, and the recovery is to resolve with a shader instead of a blit. *Needs a
  Mac; nothing here predicts it.*
- **`EXT_color_buffer_float` under ANGLE**, per §5. *Needs the ADR 003 test machines.*
- **Bandwidth.** The colour attachment doubles: 8 bytes/px, and 8 bytes/px/sample under
  MSAA. A 1280×720 viewport at 2× device scale is ~2.6 Mpx, so the resolve texture goes
  from ~10 MB to ~21 MB, and a 4× MSAA colour renderbuffer from ~42 MB to ~84 MB. That
  is per viewport, and it is the real price of this ADR. Fill rate on the composite is
  unchanged: the same texels, read once.
- **The `canvas.frag` branch** is in the toolkit's hot path. It is a compare against a
  constant on a branch no other draw takes; the risk is not cost but breakage, and
  `kind` dispatch is already a ladder (`canvas.frag:68-103`) that this extends rather than
  restructures.

---

## 7. Work plan

Ordered so that each step leaves the tree in a state where the demo says something
true.

1. **Declare the invariant.** Javadoc on `RenderTarget` and `Material.Raw`; update
   `ColorSpace`'s class javadoc (§3.5). No behaviour. This is the step that makes the
   rest reviewable.
2. **Move the transform, keep the format.** Delete the tonemap/encode from PBR and
   skybox, add the composite branch and the `KIND_HDR_SURFACE` kind, plumb exposure per
   §3.4. Still RGBA8, so values above 1 still clip; the image should be *identical*
   for anything that was not overbright, which makes this step verifiable on its own.
3. **Switch the format** (§3.1) and update `stats()`. Overbright now survives to the
   composite; the shoulder behaviour changes visibly and correctly.
4. **Decode the unmanaged shaders** (§3.2: unlit, lit, cube, lines). This is the step
   that changes the demo's appearance most, and it is last so the change is attributable.
5. **Tests and the Kitchen Sink** (§3.5, §3.6).

Each of 2, 3 and 4 is independently revertible, which matters because 4 is the one
whose "correct" output is a judgement call.

---

## 8. Open questions this document cannot settle

- **Should exposure become part of `RenderTarget`'s public surface, or stay implicit
  in the backend downcast?** §3.4 proposes the former as the smaller change. If a
  future backend composites a surface it did not render, the implicit route breaks.
- **Should the composite's transform be selectable** (ACES / Reinhard / none)? Every
  consumer today wants the same one, and a knob with one user is a knob invented too
  early. Listed because the fullscreen branch is where it would go, and adding it later
  is cheap only if §3.3's branch is written as a function rather than inline.

### Settled by the implementation (2026-08-01)

- **Exposure is public.** `RenderTarget.exposure()` exists as a `default` method
  returning 1; the backend records the pass's exposure on the target at draw time
  and the composite reads it. The first open question above is answered: a surface
  no pass rendered presents neutral exposure rather than breaking.
- **The composite's transform is a function.** `displayTransform()` in
  `canvas.frag`, per the second bullet's condition: a selectable transform later
  swaps the body, not the dispatch.
- **The new kind is 7, not 6.** §3.3 said 6 was free; the image mask
  (`KIND_IMAGE_MASK`, the `>= 5.5` branch) already owns 6. `KIND_HDR_SURFACE = 7`,
  dispatched at `>= 6.5`; still additive, no existing branch touched.
- **The clear is a seventh colour writer §0's table did not list.**
  `RenderPass.clear` takes authored sRGB with straight alpha and the backend
  decodes + premultiplies on write (the demo-scene clear likewise). This landed in
  step 2, not step 4: the moment the composite assumes linear contents, an
  undecoded clear violates the declared invariant.
- **§3.6's consumer map was imprecise.** `CubeGadget` renders `Material.Pbr`
  (mesh + number-atlas texture); the `cube.frag` program §3.2 changes is the
  *built-in placeholder cube* (`Viewport3D` with no renderer /
  `renderDemoScene`), which no demo scene currently displays. The §3.2 change
  most visible in the Kitchen Sink is the debug-line decode (the Debug segment).
- **§6, MSAA resolve on float, macOS GL 4.1:** `glBlitFramebuffer` from an
  RGBA16F multisample renderbuffer into an RGBA16F texture works: framebuffers
  complete at 4×, resolve artifact-free across every 3D demo scene. No shader
  resolve needed on this driver.
- **A known limit of §3.3's formula, accepted:** a texel that is pure additive
  radiance over a transparent background (RGB > 0 with alpha exactly 0)
  un-premultiplies toward the ACES clamp and re-premultiplies to nothing. No
  current content produces such a texel (the demos composite additive surfaces
  over opaque scene content; transparent-background scenes use opaque cubes whose
  MSAA edges carry real coverage), but a future emitter-over-transparent consumer
  would need the branch to special-case `texel.a == 0`.
- **Step 2's identity claim, quantified** (pre/post captures, bit-deterministic
  harness): PBR and skybox pixels matched within RGBA8-linear quantization (ibl
  scene 99.8% of pixels within 2/255; the gadget shot over a transparent
  background max 3/255). The residue was the three cataloged behaviour changes:
  clear colours crossing the ACES toe, MSAA resolving in linear, and blended
  surfaces compositing in linear (finding 1). Step 3 then erased the
  quantization residue, and step 4 changed the four unmanaged programs only
  (PBR-only scenes bit-identical across it).

---

## Appendix A: how the inventory in §0 was produced

So it can be re-run after any change, rather than trusted:

```bash
# every fragment output in the 3D pipeline
grep -rn "o_color\|fragColor" limn-backend-lwjgl/src/main/java/limn/backend/lwjgl/Gl3DContext.java \
                              limn-backend-lwjgl/src/main/java/limn/backend/lwjgl/GlslCodegen.java \
                              limn-backend-lwjgl/src/main/resources/limn/backend/lwjgl/shaders/
# who applies the display transform today
grep -rn "tonemapACES\|linearToSrgb" limn-backend-lwjgl/src/main
# every program the 3D context links
grep -n "ShaderProgram.fromSources\|ShaderProgram.fromResources" \
     limn-backend-lwjgl/src/main/java/limn/backend/lwjgl/Gl3DContext.java
# where a render target's format is decided
grep -n "GL_RGBA8\|glRenderbufferStorageMultisample" \
     limn-backend-lwjgl/src/main/java/limn/backend/lwjgl/GlRenderTarget.java
```
