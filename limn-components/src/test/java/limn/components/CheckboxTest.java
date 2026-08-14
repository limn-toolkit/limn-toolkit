package limn.components;

import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckboxTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong();
    private Checkbox checkbox;
    private Scene scene;
    private FakeCanvas canvas;

    private void build(Checkbox.Variant variant) {
        checkbox = new Checkbox(variant, "option");
        scene = new Scene(checkbox, clock::get);
        scene.setTextRuler(RULER);
        canvas = new FakeCanvas(200, 40);
        scene.renderFrame(canvas); // initial layout + paint
    }

    private void frame(long millis) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        scene.renderFrame(canvas);
    }

    @Test
    void clickTogglesAndNotifies() {
        build(Checkbox.Variant.BOX);
        AtomicReference<Boolean> seen = new AtomicReference<>();
        checkbox.onChange(seen::set);

        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 9, 20);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 9, 20);
        scene.inputBatchEnded();
        assertTrue(checkbox.isChecked());
        assertEquals(Boolean.TRUE, seen.get());

        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 9, 20);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, 9, 20);
        scene.inputBatchEnded();
        assertFalse(checkbox.isChecked());
        assertEquals(Boolean.FALSE, seen.get());
    }

    @Test
    void spaceTogglesWhenFocused() {
        build(Checkbox.Variant.SWITCH);
        scene.requestFocus(checkbox);
        scene.keyEvent(Keys.SPACE, true, false, 0);
        scene.inputBatchEnded();
        assertTrue(checkbox.isChecked());
    }

    @Test
    void switchSlideAnimatesWithTheFrameClock() {
        build(Checkbox.Variant.SWITCH);
        checkbox.setChecked(true);
        assertEquals(0, checkbox.animationProgress(), 1e-3, "animation starts at 0");

        frame(0);   // first tick: dt == 0
        frame(50);  // 50ms of a 160ms slide
        float mid = checkbox.animationProgress();
        assertTrue(mid > 0.2f && mid < 0.5f, "mid-slide progress, got " + mid);

        frame(50);
        frame(50);
        frame(50);
        assertEquals(1, checkbox.animationProgress(), 1e-3, "slide completes");
    }

    @Test
    void togglingBackMidAnimationReversesSmoothly() {
        build(Checkbox.Variant.SWITCH);
        checkbox.setChecked(true);
        frame(0);
        frame(80); // half way
        float mid = checkbox.animationProgress();
        assertTrue(mid > 0.3f && mid < 0.8f, "got " + mid);

        checkbox.setChecked(false); // reverse mid-flight
        frame(50);
        assertTrue(checkbox.animationProgress() < mid, "progress heads back to 0");
        frame(200);
        assertEquals(0, checkbox.animationProgress(), 1e-3);
    }

    @Test
    void headlessSetCheckedJumpsWithoutScene() {
        Checkbox lone = new Checkbox(Checkbox.Variant.BOX, "solo");
        lone.setChecked(true);
        assertEquals(1, lone.animationProgress(), 1e-3, "no scene: no animation, final state");
    }

    // ------------------------------------------------------------- size steps

    /** Scene wired to the font-scaling ruler: the only one under which a ramp means anything. */
    private void scaled(Checkbox.Variant variant) {
        checkbox = new Checkbox(variant, "option");
        scene = new Scene(checkbox, clock::get);
        scene.setTextRuler(SCALED_RULER);
    }

    private Size measureAt(ControlSize step) {
        checkbox.setControlSize(step);
        return checkbox.measure(Constraints.loose(500, 200));
    }

    @Test
    void mediumBoxRowIsUnchangedUnderTheDegenerateRuler() {
        // The MEDIUM regression net: indicator 18 + gapLabel 6 + "option" at 10pt per code
        // point, in a row the 18pt box wins over the ruler's flat 12pt line height.
        build(Checkbox.Variant.BOX);
        Size size = checkbox.measure(Constraints.loose(500, 200));
        assertEquals(18 + 6 + 60, size.width(), 1e-3);
        assertEquals(18, size.height(), 1e-3);
    }

    @Test
    void theBoxRowFollowsTheIndicatorRamp() {
        scaled(Checkbox.Variant.BOX);
        // width = indicator + gapLabel + 0.6em x 6; height = max(indicator, 1.171875 x body).
        float[] widths = { 61.6f, 66.2f, 74.4f, 87.6f, 102.4f };
        float[] heights = { 18, 18, 18, 22, 24 };
        for (ControlSize step : ControlSize.values()) {
            Size size = measureAt(step);
            assertEquals(widths[step.ordinal()], size.width(), 1e-2, step + " width");
            assertEquals(heights[step.ordinal()], size.height(), 1e-3,
                    step + ": the indicator, not the line, decides the row");
        }
    }

    @Test
    void theSwitchRowFollowsTheTrackRamp() {
        scaled(Checkbox.Variant.SWITCH);
        float[] widths = { 83.6f, 88.2f, 96.4f, 112.6f, 133.4f };
        float[] heights = { 22, 22, 22, 26, 30 };
        for (ControlSize step : ControlSize.values()) {
            Size size = measureAt(step);
            assertEquals(widths[step.ordinal()], size.width(), 1e-2, step + " width");
            assertEquals(heights[step.ordinal()], size.height(), 1e-3, step + " height");
        }
    }

    @Test
    void theSwitchThumbStaysCentredAndInsideItsTrack() {
        // Parity: the thumb centre is trackH/2, so an odd track would put it on a half point,
        // and the travel span must stay symmetric about the track.
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            float trackH = t.switchTrackH();
            assertEquals(0f, trackH % 2, step + " switch track height is even");
            float inset = t.switchThumbInset();
            float radius = trackH / 2 - inset;
            assertTrue(radius > 0, step + " thumb has positive radius");
            assertEquals(trackH, 2 * (radius + inset), 1e-4,
                    step + " thumb fills the track minus its inset on both sides");
            float minX = inset + radius;
            float maxX = t.switchTrackW() - inset - radius;
            assertEquals(t.switchTrackW(), minX + maxX, 1e-4,
                    step + " travel is symmetric about the track centre");
            assertTrue(maxX > minX, step + " the thumb actually travels");
        }
    }

    @Test
    void theCheckMarkPathIsBitIdenticalWhereverTheIndicatorSitsOnEighteen() {
        // The scale factor is indicator/18f; IEEE-754 gives x/x == 1.0 exactly, and
        // literal * 1.0f == literal, so those steps reproduce the hand-tuned mark bit for bit.
        // This is what makes the MEDIUM compatibility claim a proof, not an approximation.
        for (ControlSize step : ControlSize.values()) {
            float indicator = SizeTokens.of(step).indicator();
            if (indicator == 18f) {
                assertEquals(1.0f, indicator / 18f, 0f, step + " scales the check path by exactly 1");
                assertEquals(9.5f, 9.5f * (indicator / 18f), 0f, step + " leaves the literal alone");
            }
        }
        assertEquals(18f, SizeTokens.MEDIUM.indicator(), 0f, "MEDIUM is the authored box");
    }

    @Test
    void baselineOffsetMatchesThePaintedTextBaseline() {
        // Flex.CrossAlignment.BASELINE reads this to align a row that mixes steps; it must be
        // the same expression onPaint uses, not the box centre.
        scaled(Checkbox.Variant.BOX);
        canvas = new FakeCanvas(200, 40);
        scene.renderFrame(canvas); // root is laid out tight to the canvas: height 40
        float body = SizeTokens.MEDIUM.body().size();
        float ascent = 0.927734375f * body;
        float textHeight = (0.927734375f + 0.244140625f) * body;
        assertEquals((40 - textHeight) / 2 + ascent, checkbox.textBaseline(), 1e-3);
    }

    @Test
    void anUnlabelledCheckboxHasNoTextBaseline() {
        checkbox = new Checkbox(Checkbox.Variant.BOX, "");
        scene = new Scene(checkbox, clock::get);
        scene.setTextRuler(SCALED_RULER);
        canvas = new FakeCanvas(200, 40);
        scene.renderFrame(canvas);
        assertEquals(checkbox.height(), checkbox.textBaseline(), 1e-3,
                "no text: fall back to the bottom edge, as Widget does");
    }
    /**
     * Checkbox BOX and RadioButton are in declared lockstep: same indicator token, same box,
     * interchangeable in a form column. An unlabelled pair must therefore report the SAME
     * baseline reference, or Flex.CrossAlignment.BASELINE (which takes the max across the row)
     * drops one of them by several points. This was a real divergence: RadioButton returned a
     * text baseline for text it never paints.
     */
    @Test
    void anUnlabelledRadioAndCheckboxReportTheSameBaselineReference() {
        for (limn.scene.ControlSize step : limn.scene.ControlSize.values()) {
            Checkbox box = new Checkbox(Checkbox.Variant.BOX, "");
            RadioButton radio = new RadioButton("");
            limn.scene.layout.Row row = new limn.scene.layout.Row();
            row.add(box);
            row.add(radio);
            row.setControlSize(step);
            Scene s = new Scene(row);
            s.setTextRuler(RULER);
            s.layoutPass(200, 60);

            assertEquals(box.height(), radio.height(), 1e-3, step + ": same box");
            assertEquals(baselineOf(box), baselineOf(radio), 1e-3,
                    step + ": an unlabelled pair aligns on the same reference");
        }
    }

    /** Reads the protected hook the way Flex does. */
    private static float baselineOf(limn.scene.Widget w) {
        return new limn.scene.layout.Row() {
            float read(limn.scene.Widget child) {
                return baselineOffsetOf(child);
            }
        }.read(w);
    }

    // -------------------------------------------------------------- focus ring

    /**
     * The checkbox focus gap is 1.5, matching RadioButton, and the figure is measurable rather
     * than a matter of taste: the box border is a 1.5pt pen centred on the 0.5 inset, so its
     * outer ink edge sits 0.25pt <em>outside</em> the nominal box. A ring drawn 1pt out puts its
     * own inner ink edge on exactly that line: two strokes touching, which reads as one thick
     * seam instead of a ring. 1.5 buys 0.5pt of clear ground.
     */
    @Test
    void theFocusRingClearsTheBoxBorderInk() {
        float borderOuterInk = Strokes.HALF_PIXEL_INSET - Strokes.INDICATOR_BORDER / 2; // -0.25
        float ringInnerInk = -Strokes.FOCUS_GAP_INDICATOR + Strokes.FOCUS_RING_THIN / 2; // -0.75
        assertTrue(ringInnerInk < borderOuterInk, "the ring must not start inside the border ink");
        assertEquals(0.5f, borderOuterInk - ringInnerInk, 1e-4,
                "0.5pt of clear ground between border ink and ring ink");
        // The old gap of 1 is exactly the degenerate case the rule in Strokes forbids.
        assertEquals(borderOuterInk, -1 + Strokes.FOCUS_RING_THIN / 2, 1e-4,
                "at a 1pt gap the two strokes were flush");
    }

    /**
     * The ring is the only ink outside the box, and it is outside on all four sides (the row IS
     * the indicator, and an unlabelled checkbox is exactly as wide as it). Scene grows invalidate
     * damage by 1pt of AA feather plus paintOutset, so an undeclared 2.25pt reach leaves a
     * quarter of the fading ring stale under partial rendering.
     */
    @Test
    void paintOutsetCoversTheFocusRingReach() {
        float reach = Strokes.FOCUS_GAP_INDICATOR + Strokes.FOCUS_RING_THIN / 2;
        assertEquals(2.25f, reach, 1e-4, "gap 1.5 + half of a 1.5pt centred pen");
        assertTrue(reach > 1, "Scene's built-in 1pt feather does not cover it on its own");
        for (Checkbox.Variant variant : Checkbox.Variant.values()) {
            assertTrue(outsetOf(variant) >= reach, variant + " declares the ring's reach");
        }
        assertEquals(outsetOf(Checkbox.Variant.BOX), radioOutset(), 1e-4,
                "lockstep: a mixed form column must damage the same rectangle for both");
    }

    /** Reads the protected hook the way Scene does. */
    private static float outsetOf(Checkbox.Variant variant) {
        return new Checkbox(variant, "") {
            float read() {
                return paintOutset();
            }
        }.read();
    }

    private static float radioOutset() {
        return new RadioButton("") {
            float read() {
                return paintOutset();
            }
        }.read();
    }

    // ------------------------------------------------------------- density

    /**
     * Pins the claim both class javadocs make: the toggle row is under WCAG 2.2 SC 2.5.8's 24pt
     * target at four of five steps, and the documented remedy (stacking on
     * {@link Tokens#toggleColumnGap}) actually reaches the 24pt pitch the Spacing exception
     * needs. A regression here silently invalidates the paragraph an app is told to trust.
     */
    @Test
    void stackedTogglesReachTheSpacingExceptionPitch() {
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            float row = t.indicator(); // BOX row: the indicator wins max(indicator, lineHeight)
            assertTrue(row <= Strokes.MIN_HIT_TARGET,
                    step + ": the row never exceeds the target it is measured against");
            assertTrue(row + t.toggleColumnGap() >= Strokes.MIN_HIT_TARGET,
                    step + ": stacked toggles reach a 24pt pitch");
        }
        assertEquals(18f, SizeTokens.MEDIUM.indicator(), 0f, "MEDIUM row is 18pt");
        assertEquals(6f, SizeTokens.MEDIUM.toggleColumnGap(), 0f, "18 + 6 = 24 exactly");
    }

}
