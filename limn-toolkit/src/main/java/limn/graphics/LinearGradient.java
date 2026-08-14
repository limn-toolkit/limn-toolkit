package limn.graphics;

import java.util.Objects;

/**
 * Two-stop linear gradient from {@code (x0,y0)} (color {@code start}) to
 * {@code (x1,y1)} (color {@code end}), clamped beyond the endpoints. Points
 * are in the same logical coordinate space as the shape being painted.
 */
public record LinearGradient(float x0, float y0, float x1, float y1, Color start, Color end) implements Paint {

    public LinearGradient {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }
}
