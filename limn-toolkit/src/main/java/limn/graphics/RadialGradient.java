package limn.graphics;

import java.util.Objects;

/**
 * Two-stop radial gradient: {@code center} color at {@code (cx,cy)} fading to
 * {@code edge} color at distance {@code radius}, clamped beyond it. Points are
 * in the same logical coordinate space as the shape being painted.
 */
public record RadialGradient(float cx, float cy, float radius, Color center, Color edge) implements Paint {

    public RadialGradient {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(edge, "edge");
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be > 0, got " + radius);
        }
    }
}
