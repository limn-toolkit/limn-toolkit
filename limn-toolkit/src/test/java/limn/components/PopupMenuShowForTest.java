package limn.components;

import limn.graphics.Rect;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Padding;
import limn.testing.StubWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PopupMenu#showFor(limn.scene.Widget)} opens at the widget wherever the widget is: the
 * anchor rectangle is the widget's box mapped to the scene, which a caller handing
 * {@code showAnchored} the widget's own {@code 0, 0} got wrong, and the menu opened at the
 * window's edge. Drawn in the scene (a window that cannot be positioned), where the column's
 * place is the scene's.
 */
class PopupMenuShowForTest extends ComponentTestBase {

    @Test
    void theMenuOpensAtTheWidgetAndNotAtTheSceneOrigin() {
        Button button = new Button("Columns");
        Scene scene = new Scene(new Padding(Insets.all(120), button));
        scene.setTextRuler(RULER);
        scene.bind(new StubWindow(false));
        scene.layoutPass(600, 400);
        Rect box = button.localToSceneBounds(0, 0, button.width(), button.height());
        assertTrue(box.x() >= 100 && box.y() >= 100, "the button is away from the origin: " + box);

        PopupMenu popup = new PopupMenu(new Menu().addItem("One", () -> { }).addItem("Two", () -> { }));
        popup.showFor(button);
        scene.layoutPass(600, 400);

        assertTrue(popup.isOpen());
        assertTrue(popup.isInSceneForTest());
        float[] column = popup.columnRectForTest(0);
        assertEquals(box.x(), column[0], 1f, "the menu starts where the button starts");
        assertTrue(column[1] >= box.y() + box.height() - 1f,
                "the menu drops from under the button: column top " + column[1] + ", button " + box);
    }
}
