package limn.components;

import limn.input.Keys;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Spinner}: numeric + time stepping via buttons, wheel and keyboard, headless. */
class SpinnerTest extends ComponentTestBase {

    /** The laid-out box and the stepper coordinates, derived rather than baked in. */
    private static final SizeTokens MEDIUM = SizeTokens.MEDIUM;
    private static final float BOX_W = MEDIUM.spinnerWidth();          // 140
    private static final float BOX_H = MEDIUM.controlHeight();         // 32
    private static final float BUTTON_X = BOX_W - MEDIUM.spinnerButtonW() / 2; // 127
    private static final float UP_Y = BOX_H / 4;                       // 8
    private static final float DOWN_Y = BOX_H * 3 / 4;                 // 24
    private static final float MID_Y = BOX_H / 2;                      // 16
    /** Where the value area ends and the stepper column starts. */
    private static final float VALUE_RIGHT = BOX_W - MEDIUM.spinnerButtonW();

    private Spinner spinner;
    private Scene scene;
    private AtomicReference<Double> changed;
    private TextFieldTest.MockClipboard clipboard;

    private void build(Spinner s) {
        spinner = s;
        changed = new AtomicReference<>();
        spinner.onChange(changed::set);
        scene = new Scene(spinner);
        scene.setTextRuler(RULER);
        clipboard = new TextFieldTest.MockClipboard();
        scene.setClipboard(clipboard);
        // The MEDIUM box: spinnerWidth 140 x controlHeight 32 (was 34 before the height
        // unification). Value area [0,114), buttons x in [114,140], mid y = 16.
        scene.layoutPass(BOX_W, BOX_H);
    }

    @Test
    void clampsProgrammaticSetValueWithoutFiring() {
        build(new Spinner(0, 10, 1));
        spinner.setValue(100);
        assertEquals(10.0, spinner.value());
        spinner.setValue(-5);
        assertEquals(0.0, spinner.value());
        assertNull(changed.get());
    }

