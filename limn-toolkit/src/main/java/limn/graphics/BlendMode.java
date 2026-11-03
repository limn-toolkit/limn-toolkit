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
 * row, 3 draw calls; a heat map of 200 cells with one-pixel gaps, 9); shapes that touch or stand
 * less than a pixel apart do not, since beside a corner each one's antialiasing reaches a pixel of
 * the other (ten bars of a chart side by side, 21); ten tints that overlap, about 20 more; a line
 * of text, whose neighbouring glyphs' pictures usually share a pixel, 9 to 11 for
 * 43 characters, where outside a group it is 1.
 *
 * <p><b>The Porter-Duff rules</b>, {@link #CLEAR} to {@link #XOR}, are what a draw does to the
 * transparency of its target, and are meant inside a {@linkplain Canvas#saveLayer layer}, whose
 * picture starts transparent: an eraser ({@link #DESTINATION_OUT}) takes out of what the layer
 * drew, and a fill ({@link #SOURCE_IN}) paints only over it. Each acts where the draw covers and
 * nowhere else, and at an antialiased edge by as much as the edge covers, so a pixel the draw
 * misses keeps what it held. Outside any layer the target is the window, which is opaque: what a
 * rule leaves transparent there shows black, as on any opaque surface. On a page of a PDF a layer
 * whose drawing uses one is written as an image at the page's resolution, since PDF has no such
 * rules; outside any layer the page is that layer.
 *
 * <p>{@link #MULTIPLY}, {@link #SCREEN}, {@link #DARKEN}, {@link #LIGHTEN} and {@link #ADDITIVE}
 * change only how light a pixel is: on the window they leave its coverage as it was, and inside a
 * layer they follow the rules above for where the layer drew nothing.
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
    MULTIPLY,

    /**
     * Screen: the inverse of {@link #MULTIPLY}, the destination lightened by the source, as two
     * slides projected on one screen. Black is neutral; overlapping draws lighten toward white and
     * never past it.
     */
    SCREEN,

    /** Darken: of the source and the destination, the darker in each channel. */
    DARKEN,

    /** Lighten: of the source and the destination, the lighter in each channel. */
    LIGHTEN,

    /**
     * Porter-Duff clear: what the draw covers becomes transparent, whatever its paint. An eraser
     * whose paint does not matter.
     */
    CLEAR,

    /** Porter-Duff source: what the draw covers becomes the source, its transparency included. */
    SOURCE,

    /** Porter-Duff destination over: the source is drawn behind what is already there. */
    DESTINATION_OVER,

    /**
     * Porter-Duff source in: the source shows only where the destination is, and the destination
     * under it goes. Drawn over a shape in a layer, it fills the shape with the source.
     */
    SOURCE_IN,

    /**
     * Porter-Duff destination in: the destination stays only where the source is opaque, and as
     * much of it as the source is opaque; nothing of the source is drawn.
     */
    DESTINATION_IN,

    /** Porter-Duff source out: the source shows only where the destination is not. */
    SOURCE_OUT,

    /**
     * Porter-Duff destination out: the destination is erased where the source is, by as much as
     * the source is opaque; nothing of the source is drawn. An eraser drawn with a paint's alpha.
     */
    DESTINATION_OUT,

    /** Porter-Duff source atop: the source is drawn over the destination, only where it is. */
    SOURCE_ATOP,

    /** Porter-Duff destination atop: the destination over the source, only where the source is. */
    DESTINATION_ATOP,

    /**
     * Porter-Duff exclusive or: each shows where the other is not, and where both are, neither. The
     * rule of coverage, not a paint that inverts colours.
     */
    XOR
}
