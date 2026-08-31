package limn.components;

import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Icon.Mirroring}: the one legal negative scale in this toolkit, and what it is scoped to.
 *
 * <p>Mirroring a layout is a placement decision taken site by site; this is the exception, and it
 * is an exception because it acts on a single image inside its own box and never on a tree.
 */
class IconMirroringTest extends ComponentTestBase {

    private static final float EPS = 1e-3f;

    /** A 10x10 image; the icon under test is a mask, so it draws through {@code drawImageMask}. */
    private static final Icon SQUARE = (pixelSize, dark) -> new Image(10, 10, new byte[10 * 10 * 4]);

    /** Records the composed transform, so a flip can be read as where the ink actually lands. */
    private static final class TransformCanvas extends FakeCanvas {
        float tx;
        float sx = 1;
        final List<float[]> masks = new ArrayList<>();
        final List<Float> saves = new ArrayList<>();

        TransformCanvas() {
            super(100, 100);
        }

        @Override
        public void translate(float dx, float dy) {
            tx += sx * dx;
        }

        @Override
        public void scale(float scaleX, float scaleY) {
            sx *= scaleX;
        }

        @Override
        public void save() {
            super.save();
            saves.add(tx);
        }

        @Override
        public void restore() {
            super.restore();
            if (!saves.isEmpty()) {
                tx = saves.remove(saves.size() - 1);
                sx = 1;
            }
        }

        @Override
        public void drawImageMask(Image image, float x, float y, float w, float h, Color tint) {
            // The two device-space x of the destination rect's edges, in draw order.
            masks.add(new float[]{tx + sx * x, tx + sx * (x + w)});
        }
    }

    @Test
    void anUnmirroredIconIsDrawnExactlyWhereItIsPlaced() {
        TransformCanvas canvas = new TransformCanvas();
        SQUARE.paint(canvas, 20, 0, 16, Color.WHITE, true, false);
        assertEquals(1, canvas.masks.size());
        assertEquals(20, canvas.masks.get(0)[0], EPS);
        assertEquals(36, canvas.masks.get(0)[1], EPS);
    }

    @Test
    void aMirroredIconIsReflectedAboutItsOwnBoxAndNothingElse() {
        TransformCanvas canvas = new TransformCanvas();
        SQUARE.paint(canvas, 20, 0, 16, Color.WHITE, true, true);
        assertEquals(1, canvas.masks.size());
        // The box was [20, 36]; reflected about its own centre it is still [20, 36], with the ink
        // reversed inside it. An icon that moved would have been reflected about something else.
        float[] edges = canvas.masks.get(0);
        assertEquals(36, edges[0], EPS, "the left edge of the source lands on the right of the box");
        assertEquals(20, edges[1], EPS, "and the right edge on the left");
    }

    @Test
    void theFlipIsUndoneSoNothingAfterItIsMirrored() {
        TransformCanvas canvas = new TransformCanvas();
        SQUARE.paint(canvas, 20, 0, 16, Color.WHITE, true, true);
        SQUARE.paint(canvas, 20, 0, 16, Color.WHITE, true, false);
        assertEquals(20, canvas.masks.get(1)[0], EPS, "the transform did not leak");
        assertEquals(0, canvas.saveCount(), "and the save was balanced");
    }
}
