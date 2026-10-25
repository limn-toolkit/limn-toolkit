package limn.components.table;

import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A column changed after its table was made is taken by the table: its width at the next layout,
 * its footer at once. The setters only stored a field, so an application had to know to ask the
 * table for a layout or a refresh, and a footer changed without one showed the old text.
 */
class ColumnOnALiveTableTest {

    private HeadlessUi ui;

    private Column<String> name;
    private Column<String> size;
    private Table<String> table;
    private Scene scene;

    @BeforeEach
    void build() {
        ui = new HeadlessUi();
        name = Column.<String>text("Name", s -> s).width(120);
        size = Column.<String>text("Size", s -> s).width(80);
        table = new Table<>(List.of(name, size)).setRows(List.of("a", "b", "c"));
        scene = new Scene(table);
        scene.setTextRuler(TestRulers.FIXED);
    }

    @AfterEach
    void close() {
        ui.close();
    }

    private void layout() {
        scene.layoutPass(600, 300);
    }

    @Test
    void aWidthSetOnALiveTableIsTakenByItsNextLayout() {
        layout();
        assertEquals(120, table.widthOf(name), 0.5f);
        name.width(200);
        layout(); // a pass lays out only what asked for it
        assertEquals(200, table.widthOf(name), 0.5f);
    }

    @Test
    void aFooterSetOnALiveTableShowsAtOnce() {
        layout();
        size.footerCount();
        assertEquals("3", table.footerTextOf(size), "computed again without a refresh");
        size.footer("Total");
        assertEquals("Total", table.footerTextOf(size));
    }

    @Test
    void aColumnHiddenOnALiveTableLeavesItsNextLayout() {
        layout();
        size.visible(false);
        layout();
        assertEquals(0, table.widthOf(size), 0.5f);
    }
}
