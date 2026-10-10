package limn.components.internal;

import limn.scene.Change;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The walk by itself, against a host of plain numbers: rows of uneven height placed from the
 * anchor to the foot of the box, the end closed, a reveal from each side, a far scroll that
 * builds nothing on the way, the row kept for the keyboard, rows renumbered under it.
 */
class RowsTest {

    /** A realized row: nothing but which row it was made for. */
    private static final class Cell {
        int row;
        float y;
        float height;

        Cell(int row) {
            this.row = row;
        }
    }

    private final List<Float> heights = new ArrayList<>();
    private final List<Cell> live = new ArrayList<>();

    private Cell cell(int row) {
        return live.stream().filter(c -> c.row == row).findFirst().orElseThrow();
    }
    private final List<String> log = new ArrayList<>();
    private float box = 100;
    private int cursor = -1;
    private Cell focused;
    private int mounts;

    private final Rows<Cell> rows = new Rows<>(new Rows.Host<Cell>() {
        @Override
        public int count() {
            return heights.size();
        }

        @Override
        public Cell mount(int index, int position) {
            mounts++;
            assertEquals(live.stream().filter(c -> c.row < index).count(), position,
                    "told how many realized rows come before");
            Cell cell = new Cell(index);
            live.add(cell);
            return cell;
        }

        @Override
        public void unmount(Cell row, boolean heldFocus) {
            live.remove(row);
            log.add("unmount " + row.row + (heldFocus ? " (focus)" : ""));
        }

        @Override
        public float measure(Cell row, int index) {
            return heights.get(index);
        }

        @Override
        public void place(Cell row, int index, float y, float height) {
            row.row = index;
            row.y = y;
            row.height = height;
        }

        @Override
        public void shift(Cell row, float dy) {
            row.y += dy;
        }

        @Override
        public boolean holdsFocus(Cell row) {
            return row == focused;
        }

        @Override
        public int cursorIndex() {
            return cursor;
        }

        @Override
        public float viewportHeight() {
            return box;
        }

        @Override
        public float seedHeight() {
            return 20;
        }

        @Override
        public void scrolled(Change.Origin origin) {
            log.add("scrolled " + origin);
        }
    });

    private void rowsOf(int count, float height) {
        heights.clear();
        for (int i = 0; i < count; i++) {
            heights.add(height);
        }
    }

