package limn.components;

import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButtonTest extends ComponentTestBase {

    private final AtomicInteger fired = new AtomicInteger();
    private ProbeButton button;
    private Scene scene;

    /** Exposes the protected baseline hook: a subclass may reach it, a bare test cannot. */
    private static class ProbeButton extends Button {
        ProbeButton(String text) {
            super(text);
        }

        float baseline() {
            return baselineOffset();
        }
    }

    private void build() {
        button = new ProbeButton("OK");
        button.onAction(fired::incrementAndGet);
        scene = new Scene(button);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 40);
    }

    /** Size-ramp assertions need the font-following ruler: RULER's lineHeight is a constant 12. */
    private Button scaled(ControlSize step) {
        Button b = new Button("OK");
        Scene s = new Scene(b);
        s.setTextRuler(SCALED_RULER);
        return b.withControlSize(step);
    }

    @Test
    void clickFiresTheActionOnce() {
        build();
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 10, 10);
        scene.inputBatchEnded();
        assertEquals(1, fired.get());
    }

    @Test
    void pressShowsArmedStateAndReleaseOutsideCancels() {
        build();
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.inputBatchEnded();
        assertTrue(button.isArmed(), "pressed visual state while held");

        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 500, 500); // release outside
        scene.inputBatchEnded();
        assertFalse(button.isArmed());
        assertEquals(0, fired.get(), "no click synthesized outside the button");
    }

    @Test
    void disabledButtonNeverFires() {
        build();
        button.setEnabled(false);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 10, 10);
        scene.inputBatchEnded();
        assertEquals(0, fired.get());
    }

    @Test
    void clickAlsoFocusesTheButton() {
        build();
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10);
        scene.inputBatchEnded();
        assertSame(button, scene.focusedWidget());
    }

    @Test
    void spaceAndEnterActivateWhenFocused() {
        build();
        scene.requestFocus(button);
        scene.keyEvent(Keys.SPACE, true, false, 0);
        scene.keyEvent(Keys.SPACE, false, false, 0);
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.keyEvent(Keys.ENTER, false, false, 0);
        scene.inputBatchEnded();
        assertEquals(2, fired.get());
    }

    @Test
    void keyRepeatDoesNotAutofire() {
        build();
        scene.requestFocus(button);
        scene.keyEvent(Keys.SPACE, true, false, 0);
        scene.keyEvent(Keys.SPACE, true, true, 0); // auto-repeat
        scene.keyEvent(Keys.SPACE, true, true, 0);
        scene.keyEvent(Keys.SPACE, false, false, 0);
        scene.inputBatchEnded();
        assertEquals(1, fired.get());
    }

    @Test
    void strayKeyUpNeverFiresDuringAMousePress() {
        // Regression (code review): Space held elsewhere + click-to-focus + key-up
        // must not double-fire: keyboard arming is independent of the mouse.
        build();
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 10, 10); // focuses + arms (mouse)
        scene.keyEvent(Keys.SPACE, false, false, 0);         // key-up without key-down here
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 10, 10);
        scene.inputBatchEnded();
        assertEquals(1, fired.get(), "exactly one activation for one physical click");
    }

    @Test
    void measureIncludesTokenPadding() {
        build();
        // "OK" = 20pt under RULER, plus one padH per side. The value is unchanged by the
        // migration (padH at MEDIUM *is* the old spacingLarge, 20); the token it names is not.
        SizeTokens t = SizeTokens.MEDIUM;
        assertEquals(20 + 2 * t.padH(),
                button.measure(Constraints.loose(500, 100)).width(), 1e-3);
    }

    @Test
    void heightIsTheControlHeightFloorAtEveryStep() {
        // The label never outgrows the box on this ramp, so resolvedHeight is the floor
        // at all five steps: 24 / 28 / 32 / 40 / 50, every one an even integer.
        float[] expected = { 24, 28, 32, 40, 50 };
        for (ControlSize step : ControlSize.values()) {
            assertEquals(expected[step.ordinal()],
                    scaled(step).measure(Constraints.loose(500, 200)).height(), 1e-3,
                    "height at " + step);
        }
    }

    @Test
    void mediumHeightIsExactly32() {
        // Decision 3, the feature's one deliberate MEDIUM change: the old
        // lineHeight + 2*spacingSmall + 4 gave 32.40625 under the real vertical ratios.
        assertEquals(32, scaled(ControlSize.MEDIUM).measure(Constraints.loose(500, 200)).height(),
                1e-3);
    }

    @Test
    void widthFollowsBothTheTypeAndSpacingRamps() {
        // "OK" is 1.2 x body under SCALED_RULER; padH is the spacing ramp's largest step.
        float[] expected = { 37.2f, 46.4f, 56.8f, 71.2f, 86.8f };
        for (ControlSize step : ControlSize.values()) {
            assertEquals(expected[step.ordinal()],
                    scaled(step).measure(Constraints.loose(500, 200)).width(), 1e-3,
                    "width at " + step);
        }
    }

    @Test
    void baselineOffsetMatchesThePaintedBaseline() {
        build();
        // The box is 40 tall (tight layout), RULER's ink box 10 with ascent 8:
        // (40 - 10)/2 + 8: the very expression onPaint draws the label with.
        assertEquals(23, button.baseline(), 1e-3);
    }

    // ------------------------------------------------ the pixel-locked stroke rule

    /**
     * The toolkit's whole pen vocabulary: six distinct values, because the aliases collapse
     * onto them: HAIRLINE / CARET / IME_UNDERLINE are 1 like BORDER, CHECK_MARK /
     * IME_UNDERLINE_ACTIVE are 2 like FOCUS_RING, INDICATOR_BORDER is 1.5 like
     * FOCUS_RING_THIN. The multiset assertions below pin <em>which</em> of these a Button
     * paints; this set pins that a recorded width is a declared {@link Strokes} weight at
     * all, which a token-derived or mid-fade fractional width fails even in the (unlikely)
     * case that it repeated identically at all five steps.
     */
    private static final Set<Float> LOCKED_PENS = Set.of(
            Strokes.BORDER, Strokes.FOCUS_RING_THIN, Strokes.ARROW_PEN,
            Strokes.MENU_CHECK_PEN, Strokes.FOCUS_RING, Strokes.TAB_INDICATOR);

    /** Drives the focus fade; a real clock would make the settled state a race. */
    private final AtomicLong strokeClock = new AtomicLong();

    /**
     * Renders until every {@link limn.animation.Transition} on the scene has reached its
     * endpoint <em>exactly</em>. Two frames are needed and neither is optional: a ticker's
     * first frame carries {@code dt == 0} by contract, and the second jumps a whole second,
     * far past {@code animFocus} (0.14 s), so {@code Transition.tick} takes its
     * {@code t >= 1} branch and assigns the target verbatim instead of an eased approximation.
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
        // The pixel-lock rule, checked mechanically rather than by reading a diff. A Button
        // paints at most two strokes, the secondary outline (BORDER) and the focus ring
        // (FOCUS_RING), and SECONDARY is the interesting variant: the primary fill draws no
        // outline at all, so a primary button's multiset would be empty and prove nothing.
        // Button is not one of the four text-cluster components, so neither width is animated
        // (the ring fades in ALPHA, at a fixed 2pt); the fade still has to settle, because
        // below 0.001 the ring is not painted at all and the count would depend on the frame.
        List<Float> restingMedium = null;
        List<Float> focusedMedium = null;
        for (ControlSize step : ControlSize.values()) {
            Button b = new Button("OK").setSecondary(true).withControlSize(step);
            Scene host = new Scene(b, strokeClock::get);
            host.setTextRuler(SCALED_RULER);
            Size box = b.measure(Constraints.loose(1000, 1000));
            float w = box.width();
            float h = box.height();

            List<Float> resting = strokesOf(host, w, h);
            assertEquals(List.of(Strokes.BORDER), resting,
                    step + " at rest paints one unscaled outline and nothing else");
            assertEveryWidthIsALockedPen(resting, step);

            host.requestFocus(b);
            settle(host, w, h);
            List<Float> focused = strokesOf(host, w, h);
            assertEquals(List.of(Strokes.BORDER, Strokes.FOCUS_RING), focused,
                    step + " focused adds the ring; the outline underneath does not thicken");
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
