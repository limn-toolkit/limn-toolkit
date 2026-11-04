package limn.backend.internal;

import limn.graphics.Path2D;
import limn.graphics.RoundRect;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * The one shape a {@link Path2D} is, when it is a lone rectangle, rounded rectangle or circle: what a
 * backend clips by without drawing the path, as it clips by a rounded rectangle. Not API.
 */
public final class PathShapes {

    private static volatile Function<Path2D, @Nullable RoundRect> reader = path -> null;

    private PathShapes() {
    }

    /** Called once, by {@link Path2D}, which alone can read its shape. */
    public static void install(Function<Path2D, @Nullable RoundRect> shapeOf) {
        reader = shapeOf;
    }

    /**
     * @return the rectangle, rounded rectangle or circle (a rounded rectangle whose radii are half
     *         its sides) {@code path} is exactly, or null when it is anything else
     */
    public static @Nullable RoundRect shapeOf(Path2D path) {
        return reader.apply(path);
    }
}
