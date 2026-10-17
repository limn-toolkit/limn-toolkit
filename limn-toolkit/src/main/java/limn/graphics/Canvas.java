package limn.graphics;

import org.jspecify.annotations.Nullable;

/**
 * Immediate-mode 2D drawing API: what every widget paints through. All
 * coordinates are <em>logical points</em>; the backend multiplies by the
 * monitor content scale (a float: 1.0, 1.25, 1.5, 2.0…) into physical pixels,
 * applies analytic antialiasing, and batches geometry into as few GPU draw
 * calls as possible.
 *
 * <p>Pixel snapping: 1-logical-pixel strokes and axis-aligned lines are
 * automatically aligned to the physical pixel grid (odd device widths center
 * on half-pixels, even on integers) so hairlines stay crisp at fractional
 * scales like 125%/150%. Snapping is skipped under rotation.
 *
 * <p>State ({@link #save()}/{@link #restore()}) covers the transform, the
 * opacity and the clip. Clips only intersect (never expand) and are restored
 * by {@code restore()}; an opacity multiplies the one in force. A
 * {@link #saveLayer layer} is a save whose drawing is composited as one
 * picture by its {@code restore()}.
 *
 * <p>A Canvas is only valid during a frame callback and is single-threaded
 * (UI thread).
 */
public interface Canvas {

    // ------------------------------------------------------------ frame info

    /** @return frame width in logical points */
    float width();

    /** @return frame height in logical points */
    float height();

    /** @return physical pixels per logical point for this frame */
    float contentScale();

    /** Clears the whole framebuffer (ignores transform/clip/opacity). */
    void clear(Color color);

    /**
     * Reads back, now, a part of what has been drawn on this canvas in this frame, with its
     * transparency: what was {@linkplain #clearRect cleared} to nothing and not painted over is
     * transparent in the image. It is how a picture of one thing is made, painted alone on a
     * cleared corner of a frame before the frame itself is painted over it; a drag's picture is.
     *
     * <p>On the canvas, and not on the renderer behind it, because whoever paints a scene has
     * the canvas and may have nothing else: an application that renders its scene from a frame
     * callback of its own hands the scene a canvas and no renderer.
     *
     * @param x      the part's left edge, in logical points
     * @param y      the part's top edge, in logical points
     * @param width  its width, in logical points
     * @param height its height, in logical points
     * @return the pixels, straight alpha, rows top-down, at the canvas's own resolution; null
     *         where the canvas keeps no transparency or cannot be read
     */
    default @org.jspecify.annotations.Nullable Image readBack(float x, float y, float width, float height) {
        return null;
    }

    /**
     * Replaces one rectangle with {@code color}: {@link #clear} semantics
     * confined to a rect, in untransformed logical frame coordinates, ignoring
     * transform/clip/opacity. Partial rendering resets each repaint pass with
     * this. The default falls back to {@link #fillRect}, which is equivalent
     * only for opaque colors: backends that support translucent framebuffers
     * (popup windows) must override it with a true replace (a blend cannot
     * write alpha back to 0).
     */
    default void clearRect(float x, float y, float width, float height, Color color) {
        fillRect(x, y, width, height, color);
    }

    /**
     * Backend hint from partial rendering: every draw for the rest of this
     * frame lies inside this rect, in untransformed logical frame coordinates.
     * Backends may confine rasterization to it (e.g. {@code glScissor}) so
     * fragments outside are never shaded. Purely an optimization: pixels
     * outside simply keep their previous content, so honoring it must not
     * change what a correct frame produces. Reset at the start of each frame;
     * a non-positive width or height disables it. Default: ignored.
     */
    default void damageScissorHint(float x, float y, float width, float height) {
    }

    // ------------------------------------------------------------------ state

    /**
     * Pushes a copy of the current state (transform, opacity, clip, blend
     * mode, sampling, pixel snap).
     */
    void save();

    /** Pops back to the previously saved state. */
    void restore();

