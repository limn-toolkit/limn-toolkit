package limn.demo.a11y;

import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

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

    /**
     * Today, a week after the date every picker here holds: in the month the calendar opens on,
     * and not the day it holds, so the cursor found on that day is the selection's and not
     * today's.
     */
    private static final Clock SEPTEMBER_16 =
            Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC);

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
        DatePicker picker = new DatePicker().setClock(SEPTEMBER_16);
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
     * calendar is a window of its own, and the tree's effective focus is the calendar's cursor.
     * Since 2026-09-24 the calendar is grafted into the host's tree under the field
     * (Scene#graftPopup), so the cursor is simply the first {@code ACTIVE} node below the focused
     * field, as a native picker's is; until then it was read across the windows off the popup's
     * own tree. The field's caret segment gives up {@code ACTIVE} while the calendar is open
     * (2026-09-14), or a reader arrowing across the month would be told the field's day segment.
     */
    @Test
    void whileTheCalendarWindowHoldsTheKeyboardTheEffectiveFocusIsItsCursorNotTheFieldsCaret() {
        DatePicker picker = new DatePicker().setClock(SEPTEMBER_16);
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
        assertFalse(popup.publishesAccessibility(), "the calendar's window publishes nothing of its own");
        tree = host.bridge().tree();
        field = fieldNode(tree);
        long cursor = tree.firstActiveBelow(tree.indexOf(field.id()));
        assertTrue(cursor != 0 && cursor != caret,
                "open: the field's subtree holds the calendar's cursor, not the caret " + Transcript.of(tree));
        assertEquals(cursor, tree.effectiveFocus(), "and the effective focus is that cursor");
        AccessibleNode day = tree.find(cursor);
        assertEquals(Accessible.Role.CELL, day.role(),
                "the cursor is a day of the calendar, not a header control " + Transcript.of(tree));
        assertTrue(day.name().startsWith("September 9, 2026"),
                "the day the field holds: " + day.name());
        assertEquals(field.id(), tree.focused(), "while the focused node is still the field");

        picker.close();
        popup.close(); // the fade's end, as the desktop backend closes it
        settle(host);
        tree = host.bridge().tree();
        field = fieldNode(tree);
        assertEquals(caret, tree.firstActiveBelow(tree.indexOf(field.id())),
                "closed again, the caret segment is active once more " + Transcript.of(tree));
        assertEquals(caret, tree.effectiveFocus());
    }

    /**
     * GALLERY-NEW-2, 2026-09-15, in the presentation the recipes run in: Ctrl (or Cmd) and Up
     * climb out of the days and the month on show is the cursor at once, so the host tree's
     * effective focus lands on a month rather than on nothing. The other presentation's half is
     * the toolkit's
     * {@code DatePickerAccessibilityTest.aClimbToTheMonthsInTheSceneLandsTheEffectiveFocusOnTheMonthOnShow}.
     */
    @Test
    void aClimbToTheMonthsInAWindowOfItsOwnLandsTheEffectiveFocusOnTheMonthOnShow() {
        DatePicker picker = new DatePicker().setClock(SEPTEMBER_16);
        picker.setDate(LocalDate.of(2026, 9, 9));
        HeadlessWindow host = show(picker);
        picker.open();
        HeadlessWindow popup = popupWindow();
        settle(host, popup);

        drive(hostScene).keyEvent(limn.input.Keys.UP, true, false,
                limn.components.Accelerator.commandModifier());
        drive(hostScene).keyEvent(limn.input.Keys.UP, false, false,
                limn.components.Accelerator.commandModifier());
        drive(hostScene).inputBatchEnded();
        settle(host, popup);

        AccessibleTree tree = host.bridge().tree();
        long cursor = tree.effectiveFocus();
        AccessibleNode month = tree.find(cursor);
        assertEquals(Accessible.Role.CELL, month.role(), Transcript.of(tree));
        assertEquals("Sep, on show", month.name(),
                "the month the calendar was showing, not cell zero and not nothing "
                        + Transcript.of(tree));
    }

    /**
     * A click in the calendar's window makes it the system's key window, and the keys then went to
     * a scene nothing in which takes them. The picker gives the owner window the keyboard back, and
     * a click on the time row aims the keyboard there, as Tab does.
     */
    @Test
    void aClickOnTheTimeRowInTheCalendarsWindowTakesTheKeyboardThere() {
        DatePicker picker = new DatePicker().setClock(SEPTEMBER_16)
                .setGranularity(limn.components.date.DateField.Granularity.MINUTE);
        picker.setDateTime(java.time.LocalDateTime.of(2026, 9, 9, 18, 30));
        HeadlessWindow host = show(picker);
        picker.open();
        HeadlessWindow popup = popupWindow();
        settle(host, popup);
        // What the desktop does on a click into the popup's window.
        host.desktopFocus(false);
        popup.desktopFocus(true);
        int asked = host.focusRequests();

        limn.components.date.DateField row = null;
        java.util.ArrayDeque<limn.scene.Widget<?>> todo = new java.util.ArrayDeque<>();
        todo.add(picker.calendar().parent());
        while (!todo.isEmpty() && row == null) {
            limn.scene.Widget<?> at = todo.poll();
            if (at instanceof limn.components.date.DateField field && field != picker.field()) {
                row = field;
            }
            todo.addAll(at.children());
        }
        assertTrue(row != null, "a time row on the calendar's card");
        float x = row.localToSceneX() + 4;
        float y = row.localToSceneY() + row.height() / 2;
        Scene popupScene = row.scene();
        drive(popupScene).mouseMoved(x, y);
        drive(popupScene).mouseButton(limn.input.Keys.MOUSE_LEFT, true, 0, x, y);
        drive(popupScene).mouseButton(limn.input.Keys.MOUSE_LEFT, false, 0, x, y);
        drive(popupScene).inputBatchEnded();
        assertTrue(host.focusRequests() > asked, "the owner window asked for the keyboard back");
        host.desktopFocus(true);
        popup.desktopFocus(false);
        settle(host, popup);
        assertTrue(picker.isOpen(), "and the calendar stays open");

        for (char c : "0905".toCharArray()) {
            drive(hostScene).charTyped(c);
        }
        drive(hostScene).inputBatchEnded();
        // English keeps a 12-hour clock: 09 typed over 6:30 PM is 9 PM, as its own row shows it.
        assertEquals(java.time.LocalTime.of(21, 5), picker.time(), "the digits went into the time row");
        assertEquals(LocalDate.of(2026, 9, 9), picker.date(), "and the date is untouched");
    }

    private Scene hostScene;

    private HeadlessWindow show(limn.scene.Widget<?> content) {
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
