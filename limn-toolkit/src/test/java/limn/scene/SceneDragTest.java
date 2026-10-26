package limn.scene;

import limn.graphics.Canvas;
import limn.input.Keys;
import limn.scene.event.DragEvent;
import limn.scene.event.DragStartEvent;
import limn.scene.event.DropEvent;
import limn.scene.event.MouseEvent;
import limn.testing.HeadlessUi;
import limn.transfer.DataFormat;
import limn.transfer.Drag;
import limn.transfer.DropAction;
import limn.transfer.Transfer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A drag the scene drives (ADR 054 §2 and §3): a press that travels asks where it began, the
 * widget under the pointer accepts or its parent does, the release is the drop, Escape and a lost
 * window are not; and the keyboard does the same with the drag key, Tab, the arrows and Enter.
 */
class SceneDragTest {

    private static final DataFormat<String> CARD = DataFormat.inProcess("application/x-limn-test-card", String.class);

    private final List<String> log = new ArrayList<>();
    private HeadlessUi ui;
    private Scene scene;
    private Box source;
    private Box shelf;
    private Box plain;
    private Row row;
    private java.util.Locale localeBefore;

    /**
     * A box that drags a card when it holds one, and accepts cards when it is a shelf; one that
     * marks its refusal shows, without accepting, that it takes no card, as the picker's boards do.
     */
    private final class Box extends Widget<Box> {
        final String name;
        String card;
        boolean takes;
        boolean rejects;
        boolean marksRefusal;
        boolean refusing;
        int exits;
        int place;
        int paints;

        Box(String name) {
            this.name = name;
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(100, 60);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            paints++;
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() != MouseEvent.Type.MOVE && event.type() != MouseEvent.Type.ENTER
                    && event.type() != MouseEvent.Type.EXIT) {
                log.add(name + ":" + event.type());
            }
        }

        @Override
        protected Drag onDragStart(DragStartEvent event) {
            if (card == null) {
                return null;
            }
            log.add(name + ":START" + (event.isKeyboard() ? ":keys" : "@" + (int) event.x()));
            return Drag.of(Transfer.of(CARD, card)).allow(DropAction.MOVE, DropAction.COPY)
                    .describedAs(card).onEnd(action -> {
                        log.add(name + ":END:" + action);
                        if (action == DropAction.MOVE) {
                            card = null;
                        }
                    });
        }

        @Override
        protected void onDragOver(DragEvent event) {
            if (takes && event.offer().has(CARD)) {
                place = Math.max(0, place + event.stepY());
                event.accept();
                if (event.isKeyboard()) {
                    event.describePlace(name + " place " + place);
                }
            } else if (marksRefusal) {
                refusing = true; // the red edge: says nothing, so the parent is asked next
            }
        }

        @Override
        protected void onDragExit(DragEvent event) {
            exits++;
            refusing = false;
            log.add(name + ":EXIT");
        }

