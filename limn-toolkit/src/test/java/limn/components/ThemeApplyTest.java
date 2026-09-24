package limn.components;

import limn.graphics.Color;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What {@link Theme#apply} shows. Widgets read the palette as they paint, so a repaint carries
 * them over; the colour a scene clears to behind them does not, because it was copied out of
 * a palette when the scene was built. The first snippet of the theming guide is
 * {@code Theme.limnLight().apply(scene)}, and without the clear colour it drew the light
 * palette's dark ink on the dark canvas every scene starts with.
 */
class ThemeApplyTest extends ComponentTestBase {

    /** Records the colour of every clear, whole or partial, a frame makes. */
    private static final class ClearRecorder extends FakeCanvas {
        final List<Color> clears = new ArrayList<>();

        ClearRecorder() {
            super(400, 300);
        }

        @Override
        public void clear(Color color) {
            clears.add(color);
        }

        @Override
        public void clearRect(float x, float y, float width, float height, Color color) {
            clears.add(color);
        }
    }

    private Scene builtScene() {
        Scene scene = new Scene(new Label("Some text on the canvas"));
        scene.setTextRuler(RULER);
        scene.renderFrame(new ClearRecorder());
        return scene;
    }

    @Test
    void applyingALightPaletteClearsTheWindowToItsCanvas() {
        Scene scene = builtScene();

        Theme.limnLight().apply(scene);

        assertEquals(Theme.limnLight().background(), scene.background(),
                "the scene still clears to the colour it was built with");
        ClearRecorder frame = new ClearRecorder();
        scene.renderFrame(frame);
        assertFalse(frame.clears.isEmpty(), "the switch repainted nothing");
        for (Color cleared : frame.clears) {
            assertEquals(Theme.limnLight().background(), cleared,
                    "a frame after the switch cleared to another colour than the palette's canvas");
        }
    }

    @Test
    void aTranslucentSceneKeepsItsBackground() {
        // A scene that clears to transparent does so to let the desktop through, as the popups
        // of a combo box, a menu and a date picker do; a palette has no say over that.
        Scene scene = builtScene();
        scene.setBackground(Color.TRANSPARENT);

        Theme.limnLight().apply(scene);

        assertEquals(Color.TRANSPARENT, scene.background());
    }
}
