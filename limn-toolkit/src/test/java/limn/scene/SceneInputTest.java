package limn.scene;

import limn.input.Keys;
import limn.scene.event.CharEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Event pipeline tests with a fake input source (the WindowInput methods) and
 * a controllable clock: coalescing, bubbling, click synthesis, hover, focus
 * traversal and the slow-handler instrumentation.
 */
class SceneInputTest extends SceneTestBase {

    /** Records every event it sees; optionally consumes selected mouse types. */
    static final class Recorder extends FixedBox {
        final String name;
        final List<String> log;
        MouseEvent lastMouse;
        MouseEvent lastWheel;
        MouseEvent.Type consumeType;
        MouseEvent.Type throwOnType;

        Recorder(String name, List<String> log, float w, float h) {
            super(w, h);
            this.name = name;
            this.log = log;
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            log.add(name + ":" + event.type());
            lastMouse = event;
            if (event.type() == MouseEvent.Type.WHEEL) {
                lastWheel = event;
            }
            if (event.type() == consumeType) {
                event.consume();
            }
            if (event.type() == throwOnType) {
                throw new IllegalStateException("app handler bug");
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            log.add(name + ":KEY" + event.key());
        }

        @Override
        protected void onCharTyped(CharEvent event) {
            log.add(name + ":CHAR" + event.codepoint());
        }

        @Override
        protected void onFileDrop(limn.scene.event.FileDropEvent event) {
            log.add(name + ":DROP" + event.paths().size());
        }

        @Override
        protected void onLayout() {
            for (Widget child : children()) {
                child.measure(Constraints.loose(width(), height()));
                child.layoutBox(0, 0, width(), height());
            }
        }
    }

    private final List<String> log = new ArrayList<>();

    /** Column [a (0..100), b (100..200)] inside a 200x200 scene. */
    private Recorder a;
    private Recorder b;
    private Column rootColumn;
    private Scene scene;

    private Scene buildScene(LongSupplierAdapter clock) {
        rootColumn = new Column();
        a = new Recorder("a", log, 200, 100);
        b = new Recorder("b", log, 200, 100);
        rootColumn.add(a);
        rootColumn.add(b);
        scene = clock == null ? new Scene(rootColumn) : new Scene(rootColumn, clock);
        scene.layoutPass(200, 200);
        return scene;
    }

    private interface LongSupplierAdapter extends java.util.function.LongSupplier {
    }

    @Test
    void hoverFollowsContentMovingUnderAStationaryMouse() {
        buildScene(null);
        scene.mouseMoved(10, 10); // pointer over 'a' (0..100)
        scene.inputBatchEnded();
        assertTrue(log.contains("a:ENTER"));
        log.clear();

        // Content moves under the stationary mouse (a keyboard scroll would do
        // the same): the next frame must re-evaluate hover, not wait for the
        // next pointer event.
        rootColumn.remove(a); // 'b' slides up under the pointer
        scene.renderFrame(new NoopCanvas(200, 200));
        assertTrue(log.contains("b:ENTER"),
                "hover resyncs to what is under the pointer now: " + log);
    }

    @Test
    void programmaticFocusIsConfinedToTheTopModalOverlay() {
        buildScene(null);
        a.setFocusable(true);
        Recorder modal = new Recorder("modal", log, 200, 200);
        modal.setFocusable(true);
        scene.pushOverlay(modal);
        assertSame(modal, scene.focusedWidget(), "the overlay takes focus on push");

        scene.requestFocus(a); // background widget: must NOT steal the modal's keys
        assertSame(modal, scene.focusedWidget(),
                "focus stays inside the top overlay while it is open");

        scene.removeOverlay(modal);
        scene.requestFocus(a);
        assertSame(a, scene.focusedWidget(), "confinement lifts with the overlay");
    }

    @Test
    void pushOverlayCancelsAnInFlightDrag() {
        buildScene(null);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10); // press captures 'a'
        scene.mouseMoved(12, 10);
        scene.inputBatchEnded();
        assertTrue(log.contains("a:DRAG"), "sanity: the drag reaches the captured widget");
        log.clear();