    /**
     * How many {@link #save()}s are outstanding, the depth of the state stack.
     *
     * <p>It exists so that a caller can put the canvas back where it found it without knowing how
     * many saves happened in between, which is the only way to be safe against code it does not
     * control: a widget's paint may push clips, may push more of them down a branch, and may stop
     * halfway by throwing. {@code Widget.paintWidget} reads this before painting a widget and
     * trims back to it afterwards, so one widget's leak cannot become every ancestor's.
     *
     * @return the number of outstanding saves; 0 on a canvas nobody has saved on
     */
    int saveCount();

    /**
     * Pops saves until {@link #saveCount()} equals {@code count}.
     *
     * <p>Does nothing when the depth is already at or below {@code count}: it is a trim, not an
     * assertion, and a caller using it to recover from someone else's mistake must not be given a
     * second mistake to handle. Never pops past zero.
     *
     * @param count the depth to return to, normally one taken from {@link #saveCount()} earlier
     */
    void restoreToCount(int count);

    void translate(float dx, float dy);

    void scale(float sx, float sy);

    /** Rotates by {@code angleRadians} (positive = clockwise, y grows down). */
    void rotate(float angleRadians);

    /**
     * <b>Multiplies</b> the current state's opacity by {@code opacity}, clamped to [0..1]; every
     * subsequent paint's alpha is multiplied by the result. Restored by {@link #restore()}.
     *
     * <p>It multiplies rather than replaces so that an opacity set inside a fading surface, or
     * inside a {@linkplain #saveLayer layer}, combines with the one around it instead of undoing
     * it: a widget at one half inside a panel fading at one half draws at a quarter. On a fresh
     * {@link #save()} the current opacity is whatever the enclosing state carries, which is 1 at
     * the top of a frame and inside a layer, so a caller that saves and sets once gets the number
     * it asked for. A caller that wants an absolute value has to know what it is multiplying.
     *
     * <p>This is a per-draw alpha: each draw is translucent on its own, so where one draw covers
     * another the one beneath shows through it. To fade overlapping draws as one picture, open a
     * {@link #saveLayer layer} instead.
     *
     * @param opacity the factor, clamped to [0..1]
     */
    void setOpacity(float opacity);

    /** @return the current state's opacity, the product of every {@link #setOpacity} still in force */
    float opacity();

    /**
     * Opens a layer: until the matching {@link #restore()}, everything drawn goes into an offscreen
     * picture, and the restore composites that picture over what is beneath it with
     * {@code opacity}, once. Overlapping draws inside it therefore fade as one picture: a row's
     * highlight does not show through the row's icon, and a panel's shadow does not darken the
     * panel. This is what {@code Widget.setOpacity} draws through, and what a widget that paints
     * overlapping parts of its own uses to fade them together.
     *
     * <p>The rectangle, in current coordinates, is what the picture keeps: whatever is drawn outside
     * it is lost. A canvas sizes the picture to the rectangle as far as its current clip lets it be
     * seen, on whole device pixels, so a layer is never larger than what can show. Like
     * {@link #save()} it pushes the state (transform, clip, blend mode, sampling, pixel snap), and
     * inside it the opacity starts again from 1, since the composite carries it; the blend mode in
     * force when it is opened is the one the composite uses, and drawing inside starts from
     * {@link BlendMode#NORMAL}.
     *
     * <p><b>What it costs</b> (ADR 071, measured on the renderer this toolkit ships): two batch
     * breaks and one composited quad per layer, about 0.03 to 0.1 ms of GPU for a popup-sized
     * layer on a GPU and a percent or two of the frame on a software renderer; a window-sized one
     * costs 0.1 to 0.15 ms of GPU, and about 10% of a frame on a software renderer. A canvas keeps
     * the layer's memory, four bytes a device pixel, only while consecutive frames open layers.
     * An opacity of 1 does not need one, and a caller should not open one for it.
     *
     * <p><b>A canvas that cannot make layers</b> (a recording or measuring one) falls back to what
     * the default does: a {@link #save()} and a per-draw {@link #setOpacity}, which draws the same
     * things in the same places and lets overlapping draws show through each other.
     *
     * @param x       the left edge of what the layer keeps, in current coordinates
     * @param y       its top edge
     * @param width   its width
     * @param height  its height
     * @param opacity the opacity the picture is composited with, clamped to [0..1]
     */
    default void saveLayer(float x, float y, float width, float height, float opacity) {
        save();
        setOpacity(opacity);
    }

