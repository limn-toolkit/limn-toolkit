package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.AccessibilityBridge;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The instrument measures the right thing: a sample is one frame that published or emitted, carrying
 * the events emitted since the previous frame ended, which are the events that frame's end drained.
 */
@ExtendWith(PlatformFreeBridges.class)
class TimingBridgeTest {

    /** Remembers what reached it, so the wrapper can be shown to pass everything through. */
    private static final class Recorder implements AccessibilityBridge {
        final List<String> calls = new ArrayList<>();

        @Override public boolean isListening() {
            return true;
        }

        @Override public boolean needsPrimingPublish() {
            return true;
        }

        @Override public void publish(AccessibleTree tree, boolean reentrant) {
            calls.add("publish(" + reentrant + ")");
        }

        @Override public void emit(AccessibleEvent event) {
            calls.add("emit(" + event.type() + ")");
        }

        @Override public void frameEnded() {
            calls.add("frameEnded");
        }
    }

    private static AccessibleEvent focus(long nodeId) {
        return AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, nodeId);
    }

    /** A window with two buttons directly under it, so both are pushed and both can be posted on. */
    private static AccessibleTree aWindowWithTwoButtons() {
        Accessibility a = new Accessibility();
        a.beginWalk(480, 320, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 480, 320);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        for (int i = 0; i < 2; i++) {
            a.begin(1001 + i, 0, Locale.ENGLISH, 40, 96 + 40L * i, 160, 40);
            a.role(Accessible.Role.BUTTON);
            a.name(I18nString.literal("Button " + i), Accessible.NameFrom.CONTENT);
            a.action(Accessible.Action.PRESS);
            a.inherited(true, true, true, true, false);
            a.end();
        }
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    @Test
    void aSampleIsAFrameAndCarriesTheEventsEmittedSinceThePreviousFrameEnded() {
        TimingBridge timing = new TimingBridge(new Recorder());
        timing.publish(AccessibleTree.EMPTY, false);
        timing.frameEnded();
        timing.publish(AccessibleTree.EMPTY, false);
        timing.emit(focus(1));
        timing.emit(focus(2));
        timing.emit(focus(3));
        timing.frameEnded();
        timing.frameEnded();                      // a frame that did nothing is not a sample
        timing.emit(focus(4));                    // announcements on a still window: no publish
        timing.frameEnded();
        List<TimingBridge.Sample> samples = timing.samples();
        assertEquals(3, samples.size());
        assertEquals(0, samples.get(0).events(), "the priming publish had nothing before it");
        assertEquals(3, samples.get(1).events(),
                "one frame's difference: the events between the previous frame's end and this one");
        assertEquals(1, samples.get(1).publishes());
        assertEquals(1, samples.get(2).events(), "a frame that emitted without publishing counts");
        assertEquals(0, samples.get(2).publishes());
        assertEquals(List.of(1, 2, 3), samples.stream().map(TimingBridge.Sample::number).toList());
    }

    @Test
    void everythingPassesThroughUnchangedIncludingTheReentrantFlagAndTheFrameEnd() {
        Recorder inner = new Recorder();
        TimingBridge timing = new TimingBridge(inner);
        assertTrue(timing.isListening());
        assertTrue(timing.needsPrimingPublish());
        timing.publish(AccessibleTree.EMPTY, true);
        timing.emit(focus(1));
        timing.frameEnded();
        timing.publish(AccessibleTree.EMPTY, false);
        timing.frameEnded();
        assertEquals(List.of("publish(true)", "emit(FOCUS_CHANGED)", "frameEnded", "publish(false)",
                        "frameEnded"), inner.calls,
                "an instrument that changed what the scene said would measure itself");
        assertTrue(timing.samples().get(0).reentrant());
        assertFalse(timing.samples().get(1).reentrant());
    }

    @Test
    void overThePlatformsBridgeASampleCarriesTheDrainsOwnTimeAndCount() {
        AxBridge ax = PlatformFreeBridges.make();
        TimingBridge timing = new TimingBridge(ax);
        AccessibleTree tree = aWindowWithTwoButtons();
        timing.publish(tree, false);
        timing.frameEnded();
        // Both buttons were pushed, so both hold elements and both value changes reach a post;
        // BOUNDS_CHANGED is one this platform is deliberately not told, so it is drained and not
        // posted -- and the two counts are the two halves of what a drain costs.
        timing.publish(tree, false);
        timing.emit(AccessibleEvent.of(AccessibleEvent.Type.VALUE_CHANGED, 1001));
        timing.emit(AccessibleEvent.of(AccessibleEvent.Type.VALUE_CHANGED, 1002));
        timing.emit(AccessibleEvent.of(AccessibleEvent.Type.BOUNDS_CHANGED, 1001));
        timing.frameEnded();
        TimingBridge.Sample sample = timing.samples().get(1);
        assertEquals(3, sample.events());
        assertEquals(3, ax.lastDrainDrained(), "the drain took every queued event off the queue");
        assertEquals(2, sample.posted(), "and two of them reached the platform as a notification");
        assertTrue(sample.drainNanos() >= 0, "the drain was timed");
        assertEquals(-1, timing.samples().get(0).drainNanos(),
                "a frame with no event had no drain to time");
        assertEquals(0, ax.collapses());
        assertTrue(timing.summary().contains("posted per frame: max 2, median 2"), timing.summary());
    }

    @Test
    void aReentrantPublishsEventsAreDrainedAtTheEndOfTheFrameThatFollows() {
        AxBridge ax = PlatformFreeBridges.make();
        TimingBridge timing = new TimingBridge(ax);
        AccessibleTree tree = aWindowWithTwoButtons();
        timing.publish(tree, false);
        timing.frameEnded();
        timing.publish(tree, true);
        timing.emit(AccessibleEvent.of(AccessibleEvent.Type.VALUE_CHANGED, 1001));
        assertEquals(1, ax.queuedEvents(), "a reentrant publish drains nothing (§3.2)");
        assertEquals(1, timing.samples().size(), "and is not a frame");
        timing.frameEnded();
        TimingBridge.Sample sample = timing.samples().get(1);
        assertEquals(1, sample.events(), "the event is counted against the frame whose end told it");
        assertTrue(sample.reentrant());
        assertEquals(1, sample.posted());
        assertEquals(0, ax.queuedEvents());
    }

    @Test
    void theSummaryLeavesQuietFramesOutOfTheTypicalFigures() {
        TimingBridge timing = new TimingBridge(new Recorder());
        for (int i = 0; i < 10; i++) {
            timing.publish(AccessibleTree.EMPTY, false);
            timing.frameEnded();
        }
        timing.publish(AccessibleTree.EMPTY, false);
        timing.emit(focus(1));
        timing.emit(focus(2));
        timing.frameEnded();
        String summary = timing.summary();
        assertTrue(summary.contains("11 frames, 1 of them with events"), summary);
        assertTrue(summary.contains("events per frame: max 2, median 2"),
                "a median over mostly-quiet frames would say a scroll is free: " + summary);
    }
}
