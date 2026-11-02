# ADR 085: The canvas is the toolkit's whole 2D API, measured against the platform's own

- **Status:** **Accepted**, 2026-11-01, at the owner's request: the canvas should be to Limn what
  `java.awt.Graphics2D` is to the Java platform, an API strong enough for applications whose
  subject is graphics. This record measures the distance (section 0), states it area by area
  against that API (section 1), proposes how to close it (sections 3 and 4) in six stages
  (section 7), names what stays out (section 5), and asked seven questions (section 6), which
  the owner answered the same day by taking every recommendation (section 9). Stage 1 built the
  same day (section 10). Two defects in a shipped chart came out of the measurement (section 0.3).
- **Date:** 2026-11-01
- **Scope:** `limn.graphics.Canvas` and the values it takes (`Paint`, `Path2D`, `BlendMode`), its
  three implementations in the project (the OpenGL canvas, the PDF canvas, the recording canvas
  of printing) and the `NoopCanvas` of `limn-test`. Not in scope: widgets, the 3D stack, image
  processing, and anything section 5 names.
- **New public API (proposed):** a `Stroke` value; a fill rule on `Path2D`, with arcs, shapes,
  bounds, containment, transformation and path data; `limn.math.Affine2D`, with shear,
  concatenation and a transform getter on the canvas; `clipPath`; Porter-Duff and three separable
  modes in `BlendMode`; a `Gradient` paint of many stops and an `ImagePaint`; a glyph outline from
  `ShapedText`; a public `Picture`; an image drawn off screen. Section 4 gives each one.
- **Compatibility:** additive in the API. Every new method on `Canvas` is a default method,
  because `Canvas` is an interface an application may implement. `Paint` gains permitted types,
  as ADR 077 did with `Expr`: an exhaustive `switch` over it stops compiling, and its Javadoc
  gains the sentence that a 1.x minor release may add types. One change in behaviour: a path whose
  subpaths turn in opposite directions gets a hole where it is filled today (section 4.2).
- **Relates to:** [ADR 062](062-the-performance-budget-counts-work-and-time-is-a-report.md) (the counted draw
  calls that must not rise for what draws today); [ADR 071](071-a-widgets-opacity-fades-it-as-one-picture.md)
  (the layers this builds on, and the group effects it left out);
  [ADR 072](072-a-transform-moves-the-picture-and-leaves-the-layout.md) (section 5 there left
  "a matrix getter on `Canvas`" to a decision of its own, which this is);
  [ADR 078](078-a-document-is-widgets-cut-into-pages-and-every-page-leaves-as-pdf.md) (the PDF
  canvas); [ADR 083](083-a-label-can-show-styled-text-built-from-pieces-and-shaped-as-one-paragraph.md)
  section 7.7 (styled text on the canvas, the first step of this request).

---

## 0. What was measured

All of this was measured on 2026-11-01 at 46ca9618, with the styled-text change of ADR 083
section 7.7 still uncommitted in the same tree.

### 0.1 The canvas today

`Canvas.java` is 832 lines: 31 abstract methods and 29 default ones. By area:

- **Frame and state.** Width, height, content scale; `save`, `restore`, `saveCount`,
  `restoreToCount`; an opacity that multiplies; `saveLayer` (a group drawn as one picture, with
  an opacity and optionally a `Mat4` transform); `setBlendMode` (`NORMAL`, `ADDITIVE`,
  `MULTIPLY`); `setSampling`; `setPixelSnap`; `readBack` of a region drawn in this frame.
- **Transform.** `translate`, `scale`, `rotate`. No matrix in or out.
- **Clip.** `clipRect` and `clipRoundRect`, intersecting only; `clipBounds` answers a rectangle
  or null. Under a rotation a rectangle clip becomes the device bounding box of the rotated
  rectangle (documented as a V1 limitation); nested rounded clips keep one radius.
- **Shapes.** Rectangles, rounded rectangles (one radius, or a `RoundRect`), circles, ellipses,
  lines, each filled or stroked; `Path2D` of moves, lines, quadratic and cubic curves and closes,
  filled or stroked; a shadow under a rounded rectangle; a frosted backdrop.
