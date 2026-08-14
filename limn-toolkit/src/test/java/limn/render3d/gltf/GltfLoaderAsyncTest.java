package limn.render3d.gltf;

import limn.concurrent.Job;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link GltfLoader}'s background forms: the read and the parse happen on a worker, the model
 * arrives on the UI thread, and the progress a caller can put on a bar is monotonic and finishes at
 * 1 before the model lands. The JUnit thread plays the UI thread and pumps frames by hand.
 */
class GltfLoaderAsyncTest {

    /** Three meshes, so the mesh loop has something to report progress across. */
    private static byte[] gltf() {
        String json = """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0,
                  "scenes": [{"nodes": [0]}],
                  "nodes": [{"name": "root", "mesh": 0}],
                  "meshes": [
                    {"name": "a", "primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]},
                    {"name": "b", "primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]},
                    {"name": "c", "primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}
                  ],
                  "accessors": [
                    {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}
                  ],
                  "bufferViews": [
                    {"buffer": 0, "byteOffset": 0, "byteLength": 36},
                    {"buffer": 0, "byteOffset": 36, "byteLength": 6}
                  ],
                  "buffers": [{"byteLength": 42, "uri": "data:application/octet-stream;base64,%s"}]
                }
                """.formatted(java.util.Base64.getEncoder().encodeToString(buffer()));
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buffer() {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(42).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float v : new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}) {
            bb.putFloat(v);
        }
        for (int v : new int[]{0, 1, 2}) {
            bb.putShort((short) v);
        }
        return bb.array();
    }

    private ExecutorService workers;
    private UiRuntime runtime;

    @BeforeEach
    void setUp() {
        workers = Executors.newFixedThreadPool(2);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
    }

    @AfterEach
    void tearDown() {
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
    void parsesOffTheUiThreadAndDeliversOnIt() {
        AtomicReference<Thread> deliveredOn = new AtomicReference<>();
        AtomicReference<GltfModel> model = new AtomicReference<>();

        Job job = GltfLoader.loadAsync(gltf())
                .onSuccess(loaded -> {
                    deliveredOn.set(Thread.currentThread());
                    model.set(loaded);
                })
                .start();

        // Wait for the parse without ever running a frame. It can only finish if some other
        // thread did it, which is the whole claim; and nothing may be delivered yet, because
        // delivery needs a frame this thread has not run.
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!job.isDone()) {
            if (System.nanoTime() > deadline) {
                fail("the parse never finished off the UI thread");
            }
            Thread.onSpinWait();
        }
        assertNull(model.get(), "isDone() is not delivery: that still waits for a frame");

        pumpUntil(() -> model.get() != null);
        assertSame(Thread.currentThread(), deliveredOn.get(), "the model lands on the UI thread");
        assertEquals(3, model.get().meshes().size());
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

    @Test
    void aCancelStopsTheParseInsteadOfFinishingIt() {
        // The mesh loop is where a large model spends its time, so a withdrawn load must not run
        // to the end of it. Driven with a Progress of the test's own, because a cancel raced
        // against a three-mesh parse would not be a test of anything.
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> GltfLoader.load(gltf(), new CancelAfter(2)));
    }

    @Test
    void nothingIsReadUntilTheWorkIsStarted() throws Exception {
        Path missing = Files.createTempDirectory("limn-gltf").resolve("absent.gltf");
        AtomicInteger failures = new AtomicInteger();

        GltfLoader.loadAsync(missing).onFailure(error -> failures.incrementAndGet());
        runtime.drain();
        assertEquals(0, failures.get(), "an unstarted Work must not touch the file");
    }

    @Test
    void progressRisesToOneAndTheLastValueArrivesBeforeTheModel() {
        List<Double> seen = new ArrayList<>();
        AtomicReference<GltfModel> model = new AtomicReference<>();
        AtomicReference<Double> atDelivery = new AtomicReference<>();

        GltfLoader.loadAsync(gltf())
                .onProgress(seen::add)
                .onSuccess(loaded -> {
                    atDelivery.set(seen.isEmpty() ? null : seen.get(seen.size() - 1));
                    model.set(loaded);
                })
                .start();

        pumpUntil(() -> model.get() != null);
        assertEquals(1.0, atDelivery.get(), 1e-9,
                "the bar reaches its end before the model does, not one step short");
        double previous = -1;
        for (double fraction : seen) {
            assertTrue(fraction >= previous, "progress never goes backwards: " + seen);
            previous = fraction;
        }
    }

    @Test
    void readsAndParsesAFileFromDisk() throws Exception {
        Path file = Files.createTempFile("limn-gltf", ".gltf");
        file.toFile().deleteOnExit();
        Files.write(file, gltf());
        AtomicReference<GltfModel> model = new AtomicReference<>();

        GltfLoader.loadAsync(file).onSuccess(model::set).start();

        pumpUntil(() -> model.get() != null);
        assertEquals(3, model.get().meshes().size());
    }

    @Test
    void readsAndParsesAClasspathResource() {
        AtomicReference<GltfModel> model = new AtomicReference<>();

        GltfLoader.fromResourceAsync("/limn/render3d/gltf/test-node.gltf")
                .onSuccess(model::set)
                .start();

        pumpUntil(() -> model.get() != null);
        assertEquals("fromResource", model.get().nodes().get(0).name());
    }

    @Test
    void aMissingResourceFailsOnTheUiThreadRatherThanThrowingAtTheCall() {
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Job job = GltfLoader.fromResourceAsync("/limn/render3d/gltf/no-such-model.gltf")
                .onFailure(failure::set)
                .start();

        pumpUntil(() -> failure.get() != null);
        assertTrue(job.isDone());
        assertTrue(failure.get() instanceof IllegalStateException,
                "the missing-resource failure survives the crossing unwrapped: " + failure.get());
    }
}
