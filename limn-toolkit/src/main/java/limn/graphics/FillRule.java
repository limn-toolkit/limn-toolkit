package limn.graphics;

/**
 * Which points a path's outline encloses, counted along a ray from the point to infinity: each
 * crossing of the outline adds one where the outline crosses one way and takes one away where it
 * crosses the other. The rules are SVG's {@code fill-rule}, PDF's and the platform's.
 *
 * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum FillRule {
    /**
     * Inside where the count is not zero. Subpaths that turn the same way add up, so overlapping
     * shapes stay filled; one turning the other way inside another is a hole. The default.
     */
    NON_ZERO,
    /**
     * Inside where the count is odd. Every subpath inside another is a hole, whichever way it turns,
     * and the middle of a five-pointed star drawn as one outline is empty.
     */
    EVEN_ODD
}
