package limn.demo;

import limn.backend.CrashHandler;
import limn.backend.Crashes;
import limn.components.VideoView;
import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The video scene's audio-track button changes nothing while it is painted: its re-read changes
 * the button's text and enabled state, and a change announced during a paint throws, so every
 * frame that painted it used to fail and be contained.
 */
class AudioTracksPaintTest {

    @Test
    void aFrameThatPaintsTheTrackButtonCrashesNothingAndTheButtonStillFollowsTheSource() {
        List<Throwable> crashes = new ArrayList<>();
        CrashHandler record = (phase, error) -> {
            crashes.add(error);
            return true;
        };
        Crashes.install(record);
        try (HeadlessUi ui = new HeadlessUi()) {
            VideoScene.AudioTracks tracks =
                    new VideoScene.AudioTracks(new VideoScene.Playing(), new VideoView());
            Scene scene = new Scene(tracks);
            scene.setTextRuler(TestRulers.FIXED);
            scene.layoutPass(400, 60);
            scene.renderFrame(new NoopCanvas(400, 60));
            limn.components.Button button =
                    (limn.components.Button) tracks.children().get(0).children().get(0);
            ui.pumpUntil(() -> button.text().equals("Generated soundtrack"));
            scene.layoutPass(400, 60);
            scene.renderFrame(new NoopCanvas(400, 60));

            assertEquals(List.of(), crashes);
            assertEquals("Generated soundtrack", button.text());
        } finally {
            Crashes.uninstall(record);
        }
    }
}
