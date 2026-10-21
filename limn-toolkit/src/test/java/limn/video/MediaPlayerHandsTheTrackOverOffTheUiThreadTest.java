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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pressing play hands the audio track to the engine on a thread of its own: the engine may open
 * the audio device to take it, which held the UI thread 190 ms on a Mac and 458 ms in a Showcase
 * session on the first play of a clip. The player reads {@code PLAYING} at once (a transport's
 * button shows Pause and says so), the pictures wait on their first one until the track sounds so
 * the two begin together, a quarter of a second at most, and a pause, a seek, a stop or a close
 * made while the track is on its way reaches it as it arrives.
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
            hand.nanos += 5 * FRAME_MICROS * 1_000L; // within the grace
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

            assertEquals(List.of("seek " + 10 * FRAME_MICROS, "pause", "gain 1.0"), engine.handle.calls,
                    "the track joins the pictures where they are, and paused");
            assertEquals(MediaPlayer.State.PAUSED, player.state());
            player.resume();
            assertEquals(List.of("seek " + 10 * FRAME_MICROS, "pause", "gain 1.0", "resume"), engine.handle.calls);
        } finally {
            player.close();
        }
    }

    /**
     * A pause made while the engine is opening the device for the track: the engine starts what it
     * is given before it returns, so a track started at its gain was heard for the instant between
     * the engine's start and the pause reaching it, after the viewer had pressed Pause. It starts
     * silent, takes the pause, and only then is given its gain.
     */
    @Test
    void aPauseWhileTheTrackIsOnItsWayIsAppliedBeforeTheTrackIsHeard() throws Exception {
        Hand hand = new Hand();
        MediaPlayer player = player(hand);
        try {
            player.setAudio(new SilentSource(), PlayOptions.DEFAULTS.withGain(0.6f));
            player.start();
            assertTrue(engine.entered.await(10, TimeUnit.SECONDS));
            player.pause();

            arrived(player);

            assertEquals(0f, engine.options.gain(), "the engine starts it silent");
            assertEquals(List.of("pause", "gain 0.6"), engine.handle.calls,
                    "paused first, then given the gain it was set up with");
            player.resume();
            assertEquals(List.of("pause", "gain 0.6", "resume"), engine.handle.calls);
        } finally {
            player.close();
        }
    }

    /** Arriving with nothing made meanwhile, the track is given its gain at once. */
    @Test
    void aTrackArrivingWithNothingMadeMeanwhileIsGivenItsGain() throws Exception {
        MediaPlayer player = player(new Hand());
        try {
            player.setAudio(new SilentSource(), PlayOptions.DEFAULTS.withGain(0.6f));
            player.start();
            arrived(player);
            assertEquals(0f, engine.options.gain());
            assertEquals(List.of("gain 0.6"), engine.handle.calls);
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
        assertEquals(0f, engine.options.gain(), "started silent");
        assertEquals(List.of("stop"), engine.handle.calls, "and stopped before it was given a gain: never heard");
    }

    /**
     * Holds the application's only worker (limn-test's HeadlessUi has one; a 4-core laptop has
     * three) with a job of its own, a slow server call, until the returned latch is counted down.
     *
     * @return the thread the job holds
     */
    private Thread holdTheWorkers(CountDownLatch busy) throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        Thread[] worker = new Thread[1];
        limn.concurrent.Ui.<Void>work(progress -> {
            worker[0] = Thread.currentThread();
            holding.countDown();
            busy.await();
            return null;
        }).start();
        assertTrue(holding.await(10, TimeUnit.SECONDS), "the job holds the worker");
        return worker[0];
    }

    /**
     * Play while every worker of the application is busy with its own jobs (three dashboard panels
     * still loading on a 4-core laptop): the track reached the engine only once one of those jobs
     * returned, so the button said Pause and the picture stayed on its first frame for as long as
     * the application's work lasted. The hand-over is not the application's work, and several
     * players started together each get their track.
     */
    @Test
    void theTrackReachesTheEngineWhileEveryWorkerOfTheApplicationIsBusy() throws Exception {
        engine.open.countDown(); // a device already open: the engine answers at once
        CountDownLatch busy = new CountDownLatch(1);
        List<MediaPlayer> players = new ArrayList<>();
        try {
            Thread worker = holdTheWorkers(busy);
            for (int i = 0; i < 3; i++) {
                MediaPlayer player = player(new Hand());
                players.add(player);
                player.setAudio(new SilentSource(), PlayOptions.DEFAULTS);
                player.start();
            }
            pumpFor(() -> players.stream().noneMatch(MediaPlayer::isHandingAudioOver));

            for (MediaPlayer player : players) {
                assertFalse(player.isHandingAudioOver(), "the track reached the engine with the workers busy");
                assertSame(engine.handle, player.audio());
                assertTrue(player.isFollowingAudio(), "and times the pictures");
            }
            assertNotSame(worker, engine.calledOn, "not on the application's worker");
            assertNotSame(Thread.currentThread(), engine.calledOn, "nor on the UI thread");
        } finally {
            busy.countDown();
            players.forEach(MediaPlayer::close);
        }
    }

    /**
     * A device open that does not answer (an output waking up, a sound server that hangs): the
     * pictures waited on their first one for as long as it took, with the player saying PLAYING
     * and the position at 0, and for ever when it never answered. They wait a quarter of a second
     * at most, then go on the wall clock, and the track, when it does arrive, joins them where they
     * are: the pictures and the track are moved there together, and the track times the pictures
     * from then on.
     */
    @Test
    void aDeviceThatDoesNotAnswerCostsTheSynchronisationAndNotThePictures() throws Exception {
        Hand hand = new Hand();
        PooledTestStream stream = new PooledTestStream(64, 32, 8);
        MediaPlayer player = new MediaPlayer(stream)
                .setOwnsDecodeThread(false)
                .setRingCapacity(3)
                .setClock(new VideoClock(hand::get));
        try {
            player.setAudio(new SilentSource(), PlayOptions.DEFAULTS);
            player.start();
            assertTrue(engine.entered.await(10, TimeUnit.SECONDS), "the engine is opening the device");
            for (int i = 0; i < 3; i++) {
                player.decodeStep();
            }
            VideoFrame first = player.takePicture();
            assertEquals(0, first.ptsMicros(), "the first picture is shown");
            first.release();
            hand.nanos += TimeUnit.MILLISECONDS.toNanos(200);
            player.decodeStep();
            assertNull(player.takePicture(), "and the others wait for the sound a moment");

            hand.nanos += TimeUnit.MILLISECONDS.toNanos(100); // past the grace
            int shown = 0;
            for (int i = 0; i < 30; i++) {
                player.decodeStep();
                VideoFrame picture = player.takePicture();
                if (picture != null) {
                    shown++;
                    picture.release();
                }
                hand.nanos += FRAME_MICROS * 1_000L;
            }
            assertTrue(shown >= 25, "the pictures go on without the sound: " + shown + " of 30 shown");
            assertTrue(player.isHandingAudioOver(), "the device has still not answered");
            assertEquals(MediaPlayer.State.PLAYING, player.state());
            assertFalse(player.isFollowingAudio());
            long at = player.positionMicros();
            assertTrue(at > 25 * FRAME_MICROS, "the position moves: " + at);

            engine.handle.seconds = 0; // where an engine starts a track
            arrived(player);

            assertEquals(List.of("seek " + at, "gain 1.0"), engine.handle.calls,
                    "the track joins the pictures where they are, and is heard from there");
            player.decodeStep(); // where the decode thread moves the stream
            assertEquals(at, stream.seekTargets.get(stream.seekTargets.size() - 1),
                    "and the pictures are moved there with it, so both are on one timeline");
            assertTrue(player.isFollowingAudio(), "the track times the pictures from here");
            for (int i = 0; i < 3; i++) {
                player.decodeStep();
            }
            VideoFrame joined = player.takePicture();
            assertTrue(joined != null && joined.ptsMicros() >= at - FRAME_MICROS,
                    "the picture where they joined is shown");
            joined.release();
        } finally {
            player.close();
        }
    }

    /** Drives a player past the grace and a second of pictures on the wall clock, the device silent. */
    private void playPastTheGrace(MediaPlayer player, Hand hand) throws Exception {
        player.setAudio(new SilentSource(), PlayOptions.DEFAULTS);
        player.start();
        assertTrue(engine.entered.await(10, TimeUnit.SECONDS));
        hand.nanos += MediaPlayer.AUDIO_GRACE_NANOS;
        for (int i = 0; i < 30; i++) {
            player.decodeStep();
            VideoFrame picture = player.takePicture();
            if (picture != null) {
                picture.release();
            }
            hand.nanos += FRAME_MICROS * 1_000L;
        }
        assertTrue(player.isHandingAudioOver());
        assertTrue(player.positionMicros() > 0, "the pictures run on the wall clock");
    }

    /**
     * A late track the engine cannot move to where the pictures are would sound behind them for
     * good: it is let go, and the video plays on silently, as one with no track does.
     */
    @Test
    void aLateTrackThatCannotBeMovedIsLetGoAndTheVideoPlaysOnSilently() throws Exception {
        Hand hand = new Hand();
        MediaPlayer player = player(hand);
        try {
            engine.handle.seekable = false;
            playPastTheGrace(player, hand);

            arrived(player);

            assertEquals(List.of("stop"), engine.handle.calls, "let go, unheard");
            assertSame(Playback.NONE, player.audio());
            assertFalse(player.hasAudio());
            assertFalse(player.isFollowingAudio());
            long before = player.positionMicros();
            hand.nanos += 10 * FRAME_MICROS * 1_000L;
            assertEquals(before + 10 * FRAME_MICROS, player.positionMicros(), "on the wall clock still");
        } finally {
            player.close();
        }
    }

    /** A late track arriving while the viewer has paused joins the pictures there, paused. */
    @Test
    void aLateTrackArrivingWhilePausedJoinsThePicturesPaused() throws Exception {
        Hand hand = new Hand();
        MediaPlayer player = player(hand);
        try {
            playPastTheGrace(player, hand);
            player.pause();
            long at = player.positionMicros();

            arrived(player);

            assertEquals(List.of("seek " + at, "pause", "gain 1.0"), engine.handle.calls);
            assertEquals(MediaPlayer.State.PAUSED, player.state());
            assertEquals(at, player.positionMicros(), "where it was paused");
            player.resume();
            assertTrue(player.isFollowingAudio());
            assertEquals(List.of("seek " + at, "pause", "gain 1.0", "resume"), engine.handle.calls);
        } finally {
            player.close();
        }
    }

    /**
     * No device, a device that fails to open, or no engine at all: nothing is held, the track is
     * closed, and the pictures play at once on the wall clock, as a video with no track does, with
     * every worker of the application busy too.
     */
    @Test
    void withNoDeviceTheVideoPlaysSilentlyAtOnce() throws Exception {
        AudioEngine noDevice = new ClosingEngine() {
            @Override
            public boolean isAvailable() {
                return false;
            }
        };
        AudioEngine failsToOpen = new ClosingEngine() {
            @Override
            public Playback playStream(AudioStreamSource source, PlayOptions options) {
                source.close(); // as an engine whose device would not open does
                return Playback.NONE;
            }
        };
        CountDownLatch busy = new CountDownLatch(1);
        try {
            holdTheWorkers(busy);
            for (AudioEngine each : java.util.Arrays.asList(noDevice, failsToOpen, null)) {
                Sounds.uninstallEngine(engine);
                if (each != null) {
                    Sounds.installEngine(each);
                }
                Hand hand = new Hand();
                MediaPlayer player = player(hand);
                CountingSource track = new CountingSource();
                try {
                    player.setAudio(track, PlayOptions.DEFAULTS);
                    player.start();
                    pumpFor(() -> !player.isHandingAudioOver());
                    assertFalse(player.isHandingAudioOver(), "the engine's answer arrived");
                    assertFalse(player.hasAudio(), "no track sounds");
                    assertEquals(1, track.closes.get(), "the track was closed");
                    int shown = 0;
                    for (int i = 0; i < 6; i++) { // a fifth of a second: within the grace
                        player.decodeStep();
                        VideoFrame picture = player.takePicture();
                        if (picture != null) {
                            shown++;
                            picture.release();
                        }
                        hand.nanos += FRAME_MICROS * 1_000L;
                    }
                    assertTrue(shown >= 5, "the pictures play at once: " + shown + " of 6 shown");
                } finally {
                    player.close();
                    if (each != null) {
                        Sounds.uninstallEngine(each);
                    }
                }
            }
        } finally {
            busy.countDown();
            Sounds.installEngine(engine);
        }
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
    private static class ClosingEngine implements AudioEngine {
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
        volatile boolean seekable = true;

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
            calls.add("gain " + gain);
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
            seconds = micros / 1_000_000.0; // reports the new position, as the engine's handle does
        }

        @Override
        public boolean canSeek() {
            return seekable;
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
        volatile PlayOptions options;

        @Override
        public Playback play(AudioClip clip, float gain, boolean loop) {
            return Playback.NONE;
        }

        @Override
        public Playback playStream(AudioStreamSource source, PlayOptions options) {
            calledOn = Thread.currentThread();
            this.options = options;
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
