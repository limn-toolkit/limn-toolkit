package limn.scene;

import limn.scene.layout.Column;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A widget that is a scene's root, or an overlay on a scene's stack, belongs to that scene, and no
 * other scene, container or new scene takes it while it does: every add of a container, every push
 * of an overlay or a popup, and a new scene over it refuse it with an
 * {@link IllegalArgumentException}, before anything moves. Taken, it left the first scene and
 * joined the second, but the first still named it as its root, or kept it on its stack, and laid
 * it out at its own window's size. A widget that scene has let go of, an overlay taken off the
 * stack or the root of a window that closed, is taken as any parentless widget is.
 */
class AnotherScenesLayerIsRefusedTest extends SceneTestBase {

    /** A box that writes down each time it joins or leaves a scene, and takes children. */
    private static final class Told extends FixedBox {
        final List<String> log;
        final String name;

        Told(String name, List<String> log) {
            super(20, 20);
            this.name = name;
            this.log = log;
        }

        @Override protected void onAttached() {
            log.add("attach " + name);
        }

        @Override protected void onDetached() {
            log.add("detach " + name);
        }
    }

    /** A box that takes the keyboard. */
    private static final class Key extends FixedBox {
        Key() {
            super(20, 20);
            setFocusable(true);
        }
    }

    private enum Layer { ROOT, OVERLAY, POPUP }

    private enum Taker { CONTAINER_ADD, CONTAINER_INSERT, WIDGET_ADD, PUSH_OVERLAY, PUSH_POPUP, NEW_SCENE }

