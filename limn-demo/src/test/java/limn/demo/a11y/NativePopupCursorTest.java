package limn.demo.a11y;

import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.components.ComboBox;
import limn.components.DisplayMode;
import limn.components.Theme;
import limn.concurrent.UiRuntime;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A combo box whose list is a window of its own publishes that list in its own window's tree, as the
 * combo's child, the way a native drop-down list is its field's child (Scene#graftPopup, 2026-09-24):
 * the keyboard stays in the host, the host's cursor is the highlighted option there, each arrow key
 * is one cursor event on the combo, and the list's window publishes nothing of its own. Until then
 * the list was the popup window's own tree and the host read its cursor across the relation, and a
 * reader followed the focus into that window and back on every opening.
 *
 * <p>Here rather than in the toolkit's own tests for {@link NativePopupRelationTest}'s reason:
 * the toolkit's stub window cannot open a second window, and this is the two-window case.
 */
class NativePopupCursorTest {

    private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    private HeadlessUi ui;
    private UiRuntime runtime;
    private long nanos;
    private HeadlessBackend backend;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(() -> nanos);
        runtime = ui.runtime();
        I18n.setLocale(Locale.ENGLISH);
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
        TextRulers.install(HeadlessWindow.RULER);
        backend = new HeadlessBackend(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        TextRulers.uninstall(HeadlessWindow.RULER);
        ui.close();
    }

    @Test
    void theListIsTheFieldsChildInTheHostsTreeAndAnArrowKeyMovesItsCursorOnce() {
        ComboBox combo = new ComboBox(List.of("One", "Two", "Three"));
        combo.setSelectedIndex(1);
        HeadlessWindow host = show(combo);
        assertEquals(DisplayMode.NATIVE_WINDOW, combo.displayMode(),
                "the demo's default presentation is the native one");
        combo.requestFocus();
        settle(host);

        combo.open();
        HeadlessWindow popup = popupWindow();
        settle(host, popup);

        AccessibleTree hostTree = host.bridge().tree();
        AccessibleNode field = only(hostTree, Accessible.Role.COMBO_BOX);
        AccessibleNode list = only(hostTree, Accessible.Role.LIST);
        assertEquals(field.id(), hostTree.focused(), "the keyboard stays in the host");
        assertEquals(hostTree.indexOf(field.id()), list.parent(),
                "and the list is the field's child in the host's tree: " + Transcript.of(hostTree));
        assertFalse(popup.publishesAccessibility(), "the list's window opens no bridge of its own");
        assertEquals(0, popup.bridge().tree().nodeCount());
        AccessibleNode highlighted = activeOption(hostTree);
        assertEquals("Two", highlighted.name(), "the list opens on the selection");
        assertEquals(highlighted.id(), hostTree.activeDescendant(),
                "the host's cursor is its own option: " + Transcript.of(hostTree));
        assertEquals(highlighted.id(), hostTree.effectiveFocus());

        host.bridge().events.clear();
        host.key(Keys.DOWN);
        settle(host, popup);

        AccessibleTree after = host.bridge().tree();
        AccessibleNode moved = activeOption(after);
        assertEquals("Three", moved.name(), "Down moved the highlight: " + Transcript.of(after));
        assertEquals(moved.id(), after.activeDescendant());
        List<AccessibleEvent> cursor =
                host.bridge().eventsOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED);
        assertEquals(1, cursor.size(),
                "one cursor event in the host, on the focused combo: " + host.bridge().events);
        assertEquals(field.id(), cursor.get(0).nodeId());
        assertEquals(highlighted.id(), cursor.get(0).oldValue());
        assertEquals(moved.id(), cursor.get(0).newValue());

        host.key(Keys.ESCAPE);
        settle(host, popup);
        AccessibleTree fading = host.bridge().tree();
        assertFalse(combo.isOpen());
        assertEquals(field.id(), fading.effectiveFocus(),
                "closed, the cursor is the field at once, while the list's window still fades out: "
                        + Transcript.of(fading));
        popup.close(); // the fade's end, as the desktop backend closes the window
        settle(host);
        AccessibleTree closed = host.bridge().tree();
        assertTrue(closed.indexOf(list.id()) < 0 && closed.indexOf(moved.id()) < 0,
                "a closed window's list leaves the host's tree: " + Transcript.of(closed));
        assertEquals(field.id(), closed.effectiveFocus(), "and the cursor is still the field");
    }

    /**
     * A click in the list's window that chooses nothing -- on its scrollbar, or on the padding
     * round the rows -- makes that window the desktop's key one, and its keys then went to a scene
     * nothing in which takes them: the arrows stopped moving the highlight. They reach the combo
     * from there, and a list that closes while holding the keyboard gives it back to the field's
     * window, which on macOS otherwise got it only when it was clicked.
     */
    @Test
    void aClickInTheListThatChoosesNothingLeavesTheKeysWorkingThere() {
        ComboBox combo = new ComboBox(List.of("One", "Two", "Three"));
        combo.setSelectedIndex(1);
        HeadlessWindow host = show(combo);
        combo.requestFocus();
        combo.open();
        HeadlessWindow popup = popupWindow();
        settle(host, popup);
        int asked = host.focusRequests();

        host.desktopFocus(false);
        popup.desktopFocus(true);
        // The padding under the last row: the one above the first counts as the first row.
        limn.testing.SceneDriver.drive(popup.scene()).click(4, popup.logicalHeight() - 1);
        settle(host, popup);
        assertTrue(combo.isOpen(), "a click that chooses nothing leaves the list open");
        assertEquals(asked, host.focusRequests(), "and the keyboard where the click put it");

        popup.key(Keys.DOWN);
        assertEquals(2, combo.highlightedIndex(), "an arrow in the list's window moves the highlight");
        limn.testing.SceneDriver.drive(popup.scene()).type("o");
        assertEquals(0, combo.highlightedIndex(), "and a letter finds its option");
        popup.key(Keys.ENTER);
        assertFalse(combo.isOpen());
        assertEquals(0, combo.selectedIndex(), "Enter chooses it");
        assertTrue(host.focusRequests() > asked, "and the field's window has the keyboard back");
        host.desktopFocus(true);
        popup.desktopFocus(false);
        settle(host, popup);

        combo.open();
        runtime.drain();
        popup = backend.windows().get(backend.windows().size() - 1); // the closed one stays listed
        settle(host, popup);
        asked = host.focusRequests();
        host.desktopFocus(false);
        popup.desktopFocus(true);
        limn.testing.SceneDriver.drive(popup.scene()).click(4, popup.logicalHeight() - 1);
        popup.key(Keys.TAB);
        assertFalse(combo.isOpen(), "Tab from the list's window closes it");
        assertTrue(host.focusRequests() > asked, "and gives the field's window the keyboard");
    }

    private HeadlessWindow show(limn.scene.Widget<?> content) {
        Column root = new Column();
        root.add(content);
        HeadlessWindow window = backend.open("Limn popups", 400, 300);
        new Scene(root).bind(window);
        window.frame();
        window.desktopFocus(true);
        settle(window);
        return window;
    }

    /** The window a popup opened as: creating it is posted, so the queue is drained first. */
    private HeadlessWindow popupWindow() {
        runtime.drain();
        assertEquals(2, backend.windows().size(), "the host and the popup, and nothing else");
        return backend.windows().get(1);
    }

    /** Renders every window in turn, one frame of scene time apart, until everything settled. */
    private void settle(HeadlessWindow... windows) {
        for (int i = 0; i < 24; i++) {
            nanos += FRAME_NANOS;
            runtime.drain();
            for (HeadlessWindow window : windows) {
                window.frame();
            }
        }
    }

    private static AccessibleNode activeOption(AccessibleTree tree) {
        AccessibleNode found = null;
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.LIST_ITEM && node.has(Accessible.State.ACTIVE)) {
                assertTrue(found == null, "two active options in " + Transcript.of(tree));
                found = node;
            }
        }
        assertNotNull(found, "no active option in " + Transcript.of(tree));
        return found;
    }

    private static AccessibleNode only(AccessibleTree tree, Accessible.Role role) {
        AccessibleNode found = null;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                assertTrue(found == null, "two " + role + " nodes in " + Transcript.of(tree));
                found = tree.node(i);
            }
        }
        assertNotNull(found, "no " + role + " in " + Transcript.of(tree));
        return found;
    }
}
