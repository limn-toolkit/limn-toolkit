package limn.math;

/**
 * An axis-aligned bounding box. Used for the broadphase of picking and (later)
 * frustum culling. Build one by folding points into {@link #EMPTY} with
 * {@link #union}.
 */
public record Aabb(Vec3 min, Vec3 max) {

    /** Inverted/empty box (min = +∞, max = −∞), the identity for {@link #union}. */
    public static final Aabb EMPTY = new Aabb(
            Vec3.of(Float.POSITIVE_INFINITY), Vec3.of(Float.NEGATIVE_INFINITY));

    /** A box from explicit corners; the caller guarantees {@code min <= max} per axis. */
    public static Aabb of(Vec3 min, Vec3 max) {
        return new Aabb(min, max);
    }

    /** This box grown to contain {@code p}. */
    public Aabb union(Vec3 p) {
        return new Aabb(min.min(p), max.max(p));
    }

    /** This box grown to contain another; {@link #EMPTY} is the identity. */
    public Aabb union(Aabb o) {
        return new Aabb(min.min(o.min), max.max(o.max));
    }

    /** Whether the box is inverted on any axis, which is what {@link #EMPTY} is. */
    public boolean isEmpty() {
        return min.x() > max.x() || min.y() > max.y() || min.z() > max.z();
    }

    /** Midpoint of the box. Meaningless when {@link #isEmpty()}. */
    public Vec3 center() {
        return min.add(max).mul(0.5f);
    }

    /** Full size per axis: {@code max - min}, not the half-extent. */
    public Vec3 extent() {
        return max.sub(min);
    }

    /**
     * World-space AABB of this box transformed by an <em>affine</em> {@code m}
     * (bottom row 0,0,0,1, which every model/world matrix has). Arvo's method:
     * new center = M·c, new half-extent = |M|·h. That is equivalent to transforming
     * the 8 corners but allocation-light (runs per mesh per frame for culling). An
     * empty box stays {@link #EMPTY}.
     */
    public Aabb transformedBy(Mat4 m) {
        if (isEmpty()) {
            return EMPTY;
        }
        float cx = (min.x() + max.x()) * 0.5f;
        float cy = (min.y() + max.y()) * 0.5f;
        float cz = (min.z() + max.z()) * 0.5f;
        float hx = (max.x() - min.x()) * 0.5f;
        float hy = (max.y() - min.y()) * 0.5f;
        float hz = (max.z() - min.z()) * 0.5f;
        float ncx = m.get(0, 0) * cx + m.get(0, 1) * cy + m.get(0, 2) * cz + m.get(0, 3);
        float ncy = m.get(1, 0) * cx + m.get(1, 1) * cy + m.get(1, 2) * cz + m.get(1, 3);
        float ncz = m.get(2, 0) * cx + m.get(2, 1) * cy + m.get(2, 2) * cz + m.get(2, 3);
        float nhx = Math.abs(m.get(0, 0)) * hx + Math.abs(m.get(0, 1)) * hy + Math.abs(m.get(0, 2)) * hz;
        float nhy = Math.abs(m.get(1, 0)) * hx + Math.abs(m.get(1, 1)) * hy + Math.abs(m.get(1, 2)) * hz;
        float nhz = Math.abs(m.get(2, 0)) * hx + Math.abs(m.get(2, 1)) * hy + Math.abs(m.get(2, 2)) * hz;
        return new Aabb(new Vec3(ncx - nhx, ncy - nhy, ncz - nhz),
                new Vec3(ncx + nhx, ncy + nhy, ncz + nhz));
    }

    /**
     * Ray/box intersection (slab method). Returns the nearest non-negative
     * distance {@code t} of entry (or {@code 0} if the origin is inside), or
     * {@link Float#NaN} if the ray misses.
     */
    public float intersect(Ray ray) {
        Vec3 o = ray.origin();
        Vec3 d = ray.direction();
        float tmin = Float.NEGATIVE_INFINITY;
        float tmax = Float.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            float od = d.get(axis);
            float oo = o.get(axis);
            float lo = min.get(axis);
            float hi = max.get(axis);
            if (Math.abs(od) < 1e-8f) {
                if (oo < lo || oo > hi) {
                    return Float.NaN; // parallel and outside the slab
                }
            } else {
                float inv = 1f / od;
                float t1 = (lo - oo) * inv;
                float t2 = (hi - oo) * inv;
                if (t1 > t2) {
                    float t = t1;
                    t1 = t2;
                    t2 = t;
                }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) {
                    return Float.NaN;
                }
            }
        }
        if (tmax < 0) {
            return Float.NaN; // box entirely behind the origin
        }
        return tmin >= 0 ? tmin : 0f; // 0 when the origin is inside
    }
}
