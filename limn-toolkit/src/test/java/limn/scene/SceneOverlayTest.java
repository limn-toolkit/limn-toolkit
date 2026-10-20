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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/** Modal overlay layer: input capture and focus confinement. */
class SceneOverlayTest extends SceneTestBase {

    /** Focusable leaf that records the presses it receives. */
    static final class PressBox extends Widget<PressBox> {
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
            for (Widget<?> child : children()) {
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

        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 50, 50);
        drive(scene).inputBatchEnded();
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
        drive(scene).keyEvent(Keys.TAB, true, false, 0);
        drive(scene).inputBatchEnded();
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
    static final class HoverBar extends Widget<HoverBar> {
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
    static final class StripYieldingOverlay extends Widget<StripYieldingOverlay> {
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
        drive(scene).mouseMoved(100, 10);
        drive(scene).inputBatchEnded();
        assertTrue(hits.contains("bar") && !hits.contains("overlay"), "bar hovered through the strip: " + hits);
        assertEquals(Cursor.POINTER, win.cursor, "content cursor applies over the strip");

        hits.clear();
        // Below the strip the overlay captures as usual, with its own (default) cursor.
        drive(scene).mouseMoved(100, 100);
        drive(scene).inputBatchEnded();
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

        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 50, 50);
        drive(scene).inputBatchEnded();
        assertTrue(log.contains("second") && !log.contains("first"),
                "only the topmost overlay gets input: " + log);
    }

    /** Focusable leaf that records the focus it is told it gained or lost. */
    static final class FocusBox extends Widget<FocusBox> {
        final String name;
        final List<String> log;

        FocusBox(String name, List<String> log) {
            this.name = name;
            this.log = log;
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(100, 20);
        }

        @Override
        protected void onFocusGained() {
            log.add("gained " + name + (focusArrivedByTraversal() ? " by traversal" : ""));
        }

        @Override
        protected void onFocusLost() {
            log.add("lost " + name);
        }
    }

    /**
     * Content moved from another scene brings its keyboard, and the widget holding it is told
     * nothing: a loss would end what it was in the middle of, and a gain by traversal, which is how
     * an overlay's first stop is focused, would make a field select all of its text.
     */
    @Test
    void anOverlayMovedFromAnotherSceneKeepsItsKeyboardWithoutTellingIt() {
        FocusBox opener = new FocusBox("opener", log);
        Scene owner = new Scene(opener);
        owner.layoutPass(200, 200);
        owner.requestFocus(opener);
        Column card = new Column();
        FocusBox first = new FocusBox("first", log);
        FocusBox typing = new FocusBox("typing", log);
        card.add(first);
        card.add(typing);
        Scene window = new Scene(card);
        window.layoutPass(200, 200);
        window.requestFocus(typing);
        log.clear();

        Column overlay = new Column();
        overlay.add(card); // out of the scene it was the root of
        owner.pushOverlay(overlay, window);

        assertSame(typing, owner.focusedWidget(), "the keyboard came with the content");
        assertTrue(typing.isFocused());
        assertNull(window.focusedWidget(), "and the scene left holds nothing of it");
        assertEquals(List.of("lost opener"), log, "only this scene's own focus was told anything");

        owner.removeOverlay(overlay);
        assertSame(opener, owner.focusedWidget(), "closed, it gives the keyboard back as any does");
    }

    /** With nothing of the moved content focused where it was, the overlay's first stop is. */
    @Test
    void anOverlayMovedWithoutAKeyboardFocusesItsFirstStop() {
        Scene owner = new Scene(new FocusBox("opener", log));
        owner.layoutPass(200, 200);
        Column card = new Column();
        FocusBox first = new FocusBox("first", log);
        card.add(first);
        Scene window = new Scene(card);
        window.layoutPass(200, 200);

        Column overlay = new Column();
        overlay.add(card);
        owner.pushOverlay(overlay, window);

        assertSame(first, owner.focusedWidget());
        assertEquals(List.of("gained first by traversal"), log, "as pushOverlay(Widget) would");
    }

