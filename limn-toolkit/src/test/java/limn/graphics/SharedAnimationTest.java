package limn.graphics;

import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An animated picture among the shared loads: each of its frames is an entry of its own, kept
 * within the shared budget and let go by it, decoded again from the file when it is wanted and
 * gone, and pinned with the picture.
 */
class SharedAnimationTest {

    private HeadlessUi ui;
    private long sharedBudget;
    @TempDir
    Path directory;

    @BeforeEach
    void setUp() {
        ui = new HeadlessUi();
        sharedBudget = Images.sharedBudget();
        Images.clearSharedCache();
    }

    @AfterEach
    void tearDown() {
        Images.setSharedBudget(sharedBudget);
        Images.clearSharedCache();
        ui.close();
    }

    /** Six frames of 10 by 10, each one colour: 400 bytes a frame. */
    private Path sixFrames(String name) throws Exception {
        List<Image> frames = new ArrayList<>();
        int[] millis = new int[6];
        for (int f = 0; f < 6; f++) {
            byte[] rgba = new byte[400];
            for (int o = 0; o < 400; o += 4) {
                rgba[o] = (byte) (f * 40);
                rgba[o + 3] = (byte) 0xFF;
            }
            frames.add(new Image(10, 10, rgba));
            millis[f] = 50;
        }
        Path file = directory.resolve(name);
        Files.write(file, Images.encode(Images.animation(frames, millis, 0), ImageFormat.GIF));
        return file;
    }

    private Image landed(CompletableFuture<Image> load) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!load.isDone()) {
            ui.runtime().drain();
            if (System.nanoTime() > deadline) {
                fail("the load never landed");
            }
        }
        return load.join();
    }

    private static int which(Image frame) {
        return (frame.pixels()[0] & 0xFF) / 40;
    }

    @Test
    void everyFrameIsAnEntryTheSharedBudgetCounts() throws Exception {
        Image image = landed(Images.loadShared(sixFrames("six.gif")));
        assertTrue(image.isAnimated());
        assertEquals(6, image.frameCount());
        for (int f = 0; f < 6; f++) {
            assertEquals(f, which(image.frame(f)));
        }
        Image third = image.frame(3);
        assertSame(third, image.frame(3), "a frame is the same object for as long as it is kept");
        assertSame(image, landed(Images.loadShared(directory.resolve("six.gif"))), "and the picture is one picture");
    }

    @Test
    void aFrameTheBudgetLetGoIsDecodedAgainFromTheFile() throws Exception {
        Images.setSharedBudget(900); // the picture's own pixels and one frame more
        Image image = landed(Images.loadShared(sixFrames("tight.gif")));
        for (int turn = 0; turn < 3; turn++) {
            for (int f = 0; f < 6; f++) {
                Image frame = image.frame(f);
                assertEquals(f, which(frame), "turn " + turn + ", frame " + f);
                byte[] expected = new byte[400];
                for (int o = 0; o < 400; o += 4) {
                    expected[o] = (byte) (f * 40);
                    expected[o + 3] = (byte) 0xFF;
                }
                assertArrayEquals(expected, frame.pixels());
            }
        }
    }

    @Test
    void aPinnedPictureKeepsEveryFrameWhateverTheBudget() throws Exception {
        Images.setSharedBudget(0);
        Path file = sixFrames("pinned.gif");
        Image image = landed(Images.loadPinned(file));
        List<Image> first = new ArrayList<>();
        for (int f = 0; f < 6; f++) {
            first.add(image.frame(f));
        }
        System.gc();
        for (int f = 0; f < 6; f++) {
            assertSame(first.get(f), image.frame(f), "kept, not decoded again: frame " + f);
        }
        Images.unpin(file);
        for (int f = 0; f < 6; f++) {
            assertEquals(f, which(image.frame(f)), "unpinned, still there to ask for");
        }
    }
}
