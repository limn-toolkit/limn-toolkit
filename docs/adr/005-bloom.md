# ADR 005: Bloom belongs to the 3D pass, not to the composite

- **Status:** Accepted, 2026-08-01 (implemented in five steps as §6 planned; what the
  implementation settled is recorded in §7–§8). Builds on ADR 004; corrects one
  sentence of it (§0, finding 1), a correction that landed with step 5.
- **Date:** 2026-08-01
- **Scope:** where a bright-pass glow runs in `limn.render3d`, and what a caller sets.
- **Audience:** whoever implements it. Every claim below is tied to a file:line in this
  repo, or explicitly marked as unverified with the machine needed to settle it.

---

## 0. The three findings that decide the shape of this work

**Finding 1: ADR 004 §2 is wrong about where bloom goes, and this ADR corrects it.**
That document says a float target makes bloom "an addition to the composite step
(threshold, blur, add)". The composite is a *per-fragment* branch in a batched quad
shader (`canvas.frag:90`, reached from `GlCanvas.java:715-726`): it reads one texel and
writes one texel. Bloom is a **neighbourhood** operation: it needs a bright-pass, a
separable blur over many texels, and a combine, which is several fullscreen passes over
their own targets. None of that can happen inside a branch of a batched quad shader,
and trying would mean flushing and re-entering the 2D batch mid-frame per viewport.

**Finding 2: bloom must run after blending and before the tonemap, which is exactly
the window ADR 004 opened.** Glow is the sum of scene light spreading, so it operates on
linear scene-referred light: after every particle has accumulated, before the display
transform compresses the range. There is precisely one place with both properties: the
end of the 3D pass, at `Gl3DContext.java:253-254`, where the pass has finished drawing
and calls `target.setExposure(...)` then `target.resolve()`. Bloom slots in immediately
after the resolve.

That location has a second, larger benefit: **the composite does not change at all.**
The target still holds premultiplied linear scene-referred light (ADR 004's invariant
is preserved rather than amended), so `canvas.frag`, `drawSurface` and the batch's
`requireExposure` (`GlBatch.java:245`) are all untouched. A feature that adds nothing to
the toolkit's hottest shader is worth the extra passes.

**Finding 3: premultiplied alpha makes the naive implementation wrong at the edges.**
A `RenderTarget` composites over the UI, so its transparent regions are real. If the
bright-pass and blur carry RGB only, the combine adds light where alpha is 0, breaking
the premultiplied invariant, and producing glow that either vanishes at the boundary of
the drawn content or darkens the interface behind it. The bright-pass, the blur and the
combine all carry **RGBA**, and the combine adds alpha along with colour (clamped at 1).
Glow spreading means the effect's coverage genuinely grew.

---

## 1. Decision

1. **Bloom is a property of the pass**, set by the caller and applied by the backend
   during the pass's own teardown. Unlike exposure it never travels to the composite:
   by the time `drawSurface` reads the target, the glow is already part of its contents.
2. **Off by default, and free when off.** `intensity == 0` allocates nothing and runs no
   pass. Every existing `Viewport3D` pays exactly what it pays today.
3. **Half-resolution, separable, ping-pong.** Two half-res RGBA16F targets, a bright
   pass, N horizontal/vertical blur pairs, one additive combine back into the resolved
   colour texture.

### API

```java
/**
 * Adds a bright-pass glow to this pass's result, in linear light, before the
 * display transform. intensity 0 (the default) disables it entirely.
 */
RenderPass bloom(float threshold, float intensity, float radius);
```

- **`threshold`**: linear light above which a pixel contributes. Scene-referred, so a
  value of 1 means "brighter than a fully-lit white surface", which is the useful floor.
- **`intensity`**: how much of the blurred result is added. 0 is off.
- **`radius`**: the blur's standard deviation **in points**, the toolkit's logical unit,
  converted to texels by the backend. Points and not texels so a glow is the same size
  on a HiDPI display as on a low-DPI one; points and not a normalised fraction so it does
  not silently change when the viewport is resized.

Three numbers and no quality knob: the iteration count is derived from `radius` and
clamped by the backend. A caller asking for a wider glow should not also have to know
how many blur passes that costs.

---

## 2. The work, file by file

### 2.1 `limn-toolkit/.../RenderPass.java`

One method (above), beside `exposure` at `:44`. Javadoc states the linear-light domain
and that it is applied before the display transform, so a caller has no reason to
believe it interacts with `exposure()`. It does not: bloom is scene-side, exposure is
display-side, and they compose in that order.

