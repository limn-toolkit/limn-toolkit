package limn.components;

import limn.graphics.Paint;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Small checks: Separator sizing and TextField validation state. */
class MiscComponentsTest extends ComponentTestBase {

    /** Records the one line a Separator paints, so the crisp-line snap can be asserted. */
    private static final class LineCanvas extends FakeCanvas {
        float x1 = Float.NaN;
        float y1 = Float.NaN;
        float x2 = Float.NaN;
        float y2 = Float.NaN;
        float strokeWidth = Float.NaN;

        LineCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawLine(float ax, float ay, float bx, float by, float sw, Paint paint) {
            x1 = ax;
            y1 = ay;
            x2 = bx;
            y2 = by;
            strokeWidth = sw;
        }
    }

    private LineCanvas paint(Separator separator, float width, float height) {
        Scene scene = new Scene(separator);
        scene.setTextRuler(RULER); // Separator draws no text; set for parity with the other tests
        scene.layoutPass(width, height);
        LineCanvas canvas = new LineCanvas(width, height);
        scene.renderFrame(canvas);
        return canvas;
    }

    @Test
    void separatorIsThinOnItsAxisAndFillsTheOther() {
        // The thin axis is separatorBox, inherited from the step; the long axis is whatever the
        // parent offers. Derived from the table rather than re-typed, so a retuned row cannot
        // leave a stale literal behind. Font-blind, so RULER's degenerate metrics are irrelevant.
        for (ControlSize step : ControlSize.values()) {
            float box = SizeTokens.of(step).separatorBox();

            Separator horizontal = Separator.horizontal();
            horizontal.setControlSize(step);
            Size h = horizontal.measure(Constraints.loose(200, 100));
            assertEquals(200f, h.width(), 1e-3, "horizontal fills the width at " + step);
            assertEquals(box, h.height(), 1e-3, "thin height at " + step);

            Separator vertical = Separator.vertical();
            vertical.setControlSize(step);
            Size v = vertical.measure(Constraints.loose(200, 100));
            assertEquals(box, v.width(), 1e-3, "thin width at " + step);
            assertEquals(100f, v.height(), 1e-3, "vertical fills the height at " + step);
        }
    }

    @Test
    void separatorBoxIsOddAtEveryStepSoAOnePointLineCanCentreExactly() {
        // Parity, not taste: an odd (1pt) line only sits exactly centred inside an odd box.
        // Break this and the D15 snap below silently goes back to being half a point off.
        for (ControlSize step : ControlSize.values()) {
            float box = SizeTokens.of(step).separatorBox();
            assertEquals(box, Math.rint(box), 1e-6, "separatorBox is a whole point at " + step);
            assertTrue(((int) box & 1) == 1, "separatorBox is odd at " + step + " (" + box + ")");
        }
    }

    @Test
    void hairlineIsExactlyCentredAndPixelCrispAtEveryStep() {
        // The snap is floor + 0.5, not round + 0.5: round puts the ink a full point BELOW
        // centre on every odd box (at MEDIUM y was 5.5 in a 9pt box: 5 above, 3 below). Floor
        // centres it and still lands the 1pt stroke on one whole device pixel.
        for (ControlSize step : ControlSize.values()) {
            float box = SizeTokens.of(step).separatorBox();

            Separator horizontal = Separator.horizontal();
            horizontal.setControlSize(step);
            LineCanvas h = paint(horizontal, 200, box);
            assertEquals(box / 2, h.y1, 1e-6, "hairline centred in the box at " + step);
            assertEquals(h.y1, h.y2, 1e-6, "horizontal line is level at " + step);
            assertEquals(Strokes.HAIRLINE, h.strokeWidth, 1e-6,
                    "the line is pixel-locked at " + step);
            // Ink covers [y - 0.5, y + 0.5]; both edges must be whole points, or the 1pt stroke
            // straddles two device rows and greys out.
            assertEquals(h.y1 - Strokes.HALF_PIXEL_INSET, Math.rint(h.y1 - Strokes.HALF_PIXEL_INSET),
                    1e-6, "ink edge is a whole point at " + step);
            assertEquals(h.y1 - Strokes.HALF_PIXEL_INSET, box - (h.y1 + Strokes.HALF_PIXEL_INSET),
                    1e-6, "equal air above and below at " + step);
            assertEquals(0f, h.x1, 1e-6, "no inset by default");
            assertEquals(200f, h.x2, 1e-6, "the line spans the long axis");

            Separator vertical = Separator.vertical();
            vertical.setControlSize(step);
            LineCanvas v = paint(vertical, box, 100);
            assertEquals(box / 2, v.x1, 1e-6, "vertical hairline centred in the box at " + step);
            assertEquals(v.x1, v.x2, 1e-6, "vertical line is plumb at " + step);
            assertEquals(0f, v.y1, 1e-6, "no inset by default");
            assertEquals(100f, v.y2, 1e-6, "the line spans the long axis");
        }
    }

    @Test
    void insetTrimsBothEndsOfTheLineAndDoesNotMoveIt() {
        float box = SizeTokens.MEDIUM.separatorBox();
        Separator separator = Separator.horizontal().setInset(4); // ToolBar feeds toolBarSepInset
        LineCanvas canvas = paint(separator, 200, box);
        assertEquals(4f, canvas.x1, 1e-6);
        assertEquals(196f, canvas.x2, 1e-6);
        assertEquals(box / 2, canvas.y1, 1e-6, "the inset is a long-axis trim only");
    }

    @Test
    void unboundedLongAxisFallsBackInsteadOfCollapsing() {
        // FALLBACK_LENGTH is not a size token: 24 at every step, which is why Separator's long
        // axis is exempt from the ramp's strict-monotonicity rule.
        Constraints unboundedWidth =
                new Constraints(0, Constraints.UNBOUNDED_LIMIT, 0, 100);
        for (ControlSize step : ControlSize.values()) {
            Separator horizontal = Separator.horizontal();
            horizontal.setControlSize(step);
            assertEquals(24f, horizontal.measure(unboundedWidth).width(), 1e-3,
                    "unbounded width falls back to 24 at " + step);
        }
    }

    @Test
    void textFieldValidationState() {
        TextField field = new TextField();
        assertEquals(TextField.Validation.NONE, field.validation());
        field.setError(true);
        assertEquals(TextField.Validation.ERROR, field.validation());
        field.setValidation(TextField.Validation.SUCCESS);
        assertEquals(TextField.Validation.SUCCESS, field.validation());
        field.setError(false);
        assertEquals(TextField.Validation.NONE, field.validation());
    }
}
