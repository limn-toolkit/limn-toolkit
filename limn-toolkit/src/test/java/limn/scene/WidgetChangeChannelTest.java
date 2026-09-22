package limn.scene;

import limn.backend.CrashHandler;
import limn.backend.CrashPhase;
import limn.backend.Crashes;
import limn.concurrent.Subscription;
import limn.concurrent.UiRuntime;
import limn.scene.layout.Column;
import limn.testing.AllocationProbe;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static limn.testing.SceneDriver.drive;

/**
 * The guarantees ADR 040 §2 makes about the channel itself, as a base-class mechanism rather than
 * per component: reentrancy, containment, thread, allocation, lifetime, reach and order. Each is
 * a sentence in §2; none of them had a test before, and each is asserted here on a plain widget
 * so that no component's own behaviour is in the way.
 */
class WidgetChangeChannelTest {

    private HeadlessUi ui;
    private UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
        runtime = ui.runtime();
    }

    @AfterEach
    void uninstallRuntime() {
        ui.close();
    }

    /** A widget with one aspect of its own, announced from one seam, so the channel can be driven. */
    static final class Probe extends Widget {
        private int value;
        private final List<Change.Aspect> handled = new ArrayList<>();

        void write(int next, Change.Origin origin) {
            if (next == value) {
                return;
            }
            value = next;
            notifyChange(Change.of(Change.Aspect.VALUE, origin));
        }

        void type(int offset, int removed, int inserted, Change.Origin origin) {
            notifyTextEdit(origin, offset, removed, inserted);
        }

        @Override
        protected void handleUserChange(Change.Aspect aspect) {
            handled.add(aspect);
        }

        @Override
        protected Size onMeasure(Constraints c) {
            return c.constrain(10, 10);
        }
    }

    // ---------------------------------------------------------------------- reentrancy

    @Test
    void aRegistrationMadeDuringANotificationDoesNotReceiveIt() {
        Probe probe = new Probe();
        List<String> heard = new ArrayList<>();
        probe.observeChanges((source, change) -> {
            heard.add("first");
            probe.observeChanges((s2, c2) -> heard.add("late"));
        });

        probe.write(1, Change.Origin.CODE);
        assertEquals(List.of("first"), heard, "the late one takes effect on the next notification");

        probe.write(2, Change.Origin.CODE);
        assertEquals(List.of("first", "first", "late"), heard,
                "the second dispatch reaches the one registered during the first, and the one it"
                        + " registers in turn waits for a third");
    }

    @Test
    void anUnregistrationMadeDuringANotificationStillRunsForIt() {
        Probe probe = new Probe();
        List<String> heard = new ArrayList<>();
        AtomicReference<Subscription> second = new AtomicReference<>();
        probe.observeChanges((source, change) -> {
            heard.add("first");
            second.get().cancel(); // takes the second off the list, mid-dispatch
        });
        second.set(probe.observeChanges((source, change) -> heard.add("second")));

        probe.write(1, Change.Origin.CODE);
        assertEquals(List.of("first", "second"), heard, "the dispatch walks the array it started with");

        probe.write(2, Change.Origin.CODE);
        assertEquals(List.of("first", "second", "first"), heard);
    }

    @Test
    void aNestedChangeIsAnnouncedInFullBeforeTheOuterDispatchContinues() {
        Probe a = new Probe();
        Probe b = new Probe();
        List<String> order = new ArrayList<>();
        a.observeChanges((source, change) -> {
            order.add("a heard, writing b");
            b.write(a.value, Change.Origin.CODE);
            order.add("a resumed");
        });
        b.observeChanges((source, change) -> order.add("b heard"));
        a.observeChanges((source, change) -> order.add("a's second watcher"));

        a.write(7, Change.Origin.CODE);
        assertEquals(List.of("a heard, writing b", "b heard", "a resumed", "a's second watcher"), order);
    }

    // --------------------------------------------------------------------- containment

    @Test
    void aThrowingWatcherStopsNeitherTheNextWatcherNorTheHandlerAndIsReportedOnce() {
        Probe probe = new Probe();
        AtomicInteger next = new AtomicInteger();
        probe.observeChanges((source, change) -> {
            throw new IllegalStateException("a watcher that misbehaves");
        });
        probe.observeChanges((source, change) -> next.incrementAndGet());
        AtomicInteger reported = new AtomicInteger();
        CrashHandler counting = (phase, error) -> {
            if (phase == CrashPhase.OBSERVER) {
                reported.incrementAndGet();
            }
            return true;
        };
        Crashes.install(counting);
        try {
            probe.write(1, Change.Origin.USER); // does not reach this test
        } finally {
            Crashes.uninstall(counting);
        }
        assertEquals(1, next.get(), "the remaining watchers still run");
        assertEquals(List.of(Change.Aspect.VALUE), probe.handled, "and the handler still runs");
        assertEquals(1, reported.get(), "one crash, under OBSERVER");
    }

    // -------------------------------------------------------------------------- thread

    @Test
    void registrationAndDeliveryOffTheUiThreadThrow() throws InterruptedException {
        Probe probe = new Probe();
        Subscription handle = probe.observeChanges((source, change) -> { });
        List<Throwable> thrown = new ArrayList<>();
        Thread other = new Thread(() -> {
            try {
                probe.observeChanges((source, change) -> { });
            } catch (Throwable error) {
                thrown.add(error);
            }
            try {
                handle.cancel();
            } catch (Throwable error) {
                thrown.add(error);
            }
        }, "not-the-ui-thread");
        other.start();
        other.join();
        assertEquals(2, thrown.size(), thrown.toString());
        assertTrue(thrown.get(0) instanceof IllegalStateException, thrown.get(0).toString());
        assertTrue(thrown.get(1) instanceof IllegalStateException, thrown.get(1).toString());
    }

    // ---------------------------------------------------------------------- allocation

    @Test
    void aNotificationAllocatesNothingWithNoWatcherAndNothingWithThree() {
        assumeTrue(AllocationProbe.isSupported(), "this virtual machine does not count thread allocation");
        Probe probe = new Probe();
        Scene scene = new Scene(probe);
        int[] tick = {0};
        Runnable drag = () -> {
            for (int i = 0; i < 100; i++) {
                probe.write(++tick[0], Change.Origin.USER); // announce and handle, every step
            }
        };
        assertEquals(0, AllocationProbe.leastAllocatedBy(drag, 20), "nobody watching: the quiet path");

        AtomicInteger seen = new AtomicInteger();
        probe.observeChanges((source, change) -> seen.incrementAndGet());
        probe.observeChanges((source, change) -> seen.incrementAndGet());
        scene.observeChanges((source, change) -> seen.incrementAndGet());
        assertEquals(0, AllocationProbe.leastAllocatedBy(drag, 20),
                "three watchers: an indexed walk over an interned value allocates nothing");
        assertTrue(seen.get() > 0);
    }

    @Test
    void aTextEditAllocatesOneRecordPerEditAndNotOnePerWatcher() {
        assumeTrue(AllocationProbe.isSupported(), "this virtual machine does not count thread allocation");
        Probe probe = new Probe();
        Runnable keystrokes = () -> {
            for (int i = 0; i < 100; i++) {
                probe.type(i, 0, 1, Change.Origin.USER);
            }
        };
        assertEquals(0, AllocationProbe.leastAllocatedBy(keystrokes, 20),
                "a keystroke on a widget nobody watches constructs nothing, and still reaches the handler");
        assertTrue(probe.handled.size() >= 100);

        probe.observeChanges((source, change) -> assertTrue(change instanceof Change.TextEdit));
        long withOne = AllocationProbe.leastAllocatedBy(keystrokes, 20);
        assertTrue(withOne > 0, "one watcher: the record is built");
        probe.observeChanges((source, change) -> { });
        probe.observeChanges((source, change) -> { });
        long withThree = AllocationProbe.leastAllocatedBy(keystrokes, 20);
        assertEquals(withOne, withThree, "one record per edit, whoever is watching");
    }

    // ------------------------------------------------------------------------ lifetime

    @Test
    void cancellingAHandleTwiceIsANoOpEvenWhenTheSameWatcherIsRegisteredTwice() {
        Probe probe = new Probe();
        AtomicInteger heard = new AtomicInteger();
        ChangeObserver watcher = (source, change) -> heard.incrementAndGet();
        Subscription first = probe.observeChanges(watcher);
        probe.observeChanges(watcher);

        probe.write(1, Change.Origin.CODE);
        assertEquals(2, heard.get(), "registered twice, notified twice");

        first.cancel();
        first.cancel();
        probe.write(2, Change.Origin.CODE);
        assertEquals(3, heard.get(), "one registration came off, never two");
    }

    @Test
    void aDetachedWidgetKeepsItsWatchersAndReachesNoScene() {
        Probe probe = new Probe();
        Column root = new Column();
        root.add(probe);
        Scene scene = new Scene(root);
        AtomicInteger own = new AtomicInteger();
        AtomicInteger scenes = new AtomicInteger();
        probe.observeChanges((source, change) -> own.incrementAndGet());
        scene.observeChanges((source, change) -> {
            if (source == probe) {
                scenes.incrementAndGet();
            }
        });

        probe.write(1, Change.Origin.CODE);
        assertEquals(1, own.get());
        assertEquals(1, scenes.get());

        root.remove(probe);
        probe.write(2, Change.Origin.CODE);
        assertEquals(2, own.get(), "the subscription is the widget's and survives detach");
        assertEquals(1, scenes.get(), "the scene channel is over a tree, and hears what it holds now");

        root.add(probe);
        probe.write(3, Change.Origin.CODE);
        assertEquals(3, own.get());
        assertEquals(2, scenes.get(), "re-attached, the scene hears it again with no re-registration");
    }

    // --------------------------------------------------------------------------- reach

    @Test
    void aSceneWatcherHearsAnUnboundSceneAndAnOverlay() {
        Probe probe = new Probe();
        Scene scene = new Scene(probe); // never bound to a window: every component test's shape
        List<Widget> sources = new ArrayList<>();
        scene.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.VALUE) {
                sources.add(source);
            }
        });
        probe.write(1, Change.Origin.CODE);
        assertEquals(List.of(probe), sources, "the scene channel works on an unbound scene");

        Probe overlaid = new Probe();
        scene.pushOverlay(overlaid);
        overlaid.write(1, Change.Origin.CODE);
        assertEquals(List.of(probe, overlaid), sources, "an overlay is in the tree the scene watches");
    }

    @Test
    void notificationsDoNotBubbleToAParent() {
        Probe child = new Probe();
        Column parent = new Column();
        parent.add(child);
        new Scene(parent);
        AtomicInteger parentHeard = new AtomicInteger();
        parent.observeChanges((source, change) -> parentHeard.incrementAndGet());

        child.write(1, Change.Origin.CODE);
        assertEquals(0, parentHeard.get(), "two registration points, the widget and the scene, and nothing between");
    }

    // --------------------------------------------------------------------------- order

    @Test
    void theHandlerRunsAfterEveryWatcherAndOnlyForTheUser() {
        Probe probe = new Probe();
        Scene scene = new Scene(probe);
        List<String> order = new ArrayList<>();
        probe.observeChanges((source, change) -> order.add("own/" + change.origin()));
        scene.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.VALUE) {
                order.add("scene/" + change.origin());
            }
        });

        probe.write(1, Change.Origin.CODE);
        probe.write(2, Change.Origin.ADJUSTMENT);
        assertEquals(List.of(), probe.handled, "neither CODE nor ADJUSTMENT reaches the handler");

        probe.write(3, Change.Origin.USER);
        assertEquals(List.of(Change.Aspect.VALUE), probe.handled);
        assertEquals(List.of("own/CODE", "scene/CODE", "own/ADJUSTMENT", "scene/ADJUSTMENT",
                "own/USER", "scene/USER"), order);
    }

    @Test
    void aLayoutPassAnnouncesOneLayoutAtTheRootAndOnlyWhenItRan() {
        Probe root = new Probe();
        Scene scene = new Scene(root);
        List<Widget> layouts = new ArrayList<>();
        scene.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.LAYOUT) {
                assertEquals(Change.Origin.ADJUSTMENT, change.origin());
                layouts.add(source);
            }
        });

        scene.layoutPass(100, 100);
        assertEquals(List.of(root), layouts, "one marker, sourced at the root");

        scene.layoutPass(100, 100);
        assertEquals(1, layouts.size(), "a pass that did not run announces nothing");

        root.markNeedsLayout();
        scene.layoutPass(100, 100);
        assertEquals(2, layouts.size());
    }

    @Test
    void focusIsAnnouncedOnTheLoserThenTheGainerWithTheOriginOfThePath() {
        Probe a = new Probe();
        Probe b = new Probe();
        a.setFocusable(true);
        b.setFocusable(true);
        Column root = new Column();
        root.add(a);
        root.add(b);
        Scene scene = new Scene(root);
        scene.layoutPass(100, 100);
        List<String> order = new ArrayList<>();
        scene.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.FOCUS) {
                order.add((source == a ? "a" : "b") + "/" + change.origin());
            }
        });

        a.requestFocus();
        assertEquals(List.of("a/CODE"), order, "the public requestFocus() is code moving the focus");
        order.clear();

        scene.focusTraverse(false);
        assertEquals(List.of("a/CODE", "b/CODE"), order, "so is the public traversal");
        order.clear();

        drive(scene).keyEvent(limn.input.Keys.TAB, true, false, 0);
        drive(scene).keyEvent(limn.input.Keys.TAB, false, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals(List.of("b/USER", "a/USER"), order, "Tab is the user's, loser first");
        order.clear();

        a.setVisible(false);
        assertEquals(List.of("a/ADJUSTMENT"), order, "focus revoked by a hide is an adjustment");
    }

    @Test
    void theHiddenWidgetAnnouncesTheRevokedFocusBeforeItsOwnAspect() {
        Probe a = new Probe();
        a.setFocusable(true);
        Scene scene = new Scene(a);
        scene.layoutPass(100, 100);
        a.requestFocus();
        List<Change.Aspect> order = new ArrayList<>();
        a.observeChanges((source, change) -> order.add(change.aspect()));

        a.setVisible(false);
        assertEquals(List.of(Change.Aspect.FOCUS, Change.Aspect.VISIBLE), order,
                "the settling order in the smallest case there is: the call's own aspect last");
    }

    @Test
    void aTextChangeCannotBeMadeAsAState() {
        assertThrows(IllegalArgumentException.class,
                () -> Change.of(Change.Aspect.TEXT, Change.Origin.USER));
        Change edit = Change.edit(Change.Origin.USER, 3, 1, 2);
        assertSame(Change.Aspect.TEXT, edit.aspect());
        assertTrue(edit.fromUser());
        assertNotNull(Change.of(Change.Aspect.VALUE, Change.Origin.USER));
        assertSame(Change.of(Change.Aspect.VALUE, Change.Origin.USER),
                Change.of(Change.Aspect.VALUE, Change.Origin.USER), "interned");
    }
}
