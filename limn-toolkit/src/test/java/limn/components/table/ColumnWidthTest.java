package limn.components.table;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A column's floor holds whatever width layout starts from, the one the user dragged included. */
class ColumnWidthTest {

    /**
     * A floor raised after a drag raised the preferred width only, and the width layout starts
     * from is the dragged one, so the column stayed narrower than its own minimum.
     */
    @Test
    void aFloorSetAfterADragHoldsTheDraggedWidth() {
        Column<String> column = Column.text("Name", s -> s);
        column.dragTo(50);
        column.minWidth(100);
        assertEquals(100, column.width());

        column.minWidth(40);
        assertEquals(100, column.width(), "lowering the floor does not move a width already set");
        column.dragTo(60);
        assertEquals(60, column.width());
    }
}
