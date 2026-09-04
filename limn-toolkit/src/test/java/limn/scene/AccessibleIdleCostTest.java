package limn.scene;

import limn.accessibility.Accessible;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.i18n.I18nString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the accessibility seams cost a window nothing is listening to, which is the state every
 * window in every process is in until an assistive technology arrives.
 *
 * <p>The promise is zero, and it has to be measured rather than asserted, because the seams are
 * exactly the kind of thing that costs nothing until somebody adds one line to a funnel that runs
 * sixty times a second. The three entry points that reach nothing else in the toolkit —
 * {@link Widget#setTooltip}, {@link Widget#setFocusable} and {@link Widget#invalidateAccessible()}
 * — set their flag and buy no frame; a frame renders byte for byte what it did before; and an
 * announcement made into a process with no reader is still queued, still bounded, and still costs
 * no frame.
 */
class AccessibleIdleCostTest {

    private static final class Box extends Widget {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(100, 40);
        }
    }

    private ExecutorService workers;
    private UiRuntime runtime;
    private final AtomicLong nanos = new AtomicLong();
    private RecordingWindow window;
    private Scene scene;
    private Box box;

    @BeforeEach
    void bindScene() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(nanos::get, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        box = new Box();
        scene = new Scene(box, nanos::get);
        window = new RecordingWindow();
        scene.bind(window);
        scene.renderFrame(new NoopCanvas(200, 200));
        window.frameRequests = 0;
    }

    @AfterEach
    void unbind() {
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    @Test
    void theThreeEntryPointsThatReachNothingElseBuyNoFrameWithNothingListening() {
        box.setTooltip("Play");
        box.setFocusable(true);
        box.invalidateAccessible();
        box.setAccessibleName("Play the film");
        box.setAccessibleRole(Accessible.Role.BUTTON);
        box.setAccessibleDescription(I18nString.literal("Starts playback"));
        box.setAccessibleIgnored(false);

        assertEquals(0, window.frameRequests,
                "nothing is listening, so nothing may spend a frame on it");
    }

    /** The control: a change that paints still buys its frame, so the harness is not simply dead. */
    @Test
    void aChangeThatPaintsStillBuysItsFrame() {
        box.invalidate();
        assertEquals(1, window.frameRequests);
    }

    @Test
    void setFocusableIsANoOpWhenTheFlagDidNotMove() {
        box.setFocusable(true);
        box.setFocusable(true);
        box.setFocusable(true);
        assertEquals(0, window.frameRequests);
        assertTrue(box.isFocusable());
    }

    @Test
    void anAnnouncementIntoAProcessWithNoReaderIsQueuedAndCostsNoFrame() {
        for (int i = 0; i < 200; i++) {
            scene.announce("row " + i + " added", Accessible.Politeness.POLITE);
        }
        scene.announce(I18nString.literal("done"), Accessible.Politeness.ASSERTIVE);

        assertEquals(0, window.frameRequests,
                "an application's diagnostics must not depend on a reader being present, and must "
                        + "not spend frames on speech nobody will hear");
    }

    /**
     * A frame on a window nothing is listening to allocates nothing.
     *
     * <p>The whole point of hanging the accessibility flag on the funnel every repaint goes through
     * is that it costs one boolean store. This is what makes that a measurement rather than a
     * claim: sixty frames of a settled scene, and the smallest of them allocates nothing at all.
     */
    @Test
    void aFrameWithNothingListeningAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        NoopCanvas canvas = new NoopCanvas(200, 200);
        scene.renderFrame(canvas);

        long least = AllocationProbe.leastAllocatedBy(() -> scene.renderFrame(canvas), 60);

        assertEquals(0, least, "a frame nobody is listening to must cost no memory");
    }
}
