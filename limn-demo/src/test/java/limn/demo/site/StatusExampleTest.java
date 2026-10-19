package limn.demo.site;

import limn.components.Badge;
import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Dialog;
import limn.components.TextField;
import limn.components.table.Table;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the guides say their status, people, empty-table, popover and dialog examples do. */
class StatusExampleTest {

    private static Scene scene(Widget<?> root) {
        Scene scene = new Scene(root);
        scene.setTextRuler(TestRulers.FIXED);
        scene.layoutPass(600, 400);
        scene.renderFrame(new NoopCanvas(600, 400));
        return scene;
    }

    @Test
    void theStylesAreWhatTheGuideSays() {
        try (HeadlessUi ui = new HeadlessUi()) {
            List<Widget<?>> buttons = StatusExample.actions().children();
            assertSame(Button.Style.PRIMARY, ((Button) buttons.get(0)).style());
            assertSame(Button.Style.SECONDARY, ((Button) buttons.get(1)).style());
            assertSame(Button.Style.TERTIARY, ((Button) buttons.get(2)).style());
            assertTrue(((Button) buttons.get(3)).isDestructive());
        }
    }

    @Test
    void aCountPast99ShowsNinetyNinePlusAndTheGroupDrawsFour() {
        try (HeadlessUi ui = new HeadlessUi()) {
            Badge unread = StatusExample.unread(250);
            assertEquals("99+", unread.text());
            assertEquals("Overdue", StatusExample.statusBadge(StatusExample.Status.OVERDUE).text());
            Widget<?> row = StatusExample.approvers(List.of("Ana Lima", "Rui Costa", "Mei Chen",
                    "Omar Haddad", "Lena Vogel", "Kofi Mensah"));
            limn.components.AvatarGroup group = (limn.components.AvatarGroup) row.children().get(1);
            assertEquals(4, group.children().size(), "three faces and the count");
            assertEquals(3, group.hiddenCount());
        }
    }

    @Test
    void theTableSaysItHasNothingAndLoadsAgain() {
        try (HeadlessUi ui = new HeadlessUi()) {
            List<String> customers = new ArrayList<>();
            AtomicBoolean cleared = new AtomicBoolean();
            Table<String> table = StatusExample.orders(customers, () -> cleared.set(true));
            Scene scene = scene(new limn.scene.layout.SizedBox(400, 300, table));
            assertTrue(table.isShowingEmptyContent(), "no rows: the empty content shows");
            StatusExample.reload(table, customers, () -> List.of("Kestrel Outdoor", "Corvid Print"));
            assertTrue(table.isLoading());
            ui.pumpUntil(() -> !table.isLoading());
            scene.layoutPass(400, 300);
            assertEquals(2, table.rowCount());
            assertFalse(table.isShowingEmptyContent());
            assertFalse(cleared.get());
        }
    }

    @Test
    void theFilterButtonOpensThePopoverWithItsFieldFocused() {
        try (HeadlessUi ui = new HeadlessUi()) {
            TextField customer = new TextField();
            Button filter = StatusExample.filterButton(customer, new Checkbox(Checkbox.Variant.BOX, "Overdue only"));
            limn.scene.layout.Column root = new limn.scene.layout.Column();
            root.add(filter);
            limn.testing.HeadlessWindow window = new limn.testing.HeadlessBackend(ui.runtime())
                    .open("Orders", 600, 400);
            Scene scene = new Scene(root);
            scene.setTextRuler(TestRulers.FIXED);
            scene.bind(window);
            window.frame();
            scene.requestFocus(filter);
            drive(scene).press(Keys.SPACE);
            ui.pumpUntil(() -> customer.scene() != null);
            assertSame(customer, customer.scene().focusedWidget(),
                    "the popover's own window: the field inside has its keyboard");
            drive(scene).press(Keys.ESCAPE);
            assertSame(filter, scene.focusedWidget(), "Escape closed it; the focus never left the button");
        }
    }

    @Test
    void returnKeepsTheOrder() {
        try (HeadlessUi ui = new HeadlessUi()) {
            Scene scene = scene(new limn.components.Label("Order 1042"));
            AtomicReference<String> result = new AtomicReference<>();
            Dialog dialog = StatusExample.deleteOrder(1042).setDisplayMode(limn.components.DisplayMode.IN_SCENE);
            dialog.show(scene).thenAccept(result::set);
            scene.layoutPass(600, 400);
            drive(scene).press(Keys.ENTER);
            assertEquals("keep", result.get(), "Return gives the safe answer, never Delete");
        }
    }
}
