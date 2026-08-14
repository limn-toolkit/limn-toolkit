package limn.render3d;

import limn.math.Vec3;

/**
 * An analytic light for the PBR pass, added to a {@link RenderPass} with
 * {@link RenderPass#addLight}. Colors are linear; {@code intensity} scales them.
 * Unlit/SimpleLit materials ignore these (they read {@link RenderPass#light}).
 */
public sealed interface Light permits Light.Directional, Light.Point, Light.Spot {

    Vec3 color();

    float intensity();

    /** An infinitely distant light (sun): {@code direction} points <em>toward</em> the source. */
    record Directional(Vec3 direction, Vec3 color, float intensity) implements Light {
        /** A light with no position; {@code direction} is where it shines towards. */
    public static Directional of(Vec3 direction, Vec3 color) {
            return new Directional(direction, color, 1f);
        }
    }

    /**
     * An omnidirectional light at {@code position} with inverse-square falloff.
     * {@code range} &gt; 0 applies a smooth windowing cutoff at that distance;
     * {@code range} &le; 0 means unbounded (pure inverse-square).
     */
    record Point(Vec3 position, Vec3 color, float intensity, float range) implements Light {
        /** A positioned light falling off with distance. */
    public static Point of(Vec3 position, Vec3 color, float intensity) {
            return new Point(position, color, intensity, 0f);
        }
    }

    /**
     * A cone light at {@code position} aimed along {@code direction} (pointing away
     * from the light). Full intensity within {@code innerAngleRadians}, falling to
     * zero at {@code outerAngleRadians}; {@code range} as in {@link Point}.
     */
    record Spot(Vec3 position, Vec3 direction, Vec3 color, float intensity, float range,
                float innerAngleRadians, float outerAngleRadians) implements Light {
    }
}
