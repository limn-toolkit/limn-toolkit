# ADR 072: A transform moves the picture and leaves the layout

- **Status:** Proposed, 2026-10-17; decided the same day. The owner decided on 2026-10-17 that
  opacity and transforms are containers, as `Padding` is for insets, and not methods of `Widget`;
  opacity moved from `Widget` into an `Opacity` container by an amendment of ADR 071 (its section
  7), and this record designs the transform container beside it. The owner answered every
  question of section 4 the same day, and one more the answers raised (section 4.1): the two seams, the
  2D tier and the 3D tier all ship before 1.0, in one final `Transform`. The measurements of
  section 7 are owed on the lab's guests before the status moves to Accepted; a number that
  contradicts a decision comes back to the owner. **Stage 0 built, 2026-10-18** (section 8): the
  two seams, the internal hook and the test-only container that prove them at rotated and turned
  points, the sentence on `Change.Aspect`, and the hit test cut at a clipping parent's clip.
  **Stage 1 built, 2026-10-18** (section 9): the `Transform` container with its translation,
  scale and origin, `slideTo`, `scaleTo` and a flat `flipTo`, `Change.Aspect.TRANSFORM`, every path
  stage 0 left summing offsets routed through the matrix, the dialog's slide, and section 7's glyph
  measurement on this Mac. **Stage 2 built, 2026-10-18** (section 10): the rotation, `rotateTo` and
  a flat `setMatrix` on the container, drawn through a picture in local space that the canvas's new
  `saveLayer` with a matrix composites as a projected quad under the enclosing clip, the warped
  glass, popup windows placed again, `Opacity.fadeTo`'s finite seconds, and section 7's two layer
  measurements on this Mac; stage 3 is not built.
- **Date:** 2026-10-17
- **Scope:**
  - a container that draws its child moved, scaled, rotated or turned in 3D, without changing
    where layout put anything;
  - how the renderer draws each kind of transform, and what each costs a frame;
  - hit testing, coordinate conversion, partial rendering, culling, focus, the accessible tree,
    the input method's anchor, popups and backdrop effects under a transform;
  - how it nests with the `Opacity` container;
  - the two API shapes that must change before 1.0 so that any of it can be added after;
  - the order in which it is built.
- **New public API (proposed):** before 1.0, `Widget.localToScene(x, y)`,
  `Widget.sceneToLocal(x, y)` and `Widget.localToSceneBounds(x, y, width, height)` in place of the
  four per-axis conversions, and `Widget.hitTest` made the final entry of a protected
  `onHitTest` hook with a `hitTestChild` helper (section 3); and, also before 1.0, the container
  `limn.scene.layout.Transform`, its animations, `Change.Aspect.TRANSFORM` and
  `Canvas.saveLayer(x, y, width, height, opacity, Mat4)` (section 2).
- **Compatibility:** section 3 removes `sceneToLocalX(float)`, `sceneToLocalY(float)`,
  `localToSceneX()` and `localToSceneY()`, and an override of `hitTest` stops compiling; section
  3.4 says what a 0.9.0 user changes. The container itself is additive.

---

## 0. What this rests on

No probe was run for this record. Every cost below is read from ADR 071's measurements, the
same layer and composite a transform would use, or is marked as an estimate; section 7 lists
what must be measured before the status moves. The facts about the code were read at `a6780e72`
(`claude/review-fixes`, right after ADR 071 was built) and are summarised from the survey of
2026-10-17 that preceded this record:

- **`Canvas`** has `translate`, `scale` and `rotate(radians)` and no matrix API. The backend keeps
  a full 2D affine per state. Under a rotation, clips become the device bounding box of the
  rotated rectangle (content shows in the corners), pixel and stroke snapping turn off, and
  `clipBounds()` answers `null`, so nothing under it is culled.
- **Text** is drawn from coverage bitmaps rasterized at the physical size, snapped when the
  transform is axis-aligned and uniform. Rotated or skewed, the glyph quads are sampled
  bilinearly: legible and slightly soft.
- **ADR 071's layer** draws a subtree into a pooled RGBA8 premultiplied picture and composites it
  with a program of its own, `canvas_composite`: one quad, `vec2` positions in the window's
  device pixels, under no clip, the opacity and nothing else. The picture is sized to what the
  subtree paints (`Widget.paintedBounds`), kept only while consecutive frames use it, and its
  corner sits on the window's grid of two by two pixels.
- **A widget's position flows one way.** `x()`/`y()` are the layout position in the parent;
  `Widget.paintChildren` translates by them and calls the child's `paintWidget`, which is
  `public final`. Hit testing is `public`, overridable, and maps a point into a child by
  subtracting `child.x()`/`child.y()`. The scene converts with four per-axis methods that sum
  offsets up the parents.
- **`limn.math`** already has public `Vec2`, `Vec3`, `Vec4`, `Quat`, `Mat3`, `Mat4` (with
  `perspective`, `invert`), `Transform3D`, `Ray`. `limn.render3d` draws 3D content inside a flat
  widget; nothing projects widgets.
- **`Dialog`** fakes a translation: its card's slide re-places the card with `moveChild` inside
  `paintChildren` on every frame of the fade, and invalidates the accessible tree each frame
  because the layout never saw that box.

## 1. Why

Applications for business desktops move parts of their windows: a drawer slides in from the
side, a page replaces another, a pressed card shrinks a little, a panel turns over to show its
back. Limn can fade them (ADR 071) and cannot move them except by laying them out again on every
frame, which moves what the layout, the reader and the hit test see, and costs a layout per
frame. The toolkit's own dialog already needs a move that is only a picture.

Two shapes of today's API stand in the way of ever adding a rotation or a turn in 3D after 1.0:
a per-axis coordinate conversion cannot express a rotation at all, since a local x then depends
on the scene's x and y; and an overridable hit test lets any container, the toolkit's or an
application's, decide by itself where its children are. Section 3 is about those two, and they
are the part with a deadline.

## 2. Decision (proposed)

### 2.1 A container that moves a picture

- **`Transform` is a container of one child**, in `limn.scene.layout` beside `Padding` and the
  `Opacity` of ADR 071's amendment. `new Transform(child)`; it measures as its child and lays the
  child at its own origin with its own size, so with every value at rest it is invisible to
  layout, painting, input and readers.
- **It is a render transform.** It changes where the child's picture is drawn, where the pointer
  finds it, and the bounds published for it. It changes nothing layout reads: the Transform's
  box, its child's box, `x()`, `y()`, `width()` and `height()`, and every sibling's place are what
  they would be at rest. A child scaled to twice its size overlaps its neighbours; it does not
  push them.
- **A layout transform is not in this record.** A transform that layout measures (a rotated
  header label that takes the room of its rotated box) is offered by a few desktop frameworks and
  used mostly for vertical text; the chart paints its vertical title itself, and nothing in the
  toolkit or the Showcase asks for it. If it is ever wanted it is a layout container of its own,
  measured by the rule of the simple layouts (ADR 049, 050), and not a mode of this one.
- **It paints nothing of its own and is transparent to the accessible tree**, as `Padding` is.
  It takes no focus. Its child keeps its roles, states, names and order.
- **It does not clip.** What the child draws past the Transform's box is seen there. A Transform
  that should cut its child is put inside a container that clips.

### 2.2 The API

All setters are fluent, UI thread only, and throw when called from inside a paint, as
`setOpacity` does (ADR 071 §2.1). `NaN` and infinities are refused with
`IllegalArgumentException`. Lengths are logical points; angles are degrees.

- **`setTranslation(float x, float y)`**, `translationX()`, `translationY()`. `x` runs toward the
  end of the layout direction: right in a left-to-right subtree, left in a right-to-left one
  (question 6). `y` runs down.
- **`setScale(float sx, float sy)`** and `setScale(float s)`, `scaleX()`, `scaleY()`. A negative
  factor mirrors, a factor of 0 collapses the child to a line or a point, which then draws
  nothing and takes no pointer.
- **`setOrigin(float fractionX, float fractionY)`**, `originX()`, `originY()`: the point that a
  scale, a rotation or a turn keeps still, as fractions of the box, `(0.5, 0.5)` by default.
  `fractionX` is measured from the start edge, so a scale that grows from the start grows
  rightward in a left-to-right subtree and leftward in a right-to-left one, without the
  application asking which.
- **`setRotation(float degrees)`**, `rotation()`: clockwise on the screen, as `Canvas.rotate`
  turns. **Physical**: a right-to-left subtree turns the same way, since a clock does.
- **`setRotation3D(Vec3 axis, float degrees)`** and `setRotation3D(Quat)`, `rotation3D()`: a turn
  of the child's plane about an axis through the origin. The axes are the canvas's: x to the
  right, y down, z out of the screen toward the viewer; an angle about the z axis turns as
  `setRotation` does, and the handedness of the other two follows from it, pinned by a test
  rather than by prose. Physical, like `setRotation`.
- **`setPerspective(float distance)`**, `perspective()`: how far from the plane the eye is, in
  points, measured from the origin along z. `Float.POSITIVE_INFINITY`, the default, is no
  perspective: a turn about y then narrows the child without foreshortening it. A distance of two
  to four times the box's larger side is the usual look, and the Javadoc says so. The vanishing
  point is the origin.
- **`setBackfaceVisible(boolean)`**, `isBackfaceVisible()`: whether the child is drawn and hit
  while its plane faces away from the viewer. `true` by default.
- **`setMatrix(Mat4)`**, `matrix()`: a general matrix, identity by default, applied about the
  origin after the scale. A 2D affine, a skew included, is a `Mat4` whose z row and column are
  the identity's; no separate 2D affine type is added, because `Mat3` is a 3D normal matrix
  and a second matrix type would carry the same numbers. The child's plane is `z = 0`, so a
  matrix that moves points in z only matters through the perspective. (2026-10-18, stage 2:
  built with a matrix that keeps the plane flat, its bottom row `(0, 0, any, w > 0)`, and refused
  otherwise until stage 3 brings the perspective; `Mat4.affine2D` makes one from a 2D affine map's
  six numbers, the factory a skew needed; section 10.2.)
- **The order is fixed.** For a point `p` of the child, with `O` the translation to the origin:
  `O · translation · perspective · rotation3D · rotation · scale · matrix · O⁻¹ · p`, then the
  Transform's own position in its parent. So a scale, a rotation and a turn share one pivot, a
  translation moves the result, and the perspective looks at it from in front of the origin. An
  application that needs another order nests two Transforms or writes the `Mat4`. (2026-10-18,
  stage 1: a flip's turn, which is not a value, takes its place between the perspective and
  `rotation3D`, so it turns the child as it appears, whatever turn it has; section 9.2.)
- **`reset()`** puts every value back at rest, as one change.

### 2.3 Animations and announcements

- **One animated form per motion**, in the style of `fadeTo`:
  - `slideTo(float x, float y, double seconds, @Nullable Runnable onArrive)` and `slideTo(x, y)`;
  - `scaleTo(float sx, float sy, double seconds, @Nullable Runnable onArrive)`, `scaleTo(s)`;
  - `rotateTo(float degrees, double seconds, @Nullable Runnable onArrive)`, `rotateTo(degrees)`;
  - `flipTo(Flip direction, double seconds, Runnable atEdge, @Nullable Runnable onArrive)` and
    `flipTo(direction, atEdge)`.
- **They behave as `fadeTo` does** after ADR 071's review: the short form takes the theme's window
  animation; one ticker per Transform on the scene's wall-time ticker, only while something moves;
  eased as `fadeTo` eases; a newer call for the same motion, or a direct set of the same value,
  supersedes the one in flight and its `onArrive` does not run; motions of different values run
  together (a slide while a scale); a Transform that leaves its scene settles every motion at its
  target as it leaves and runs their `onArrive` then; with no scene to tick, or for zero seconds,
  it goes straight to the target.
