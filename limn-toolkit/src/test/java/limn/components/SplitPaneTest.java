package limn.components;

import limn.graphics.Color;
import limn.graphics.Paint;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The split's behaviour, driven through a real scene with no GL.
 *
 * <p>Nothing here hard-codes the gutter or the grab band. A press anywhere on the
 * divider records where it landed relative to the line, so a drag moves the split
 * by exactly the pointer's travel, which is the property worth pinning, and the
 * one that would break silently if either constant were ever changed alone.
 */
class SplitPaneTest extends ComponentTestBase {

    private Label left;
    private Label right;
    private SplitPane split;
    private Scene scene;
    private final List<Float> ratios = new ArrayList<>();

    @BeforeEach
    void layOutASplit() {
        left = new Label("left");
        right = new Label("right");
        split = SplitPane.horizontal(left, right);
        split.onRatioChange(ratios::add);
        scene = new Scene(split);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 200);
    }

    @Test
    void theRatioSharesTheSpace() {
        assertEquals(left.width(), right.width(), 0.01f, "the default split is even");
        assertTrue(left.width() + right.width() < 400,
                "the gutter has to come out of the shared space, not out of the panes");

        split.setRatio(0.25f);
        scene.layoutPass(400, 200);
        assertEquals(left.width() * 3, right.width(), 0.5f, "a quarter is not a quarter");
    }

    @Test
    void aSplitFillsTheHeightItIsGiven() {
        assertEquals(200, left.height(), 0.01f);
        assertEquals(200, right.height(), 0.01f);
    }

    @Test
    void draggingTheDividerMovesItByThePointersTravel() {
        float before = left.width();
        dragDivider(40);

        assertEquals(before + 40, left.width(), 0.5f,
                "the divider did not follow the pointer point for point");
        assertEquals(1, ratios.size(), "a drag reports the ratio it settled on");
        assertEquals(split.ratio(), ratios.get(0), 1e-6f);
    }

    @Test
    void aDragOffTheLineDoesNotJumpTheDivider() {
        // The band is wider than the line so it can be grabbed; pressing near the
        // edge of it must not snap the split to the pointer.
        Widget divider = split.divider();
        float before = left.width();
        float x = divider.localToSceneX() + 1;
        float y = divider.localToSceneY() + divider.height() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.inputBatchEnded();
        scene.layoutPass(400, 200);

        assertEquals(before, left.width(), 0.01f, "the press alone moved the split");
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();
    }

    @Test
    void theMinimumsStopTheDrag() {
        split.setMinimums(120, 60);
        scene.layoutPass(400, 200);

        dragDivider(-1000);
        assertEquals(120, left.width(), 0.5f, "the first pane went under its floor");

        dragDivider(1000);
        assertEquals(60, right.width(), 0.5f, "the second pane went under its floor");
    }

    @Test
    void aPaneWithNoFloorCollapses() {
        split.setMinimums(0, 0);
        dragDivider(-1000);
        assertEquals(0, left.width(), 0.5f, "a pane allowed to collapse did not");
        assertTrue(right.width() > 380, "and the other one should have taken the space");
    }

    @Test
    void theSplitSurvivesAResize() {
        split.setRatio(0.25f);
        scene.layoutPass(400, 200);
        scene.layoutPass(800, 200);

        assertEquals(left.width() * 3, right.width(), 1f,
                "the ratio is what survives a resize, so a quarter stays a quarter");
    }

    @Test
    void arrowsMoveTheDividerAndShiftIsTheFineStep() {
        focusDivider();
        float before = left.width();

        press(Keys.RIGHT, 0);
        assertEquals(before + 10, left.width(), 0.5f, "an arrow should move a visible step");

        press(Keys.LEFT, Keys.MOD_SHIFT);
        assertEquals(before + 9, left.width(), 0.5f, "Shift should move one point");
        assertEquals(2, ratios.size(), "each press is one reported change");
    }

    @Test
    void homeAndEndParkAgainstTheFloors() {
        split.setMinimums(120, 60);
        scene.layoutPass(400, 200);
        focusDivider();

        press(Keys.HOME, 0);
        assertEquals(120, left.width(), 0.5f);

        press(Keys.END, 0);
        assertEquals(60, right.width(), 0.5f);
    }

    @Test
    void theOtherAxisIsLeftAlone() {
        // A horizontal split divides left-to-right, so Up/Down belong to whatever
        // is inside it (a list, a scroll view), and must not move the divider.
        focusDivider();
        float before = left.width();

        press(Keys.UP, 0);
        press(Keys.DOWN, 0);

        assertEquals(before, left.width(), 0.01f, "the cross axis moved the split");
        assertTrue(ratios.isEmpty(), "and reported a change that never happened");
    }

    @Test
    void aVerticalSplitDividesTheHeight() {
        Label top = new Label("top");
        Label bottom = new Label("bottom");
        SplitPane stacked = SplitPane.vertical(top, bottom);
        Scene stackedScene = new Scene(stacked);
        stackedScene.setTextRuler(RULER);
        stackedScene.layoutPass(400, 200);

        assertEquals(400, top.width(), 0.01f, "a stacked pane takes the full width");
        assertEquals(top.height(), bottom.height(), 0.01f);
        assertTrue(top.height() + bottom.height() < 200, "the gutter comes out of the height");
    }

    /** Presses on the divider, moves the pointer by {@code delta} points, releases. */
    private void dragDivider(float delta) {
        Widget divider = split.divider();
        float x = divider.localToSceneX() + divider.width() / 2;
        float y = divider.localToSceneY() + divider.height() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseMoved(x + delta, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x + delta, y);
        scene.inputBatchEnded();
        scene.layoutPass(400, 200);
    }

    /** Turns the keyboard on and focuses the divider; the opt-in is part of focusing it. */
    private void focusDivider() {
        split.setDividerFocusable(true);
        split.divider().requestFocus();
        assertEquals(split.divider(), scene.focusedWidget(),
                "the divider never took focus, so the keys went nowhere");
    }

    private void press(int key, int modifiers) {
        scene.keyEvent(key, true, false, modifiers);
        scene.inputBatchEnded();
        scene.layoutPass(400, 200);
    }

    @Test
    void theGutterFollowsTheControlSize() {
        // The premise: every component is sensitive to the step. A split has one
        // extent of its own (the gutter between the panes), so that is where the
        // step has to show, and the panes give up exactly that much space.
        float dense = gutterAt(limn.scene.ControlSize.XSMALL);
        float roomy = gutterAt(limn.scene.ControlSize.XLARGE);
        assertTrue(roomy > dense,
                "a roomy step must leave a wider gutter (was " + dense + " and " + roomy + ")");
        assertEquals(SizeTokens.of(limn.scene.ControlSize.XSMALL).separatorBox(), dense, 0.01f,
                "the gutter is the separator box: one centred hairline's worth of room");
    }

    /** The space the panes give up at {@code step}, which is the gutter. */
    private float gutterAt(limn.scene.ControlSize step) {
        Label a = new Label("a");
        Label b = new Label("b");
        SplitPane pane = SplitPane.horizontal(a, b);
        pane.setControlSize(step);
        Scene host = new Scene(pane);
        host.setTextRuler(RULER);
        host.layoutPass(400, 200);
        return 400 - a.width() - b.width();
    }

    @Test
    void aSplitIsWorkedWithThePointerUntilTheKeyboardIsAskedFor() {
        assertFalse(split.isDividerFocusable(), "a divider is not a tab stop by default");

        split.divider().requestFocus();
        assertNull(scene.focusedWidget(), "focus landed on a divider that does not take it");
        float before = left.width();
        press(Keys.RIGHT, 0);
        assertEquals(before, left.width(), 0.01f, "an arrow moved a split nobody focused");

        // Everything the pointer does is untouched by the flag being off.
        dragDivider(40);
        assertEquals(before + 40, left.width(), 0.5f, "the drag needed focus to work");

        split.setDividerFocusable(true);
        assertTrue(split.isDividerFocusable());
        split.divider().requestFocus();
        press(Keys.RIGHT, 0);
        assertEquals(before + 50, left.width(), 0.5f,
                "turning the flag on did not give back the arrow keys");
    }

    @Test
    void focusHoverAndDragAreThreeTintsOfOneAccentEachLighterThanTheLast() {
        // The states have to be told apart by colour: they are all the same mark now
        // (the line itself), so nothing else distinguishes them.
        AtomicLong clock = new AtomicLong();
        Label a = new Label("a");
        SplitPane pane = SplitPane.horizontal(a, new Label("b")).setDividerFocusable(true);
        Scene host = new Scene(pane, clock::get);
        host.setTextRuler(RULER);
        host.layoutPass(400, 200);
        Widget divider = pane.divider();
        float x = divider.localToSceneX() + divider.width() / 2;
        float y = divider.localToSceneY() + divider.height() / 2;

        Color rest = lineColor(host, clock);
        divider.requestFocus();
        Color focused = lineColor(host, clock);
        host.mouseMoved(x, y);
        host.inputBatchEnded();
        Color hovered = lineColor(host, clock);
        host.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        host.inputBatchEnded();
        Color dragged = lineColor(host, clock);
        host.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        host.inputBatchEnded();

        Theme theme = Theme.current();
        assertEquals(theme.outline, rest, "at rest a divider is a hairline separator");
        assertEquals(theme.focusRing, focused,
                "a focused divider wears the same accent as every other focused control");
        assertTrue(lightness(hovered) > lightness(focused),
                "hover must be lighter than focus (" + hovered.toHex()
                        + " against " + focused.toHex() + ")");
        assertTrue(lightness(dragged) > lightness(hovered),
                "a drag must be lighter than hover (" + dragged.toHex()
                        + " against " + hovered.toHex() + ")");
    }

    /**
     * Renders one settled frame and returns the colour of the divider's line. Two
     * frames, and neither is optional: a ticker's first frame carries {@code dt == 0}
     * by contract, so a transition only reaches its endpoint <em>exactly</em> on a
     * second frame that jumps past its duration. Recording one frame earlier records
     * a mid-fade tint, and the ordering asserted above would be a race.
     */
    private static Color lineColor(Scene host, AtomicLong clock) {
        LineCanvas canvas = new LineCanvas(400, 200);
        host.renderFrame(canvas);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        host.renderFrame(canvas);
        assertNotNull(canvas.color, "the divider painted no line");
        return canvas.color;
    }

    /** Relative luminance: "lighter" over a ramp toward white, which HSV value is not. */
    private static float lightness(Color c) {
        return 0.2126f * c.r() + 0.7152f * c.g() + 0.0722f * c.b();
    }

    /** Records the divider's line; it is the only line the scene paints. */
    private static final class LineCanvas extends FakeCanvas {
        Color color;

        LineCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
            if (paint instanceof Color c) {
                color = c;
            }
        }
    }

    @Test
    void eachOrientationOffersTheCursorForTheAxisItMoves() {
        // Regression: the divider was a field initializer, so it asked an
        // orientation that was still null which way it lay. Both kinds of split
        // came out with the stacked cursor and only one of them looked wrong.
        assertEquals(limn.backend.Cursor.RESIZE_EW, split.divider().cursor(),
                "a side-by-side split moves left and right");

        SplitPane stacked = SplitPane.vertical(new Label("top"), new Label("bottom"));
        assertEquals(limn.backend.Cursor.RESIZE_NS, stacked.divider().cursor(),
                "a stacked split moves up and down");
    }
}
