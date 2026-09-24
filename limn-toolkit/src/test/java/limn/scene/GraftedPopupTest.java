package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.scene.internal.SceneAccess;
import limn.testing.RecordingAccessibilityBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A popup in a window of its own, published by the window that opened it: {@link Scene#graftPopup}.
 * A native drop-down list is its field's child, and a reader never leaves the field's window while
 * it is open; until this, the popup's window published a tree of its own, and a reader followed the
 * focus into it and back on every opening.
 */
class GraftedPopupTest extends AccessibleTestBase {

    private RecordingWindow popupWindow;
    private RecordingAccessibilityBridge popupBridge;
    private Probe opener;
    private Group list;
    private Probe apple;

    /** A host with a focused combo at (100, 200) on screen, and its list's window at (110, 260). */
    private Scene openAList() {
        Group root = new Group();
        opener = new Probe(Accessible.Role.COMBO_BOX, "Fruit");
        opener.setFocusable(true);
        root.add(opener);
        bind(root);
        window.screenX = 100;
        window.screenY = 200;
        scene.requestFocus(opener);
        frame();

        list = new Group();
        list.setAccessibleRole(Accessible.Role.LIST);
        list.setAccessibleName("Fruits");
        apple = new Probe(Accessible.Role.LIST_ITEM, "Apple");
        apple.active = true;
        apple.actions = new Accessible.Action[] {Accessible.Action.PRESS};
        list.add(apple);
        list.setInheritanceHost(opener);

        popupWindow = new RecordingWindow();
        popupWindow.screenX = 110;
        popupWindow.screenY = 260;
        popupBridge = RecordingAccessibilityBridge.listening();
        popupWindow.accessibility = popupBridge;
        Scene popup = new Scene(list, nanos::get);
        scene.graftPopup(popup);
        popup.bind(popupWindow);
        popup.renderFrame(canvas);
        frame();
        bridge.events.clear();
        return popup;
    }

    @Test
    void theListIsTheFieldsChildInTheFieldsTreeAtItsPlaceOnScreen() {
        openAList();
        AccessibleNode field = node("Fruit");
        AccessibleNode listNode = node("Fruits");
        assertEquals(tree().indexOf(field.id()), listNode.parent(), describe(tree()));
        AccessibleNode option = node("Apple");
        assertEquals(tree().indexOf(listNode.id()), option.parent());
        assertEquals(10, option.x(), 0.001, "the list's window is 10 points right of the host's");
        assertEquals(60, option.y(), 0.001, "and 60 points down");
        assertTrue(tree().holds(option.id()), "an identifier of the host's own");
        assertEquals(0, popupBridge.attachments, "the list's window was never asked for a bridge");
        assertTrue(popupBridge.published.isEmpty());
    }

    @Test
    void theListsCursorIsTheFieldsActiveDescendant() {
        openAList();
        assertEquals(node("Fruit").id(), tree().focused());
        assertEquals(node("Apple").id(), tree().activeDescendant(),
                "the first ACTIVE node below the focused field: " + describe(tree()));
    }

    @Test
    void aVerbOnAnOptionIsPerformedInThePopupsScene() {
        openAList();
        assertTrue(bridge.host.perform(node("Apple").id(), Accessible.Action.PRESS,
                Accessible.Argument.NONE));
        runtime.drain();
        assertEquals(1, apple.performed.size(), "the option itself performed it: " + apple.performed);
    }

    @Test
    void aChangeInThePopupWalksTheHostAtOnce() {
        openAList();
        int requests = window.frameRequests;
        apple.name = limn.i18n.I18nString.literal("Apricot");
        apple.invalidateAccessible();
        assertTrue(window.frameRequests > requests, "the host is asked for a frame, not the popup");
        frame();
        assertEquals("Apricot", node("Apricot").name());
    }

    @Test
    void anAnnouncementInThePopupIsSaidByTheHost() {
        Scene popup = openAList();
        popup.announce("Three fruits", Accessible.Politeness.POLITE);
        frame();
        assertTrue(bridge.events.stream().anyMatch(e -> e.type() == AccessibleEvent.Type.ANNOUNCEMENT),
                "said through the host's bridge: " + bridge.events);
    }

    @Test
    void aClosedWindowsListLeavesTheTree() {
        Scene popup = openAList();
        long option = node("Apple").id();
        SceneAccess.input(popup).windowClosed();
        frame();
        assertTrue(tree().indexOf(option) < 0, describe(tree()));
        assertEquals(node("Fruit").id(), tree().effectiveFocus(), "the cursor is the field again");
    }

    @Test
    void aPopupIsGraftedOnceBeforeItIsBoundAndNamesItsOpener() {
        Group root = new Group();
        Probe field = new Probe(Accessible.Role.COMBO_BOX, "Fruit");
        root.add(field);
        bind(root);
        Group orphan = new Group();
        assertThrows(IllegalArgumentException.class, () -> scene.graftPopup(new Scene(orphan, nanos::get)),
                "a root that names no opener has nowhere to hang");
        Group bound = new Group();
        bound.setInheritanceHost(field);
        Scene already = new Scene(bound, nanos::get);
        already.bind(new RecordingWindow());
        assertThrows(IllegalStateException.class, () -> scene.graftPopup(already),
                "a bound popup has asked its window for a bridge already");
        assertFalse(scene.isGraftedPopup(already));
    }
}
