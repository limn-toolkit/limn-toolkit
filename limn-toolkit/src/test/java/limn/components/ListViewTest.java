package limn.components;

import limn.scene.Change;
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

    /** A cell that remembers which row it was built for; not pooled, so the row is unambiguous. */
    static final class IndexCell extends Widget {
        final int index;

        IndexCell(int index) {
            this.index = index;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), 40);
        }
    }

    /**
     * {@code children()} is the bar and then the mounted cells <b>in data order</b>, whichever
     * direction the scroll realized them in.
     *
     * <p>Rows are realized from both ends: the walk that renormalizes the anchor after an upward
     * scroll runs <em>upward</em> and mounts the rows above the anchor in descending order. A
     * container that appended each one as it arrived would leave this list holding 3, 4, 2, 1, 0
     * after the round trip below — and {@code children()} is Tab order, which the class
     * documentation advertises ("clicks on a row's own buttons reach those buttons"), and is also
     * the reading order an assistive technology is given. Its accessible half is pinned by
     * {@link ListViewAccessibilityTest}.
     */
    @Test
    void childrenStayInDataOrderAfterScrollingDownAndBackUp() {
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return 100;
            }

            @Override
            public Widget rowAt(int index) {
                return new IndexCell(index);
            }
        });
        FakeCanvas canvas = new FakeCanvas(300, 200); // five 40pt rows
        Scene scene = scene(list, canvas);

        list.scrollBy(200);
        scene.renderFrame(canvas);
        assertEquals(5, list.firstVisibleIndex(), "one viewport down");
        list.scrollBy(-200);
        scene.renderFrame(canvas);
        assertEquals(0, list.firstVisibleIndex(), "and back");

        List<Widget> children = list.children();
        assertEquals(6, children.size(), "the bar and five rows: " + children);
        assertTrue(children.get(0) instanceof ScrollBar, "the bar keeps position zero");
        for (int i = 1; i < children.size(); i++) {
            assertEquals(i - 1, ((IndexCell) children.get(i)).index,
                    "children() is data order, not the order the scroll realized them in");
        }
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
            // Six rows of the seed: the 6 is a row COUNT that must not move with the step, and
            // the seed is a token that stands still once rows are measured (decision 44).
            assertEquals(6 * t.listRowSeed(), size.height(), EPS,
                    step + ": the unbounded height is 6 seed rows");
        }
    }

    /**
     * Decision 44 (2026-09-14): the unbounded height is the seed's and stands still once rows are
     * measured. Until that day it followed the measured average, so a list in a scroll pane
     * changed its measured size whenever rows of another height scrolled in, and every such
     * contained layout fell back to a full pass of the parent.
     */
    @Test
    void theUnboundedHeightIsTheSeedsAndDoesNotMoveOnceRowsAreMeasured() {
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            ListView list = list(100, i -> i % 2 == 0 ? 40 : 90);
            list.setControlSize(step);
            FakeCanvas canvas = new FakeCanvas(300, 200);
            Scene scene = scene(list, canvas);

            assertEquals(6 * t.listRowSeed(), list.measure(unbounded()).height(), EPS,
                    step + ": rows of 40 and 90 were measured and the preference did not move");

            scene.scrolled(0, -10, 10, 50);
            scene.inputBatchEnded();
            scene.renderFrame(canvas);
            assertEquals(6 * t.listRowSeed(), list.measure(unbounded()).height(), EPS,
                    step + ": nor after a scroll realized rows of the other height");
        }
    }

    @Test
    void setVisibleRowsChangesTheUnboundedHeightAndRefusesLessThanOne() {
        ListView list = list(100, i -> 40);
        SizeTokens t = SizeTokens.of(ControlSize.MEDIUM);
        assertEquals(6, list.visibleRows(), "the default");

        list.setVisibleRows(3);

        assertEquals(3 * t.listRowSeed(), list.measure(unbounded()).height(), EPS,
                "three seed rows: a count of the seed, never of the realized rows");
        assertEquals(t.listWidth(), list.measure(unbounded()).width(), EPS,
                "the width is untouched");
        assertThrows(IllegalArgumentException.class, () -> list.setVisibleRows(0));
        assertEquals(3, list.visibleRows(), "a refused count changes nothing");
        assertEquals(200, list.measure(new Constraints(0, 300, 0, 200)).height(), EPS,
                "a bounded height from the parent wins over the preference");
    }

    /**
     * Decision 44's second half: a wheel that finds the list at either end passes to the scroller
     * that holds it, so a list inside a scroll pane is not a wall the wheel cannot get past.
     * The list is a column's first child inside a vertical scroll pane, so it measures unbounded
     * (six seed rows) and the column overflows the pane by the box below it.
     */
    @Test
    void aWheelAtEitherEndOfTheListPassesToTheScrollerThatHoldsIt() {
        ListView list = list(20, i -> 40);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(list);
        column.add(new Widget() {
            @Override
            protected Size onMeasure(Constraints c) {
                return c.constrain(c.maxWidth(), 400);
            }
        });
        ScrollView pane = new ScrollView(column);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = new Scene(pane);
        scene.setTextRuler(RULER);
        scene.renderFrame(canvas);
        float seedHeight = 6 * SizeTokens.of(ControlSize.MEDIUM).listRowSeed();
        assertEquals(seedHeight, list.height(), EPS, "the fixture: the list is six seed rows tall");
        float listMax = 20 * 40 - seedHeight;

        // Down: the list takes every detent until it rests on its last row.
        int notches = 0;
        while (pane.offsetY() == 0 && notches < 100) {
            scene.scrolled(0, -1, 50, 50);
            scene.inputBatchEnded();
            scene.renderFrame(canvas);
            notches++;
            if (notches * Strokes.WHEEL_STEP < listMax) {
                assertEquals(0, pane.offsetY(), EPS,
                        "the pane stays put while the list can still scroll: notch " + notches);
            }
        }
        assertEquals((int) Math.ceil(listMax / Strokes.WHEEL_STEP) + 1, notches,
                "the first detent the list cannot use is the pane's");
        assertEquals(Strokes.WHEEL_STEP, pane.offsetY(), EPS, "one notch of the pane");

        // Up: the list is at its end and not at its top, so it takes the detent back first.
        scene.scrolled(0, 1, 50, 50);
        scene.inputBatchEnded();
        scene.renderFrame(canvas);
        assertEquals(Strokes.WHEEL_STEP, pane.offsetY(), EPS,
                "the list could move up, so it did and the pane did not");

        // Up at the top: the list scrolled back to zero, and the next detent is the pane's.
        for (int i = 0; i < 40; i++) {
            scene.scrolled(0, 1, 50, 50);
            scene.inputBatchEnded();
            scene.renderFrame(canvas);
        }
        assertEquals(0, list.firstVisibleIndex(), "the list is back at its first row");
        assertEquals(0, pane.offsetY(), EPS, "and the pane took the detents the list could not");
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
    void theSelectionSetterChainsAndNullClearsAHandlerSlot() {
        ListView list = list(3, index -> 20);

        assertSame(list, list.setSelectedIndex(1), "the setter chains, as setScrollbarPolicy does");
        assertEquals(1, list.selectedIndex());
        list.onSelect(index -> { });
        assertThrows(IllegalStateException.class, () -> list.onSelect(index -> { }),
                "a second handler over an occupied slot is refused rather than silently replacing");
        assertSame(list, list.onSelect(null), "null clears the slot");
        list.onSelect(index -> { });
        assertSame(list, list.onActivate(null));
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
        AtomicInteger handled = new AtomicInteger(-2);
        list.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.SELECTION) {
                heard.set(list.selectedIndex());
            }
        });
        list.onSelect(handled::set);

        assertSame(list, list.clearSelection(), "the clear chains, as the setter does");
        assertEquals(-1, list.selectedIndex());
        assertEquals(-1, heard.get(), "a watcher bound to the selection hears it empty");
        assertEquals(-2, handled.get(), "the handler answers the user, and code emptied it");

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
     * detail pane watching the selection showing a record that was deleted; what it hears is the
     * list moving by itself, an adjustment, and not a user choosing a row.
     */
    @Test
    void refreshTellsTheWatchersWhenShrinkingDataMovedTheSelection() {
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
        AtomicInteger handled = new AtomicInteger(-2);
        List<Change.Origin> origins = new ArrayList<>();
        list.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.SELECTION) {
                heard.set(list.selectedIndex());
                origins.add(change.origin());
            }
        });
        list.onSelect(handled::set);

        count[0] = 4;
        list.refresh();
        assertEquals(3, list.selectedIndex(), "the selection lands on the new last row");
        assertEquals(3, heard.get());
        assertEquals(List.of(Change.Origin.ADJUSTMENT), origins, "the list moved it by itself");
        assertEquals(-2, handled.get(), "and no user chose a row");

        heard.set(-2);
        list.refresh();
        assertEquals(-2, heard.get(), "a refresh that moves nothing announces no selection");

        count[0] = 0;
        list.refresh();
        assertEquals(-1, list.selectedIndex(), "an emptied list has no selection left");
        assertEquals(-1, heard.get());
    }
}