- **Strokes.** A width and nothing else. `drawLine` has round caps; `drawPath` strokes each
  segment as a capsule, so caps and joins are round, and a translucent stroke is darker where two
  capsules overlap (documented).
- **Fills.** Each closed subpath of a path is filled on its own by an ear-clipping triangulator
  (`PolygonTriangulator`, 120 lines); a hole is not a hole, and a subpath that crosses itself is
  "unsupported" (documented).
- **Paint.** `Color`, `LinearGradient` and `RadialGradient`, two colours each, padded at the ends.
  `Paint` is sealed.
- **Text.** A string at a baseline (shaped for the caller), a `ShapedText` whole or a range of
  it, a `StyledLayout` or `StyledText` (ADR 083 section 7.7), and `measureText`.
- **Images.** An image into a rectangle, a part of one, with a tint, as a mask, and a GPU
  surface (3D, video).

### 0.2 Who implements it

Three implementations in the toolkit and its backend: `GlCanvas` (3,344 lines), `PdfCanvas` (730)
and the printing's `RecordingCanvas` (502), which records a page to replay it into the PDF.
`limn-test` publishes `NoopCanvas` (73), and seven test fakes implement it in the toolkit's tests.
Because `Canvas` is a public interface that is not sealed, an application may implement it too,
so under the 1.x contract every method this record adds has to be a default method. A default
method can be exact where it can be written with what is already abstract (a stroked path is a
filled outline, section 4.1), and elsewhere has to be a documented approximation, as the rotated
clip already is.

### 0.3 Two pictures

A throwaway test drew on a headless OpenGL context (2x, 1,200 by 700 pixels) eight things a 2D API is
expected to draw. What the current canvas drew:

1. **A square with a square hole**, the inner subpath turning the other way: a solid square.
2. **A five-pointed star drawn as one self-crossing outline**: a broken shape, two of its points
   cut off and a quadrilateral filled across its lower half, which is what an ear clipper gives
   for a polygon it does not support.
3. **A zigzag at 45% opacity, 14 points wide**: a darker disc at every joint, where the capsules
   overlap.
4. **A 70-point square clip rotated 30 degrees**, filled with blue and outlined in red where it
   was asked: the blue fills the upright 96-point box around it.
5. **A thick open polyline**: round caps and a round joint; there is no other kind.
6. **A gradient of three colours**: possible only as two rectangles side by side.
7. **A dashed line**: possible only as separate short lines, with round ends.
8. **A skew**: there is no `shear`. One can be built from `rotate`, `scale` and `rotate`, which
   is how `saveLayer` decomposes a `Mat4`, but no caller should need to know that.

The same test then drew the toolkit's own `LineChart`, with `setArea(true)`, a series of
3, −2, 4, −3, 2, −1 and a second series at half opacity. **Two defects of a shipped component:**

- **D1. A line chart's area is drawn wrong where its values cross the baseline.** The area is one
  closed path along the line and back along the baseline; where the line crosses the baseline the
  path crosses itself, the triangulator meets case 2, and the area comes out as stray triangles:
  a wedge from the baseline up to the value 4, and the lobes below zero partly missing. Any chart
  whose values change sign shows it, and so does a stacked area whose line crosses its base.
- **D2. A translucent series line is darker at every point**: case 3 on a chart. A series is
  translucent while it fades in or out after its legend entry is toggled, and when its colour has
  alpha.

### 0.4 What is already underneath

- **The OpenGL canvas keeps a full affine transform** (`Transform2D`, a 2x2 matrix and a
  translation) per state. A shear or a concatenated matrix costs nothing in the renderer.
- **Every context the backend creates has an 8-bit stencil buffer**: GLFW's default, which the
  backend does not override; 8 bits asked of WGL, of EGL (the ANGLE path) and of the macOS
  software fallback. The canvas clears it every frame and uses it for nothing. Layer textures
  (`GlLayerPool`) have no stencil attachment.
- **Layers exist** and are pooled: what `saveLayer` and the `Opacity` container draw into, then
  composite. A clip by any path can be a layer masked at its `restore` (section 4.3).
- **Antialiasing is a fringe**: a filled polygon gets a one-pixel ring whose alpha falls to zero
  (`emitFringeRing`). The same ring can edge a fill drawn through the stencil.
