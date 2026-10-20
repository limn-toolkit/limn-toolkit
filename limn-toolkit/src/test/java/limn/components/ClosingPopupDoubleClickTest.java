package limn.components;

import limn.components.date.DatePicker;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.AccessibleHarness;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import limn.testing.SceneDriver;
import limn.testing.StubWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A double click on an item of a list, a calendar or a menu drawn in the scene: the first click
 * picks it and the popup starts to fade, and the second lands on the item still drawn there. It is
 * the popup's, swallowed, and never the control's beneath the item, which a press beside the
 * fading card does reach. In a window of its own the second click goes to that window, as before.
 */
class ClosingPopupDoubleClickTest extends ComponentTestBase {

    private int deleted;
    private int ran;
    private AccessibleHarness harness;
    private Button delete;
    private TextField field;

    /** {@code above} over a "Delete all" button and a field, in a stub window that keeps popups in the scene. */
    private void bind(Widget<?> above) {
        bind(above, 0);
    }

    /** With the button {@code tall} points high when not 0, for a calendar whose grid starts lower. */
    private void bind(Widget<?> above, float tall) {
        bind(above, tall, false);
    }

    /** With the field right under {@code above} and the button after it, when {@code fieldFirst}. */
    private void bind(Widget<?> above, float tall, boolean fieldFirst) {
        delete = new Button("Delete all").onAction(() -> deleted++);
        field = new TextField().setText("hello world").setAccessibleName("Notes");
        Column root = new Column();
        root.add(above);
        if (fieldFirst) {
            root.add(new SizedBox(300, 30, field));
        }
        root.add(tall == 0 ? delete : new SizedBox(200, tall, delete));
        if (!fieldFirst) {
            root.add(new SizedBox(300, 30, field));
        }
        root.add(new SizedBox(300, 300, new Label("")));
        harness = new AccessibleHarness(runtime, root, new StubWindow(false));
        harness.scene.setTextRuler(RULER);
        harness.frame();
    }

    private static float[] centre(Widget<?> w) {
        var p = w.localToScene(w.width() / 2, w.height() / 2);
        return new float[] {p.x(), p.y()};
    }

    private void press(float[] at, int count) {
        SceneDriver d = drive(harness.scene);
        d.mouseMoved(at[0], at[1]);
        d.mouseButton(Keys.MOUSE_LEFT, true, 0, at[0], at[1], count);
        d.mouseButton(Keys.MOUSE_LEFT, false, 0, at[0], at[1], count);
        d.inputBatchEnded();
        harness.frame();
    }

    /** The second click, 60 ms after the first, on the same point: nothing beneath hears it. */
    private void theSecondClickIsSwallowed(float[] at, String what) {
        Widget<?> before = harness.scene.focusedWidget();
        harness.advanceTime(60, null);
        press(at, 2);
        assertEquals(0, deleted, what + ": the button beneath the item never ran");
        assertNotSame(delete, harness.scene.focusedWidget(), what + ": nor took the keyboard");
        assertEquals("", field.selectedText(), what + ": nor did a field beneath select a word");
        assertTrue(harness.scene.focusedWidget() == before || harness.scene.focusedWidget() == null
                || harness.scene.focusedWidget() != delete, what);
        harness.advanceTime(500, null);
        press(at, 1);
        assertEquals(1, deleted, what + ": once the popup is gone, the button beneath takes a click");
    }

    @Test
    void aDoubleClickOnAComboItemNeverReachesTheButtonBeneath() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            items.add("Item " + i);
        }
        ComboBox<String> combo = new ComboBox<>(items).setDisplayMode(DisplayMode.IN_SCENE);
        bind(combo);
        float[] at = centre(delete);
        drive(harness.scene).click(combo);
        harness.advanceTime(400, null);
        assertTrue(combo.isOpen(), "sanity: the list covers the button");
        press(at, 1);
        assertFalse(combo.isOpen(), "sanity: the first click picked an item");
        theSecondClickIsSwallowed(at, "combo box");
    }

    @Test
    void aDoubleClickOnAFieldBeneathAComboItemSelectsNoWord() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            items.add("Item " + i);
        }
        ComboBox<String> combo = new ComboBox<>(items).setDisplayMode(DisplayMode.IN_SCENE);
        bind(combo, 0, true);
        float[] at = {20, centre(field)[1]}; // under the list, which is narrower than the field
        drive(harness.scene).click(combo);
        harness.advanceTime(400, null);
        press(at, 1);
        assertFalse(combo.isOpen(), "sanity");
        assertNotSame(field, harness.scene.focusedWidget(), "sanity: the first click was on an item drawn over the field");
        harness.advanceTime(60, null);
        press(at, 2);
        assertEquals("", field.selectedText(), "the field beneath the item selected no word");
    }

    @Test
    void aDoubleClickOnAMenuItemNeverReachesTheButtonBeneath() {
        Button anchor = new Button("Open");
        bind(anchor);
        float[] at = centre(delete);
        Menu menu = new Menu();
        for (int i = 0; i < 8; i++) {
            menu.addItem("Action " + i, () -> ran++);
        }
        PopupMenu popup = new PopupMenu(menu).setDisplayMode(DisplayMode.IN_SCENE);
        popup.showAt(anchor, at[0] - 20, at[1] - 10);
        harness.advanceTime(400, null);
        press(at, 1);
        assertEquals(1, ran, "sanity: the first click ran an item");
        theSecondClickIsSwallowed(at, "menu");
        assertEquals(1, ran);
    }

    @Test
    void aDoubleClickOnADateNeverReachesTheButtonBeneath() {
        DatePicker picker = new DatePicker().setClock(java.time.Clock.fixed(
                java.time.Instant.parse("2026-09-09T12:00:00Z"), java.time.ZoneOffset.UTC))
                .setDisplayMode(DisplayMode.IN_SCENE);
        bind(picker, 200);
        picker.open();
        harness.advanceTime(400, null);
        assertTrue(picker.isOpen(), "sanity");
        float[] at = centre(delete);
        press(at, 1);
        assertFalse(picker.isOpen(), "sanity: the first click picked the day drawn over the button");
        theSecondClickIsSwallowed(at, "date picker");
    }

    @Test
    void inAWindowOfItsOwnTheSecondClickGoesToThatWindow() {
        long[] now = {TimeUnit.SECONDS.toNanos(1)};
        HeadlessBackend backend = new HeadlessBackend(runtime);
        HeadlessWindow owner = backend.open("Orders", 400, 400);
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            items.add("Item " + i);
        }
        ComboBox<String> combo = new ComboBox<>(items);
        delete = new Button("Delete all").onAction(() -> deleted++);
        Column root = new Column();
        root.add(combo);
        root.add(delete);
        Scene scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(owner);
        owner.frame();
        combo.open();
        runtime.drain();
        Scene list = null;
        for (HeadlessWindow w : backend.windows()) {
            if (w != owner && w.scene() != null) {
                list = w.scene();
                w.frame();
            }
        }
        assertNotNull(list, "sanity: the list is a window of its own");
        float x = list.root().width() / 2;
        float y = 10;
        drive(list).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y, 1);
        drive(list).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y, 1);
        drive(list).inputBatchEnded();
        assertFalse(combo.isOpen(), "sanity: picked");
        drive(list).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y, 2);
        drive(list).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y, 2);
        drive(list).inputBatchEnded();
        assertEquals(0, deleted, "the second click went to the list's window, not the owner's button");
    }
}
