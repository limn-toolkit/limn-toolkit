package limn.components;

import limn.components.date.DatePicker;
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A popup hanging from a widget that stops being shown goes, as one whose widget leaves the window
 * does: the widget hidden itself or with a container, a tab strip showing another page, an expander
 * folding. A popover and a combo box's list, in the scene and in a window of their own; each close
 * is an adjustment.
 */
class AnchoredPopupHiddenAnchorTest extends ComponentTestBase {

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};
    private final List<String> heard = new ArrayList<>();
    private HeadlessBackend backend;
    private HeadlessWindow owner;
    private Scene scene;

    private void frames(int rounds) {
        for (int round = 0; round < rounds; round++) {
            now[0] += TimeUnit.MILLISECONDS.toNanos(16);
            runtime.drain();
            for (HeadlessWindow w : new ArrayList<>(backend.windows())) {
                if (!w.isClosed() && w.scene() != null) {
                    w.frame();
                }
            }
        }
    }

    private void inWindow(Widget<?> root) {
        backend = new HeadlessBackend(runtime);
        owner = backend.open("Orders", 600, 400);
        runtime.setDelayClock(() -> now[0]);
        scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(owner);
        owner.frame();
    }

    @Test
    void aPopoverWhoseButtonIsHiddenCloses() {
        for (DisplayMode mode : DisplayMode.values()) {
            for (boolean byContainer : new boolean[] {false, true}) {
                heard.clear();
                Button filter = new Button("Filter");
                Column holder = new Column();
                holder.add(filter);
                Column root = new Column();
                root.add(holder);
                inWindow(root);
                Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(mode)
                        .attachTo(filter).onClose(origin -> heard.add(origin.toString()));
                drive(scene).click(filter);
                frames(3);
                assertTrue(popover.isOpen(), "sanity");
                (byContainer ? holder : filter).setVisible(false);
                String where = mode + (byContainer ? ", its container hidden" : ", hidden");
                assertFalse(popover.isOpen(), where);
                assertEquals(List.of("ADJUSTMENT"), heard, where);
                frames(40);
                assertEquals(1, backend.windows().stream().filter(w -> !w.isClosed()).count(), where);
                (byContainer ? holder : filter).setVisible(true);
                frames(2);
                drive(scene).click(filter);
                frames(3);
                assertTrue(popover.isOpen(), where + ": shown again, it opens again");
                owner.requestClose();
                runtime.drain();
            }
        }
    }

    @Test
    void aComboBoxWhoseFieldIsHiddenClosesItsList() {
        for (DisplayMode mode : DisplayMode.values()) {
            ComboBox<String> combo = new ComboBox<>(List.of("Open", "Paid", "Late")).setDisplayMode(mode);
            Column holder = new Column();
            holder.add(combo);
            Column root = new Column();
            root.add(holder);
            inWindow(root);
            List<Change.Origin> expanded = new ArrayList<>();
            combo.observeChanges((source, change) -> {
                if (change.aspect() == Change.Aspect.EXPANDED) {
                    expanded.add(change.origin());
                }
            });
            combo.open();
            frames(3);
            assertTrue(combo.isOpen(), "sanity");
            expanded.clear();
            holder.setVisible(false);
            assertFalse(combo.isOpen(), mode + ": the list went with the field");
            assertTrue(expanded.contains(Change.Origin.ADJUSTMENT), mode + ": " + expanded);
            owner.requestClose();
            runtime.drain();
        }
    }

    // ------------------------------------------------------------------ asked to open while hidden

    @Test
    void aComboBoxAskedToOpenWhileHiddenStaysClosed() {
        for (DisplayMode mode : DisplayMode.values()) {
            ComboBox<String> combo = new ComboBox<>(List.of("Open", "Paid", "Late")).setDisplayMode(mode);
            Column section = new Column();
            section.add(combo);
            Column root = new Column();
            root.add(section);
            inWindow(root);
            List<Change.Origin> expanded = new ArrayList<>();
            combo.observeChanges((source, change) -> {
                if (change.aspect() == Change.Aspect.EXPANDED) {
                    expanded.add(change.origin());
                }
            });
            section.setVisible(false);
            combo.open();
            frames(3);
            assertFalse(combo.isOpen(), mode + ": a list that cannot be shown is not open");
            assertEquals(List.of(), expanded, mode + ": nor said to be");
            section.setVisible(true);
            frames(2);
            drive(scene).click(combo);
            frames(3);
            assertTrue(combo.isOpen(), mode + ": the first click once shown opens it");
            owner.requestClose();
            runtime.drain();
        }
    }

    @Test
    void aDatePickerAskedToOpenWhileHiddenStaysClosedAndItsFieldCommitsNothing() {
        for (DisplayMode mode : DisplayMode.values()) {
            DatePicker picker = new DatePicker().setClock(java.time.Clock.fixed(
                    java.time.Instant.parse("2026-09-09T12:00:00Z"), java.time.ZoneOffset.UTC))
                    .setDisplayMode(mode);
            Column section = new Column();
            section.add(new SizedBox(300, 40, picker));
            Column root = new Column();
            root.add(section);
            inWindow(root);
            List<Change.Origin> expanded = new ArrayList<>();
            picker.observeChanges((source, change) -> {
                if (change.aspect() == Change.Aspect.EXPANDED) {
                    expanded.add(change.origin());
                }
            });
            section.setVisible(false);
            picker.open();
            frames(3);
            assertFalse(picker.isOpen(), mode + ": a calendar that cannot be shown is not open");
            assertEquals(List.of(), expanded, mode.toString());
            section.setVisible(true);
            frames(2);
            scene.requestFocus(picker.field());
            drive(scene).press(Keys.DOWN); // the field's own: the day segment steps
            drive(scene).press(Keys.ENTER);
            frames(3);
            assertNull(picker.date(), mode + ": no whole date committed from a calendar never shown: "
                    + picker.field().text());
            owner.requestClose();
            runtime.drain();
        }
    }

    @Test
    void aPopoverIsNotShownFromAButtonATransformDrawsAsNothing() {
        // A card's face scaled to 0: the button on it is not drawn, and the keyboard is not given
        // to a panel hanging from it. Scrolled out of view, a widget can still be shown for.
        Button filter = new Button("Filter");
        limn.scene.layout.Transform face = new limn.scene.layout.Transform(filter).setScale(0);
        Column root = new Column();
        root.add(face);
        inWindow(root);
        Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(DisplayMode.IN_SCENE);
        popover.showFor(filter);
        assertFalse(popover.isOpen(), "not from a button drawn as nothing");
        face.setScale(1);
        frames(2);
        popover.showFor(filter);
        assertTrue(popover.isOpen(), "drawn again, it opens");
    }

    /** The ways a card stops drawing its face: turned away or scaled to 0, by a set or a motion. */
    enum FaceGone {
        TURNED_AWAY {
            @Override
            void undraw(limn.scene.layout.Transform card) {
                card.setRotation3D(limn.math.Vec3.UNIT_Y, 180);
            }
        },
        SCALED_TO_NOTHING {
            @Override
            void undraw(limn.scene.layout.Transform card) {
                card.setScale(0);
            }
        },
        SCALED_TO_NOTHING_BY_A_MOTION {
            @Override
            void undraw(limn.scene.layout.Transform card) {
                card.scaleTo(0, 0, 0.2, null); // the frame that arrives draws nothing
            }
        };

        abstract void undraw(limn.scene.layout.Transform card);
    }

    /** A card a reader turns over by hand, back faces hidden, holding {@code face}. */
    private static limn.scene.layout.Transform card(Widget<?> face) {
        return new limn.scene.layout.Transform(face).setPerspective(600).setBackfaceVisible(false);
    }

    @Test
    void aPopoverOnACardFaceThatStopsBeingDrawnCloses() {
        // A "Filter" button on the front of a card, its popover open, and the card turned over or
        // collapsed: the button is no longer shown, so the panel has nothing to hang from.
        for (DisplayMode mode : DisplayMode.values()) {
            for (FaceGone gone : FaceGone.values()) {
                heard.clear();
                Button filter = new Button("Filter");
                limn.scene.layout.Transform card = card(filter);
                int[] otherClicks = {0};
                Button other = new Button("Other").onAction(() -> otherClicks[0]++);
                Column root = new Column();
                root.add(card);
                root.add(other);
                inWindow(root);
                Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(mode)
                        .attachTo(filter).onClose(origin -> heard.add(origin.toString()));
                drive(scene).click(filter);
                frames(3);
                String where = mode + ", " + gone;
                assertTrue(popover.isOpen(), where + ": sanity");
                gone.undraw(card);
                frames(20);
                assertFalse(filter.isShowing(), where + ": sanity, the face is not drawn");
                assertFalse(popover.isOpen(), where + ": the popover went with the face");
                assertEquals(List.of("ADJUSTMENT"), heard, where);
                frames(40);
                assertEquals(1, backend.windows().stream().filter(w -> !w.isClosed()).count(),
                        where + ": no popup window left");
                drive(scene).click(other);
                assertEquals(1, otherClicks[0], where + ": no layer left over the window");
                card.reset().setPerspective(600).setBackfaceVisible(false);
                frames(2);
                drive(scene).click(filter);
                frames(3);
                assertTrue(popover.isOpen(), where + ": drawn again, it opens again");
                owner.requestClose();
                runtime.drain();
            }
        }
    }

    @Test
    void aComboBoxOnACardFaceThatStopsBeingDrawnClosesItsList() {
        for (DisplayMode mode : DisplayMode.values()) {
            for (FaceGone gone : FaceGone.values()) {
                ComboBox<String> combo = new ComboBox<>(List.of("Open", "Paid", "Late")).setDisplayMode(mode);
                limn.scene.layout.Transform card = card(combo);
                int[] otherClicks = {0};
                Button other = new Button("Other").onAction(() -> otherClicks[0]++);
                Column root = new Column();
                root.add(card);
                root.add(other);
                inWindow(root);
                List<Change.Origin> expanded = new ArrayList<>();
                combo.observeChanges((source, change) -> {
                    if (change.aspect() == Change.Aspect.EXPANDED) {
                        expanded.add(change.origin());
                    }
                });
                combo.open();
                frames(3);
                String where = mode + ", " + gone;
                assertTrue(combo.isOpen(), where + ": sanity");
                expanded.clear();
                gone.undraw(card);
                frames(20);
                assertFalse(combo.isOpen(), where + ": the list went with the face");
                assertEquals(List.of(Change.Origin.ADJUSTMENT), expanded, where);
                frames(40);
                assertEquals(1, backend.windows().stream().filter(w -> !w.isClosed()).count(),
                        where + ": no popup window left");
                drive(scene).click(other);
                assertEquals(1, otherClicks[0], where + ": no layer left over the window");
                owner.requestClose();
                runtime.drain();
            }
        }
    }

    @Test
    void aPopoverWhoseOnCloseThrowsAsItsFaceStopsBeingDrawnStillClosesAndTheFaceLosesTheFocus() {
        // An application's onClose with a bug: a set rethrows it once the revocation is complete,
        // and a motion's frame reports it as a ticker's throw; either way nothing is left behind.
        for (DisplayMode mode : DisplayMode.values()) {
            for (boolean byMotion : new boolean[] {false, true}) {
                Button filter = new Button("Filter");
                limn.scene.layout.Transform card = card(filter);
                int[] otherClicks = {0};
                Button other = new Button("Other").onAction(() -> otherClicks[0]++);
                Column root = new Column();
                root.add(card);
                root.add(other);
                inWindow(root);
                Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(mode)
                        .attachTo(filter).onClose(origin -> {
                            throw new IllegalStateException("an application's onClose throws");
                        });
                drive(scene).click(filter);
                frames(3);
                String where = mode + (byMotion ? ", by a motion" : ", by a set");
                assertTrue(popover.isOpen(), where + ": sanity");
                if (byMotion) {
                    card.scaleTo(0, 0, 0.2, null);
                    frames(20); // the frame's throw is contained: it does not leave the loop
                } else {
                    IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                            IllegalStateException.class, () -> card.setScale(0));
                    assertEquals("an application's onClose throws", thrown.getMessage(), where);
                    frames(20);
                }
                assertFalse(popover.isOpen(), where + ": closed all the same");
                assertFalse(filter.isFocused(), where + ": the face's button does not keep the keyboard");
                frames(40);
                drive(scene).click(other);
                assertEquals(1, otherClicks[0], where + ": no layer left over the window");
                card.setScale(1);
                frames(2);
                assertTrue(filter.isShowing(), where + ": drawn again");
                owner.requestClose();
                runtime.drain();
            }
        }
    }

    @Test
    void aComboBoxOpenedInADialogBeforeItsFirstFrameOpens() {
        ComboBox<String> combo = new ComboBox<>(List.of("One", "Two")).setDisplayMode(DisplayMode.IN_SCENE);
        Column root = new Column();
        root.add(new Label("Orders"));
        inWindow(root);
        new Dialog("Pick one", "").setDisplayMode(DisplayMode.IN_SCENE).setContent(combo).show(scene);
        combo.open(); // before any layout: no size yet, under a card that clips
        assertTrue(combo.isOpen(), "not laid out yet is not hidden");
    }

    @Test
    void aTabSwitchedByAButtonInThePopoverClosesIt() {
        Button details = new Button("Details");
        Column first = new Column();
        first.add(details);
        TabbedPane tabs = new TabbedPane().addTab("One", first).addTab("Two", new Label("Two"));
        inWindow(new SizedBox(500, 300, tabs));
        Button go = new Button("Go to Two");
        go.onAction(() -> tabs.setSelectedIndex(1));
        Column content = new Column();
        content.add(go);
        Popover popover = new Popover(content).setDisplayMode(DisplayMode.IN_SCENE).attachTo(details)
                .onClose(origin -> heard.add(origin.toString()));
        drive(scene).click(details);
        frames(3);
        assertTrue(popover.isOpen(), "sanity");
        drive(scene).press(Keys.SPACE);
        frames(3);
        assertEquals(1, tabs.selectedIndex(), "sanity: the tab switched");
        assertFalse(details.isShowing(), "sanity: its button is on the page out of sight");
        assertFalse(popover.isOpen(), "the popover went with the page");
        assertEquals(List.of("ADJUSTMENT"), heard);
    }

    @Test
    void aPopoverWhoseOnCloseDrawsTheFaceAgainLeavesTheFaceItsFocus() {
        // A card scaled to 0 by a set while a popover is open from a button on its face, and the
        // popover's onClose puts the scale back ("closing the filter restores the card"): the face
        // is drawn again, so the focus on it stays where it is. The set went on to take the focus
        // out of a face that was drawn again, after the handler had undone it. In a window of its
        // own the popover leaves the scene's keyboard on the face; in the scene it holds it, and
        // gives it back before its onClose runs, while the face is still not drawn.
        for (DisplayMode mode : new DisplayMode[] {DisplayMode.NATIVE_WINDOW}) {
            Button filter = new Button("Filter");
            Button second = new Button("Second");
            Column face = new Column();
            face.add(filter);
            face.add(second);
            limn.scene.layout.Transform card = card(face);
            Column root = new Column();
            root.add(card);
            root.add(new Button("Other"));
            inWindow(root);
            boolean[] once = {false};
            Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(mode)
                    .onClose(origin -> {
                        if (!once[0]) {
                            once[0] = true;
                            card.setScale(1);
                        }
                    });
            scene.requestFocus(second);
            popover.showFor(filter);
            frames(3);
            String where = mode.toString();
            assertTrue(popover.isOpen(), where + ": sanity");

            card.setScale(0);
            frames(3);

            assertFalse(popover.isOpen(), where);
            assertEquals(1f, card.scaleX(), where + ": sanity, the handler drew the face again");
            assertTrue(second.isShowing(), where);
            assertTrue(second.isFocused(), where + ": the face drawn again keeps the focus, given back to it in the "
                    + "scene: " + scene.focusedWidget());
            owner.requestClose();
            runtime.drain();
        }
    }

    /** A box its test sizes, which lays its one child out at that size. */
    private static final class Sized extends Widget<Sized> {
        float w = 200;

        Sized(Widget<?> child) {
            add(child);
        }

        void setWidth(float width) {
            w = width;
            markNeedsLayout();
        }

        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints c) {
            children().get(0).measure(limn.scene.Constraints.tight(w, 100));
            return c.constrain(w, 100);
        }

        @Override
        protected void onLayout() {
            children().get(0).layoutBox(0, 0, w, 100);
        }
    }

    @Test
    void aLayoutPassThatStopsAFaceBeingDrawnClosesItsPopoverAfterThePass() {
        // A face turned within a ten-thousandth of a degree of edge-on under a perspective, drawn
        // until its box is resized and the rounding of the new matrix puts it past edge-on; a
        // popover open from a button on it, whose onClose tidies up by taking the card out of its
        // column. The close runs after the layout pass, not inside it: run there, the handler
        // edited the tree a column was laying out, and the frame threw.
        for (DisplayMode mode : DisplayMode.values()) {
            List<Throwable> crashes = new ArrayList<>();
            limn.backend.CrashHandler handler = (phase, error) -> {
                crashes.add(error);
                return true;
            };
            limn.backend.Crashes.install(handler);
            try {
                Button filter = new Button("Filter");
                Column face = new Column();
                face.add(filter);
                Sized box = new Sized(face);
                limn.scene.layout.Transform card = new limn.scene.layout.Transform(box).setPerspective(200)
                        .setRotation3D(limn.math.Vec3.UNIT_Y, 89.99994f);
                Column root = new Column();
                root.add(card);
                root.add(new Button("Other"));
                inWindow(root);
                frames(2);
                String where = mode.toString();
                assertTrue(filter.isShowing(), where + ": sanity, drawn at this width");
                List<String> closes = new ArrayList<>();
                Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(mode)
                        .onClose(origin -> {
                            closes.add(origin.toString());
                            root.remove(card);
                        });
                popover.showFor(filter);
                frames(3);
                assertTrue(popover.isOpen(), where + ": sanity");

                box.setWidth(1000);
                frames(3);
                assertFalse(filter.isShowing() && card.parent() != null, where + ": sanity, not drawn at that one");

                assertEquals(List.of(), crashes, where + ": nothing thrown in the frame");
                assertEquals(List.of("ADJUSTMENT"), closes, where);
                assertFalse(popover.isOpen(), where);
                assertNull(card.parent(), where + ": the handler's removal done");
                owner.requestClose();
                runtime.drain();
            } finally {
                limn.backend.Crashes.uninstall(handler);
            }
        }
    }
}