    @Test
    void anotherScenesRootOrOverlayIsRefusedByEveryAddEveryPushAndANewScene() {
        List<String> failures = new ArrayList<>();
        for (Layer layer : Layer.values()) {
            for (Taker taker : Taker.values()) {
                String how = layer + " taken by " + taker;
                List<String> log = new ArrayList<>();
                Told card = new Told("card", log);
                Told inner = new Told("inner", log);
                card.add(inner);
                Scene first;
                if (layer == Layer.ROOT) {
                    first = new Scene(card);
                } else {
                    first = new Scene(new FixedBox(100, 100));
                    if (layer == Layer.OVERLAY) {
                        first.pushOverlay(card);
                    } else {
                        first.pushPopup(card);
                    }
                }
                Column column = new Column();
                Key key = new Key();
                column.add(key);
                FixedBox box = new FixedBox(50, 50);
                column.add(box);
                Scene second = new Scene(column);
                second.requestFocus(key);
                List<Change.Aspect> heard = new ArrayList<>();
                column.observeChanges((source, change) -> heard.add(change.aspect()));
                box.observeChanges((source, change) -> heard.add(change.aspect()));
                log.clear();

                Runnable take = switch (taker) {
                    case CONTAINER_ADD -> () -> column.add(card);
                    case CONTAINER_INSERT -> () -> column.add(0, card);
                    case WIDGET_ADD -> () -> box.add(card);
                    case PUSH_OVERLAY -> () -> second.pushOverlay(card);
                    case PUSH_POPUP -> () -> second.pushPopup(card);
                    case NEW_SCENE -> () -> new Scene(card);
                };
                try {
                    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, take::run, how);
                    assertTrue(refused.getMessage().contains(layer == Layer.ROOT ? "root" : "overlay"),
                            how + ": says why: " + refused.getMessage());
                    assertNull(card.parent(), how + ": no parent");
                    assertSame(first, card.scene(), how + ": still the first scene's");
                    assertSame(first, inner.scene(), how);
                    if (layer == Layer.ROOT) {
                        assertSame(card, first.root(), how);
                    } else {
                        assertTrue(first.holdsAsLayer(card), how + ": still on the first scene's stack");
                    }
                    assertEquals(List.of(), log, how + ": nobody told anything");
                    assertEquals(List.of(key, box), column.children(), how + ": the column unchanged");
                    assertTrue(box.children().isEmpty(), how);
                    assertEquals(List.of(), heard, how + ": nothing announced");
                    assertFalse(second.holdsAsLayer(card), how + ": not on the second scene's stack");
                    assertSame(key, second.focusedWidget(), how + ": the second scene's keyboard where it was");
                    assertFalse(second.isPopup(card), how);
                } catch (AssertionError e) {
                    failures.add(e.getMessage());
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void aScenesOwnOverlayIsRefusedByAContainerOfItsOwnTree() {
        // The same scene: an overlay put into a column of the root's tree would be a layer and a
        // child at once, drawn twice and hit twice.
        List<String> log = new ArrayList<>();
        Told card = new Told("card", log);
        Column root = new Column();
        Scene scene = new Scene(root);
        scene.pushOverlay(card);
        log.clear();

        assertThrows(IllegalArgumentException.class, () -> root.add(card));

        assertNull(card.parent());
        assertTrue(scene.holdsAsLayer(card));
        assertTrue(root.children().isEmpty());
        assertEquals(List.of(), log);
    }

    @Test
    void aWidgetLetGoOfIsTakenAsAnyParentlessWidgetIs() {
        // An overlay taken off the stack, and the root of a window that closed: their scene holds
        // them no longer, and either is taken by a container of another scene as before.
        List<String> log = new ArrayList<>();
        Told overlay = new Told("overlay", log);
        Scene first = new Scene(new FixedBox(100, 100));
        first.pushOverlay(overlay);
        first.removeOverlay(overlay);
        Column column = new Column();
        Scene second = new Scene(column);
        column.add(overlay);
        assertSame(second, overlay.scene(), "an overlay taken off its stack");

        HeadlessBackend backend = new HeadlessBackend(runtime);
        Told root = new Told("root", log);
        Scene closing = new Scene(root);
        closing.setTextRuler(TestRulers.FIXED);
        HeadlessWindow window = backend.open("Closes", 200, 100);
        closing.bind(window);
        window.frame();
        window.close();
        assertNull(root.scene(), "sanity: the closed window's tree left it");
        log.clear();
        column.add(root);
        assertSame(column, root.parent(), "the root of a window that closed");
        assertSame(second, root.scene());
        assertEquals(List.of("attach root"), log);
    }

    @Test
    void aRefusedTakeLeavesAPopupOpenInTheFirstSceneOpen() {
        // A refusal comes before the walk, so it closes nothing on the way: a popup hung from a
        // widget of the refused subtree stays open in the scene that still holds it.
        Key anchor = new Key();
        Column card = new Column();
        card.add(anchor);
        Scene first = new Scene(card);
        List<String> closes = new ArrayList<>();
        FixedBox popup = new FixedBox(40, 40);
        first.pushPopup(popup);
        limn.scene.internal.SceneAccess.observeAnchorLeaving(first, anchor, () -> {
            closes.add("closed");
            first.removeOverlay(popup);
        });
        Column column = new Column();
        new Scene(column);

        assertThrows(IllegalArgumentException.class, () -> column.add(card));

        assertEquals(List.of(), closes, "no popup closed for a move that never happened");
        assertTrue(first.holdsAsLayer(popup));
        assertSame(first, anchor.scene());
    }
    /**
     * A widget that has a parent belongs to that parent, and no scene takes it as a layer or a root
     * while it does: a push of an overlay or a popup and a new scene over it refuse it as a
     * container's add does, before anything moves, whether the parent is in a scene (the same one
     * or another) or in none. Taken, it was a layer of one scene and a child of a container at
     * once, laid out by both, and the container went on drawing and dispatching to it.
     */
    @Test
    void aWidgetWithAParentIsRefusedByEveryPushAndANewScene() {
        List<String> failures = new ArrayList<>();
        for (String where : new String[] {"in this scene", "in another scene", "in no scene"}) {
            for (Taker taker : new Taker[] {Taker.PUSH_OVERLAY, Taker.PUSH_POPUP, Taker.NEW_SCENE}) {
                String how = "a child " + where + " taken by " + taker;
                List<String> log = new ArrayList<>();
                Told card = new Told("card", log);
                Column holder = new Column();
                holder.add(card);
                Key key = new Key();
                Column page = new Column();
                page.add(key);
                Scene taking;
                Scene holding = null;
                if (where.equals("in this scene")) {
                    page.add(holder);
                    taking = new Scene(page);
                    holding = taking;
                } else {
                    taking = new Scene(page);
                    if (where.equals("in another scene")) {
                        holding = new Scene(holder);
                    }
                }
                taking.requestFocus(key);
                List<Change.Aspect> heard = new ArrayList<>();
                holder.observeChanges((source, change) -> heard.add(change.aspect()));
                log.clear();
                Runnable take = switch (taker) {
                    case PUSH_OVERLAY -> () -> taking.pushOverlay(card);
                    case PUSH_POPUP -> () -> taking.pushPopup(card);
                    default -> () -> new Scene(card);
                };
                try {
                    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, take::run, how);
                    assertTrue(refused.getMessage().contains("parent"), how + ": says why: " + refused.getMessage());
                    assertSame(holder, card.parent(), how + ": still its parent's");
                    assertEquals(List.of(card), holder.children(), how);
                    assertSame(holding, card.scene(), how + ": in the scene it was in");
                    assertFalse(taking.holdsAsLayer(card), how + ": not on the stack");
                    assertEquals(List.of(), log, how + ": nobody told anything");
                    assertEquals(List.of(), heard, how + ": nothing announced");
                    assertSame(key, taking.focusedWidget(), how + ": the keyboard where it was");
                } catch (AssertionError failure) {
                    failures.add(failure.getMessage());
                }
            }
        }
        assertEquals(List.of(), failures);
    }

    /**
     * A scene's own overlay pushed on it again, and its own root pushed on it, are refused as
     * another scene's are. Accepted, a double push put the widget on the stack twice, and the first
     * {@code removeOverlay} walked it out of the scene while it stayed on top, the keyboard left on
     * a field in no scene; the root pushed and then taken off walked the window's whole tree out.
     */
    @Test
    void aScenesOwnOverlayOrRootIsRefusedByItsOwnPush() {
        List<String> failures = new ArrayList<>();
        for (String what : new String[] {"its overlay", "its popup", "its root"}) {
            for (Taker taker : new Taker[] {Taker.PUSH_OVERLAY, Taker.PUSH_POPUP}) {
                String how = what + " taken by its own " + taker;
                List<String> log = new ArrayList<>();
                Key opener = new Key();
                Column page = new Column();
                page.add(opener);
                Scene scene = new Scene(page);
                scene.requestFocus(opener);
                Told panel = new Told("panel", log);
                Key inside = new Key();
                panel.add(inside);
                Widget<?> card = panel;
                if (what.equals("its overlay")) {
                    scene.pushOverlay(panel);
                } else if (what.equals("its popup")) {
                    scene.pushPopup(panel);
                } else {
                    card = page;
                }
                Widget<?> focusBefore = scene.focusedWidget();
                int stackBefore = scene.overlays().size();
                log.clear();
                Widget<?> taken = card;
                Runnable take = taker == Taker.PUSH_OVERLAY ? () -> scene.pushOverlay(taken) : () -> scene.pushPopup(taken);
                try {
                    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, take::run, how);
                    assertTrue(refused.getMessage().contains(what.equals("its root") ? "root" : "overlay"),
                            how + ": says why: " + refused.getMessage());
                    assertEquals(stackBefore, scene.overlays().size(), how + ": the stack unchanged");
                    assertSame(focusBefore, scene.focusedWidget(), how + ": the keyboard where it was");
                    assertEquals(List.of(), log, how + ": nobody told anything");
                    assertSame(page, scene.root(), how);
                    assertSame(scene, page.scene(), how + ": the root still in the scene");
                    if (card == panel) {
                        scene.removeOverlay(panel);
                        assertFalse(scene.overlays().contains(panel), how + ": one removeOverlay takes it off");
                        assertNull(panel.scene(), how);
                        assertSame(opener, scene.focusedWidget(), how + ": and gives the opener the keyboard back");
                    }
                } catch (AssertionError failure) {
                    failures.add(failure.getMessage());
                }
            }
        }
        assertEquals(List.of(), failures);
    }

    /** A scene bound to a window that then closed, its tree let go of. */
    private Scene closedScene(HeadlessBackend backend, Widget<?> root) {
        Scene scene = new Scene(root);
        scene.setTextRuler(TestRulers.FIXED);
        HeadlessWindow window = backend.open("Floating", 200, 100);
        scene.bind(window);
        window.frame();
        return scene;
    }

    /**
     * A scene whose window closed and is bound again does not take back a root another scene took
     * meanwhile: a floating tool window's content docked into the main window's column, or made a
     * new window's root, and the same scene then bound to a new window to float it again. It took
     * the root back unasked, the content then the floating scene's root and the dock's child at
     * once. The bind is refused, before anything changes, and works once the content is let go.
     */
    @Test
    void aClosedScenesRootTakenMeanwhileRefusesItsBindAgain() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        for (String taker : new String[] {"a column of another scene", "a new scene", "a column in no scene"}) {
            List<String> log = new ArrayList<>();
            Told content = new Told("content", log);
            Scene floating = closedScene(backend, content);
            backend.windows().get(backend.windows().size() - 1).close();
            assertNull(content.scene(), taker + ": sanity, the close let go of it");
            Column dock = new Column();
            Scene holder = null;
            if (taker.equals("a new scene")) {
                holder = new Scene(content);
            } else {
                dock.add(content);
                if (taker.equals("a column of another scene")) {
                    holder = new Scene(dock);
                }
            }
            log.clear();
            HeadlessWindow again = backend.open("Floating again", 200, 100);
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> floating.bind(again),
                    taker);
            assertTrue(refused.getMessage().contains("root"), taker + ": says why: " + refused.getMessage());
            assertSame(holder, content.scene(), taker + ": still where it was taken");
            assertEquals(List.of(), log, taker + ": nobody told anything");
            assertNull(again.scene(), taker + ": the window was not bound");
            if (holder != null && holder.root() == content) {
                assertSame(content, holder.root(), taker);
            } else {
                assertSame(dock, content.parent(), taker + ": still the dock's");
            }

            // Let go of, it is taken back as any closed scene's root is.
            if (content.parent() != null) {
                dock.remove(content);
            } else {
                continue; // a new scene's root is its scene's until that window closes
            }
            floating.bind(again);
            assertSame(floating, content.scene(), taker + ": bound again once let go of");
        }
    }

    /**
     * Its overlays another scene took meanwhile are that scene's: bound again, the closed scene
     * leaves them off its stack, where they stayed on both stacks at once, and takes back the
     * ones nobody took.
     */
    @Test
    void aClosedScenesOverlayTakenMeanwhileStaysWithItsTaker() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        List<String> log = new ArrayList<>();
        Scene floating = closedScene(backend, new FixedBox(100, 100));
        Told pushedElsewhere = new Told("pushed elsewhere", log);
        Told adopted = new Told("adopted", log);
        Told untouched = new Told("untouched", log);
        floating.pushOverlay(pushedElsewhere);
        floating.pushPopup(adopted);
        floating.pushOverlay(untouched);
        backend.windows().get(backend.windows().size() - 1).close();
        Scene other = new Scene(new FixedBox(100, 100));
        other.pushOverlay(pushedElsewhere);
        Column column = new Column();
        column.add(adopted);
        log.clear();

        HeadlessWindow again = backend.open("Floating again", 200, 100);
        floating.bind(again);

        assertEquals(List.of(untouched), floating.overlays(), "only the one nobody took is back on the stack");
        assertSame(floating, untouched.scene());
        assertSame(other, pushedElsewhere.scene(), "the pushed one stays its taker's");
        assertTrue(other.holdsAsLayer(pushedElsewhere));
        assertFalse(floating.holdsAsLayer(pushedElsewhere));
        assertSame(column, adopted.parent(), "the adopted one stays its column's");
        assertNull(adopted.scene());
        assertFalse(floating.isPopup(adopted));
        assertEquals(List.of("attach untouched"), log);
        floating.removeOverlay(untouched);
        assertNull(untouched.scene(), "and comes off the stack as any overlay does");
    }
}
