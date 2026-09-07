package limn.backend.lwjgl.a11y;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each of the probe's cycles moves exactly the thing it says it moves, as a reader would see it.
 *
 * <p>Read off the <em>published tree</em> and not off the widgets, because the count ADR&nbsp;039
 * &sect;13.19 asks for is a count of what a difference between two trees produces: a cycle that
 * moved a widget without moving its node would count nothing and look like a quiet frame.
 */
class ProbeSceneTest {

    private ExecutorService workers;
    private UiRuntime runtime;
    private final AtomicLong nanos = new AtomicLong();
    private Scene scene;
    private ProbeScene probe;
    private Recorder recorder;
    private String cycleBefore;

    /** Keeps every tree and event; enough to say what one tick changed. */
    private static final class Recorder implements AccessibilityBridge {
        AccessibleTree last = AccessibleTree.EMPTY;
        int events;

        @Override
        public boolean isListening() {
            return true;
        }

        @Override
        public void publish(AccessibleTree tree, boolean reentrant) {
            last = tree;
        }

        @Override
        public void emit(AccessibleEvent event) {
            events++;
        }
    }

    @BeforeEach
    void bind() {
        cycleBefore = System.getProperty("probe.cycle");
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(nanos::get, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        probe = new ProbeScene();
        scene = new Scene(probe.root(), nanos::get);
        ProbeWindow window = new ProbeWindow();
        recorder = new Recorder();
        window.accessibility = recorder;
        scene.bind(window);
        scene.renderFrame(new NoCanvas());
    }

    @AfterEach
    void unbind() {
        if (cycleBefore == null) {
            System.clearProperty("probe.cycle");
        } else {
            System.setProperty("probe.cycle", cycleBefore);
        }
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }

    private AccessibleNode only(Accessible.Role role) {
        AccessibleNode found = null;
        AccessibleTree tree = recorder.last;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                assertTrue(found == null, "more than one " + role + " in the probe");
                found = tree.node(i);
            }
        }
        assertNotNull(found, "no " + role + " in the probe's tree");
        return found;
    }

    private void tick(String cycle) {
        System.setProperty("probe.cycle", cycle);
        probe.tick(scene);
        scene.renderFrame(new NoCanvas());
    }

    @Test
    void theDefaultCycleMovesTheFocusAndNothingElse() {
        double volume = only(Accessible.Role.SLIDER).value().value();
        tick("focus");
        assertEquals(probe.focusable.get(0), scene.focusedWidget());
        assertEquals(volume, only(Accessible.Role.SLIDER).value().value());
    }

    @Test
    void theValueCycleTogglesTheBoxAndStepsTheSliderByTen() {
        double volume = only(Accessible.Role.SLIDER).value().value();
        tick("value");
        assertEquals(volume + 10, only(Accessible.Role.SLIDER).value().value());
    }

    @Test
    void theDragCycleMovesTheSliderByOneLikeASampledDrag() {
        double volume = only(Accessible.Role.SLIDER).value().value();
        tick("drag");
        assertEquals(volume + 1, only(Accessible.Role.SLIDER).value().value());
        assertTrue(recorder.events > 0, "a slider that moved raised nothing");
    }

    @Test
    void theScrollCyclePagesTheListSoThatRowsAreRealizedAndReleased() {
        AccessibleNode list = only(Accessible.Role.LIST);
        assertTrue(list.scroll().verticallyScrollable(), "the list is not scrollable");
        double before = list.scroll().verticalPercent();
        int eventsBefore = recorder.events;
        tick("scroll");
        double after = only(Accessible.Role.LIST).scroll().verticalPercent();
        assertNotEquals(before, after, "the scroll cycle did not move the list");
        assertTrue(recorder.events > eventsBefore,
                "a page of new rows and a moved viewport raised no event at all");
    }
}
