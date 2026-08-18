package limn.components;

import limn.graphics.Color;
import limn.scene.Constraints;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.video.VideoClock;
import limn.video.VideoSurfaces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VideoView} with no GPU: a fake surface provider, a pooled stream with no decoder, and a
 * canvas that records {@code drawSurface}. What is asserted here is everything a user of the widget
 * can see: the box it asks for, where the picture lands inside it, that a picture is uploaded once
 * per frame and released exactly once, that a hidden view costs nothing, and what each way a stream
 * can end actually draws.
 */
class VideoViewTest extends ComponentTestBase {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private TestVideoSurfaces surfaces;

    @BeforeEach
    void installSurfaces() {
        surfaces = new TestVideoSurfaces();
        VideoSurfaces.install(surfaces);
    }

    @AfterEach
    void uninstallSurfaces() {
        // The registry is process-wide and outlives this class.
        VideoSurfaces.uninstall(surfaces);
    }

    /** A clock a test moves by hand, so a whole stream is paced with no real time passing. */
    private VideoClock testClock() {
        return new VideoClock(nanos::get);
    }

    private void advanceMillis(long millis) {
        nanos.addAndGet(millis * 1_000_000L);
    }

    // ------------------------------------------------------------------ measure

    @Test
    void aViewWithNoStreamAsksForNothing() {
        VideoView view = new VideoView();
        assertEquals(new Size(0, 0), view.measure(Constraints.loose(800, 600)),
                "nothing to show is nothing to reserve");
    }

    @Test
    void aStreamIsMeasuredAtOnePointPerPixelBeforeAnyPictureExists() {
        VideoView view = new VideoView(new TestVideoStream(640, 360));
        assertEquals(new Size(640, 360), view.measure(Constraints.loose(4000, 4000)),
                "the size is final at open, which is what lets a view be laid out before it decodes");
    }

    @Test
    void aPreferredSizeReplacesTheStreamsOwn() {
        VideoView view = new VideoView(new TestVideoStream(1920, 1080)).setPreferredSize(480, 270);
        assertEquals(new Size(480, 270), view.measure(Constraints.loose(4000, 4000)));
    }

    @Test
    void aPreferredSizeReservesTheBoxBeforeAStreamExists() {
        VideoView view = new VideoView().setPreferredSize(320, 180);
        assertEquals(new Size(320, 180), view.measure(Constraints.loose(4000, 4000)));
    }

    @Test
    void constraintsThatCannotHoldThePictureClampIt() {
        VideoView view = new VideoView(new TestVideoStream(1920, 1080));
        assertEquals(new Size(300, 200), view.measure(Constraints.tight(300, 200)),
                "a tight box wins outright");
        view.markNeedsLayout();
        assertEquals(new Size(400, 1080),
                view.measure(new Constraints(0, 400, 0, Constraints.UNBOUNDED_LIMIT)),
                "one clamped axis does not drag the other: the letterbox restores the ratio");
    }

    // ------------------------------------------------------------------ letterbox

    /** Lays a view out alone in a box and renders one frame; returns the canvas that recorded it. */
    private RecordingTestCanvas paintInBox(VideoView view, float boxW, float boxH, float scale) {
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(boxW, boxH);
        RecordingTestCanvas canvas = new RecordingTestCanvas(boxW, boxH);
        canvas.contentScale = scale;
        scene.renderFrame(canvas);
        return canvas;
    }

    @Test
    void aSixteenByNinePictureInASixteenByNineBoxHasNoBarsAtAll() {
        VideoView view = new VideoView(new TestVideoStream(640, 360)).setClock(testClock());
        RecordingTestCanvas.SurfaceDraw draw = paintInBox(view, 320, 180, 1).onlySurface();
        assertEquals(0f, draw.x(), "a matching ratio must not leave a hairline on one edge");
        assertEquals(0f, draw.y());
        assertEquals(320f, draw.width());
        assertEquals(180f, draw.height());
    }

