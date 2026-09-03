package limn.components;

import limn.input.Keys;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Padding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void pointerActivityRevealsTheBarAndTheHoldEndsOnATimerNotATicker() {
        long[] now = {System.nanoTime()};
        Model model = new Model();
        ScrollBar bar = mount(ScrollBar.Policy.AUTO, model);
        bar.clock(() -> now[0]);
        // Let the first-overflow flash expire on the injected clock, and take the ticker
        // population as the floor: what this test asserts is that activity adds none.
        now[0] += 5_000_000_000L;
        bar.onHoldElapsed();
        assertFalse(bar.revealing(), "the flash has expired");

        bar.onHostActivity();
        assertTrue(bar.revealing(), "pointer activity over the host reveals the bar");
        for (int i = 0; i < 100; i++) {
            now[0] += 8_000_000L; // 120 moves at the display's rate, all inside the hold
            bar.onHostActivity();
        }
        assertTrue(bar.revealing(), "and keeps it revealed while the pointer moves");

        // The delayed check finds the hold extended and stays quiet; time passes; it ends it.
        bar.onHoldElapsed();
        assertTrue(bar.revealing(), "a check that lands inside an extended hold changes nothing");
        now[0] += 2_000_000_000L;
        bar.onHoldElapsed();
        assertFalse(bar.revealing(), "the hold has ended and the bar is fading");
    }

    @Test
    void restingThePointerOnTheBarHoldsItWithoutAnyTimer() {
        Model model = new Model();
        ScrollBar bar = mount(ScrollBar.Policy.AUTO, model);
        scene.mouseMoved(ScrollBar.thickness() / 2, 100);
        scene.inputBatchEnded();
        assertTrue(bar.revealing(), "the pointer over the bar shows it");
        // Nothing timed is pending here: hover ends by its own EXIT event, so a bar rested on
        // asks for no frame, where it used to keep a ticker alive for as long as the rest.
        scene.mouseMoved(-50, -50);
        scene.inputBatchEnded();
        long[] now = {System.nanoTime() + 5_000_000_000L};
        bar.clock(() -> now[0]);
        bar.onHoldElapsed();
        assertFalse(bar.revealing(), "off the bar and past every hold, it fades");
    }
}
