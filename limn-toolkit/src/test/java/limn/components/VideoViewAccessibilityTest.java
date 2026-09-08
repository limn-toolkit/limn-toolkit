package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.ValueFacet;
import limn.testing.AllocationProbe;
import limn.video.VideoClock;
import limn.video.VideoStreamSource;
import limn.video.VideoSurfaces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link VideoView} becomes in the accessible tree: one {@code VIDEO} node that is always
 * there, over the box the layout gave it and never over the picture, named by the application or by
 * its tooltip and never by the class, described by whichever notice the paint would draw, and
 * carrying its position in whole seconds whenever the stream has a length to measure it against.
 *
 * <p>The view paints, so a hook that stopped declaring the role would have it deleted as scaffolding
 * with a warning naming a toolkit class in an application's log &mdash; and the recommendation that
 * warning makes, {@code setAccessibleIgnored(true)}, would take the transport with it. The walk's
 * log is captured around every case and checked after each, because it warns once per class for the
 * life of the virtual machine and whichever case ran first is the only one that would see it.
 *
 * <p>Six of the cases pin where ADR 039 §7's row was wrong or silent, and the sharpest of them is
 * the last: a decode that throws inside the <em>upload</em> is drawn by the very paint that
 * discovered it, declares no damage, and stops the ticker, so without the one
 * {@code invalidateAccessible()} the widget now makes there the pixels say the video cannot be
 * played and the tree says nothing at all. The row also presumed a duration the widget could always
 * answer, said nothing about read-only over a stream whose {@code seek} throws, listed no action
 * where there is one, and gave neither of the two notices the widget draws.
 *
 * <p>Every case drives the public API of {@link VideoView} and {@code Widget} on a bound scene, or
 * calls the scene from where a bridge stands, and reads back what the scene published. Nothing here
 * constructs a node.
 */
class VideoViewAccessibilityTest extends AccessibleComponentTestBase {

    /** A wall clock a test moves by hand, so a whole film is paced with no real time passing. */
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    private TestVideoSurfaces surfaces;
    private TestVideoStream stream;
    private VideoView view;

    /** Every record the walk logged while a case was running; see the class comment. */
    private final List<LogRecord> logged = new ArrayList<>();

    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private Logger walkLogger;

    @BeforeEach
    void installSurfacesAndCaptureTheWalksLog() {
        surfaces = new TestVideoSurfaces();
        VideoSurfaces.install(surfaces);
        walkLogger = Logger.getLogger("limn.scene.AccessibleWalk");
        walkLogger.addHandler(capture);
    }

