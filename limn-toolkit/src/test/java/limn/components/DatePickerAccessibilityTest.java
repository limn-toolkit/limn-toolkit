package limn.components;

import limn.testing.StubWindow;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.components.date.DatePicker;
import limn.i18n.I18n;
import limn.scene.layout.Column;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * What a screen reader is told about a date picker as it stands in a form, closed; ADR 042 §8,
 * amended 2026-09-14 (decisions 18 and 55).
 *
 * <p>The node a reader arrives at is the field, so the field is what says a calendar can be
 * opened from here, whether it is, and takes the verbs that open and close it — the shape
 * {@code ComboBox} publishes and the ARIA combobox pattern names. A single picker's own group
 * is no node at all, so a caption bound to the picker names the field; a range picker keeps its
 * group under the caption and names its two ends for themselves. The button is a plain press.
 *
 * <p>In-scene on purpose: {@link StubWindow} cannot create the native popup window. Here the
 * open popup is an overlay of the scene, so the field publishes its expanded state and no verb
 * (the scene would refuse a verb on the field beneath the overlay; the overlay's {@code CANCEL}
 * closes). The native presentation's half — the field keeping the focus and taking
 * {@code COLLAPSE} while the calendar is a window of its own — is limn-demo's
 * {@code DatePickerNativePopupTest}, over a {@code HeadlessBackend} that can.
 */
class DatePickerAccessibilityTest extends AccessibleComponentTestBase {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private DatePicker picker;

    private void bindCaptioned(DatePicker built, String caption) {
        I18n.setLocale(PT_BR);
        picker = built;
        picker.setDate(LocalDate.of(2026, 9, 9));
        picker.setDisplayMode(DisplayMode.IN_SCENE);
        Column root = new Column();
        Label label = new Label(caption);
        root.add(label);
        root.add(picker);
        label.setLabelFor(picker);
        bind(root);
    }

    /** Every record the walk logged while a test ran; see {@link #thePickerIsNeverNamedInTheLog}. */
    private final List<LogRecord> logged = new ArrayList<>();

    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private Logger walkLogger;

    @BeforeEach
    void captureTheWalksLog() {
        walkLogger = Logger.getLogger("limn.scene.AccessibleWalk");
        walkLogger.addHandler(capture);
    }

    @AfterEach
    void resetLocale() {
        I18n.setLocale(Locale.US);
    }

    /**
     * A single picker is no node (decision 55) and paints its box, so the walk's
     * paints-and-says-nothing guard named {@code DatePicker} in an application's log and advised
     * a name, which would undo the decision. Checked after every case, as {@code TabbedPane}'s
     * test does, because the walk names a class once per virtual machine: whichever case here
     * binds a single picker first is the one that catches a lost {@code paintsDecoration}.
     */
    @AfterEach
    void thePickerIsNeverNamedInTheLog() {
        walkLogger.removeHandler(capture);
        List<String> aboutThePicker = new ArrayList<>();
        for (LogRecord record : logged) {
            if (record.getParameters() != null && record.getParameters().length > 0
                    && DatePicker.class.getName().equals(record.getParameters()[0])) {
                aboutThePicker.add(record.getMessage());
            }
        }
        assertTrue(aboutThePicker.isEmpty(),
                "the toolkit's picker is named in an application's log: " + aboutThePicker);
    }

    private List<AccessibleNode> nodesOf(Accessible.Role role) {
        List<AccessibleNode> found = new ArrayList<>();
        for (int i = 0; i < tree().nodeCount(); i++) {
            if (tree().node(i).role() == role) {
                found.add(tree().node(i));
            }
        }
        return found;
    }

    private static boolean offers(AccessibleNode node, Accessible.Action action) {
        return node.actions() != null && node.actions().actions().contains(action);
    }

