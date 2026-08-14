package limn.scene;

import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a posted task costs in frames. The answer is nothing unless the task
 * asks: a task invalidates what it mutates, and a task that mutates nothing
 * leaves the window asleep.
 *
 * <p>This is the property behind every timer in this repository. A poll that
 * re-reads a player's position ten times a second is affordable only because
 * firing is free; when running a task repainted every window, replacing a
 * ticker with a timer moved the cost from the display's refresh rate to the sum
 * of the poll rates rather than removing it, and a paint that armed a poll
 * whose task forced the next paint sustained itself forever.
 *
 * <p><b>Scope.</b> These tests drive the halves a JUnit thread can own: the
 * runtime's drain, the scene's invalidation, and the window's frame requests
 * ({@link RecordingWindow} counts them). They do <b>not</b> run
 * {@code LwjglBackend.runEventLoop}, which needs GLFW on the process's first
 * thread and a real window, so the loop's own wiring (that it drains with the
 * crash hook and repaints nothing else) is not covered here; what is covered
 * is that the drain hands it no reason to. The crash hook itself is asserted
 * below at the seam the loop calls.
 */
class PostedTaskFrameCostTest {

    /**
     * A widget with one piece of state written two ways: a field a task can set
     * behind the toolkit's back (the residual risk the contract on
     * {@code Ui.post} names) and a guarded setter shaped like the component
     * ones: unchanged value, no damage.
     */
    private static final class Readout extends Widget {
        String text = "";

        void setText(String newText) {
            if (text.equals(newText)) {
                return;
            }
            text = newText;
            invalidate();
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(120, 20);
        }
    }

    private ExecutorService workers;
    private UiRuntime runtime;
    private final AtomicLong nanos = new AtomicLong();
    private RecordingWindow window;
    private Scene scene;
    private Readout readout;

    @BeforeEach
    void bindScene() {
        workers = Executors.newFixedThreadPool(1);
        // Injected clock: postDelayed deadlines are compared against it, so the
        // test advances time instead of sleeping through it.
        runtime = new UiRuntime(nanos::get, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        readout = new Readout();
        scene = new Scene(readout, nanos::get);
        window = new RecordingWindow();
        scene.bind(window);
        scene.renderFrame(new NoopCanvas(200, 200)); // settle the first frame
        window.frameRequests = 0;
    }

    @AfterEach
    void unbind() {
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    @Test
    void aTaskThatMutatesWithoutInvalidatingAsksForNoFrame() {
        Ui.post(() -> readout.text = "changed");

        assertEquals(1, runtime.drain());
        assertEquals(0, window.frameRequests,
                "a drained task must not buy a frame it did not ask for");
    }

    /** The control: without this the harness could pass by being dead. */
    @Test
    void aTaskThatInvalidatesGetsItsFrame() {
        Ui.post(() -> readout.setText("changed"));

        assertEquals(1, runtime.drain());
        assertEquals(1, window.frameRequests);
    }

    /**
     * The done-criterion, at the seam this test can reach: a window with
     * nothing moving on it, watched by a timer that re-arms itself forever,
     * renders nothing at all. Fifty polls, zero frames.
     */
    @Test
    void anIdleWindowWithATimerRunningCostsZeroFrames() {
        arm();

        for (int i = 0; i < 50; i++) {
            nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            assertEquals(1, runtime.drain());
        }

        assertEquals(50, polls);
        assertEquals(0, window.frameRequests,
                "a poll that finds nothing changed must leave the window asleep");
    }

    /** And the same timer pays for exactly the frames it does change something on. */
    @Test
    void theSameTimerBuysAFrameOnEachPollThatChangesSomething() {
        arm();

        for (int i = 0; i < 50; i++) {
            nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            moving = i < 3 ? "moving " + i : "settled";
            runtime.drain();
        }

        // Three changes while the value moved, one more when it settled.
        assertEquals(4, window.frameRequests);
    }

    private int polls;
    private String moving = "";

    /** A self-re-arming poll of the shape the demo's readouts use. */
    private void arm() {
        Ui.postDelayed(() -> {
            polls++;
            readout.setText(moving);
            arm();
        }, 100);
    }

    /**
     * The one path that still repaints on a task's behalf, and the reason it
     * exists: a task that threw applied part of its mutation and invalidated
     * none of it. The hook is the loop's, and the loop repaints every window
     * with it.
     */
    @Test
    void onlyATaskThatThrowsRunsTheSettleHook() {
        int[] settled = {0};

        Ui.post(() -> readout.text = "quiet");
        assertEquals(1, runtime.drain(() -> settled[0]++));
        assertEquals(0, settled[0]);
        assertEquals(0, window.frameRequests);

        Ui.post(() -> {
            readout.text = "half";
            throw new IllegalStateException("expected: a task that throws part-way");
        });
        Ui.post(() -> readout.text = "the drain keeps going");

        assertEquals(2, runtime.drain(() -> settled[0]++));
        assertEquals(1, settled[0]);
        assertEquals("the drain keeps going", readout.text);
    }
}
