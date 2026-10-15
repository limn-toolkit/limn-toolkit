package limn.components;

import limn.graphics.BitmapIcon;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.graphics.SvgIcon;
import limn.graphics.SvgRenderer;
import limn.scene.Scene;
import limn.testfixtures.FakeSvg;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An image view shows an icon as well as an image (ADR 066 section 4): drawn at the view's own size
 * and density, so a vector stays sharp, measured at the icon's natural size, tinted as a mask when
 * the icon is one, and played with the view's controls when it moves.
 */
class ImageViewIconTest {

    private final AtomicLong nanos = new AtomicLong();
    private HeadlessUi ui;
    private final List<Integer> asked = new ArrayList<>();
    private final SvgRenderer renderer = FakeSvg.renderer((svg, side) -> {
        asked.add(side);
        return new Image(side, side, new byte[side * side * 4]);
    });
    private Image drawn;
    private boolean masked;
    private float scale = 1;
    private NoopCanvas canvas;

    @BeforeEach
    void setUp() {
        ui = new HeadlessUi(nanos::get);
        SvgIcon.installRenderer(renderer);
        canvas = new NoopCanvas(400, 400) {
            @Override public float contentScale() { return scale; }
            @Override public void drawImage(Image image, float x, float y, float w, float h) { drawn = image; masked = false; }
            @Override public void drawImage(Image image, float x, float y, float w, float h, Color tint) { drawn = image; masked = false; }
            @Override public void drawImageMask(Image image, float x, float y, float w, float h, Color tint) { drawn = image; masked = true; }
        };
    }

    @AfterEach
    void tearDown() {
        SvgIcon.uninstallRenderer(renderer);
        ui.close();
    }

    private Scene show(ImageView view, float w, float h) {
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(view);
        Scene scene = new Scene(column, nanos::get);
        scene.setTextRuler(TestRulers.FIXED);
        view.setPreferredSize(w, h);
        scene.layoutPass(400, 400);
        scene.renderFrame(canvas);
        return scene;
    }

    @Test
    void anSvgIsDrawnAtTheViewsOwnSizeAndDensity() {
        SvgIcon icon = SvgIcon.of("<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'/>");
        ImageView view = new ImageView(icon);
        show(view, 100, 100);
        assertEquals(100, drawn.width(), "a 100-point view draws 100 pixels at 1x");
        scale = 2;
        show(new ImageView(icon), 150, 150);
        assertEquals(300, drawn.width(), "and 300 at 2x for 150 points: never a bitmap stretched");
    }

    @Test
    void anIconsNaturalSizeIsWhatTheViewMeasuresAt() {
        SvgIcon icon = SvgIcon.of("<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'/>");
        ImageView view = new ImageView(icon);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(view);
        Scene scene = new Scene(column, nanos::get);
        scene.setTextRuler(TestRulers.FIXED);
        scene.layoutPass(400, 400);
        assertEquals(1, view.width(), "the fake document is one unit square");
        Image big = new Image(40, 20, new byte[40 * 20 * 4]);
        view.setIcon(BitmapIcon.picture(big));
        scene.layoutPass(400, 400);
        assertEquals(40, view.width(), "a bitmap icon's picture");
        assertEquals(20, view.height());
    }

    @Test
    void aTintableIconTakesTheTintAsItsColourAndAPictureIsTintedAsAnImage() {
        show(new ImageView(BitmapIcon.mask(new Image(8, 8, new byte[256]))).setTint(Color.rgb(0xFF0000)), 16, 16);
        assertTrue(masked, "a mask's shape in the tint's colour");
        show(new ImageView(BitmapIcon.picture(new Image(8, 8, new byte[256]))).setTint(Color.rgb(0xFF0000)), 16, 16);
        assertTrue(!masked);
    }

    @Test
    void anImageAndAnIconReplaceEachOther() {
        Image image = new Image(4, 4, new byte[64]);
        ImageView view = new ImageView(image);
        view.setIcon(BitmapIcon.picture(image));
        assertNull(view.image());
        view.setImage(image);
        assertNull(view.icon());
        assertSame(image, view.image());
    }

    @Test
    void anAnimatedIconPlaysWithTheViewsControls() {
        List<Image> frames = new ArrayList<>();
        for (int f = 0; f < 4; f++) {
            frames.add(new Image(2, 2, new byte[16]));
        }
        Image animated = Images.animation(frames, new int[] {100, 100, 100, 100}, 0);
        ImageView view = new ImageView(BitmapIcon.picture(animated)).setSpeed(2);
        Scene scene = show(view, 20, 20);
        scene.renderFrame(canvas);
        assertSame(animated.frame(0), drawn);
        nanos.addAndGet(60_000_000L);
        ui.runtime().drain();
        scene.renderFrame(canvas);
        assertSame(animated.frame(1), drawn, "twice as fast: 50 ms a frame");
        view.setPlaying(false);
        nanos.addAndGet(500_000_000L);
        ui.runtime().drain();
        scene.renderFrame(canvas);
        assertSame(animated.frame(1), drawn, "held");
        view.seekFrame(3);
        scene.renderFrame(canvas);
        assertEquals(3, view.frameIndex());
        assertSame(animated.frame(3), drawn);
    }
}
