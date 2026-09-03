package limn.demo.site;

import limn.graphics.Image;
import limn.graphics.ImageFormat;
import limn.graphics.Images;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture's off-thread writer, with no window and no GL context: what it promises is that
 * every image handed to it is on disk by the time {@code join} returns, byte for byte what the
 * synchronous save would have written, that a failed write fails the run, and that the queue
 * cannot grow past its ceiling.
 */
class GalleryFrameWriterTest {

    @TempDir
    Path dir;

    @Test
    void everyFrameIsOnDiskAfterJoinWithTheSynchronousBytes() throws IOException {
        Gallery.FrameWriter writer = new Gallery.FrameWriter(2);
        List<Image> images = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Image image = gradient(40 + i, 30);
            images.add(image);
            // Deliberately more frames than the two threads' four slots: the caller blocks on
            // the ceiling and every frame still arrives.
            writer.write(image, dir.resolve("nested").resolve("frame-" + i + ".png"));
        }
        writer.join();

        for (int i = 0; i < 12; i++) {
            Path file = dir.resolve("nested").resolve("frame-" + i + ".png");
            assertTrue(Files.exists(file), file + " was written");
            assertArrayEquals(Images.encode(images.get(i), ImageFormat.PNG), Files.readAllBytes(file),
                    "the same encoder on another thread writes the same bytes; the site hashes them");
        }
    }

    @Test
    void aFailedWriteFailsTheJoinAndNamesTheCause() {
        Gallery.FrameWriter writer = new Gallery.FrameWriter(1, 2, (image, file) -> {
            throw new IllegalStateException("disk full at " + file.getFileName());
        });
        writer.write(gradient(4, 4), dir.resolve("lost.png"));

        IOException failure = assertThrows(IOException.class, writer::join);
        assertTrue(failure.getMessage().contains("lost.png"), failure.getMessage());
        assertTrue(failure.getCause() instanceof IllegalStateException, "the cause is attached");
    }

    @Test
    void writeBlocksOnceEveryInFlightSlotIsTaken() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Gallery.FrameWriter writer = new Gallery.FrameWriter(1, 2, (image, file) -> {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        Image image = gradient(4, 4);
        writer.write(image, dir.resolve("a.png"));
        writer.write(image, dir.resolve("b.png"));

        AtomicBoolean third = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            writer.write(image, dir.resolve("c.png"));
            third.set(true);
            done.countDown();
        });
        caller.start();
        assertFalse(done.await(200, TimeUnit.MILLISECONDS),
                "a third frame waits: two slots, both held by a save that has not finished");
        assertFalse(third.get());

        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "released, the waiting write goes through");
        writer.join();
    }

    @Test
    void nothingIsAcceptedAfterJoin() throws IOException {
        Gallery.FrameWriter writer = new Gallery.FrameWriter(1);
        writer.join();
        assertThrows(IllegalStateException.class,
                () -> writer.write(gradient(2, 2), dir.resolve("late.png")),
                "a write nobody will wait for is a capture that may never exist");
        assertEquals(0, dir.toFile().list().length, "and nothing was written");
    }

    private static Image gradient(int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                rgba[i] = (byte) (x * 255 / Math.max(1, width - 1));
                rgba[i + 1] = (byte) (y * 255 / Math.max(1, height - 1));
                rgba[i + 2] = (byte) ((x + y) & 0xFF);
                rgba[i + 3] = (byte) 0xFF;
            }
        }
        return new Image(width, height, rgba);
    }
}
