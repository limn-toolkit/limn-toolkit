package limn.demo.a11y;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
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

import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision 18 (2026-09-14) in the presentation where it matters: the calendar is a window of its
 * own, the field keeps the focus, and the field is what a reader opens and closes it with —
 * {@code EXPAND} while closed, {@code COLLAPSE} while open, each published only in its moment.
 *
 * <p>Here rather than in the toolkit's tests for {@link NativePopupRelationTest}'s reason: the
 * toolkit's stub window cannot open a second window, and the in-scene overlay's input gate
 * refuses a verb on the field beneath it, so the closing half is only reachable natively.
 */
class DatePickerNativePopupTest {

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
    void theFieldOpensAndClosesItsCalendarWindowWithTheVerbsItPublishes() {
        DatePicker picker = new DatePicker();
        picker.setDate(LocalDate.of(2026, 9, 9));
        HeadlessWindow host = show(picker);

        AccessibleNode field = fieldNode(host.bridge().tree());
        assertTrue(field.has(Accessible.State.HAS_POPUP));
        assertFalse(field.has(Accessible.State.EXPANDED));
        assertTrue(offers(field, Accessible.Action.EXPAND), Transcript.of(host.bridge().tree()));
        assertFalse(offers(field, Accessible.Action.COLLAPSE));

        assertTrue(host.bridge().host.perform(field.id(), Accessible.Action.EXPAND,
                Accessible.Argument.NONE));
        runtime.drain();
        assertTrue(picker.isOpen(), "Expand on the field opened the calendar");
        assertEquals(DisplayMode.NATIVE_WINDOW, picker.displayMode());
        HeadlessWindow popup = popupWindow();
        settle(host, popup);
        assertTrue(picker.field().isFocused(), "and the field keeps the focus: the popup takes none");

        field = fieldNode(host.bridge().tree());
        assertTrue(field.has(Accessible.State.EXPANDED));
        assertTrue(offers(field, Accessible.Action.COLLAPSE), Transcript.of(host.bridge().tree()));
        assertFalse(offers(field, Accessible.Action.EXPAND));

        assertTrue(host.bridge().host.perform(field.id(), Accessible.Action.COLLAPSE,
                Accessible.Argument.NONE));
        runtime.drain();
        assertFalse(picker.isOpen(), "Collapse on the field closed it");
        settle(host, popup);
        field = fieldNode(host.bridge().tree());
        assertFalse(field.has(Accessible.State.EXPANDED));
        assertTrue(offers(field, Accessible.Action.EXPAND));
    }

    private HeadlessWindow show(limn.scene.Widget content) {
        Column root = new Column();
        root.add(content);
        HeadlessWindow window = backend.open("Limn date picker", 400, 300);
        Scene scene = new Scene(root);
        scene.bind(window);
        window.frame();
        window.desktopFocus(true);
        scene.requestFocus(((DatePicker) content).field());
        settle(window);
        return window;
    }

    /** The window a popup opened as: creating it is posted, so the queue is drained first. */
    private HeadlessWindow popupWindow() {
        runtime.drain();
        assertEquals(2, backend.windows().size(), "the host and the popup, and nothing else");
        return backend.windows().get(1);
    }

    private void settle(HeadlessWindow... windows) {
        for (int i = 0; i < 24; i++) {
            nanos += FRAME_NANOS;
            runtime.drain();
            for (HeadlessWindow window : windows) {
                window.frame();
            }
        }
    }

    /** The field: the one group with an expand facet, since the picker's own group is no node. */
    private static AccessibleNode fieldNode(AccessibleTree tree) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.GROUP && node.expand() != null) {
                return node;
            }
        }
        throw new AssertionError("no field with a popup in " + Transcript.of(tree));
    }

    private static boolean offers(AccessibleNode node, Accessible.Action action) {
        return node.actions() != null && node.actions().actions().contains(action);
    }
}
