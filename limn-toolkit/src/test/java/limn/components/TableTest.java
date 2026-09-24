package limn.components;

import limn.testfixtures.PlainWidget;

import limn.components.table.Column;
import limn.components.table.SortOrder;
import limn.components.table.Table;
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.SizedBox;
import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/** The table's engine: virtualization, the permutation, selection, widths and the header. */
class TableTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;

    record Person(String name, int age) {
    }

    private static List<Person> people(int count) {
        List<Person> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new Person("Person " + i, i % 90));
        }
        return list;
    }

    private static Column<Person> nameColumn() {
        return Column.text("Name", Person::name).width(100);
    }

    private static Column<Person> ageColumn() {
        return Column.numeric("Age", Person::age).width(60);
    }

    private Scene scene(Table<?> table, FakeCanvas canvas) {
        Scene scene = new Scene(table);
        scene.setTextRuler(RULER);
        scene.renderFrame(canvas);
        return scene;
    }

    private float headerHeight(Table<?> table) {
        return Theme.current().tokensFor(table).controlHeight();
    }

    private float rowHeight(Table<?> table) {
        SizeTokens t = Theme.current().tokensFor(table);
        return Math.max(t.controlHeight(), RULER.measure("Hg", t.body()).height() + 2 * t.padV());
    }

    private float rowCenterY(Table<?> table, int viewRow) {
        return headerHeight(table) + (viewRow + 0.5f) * rowHeight(table);
    }

    private static void click(Scene scene, float x, float y, int modifiers) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, modifiers, x, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, modifiers, x, y);
        drive(scene).inputBatchEnded();
    }

    @Test
    void realizesOnlyTheRowsThatFit() {
        AtomicInteger built = new AtomicInteger();
        Column<Person> button = Column.<Person>widget(limn.i18n.I18nString.literal("Open"), p -> {
            built.incrementAndGet();
            return new Button("Open");
        }).width(80);
        Table<Person> table = new Table<>(List.of(nameColumn(), button));
        table.setRows(people(1_000_000));
        FakeCanvas canvas = new FakeCanvas(300, 200);
        scene(table, canvas);
        int rowsThatFit = (int) Math.ceil((200 - headerHeight(table)) / rowHeight(table));
        assertTrue(built.get() >= 3 && built.get() <= rowsThatFit + 1,
                "one widget per realized row, and only the viewport's worth: " + built);
        assertEquals(2 + built.get(), table.children().size(), "two bars and the widget cells");
        assertEquals(0, table.firstVisibleRow());
    }

    @Test
    void sortIsAPermutationAndTheModelAndTheSelectionStayPut() {
        List<Person> rows = List.of(new Person("Carol", 3), new Person("Alice", 1),
                new Person("Bob", 2));
        Column<Person> name = nameColumn();
        Table<Person> table = new Table<>(List.of(name, ageColumn()));
        table.setRows(rows);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        table.setSelectedRow(0); // Carol
        table.setSort(name, SortOrder.ASCENDING);
        scene.renderFrame(canvas);
        assertEquals(1, table.viewToModel(0), "Alice first");
        assertEquals(2, table.modelToView(0), "Carol last");
        assertEquals(0, table.selectedRow(), "the selection is a model row and did not move");
        assertSame(rows, table.rows(), "the application's list is untouched");
        assertEquals("Carol", rows.get(0).name());
        table.setSort(name, SortOrder.DESCENDING);
        assertEquals(0, table.viewToModel(0), "Carol first descending");
        table.setSort(name, SortOrder.NONE);
        assertEquals(0, table.viewToModel(0));
        assertNull(table.sortColumn());
    }

    /**
     * TABLE-NEW-2 (decision 23 of 2026-09-14): the focus cell and the range anchor are view
     * positions, and a sort that left them where they stood put the cursor on whatever record
     * the permutation moved there. Both now follow their records, so Down from Carol stays on
     * Carol and a Shift range still extends from the row the user last acted on.
     */
    @Test
    void aSortCarriesTheFocusCellAndTheRangeAnchorWithTheirRecords() {
        Column<Person> name = nameColumn();
        Table<Person> table = new Table<>(List.of(name, ageColumn()));
        table.setRows(List.of(new Person("Carol", 3), new Person("Alice", 1),
                new Person("Bob", 2)));
        table.setSelectionMode(SelectionMode.MULTI);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        scene.requestFocus(table);
        table.setSelectedRow(0); // Carol, shown first
        assertEquals(0, table.focusRow());
        table.setSort(name, SortOrder.ASCENDING);
        scene.renderFrame(canvas);
        assertEquals(2, table.modelToView(0), "Carol is shown last now");
        assertEquals(2, table.focusRow(), "and the focus cell went with her");
        drive(scene).keyEvent(Keys.DOWN, true, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals(0, table.selectedRow(), "Down from the last row stays on Carol");
        drive(scene).keyEvent(Keys.UP, true, false, Keys.MOD_SHIFT);
        drive(scene).inputBatchEnded();
        assertArrayEquals(new int[] {0, 2}, table.selectedRows(),
                "Shift+Up extends from Carol's row, where the anchor followed her: Bob and Carol");
        table.setSort(name, SortOrder.NONE);
        scene.renderFrame(canvas);
        assertEquals(2, table.focusRow(), "the model's order puts Bob's row, the lead, last");
        assertEquals(2, table.selectedRow());
    }

    /**
     * Decision 40 of 2026-09-14: after a sort the focus row is revealed with the least scroll
     * that shows it, as every other write that moves the focus cell does; until then the cursor
     * could sit fifty rows below the viewport with nothing on screen to say so.
     */
    @Test
    void aSortRevealsTheFocusRowWithTheLeastScroll() {
        Column<Person> age = ageColumn();
        Table<Person> table = new Table<>(List.of(nameColumn(), age));
        List<Person> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add(new Person("Person " + i, (i * 37 + 39) % 60)); // a permutation of 0..59
        }
        table.setRows(rows);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        // Full rows in the viewport; one more may show partly above them.
        int fit = (int) ((200 - headerHeight(table)) / rowHeight(table));
        scene.requestFocus(table);
        table.setSelectedRow(0);
        scene.renderFrame(canvas);
        assertEquals(0, table.firstVisibleRow());
        table.setSort(age, SortOrder.DESCENDING);
        scene.renderFrame(canvas);
        scene.renderFrame(canvas);
        assertEquals(20, table.focusRow(), "Person 0, age 39, is 21st by descending age");
        int first = table.firstVisibleRow();
        assertTrue(first == 20 - fit || first == 20 - fit + 1,
                "the least scroll shows the focus row as the last row in view, not the first: "
                        + first);
        table.setSort(age, SortOrder.NONE);
        scene.renderFrame(canvas);
        scene.renderFrame(canvas);
        assertEquals(0, table.focusRow());
        assertEquals(0, table.firstVisibleRow(), "and back at the top when it moves up");
    }

    @Test
    void aHeaderClickCyclesTheSortAndTheRequestHookReplacesIt() {
        Column<Person> name = nameColumn();
        Table<Person> table = new Table<>(List.of(name, ageColumn()));
        table.setRows(List.of(new Person("B", 0), new Person("A", 0)));
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        float y = headerHeight(table) / 2;
        click(scene, 50, y, 0);
        assertSame(name, table.sortColumn());
        assertEquals(SortOrder.ASCENDING, table.sortOrder());
        assertEquals(1, table.viewToModel(0), "A first");
        click(scene, 50, y, 0);
        assertEquals(SortOrder.DESCENDING, table.sortOrder());
        click(scene, 50, y, 0);
        assertEquals(SortOrder.NONE, table.sortOrder());

        AtomicReference<Column<Person>> asked = new AtomicReference<>();
        AtomicReference<SortOrder> order = new AtomicReference<>();
        table.onSortRequest((column, o) -> {
            asked.set(column);
            order.set(o);
        });
        click(scene, 50, y, 0);
        assertSame(name, asked.get());
        assertEquals(SortOrder.ASCENDING, order.get());
        assertEquals(0, table.viewToModel(0), "the toolkit sorted nothing: the application will");
        assertEquals(SortOrder.ASCENDING, table.sortOrder(), "but the header shows the order");
    }

    /**
     * FN-3 of the 2026-09-24 review: a number met a string through both formatted texts, so in a
     * column holding 5, 20 and "3" the order was a cycle, 5 &lt; 20 &lt; "3" &lt; 5. The sort threw
     * "Comparison method violates its general contract" on the 21st of these lists, and every
     * refresh threw again while the header showed the sort. The numbers come first now, by value,
     * then the text.
     */
    @Test
    void aColumnMixingNumbersAndTextSortsTheNumbersFirstAndThenTheText() {
        Column<Object> value = Column.of("Value", (Object o) -> o,
                (Object v, java.util.Locale l) -> String.valueOf(v));
        java.util.Random random = new java.util.Random(1);
        List<List<Object>> lists = new ArrayList<>();
        List<Table<Object>> tables = new ArrayList<>();
        for (int list = 0; list < 40; list++) {
            List<Object> rows = new ArrayList<>();
            int n = 64 + random.nextInt(2000);
            for (int i = 0; i < n; i++) {
                int k = random.nextInt(1000);
                rows.add(random.nextBoolean() ? (Object) k : (Object) Integer.toString(k));
            }
            Table<Object> table = new Table<>(List.of(value));
            table.setRows(rows);
            table.setSort(value, SortOrder.ASCENDING);
            table.refresh();
            lists.add(rows);
            tables.add(table);
        }
        for (int list = 0; list < lists.size(); list++) {
            List<Object> rows = lists.get(list);
            Table<Object> table = tables.get(list);
            Object previous = rows.get(table.viewToModel(0));
            for (int v = 1; v < rows.size(); v++) {
                Object current = rows.get(table.viewToModel(v));
                String where = "list " + list + ", row " + v + ": " + previous + " then " + current;
                if (current instanceof Integer c) {
                    assertTrue(previous instanceof Integer p && p <= c, where);
                } else if (previous instanceof String p) {
                    // Strings of digits alone collate as they compare by char.
                    assertTrue(p.compareTo((String) current) <= 0, where);
                }
                previous = current;
            }
        }
    }

    @Test
    void theKeyboardMovesTheFocusCellAndTheSelectionWithIt() {
        Table<Person> table = new Table<>(List.of(nameColumn(), ageColumn()));
        table.setRows(people(50));
        table.setSelectionMode(SelectionMode.MULTI);
        AtomicInteger selects = new AtomicInteger();
        table.onSelect(selects::incrementAndGet);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        scene.requestFocus(table);
        drive(scene).keyEvent(Keys.DOWN, true, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals(0, table.selectedRow(), "the first Down lands on the first row");
        assertEquals(0, table.focusRow());
        drive(scene).keyEvent(Keys.DOWN, true, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals(1, table.selectedRow());
        drive(scene).keyEvent(Keys.DOWN, true, false, Keys.MOD_SHIFT);
        drive(scene).inputBatchEnded();
        assertArrayEquals(new int[] {1, 2}, table.selectedRows(), "Shift extends the range");
        assertEquals(2, table.focusRow());
        drive(scene).keyEvent(Keys.RIGHT, true, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals(1, table.focusColumn());
        drive(scene).keyEvent(Keys.RIGHT, true, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals(1, table.focusColumn(), "no column past the last");
        drive(scene).keyEvent(Keys.END, true, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals(49, table.selectedRow());
        scene.renderFrame(canvas);
        assertTrue(table.firstVisibleRow() > 30, "End scrolled the last row into view");
        drive(scene).keyEvent(Keys.A, true, false, Accelerator.commandModifier());
        drive(scene).inputBatchEnded();
        assertEquals(50, table.selectedRows().length, "select all");
        assertTrue(selects.get() >= 5, "every change fired once: " + selects);
    }

    @Test
    void multiSelectionFollowsThePlatformsGrammar() {
        Table<Person> table = new Table<>(List.of(nameColumn(), ageColumn()));
        table.setRows(people(20));
        table.setSelectionMode(SelectionMode.MULTI);
        FakeCanvas canvas = new FakeCanvas(300, 300);
        Scene scene = scene(table, canvas);
        click(scene, 30, rowCenterY(table, 1), 0);
        assertArrayEquals(new int[] {1}, table.selectedRows());
        click(scene, 30, rowCenterY(table, 3), Keys.MOD_SHIFT);
        assertArrayEquals(new int[] {1, 2, 3}, table.selectedRows(), "Shift selects the range");
        click(scene, 30, rowCenterY(table, 5), Accelerator.commandModifier());
        assertArrayEquals(new int[] {1, 2, 3, 5}, table.selectedRows(), "command toggles one on");
        // The gesture moves the cursor to the row it toggled, which a reader's ADD_TO_SELECTION
        // and DESELECT do not (decision 20; TableAccessibilityTest pins the verbs' half).
        assertEquals(5, table.focusRow(), "the command-click moves the cursor");
        click(scene, 30, rowCenterY(table, 2), Accelerator.commandModifier());
        assertArrayEquals(new int[] {1, 3, 5}, table.selectedRows(), "and off");
        assertEquals(2, table.focusRow(), "toggling off moves it too");
        click(scene, 30, rowCenterY(table, 0), 0);
        assertArrayEquals(new int[] {0}, table.selectedRows(), "a plain click selects one alone");
        table.setSelectionMode(SelectionMode.NONE);
        assertEquals(-1, table.selectedRow());
        click(scene, 30, rowCenterY(table, 2), 0);
        assertEquals(0, table.selectedRows().length, "nothing selects in NONE");
        assertEquals(2, table.focusRow(), "but the focus cell still moves");
        table.setSelectionMode(SelectionMode.MULTI);
        table.setSelectedRows(7, 2, 9);
        assertArrayEquals(new int[] {2, 7, 9}, table.selectedRows(), "code names the set");
        assertEquals(9, table.selectedRow(), "and the last named is the lead");
        table.setSelectedRows();
        assertEquals(0, table.selectedRows().length, "none clears");
    }

    /**
     * Decision 32 of 2026-09-14: what Enter and a double click open is the cursor row, which
     * is the lead in {@code SINGLE}, may differ from it in {@code MULTI} after a toggle, and is
     * the only row there is in {@code NONE}.
     */
    /**
     * A refresh after the application removed rows from the front keeps the cursor on its record.
     * The cursor's old position was checked against the new list's size, so a cursor past the new
     * end was taken for gone: on I after A–E were removed it landed on J, and Enter opened J.
     */
    @Test
    void aRefreshAfterRowsLeftTheFrontKeepsTheCursorOnItsRecord() {
        Table<Person> table = new Table<>(List.of(nameColumn(), ageColumn()));
        List<Person> rows = people(10);
        table.setRows(rows);
        AtomicInteger activated = new AtomicInteger(-1);
        table.onActivate(activated::set);
        Scene scene = scene(table, new FakeCanvas(300, 300));
        scene.requestFocus(table);
        table.setSelectedRow(8); // Person 8, the cursor and the selection
        rows.subList(0, 5).clear();
        table.refresh();
        assertEquals(3, table.selectedRow(), "the selection followed Person 8");
        assertEquals(3, table.focusRow(), "and so did the cursor");
        drive(scene).keyEvent(Keys.ENTER, true, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals(3, activated.get(), "Enter opens Person 8, not Person 9");
    }

    @Test
    void enterAndADoubleClickActivateTheCursorRow() {
        Table<Person> table = new Table<>(List.of(nameColumn(), ageColumn()));
        table.setRows(people(5));
        AtomicInteger activated = new AtomicInteger(-1);
        table.onActivate(activated::set);
        FakeCanvas canvas = new FakeCanvas(300, 300);
        Scene scene = scene(table, canvas);
        scene.requestFocus(table);
        drive(scene).keyEvent(Keys.ENTER, true, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals(-1, activated.get(), "no cursor yet, nothing activated");
        table.setSelectedRow(3);
        drive(scene).keyEvent(Keys.ENTER, true, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals(3, activated.get());
        activated.set(-1);
        float y = rowCenterY(table, 1);
        click(scene, 30, y, 0);
        click(scene, 30, y, 0);
        assertEquals(1, activated.get(), "two presses on one row are a double click");

        table.setSelectionMode(SelectionMode.MULTI);
        table.setSelectedRows(1, 3); // the lead and the cursor on row 3
        click(scene, 30, rowCenterY(table, 3), Accelerator.commandModifier()); // toggled off
        assertEquals(1, table.selectedRow(), "the lead fell back to row 1");
        assertEquals(3, table.focusRow(), "the cursor stayed on row 3");
        drive(scene).keyEvent(Keys.ENTER, true, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals(3, activated.get(), "Enter opens the cursor row, not the lead");

        table.setSelectionMode(SelectionMode.NONE);
        click(scene, 30, rowCenterY(table, 2), 0);
        assertEquals(-1, table.selectedRow());
        drive(scene).keyEvent(Keys.ENTER, true, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals(2, activated.get(), "in NONE the cursor row is what opens");
    }

    private static void tab(Scene scene, boolean backward) {
        drive(scene).keyEvent(Keys.TAB, true, false, backward ? Keys.MOD_SHIFT : 0);
        drive(scene).inputBatchEnded();
    }

    private static void key(Scene scene, int key) {
        drive(scene).keyEvent(key, true, false, 0);
        drive(scene).inputBatchEnded();
    }

    /**
     * The critic's phase-2 finding (fix round of 2026-09-15): after decision 40's least-scroll
     * reveal, the first Page Down from the top of a long table scrolled by one row and left the
     * cursor at the foot of the view. Page Down and Page Up page: the cursor moves a page of rows
     * and the view moves with it, so the cursor keeps its place on screen — on a viewport that
     * holds a whole number of rows and on one that shows part of the next.
     */
    @Test
    void pageDownAndPageUpMoveTheViewAPageWithTheCursor() {
        for (int partial : new int[] {20, 0}) {
            Table<Person> table = new Table<>(List.of(nameColumn(), ageColumn()));
            table.setRows(people(100));
            float rowH = rowHeight(table);
            float headerH = headerHeight(table);
            int page = 6;
            FakeCanvas canvas = new FakeCanvas(300, headerH + page * rowH + partial);
            Scene scene = scene(table, canvas);
            scene.requestFocus(table);
            key(scene, Keys.DOWN);
            scene.renderFrame(canvas);
            assertEquals(0, table.focusRow());
            String geometry = partial == 0 ? "whole rows" : "a part of the next row showing";

            key(scene, Keys.PAGE_DOWN);
            scene.renderFrame(canvas);
            assertEquals(page, table.focusRow(), "the cursor a page away, " + geometry);
            assertEquals(page, table.firstVisibleRow(),
                    "the view a page down, the cursor still at its head, " + geometry);

            key(scene, Keys.DOWN);
            key(scene, Keys.PAGE_DOWN);
            scene.renderFrame(canvas);
            assertEquals(2 * page + 1, table.focusRow(), geometry);
            assertEquals(2 * page, table.firstVisibleRow(),
                    "the cursor kept its place, the second row in view, " + geometry);

            key(scene, Keys.PAGE_UP);
            scene.renderFrame(canvas);
            assertEquals(page + 1, table.focusRow(), geometry);
            assertEquals(page, table.firstVisibleRow(), "Page Up is the mirror, " + geometry);

            key(scene, Keys.PAGE_UP);
            key(scene, Keys.PAGE_UP);
            scene.renderFrame(canvas);
            assertEquals(0, table.focusRow(), "clamped at the top, " + geometry);
            assertEquals(0, table.firstVisibleRow(), geometry);
        }
    }

    /**
     * Decision 36 of 2026-09-14 (TABLE-SORT-KEYS): with a sortable column shown, the header is a
     * focus stop of its own, before the rows — Tab enters at the header, Tab again at the rows,
     * Shift+Tab walks the reverse — Left and Right move its column cursor, Home and End go to
     * the ends, Space sorts the column under it cycling ascending, descending and the model's
     * order, and Down hands the keyboard to the rows. A click on a header sorts and leaves the
     * keyboard in the rows, remembering the column for the next Tab into the header; a click on
     * a row takes the keyboard back from the header. Without a sortable column there is no stop.
     */
    @Test
    void theHeaderIsAFocusStopOfItsOwnAndTheKeyboardSortsFromIt() {
        Column<Person> name = nameColumn();
        Column<Person> age = ageColumn();
        Table<Person> table = new Table<>(List.of(name, age));
        table.setRows(List.of(new Person("B", 1), new Person("A", 2), new Person("C", 0)));
        Button before = new Button("Before");
        Button after = new Button("After");
        limn.scene.layout.Column root = new limn.scene.layout.Column();
        root.add(before);
        root.add(new limn.scene.layout.SizedBox(300, 150, table));
        root.add(after);
        FakeCanvas canvas = new FakeCanvas(300, 300);
        Scene scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.renderFrame(canvas);
        scene.requestFocus(before);

        tab(scene, false);
        assertSame(table, scene.focusedWidget());
        assertTrue(table.isHeaderFocused(), "Tab enters at the header");
        assertEquals(0, table.headerColumn());
        key(scene, Keys.RIGHT);
        assertEquals(1, table.headerColumn(), "Right moves the column cursor");
        key(scene, Keys.RIGHT);
        assertEquals(1, table.headerColumn(), "and stops at the last column");
        key(scene, Keys.LEFT);
        assertEquals(0, table.headerColumn());
        key(scene, Keys.END);
        assertEquals(1, table.headerColumn());
        key(scene, Keys.HOME);
        assertEquals(0, table.headerColumn());
        key(scene, Keys.SPACE);
        assertSame(name, table.sortColumn());
        assertEquals(SortOrder.ASCENDING, table.sortOrder(), "Space sorts the column under it");
        assertEquals(1, table.viewToModel(0), "A first");
        key(scene, Keys.SPACE);
        assertEquals(SortOrder.DESCENDING, table.sortOrder());
        assertEquals(2, table.viewToModel(0), "C first");
        key(scene, Keys.SPACE);
        assertEquals(SortOrder.NONE, table.sortOrder(), "then the model's order");
        assertEquals(0, table.viewToModel(0));
        key(scene, Keys.DOWN);
        assertFalse(table.isHeaderFocused(), "Down hands the keyboard to the rows");
        assertEquals(-1, table.focusRow(), "without moving the focus cell");
        assertSame(table, scene.focusedWidget());

        tab(scene, true);
        assertTrue(table.isHeaderFocused(), "Shift+Tab from the rows goes back to the header");
        key(scene, Keys.PAGE_DOWN);
        assertTrue(table.isHeaderFocused(), "a row key does nothing on the header");
        assertEquals(-1, table.selectedRow());
        tab(scene, false);
        assertFalse(table.isHeaderFocused(), "Tab from the header enters the rows");
        assertSame(table, scene.focusedWidget());
        tab(scene, false);
        assertSame(after, scene.focusedWidget(), "and Tab from the rows leaves");
        tab(scene, true);
        assertSame(table, scene.focusedWidget());
        assertFalse(table.isHeaderFocused(), "Shift+Tab enters at the rows, the last stop");
        tab(scene, true);
        assertTrue(table.isHeaderFocused());
        tab(scene, true);
        assertSame(before, scene.focusedWidget(), "and Shift+Tab from the header leaves");

        float headerY = headerHeight(table) / 2;
        float tableTop = table.localToSceneY();
        assertTrue(tableTop > 0, "the table sits under the first button: " + tableTop);
        click(scene, 130, tableTop + headerY, 0); // the Age header
        assertFalse(table.isHeaderFocused(), "the pointer sorts; the keyboard stays in the rows");
        assertSame(age, table.sortColumn());
        assertEquals(1, table.headerColumn(), "but the header's cursor remembers the column");
        tab(scene, true);
        assertTrue(table.isHeaderFocused());
        assertEquals(1, table.headerColumn(), "so Shift+Tab into the header starts there");
        click(scene, 30, tableTop + rowCenterY(table, 0), 0);
        assertFalse(table.isHeaderFocused(), "and a click on a row takes the keyboard back");

        name.sortable(false);
        age.sortable(false);
        table.refresh();
        scene.renderFrame(canvas);
        scene.requestFocus(before);
        tab(scene, false);
        assertSame(table, scene.focusedWidget());
        assertFalse(table.isHeaderFocused(), "nothing to sort, so the header is no stop");
        tab(scene, false);
        assertSame(after, scene.focusedWidget());
        tab(scene, true);
        assertSame(table, scene.focusedWidget());
        tab(scene, true);
        assertSame(before, scene.focusedWidget(), "in either direction");
    }

    @Test
    void weightsShareTheLeftoverAndADragOverridesIt() {
        Column<Person> name = nameColumn().weight(1);
        Column<Person> age = ageColumn();
        Table<Person> table = new Table<>(List.of(name, age));
        table.setRows(people(3));
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        assertEquals(240, table.widthOf(name), EPS, "the weighted column takes the leftover");
        assertEquals(60, table.widthOf(age), EPS);
        // Drag the divider at the name column's trailing edge 40 points to the left.
        float y = headerHeight(table) / 2;
        drive(scene).mouseMoved(240, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 240, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseMoved(200, y);
        drive(scene).inputBatchEnded();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, 200, y);
        drive(scene).inputBatchEnded();
        assertEquals(200, name.width(), EPS, "the dragged width is held on the column");
        scene.renderFrame(canvas);
        assertEquals(240, table.widthOf(name), EPS,
                "and the weight still fills the leftover from the dragged width");
        name.weight(0);
        table.refresh();
        scene.renderFrame(canvas);
        assertEquals(200, table.widthOf(name), EPS);
        age.visible(false);
        table.refresh();
        scene.renderFrame(canvas);
        assertEquals(0, table.widthOf(age), "a hidden column has no width");
    }

    @Test
    void theFooterSummarisesTheRowsAndTakesItsOwnStrip() {
        Column<Person> name = nameColumn().footer("Total");
        Column<Person> age = ageColumn().footerSum();
        Table<Person> table = new Table<>(List.of(name, age));
        List<Person> rows = new ArrayList<>(List.of(new Person("A", 10), new Person("B", 20),
                new Person("C", 30)));
        table.setRows(rows);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        assertEquals("Total", table.footerTextOf(name));
        assertEquals("60", table.footerTextOf(age));
        rows.add(new Person("D", 40));
        table.refresh();
        assertEquals("100", table.footerTextOf(age), "recomputed on refresh");
        age.footerAverage();
        table.refresh();
        assertEquals("25", table.footerTextOf(age));
        age.footerMax();
        table.refresh();
        assertEquals("40", table.footerTextOf(age));
        age.footerMin();
        table.refresh();
        assertEquals("10", table.footerTextOf(age));
        name.footerCount();
        table.refresh();
        assertEquals("4", table.footerTextOf(name));
        age.footer(all -> all.get(0).age() * 2.0);
        table.refresh();
        assertEquals("20", table.footerTextOf(age), "a function, formatted as the column is");
        // The footer takes a strip of its own, so fewer rows fit than without it.
        Table<Person> plain = new Table<>(List.of(nameColumn(), ageColumn()));
        plain.setRows(people(200));
        scene(plain, canvas);
        table.setRows(people(200));
        scene.renderFrame(canvas);
        scene.requestFocus(table);
        drive(scene).keyEvent(Keys.END, true, false, 0);
        drive(scene).inputBatchEnded();
        scene.renderFrame(canvas);
        Scene plainScene = scene(plain, canvas);
        plainScene.requestFocus(plain);
        drive(plainScene).keyEvent(Keys.END, true, false, 0);
        drive(plainScene).inputBatchEnded();
        plainScene.renderFrame(canvas);
        assertTrue(table.firstVisibleRow() > plain.firstVisibleRow(),
                "with a footer the last row sits higher, so the first shown row is later");
    }

    /**
     * FN-9 of the 2026-09-24 review: the footer's documentation asks a numeric column for "a
     * number", and its formatter cast what came back to {@code Double}, so the natural
     * {@code mapToInt(...).sum()} threw a ClassCastException out of {@code setRows}. Any
     * {@code Number} is written by the column's format now.
     */
    @Test
    void aNumericColumnsFooterFunctionMayAnswerAnyNumber() {
        Column<Person> sum = ageColumn().footer(all -> all.stream().mapToInt(Person::age).sum());
        Column<Person> max = ageColumn().footer(
                all -> all.stream().mapToLong(Person::age).max().orElse(0));
        Column<Person> exact = ageColumn().footer(all -> new java.math.BigDecimal("2.5"));
        Table<Person> table = new Table<>(List.of(nameColumn(), sum, max, exact));
        table.setRows(List.of(new Person("A", 2), new Person("B", 3)));
        assertEquals("5", table.footerTextOf(sum), "an int");
        assertEquals("3", table.footerTextOf(max), "a long");
        assertTrue(table.footerTextOf(exact).matches("2[.,]5"),
                "a BigDecimal: " + table.footerTextOf(exact));
    }

    @Test
    void aNumericColumnsFormatWritesItsCellsAndItsFooterAlike() {
        Column<Person> price = Column.<Person>numeric("Price", p -> p.age() * 10.5,
                limn.i18n.NumberFormats.prefix("R$ ")).footerSum();
        Column<Person> money = Column.<Person>currency("Money", p -> p.age() * 10.5,
                java.util.Currency.getInstance("BRL")).footerSum();
        Table<Person> table = new Table<>(List.of(nameColumn(), price, money));
        table.setRows(List.of(new Person("A", 1), new Person("B", 2)));
        FakeCanvas canvas = new FakeCanvas(400, 200);
        Scene scene = scene(table, canvas);
        assertTrue(table.footerTextOf(price).startsWith("R$ "), table.footerTextOf(price));
        assertTrue(table.footerTextOf(price).endsWith("31,5") || table.footerTextOf(price).endsWith("31.5"),
                "the sum, written by the column's own format: " + table.footerTextOf(price));
        String sum = table.footerTextOf(money);
        assertTrue(sum.contains("R$") || sum.contains("BRL"), "money keeps its currency: " + sum);
        assertTrue(sum.contains("31,50") || sum.contains("31.50"), "and its two decimals: " + sum);
        scene.setLocale(java.util.Locale.FRANCE);
        table.refresh();
        scene.renderFrame(canvas);
        String french = table.footerTextOf(money);
        assertTrue(french.endsWith("R$") || french.endsWith("BRL"),
                "under fr the symbol follows the amount: " + french);
    }

    /**
     * TABLE-NEW-3 (decision 23 of 2026-09-14): the documented {@code onSortRequest} recipe —
     * reorder the list, call {@code refresh()} — kept the selection by row number, so it moved
     * onto whatever records the application's sort had put at those numbers. A row is its
     * record now: the selection, the lead and the focus cell follow Carol to where she is.
     */
    @Test
    void aServerSortKeepsTheSelectionOnItsRecords() {
        Column<Person> name = nameColumn();
        Table<Person> table = new Table<>(List.of(name, ageColumn()));
        List<Person> rows = new ArrayList<>(List.of(new Person("Carol", 3),
                new Person("Alice", 1), new Person("Bob", 2)));
        table.setRows(rows);
        table.onSortRequest((column, order) -> {
            rows.sort(order == SortOrder.DESCENDING
                    ? java.util.Comparator.comparing(Person::name).reversed()
                    : java.util.Comparator.comparing(Person::name));
            table.refresh();
        });
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        scene.requestFocus(table);
        table.setSelectedRow(0); // Carol
        click(scene, 50, headerHeight(table) / 2, 0);
        scene.renderFrame(canvas);
        assertEquals("Carol", rows.get(2).name(), "the application sorted its list");
        assertEquals(2, table.selectedRow(), "and the selection followed her record");
        assertEquals(2, table.focusRow(), "as did the focus cell");
        drive(scene).keyEvent(Keys.UP, true, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals("Bob", rows.get(table.selectedRow()).name(), "Up from Carol is Bob");
    }

    /**
     * A refresh that answers a sort request is the application's sort, so it reveals the focus
     * row as the table's own sort does (decision 40); a plain refresh keeps the scroll position
     * it promises, which {@code TableFocusedRowTest} pins from the reader's side.
     */
    @Test
    void aSortRequestsRefreshRevealsTheFocusRowAsTheTablesOwnSortDoes() {
        Column<Person> name = nameColumn();
        Table<Person> table = new Table<>(List.of(name, ageColumn()));
        List<Person> rows = new ArrayList<>(people(60));
        table.setRows(rows);
        table.onSortRequest((column, order) -> {
            java.util.Collections.reverse(rows);
            table.refresh();
        });
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        scene.requestFocus(table);
        table.setSelectedRow(0);
        click(scene, 50, headerHeight(table) / 2, 0);
        scene.renderFrame(canvas);
        scene.renderFrame(canvas);
        assertEquals(59, table.focusRow(), "Person 0 is last now");
        assertTrue(table.firstVisibleRow() > 40,
                "and the table scrolled to show it: " + table.firstVisibleRow());
    }

    /**
     * Decision 23 of 2026-09-14, the general case: any insert, remove or reorder followed by
     * {@code refresh()} keeps the selection, the lead, the focus cell and the range anchor on
     * their records; a record the list no longer holds leaves the selection with one
     * {@code SELECTION}/{@code ADJUSTMENT}, and the handler hears nothing, since no user chose.
     */
    @Test
    void refreshFollowsTheRecordsThroughAnInsertARemoveAndAReorder() {
        List<Person> rows = new ArrayList<>(people(6));
        Table<Person> table = new Table<>(List.of(nameColumn(), ageColumn()));
        table.setRows(rows);
        table.setSelectionMode(SelectionMode.MULTI);
        AtomicInteger handled = new AtomicInteger();
        table.onSelect(handled::incrementAndGet);
        List<String> heard = new ArrayList<>();
        table.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.SELECTION
                    || change.aspect() == Change.Aspect.ACTIVE) {
                heard.add(change.aspect() + "/" + change.origin());
            }
        });
        FakeCanvas canvas = new FakeCanvas(300, 300);
        Scene scene = scene(table, canvas);
        scene.requestFocus(table);
        table.setSelectedRows(1, 3); // Person 1 and Person 3, the lead and the focus row
        heard.clear();

        rows.add(0, new Person("Newcomer", 99));
        table.refresh();
        scene.renderFrame(canvas);
        assertArrayEquals(new int[] {2, 4}, table.selectedRows(), "both moved down one");
        assertEquals(4, table.selectedRow(), "the lead is still Person 3");
        assertEquals(4, table.focusRow(), "and so is the focus cell");
        assertEquals(List.of("ACTIVE/ADJUSTMENT"), heard,
                "the cursor's move is announced; the selection's records are the same set");
        heard.clear();

        rows.remove(4); // Person 3, the lead, is gone
        table.refresh();
        scene.renderFrame(canvas);
        assertArrayEquals(new int[] {2}, table.selectedRows(), "the vanished record left");
        assertEquals(2, table.selectedRow(), "the last selected row is the lead now");
        assertEquals(4, table.focusRow(), "the focus cell keeps its position");
        assertEquals(List.of("SELECTION/ADJUSTMENT"), heard, "one announcement, a consequence");
        assertEquals(0, handled.get(), "and no handler: no user chose a row");
        heard.clear();

        java.util.Collections.reverse(rows);
        table.refresh();
        scene.renderFrame(canvas);
        assertArrayEquals(new int[] {3}, table.selectedRows(), "Person 1 is fourth of six now");
        assertEquals("Person 1", rows.get(table.selectedRow()).name());
        assertEquals(1, table.focusRow(), "the focus row's record, Person 4, is second now");
        drive(scene).keyEvent(Keys.UP, true, false, Keys.MOD_SHIFT);
        drive(scene).inputBatchEnded();
        assertArrayEquals(new int[] {0, 1}, table.selectedRows(),
                "Shift+Up extends from the anchor, which followed Person 4 too");
    }

    /**
     * Decision 23's rule for records that are equal: they are told apart by occurrence, so the
     * third "a" stays the third "a" after an insert above it; and a {@link Table#rowKey} names
     * what identity is when {@code equals} does not — here the name, so an edited record with
     * the same name is the same row.
     */
    @Test
    void equalRecordsAreToldApartByOccurrenceAndARowKeyNamesIdentity() {
        Table<String> letters = new Table<>(List.of(Column.<String>text("Letter", s -> s)));
        List<String> as = new ArrayList<>(List.of("a", "a", "a", "b"));
        letters.setRows(as);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(letters, canvas);
        letters.setSelectedRow(2); // the third "a"
        as.add(0, "b");
        letters.refresh();
        scene.renderFrame(canvas);
        assertEquals(3, letters.selectedRow(), "still the third \"a\", which sits fourth now");
        as.remove(1); // an "a" before it is gone, so there is no third "a" any more
        letters.refresh();
        assertEquals(-1, letters.selectedRow(),
                "occurrence is all that tells equal records apart: the third \"a\" is gone");

        Table<Person> people = new Table<>(List.of(nameColumn(), ageColumn()));
        List<Person> rows = new ArrayList<>(List.of(new Person("Carol", 3),
                new Person("Alice", 1), new Person("Bob", 2)));
        people.setRows(rows);
        people.rowKey(Person::name);
        scene(people, canvas);
        people.setSelectedRow(2); // Bob
        rows.set(2, new Person("Bob", 40));
        rows.add(0, rows.remove(2));
        people.refresh();
        assertEquals(0, people.selectedRow(), "Bob, older and first now, is still selected");
        assertEquals(40, rows.get(people.selectedRow()).age());
    }

    @Test
    void refreshDropsASelectionTheListNoLongerHas() {
        List<Person> rows = new ArrayList<>(people(4));
        Table<Person> table = new Table<>(List.of(nameColumn(), ageColumn()));
        table.setRows(rows);
        AtomicInteger handled = new AtomicInteger();
        table.onSelect(handled::incrementAndGet);
        List<Change.Origin> heard = new ArrayList<>();
        table.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.SELECTION) {
                heard.add(change.origin());
            }
        });
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        table.setSelectedRow(3);
        heard.clear();
        rows.remove(3);
        table.refresh();
        scene.renderFrame(canvas);
        assertEquals(-1, table.selectedRow(), "the deleted row is not selected");
        assertEquals(List.of(Change.Origin.ADJUSTMENT), heard,
                "a watcher heard the table drop it by itself");
        assertEquals(0, handled.get(), "the handler answers the user, and no user chose a row");
        assertEquals(3, table.rowCount());
    }

    @Test
    void rightToLeftPutsTheFirstColumnAtTheRightEdge() {
        Column<Person> name = nameColumn();
        Column<Person> age = ageColumn();
        Table<Person> table = new Table<>(List.of(name, age));
        table.setRows(people(3));
        AtomicReference<Column<Person>> asked = new AtomicReference<>();
        table.onSortRequest((column, order) -> asked.set(column));
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        scene.setLayoutDirection(LayoutDirection.RTL);
        scene.renderFrame(canvas);
        float y = headerHeight(table) / 2;
        click(scene, 280, y, 0);
        assertSame(name, asked.get(), "the first column is at the right edge");
        click(scene, 170, y, 0);
        assertSame(age, asked.get(), "and the second is to its left");
    }

    @Test
    void aWidgetCellIsAChildOfTheTableAndGoesWithItsRow() {
        Column<Person> button = Column.<Person>widget(limn.i18n.I18nString.literal("Open"),
                p -> new Button(p.name())).width(80);
        Table<Person> table = new Table<>(List.of(nameColumn(), button));
        table.setRows(people(100));
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        List<Widget<?>> first = new ArrayList<>(table.children());
        table.scrollBy(0, 5000);
        scene.renderFrame(canvas);
        for (Widget<?> w : table.children()) {
            if (w instanceof Button) {
                assertTrue(!first.contains(w), "a scrolled-away row released its widget");
            }
        }
        assertTrue(table.firstVisibleRow() > 50);
    }

    /**
     * B6 (2026-09-14): a hidden widget column built a widget per realized row all the same,
     * never laid out but a child, so a Button in it was a Tab stop nobody could see and a
     * published node with a column index past the table's. Now a hidden widget column builds
     * nothing; showing it is picked up by the next layout, which is what {@code refresh()}
     * asks for, and hiding it again releases the widgets the same way.
     */
    @Test
    void aHiddenWidgetColumnBuildsNothingAndIsNoTabStop() {
        AtomicInteger built = new AtomicInteger();
        Column<Person> button = Column.<Person>widget("Open", p -> {
            built.incrementAndGet();
            return new Button("Open");
        }).width(80).visible(false);
        Table<Person> table = new Table<>(List.of(nameColumn(), button));
        table.setRows(people(50));
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        assertEquals(0, built.get(), "a hidden widget column builds no widget");
        assertEquals(2, table.children().size(), "the two bars and nothing else");
        scene.requestFocus(table);
        scene.focusTraverse(false);
        scene.renderFrame(canvas);
        assertFalse(scene.focusedWidget() instanceof Button, "no widget, so no Tab stop: "
                + scene.focusedWidget());

        button.visible(true);
        table.refresh();
        scene.renderFrame(canvas);
        int rowsThatFit = (int) Math.ceil((200 - headerHeight(table)) / rowHeight(table));
        assertTrue(built.get() >= 3 && built.get() <= rowsThatFit + 1,
                "shown, it builds the viewport's worth and no more: " + built);
        assertEquals(2 + built.get(), table.children().size(), "and each is a child");
        scene.requestFocus(table);
        scene.focusTraverse(false);
        scene.renderFrame(canvas);
        assertTrue(scene.focusedWidget() instanceof Button, "a shown widget cell is a Tab stop");

        // Hidden again, with no refresh: the next layout is enough, and the focused widget's
        // release hands the keyboard back to the table rather than dropping it.
        button.visible(false);
        table.setShowHeader(false);
        scene.renderFrame(canvas);
        assertEquals(2, table.children().size(), "the next layout released the widgets");
        assertSame(table, scene.focusedWidget(), "the keyboard came back to the table");
    }

    /**
     * Review of table-B (2026-09-14): a change to the shown set re-mounted every realized row,
     * so hiding a value column, with or without a refresh, rebuilt every widget cell and took
     * the keyboard off the switch a user was on. The layout now releases only a hidden widget
     * column's widgets and builds only a newly shown one's, in their places among the children;
     * a refresh rebuilds every other row and, since 2026-09-15, keeps the one whose widget cell
     * holds the keyboard (decision 22), as {@code Column.visible} says.
     */
    @Test
    void aColumnShownOrHiddenLeavesEveryOtherWidgetCellAndTheKeyboardWhereTheyAre() {
        List<Widget<?>> opens = new ArrayList<>();
        List<Widget<?>> mores = new ArrayList<>();
        Column<Person> age = ageColumn();
        Column<Person> open = Column.<Person>widget("Open", p -> {
            Button b = new Button("Open");
            opens.add(b);
            return b;
        }).width(80);
        Column<Person> more = Column.<Person>widget("More", p -> {
            Button b = new Button("More");
            mores.add(b);
            return b;
        }).width(80).visible(false);
        Table<Person> table = new Table<>(List.of(nameColumn(), age, open, more));
        table.setRows(people(50));
        FakeCanvas canvas = new FakeCanvas(400, 200);
        Scene scene = scene(table, canvas);
        int built = opens.size();
        assertTrue(built >= 3, "the viewport's worth of Open buttons: " + built);
        Widget<?> target = opens.get(1);
        scene.requestFocus(target);
        scene.renderFrame(canvas);
        assertSame(target, scene.focusedWidget());

        age.visible(false);
        table.markNeedsLayout();
        scene.renderFrame(canvas);
        assertEquals(built, opens.size(), "hiding a value column builds no widget");
        assertSame(target, scene.focusedWidget(), "and leaves the keyboard on the switch it was on");
        assertTrue(table.children().contains(target), "which is still a child");

        more.visible(true);
        table.markNeedsLayout();
        scene.renderFrame(canvas);
        assertEquals(built, opens.size(), "showing a widget column rebuilds no other column's widget");
        assertEquals(built, mores.size(), "and builds its own for each realized row");
        assertSame(target, scene.focusedWidget());
        List<Widget<?>> children = table.children();
        assertEquals(2 + 2 * built, children.size(), "the bars, then two cells per realized row");
        for (int i = 0; i < built; i++) {
            assertSame(opens.get(i), children.get(2 + 2 * i), "row " + i + "'s Open in data order");
            assertSame(mores.get(i), children.get(3 + 2 * i), "and its More after it, by column");
        }

        more.visible(false);
        table.markNeedsLayout();
        scene.renderFrame(canvas);
        assertEquals(2 + built, table.children().size(), "hidden again, its widgets are released");
        assertSame(target, scene.focusedWidget(), "and the Open the user is on stays");

        // A refresh that also shows a widget column (the Column.visible recipe) rebuilds every
        // other row, and keeps the row whose switch holds the keyboard (decision 22).
        more.visible(true);
        table.refresh();
        scene.renderFrame(canvas);
        assertTrue(opens.size() > built, "a refresh rebuilds every other row, as documented");
        assertSame(target, scene.focusedWidget(), "and keeps the Open the user is on");
        assertTrue(table.children().contains(target), "a child still");
    }

    /**
     * Decision 22 of 2026-09-14, the widget-cell half (fix round of 2026-09-15): a refresh or a
     * sort released the row whose widget cell held the keyboard and handed the keyboard to the
     * table (ac431b4), {@code ListView}'s rule for a widget bound to data the list may no longer
     * hold. A table follows its records (decision 23), so a record found again keeps its row and
     * the widget the user is on; only a record the list lost releases it.
     */
    @Test
    void aRefreshOrASortKeepsTheWidgetCellThatHoldsTheKeyboardWhileItsRecordStays() {
        List<Widget<?>> opens = new ArrayList<>();
        Column<Person> open = Column.<Person>widget("Open", p -> {
            Button b = new Button("Open " + p.name());
            opens.add(b);
            return b;
        }).width(120);
        Table<Person> table = new Table<>(List.of(nameColumn(), ageColumn(), open));
        List<Person> rows = new ArrayList<>(people(50));
        table.setRows(rows);
        FakeCanvas canvas = new FakeCanvas(400, 200);
        Scene scene = scene(table, canvas);
        Widget<?> target = opens.get(2); // Person 2's
        scene.requestFocus(target);
        scene.renderFrame(canvas);
        float wasY = target.y();
        int builtBefore = opens.size();

        rows.add(0, new Person("Newcomer", 99));
        table.refresh();
        scene.renderFrame(canvas);
        assertTrue(opens.size() > builtBefore + 2, "the other rows were rebuilt: " + opens.size());
        float rowHeight = opens.get(builtBefore + 1).y() - opens.get(builtBefore).y();
        assertTrue(rowHeight > 0, "Newcomer's button above Person 0's");
        assertSame(target, scene.focusedWidget(), "the refresh left the keyboard on Person 2's button");
        assertTrue(table.children().contains(target), "which is still the table's child");
        assertEquals(wasY + rowHeight, target.y(), EPS, "one row further down, with its record");
        assertEquals(1, table.children().stream().filter(w -> w == target).count());

        table.setSort(table.columns().get(1), SortOrder.DESCENDING);
        scene.renderFrame(canvas);
        assertSame(target, scene.focusedWidget(), "a sort keeps it too");
        assertTrue(table.children().contains(target));

        rows.remove(new Person("Person 2", 2));
        table.refresh();
        scene.renderFrame(canvas);
        assertFalse(table.children().contains(target), "a record the list lost releases its row");
        assertSame(table, scene.focusedWidget(), "and hands the keyboard to the table");
    }

    /**
     * TABLE-NEW-4 (2026-09-14): {@code onSortRequest} assigned its field directly, the one
     * registrar outside {@code Work} that skipped ADR 040's one-slot policy, and the slot
     * changing hands sorted nothing: a handler set over a toolkit sort left the permutation in
     * place until the next refresh, and clearing it left the rows in whatever order the
     * application had put them under a header still showing a sort.
     */
    @Test
    void theSortRequestSlotIsOneSlotAndChangingHandsReSortsAtOnce() {
        Column<Person> name = nameColumn();
        Table<Person> table = new Table<>(List.of(name, ageColumn()));
        table.setRows(List.of(new Person("Carol", 3), new Person("Alice", 1),
                new Person("Bob", 2)));
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        scene.requestFocus(table);
        table.setSelectedRow(0); // Carol
        table.setSort(name, SortOrder.ASCENDING);
        scene.renderFrame(canvas);
        assertEquals(1, table.viewToModel(0), "the toolkit sorted: Alice first");
        assertEquals(2, table.focusRow(), "the cursor is on Carol, shown last");

        List<Change> heard = new ArrayList<>();
        table.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.CHILDREN) {
                heard.add(change);
            }
        });
        AtomicInteger told = new AtomicInteger();
        java.util.function.BiConsumer<Column<Person>, SortOrder> first =
                (column, order) -> told.incrementAndGet();
        table.onSortRequest(first);
        assertThrows(IllegalStateException.class,
                () -> table.onSortRequest((column, order) -> { }),
                "a second handler over the first is refused, as every onX is");
        assertEquals(0, table.viewToModel(0), "set, the permutation is dropped at once");
        assertSame(name, table.sortColumn());
        assertEquals(SortOrder.ASCENDING, table.sortOrder(), "the header still shows the order");
        assertEquals(0, table.focusRow(), "the cursor went with Carol, first in model order");
        assertEquals(List.of(Change.Origin.CODE), heard.stream().map(Change::origin).toList(),
                "announced as a sort is, as a caller's write");
        assertEquals(0, told.get(), "and reaches no handler: nobody clicked");
        scene.renderFrame(canvas);

        // The application answers a click by reordering its own list; here it ignores it, so
        // the rows stay in model order under a header that now says descending.
        float y = headerHeight(table) / 2;
        click(scene, 50, y, 0);
        assertEquals(1, told.get(), "the click reached the handler");
        assertEquals(SortOrder.DESCENDING, table.sortOrder());
        assertEquals(0, table.viewToModel(0), "the application sorted nothing");
        heard.clear();

        table.onSortRequest(null);
        assertEquals(0, table.viewToModel(0), "cleared, the table sorts by the header: Carol");
        assertEquals(1, table.viewToModel(2), "Alice last, descending");
        assertEquals(0, table.focusRow(), "the cursor is still on Carol");
        assertEquals(List.of(Change.Origin.CODE), heard.stream().map(Change::origin).toList());
        table.onSortRequest((column, order) -> told.incrementAndGet());
        assertEquals(0, table.viewToModel(0), "null then a handler is allowed, and drops the sort");
        assertEquals(1, table.viewToModel(1), "model order again");
    }

    // ------------------------------------------------------------- the wheel and the height

    private static Constraints unbounded() {
        return new Constraints(0, Constraints.UNBOUNDED_LIMIT, 0, Constraints.UNBOUNDED_LIMIT);
    }

    /** The x of any widget cell of the one widget column: every row's sits at the same x. */
    private static float widgetX(Table<?> table) {
        for (Widget<?> child : table.children()) {
            if (!(child instanceof ScrollBar)) {
                return child.x();
            }
        }
        throw new AssertionError("no widget cell mounted");
    }

    private static void wheel(Scene scene, FakeCanvas canvas, float sx, float sy) {
        drive(scene).scrolled(sx, sy, 50, 100);
        drive(scene).inputBatchEnded();
        scene.renderFrame(canvas);
    }

    /**
     * TABLE-NEW-12 (2026-09-14): any non-zero {@code scrollX} made a wheel event sideways and
     * dropped its {@code scrollY}, so a trackpad swipe that was not perfectly vertical scrolled
     * nothing on a table whose columns fit, and only sideways on one that did not. The axes are
     * taken independently now, as {@code ScrollView} takes them; Shift still turns a plain
     * vertical wheel into a horizontal one, and only a plain one.
     */
    @Test
    void aWheelTakesBothAxesAndShiftTurnsAPlainWheelSideways() {
        Table<Person> narrow = new Table<>(List.of(nameColumn()));
        narrow.setRows(people(50));
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(narrow, canvas);
        wheel(scene, canvas, 0.3f, -3);
        assertTrue(narrow.firstVisibleRow() > 0,
                "a slightly diagonal wheel scrolls the rows of a table whose columns fit");

        List<Column<Person>> columns = new ArrayList<>();
        columns.add(Column.<Person>widget("Open", p -> new Button("Open")).width(120));
        for (int c = 0; c < 4; c++) {
            columns.add(Column.text("Name " + c, Person::name).width(120));
        }
        Table<Person> wide = new Table<>(columns);
        wide.setRows(people(50));
        scene = scene(wide, canvas);
        float x0 = widgetX(wide);
        wheel(scene, canvas, -0.5f, -3); // toward the later columns, and down
        assertTrue(wide.firstVisibleRow() > 0, "the rows moved");
        assertEquals(x0 - 0.5f * Strokes.WHEEL_STEP, widgetX(wide), EPS,
                "and the columns, each axis by its own delta");

        int row = wide.firstVisibleRow();
        float x1 = widgetX(wide);
        drive(scene).keyEvent(Keys.LEFT_SHIFT, true, false, Keys.MOD_SHIFT);
        wheel(scene, canvas, 0, -1);
        assertEquals(row, wide.firstVisibleRow(), "Shift sends a plain wheel sideways, not down");
        assertEquals(x1 - Strokes.WHEEL_STEP, widgetX(wide), EPS);
        wheel(scene, canvas, -1, -1);
        assertEquals(x1 - 2 * Strokes.WHEEL_STEP, widgetX(wide), EPS,
                "a tilt wheel with Shift held drives its own axis once");
        assertTrue(wide.firstVisibleRow() > row, "and its scrollY still moves the rows");
        drive(scene).keyEvent(Keys.LEFT_SHIFT, false, false, 0);

        wheel(scene, canvas, -100, 0);
        assertEquals(x0 - (5 * 120 - 300), widgetX(wide), EPS,
                "sideways clamps at the content's end");
    }

    /**
     * Decision 44 (2026-09-14): the wheel was consumed whenever the rows overflowed, so a table
     * inside a scroll pane was a wall the wheel could not get past once it had scrolled to its
     * end. A detent is consumed only when an offset moved; at either end, or on a table that
     * fits, it is left for the scroller that holds the table.
     */
    @Test
    void aWheelAtEitherEndOfTheTablePassesToTheScrollerThatHoldsIt() {
        Table<Person> table = new Table<>(List.of(nameColumn()));
        table.setRows(people(20));
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(table);
        column.add(new PlainWidget() {
            @Override
            protected limn.scene.Size onMeasure(Constraints c) {
                return c.constrain(c.maxWidth(), 400);
            }
        });
        ScrollView pane = new ScrollView(column);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = new Scene(pane);
        scene.setTextRuler(RULER);
        scene.renderFrame(canvas);
        float viewport = 8 * SizeTokens.of(ControlSize.MEDIUM).listRowSeed();
        assertEquals(headerHeight(table) + viewport, table.height(), EPS,
                "the fixture: the header and eight seed rows");
        float tableMax = 20 * rowHeight(table) - viewport;

        // Down: the table takes every detent until it rests on its last row.
        int notches = 0;
        while (pane.offsetY() == 0 && notches < 100) {
            wheel(scene, canvas, 0, -1);
            notches++;
            if (notches * Strokes.WHEEL_STEP < tableMax) {
                assertEquals(0, pane.offsetY(), EPS,
                        "the pane stays put while the table can still scroll: notch " + notches);
            }
        }
        assertEquals((int) Math.ceil(tableMax / Strokes.WHEEL_STEP) + 1, notches,
                "the first detent the table cannot use is the pane's");
        assertEquals(Strokes.WHEEL_STEP, pane.offsetY(), EPS, "one notch of the pane");

        // Up: the table is at its end and not at its top, so it takes the detent back first.
        wheel(scene, canvas, 0, 1);
        assertEquals(Strokes.WHEEL_STEP, pane.offsetY(), EPS,
                "the table could move up, so it did and the pane did not");

        // Up at the top: the table scrolled back to zero, and the next detent is the pane's.
        for (int i = 0; i < 40; i++) {
            wheel(scene, canvas, 0, 1);
        }
        assertEquals(0, table.firstVisibleRow());
        assertEquals(0, pane.offsetY(), EPS, "the pane took the detent the table could not");

        // A table that fits hands every detent on: three rows never take one.
        table.setRows(people(3));
        scene.renderFrame(canvas);
        wheel(scene, canvas, 0, -1);
        assertEquals(Strokes.WHEEL_STEP, pane.offsetY(), EPS, "nothing to scroll, so the pane's");
    }

    /**
     * Decision 44 (2026-09-14): under an unbounded height the table answered eight rows of the
     * realized average, which moved as rows of another height were measured, and a measured
     * size that moves under a contained layout re-lays out the parent: a table in a scroll pane
     * jittered as it scrolled. The preference is the seed's, a token that stands still, and
     * {@code setVisibleRows} says how many of it.
     */
    @Test
    void theUnboundedHeightIsTheSeedsAndSetVisibleRowsChangesIt() {
        Column<Person> tall = Column.<Person>widget("Tall", p -> new SizedBox(40, 60)).width(80);
        Table<Person> table = new Table<>(List.of(nameColumn(), tall));
        table.setRows(people(100));
        for (ControlSize step : ControlSize.values()) {
            table.setControlSize(step);
            SizeTokens t = SizeTokens.of(step);
            assertEquals(t.controlHeight() + 8 * t.listRowSeed(),
                    table.measure(unbounded()).height(), EPS,
                    step + ": the header and eight seed rows");
        }
        table.setControlSize(ControlSize.MEDIUM);
        SizeTokens t = SizeTokens.of(ControlSize.MEDIUM);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        List<Float> tops = new ArrayList<>();
        for (Widget<?> child : table.children()) {
            if (child instanceof SizedBox) {
                tops.add(child.y());
            }
        }
        float measured = tops.get(1) - tops.get(0);
        assertEquals(60 + 2 * t.padV(), measured, EPS, "the fixture: rows taller than the seed");
        assertTrue(Math.abs(measured - t.listRowSeed()) > 1, "and not the seed by accident");
        assertEquals(t.controlHeight() + 8 * t.listRowSeed(), table.measure(unbounded()).height(),
                EPS, "rows of another height were measured and the preference did not move");

        assertEquals(8, table.visibleRows(), "the default");
        table.setVisibleRows(3);
        assertEquals(3, table.visibleRows());
        assertEquals(t.controlHeight() + 3 * t.listRowSeed(), table.measure(unbounded()).height(),
                EPS);
        assertThrows(IllegalArgumentException.class, () -> table.setVisibleRows(0));
        assertEquals(3, table.visibleRows(), "refused, and unchanged");
    }

    // ------------------------------------------------------ horizontal scrolling (B3)

    /** Five text columns of 120 points: "P3c1" is row 3's cell in column 1, short so nothing ellipsizes. */
    private static List<Column<Person>> fiveColumns() {
        List<Column<Person>> columns = new ArrayList<>();
        for (int c = 0; c < 5; c++) {
            final int n = c;
            columns.add(Column.<Person>text("C" + c,
                    p -> "P" + p.name().substring("Person ".length()) + "c" + n).width(120));
        }
        return columns;
    }

    /** {@link #RULER}, counting the lines it shaped by text. */
    private static final class CountingRuler implements TextRuler {
        final List<String> shaped = new ArrayList<>();

        @Override
        public TextMetrics measure(String text, Font font) {
            return RULER.measure(text, font);
        }

        @Override
        public ShapedText shape(String text, Font font, ShapedText.Direction base) {
            shaped.add(text);
            return TextRuler.super.shape(text, font, base);
        }

        int shapedCount(String text) {
            int n = 0;
            for (String s : shaped) {
                if (s.equals(text)) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * B3 (2026-09-14): nothing had ever scrolled a table sideways. The band of columns that
     * intersect the viewport is what is shaped and painted (ADR 041 §2): a column wholly
     * outside is neither, its text is shaped the first time it enters and held after, and the
     * offset clamps at the content's end.
     */
    @Test
    void aWideTableScrollsSidewaysAndShapesAndPaintsOnlyTheColumnsInView() {
        Table<Person> table = new Table<>(fiveColumns());
        table.setRows(people(20));
        CountingRuler ruler = new CountingRuler();
        TablePaintCanvas canvas = new TablePaintCanvas(300, 200);
        Scene scene = new Scene(table);
        scene.setTextRuler(ruler);
        scene.renderFrame(canvas);
        float padH = Theme.current().tokensFor(table).padH();
        assertTrue(canvas.drew("P0c0") && canvas.drew("P0c2"),
                "the columns in view, the cut one included: " + canvas.texts());
        assertFalse(canvas.drew("P0c3"), "a column wholly outside the viewport is not painted");
        assertFalse(canvas.drew("C3"), "nor its header");
        assertEquals(0, ruler.shapedCount("P0c3"), "nor shaped");
        assertTrue(ruler.shapedCount("P0c0") > 0, "the fixture counts: " + ruler.shaped);

        table.scrollBy(1000, 0);
        canvas.reset();
        scene.renderFrame(canvas);
        assertTrue(canvas.drew("P0c3") && canvas.drew("P0c4") && canvas.drew("C4"),
                "clamped at the content's end, the last columns are in view: " + canvas.texts());
        assertFalse(canvas.drew("P0c0"), "and the first is out");
        assertEquals(300 - 120 + padH, canvas.text("C4").x(), EPS,
                "the last column ends at the viewport's edge: 600 points of columns, 300 shown");
        assertEquals(1, ruler.shapedCount("P0c3"), "shaped the first time it entered");

        table.scrollBy(-1000, 0);
        canvas.reset();
        scene.renderFrame(canvas);
        assertEquals(padH, canvas.text("C0").x(), EPS, "back at the origin");
        table.scrollBy(1000, 0);
        canvas.reset();
        scene.renderFrame(canvas);
        assertEquals(1, ruler.shapedCount("P0c3"), "and held while it was out, not shaped again");
    }

    /** B3: Right past the viewport brings the focus column into view, as a consequence of the key. */
    @Test
    void theFocusCellBringsItsColumnIntoView() {
        Table<Person> table = new Table<>(fiveColumns());
        table.setRows(people(20));
        TablePaintCanvas canvas = new TablePaintCanvas(300, 200);
        Scene scene = scene(table, canvas);
        scene.requestFocus(table);
        table.setSelectedRow(0);
        List<Change> heard = new ArrayList<>();
        table.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.VALUE) {
                heard.add(change);
            }
        });
        for (int i = 0; i < 3; i++) {
            key(scene, Keys.RIGHT);
        }
        assertEquals(3, table.focusColumn());
        canvas.reset();
        scene.renderFrame(canvas);
        float padH = Theme.current().tokensFor(table).padH();
        assertEquals(300 - 120 + padH, canvas.text("C3").x(), EPS,
                "the column was scrolled in to end at the viewport's edge: " + canvas.texts());
        assertEquals(2, heard.size(), "the cut third column and the fourth each scrolled once: " + heard);
        for (Change change : heard) {
            assertEquals(Change.Origin.ADJUSTMENT, change.origin(), "a consequence of the key");
        }
        for (int i = 0; i < 3; i++) {
            key(scene, Keys.LEFT);
        }
        canvas.reset();
        scene.renderFrame(canvas);
        assertEquals(padH, canvas.text("C0").x(), EPS, "and Left back brings the first column home");
    }
}
