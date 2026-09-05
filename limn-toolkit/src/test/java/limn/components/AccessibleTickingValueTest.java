package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.video.VideoClock;
import limn.video.VideoSurfaces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one rule ADR 039 §6 states for a value that advances on its own, over the widget the rule was
 * written for: a {@link VideoView} played for a simulated minute publishes about sixty trees rather
 * than about three thousand six hundred.
 *
 * <p>A playing view invalidates on every picture it presents, so the walk runs at the display's rate
 * whatever this test asserts &mdash; that part is not bought back and is the honest cost. What is
 * bought back is the copy and the events: a position published raw would differ on essentially every
 * frame, and the difference would copy the whole tree, for the length of the film, with the user
 * doing nothing at all. The widget rounds instead, because a widget knows what its value means and
 * the difference does not.
 *
 * <p>It lives in {@code limn.components} rather than beside the scene's own accessibility tests
 * because the stream and the surface provider it needs, {@link TestVideoStream} and
 * {@link TestVideoSurfaces}, are package-private here; and because what it pins is a widget's
 * decision rather than the mechanism's.
 */
class AccessibleTickingValueTest extends AccessibleComponentTestBase {

    /** Frames in a simulated minute at sixty a second. */
    private static final int FRAMES = 3600;

    /** Nanoseconds per frame at sixty a second, which is what the fake wall clock moves by. */
    private static final long FRAME_NANOS = 16_666_667L;

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

    @Test
    void aMinuteOfPlaybackPublishesAboutSixtyTreesAndNotThreeThousandSixHundred() {
        TestVideoStream stream = new TestVideoStream(640, 360);
        stream.durationMicros = 600_000_000L; // ten minutes, so nothing ends inside this test
        VideoView view = new VideoView().setClock(new VideoClock(nanos::get));
        view.setSource(stream);
        bind(view);
        view.setPaused(false);
        frame();

        int published = bridge.published.size();
        long changes = bridge.countOf(AccessibleEvent.Type.VALUE_CHANGED);
        long id = node(Accessible.Role.VIDEO).id();

        for (int i = 0; i < FRAMES; i++) {
            nanos.addAndGet(FRAME_NANOS);
            frame();
        }

        int trees = bridge.published.size() - published;
        long valueChanges = bridge.countOf(AccessibleEvent.Type.VALUE_CHANGED) - changes;
        assertTrue(trees > 40 && trees < 90,
                "a minute of playback is about sixty published trees; unrounded it would be about "
                        + FRAMES + ", and this one published " + trees);
        assertEquals(trees, valueChanges,
                "and every one of them is there because the second moved: nothing else about a "
                        + "playing view changes" + describe(tree()));
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.VALUE_CHANGED) {
                assertEquals(id, event.nodeId(), "on the video node and no other");
            }
        }

        AccessibleNode video = node(Accessible.Role.VIDEO);
        assertEquals(60d, video.value().value(), 2d,
                "and the position it settled on is the minute that passed" + describe(tree()));
        assertEquals(MediaControls.clock((long) video.value().value() * 1_000_000L),
                video.value().text(), "in the transport's own form" + describe(tree()));
    }
}
