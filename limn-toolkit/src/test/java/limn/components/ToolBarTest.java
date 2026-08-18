package limn.components;

import limn.graphics.Paint;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ToolBar} geometry across the size axis: the bar's height is
 * {@code tallestChild + 2*toolBarPad}, items flow left to right at {@code toolBarGap}, and every
 * item is vertically centered in the inner band. Every expected number is <b>derived from
 * {@link SizeTokens}</b> rather than baked in: a literal here would only prove that the test
 * and the component were edited on the same day, and the whole point of the table is that one
 * place owns the numbers.
 */
class ToolBarTest extends ComponentTestBase {

    private static final SizeTokens MEDIUM = SizeTokens.MEDIUM;
    private static final float PAD = MEDIUM.toolBarPad();   // 8
    private static final float GAP = MEDIUM.toolBarGap();   // 8

    /** A fixed-size stand-in, so the assertions are about the bar and not about a Button. */
    private static final class Block extends Widget {
        private final float w;
        private final float h;

        Block(float w, float h) {
            this.w = w;
            this.h = h;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(w, h);
        }
    }

    /** Records every line the frame draws: a Separator's inset is only visible in paint. */
    private static final class LineCanvas extends FakeCanvas {
        final List<float[]> lines = new ArrayList<>();

        LineCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawLine(float x1, float y1, float x2, float y2, float strokeWidth, Paint paint) {
            lines.add(new float[] { x1, y1, x2, y2 });
        }
    }

    private ToolBar bar;
    private Scene scene;

    private void build(Widget... items) {
        bar = new ToolBar();
        for (Widget item : items) {
            bar.addItem(item);
        }
        scene = new Scene(bar);
        scene.setTextRuler(RULER);
    }

    @Test
    void heightIsTheTallestChildPlusPaddingOnBothEdges() {
        build(new Block(40, 20), new Block(30, 28), new Block(20, 16));
        Size size = bar.measure(Constraints.loose(500, 200));
        assertEquals(28 + 2 * PAD, size.height(), 1e-3, "tallest child (28) + 2 * toolBarPad");
    }

    @Test
    void widthIsThePaddedSumOfItemsAndGaps() {
        build(new Block(40, 20), new Block(30, 20), new Block(20, 20));
        Size size = bar.measure(Constraints.loose(500, 200));
        // Three items, two gaps, padding at both ends.
        assertEquals(PAD + 40 + GAP + 30 + GAP + 20 + PAD, size.width(), 1e-3);
    }

    @Test
    void itemsFlowLeftToRightAndAreCenteredInTheInnerBand() {
        Block tall = new Block(30, 28);
        Block shortOne = new Block(40, 16);
        build(tall, shortOne);
        scene.layoutPass(400, 28 + 2 * PAD);

        assertEquals(PAD, tall.x(), 1e-3, "the first item starts at toolBarPad");
        assertEquals(PAD + 30 + GAP, shortOne.x(), 1e-3, "the second clears the first plus the gap");
        // Inner band is height - 2*pad; each item is centered in it.
        float innerH = bar.height() - 2 * PAD;
        assertEquals(PAD + (innerH - 28) / 2, tall.y(), 1e-3);
        assertEquals(PAD + (innerH - 16) / 2, shortOne.y(), 1e-3);
    }

    @Test
    void gapIsHonoredAndClampedAtZero() {
        Block a = new Block(20, 20);
        Block b = new Block(20, 20);
        build(a, b);
        bar.gap(20);
        scene.layoutPass(400, 20 + 2 * PAD);
        assertEquals(PAD + 20 + 20, b.x(), 1e-3);

        bar.gap(-5);
        scene.layoutPass(400, 20 + 2 * PAD);
        assertEquals(PAD + 20, b.x(), 1e-3, "a negative gap clamps to 0");
    }

    @Test
    void anEmptyBarIsJustItsPadding() {
        build();
        Size size = bar.measure(Constraints.loose(500, 200));
        assertEquals(2 * PAD, size.width(), 1e-3);
        assertEquals(2 * PAD, size.height(), 1e-3);
    }

    // ---------------------------------------------------------------- the size axis

