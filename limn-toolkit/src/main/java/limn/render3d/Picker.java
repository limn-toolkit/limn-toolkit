package limn.render3d;

import limn.math.Aabb;
import limn.math.Mat4;
import limn.math.Ray;
import limn.math.Vec3;

import java.util.List;

/**
 * CPU ray picking. For each {@link Pickable}: a world-space AABB broadphase, then
 * the ray is transformed into mesh-local space and tested triangle-by-triangle
 * (Möller–Trumbore). Returns the nearest hit, or {@code null} for a miss. Good for
 * up to thousands of objects; a GPU id-buffer path can come later for scale.
 */
public final class Picker {

    private Picker() {
    }

    /** The nearest hit along the ray, or a miss when nothing intersects. */
    public static PickResult pick(Ray worldRay, List<Pickable> pickables) {
        PickResult best = null;
        for (Pickable p : pickables) {
            Aabb worldBox = p.mesh().bounds().transformedBy(p.transform());
            if (Float.isNaN(worldBox.intersect(worldRay))) {
                continue; // broadphase miss
            }
            Mat4 inverseModel = p.transform().invert();
            Ray localRay = worldRay.transformedBy(inverseModel);
            float t = nearestTriangle(localRay, p.mesh());
            if (!Float.isNaN(t) && (best == null || t < best.distance())) {
                best = new PickResult(p.tag(), t, worldRay.pointAt(t));
            }
        }
        return best;
    }

    private static float nearestTriangle(Ray ray, MeshData mesh) {
        float[] pos = mesh.get(VertexAttribute.POSITION);
        if (pos == null) {
            return Float.NaN;
        }
        int[] idx = mesh.indices();
        float best = Float.NaN;
        for (int i = 0; i + 2 < idx.length; i += 3) {
            float t = rayTriangle(ray, vertex(pos, idx[i]), vertex(pos, idx[i + 1]), vertex(pos, idx[i + 2]));
            if (!Float.isNaN(t) && (Float.isNaN(best) || t < best)) {
                best = t;
            }
        }
        return best;
    }

    private static Vec3 vertex(float[] pos, int i) {
        return new Vec3(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]);
    }

    /** Möller–Trumbore; returns the ray {@code t} of the hit or {@link Float#NaN}. */
    private static float rayTriangle(Ray ray, Vec3 v0, Vec3 v1, Vec3 v2) {
        Vec3 o = ray.origin();
        Vec3 d = ray.direction();
        Vec3 edge1 = v1.sub(v0);
        Vec3 edge2 = v2.sub(v0);
        Vec3 pvec = d.cross(edge2);
        float det = edge1.dot(pvec);
        if (Math.abs(det) < 1e-8f) {
            return Float.NaN; // parallel
        }
        float inv = 1f / det;
        Vec3 tvec = o.sub(v0);
        float u = tvec.dot(pvec) * inv;
        if (u < 0 || u > 1) {
            return Float.NaN;
        }
        Vec3 qvec = tvec.cross(edge1);
        float v = d.dot(qvec) * inv;
        if (v < 0 || u + v > 1) {
            return Float.NaN;
        }
        float t = edge2.dot(qvec) * inv;
        return t > 1e-6f ? t : Float.NaN;
    }
}
