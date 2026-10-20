package limn.components;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A popover's close runs the application's code (the closes of the popovers hung in it, their
 * {@code onClose}), and that code may show a popover again: the close in progress never takes down
 * what was shown meanwhile, never leaves a layer over the window that nothing owns, and a popover
 * that says it is open is shown.
 */
class PopoverReentrancyTest extends ComponentTestBase {

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

    private long openWindows() {
        return backend.windows().stream().filter(w -> !w.isClosed()).count();
    }

    /** Whether a popover that says it is open is shown: its content in a scene, in a window that is up. */
    private static boolean shown(Popover popover) {
        Scene in = popover.content().scene();
        return in != null && (in.window() == null || !in.window().isClosed());
    }

    private enum Trigger { CODE, APPLICATION_SWITCH, ANCHOR_LEAVES }

    // ------------------------------------------------------------------ an inner onClose showing the outer again

    @Test
    void anInnerOnCloseShowingTheOuterAgainLeavesTheWindowUsableInTheScene() {
        for (Trigger trigger : Trigger.values()) {
            innerOnCloseShowsTheOuterAgain(DisplayMode.IN_SCENE, trigger);
        }
    }

    @Test
    void anInnerOnCloseShowingTheOuterAgainLeavesTheWindowUsableInWindows() {
        for (Trigger trigger : Trigger.values()) {
            innerOnCloseShowsTheOuterAgain(DisplayMode.NATIVE_WINDOW, trigger);
        }
    }

    /**
     * "Back to the main popover when the sub-popover goes": normally nothing, since the outer one
     * is still open. When the outer one is the one closing, its close ran the inner's handler half
     * way through, and the handler's fresh presentation was taken down by the rest of it while the
     * old layer stayed over the window for good, swallowing every press.
     */
    private void innerOnCloseShowsTheOuterAgain(DisplayMode mode, Trigger trigger) {
        heard.clear();
        Button more = new Button("More");
        Column holder = new Column();
        holder.add(more);
        Button base = new Button("Base");
        int[] clicks = {0};
        base.onAction(() -> clicks[0]++);
        Column root = new Column();
        root.add(holder);
        root.add(base);
        root.add(new SizedBox(300, 200, new Label("Orders")));
        inWindow(root);
        Button columns = new Button("Columns");
        Column outerContent = new Column();
        outerContent.add(columns);
        Popover outer = new Popover(outerContent).setDisplayMode(mode).attachTo(more)
                .onClose(origin -> heard.add("outer " + origin));
        Popover inner = new Popover(new SizedBox(100, 30, new TextField())).setDisplayMode(mode).attachTo(columns);
        inner.onClose(origin -> {
            heard.add("inner " + origin);
            outer.showFor(more);
        });
        outer.showFor(more);
        frames(3);
        inner.showFor(columns);
        frames(3);
        assertTrue(outer.isOpen() && inner.isOpen(), "sanity: both open");

        switch (trigger) {
            case CODE -> outer.close();
            case APPLICATION_SWITCH -> {
                owner.desktopFocus(true);
                owner.desktopFocus(false);
            }
            case ANCHOR_LEAVES -> holder.remove(more);
        }
        frames(40);
        String where = mode + " " + trigger + " " + heard;
        assertFalse(inner.isOpen(), where);
        assertEquals(outer.isOpen(), shown(outer), "the outer one is shown exactly when it says it is open: " + where);
        if (trigger == Trigger.ANCHOR_LEAVES) {
            assertFalse(outer.isOpen(), "never shown again from a button on its way out: " + where);
        }

        if (outer.isOpen()) {
            // Shown again during its own close: what it hears is its own, a press outside included.
            drive(scene).click(590, 390);
            frames(40);
            assertFalse(outer.isOpen(), "a press outside closes the one shown again: " + where);
            clicks[0] = 0;
        }
        outer.close();
        frames(40);
        drive(scene).click(base);
        frames(3);
        assertEquals(1, clicks[0], "the window takes a click again, nothing is left over it: " + where);
        assertEquals(1, openWindows(), "the owner's window alone: " + where);
        outer.showFor(more.scene() != null ? more : base);
        frames(3);
        assertTrue(outer.isOpen() && shown(outer), "and the outer one opens and shows again: " + where);
        owner.requestClose();
        runtime.drain();
    }

    // ------------------------------------------------------------------ onClose showing the same popover elsewhere

