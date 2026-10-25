package limn.backend.lwjgl;

import limn.concurrent.Subscription;
import limn.concurrent.UiRuntime;
import limn.graphics.Font;
import limn.graphics.FontLoader;
import limn.graphics.Fonts;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    private HeadlessUi ui;
    private UiRuntime runtime;

    @TempDir
    Path tempDir;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
        runtime = ui.runtime();
    }

    @AfterEach
    void uninstallRuntime() {
        ui.close();
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
    void anEmptyFamilyReadsNothingAndIsStillAnsweredOnALaterFrame() {
        try (FontStore store = new FontStore()) {
            AtomicInteger answers = new AtomicInteger();
            store.preloadFamily(null, answers::incrementAndGet);
            store.preloadFamily("   ", answers::incrementAndGet);
            assertEquals(0, answers.get(), "never inline, even with nothing to read");
            runtime.drain();
            assertEquals(2, answers.get(),
                    "a loader answers once when there is nothing to read, as FontLoader.preload says");
            assertFalse(store.systemScanRequested(), "nothing to look for, nothing to enumerate");
        }
    }

    @Test
    void aBundledFamilyIsAnsweredWithoutWaitingForTheEnumeration() {
        try (FontStore store = new FontStore()) {
            AtomicInteger answers = new AtomicInteger();
            store.preloadFamily("Roboto", answers::incrementAndGet);
            assertFalse(store.systemScanRequested(),
                    "a resolve takes the bundled face before any system one of the name, so the "
                            + "system's copy is nothing to look for");
            runtime.drain();
            assertEquals(1, answers.get());
        }
    }

    @Test
    void thePublicPreloadReachesTheStoreAndLeavesRegularAndBoldResident() throws IOException {
        Path regularFile = copyOfBundled("Roboto-Regular.ttf", "probe-regular.ttf");
        Path boldFile = copyOfBundled("Roboto-Bold.ttf", "probe-bold.ttf");
        try (FontStore store = new FontStore()) {
            store.setSystemFaces(List.of(
                    new SystemFonts.Face(PROBE_FAMILY, "Regular", regularFile, 0, false, false),
                    new SystemFonts.Face(PROBE_FAMILY, "Bold", boldFile, 0, true, false)));
            FontLoader loader = store.loader();
            Fonts.installLoader(loader); // what the backend installs
            try {
                AtomicBoolean resident = new AtomicBoolean();
                Fonts.preload(PROBE_FAMILY).thenRun(() -> resident.set(true));
                assertFalse(resident.get(), "never inside the call");
                pumpUntil(resident::get);

                // Gone from the disk: a resolve that still had to read would fail and stand in
                // with the fallback, so the names below prove both faces were already in memory.
                Files.delete(regularFile);
                Files.delete(boldFile);
                assertEquals(PROBE_FAMILY + " Regular",
                        store.resolve(new Font(PROBE_FAMILY, 14)).name());
                assertEquals(PROBE_FAMILY + " Bold",
                        store.resolve(new Font(PROBE_FAMILY, 14).bold()).name());
            } finally {
                Fonts.uninstallLoader(loader);
            }
        }
    }

    @Test
    void theFrameAfterTheSwitchFindsTheNewDefaultFamilyResident() throws IOException {
        Path regularFile = copyOfBundled("Roboto-Regular.ttf", "probe-regular.ttf");
        Path boldFile = copyOfBundled("Roboto-Bold.ttf", "probe-bold.ttf");
        try (FontStore store = new FontStore()) {
            store.setSystemFaces(List.of(
                    new SystemFonts.Face(PROBE_FAMILY, "Regular", regularFile, 0, false, false),
                    new SystemFonts.Face(PROBE_FAMILY, "Bold", boldFile, 0, true, false)));
            FontLoader loader = store.loader();
            Fonts.installLoader(loader);
            AtomicReference<String> regularAtSwitch = new AtomicReference<>();
            AtomicReference<String> boldAtSwitch = new AtomicReference<>();
            // Subscribed after the store, as a scene is: the store has dropped its memo by the
            // time this runs, so what it resolves here is what the next frame's layout resolves.
            Subscription relayout = Fonts.observeChanges(() -> {
                if (regularAtSwitch.get() != null || !PROBE_FAMILY.equals(Fonts.defaultFamily())) {
                    return;
                }
                try {
                    Files.delete(regularFile); // a read now would fail and fall back
                    Files.delete(boldFile);
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
                regularAtSwitch.set(store.resolve(Font.of(14)).name());
                boldAtSwitch.set(store.resolve(Font.of(14).bold()).name());
            });
            try {
                AtomicReference<Boolean> switched = new AtomicReference<>();
                Fonts.setDefaultFamilyWhenResident(PROBE_FAMILY).thenAccept(switched::set);
                assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily(),
                        "not before the faces are read");
                assertEquals("Roboto", store.resolve(Font.of(14)).name(),
                        "and the frames until then draw in the family in force");

                pumpUntil(() -> switched.get() != null);
                assertTrue(switched.get());
                assertEquals(PROBE_FAMILY + " Regular", regularAtSwitch.get());
                assertEquals(PROBE_FAMILY + " Bold", boldAtSwitch.get());
            } finally {
                relayout.cancel();
                Fonts.setDefaultFamily(null);
                Fonts.uninstallLoader(loader);
            }
        }
    }

    /** A bundled face copied to disk, described the way the OS enumeration would. */
    private SystemFonts.Face probeFace() throws IOException {
        return new SystemFonts.Face(PROBE_FAMILY, "Regular",
                copyOfBundled("Roboto-Regular.ttf", "probe.ttf"), 0, false, false);
    }

    /** One of the bundled faces, written to a file of this test's own. */
    private Path copyOfBundled(String face, String name) throws IOException {
        Path file = tempDir.resolve(name);
        try (InputStream in = FontStore.openBundled(FontStore.bundledPath(face))) {
            Files.write(file, in.readAllBytes());
        }
        return file;
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