- **`flipTo` turns the child over and back.** It turns the plane from flat to edge-on about the
  axis `direction` names, runs `atEdge` there (where the application swaps what the child shows:
  hides one face, shows the other), and turns it from edge-on the other side back to flat, so the
  new content arrives the right way round. `Flip.FORWARD` and `Flip.BACKWARD` turn about the
  vertical axis the way a page turns forward or back in the layout's reading direction: forward,
  the end edge comes up toward the reader and over to the start, so a right-to-left subtree turns
  the other way. `Flip.UP` and `Flip.DOWN` turn about the horizontal axis and are physical. A flip
  uses the Transform's perspective; with none it narrows the child to a line and widens it back,
  which is a flip drawn flat and needs no 3D (section 6, stage 1). It leaves `rotation3D()` as it
  found it. (2026-10-18, as built: one eased motion over the whole turn, fastest edge-on; a newer
  `flipTo` turns on from where the one it supersedes stopped and runs its own `atEdge`; section
  9.2.)
- **Less motion.** While the system asks for less motion, `slideTo`, `scaleTo` and `rotateTo` go
  straight to the target and `onArrive` runs at once, as `fadeTo` does. `flipTo` runs `atEdge`
  and then `onArrive` at once, swapping the content with no turn (question 8).
- **A watcher hears a transform change by the owner's rule of ADR 071** (decision 2 there),
  through a new aspect `Change.Aspect.TRANSFORM`, origin `CODE`:
  - a direct set that changes a value announces it, every time; `reset()` announces once
    (2026-10-18: besides the end of each motion it supersedes, as any set's supersession
    announces);
  - an animation announces when it starts and when it arrives or is superseded, and its frames in
    between set the value and damage the picture without announcing;
  - an animation that goes straight to its target is a direct set;
  - nothing is announced for a value set to what it already is.
- **The accessible tree is walked when a value is set and when an animation arrives**, not on
  its frames (question 9): a reader's highlight jumps to where the child came to rest.

### 2.4 How it is drawn

There are three cases, and only the third makes a picture.

- **At rest, nothing changes.** Every value at rest is the identity, and `paintChildren` is the
  frame of today: no matrix, no layer, no cost. The scene keeps a count of Transforms that are not
  at rest; while it is zero, every path below is today's.
- **A translation and a scale are drawn straight into the canvas.** The Transform's
  `paintChildren` applies them with `Canvas.translate` and `Canvas.scale` about the origin before
  the child's `paintWidget`. (2026-10-18, as built: `Widget.paintWidget` applies them, for any
  widget whose matrix keeps the plane's axes, from the very matrix the hit test and the
  conversions read; section 9.2.) Every clip inside stays exact (an axis-aligned transform keeps a
  rectangle a rectangle), glyphs stay snapped and crisp at a uniform scale, `clipBounds()` keeps
  answering, and the frame costs only the pixels the move repaints. A non-uniform scale (a flat
  flip) turns snapping off while it lasts, as `Canvas.scale` does today: soft for a transient,
  crisp again at rest. A scale animation rasterizes glyphs at each new size, by steps of an
  eighth of a pixel; what that does to the glyph atlas is unmeasured (section 7).
- **A rotation, a skew, a general matrix and every 3D value are drawn through a layer** in the
  child's own, axis-aligned space, and the picture is composited through the transform (question
  5). This is ADR 071's layer with three differences:
  - **The picture is drawn in local space, and so is re-based.** ADR 071's build re-bases nothing,
    because an opacity layer lies on the window's pixels. A transform's picture does not: the
    canvas opens it with a transform of its own, the Transform's local space times one scale, and
    a device clip of its own, the picture's rectangle. Every clip inside (a scroll view, a text
    field, a table) is therefore an exact rectangle again, rounded clips included, and
    `clipBounds()` answers, so culling inside works as it does in the window. (2026-10-18, as
    built: that space times the one scale, moved by whole pixels so that the picture lies at the
    bottom-left of a space the window's size, its corner on that space's 2x2 grid; section 10.2.)
  - **The picture covers the part of the child that can be seen**: what the subtree paints
    (`paintedBounds`), intersected with the enclosing clip and the repaint pass mapped back into
    local space through the inverse transform. A large panel turned inside a small viewport draws
    only its visible part.
  - **Its scale** is the device scale times the largest magnification the transform gives any
    point of the picture: for a rotation, the scale already in force; under perspective, the
    near edge's (a 400 pt card turned 90 degrees about its vertical axis at a distance of 800 pt
    shows its near edge at 1.33 times). Capped, per side, at the window's framebuffer; past that it
    is rasterized smaller and composited softer.
- **The composite becomes a projected quad.** `canvas_composite.vert` takes `vec4` positions: the
  picture's four corners through the canvas's current transform and the Transform's matrix, in
  homogeneous coordinates, so the GPU interpolates the texture perspective-correctly and clips the
  part of a plane that falls behind the eye (`w < 0`) by itself. A 2D transform has `w = 1`, and
  so does ADR 071's opacity composite, which keeps one program for both.
- **The enclosing clip is applied at the composite.** ADR 071's composite draws under no clip
  because its picture was drawn under the clip already; a picture drawn in local space was not.
  The enclosing device rectangle and the repaint pass are applied as a scissor, which costs
  nothing per pixel. An enclosing rounded clip (a Transform inside a rounded panel) needs the
  rounded clip's distance in the composite program; it is a variant of the program used only when
  a rounded clip is in force, because ADR 071 §6.1 found that the canvas program's clip over every
  pixel of a picture cost Apple's software renderer 251 ms on a window's frame. Its cost there is
  owed (section 7). (2026-10-18, as built: the variant also takes a rectangle with an edge between
  pixels, which a scissor cannot cut as the canvas program does, and runs only on the strips of
  the picture near the clip's edges and corners, the plain program under a scissor over the rest;
  measured in section 7, section 10.2.)
- **Edges are antialiased by the picture's own transparent ring.** The picture covers the
  painted bounds grown by a pixel, so its outermost texels are clear, and bilinear filtering
  ramps a rotated edge over about a pixel. A pooled picture larger than the part drawn clears a
  ring of one texel around the part, since texels past it hold an earlier frame's content and the
  filter reads them. A coverage term in the shader would be sharper and costs per pixel; not
  proposed.
- **Text at an angle** is bilinear in both paths: drawn straight, the glyph quads are sampled at
  an angle; through the layer, the picture is. At an oblique 3D angle the picture is also
  minified, which aliases at rest and shimmers while it turns. Whether mipmaps are made for that
  is question 11.
- **The backface.** With `setBackfaceVisible(false)`, a composite whose projected quad winds the
  other way is skipped. Edge-on (exactly 90 degrees) the quad has no area and draws nothing.
- **What is not rasterized twice.** A Transform at rest makes no picture; a translation or a
  scale makes none; ADR 071's group under a Transform's translation or scale is ADR 071's group.
  Only a rotation, a skew or a turn costs a picture.
- **The canvas method** is public, as ADR 071's decision 4 made `saveLayer` public:
  `Canvas.saveLayer(x, y, width, height, opacity, Mat4 transform)`, closed by `restore()`. Its
  default implementation, for canvases that cannot make layers (the recording and measuring
  canvases of the tests), applies the 2D part of the matrix straight to the canvas and the opacity
  per draw, which draws the same commands and is right for counting them.

**What it costs a frame, read from ADR 071.** A turning or rotated Transform costs what a fading
group of the same size costs, since it is the same layer and one quad; a projected quad covers at
most the pixels of a flat one, so the composite is estimated at no more per pixel (the
perspective divide is per vertex; not measured).

| Picture | This Mac's GPU | Windows guest | Fedora guest | Software renderer (macOS guest) | Memory, while it moves |
|---|---|---|---|---|---|
| a card or popup, ~700 × 900 px | +0.04 ms CPU, +0.03 ms GPU | +0.09 ms GPU | +0.025 ms GPU | +1.2 to 2.2 ms (1 to 2% of the frame); +2.8 to 3.4 ms as built | 2.4 MiB |
| a whole page, 2560 × 1600 px | +0.12 ms GPU | +0.10 ms GPU | +0.15 ms GPU | +17 to 19 ms (about 10%) | 15.6 MiB |
| a second level inside it | +0.03 ms of each | +0.06 ms GPU | +0.04 ms | +1.1 to 1.8 ms | one more picture |

The picture is freed the frame after the motion ends, as ADR 071 §2.5 decides. A Transform left
at rest at an angle is redrawn into its picture whenever something inside it repaints, and pays
the making of the picture again each time, 0.1 to 0.5 ms on this GPU (ADR 071 §0).

### 2.5 With `Opacity`

- **They nest either way and the picture is the same.** Opacity and geometry commute: a child
  faded and then turned looks as one turned and then faded. The documented form for "fade and
  move" is `new Opacity(new Transform(child))`, the opacity outside, which reads as "fade this
  moving thing" and puts the opacity's picture on the window's grid.
- **They share the layer machinery**: the same pool, the same picture sizes rounded to 64
  pixels, the same composite program, `w = 1` for a fade and a projected quad for a turn.
- **What each order costs.** With a translation or a scale there is one picture, the opacity's,
  in either order. With a rotation or a turn there are two, the transform's and the opacity's,
  in either order: ADR 071's NESTED rows, +0.03 ms on this GPU, +1.1 to 1.8 ms on the software
  renderer. Folding an `Opacity` that is the direct child or parent of a layered Transform into
  the Transform's composite, which already multiplies by an alpha, would make it one; question 12.
- **`Opacity`'s picture covers a Transform inside it**: `paintedBounds` grows by each widget's
  `paintOutset`, and a Transform's outset is its projected overhang (section 2.6).

### 2.6 What a partial frame repaints, and what is culled

- **A Transform declares its overhang as its outset.** `paintOutset()` answers the largest
  distance, on any side, by which the projection of the child's box grown by the child's own
  outset passes the Transform's box, computed when a value changes and held, so the hook stays
  constant-time. (2026-10-18, as built: computed on asking, four corners through the matrix,
  which is constant-time too and cannot go stale when the child's own outset changes; section
  9.2.)
