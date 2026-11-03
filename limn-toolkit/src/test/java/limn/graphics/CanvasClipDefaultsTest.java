package limn.graphics;

import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The path clip's default, for a canvas of an application's own: the path's bounds, a region that
 * holds the path and more, and nothing for an empty path.
 */
class CanvasClipDefaultsTest {

    /** Keeps the rectangles it is clipped to. */
    private static final class Clipping extends NoopCanvas {
        final List<Rect> clips = new ArrayList<>();

        Clipping() {
            super(400, 300);
        }

        @Override
        public void clipRect(float x, float y, float width, float height) {
            clips.add(new Rect(x, y, width, height));
        }
    }

    @Test
    void aPathClipIsItsBoundsOnACanvasWithNoOtherClip() {
        Clipping canvas = new Clipping();
        canvas.clipPath(new Path2D().moveTo(10, 20).lineTo(90, 30).lineTo(40, 80).close());
        assertEquals(List.of(new Rect(10, 20, 80, 60)), canvas.clips);
    }

    @Test
    void anEmptyPathClipsEverythingAway() {
        Clipping canvas = new Clipping();
        canvas.clipPath(new Path2D());
        assertEquals(List.of(new Rect(0, 0, 0, 0)), canvas.clips);
    }
}
