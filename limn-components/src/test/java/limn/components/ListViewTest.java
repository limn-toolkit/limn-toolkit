package limn.components;

import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The virtualization contract of {@link ListView}: only visible rows get a
 * widget (even with variable heights), the anchor advances correctly across
 * differently-sized rows, and keyboard/mouse selection works, all headless.
 *
 * <p>Plus the size-step contract: the list is a propagator (its rows resolve the step
 * themselves), its two free-axis fallbacks come from the token row, and the wheel detent and
 * the selection ring's weights stay locked at every step.
 */
class ListViewTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;

    /** Fixed-height cell (its measured height is the row height). */
    static final class Cell extends Widget {
        final float rowHeight;

        Cell(float rowHeight) {
            this.rowHeight = rowHeight;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), rowHeight);
        }
    }

    private final AtomicInteger created = new AtomicInteger();

    /** Adapter over {@code count} rows whose height comes from {@code heightOf(index)}, pooled. */
    private ListView list(int count, java.util.function.IntToDoubleFunction heightOf) {
        return new ListView(new ListView.Adapter() {
            private final Deque<Cell> pool = new ArrayDeque<>();

            @Override
            public int rowCount() {
                return count;
            }

            @Override
            public Widget rowAt(int index) {
                float h = (float) heightOf.applyAsDouble(index);
                Cell cell = pool.isEmpty() ? create(h) : pool.pop();
                return cell;
            }

            @Override
            public void recycle(Widget widget) {
                pool.push((Cell) widget);
            }

            private Cell create(float h) {
                created.incrementAndGet();
                return new Cell(h);
            }
        });
    }

    private Scene scene(ListView list, FakeCanvas canvas) {
        Scene scene = new Scene(list);
        scene.setTextRuler(RULER);
        scene.renderFrame(canvas);
        return scene;
    }

    /** Row bound to a datum at creation: stale if reused across a refresh. */
    static final class BoundRow extends Widget {
        final String value;

        BoundRow(String value) {
            this.value = value;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 40);
        }
    }

    @Test
    void refreshRebindsMountedRows() {
        java.util.List<String> data = new java.util.ArrayList<>(java.util.List.of("A", "B", "C"));
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return data.size();
            }

            @Override
            public Widget rowAt(int index) {
                return new BoundRow(data.get(index));
            }
        });
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(list, canvas);
        assertTrue(mountedValues(list).contains("A"), "sanity: 'A' visible before the change");

        data.remove(0); // delete the first datum
        list.refresh();
        scene.renderFrame(canvas);

        java.util.List<String> values = mountedValues(list);
        assertTrue(!values.contains("A"), "deleted datum must vanish from mounted rows: " + values);
        assertTrue(values.containsAll(java.util.List.of("B", "C")), "remaining data rebind: " + values);
    }

    private static java.util.List<String> mountedValues(ListView list) {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (Widget child : list.children()) {
            if (child instanceof BoundRow row) {
                values.add(row.value);
            }
        }
        return values;
    }

    @Test
    void selectionSetBeforeFirstLayoutIsRevealedByIt() {
        // The ComboBox-popup pattern: the selection is set while the list has
        // no size yet; the first layout must still bring it into view instead
        // of opening scrolled to the top.
        ListView list = list(200, i -> 40);
        list.setSelectedIndex(150);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(list, canvas); // first frame (layout may take two passes)
        scene.renderFrame(canvas);

        assertTrue(list.firstVisibleIndex() > 100,
                "the list opened at the selection, not the top: " + list.firstVisibleIndex());
    }

    @Test
    void scrollShiftsMountedRowCoordinatesSynchronously() {
        // The Scrollable contract: nested reveals re-read child coordinates in
        // the same pass, so a scroll may not defer the position update to the
        // next layout (an outer scroller would see a phantom rect).
        ListView list = list(1000, i -> 40);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        scene(list, canvas);
        Widget firstRow = list.children().stream()
                .filter(c -> c instanceof Cell).findFirst().orElseThrow();
        float before = firstRow.y();
        list.scrollBy(120); // no frame rendered in between
        assertEquals(before - 120, firstRow.y(), 0.5f, "rows move in the same call");
    }

    @Test
    void scrollingMovesTheMountedRowsWithoutRelayingThemOut() {
        // A row's own layout does not depend on where the row sits, so re-running it as the row
        // slides computes the answer it already had (once per mounted row, per wheel detent, per
        // drag frame), and the layout pass the scroll schedules runs it again regardless. Counted
        // rather than argued, because the difference does not show on screen: nothing here is
        // about what is painted, only about how much work paints it.
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return 1000;
            }

            @Override
            public Widget rowAt(int index) {
                return new CountingCell(40);
            }
        });
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(list, canvas);
        List<CountingCell> rows = list.children().stream()
                .filter(c -> c instanceof CountingCell).map(c -> (CountingCell) c).toList();
        assertFalse(rows.isEmpty(), "sanity: rows are mounted");
        int before = rows.stream().mapToInt(c -> c.layouts).sum();

        list.scrollBy(7); // less than a row: the same rows, seven points higher

        assertEquals(before, rows.stream().mapToInt(c -> c.layouts).sum(),
                "sliding a row is not a reason to lay it out again");
        assertEquals(-7, rows.get(0).y(), EPS, "and it did move");

        // The pass the scroll schedules is what re-lays them out, which is where the work belongs:
        // mounting and recycling happen there and nowhere else.
        scene.renderFrame(canvas);
        assertTrue(rows.stream().mapToInt(c -> c.layouts).sum() > before,
                "the scheduled pass still lays the rows out");
    }

    /** Cell that counts its own layouts: the scroll fast path is measured, not eyeballed. */
    static final class CountingCell extends Widget {
        private final float rowHeight;
        int layouts;

        CountingCell(float rowHeight) {
            this.rowHeight = rowHeight;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), rowHeight);
        }

        @Override
        protected void onLayout() {
            layouts++;
        }
    }

    @Test
    void onlyMaterializesVisibleRowsOfAHugeList() {
        ListView list = list(1_000_000, i -> 40);
        FakeCanvas canvas = new FakeCanvas(300, 200); // ~5 rows visible
        scene(list, canvas);

        assertEquals(1_000_000, list.rowCount());
        // ~5 rows + the scrollbar child, never a million.
        assertTrue(list.children().size() <= 9, "materialized: " + list.children().size());
        assertTrue(created.get() <= 9, "created: " + created.get());
    }

    @Test
    void poolStaysBoundedWhileScrolling() {
        ListView list = list(1_000_000, i -> 40);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(list, canvas);

        for (int i = 0; i < 500; i++) {
            list.scrollBy(37);
            scene.renderFrame(canvas);
        }
        assertTrue(list.firstVisibleIndex() > 100, "actually scrolled: " + list.firstVisibleIndex());
        assertTrue(created.get() <= 10, "no unbounded creation: " + created.get());
    }

    @Test
    void anchorAdvancesAcrossVariableHeights() {
        // Alternating small headers (30) and tall cards (90).
        ListView list = list(1000, i -> i % 2 == 0 ? 30 : 90);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(list, canvas);
        assertEquals(0, list.firstVisibleIndex());

        list.scrollBy(30); // scroll past the first (header) row exactly
        scene.renderFrame(canvas);
        assertEquals(1, list.firstVisibleIndex(), "one 30pt header scrolled off");

        list.scrollBy(90); // scroll past the next (card) row
        scene.renderFrame(canvas);
        assertEquals(2, list.firstVisibleIndex(), "one 90pt card scrolled off");
    }

    @Test
    void endHomeAndArrowsSelect() {
        ListView list = list(100, i -> 40);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(list, canvas);
        list.requestFocus();

        scene.keyEvent(Keys.END, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(99, list.selectedIndex());
        scene.renderFrame(canvas);
        assertTrue(list.firstVisibleIndex() >= 90 && list.firstVisibleIndex() <= 99,
                "End reveals the last row: first=" + list.firstVisibleIndex());

        scene.keyEvent(Keys.HOME, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(0, list.selectedIndex());

        scene.keyEvent(Keys.DOWN, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(1, list.selectedIndex());
    }

    @Test
    void clickSelectsTheRowUnderThePointer() {
        ListView list = list(100, i -> 40);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(list, canvas);

        // x=10 (left, away from the scrollbar), y=90 → row 2 (rows at 0,40,80,…).
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 90);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 10, 90);
        scene.inputBatchEnded();
        assertEquals(2, list.selectedIndex());
    }

    // ------------------------------------------------------------ size steps

    private static Constraints unbounded() {
        return new Constraints(0, Constraints.UNBOUNDED_LIMIT, 0, Constraints.UNBOUNDED_LIMIT);
    }

    @Test
    void unboundedMeasureFallsBackToTheStepsWidthAndRowSeed() {
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            ListView list = list(100, i -> 40);
            list.setControlSize(step);

            Size size = list.measure(unbounded());
            assertEquals(t.listWidth(), size.width(), EPS,
                    step + ": the unbounded width is listWidth");
            // Six rows of the seed: the seed is the only row height known before layout has
            // measured one, and the 6 is a row COUNT that must not move with the step.
            assertEquals(6 * t.listRowSeed(), size.height(), EPS,
                    step + ": the unbounded height is 6 seed rows");
        }
    }

    @Test
    void aMeasuredRowHeightSupersedesTheSeed() {
        // The seed exists for frame 0 only; once real rows have been measured the estimate is
        // theirs, so the same list measures the same intrinsic height at every step.
        for (ControlSize step : ControlSize.values()) {
            ListView list = list(100, i -> 40);
            list.setControlSize(step);
            FakeCanvas canvas = new FakeCanvas(300, 200);
            scene(list, canvas);

            assertEquals(6 * 40, list.measure(unbounded()).height(), EPS,
                    step + ": 40pt rows were measured, so the seed is out of the picture");
        }
    }

    @Test
    void oneWheelNotchTravelsTheSameDistanceAtEveryStep() {
        // A detent is a device unit (Strokes.WHEEL_STEP): the same flick must move the same
        // physical distance in a dense list and a roomy one.
        for (ControlSize step : ControlSize.values()) {
            ListView list = list(1000, i -> 40);
            list.setControlSize(step);
            FakeCanvas canvas = new FakeCanvas(300, 200);
            Scene scene = scene(list, canvas);
            Widget firstRow = list.children().stream()
                    .filter(c -> c instanceof Cell).findFirst().orElseThrow();
            float before = firstRow.y();

            scene.scrolled(0, -1, 10, 50); // one notch down, away from the scrollbar
            scene.inputBatchEnded();

            assertEquals(before - Strokes.WHEEL_STEP, firstRow.y(), EPS,
                    step + ": one notch is 48 logical points");
        }
    }

    /** Row that sizes itself from the step <em>it</em> resolves, never one handed down. */
    static final class StepRow extends Widget {
        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), Theme.current().tokensFor(this).controlHeight());
        }
    }

    @Test
    void rowsResolveTheListsStepThemselves() {
        // The whole answer to "what does a SMALL ListView mean": rows are widgets in the list's
        // subtree, added before they are measured, so the resolution walk reaches them. The list
        // imposes no row height of its own.
        for (ControlSize step : ControlSize.values()) {
            ListView list = new ListView(new ListView.Adapter() {
                @Override
                public int rowCount() {
                    return 50;
                }

                @Override
                public Widget rowAt(int index) {
                    return new StepRow();
                }
            });
            list.setControlSize(step);
            FakeCanvas canvas = new FakeCanvas(300, 200);
            scene(list, canvas);

            Widget row = list.children().stream()
                    .filter(c -> c instanceof StepRow).findFirst().orElseThrow();
            assertEquals(SizeTokens.of(step).controlHeight(), row.height(), EPS,
                    step + ": the row measured at the step it inherited from the list");
        }
    }

    /** Records the selection ring: the only stroked round rect in a ListView frame. */
    private static final class RingCanvas extends FakeCanvas {
        RoundRect ring;
        float ringStroke = Float.NaN;

        RingCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawRoundRect(RoundRect roundRect, float strokeWidth, Paint paint) {
            ring = roundRect;
            ringStroke = strokeWidth;
        }
    }

    @Test
    void theSelectionRingKeepsLockedWeightsAndTakesOnlyItsRadiusFromTheStep() {
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            ListView list = list(100, i -> 40);
            list.setControlSize(step);
            RingCanvas canvas = new RingCanvas(300, 200);
            Scene scene = scene(list, canvas);
            list.setSelectedIndex(0);
            scene.renderFrame(canvas); // unfocused: focusFade is settled at 0, so 1.5pt resting

            assertNotNull(canvas.ring, step + ": the selected row is ringed");
            assertEquals(Strokes.FOCUS_RING_THIN, canvas.ringStroke, EPS,
                    step + ": the resting weight is locked at 1.5");
            assertEquals(Strokes.FOCUS_RING_THIN, canvas.ring.x(), EPS,
                    step + ": the inset is a half-stroke consequence, locked with the weight");
            assertEquals(300 - 2 * Strokes.FOCUS_RING_THIN, canvas.ring.width(), EPS,
                    step + ": the shrink is twice the inset");
            assertEquals(40 - 2 * Strokes.FOCUS_RING_THIN, canvas.ring.height(), EPS,
                    step + ": the ring is inset inside the row, not scaled to it");
            assertEquals(t.radiusMedium(), canvas.ring.topLeft(), EPS,
                    step + ": only the corner radius moves with the step");
        }
    }

    @Test
    void activateFiresForTheSelection() {
        ListView list = list(100, i -> 40);
        AtomicInteger activated = new AtomicInteger(-1);
        list.onActivate(activated::set);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(list, canvas);
        list.requestFocus();

        list.setSelectedIndex(7);
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(7, activated.get());
    }

    @Test
    void aReservedBarNarrowsTheRowsInsteadOfCoveringThem() {
        // A list of records puts a count, a date or a status at the right edge of a
        // row; a thumb over it is the defect this mode exists to prevent.
        List<Widget> rows = new ArrayList<>();
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return 40;
            }

            @Override
            public Widget rowAt(int index) {
                Widget row = new Label("row " + index);
                rows.add(row);
                return row;
            }
        }).setBarLayout(ScrollGutters.Layout.RESERVED);

        Scene host = new Scene(list);
        host.setTextRuler(RULER);
        host.layoutPass(200, 100);

        assertFalse(rows.isEmpty(), "no row was ever mounted");
        for (Widget row : rows) {
            assertEquals(200 - ScrollBar.thickness(), row.width(), 1e-3f,
                    "a mounted row still ran under the bar");
        }
    }

    @Test
    void anOverlaidBarLeavesTheRowsFullWidth() {
        List<Widget> rows = new ArrayList<>();
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return 40;
            }

            @Override
            public Widget rowAt(int index) {
                Widget row = new Label("row " + index);
                rows.add(row);
                return row;
            }
        });

        Scene host = new Scene(list);
        host.setTextRuler(RULER);
        host.layoutPass(200, 100);

        assertEquals(200, rows.get(0).width(), 1e-3f, "the default must not have changed");
    }

    /**
     * The two idioms this class used to break: every other configuration method on it chains, and
     * every other widget in the set refuses a null listener. Its selection setter returned void
     * and its listener setters stored whatever they were handed, null included, which is why the
     * fire sites all carried a null guard.
     */
    @Test
    void theSelectionSetterChainsAndTheListenersRefuseNull() {
        ListView list = list(3, index -> 20);

        assertSame(list, list.setSelectedIndex(1), "the setter chains, as setScrollbarPolicy does");
        assertEquals(1, list.selectedIndex());
        assertThrows(NullPointerException.class, () -> list.onSelect(null));
        assertThrows(NullPointerException.class, () -> list.onActivate(null));
    }

    // ------------------------------------------------------- the selection contract

    /**
     * {@code -1} reads the empty selection and never sets it. Clamping {@code -1} to row 0 made
     * the idiomatic "clear the selection" select the first row, scroll it into view and tell the
     * listener a row had been chosen.
     */
    @Test
    void minusOneIsRefusedRatherThanTakenAsRowZero() {
        ListView list = list(10, index -> 20);
        AtomicInteger heard = new AtomicInteger(-2);
        list.onSelect(heard::set);

        assertThrows(IndexOutOfBoundsException.class, () -> list.setSelectedIndex(-1));

        assertEquals(-1, list.selectedIndex(), "nothing was selected");
        assertEquals(-2, heard.get(), "and nothing was announced");
    }

    @Test
    void clearSelectionIsTheWayToNoSelectionAndSaysSo() {
        ListView list = list(10, index -> 20);
        list.setSelectedIndex(3);
        AtomicInteger heard = new AtomicInteger(-2);
        list.onSelect(heard::set);

        assertSame(list, list.clearSelection(), "the clear chains, as the setter does");
        assertEquals(-1, list.selectedIndex());
        assertEquals(-1, heard.get(), "a listener bound to the selection hears it empty");

        heard.set(-2);
        list.clearSelection();
        assertEquals(-2, heard.get(), "clearing an already-empty selection says nothing");
    }

    /**
     * The public setter throws on an index that is not a row; the keys do not, because a key
     * pressed at an end is a key with nowhere to go rather than a caller's bad index. Every arrow
     * and jump key runs through the same clamp, on an empty list included.
     */
    @Test
    void keysAtTheEndsStopThereInsteadOfThrowing() {
        ListView list = list(3, index -> 40);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(list, canvas);
        list.requestFocus();

        for (int i = 0; i < 6; i++) {
            scene.keyEvent(Keys.DOWN, true, false, 0);
        }
        scene.inputBatchEnded();
        assertEquals(2, list.selectedIndex(), "Down past the last row stays on it");

        for (int i = 0; i < 6; i++) {
            scene.keyEvent(Keys.UP, true, false, 0);
        }
        scene.keyEvent(Keys.PAGE_UP, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(0, list.selectedIndex(), "Up and Page Up past the first row stay on it");

        ListView empty = list(0, index -> 40);
        Scene emptyScene = scene(empty, canvas);
        empty.requestFocus();
        for (int key : new int[] {Keys.HOME, Keys.END, Keys.DOWN, Keys.UP,
                                  Keys.PAGE_DOWN, Keys.PAGE_UP}) {
            emptyScene.keyEvent(key, true, false, 0);
        }
        emptyScene.inputBatchEnded();
        assertEquals(-1, empty.selectedIndex(), "an empty list has nothing for a key to reach");
    }

    /**
     * The adapter shrank past the selected row, so the selection moved. Silence here leaves a
     * detail pane bound to {@code onSelect} showing a record that was deleted.
     */
    @Test
    void refreshTellsTheListenerWhenShrinkingDataMovedTheSelection() {
        int[] count = {10};
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return count[0];
            }

            @Override
            public Widget rowAt(int index) {
                return new Cell(20);
            }
        });
        list.setSelectedIndex(9);
        AtomicInteger heard = new AtomicInteger(-2);
        list.onSelect(heard::set);

        count[0] = 4;
        list.refresh();
        assertEquals(3, list.selectedIndex(), "the selection lands on the new last row");
        assertEquals(3, heard.get());

        heard.set(-2);
        list.refresh();
        assertEquals(-2, heard.get(), "a refresh that moves nothing announces nothing");

        count[0] = 0;
        list.refresh();
        assertEquals(-1, list.selectedIndex(), "an emptied list has no selection left");
        assertEquals(-1, heard.get());
    }
}