        scene.pushOverlay(new Recorder("modal", log, 200, 200));
        assertTrue(log.contains("a:RELEASE"), "the capture is released when the modal opens");
        log.clear();
        scene.mouseMoved(40, 10); // pointer still physically down in the OS
        scene.inputBatchEnded();
        assertTrue(log.stream().noneMatch(s -> s.equals("a:DRAG")),
                "no DRAG keeps flowing beneath the scrim: " + log);
    }

    @Test
    void consecutiveMovesCoalesceToTheNewest() {
        buildScene(null);
        for (int i = 0; i < 5; i++) {
            scene.mouseMoved(10 + i, 10);
        }
        scene.inputBatchEnded();
        assertEquals(List.of("a:ENTER", "a:MOVE"), log);
        assertEquals(14, a.lastMouse.x(), 1e-3);
    }

    @Test
    void scrollsAccumulateDeltas() {
        buildScene(null);
        scene.scrolled(0, -1, 10, 10);
        scene.scrolled(0, -1, 10, 10);
        scene.scrolled(1, -1, 10, 10);
        scene.inputBatchEnded();
        long wheels = log.stream().filter(s -> s.endsWith("WHEEL")).count();
        assertEquals(1, wheels);
        assertEquals(-3, a.lastWheel.scrollY(), 1e-3);
        assertEquals(1, a.lastWheel.scrollX(), 1e-3);
    }

