package limn.math;

/**
 * A ray {@code origin + t·direction}, {@code t ≥ 0}. Used for picking. The
 * direction is stored as given (not re-normalized) so a ray transformed into a
 * model's local space keeps its {@code t} parameter consistent with world space.
 */
public record Ray(Vec3 origin, Vec3 direction) {

    /** A ray with a normalized direction. */
    public static Ray of(Vec3 origin, Vec3 direction) {
        return new Ray(origin, direction.normalize());
    }

    /** The point {@code t} units along the ray from its origin. */
    public Vec3 pointAt(float t) {
        return origin.add(direction.mul(t));
    }

    /**
     * Brings this ray into another space by {@code m} (e.g. inverse-model, to
     * test triangles in mesh-local space). Direction is transformed as a
     * direction and left un-normalized so {@code t} still maps to world distance.
     */
    public Ray transformedBy(Mat4 m) {
        return new Ray(m.transformPoint(origin), m.transformDirection(direction));
    }
}
