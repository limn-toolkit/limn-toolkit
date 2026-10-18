package limn.components;

import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Padding;
import limn.scene.layout.Stack;
import limn.scene.layout.Transform;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A popup in a window of its own, opened from a widget inside a {@link Transform}, is placed again
 * when the container comes to rest somewhere new (ADR 072 §2.8): on a set, and at a motion's
 * arrival, and not on the frames between. The scene lays a popup drawn in it out again by itself;
 * a window's placement is its owner's, which the scene asks.
 */
class PopupWindowUnderATransformTest extends ComponentTestBase {

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};

    @Test
    void aComboBoxsListWindowIsPlacedAgainOnASetAndAtAnArrivalAndNotOnTheFramesBetween() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        HeadlessWindow owner = backend.open("Owner", 400, 300);
        ComboBox<String> combo = new ComboBox<>(List.of("One", "Two", "Three"));
        Transform t = new Transform(combo).setOrigin(0, 0);
        Stack root = new Stack();
        root.add(new Padding(new Insets(20, 0, 0, 20), t));
        Scene scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(owner);
        owner.frame();
        combo.open();
        runtime.drain(); // the window is made a turn later
        HeadlessWindow list = assertInstanceOf(HeadlessWindow.class, combo.popupWindow(),
                "the list opened in a window of its own");
        assertNotNull(list);
        int placed = list.screenPositionRequests();

        t.setTranslation(40, 30);
        runtime.drain(); // placed again a turn later, outside the frame that settled it
        assertEquals(placed + 1, list.screenPositionRequests(), "a set places the window again");
        int x = list.requestedScreenX();
        int y = list.requestedScreenY();

        t.setTranslation(40, 60);
        runtime.drain();
        assertEquals(x, list.requestedScreenX(), "the window follows the anchor: across, not at all");
        assertEquals(y + 30, list.requestedScreenY(), "and down, by what the anchor moved");

        // A slide: the frames between move nothing, and its arrival places the window again.
        int beforeSlide = list.screenPositionRequests();
        t.slideTo(40, 10, 0.2, null);
        owner.frame();
        for (int i = 0; i < 4; i++) {
            now[0] += TimeUnit.MILLISECONDS.toNanos(16);
            owner.frame();
            runtime.drain();
        }
        assertTrue(t.translationY() < 60 && t.translationY() > 10, "in flight: " + t.translationY());
        assertEquals(beforeSlide, list.screenPositionRequests(), "the frames of the slide place nothing");
        for (int i = 0; i < 30; i++) {
            now[0] += TimeUnit.MILLISECONDS.toNanos(16);
            owner.frame();
            runtime.drain();
        }
        assertEquals(10f, t.translationY(), "arrived");
        assertEquals(beforeSlide + 1, list.screenPositionRequests(), "its arrival places the window once");
        assertEquals(y - 20, list.requestedScreenY(), "where the anchor came to rest");

        combo.close();
        runtime.drain();
        int closed = list.screenPositionRequests();
        t.setTranslation(0, 0);
        runtime.drain();
        assertEquals(closed, list.screenPositionRequests(), "a closed list is not placed again");
    }
}
