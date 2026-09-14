package limn.components.date;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.SizeTokens;
import limn.components.Strokes;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.RoundRect;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.LanguageWitness;
import limn.input.Keys;
import limn.lang.Checks;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.CharEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.Chronology;
import java.time.chrono.Era;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A date, a time, or both, typed into segments the language orders.
 *
 * <p>Two starts and one knob decide the shape (ADR 042 &sect;2, amended 2026-09-14): a field
 * starts at the year or at the hour, and {@link #setGranularity} says how fine it goes.
 *
 * <pre>{@code
 * new DateField()                              // 31/12/2026   -- a date
 * new DateField().setGranularity(MONTH)        // 12/2026      -- a month
 * new DateField().setGranularity(MINUTE)       // 31/12/2026 14:30
 * DateField.ofTime()                           // 14:30        -- a time of day
 * DateField.ofTime().setGranularity(SECOND)    // 14:30:45
 * new DatePicker()                             // the first, with a calendar to pick from
 * }</pre>
 *
 * <p><b>The segments and the separators are the locale's</b> (ADR 042 &sect;3). They come from the
 * language's own short pattern, so a Brazilian field reads day, month, year, an American one month,
 * day, year, a Japanese one year, month, day, and a Korean one keeps the full stop after the last
 * of them. A two-digit year in a pattern is widened to four here, because the field is edited and a
 * two-digit year in something a person types is an ambiguity the toolkit would be creating on
 * purpose.
 *
 * <p><b>The keyboard.</b> Up and Down adjust the focused segment and roll over its bounds; Left and
 * Right move between segments; Home and End go to the first and last; Delete and Backspace clear
 * the focused segment. Typing digits fills the focused segment and moves on to the next when it can
 * hold no more, which is what makes a date typable as a run of digits: {@code 31122026} in a
 * day-month-year language commits the last day of 2026 without a separator being typed. A separator
 * typed anyway moves on, so a person who types the slashes is not fighting the field either.
 * {@code Ctrl/Cmd+C} copies the whole value as shown and {@code Ctrl/Cmd+V} parses what is on the
 * clipboard, which is where {@code 2026-12-31} pasted out of a spreadsheet is understood.
 *
 * <p><b>An incomplete field has no value.</b> {@link #date()} answers {@code null} until every
 * segment of the date is filled, and the field publishes itself invalid meanwhile. It never guesses
 * the missing part: guessing is what makes a form submit a date nobody typed.
 *
 * <p><b>Bounds are held, not snapped.</b> A date outside {@link #setMinDate}/{@link #setMaxDate} or
 * refused by {@link #setDateFilter} stays on screen, the field publishes {@code VALIDITY} with a
 * message saying which of the three it broke, and {@link #date()} still answers what was typed.
 * Snapping a typed date to the nearest legal one throws away what the person wrote and tells them
 * nothing.
 *
 * <p><b>To a screen reader it is a group of spin buttons</b>, one per editable segment, which is
 * the shape {@code Spinner} publishes for its two arrows and for the same reason: the caret is in
 * one segment at a time, and a single text field publishing the whole date gives a reader no way to
 * say which part that is.
 *
 * <p><b>Reading right to left the segments do not reorder.</b> A date is a run of numbers, and a run
 * of numbers keeps its own left-to-right order inside a right-to-left line: that is the
 * bidirectional algorithm's rule and not a convention of this toolkit, and it is the same reading
 * {@code Spinner} documents for its clock. What mirrors is which side of the box the run sits
 * against.
 */
public class DateField extends Widget {

    /**
     * How fine a field goes: which segment is its last. A field that starts at the year edits
     * down to this; one that starts at the hour edits from there down to it, and refuses the three
     * date levels (decision 12, 2026-09-14).
     *
     * <p>A closed list on purpose. A week, a quarter or a decade would each be a real piece of
     * design &mdash; what the segment shows, what a range of them means &mdash; and a value in an
     * open set that nobody had designed for would be a field that draws nothing.
     */
    public enum Granularity {
        /** The year alone. A range of years runs from 1 January to 31 December. */
        YEAR,
        /** Year and month: a month picker. A range of months runs from the 1st to the last day. */
        MONTH,
        /** A whole date: the default for {@code new DateField()}. */
        DAY,
        /** Down to the hour. A range's end is the last minute of its hour. */
        HOUR,
        /** Down to the minute: the default for {@link #ofTime()}. */
        MINUTE,
        /** Down to the second. */
        SECOND;

        /** Whether this level carries a time of day. */
        boolean hasTime() {
            return this.compareTo(HOUR) >= 0;
        }

        /** Whether this level holds a segment for a field, or is coarser than it. */
        boolean holds(Granularity segment) {
            return this.compareTo(segment) >= 0;
        }
    }

    /** No value in a segment. Not -1: an hour of zero and a minute of zero are ordinary. */
    private static final int UNSET = Integer.MIN_VALUE;

    /** Whether the segments start at the year (a date) or at the hour (a time of day). */
    private final boolean startsAtYear;
    private Granularity granularity;
    /**
     * Which end of a period this field is, when it is one end of a {@link DatePicker#ofRange()}:
     * the end of a period answers the last day or instant of what it names (a month picker's end
     * field reads June and answers the 30th), the start the first (decision 51, 2026-09-14). A
     * field that is not an end of anything is a start, which is what a single value is.
     */
    private boolean periodEnd;

    private Chronology declaredChronology;
    /** Where today and now are read from for an empty segment's first step; see setClock. */
    private Clock clock;

    /**
     * The value for {@link #setTwoDigitYearWindow} that turns the guess off: a two-digit year
     * pasted into the field is left blank and the field stays incomplete.
     */
    public static final int REFUSE_TWO_DIGIT_YEARS = -1;

    /** How many years back the century window for a two-digit year starts; see the setter. */
    private int twoDigitYearWindow = 80;

    /**
     * The characters typed since the last key or click, kept so a whole ISO date typed digit by
     * digit ({@code 2026-12-31}) is recognised as one (ADR 042 &sect;3): typed into a day-first
     * field segment by segment it committed 0001-02-20 and called it valid.
     */
    private final StringBuilder typedRun = new StringBuilder();

    // ---- the pattern, taken apart, rebuilt only when the language or the calendar moves --------

    private final LanguageWitness patternLanguage = new LanguageWitness();
    private Chronology patternChronology;
    private Granularity patternGranularity;
    private List<DatePattern.Part> parts = List.of();
    /** Indices into {@link #parts} of the segments the keyboard stops on, in order. */
    private int[] editable = new int[0];
    /** The widest form the value can take, which is what the field is measured from. */
    private String widestForm = "";

    // ---- the value -----------------------------------------------------------------------------

    /** The date, ISO, or {@code null} while the date segments are incomplete. */
    private LocalDate dateValue;
    /** The time, or {@code null} while the time segments are incomplete. */
    private LocalTime timeValue;
    /** Year, month and day <b>in the calendar being drawn</b>; the ISO value above is derived. */
    private int year = UNSET;
    private int month = UNSET;
    private int day = UNSET;
    private int hour = UNSET; // always 0..23, whatever clock the pattern shows
    private int minute = UNSET;
    private int second = UNSET;

    private int focusedSlot;
    /** How many digits have been typed into the focused segment since it was last entered. */
    private int typedDigits;

    private LocalDate minDate;
    private LocalDate maxDate;
    private Predicate<LocalDate> dateFilter;

    private I18nString validity;
    private Consumer<LocalDate> onChange;
    private Consumer<LocalTime> onTimeChange;
    /** Which half moved, so one announcement reaches the one handler it belongs to. */
    private boolean lastMoveWasTime;

    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);

    /**
     * Whether this field paints its own box and border. A field inside a {@link DatePicker} does
     * not: the picker paints one box around both ends of a period and the trailing button, so the
     * control reads as one control rather than as two fields that happen to be adjacent.
     */
    private boolean chrome = true;
    /**
     * What the picker takes before the field sees a key. Package-private and single-slotted,
     * because it is the picker's wiring and not an application's: the handler slots stay free for
     * whoever asked for {@code picker.field()}.
     */
    private java.util.function.Consumer<KeyEvent> keyDelegate;
    /** The picker's, for the characters: see {@link #keyDelegate}. */
    private java.util.function.Consumer<CharEvent> charDelegate;
    /**
     * Whether a picker is aiming the keyboard at this field although it does not hold the focus:
     * the time row inside a popup, which the popup contract keeps unfocused. {@code CalendarView}'s
     * flag of the same name, for the same reason. Package-private and the picker's.
     */
    private boolean keyboardActive;

    /** A field that edits a date, down to the day. */
    public DateField() {
        this(true);
    }

    private DateField(boolean date) {
        DateStrings.ensureRegistered();
        this.startsAtYear = date;
        this.granularity = date ? Granularity.DAY : Granularity.MINUTE;
        setFocusable(true);
        setCursor(Cursor.TEXT);
    }

    /**
     * A field that edits a time of day, on the clock the language keeps: twenty-four hours in
     * Portuguese and German, twelve and a day period in English and Korean. Down to the minute
     * until {@link #setGranularity} says otherwise.
     *
     * @return the field
     */
    public static DateField ofTime() {
        return new DateField(false);
    }

    /** @return whether this field carries date segments: it starts at the year */
    public boolean hasDate() {
        return startsAtYear;
    }

    /** @return whether this field carries time segments: it goes down to at least the hour */
    public boolean hasTime() {
        return granularity.hasTime();
    }

    /** @return how fine this field goes; {@link Granularity#DAY} for a date, {@link Granularity#MINUTE} for a time */
    public Granularity granularity() {
        return granularity;
    }

    /**
     * How fine this field goes: which segment is its last (decision 12, 2026-09-14).
     *
     * <p>A field that starts at the year takes any level: {@link Granularity#MONTH} makes it a
     * month picker with no day, {@link Granularity#MINUTE} adds a clock after the date. A field
     * that starts at the hour takes {@link Granularity#HOUR}, {@link Granularity#MINUTE} or
     * {@link Granularity#SECOND} and refuses the three date levels: it has no date to edit down
     * to, and silently accepting the call would leave a field that shows nothing.
     *
     * <p>Segments the new level no longer holds are emptied; the value follows (a date field
     * made a month field answers the first of its month), and the change is announced as
     * {@code VALUE} from code when it moved.
     *
     * @param level how fine to go
     * @return this
     * @throws IllegalArgumentException for a date level on a field built by {@link #ofTime()}
     */
    public DateField setGranularity(Granularity level) {
        Ui.checkUiThread();
        Objects.requireNonNull(level, "granularity");
        if (!startsAtYear && !level.hasTime()) {
            throw new IllegalArgumentException("a time field has no date to edit down to " + level
                    + "; build a date field with new DateField() and set the level on that");
        }
        if (granularity == level) {
            return this;
        }
        granularity = level;
        if (!level.holds(Granularity.MONTH)) {
            month = UNSET;
        }
        if (!level.holds(Granularity.DAY)) {
            day = UNSET;
        }
        if (!level.hasTime()) {
            hour = UNSET;
        }
        if (!level.holds(Granularity.MINUTE)) {
            minute = UNSET;
        }
        if (!level.holds(Granularity.SECOND)) {
            second = UNSET;
        }
        LocalDate dateWas = dateValue;
        LocalTime timeWas = timeValue;
        rebuildValue();
        markNeedsLayout();
        if (!Objects.equals(dateWas, dateValue) || !Objects.equals(timeWas, timeValue)) {
            lastMoveWasTime = Objects.equals(dateWas, dateValue);
            notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.CODE));
        }
        refreshValidity(Change.Origin.CODE);
        return this;
    }

    /** The picker's, not an application's: see {@link #periodEnd}. */
    void setPeriodEnd(boolean end) {
        if (periodEnd == end) {
            return;
        }
        periodEnd = end;
        rebuildValue();
    }

    // ------------------------------------------------------------------ the value

    /**
     * @return the date, or {@code null} if this field has no date part or its segments are not all
     *         filled. A date outside the bounds is still answered here: the field holds what was
     *         typed and says separately that it is not acceptable (see {@link #isValid()}). At a
     *         {@link Granularity} coarser than a day this is the first day of the period named
     *         &mdash; the last day for the end field of a {@link DatePicker#ofRange()} (decision
     *         51, 2026-09-14)
     */
    public LocalDate date() {
        return dateValue;
    }

    /**
     * @return the time of day, or {@code null} if this field has no time part or it is incomplete.
     *         At {@link Granularity#HOUR} the minute is zero, and for the end field of a period
     *         the last one of the hour, with the seconds to match (decision 51)
     */
    public LocalTime time() {
        return timeValue;
    }

    /**
     * @return both halves as one value, or {@code null} unless this field carries both and both are
     *         complete. Never invented: a date-only field answers {@code null} here rather than
     *         midnight
     */
    public LocalDateTime dateTime() {
        return dateValue != null && timeValue != null ? LocalDateTime.of(dateValue, timeValue) : null;
    }

    /**
     * Sets the date, or clears it.
     *
     * @param date the date, or {@code null} to empty the date segments
     * @return this
     */
    public DateField setDate(LocalDate date) {
        Ui.checkUiThread();
        LocalDate was = dateValue;
        if (Objects.equals(was, date)) {
            return this;
        }
        applyDate(date);
        if (Objects.equals(was, dateValue)) {
            return this; // a day of the period already shown: a month field is told 15 June twice
        }
        lastMoveWasTime = false;
        invalidate();
        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.CODE));
        refreshValidity(Change.Origin.CODE);
        return this;
    }

    /**
     * Sets the time of day, or clears it.
     *
     * @param time the time, or {@code null} to empty the time segments
     * @return this
     */
    public DateField setTime(LocalTime time) {
        Ui.checkUiThread();
        writeTime(time, Change.Origin.CODE);
        return this;
    }

    /**
     * The picker's route for its popup time row: the same write as {@link #setTime}, announced
     * with the origin the row's keystroke had, so a person typing into the row reaches the
     * application's handler exactly as if they had typed into this field.
     */
    void writeTime(LocalTime time, Change.Origin origin) {
        LocalTime was = timeValue;
        if (Objects.equals(was, time)) {
            return;
        }
        applyTime(time);
        if (Objects.equals(was, timeValue)) {
            return;
        }
        lastMoveWasTime = true;
        invalidate();
        notifyChange(Change.of(Change.Aspect.VALUE, origin));
        refreshValidity(origin);
    }

    /**
     * Sets both halves at once, which is one announcement rather than two: a caller writing a
     * timestamp is making one change, and a watcher that saw two would see a moment that never
     * existed between them.
     *
     * @param value the moment, or {@code null} to empty every segment
     * @return this
     */
    public DateField setDateTime(LocalDateTime value) {
        Ui.checkUiThread();
        LocalDate date = value == null ? null : value.toLocalDate();
        LocalTime time = value == null ? null : value.toLocalTime();
        LocalDate dateWas = dateValue;
        LocalTime timeWas = timeValue;
        if (Objects.equals(dateWas, date) && Objects.equals(timeWas, time)) {
            return this;
        }
        applyDate(date);
        applyTime(time);
        if (Objects.equals(dateWas, dateValue) && Objects.equals(timeWas, timeValue)) {
            return this;
        }
        invalidate();
        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.CODE));
        refreshValidity(Change.Origin.CODE);
        return this;
    }

    /** Clears every segment. */
    public DateField clear() {
        return setDateTime(null);
    }

    /**
     * Writes a date into the segments this level holds and re-derives the value from them, so a
     * month field told 15 June holds June and answers the 1st (or, as the end of a period, the
     * 30th): the value is always what the segments say, never a day the segments cannot show.
     */
    private void applyDate(LocalDate date) {
        valueRevision++;
        if (!startsAtYear || date == null) {
            year = month = day = UNSET;
            dateValue = null;
            return;
        }
        ChronoLocalDate drawn = CalendarChronology.date(chronology(), date);
        if (drawn == null) {
            // A date the drawn calendar cannot reach. The ISO value stands, and the segments show
            // what ISO says, which is what the fallback in CalendarChronology means everywhere.
            year = date.getYear();
            month = granularity.holds(Granularity.MONTH) ? date.getMonthValue() : UNSET;
            day = granularity.holds(Granularity.DAY) ? date.getDayOfMonth() : UNSET;
            dateValue = date;
            return;
        }
        year = drawn.get(ChronoField.YEAR_OF_ERA);
        month = granularity.holds(Granularity.MONTH) ? drawn.get(ChronoField.MONTH_OF_YEAR) : UNSET;
        day = granularity.holds(Granularity.DAY) ? drawn.get(ChronoField.DAY_OF_MONTH) : UNSET;
        LocalDate rebuilt = buildDate(year, month, day);
        dateValue = rebuilt == null ? date : rebuilt;
    }

    private void applyTime(LocalTime time) {
        valueRevision++;
        if (!hasTime() || time == null) {
            hour = minute = second = UNSET;
            timeValue = null;
            return;
        }
        hour = time.getHour();
        minute = granularity.holds(Granularity.MINUTE) ? time.getMinute() : UNSET;
        second = granularity.holds(Granularity.SECOND) ? time.getSecond() : UNSET;
        timeValue = buildTime();
    }

    /**
     * Rebuilds the two values from the segments after one of them was edited: the date is a date
     * only when every segment this level holds is filled and they together name a day that exists.
     */
    private void rebuildValue() {
        valueRevision++;
        LocalDate date = null;
        if (startsAtYear && year != UNSET
                && (!granularity.holds(Granularity.MONTH) || month != UNSET)
                && (!granularity.holds(Granularity.DAY) || day != UNSET)) {
            date = buildDate(year, month, day);
        }
        dateValue = date;
        timeValue = buildTime();
    }

    /**
     * The time the time segments name, or {@code null} while one this level holds is empty. A
     * segment below the level is the period's first instant, or its last for the end of a
     * period: an hour field's end at 14 is 14:59:59 (decision 51).
     */
    private LocalTime buildTime() {
        if (!hasTime() || hour == UNSET
                || granularity.holds(Granularity.MINUTE) && minute == UNSET
                || granularity.holds(Granularity.SECOND) && second == UNSET) {
            return null;
        }
        int m = granularity.holds(Granularity.MINUTE) ? minute : periodEnd ? 59 : 0;
        int s = granularity.holds(Granularity.SECOND) ? second : periodEnd ? 59 : 0;
        return LocalTime.of(hour, m, s);
    }

    /**
     * The segment values as an ISO date, clamping the day to the month's length: a person who
     * types 31 into a February is typing the last day of it, and refusing the whole date because
     * one segment overshot is how a field ends up empty for reasons nobody can see.
     *
     * <p>A segment below this field's level is {@link #UNSET} and stands for the whole period: the
     * first day of the month or year, or the last day of it for the end of a period, in the
     * calendar being drawn &mdash; the 30th or the 31st, the 28th or the 29th, the last day of a
     * Hijri year that has no 31 December (decision 51, 2026-09-14).
     *
     * @return the date, or {@code null} if the calendar being drawn has no such day at all
     */
    private LocalDate buildDate(int yearOfEra, int monthOfYear, int dayOfMonth) {
        Chronology chronology = chronology();
        try {
            Era era = eraForBuilding(chronology);
            if (monthOfYear == UNSET) {
                ChronoLocalDate first = era == null
                        ? chronology.date(yearOfEra, 1, 1)
                        : chronology.date(era, yearOfEra, 1, 1);
                return CalendarChronology.iso(periodEnd
                        ? first.with(ChronoField.DAY_OF_YEAR, first.lengthOfYear()) : first);
            }
            ChronoLocalDate first = era == null
                    ? chronology.date(yearOfEra, monthOfYear, 1)
                    : chronology.date(era, yearOfEra, monthOfYear, 1);
            int wanted = dayOfMonth == UNSET ? periodEnd ? first.lengthOfMonth() : 1 : dayOfMonth;
            int clamped = Math.min(wanted, first.lengthOfMonth());
            return CalendarChronology.iso(first.with(ChronoField.DAY_OF_MONTH, clamped));
        } catch (DateTimeException | ArithmeticException e) {
            return null;
        }
    }

    /**
     * Which era a typed year belongs to: the one the current value is in, or the one today is in.
     *
     * <p>ISO answers {@code null} and takes the proleptic-year call, which is the same year for
     * every date this field can hold. The era segment is read-only (ADR 042 &sect;3), so this is
     * the whole of how an era is decided, and it is why a Japanese field cannot yet be typed
     * across an era boundary.
     */
    private Era eraForBuilding(Chronology chronology) {
        if (chronology == IsoChronology.INSTANCE) {
            return null;
        }
        ChronoLocalDate reference = dateValue != null
                ? CalendarChronology.date(chronology, dateValue) : null;
        if (reference == null) {
            // Today by the widget's clock (ADR 042 §1), not the wall clock: an empty Japanese
            // field under a clock set to 2018 types into Heisei, and a capture pinned to a day
            // types into that day's era (DATES-NEW-10, 2026-09-14).
            reference = CalendarChronology.date(chronology, today());
            if (reference == null) {
                return null;
            }
        }
        return reference.getEra();
    }

    /** Today, by this field's clock. */
    private LocalDate today() {
        return clock == null ? LocalDate.now() : LocalDate.now(clock);
    }

    /**
     * Whether the drawn calendar's years are spoken and typed with their era: Reiwa 8 and
     * Minguo 115 say nothing without it, where 2026, 2569 and 1448 do (era-year-width,
     * 2026-09-14). Read off the year of era today, never off a list of chronologies.
     */
    private boolean eraCalendar() {
        return CalendarChronology.yearsNameTheirEra(chronology(), today());
    }

    // ------------------------------------------------------------------ bounds and validity

    /** @return the earliest acceptable date, or {@code null} for no lower bound */
    public LocalDate minDate() {
        return minDate;
    }

    /**
     * The earliest acceptable date. A typed date before it is held and published invalid, never
     * snapped.
     *
     * @param date the bound, or {@code null} for none
     * @return this
     */
    public DateField setMinDate(LocalDate date) {
        Ui.checkUiThread();
        if (Objects.equals(minDate, date)) {
            return this;
        }
        minDate = date;
        notifyChange(Change.of(Change.Aspect.RANGE, Change.Origin.CODE));
        refreshValidity(Change.Origin.ADJUSTMENT);
        return this;
    }

    /** @return the latest acceptable date, or {@code null} for no upper bound */
    public LocalDate maxDate() {
        return maxDate;
    }

    /**
     * The latest acceptable date; the mirror of {@link #setMinDate}.
     *
     * @param date the bound, or {@code null} for none
     * @return this
     */
    public DateField setMaxDate(LocalDate date) {
        Ui.checkUiThread();
        if (Objects.equals(maxDate, date)) {
            return this;
        }
        maxDate = date;
        notifyChange(Change.of(Change.Aspect.RANGE, Change.Origin.CODE));
        refreshValidity(Change.Origin.ADJUSTMENT);
        return this;
    }

    /** @return the filter, or {@code null} if every date inside the bounds is acceptable */
    public Predicate<LocalDate> dateFilter() {
        return dateFilter;
    }

    /**
     * Which dates are acceptable beyond the two bounds. Unlike the calendar's, this one is called
     * when the value changes rather than once per painted cell, so it may be as expensive as an
     * application's own validation is.
     *
     * @param filter answers whether a date is acceptable, or {@code null} to accept every date
     * @return this
     */
    public DateField setDateFilter(Predicate<LocalDate> filter) {
        Ui.checkUiThread();
        dateFilter = filter;
        notifyChange(Change.of(Change.Aspect.RANGE, Change.Origin.CODE));
        refreshValidity(Change.Origin.ADJUSTMENT);
        return this;
    }

    /**
     * @return whether what the field holds is complete and acceptable. An empty field is
     *         <b>valid</b>: a form decides whether a date is required, and a field that called
     *         itself invalid the moment it was created would put an error on every blank form
     */
    public boolean isValid() {
        return validity == null;
    }

    /**
     * @return why the field is not valid, in the field's own language, or {@code null} when it is.
     *         This is what a screen reader is told after "invalid", and what an application shows
     *         beside the field if it does not have a message of its own
     */
    public I18nString validationMessage() {
        return validity;
    }

    /** Recomputes the validity and announces it only when it actually changed. */
    private void refreshValidity(Change.Origin origin) {
        I18nString next = computeValidity();
        if (Objects.equals(validity, next)) {
            return;
        }
        validity = next;
        invalidate();
        notifyChange(Change.of(Change.Aspect.VALIDITY, origin));
    }

    private I18nString computeValidity() {
        if (isEmpty()) {
            return null; // a blank field is not an error; a required one is the form's business
        }
        if (startsAtYear && dateValue == null || hasTime() && timeValue == null) {
            return DateStrings.INVALID_INCOMPLETE;
        }
        if (dateValue != null) {
            if (minDate != null && dateValue.isBefore(minDate)
                    || maxDate != null && dateValue.isAfter(maxDate)) {
                return DateStrings.INVALID_OUT_OF_RANGE;
            }
            if (dateFilter != null && !dateFilter.test(dateValue)) {
                return DateStrings.INVALID_UNAVAILABLE;
            }
        }
        return null;
    }

    /** @return whether every segment is empty */
    public boolean isEmpty() {
        return year == UNSET && month == UNSET && day == UNSET
                && hour == UNSET && minute == UNSET && second == UNSET;
    }

    /** @return the calendar system the segments are shown in */
    public Chronology chronology() {
        return CalendarChronology.resolve(declaredChronology, locale());
    }

    /**
     * Shows the segments in a calendar system of the application's choosing rather than the
     * language's; the value exchanged stays ISO either way.
     *
     * @param chronology the calendar, or {@code null} to follow the language
     * @return this
     */
    public DateField setChronology(Chronology chronology) {
        Ui.checkUiThread();
        if (Objects.equals(declaredChronology, chronology)) {
            return this;
        }
        declaredChronology = chronology;
        // The segments hold fields of the calendar being drawn, so they are re-derived from the
        // ISO value rather than reinterpreted: 1448 in Hijri is not 1448 in ISO.
        applyDate(dateValue);
        markNeedsLayout();
        return this;
    }

    // ------------------------------------------------------------------ handlers

    /**
     * Called when the user moves the date, with what it is now &mdash; {@code null} while the
     * segments are incomplete, which is the state a person is in for most of the time they are
     * typing.
     *
     * <p>The user alone (ADR 040): {@link #setDate} from code never reaches it.
     *
     * @param listener what to run, or {@code null} to clear the slot
     * @return this
     * @throws IllegalStateException if a handler is already registered
     */
    public DateField onChange(Consumer<LocalDate> listener) {
        Ui.checkUiThread();
        this.onChange = Checks.handlerSlot(onChange, listener, "DateField.onChange");
        return this;
    }

    /**
     * Called when the user moves the time of day. A field with both halves reaches this one for a
     * change to the clock and {@link #onChange} for a change to the date, so an application that
     * wants the whole value reads {@link #dateTime()} from either.
     *
     * @param listener what to run, or {@code null} to clear the slot
     * @return this
     * @throws IllegalStateException if a handler is already registered
     */
    public DateField onTimeChange(Consumer<LocalTime> listener) {
        Ui.checkUiThread();
        this.onTimeChange = Checks.handlerSlot(onTimeChange, listener, "DateField.onTimeChange");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.VALUE) {
            if (lastMoveWasTime) {
                if (onTimeChange != null) {
                    onTimeChange.accept(timeValue);
                }
            } else if (onChange != null) {
                onChange.accept(dateValue);
            }
            return;
        }
        super.handleUserChange(aspect);
    }

    // ------------------------------------------------------------------ the pattern

    /**
     * Rebuilds the segment list when the language, the calendar or the granularity moved.
     *
     * <p>The date pattern and the time pattern are fetched separately and joined with a space,
     * never carved out of one combined pattern: carving would leave the joining words of a dozen
     * languages stranded, and which of them to drop is a guess in every language nobody in the room
     * reads (ADR 042 &sect;3). A level coarser than the pattern is cut from the pattern's own
     * end: the day and its separator go for a month field, the minute and its colon for an hour
     * field ({@link DatePattern#without}).
     */
    private void ensureParts() {
        Locale locale = locale();
        Chronology chronology = chronology();
        boolean languageMoved = patternLanguage.moved();
        if (!languageMoved && parts != null && !parts.isEmpty()
                && Objects.equals(patternChronology, chronology)
                && patternGranularity == granularity) {
            return;
        }
        // The published value texts are the language's (a month name, the word for an empty
        // segment), so the witness they are handed with has to move when the language does.
        valueRevision++;
        patternChronology = chronology;
        patternGranularity = granularity;
        List<DatePattern.Part> built = new ArrayList<>();
        if (startsAtYear) {
            List<DatePattern.Part> date = DatePattern.shortDate(chronology, locale);
            if (!granularity.holds(Granularity.DAY)) {
                date = DatePattern.without(date, DatePattern.Field.DAY);
            }
            if (!granularity.holds(Granularity.MONTH)) {
                date = DatePattern.without(date, DatePattern.Field.MONTH);
            }
            built.addAll(date);
        }
        if (hasTime()) {
            if (startsAtYear) {
                built.add(new DatePattern.Literal(" "));
            }
            List<DatePattern.Part> time =
                    DatePattern.time(granularity.holds(Granularity.SECOND), locale);
            if (!granularity.holds(Granularity.MINUTE)) {
                time = DatePattern.without(time, DatePattern.Field.MINUTE);
            }
            built.addAll(time);
        }
        parts = List.copyOf(built);
        List<Integer> stops = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i) instanceof DatePattern.FieldPart f && f.field().editable()) {
                stops.add(i);
            }
        }
        editable = stops.stream().mapToInt(Integer::intValue).toArray();
        focusedSlot = Math.min(focusedSlot, Math.max(0, editable.length - 1));
        widestForm = buildWidestForm(locale);
    }

    /**
     * The widest the value can ever be drawn, which is what the box is measured from: a field that
     * measured its current value would change width as it was typed into, and a form's columns
     * would walk about while somebody filled them in.
     */
    private String buildWidestForm(Locale locale) {
        StringBuilder wide = new StringBuilder();
        for (DatePattern.Part part : parts) {
            if (part instanceof DatePattern.Literal literal) {
                wide.append(literal.text());
            } else if (part instanceof DatePattern.FieldPart field) {
                wide.append(widestFieldForm(field, locale));
            }
        }
        return wide.toString();
    }

    private String widestFieldForm(DatePattern.FieldPart field, Locale locale) {
        return switch (field.field()) {
            case MONTH -> field.width() >= 3 ? widestMonthName(locale) : "88";
            case YEAR -> eraCalendar() ? "888" : "8888";
            case DAY_PERIOD -> widestDayPeriod(locale);
            case ERA -> widestEra(locale);
            case OTHER -> "";
            default -> "88";
        };
    }

    private String widestMonthName(Locale locale) {
        String widest = "";
        for (java.time.Month month : java.time.Month.values()) {
            String name = month.getDisplayName(TextStyle.FULL_STANDALONE, locale);
            if (name.length() > widest.length()) {
                widest = name;
            }
        }
        return widest;
    }

    private String widestDayPeriod(Locale locale) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("a", locale);
        String am = formatter.format(LocalTime.of(6, 0));
        String pm = formatter.format(LocalTime.of(18, 0));
        return am.length() >= pm.length() ? am : pm;
    }

    private String widestEra(Locale locale) {
        Chronology chronology = chronology();
        String widest = "";
        for (Era era : chronology.eras()) {
            String name = era.getDisplayName(TextStyle.SHORT, locale);
            if (name.length() > widest.length()) {
                widest = name;
            }
        }
        return widest;
    }

    // ------------------------------------------------------------------ rendering a segment

    /**
     * What one segment shows: its digits, its name, or dashes standing for what has not been typed.
     *
     * <p>Dashes rather than the letters some platforms use ({@code dd/mm/yyyy}), because those
     * letters are an English mnemonic that is wrong in most languages and unreadable in most
     * scripts, and because a reader is told what the segment is by its own name in the tree.
     */
    private String segmentText(DatePattern.FieldPart field) {
        return switch (field.field()) {
            // Widened to four in a calendar whose year is the whole year; drawn at its own width
            // in one whose years carry an era (Reiwa 8, not 0008): the widening removes a
            // two-digit year's ambiguity, and a year of era inside a named era has none
            // (ADR 042 §3, amended 2026-09-14).
            case YEAR -> year == UNSET ? (eraCalendar() ? "--" : "----")
                    : eraCalendar() ? pad(year, 1) : pad(year, Math.max(4, field.width()));
            case MONTH -> monthText(field);
            case DAY -> day == UNSET ? "--" : pad(day, field.width());
            case HOUR24 -> hour == UNSET ? "--" : pad(hour, field.width());
            case HOUR12 -> hour == UNSET ? "--" : pad(((hour + 11) % 12) + 1, field.width());
            case MINUTE -> minute == UNSET ? "--" : pad(minute, 2);
            case SECOND -> second == UNSET ? "--" : pad(second, 2);
            case DAY_PERIOD -> hour == UNSET ? "--"
                    : DateTimeFormatter.ofPattern(field.pattern(), locale())
                            .format(LocalTime.of(hour, 0));
            case ERA, OTHER -> readOnlyText(field);
        };
    }

    private String monthText(DatePattern.FieldPart field) {
        if (month == UNSET) {
            return field.width() >= 3 ? "---" : "--";
        }
        if (field.width() < 3) {
            return pad(month, field.width());
        }
        // A named month is the calendar's, not java.time.Month's: the eighth month of a Hijri year
        // is not August. Rendered through the value so the name and the number cannot disagree.
        ChronoLocalDate drawn = dateValue == null ? null
                : CalendarChronology.date(chronology(), dateValue);
        if (drawn == null) {
            return java.time.Month.of(Math.min(12, Math.max(1, month)))
                    .getDisplayName(TextStyle.FULL_STANDALONE, locale());
        }
        return DateTimeFormatter.ofPattern(field.pattern(), locale())
                .withChronology(chronology()).format(drawn);
    }

    /** An era or any other field the keyboard does not stop on: drawn from the value, or blank. */
    private String readOnlyText(DatePattern.FieldPart field) {
        if (dateValue == null) {
            return "";
        }
        ChronoLocalDate drawn = CalendarChronology.date(chronology(), dateValue);
        if (drawn == null) {
            return "";
        }
        try {
            return DateTimeFormatter.ofPattern(field.pattern(), locale())
                    .withChronology(chronology()).format(drawn);
        } catch (DateTimeException e) {
            return "";
        }
    }

    /** ASCII digits first, then the language's numbering system: ADR 033's one format-time seam. */
    private static String pad(int value, int width) {
        String digits = Integer.toString(Math.abs(value));
        StringBuilder text = new StringBuilder();
        for (int i = digits.length(); i < width; i++) {
            text.append('0');
        }
        text.append(digits);
        return I18n.localizeDigits(text.toString());
    }

    /**
     * The whole value as it is drawn: what {@code Ctrl+C} copies, and what a reader is told the
     * field holds.
     *
     * @return the field's text, with its separators and its dashes
     */
    public String text() {
        ensureParts();
        StringBuilder out = new StringBuilder();
        for (DatePattern.Part part : parts) {
            if (part instanceof DatePattern.Literal literal) {
                out.append(literal.text());
            } else if (part instanceof DatePattern.FieldPart field) {
                out.append(segmentText(field));
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ editing one segment

    private DatePattern.FieldPart focusedField() {
        ensureParts();
        if (editable.length == 0) {
            return null;
        }
        int index = editable[Math.min(focusedSlot, editable.length - 1)];
        return (DatePattern.FieldPart) parts.get(index);
    }

    private int segmentValue(DatePattern.Field field) {
        return switch (field) {
            case YEAR -> year;
            case MONTH -> month;
            case DAY -> day;
            case HOUR24 -> hour;
            case HOUR12 -> hour == UNSET ? UNSET : ((hour + 11) % 12) + 1;
            case MINUTE -> minute;
            case SECOND -> second;
            case DAY_PERIOD -> hour == UNSET ? UNSET : hour / 12;
            default -> UNSET;
        };
    }

    private int segmentMin(DatePattern.Field field) {
        return switch (field) {
            case YEAR, MONTH, DAY, HOUR12 -> 1;
            default -> 0;
        };
    }

    private int segmentMax(DatePattern.Field field) {
        return switch (field) {
            // Not 9999: a year is bounded by what the calendar being drawn can hold, and Hijri
            // stops at 1600. Asked of the chronology rather than assumed. A year of era that
            // needs its era is one to three digits (era-year-width), which is what lets a typed
            // "115" roll on and a typed "8" wait for a Right.
            case YEAR -> eraCalendar() ? 999
                    : (int) Math.min(9999, chronology().range(ChronoField.YEAR_OF_ERA).getMaximum());
            case MONTH -> 12;
            case DAY -> daysInCurrentMonth();
            case HOUR24 -> 23;
            case HOUR12 -> 12;
            case MINUTE, SECOND -> 59;
            case DAY_PERIOD -> 1;
            default -> 0;
        };
    }

    /** How long the month on show is, in the calendar being drawn; 31 until one is nameable. */
    private int daysInCurrentMonth() {
        if (year == UNSET || month == UNSET) {
            return 31;
        }
        try {
            Era era = eraForBuilding(chronology());
            ChronoLocalDate first = era == null
                    ? chronology().date(year, month, 1)
                    : chronology().date(era, year, month, 1);
            return first.lengthOfMonth();
        } catch (DateTimeException | ArithmeticException e) {
            return 31;
        }
    }

    /**
     * Writes one segment and rebuilds the value. The one seam every edit goes through &mdash; a
     * key, a typed digit, a paste and an assistive technology's set &mdash; so all of them clamp
     * alike, announce alike and reach the handler alike.
     */
    private void writeSegment(DatePattern.Field field, int value, Change.Origin origin) {
        switch (field) {
            case YEAR -> year = value;
            case MONTH -> month = value;
            case DAY -> day = value;
            case HOUR24 -> hour = value;
            case HOUR12 -> {
                int half = hour == UNSET ? 0 : hour / 12;
                hour = half * 12 + value % 12;
            }
            case MINUTE -> minute = value;
            case SECOND -> second = value;
            case DAY_PERIOD -> hour = (hour == UNSET ? 0 : hour % 12) + (value == 0 ? 0 : 12);
            default -> {
                return;
            }
        }
        // A day that overshot the month it is now in follows the month rather than emptying the
        // field: 31 January stepped to February is 28, which is what every date editor does.
        if ((field == DatePattern.Field.MONTH || field == DatePattern.Field.YEAR)
                && day != UNSET) {
            day = Math.min(day, daysInCurrentMonth());
        }
        lastMoveWasTime = isTimeField(field);
        rebuildValue();
        invalidate();
        notifyChange(Change.of(Change.Aspect.VALUE, origin));
        refreshValidity(origin);
    }

    private static boolean isTimeField(DatePattern.Field field) {
        return switch (field) {
            case HOUR12, HOUR24, MINUTE, SECOND, DAY_PERIOD -> true;
            default -> false;
        };
    }

    /** Up and Down, rolling over the segment's own bounds. */
    private void step(int delta) {
        DatePattern.FieldPart part = focusedField();
        if (part == null) {
            return;
        }
        DatePattern.Field field = part.field();
        int min = segmentMin(field);
        int max = segmentMax(field);
        int current = segmentValue(field);
        int next;
        if (current == UNSET) {
            // The first press on an empty segment, up or down alike, lands somewhere sensible
            // rather than at the minimum: today's own value by the widget's clock, which is what
            // the person is usually near (kept as the rule on 2026-09-14, decision 16).
            next = defaultFor(field);
        } else {
            int span = max - min + 1;
            next = min + Math.floorMod(current - min + delta, span);
        }
        typedDigits = 0;
        writeSegment(field, next, Change.Origin.USER);
    }

    /**
     * Where this field reads today and now from, for the one thing it asks them: what an empty
     * segment becomes on its first step. Default {@code null}, the system clock in the default
     * zone read at each call, as {@link LocalDate#now()} and {@link LocalTime#now()} are.
     *
     * @param newClock the clock, or {@code null} for the system's
     * @return this
     */
    public DateField setClock(Clock newClock) {
        Ui.checkUiThread();
        clock = newClock;
        return this;
    }

    /** @return how many years back the window for a two-digit year starts; 80 unless changed */
    public int twoDigitYearWindow() {
        return twoDigitYearWindow;
    }

    /**
     * Which century a two-digit year means (decision 57, 2026-09-14). A year written with two
     * digits &mdash; pasted as {@code 31/12/26}, or typed as {@code 26} and left with a Right
     * or a Home &mdash; resolves into the hundred years that start {@code yearsBack} years
     * before today by the field's clock: by default 80 back and 19 ahead, so in 2026 {@code 26}
     * is 2026 and {@code 85} is 1985. A calendar whose years carry their era (Reiwa 8) is not
     * windowed: there a two-digit year is the whole year.
     *
     * <p>{@link #REFUSE_TWO_DIGIT_YEARS} turns the guess off: a pasted two-digit year is left
     * blank and the field stays incomplete, and a typed one stays exactly what was typed.
     *
     * @param yearsBack how far back the window starts, {@code 0} to {@code 99}, or
     *                  {@link #REFUSE_TWO_DIGIT_YEARS}
     * @return this
     */
    public DateField setTwoDigitYearWindow(int yearsBack) {
        Ui.checkUiThread();
        if (yearsBack != REFUSE_TWO_DIGIT_YEARS && (yearsBack < 0 || yearsBack > 99)) {
            throw new IllegalArgumentException("a two-digit year window is 0..99 years back, or "
                    + "REFUSE_TWO_DIGIT_YEARS; got " + yearsBack);
        }
        twoDigitYearWindow = yearsBack;
        return this;
    }

    /**
     * The year a two-digit one stands for, inside the window, or {@link #UNSET} when guessing is
     * off. The window is {@code [today - yearsBack, today - yearsBack + 99]}.
     */
    private int resolveTwoDigitYear(int twoDigits) {
        if (twoDigitYearWindow == REFUSE_TWO_DIGIT_YEARS) {
            return UNSET;
        }
        int base = today().getYear() - twoDigitYearWindow;
        int candidate = Math.floorDiv(base, 100) * 100 + twoDigits;
        return candidate < base ? candidate + 100 : candidate;
    }

    /** What an empty segment becomes on its first step: today's, which is the nearest guess. */
    private int defaultFor(DatePattern.Field field) {
        LocalDate today = clock == null ? LocalDate.now() : LocalDate.now(clock);
        ChronoLocalDate drawn = CalendarChronology.date(chronology(), today);
        LocalTime now = clock == null ? LocalTime.now() : LocalTime.now(clock);
        return switch (field) {
            case YEAR -> drawn == null ? today.getYear() : drawn.get(ChronoField.YEAR_OF_ERA);
            case MONTH -> drawn == null ? today.getMonthValue() : drawn.get(ChronoField.MONTH_OF_YEAR);
            case DAY -> drawn == null ? today.getDayOfMonth() : drawn.get(ChronoField.DAY_OF_MONTH);
            case HOUR24, HOUR12 -> now.getHour();
            case MINUTE -> now.getMinute();
            case SECOND -> now.getSecond();
            case DAY_PERIOD -> now.getHour() / 12;
            default -> 0;
        };
    }

    /**
     * A digit typed into the focused segment. Two digits fill a day, a month, an hour, a minute or
     * a second and four fill a year, and the segment rolls on to the next as soon as no further
     * digit could fit &mdash; which is what makes a whole date typable as a run of digits.
     */
    private void typeDigit(int digit) {
        DatePattern.FieldPart part = focusedField();
        if (part == null || !part.field().numeric()) {
            return;
        }
        DatePattern.Field field = part.field();
        int width = field == DatePattern.Field.YEAR ? (eraCalendar() ? 3 : 4) : 2;
        int current = segmentValue(field);
        int next = typedDigits == 0 || current == UNSET ? digit : current * 10 + digit;
        if (next > segmentMax(field) && typedDigits > 0) {
            next = digit; // the run overflowed: this digit starts the segment again
            typedDigits = 0;
        }
        typedDigits++;
        int max = segmentMax(field);
        if (next > max) {
            next = max;
        }
        if (next < segmentMin(field) && typedDigits >= width) {
            next = segmentMin(field);
        }
        writeSegment(field, next, Change.Origin.USER);
        // On to the next segment when this one is full, or when no further digit could fit in it:
        // a 3 typed into a two-digit day cannot become 3x for any x, so waiting for a second digit
        // would make the field feel stuck.
        if (typedDigits >= width || next * 10 > max) {
            moveSlot(1, true);
        }
    }

    /** AM/PM by keystroke as well as by arrow: the two letters people actually type. */
    private void typeDayPeriod(int codepoint) {
        DatePattern.FieldPart part = focusedField();
        if (part == null || part.field() != DatePattern.Field.DAY_PERIOD) {
            return;
        }
        int lower = Character.toLowerCase(codepoint);
        if (lower == 'a') {
            writeSegment(DatePattern.Field.DAY_PERIOD, 0, Change.Origin.USER);
        } else if (lower == 'p') {
            writeSegment(DatePattern.Field.DAY_PERIOD, 1, Change.Origin.USER);
        }
    }

    private void clearSegment() {
        DatePattern.FieldPart part = focusedField();
        if (part == null) {
            return;
        }
        switch (part.field()) {
            case YEAR -> year = UNSET;
            case MONTH -> month = UNSET;
            case DAY -> day = UNSET;
            case HOUR12, HOUR24, DAY_PERIOD -> hour = UNSET;
            case MINUTE -> minute = UNSET;
            case SECOND -> second = UNSET;
            default -> {
                return;
            }
        }
        typedDigits = 0;
        lastMoveWasTime = isTimeField(part.field());
        rebuildValue();
        invalidate();
        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.USER));
        refreshValidity(Change.Origin.USER);
    }

    /**
     * Moves the caret between segments.
     *
     * @param delta -1 for the previous segment, +1 for the next, in <b>reading</b> order
     * @param quiet whether to skip the announcement, for the roll-on that follows a typed digit:
     *              the digit has already announced, and a second event for the caret following it
     *              would tell a reader the caret moved on its own
     */
    private void moveSlot(int delta, boolean quiet) {
        ensureParts();
        if (editable.length == 0) {
            return;
        }
        int next = Math.max(0, Math.min(editable.length - 1, focusedSlot + delta));
        if (next == focusedSlot) {
            return;
        }
        commitTypedYear();
        focusedSlot = next;
        typedDigits = 0;
        invalidate();
        if (!quiet) {
            notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
        }
    }

    /** @return which segment the caret is in, counted over the editable segments in reading order */
    public int focusedSegment() {
        return focusedSlot;
    }

    /** The picker's, not an application's: see {@link #chrome}. */
    void setChrome(boolean paintOwnBox) {
        chrome = paintOwnBox;
        invalidate();
    }

    /** The picker's, not an application's: see {@link #keyDelegate}. */
    void setKeyDelegate(java.util.function.Consumer<KeyEvent> delegate) {
        keyDelegate = delegate;
    }

    /** The picker's, not an application's: see {@link #charDelegate}. */
    void setCharDelegate(java.util.function.Consumer<CharEvent> delegate) {
        charDelegate = delegate;
    }

    /** The picker's, not an application's: see {@link #keyboardActive}. */
    void setKeyboardActive(boolean active) {
        if (keyboardActive == active) {
            return;
        }
        keyboardActive = active;
        typedDigits = 0;
        focusFade.to(active || isFocused() ? 1 : 0);
        invalidate();
    }

    /** Whether the caret is drawn and published: the field holds the focus, or a picker aims here. */
    private boolean caretShown() {
        return isFocused() || keyboardActive;
    }

    // ------------------------------------------------------------------ parsing a whole string

    /**
     * Reads a whole date out of text: what a paste goes through.
     *
     * <p>Three attempts, cheapest first: the ISO form a spreadsheet or a database hands over, the
     * language's own short and medium forms, and finally the runs of digits in the text mapped onto
     * the segments in the order the pattern puts them. The last one is what understands
     * {@code 31122026} and {@code 31-12-2026} alike, and it is deliberately last: it cannot tell a
     * day from a month on its own and must not be given the chance while a real parser might
     * succeed.
     *
     * @param text what was pasted
     * @return whether anything was read
     */
    private boolean parseInto(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = I18n.toAsciiDigits(text.trim());
        if (startsAtYear) {
            Parsed parsed = parseDate(trimmed);
            if (parsed != null) {
                applyDate(parsed.date());
                if (parsed.yearUnknown()) {
                    year = UNSET; // a two-digit year with the guess off: blank, and incomplete
                    rebuildValue();
                }
                if (hasTime()) {
                    LocalTime clock = parseTime(trimmed);
                    if (clock != null) {
                        applyTime(clock);
                    }
                }
                return true;
            }
        }
        if (hasTime()) {
            LocalTime clock = parseTime(trimmed);
            if (clock != null) {
                applyTime(clock);
                return true;
            }
        }
        return startsAtYear && parseByDigitRuns(trimmed);
    }

    /**
     * A date read out of text, and whether its year was written with two digits and the guess
     * is off &mdash; in which case the day and the month are taken and the year is left blank.
     */
    private record Parsed(LocalDate date, boolean yearUnknown) {
    }

    private Parsed parseDate(String text) {
        try {
            return new Parsed(LocalDate.parse(text), false);
        } catch (DateTimeException ignored) {
            // Not the ISO form; the language's own is next.
        }
        for (java.time.format.FormatStyle style : new java.time.format.FormatStyle[]{
                java.time.format.FormatStyle.SHORT, java.time.format.FormatStyle.MEDIUM}) {
            try {
                LocalDate parsed = LocalDate.parse(text, DateTimeFormatter.ofLocalizedDate(style)
                        .withLocale(locale()).withChronology(chronology()));
                // The language's short pattern is 'dd/MM/y' in Portuguese, English and French,
                // and 'y' parses "26" as the year 26: what was pasted meant this century. A
                // year in the twenties with no four-digit run anywhere in the text is a
                // two-digit year and goes through the window (decision 57).
                if (parsed.getYear() >= 0 && parsed.getYear() < 100 && !eraCalendar()
                        && !java.util.regex.Pattern.compile("\\d{3,}").matcher(text).find()) {
                    int resolved = resolveTwoDigitYear(parsed.getYear());
                    return resolved == UNSET ? new Parsed(parsed, true)
                            : new Parsed(parsed.withYear(resolved), false);
                }
                return new Parsed(parsed, false);
            } catch (DateTimeException ignored) {
                // Try the next style, then fall through to the digit runs.
            }
        }
        return null;
    }

    private LocalTime parseTime(String text) {
        java.util.regex.Matcher clock =
                java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?").matcher(text);
        if (!clock.find()) {
            return null;
        }
        try {
            int h = Integer.parseInt(clock.group(1));
            int m = Integer.parseInt(clock.group(2));
            int s = clock.group(3) == null ? 0 : Integer.parseInt(clock.group(3));
            // A 12-hour string carrying a period: the two letters decide the half of the day.
            String lower = text.toLowerCase(Locale.ROOT);
            if (h <= 12 && lower.contains("pm") && h < 12) {
                h += 12;
            } else if (h == 12 && lower.contains("am")) {
                h = 0;
            }
            return LocalTime.of(h, m, s);
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    /**
     * The last resort: the digit runs of the text, in order, poured into the date segments in the
     * order this language writes them. A run of eight digits with no separators is split by the
     * segments' own widths.
     *
     * <p>The runs stay strings until each is known to be short enough to be a number
     * (DATES-NEW-9, 2026-09-14): {@code Integer.parseInt} over the whole of a pasted account
     * number threw out of the key handler, and a leading zero turned into a seven-digit run
     * nothing matched. A month or a day outside its range refuses the whole paste rather than
     * writing 13 into the month, and a two-digit year goes through the window.
     */
    private boolean parseByDigitRuns(String text) {
        List<String> runs = new ArrayList<>();
        java.util.regex.Matcher digits = java.util.regex.Pattern.compile("\\d+").matcher(text);
        while (digits.find()) {
            runs.add(digits.group());
        }
        List<DatePattern.Field> order = new ArrayList<>();
        for (int index : editable) {
            DatePattern.Field field = ((DatePattern.FieldPart) parts.get(index)).field();
            if (field == DatePattern.Field.YEAR || field == DatePattern.Field.MONTH
                    || field == DatePattern.Field.DAY) {
                order.add(field);
            }
        }
        if (order.isEmpty()) {
            return false;
        }
        if (runs.size() == 1 && order.size() > 1) {
            // One long run: split it by the widths the segments are drawn at, with the year
            // four digits wide or two.
            String run = runs.get(0);
            int full = 4 + 2 * (order.size() - 1);
            if (run.length() != full && run.length() != full - 2) {
                return false;
            }
            int yearWidth = run.length() == full ? 4 : 2;
            runs.clear();
            int at = 0;
            for (DatePattern.Field field : order) {
                int width = field == DatePattern.Field.YEAR ? yearWidth : 2;
                runs.add(run.substring(at, at + width));
                at += width;
            }
        }
        if (runs.size() < order.size()) {
            return false;
        }
        int y = UNSET;
        int m = UNSET;
        int d = UNSET;
        boolean yearUnknown = false;
        for (int i = 0; i < order.size(); i++) {
            String run = runs.get(i);
            if (run.length() > 4) {
                return false; // not a date's digit: an account number, a phone number
            }
            int value = Integer.parseInt(run);
            switch (order.get(i)) {
                case YEAR -> {
                    if (run.length() <= 2 && !eraCalendar()) {
                        int resolved = resolveTwoDigitYear(value);
                        yearUnknown = resolved == UNSET;
                        y = resolved;
                    } else {
                        y = value;
                    }
                }
                case MONTH -> {
                    if (value < 1 || value > 12) {
                        return false;
                    }
                    m = value;
                }
                case DAY -> {
                    if (value < 1 || value > 31) {
                        return false;
                    }
                    d = value;
                }
                default -> {
                }
            }
        }
        valueRevision++;
        year = yearUnknown ? UNSET : y;
        month = m;
        day = d;
        rebuildValue();
        return true;
    }

    // ------------------------------------------------------------------ measure and paint

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        ensureParts();
        TextMetrics metrics = textRuler().measure(widestForm.isEmpty() ? "Hg" : widestForm, t.body());
        return constraints.constrain(metrics.width() + 2 * t.fieldPadH(),
                t.resolvedHeight(metrics.lineHeight()));
    }

    @Override
    protected float baselineOffset() {
        TextMetrics metrics = textRuler().measure("Hg", Theme.current().tokensFor(this).body());
        return (height() - metrics.height()) / 2 + metrics.ascent();
    }

    @Override
    protected float paintOutset() {
        return Strokes.FOCUS_RING_OUTSET;
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        ensureParts();
        boolean enabled = isEnabled();
        float w = width();
        float h = height();
        float focus = focusFade.value();
        float radius = t.radiusMedium();

        if (chrome) {
            canvas.fillRoundRect(0, 0, w, h, radius, enabled ? theme.surface : theme.disabledFill);
        }
        canvas.save();
        canvas.clipRoundRect(RoundRect.of(0, 0, w, h, radius));
        paintSegments(canvas, theme, t, enabled, focus);
        canvas.restore();

        // The border carries the validity: a field holding something it cannot accept is outlined
        // in the danger colour whether or not it has focus, which is the one visual difference an
        // invalid field has and the reason the colour is not folded into the focus lerp. A field
        // inside a picker draws neither: the picker's own box carries both, over both ends of a
        // period at once, and two nested borders would read as two controls.
        if (!chrome) {
            return;
        }
        float half = Strokes.HALF_PIXEL_INSET;
        Color border = validity != null ? theme.danger : theme.outline.lerp(theme.focusRing, focus);
        canvas.drawRoundRect(half, half, w - 2 * half, h - 2 * half, radius,
                Strokes.BORDER + (Strokes.FOCUS_RING - Strokes.BORDER) * focus, border);
    }

    private void paintSegments(Canvas canvas, Theme theme, SizeTokens t, boolean enabled,
                               float focus) {
        Font font = t.body();
        TextRuler ruler = textRuler();
        TextMetrics fm = ruler.measure("Hg", font);
        float baseline = (height() - fm.height()) / 2 + fm.ascent();
        ShapedText.Direction neutral = neutralBase();

        // The run is composed left to right from the widths of its own pieces and then placed as a
        // whole: a date is a run of numbers and keeps its internal order in both directions, so
        // only its ORIGIN mirrors. The same reading Spinner's clock documents.
        float runWidth = 0;
        for (DatePattern.Part part : parts) {
            runWidth += pieceWidth(ruler, font, neutral, part);
        }
        float pad = t.fieldPadH();
        float x = isRightToLeft() ? Math.max(pad, width() - pad - runWidth) : pad;
        int slot = 0;
        Color ink = enabled ? theme.text : theme.disabledText;
        for (DatePattern.Part part : parts) {
            String text = part instanceof DatePattern.Literal literal
                    ? literal.text()
                    : segmentText((DatePattern.FieldPart) part);
            ShapedText line = ruler.shape(text, font, ShapedText.Direction.of(text, neutral));
            float pieceWidth = line.metrics().width();
            boolean isField = part instanceof DatePattern.FieldPart field && field.field().editable();
            if (isField) {
                if (focus > 0.001f && slot == focusedSlot) {
                    float padX = t.spinnerFieldPadX();
                    float inset = t.spinnerFieldInset();
                    canvas.fillRoundRect(x - padX, inset, pieceWidth + 2 * padX,
                            height() - 2 * inset, t.radiusSmall(),
                            theme.primary.withAlpha(0.20f * focus));
                }
                slot++;
            }
            boolean placeholder = isField && text.startsWith("-");
            canvas.drawText(line, x, baseline,
                    part instanceof DatePattern.Literal || placeholder ? theme.textMuted : ink);
            x += pieceWidth;
        }
    }

    private float pieceWidth(TextRuler ruler, Font font, ShapedText.Direction neutral,
                             DatePattern.Part part) {
        String text = part instanceof DatePattern.Literal literal
                ? literal.text()
                : segmentText((DatePattern.FieldPart) part);
        return ruler.shape(text, font, ShapedText.Direction.of(text, neutral)).metrics().width();
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void onFocusGained() {
        focusFade.to(1);
        typedDigits = 0;
        typedRun.setLength(0);
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(keyboardActive ? 1 : 0);
        // Leaving the field leaves the year segment too -- unless a picker is only moving the
        // focus onto its own in-scene overlay while the caret stays here, which is the one loss
        // of focus that is not a person moving on.
        if (!keyboardActive) {
            commitTypedYear();
        }
        typedDigits = 0;
        typedRun.setLength(0);
    }

    @Override
    protected boolean acceptsTextInput() {
        return true;
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        if (event.type() != MouseEvent.Type.CLICK || event.button() != Keys.MOUSE_LEFT
                || !isEnabled()) {
            if (event.type() == MouseEvent.Type.PRESS) {
                event.consume();
            }
            return;
        }
        event.consume();
        requestFocus();
        typedRun.setLength(0);
        int slot = slotAt(sceneToLocalX(event.x()));
        if (slot >= 0 && slot != focusedSlot) {
            commitTypedYear();
            focusedSlot = slot;
            typedDigits = 0;
            invalidate();
            notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
        }
    }

    /**
     * Which segment a click landed in, walking the same composed run the paint draws so that a
     * click and the segment it highlights cannot disagree. Falls back to the nearest end, because
     * a click on a separator is a click on the segment beside it rather than on nothing.
     */
    private int slotAt(float localX) {
        ensureParts();
        SizeTokens t = Theme.current().tokensFor(this);
        TextRuler ruler = textRuler();
        Font font = t.body();
        ShapedText.Direction neutral = neutralBase();
        float runWidth = 0;
        for (DatePattern.Part part : parts) {
            runWidth += pieceWidth(ruler, font, neutral, part);
        }
        float pad = t.fieldPadH();
        float x = isRightToLeft() ? Math.max(pad, width() - pad - runWidth) : pad;
        int slot = 0;
        int last = -1;
        for (DatePattern.Part part : parts) {
            float pieceWidth = pieceWidth(ruler, font, neutral, part);
            boolean isField = part instanceof DatePattern.FieldPart field && field.field().editable();
            if (isField) {
                if (localX < x + pieceWidth) {
                    return slot;
                }
                last = slot;
                slot++;
            }
            x += pieceWidth;
        }
        return last;
    }

    @Override
    protected void onCharTyped(CharEvent event) {
        if (!isEnabled()) {
            return;
        }
        // The picker first, as for the keys: while its popup's time row is the thing being typed
        // into, the digits are the row's and not this field's.
        if (charDelegate != null) {
            charDelegate.accept(event);
            if (event.isConsumed()) {
                return;
            }
        }
        int codepoint = event.codepoint();
        int digit = Character.digit(codepoint, 10);
        if (digit >= 0) {
            event.consume();
            typeDigit(digit);
            noteTyped(codepoint);
            return;
        }
        if (Character.isLetter(codepoint)) {
            event.consume();
            typedRun.setLength(0);
            typeDayPeriod(codepoint);
            return;
        }
        // A separator typed by somebody who types separators: move on rather than beep. Any
        // non-letter, non-digit character does, because the pattern's own separator is a slash in
        // one language, a full stop in another and an ideograph in a third.
        if (!Character.isWhitespace(codepoint)) {
            event.consume();
            moveSlot(1, false);
            noteTyped(codepoint);
        }
    }

    /**
     * Remembers a typed character and, once the run reads as an ISO date, commits that date as
     * a whole (ADR 042 &sect;3, settled typed-iso-run): {@code 2026-12-31} typed digit by digit
     * into a day-first field went segment by segment to 0001-02-20 and called it valid. The
     * segments wander while the run is being typed and are overwritten when it completes; a
     * key or a click starts the run over.
     */
    private void noteTyped(int codepoint) {
        if (!startsAtYear || !granularity.holds(Granularity.MONTH)) {
            typedRun.setLength(0);
            return;
        }
        if (codepoint != '-' && Character.digit(codepoint, 10) < 0) {
            typedRun.setLength(0);
            return;
        }
        typedRun.appendCodePoint(codepoint);
        if (typedRun.length() > 16) {
            typedRun.delete(0, typedRun.length() - 16);
        }
        java.util.regex.Matcher iso = java.util.regex.Pattern
                .compile(granularity.holds(Granularity.DAY) ? "\\d{4}-\\d{2}-\\d{2}$" : "\\d{4}-\\d{2}$")
                .matcher(typedRun);
        if (!iso.find()) {
            return;
        }
        String run = iso.group();
        typedRun.setLength(0);
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(granularity.holds(Granularity.DAY) ? run : run + "-01");
        } catch (DateTimeException e) {
            return; // 2026-13-45 is digits in an ISO shape, not a date; the segments stand
        }
        applyDate(parsed);
        focusedSlot = Math.max(0, editable.length - 1);
        typedDigits = 0;
        lastMoveWasTime = false;
        invalidate();
        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.USER));
        refreshValidity(Change.Origin.USER);
    }

    /**
     * A two-digit year typed and left (a Right, a Home, a separator, a click into another
     * segment, or the focus going elsewhere) resolves through the window (decision 57): "26"
     * left in the year is 2026, as it is on every desktop date field. Four digits are what was
     * meant; three are left alone too, and so is a year of era. With the guess off, what was
     * typed stays.
     */
    private void commitTypedYear() {
        DatePattern.FieldPart part = focusedField();
        if (part == null || part.field() != DatePattern.Field.YEAR || typedDigits == 0
                || typedDigits > 2 || year == UNSET || year >= 100 || eraCalendar()) {
            return;
        }
        int resolved = resolveTwoDigitYear(year);
        if (resolved == UNSET || resolved == year) {
            return;
        }
        year = resolved;
        typedDigits = 0;
        lastMoveWasTime = false;
        rebuildValue();
        invalidate();
        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.USER));
        refreshValidity(Change.Origin.USER);
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed() || !isEnabled()) {
            return;
        }
        // The picker first, if there is one: while its calendar is open the navigation keys belong
        // to the grid and the digits still belong here, and the only way to split them is to let
        // the owner take what it wants before this method claims the arrows.
        if (keyDelegate != null) {
            keyDelegate.accept(event);
            if (event.isConsumed()) {
                return;
            }
        }
        typedRun.setLength(0); // a key is not part of a typed run
        boolean command = (event.modifiers() & (Keys.MOD_CONTROL | Keys.MOD_SUPER)) != 0;
        if (command) {
            switch (event.key()) {
                case Keys.C -> {
                    clipboard().set(text());
                    event.consume();
                }
                case Keys.V -> {
                    String pasted = clipboard().get();
                    if (parseInto(pasted)) {
                        lastMoveWasTime = !startsAtYear;
                        invalidate();
                        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.USER));
                        refreshValidity(Change.Origin.USER);
                    }
                    event.consume();
                }
                default -> {
                }
            }
            return;
        }
        switch (event.key()) {
            case Keys.UP -> {
                step(1);
                event.consume();
            }
            case Keys.DOWN -> {
                step(-1);
                event.consume();
            }
            // The segments are a run of numbers whose order does not mirror, but the CARET moves
            // along the screen: Left goes to the segment drawn on the left, which is the previous
            // one in both directions because the run itself did not turn over.
            case Keys.LEFT -> {
                moveSlot(-1, false);
                event.consume();
            }
            case Keys.RIGHT -> {
                moveSlot(1, false);
                event.consume();
            }
            case Keys.HOME -> {
                moveSlot(-editable.length, false);
                event.consume();
            }
            case Keys.END -> {
                moveSlot(editable.length, false);
                event.consume();
            }
            case Keys.DELETE, Keys.BACKSPACE -> {
                clearSegment();
                event.consume();
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ accessibility

    /**
     * A group of spin buttons, one per editable segment (ADR 042 &sect;8).
     *
     * <p>The group carries the whole value as its value text and the validity message when it has
     * one; each segment carries its own name, its own bounds, its own value against its own range,
     * and the two step verbs, which reach the same private path Up and Down reach.
     *
     * <p>The group names itself with nothing: what a date field is for is the question the form
     * asks, which only the application has, and this is a public widget an application names with
     * {@code setAccessibleName}, a bound caption or a tooltip.
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        ensureParts();
        SizeTokens t = Theme.current().tokensFor(this);
        a.role(Accessible.Role.GROUP);
        // No value facet on the group, and this is a correction the tests forced rather than a
        // choice made up front. The first cut published the whole date here as a valueText, which
        // reaches nobody: valueText gives an EXISTING value a display form, and a node with no
        // value has no facet for it to land on. The fix is not to invent a number for the group --
        // a group has none, and a min, a max and a step over a date would be three lies for a
        // bridge to publish. The segments carry the values, each over its own real range, and an
        // application that wants the whole date as a string has DateField.text().
        if (validity != null) {
            a.state(Accessible.State.INVALID, true);
            a.description(validity);
        }

        TextRuler ruler = textRuler();
        Font font = t.body();
        ShapedText.Direction neutral = neutralBase();
        float runWidth = 0;
        for (DatePattern.Part part : parts) {
            runWidth += pieceWidth(ruler, font, neutral, part);
        }
        float pad = t.fieldPadH();
        float x = isRightToLeft() ? Math.max(pad, width() - pad - runWidth) : pad;
        int slot = 0;
        for (DatePattern.Part part : parts) {
            float pieceWidth = pieceWidth(ruler, font, neutral, part);
            if (part instanceof DatePattern.FieldPart field && field.field().editable()) {
                a.child(slot);
                a.bounds(x, 0, pieceWidth, height());
                a.role(Accessible.Role.SPIN_BUTTON);
                a.name(nameOf(field.field()), Accessible.NameFrom.CONTENT);
                int value = segmentValue(field.field());
                if (value == UNSET) {
                    // A segment nobody has filled says so (decisions 16 and 53, 2026-09-14): the
                    // range stands, the number is absent, and the spoken text is a word rather
                    // than the dashes that are drawn -- "--" read aloud is nothing. It published
                    // its minimum as if typed until the facet could say empty.
                    a.emptyValue(segmentMin(field.field()), segmentMax(field.field()), 1, false);
                    a.valueText(DateStrings.SEGMENT_EMPTY.get(), valueRevision);
                } else {
                    a.value(value, segmentMin(field.field()), segmentMax(field.field()), 1);
                    a.valueText(field.field() == DatePattern.Field.YEAR
                            ? yearSpoken(field) : segmentText(field), valueRevision);
                }
                a.action(Accessible.Action.INCREMENT, Accessible.Action.DECREMENT);
                if (slot == focusedSlot && caretShown()) {
                    a.state(Accessible.State.ACTIVE);
                }
                a.endChild();
                slot++;
            }
            x += pieceWidth;
        }
    }

    /**
     * What a reader is told the year segment holds: the year as drawn, or, in a calendar whose
     * years need their era, the era with it &mdash; "令和8", "民國115" (decision 38, 2026-09-14).
     * The era is drawn as its own read-only piece of the pattern ("R8/9/9") and is no node of its
     * own; the year's spoken text is where it reaches a reader, so drawn and spoken agree without
     * a string of this toolkit's. Built from the segments rather than the value, so a year typed
     * ahead of its month is spoken with its era too.
     */
    private String yearSpoken(DatePattern.FieldPart field) {
        if (!eraCalendar() || year == UNSET) {
            return segmentText(field);
        }
        Chronology chronology = chronology();
        try {
            Era era = eraForBuilding(chronology);
            ChronoLocalDate first = era == null
                    ? chronology.date(year, 1, 1) : chronology.date(era, year, 1, 1);
            return CalendarChronology.eraYear(chronology, first, locale(), false);
        } catch (DateTimeException | ArithmeticException e) {
            return segmentText(field);
        }
    }

    /** Bumped by every value move, so a walk can decide the displayed text is unchanged cheaply. */
    private long valueRevision;

    private static I18nString nameOf(DatePattern.Field field) {
        return switch (field) {
            case YEAR -> DateStrings.SEGMENT_YEAR;
            case MONTH -> DateStrings.SEGMENT_MONTH;
            case DAY -> DateStrings.SEGMENT_DAY;
            case HOUR12, HOUR24 -> DateStrings.SEGMENT_HOUR;
            case MINUTE -> DateStrings.SEGMENT_MINUTE;
            case SECOND -> DateStrings.SEGMENT_SECOND;
            case DAY_PERIOD -> DateStrings.SEGMENT_DAY_PERIOD;
            default -> DateStrings.SEGMENT_ERA;
        };
    }

    /**
     * A segment's step or set, through the same path the keyboard takes: the verbs are the two keys
     * they stand for, so an assistive technology rolls over the segment's bounds exactly as Up and
     * Down do, and reaches the application's handler through the same {@code USER} seam.
     */
    @Override
    protected boolean onSyntheticAction(long key, Accessible.Action action,
                                        Accessible.Argument arg) {
        ensureParts();
        if (key < 0 || key >= editable.length || !isEnabled()) {
            return false;
        }
        int previous = focusedSlot;
        focusedSlot = (int) key;
        try {
            switch (action) {
                case INCREMENT -> step(1);
                case DECREMENT -> step(-1);
                case SET_VALUE -> {
                    double asked = Accessible.Argument.finiteValueOf(arg);
                    if (Double.isNaN(asked)) {
                        return false;
                    }
                    DatePattern.FieldPart part = focusedField();
                    if (part == null) {
                        return false;
                    }
                    int clamped = (int) Math.max(segmentMin(part.field()),
                            Math.min(segmentMax(part.field()), Math.rint(asked)));
                    writeSegment(part.field(), clamped, Change.Origin.USER);
                }
                default -> {
                    focusedSlot = previous;
                    return false;
                }
            }
        } finally {
            invalidate();
        }
        return true;
    }
}
