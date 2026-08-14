package limn.render3d.gltf;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.Image;
import limn.graphics.ImageDecoder;
import limn.graphics.Images;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The split between decoding a model's textures and uploading them: the decode is CPU work that
 * runs anywhere, the upload needs the frame, and once the decode has happened the upload must not
 * quietly do it again. Everything here is headless (a fake GPU provider and a counting image
 * decoder), because that is exactly the seam being tested.
 */
class GltfModelTexturesTest {

    /** Counts decodes and records where they happened. */
    private static final class CountingDecoder implements ImageDecoder {
        final AtomicInteger decodes = new AtomicInteger();
        volatile Thread lastThread;

        @Override
        public Image decode(byte[] fileBytes) {
            decodes.incrementAndGet();
            lastThread = Thread.currentThread();
            return new Image(1, 1, new byte[4]);
        }
    }

    private static final class FakeTexture implements GpuTexture {
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
        }
    }

    private static final class FakeMesh implements GpuMesh {
        @Override
        public limn.math.Aabb bounds() {
            return limn.math.Aabb.EMPTY;
        }

        @Override
        public void dispose() {
        }
    }

    private static final class FakeProvider implements Graphics3D.Provider {
        final List<GpuTexture> textures = new ArrayList<>();

        @Override
        public RenderTarget createTarget(int widthPx, int heightPx, int samples) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GpuMesh upload(MeshData mesh) {
            return new FakeMesh();
        }

        @Override
        public GpuTexture uploadTexture(TextureData texture, Sampler sampler) {
            GpuTexture uploaded = new FakeTexture();
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
     * Three textures over two images: 0 and 1 both sample image 0 and are referenced by materials;
     * 2 samples image 1 and no material names it.
     */
    private static GltfModel model() {
        Vec4 white = new Vec4(1, 1, 1, 1);
        return new GltfModel(
                List.of(new GltfModel.MeshDef(
                        List.of(new GltfModel.Primitive(new MeshData(), 0)), "quad")),
                List.of(new GltfModel.MaterialDef(white, 0, 1, Vec3.ZERO, 0, "used"),
                        new GltfModel.MaterialDef(white, 0, 1, Vec3.ZERO, 1, "alsoUsed")),
                List.of(new GltfModel.TextureDef(0, -1),
                        new GltfModel.TextureDef(0, -1),
                        new GltfModel.TextureDef(1, -1)),
                List.of(),
                List.of(new GltfModel.ImageDef(new byte[]{1}, "image/png"),
                        new GltfModel.ImageDef(new byte[]{2}, "image/png")),
                List.of(new GltfModel.NodeDef(Transform3D.IDENTITY, 0, new int[0], "node")),
                new int[]{0});
    }

    private ExecutorService workers;
    private UiRuntime runtime;
    private final CountingDecoder decoder = new CountingDecoder();
    private final FakeProvider provider = new FakeProvider();

    @BeforeEach
    void setUp() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        Images.installDecoder(decoder);
        Graphics3D.install(provider);
    }

    @AfterEach
    void tearDown() {
        Graphics3D.uninstall(provider);
        Images.uninstallDecoder(decoder);
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    private void pumpUntil(BooleanSupplier done) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!done.getAsBoolean()) {
            runtime.drain();
            if (System.nanoTime() > deadline) {
                fail("timed out pumping the UI queue");
            }
        }
    }

    @Test
    void decodesOnlyReferencedTexturesAndOncePerImage() {
        GltfModel.DecodedTextures decoded = model().decodeTextures();

        assertEquals(2, decoded.count(), "the texture no material names is not decoded");
        assertEquals(1, decoder.decodes.get(), "two textures over one image is one decode");
    }

    @Test
    void uploadingDecodedTexturesDoesNotDecodeAgain() {
        GltfModel model = model();
        GltfModel.DecodedTextures decoded = model.decodeTextures();
        int afterDecode = decoder.decodes.get();

        Scene3D scene = model.toScene3D(decoded);

        assertEquals(afterDecode, decoder.decodes.get(),
                "the frame half must not redo the CPU half");
        assertEquals(2, provider.textures.size(), "one upload per referenced texture");
        scene.dispose();
    }

    @Test
    void texturesFromAnotherModelAreRefusedRatherThanMisindexed() {
        GltfModel.DecodedTextures foreign = model().decodeTextures();

        assertThrows(IllegalArgumentException.class, () -> model().toScene3D(foreign));
    }

    @Test
    void decodesOffTheUiThreadAndDeliversOnIt() {
        AtomicReference<Thread> deliveredOn = new AtomicReference<>();
        AtomicReference<GltfModel.DecodedTextures> decoded = new AtomicReference<>();

        model().decodeTexturesAsync()
                .onSuccess(result -> {
                    deliveredOn.set(Thread.currentThread());
                    decoded.set(result);
                })
                .start();

        pumpUntil(() -> decoded.get() != null);
        assertSame(Thread.currentThread(), deliveredOn.get(), "delivery is the UI thread");
        assertNotSame(Thread.currentThread(), decoder.lastThread, "the decode is not");
        assertEquals(2, decoded.get().count());
    }

    @Test
    void theAsyncDecodeReportsProgressEndingAtOne() {
        List<Double> seen = new ArrayList<>();
        AtomicReference<GltfModel.DecodedTextures> decoded = new AtomicReference<>();

        model().decodeTexturesAsync().onProgress(seen::add).onSuccess(decoded::set).start();

        pumpUntil(() -> decoded.get() != null);
        assertTrue(!seen.isEmpty() && seen.get(seen.size() - 1) == 1.0,
                "the bar finishes before the images land: " + seen);
    }

    /** Answers cancelled once more than {@code afterReports} progress reports have been made. */
    private static final class CancelAfter implements limn.concurrent.Progress {
        private final int afterReports;
        private int reports;

        CancelAfter(int afterReports) {
            this.afterReports = afterReports;
        }

        @Override
        public boolean isCancelled() {
            return reports > afterReports;
        }

        @Override
        public void report(double fraction) {
            reports++;
        }
    }

    /** Two referenced textures over two distinct images, so an abandoned loop is visible. */
    private static GltfModel twoImageModel() {
        Vec4 white = new Vec4(1, 1, 1, 1);
        return new GltfModel(
                List.of(),
                List.of(new GltfModel.MaterialDef(white, 0, 1, Vec3.ZERO, 0, "first"),
                        new GltfModel.MaterialDef(white, 0, 1, Vec3.ZERO, 1, "second")),
                List.of(new GltfModel.TextureDef(0, -1), new GltfModel.TextureDef(1, -1)),
                List.of(),
                List.of(new GltfModel.ImageDef(new byte[]{1}, "image/png"),
                        new GltfModel.ImageDef(new byte[]{2}, "image/png")),
                List.of(),
                new int[0]);
    }

    @Test
    void aCancelStopsBetweenImagesInsteadOfDecodingTheRest() {
        // One image decoded, then the withdrawal is noticed. Driven with a Progress of the test's
        // own: a cancel raced against two one-pixel decodes would not be a test of anything.
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> twoImageModel().decodeTextures(new CancelAfter(0)));
        assertEquals(1, decoder.decodes.get(), "the second image is never decoded");
    }

    @Test
    void theOneShotFormStillDecodesAndUploadsTogether() {
        Scene3D scene = model().toScene3D();

        assertEquals(1, decoder.decodes.get());
        assertEquals(2, provider.textures.size());
        scene.dispose();
    }
}
