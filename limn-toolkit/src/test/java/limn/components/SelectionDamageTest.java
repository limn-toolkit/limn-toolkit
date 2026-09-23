package limn.components;

import limn.testfixtures.IndexedRows;

import limn.components.table.Column;
import limn.components.table.Table;
import limn.graphics.Rect;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * Moving the selection in a list or a table repaints the rows that changed, not the widget.
 *
 * <p>ADR 043 &sect;9.2 and &sect;9.4. These are the toolkit's two most-used widgets and both used
 * to answer an arrow key with {@code invalidate()}, which is the whole widget: correct, and the
 * exact failure mode partial rendering has &mdash; a picture nobody can fault and a repaint out of
 * all proportion to what moved. Under the old full-frame default it cost nothing, because the
 * frame was full anyway; under the proposed one a keyboard walk down a list is a walk down a list.
 *
 * <p>What is asserted is the <b>clip</b>, not the picture: a stale pixel is invisible to an
 * assertion and a rectangle is not. Each widget is boxed strictly inside a larger window, so that
 * "the widget" and "the window" are different rectangles and the question can be asked at all
 * &mdash; without the inset both are the same number and every answer looks like a pass.
 */
class SelectionDamageTest extends ComponentTestBase {

    /** A window big enough that the widget under test is a minority of it. */
    private static final int WINDOW_W = 400;
    private static final int WINDOW_H = 300;
    private static final int INSET = 40;

    private Scene scene;
    private AtomicLong nanos;

    // ------------------------------------------------------------------ the list

    private static final class Cell extends Widget {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 24);
        }
    }

    private ListView<Integer> list;

    private RecordingTestCanvas settledList() {
        list = IndexedRows.list(new IndexedRows() {
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
        return settle(list);
    }

    // ------------------------------------------------------------------ the table

    record Person(String name, int age) {
    }

    private Table<Person> table;

    private RecordingTestCanvas settledTable() {
        List<Person> people = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            people.add(new Person("Person " + i, i % 90));
        }
        table = new Table<>(List.of(
                Column.text("Name", Person::name).width(100),
                Column.numeric("Age", Person::age).width(60)));
        table.setRows(people);
        return settle(table);
    }

    // ------------------------------------------------------------------ the harness

    /**
     * Renders until the widget stops painting of its own accord, then hands back the canvas ready
     * to record one gesture.
     *
     * <p>The settling is the measurement, not preamble. A focus ring fades over 0.14&nbsp;s and a
     * scrollbar's reveal holds for over a second, and either one invalidates the widget once per
     * tick; a reading taken while one is running measures the fade and calls it the gesture. Scene
     * time rather than real time, because a loop of real frames never reaches the end of a
     * second-long hold.
     */
    private RecordingTestCanvas settle(Widget widget) {
        nanos = new AtomicLong();
        scene = new Scene(new Padding(Insets.all(INSET), widget), nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);

        RecordingTestCanvas canvas = new RecordingTestCanvas(WINDOW_W, WINDOW_H);
        widget.requestFocus();
        boolean quiet = false;
        for (int i = 0; i < 120 && !quiet; i++) {
            nanos.addAndGet(200_000_000L);
            canvas.reset();
            scene.renderFrame(canvas);
            quiet = canvas.nothingPainted();
        }
        assertTrue(quiet, "the widget must stop painting before a gesture is measured");
        return canvas;
    }

    /** One key, delivered and rendered, with the canvas reset immediately before it. */
    private void press(RecordingTestCanvas canvas, int key) {
        canvas.reset();
        drive(scene).keyEvent(key, true, false, 0);
        drive(scene).inputBatchEnded();
        scene.renderFrame(canvas);
    }

    /**
     * Asserts the frame was partial and its clip is no taller than {@code maxRows} rows plus the
     * slack a ring and its anti-aliasing need.
     *
     * <p>The height is the whole assertion. Width is not: both widgets draw a selection across
     * their full width, so a row band is as wide as the widget and always will be. What must not
     * happen is a band as tall as the widget.
     */
    private void assertDamagedRowsOnly(RecordingTestCanvas canvas, Widget widget,
                                       float rowHeight, int maxRows, String what) {
        assertFalse(canvas.cleared, what + " cleared the frame, so it repainted the window");
        Rect clip = canvas.firstClip;
        assertTrue(clip != null, what + " painted without a pass clip, which is a full frame");
        float ceiling = maxRows * rowHeight + 8;
        assertTrue(clip.height() <= ceiling,
                what + " damaged " + clip + ", which is " + (clip.height() / rowHeight)
                        + " rows of the widget's " + (widget.height() / rowHeight)
                        + "; the ceiling is " + maxRows);
        assertTrue(clip.width() <= widget.width() + 8,
                what + " damaged " + clip + ", which is wider than the widget");
    }

    // ------------------------------------------------------------------ the list

    @Test
    void aListArrowDamagesTheTwoRowsItMovedBetween() {
        RecordingTestCanvas canvas = settledList();
        list.setSelectedIndex(3);
        press(canvas, Keys.DOWN);
        assertDamagedRowsOnly(canvas, list, 24, 2, "an arrow down in a list");
    }

    /**
     * The one that has to stay whole: a Page key moves the selection far enough to scroll, and a
     * scroll re-mounts every row. Two row bands would be a lie there.
     */
    @Test
    void aListPageStillDamagesTheList() {
        RecordingTestCanvas canvas = settledList();
        list.setSelectedIndex(0);
        // Twice: the first Page lands on the last row already visible and scrolls nothing, which
        // is the behaviour, not a miss. The second is the one that moves the viewport.
        press(canvas, Keys.PAGE_DOWN);
        press(canvas, Keys.PAGE_DOWN);
        assertTrue(list.firstVisibleIndex() > 0, "the list really did scroll");
        assertFalse(canvas.cleared, "a page in a list repainted the whole window");
        Rect clip = canvas.firstClip;
        assertTrue(clip != null && clip.height() <= list.height() + 8,
                "a page in a list damaged " + clip + ", which is more than the list");
    }

    // ------------------------------------------------------------------ the table

    @Test
    void aTableArrowDamagesTheTwoRowsItMovedBetween() {
        RecordingTestCanvas canvas = settledTable();
        float rowHeight = rowHeight(table);
        press(canvas, Keys.DOWN);
        press(canvas, Keys.DOWN);
        assertDamagedRowsOnly(canvas, table, rowHeight, 2, "an arrow down in a table");
    }

    /** The focus cell moves along one row: one row band, not two and not the table. */
    @Test
    void aTableCellStepDamagesOneRow() {
        RecordingTestCanvas canvas = settledTable();
        press(canvas, Keys.DOWN);
        float rowHeight = rowHeight(table);
        press(canvas, Keys.RIGHT);
        assertDamagedRowsOnly(canvas, table, rowHeight, 1, "a cell step in a table");
    }

    private float rowHeight(Table<?> t) {
        SizeTokens tokens = Theme.current().tokensFor(t);
        return Math.max(tokens.controlHeight(),
                RULER.measure("Hg", tokens.body()).height() + 2 * tokens.padV());
    }
}
