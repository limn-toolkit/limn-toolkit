package limn.graphics;

/**
 * A themeable, resolution-independent glyph, conceptually distinct from an
 * {@link Image}, which is just a fixed grid of RGBA pixels for display. An icon
 * knows how to produce the <em>right</em> bitmap for a given device-pixel size
 * and theme brightness, and whether that bitmap is a single-color mask (recolored
 * with the theme) or a finished, pre-colored picture.
 *
 * <p>This split is why components take an {@code Icon} (buttons, labels, tabs,
 * field adornments) while style-free image widgets take a raw {@link Image}: an
 * icon adapts to DPI and theme, a picture does not.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link SvgIcon}: vector source, rasterized <em>crisply at any size</em>
 *       (no up/down-sampling blur on HiDPI), tinted to the theme.</li>
 *   <li>{@link BitmapIcon}, one or more PNGs: a monochrome/grayscale
 *       {@linkplain BitmapIcon#mask mask} to tint, {@linkplain BitmapIcon#themed
 *       light/dark variants}, or {@linkplain BitmapIcon#mask per-resolution}
 *       variants.</li>
 * </ul>
 */
public interface Icon {

    /**
     * The bitmap best suited to a {@code pixelSize}×{@code pixelSize} box measured
     * in <em>device</em> pixels, for the given theme brightness. Vector icons
     * rasterize at exactly this size (so they stay sharp on HiDPI and at any zoom);
     * bitmap icons pick their nearest available variant.
     *
     * <p>Answers on the calling thread, and has no asynchronous form on purpose:
     * {@link #paint} calls it from inside a frame, and a paint that asked for a
     * bitmap has to be given one before it can finish. An implementation whose
     * bitmap is expensive to produce carries the warm-up on its own type instead,
     * where the caller can run it ahead of the frame; {@link SvgIcon#imageAsync}
     * is the one that does.
     *
     * @param pixelSize target extent in physical pixels (≥ 1)
     * @param dark      whether the active theme is dark (selects light/dark variants)
     */
    Image image(int pixelSize, boolean dark);

    /**
     * Whether {@link #image} returns a single-color mask to be recolored with the
     * theme (true: SVG, grayscale/mono PNG), or a finished picture to draw as-is
     * (false: pre-colored light/dark variants). Governs whether {@link #paint}
     * applies its tint.
     */
    default boolean tintable() {
        return true;
    }

    /**
     * Paints this icon into a {@code size}×{@code size} <em>logical</em> box at
     * {@code (x, y)}: it requests a bitmap at the exact device resolution
     * ({@code size × }{@link Canvas#contentScale()}) so the result is crisp, then
     * applies {@code tint} when {@linkplain #tintable() tintable} (otherwise draws
     * the picture untouched). This is the one idiom every component uses to draw an
     * icon.
     */
    default void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark) {
        int px = Math.max(1, Math.round(size * canvas.contentScale()));
        Image bitmap = image(px, dark);
        // Aspect-fit, centered: the rasterizer preserves the source's aspect
        // (a 2:1 SVG comes back wider than tall), and stretching that into the
        // square box would distort it. Square bitmaps fill the box exactly.
        float scale = size / Math.max(bitmap.width(), bitmap.height());
        float w = bitmap.width() * scale;
        float h = bitmap.height() * scale;
        float dx = x + (size - w) / 2f;
        float dy = y + (size - h) / 2f;
        if (tintable() && tint != null) {
            // Coverage tint: recolors the mask to the theme regardless of the
            // source color (an SVG/PNG authored in black recolors correctly).
            canvas.drawImageMask(bitmap, dx, dy, w, h, tint);
        } else {
            canvas.drawImage(bitmap, dx, dy, w, h);
        }
    }

    /**
     * Whether an icon turns around when the interface does.
     *
     * <p>The toolkit classifies <b>nothing</b>. Only the code that placed an icon knows whether
     * its arrow means "back" (which mirrors), "download" (which does not), or is a shape inside a
     * logo (which must not). An icon pack is an application's vocabulary, and a curated list of
     * which of its glyphs are directional would be wrong for every application shipping its own.
     *
     * <p>{@link #NEVER} is the default, and the asymmetry is deliberate: a wrong {@code NEVER} is
     * one back-arrow pointing the wrong way in one place, and a wrong {@link #IN_RTL} is every
     * logo, brand mark, chart glyph and photograph in the application flipped.
     */
    enum Mirroring {
        /** Drawn as authored, whichever way the interface reads. The default. */
        NEVER,
        /** Flipped horizontally about its own box in a right-to-left subtree. */
        IN_RTL
    }

    /**
     * Paints this icon, flipped horizontally about its own box when {@code mirrored}.
     *
     * <p>The flip is a negative x scale about the destination rect, and it is the <b>one</b> legal
     * negative scale in this toolkit: it acts on a single image, never on a tree. Mirroring a
     * layout is a placement decision taken by the widget that owns the coordinate, and a transform
     * at any root would turn correctly shaped text into a mirror image, flip every picture and
     * every video frame, and put an inverse transform on the hot path of every hit test.
     *
     * <p>A caller passes {@code mirrored} as
     * {@code mirroring == Mirroring.IN_RTL && widget.layoutDirection() == RTL}: the flag says what
     * the icon means and the axis says which way the interface reads, and neither alone is the
     * answer.
     *
     * @param mirrored whether to flip; {@code false} is exactly
     *                 {@link #paint(Canvas, float, float, float, Color, boolean)}
     */
    default void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark,
                       boolean mirrored) {
        if (!mirrored) {
            paint(canvas, x, y, size, tint, dark);
            return;
        }
        // Reflected about the box's own vertical centre line, so the icon stays exactly where it
        // was placed: translate the axis to the centre, negate x, translate back.
        canvas.save();
        try {
            canvas.translate(x + size / 2f, 0);
            canvas.scale(-1, 1);
            canvas.translate(-(x + size / 2f), 0);
            paint(canvas, x, y, size, tint, dark);
        } finally {
            canvas.restore();
        }
    }
}
