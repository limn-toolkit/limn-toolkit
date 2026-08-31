package limn.components;

import limn.graphics.Color;
import limn.graphics.LinearGradient;
import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.input.Keys;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ColorPicker} read right to left, and the parts of it that must not turn round.
 *
 * <p>The picker holds two opposite answers about direction, so half of this file exists to keep a
 * later sweep from unifying them. The rails are value axes: sweep, thumb, pointer and the
 * horizontal arrows all mirror together. The saturation/value field is a colour space and the hue
 * ramp is vertical, and neither moves at all.
 *
 * <p>Every expectation is arithmetic against the box the layout produced under the deterministic
 * {@link #RULER}, taken off the calls the widget actually made rather than off a picture: the
 * failure this file is written against is a sweep painted for one direction under a thumb placed
 * for the other, which is a colour a screenshot cannot tell from the right one.
 */
class ColorPickerMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float WIDTH = 320;
    /** Tall enough for four channel lines, so no rail is laid out past the bottom. */
    private static final float HEIGHT = 520;
    /** The hue channel and the ramp are both six two-stop bands. */
    private static final int BANDS = 6;

    private ColorPicker picker;
    private Scene scene;

    private void build(LayoutDirection direction) {
        picker = new ColorPicker();
        picker.setLayoutDirection(direction);
        scene = new Scene(picker);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDTH, HEIGHT);
    }

    // ------------------------------------------------------------------ the thumb

    @Test
    void aChannelAtItsMinimumRestsOnTheLeadingEdgeReadingRightToLeft() {
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.BLACK); // every RGB channel at zero
        Widget red = picker.rail(0);
        float thumb = Theme.current().tokensFor(red).colorThumbW();

        assertEquals(red.localToSceneX() + red.width() - thumb / 2,
                thumbCentre(paint(), red), EPS,
                "the range starts at the leading edge, which reading right to left is the right");
    }

    @Test
    void aChannelAtItsMinimumIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR);
        picker.setInitialColor(Color.BLACK);
        Widget red = picker.rail(0);
        float thumb = Theme.current().tokensFor(red).colorThumbW();

        assertEquals(red.localToSceneX() + thumb / 2, thumbCentre(paint(), red), EPS);
    }

    @Test
    void aHalfWayThumbIsTheSameDistanceFromTheOppositeEdge() {
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.rgb(0x804020));
        Widget red = picker.rail(0);
        float thumb = Theme.current().tokensFor(red).colorThumbW();
        float fraction = (float) (picker.channel(0).value() / 255.0);

        assertEquals(red.localToSceneX() + red.width()
                        - (thumb / 2 + fraction * (red.width() - thumb)),
                thumbCentre(paint(), red), EPS,
                "the travel is a magnitude and only its coordinate is reflected");
    }

    // ---------------------------------------------------------------- the pointer

    @Test
    void aPressOnTheThumbLeavesTheValueWhereItIsReadingRightToLeft() {
        // The inverse-pair contract, stated as the one thing a user can see: paint and pointer
        // compose the same coordinate, so pressing exactly where the thumb is drawn is a no-op.
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.rgb(0x804020));
        Widget red = picker.rail(0);

        pressRailAt(red, thumbCentre(paint(), red));

        assertEquals(0x80, picker.channel(0).value(), 1e-9,
                "a press landing on the thumb moved the value, so the two disagree about the axis");
    }

    @Test
    void aPressAQuarterAlongTheBoxIsThreeQuartersAlongTheRangeReadingRightToLeft() {
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.rgb(0x804020));
        Widget green = picker.rail(1);

        pressRailAt(green, travelX(green, 0.25f));

        assertEquals(191, picker.channel(1).value(), 1e-9,
                "a quarter of the travel in from the left is three quarters of the channel");
    }

    @Test
    void aPressAQuarterAlongTheBoxIsAQuarterAlongTheRangeReadingLeftToRight() {
        build(LayoutDirection.LTR);
        picker.setInitialColor(Color.rgb(0x804020));
        Widget green = picker.rail(1);

        pressRailAt(green, travelX(green, 0.25f));

        assertEquals(64, picker.channel(1).value(), 1e-9);
    }

    // ------------------------------------------------------------------ the sweep

    @Test
    void theChannelSweepTurnsRoundWithTheThumb() {
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.BLACK);
        Widget red = picker.rail(0);

        LinearGradient sweep = horizontalSweepIn(paint(), red);
        assertEquals(0, sweep.x0(), EPS, "the sweep still spans the whole band");
        assertEquals(red.width(), sweep.x1(), EPS);
        assertEquals(new Color(1, 0, 0, 1), sweep.start(),
                "the full end of the channel is on the trailing side, away from the thumb");
        assertEquals(new Color(0, 0, 0, 1), sweep.end(),
                "and the empty end is under the thumb resting at zero");
    }

    @Test
    void theChannelSweepIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR);
        picker.setInitialColor(Color.BLACK);

        LinearGradient sweep = horizontalSweepIn(paint(), picker.rail(0));
        assertEquals(new Color(0, 0, 0, 1), sweep.start());
        assertEquals(new Color(1, 0, 0, 1), sweep.end());
    }

    @Test
    void theHueChannelsBandsAreMeasuredBackFromTheLeadingEdge() {
        // Six bands rather than a gradient, and the one place the reflection is a walk over
        // several coordinates instead of one. The overlapping point has to move to the side the
        // next band is drawn on, or the seam it closes reopens and a point of ink hangs off the
        // far end of the rail instead.
        build(LayoutDirection.RTL);
        picker.setFormat(ColorPicker.Format.HSV);
        scene.layoutPass(WIDTH, HEIGHT);
        picker.setInitialColor(Color.hsv(0, 1, 1, 1));
        Widget hueRail = picker.rail(0);
        float band = hueRail.width() / BANDS;

        List<Fill> bands = horizontalSweepsIn(paint(), hueRail);
        assertEquals(BANDS, bands.size(), "one fill per band, whichever way they run");

        LinearGradient first = (LinearGradient) bands.get(0).paint();
        assertEquals(hueRail.width(), first.x1(), EPS,
                "hue zero belongs against the leading edge, under the thumb showing it");
        assertEquals(Color.hsv(0, 1, 1, 1), first.end());
        assertEquals(hueRail.width() - band, first.x0(), EPS);
        assertEquals(hueRail.localToSceneX() + hueRail.width() - band - 1, bands.get(0).x(), EPS,
                "the overlapping point goes toward the band drawn next, which is the left one");

        LinearGradient last = (LinearGradient) bands.get(BANDS - 1).paint();
        assertEquals(0, last.x0(), EPS, "the last band closes on the trailing edge");
    }

    @Test
    void theHueChannelsBandsAreUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR);
        picker.setFormat(ColorPicker.Format.HSV);
        scene.layoutPass(WIDTH, HEIGHT);
        picker.setInitialColor(Color.hsv(0, 1, 1, 1));
        Widget hueRail = picker.rail(0);
        float band = hueRail.width() / BANDS;

        List<Fill> bands = horizontalSweepsIn(paint(), hueRail);
        LinearGradient first = (LinearGradient) bands.get(0).paint();
        assertEquals(0, first.x0(), EPS);
        assertEquals(band, first.x1(), EPS);
        assertEquals(Color.hsv(0, 1, 1, 1), first.start());
        assertEquals(hueRail.localToSceneX(), bands.get(0).x(), EPS);
    }

    @Test
    void theTransparentEndOfTheAlphaSweepFollowsItsOwnZero() {
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.rgb(0xFF0000));

        LinearGradient sweep = horizontalSweepIn(paint(), picker.alphaRail());
        assertEquals(0f, sweep.end().a(), EPS,
                "alpha zero is the low end of the same axis, so it moves to the leading edge");
        assertEquals(1f, sweep.start().a(), EPS);
    }

    @Test
    void theAlphaSweepIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR);
        picker.setInitialColor(Color.rgb(0xFF0000));

        LinearGradient sweep = horizontalSweepIn(paint(), picker.alphaRail());
        assertEquals(0f, sweep.start().a(), EPS);
        assertEquals(1f, sweep.end().a(), EPS);
    }

    // ------------------------------------------------------------- the arrow keys

    @Test
    void theRailsHorizontalArrowsFollowTheThumbReadingRightToLeft() {
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.rgb(0x804020));
        focusRail(1);

        press(Keys.LEFT, 0);
        assertEquals(0x41, picker.channel(1).value(), 1e-9,
                "Left points at the high end of a mirrored rail, so it must raise the channel");
        press(Keys.RIGHT, 0);
        assertEquals(0x40, picker.channel(1).value(), 1e-9);
        press(Keys.LEFT, Keys.MOD_SHIFT);
        assertEquals(0x4A, picker.channel(1).value(), 1e-9, "Shift still moves ten of them");
    }

    @Test
    void theRailsVerticalArrowsAreUntouchedByTheDirection() {
        // The hazard the two arms were split for: Left was grouped with Down and Right with Up,
        // and swapping the arms wholesale would have inverted the half that names the value.
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.rgb(0x804020));
        focusRail(1);

        press(Keys.UP, 0);
        assertEquals(0x41, picker.channel(1).value(), 1e-9, "Up means more on any page");
        press(Keys.DOWN, 0);
        assertEquals(0x40, picker.channel(1).value(), 1e-9, "and Down means less");
    }

    @Test
    void theRailsArrowsAreUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR);
        picker.setInitialColor(Color.rgb(0x804020));
        focusRail(1);

        press(Keys.LEFT, 0);
        assertEquals(0x3F, picker.channel(1).value(), 1e-9);
        press(Keys.RIGHT, 0);
        assertEquals(0x40, picker.channel(1).value(), 1e-9);
    }

    @Test
    void homeEndAndThePageKeysNameTheValueInBothDirections() {
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.rgb(0x804020));
        focusRail(2);

        press(Keys.END, 0);
        assertEquals(255, picker.channel(2).value(), 1e-9, "End is the maximum, not a side");
        press(Keys.HOME, 0);
        assertEquals(0, picker.channel(2).value(), 1e-9, "and Home is the minimum");
        press(Keys.PAGE_UP, 0);
        assertEquals(10, picker.channel(2).value(), 1e-9, "a page up is ten units up the value");
        press(Keys.PAGE_DOWN, 0);
        assertEquals(0, picker.channel(2).value(), 1e-9);
    }

    // --------------------------------------------- what must NOT turn round

    @Test
    void theSaturationValueFieldDoesNotMirrorItsGradient() {
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.hsv(200, 0.25f, 1f, 1f));
        Widget plane = picker.saturationValueField();

        LinearGradient across = horizontalSweepIn(paint(), plane);
        assertEquals(Color.WHITE, across.start(),
                "white stays in the corner every picker puts it in: this is a colour space");
        assertEquals(Color.hsv(picker.hue(), 1, 1, 1), across.end());
    }

    @Test
    void theSaturationValueFieldsCursorDoesNotMirrorEither() {
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.hsv(200, 0.25f, 1f, 1f));
        Widget plane = picker.saturationValueField();

        assertEquals(plane.localToSceneX() + 0.25f * plane.width(), cursorX(paint(), plane), 0.05f,
                "the cursor rides saturation from the left, like the gradient under it");
    }

    @Test
    void theSaturationValueFieldsPressDoesNotMirrorEither() {
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.hsv(200, 0.8f, 0.8f, 1f));
        Widget plane = picker.saturationValueField();

        float x = plane.localToSceneX() + 0.25f * plane.width();
        float y = plane.localToSceneY() + plane.height() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        scene.inputBatchEnded();

        assertEquals(0.25f, picker.color().saturation(), 0.01f,
                "the press is the cursor's inverse and has to stay unreflected with it");
    }

    @Test
    void thePickersOwnArrowsWalkTheColourSpaceInBothDirections() {
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.hsv(200, 0.5f, 0.5f, 1f));
        picker.requestFocus();
        assertEquals(picker, scene.focusedWidget());

        press(Keys.RIGHT, 0);
        assertTrue(picker.color().saturation() > 0.5f,
                "Right walks saturation up in both directions, because the plane does not move");
        press(Keys.LEFT, 0);
        assertEquals(0.5f, picker.color().saturation(), 0.01f);
    }

    @Test
    void theHueRampPaintsTheSameEitherWay() {
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.hsv(120, 1f, 1f, 1f));
        Widget ramp = picker.hueRamp();
        Frame frame = paint();

        List<Fill> bands = gradientsIn(frame, ramp);
        assertEquals(BANDS, bands.size(), "the ramp is six bands whichever way the picker reads");
        for (Fill fill : bands) {
            LinearGradient g = (LinearGradient) fill.paint();
            assertEquals(g.x0(), g.x1(), EPS,
                    "every band on the ramp runs down the box; there is no x here to reflect");
            assertEquals(ramp.localToSceneX(), fill.x(), EPS);
            assertEquals(ramp.width(), fill.w(), EPS);
        }

        Fill marker = markerIn(frame, ramp);
        assertEquals(ramp.localToSceneX(), marker.x(), EPS, "the marker spans the full width");
        assertEquals(ramp.localToSceneY() + 120f / 360f * ramp.height(),
                marker.y() + marker.h() / 2, EPS, "and its only variable coordinate is a y");
    }

    // ------------------------------------------------------- the before/after pair

    @Test
    void theSwatchReadsTheColourItOpenedOnFirst() {
        build(LayoutDirection.RTL);
        picker.setInitialColor(Color.rgb(0xFF0000));
        Color opened = picker.color();
        picker.setColor(Color.rgb(0x0000FF));
        Color now = picker.color();
        assertNotEquals(opened, now, "the two halves have to be distinguishable");

        Widget swatch = picker.preview();
        Frame frame = paint();
        assertEquals(swatch.localToSceneX() + swatch.width() / 2, halfX(frame, swatch, opened), EPS,
                "before and after are an ordered pair, so the before is on the leading half");
        assertEquals(swatch.localToSceneX(), halfX(frame, swatch, now), EPS);
    }

    @Test
    void theSwatchIsUnchangedReadingLeftToRight() {
        build(LayoutDirection.LTR);
        picker.setInitialColor(Color.rgb(0xFF0000));
        Color opened = picker.color();
        picker.setColor(Color.rgb(0x0000FF));
        Color now = picker.color();

        Widget swatch = picker.preview();
        Frame frame = paint();
        assertEquals(swatch.localToSceneX(), halfX(frame, swatch, opened), EPS);
        assertEquals(swatch.localToSceneX() + swatch.width() / 2, halfX(frame, swatch, now), EPS);
    }

    // ------------------------------------------------------------------- driving

    private void focusRail(int index) {
        picker.rail(index).requestFocus();
        assertEquals(picker.rail(index), scene.focusedWidget(),
                "the rail never took focus, so the key went nowhere");
    }

    private void press(int key, int modifiers) {
        scene.keyEvent(key, true, false, modifiers);
        scene.inputBatchEnded();
    }

    private void pressRailAt(Widget rail, float sceneX) {
        float y = rail.localToSceneY() + rail.height() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, sceneX, y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, sceneX, y);
        scene.inputBatchEnded();
    }

    /**
     * A scene x that is {@code fraction} of the way along the rail's travel from the box's
     * <b>physical left</b> edge, thumb inset included. Physical on purpose: what the direction
     * decides is which value that coordinate names, so the coordinate itself must not know one.
     */
    private float travelX(Widget rail, float fraction) {
        float thumb = Theme.current().tokensFor(rail).colorThumbW();
        return rail.localToSceneX() + thumb / 2 + fraction * (rail.width() - thumb);
    }

    // ------------------------------------------------------------- reading a frame

    /** One recorded fill, in scene coordinates. */
    private record Fill(float x, float y, float w, float h, Paint paint) {
        float centreX() {
            return x + w / 2;
        }

        float centreY() {
            return y + h / 2;
        }
    }

    /** One recorded circle, in scene coordinates. */
    private record Ring(float cx, float cy, float radius) {
    }

    /** Everything one frame painted, with the child transforms already applied. */
    private record Frame(List<Fill> rects, List<Fill> roundRects, List<Ring> circles) {
    }

    private Frame paint() {
        // Damaged whole, so the frame is a full one: a partial pass could cull the very widget
        // the assertion is about and report it as having painted nothing.
        picker.invalidate();
        GeometryCanvas canvas = new GeometryCanvas(WIDTH, HEIGHT);
        scene.renderFrame(canvas);
        return new Frame(canvas.rects, canvas.roundRects, canvas.circles);
    }

    /**
     * The centre of the capsule {@code rail} drew for its thumb. Matched on the thumb's own two
     * extents and on the rail's vertical centre, so the rails stacked above and below it (each
     * drawing the same capsule) cannot answer for it.
     */
    private float thumbCentre(Frame frame, Widget rail) {
        SizeTokens t = Theme.current().tokensFor(rail);
        float top = rail.localToSceneY() + rail.height() / 2 - t.colorThumbH() / 2;
        for (Fill fill : frame.roundRects()) {
            if (Math.abs(fill.w() - t.colorThumbW()) < EPS
                    && Math.abs(fill.h() - t.colorThumbH()) < EPS
                    && Math.abs(fill.y() - top) < EPS) {
                return fill.centreX();
            }
        }
        throw new AssertionError("the rail painted no thumb");
    }

    /** The full-width bar {@code ramp} drew for its hue marker. */
    private Fill markerIn(Frame frame, Widget ramp) {
        for (Fill fill : frame.roundRects()) {
            if (Math.abs(fill.w() - ramp.width()) < EPS && covers(ramp, fill)) {
                return fill;
            }
        }
        throw new AssertionError("the ramp painted no marker");
    }

    /** The half of {@code swatch} filled with {@code colour}, as a scene x of its left edge. */
    private float halfX(Frame frame, Widget swatch, Color colour) {
        for (Fill fill : frame.rects()) {
            if (colour.equals(fill.paint()) && Math.abs(fill.w() - swatch.width() / 2) < EPS
                    && covers(swatch, fill)) {
                return fill.x();
            }
        }
        throw new AssertionError("the swatch painted no half in " + colour);
    }

    /** The x the saturation/value cursor was rung around. */
    private float cursorX(Frame frame, Widget plane) {
        for (Ring ring : frame.circles()) {
            if (ring.cx() >= plane.localToSceneX() - EPS
                    && ring.cx() <= plane.localToSceneX() + plane.width() + EPS
                    && ring.cy() >= plane.localToSceneY() - EPS
                    && ring.cy() <= plane.localToSceneY() + plane.height() + EPS) {
                return ring.cx();
            }
        }
        throw new AssertionError("the field painted no cursor");
    }

    private LinearGradient horizontalSweepIn(Frame frame, Widget widget) {
        List<Fill> found = horizontalSweepsIn(frame, widget);
        if (found.isEmpty()) {
            throw new AssertionError("nothing swept across " + widget);
        }
        return (LinearGradient) found.get(0).paint();
    }

    /** Every gradient fill in {@code widget} that runs across it, in the order it was painted. */
    private List<Fill> horizontalSweepsIn(Frame frame, Widget widget) {
        List<Fill> found = new ArrayList<>();
        for (Fill fill : gradientsIn(frame, widget)) {
            LinearGradient g = (LinearGradient) fill.paint();
            if (g.x0() != g.x1()) {
                found.add(fill);
            }
        }
        return found;
    }

    private List<Fill> gradientsIn(Frame frame, Widget widget) {
        List<Fill> found = new ArrayList<>();
        for (Fill fill : frame.rects()) {
            if (fill.paint() instanceof LinearGradient && covers(widget, fill)) {
                found.add(fill);
            }
        }
        return found;
    }

    /**
     * Whether {@code fill} belongs to {@code widget}, by its centre rather than by containment:
     * the hue bands overlap their neighbour by a point on purpose, so the outermost one hangs
     * past the rail's own edge and a containment test would drop exactly the band whose position
     * the reflection is about.
     */
    private boolean covers(Widget widget, Fill fill) {
        return fill.centreX() >= widget.localToSceneX()
                && fill.centreX() <= widget.localToSceneX() + widget.width()
                && fill.centreY() >= widget.localToSceneY()
                && fill.centreY() <= widget.localToSceneY() + widget.height();
    }

    /**
     * Records fills in scene coordinates by following the translations the paint walk pushes,
     * which is what lets an assertion name a widget's own box instead of a local offset that
     * every rail in the column shares.
     */
    private static final class GeometryCanvas extends FakeCanvas {

        private final List<Fill> rects = new ArrayList<>();
        private final List<Fill> roundRects = new ArrayList<>();
        private final List<Ring> circles = new ArrayList<>();
        private final Deque<float[]> saved = new ArrayDeque<>();
        private float tx;
        private float ty;

        GeometryCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void save() {
            super.save();
            saved.push(new float[] {tx, ty});
        }

        @Override
        public void restore() {
            if (!saved.isEmpty()) {
                float[] outer = saved.pop();
                tx = outer[0];
                ty = outer[1];
            }
            super.restore();
        }

        @Override
        public void restoreToCount(int count) {
            while (saveCount() > count && !saved.isEmpty()) {
                restore();
            }
            super.restoreToCount(count);
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            rects.add(new Fill(tx + x, ty + y, w, h, paint));
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            roundRects.add(new Fill(tx + roundRect.x(), ty + roundRect.y(),
                    roundRect.width(), roundRect.height(), paint));
        }

        @Override
        public void drawCircle(float cx, float cy, float radius, float strokeWidth, Paint paint) {
            circles.add(new Ring(tx + cx, ty + cy, radius));
        }
    }
}
