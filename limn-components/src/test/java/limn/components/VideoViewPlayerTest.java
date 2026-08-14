package limn.components;

import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.layout.Column;
import limn.video.MediaPlayer;
import limn.video.VideoClock;
import limn.video.VideoSurfaces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VideoView} driven by a {@link MediaPlayer} rather than by a stream directly. What is
 * asserted is that the player becomes the single answer to everything the view used to answer for
 * itself (pacing, the end, a failure, looping), that the upload discipline is unchanged, and that
 * the view still owns nothing it was given.
 *
 * <p>The player is given no decode thread so that every picture arrives exactly when this test says
 * it does.
 */
class VideoViewPlayerTest extends ComponentTestBase {

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

    private Scene sceneFor(VideoView view, float boxW, float boxH) {
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(boxW, boxH);
        return scene;
    }

    private void advanceMillis(long millis) {
        nanos.addAndGet(millis * 1_000_000L);
    }

    // ------------------------------------------------------------------ the seam

    @Test
    void aPlayerAndAStreamAreMutuallyExclusive() {
        TestVideoStream stream = new TestVideoStream(640, 360);
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView(stream);
        assertSame(stream, view.source());
        assertNull(view.player());

        view.setPlayer(player);
        assertSame(player, view.player());
        assertNull(view.source(), "two things pacing one picture is two clocks disagreeing");

        view.setSource(stream);
        assertSame(stream, view.source());
        assertNull(view.player());
        player.close();
    }

    @Test
    void thePlayersClockIsTheOneThatPacesAndSettingAnotherIsRefusedLoudly() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView();
        VideoClock ownClock = view.clock();

        view.setPlayer(player);
        assertSame(player.clock(), view.clock(),
                "one answer, not two that can disagree about when a picture is due");
        assertNotSame(ownClock, view.clock());
        assertThrows(IllegalStateException.class, () -> view.setClock(new VideoClock()),
                "silently ignoring it would leave the caller holding a clock that paces nothing");