    @Test
    void padAndGapFollowTheStepWhenTheAppNeverSetThem() {
        SizeTokens small = SizeTokens.of(ControlSize.SMALL);
        Block a = new Block(20, 20);
        Block b = new Block(30, 20);
        build(a, b);
        bar.setControlSize(ControlSize.SMALL);

        Size size = bar.measure(Constraints.loose(500, 200));
        assertEquals(small.toolBarPad() + 20 + small.toolBarGap() + 30 + small.toolBarPad(),
                size.width(), 1e-3);
        assertEquals(20 + 2 * small.toolBarPad(), size.height(), 1e-3);

        scene.layoutPass(400, size.height());
        assertEquals(small.toolBarPad(), a.x(), 1e-3);
        assertEquals(small.toolBarPad() + 20 + small.toolBarGap(), b.x(), 1e-3);
    }

    @Test
    void theStepIsInheritedByItemsRatherThanCopiedOntoThem() {
        Button item = new Button("OK");
        build(item);
        bar.setControlSize(ControlSize.XSMALL);
        // Nothing is written onto the child: it declares nothing and resolves through the bar.
        assertEquals(ControlSize.XSMALL, item.controlSize());
        assertNull(item.declaredControlSize(), "propagation is inheritance, not assignment");
    }

    @Test
    void anExplicitGapLatchesAndSurvivesAStepChange() {
        SizeTokens xsmall = SizeTokens.of(ControlSize.XSMALL);
        Block a = new Block(20, 20);
        Block b = new Block(20, 20);
        build(a, b);
        bar.gap(16);
        bar.setControlSize(ControlSize.XSMALL);

        // The pad moved with the step; the gap the app chose did not.
        assertFalse(xsmall.toolBarGap() == 16, "the fixture only means something if 16 is not the token");
        scene.layoutPass(400, 20 + 2 * xsmall.toolBarPad());
        assertEquals(xsmall.toolBarPad(), a.x(), 1e-3);
        assertEquals(xsmall.toolBarPad() + 20 + 16, b.x(), 1e-3, "gap(16) is never stomped by a step");
    }

    @Test
    void aStretchyItemDoesNotDragTheBarToTheFullConstraintHeight() {
        // Separator.vertical() fills whatever height it is given. Measuring it against the
        // incoming bound made a 200pt-tall constraint produce a 216pt toolbar; the bar's
        // height must come from the tallest item's NATURAL height.
        build(new Block(30, 28));
        bar.addSeparator();
        Size size = bar.measure(Constraints.loose(500, 200));
        assertEquals(28 + 2 * PAD, size.height(), 1e-3, "the divider stretches in layout, not in measure");
    }

    @Test
    void aSeparatorTheBarBuiltIsInsetPerTheStep() {
        build(new Block(30, 28));
        bar.addSeparator();
        float height = 28 + 2 * PAD;
        scene.layoutPass(400, height);
        LineCanvas canvas = new LineCanvas(400, height);
        scene.renderFrame(canvas);
        assertEquals(1, canvas.lines.size(), "one divider, one line");
        // Separator paints drawLine(x, inset, x, height - inset).
        assertEquals(MEDIUM.toolBarSepInset(), canvas.lines.get(0)[1], 1e-3);

        bar.setControlSize(ControlSize.SMALL);
        SizeTokens small = SizeTokens.of(ControlSize.SMALL);
        scene.layoutPass(400, 28 + 2 * small.toolBarPad());
        canvas = new LineCanvas(400, 28 + 2 * small.toolBarPad());
        scene.renderFrame(canvas);
        assertEquals(1, canvas.lines.size());
        assertEquals(small.toolBarSepInset(), canvas.lines.get(0)[1], 1e-3,
                "the inset the bar owns tracks the bar's step");
    }

    @Test
    void aSeparatorTheAppAddedItselfKeepsItsOwnInset() {
        build(new Block(30, 28), Separator.vertical().setInset(12));
        float height = 28 + 2 * PAD;
        scene.layoutPass(400, height);
        LineCanvas canvas = new LineCanvas(400, height);
        scene.renderFrame(canvas);
        assertEquals(1, canvas.lines.size());
        assertEquals(12, canvas.lines.get(0)[1], 1e-3,
                "the bar only owns the separators addSeparator() built");
    }
}
