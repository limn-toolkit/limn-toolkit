package limn.components;

import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.video.MediaPlayer;
import limn.video.VideoClock;
import limn.video.VideoStreamSource.SeekMode;
import limn.video.VideoSurfaces;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two things phase 7 added to the widget: a seek that puts the view's own state right along
 * with the stream's, and a recording made sideways being shown upright.
 */
class VideoViewSeekTest extends ComponentTestBase {

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

    // ------------------------------------------------------------------ seeking

    @Test
    void seekingAStreamMovesItAndReAnchorsThePacing() {
        TestVideoStream stream = new TestVideoStream(64, 48);
        VideoView view = new VideoView(stream).setClock(new VideoClock(nanos::get)).setAutoplay(true);
        paintInBox(view, 128, 96);

        view.seek(3_000_000, SeekMode.KEYFRAME);

        assertEquals(1, stream.seeks);
        assertEquals(3_000_000L, stream.seekedTo);
        assertSame(SeekMode.KEYFRAME, stream.seekedMode, "the mode reaches the stream");
        assertEquals(3_000_000L, view.positionMicros(),
                "the view's own clock was moved with the stream, or the next picture would be "
                        + "judged against a timeline the stream has left");
    }

    @Test
    void seekingClearsAnEndedState() {
        TestVideoStream stream = new TestVideoStream(64, 48);
        stream.frameCount = 1;
        stream.timed = false; // one picture per repaint, so the end is reached in a few frames
        VideoView view = new VideoView(stream).setClock(new VideoClock(nanos::get)).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(128, 96);
        RecordingTestCanvas canvas = new RecordingTestCanvas(128, 96);
        for (int frame = 0; frame < 6; frame++) {
            canvas.reset();
            scene.renderFrame(canvas);
        }
        assertTrue(view.isEnded(), "the fixture needs a stream that has ended");

        stream.frameCount = 20;
        view.seek(0, SeekMode.EXACT);
        assertFalse(view.isEnded(), "seeking out of the end is how a viewer replays a part");
    }

    @Test
    void seekingClearsAFailure() {
        TestVideoStream stream = new TestVideoStream(64, 48);
        stream.failOnRead = new IllegalStateException("decode blew up");
        VideoView view = new VideoView(stream).setClock(new VideoClock(nanos::get)).setAutoplay(true);
        paintInBox(view, 128, 96);
        org.junit.jupiter.api.Assertions.assertNotNull(view.failure());

        stream.failOnRead = null;
        view.seek(1_000_000, SeekMode.EXACT);
        org.junit.jupiter.api.Assertions.assertNull(view.failure(),
                "a decode that threw at one position says nothing about another");
    }

    @Test
    void aStreamThatRefusesIsAskedFirst() {
        TestVideoStream stream = new TestVideoStream(64, 48);
        stream.seekable = false;
        VideoView view = new VideoView(stream).setClock(new VideoClock(nanos::get)).setAutoplay(true);
        assertFalse(view.canSeek());
        assertThrows(UnsupportedOperationException.class, () -> view.seek(1_000_000));
        assertEquals(0, stream.seeks);
    }

    @Test
    void aViewWithNothingToShowCannotSeekAndDoesNotThrow() {
        VideoView view = new VideoView();
        assertFalse(view.canSeek());
        view.seek(1_000_000); // nothing to move, and nothing to complain about
    }

    @Test
    void aPlayerAnswersForTheView() {
        TestVideoStream stream = new TestVideoStream(64, 48);
        MediaPlayer player = new MediaPlayer(stream)
                .setOwnsDecodeThread(false)
                .setClock(new VideoClock(nanos::get));
        VideoView view = new VideoView().setPlayer(player);
        try {
            player.start();
            assertTrue(view.canSeek());
            view.seek(2_000_000, SeekMode.EXACT);
            assertEquals(2_000_000L, view.positionMicros(),
                    "the player's position is the view's, so a transport reads one number");
            player.decodeStep();
            assertEquals(1, stream.seeks, "the player's decode thread is what moved the stream");
        } finally {
            player.close();
        }
    }

    // ------------------------------------------------------------------ rotation

    @Test
    void aQuarterTurnSwapsTheMeasuredSize() {
        TestVideoStream stream = new TestVideoStream(640, 360);
        stream.rotation = 90;
        VideoView view = new VideoView(stream);
        Size measured = view.measure(Constraints.loose(4000, 4000));
        assertEquals(360f, measured.width(),
                "a recording made sideways asks for the box it will be seen in");
        assertEquals(640f, measured.height());
    }

    @Test
    void aHalfTurnDoesNotSwapTheMeasuredSize() {
        TestVideoStream stream = new TestVideoStream(640, 360);
        stream.rotation = 180;
        VideoView view = new VideoView(stream);
        Size measured = view.measure(Constraints.loose(4000, 4000));
        assertEquals(640f, measured.width());
        assertEquals(360f, measured.height());
    }

    @Test
    void anUnrotatedStreamMeasuresAsItAlwaysDid() {
        TestVideoStream stream = new TestVideoStream(640, 360);
        VideoView view = new VideoView(stream);
        Size measured = view.measure(Constraints.loose(4000, 4000));
        assertEquals(640f, measured.width());
        assertEquals(360f, measured.height());
    }

    @Test
    void aQuarterTurnLetterboxesByWhatWillBeSeen() {
        // Stored 640×360, displayed 360×640, in a 400×400 box: what fits is the DISPLAYED ratio,
        // so the picture is 225 across and 400 tall. Solving the letterbox in stored dimensions
        // would give 400×225 and put the picture outside the box once it was turned.
        TestVideoStream stream = new TestVideoStream(640, 360);
        stream.rotation = 90;
        VideoView view = new VideoView(stream).setClock(new VideoClock(nanos::get)).setAutoplay(true);
        RecordingTestCanvas.SurfaceDraw draw = paintInBox(view, 400, 400).onlySurface();

        // Drawn under the rotation, so the quad carries the stored extents about the origin.
        assertEquals(400f, draw.width(), 0.01f);
        assertEquals(225f, draw.height(), 0.01f);
        assertEquals(-200f, draw.x(), 0.01f, "centred on the rotation's origin");
        assertEquals(-112.5f, draw.y(), 0.01f);
    }

    @Test
    void anUnrotatedStreamIsStillDrawnInBoxCoordinates() {
        TestVideoStream stream = new TestVideoStream(640, 360);
        VideoView view = new VideoView(stream).setClock(new VideoClock(nanos::get)).setAutoplay(true);
        RecordingTestCanvas.SurfaceDraw draw = paintInBox(view, 400, 400).onlySurface();
        assertEquals(0f, draw.x(), "no rotation means no transform at all");
        assertEquals(87.5f, draw.y(), 0.01f);
        assertEquals(400f, draw.width());
        assertEquals(225f, draw.height());
    }

    private RecordingTestCanvas paintInBox(VideoView view, float boxW, float boxH) {
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(boxW, boxH);
        RecordingTestCanvas canvas = new RecordingTestCanvas(boxW, boxH);
        scene.renderFrame(canvas);
        return canvas;
    }
}
