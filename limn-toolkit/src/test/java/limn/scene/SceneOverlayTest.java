package limn.scene;

import limn.backend.Cursor;
import limn.input.Keys;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Modal overlay layer: input capture and focus confinement. */
class SceneOverlayTest extends SceneTestBase {

    /** Focusable leaf that records the presses it receives. */
    static final class PressBox extends Widget {
        final String name;
        final List<String> log;

        PressBox(String name, List<String> log) {
            this.name = name;
            this.log = log;
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(200, 200);
        }

        @Override
        protected void onLayout() {
            for (Widget child : children()) {
                child.measure(Constraints.loose(width(), height()));
                child.layoutBox(0, 0, width(), height());
            }
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() == MouseEvent.Type.PRESS) {
                log.add(name);
            }
        }
    }

    private final List<String> log = new ArrayList<>();

    @Test
    void overlayCapturesAllInputAwayFromContent() {
        PressBox content = new PressBox("content", log);
        Scene scene = new Scene(content);
        scene.layoutPass(200, 200);

        PressBox overlay = new PressBox("overlay", log);
        scene.pushOverlay(overlay);
        scene.layoutPass(200, 200);

        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 50, 50);
        scene.inputBatchEnded();
        assertTrue(log.contains("overlay"), "overlay receives the press");
        assertFalse(log.contains("content"), "content behind the modal must not: " + log);
    }

    @Test
    void focusIsConfinedToTheOverlayAndRestoredOnRemove() {
        Column content = new Column();
        PressBox contentBox = new PressBox("content", log);
        content.add(contentBox);
        Scene scene = new Scene(content);
        scene.layoutPass(200, 200);
        scene.requestFocus(contentBox);
        assertSame(contentBox, scene.focusedWidget());

        PressBox overlayBox = new PressBox("overlay", log);
        scene.pushOverlay(overlayBox);
        // pushOverlay moves focus into the modal layer.
        assertSame(overlayBox, scene.focusedWidget());

        // Tab traversal stays inside the overlay (single focusable → itself).
        scene.keyEvent(Keys.TAB, true, false, 0);
        scene.inputBatchEnded();
        assertSame(overlayBox, scene.focusedWidget(), "Tab cannot escape the modal layer");

        scene.removeOverlay(overlayBox);
        assertSame(contentBox, scene.focusedWidget(), "focus returns to the widget that opened the overlay");
    }

    @Test
    void stackedOverlaysRestoreFocusLevelByLevel() {
        Column content = new Column();
        PressBox contentBox = new PressBox("content", log);
        content.add(contentBox);
        Scene scene = new Scene(content);
        scene.layoutPass(200, 200);
        scene.requestFocus(contentBox);

        PressBox overlay1 = new PressBox("o1", log);
        scene.pushOverlay(overlay1);
        assertSame(overlay1, scene.focusedWidget());
        PressBox overlay2 = new PressBox("o2", log);
        scene.pushOverlay(overlay2);
        assertSame(overlay2, scene.focusedWidget());

        scene.removeOverlay(overlay2);
        assertSame(overlay1, scene.focusedWidget(), "focus returns to the overlay below");
        scene.removeOverlay(overlay1);
        assertSame(contentBox, scene.focusedWidget(), "then back to the content trigger");
    }

    /** Leaf that requests the pointer cursor and records the hovers it receives. */
    static final class HoverBar extends Widget {
        final List<String> log;

        HoverBar(List<String> log) {
            this.log = log;
            setCursor(Cursor.POINTER);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(200, 200);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() == MouseEvent.Type.ENTER || event.type() == MouseEvent.Type.MOVE) {
                log.add("bar");
            }
        }
    }

    /** Overlay that yields the top 20px strip to the content beneath it. */
    static final class StripYieldingOverlay extends Widget {
        final List<String> log;

        StripYieldingOverlay(List<String> log) {
            this.log = log;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(c.maxWidth(), c.maxHeight());
        }

        @Override
        protected boolean overlayPassesPointer(float sceneX, float sceneY) {
            return sceneY < 20; // the "menu bar strip"
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() == MouseEvent.Type.ENTER || event.type() == MouseEvent.Type.MOVE) {
                log.add("overlay");
            }
        }
    }

    @Test
    void overlayYieldsItsStripToTheContentBeneathIncludingTheCursor() {
        List<String> hits = new ArrayList<>();
        RecordingWindow win = new RecordingWindow();
        HoverBar bar = new HoverBar(hits);
        Scene scene = new Scene(bar);
        scene.bind(win);
        scene.layoutPass(200, 200);

        scene.pushOverlay(new StripYieldingOverlay(hits));
        scene.layoutPass(200, 200);

        // Over the yielded strip: the pointer falls through to the content bar; it
        // hovers and its cursor wins, exactly like a menu bar behind a fullscreen menu.
        scene.mouseMoved(100, 10);
        scene.inputBatchEnded();
        assertTrue(hits.contains("bar") && !hits.contains("overlay"), "bar hovered through the strip: " + hits);
        assertEquals(Cursor.POINTER, win.cursor, "content cursor applies over the strip");

        hits.clear();
        // Below the strip the overlay captures as usual, with its own (default) cursor.
        scene.mouseMoved(100, 100);
        scene.inputBatchEnded();
        assertTrue(hits.contains("overlay") && !hits.contains("bar"), "overlay captured below the strip: " + hits);
        assertEquals(Cursor.DEFAULT, win.cursor, "overlay cursor below the strip");
    }

    @Test
    void removingABuriedOverlayLeavesTheTopOverlayFocused() {
        PressBox content = new PressBox("content", log);
        Scene scene = new Scene(content);
        scene.layoutPass(200, 200);
        scene.requestFocus(content);

        PressBox lower = new PressBox("lower", log);
        scene.pushOverlay(lower);
        PressBox upper = new PressBox("upper", log);
        scene.pushOverlay(upper);
        assertSame(upper, scene.focusedWidget());

        // Remove the buried (lower) overlay: the previous menu fading out under the
        // menu it was switched to. The top overlay must keep focus (not be cleared).
        scene.removeOverlay(lower);
        assertSame(upper, scene.focusedWidget(), "buried removal must not disturb the top overlay's focus");
    }

    @Test
    void overlaysStackLastOnTop() {
        PressBox content = new PressBox("content", log);
        Scene scene = new Scene(content);
        scene.layoutPass(200, 200);
        scene.pushOverlay(new PressBox("first", log));
        PressBox second = new PressBox("second", log);
        scene.pushOverlay(second);
        scene.layoutPass(200, 200);

        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 50, 50);
        scene.inputBatchEnded();
        assertTrue(log.contains("second") && !log.contains("first"),
                "only the topmost overlay gets input: " + log);
    }
}
