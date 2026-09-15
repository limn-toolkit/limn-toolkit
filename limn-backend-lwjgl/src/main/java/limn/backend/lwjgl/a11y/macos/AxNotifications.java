package limn.backend.lwjgl.a11y.macos;

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
 * <p><b>And two entries are posted somewhere other than their own node.</b> A focus change is
 * delivered only to an observer registered on the <em>application</em> element — measured in the
 * spike, where an observer on the element itself received nothing — so it is posted at application
 * level and never per element; and a cursor move under the focused node is a focus change here
 * (decision 1), posted the same way. That is the one place where "post the notification on its
 * subject" is wrong here, and it is a fact about AppKit rather than a choice. The bridge posts at most
 * one of them per frame: a publish that moved both the focus and the cursor is one move for a reader.
 *
 * <p><b>And what is about the whole window is posted on the window</b> (MACOS-NEW-3): an announcement,
 * with its text and priority, and a layout change for the model's {@code INVALIDATED} or a change of the
 * elided root's children — at most one of those per frame. Each named node 0 or the root, which no
 * element stands for, so none of them had ever been posted.
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
        /**
         * On the window AppKit vends for this scene: what has no element of its own to be posted on,
         * because it is about the whole window — an announcement, and a layout change of the window
         * root the bridge elides. Read on the macOS 26.6.2 guest, 2026-09-15
         * ({@code scripts/a11y/macos/announcement-probe.swift}): AXAnnouncementRequested and
         * AXLayoutChanged posted on the window reached an observer registered on the window and one
         * registered on the application; posted on NSApp, only the application's; posted on the
         * content view, nobody's.
         */
        WINDOW,
    }

    /**
     * A notification symbol and where to post it, or {@code null} for an event this platform is not
     * told.
     *
     * @param notificationSymbol the AppKit global to resolve, or the name itself when {@code literal}
     * @param subject            where it is posted
     * @param literal            whether the name is a HIServices macro with no symbol to resolve
     */
    record Posting(String notificationSymbol, Subject subject, boolean literal) {
        Posting(String notificationSymbol, Subject subject) {
            this(notificationSymbol, subject, false);
        }
    }

    /**
     * {@code kAXElementBusyChangedNotification}, which is a {@code CFSTR} macro in HIServices'
     * {@code AXNotificationConstants.h} and not an AppKit global: there is nothing for {@code dlsym}
     * to find, which is the constants rule's second exception after the announcement priorities.
     * The value was read off this build's SDK on 2026-09-13, not recalled.
     */
    static final String BUSY_CHANGED = "AXElementBusyChanged";

    private static final Map<AccessibleEvent.Type, Posting> BY_TYPE =
            new EnumMap<>(AccessibleEvent.Type.class);

    private static void post(AccessibleEvent.Type type, String symbol) {
        BY_TYPE.put(type, new Posting(symbol, Subject.NODE));
    }

    private static void postToApplication(AccessibleEvent.Type type, String symbol) {
        BY_TYPE.put(type, new Posting(symbol, Subject.APPLICATION));
    }

    private static void postToWindow(AccessibleEvent.Type type, String symbol) {
        BY_TYPE.put(type, new Posting(symbol, Subject.WINDOW));
    }

    static {
        postToApplication(AccessibleEvent.Type.FOCUS_CHANGED,
                "NSAccessibilityFocusedUIElementChangedNotification");
        // The cursor moving under the focused node is a focus move on this platform (decision 1;
        // semantics 4): the focused element is the cursor item, so a client is told the focus
        // changed and asks where. It was a selected-children change on the container, which told a
        // reader to re-read a selection the cursor had not touched, on an attribute nothing answered.
        postToApplication(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED,
                "NSAccessibilityFocusedUIElementChangedNotification");
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
        // An announcement has no node (its identifier is 0), so it goes on the window, with its text
        // and priority as user info (MACOS-NEW-3): it was posted on the element of node 0, which no
        // client holds, so no announcement ever reached AppKit.
        postToWindow(AccessibleEvent.Type.ANNOUNCEMENT,
                "NSAccessibilityAnnouncementRequestedNotification");
        // The collapse of a queue too small for the difference it was handed. One layout-changed on
        // the window is exactly the right thing to say -- "re-read everything" -- and it is what a
        // client already does with it. The bridge's own work for this event is the reconciliation
        // sweep over its registry, not the notification. On the window, because the event names no
        // node (MACOS-NEW-3).
        postToWindow(AccessibleEvent.Type.INVALIDATED, "NSAccessibilityLayoutChangedNotification");

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
     * @return how this platform is told, or {@code null} when it is not told: by a row that says
     *         so, or by a type with no row, which the coverage test keeps from shipping and which
     *         at run time is the silence every platform answers a gap with
     */
    static Posting of(AccessibleEvent.Type type) {
        return BY_TYPE.get(type);
    }

    /**
     * @param type an event type
     * @return whether this table has a row for it, a row saying "nothing" included — which is what the
     *         coverage test holds every type to, since {@link #of(AccessibleEvent.Type)} answers null
     *         both for a row of nothing and for no row at all (MACOS-NEW-13)
     */
    static boolean hasDecision(AccessibleEvent.Type type) {
        return BY_TYPE.containsKey(type);
    }

    /**
     * What a structure change of the window root is posted as: the root is elided (§2.2), so no
     * element of ours stands for it, and its children's change is the window's layout changing
     * (MACOS-NEW-3).
     */
    static final Posting WINDOW_LAYOUT_CHANGED =
            new Posting("NSAccessibilityLayoutChangedNotification", Subject.WINDOW);

    /**
     * The same decision for one event, where the state that changed can decide it.
     *
     * <p>Two states do. {@code BUSY} is not read back off {@code AXValue} as every other state is,
     * but off the element's own busy attribute, which has a notification of its own. A value-changed
     * posted for it would send a client to re-read an attribute that did not move.
     *
     * <p>And {@code ACTIVE} is told nothing at all: no attribute of this platform is read back off it.
     * Where it matters — the cursor under the focused widget — it is the focused element, and the
     * focused node's {@code ACTIVE_DESCENDANT_CHANGED} already posts the focus change a reader asks
     * after; anywhere else it is a cursor nobody is on. A value-changed for it, on the row the cursor
     * left and on the row it reached, was two posts per arrow that a native outline never makes (read
     * on the macOS 26.6.2 guest, 2026-09-15, {@code scripts/a11y/macos/outline-probe.swift}: a selection
     * write delivered only {@code AXSelectedRowsChanged} to an observer that also asked for
     * {@code AXValueChanged}).
     *
     * @param event the event
     * @return how this platform is told, or {@code null} when it is not
     */
    static Posting of(AccessibleEvent event) {
        if (event.type() == AccessibleEvent.Type.STATE_CHANGED) {
            if (event.state() == Accessible.State.BUSY) return new Posting(BUSY_CHANGED, Subject.NODE, true);
            if (event.state() == Accessible.State.ACTIVE) return null;
        }
        return of(event.type());
    }

    /**
     * What a {@code SELECTION_CHANGED} is posted as, on its container, by the shape of that container's
     * members: the notification of the attribute its selection is read from.
     *
     * @param shape what the container's members are
     * @return the posting
     */
    static Posting selection(AxGrid.SelectionShape shape) {
        return switch (shape) {
            case ROWS -> SELECTED_ROWS;
            case CELLS -> SELECTED_CELLS;
            case CHILDREN -> of(AccessibleEvent.Type.SELECTION_CHANGED);
        };
    }

    /**
     * What an outline row opening or closing is posted as: {@code AXRowExpanded} or
     * {@code AXRowCollapsed} on the row, and a row-count change on the outline, which is what a native
     * NSOutlineView posted when its row's AXDisclosing was set (read on the macOS 26.6.2 guest,
     * 2026-09-15, outline-probe.swift: RowCountChanged on the outline, RowExpanded on the row).
     *
     * @param open whether the row opened
     * @return the posting on the row
     */
    static Posting disclosure(boolean open) {
        return open ? ROW_EXPANDED : ROW_COLLAPSED;
    }

    static final Posting ROW_EXPANDED = new Posting("NSAccessibilityRowExpandedNotification", Subject.NODE);
    static final Posting ROW_COLLAPSED = new Posting("NSAccessibilityRowCollapsedNotification", Subject.NODE);
    static final Posting ROW_COUNT_CHANGED =
            new Posting("NSAccessibilityRowCountChangedNotification", Subject.NODE);

    private static final Posting SELECTED_ROWS =
            new Posting("NSAccessibilitySelectedRowsChangedNotification", Subject.NODE);
    private static final Posting SELECTED_CELLS =
            new Posting("NSAccessibilitySelectedCellsChangedNotification", Subject.NODE);

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
            if (posting != null && !posting.literal()) symbols.add(posting.notificationSymbol());
        }
        symbols.add(ROW_EXPANDED.notificationSymbol());
        symbols.add(ROW_COLLAPSED.notificationSymbol());
        symbols.add(ROW_COUNT_CHANGED.notificationSymbol());
        symbols.add(SELECTED_ROWS.notificationSymbol());
        symbols.add(SELECTED_CELLS.notificationSymbol());
        symbols.add(PRIORITY_KEY_SYMBOL);
        symbols.add(ANNOUNCEMENT_KEY_SYMBOL);
        return symbols;
    }
}