    /**
     * Sets the current state's {@link BlendMode}: how every subsequent
     * primitive (shape, text, image) combines with the destination. Restored
     * by {@link #restore()}. Backends without blend-mode support paint
     * {@link BlendMode#NORMAL} (the no-op default).
     */
    default void setBlendMode(BlendMode mode) {
    }

    /** @return the current {@link BlendMode} (default {@link BlendMode#NORMAL}) */
    default BlendMode blendMode() {
        return BlendMode.NORMAL;
    }

    /**
     * Sets the current state's image {@link Sampling}: how subsequent
     * {@code drawImage} calls filter texels ({@link Sampling#PIXELATED} keeps
     * pixel art crisp when scaled). Restored by {@link #restore()}. Shapes and
     * text are unaffected.
     */
    default void setSampling(Sampling sampling) {
    }

    /** @return the current image {@link Sampling} (default {@link Sampling#SMOOTH}) */
    default Sampling sampling() {
        return Sampling.SMOOTH;
    }

    /**
     * Enables/disables the automatic pixel-grid snapping of <em>image</em>
     * quads under axis-aligned transforms. Snapping (the default) keeps
     * icons and stills crisp; disable it for sprites that move sub-pixel per
     * frame. A slow smooth scroll snaps into a visible stutter otherwise.
     * Text and stroke snapping are unaffected. Restored by {@link #restore()}.
     */
    default void setPixelSnap(boolean snap) {
    }

    /** @return whether image quads snap to the pixel grid (default {@code true}) */
    default boolean pixelSnap() {
        return true;
    }

    /**
     * Intersects the clip with an axis-aligned rectangle (in current
     * coordinates). V1 limitation: under a rotated transform the clip
     * degrades to the device-space AABB of the transformed corners, a
     * superset of the requested region (content may show in the AABB's
     * corners). Axis-aligned transforms clip exactly.
     */
    void clipRect(float x, float y, float width, float height);

    /** Intersects the clip with an axis-aligned rectangle (see {@link #clipRect(float, float, float, float)}). */
    default void clipRect(Rect rect) {
        clipRect(rect.x(), rect.y(), rect.width(), rect.height());
    }

    /**
     * The current clip as a rectangle in <em>current</em> coordinates, or {@code null} when this
     * canvas cannot say: it does not track clips, or the transform is rotated and the clip's
     * device-space box has no rectangular pre-image. A caller may use a non-null answer only to
     * skip work whose every pixel would be clipped away, which is what {@code Widget} does for a
     * subtree scrolled entirely out of a viewport; nothing may draw <em>more</em> because of it.
     * The default is the null answer, so a canvas that inherits it simply paints everything.
     */
    default @Nullable Rect clipBounds() {
        return null;
    }

    /**
     * Intersects the clip with a rounded rectangle. The corners are this
     * rectangle's own: a {@link #clipRect(float, float, float, float)} made
     * before or after it cuts the region straight and never rounds where it
     * cuts, so a repaint confined to part of a rounded surface draws that part
     * exactly as a whole frame does.
     *
     * <p>V1 limitations: the clip state tracks one rounded rect, so nested
     * rounded clips intersect their rectangles exactly and the region they
     * leave is rounded by the most recent corner radius alone, as a single
     * uniform radius (the max of the four corners, clamped to that region's
     * half extents). That is exact when the inner rounded rect lies inside the
     * outer one; where the two only overlap, every corner of the region they
     * leave is rounded by the inner radius, whichever rectangle it came from.
     * A rounded rect whose corners are all square clips like {@code clipRect}
     * and leaves an earlier one's corners as they were. Rotation degrades to
     * the AABB like {@code clipRect}.
     */
    void clipRoundRect(RoundRect roundRect);