    @Test
    void theCaptionNamesTheFieldAndTheFieldSaysItHasAPopup() throws InterruptedException {
        bindCaptioned(new DatePicker(), "Data de entrega");
        List<AccessibleNode> groups = nodesOf(Accessible.Role.GROUP);
        assertEquals(1, groups.size(),
                "one group: the field's; the picker's own is no node " + describe(tree()));
        AccessibleNode field = groups.get(0);
        assertEquals("Data de entrega", field.name(),
                "the caption bound to the picker names the field a reader arrives at");
        assertEquals(Accessible.NameFrom.LABEL, field.nameFrom());
        assertTrue(field.has(Accessible.State.FOCUSABLE));
        assertTrue(field.has(Accessible.State.HAS_POPUP), describe(tree()));
        assertFalse(field.has(Accessible.State.EXPANDED));
        assertTrue(offers(field, Accessible.Action.EXPAND), "closed: Expand is the verb");
        assertFalse(offers(field, Accessible.Action.COLLAPSE));
        assertEquals(3, childrenOf(field).size(), "and it is still the group of three segments");

        AccessibleNode button = node(Accessible.Role.BUTTON);
        assertEquals("Abrir calendário", button.name());
        assertNull(button.expand(), "a plain press: the popup's state is the field's to tell");
        assertTrue(offers(button, Accessible.Action.PRESS));
        assertFalse(offers(button, Accessible.Action.EXPAND));
        // Brief item 14: that the button refuses the verb it no longer publishes was a claim by
        // reading. The host accepts any verb on a published node and leaves the list to the
        // bridges (semantics 5), so what is asserted is the effect: nothing opens.
        perform(button.id(), Accessible.Action.EXPAND, Accessible.Argument.NONE);
        assertFalse(picker.isOpen(), "Expand on the button does nothing: its verb is Press");

        assertTrue(perform(field.id(), Accessible.Action.EXPAND, Accessible.Argument.NONE));
        assertTrue(picker.isOpen(), "Expand on the field opens the calendar");
        frame();
        field = nodesOf(Accessible.Role.GROUP).stream()
                .filter(group -> group.name().equals("Data de entrega")).findFirst().orElseThrow();
        assertTrue(field.has(Accessible.State.EXPANDED));
        assertTrue(field.has(Accessible.State.HAS_POPUP));
        // In this presentation the popup is an overlay of the scene, and the scene refuses every
        // verb on a widget beneath it: a COLLAPSE published on the field would be answered
        // "accepted" from the snapshot and close nothing (semantics 5). So the field publishes
        // its state and no verb, the overlay's CANCEL is the closing verb, and the same is
        // true of what is performed: Collapse on the field is refused, Cancel on the overlay
        // closes. In a window of its own the field takes COLLAPSE -- DatePickerNativePopupTest.
        assertFalse(offers(field, Accessible.Action.COLLAPSE),
                "open in the scene: no verb on the field " + describe(tree()));
        assertFalse(offers(field, Accessible.Action.EXPAND));
        // The host accepts any verb on a node it published and leaves the published list to
        // the bridges (semantics 5), so what is asserted is what happens: nothing.
        perform(field.id(), Accessible.Action.COLLAPSE, Accessible.Argument.NONE);
        assertTrue(picker.isOpen(), "Collapse on the field beneath the overlay does nothing, "
                + "which is why the field does not publish it");
        AccessibleNode overlay = nodesOf(Accessible.Role.GROUP).stream()
                .filter(group -> group.name().equals("Calendário") && offers(group, Accessible.Action.CANCEL))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no overlay group with CANCEL in " + describe(tree())));
        assertEquals(overlay.id(), tree().focused(), "the overlay holds the focus (decision 1)");
        long cursor = tree().firstActiveBelow(tree().indexOf(overlay.id()));
        assertTrue(cursor != 0, "and the calendar's cursor is ACTIVE under it " + describe(tree()));
        assertEquals(cursor, tree().effectiveFocus(), "which is the tree's effective focus");
        assertEquals(Accessible.Role.CELL, tree().node(tree().indexOf(cursor)).role());
        assertTrue(perform(overlay.id(), Accessible.Action.CANCEL, Accessible.Argument.NONE));
        assertFalse(picker.isOpen(), "Cancel on the overlay closes the calendar");
        frame();
        field = nodesOf(Accessible.Role.GROUP).get(0);
        assertFalse(field.has(Accessible.State.EXPANDED));
        assertTrue(offers(field, Accessible.Action.EXPAND));
    }

