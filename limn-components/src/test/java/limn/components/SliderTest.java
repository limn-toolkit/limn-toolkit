package limn.components;

import limn.graphics.Paint;
import limn.graphics.RoundRect;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Slider}: clamping/snapping, click-to-jump, drag, and keyboard, driven headlessly. */
class SliderTest extends ComponentTestBase {

    // Derived, never baked: the geometry below has to follow the token row, or the day
    // sliderKnobHover moves these tests keep passing against coordinates that no longer point
    // at the track. MEDIUM: pad 14 (knobHover 10 + FOCUS_GAP_SLIDER 3 + BORDER 1; the pad
    // carries the ring's own stroke, which is what makes the height 28 rather than 26 and keeps
    // a focused ring from losing half its width off the ends of the track). The Slider
    // carries no text, so the degenerate RULER cannot influence any number here.
    private static final SizeTokens MD = SizeTokens.MEDIUM;
    private static final float PAD = MD.sliderPad();
    private static final float TRACK = 200; // round, so the click fractions stay exact
    private static final float WIDTH = 2 * PAD + TRACK;
    private static final float HEIGHT = MD.sliderHeight();

    private Slider slider;
    private Scene scene;
    private AtomicReference<Float> changed;

    /** Scene-local x for a fraction of the track: the inverse of {@code Slider.thumbX}. */
    private static float trackX(float fraction) {
        return PAD + fraction * TRACK;
    }

    private void build(Slider s) {
        slider = s;
        changed = new AtomicReference<>();
        slider.onChange(changed::set);
        scene = new Scene(slider);
        scene.setTextRuler(RULER);
        scene.layoutPass(WIDTH, HEIGHT);
    }

    @Test
    void clampsAndSnapsProgrammaticSetValueWithoutFiring() {
        build(new Slider(0, 100).setStep(10));
        slider.setValue(200);
        assertEquals(100f, slider.value());
        slider.setValue(-5);
        assertEquals(0f, slider.value());
        slider.setValue(23);
        assertEquals(20f, slider.value(), "snapped to the nearest step");
        assertNull(changed.get(), "programmatic setValue does not fire onChange");
    }

