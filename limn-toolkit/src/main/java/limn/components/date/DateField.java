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
 * <p>Four shapes come out of three constructors, and the picker adds the calendar to any of them:
 *
 * <pre>{@code
 * new DateField()          // 31/12/2026   -- a date
 * DateField.ofTime()       // 14:30        -- a time of day
 * DateField.ofDateTime()   // 31/12/2026 14:30
 * new DatePicker()         // the first, with a calendar to pick from
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

    /** No value in a segment. Not -1: an hour of zero and a minute of zero are ordinary. */
    private static final int UNSET = Integer.MIN_VALUE;

    private final boolean hasDate;
    private final boolean hasTime;
    private boolean showSeconds;

    private Chronology declaredChronology;
    /** Where today and now are read from for an empty segment's first step; see setClock. */
    private Clock clock;

    // ---- the pattern, taken apart, rebuilt only when the language or the calendar moves --------

    private final LanguageWitness patternLanguage = new LanguageWitness();
    private Chronology patternChronology;
    private boolean patternSeconds;
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

    /** A field that edits a date. */
    public DateField() {
        this(true, false);
    }

    private DateField(boolean date, boolean time) {
        DateStrings.ensureRegistered();
        this.hasDate = date;
        this.hasTime = time;
        setFocusable(true);
        setCursor(Cursor.TEXT);
    }

    /**
     * A field that edits a time of day, on the clock the language keeps: twenty-four hours in
     * Portuguese and German, twelve and a day period in English and Korean.
     *
     * @return the field
     */
    public static DateField ofTime() {
        return new DateField(false, true);
    }

    /**
     * A field that edits a date and a time of day, in that order and with the language's own
     * separators between them.
     *
     * @return the field
     */
    public static DateField ofDateTime() {
        return new DateField(true, true);
    }

    /** @return whether this field carries date segments */
    public boolean hasDate() {
        return hasDate;
    }

    /** @return whether this field carries time segments */
    public boolean hasTime() {
        return hasTime;
    }

    /** @return whether the seconds segment is shown */
    public boolean showsSeconds() {
        return showSeconds;
    }

    /**
     * Adds or removes the seconds segment. A field with no time part ignores this.
     *
     * @param show whether to show seconds
     * @return this
     */
    public DateField setShowSeconds(boolean show) {
        Ui.checkUiThread();
        if (showSeconds == show) {
            return this;
        }
        showSeconds = show;
        if (!show) {
            second = UNSET;
        }
        markNeedsLayout();
        return this;
    }

    // ------------------------------------------------------------------ the value

    /**
     * @return the date, or {@code null} if this field has no date part or its segments are not all
     *         filled. A date outside the bounds is still answered here: the field holds what was
     *         typed and says separately that it is not acceptable (see {@link #isValid()})
     */
    public LocalDate date() {
        return dateValue;
    }

    /** @return the time of day, or {@code null} if this field has no time part or it is incomplete */
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
        if (Objects.equals(dateValue, date)) {
            return this;
        }
        applyDate(date);
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
        if (Objects.equals(timeValue, time)) {
            return this;
        }
        applyTime(time);
        lastMoveWasTime = true;
        invalidate();
        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.CODE));
        refreshValidity(Change.Origin.CODE);
        return this;
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
        if (Objects.equals(dateValue, date) && Objects.equals(timeValue, time)) {
            return this;
        }
        applyDate(date);
        applyTime(time);
        invalidate();
        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.CODE));
        refreshValidity(Change.Origin.CODE);
        return this;
    }

    /** Clears every segment. */
    public DateField clear() {
        return setDateTime(null);
    }

    private void applyDate(LocalDate date) {
        valueRevision++;
        dateValue = date;
        if (!hasDate || date == null) {
            year = month = day = UNSET;
            return;
        }
        ChronoLocalDate drawn = CalendarChronology.date(chronology(), date);
        if (drawn == null) {
            // A date the drawn calendar cannot reach. The ISO value stands, and the segments show
            // what ISO says, which is what the fallback in CalendarChronology means everywhere.
            year = date.getYear();
            month = date.getMonthValue();
            day = date.getDayOfMonth();
            return;
        }
        year = drawn.get(ChronoField.YEAR_OF_ERA);
        month = drawn.get(ChronoField.MONTH_OF_YEAR);
        day = drawn.get(ChronoField.DAY_OF_MONTH);
    }

    private void applyTime(LocalTime time) {
        valueRevision++;
        timeValue = time;
        if (!hasTime || time == null) {
            hour = minute = second = UNSET;
            return;
        }
        hour = time.getHour();
        minute = time.getMinute();
        second = time.getSecond();
    }

    /**
     * Rebuilds the two values from the segments after one of them was edited: the date is a date
     * only when every one of its segments is filled and the three together name a day that exists.
     */
    private void rebuildValue() {
        valueRevision++;
        LocalDate date = null;
        if (hasDate && year != UNSET && month != UNSET && day != UNSET) {
            date = buildDate(year, month, day);
        }
        LocalTime time = null;
        if (hasTime && hour != UNSET && minute != UNSET && (!showSeconds || second != UNSET)) {
            time = LocalTime.of(hour, minute, showSeconds && second != UNSET ? second : 0);
        }
        dateValue = date;
        timeValue = time;
    }

    /**
     * Three segment values as an ISO date, clamping the day to the month's length: a person who
     * types 31 into a February is typing the last day of it, and refusing the whole date because
     * one segment overshot is how a field ends up empty for reasons nobody can see.
     *
     * @return the date, or {@code null} if the calendar being drawn has no such day at all
     */
    private LocalDate buildDate(int yearOfEra, int monthOfYear, int dayOfMonth) {
        Chronology chronology = chronology();
        try {
            Era era = eraForBuilding(chronology);
            ChronoLocalDate first = era == null
                    ? chronology.date(yearOfEra, monthOfYear, 1)
                    : chronology.date(era, yearOfEra, monthOfYear, 1);
            int clamped = Math.min(dayOfMonth, first.lengthOfMonth());
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
            try {
                reference = chronology.dateNow();
            } catch (DateTimeException e) {
                return null;
            }
        }
        return reference.getEra();
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
        if (hasDate && dateValue == null || hasTime && timeValue == null) {
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
     * Rebuilds the segment list when the language, the calendar or the seconds flag moved.
     *
     * <p>The date pattern and the time pattern are fetched separately and joined with a space,
     * never carved out of one combined pattern: carving would leave the joining words of a dozen
     * languages stranded, and which of them to drop is a guess in every language nobody in the room
     * reads (ADR 042 &sect;3).
     */
    private void ensureParts() {
        Locale locale = locale();
        Chronology chronology = chronology();
        boolean languageMoved = patternLanguage.moved();
        if (!languageMoved && parts != null && !parts.isEmpty()
                && Objects.equals(patternChronology, chronology)
                && patternSeconds == showSeconds) {
            return;
        }
        patternChronology = chronology;
        patternSeconds = showSeconds;
        List<DatePattern.Part> built = new ArrayList<>();
        if (hasDate) {
            built.addAll(DatePattern.shortDate(chronology, locale));
        }
        if (hasTime) {
            if (hasDate) {
                built.add(new DatePattern.Literal(" "));
            }
            built.addAll(DatePattern.time(showSeconds, locale));
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
            case YEAR -> "8888";
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
            case YEAR -> year == UNSET ? "----" : pad(year, Math.max(4, field.width()));
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
            // stops at 1600. Asked of the chronology rather than assumed.
            case YEAR -> (int) Math.min(9999, chronology().range(ChronoField.YEAR_OF_ERA).getMaximum());
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
            // The first press on an empty segment lands somewhere sensible rather than at the
            // minimum: today's own value, which is what the person is usually near.
            next = delta > 0 ? defaultFor(field) : defaultFor(field);
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
        int width = field == DatePattern.Field.YEAR ? 4 : 2;
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
        if (hasDate) {
            LocalDate parsed = parseDate(trimmed);
            if (parsed != null) {
                applyDate(parsed);
                if (hasTime) {
                    LocalTime clock = parseTime(trimmed);
                    if (clock != null) {
                        applyTime(clock);
                    }
                }
                return true;
            }
        }
        if (hasTime) {
            LocalTime clock = parseTime(trimmed);
            if (clock != null) {
                applyTime(clock);
                return true;
            }
        }
        return hasDate && parseByDigitRuns(trimmed);
    }

    private LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeException ignored) {
            // Not the ISO form; the language's own is next.
        }
        for (java.time.format.FormatStyle style : new java.time.format.FormatStyle[]{
                java.time.format.FormatStyle.SHORT, java.time.format.FormatStyle.MEDIUM}) {
            try {
                return LocalDate.parse(text, DateTimeFormatter.ofLocalizedDate(style)
                        .withLocale(locale()).withChronology(chronology()));
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
     */
    private boolean parseByDigitRuns(String text) {
        List<Integer> numbers = new ArrayList<>();
        java.util.regex.Matcher digits = java.util.regex.Pattern.compile("\\d+").matcher(text);
        while (digits.find()) {
            numbers.add(Integer.parseInt(digits.group()));
        }
        List<DatePattern.Field> order = new ArrayList<>();
        for (int index : editable) {
            DatePattern.Field field = ((DatePattern.FieldPart) parts.get(index)).field();
            if (field == DatePattern.Field.YEAR || field == DatePattern.Field.MONTH
                    || field == DatePattern.Field.DAY) {
                order.add(field);
            }
        }
        if (numbers.size() == 1 && order.size() == 3) {
            // One long run: split it by the widths the segments are drawn at.
            String run = Integer.toString(numbers.get(0));
            if (run.length() != 8 && run.length() != 6) {
                return false;
            }
            numbers.clear();
            int at = 0;
            for (DatePattern.Field field : order) {
                int width = field == DatePattern.Field.YEAR ? run.length() - 4 : 2;
                if (field == DatePattern.Field.YEAR) {
                    width = run.length() == 8 ? 4 : 2;
                }
                numbers.add(Integer.parseInt(run.substring(at, at + width)));
                at += width;
            }
        }
        if (numbers.size() < order.size()) {
            return false;
        }
        for (int i = 0; i < order.size(); i++) {
            int value = numbers.get(i);
            switch (order.get(i)) {
                case YEAR -> year = value < 100 ? 2000 + value : value;
                case MONTH -> month = value;
                case DAY -> day = value;
                default -> {
                }
            }
        }
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
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
        typedDigits = 0;
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
        int slot = slotAt(sceneToLocalX(event.x()));
        if (slot >= 0 && slot != focusedSlot) {
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
        int codepoint = event.codepoint();
        int digit = Character.digit(codepoint, 10);
        if (digit >= 0) {
            event.consume();
            typeDigit(digit);
            return;
        }
        if (Character.isLetter(codepoint)) {
            event.consume();
            typeDayPeriod(codepoint);
            return;
        }
        // A separator typed by somebody who types separators: move on rather than beep. Any
        // non-letter, non-digit character does, because the pattern's own separator is a slash in
        // one language, a full stop in another and an ideograph in a third.
        if (!Character.isWhitespace(codepoint)) {
            event.consume();
            moveSlot(1, false);
        }
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
                        lastMoveWasTime = !hasDate;
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
                a.value(value == UNSET ? segmentMin(field.field()) : value,
                        segmentMin(field.field()), segmentMax(field.field()), 1);
                a.valueText(segmentText(field), valueRevision);
                a.action(Accessible.Action.INCREMENT, Accessible.Action.DECREMENT);
                if (slot == focusedSlot && isFocused()) {
                    a.state(Accessible.State.ACTIVE);
                }
                a.endChild();
                slot++;
            }
            x += pieceWidth;
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
