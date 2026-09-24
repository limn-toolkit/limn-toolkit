package limn.demo.a11y;

import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.AccessibleTree;
import limn.components.ComboBox;
import limn.components.DisplayMode;
import limn.components.Theme;
import limn.components.date.DatePicker;
import limn.concurrent.UiRuntime;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A popup that is a window of its own says who opened it, and the opener says which popup it
 * opened: the pair ADR 039 §1.11 and §5.4 promise, which the 2026-09-13 audit found published in
 * neither direction (CRIT-2). Since 2026-09-24 the popup is grafted into the opener's tree
 * (Scene#graftPopup), as a native drop-down list is its field's child, so both ends of the pair are
 * in one tree and its window publishes nothing of its own.
 *
 * <p>Here rather than in the toolkit's own tests because the toolkit's stub window cannot open a
 * second window, while {@link HeadlessBackend} opens a real {@link HeadlessWindow} for a popup
 * exactly as the desktop backends do.
 */
class NativePopupRelationTest {

    private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    /** The date picker's today, so that the month its calendar opens on is the same on every run. */
    private static final Clock SEPTEMBER_9 =
            Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);

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
    void aNativeComboListNamesItsFieldAndTheFieldNamesTheListBack() {
        ComboBox combo = new ComboBox(List.of("One", "Two", "Three"));
        HeadlessWindow host = show(combo);
        assertEquals(DisplayMode.NATIVE_WINDOW, combo.displayMode(),
                "the demo's default presentation is the native one");

        combo.open();
        HeadlessWindow popup = popupWindow();
        settle(host, popup);

        AccessibleTree tree = host.bridge().tree();
        AccessibleNode list = only(tree, Accessible.Role.LIST);
        AccessibleNode field = only(tree, Accessible.Role.COMBO_BOX);
        assertLinked(tree, field, list);
        assertFalse(popup.publishesAccessibility(), "the list's window publishes nothing of its own");
    }

    @Test
    void aNativeCalendarNamesItsPickerAndThePickerNamesTheCalendarBack() {
        DatePicker picker = new DatePicker().setClock(SEPTEMBER_9);
        HeadlessWindow host = show(picker);

        picker.open();
        assertEquals(DisplayMode.NATIVE_WINDOW, picker.displayMode(),
                "the picker's default presentation is the native one");
        HeadlessWindow popup = popupWindow();
        settle(host, popup);

        AccessibleTree tree = host.bridge().tree();
        AccessibleNode calendar = only(tree, Accessible.Role.TABLE);
        AccessibleNode panel = popupRootOf(tree);
        assertEquals(Accessible.Role.GROUP, panel.role(), "the card the grid sits on");
        assertTrue(isBelow(tree, calendar, panel), "and the grid is on it: " + Transcript.of(tree));
        assertLinked(tree, pickerNode(tree), panel);
        assertFalse(popup.publishesAccessibility(), "the calendar's window publishes nothing of its own");
    }

    /**
     * The link leaves with the popup: once its window closes, the host's next publish carries
     * no {@code CONTROLLER_FOR} and none of the popup's nodes.
     */
    @Test
    void closingTheNativePopupWithdrawsTheMirrorFromTheOpener() {
        DatePicker picker = new DatePicker().setClock(SEPTEMBER_9);
        HeadlessWindow host = show(picker);
        picker.open();
        HeadlessWindow popup = popupWindow();
        settle(host, popup);
        assertTrue(has(pickerNode(host.bridge().tree()), Accessible.Relation.CONTROLLER_FOR),
                "linked while open");

        picker.close();
        // The picker fades its window out on wall time and asks for its close when the fade
        // ends; the close itself is the desktop's, and it is what a headless window can do
        // deterministically: tear the window down as the backend does once the fade is over.
        popup.close();
        assertTrue(popup.isClosed());
        settle(host);

        assertFalse(has(pickerNode(host.bridge().tree()), Accessible.Relation.CONTROLLER_FOR),
                "the opener no longer names a popup that is gone: "
                        + Transcript.of(host.bridge().tree()));
        for (int i = 0; i < host.bridge().tree().nodeCount(); i++) {
            assertNotEquals(Accessible.Role.TABLE, host.bridge().tree().node(i).role(),
                    "and the calendar left the tree with its window");
        }
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

    private static AccessibleNode pickerNode(AccessibleTree tree) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.GROUP && node.expand() != null) {
                return node;
            }
        }
        throw new AssertionError("no picker group in " + Transcript.of(tree));
    }

    private static AccessibleNode only(AccessibleTree tree, Accessible.Role role) {
        AccessibleNode found = null;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                assertNull(found, "two " + role + " nodes in " + Transcript.of(tree));
                found = tree.node(i);
            }
        }
        assertNotNull(found, "no " + role + " in " + Transcript.of(tree));
        return found;
    }

    private static boolean has(AccessibleNode node, Accessible.Relation kind) {
        for (AccessibleRelation relation : node.relations()) {
            if (relation.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    private static long targetOf(AccessibleNode node, Accessible.Relation kind, String what) {
        for (AccessibleRelation relation : node.relations()) {
            if (relation.kind() == kind) {
                return relation.target();
            }
        }
        throw new AssertionError(what + " carries no " + kind + ": " + node.relations());
    }

    /**
     * The popup's root is the opener's child in the one tree, names the opener, and is named back.
     */
    private static void assertLinked(AccessibleTree tree, AccessibleNode opener, AccessibleNode popupRoot) {
        assertEquals(tree.indexOf(opener.id()), popupRoot.parent(),
                "the popup hangs under its opener: " + Transcript.of(tree));
        long back = targetOf(popupRoot, Accessible.Relation.POPUP_FOR, "the popup root");
        assertEquals(opener.id(), back, "POPUP_FOR names the opener");
        long forth = targetOf(opener, Accessible.Relation.CONTROLLER_FOR, "the opener");
        assertEquals(popupRoot.id(), forth, "CONTROLLER_FOR names the popup's root");
        assertTrue(tree.holds(back) && tree.holds(forth), "and both are this tree's own");
    }

    private static AccessibleNode popupRootOf(AccessibleTree tree) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (has(tree.node(i), Accessible.Relation.POPUP_FOR)) {
                return tree.node(i);
            }
        }
        throw new AssertionError("no popup root in " + Transcript.of(tree));
    }

    private static boolean isBelow(AccessibleTree tree, AccessibleNode node, AccessibleNode ancestor) {
        int target = tree.indexOf(ancestor.id());
        for (int at = node.parent(); at != AccessibleNode.NONE; at = tree.node(at).parent()) {
            if (at == target) {
                return true;
            }
        }
        return false;
    }
}