        @Override
        protected void onDrop(DropEvent event) {
            if (rejects) {
                event.reject();
                return;
            }
            event.offer().read(CARD).thenAccept(value -> {
                card = value;
                log.add(name + ":DROP:" + value + ":" + event.action() + (event.source() == source ? ":from-source" : ""));
            });
        }
    }

    /**
     * Three boxes side by side, 100 wide each: the source, a shelf that accepts, a plain one. It
     * takes a card only when told to, the drop none of its children wants, and otherwise marks
     * that it refuses one when told to, as a panel around widgets that say nothing would.
     */
    private final class Row extends Widget<Row> {
        boolean takes;
        boolean marksRefusal;
        boolean refusing;
        int exits;
        String dropped;

        void put(Widget<?> child) {
            add(child);
        }

        @Override
        protected void onDragOver(DragEvent event) {
            if (takes && event.offer().has(CARD)) {
                event.accept();
            } else if (marksRefusal) {
                refusing = true;
            }
        }

        @Override
        protected void onDragExit(DragEvent event) {
            exits++;
            refusing = false;
        }

        @Override
        protected void onDrop(DropEvent event) {
            event.offer().read(CARD).thenAccept(value -> dropped = value);
        }

        void take(Widget<?> child) {
            remove(child);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), 60);
        }

        @Override
        protected void onPaint(Canvas canvas) {
        }

        @Override
        protected void onLayout() {
            float x = 0;
            for (Widget<?> child : children()) {
                child.measure(Constraints.loose(100, height()));
                child.layoutBox(x, 0, 100, height());
                x += 100;
            }
        }
    }

    @BeforeEach
    void build() {
        ui = new HeadlessUi();
        localeBefore = limn.i18n.I18n.locale();
        limn.i18n.I18n.setLocale(java.util.Locale.ENGLISH);
        source = new Box("source");
        source.card = "ace";
        shelf = new Box("shelf");
        shelf.takes = true;
        plain = new Box("plain");
        row = new Row();
        row.put(source);
        row.put(shelf);
        row.put(plain);
        scene = new Scene(row);
        scene.layoutPass(300, 60);
    }

    @AfterEach
    void close() {
        limn.i18n.I18n.setLocale(localeBefore);
        ui.close();
    }

    private void press(float x) {
        drive(scene).mouseMoved(x, 30);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, 30, 1);
        drive(scene).inputBatchEnded();
    }

    private void move(float x) {
        drive(scene).mouseMoved(x, 30);
        drive(scene).inputBatchEnded();
    }

    private void release(float x, int modifiers) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, modifiers, x, 30, 1);
        drive(scene).inputBatchEnded();
    }

    private void key(int key, int modifiers) {
        drive(scene).keyEvent(key, 0, true, false, modifiers);
        drive(scene).keyEvent(key, 0, false, false, modifiers);
        drive(scene).inputBatchEnded();
    }

    @Test
    void aPressThatTravelsBeginsADragAndTheReleaseDropsItOnTheWidgetThatAccepted() {
        press(50);
        move(52);
        assertFalse(scene.isDragging(), "two points are not a drag");
        move(60);
        assertTrue(scene.isDragging());
        assertEquals(List.of("source:PRESS", "source:DRAG", "source:START@50", "source:RELEASE"), log,
                "asked where the press began, and the press is over for the widget that had it");
        log.clear();

        move(150);
        release(150, 0);
        // The drag began over the source, which was asked first and refused its own card: it
        // hears that the pointer left it (ADR 054, amended 2026-10-26).
        assertEquals(List.of("source:EXIT", "shelf:DROP:ace:MOVE:from-source", "source:END:MOVE"), log,
                "no click, no exit after a drop, and the source hears what was done");
        assertEquals("ace", shelf.card);
        assertEquals(null, source.card);
        assertFalse(scene.isDragging());
    }

    @Test
    void whatIsDraggedIsPaintedAgainUnderThePointerWhileTheDragLasts() {
        Canvas canvas = new limn.testing.NoopCanvas(300, 60);
        scene.renderFrame(canvas);
        assertEquals(1, source.paints);
        press(50);
        move(150);
        scene.renderFrame(canvas);
        assertEquals(3, source.paints, "once in its place, once under the pointer");
        assertEquals(2, shelf.paints, "nothing else is painted twice");
        release(150, 0);
        scene.renderFrame(canvas);
        assertEquals(4, source.paints, "the drag over, so is its picture");
    }

    @Test
    void thePictureSurvivesAFrameThatRepaintsOnlyTheTarget() {
        Canvas canvas = new limn.testing.NoopCanvas(300, 60);
        plain.takes = true; // the box that does not touch the source's
        scene.setPartialRendering(true);
        scene.renderFrame(canvas);
        scene.renderFrame(canvas); // history settled
        press(50);
        move(250);
        scene.renderFrame(canvas, false, Float.NaN, 1);
        scene.renderFrame(canvas, false, Float.NaN, 1);
        source.paints = 0;
        plain.paints = 0;
        // The target marks where the drop would land and repaints itself. The source, a hundred
        // points away, is not part of that frame: a partial frame skips a widget whose own place
        // misses what is repainted. Its picture under the pointer must not be skipped with it.
        plain.invalidate();
        scene.renderFrame(canvas, false, Float.NaN, 1);
        assertEquals(1, plain.paints);
        assertEquals(1, source.paints, "under the pointer, and not in its own place, which did not change");
    }

    @Test
    void aWidgetThatAnswersNoDragKeepsItsOrdinaryDragEvents() {
        press(250);
        move(280);
        release(280, 0);
        assertEquals(List.of("plain:PRESS", "plain:DRAG", "plain:RELEASE", "plain:CLICK"), log);
    }

    @Test
    void leavingTheWidgetThatAcceptedTellsItAndADropNowhereTellsTheSourceNothingWasDone() {
        press(50);
        move(150);
        move(250);
        assertEquals("shelf:EXIT", log.get(log.size() - 1));
        release(250, 0);
        assertEquals("source:END:null", log.get(log.size() - 1));
        assertEquals("ace", source.card, "nothing took it: it stays");
    }

    @Test
    void theModifierHeldAtTheDropChoosesAmongWhatTheSourceAllows() {
        press(50);
        move(150);
        drive(scene).keyEvent(Keys.LEFT_CONTROL, 0, true, false,
                limn.backend.Platform.current().isMacOs() ? Keys.MOD_ALT : Keys.MOD_CONTROL);
        drive(scene).inputBatchEnded();
        release(150, limn.backend.Platform.current().isMacOs() ? Keys.MOD_ALT : Keys.MOD_CONTROL);
        assertTrue(log.contains("shelf:DROP:ace:COPY:from-source"), log.toString());
        assertEquals("ace", source.card, "a copy leaves the source its own");
    }

    @Test
    void escapeAndALostWindowCallTheDragOffAndADropTheTargetRefusesIsNoDrop() {
        press(50);
        move(150);
        key(Keys.ESCAPE, 0);
        assertFalse(scene.isDragging());
        assertEquals(List.of("shelf:EXIT", "source:END:null"), log.subList(log.size() - 2, log.size()));
        release(150, 0);

        log.clear();
        press(50);
        move(150);
        drive(scene).windowFocusChanged(false);
        drive(scene).inputBatchEnded();
        assertTrue(log.contains("source:END:null"), log.toString());
        drive(scene).windowFocusChanged(true);

        log.clear();
        shelf.rejects = true;
        press(50);
        move(150);
        release(150, 0);
        assertEquals("source:END:null", log.get(log.size() - 1));
    }

    @Test
    void theSourceLeavingTheSceneEndsItsDrag() {
        press(50);
        move(150);
        row.take(source);
        assertFalse(scene.isDragging());
        assertTrue(log.contains("source:END:null"), log.toString());
    }

    /** The page the drag began on replaced mid-drag: the drag ends, and the new page hears no drop. */
    @Test
    void aNewRootEndsADragTheOldOneStarted() {
        press(50);
        move(150);
        Box next = new Box("next");
        next.takes = true;
        log.clear();

        scene.setRoot(next);

        assertFalse(scene.isDragging());
        assertTrue(log.contains("source:END:null"), log.toString());
        scene.layoutPass(300, 60);
        release(50, 0);
        assertFalse(log.stream().anyMatch(s -> s.startsWith("next:DROP")), log.toString());
    }

    @Test
    void theKeyboardPicksUpWalksTheWidgetsThatAcceptChoosesAPlaceAndDrops() {
        Box second = new Box("second");
        second.takes = true;
        row.put(second);
        scene.layoutPass(400, 60);
        scene.requestFocus(source);
        int mods = Keys.MOD_SHIFT | (limn.backend.Platform.current().isMacOs() ? Keys.MOD_SUPER : Keys.MOD_CONTROL);

        key(Keys.D, mods);
        assertTrue(scene.isDragging());
        assertEquals(List.of("source:START:keys", "source:EXIT"), log,
                "asked first whether it takes its own card, the source refused, and the drag moved on");
        List<String> said = scene.queuedAnnouncements();
        assertTrue(said.get(0).startsWith("Dragging ace."), said.toString());

        key(Keys.TAB, 0);
        key(Keys.DOWN, 0);
        key(Keys.DOWN, 0);
        key(Keys.TAB, 0);
        key(Keys.TAB, Keys.MOD_SHIFT);
        said = scene.queuedAnnouncements();
        assertEquals(List.of("shelf place 0", "shelf place 1", "shelf place 2", "second place 0", "shelf place 2"),
                said.subList(1, said.size()), "only the widgets that accept, and the place each keeps");

        key(Keys.ENTER, 0);
        said = scene.queuedAnnouncements();
        assertEquals("Dropped.", said.get(said.size() - 1));
        assertEquals("ace", shelf.card);
        assertEquals("source:END:MOVE", log.get(log.size() - 1));
        assertFalse(scene.isDragging());

        shelf.card = null;
        source.card = "two";
        key(Keys.D, mods);
        key(Keys.ESCAPE, 0);
        said = scene.queuedAnnouncements();
        assertEquals("Drag cancelled.", said.get(said.size() - 1));
        assertEquals("two", source.card);
    }

    // ---- ADR 054 amended (2026-10-26): every widget the drag was over hears onDragExit, accepted
    // or not. Until then only the widget that had accepted did, and the picker's board, which
    // marks in red that it takes no card from that list, kept the red edge after the pointer left
    // it or the drag ended over it (the Fedora KDE guest, 2026-10-25).

    @Test
    void aWidgetThatRefusesWithoutAcceptingHearsOneExitWhenThePointerLeavesIt() {
        plain.marksRefusal = true;
        press(50);
        move(250);
        move(260);
        move(270);
        assertTrue(plain.refusing, "it shows that it takes no card");
        assertEquals(0, plain.exits, "moving within it is not leaving it");

        move(150);
        assertEquals(1, plain.exits, "the pointer left it, for the shelf");
        assertFalse(plain.refusing, "and what it drew went");

        release(150, 0);
        assertEquals("ace", shelf.card);
        assertEquals(1, plain.exits, "a drop elsewhere after it left tells it nothing more");
        assertEquals(0, shelf.exits, "the widget handed the drop hears no exit");
    }

    @Test
    void aDropRefusedOverAWidgetThatRefusesTellsItOnce() {
        plain.marksRefusal = true;
        press(50);
        move(250);
        release(250, 0);
        assertEquals(1, plain.exits);
        assertFalse(plain.refusing);
        assertEquals("source:END:null", log.get(log.size() - 1), "nothing took it");
        assertEquals("ace", source.card);
    }

    @Test
    void escapeOverAWidgetThatRefusesTellsItOnce() {
        plain.marksRefusal = true;
        press(50);
        move(250);
        key(Keys.ESCAPE, 0);
        assertFalse(scene.isDragging());
        assertEquals(1, plain.exits);
        assertFalse(plain.refusing);
        release(250, 0);
        assertEquals(1, plain.exits, "the release after it ends nothing more");
    }

    /**
     * Which widgets hear it: those the last question reached, from the widget under the pointer
     * outward through each ancestor asked, up to the one that accepted. A panel around children
     * that say nothing is asked wherever the pointer is over them, and told when a child under
     * the pointer answers before it, as when the pointer leaves it.
     */
    @Test
    void aPanelThatRefusesIsToldWhenAChildAnswersFirstAndNotWhileThePointerCrossesItsChildren() {
        row.marksRefusal = true;
        press(50);
        move(60);
        assertTrue(row.refusing, "over the source, which says nothing, the panel is asked and refuses");

        move(250);
        assertEquals(1, source.exits, "the pointer left the source");
        assertEquals(0, row.exits, "still over the panel, still asked");
        assertTrue(row.refusing);

        move(150);
        assertEquals(1, row.exits, "the shelf answered before the panel was asked");
        assertFalse(row.refusing);
        assertEquals(1, plain.exits);

        release(150, 0);
        assertEquals("ace", shelf.card);
        assertEquals(0, shelf.exits, "the widget handed the drop hears no exit");
        assertEquals(1, row.exits);
        assertEquals(1, plain.exits);
        assertEquals(1, source.exits);
    }

    @Test
    void aWidgetUnderTheTargetThatRefusedHearsTheExitAtTheDropAndTheTargetDoesNot() {
        row.takes = true;
        plain.marksRefusal = true;
        press(50);
        move(250);
        assertTrue(plain.refusing, "it says nothing: the panel around it takes the card");
        release(250, 0);
        assertEquals("ace", row.dropped);
        assertEquals(1, plain.exits, "it was asked, and the drop is not its");
        assertFalse(plain.refusing);
        assertEquals(0, row.exits, "the panel was handed the drop");
    }

    @Test
    void aWidgetHiddenWhileADragIsOverItHearsThatTheDragLeftIt() {
        plain.marksRefusal = true;
        press(50);
        move(250);
        plain.setVisible(false);
        assertEquals(1, plain.exits, "as the hovered widget hears its exit");
        assertFalse(plain.refusing, "so that its mark is not there when it is shown again");
        move(260);
        release(150, 0);
        assertEquals(1, plain.exits);
    }

    @Test
    void theKeyboardTellsAWidgetItWalksPastAtOnceAndItsTargetWhenTheDragMovesOnOrEnds() {
        plain.marksRefusal = true;
        scene.requestFocus(source);
        int mods = Keys.MOD_SHIFT | (limn.backend.Platform.current().isMacOs() ? Keys.MOD_SUPER : Keys.MOD_CONTROL);

        key(Keys.D, mods);
        assertEquals(1, source.exits, "asked first, the source refused its own card, and the drag moved on");

        key(Keys.TAB, 0);
        assertEquals(0, shelf.exits, "the shelf took it");
        key(Keys.TAB, 0);
        assertEquals(1, plain.exits, "walked past, a widget that refuses is told at once");
        assertFalse(plain.refusing, "and its refusal is not left drawn");
        assertEquals(0, shelf.exits, "nothing else took it: it stays on the shelf");

        key(Keys.ESCAPE, 0);
        assertFalse(scene.isDragging());
        assertEquals(1, shelf.exits, "called off: the target hears it once");

        key(Keys.D, mods);
        key(Keys.TAB, 0);
        key(Keys.ENTER, 0);
        assertEquals("ace", shelf.card);
        assertEquals(1, shelf.exits, "the target handed the drop hears no exit");
    }

    @Test
    void aDragFromAnotherWindowOrProgramTellsEveryWidgetItWasOver() {
        plain.marksRefusal = true;
        limn.backend.spi.WindowInput input = limn.scene.internal.SceneAccess.input(scene);
        limn.transfer.Offer cards = Transfer.of(CARD, "joker").asOffer();
        java.util.Set<DropAction> copy = java.util.Set.of(DropAction.COPY);

        input.dragOver(cards, copy, 250, 30);
        input.dragOver(cards, copy, 260, 30);
        assertTrue(plain.refusing);
        assertEquals(0, plain.exits, "moving within it is not leaving it");
        input.dragOver(cards, copy, 150, 30);
        assertEquals(1, plain.exits, "left for the shelf");
        assertFalse(plain.refusing);

        input.dragOver(cards, copy, 250, 30);
        assertEquals(1, shelf.exits, "the shelf, left in turn");
        input.dragLeft();
        assertEquals(2, plain.exits, "the drag left the window");
        assertFalse(plain.refusing);

        input.dragOver(cards, copy, 250, 30);
        assertEquals(null, input.dropped(cards, copy, 250, 30), "nothing takes it there");
        assertEquals(3, plain.exits, "a drop refused over it");
        assertFalse(plain.refusing);

        input.dragOver(cards, copy, 150, 30);
        assertEquals(DropAction.COPY, input.dropped(cards, copy, 150, 30));
        assertEquals("joker", shelf.card);
        assertEquals(1, shelf.exits, "the widget handed the drop hears no exit");
        assertEquals(3, plain.exits);
    }

    @Test
    void filesDroppedWithNoHoverTellEveryWidgetAskedThatIsNotHandedThem() {
        plain.marksRefusal = true;
        drive(scene).filesDropped(List.of(java.nio.file.Path.of("a.txt")), 250, 30);
        drive(scene).inputBatchEnded();
        assertEquals(1, plain.exits, "asked once at the drop, and refusing");
        assertFalse(plain.refusing);
    }
}
