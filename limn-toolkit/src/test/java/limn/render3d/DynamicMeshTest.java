package limn.render3d;

import limn.math.Aabb;
import limn.math.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The CPU half of per-frame geometry: the live prefix a producer declares with
 * {@link MeshData#counts}, and the default {@link GpuMesh#update} contract. All
 * headless: no GL, no provider.
 *
 * <p>The load-bearing one is {@link #boundsMeasureOnlyTheLivePrefix()}: a stale
 * capacity vertex left at last frame's position inflates the AABB, and nothing in
 * the rendered picture says so: picking's broadphase and the shadow fit just
 * quietly use the bigger box.
 */
class DynamicMeshTest {

    private static final float EPS = 1e-4f;

    /** Four vertices of capacity, holding a unit quad in the first two. */
    private static MeshData withCapacity() {
        return new MeshData()
                .put(VertexAttribute.POSITION, new float[]{
                        0, 0, 0,
                        1, 1, 1,
                        50, 50, 50,   // capacity, not live
                        60, 60, 60})
                .indices(new int[]{0, 1, 2, 0, 2, 3});
    }

    @Test
    void aFreshMeshIsEntirelyLive() {
        MeshData mesh = withCapacity();
        assertEquals(4, mesh.vertexCount());
        assertEquals(4, mesh.vertexCapacity());
        assertEquals(6, mesh.indexCount());
    }

    @Test
    void countsNarrowsWithoutTouchingCapacity() {
        MeshData mesh = withCapacity().counts(2, 3);
        assertEquals(2, mesh.vertexCount());
        assertEquals(3, mesh.indexCount());
        assertEquals(4, mesh.vertexCapacity(), "the backing arrays are untouched");
        assertEquals(12, mesh.get(VertexAttribute.POSITION).length, "and are handed back whole");
        assertEquals(6, mesh.indices().length);
    }

    @Test
    void boundsMeasureOnlyTheLivePrefix() {
        Aabb whole = withCapacity().bounds();
        assertVec(new Vec3(60, 60, 60), whole.max());

        Aabb live = withCapacity().counts(2, 3).bounds();
        assertVec(new Vec3(0, 0, 0), live.min());
        assertVec(new Vec3(1, 1, 1), live.max(), "the two capacity vertices must not count");
    }

    @Test
    void anEmptyPrefixHasEmptyBounds() {
        assertSame(Aabb.EMPTY, withCapacity().counts(0, 0).bounds());
    }

    @Test
    void countsRejectsMoreThanCapacity() {
        MeshData mesh = withCapacity();
        assertThrows(IllegalArgumentException.class, () -> mesh.counts(5, 6));
        assertThrows(IllegalArgumentException.class, () -> mesh.counts(4, 7));
        assertThrows(IllegalArgumentException.class, () -> mesh.counts(-1, 0));
    }

    @Test
    void newBackingDataResetsThePrefix() {
        // put()/indices() describe new storage, so they cannot inherit a narrower
        // prefix measured against the old one. Documented as "call counts() last".
        MeshData mesh = withCapacity().counts(1, 3);
        assertEquals(1, mesh.vertexCount());
        mesh.put(VertexAttribute.NORMAL, new float[12]);
        assertEquals(4, mesh.vertexCount());
        mesh.counts(2, 3);
        mesh.indices(new int[]{0, 1, 2});
        assertEquals(3, mesh.indexCount());
    }

    @Test
    void writingIntoTheArraysAfterwardsIsWhatMakesAFrameFree() {
        // MeshData holds the arrays by reference; that is the contract a per-frame
        // producer relies on: size once, rewrite in place, re-declare the prefix.
        float[] positions = new float[12];
        MeshData mesh = new MeshData().put(VertexAttribute.POSITION, positions);
        assertSame(positions, mesh.get(VertexAttribute.POSITION));
        positions[0] = 7;
        assertVec(new Vec3(7, 0, 0), mesh.counts(1, 0).bounds().max());
    }

    @Test
    void aStaticMeshRefusesToBeUpdated() {
        GpuMesh staticMesh = new GpuMesh() {
            @Override
            public Aabb bounds() {
                return Aabb.EMPTY;
            }

            @Override
            public void dispose() {
            }
        };
        // The default is a refusal, so a handle from a provider that predates
        // dynamic meshes says so instead of silently drawing stale geometry.
        assertThrows(UnsupportedOperationException.class, () -> staticMesh.update(withCapacity()));
    }

    @Test
    void theFacadeCarriesTheUsageToTheProvider() {
        RecordingProvider provider = new RecordingProvider();
        Graphics3D.install(provider);
        try {
            Graphics3D.upload(withCapacity());
            Graphics3D.upload(withCapacity(), MeshUsage.DYNAMIC);
        } finally {
            Graphics3D.uninstall(provider); // process-global static: a leak poisons later tests
        }
        assertEquals(MeshUsage.STATIC, provider.usages.get(0), "the old entry point stays static");
        assertEquals(MeshUsage.DYNAMIC, provider.usages.get(1));
    }

    @Test
    void aProviderThatPredatesDynamicMeshesStillUploads() {
        // Provider.upload(mesh, usage) is a default, so a backend written before
        // this existed keeps compiling and keeps returning a usable handle, one
        // that refuses update(), which is the honest answer.
        LegacyProvider provider = new LegacyProvider();
        GpuMesh uploaded;
        Graphics3D.install(provider);
        try {
            uploaded = Graphics3D.upload(withCapacity(), MeshUsage.DYNAMIC);
        } finally {
            Graphics3D.uninstall(provider);
        }
        assertEquals(1, provider.uploads);
        GpuMesh handle = uploaded;
        assertThrows(UnsupportedOperationException.class, () -> handle.update(withCapacity()));
    }

    /** Records the usage every upload asked for; every other Provider call is off-limits. */
    private static final class RecordingProvider extends LegacyProvider {
        final java.util.List<MeshUsage> usages = new java.util.ArrayList<>();

        @Override
        public GpuMesh upload(MeshData mesh, MeshUsage usage) {
            usages.add(usage);
            return upload(mesh);
        }
    }

    /** A provider implementing only the pre-usage SPI. */
    private static class LegacyProvider implements Graphics3D.Provider {
        int uploads;

        @Override
        public RenderTarget createTarget(int widthPx, int heightPx, int samples) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GpuMesh upload(MeshData mesh) {
            uploads++;
            return new GpuMesh() {
                @Override
                public Aabb bounds() {
                    return Aabb.EMPTY;
                }

                @Override
                public void dispose() {
                }
            };
        }

        @Override
        public GpuTexture uploadTexture(TextureData texture, Sampler sampler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void render(RenderTarget target, limn.render3d.Camera camera,
                           java.util.function.Consumer<RenderPass> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void renderDemoScene(RenderTarget target, double timeSeconds) {
            throw new UnsupportedOperationException();
        }
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertVec(expected, actual, "vector");
    }

    private static void assertVec(Vec3 expected, Vec3 actual, String message) {
        assertEquals(expected.x(), actual.x(), EPS, message + " x");
        assertEquals(expected.y(), actual.y(), EPS, message + " y");
        assertEquals(expected.z(), actual.z(), EPS, message + " z");
    }
}
