package limn.graphics;

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
 * by {@code restore()}.
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
     * Sets the current state's opacity multiplier in [0..1]; every subsequent
     * paint's alpha is multiplied by it. Restored by {@link #restore()}.
     */
    void setOpacity(float opacity);

    float opacity();

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
     * Intersects the clip with a rounded rectangle. V1 limitations: the clip
     * state tracks one rounded rect, so nested rounded clips intersect their
     * rectangles exactly, but only the most recent corner radius applies, as
     * a single uniform radius (the max of the four corners, clamped to the
     * clip region's half extents); rotation degrades to the AABB like
     * {@link #clipRect(float, float, float, float)}.
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
     * practical necessity: the near-universal border idiom
     * {@code drawRoundRect(0.5f, 0.5f, width() - 1, height() - 1, …)} produces a
     * negative size the moment a widget is laid out under 1 pt, which happens
     * routinely while a window is being resized small. Explicitly building a
     * {@link RoundRect} still validates, so genuinely malformed geometry (say,
     * swapped corners) is still caught at construction.
     */
    default void drawRoundRect(float x, float y, float width, float height, float radius,
                               float strokeWidth, Paint paint) {
        if (width <= 0 || height <= 0) {
            return;
        }
        drawRoundRect(RoundRect.of(x, y, width, height, radius), strokeWidth, paint);
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
     * pays nothing at all.
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
     * cached per window.
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
     * The default is a no-op (surfaces need a GPU backend); backends override.
     */
    default void drawSurface(GpuSurface surface, float x, float y, float w, float h) {
    }
}
