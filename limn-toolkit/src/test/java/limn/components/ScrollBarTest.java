package limn.components;

import limn.input.Keys;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dragging and track-paging math of the shared {@link ScrollBar}. */
class ScrollBarTest extends ComponentTestBase {

    private static final class Model implements ScrollBar.Model {
        float content = 1000;
        float viewport = 200;
        float offset;

        @Override
        public float contentLength() {
            return content;
        }

        @Override
        public float viewportLength() {
            return viewport;
        }

        @Override
        public float offset() {
            return offset;
        }

        @Override
        public void setOffset(float value) {
            offset = value; // the host would clamp; recorded raw here
        }
    }

    private Scene scene;

    private ScrollBar mount(ScrollBar.Policy policy, Model model) {
        ScrollBar bar = new ScrollBar(ScrollBar.Orientation.VERTICAL, model).setPolicy(policy);
        scene = new Scene(new Padding(Insets.NONE, bar));
        scene.layoutPass(ScrollBar.thickness(), 200);
        return bar;
    }

    @Test
    void draggingTheThumbScrolls() {
        Model model = new Model();
        mount(ScrollBar.Policy.ALWAYS, model);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 3, 8); // grab the thumb near the top
        scene.inputBatchEnded();
        scene.mouseMoved(3, 108); // drag +100 down the track
        scene.inputBatchEnded();
        assertTrue(model.offset > 300 && model.offset < 700, "dragged proportionally: " + model.offset);
    }

    @Test
    void clickingTheTrackPagesTowardThePointer() {
        Model model = new Model();
        mount(ScrollBar.Policy.ALWAYS, model);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 3, 180); // well below the thumb
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 3, 180);
        scene.inputBatchEnded();
        assertEquals(model.viewport, model.offset, 0.01f, "paged down by one viewport");
    }

    @Test
    void hiddenPolicyIsTransparentToClicks() {
        Model model = new Model();
        mount(ScrollBar.Policy.HIDDEN, model);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 3, 8);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 3, 8);
        scene.inputBatchEnded();
        assertEquals(0, model.offset, "a hidden bar ignores clicks");
    }

    @Test
    void theTrackMarginIsTheToolkitConstant() {
        // ADR 002 recorded that Strokes.SCROLLBAR_MARGIN was declared and read by nobody while
        // ScrollBar kept a private twin that agreed by coincidence. The bar reads the constant
        // now; this pins the value every screenshot was baselined on.
        assertEquals(2f, Strokes.SCROLLBAR_MARGIN, 0f);
        assertTrue(ScrollBar.thickness() > 2 * Strokes.SCROLLBAR_MARGIN,
                "the margin is what the track thickness carries on both sides");
    }
}
