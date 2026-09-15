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

    /**
     * Decision 5 and semantics 4 in the same presentation: the field keeps the focus while the
     * calendar is a window of its own, and the tree's effective focus falls through to the
     * cursor in the popup's tree only when the focused node's own subtree holds no
     * {@code ACTIVE} node. Until 2026-09-14 the field's caret segment stayed {@code ACTIVE}
     * for as long as the field was focused, so the cross-window fallback could never fire for
     * a date picker: a reader arrowing across the month was told the field's day segment.
     */
    @Test
    void whileTheCalendarWindowHoldsTheKeyboardTheEffectiveFocusIsItsCursorNotTheFieldsCaret() {
        DatePicker picker = new DatePicker();
        picker.setDate(LocalDate.of(2026, 9, 9));
        HeadlessWindow host = show(picker);
        AccessibleTree tree = host.bridge().tree();
        AccessibleNode field = fieldNode(tree);
        long caret = tree.firstActiveBelow(tree.indexOf(field.id()));
        assertTrue(caret != 0, "closed and focused, the caret segment is the active descendant "
                + Transcript.of(tree));
        assertEquals(caret, tree.effectiveFocus());

        picker.open();
        HeadlessWindow popup = popupWindow();
        settle(host, popup);
        assertTrue(picker.field().isFocused(), "the field keeps the focus");
        tree = host.bridge().tree();
        field = fieldNode(tree);
        assertEquals(0, tree.firstActiveBelow(tree.indexOf(field.id())),
                "open: the field's subtree claims no ACTIVE node " + Transcript.of(tree));
        AccessibleTree popupTree = popup.bridge().tree();
        long cursor = popupTree.firstActiveBelow(0);
        assertTrue(cursor != 0, "the popup's tree holds the cursor " + Transcript.of(popupTree));
        assertEquals(cursor, tree.effectiveFocus(),
                "and the host tree's effective focus is that cursor, read across the windows");
        AccessibleNode day = popupTree.node(popupTree.indexOf(cursor));
        assertEquals(Accessible.Role.CELL, day.role(),
                "the cursor is a day of the calendar, not a header control " + Transcript.of(popupTree));
        assertTrue(day.name().startsWith("September 9, 2026"),
                "the day the field holds: " + day.name());
        assertEquals(field.id(), tree.focused(), "while the focused node is still the field");

        picker.close();
        settle(host, popup);
        tree = host.bridge().tree();
        field = fieldNode(tree);
        assertEquals(caret, tree.firstActiveBelow(tree.indexOf(field.id())),
                "closed again, the caret segment is active once more");
        assertEquals(caret, tree.effectiveFocus());
    }

    /**
     * GALLERY-NEW-2, 2026-09-15, in the presentation the recipes run in: Ctrl (or Cmd) and Up
     * climb out of the days and the month on show is the cursor at once, so the host tree's
     * effective focus — read across the two windows, because the field keeps the focus and the
     * calendar is a window of its own — lands on a month rather than on nothing. The other
     * presentation's half is the toolkit's
     * {@code DatePickerAccessibilityTest.aClimbToTheMonthsInTheSceneLandsTheEffectiveFocusOnTheMonthOnShow}.
     */
    @Test
    void aClimbToTheMonthsInAWindowOfItsOwnLandsTheEffectiveFocusOnTheMonthOnShow() {
        DatePicker picker = new DatePicker();
        picker.setDate(LocalDate.of(2026, 9, 9));
        HeadlessWindow host = show(picker);
        picker.open();
        HeadlessWindow popup = popupWindow();
        settle(host, popup);

        hostScene.keyEvent(limn.input.Keys.UP, true, false,
                limn.components.Accelerator.commandModifier());
        hostScene.keyEvent(limn.input.Keys.UP, false, false,
                limn.components.Accelerator.commandModifier());
        hostScene.inputBatchEnded();
        settle(host, popup);

        AccessibleTree popupTree = popup.bridge().tree();
        long cursor = popupTree.firstActiveBelow(0);
        assertTrue(cursor != 0, "a chooser cell is the cursor " + Transcript.of(popupTree));
        AccessibleNode month = popupTree.node(popupTree.indexOf(cursor));
        assertEquals(Accessible.Role.CELL, month.role(), Transcript.of(popupTree));
        assertEquals("Sep, on show", month.name(),
                "the month the calendar was showing, not cell zero and not nothing "
                        + Transcript.of(popupTree));
        assertEquals(cursor, host.bridge().tree().effectiveFocus(),
                "and the host tree answers it, across the windows " + Transcript.of(popupTree));
    }

    private Scene hostScene;

    private HeadlessWindow show(limn.scene.Widget content) {
        Column root = new Column();
        root.add(content);
        HeadlessWindow window = backend.open("Limn date picker", 400, 300);
        Scene scene = new Scene(root);
        hostScene = scene;
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
