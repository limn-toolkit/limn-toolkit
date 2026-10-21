package limn.scene;

import limn.input.Keys;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Column;
import limn.scene.layout.Opacity;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A scene given another root: the way a window shows another page. The old root leaves the scene
 * as a removed widget does, told it left, the keyboard, the pointer and the popups hung in it taken
 * out, and is free for any other taker; the new one joins as a new scene's root does. Before, a
 * scene let go of its root only when its window closed, so the root of a scene that was never
 * bound (one built to measure a widget), or of one replaced on its window by another scene's bind,
 * could never be added anywhere or be another scene's root for the life of the process.
 */
class SceneSetRootTest extends SceneTestBase {

    /** A box that writes down each time it joins or leaves a scene, and what the pointer does to it. */
    private static class Told extends FixedBox {
        final List<String> log;
        final String name;

        Told(String name, List<String> log) {
            super(40, 20);
            this.name = name;
            this.log = log;
        }

        @Override protected void onAttached() {
            log.add("attach " + name);
        }

        @Override protected void onDetached() {
            log.add("detach " + name);
        }

        @Override protected void onMouseEvent(MouseEvent event) {
            if (event.type() != MouseEvent.Type.MOVE) {
                log.add(name + ":" + event.type());
            }
        }
    }

    /** A column that writes down each time it joins or leaves a scene. */
    private static class Page extends Column {
        final List<String> log;
        final String name;

        Page(String name, List<String> log) {
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

    /** A box that takes the keyboard and writes down how it was given it and when it lost it. */
    private static class Key extends FixedBox {
        final List<String> log;
        final String name;

        Key(String name, List<String> log) {
            super(40, 20);
            this.name = name;
            this.log = log;
            setFocusable(true);
        }

        @Override protected void onFocusGained() {
            log.add("gained " + name + (focusArrivedByTraversal() ? " by traversal" : ""));
        }

        @Override protected void onFocusLost() {
            log.add("lost " + name);
        }
    }

    private final List<String> log = new ArrayList<>();

    // ---------------------------------------------------------------- the swap

    @Test
    void theOldRootLeavesThenTheNewOneJoins() {
        Told a = new Told("a", log);
        a.add(new Told("a.inner", log));
        Told b = new Told("b", log);
        b.add(new Told("b.inner", log));
        Scene scene = new Scene(a);
        log.clear();

        scene.setRoot(b);

        assertEquals(List.of("detach a.inner", "detach a", "attach b", "attach b.inner"), log,
                "the old root told it left, children first, before the new one is told it joined");
        assertSame(b, scene.root());
        assertNull(a.scene(), "the old root is in no scene");
        assertNull(a.children().get(0).scene());
        assertSame(scene, b.children().get(0).scene());
        assertFalse(scene.holdsAsRootOrLayer(a), "nor does the scene hold it");
        assertTrue(scene.holdsAsRootOrLayer(b));
    }

    @Test
    void theRootTheSceneAlreadyHasChangesNothing() {
        Key key = new Key("key", log);
        Page page = new Page("page", log);
        page.add(key);
        Scene scene = new Scene(page);
        scene.requestFocus(key);
        log.clear();

        scene.setRoot(page);

        assertEquals(List.of(), log, "nobody told anything");
        assertSame(key, scene.focusedWidget());
    }

    private enum Taker { CONTAINER_ADD, PUSH_OVERLAY, PUSH_POPUP, NEW_SCENE, ANOTHER_SCENES_SET_ROOT, THIS_SCENE_AGAIN }

    @Test
    void theOldRootIsTakenByEveryTakerOnceTheSceneHasAnotherRoot() {
        for (Taker taker : Taker.values()) {
            Told a = new Told("a", log);
            Scene scene = new Scene(a);
            Told b = new Told("b", log);
            scene.setRoot(b);
            Column column = new Column();
            Scene other = new Scene(column);
            Scene expected = switch (taker) {
                case CONTAINER_ADD -> {
                    column.add(a);
                    yield other;
                }
                case PUSH_OVERLAY -> {
                    other.pushOverlay(a);
                    yield other;
                }
                case PUSH_POPUP -> {
                    other.pushPopup(a);
                    yield other;
                }
                case NEW_SCENE -> new Scene(a);
                case ANOTHER_SCENES_SET_ROOT -> {
                    other.setRoot(a);
                    yield other;
                }
                case THIS_SCENE_AGAIN -> {
                    scene.setRoot(a);
                    yield scene;
                }
            };
            assertSame(expected, a.scene(), taker + ": taken");
            if (taker == Taker.NEW_SCENE || taker == Taker.ANOTHER_SCENES_SET_ROOT || taker == Taker.THIS_SCENE_AGAIN) {
                assertSame(a, expected.root(), taker.name());
            }
        }
    }

    /** The review's case: a scene built only to measure a widget held it for ever. */
    @Test
    void aSceneThatWasNeverBoundLetsGoOfItsRootToo() {
        FixedBox chart = new FixedBox(120, 80);
        Scene measuring = new Scene(chart);
        measuring.layoutPass(200, 100);
        Column page = new Column();
        new Scene(page);
        assertThrows(IllegalArgumentException.class, () -> page.add(chart), "still the measuring scene's root");

        measuring.setRoot(new FixedBox(1, 1));

        page.add(chart);
        assertSame(page.scene(), chart.scene(), "the page took it");
    }

    /** The review's other case: a scene replaced on its window by another scene's bind never closes. */
    @Test
    void aSceneReplacedOnItsWindowLetsGoOfItsRootSoTheFirstPageCanComeBack() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        HeadlessWindow window = backend.open("Pages", 200, 100);
        Told pageA = new Told("a", log);
        Scene sceneA = new Scene(pageA);
        sceneA.bind(window);
        window.frame();
        Scene sceneB = new Scene(new Told("b", log));
        sceneB.bind(window);
        window.frame();
        assertThrows(IllegalArgumentException.class, () -> new Scene(pageA), "still scene A's root");

        sceneA.setRoot(new FixedBox(1, 1));

        Scene back = new Scene(pageA);
        back.bind(window);
        window.frame();
        assertSame(back, pageA.scene());
        assertSame(back, window.scene());
    }

