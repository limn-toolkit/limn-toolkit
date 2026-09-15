package limn.components;

import limn.components.date.DateField;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.chrono.Chronology;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The segmented editor: what the locale orders, what typing does, and what an incomplete field is. */
class DateFieldTest extends ComponentTestBase {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final Locale EN_US = Locale.forLanguageTag("en-US");

    private DateField field;
    private Scene scene;

    private void build(DateField built, Locale locale) {
        I18n.setLocale(locale);
        field = built;
        scene = new Scene(field);
        scene.setTextRuler(RULER);
        scene.layoutPass(300, 32);
        scene.requestFocus(field);
    }

    @AfterEach
    void resetLocale() {
        I18n.setLocale(Locale.US);
    }

    private void key(int keyCode) {
        scene.keyEvent(keyCode, true, false, 0);
        scene.keyEvent(keyCode, false, false, 0);
        scene.inputBatchEnded();
    }

    private void type(String digits) {
        for (int i = 0; i < digits.length(); i++) {
            scene.charTyped(digits.charAt(i));
        }
        scene.inputBatchEnded();
    }

    @Test
    void theSegmentsAreInTheOrderTheLanguageWritesThem() {
        build(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 12, 31));
        assertEquals("31/12/2026", field.text());

        build(new DateField(), EN_US);
        field.setDate(LocalDate.of(2026, 12, 31));
        assertEquals("12/31/2026", field.text());
    }

    @Test
    void aTwoDigitYearInThePatternIsWidenedToFour() {
        // en-US's short pattern is M/d/yy. The order and the separators are the locale's; a
        // two-digit year in a field somebody types into is an ambiguity the toolkit would be
        // creating on purpose (ADR 042 3).
        build(new DateField(), EN_US);
        field.setDate(LocalDate.of(2026, 1, 2));
        assertTrue(field.text().endsWith("2026"), "the year is written in full: " + field.text());
    }

    @Test
    void anIncompleteFieldHasNoValueAndSaysSo() {
        build(new DateField(), PT_BR);
        type("31");             // the day
        assertNull(field.date(), "one segment is not a date");
        assertFalse(field.isValid());
        assertNotNull(field.validationMessage());
        type("12");             // the month
        assertNull(field.date());
        type("2026");           // and the year
        assertEquals(LocalDate.of(2026, 12, 31), field.date());
        assertTrue(field.isValid());
        assertNull(field.validationMessage());
    }

    @Test
    void aRunOfDigitsFillsTheWholeDateWithoutASeparatorBeingTyped() {
        build(new DateField(), PT_BR);
        type("31122026");
        assertEquals(LocalDate.of(2026, 12, 31), field.date());

        build(new DateField(), EN_US);
        type("12312026");
        assertEquals(LocalDate.of(2026, 12, 31), field.date(),
                "the same run means month, day, year here");
    }

    @Test
    void aSeparatorTypedByHandMovesOnRatherThanBeingRefused() {
        build(new DateField(), PT_BR);
        type("3/12/2026");
        assertEquals(LocalDate.of(2026, 12, 3), field.date());
    }

    @Test
    void anEmptyFieldIsValidBecauseWhetherADateIsRequiredIsTheFormsBusiness() {
        build(new DateField(), PT_BR);
        assertTrue(field.isEmpty());
        assertTrue(field.isValid());
        assertNull(field.validationMessage());
    }

    /**
     * An empty segment's first step lands on today's value, and today is the field's clock: a
     * field in a capture or a test lands on the same day whatever day it is run.
     */
    @Test
    void anEmptySegmentsFirstStepLandsOnTheClocksToday() {
        build(new DateField(), PT_BR);
        field.setClock(Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneOffset.UTC));
        key(Keys.UP);    // the day, which leads in Portuguese
        key(Keys.RIGHT);
        key(Keys.UP);    // the month
        key(Keys.RIGHT);
        key(Keys.UP);    // the year
        assertEquals(LocalDate.of(2026, 3, 15), field.date());
    }

    @Test
    void theArrowsStepTheFocusedSegmentAndRollOverIt() {
        build(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 12, 31));
        key(Keys.UP);
        assertEquals(LocalDate.of(2026, 12, 1), field.date(),
                "the day rolls from the end of the month to its start");
        key(Keys.DOWN);
        assertEquals(LocalDate.of(2026, 12, 31), field.date());
    }

    @Test
    void theCaretMovesBetweenSegmentsAndDeleteEmptiesTheOneItIsIn() {
        build(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 12, 31));
        assertEquals(0, field.focusedSegment());
        key(Keys.RIGHT);
        assertEquals(1, field.focusedSegment());
        key(Keys.DELETE);
        assertNull(field.date(), "a date missing its month is not a date");
        assertTrue(field.text().contains("--"));
        key(Keys.END);
        assertEquals(2, field.focusedSegment(), "End is the last segment");
        key(Keys.HOME);
        assertEquals(0, field.focusedSegment());
    }

    @Test
    void steppingTheMonthCarriesADayThatOvershotIt() {
        build(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 1, 31));
        key(Keys.RIGHT);  // onto the month
        key(Keys.UP);     // into February
        assertEquals(LocalDate.of(2026, 2, 28), field.date(),
                "31 January stepped into February is the 28th, not nothing");
    }

    @Test
    void aTimeFieldKeepsTheClockTheLanguageKeeps() {
        build(DateField.ofTime(), PT_BR);
        field.setTime(LocalTime.of(14, 30));
        assertEquals("14:30", field.text(), "Portuguese counts to 24");
        assertNull(field.date(), "a time field has no date to answer");

        build(DateField.ofTime(), EN_US);
        field.setTime(LocalTime.of(14, 30));
        assertTrue(field.text().startsWith("2:30"), "English counts to 12: " + field.text());
        assertTrue(field.text().toUpperCase(Locale.ROOT).contains("PM"));
    }

    @Test
    void theDayPeriodIsTypedWithTheTwoLettersPeopleActuallyType() {
        build(DateField.ofTime(), EN_US);
        field.setTime(LocalTime.of(9, 0));
        key(Keys.END); // the day period is the last segment of an English clock
        type("p");
        assertEquals(LocalTime.of(21, 0), field.time());
        type("a");
        assertEquals(LocalTime.of(9, 0), field.time());
    }

    @Test
    void secondsAreShownOnlyAtTheSecondLevelAndAMinuteFieldHoldsNone() {
        build(DateField.ofTime().setGranularity(DateField.Granularity.SECOND), PT_BR);
        field.setTime(LocalTime.of(14, 30, 45));
        assertEquals("14:30:45", field.text());

        build(DateField.ofTime(), PT_BR);
        field.setTime(LocalTime.of(14, 30, 45));
        assertEquals("14:30", field.text());
        assertEquals(LocalTime.of(14, 30), field.time(),
                "the value is what the segments say, never a second the segments cannot show");
    }

    @Test
    void aDateAndTimeFieldAnswersBothHalvesAndNeverInventsOne() {
        build(new DateField().setGranularity(DateField.Granularity.MINUTE), PT_BR);
        assertNull(field.dateTime());
        field.setDateTime(LocalDateTime.of(2026, 12, 31, 18, 5));
        assertEquals(LocalDate.of(2026, 12, 31), field.date());
        assertEquals(LocalTime.of(18, 5), field.time());
        assertEquals(LocalDateTime.of(2026, 12, 31, 18, 5), field.dateTime());

        build(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 12, 31));
        assertNull(field.dateTime(), "a date-only field answers null here rather than midnight");
    }

    // ------------------------------------------------------------ granularity (decision 12, 51)

    @Test
    void aMonthFieldShowsAMonthAndAYearAndAnswersTheFirstOfTheMonth() {
        build(new DateField().setGranularity(DateField.Granularity.MONTH), PT_BR);
        field.setDate(LocalDate.of(2026, 6, 15));
        assertEquals("06/2026", field.text(), "the day and its slash are cut from the pattern");
        assertEquals(LocalDate.of(2026, 6, 1), field.date(),
                "a month field told 15 June holds June and answers the 1st");
        type("072027");
        assertEquals(LocalDate.of(2027, 7, 1), field.date(), "two segments, typed as one run");

        build(new DateField().setGranularity(DateField.Granularity.MONTH), EN_US);
        field.setDate(LocalDate.of(2026, 6, 15));
        assertEquals("6/2026", field.text(), "M/d/yy loses its middle day and the slash before it");

        build(new DateField().setGranularity(DateField.Granularity.MONTH),
                Locale.forLanguageTag("ko-KR"));
        field.setDate(LocalDate.of(2026, 6, 15));
        assertEquals("2026. 6.", field.text(),
                "Korean keeps the full stop that closes the date and loses the one before the day");
    }

    @Test
    void aYearFieldIsTheYearAloneAndAnswersItsFirstDay() {
        build(new DateField().setGranularity(DateField.Granularity.YEAR), PT_BR);
        field.setDate(LocalDate.of(2026, 6, 15));
        assertEquals("2026", field.text());
        assertEquals(LocalDate.of(2026, 1, 1), field.date());
    }

    @Test
    void anHourFieldDropsTheMinuteAndItsColon() {
        build(DateField.ofTime().setGranularity(DateField.Granularity.HOUR), PT_BR);
        field.setTime(LocalTime.of(14, 30));
        assertEquals("14", field.text());
        assertEquals(LocalTime.of(14, 0), field.time(), "the hour's first minute");

        build(new DateField().setGranularity(DateField.Granularity.HOUR), PT_BR);
        field.setDateTime(LocalDateTime.of(2026, 9, 9, 14, 30));
        assertEquals("09/09/2026 14", field.text(), "a date field down to the hour");
    }

    @Test
    void aTimeFieldRefusesADateLevelRatherThanShowingNothing() {
        build(DateField.ofTime(), PT_BR);
        assertThrows(IllegalArgumentException.class,
                () -> field.setGranularity(DateField.Granularity.DAY));
        assertEquals(DateField.Granularity.MINUTE, field.granularity(), "and stays where it was");
    }

    @Test
    void aFieldMadeCoarserDropsTheSegmentsItLostAndAnnouncesTheValueThatMoved() {
        build(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 6, 15));
        List<Change.Aspect> heard = new ArrayList<>();
        field.observeChanges((widget, change) -> heard.add(change.aspect()));
        field.setGranularity(DateField.Granularity.MONTH);
        assertEquals(LocalDate.of(2026, 6, 1), field.date());
        assertTrue(heard.contains(Change.Aspect.VALUE), "the 15th became the 1st: " + heard);
        heard.clear();
        field.setGranularity(DateField.Granularity.DAY);
        assertNull(field.date(), "the day segment is back and empty, so there is no date yet");
        assertTrue(field.text().contains("--"), field.text());
    }

    // ----------------------------------------------- typed and pasted input (DATES-NEW-9, decision 57)

    private final TextFieldTest.MockClipboard clipboard = new TextFieldTest.MockClipboard();

    private void paste(String text) {
        scene.setClipboard(clipboard);
        clipboard.set(text);
        scene.keyEvent(Keys.V, true, false, Keys.MOD_CONTROL);
        scene.keyEvent(Keys.V, false, false, Keys.MOD_CONTROL);
        scene.inputBatchEnded();
    }

    @Test
    void aPastedTwoDigitYearIsTheSameCenturyEveryWayItIsWritten() {
        build(new DateField(), PT_BR);
        field.setClock(IN_2026);
        paste("31/12/26");
        assertEquals(LocalDate.of(2026, 12, 31), field.date(),
                "the language's own form: 'dd/MM/y' parsed 26 as the year 26 before");
        assertTrue(field.isValid());
        paste("31122026");
        assertEquals(LocalDate.of(2026, 12, 31), field.date(), "the digit run, four-digit year");
        paste("311226");
        assertEquals(LocalDate.of(2026, 12, 31), field.date(), "the digit run, two-digit year");
        paste("31/12/85");
        assertEquals(LocalDate.of(1985, 12, 31), field.date(), "eighty years back is the window's start");
        paste("31/12/45");
        assertEquals(LocalDate.of(2045, 12, 31), field.date(), "and nineteen ahead its end");
        paste("31/12/0026");
        assertEquals(LocalDate.of(26, 12, 31), field.date(),
                "four digits are what was meant, however small; the bounds are the application's");
    }

    @Test
    void theTwoDigitYearWindowIsAdjustableAndCanBeTurnedOff() {
        build(new DateField(), PT_BR);
        field.setClock(IN_2026);
        field.setTwoDigitYearWindow(20);
        paste("31/12/85");
        assertEquals(LocalDate.of(2085, 12, 31), field.date(), "2006 to 2105 now");
        assertThrows(IllegalArgumentException.class, () -> field.setTwoDigitYearWindow(100));

        field.setTwoDigitYearWindow(DateField.REFUSE_TWO_DIGIT_YEARS);
        paste("31/12/26");
        assertNull(field.date(), "no guess: the year is left blank");
        assertEquals("31/12/----", field.text());
        assertFalse(field.isValid(), "and the field says it is incomplete");
        paste("311226");
        assertEquals("31/12/----", field.text(), "the digit run the same way");
        paste("31/12/2026");
        assertEquals(LocalDate.of(2026, 12, 31), field.date(), "four digits need no guess");
    }

    @Test
    void aPastedRunKeepsItsLeadingZero() {
        build(new DateField(), PT_BR);
        paste("01022026");
        assertEquals(LocalDate.of(2026, 2, 1), field.date(),
                "Integer.toString dropped the zero and a seven-digit run matched nothing");
        paste("0102");
        assertEquals(LocalDate.of(2026, 2, 1), field.date(), "too short to be a date: untouched");
    }

    @Test
    void anOverlongPasteChangesNothingAndThrowsNothing() {
        build(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 9, 9));
        List<Throwable> crashed = new ArrayList<>();
        limn.backend.CrashHandler handler = (phase, error) -> {
            crashed.add(error);
            return true;
        };
        limn.backend.Crashes.install(handler);
        try {
            paste("123456789012");
            paste("1234567890123456789012345");
            paste("12345/12345/2026");
        } finally {
            limn.backend.Crashes.uninstall(handler);
        }
        assertEquals(LocalDate.of(2026, 9, 9), field.date(), "not a date: the value stands");
        assertEquals(List.of(), crashed,
                "and nothing threw out of the key handler (Integer.parseInt over the whole run did)");
    }

    @Test
    void aPastedImpossibleMonthOrDayIsRefusedWhole() {
        build(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 9, 9));
        paste("31/13/2026");
        assertEquals(LocalDate.of(2026, 9, 9), field.date(), "a thirteenth month is no date");
        assertEquals("09/09/2026", field.text(), "and no segment was written above its range");
        paste("32/01/2026");
        assertEquals("09/09/2026", field.text());
        paste("31/02/2026");
        assertEquals(LocalDate.of(2026, 2, 28), field.date(),
                "a day past a short month is the last day of it, as typing 31 into February is");
    }

    @Test
    void aTypedIsoRunCommitsTheSameDayAsTheLanguagesForm() {
        build(new DateField(), PT_BR);
        type("2026-12-31");
        assertEquals(LocalDate.of(2026, 12, 31), field.date(),
                "typed segment by segment into a day-first field this was 0001-02-20");
        assertTrue(field.isValid());
        assertEquals(2, field.focusedSegment(), "the caret ends on the last segment");

        build(new DateField(), EN_US);
        type("2026-12-31");
        assertEquals(LocalDate.of(2026, 12, 31), field.date());

        build(new DateField().setGranularity(DateField.Granularity.MONTH), PT_BR);
        type("2026-12");
        assertEquals(LocalDate.of(2026, 12, 1), field.date(), "a month field takes the year and month");
    }

    @Test
    void aTypedTwoDigitYearResolvesWhenTheCaretLeavesIt() {
        build(new DateField(), PT_BR);
        field.setClock(IN_2026);
        type("3112");
        type("26");
        assertEquals(LocalDate.of(26, 12, 31), field.date(), "while the caret is still in the year");
        key(Keys.HOME);
        assertEquals(LocalDate.of(2026, 12, 31), field.date(), "left with Home, it is this century");

        build(new DateField(), PT_BR);
        field.setClock(IN_2026);
        type("311285");
        scene.requestFocus(null);
        assertEquals(LocalDate.of(1985, 12, 31), field.date(), "left with the focus, the same");

        build(new DateField(), PT_BR);
        field.setClock(IN_2026);
        field.setTwoDigitYearWindow(DateField.REFUSE_TWO_DIGIT_YEARS);
        type("311226");
        assertEquals(LocalDate.of(26, 12, 31), field.date(), "while the caret is still in the year");
        key(Keys.HOME);
        assertNull(field.date(), "with the guess off a typed two-digit year is left blank too");
        assertEquals("31/12/----", field.text());
        assertFalse(field.isValid(), "and the field is incomplete, as after a paste (decision 57)");
    }

    // ------------------------------------------------------------ eras (era-year-width, decision 38)

    private static final Locale JAPANESE = Locale.forLanguageTag("ja-JP-u-ca-japanese");
    private static final Locale MINGUO = Locale.forLanguageTag("zh-TW-u-ca-roc");
    private static final Clock IN_2026 =
            Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);

    /**
     * ADR 042 §3's four-digit widening removes a two-digit year's ambiguity; a year of era inside
     * a named era has none, so it is drawn at its own width and typed with one to three digits.
     * Before 2026-09-14 the field drew "R0008/9/9" and "民國0115/9/9" and waited for four digits.
     */
    @Test
    void anEraYearIsDrawnAtItsOwnWidthAndTypedWithUpToThreeDigits() {
        build(new DateField(), JAPANESE);
        field.setClock(IN_2026);
        field.setDate(LocalDate.of(2026, 9, 9));
        assertEquals("R8/9/9", field.text());
        assertEquals(LocalDate.of(2026, 9, 9), field.date(), "the value is ISO either way");

        build(new DateField(), MINGUO);
        field.setClock(IN_2026);
        field.setDate(LocalDate.of(2026, 9, 9));
        assertEquals("民國115/9/9", field.text(), "the Republic's 115th year, at its own width");
        type("116");            // three digits fill the year and roll on to the month
        type("3");
        key(Keys.RIGHT);
        type("4");
        assertEquals(LocalDate.of(2027, 3, 4), field.date(), "Minguo 116-03-04");

        build(new DateField(), JAPANESE);
        field.setClock(IN_2026);
        type("9");              // one digit: the year waits for a Right rather than a fourth digit
        key(Keys.RIGHT);
        type("1");
        key(Keys.RIGHT);
        type("2");
        assertEquals(LocalDate.of(2027, 1, 2), field.date(), "Reiwa 9-01-02, the era from the clock");
    }

    /**
     * Whether the years carry their era is held per chronology rather than derived on every
     * call (it read the clock and converted today through the chronology, several times a
     * frame), so what has to hold is that the held answer goes with the chronology: a field
     * moved from the Japanese calendar to ISO widens its year again and wants four digits.
     */
    @Test
    void aFieldMovedOffAnEraCalendarWidensItsYearAgain() {
        build(new DateField(), JAPANESE);
        field.setClock(IN_2026);
        field.setDate(LocalDate.of(2026, 9, 9));
        assertEquals("R8/9/9", field.text());
        field.setChronology(java.time.chrono.IsoChronology.INSTANCE);
        assertEquals("2026/09/09", field.text(), "the same day, in the language's ISO form");
        key(Keys.HOME);
        type("2027");           // four digits are a year again; three would have rolled on
        assertEquals(LocalDate.of(2027, 9, 9), field.date());
    }

    @Test
    void anEmptyEraFieldTypesIntoTheClocksEra() {
        build(new DateField(), JAPANESE);
        field.setClock(Clock.fixed(Instant.parse("2018-06-01T12:00:00Z"), ZoneOffset.UTC));
        type("30");             // Heisei 30 by that clock; Reiwa 30 (2048) by the wall clock's era
        key(Keys.RIGHT);
        type("6");
        key(Keys.RIGHT);
        type("1");
        assertEquals(LocalDate.of(2018, 6, 1), field.date());
        assertEquals("H30/6/1", field.text());
    }

    @Test
    void aDateOutsideTheBoundsIsHeldAndReportedRatherThanSnapped() {
        build(new DateField(), PT_BR);
        field.setMinDate(LocalDate.of(2026, 9, 1));
        field.setMaxDate(LocalDate.of(2026, 9, 30));
        field.setDate(LocalDate.of(2026, 10, 6));
        assertEquals(LocalDate.of(2026, 10, 6), field.date(),
                "what was typed is still what the field holds");
        assertFalse(field.isValid());
        assertNotNull(field.validationMessage());

        field.setDate(LocalDate.of(2026, 9, 15));
        assertTrue(field.isValid());
    }

    @Test
    void aFilteredDateIsInvalidWithItsOwnMessage() {
        build(new DateField(), PT_BR);
        field.setDateFilter(day -> day.getDayOfMonth() != 13);
        field.setDate(LocalDate.of(2026, 11, 13));
        assertFalse(field.isValid());
        assertNotNull(field.validationMessage());
        assertEquals("limn.date.invalid.unavailable", field.validationMessage().key());
    }

    @Test
    void theValidityMessageNamesWhichRuleWasBroken() {
        build(new DateField(), PT_BR);
        type("31");
        assertEquals("limn.date.invalid.incomplete", field.validationMessage().key());
        field.setMaxDate(LocalDate.of(2026, 1, 1));
        field.setDate(LocalDate.of(2026, 5, 5));
        assertEquals("limn.date.invalid.outOfRange", field.validationMessage().key());
    }

    @Test
    void aChangeOfCalendarReDerivesTheSegmentsFromTheIsoValue() {
        build(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 9, 9));
        String iso = field.text();
        field.setChronology(Chronology.of("ThaiBuddhist"));
        assertEquals(LocalDate.of(2026, 9, 9), field.date(), "the value the application reads is ISO");
        assertFalse(field.text().equals(iso), "and what is drawn is the Buddhist year");
        assertTrue(field.text().contains("2569"), "which is 543 greater: " + field.text());
    }

    @Test
    void everyAnnouncedAspectHasAnAccessorAndAWriteOfTheSameValueAnnouncesNothing() {
        build(new DateField(), PT_BR);
        field.setDate(LocalDate.of(2026, 9, 9));
        List<Change.Aspect> heard = new ArrayList<>();
        field.observeChanges((widget, change) -> heard.add(change.aspect()));
        field.setDate(LocalDate.of(2026, 9, 9));
        assertTrue(heard.isEmpty(), "nothing moved");

        field.setDate(LocalDate.of(2026, 9, 10));
        assertTrue(heard.contains(Change.Aspect.VALUE));
        assertEquals(LocalDate.of(2026, 9, 10), field.date());

        heard.clear();
        field.setMinDate(LocalDate.of(2026, 9, 20));
        assertTrue(heard.contains(Change.Aspect.RANGE));
        assertTrue(heard.contains(Change.Aspect.VALIDITY), "and the value stopped being acceptable");
        assertEquals(LocalDate.of(2026, 9, 20), field.minDate());
    }

    @Test
    void theHandlerRunsForTheUserAndNotForACallersWrite() {
        build(new DateField(), PT_BR);
        List<LocalDate> heard = new ArrayList<>();
        field.onChange(heard::add);
        field.setDate(LocalDate.of(2026, 9, 9));
        assertTrue(heard.isEmpty(), "a caller's write reaches no handler (ADR 040)");
        key(Keys.UP);
        assertEquals(1, heard.size());
        assertEquals(field.date(), heard.get(0));
    }

    /**
     * ADR 042 §3: the era is drawn and never edited, and the caret never stops on it -- Home
     * lands on the year beside it, Left from there stays, and what is typed there is the year.
     */
    @Test
    void theCaretNeverStopsOnTheEra() {
        build(new DateField(), JAPANESE);
        field.setClock(IN_2026);
        field.setDate(LocalDate.of(2026, 9, 9));
        key(Keys.END);
        key(Keys.HOME);
        assertEquals(0, field.focusedSegment(), "the first editable segment is the year");
        key(Keys.LEFT);
        assertEquals(0, field.focusedSegment(), "and there is nothing editable before it");
        type("9");
        key(Keys.RIGHT);
        assertEquals("R9/9/9", field.text(), "the digit went into the year, not the era");
        assertEquals(LocalDate.of(2027, 9, 9), field.date());
    }
}
