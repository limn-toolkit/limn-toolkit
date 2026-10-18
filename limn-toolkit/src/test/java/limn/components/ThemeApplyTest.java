package limn.components;

import limn.graphics.Color;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What a palette switch shows. Widgets read the palette as they paint, and so does the colour a
 * scene clears to behind them unless its application chose one: every scene repaints when the
 * palette moves, without being handed over, and one built after the switch clears to the new
 * palette from its first frame. When the clear colour was copied out of a palette as the scene
 * was built, {@code Theme.light().apply()} before the first window drew the light palette's
 * widgets on the dark canvas every scene started with, and a window opened after a switch, or
 * never handed to {@code apply}, mixed the two palettes.
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

    /** Renders a frame of {@code scene} and asserts that it repainted and cleared to {@code expected}. */
    private static void assertClearsTo(Color expected, Scene scene, String why) {
        ClearRecorder frame = new ClearRecorder();
        scene.renderFrame(frame);
        assertFalse(frame.clears.isEmpty(), "the frame repainted nothing: " + why);
        for (Color cleared : frame.clears) {
            assertEquals(expected, cleared, why);
        }
    }

    @Test
    void aPaletteAppliedBeforeAnyWindowIsTheFirstFramesCanvas() {
        Theme.light().apply();

        Scene scene = new Scene(new Label("Some text on the canvas"));
        scene.setTextRuler(RULER);

        assertEquals(Theme.light().background(), scene.background());
        assertClearsTo(Theme.light().background(), scene,
                "a scene built after the palette was applied clears to that palette's canvas");
    }

    @Test
    void aSwitchRepaintsAWindowNobodyHandedOverInTheNewCanvas() {
        Scene scene = builtScene();

        Theme.limnLight().apply();

        assertClearsTo(Theme.limnLight().background(), scene,
                "a frame after the switch cleared to another colour than the palette's canvas");
    }

    @Test
    void aSecondWindowOpenedAfterASwitchFollowsIt() {
        Scene first = builtScene();
        Theme.limnLight().apply();
        assertClearsTo(Theme.limnLight().background(), first, "the first window follows the switch");

        Scene second = builtScene();
        assertClearsTo(Theme.limnLight().background(), second,
                "and so does one opened after it, in a later frame");
        Theme.dark().apply();
        assertClearsTo(Theme.dark().background(), first, "both follow the next switch");
        assertClearsTo(Theme.dark().background(), second, "both follow the next switch");
    }

    @Test
    void aBackgroundTheApplicationChoseIsKeptAcrossASwitch() {
        // A scene that clears to transparent does so to let the desktop through, as the popups
        // of a combo box, a menu and a date picker do; a palette has no say over that, nor over
        // an opaque colour of the application's own.
        Scene clear = builtScene();
        clear.setBackground(Color.TRANSPARENT);
        Scene black = builtScene();
        black.setBackground(Color.BLACK);

        Theme.limnLight().apply();

        assertEquals(Color.TRANSPARENT, clear.background());
        assertClearsTo(Color.TRANSPARENT, clear, "a transparent scene stays transparent");
        assertClearsTo(Color.BLACK, black, "an opaque colour the application chose is kept");

        black.setBackground(null);
        black.requestRender();
        assertClearsTo(Theme.limnLight().background(), black,
                "and handed back, the scene follows the palette again");
    }
}
