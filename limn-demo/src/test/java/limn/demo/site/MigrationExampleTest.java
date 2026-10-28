package limn.demo.site;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.ComboBox;
import limn.components.Label;
import limn.components.ListView;
import limn.components.TextField;
import limn.components.table.Table;
import limn.demo.site.MigrationTableExample.Order;
import limn.scene.Scene;
import limn.scene.layout.Padding;
import limn.scene.Insets;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the "coming from" pages say each Limn half does. */
class MigrationExampleTest {

    private HeadlessUi ui;

    @BeforeEach
    void install() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void uninstall() {
        ui.close();
    }

    private static Scene sceneOf(limn.scene.Widget<?> root) {
        Scene scene = new Scene(root);
        scene.setTextRuler(TestRulers.FIXED);
        scene.layoutPass(480, 320);
        return scene;
    }

    @Test
    void theFormSavesWhatTheUserTypedAndTheHandlerEnablesTheButton() {
        TextField name = new TextField();
        TextField email = new TextField();
        Button save = new Button("Save");
        Checkbox notify = new Checkbox("Notify me");
        ComboBox<String> plan = new ComboBox<>(List.of("Personal", "Team"));
        Label status = new Label("");
        MigrationExample.wire(save, name, notify, plan, status);
        Scene scene = sceneOf(MigrationExample.contactForm(name, email, save));

        name.setText("   ");                    // the code's write: the handler does not run
        assertTrue(save.isEnabled(), "a handler answers the user, not setText");

        drive(scene).click(name).type("Ada");
        scene.layoutPass(480, 320);
        assertTrue(save.isEnabled());
        drive(scene).click(save);
        assertEquals("Saved    Ada", status.text());
    }

    @Test
    void theSearchLandsOnTheUiThreadAndAnOlderOneIsWithdrawn() {
        ListView<String> results = new ListView<>(item -> new Label(item));
        Label status = new Label("");
        MigrationExample.Search search = new MigrationExample.Search();
        search.find("pens", query -> List.of(query + " 1", query + " 2"), results, status);
        assertEquals("Searching…", status.text());
        ui.pumpUntil(() -> status.text().endsWith("found"), TimeUnit.SECONDS.toNanos(10));
        assertEquals("2 found", status.text());
        assertEquals(List.of("pens 1", "pens 2"), results.items());
    }

    @Test
    void theClockTicksAndStops() {
        Label time = new Label("");
        MigrationExample.Clock clock = new MigrationExample.Clock();
        clock.start(time);
        assertFalse(time.text().isEmpty());
        clock.stop();
    }

    @Test
    void theMeterPaintsItsLevelInsideItsOwnBox() {
        MigrationExample.Meter meter = new MigrationExample.Meter().setLevel(1.5f);
        limn.scene.layout.Column column = new limn.scene.layout.Column(); // loose: the meter's own size
        column.add(meter);
        Scene scene = sceneOf(new Padding(Insets.all(10), column));
        assertEquals(160f, meter.width());
        assertEquals(12f, meter.height());
        scene.renderFrame(new NoopCanvas(480, 320));
    }

    @Test
    void theTableReadsTheApplicationsOwnObjects() {
        Label status = new Label("");
        List<Order> rows = List.of(new Order("#1089", "Kestrel Outdoor", 4099.75),
                new Order("#1088", "Corvid Print", 3122.50));
        Table<Order> table = MigrationTableExample.orders(rows, status);
        sceneOf(table);
        assertEquals(2, table.rows().size());
        assertEquals(3, table.columns().size());
    }
}
