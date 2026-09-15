package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The events table, and the two rules in it that came from measurement rather than from symmetry
 * with the other two platforms.
 */
class AxNotificationsTest {

    @Test
    void everyEventTypeHasADecision() {
        Set<String> undecided = new TreeSet<>();
        for (AccessibleEvent.Type type : AccessibleEvent.Type.values()) {
            try {
                AxNotifications.of(type);
            } catch (IllegalStateException e) {
                undecided.add(type.name());
            }
        }
        assertTrue(undecided.isEmpty(),
                "event types with no macOS row, not even a row saying 'nothing': " + undecided);
    }

    /**
     * A node that starts or stops being busy (a tree row whose children are on their way, ADR 044
     * §2) posts AXElementBusyChanged, the notification of the attribute that carries it. Every
     * other state change still posts value-changed, because every other state is read back off
     * AXValue. BUSY's name is a HIServices macro with no symbol, so it travels as a literal, and
     * the constants test is not asked to find it in AppKit.
     */
    @Test
    void aBusyChangeIsPostedAsTheBusyAttributesOwnNotification() {
        AxNotifications.Posting busy = AxNotifications.of(
                AccessibleEvent.state(7, limn.accessibility.Accessible.State.BUSY, false));
        assertEquals("AXElementBusyChanged", busy.notificationSymbol());
        assertEquals(AxNotifications.Subject.NODE, busy.subject());
        assertTrue(busy.literal(), "a macro, not a symbol dlsym could resolve");
        assertTrue(!AxNotifications.symbols().contains("AXElementBusyChanged"),
                "so the constants test does not look for it in the AppKit dump");

        AxNotifications.Posting checked = AxNotifications.of(
                AccessibleEvent.state(7, limn.accessibility.Accessible.State.CHECKED, true));
        assertEquals("NSAccessibilityValueChangedNotification", checked.notificationSymbol(),
                "and a state read back off AXValue is still a value change");
        assertTrue(!checked.literal());

        assertNull(AxNotifications.of(AccessibleEvent.state(7, Accessible.State.ACTIVE, true)),
                "ACTIVE is read back off no attribute: the focused node's cursor event is the focus change");
    }

    @Test
    void aDestroyedNodeIsPostedByNobodyHere() {
        // §13.20, measured: AppKit posts AXUIElementDestroyed itself, once, however the client
        // registered. Ours arrived on top of it once per matching registration.
        assertNull(AxNotifications.of(AccessibleEvent.Type.NODE_DESTROYED),
                "posting this again is a duplicate whose count depends on how the client registered");
    }

    @Test
    void theWindowEventsAreAppKitsOwnAndNotOurs() {
        for (AccessibleEvent.Type type : EnumSet.of(
                AccessibleEvent.Type.WINDOW_OPENED, AccessibleEvent.Type.WINDOW_CLOSED,
                AccessibleEvent.Type.WINDOW_ACTIVATED, AccessibleEvent.Type.WINDOW_DEACTIVATED)) {
            assertNull(AxNotifications.of(type),
                    type + " names the window root, which §2.2 elides because AppKit vends the window");
        }
    }

    @Test
    void aBoundsChangeIsNeverPostedPerNode() {
        assertNull(AxNotifications.of(AccessibleEvent.Type.BOUNDS_CHANGED),
                "one event per node during a drag is a storm on every platform");
    }

    @Test
    void focusAndTheCursorArePostedAtApplicationLevelAndEverythingElseAtItsOwnNode() {
        // Restated 2026-09-15 (decision 1; M3): a cursor move under the focused node is a focus move
        // on this platform, so ACTIVE_DESCENDANT_CHANGED joined FOCUS_CHANGED at application level. It
        // was a selected-children change on its own node.
        java.util.Set<AccessibleEvent.Type> toApplication = EnumSet.of(
                AccessibleEvent.Type.FOCUS_CHANGED, AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED);
        for (AccessibleEvent.Type type : toApplication) {
            AxNotifications.Posting posting = AxNotifications.of(type);
            assertEquals(AxNotifications.Subject.APPLICATION, posting.subject(),
                    type + ": an observer registered on the element receives nothing; this is AppKit's");
            assertEquals("NSAccessibilityFocusedUIElementChangedNotification", posting.notificationSymbol(),
                    type + " is where the user is, and a client asks the focused element after it");
        }
        for (AccessibleEvent.Type type : AccessibleEvent.Type.values()) {
            AxNotifications.Posting posting = AxNotifications.of(type);
            if (posting == null || toApplication.contains(type)) continue;
            assertEquals(AxNotifications.Subject.NODE, posting.subject(),
                    type + " has no reason to be posted anywhere but its own node");
        }
    }

    @Test
    void theEventsThisPlatformIsNotToldAreExactlyThese() {
        // As with the role phrases: the point is that changing this set is a deliberate act. An
        // event silently added to it is an event a reader stops hearing about.
        Set<AccessibleEvent.Type> silent = EnumSet.noneOf(AccessibleEvent.Type.class);
        for (AccessibleEvent.Type type : AccessibleEvent.Type.values()) {
            if (AxNotifications.of(type) == null) silent.add(type);
        }
        assertEquals(EnumSet.of(
                AccessibleEvent.Type.NODE_DESTROYED,
                AccessibleEvent.Type.WINDOW_OPENED, AccessibleEvent.Type.WINDOW_CLOSED,
                AccessibleEvent.Type.WINDOW_ACTIVATED, AccessibleEvent.Type.WINDOW_DEACTIVATED,
                AccessibleEvent.Type.BOUNDS_CHANGED,
                AccessibleEvent.Type.INVOKED), silent);
    }

    @Test
    void anAnnouncementIsToldWithAPriorityAndAKey() {
        assertNotNull(AxNotifications.of(AccessibleEvent.Type.ANNOUNCEMENT));
        assertTrue(AxNotifications.priorityFor(Accessible.Politeness.ASSERTIVE)
                        > AxNotifications.priorityFor(Accessible.Politeness.POLITE),
                "an assertive announcement interrupts and a polite one waits, so the numbers order "
                        + "the same way; these are the one platform constant that cannot be dlsymed");
        for (Accessible.Politeness politeness : Accessible.Politeness.values()) {
            assertTrue(AxNotifications.priorityFor(politeness) > 0, politeness + " has no priority");
        }
    }
}