- **PDF has every one of these natively**: dash patterns, butt, round and square caps, miter,
  round and bevel joins with a miter limit, the non-zero and even-odd rules, clipping by any path
  under either rule, any affine matrix, multi-stop axial and radial shadings with extension,
  tiling patterns, and the blend modes Screen, Darken and Lighten. The PDF canvas uses round caps
  and joins and fills subpaths one at a time only because the canvas contract says so.
- **Glyph outlines are already read**, by the backend's `FontOutlines` behind the `GlyphOutlines`
  service, so that printed text is vector text. A public glyph outline is a new door to an
  existing room.
- **A recording canvas exists** (`RecordingCanvas`, internal to printing): a display list that
  replays onto any canvas. It is a `Picture` in everything but name and visibility.
- **A complete CPU vector rasterizer is already shipped**, inside `limn-lunasvg-natives` (the
  rasterizer under the SVG renderer), but its native library exposes only the SVG document calls.

## 1. The measure: the platform's 2D API, area by area

| Area | `Graphics2D` and its companions | Limn today | Gap |
| --- | --- | --- | --- |
| Geometry | `Shape`: rectangle, rounded rectangle, ellipse, arc, line, quadratic, cubic, `Path2D`, `Polygon`; `getBounds2D`, `contains`, `intersects`; a path iterator, flattening | Rectangles, rounded rectangles, circles, ellipses, lines as canvas calls; `Path2D` with curves, `forEach`, `flatten`, `copy` | Arcs; shapes appended to a path; bounds; containment; a path transformed; path data from text |
| Fill rule | `WIND_NON_ZERO`, `WIND_EVEN_ODD` on the path | Each subpath alone; no holes; no self-crossing | Both rules, holes, self-crossing (0.3 cases 1, 2; D1) |
| Stroke | `BasicStroke`: width, caps (butt, round, square), joins (miter, round, bevel), miter limit, dash array and phase; `createStrokedShape` | Width; round caps and joins; overlaps double-blend | Caps, joins, miter limit, dashes, a stroke's outline as a shape (cases 3, 5, 7; D2) |
| Paint | `Color`; `GradientPaint`; `LinearGradientPaint` and `RadialGradientPaint` of many stops, with no-cycle, reflect, repeat, a focus and a transform; `TexturePaint` | `Color`; two-colour linear and radial, padded | Many stops, repeat and reflect, a focal point, a sweep, an image pattern (case 6) |
| Compositing | `AlphaComposite`: the twelve Porter-Duff rules with an extra alpha; an XOR paint mode | Opacity; `NORMAL`, `ADDITIVE`, `MULTIPLY`; layers | Porter-Duff (erasing, masking); `SCREEN`, `DARKEN`, `LIGHTEN` |
| Transform | `AffineTransform`; `translate`, `rotate` (also about a point), `scale`, `shear`, `transform`, `setTransform`, `getTransform`; an inverse | `translate`, `scale`, `rotate` | Shear, concatenation, a getter, an inverse to map a pointer into drawing space (case 8) |
| Clip | `clip(Shape)`, `setClip`, `getClip`, `getClipBounds`, `hitClip` | Rectangle and one rounded rectangle; bounds | Any path; an exact rotated rectangle (case 4) |
| Text | `drawString`, attributed strings, `GlyphVector`, `TextLayout` (carets, hit testing, outline), `FontMetrics`, `LineBreakMeasurer` | Shaped text with fallback faces and bidi; styled paragraphs with wrapping, lists, ellipsis; metrics; carets and hit testing inside the text widgets | Text as a shape (stroked, clipped by, filled with an image) |
| Images | `drawImage` with a transform, a part, a background; `BufferedImageOp` filters; `BufferedImage.createGraphics` to draw off screen; `VolatileImage` | An image into a rectangle, a part, a tint, a mask; sampling; a GPU surface; a read-back of the frame | Drawing into an image off screen; a replayable recording |
| Hints | Antialiasing, text antialiasing, quality, stroke normalization, interpolation, fractional metrics | Always antialiased; pixel snapping; image sampling | None needed (section 5) |
| Hit testing | `hit(Rectangle, Shape, onStroke)`; `Shape.contains`; `Area` | None on the canvas | Containment in a fill or a stroke |
| Geometry algebra | `Area`: union, intersection, subtraction, exclusive or | None | Section 5 |
| Printing | `Graphics2D` is the printing API (`Printable`) | The same canvas paints a page into PDF (ADR 078) | None |

