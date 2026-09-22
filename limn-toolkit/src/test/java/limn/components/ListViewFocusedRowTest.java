package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.graphics.Canvas;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * The row the keyboard is in survives a scroll that moves it out of the viewport (ADR 039
 * §13.29).
 *
 * <p>A {@link ListView} realizes the rows in its viewport and hands the rest back to the adapter,
 * and until this step it handed back the row holding the keyboard focus with the others, moving
 * the focus up to the list. The keyboard user never noticed: the arrows and the page keys move
 * by selection, and the selected row is always realized. A screen reader user did. A reader
 * whose cursor follows the keyboard focus onto a focusable row — VoiceOver's does — was reading
 * that row when a Page Down released it, and with its node gone from the tree the reader fell
 * back to "you are currently in a window" and then to the scroll bar. So the row holding the
 * focus is now kept mounted, in data order, with its widget and its focus untouched, laid out
 * wholly outside the viewport where the scroll estimate puts it, and published as the list item
 * it is: not {@code SHOWING}, with a box outside the list's, as a scroll pane publishes content
 * scrolled away. It is released by the first realization pass that finds it outside the run and
 * no longer holding the focus, and by {@link ListView#refresh()}, which releases everything.
 *
 * <p>Since 2026-09-14 (decision 22) the selected row is kept the same way while the <em>list</em>
 * holds the keyboard, because it is then the reader's cursor; the cases under "the cursor row"
 * pin that, and that a refresh realizes it again rather than losing it.
 *
 * <p>Every case drives the list's public API and the scene's own key path on a bound scene, and
 * reads back what the scene published. Nothing constructs a node.
 */
class ListViewFocusedRowTest extends AccessibleComponentTestBase {

    /** The window is 400 &times; 300 and the list is the root, so this many 50pt rows fit. */
    private static final int VISIBLE = 6;

    private static final int ROWS = 300;

    private static final float ROW_HEIGHT = 50;

    // ------------------------------------------------------------------------------ the fixture

    /**
     * A row holding a real button, which is the shape a reader's cursor lands on: the cell
     * itself declares nothing and takes {@code LIST_ITEM} from the list, and the button inside
     * it is what the keyboard focus is in.
     */
    static final class ButtonRow extends Widget {
        final Button button = new Button("Open");

        /** The data index this row was last bound to by the adapter. */
        int index = -1;

        /** Whether this row painted since the flag was last cleared. */
        boolean painted;

        ButtonRow() {
            add(button);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), ROW_HEIGHT);
        }

        @Override
        protected void onLayout() {
            button.measure(Constraints.tight(width(), height()));
            button.layoutBox(0, 0, width(), height());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            painted = true;
        }
    }

    /** Pooled button rows, with names the adapter holds, and a record of every cell returned. */
    static final class Rows implements ListView.Adapter {
        private final I18nString[] names = new I18nString[ROWS];
        private final Deque<ButtonRow> pool = new ArrayDeque<>();

        /** Every cell handed back through {@link #recycle}, in order. */
        final List<Widget> recycled = new ArrayList<>();

        Rows() {
            for (int i = 0; i < ROWS; i++) {
                names[i] = I18nString.literal("Row " + i);
            }
        }

        @Override
        public int rowCount() {
            return ROWS;
        }

        @Override
        public Widget rowAt(int index) {
            ButtonRow row = pool.isEmpty() ? new ButtonRow() : pool.pop();
            row.index = index;
            return row;
        }

        @Override
        public void recycle(Widget widget) {
            recycled.add(widget);
            pool.push((ButtonRow) widget);
        }

        @Override
        public I18nString rowName(int index) {
            return names[index];
        }
    }

    private Rows rows;

    private ListView list;

    /** Binds a list of {@link #ROWS} button rows, with row one's button holding the focus. */
    private void bindWithFocusInRowOne() {
        rows = new Rows();
        list = new ListView(rows);
        bind(list);
        list.setSelectedIndex(1);
        frame();
        cellOf(1).button.requestFocus();
        frame();
        assertTrue(cellOf(1).button.isFocused(), "the fixture: the keyboard is in row one");
    }

    /** Presses Page Down through the scene's own key path, as a keyboard does. */
    private void pageDown() {
        drive(scene).keyEvent(Keys.PAGE_DOWN, true, false, 0);
        drive(scene).inputBatchEnded();
        frame();
    }

    /** @return the mounted cell bound to {@code index}, or {@code null} when none is */
    private ButtonRow cellOf(int index) {
        for (Widget child : list.children()) {
            if (child instanceof ButtonRow row && row.index == index) {
                return row;
            }
        }
        return null;
    }

    /** @return the data indices of the mounted rows, in {@code children()} order */
    private List<Integer> mountedIndices() {
        List<Integer> found = new ArrayList<>();
        for (Widget child : list.children()) {
            if (child instanceof ButtonRow row) {
                found.add(row.index);
            }
        }
        return found;
    }

    private AccessibleNode listNode() {
        return node(Accessible.Role.LIST);
    }

    /** @return the list's children that are members of its selection, in tree order */
    private List<AccessibleNode> rowNodes() {
        List<AccessibleNode> found = new ArrayList<>();
        for (AccessibleNode child : childrenOf(listNode())) {
            if (child.selectionItem() != null) {
                found.add(child);
            }
        }
        return found;
    }

    /** @return the published node for data row {@code index}, or {@code null} when unrealized */
    private AccessibleNode rowNode(int index) {
        for (AccessibleNode row : rowNodes()) {
            if (row.selectionItem().positionInSet() == index + 1) {
                return row;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------- the kept row

    @Test
    void theRowHoldingTheKeyboardFocusSurvivesPagingOutOfTheViewport() {
        bindWithFocusInRowOne();
        ButtonRow rowOne = cellOf(1);

        pageDown();
        pageDown();
        pageDown();

        assertEquals(19, list.selectedIndex(), "three pages of six from row one");
        assertEquals(19, list.firstVisibleIndex(), "a far jump lands the selection at the top");
        assertSame(rowOne, cellOf(1),
                "the row the keyboard is in is still mounted, on the same widget");
        assertTrue(rowOne.button.isFocused(), "and the focus never moved");
        assertFalse(rows.recycled.contains(rowOne), "and the adapter was not handed it back");
        assertEquals(List.of(1, 19, 20, 21, 22, 23, 24), mountedIndices(),
                "children() is the bar and then the mounted rows in data order, the kept row "
                        + "ahead of the viewport's, which is reading order and Tab order both");
    }

    @Test
    void theKeptRowIsPublishedAsAListItemOutsideTheListAndNotShowing() {
        bindWithFocusInRowOne();

        pageDown();
        pageDown();
        pageDown();

        AccessibleNode kept = rowNode(1);
        assertNotNull(kept, "the row the reader stands on is still in the tree: " + describe(tree()));
        assertEquals(Accessible.Role.LIST_ITEM, kept.role(), describe(tree()));
        assertEquals("Row 1", kept.name(), describe(tree()));
        assertEquals(2, kept.selectionItem().positionInSet(), describe(tree()));
        assertEquals(ROWS, kept.selectionItem().sizeOfSet(), describe(tree()));
        assertTrue(kept.has(Accessible.State.VISIBLE),
                "offscreen is not invisible: " + describe(tree()));
        assertFalse(kept.has(Accessible.State.SHOWING),
                "but it has no pixels on screen, which is what a scroll pane says of content "
                        + "scrolled away: " + describe(tree()));
        AccessibleNode container = listNode();
        assertTrue(kept.y() + kept.height() <= container.y(),
                "its box is where the row is, wholly above the list's: " + describe(tree()));
        List<AccessibleNode> focused = nodesWith(Accessible.State.FOCUSED);
        assertEquals(1, focused.size(), describe(tree()));
        assertEquals(Accessible.Role.BUTTON, focused.get(0).role(), describe(tree()));
        assertTrue(childrenOf(kept).contains(focused.get(0)),
                "and the focused node is the kept row's own button: " + describe(tree()));
        assertEquals(VISIBLE + 1, rowNodes().size(),
                "the viewport's rows and the kept one, and no other: " + describe(tree()));
    }

    @Test
    void theKeptRowIsNeitherPaintedInTheViewportNorUnderThePointer() {
        bindWithFocusInRowOne();
        ButtonRow rowOne = cellOf(1);

        pageDown();
        pageDown();
        pageDown();

        assertTrue(rowOne.y() + rowOne.height() <= 0,
                "laid out wholly above the viewport: y=" + rowOne.y());
        for (Widget child : list.children()) {
            if (child instanceof ButtonRow row) {
                row.painted = false;
            }
        }
        list.invalidate();
        frame();
        assertFalse(rowOne.painted, "a row outside the viewport paints nothing into it");
        assertTrue(cellOf(19).painted, "while the rows in it paint as before");

        Widget hit = list.hitTest(20, ROW_HEIGHT + 5);
        assertNotNull(hit);
        Widget under = hit;
        while (under != null && !(under instanceof ButtonRow)) {
            under = under.parent();
        }
        assertSame(cellOf(20), under,
                "the second slot of the viewport belongs to the row bound there, and the kept "
                        + "row, whatever slot it held before the page, takes no click: " + hit);
    }

    @Test
    void selectingTheKeptRowJumpsBackToItOnTheSameWidget() {
        bindWithFocusInRowOne();
        ButtonRow rowOne = cellOf(1);
        pageDown();
        pageDown();
        pageDown();

        list.setSelectedIndex(1);
        frame();

        assertEquals(1, list.firstVisibleIndex(),
                "the reveal jumps exactly, as it does for an unrealized row, rather than "
                        + "scrolling by the kept row's estimated box");
        assertSame(rowOne, cellOf(1), "and finds the cell already mounted");
        assertTrue(rowOne.button.isFocused());
        assertTrue(rowNode(1).has(Accessible.State.SHOWING), describe(tree()));
        assertFalse(rows.recycled.contains(rowOne));
    }

    // ------------------------------------------------------------------------------- release

    @Test
    void theKeptRowIsReleasedByTheFirstPassThatFindsTheFocusGone() {
        bindWithFocusInRowOne();
        ButtonRow rowOne = cellOf(1);
        pageDown();
        pageDown();
        pageDown();
        assertSame(rowOne, cellOf(1), "kept while the focus is in it");

        list.requestFocus();
        pageDown();

        assertNull(cellOf(1), "no longer in the tree");
        assertTrue(rows.recycled.contains(rowOne), "the adapter has it back");
        assertNull(rowNode(1), "and nothing is published for it: " + describe(tree()));
        assertTrue(list.isFocused(), "the release moved nothing: the focus was on the list");
        assertEquals(VISIBLE + 1, list.children().size(), "the bar and the viewport's rows");
    }

    @Test
    void refreshReleasesTheKeptRowWithEveryOtherAndTheFocusFallsBackToTheList() {
        bindWithFocusInRowOne();
        ButtonRow rowOne = cellOf(1);
        pageDown();
        pageDown();
        pageDown();

        list.refresh();
        frame();

        assertTrue(rows.recycled.contains(rowOne),
                "a refresh unmounts every cell, because each is bound to a datum the adapter "
                        + "may have replaced, and the focused one is bound to one too");
        assertTrue(list.isFocused(), "as it always did, the focus falls back to the list");
        assertNull(rowNode(1), describe(tree()));
    }

    // ------------------------------------------------------------------------- the cursor row

    /** Binds a list of {@link #ROWS} button rows with the list itself holding the keyboard. */
    private void bindWithFocusOnTheList() {
        rows = new Rows();
        list = new ListView(rows);
        bind(list);
        list.requestFocus();
        list.setSelectedIndex(1);
        frame();
        assertTrue(list.isFocused(), "the fixture: the keyboard is on the list");
    }

    /** Wheels the list a long way down, as a pointer does, without moving the selection. */
    private void wheelFarDown() {
        drive(scene).scrolled(0, -30, 20, 100);
        drive(scene).inputBatchEnded();
        frame();
    }

    /**
     * Decision 22 (2026-09-14), the {@code ListView} copy: while the list holds the keyboard the
     * selected row is the reader's cursor — the one node below the focused list published
     * {@code ACTIVE} — and a wheel that scrolled it away used to recycle it, so the cursor
     * resolved to nothing until the next arrow key. The row is kept exactly as a row holding
     * the focus is, and released by the first pass after the keyboard leaves.
     */
    @Test
    void theSelectedRowIsKeptWhileTheListHoldsTheKeyboardAndReleasedWhenItLeaves() {
        bindWithFocusOnTheList();
        ButtonRow rowOne = cellOf(1);
        long cursorBefore = tree().activeDescendant();
        assertEquals(rowNode(1).id(), cursorBefore, "the fixture: the cursor is row one");

        wheelFarDown();

        assertTrue(list.firstVisibleIndex() > VISIBLE, "the viewport left row one behind");
        assertSame(rowOne, cellOf(1), "kept, on the same widget");
        assertFalse(rows.recycled.contains(rowOne), "and the adapter was not handed it back");
        assertEquals(1, mountedIndices().get(0), "in data order, ahead of the viewport's rows");
        AccessibleNode kept = rowNode(1);
        assertNotNull(kept, "the cursor row is still in the tree: " + describe(tree()));
        assertTrue(kept.selectionItem().selected(), describe(tree()));
        assertTrue(kept.has(Accessible.State.ACTIVE), "still the cursor: " + describe(tree()));
        assertFalse(kept.has(Accessible.State.SHOWING), "and not on screen: " + describe(tree()));
        assertTrue(kept.y() + kept.height() <= listNode().y(),
                "its box is wholly above the list's: " + describe(tree()));
        assertEquals(cursorBefore, tree().activeDescendant(),
                "the tree resolves the same cursor it did before the wheel: " + describe(tree()));
        assertEquals("", listNode().description(),
                "the row speaks for itself, so the list does not name it a second time: "
                        + describe(tree()));

        scene.requestFocus(null);
        frame();

        assertNull(cellOf(1), "released by the first pass after the keyboard left");
        assertTrue(rows.recycled.contains(rowOne), "the adapter has it back");
        assertNull(rowNode(1), describe(tree()));
        assertEquals("Row 1", listNode().description(),
                "and the unfocused list names its unrealized selection the way it always did: "
                        + describe(tree()));
    }

    @Test
    void takingTheKeyboardRealizesASelectionAlreadyScrolledAway() {
        rows = new Rows();
        list = new ListView(rows);
        bind(list);
        list.setSelectedIndex(1);
        frame();
        wheelFarDown();
        assertNull(cellOf(1), "unfocused: the selected row went back with the others");
        assertEquals("Row 1", listNode().description(), describe(tree()));

        list.requestFocus();
        frame();

        assertNotNull(cellOf(1), "the pass the keyboard's arrival buys realizes the cursor row");
        AccessibleNode kept = rowNode(1);
        assertNotNull(kept, describe(tree()));
        assertTrue(kept.has(Accessible.State.ACTIVE), describe(tree()));
        assertFalse(kept.has(Accessible.State.SHOWING), describe(tree()));
        assertEquals(kept.id(), tree().activeDescendant(), describe(tree()));
        assertEquals("", listNode().description(), describe(tree()));
        assertTrue(list.firstVisibleIndex() > VISIBLE,
                "and the viewport did not move: taking the keyboard is not a reveal");
    }

    @Test
    void aRefreshKeepsTheCursorRowWhileTheListHoldsTheKeyboard() {
        bindWithFocusOnTheList();
        ButtonRow rowOne = cellOf(1);
        wheelFarDown();
        assertSame(rowOne, cellOf(1));

        list.refresh();
        frame();

        assertTrue(rows.recycled.contains(rowOne),
                "a refresh unmounts every cell, because each is bound to a datum the adapter "
                        + "may have replaced");
        ButtonRow again = cellOf(1);
        assertNotNull(again, "and the next pass realizes the cursor row afresh");
        assertEquals(1, again.index, "bound to the datum the adapter now holds at that index");
        AccessibleNode kept = rowNode(1);
        assertNotNull(kept, describe(tree()));
        assertTrue(kept.has(Accessible.State.ACTIVE), describe(tree()));
        assertFalse(kept.has(Accessible.State.SHOWING), describe(tree()));
        assertEquals(1, list.selectedIndex());
    }

    // ------------------------------------------------------------------- everyone else, as before

    @Test
    void aRowThatIsNeitherTheCursorNorTheFocusIsStillRecycled() {
        bindWithFocusOnTheList();
        List<Widget> firstPage = new ArrayList<>();
        for (int i = 0; i < VISIBLE; i++) {
            firstPage.add(cellOf(i));
        }

        pageDown();
        pageDown();
        pageDown();

        assertEquals(19, list.selectedIndex(), "the page keys move the selection, and so the cursor");
        assertTrue(rows.recycled.containsAll(firstPage),
                "every row of the first page went back to the adapter, row one included: it "
                        + "holds no focus and is no longer the cursor: " + rows.recycled.size());
        assertEquals(List.of(19, 20, 21, 22, 23, 24), mountedIndices(),
                "the viewport's rows and nothing else");
        assertEquals(VISIBLE, rowNodes().size(), describe(tree()));
        assertNull(rowNode(1), describe(tree()));
        assertTrue(list.isFocused());
    }

    @Test
    void aRowWhoseFocusLeftBeforeThePageIsRecycledWithTheOthers() {
        bindWithFocusInRowOne();
        ButtonRow rowOne = cellOf(1);
        cellOf(3).button.requestFocus();
        frame();

        pageDown();
        pageDown();
        pageDown();

        assertTrue(rows.recycled.contains(rowOne), "row one held nothing and went back");
        assertNull(cellOf(1));
        assertNotNull(cellOf(3), "row three is the one kept now");
        assertTrue(cellOf(3).button.isFocused());
        assertEquals(List.of(3, 19, 20, 21, 22, 23, 24), mountedIndices());
    }
}
