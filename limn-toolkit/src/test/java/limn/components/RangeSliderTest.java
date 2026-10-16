package limn.components;

import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RangeSlider} (ADR 067 section 4): two thumbs that stop at each other or at a minimum gap,
 * a press that takes the nearer thumb, each thumb a Tab stop moved by the keyboard within what the
 * other leaves it, and the application told the range the user set and nothing it set itself.
 */
class RangeSliderTest extends ComponentTestBase {

    private static final SizeTokens MD = SizeTokens.MEDIUM;
    private static final float PAD = MD.sliderPad();
    private static final float TRACK = 200;
    private static final float WIDTH = 2 * PAD + TRACK;
    private static final float HEIGHT = MD.sliderHeight();

    private RangeSlider slider;
    private Scene scene;
    private final List<float[]> changed = new ArrayList<>();
    private final List<float[]> committed = new ArrayList<>();

    private static float trackX(float fraction) {
        return PAD + fraction * TRACK;
    }

    private void build(RangeSlider s) {
        slider = s;
        slider.onChange((lo, hi) -> changed.add(new float[] {lo, hi}));
        slider.onCommit((lo, hi) -> committed.add(new float[] {lo, hi}));
        scene = new Scene(slider);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDTH, HEIGHT);
    }

    private void press(float fraction) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, trackX(fraction), HEIGHT / 2);
        drive(scene).inputBatchEnded();
    }

    private void dragTo(float fraction) {
        drive(scene).mouseMoved(trackX(fraction), HEIGHT / 2);
        drive(scene).inputBatchEnded();
    }

    private void release(float fraction) {
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, trackX(fraction), HEIGHT / 2);
        drive(scene).inputBatchEnded();
    }

    private void key(int key) {
        drive(scene).keyEvent(key, true, false, 0);
        drive(scene).inputBatchEnded();
    }

    @Test
    void aFreshSliderSpansItsWholeRange() {
        build(new RangeSlider(0, 100));
        assertEquals(0, slider.lower());
        assertEquals(100, slider.upper());
        assertThrows(IllegalArgumentException.class, () -> new RangeSlider(5, 5));
    }

    @Test
    void setValuesClampsSnapsKeepsTheGapAndTellsNoHandler() {
        build(new RangeSlider(0, 100).setStep(10));
        slider.setValues(-20, 47);
        assertEquals(0, slider.lower());
        assertEquals(50, slider.upper(), "snapped");
        slider.setMinimumGap(30).setValues(40, 50);
        assertEquals(30, slider.upper() - slider.lower(), "held the gap apart");
        assertThrows(IllegalArgumentException.class, () -> slider.setValues(60, 50));
        assertTrue(changed.isEmpty(), "the application's own writes reach no handler");
    }

    @Test
    void aPressOnTheTrackTakesTheNearerThumb() {
        build(new RangeSlider(0, 100));
        slider.setValues(20, 80);
        press(0.3f);
        release(0.3f);
        assertEquals(30, slider.lower(), 0.5f);
        assertEquals(80, slider.upper());
        press(0.7f);
        release(0.7f);
        assertEquals(70, slider.upper(), 0.5f);
        assertEquals(2, committed.size(), "each gesture is one decision");
        assertEquals(30, changed.get(0)[0], 0.5f, "the handler hears both values");
        assertEquals(80, changed.get(0)[1], 0.5f);
    }

    @Test
    void aThumbStopsAtTheOtherOrAtTheGap() {
        build(new RangeSlider(0, 100).setMinimumGap(10));
        slider.setValues(20, 60);
        press(0.2f);
        dragTo(0.9f);
        release(0.9f);
        assertEquals(50, slider.lower(), 0.01f, "stopped the gap short of the upper");
        assertEquals(60, slider.upper(), "which did not move");
    }

    @Test
    void twoThumbsOnOneSpotAreToldApartByWhereThePointerGoes() {
        build(new RangeSlider(0, 100));
        slider.setValues(50, 50);
        press(0.5f);
        dragTo(0.3f);
        release(0.3f);
        assertEquals(30, slider.lower(), 0.5f, "dragged towards the minimum: the lower");
        assertEquals(50, slider.upper());
        slider.setValues(50, 50);
        press(0.5f);
        dragTo(0.8f);
        release(0.8f);
        assertEquals(80, slider.upper(), 0.5f, "towards the maximum: the upper");
    }

    @Test
    void eachThumbIsATabStopMovedByTheKeyboardWithinWhatTheOtherLeavesIt() {
        build(new RangeSlider(0, 100).setStep(5).setMinimumGap(10));
        slider.setValues(20, 60);
        key(Keys.TAB);
        key(Keys.RIGHT);
        assertEquals(25, slider.lower(), "the lower takes the first Tab");
        key(Keys.END);
        assertEquals(50, slider.lower(), "End is as far as the upper leaves it");
        key(Keys.TAB);
        key(Keys.HOME);
        assertEquals(60, slider.upper(), "the upper's Home stops the gap above the lower");
        key(Keys.END);
        assertEquals(100, slider.upper());
    }

    /**
     * A minimum gap that is not a whole number of steps: the thumb the gap pushes lands on the
     * step's grid, past the gap rather than short of it, so a value is never one the steps cannot
     * reach. From the API, the keyboard and the bounds a reader is told alike.
     */
    @Test
    void aGapThatIsNotAWholeNumberOfStepsKeepsTheValuesOnTheGrid() {
        build(new RangeSlider(0, 100).setStep(10).setMinimumGap(15));
        slider.setValues(50, 60);
        assertEquals(50, slider.lower());
        assertEquals(70, slider.upper(), "pushed up to the next step past the gap");
        slider.setValues(90, 100);
        assertEquals(80, slider.lower(), "at the top, the lower gives way, down to a step");
        assertEquals(100, slider.upper());
        slider.setValues(20, 80);
        key(Keys.TAB);
        key(Keys.END);
        assertEquals(60, slider.lower(), "the keyboard's End stops on a step below the gap");
        key(Keys.TAB);
        key(Keys.HOME);
        assertEquals(80, slider.upper(), "and the upper's Home on a step above it");
        slider.setMinimumGap(0).setValues(56, 71);
        slider.setMinimumGap(15);
        assertTrue(slider.upper() - slider.lower() >= 15, "a gap set later holds as well");
        assertEquals(0, slider.upper() % 10, 0.001f, "on a step: " + slider.upper());
    }

    /** A key that cannot move its thumb, at the end of its travel, settles nothing new. */
    @Test
    void aKeyAtABlockedEndCommitsNothing() {
        build(new RangeSlider(0, 100));
        key(Keys.TAB);
        key(Keys.HOME);
        key(Keys.LEFT);
        assertTrue(committed.isEmpty(), "the lower was at the minimum already: " + committed.size());
        key(Keys.RIGHT);
        assertEquals(1, committed.size(), "a key that moved it commits");
    }

    /** Not a number is no value: refused, as a step and a gap that are not numbers are. */
    @Test
    void valuesThatAreNotNumbersAreRefused() {
        build(new RangeSlider(0, 100));
        assertThrows(IllegalArgumentException.class, () -> slider.setValues(Float.NaN, 50));
        assertThrows(IllegalArgumentException.class, () -> slider.setValues(10, Float.NaN));
        assertEquals(0, slider.lower());
        assertEquals(100, slider.upper());
    }

    @Test
    void readingRightToLeftTheMinimumIsAtTheRight() {
        RangeSlider s = new RangeSlider(0, 100);
        s.setLayoutDirection(LayoutDirection.RTL);
        build(s);
        slider.setValues(20, 80);
        press(0.25f); // a quarter from the left is three quarters of the range
        release(0.25f);
        assertEquals(75, slider.upper(), 0.5f);
        assertEquals(20, slider.lower());
    }
}