## 2. What the canvas has that the measure does not

The measure is a measure, not a model to copy. The canvas already draws things the platform's API
does not: shadows with a blur, a frosted backdrop, groups composited as one picture with an
opacity or a 3D transform, additive and multiplied light, GPU surfaces from 3D and video,
shaped text with fallback faces and bidirectional order, styled paragraphs, animated images,
SVG, pixel snapping at fractional scales, and a frame painted only where it changed. None of
that changes here, and nothing added may make it slower.

## 3. What is proposed, in principle

1. **Exact on the toolkit's three canvases, or not added.** What the OpenGL canvas draws, the PDF
   canvas writes and the recording canvas replays, each without approximation; the pixels and the
   page agree.
2. **The fast path stays the fast path.** A shape that draws in one batch today draws in one batch
   after; the counted draw calls of ADR 062 for the gallery do not rise. The new machinery runs
   only for what needs it: a path with a hole, a dashed stroke, a clip that is not a rectangle.
3. **Values per call, as paints are.** A stroke is passed with the call like a paint, not set as
   hidden state; the state the canvas keeps stays what it is (transform, clip, opacity, blend,
   sampling, snapping).
4. **Geometry in Java, filling in the renderer.** The stroker, the dasher, arcs, bounds and
   containment are platform-neutral code in `limn.graphics`, tested without a GPU; a renderer is
   asked only to fill a path under a rule. That is also what makes the new default methods exact
   for an application's own canvas.
5. **No `setTransform`.** A widget's canvas already carries its parent's translation; replacing the
   matrix would draw in the wrong place, which is the classic misuse of the platform's own. The
   getter and `concat` give everything `setTransform` is used for.

## 4. The gaps, and the proposal for each

### 4.1 Strokes

A `Stroke` value: width, cap (`BUTT`, `ROUND`, `SQUARE`), join (`MITER`, `ROUND`, `BEVEL`), miter
limit (4 by default, as in SVG), and a dash pattern with a phase. Immutable, built as
`Stroke.of(2).withCap(Stroke.Cap.ROUND).withDash(0, 6).withDashPhase(3)` (section 10 says why the
phase has a method of its own). `Stroke.outline(Path2D)` returns the
stroke's outline as a path, to be filled under the non-zero rule, which is what the platform's
`createStrokedShape` is, and what section 4.9 tests a hit against.

