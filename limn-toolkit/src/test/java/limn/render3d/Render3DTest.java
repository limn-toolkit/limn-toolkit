package limn.render3d;

import limn.math.Aabb;
import limn.math.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless checks for the neutral 3D core: mesh data, primitives, camera. */
class Render3DTest {

    private static final float EPS = 1e-4f;

    @Test
    void meshDataRejectsMismatchedVertexCounts() {
        MeshData mesh = new MeshData().put(VertexAttribute.POSITION, new float[]{0, 0, 0, 1, 1, 1});
        assertEquals(2, mesh.vertexCount());
        assertThrows(IllegalArgumentException.class,
                () -> mesh.put(VertexAttribute.NORMAL, new float[]{0, 1, 0})); // 1 vertex ≠ 2
    }

    @Test
    void cubePrimitiveHasExpectedCountsAndBounds() {
        MeshData cube = Primitives.cube(2);
        assertEquals(24, cube.vertexCount()); // 4 per face for per-face normals
        assertEquals(36, cube.indices().length);
        assertTrue(cube.has(VertexAttribute.NORMAL));
        Aabb b = cube.bounds();
        assertVec(new Vec3(-1, -1, -1), b.min());
        assertVec(new Vec3(1, 1, 1), b.max());
    }

    @Test
    void spherePrimitiveHasUnitNormalsAndRadiusBounds() {
        MeshData sphere = Primitives.sphere(2f, 8, 12);
        Aabb b = sphere.bounds();
        // Bounds reach ~±radius on every axis.
        assertEquals(2, b.max().y(), 1e-2f);
        assertEquals(-2, b.min().y(), 1e-2f);
        float[] n = sphere.get(VertexAttribute.NORMAL);
        for (int i = 0; i + 2 < n.length; i += 3) {
            float len = new Vec3(n[i], n[i + 1], n[i + 2]).length();
            assertEquals(1, len, 1e-3f, "normals are unit length");
        }
    }

    @Test
    void renderTargetExposureDefaultsToNeutral() {
        // ADR 004 §3.4: exposure rides on the target so the composite's display
        // transform can read it. A target no pass has rendered into must present
        // neutral exposure; anything else would scale (or black out) the first
        // composite of a fresh target.
        RenderTarget bare = new RenderTarget() {
            @Override
            public int widthPx() {
                return 1;
            }

            @Override
            public int heightPx() {
                return 1;
            }

            @Override
            public void resize(int widthPx, int heightPx) {
            }

            @Override
            public void dispose() {
            }

            @Override
            public int samples() {
                return 1;
            }

            @Override
            public limn.backend.RenderStats stats() {
                return limn.backend.RenderStats.EMPTY;
            }

            @Override
            public limn.graphics.Image readDisplayReferred(int x, int y, int w, int h) {
                throw new UnsupportedOperationException();
            }

            @Override
            public limn.graphics.ScenePixels readSceneReferred(int x, int y, int w, int h) {
                throw new UnsupportedOperationException();
            }
        };
        assertEquals(1f, bare.exposure(), 0f);
    }

    @Test
    void cameraViewPlacesEyeAtOrigin() {
        Camera cam = new Camera().eye(new Vec3(0, 0, 6)).target(Vec3.ZERO);
        assertVec(Vec3.ZERO, cam.view().transformPoint(new Vec3(0, 0, 6)));
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPS, "x");
        assertEquals(expected.y(), actual.y(), EPS, "y");
        assertEquals(expected.z(), actual.z(), EPS, "z");
    }
}