    @Test
    void clicksAreNeverDroppedAndKeepOrderWithMoves() {
        buildScene(null);
        scene.mouseMoved(10, 10);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.mouseMoved(12, 10); // between press and release: DRAG
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 12, 10);
        scene.inputBatchEnded();
        assertEquals(List.of("a:ENTER", "a:MOVE", "a:PRESS", "a:DRAG", "a:RELEASE", "a:CLICK"), log);
    }

    @Test
    void releaseOutsideThePressedWidgetCancelsTheClick() {
        buildScene(null);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);   // press on a
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 10, 150); // release over b
        scene.inputBatchEnded();
        assertTrue(log.contains("a:PRESS"));
        assertTrue(log.contains("a:RELEASE"), "release goes to the pressed widget");
        assertTrue(log.stream().noneMatch(s -> s.endsWith("CLICK")), "no click: " + log);
    }

    @Test
    void unconsumedEventsBubbleAndConsumeStopsPropagation() {
        Recorder outer = new Recorder("outer", log, 200, 200);
        Column inner = new Column();
        Recorder child = new Recorder("child", log, 100, 100);
        inner.add(child);
        outer.add(inner);
        Scene s2 = new Scene(outer);
        s2.layoutPass(200, 200);

        s2.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        s2.inputBatchEnded();
        assertTrue(log.contains("child:PRESS") && log.contains("outer:PRESS"),
                "unconsumed press bubbles: " + log);

        log.clear();
        child.consumeType = MouseEvent.Type.PRESS;
        s2.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        s2.inputBatchEnded();
        assertTrue(log.contains("child:PRESS") && !log.contains("outer:PRESS"),
                "consumed press must not bubble: " + log);
    }

    @Test
    void hoverTransitionsDispatchEnterAndExit() {
        buildScene(null);
        scene.mouseMoved(10, 10);
        scene.inputBatchEnded();
        scene.mouseMoved(10, 150);
        scene.inputBatchEnded();
        assertEquals(List.of("a:ENTER", "a:MOVE", "a:EXIT", "b:ENTER", "b:MOVE"), log);
    }

    @Test
    void pointerLeavingTheWindowClearsHover() {
        buildScene(null);
        scene.mouseMoved(10, 10);
        scene.inputBatchEnded();
        scene.pointerEntered(false);
        scene.inputBatchEnded();
        assertTrue(log.contains("a:EXIT"));
    }

    @Test
    void tabTraversalFollowsLayoutOrderAndShiftReverses() {
        buildScene(null);
        a.setFocusable(true);
        b.setFocusable(true);

        scene.keyEvent(Keys.TAB, true, false, 0);
        scene.inputBatchEnded();
        assertSame(a, scene.focusedWidget());

        scene.keyEvent(Keys.TAB, true, false, 0);
        scene.inputBatchEnded();
        assertSame(b, scene.focusedWidget());

        scene.keyEvent(Keys.TAB, true, false, Keys.MOD_SHIFT);
        scene.inputBatchEnded();
        assertSame(a, scene.focusedWidget());
    }

    @Test
    void clickFocusesTheNearestFocusableAncestor() {
        buildScene(null);
        b.setFocusable(true);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 150);
        scene.inputBatchEnded();
        assertSame(b, scene.focusedWidget());
        // a is not focusable: pressing it keeps focus where it was.
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.inputBatchEnded();
        assertSame(b, scene.focusedWidget());
    }

    @Test
    void keysAndCharsGoToTheFocusedWidget() {
        buildScene(null);
        b.setFocusable(true);
        scene.requestFocus(b);
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.charTyped('x');
        scene.inputBatchEnded();
        assertTrue(log.contains("b:KEY" + Keys.ENTER), log.toString());
        assertTrue(log.contains("b:CHAR" + (int) 'x'), log.toString());
        assertTrue(log.stream().noneMatch(s -> s.startsWith("a:KEY")), "a never sees the key");
    }

    @Test
    void disabledSubtreesAreNotHit() {
        buildScene(null);
        a.setEnabled(false);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.inputBatchEnded();
        assertTrue(log.stream().noneMatch(s -> s.startsWith("a:")), log.toString());
    }

    @Test
    void slowHandlersAreCountedAgainstTheBudget() {
        // Every clock read advances 10 ms: any handler appears to take 10 ms.
        AtomicLong fake = new AtomicLong();
        buildScene(() -> fake.addAndGet(TimeUnit.MILLISECONDS.toNanos(10)));
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.inputBatchEnded();
        assertTrue(scene.slowHandlerCount() > 0, "10 ms handlers exceed the 8 ms budget");
    }

    @Test
    void widgetMutationOffTheUiThreadThrows() throws ExecutionException, InterruptedException {
        buildScene(null);
        IllegalStateException error = workers.submit(() ->
                assertThrows(IllegalStateException.class, () -> a.setVisible(false))
        ).get();
        assertTrue(error.getMessage().contains("UI thread"), error.getMessage());
    }

    @Test
    void removingThePressedWidgetInItsOwnPressHandlerReleasesCapture() {
        // Regression (code review): 'pressed' must be captured BEFORE the PRESS
        // dispatch so onWidgetDetached can clear it when a handler removes
        // the widget; otherwise the scene keeps dragging a dead widget.
        class SelfRemoving extends FixedBox {
            SelfRemoving() {
                super(200, 100);
            }

            @Override
            protected void onMouseEvent(MouseEvent event) {
                if (event.type() == MouseEvent.Type.PRESS) {
                    parent().remove(this);
                    event.consume();
                }
            }
        }
        Column column = new Column();
        SelfRemoving volatileBox = new SelfRemoving();
        Recorder survivor = new Recorder("survivor", log, 200, 100);
        column.add(volatileBox);
        column.add(survivor);
        Scene s2 = new Scene(column);
        s2.layoutPass(200, 200);

        s2.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10); // press removes the widget
        s2.inputBatchEnded();
        s2.layoutPass(200, 200); // survivor moves up to fill the space
        s2.mouseMoved(10, 20);
        s2.inputBatchEnded();
        assertTrue(log.contains("survivor:MOVE"),
                "capture must be released; the live tree gets the moves: " + log);
        assertTrue(log.stream().noneMatch(s -> s.endsWith(":DRAG")),
                "no drag to a detached widget: " + log);
    }

    @Test
    void disablingTheHoveredWidgetSynthesizesExit() {
        // Regression (code review): clearing hover state without an EXIT left
        // components painting their hover visuals forever after re-enable.
        buildScene(null);
        scene.mouseMoved(10, 10);
        scene.inputBatchEnded();
        assertTrue(log.contains("a:ENTER"));

        a.setEnabled(false);
        assertTrue(log.contains("a:EXIT"), "synthetic EXIT on disable: " + log);
    }

    @Test
    void disablingThePressedWidgetSynthesizesRelease() {
        buildScene(null);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.inputBatchEnded();
        a.setEnabled(false);
        assertTrue(log.contains("a:RELEASE"), "synthetic RELEASE on disable: " + log);
    }

    @Test
    void detachedWidgetsReleaseFocusAndHover() {
        buildScene(null);
        b.setFocusable(true);
        scene.requestFocus(b);
        scene.mouseMoved(10, 150);
        scene.inputBatchEnded();
        rootColumn.remove(b);
        assertNull(scene.focusedWidget());
    }

    @Test
    void throwingHandlerNeitherAbortsTheBatchNorReplaysEvents() {
        // Regression (code review): the queue was cleared only after the whole
        // dispatch loop, so a throwing handler left the dispatched prefix queued
        // and the next batch replayed it (duplicate clicks, unbounded growth).
        buildScene(null);
        a.throwOnType = MouseEvent.Type.PRESS;

        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);   // a: PRESS handler throws
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 10, 10);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 150);  // b, queued behind the throw
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 10, 150);
        scene.inputBatchEnded(); // must not throw
        assertTrue(log.contains("b:CLICK"), "events after the throw are still delivered: " + log);

        log.clear();
        scene.inputBatchEnded();
        assertTrue(log.isEmpty(), "nothing is replayed on the next batch: " + log);
    }

    @Test
    void droppedFilesDispatchToTheWidgetUnderThePointer() {
        buildScene(null);
        scene.mouseMoved(10, 150); // the platform moves the cursor onto the window before dropping
        scene.inputBatchEnded();
        log.clear();

        scene.filesDropped(List.of(java.nio.file.Path.of("a.txt"), java.nio.file.Path.of("b.png")));
        scene.inputBatchEnded();

        assertEquals(List.of("b:DROP2"), log, "the drop bubbles from the widget under the pointer");
    }

    @Test
    void windowBlurCancelsPressAndHover() {
        // Regression (code review): Cmd-Tab while holding a drag left pressed
        // state stuck: the RELEASE happens in another app and never arrives.
        buildScene(null);
        scene.mouseMoved(10, 10);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.inputBatchEnded();
        log.clear();

        scene.windowFocusChanged(false);
        scene.inputBatchEnded();
        assertTrue(log.contains("a:RELEASE"), "blur synthesizes the RELEASE: " + log);
        assertTrue(log.contains("a:EXIT"), "blur clears hover: " + log);

        log.clear();
        scene.mouseMoved(12, 10);
        scene.inputBatchEnded();
        assertTrue(log.stream().noneMatch(s -> s.endsWith(":DRAG")),
                "after blur, moves are plain MOVEs again: " + log);
    }

    /** Records key events with their press state, which {@link Recorder} deliberately flattens. */
    private static final class KeyBox extends FixedBox {
        final List<String> keys = new ArrayList<>();

        KeyBox() {
            super(200, 200);
            setFocusable(true);
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            keys.add((event.isPressed() ? "down " : "up ") + event.key()
                    + (event.modifiers() == 0 ? "" : " mods=" + event.modifiers()));
        }
    }

    @Test
    void windowBlurReleasesEveryKeyStillHeld() {
        // The keyboard twin of windowBlurCancelsPressAndHover. The key-up lands in whatever app
        // took focus, so a widget that pairs PRESS with RELEASE (held-arrow repeat, a key that
        // keeps something firing, push-to-talk) stays armed forever after one alt-tab.
        KeyBox box = new KeyBox();
        Scene s = new Scene(box);
        s.layoutPass(200, 200);
        s.requestFocus(box);
        s.keyEvent(Keys.LEFT_SHIFT, true, false, Keys.MOD_SHIFT);
        s.keyEvent(Keys.W, true, false, Keys.MOD_SHIFT);
        s.keyEvent(Keys.A, true, false, Keys.MOD_SHIFT);
        s.keyEvent(Keys.A, false, false, Keys.MOD_SHIFT); // released normally, before the blur
        s.inputBatchEnded();
        box.keys.clear();

        s.windowFocusChanged(false);
        s.inputBatchEnded();

        assertEquals(List.of("up " + Keys.W, "up " + Keys.LEFT_SHIFT), box.keys,
                "exactly the keys still held, with no modifiers: focus is gone, so what is still"
                        + " physically down is unknowable and 0 is the only honest mask");
        assertEquals(0, s.modifiers(), "and the mask itself is clear");
    }

    @Test
    void keysReleasedWhileBlurredAreNotReleasedTwice() {
        // The synthetic release must leave the scene believing nothing is down: the real key-up
        // never arrives (it went to the other app), but a second blur must not invent one either.
        KeyBox box = new KeyBox();
        Scene s = new Scene(box);
        s.layoutPass(200, 200);
        s.requestFocus(box);
        s.keyEvent(Keys.SPACE, true, false, 0);
        s.inputBatchEnded();
        s.windowFocusChanged(false);
        s.inputBatchEnded();
        box.keys.clear();

        s.windowFocusChanged(true);
        s.windowFocusChanged(false);
        s.inputBatchEnded();

        assertEquals(List.of(), box.keys, "nothing was held the second time");
    }

    @Test
    void anAutoRepeatDoesNotOutliveTheBlurEither() {
        // A REPEAT is the only event some platforms deliver for a key held across a focus change
        // into the window; treating it as "still down" is what makes the release below possible.
        KeyBox box = new KeyBox();
        Scene s = new Scene(box);
        s.layoutPass(200, 200);
        s.requestFocus(box);
        s.keyEvent(Keys.W, true, true, 0); // repeat, no preceding press in this scene
        s.inputBatchEnded();
        box.keys.clear();

        s.windowFocusChanged(false);
        s.inputBatchEnded();

        assertEquals(List.of("up " + Keys.W), box.keys, "the repeat left a key to release");
    }

    @Test
    void charsGoToTheModalOverlayWhenNothingIsFocused() {
        // Regression (code review): keys already routed to the top modal layer,
        // chars still reached the blocked content root.
        buildScene(null);
        Recorder overlay = new Recorder("overlay", log, 200, 200);
        scene.pushOverlay(overlay);
        log.clear();
        scene.charTyped('x');
        scene.inputBatchEnded();
        assertTrue(log.contains("overlay:CHAR" + (int) 'x'), log.toString());
        assertTrue(log.stream().noneMatch(s -> s.startsWith("a:CHAR")),
                "blocked content must not see chars: " + log);
    }

    @Test
    void onAttachedMayGrowTheTreeWithoutConcurrentModification() {
        // Regression (code review): a child adding a sibling in onAttached blew
        // up the attach traversal's iterator.
        Column container = new Column();
        Widget selfExpanding = new FixedBox(10, 10) {
            @Override
            protected void onAttached() {
                container.add(new FixedBox(10, 10));
            }
        };
        container.add(selfExpanding);
        Scene s2 = new Scene(container); // must not throw
        s2.layoutPass(100, 100);
        assertEquals(2, container.children().size());
    }

    @Test
    void throwingTickerIsRemovedInsteadOfRethrowingEveryFrame() {
        buildScene(null);
        List<Integer> ticks = new ArrayList<>();
        scene.addTicker(dt -> {
            ticks.add(ticks.size());
            throw new IllegalStateException("app ticker bug");
        });
        scene.tickAnimations(); // must not throw
        scene.tickAnimations();
        assertEquals(1, ticks.size(), "the throwing ticker runs once and is dropped");
    }
}
