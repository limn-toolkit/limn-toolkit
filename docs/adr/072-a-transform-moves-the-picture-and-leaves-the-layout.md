# ADR 072: A transform moves the picture and leaves the layout

- **Status:** Proposed, 2026-10-17; decided the same day. The owner decided on 2026-10-17 that
  opacity and transforms are containers, as `Padding` is for insets, and not methods of `Widget`;
  opacity moved from `Widget` into an `Opacity` container by an amendment of ADR 071 (its section
  7), and this record designs the transform container beside it. The owner answered every
  question of section 4 the same day, and one more the answers raised (section 4.1): the two seams, the
  2D tier and the 3D tier all ship before 1.0, in one final `Transform`. Nothing here is built.
  The measurements of section 7 are owed on the lab's guests before the status moves to
  Accepted; a number that contradicts a decision comes back to the owner.
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
  matrix that moves points in z only matters through the perspective.
- **The order is fixed.** For a point `p` of the child, with `O` the translation to the origin:
  `O · translation · perspective · rotation3D · rotation · scale · matrix · O⁻¹ · p`, then the
  Transform's own position in its parent. So a scale, a rotation and a turn share one pivot, a
  translation moves the result, and the perspective looks at it from in front of the origin. An
  application that needs another order nests two Transforms or writes the `Mat4`.
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
  found it.
- **Less motion.** While the system asks for less motion, `slideTo`, `scaleTo` and `rotateTo` go
  straight to the target and `onArrive` runs at once, as `fadeTo` does. `flipTo` runs `atEdge`
  and then `onArrive` at once, swapping the content with no turn (question 8).
- **A watcher hears a transform change by the owner's rule of ADR 071** (decision 2 there),
  through a new aspect `Change.Aspect.TRANSFORM`, origin `CODE`:
  - a direct set that changes a value announces it, every time; `reset()` announces once;
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
  the child's `paintWidget`. Every clip inside stays exact (an axis-aligned transform keeps a
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
    `clipBounds()` answers, so culling inside works as it does in the window.
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
  owed (section 7).
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
  constant-time.
- **A change damages the old and the new projected bounds**: the axis-aligned bounds of the
  child's box and outset through the old matrix and through the new one, each clipped at
  `w > ε`, in the scene, clamped by clipping ancestors as `invalidate()` damage is. No layout.
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
  animation.

### 2.9 Backdrop effects under and inside a transform

- **Under a translation or a scale** a glass is drawn straight into the window and reads what it
  reads today; under ADR 071's group it reads as ADR 071 §2.6 decides.
- **A glass beneath a Transform** (drawn before it) is repainted when the Transform's damage
  reaches it (`paintsFromBackdrop`), as for a fading group.
- **Inside a rotated or skewed picture** (2D, a layer in local space) the window under it is not
  at the picture's pixels. Proposed: it reads what it would read if the picture were opaque, as
  inside ADR 071's group, by drawing the window's copy into local space through the inverse
  affine, one more quad per glass per open picture over the glass's bounds; a frosted card turned
  a few degrees keeps showing the window through its glass (question 10).
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
must say so.

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
  `IllegalArgumentException` for a widget that is not this widget's child.
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
- **A call of `hitTest`** compiles and answers as before.
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
- **Glyphs under a scale animation:** rasterizations and atlas pages per frame of a `scaleTo` of a
  text-heavy card, and the frame time it costs.
- **`glGenerateMipmap` per frame** on the three guests, for question 11.
- **A partial frame through a moved child** against a whole frame (stage 1's pixel test, run on
  the guests), since the scene's set of Transforms changes what a pass paints.