### 2.2 `limn-backend-lwjgl/.../GlRenderTarget.java`

The two half-res targets are the target's, not the context's: they are sized from it and
die with it. Allocate **lazily, on first use with bloom enabled**, so a target that never
blooms never pays. `stats()` (`:142-148`, already updated by ADR 004 to 8 bytes/px) adds
them when present; the perf monitor must show what bloom costs, or the cost is
invisible exactly when someone is looking for it.

### 2.3 `limn-backend-lwjgl/.../Gl3DContext.java`

Insert after `target.resolve()` at `:254`, inside the existing `try` so the `finally`
at `:255-262` still restores the framebuffer, viewport and depth state if a pass throws.

Three programs, all fullscreen-triangle, all RGBA:

- **Bright pass**: `max(rgb - threshold, 0)`, carrying alpha, colour → bloomA (half res).
- **Blur**: separable Gaussian, bloomA ⇄ bloomB, N pairs. One program with a direction
  uniform, not two.
- **Combine**: bloomB → the resolved colour texture, additive (`GL_ONE, GL_ONE`), RGB
  and A both. Depth test off; the resolve FBO is the destination.

No read-write hazard: the bright pass reads the colour texture and writes bloomA, the
blur never touches the colour texture, and only the combine writes it back.

### 2.4 The demo

`Viewport3DScene` (reached from `KitchenSinkScene.java:279`) gains a bloom control: a
threshold and an intensity the user can move, over content with something overbright in
it. The Kitchen Sink is this repo's only visual regression net for GL state, and a bloom
that is only ever exercised at its default of "off" is not exercised at all.

---

## 3. Cost

**Memory.** Two half-res RGBA16F targets = `2 × (w/2 × h/2) × 8` bytes = `w × h × 4`.
For a 1280×720 viewport at 2× device scale (~2.6 Mpx) that is ~10 MB on top of the ~21 MB
ADR 004 already costs. Allocated only when bloom is on.

**Fill.** `2N + 2` fullscreen passes at quarter the pixel count. At N = 3 that is eight
half-res passes (the standard price, and the reason the blur is not full-res).

**When off:** nothing. No allocation, no pass, one branch on `intensity == 0`.

---

## 4. What this deliberately is not

- **Not physically-correct glare.** No lens dirt, no ghosting, no chromatic streaks. A
  threshold-and-blur is what an artist tunes against and what other engines call bloom;
  the rest is a look, and a toolkit should not have one.
- **Not a post-processing framework.** One effect with three knobs, in a fixed place. If
  a second effect ever wants the same slot, *that* is when a chain gets designed; a
  general pipeline invented for one client would be a guess about the other clients.
- **Not applied to the 2D canvas.** Widgets are authored in sRGB and are display-referred
  by construction; there is no scene-referred range for a bright pass to find.

---

## 5. Risks and what only hardware settles

- **Half-res RGBA16F FBOs.** ADR 004 already requires float render targets, and ADR 003
  §4.4 already gates `EXT_color_buffer_float` on GL ES. Bloom adds no new capability:
  it adds two more targets of a format the toolkit already demands. *No new risk, stated
  so nobody re-litigates it.*
- **Blur quality at low radius.** A half-res blur under ~2 points is a blocky glow rather
  than a soft one. The clamp should refuse to run at all below a minimum radius rather
  than produce visible squares; picking that floor needs eyes on a real display.
- **The combine's alpha.** Finding 3's reasoning is sound but its result is a judgement
  call: glow spreading over previously-transparent pixels makes the viewport's content
  extend past where the geometry was. That is correct and may still look wrong over a
  busy UI. *Needs a real scene, not a test.*

---

## 6. Work plan

1. **API and the off path.** `RenderPass.bloom(...)`, stored, and the `intensity == 0`
   early-out. Nothing renders differently. This step is where the "free when off" claim
   becomes verifiable.
2. **Bright pass and the targets**, drawn straight to the screen for inspection before
   anything is combined, because a bright pass is easy to get subtly wrong and
   impossible to debug once it is buried under a blur.
3. **Blur.**
4. **Combine, with alpha** (finding 3).
5. **The Kitchen Sink control, and `stats()`.**

---

## 7. Open questions: resolved at acceptance

- **Should `threshold` have a soft knee?** **Shipped without one: a deliberate
  deferral, not an oversight.** The pop is real and observable: in the demo, sliding
  the threshold across a sphere's brightness snaps its glow in and out, and a moving
  bright edge does the same as it crosses. It was kept out because the API cost is the
  irreversible half (the number can be added later as a fourth parameter defaulting to
  0 = hard, but never removed), and one implementation of the hard threshold was worth
  having before choosing the knee's shape. If the pop bothers a real scene, that is
  the signal to add it; the bright pass has exactly one line to change.
