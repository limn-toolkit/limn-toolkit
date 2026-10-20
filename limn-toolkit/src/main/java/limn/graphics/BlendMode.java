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
 *
 * <p>Inside a group drawn as one picture, an {@code Opacity} container below 1 or a
 * {@linkplain Canvas#saveLayer layer}, a mode combines with what the group itself drew, and where it
 * drew nothing the source is drawn as itself, as an isolated group of a web page has it; outside any
 * group it combines with the window. A multiplied tint inside a fading container therefore shows
 * as itself while the container fades and multiplies what is beneath once the container is at 1.
 *
 * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
 * {@code default} branch.
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
