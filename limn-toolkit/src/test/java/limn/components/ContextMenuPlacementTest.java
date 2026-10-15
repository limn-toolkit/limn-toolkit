package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.input.Keys;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Row;
import limn.scene.layout.SizedBox;
import limn.testing.StubWindow;
import org.junit.jupiter.api.Test;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A menu asked for with the pointer opens at the pointer, wherever the widget that asked sits in
 * the scene. A mouse event's point is in scene coordinates; the region and the text fields read it
 * as their own, so a menu opened away from the scene's origin landed as far again from the pointer
 * as the widget was from the origin.
 */
class ContextMenuPlacementTest extends AccessibleComponentTestBase {

    private static final float LEFT = 60;
    private static final float TOP = 50;
    /** Room for the column of rows the menu draws around the point it was asked for. */
    private static final float NEAR = 16;

    /**
     * {@code content} set {@link #LEFT} in and {@link #TOP} down, on a 400 by 300 window that cannot
     * place one: small offsets and a pointer near the content's corner, so a menu put where it
     * belongs or where the defect put it is clear of the scene's edges either way.
     */
    private void place(Widget<?> content) {
        Row row = new Row();
        row.add(new SizedBox(LEFT, 10));
        row.add(content);
        Column root = new Column();
        root.add(new SizedBox(10, TOP));
        root.add(row);
        bind(root, new StubWindow(false));
        scene.setTextRuler(RULER);
        frame();
    }

    private void rightPressAt(float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).mouseButton(Keys.MOUSE_RIGHT, true, 0, x, y);
        drive(scene).inputBatchEnded();
        frame();
    }

    private void assertOpensAt(float x, float y) {
        AccessibleNode menu = node(Accessible.Role.MENU);
        assertEquals(x, menu.x(), NEAR, "the menu's corner is at the pointer" + describe(tree()));
        assertEquals(y, menu.y(), NEAR, "the menu's corner is at the pointer" + describe(tree()));
    }

    @Test
    void anAttachedRegionsMenuOpensAtThePointer() {
        Widget<?> region = ContextMenus.attach(new SizedBox(200, 120),
                () -> new Menu().addItem("Open", () -> { }).addItem("Rename", () -> { }));
        place(region);
        float x = LEFT + 20;
        float y = TOP + 20;
        rightPressAt(x, y);
        assertOpensAt(x, y);
    }

    @Test
    void aTextFieldsMenuOpensAtThePointer() {
        TextField field = new TextField().setText("Halcyon Foods");
        place(new SizedBox(200, SizedBox.UNSET, field));
        float x = LEFT + 20;
        float y = TOP + field.height() / 2;
        rightPressAt(x, y);
        assertOpensAt(x, y);
    }

    @Test
    void aTextAreasMenuOpensAtThePointer() {
        TextArea area = new TextArea();
        area.setText("Prefers deliveries before 10 a.m.");
        place(new SizedBox(200, 80, area));
        float x = LEFT + 20;
        float y = TOP + 20;
        rightPressAt(x, y);
        assertOpensAt(x, y);
    }
}
