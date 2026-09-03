package limn.backend.lwjgl;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.FontCatalog;
import limn.graphics.Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.Platform;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operating system's families reaching {@link Fonts#available()} by the road a font picker
 * takes: the first listing kicks the enumeration, the enumeration lands on the UI thread, the
 * store re-installs the catalog, and a listener rebuilds. Wired exactly as the backend wires it
 * at startup, minus the window, so it runs headless against this machine's real font
 * directories (the parser reads name tables only; nothing here needs GL).
 *
 * <p>The sibling tests hand {@link FontStore#setSystemFaces} a face of their own; this one lets
 * the store go and look, because the failure it guards against is a chain with a missing link
 * (a scan never kicked, a landing nobody was told about) rather than a parser defect.
 */
class SystemFontCatalogTest {

    private ExecutorService workers;
    private UiRuntime runtime;
    private FontCatalog catalog;
    private final AtomicInteger changes = new AtomicInteger();
    private final Runnable listener = changes::incrementAndGet;

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        Fonts.addChangeListener(listener);
    }

    @AfterEach
    void uninstallRuntime() {
        Fonts.removeChangeListener(listener);
        if (catalog != null) {
            Fonts.uninstallCatalog(catalog);
        }
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    @Test
    void theFirstListingBringsTheOperatingSystemsFamiliesToEveryListener() {
        Assumptions.assumeFalse(SystemFonts.scan().isEmpty(), "no fonts installed on this machine");
        try (FontStore store = new FontStore()) {
            catalog = store::families;
            Fonts.installCatalog(catalog);
            store.setCatalogChangedNotifier(() -> Fonts.installCatalog(catalog));
            changes.set(0);

            List<String> atStartup = Fonts.available();
            assertTrue(store.systemScanRequested(), "listing the families is what starts the scan");
            pumpUntil(() -> changes.get() > 0);

            List<String> enumerated = Fonts.available();
            assertTrue(enumerated.size() > atStartup.size(),
                    "the enumeration must add to the bundled families: " + enumerated);
            assertTrue(enumerated.containsAll(atStartup), "and take none of them away");
            Set<String> distinct = new java.util.HashSet<>(enumerated);
            assertTrue(distinct.size() == enumerated.size(), "a family is listed once: " + enumerated);
            if (Platform.get() == Platform.MACOSX) {
                // Two faces every macOS ships in /System/Library/Fonts, one of them a .ttc: the
                // names a designer looks for first, and the two file shapes the parser must read.
                assertTrue(enumerated.contains("Helvetica Neue"), "Helvetica Neue in " + enumerated);
                assertTrue(enumerated.contains("Menlo"), "Menlo in " + enumerated);
            }
            assertFalse(store.families().stream().anyMatch(f -> f.startsWith(".")),
                    "the store must not offer what the parser filters");
        }
    }

    private void pumpUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0) {
            runtime.drain();
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean(), "the enumeration never landed on the UI thread");
    }
}
