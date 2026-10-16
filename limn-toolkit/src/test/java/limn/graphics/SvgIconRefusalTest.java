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
    private final limn.graphics.SvgRenderer refusing = limn.testfixtures.FakeSvg.renderer((svg, px) -> {
        asked.add(px);
        throw new IllegalArgumentException("SVG has no intrinsic size");
    });
    private final limn.graphics.SvgRenderer working = limn.testfixtures.FakeSvg.renderer((svg, px) -> {
        asked.add(px);
        return new Image(px, px, new byte[px * px * 4]);
    });

    @AfterEach
    void uninstall() {
        SvgIcon.uninstallRenderer(refusing);
        SvgIcon.uninstallRenderer(working);
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
        SvgIcon.installRenderer(refusing);
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

    /**
     * A refusal after some frames drew makes an animated icon a still one, one frame long, under
     * a timeline that stood on a later frame: the clock's next step asked for that frame's time
     * and threw IndexOutOfBoundsException on the UI thread.
     */
    @Test
    void anAnimatedIconRefusedPartWayStopsItsTimelineOnTheFirstFrame() {
        limn.graphics.SvgRenderer once = limn.testfixtures.FakeSvg.renderer((svg, px) -> {
            asked.add(px);
            if (asked.size() > 1) {
                throw new IllegalArgumentException("refused at a later moment");
            }
            return new Image(px, px, new byte[px * px * 4]);
        });
        SvgIcon.installRenderer(once);
        try {
            SvgIcon icon = SvgIcon.of("<svg xmlns='http://www.w3.org/2000/svg' width='5' height='5'><rect width='5' "
                    + "height='5'><animate attributeName='x' from='0' to='5' dur='1s' repeatCount='indefinite'/></rect></svg>");
            limn.graphics.internal.IconTimeline timeline = new limn.graphics.internal.IconTimeline(icon);
            icon.image(16, false, timeline.frame());
            timeline.advance(0.2);
            assertEquals(4, timeline.frame(), "a fifth of a second at 24 frames a second");
            icon.image(16, false, timeline.frame()); // refused
            assertEquals(1, icon.frameCount());
            assertDoesNotThrow(() -> timeline.advance(0.05));
            assertEquals(0, timeline.frame());
            assertEquals(false, timeline.isPlaying(), "a still icon's timeline has finished");
            assertDoesNotThrow(() -> icon.image(16, false, timeline.frame()));
        } finally {
            SvgIcon.uninstallRenderer(once);
        }
    }

    @Test
    void aSourceThatRasterizesIsUntouched() {
        SvgIcon.installRenderer(working);
        SvgIcon icon = SvgIcon.of("<svg/>");
        Image first = icon.image(24);
        assertEquals(24, first.width());
        assertSame(first, icon.image(24));
        assertEquals(List.of(24), asked);
    }
}
