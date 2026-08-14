# ADR 019: A shape can be made of what is behind it

- **Status:** Accepted, 2026-08-05. First step implemented on `main`; §7 lists what is not.
- **Date:** 2026-08-05
- **Scope:** how a widget draws itself out of the pixels already under it: the SPI, the one
  new machine in the 2D renderer, and which effects exist.
- **Audience:** whoever implements or extends it.

---

## 0. What was missing

The 2D pipeline could put colour over content and nothing else. `Dialog`'s "glassy" panel is a
round rect filled with a translucent theme colour; the word appears in its source and the effect
does not. There was no way for a shape to read what was behind it, so there was no frosted panel,
no refraction, no redaction, and no place to put the next thing of that kind either.

The absence was structural rather than an oversight. `GlCanvas` draws straight into the window's
framebuffer and never samples it; the batch is built for one draw call a frame; and `canvas.frag`
had no branch that needed a texture it had not been handed.

---

## 1. Decision

1. **A `BackdropEffect` is a closed set**, in `limn.graphics`: `Clear`, `Wash`, `Pixelate`, each a
   record carrying its own parameters and a tint. §2.
2. **One entry point**, `Canvas.fillBackdropRoundRect(RoundRect, BackdropEffect)`, whose **default
   implementation fills the tint**. A renderer without backdrop support draws a flat translucent
   panel of the right size in the right place, which is what this toolkit drew before. §3.
3. **The renderer flushes, copies, and samples.** The backend keeps one framebuffer-sized RGBA8
   texture per canvas, allocated on first use; an effect flushes the batch, copies its own bounds
   into that texture, and queues an ordinary SDF quad with a new `kind` that samples it. §4.
4. **No blur in this step.** Every variant here samples once (three times in the rim, where
   dispersion is on) and needs no second target and no extra pass. §7 is where blur goes.

---

## 2. Why a closed set, when `ImageFormat` is open

ADR 018 argued the opposite way about formats, and the asymmetry is the point. A format is
contributed by whichever encoder is installed, so closing that set would mean a toolkit release for
a format the toolkit does not implement. An effect is a branch in a fragment shader: it exists only
if the renderer has one. An open type would let a caller name an effect nothing can draw, and the
failure would be a blank rectangle rather than a message.

The internal precedent is `Material` in the 3D subsystem, a closed set of shading models with
`Material.Raw` as an escape hatch that ADR 004 §4 had to declare a break for. That escape hatch is
the argument against offering a caller-written shader here: the invariant a raw 2D shader would
have to obey is premultiplied output plus the GLSL 330 ∩ ES 3.00 subset ADR 001 fixes and ADR 003
depends on, and nothing can check either. **A named effect keeps the invariant inside the toolkit.**

### The variants, and why these three

| | What it does | Cost |
| --- | --- | --- |
| `Clear` | Refracts at the rim, from the shape's own SDF gradient; optional chromatic dispersion | 1 sample, 3 in the rim band |
| `Wash` | Moves the backdrop's saturation, undisplaced | 1 sample |
| `Pixelate` | One sample per cell, grid anchored to the framebuffer | 1 sample |

`Clear` is the identity piece. A frosted panel is what every toolkit has; a *transparent* pane that
bends what is behind it is what glass actually does, it keeps the content legible where frosting
destroys it, and (the part that made it worth doing first) **it was nearly free here**. The
canvas already evaluates `sdRoundRect` per fragment for antialiasing, so the gradient of that same
distance is the surface normal of a bevelled rim, with no extra geometry and no height map. The
dispersion is three taps instead of one in the rim band, and it is the detail that reads as optics
rather than as a filter.

`Pixelate` is in the set because redaction is a toolkit responsibility, not a decoration: it is one
sample per cell, so what the cell covered is not recoverable from the output, and the alternative
an application would otherwise reach for, a blur, is.

Deliberately **not** effects: a drop shadow (it blurs the *shape*, and it is painted before rather
than after; putting it here would turn `BackdropEffect` into "effects in general" and cost the name
its meaning) and a scrim (a translucent fill, which the batch already draws for free; routing it
through a flush and a copy would be a pure loss).

**Not implemented, by decision:** the merging/melting behaviour of Apple's material. It needs a
smooth minimum of signed distance fields *between* widgets, and each widget paints its own quad in
its own pass: it is a different painting model, not a shader. The owner's call was to skip it and
keep a cross-platform identity of our own; the dispersion in `Clear` is where that identity lives.

---

## 3. Degradation, not failure

`fillBackdropRoundRect` is a `default` method that fills `effect.tint()`. That single choice does
three things: no `Canvas` implementation in this repository needed changing, every test double
keeps working, and the documented fallback is not a story but the code path itself.

It is also the right *kind* of failure for this subject, and the repository has both kinds already.
`Videos.open` throws, because a video that does not open is a blank rectangle where content was
asked for and the caller must be told. A backdrop effect is the opposite: the panel is still there,
still the right size, still legible; only the material is missing. That is the audio facade's
bargain, and it applies for the same reason.

---

## 4. The one new machine

