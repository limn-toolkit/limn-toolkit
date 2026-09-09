package limn.components;

import limn.components.table.Column;
import limn.components.table.SortOrder;
import limn.components.table.Table;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        scene.mouseMoved(x, y);
        scene.inputBatchEnded();
        scene.mouseButton(Keys.MOUSE_LEFT, true, modifiers, x, y);
        scene.inputBatchEnded();
        scene.mouseButton(Keys.MOUSE_LEFT, false, modifiers, x, y);
        scene.inputBatchEnded();
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

    @Test
    void theKeyboardMovesTheFocusCellAndTheSelectionWithIt() {
        Table<Person> table = new Table<>(List.of(nameColumn(), ageColumn()));
        table.setRows(people(50));
        table.setSelectionMode(Table.SelectionMode.MULTI);
        AtomicInteger selects = new AtomicInteger();
        table.onSelect(selects::incrementAndGet);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        scene.requestFocus(table);
        scene.keyEvent(Keys.DOWN, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(0, table.selectedRow(), "the first Down lands on the first row");
        assertEquals(0, table.focusRow());
        scene.keyEvent(Keys.DOWN, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(1, table.selectedRow());
        scene.keyEvent(Keys.DOWN, true, false, Keys.MOD_SHIFT);
        scene.inputBatchEnded();
        assertArrayEquals(new int[] {1, 2}, table.selectedRows(), "Shift extends the range");
        assertEquals(2, table.focusRow());
        scene.keyEvent(Keys.RIGHT, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(1, table.focusColumn());
        scene.keyEvent(Keys.RIGHT, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(1, table.focusColumn(), "no column past the last");
        scene.keyEvent(Keys.END, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(49, table.selectedRow());
        scene.renderFrame(canvas);
        assertTrue(table.firstVisibleRow() > 30, "End scrolled the last row into view");
        scene.keyEvent(Keys.A, true, false, Accelerator.commandModifier());
        scene.inputBatchEnded();
        assertEquals(50, table.selectedRows().length, "select all");
        assertTrue(selects.get() >= 5, "every change fired once: " + selects);
    }

    @Test
    void multiSelectionFollowsThePlatformsGrammar() {
        Table<Person> table = new Table<>(List.of(nameColumn(), ageColumn()));
        table.setRows(people(20));
        table.setSelectionMode(Table.SelectionMode.MULTI);
        FakeCanvas canvas = new FakeCanvas(300, 300);
        Scene scene = scene(table, canvas);
        click(scene, 30, rowCenterY(table, 1), 0);
        assertArrayEquals(new int[] {1}, table.selectedRows());
        click(scene, 30, rowCenterY(table, 3), Keys.MOD_SHIFT);
        assertArrayEquals(new int[] {1, 2, 3}, table.selectedRows(), "Shift selects the range");
        click(scene, 30, rowCenterY(table, 5), Accelerator.commandModifier());
        assertArrayEquals(new int[] {1, 2, 3, 5}, table.selectedRows(), "command toggles one on");
        click(scene, 30, rowCenterY(table, 2), Accelerator.commandModifier());
        assertArrayEquals(new int[] {1, 3, 5}, table.selectedRows(), "and off");
        click(scene, 30, rowCenterY(table, 0), 0);
        assertArrayEquals(new int[] {0}, table.selectedRows(), "a plain click selects one alone");
        table.setSelectionMode(Table.SelectionMode.NONE);
        assertEquals(-1, table.selectedRow());
        click(scene, 30, rowCenterY(table, 2), 0);
        assertEquals(0, table.selectedRows().length, "nothing selects in NONE");
        assertEquals(2, table.focusRow(), "but the focus cell still moves");
    }

    @Test
    void enterAndADoubleClickActivateTheLeadRow() {
        Table<Person> table = new Table<>(List.of(nameColumn(), ageColumn()));
        table.setRows(people(5));
        AtomicInteger activated = new AtomicInteger(-1);
        table.onActivate(activated::set);
        FakeCanvas canvas = new FakeCanvas(300, 300);
        Scene scene = scene(table, canvas);
        scene.requestFocus(table);
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(-1, activated.get(), "nothing selected, nothing activated");
        table.setSelectedRow(3);
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(3, activated.get());
        activated.set(-1);
        float y = rowCenterY(table, 1);
        click(scene, 30, y, 0);
        click(scene, 30, y, 0);
        assertEquals(1, activated.get(), "two presses on one row are a double click");
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
        scene.mouseMoved(240, y);
        scene.inputBatchEnded();
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 240, y);
        scene.inputBatchEnded();
        scene.mouseMoved(200, y);
        scene.inputBatchEnded();
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 200, y);
        scene.inputBatchEnded();
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
    void refreshDropsASelectionTheListNoLongerHas() {
        List<Person> rows = new ArrayList<>(people(4));
        Table<Person> table = new Table<>(List.of(nameColumn(), ageColumn()));
        table.setRows(rows);
        AtomicInteger selects = new AtomicInteger();
        table.onSelect(selects::incrementAndGet);
        FakeCanvas canvas = new FakeCanvas(300, 200);
        Scene scene = scene(table, canvas);
        table.setSelectedRow(3);
        int before = selects.get();
        rows.remove(3);
        table.refresh();
        scene.renderFrame(canvas);
        assertEquals(-1, table.selectedRow(), "the deleted row is not selected");
        assertEquals(before + 1, selects.get(), "and the listener heard it go");
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
        List<Widget> first = new ArrayList<>(table.children());
        table.scrollBy(0, 5000);
        scene.renderFrame(canvas);
        for (Widget w : table.children()) {
            if (w instanceof Button) {
                assertTrue(!first.contains(w), "a scrolled-away row released its widget");
            }
        }
        assertTrue(table.firstVisibleRow() > 50);
    }
}
