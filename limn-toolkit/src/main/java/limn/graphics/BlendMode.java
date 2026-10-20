package limn.graphics;

/**
 * How subsequent {@link Canvas} painting combines with what is already on the
 * target. Part of the canvas state ({@link Canvas#setBlendMode}), so it is
 * saved/restored with {@link Canvas#save()}/{@link Canvas#restore()} and
 * applies to every primitive: shapes, text, images.
 *
 * <p>The pipeline is premultiplied-alpha end to end; each mode maps to a
 * fixed blend equation, and switching modes mid-frame is a batch
 * break (one extra draw call), so group same-mode painting where possible.
 *
 * <p>Inside a group drawn as one picture, an {@code Opacity} container below 1 or a
 * {@linkplain Canvas#saveLayer layer}, a mode combines with what the group itself drew, and where it
 * drew nothing the source is drawn as itself, as an isolated group of a web page has it; outside any
 * group it combines with the window. A multiplied tint inside a fading container therefore shows
 * as itself while the container fades and multiplies what is beneath once the container is at 1.
 * Inside a group {@link #MULTIPLY} is drawn in two passes, of which the second reads what the first
 * left, so draws in it share their two draw calls only while no two of them can cover the same
 * pixel, sixty-four at most; each draw that can takes two of its own. What that costs on every frame
 * that draws the group as a picture, while it fades or while it is turned, and never at rest, as
 * measured on a graphics card: shapes a pixel apart or more share (ten rectangles or circles in a
 * row, 3 draw calls; a heat map of 200 cells with one-pixel gaps, 9); ten tints that overlap, about
 * 20 more; a line of text, whose neighbouring glyphs' pictures usually share a pixel, 9 to 11 for
 * 43 characters, where outside a group it is 1.
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
