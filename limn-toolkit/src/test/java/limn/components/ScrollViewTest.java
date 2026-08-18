package limn.components;

import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ScrollView} offset clamping, wheel scrolling and scrolled hit-testing. */
class ScrollViewTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;

    /** Fixed-preferred-size leaf. */
    static final class Box extends Widget {
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
    void wheelThroughTheSceneScrollsAndIsConsumed() {
        Box content = new Box(100, 400);
        ScrollView scroll = new ScrollView(content);
        Scene scene = new Scene(scroll);
        scene.layoutPass(100, 100);

        scene.scrolled(0, -1, 50, 50); // wheel down
        scene.inputBatchEnded();
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
        scene.keyEvent(key, true, false, 0);
        scene.keyEvent(key, false, false, 0);
        scene.inputBatchEnded();
    }

    @Test
    void wheelWithoutOverflowIsNotConsumed() {
        Box content = new Box(100, 50);
        ScrollView scroll = new ScrollView(content);
        Scene scene = new Scene(scroll);
        scene.layoutPass(100, 100);
        scene.scrolled(0, -1, 50, 25);
        scene.inputBatchEnded();
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

        scene.keyEvent(Keys.LEFT_SHIFT, true, false, Keys.MOD_SHIFT);
        scene.scrolled(0, -1, 50, 50); // a plain vertical wheel, Shift held
        scene.inputBatchEnded();

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

        scene.scrolled(0, -1, 50, 50);
        scene.inputBatchEnded();

        assertTrue(scroll.offsetY() > 0, "no Shift, no axis swap");
        assertEquals(0, scroll.offsetX(), EPS);
    }
}
