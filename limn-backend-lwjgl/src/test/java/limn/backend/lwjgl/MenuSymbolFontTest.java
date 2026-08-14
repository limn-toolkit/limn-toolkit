package limn.backend.lwjgl;

import limn.graphics.Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key symbols a menu row draws its shortcut hints with resolve to real glyphs.
 *
 * <p>This face is built rather than downloaded ({@code scripts/generate-menu-symbols.py} merges
 * three Noto sources into it), so unlike the vendored Noto faces there is no upstream digest that
 * proves it is what it should be. What proves it is this: every code point it exists for resolves
 * through the fallback chain to a face that has the glyph, and stb parsed that face to answer.
 * A generator that emitted a subtly malformed table would fail here rather than in a menu.
 */
class MenuSymbolFontTest {

    /** Every symbol the accelerator hints can use, with the key each one stands for. */
    private static final Map<Integer, String> SYMBOLS = new LinkedHashMap<>();

    static {
        SYMBOLS.put(0x2318, "Command");
        SYMBOLS.put(0x2325, "Option");
        SYMBOLS.put(0x2303, "Control");
        SYMBOLS.put(0x21E7, "Shift");
        SYMBOLS.put(0x21EA, "Caps Lock");
        SYMBOLS.put(0x23CE, "Return");
        SYMBOLS.put(0x2324, "Enter");
        SYMBOLS.put(0x232B, "Delete backwards");
        SYMBOLS.put(0x2326, "Delete forwards");
        SYMBOLS.put(0x21E5, "Tab");
        SYMBOLS.put(0x238B, "Escape");
        SYMBOLS.put(0x2423, "Space");
        SYMBOLS.put(0x21DE, "Page Up");
        SYMBOLS.put(0x21DF, "Page Down");
        SYMBOLS.put(0x2196, "Home");
        SYMBOLS.put(0x2198, "End");
        SYMBOLS.put(0x2190, "Left");
        SYMBOLS.put(0x2191, "Up");
        SYMBOLS.put(0x2192, "Right");
        SYMBOLS.put(0x2193, "Down");
    }

    private ExecutorService workers;
    private limn.concurrent.UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new limn.concurrent.UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        limn.concurrent.Ui.install(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        limn.concurrent.Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    private static void assumePresent() {
        try (InputStream in = MenuSymbolFontTest.class.getResourceAsStream(
                "/limn/backend/lwjgl/fonts/LimnMenuSymbols.ttf")) {
            // Optional the way every other bundled face is: a stripped build renders the
            // hints as .notdef and everything else still works.
            Assumptions.assumeTrue(in != null, "LimnMenuSymbols.ttf is optional");
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void everyMenuSymbolResolvesToAFaceThatHasIt() {
        assumePresent();
        FontStore store = new FontStore();
        StbFont roboto = store.resolve(Font.of(14));

        for (Map.Entry<Integer, String> entry : SYMBOLS.entrySet()) {
            int codepoint = entry.getKey();
            String what = String.format("U+%04X (%s)", codepoint, entry.getValue());
            StbFont face = store.faceForCodepoint(roboto, codepoint);
            assertTrue(face.hasGlyph(codepoint),
                    what + " resolved to a face that lacks it, so a hint draws .notdef");
        }
    }

    /**
     * The four modifiers are the ones every hint is built from, and none of them is in the UI
     * font, which is the whole reason this face exists. They also have to arrive together: a
     * hint reading ⌘⇧S in two designs looks like a rendering fault rather than a shortcut.
     *
     * <p>Deliberately not asserted for all twenty. Roboto happens to carry a few of them
     * already (the plain arrows, the open box for Space) and the primary rightly wins there;
     * pinning which ones would be pinning a fact about Roboto that Roboto can change.
     */
    @Test
    void theModifierSymbolsComeFromOneFaceThatIsNotTheUiFont() {
        assumePresent();
        FontStore store = new FontStore();
        StbFont roboto = store.resolve(Font.of(14));

        StbFont first = null;
        for (int codepoint : new int[]{0x2318, 0x2325, 0x2303, 0x21E7}) {
            String what = String.format("U+%04X", codepoint);
            assertFalse(roboto.hasGlyph(codepoint), what + " is in Roboto after all");
            StbFont face = store.faceForCodepoint(roboto, codepoint);
            assertNotSame(roboto, face, what + " fell back to the primary, so it draws .notdef");
            if (first == null) {
                first = face;
            }
            assertSame(first, face, what + " came from a different face than its siblings");
        }
    }

    /**
     * It has to be there on the first frame. The broad-coverage faces load on a background parse
     * kicked off by the first glyph that misses, so a menu opened in the opening moments of a run
     * would draw boxes where its hints belong: the one place a fallback arriving late is seen.
     */
    @Test
    void theSymbolsResolveWithoutWaitingForTheBackgroundFallbacks() {
        assumePresent();
        FontStore store = new FontStore();
        StbFont roboto = store.resolve(Font.of(14));

        StbFont face = store.faceForCodepoint(roboto, 0x2318);

        assertNotSame(roboto, face, "the Command symbol resolved before any background load");
        assertFalse(store.heavyFallbacksRequested(),
                "resolving a menu symbol must not have kicked the heavy background load");
    }

}
