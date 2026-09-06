package limn.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;

import java.util.EnumMap;
import java.util.Map;

/**
 * Which notification, if any, this platform is told about an event — and where it is posted.
 *
 * <p><b>The interesting half of this table is the events that map to nothing.</b> On the other two
 * platforms a bridge raises everything it knows; here AppKit is already speaking for the window it
 * vends, and §2.2 elides our own window root precisely so that a client is not told the same thing
 * twice. Doubling an announcement is not a cosmetic fault: a reader that hears two window-opened
 * events for one window offers the user two windows.
 *
 * <p>Two entries are empty for reasons the phase 7 probe run established rather than assumed.
 *
 * <ul>
 *   <li>{@code NODE_DESTROYED} posts nothing. AppKit posts {@code AXUIElementDestroyed} itself when
 *       an element goes away, exactly once however the client registered; ours arrived on top of
 *       that, once per matching registration — two notifications for a client watching the element,
 *       three for one watching both it and the application (§13.20). Every earlier draft said "post,
 *       then release", by analogy with Windows and Linux, and the analogy was the mistake.</li>
 *   <li>The four window events post nothing, because AppKit's own {@code AXWindowCreated},
 *       {@code AXUIElementDestroyed}, {@code AXMoved} and {@code AXResized} are already on the
 *       object it vends for the window, and ours would name a node no client can see.</li>
 * </ul>
 *
 * <p><b>And one entry is posted somewhere other than its own node.</b> A focus change is delivered
 * only to an observer registered on the <em>application</em> element — measured in the spike, where
 * an observer on the element itself received nothing — so it is posted at application level and
 * never per element. That is the one place where "post the notification on its subject" is wrong
 * here, and it is a fact about AppKit rather than a choice.
 */
final class AxNotifications {

    private AxNotifications() {
    }

    /** Where a notification is posted, which is not always the node it is about. */
    enum Subject {
        /** On the element the event names. */
        NODE,
        /** On the process's application element, which is the only registration focus reaches. */
        APPLICATION,
    }

    /** A notification symbol and where to post it, or {@code null} for an event this platform is not told. */
    record Posting(String notificationSymbol, Subject subject) {
    }

    private static final Map<AccessibleEvent.Type, Posting> BY_TYPE =
            new EnumMap<>(AccessibleEvent.Type.class);

    private static void post(AccessibleEvent.Type type, String symbol) {
        BY_TYPE.put(type, new Posting(symbol, Subject.NODE));
    }

    private static void postToApplication(AccessibleEvent.Type type, String symbol) {
        BY_TYPE.put(type, new Posting(symbol, Subject.APPLICATION));
    }

