package limn.components.table;

import limn.i18n.I18nString;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A column's setters are held to the UI thread as every widget's are. The table reads its columns
 * on the UI thread while it lays out, sorts and paints, so a width or a visibility written from
 * another thread is a race with that reading, and one nothing reported.
 */
class ColumnUiThreadTest {

    private HeadlessUi ui;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void uninstallRuntime() {
        ui.close();
    }

    /** Every public mutator of a column, by the name a failure reports it under. */
    private static Map<String, Consumer<Column<String>>> mutators() {
        Map<String, Consumer<Column<String>>> all = new LinkedHashMap<>();
        all.put("comparator", c -> c.comparator(Comparator.naturalOrder()));
        all.put("sortable", c -> c.sortable(false));
        all.put("align", c -> c.align(Column.Alignment.CENTER));
        all.put("width", c -> c.width(300));
        all.put("minWidth", c -> c.minWidth(80));
        all.put("weight", c -> c.weight(1));
        all.put("visible", c -> c.visible(false));
        all.put("footer(I18nString)", c -> c.footer(I18nString.literal("Total")));
        all.put("footer(String)", c -> c.footer("Total"));
        all.put("footer(Function)", c -> c.footer((List<String> rows) -> rows.size()));
        all.put("footerSum", Column::footerSum);
        all.put("footerAverage", Column::footerAverage);
        all.put("footerMin", Column::footerMin);
        all.put("footerMax", Column::footerMax);
        all.put("footerCount", Column::footerCount);
        all.put("resetWidth", Column::resetWidth);
        return all;
    }

    private static Column<String> column() {
        return Column.numeric("Length", String::length);
    }

    /** What {@code action} threw on a thread that is not the UI thread, or {@code null}. */
    private static Throwable offTheUiThread(Runnable action) throws InterruptedException {
        Throwable[] thrown = new Throwable[1];
        Thread other = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                thrown[0] = error;
            }
        }, "not-the-ui-thread");
        other.start();
        other.join();
        return thrown[0];
    }

    @Test
    void everySetterRefusesAThreadThatIsNotTheUiThreadAndChangesNothing() throws InterruptedException {
        for (Map.Entry<String, Consumer<Column<String>>> mutator : mutators().entrySet()) {
            Column<String> column = column();
            Throwable thrown = offTheUiThread(() -> mutator.getValue().accept(column));
            assertTrue(thrown instanceof IllegalStateException,
                    mutator.getKey() + " off the UI thread: " + thrown);
            assertTrue(thrown.getMessage().startsWith("Not on the Limn UI thread"),
                    mutator.getKey() + ": " + thrown.getMessage());
            assertEquals(120, column.width(), mutator.getKey() + " wrote a width before it checked");
            assertEquals(40, column.minWidth(), mutator.getKey() + " wrote a floor before it checked");
            assertEquals(0, column.weight(), mutator.getKey() + " wrote a weight before it checked");
            assertTrue(column.isVisible(), mutator.getKey() + " hid the column before it checked");
            assertTrue(column.isSortable(), mutator.getKey() + " changed sorting before it checked");
            assertEquals(Column.Alignment.END, column.alignment(), mutator.getKey());
            assertFalse(column.hasFooter(), mutator.getKey() + " set a footer before it checked");
            assertFalse(column.sortsByComparator(),
                    mutator.getKey() + " named a comparator before it checked");
        }
    }

    @Test
    void everySetterRunsOnTheUiThread() {
        for (Map.Entry<String, Consumer<Column<String>>> mutator : mutators().entrySet()) {
            mutator.getValue().accept(column());
        }
        Column<String> column = column().width(300).minWidth(80).weight(1).visible(false)
                .align(Column.Alignment.CENTER).sortable(false).footerSum();
        assertEquals(300, column.width());
        assertEquals(80, column.minWidth());
        assertEquals(1, column.weight());
        assertFalse(column.isVisible());
        assertEquals(Column.Alignment.CENTER, column.alignment());
        assertFalse(column.isSortable());
        assertTrue(column.hasFooter());
    }

    /**
     * With no UI running at all there is no UI thread to be on, and a setter says so rather than
     * letting the column be built somewhere the table's reading cannot be ordered against.
     */
    @Test
    void aSetterWithNoUiRunningSaysSo() {
        Column<String> column = column();
        ui.close();
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> column.width(300));
        assertTrue(thrown.getMessage().startsWith("No UiRuntime installed"), thrown.getMessage());
        assertEquals(120, column.width());
    }
}
