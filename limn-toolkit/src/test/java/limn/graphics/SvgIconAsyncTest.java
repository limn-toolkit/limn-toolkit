package limn.graphics;

import limn.concurrent.Job;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link SvgIcon#imageAsync}: the parse and rasterize leave the UI thread, the bitmap is folded into
 * the icon's cache before it is delivered (so the delivered instance is the one paint will draw, and
 * therefore one texture), and the cache itself stays confined to the UI thread.
 */
class SvgIconAsyncTest {

    /** Records the rasterizing thread, hands back a fresh Image, and can be held mid-call. */
    private static final class FakeRasterizer implements SvgRasterizer {
        final AtomicInteger rasterizations = new AtomicInteger();
        volatile Thread lastThread;
        volatile Image lastProduced;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

        @Override
        public Image rasterize(byte[] svgBytes, int pixelSize) {
            lastThread = Thread.currentThread();
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never released the rasterizer");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            rasterizations.incrementAndGet();
            Image produced = new Image(pixelSize, pixelSize, new byte[pixelSize * pixelSize * 4]);
            lastProduced = produced;
            return produced;
        }
    }

    private ExecutorService workers;
    private UiRuntime runtime;
    private final FakeRasterizer rasterizer = new FakeRasterizer();

    @BeforeEach
    void setUp() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        SvgIcon.installRasterizer(rasterizer);
    }

    @AfterEach
    void tearDown() {
        SvgIcon.uninstallRasterizer(rasterizer);
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

    @Test
    void rasterizesOffTheUiThreadAndFoldsInBeforeItDelivers() {
        SvgIcon icon = SvgIcon.of("<svg/>");
        AtomicReference<Thread> deliveryThread = new AtomicReference<>();
        AtomicReference<Image> cachedAtDelivery = new AtomicReference<>();
        AtomicReference<Image> delivered = new AtomicReference<>();

        icon.imageAsync(32)
                .onSuccess(bitmap -> {
                    deliveryThread.set(Thread.currentThread());
                    // The fold is posted from the body, so by now image(32) must be a hit
                    // returning this very instance: two instances would be two textures.
                    cachedAtDelivery.set(icon.image(32));
                    delivered.set(bitmap);
                })
                .start();

        pumpUntil(() -> delivered.get() != null);
        assertNotSame(Thread.currentThread(), rasterizer.lastThread,
                "the rasterize must run on a worker, not the UI thread");
        assertSame(Thread.currentThread(), deliveryThread.get(),
                "onSuccess must run on the UI thread");
        assertSame(delivered.get(), cachedAtDelivery.get(),
                "the delivered bitmap must already be the cached one");
        assertEquals(1, rasterizer.rasterizations.get(), "one rasterize, not two");
        assertSame(delivered.get(), icon.image(32), "and it stays cached afterwards");
        assertEquals(1, rasterizer.rasterizations.get());
    }

    @Test
    void anAlreadyCachedSizeIsDeliveredWithoutRasterizingAgain() {
        SvgIcon icon = SvgIcon.of("<svg/>");
        Image warm = icon.image(24);
        assertEquals(1, rasterizer.rasterizations.get());

        AtomicReference<Image> delivered = new AtomicReference<>();
        icon.imageAsync(24).onSuccess(delivered::set).start();

        pumpUntil(() -> delivered.get() != null);
        assertSame(warm, delivered.get(), "a hit delivers the instance already cached");
        assertEquals(1, rasterizer.rasterizations.get(), "and rasterizes nothing");
    }

    @Test
    void aCancelledJobKeepsTheFinishedRasterAndDeliversNothing() throws Exception {
        SvgIcon icon = SvgIcon.of("<svg/>");
        rasterizer.entered = new CountDownLatch(1);
        rasterizer.release = new CountDownLatch(1);

        AtomicReference<Image> delivered = new AtomicReference<>();
        Job job = icon.imageAsync(48).onSuccess(delivered::set).start();

        assertTrue(rasterizer.entered.await(5, TimeUnit.SECONDS), "the body must have started");
        job.cancel();                 // withdrawn while the rasterize is in flight
        rasterizer.release.countDown();
        pumpUntil(job::isDone);
        runtime.drain();              // the fold was posted before the body returned
        runtime.drain();

        assertNull(delivered.get(), "a cancelled job delivers nothing");
        rasterizer.entered = null;    // let any further call through
        assertSame(rasterizer.lastProduced, icon.image(48),
                "the finished raster is folded in anyway; it was paid for either way");
        assertEquals(1, rasterizer.rasterizations.get(), "so no second rasterize is needed");
    }

    @Test
    void theCacheRejectsAnyThreadButTheUiOne() throws Exception {
        SvgIcon icon = SvgIcon.of("<svg/>");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread offThread = new Thread(() -> {
            thrown.set(assertThrows(IllegalStateException.class, () -> icon.image(16)));
        });
        offThread.start();
        offThread.join(5_000);

        assertNotNull(thrown.get(),
                "image() from a worker would corrupt the unsynchronized LRU");
        assertEquals(0, rasterizer.rasterizations.get(), "and it must be refused before it works");
    }

    @Test
    void imageAsyncItselfIsUiThreadOnly() throws Exception {
        SvgIcon icon = SvgIcon.of("<svg/>");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread offThread = new Thread(() -> {
            thrown.set(assertThrows(IllegalStateException.class, () -> icon.imageAsync(16)));
        });
        offThread.start();
        offThread.join(5_000);

        assertNotNull(thrown.get(),
                "it reads the cache before it schedules anything, so it is confined too");
    }
}
