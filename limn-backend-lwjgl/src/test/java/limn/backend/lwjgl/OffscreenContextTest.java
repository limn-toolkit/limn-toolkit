package limn.backend.lwjgl;

import limn.graphics.Color;
import limn.graphics.Image;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend's own context for pictures off screen, let go of at every sign that the machine was
 * away (a sleep, a step of the clock) and made again at the next picture: the picture after is
 * drawn as before, and letting go destroys the context rather than dropping it. Dropped, every
 * wake left a whole context behind on macOS, about 300 kB of the GPU's memory in two regions.
 */
class OffscreenContextTest {

    private FontStore fonts;
    private HeadlessUi ui;
    private OffscreenContext context;

    @BeforeEach
    void open() {
        HeadlessGl.assumeAvailable();
        ui = new HeadlessUi();
        fonts = new FontStore();
        context = new OffscreenContext(fonts, false);
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.dispose();
        }
        HeadlessGl.restoreCurrent();
        if (fonts != null) {
            fonts.close();
        }
        if (ui != null) {
            ui.close();
        }
    }

    private Image picture() {
        Image image = context.paint(64, 64, 1, c -> c.fillRoundRect(4, 4, 56, 56, 8, Color.rgb(0x2060E0)));
        assertNotNull(image, () -> "no picture: " + context.failure());
        return image;
    }

    @Test
    void aPictureAfterTheContextWasLetGoIsDrawnAsBefore() {
        Image before = picture();
        // On ANGLE this class's own context is on a window of ANGLE's display, as a window of the
        // application's would be: said to have no window open, the context would end the display
        // under it, and every test after this one would draw on nothing.
        boolean windowOpen = HeadlessGl.isEs();
        for (int i = 0; i < 3; i++) {
            context.forgetLost(windowOpen);
            Image after = picture();
            assertTrue(java.util.Arrays.equals(before.pixels(), after.pixels()), "the same picture, round " + i);
        }
    }

    @Test
    void lettingTheContextGoLeavesNoneOfItsMemoryBehind() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac"),
                "the GPU's regions are read with vmmap, on macOS");
        for (int i = 0; i < 10; i++) {
            picture();
            context.forgetLost(HeadlessGl.isEs());
        }
        int before = gpuRegions();
        for (int i = 0; i < 60; i++) {
            picture();
            context.forgetLost(HeadlessGl.isEs());
        }
        int after = gpuRegions();
        // Dropped without being destroyed, each context left two regions: 120 here.
        assertTrue(after - before < 20, "GPU regions " + before + " -> " + after + " over 60 contexts let go");
    }

    /** The count of the process's IOAccelerator (graphics) regions, vmmap's last column. */
    private static int gpuRegions() throws Exception {
        Process vm = new ProcessBuilder("vmmap", "--summary", Long.toString(ProcessHandle.current().pid()))
                .redirectErrorStream(true).start();
        for (String line : new String(vm.getInputStream().readAllBytes()).split("\n")) {
            if (line.startsWith("IOAccelerator (graphics)")) {
                String[] columns = line.trim().split("\\s+");
                return Integer.parseInt(columns[columns.length - 1]);
            }
        }
        Assumptions.abort("vmmap shows no IOAccelerator (graphics) region");
        return 0;
    }
}
