package limn.components;

import limn.components.table.Column;
import limn.components.table.Table;
import limn.input.Keys;
import limn.scene.Scene;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An arrow key deep in a table with no {@code rowKey} reads the rows above it once, not once per
 * key (PF-4). Measured before the fix at 251 µs a key near the end of 100,000 rows and 2.2 ms at
 * 1,000,000, a pass over every row above each time; equal records must still be told apart.
 */
class TableArrowCostTest extends ComponentTestBase {

    /** A row whose comparisons are counted: the price of telling equal records apart. */
    static final class Counted {
        static final AtomicLong COMPARISONS = new AtomicLong();
        final String name;

        Counted(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            COMPARISONS.incrementAndGet();
            return other instanceof Counted counted && counted.name.equals(name);
        }

        @Override
        public int hashCode() {
            COMPARISONS.incrementAndGet();
            return name.hashCode();
        }
    }

    @Test
    void anArrowNearTheEndComparesAHandfulOfRowsOnceTheFirstHasReadThem() {
        int n = 20_000;
        List<Counted> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(new Counted("row " + (i % 500)));
        }
        Table<Counted> table = new Table<>(List.of(Column.text("Name", (Counted c) -> c.name)));
        table.setRows(rows);
        Scene scene = new Scene(table);
        scene.setTextRuler(RULER);
        NoopCanvas canvas = new NoopCanvas(400, 300);
        scene.layoutPass(400, 300);
        scene.renderFrame(canvas);
        scene.requestFocus(table);
        table.setSelectedRow(n - 200);
        drive(scene).press(Keys.DOWN); // the first reads the rows above once
        scene.renderFrame(canvas);

        Counted.COMPARISONS.set(0);
        for (int k = 0; k < 20; k++) {
            drive(scene).press(Keys.DOWN);
            scene.renderFrame(canvas);
        }
        long perKey = Counted.COMPARISONS.get() / 20;
        assertTrue(perKey < 500, "comparisons per key, where a pass over the rows above is ~" + n
                + ": " + perKey);
        assertEquals(n - 200 + 21, table.selectedRow() + 0, "the arrows still moved");
    }

    @Test
    void equalRecordsAreStillToldApartAcrossARefresh() {
        List<Counted> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(new Counted(i % 3 == 0 ? "same" : "row " + i));
        }
        Table<Counted> table = new Table<>(List.of(Column.text("Name", (Counted c) -> c.name)));
        table.setRows(rows);
        Scene scene = new Scene(table);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
        scene.renderFrame(new NoopCanvas(400, 300));
        scene.requestFocus(table);
        table.setSelectedRow(5);
        drive(scene).press(Keys.DOWN); // row 6, the third "same"
        assertEquals(6, table.selectedRow());

        // With no rowKey a record is "the third row equal to this one". The first "same" leaves,
        // and the third is now the one at 8, which is where the selection goes, as it did when
        // every key counted the rows above it again.
        rows.remove(0);
        table.refresh();
        assertEquals(8, table.selectedRow(), "followed as the same occurrence of an equal record");
    }
}
