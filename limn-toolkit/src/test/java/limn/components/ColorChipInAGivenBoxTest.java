package limn.components;

import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Paint;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A colour button with no caption asks for a square, and its chip sits in the middle of it. A parent
 * may give it another box: a row that stretches its children makes it taller, {@code setMaxWidth}
 * narrower, {@code setMinWidth} wider. The chip must stay inside whatever box it is given: centred
 * in a narrower one, as an icon-only button's icon is; in a taller one, as far from the leading edge
 * as in the square, centred top to bottom; in a wider one, on the leading edge as far in as in the
 * square. Before, the inset was taken from the box's height alone, and a button stretched to 200
 * tall drew its chip wholly outside its own box reading right to left, and far past its middle
 * reading left to right; narrowed to 24 the chip overran the border on the trailing side.
 */
class ColorChipInAGivenBoxTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;
    private static final Color VALUE = Color.rgb(0xF59E0B);

    /** Paints nothing and asks for {@code height}, so a stretching row grows its neighbours to it. */
    private static final class Tall extends Widget<Tall> {
        private final float height;

        Tall(float height) {
            this.height = height;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(10, height);
        }

        @Override
        protected void onPaint(Canvas canvas) {
        }
    }

    /** The chip's square, in the button's own coordinates, and the box it was painted in. */
    private record Placed(float x, float y, float side, float width, float height) {
    }

    private static Placed chipIn(LayoutDirection direction, boolean stretch, float tall, float minWidth,
            float maxWidth) {
        ColorPickerButton button = new ColorPickerButton(VALUE).setText("");
        if (minWidth > 0) {
            button.setMinWidth(minWidth);
        }
        if (maxWidth > 0) {
            button.setMaxWidth(maxWidth);
        }
        Row row = new Row();
        row.setLayoutDirection(direction);
        if (stretch) {
            row.crossAlignment(Flex.CrossAlignment.STRETCH);
        }
        row.add(button);
        row.add(new Tall(tall));
        Scene scene = new Scene(row);
        scene.setTextRuler(SCALED_RULER);
        scene.layoutPass(400, 300);
        List<float[]> chips = new ArrayList<>();
        float side = SizeTokens.MEDIUM.iconBox();
        scene.renderFrame(new FakeCanvas(400, 300) {
            @Override
            public void fillRect(float x, float y, float w, float h, Paint paint) {
                if (VALUE.equals(paint) && Math.abs(w - side) < EPS && Math.abs(h - side) < EPS) {
                    chips.add(new float[] {x, y});
                }
            }
        });
        assertEquals(1, chips.size(), "one chip painted");
        return new Placed(chips.get(0)[0], chips.get(0)[1], side, button.width(), button.height());
    }

    private static void assertInside(String what, Placed chip) {
        assertTrue(chip.x() >= -EPS && chip.x() + chip.side() <= chip.width() + EPS,
                what + ": chip " + chip.x() + ".." + (chip.x() + chip.side()) + " inside 0.." + chip.width());
        assertTrue(chip.y() >= -EPS && chip.y() + chip.side() <= chip.height() + EPS,
                what + ": chip " + chip.y() + ".." + (chip.y() + chip.side()) + " inside 0.." + chip.height());
    }

    @ParameterizedTest
    @EnumSource(LayoutDirection.class)
    void inItsOwnSquareTheChipIsInTheMiddle(LayoutDirection direction) {
        Placed chip = chipIn(direction, false, 10, 0, 0);
        assertEquals(chip.height(), chip.width(), EPS, "sanity: its own square");
        assertEquals((chip.width() - chip.side()) / 2, chip.x(), EPS, direction + ": centred across");
        assertEquals((chip.height() - chip.side()) / 2, chip.y(), EPS, direction + ": centred down");
    }

    @ParameterizedTest
    @EnumSource(LayoutDirection.class)
    void stretchedTallerTheChipStaysWhereTheSquarePutItAcrossAndCentresDown(LayoutDirection direction) {
        Placed chip = chipIn(direction, true, 200, 0, 0);
        assertTrue(chip.height() >= 200, "sanity: the row stretched the button, " + chip.height());
        assertInside(direction + ", stretched", chip);
        assertEquals((chip.width() - chip.side()) / 2, chip.x(), EPS, direction + ": as in the square");
        assertEquals((chip.height() - chip.side()) / 2, chip.y(), EPS, direction + ": centred down");
    }

    @ParameterizedTest
    @EnumSource(LayoutDirection.class)
    void narrowedTheChipIsCentredInTheNarrowerBox(LayoutDirection direction) {
        Placed chip = chipIn(direction, false, 10, 0, 24);
        assertEquals(24, chip.width(), EPS, "sanity: setMaxWidth narrowed it");
        assertTrue(chip.height() > chip.width(), "sanity: narrower than tall");
        assertInside(direction + ", narrowed to 24", chip);
        assertEquals((24 - chip.side()) / 2, chip.x(), EPS, direction + ": centred across, as a button's icon");
    }

    @ParameterizedTest
    @EnumSource(LayoutDirection.class)
    void narrowedAndStretchedTheChipIsCentredBothWays(LayoutDirection direction) {
        Placed chip = chipIn(direction, true, 200, 0, 24);
        assertTrue(chip.height() >= 200, "sanity: the row stretched the button, " + chip.height());
        assertInside(direction + ", narrowed and stretched", chip);
        assertEquals((24 - chip.side()) / 2, chip.x(), EPS, direction + ": centred across");
        assertEquals((chip.height() - chip.side()) / 2, chip.y(), EPS, direction + ": centred down");
    }

    @ParameterizedTest
    @EnumSource(LayoutDirection.class)
    void widenedTheChipKeepsTheLeadingEdgeAsFarInAsInTheSquare(LayoutDirection direction) {
        Placed chip = chipIn(direction, false, 10, 80, 0);
        assertEquals(80, chip.width(), EPS, "sanity: setMinWidth widened it");
        float inset = (chip.height() - chip.side()) / 2;
        float expected = direction == LayoutDirection.RTL ? 80 - inset - chip.side() : inset;
        assertEquals(expected, chip.x(), EPS, direction + ": on the leading edge");
    }
}
