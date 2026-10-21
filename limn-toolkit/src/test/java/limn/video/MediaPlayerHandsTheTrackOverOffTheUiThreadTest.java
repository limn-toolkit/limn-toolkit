package limn.video;

import limn.sound.AudioClip;
import limn.sound.AudioEngine;
import limn.sound.AudioStreamSource;
import limn.sound.PlayOptions;
import limn.sound.Playback;
import limn.sound.Sounds;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pressing play hands the audio track to the engine on a worker: the engine may open the audio
 * device to take it, which held the UI thread 190 ms on a Mac and 458 ms in a Showcase session on
 * the first play of a clip. The player reads {@code PLAYING} at once (a transport's button shows
 * Pause and says so), the pictures wait on their first one until the track sounds so the two begin
 * together, and a pause, a seek, a stop or a close made while the track is on its way reaches it as
 * it arrives.
 */
class MediaPlayerHandsTheTrackOverOffTheUiThreadTest {

    private static final long FRAME_MICROS = 33_333;

    private HeadlessUi ui;
    private SlowEngine engine;

    @BeforeEach
    void install() {
        ui = new HeadlessUi();
        engine = new SlowEngine();
        Sounds.installEngine(engine);
    }

    @AfterEach
    void uninstall() {
        engine.open.countDown();
        Sounds.uninstallEngine(engine);
        ui.close();
    }

    /** A wall clock the test turns by hand. */
    private static final class Hand {
        long nanos;

        long get() {
            return nanos;
        }
    }

    private MediaPlayer player(Hand hand) {
        return new MediaPlayer(new PooledTestStream(64, 32, 8))
                .setOwnsDecodeThread(false)
                .setRingCapacity(3)
                .setClock(new VideoClock(hand::get));
    }

