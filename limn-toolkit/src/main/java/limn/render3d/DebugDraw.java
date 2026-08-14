package limn.render3d;

import limn.math.Aabb;
import limn.math.Mat4;
import limn.math.Ray;
import limn.math.Vec3;
import limn.math.Vec4;

/**
 * Immediate-mode debug geometry: world-space lines (AABBs, oriented boxes,
 * axes, grids, rays) batched on the CPU and flushed into a {@link RenderPass}
 * once per frame. Purely a vertex buffer builder: no GL, so it works headless
 * and in tests; the backend draws the batch after the scene's meshes.
 *
 * <p>Two buckets: depth-tested lines are occluded by scene geometry like any
 * mesh; overlay ("X-ray") lines draw on top regardless of depth. Select with
 * {@link #depthTest(boolean)}; primitives go to the current bucket.
 *
 * <p>GC discipline: vertices accumulate into reused, doubling {@code float[]}s;
 * steady-state emission allocates nothing. The core primitives take floats;
 * {@code Vec3}/{@code Vec4} conveniences exist for call sites where clarity
 * beats the last allocation. Typical per-frame use inside a
 * {@code Viewport3D.Renderer}:
 *
 * <pre>{@code
 * debug.grid(10, 20, gray);
 * debug.axes(nodeWorld, 0.5f);
 * debug.depthTest(false).aabb(worldBounds, red);
 * debug.flush(pass); // submits both buckets and resets for the next frame
 * }</pre>
 *
 * <p>Lines are 1 px (core profile clamps {@code glLineWidth}; the viewport's
 * MSAA target antialiases them). Colors are STRAIGHT (non-premultiplied) and are
 * alpha-blended over whatever the pass has already drawn, so a faded guide really
 * does read as faded.
 */
public final class DebugDraw {

    /** Floats per vertex: x, y, z, r, g, b, a. */
    public static final int FLOATS_PER_VERTEX = 7;

    private float[] depthVertices = new float[64 * FLOATS_PER_VERTEX];
    private int depthCount; // vertices
    private float[] overlayVertices = new float[16 * FLOATS_PER_VERTEX];
    private int overlayCount; // vertices
    private boolean depthTested = true;

    /** Selects the bucket for subsequent primitives (default: depth-tested). */
    public DebugDraw depthTest(boolean on) {
        this.depthTested = on;
        return this;
    }

    /** A world-space line segment, the primitive everything else builds on. */
    public DebugDraw line(float x0, float y0, float z0, float x1, float y1, float z1,
                          float r, float g, float b, float a) {
        float[] v = reserve(2);
        int i = (depthTested ? depthCount : overlayCount) * FLOATS_PER_VERTEX;
        v[i] = x0;
        v[i + 1] = y0;
        v[i + 2] = z0;
        v[i + 3] = r;
        v[i + 4] = g;
        v[i + 5] = b;
        v[i + 6] = a;
        v[i + 7] = x1;
        v[i + 8] = y1;
        v[i + 9] = z1;
        v[i + 10] = r;
        v[i + 11] = g;
        v[i + 12] = b;
        v[i + 13] = a;
        commit(2);
        return this;
    }

    /** Queues a world-space line for this frame; cleared after it is drawn. */
    public DebugDraw line(Vec3 from, Vec3 to, Vec4 color) {
        return line(from.x(), from.y(), from.z(), to.x(), to.y(), to.z(),
                color.x(), color.y(), color.z(), color.w());
    }

