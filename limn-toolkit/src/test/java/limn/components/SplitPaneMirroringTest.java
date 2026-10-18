package limn.components;

import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * {@link SplitPane} read right to left: which side the first pane takes, where the grab band
 * lands, which way a drag moves the divider, which way the arrows do, and the four decisions
 * that deliberately do not turn around.
 *
 * <p>Every expectation is arithmetic against the deterministic {@link #RULER} and the split's
 * own two locked constants -- the separator box it uses as a gutter and the hit target it uses
 * as a grab band -- rather than a picture. A mirrored split that is inside out is obvious in a
 * screenshot; a grab band four points away from the line it is meant to catch is not, and that
 * is the failure worth pinning.
 */
class SplitPaneMirroringTest extends ComponentTestBase {

    private static final float EPS = 0.01f;
    private static final float WIDTH = 400;
    private static final float HEIGHT = 200;

    /** The gutter, from the same token the widget reads. */
    private static final float GUTTER = SizeTokens.of(ControlSize.MEDIUM).separatorBox();
    /** The grab band, locked at the accessibility floor. */
    private static final float GRAB = Strokes.MIN_HIT_TARGET;

    /** What the two panes share once the gutter has taken its cut. */
    private static final float TOTAL = WIDTH - GUTTER;
    /** The first pane's extent at the default even ratio; well clear of either floor. */
    private static final float FIRST = TOTAL / 2;
    private static final float SECOND = TOTAL - FIRST;
    /** The grab band's offset along the divided axis, centred on the gutter. */
    private static final float DIVIDER_START = FIRST + GUTTER / 2 - GRAB / 2;

    private Label first;
    private Label second;
    private SplitPane split;
    private Scene scene;
    private final List<Float> ratios = new ArrayList<>();

    /** A side-by-side split reading {@code direction}, laid out and ready to be driven. */
    private void buildHorizontal(LayoutDirection direction) {
        first = new Label("first");
        second = new Label("second");
        split = SplitPane.horizontal(first, second);
        split.setLayoutDirection(direction);
        split.onRatioChange(ratios::add);
        scene = new Scene(split);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDTH, HEIGHT);
    }

    // ------------------------------------------------------------------ layout

    @Test
    void theFirstPaneTakesTheRightEdgeReadingRightToLeft() {
        buildHorizontal(LayoutDirection.RTL);

        assertEquals(FIRST, first.width(), EPS, "the ratio is a magnitude and does not change");
        assertEquals(SECOND, second.width(), EPS);
        assertEquals(WIDTH - FIRST, first.localToScene(0, 0).x(), EPS,
                "the first pane is flush against the edge reading starts from");
        assertEquals(0, second.localToScene(0, 0).x(), EPS,
                "and the second pane takes the far side, which is the left");
        assertEquals(WIDTH - FIRST - GUTTER, second.localToScene(0, 0).x() + second.width(), EPS,
                "the gutter is still between them, and still one gutter wide");
    }

    @Test
    void thePanesAreUnchangedReadingLeftToRight() {
        buildHorizontal(LayoutDirection.LTR);

        assertEquals(0, first.localToScene(0, 0).x(), EPS);
        assertEquals(FIRST + GUTTER, second.localToScene(0, 0).x(), EPS);
        assertEquals(DIVIDER_START, split.divider().localToScene(0, 0).x(), EPS);
    }

    @Test
    void theGrabBandStaysCentredOnTheLineItCatches() {
        buildHorizontal(LayoutDirection.RTL);
        Widget<?> divider = split.divider();

        assertEquals(WIDTH - DIVIDER_START - GRAB, divider.localToScene(0, 0).x(), EPS,
                "the band is the reflection of the offset the panes were placed by");
        assertEquals(GRAB, divider.width(), EPS, "the band keeps its width");
        // The point the panes meet at, physically: the middle of the gutter.
        float boundary = WIDTH - FIRST - GUTTER / 2;
        assertEquals(boundary, divider.localToScene(0, 0).x() + divider.width() / 2, EPS,
                "the band and the line it draws would separate if either moved alone");
        assertTrue(divider.localToScene(0, 0).x() < WIDTH - FIRST
                        && divider.localToScene(0, 0).x() + GRAB > WIDTH - FIRST - GUTTER,
                "the band overlaps both panes, which is what makes it grabbable");
    }

    @Test
    void aStackedSplitIsUntouchedByTheDirection() {
        Label top = new Label("top");
        Label bottom = new Label("bottom");
        SplitPane stacked = SplitPane.vertical(top, bottom);
        stacked.setLayoutDirection(LayoutDirection.RTL);
        Scene stackedScene = new Scene(stacked);
        stackedScene.setTextRuler(RULER);
        stackedScene.layoutPass(WIDTH, HEIGHT);

        float stackedTotal = HEIGHT - GUTTER;
        assertEquals(0, top.localToScene(0, 0).x(), EPS, "a stacked pane still starts at the left");
        assertEquals(0, bottom.localToScene(0, 0).x(), EPS);
        assertEquals(WIDTH, top.width(), EPS, "and still takes the full width");
        assertEquals(0, top.localToScene(0, 0).y(), EPS);
        assertEquals(stackedTotal / 2, top.height(), EPS);
        assertEquals(stackedTotal / 2 + GUTTER, bottom.localToScene(0, 0).y(), EPS,
                "the divided axis is vertical, and direction says nothing about it");
    }

    // ----------------------------------------------------------------- the drag

    @Test
    void aDragFollowsThePointerPointForPointReadingRightToLeft() {
        buildHorizontal(LayoutDirection.RTL);
        Widget<?> divider = split.divider();
        float y = divider.localToScene(0, 0).y() + divider.height() / 2;
        float x = divider.localToScene(0, 0).x() + divider.width() / 2;

        drag(x, y, 40);

        assertEquals(FIRST - 40, first.width(), EPS,
                "dragging towards the second pane has to shrink the first one");
        assertEquals(WIDTH - FIRST + 40, first.localToScene(0, 0).x(), EPS,
                "and the pane stays flush against the edge it started from");
        assertEquals(1, ratios.size(), "a drag reports the ratio it settled on");
        assertTrue(ratios.get(0) < 0.5f, "the ratio is the first pane's share, whichever side it is on");
    }

    @Test
    void aDragOffTheLineStillDoesNotJumpTheDivider() {
        // The offset taken at the press is a difference between two values on the divided axis.
        // Reflecting it a second time would put it on the wrong side of the line and snap the
        // divider to the pointer, which is exactly what the grab band exists to prevent.
        buildHorizontal(LayoutDirection.RTL);
        Widget<?> divider = split.divider();
        float y = divider.localToScene(0, 0).y() + divider.height() / 2;
        float edge = divider.localToScene(0, 0).x() + 1;

        drag(edge, y, 40);

        assertEquals(FIRST - 40, first.width(), EPS,
                "a press near the edge of the band moved the split by more than the pointer");
    }

    @Test
    void aDragIsUnchangedReadingLeftToRight() {
        buildHorizontal(LayoutDirection.LTR);
        Widget<?> divider = split.divider();
        float y = divider.localToScene(0, 0).y() + divider.height() / 2;

        drag(divider.localToScene(0, 0).x() + divider.width() / 2, y, 40);

        assertEquals(FIRST + 40, first.width(), EPS);
        assertEquals(0, first.localToScene(0, 0).x(), EPS);
    }

    /** Presses at {@code x}, moves the pointer {@code delta} points to the right, releases. */
    private void drag(float x, float y, float delta) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        drive(scene).mouseMoved(x + delta, y);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x + delta, y);
        drive(scene).inputBatchEnded();
        scene.layoutPass(WIDTH, HEIGHT);
    }

    // -------------------------------------------------------------- the arrows

    @Test
    void theArrowsMoveTheDividerTheWayTheyPointReadingRightToLeft() {
        buildHorizontal(LayoutDirection.RTL);
        focusDivider();
        float before = split.divider().localToScene(0, 0).x();

        press(Keys.LEFT, 0);
        assertEquals(before - 10, split.divider().localToScene(0, 0).x(), EPS,
                "Left has to move the divider left, or the keyboard disagrees with the pointer");
        assertEquals(FIRST + 10, first.width(), EPS,
                "and the pane on the right grows by exactly what the divider gave it");

        press(Keys.RIGHT, 0);
        assertEquals(before, split.divider().localToScene(0, 0).x(), EPS, "Right has to undo Left");
        assertEquals(FIRST, first.width(), EPS);

        press(Keys.LEFT, Keys.MOD_SHIFT);
        assertEquals(before - 1, split.divider().localToScene(0, 0).x(), EPS,
                "Shift is the fine step and not a second direction");
    }

    @Test
    void theArrowsAreUnchangedReadingLeftToRight() {
        buildHorizontal(LayoutDirection.LTR);
        focusDivider();
        float before = split.divider().localToScene(0, 0).x();

        press(Keys.RIGHT, 0);
        assertEquals(before + 10, split.divider().localToScene(0, 0).x(), EPS);
        assertEquals(FIRST + 10, first.width(), EPS);

        press(Keys.LEFT, 0);
        assertEquals(before, split.divider().localToScene(0, 0).x(), EPS);
    }

    @Test
    void theArrowAndTheDragMoveTheDividerTheSameWay() {
        // The one property worth stating on its own: a divider dragged right by the mouse and
        // moved left by the Right arrow is worse than either convention alone.
        buildHorizontal(LayoutDirection.RTL);
        focusDivider();
        Widget<?> divider = split.divider();
        float y = divider.localToScene(0, 0).y() + divider.height() / 2;

        press(Keys.RIGHT, 0);
        float keyed = split.divider().localToScene(0, 0).x();

        buildHorizontal(LayoutDirection.RTL);
        divider = split.divider();
        drag(divider.localToScene(0, 0).x() + divider.width() / 2, y, 10);
        float dragged = split.divider().localToScene(0, 0).x();

        assertEquals(dragged, keyed, EPS,
                "the Right arrow and a rightward drag landed the divider in different places");
    }

    @Test
    void theOffAxisArrowsAreStillRefusedWhenMirrored() {
        // matchesAxis is what makes the flip above safe: it turns Up and Down away before
        // either arm can reach a body, so a horizontal split never sees them at all.
        buildHorizontal(LayoutDirection.RTL);
        focusDivider();
        float before = split.divider().localToScene(0, 0).x();

        press(Keys.UP, 0);
        press(Keys.DOWN, 0);

        assertEquals(before, split.divider().localToScene(0, 0).x(), EPS, "the cross axis moved the split");
        assertTrue(ratios.isEmpty(), "and reported a change that never happened");
    }

    @Test
    void aStackedSplitKeepsItsOwnArrowsAndIgnoresTheHorizontalOnes() {
        Label top = new Label("top");
        SplitPane stacked = SplitPane.vertical(top, new Label("bottom"));
        stacked.setLayoutDirection(LayoutDirection.RTL);
        stacked.setDividerFocusable(true);
        Scene stackedScene = new Scene(stacked);
        stackedScene.setTextRuler(RULER);
        stackedScene.layoutPass(WIDTH, HEIGHT);
        stacked.divider().requestFocus();
        float before = top.height();

        drive(stackedScene).keyEvent(Keys.LEFT, true, false, 0);
        drive(stackedScene).keyEvent(Keys.RIGHT, true, false, 0);
        drive(stackedScene).inputBatchEnded();
        stackedScene.layoutPass(WIDTH, HEIGHT);
        assertEquals(before, top.height(), EPS, "a horizontal key moved a vertical split");

        drive(stackedScene).keyEvent(Keys.DOWN, true, false, 0);
        drive(stackedScene).inputBatchEnded();
        stackedScene.layoutPass(WIDTH, HEIGHT);
        assertEquals(before + 10, top.height(), EPS,
                "and Down still grows the top pane, in either direction");
    }

    // ------------------------------------------------------- what does not move

    @Test
    void homeAndEndNameTheValueAndDoNotTurnAround() {
        // Home and End name the first pane's extent, which is a value and not a side. Reading
        // right to left, Home therefore moves the divider visually RIGHT -- the opposite of the
        // arrows, and correct. Pinned here so a later sweep cannot quietly mirror them.
        buildHorizontal(LayoutDirection.RTL);
        split.setMinimums(120, 60);
        scene.layoutPass(WIDTH, HEIGHT);
        focusDivider();

        press(Keys.HOME, 0);
        assertEquals(120, first.width(), EPS, "Home parks against the first pane's own floor");
        assertEquals(WIDTH - 120, first.localToScene(0, 0).x(), EPS);
        float atHome = split.divider().localToScene(0, 0).x();

        press(Keys.END, 0);
        assertEquals(60, second.width(), EPS, "End parks against the second pane's floor");
        assertEquals(0, second.localToScene(0, 0).x(), EPS);
        float atEnd = split.divider().localToScene(0, 0).x();

        assertTrue(atHome > atEnd,
                "Home collapses the first pane, which reading right to left is a move to the right"
                        + " (Home at " + atHome + ", End at " + atEnd + ")");
    }

    @Test
    void homeAndEndAreUnchangedReadingLeftToRight() {
        buildHorizontal(LayoutDirection.LTR);
        split.setMinimums(120, 60);
        scene.layoutPass(WIDTH, HEIGHT);
        focusDivider();

        press(Keys.HOME, 0);
        assertEquals(120, first.width(), EPS);
        float atHome = split.divider().localToScene(0, 0).x();

        press(Keys.END, 0);
        assertEquals(60, second.width(), EPS);
        assertTrue(split.divider().localToScene(0, 0).x() > atHome,
                "reading left to right Home is the left-hand end, as it always was");
    }

    @Test
    void theRatioAndTheFloorsAreMagnitudesInEitherDirection() {
        // setRatio, ratio() and setMinimums are extents along the divided axis. If any of them
        // learned about the direction, an application that persisted a ratio under one would
        // restore the wrong layout under the other.
        buildHorizontal(LayoutDirection.LTR);
        split.setRatio(0.25f);
        scene.layoutPass(WIDTH, HEIGHT);
        float ltrFirst = first.width();

        buildHorizontal(LayoutDirection.RTL);
        split.setRatio(0.25f);
        scene.layoutPass(WIDTH, HEIGHT);

        assertEquals(ltrFirst, first.width(), EPS, "a quarter is a quarter of the first pane");
        assertEquals(0.25f, split.ratio(), 1e-6f);
    }

    @Test
    void theResizeCursorIsSymmetricAndStaysSo() {
        // A double-headed cursor has no side to be on, and the divider's constructor is the one
        // place the direction must never be read: the parent is assigned after it runs.
        buildHorizontal(LayoutDirection.RTL);
        assertEquals(limn.backend.Cursor.RESIZE_EW, split.divider().cursor(),
                "a side-by-side split moves left and right, whichever way it reads");
    }

    /** Turns the keyboard on and focuses the divider; the opt-in is part of focusing it. */
    private void focusDivider() {
        split.setDividerFocusable(true);
        split.divider().requestFocus();
        assertEquals(split.divider(), scene.focusedWidget(),
                "the divider never took focus, so the keys went nowhere");
    }

    private void press(int key, int modifiers) {
        drive(scene).keyEvent(key, true, false, modifiers);
        drive(scene).inputBatchEnded();
        scene.layoutPass(WIDTH, HEIGHT);
    }
}
