package limn.graphics;

import limn.math.Affine2D;

import java.util.Objects;

/**
 * An image as a paint: a shape filled or stroked with it shows the image laid under it, one pixel of
 * the image to one unit of the shape's coordinates and its top-left corner at their origin, then
 * placed by {@link #transform()}, and repeated on each axis as {@link #tileX()} and {@link #tileY()}
 * say. A tiled floor, a hatch, a photograph cut to a circle, text filled with a texture.
 *
 * <pre>{@code
 * Paint hatch = ImagePaint.of(stripe).withTransform(Affine2D.rotation(Math.PI / 4).scale(0.5, 0.5));
 * canvas.fillCircle(80, 80, 60, ImagePaint.of(photo).withTile(Tile.NONE, Tile.NONE));
 * }</pre>
 *
 * <p>The image is sampled as {@link #sampling()} says, and its alpha is the paint's; a canvas's
 * opacity multiplies it, as it does every paint.
 *
 * @param image     the picture
 * @param transform where the picture stands: a point of the shape shows the image's pixel at that
 *                  point mapped back through the matrix
 * @param tileX     what lies left and right of the picture
 * @param tileY     what lies above and below it
 * @param sampling  how a pixel between the picture's is read
 */
public record ImagePaint(Image image, Affine2D transform, Tile tileX, Tile tileY, Sampling sampling)
        implements Paint {

    /**
     * What an image paint shows past the picture's edge on one axis.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Tile {
        /** The picture again, and again. */
        REPEAT,
        /** The picture again, mirrored at each edge, so its edges meet themselves. */
        MIRROR,
        /** Nothing: the picture once, and clear past it. */
        NONE
    }

    /**
     * @throws ArithmeticException for a matrix that has no inverse
     */
    public ImagePaint {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(tileX, "tileX");
        Objects.requireNonNull(tileY, "tileY");
        Objects.requireNonNull(sampling, "sampling");
        transform.invert(); // refused here, where it is given, and not where it is drawn
    }

    /** The picture repeated on both axes, from the origin, smoothly sampled. */
    public static ImagePaint of(Image image) {
        return new ImagePaint(image, Affine2D.IDENTITY, Tile.REPEAT, Tile.REPEAT, Sampling.SMOOTH);
    }

    /** This paint with the picture placed by {@code transform}. */
    public ImagePaint withTransform(Affine2D transform) {
        return new ImagePaint(image, transform, tileX, tileY, sampling);
    }

    /** This paint with {@code tileX} across and {@code tileY} down. */
    public ImagePaint withTile(Tile tileX, Tile tileY) {
        return new ImagePaint(image, transform, tileX, tileY, sampling);
    }

    /** This paint sampled as {@code sampling} says. */
    public ImagePaint withSampling(Sampling sampling) {
        return new ImagePaint(image, transform, tileX, tileY, sampling);
    }
}
