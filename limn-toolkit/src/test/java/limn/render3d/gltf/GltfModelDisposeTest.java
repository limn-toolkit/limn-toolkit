package limn.render3d.gltf;

import limn.graphics.Image;
import limn.graphics.ImageDecoder;
import limn.graphics.Images;
import limn.math.Aabb;
import limn.math.Transform3D;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.render3d.Camera;
import limn.render3d.GpuMesh;
import limn.render3d.GpuTexture;
import limn.render3d.Graphics3D;
import limn.render3d.MeshData;
import limn.render3d.RenderPass;
import limn.render3d.RenderTarget;
import limn.render3d.Sampler;
import limn.render3d.TextureData;
import limn.render3d.scene.Scene3D;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link GltfModel#toScene3D()} resource ownership, with a fake GPU provider:
 * only material-referenced textures are uploaded, and {@link Scene3D#dispose()}
 * releases every upload exactly once, including textures whose material is not
 * assigned to any mesh (unreachable from the node tree).
 */
class GltfModelDisposeTest {

    static final class FakeTexture implements GpuTexture {
        int disposeCalls;

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
            disposeCalls++;
        }
    }

    static final class FakeMesh implements GpuMesh {
        int disposeCalls;

        @Override
        public Aabb bounds() {
            return Aabb.EMPTY;
        }

        @Override
        public void dispose() {
            disposeCalls++;
        }
    }

    static final class FakeProvider implements Graphics3D.Provider {
        final List<FakeTexture> textures = new ArrayList<>();
        final List<FakeMesh> meshes = new ArrayList<>();

        @Override
        public RenderTarget createTarget(int widthPx, int heightPx, int samples) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GpuMesh upload(MeshData mesh) {
            FakeMesh uploaded = new FakeMesh();
            meshes.add(uploaded);
            return uploaded;
        }

        @Override
        public GpuTexture uploadTexture(TextureData texture, Sampler sampler) {
            FakeTexture uploaded = new FakeTexture();
            textures.add(uploaded);
            return uploaded;
        }

        @Override
        public void render(RenderTarget target, Camera camera, Consumer<RenderPass> body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void renderDemoScene(RenderTarget target, double timeSeconds) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Three textures: 0 = referenced by the drawn material, 1 = referenced by a
     * material no primitive uses, 2 = referenced by no material at all.
     */
    private static GltfModel model() {
        Vec4 white = new Vec4(1, 1, 1, 1);
        return new GltfModel(
                List.of(new GltfModel.MeshDef(
                        List.of(new GltfModel.Primitive(new MeshData(), 0)), "quad")),
                List.of(new GltfModel.MaterialDef(white, 0, 1, Vec3.ZERO, 0, "used"),
                        new GltfModel.MaterialDef(white, 0, 1, Vec3.ZERO, 1, "unused")),
                List.of(new GltfModel.TextureDef(0, -1),
                        new GltfModel.TextureDef(0, -1),
                        new GltfModel.TextureDef(0, -1)),
                List.of(),
                List.of(new GltfModel.ImageDef(new byte[]{1}, "image/png")),
                List.of(new GltfModel.NodeDef(Transform3D.IDENTITY, 0, new int[0], "node")),
                new int[]{0});
    }

    @Test
    void disposeReleasesEveryUploadOnce() {
        FakeProvider provider = new FakeProvider();
        ImageDecoder decoder = bytes -> new Image(1, 1, new byte[4]);
        Graphics3D.install(provider);
        Images.installDecoder(decoder);
        try {
            Scene3D scene = model().toScene3D();

            // Texture 2 is referenced by no material: never uploaded.
            assertEquals(2, provider.textures.size(), "only material-referenced textures upload");
            assertEquals(1, provider.meshes.size());

            scene.dispose();
            for (FakeTexture texture : provider.textures) {
                assertEquals(1, texture.disposeCalls,
                        "every upload freed once, even without a mesh reaching it");
            }
            for (FakeMesh mesh : provider.meshes) {
                assertEquals(1, mesh.disposeCalls);
            }

            scene.dispose(); // second dispose is a documented no-op
            for (FakeTexture texture : provider.textures) {
                assertEquals(1, texture.disposeCalls);
            }
        } finally {
            Images.uninstallDecoder(decoder);
            Graphics3D.uninstall(provider);
        }
    }
}