- **A change damages the old and the new projected bounds**: the axis-aligned bounds of the
  child's box and outset through the old matrix and through the new one, each clipped at
  `w > ε`, in the scene, clamped by clipping ancestors as `invalidate()` damage is. No layout.
  (2026-10-18, as built: the child's whole painted bounds, a descendant laid past its box
  included, as an opacity's change damages; section 9.2.)
  Under perspective these may exceed any fixed outset (the 1.33 of section 2.4), which is why the
  projection, not a declared number, is what is damaged. An animation frame damages the bounds it
  leaves and the bounds it reaches.
- **A pass reaches a moved child wherever it is drawn.** A pass culls a subtree by its root's box
  and outset, and a Transform's ancestors know nothing of where it moved its child: a drawer slid
  past its parent's box would be repainted by a whole frame and skipped by a partial one, the
  stale-pixel defect partial rendering exists to avoid. So the scene keeps the Transforms that are
  not at rest in a set, as it keeps the widgets that paint from the backdrop (ADR 043, ADR 019
  §6), and a pass that meets one's projected bounds paints the path down to it whatever its
  ancestors' boxes say. An ancestor that clips its children still clips; the pass follows the
  paint.
- **Culling under a transform.** `Scene.culledFromPaint` tests a widget under a Transform by its
  projected bounds; inside a layer, `Widget.missesClip` tests against the picture's local clip,
  which is exact again. With no Transform away from rest the test is today's.

### 2.7 Hit testing and coordinates under a transform

Both depend on section 3, and with it they are short.

- **A Transform maps a point into its child through the inverse of its matrix**, in
  `hitTestChild` (section 3.2): an inverse affine for 2D; for 3D, the ray from the eye through
  the point meets the child's plane, and a point that meets it behind the eye, edge-on, or on a
  hidden backface is a miss. A collapsed scale is a miss.
- **A Transform is hit only where its child is**, inside its box or outside it, and answers
  `null` elsewhere: when the child has slid away, the place it left takes no pointer from what is
  beneath.
- **Its ancestors let the pointer through to it** by the rule of section 2.6: a point outside a
  widget's box goes on to its children when the widget does not clip them and a Transform's
  projected bounds under it hold the point, so a slid drawer is hit where it is drawn. With no
  Transform away from rest, the box rule is today's.
- **Every conversion goes through the transforms on the way**: `localToScene`, `sceneToLocal` and
  `localToSceneBounds` of section 3.3. A widget inside a rotated panel that converts an event's
  scene point gets its own local point, exactly; a drag measured by differences of local points
  is right at any angle.

### 2.8 Focus, reveal, readers, the input method and popups

- **Focus rings** are drawn by each widget inside its own paint, so they turn with it, and lie
  inside the picture, which covers the painted bounds.
- **`revealInView`** hands a scrolling ancestor above a Transform the projected bounds of the
  widget in that ancestor's space; a scrolling ancestor inside the Transform is unaffected.
  `isShowing` intersects the projected bounds with each clipping ancestor's clip.
- **The accessible tree** publishes, for every node, synthetic child and text range under a
  Transform, the axis-aligned bounds of its projection, as platforms do for transformed elements.
  The tree, its roles, states and the shapes of ADR 045 do not change, and no bridge changes. The
  published bounds follow section 2.3's walk: on a set and on an animation's arrival. A live
  reader run is not owed: what the readers read moves only through the bounds every node already
  publishes.
- **The input method's caret rectangle** is the bounds of the projected caret.
- **Popups and tooltips anchored inside a Transform** stand upright against the anchor's
  projected bounds; they are not turned with it. They read those bounds when they open and when
  they are placed again: an in-scene popup's layer is laid out again, and a popup window is placed
  again, when a Transform above its anchor is set or arrives. They do not follow the frames of an
  animation. (2026-10-18, stage 1: the in-scene layer is laid out again; a popup in a window of its
  own is not placed again yet, section 9.4. Stage 2, the same day: a popup window placed against a
  widget is placed again by its owner, which the scene asks; a popup menu, opened at a point or a
  rectangle and not against a widget, is not followed; section 10.2.)

### 2.9 Backdrop effects under and inside a transform

- **Under a translation or a scale** a glass is drawn straight into the window and reads what it
  reads today; under ADR 071's group it reads as ADR 071 §2.6 decides.
- **A glass beneath a Transform** (drawn before it) is repainted when the Transform's damage
  reaches it (`paintsFromBackdrop`), as for a fading group.
- **Inside a rotated or skewed picture** (2D, a layer in local space) the window under it is not
  at the picture's pixels. Proposed: it reads what it would read if the picture were opaque, as
  inside ADR 071's group, by drawing the window's copy into local space through the inverse
  affine, one more quad per glass per open picture over the glass's bounds; a frosted card turned
  a few degrees keeps showing the window through its glass (question 10). (2026-10-18, as built,
  into a second copy the window's size; within 8 steps of the glass upright; section 10.2.)
- **Inside a 3D picture**, a glass reads only the subtree's own content: the Transform is a
  backdrop root, as a web page's isolated groups are. Warping the window through an inverse
  projection is possible with the same program, but what a blur reads through a plane seen at an
  angle has no answer a user could check, and a flip lasts a third of a second (question 10).

### 2.10 Several turns at once

- **Each 3D Transform is flattened into its parent's plane.** A turned Transform inside another
  turned one is a picture composited into the outer picture, which is then projected. Nothing is
  depth-sorted.
- **Children do not share a 3D space** (the web's `preserve-3d`): two cards turned toward each
  other do not intersect, the later one is drawn over. Sharing a space needs depth sorting and
  plane splitting between siblings, is the most error-prone part of the platform that has it, and
  no business application needs it. Not offered, now or later.
- **A wall of tiles** seen at an angle is one Transform around the row of tiles, flattened as one
  plane, which is what a wall is.
- **A perspective shared by several siblings** (each turned about its own axis, all seen from one
  eye) is not offered; each Transform's perspective is its own, about its own origin.

### 2.11 The dialog's slide

`Dialog`'s in-scene card becomes `Opacity(Transform(card))`: the fade is the `Opacity`'s, as the
amendment of ADR 071 has it, and the slide is the Transform's translation, `(1 − fade) × 14`
points down, driven by the same fade. The per-frame `moveChild` and the per-frame
`invalidateAccessible` in `paintChildren` go; a drag still moves the card for real, with
`moveChild`, on the drag's events, because a dragged card is where the user put it and the tree
must say so. (2026-10-18, as built: the veil's repaint on each frame of the fade went to a
picture-only damage too, since it asked for a walk on every frame as well; section 9.2.)

## 3. Before 1.0: the two seams

These are changes of shape, not features. They were proposed for 1.0 whether the Transform shipped
in 1.0, in 1.x, or never, each to be approved on its own; the owner approved both, and the
container ships in 1.0 with them (section 4). With no consumers known, every
migration is inside the repository; with consumers after 1.0, each would be a breaking change.

### 3.1 Why they cannot wait, and what the container changes about it

- **The conversions cannot wait.** `sceneToLocalX(float)` returns a local x from a scene x. Under a
  rotation a local x depends on the scene's x and y, so the signature itself is wrong, whatever
  its body. If the point forms are added in 1.x instead, the per-axis forms stay public until 2.0
  (deprecated members are removed only in a major, decision of 2026-09-30) and answer wrongly
  inside any rotated Transform for as long as they exist. A translation and a scale alone would
  survive the per-axis forms, since an axis-aligned transform is separable; nothing else would.
- **The hit test is closer to a choice than it was.** The survey of 2026-10-17 assumed a
  transform on any widget, applied at the widget's own entry; an override that checked its own
  bounds or mapped its children by hand would bypass it. With the owner's container decision the
  transform lies inside `Transform`, which answers its own hit test, and an application container
  that subtracts `child.x()` from a point and calls the child's `hitTest` reaches a Transform
  correctly. What still needs the toolkit to own the entry is narrower and real:
  - **where an ancestor lets the pointer through** (section 2.7): a slid drawer outside its
    parent's box is drawn there by every non-clipping parent, and is hit there only if each
    ancestor's entry asks, which an override that rejects points outside its own box does not;
  - **one preamble**: three overrides copy the visible, enabled and bounds checks word for word;
  - **one place for what a pointer passes by**: the scene filters inert subtrees after the walk
    "because a list, a tree and a table answer their own hit test" (`Scene.outsideInert`), and a
    pointer-transparent flag, if one is ever added, would be one line in the entry.
  So seam A is recommended, and declining it costs the Transform nothing but the drawer outside
  an application container's box (question 2).

### 3.2 Seam A: the hit test has one entry

```java
/** The deepest widget under the point, this one, or null; the point is in this widget's space. */
public final @Nullable Widget<?> hitTest(float localX, float localY)

/** What this widget answers for a point that reached it; the default asks the children, last first. */
protected @Nullable Widget<?> onHitTest(float localX, float localY)

/** Maps a point of this widget's space into {@code child}'s and asks the child. */
protected final @Nullable Widget<?> hitTestChild(Widget<?> child, float localX, float localY)
```

- **`hitTest`** keeps its name, its parameters and their meaning, so every call compiles and
  answers as before. It returns `null` for a widget that is not visible or not enabled; for a
  point outside the box, unless the widget does not clip its children and a Transform away from
  rest under it holds the point (section 2.7; with none, today's rule); and otherwise what
  `onHitTest` answers.
- **`onHitTest`** is the hook. The default asks each child, last to first, through
  `hitTestChild`, and answers the first that is hit, else this widget. An override answers a
  child's answer, itself, or `null` to let the pointer through to what is beneath (as the combo
  box's chips and the range slider's thumbs do today).
- **`hitTestChild`** subtracts the child's position and, in a Transform, applies the inverse of
  what the Transform applies to its child; then calls the child's `hitTest`. It throws
  `IllegalArgumentException` for a widget that is not this widget's child. (2026-10-18, as
  built: the inverse first, then the position, and nothing where a clipping parent cuts the
  child away; section 8.2.)
- **The scene's root and its overlays**, which have no parent, are asked through `hitTest` with
  the scene point converted by `sceneToLocal` (section 3.3).
- **What moves in the repository.** The eight overrides become `onHitTest` and lose their
  preamble: `ListView` (scroll bar first), `Tree` and `Table` (both bars first, then their own
  viewport and twisty rules), `SplitPane` (the divider over the panes), `MenuBar` (its titles,
  `null` past the last), `ScrollBar` (`null` while faded), and the inner classes of `ComboBox`
  (the chip row) and `RangeSlider` (a thumb), which answer `null`. The twelve hand mappings
  become `hitTestChild` or `sceneToLocal`: `ListView` 2, `Tree` 3, `Table` 3, `SplitPane` 1,
  `Scene.hitAt` 2 (the root and the top overlay), and the demo's `site/Motion.hitAt` 1. Two more
  places sum offsets up the parents by hand and become `sceneToLocal` and `localToSceneBounds`:
  `MenuBar.pointOverTitle` and the demo's `IconsScene.openBelow`. The 17 test files that call
  `hitTest` do not change.

### 3.3 Seam B: conversions take and give points

```java
/** A point of this widget's space in the scene's, through every transform above it. */
public final Vec2 localToScene(float x, float y)

/** A scene point in this widget's space; (NaN, NaN) where no point of this widget's plane is there. */
public final Vec2 sceneToLocal(float sceneX, float sceneY)

/** The axis-aligned bounds, in the scene, of a rectangle of this widget's space. */
public final Rect localToSceneBounds(float x, float y, float width, float height)
```

- **Removed:** `sceneToLocalX(float)`, `sceneToLocalY(float)`, `localToSceneX()`,
  `localToSceneY()`. No deprecation cycle is owed before 1.0.
- **`sceneToLocal` answers `NaN`** for a scene point that maps to no point of the widget's plane:
  behind the eye, an edge-on plane, a collapsed scale. `NaN` fails every comparison, so a caller
  that tests `p.x() >= 0 && p.x() < width()` treats it as outside without a branch of its own;
  a nullable return would put a null check on 113 call lines for a case only 3D has.
- **`localToSceneBounds`** clips the projected rectangle at `w > ε` before taking its bounds. It
  is what a popup, a drag, the input method and the accessible walk need, and what replaces the
  idiom `localToSceneX() + width() / 2` with `localToScene(width() / 2, height() / 2)`.
- **`Vec2` is `limn.math.Vec2` and `Rect` is `limn.graphics.Rect`**, both public records. The
  public forms allocate one record per call; the scene's own hot paths (culling per widget per
  pass, damage, the accessible walk, `isShowing`, `showingClip`, `revealInView`) use
  package-internal forms that write into a caller's `float[]`, because an idle window must not
  allocate.
- **`x()` and `y()`** say in their Javadoc that they are the layout position in the parent's
  space, before anything a Transform parent applies.
- **What moves in the repository**, counted at `a6780e72`: 113 call lines in 35 main source files
  outside `Widget` (27 in the toolkit, 7 in the demo, `SceneDriver` in `limn-test`), 440 lines in
  88 test files, and the Javadoc of `MouseEvent`, which tells a widget to convert with the
  per-axis forms. ADRs 032 and 039 name the old methods and stay as written, as records do.
  `SceneDriver.click(widget)` keeps its signature and clicks `localToScene(width / 2, height / 2)`.

### 3.4 What breaks for a 0.9.0 user

Read from `v0.9.0`, where `hitTest` is public and overridable and the four per-axis conversions
are `public final`:

- **A class that overrides `hitTest`** no longer compiles. The fix: rename it `onHitTest`, delete
  the visible, enabled and bounds checks the entry now makes, and replace
  `child.hitTest(x - child.x(), y - child.y())` with `hitTestChild(child, x, y)`.
- **A call of `hitTest`** compiles and answers as before. (2026-10-18: but for three answers,
  all fixes, at `NaN`, on an inert widget and where a parent clips; section 8.2.)
- **A call of any per-axis conversion** no longer compiles. The fix is mechanical:
  `w.sceneToLocalX(sx)` and `w.sceneToLocalY(sy)` become one `w.sceneToLocal(sx, sy)`;
  `w.localToSceneX() + a` and `w.localToSceneY() + b` become `w.localToScene(a, b)`; a box in the
  scene is `w.localToSceneBounds(0, 0, w.width(), w.height())`.
- **`SceneDriver.click(widget)`** and every other `limn-test` signature are unchanged.

### 3.5 Proved before the container exists

The seams are only worth landing if they are right for a rotation, and no rotation can be drawn
before the container. So stage 0 lands, inside the scene package and unpublished, the hook by
which a widget applies a matrix to its children in conversion and hit testing (the one the
Transform will use), and the tests put a test-only container under it that rotates and turns
its child for those two purposes only. Every component that converts a pointer (a slider's drag,
a text field's caret placement, a table's column resize, a tree's twisty, the menu bar's titles)
is driven at a rotated point and must give what it gives at the corresponding upright point.
A call site still summing offsets fails it; each test is checked to fail with its fix backed
out.

### 3.6 One sentence for `Change.Aspect`

`Change.Aspect` should say in its Javadoc that it may gain constants in a minor release (ADR 071
added `OPACITY`; this record adds `TRANSFORM`), so that an application's exhaustive `switch` over
it carries a default from the first day. There is no contract record for 1.0 (the owner's
decision: every 1.x is compatible with 1.0, as the README's "Versions" paragraph says), so the
sentence goes on the type. A sentence, not code.

## 4. Questions for the owner

Answered by the owner on 2026-10-17. Each question keeps its recommendation and cost as put; the answer
follows it. Questions 1, 2 and 5 to 13 took the recommendation. Questions 3 and 4
did not: the container, 2D and 3D, ships before 1.0. Section 4.1 records the question the answer
to question 4 raised.


1. **Seam B, the point conversions, before 1.0?**
   - *Recommended:* yes, as section 3.3.
   - *Cost:* 113 call lines in 35 main files, 440 test lines in 88 files, one Javadoc; mechanical,
     one round. Nothing changes for a widget under no Transform.
   - *The alternative:* add the point forms in 1.x and deprecate the per-axis ones, which then
     answer wrongly under any rotation until 2.0.
   - **Answer:** yes, before 1.0.
2. **Seam A, the hit test's single entry, before 1.0?**
   - *Recommended:* yes, as section 3.2: the toolkit decides where ancestors let the pointer
     through to a moved child, one preamble instead of eight, and a single place for what a
     pointer passes by.
   - *Cost:* eight overrides and twelve hand mappings plus two hand walks, in one round; a 0.9.0
     class that overrides `hitTest` stops compiling (section 3.4).
   - *The alternative:* keep `hitTest` overridable. The Transform still works (it answers its own
     hit test); a moved child outside an application container's box that rejects outside points
     is drawn there and not hit there, and making the entry final after 1.0 is a major's change.
   - **Answer:** yes, before 1.0.
3. **When does the container ship?**
   - *Recommended:* the seams before 1.0; the Transform's stage 1 (translation, scale, origin,
     `slideTo`, `scaleTo`, a flat `flipTo`, the dialog's slide) in the first 1.x minor that wants
     it, stages 2 and 3 after.
   - *Cost of stage 1 now instead:* a medium round: the container, old-and-new damage, the
     Transform set for passes and hit tests, conversions through it, announcements, the dialog;
     no new rendering risk, one measurement (glyphs under a scale animation).
   - *The alternative:* stage 1 before 1.0 too, which fixes the dialog's per-frame layout and
     accessible walk now.
   - **Answer:** **stage 1 before 1.0**, against the recommendation; with question 4, stages 1, 2
     and 3 all before 1.0.
4. **Does the 3D tier ship with the 2D one?**
   - *Recommended:* no, later. Value for business applications is low (onboarding cards, a
     showcase); cost is a round the size of ADR 071: the projected composite, the clip at the
     composite, the near plane, the glass rule, the oblique-text decision, inverse projection in
     the hit test, and the guests' measurements.
   - *The alternative:* both tiers in one release, one round of measurements for both.
   - **Answer:** **with the 2D tier**, against the recommendation, asking first whether 2D and 3D
     are better one container or two (section 4.1).
5. **A rotation drawn straight, or through a layer?**
   - *Recommended:* through a layer in local space (section 2.4): every clip inside exact,
     culling working, one path shared with 3D.
   - *Cost:* a picture per rotated Transform per frame it repaints (the table of section 2.4),
     and text at an angle bilinear from the picture, which is about as soft as drawn straight.
   - *The alternative:* drawn straight: crisp shapes and no picture, but every clip inside
     becomes its bounding box (a rotated scroll view shows its content in the corners) and
     nothing culls, unless the device clip and the canvas shader learn an oriented clip, a
     per-fragment cost on the software renderer.
   - **Answer:** through a layer, as recommended.
6. **Does a translation's x follow the layout direction?**
   - *Recommended:* yes: x runs toward the end, as the origin's fraction is measured from the
     start (section 2.2), so a drawer that slides in from the end is one line in both directions.
   - *Cost:* an application that means "to the right" in a right-to-left window negates x;
     `Padding`'s insets, by contrast, are physical.
   - *The alternative:* a physical x, and the direction left to `slideTo`'s caller.
   - **Answer:** yes, as recommended.
7. **Angles in degrees?**
   - *Recommended:* degrees on the Transform, as every comparable toolkit's property is; radians
     stay on `Canvas.rotate` and `Quat`, which are math.
   - *Cost:* two units in the API, each where its readers expect it.
   - **Answer:** degrees, as recommended.
8. **Under less motion, does `flipTo` swap at once or dip through transparency?**
   - *Recommended:* at once: `atEdge` and `onArrive` run immediately, as every other motion
     arrives at once.
   - *Cost:* the content changes with no transition at all.
   - *The alternative:* the composite's alpha goes to 0 and back over the flip's time, swapping
     at the bottom: a transition with no motion, at the cost of a fade the Transform owns beside
     the `Opacity` container's.
   - **Answer:** at once, as recommended.
9. **Do the accessible bounds follow an animation's frames?**
   - *Recommended:* no: the tree is walked on a set and at an animation's arrival (section 2.3),
     as ADR 071 avoided walking it per fade frame.
   - *Cost:* during a slide of a fraction of a second a reader's highlight stays where the child
     was, then jumps to where it is.
   - *The alternative:* a walk per frame while a reader listens, as the dialog's slide does today.
   - **Answer:** no, as recommended: on a set and at arrival.
10. **What does a glass inside a turned picture read?**
    - *Recommended:* inside a 2D picture, what it would read if the picture were opaque, warped
      through the inverse affine, as ADR 071's decision 5; inside a 3D picture, the subtree's own
      content only (section 2.9).
    - *Cost:* one more quad per glass per open picture for 2D; a frosted card that turns in 3D
      shows its own tint and no window through it for the length of the turn.
    - *The alternative:* a backdrop root in both, the simplest; or the window warped in both.
    - **Answer:** as recommended.
11. **Mipmaps for text at an oblique 3D angle?**
    - *Recommended:* not in the first 3D build: bilinear only, and section 7 measures
      `glGenerateMipmap` per frame on the guests; decide on the numbers.
    - *Cost:* a page turned far from the viewer aliases at rest and shimmers while it turns.
    - *The alternative:* mipmaps made while the picture is minified past two to one anywhere,
      at a cost per frame unknown on the software renderer.
    - **Answer:** not in the first 3D build, as recommended; the measurement of section 7 decides
      after.
12. **Fold an adjacent `Opacity` into a turned Transform's composite?**
    - *Recommended:* not in the first build; two pictures cost ADR 071's NESTED rows (+0.03 ms on
      this GPU, +1.1 to 1.8 ms on the software renderer), and the fold can be added later
      without any API.
    - *Cost of folding:* a special case in the paint path for the two containers.
    - **Answer:** not in the first build, as recommended.
13. **The name `Transform`?**
    - *Recommended:* `Transform`, a noun like `Padding` and `Opacity`.
    - *Cost:* it reads like a matrix type at first sight, beside `Transform3D` in `limn.math`.
    - *The alternative:* `Transformed`.
    - **Answer:** `Transform`, as recommended.

### 4.1 One container or two

The owner's answer to question 4 asked whether 2D and 3D are better as one container, or as a 2D
container and a 3D one, perhaps one extending the other. Recommended and decided: **one final
`Transform`**, whose drawing path follows the matrix in force (section 2.4), because:

- **Inheritance would contradict the toolkit's final widgets** (the API audit of 2026-09-22 found
  subclassable widgets a high). A `sealed` 2D class permitting only the 3D one is possible in
  Java 17, but then the 3D container does everything the 2D one does, applications reach for it
  every time, and the split adds a choice without a guarantee.
