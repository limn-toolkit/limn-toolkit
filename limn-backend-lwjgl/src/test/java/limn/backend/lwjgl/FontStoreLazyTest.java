package limn.backend.lwjgl;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lazy font loading: only Roboto Regular parses at construction; style
 * variants parse on first resolve; the heavyweight fallbacks (CJK/emoji)
 * arrive from a background parse folded in explicitly here (pure CPU:
 * stb_truetype, no GL context, so it runs headlessly).
 */
class FontStoreLazyTest {

    private ExecutorService workers;
    private UiRuntime runtime;

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
    void onlyRegularParsesEagerlyAndVariantsParseOnFirstResolve() {
        try (FontStore store = new FontStore()) {
            assertTrue(store.bundledLoaded("roboto"), "the regular face is the eager last resort");
            assertFalse(store.bundledLoaded("roboto bold"), "bold must not parse at startup");
            assertFalse(store.bundledLoaded("roboto italic"));
            assertFalse(store.bundledLoaded("roboto bold italic"));

            StbFont bold = store.resolve(Font.of(14).bold());
            assertNotNull(bold);
            assertTrue(store.bundledLoaded("roboto bold"), "first bold resolve parses the face");
            assertFalse(store.bundledLoaded("roboto bold italic"), "unused variants stay unparsed");

            // The parsed face measures like any eager one.
            assertTrue(store.measure(Font.of(14).bold(), "Hi").width() > 0);
        }
    }

    @Test
    void backgroundFallbacksFoldInAndUpgradeTheCatalog() {
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        Assumptions.assumeTrue(loaded.cjk() != null, "Noto CJK not bundled on this machine");
        try (FontStore store = new FontStore()) {
            assertFalse(store.families().contains("Noto Sans CJK"), "not available before fold-in");
            assertTrue(store.installHeavyFallbacks(loaded));
            assertTrue(store.families().contains("Noto Sans CJK"));
            // CJK text now measures through the fallback chain (glyphs found).
            assertTrue(store.measure(Font.of(14), "你好").width() > 0);
        }
    }

    @Test
    void aParseThatThrowsHalfwayFreesEveryFaceItHadAlreadyLoaded() {
        // A bundled resource that is present and unreadable throws rather than answering null, and
        // by then the faces before it are native buffers stb allocated that nothing else holds: the
        // fold-in that would have taken them over is what is not going to happen. Roboto stands in
        // for both the CJK face and a script face, because what is asserted is the ownership rule,
        // not which font it was — and the second one is here because a batch that grew from two
        // loaders to three is exactly where one of them stops being freed.
        StbFont cjk = StbFont.loadResourceIfPresent(
                "/limn/fonts/Roboto-Regular.ttf", "Roboto");
        StbFont script = StbFont.loadResourceIfPresent(
                "/limn/fonts/Roboto-Regular.ttf", "Roboto");
        assertNotNull(cjk);
        assertNotNull(script);
        RuntimeException unreadable = new java.io.UncheckedIOException(
                new java.io.IOException("reading font"));

        assertSame(unreadable, assertThrows(RuntimeException.class,
                () -> FontStore.parseHeavyFallbacks(() -> cjk, () -> java.util.List.of(script),
                        () -> {
                            throw unreadable;
                        })));
        assertTrue(cjk.isClosed(), "the half-parsed set must not outlive the parse that failed");
        assertTrue(script.isClosed(), "and that is every face in it, not only the first");
    }

    @Test
    void heavyFallbacksKickOnlyOnFirstGlyphMiss() {
        try (FontStore store = new FontStore()) {
            store.measure(Font.of(14), "Hello, Latin only");
            assertFalse(store.heavyFallbacksRequested(),
                    "Latin-only text must not load the Noto fallbacks");

            store.measure(Font.of(14), "日"); // the primary lacks this glyph
            assertTrue(store.heavyFallbacksRequested(),
                    "the first missing glyph kicks the background load");
        }
    }

    @Test
    void systemScanKicksOnFirstListingOrUnknownFamily() {
        try (FontStore store = new FontStore()) {
            assertFalse(store.systemScanRequested(), "startup must not enumerate the OS fonts");
            store.resolve(Font.of(14)); // bundled family: still no need
            assertFalse(store.systemScanRequested());

            store.families(); // the listing is the first real need
            assertTrue(store.systemScanRequested());
        }
        try (FontStore store = new FontStore()) {
            store.resolve(new Font("Some Unknown Family", 14));
            assertTrue(store.systemScanRequested(),
                    "an unknown family needs the enumeration to resolve properly");
        }
    }

    @Test
    void fallbacksArrivingAfterCloseAreFreedNotInstalled() {
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        Assumptions.assumeTrue(loaded.cjk() != null || loaded.emoji() != null,
                "no fallback binaries bundled on this machine");
        FontStore store = new FontStore();
        store.close();
        assertFalse(store.installHeavyFallbacks(loaded), "a late arrival must be dropped (and freed)");
    }
}
