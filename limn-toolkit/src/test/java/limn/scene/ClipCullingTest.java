package limn.scene;

import limn.components.Button;
import limn.components.ScrollView;
import limn.graphics.Rect;
import limn.scene.layout.Column;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A subtree scrolled entirely out of a viewport is not painted. Partial rendering already
 * skipped what missed its pass rect; a plain full-frame paint walked every row of a scrolled
 * column, ran its paint, and put its quads in the batch for the GPU to discard, because the
 * canvas only refused work when its clip was <em>empty</em>, never when a box missed it.
 */
class ClipCullingTest extends SceneTestBase {

    /** A {@link NoopCanvas} that tracks translation and rect clips, as a real canvas does. */
    private static final class ClipTrackingCanvas extends NoopCanvas {
        private record State(float tx, float ty, Rect clip) {
        }

        private final Deque<State> stack = new ArrayDeque<>();
        private float tx;
        private float ty;
        private Rect clip;

        ClipTrackingCanvas(float width, float height) {
            super(width, height);
            clip = new Rect(0, 0, width, height);
        }

        @Override
        public void save() {
            super.save();
            stack.push(new State(tx, ty, clip));
        }

        @Override
        public void restore() {
            super.restore();
            State s = stack.pop();
            tx = s.tx();
            ty = s.ty();
            clip = s.clip();
        }

        @Override
        public void translate(float dx, float dy) {
            tx += dx;
            ty += dy;
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            float x0 = Math.max(clip.x(), tx + x);
            float y0 = Math.max(clip.y(), ty + y);
            float x1 = Math.min(clip.x() + clip.width(), tx + x + w);
            float y1 = Math.min(clip.y() + clip.height(), ty + y + h);
            clip = new Rect(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
        }

        @Override
        public Rect clipBounds() {
            return new Rect(clip.x() - tx, clip.y() - ty, clip.width(), clip.height());
        }
    }

    private static final int ROWS = 200;

    private static Scene scrolledScene(float viewportHeight) {
        Column column = new Column();
        for (int i = 0; i < ROWS; i++) {
            column.add(new Button("row " + i));
        }
        ScrollView scroll = new ScrollView(column);
        Scene scene = new Scene(scroll);
        scene.layoutPass(300, viewportHeight);
        return scene;
    }

    @Test
    void rowsScrolledOutOfTheViewportAreNotPainted() {
        Scene scene = scrolledScene(120);
        ClipTrackingCanvas canvas = new ClipTrackingCanvas(300, 120);
        scene.renderFrame(canvas);
        float painted = scene.metrics().paintedWidgets().last();
        // The viewport shows a handful of rows; the scroll view, the column, the bar and the
        // rows straddling the edges are the rest. Two hundred is what it used to be.
        assertTrue(painted < 20, "painted " + painted + " widgets for a 120 pt viewport over "
                + ROWS + " rows");
    }

    @Test
    void aCanvasThatCannotReportItsClipPaintsEverything() {
        Scene scene = scrolledScene(120);
        NoopCanvas canvas = new NoopCanvas(300, 120);
        scene.renderFrame(canvas);
        assertTrue(scene.metrics().paintedWidgets().last() > ROWS,
                "the null answer skips nothing");
    }

    @Test
    void whatIsPaintedFollowsTheScrollPosition() {
        Scene scene = scrolledScene(120);
        ClipTrackingCanvas canvas = new ClipTrackingCanvas(300, 120);
        scene.renderFrame(canvas);
        float atTop = scene.metrics().paintedWidgets().last();
        ScrollView scroll = (ScrollView) scene.root();
        scroll.scrollTo(0, 2000);
        scene.layoutPass(300, 120);
        scene.renderFrame(canvas);
        assertEquals(atTop, scene.metrics().paintedWidgets().last(), 4,
                "a viewport in the middle of the column paints as many rows as one at the top");
    }
}