    /** A leaf whose first onAttached throws, as an application's may. */
    static final class AttachFault extends Widget<AttachFault> {
        boolean armed = true;

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(50, 20);
        }

        @Override
        protected void onAttached() {
            if (armed) {
                throw new IllegalStateException("a fault in onAttached (expected)");
            }
        }
    }

    @Test
    void anOverlayWhoseAttachThrowsHandsTheKeyboardToNoWidgetTheWalkNeverReached() {
        // A dialog whose first widget's onAttached throws, pushed with its first stop focused by
        // traversal and with a named one: the push rethrows, and the keyboard is on no widget
        // outside the scene. The push held the walk's throw and went on to focus the dialog's
        // button, which the walk never reached: a keyboard handed to a widget in no scene.
        for (boolean named : new boolean[] {false, true}) {
            Column content = new Column();
            PressBox opener = new PressBox("opener", log);
            content.add(opener);
            Scene scene = new Scene(content);
            scene.layoutPass(200, 200);
            scene.requestFocus(opener);
            Column dialog = new Column();
            dialog.add(new AttachFault());
            PressBox inside = new PressBox("inside", log);
            dialog.add(inside);
            IllegalStateException thrown = null;
            try {
                if (named) {
                    scene.pushOverlayFocusing(dialog, inside);
                } else {
                    scene.pushOverlay(dialog);
                }
            } catch (IllegalStateException e) {
                thrown = e;
            }
            String where = named ? "pushed focusing its button" : "pushed";
            assertEquals("a fault in onAttached (expected)", thrown == null ? null : thrown.getMessage(), where);
            assertNull(inside.scene(), where + ": sanity, the walk never reached it");
            Widget<?> focused = scene.focusedWidget();
            assertTrue(focused == null || focused.scene() == scene,
                    where + ": the keyboard is on no widget outside the scene: " + focused);
            scene.layoutPass(200, 200);
            drive(scene).press(Keys.TAB);
            focused = scene.focusedWidget();
            assertTrue(focused == null || focused.scene() == scene, where + ": nor after a Tab: " + focused);
        }
    }

    @Test
    void contentMovedFromAnotherSceneWhoseAttachThrowsBringsNoKeyboardTheWalkNeverReached() {
        // A dialog's content moved into its owner's scene with a field in it focused, the move cut
        // short by an onAttached that throws before the walk reaches the field: the keyboard does
        // not come along to a widget in no scene.
        FocusBox opener = new FocusBox("opener", log);
        Scene owner = new Scene(opener);
        owner.layoutPass(200, 200);
        owner.requestFocus(opener);
        Column card = new Column();
        AttachFault fault = new AttachFault();
        fault.armed = false;
        FocusBox typing = new FocusBox("typing", log);
        card.add(fault);
        card.add(typing);
        Scene window = new Scene(card);
        window.layoutPass(200, 200);
        window.requestFocus(typing);
        Column overlay = new Column();
        overlay.add(card);
        fault.armed = true;
        IllegalStateException thrown = null;
        try {
            owner.pushOverlay(overlay, window);
        } catch (IllegalStateException e) {
            thrown = e;
        }
        assertEquals("a fault in onAttached (expected)", thrown == null ? null : thrown.getMessage());
        assertNull(typing.scene(), "sanity, the walk never reached it");
        Widget<?> focused = owner.focusedWidget();
        assertTrue(focused == null || focused.scene() == owner, "the keyboard is in the scene: " + focused);
        // The scene the content left lets go of the keyboard there, as the push does when the walk
        // reaches the widget: it kept it on a widget it no longer held, and its window's close then
        // told the widget of a loss, which ends an entry, the thing a moved focus is spared.
        assertNull(window.focusedWidget(), "the scene left lets go of it");
        log.clear();
        window.requestFocus(null);
        assertFalse(log.contains("lost typing"), "and tells the widget of no loss later: " + log);
    }

