package limn.render3d.scene;

import limn.math.Aabb;
import limn.math.Mat4;
import limn.math.Transform3D;
import limn.math.Vec3;
import limn.render3d.Camera;
import limn.render3d.Environment;
import limn.render3d.GpuMesh;
import limn.render3d.IrradianceSh;
import limn.render3d.Light;
import limn.render3d.Material;
import limn.render3d.RenderPass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retained-graph traversal: world-transform composition, frustum culling, light transforms. */
class SceneGraphTest {

    private static final Aabb UNIT = Aabb.of(new Vec3(-0.5f, -0.5f, -0.5f), new Vec3(0.5f, 0.5f, 0.5f));
    private static final Material RED = Material.Pbr.of(1f, 0f, 0f);

    /** A GL-free mesh stub: culling only needs its object-space bounds. */
    private static GpuMesh mesh() {
        return new GpuMesh() {
            @Override
            public Aabb bounds() {
                return UNIT;
            }

            @Override
            public void dispose() {
            }
        };
    }

    private static Camera camera() {
        return new Camera().eye(new Vec3(0, 0, 5)).target(Vec3.ZERO);
    }

    @Test
    void boundsCoverEveryMeshInWorldSpace() {
        Scene3D scene = new Scene3D();
        scene.root().add(new MeshNode(mesh(), RED))
                .transform(Transform3D.at(new Vec3(10, 0, 0)));
        scene.root().add(new MeshNode(mesh(), RED))
                .transform(Transform3D.at(new Vec3(-10, 0, 0)));

        Aabb box = scene.bounds();
        assertEquals(-10.5f, box.min().x(), 1e-4f);
        assertEquals(10.5f, box.max().x(), 1e-4f);
        assertEquals(0f, box.center().x(), 1e-4f, "the two cancel out");
        assertEquals(21f, box.extent().x(), 1e-4f, "extent is the full span, not the half");
    }

    @Test
    void boundsFollowANestedTransform() {
        Scene3D scene = new Scene3D();
        Node group = scene.root().add(new Node()).transform(Transform3D.at(new Vec3(0, 4, 0)));
        group.add(new MeshNode(mesh(), RED)).transform(Transform3D.at(new Vec3(0, 1, 0)));

        Aabb box = scene.bounds();
        assertEquals(5f, box.center().y(), 1e-4f, "parent and child transforms compose");
    }

    @Test
    void anEmptyGraphHasEmptyBounds() {
        assertTrue(new Scene3D().bounds().isEmpty());
    }

    @Test
    void cullsMeshesOutsideTheFrustum() {
        Scene3D scene = new Scene3D();
        MeshNode inView = scene.root().add(new MeshNode(mesh(), RED));
        scene.root().add(new MeshNode(mesh(), RED).transform(Transform3D.at(new Vec3(1000, 0, 0))));

        List<Scene3D.MeshInstance> visible = scene.visibleMeshes(camera(), 1f);

        assertEquals(2, scene.meshCount(), "both meshes are in the graph");
        assertEquals(1, visible.size(), "the far mesh is culled");
        assertSame(inView, visible.get(0).node());
    }

    @Test
    void composesWorldTransformsThroughParents() {
        Scene3D scene = new Scene3D();
        Node group = scene.root().add(new Node().transform(Transform3D.at(new Vec3(0.5f, 0, 0))));
        MeshNode child = group.add(new MeshNode(mesh(), RED).transform(Transform3D.at(new Vec3(0.3f, 0, 0))));

        Scene3D.MeshInstance instance = scene.visibleMeshes(camera(), 1f).stream()
                .filter(mi -> mi.node() == child).findFirst().orElseThrow();
        Vec3 worldPos = instance.worldMatrix().transformPoint(Vec3.ZERO);

        assertEquals(0.8f, worldPos.x(), 1e-4f, "0.5 (parent) + 0.3 (child)");
        assertEquals(0f, worldPos.y(), 1e-4f);
        assertEquals(0f, worldPos.z(), 1e-4f);
    }

    @Test
    void transformsLightsIntoWorldSpace() {
        Scene3D scene = new Scene3D();
        scene.root().add(new Node().transform(Transform3D.at(new Vec3(10, 0, 0))))
                .add(new LightNode(Light.Point.of(Vec3.ZERO, new Vec3(1, 1, 1), 5f)));

        List<Light> lights = scene.lights();

        assertEquals(1, lights.size());
        Light.Point moved = (Light.Point) lights.get(0);
        assertEquals(10f, moved.position().x(), 1e-4f, "light follows its parent's transform");
    }

    @Test
    void castShadowsFitsTheDirectionalLightToTheVisibleBounds() {
        Scene3D scene = new Scene3D().castShadows(true);
        scene.root().add(new MeshNode(mesh(), RED)); // UNIT box at the origin
        scene.root().add(new LightNode(Light.Directional.of(new Vec3(0, 1, 0), new Vec3(1, 1, 1))));

        RecordingPass pass = new RecordingPass();
        scene.render(pass, camera(), 1f);

        assertTrue(pass.shadowCalled, "a directional light + castShadows requests a shadow");
        assertEquals(0f, pass.shadowCenter.x(), 1e-4f);
        // UNIT extent (1,1,1) → bounding-sphere radius ≈ √3/2 (+ a small margin).
        assertEquals(0.876f, pass.shadowRadius, 0.02f);
    }