`GlBackdrop` is a framebuffer-sized RGBA8 texture, one per canvas, allocated the first time an
effect is drawn and never by a window that draws none. Framebuffer-sized rather than region-sized
so the copy lands at the coordinates it occupies on screen: a fragment then finds itself in it with
`gl_FragCoord.xy / u_viewport`, and no rectangle has to be plumbed through to map it. Only the
requested region is copied, so the shader clamps its sampling to that region; outside it the
texture still holds whatever the previous effect left.

The quad itself is an ordinary batched SDF round rect with `kind = 8`, which means clipping,
opacity, corner radii and antialiasing all work through the code that already does them. Three
attribute slots are repurposed for it and the shader header says so: the stroke slot carries the
device scale (a backdrop quad is always a fill), `v_colorB` carries the variant and its two
parameters and the opacity, and `v_grad` carries the copied region to clamp against.

**The costs, stated where they land:**

- **A batch break per effect.** What is queued has not reached the framebuffer, and the framebuffer
  is what is about to be copied. This is the toolkit's "one draw call per frame" promise being
  spent deliberately; the batch already breaks on an atlas page switch, so the mechanism is not new,
  only the reason.
- **A copy of the shape's bounds**, expanded by however far the effect can displace a sample plus
  the AA skirt. Short by a pixel and the rim samples stale texels.
- **A full framebuffer of RGBA8**, reported through `stats()` so the perf monitor shows it.

An effect is therefore priced for a control bar and mispriced for a list row. `BackdropPanel`'s
javadoc says so at the place someone would make that mistake.

### Why not render the scene to an offscreen target instead

The other way to get a sampleable backdrop is to draw the whole 2D scene into an FBO and let
effects read it. It removes the per-effect copy and adds a full-size target to every window,
whether or not anything uses it, plus a final blit for every frame the toolkit has ever drawn. The
copy is proportional to the effect; the offscreen scene is proportional to the window. With one
control bar on screen, the copy wins by a wide margin, and it is revisitable if that ever changes.

---

## 5. What this rests on that is not obvious

- **The window framebuffer is single-sampled.** No `GLFW_SAMPLES` hint is requested (the
  antialiasing is analytic, in the shader), so `glCopyTexSubImage2D` from the back buffer is legal
  and cheap. It is an error from a multisampled read buffer, so a future decision to request MSAA on
  the window is also a decision to resolve before every copy.
- **Paint order is now load-bearing.** An effect samples what the frame has already drawn: a panel
  must be a sibling *after* the content it sits over. Nothing else in `Canvas` cares about this, so
  it is stated on the method and demonstrated in the scene.
- **The framebuffer is premultiplied.** The shader un-premultiplies its sample before mixing the
  tint, which on an opaque window is a division by one and on a translucent one is the difference
  between a correct panel and a dark one.
- **A backdrop shape is opaque within its coverage.** It replaces the pixels behind it with a
  transformed copy of them. That is what makes "clear tint, no displacement" exactly identity,
  the assertion the GL test leads with, because it covers the copy coordinates, the shader's
  self-location, the un-premultiply and the blend in one line.

---

## 6. The known limit: partial rendering

With `Scene.setPartialRendering` on, a frame repaints only what was invalidated. A shape filled
from its backdrop depends on pixels that are not its own, so if content behind it changes while it
does not itself invalidate, it keeps showing the older backdrop.

It does not bite where the pilot puts it (the video transport sits over a picture that repaints
every frame), and it does not bite over static content, because nothing moved. It bites exactly in
between, and the fix is a concept `Scene` does not have yet: a widget that declares itself
backdrop-dependent, whose rect joins the damage list whenever the damage intersects it. That is
§7's third item. Until then this is documented on `Canvas.fillBackdropRoundRect`, in
`BackdropPanel`, and on screen in the demo scene, rather than discovered.

---

## 7. Deliberately not in this step

1. **Blur**, and with it `Frosted`, `Glass` (frosted + refraction + specular) and `Motion`. These
   need a second target and a ping-pong pair; the separable chain already exists in the 3D
   subsystem's bloom (ADR 005) and is authored in the same portable subset, so the work is to move
   it out of the 3D context rather than to write it.
2. **A rim specular.** `Clear` refracts but does not catch a highlight along its edge, which is the
   cheapest remaining thing that would make it read as glass rather than as a lens. It is a
   parameter and a `pow`, left out only to keep this step's surface to what is tested.
3. **Backdrop-dependent damage**, per §6.
4. **Paths.** The effect needs the shape's signed distance to find its own rim, and a filled path
   has none. Round rects cover rectangles, circles and capsules through the same SDF.
5. **A caller-supplied shader.** §2.

---

## 8. Verification

- `BackdropEffectTest` (no GPU): the tint fallback is what an unimplementing renderer draws, the
  convenience overload keeps its geometry, a degenerate size draws nothing, and every parameter is
  refused at construction rather than at the next frame.
- `GlBackdropTest` (real device, six cases): identity for a pane that displaces and tints nothing;
  a wash that greys what it covers to the right luminance and leaves what it does not; a pixelate
  whose cell is genuinely one sample; a rim that displaces the backdrop; and a middle that does not.
- `--scene glass` and the Kitchen Sink's `Glass` tab: the three variants over one generated plate.
- The video transport is a `BackdropPanel`, the pilot target, over content that repaints anyway.
