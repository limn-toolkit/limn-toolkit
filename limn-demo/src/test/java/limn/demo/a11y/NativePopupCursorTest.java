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
 * The cursor follows the user across windows (decision 5; ADR 039 §1.10, amended 2026-09-14):
 * a combo box whose list is a window of its own keeps the keyboard in the host, and the host's
 * tree answers its effective focus with the highlighted option in the popup's tree, read across
 * the {@code CONTROLLER_FOR} relation; each arrow key is one cursor event on the combo in the
 * host, naming the popup's option.
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
    void theHostsEffectiveFocusIsTheNativePopupsHighlightedOptionAndAnArrowKeyMovesItOnce() {
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
        AccessibleTree popupTree = popup.bridge().tree();
        AccessibleNode field = only(hostTree, Accessible.Role.COMBO_BOX);
        assertEquals(field.id(), hostTree.focused(), "the keyboard stays in the host");
        assertEquals(0, popupTree.focused(), "and the popup window focuses nothing of its own");
        AccessibleNode highlighted = activeOption(popupTree);
        assertEquals("Two", highlighted.name(), "the list opens on the selection");

        assertEquals(highlighted.id(), hostTree.activeDescendant(),
                "the host's cursor is read across the relation, off the popup's tree: "
                        + Transcript.of(hostTree) + Transcript.of(popupTree));
        assertEquals(highlighted.id(), hostTree.effectiveFocus());
        assertFalse(hostTree.holds(hostTree.activeDescendant()),
                "and the number says it is the other window's");
        assertTrue(popupTree.holds(hostTree.activeDescendant()));
        assertEquals(0, popupTree.activeDescendant(),
                "the popup's own tree has no focused node, so no cursor of its own");

        host.bridge().events.clear();
        host.key(Keys.DOWN);
        settle(host, popup);

        AccessibleNode moved = activeOption(popup.bridge().tree());
        assertEquals("Three", moved.name(), "Down moved the highlight in the popup");
        assertEquals(moved.id(), host.bridge().tree().activeDescendant(),
                "and the host's tree followed it: " + Transcript.of(host.bridge().tree()));
        List<AccessibleEvent> cursor =
                host.bridge().eventsOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED);
        assertEquals(1, cursor.size(),
                "one cursor event in the host, on the focused combo: " + host.bridge().events);
        assertEquals(field.id(), cursor.get(0).nodeId());
        assertEquals(highlighted.id(), cursor.get(0).oldValue());
        assertEquals(moved.id(), cursor.get(0).newValue());
        assertTrue(popup.bridge().eventsOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED).isEmpty(),
                "the popup, focusing nothing, announces no cursor of its own: "
                        + popup.bridge().events);
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
