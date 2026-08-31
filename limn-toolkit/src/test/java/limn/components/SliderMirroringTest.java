package limn.components;

import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Slider} read right to left: which end of the track holds {@code min}, which way the fill
 * grows, where a click lands, and which keys follow the direction.
 *
 * <p>Every expectation is arithmetic against the geometry the component paints and the values it
 * settles on, never a picture. A mirrored slider that maps the pointer through one origin and the
 * thumb through another looks perfectly correct in a screenshot and selects a different value than
 * the one aimed at, which is the whole defect this file exists to catch.
 *
 * <p>The box is the token pad at both ends plus a round 200pt track, so every fraction below is an
 * exact coordinate. The Slider carries no text, so the degenerate ruler cannot influence a number
 * here.
 */
class SliderMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final SizeTokens MD = SizeTokens.of(ControlSize.MEDIUM);
    private static final float PAD = MD.sliderPad();
    private static final float TRACK = 200;
    private static final float WIDTH = 2 * PAD + TRACK;
    private static final float HEIGHT = MD.sliderHeight();

    private Slider slider;
    private Scene scene;

    private void build(LayoutDirection direction, Slider s) {
        slider = s;
        slider.setLayoutDirection(direction);
        scene = new Scene(slider);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDTH, HEIGHT);
    }

    /** Physical widget-local x of a fraction of the track, measured from the left in both directions. */
    private static float trackX(float fractionFromLeft) {
        return PAD + fractionFromLeft * TRACK;
    }

    private void click(float localX) {
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, localX, HEIGHT / 2);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, localX, HEIGHT / 2);
        scene.inputBatchEnded();
    }

    private void press(int key) {
        scene.keyEvent(key, true, false, 0);
        scene.keyEvent(key, false, false, 0);
        scene.inputBatchEnded();
    }

    private void focus() {
        scene.focusTraverse(false);
    }

    private InkCanvas paint() {
        InkCanvas canvas = new InkCanvas(WIDTH, HEIGHT);
        scene.renderFrame(canvas);
        return canvas;
    }

    /**
     * Records the two round rects the track is made of (the rail, then the filled portion) and the
     * centre of the knob, which is the one circle the slider fills.
     */
    private static final class InkCanvas extends FakeCanvas {
        final List<RoundRect> fills = new ArrayList<>();
        float knobCx = Float.NaN;

        InkCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            fills.add(roundRect);
        }

        @Override
        public void fillCircle(float cx, float cy, float radius, Paint paint) {
            knobCx = cx;
        }
    }

    // ------------------------------------------------------------------ the value origin

    @Test
    void minSitsAtTheRightEndOfTheTrackReadingRightToLeft() {
        build(LayoutDirection.RTL, new Slider(0, 100));
        assertEquals(PAD + TRACK, paint().knobCx, EPS, "fraction 0 is the leading end, the right one");

        slider.setValue(100);
        assertEquals(PAD, paint().knobCx, EPS, "and max has walked all the way to the left");

        slider.setValue(25);
        assertEquals(PAD + 0.75f * TRACK, paint().knobCx, EPS, "a quarter along, from the right");
    }

    @Test
    void minSitsAtTheLeftEndReadingLeftToRight() {
        build(LayoutDirection.LTR, new Slider(0, 100));
        assertEquals(PAD, paint().knobCx, EPS);

        slider.setValue(25);
        assertEquals(PAD + 0.25f * TRACK, paint().knobCx, EPS);

        slider.setValue(100);
        assertEquals(PAD + TRACK, paint().knobCx, EPS);
    }

    // ------------------------------------------------------------------ the painted track

    @Test
    void theFilledPortionGrowsLeftwardsFromTheRightEndReadingRightToLeft() {
        build(LayoutDirection.RTL, new Slider(0, 100).setValue(25));
        InkCanvas canvas = paint();
        assertEquals(2, canvas.fills.size(), "the rail and the filled portion");

        RoundRect fill = canvas.fills.get(1);
        assertEquals(PAD + 0.75f * TRACK, fill.x(), EPS, "the fill starts under the thumb");
        assertEquals(0.25f * TRACK, fill.width(), EPS, "and runs to the track's right end");
        assertEquals(PAD + TRACK, fill.x() + fill.width(), EPS, "which is where min is");
    }

    @Test
    void theFilledPortionIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR, new Slider(0, 100).setValue(25));
        RoundRect fill = paint().fills.get(1);
        assertEquals(PAD, fill.x(), EPS);
        assertEquals(0.25f * TRACK, fill.width(), EPS);
    }

    @Test
    void theRailDoesNotMoveBecauseThePadIsReservedAtBothEnds() {
        // The CENTRE site: the track rectangle is symmetric in the box, so only the meaning of its
        // ends flips. A direction branch here would shorten or shift the rail for no reason.
        build(LayoutDirection.LTR, new Slider(0, 100).setValue(40));
        RoundRect ltr = paint().fills.get(0);
        build(LayoutDirection.RTL, new Slider(0, 100).setValue(40));
        RoundRect rtl = paint().fills.get(0);

        assertEquals(PAD, ltr.x(), EPS);
        assertEquals(ltr.x(), rtl.x(), EPS, "same left edge");
        assertEquals(TRACK, ltr.width(), EPS);
        assertEquals(ltr.width(), rtl.width(), EPS, "same width");
        assertEquals(ltr.y(), rtl.y(), EPS, "and the vertical geometry is not a direction at all");
        assertEquals(ltr.height(), rtl.height(), EPS);
    }

    @Test
    void theEmptyAndFullEndsPaintNoFillAtAll() {
        // The zero-width guard has to be on the side the fill is anchored to: reading right to
        // left, min is the empty case and it is the RIGHT end that must produce nothing.
        build(LayoutDirection.RTL, new Slider(0, 100));
        assertEquals(1, paint().fills.size(), "at min there is nothing filled to paint");

        slider.setValue(100);
        InkCanvas full = paint();
        assertEquals(2, full.fills.size(), "at max the fill spans the whole track");
        assertEquals(PAD, full.fills.get(1).x(), EPS);
        assertEquals(TRACK, full.fills.get(1).width(), EPS);
    }

    // ------------------------------------------------------------------ the pointer

    @Test
    void aClickMapsThroughTheSameOriginThePaintUses() {
        build(LayoutDirection.RTL, new Slider(0, 100));
        click(trackX(0.25f));
        assertEquals(75f, slider.value(), EPS, "a quarter from the left is three quarters of the value");
        assertEquals(trackX(0.25f), paint().knobCx, EPS, "and the thumb came back to the pointer");

        click(trackX(0.9f));
        assertEquals(10f, slider.value(), EPS);
        assertEquals(trackX(0.9f), paint().knobCx, EPS);
    }

    @Test
    void aClickIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR, new Slider(0, 100));
        click(trackX(0.25f));
        assertEquals(25f, slider.value(), EPS);
        assertEquals(trackX(0.25f), paint().knobCx, EPS);
    }

    @Test
    void draggingPastEitherEndReachesTheValueThatEndHolds() {
        build(LayoutDirection.RTL, new Slider(0, 100));
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, trackX(0.5f), HEIGHT / 2);
        scene.inputBatchEnded();
        assertEquals(50f, slider.value(), EPS);

        scene.mouseMoved(-WIDTH, HEIGHT / 2); // past the left end, which is max here
        scene.inputBatchEnded();
        assertEquals(100f, slider.value(), EPS);

        scene.mouseMoved(2 * WIDTH, HEIGHT / 2); // and past the right end, which is min
        scene.inputBatchEnded();
        assertEquals(0f, slider.value(), EPS);
    }

    // ------------------------------------------------------------------ the keyboard

    @Test
    void leftAndRightFollowTheDirectionReadingRightToLeft() {
        build(LayoutDirection.RTL, new Slider(0, 100).setStep(10).setValue(50));
        focus();

        press(Keys.LEFT);
        assertEquals(60f, slider.value(), EPS, "left is towards max, because max is on the left");
        press(Keys.RIGHT);
        assertEquals(50f, slider.value(), EPS, "and right comes back down");
    }

    @Test
    void leftAndRightAreUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR, new Slider(0, 100).setStep(10).setValue(50));
        focus();

        press(Keys.LEFT);
        assertEquals(40f, slider.value(), EPS);
        press(Keys.RIGHT);
        assertEquals(50f, slider.value(), EPS);
    }

    @Test
    void anArrowKeyMovesTheThumbTheWayTheKeyPoints() {
        // The key decision and the geometry are one decision: a slider whose Left key raised the
        // value while the thumb walked right would be self-contradictory in a way no per-side
        // assertion catches on its own.
        build(LayoutDirection.RTL, new Slider(0, 100).setStep(10).setValue(50));
        focus();
        float before = paint().knobCx;

        press(Keys.LEFT);
        assertTrue(paint().knobCx < before, "the thumb moved left, like the key");

        press(Keys.RIGHT);
        press(Keys.RIGHT);
        assertTrue(paint().knobCx > before, "and right moved it back past where it started");
    }

    @Test
    void upAndDownDoNotMirror() {
        // They are a vertical pair. They shared an arm with Left/Right only because the two agreed
        // reading left to right, and mirroring the arm rather than the horizontal keys would have
        // inverted them too.
        build(LayoutDirection.RTL, new Slider(0, 100).setStep(10).setValue(50));
        focus();

        press(Keys.UP);
        assertEquals(60f, slider.value(), EPS, "up is still towards max");
        press(Keys.DOWN);
        assertEquals(50f, slider.value(), EPS, "and down is still towards min");
    }

    @Test
    void homeAndEndDoNotMirrorBecauseTheyNameAValue() {
        build(LayoutDirection.RTL, new Slider(0, 100).setValue(50));
        focus();

        press(Keys.HOME);
        assertEquals(0f, slider.value(), EPS, "Home is min in both directions");
        press(Keys.END);
        assertEquals(100f, slider.value(), EPS, "and End is max");
    }

    @Test
    void pageKeysDoNotMirrorEitherBecauseAPageIsAMagnitude() {
        build(LayoutDirection.RTL, new Slider(0, 100).setStep(5).setValue(50));
        focus();

        press(Keys.PAGE_UP);
        assertEquals(100f, slider.value(), EPS, "ten steps up, clamped at max");
        press(Keys.PAGE_DOWN);
        assertEquals(50f, slider.value(), EPS, "and ten steps back down");
    }

    // ------------------------------------------------------------------ the value domain

    @Test
    void theValueItselfIsNotMirrored() {
        // Nothing about the range flips: min stays min, snapping stays snapping, and a
        // programmatic value lands on the same number it does reading left to right.
        build(LayoutDirection.RTL, new Slider(0, 100).setStep(10));
        slider.setValue(23);
        assertEquals(20f, slider.value(), EPS, "snapped to the nearest step, not the mirrored one");
        assertEquals(0f, slider.min(), EPS);
        assertEquals(100f, slider.max(), EPS);
    }
}