    @Test
    void culledMeshesStillReachTheShadowPass() {
        // Regression (code review): the depth pass replayed the camera-culled
        // list, so an object leaving the view had its shadow pop out of the scene.
        Scene3D scene = new Scene3D().castShadows(true);
        scene.root().add(new MeshNode(mesh(), RED)); // visible at the origin
        scene.root().add(new MeshNode(mesh(), RED)
                .transform(Transform3D.at(new Vec3(1000, 0, 0)))); // far outside the frustum
        scene.root().add(new LightNode(Light.Directional.of(new Vec3(0, 1, 0), new Vec3(1, 1, 1))));

        RecordingPass pass = new RecordingPass();
        scene.render(pass, camera(), 1f);

        assertEquals(1, pass.drawCount, "only the visible mesh reaches the color pass");
        assertEquals(1, pass.shadowOnlyCount, "the culled mesh still casts a shadow");
    }

    @Test
    void noShadowWithoutADirectionalLight() {
        Scene3D scene = new Scene3D().castShadows(true);
        scene.root().add(new MeshNode(mesh(), RED));
        scene.root().add(new LightNode(Light.Point.of(new Vec3(0, 2, 0), new Vec3(1, 1, 1), 5f)));

        RecordingPass pass = new RecordingPass();
        scene.render(pass, camera(), 1f);

        assertFalse(pass.shadowCalled, "point-only lighting casts no directional shadow");
    }

    @Test
    void worldMatricesAreCachedUntilATransformChanges() {
        Scene3D scene = new Scene3D();
        Node group = scene.root().add(new Node().transform(Transform3D.at(new Vec3(1, 0, 0))));
        MeshNode child = group.add(new MeshNode(mesh(), RED));

        Mat4 first = scene.visibleMeshes(camera(), 1f).get(0).worldMatrix();
        Mat4 second = scene.visibleMeshes(camera(), 1f).get(0).worldMatrix();
        assertSame(first, second, "a clean subtree reuses the cached world matrix instance");

        group.transform(Transform3D.at(new Vec3(2, 0, 0)));
        Mat4 third = scene.visibleMeshes(camera(), 1f).get(0).worldMatrix();
        assertNotSame(first, third, "a parent transform change recomposes the child's world");
        assertEquals(2f, third.transformPoint(Vec3.ZERO).x(), 1e-4f);
        assertSame(child, scene.visibleMeshes(camera(), 1f).get(0).node());
    }

    @Test
    void disposeReleasesEachDistinctResourceOnceAndEmptiesTheTree() {
        int[] meshDisposals = {0};
        GpuMesh shared = new GpuMesh() {
            @Override
            public Aabb bounds() {
                return UNIT;
            }

            @Override
            public void dispose() {
                meshDisposals[0]++;
            }
        };
        int[] textureDisposals = {0};
        limn.render3d.GpuTexture texture = new limn.render3d.GpuTexture() {
            @Override
            public int widthPx() {
                return 1;
            }

            @Override
            public int heightPx() {
                return 1;
            }

            @Override
            public void dispose() {
                textureDisposals[0]++;
            }
        };
        Material textured = Material.Pbr.of(1f, 1f, 1f).textured(texture);

        Scene3D scene = new Scene3D();
        Node group = scene.root().add(new Node());
        // The same mesh instanced twice (glTF-style sharing), the texture on both.
        group.add(new MeshNode(shared, textured));
        scene.root().add(new MeshNode(shared, textured));

        scene.dispose();

        assertEquals(1, meshDisposals[0], "a shared mesh is released once");
        assertEquals(1, textureDisposals[0], "a shared texture is released once");
        assertTrue(scene.root().children().isEmpty(), "the tree is emptied");
        assertEquals(0, scene.meshCount());

        scene.dispose();
        assertEquals(1, meshDisposals[0], "disposing twice is a no-op");
    }

    /** A RenderPass that records the shadow request (ignores everything else). */
    private static final class RecordingPass implements RenderPass {
        boolean shadowCalled;
        Vec3 shadowCenter;
        float shadowRadius;
        int drawCount;
        int shadowOnlyCount;

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
            shadowCalled = true;
            shadowCenter = center;
            shadowRadius = radius;
            return this;
        }

        @Override
        public RenderPass environment(Environment environment, IrradianceSh irradiance) {
            return this;
        }

        @Override
        public void draw(GpuMesh mesh, Material material, Mat4 model) {
            drawCount++;
        }

        @Override
        public void drawShadowOnly(GpuMesh mesh, Mat4 model) {
            shadowOnlyCount++;
        }
    }
}