    static {
        postToApplication(AccessibleEvent.Type.FOCUS_CHANGED,
                "NSAccessibilityFocusedUIElementChangedNotification");
        post(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED,
                "NSAccessibilitySelectedChildrenChangedNotification");
        post(AccessibleEvent.Type.STRUCTURE_CHANGED, "NSAccessibilityLayoutChangedNotification");
        post(AccessibleEvent.Type.NAME_CHANGED, "NSAccessibilityTitleChangedNotification");
        // AppKit has no description-changed notification. A client that cares re-reads the
        // attribute after a layout change, which is what the structure event already says.
        post(AccessibleEvent.Type.DESCRIPTION_CHANGED, "NSAccessibilityLayoutChangedNotification");
        // A state is whatever attribute carries it, and every one of them is read back off AXValue
        // or a role attribute -- so the honest single notification for "something about this node
        // changed" is the value one, which is what AppKit's own controls post for a toggle.
        post(AccessibleEvent.Type.STATE_CHANGED, "NSAccessibilityValueChangedNotification");
        post(AccessibleEvent.Type.VALUE_CHANGED, "NSAccessibilityValueChangedNotification");
        post(AccessibleEvent.Type.SELECTION_CHANGED,
                "NSAccessibilitySelectedChildrenChangedNotification");
        post(AccessibleEvent.Type.TEXT_CHANGED, "NSAccessibilityValueChangedNotification");
        post(AccessibleEvent.Type.CARET_MOVED, "NSAccessibilitySelectedTextChangedNotification");
        post(AccessibleEvent.Type.TEXT_SELECTION_CHANGED,
                "NSAccessibilitySelectedTextChangedNotification");
        post(AccessibleEvent.Type.ANNOUNCEMENT,
                "NSAccessibilityAnnouncementRequestedNotification");
        // The collapse of a queue too small for the difference it was handed. One layout-changed on
        // the window is exactly the right thing to say -- "re-read everything" -- and it is what a
        // client already does with it. The bridge's own work for this event is the reconciliation
        // sweep over its registry, not the notification.
        post(AccessibleEvent.Type.INVALIDATED, "NSAccessibilityLayoutChangedNotification");

        // Deliberately absent, each for a stated reason. They are put in the map as nulls rather
        // than left out, so that a reader of this file sees the decision instead of a gap, and so
        // that a type added to the enum later fails the coverage test rather than silently
        // becoming one of these.
        BY_TYPE.put(AccessibleEvent.Type.NODE_DESTROYED, null);
        BY_TYPE.put(AccessibleEvent.Type.WINDOW_OPENED, null);
        BY_TYPE.put(AccessibleEvent.Type.WINDOW_CLOSED, null);
        BY_TYPE.put(AccessibleEvent.Type.WINDOW_ACTIVATED, null);
        BY_TYPE.put(AccessibleEvent.Type.WINDOW_DEACTIVATED, null);
        // A bounds change is never posted per node, on any platform: one event per node during a
        // drag is a storm, and AppKit already posts AXMoved and AXResized for the window itself.
        BY_TYPE.put(AccessibleEvent.Type.BOUNDS_CHANGED, null);
        // The action's own return is the acknowledgement here, as §2.4 says; there is nothing to
        // tell a client that it did not just learn by asking.
        BY_TYPE.put(AccessibleEvent.Type.INVOKED, null);
    }

    /**
     * @param type the event
     * @return how this platform is told, or {@code null} when it is deliberately not told at all
     */
    static Posting of(AccessibleEvent.Type type) {
        if (!BY_TYPE.containsKey(type)) {
            throw new IllegalStateException("no macOS decision for " + type
                    + "; an event type needs a row here, including a row that says 'nothing'");
        }
        return BY_TYPE.get(type);
    }

    /**
     * The three announcement priorities, and the one place in three platforms where §12.3's
     * constants rule cannot be honoured.
     *
     * <p>{@code NSAccessibilityPriorityLow}, {@code …Medium} and {@code …High} are values of the C
     * enum {@code NSAccessibilityPriorityLevel}, not exported {@code NSString} globals like every
     * role, subrole and notification name — so there is nothing for {@code dlsym} to find and
     * {@code dump-appkit-constants.swift} lists them as absent, permanently, to keep that visible.
     * The key they ride under <em>is</em> exported and is read the ordinary way.
     *
     * @param politeness how much the announcement wants to interrupt
     * @return the number AppKit expects under {@code NSAccessibilityPriorityKey}
     */
    static int priorityFor(Accessible.Politeness politeness) {
        return switch (politeness) {
            // Polite: spoken when whatever is being read finishes, which is what low means here.
            case POLITE -> 10;
            case ASSERTIVE -> 90;
        };
    }

    /** The symbol for the key a priority rides under; exported, unlike the priorities themselves. */
    static final String PRIORITY_KEY_SYMBOL = "NSAccessibilityPriorityKey";

    /** The symbol for the key an announcement's text rides under. */
    static final String ANNOUNCEMENT_KEY_SYMBOL = "NSAccessibilityAnnouncementKey";

    /** Every notification symbol this table names, for the constants test. */
    static java.util.Set<String> symbols() {
        java.util.Set<String> symbols = new java.util.LinkedHashSet<>();
        for (Posting posting : BY_TYPE.values()) {
            if (posting != null) symbols.add(posting.notificationSymbol());
        }
        symbols.add(PRIORITY_KEY_SYMBOL);
        symbols.add(ANNOUNCEMENT_KEY_SYMBOL);
        return symbols;
    }
}
