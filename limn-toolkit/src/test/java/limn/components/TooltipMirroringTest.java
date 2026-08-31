package limn.components;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tooltip is the one surface a {@link Scene} paints itself rather than delegating to a widget,
 * so it is the one place the inherited axes are read outside an {@code onPaint}. It already
 * resolved the anchor's size step; this pins that it resolves the anchor's direction too.
 *
 * <p>Two things move together and are asserted together: which side of the pointer the panel opens
 * on, and which end of the panel the text sits against. A panel that opened the right way with its
 * text pinned to the wrong pad would look almost correct, which is the failure worth a test.
 */
class TooltipMirroringTest extends ComponentTestBase {

    /** The tooltip shows behind a dwell, so this runs on a clock a test can move. */
    private final java.util.concurrent.atomic.AtomicLong nanos =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Swaps the base's wall clock for one this test drives, keeping the base's field so its own
     * teardown still uninstalls the runtime that is actually installed.
     */
    @org.junit.jupiter.api.BeforeEach
    void installControllableClock() {
        Ui.uninstall(runtime);
        runtime = new UiRuntime(nanos::get, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
    }

    private static final float EPS = 1e-3f;
    private static final float SCENE_W = 400;
    private static final float SCENE_H = 200;
    /** "abcd" under {@link #RULER}: four clusters at 10pt. */
    private static final String TIP = "abcd";
    private static final float TIP_WIDTH = 40;

    private static final class Box extends Widget {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(SCENE_W, SCENE_H);
        }
    }

    /** Records the panel's rect and the x its text was drawn at. */
    private static final class TooltipCanvas extends FakeCanvas {
        final List<float[]> rounds = new ArrayList<>();
        float textX = Float.NaN;

        TooltipCanvas() {
            super(SCENE_W, SCENE_H);
        }

        @Override
        public void fillRoundRect(float x, float y, float w, float h, float r, Paint paint) {
            rounds.add(new float[]{x, w});
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            textX = x;
        }
    }

    /** Hovers the box, waits out the dwell, paints, and hands back what was drawn. */
    private TooltipCanvas hover(LayoutDirection direction) {
        Box box = new Box();
        box.setTooltip(TIP);
        box.setLayoutDirection(direction);
        Scene scene = new Scene(box);
        scene.setTextRuler(RULER);
        scene.bind(new StubWindow(false));
        scene.layoutPass(SCENE_W, SCENE_H);

        scene.mouseMoved(200, 100);
        scene.inputBatchEnded();
        // The show is posted behind the dwell; move the clock past it and let it fire.
        nanos.addAndGet(5_000_000_000L);
        runtime.drain();

        TooltipCanvas canvas = new TooltipCanvas();
        scene.renderFrame(canvas);
        return canvas;
    }

    @Test
    void thePanelOpensOnTheSideReadingStartsFromAndItsTextSitsInThatPad() {
        TooltipCanvas ltr = hover(LayoutDirection.LTR);
        float[] ltrPanel = panelOf(ltr);
        assertEquals(200 + 12, ltrPanel[0], EPS, "the default is unchanged: right of the pointer");
        float padH = ltr.textX - ltrPanel[0];
        assertTrue(padH > 0, "and its text sits inside the left pad");

        TooltipCanvas rtl = hover(LayoutDirection.RTL);
        float[] rtlPanel = panelOf(rtl);
        assertEquals(200 - 12 - rtlPanel[1], rtlPanel[0], EPS, "left of the pointer");
        assertEquals(rtlPanel[0] + rtlPanel[1] - padH - TIP_WIDTH, rtl.textX, EPS,
                "and its text sits inside the pad on the other end");
    }

    @Test
    void thePanelIsTheSameWidthInBothDirections() {
        // A panel sized from one shaping and drawn from another would differ here, which is the
        // way the two could quietly disagree about where the second pad ends.
        assertEquals(panelOf(hover(LayoutDirection.LTR))[1],
                panelOf(hover(LayoutDirection.RTL))[1], EPS);
    }

    /** The tooltip panel: the widest round rect of the frame, which nothing else here paints. */
    private static float[] panelOf(TooltipCanvas canvas) {
        assertTrue(!canvas.rounds.isEmpty(), "the tooltip was painted");
        float[] widest = canvas.rounds.get(0);
        for (float[] r : canvas.rounds) {
            if (r[1] > widest[1]) {
                widest = r;
            }
        }
        return widest;
    }
}
