package limn.demo.site;

import limn.graphics.Image;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code Gallery.main} does around the event loop, with no window and no GL context: the
 * writer is drained and its pool shut down whichever way the loop ends.
 *
 * <p>The loop can end by throwing. {@code LwjglBackend.runEventLoop} contains a frame that threw
 * by logging it and handing the same window another frame, and gives up after
 * {@code CRASH_STREAK_LIMIT} = 100 consecutive crashed iterations by throwing
 * {@code IllegalStateException} out of the loop -- the one path an Error from a frame callback
 * actually takes, and far short of the driver's own frame ceiling. That throw used to unwind
 * past {@code writer.join()}, which was not in a finally; the writer's threads are deliberately
 * not daemons and {@code join} is what shuts their pool down, so the main thread died and the
 * JVM sat there holding live threads with no work. The capture task hung forever.
 */
class GalleryDrainTest {

    @TempDir
    Path dir;

    /** One pixel, so a queued write has something to carry. */
    private static Image pixel() {
        return new Image(1, 1, new byte[] {0, 0, 0, -1});
    }

    @Test
    void aLoopThatThrowsStillDrainsTheWriterAndShutsItsPoolDown() {
        List<Path> written = new CopyOnWriteArrayList<>();
        AtomicReference<Thread> writerThread = new AtomicReference<>();
        Gallery.FrameWriter writer = new Gallery.FrameWriter(1, 2, (image, file) -> {
            writerThread.set(Thread.currentThread());
            written.add(file);
        });
        IllegalStateException crashed =
                new IllegalStateException("event loop crashed 100 consecutive iterations");

        Throwable ended = Gallery.runAndDrain(() -> {
            writer.write(pixel(), dir.resolve("queued@2x.png"));
            throw crashed;
        }, writer);

        assertSame(crashed, ended, "the loop's throw is what the caller is handed, to print"
                + " and to exit 1 on");
        assertEquals(List.of(dir.resolve("queued@2x.png")), new ArrayList<>(written),
                "and the write queued before it still ran");
        assertThrows(IllegalStateException.class,
                () -> writer.write(pixel(), dir.resolve("late@2x.png")),
                "the pool is shut down: a write after the drain is refused, not silently lost");
        Thread thread = writerThread.get();
        // The pool is terminated by the time join returns; the worker's own thread finishes
        // dying a moment later, so this is waited for rather than read once.
        assertFalse(stillAlive(thread),
                "and no non-daemon writer thread is left alive to keep the JVM up: " + thread);
    }

    /** Whether {@code thread} is still alive a generous moment after its pool terminated. */
    private static boolean stillAlive(Thread thread) {
        if (thread == null) {
            return false;
        }
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (thread.isAlive() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return thread.isAlive();
    }

    @Test
    void aCleanLoopDrainsAndReportsNothing() {
        List<Path> written = new CopyOnWriteArrayList<>();
        Gallery.FrameWriter writer =
                new Gallery.FrameWriter(1, 2, (image, file) -> written.add(file));

        Throwable ended = Gallery.runAndDrain(
                () -> writer.write(pixel(), dir.resolve("shot@2x.png")), writer);

        assertNull(ended, "a loop that returned is not a failure");
        assertEquals(List.of(dir.resolve("shot@2x.png")), new ArrayList<>(written),
                "and its captures are on disk before anything downstream is published");
    }

    /**
     * A write that failed is what the task fails over, and that does not change when the loop
     * was clean: the drain's own failure is then the one the caller reports.
     */
    @Test
    void aFailedWriteAfterACleanLoopIsWhatIsReported() {
        Gallery.FrameWriter writer = new Gallery.FrameWriter(1, 2, (image, file) -> {
            throw new IllegalStateException("disk full at " + file.getFileName());
        });

        Throwable ended = Gallery.runAndDrain(
                () -> writer.write(pixel(), dir.resolve("shot@2x.png")), writer);

        assertTrue(ended instanceof IOException && String.valueOf(ended.getMessage())
                        .contains("disk full at shot@2x.png"),
                "the drain's failure names the capture that was not written: " + ended);
    }

    /** Neither failure may hide the other: the loop's is the cause, the drain's rides with it. */
    @Test
    void aFailedWriteUnderAThrowingLoopRidesOnItAsSuppressed() {
        Gallery.FrameWriter writer = new Gallery.FrameWriter(1, 2, (image, file) -> {
            throw new IllegalStateException("disk full at " + file.getFileName());
        });
        IllegalStateException crashed =
                new IllegalStateException("event loop crashed 100 consecutive iterations");

        Throwable ended = Gallery.runAndDrain(() -> {
            writer.write(pixel(), dir.resolve("shot@2x.png"));
            throw crashed;
        }, writer);

        assertSame(crashed, ended, "the loop's throw is still the reported cause");
        assertEquals(1, ended.getSuppressed().length,
                "and the drain's failure is attached rather than dropped: "
                        + List.of(ended.getSuppressed()));
        assertTrue(ended.getSuppressed()[0] instanceof IOException,
                "which is the join's: " + ended.getSuppressed()[0]);
    }
}
