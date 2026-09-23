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
        private boolean drew;

        ClipCanvas() {
            super(400, 300);
        }

        @Override
        public void clipRect(float x, float y, float w, float h) {
            if (!drew) {
                clips.add(String.format("%.0f,%.0f %.0fx%.0f", x, y, w, h));
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
            for (String clip : clips) {
                String[] parts = clip.split("[, x]+");
                float x = Float.parseFloat(parts[0]);
                float y = Float.parseFloat(parts[1]);
                float w = Float.parseFloat(parts[2]);
                float h = Float.parseFloat(parts[3]);
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

        Box(boolean backdrop) {
            this.backdrop = backdrop;
        }

        @Override
        protected boolean paintsFromBackdrop() {
            return backdrop;
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(100, 40);
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
        for (int i = 0; i < 3; i++) {
            scene.renderFrame(new ClipCanvas());
        }
    }

    private ClipCanvas frame(Scene scene) {
        ClipCanvas canvas = new ClipCanvas();
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