    @Test
    void clickingTheTrackJumpsToThePointerAndFires() {
        build(new Slider(0, 100));
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, trackX(0.75f), HEIGHT / 2);
        scene.inputBatchEnded();
        assertEquals(75f, slider.value(), 0.5f);
        assertEquals(75f, changed.get(), 0.5f, "a user change fires onChange");
    }

    @Test
    void draggingUpdatesTheValueAndClampsAtTheEnd() {
        build(new Slider(0, 100));
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, trackX(0.5f), HEIGHT / 2);
        scene.inputBatchEnded();
        assertEquals(50f, slider.value(), 0.5f);
        scene.mouseMoved(2 * WIDTH, HEIGHT / 2); // drag past the right end
        scene.inputBatchEnded();
        assertEquals(100f, slider.value(), 0.5f);
    }

    @Test
    void keyboardNudgesByStepAndJumpsToEnds() {
        build(new Slider(0, 100).setStep(10).setValue(50));
        scene.focusTraverse(false);
        assertSame(slider, scene.focusedWidget());

        scene.keyEvent(Keys.RIGHT, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(60f, slider.value());
        assertEquals(60f, changed.get());

        scene.keyEvent(Keys.HOME, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(0f, slider.value());

        scene.keyEvent(Keys.END, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(100f, slider.value());
    }

    @Test
    void aDragFiresOnChangePerStepAndOnCommitOnceAtTheEnd() {
        build(new Slider(0, 100));
        java.util.List<Float> changes = new java.util.ArrayList<>();
        java.util.List<Float> commits = new java.util.ArrayList<>();
        slider.onChange(changes::add);
        slider.onCommit(commits::add);

        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, trackX(0.2f), HEIGHT / 2);
        scene.inputBatchEnded();
        scene.mouseMoved(trackX(0.5f), HEIGHT / 2);
        scene.inputBatchEnded();
        scene.mouseMoved(trackX(0.8f), HEIGHT / 2);
        scene.inputBatchEnded();
        assertEquals(3, changes.size(), "a preview per position the thumb passed through");
        assertEquals(0, commits.size(), "and no decision while it is still moving");

        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, trackX(0.8f), HEIGHT / 2);
        scene.inputBatchEnded();
        assertEquals(1, commits.size(), "exactly one when the user lets go");
        assertEquals(80f, commits.get(0), 0.5f, "carrying the value they settled on");
    }

    @Test
    void aDragThatEndsWhereItBeganStillCommits() {
        build(new Slider(0, 100).setValue(40));
        java.util.List<Float> commits = new java.util.ArrayList<>();
        slider.onCommit(commits::add);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, trackX(0.4f), HEIGHT / 2);
        scene.inputBatchEnded();
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, trackX(0.4f), HEIGHT / 2);
        scene.inputBatchEnded();
        assertEquals(1, commits.size(), "the user still chose that value");
    }

    @Test
    void aKeyboardChangeIsItsOwnCommit() {
        build(new Slider(0, 100).setStep(10).setValue(50));
        java.util.List<Float> commits = new java.util.ArrayList<>();
        slider.onCommit(commits::add);
        scene.focusTraverse(false);
        scene.keyEvent(Keys.RIGHT, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(1, commits.size(), "there is no drag to end: the change is the decision");
        assertEquals(60f, commits.get(0));
    }

    @Test
    void setValueCommitsNothing() {
        build(new Slider(0, 100));
        java.util.List<Float> commits = new java.util.ArrayList<>();
        slider.onCommit(commits::add);
        slider.setValue(70);
        assertEquals(0, commits.size(), "a programmatic change is not a user's decision");
    }

    @Test
    void endReachesMaxEvenWhenStepDoesNotDivideTheRange() {
        build(new Slider(0, 100).setStep(30)); // grid 0,30,60,90: 100 is off-grid
        scene.focusTraverse(false);
        scene.keyEvent(Keys.END, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(100f, slider.value(), "End reaches max despite the off-grid step");
        scene.keyEvent(Keys.HOME, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(0f, slider.value());
    }

    /**
     * Records the rail's fill rect and the thumb's circle so the parity invariant
     * can be asserted: everything the slider centers vertically must land on whole
     * logical points, or its long edges antialias into two half-covered rows
     * (fills are not pixel-snapped; only strokes are, in the backend).
     */
    private static final class GeometryCanvas extends FakeCanvas {
        RoundRect firstFill;
        float circleCy = Float.NaN;
        float circleRadius = Float.NaN;

        GeometryCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillRoundRect(RoundRect roundRect, Paint paint) {
            if (firstFill == null) {
                firstFill = roundRect; // the unfilled rail, painted before the fill and thumb
            }
        }

        @Override
        public void fillCircle(float cx, float cy, float radius, Paint paint) {
            circleCy = cy;
            circleRadius = radius;
        }
    }

    @Test
    void centeredRailAndThumbLandOnWholePointsSoTheirEdgesStayCrisp() {
        build(new Slider(0, 100).setValue(50));
        GeometryCanvas canvas = new GeometryCanvas(WIDTH, HEIGHT);
        scene.renderFrame(canvas);

        RoundRect rail = canvas.firstFill;
        assertNotNull(rail, "the rail is painted with fillRoundRect");
        // The rail is centered: trackTop = (height - thickness) / 2. The height is
        // 2 * pad() and therefore even, so the thickness must be even too: a 5pt
        // rail put trackTop at 10.5 and blurred the toolkit's longest straight run.
        assertEquals(rail.y(), Math.rint(rail.y()),
                "rail top is a whole point (thickness must share the height's parity)");
        assertEquals(rail.height(), Math.rint(rail.height()), "rail thickness is a whole point");
        assertEquals(0f, (slider.height() - rail.height()) % 2,
                "height - thickness is even, so centering cannot land on a half point");

        // The thumb at MEDIUM happens to have a whole centre and radius too. This is a MEDIUM
        // observation, NOT the parity rule: ADR 002 3.6(1) exempts circle geometry, because a
        // perimeter is antialiased whatever its coordinates, and the knob ramp spends its halves
        // there deliberately (6.5 / 12.5). The per-step test below asserts only the rail.
        assertEquals(canvas.circleCy, Math.rint(canvas.circleCy), "thumb center is a whole point");
        assertEquals(canvas.circleRadius, Math.rint(canvas.circleRadius),
                "thumb radius is a whole point");
    }

    /**
     * The parity invariant at <em>every</em> step, asserted against the geometry the component
     * actually paints rather than against the token table: a Slider that stopped reading
     * {@code sliderRail()} would still pass a table-only check.
     */
    @Test
    void everyStepCentresTheRailOnWholePoints() {
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            Slider s = new Slider(0, 100).setValue(50);
            s.setControlSize(step);
            float w = 2 * t.sliderPad() + TRACK;
            float h = t.sliderHeight();
            // Loose first: layoutPass constrains tightly, so only this proves onMeasure asked
            // for the token height rather than merely accepting whatever the parent handed down.
            assertEquals(h, s.measure(Constraints.loose(1000, 1000)).height(),
                    step + ": measured height follows sliderHeight()");

            Scene stepScene = new Scene(s);
            stepScene.setTextRuler(RULER);
            stepScene.layoutPass(w, h);

            GeometryCanvas canvas = new GeometryCanvas(w, h);
            stepScene.renderFrame(canvas);
            RoundRect rail = canvas.firstFill;
            assertNotNull(rail, step + ": the rail is painted");
            assertEquals(t.sliderRail(), rail.height(), step + ": rail thickness is the token");
            assertEquals(0f, (h - rail.height()) % 2,
                    step + ": height - rail must be even (24-4, 24-4, 28-6, 33-7, 38-8)");
            assertEquals(rail.y(), Math.rint(rail.y()), step + ": rail top is a whole point");
        }
    }

    /**
     * The Slider is the one control where {@link Strokes#MIN_HIT_TARGET} genuinely binds: its
     * height comes from a focus-ring constant, not from the shared control-height ramp, so at
     * XSMALL the natural 2*10.5 = 21 is lifted to 24.
     */
    @Test
    void theHitFloorBindsAtXsmallAndEveryStepClearsIt() {
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            assertTrue(t.sliderHeight() >= Strokes.MIN_HIT_TARGET,
                    step + " meets the 24pt pointer target in paint");
        }
        SizeTokens xs = SizeTokens.of(ControlSize.XSMALL);
        assertTrue(2 * xs.sliderPad() < Strokes.MIN_HIT_TARGET,
                "XSMALL's natural height is below the floor, so the clamp is not decorative");
        assertEquals(Strokes.MIN_HIT_TARGET, xs.sliderHeight());
    }

    /**
     * The free axis (ADR 002 4.3): the preferred length does not move with the step, and must
     * stay equal to ProgressBar's. This is why the Slider is exempt from the strict width
     * monotonicity check in {@code ControlSizeTest}.
     */
    @Test
    void thePreferredLengthIsIdenticalAtEveryStep() {
        float expected = Float.NaN;
        for (ControlSize step : ControlSize.values()) {
            Slider s = new Slider(0, 100);
            s.setControlSize(step);
            float w = s.measure(Constraints.loose(1000, 1000)).width();
            if (Float.isNaN(expected)) {
                expected = w;
            }
            assertEquals(expected, w, step + ": length is a free axis, identical at every step");
        }
    }

    // ------------------------------------------------ the pixel-locked stroke rule

    /**
     * The toolkit's whole pen vocabulary: six distinct values, because the aliases collapse
     * onto them: HAIRLINE / CARET / IME_UNDERLINE are 1 like BORDER, CHECK_MARK /
     * IME_UNDERLINE_ACTIVE are 2 like FOCUS_RING, INDICATOR_BORDER is 1.5 like
     * FOCUS_RING_THIN. The multiset assertions pin <em>which</em> of these the Slider paints;
     * this set pins that a recorded width is a declared {@link Strokes} weight at all.
     */
    private static final Set<Float> LOCKED_PENS = Set.of(
            Strokes.BORDER, Strokes.FOCUS_RING_THIN, Strokes.ARROW_PEN,
            Strokes.MENU_CHECK_PEN, Strokes.FOCUS_RING, Strokes.TAB_INDICATOR);

    /** Drives the focus fade; a real clock would make the settled state a race. */
    private final AtomicLong strokeClock = new AtomicLong();

    /**
     * Renders until every {@link limn.animation.Transition} on the scene has reached its
     * endpoint <em>exactly</em>. Two frames and neither is optional: a ticker's first frame
     * carries {@code dt == 0} by contract, and only the second, a whole second later, far
     * past {@code animFocus} (0.14 s), takes {@code Transition.tick}'s {@code t >= 1} branch
     * and assigns the target verbatim instead of an eased approximation.
     */
    private void settle(Scene host, float w, float h) {
        FakeCanvas warm = new FakeCanvas(w, h);
        host.renderFrame(warm);
        strokeClock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        host.renderFrame(warm);
    }

    /** One full frame's stroke widths, sorted. Partial rendering is off, so nothing is culled. */
    private static List<Float> strokesOf(Scene host, float w, float h) {
        StrokeRecordingCanvas canvas = new StrokeRecordingCanvas(w, h);
        host.renderFrame(canvas);
        return canvas.widths();
    }

    private static void assertEveryWidthIsALockedPen(List<Float> widths, ControlSize step) {
        for (float pen : widths) {
            assertTrue(LOCKED_PENS.contains(pen),
                    step + " strokes " + pen + ", which is not a Strokes weight");
        }
    }

    @Test
    void everyStrokeIsIdenticalAtEveryStep() {
        // The pixel-lock rule, checked mechanically rather than by reading a diff, on the one
        // control whose HEIGHT is derived from a focus-ring constant. The knob's diameter
        // triples across the ramp (5 -> 12 at rest) while its outline stays INDICATOR_BORDER
        // and the ring around it stays FOCUS_RING: exactly the size-vs-weight separation the
        // rule exists to state. The rail and the fill are fillRoundRects and record nothing.
        //
        // The focused pass is what makes the pad visible from a test: sliderPad() includes the
        // ring's own BORDER at MEDIUM too, so the 2pt ring recorded here is fully inside the
        // box at every step instead of being clipped in half at the ends of the track.
        List<Float> restingMedium = null;
        List<Float> focusedMedium = null;
        for (ControlSize step : ControlSize.values()) {
            Slider s = new Slider(0, 100).setValue(50);
            s.setControlSize(step);
            Scene host = new Scene(s, strokeClock::get);
            host.setTextRuler(RULER);
            Size box = s.measure(Constraints.loose(1000, 1000));
            float w = box.width();
            float h = box.height();

            List<Float> resting = strokesOf(host, w, h);
            assertEquals(List.of(Strokes.INDICATOR_BORDER), resting,
                    step + " at rest paints one unscaled knob outline and nothing else");
            assertEveryWidthIsALockedPen(resting, step);

            host.requestFocus(s);
            settle(host, w, h);
            List<Float> focused = strokesOf(host, w, h);
            assertEquals(List.of(Strokes.INDICATOR_BORDER, Strokes.FOCUS_RING), focused,
                    step + " focused adds the ring; the knob outline keeps its own weight");
            assertEveryWidthIsALockedPen(focused, step);

            if (restingMedium == null) {
                restingMedium = resting;
                focusedMedium = focused;
            }
            assertEquals(restingMedium, resting, step + " matches the first step's resting multiset");
            assertEquals(focusedMedium, focused, step + " matches the first step's focused multiset");
        }
    }
}