    @Test
    void aRangePickerKeepsTheCaptionOnItsGroupAndNamesItsTwoEnds() {
        bindCaptioned(DatePicker.ofRange(), "Estadia");
        List<AccessibleNode> groups = nodesOf(Accessible.Role.GROUP);
        assertEquals(3, groups.size(), "the picker's group and its two fields " + describe(tree()));
        AccessibleNode group = groups.get(0);
        assertEquals("Estadia", group.name(), "the caption stays on the group");
        assertFalse(group.has(Accessible.State.FOCUSABLE));
        assertNull(group.expand(), "which carries no expand state of its own");
        assertNull(group.actions());
        List<AccessibleNode> ends = childrenOf(group).stream()
                .filter(child -> child.role() == Accessible.Role.GROUP).toList();
        assertEquals("Data inicial", ends.get(0).name());
        assertEquals("Data final", ends.get(1).name());
        for (AccessibleNode end : ends) {
            assertTrue(end.has(Accessible.State.HAS_POPUP), end.name());
            assertTrue(offers(end, Accessible.Action.EXPAND), end.name());
        }
    }

    /**
     * Decision 11's positive half inside an open picker: {@code FOCUS} on a day of the calendar
     * moves its cursor and commits nothing -- the popup stays open, the field keeps its date and
     * the cursor is the tree's effective focus under the overlay. {@code SELECT} is the pick.
     */
    @Test
    void focusOnADayOfTheOpenCalendarMovesItsCursorAndCommitsNothing() throws InterruptedException {
        bindCaptioned(new DatePicker(), "Data de entrega");
        List<LocalDate> picked = new ArrayList<>();
        picker.onSelect(() -> picked.add(picker.date()));
        picker.open();
        frame();
        AccessibleNode twelfth = nodesOf(Accessible.Role.CELL).stream()
                .filter(cell -> cell.name().startsWith("12 de setembro")).findFirst()
                .orElseThrow(() -> new AssertionError(describe(tree())));
        assertTrue(offers(twelfth, Accessible.Action.FOCUS), describe(tree()));
        assertTrue(perform(twelfth.id(), Accessible.Action.FOCUS, Accessible.Argument.NONE));
        assertTrue(picker.isOpen(), "FOCUS commits nothing, so the calendar stays open");
        assertEquals(LocalDate.of(2026, 9, 9), picker.date(), "and the field keeps its date");
        assertTrue(picked.isEmpty());
        frame();
        long cursor = tree().effectiveFocus();
        AccessibleNode at = tree().node(tree().indexOf(cursor));
        assertTrue(at.name().startsWith("12 de setembro"),
                "the cursor is the 12th, and it is the effective focus: " + describe(tree()));
    }

