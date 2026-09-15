package limn.backend.lwjgl.a11y.macos;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the user is, as AppKit is told it (decision 1; semantics 4; M3 and MACOS-NEW-2): the focused
 * element is the tree's effective focus — the cursor item under the focused widget — a cursor move is
 * a focus move posted at application level, and a cursor resolved into a native popup's tree is
 * answered with that window's element (decision 5).
 */
class AxFocusTest {

    /** A window whose list holds the keyboard, with its second item the cursor. */
    private static AccessibleTree aFocusedListWithACursor(long cursorId) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("w"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 10, 200, 100);
        a.role(Accessible.Role.LIST);
        a.name(I18nString.literal("Fruit"), Accessible.NameFrom.EXPLICIT);
        a.selection(false, false);
        a.inherited(true, true, true, true, true);
        for (int i = 0; i < 3; i++) {
            long id = 1002 + i;
            a.begin(id, 1, Locale.ENGLISH, 10, 10 + 30L * i, 200, 30);
            a.role(Accessible.Role.LIST_ITEM);
            a.name(I18nString.literal("Item " + i), Accessible.NameFrom.CONTENT);
            a.selectionItem(id == cursorId, i + 1, 3);
            if (id == cursorId) a.state(Accessible.State.ACTIVE, true);
            a.inherited(true, true, true, false, false);
            a.end();
        }
        a.end();
        a.end();
        return a.publish(1001, 0, 0, 1f, true);
    }

    private static List<String> posted(List<String> trace) {
        return trace.stream().filter(line -> line.startsWith("posted "))
                .map(line -> line.substring("posted ".length())).toList();
    }

    @Test
    void theFocusedElementIsTheCursorItemAndNotTheWidgetAroundIt() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aFocusedListWithACursor(1003);
        bridge.publish(tree, false);
        long[] items = bridge.childElementsOf(tree.find(1001));
        assertEquals(1003, tree.effectiveFocus(), "the fixture: the cursor item is the effective focus");
        assertEquals(items[1], bridge.focusedElement(),
                "VoiceOver asks where the focus went and must land on the item the user is on");
        assertTrue(bridge.isFocused(tree.find(1003)), "the item says it is focused");
        assertFalse(bridge.isFocused(tree.find(1001)),
                "and the list, which only holds the keyboard around it, does not");
        assertFalse(bridge.isFocused(tree.find(1002)));
    }

    @Test
    void aFocusedWidgetWithNoCursorIsItselfTheFocusedElement() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        AccessibleTree tree = aFocusedListWithACursor(0);
        bridge.publish(tree, false);
        long list = bridge.childElementsOf(tree.root())[0];
        assertEquals(list, bridge.focusedElement());
        assertTrue(bridge.isFocused(tree.find(1001)));
    }

    @Test
    void aCursorMoveIsToldAsAFocusChangeAtApplicationLevel() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        List<String> trace = new ArrayList<>();
        bridge.trace(trace::add);
        AccessibleTree tree = aFocusedListWithACursor(1003);
        bridge.publish(tree, false);
        bridge.childElementsOf(tree.root());
        bridge.childElementsOf(tree.find(1001));   // the list and its items are held
        bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED, 1001,
                1002L, 1003L));
        bridge.frameEnded();
        assertEquals(List.of("NSAccessibilityFocusedUIElementChangedNotification"), posted(trace),
                "a cursor move is where the user went, told the way a focus move is; it was a "
                        + "selected-children change on the list, which re-read a selection nothing moved");
    }

    @Test
    void aFocusMoveAndACursorMoveInOneFrameAreOneFocusChange() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        List<String> trace = new ArrayList<>();
        bridge.trace(trace::add);
        AccessibleTree tree = aFocusedListWithACursor(1003);
        bridge.publish(tree, false);
        bridge.childElementsOf(tree.find(1001));
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.FOCUS_CHANGED, 1001));
        bridge.emit(AccessibleEvent.property(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED, 1001,
                0L, 1003L));
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.NAME_CHANGED, 1003));
        bridge.frameEnded();
        assertEquals(List.of("NSAccessibilityTitleChangedNotification",
                        "NSAccessibilityFocusedUIElementChangedNotification"), posted(trace),
                "one move for a reader, told once and after what else the frame said");
    }

    @Test
    void theModelsInvalidatedSweepsTheRegistryAndTellsWhereTheUserIsAgain() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        List<String> trace = new ArrayList<>();
        bridge.trace(trace::add);
        bridge.publish(aFocusedListWithACursor(1003), false);
        bridge.childElementsOf(aFocusedListWithACursor(1003).find(1001));
        assertEquals(4, bridge.elementCount());
        int pushes = bridge.pushes();

        // The model's collapse: a publish too wide for its budget, and a tail that did not move the
        // focus. The node the registry held for item 1004 is gone from the tree.
        AccessibleTree shrunk = aFocusedListWithACursorAndTwoItems();
        bridge.publish(shrunk, false);
        bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.INVALIDATED, 0));
        bridge.frameEnded();
        assertEquals(3, bridge.elementCount(), "semantics 7: macOS reconciles on the model's INVALIDATED");
        assertEquals(pushes + 1, bridge.pushes(), "and the array AppKit holds is pushed again");
        assertEquals(List.of("NSAccessibilityFocusedUIElementChangedNotification"), posted(trace),
                "the sweep may have released what the reader stood on, so where it is goes out again");
    }

    @Test
    void aCollapsedQueueTellsWhereTheUserIsAgainAndNothingFocusedTellsNothing() {
        AxBridge bridge = AxBridge.withoutThePlatform();
        List<String> trace = new ArrayList<>();
        bridge.trace(trace::add);
        AccessibleTree tree = aFocusedListWithACursor(1003);
        bridge.publish(tree, false);
        for (int i = 0; i <= AxEvents.CAPACITY; i++) {
            bridge.emit(AccessibleEvent.of(AccessibleEvent.Type.VALUE_CHANGED, 1002));
        }
        bridge.frameEnded();
        assertEquals(List.of("NSAccessibilityFocusedUIElementChangedNotification"), posted(trace),
                "the focus change the collapse dropped is said again (semantics 4)");

        AxBridge unfocused = AxBridge.withoutThePlatform();
        List<String> quiet = new ArrayList<>();
        unfocused.trace(quiet::add);
        unfocused.publish(AccessibleTree.EMPTY, false);
        for (int i = 0; i <= AxEvents.CAPACITY; i++) {
            unfocused.emit(AccessibleEvent.of(AccessibleEvent.Type.VALUE_CHANGED, 1002));
        }
        unfocused.frameEnded();
        assertTrue(posted(quiet).isEmpty(), "with nobody anywhere, there is nowhere to send a reader");
    }

    private static AccessibleTree aFocusedListWithACursorAndTwoItems() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("w"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 10, 200, 100);
        a.role(Accessible.Role.LIST);
        a.name(I18nString.literal("Fruit"), Accessible.NameFrom.EXPLICIT);
        a.selection(false, false);
        a.inherited(true, true, true, true, true);
        for (int i = 0; i < 2; i++) {
            long id = 1002 + i;
            a.begin(id, 1, Locale.ENGLISH, 10, 10 + 30L * i, 200, 30);
            a.role(Accessible.Role.LIST_ITEM);
            a.name(I18nString.literal("Item " + i), Accessible.NameFrom.CONTENT);
            a.selectionItem(id == 1003, i + 1, 2);
            if (id == 1003) a.state(Accessible.State.ACTIVE, true);
            a.inherited(true, true, true, false, false);
            a.end();
        }
        a.end();
        a.end();
        return a.publish(1001, 0, 0, 1f, true);
    }

    /** Decision 5's two windows: a host field whose cursor is the ACTIVE day of its native popup. */
    private record TwoWindows(AccessibleTree host, AccessibleTree popup, long field, long day) {

        static TwoWindows aFieldWithItsCursorInAPopup() {
            Accessibility popupWalk = new Accessibility();
            long popupRoot = popupWalk.mint();
            long grid = popupWalk.mint();
            long day = popupWalk.mint();
            popupWalk.beginWalk(200, 200, Locale.ENGLISH);
            popupWalk.begin(popupRoot, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 200, 200);
            popupWalk.role(Accessible.Role.WINDOW);
            popupWalk.inherited(true, true, true, false, false);
            popupWalk.begin(grid, 0, Locale.ENGLISH, 0, 0, 200, 200);
            popupWalk.role(Accessible.Role.GROUP);
            popupWalk.inherited(true, true, true, false, false);
            popupWalk.begin(day, 1, Locale.ENGLISH, 10, 10, 20, 20);
            popupWalk.role(Accessible.Role.CELL);
            popupWalk.name(I18nString.literal("15"), Accessible.NameFrom.CONTENT);
            popupWalk.state(Accessible.State.ACTIVE, true);
            popupWalk.inherited(true, true, true, false, false);
            popupWalk.end();
            popupWalk.end();
            popupWalk.end();
            AccessibleTree popup = popupWalk.publish(0, 0, 0, 1f, true);

            Accessibility hostWalk = new Accessibility();
            long hostRoot = hostWalk.mint();
            long field = hostWalk.mint();
            hostWalk.beginWalk(400, 300, Locale.ENGLISH);
            hostWalk.begin(hostRoot, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
            hostWalk.role(Accessible.Role.WINDOW);
            hostWalk.inherited(true, true, true, false, false);
            hostWalk.begin(field, 0, Locale.ENGLISH, 10, 10, 200, 30);
            hostWalk.role(Accessible.Role.TEXT_FIELD);
            hostWalk.inherited(true, true, true, true, true);
            hostWalk.end();
            hostWalk.end();
            hostWalk.foreignActiveDescendant(day);
            AccessibleTree host = hostWalk.publish(field, 0, 0, 1f, true);
            assertEquals(day, host.effectiveFocus(), "the fixture: the cursor is the popup's day");
            return new TwoWindows(host, popup, field, day);
        }
    }

    @Test
    void aCursorInAnotherWindowsTreeIsAnsweredWithThatWindowsElement() {
        TwoWindows windows = TwoWindows.aFieldWithItsCursorInAPopup();
        AxBridge host = AxBridge.withoutThePlatform();
        AxBridge popup = AxBridge.withoutThePlatform();
        try {
            popup.publish(windows.popup(), false);
            host.publish(windows.host(), false);
            int hostElements = host.elementCount();
            int popupElements = popup.elementCount();

            long answered = host.focusedElement();
            assertNotEquals(0L, answered, "the host's view names where the user is, in the other window");
            assertEquals(popupElements + 1, popup.elementCount(),
                    "the day's element is minted by the popup's bridge, whose tree it stands for");
            assertEquals(hostElements, host.elementCount(), "and nothing of the popup's in the host");
            assertEquals(windows.day(), popup.nodeFor(answered).id());

            assertEquals(answered, popup.focusedElement(),
                    "the popup's own view, with nothing of its own focused, names the same element");
            assertTrue(popup.isFocused(windows.popup().find(windows.day())), "the day says it is focused");
            assertFalse(host.isFocused(windows.host().find(windows.field())), "and the field does not");

            List<String> trace = new ArrayList<>();
            host.trace(trace::add);
            host.emit(AccessibleEvent.property(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED,
                    windows.field(), 0L, windows.day()));
            host.frameEnded();
            assertEquals(List.of("NSAccessibilityFocusedUIElementChangedNotification"), posted(trace),
                    "posted at application level by the window whose field moved it");
        } finally {
            host.detach();
            popup.detach();
        }
        assertEquals(0L, host.focusedElement(), "a detached bridge answers from an empty tree");
    }

    @Test
    void aCursorThatLeavesAnotherWindowIsNoLongerAnsweredThereOnceThatWindowPublishes() {
        TwoWindows windows = TwoWindows.aFieldWithItsCursorInAPopup();
        AxBridge host = AxBridge.withoutThePlatform();
        AxBridge popup = AxBridge.withoutThePlatform();
        try {
            popup.publish(windows.popup(), false);
            host.publish(windows.host(), false);
            AccessibleNode day = windows.popup().find(windows.day());
            assertTrue(popup.isFocused(day), "the fixture: the host's cursor is the popup's day");

            // The host publishes a tree whose field has no cursor in the popup any more, and nothing
            // happens in the popup's own window: the popup's answer must follow the host's publish.
            Accessibility hostWalk = new Accessibility();
            hostWalk.beginWalk(400, 300, Locale.ENGLISH);
            hostWalk.begin(windows.host().root().id(), AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
            hostWalk.role(Accessible.Role.WINDOW);
            hostWalk.inherited(true, true, true, false, false);
            hostWalk.begin(windows.field(), 0, Locale.ENGLISH, 10, 10, 200, 30);
            hostWalk.role(Accessible.Role.TEXT_FIELD);
            hostWalk.inherited(true, true, true, true, true);
            hostWalk.end();
            hostWalk.end();
            host.publish(hostWalk.publish(windows.field(), 0, 0, 1f, true), false);

            assertFalse(popup.isFocused(day),
                    "an answer derived from another window's tree is resolved again when that tree changes");
            assertEquals(0L, popup.focusedElement());
        } finally {
            host.detach();
            popup.detach();
        }
    }

    @Test
    void aPopupNoLongerAnswersTheCursorOfAWindowThatClosed() {
        TwoWindows windows = TwoWindows.aFieldWithItsCursorInAPopup();
        AxBridge host = AxBridge.withoutThePlatform();
        AxBridge popup = AxBridge.withoutThePlatform();
        AxBridge third = AxBridge.withoutThePlatform();
        try {
            // A third window stays open throughout, so the popup is never the only one left.
            third.publish(aFocusedListWithACursor(0), false);
            popup.publish(windows.popup(), false);
            host.publish(windows.host(), false);
            AccessibleNode day = windows.popup().find(windows.day());
            assertTrue(popup.isFocused(day), "the fixture: the host's cursor is the popup's day");
            host.detach();
            assertFalse(popup.isFocused(day),
                    "a window that closed without publishing again takes its cursor with it");
        } finally {
            popup.detach();
            third.detach();
        }
    }

    @Test
    void aDetachedWindowNoLongerAnswersForAnotherWindowsCursor() {
        TwoWindows windows = TwoWindows.aFieldWithItsCursorInAPopup();
        AxBridge host = AxBridge.withoutThePlatform();
        AxBridge popup = AxBridge.withoutThePlatform();
        try {
            popup.publish(windows.popup(), false);
            host.publish(windows.host(), false);
            assertTrue(popup.isOpen() && host.isOpen(), "a publish of a tree opens a window");
            popup.detach();
            assertFalse(popup.isOpen(), "a detach closes it, or the set holds every window ever opened");
            assertEquals(0L, host.focusedElement(),
                    "a cursor in a window that closed is nowhere: not a stale element of a freed registry");
        } finally {
            host.detach();
        }
    }
}
