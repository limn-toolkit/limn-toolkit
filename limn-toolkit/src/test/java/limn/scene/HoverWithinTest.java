package limn.scene;

import limn.scene.internal.SceneAccess;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hover within a subtree ({@code SceneAccess.observeHoverWithin}), which a pressable card's
 * area reads (ADR 073 §2.6): the scene sends a hover's enter and exit to the deepest widget alone,
 * and a surface holding labels and pictures is hovered while the pointer is over any of them.
 * Told once as the pointer enters the subtree and once as it leaves, and not as it moves between
 * the subtree's own widgets; told it has left when the widget under the pointer goes, the subtree
 * with it or not, since the scene then holds no hovered widget until the pointer moves.
 */
class HoverWithinTest extends SceneTestBase {

    /** A box that holds its children where they are put: two side by side, and itself between. */
    private static final class Area extends Widget<Area> {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(200, 100);
        }

        @Override
        protected void onLayout() {
            children().get(0).layoutBox(10, 10, 60, 60);
            children().get(1).layoutBox(120, 10, 60, 60);
        }

        void hold(Widget<?> child) {
            add(child);
        }

        void drop(Widget<?> child) {
            remove(child);
        }
    }

    private final List<Boolean> told = new ArrayList<>();
    private Scene scene;
    private Area area;

    private void bindArea() {
        area = new Area();
        area.hold(new FixedBox(60, 60));
        area.hold(new FixedBox(60, 60));
        Column root = new Column();
        root.add(area);
        root.add(new FixedBox(200, 100)); // beside it, below
        scene = new Scene(root);
        scene.bind(new RecordingWindow());
        scene.layoutPass(300, 300);
        SceneAccess.observeHoverWithin(area, told::add);
    }

    private void move(float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
    }

    @Test
    void movingBetweenTheSubtreesOwnWidgetsTellsNothingAndLeavingItTellsOnce() {
        bindArea();
        move(40, 40);     // a child
        move(95, 40);     // the area itself, between its children
        move(150, 40);    // the other child
        assertEquals(List.of(true), told, "entered once, wherever inside it the pointer went");
        move(100, 150);   // the box below, outside
        move(100, 160);
        assertEquals(List.of(true, false), told, "and left once");
        move(40, 40);
        assertEquals(List.of(true, false, true), told);
    }

    @Test
    void aWidgetRemovedUnderThePointerTakesTheHoverAndTheSubtreeHearsItLeft() {
        bindArea();
        move(40, 40);
        Widget<?> under = area.children().get(0);
        area.drop(under);
        // The scene holds no hovered widget until the pointer moves again: the subtree is told
        // so, and told it is entered again when the pointer, still over it, next moves.
        assertEquals(List.of(true, false), told);
        move(41, 40);
        assertEquals(List.of(true, false, true), told);
        ((Column) area.parent()).remove(area);
        assertEquals(List.of(true, false, true, false), told, "gone with the pointer in it: it has left");
    }

    @Test
    void aSubtreeGoingInertWithThePointerInItHasLeft() {
        bindArea();
        move(40, 40);
        SceneAccess.setInert(area, true);
        assertEquals(List.of(true, false), told);
    }

    @Test
    void anObserverTakenAwayIsToldNothing() {
        bindArea();
        SceneAccess.observeHoverWithin(area, null);
        move(40, 40);
        move(100, 150);
        assertEquals(List.of(), told);
    }
}
