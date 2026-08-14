package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loading a font file an application ships: the family comes from the file, the failures are
 * loud, and the background enumeration of operating-system fonts does not un-register it.
 *
 * <p>The last one is the reason this file exists. A face loaded by path is in no enumeration, so
 * the scan landing (which happens on a worker, mid-run) used to rebuild the family map without
 * it, and the font reverted with nothing in the log.
 */
class FontStoreLoadFileTest {

    private java.util.concurrent.ExecutorService workers;
    private limn.concurrent.UiRuntime runtime;

    @org.junit.jupiter.api.BeforeEach
    void installRuntime() {
        // FontStore entry points are UI-thread confined (enforced), as in FontStoreTest.
        workers = java.util.concurrent.Executors.newFixedThreadPool(1);
        runtime = new limn.concurrent.UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        limn.concurrent.Ui.install(runtime);
    }

    @org.junit.jupiter.api.AfterEach
    void uninstallRuntime() {
        limn.concurrent.Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    /**
     * A face bundled for the menu glyphs, copied out to a file. It is registered as a face but
     * never advertised as a family, so its appearance in the catalog can only come from the load
     * under test, which a family the store already lists (Roboto) could not prove.
     */
    private static Path menuSymbolsFile(Path dir) throws Exception {
        byte[] bytes;
        try (InputStream in = FontStoreLoadFileTest.class.getResourceAsStream(
                "/limn/backend/lwjgl/fonts/LimnMenuSymbols.ttf")) {
            assertNotNull(in, "the bundled menu symbol face must be on the classpath");
            bytes = in.readAllBytes();
        }
        Path file = dir.resolve("symbols.ttf");
        Files.write(file, bytes);
        return file;
    }

    @Test
    void publishesTheFamilyTheFileDeclares(@TempDir Path dir) throws Exception {
        FontStore store = new FontStore();
        String family = store.loadFile(menuSymbolsFile(dir));
        // Not "symbols", which is what the file is called: a caller that assumed the file name
        // would name a family that resolves to nothing and render in the fallback face.
        assertEquals("Limn Menu Symbols", family);
        assertTrue(store.families().contains(family), "a loaded family joins the catalog");
    }

    @Test
    void survivesTheSystemEnumerationLanding(@TempDir Path dir) throws Exception {
        FontStore store = new FontStore();
        String family = store.loadFile(menuSymbolsFile(dir));

        // The scan landing with nothing in it: the strongest form of the race, and what a
        // headless runner with no font directories actually produces.
        store.setSystemFaces(List.of());

        assertTrue(store.families().contains(family),
                "an enumeration that never saw this file must not un-register it");
    }

    @Test
    void loadingTwiceIsIdempotent(@TempDir Path dir) throws Exception {
        FontStore store = new FontStore();
        Path file = menuSymbolsFile(dir);
        assertEquals(store.loadFile(file), store.loadFile(file));
        assertEquals(1, store.families().stream().filter("Limn Menu Symbols"::equals).count(),
                "the family is listed once, however often the file is loaded");
    }

    @Test
    void aMissingFileFails(@TempDir Path dir) {
        FontStore store = new FontStore();
        // Never a silent fallback: the whole point of the call is that the family exists
        // afterwards, and a caller that got the default face instead has no way to notice.
        assertThrows(UncheckedIOException.class, () -> store.loadFile(dir.resolve("absent.ttf")));
    }

    @Test
    void aFileThatCarriesNoFaceFails(@TempDir Path dir) throws Exception {
        FontStore store = new FontStore();
        Path file = dir.resolve("garbage.ttf");
        Files.write(file, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(IllegalArgumentException.class, () -> store.loadFile(file));
    }
}