    /** A form that starts the keyboard on one of its fields as it joins a scene, as a page of a wizard does. */
    static final class StartingForm extends Column {
        final List<String> log;
        Widget<?> start;

        StartingForm(List<String> log) {
            this.log = log;
        }

        @Override
        protected void onAttached() {
            log.add("form attached");
            scene().requestFocus(start);
        }
    }

    /** A focusable leaf that says when it joins and when it gains the keyboard. */
    static final class JoiningField extends Widget<JoiningField> {
        final String name;
        final List<String> log;

        JoiningField(String name, List<String> log) {
            this.name = name;
            this.log = log;
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(100, 20);
        }

        @Override
        protected void onAttached() {
            log.add(name + " attached");
        }

        @Override
        protected void onFocusGained() {
            log.add("gained " + name + (scene() == null ? " in no scene" : ""));
        }
    }

    @Test
    void aContainerFocusingItsFieldFromOnAttachedStartsTheKeyboardThere() {
        // A form whose onAttached says "start here", scene().requestFocus(nameField), added to a
        // live window and made the root a scene is built on: the walk attaches top-down, so the
        // field answers no scene while the form's onAttached runs, and the request was dropped,
        // the keyboard starting nowhere. It lands on the field once the field has joined.
        for (boolean asRoot : new boolean[] {false, true}) {
            String where = asRoot ? "the root of a new scene" : "added to a live window";
            log.clear();
            StartingForm form = new StartingForm(log);
            JoiningField other = new JoiningField("surname", log);
            JoiningField name = new JoiningField("name", log);
            form.add(other);
            form.add(name);
            form.start = name;
            Scene scene;
            if (asRoot) {
                scene = new Scene(form);
            } else {
                Column content = new Column();
                PressBox base = new PressBox("base", new ArrayList<>());
                content.add(base);
                scene = new Scene(content);
                scene.layoutPass(200, 200);
                scene.requestFocus(base);
                content.add(form);
            }
            assertSame(name, scene.focusedWidget(), where + ": " + log);
            assertEquals(List.of("form attached", "surname attached", "name attached", "gained name"), log,
                    where + ": the field gains the keyboard once it has joined, and is the one told");
        }
    }

    @Test
    void aFocusAskedForInAJoinTheWalkNeverFinishesLandsNowhere() {
        // The hard case beside it: the form asks for its field, and a widget before the field throws
        // from onAttached, so the walk never reaches it. The keyboard goes to no widget in no scene,
        // on a live window and in a pushed overlay alike.
        for (boolean overlay : new boolean[] {false, true}) {
            String where = overlay ? "pushed as an overlay" : "added to a live window";
            log.clear();
            StartingForm form = new StartingForm(log);
            AttachFault fault = new AttachFault();
            JoiningField name = new JoiningField("name", log);
            form.add(fault);
            form.add(name);
            form.start = name;
            Column content = new Column();
            PressBox base = new PressBox("base", new ArrayList<>());
            content.add(base);
            Scene scene = new Scene(content);
            scene.layoutPass(200, 200);
            scene.requestFocus(base);
            IllegalStateException thrown = null;
            try {
                if (overlay) {
                    scene.pushOverlay(form);
                } else {
                    content.add(form);
                }
            } catch (IllegalStateException e) {
                thrown = e;
            }
            assertEquals("a fault in onAttached (expected)", thrown == null ? null : thrown.getMessage(), where);
            assertNull(name.scene(), where + ": sanity, the walk never reached it");
            Widget<?> focused = scene.focusedWidget();
            assertTrue(focused == null || focused.scene() == scene, where + ": " + focused + " " + log);
            assertFalse(log.contains("gained name in no scene"), where + ": " + log);
        }
    }
}