- **Without inheritance**, a card that slides and then turns needs two containers nested, and the
  order between them becomes the application's puzzle; the fixed order of section 2.2 already
  answers it inside one.
- **What differs between the tiers follows the matrix, not the type.** The backface, the near
  plane, the backdrop root and the projected composite apply while the matrix has a perspective
  or a turn out of the plane; a 3D container at rest, or turned by 0 degrees, must cost what a 2D
  one costs, so a type would promise nothing the matrix does not already decide.
- **The name is taken**: `limn.math.Transform3D` exists.
- What a split would buy is a shorter 2D API; the 3D tier adds four setters
  (`setRotation3D`, `setPerspective`, `setBackfaceVisible`, `setMatrix`), which the Javadoc groups
  apart.

## 5. Not in this record

- **A layout transform** (section 2.1).
- **A shared 3D space between siblings**, and a perspective shared by siblings (section 2.10).
- **A transform method on `Widget`**: the owner's decision is a container.
- **A pointer-transparent flag, a public clip toggle, a matrix getter on `Canvas`.** The survey
  lists them among the widget-level gaps; each is its own decision, and only the canvas getter
  cannot be added later as a default method. None is needed by the Transform.
- **Using a widget's picture as a texture inside a `Viewport3D`.** The 3D stack draws content
  inside a widget; it does not take widgets in.
- **Group effects on the same layer** (blur, colour matrix, mask), as ADR 071 §4 left them.

## 6. Stages, and what each must prove

- **Stage 0, before 1.0: the seams.** Section 3, with the internal hook and the test-only
  container of section 3.5. Tests: each migrated component driven at rotated and turned points
  gives what it gives upright; the conversions round-trip (`sceneToLocal(localToScene(p)) = p`)
  under translation, scale, rotation and perspective, and answer `NaN` behind the eye;
  `localToSceneBounds` against the four corners by hand; the entry's preamble (hidden, disabled,
  outside) for every former override; `hitTestChild` refusing a stranger; the whole suite
  unchanged, since nothing is transformed; idle frames still allocate nothing (`FullFrameAllocationTest`).