    // ----------------------------------------------------------------- shapes

    void fillRect(float x, float y, float width, float height, Paint paint);

    default void fillRect(Rect rect, Paint paint) {
        fillRect(rect.x(), rect.y(), rect.width(), rect.height(), paint);
    }

    /** Strokes the rectangle outline, centered on its boundary. */
    void drawRect(float x, float y, float width, float height, float strokeWidth, Paint paint);

    void fillRoundRect(RoundRect roundRect, Paint paint);

    default void fillRoundRect(float x, float y, float width, float height, float radius, Paint paint) {
        if (width <= 0 || height <= 0) {
            return; // degenerate geometry draws nothing; see drawRoundRect
        }
        fillRoundRect(RoundRect.of(x, y, width, height, radius), paint);
    }

    /**
     * Draws one layer of the shadow a rounded rectangle casts: the shape moved by the layer's
     * offset, its edge softened over the layer's blur, in the layer's colour. Drawn whole, under
     * where the shape will be: paint the shape after it. Ink reaches {@link Shadow#reach()} past
     * the shape's box.
     *
     * <p>The default draws nothing: a shadow is decoration a canvas may go without, and a
     * recording or counting canvas has nothing to say about it.
     *
     * @param shape  the shape that casts it
     * @param shadow the layer
     * @throws NullPointerException if either argument is null
     */
    default void drawShadow(RoundRect shape, Shadow shadow) {
        java.util.Objects.requireNonNull(shape, "shape");
        java.util.Objects.requireNonNull(shadow, "shadow");
    }

    /**
     * Fills {@code roundRect} with what this frame has <em>already drawn</em> underneath it, put
     * through {@code effect}: a glass panel, a wash over video, a redacted field. The shape is
     * opaque within its coverage: the effect replaces the pixels behind it with a transformed copy
     * of them, so a {@link BackdropEffect#tint()} with alpha 0 and no displacement is exactly
     * identity.
     *
     * <p><b>It samples the frame at the moment it is called.</b> Anything drawn after it is not in
     * the backdrop and will simply paint on top; anything drawn before it is. That makes paint
     * order load-bearing in a way it is not anywhere else in this interface: a panel must be
     * painted after the content it is meant to sit over, which for a widget means the ordinary
     * child order and for a scene means the ordinary z order.
     *
     * <p><b>Cost.</b> A renderer that batches (this one does) must flush pending geometry and copy
     * a region of the framebuffer before it can sample it, so every call is a batch break plus a
     * copy of the shape's bounds. That is cheap for a control bar and wasteful for a hundred small
     * shapes; it is the reason this is a separate entry point rather than a {@link Paint}.
     *
     * <p><b>Where it goes wrong, and it is one place:</b> with partial rendering enabled
     * ({@code Scene.setPartialRendering}), a frame repaints only what was invalidated. A shape
     * filled this way depends on pixels that are not its own, so if content behind it changes
     * while it does not itself invalidate, it keeps showing the older backdrop. Over content that
     * repaints anyway (video, an animation, a viewport), the question does not arise. Over static
     * content the effect is correct because nothing moved.
     *
     * <p>Rounded rectangles only, which covers rectangles, circles and capsules through the same
     * shape. There is no path-shaped form: the effect needs the shape's signed distance to find its
     * own rim, and a filled path has no analytic distance field.
     *
     * <p><b>Renderers need not implement it.</b> The default fills the shape with
     * {@link BackdropEffect#tint()}: a flat translucent panel, the right size in the right place,
     * which is what this toolkit drew before any of this existed. A backdrop effect degrades; it
     * does not fail.
     *
     * @throws NullPointerException if either argument is null
     */
    default void fillBackdropRoundRect(RoundRect roundRect, BackdropEffect effect) {
        fillRoundRect(roundRect, effect.tint());
    }