    /** Runs the UI thread's queue until {@code done}, for ten seconds at most, and returns either way. */
    private void pumpFor(java.util.function.BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            ui.runtime().drain();
            Thread.onSpinWait();
        }
    }

    private void arrived(MediaPlayer player) {
        engine.open.countDown();
        ui.pumpUntil(() -> !player.isHandingAudioOver(), 10_000_000_000L);
    }

    @Test
    void playReturnsAtOnceWhileTheEngineOpensTheDeviceAndThePicturesWaitForTheSound() throws Exception {
        Hand hand = new Hand();
        MediaPlayer player = player(hand);
        try {
            player.setAudio(new SilentSource(), PlayOptions.DEFAULTS);
            long before = System.nanoTime();
            player.start();
            long took = System.nanoTime() - before;

            assertTrue(engine.entered.await(10, TimeUnit.SECONDS), "the engine was asked");
            assertNotSame(Thread.currentThread(), engine.calledOn, "and not on the UI thread");
            assertTrue(took < TimeUnit.MILLISECONDS.toNanos(150), "start() waited for the device: " + took / 1_000_000 + " ms");
            assertEquals(MediaPlayer.State.PLAYING, player.state(), "playing from the press: the button shows Pause");
            assertTrue(player.hasAudio(), "the track is the player's still, on its way");
            assertSame(Playback.NONE, player.audio(), "not sounding yet");

            for (int i = 0; i < 3; i++) {
                player.decodeStep();
            }
            VideoFrame first = player.takePicture();
            assertEquals(0, first.ptsMicros(), "the first picture is shown");
            first.release();
            hand.nanos += 10 * FRAME_MICROS * 1_000L;
            player.decodeStep();
            assertEquals(null, player.takePicture(), "and held there while the sound is on its way");

            arrived(player);
            assertSame(engine.handle, player.audio(), "the track sounds");
            assertTrue(player.isFollowingAudio(), "and times the pictures");
            engine.handle.seconds = FRAME_MICROS / 1_000_000.0;
            VideoFrame second = player.takePicture();
            assertEquals(FRAME_MICROS, second.ptsMicros(), "picture and sound go on together");
            second.release();
        } finally {
            player.close();
        }
    }

    @Test
    void aPauseAndASeekMadeWhileTheTrackIsOnItsWayReachItAsItArrives() throws Exception {
        Hand hand = new Hand();
        MediaPlayer player = player(hand);
        try {
            player.setAudio(new SilentSource(), PlayOptions.DEFAULTS);
            player.start();
            assertTrue(engine.entered.await(10, TimeUnit.SECONDS));
            player.seek(10 * FRAME_MICROS);
            player.pause();

            arrived(player);

            assertEquals(List.of("seek " + 10 * FRAME_MICROS, "pause"), engine.handle.calls,
                    "the track joins the pictures where they are, and paused");
            assertEquals(MediaPlayer.State.PAUSED, player.state());
            player.resume();
            assertEquals(List.of("seek " + 10 * FRAME_MICROS, "pause", "resume"), engine.handle.calls);
        } finally {
            player.close();
        }
    }

    @Test
    void aCloseWhileTheTrackIsOnItsWayStopsItWhenItArrives() throws Exception {
        Hand hand = new Hand();
        MediaPlayer player = player(hand);
        player.setAudio(new SilentSource(), PlayOptions.DEFAULTS);
        player.start();
        assertTrue(engine.entered.await(10, TimeUnit.SECONDS));

        player.close();
        engine.open.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!engine.handle.calls.contains("stop") && System.nanoTime() < deadline) {
            ui.runtime().drain();
            Thread.onSpinWait();
        }

        assertTrue(engine.handle.calls.contains("stop"), "the engine was told to stop the track it took");
        assertSame(Playback.NONE, player.audio());
    }

    /**
     * A {@code start()} followed in the same turn by a {@code close()}, a {@code stop()} or a
     * {@code restart()}: an autoplay a page's attach starts and the same turn's page switch closes,
     * or {@code player.start(); if (!allowed) player.stop();}. The close usually beats the hand-over
     * to the track, and a hand-over that was withdrawn before it took the track left it closed by
     * nobody (1992 of 2000 in a review run), so a container kept queueing that track's packets for
     * as long as it stayed open. Exactly one side owns the track, and it is closed exactly once.
     */
    @Test
    void aCloseAStopOrARestartInTheTurnOfTheStartClosesTheTrackExactlyOnce() {
        ClosingEngine closing = new ClosingEngine();
        Sounds.installEngine(closing);
        try {
            int n = 2000;
            List<CountingSource> tracks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                MediaPlayer player = player(new Hand());
                CountingSource track = new CountingSource();
                tracks.add(track);
                player.setAudio(track, PlayOptions.DEFAULTS);
                player.start();
                switch (i % 3) {
                    case 0 -> player.close();
                    case 1 -> {
                        player.stop();
                        player.close();
                    }
                    default -> {
                        player.restart(); // silent afterwards: the track went with the stop
                        player.close();
                    }
                }
            }
            pumpFor(() -> tracks.stream().allMatch(track -> track.closes.get() > 0));
            assertEquals(0, tracks.stream().filter(track -> track.closes.get() == 0).count(),
                    "tracks closed by nobody");
            for (int i = 0; i < n; i++) {
                assertEquals(1, tracks.get(i).closes.get(), "track " + i + " closed exactly once");
            }
            assertEquals(closing.taken.get(), closing.stopped.get(),
                    "every track the engine took was stopped");
        } finally {
            Sounds.uninstallEngine(closing);
        }
    }

    /**
     * The same close with every worker of the application busy, so the hand-over cannot have taken
     * the track yet: it was closed by nobody, and the engine never saw it.
     */
    @Test
    void aCloseWhileTheWorkersAreBusyClosesTheTrack() throws Exception {
        ClosingEngine closing = new ClosingEngine();
        Sounds.installEngine(closing);
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch holding = new CountDownLatch(1);
        limn.concurrent.Ui.<Void>work(progress -> {
            holding.countDown();
            busy.await(); // a slow server call of the application's
            return null;
        }).start();
        try {
            assertTrue(holding.await(10, TimeUnit.SECONDS));
            MediaPlayer player = player(new Hand());
            CountingSource track = new CountingSource();
            player.setAudio(track, PlayOptions.DEFAULTS);
            player.start();
            player.close();
            pumpFor(() -> track.closes.get() > 0);
            assertEquals(1, track.closes.get(), "closed while the workers are still busy");
            busy.countDown();
            Thread.sleep(100);
            ui.runtime().drain();
            assertEquals(1, track.closes.get(), "closed exactly once");
            assertEquals(closing.taken.get(), closing.stopped.get());
        } finally {
            busy.countDown();
            Sounds.uninstallEngine(closing);
        }
    }

    /** An audio source that counts its closes. */
    private static final class CountingSource implements AudioStreamSource {
        final java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();

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
            closes.incrementAndGet();
        }
    }

    /** An engine that takes a stream at once and closes it when told to stop, as the real one does. */
    private static final class ClosingEngine implements AudioEngine {
        final java.util.concurrent.atomic.AtomicInteger taken = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger stopped = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Playback play(AudioClip clip, float gain, boolean loop) {
            return Playback.NONE;
        }

        @Override
        public Playback playStream(AudioStreamSource source, PlayOptions options) {
            taken.incrementAndGet();
            return new Playback() {
                private boolean done;

                @Override
                public synchronized void stop() {
                    if (!done) {
                        done = true;
                        stopped.incrementAndGet();
                        source.close();
                    }
                }

                @Override
                public boolean isPlaying() {
                    return true;
                }

                @Override
                public void setGain(float gain) {
                }
            };
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    /** An audio source with nothing in it. */
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

    /** A playback that writes down what it is told, and reports a position a test sets. */
    private static final class Recording implements Playback {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        volatile double seconds;

        @Override
        public void stop() {
            calls.add("stop");
        }

        @Override
        public boolean isPlaying() {
            return true;
        }

        @Override
        public void setGain(float gain) {
        }

        @Override
        public void pause() {
            calls.add("pause");
        }

        @Override
        public void resume() {
            calls.add("resume");
        }

        @Override
        public void seek(long micros) {
            calls.add("seek " + micros);
        }

        @Override
        public boolean canSeek() {
            return true;
        }

        @Override
        public double positionSeconds() {
            return seconds;
        }
    }

    /** An engine that takes a stream only once the test lets it, as one opening the device does. */
    private static final class SlowEngine implements AudioEngine {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch open = new CountDownLatch(1);
        final Recording handle = new Recording();
        volatile Thread calledOn;

        @Override
        public Playback play(AudioClip clip, float gain, boolean loop) {
            return Playback.NONE;
        }

        @Override
        public Playback playStream(AudioStreamSource source, PlayOptions options) {
            calledOn = Thread.currentThread();
            entered.countDown();
            try {
                open.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return handle;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
