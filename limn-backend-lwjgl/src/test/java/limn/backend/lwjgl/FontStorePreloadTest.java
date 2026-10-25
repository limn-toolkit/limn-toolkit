package limn.backend.lwjgl;

import limn.concurrent.Subscription;
import limn.concurrent.UiRuntime;
import limn.graphics.Font;
import limn.graphics.FontCatalog;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
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
            AtomicInteger laidOut = new AtomicInteger();
            store.setCatalogChangedNotifier(laidOut::incrementAndGet);
            AtomicBoolean resident = new AtomicBoolean();
            store.preloadFamily(PROBE_FAMILY, () -> resident.set(true));
            assertFalse(resident.get(), "the callback must never run inline");

            Font probe = new Font(PROBE_FAMILY, 14);
            assertSame(store.resolve(Font.of(14)), store.resolve(probe),
                    "while the read is in flight the frame stands in with the fallback");

            pumpUntil(resident::get);
            assertNotSame(store.resolve(Font.of(14)), store.resolve(probe),
                    "the fold-in re-resolves the family to the face it read");
            assertEquals(1, laidOut.get(), "and has the text laid out in the stand-in laid out again");
        }
    }

    @Test
    void anOvertakenReadLaysNothingOutAgainAndClosesNoFaceInUse() throws IOException {
        List<SystemFonts.Face> faces = new ArrayList<>();
        for (String family : List.of("Current", "Other One", "Other Two", "Overtaken")) {
            faces.addAll(regularAndBold(family));
        }
        try (FontStore store = new FontStore()) {
            store.setSystemFaces(faces);
            AtomicInteger laidOut = new AtomicInteger();
            store.setCatalogChangedNotifier(laidOut::incrementAndGet);
            FontLoader loader = store.loader();
            Fonts.installLoader(loader);
            try {
                // The UI drawn in one family and two labels in families of their own: six faces,
                // the most the store keeps, and every one of them in use.
                Fonts.setDefaultFamily("Current");
                store.resolve(Font.of(14));
                store.resolve(Font.of(14).bold());
                for (String family : List.of("Other One", "Other Two")) {
                    store.resolve(new Font(family, 14));
                    store.resolve(new Font(family, 14).bold());
                }
                long epoch = store.epoch();

                Fonts.setDefaultFamilyWhenResident("Overtaken");
                Fonts.setDefaultFamily("Current"); // the later choice, the family in force
                pumpUntil(() -> !store.readInFlight("Overtaken"));
                runtime.drain();

                assertEquals(0, laidOut.get(), "nothing was drawn in the family it read for");
                assertEquals(epoch, store.epoch(), "and no shaped text has to be shaped again");
                deleteFiles("Current", "Other One", "Other Two", "Overtaken");
                for (String family : List.of("Current", "Other One", "Other Two")) {
                    assertEquals(family + " Regular", store.resolve(new Font(family, 14)).name(),
                            "a face in use is not closed to make room for a read nobody wants");
                    assertEquals(family + " Bold", store.resolve(new Font(family, 14).bold()).name());
                }
                assertEquals("Roboto", store.resolve(new Font("Overtaken", 14)).name(),
                        "with no room but what closing a face in use would make, the read's own "
                                + "faces are the ones let go of");
            } finally {
                Fonts.setDefaultFamily(null);
                Fonts.uninstallLoader(loader);
            }
        }
    }

    @Test
    void aPickerSteppedThroughFamiliesLaysOutOnceAndKeepsTheFamilyTheUiWasDrawnIn()
            throws IOException {
        List<String> steps = List.of("Step A", "Step B", "Step C", "Step D");
        List<SystemFonts.Face> faces = new ArrayList<>(regularAndBold("Current"));
        for (String step : steps) {
            faces.addAll(regularAndBold(step));
        }
        try (FontStore store = new FontStore()) {
            store.setSystemFaces(faces);
            AtomicInteger catalogs = new AtomicInteger();
            FontCatalog catalog = store::families;
            // What the backend installs: a catalog notification is a Fonts change, which every
            // scene lays out again for.
            store.setCatalogChangedNotifier(() -> {
                catalogs.incrementAndGet();
                Fonts.installCatalog(catalog);
            });
            FontLoader loader = store.loader();
            Fonts.installLoader(loader);
            Fonts.setDefaultFamily("Current");
            store.resolve(Font.of(14));
            store.resolve(Font.of(14).bold());
            AtomicInteger layouts = new AtomicInteger();
            Subscription relayout = Fonts.observeChanges(layouts::incrementAndGet);
            try {
                CompletionStage<Boolean> last = null;
                for (String step : steps) {
                    last = Fonts.setDefaultFamilyWhenResident(step); // one pick per entry passed
                }
                AtomicReference<Boolean> switched = new AtomicReference<>();
                last.thenAccept(switched::set);
                pumpUntil(() -> switched.get() != null
                        && steps.stream().noneMatch(store::readInFlight));
                runtime.drain();

                assertTrue(switched.get());
                assertEquals("Step D", Fonts.defaultFamily());
                assertEquals(1, layouts.get(),
                        "one switch, one layout: the three reads it overtook laid nothing out");
                assertEquals(0, catalogs.get());
                deleteFiles("Current", "Step D");
                assertEquals("Step D Regular", store.resolve(Font.of(14)).name());
                assertEquals("Step D Bold", store.resolve(Font.of(14).bold()).name());
                assertEquals("Current Regular", store.resolve(new Font("Current", 14)).name(),
                        "the family the UI was drawn in until the switch is still resident, "
                                + "whichever order the reads landed in");
                assertEquals("Current Bold", store.resolve(new Font("Current", 14).bold()).name());
            } finally {
                relayout.cancel();
                Fonts.setDefaultFamily(null);
                Fonts.uninstallLoader(loader);
                Fonts.uninstallCatalog(catalog);
            }
        }
    }

    @Test
    void aWithdrawnRequestIsNotAnsweredAndItsReadStaysWhereThereIsRoom() throws IOException {
        try (FontStore store = new FontStore()) {
            store.setSystemFaces(regularAndBold(PROBE_FAMILY));
            AtomicInteger laidOut = new AtomicInteger();
            store.setCatalogChangedNotifier(laidOut::incrementAndGet);
            AtomicInteger answers = new AtomicInteger();
            Runnable answer = answers::incrementAndGet;
            store.preloadFamily(PROBE_FAMILY, answer);
            long epoch = store.epoch();
            store.withdrawPreload(PROBE_FAMILY, answer);
            assertTrue(store.readInFlight(PROBE_FAMILY), "a read once started is not called back");

            pumpUntil(() -> !store.readInFlight(PROBE_FAMILY));
            runtime.drain();
            assertEquals(0, answers.get(), "a withdrawn request is not answered");
            assertEquals(0, laidOut.get());
            assertEquals(epoch, store.epoch());
            deleteFiles(PROBE_FAMILY);
            assertEquals(PROBE_FAMILY + " Regular", store.resolve(new Font(PROBE_FAMILY, 14)).name(),
                    "with room to spare, what the read produced is kept, so asking again is cheap");
        }
    }

    @Test
    void aRequestWithdrawnBeforeTheEnumerationLandsLeavesNothingToRead() {
        try (FontStore store = new FontStore()) {
            Runnable answer = () -> { };
            store.preloadFamily(PROBE_FAMILY, answer);
            assertTrue(store.readInFlight(PROBE_FAMILY), "waiting for the enumeration");
            store.withdrawPreload(PROBE_FAMILY, answer);
            assertFalse(store.readInFlight(PROBE_FAMILY),
                    "with no request left, nothing is read when the enumeration lands");
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

    /**
     * A family of two faces, the bundled Roboto Regular and Bold each copied to a file of the
     * family's own, so that deleting them proves what is resident; see {@link #deleteFiles}.
     */
    private List<SystemFonts.Face> regularAndBold(String family) throws IOException {
        return List.of(
                new SystemFonts.Face(family, "Regular",
                        copyOfBundled("Roboto-Regular.ttf", family + "-regular.ttf"), 0, false, false),
                new SystemFonts.Face(family, "Bold",
                        copyOfBundled("Roboto-Bold.ttf", family + "-bold.ttf"), 0, true, false));
    }

    /** Takes the families' files of {@link #regularAndBold} away: a read of them now fails. */
    private void deleteFiles(String... families) throws IOException {
        for (String family : families) {
            Files.delete(tempDir.resolve(family + "-regular.ttf"));
            Files.delete(tempDir.resolve(family + "-bold.ttf"));
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
