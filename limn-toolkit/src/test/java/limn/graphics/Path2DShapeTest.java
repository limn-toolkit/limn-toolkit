package limn.graphics;

import limn.backend.internal.PathShapes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A path knows when it is one rectangle, rounded rectangle or circle and nothing else, which a
 * canvas clips by without drawing it; anything added, or anything but those alone, and it does not.
 */
class Path2DShapeTest {

    @Test
    void aLoneShapeIsKnown() {
        assertEquals(RoundRect.of(1, 2, 30, 40, 0), PathShapes.shapeOf(new Path2D().addRect(1, 2, 30, 40)));
        assertEquals(RoundRect.of(1, 2, 30, 40, 5), PathShapes.shapeOf(new Path2D().addRoundRect(1, 2, 30, 40, 5)));
        assertEquals(RoundRect.of(5, 5, 20, 20, 10), PathShapes.shapeOf(new Path2D().addCircle(15, 15, 10)));
        assertEquals(RoundRect.of(1, 2, 30, 40, 0), PathShapes.shapeOf(new Path2D().addRect(1, 2, 30, 40).copy()),
                "a copy keeps it");
    }

    @Test
    void anythingElseIsNot() {
        assertNull(PathShapes.shapeOf(new Path2D()));
        assertNull(PathShapes.shapeOf(new Path2D().addEllipse(15, 15, 10, 5)), "an ellipse");
        assertNull(PathShapes.shapeOf(new Path2D().addRect(0, 0, 10, 10).lineTo(20, 20)), "a segment after it");
        assertNull(PathShapes.shapeOf(new Path2D().addRect(0, 0, 10, 10).addCircle(30, 30, 5)), "a second shape");
        assertNull(PathShapes.shapeOf(new Path2D().moveTo(0, 0).addRect(0, 0, 10, 10)), "a shape after a move");
        assertNull(PathShapes.shapeOf(new Path2D().addRect(0, 0, -10, 10)), "a negative size");
        assertNull(PathShapes.shapeOf(new Path2D().addCircle(0, 0, 0)), "no radius");
        Path2D reused = new Path2D().addRect(0, 0, 10, 10);
        reused.reset();
        assertNull(PathShapes.shapeOf(reused), "reset");
        assertNull(PathShapes.shapeOf(new Path2D().addPath(new Path2D().addRect(0, 0, 10, 10))),
                "added from another path");
    }
}
