package limn.components;

import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/** {@link ScrollView} offset clamping, wheel scrolling and scrolled hit-testing. */
class ScrollViewTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;

    /** Fixed-preferred-size leaf. */
    static final class Box extends Widget<Box> {
        private final float prefWidth;
        private final float prefHeight;

        Box(float prefWidth, float prefHeight) {
            this.prefWidth = prefWidth;
            this.prefHeight = prefHeight;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(prefWidth, prefHeight);
        }
    }

    @Test
    void offsetsClampToContentOverflow() {
        Box content = new Box(100, 300);
        ScrollView scroll = new ScrollView(content);
        scroll.measure(Constraints.tight(100, 100));
        scroll.layoutBox(0, 0, 100, 100);

        assertEquals(200, scroll.maxOffsetY(), EPS);
        scroll.scrollBy(0, 50);
        assertEquals(50, scroll.offsetY(), EPS);
        assertEquals(-50, content.y(), EPS, "child physically moves for hit-testing");

        scroll.scrollBy(0, 10_000);
        assertEquals(200, scroll.offsetY(), EPS, "clamped to the bottom");
        scroll.scrollTo(0, -5);
        assertEquals(0, scroll.offsetY(), EPS, "clamped to the top");
    }

    @Test
    void anOffsetSetBeforeTheFirstLayoutIsWhereTheFirstLayoutPutsTheContent() {
        Box content = new Box(100, 300);
        ScrollView scroll = new ScrollView(content);
        scroll.scrollTo(0, 64); // built already scrolled, before anything is measured
        Scene scene = new Scene(scroll);
        scene.layoutPass(100, 100);
        assertEquals(64, scroll.offsetY(), EPS, "kept, where it was clamped to nothing before");
        assertEquals(-64, content.y(), EPS);

        ScrollView past = new ScrollView(new Box(100, 150));
        past.scrollTo(0, 500);
        new Scene(past).layoutPass(100, 100);
        assertEquals(50, past.offsetY(), EPS, "and clamped by the content the layout measured");
    }

    /**
     * A view inside a scrolling page, as the glass page's shelf is: a detent that finds the inner
     * view at an end of its axis, start or finish, scrolls the page; one that can still move the
     * inner view moves it alone.
     */
    @Test
    void aDetentAtAnEndOfANestedViewScrollsTheViewHoldingIt() {
        ScrollView inner = new ScrollView(new Box(100, 400));
        Column page = new Column();
        page.add(new Box(100, 200));
        page.add(new limn.scene.layout.SizedBox(100, 100, inner));
        page.add(new Box(100, 600));
        ScrollView outer = new ScrollView(page);
        Scene scene = new Scene(outer, eachDetentItsOwnGesture());
        scene.layoutPass(100, 300);
        outer.scrollTo(0, 150); // the page scrolled down; the inner view sits at y 50..150
        scene.layoutPass(100, 300);
        assertEquals(0, inner.offsetY(), EPS);

        drive(scene).scrolled(0, 1, 50, 100); // up, over the inner view at its top
        drive(scene).inputBatchEnded();
        assertEquals(0, inner.offsetY(), EPS, "nothing to give upward");
        assertEquals(102, outer.offsetY(), EPS, "so the page took the detent");

        scene.layoutPass(100, 300);
        float y = inner.localToScene(0, 0).y() + 50;
        drive(scene).scrolled(0, -1, 50, y); // down: the inner view can move, and moves alone
        drive(scene).inputBatchEnded();
        assertEquals(48, inner.offsetY(), EPS);
        assertEquals(102, outer.offsetY(), EPS);

        inner.scrollTo(0, 300); // its end
        drive(scene).scrolled(0, -1, 50, y);
        drive(scene).inputBatchEnded();
        assertEquals(300, inner.offsetY(), EPS);
        assertEquals(150, outer.offsetY(), EPS, "at the inner view's end, down scrolls the page");
    }

    /**
     * A flick that brings the inner view to its top stays with it, its inertia included: the page
     * does not move under a gesture that was the inner view's. The next gesture, after a pause,
     * finds the inner view at its top and scrolls the page.
     */
    @Test
    void aGestureThatTookTheInnerViewStaysWithItAtItsEndAndTheNextOnePassesOn() {
        long[] now = {0};
        ScrollView inner = new ScrollView(new Box(100, 400));
        Column page = new Column();
        page.add(new Box(100, 200));
        page.add(new limn.scene.layout.SizedBox(100, 100, inner));
        page.add(new Box(100, 600));
        ScrollView outer = new ScrollView(page);
        Scene scene = new Scene(outer, () -> now[0]);
        scene.layoutPass(100, 300);
        outer.scrollTo(0, 150);
        inner.scrollTo(0, 60);
        scene.layoutPass(100, 300);
        float y = inner.localToScene(0, 0).y() + 50;

        for (int i = 0; i < 6; i++) { // one flick upward, an event every 16 ms
            now[0] += 16_000_000L;
            drive(scene).scrolled(0, 1, 50, y);
            drive(scene).inputBatchEnded();
        }
        assertEquals(0, inner.offsetY(), EPS, "the flick brought the inner view to its top");
        assertEquals(150, outer.offsetY(), EPS, "and the rest of it stayed there");

        now[0] += 200_000_000L; // a pause: the next event is a new gesture
        drive(scene).scrolled(0, 1, 50, y);
        drive(scene).inputBatchEnded();
        assertEquals(102, outer.offsetY(), EPS, "a new gesture at the inner view's top scrolls the page");
    }

    /**
     * On a trackpad the inertia of one swipe keeps the events coming with no pause, and a second
     * swipe begins inside them: the platform marks its first event, and that one chooses its
     * scroller afresh. Without the mark the page could never be reached while swipes followed
     * each other faster than the inertia died.
     */
    @Test
    void aGestureThePlatformSaysBeginsPassesOnInsideTheInertiaOfTheOneBefore() {
        long[] now = {0};
        ScrollView inner = new ScrollView(new Box(100, 400));
        Column page = new Column();
        page.add(new Box(100, 200));
        page.add(new limn.scene.layout.SizedBox(100, 100, inner));
        page.add(new Box(100, 600));
        ScrollView outer = new ScrollView(page);
        Scene scene = new Scene(outer, () -> now[0]);
        scene.layoutPass(100, 300);
        outer.scrollTo(0, 150);
        inner.scrollTo(0, 60);
        scene.layoutPass(100, 300);
        float y = inner.localToScene(0, 0).y() + 50;

        drive(scene).scrolled(0, 1, 50, y, true); // a swipe up
        drive(scene).inputBatchEnded();
        for (int i = 0; i < 5; i++) { // and its inertia, every 16 ms
            now[0] += 16_000_000L;
            drive(scene).scrolled(0, 1, 50, y);
            drive(scene).inputBatchEnded();
        }
        assertEquals(0, inner.offsetY(), EPS);
        assertEquals(150, outer.offsetY(), EPS, "the inertia stayed with the inner view");

        now[0] += 16_000_000L; // a second swipe, inside the inertia: no pause at all
        drive(scene).scrolled(0, 1, 50, y, true);
        drive(scene).inputBatchEnded();
        assertEquals(102, outer.offsetY(), EPS, "the new gesture found the inner view at its top");
    }

    /**
     * The page scrolled back up carries the inner view under a pointer that does not move: the
     * rest of the gesture is the page's still, though the inner view could scroll.
     */
    @Test
    void aGestureStaysWithThePageWhenTheInnerViewPassesUnderThePointer() {
        long[] now = {0};
        ScrollView inner = new ScrollView(new Box(100, 400));
        Column page = new Column();
        page.add(new Box(100, 200));
        page.add(new limn.scene.layout.SizedBox(100, 100, inner));
        page.add(new Box(100, 600));
        ScrollView outer = new ScrollView(page);
        Scene scene = new Scene(outer, () -> now[0]);
        scene.layoutPass(100, 300);
        outer.scrollTo(0, 150);
        inner.scrollTo(0, 300); // scrolled to its end on the way down
        scene.layoutPass(100, 300);
        float y = 150; // over the page, below the inner view (page y 300)

        drive(scene).scrolled(0, 1, 50, y, true);
        drive(scene).inputBatchEnded();
        scene.layoutPass(100, 300);
        assertEquals(102, outer.offsetY(), EPS, "the page took the gesture");
        assertTrue(inner.localToScene(0, 0).y() <= y && y < inner.localToScene(0, 0).y() + inner.height(),
                "and the inner view is now under the pointer");

        now[0] += 16_000_000L;
        drive(scene).scrolled(0, 1, 50, y);
        drive(scene).inputBatchEnded();
        assertEquals(54, outer.offsetY(), EPS, "the rest of the gesture is the page's");
        assertEquals(300, inner.offsetY(), EPS, "the inner view under the pointer took none of it");
    }

    @Test
    void aSidewaysDetentAtAnEndOfANestedViewGoesToTheViewHoldingIt() {
        ScrollView inner = new ScrollView(new Box(400, 50), true, false);
        limn.scene.layout.Row row = new limn.scene.layout.Row();
        row.add(new Box(200, 50));
        row.add(new limn.scene.layout.SizedBox(100, 50, inner));
        row.add(new Box(600, 50));
        ScrollView outer = new ScrollView(row, true, false);
        Scene scene = new Scene(outer);
        scene.layoutPass(300, 50);
        outer.scrollTo(150, 0);
        scene.layoutPass(300, 50);
        drive(scene).scrolled(1, 0, inner.localToScene(0, 0).x() + 50, 25); // toward the start
        drive(scene).inputBatchEnded();
        assertEquals(0, inner.offsetX(), EPS);
        assertEquals(102, outer.offsetX(), EPS, "the inner view at its start passed it on");
    }

    @Test
    void aTextAreaAtTheTopOfItsTextPassesAnUpwardDetentToThePage() {
        TextArea area = new TextArea();
        area.setText("one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\neleven\ntwelve");
        Column page = new Column();
        page.add(new Box(100, 200));
        page.add(new limn.scene.layout.SizedBox(100, 80, area));
        page.add(new Box(100, 600));
        ScrollView outer = new ScrollView(page);
        Scene scene = new Scene(outer);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 300);
        outer.scrollTo(0, 150);
        scene.layoutPass(100, 300);
        drive(scene).scrolled(0, 1, 50, area.localToScene(0, 0).y() + 40); // up, the text at its top
        drive(scene).inputBatchEnded();
        assertEquals(102, outer.offsetY(), EPS, "the page took the detent the area could not use");
    }

    @Test
    void scrollBarInsetsSetTheTracksInAndLeaveTheScrollingAlone() {
        Box content = new Box(400, 400);
        ScrollView scroll = new ScrollView(content, true, true)
                .setBarInsets(new limn.scene.Insets(52, 0, 0, 30));
        Scene scene = new Scene(scroll);
        scene.layoutPass(200, 200);
        ScrollBar v = scroll.verticalBar();
        float t = ScrollBar.thickness();
        assertEquals(52, v.y(), EPS, "the vertical track starts below the inset");
        assertEquals(200 - t - 52, v.height(), EPS, "and is that much shorter, the corner kept");
        ScrollBar h = scroll.horizontalBar();
        assertEquals(30, h.x(), EPS, "the horizontal track starts in from the left");
        assertEquals(200 - t - 30, h.width(), EPS);
        assertEquals(200, scroll.maxOffsetY(), EPS, "the scrolling itself is unchanged");

        scroll.setLayoutDirection(limn.scene.LayoutDirection.RTL);
        scene.layoutPass(200, 200);
        assertEquals(200 - (200 - t - 30) - 30, h.x(), EPS,
                "right to left, the start inset is on the right, as a Padding's is");
    }

    @Test
    void wheelThroughTheSceneScrollsAndIsConsumed() {
        Box content = new Box(100, 400);
        ScrollView scroll = new ScrollView(content);
        Scene scene = new Scene(scroll);
        scene.layoutPass(100, 100);

        drive(scene).scrolled(0, -1, 50, 50); // wheel down
        drive(scene).inputBatchEnded();
        assertEquals(48, scroll.offsetY(), EPS, "one notch = 48 logical points");
    }

    @Test
    void hitTestFollowsTheScrolledContent() {
        Column column = new Column();
        Box top = new Box(100, 100);
        Box bottom = new Box(100, 100);
        column.add(top);
        column.add(bottom);
        ScrollView scroll = new ScrollView(column);
        Scene scene = new Scene(scroll);
        scene.layoutPass(100, 100);

        assertSame(top, scroll.hitTest(50, 50));
        scroll.scrollTo(0, 100);
        assertSame(bottom, scroll.hitTest(50, 50), "after scrolling, the bottom child is under the cursor");
    }

    @Test
    void contentThatOverflowsOnFirstLayoutFlashesItsBar() {
        // An AUTO bar starts invisible and its hold timers start expired, so it used to appear
        // only once the pointer moved over the host or the wheel turned. A dialog capped at the
        // work area therefore opened with nothing at all saying its body had been cut off: the
        // gutter inset says the card is capped, only the bar says it can be scrolled.
        ScrollView view = new ScrollView(new Box(100, 900));
        Scene scene = new Scene(view);
        scene.setTextRuler(RULER);
        scene.layoutPass(200, 100);

        assertTrue(view.verticalBar().revealing(),
                "newly scrollable content announces itself, the way an overlay scroller does");
    }

    @Test
    void contentThatFitsSaysNothing() {
        ScrollView view = new ScrollView(new Box(100, 50));
        Scene scene = new Scene(view);
        scene.setTextRuler(RULER);
        scene.layoutPass(200, 100);

        assertFalse(view.verticalBar().revealing(), "nothing to scroll, nothing to announce");
    }

    @Test
    void pageAndHomeEndScrollFromTheKeyboard() {
        // There was no keyboard scroll path at all: the class handled the wheel and nothing else,
        // so clipped content in a view whose content is not focusable (a wall of text) could
        // only be reached with a mouse.
        Box content = new Box(100, 400);
        ScrollView scroll = new ScrollView(content);
        Scene scene = new Scene(scroll);
        scene.layoutPass(100, 100);

        press(scene, Keys.PAGE_DOWN);
        assertEquals(100, scroll.offsetY(), EPS, "a page is the viewport");
        press(scene, Keys.END);
        assertEquals(300, scroll.offsetY(), EPS, "the end is the end, not one more page");
        press(scene, Keys.PAGE_UP);
        assertEquals(200, scroll.offsetY(), EPS);
        press(scene, Keys.HOME);
        assertEquals(0, scroll.offsetY(), EPS);
    }

    @Test
    void aKeyThatMovesNothingIsLeftForSomethingElse() {
        // Consuming a key the view could not act on would strand it: a scroll view nested in
        // another one has to let the key it is done with reach the one outside it.
        Box content = new Box(100, 400);
        ScrollView scroll = new ScrollView(content);
        Scene scene = new Scene(scroll);
        scene.layoutPass(100, 100);

        KeyEvent atTop = new KeyEvent(Keys.PAGE_UP, true, false, 0);
        scroll.scrollByKey(atTop);
        assertFalse(atTop.isConsumed(), "already at the top: nothing moved, nothing consumed");

        KeyEvent moves = new KeyEvent(Keys.PAGE_DOWN, true, false, 0);
        scroll.scrollByKey(moves);
        assertTrue(moves.isConsumed());
    }

    @Test
    void arrowKeysAreLeftToWhateverIsFocused() {
        // Deliberate: arrows are the set a focused widget is most likely to want for something
        // else (a list moves its selection, a slider its value), so the view does not guess.
        Box content = new Box(100, 400);
        ScrollView scroll = new ScrollView(content);
        Scene scene = new Scene(scroll);
        scene.layoutPass(100, 100);

        press(scene, Keys.DOWN);
        assertEquals(0, scroll.offsetY(), EPS);
    }

    private static void press(Scene scene, int key) {
        drive(scene).keyEvent(key, true, false, 0);
        drive(scene).keyEvent(key, false, false, 0);
        drive(scene).inputBatchEnded();
    }

    @Test
    void wheelWithoutOverflowIsNotConsumed() {
        Box content = new Box(100, 50);
        ScrollView scroll = new ScrollView(content);
        Scene scene = new Scene(scroll);
        scene.layoutPass(100, 100);
        drive(scene).scrolled(0, -1, 50, 25);
        drive(scene).inputBatchEnded();
        assertEquals(0, scroll.offsetY(), EPS);
    }

    // --- reserved gutters ----------------------------------------------------

    @Test
    void overlayBarsLeaveTheContentTheWholeBox() {
        ScrollView view = new ScrollView(new Box(100, 900));
        Scene host = new Scene(view);
        host.setTextRuler(RULER);
        host.layoutPass(200, 100);

        assertEquals(200, view.viewportWidth(), EPS, "an overlay bar takes nothing");
        assertEquals(100, view.viewportHeight(), EPS);
        assertEquals(800, view.maxOffsetY(), EPS);
    }

    @Test
    void aReservedBarNarrowsTheContentByItsThickness() {
        Box content = new Box(100, 900);
        ScrollView view = new ScrollView(content).setBarLayout(ScrollGutters.Layout.RESERVED);
        Scene host = new Scene(view);
        host.setTextRuler(RULER);
        host.layoutPass(200, 100);

        float bar = ScrollBar.thickness();
        assertEquals(200 - bar, view.viewportWidth(), EPS, "the gutter came out of the content");
        assertEquals(200 - bar, content.width(), EPS, "and the child was laid out narrower");
        assertEquals(100, view.viewportHeight(), EPS, "nothing overflows horizontally");
        assertEquals(800, view.maxOffsetY(), EPS, "the reach is measured against the viewport");
    }

    @Test
    void aReservedGutterOnlyAppearsWhereThereIsOverflow() {
        Box content = new Box(50, 50); // fits both ways
        ScrollView view = new ScrollView(content).setBarLayout(ScrollGutters.Layout.RESERVED);
        Scene host = new Scene(view);
        host.setTextRuler(RULER);
        host.layoutPass(200, 100);

        assertEquals(200, view.viewportWidth(), EPS, "a gutter was held open for nothing");
        assertEquals(100, view.viewportHeight(), EPS);
    }

    @Test
    void bothAxesReserveTheirOwnGutter() {
        Box content = new Box(900, 900);
        ScrollView view = new ScrollView(content, true, true)
                .setBarLayout(ScrollGutters.Layout.RESERVED);
        Scene host = new Scene(view);
        host.setTextRuler(RULER);
        host.layoutPass(200, 100);

        float bar = ScrollBar.thickness();
        assertEquals(200 - bar, view.viewportWidth(), EPS);
        assertEquals(100 - bar, view.viewportHeight(), EPS);
        assertEquals(900 - (200 - bar), view.maxOffsetX(), EPS);
        assertEquals(900 - (100 - bar), view.maxOffsetY(), EPS);
    }

    @Test
    void aHiddenBarReservesNothing() {
        // Nothing will ever be drawn there, so holding the strip open is pure loss.
        Box content = new Box(100, 900);
        ScrollView view = new ScrollView(content)
                .setBarLayout(ScrollGutters.Layout.RESERVED)
                .setScrollbarPolicy(ScrollBar.Policy.HIDDEN);
        Scene host = new Scene(view);
        host.setTextRuler(RULER);
        host.layoutPass(200, 100);

        assertEquals(200, view.viewportWidth(), EPS);
    }

    @Test
    void narrowingForAGutterCanBringTheOtherBarOut() {
        // The two-pass case: 195 wide fits in 200, but not in 200 minus a gutter, so
        // reserving the vertical strip is what makes the horizontal one necessary.
        Box content = new Box(195, 900);
        ScrollView view = new ScrollView(content, true, true)
                .setBarLayout(ScrollGutters.Layout.RESERVED);
        Scene host = new Scene(view);
        host.setTextRuler(RULER);
        host.layoutPass(200, 100);

        float bar = ScrollBar.thickness();
        assertEquals(100 - bar, view.viewportHeight(), EPS,
                "the second pass never noticed the horizontal overflow it created");
        assertEquals(195 - (200 - bar), view.maxOffsetX(), EPS);
    }

    /**
     * Shift turns a vertical wheel into a horizontal one: how a plain mouse, with no tilt wheel
     * and no trackpad, reaches the right of a wide table. Without it that user's only horizontal
     * gesture is dragging the thumb.
     */
    @Test
    void shiftWheelScrollsAWideViewHorizontally() {
        Box content = new Box(400, 100);
        ScrollView scroll = new ScrollView(content, true, true);
        Scene scene = new Scene(scroll);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);

        drive(scene).keyEvent(Keys.LEFT_SHIFT, true, false, Keys.MOD_SHIFT);
        drive(scene).scrolled(0, -1, 50, 50); // a plain vertical wheel, Shift held
        drive(scene).inputBatchEnded();

        assertTrue(scroll.offsetX() > 0,
                "Shift must send a vertical wheel to the horizontal axis");
        assertEquals(0, scroll.offsetY(), EPS, "and must not also scroll vertically");
    }

    /** Without Shift the same view must keep its ordinary mapping. */
    @Test
    void anUnmodifiedWheelStillScrollsVertically() {
        Box content = new Box(400, 400);
        ScrollView scroll = new ScrollView(content, true, true);
        Scene scene = new Scene(scroll);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);

        drive(scene).scrolled(0, -1, 50, 50);
        drive(scene).inputBatchEnded();

        assertTrue(scroll.offsetY() > 0, "no Shift, no axis swap");
        assertEquals(0, scroll.offsetX(), EPS);
    }

    /**
     * A pane can be laid out at a box thinner than a scroll bar, and a two-axis pane subtracts a
     * bar's thickness from each side to leave the corner square clear. Both lengths need a floor:
     * a collapsed {@code SplitPane} pane and a {@code SizedBox(0, 0)} both produce that box
     * legitimately, and {@code Constraints} refuses a negative extent, so the throw comes out of
     * the layout pass, where the scene's guard abandons the whole frame &mdash; no paint, and no
     * accessible tree, for every other widget in the window too.
     */
    @Test
    void aPaneThinnerThanItsOwnBarsLaysOutInsteadOfAbandoningTheFrame() {
        float t = ScrollBar.thickness();
        for (float side : new float[] {0, t / 2, t - 0.5f}) {
            ScrollView scroll = new ScrollView(new Box(0, 0), true, true);
            scroll.measure(Constraints.tight(side, side));
            assertDoesNotThrow(() -> scroll.layoutBox(0, 0, side, side),
                    "laid out at " + side + ", with a bar thickness of " + t);
            assertEquals(0, scroll.maxOffsetX(), EPS, "and the offsets still answer");
            assertEquals(0, scroll.maxOffsetY(), EPS);
        }
    }

    /**
     * A clock 200 ms later at every reading: each detent its own gesture, turned one at a time,
     * so none is held by the scroller the gesture before it took.
     */
    private static java.util.function.LongSupplier eachDetentItsOwnGesture() {
        long[] now = {0};
        return () -> now[0] += 200_000_000L;
    }
}
