package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stopwatch bridge tells a raise from a skip by asking the real bridge, and never by minting.
 *
 * <p>The platform calls are no-ops here, so what is checked is the classification and the
 * forwarding: an event on a node no client asked for is counted as skipped, one on a node a client
 * holds is counted as raised, and counting changes nothing about what the real bridge holds.
 */
class TimedBridgeTest {

    private static AccessibleTree aWindowWithAButton() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 20, 160, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, false);
        a.end();
        a.end();
        return a.publish(0, 0, 0, 1f, true);
    }

    @Test
    void anEventOnANodeNobodyAskedForIsASkipAndOneOnAHeldNodeIsARaise() {
        UiaBridge real = UiaBridge.withoutTheGate(0x1234);
        EmitTally tally = new EmitTally();
        List<String> log = new ArrayList<>();
        TimedBridge timed = new TimedBridge(real, tally, log::add);
        try {
            timed.publish(aWindowWithAButton(), false);
            assertFalse(real.holdsElementFor(1001), "nothing has asked for the button");

            // A property change, which the bridge raises only for a held element. Until
            // 2026-09-15 this was a focus change; those are raised whether or not anything was
            // held, since the bridge mints the focused element (WINDOWS-NEW-4).
            timed.emit(AccessibleEvent.property(AccessibleEvent.Type.NAME_CHANGED, 1001,
                    "Save", "Save as"));
            assertEquals(1, tally.skipped());
            assertEquals(0, tally.raised());
            assertFalse(real.holdsElementFor(1001),
                    "counting did not mint: a probe that minted would count its own elements");

            // What a client's navigation does: the element exists from here on.
            real.objectFor(1001);
            assertTrue(real.holdsElementFor(1001));
            timed.emit(AccessibleEvent.property(AccessibleEvent.Type.NAME_CHANGED, 1001,
                    "Save as", "Save"));
            assertEquals(1, tally.skipped());
            assertEquals(1, tally.raised());
            assertEquals(2, tally.eventsInOpenFrame());
            assertEquals(1, tally.raisedInOpenFrame());

            timed.publish(aWindowWithAButton(), false);
            assertEquals(2, timed.frames());
            assertEquals(1, tally.frames(), "the first frame closed when the second was published");
            assertEquals(List.of("FRAME 1 events=2 raised=1"), log);
        } finally {
            timed.detach();
        }
    }

    /**
     * The frame's end is a member the scene calls, and since 2026-09-16 it is the boundary the real
     * bridge flushes a collapse's owed re-announcement at. A wrapper that inherited the no-op
     * default would make the probe's runs the one place a collapse whose tail is nothing but
     * structure never says where the user is — and the probe's runs are the only Windows runs.
     */
    @Test
    void theFramesEndIsForwardedSoACollapsesReannouncementIsStillFlushed() {
        UiaBridge real = UiaBridge.withoutTheGate(0x1234);
        TimedBridge timed = new TimedBridge(real, new EmitTally(), null);
        List<String> trace = java.util.Collections.synchronizedList(new ArrayList<String>());
        java.util.function.Consumer<String> before = UiaWindow.trace;
        UiaWindow.trace = trace::add;
        try {
            timed.publish(aFocusedWindow(), false);
            real.objectFor(1000);
            real.noteAsked();
            // A publish past the model's budget whose reserved tail holds nothing but structure.
            timed.emit(AccessibleEvent.of(AccessibleEvent.Type.INVALIDATED, 0));
            timed.frameEnded();

            long deadline = System.nanoTime() + 2_000_000_000L;
            boolean said = false;
            while (!said && System.nanoTime() < deadline) {
                synchronized (trace) {
                    said = trace.stream().anyMatch(l -> l.startsWith(
                            "raised after the model's INVALIDATED for node 1001 in "));
                }
                Thread.onSpinWait();
            }
            assertTrue(said, "the frame's end never reached the real bridge: " + trace);
        } finally {
            UiaWindow.trace = before;
            timed.detach();
        }
    }

    /** The same window, with the button holding the keyboard, so there is a focus to re-announce. */
    private static AccessibleTree aFocusedWindow() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 20, 160, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, true, true);
        a.end();
        a.end();
        return a.publish(1001, 0, 0, 1f, true);
    }

    @Test
    void detachClosesTheCountPrintsTheSummaryAndStillDetachesTheRealBridge() {
        UiaBridge real = UiaBridge.withoutTheGate(0x1234);
        EmitTally tally = new EmitTally();
        List<String> log = new ArrayList<>();
        TimedBridge timed = new TimedBridge(real, tally, log::add);
        timed.publish(aWindowWithAButton(), false);
        real.objectFor(1001);
        timed.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
        assertEquals(1, real.elementCount());

        timed.detach();

        assertEquals(1, tally.frames(), "the open frame was closed by the detach");
        assertTrue(log.get(log.size() - 1).startsWith("TALLY frames=1"), log.toString());
        assertEquals(0, real.elementCount(), "and the real bridge was detached underneath");
    }
}