    @AfterEach
    void theViewIsNeverNamedInAnApplicationsLog() {
        // The registry is process-wide and outlives this class.
        VideoSurfaces.uninstall(surfaces);
        walkLogger.removeHandler(capture);
        for (LogRecord record : logged) {
            String message = String.valueOf(record.getMessage())
                    + java.util.Arrays.toString(record.getParameters());
            assertFalse(message.contains("limn.components.VideoView"),
                    "the view paints its picture and its notices, so a hook that stopped declaring "
                            + "the role would have it deleted in silence and this class named in "
                            + "an application's log: " + message);
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /**
     * A view showing {@code source} as the scene's root, with one frame rendered.
     *
     * <p>The clock is installed <em>before</em> the source on purpose: installing a source is what
     * decides whether the view starts paused, and it says so to whichever clock it is holding at the
     * time, so a clock swapped in afterwards would be running under a view that believes it is
     * paused.
     *
     * @param source the stream to show, or null for a view holding nothing at all
     */
    private void bindView(TestVideoStream source) {
        stream = source;
        view = new VideoView().setClock(new VideoClock(nanos::get));
        if (source != null) {
            view.setSource(source);
        }
        bind(view);
    }

    /** @return the one video node in the current tree */
    private AccessibleNode video() {
        return node(Accessible.Role.VIDEO);
    }

    /** Advances the hand-driven wall clock and renders one frame. */
    private void playMillis(long millis) {
        nanos.addAndGet(millis * 1_000_000L);
        frame();
    }

    // -------------------------------------------------------------------------------- the node

    @Test
    void thereIsOneVideoNodeWhateverTheViewIsHolding() {
        bindView(null);

        AccessibleNode empty = video();
        long id = empty.id();
        assertEquals(400f, empty.width(),
                "a view holding nothing still publishes the box the layout gave it, which here is "
                        + "the whole window, this view being the root and laid out tight to it"
                        + describe(tree()));
        assertEquals(300f, empty.height(), describe(tree()));

        view.setSource(new TestVideoStream(640, 360));
        frame();
        assertEquals(id, video().id(),
                "a content node that appeared with the stream would throw away an application's "
                        + "name and its bound caption every time it went" + describe(tree()));

        view.setSource(null);
        frame();
        assertEquals(id, video().id(), "and again on the way out" + describe(tree()));
        assertEquals(Accessible.Role.VIDEO, video().role(),
                "the role is unconditional: with no source, with no backend, with a decode that "
                        + "threw" + describe(tree()));
    }

    @Test
    void theBoxIsTheWidgetsAndNeverThePicture() {
        stream = new TestVideoStream(640, 360);
        stream.pendingOnce = true; // so the first paint has no picture to upload
        bindView(stream);

        assertEquals(0, surfaces.totalUploads(), "no picture yet, which is the point of this case");
        AccessibleNode before = video();
        assertEquals(0f, before.x(), describe(tree()));
        assertEquals(0f, before.y(), describe(tree()));
        assertEquals(400f, before.width(), describe(tree()));
        assertEquals(300f, before.height(),
                "the box is what the layout reserved, and it exists before any picture does"
                        + describe(tree()));

        view.invalidate();
        frame();
        frame();
        assertTrue(surfaces.totalUploads() > 0, "a picture has arrived now");

        AccessibleNode after = video();
        assertEquals(300f, after.height(),
                "a 16:9 picture in a 4:3 box letterboxes to 400 × 225 under CONTAIN, and the node "
                        + "is still the box: the fitted quad is zero-sized until an upload and "
                        + "moves under a resolution change with no layout behind it"
                        + describe(tree()));
        assertEquals(0f, after.y(), "the bars are not part of anything a reader is told about");
    }

    // -------------------------------------------------------------------------------- the names

    @Test
    void everyNameIsTheApplicationsAndNoneIsTheClasss() {
        bindView(new TestVideoStream(640, 360));

        assertEquals("", video().name(),
                "the class holds no string that names it: the two it draws are statuses"
                        + describe(tree()));

        view.setTooltip("Trailer");
        frame();
        assertEquals("Trailer", video().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.TOOLTIP, video().nameFrom(),
                "the walk's free default, which is what names the demo's video");

        view.setAccessibleName("The Very Long Engagement");
        frame();
        assertEquals("The Very Long Engagement", video().name(), describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, video().nameFrom());
        assertEquals("Trailer", video().description(),
                "and the tooltip falls through to the description, the walk's other default"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the notices

    @Test
    void aMissingBackendIsADescriptionAndLeavesTheNameAlone() {
        VideoSurfaces.uninstall(surfaces);
        bindView(new TestVideoStream(640, 360));
        view.setTooltip("Trailer");
        frame();

        assertEquals(ComponentStrings.VIDEO_NO_BACKEND.get(), video().description(),
                "a reader told only \"video\" over a box reading \"Video unavailable\" has been "
                        + "told the wrong thing" + describe(tree()));
        assertEquals("Trailer", video().name(),
                "a status is not an identity, so it never displaces a name" + describe(tree()));
    }

    @Test
    void aDecodeThatThrewIsADescription() {
        stream = new TestVideoStream(640, 360);
        stream.failOnRead = new IllegalStateException("the decoder gave up");
        bindView(stream);
        frame();

        assertNotNull(view.failure(), "the first paint primes the view, which is what reads");
        assertEquals(ComponentStrings.VIDEO_DECODE_FAILED.get(), video().description(),
                describe(tree()));
    }

    @Test
    void withNoBackendAndAFailureTheBackendNoticeWins() {
        stream = new TestVideoStream(640, 360);
        stream.failOnRead = new IllegalStateException("the decoder gave up");
        bindView(stream);
        frame();
        assertNotNull(view.failure(), "a real failure, recorded while there was still a provider");

        VideoSurfaces.uninstall(surfaces);
        view.invalidate();
        frame();

        assertEquals(ComponentStrings.VIDEO_NO_BACKEND.get(), video().description(),
                "onPaint tests for a backend and returns before it ever reads failure(), so the "
                        + "hook has to read them the same way round or it describes a notice the "
                        + "paint would not draw" + describe(tree()));
    }

    @Test
    void anUploadThatThrewReachesTheTreeOnTheNextFrame() {
        bindView(new TestVideoStream(640, 360));
        assertTrue(surfaces.totalUploads() > 0, "the poster went up on the first paint");

        surfaces.latest().failOnUpload = new IllegalStateException("the device was lost");
        view.setPaused(false);
        for (int i = 0; i < 20 && view.failure() == null; i++) {
            playMillis(20);
        }
        assertNotNull(view.failure(), "an upload has to have thrown for this case to mean anything");

        int published = bridge.published.size();
        frame(); // nothing else damaged: the ticker has stopped and the paint returns early

        assertEquals(published + 1, bridge.published.size(),
                "the catch in uploadIfDue declares no damage and stops the ticker, so the one "
                        + "invalidateAccessible() there is the whole of what buys this frame"
                        + describe(tree()));
        assertEquals(ComponentStrings.VIDEO_DECODE_FAILED.get(), video().description(),
                describe(tree()));
    }

    // -------------------------------------------------------------------------------- the value

    @Test
    void thePositionIsWholeSecondsInTheTransportsOwnForm() {
        stream = new TestVideoStream(640, 360);
        stream.durationMicros = 90_000_000L;
        bindView(stream);
        view.seek(12_400_000L);
        frame();

        ValueFacet facet = video().value();
        assertNotNull(facet, "a seekable stream of a known length is a range" + describe(tree()));
        assertEquals(12d, facet.value(), "whole seconds, and never microseconds" + describe(tree()));
        assertEquals(0d, facet.min());
        assertEquals(90d, facet.max(), "the length in whole seconds, and not in microseconds");
        assertEquals(0d, facet.step(), "no step, because this class defines no skip interval");
        assertFalse(facet.readOnly(), "a seekable stream is a settable position");
        assertFalse(video().has(Accessible.State.READ_ONLY));
        assertEquals(MediaControls.clock(12_000_000L), facet.text(),
                "the transport's own m:ss of the same second, so the tree and the bar cannot say "
                        + "two different times" + describe(tree()));
        assertEquals("0:12", facet.text(), describe(tree()));
    }

    @Test
    void aStreamThatCannotBeSeekedPublishesTheSameRangeReadOnly() {
        stream = new TestVideoStream(640, 360);
        stream.durationMicros = 90_000_000L;
        stream.seekable = false;
        bindView(stream);

        ValueFacet facet = video().value();
        assertNotNull(facet, "the position is still worth reading" + describe(tree()));
        assertTrue(facet.readOnly(),
                "the facet's presence is what advertises a set, and seek() throws on this stream"
                        + describe(tree()));
        assertTrue(video().has(Accessible.State.READ_ONLY),
                "derived from the facet, which is why nothing sets it by hand" + describe(tree()));
    }

    @Test
    void aStreamWithNoLengthPublishesNoRangeAtAll() {
        stream = new TestVideoStream(640, 360);
        assertEquals(VideoStreamSource.DURATION_UNKNOWN, stream.durationMicros(),
                "a pipe, a live input, a container carrying no duration: the default");
        bindView(stream);

        assertNull(video().value(),
                "a range whose maximum is -1 is not a range, and this is the gate the transport "
                        + "already puts on its own scrub bar" + describe(tree()));
    }

    // ------------------------------------------------------------------------------- the action

    @Test
    void aSetSeeksExactlyWhereTheScrubBarSeeks() throws InterruptedException {
        stream = new TestVideoStream(640, 360);
        stream.durationMicros = 60_000_000L;
        bindView(stream);

        assertTrue(perform(video().id(), Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(12)), "the node is in the published tree");
        assertEquals(12_000_000L, stream.seekedTo, "seconds in, microseconds out");
        assertEquals(VideoStreamSource.SeekMode.EXACT, stream.seekedMode,
                "a reader's set is a settled position and not a drag");

        frame();
        assertEquals(12d, video().value().value(),
                "the view's own clock was re-anchored too, not just the stream" + describe(tree()));
    }

    @Test
    void aSetOutsideTheRangeIsClampedAndNeverThrows() throws InterruptedException {
        stream = new TestVideoStream(640, 360);
        stream.durationMicros = 60_000_000L;
        bindView(stream);
        long id = video().id();

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(999));
        assertEquals(60_000_000L, stream.seekedTo, "past the end lands on the end");

        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(-5));
        assertEquals(0L, stream.seekedTo,
                "and a negative lands at the start, where seek() would have thrown");

        int seeks = stream.seeks;
        perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(Double.NaN));
        perform(id, Accessible.Action.SET_VALUE,
                new Accessible.Argument.OfValue(Double.POSITIVE_INFINITY));
        assertEquals(seeks, stream.seeks,
                "a value that is not a number survives every clamp, so it is refused before one");
    }

    @Test
    void aSetOnAStreamThatCannotBeSeekedDoesNothingAndThrowsNothing()
            throws InterruptedException {
        stream = new TestVideoStream(640, 360);
        stream.durationMicros = 60_000_000L;
        stream.seekable = false;
        bindView(stream);

        perform(video().id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(12));
        assertEquals(0, stream.seeks,
                "the hook re-checks exactly what the facet advertised; unguarded, the stream's "
                        + "UnsupportedOperationException would come out of the posted task");
    }

    @Test
    void thereIsNoPlayPauseVerbAndTheViewIsNotATabStop() throws InterruptedException {
        bindView(new TestVideoStream(640, 360));
        boolean pausedBefore = view.isPaused();

        assertNull(video().actions(),
                "a parameterised set is advertised by the facet and never listed, and there is no "
                        + "parameterless verb here at all: the view handles neither mouse nor key "
                        + "and is not focusable, so a toggle would be an operation only a "
                        + "screen-reader user has" + describe(tree()));

        perform(video().id(), Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        perform(video().id(), Accessible.Action.PRESS, Accessible.Argument.NONE);
        assertEquals(pausedBefore, view.isPaused(),
                "and neither verb starts a soundtrack the application deliberately did not");
        assertFalse(video().has(Accessible.State.FOCUSABLE), describe(tree()));
    }

    // ---------------------------------------------------------------------------- the transport

    @Test
    void theTransportIsAChildThatSpeaksForItself() {
        bindView(new TestVideoStream(640, 360));
        view.setControlsVisible(true);
        frame();
        frame();

        AccessibleNode node = video();
        List<AccessibleNode> children = childrenOf(node);
        assertEquals(1, children.size(), "the bar is the only child this view ever adds"
                + describe(tree()));
        assertEquals(Accessible.Role.TOOL_BAR, children.get(0).role(), describe(tree()));
        assertTrue(children.get(0).has(Accessible.State.HORIZONTAL),
                "declared by the bar, which lays its controls in a row and only a row");
        assertEquals(List.of(), node.relations(),
                "and the view declares nothing back: CONTROLLED_BY would be present only for the "
                        + "bar containment already names, and absent whenever an application lays "
                        + "its own transport out as a sibling" + describe(tree()));
        List<Long> controlled = children.get(0).relations().stream()
                .filter(relation -> relation.kind() == Accessible.Relation.CONTROLLER_FOR)
                .map(AccessibleRelation::target).toList();
        assertEquals(List.of(node.id()), controlled,
                "the bar's own relation resolves now that the view publishes a node"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------- what it costs

    @Test
    void aQuietFrameOverAPosterAllocatesNothingAndPublishesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        stream = new TestVideoStream(640, 360);
        stream.durationMicros = 90_000_000L;
        bindView(stream);

        assertEquals(0, allocatedByADamagedFrame(() -> {
            view.invalidate();
            frame();
        }), "a paused view holding its poster publishes one unchanged second, and the one "
                + "careless line that would break this is the m:ss built inside the hook");
    }

    @Test
    void aPlayingFrameWithinTheSameSecondAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        stream = new TestVideoStream(640, 360);
        stream.durationMicros = 90_000_000L;
        bindView(stream);
        view.setPaused(false);
        frame();

        assertEquals(0, allocatedByADamagedFrame(() -> {
            view.invalidate();
            playMillis(1);
        }), "the position moves every frame and the whole second does not, which is the state a "
                + "film spends fifty-nine frames out of sixty in");
    }

    /**
     * @param oneFrame damages the view and renders, which is the loop under measurement
     * @return what one damaged frame costs with a reader attached over what the same frame costs
     *         with nobody listening. Measured as a difference rather than against zero: a headless
     *         frame has a floor of its own that has nothing to do with this widget, and a playing
     *         one decodes.
     */
    private long allocatedByADamagedFrame(Runnable oneFrame) {
        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            oneFrame.run();
        }, () -> {
            bridge.listening = false;
            oneFrame.run();
        }, 60);
        bridge.listening = true;
        return cost[0] - cost[1];
    }
}
