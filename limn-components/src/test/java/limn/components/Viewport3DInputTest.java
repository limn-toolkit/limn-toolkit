package limn.components;

import limn.input.Keys;
import limn.render3d.CameraController;
import limn.scene.Scene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Viewport3D camera gestures are LEFT-button only: a right/middle-button
 * press never arms a gesture, so its DRAG/RELEASE can neither move the camera
 * with stale coordinates nor fire a picking click.
 */
class Viewport3DInputTest extends ComponentTestBase {

    private Viewport3D viewport;
    private Scene scene;
    private final List<float[]> drags = new ArrayList<>();

    @BeforeEach
    void build() {
        viewport = new Viewport3D();
        viewport.setController(new CameraController() {
            @Override
            public void drag(float dx, float dy) {
                drags.add(new float[]{dx, dy});
            }

            @Override
            public void zoom(float amount) {
            }
        });
        scene = new Scene(viewport);
        scene.setTextRuler(RULER);
        scene.layoutPass(200, 200);
    }

    private void drag(int button, float fromX, float fromY, float toX, float toY) {
        scene.mouseButton(button, true, 0, fromX, fromY);
        scene.mouseMoved(toX, toY);
        scene.mouseButton(button, false, 0, toX, toY);
        scene.inputBatchEnded();
    }

    @Test
    void leftDragMovesTheCameraByTheActualDelta() {
        drag(Keys.MOUSE_LEFT, 100, 100, 130, 100);
        assertEquals(1, drags.size());
        assertEquals(30, drags.get(0)[0], 0.5f);
    }

    @Test
    void wheelBubblesToTheScrollViewWithoutAController() {
        // A static viewport (no camera controller) must not swallow the wheel:
        // the page containing it has to keep scrolling.
        Viewport3D plain = new Viewport3D(); // no controller
        limn.scene.layout.Column col = new limn.scene.layout.Column();
        col.add(plain);
        col.add(new limn.scene.layout.SizedBox(200, 400));
        ScrollView scrollView = new ScrollView(col);
        Scene s = new Scene(scrollView);
        s.setTextRuler(RULER);
        s.layoutPass(200, 200);

        s.scrolled(0, -3, 100, 100); // wheel over the viewport
        s.inputBatchEnded();
        assertTrue(scrollView.offsetY() > 0, "the page scrolled: " + scrollView.offsetY());
    }

    @Test
    void wheelZoomsAndIsConsumedWithAController() {
        final float[] zoomed = {0};
        viewport.setController(new CameraController() {
            @Override
            public void drag(float dx, float dy) {
            }

            @Override
            public void zoom(float amount) {
                zoomed[0] += amount;
            }
        });
        scene.scrolled(0, 2, 100, 100);
        scene.inputBatchEnded();
        assertEquals(2, zoomed[0], 0.01f);
    }

    @Test
    void rightDragNeverUsesStaleGestureState() {
        // Arm real state far away with a left gesture first...
        drag(Keys.MOUSE_LEFT, 10, 10, 20, 10);
        drags.clear();
        // ...then a right-button gesture elsewhere: before the fix this reused
        // lastX/lastY = (20, 10) and jumped the camera by (140, 140).
        drag(Keys.MOUSE_RIGHT, 150, 140, 160, 150);
        assertTrue(drags.isEmpty(), "non-left gestures must not drive the camera: " + drags.size());
    }
}
