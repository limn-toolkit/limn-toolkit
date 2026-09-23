package limn.components;

import limn.scene.Scene;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A widget's id, found by {@link Scene#find} and clicked by the driver. */
class WidgetIdTest extends ComponentTestBase {

    @Test
    void theFirstMatchInTreeOrderIsFound() {
        Column inner = new Column();
        Button deep = new Button("deep").setId("save");
        inner.add(deep);
        Column root = new Column();
        root.add(inner);
        Button later = new Button("later").setId("save");
        root.add(later);
        Scene scene = new Scene(root);

        assertSame(deep, scene.find("save"));
        assertNull(scene.find("missing"));
    }

    @Test
    void aHiddenWidgetAndAnOverlayAreSearched() {
        Column root = new Column();
        Label hidden = new Label("hidden").setId("note");
        hidden.setVisible(false);
        root.add(hidden);
        Scene scene = new Scene(root);
        assertSame(hidden, scene.find("note"));

        Column overlay = new Column();
        Button ok = new Button("OK").setId("ok");
        overlay.add(ok);
        scene.pushOverlay(overlay);
        assertSame(ok, scene.find("ok"));
    }

    @Test
    void theTypedLookupCastsOrSaysWhy() {
        Column root = new Column();
        root.add(new Button("OK").setId("ok"));
        Scene scene = new Scene(root);

        assertEquals("OK", scene.find("ok", Button.class).text());
        assertNull(scene.find("missing", Button.class));
        assertThrows(ClassCastException.class, () -> scene.find("ok", Label.class));
    }

    @Test
    void theDriverClicksById() {
        AtomicInteger clicks = new AtomicInteger();
        Column root = new Column();
        root.add(new Button("Save").setId("save").onAction(clicks::incrementAndGet));
        Scene scene = new Scene(root);
        scene.layoutPass(200, 100);

        drive(scene).click("save");
        assertEquals(1, clicks.get());
        assertThrows(IllegalArgumentException.class, () -> drive(scene).click("missing"));
    }
}