- **Stage 1, before 1.0: translation, scale, origin.** The container; `slideTo`, `scaleTo`, a flat
  `flipTo`; `Change.Aspect.TRANSFORM`; the scene's set of Transforms away from rest; old and new
  damage; the dialog's slide. Tests: at rest the frame and its draws are today's (counted);
  damage is old and new projected bounds and no layout (`DamageContractTest`'s rules); a partial
  frame through a sliding Transform equals the whole frame, pixel for pixel, including a slide
  past an unclipped parent's box; hit testing and readers' bounds at rest and after arrival;
  announcements (a set every change, an animation two, a superseded one its end and the new one
  its start, less motion one and `onArrive` at once); right-to-left origin, translation and
  `Flip.FORWARD`; the dialog with no per-frame layout and no per-frame accessible walk; the
  work budget (ADR 062) unchanged for a still form.
- **Stage 2, before 1.0: rotation and the general 2D matrix.** The layer in local space, re-based; the
  composite with `vec4` positions, the scissor and the rounded-clip variant; the cleared ring;
  `rotateTo`; the warped glass. Tests on a real device: a rotated scroll view shows nothing in its
  corners; a rotated text field's caret and selection land where the pointer is; a partial pass
  through a rotated Transform equals the whole frame; edges antialiased within a pixel; a glass in
  a slightly rotated card equal, within a few steps, to the same glass upright under the inverse
  rotation; the picture freed the frame after a rotation ends.
- **Stage 3, before 1.0: 3D.** `setRotation3D`, `setMatrix`, `setPerspective`, `setBackfaceVisible`, a
  `flipTo` with perspective; the near plane; the backdrop root; the oblique-text decision. Tests:
  the flipped card's projected bounds (the 1.33 case) damaged and repainted; hit testing by
  inverse projection, a miss behind the eye and on a hidden backface; the handedness of the three
  axes pinned; a turn of exactly 90 degrees draws nothing and takes nothing.

## 7. What must be measured before Accepted

By the method of ADR 071 §0: interleaved modes, rotated per frame, medians of 300 frames after 60
on a GPU and of 40 after 5 on the software renderer, two rounds, a repeat of the opaque frame as
the floor, on this Mac's GPU and software renderer and on the Windows, Fedora and macOS guests.

- **The projected composite** against ADR 071's flat one, for a card and for a page, at 0, 30
  and 75 degrees: the perspective-correct interpolation per pixel on the software renderer is the
  unknown.
- **The re-basing** a transform's picture needs and an opacity's does not: a rotated card's frame
  against the same card in an opacity group.
- **The rounded enclosing clip in the composite program** on the software renderer, against the
  scissor alone.

  **Both measured on this Mac, 2026-10-18** (the probe `TransformLayerProbe` in the backend's
  tests, run by setting `LIMN_PROBE_TRANSFORM_LAYER`; the guests' run still owed): ADR 071 section
  0's popup, 300 by 400 points with two shadows, a bordered panel and sixteen rows, over a striped
  page in a 1280 × 800 window at 2x, each frame repainting the card, the modes interleaved and
  rotated, each on a canvas of its own; partial frames (the card's own damage, which a turned card
  makes larger by its turned bounds) and whole frames (the same window for every mode, only the
  picture differing). OPAQUE and OPAQUE' the card drawn straight, the floor; GROUP the card in an
  `Opacity` at one half; TURNED and TURNED_30 the card in a `Transform` at 3 and 30 degrees;
  RECT_CLIP and ROUND_CLIP the card at 3 degrees inside a panel that clips it square or with
  16-point corners. Round 1 / round 2, medians, milliseconds.

  On Apple's software renderer (4.1 APPLE-24.0.2), 40 frames after 5, one number per frame, with
  the composite's computed clip confined to the picture's edges (section 10.2):

  | Frame | OPAQUE | OPAQUE' | GROUP | TURNED | TURNED_30 | RECT_CLIP | ROUND_CLIP |
  |---|---|---|---|---|---|---|---|
  | partial | 163.7 / 163.0 | 164.1 / 163.4 | 166.9 / 166.4 | 173.6 / 172.5 | 198.4 / 198.2 | 154.4 / 154.1 | 155.3 / 154.5 |
  | whole | 343.5 / 342.8 | 343.5 / 343.1 | 343.2 / 343.1 | 346.3 / 346.0 | 345.7 / 345.7 | 338.1 / 337.6 | 339.4 / 337.9 |

  Before that, with the variant program over the whole picture, the same probe gave RECT_CLIP
  174.4 / 174.7 and ROUND_CLIP 187.3 / 187.7 in partial frames, 382.1 / 382.4 and 395.2 / 395.3 in
  whole ones, against OPAQUE 185.5 / 185.3 and 387.8 / 388.4 (the machine ran some 12% slower in
  that run; each run is read only against itself).

  On this Mac's GPU (Apple M5 Max, OpenGL 4.1 over Metal), 300 frames after 60, CPU / GPU / frame:

  | Frame | Mode | Round 1 | Round 2 |
  |---|---|---|---|
  | partial | OPAQUE | 0.149 / 0.216 / 0.561 | 0.083 / 0.022 / 0.228 |
  | | OPAQUE' | 0.148 / 0.217 / 0.559 | 0.078 / 0.021 / 0.228 |
  | | GROUP | 0.174 / 0.290 / 0.584 | 0.096 / 0.067 / 0.241 |
  | | TURNED | 0.172 / 0.300 / 0.594 | 0.101 / 0.067 / 0.247 |
  | | TURNED_30 | 0.177 / 0.351 / 0.656 | 0.105 / 0.067 / 0.251 |
  | | RECT_CLIP | 0.170 / 0.264 / 0.552 | 0.100 / 0.065 / 0.248 |
  | | ROUND_CLIP | 0.178 / 0.298 / 0.599 | 0.104 / 0.064 / 0.251 |
  | whole | OPAQUE | 0.107 / 0.088 / 0.338 | 0.106 / 0.086 / 0.339 |
  | | OPAQUE' | 0.103 / 0.087 / 0.334 | 0.100 / 0.086 / 0.333 |
  | | GROUP | 0.121 / 0.147 / 0.333 | 0.119 / 0.145 / 0.329 |
  | | TURNED | 0.126 / 0.148 / 0.339 | 0.123 / 0.143 / 0.334 |
  | | TURNED_30 | 0.125 / 0.145 / 0.337 | 0.124 / 0.145 / 0.335 |
  | | RECT_CLIP | 0.126 / 0.143 / 0.336 | 0.124 / 0.142 / 0.334 |
  | | ROUND_CLIP | 0.128 / 0.151 / 0.339 | 0.126 / 0.141 / 0.336 |

  What the numbers say:

  - **The re-basing costs a turned card's whole frame 3 ms on the software renderer** over the same
    card in an opacity group (+3.0 / +2.9, under 1% of the frame; floor 0.0 / 0.3; +3.0 / +3.2 in
    the run before), and nothing a GPU
    shows (+0.005 / +0.004 ms of CPU and +0.001 / −0.002 ms of GPU, inside the floor). It is not
    the state being moved, which costs nothing per draw, but the picture drawn at the card's
    magnification and laid back filtered: the same at 3 and at 30 degrees (345.7 against 346.3).
  - **A turned card's partial frame costs more than its picture**: +6.7 / +6.1 ms over the group
    at 3 degrees and +31.5 / +31.8 at 30 on the software renderer, because the pass is the turned
    bounds of the card and its shadow, a tenth larger than the upright card's at 3 degrees and
    nearly twice as large at 30, and everything under it is repainted. On the GPU the difference is a few hundredths of
    a millisecond, within the clock's movement between rounds (round 1's clocks were low
    throughout, every mode alike).
  - **The rounded enclosing clip costs 0.4 to 1.3 ms of a frame on the software renderer** over
    the scissor alone (floor 0.0 to 0.4), once the variant runs only on the picture's edge strips;
    run over the whole picture as section 2.4 first had it, it cost 13 ms, 7% of the frame, which
    is why the build splits it. On the GPU it is within the floor.
  - Neither reopens a decision: a turned card costs what a fading one costs plus a few percent of a
    software frame, as section 2.4's table estimated.
- **Glyphs under a scale animation:** rasterizations and atlas pages per frame of a `scaleTo` of a
  text-heavy card, and the frame time it costs. **Measured on this Mac, 2026-10-18** (the probe
  `TransformGlyphProbe` in the backend's tests, run by setting `LIMN_PROBE_TRANSFORM_GLYPHS`; the
  guests' run still owed): a card of 26 lines of text in four styles, 432 points wide, over a
  striped page in a 1280 × 800 window at 2x, each frame repainting the card, the modes on canvases
  of their own; a scale from 0.95 to 1 over twelve frames, eased as a motion is, run on a fresh
  canvas each time (COLD, the first time an application runs it) and repeated on a warm one
  (WARM), against the card at rest (STILL, STILL' the floor) and held at 0.975 (SCALED). Round 1 /
  round 2, medians, milliseconds:

  | Mode | CPU | GPU | Frame | Glyphs rasterized a frame | Atlas pages |
  |---|---|---|---|---|---|
  | STILL | 0.185 / 0.190 | 0.065 / 0.064 | 0.383 / 0.385 | 0 | 1 |
  | STILL' | 0.184 / 0.190 | 0.067 / 0.063 | 0.383 / 0.386 | 0 | 1 |
  | SCALED | 0.185 / 0.189 | 0.062 / 0.060 | 0.378 / 0.381 | 0 | 1 |
  | WARM | 0.187 / 0.191 | 0.062 / 0.062 | 0.382 / 0.382 | 0 | 1 |
  | COLD | 0.434 / 0.437 | 0.064 / 0.062 | 0.629 / 0.631 | 78.2 (at most 86) | 1 |

  That is on this Mac's GPU (Apple M5 Max, OpenGL 4.1 over Metal). On Apple's software renderer,
  40 frames after 5, the frame is the card's area and nothing else: STILL 325.41 / 325.43 ms,
  STILL' 325.39 / 325.34, SCALED 308.28 / 308.22 and WARM 312.87 / 313.19 (smaller by the area the
  scale takes away), COLD 313.52 / 313.23 with the same 79 glyphs a frame, +0.66 / +0.03 ms over
  WARM, inside the floor's reach. What the numbers say: the first run of a scale over text
  rasterizes every glyph on the card at every eighth of a pixel it passes, about +0.25 ms of CPU a
  frame on this Mac, some 3 ms over the twelve frames, once; the GPU's time does not move, the run
  fits in the one atlas page the card already uses (a 1024 × 1024 single-channel page, 1 MiB), and
  a repeat of the same motion, or a scale held, costs what the card at rest costs. Nothing here asks
  for a cap on the sizes rasterized or for a picture under a scale; the atlas's own eviction past
  four pages covers a continuous zoom.
- **`glGenerateMipmap` per frame** on the three guests, for question 11.
- **A partial frame through a moved child** against a whole frame (stage 1's pixel test, run on
  the guests), since the scene's set of Transforms changes what a pass paints. (2026-10-18: green
  on this Mac's GPU, `TransformPassSeamTest`; the guests' run owed.)

## 8. Stage 0, built 2026-10-18

Built on `claude/adr-072-stage0` from `73f35415`, in six commits before this section: seam B,
seam A, the internal hook with the test-only container, the sentence on `Change.Aspect`, the clip
in the hit test, and one allocation taken out of `localToScene`. Nothing draws a transform yet;
the container is stage 1.

### 8.1 What was built

- **Seam B (section 3.3)**, as written: `Widget.localToScene(float, float)` and
  `sceneToLocal(float, float)` return a `limn.math.Vec2`, `localToSceneBounds(float, float, float,
  float)` a `limn.graphics.Rect`, all `public final`, and the four per-axis forms are gone.
  Package-private forms write into a caller's `float[]` and allocate nothing; `Scene.culledFromPaint`,
  `Scene.hitAt` and `AccessibleWalk` use them. Every caller moved: 35 main files besides `Widget`
  (the toolkit, the demo, `SceneDriver.click`, whose signature is unchanged) and 477 calls in 89
  test files, the tests mechanically to `localToScene(0, 0).x()`, the main sources by hand to the
  point that was meant (a popup's anchor is `localToSceneBounds` of the anchor's rectangle; a text
  field's caret damage and keyboard menu use the caret's own box). `MouseEvent`'s Javadoc teaches
  the point form; `x()` and `y()` say they are the layout position.
- **Seam A (section 3.2)**, as written: `hitTest` is `public final`, `onHitTest` the protected
  hook with the default of section 3.2, `hitTestChild` the protected final helper that refuses a
  stranger with `IllegalArgumentException`. The eight overrides became `onHitTest`; the twelve hand
  mappings and the two hand walks moved (ListView 2, Tree 3, Table 3, SplitPane 1 and the
  scene's 2 to `hitTestChild` or the package `sceneToLocal`, the demo's `Motion.hitAt` to the
  public one; `MenuBar.pointOverTitle` and `IconsScene.openBelow` to `sceneToLocal` and
  `localToSceneBounds`).
- **The hook and the test container (section 3.5).** `limn.scene.internal.TransformAccess`
  (`setChildMatrix(Widget, Mat4)`, `childMatrix(Widget)`), installed by `Widget`'s static
  initializer as `OpacityAccess` is; a package-private `ChildTransform` holds the plane's
  homography and its inverse; the scene keeps the widgets with a matrix in a list. The test-only
  container is `limn.scene.Turned` in the toolkit's tests, with `limn.math.PerspectiveForTest`.
- **Section 3.6:** `Change.Aspect`'s Javadoc says a minor release may add a constant, as
  `OPACITY` was added, so a `switch` naming every aspect keeps a `default`.
- **The clip in the hit test** (the workarounds hunt of 2026-10-17, its second item): see 8.2.

### 8.2 What the build settles, and where it departs from the text above

- **2026-10-18. The matrix is the widget's, in its own space.** The hook takes the matrix a widget
  draws its children through, applied to a point of the widget's own space with the child's
  position already added: a child point `q` is drawn at `M · (child.x + q)`, as a paint that
  concatenates `M` and then translates by `child.x()` draws it. So `hitTestChild` applies the
  inverse first and then subtracts the child's position, the reverse of section 3.2's wording;
  for the `Transform`, whose child sits at its origin, the two orders agree. The children lie in
  the plane `z = 0`, so the matrix's x, y and w rows and columns make a 3 × 3 homography of the
  plane, held with its adjugate in double precision when the matrix is set.
- **2026-10-18. What `NaN` means, in numbers.** A point is behind the eye when its `w` is not above
  `1e-6`. A plane is edge-on or collapsed at a point when less than a millionth of its area is left
  there (`det / w³`): a quarter turn leaves single precision a cosine of about `4e-8`, not 0, so
  exact zero is not the test. `localToSceneBounds` of a rectangle wholly behind the eye answers
  `Rect(NaN, NaN, 0, 0)`, which section 3.3 left open; one partly behind keeps its front, cut at
  `w = 1e-6`, whose edge projects very far, which is the true answer near the eye.
- **2026-10-18. The pointer passes through boxes in stage 0, not stage 1.** Section 3.2's rule for
  a widget that does not clip (a point outside its box goes on to a descendant drawn moved there)
  is built now, because the test container needs it to prove a slid child is hit where it is
  drawn. The scene's list is of widgets with a matrix (stage 1's set of Transforms away from rest
  is the same list), and the test is against the bounds of each such widget's visible children's
  boxes through its matrix, carried up to the asking widget; outside its box a widget answers the
  descendant or `null`, never itself. Boxes, not outsets: a pointer hits a box.
