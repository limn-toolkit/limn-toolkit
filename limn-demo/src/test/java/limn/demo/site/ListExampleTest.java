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
}
