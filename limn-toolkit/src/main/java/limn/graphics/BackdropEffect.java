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
     * <p>This is the cheap variant, with three samples per pixel in the rim band and no blur
     * chain, and the one that reads as glass rather than as a frosted overlay. Over busy content
     * it keeps the content legible, which frosting does not.
     *
     * <p><b>The bevel is a quarter-round and the light is actually refracted through it.</b> The
     * sine of the angle the surface makes with the pane is the distance still to go across the
     * rim, which needs no height map, and the picture behind is displaced by the lateral shift
     * through a slab of that depth. That concentration is the whole look: real glass bends almost
     * nothing across its flat middle and then very hard in the last fraction of the bevel. An
     * earlier version ramped the displacement quadratically, which is smooth everywhere and reads
     * as a soft lens.
     *
     * <p><b>No highlight rides the rim</b>, and that is a decision rather than an omission. ADR
     * 019 predicted a specular would be the cheapest thing that made this read as glass rather
     * than as a lens; built and looked at, a key light fixed in the shader reads as painted-on,
     * because it does not move when anything else in the window does. A highlight that belonged
     * here would have to come from somewhere the scene actually knows about, and the toolkit has
     * no light for a 2D canvas to ask.
     *
     * @param tint       colour over the refracted backdrop
     * @param thickness  the pane's <b>body</b>, in points: both the width of the rounded rim and
     *                   the optical depth the ray crosses, because for a bevelled pane they are
     *                   the same measurement. 0 is a flat pane that refracts nothing and catches
     *                   no highlight. A thicker pane rolls wider and carries the picture further
     *                   sideways under the roll; past half the shape's smallest side the whole
     *                   shape is bevel
     * @param dispersion how far red and blue split either side of green, 0 (none) to 1. This
     *                   spreads the <b>index</b>, not the displacement: at 1 the two ends of the
     *                   spectrum differ by about four parts in a hundred, against roughly one in
     *                   sixty for real crown glass, so even full dispersion is an exaggeration of
     *                   an optical fringe rather than a prism
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
     * <p><b>{@code saturation} is already the strength knob</b>, and there is deliberately no
     * second one beside it. A separate "amount" that blended the result back toward the original
     * would be algebra, not a control: mixing by {@code a} toward a wash of {@code s} lands on
     * exactly the wash of {@code 1 - a(1 - s)}, so every pair of values is reachable by moving
     * {@code saturation} alone. What {@code lift} adds instead is the axis saturation cannot
     * reach.
     *
     * @param tint       colour over the washed backdrop
     * @param saturation 0 turns the backdrop grey, 1 leaves it alone, above 1 boosts it. Values
     *                   above about 2 clip in the bright channels
     * @param lift       moves the backdrop's brightness before the tint goes on: 0 leaves it,
     *                   negative sinks it toward black, positive floats it toward white, and
     *                   &plusmn;1 is the whole way. This is what makes a frosted panel readable
     *                   over content it happens to match in tone, which desaturating alone
     *                   cannot do
     */
    record Wash(Color tint, float saturation, float lift) implements BackdropEffect {
        /**
         * @throws NullPointerException     if {@code tint} is null
         * @throws IllegalArgumentException if {@code saturation} is negative or {@code lift} is
         *                                  outside -1..1
         */
        public Wash {
            Objects.requireNonNull(tint, "tint");
            if (saturation < 0 || !Float.isFinite(saturation)) {
                throw new IllegalArgumentException("saturation must be >= 0, got " + saturation);
            }
            if (!(lift >= -1) || lift > 1) {
                throw new IllegalArgumentException("lift must be -1..1, got " + lift);
            }
        }

        /** A wash that only moves saturation, which is what this variant was before it lifted. */
        public Wash(Color tint, float saturation) {
            this(tint, saturation, 0f);
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

    /**
     * The backdrop blurred along <b>one axis</b>. Two of these, crossed, are a full blur.
     *
     * <p><b>Separable on purpose, and it is why this is affordable at all.</b> A true
     * two-dimensional blur of radius r costs r&sup2; samples per pixel; the same blur run
     * horizontally and then vertically costs 2r and is indistinguishable. So this variant does one
     * axis and the caller draws it twice, which works because a backdrop effect reads the
     * framebuffer and writes the framebuffer: the second pass samples what the first one left.
     *
     * <pre>{@code
     * // frosted glass: refract, then blur across, then down
     * canvas.fillBackdropRoundRect(shape, new BackdropEffect.Clear(tint, 20, 0.3f));
     * canvas.fillBackdropRoundRect(shape, new BackdropEffect.Blur(Color.TRANSPARENT, 8, Axis.X));
     * canvas.fillBackdropRoundRect(shape, new BackdropEffect.Blur(Color.TRANSPARENT, 8, Axis.Y));
     * }</pre>
     *
     * <p>Stacking is the general mechanism here, not a trick for this variant: any of these
     * effects composes with any other by being drawn over it, at one flush and one copy each.
     *
     * @param tint   colour over the blurred backdrop; usually clear on the first of a pair, so the
     *               tint is not laid down twice
     * @param radius how far the blur reaches, in <b>points</b>. 0 is no blur at all, and the pass
     *               is then exactly identity
     * @param axis   which way this pass smears; see the class note on why it is one at a time
     */
    record Blur(Color tint, float radius, Axis axis) implements BackdropEffect {

        /** Which way one blur pass runs. */
        public enum Axis {
            /** Across. */
            X,
            /** Down. */
            Y
        }

        /**
         * @throws NullPointerException     if {@code tint} or {@code axis} is null
         * @throws IllegalArgumentException if {@code radius} is negative or not finite
         */
        public Blur {
            Objects.requireNonNull(tint, "tint");
            Objects.requireNonNull(axis, "axis");
            if (!(radius >= 0) || !Float.isFinite(radius)) {
                throw new IllegalArgumentException("radius must be >= 0, got " + radius);
            }
        }
    }

    /**
     * The backdrop as a cathode-ray tube shows it: bent onto a curved face and striped by the
     * scan. A shape wearing this reads as a <em>screen inside the window</em> rather than as a
     * filter over it, which is the whole reason it is a shape effect and not a full-frame pass.
     *
     * <p><b>Both halves are anchored to the shape, not to the framebuffer</b>, and that is the
     * opposite of {@link Pixelate}. A pixelated panel anchors its grid to the framebuffer so a
     * panel that moves does not shimmer through its own cells; a tube's curvature and its scan
     * lines are properties of the tube, so they have to travel with it, and one that kept its
     * stripes while it slid would read as a hole cut in a filter.
     *
     * <p>The two pitches are locked rather than exposed: {@value #SCAN_PITCH} points for the beam
     * and {@value #GRILLE_PITCH} for the mask. In points and not pixels, so the structure is the
     * same size on a dense display as on a coarse one; and the beam <b>coarser than the mask</b>,
     * which is the ratio that makes the scan read as lines at all. At one shared pitch the two
     * patterns sum into an even mesh, which looks like a screen door laid over the picture.
     *
     * <p><b>Two parameters drive four things.</b> {@code scanline} moves the beam and the mask
     * together and {@code curvature} moves the bulge and the falloff together, because within
     * each pair the two are genuinely one thing: the glass that bends the picture is the glass
     * that dims its corners, and a beam without the mask in front of it is not a tube. Across the
     * pairs they are different hardware, which is why there are two parameters and not one.
     *
     * <p><b>Give it room.</b> Both halves are fractions of the distance from the shape's own
     * centre, so in a panel the size of a caption they move a point or two and the whole thing
     * reads as a stripe pattern laid over the picture. It wants to <em>be</em> the screen.
     *
     * @param tint      colour over the tube; usually clear, since a tint dense enough to see also
     *                  flattens the curvature it is laid over
     * @param scanline  how deep the mask cuts, 0 (none) to 1. Drives both the scan lines and the
     *                  grille: bright scan lines swell into their own gap the way a phosphor does,
     *                  so a lit area stays lit instead of being evenly darkened. Around 0.2 reads
     *                  as a tube behind glass; 0.5 and up reads as one you are close to
     * @param curvature how far the face bulges, 0 (flat) to 1, and with it how far the corners
     *                  fall away. The displacement grows with the SQUARE of the distance from the
     *                  centre, so the middle stays still and the corners move most, which is the
     *                  shape of a real tube and not a lens
     */
    record Crt(Color tint, float scanline, float curvature) implements BackdropEffect {

        /** Beam pitch in <b>points</b>: the distance between one scan line and the next. */
        public static final float SCAN_PITCH = 6f;

        /** Mask pitch in <b>points</b>: the width of one RGB triad of the aperture grille. */
        public static final float GRILLE_PITCH = 3f;

        /**
         * @throws NullPointerException     if {@code tint} is null
         * @throws IllegalArgumentException if {@code scanline} or {@code curvature} is outside 0..1
         */
        public Crt {
            Objects.requireNonNull(tint, "tint");
            if (!(scanline >= 0) || scanline > 1) {
                throw new IllegalArgumentException("scanline must be 0..1, got " + scanline);
            }
            if (!(curvature >= 0) || curvature > 1) {
                throw new IllegalArgumentException("curvature must be 0..1, got " + curvature);
            }
        }

        /** A tube with a visible scan and a gentle bulge: the default set of this toolkit. */
        public Crt(Color tint) {
            this(tint, 0.15f, 0.12f);
        }
    }
}