- **2026-10-18. Inert is in the entry, and lands on what is beneath.** `Scene.outsideInert` is
  gone, as section 3.1 foresaw. A press over an inert subtree now lands on what is beneath it, a
  sibling under it where there is one and its parent where there is none; the filter after the
  walk always gave the parent. `SceneAccess.setInert`'s Javadoc says so.
- **2026-10-18. Section 3.4 is not quite right that every call of `hitTest` "answers as
  before".** Three answers change, all fixes: a point of `NaN` is outside (the copied preambles
  and the base class let it through, and a list then answered itself); an inert widget answers
  `null` itself; and a clipped child is not hit where its parent cuts it away (below).
- **2026-10-18. The clip is honoured in `hitTestChild`.** A widget that `clipsChildren()` answers
  `null` for a child at a point outside the clip rectangle it gives that child
  (`clipX`/`clipY`/`clipWidth`/`clipHeight`), in its own space before any matrix, as the paint cuts
  it. Content under a scroll pane's reserved gutter no longer takes the press meant for the
  gutter, and `Table` lost its hand test of the rows' band, its clip accessors already answering
  the rows' viewport. `Tree` keeps its `inViewport` test: it answers the default clip, its box,
  for its cells, and giving it the accessors changes what `isShowing` and the damage clamp say of
  its cells, which is not this stage's.
- **2026-10-18. What follows the matrix in stage 0, and what does not.** The conversions, the hit
  test, the accessible tree's published bounds (`AccessibleWalk` takes `localToSceneBounds`) and
  `Scene.culledFromPaint` (the box and outset through `localToSceneBounds`) follow it. The paint,
  `invalidate()`'s damage, `isShowing`, `showingClip`, `revealInView` and `paintedBounds` still sum
  layout offsets, which is right while nothing paints moved; stage 1 routes each through the
  matrix (sections 2.6 and 2.8), and a partial pass under the test container would cull by where
  the matrix would draw while the paint draws upright, which no test does.
- **2026-10-18. The public forms' cost.** `localToScene` allocates the one record it returns;
  `sceneToLocal` and `localToSceneBounds` a two- or four-float array besides, which the walk from
  the root and the clipped bounds need. Only `localToScene` is asked from a paint (the date
  picker's button, to put its glyph on the pixel grid).
- **For stage 3:** `Mat4` has no public factory for a perspective seen from a distance along z
  (`Mat4.perspective` is a camera's projection), and its array constructor is package-private; the
  tests build one in `limn.math`. `setPerspective` needs one, or the container composes its matrix
  in `limn.math`'s package. The hook has no backface flag yet; section 2.7's miss on a hidden
  backface is stage 3's, through the same hook.

### 8.3 Section 6's stage 0 tests, and where they are

- **Each migrated component at rotated and turned points** (`TurnedPointerTest`): a slider's drag,
  a text field's caret placement, a table's column resize, a tree's triangle, the menu bar's titles
  (a press on one, then the pointer onto another through the dropdown drawn in the scene, which is
  `MenuBar.pointOverTitle`), a list's rows, a split pane's divider and a range slider's thumbs,
  upright, rotated 33 degrees and turned 38 degrees about the vertical axis with the eye 1200
  points away, the pointer put where the matrix draws the upright point, worked out from
  `Mat4.transform` and the layout's offsets. With the slider, the range slider and
  `pointOverTitle` summing offsets again, those three fail; with `sceneToLocal` ignoring the matrix,
  six of the eight fail.
- **The round trip, `NaN` and the bounds** (`PointConversionTest`): translation, scale, two
  rotations, a turn in perspective, a turn after a rotation and two containers nested; a point
  behind the eye `NaN` both ways; edge-on and scaled to 0 on either axis; the four corners by hand;
  a rectangle wholly behind the eye; a child hit where it is drawn and not where it was; a slid
  child hit past two boxes unless its parent clips; the scene's list kept on rest and detach; and
  the allocations (one record from `localToScene`, nothing from the package forms).
- **The entry's preamble for every former override, and `hitTestChild` refusing a stranger**
  (`HitTestEntryTest`): the eight hidden, disabled, inert, outside on each side and at `NaN`; an
  application widget asked only past the preamble; a stranger refused; a press over an inert widget
  landing on the one beneath; the scroll pane's gutter and the table's header not hitting the
  content cut away there.
- **Proved both ways**, each piece backed out and the tests run: with the old entry, the
  preamble cases fail at `NaN` for the list, the tree, the table, the split pane and the scroll
  bar and when inert for all six that answer, and the inert press lands on the stack; without the
  inverse in `hitTestChild`, the tree's triangle and three hit cases; without the matrix in
  `sceneToLocal`, six components and four conversion cases; without the rule for moved children,
  both hit cases past a box; with a point mapped in place of the bounds, both bounds cases;
  without the clip in `hitTestChild`, the gutter and the header; with the array back in
  `localToScene`, the allocation case (48 bytes).
- **The whole suite unchanged, `FullFrameAllocationTest` and `WorkBudgetTest` green**:
  `./gradlew check --continue` at `a138f933`, 7306 tests, 90 skipped, none failed.

## 9. Stage 1, built 2026-10-18

Built on `claude/adr-072-stage1` from `8be2b86c` (stage 0 and its CHANGELOG entries), in seven
commits before this section: the paths stage 0 left summing offsets routed through the matrix, the
container and its motions, the pixel test, the dialog's slide, the matrix composed in place with the
two budgets, the Showcase and the guide, and the glyph probe. Rotation, the general matrix, the
layer and 3D are stages 2 and 3.

### 9.1 What was built

- **`limn.scene.layout.Transform`** (sections 2.1 and 2.2), final, one child: `setTranslation(x,
  y)`, `setScale(s)`, `setScale(sx, sy)`, `setOrigin(fractionX, fractionY)`, `reset()`, and
  `translationX()`, `translationY()`, `scaleX()`, `scaleY()`, `originX()`, `originY()`; fluent, UI
  thread only, refused inside a paint, `NaN` and the infinities refused. It measures, lays out and
  aligns on a baseline as its child, has no node of its own, does not clip, and its `onHitTest`
  answers the child through `hitTestChild` or nothing, never itself.
- **The motions** (section 2.3): `slideTo(x, y)` and `slideTo(x, y, seconds, onArrive)`,
  `scaleTo(s)` and `scaleTo(sx, sy, seconds, onArrive)`, `flipTo(direction, atEdge)` and
  `flipTo(direction, seconds, atEdge, onArrive)` with the nested enum `Transform.Flip` (`FORWARD`,
  `BACKWARD`, `UP`, `DOWN`); one ticker per container on the scene's wall-time ticker, eased by the
  fade's smoothstep, superseded by a newer motion of the same value or a set of it (whose
  `onArrive` then never runs), settled at the target as the container leaves its scene, and
  straight to the target under less motion, for no time, for the value it has, or with no scene or
  window. `Change.Aspect.TRANSFORM` (with its sentence on the type) is announced on every set that
  changes a value and twice per motion; the accessible tree is walked on a set and at an arrival
  and on no frame between.
- **The machinery behind it** stays in `limn.scene`, as the opacity's does: a package-private
  `TransformGroup` holds the values, the motions and the ticker, composes the matrix and is reached
  through `limn.scene.internal.TransformAccess`, which gained `makeContainer` and a `Values`
  interface the group implements; a widget nobody moves pays one null reference.
- **Every path section 8.2 left summing offsets** follows the matrix: the paint (`Widget.paintWidget`
  through `Canvas.translate` and `Canvas.scale` for a matrix that keeps the axes), the damage clamp,
  `isShowing`, `showingClip`, `revealInView`, `paintedBounds`, the canvas-clip cull and the scene's
  cull, both of which let a pass through an ancestor's box to a widget drawn moved into it (section
  2.6), with the stage 0 list as the scene's set. The accessible walk places synthetic children
  where their owner is drawn, through two step-only methods on `Accessibility` (`syntheticBoxAt`,
  `placeSyntheticAt`).
