package limn.demo.site;

import java.util.ArrayList;
import java.util.List;
import limn.graphics.BlendMode;
import limn.graphics.Canvas;
import limn.graphics.FillRule;
import limn.graphics.Path2D;
import limn.graphics.Picture;
import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the drawing guide says of its examples: the star's middle is filled under one rule and a
 * hole under the other, a hit on the line is not a hit on the shape, the pin is one picture
 * stamped eight times, the eraser and the paint inside a shape draw inside a layer, and every
 * picture of the guide lays out as the row it is shown as.
 */
class DrawingExampleTest {

    @Test
    void theStarsMiddleIsFilledUnderNonZeroAndAHoleUnderEvenOdd() {
        Path2D nonZero = DrawingExample.star(42, 64, 40, FillRule.NON_ZERO);
        Path2D evenOdd = DrawingExample.star(42, 64, 40, FillRule.EVEN_ODD);
        assertTrue(nonZero.contains(42, 64));
        assertFalse(evenOdd.contains(42, 64), "the middle crosses the outline twice: a hole");
        assertTrue(nonZero.contains(42, 30) && evenOdd.contains(42, 30), "an arm is filled under both");
    }

    @Test
    void aPointBesideAnEdgeIsOnTheLineButNotOnTheShape() {
        Path2D star = DrawingExample.star(42, 64, 40, FillRule.NON_ZERO);
        // A tenth of the way down the top arm's right edge, a unit and a half outside it.
        float x = 42 + 0.1f * 23.51f + 1.43f;
        float y = 24 + 0.1f * 72.36f - 0.46f;
        assertFalse(DrawingExample.onShape(star, x, y));
        assertTrue(DrawingExample.onLine(star, x, y), "within the four-unit line's half width");
        assertFalse(DrawingExample.onLine(star, 120, 10), "and nowhere near it");
    }

    @Test
    void thePinIsOnePictureStampedEightTimes() {
        List<Picture> drawn = new ArrayList<>();
        DrawingExample.stamped(new NoopCanvas(160, 120) {
            @Override
            public void drawPicture(Picture picture) {
                drawn.add(picture);
            }
        });
        assertEquals(8, drawn.size());
        assertTrue(drawn.stream().allMatch(p -> p == DrawingExample.PIN), "recorded once, replayed");
    }

    @Test
    void theEraserAndThePaintInsideAShapeEachDrawInsideALayer() {
        List<String> calls = new ArrayList<>();
        Canvas canvas = new NoopCanvas(160, 120) {
            private int depth;

            @Override
            public void saveLayer(float x, float y, float width, float height, float opacity) {
                depth++;
                calls.add("layer");
            }

            @Override
            public void restore() {
                if (depth > 0) {
                    depth--;
                    calls.add("end");
                }
            }

            @Override
            public void setBlendMode(BlendMode mode) {
                calls.add((depth > 0 ? "inside " : "outside ") + mode);
            }
        };
        DrawingExample.layers(canvas);
        assertEquals(List.of("layer", "inside DESTINATION_OUT", "end", "layer", "inside SOURCE_IN", "end"), calls);
    }

    @Test
    void everyPictureOfTheGuideLaysOutItsPanelsTwoToARowInsideItsWindow() {
        try (HeadlessUi ui = new HeadlessUi()) {
            Scene[] scenes = {DrawingExample.shapesScene(), DrawingExample.paintsScene(),
                    DrawingExample.clipsScene(), DrawingExample.textScene()};
            float wide = 2 * DrawingExample.WIDTH * DrawingExample.SCALE + 16 + 40;
            for (int i = 0; i < scenes.length; i++) {
                Scene scene = scenes[i];
                scene.setTextRuler(TestRulers.FIXED);
                limn.scene.Size size = scene.root().measure(limn.scene.Constraints.loose(1024, 700));
                assertEquals(wide, size.width(), 0.01f, "picture " + i + ": two panels and their gap, padded");
                assertTrue(size.height() <= 700, "picture " + i + " fits the showcase's window: " + size);
                scene.layoutPass(1024, 700);
                if (i < 3) {
                    // The text picture shapes glyph outlines and draws an image, which need a
                    // backend; the other three draw on any canvas.
                    scene.renderFrame(new NoopCanvas(1024, 700));
                }
            }
        }
    }
}