    /** {@link #fillBackdropRoundRect(RoundRect, BackdropEffect)} with one radius on every corner. */
    default void fillBackdropRoundRect(float x, float y, float width, float height, float radius,
                                       BackdropEffect effect) {
        if (width <= 0 || height <= 0) {
            return; // degenerate geometry draws nothing; see drawRoundRect
        }
        fillBackdropRoundRect(RoundRect.of(x, y, width, height, radius), effect);
    }

    /** Strokes the rounded-rect outline, centered on its boundary. */
    void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint);

    /**
     * Strokes the rounded-rect outline, centered on its boundary.
     *
     * <p>A degenerate size (zero or negative width/height) draws nothing rather
     * than throwing, which is the convention every 2D API follows, and a
     * practical necessity: a border drawn inside its box
     * ({@link #drawRoundRectBorder}, a stroke on the box inset by half the
     * border) produces a negative size the moment a widget is laid out narrower
     * than its border, which happens routinely while a window is being resized
     * small. Explicitly building a {@link RoundRect} still validates, so
     * genuinely malformed geometry (say, swapped corners) is still caught at
     * construction.
     */
    default void drawRoundRect(float x, float y, float width, float height, float radius,
                               float strokeWidth, Paint paint) {
        if (width <= 0 || height <= 0) {
            return;
        }
        drawRoundRect(RoundRect.of(x, y, width, height, radius), strokeWidth, paint);
    }

    // ------------------------------------------------------ bordered surfaces

    /**
     * Paints a bordered surface: {@code fill} over the rounded box, and a border
     * {@code borderWidth} wide in {@code border} lying wholly inside the box, its
     * outer edge exactly the box's outline. The one call for a field, a card, a
     * panel or a swatch; {@link #drawRoundRectBorder} explains the geometry, and
     * {@link #fillRoundRectInsideBorder} and {@link #clipRoundRectInsideBorder}
     * are its two halves, for a surface that paints something between its fill
     * and its border or whose fill is not one paint.
     *
     * <p>The defaults here build on the scalar {@code fillRoundRect} and
     * {@code drawRoundRect}, so they allocate nothing. A renderer may draw the pair
     * in one pass instead, as long as it keeps the geometry the defaults define,
     * moved onto its pixel grid as the two halves say.
     */
    default void fillRoundRectWithBorder(float x, float y, float width, float height, float radius,
                                         Paint fill, float borderWidth, Paint border) {
        fillRoundRectInsideBorder(x, y, width, height, radius, borderWidth, fill);
        drawRoundRectBorder(x, y, width, height, radius, borderWidth, border);
    }

    /**
     * Strokes a border {@code borderWidth} wide lying wholly inside the rounded
     * box, its outer edge exactly the box's outline.
     *
     * <p><b>The geometry.</b> A stroke is centred on its path, so a border inside
     * a box is a stroke on the box inset by half the border. The inset path's
     * corner radius has to shrink by the same half border, and that is what makes
     * it concentric with the box: every point of an arc of radius
     * {@code radius - borderWidth / 2} is half a border from the box's own arc,
     * so the stroke's outer edge follows the box's outline all the way round.
     * Insetting the rectangle but keeping the box's radius, the obvious
     * {@code drawRoundRect(0.5f, 0.5f, w - 1, h - 1, radius, 1, …)}, draws a
     * corner whose outer edge is an arc of radius {@code radius + 0.5} about a
     * centre half a point further in: on the diagonal it falls
     * {@code 0.5 × (√2 − 1)}, about 0.2 pt, short of the box's corner, and
     * whatever fills the box shows outside the border there.
     *
     * <p>A radius under half the border leaves the path square, and the stroke's
     * outer corner is then rounded by half the border, which is as tight as a
     * centred stroke can draw. A box no wider or taller than its border draws
     * nothing.
     */
    default void drawRoundRectBorder(float x, float y, float width, float height, float radius,
                                     float borderWidth, Paint paint) {
        float half = borderWidth / 2;
        drawRoundRect(x + half, y + half, width - borderWidth, height - borderWidth,
                Math.max(0, radius - half), borderWidth, paint);
    }

    /**
     * Fills the box inside the border {@link #drawRoundRectBorder} draws with the
     * same arguments, as far as the border's centre line: the box inset by half
     * the border, with the radius reduced by as much. Reaching exactly that far
     * is what hides the fill's antialiased edge under the border's opaque middle:
     * a fill of the whole box puts that edge on the border's own outer edge, and
     * the partly covered pixels there mix the fill into the border's fringe, which
     * a strong fill under a grey border shows as a coloured rim. Draw the border
     * after it.
     *
     * <p>A renderer that moves a stroke's centre line onto its pixel grid (see the
     * class notes) moves this fill's edge with it, to where the border's centre
     * line is drawn rather than where it was asked for: left behind, the fill's
     * corner arc no longer shares a centre with the border's: wherever the border
     * moved inward the fill shows past it on the diagonal, and wherever it moved
     * outward the background shows between the two.
     *
     * <p>A border that fades out, leaving the fill to stand alone, is the one case
     * this is wrong for: the fill has to reach the box then, and is a plain
     * {@link #fillRoundRect(float, float, float, float, float, Paint)} of it.
     */
    default void fillRoundRectInsideBorder(float x, float y, float width, float height, float radius,
                                           float borderWidth, Paint paint) {
        float half = borderWidth / 2;
        fillRoundRect(x + half, y + half, width - borderWidth, height - borderWidth,
                Math.max(0, radius - half), paint);
    }

    /**
     * Intersects the clip with the shape {@link #fillRoundRectInsideBorder}
     * fills, for a surface whose fill is a gradient, an image or several shapes:
     * clip, paint them over the whole box, restore, then draw the border. Builds
     * one {@link RoundRect}, as {@link #clipRoundRect} needs. A renderer that moves
     * strokes onto its pixel grid moves this clip's edge with the border's centre
     * line, as {@link #fillRoundRectInsideBorder} explains.
     */
    default void clipRoundRectInsideBorder(float x, float y, float width, float height, float radius,
                                           float borderWidth) {
        float half = borderWidth / 2;
        clipRoundRect(RoundRect.of(x + half, y + half, Math.max(0, width - borderWidth),
                Math.max(0, height - borderWidth), Math.max(0, radius - half)));
    }

    void fillCircle(float cx, float cy, float radius, Paint paint);

    void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint);

    void fillEllipse(float cx, float cy, float radiusX, float radiusY, Paint paint);

    void drawEllipse(float cx, float cy, float radiusX, float radiusY, float strokeWidth, Paint paint);

    /** Line segment with round caps. Axis-aligned lines are pixel-snapped. */
    void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint);

    /**
     * Fills a path. Each closed subpath is filled independently (see
     * {@link Path2D} for the v1 winding limitations).
     */
    void fillPath(Path2D path, Paint paint);

    /**
     * Strokes a path with round caps and joins. V1 limitation: joins are
     * overlapping round caps, so translucent paints double-blend slightly at
     * each vertex.
     */
    void drawPath(Path2D path, float strokeWidth, Paint paint);

    // ------------------------------------------------------------------ text

    /**
     * Draws a single line of text with {@code (x, y)} at the <em>baseline</em>
     * origin. Glyphs are rasterized at {@code font.size() ×} the effective
     * device scale (content scale × canvas transform) and cached in a glyph
     * atlas keyed by physical pixel size: bitmaps are never scaled, so text
     * stays sharp at 1.0/1.25/1.5/2.0. Under axis-aligned uniform transforms,
     * glyphs are snapped to the physical pixel grid.
     *
     * <p>Full code-point support, surrogate pairs included. A code point the font
     * lacks is resolved against the registered fallback faces, so mixed-script text
     * draws; a code point no face has renders as {@code .notdef}.
     *
     * <p><b>This is the shaped path with the shaping done for you.</b> A canvas that
     * has a shaper hands the string to the installed {@link TextRuler} and draws the
     * {@link ShapedText} it gets back, so contextual forms, ligatures, mark
     * attachment and bidirectional ordering are what any caption gets, not only the
     * widgets that hold a value of their own. What that costs is one memo lookup per
     * call on the ruler's side; what it buys is that the string measured by
     * {@link #measureText} and the string drawn here are the same arithmetic.
     * A caller that draws the same text every frame should still hold a
     * {@link ShapedText} and use the overload below &mdash; that is the form that
     * pays nothing at all, and the only one that carries a base direction: this
     * one resolves the paragraph from the string alone, left to right where no
     * character is strong, which is wrong exactly for the all-neutral captions a
     * widget could have decided ({@code Widget.shapeText}).
     *
     * <p>Control characters, {@code \n} included, are skipped: multi-line layout
     * belongs to the widget layer. Under anisotropic scale glyphs rasterize at the
     * larger axis and filter on the smaller one.
     */
    void drawText(String text, float x, float y, Font font, Paint paint);

    /**
     * Draws an already-shaped line with {@code (x, y)} at the <em>baseline</em> origin of its
     * <b>left</b> edge &mdash; for either base direction: right-to-left text fills the same box from
     * the other end rather than growing leftwards from {@code x}, so the run covers
     * {@code [x, x + text.metrics().width()]} and right-aligning a right-to-left paragraph is a
     * matter of choosing {@code x}. Rasterization, atlas keying and pixel snapping are exactly as
     * {@link #drawText(String, float, float, Font, Paint)} describes.
     *
     * <p>This is the form that costs nothing per frame: the shaping was already paid for, and this
     * walks {@linkplain ShapedText#runs() runs}, resolving a face once each, then glyphs. The
     * {@code String} overload above draws the same scripts equally correctly and pays a memo lookup
     * to do it; the difference is only who holds the value. There is no {@link Font} parameter
     * because the font
     * is the one the glyphs were chosen for; a font passed here could disagree with it, and the text
     * would then be measured by one face and drawn by another.
     *
     * <p>A cluster reported as {@link ShapedText#NO_GLYPH} is drawn from the shaped text's own
     * characters instead, which is how a colour-emoji strike keeps working and how a ruler that
     * cannot shape still paints. So does a run whose {@linkplain ShapedText.Run#faceId() face} this
     * canvas no longer recognizes, which is what a value shaped before an eviction becomes: a stale
     * value draws the right characters by the slower route rather than the wrong glyphs from
     * whichever face inherited the id.
     *
     * <p>The whole run draws in one {@link Paint}. Text that changes colour partway is two runs
     * today, and two runs shape independently.
     *
     * <p>Default, not abstract, for two reasons that point the same way. {@code Canvas} is a
     * published interface, and an abstract method added to one breaks every implementation that
     * exists outside this repository as well as every recording and counting canvas inside it. And
     * the honest fallback for a canvas that cannot place glyphs itself is the string the run was
     * shaped from, which is what the default draws.
     */
    default void drawText(ShapedText text, float x, float y, Paint paint) {
        drawText(text.text(), x, y, text.font(), paint);
    }

    /**
     * Measures a single line of text in logical points (baseline-relative,
     * unquantized and therefore independent of the monitor scale). Same
     * code-point rules as {@link #drawText}, and the same shaping: a canvas that
     * shapes when it draws measures what it will draw, so a caption is never laid
     * out to one width and painted at another.
     */
    TextMetrics measureText(String text, Font font);

    // ----------------------------------------------------------------- images

    /**
     * Draws {@code image} into the rectangle {@code (x, y, w, h)} in logical
     * points, scaled with linear filtering, modulated by the current
     * {@link #opacity()}. The GPU texture is created lazily on first use and
     * cached per window, within {@link Images#textureBudget()} as
     * {@link Images#setTextureBudget} describes: a texture dropped at a frame
     * boundary is made again when its image is next drawn, and none that the
     * frame just ended drew is dropped.
     */
    void drawImage(Image image, float x, float y, float w, float h);

    /** Draws {@code image} at its natural pixel size (1 pixel = 1 logical point). */
    default void drawImage(Image image, float x, float y) {
        drawImage(image, x, y, image.width(), image.height());
    }

    /**
     * Draws the source rectangle {@code (srcX, srcY, srcW, srcH)}, in
     * <em>image pixels</em>, into the destination rectangle
     * {@code (dstX, dstY, dstW, dstH)} in logical points. This is the sprite
     * primitive: frames of a sprite sheet, tiles of a tileset and regions of a
     * texture atlas all draw from one shared Image (one GPU texture, so
     * batching stays unbroken across sprites of the same sheet).
     *
     * <p>The source rectangle is not clamped: coordinates outside the image
     * sample the edge texels ({@code CLAMP_TO_EDGE}). With
     * {@link Sampling#SMOOTH}, half a texel of bleed from neighboring atlas
     * cells is possible at the edges; pack sheets with 1px gutters or use
     * {@link Sampling#PIXELATED} for exact cells. Gutters only protect mip
     * level 0: SMOOTH minification (drawing a cell well below its pixel size)
     * selects coarser mips where whole cells average together; for atlases
     * that will be minified, use {@code PIXELATED} or pad cells generously.
     */
    void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                   float dstX, float dstY, float dstW, float dstH);

    /**
     * The sprite primitive and the tint, together: one cell of a sheet, modulated by
     * {@code tint} exactly as {@link #drawImage(Image, float, float, float, float, Color)}
     * modulates a whole image.
     *
     * <p>Both halves already existed and could not be used at once, which left an
     * ordinary thing (a tinted sprite from a sheet) reachable only by splitting the
     * sheet into one Image per cell and losing the batching that is the point of a
     * sheet. Everything the source-rectangle overload says about clamping, bleed and
     * mips applies here unchanged.
     */
    void drawImage(Image image, float srcX, float srcY, float srcW, float srcH,
                   float dstX, float dstY, float dstW, float dstH, Color tint);

    /**
     * Draws {@code image} tinted by {@code tint} (its RGB and alpha are
     * multiplied by the tint's), the path for <em>modulating</em> a picture
     * (e.g. {@code ImageView.setTint}). A white opaque tint draws it unchanged.
     * A black source stays black: to recolor a mask, use {@link #drawImageMask}.
     */
    void drawImage(Image image, float x, float y, float w, float h, Color tint);

    /**
     * Draws {@code image} as a single-color <em>mask</em>: the image's alpha is
     * used as coverage and painted in {@code tint}'s color; the image's own RGB
     * is ignored. This is the icon-recolor path: a mask authored in <em>any</em>
     * color (black, white, colored) recolors cleanly to the theme, unlike
     * {@link #drawImage(Image, float, float, float, float, Color)} which
     * multiplies (so a black mask would stay black). The default falls back to
     * that multiply; backends override for a true coverage tint.
     */
    default void drawImageMask(Image image, float x, float y, float w, float h, Color tint) {
        drawImage(image, x, y, w, h, tint);
    }

    /**
     * Composites a {@link GpuSurface} (e.g. an offscreen 3D viewport) into the
     * rectangle {@code (x, y, w, h)} in logical points, as one quad in the 2D
     * paint order, so overlays, dialogs, tooltips and clipping apply to it like
     * any other content. The surface must already have been rendered this frame.
     *
     * <p>No default: it was a no-op, so a backend that did not implement it drew every
     * 3D viewport and video blank without an error. A canvas that cannot composite a GPU surface
     * (a recording or measuring canvas) implements it as a no-op, on purpose and in writing.
     */
    void drawSurface(GpuSurface surface, float x, float y, float w, float h);
}