- **The dialog's slide** (section 2.11): `Opacity(Transform(card))`, the slide a `slideTo` in step
  with the fade, the per-frame `moveChild` and `invalidateAccessible` gone, a drag placing the card
  with `moveChild` on its events.
- **The Showcase and the guide:** the Motion page's "Two-sided card", a customer card that turns
  over to its reminder; the Animations guide's "Moving part of a window", from
  `AnimationExample`'s `guide:animation-transform` region (a drawer from the end edge and a card
  that flips), held by `AnimationExampleTest`.

### 9.2 What the build settles, and where it departs from the text above

- **2026-10-18. The paint is `Widget`'s, not the container's.** Section 2.4 has the Transform's
  `paintChildren` apply the translation and the scale. `Widget.paintWidget` applies the matrix it
  holds instead, for any widget whose matrix keeps the plane's axes, around `paintChildren`; so the
  paint, the hit test, the conversions, the damage and the cull read one matrix, and the container
  has no paint code. A matrix that turns the axes is still drawn upright, which only the tests'
  turned container sets; stage 2's layer replaces that branch.
- **2026-10-18. The flip's place in the product, its easing, and a flip superseded.** The matrix is
  `O · translation · flip · scale · O⁻¹` now; the flip's turn takes its place between the perspective
  and `rotation3D` when stage 3 adds them, so it turns the child as it appears. Its side is resolved
  physically as it starts: the end edge comes toward the reader (its z positive) for `FORWARD`, the
  start edge for `BACKWARD`, the bottom edge for `UP` and the top edge for `DOWN`, which
  `TransformTest` pins in both directions. One smoothstep over the whole turn, from flat to the
  edge on its side and from the other side back to flat, puts the fastest frames edge-on, where the
  swap is not seen. A newer `flipTo` turns on from wherever the one it supersedes stopped and runs
  its own `atEdge`; `reset()` brings a half-turned child back flat.
- **2026-10-18. A superseded motion still announces its end under `reset()`.** Section 2.3 says
  `reset()` announces once; it does, besides the end of each motion it supersedes, which is the rule
  of a set superseding a motion and is what a watcher counting starts and ends needs.
- **2026-10-18. Seconds must be finite.** A motion refuses an infinite duration, which would never
  arrive; `Opacity.fadeTo` accepts one, and the two should agree before 1.0 (the owner's to settle,
  a one-line change either way).
- **2026-10-18. The outset is computed on asking.** Section 2.6 holds it from the last change; the
  child's own outset can change without the Transform hearing (a theme's focus ring), and four
  corners through the matrix are constant-time, so it is computed each time the hook is asked. A
  transform deeper down that moves its child past its own parent is not in this outset; the scene's
  set carries a pass to it.
- **2026-10-18. A change damages the child's painted bounds, not its box and outset.** The bounds
  `paintedBounds` gives, through the old matrix and the new one, so a descendant laid past the
  child's box, which the child's outset does not always declare, is repainted where it was and
  where it is, as an opacity's change does.
- **2026-10-18. An `Opacity` round a moving Transform makes a layer larger than the picture on the
  side the child left.** Its layer is the union of the Transform's box grown on every side by its
  overhang, which is symmetric because an outset is one number, and the exact rectangle the child is
  drawn in; a drawer slid 300 points to the end makes a layer 300 points wider toward the start
  too. Correct, and a cost only while both are in force; stage 2's layer in local space is where an
  exact bound would come from.
- **2026-10-18. The matrix is composed in place.** The group composes into a `MutMat4` it keeps and
  loads it into the `ChildTransform` the widget keeps (its fields no longer final), so a frame of a
  motion allocates no matrix: 136 bytes a frame for a sliding form against a fading one's 64, the
  three vectors `MutMat4.setTrs` takes. The `Mat4` the internal hook's getter answers for a
  container is a snapshot taken on asking.
- **2026-10-18. The dialog's veil repaints as a picture too.** Section 2.11 names the per-frame
  `moveChild` and `invalidateAccessible`; the veil's repaint on each frame of the fade asked for a
  walk as well (`overlay.invalidate()`), and goes through a picture-only damage now,
  `SceneAccess.repaintPicture`, the path an opacity's fade already took. The slide keeps pace with
  the fade by a `slideTo` over the same time on the same wall-time frames from where it is, which
  holds it at `(1 − fade) × 14` on every frame, the fade out from wherever the fade in got to
  included.
- **2026-10-18. Popups.** A popup drawn in the scene whose anchor lies under a Transform is laid
  out again when the Transform is set or arrives (`Scene.anchorsMoved`), as section 2.8 has it; a
  popup in a window of its own is placed where it opened and is not placed again, since the scene
  knows nothing of the window's placement (section 9.4).
- **2026-10-18. Text ranges.** Section 2.8 publishes a text range's projected bounds. No bridge
  reads a text node's caret rectangle, the one range-like box the tree carries (in the node's own
  coordinates), so nothing was routed there; the input method's caret rectangle was already
  projected in stage 0 (`TextField.caretRect` through `localToSceneBounds`).

### 9.3 Section 6's stage 1 tests, and where they are

