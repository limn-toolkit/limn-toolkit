package limn.scene;

import limn.concurrent.UiRuntime;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Paint;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 019 §6's limit, closed: a widget made of the pixels behind it repaints when they do.
 *
 * <p>The failure this pins is not a crash and not a wrong number &mdash; it is a picture that
 * keeps showing history, which no assertion about a widget's own state can see. What it can see is
 * the damage: if the panel's rectangle is not in the frame's repaint region, the panel is not
 * repainted, and its backdrop is whatever it was.
 */
class BackdropDamageTest {

    private HeadlessUi ui;

    /** Records the clip the scene applies before anything is drawn: the frame's repaint region. */
    private static final class ClipCanvas extends NoopCanvas {
        final List<String> clips = new ArrayList<>();
        final List<float[]> rects = new ArrayList<>();
        private final float scale;
        private boolean drew;

        ClipCanvas() {
            this(1);
        }

        /** @param scale device pixels per point: 2 is a Retina screen's, where a quarter point is half a pixel */
        ClipCanvas(float scale) {
            super(400, 300);
            this.scale = scale;
        }

        @Override
        public float contentScale() {
            return scale;
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            if (!drew) {
                clips.add(String.format(Locale.ROOT, "%.3f,%.3f %.3fx%.3f", x, y, w, h));
                rects.add(new float[] {x, y, w, h});
            }
        }

        @Override
        public void clear(Color color) {
            drew = true;
        }

        @Override
        public void fillRect(float x, float y, float w, float h, Paint paint) {
            drew = true;
        }

        boolean full() {
            return clips.isEmpty();
        }