    /** The 12 edges of an axis-aligned box. Empty boxes draw nothing. */
    public DebugDraw aabb(Aabb box, Vec4 color) {
        if (box.isEmpty()) {
            return this;
        }
        float x0 = box.min().x(), y0 = box.min().y(), z0 = box.min().z();
        float x1 = box.max().x(), y1 = box.max().y(), z1 = box.max().z();
        float r = color.x(), g = color.y(), b = color.z(), a = color.w();
        // Bottom rectangle, top rectangle, four verticals.
        line(x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(x0, y0, z1, x0, y0, z0, r, g, b, a);
        line(x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(x0, y1, z1, x0, y1, z0, r, g, b, a);
        line(x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(x0, y0, z1, x0, y1, z1, r, g, b, a);
        return this;
    }

    /**
     * The 12 edges of {@code box} transformed by an affine {@code transform},
     * an oriented box (a mesh's local bounds under its world matrix), unlike
     * {@code aabb(box.transformedBy(m), …)} which draws the axis-aligned hull
     * the culler uses. Empty boxes draw nothing.
     */
    public DebugDraw obb(Aabb box, Mat4 transform, Vec4 color) {
        if (box.isEmpty()) {
            return this;
        }
        // Transform the 8 corners with primitive math (affine: no divide).
        float r = color.x(), g = color.y(), b = color.z(), a = color.w();
        float m00 = transform.get(0, 0), m01 = transform.get(0, 1), m02 = transform.get(0, 2), m03 = transform.get(0, 3);
        float m10 = transform.get(1, 0), m11 = transform.get(1, 1), m12 = transform.get(1, 2), m13 = transform.get(1, 3);
        float m20 = transform.get(2, 0), m21 = transform.get(2, 1), m22 = transform.get(2, 2), m23 = transform.get(2, 3);
        float[] corners = cornerScratch;
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? box.min().x() : box.max().x();
            float y = (i & 2) == 0 ? box.min().y() : box.max().y();
            float z = (i & 4) == 0 ? box.min().z() : box.max().z();
            corners[i * 3] = m00 * x + m01 * y + m02 * z + m03;
            corners[i * 3 + 1] = m10 * x + m11 * y + m12 * z + m13;
            corners[i * 3 + 2] = m20 * x + m21 * y + m22 * z + m23;
        }
        for (int[] edge : BOX_EDGES) {
            int c0 = edge[0] * 3;
            int c1 = edge[1] * 3;
            line(corners[c0], corners[c0 + 1], corners[c0 + 2],
                    corners[c1], corners[c1 + 1], corners[c1 + 2], r, g, b, a);
        }
        return this;
    }

    // Corner index bit i selects max on axis i (x=1, y=2, z=4); edges connect
    // corners differing in exactly one bit.
    private static final int[][] BOX_EDGES = {
            {0, 1}, {2, 3}, {4, 5}, {6, 7}, // x edges
            {0, 2}, {1, 3}, {4, 6}, {5, 7}, // y edges
            {0, 4}, {1, 5}, {2, 6}, {3, 7}, // z edges
    };
    private final float[] cornerScratch = new float[24];

    /**
     * The basis of an affine {@code transform} drawn from its origin: X red,
     * Y green, Z blue, each {@code size} long (in the transform's own scale).
     */
    public DebugDraw axes(Mat4 transform, float size) {
        float ox = transform.get(0, 3);
        float oy = transform.get(1, 3);
        float oz = transform.get(2, 3);
        line(ox, oy, oz,
                ox + transform.get(0, 0) * size, oy + transform.get(1, 0) * size, oz + transform.get(2, 0) * size,
                1, 0.2f, 0.2f, 1);
        line(ox, oy, oz,
                ox + transform.get(0, 1) * size, oy + transform.get(1, 1) * size, oz + transform.get(2, 1) * size,
                0.2f, 1, 0.2f, 1);
        line(ox, oy, oz,
                ox + transform.get(0, 2) * size, oy + transform.get(1, 2) * size, oz + transform.get(2, 2) * size,
                0.3f, 0.5f, 1, 1);
        return this;
    }

    /**
     * A square grid on the XZ plane at y=0, centered at the origin:
     * {@code divisions}×{@code divisions} cells spanning ±{@code halfExtent}.
     */
    public DebugDraw grid(float halfExtent, int divisions, Vec4 color) {
        float r = color.x(), g = color.y(), b = color.z(), a = color.w();
        float step = 2 * halfExtent / Math.max(1, divisions);
        for (int i = 0; i <= divisions; i++) {
            float t = -halfExtent + i * step;
            line(t, 0, -halfExtent, t, 0, halfExtent, r, g, b, a);
            line(-halfExtent, 0, t, halfExtent, 0, t, r, g, b, a);
        }
        return this;
    }

    /** A ray drawn {@code length} long from its origin. */
    public DebugDraw ray(Ray ray, float length, Vec4 color) {
        Vec3 o = ray.origin();
        Vec3 d = ray.direction();
        return line(o.x(), o.y(), o.z(),
                o.x() + d.x() * length, o.y() + d.y() * length, o.z() + d.z() * length,
                color.x(), color.y(), color.z(), color.w());
    }

    /**
     * Submits both buckets to the pass and resets for the next frame. The
     * arrays are handed to the backend by reference and consumed before this
     * pass's render call returns; call once, at the end of frame emission.
     */
    public void flush(RenderPass pass) {
        if (depthCount > 0) {
            pass.debugLines(depthVertices, depthCount, true);
        }
        if (overlayCount > 0) {
            pass.debugLines(overlayVertices, overlayCount, false);
        }
        clear();
    }

    /** Drops all emitted vertices (buckets stay allocated for reuse). */
    public void clear() {
        depthCount = 0;
        overlayCount = 0;
        depthTested = true;
    }

    /** @return vertices currently held in the given bucket (for tests/HUDs) */
    public int vertexCount(boolean depthTestedBucket) {
        return depthTestedBucket ? depthCount : overlayCount;
    }

    private float[] reserve(int vertices) {
        float[] v = depthTested ? depthVertices : overlayVertices;
        int used = (depthTested ? depthCount : overlayCount) * FLOATS_PER_VERTEX;
        int needed = used + vertices * FLOATS_PER_VERTEX;
        if (needed > v.length) {
            int grown = Math.max(needed, v.length * 2);
            v = java.util.Arrays.copyOf(v, grown);
            if (depthTested) {
                depthVertices = v;
            } else {
                overlayVertices = v;
            }
        }
        return v;
    }

    private void commit(int vertices) {
        if (depthTested) {
            depthCount += vertices;
        } else {
            overlayCount += vertices;
        }
    }
}
