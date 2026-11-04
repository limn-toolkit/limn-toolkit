package limn.graphics;

import limn.math.Affine2D;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link Picture} keeps what its painter drew, as it was drawn, and draws it again on any canvas
 * in that canvas's coordinates, inside another picture too, leaving the canvas's state as it found
 * it.
 */
class PictureTest {

    /** Keeps the drawing calls replayed into it, with the transform each was drawn under. */
    private static final class Keeping extends NoopCanvas {
        final List<String> calls = new ArrayList<>();
        private Affine2D t = Affine2D.IDENTITY;
        private final java.util.ArrayDeque<Affine2D> saved = new java.util.ArrayDeque<>();

        Keeping() {
            super(400, 300);
        }

        @Override
        public void save() {
            saved.push(t);
        }

        @Override
        public void restore() {
            t = saved.isEmpty() ? Affine2D.IDENTITY : saved.pop();
        }

        @Override
        public int saveCount() {
            return saved.size();
        }

        @Override
        public void restoreToCount(int count) {
            while (saved.size() > count) {
                restore();
            }
        }

        @Override
        public void translate(float dx, float dy) {
            t = t.translate(dx, dy);
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            calls.add("fillRect " + (int) (t.e() + x) + "," + (int) (t.f() + y));
        }

        @Override
        public void fillPath(Path2D path, Paint paint) {
            calls.add("fillPath " + path.bounds());
        }

        @Override
        public void drawSurface(GpuSurface surface, float x, float y, float w, float h) {
            calls.add("drawSurface");
        }

        @Override
        public void saveLayer(float x, float y, float width, float height, float opacity,
                              limn.math.Mat4 transform) {
            save();
            calls.add("saveLayer3D " + (int) width + "x" + (int) height + " at " + opacity);
        }

        @Override
        public void saveLayer(float x, float y, float width, float height, float opacity) {
            save();
            calls.add("saveLayer " + (int) width + "x" + (int) height + " at " + opacity);
        }
    }

    @Test
    void itDrawsWhatWasRecordedInTheCanvassCoordinates() {
        Picture square = Picture.record(20, 20, c -> c.fillRect(2, 3, 10, 10, Color.BLACK));
        Keeping canvas = new Keeping();
        canvas.translate(100, 50);
        canvas.drawPicture(square);
        canvas.drawPicture(square);
        assertEquals(List.of("fillRect 102,53", "fillRect 102,53"), canvas.calls);
        assertEquals(0, canvas.saveCount(), "the canvas's state as it was");
        assertEquals(20, square.width());
    }

    @Test
    void aLayerSeenThroughA3DMatrixIsReplayedAsThatLayer() {
        // Recorded as the canvas's default, a save, a concat and an opacity, it came back as no
        // layer at all: each draw faded on its own, nothing cut to the layer's bounds.
        limn.math.Mat4 turned = limn.math.Mat4.identity();
        Picture p = Picture.record(100, 100, c -> {
            c.saveLayer(0, 0, 50, 40, 0.5f, turned);
            c.fillRect(0, 0, 40, 40, Color.BLACK);
            c.restore();
        });
        Keeping canvas = new Keeping();
        p.draw(canvas);
        assertEquals(List.of("saveLayer3D 50x40 at 0.5", "fillRect 0,0"), canvas.calls);
        assertEquals(0, canvas.saveCount(), "and closed by its restore");
    }

    @Test
    void aPathIsKeptAsItWasWhenDrawn() {
        Path2D path = new Path2D().addRect(0, 0, 5, 5);
        Picture p = Picture.record(10, 10, c -> c.fillPath(path, Color.BLACK));
        path.reset();
        Keeping canvas = new Keeping();
        p.draw(canvas);
        assertTrue(canvas.calls.get(0).contains("width=5.0"), canvas.calls.get(0));
    }

    @Test
    void aPictureDrawsInsideAnotherAndKeepsASurface() {
        Picture inner = Picture.record(10, 10, c -> c.fillRect(0, 0, 1, 1, Color.BLACK));
        GpuSurface surface = new GpuSurface() {
            @Override
            public int widthPx() {
                return 4;
            }

            @Override
            public int heightPx() {
                return 4;
            }

            @Override
            public void resize(int widthPx, int heightPx) {
            }

            @Override
            public void dispose() {
            }
        };
        Picture outer = Picture.record(50, 50, c -> {
            c.translate(10, 10);
            c.drawPicture(inner);
            c.drawSurface(surface, 0, 0, 4, 4);
        });
        Keeping canvas = new Keeping();
        outer.draw(canvas);
        assertEquals(List.of("fillRect 10,10", "drawSurface"), canvas.calls);
    }

    @Test
    void theRecordingCanvasAnswersThePicturesSizeAndScale() {
        float[] seen = new float[3];
        Picture.record(30, 40, 3, c -> {
            seen[0] = c.width();
            seen[1] = c.height();
            seen[2] = c.contentScale();
        });
        assertEquals(30, seen[0]);
        assertEquals(40, seen[1]);
        assertEquals(3, seen[2]);
        assertThrows(IllegalArgumentException.class, () -> Picture.record(-1, 5, c -> { }));
        assertThrows(IllegalArgumentException.class, () -> Picture.record(5, 5, 0, c -> { }));
    }
}