    private List<Integer> realized() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < rows.mountedCount(); i++) {
            out.add(rows.rowAt(i));
        }
        return out;
    }

    @Test
    void onlyTheRowsThatReachTheFootOfTheBoxAreRealized() {
        rowsOf(1000, 20);
        rows.layout();
        assertEquals(List.of(0, 1, 2, 3, 4), realized());
        assertEquals(0, rows.placedFrom());
        assertEquals(5, rows.placedTo());
        assertEquals(40f, rows.topOf(2));
        assertTrue(Float.isNaN(rows.topOf(9)));
        assertEquals(20_000f, rows.contentHeight());
    }

    @Test
    void aScrollMovesTheRealizedRowsAtOnceAndThePassWalksOn() {
        rowsOf(1000, 20);
        rows.layout();
        assertTrue(rows.scrollBy(30, Change.Origin.USER));
        assertEquals(-30f, cell(0).y, "moved now, before any pass");
        assertEquals(List.of("scrolled USER"), log);
        rows.layout();
        assertEquals(1, rows.anchorIndex());
        assertEquals(-10f, rows.anchorTop());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), realized());
        assertEquals(30f, rows.offset());
        assertFalse(rows.scrollBy(-1000, Change.Origin.USER) && rows.offset() != 0, "clamped at the start");
    }

    @Test
    void theEndIsClosedOverRowsOfUnevenHeight() {
        rowsOf(10, 20);
        heights.set(9, 70f);
        rows.layout();
        for (int i = 0; i < 20 && rows.scrollBy(60, Change.Origin.USER); i++) {
            rows.layout();
        }
        rows.layout();
        assertEquals(10, rows.placedTo());
        assertEquals(100f, rows.placedBottom(), "the last row's foot on the box's foot");
        assertEquals(7, rows.anchorIndex(), "20 + 20 + 70 from row 7, ten of it above the box");
        assertEquals(-10f, rows.anchorTop());
    }

    @Test
    void rowsShorterThanTheBoxStayAtItsTop() {
        rowsOf(3, 20);
        rows.layout();
        assertFalse(rows.scrollBy(50, Change.Origin.USER), "nothing to scroll");
        assertEquals(0f, rows.anchorTop());
        assertEquals(60f, rows.placedBottom());
    }

    @Test
    void aRevealLandsAtTheEdgeTheRowLiesBeyondAndMovesNothingForARowInSight() {
        rowsOf(1000, 20);
        heights.set(500, 35f);
        rows.layout();

        rows.reveal(() -> 500);
        rows.layout();
        assertEquals(100f, rows.topOf(500) + 35f, "from below: its foot on the box's foot");

        rows.reveal(() -> 498);
        rows.layout();
        assertEquals(65f, rows.topOf(500), "already in sight: nothing moved");

        rows.reveal(() -> 100);
        rows.layout();
        assertEquals(0f, rows.topOf(100), "from above: its top on the box's top");

        rows.reveal(() -> -1);
        rows.layout();
        assertEquals(100, rows.anchorIndex(), "a row no longer there is forgotten");
    }

    @Test
    void theLaterOfTwoRevealsLandsAndAScrollCancelsOne() {
        rowsOf(1000, 20);
        rows.layout();
        rows.reveal(() -> 900);
        rows.reveal(() -> 300);
        rows.layout();
        assertEquals(80f, rows.topOf(300));

        rows.reveal(() -> 900);
        rows.scrollBy(20, Change.Origin.USER);
        assertFalse(rows.isRevealPending());
        rows.layout();
        assertEquals(297, rows.anchorIndex());
    }

    @Test
    void aFarScrollBuildsNoRowOnTheWay() {
        rowsOf(100_000, 20);
        rows.layout();
        mounts = 0;
        rows.scrollBy(1_000_000, Change.Origin.CODE);
        rows.layout();
        assertTrue(mounts <= 6, "only the rows shown at the landing, not " + mounts);
        assertEquals(50_000, rows.anchorIndex());
    }

    @Test
    void theRowTheKeyboardIsOnIsKeptOutOfSightAndLeftOutOfTheMean() {
        rowsOf(1000, 20);
        heights.set(0, 60f);
        cursor = 0;
        rows.layout();
        rows.scrollTo(2000, Change.Origin.USER);
        rows.layout();
        assertTrue(realized().contains(0), "the cursor's row is still realized");
        assertTrue(cell(0).y + cell(0).height <= 0, "wholly above the box");
        assertTrue(Float.isNaN(rows.topOf(0)), "and not one of the placed rows");
        assertEquals(20f, rows.meanHeight(), "the mean is the placed run's");

        cursor = -1;
        rows.layout();
        assertFalse(realized().contains(0), "released once the keyboard has left");
    }

    @Test
    void aRowWithAFocusedControlIsSparedAndARefreshLetsGoOfEverything() {
        rowsOf(1000, 20);
        rows.layout();
        focused = cell(2);
        rows.scrollTo(4000, Change.Origin.USER);
        rows.layout();
        assertTrue(realized().contains(2));

        log.clear();
        rows.refresh(null, -1);
        assertEquals(0, rows.mountedCount());
        assertTrue(log.contains("unmount 2 (focus)"), log.toString());
        assertNull(rows.mounted(2));
    }

    @Test
    void aRefreshCarriesOneRowOverToItsNewIndex() {
        rowsOf(1000, 20);
        rows.layout();
        Cell kept = cell(3);
        kept.row = 1; // as the widget rebinds it
        rows.refresh(kept, 1);
        assertEquals(List.of(1), realized());
        rows.layout();
        assertEquals(kept, rows.mounted(1), "the same realized row, now row 1");
        assertEquals(5, rows.mountedCount());
    }

    @Test
    void realizedRowsFollowTheirRowsWhenOthersArePutInOrTakenOut() {
        rowsOf(1000, 20);
        rows.layout();
        Cell two = cell(2);
        // A row put in before row 2, and row 3 taken out.
        rows.renumber(cell -> cell.row = cell.row < 2 ? cell.row : cell.row == 3 ? -1 : cell.row + 1);
        assertEquals(List.of(0, 1, 3, 5), realized());
        assertEquals(1, rows.orphans().size());
        log.clear();
        rows.layout();
        assertTrue(log.contains("unmount -1"), "the row that is gone, let go of at the pass: " + log);
        assertEquals(two, rows.mounted(3), "the same realized row, at its row's new index");
        assertEquals(List.of(0, 1, 2, 3, 4), realized());
    }

    @Test
    void pagingMovesTheViewWithTheCursorWhenItWasInSight() {
        rowsOf(1000, 20);
        rows.layout();
        rows.scrollBy(10, Change.Origin.USER);
        rows.layout();
        assertTrue(rows.pageWith(2, 7));
        rows.layout();
        assertEquals(5, rows.anchorIndex());
        assertEquals(30f, rows.topOf(7), "where row 2 was");

        assertFalse(rows.pageWith(900, 905), "from a row out of sight there is no place to keep");
        assertTrue(rows.isWhollyInSight(7));
        assertFalse(rows.isWhollyInSight(10), "cut by the foot");

        rows.moveViewTo(-3, 5);
        assertEquals(0, rows.anchorIndex(), "before the first row is the first row");
        assertEquals(0f, rows.anchorTop());
    }

    @Test
    void rowsPlacedPastTheFootForADragThatLiftedSome() {
        rowsOf(1000, 20);
        rows.setOverscan(40);
        rows.layout();
        assertEquals(7, rows.placedTo());
    }
}
