package limn.backend.lwjgl;

import limn.graphics.Font;
import limn.graphics.Fonts;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four embedded Roboto faces (regular/bold/italic/bold-italic) load and get
 * distinct {@code faceId}s: the glyph atlas keys its cache on that id, so a
 * shared id would collide bold with regular glyphs.
 */
class FontStoreTest {

    private java.util.concurrent.ExecutorService workers;
    private limn.concurrent.UiRuntime runtime;

    @org.junit.jupiter.api.BeforeEach
    void installRuntime() {
        // FontStore entry points are UI-thread confined (enforced): bind the
        // JUnit thread as the UI thread, like the component test base does.
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

    @Test
    void resolvesFourDistinctFacesWithDistinctIds() {
        try (FontStore store = new FontStore()) {
            StbFont regular = store.resolve(Font.of(14));
            StbFont bold = store.resolve(Font.of(14).bold());
            StbFont italic = store.resolve(Font.of(14).italic());
            StbFont boldItalic = store.resolve(Font.of(14).boldItalic());

            assertNotSame(regular, bold);
            assertNotSame(regular, italic);
            assertNotSame(bold, boldItalic);

            Set<Integer> ids = Set.of(store.faceId(regular), store.faceId(bold),
                    store.faceId(italic), store.faceId(boldItalic));
            assertEquals(4, ids.size(), "each face needs a distinct id");
        }
    }

    @Test
    void unknownFamilyFallsBackToRegular() {
        try (FontStore store = new FontStore()) {
            assertSame(store.resolve(Font.of(14)), store.resolve(new Font("Comic Sans", 14)));
        }
    }

    @Test
    void latinStaysOnThePrimaryFace() {
        try (FontStore store = new FontStore()) {
            StbFont primary = store.resolve(Font.of(14));
            assertSame(primary, store.faceForCodepoint(primary, 'A'),
                    "Roboto has Latin, no fallback needed");
        }
    }

    @Test
    void cjkFallsBackWhenBundledElseDegradesToPrimary() {
        // Fallbacks now arrive from a background parse; fold them in inline
        // (what the backend's async task does) before exercising the chain.
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        try (FontStore store = new FontStore()) {
            store.installHeavyFallbacks(loaded);
            StbFont primary = store.resolve(Font.of(14));
            int han = 0x65E5; // 日
            StbFont face = store.faceForCodepoint(primary, han);
            if (loaded.cjk() != null) {
                assertNotSame(primary, face, "CJK should resolve to the Noto fallback face");
                assertTrue(face.hasGlyph(han), "the resolved fallback must actually have the glyph");
            } else {
                assertSame(primary, face, "no fallback bundled → primary face (renders .notdef)");
            }
        }
    }

    @Test
    void measureHandlesMixedScriptWithoutThrowing() {
        try (FontStore store = new FontStore()) {
            Font font = Font.of(14);
            float latin = store.measure(font, "Hello").width();
            float mixed = store.measure(font, "Hello 日本 😀").width();
            assertTrue(latin > 0, "Latin measures positive");
            assertTrue(mixed > latin, "the mixed-script line is wider than its Latin prefix");
        }
    }

    @Test
    void switchingDefaultFamilyReResolvesTheDefaultFont() {
        FontStore.HeavyFallbacks loaded = FontStore.parseHeavyFallbacks();
        try (FontStore store = new FontStore()) {
            store.installHeavyFallbacks(loaded); // background fold-in, done inline
            StbFont roboto = store.resolve(Font.of(14)); // DEFAULT_FAMILY → Roboto
            org.junit.jupiter.api.Assumptions.assumeTrue(store.families().contains("Noto Sans CJK"),
                    "needs a second bundled family to switch to");
            try {
                Fonts.setDefaultFamily("Noto Sans CJK"); // fires the listener → store drops its memo
                StbFont switched = store.resolve(Font.of(14));
                assertNotSame(roboto, switched, "the default font must follow Fonts.setDefaultFamily");
                assertTrue(switched.hasGlyph(0x65E5), "the switched face is the CJK one");
            } finally {
                Fonts.setDefaultFamily(Font.DEFAULT_FAMILY); // restore global state for other tests
            }
        }
    }

    @Test
    void familiesIncludeTheBundledPrimaries() {
        try (FontStore store = new FontStore()) {
            assertTrue(store.families().contains("Roboto"), "Roboto is always selectable");
        }
    }

}
