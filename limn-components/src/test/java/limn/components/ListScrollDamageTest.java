package limn.components;

import limn.graphics.Rect;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scrolling a list repaints the list, not the window.
 *
 * <p>It is the most common heavy interaction there is, and it used to ask for a whole-scene
 * layout on every wheel detent, which a scene answers with full damage, because a layout pass
 * may move any widget without that widget invalidating where it used to be. So every detent
 * repainted the window, doing exactly the work the damage machinery exists to avoid, and a
 * window that also held a video or an animation paid the full composite per tick.
 *
 * <p>The list asks for a <em>contained</em> layout instead, which it is entitled to because it
 * clips its rows and a scroll cannot change its own box. Both halves are checked by the scene
 * rather than trusted (see the toolkit's own suite), so what is asserted here is the outcome:
 * the frame stays partial and the damage stays inside the list.
 */
class ListScrollDamageTest extends ComponentTestBase {

    private static final class Cell extends Widget {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 24);
        }
    }

    private ListView list;
    private Scene scene;
    private AtomicLong nanos;

    private RecordingTestCanvas settled() {
        list = new ListView(new ListView.Adapter() {
            private final Deque<Cell> pool = new ArrayDeque<>();

            @Override
            public int rowCount() {
                return 500;
            }

            @Override
            public Widget rowAt(int index) {
                return pool.isEmpty() ? new Cell() : pool.pop();
            }

            @Override
            public void recycle(Widget widget) {
                pool.push((Cell) widget);
            }
        });
        // Inset, so the window is strictly bigger than the list: damage the size of the frame is
        // then unambiguously "the whole window" rather than "the list happens to fill it".
        nanos = new AtomicLong();
        scene = new Scene(new Padding(Insets.all(40), list), nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);

        RecordingTestCanvas canvas = new RecordingTestCanvas(400, 300);
        boolean quiet = false;
        for (int i = 0; i < 80 && !quiet; i++) {
            // Scene time, not real time: the scrollbar's reveal holds for over a second and then
            // fades, and a loop of real frames never reaches the end of it.
            nanos.addAndGet(200_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            quiet = canvas.nothingPainted();
        }
        assertTrue(quiet, "the list must stop painting before the scroll is measured");
        return canvas;
    }

    private void assertDamagedTheListOnly(RecordingTestCanvas canvas, String what) {
        assertFalse(canvas.cleared, what + " cleared the frame, so it repainted the window");
        Rect clip = canvas.firstClip;
        assertTrue(clip != null, what + " painted without a pass clip, which is a full frame");
        // The list is 320x220 at 40,40; a pass clip carries a 1px anti-aliasing outset.
        assertTrue(clip.x() >= 38 && clip.y() >= 38
                        && clip.width() <= 324 && clip.height() <= 224,
                what + " damaged " + clip + ", which is more than the list");
    }

    @Test
    void aScrollWithinARowDamagesOnlyTheList() {
        RecordingTestCanvas canvas = settled();
        canvas.reset();
        list.scrollBy(7); // less than a row: the same rows, seven points higher
        scene.renderFrame(canvas);
        assertDamagedTheListOnly(canvas, "a scroll of less than a row");
    }

    /**
     * The harder half: crossing a row boundary mounts a row and recycles another, and both go
     * through {@code add}/{@code remove}, which ask for a layout. If that escalated, every
     * detent past a row edge would be a full frame again, which is most of them.
     */
    @Test
    void aScrollThatMountsAndRecyclesRowsAlsoDamagesOnlyTheList() {
        RecordingTestCanvas canvas = settled();
        canvas.reset();
        list.scrollBy(60); // two and a half rows
        scene.renderFrame(canvas);
        assertDamagedTheListOnly(canvas, "a scroll across row boundaries");
        assertTrue(list.firstVisibleIndex() > 0, "the list really did scroll");
    }
}
