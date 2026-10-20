package limn.components;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.layout.Row;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A button that holds an icon and no caption is square: as wide as it is tall, which is the
 * control height of its step, 24 / 28 / 32 / 40 / 50. Before, it was the icon with the caption's
 * padding on both sides, 58 by 32 at MEDIUM, the icon standing 20 points from each edge.
 *
 * <p>The width is a default and not a rule: {@link Button#setMinWidth} and
 * {@link Button#setMaxWidth}, which every widget has, widen or narrow one button, and the icon
 * stays in the middle of whatever box it is given. A caption, alone or beside an icon, keeps the
 * width it had.
 */
class IconOnlyButtonTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final float[] CONTROL_HEIGHT = { 24, 28, 32, 40, 50 };

    private static Button iconOnly(ControlSize step, TextRuler ruler) {
        Button button = new Button("").setIcon(new RecordingIcon());
        Scene scene = new Scene(button);
        scene.setTextRuler(ruler);
        return button.setControlSize(step);
    }

    private static Size measured(limn.scene.Widget<?> widget) {
        return widget.measure(Constraints.loose(500, 200));
    }

    @Test
    void anIconOnlyButtonIsAsWideAsItsControlHeightAtEveryStep() {
        for (ControlSize step : ControlSize.values()) {
            Size size = measured(iconOnly(step, SCALED_RULER));
            float side = CONTROL_HEIGHT[step.ordinal()];
            assertEquals(side, size.width(), EPS, "width at " + step);
            assertEquals(side, size.height(), EPS, "height at " + step);
        }
    }

    @Test
    void mediumIsThirtyTwoSquareWhereItUsedToBeFiftyEightWide() {
        Size size = measured(iconOnly(ControlSize.MEDIUM, SCALED_RULER));
        assertEquals(32, size.width(), EPS);
        assertEquals(32, size.height(), EPS);
    }

    /**
     * The square is the button's own height, not a number beside it: under a face whose line is
     * taller than the step's floor the button grows, and its width grows with it.
     */
    @Test
    void theSquareFollowsAHeightATallFaceGrows() {
        TextRuler tall = (text, font) -> new TextMetrics(10f * (int) text.codePoints().count(), 30, 10, 40);
        Size size = measured(iconOnly(ControlSize.MEDIUM, tall));
        assertTrue(size.height() > 32, "the face must outgrow the floor for this case to mean anything");
        assertEquals(size.height(), size.width(), EPS);
    }

    @Test
    void noStepGoesBelowTheHitTargetFloor() {
        for (ControlSize step : ControlSize.values()) {
            Size size = measured(iconOnly(step, SCALED_RULER));
            assertTrue(size.width() >= Strokes.MIN_HIT_TARGET, "width at " + step);
            assertTrue(size.height() >= Strokes.MIN_HIT_TARGET, "height at " + step);
        }
    }

    @Test
    void aCaptionAloneKeepsItsPaddingOnBothSides() {
        SizeTokens t = SizeTokens.MEDIUM;
        Button button = new Button("OK");
        new Scene(button).setTextRuler(RULER);
        assertEquals(20 + 2 * t.padH(), measured(button).width(), EPS);
    }

    @Test
    void anIconBesideACaptionKeepsTheWidthItHad() {
        SizeTokens t = SizeTokens.MEDIUM;
        Button button = new Button("OK").setIcon(new RecordingIcon());
        new Scene(button).setTextRuler(RULER);
        assertEquals(t.fieldPadH() + t.iconBox() + t.gapIcon() + 20 + t.padH(),
                measured(button).width(), EPS);
    }

    /** Neither an icon nor a caption is not an icon-only button, and keeps the box it had. */
    @Test
    void aButtonWithNothingInItIsNotMadeSquare() {
        Button button = new Button("");
        new Scene(button).setTextRuler(SCALED_RULER);
        assertEquals(2 * SizeTokens.MEDIUM.padH(), measured(button).width(), EPS);
    }

    @Test
    void aMinimumWidthWidensOneButtonAndTheIconStaysInTheMiddle() {
        Button button = iconOnly(ControlSize.MEDIUM, SCALED_RULER);
        button.setMinWidth(58);
        assertEquals(58, measured(button).width(), EPS);

        RecordingIcon icon = new RecordingIcon();
        button.setIcon(icon);
        Row row = new Row();
        row.add(button);
        Scene scene = new Scene(row);
        scene.setTextRuler(SCALED_RULER);
        scene.layoutPass(300, 100);
        scene.renderFrame(new FakeCanvas(300, 100));
        assertEquals(58, button.width(), EPS, "a row lets the button choose its width");
        assertEquals((58 - SizeTokens.MEDIUM.iconBox()) / 2, icon.x, EPS);
    }

    @Test
    void aMaximumWidthNarrowsOneButton() {
        Button button = iconOnly(ControlSize.MEDIUM, SCALED_RULER);
        button.setMaxWidth(28);
        assertEquals(28, measured(button).width(), EPS);
    }

    /** The square is a size, so it is the same reading either way, and so is the icon's place in it. */
    @Test
    void theSquareAndTheIconInItAreTheSameRightToLeft() {
        float[] x = new float[2];
        for (LayoutDirection direction : new LayoutDirection[] { LayoutDirection.LTR, LayoutDirection.RTL }) {
            RecordingIcon icon = new RecordingIcon();
            Button button = new Button("").setIcon(icon);
            Row row = new Row();
            row.setLayoutDirection(direction);
            row.add(button);
            Scene scene = new Scene(row);
            scene.setTextRuler(SCALED_RULER);
            scene.layoutPass(300, 100);
            scene.renderFrame(new FakeCanvas(300, 100));
            assertEquals(32, button.width(), EPS, "width " + direction);
            x[direction.ordinal()] = icon.x;
        }
        assertEquals((32 - SizeTokens.MEDIUM.iconBox()) / 2, x[0], EPS);
        assertEquals(x[0], x[1], EPS);
    }

    // ------------------------------------------------- a colour button with no caption

    @Test
    void aColourButtonWithNoCaptionIsSquareAtEveryStep() {
        for (ControlSize step : ControlSize.values()) {
            ColorPickerButton button = new ColorPickerButton(Color.rgb(0xF59E0B)).setText("");
            new Scene(button).setTextRuler(SCALED_RULER);
            button.setControlSize(step);
            Size size = measured(button);
            float side = CONTROL_HEIGHT[step.ordinal()];
            assertEquals(side, size.width(), EPS, "width at " + step);
            assertEquals(side, size.height(), EPS, "height at " + step);
        }
    }

    @Test
    void aColourButtonWithItsHexKeepsTheWidthItHad() {
        SizeTokens t = SizeTokens.MEDIUM;
        ColorPickerButton button = new ColorPickerButton(Color.rgb(0xF59E0B));
        new Scene(button).setTextRuler(RULER);
        assertEquals(t.fieldPadH() + t.iconBox() + t.gapIcon() + 70 + t.padH(),
                measured(button).width(), EPS);
    }

    private static final class RecordingIcon implements Icon {

        private float x = Float.NaN;

        @Override
        public Image image(int pixelSize, boolean dark) {
            throw new UnsupportedOperationException("measure-only");
        }

        @Override
        public void paint(Canvas canvas, float x, float y, float size, Color tint, boolean dark,
                boolean mirrored) {
            this.x = x;
        }
    }
}