    @Test
    void upAndDownButtonsStepAndClamp() {
        build(new Spinner(0, 10, 1).setValue(5));
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, BUTTON_X, UP_Y); // up button (top-right)
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, BUTTON_X, UP_Y);
        scene.inputBatchEnded();
        assertEquals(6.0, spinner.value());
        assertEquals(6.0, changed.get());

        spinner.setValue(0);
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, BUTTON_X, DOWN_Y); // down button (bottom-right)
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, BUTTON_X, DOWN_Y);
        scene.inputBatchEnded();
        assertEquals(0.0, spinner.value(), "clamped at min");
    }

    @Test
    void theWheelDoesNotChangeTheValue() {
        build(new Spinner(0, 10, 1).setValue(5));
        scene.scrolled(0, 1, 60, MID_Y); // wheel up over the value area
        scene.inputBatchEnded();
        assertEquals(5.0, spinner.value(), "the wheel is not a value gesture");
        scene.scrolled(0, -1, 60, MID_Y);
        scene.inputBatchEnded();
        assertEquals(5.0, spinner.value());
    }

    /**
     * The reported defect, as a test: scrolling a property panel stalled and edited a field
     * whenever the pointer crossed a spinner. Mouse events bubble until something consumes
     * them, so the fix is that the spinner never consumes the wheel, and the assertion that
     * matters is not just "the value held" but "the scroller actually moved".
     */
    @Test
    void theWheelReachesAScrollingAncestorInsteadOfBeingSwallowed() {
        Spinner inspectorField = new Spinner(0, 10, 1).setValue(5);
        Column content = new Column();
        content.add(inspectorField);
        for (int i = 0; i < 20; i++) {
            content.add(new Label("row " + i)); // enough content to make the view scrollable
        }
        ScrollView panel = new ScrollView(content);
        Scene panelScene = new Scene(panel);
        panelScene.setTextRuler(RULER);
        panelScene.layoutPass(200, 100);

        // The pointer sits over the spinner, which is the whole point of the bug.
        panelScene.scrolled(0, -1, 60, 10);
        panelScene.inputBatchEnded();

        assertEquals(5.0, inspectorField.value(), "the spinner under the pointer is untouched");
        assertEquals(48, panel.offsetY(), 1e-3, "and the panel scrolled one notch");
    }

    @Test
    void keyboardStepsAndJumpsToBounds() {
        build(new Spinner(0, 10, 2).setValue(4));
        scene.focusTraverse(false);
        scene.keyEvent(Keys.UP, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(6.0, spinner.value());
        scene.keyEvent(Keys.END, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(10.0, spinner.value());
        scene.keyEvent(Keys.HOME, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(0.0, spinner.value());
    }

    @Test
    void numericDecimalsInferredFromStep() {
        build(new Spinner(0, 1, 0.25).setValue(0.5));
        assertEquals("0.50", spinner.text());
    }

    @Test
    void timeFormatsAndStepsHoursThenMinutes() {
        build(Spinner.time().setValue(7 * 60 + 30)); // 07:30
        assertEquals("07:30", spinner.text());
        scene.focusTraverse(false);

        scene.keyEvent(Keys.UP, true, false, 0); // hours field active by default → +60
        scene.inputBatchEnded();
        assertEquals("08:30", spinner.text());

        scene.keyEvent(Keys.RIGHT, true, false, 0); // move to the minutes field
        scene.keyEvent(Keys.UP, true, false, 0);    // → +1 minute
        scene.inputBatchEnded();
        assertEquals("08:31", spinner.text());
    }

    @Test
    void numericEndAndUpReachMaxWhenStepDoesNotDivideTheRange() {
        build(new Spinner(0, 100, 7)); // grid tops out at 98; 100 is off-grid
        scene.focusTraverse(false);
        scene.keyEvent(Keys.END, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(100.0, spinner.value(), "End reaches max even off-grid");

        spinner.setValue(98); // the highest grid point
        scene.keyEvent(Keys.UP, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(100.0, spinner.value(), "Up from the top grid point advances to max");
    }

    @Test
    void shiftArrowMatchesPageUp() {
        // The point of the binding: PageUp is missing from laptop keyboards.
        build(new Spinner(0, 100, 2).setValue(50));
        scene.focusTraverse(false);
        scene.keyEvent(Keys.UP, true, false, Keys.MOD_SHIFT);
        scene.inputBatchEnded();
        assertEquals(70.0, spinner.value(), "Shift+Up is ten steps");

        scene.keyEvent(Keys.PAGE_DOWN, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(50.0, spinner.value(), "and PageDown still undoes exactly that");
    }

    @Test
    void altArrowStepsOneUnitOfTheLastDisplayedDigit() {
        build(new Spinner(0, 10, 0.05).setValue(1)); // two decimals shown
        scene.focusTraverse(false);
        scene.keyEvent(Keys.UP, true, false, Keys.MOD_ALT);
        scene.inputBatchEnded();
        assertEquals(1.01, spinner.value(), 1e-9, "a fine step must be visible on screen");
        assertEquals("1.01", spinner.text());
    }

    @Test
    void altNeverStepsFinerThanTheDisplayCanShow() {
        // step 1 shows no decimals, so there is nothing finer to offer: Alt is a
        // plain step rather than an invisible one.
        build(new Spinner(0, 10, 1).setValue(5));
        scene.focusTraverse(false);
        scene.keyEvent(Keys.UP, true, false, Keys.MOD_ALT);
        scene.inputBatchEnded();
        assertEquals(6.0, spinner.value());
    }

    @Test
    void fineStepsSnapToTheirOwnGridAndCoarseStepsRealign() {
        build(new Spinner(0, 10, 0.05).setValue(1));
        scene.focusTraverse(false);
        scene.keyEvent(Keys.UP, true, false, Keys.MOD_ALT);
        scene.keyEvent(Keys.UP, true, false, Keys.MOD_ALT);
        scene.inputBatchEnded();
        assertEquals(1.02, spinner.value(), 1e-9, "fine steps walk the fine grid");

        scene.keyEvent(Keys.UP, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(1.05, spinner.value(), 1e-9, "a plain step returns to the step grid");
    }

    @Test
    void altWinsWhenBothModifiersAreHeld() {
        build(new Spinner(0, 10, 0.05).setValue(1));
        scene.focusTraverse(false);
        scene.keyEvent(Keys.UP, true, false, Keys.MOD_ALT | Keys.MOD_SHIFT);
        scene.inputBatchEnded();
        assertEquals(1.01, spinner.value(), 1e-9);
    }

    @Test
    void modifiedArrowsAlsoWorkSidewaysInNumericMode() {
        build(new Spinner(0, 100, 2).setValue(50));
        scene.focusTraverse(false);
        scene.keyEvent(Keys.RIGHT, true, false, Keys.MOD_SHIFT);
        scene.inputBatchEnded();
        assertEquals(70.0, spinner.value());
    }

    @Test
    void timeModeIgnoresTheModifiers() {
        // Hours vs minutes is already the coarse/fine split; Shift must not turn an
        // hour bump into ten of them.
        build(Spinner.time().setValue(7 * 60 + 30));
        scene.focusTraverse(false);
        scene.keyEvent(Keys.UP, true, false, Keys.MOD_SHIFT);
        scene.inputBatchEnded();
        assertEquals("08:30", spinner.text());

        scene.keyEvent(Keys.UP, true, false, Keys.MOD_ALT);
        scene.inputBatchEnded();
        assertEquals("09:30", spinner.text());
    }

    @Test
    void modifiedArrowsRespectBoundsAndFireOnce() {
        build(new Spinner(0, 10, 1).setValue(9));
        scene.focusTraverse(false);
        scene.keyEvent(Keys.UP, true, false, Keys.MOD_SHIFT); // +10 → clamped
        scene.inputBatchEnded();
        assertEquals(10.0, spinner.value());
        assertEquals(10.0, changed.get());
    }

    @Test
    void snapsToTheStepGridByDefault() {
        build(new Spinner(1, 100_000, 50));
        spinner.setValue(1200);
        assertEquals(1201.0, spinner.value(), "the grid is anchored at min, so 1200 is off it");
    }

    @Test
    void snappingCanBeTurnedOffForValuesTheSpinnerDoesNotOwn() {
        build(new Spinner(1, 100_000, 50).setSnapToStep(false));
        spinner.setValue(1200);
        assertEquals(1200.0, spinner.value(), "an inspector must show the model's value verbatim");
        spinner.setValue(0.5);
        assertEquals(1.0, spinner.value(), "bounds still clamp");
    }

    @Test
    void steppingStaysExactWithoutSnapping() {
        build(new Spinner(0, 100, 10).setSnapToStep(false).setValue(37));
        scene.keyEvent(Keys.UP, true, false, 0);
        scene.inputBatchEnded();
        assertEquals(47.0, spinner.value(), "stepping moves by step from the value actually held");
    }

    @Test
    void timeRejectsOutOfDayBounds() {
        assertThrows(IllegalArgumentException.class, () -> Spinner.time(-90, 60, 15));
        assertThrows(IllegalArgumentException.class, () -> Spinner.time(0, 24 * 60, 1));
    }

    @Test
    void timeClampsAtTheUpperBound() {
        build(Spinner.time().setValue(23 * 60 + 59)); // 23:59, hours field
        scene.focusTraverse(false);
        scene.keyEvent(Keys.UP, true, false, 0); // +60 → 24:59 > max → clamped
        scene.inputBatchEnded();
        assertEquals("23:59", spinner.text());
    }

    /**
     * The measured width must fit the widest value the range can produce, not the
     * value currently held; otherwise a long value slides under the stepper column
     * and the button divider bisects its last digit. RULER is 10pt per code point.
     */
    @Test
    void measureFitsTheWidestValueTheRangeCanProduce() {
        build(new Spinner(0, 1e9, 0.001)); // widest rendering: "1000000000.000", 14 chars
        // pads + value + button column
        float expected = 2 * MEDIUM.spacingMedium() + 140 + MEDIUM.spinnerButtonW();
        assertEquals(expected,
                spinner.measure(Constraints.loose(1000, 200)).width(), 1e-3,
                "width covers the widest value, both pads and the stepper column");
        assertEquals("1000000000.000", new Spinner(0, 1e9, 0.001).setValue(1e9).text(),
                "the measured string is the one paintValue draws");
    }

    @Test
    void measureUsesTheNegativeBoundWhenItIsTheWiderOne() {
        // "-1000000.00" (11 chars) beats "0.00" (4): the sign only appears at min.
        build(new Spinner(-1e6, 0, 0.01));
        assertEquals(2 * MEDIUM.spacingMedium() + 110 + MEDIUM.spinnerButtonW(),
                spinner.measure(Constraints.loose(1000, 200)).width(), 1e-3);
    }

    @Test
    void measureKeepsThePreferredWidthAsAFloorForNarrowRanges() {
        build(new Spinner(0, 99, 1)); // "99" needs 20pt; nowhere near the 140pt floor
        assertEquals(BOX_W, spinner.measure(Constraints.loose(1000, 200)).width(), 1e-3,
                "a narrow range keeps today's preferred width exactly");
        build(Spinner.time()); // "23:59" is always 5 glyphs, also under the floor
        assertEquals(BOX_W, spinner.measure(Constraints.loose(1000, 200)).width(), 1e-3);
    }

    /**
     * The step ramp. The value is one line and the box floor always wins, so a Spinner
     * measures the shared {@code controlHeight} at every step and lines up with Button /
     * TextField / ComboBox in a form row, including at MEDIUM, where the old +2 fudge
     * (34) is gone. Uses {@link #SCALED_RULER}: {@link #RULER}'s font-blind lineHeight 12
     * would let a broken font ramp pass unnoticed.
     */
    @Test
    void heightAndPreferredWidthFollowTheStepRamp() {
        Spinner s = new Spinner(0, 99, 1); // narrow range: the width floor binds at every step
        Scene sc = new Scene(s);
        sc.setTextRuler(SCALED_RULER);
        for (ControlSize step : ControlSize.values()) {
            ControlSize.setProcessDefault(step);
            SizeTokens t = SizeTokens.of(step);
            Size size = s.measure(Constraints.loose(1000, 200));
            assertEquals(t.controlHeight(), size.height(), 1e-3, step + " height");
            assertEquals(t.spinnerWidth(), size.width(), 1e-3, step + " preferred width");
        }
    }

    @Test
    void measureStillObeysAConstrainedParent() {
        build(new Spinner(0, 1e9, 0.001));
        assertEquals(120, spinner.measure(Constraints.loose(120, 200)).width(), 1e-3,
                "a bounded parent still wins: the spinner asks, it does not demand");
    }

    // ------------------------------------------------ the pixel-locked stroke rule

    /**
     * The toolkit's whole pen vocabulary: six distinct values, because the aliases collapse
     * onto them: HAIRLINE / CARET / IME_UNDERLINE are 1 like BORDER, CHECK_MARK /
     * IME_UNDERLINE_ACTIVE are 2 like FOCUS_RING, INDICATOR_BORDER is 1.5 like
     * FOCUS_RING_THIN. The multiset assertions pin <em>which</em> of these a Spinner paints;
     * this set pins that a recorded width is a declared {@link Strokes} weight at all, which
     * is the assertion that a mid-fade border (1.37, say) fails outright.
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
        // The pixel-lock rule, checked mechanically, on one of the four components that carry
        // the trap: the outer border's width is the EXPRESSION
        // BORDER + (FOCUS_RING - BORDER) * focus, so it is 1 only while the fade rests at 0 and
        // 2 only once it rests at 1. Sampled anywhere in between it is a frame-dependent
        // fraction and this test would be flaky by construction: hence settle(), and hence
        // both endpoints being asserted rather than just the resting one.
        //
        // Five strokes either way and none of them tabled: two 1pt dividers (the stepper
        // column's vertical rule and the up/down split), two ARROW_PEN triangles whose EXTENT
        // rides arrowHalf 4 -> 6 while the pen does not, and the border.
        List<Float> restingMedium = null;
        List<Float> focusedMedium = null;
        for (ControlSize step : ControlSize.values()) {
            Spinner s = new Spinner(0, 99, 1); // narrow range: the width floor binds everywhere
            s.setControlSize(step);
            Scene host = new Scene(s, strokeClock::get);
            host.setTextRuler(SCALED_RULER);
            Size box = s.measure(Constraints.loose(1000, 1000));
            float w = box.width();
            float h = box.height();

            List<Float> resting = strokesOf(host, w, h);
            assertEquals(
                    List.of(Strokes.BORDER, Strokes.BORDER, Strokes.BORDER,
                            Strokes.ARROW_PEN, Strokes.ARROW_PEN),
                    resting,
                    step + " at rest: two dividers, the border, and two arrow pens");
            assertEveryWidthIsALockedPen(resting, step);

            host.requestFocus(s);
            settle(host, w, h);
            List<Float> focused = strokesOf(host, w, h);
            assertEquals(
                    List.of(Strokes.BORDER, Strokes.BORDER,
                            Strokes.ARROW_PEN, Strokes.ARROW_PEN, Strokes.FOCUS_RING),
                    focused,
                    step + " focused: the border reaches FOCUS_RING exactly, the rest unmoved");
            assertEveryWidthIsALockedPen(focused, step);

            if (restingMedium == null) {
                restingMedium = resting;
                focusedMedium = focused;
            }
            assertEquals(restingMedium, resting, step + " matches the first step's resting multiset");
            assertEquals(focusedMedium, focused, step + " matches the first step's focused multiset");
        }
    }

    // --- typing, copy and paste ---------------------------------------------

    @Test
    void typingReplacesTheValueAndEnterCommits() {
        build(new Spinner(0, 100, 1));
        spinner.setValue(3);

        typeInto("42");
        assertTrue(spinner.isEditing(), "a digit should have started an edit");
        assertEquals(3.0, spinner.value(), "nothing is committed while it is being typed");

        press(Keys.ENTER, 0);
        assertEquals(42.0, spinner.value());
        assertEquals(42.0, changed.get(), "the committed value is reported once");
        assertTrue(!spinner.isEditing(), "Enter should have ended the edit");
    }

    @Test
    void escapePutsBackWhatWasThere() {
        build(new Spinner(0, 100, 1));
        spinner.setValue(3);

        typeInto("42");
        press(Keys.ESCAPE, 0);

        assertEquals(3.0, spinner.value(), "Escape kept the typed number");
        assertNull(changed.get(), "and reported a change that was cancelled");
    }

    @Test
    void movingTheFocusAwayCommits() {
        build(new Spinner(0, 100, 1));
        typeInto("7");
        // Focus leaving the spinner, whatever took it: clicking another field is
        // the same event as tabbing away.
        scene.requestFocus(null);
        scene.inputBatchEnded();
        assertEquals(7.0, spinner.value(), "a typed number was thrown away on blur");
    }

    @Test
    void textThatIsNotANumberCommitsNothing() {
        build(new Spinner(0, 100, 1));
        spinner.setValue(3);

        typeInto("1..2");
        press(Keys.ENTER, 0);

        assertEquals(3.0, spinner.value(), "half a number is not a number");
        assertNull(changed.get());
    }

    @Test
    void lettersNeverStartAnEdit() {
        build(new Spinner(0, 100, 1));
        spinner.setValue(3);
        typeInto("abc");
        assertTrue(!spinner.isEditing(), "a letter is not part of a number");
        assertEquals(3.0, spinner.value());
    }

    @Test
    void aTypedNumberIsClampedLikeAnyOther() {
        build(new Spinner(0, 10, 1));
        typeInto("999");
        press(Keys.ENTER, 0);
        assertEquals(10.0, spinner.value(), "typing past the maximum must clamp, not refuse");
    }

    @Test
    void aCommaIsADecimalPoint() {
        // The value renders with a point and half the world types a comma; the
        // field chose the format, so it is the one that has to bridge them.
        build(new Spinner(0, 10, 0.1));
        typeInto("1,5");
        press(Keys.ENTER, 0);
        assertEquals(1.5, spinner.value(), 1e-9);
    }

    @Test
    void steppingWhileTypingStepsFromWhatIsTyped() {
        build(new Spinner(0, 100, 1));
        spinner.setValue(3);

        typeInto("50");
        press(Keys.UP, 0);

        assertEquals(51.0, spinner.value(), "the step ignored the number on screen");
        assertTrue(spinner.isEditing(), "stepping mid-edit should not end the edit");
        press(Keys.ENTER, 0);
        assertEquals(51.0, spinner.value());
    }

    @Test
    void reachingForTheArrowsCommitsFirst() {
        build(new Spinner(0, 100, 1));
        typeInto("50");
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, BUTTON_X, UP_Y);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, BUTTON_X, UP_Y);
        scene.inputBatchEnded();
        assertEquals(51.0, spinner.value(), "the button stepped the old value");
    }

    @Test
    void copyingAnUntouchedFieldTakesTheWholeValue() {
        build(new Spinner(0, 100, 1));
        spinner.setValue(37);
        scene.requestFocus(spinner);

        press(Keys.C, Keys.MOD_CONTROL);

        assertEquals("37", clipboard.value, "Ctrl+C on a field nobody touched means the value");
        assertTrue(!spinner.isEditing(), "and it is not an edit");
    }

    @Test
    void cuttingNeedsSomethingSelected() {
        build(new Spinner(0, 100, 1));
        spinner.setValue(37);
        scene.requestFocus(spinner);

        press(Keys.X, Keys.MOD_CONTROL);

        assertEquals("", clipboard.value, "there is no such thing as a spinner with no value");
        assertEquals(37.0, spinner.value());
    }

    @Test
    void pastingReplacesTheValue() {
        build(new Spinner(0, 100, 1));
        spinner.setValue(3);
        clipboard.value = "12";
        scene.requestFocus(spinner);

        press(Keys.V, Keys.MOD_CONTROL);
        press(Keys.ENTER, 0);

        assertEquals(12.0, spinner.value());
    }

    @Test
    void pastingSomethingElseLeavesTheValueAlone() {
        build(new Spinner(0, 100, 1));
        spinner.setValue(3);
        clipboard.value = "12 px";
        scene.requestFocus(spinner);

        press(Keys.V, Keys.MOD_CONTROL);
        press(Keys.ENTER, 0);

        assertEquals(3.0, spinner.value(), "a paste is shown as it arrived and judged on commit");
        assertNull(changed.get());
    }

    @Test
    void aProgrammaticSetValueCancelsTheEdit() {
        // The value moved from somewhere else (another control on the same model),
        // so committing the half-typed text later would silently undo whatever
        // moved it. This is what protects a bound field like ColorPicker's.
        build(new Spinner(0, 100, 1));
        typeInto("99");

        spinner.setValue(4);
        press(Keys.ENTER, 0);

        assertEquals(4.0, spinner.value(), "the abandoned edit came back and overwrote the model");
        assertTrue(!spinner.isEditing());
    }

    @Test
    void anUneditableSpinnerIsStillAStepper() {
        build(new Spinner(0, 100, 1).setEditable(false));
        spinner.setValue(3);

        typeInto("42");
        assertTrue(!spinner.isEditing(), "typing was turned off");
        assertEquals(3.0, spinner.value());

        press(Keys.LEFT, 0);
        assertEquals(2.0, spinner.value(), "Left/Right must go on stepping where typing is off");
    }

    @Test
    void aTimeIsTypedTheWayItIsShown() {
        build(Spinner.time());
        typeInto("7:30");
        press(Keys.ENTER, 0);
        assertEquals(7 * 60 + 30.0, spinner.value());
        assertEquals("07:30", spinner.text());
    }

    @Test
    void anImpossibleTimeCommitsNothing() {
        build(Spinner.time());
        spinner.setValue(8 * 60);
        typeInto("7:75");
        press(Keys.ENTER, 0);
        assertEquals(8 * 60.0, spinner.value(), "7:75 means nothing, and 8:15 is an invention");
    }

    /** Focuses the spinner and types, the way a keyboard delivers it. */
    private void typeInto(String text) {
        scene.requestFocus(spinner);
        text.codePoints().forEach(scene::charTyped);
        scene.inputBatchEnded();
    }

    private void press(int key, int modifiers) {
        scene.keyEvent(key, true, false, modifiers);
        scene.inputBatchEnded();
    }

    @Test
    void theTypedTextIsPaintedWithItsSelectionAndCaret() {
        build(new Spinner(0, 100, 1));
        spinner.setValue(37);
        typeInto("5"); // replaces the selected value, leaving a bare caret

        EditCanvas afterTyping = new EditCanvas(BOX_W, BOX_H);
        scene.renderFrame(afterTyping);
        assertEquals("5", afterTyping.drawnText, "the box shows what is being typed, not the value");
        assertTrue(afterTyping.caretWidth == Strokes.CARET,
                "a caret marks where the next keystroke lands");
        assertTrue(afterTyping.selectionWidth <= 0, "and there is nothing selected to highlight");

        press(Keys.A, Keys.MOD_CONTROL);
        EditCanvas afterSelectAll = new EditCanvas(BOX_W, BOX_H);
        scene.renderFrame(afterSelectAll);
        assertTrue(afterSelectAll.selectionWidth > 0, "select-all painted no selection");
        assertTrue(Float.isNaN(afterSelectAll.caretWidth),
                "a caret over a selection only argues with the highlight");
    }

    /** Records what the value area painted while an edit was in progress. */
    private static final class EditCanvas extends FakeCanvas {
        String drawnText;
        float selectionWidth = -1;
        float caretWidth = Float.NaN;

        EditCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            drawnText = text;
        }

        @Override
        public void fillRect(float x, float y, float width, float height, Paint paint) {
            // The selection is the only fillRect in the value area; the box fill is
            // a round rect and the button hover needs a hovered button.
            selectionWidth = width;
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
            // Filtered by position, not by weight: the divider down the stepper
            // column is also a vertical 1 pt line, and CARET and BORDER are both 1.
            if (x1 == x2 && strokeWidth == Strokes.CARET && x1 < VALUE_RIGHT - 1) {
                caretWidth = strokeWidth;
            }
        }
    }
}