- **At rest the frame and its draws are today's, counted:** `TransformTest.atRestTheFrameAndItsDrawsAreTodays`
  (the same fills at the same places with six rows wrapped, and after a move set back by
  `reset()`, with no matrix and nothing in the scene's set) and
  `WorkBudgetTest.aStillFormCostsTheSameInTransformsAtRestOrMoved` (the same commands at ten and
  forty rows, at rest and moved and scaled).
- **Damage is old and new projected bounds and no layout:**
  `TransformTest.aSetDamagesWhereTheChildWasAndWhereItIsAndLaysNothingOut` (the two rectangles,
  the scaled one, no whole frame, no layout pass, nothing for the value it has) and
  `eachFrameOfASlideDamagesWhereItWasAndWhereItIsAndNoMore`.
- **A partial frame equals the whole, past an unclipped parent's box:** `TransformPassSeamTest` on
  the GPU (a slide step, a slide past the parent's box, something repainting where the slid card
  lies, a scale step, a fading slide in either order), and
  `TransformTest.aPassReachesAChildSlidPastAnUnclippedParentsBox`.
- **Hit testing and readers' bounds at rest and after arrival:**
  `TransformTest.thePointerFindsTheChildWhereItIsDrawnAtRestAndAfterArrival` and
  `aReaderIsToldWhereTheChildIsOnASetAndAtArrivalAndNotOnTheFramesBetween` (a synthetic child
  scaled with its owner, the published bounds and the walk count unchanged on the frames between).
- **Announcements:** `aSetAnnouncesEveryChangeResetOnceAndTheSameValueNothing`,
  `aMotionAnnouncesItsStartAndItsEndAndNoFrameBetween`,
  `aNewerMotionOrASetSupersedesOneInFlightAndMotionsOfDifferentValuesRunTogether`,
  `underLessMotionAMotionGoesStraightThereAsOneSetAndAFlipSwapsAtOnce`.
- **Right-to-left origin, translation and `Flip.FORWARD`:**
  `inARightToLeftSubtreeTheTranslationAndTheOriginFollowTheDirection` and
  `aFlipTurnsTheChildFlatToEdgeOnAndBackAndSwapsAtTheEdge` (each of the four directions in both
  layout directions: the named edge toward the reader, drawn narrowed flat, `atEdge` once, at rest
  after).
- **The dialog with no per-frame layout and no per-frame accessible walk:** `DialogSlideTest` (the
  slide in step with the fade on every frame, no layout pass, no walk, the arrival published; a drag
  still placing the card for real); `InSceneFadeEndsTest` reaches the fade through the slide.
- **The work budget unchanged for a still form, and `FullFrameAllocationTest` green:** the budget
  case above, and `FullFrameAllocationTest.aFrameOfASlidingFormAllocatesAlmostNothing` beside the
  two it had.
- Besides: the values and refusals, the thread and paint rules, layout and the tree untouched,
  where a moved and scaled child is drawn, Opacity on either side covering the moved child, one
  ticker for thirty thousand motions, every motion settled as the container leaves its scene,
  showing past a clip, and the flipped card arriving the right way round, all in `TransformTest`
  (22 cases).
- **Proved both ways**, each piece backed out and the tests run: without either cull's way through
  an ancestor's box, the two seam cases past the parent's box and `TransformTest`'s pass and damage
  cases fail; with only the canvas clip's way removed, the two seam cases still fail on the GPU;
  without the damage of the place a child left, the slide step, the slide past the box and the
  fading slide fail, and the damage case; with the dialog's veil back on `invalidate()`, the
  dialog's walk case fails on its first frame.
- **The whole suite:** `./gradlew check --continue` at `53229d97`, 7340 tests, 91 skipped (the probe
  among them), none failed.

### 9.4 What stage 2 must still do

(2026-10-18: done in stage 2, section 10, but for the guests' runs, which come in one round after
stage 3, and what section 8.2 left for stage 3.)

- **The layer in local space** for every matrix that turns the axes, replacing the upright branch
  of `Widget.paintChildrenThrough`: re-based, the composite with `vec4` positions, the scissor and
  the rounded-clip variant, the cleared ring, as section 2.4 has them; `setRotation`, `rotation()`,
  `rotateTo` and `setMatrix` on the container, composed into the same product
  (`O · translation · flip · rotation · scale · matrix · O⁻¹`, the flip's place kept for 3D).
- **The warped glass** of section 2.9 and question 10.
- **An exact layer for an `Opacity` round a moving Transform**, if the symmetric overhang above is
  measured to matter.
- **A popup window placed again** when a Transform above its anchor is set or arrives: the scene
  needs a way to ask the popup's owner, which places the window, to do it.
- **The guests' runs** of section 7: the glyph probe and the pixel test on Windows, Fedora and the
  macOS guest, and stage 2's own composite measurements.
- **`Opacity.fadeTo` and the motions agreeing on an infinite duration** (section 9.2).
- What section 8.2 left for stage 3 stands: a public perspective factory or the composition in
  `limn.math`, and a backface flag on the hook.

## 10. Stage 2, built 2026-10-18

Built on `claude/adr-072-stage2` from `7d60118f` (stage 1 and its CHANGELOG entries), in ten
commits before this section: the opacity's finite seconds, the canvas's layer through a transform
with the warped glass, the container's rotation and matrix drawn through it, the device tests, the
popup windows placed again, the guide, the exact layer of an `Opacity` round a moving Transform,
the scroll view's culling held, the probe of section 7, and the composite's clip computed only
where a pixel can be cut. The turns
in depth, the perspective, the backface and the near plane are stage 3.

### 10.1 What was built

- **`Canvas.saveLayer(x, y, width, height, opacity, Mat4 transform)`** (section 2.4), public as
  ADR 071's `saveLayer` is: a layer in a space of its own, the rectangle in that space, composited
  through `transform` after the canvas's own. Its default, for a canvas that cannot make layers,
  applies the matrix's flat part straight with `translate`, `rotate`, `scale` and `rotate` again
  (two rotations about a scale, which expresses a skew no single call does) and the opacity per
  draw. `Mat4.affine2D(a, b, c, d, e, f)` makes a matrix from a 2D affine map's six numbers.
- **`GlCanvas`'s re-based layer.** A transform that keeps the axes is applied straight and the
  layer is the plain one (none at an opacity of 1). Any other: the picture covers what of the
  rectangle the enclosing clip lets be seen, the clip's corners mapped back through the inverse
  homography and grown by two of the picture's pixels; it is drawn at the largest magnification the
  transform gives that part, the largest singular value of its derivative, and smaller where that
  would pass the framebuffer; inside, the transform is that scale and the clip the picture's own
  rectangle, so every clip in the subtree is an exact rectangle again and `clipBounds()` answers.
  A ring of one texel past the part drawn is cleared. The restore lays the picture's four corners
  through the homography as `vec4` positions (`canvas_composite.vert` takes `vec4`; a plain layer's
  `w` is 1), sampled bilinearly through a sampler object over the pool's nearest sampling, under
  the enclosing clip (10.2).
- **The warped glass** (section 2.9, answer 10): a glass inside a re-based layer reads what it
  would read were every layer round it opaque, in its own space: the window's copy warped into it
  through the inverse of the way out to the window, as one quad over the region the glass reads,
  then each open layer's picture so far through its own way in, into a second window-sized copy
  `GlBackdrop` keeps beside the first. A layer whose way out is not affine is a backdrop root,
  which is section 2.9's rule for 3D, there for stage 3 to turn on.
- **`Transform`'s rotation and matrix** (section 2.2): `setRotation(float degrees)`, `rotation()`,
  `rotateTo(degrees)` and `rotateTo(degrees, seconds, onArrive)` by `slideTo`'s rules
  (announcements, supersession, less motion, settling on leaving, one ticker); `setMatrix(Mat4)` and
  `matrix()`. `TransformGroup` composes `O · translation · flip · rotation · scale · matrix · O⁻¹`.
  `reset()` puts both back.
- **The paint through the picture** (section 9.4's first item): `Widget.paintChildrenThrough`, for
  a matrix that does not keep the axes, opens the layer over what the children paint upright in the
  container's space (`paintedRect` with the matrix left out) and paints them into it; the matrix is
  `ChildTransform.asMat4()`, the one set or a snapshot of the one loaded in place, taken once per
  load.
- **Popup windows placed again** (section 9.4): `Scene.observeAnchorMoved(anchor, placeAgain)`,
  reached through `SceneAccess`, keeps each popup window's anchor and what places it; a set and an
  arrival (`Scene.anchorsMoved`) run it for every anchor under the container. `AnchoredPopup` (the
  combo box's list, the date picker's calendar, the tab list) subscribes while its window is up and
  places it again a turn later through its own `refit`. `HeadlessWindow` keeps the positions asked
  of it, for the test.
- **`Opacity.fadeTo`** refuses an infinite number of seconds (section 9.2's open question), with a
  dated note in ADR 071 section 7.2.
- **The guide:** "Moving part of a window" names the rotation and the matrix, says what a turned
  child costs and looks like, and shows a section's arrow that turns down as the section opens,
  clockwise or not by the direction (`AnimationExample.showOpen`). The Showcase's Motion page
  already turns a card over; a rotation there would be forced and was not added.

### 10.2 What the build settles, and where it departs from the text above

- **2026-10-18. `setMatrix` lands now, restricted to a flat matrix.** Sections 2.2, 6 and 9.4 put
  "the general 2D matrix" in stage 2 and list `setMatrix` in stage 3's tests too. It lands in stage
  2 with the signature of section 2.2 and refuses, with `IllegalArgumentException`, a matrix whose
  bottom row tilts the plane toward the eye (not `(0, 0, any, w > 0)`): that is a perspective, which
  stage 3 brings with the near plane, the backface and the magnification under it, and which will
  lift the restriction without changing the signature. Its z row and column are inert while no
  perspective is applied, as section 2.2 says.
- **2026-10-18. `Mat4.affine2D` is added.** Section 2.2 adds "no separate 2D affine type", and
  none is; but `Mat4` had no factory a skew could be made with (its array constructor is
  package-private, and the public factories make translations, scales and turns), so a factory
  taking the six numbers of a 2D canvas's matrix was the smallest way to make `setMatrix` usable.
  Stage 3's perspective factory (section 8.2) is still owed.
- **2026-10-18. The picture's space is window-sized, the picture at its bottom-left.** Section 2.4
  has the canvas open the picture with "the Transform's local space times one scale". As built
  it is that space times one scale and moved by whole pixels, so that the picture lies inside a
  space the window's size with its bottom edge on the space's: every clamp to the framebuffer, the
  rule that keeps a nested opacity layer on the 2x2 grid, and a glass's own coordinates in the
  backdrop copy then hold inside it unchanged. The picture's corner sits on the 2x2 grid of the
  scaled space, as ADR 071's sits on the window's, measured from the scaled space's own origin:
  without it a pass's smaller picture of a turned card antialiased its curves up to 5 steps off the
  whole frame's (`TransformPassSeamTest`).
- **2026-10-18. The clip at the composite.** Section 2.4 applies the enclosing rectangle as a
  scissor and a rounded clip by a variant of the program. A rectangle with an edge between pixels
  (a viewport laid at a fraction of a point) takes the variant too: a scissor covers such an edge's
  pixels wholly or not at all where the canvas program covers them in part, 106 steps apart at the
  edge (`GlTurnedLayerTest`). And the variant runs only where a pixel can be cut: the band between
  the rounded clip's corners, inside every edge by half a pixel, is drawn by the plain program under
  a scissor, and the variant takes the strips above, below and beside it, five draws of the one
  quad. Measured before and after in section 7.
- **2026-10-18. The picture's reach past what is seen** is two of its pixels, so that a filtered
  sample at the clip's edge reads texels that were drawn. No test fails without it (the culling's
  own pixel of margin covers the cases tried); it is kept as the cheap guard section 2.4's filtering
  asks for.
- **2026-10-18. The glass, within 8 steps.** Section 6 asks for "a few steps". A glass in a card
  turned 4 degrees reads the window within 8 steps of the same glass upright under the inverse turn,
  read between pixels; the 8 are two filtered resamplings (the warp, the picture laid at the angle)
  rounding a ramp's turning points by a share of a pixel. Without the warp it is 125 off.
- **2026-10-18. An `Opacity` round a moving Transform makes an exact layer.** Section 9.2 found the
  layer as much wider toward the start as the child slid to the end, and 9.4 left it "if measured to
  matter". It needed no measurement to fix: a transform container away from rest paints nothing of
  its own, so `Widget.paintedRect` gives, for it, exactly where it draws its children, and not its
  box grown by the one-number overhang partial rendering reads (`TransformTest`'s pair case holds the
  layer to the child and its antialiased point).
- **2026-10-18. Popup menus are not followed.** A `PopupMenu` opens at a point or a rectangle its
  caller gives in scene coordinates, not against a widget; nothing knows what to place it against
  again, in the scene or in a window. Section 2.8's rule holds for every popup placed against a
  widget.
- **2026-10-18. What follows the rotation, checked.** The hit test, the conversions, the damage
  (the children's painted bounds through the old and the new matrix), the scene's cull and the
  canvas's cull inside the picture, `isShowing`, `revealInView`, the accessible bounds and the input
  method's caret rectangle were routed through the matrix by stages 0 and 1 for any homography;
  stage 2 changed none of them and holds each under a real rotation (10.3).
- **2026-10-18. A frame of a turning form allocates 264 bytes** (`FullFrameAllocationTest`), a
  sliding one's 136 and the rotation's turn and the matrix taken as a value for the picture; a
  turned container repainted while still allocates nothing for its matrix.

### 10.3 Section 6's stage 2 tests, and where they are

- **A rotated scroll view shows nothing in its corners:** `TransformRotationPixelTest` (a scroll
  view turned 30 degrees, every pixel of its bounding box past the turned viewport the page; 5739
  such pixels drawn straight) and `GlTurnedLayerTest`'s clip case against the canvas's straight
  rotation; and it still skips the rows scrolled out of its view (14 painted, 208 drawn straight).
- **A rotated text field's caret and selection land where the pointer is:**
  `TransformRotationPixelTest` (a field turned -24 degrees: the caret drawn within half a glyph of
  the click along the field, the selection band from where the drag started to where it ended, each
  changed pixel turned back into the field's coordinates), and `TurnedPointerTest`, whose eight
  components now run inside the real `Transform` rotated 33 degrees and skewed 20 as well.
- **A partial pass through a rotated Transform equals the whole frame:** `TransformPassSeamTest`'s
  rotation step, skew step, a repaint inside a turned card, and a fading rotation in either order.
- **Edges antialiased within a pixel:** `GlTurnedLayerTest` (the same area covered as the straight
  draw, the ramp across an edge within a pixel of the straight draw's), and the cleared ring.
- **A glass in a slightly rotated card equal, within a few steps, to the same glass upright under
  the inverse rotation:** `GlTurnedLayerTest`, within 8 (10.2).
- **The picture freed the frame after a rotation ends:** `GlTurnedLayerTest` and
  `TransformRotationPixelTest`.
- Besides: the enclosing rectangular and rounded clips against a straight draw, a move and a scale
  making no picture, a picture past the window (`GlTurnedLayerTest`); the values, refusals, product,
  right-to-left rule, picture, damage, motion, less motion, settling, pointer, readers, `isShowing`,
  `revealInView` and the caret rectangle (`TransformRotationTest`, 13 cases); the popup window
  (`PopupWindowUnderATransformTest`); the canvas's default (`SaveLayerThroughATransformTest`);
  `Mat4.affine2D` (`MathTest`); the budget, one command per picture
  (`WorkBudgetTest.aStillFormInTurnedTransformsCostsItsPicturesAndNothingMore`); the guide's arrow
  (`AnimationExampleTest`).
- **Proved both ways**, each piece backed out and the tests run: without the warp the glass is 125
  off; without the variant program both enclosing clips fail; with no picture the clip, the corners,
  the culling, the caret and the freeing fail; without the ring a reused picture is 106 off; without
  the 2x2 grid the repaint inside a turned card is 5 off; with the matrix left out of the product
  the table's resize and the tree's triangle fail skewed; with the picture replaced by a save the
  picture case fails; with the scene not asking the popup's owner the popup case fails; with
  `Opacity.fadeTo` accepting infinity its case fails.
- **The whole suite:** `./gradlew check --continue` at `b8e983ba`, 7377 tests, 92 skipped (the two
  probes among them), none failed.

### 10.4 What stage 3 must still do

- `setRotation3D`, `setPerspective`, `setBackfaceVisible` and a `flipTo` drawn with perspective;
  lifting `setMatrix`'s restriction; a public perspective factory or the composition in `limn.math`
  (section 8.2); the backface flag on the hook.
- In the canvas: the near plane for a picture's visible part and its corners (the composite already
  takes homogeneous positions and the GPU cuts `w < 0`, but `clipSeenFrom` gives up on a clip
  corner behind the eye and the picture then covers the whole rectangle), the magnification under
  perspective (computed at the visible part's corners already), the skipped composite of a backface,
  and the backdrop root, which `captureInSpace` already applies to a layer whose way out is not
  affine.
- The oblique-text decision and `glGenerateMipmap` measured (question 11); section 7's projected
  composite, which the probe's 30-degree mode measures flat, at 75 degrees in perspective.
- The guests' runs of section 7, after stage 3, in one round: the glyph probe, the pixel tests, and
  `TransformLayerProbe` on Windows, Fedora and the macOS guest.
- The fold of an adjacent `Opacity` into a turned Transform's composite stays declined (question 12).
