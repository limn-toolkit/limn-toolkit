package limn.video;

import limn.backend.CrashHandler;
import limn.backend.CrashPhase;
import limn.backend.Crashes;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.sound.AudioClip;
import limn.sound.AudioEngine;
import limn.sound.AudioStreamSource;
import limn.sound.PlayOptions;
import limn.sound.Playback;
import limn.sound.Sounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player: its ring, its lifecycle, its shutdown, and the fact that a stream with no soundtrack
 * (which is every stream a pure-Java decoder produces) plays at the right rate anyway.
 *
 * <p>Nothing here sleeps. The deterministic cases give the player no decode thread at all and turn
 * {@link MediaPlayer#decodeStep()} by hand; the genuinely concurrent ones use latches to pin the
 * interleaving, and wait on a condition rather than on a duration, so a loaded machine makes them
 * slower and never makes them fail.
 */
class MediaPlayerTest {

    private static final long FRAME_MICROS = 33_333; // 30 per second
    /** Generous: a test that needs it has already failed to make progress, not merely been slow. */
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

    /** Wall clock a test turns by hand. */
    private static final class Hand {
        long nanos;

        long get() {
            return nanos;
        }

        void advanceMicros(long micros) {
            nanos += micros * 1_000L;
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

    /** Waits for a condition rather than for a duration; fails only if it never becomes true. */
    private static void await(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + AWAIT_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.onSpinWait();
        }
    }

    // ------------------------------------------------------------------ the common case

    @Test
    void withNoAudioTrackAtAllThePicturesStillRunAtTheStreamsRate() {
        // Every stream a pure-Java decoder produces has no soundtrack, so this is the common case
        // and not the corner: no master is installed and the wall clock paces at the right rate.
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            assertFalse(player.hasAudio());
            assertSame(Playback.NONE, player.audio());

            int shown = 0;
            for (int ask = 0; ask < 60; ask++) {
                pump(player, 3);
                VideoFrame picture = player.takePicture();
                if (picture != null) {
                    shown++;
                    picture.release();
                }
                hand.advanceMicros(FRAME_MICROS);
            }
            // Asked once per picture interval, every picture is due exactly once: all sixty come
            // out, none is dropped, and none is shown twice.
            assertEquals(60, shown, "the wall clock must pace at the stream's own rate");
            assertEquals(0, player.clock().droppedFrames());
        }
        assertEquals(stream.slots(), stream.freeSlots(), "every borrowed picture went back");
        assertEquals(0, stream.closes, "the stream is the caller's; the player never closes it");
    }

    @Test
    void aPlayerThatFallsBehindDropsRatherThanShowingPicturesLate() {
        // The capability a ring buys that a view holding one picture cannot have: naming the
        // picture behind the candidate is what licenses a drop.
        PooledTestStream stream = new PooledTestStream(64, 32, 6);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 4)) {
            player.start();
            pump(player, 4);
            VideoFrame first = player.takePicture();
            assertNotNull(first);
            first.release();

            // A whole second passes with nobody asking: a stall in the window, or a machine that
            // went away. The pictures for that second are due in the past.
            hand.advanceMicros(1_000_000);
            pump(player, 4);
            VideoFrame caught = player.takePicture();
            assertNotNull(caught);
            assertTrue(player.clock().droppedFrames() > 0,
                    "a player an entire second behind must skip, not show every picture late");
            assertTrue(caught.ptsMicros() > FRAME_MICROS,
                    "the picture handed over must be a recent one, not the next in line");
            caught.release();
        }
        assertEquals(stream.slots(), stream.freeSlots());
    }

    // ------------------------------------------------------------------ the ring

    @Test
    void afullRingStopsTheDecoderInsteadOfHoldingMorePicturesThanItSaid() {
        PooledTestStream stream = new PooledTestStream(64, 32, 6);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 2)) {
            player.start();

            assertEquals(MediaPlayer.Step.PRODUCED, player.decodeStep());
            assertEquals(MediaPlayer.Step.PRODUCED, player.decodeStep());
            assertEquals(2, player.bufferedPictures());

            int readsAtCapacity = stream.reads.get();
            assertEquals(MediaPlayer.Step.IDLE, player.decodeStep(), "a full ring is nothing to do");
            assertEquals(MediaPlayer.Step.IDLE, player.decodeStep());
            assertEquals(readsAtCapacity, stream.reads.get(),
                    "a full ring must not read: the picture would have nowhere to go and the "
                            + "stream's own pool would lose a slot to hold it");
            assertEquals(2, player.bufferedPictures());
            assertEquals(stream.slots() - 2, stream.freeSlots());

            // Room again the moment one is consumed.
            player.takePicture().release();
            assertEquals(MediaPlayer.Step.PRODUCED, player.decodeStep());
        }
        assertEquals(stream.slots(), stream.freeSlots());
    }

    @Test
    void anEmptyRingIsCountedOncePerDrySpellRatherThanOncePerAsk() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();

            assertNull(player.takePicture(), "nothing decoded yet");
            assertNull(player.takePicture());
            assertNull(player.takePicture());
            assertEquals(1, player.underruns(),
                    "one dry spell, however many times it is asked about: a count per ask would "
                            + "read as a frame count and mean nothing");

            pump(player, 1);
            assertNotNull(player.takePicture());
            hand.advanceMicros(FRAME_MICROS);
            assertNull(player.takePicture(), "dry again");
            assertEquals(2, player.underruns());
        }
    }

    @Test
    void theStreamsOwnPoolBoundsTheReadAheadWhenItIsSmallerThanTheRing() {
        // Two slots: one picture can be held and one is what the stream decodes into. A ring of
        // four cannot change that, and the answer is PENDING rather than a stall or a throw.
        PooledTestStream stream = new PooledTestStream(64, 32, 2);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 4)) {
            player.start();
            pump(player, 8);
            assertEquals(2, player.bufferedPictures(),
                    "the read-ahead is the smaller of the ring and the stream's spare slots");
            assertEquals(0, stream.freeSlots());
            assertEquals(MediaPlayer.Step.IDLE, player.decodeStep(),
                    "a stream with every slot held reports PENDING, which is not the end");
        }
        assertEquals(stream.slots(), stream.freeSlots());
    }

    @Test
    void anUntimedStreamHandsOverOnePicturePerAsk() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        stream.timed = false;
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            pump(player, 3);
            // No arithmetic is possible on the unknown-timestamp sentinel, so the fastest honest
            // answer is one picture per ask, with the wall clock never consulted.
            for (int i = 0; i < 3; i++) {
                VideoFrame picture = player.takePicture();
                assertNotNull(picture, "picture " + i);
                assertEquals(VideoFrame.PTS_UNKNOWN, picture.ptsMicros());
                picture.release();
            }
            assertEquals(0, player.clock().presentedFrames(),
                    "the clock was never asked, because it would have refused the sentinel");
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void endOfStreamKeepsTheLastPicturesAndOnlyThenReportsTheEnd() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        stream.frameCount = 2;
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            pump(player, 4);
            assertEquals(2, player.bufferedPictures());
            assertFalse(player.isEnded(), "the stream ended, but its pictures have not been shown");

            player.takePicture().release();
            hand.advanceMicros(FRAME_MICROS);
            player.takePicture().release();
            assertFalse(player.isEnded());

            hand.advanceMicros(FRAME_MICROS);
            assertNull(player.takePicture());
            assertTrue(player.isEnded(), "empty and the stream is done: only now is it the end");
            assertEquals(MediaPlayer.State.ENDED, player.state());
        }
        assertEquals(stream.slots(), stream.freeSlots());
    }

    @Test
    void pausingFreezesTheTimelineAndKeepsFillingTheRing() {
        PooledTestStream stream = new PooledTestStream(64, 32, 6);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            pump(player, 1);
            player.takePicture().release();

            player.pause();
            assertEquals(MediaPlayer.State.PAUSED, player.state());
            hand.advanceMicros(5_000_000); // five seconds of the world moving on
            assertNull(player.takePicture(), "a paused player shows nothing new");

            pump(player, 3);
            assertEquals(3, player.bufferedPictures(),
                    "decoding continues while paused, so resuming costs no decode");

            player.resume();
            assertEquals(MediaPlayer.State.PLAYING, player.state());
            assertNull(player.takePicture(),
                    "the timeline resumes where it froze, so the next picture is still one frame "
                            + "interval away; the five seconds were not spent");
            hand.advanceMicros(FRAME_MICROS);
            VideoFrame next = player.takePicture();
            assertNotNull(next);
            assertEquals(FRAME_MICROS, next.ptsMicros(),
                    "the pause cost no media time: the next picture is the next picture, not the "
                            + "one five seconds of wall clock later");
            next.release();
        }
        assertEquals(stream.slots(), stream.freeSlots());
    }

    @Test
    void stopReleasesEverythingAndLeavesTheStreamOpenWhereItStood() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            pump(player, 3);
            assertEquals(3, player.bufferedPictures());

            player.stop();

            assertEquals(MediaPlayer.State.IDLE, player.state());
            assertEquals(0, player.bufferedPictures());
            assertEquals(stream.slots(), stream.freeSlots(), "a stop hands every picture back");
            assertEquals(0, stream.closes, "a stop does not close the caller's stream");
            assertEquals(0, stream.resets, "and does not rewind it either");

            // Startable again, from where the stream had reached.
            player.start();
            pump(player, 1);
            VideoFrame picture = player.takePicture();
            assertNotNull(picture);
            assertEquals(3 * FRAME_MICROS, picture.ptsMicros(), "carried on rather than rewound");
            picture.release();
        }
    }

    @Test
    void restartRewindsAndClearsAFailure() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            pump(player, 2);
            player.takePicture().release();

            stream.failOnRead = new IllegalStateException("corrupt");
            assertEquals(MediaPlayer.Step.DONE, player.decodeStep());
            assertEquals(MediaPlayer.State.FAILED, player.state());
            assertSame(stream.failOnRead, player.failure());

            stream.failOnRead = null;
            player.restart();

            assertNull(player.failure(), "a restart is how a view recovers from a decode that threw");
            assertEquals(MediaPlayer.State.PLAYING, player.state());
            assertEquals(1, stream.resets);
            pump(player, 1);
            VideoFrame picture = player.takePicture();
            assertEquals(0, picture.ptsMicros(), "rewound to the first picture");
            picture.release();
        }
        assertEquals(stream.slots(), stream.freeSlots());
    }

    @Test
    void aStreamThatCannotBeRewoundSaysSoRatherThanEndingQuietly() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        stream.rewindable = false;
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            assertThrows(UnsupportedOperationException.class, player::restart);
        }
    }

    @Test
    void configurationIsRefusedWhilePlayingAndEverythingIsRefusedAfterClose() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        Hand hand = new Hand();
        MediaPlayer player = manual(stream, hand, 3);
        player.start();
        assertThrows(IllegalStateException.class, () -> player.setRingCapacity(8));
        assertThrows(IllegalStateException.class, () -> player.setOwnsDecodeThread(true));
        assertThrows(IllegalStateException.class, () -> player.setClock(new VideoClock()));

        player.close();
        assertEquals(MediaPlayer.State.CLOSED, player.state());
        assertThrows(IllegalStateException.class, player::start);
        assertThrows(IllegalStateException.class, player::restart);
        player.close(); // idempotent
        player.stop();  // harmless on a closed player
        assertNull(player.takePicture());
        assertEquals(0, stream.closes, "closing a player never closes the caller's stream");
    }

    @Test
    void aResolutionChangeMidStreamIsCarriedThroughUntouched() {
        // The player reads no geometry at all: what it hands over is what the stream published,
        // and re-letterboxing it is the view's business.
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        stream.shrinkAfter = 2;
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 1)) {
            player.start();
            for (int i = 0; i < 4; i++) {
                pump(player, 1);
                VideoFrame picture = player.takePicture();
                assertNotNull(picture, "picture " + i);
                int expected = i < 2 ? 64 : 32;
                assertEquals(expected, picture.width(), "picture " + i + " width");
                assertEquals(i < 2 ? 32 : 16, picture.height(), "picture " + i + " height");
                picture.release();
                hand.advanceMicros(FRAME_MICROS);
            }
            assertNull(player.failure(), "a resolution change is not a failure");
        }
        assertEquals(stream.slots(), stream.freeSlots());
    }

    // ------------------------------------------------------------------ looping

    @Test
    void loopingRewindsAndTheLastPictureOfAPassIsNeverDroppedForTheFirstOfTheNext() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        stream.frameCount = 3;
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.setLooping(true);
            player.start();
            pump(player, 8); // three pictures, the end, a rewind, and more

            int shown = 0;
            long lastPts = -1;
            boolean wrapped = false;
            for (int i = 0; i < 6; i++) {
                VideoFrame picture = player.takePicture();
                if (picture != null) {
                    if (picture.ptsMicros() < lastPts) {
                        wrapped = true;
                    }
                    lastPts = picture.ptsMicros();
                    shown++;
                    picture.release();
                }
                pump(player, 2);
                hand.advanceMicros(FRAME_MICROS);
            }
            assertTrue(stream.resets > 0, "looping rewinds the stream at its end");
            assertTrue(wrapped, "the pictures must come round again");
            assertEquals(6, shown, "no picture of either pass was skipped");
            assertFalse(player.isEnded(), "a looping player never ends");
        }
        assertEquals(stream.slots(), stream.freeSlots());
    }

    @Test
    void aLoopDropsTheAudioMasterBecauseThePicturesTimelineRestartedAndTheTracksDidNot() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        stream.frameCount = 2;
        SteadyEngine engine = new SteadyEngine();
        Sounds.installEngine(engine);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.setAudio(new SilentSource(), PlayOptions.DEFAULTS);
            player.setLooping(true);
            player.start();
            assertTrue(player.hasAudio());

            // Under the master, at the track's position.
            pump(player, 2);
            player.takePicture().release();
            engine.handle.seconds = FRAME_MICROS / 1_000_000.0;
            hand.advanceMicros(FRAME_MICROS);
            player.takePicture().release();

            // The pictures come round again; the track does not, so it can no longer say when a
            // picture is due. The wall clock takes over rather than every picture holding until
            // the track has run its length.
            pump(player, 4);
            engine.handle.seconds = 5.0; // the track plays on, now on a timeline of its own
            hand.advanceMicros(FRAME_MICROS);
            VideoFrame wrapped = player.takePicture();
            assertNotNull(wrapped, "a wrapped pass must not freeze waiting for the soundtrack");
            assertEquals(0, wrapped.ptsMicros(), "the first picture of the new pass");
            wrapped.release();

            hand.advanceMicros(FRAME_MICROS);
            pump(player, 2);
            VideoFrame next = player.takePicture();
            assertNotNull(next, "and the pass after the loop keeps running at the right rate");
            next.release();
        } finally {
            Sounds.uninstallEngine(engine);
        }
        assertEquals(stream.slots(), stream.freeSlots());
    }

    // ------------------------------------------------------------------ audio

    @Test
    void aTrackThatSoundsBecomesTheMasterAndTheEngineOwnsItFromThere() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        SteadyEngine engine = new SteadyEngine();
        Sounds.installEngine(engine);
        SilentSource track = new SilentSource();
        Hand hand = new Hand();
        MediaPlayer player = manual(stream, hand, 3);
        try {
            PlayOptions options = PlayOptions.DEFAULTS.withGain(0.6f);
            player.setAudio(track, options);
            player.start();

            assertSame(track, engine.received, "the open track reached the engine");
            assertEquals(0.6f, engine.receivedOptions.gain());
            assertSame(engine.handle, player.audio());
            assertEquals(0, track.closes, "the engine owns it now");

            // The pictures follow the track's position, not the wall clock: nothing moves the
            // wall here at all, and the track alone decides what is due.
            pump(player, 3);
            player.takePicture().release();
            engine.handle.seconds = FRAME_MICROS / 1_000_000.0;
            VideoFrame second = player.takePicture();
            assertNotNull(second, "the track says this picture's moment has come");
            assertEquals(FRAME_MICROS, second.ptsMicros());
            second.release();

            // And the track alone is what skips: moved two intervals on, the picture in between
            // is stale and is dropped rather than shown late.
            engine.handle.seconds = 3 * FRAME_MICROS / 1_000_000.0;
            pump(player, 2);
            VideoFrame caughtUp = player.takePicture();
            assertNotNull(caughtUp);
            assertEquals(3 * FRAME_MICROS, caughtUp.ptsMicros(), "the stale picture was dropped");
            caughtUp.release();

            player.close();
            assertTrue(engine.handle.stopped, "closing the player stops the track");
            assertEquals(0, track.closes,
                    "and does NOT close it: handing a source to an engine transferred it, so a "
                            + "player that closed it too would be closing it twice");
        } finally {
            player.close();
            Sounds.uninstallEngine(engine);
        }
        assertEquals(stream.slots(), stream.freeSlots());
    }

    @Test
    void aTrackThatNeverReachedTheEngineIsClosedByTheClose() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        SilentSource track = new SilentSource();
        Hand hand = new Hand();
        MediaPlayer player = manual(stream, hand, 3);
        player.setAudio(track, PlayOptions.DEFAULTS);
        player.close(); // never started, so the engine never took it

        assertEquals(1, track.closes, "still the player's, so still the player's to close");
    }

    @Test
    void aMachineWithNoAudioDeviceGetsNoMasterAndTheRightRateRatherThanNoPictures() {
        // Sounds hands back Playback.NONE with no engine installed and on a machine with no
        // device. Its position is a constant zero; following it would cost a stall's worth of
        // held pictures on exactly the machines that can least afford a hitch.
        Sounds.uninstallEngine(null);
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        SilentSource track = new SilentSource();
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.setAudio(track, PlayOptions.DEFAULTS);
            player.start();

            assertSame(Playback.NONE, player.audio(), "nothing sounds");
            assertEquals(1, track.closes, "the facade closed the track it was handed");

            int shown = 0;
            for (int ask = 0; ask < 30; ask++) {
                pump(player, 3);
                VideoFrame picture = player.takePicture();
                if (picture != null) {
                    shown++;
                    picture.release();
                }
                hand.advanceMicros(FRAME_MICROS);
            }
            assertEquals(30, shown,
                    "with no device the video must play at the right rate, not at no rate");
            assertEquals(0, player.clock().stallCount(),
                    "no master was installed, so there was nothing to declare stalled");
        }
        assertEquals(stream.slots(), stream.freeSlots());
    }

    @Test
    void aTrackThatStopsSoundingHandsTheTimelineBackToTheWallClock() {
        PooledTestStream stream = new PooledTestStream(64, 32, 6);
        SteadyEngine engine = new SteadyEngine();
        Sounds.installEngine(engine);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 4)) {
            player.setAudio(new SilentSource(), PlayOptions.DEFAULTS);
            player.start();

            pump(player, 4);
            player.takePicture().release();

            // The track finishes while the pictures have not. Its position freezes and it reports
            // that it is no longer playing.
            engine.handle.seconds = 0.5;
            engine.handle.playing = false;
            long shown = 0;
            long pts = FRAME_MICROS;
            for (long wall = 0; wall < 3_000_000; wall += FRAME_MICROS) {
                hand.advanceMicros(FRAME_MICROS);
                pump(player, 4);
                VideoFrame picture = player.takePicture();
                if (picture != null) {
                    shown++;
                    pts = picture.ptsMicros();
                    picture.release();
                }
            }
            assertTrue(shown > 50,
                    "only " + shown + " pictures in 3 s: the wall clock never took the timeline back");
            assertTrue(pts > 2_000_000, "and the stream kept moving forward, reaching " + pts + "us");
        } finally {
            Sounds.uninstallEngine(engine);
        }
        assertEquals(stream.slots(), stream.freeSlots());
    }

    // ------------------------------------------------------------------ errors

    @Test
    void aDecodeThatThrowsStopsTheStreamAndSurfacesWhereSomeoneCanSeeIt() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        RuntimeException boom = new IllegalStateException("bitstream is nonsense");
        stream.failOnRead = boom;
        AtomicReference<CrashPhase> phase = new AtomicReference<>();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CrashHandler handler = (crashPhase, error) -> {
            phase.set(crashPhase);
            reported.set(error);
            return true;
        };
        Crashes.install(handler);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            assertEquals(MediaPlayer.Step.DONE, player.decodeStep());

            assertEquals(MediaPlayer.State.FAILED, player.state());
            assertSame(boom, player.failure(), "kept, so a widget can say the video cannot be played");
            assertEquals(CrashPhase.DECODE, phase.get(),
                    "a decode thread belongs to no event-loop phase; without its own the stack "
                            + "would be swallowed entirely");
            assertSame(boom, reported.get());
            assertNull(player.takePicture(), "a failed player hands out nothing more");
        } finally {
            Crashes.uninstall(handler);
        }
        assertEquals(stream.slots(), stream.freeSlots(),
                "a failure must not strand the pictures already decoded");
    }

    @Test
    void aDecodeFailureOutranksATransitionTheUiThreadHadAlreadyDecidedOn() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            stream.failOnRead = new IllegalStateException("bitstream is nonsense");
            player.decodeStep();
            assertEquals(MediaPlayer.State.FAILED, player.state());

            // The losing half of the race, which no public method can reach because each of them
            // re-reads the state: the UI thread tested it a moment ago, the decode thread wrote
            // FAILED in between, and now the transition it decided on then is applied. Every one
            // of these used to be a plain assignment, and each of them reported a player that had
            // ended cleanly or paused while failure() held an exception.
            assertFalse(player.enterState(MediaPlayer.State.PLAYING, MediaPlayer.State.ENDED),
                    "takePicture's end, decided from a sourceEnded the failure itself set");
            assertFalse(player.enterState(MediaPlayer.State.PLAYING, MediaPlayer.State.PAUSED));
            assertFalse(player.enterState(MediaPlayer.State.PAUSED, MediaPlayer.State.PLAYING));

            assertEquals(MediaPlayer.State.FAILED, player.state());
            assertNotNull(player.failure(),
                    "the contract is state and failure agreeing: FAILED is the state that has one");

            // Sticky until something clears it deliberately, which is what the documented recovery
            // is, and after that the ordinary transitions work again.
            player.stop();
            assertNull(player.failure());
            assertTrue(player.enterState(MediaPlayer.State.IDLE, MediaPlayer.State.PLAYING));
        }
    }

    @Test
    void aFailureReleasesThePicturesAlreadyInTheRing() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            pump(player, 2);
            assertEquals(2, player.bufferedPictures());

            stream.failOnRead = new IllegalStateException("gone");
            player.decodeStep();

            assertNull(player.takePicture());
            assertEquals(0, player.bufferedPictures(),
                    "the slots go back to a stream that can no longer refill them anyway");
            assertEquals(stream.slots(), stream.freeSlots());
        }
    }

    // ------------------------------------------------------------------ threads and shutdown

    @Test
    void aRealDecodeThreadFillsTheRingAndEveryPictureIsReleasedExactlyOnce() throws Exception {
        PooledTestStream stream = new PooledTestStream(64, 32, 5);
        MediaPlayer player = new MediaPlayer(stream).setRingCapacity(3);
        try {
            player.start();
            await(() -> player.bufferedPictures() == 3, "the decode thread to fill the ring");

            // The recycler throws when a slot comes back twice, and a throw on the decode thread
            // would land in the player's failure. Both are checked below.
            for (int i = 0; i < 40; i++) {
                VideoFrame picture = player.takePicture();
                if (picture != null) {
                    picture.release();
                }
                await(() -> player.bufferedPictures() > 0, "the decode thread to make progress");
            }
            assertNull(player.failure(),
                    "a picture released twice across the handoff would surface here: "
                            + player.failure());
            assertTrue(player.decodedFrames() > 3);
        } finally {
            player.close();
        }
        assertEquals(stream.slots(), stream.freeSlots(),
                "every picture the decode thread produced went back exactly once");
        assertEquals(MediaPlayer.State.CLOSED, player.state());
    }

    @Test
    void closingWhileAReadIsInFlightWaitsForItAndReleasesWhatItProduced() throws Exception {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        stream.entered = entered;
        stream.release = release;

        MediaPlayer player = new MediaPlayer(stream).setRingCapacity(3);
        player.start();
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "the decode thread must reach the read");

        // Let the read finish only once the close is under way, so the close is what waits.
        Thread releaser = new Thread(() -> {
            try {
                closing.await();
                release.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "test-releaser");
        releaser.start();

        closing.countDown();
        player.close();
        releaser.join();

        // The whole point of the join: after this returns, nothing will touch the stream again,
        // which is what makes closing it next safe rather than a decode against a dead decoder.
        stream.shutdownComplete = true;
        assertEquals(0, stream.readsAfterShutdown.get(),
                "close() returned while a read could still happen: the caller cannot close the "
                        + "stream after that without racing the decoder");
        assertEquals(stream.slots(), stream.freeSlots(),
                "a picture in flight at the close must go back, not be stranded in a dead ring");
        assertEquals(MediaPlayer.State.CLOSED, player.state());
        assertEquals(0, stream.closes, "and the stream is still the caller's to close");
    }

    @Test
    void aPlayerClosedTwiceFromDifferentStatesIsStillCorrect() {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        MediaPlayer player = new MediaPlayer(stream).setRingCapacity(2);
        player.start();
        await(() -> player.bufferedPictures() > 0, "a picture to be decoded");
        player.pause();
        player.close();
        player.close();
        assertEquals(stream.slots(), stream.freeSlots());
        assertEquals(MediaPlayer.State.CLOSED, player.state());
    }

    @Test
    void everyUiThreadMethodRefusesAnotherThread() throws Exception {
        PooledTestStream stream = new PooledTestStream(64, 32, 4);
        Hand hand = new Hand();
        try (MediaPlayer player = manual(stream, hand, 3)) {
            player.start();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    player.takePicture();
                } catch (Throwable error) {
                    thrown.set(error);
                }
            }, "test-off-thread");
            other.start();
            other.join();
            assertTrue(thrown.get() instanceof IllegalStateException,
                    "a picture taken on two threads is a picture released twice, so the check is "
                            + "worth its cost: " + thrown.get());
        }
    }

    // ------------------------------------------------------------------ doubles

    /** An audio source with nothing in it: what is under test is who closes it, not how it sounds. */
    private static final class SilentSource implements AudioStreamSource {
        int closes;

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
            closes++;
        }
    }

    /** A playback whose position a test sets by hand, in seconds. */
    private static final class HandPlayback implements Playback {
        double seconds;
        boolean playing = true;
        boolean stopped;

        @Override
        public void stop() {
            stopped = true;
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
    }

    /** An engine that accepts a stream and keeps it, the way the shipped one does. */
    private static final class SteadyEngine implements AudioEngine {
        final HandPlayback handle = new HandPlayback();
        AudioStreamSource received;
        PlayOptions receivedOptions;

        @Override
        public Playback play(AudioClip clip, float gain, boolean loop) {
            return Playback.NONE;
        }

        @Override
        public Playback playStream(AudioStreamSource source, PlayOptions options) {
            received = source;
            receivedOptions = options;
            return handle;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
