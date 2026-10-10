package limn.graphics;

import java.util.Objects;

/**
 * One layer of the shadow a shape casts: the shape's own outline moved by an offset, blurred, in
 * one colour. What {@link Canvas#drawShadow} draws.
 *
 * <p>The blur is the distance over which the shadow's edge goes from full to nothing, as in a
 * style sheet's {@code box-shadow}; zero is a hard copy of the shape.
 *
 * @param offsetX how far the shadow lies to the right of the shape, in points
 * @param offsetY how far below it
 * @param blur    the width of the soft edge, in points; zero or more
 * @param color   the shadow's colour, its alpha being how dark it is where it is whole
 */
public record Shadow(float offsetX, float offsetY, float blur, Color color) {

    public Shadow {
        Objects.requireNonNull(color, "color");
        if (!Float.isFinite(offsetX) || !Float.isFinite(offsetY) || !Float.isFinite(blur) || blur < 0) {
            throw new IllegalArgumentException("a shadow's offsets are finite and its blur zero or more");
        }
    }

    /**
     * @return how far past the shape's box this layer can leave ink, on its farthest side: what
     *         a widget drawing it adds to the region its repaints damage
     */
    public float reach() {
        return 1.5f * blur + Math.max(Math.abs(offsetX), Math.abs(offsetY));
    }
}
