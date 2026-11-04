package limn.backend.lwjgl;

import limn.graphics.Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link Font#MONOSPACE_FAMILY}: Roboto Mono where {@code limn-fonts-roboto-mono} is on the class
 * path, every character one width and each weight its own instance; the default family where it
 * is not, so the alias never draws nothing.
 */
class MonospaceFamilyTest {

    private java.util.concurrent.ExecutorService workers;
    private limn.concurrent.UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        workers = java.util.concurrent.Executors.newFixedThreadPool(1);
        runtime = new limn.concurrent.UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        limn.concurrent.Ui.install(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        limn.concurrent.Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    private static boolean bundled() {
        return FontStore.exists(FontStore.bundledPath("RobotoMono-Variable.ttf"));
    }

    @Test
    void withoutTheArtifactTheAliasIsTheDefaultFamily() {
        assumeTrue(!bundled(), "limn-fonts-roboto-mono is on this class path");
        try (FontStore store = new FontStore()) {
            assertSame(store.resolve(new Font(Font.DEFAULT_FAMILY, 14, 400, false)),
                    store.resolve(new Font(Font.MONOSPACE_FAMILY, 14, 400, false)));
        }
    }

    @Test
    void theAliasIsRobotoMonoEveryCharacterOneWidthAndEachWeightItsOwn() {
        assumeTrue(bundled(), "limn-fonts-roboto-mono is not on this class path");
        try (FontStore store = new FontStore()) {
            StbFont regular = store.resolve(new Font(Font.MONOSPACE_FAMILY, 20, 400, false));
            assertTrue(regular.name().startsWith("Roboto Mono"), regular.name());
            float i = regular.glyphAdvance(regular.glyphIndex('i'), 20);
            for (char c : "MW0_{".toCharArray()) {
                assertEquals(i, regular.glyphAdvance(regular.glyphIndex(c), 20), 1e-3, "'" + c + "'");
            }
            StbFont bold = store.resolve(new Font(Font.MONOSPACE_FAMILY, 20, 700, false));
            assertTrue(bold.isInstance() && bold.weight() == 700, bold.name());
            assertEquals(700, store.resolve(new Font(Font.MONOSPACE_FAMILY, 20, 900, false)).weight(),
                    "900 asked of a face that ends at 700");
            StbFont italic = store.resolve(new Font(Font.MONOSPACE_FAMILY, 20, 400, true));
            assertTrue(italic.name().contains("Italic"), italic.name());
        }
    }

    @Test
    void theRangeTheStoreDescribesIsTheOneTheFilesDeclare(@TempDir Path dir) throws Exception {
        assumeTrue(bundled(), "limn-fonts-roboto-mono is not on this class path");
        for (String file : new String[] {"RobotoMono-Variable.ttf", "RobotoMono-VariableItalic.ttf"}) {
            Path copy = dir.resolve(file);
            try (InputStream in = FontStore.openBundled(FontStore.bundledPath(file))) {
                Files.copy(in, copy);
            }
            SystemFonts.Face face = SystemFonts.facesIn(copy).get(0);
            assertEquals(FontStore.MONO_MIN_WEIGHT, face.minWeight(), file);
            assertEquals(FontStore.MONO_MAX_WEIGHT, face.maxWeight(), file);
            assertEquals(FontStore.MONO_DEFAULT_WEIGHT, face.weight(), file);
        }
    }
}
