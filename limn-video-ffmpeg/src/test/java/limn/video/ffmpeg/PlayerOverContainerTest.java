package limn.video.ffmpeg;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.video.MediaPlayer;
import limn.video.VideoClock;
import limn.video.VideoFrame;
import limn.video.VideoStreamSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real container driven by a real player.
 *
 * <p>Everything else here reads the stream directly, which is the path a screenshot takes and is
 * therefore the path that was already covered. The path a person actually watches is not that one:
 * it is a {@link MediaPlayer} pulling on a decode thread, a ring, and a clock deciding when a
 * picture is due; the first thing that went wrong in the Kitchen Sink went wrong there and
 * nowhere else, so it is worth its own file.
 *
 * <p>Nothing here sleeps. The player is given no decode thread and its step is turned by hand, and
 * the clock is a number this test advances, so a loaded machine makes it slower and never makes it
 * fail.
 */
class PlayerOverContainerTest {

    @TempDir
    Path directory;

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
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    /** The wall clock, turned by hand. */
    private static final class Hand {
        long nanos;

        long get() {
            return nanos;
        }

        void advanceMicros(long micros) {
            nanos += micros * 1_000L;
        }
    }

    @Test
    void aPlayerOverARealContainerHandsOutItsPictures() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 320, 180, 60, 0);

        try (FfmpegMedia media = FfmpegMedia.open(clip, false, FfmpegMedia.DEFAULT_SLOTS)) {
            Hand hand = new Hand();
            MediaPlayer player = new MediaPlayer(media.video())
                    .setOwnsDecodeThread(false)
                    .setRingCapacity(3)
                    .setClock(new VideoClock(hand::get));
            try {
                player.start();

                // Fill the ring before asking for anything, the way a decode thread would have.
                for (int i = 0; i < 16; i++) {
                    player.decodeStep();
                }
                assertTrue(player.bufferedPictures() > 0,
                        "the decode step produced nothing at all from a real container");

                int shown = 0;
                for (int step = 0; step < 400 && shown < 30; step++) {
                    player.decodeStep();
                    VideoFrame picture = player.takePicture();
                    if (picture != null) {
                        assertEquals(320, picture.width());
                        picture.release();
                        shown++;
                    }
                    hand.advanceMicros(33_333);
                }
                assertEquals(30, shown, "the player handed out pictures as they came due");
                assertNull(player.failure(), () -> "the decode thread failed: " + player.failure());
            } finally {
                player.close();
            }
        }
    }

    /**
     * The same thing with the container's own soundtrack attached but no audio engine installed,
     * which is the machine with no sound card, and is also what a build server is. The handle is
     * the null one, so no master is installed and the pictures pace on the wall clock; what must
     * NOT happen is that they stop arriving.
     */
    @Test
    void asoundtrackThatNeverSoundsDoesNotStopThePictures() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 320, 180, 60, 2);

        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            assertNotNull(media.audio());
            Hand hand = new Hand();
            MediaPlayer player = new MediaPlayer(media.video())
                    .setOwnsDecodeThread(false)
                    .setRingCapacity(3)
                    .setClock(new VideoClock(hand::get));
            try {
                player.setAudio(media.audio(), limn.sound.PlayOptions.DEFAULTS);
                player.start();

                for (int i = 0; i < 16; i++) {
                    player.decodeStep();
                }
                int shown = 0;
                for (int step = 0; step < 400 && shown < 30; step++) {
                    player.decodeStep();
                    VideoFrame picture = player.takePicture();
                    if (picture != null) {
                        picture.release();
                        shown++;
                    }
                    hand.advanceMicros(33_333);
                }
                assertEquals(30, shown,
                        "a track that never sounded must not hold the pictures back");
                assertNull(player.failure(), () -> "the decode thread failed: " + player.failure());
            } finally {
                player.close();
            }
        }
    }

    /**
     * Changing the language of a film that is playing, which is what the Kitchen Sink's audio
     * button does, and which is a <em>second player over the same borrowed stream</em>, because a
     * player takes its audio track at construction and there is no swapping one under a running
     * one: handing a source to the audio engine transfers it.
     *
     * <p>So the sequence is the whole of the answer, and every step of it is load-bearing. The
     * first player is closed, which joins its decode thread and is what makes the stream safe to
     * hand to a second one; the container is then asked for another track, which supersedes the one
     * the first player had; and the new player is placed back where the picture had reached. The
     * container is open throughout and closed by neither player, because neither opened it.
     */
    @Test
    void aSecondPlayerTakesAnotherLanguageOverTheSameBorrowedStream() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 90,
                java.util.List.of(new FfmpegMedia.ClipAudioTrack(2, "eng"),
                        new FfmpegMedia.ClipAudioTrack(2, "fra")));

        try (FfmpegMedia media = FfmpegMedia.open(clip)) {
            assertEquals(2, media.audioTracks().size());
            VideoStreamSource video = media.video();
            Hand hand = new Hand();

            MediaPlayer first = new MediaPlayer(video)
                    .setOwnsDecodeThread(false)
                    .setRingCapacity(3)
                    .setClock(new VideoClock(hand::get));
            first.setAudio(media.audio(), limn.sound.PlayOptions.DEFAULTS);
            first.start();
            int shown = 0;
            for (int step = 0; step < 400 && shown < 10; step++) {
                first.decodeStep();
                VideoFrame picture = first.takePicture();
                if (picture != null) {
                    picture.release();
                    shown++;
                }
                hand.advanceMicros(33_333);
            }
            assertEquals(10, shown);
            long reached = first.positionMicros();
            first.close();

            MediaPlayer second = new MediaPlayer(video)
                    .setOwnsDecodeThread(false)
                    .setRingCapacity(3)
                    .setClock(new VideoClock(hand::get));
            try {
                second.setAudio(media.audio(1), limn.sound.PlayOptions.DEFAULTS);
                assertEquals(1, media.selectedAudioTrack());
                second.start();
                second.seek(reached, VideoStreamSource.SeekMode.EXACT);

                int again = 0;
                for (int step = 0; step < 400 && again < 20; step++) {
                    second.decodeStep();
                    VideoFrame picture = second.takePicture();
                    if (picture != null) {
                        picture.release();
                        again++;
                    }
                    hand.advanceMicros(33_333);
                }
                assertEquals(20, again,
                        "the pictures kept coming out of the stream the first player gave back");
                assertNull(second.failure(), () -> "the second player failed: " + second.failure());
            } finally {
                second.close();
            }
            assertTrue(media.isOpen(), "neither player closed the container, because neither opened it");
        }
    }

    /**
     * Looping, which is what the Kitchen Sink switches on. The container has to rewind underneath a
     * running player and keep producing.
     */
    @Test
    void aLoopingPlayerRewindsTheContainerAndKeepsGoing() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 20, 0);

        try (FfmpegMedia media = FfmpegMedia.open(clip, false, FfmpegMedia.DEFAULT_SLOTS)) {
            Hand hand = new Hand();
            MediaPlayer player = new MediaPlayer(media.video())
                    .setOwnsDecodeThread(false)
                    .setRingCapacity(3)
                    .setClock(new VideoClock(hand::get));
            try {
                player.setLooping(true);
                player.start();

                int shown = 0;
                // More pictures than the clip holds: it has to have gone round.
                for (int step = 0; step < 2000 && shown < 50; step++) {
                    player.decodeStep();
                    VideoFrame picture = player.takePicture();
                    if (picture != null) {
                        picture.release();
                        shown++;
                    }
                    hand.advanceMicros(33_333);
                }
                assertEquals(50, shown, "a 20-picture clip on loop kept producing past its end");
                assertNull(player.failure(), () -> "the decode thread failed: " + player.failure());
            } finally {
                player.close();
            }
        }
    }

    /**
     * A seek through the whole stack against a real container (the player's request, the decode
     * thread's reposition, the shim's placement and discard), landing where it was asked to and
     * carrying on from there.
     */
    @Test
    void aSeekThroughThePlayerLandsAndKeepsPlaying() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 120, 0);

        try (FfmpegMedia media = FfmpegMedia.open(clip, false, FfmpegMedia.DEFAULT_SLOTS)) {
            Hand hand = new Hand();
            MediaPlayer player = new MediaPlayer(media.video())
                    .setOwnsDecodeThread(false)
                    .setRingCapacity(3)
                    .setClock(new VideoClock(hand::get));
            try {
                player.start();
                for (int i = 0; i < 16; i++) {
                    player.decodeStep();
                }

                long target = 2_000_000;
                player.seek(target, VideoStreamSource.SeekMode.EXACT);
                assertEquals(target, player.positionMicros(),
                        "the position is the target while the pictures are still being found");

                VideoFrame first = null;
                for (int step = 0; step < 400 && first == null; step++) {
                    player.decodeStep();
                    first = player.takePicture();
                    hand.advanceMicros(33_333);
                }
                assertNotNull(first, "no picture arrived after the seek");
                assertTrue(first.ptsMicros() >= target,
                        "landed on " + first.ptsMicros() + ", before the target " + target);
                first.release();

                int shown = 1;
                for (int step = 0; step < 600 && shown < 20; step++) {
                    player.decodeStep();
                    VideoFrame picture = player.takePicture();
                    if (picture != null) {
                        picture.release();
                        shown++;
                    }
                    hand.advanceMicros(33_333);
                }
                assertEquals(20, shown, "playback carried on from where the seek landed");
                assertNull(player.failure(), () -> "the decode thread failed: " + player.failure());
            } finally {
                player.close();
            }
        }
    }

    /**
     * A seek arriving while a looping player has already wrapped. The pass bookkeeping and the seek
     * both move the timeline, and a seek read as a wrap (or a wrap read as a seek) leaves the
     * player either dropping the first picture of every pass or never producing another one.
     */
    @Test
    void aSeekDuringALoopIsNotAWrap() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 20, 0);

        try (FfmpegMedia media = FfmpegMedia.open(clip, false, FfmpegMedia.DEFAULT_SLOTS)) {
            Hand hand = new Hand();
            MediaPlayer player = new MediaPlayer(media.video())
                    .setOwnsDecodeThread(false)
                    .setRingCapacity(3)
                    .setClock(new VideoClock(hand::get));
            try {
                player.setLooping(true);
                player.start();

                int shown = 0;
                for (int step = 0; step < 1500 && shown < 30; step++) {
                    player.decodeStep();
                    VideoFrame picture = player.takePicture();
                    if (picture != null) {
                        picture.release();
                        shown++;
                    }
                    hand.advanceMicros(33_333);
                }
                assertEquals(30, shown, "the fixture needs a player that has already wrapped");

                player.seek(200_000, VideoStreamSource.SeekMode.EXACT);
                int afterSeek = 0;
                for (int step = 0; step < 1500 && afterSeek < 30; step++) {
                    player.decodeStep();
                    VideoFrame picture = player.takePicture();
                    if (picture != null) {
                        picture.release();
                        afterSeek++;
                    }
                    hand.advanceMicros(33_333);
                }
                assertEquals(30, afterSeek, "a seek mid-loop keeps looping and keeps producing");
                assertNull(player.failure(), () -> "the decode thread failed: " + player.failure());
            } finally {
                player.close();
            }
        }
    }

    /**
     * Closing while the decode thread is genuinely running and a seek has just been asked for:
     * the shutdown a window closing mid-play performs. What must hold is that {@code close()}
     * returns, so the container can be closed after it without a decode against a torn down
     * decoder.
     */
    @Test
    void closingMidSeekReturnsAndLeavesTheContainerClosable() throws IOException {
        FfmpegTests.requireWriter();
        Path clip = FfmpegTests.clip(directory, FfmpegMedia.ClipCodec.MPEG4, 160, 120, 120, 0);

        FfmpegMedia media = FfmpegMedia.open(clip, false, FfmpegMedia.DEFAULT_SLOTS);
        MediaPlayer player = new MediaPlayer(media.video()).setRingCapacity(3);
        try {
            player.start();
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (player.decodedFrames() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(player.decodedFrames() > 0, "the decode thread never produced anything");
            player.seek(1_000_000, VideoStreamSource.SeekMode.EXACT);
            player.close(); // joins the decode thread, seek pending or not
        } finally {
            media.close();
        }
        assertFalse(media.isOpen());
    }
}