    /**
     * Semantics 5 beneath the overlay (2026-09-15): while the calendar is an overlay of the
     * scene, the scene refuses every verb on the field under it, so the walk publishes none there
     * (ADR 039 §1.13, amended that day): the field drops {@code COLLAPSE}, the button its
     * {@code PRESS}, and the segments theirs -- no step, no {@code FOCUS}, a read-only value --
     * and all of them get them back when the calendar closes. The rule is the walk's since that
     * amendment and no longer the field's own, so this case is what holds it for the picker.
     */
    @Test
    void theFieldsSegmentsCarryNoVerbBeneathTheCalendarOverlay() throws InterruptedException {
        bindCaptioned(new DatePicker(), "Data de entrega");
        AccessibleNode field = nodesOf(Accessible.Role.GROUP).get(0);
        AccessibleNode month = childrenOf(field).get(1);
        assertTrue(offers(month, Accessible.Action.FOCUS), "closed, the segment is operable "
                + describe(tree()));
        picker.open();
        frame();
        field = nodesOf(Accessible.Role.GROUP).stream()
                .filter(group -> group.name().equals("Data de entrega")).findFirst().orElseThrow();
        assertNull(field.actions(), "the field beneath the overlay carries no verb: "
                + describe(tree()));
        assertNull(node("Abrir calendário").actions(),
                "nor the button beside it: " + describe(tree()));
        List<AccessibleNode> segments = childrenOf(field);
        assertEquals(3, segments.size(), describe(tree()));
        for (AccessibleNode segment : segments) {
            for (Accessible.Action verb : List.of(Accessible.Action.INCREMENT,
                    Accessible.Action.DECREMENT, Accessible.Action.FOCUS)) {
                assertFalse(offers(segment, verb),
                        "beneath the overlay a segment carries no " + verb + ": " + describe(tree()));
            }
            assertFalse(segment.accepts(Accessible.Action.SET_VALUE),
                    "nor SET_VALUE: " + describe(tree()));
            assertFalse(segment.value().readOnly(),
                    "on a value still writable: " + describe(tree()));
        }
        int caret = picker.field().focusedSegment();
        perform(segments.get(1).id(), Accessible.Action.FOCUS, Accessible.Argument.NONE);
        assertEquals(caret, picker.field().focusedSegment(), "which the scene would have dropped");

        picker.close();
        frame();
        field = nodesOf(Accessible.Role.GROUP).get(0);
        assertTrue(offers(childrenOf(field).get(1), Accessible.Action.FOCUS),
                "closed again, the verbs are back " + describe(tree()));
        assertTrue(offers(field, Accessible.Action.EXPAND), describe(tree()));
        assertTrue(offers(node("Abrir calendário"), Accessible.Action.PRESS),
                describe(tree()));
        assertTrue(childrenOf(field).get(1).accepts(Accessible.Action.SET_VALUE));
    }

    /**
     * Through the fade-out after a close, the calendar's layer is still drawn, still the layer
     * that owns input and still published, and it offers no {@code CANCEL} (semantics 5; the 2d
     * review, 2026-09-15): the hook's {@code setOpen(false)} returns at once for a picker already
     * closed, so a {@code CANCEL} there was answered from the snapshot for nothing. The clock is
     * held still after the close, so the fade stays at its first frame. What the calendar inside
     * publishes is not held here: it still performs its verbs through the fade.
     */
    @Test
    void throughTheFadeOutTheCalendarsLayerOffersNoCancel() throws InterruptedException {
        bindCaptioned(new DatePicker(), "Data de entrega");
        picker.open();
        frame();
        settleAnimations(null); // faded in, so the fade out has somewhere to start from
        frame();
        List<AccessibleNode> layer = nodesWith(Accessible.State.MODAL);
        assertEquals(1, layer.size(), describe(tree()));
        assertTrue(offers(layer.get(0), Accessible.Action.CANCEL), "open: the layer dismisses "
                + describe(tree()));

        assertTrue(perform(layer.get(0).id(), Accessible.Action.CANCEL, Accessible.Argument.NONE));
        frame();
        assertFalse(picker.isOpen());
        layer = nodesWith(Accessible.State.MODAL);
        assertEquals(1, layer.size(), "the fading layer is still drawn and still owns input"
                + describe(tree()));
        assertFalse(offers(layer.get(0), Accessible.Action.CANCEL),
                "and offers no CANCEL, which setOpen's guard would drop" + describe(tree()));
        perform(layer.get(0).id(), Accessible.Action.CANCEL, Accessible.Argument.NONE);
        frame();
        assertFalse(picker.isOpen(), "a CANCEL sent anyway reopens nothing");
        assertEquals(LocalDate.of(2026, 9, 9), picker.date(), "and moves nothing");
    }

