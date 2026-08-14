package limn.math;

/**
 * The six clip planes of a view-projection, for frustum culling. Extracted with
 * the Gribb-Hartmann method; each plane's normal points <em>inward</em>, so a
 * point is inside when {@code n·p + d ≥ 0} for all six. The AABB test is
 * conservative: it never culls something visible, but may keep something just
 * outside a corner.
 */
public final class Frustum {

    private final Vec4[] planes; // each (a, b, c, d): inside when a*x+b*y+c*z+d >= 0

    private Frustum(Vec4[] planes) {
        this.planes = planes;
    }

    /** Builds the frustum from a view-projection matrix (clip z ∈ [-1, 1]). */
    public static Frustum fromViewProjection(Mat4 vp) {
        return new Frustum(new Vec4[]{
                plane(vp, 0, +1), // left   = row3 + row0
                plane(vp, 0, -1), // right  = row3 - row0
                plane(vp, 1, +1), // bottom = row3 + row1
                plane(vp, 1, -1), // top    = row3 - row1
                plane(vp, 2, +1), // near   = row3 + row2
                plane(vp, 2, -1), // far    = row3 - row2
        });
    }

    private static Vec4 plane(Mat4 m, int row, int sign) {
        float a = m.get(3, 0) + sign * m.get(row, 0);
        float b = m.get(3, 1) + sign * m.get(row, 1);
        float c = m.get(3, 2) + sign * m.get(row, 2);
        float d = m.get(3, 3) + sign * m.get(row, 3);
        float len = (float) Math.sqrt(a * a + b * b + c * c);
        if (len > 1e-8f) {
            a /= len;
            b /= len;
            c /= len;
            d /= len;
        }
        return new Vec4(a, b, c, d);
    }

    /**
     * @return false only when {@code box} lies fully outside the frustum.
     *         Allocation-free: it runs once per mesh per frame during culling.
     */
    public boolean intersects(Aabb box) {
        float cx = (box.min().x() + box.max().x()) * 0.5f;
        float cy = (box.min().y() + box.max().y()) * 0.5f;
        float cz = (box.min().z() + box.max().z()) * 0.5f;
        float hx = (box.max().x() - box.min().x()) * 0.5f;
        float hy = (box.max().y() - box.min().y()) * 0.5f;
        float hz = (box.max().z() - box.min().z()) * 0.5f;
        for (Vec4 p : planes) {
            float distance = p.x() * cx + p.y() * cy + p.z() * cz + p.w();
            float radius = Math.abs(p.x()) * hx + Math.abs(p.y()) * hy + Math.abs(p.z()) * hz;
            if (distance + radius < 0) {
                return false; // fully behind this plane
            }
        }
        return true;
    }
}
