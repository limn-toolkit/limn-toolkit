package limn.graphics;

import java.util.Objects;

/**
 * What a shape does to the pixels already behind it: a material, not a paint. Passed to
 * {@link Canvas#fillBackdropRoundRect}, which re-samples what the frame has drawn so far under the
 * shape, transforms it, and lays the {@link #tint()} over the result.
 *
 * <p>A <b>closed</b> set, unlike {@link ImageFormat}, and the asymmetry is the point: a format is
 * contributed by whichever encoder is installed, whereas an effect exists only if the renderer has
 * a branch for it. An open type would let a caller name an effect that nothing can draw, and the
 * failure would be a blank rectangle rather than a message.
 *
 * <p>Every variant carries a tint, because glass is never purely the thing behind it, and because
 * the tint is the documented fallback: a renderer with no backdrop support fills the shape with it
 * and the layout is still right, which is what {@link Canvas#fillBackdropRoundRect} does by
 * default.
 *
 * <p>Sizes here are in <b>logical points</b>, like every other dimension a widget passes to a
 * canvas; the renderer converts them using the transform in force.
 */
public sealed interface BackdropEffect {

    /**
     * The colour laid over the transformed backdrop, straight (not premultiplied) alpha. An alpha
     * of 0 shows the backdrop alone; 1 hides it entirely, which is a plain fill drawn the expensive
     * way.
     */
    Color tint();

    /**
     * A transparent pane: the backdrop is not blurred, it is <b>refracted</b> at the rim, the way
     * light bends through the bevelled edge of real glass. The shape's own signed-distance field is
     * the surface, so the effect follows every corner radius exactly and costs no extra geometry.
     *
     * <p>This is the cheap variant, with one sample per pixel (three where {@code dispersion} is
     * non-zero) and no blur chain, and the one that reads as glass rather than as a frosted
     * overlay. Over busy content it keeps the content legible, which frosting does not.
     *
     * @param tint       colour over the refracted backdrop
     * @param thickness  width of the refracting rim in <b>points</b>, measured inward from the
     *                   edge; 0 is a flat pane with no refraction at all. Displacement peaks at
     *                   the edge and falls to nothing at {@code thickness} inside it, so a value
     *                   larger than half the shape's smallest side bends the whole shape
     * @param dispersion how far the red and blue channels refract either side of green, 0 (none)
     *                   to 1. Small values read as an optical fringe; large ones as a prism
     */
    record Clear(Color tint, float thickness, float dispersion) implements BackdropEffect {
        /**
         * @throws NullPointerException     if {@code tint} is null
         * @throws IllegalArgumentException if {@code thickness} is negative or {@code dispersion}
         *                                  is outside 0..1
         */
        public Clear {
            Objects.requireNonNull(tint, "tint");
            if (thickness < 0 || !Float.isFinite(thickness)) {
                throw new IllegalArgumentException("thickness must be >= 0, got " + thickness);
            }
            if (dispersion < 0 || dispersion > 1) {
                throw new IllegalArgumentException("dispersion must be 0..1, got " + dispersion);
            }
        }

        /** A pane with a 12pt rim and a light optical fringe: the default glass of this toolkit. */
        public Clear(Color tint) {
            this(tint, 12f, 0.35f);
        }
    }

    /**
     * The backdrop, undisplaced, with its saturation moved. No blur and no refraction: one sample
     * per pixel, so it is the effect to reach for over video, where a blur costs bandwidth every
     * frame and a displacement fights the motion.
     *
     * @param tint       colour over the washed backdrop
     * @param saturation 0 turns the backdrop grey, 1 leaves it alone, above 1 boosts it. Values
     *                   above about 2 clip in the bright channels
     */
    record Wash(Color tint, float saturation) implements BackdropEffect {
        /**
         * @throws NullPointerException     if {@code tint} is null
         * @throws IllegalArgumentException if {@code saturation} is negative
         */
        public Wash {
            Objects.requireNonNull(tint, "tint");
            if (saturation < 0 || !Float.isFinite(saturation)) {
                throw new IllegalArgumentException("saturation must be >= 0, got " + saturation);
            }
        }
    }

    /**
     * The backdrop reduced to squares: redaction, not decoration. Each cell samples one point, so
     * what the cell hid cannot be recovered from the output: this is the variant to put over a
     * field before a screenshot, and the reason it exists in a toolkit at all.
     *
     * @param tint colour over the pixelated backdrop; usually clear or barely tinted, since a tint
     *             that hides the blocks also hides that something was hidden
     * @param cell cell edge in <b>points</b>, at least 1. The grid is aligned to the framebuffer,
     *             not to the shape, so a panel that moves does not shimmer through its own cells
     */
    record Pixelate(Color tint, float cell) implements BackdropEffect {
        /**
         * @throws NullPointerException     if {@code tint} is null
         * @throws IllegalArgumentException if {@code cell} is below 1
         */
        public Pixelate {
            Objects.requireNonNull(tint, "tint");
            if (!(cell >= 1) || !Float.isFinite(cell)) {
                throw new IllegalArgumentException("cell must be >= 1 point, got " + cell);
            }
        }
    }
}
