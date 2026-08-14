package limn.graphics;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link Images#encodeAsync}: the compression runs on a worker, the bytes arrive on the UI thread,
 * the description does nothing until it is started, and a request no encoder accepts is a delivered
 * failure rather than a throw at the call site.
 */
class ImagesEncodeAsyncTest {

    /** PNG, but recording which thread ran the compression. */
    private static final class RecordingEncoder implements ImageEncoder {
        final AtomicInteger encodes = new AtomicInteger();
        volatile Thread lastThread;

        @Override
        public String name() {
            return "recording-png";
        }

        @Override
        public boolean supports(ImageEncodeOptions options) {
            return PngEncoder.INSTANCE.supports(options);
        }

        @Override
        public void encode(Image image, ImageEncodeOptions options, OutputStream out)
                throws IOException {
            encodes.incrementAndGet();
            lastThread = Thread.currentThread();
            PngEncoder.INSTANCE.encode(image, options, out);
        }
    }

    private ExecutorService workers;
    private UiRuntime runtime;
    private final RecordingEncoder encoder = new RecordingEncoder();

    @BeforeEach
    void setUp() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        Images.uninstallAllEncoders();
        Images.installEncoder(encoder);
    }

    @AfterEach
    void tearDown() {
        Images.uninstallAllEncoders();
        Images.installEncoder(PngEncoder.INSTANCE);
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    /** Spins the UI queue (like the backend loop) until the condition holds. */
    private void pumpUntil(BooleanSupplier done) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!done.getAsBoolean()) {
            runtime.drain();
            if (System.nanoTime() > deadline) {
                fail("timed out pumping the UI queue");
            }
        }
    }

    private static Image checkerboard() {
        byte[] pixels = new byte[4 * 3 * 4];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i * 7);
        }
        return new Image(4, 3, pixels);
    }

    @Test
    void compressesOnAWorkerAndDeliversTheBytesOnTheUiThread() {
        Image image = checkerboard();
        ImageEncodeOptions options = new ImageEncodeOptions(ImageFormat.PNG);
        byte[] expected = Images.encode(image, options); // synchronous reference, on this thread
        Thread synchronousThread = encoder.lastThread;

        AtomicReference<byte[]> delivered = new AtomicReference<>();
        AtomicReference<Thread> deliveryThread = new AtomicReference<>();
        Images.encodeAsync(image, options)
                .onSuccess(bytes -> {
                    deliveryThread.set(Thread.currentThread());
                    delivered.set(bytes);
                })
                .start();

        pumpUntil(() -> delivered.get() != null);
        assertSame(Thread.currentThread(), synchronousThread,
                "the synchronous form compresses on its caller's thread");
        assertNotSame(Thread.currentThread(), encoder.lastThread,
                "the async form must compress on a worker, not the UI thread");
        assertSame(Thread.currentThread(), deliveryThread.get(),
                "onSuccess must run on the UI thread");
        assertArrayEquals(expected, delivered.get(),
                "the bytes must be exactly what the synchronous form produces");
    }

    @Test
    void nothingRunsUntilStart() {
        Images.encodeAsync(checkerboard(), new ImageEncodeOptions(ImageFormat.PNG));
        for (int frame = 0; frame < 3; frame++) {
            runtime.drain();
        }
        assertEquals(0, encoder.encodes.get(),
                "an unstarted description must not have been submitted");
    }

    @Test
    void aFormatNoEncoderAcceptsFailsThroughOnFailureOnTheUiThread() {
        Images.uninstallAllEncoders(); // nothing claims anything now

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Thread> failureThread = new AtomicReference<>();
        Images.encodeAsync(checkerboard(), new ImageEncodeOptions(ImageFormat.PNG))
                .onFailure(error -> {
                    failureThread.set(Thread.currentThread());
                    failure.set(error);
                })
                .start();

        pumpUntil(() -> failure.get() != null);
        assertInstanceOf(UnsupportedOperationException.class, failure.get(),
                "the encoder is chosen when the body runs, so its absence is a delivered failure");
        assertSame(Thread.currentThread(), failureThread.get(),
                "onFailure must run on the UI thread");
    }
}
