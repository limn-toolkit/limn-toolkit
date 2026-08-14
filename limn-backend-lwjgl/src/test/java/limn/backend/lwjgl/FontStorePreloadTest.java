package limn.backend.lwjgl;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Background preload of a system family: the read that {@code resolve} would
 * otherwise do inside a measure moves to the worker pool, and until it lands
 * the frame stands in with the global fallback instead of blocking.
 *
 * <p>The "system" family here is a bundled Roboto copied to a temp file and
 * installed through {@link FontStore#setSystemFaces}, so nothing depends on
 * which fonts this machine has, and installing an enumeration also stops the
 * store from walking the real OS font directories mid-test.
 */
class FontStorePreloadTest {

    private static final String PROBE_FAMILY = "Preload Probe";

    private ExecutorService workers;
    private UiRuntime runtime;

    @TempDir
    Path tempDir;

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    @Test
    void aPreloadedFamilyResolvesWithoutReadingOnTheUiThread() throws IOException {
        SystemFonts.Face probeFace = probeFace();
        try (FontStore store = new FontStore()) {
            store.setSystemFaces(List.of(probeFace));
            AtomicBoolean resident = new AtomicBoolean();
            store.preloadFamily(PROBE_FAMILY, () -> resident.set(true));
            assertFalse(resident.get(), "the callback must never run inline");

            Font probe = new Font(PROBE_FAMILY, 14);
            assertSame(store.resolve(Font.of(14)), store.resolve(probe),
                    "while the read is in flight the frame stands in with the fallback");

            pumpUntil(resident::get);
            assertNotSame(store.resolve(Font.of(14)), store.resolve(probe),
                    "the fold-in re-resolves the family to the face it read");
        }
    }

    @Test
    void aBundledFamilyIsAnsweredOnALaterFrameHavingLoadedNothing() {
        try (FontStore store = new FontStore()) {
            store.setSystemFaces(List.of()); // the enumeration landed and found nothing
            AtomicInteger answers = new AtomicInteger();
            store.preloadFamily("Roboto", answers::incrementAndGet);
            assertEquals(0, answers.get(), "never inline, so a caller may flip its own state inside");
            runtime.drain();
            assertEquals(1, answers.get(), "already resident, but still answered");
        }
    }

    @Test
    void twoPreloadsOfOneFamilyAreBothAnswered() throws IOException {
        SystemFonts.Face probeFace = probeFace();
        try (FontStore store = new FontStore()) {
            store.setSystemFaces(List.of(probeFace));
            AtomicInteger answers = new AtomicInteger();
            store.preloadFamily(PROBE_FAMILY, answers::incrementAndGet);
            store.preloadFamily(PROBE_FAMILY.toLowerCase(java.util.Locale.ROOT),
                    answers::incrementAndGet); // matched case-insensitively
            pumpUntil(() -> answers.get() >= 2);
            assertEquals(2, answers.get());
        }
    }

    @Test
    void aPreloadWithNoEnumerationYetAsksForOne() {
        try (FontStore store = new FontStore()) {
            assertFalse(store.systemScanRequested(), "startup must not enumerate the OS fonts");
            store.preloadFamily(PROBE_FAMILY, () -> { });
            assertTrue(store.systemScanRequested(),
                    "no font file is known for any family until the OS is enumerated");
        }
    }

    @Test
    void aClosedStoreAnswersNobody() {
        FontStore store = new FontStore();
        store.close();
        AtomicInteger answers = new AtomicInteger();
        store.preloadFamily("Roboto", answers::incrementAndGet);
        runtime.drain();
        assertEquals(0, answers.get());
    }

    @Test
    void anEmptyFamilyDoesNothingAtAll() {
        try (FontStore store = new FontStore()) {
            AtomicInteger answers = new AtomicInteger();
            store.preloadFamily(null, answers::incrementAndGet);
            store.preloadFamily("   ", answers::incrementAndGet);
            runtime.drain();
            assertEquals(0, answers.get());
            assertFalse(store.systemScanRequested(), "nothing to look for, nothing to enumerate");
        }
    }

    /** A bundled face copied to disk, described the way the OS enumeration would. */
    private SystemFonts.Face probeFace() throws IOException {
        Path file = tempDir.resolve("probe.ttf");
        try (InputStream in = FontStore.class.getResourceAsStream(
                "/limn/backend/lwjgl/fonts/Roboto-Regular.ttf")) {
            Files.write(file, in.readAllBytes());
        }
        return new SystemFonts.Face(PROBE_FAMILY, "Regular", file, 0, false, false);
    }

    /** Runs frames until {@code condition} holds: the delivery lands on one. */
    private void pumpUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0) {
            runtime.drain();
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean(), "the background load never landed");
    }
}
