package limn.components.table;

import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A column's floor holds whatever width layout starts from, the one the user dragged included. */
class ColumnWidthTest {

    /** A column's setters run on the UI thread, which this binds to the test's own. */
    private HeadlessUi ui;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void uninstallRuntime() {
        ui.close();
    }

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