        boolean covers(Widget<?> widget) {
            float wx = widget.localToSceneX();
            float wy = widget.localToSceneY();
            for (float[] clip : rects) {
                float x = clip[0];
                float y = clip[1];
                float w = clip[2];
                float h = clip[3];
                if (x <= wx && y <= wy
                        && x + w >= wx + widget.width() && y + h >= wy + widget.height()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A widget with a size, which paints and can be told it lives off its backdrop. */
    private static class Box extends Widget<Box> {
        private final boolean backdrop;
        private final float boxWidth;
        private final float boxHeight;
        /** How far past its box it reads what is behind it, as a blur does. */
        float reach;

        Box(boolean backdrop) {
            this(backdrop, 100, 40);
        }

        Box(boolean backdrop, float width, float height) {
            this.backdrop = backdrop;
            this.boxWidth = width;
            this.boxHeight = height;
        }

        @Override
        protected boolean paintsFromBackdrop() {
            return backdrop;
        }

        @Override
        protected float backdropReach() {
            return reach;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(boxWidth, boxHeight);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            canvas.fillRect(0, 0, width(), height(), Color.WHITE);
        }
    }

    @BeforeEach
    void install() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void uninstall() {
        ui.close();
    }

    private Scene sceneOf(Widget<?> root) {
        Scene scene = new Scene(root);
        scene.setPartialRendering(true);
        scene.layoutPass(400, 300);
        return scene;
    }

    /** Two frames of nothing, so the two-frame union has settled and a damage is visible alone. */
    private void settle(Scene scene) {
        settle(scene, 1);
    }

    private void settle(Scene scene, float scale) {
        for (int i = 0; i < 3; i++) {
            scene.renderFrame(new ClipCanvas(scale));
        }
    }

    private ClipCanvas frame(Scene scene) {
        return frame(scene, 1);
    }

    private ClipCanvas frame(Scene scene, float scale) {
        ClipCanvas canvas = new ClipCanvas(scale);
        scene.renderFrame(canvas);
        return canvas;
    }

    @Test
    void aPanelMadeOfWhatIsBehindItRepaintsWhenThatChanges() {
        Box behind = new Box(false);
        Box glass = new Box(true);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(behind);
        column.add(glass);
        Scene scene = sceneOf(column);
        assertEquals(1, scene.backdropDependantCount(), "the panel registered itself");
        settle(scene);

        behind.invalidate();
        frame(scene);          // the two-frame union still carries the settle frames
        behind.invalidate();
        ClipCanvas canvas = frame(scene);
        assertFalse(canvas.full(), "the frame is partial, which is the whole point of the mode");
        assertTrue(canvas.covers(glass),
                "the panel is made of pixels that just changed, so it repaints: " + canvas.clips);
    }

    @Test
    void aPlainWidgetIsNotDraggedIntoTheDamage() {
        Box behind = new Box(false);
        Box plain = new Box(false);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(behind);
        column.add(plain);
        Scene scene = sceneOf(column);
        assertEquals(0, scene.backdropDependantCount());
        settle(scene);

        behind.invalidate();
        frame(scene);
        behind.invalidate();
        ClipCanvas canvas = frame(scene);
        assertFalse(canvas.covers(plain),
                "nothing says this one depends on its neighbour, so it is left alone");
    }

    @Test
    void aPanelNowhereNearTheDamageIsLeftAlone() {
        Box behind = new Box(false);
        Box spacer = new Box(false);
        Box glass = new Box(true);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.gap(80); // far enough that the damage cannot reach the panel
        column.add(behind);
        column.add(spacer);
        column.add(glass);
        Scene scene = sceneOf(column);
        settle(scene);

        behind.invalidate();
        frame(scene);
        behind.invalidate();
        ClipCanvas canvas = frame(scene);
        assertFalse(canvas.covers(glass), "damage it does not touch is none of its business");
    }

    /**
     * The pass a backdrop panel widens the damage into lies on the device grid, as every other pass
     * does.
     *
     * <p>The defect this pins was seen on a Retina screen as a thin line down the edge of a floating
     * panel of glass, through the picture it floats over, whenever something inside the panel
     * repainted: a slider moving, a button lighting up under the pointer. The panel's rectangle was
     * added at its layout position, a fraction of a point in, while every other damage rectangle is
     * snapped outward to whole device pixels. The pass then cleared the pixel its edge cut through
     * whole, rounding, and painted it back at the clip's fractional coverage, so what showed there
     * was the picture with a share of the window's background in it: a column of it the height of
     * the pass, standing until something repainted that pixel whole. The backend's
     * BackdropPassSeamTest measures the column itself on a GPU.
     */
    @Test
    void thePassABackdropPanelWidensTheDamageIntoLiesOnTheDeviceGrid() {
        Box glass = new Box(true, 100.6f, 40);
        Box inside = new Box(false, 10, 10);
        limn.scene.layout.Stack layers = new limn.scene.layout.Stack();
        // Where a centred panel lands: a fraction of a point in, on both axes.
        layers.add(new limn.scene.layout.Padding(new Insets(10.35f, 0, 0, 20.1f), glass));
        layers.add(new limn.scene.layout.Padding(new Insets(25, 0, 0, 60), inside));
        Scene scene = sceneOf(layers);
        assertEquals(20.1f, glass.localToSceneX(), 1e-4f, "the panel sits off the grid, as the case needs");
        settle(scene, 2);

        inside.invalidate(); // something over the glass repaints, and the glass with it
        frame(scene, 2);
        inside.invalidate();
        ClipCanvas canvas = frame(scene, 2);
        assertFalse(canvas.full(), "the frame is partial");
        assertTrue(canvas.covers(glass), "the panel is in the damage: " + canvas.clips);
        for (float[] clip : canvas.rects) {
            for (float edge : new float[] {clip[0], clip[1], clip[0] + clip[2], clip[1] + clip[3]}) {
                float device = edge * 2;
                assertEquals(Math.round(device), device, 1e-3f,
                        "every pass edge is a whole device pixel, or the pass clears a pixel whole and "
                                + "paints it back in part: " + canvas.clips);
            }
        }
    }

    /**
     * A panel that reads past its box (a blur) repaints when something within that reach changes,
     * and repaints the reach with it.
     *
     * <p>Two defects with one cause. A change just outside a frosted panel, nearer than its blur
     * reaches, changes the panel's picture, and the damage test, which looked at the box alone,
     * left the panel showing the old blur. And a pass that held the box alone let the blur read the
     * margin around it as the last frame had finished it, with whatever was painted over it there,
     * where a whole frame lets it read that margin before anything later is drawn: a scroll bar over
     * the end of the Showcase's frosted toolbar came out blurred into the glass on every partial
     * repaint, by up to 12 levels in 255.
     */
    @Test
    void aChangeWithinThePanelsReachRepaintsItWithItsReach() {
        Box glass = new Box(true, 100, 40);
        glass.reach = 10;
        Box neighbour = new Box(false, 10, 10);
        limn.scene.layout.Stack layers = new limn.scene.layout.Stack();
        layers.add(new limn.scene.layout.Padding(new Insets(20, 0, 0, 40), glass));
        // Four points past the panel's right edge: beyond the point every damage rectangle is
        // grown by, within the ten the panel reads.
        layers.add(new limn.scene.layout.Padding(new Insets(30, 0, 0, 144), neighbour));
        Scene scene = sceneOf(layers);
        settle(scene);

        neighbour.invalidate();
        frame(scene);
        neighbour.invalidate();
        ClipCanvas canvas = frame(scene);
        assertFalse(canvas.full(), "the frame is partial");
        assertTrue(canvas.covers(glass), "a change the panel reads is a change behind it: " + canvas.clips);
        boolean withItsReach = false;
        for (float[] clip : canvas.rects) {
            withItsReach |= clip[0] <= 40 - 10 && clip[1] <= 20 - 10
                    && clip[0] + clip[2] >= 140 + 10 && clip[1] + clip[3] >= 60 + 10;
        }
        assertTrue(withItsReach, "the panel repaints with the margin it reads: " + canvas.clips);
    }

    @Test
    void aPanelLeavingTheSceneStopsBeingConsulted() {
        Box glass = new Box(true);
        limn.scene.layout.Column column = new limn.scene.layout.Column();
        column.add(glass);
        Scene scene = sceneOf(column);
        assertEquals(1, scene.backdropDependantCount());
        column.remove(glass);
        assertEquals(0, scene.backdropDependantCount(),
                "the registry is maintained on the funnel a subtree leaves a scene through");
    }
}
