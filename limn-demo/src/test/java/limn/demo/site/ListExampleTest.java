package limn.demo.site;

import limn.components.Label;
import limn.components.ListView;
import limn.input.Keys;
import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the lists guide says its example does: the detail follows the selection; Enter opens. */
class ListExampleTest {

    @Test
    void theDetailFollowsTheSelectionAndEnterOpensTheRecord() {
        try (HeadlessUi ui = new HeadlessUi()) {
            List<ListExample.Person> people = List.of(new ListExample.Person("Ada", "ada@example.org"),
                    new ListExample.Person("Grace", "grace@example.org"));
            Label detail = new Label("");
            List<ListExample.Person> opened = new ArrayList<>();
            ListView<ListExample.Person> list = ListExample.people(people, detail, opened::add);
            Scene scene = new Scene(list);
            scene.setTextRuler(TestRulers.FIXED);
            scene.layoutPass(300, 200);
            scene.renderFrame(new NoopCanvas(300, 200));
            scene.requestFocus(list);

            drive(scene).press(Keys.DOWN).press(Keys.DOWN);
            assertEquals("grace@example.org", detail.text());
            drive(scene).press(Keys.ENTER);
            assertEquals(List.of(people.get(1)), opened);

            list.setSelectedIndex(0);
            assertEquals("ada@example.org", detail.text(), "a selection from code is watched too");
        }
    }

    /**
     * The guide's pooled row with a button: after a scroll has recycled every row widget, the
     * button in the row showing Person 80 removes Person 80, because the row keeps the item it
     * shows and the action, registered once, reads it.
     */
    @Test
    void aRecycledRowsButtonActsOnTheItemTheRowShowsNow() {
        try (HeadlessUi ui = new HeadlessUi()) {
            List<ListExample.Person> people = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                people.add(new ListExample.Person("Person " + i, "p" + i + "@example.org"));
            }
            ListView<ListExample.Person> list = ListExample.removable(people);
            Scene scene = new Scene(list);
            scene.setTextRuler(TestRulers.FIXED);
            NoopCanvas canvas = new NoopCanvas(300, 200);
            scene.layoutPass(300, 200);
            scene.renderFrame(canvas);
            list.setSelectedIndex(80); // reveals row 80, recycling the widgets of the rows above
            scene.renderFrame(canvas);
            scene.renderFrame(canvas);

            limn.components.Button remove = buttonInRowShowing(list, "Person 80");
            float x = remove.localToSceneX() + remove.width() / 2;
            float y = remove.localToSceneY() + remove.height() / 2;
            drive(scene).mouseMoved(x, y);
            drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
            drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
            drive(scene).inputBatchEnded();

            assertEquals(99, people.size());
            assertEquals(-1, people.stream().map(ListExample.Person::name).toList().indexOf("Person 80"),
                    "Person 80 was removed, not the person its widget was first made for");
            assertEquals("Person 81", people.get(80).name());
        }
    }

    private static limn.components.Button buttonInRowShowing(limn.scene.Widget<?> root, String name) {
        java.util.ArrayDeque<limn.scene.Widget<?>> rows = new java.util.ArrayDeque<>(root.children());
        while (!rows.isEmpty()) {
            limn.scene.Widget<?> row = rows.poll();
            limn.components.Button button = null;
            boolean shows = false;
            java.util.ArrayDeque<limn.scene.Widget<?>> inside = new java.util.ArrayDeque<>(List.of(row));
            while (!inside.isEmpty()) {
                limn.scene.Widget<?> at = inside.poll();
                if (at instanceof Label label && name.equals(label.text())) {
                    shows = true;
                }
                if (at instanceof limn.components.Button b) {
                    button = b;
                }
                inside.addAll(at.children());
            }
            if (shows && button != null) {
                return button;
            }
        }
        throw new AssertionError("no realized row shows " + name);
    }
}
