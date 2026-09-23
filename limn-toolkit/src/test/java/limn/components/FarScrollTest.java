package limn.components;

import limn.components.table.Column;
import limn.components.table.Table;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A far {@code scrollBy} lands by estimate, as a drag of the bar does (PF-8): it used to move the
 * anchor and leave the next layout to walk and measure every row on the way, 1.35-1.95 s for
 * 100,000 rows. Measured here by which rows are asked for, since that is the walk.
 */
class FarScrollTest extends ComponentTestBase {

    private static final int ROWS = 100_000;

    @Test
    void aTableScrolledToItsEndAsksOnlyForTheRowsItShows() {
        Set<Integer> asked = new TreeSet<>();
        List<Integer> rows = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            rows.add(i);
        }
        Table<Integer> table = new Table<>(List.of(Column.text("Row", (Integer i) -> {
            asked.add(i);
            return "row " + i;
        })));
        table.setRows(rows);
        Scene scene = new Scene(table);
        scene.setTextRuler(RULER);
        NoopCanvas canvas = new NoopCanvas(400, 300);
        scene.layoutPass(400, 300);
        scene.renderFrame(canvas);
        asked.clear();

        table.scrollBy(0, 1e9f);
        scene.renderFrame(canvas);

        assertTrue(asked.size() < 200, "rows asked for on the way: " + asked.size());
        assertTrue(asked.contains(ROWS - 1), "the last row is shown: " + last(asked));
    }

    @Test
    void aListScrolledToItsEndBuildsOnlyTheRowsItShows() {
        Set<Integer> asked = new TreeSet<>();
        ListView list = new ListView(new ListView.Adapter() {
            @Override
            public int rowCount() {
                return ROWS;
            }

            @Override
            public Widget rowAt(int index) {
                asked.add(index);
                return new Label("row " + index);
            }

            @Override
            public void recycle(Widget widget) {
            }
        });
        Scene scene = new Scene(list);
        scene.setTextRuler(RULER);
        NoopCanvas canvas = new NoopCanvas(400, 300);
        scene.layoutPass(400, 300);
        scene.renderFrame(canvas);
        asked.clear();

        list.scrollBy(1e9f);
        scene.renderFrame(canvas);

        assertTrue(asked.size() < 200, "rows built on the way: " + asked.size());
        assertTrue(asked.contains(ROWS - 1), "the last row is shown: " + last(asked));
    }

    private static Object last(Set<Integer> asked) {
        return asked.isEmpty() ? "none" : ((TreeSet<Integer>) asked).last();
    }
}