    @Test
    void aWidePictureInASquareBoxIsLetterboxed() {
        VideoView view = new VideoView(new TestVideoStream(640, 360)).setClock(testClock());
        RecordingTestCanvas.SurfaceDraw draw = paintInBox(view, 400, 400, 1).onlySurface();
        assertEquals(0f, draw.x());
        assertEquals(400f, draw.width());
        assertEquals(225f, draw.height());
        assertEquals(87.5f, draw.y(), "the bars split evenly");
    }

    @Test
    void aTallPictureInASquareBoxIsPillarboxed() {
        VideoView view = new VideoView(new TestVideoStream(360, 640)).setClock(testClock());
        RecordingTestCanvas.SurfaceDraw draw = paintInBox(view, 400, 400, 1).onlySurface();
        assertEquals(0f, draw.y());
        assertEquals(400f, draw.height());
        assertEquals(225f, draw.width());
        assertEquals(87.5f, draw.x());
    }

    @Test
    void anOddPictureSizeStaysInsideTheBox() {
        // 641x361: every chroma plane rounds up, and the ratio is not a round number either.
        VideoView view = new VideoView(new TestVideoStream(641, 361)).setClock(testClock());
        RecordingTestCanvas.SurfaceDraw draw = paintInBox(view, 400, 400, 1).onlySurface();
        assertEquals(0f, draw.x());
        assertEquals(400f, draw.width());
        assertEquals(400f * 361f / 641f, draw.height(), 1e-3f);
        assertTrue(draw.y() >= 0 && draw.y() + draw.height() <= 400 + 1e-3f,
                "the picture must not leave the box: " + draw);
    }

    @Test
    void coverFillsTheBoxAndTheOverflowIsClippedAway() {
        VideoView view = new VideoView(new TestVideoStream(640, 360))
                .setClock(testClock()).setFit(VideoView.Fit.COVER);
        RecordingTestCanvas.SurfaceDraw draw = paintInBox(view, 400, 400, 1).onlySurface();
        assertEquals(400f, draw.height(), "cover binds on the short axis");
        assertEquals(400f * 640f / 360f, draw.width(), 1e-3f);
        assertTrue(draw.x() < 0, "the overflow hangs out of both sides: " + draw);
        assertEquals(0f, draw.y());
    }

    @Test
    void fillStretchesToTheBox() {
        VideoView view = new VideoView(new TestVideoStream(640, 360))
                .setClock(testClock()).setFit(VideoView.Fit.FILL);
        RecordingTestCanvas.SurfaceDraw draw = paintInBox(view, 400, 400, 1).onlySurface();
        assertEquals(0f, draw.x());
        assertEquals(0f, draw.y());
        assertEquals(400f, draw.width());
        assertEquals(400f, draw.height());
    }

    @Test
    void theRectangleIsInPointsAndDoesNotMoveWithTheContentScale() {
        VideoView one = new VideoView(new TestVideoStream(640, 360)).setClock(testClock());
        RecordingTestCanvas.SurfaceDraw at1 = paintInBox(one, 400, 400, 1).onlySurface();
        VideoView two = new VideoView(new TestVideoStream(640, 360)).setClock(testClock());
        RecordingTestCanvas.SurfaceDraw at2 = paintInBox(two, 400, 400, 2).onlySurface();
        assertEquals(at1.x(), at2.x(), "the destination is in points; the device pixels are the surface's");
        assertEquals(at1.y(), at2.y());
        assertEquals(at1.width(), at2.width());
        assertEquals(at1.height(), at2.height());
    }

    @Test
    void theSurfaceIsNeverResizedByTheView() {
        // TestVideoSurfaces.Surface.resize throws: the picture is the size the stream made it.
        VideoView view = new VideoView(new TestVideoStream(640, 360)).setClock(testClock());
        paintInBox(view, 400, 400, 1);
        assertEquals(1, surfaces.totalUploads());
    }

    // ------------------------------------------------------------------ upload and release

    @Test
    void aPictureIsUploadedOnceAFrameHoweverManyPassesPaintIt() {
        TestVideoStream stream = new TestVideoStream(640, 360);
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 400);
        RecordingTestCanvas canvas = new RecordingTestCanvas(400, 400);
        scene.renderFrame(canvas);
        assertEquals(1, surfaces.totalUploads());
        assertEquals(1, canvas.surfaces.size());

