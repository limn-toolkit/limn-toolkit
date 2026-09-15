package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.ScrollFacet;
import limn.graphics.Canvas;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link ListView} becomes in the accessible tree: one {@code LIST} node over the rows it
 * has actually realized, each keyed by its data index, numbered against the adapter's own count,
 * and the selected one marked as the cursor the list's keyboard is moving.
 *
 * <p>The cases that pin where ADR 039 §7's row was wrong or short against this source, all six of
 * them. The row asks for {@code ActionFacet{PRESS}} on a <em>row</em> mapped onto
 * {@link ListView#activate()}, and that is the context region's defect verbatim: a row is a widget
 * child, so the walk records the application's own cell as the node's owner and the scene
 * dispatches strictly there, where the hook is {@code Widget}'s and answers false — and the
 * platform has already been told the action was accepted, so it is a silent failure rather than a
 * reported one. Even dispatchable it would be the wrong call, because {@code activate()} opens the
 * <em>selected</em> row and a press on row seven while row three is selected would open record
 * three. So the verb is on the list, which owns it and can perform it, and it is offered only while
 * something is selected. The row is silent on where a row's name comes from, and the common case
 * has none, so {@link ListView.Adapter#rowName} — added by the record and used by nothing until now
 * — answers both a realized row whose cell said nothing and the selected row that is not realized
 * at all. It says "{@code SelectionFacet}" without saying which shape, and the naive reading is
 * wrong: {@code required} is false unconditionally, because this class documents no-selection as a
 * genuine resting state where the combo refuses an empty item list and the tab pane is always
 * selected. And it is silent on the scroll facet's derivation and on the half-point slop the tab
 * strip's step made that widget's rule: this one's own wheel gate and scroll clamp use the bare
 * subtraction, so a list overflowing by a third of a point really does scroll and must say so.
 *
 * <p>One defect is in no row at all, and no existing test could see it: {@code children()} fell out
 * of data order on any upward scroll, because {@code Widget#add} appends and the mount walk runs
 * upward from the anchor. Tree order is reading order and Tab order both, so a reader heard "4 of
 * 5000, 5 of 5000, 3 of 5000" and a Tab through rows holding buttons walked them the same way.
 * {@code AccessibleFocusOrderTest} cannot see it, because both sides of that invariant read
 * {@code children()}. It is pinned here and, on the keyboard's side, in {@code ListViewTest}.
 *
 * <p>Every case drives the list's public API on a bound scene, or calls the scene from where a
 * bridge stands, and reads back what the scene published. Nothing constructs a node.
 */
class ListViewAccessibilityTest extends AccessibleComponentTestBase {

    private static final double EPS = 1e-6;

    /** The window is 400 &times; 300 and the list is the root, so this many 50pt rows fit. */
    private static final int VISIBLE = 6;

    // ------------------------------------------------------------------------------ the fixture

    /** A cell of a fixed height that says nothing at all about itself. */
    private static class Cell extends Widget {
        private final float rowHeight;

        Cell(float rowHeight) {
            this.rowHeight = rowHeight;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), rowHeight);
        }
    }

    /**
     * A cell that draws its own content and declares nothing: the shape §1.6's
     * paints-and-says-nothing warning exists for, and the demo's own row cells. Declared here and
     * nowhere else, because the walk remembers the classes it has named for the life of the virtual
     * machine and a class shared with another test would be warned about at most once in a run.
     */
    private static final class PaintingCell extends Cell {
        PaintingCell(float rowHeight) {
            super(rowHeight);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            // Draws nothing; what the guard can see is that the class declared the method.
        }
    }

    /** A cell that says what it is, to pin that the list does not overwrite it. */
    private static final class ButtonCell extends Cell {
        ButtonCell(float rowHeight) {
            super(rowHeight);
        }

        @Override
        protected void onAccessibility(limn.accessibility.Accessibility a) {
            a.role(Accessible.Role.BUTTON);
        }
    }

    /** How a row's widget is made, so one adapter covers every cell shape a test needs. */
    private interface CellFactory {
        Widget make(float rowHeight);
    }

    /**
     * The adapter under test: pooled cells, and names it <b>holds</b> rather than builds, which is
     * the contract the row-name javadoc states and the allocation case below measures.
     */
    private static final class Rows implements ListView.Adapter {
        private final int count;
        private final float rowHeight;
        private final I18nString[] names;
        private final CellFactory factory;
        private final Deque<Widget> pool = new ArrayDeque<>();
        private int created;

        Rows(int count, float rowHeight, boolean named, CellFactory factory) {
            this.count = count;
            this.rowHeight = rowHeight;
            this.factory = factory;
            this.names = named ? new I18nString[count] : null;
            if (named) {
                for (int i = 0; i < count; i++) {
                    names[i] = I18nString.literal("Row " + i);
                }
            }
        }

        @Override
        public int rowCount() {
            return count;
        }

        @Override
        public Widget rowAt(int index) {
            if (pool.isEmpty()) {
                created++;
                return factory.make(rowHeight);
            }
            return pool.pop();
        }

        @Override
        public void recycle(Widget widget) {
            pool.push(widget);
        }

        @Override
        public I18nString rowName(int index) {
            return names == null ? null : names[index];
        }
    }

    private ListView bindList(Rows rows) {
        ListView list = new ListView(rows);
        bind(list);
        return list;
    }

    private ListView bindPlain(int count, float rowHeight) {
        return bindList(new Rows(count, rowHeight, false, Cell::new));
    }

    private ListView bindNamed(int count, float rowHeight) {
        return bindList(new Rows(count, rowHeight, true, Cell::new));
    }

    /** @return the one list node in the current tree */
    private AccessibleNode listNode() {
        return node(Accessible.Role.LIST);
    }

    /** @return the list's children that carry a member-of-a-selection facet, in tree order */
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

    /**
     * The widget currently bound to a data row, read through the invariant this step establishes:
     * {@code children()} is the bar and then the realized cells in ascending data order.
     */
    private static Widget cellOf(ListView list, int index) {
        return list.children().get(1 + index - list.firstVisibleIndex());
    }


    // ------------------------------------------------------------------- the role, and what is in

    @Test
    void aHugeListIsOneNodeOverTheRowsItRealizedAndNotOverItsData() {
        ListView list = bindPlain(500, 50);

        AccessibleNode node = listNode();
        assertEquals(500, list.rowCount(), "the model really is that long");
        List<AccessibleNode> rows = rowNodes();
        assertEquals(VISIBLE, rows.size(),
                "a list publishes the rows it realized and never one per index: " + describe(tree()));
        for (AccessibleNode row : rows) {
            assertEquals(Accessible.Role.LIST_ITEM, row.role(), describe(tree()));
        }
        AccessibleNode bar = node(Accessible.Role.SCROLL_BAR);
        assertNull(bar.selectionItem(),
                "the bar is a child of the list and is not one of its rows: " + describe(tree()));
        assertEquals(VISIBLE + 1, childrenOf(node).size(),
                "the bar and the realized rows, and nothing else: " + describe(tree()));
    }

    @Test
    void aCellThatAlreadySaidWhatItIsKeepsIt() {
        bindList(new Rows(500, 50, false, ButtonCell::new));

        for (AccessibleNode row : rowNodes()) {
            assertEquals(Accessible.Role.BUTTON, row.role(),
                    "the role is written only onto a cell that declared none, because eliding a "
                            + "scroll pane would take its scroll facet with it: " + describe(tree()));
            assertNotNull(row.selectionItem(),
                    "and it is still a member of the list's selection: " + describe(tree()));
        }
    }

    @Test
    void everyRowIsNumberedAgainstTheAdaptersCountAndNotThePublishedOne() {
        ListView list = bindPlain(500, 50);
        list.scrollBy(50 * 20);
        frame();

        List<AccessibleNode> rows = rowNodes();
        assertEquals(VISIBLE, rows.size(), describe(tree()));
        int first = list.firstVisibleIndex();
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(first + i + 1, rows.get(i).selectionItem().positionInSet(),
                    "position in the data, not in what was published: " + describe(tree()));
            assertEquals(500, rows.get(i).selectionItem().sizeOfSet(),
                    "the model's count, which is the number this widget's facet was written about: "
                            + describe(tree()));
        }
    }

    // -------------------------------------------------------- the selection, and where the cursor is

    @Test
    void theSelectedRowIsTheOneSelectedNodeAndIsAlsoTheListsCursor() {
        ListView list = bindPlain(500, 50);
        list.setSelectedIndex(2);
        scene.requestFocus(list);
        frame();

        AccessibleNode node = listNode();
        List<AccessibleNode> selected = nodesWith(Accessible.State.SELECTED);
        assertEquals(1, selected.size(), "exactly one: " + describe(tree()));
        assertEquals(3, selected.get(0).selectionItem().positionInSet(), describe(tree()));
        assertTrue(selected.get(0).has(Accessible.State.ACTIVE),
                "here the selection is the cursor and the keyboard stays on the list, so without "
                        + "the active bit a reader can enumerate the rows and never learn which "
                        + "one the user is on: " + describe(tree()));
        assertEquals(node.id(), tree().focused(), describe(tree()));
        assertEquals(selected.get(0).id(), tree().activeDescendant(),
                "the tree's cursor is resolved from that bit, below the focused node: "
                        + describe(tree()));
        assertFalse(node.selection().multiSelectable(), "one selectedIndex, and no more");
        assertFalse(node.selection().required(),
                "a list genuinely rests with nothing selected, unlike a combo, which refuses an "
                        + "empty item list, and unlike a tab pane");
    }

    @Test
    void clearingTheSelectionLeavesNoSelectedNodeAndNoCursor() {
        ListView list = bindPlain(500, 50);
        list.setSelectedIndex(2);
        scene.requestFocus(list);
        frame();
        list.clearSelection();
        frame();

        assertTrue(nodesWith(Accessible.State.SELECTED).isEmpty(), describe(tree()));
        assertTrue(nodesWith(Accessible.State.ACTIVE).stream()
                        .noneMatch(n -> n.selectionItem() != null),
                "and no row is the cursor either: " + describe(tree()));
        assertEquals(0, tree().activeDescendant(), describe(tree()));
        assertFalse(listNode().selection().required(),
                "which is the state `required` would have been lying about");
    }

    @Test
    void movingTheSelectionOnABoundSceneRaisesTheEventsThatNameTheRow() {
        ListView list = bindPlain(500, 50);
        long listId = listNode().id();
        scene.requestFocus(list);
        frame();
        bridge.events.clear();

        list.setSelectedIndex(2);
        frame();

        AccessibleNode row = rowNode(2);
        assertNotNull(row, describe(tree()));
        List<AccessibleEvent> states = bridge.eventsOf(AccessibleEvent.Type.STATE_CHANGED);
        assertTrue(states.stream().anyMatch(event -> event.nodeId() == row.id()
                        && event.state() == Accessible.State.SELECTED
                        && Boolean.TRUE.equals(event.newValue())),
                "the row is announced selected: " + bridge.events);
        assertTrue(bridge.eventsOf(AccessibleEvent.Type.SELECTION_CHANGED).stream()
                        .anyMatch(event -> event.nodeId() == listId),
                "and the container is told its selection moved: " + bridge.events);
        List<AccessibleEvent> moved = bridge.eventsOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED);
        assertEquals(1, moved.size(),
                "and where the cursor went, once, on the focused node, which is the event a "
                        + "reader follows: " + bridge.events);
        assertEquals(listId, moved.get(0).nodeId());
        assertEquals(row.id(), moved.get(0).newValue());
    }

    /**
     * MODEL-NEW-6 (ADR 039 §1.10, amended 2026-09-14): End moves the selection onto a row the
     * list had not realized, which publishes a brand-new selected node — and the container's
     * selection moved as surely as if the row had been there. One event on the list, naming the
     * row that entered its selection and the row that left it (the old one, unrealized by the
     * scroll and so gone from the tree in the same publish).
     */
    @Test
    void aSelectionMovedOntoARowThatWasNotRealizedIsASelectionChange() {
        ListView list = bindPlain(200, 50);
        long listId = listNode().id();
        list.setSelectedIndex(0);
        scene.requestFocus(list);
        frame();
        long first = rowNode(0).id();
        assertTrue(rowNodes().size() < 100, "only the rows that fit are realized: " + describe(tree()));
        bridge.events.clear();

        scene.keyEvent(Keys.END, true, false, 0);
        scene.keyEvent(Keys.END, false, false, 0);
        scene.inputBatchEnded();
        frame();

        assertEquals(199, list.selectedIndex());
        AccessibleNode last = rowNode(199);
        assertNotNull(last, "the last row is realized now: " + describe(tree()));
        assertTrue(last.selectionItem().selected());
        assertEquals(listNode().id(), tree().node(last.selectionContainer()).id(),
                "the row's container is the list: " + describe(tree()));
        List<AccessibleEvent> moved = bridge.eventsOf(AccessibleEvent.Type.SELECTION_CHANGED);
        assertEquals(1, moved.size(), "one selection change, on the list: " + bridge.events);
        assertEquals(listId, moved.get(0).nodeId());
        assertEquals(List.of(last.id()), moved.get(0).addedMembers(),
                "the row the selection landed on, new in this publish: " + bridge.events);
        assertEquals(List.of(first), moved.get(0).removedMembers(),
                "and the row it left, gone from the tree in the same publish: " + bridge.events);
        assertFalse(moved.get(0).multiSelectable());
    }

    /**
     * The settled active-state gate (ADR 039 §1.10, amended 2026-09-14): the cursor is the
     * focused node's, resolved downward from it, so a list nobody is in publishes no
     * {@code ACTIVE} row and raises no cursor event; a list nested in another list's row would
     * otherwise hand the outer one its selected row as the outer one's cursor.
     */
    @Test
    void anUnfocusedListWithASelectedRowPublishesNoCursor() {
        ListView list = bindPlain(500, 50);
        list.setSelectedIndex(2);
        frame();

        assertEquals(1, nodesWith(Accessible.State.SELECTED).size(), describe(tree()));
        assertTrue(nodesWith(Accessible.State.ACTIVE).isEmpty(),
                "the selection stands, the cursor does not: " + describe(tree()));
        assertEquals(0, tree().activeDescendant(), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "an unfocused container announces nothing: " + bridge.events);
    }

    // ---------------------------------------------------------------- identity across recycling

    @Test
    void aRecycledCellCarriesTheRowItIsBoundToAndNeverTheRowItCameFrom() {
        Rows rows = new Rows(500, 50, false, Cell::new);
        ListView list = bindList(rows);

        long rowThree = rowNode(3).id();
        Widget cellOfRowThree = cellOf(list, 3);

        list.scrollBy(50 * VISIBLE);
        frame();
        list.scrollBy(50 * VISIBLE);
        frame();

        assertEquals(2 * VISIBLE, list.firstVisibleIndex(), "two viewports down");
        assertSame(cellOfRowThree, cellOf(list, 2 * VISIBLE + 2),
                "the adapter really did re-issue the cell that held row three, which is the whole "
                        + "of what this test is about");
        assertTrue(rows.created < 3 * VISIBLE, "and pooled rather than created: " + rows.created);
        assertNotEquals(rowThree, rowNode(2 * VISIBLE + 2).id(),
                "the identifier is the data index's and not the widget's, so a client holding row "
                        + "three is not silently handed the row that cell is now showing");

        list.scrollBy(-50f * 2 * VISIBLE);
        frame();

        assertEquals(0, list.firstVisibleIndex());
        assertNotSame(cellOfRowThree, cellOf(list, 3),
                "row three came back on a different widget, which is the case the key exists for");
        assertEquals(rowThree, rowNode(3).id(),
                "and it is the same element to whoever was holding it");
    }

    // ------------------------------------------------------------------------------- tree order

    @Test
    void treeOrderIsDataOrderAfterScrollingDownAndBackUp() {
        ListView list = bindPlain(500, 50);

        list.scrollBy(50 * VISIBLE);
        frame();
        list.scrollBy(-50f * VISIBLE);
        frame();

        assertEquals(0, list.firstVisibleIndex());
        List<AccessibleNode> rows = rowNodes();
        assertEquals(VISIBLE, rows.size(), describe(tree()));
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, rows.get(i).selectionItem().positionInSet(),
                    "reading order is data order. The mount walk runs upward from the anchor, so a "
                            + "container that appended each row as it was realized publishes them "
                            + "in the order the scroll happened to realize them: " + describe(tree()));
        }
    }

    // ------------------------------------------------------------------------------ the scroll facet

    @Test
    void theScrollFacetIsWhereTheViewportSitsAndHowMuchOfTheContentItShows() {
        ListView list = bindPlain(500, 50);

        ScrollFacet atRest = listNode().scroll();
        assertNotNull(atRest, describe(tree()));
        assertEquals(0, atRest.verticalPercent(), EPS);
        assertEquals(300.0 / (500 * 50), atRest.verticalViewSize(), 1e-4,
                "the fraction of the content the viewport shows");
        assertTrue(atRest.verticallyScrollable());
        assertEquals(0, atRest.horizontalPercent(), EPS);
        assertEquals(1, atRest.horizontalViewSize(), EPS);
        assertFalse(atRest.horizontallyScrollable(), "the list has one axis");

        list.scrollBy(500 * 50);
        frame();

        assertEquals(1, listNode().scroll().verticalPercent(), EPS,
                "the percent is the offset over the maximum offset, so the end is one");
    }

    @Test
    void aListWhoseContentFitsStillPublishesTheFacet() {
        bindPlain(3, 50);

        ScrollFacet fits = listNode().scroll();
        assertNotNull(fits,
                "published unconditionally, so that resizing past the fitting point moves two "
                        + "booleans rather than making a facet appear and disappear: "
                        + describe(tree()));
        assertFalse(fits.verticallyScrollable());
        assertEquals(0, fits.verticalPercent(), EPS);
        assertEquals(1, fits.verticalViewSize(), EPS, "all of it is on screen");
    }

    @Test
    void aListOverflowingByLessThanTheBarsSlopStillSaysItScrolls() {
        // Three rows of 100.1 in a 300pt viewport: three tenths of a point over. The bar declines
        // to exist below half a point, and this widget's own wheel gate and scroll clamp use the
        // bare subtraction and will move it, so a slop-gated boolean here would advertise a
        // refusal the widget does not make.
        bindPlain(3, 100.1f);

        assertTrue(listNode().scroll().verticallyScrollable(),
                "the list moves, so it says so: " + describe(tree()));
        for (int i = 0; i < tree().nodeCount(); i++) {
            assertNotEquals(Accessible.Role.SCROLL_BAR, tree().node(i).role(),
                    "and the bar, which declines below half a point, is not in the tree at all: "
                            + describe(tree()));
        }
    }

    // ---------------------------------------------------------------------------------- the verb

    @Test
    void aPressOnTheListOpensTheSelectedRowThroughTheSamePathEnterTakes() throws Exception {
        AtomicInteger activated = new AtomicInteger(-1);
        AtomicInteger calls = new AtomicInteger();
        Rows rows = new Rows(500, 50, false, Cell::new);
        ListView list = new ListView(rows);
        list.onActivate(index -> {
            activated.set(index);
            calls.incrementAndGet();
        });
        bind(list);
        list.setSelectedIndex(2);
        frame();

        assertTrue(listNode().actions().actions().contains(Accessible.Action.PRESS),
                describe(tree()));
        assertTrue(perform(listNode().id(), Accessible.Action.PRESS, Accessible.Argument.NONE));

        assertEquals(1, calls.get(), "exactly once");
        assertEquals(2, activated.get(), "and on the selected row");
        assertTrue(bridge.eventsOf(AccessibleEvent.Type.INVOKED).stream()
                        .anyMatch(event -> event.nodeId() == listNode().id()),
                "a successful press is acknowledged: " + bridge.events);
    }

    @Test
    void aListWithNothingSelectedOffersNoPressAndDoesNothingWhenPressed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ListView list = new ListView(new Rows(500, 50, false, Cell::new));
        list.onActivate(index -> calls.incrementAndGet());
        bind(list);

        assertFalse(listNode().actions().actions().contains(Accessible.Action.PRESS),
                "a verb that could only ever no-op is not offered: " + describe(tree()));
        perform(listNode().id(), Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertEquals(0, calls.get(), "and it is refused rather than accepted and dropped");
    }

    @Test
    void aDisabledListRefusesThePress() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ListView list = new ListView(new Rows(500, 50, false, Cell::new));
        list.onActivate(index -> calls.incrementAndGet());
        bind(list);
        list.setSelectedIndex(2);
        frame();
        long id = listNode().id();
        list.setEnabled(false);
        frame();

        perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE);

        assertEquals(0, calls.get(), "the scene's own gate, which is why the hook has none");
    }

    /**
     * A row carries exactly one verb, {@code SELECT}, and it is the list's rather than the
     * cell's (ADR 039 §1.5, amended 2026-09-14; §11's "not per-row actuation" reversed the same
     * day). A verb <em>written</em> onto a row would still be dispatched to the application's
     * own cell widget, whose hook answers false, while the platform has already been told the
     * action was accepted — which is why the row carried nothing until the walk learned to route
     * a container's claim. Performing it through the bridge host lands on the row the reader
     * addressed, and the list selects that row as a click does, from the user.
     */
    @Test
    void aRowCarriesTheSelectVerbTheListDelegatedAndTheListPerformsIt() throws Exception {
        ListView list = bindPlain(500, 50);
        list.setSelectedIndex(2);
        frame();

        for (AccessibleNode row : rowNodes()) {
            assertNotNull(row.actions(), "a row carries the verb the list delegated onto it: "
                    + describe(tree()));
            assertTrue(row.actions().has(Accessible.Action.SELECT),
                    "the verb the list delegated onto the row: " + describe(tree()));
            assertFalse(row.actions().has(Accessible.Action.PRESS),
                    "and nothing written by the cell: " + describe(tree()));
        }
        List<limn.scene.Change> changes = new ArrayList<>();
        scene.observeChanges((source, change) -> changes.add(change));

        assertTrue(perform(rowNode(4).id(), Accessible.Action.SELECT, Accessible.Argument.NONE));
        frame();

        assertEquals(4, list.selectedIndex(), "the row addressed, not the one that was selected");
        assertTrue(rowNode(4).selectionItem().selected(), describe(tree()));
        assertFalse(rowNode(2).selectionItem().selected(), describe(tree()));
        assertEquals(1, changes.size(), "announced once, as a click is: " + changes);
        assertEquals(limn.scene.Change.Aspect.SELECTION, changes.get(0).aspect());
        assertEquals(limn.scene.Change.Origin.USER, changes.get(0).origin(),
                "and from the user, which is who a reader is");
    }

    /**
     * Decision 20's row verb set, read against this widget (ADR 039 §7's ListView row, amended
     * 2026-09-14): {@code SELECT} and, on a cell that cannot take the keyboard,
     * {@code SCROLL_INTO_VIEW}, both delegated; never {@code FOCUS} (decision 11: the selection
     * is the cursor), never {@code ADD_TO_SELECTION} or {@code DESELECT} (one selected row, no
     * multi-select). The reveal lands on the row a reader addressed and moves neither the
     * selection nor the cursor.
     */
    @Test
    void aRowThatCannotTakeTheKeyboardCarriesScrollIntoViewAndTheListRevealsItInPlace()
            throws Exception {
        ListView list = bindNamed(500, 50);
        list.requestFocus();
        list.setSelectedIndex(0);
        frame();
        assertEquals(java.util.Set.of(Accessible.Action.SELECT, Accessible.Action.SCROLL_INTO_VIEW),
                rowNode(0).actions().actions(),
                "the two verbs the list delegates, and no FOCUS: " + describe(tree()));

        list.scrollBy(50f * 2 * VISIBLE);
        frame();
        AccessibleNode kept = rowNode(0);
        assertNotNull(kept, "the cursor row is kept while the list holds the keyboard: "
                + describe(tree()));
        assertFalse(kept.has(Accessible.State.SHOWING), describe(tree()));
        bridge.events.clear();

        assertTrue(perform(kept.id(), Accessible.Action.SCROLL_INTO_VIEW, Accessible.Argument.NONE));
        frame();

        assertTrue(rowNode(0).has(Accessible.State.SHOWING),
                "revealed where it stands: " + describe(tree()));
        assertEquals(0, list.firstVisibleIndex());
        assertEquals(0, list.selectedIndex(), "the selection did not move");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.SELECTION_CHANGED), bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED),
                "and neither did the cursor: " + bridge.events);

        perform(rowNode(3).id(), Accessible.Action.FOCUS, Accessible.Argument.NONE);
        frame();

        assertEquals(0, list.selectedIndex(),
                "FOCUS on a row is neither published nor performed: a focus that selected would "
                        + "be SELECT under another name");
        assertEquals(listNode().id(), tree().focused(), describe(tree()));
        assertEquals(rowNode(0).id(), tree().activeDescendant(), describe(tree()));
    }

    @Test
    void aRowThatCanTakeTheKeyboardGetsTheWalksFreeVerbsAndTheListDelegatesNoneOfThem() {
        bindList(new Rows(500, 50, true, height -> {
            Cell cell = new Cell(height);
            cell.setFocusable(true);
            return cell;
        }));

        for (AccessibleNode row : rowNodes()) {
            assertEquals(java.util.Set.of(Accessible.Action.SELECT, Accessible.Action.FOCUS,
                            Accessible.Action.SCROLL_INTO_VIEW), row.actions().actions(),
                    "the walk's two free verbs on a focusable widget and the list's SELECT; a "
                            + "delegation of either free verb would be refused as a second "
                            + "performer, so the list makes none: " + describe(tree()));
        }
    }

    // ---------------------------------------------------------------------------------- the names

    @Test
    void anAdapterWithNamesNamesEveryRealizedRow() {
        bindNamed(500, 50);

        List<AccessibleNode> rows = rowNodes();
        for (int i = 0; i < rows.size(); i++) {
            assertEquals("Row " + i, rows.get(i).name(), describe(tree()));
            assertEquals(Accessible.NameFrom.CONTENT, rows.get(i).nameFrom());
        }
    }

    @Test
    void anAdapterWithNoNamesLeavesTheRowsUnnamedAndThrowsNothing() {
        bindPlain(500, 50);

        for (AccessibleNode row : rowNodes()) {
            assertEquals("", row.name(), describe(tree()));
        }
    }

    @Test
    void aSelectedRowScrolledOutOfViewIsNamedOnTheListsOwnNode() {
        ListView list = bindNamed(500, 50);
        list.setSelectedIndex(0);
        frame();

        assertEquals("Row 0", rowNode(0).name());
        assertEquals("", listNode().description(),
                "while the row is realized it carries its own name, and a second copy here is the "
                        + "same name spoken twice: " + describe(tree()));

        list.scrollBy(50f * 2 * VISIBLE);
        frame();

        assertNull(rowNode(0), "row zero is no longer realized: " + describe(tree()));
        assertTrue(nodesWith(Accessible.State.SELECTED).isEmpty(),
                "so nothing in the tree is selected: " + describe(tree()));
        assertEquals("Row 0", listNode().description(),
                "and the only place left to say which row the user is on is the list itself: "
                        + describe(tree()));

        list.scrollBy(-50f * 2 * VISIBLE);
        frame();

        assertEquals("Row 0", rowNode(0).name());
        assertEquals("", listNode().description(),
                "and it goes away again the moment the row can speak for itself: " + describe(tree()));
    }

    // ------------------------------------------------------------------ boxes, mirroring and paint

    @Test
    void aRowsBoxIsTheCellsBoxIncludingTheOneScrolledHalfwayOffTheTop() {
        ListView list = bindPlain(500, 50);
        list.scrollBy(20);
        frame();

        AccessibleNode first = rowNode(0);
        assertNotNull(first, describe(tree()));
        assertEquals(-20, first.y(), EPS,
                "that is where the row is; nothing clamps a box to the viewport, and SHOWING is "
                        + "what says how much of it is on screen: " + describe(tree()));
        assertEquals(0, first.x(), EPS);
        assertEquals(400, first.width(), EPS);
        assertEquals(50, first.height(), EPS);
        assertTrue(first.has(Accessible.State.SHOWING), "half of it is: " + describe(tree()));
        assertEquals(30, rowNode(1).y(), EPS, describe(tree()));
    }

    @Test
    void aFullyScrolledOutRowHasNoNodeAtAll() {
        ListView list = bindPlain(500, 50);
        list.scrollBy(50f * 2 * VISIBLE);
        frame();

        assertNull(rowNode(0), describe(tree()));
        assertNotNull(rowNode(2 * VISIBLE), describe(tree()));
    }

    @Test
    void mirroringMovesEveryRowsOriginAndLeavesTreeOrderAlone() {
        ListView list = new ListView(new Rows(500, 50, false, Cell::new));
        list.setLayoutDirection(LayoutDirection.RTL);
        list.setBarLayout(ScrollGutters.Layout.RESERVED);
        bind(list);

        float strip = ScrollBar.thickness();
        List<AccessibleNode> rows = rowNodes();
        assertEquals(VISIBLE, rows.size(), describe(tree()));
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(strip, rows.get(i).x(), EPS,
                    "the bar is on the trailing side, which reading right to left is the left one, "
                            + "and the rows begin past it: " + describe(tree()));
            assertEquals(400 - strip, rows.get(i).width(), EPS);
            assertEquals(i + 1, rows.get(i).selectionItem().positionInSet(),
                    "and the tree is not sorted by geometry: " + describe(tree()));
        }
        assertEquals(0, node(Accessible.Role.SCROLL_BAR).x(), EPS, describe(tree()));
    }

    @Test
    void aCellThatPaintsAndSaysNothingIsAListItemAndIsNotWarnedAbout() {
        bindList(new Rows(500, 50, true, PaintingCell::new));

        for (AccessibleNode row : rowNodes()) {
            assertEquals(Accessible.Role.LIST_ITEM, row.role(), describe(tree()));
        }
        assertTrue(logged.isEmpty(),
                "the role this hook writes takes the paints-and-says-nothing warning away, which "
                        + "was the application's only notice that a cell it drew said nothing — "
                        + "which is exactly why rowName is asked for a realized row too: " + logged);
    }

    // ---------------------------------------------------------------------- what a quiet frame costs

    @Test
    void aFrameThatDamagesTheListAndChangesNothingPublishesNothing() {
        ListView list = bindNamed(500, 50);
        list.setSelectedIndex(2);
        frame();
        int before = bridge.published.size();

        for (int i = 0; i < 10; i++) {
            list.invalidate();
            frame();
        }

        assertEquals(before, bridge.published.size(), "no difference, so no snapshot");
    }

    /**
     * The list under test with its paint stubbed out, and only its paint. The selection ring
     * interpolates between two theme colours on every frame it is drawn, and a colour is an object,
     * so a list with a selection allocates one per painted frame — the widget's drawing cost, which
     * belongs to the widget's own test, and not the walk's, which is what this measures. The
     * describe hooks underneath are the shipped ones.
     */
    private static final class UnpaintedList extends ListView {
        UnpaintedList(Adapter adapter) {
            super(adapter);
        }

        @Override
        protected void paintChildren(Canvas canvas) {
        }
    }

    @Test
    void aFrameThatDamagesTheListAndChangesNothingAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        ListView list = new UnpaintedList(new Rows(500, 50, true, Cell::new));
        bind(list);
        list.setSelectedIndex(2);
        frame();

        long least = AllocationProbe.leastAllocatedBy(() -> {
            list.invalidate();
            frame();
        }, 60);

        assertEquals(0, least,
                "the hooks run on every damaged frame: a map keyed by Integer boxes its key for "
                        + "any row above 127 and allocates an iterator per sweep, an adapter asked "
                        + "for its count once per row costs a call per row, and a name built per "
                        + "call republishes the whole tree every frame because the comparison is "
                        + "by reference");
    }

    // ------------------------------------------------------------- the walk's log, for the paint case

    private final List<LogRecord> logged = new ArrayList<>();

    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private Logger walkLogger;

    @BeforeEach
    void captureTheWalksLog() {
        walkLogger = Logger.getLogger("limn.scene.AccessibleWalk");
        walkLogger.addHandler(capture);
    }

    @AfterEach
    void stopCapturing() {
        walkLogger.removeHandler(capture);
    }
}
