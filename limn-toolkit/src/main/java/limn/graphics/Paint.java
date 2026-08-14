package limn.graphics;

/**
 * How a shape is filled or stroked: a solid {@link Color} or a
 * {@link LinearGradient}/{@link RadialGradient}. Gradient coordinates live in
 * the same (logical, pre-transform) space as the shape being painted.
 * Translucency comes from the alpha channel of the paint's colors, further
 * multiplied by {@link Canvas#setOpacity(float)}.
 */
public sealed interface Paint permits Color, LinearGradient, RadialGradient {
}