        view.setPlayer(null);
        assertSame(ownClock, view.clock(), "the view's own clock is back");
        player.close();
    }

    @Test
    void theBoxIsMeasuredFromThePlayersStreamBeforeAnyPictureExists() {
        TestVideoStream stream = new TestVideoStream(1280, 720);
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView().setPlayer(player);
        assertEquals(new Size(1280, 720), view.measure(Constraints.loose(4000, 4000)),
                "a player's stream answers its size at open, exactly as a stream given directly does");
        player.close();
    }

    // ------------------------------------------------------------------ pictures

    @Test
    void thePlayerHandsOverAPictureAndTheViewUploadsItOncePerFrame() {
        TestVideoStream stream = new TestVideoStream(640, 360);
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView().setPlayer(player);
        Scene scene = sceneFor(view, 320, 180);
        player.start();
        player.decodeStep();

        RecordingTestCanvas canvas = new RecordingTestCanvas(320, 180);
        scene.renderFrame(canvas);
        assertEquals(1, surfaces.totalUploads(), "the first frame shows a picture, not a blank box");
        assertEquals(1, canvas.surfaces.size());

        // A partial frame paints one pass per damage rectangle. A player changes nothing about
        // that: the second pass draws the same texels rather than uploading over a queued quad.
        view.paintWidget(canvas);
        assertEquals(1, surfaces.totalUploads(), "the second pass of one frame must not upload");
        assertEquals(2, canvas.surfaces.size(), "but it must still draw");

        player.close();
        assertEquals(stream.slots(), stream.freeSlots(), "every picture went back exactly once");
    }

    @Test
    void thePicturesArePacedByThePlayerAndNotByTheRepaintRate() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.ptsStepMicros = 33_333; // 30 per second
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView().setPlayer(player);
        Scene scene = sceneFor(view, 100, 100);
        player.start();
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);

        for (int i = 0; i < 3; i++) {
            player.decodeStep();
        }
        scene.renderFrame(canvas);
        assertEquals(1, surfaces.totalUploads(), "the first picture");

        // Six repaints inside one picture interval must show one picture, not six.
        for (int frame = 0; frame < 6; frame++) {
            advanceMillis(2);
            scene.renderFrame(canvas);
        }
        assertEquals(1, surfaces.totalUploads(),
                "a repaint is not a moment: the player decides when the next picture is due");

        advanceMillis(30);
        scene.renderFrame(canvas);
        assertEquals(2, surfaces.totalUploads(), "and at its moment, it is shown");

        player.close();
        assertEquals(stream.slots(), stream.freeSlots());
    }

    @Test
    void aViewShowingAPlayerWithNothingReadyKeepsThePictureItHas() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView().setPlayer(player);
        Scene scene = sceneFor(view, 100, 100);
        player.start();
        player.decodeStep();

        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        scene.renderFrame(canvas);
        assertEquals(1, surfaces.totalUploads());

        // The decoder falls behind: nothing new for several frames. The picture on screen stays,
        // and the box does not blank.
        advanceMillis(200);
        for (int frame = 0; frame < 4; frame++) {
            canvas.reset();
            scene.renderFrame(canvas);
        }
        assertEquals(1, surfaces.totalUploads(), "nothing new was uploaded");
        assertEquals(1, canvas.surfaces.size(), "but the picture is still drawn");
        assertTrue(player.underruns() > 0, "and the player counted the dry spell");

        player.close();
    }

    // ------------------------------------------------------------------ delegation

    @Test
    void theEndIsThePlayersAnswerAndItStopsTheTicker() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.frameCount = 2;
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView().setPlayer(player);
        Scene scene = sceneFor(view, 100, 100);
        player.start();
        for (int i = 0; i < 4; i++) {
            player.decodeStep();
        }
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        for (int frame = 0; frame < 4; frame++) {
            advanceMillis(40);
            scene.renderFrame(canvas);
        }

        assertTrue(view.isEnded(), "the view reports the player's end, not one of its own");
        assertEquals(2, surfaces.totalUploads(), "both pictures were shown");

        // The last picture stays on screen and nothing more is asked for.
        canvas.reset();
        advanceMillis(200);
        scene.renderFrame(canvas);
        assertEquals(2, surfaces.totalUploads());
        assertEquals(1, canvas.surfaces.size(), "an ended view keeps drawing what it has");

        player.close();
        assertEquals(stream.slots(), stream.freeSlots());
    }

    @Test
    void aDecodeThatThrewOnThePlayersThreadIsWhatTheViewShows() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        RuntimeException boom = new IllegalStateException("bitstream is nonsense");
        stream.failOnRead = boom;
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView().setPlayer(player);
        Scene scene = sceneFor(view, 100, 100);
        player.start();
        player.decodeStep();

        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        scene.renderFrame(canvas);

        assertSame(boom, view.failure(),
                "a decode thread belongs to no widget, so the view is where a user sees it");
        assertEquals(0, canvas.surfaces.size(), "the notice is drawn instead of a picture");
        assertEquals(0, surfaces.totalUploads());

        // A restart is how it recovers, and it goes through the player.
        stream.failOnRead = null;
        view.restart();
        assertNull(view.failure());
        assertEquals(1, stream.resets, "the view's restart rewound the player's stream");
        player.decodeStep();
        scene.renderFrame(canvas);
        assertEquals(1, surfaces.totalUploads(), "and it plays again");

        player.close();
        assertEquals(stream.slots(), stream.freeSlots());
    }

    @Test
    void loopingIsThePlayersToDoAndTheViewOnlyAsksForIt() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.frameCount = 2;
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView().setPlayer(player);

        assertFalse(view.isLooping());
        view.setLooping(true);
        assertTrue(player.isLooping(), "the player owns the decode, so it owns the rewind");
        assertTrue(view.isLooping(), "and the view reports the player's answer, not a stale copy");

        Scene scene = sceneFor(view, 100, 100);
        player.start();
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        for (int frame = 0; frame < 8; frame++) {
            for (int i = 0; i < 3; i++) {
                player.decodeStep();
            }
            advanceMillis(40);
            scene.renderFrame(canvas);
        }
        assertTrue(stream.resets > 0, "the stream came round again");
        assertFalse(view.isEnded(), "a looping player never ends");

        player.close();
        assertEquals(stream.slots(), stream.freeSlots());
    }

    // ------------------------------------------------------------------ ownership

    @Test
    void detachingReleasesWhatTheViewOwnsAndStopsNothingElse() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView().setPlayer(player);
        Column root = new Column();
        root.add(view);
        Scene scene = new Scene(root, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(200, 200);
        player.start();
        player.decodeStep();
        scene.renderFrame(new RecordingTestCanvas(200, 200));
        assertEquals(1, surfaces.totalUploads());

        root.remove(view);
        scene.renderFrame(new RecordingTestCanvas(200, 200)); // drains the deferred disposal

        assertEquals(MediaPlayer.State.PLAYING, player.state(),
                "a detached view must not stop a player: the player is the application's, and a "
                        + "soundtrack that stopped when a tab was clicked is not what it is for");
        assertEquals(0, stream.closes, "and nothing closes the caller's stream");
        assertSame(player, view.player());
        assertTrue(surfaces.latest().disposed, "the device-side picture is the view's own to free");

        player.close();
        assertEquals(stream.slots(), stream.freeSlots(),
                "the picture the view was holding at the detach went back");
    }

    @Test
    void closingThePlayerLeavesTheViewShowingTheLastPictureRatherThanFailing() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        MediaPlayer player = manualPlayer(stream);
        VideoView view = new VideoView().setPlayer(player);
        Scene scene = sceneFor(view, 100, 100);
        player.start();
        player.decodeStep();
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        scene.renderFrame(canvas);
        assertEquals(1, surfaces.totalUploads());

        player.close();
        canvas.reset();
        advanceMillis(100);
        scene.renderFrame(canvas);

        assertNull(view.failure(), "a closed player is not a broken video");
        assertEquals(1, canvas.surfaces.size(), "the last picture stays on screen");
        assertEquals(1, surfaces.totalUploads());
        assertEquals(stream.slots(), stream.freeSlots());
    }
}
