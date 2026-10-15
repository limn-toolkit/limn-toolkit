package limn.demo;

import limn.components.Theme;
import limn.graphics.Image;
import limn.graphics.ImageDecoder;
import limn.graphics.Images;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testfixtures.Work;
import limn.testing.AllocationProbe;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The performance budget of a whole application: how much the Showcase's first screen is to build
 * and to draw, and that it asks for nothing once it is on screen. In work and not in time, as
 * the toolkit's own budget is and for its reason: the count is the same on every machine.
 */
class ShowcaseBudgetTest {

    private static final ImageDecoder DECODER = bytes -> new Image(16, 16, new byte[16 * 16 * 4]);

    private static final limn.graphics.SvgRenderer RASTERIZER = limn.testfixtures.FakeSvg.renderer((svg, px) -> new Image(1, 1, new byte[4]));

    private long now;
    private static final int WIDTH = 1100;
    private static final int HEIGHT = 760;

    private HeadlessUi ui;
    private HeadlessBackend backend;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(() -> now);
        I18n.setLocale(Locale.ENGLISH);
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
        TextRulers.install(HeadlessWindow.RULER);
        Images.installDecoder(DECODER);
        limn.graphics.SvgIcon.installRenderer(RASTERIZER);
        backend = new HeadlessBackend(ui.runtime());
    }

    @AfterEach
    void uninstallRuntime() {
        limn.graphics.SvgIcon.uninstallRenderer(RASTERIZER);
        Images.uninstallDecoder(DECODER);
        TextRulers.uninstall(HeadlessWindow.RULER);
        ui.close();
    }

    private static int count(Widget<?> root) {
        int n = 1;
        for (Widget<?> child : root.children()) {
            n += count(child);
        }
        return n;
    }

    @Test
    void theFirstScreenIsBuiltAndDrawnWithinItsBudget() {
        Work.Counting canvas = new Work.Counting(WIDTH, HEIGHT);
        Scene scene = AppScene.create().scene();
        scene.layoutPass(WIDTH, HEIGHT);
        scene.renderFrame(canvas.canvas);
        Work.within("widgets of the Showcase's first screen", count(scene.root()), 170); // 146 counted
        Work.within("drawing commands of the Showcase's first frame", canvas.take(), 230); // 230
        // 225 to 230 on 2026-10-15: the five menus of the menu bar carry access letters, and each
        // underline under one is a line drawn.
        if (AllocationProbe.isSupported()) {
            long bytes = AllocationProbe.leastAllocatedBy(() -> {
                Scene again = AppScene.create().scene();
                again.layoutPass(WIDTH, HEIGHT);
                again.renderFrame(canvas.canvas);
            }, 4);
            Work.within("bytes allocated to build the Showcase and draw its first frame", bytes, 1_550_000); // 1 032 240
        }
    }

    @Test
    void onceItsOpeningHasPlayedTheShowcaseAsksForNoFrame() throws InterruptedException {
        Scene scene = AppScene.create().scene();
        HeadlessWindow window = backend.open(AppScene.TITLE, WIDTH, HEIGHT);
        scene.bind(window);
        // The opening's transitions run on the wall clock, so they are given time to end: the
        // only time in this budget, and a deadline that a machine however slow does not meet late.
        long deadline = System.nanoTime() + 20_000_000_000L;
        window.frame();
        while (window.takeFrameRequests() > 0) {
            assertTrue(System.nanoTime() < deadline, "the Showcase never came to rest: something animates for ever");
            Thread.sleep(5);
            ui.pumpUntil(() -> true);
            window.frame();
        }
        int asked = 0;
        for (int frame = 0; frame < 60; frame++) {
            Thread.sleep(2);
            ui.pumpUntil(() -> true);
            window.frame();
            asked += window.takeFrameRequests();
        }
        assertEquals(0, asked, "an application nobody touches asks for no frame, so it costs no processor");
    }
}