On the canvas: `drawPath`, `drawLine`, `drawRect`, `drawRoundRect`, `drawCircle` and
`drawEllipse` gain a `Stroke` overload. Each existing `float` overload keeps its look exactly
(round where it is round today, the rectangles' own corners where they have them), so no widget
changes by accident.

- **OpenGL:** a stroke that is opaque, solid and round-capped keeps today's capsules. Any other
  stroke, including every translucent one, is the outline filled once under non-zero (section
  4.2). That is what removes the dark joints of case 3 and D2.
- **PDF:** the native operators for width, cap, join, miter limit and dash, so a page keeps
  curves and stays small.
- **Default for another canvas:** `fillPath(stroke.outline(path), paint)`, exact once that canvas
  honours the fill rule.

### 4.2 Fill rules, holes, self-crossing

`Path2D` gains `setFillRule(FillRule)` with `NON_ZERO` (the default) and `EVEN_ODD`, as the
platform's path carries its winding rule and as SVG's `fill-rule` does.

**Behaviour change.** Today every subpath is filled on its own, so the union of subpaths is
drawn. Under non-zero, subpaths that turn the same way draw exactly as today; a subpath turning
the other way inside another becomes a hole. The toolkit's 14 calls of `fillPath` and `drawPath`
in components were read: every path they build is one contour, so none changes; the line chart's
area is one contour that crosses itself where its values change sign, and both of its lobes are
filled under either rule, which repairs D1. An application that relied on the union of opposite
subpaths changes look; the toolkit has no such path.

- **OpenGL:** a path of one contour that does not cross itself (nearly every path drawn today)
  keeps the ear clipper and its batch. Any other path is filled by stencil and cover: the
  triangles of a fan increment or decrement the stencil (or invert it, for even-odd), the existing
  fringe ring is drawn where the stencil says outside, and one cover quad paints where it says
  inside. That is three draws for that path, and exact for every path. Layer textures gain a
  stencil attachment so that it works inside a layer.
- **PDF:** `f` and `f*`, and `W` and `W*` for clips.

### 4.3 Clips

`clipPath(Path2D)` intersects the clip with any path under its rule. A `clipRect` under a rotation
or a shear stops widening to the bounding box and becomes a path clip, which fixes case 4.

- **OpenGL:** the rectangle and rounded-rectangle clips keep their current mechanism, which is
  cheap. A path clip opens a layer at the clip's bounds and, at the matching `restore`, keeps only
  what the path covers (destination-in, section 4.4) with an antialiased edge. A clip by a stencil
  would be cheaper and aliased; an edge without antialiasing is what this toolkit does not draw.
- **PDF:** `W n` with the path.
- **Default for another canvas:** `clipRect` of the path's bounds. That is a documented superset,
  like the rotated clip today; `clipBounds` stays honest about it.

### 4.4 Compositing

`BlendMode` gains the Porter-Duff rules (`CLEAR`, `SOURCE`, `DESTINATION_OVER`, `SOURCE_IN`,
`DESTINATION_IN`, `SOURCE_OUT`, `DESTINATION_OUT`, `SOURCE_ATOP`, `DESTINATION_ATOP`, `XOR`) and
the separable `SCREEN`, `DARKEN` and `LIGHTEN`. The enum already says a 1.x minor release may add
constants. They act on the innermost layer; the guide shows them inside `saveLayer`, where an
eraser (`DESTINATION_OUT`) or a mask (`SOURCE_IN`) means what a user expects. The Porter-Duff
`XOR` is the alpha rule, not the platform's XOR paint mode (section 5).

- **OpenGL:** each is a fixed blend function on premultiplied colour, so no shader and no read of
  the destination; `DARKEN` and `LIGHTEN` use the minimum and maximum equations, exact for opaque
  sources and to be measured for translucent ones before they are promised.
- **PDF:** `SCREEN`, `DARKEN`, `LIGHTEN` are native. The Porter-Duff rules are not: a layer that
  uses one is written into the page as an image at the page's resolution. That is exact in pixels
  and loses the vector; how often a document needs it is to be seen.

### 4.5 Transform

`limn.math.Affine2D`, an immutable 2D affine matrix: identity, translation, rotation, scale,
shear, `then`, `inverse`, and the mapping of a point. On the canvas: `shear(sx, sy)`,
`concat(Affine2D)`, `rotate(angle, cx, cy)`, and `transform()`, the current matrix (section 10
says from where). The getter is the one method ADR 072 noted cannot be a real default; its default
answers null, as `clipBounds` does, and the toolkit's canvases answer.

- **OpenGL:** `Transform2D` is already full; this exposes it. A shear turns snapping off, as a
  rotation does. Glyphs under a shear are sampled like rotated ones: legible and slightly soft.
- **PDF:** `cm`.

### 4.6 Paint

A `Gradient` paint: linear, radial with an optional focal point, and sweep (around a centre),
each with any number of stops at positions from 0 to 1 and a spread (`PAD`, `REPEAT`,
`REFLECT`). `LinearGradient` and `RadialGradient` stay as the two-colour shorthands they are. An
`ImagePaint`: an image repeated (or not) on each axis under an `Affine2D`, with a sampling.

- **OpenGL:** the stops of every gradient in a frame go into rows of one ramp texture, so
  gradients keep sharing a batch; the spread and the sweep's angle are a few instructions in the
  shader; a focal radial gradient is the two-circle formula. An image pattern samples its rectangle
  in the image atlas with the coordinate wrapped in the shader, since a picture inside an atlas
  cannot use the hardware's repeat.
- **PDF:** axial and radial shadings with a stitching function; repeat and reflect written out as
  repeated stops over the range the shape covers; a tiling pattern for the image. A sweep has no
  PDF shading: a mesh close enough to stay within half a device pixel at the page's resolution, to
  be measured.

### 4.7 Text as a shape

`ShapedText.outline(float x, float y)` returns the text's glyphs as a `Path2D` at that baseline,
read through the backend's glyph outline service that printing already uses. Text can then be
stroked, dashed, used as a clip (a picture seen through the letters), or tested for a hit. A glyph
with no outline (a colour emoji) is absent from the path, and the method says so.

### 4.8 Pictures and images off screen

- **`Picture`:** the recording canvas made public. A picture is recorded once, replayed onto any
  canvas at any scale (the screen, a printed page, another picture), and is immutable. It is how a
  diagram drawn once is shown many times, and how the same drawing goes to the screen and to a PDF.
- **An image drawn off screen:** `Images.render(width, height, scale, painter)`, which runs the
  painter on a canvas whose target is a texture and reads the pixels back into an `Image`. On the
  UI thread, through the running backend; it is what a thumbnail, an exported chart or a cached
  drawing needs.
- **Not proposed now:** a canvas over an image on any thread, without a window, as the platform's
  `BufferedImage.createGraphics` is. It needs a CPU rasterizer. The one already shipped (0.4)
  would need a release of its native library with its drawing calls exposed, and its pixels would
  differ from the GPU's at the edges, so a drawing would look different on screen and in its
  image. Question 4.

### 4.9 Geometry and hit testing

`Path2D` gains `arc` (centre, radii, start, sweep), `arcTo` (the tangent arc between two lines,
for rounded polygons), `addRect`, `addRoundRect`, `addEllipse`, `addPath` (with an `Affine2D`),
`bounds`, `contains(x, y)` under its rule, `transformed(Affine2D)`, and `Path2D.parse(String)` for
SVG path data, which is how icon and diagram tools exchange a shape. A stroke's hit is
`stroke.outline(path).contains(x, y)`; a pointer reaches drawing coordinates through the widget's
`sceneToLocal` and the inverse of whatever `Affine2D` the drawing applied.

### 4.10 What each addition costs where

| Addition | Pure Java | OpenGL canvas | PDF canvas | Recording canvas | Default method |
| --- | --- | --- | --- | --- | --- |
| `Affine2D`, shear, concat, getter | the value | exposes `Transform2D` | `cm` | records it | concat by decomposition (exact); getter null |
| Path geometry, `parse`, `contains` | all of it | none | none | none | none needed |
| Fill rule | containment | stencil and cover beside the ear clipper; stencil on layers | `f*`, `W*` | records it | per-subpath fill (today's) |
| `Stroke` | stroker and dasher | outline filled; fast path kept | native operators | records it | outline filled (exact) |
| `clipPath`, exact rotated clip | none | layer masked at restore | `W n` | records it | bounds (superset) |
| Blend modes | none | blend functions | native or rasterized group | records it | ignored, as `setBlendMode`'s default is today |
| `Gradient`, `ImagePaint` | the values | ramp texture, shader | shadings, patterns | records it | none: an application's canvas receives a paint type it may not know, as `Paint`'s Javadoc will say |
| Text outline | none | none | none | none | through the backend service |
| `Picture`, image off screen | `Picture` | render target, read-back | replays | is it | none |

## 5. Not in this record

- **Constructive geometry** (the platform's `Area`: union, intersection, subtraction of shapes).
  Clipping and the even-odd rule cover most of what applications use it for; a robust polygon
  boolean on curves is a large piece of code to own. Question 6.
- **The XOR paint mode**, which inverts against the destination: a technique of displays without
  compositing, and a draw of its own on every backend.
- **Custom `Paint` and `Composite` in Java**, run per pixel: on a GPU there is no per-pixel Java.
  A shader-level extension point is a different and larger decision.
- **Rendering hints.** Antialiasing is always on; pixel snapping and sampling already exist; quality
  levels have no meaning when one path is exact.
- **Image filters** (convolution, lookup, colour matrix) and the group effects ADR 071 left out
  (blur, colour matrix, mask of a widget). Image processing is the application's.
- **Colour management** (colour spaces, profiles).
- **A CPU canvas on any thread** (section 4.8), unless question 4 says otherwise.

## 6. Questions for the owner

1. **Which stages, in what order?** *Recommended:* all six, in the order of section 7. Stages 1 to
   4 remove the eight cases of section 0.3 and both defects; 5 and 6 are what a graphics
   application asks for next.
2. **The default fill rule.** *Recommended:* `NON_ZERO`. It is the platform's default, SVG's and
   PDF's; it draws today's paths unchanged when their subpaths turn the same way. `EVEN_ODD` as
   the default would turn some overlaps of today's same-direction subpaths into holes.
3. **A stroke per call, or as state?** *Recommended:* per call, as the paint is. The platform keeps
   the stroke as state (`setStroke`), and a widget that sets one and forgets to restore it changes
   every widget painted after it.
4. **Off screen: GPU only, or a CPU canvas too?** *Recommended:* `Picture` and the GPU image now
   (section 4.8), and a CPU canvas only when an application needs drawing off the UI thread. The
   cost of doing it now is a natives release and a second rasterizer whose edges differ.
5. **Porter-Duff in `BlendMode`, or a separate composite type?** *Recommended:* in `BlendMode`. It
   is one state that is already saved and restored, and the enum already admits constants. A
   separate type would add a second state that combines with the first in ways to document.
6. **Constructive geometry?** *Recommended:* not now (section 5). If a diagram or drawing
   application needs it, a stage 7 over flattened paths, with curves lost in the result.
7. **D1 and D2 before stage 2, or with it?** These are defects, and they get fixed; the question
   is how. *Recommended:* with stage 2 and stage 3, which fix them at the root and for every
   path, with a pixel test on the chart. If the stages wait, `LineChart` alone can split its
   flattened area where it crosses the baseline (D1) and draw a translucent line through a layer
   (D2).

## 7. Stages

Each stage carries all four canvases and the test fakes, its pixel tests on the headless context
(as the probe of section 0.3 did, but asserting), its PDF tests, the ADR 062 counts unchanged for
the gallery, a section in the custom-widgets guide, and a page of the demo drawing it. Line counts
are estimates from the closest code in the tree (`Path2D` 290, `PolygonTriangulator` 120,
`GlLayerPool` 204, `PdfCanvas` 730), not measurements.

| Stage | What | Fixes | Estimate |
| --- | --- | --- | --- |
| 1 | `Affine2D`, shear, concat, getter; path geometry, `parse`, `contains`; `Stroke` with its stroker and dasher, in Java only | case 8 | 1,500 lines with tests |
| 2 | Fill rules on every canvas; stencil and cover; stencil on layers | cases 1, 2; D1 | 800 |
| 3 | Strokes drawn on every canvas | cases 3, 5, 7; D2 | 600 (the stroker is stage 1) |
| 4 | `clipPath`, exact rotated clip, blend modes | case 4 | 900 |
| 5 | `Gradient` and `ImagePaint` | case 6 | 1,000 |
| 6 | Text outlines, `Picture`, image off screen | — | 700 |

## 8. How it is verified

- **The eight cases and the chart** of section 0.3, redrawn by an asserting pixel test after
  stage 4: the hole is empty, the star is whole, the joints of a translucent zigzag are the
  stroke's own alpha, the rotated clip stops at its edges, the chart's area follows its line on
  both sides of zero.
- **The same drawings in PDF**, read back by the PDF tests the printing already has, and compared
  with the screen in a rasterized page.
- **The gallery's counted draw calls** before and after each stage, equal for what did not change.
- **Two guests** (Windows, the ANGLE path; Linux) for the stencil, which is the one part that
  depends on the driver.

## 9. The owner's answers

On 2026-11-01 the owner took the recommendation of every question in section 6:

1. **All six stages, in the order of section 7.**
2. **`NON_ZERO` is the default fill rule.**
3. **A stroke is passed per call**, like a paint; the canvas keeps no stroke state.
4. **`Picture` and the GPU image now; a CPU canvas only when an application needs drawing off the
   UI thread.**
5. **Porter-Duff goes into `BlendMode`.**
6. **No constructive geometry now.**
7. **D1 and D2 are fixed by stages 2 and 3**, at the root, each with a pixel test on the chart.

## 10. Stage 1, as built

Built 2026-11-01, uncommitted at the time of writing, on the branch of ADR 083's styled text.

- **`limn.math.Affine2D`** is a record of the six components PDF and `Mat4.affine2D` name
  (`a` to `f`), in double precision, validated finite, with a negative zero stored as zero so that
  a turn by nothing equals `IDENTITY`. Section 4.5 named a `then`; it was not built. The building
  methods (`translate`, `scale`, `rotate`, `shear`, `concat`) each apply their operation first, as
  the canvas's calls compose, so a matrix and a canvas built by the same calls agree, and one
  composition order is the only one to learn. The inverse is `invert()`, as `Mat3` and `Mat4`
  name theirs. It replaces the printing's internal `Affine`, which was the same map under another
  name.
- **The canvas** gains `rotate(angle, cx, cy)`, `shear`, `concat` and `transform()`, all default
  methods. `concat`'s default composes the matrix from `translate`, `rotate` and `scale` by the
  singular value decomposition the layer default already used, and that default now calls it.
  `transform()` answers from the corner of the window or the page, the content scale taken out,
  across every layer whose picture lies affinely in the window, and from a layer's own origin only
  under a perspective, where no affine matrix reaches the window. That replaces "from the widget's
  origin" in section 4.5, which no canvas can know. The OpenGL canvas keeps, per state, where its
  logical origin lies in its device pixels and in the window; the PDF canvas composes the forms of
  the layers it is inside.
- **`Path2D`** gains the fill rule (`FillRule`, `NON_ZERO` by default, kept by `reset`, `copy` and
  `transformed`), `arc`, `arcTo`, `ellipticalArcTo` (the SVG arc, which `parse` needs and which an
  application drawing from SVG needs too), `addRect`, `addRoundRect` with the corners a canvas
  draws, `addEllipse`, `addCircle`, `addPath` with or without a matrix, `transformed`, `bounds`
  (the curves' reach, not their control points; null for an empty path), `contains`, and
  `parse`. Arcs are cubic curves of at most a quarter turn, within three ten-thousandths of the
  radius, so a renderer still sees five kinds of segment. A close now leaves the current point at
  the subpath's start, as SVG does. The canvases do not yet fill by the rule: that is stage 2.
- **`Stroke`** takes SVG's defaults (butt ends, mitred corners, limit 4). The phase is
  `withDashPhase`, apart from `withDash(float...)`, because the example of section 4.1,
  `withDash(0, 6, 3)`, read either as a phase and a pattern or as a pattern of a dot, a gap and a
  dash. A pattern of an odd count is repeated, as SVG does. `outline` builds the stroke's region as
  a sum of pieces that all wind one way (a quadrilateral per segment, a wedge on the outside of each
  corner, a cap per end), so it is exact under non-zero with no edge intersections computed; the
  inside of a corner goes in to the corner point, or straight across when both segments are at
  least half the width long. Inside a flattened curve the corners are round, whatever the join. A
  subpath that is only a move draws nothing, and one that goes nowhere is a dot under round and
  square ends, as SVG draws them; a dash pattern turning on exactly at an open path's end leaves a
  dot there, as the start does. A pattern that would cut the path into more than 100,000 dashes is
  not followed and the line is drawn solid.
- **Tests:** `Affine2DTest`; `Path2DGeometryTest` (arcs on their circle, tangent and SVG arcs,
  shapes, bounds, both rules on a framed square, a doubled square and a star, the matrix, every
  command of path data and where malformed data fails); `StrokeTest`, which holds the outline of
  random polylines (turns straight back and segments shorter than the width included) against the
  definition point by point: within half the width for round ends and corners, solid and dashed,
  and the rectangles and wedges for butt ends with bevelled or mitred corners; a mutation that sent
  every inner corner straight across was caught. `CanvasTransformDefaultsTest` composes mirrors and
  singular matrices through the defaults; `CanvasTransformPrintTest` the recording and PDF
  canvases; `GlCanvasTransformTest`, on a headless context, the getter through a turned and a
  perspective layer and a shear in pixels.
- **Owed to the stages after it:** the custom-widgets guide covers what this stage built; the demo
  page of section 7 waits for stage 3, the first that draws anything new.
