package limn.graphics;

/**
 * How subsequent {@link Canvas} painting combines with what is already on the
 * target. Part of the canvas state ({@link Canvas#setBlendMode}), so it is
 * saved/restored with {@link Canvas#save()}/{@link Canvas#restore()} and
 * applies to every primitive: shapes, text, images.
 *
 * <p>The pipeline is premultiplied-alpha end to end; each mode maps to a
 * single fixed blend equation, and switching modes mid-frame is a batch
 * break (one extra draw call), so group same-mode painting where possible.
 */
public enum BlendMode {

    /** Source-over: ordinary painting, the default. */
    NORMAL,

    /**
     * Additive: source adds to the destination, for light, glow, fire, laser and
     * particle effects that brighten whatever they overlap. Black is neutral;
     * overlapping strokes accumulate toward white.
     */
    ADDITIVE,

    /**
     * Multiply: source darkens the destination, for shadows, vignettes, tint
     * layers. White is neutral; overlapping strokes accumulate toward black.
     */
    MULTIPLY
}
