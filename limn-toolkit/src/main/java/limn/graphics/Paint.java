package limn.graphics;

/**
 * How a shape is filled or stroked: a solid {@link Color}; a two-colour {@link LinearGradient} or
 * {@link RadialGradient}; a {@link Gradient} of any number of colours, linear, radial or sweeping;
 * or an {@link ImagePaint}. A paint's coordinates live in the same (logical, pre-transform) space as
 * the shape being painted. Translucency comes from the alpha channel of the paint's colors, further
 * multiplied by {@link Canvas#setOpacity(float)}.
 *
 * <p>A 1.x minor release may permit more types: a {@code switch} over a paint needs a
 * {@code default} branch, and a canvas of an application's own receives a paint it may not know.
 */
public sealed interface Paint permits Color, LinearGradient, RadialGradient, Gradient, ImagePaint {
}
