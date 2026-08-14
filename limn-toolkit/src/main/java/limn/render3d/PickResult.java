package limn.render3d;

import limn.math.Vec3;

/**
 * The nearest hit from {@link Picker}: the hit {@link Pickable}'s {@code tag}, the
 * ray distance, and the world-space intersection {@code point}.
 */
public record PickResult(Object tag, float distance, Vec3 point) {
}
