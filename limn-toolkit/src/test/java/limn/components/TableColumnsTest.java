package limn.components;

import limn.components.table.Column;
import limn.components.table.SortOrder;
import limn.components.table.Table;
import limn.input.Keys;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A table's columns in its second phase (ADR 054 §4): moved by the application, by a drag of the
 * header and by Alt+Shift+Left and Right, the leading ones kept in place while the others
 * scroll, and the application told when the user rearranged them.
 */
class TableColumnsTest extends ComponentTestBase {

    record City(String name, int people, String state, String code) {
    }

    private Column<City> name;
    private Column<City> people;
    private Column<City> state;
    private Column<City> code;
    private Table<City> table;
    private Scene scene;
    private int told;
    private float sceneWidth;

    private void build(float width) {
        sceneWidth = width;
        name = Column.text("Name", City::name).width(100);
        people = Column.numeric("People", City::people).width(60);
        state = Column.text("State", City::state).width(100);
        code = Column.text("Code", City::code).width(100);
        table = new Table<>(List.of(name, people, state, code));
        List<City> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rows.add(new City("City " + i, i * 1000, "S" + i % 5, "C" + i));
        }
        table.setRows(rows);
        table.onColumnsChange(() -> told++);
        scene = new Scene(table);
        scene.setTextRuler(RULER);
        scene.layoutPass(width, 300);
        scene.requestFocus(table);
    }

    private float headerY() {
        return Theme.current().tokensFor(table).controlHeight() / 2;
    }

    private float firstRowY() {
        return Theme.current().tokensFor(table).controlHeight() * 1.5f;
    }

    private void press(float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y, 1);
        drive(scene).inputBatchEnded();
    }

    private void move(float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
    }

    private void release(float x, float y) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y, 1);
        drive(scene).inputBatchEnded();
    }

    private void layout() {
        scene.layoutPass(sceneWidth, 300);
    }

    @Test
    void theApplicationMovesAColumnAndTheKeyboardStaysOnItsColumn() {
        build(500);
        press(50, firstRowY());
        release(50, firstRowY());
        assertEquals(0, table.focusColumn(), "on Name");

        table.moveColumn(name, 2);
        layout();
        assertEquals(List.of(people, state, name, code), table.columns());
        assertEquals(2, table.focusColumn(), "the cursor went with Name");
        assertEquals(0, told, "a caller's write tells no handler");
        assertEquals(100, table.widthOf(name), 0.01f);

        assertThrows(IndexOutOfBoundsException.class, () -> table.moveColumn(name, 4));
        assertThrows(IllegalArgumentException.class,
                () -> table.moveColumn(Column.text("Other", City::name), 0));
        assertThrows(UnsupportedOperationException.class, () -> table.columns().remove(0));
    }

    @Test
    void aPressOnAHeaderThatTravelsMovesTheColumnAndOneThatDoesNotSortsIt() {
        build(500);
        press(50, headerY());
        release(50, headerY());
        assertSame(name, table.sortColumn(), "a click sorts, on the release");
        assertEquals(SortOrder.ASCENDING, table.sortOrder());

        press(50, headerY());
        move(120, headerY());
        move(230, headerY() + 200);      // far below the header: only where along it counts
        release(230, headerY() + 200);
        layout();
        assertEquals(List.of(people, state, name, code), table.columns(),
                "Name's middle passed People's and State's: it stands after them");
        assertEquals(1, told);
        assertEquals(SortOrder.ASCENDING, table.sortOrder(), "the move did not sort again");

        press(30, headerY());            // People, now first
        move(20, headerY());             // held against the edge of its group: it has nowhere to go
        release(20, headerY());
        assertEquals(List.of(people, state, name, code), table.columns(), "dropped where it was");
        assertEquals(1, told);

        press(30, headerY());
        move(200, headerY());
        drive(scene).press(Keys.ESCAPE);
        release(200, headerY());
        layout();
        assertEquals(List.of(people, state, name, code), table.columns(), "Escape puts it back");
        assertEquals(1, told);
        assertSame(name, table.sortColumn(), "and the release after it is no click");
    }

    @Test
    void altShiftAndAnArrowMoveTheColumnTheKeyboardIsOn() {
        build(500);
        press(50, firstRowY());
        release(50, firstRowY());
        drive(scene).press(Keys.RIGHT, Keys.MOD_ALT | Keys.MOD_SHIFT);
        layout();
        assertEquals(List.of(people, name, state, code), table.columns());
        assertEquals(1, table.focusColumn());
        assertEquals(1, told);

        drive(scene).press(Keys.LEFT, Keys.MOD_ALT | Keys.MOD_SHIFT);
        drive(scene).press(Keys.LEFT, Keys.MOD_ALT | Keys.MOD_SHIFT);
        layout();
        assertEquals(List.of(name, people, state, code), table.columns(), "back, and no further than the first");
        assertEquals(2, told);

        table.setColumnReorderingAllowed(false);
        drive(scene).press(Keys.RIGHT, Keys.MOD_ALT | Keys.MOD_SHIFT);
        press(50, headerY());
        move(230, headerY());
        release(230, headerY());
        assertEquals(List.of(name, people, state, code), table.columns(), "not allowed: neither way");
    }

    @Test
    void theLeadingColumnsStayWhileTheOthersScrollUnderThem() {
        build(220);                      // 360 points of columns in 220
        table.setFrozenColumns(1);
        layout();
        table.scrollBy(100, 0);
        layout();
        // Scrolled by 100: People would be at 0 to 60, State at 60 to 160, were Name not kept.
        press(50, headerY());
        release(50, headerY());
        assertSame(name, table.sortColumn(), "Name is still where it was");
        press(150, headerY());
        release(150, headerY());
        assertSame(state, table.sortColumn(), "and State has scrolled to beside it");

        table.setFrozenColumns(0);
        layout();
        press(50, headerY());
        release(50, headerY());
        assertSame(people, table.sortColumn(), "with none kept, People is under the pointer there");
    }

    @Test
    void theUserMovesAColumnOnlyWithinItsGroup() {
        build(220);
        table.setFrozenColumns(1);
        layout();
        press(150, firstRowY());         // People, the first of the scrolling ones
        release(150, firstRowY());
        assertEquals(1, table.focusColumn());
        drive(scene).press(Keys.LEFT, Keys.MOD_ALT | Keys.MOD_SHIFT);
        layout();
        assertEquals(List.of(name, people, state, code), table.columns(), "not into the kept ones");
        assertEquals(0, told);
        assertNull(table.sortColumn());

        table.moveColumn(people, 0);     // the application may
        layout();
        assertEquals(List.of(people, name, state, code), table.columns());
    }
}
