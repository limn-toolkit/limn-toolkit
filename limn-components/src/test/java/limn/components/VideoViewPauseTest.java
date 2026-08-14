package limn.components;

import limn.scene.Scene;
import limn.video.MediaPlayer;
import limn.video.VideoClock;
import limn.video.VideoSurfaces;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Freezing the picture, which a transport control is the whole reason for. The two halves that can
 * be got wrong separately: a stream shown directly stops advancing, and a player is told rather
 * than the view quietly holding pictures the player goes on decoding and timing.
 */
class VideoViewPauseTest extends ComponentTestBase {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private TestVideoSurfaces surfaces;

    @BeforeEach
    void installSurfaces() {
        surfaces = new TestVideoSurfaces();
        VideoSurfaces.install(surfaces);
    }

    @AfterEach
    void uninstallSurfaces() {
        VideoSurfaces.uninstall(surfaces);
    }

    @Test
    void pausingAStreamStopsPicturesArriving() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        VideoView view = new VideoView(stream).setClock(new VideoClock(nanos::get)).setAutoplay(true);
        Scene scene = attach(view);

        renderFrames(scene, 4, 40_000_000L);
        int shownWhilePlaying = surfaces.totalUploads();
        assertTrue(shownWhilePlaying >= 2, "it was playing first: " + shownWhilePlaying);

        view.setPaused(true);
        assertTrue(view.isPaused());
        renderFrames(scene, 6, 40_000_000L);

        assertEquals(shownWhilePlaying, surfaces.totalUploads(),
                "a paused view shows the picture it had, however much wall time passes");
    }

    @Test
    void resumingCarriesOnRatherThanRestarting() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        VideoView view = new VideoView(stream).setClock(new VideoClock(nanos::get)).setAutoplay(true);
        Scene scene = attach(view);
        renderFrames(scene, 3, 40_000_000L);

        view.setPaused(true);
        long frozen = view.positionMicros();
        renderFrames(scene, 5, 40_000_000L);
        assertEquals(frozen, view.positionMicros(), "the paused span costs no media time");

        view.setPaused(false);
        assertFalse(view.isPaused());
        int before = surfaces.totalUploads();
        renderFrames(scene, 4, 40_000_000L);
        assertTrue(surfaces.totalUploads() > before, "pictures arrive again");
    }

    /**
     * The case the clock cannot answer: with no presentation times there is no pacing decision to
     * hold, so a view that asked the clock and nothing else would go on showing a picture per
     * repaint through the pause.
     */
    @Test
    void anUntimedStreamPausesToo() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.timed = false; // PTS_UNKNOWN: one picture per repaint, no clock in the path at all
        VideoView view = new VideoView(stream).setClock(new VideoClock(nanos::get)).setAutoplay(true);
        Scene scene = attach(view);

        renderFrames(scene, 3, 16_000_000L);
        int shown = surfaces.totalUploads();
        assertTrue(shown >= 2, "an untimed stream shows one picture per repaint: " + shown);

        view.setPaused(true);
        renderFrames(scene, 5, 16_000_000L);

        assertEquals(shown, surfaces.totalUploads(),
                "the pause is answered by the view, because no clock is consulted for this stream");
    }

    @Test
    void aPlayerIsToldRatherThanHavingItsPicturesHeldBack() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        MediaPlayer player = new MediaPlayer(stream).setOwnsDecodeThread(false);
        VideoView view = new VideoView().setPlayer(player);
        player.start();

        view.setPaused(true);

        assertSame(MediaPlayer.State.PAUSED, player.state(),
                "the sound freezes with the picture only if the player itself is paused");
        assertTrue(view.isPaused());

        view.setPaused(false);
        assertSame(MediaPlayer.State.PLAYING, player.state());
        assertFalse(view.isPaused());
        player.close();
    }

    /** A player paused by the application, not through the view, still reads as paused. */
    @Test
    void theViewReportsAPauseItDidNotCause() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        MediaPlayer player = new MediaPlayer(stream).setOwnsDecodeThread(false);
        VideoView view = new VideoView().setPlayer(player);
        player.start();
        assertFalse(view.isPaused());

        player.pause();

        assertTrue(view.isPaused(),
                "a control reading the view must see the state the player is actually in, or it "
                        + "will offer to pause something already paused");
        player.close();
    }

    @Test
    void aNewStreamDoesNotInheritThePreviousOnesPause() {
        // Two different questions, and only this one is inherited: what a view was told about the
        // stream it is showing says nothing about the next one. Whether the next one plays is
        // setAutoplay's answer, not the previous stream's.
        TestVideoStream first = new TestVideoStream(64, 36);
        VideoView view = new VideoView(first).setClock(new VideoClock(nanos::get)).setAutoplay(true);
        Scene scene = attach(view);
        renderFrames(scene, 2, 40_000_000L);
        view.setPaused(true);

        view.setSource(new TestVideoStream(64, 36));

        assertFalse(view.isPaused(), "the pause belonged to the stream that has been replaced");
        int before = surfaces.totalUploads();
        renderFrames(scene, 3, 40_000_000L);
        assertTrue(surfaces.totalUploads() > before);
    }

    @Test
    void aStreamStartsPausedAndStillShowsItsFirstPicture() {
        // The default, and the half of it that is easy to get wrong: paused must mean a still
        // frame, not an empty box, or every video panel opens as a hole where a picture goes.
        TestVideoStream stream = new TestVideoStream(64, 36);
        VideoView view = new VideoView(stream).setClock(new VideoClock(nanos::get));
        Scene scene = attach(view);

        assertTrue(view.isPaused(), "a video starts paused unless the application says otherwise");
        renderFrames(scene, 5, 40_000_000L);

        assertEquals(1, surfaces.totalUploads(),
                "the first picture is shown and held; nothing after it is");

        view.setPaused(false);
        renderFrames(scene, 3, 40_000_000L);
        assertTrue(surfaces.totalUploads() > 1, "and it plays when it is asked to");
    }

    private Scene attach(VideoView view) {
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);
        return scene;
    }

    private void renderFrames(Scene scene, int frames, long stepNanos) {
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        for (int frame = 0; frame < frames; frame++) {
            canvas.reset();
            scene.renderFrame(canvas);
            nanos.addAndGet(stepNanos);
        }
    }
}
