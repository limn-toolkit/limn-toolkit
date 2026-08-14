package limn.render3d;

import limn.math.Aabb;
import limn.math.Mat4;
import limn.math.Quat;
import limn.math.Ray;
import limn.math.Vec3;
import limn.math.Vec4;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The debug-line batch builder: vertex layout, bucket routing, primitive vertex
 * counts, growth without data loss, and the flush/clear contract, all CPU-side
 * (the backend only ever sees the flushed arrays).
 */
class DebugDrawTest {

    private static final float EPS = 1e-4f;
    private static final Vec4 RED = new Vec4(1, 0, 0, 1);

    /** Captures debugLines submissions; every other RenderPass method is a stub. */
    private static final class RecordingPass implements RenderPass {
        record Batch(float[] vertices, int vertexCount, boolean depthTested) {
        }

        final List<Batch> batches = new ArrayList<>();

        @Override
        public void debugLines(float[] vertices, int vertexCount, boolean depthTested) {
            batches.add(new Batch(vertices, vertexCount, depthTested));
        }

        @Override
        public RenderPass clear(float r, float g, float b, float a) {
            return this;
        }

        @Override
        public RenderPass light(Vec3 direction, Vec3 color, float ambient) {
            return this;
        }

        @Override
        public RenderPass addLight(Light light) {
            return this;
        }

        @Override
        public RenderPass ambient(Vec3 color) {
            return this;
        }

        @Override
        public RenderPass exposure(float exposure) {
            return this;
        }

        @Override
        public RenderPass shadow(Vec3 direction, Vec3 center, float radius) {
            return this;
        }

        @Override
        public RenderPass environment(Environment environment, IrradianceSh irradiance) {
            return this;
        }

        @Override
        public void draw(GpuMesh mesh, Material material, Mat4 model) {
        }
    }

    @Test
    void lineWritesInterleavedPositionAndColor() {
        DebugDraw debug = new DebugDraw();
        debug.line(1, 2, 3, 4, 5, 6, 0.1f, 0.2f, 0.3f, 0.4f);
        assertEquals(2, debug.vertexCount(true));

        RecordingPass pass = new RecordingPass();
        debug.flush(pass);
        assertEquals(1, pass.batches.size());
        float[] v = pass.batches.get(0).vertices();
        assertEquals(1, v[0], EPS);
        assertEquals(2, v[1], EPS);
        assertEquals(3, v[2], EPS);
        assertEquals(0.1f, v[3], EPS);
        assertEquals(0.4f, v[6], EPS);
        assertEquals(4, v[7], EPS);
        assertEquals(6, v[9], EPS);
    }

    @Test
    void depthTestSelectsTheBucket() {
        DebugDraw debug = new DebugDraw();
        debug.line(Vec3.ZERO, Vec3.UNIT_X, RED);
        debug.depthTest(false).line(Vec3.ZERO, Vec3.UNIT_Y, RED);
        assertEquals(2, debug.vertexCount(true));
        assertEquals(2, debug.vertexCount(false));

        RecordingPass pass = new RecordingPass();
        debug.flush(pass);
        assertEquals(2, pass.batches.size());
        assertTrue(pass.batches.get(0).depthTested());
        assertTrue(!pass.batches.get(1).depthTested());
    }

    @Test
    void primitivesEmitTheExpectedVertexCounts() {
        DebugDraw debug = new DebugDraw();
        Aabb box = new Aabb(new Vec3(-1, -1, -1), new Vec3(1, 1, 1));
        debug.aabb(box, RED);
        assertEquals(24, debug.vertexCount(true), "an AABB is 12 edges");
        debug.clear();

        debug.obb(box, Mat4.identity(), RED);
        assertEquals(24, debug.vertexCount(true), "an OBB is 12 edges");
        debug.clear();

        debug.axes(Mat4.identity(), 1);
        assertEquals(6, debug.vertexCount(true), "axes are 3 lines");
        debug.clear();

        debug.grid(5, 10, RED);
        assertEquals((11 + 11) * 2, debug.vertexCount(true), "n divisions = n+1 lines each way");
        debug.clear();

        debug.ray(new Ray(Vec3.ZERO, Vec3.UNIT_Z), 4, RED);
        assertEquals(2, debug.vertexCount(true));
    }

    @Test
    void emptyBoxesEmitNothing() {
        DebugDraw debug = new DebugDraw();
        debug.aabb(Aabb.EMPTY, RED);
        debug.obb(Aabb.EMPTY, Mat4.identity(), RED);
        assertEquals(0, debug.vertexCount(true));
    }

    @Test
    void obbTransformsTheCornersNotTheHull() {
        // 90° about Z maps the +X edge direction onto +Y: an oriented box keeps
        // its shape (corners at ±1), unlike the axis-aligned hull of a rotated box.
        DebugDraw debug = new DebugDraw();
        Aabb box = new Aabb(new Vec3(-1, -0.5f, 0), new Vec3(1, 0.5f, 0));
        Mat4 rot = Mat4.rotation(Quat.fromAxisAngle(Vec3.UNIT_Z, (float) Math.PI / 2));
        debug.obb(box, rot, RED);

        RecordingPass pass = new RecordingPass();
        debug.flush(pass);
        float[] v = pass.batches.get(0).vertices();
        int count = pass.batches.get(0).vertexCount();
        float maxAbsX = 0;
        float maxAbsY = 0;
        for (int i = 0; i < count; i++) {
            maxAbsX = Math.max(maxAbsX, Math.abs(v[i * DebugDraw.FLOATS_PER_VERTEX]));
            maxAbsY = Math.max(maxAbsY, Math.abs(v[i * DebugDraw.FLOATS_PER_VERTEX + 1]));
        }
        assertEquals(0.5f, maxAbsX, EPS, "the box's short side now spans x");
        assertEquals(1f, maxAbsY, EPS, "the box's long side now spans y");
    }

    @Test
    void growthPreservesEarlierVertices() {
        DebugDraw debug = new DebugDraw();
        int lines = 500; // well past the initial capacity
        for (int i = 0; i < lines; i++) {
            debug.line(i, 0, 0, i, 1, 0, 1, 1, 1, 1);
        }
        assertEquals(lines * 2, debug.vertexCount(true));
        RecordingPass pass = new RecordingPass();
        debug.flush(pass);
        float[] v = pass.batches.get(0).vertices();
        for (int i = 0; i < lines; i++) {
            assertEquals(i, v[i * 2 * DebugDraw.FLOATS_PER_VERTEX], EPS, "line " + i);
        }
    }

    @Test
    void flushResetsForTheNextFrame() {
        DebugDraw debug = new DebugDraw();
        debug.depthTest(false).line(Vec3.ZERO, Vec3.UNIT_X, RED);
        debug.flush(new RecordingPass());
        assertEquals(0, debug.vertexCount(true));
        assertEquals(0, debug.vertexCount(false));

        // After flush the bucket selector is back to depth-tested.
        debug.line(Vec3.ZERO, Vec3.UNIT_X, RED);
        assertEquals(2, debug.vertexCount(true));

        RecordingPass empty = new RecordingPass();
        debug.clear();
        debug.flush(empty);
        assertTrue(empty.batches.isEmpty(), "empty buckets are not submitted");
    }
}
