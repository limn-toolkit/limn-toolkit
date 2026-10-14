package limn.demo.site;

import limn.components.ImageView;
import limn.graphics.Image;
import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.NoopCanvas;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the media guide says its example of pictures that move does, on a clock the test moves. */
class AnimatedImageExampleTest {

    private HeadlessUi ui;
    private long nanos = 1_000_000_000L;

    @BeforeEach
    void install() {
        ui = new HeadlessUi(() -> nanos);
    }

    @AfterEach
    void uninstall() {
        ui.close();
    }

    @Test
    void theSpinnerPlaysTheFileByItselfAndPausingHoldsItsFrame() {
        ImageView spinner = AnimatedImageExample.spinner("/limn/demo/images/orbit.webp");
        Scene scene = new Scene(spinner, () -> nanos);
        scene.setTextRuler(TestRulers.FIXED);
        NoopCanvas canvas = new NoopCanvas(200, 200);
        scene.renderFrame(canvas);
        assertTrue(spinner.isPlaying(), "an animated picture plays by itself");
        int first = spinner.frameIndex();
        for (int i = 0; i < 10; i++) {
            nanos += 50_000_000L;
            ui.runtime().drain();
            scene.renderFrame(canvas);
        }
        assertNotEquals(first, spinner.frameIndex(), "half a second later it has moved on");

        AnimatedImageExample.pause(spinner, true);
        int held = spinner.frameIndex();
        for (int i = 0; i < 10; i++) {
            nanos += 50_000_000L;
            ui.runtime().drain();
            scene.renderFrame(canvas);
        }
        assertEquals(held, spinner.frameIndex(), "paused, it holds the frame shown");
        assertFalse(spinner.isPlaying());
    }

    @Test
    void theCoinIsEightFramesOfNinetyMillisecondsFromOneRow() {
        Image sheet = new Image(8 * 6, 6, new byte[8 * 6 * 6 * 4]);
        Image coin = AnimatedImageExample.coin(sheet);
        assertEquals(8, coin.frameCount());
        assertEquals(6, coin.width());
        assertEquals(90, coin.frameMillis(7));
    }

    @Test
    void anAnimationIsSavedAsAnAnimatedPng(@TempDir Path directory) throws IOException {
        Image coin = AnimatedImageExample.coin(new Image(8 * 6, 6, new byte[8 * 6 * 6 * 4]));
        Path file = directory.resolve("coin.png");
        AnimatedImageExample.save(coin, file);
        String chunks = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertTrue(chunks.startsWith("\u0089PNG"), "a PNG");
        assertTrue(chunks.contains("acTL") && chunks.contains("fdAT"), "with its frames");
    }
}