    @Test
    void aPopoverShownElsewhereByItsOwnOnCloseAsItsAnchorLeavesIsShownThere() {
        for (DisplayMode mode : DisplayMode.values()) {
            // A walkthrough reusing one popover from step to step: the step's anchor goes, and
            // its handler moves the popover on. The close that followed took the new presentation
            // down, and the popover said it was open with nothing shown, refusing every showFor.
            heard.clear();
            Button first = new Button("First");
            Button next = new Button("Next");
            Column holder = new Column();
            holder.add(first);
            Column root = new Column();
            root.add(holder);
            root.add(next);
            inWindow(root);
            Popover[] self = new Popover[1];
            self[0] = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(mode).onClose(origin -> {
                heard.add("P " + origin);
                if (origin == Change.Origin.ADJUSTMENT) {
                    self[0].showFor(next);
                }
            });
            self[0].showFor(first);
            frames(3);
            holder.remove(first);
            frames(40);
            assertEquals(List.of("P ADJUSTMENT"), heard, mode.toString());
            assertTrue(self[0].isOpen(), mode + ": shown for the next step");
            assertTrue(shown(self[0]), mode + ": and it is on screen");
            self[0].close();
            frames(40);
            assertFalse(shown(self[0]), mode + ": closed, gone");
            owner.requestClose();
            runtime.drain();
        }
    }

    @Test
    void aPopoverShownAgainForTheAnchorThatIsLeavingStaysClosed() {
        for (DisplayMode mode : DisplayMode.values()) {
            heard.clear();
            Button first = new Button("First");
            Column holder = new Column();
            holder.add(first);
            Column root = new Column();
            root.add(holder);
            inWindow(root);
            Popover[] self = new Popover[1];
            self[0] = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(mode).onClose(origin -> {
                heard.add("P " + origin);
                self[0].showFor(first); // still answers its scene while it leaves
            });
            self[0].showFor(first);
            frames(3);
            holder.remove(first);
            frames(40);
            assertFalse(self[0].isOpen(), mode + ": a widget on its way out is nothing to hang from");
            assertNull(self[0].content().scene(), mode.toString());
            Button again = new Button("Again");
            root.add(again);
            frames(2);
            self[0].showFor(again);
            frames(3);
            assertTrue(self[0].isOpen() && shown(self[0]), mode + ": it opens from a widget in the window");
            owner.requestClose();
            runtime.drain();
        }
    }

    @Test
    void anInnerOnCloseThatThrowsStillLetsTheOuterOneCloseInTheScene() {
        for (String how : List.of("code", "hide", "remove")) {
            innerOnCloseThrows(DisplayMode.IN_SCENE, how);
        }
    }

    @Test
    void anInnerOnCloseThatThrowsStillLetsTheOuterOneCloseInWindows() {
        for (String how : List.of("code", "hide", "remove")) {
            innerOnCloseThrows(DisplayMode.NATIVE_WINDOW, how);
        }
    }

    /**
     * A bug in an inner popover's handler, once: the outer one's close stopped at the throw, its
     * fields already cleared, and its layer covered the window for good (its window stayed open).
     * Every close and every teardown runs, and the throw comes after.
     */
    private void innerOnCloseThrows(DisplayMode mode, String how) {
        heard.clear();
        Button more = new Button("More");
        Column holder = new Column();
        holder.add(more);
        Button base = new Button("Base");
        int[] clicks = {0};
        base.onAction(() -> clicks[0]++);
        Column root = new Column();
        root.add(holder);
        root.add(new SizedBox(300, 120, new Label("Orders")));
        root.add(base);
        inWindow(root);
        Button columns = new Button("Columns");
        Column outerContent = new Column();
        outerContent.add(columns);
        Popover outer = new Popover(outerContent).setDisplayMode(mode).attachTo(more)
                .onClose(origin -> heard.add("outer " + origin));
        Popover inner = new Popover(new SizedBox(100, 30, new TextField())).setDisplayMode(mode).attachTo(columns)
                .onClose(origin -> {
                    heard.add("inner " + origin);
                    throw new IllegalStateException("an inner handler's bug");
                });
        outer.showFor(more);
        frames(3);
        inner.showFor(columns);
        frames(3);
        RuntimeException thrown = null;
        try {
            switch (how) {
                case "code" -> outer.close();
                case "hide" -> holder.setVisible(false);
                default -> holder.remove(more);
            }
        } catch (IllegalStateException e) {
            thrown = e;
        }
        String where = mode + " " + how + " " + heard;
        assertNotNull(thrown, "the handler's throw is not swallowed: " + where);
        assertFalse(outer.isOpen() || inner.isOpen(), where);
        assertTrue(heard.contains("outer " + (how.equals("code") ? "CODE" : "ADJUSTMENT")),
                "the outer one's own handler still ran: " + where);
        frames(40);
        drive(scene).click(base);
        frames(3);
        assertEquals(1, clicks[0], "nothing is left over the window: " + where);
        assertEquals(1, openWindows(), "the owner's window alone: " + where);
        owner.requestClose();
        runtime.drain();
    }

