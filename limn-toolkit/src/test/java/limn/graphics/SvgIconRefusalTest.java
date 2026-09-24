package limn.graphics;

import limn.testing.NoopCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A source the rasterizer refuses. Such a source fails at every size, and it used to fail on
 * every paint: the exception left the paint, the frame caught and counted it, and after enough
 * of them the window stopped repainting at all, because of one broken icon.
 */
class SvgIconRefusalTest {

    private final List<Integer> asked = new ArrayList<>();
    private final SvgRasterizer refusing = (svg, px) -> {
        asked.add(px);
        throw new IllegalArgumentException("SVG has no intrinsic size");
    };
    private final SvgRasterizer working = (svg, px) -> {
        asked.add(px);
        return new Image(px, px, new byte[px * px * 4]);
    };

    @AfterEach
    void uninstall() {
        SvgIcon.uninstallRasterizer(refusing);
        SvgIcon.uninstallRasterizer(working);
    }

    /** Records the masks an icon paints. */
    private static final class Masks extends NoopCanvas {
        final List<Image> drawn = new ArrayList<>();

        Masks() {
            super(100, 100);
        }

        @Override
        public void drawImageMask(Image image, float x, float y, float w, float h, Color tint) {
            drawn.add(image);
        }
    }

    @Test
    void aRefusedSourcePaintsNothingAndIsNotParsedAgain() {
        SvgIcon.installRasterizer(refusing);
        SvgIcon icon = SvgIcon.of("<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M0 0L1 1\"/></svg>");
        Masks canvas = new Masks();

        for (int frame = 0; frame < 20; frame++) {
            // A size animating as well: a new size must not buy a new parse either.
            float size = 16 + frame;
            assertDoesNotThrow(() -> icon.paint(canvas, 0, 0, size, Color.BLACK, false),
                    "a paint must not throw for a source that cannot be drawn");
        }

        assertEquals(List.of(16), asked, "the rasterizer is asked once, and never again");
        assertEquals(20, canvas.drawn.size(), "every paint completes");
        for (Image mask : canvas.drawn) {
            assertSame(canvas.drawn.get(0), mask, "and draws the same empty bitmap");
            for (byte b : mask.pixels()) {
                assertEquals(0, b, "which covers nothing");
            }
        }
    }

    @Test
    void aSourceThatRasterizesIsUntouched() {
        SvgIcon.installRasterizer(working);
        SvgIcon icon = SvgIcon.of("<svg/>");
        Image first = icon.image(24);
        assertEquals(24, first.width());
        assertSame(first, icon.image(24));
        assertEquals(List.of(24), asked);
    }
}