        // A partial frame paints one pass per damage rectangle: the second pass must draw the same
        // texels, not upload over the quad the first one queued.
        view.paintWidget(canvas);
        assertEquals(1, surfaces.totalUploads(), "the second pass of one frame must not upload");
        assertEquals(2, canvas.surfaces.size(), "but it must still draw");
    }

    @Test
    void everyPictureIsReleasedExactlyOnce() {
        TestVideoStream stream = new TestVideoStream(640, 360);
        stream.timed = false; // one picture per repaint, so several change hands quickly
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 400);
        RecordingTestCanvas canvas = new RecordingTestCanvas(400, 400);
        for (int frame = 0; frame < 6; frame++) {
            canvas.reset();
            scene.renderFrame(canvas);
        }
        assertTrue(surfaces.totalUploads() >= 5, "pictures kept arriving: " + surfaces.totalUploads());
        assertEquals(stream.delivered, surfaces.totalUploads(),
                "every delivered picture reached the device");
        assertEquals(stream.slots(), stream.freeSlots(),
                "a picture never handed back costs the producer a slot for good");
    }

    @Test
    void anUntimedStreamShowsOnePicturePerRepaint() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.timed = false; // PTS_UNKNOWN: the clock refuses to time it, so the view does not try
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        for (int frame = 0; frame < 4; frame++) {
            scene.renderFrame(canvas);
        }
        assertEquals(4, surfaces.totalUploads());
    }

    // ------------------------------------------------------------------ pacing

    @Test
    void aPictureIsHeldUntilItsMomentAndThenShown() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.ptsStepMicros = 33_333; // 30 per second
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);

        scene.renderFrame(canvas);
        assertEquals(1, surfaces.totalUploads(), "the first picture anchors the timeline and shows");

        for (int frame = 0; frame < 4; frame++) {
            advanceMillis(2); // a 500 Hz repaint: still inside the first picture's interval
            scene.renderFrame(canvas);
        }
        assertEquals(1, surfaces.totalUploads(),
                "a repaint faster than the stream must not show the stream faster");

        advanceMillis(40);
        scene.renderFrame(canvas);
        assertEquals(2, surfaces.totalUploads(), "past its presentation time it is shown");
    }

    @Test
    void aViewThatCannotKeepUpDropsRatherThanPlayingSlowly() {
        // The defect this pins: without a successor to name, the clock may never drop, and a view
        // that cannot drop shows every picture however late it is, which is not a stutter but the
        // whole stream playing slower than itself, with the position readout running away from the
        // picture. A 120-per-second stream repainted 30 times a second is the smallest honest case.
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.ptsStepMicros = 8_333;
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        scene.renderFrame(canvas); // the first picture anchors the timeline at its own time

        int repaints = 20;
        for (int frame = 0; frame < repaints; frame++) {
            advanceMillis(33);
            scene.renderFrame(canvas);
        }

        long elapsed = repaints * 33_000L;
        assertEquals(elapsed, surfaces.lastPtsMicros(), 3 * stream.ptsStepMicros,
                "the picture on screen must be the one whose moment it is, not the "
                        + (repaints + 1) + "th picture of the stream");
        assertTrue(stream.delivered > repaints + 1,
                "catching up means reading past the pictures whose moment went by: "
                        + stream.delivered + " read for " + (repaints + 1) + " repaints");
    }

    @Test
    void aSingleSlotSourceStillPlaysWithoutDropping() {
        // The read-ahead is best-effort: a source that can lend only one picture at a time answers
        // PENDING to it forever, and the view must then behave exactly as it did before there was
        // one: no successor, therefore no drops, and certainly no stall.
        TestVideoStream stream = new TestVideoStream(64, 36, 1);
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);

        scene.renderFrame(canvas);
        for (int frame = 0; frame < 3; frame++) {
            advanceMillis(40);
            scene.renderFrame(canvas);
        }

        assertEquals(4, surfaces.totalUploads(), "one picture per interval, none dropped");
        assertEquals(4, stream.delivered,
                "and not one picture read that was not shown: with no slot to read ahead into, "
                        + "the read-ahead must cost nothing at all");
    }

    @Test
    void aPausedViewStopsAskingForFrames() {
        // A registered ticker asks the scene for a frame every frame (that is what an animation
        // is), so a view that keeps ticking through a pause holds the whole window at the display's
        // refresh rate to redraw a picture that cannot change, and the frames it asks for carry no
        // damage at all. Pausing must take the view out of the loop entirely.
        TestVideoStream stream = new TestVideoStream(64, 36);
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        StubWindow window = new StubWindow();
        scene.bind(window);
        scene.layoutPass(100, 100);
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        scene.renderFrame(canvas);

        view.setPaused(true);
        for (int frame = 0; frame < 5; frame++) {
            advanceMillis(16);
            scene.renderFrame(canvas);
        }
        int settled = window.framesRequested;
        for (int frame = 0; frame < 5; frame++) {
            advanceMillis(16);
            scene.renderFrame(canvas);
        }

        assertEquals(settled, window.framesRequested,
                "a paused view must ask for no frames of its own");

        // And resuming must not wait for anything: setPaused re-arms the ticker itself.
        view.setPaused(false);
        assertTrue(window.framesRequested > settled, "resuming asks for a frame straight away");
        advanceMillis(40);
        scene.renderFrame(canvas);
        assertEquals(2, surfaces.totalUploads(), "and the next picture is shown");
    }

    // ------------------------------------------------------------------ damage

    @Test
    void aPlayingViewDamagesOnlyItsOwnRectangle() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true).setPreferredSize(200, 120);
        // A column keeps the view at the size it asked for (cross alignment START), so the damage
        // this asserts on is genuinely smaller than the window rather than most of it.
        Column column = new Column();
        column.add(view);
        Scene scene = new Scene(new Padding(Insets.all(20), column), nanos::get);
        scene.setTextRuler(RULER);
        scene.setPartialRendering(true);
        scene.layoutPass(400, 400);

        RecordingTestCanvas canvas = new RecordingTestCanvas(400, 400);
        boolean settled = false;
        for (int i = 0; i < 10 && !settled; i++) {
            canvas.reset();
            scene.renderFrame(canvas);
            settled = canvas.nothingPainted();
        }
        assertTrue(settled, "a held picture must stop painting entirely");

        advanceMillis(40); // the next picture's moment arrives
        canvas.reset();
        scene.renderFrame(canvas);

        assertFalse(canvas.cleared, "a new picture is a partial frame, not a full one");
        assertNotNull(canvas.firstClip, "and it must actually repaint");
        // The view's own box, grown by the pixel of feathering every widget's damage carries.
        float left = view.localToSceneX() - 1;
        float top = view.localToSceneY() - 1;
        assertTrue(canvas.firstClip.x() >= left && canvas.firstClip.y() >= top
                        && canvas.firstClip.right() <= left + view.width() + 2
                        && canvas.firstClip.bottom() <= top + view.height() + 2,
                "damage must stay inside the view's box: " + canvas.firstClip);
    }

    // ------------------------------------------------------------------ showing and not

    @Test
    void aHiddenViewDecodesNothingAndUploadsNothing() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.timed = false;
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        scene.renderFrame(canvas);
        int readsWhileShowing = stream.reads;
        int uploadsWhileShowing = surfaces.totalUploads();

        view.setVisible(false);
        for (int frame = 0; frame < 5; frame++) {
            scene.renderFrame(canvas);
        }
        assertEquals(readsWhileShowing, stream.reads, "a hidden view must not decode");
        assertEquals(uploadsWhileShowing, surfaces.totalUploads(), "nor upload");

        view.setVisible(true);
        scene.renderFrame(canvas);
        scene.renderFrame(canvas);
        assertTrue(stream.reads > readsWhileShowing, "showing it again starts it");
        assertTrue(surfaces.totalUploads() > uploadsWhileShowing);
    }

    // ------------------------------------------------------------------ end states

    @Test
    void withNoGpuBackendItDrawsANoticeAndTouchesNoStream() {
        VideoSurfaces.uninstall(surfaces);
        TestVideoStream stream = new TestVideoStream(64, 36);
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        RecordingTestCanvas canvas = paintInBox(view, 200, 120, 1);
        assertEquals(0, stream.reads, "nothing can be shown, so nothing is decoded");
        assertTrue(canvas.surfaces.isEmpty());
        assertTrue(canvas.paints > 0, "the notice is drawn");
        assertTrue(surfaces.created.isEmpty());
    }

    @Test
    void aStreamWithNoPictureYetShowsTheBarsAndNoPicture() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.pendingOnce = true; // the decoder has not produced one
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        scene.renderFrame(canvas);
        assertEquals(1, stream.reads, "PENDING must be answered by asking again later, not by spinning");
        assertEquals(0, surfaces.totalUploads());
        assertTrue(canvas.surfaces.isEmpty(), "nothing is drawn where a picture is not yet");
        assertTrue(canvas.paints > 0, "but the box is still filled with the bars");

        canvas.reset();
        scene.renderFrame(canvas);
        assertEquals(1, surfaces.totalUploads(), "the next frame asks again and gets one");
    }

    @Test
    void aStreamThatEndsKeepsItsLastPictureAndStopsCosting() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.timed = false;
        stream.frameCount = 2;
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        for (int frame = 0; frame < 6; frame++) {
            scene.renderFrame(canvas);
        }
        assertTrue(view.isEnded());
        assertEquals(2, surfaces.totalUploads());
        int readsAtEnd = stream.reads;
        for (int frame = 0; frame < 4; frame++) {
            canvas.reset();
            scene.renderFrame(canvas);
        }
        assertEquals(readsAtEnd, stream.reads, "an ended view asks the stream for nothing more");
        assertEquals(1, canvas.surfaces.size(), "and the last picture is still on screen");
        assertEquals(0, stream.closes, "the stream is the caller's: the view never closes it");
    }

    @Test
    void aStreamThatCannotBeRewoundEndsWhateverLoopingSays() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.timed = false;
        stream.frameCount = 1;
        stream.rewindable = false;
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true).setLooping(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        for (int frame = 0; frame < 5; frame++) {
            scene.renderFrame(canvas);
        }
        assertTrue(view.isEnded());
        assertEquals(0, stream.resets, "a stream that says it cannot be rewound is not rewound");
        assertEquals(1, surfaces.totalUploads());
    }

    @Test
    void loopingRewindsAtTheEnd() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.timed = false;
        stream.frameCount = 2;
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true).setLooping(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        for (int frame = 0; frame < 8; frame++) {
            scene.renderFrame(canvas);
        }
        assertFalse(view.isEnded(), "looping never ends");
        assertTrue(stream.resets >= 1, "it rewound");
        assertTrue(surfaces.totalUploads() > 2, "and kept showing pictures");
    }

    @Test
    void aDecodeThatThrowsBecomesANoticeAndStopsTheStream() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        stream.failOnRead = new IllegalStateException("truncated stream");
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        scene.renderFrame(canvas);

        assertNotNull(view.failure());
        assertEquals("truncated stream", view.failure().getMessage());
        assertTrue(canvas.surfaces.isEmpty(), "no picture, so no quad");
        assertTrue(canvas.paints > 0, "a message instead");

        int readsAtFailure = stream.reads;
        for (int frame = 0; frame < 4; frame++) {
            scene.renderFrame(canvas);
        }
        assertEquals(readsAtFailure, stream.reads, "a failed view asks the stream for nothing more");

        stream.failOnRead = null;
        view.restart();
        scene.renderFrame(canvas);
        assertNull(view.failure(), "restart is how a view recovers");
        assertEquals(1, surfaces.totalUploads());
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void detachingReleasesThePictureAndHandsTheSurfaceToTheScene() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        Column column = new Column();
        column.add(view);
        Scene scene = new Scene(column, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(200, 200);
        RecordingTestCanvas canvas = new RecordingTestCanvas(200, 200);
        scene.renderFrame(canvas);
        TestVideoSurfaces.Surface first = surfaces.latest();
        assertNotNull(first);

        column.remove(view);
        assertFalse(first.disposed, "GPU disposal needs the context, and a detach is not in a frame");
        scene.renderFrame(canvas); // the scene drains its deferred disposals at the top of a frame
        assertTrue(first.disposed, "the surface is freed once the context is current");
        assertEquals(stream.slots(), stream.freeSlots(), "and any held picture went back");
        assertEquals(0, stream.closes, "the stream is the caller's");

        column.add(view);
        scene.layoutPass(200, 200);
        scene.renderFrame(canvas);
        TestVideoSurfaces.Surface second = surfaces.latest();
        assertNotSame(first, second, "a re-attached view builds a surface for the window it joined");
        assertTrue(second.uploads >= 1, "and shows the stream's next picture");
    }

    @Test
    void replacingTheStreamClosesNothingAndShowsTheNewOne() {
        TestVideoStream first = new TestVideoStream(640, 360);
        TestVideoStream second = new TestVideoStream(360, 640);
        VideoView view = new VideoView(first).setClock(testClock()).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 400);
        RecordingTestCanvas canvas = new RecordingTestCanvas(400, 400);
        scene.renderFrame(canvas);
        assertEquals(400f, canvas.onlySurface().width());

        view.setSource(second);
        assertEquals(0, first.closes, "the previous stream is the caller's to close");
        assertSame(second, view.source());
        canvas.reset();
        scene.renderFrame(canvas);
        assertEquals(400f, canvas.onlySurface().height(), "the new stream's ratio, not the old one's");
        assertEquals(225f, canvas.onlySurface().width(), 1e-3f);
    }

    @Test
    void restartRewindsTheStreamAndTheTimeline() {
        TestVideoStream stream = new TestVideoStream(64, 36);
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        Scene scene = new Scene(view, nanos::get);
        scene.setTextRuler(RULER);
        scene.layoutPass(100, 100);
        RecordingTestCanvas canvas = new RecordingTestCanvas(100, 100);
        advanceMillis(40);
        scene.renderFrame(canvas);
        advanceMillis(40);
        scene.renderFrame(canvas);
        assertEquals(2, surfaces.totalUploads());

        view.restart();
        scene.renderFrame(canvas);
        assertEquals(1, stream.resets);
        assertEquals(3, surfaces.totalUploads(), "the first picture of the new pass shows at once");
    }

    /**
     * The background cannot be turned off, and that is a deliberate removal: it used to be possible
     * to pass null and have the view paint nothing behind the picture. The box is reserved on every
     * path (no stream, no backend, a decode that threw), so a view painting nothing is a hole in
     * the window that shows whatever is behind it, and every one of those paths is a state a real
     * application spends time in.
     */
    @Test
    void theBackgroundCannotBeTurnedOff() {
        VideoView view = new VideoView();
        assertEquals(Color.BLACK, view.letterboxColor(), "opaque black until told otherwise");
        assertThrows(NullPointerException.class, () -> view.setLetterboxColor(null));
        assertThrows(IllegalArgumentException.class,
                () -> view.setLetterboxColor(Color.TRANSPARENT));
        assertThrows(IllegalArgumentException.class,
                () -> view.setLetterboxColor(Color.rgba(0xFF0000, 0f)));
        // A partly transparent one tints what is behind rather than deleting it, and is allowed.
        view.setLetterboxColor(Color.rgba(0x202020, 0.5f));
        assertEquals(0.5f, view.letterboxColor().a(), 1e-6f);
    }

    /** With no stream, no player and nothing decoded, the box is still filled. */
    @Test
    void aViewWithNothingToShowStillPaintsItsBackground() {
        VideoView view = new VideoView();
        RecordingTestCanvas canvas = paintInBox(view, 400, 400, 1);
        assertTrue(canvas.paints >= 1,
                "an empty player paints its background; painting nothing leaves a hole");
    }

    /** And so does one whose decode threw, under the notice rather than instead of it. */
    @Test
    void aFailedViewPaintsTheBackgroundUnderItsNotice() {
        TestVideoStream stream = new TestVideoStream(640, 360);
        stream.failOnRead = new IllegalStateException("decode blew up");
        VideoView view = new VideoView(stream).setClock(testClock()).setAutoplay(true);
        RecordingTestCanvas canvas = paintInBox(view, 400, 400, 1);
        assertNotNull(view.failure(), "the read threw, so this is the failed path");
        assertTrue(canvas.paints >= 2,
                "the background, then the notice on top of it, not the notice alone");
    }
}
