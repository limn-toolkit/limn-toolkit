package limn.video;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.sound.AudioClip;
import limn.sound.AudioEngine;
import limn.sound.AudioStreamSource;
import limn.sound.PlayOptions;
import limn.sound.Playback;
import limn.sound.Sounds;
import limn.video.VideoStreamSource.SeekMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Seeking the player: what happens to the pictures it is holding, to the stream it is not allowed
 * to touch from the asking thread, to the soundtrack, and to a clock that would otherwise read the
 * move as a master running away.
 *
 * <p>Nothing sleeps. The deterministic cases own no decode thread and turn {@code decodeStep()} by
 * hand; the one case that is genuinely about two threads pins the interleaving with latches.
 */
class MediaPlayerSeekTest {

    private static final long FRAME_MICROS = 33_333; // 30 per second
    private static final long AWAIT_NANOS = 10_000_000_000L;

    private ExecutorService workers;
    private UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        Sounds.uninstallEngine(null);
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    // ------------------------------------------------------------------ the pictures it is holding

    @Test
    void aSeekReleasesEveryRingPictureExactlyOnce() {
        PooledTestStream stream = new PooledTestStream(16, 16, 6);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 4)) {
            player.start();
            pump(player, 8);
            assertEquals(4, player.bufferedPictures(), "the ring is full before the seek");
            assertEquals(2, stream.freeSlots(), "four of six slots are out on loan");

            // The recycler in PooledTestStream throws on a second release of one slot, so a double
            // release is a failure here and not a silent one; the free count catches the other
            // half, a picture that is never handed back at all.
            player.seek(2_000_000, SeekMode.EXACT);

            assertEquals(0, player.bufferedPictures());
            assertEquals(6, stream.freeSlots(),
                    "every borrowed picture went back, exactly once, before the stream moved");
        }
    }

    @Test
    void aSeekThatStarvedThePoolWouldStopTheStream() {
        PooledTestStream stream = new PooledTestStream(16, 16, 4);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            pump(player, 6);
            player.seek(1_000_000, SeekMode.EXACT);
            pump(player, 6); // one step performs the seek, the rest decode

            assertTrue(player.bufferedPictures() > 0,
                    "a pool with every slot still held answers PENDING forever, so the proof that "
                            + "the ring really was released is that decoding resumes at all");
        }
    }

    // ------------------------------------------------------------------ what reaches the stream

    @Test
    void theStreamIsRepositionedByTheDecodeThreadAndNotByTheCaller() {
        PooledTestStream stream = new PooledTestStream(16, 16, 4);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 2)) {
            player.start();
            pump(player, 4);

            player.seek(5_000_000, SeekMode.KEYFRAME);
            assertEquals(0, stream.seeks.get(),
                    "the asking thread must not touch a stream the decode thread reads");

            player.decodeStep();
            assertEquals(1, stream.seeks.get());
            assertEquals(5_000_000L, stream.seekTargets.get(0));
        }
    }

    @Test
    void theModeReachesTheStream() {
        PooledTestStream stream = new PooledTestStream(16, 16, 4);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 2)) {
            player.start();
            // 30 per second, so a target of one and a half pictures separates the two modes.
            player.seek(FRAME_MICROS + FRAME_MICROS / 2, SeekMode.KEYFRAME);
            player.decodeStep();
            pump(player, 2);
            VideoFrame keyframed = player.takePicture();
            assertNotNull(keyframed);
            assertEquals(FRAME_MICROS, keyframed.ptsMicros(), "KEYFRAME lands at or before");
            keyframed.release();
        }

        PooledTestStream exact = new PooledTestStream(16, 16, 4);
        Hand later = new Hand();
        try (MediaPlayer player = manual(exact, later, 2)) {
            player.start();
            player.seek(FRAME_MICROS + FRAME_MICROS / 2, SeekMode.EXACT);
            player.decodeStep();
            pump(player, 2);
            VideoFrame landed = player.takePicture();
            assertNotNull(landed);
            assertEquals(2 * FRAME_MICROS, landed.ptsMicros(), "EXACT lands at or after");
            landed.release();
        }
    }

    @Test
    void aStreamThatRefusesIsAskedBeforeItIsDriven() {
        PooledTestStream stream = new PooledTestStream(16, 16, 4);
        stream.seekable = false;
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 2)) {
            player.start();
            assertFalse(player.canSeek());
            assertThrows(UnsupportedOperationException.class, () -> player.seek(1_000_000));
            assertEquals(0, stream.seeks.get(), "nothing reached the stream");

            // And the player is unharmed: the refusal is not a state change.
            pump(player, 3);
            assertSame(MediaPlayer.State.PLAYING, player.state());
        }
    }

    @Test
    void aNegativeTargetIsRefused() {
        PooledTestStream stream = new PooledTestStream(16, 16, 4);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 2)) {
            player.start();
            assertThrows(IllegalArgumentException.class, () -> player.seek(-1));
        }
    }

    // ------------------------------------------------------------------ the ends

    @Test
    void seekingPastTheEndEndsThePlayer() {
        PooledTestStream stream = new PooledTestStream(16, 16, 4);
        stream.frameCount = 10;
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 2)) {
            player.start();
            player.seek(60_000_000, SeekMode.EXACT); // far past ten pictures
            pump(player, 6);
            assertNull(player.takePicture());
            assertTrue(player.isEnded(), "the position is the end, and the end is a state");
        }
    }

    @Test
    void seekingBackwardsOutOfTheEndPlaysAgain() {
        PooledTestStream stream = new PooledTestStream(16, 16, 4);
        stream.frameCount = 4;
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 2)) {
            player.start();
            for (int i = 0; i < 12; i++) {
                player.decodeStep();
                VideoFrame frame = player.takePicture();
                if (frame != null) {
                    frame.release();
                }
                hand.nanos += FRAME_MICROS * 1_000L;
            }
            assertTrue(player.isEnded());

            player.seek(0, SeekMode.EXACT);
            assertSame(MediaPlayer.State.PLAYING, player.state(),
                    "seeking out of the end is how a viewer replays a part");
            pump(player, 4);
            VideoFrame again = player.takePicture();
            assertNotNull(again, "the stream produces pictures again");
            again.release();
        }
    }

    @Test
    void aDecodeThreadThatEndedIsStillThereToSeekWith() {
        // The decode loop parks at the end rather than exiting, so nothing has to restart it, and
        // a restart from another thread would be a second chance at two threads on one stream.
        PooledTestStream stream = new PooledTestStream(16, 16, 4);
        stream.frameCount = 2;
        MediaPlayer player = new MediaPlayer(stream).setRingCapacity(2);
        try {
            player.start();
            await(() -> {
                VideoFrame frame = player.takePicture();
                if (frame != null) {
                    frame.release();
                }
                return player.isEnded();
            }, "the stream to end");

            stream.frameCount = 20;
            player.seek(0, SeekMode.EXACT);
            await(() -> player.bufferedPictures() > 0,
                    "the parked decode thread to notice the seek and decode again");
        } finally {
            player.close();
        }
    }

    // ------------------------------------------------------------------ pausing

    @Test
    void aSeekWhilePausedHandsOverExactlyOnePicture() {
        PooledTestStream stream = new PooledTestStream(16, 16, 6);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            pump(player, 4);
            VideoFrame first = player.takePicture();
            assertNotNull(first);
            first.release();
            player.pause();
            assertNull(player.takePicture(), "a pause holds");

            player.seek(3_000_000, SeekMode.EXACT);
            pump(player, 4);
            VideoFrame landed = player.takePicture();
            assertNotNull(landed, "a viewer scrubbing a paused video is asking to see where it is");
            landed.release();

            assertNull(player.takePicture(), "and then it holds again");
            assertSame(MediaPlayer.State.PAUSED, player.state(), "the pause was not lifted");
        }
    }

    // ------------------------------------------------------------------ the timeline

    @Test
    void positionIsTheTargetBetweenTheRequestAndTheFirstPicture() {
        PooledTestStream stream = new PooledTestStream(16, 16, 4);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 2)) {
            player.start();
            pump(player, 3);
            VideoFrame shown = player.takePicture();
            assertNotNull(shown);
            shown.release();

            player.seek(7_000_000, SeekMode.EXACT);
            assertEquals(7_000_000, player.positionMicros());
            hand.nanos += 500_000_000L; // half a second of decoding
            assertEquals(7_000_000, player.positionMicros(),
                    "a transport reading this shows where it was told to go, not where the "
                            + "buffering has got to");
        }
    }

    @Test
    void aSeekIsNotALoopAndKeepsTheMaster() {
        PooledTestStream stream = new PooledTestStream(16, 16, 6);
        SteadyEngine engine = new SteadyEngine();
        Sounds.installEngine(engine);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.setAudio(new SilentSource(), PlayOptions.DEFAULTS);
            player.start();
            engine.handle.seconds = 0.05;
            pump(player, 4);
            VideoFrame first = player.takePicture();
            assertNotNull(first);
            first.release();
            assertTrue(player.isFollowingAudio());

            // A seek moves the sound too (the handle is told, and reports the new position at
            // once), so the pictures' timeline and the track's are still the same one.
            player.seek(20_000_000, SeekMode.EXACT);
            assertEquals(20_000_000, engine.handle.seekedToMicros);
            engine.handle.seconds = 20.0;
            pump(player, 6);
            hand.nanos += FRAME_MICROS * 1_000L;
            VideoFrame after = player.takePicture();
            if (after != null) {
                after.release();
            }

            assertTrue(player.isFollowingAudio(),
                    "a seek the clock was told about must not read as a master that ran away");
        }
    }

    @Test
    void aTrackThatCannotFollowIsDroppedRatherThanChased() {
        PooledTestStream stream = new PooledTestStream(16, 16, 6);
        SteadyEngine engine = new SteadyEngine();
        engine.handle.seekable = false; // an engine, or a track, that cannot be repositioned
        Sounds.installEngine(engine);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.setAudio(new SilentSource(), PlayOptions.DEFAULTS);
            player.start();
            engine.handle.seconds = 0.05;
            pump(player, 4);
            VideoFrame first = player.takePicture();
            assertNotNull(first);
            first.release();
            assertTrue(player.isFollowingAudio());
            assertFalse(player.canSeekAudio());

            player.seek(20_000_000, SeekMode.EXACT);
            assertFalse(player.isFollowingAudio(),
                    "a soundtrack left where it was is on a timeline the pictures have left, and "
                            + "following it would hold every picture until it caught up");
        }
    }

    // ------------------------------------------------------------------ the concurrent case

    @Test
    void aSeekArrivingDuringABlockingReadDoesNotShowThePictureThatReadProduced() throws Exception {
        PooledTestStream stream = new PooledTestStream(16, 16, 6);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stream.entered = entered;
        stream.release = release;

        MediaPlayer player = new MediaPlayer(stream).setRingCapacity(3);
        try {
            player.start();
            assertTrue(entered.await(AWAIT_NANOS, java.util.concurrent.TimeUnit.NANOSECONDS),
                    "the decode thread to be inside a read");

            // The seek lands while the decode thread is blocked in readFrame. The picture that
            // read is about to produce belongs to the position being left.
            stream.entered = null;
            player.seek(10_000_000, SeekMode.EXACT);
            release.countDown();
            stream.release = null;

            await(() -> stream.seeks.get() == 1, "the decode thread to perform the seek");
            await(() -> player.bufferedPictures() > 0, "pictures from the new position");

            VideoFrame shown = player.takePicture();
            assertNotNull(shown);
            assertTrue(shown.ptsMicros() >= 10_000_000,
                    "the in-flight picture was released rather than enqueued, so what is shown is "
                            + "from where the viewer went and not from where they were");
            shown.release();
        } finally {
            player.close();
        }
        assertEquals(stream.slots(), stream.freeSlots(),
                "and the in-flight picture went back exactly once");
    }

    // ------------------------------------------------------------------ steady state

    @Test
    void aSteadyDecodeAfterASeekAllocatesNothing() {
        assumeTrue(AllocationProbe.isSupported(), "this virtual machine does not count thread allocation");
        PooledTestStream stream = new PooledTestStream(32, 32, 6);
        stream.frameCount = Integer.MAX_VALUE;
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            pump(player, 8);
            player.seek(4_000_000, SeekMode.EXACT);
            pump(player, 8);
            Runnable passes = () -> {
                for (int i = 0; i < 200; i++) {
                    player.decodeStep();
                    VideoFrame frame = player.takePicture();
                    if (frame != null) {
                        frame.release();
                    }
                    hand.nanos += FRAME_MICROS * 1_000L;
                }
            };
            // Warmed up past the seek by leastAllocatedBy's own first pass: measuring across a
            // seek would measure the seek, which allocates once and is not the steady state.
            long allocated = AllocationProbe.leastAllocatedBy(passes, 3);
            assertEquals(0, allocated,
                    "decoding, timing and handing over pictures after a seek allocates nothing");
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** Wall clock a test turns by hand. */
    private static final class Hand {
        long nanos;

        long get() {
            return nanos;
        }
    }

    private static MediaPlayer manual(PooledTestStream stream, Hand hand, int ring) {
        return new MediaPlayer(stream)
                .setOwnsDecodeThread(false)
                .setRingCapacity(ring)
                .setClock(new VideoClock(hand::get));
    }

    private static void pump(MediaPlayer player, int times) {
        for (int i = 0; i < times; i++) {
            player.decodeStep();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + AWAIT_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.onSpinWait();
        }
    }

    private static final class SilentSource implements AudioStreamSource {
        @Override
        public int channels() {
            return 2;
        }

        @Override
        public int sampleRate() {
            return 44_100;
        }

        @Override
        public int readFrames(short[] out, int maxFrames) {
            return maxFrames;
        }

        @Override
        public void reset() {
        }

        @Override
        public void close() {
        }
    }

    /** A playback a test reads back: where it was seeked to, and whether it admits to seeking. */
    private static final class HandPlayback implements Playback {
        double seconds;
        boolean playing = true;
        boolean seekable = true;
        long seekedToMicros = -1;

        @Override
        public void stop() {
            playing = false;
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public void setGain(float gain) {
        }

        @Override
        public double positionSeconds() {
            return seconds;
        }

        @Override
        public boolean canSeek() {
            return seekable;
        }

        @Override
        public void seek(long micros) {
            if (seekable) {
                seekedToMicros = micros;
            }
        }
    }

    private static final class SteadyEngine implements AudioEngine {
        final HandPlayback handle = new HandPlayback();

        @Override
        public Playback play(AudioClip clip, float gain, boolean loop) {
            return Playback.NONE;
        }

        @Override
        public Playback playStream(AudioStreamSource source, PlayOptions options) {
            return handle;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
