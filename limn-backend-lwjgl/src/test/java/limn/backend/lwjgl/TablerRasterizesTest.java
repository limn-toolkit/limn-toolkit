package limn.backend.lwjgl;

import limn.graphics.Image;
import limn.icons.tabler.Tabler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every icon the pack ships is drawn once, through the rasterizer that will draw it in an
 * application. NanoSVG is a small parser (no {@code <use>}, no CSS, no masks or filters),
 * so an upstream release that starts using one of those would otherwise ship a button with
 * nothing on it, and nothing anywhere would say so.
 *
 * <p>This is the whole reason the pack is allowed to be generated from an upstream tarball
 * rather than hand-checked: bumping the pin is safe because failing this is what an
 * unsupported drawing does.
 */
class TablerRasterizesTest {

    /** Big enough that a hairline cannot round away to nothing and be read as a failure. */
    private static final int SIZE = 48;

    private java.util.concurrent.ExecutorService workers;
    private limn.concurrent.UiRuntime runtime;

    @org.junit.jupiter.api.BeforeEach
    void installRuntime() {
        // SvgIcon's per-size cache is UI-thread confined and enforced, so drawing through the
        // real Icon path needs this thread to BE the UI thread, installed and removed per
        // test, because other classes here install one too and Ui.install refuses a second.
        workers = java.util.concurrent.Executors.newFixedThreadPool(1);
        runtime = new limn.concurrent.UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        limn.concurrent.Ui.install(runtime);
        limn.graphics.SvgIcon.installRasterizer(new NanoSvgRasterizer());
    }

    @org.junit.jupiter.api.AfterEach
    void uninstallRuntime() {
        limn.concurrent.Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    @Test
    void everyIconInThePackRasterizesToSomething() {
        List<String> blank = new ArrayList<>();
        List<String> threw = new ArrayList<>();
        int drawn = 0;

        for (String name : Tabler.names()) {
            drawn += draw(name, false, blank, threw);
            if (Tabler.hasFilled(name)) {
                drawn += draw(name, true, blank, threw);
            }
        }

        assertTrue(drawn > 6000, "the pack should ship the whole set; drew " + drawn);
        assertTrue(threw.isEmpty(), "icons the rasterizer could not parse: " + limited(threw));
        assertTrue(blank.isEmpty(), "icons that rasterized to nothing at " + SIZE + "px: "
                + limited(blank));
    }

    private static int draw(String name, boolean filled,
                            List<String> blank, List<String> threw) {
        String label = (filled ? "filled/" : "outline/") + name;
        try {
            // Through the pack's own Icon rather than around it: this covers the blob slicing
            // and the copy that keeps NanoSVG's destructive parse off the shared bytes, which
            // is where a wrong offset would show up as one icon drawing another's shape.
            Image image = (filled ? Tabler.filled(name) : Tabler.outline(name))
                    .image(SIZE, false);
            byte[] pixels = image.pixels();
            for (int i = 3; i < pixels.length; i += 4) {
                if ((pixels[i] & 0xFF) > 8) {
                    return 1;
                }
            }
            blank.add(label);
        } catch (Throwable error) {
            threw.add(label + " (" + error.getClass().getSimpleName() + ")");
        }
        return 1;
    }

    private static String limited(List<String> names) {
        return names.stream().limit(20).toList()
                + (names.size() > 20 ? " (and " + (names.size() - 20) + " more)" : "");
    }
}
