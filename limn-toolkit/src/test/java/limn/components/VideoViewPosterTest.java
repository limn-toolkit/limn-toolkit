package limn.components;

import limn.graphics.Image;
import limn.scene.Scene;
import limn.video.MediaPlayer;
import limn.video.Poster;
import limn.video.VideoClock;
import limn.video.VideoSurfaces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link VideoView} shows before it has played (ADR 069): the first picture by default, with
 * a stream and with a player nobody started; the picture at a time, with playback still starting at
 * the beginning; an image of the application's or nothing, with the stream left unread; and the
 * fallbacks when the stream cannot go where the poster asks.
 */
class VideoViewPosterTest extends ComponentTestBase {

    private static final long STEP = 33_333;

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

    private MediaPlayer manualPlayer(TestVideoStream stream) {
        return new MediaPlayer(stream)
                .setOwnsDecodeThread(false)
                .setRingCapacity(3)
                .setClock(new VideoClock(nanos::get));
    }

    private Scene sceneFor(VideoView view) {
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);
        return scene;
    }

    private RecordingTestCanvas render(Scene scene) {
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        scene.renderFrame(canvas);
        return canvas;
    }

    // ------------------------------------------------------------------ a player

    @Test
    void aPlayerNobodyStartedShowsItsFirstPictureAndStaysIdle() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView().setPlayer(player);
        Scene scene = sceneFor(view);
        assertTrue(player.isCued(), "installing an idle player cues the poster on it");

        player.decodeStep();
        render(scene);
        assertEquals(1, surfaces.totalUploads(), "a stopped video is a still frame, not a black box");
        assertEquals(0, surfaces.lastPtsMicros());
        assertEquals(MediaPlayer.State.IDLE, player.state(), "a poster is not playback");
        assertFalse(player.isCued(), "handed over, once");
        assertEquals(0, stream.seeks, "a fresh stream is already at its first picture");
        player.close();
    }

    @Test
    void aPlayersPosterAtATimeIsThatPictureAndPlayStartsAtTheBeginning() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView().setPoster(Poster.at(Duration.ofMillis(100))).setPlayer(player);
        Scene scene = sceneFor(view);

        player.decodeStep(); // the seek to the poster's time
        player.decodeStep(); // its picture
        render(scene);
        assertEquals(1, surfaces.totalUploads());
        assertEquals(4 * STEP, surfaces.lastPtsMicros(), "the first picture at or after 100 ms");

        view.setPaused(false);
        player.decodeStep(); // back to the beginning
        player.decodeStep();
        render(scene);
        assertEquals(2, surfaces.totalUploads());
        assertEquals(0, surfaces.lastPtsMicros(), "a poster is a cover, not a starting point");
        player.close();
    }

    @Test
    void aCuePastTheEndHandsNothingOver() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.frameCount = 3;
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView().setPoster(Poster.at(Duration.ofSeconds(1))).setPlayer(player);
        Scene scene = sceneFor(view);

        player.decodeStep();
        player.decodeStep();
        assertFalse(player.isCued(), "the stream ended with no picture to give");
        render(scene);
        assertEquals(0, surfaces.totalUploads(), "the bar colour stays");
        player.close();
    }

    // ------------------------------------------------------------------ a stream

    @Test
    void aStreamsPosterAtATimeIsThatPictureAndPlayStartsAtTheBeginning() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        VideoView view = new VideoView().setPoster(Poster.at(Duration.ofMillis(100))).setSource(stream);
        Scene scene = sceneFor(view);

        render(scene);
        assertEquals(1, surfaces.totalUploads());
        assertEquals(4 * STEP, surfaces.lastPtsMicros());

        view.setPaused(false);
        for (int frame = 0; frame < 3 && surfaces.totalUploads() < 2; frame++) {
            nanos.addAndGet(16_000_000L);
            render(scene);
        }
        assertEquals(0, stream.seekedTo, "rewound for playback");
        assertEquals(0, surfaces.lastPtsMicros(), "playback starts at the beginning");
    }

    @Test
    void aStreamThatCannotSeekShowsItsFirstPictureForAPosterAtATime() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.seekable = false;
        VideoView view = new VideoView().setPoster(Poster.at(Duration.ofMillis(100))).setSource(stream);
        render(sceneFor(view));
        assertEquals(1, surfaces.totalUploads());
        assertEquals(0, surfaces.lastPtsMicros());
        assertEquals(0, stream.seeks);
    }

    @Test
    void anImagePosterIsDrawnFittedAndTheStreamIsNotReadBeforePlay() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        Image cover = new Image(32, 18, new byte[32 * 18 * 4]);
        VideoView view = new VideoView().setPoster(Poster.image(cover)).setSource(stream);
        Scene scene = sceneFor(view);

        RecordingTestCanvas canvas = render(scene);
        assertEquals(0, stream.reads, "an image poster reads nothing");
        assertEquals(0, surfaces.totalUploads());
        assertEquals(1, canvas.images.size());
        assertEquals(100, canvas.images.get(0).width(), 0.01f, "contained, as the video would be");
        assertEquals(56.25f, canvas.images.get(0).height(), 0.01f);

        view.setPaused(false);
        nanos.addAndGet(16_000_000L);
        RecordingTestCanvas playing = render(scene);
        assertEquals(1, surfaces.totalUploads(), "playback's own picture replaces it");
        assertEquals(0, playing.images.size());
    }

    @Test
    void noPosterIsTheBarColourAndReadsNothing() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        VideoView view = new VideoView().setPoster(Poster.none()).setSource(stream);
        RecordingTestCanvas canvas = render(sceneFor(view));
        assertEquals(0, stream.reads);
        assertEquals(0, surfaces.totalUploads());
        assertEquals(0, canvas.images.size());
    }

    @Test
    void aStreamShowsItsFirstPictureByDefault() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        VideoView view = new VideoView(stream);
        render(sceneFor(view));
        assertEquals(1, surfaces.totalUploads());
        assertEquals(0, surfaces.lastPtsMicros());
        assertEquals(Poster.firstPicture(), view.poster());
    }
}