    @Test
    void anApplicationCodeThrowingWhileAnAnchorLeavesStillLetsTheRemovalFinish() {
        Button first = new Button("First");
        Column holder = new Column();
        holder.add(first);
        Column root = new Column();
        root.add(holder);
        inWindow(root);
        Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(DisplayMode.IN_SCENE)
                .onClose(origin -> {
                    throw new IllegalStateException("an application's handler bug");
                });
        popover.showFor(first);
        frames(3);
        IllegalStateException thrown = null;
        try {
            holder.remove(first);
        } catch (IllegalStateException e) {
            thrown = e;
        }
        assertNotNull(thrown, "the handler's throw is not swallowed");
        assertNull(first.scene(), "the removal finished: the button left the window");
        assertNull(first.parent());
        assertFalse(popover.isOpen());
        assertTrue(holder.children().isEmpty());
        owner.requestClose();
        runtime.drain();
    }

    @Test
    void anObserverThrowingAfterARemovalDoesNotHideTheHandlersThrow() {
        Button first = new Button("First");
        Column holder = new Column();
        holder.add(first);
        Column root = new Column();
        root.add(holder);
        inWindow(root);
        holder.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.CHILDREN) {
                throw new IllegalArgumentException("an observer's bug");
            }
        });
        Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(DisplayMode.IN_SCENE)
                .onClose(origin -> {
                    throw new IllegalStateException("an application's handler bug");
                });
        popover.showFor(first);
        frames(3);
        RuntimeException thrown = null;
        try {
            holder.remove(first);
        } catch (RuntimeException e) {
            thrown = e;
        }
        assertNotNull(thrown);
        // The change channel contains an observer's throw; whatever else throws as the removal
        // completes rides along with the handler's, which is the one that leaves.
        assertEquals("an application's handler bug", thrown.getMessage(), "the handler's throw, not hidden");
        assertNull(first.scene());
        owner.requestClose();
        runtime.drain();
    }

    @Test
    void addingAWidgetFromAnotherSceneCompletesWhenAHandlerThrows() {
        Button first = new Button("First");
        Column elsewhere = new Column();
        elsewhere.add(first);
        Scene other = new Scene(elsewhere, () -> now[0]);
        other.setTextRuler(RULER);
        other.layoutPass(400, 300);
        Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(DisplayMode.IN_SCENE)
                .onClose(origin -> {
                    throw new IllegalStateException("an application's handler bug");
                });
        popover.showFor(first);
        assertTrue(popover.isOpen(), "sanity");
        Column root = new Column();
        inWindow(root);
        List<Change.Aspect> heardOnRoot = new ArrayList<>();
        root.observeChanges((source, change) -> heardOnRoot.add(change.aspect()));
        IllegalStateException thrown = null;
        try {
            root.add(elsewhere); // a scene's root, adopted by another
        } catch (IllegalStateException e) {
            thrown = e;
        }
        assertNotNull(thrown, "the handler's throw is not swallowed");
        assertSame(root, elsewhere.parent());
        assertSame(scene, first.scene(), "the move completed");
        assertTrue(heardOnRoot.contains(Change.Aspect.CHILDREN), "and was announced: " + heardOnRoot);
        assertFalse(popover.isOpen());
        owner.requestClose();
        runtime.drain();
    }

    @Test
    void aVirtualMachineErrorInAHandlerLeavesAtOnce() {
        Button first = new Button("First");
        Button second = new Button("Second");
        Column holder = new Column();
        holder.add(first);
        holder.add(second);
        Column root = new Column();
        root.add(holder);
        inWindow(root);
        Popover p1 = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(DisplayMode.NATIVE_WINDOW)
                .onClose(origin -> {
                    throw new StackOverflowError("deep");
                });
        Popover p2 = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(DisplayMode.NATIVE_WINDOW)
                .onClose(origin -> heard.add("second " + origin));
        p1.showFor(first);
        p2.showFor(second);
        frames(3);
        StackOverflowError thrown = null;
        try {
            root.remove(holder);
        } catch (StackOverflowError e) {
            thrown = e;
        }
        assertNotNull(thrown, "it propagates as it is");
        assertEquals(List.of(), heard, "and no more application code runs after it");
    }
    @Test
    void aFadeLeavingWithAnAnchorWhosePopoverThrowsStillArrives() {
        // A card fading in, a button on it with its popover open, and the card taken out mid-fade by
        // a removal or with the overlay it is in: the popover's onClose has a bug and throws. The
        // removal completes and rethrows that throw, and the fade's onArrive, which a leaving runs
        // once the change is over, still runs once, as it would without the throw.
        for (boolean asOverlay : new boolean[] {false, true}) {
            Button filter = new Button("Filter");
            int[] arrived = {0};
            limn.scene.layout.Opacity card = new limn.scene.layout.Opacity(filter).setOpacity(0.2f);
            Column holder = new Column();
            Column root = new Column();
            root.add(holder);
            root.add(new Button("Base"));
            inWindow(root);
            if (asOverlay) {
                scene.pushOverlay(card);
            } else {
                holder.add(card);
            }
            frames(2);
            card.fadeTo(1f, 1.0, () -> arrived[0]++);
            frames(2);
            List<Change.Aspect> told = new ArrayList<>();
            holder.observeChanges((source, change) -> told.add(change.aspect()));
            Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(DisplayMode.IN_SCENE)
                    .onClose(origin -> {
                        throw new IllegalStateException("an application's handler bug");
                    });
            popover.showFor(filter);
            frames(3);
            assertTrue(popover.isOpen(), "sanity");
            String where = asOverlay ? "its overlay removed" : "removed";
            IllegalStateException thrown = null;
            try {
                if (asOverlay) {
                    scene.removeOverlay(card);
                } else {
                    holder.remove(card);
                }
            } catch (IllegalStateException e) {
                thrown = e;
            }
            assertNotNull(thrown, where + ": the handler's throw is not swallowed");
            assertEquals("an application's handler bug", thrown.getMessage(), where);
            assertNull(filter.scene(), where + ": the change completed");
            assertFalse(popover.isOpen(), where);
            assertEquals(1, arrived[0], where + ": the fade's arrival ran once all the same");
            if (!asOverlay) {
                assertEquals(List.of(Change.Aspect.CHILDREN), told, where + ": the removal was announced");
            }
            frames(40);
            assertEquals(1, arrived[0], where + ": and only once");
            owner.requestClose();
            runtime.drain();
        }
    }

    /** A column counting what it is told as it joins and leaves a scene. */
    private static final class Counted extends Column {
        int attached;
        int detached;

        @Override
        protected void onAttached() {
            attached++;
        }

        @Override
        protected void onDetached() {
            detached++;
        }
    }

    @Test
    void anOnClosePuttingTheRemovedCardBackLeavesItInTheScene() {
        // A card with a "Filter" button whose popover is open, removed from its column; the
        // popover's onClose "puts the card back", into the same column or into another one of the
        // window. The card ends where the handler put it and in the window's scene, told it joined
        // again: a card left in the tree with no scene took clicks, but no popover ever opened from
        // its buttons again and nothing it registered as it joined was registered.
        for (boolean other : new boolean[] {false, true}) {
            String where = other ? "put back into another column" : "put back into its own column";
            Button filter = new Button("Filter");
            int[] clicks = {0};
            filter.onAction(() -> clicks[0]++);
            Counted card = new Counted();
            card.add(filter);
            Column holder = new Column();
            Column elsewhere = new Column();
            Column root = new Column();
            root.add(holder);
            root.add(elsewhere);
            root.add(new Button("Base"));
            inWindow(root);
            holder.add(card);
            frames(2);
            assertEquals(1, card.attached, "sanity");
            boolean[] once = {false};
            Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(DisplayMode.IN_SCENE)
                    .onClose(origin -> {
                        if (!once[0]) {
                            once[0] = true;
                            (other ? elsewhere : holder).add(card);
                        }
                    });
            popover.showFor(filter);
            frames(3);
            assertTrue(popover.isOpen(), "sanity");

            holder.remove(card);
            frames(3);

            assertFalse(popover.isOpen(), where);
            assertSame(other ? elsewhere : holder, card.parent(), where);
            assertSame(scene, card.scene(), where + ": in the window's scene, as its place in the tree says");
            assertSame(scene, filter.scene(), where);
            assertEquals(2, card.attached, where + ": told it joined again");
            assertEquals(1, card.detached, where);
            Popover again = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(DisplayMode.IN_SCENE);
            again.showFor(filter);
            frames(3);
            assertTrue(again.isOpen(), where + ": a popover opens from its button again");
            again.close();
            frames(40);
            owner.requestClose();
            runtime.drain();
        }
    }

    @Test
    void anOnCloseTakingAnAdoptedCardOutAgainLeavesItOutOfEveryScene() {
        // The mirror case: a card that is another window's root, a popover open from its button, is
        // adopted into this window's column; the popover's onClose, run as the card leaves the other
        // scene, takes the card out of the column again. The card ends out of the tree and out of
        // every scene, told it left as often as it was told it joined: it stayed attached to this
        // scene, parentless, listed by it and never detached.
        Button filter = new Button("Filter");
        Counted card = new Counted();
        card.add(filter);
        Column holder = new Column();
        Column root = new Column();
        root.add(holder);
        inWindow(root);
        HeadlessWindow second = backend.open("Details", 300, 200);
        Scene other = new Scene(card, () -> now[0]);
        other.setTextRuler(RULER);
        other.bind(second);
        second.frame();
        Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(DisplayMode.IN_SCENE)
                .onClose(origin -> holder.remove(card));
        popover.showFor(filter);
        frames(3);
        assertTrue(popover.isOpen(), "sanity");

        holder.add(card);
        frames(3);

        assertFalse(popover.isOpen());
        assertNull(card.parent(), "where the handler left it");
        assertNull(card.scene(), "out of every scene, as its place in the tree says");
        assertNull(filter.scene());
        assertEquals(card.attached, card.detached,
                "told it left as often as it joined: attached " + card.attached + ", detached " + card.detached);
        owner.requestClose();
        second.requestClose();
        runtime.drain();
    }

    /** A column that records the scene it answers each time it is told it joins or leaves one. */
    private static final class Seen extends Column {
        final List<Scene> joined = new ArrayList<>();
        final List<Scene> left = new ArrayList<>();

        @Override
        protected void onAttached() {
            joined.add(scene());
        }

        @Override
        protected void onDetached() {
            left.add(scene());
        }
    }

    @Test
    void anOnCloseMovingTheRemovedCardIntoAnotherWindowTellsItItLeftTheFirst() {
        // A card with an open popover, removed from its column; the popover's onClose puts it into
        // a column of a second window. The card was moved between the two scenes by a walk that
        // told it nothing, and then told it left the second window: it never heard that it left the
        // first, so what its onAttached registered there, given back through scene(), was given
        // back to the second. It is told it left the first window and joined the second, and ends
        // in the second, a popover opening from its button there.
        Button filter = new Button("Filter");
        Seen card = new Seen();
        card.add(filter);
        Column holder = new Column();
        Column root = new Column();
        root.add(holder);
        root.add(new Button("Base"));
        inWindow(root);
        HeadlessWindow second = backend.open("Details", 300, 200);
        Column elsewhere = new Column();
        Scene other = new Scene(elsewhere, () -> now[0]);
        other.setTextRuler(RULER);
        other.bind(second);
        second.frame();
        holder.add(card);
        frames(2);
        card.joined.clear();
        Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(DisplayMode.IN_SCENE)
                .onClose(origin -> elsewhere.add(card));
        popover.showFor(filter);
        frames(3);
        assertTrue(popover.isOpen(), "sanity");

        holder.remove(card);
        frames(3);

        assertSame(elsewhere, card.parent());
        assertSame(other, card.scene());
        assertSame(other, filter.scene());
        assertTrue(card.left.contains(scene), "told it left the first window: " + card.left);
        assertSame(other, card.joined.get(card.joined.size() - 1), "and that it joined the second");
        assertEquals(card.joined.size(), card.left.size(), "told it joined as often as it left, after its first join: "
                + card.joined + " " + card.left);
        Popover again = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(DisplayMode.IN_SCENE);
        again.showFor(filter);
        frames(3);
        assertTrue(again.isOpen(), "a popover opens from its button in the second window");
        again.close();
        frames(40);
        owner.requestClose();
        second.requestClose();
        runtime.drain();
    }
}