- **Should the blur run before or after the MSAA resolve?** **After: implementing
  confirmed the proposal and hardened the reason.** The multisample attachment is a
  *renderbuffer* (`GlRenderTarget.allocate()`), which cannot be sampled at all: the
  bright pass needs a texture, and the resolve is where the only sampleable texture
  first exists. "Before" would mean restructuring the target around per-sample
  textures, several times the cost, for detail the blur is about to destroy anyway.

---

## 8. What the implementation settled (deltas from the text above)

- **The bright pass is sharper about premultiplication than §2.3's shorthand.** Not
  `max(rgb - threshold, 0)` carrying alpha verbatim: the input is premultiplied, so the
  threshold scales by alpha (`max(rgb - threshold·a, 0)`; a translucent surface is
  judged on its color, not its coverage), and the output alpha is the source coverage
  times the *surviving fraction* of light. That makes the bright layer a premultiplied
  layer of the same un-premultiplied color with reduced coverage. And it is what keeps
  finding 3's two failure modes out: carrying alpha where no light survived would
  spread a dark veil; dropping it where light survived would cut the glow at the edge.
- **"Clamped at 1" is spelled as a coverage union.** On a float target the blender does
  not saturate, so additive alpha (`ONE, ONE`) can pass 1 and flip the composite's
  `(1 − a)` negative. The combine adds RGB (`ONE, ONE`) and unions alpha
  (`ONE, ONE_MINUS_SRC_ALPHA`), with the shader clamping its own `intensity × a ≤ 1`.
- **The blur widens by spacing, not by count alone.** One 9-tap Gaussian (σ = 2
  half-res texels at unit spacing); pair *j* samples at spacing *j+1*, so sigmas
  accumulate in quadrature and 6 pairs reach ≈ 19 texels with no mip pyramid.
  `BloomMath.blurPairs` picks the smallest N that reaches the target, clamped at 6.
  The chain ends in bloom **A** (H: A→B, V: B→A), so the combine reads A; §2.3 said
  "bloomB", which was illustrative.
- **The radius floor survived with a different justification.** The feared half-res
  squares never appear; one pair already blurs by 2 texels, which covers the half-res
  grid even under a 1× linear upsample (eyeballed at radii 1–16 pt, step 3). But that
  same minimum means every request under 2 texels produces the identical
  wider-than-asked glow, so `MIN_SIGMA_TEXELS = 1` now refuses where the chain would
  over-deliver by more than 2×: honesty, not blockiness.
- **The bright pass caps glow at what coverage can display** (post-acceptance review
  finding). Additive blending deposits rgb at alpha 0 over a transparent background
  (its alpha factors are `ZERO, ONE`), and the composite shows such texels as nothing,
  yet `rgb − t·0` passes every threshold, so that invisible light would halo onto
  covered neighbours with no way to suppress it. The bright pass therefore clamps its
  output to `16 × alpha`: the composite can never display more than ~alpha worth of a
  texel's energy, so neither may the glow's source. 16 sits far past the tonemap's
  shoulder (real content is untouched) and the cap fades in continuously as coverage
  vanishes, avoiding a pop at exactly zero.
- **Points reach the backend through the provider router.** `Gl3DContext.render` gained
  a `pixelsPerPoint` parameter fed by `LwjglGraphics3D` from the current canvas's
  content scale; the `Graphics3D.Provider` SPI is unchanged.
- **`RenderPass.bloom` is a default method** (ignored), like `drawShadowOnly` and
  `debugLines`, so headless and test passes keep compiling; `GlRenderPass` overrides.

## Appendix: how the insertion point was located

```bash
# where a pass finishes and hands its target to the composite
grep -n "setExposure\|target.resolve()" limn-backend-lwjgl/src/main/java/limn/backend/lwjgl/Gl3DContext.java
# what the composite does with it, and why it must not change
grep -n "KIND_HDR_SURFACE\|displayTransform\|u_exposure" \
     limn-backend-lwjgl/src/main/java/limn/backend/lwjgl/GlCanvas.java \
     limn-backend-lwjgl/src/main/resources/limn/backend/lwjgl/shaders/canvas.frag
# the invariant bloom must preserve
sed -n '1,40p' limn-toolkit/src/main/java/limn/render3d/RenderTarget.java
```
