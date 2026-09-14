package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.components.date.DateField;
import limn.i18n.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a screen reader is told about a segmented date field; ADR 042 §8.
 *
 * <p>The shape under test is the one {@code Spinner} already publishes for its arrows: a group of
 * spin buttons. A single text field publishing {@code 31/12/2026} would leave a reader with no way
 * to say which part the caret is in, which is the whole reason this is not one.
 */
class DateFieldAccessibilityTest extends AccessibleComponentTestBase {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private DateField bindField(DateField field, Locale locale) {
        I18n.setLocale(locale);
        bind(field);
        return field;
    }

    @AfterEach
    void resetLocale() {
        I18n.setLocale(Locale.US);
    }

    private AccessibleNode groupNode() {
        return node(Accessible.Role.GROUP);
    }

    private List<AccessibleNode> segmentNodes() {
        return childrenOf(groupNode());
    }

    @Test
    void aFieldIsAGroupOfOneSpinButtonPerEditableSegment() {
        DateField field = bindField(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 12, 31));
        frame();
        List<AccessibleNode> segments = segmentNodes();
        assertEquals(3, segments.size(), "day, month, year");
        for (AccessibleNode segment : segments) {
            assertEquals(Accessible.Role.SPIN_BUTTON, segment.role());
            assertFalse(segment.name().isBlank(),
                    "a segment nobody can name is a segment nobody can use");
            assertNotNull(segment.value());
            assertTrue(segment.actions().actions().contains(Accessible.Action.INCREMENT));
            assertTrue(segment.actions().actions().contains(Accessible.Action.DECREMENT));
        }
    }

    @Test
    void theSegmentsAreNamedForWhatTheyHoldAndInTheOrderTheLanguageWritesThem() {
        DateField field = bindField(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 12, 31));
        frame();
        List<AccessibleNode> segments = segmentNodes();
        assertEquals("Dia", segments.get(0).name());
        assertEquals("Mês", segments.get(1).name());
        assertEquals("Ano", segments.get(2).name());

        DateField american = bindField(new DateField(), Locale.forLanguageTag("en-US"));
        american.setDate(LocalDate.of(2026, 12, 31));
        frame();
        assertEquals("Month", segmentNodes().get(0).name(), "the month comes first here");
        assertEquals("Day", segmentNodes().get(1).name());
    }

    @Test
    void eachSegmentCarriesItsOwnRangeAndNotTheFieldsOwn() {
        DateField field = bindField(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 2, 10));
        frame();
        List<AccessibleNode> segments = segmentNodes();
        assertEquals(1, segments.get(0).value().min());
        assertEquals(28, segments.get(0).value().max(),
                "February 2026 is 28 days long, and the day segment says so");
        assertEquals(1, segments.get(1).value().min());
        assertEquals(12, segments.get(1).value().max());
    }

    @Test
    void theGroupPublishesNoValueOfItsOwnAndTheSegmentsCarryThem() {
        DateField field = bindField(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 12, 31));
        frame();
        // A group has no number, and a min, a max and a step over a date would be three lies. The
        // whole date is DateField.text() for an application; a reader gets it a segment at a time,
        // each with its own real range and its own display form.
        assertNull(groupNode().value());
        assertEquals("31/12/2026", field.text());
        List<AccessibleNode> segments = segmentNodes();
        assertEquals("31", segments.get(0).value().text());
        assertEquals("12", segments.get(1).value().text());
        assertEquals("2026", segments.get(2).value().text());
    }

    @Test
    void anUnacceptableValueIsPublishedInvalidWithTheReason() {
        DateField field = bindField(new DateField(), PT_BR);
        field.setMaxDate(LocalDate.of(2026, 1, 1));
        field.setDate(LocalDate.of(2026, 12, 31));
        frame();
        AccessibleNode group = groupNode();
        assertTrue(group.has(Accessible.State.INVALID));
        assertFalse(group.description().isBlank(),
                "\"invalid\" with no reason is a reader being told there is a problem and not what");
    }

    @Test
    void aClockFieldPublishesItsHourMinuteAndDayPeriod() {
        DateField field = bindField(DateField.ofTime(), Locale.forLanguageTag("en-US"));
        field.setTime(LocalTime.of(14, 30));
        frame();
        List<String> names = segmentNodes().stream().map(AccessibleNode::name).toList();
        assertTrue(names.contains("Hour"), names.toString());
        assertTrue(names.contains("Minute"), names.toString());
        assertEquals(3, names.size(), "a 12-hour clock has a third segment: " + names);
    }

    @Test
    void aStepFromOutsideReachesThePathTheArrowKeysReach() throws InterruptedException {
        DateField field = bindField(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 12, 31));
        frame();
        AccessibleNode month = segmentNodes().get(1);
        perform(month.id(), Accessible.Action.DECREMENT, Accessible.Argument.NONE);
        assertEquals(LocalDate.of(2026, 11, 30), field.date(),
                "the month stepped back, and the day followed the shorter month");
    }

    /**
     * Decision 49 and semantics 4 (ADR 039 §1.10, amended 2026-09-14): the caret's segment is
     * the field's cursor, and a digit that completes a segment and rolls the caret onto the
     * next one moves it — one {@code ACTIVE_DESCENDANT_CHANGED} on the field, naming the
     * segment the caret left and the one it landed on. The widget's own quiet flag on that
     * roll-on gates only its ADR 040 observers; the tree's difference is what a reader follows.
     */
    @Test
    void aDigitThatRollsTheCaretOntoTheNextSegmentMovesTheFieldsCursor() {
        DateField field = bindField(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 12, 31));
        scene.requestFocus(field);
        frame();
        List<AccessibleNode> segments = segmentNodes();
        AccessibleNode day = segments.get(0);
        AccessibleNode month = segments.get(1);
        assertEquals(groupNode().id(), tree().focused(), describe(tree()));
        assertEquals(day.id(), tree().activeDescendant(),
                "the caret starts in the day segment: " + describe(tree()));
        bridge.events.clear();

        scene.charTyped('1');
        scene.charTyped('5');
        scene.inputBatchEnded();
        frame();

        assertEquals(LocalDate.of(2026, 12, 15), field.date(), "the day took the two digits");
        assertEquals(month.id(), tree().activeDescendant(),
                "and the caret rolled onto the month: " + describe(tree()));
        List<AccessibleEvent> moved = bridge.eventsOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED);
        assertEquals(1, moved.size(),
                "one cursor event on the field, which is the focused node: " + bridge.events);
        assertEquals(groupNode().id(), moved.get(0).nodeId());
        assertEquals(day.id(), moved.get(0).oldValue());
        assertEquals(month.id(), moved.get(0).newValue());
    }

    /**
     * Decisions 16 and 53 (DATES-NEW-8): a segment nobody has filled publishes an empty value
     * over its real range with the spoken word as its text, never its minimum as if typed (a
     * client reading the number heard "1" for a day nobody typed); the dashes stay drawn. Typing
     * the first digit is a value change even when the digit is the minimum. The first step from
     * empty lands on today's own value by the widget's clock, which is the rule kept.
     */
    @Test
    void aBlankSegmentSaysItIsEmptyAndFillingItIsAValueChange() {
        DateField field = bindField(new DateField(), PT_BR);
        field.setClock(java.time.Clock.fixed(java.time.Instant.parse("2026-03-15T12:00:00Z"),
                java.time.ZoneOffset.UTC));
        scene.requestFocus(field);
        frame();
        AccessibleNode day = segmentNodes().get(0);
        assertNotNull(day.value(), "the range stands");
        assertTrue(day.value().empty(), "but there is no number: " + day.value());
        assertEquals(1, day.value().min());
        assertEquals(31, day.value().max());
        assertEquals("vazio", day.value().text(), "the spoken word, not the dashes");
        assertTrue(field.text().startsWith("--"), "which stay drawn: " + field.text());
        bridge.events.clear();

        scene.charTyped('1');
        scene.inputBatchEnded();
        frame();
        day = segmentNodes().get(0);
        assertFalse(day.value().empty());
        assertEquals(1, day.value().value(), "the minimum, this time because it was typed");
        assertEquals("01", day.value().text(), "drawn at the pattern's own width");
        assertTrue(bridge.eventsOf(AccessibleEvent.Type.VALUE_CHANGED).stream()
                        .anyMatch(event -> event.nodeId() == segmentNodes().get(0).id()),
                "a value change on the day, though the number is the minimum: " + bridge.events);

        AccessibleNode month = segmentNodes().get(1);
        assertTrue(month.value().empty());
        scene.keyEvent(limn.input.Keys.RIGHT, true, false, 0);
        scene.keyEvent(limn.input.Keys.UP, true, false, 0);
        scene.inputBatchEnded();
        frame();
        assertEquals(3, segmentNodes().get(1).value().value(),
                "the first step from empty is today's month by the field's clock");
    }

    /**
     * Decision 38 (DATES-NEW-7): the era is no node of its own — it is drawn as a read-only piece
     * of the pattern — and reaches a reader in the year segment's spoken text, "令和8" for the "8"
     * that is drawn, with no string of this toolkit's. A calendar whose years are whole years
     * (ISO, Buddhist, Hijri) speaks the bare number as before.
     */
    @Test
    void anEraCalendarsYearSegmentSpeaksItsEra() {
        DateField field = bindField(new DateField(), Locale.forLanguageTag("ja-JP-u-ca-japanese"));
        field.setClock(java.time.Clock.fixed(java.time.Instant.parse("2026-09-09T12:00:00Z"),
                java.time.ZoneOffset.UTC));
        field.setDate(LocalDate.of(2026, 9, 9));
        frame();
        List<AccessibleNode> segments = segmentNodes();
        assertEquals(3, segments.size(), "year, month, day: the era is no segment " + describe(tree()));
        assertEquals("令和8", segments.get(0).value().text());
        assertEquals(8, segments.get(0).value().value());
        assertEquals("R8/9/9", field.text(), "drawn with the era's one letter");

        DateField minguo = bindField(new DateField(), Locale.forLanguageTag("zh-TW-u-ca-roc"));
        minguo.setDate(LocalDate.of(2026, 9, 9));
        frame();
        assertEquals("民國115", segmentNodes().get(0).value().text());

        DateField thai = bindField(new DateField(), Locale.forLanguageTag("th-TH-u-ca-buddhist"));
        thai.setDate(LocalDate.of(2026, 9, 9));
        frame();
        List<String> texts = segmentNodes().stream().map(node -> node.value().text()).toList();
        assertTrue(texts.contains("2569"), "a whole year speaks as itself: " + texts);
    }

    @Test
    void aSetValueFromOutsideClampsToTheSegmentsOwnRange() throws InterruptedException {
        DateField field = bindField(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 6, 10));
        frame();
        AccessibleNode day = segmentNodes().get(0);
        perform(day.id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(99));
        assertEquals(LocalDate.of(2026, 6, 30), field.date(),
                "June has thirty days and the segment will not hold more");
    }
}
