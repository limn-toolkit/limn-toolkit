package limn.components;

import limn.accessibility.Accessible;
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
