package limn.video.ffmpeg;

import limn.video.VideoStreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One demultiplexer, two consumers, two threads, two rates, with neither starving the other.
 *
 * <p>This is the arrangement a real player produces and nothing simpler reproduces: the pictures
 * are pulled by the player's decode thread and the soundtrack by the audio engine's streaming
 * thread, and those threads are not the same one and do not run at the same rate. Whichever asks
 * first runs the demultiplexer and queues what it meets for the other, so neither waits for the
 * other to be pulled.
 */
class TwoTracksTest {

    @TempDir
    Path directory;

    @Test
    void bothTracksRunAtOnceFromDifferentThreads() throws Exception {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, 160, 120, 120, 2);

        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            AtomicInteger pictures = new AtomicInteger();
            AtomicInteger soundFrames = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(2);

            Thread decode = new Thread(() -> {
                try {
                    VideoStreamSource video = media.video();
                    while (RoundTripTest.readNext(video) == VideoStreamSource.Read.FRAME) {
                        video.frame().release();
                        pictures.incrementAndGet();
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    done.countDown();
                }
            }, "decode");

            Thread stream = new Thread(() -> {
                try {
                    short[] out = new short[512 * 2];
                    int read;
                    while ((read = media.audio().readFrames(out, 512)) > 0) {
                        soundFrames.addAndGet(read);
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    done.countDown();
                }
            }, "stream");

            decode.start();
            stream.start();
            assertTrue(done.await(60, TimeUnit.SECONDS), "both tracks finished");
            assertNull(failure.get(), () -> "a track failed: " + failure.get());

            assertEquals(120, pictures.get(), "every picture arrived");
            assertTrue(soundFrames.get() > 150_000,
                    "about four seconds of sound arrived, got " + soundFrames.get());

            // Nothing was dropped: the bound on a queue is far above any interleaving a muxer
            // produces, so reaching it means a consumer stopped, and here neither did.
            long[] dropped = media.droppedPackets();
            assertEquals(0L, dropped[0], "no video packets dropped");
            assertEquals(0L, dropped[1], "no audio packets dropped");
        }
    }

    /**
     * The pictures run to the end while nobody reads the soundtrack at all. The audio track's
     * queue is what absorbs that, and it is bounded, so what this asserts is that the video side
     * never blocks on it and never fails, whatever the queue is doing.
     */
    @Test
    void anUnreadSoundtrackDoesNotStopThePictures() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, 160, 120, 120, 2);

        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            VideoStreamSource video = media.video();
            int pictures = 0;
            while (RoundTripTest.readNext(video) == VideoStreamSource.Read.FRAME) {
                video.frame().release();
                pictures++;
            }
            assertEquals(120, pictures);
            assertEquals(0L, media.droppedPackets()[0], "the pictures were never dropped");
        }
    }

    /**
     * And with the track released, its packets stop being queued at all, which is what keeps a
     * container whose soundtrack the audio engine refused (a channel count it will not mix, a full
     * admission queue) from accumulating packets nobody will ever read.
     */
    @Test
    void aReleasedSoundtrackIsNotBufferedAtAll() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, 160, 120, 120, 2);

        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            media.audio().close();
            VideoStreamSource video = media.video();
            int pictures = 0;
            while (RoundTripTest.readNext(video) == VideoStreamSource.Read.FRAME) {
                video.frame().release();
                pictures++;
            }
            assertEquals(120, pictures);
            long[] dropped = media.droppedPackets();
            assertEquals(0L, dropped[0]);
            assertEquals(0L, dropped[1],
                    "an unclaimed track's packets are freed as they are met, never queued and "
                            + "then dropped");
        }
    }

    /**
     * A picture may be released on a thread other than the one it was delivered to: the SPI says
     * so, and a player hands pictures to the user interface thread, which is where they die. That
     * makes the recycler concurrent with the decoder by construction.
     */
    @Test
    void picturesMayBeReleasedFromAnotherThread() throws Exception {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, 160, 120, 60, 0);

        try (FfmpegMedia media = FfmpegMedia.open(clip, false, 4)) {
            VideoStreamSource video = media.video();
            java.util.concurrent.BlockingQueue<limn.video.VideoFrame> handed =
                    new java.util.concurrent.LinkedBlockingQueue<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicInteger released = new AtomicInteger();

            Thread consumer = new Thread(() -> {
                try {
                    for (int i = 0; i < 60; i++) {
                        limn.video.VideoFrame frame = handed.poll(30, TimeUnit.SECONDS);
                        if (frame == null) {
                            return;
                        }
                        frame.release();
                        released.incrementAndGet();
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            }, "consumer");
            consumer.start();

            // PENDING here is not a hiccup, it is the expected steady state: four slots are in
            // flight with the other thread and the decoder has nowhere to put a fifth picture
            // until one comes back. So this waits for the consumer rather than spinning: the
            // whole point of PENDING is that the answer is "release one and ask again".
            int decoded = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (decoded < 60 && System.nanoTime() < deadline) {
                VideoStreamSource.Read read = video.readFrame();
                if (read == VideoStreamSource.Read.END) {
                    break;
                }
                if (read == VideoStreamSource.Read.PENDING) {
                    Thread.onSpinWait();
                    continue;
                }
                handed.put(video.frame());
                decoded++;
            }
            consumer.join(60_000);
            assertNull(failure.get(), () -> "the consumer failed: " + failure.get());
            assertEquals(60, decoded);
            assertEquals(60, released.get(), "every picture was released, on the other thread");
        }
    }
}