    @Test
    void oneSceneBoundOnceShowsOnePageThenTheOtherAndBackAgain() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        HeadlessWindow window = backend.open("Pages", 200, 100);
        Painted pageA = new Painted();
        Painted pageB = new Painted();
        Scene scene = new Scene(pageA);
        scene.bind(window);
        window.frame();
        assertEquals(1, pageA.paints);

        scene.setRoot(pageB);
        window.frame();
        assertEquals(1, pageB.paints, "the new page painted");
        assertEquals(1, pageA.paints, "the old one not");
        assertEquals(200, pageB.width(), "laid out at the window's size");

        scene.setRoot(pageA);
        window.frame();
        assertEquals(2, pageA.paints, "back again");
        assertEquals(1, pageB.paints);
        assertSame(scene, pageA.scene());
        assertNull(pageB.scene());
    }

    /** A box that counts its paints. */
    private static final class Painted extends FixedBox {
        int paints;

        Painted() {
            super(10, 10);
        }

        @Override protected void onPaint(limn.graphics.Canvas canvas) {
            paints++;
        }
    }

    // ---------------------------------------------------------------- refused, before anything moves

    @Test
    void aWidgetThatIsSomebodyElsesIsRefusedBeforeAnythingMoves() {
        for (String what : List.of("parented here", "parented elsewhere", "parented nowhere", "another scene's root",
                "another scene's overlay", "another scene's popup", "this scene's overlay", "this scene's popup")) {
            Key key = new Key("key", log);
            Page page = new Page("page", log);
            page.add(key);
            Scene scene = new Scene(page);
            scene.requestFocus(key);
            Told taken = new Told("taken", log);
            switch (what) {
                case "parented here" -> page.add(taken);
                case "parented elsewhere" -> {
                    Column elsewhere = new Column();
                    new Scene(elsewhere);
                    elsewhere.add(taken);
                }
                case "parented nowhere" -> new Column().add(taken);
                case "another scene's root" -> new Scene(taken);
                case "another scene's overlay" -> new Scene(new Column()).pushOverlay(taken);
                case "another scene's popup" -> new Scene(new Column()).pushPopup(taken);
                case "this scene's overlay" -> scene.pushOverlay(taken);
                case "this scene's popup" -> scene.pushPopup(taken);
                default -> throw new AssertionError(what);
            }
            Widget<?> focusedBefore = scene.focusedWidget();
            Scene takenScene = taken.scene();
            log.clear();

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> scene.setRoot(taken), what);

            assertTrue(refused.getMessage().contains("root of a scene"), what + ": says what it would be: "
                    + refused.getMessage());
            assertSame(page, scene.root(), what + ": the root unchanged");
            assertSame(scene, page.scene(), what);
            assertSame(takenScene, taken.scene(), what + ": the widget where it was");
            assertEquals(List.of(), log, what + ": nobody told anything");
            assertSame(focusedBefore, scene.focusedWidget(), what + ": the keyboard where it was");
        }
    }

    // ---------------------------------------------------------------- the keyboard

    @Test
    void theKeyboardInTheOldRootGoesToTheNewRootsFirstStopAsTabPutsIt() {
        Key old = new Key("old", log);
        Page a = new Page("a", log);
        a.add(old);
        Page b = new Page("b", log);
        b.add(new FixedBox(40, 20));
        Key first = new Key("first", log);
        b.add(first);
        b.add(new Key("second", log));
        Scene scene = new Scene(a);
        scene.requestFocus(old);
        log.clear();

        scene.setRoot(b);

        assertSame(first, scene.focusedWidget());
        assertEquals(List.of("detach a", "lost old", "attach b", "gained first by traversal"), log);
    }

    @Test
    void noKeyboardInTheOldRootGivesNoneToTheNewOne() {
        Page a = new Page("a", log);
        a.add(new Key("old", log));
        Page b = new Page("b", log);
        b.add(new Key("first", log));
        Scene scene = new Scene(a);
        log.clear();

        scene.setRoot(b);

        assertNull(scene.focusedWidget());
        assertEquals(List.of("detach a", "attach b"), log);
    }

    /** The new page's content starts the keyboard on a field of its own: that one, and no first stop. */
    @Test
    void aWidgetTheNewRootAsksForAsItJoinsIsFocusedInsteadOfTheFirstStop() {
        Key old = new Key("old", log);
        Page a = new Page("a", log);
        a.add(old);
        Key first = new Key("first", log);
        Key asked = new Key("asked", log);
        Page b = new Page("b", log) {
            @Override protected void onAttached() {
                super.onAttached();
                scene().requestFocus(asked);
            }
        };
        b.add(first);
        b.add(asked);
        Scene scene = new Scene(a);
        scene.requestFocus(old);
        log.clear();

        scene.setRoot(b);

        assertSame(asked, scene.focusedWidget());
        assertEquals(List.of("detach a", "lost old", "attach b", "gained asked"), log, "no gain and loss of the first");
    }

    /**
     * A dialog opened from the old page, the page replaced while it is up (its answer switches the
     * page): the keyboard stays in the dialog, and when it closes it goes to the new page's first
     * stop, where its way back led into a page that is gone.
     */
    @Test
    void anOverlayOpenedFromTheOldRootGivesTheKeyboardToTheNewRootsFirstStopWhenItCloses() {
        Key opener = new Key("opener", log);
        Page a = new Page("a", log);
        a.add(opener);
        Key first = new Key("first", log);
        Page b = new Page("b", log);
        b.add(first);
        Scene scene = new Scene(a);
        scene.requestFocus(opener);
        Key ok = new Key("ok", log);
        Column dialog = new Column();
        dialog.add(ok);
        scene.pushOverlay(dialog);
        assertSame(ok, scene.focusedWidget());
        log.clear();

        scene.setRoot(b);

        assertSame(ok, scene.focusedWidget(), "the keyboard stays in the dialog");
        assertTrue(scene.holdsAsLayer(dialog), "the dialog stays on the stack");
        assertEquals(List.of("detach a", "attach b"), log);

        scene.removeOverlay(dialog);

        assertSame(first, scene.focusedWidget(), "the new page's first stop, not nothing");
        assertEquals(List.of("detach a", "attach b", "lost ok", "gained first by traversal"), log);
    }

    /** The same dialog given the keyboard back early, as it starts to fade out. */
    @Test
    void anOverlayReleasingTheKeyboardEarlyGivesItToTheNewRootsFirstStopToo() {
        Key opener = new Key("opener", log);
        Page a = new Page("a", log);
        a.add(opener);
        Key first = new Key("first", log);
        Page b = new Page("b", log);
        b.add(first);
        Scene scene = new Scene(a);
        scene.requestFocus(opener);
        Column dialog = new Column();
        dialog.add(new Key("ok", log));
        scene.pushOverlay(dialog);
        scene.setRoot(b);

        limn.scene.internal.SceneAccess.releaseOverlayKeyboard(scene, dialog);

        assertSame(first, scene.focusedWidget());
        scene.removeOverlay(dialog);
        assertSame(first, scene.focusedWidget(), "and nothing a second time");
    }

    // ---------------------------------------------------------------- the pointer

    @Test
    void thePressAndTheHoverInTheOldRootAreTakenOutAndTheNewRootIsNotClicked() {
        Told a = new Told("a", log);
        Told b = new Told("b", log);
        Scene scene = new Scene(a);
        scene.layoutPass(100, 100);
        drive(scene).mouseMoved(10, 10);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10, 1);
        drive(scene).inputBatchEnded();
        log.clear();

        scene.setRoot(b);
        assertEquals(List.of("detach a", "a:EXIT", "a:RELEASE", "attach b"), log,
                "the old root's exit and release, which it would otherwise never get");
        scene.layoutPass(100, 100);
        log.clear();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, 10, 10, 1);
        drive(scene).inputBatchEnded();

        assertFalse(log.contains("b:CLICK"), "a press on the old page is no click on the new: " + log);
        assertFalse(log.stream().anyMatch(s -> s.startsWith("a:")), "nor anything more for the old: " + log);
    }

    /** A button's click handler switches the page while its own event is being dispatched. */
    @Test
    void aClickHandlerOfTheOldRootSwitchesThePageOnceAndTheNewPageHearsNoClick() {
        AtomicInteger switched = new AtomicInteger();
        Page b = new Page("b", log);
        Key first = new Key("first", log);
        b.add(first);
        Scene[] held = new Scene[1];
        Key button = new Key("button", log) {
            @Override protected void onMouseEvent(MouseEvent event) {
                if (event.type() == MouseEvent.Type.CLICK) {
                    switched.incrementAndGet();
                    held[0].setRoot(b);
                    event.consume();
                }
            }
        };
        Page a = new Page("a", log);
        a.add(button);
        held[0] = new Scene(a);
        held[0].layoutPass(100, 100);
        log.clear();

        drive(held[0]).click(10, 10);

        assertEquals(1, switched.get(), "the handler ran once");
        assertSame(b, held[0].root());
        assertSame(first, held[0].focusedWidget(), "the click focused the button, so the new page's first stop");
        assertEquals(List.of("gained button", "detach a", "lost button", "attach b", "gained first by traversal"), log);
    }

    // ---------------------------------------------------------------- what the old root had running

    /** A popup hung from the old root closes as for a removal; one hung elsewhere stays. */
    @Test
    void aPopupHungFromTheOldRootClosesAndOneHungFromAnOverlayStays() {
        Told anchor = new Told("anchor", log);
        Page a = new Page("a", log);
        a.add(anchor);
        Scene scene = new Scene(a);
        Told overlayAnchor = new Told("overlay anchor", log);
        Column overlay = new Column();
        overlay.add(overlayAnchor);
        scene.pushOverlay(overlay);
        AtomicInteger closedFromPage = new AtomicInteger();
        AtomicInteger closedFromOverlay = new AtomicInteger();
        scene.observeAnchorLeaving(anchor, closedFromPage::incrementAndGet);
        scene.observeAnchorLeaving(overlayAnchor, closedFromOverlay::incrementAndGet);

        scene.setRoot(new Page("b", log));

        assertEquals(1, closedFromPage.get(), "closed once");
        assertEquals(0, closedFromOverlay.get());
        assertTrue(scene.holdsAsLayer(overlay));
    }

    /** A fade running in the old root comes to its end as it leaves, and its onArrive runs once the new root is in. */
    @Test
    void aFadeRunningInTheOldRootArrivesOnceTheNewRootIsIn() {
        long[] now = {TimeUnit.SECONDS.toNanos(1)};
        FixedBox inside = new FixedBox(40, 20);
        Opacity fading = new Opacity(inside);
        Page a = new Page("a", log);
        a.add(fading);
        Page b = new Page("b", log);
        Scene scene = new Scene(a, () -> now[0]);
        NoopCanvas canvas = new NoopCanvas(100, 100);
        scene.renderFrame(canvas);
        List<String> arrived = new ArrayList<>();
        fading.fadeTo(0f, 0.3, () -> arrived.add("arrived with root " + (scene.root() == b ? "b" : "a")
                + (b.scene() == scene ? " joined" : " not joined")));
        now[0] += TimeUnit.MILLISECONDS.toNanos(16);
        scene.renderFrame(canvas);
        assertEquals(List.of(), arrived, "mid-fade");

        scene.setRoot(b);

        assertEquals(List.of("arrived with root b joined"), arrived);
        now[0] += TimeUnit.SECONDS.toNanos(1);
        scene.renderFrame(canvas);
        assertEquals(1, arrived.size(), "and only once");
    }

    // ---------------------------------------------------------------- asked again while it changes

    @Test
    void aRootAskedForFromTheOldRootsOnDetachedIsSetOnceThisChangeIsComplete() {
        Page c = new Page("c", log);
        Scene[] held = new Scene[1];
        Page a = new Page("a", log) {
            @Override protected void onDetached() {
                super.onDetached();
                held[0].setRoot(c);
                log.add("asked; root is " + (held[0].root() == c ? "c" : "not c"));
            }
        };
        Page b = new Page("b", log);
        held[0] = new Scene(a);
        log.clear();

        held[0].setRoot(b);

        assertSame(c, held[0].root());
        assertEquals(List.of("detach a", "asked; root is not c", "attach b", "detach b", "attach c"), log,
                "the change in progress completes, then the one asked");
        assertNull(b.scene());
        assertSame(held[0], c.scene());
    }

    /** A page that sends the window on to another as it joins (a sign-in page that finds the user signed in). */
    @Test
    void aRootThatAsksForAnotherAsItJoinsHandsTheSceneOn() {
        Page home = new Page("home", log);
        Scene[] held = new Scene[1];
        Page signIn = new Page("sign in", log) {
            @Override protected void onAttached() {
                super.onAttached();
                held[0].setRoot(home);
            }
        };
        Page a = new Page("a", log);
        held[0] = new Scene(a);
        log.clear();

        held[0].setRoot(signIn);

        assertSame(home, held[0].root());
        assertEquals(List.of("detach a", "attach sign in", "detach sign in", "attach home"), log);
        assertNull(signIn.scene());
    }

    /** A page that redirects as it joins, to the next one, {@code hops} times: a chain that settles. */
    private Page[] chain(Scene[] held, int hops) {
        Page[] pages = new Page[hops + 1];
        pages[hops] = new Page("page " + hops, log);
        for (int i = hops - 1; i >= 0; i--) {
            Page next = pages[i + 1];
            pages[i] = new Page("page " + i, log) {
                @Override protected void onAttached() {
                    super.onAttached();
                    held[0].setRoot(next);
                }
            };
        }
        return pages;
    }

    /**
     * Each root asked as one joins is set once that change is complete, by a loop and not by a call
     * inside the one before, so a long chain of redirects that settles uses no more stack than one.
     */
    @Test
    void aLongChainOfRedirectsSettlesOnItsLastPage() {
        Scene[] held = new Scene[1];
        Page[] pages = chain(held, Scene.MAX_ROOTS_ASKED_WHILE_SETTING);
        held[0] = new Scene(new Page("start", log));

        held[0].setRoot(pages[0]);

        assertSame(pages[pages.length - 1], held[0].root(), "every redirect followed, the last one shown");
        for (int i = 0; i < pages.length - 1; i++) {
            assertNull(pages[i].scene(), "page " + i + " passed through and left");
        }
    }

    /**
     * Two pages whose guards disagree, each sending the window to the other as it joins: an
     * application bug that never settles. Before, every redirect was a call inside the one before
     * and the stack overflowed after some twenty thousand; now the scene stops after its limit with
     * an IllegalStateException that says so, on a page it has fully set, and is usable afterwards.
     */
    @Test
    void twoPagesThatRedirectToEachOtherAreStoppedWithAClearRefusal() {
        Scene[] held = new Scene[1];
        Page[] pair = new Page[2];
        AtomicInteger joins = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            int other = 1 - i;
            pair[i] = new Page(i == 0 ? "orders" : "sign in", log) {
                @Override protected void onAttached() {
                    super.onAttached();
                    joins.incrementAndGet();
                    held[0].setRoot(pair[other]);
                }
            };
        }
        held[0] = new Scene(new Page("start", log));

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> held[0].setRoot(pair[0]));

        assertTrue(refused.getMessage().contains(String.valueOf(Scene.MAX_ROOTS_ASKED_WHILE_SETTING)), refused.getMessage());
        assertEquals(Scene.MAX_ROOTS_ASKED_WHILE_SETTING + 1, joins.get(), "the first page and each redirect followed");
        Widget<?> shown = held[0].root();
        assertTrue(shown == pair[0] || shown == pair[1], "one of the two pages is the root");
        assertSame(held[0], shown.scene(), "fully set");
        Page other = shown == pair[0] ? pair[1] : pair[0];
        assertNull(other.scene(), "the other left");
        new Column().add(other); // and is free

        Page settled = new Page("settled", log);
        held[0].setRoot(settled);
        assertSame(settled, held[0].root(), "the scene sets a root again afterwards");
    }

    @Test
    void theLastRootAskedWhileItChangesWinsAndTheOthersAreToldNothing() {
        Page c = new Page("c", log);
        Page d = new Page("d", log);
        Scene[] held = new Scene[1];
        Page a = new Page("a", log) {
            @Override protected void onDetached() {
                super.onDetached();
                held[0].setRoot(c);
                held[0].setRoot(d);
            }
        };
        held[0] = new Scene(a);
        Page b = new Page("b", log);
        log.clear();

        held[0].setRoot(b);

        assertSame(d, held[0].root());
        assertEquals(List.of("detach a", "attach b", "detach b", "attach d"), log);
        assertNull(c.scene());
    }

    @Test
    void theRootBeingSetAskedAgainWhileItChangesChangesNothingMore() {
        Scene[] held = new Scene[1];
        Page b = new Page("b", log);
        Page a = new Page("a", log) {
            @Override protected void onDetached() {
                super.onDetached();
                held[0].setRoot(b);
            }
        };
        held[0] = new Scene(a);
        log.clear();

        held[0].setRoot(b);

        assertSame(b, held[0].root());
        assertEquals(List.of("detach a", "attach b"), log);
    }

    /** While the old root leaves, the new one is in no scene yet, and a handler of the old one cannot take it. */
    @Test
    void theNewRootCannotBeTakenByAHandlerOfTheOldOneWhileItLeaves() {
        Page b = new Page("b", log);
        Column elsewhere = new Column();
        Scene other = new Scene(new Column());
        List<String> refused = new ArrayList<>();
        Page a = new Page("a", log) {
            @Override protected void onDetached() {
                super.onDetached();
                for (Runnable take : List.<Runnable>of(() -> elsewhere.add(b), () -> other.pushOverlay(b),
                        () -> new Scene(b), () -> other.setRoot(b))) {
                    try {
                        take.run();
                        refused.add("taken");
                    } catch (IllegalArgumentException expected) {
                        refused.add(expected.getMessage().contains("root of a scene") ? "refused" : expected.getMessage());
                    }
                }
            }
        };
        Scene scene = new Scene(a);
        log.clear();

        scene.setRoot(b);

        assertEquals(List.of("refused", "refused", "refused", "refused"), refused);
        assertSame(scene, b.scene());
        assertNull(b.parent());
        assertSame(b, scene.root());
    }

    /** A popup's handler, as it closes, may take the old root, which the scene has let go of. */
    @Test
    void aClosingPopupsHandlerMayTakeTheOldRootIntoAnOverlayOfTheSameScene() {
        Key field = new Key("field", log);
        Page a = new Page("a", log);
        a.add(field);
        Scene scene = new Scene(a);
        Column panel = new Column();
        scene.pushOverlay(panel);
        scene.observeAnchorLeaving(field, () -> panel.add(a));
        Page b = new Page("b", log);
        log.clear();

        scene.setRoot(b);

        assertSame(panel, a.parent(), "the handler's move stands");
        assertSame(scene, a.scene(), "in the overlay's tree");
        assertSame(b, scene.root());
        assertSame(scene, b.scene());
    }

    @Test
    void aRootAskedForByAClosingPopupsHandlerIsSetOnceThisChangeIsComplete() {
        Told anchor = new Told("anchor", log);
        Page a = new Page("a", log);
        a.add(anchor);
        Scene scene = new Scene(a);
        Page c = new Page("c", log);
        scene.observeAnchorLeaving(anchor, () -> scene.setRoot(c));
        Page b = new Page("b", log);
        log.clear();

        scene.setRoot(b);

        assertSame(c, scene.root());
        assertNull(b.scene());
    }

    // ---------------------------------------------------------------- a scene with no window, or a closed one

    @Test
    void aClosedScenesNewRootJoinsAtTheNextBindAndTheOldOneNever() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        HeadlessWindow window = backend.open("Tool", 200, 100);
        Page a = new Page("a", log);
        Scene scene = new Scene(a);
        scene.bind(window);
        window.frame();
        window.userClose();
        assertNull(a.scene(), "the close let go of it");
        Page b = new Page("b", log);
        log.clear();

        scene.setRoot(b);

        assertEquals(List.of(), log, "nothing joins a scene whose window closed");
        assertSame(b, scene.root());
        HeadlessWindow again = backend.open("Tool", 200, 100);
        scene.bind(again);
        again.frame();
        assertEquals(List.of("attach b"), log);
        assertSame(scene, b.scene());
        assertNull(a.scene());
        new Column().add(a); // free
    }

    /** A root its scene let go of to move into another scene (a dialog's card) stays where it went. */
    @Test
    void aRootLetGoOfIntoAnotherSceneIsNotWalkedOutOfThatOne() {
        Told card = new Told("card", log);
        Scene own = new Scene(card);
        Scene owner = new Scene(new Column());
        limn.scene.internal.SceneAccess.letGoOfRoot(own);
        owner.pushOverlay(card);
        Page next = new Page("next", log);
        log.clear();

        own.setRoot(next);

        assertSame(owner, card.scene(), "still the other scene's overlay");
        assertEquals(List.of("attach next"), log);
        assertTrue(own.holdsAsRootOrLayer(next), "and the new root is held, not let go of");
        assertThrows(IllegalArgumentException.class, () -> new Scene(next));
    }
}