    /**
     * GALLERY-NEW-2, 2026-09-15, in the presentation this class can drive: with the calendar an
     * overlay of the scene, Ctrl (or Cmd) and Up climb to the months and the month on show is the
     * cursor at once — so the tree's effective focus, which the reader follows across the field
     * and the overlay, lands on a month rather than on nothing. The native presentation's half is
     * limn-demo's {@code DatePickerNativePopupTest}, over a backend that can open the window.
     */
    @Test
    void aClimbToTheMonthsInTheSceneLandsTheEffectiveFocusOnTheMonthOnShow()
            throws InterruptedException {
        bindCaptioned(new DatePicker(), "Data de entrega");
        picker.open();
        frame();
        // The reader puts the cursor in the grid first, as its recipe does: in this presentation
        // the overlay's own group holds the focus until something inside asks for it.
        AccessibleNode ninth = nodesOf(Accessible.Role.CELL).stream()
                .filter(cell -> cell.name().startsWith("9 de setembro")).findFirst()
                .orElseThrow(() -> new AssertionError(describe(tree())));
        assertTrue(perform(ninth.id(), Accessible.Action.FOCUS, Accessible.Argument.NONE));
        frame();
        assertEquals(ninth.id(), tree().effectiveFocus(), describe(tree()));

        drive(scene).keyEvent(limn.input.Keys.UP, true, false, Accelerator.commandModifier());
        drive(scene).keyEvent(limn.input.Keys.UP, false, false, Accelerator.commandModifier());
        drive(scene).inputBatchEnded();
        frame();

        List<AccessibleNode> active = nodesWith(Accessible.State.ACTIVE).stream()
                .filter(node -> node.role() == Accessible.Role.CELL).toList();
        assertEquals(1, active.size(), "one chooser cell is the cursor: " + describe(tree()));
        assertTrue(active.get(0).name().toLowerCase(PT_BR).startsWith("set"),
                "September, the month the calendar was showing: " + active.get(0).name()
                        + describe(tree()));
        assertEquals(active.get(0).id(), tree().effectiveFocus(),
                "and it is what a reader is told: the cursor is the effective focus"
                        + describe(tree()));
    }
    /**
     * Decision 102 (2026-09-22), read on the macOS guest in phase 8 of ADR 045: VoiceOver writes
     * {@code AXSelected} on every cell its cursor reaches, the bridge posts {@code SELECT}, and a
     * popup that committed on it closed under the user's arrow keys. A client's write now marks
     * the day and commits nothing; Enter on the marked day still commits, which the calendar
     * allows by no longer swallowing a user's pick of the day already selected.
     */
    @Test
    void aClientsSelectMarksADayWithoutClosingAndEnterThenCommitsIt() throws InterruptedException {
        bindCaptioned(new DatePicker(), "Data de entrega");
        picker.open();
        frame();
        AccessibleNode tenth = nodesOf(Accessible.Role.CELL).stream()
                .filter(cell -> cell.name().startsWith("10 de setembro")).findFirst()
                .orElseThrow(() -> new AssertionError(describe(tree())));
        assertTrue(perform(tenth.id(), Accessible.Action.FOCUS, Accessible.Argument.NONE));
        frame();
        assertTrue(perform(tenth.id(), Accessible.Action.SELECT, Accessible.Argument.NONE));
        frame();
        assertTrue(picker.isOpen(), "the popup stays open under a client's write"
                + describe(tree()));
        assertTrue(tree().find(tenth.id()).has(Accessible.State.SELECTED),
                "and the day is marked" + describe(tree()));
        assertEquals(LocalDate.of(2026, 9, 9), picker.date(), "and nothing is committed");

        drive(scene).keyEvent(limn.input.Keys.ENTER, true, false, 0);
        drive(scene).keyEvent(limn.input.Keys.ENTER, false, false, 0);
        drive(scene).inputBatchEnded();
        frame();
        assertFalse(picker.isOpen(), "Enter on the marked day commits and closes"
                + describe(tree()));
        assertEquals(LocalDate.of(2026, 9, 10), picker.date(), "with the marked day");
    }
}
