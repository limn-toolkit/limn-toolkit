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
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.i18n.I18n;
import limn.i18n.LanguageWitness;
import limn.input.Keys;
import limn.lang.Checks;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.Chronology;
import java.time.chrono.IsoChronology;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A month at a time: six weeks of seven days, a header that names the month and pages it, and
 * optionally a leading column of week numbers.
 *
 * <p>A widget in its own right and not a part of {@link DatePicker}, because a scheduling screen
 * wants a calendar sitting in a panel rather than hidden in a popup. The picker uses this one; so
 * can an application, with the same three lines.
 *
 * <pre>{@code
 * CalendarView calendar = new CalendarView()
 *         .setMinDate(LocalDate.now())
 *         .setDateFilter(day -> day.getDayOfWeek() != DayOfWeek.SATURDAY
 *                            && day.getDayOfWeek() != DayOfWeek.SUNDAY)
 *         .onSelect(day -> booking.setStart(day));
 * }</pre>
 *
 * <p><b>The value is ISO and the calendar drawn is the reader's</b> (ADR 042 &sect;1). Everything
 * this class hands out and takes in is a {@link LocalDate}; the month names, the year number and
 * the length of a month come from the chronology resolved for the widget's effective locale, which
 * is ISO for every language until one carries a {@code u-ca} extension or an application calls
 * {@link #setChronology}. A Thai user driving a Buddhist calendar picks a day out of a grid headed
 * with a year 543 greater, and the application stores the ISO date it asked for.
 *
 * <p><b>Always six rows.</b> A month needs four to six depending on where it starts, and a grid
 * that changed height as it paged would resize the popup under the pointer and move the button the
 * user was about to press. The leading and trailing cells are filled with the neighbouring months'
 * days, drawn muted; they are selectable, and picking one pages the grid to that month.
 *
 * <p><b>The keyboard cursor is not the selection.</b> Arrows move a cursor, Enter and Space commit
 * it. A grid where arrowing selected would fire a form's handler seven times crossing a week, and
 * would make a range impossible to build with the keyboard at all. Left and Right move by a day and
 * mirror with the layout; Up and Down move by a week and never mirror; Home and End are the first
 * and last day of the week; PageUp and PageDown page the month, and with Shift the year.
 *
 * <p><b>Bounds are enforced, not snapped</b> (ADR 042 &sect;5). A day outside
 * {@link #setMinDate}/{@link #setMaxDate}, or refused by {@link #setDateFilter}, is drawn disabled,
 * skipped by the cursor, refuses a click and is published without a select verb.
 *
 * <p><b>To a screen reader this is a table</b> (ADR 042 &sect;8): {@code TABLE} over {@code ROW}s of
 * {@code CELL}s under a row of {@code COLUMN_HEADER}s, which are the four roles ADR 041 mapped on
 * all three platforms. A day cell is named with the whole date and not the bare number, because a
 * cell heard on its own has to say what it is.
 */
public class CalendarView extends Widget {

    /** What a click does. */
    public enum SelectionMode {
        /** Nothing: a calendar that displays a month and is not an input. */
        NONE,
        /** One day. {@link #onSelect} is the handler. */
        SINGLE,
        /** A period, anchored by the first click and closed by the second. {@link #onSelectRange}. */
        RANGE
    }

    /** How many week rows are drawn, always. The class comment says why it is not variable. */
    private static final int WEEKS = 6;
    private static final int DAYS_IN_WEEK = 7;
    private static final int CELLS = WEEKS * DAYS_IN_WEEK;

    /**
     * The accessible key space, and it is one space rather than one per parent.
     *
     * <p>A synthetic child's key has only to be unique among its siblings for the tree to identify
     * it &mdash; the publish step interns the (owner, key) pair. But a verb performed on one
     * arrives at {@link #onSyntheticAction} carrying <b>only</b> the innermost key, so two nodes
     * under different parents that share a key are two nodes this widget cannot tell apart. The day
     * cells therefore take the non-negative half, one key per cell across the whole grid, and
     * everything that is not a day is negative and spaced apart from its neighbours by more than it
     * can ever have members. The cells are the only nodes here that carry a verb, and the flat key
     * is what makes {@code SELECT} land on the day it was asked for.
     */
    private static final long KEY_PREVIOUS = -1;
    private static final long KEY_NEXT = -2;
    private static final long KEY_HEADER = -3;
    /** One per column head, offset so it cannot meet a row or a week number. */
    private static final long KEY_HEAD_BASE = -10;
    /** One per week row. */
    private static final long KEY_ROW_BASE = -100;
    /** One per week-number cell. */
    private static final long KEY_WEEK_BASE = -200;

    private SelectionMode selectionMode = SelectionMode.SINGLE;

    /** Any day inside the month on show; normalized to its first day when the grid is rebuilt. */
    private LocalDate visibleMonth = LocalDate.now();
    private LocalDate selected;
    private DateRange selectedRange;
    /** The end a range is being built from, while it is being built; not published as a range. */
    private LocalDate rangeAnchor;
    /** What the range would be if the pointer or the cursor committed now. */
    private LocalDate rangePreview;
    /** Where the keyboard is. Never the selection, and never null once the widget has focus. */
    private LocalDate cursor;
    private LocalDate hover;

    private LocalDate minDate;
    private LocalDate maxDate;
    private Predicate<LocalDate> dateFilter;
    private Function<LocalDate, DayMark> dayMarks;
    private boolean showWeekNumbers;
    private Chronology declaredChronology;
    private DayOfWeek declaredFirstDayOfWeek;

    private Consumer<LocalDate> onSelect;
    private Consumer<DateRange> onSelectRange;

    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);

    /** Which paging button the pointer is over: -1 previous, +1 next, 0 neither. */
    private int pagingHover;
    /**
     * Whether the keyboard is aimed at this grid although the grid does not hold the focus.
     *
     * <p>The one state a calendar inside a {@link DatePicker} needs and a standalone one never
     * does. The toolkit's popup contract is that a popup does not take focus, so the picker's field
     * keeps it and forwards the navigation keys here; without this the grid would draw no cursor
     * and publish no active descendant while it was being driven, and a reader following the
     * arrows would hear nothing move.
     */
    private boolean keyboardActive;

    // ---- geometry, settled by the layout and read by the paint, the hit test and the tree ----

    private float headerH;
    private float weekdayH;
    private float cellW;
    private float cellH;
    private float weekColW;
    /** Physical left edge of the day columns; the week column sits before it in reading order. */
    private float gridX;
    private float gridY;

    // ---- the memo: what the grid is showing, rebuilt only when something it depends on moved ----

    private final LanguageWitness textLanguage = new LanguageWitness();
    private long textEpoch;
    private long gridStartEpoch = Long.MIN_VALUE;
    private long monthFirstEpoch;
    private long monthLastEpoch;
    private long previousFirstEpoch;
    private long nextFirstEpoch;
    private Chronology gridChronology = IsoChronology.INSTANCE;
    private DayOfWeek gridFirstDay = DayOfWeek.MONDAY;
    private String monthTitle = "";
    private final String[] dayText = new String[CELLS];
    private final String[] weekText = new String[WEEKS];
    private final String[] weekdayText = new String[DAYS_IN_WEEK];
    private final DayOfWeek[] weekdays = new DayOfWeek[DAYS_IN_WEEK];

    /** The picker's, not an application's: see {@link #keyboardActive}. */
    void setKeyboardActive(boolean active) {
        if (keyboardActive == active) {
            return;
        }
        keyboardActive = active;
        if (active) {
            cursor = cursorOrDefault();
        }
        focusFade.to(active ? 1 : 0);
        invalidate();
    }

    /**
     * A calendar showing this month, with nothing selected.
     *
     * <p>"This month" is read once, from the system clock, at construction. A calendar is not a
     * clock and does not repaint at midnight; an application that leaves one on screen across a day
     * boundary and cares calls {@link #setVisibleMonth} when it notices.
     */
    public CalendarView() {
        DateStrings.ensureRegistered();
        setFocusable(true);
        setCursor(Cursor.POINTER);
    }

    // ------------------------------------------------------------------ selection

    /** @return the selected day, or {@code null} if none is; always {@code null} in RANGE mode */
    public LocalDate selectedDate() {
        return selected;
    }

    /**
     * Selects a day, or clears the selection.
     *
     * <p>Pages the grid to the month holding it, because a selection the user cannot see is a
     * selection they have to go looking for. Selecting the day already selected changes nothing and
     * announces nothing, which is the early return that keeps two calendars bound to each other
     * from recursing.
     *
     * @param day the day, or {@code null} to clear
     * @return this
     */
    public CalendarView setSelectedDate(LocalDate day) {
        Ui.checkUiThread();
        if (day != null) {
            setVisibleMonth(day);
        }
        if (java.util.Objects.equals(selected, day)) {
            return this;
        }
        selected = day;
        if (day != null) {
            cursor = day;
        }
        invalidate();
        notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.CODE));
        return this;
    }

    /**
     * @return the selected period, or {@code null} if none is complete. A period being built &mdash;
     *         one end chosen and the other not &mdash; is not a period and is not answered here
     *         (ADR 042 &sect;6)
     */
    public DateRange selectedRange() {
        return selectedRange;
    }

    /**
     * Selects a period, or clears it. Pages the grid to the month holding its start.
     *
     * @param range the period, or {@code null} to clear
     * @return this
     */
    public CalendarView setSelectedRange(DateRange range) {
        Ui.checkUiThread();
        if (range != null) {
            setVisibleMonth(range.start());
        }
        if (java.util.Objects.equals(selectedRange, range)) {
            return this;
        }
        selectedRange = range;
        rangeAnchor = null;
        rangePreview = null;
        if (range != null) {
            cursor = range.start();
        }
        invalidate();
        notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.CODE));
        return this;
    }

    /**
     * Where the keyboard is, which is <b>not</b> the selection: arrows move this and Enter commits
     * it, so a grid can be walked without firing a form's handler at every step.
     *
     * <p>Public because it is announced: the {@code ACTIVE} aspect says the cursor moved, and ADR
     * 040 &sect;1.11 will not let a widget announce a state no accessor answers.
     *
     * @return the day the cursor is on, or {@code null} before the grid has had focus
     */
    public LocalDate focusedDate() {
        return cursor;
    }

    /** @return what a click does; {@link SelectionMode#SINGLE} unless it was changed */
    public SelectionMode selectionMode() {
        return selectionMode;
    }

    /**
     * Whether this calendar picks a day, a period, or nothing.
     *
     * <p>Changing the mode drops whatever the other mode had selected, because a day is not a period
     * and carrying one across as the other would be inventing a selection the user never made.
     *
     * @param mode the mode
     * @return this
     */
    public CalendarView setSelectionMode(SelectionMode mode) {
        Ui.checkUiThread();
        java.util.Objects.requireNonNull(mode, "mode");
        if (selectionMode == mode) {
            return this;
        }
        selectionMode = mode;
        boolean had = selected != null || selectedRange != null;
        selected = null;
        selectedRange = null;
        rangeAnchor = null;
        rangePreview = null;
        invalidate();
        if (had) {
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
        }
        return this;
    }

    // ------------------------------------------------------------------ what is on show

    /** @return the first day of the month currently drawn */
    public LocalDate visibleMonth() {
        return visibleMonth.withDayOfMonth(1);
    }

    /**
     * Pages the grid to the month holding a day.
     *
     * @param day any day in the wanted month
     * @return this
     */
    public CalendarView setVisibleMonth(LocalDate day) {
        Ui.checkUiThread();
        java.util.Objects.requireNonNull(day, "day");
        return showMonth(day, Change.Origin.CODE);
    }

    private CalendarView showMonth(LocalDate day, Change.Origin origin) {
        LocalDate first = day.withDayOfMonth(1);
        if (visibleMonth.withDayOfMonth(1).equals(first)) {
            return this;
        }
        visibleMonth = first;
        markNeedsLayout();
        notifyChange(Change.of(Change.Aspect.VALUE, origin));
        return this;
    }

    /**
     * Moves the grid by whole months, in the calendar being drawn: a Hijri month is 29 or 30 days
     * and paging by 30 would drift.
     *
     * @param months how many, negative to go back
     */
    private void pageMonths(int months, Change.Origin origin) {
        Chronology chronology = chronology();
        ChronoLocalDate current = CalendarChronology.date(chronology, visibleMonth.withDayOfMonth(1));
        LocalDate next = current == null ? visibleMonth.plusMonths(months)
                : CalendarChronology.iso(current.plus(months, ChronoUnit.MONTHS));
        if (next != null) {
            showMonth(next, origin);
        }
    }

    // ------------------------------------------------------------------ bounds and marks

    /** @return the earliest selectable day, or {@code null} for no lower bound */
    public LocalDate minDate() {
        return minDate;
    }

    /**
     * The earliest selectable day. A day before it is drawn disabled, refuses a click and carries no
     * select verb; it is <b>not</b> hidden, because a month with half its days missing reads as a
     * broken grid rather than as a bounded one.
     *
     * @param day the bound, or {@code null} for none
     * @return this
     */
    public CalendarView setMinDate(LocalDate day) {
        Ui.checkUiThread();
        if (java.util.Objects.equals(minDate, day)) {
            return this;
        }
        minDate = day;
        invalidate();
        notifyChange(Change.of(Change.Aspect.RANGE, Change.Origin.CODE));
        return this;
    }

    /** @return the latest selectable day, or {@code null} for no upper bound */
    public LocalDate maxDate() {
        return maxDate;
    }

    /**
     * The latest selectable day; the mirror of {@link #setMinDate}.
     *
     * @param day the bound, or {@code null} for none
     * @return this
     */
    public CalendarView setMaxDate(LocalDate day) {
        Ui.checkUiThread();
        if (java.util.Objects.equals(maxDate, day)) {
            return this;
        }
        maxDate = day;
        invalidate();
        notifyChange(Change.of(Change.Aspect.RANGE, Change.Origin.CODE));
        return this;
    }

    /** @return the filter, or {@code null} if every day inside the bounds may be picked */
    public Predicate<LocalDate> dateFilter() {
        return dateFilter;
    }

    /**
     * Which days may be picked, beyond the two bounds: weekends out, blackout dates out, only the
     * days a resource is free.
     *
     * <p><b>Called during paint, once per visible cell.</b> It must be cheap and it must be pure: a
     * filter that queries a database is a filter that stalls a frame, and one that answers
     * differently for the same day makes the grid flicker between two pictures of itself.
     *
     * @param filter answers whether a day may be picked, or {@code null} to allow every day
     * @return this
     */
    public CalendarView setDateFilter(Predicate<LocalDate> filter) {
        Ui.checkUiThread();
        dateFilter = filter;
        invalidate();
        notifyChange(Change.of(Change.Aspect.RANGE, Change.Origin.CODE));
        return this;
    }

    /**
     * What to draw under a day beyond its number: a holiday, a deadline, a day with appointments.
     *
     * <p>Called during paint, once per visible cell, under the same two obligations
     * {@link #setDateFilter} carries.
     *
     * @param marks answers a {@link DayMark} for a day, or {@code null} for none; may itself return
     *              {@code null} for a day with nothing to say
     * @return this
     */
    public CalendarView setDayMarks(Function<LocalDate, DayMark> marks) {
        Ui.checkUiThread();
        dayMarks = marks;
        invalidate();
        return this;
    }

    /** @return whether the week-number column is shown */
    public boolean showsWeekNumbers() {
        return showWeekNumbers;
    }

    /**
     * Shows a leading column of week numbers, in the numbering this language uses &mdash; which is
     * not ISO's everywhere, and deliberately so: Germany counts a week as belonging to the year
     * holding four of its days and the United States to the year holding its first, so the same
     * week at the turn of a year has two right answers and this shows each reader theirs.
     *
     * @param show whether to show the column
     * @return this
     */
    public CalendarView setShowWeekNumbers(boolean show) {
        Ui.checkUiThread();
        if (showWeekNumbers == show) {
            return this;
        }
        showWeekNumbers = show;
        markNeedsLayout();
        return this;
    }

    /** @return the calendar system being drawn, resolved from the locale unless one was declared */
    public Chronology chronology() {
        return CalendarChronology.resolve(declaredChronology, locale());
    }

    /**
     * Draws the grid in a calendar system of the application's choosing rather than the language's.
     *
     * <p>Rarely wanted, and worth having: an application whose domain <em>is</em> a calendar &mdash;
     * an Islamic finance ledger, a Japanese government form &mdash; needs the calendar its documents
     * are written in whatever language the interface is set to. The value exchanged is ISO either
     * way (ADR 042 &sect;1).
     *
     * @param chronology the calendar to draw, or {@code null} to follow the language again
     * @return this
     */
    public CalendarView setChronology(Chronology chronology) {
        Ui.checkUiThread();
        if (java.util.Objects.equals(declaredChronology, chronology)) {
            return this;
        }
        declaredChronology = chronology;
        markNeedsLayout();
        return this;
    }

    /** @return the day the drawn week starts on */
    public DayOfWeek firstDayOfWeek() {
        return declaredFirstDayOfWeek != null
                ? declaredFirstDayOfWeek : CalendarChronology.firstDayOfWeek(locale());
    }

    /**
     * Starts the week on a day of the application's choosing rather than the language's: Monday in
     * Germany, Sunday in Brazil and Saturday in Egypt are what the locale answers, and an
     * application whose own week is fixed by regulation says so here.
     *
     * @param day the first day of a week, or {@code null} to follow the language
     * @return this
     */
    public CalendarView setFirstDayOfWeek(DayOfWeek day) {
        Ui.checkUiThread();
        if (declaredFirstDayOfWeek == day) {
            return this;
        }
        declaredFirstDayOfWeek = day;
        markNeedsLayout();
        return this;
    }

    // ------------------------------------------------------------------ handlers

    /**
     * Called with the day the user picked, in {@link SelectionMode#SINGLE}.
     *
     * <p>The user alone: a {@link #setSelectedDate} from code never reaches it (ADR 040). Code that
     * wants every change, whoever made it, watches the widget with
     * {@link Widget#observeChanges} and reads {@link #selectedDate()}.
     *
     * @param listener what to run, or {@code null} to clear the slot
     * @return this
     * @throws IllegalStateException if a handler is already registered
     */
    public CalendarView onSelect(Consumer<LocalDate> listener) {
        Ui.checkUiThread();
        this.onSelect = Checks.handlerSlot(onSelect, listener, "CalendarView.onSelect");
        return this;
    }

    /**
     * Called with the period the user closed, in {@link SelectionMode#RANGE}. The first click of a
     * period reaches nothing: a range with one end is not a range.
     *
     * @param listener what to run, or {@code null} to clear the slot
     * @return this
     * @throws IllegalStateException if a handler is already registered
     */
    public CalendarView onSelectRange(Consumer<DateRange> listener) {
        Ui.checkUiThread();
        this.onSelectRange = Checks.handlerSlot(onSelectRange, listener, "CalendarView.onSelectRange");
        return this;
    }

    /**
     * The two modes' handlers, each reached only in its own mode.
     *
     * <p>Chains to {@code super} for every other aspect, which is the link a subclass must not
     * break (ADR 040 &sect;1.11).
     */
    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.SELECTION) {
            if (selectionMode == SelectionMode.SINGLE && onSelect != null) {
                onSelect.accept(selected);
            } else if (selectionMode == SelectionMode.RANGE && onSelectRange != null
                    && selectedRange != null) {
                onSelectRange.accept(selectedRange);
            }
            return;
        }
        super.handleUserChange(aspect);
    }

    // ------------------------------------------------------------------ what a day is

    /**
     * Whether a day may be picked: inside both bounds and not refused by the filter.
     *
     * @param day the day
     * @return whether a click on it would do anything
     */
    public boolean isSelectable(LocalDate day) {
        if (day == null || selectionMode == SelectionMode.NONE) {
            return false;
        }
        if (minDate != null && day.isBefore(minDate)) {
            return false;
        }
        if (maxDate != null && day.isAfter(maxDate)) {
            return false;
        }
        return dateFilter == null || dateFilter.test(day);
    }

    /** Whether a day falls inside the selection as it stands, including a range being previewed. */
    private boolean isInBand(LocalDate day) {
        if (selectionMode != SelectionMode.RANGE) {
            return false;
        }
        if (selectedRange != null) {
            return selectedRange.contains(day);
        }
        if (rangeAnchor != null && rangePreview != null) {
            return DateRange.of(rangeAnchor, rangePreview).contains(day);
        }
        return rangeAnchor != null && rangeAnchor.equals(day);
    }

    /** Whether a day is an end of the selection: filled solid rather than washed. */
    private boolean isSelectedEnd(LocalDate day) {
        if (selectionMode == SelectionMode.SINGLE) {
            return day.equals(selected);
        }
        if (selectionMode != SelectionMode.RANGE) {
            return false;
        }
        if (selectedRange != null) {
            return day.equals(selectedRange.start()) || day.equals(selectedRange.end());
        }
        return day.equals(rangeAnchor) || day.equals(rangePreview);
    }

    /**
     * The one seam every pick goes through: a click, Enter on the cursor and an assistive
     * technology's {@code SELECT} all arrive here, so all three clamp, page and announce alike.
     *
     * <p>In {@code RANGE} it is a two-step: the first pick anchors, announces {@code SELECTION} for
     * the anchor and reaches no handler because a range with one end is not a range; the second
     * closes the period and reaches the handler with it.
     */
    private boolean pick(LocalDate day, Change.Origin origin) {
        if (!isSelectable(day)) {
            return false;
        }
        cursor = day;
        showMonth(day, Change.Origin.ADJUSTMENT);
        switch (selectionMode) {
            case SINGLE -> {
                if (day.equals(selected)) {
                    return true; // nothing moved: the early return two bound calendars need
                }
                selected = day;
                invalidate();
                notifyChange(Change.of(Change.Aspect.SELECTION, origin));
            }
            case RANGE -> {
                if (rangeAnchor == null) {
                    rangeAnchor = day;
                    rangePreview = day;
                    selectedRange = null;
                    invalidate();
                    // Through the ordinary seam, and the handler still does not run: the aspect
                    // moved (a reader is told the anchor is selected) while the value an
                    // application would read is still null, and handleUserChange refuses the range
                    // handler for exactly that. Announcing the half alone would say the same thing
                    // and would break the contract test's rule that a seam calling one half owes
                    // the other -- a rule worth more than the one call it costs here.
                    notifyChange(Change.of(Change.Aspect.SELECTION, origin));
                } else {
                    selectedRange = DateRange.of(rangeAnchor, day);
                    rangeAnchor = null;
                    rangePreview = null;
                    invalidate();
                    notifyChange(Change.of(Change.Aspect.SELECTION, origin));
                }
            }
            default -> {
            }
        }
        return true;
    }

    /** Moves the keyboard cursor, paging the grid if it walked off the month. */
    private void moveCursor(LocalDate day, boolean extend) {
        if (day == null || day.equals(cursor) && !extend) {
            return;
        }
        cursor = day;
        if (extend && selectionMode == SelectionMode.RANGE && rangeAnchor != null) {
            rangePreview = day;
        }
        showMonth(day, Change.Origin.ADJUSTMENT);
        invalidate();
        notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
    }

    /** Where the cursor is when the keyboard first arrives: the selection, else today. */
    private LocalDate cursorOrDefault() {
        if (cursor != null) {
            return cursor;
        }
        if (selected != null) {
            return selected;
        }
        if (selectedRange != null) {
            return selectedRange.start();
        }
        LocalDate today = LocalDate.now();
        return visibleMonth.withDayOfMonth(1).getMonth() == today.getMonth()
                && visibleMonth.getYear() == today.getYear() ? today : visibleMonth.withDayOfMonth(1);
    }

    // ------------------------------------------------------------------ the grid memo

    /**
     * Rebuilds what the grid shows, and only when something it depends on moved: the month, the
     * calendar system, the first day of the week, or the language.
     *
     * <p>Every string the paint and the accessible tree need is built here, once, and held: 42 day
     * numbers, 6 week numbers, 7 weekday heads and the title. Building them in the paint would be
     * 55 strings per damaged frame to conclude that nothing had moved, which is the allocation a
     * quiet frame is a promise against; building them in the accessibility hook would be the same
     * cost again on a thread that has less to spare.
     */
    private void rebuildGrid() {
        Locale locale = locale();
        Chronology chronology = CalendarChronology.usableFor(
                CalendarChronology.resolve(declaredChronology, locale), visibleMonth);
        DayOfWeek firstDay = firstDayOfWeek();
        LocalDate first = visibleMonth.withDayOfMonth(1);
        ChronoLocalDate chronoFirst = CalendarChronology.date(chronology, first);
        if (chronoFirst != null) {
            // The first of the month IN THE CALENDAR BEING DRAWN, which is a different day from the
            // ISO first whenever the two disagree, and is the whole reason this is not
            // first.withDayOfMonth(1).
            chronoFirst = chronoFirst.with(ChronoField.DAY_OF_MONTH, 1);
            first = CalendarChronology.iso(chronoFirst);
        }
        if (first == null) {
            first = visibleMonth.withDayOfMonth(1);
        }
        int shift = Math.floorMod(first.getDayOfWeek().getValue() - firstDay.getValue(), DAYS_IN_WEEK);
        LocalDate start = first.minusDays(shift);
        boolean languageMoved = textLanguage.moved();
        if (!languageMoved && gridStartEpoch == start.toEpochDay()
                && gridChronology.equals(chronology) && gridFirstDay == firstDay) {
            return;
        }
        gridStartEpoch = start.toEpochDay();
        gridChronology = chronology;
        gridFirstDay = firstDay;
        monthFirstEpoch = first.toEpochDay();
        int monthLength = chronoFirst != null ? chronoFirst.lengthOfMonth() : first.lengthOfMonth();
        monthLastEpoch = monthFirstEpoch + monthLength - 1;
        nextFirstEpoch = monthLastEpoch + 1;
        int previousLength = previousMonthLength(chronology, chronoFirst, first);
        previousFirstEpoch = monthFirstEpoch - previousLength;

        monthTitle = chronoFirst != null
                ? CalendarChronology.monthHeader(chronology, chronoFirst, locale)
                : "";
        for (int i = 0; i < CELLS; i++) {
            dayText[i] = I18n.localizeDigits(Integer.toString(dayNumber(gridStartEpoch + i)));
        }
        WeekFields weekFields = CalendarChronology.weekFields(locale);
        for (int w = 0; w < WEEKS; w++) {
            LocalDate rowStart = start.plusDays((long) w * DAYS_IN_WEEK);
            weekText[w] = I18n.localizeDigits(
                    Integer.toString(rowStart.get(weekFields.weekOfWeekBasedYear())));
        }
        for (int c = 0; c < DAYS_IN_WEEK; c++) {
            DayOfWeek day = firstDay.plus(c);
            weekdays[c] = day;
            weekdayText[c] = CalendarChronology.narrowWeekday(day, locale);
        }
        textEpoch++;
    }

    private static int previousMonthLength(Chronology chronology, ChronoLocalDate chronoFirst,
                                           LocalDate isoFirst) {
        if (chronoFirst != null) {
            ChronoLocalDate previous = chronoFirst.minus(1, ChronoUnit.MONTHS);
            return previous.lengthOfMonth();
        }
        return isoFirst.minusMonths(1).lengthOfMonth();
    }

    /**
     * The number drawn in a cell, in the calendar being drawn, from three month starts rather than
     * from a conversion per cell: 42 conversions a frame is what this avoids, and in a chronology
     * whose conversion can throw it is also 42 guarded calls avoided.
     */
    private int dayNumber(long epochDay) {
        if (epochDay >= nextFirstEpoch) {
            return (int) (epochDay - nextFirstEpoch) + 1;
        }
        if (epochDay >= monthFirstEpoch) {
            return (int) (epochDay - monthFirstEpoch) + 1;
        }
        return (int) (epochDay - previousFirstEpoch) + 1;
    }

    private boolean isInVisibleMonth(long epochDay) {
        return epochDay >= monthFirstEpoch && epochDay <= monthLastEpoch;
    }

    /** The day in cell {@code index}, counted across the grid in reading order. */
    private LocalDate dayAt(int index) {
        return LocalDate.ofEpochDay(gridStartEpoch + index);
    }

    // ------------------------------------------------------------------ measure, layout, paint

    /**
     * A square day cell at the step's control height, so a calendar and a text field at the same
     * density agree about how tall a row of anything is, and seven of them plus the week column is
     * the natural width. The title is measured too: a long month name at a large step is wider than
     * seven cells in several languages, and a header clipped to the grid would cut it.
     */
    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        rebuildGrid();
        TextRuler ruler = textRuler();
        TextMetrics title = ruler.measure(monthTitle.isEmpty() ? "Hg" : monthTitle, t.body());
        TextMetrics label = ruler.measure("Hg", t.label());
        float cell = t.calendarCell();
        float week = showWeekNumbers ? t.calendarWeekColumn() : 0;
        float pad = t.spacingSmall();
        // The header has to hold the title between the two paging buttons, each a cell wide.
        float headerWidth = title.width() + 2 * cell + 2 * t.spacingMedium();
        float width = Math.max(week + DAYS_IN_WEEK * cell, headerWidth) + 2 * pad;
        float height = 2 * pad + t.resolvedHeight(title.lineHeight())
                + (label.lineHeight() + t.spacingSmall()) + WEEKS * cell;
        return constraints.constrain(width, height);
    }

    @Override
    protected void onLayout() {
        SizeTokens t = Theme.current().tokensFor(this);
        rebuildGrid();
        TextRuler ruler = textRuler();
        float pad = t.spacingSmall();
        headerH = t.resolvedHeight(ruler.measure("Hg", t.body()).lineHeight());
        weekdayH = ruler.measure("Hg", t.label()).lineHeight() + t.spacingSmall();
        weekColW = showWeekNumbers ? t.calendarWeekColumn() : 0;
        // The grid takes what is left and shares it, so a calendar stretched by its parent fills
        // the box rather than huddling at one end of it; never below the step's own cell, which is
        // the hit target the size axis promises.
        float available = Math.max(0, width() - 2 * pad - weekColW);
        cellW = Math.max(t.calendarCell(), available / DAYS_IN_WEEK);
        float rows = Math.max(0, height() - 2 * pad - headerH - weekdayH);
        cellH = Math.max(t.calendarCell(), rows / WEEKS);
        float contentW = weekColW + DAYS_IN_WEEK * cellW;
        float left = pad + Math.max(0, (width() - 2 * pad - contentW) / 2);
        // Reading right to left the week column is at the right edge, so the day columns start
        // where it ends on the other side. Only this coordinate mirrors; the columns themselves are
        // walked in reading order by cellLeft.
        gridX = isRightToLeft() ? left : left + weekColW;
        gridY = pad + headerH + weekdayH;
    }

    /**
     * Physical left edge of column {@code c}, counted in reading order: column 0 is the first day of
     * the week, which is the leftmost column reading left to right and the rightmost reading right
     * to left. The one place a column index becomes an x, and the exact inverse of
     * {@link #columnAt}.
     */
    private float cellLeft(int c, boolean rtl) {
        return rtl ? gridX + (DAYS_IN_WEEK - 1 - c) * cellW : gridX + c * cellW;
    }

    private int columnAt(float localX, boolean rtl) {
        float offset = localX - gridX;
        int c = (int) Math.floor(offset / cellW);
        if (rtl) {
            c = DAYS_IN_WEEK - 1 - c;
        }
        return c < 0 || c >= DAYS_IN_WEEK ? -1 : c;
    }

    /** Where the week-number column sits: the edge reading starts from, beside the first column. */
    private float weekColumnLeft(boolean rtl) {
        return rtl ? gridX + DAYS_IN_WEEK * cellW : gridX - weekColW;
    }

    @Override
    protected float paintOutset() {
        return Strokes.FOCUS_RING_OUTSET;
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        LayoutDirection direction = layoutDirection();
        boolean rtl = direction.isRightToLeft();
        rebuildGrid();
        TextRuler ruler = textRuler();
        ShapedText.Direction neutral = direction.neutralBase();
        boolean enabled = isEnabled();

        paintHeader(canvas, theme, t, ruler, neutral, rtl, enabled);
        paintWeekdayRow(canvas, theme, t, ruler, neutral, rtl);

        Font body = t.body();
        TextMetrics fm = ruler.measure("Hg", body);
        LocalDate today = LocalDate.now();
        float radius = t.radiusSmall();
        float inset = Strokes.HALF_PIXEL_INSET;

        for (int w = 0; w < WEEKS; w++) {
            float top = gridY + w * cellH;
            if (showWeekNumbers) {
                ShapedText week = ruler.shape(weekText[w], t.label(),
                        ShapedText.Direction.of(weekText[w], neutral));
                canvas.drawText(week,
                        weekColumnLeft(rtl) + (weekColW - week.metrics().width()) / 2,
                        top + (cellH - fm.height()) / 2 + fm.ascent(), theme.textMuted);
            }
            for (int c = 0; c < DAYS_IN_WEEK; c++) {
                int index = w * DAYS_IN_WEEK + c;
                long epoch = gridStartEpoch + index;
                LocalDate day = LocalDate.ofEpochDay(epoch);
                float left = cellLeft(c, rtl);
                boolean inMonth = isInVisibleMonth(epoch);
                boolean selectable = enabled && isSelectable(day);
                boolean end = isSelectedEnd(day);
                boolean band = isInBand(day);

                if (band && !end) {
                    // A wash rather than a fill, and square rather than rounded: the band is one
                    // shape across a week, and rounding every cell would draw it as seven pills.
                    canvas.fillRect(left, top, cellW, cellH, theme.primary.withAlpha(0.18f));
                }
                if (end) {
                    canvas.fillRoundRect(left + inset, top + inset, cellW - 2 * inset,
                            cellH - 2 * inset, radius, enabled ? theme.primary : theme.disabledFill);
                } else if (day.equals(hover) && selectable) {
                    canvas.fillRoundRect(left + inset, top + inset, cellW - 2 * inset,
                            cellH - 2 * inset, radius, theme.surfaceRaised);
                }
                if (day.equals(today) && !end) {
                    // A ring, so today is legible under a band and under a hover alike.
                    canvas.drawRoundRect(left + inset, top + inset, cellW - 2 * inset,
                            cellH - 2 * inset, radius, Strokes.BORDER, theme.primary);
                }

                String text = dayText[index];
                ShapedText line = ruler.shape(text, body, ShapedText.Direction.of(text, neutral));
                Color ink = !selectable ? theme.disabledText
                        : end ? theme.onPrimary
                        : inMonth ? theme.text : theme.textMuted;
                canvas.drawText(line, left + (cellW - line.metrics().width()) / 2,
                        top + (cellH - fm.height()) / 2 + fm.ascent(), ink);

                DayMark mark = dayMarks == null ? null : dayMarks.apply(day);
                if (mark != null) {
                    float dot = Math.max(1.5f, t.popupDotRadius());
                    canvas.fillCircle(left + cellW / 2, top + cellH - dot - t.spacingSmall() / 2,
                            dot, end ? theme.onPrimary : mark.dot());
                }
                // Gated on the fade rather than on isFocused, so the ring keeps rendering while it
                // fades out: focus is already gone by then and the ring would otherwise blink off.
                float focus = focusFade.value();
                if (focus > 0.001f && day.equals(cursor)) {
                    float gap = Strokes.FOCUS_GAP_INDICATOR;
                    canvas.drawRoundRect(left + gap, top + gap, cellW - 2 * gap, cellH - 2 * gap,
                            radius, Strokes.FOCUS_RING_THIN, theme.focusRing.withAlpha(focus));
                }
            }
        }
    }

    private void paintHeader(Canvas canvas, Theme theme, SizeTokens t, TextRuler ruler,
                             ShapedText.Direction neutral, boolean rtl, boolean enabled) {
        float pad = t.spacingSmall();
        ShapedText title = ruler.shape(monthTitle, t.body(),
                ShapedText.Direction.of(monthTitle, neutral));
        TextMetrics fm = ruler.measure("Hg", t.body());
        canvas.drawText(title, (width() - title.metrics().width()) / 2,
                pad + (headerH - fm.height()) / 2 + fm.ascent(),
                enabled ? theme.text : theme.disabledText);
        // The arrow that goes BACK is in the gutter reading starts from, and points that way; both
        // its side and its ink turn over, while what it does does not. Same split SegmentedControl
        // draws its scroll chevrons with, and for the same reason.
        float buttonW = t.calendarCell();
        float leftX = pad;
        float rightX = width() - pad - buttonW;
        paintChevron(canvas, theme, t, rtl ? rightX : leftX, buttonW, rtl ? 1 : -1,
                enabled, pagingHover == -1);
        paintChevron(canvas, theme, t, rtl ? leftX : rightX, buttonW, rtl ? -1 : 1,
                enabled, pagingHover == 1);
    }

    /**
     * One paging arrow, two strokes rather than a glyph: a font with no coverage for an arrow
     * character renders tofu, and this is chrome that must look the same in every language.
     */
    private void paintChevron(Canvas canvas, Theme theme, SizeTokens t, float x, float w,
                              int pointing, boolean enabled, boolean hovered) {
        float cx = x + w / 2;
        float cy = t.spacingSmall() + headerH / 2;
        if (hovered && enabled) {
            canvas.fillRoundRect(x, t.spacingSmall(), w, headerH, t.radiusSmall(),
                    theme.surfaceRaised);
        }
        Color ink = !enabled ? theme.disabledText : hovered ? theme.text : theme.textMuted;
        float s = t.chevronHalfW();
        float tip = cx + pointing * s / 2;
        float tail = cx - pointing * s / 2;
        canvas.drawLine(tail, cy - s, tip, cy, Strokes.ARROW_PEN, ink);
        canvas.drawLine(tip, cy, tail, cy + s, Strokes.ARROW_PEN, ink);
    }

    private void paintWeekdayRow(Canvas canvas, Theme theme, SizeTokens t, TextRuler ruler,
                                 ShapedText.Direction neutral, boolean rtl) {
        Font font = t.label();
        TextMetrics fm = ruler.measure("Hg", font);
        float top = t.spacingSmall() + headerH;
        if (showWeekNumbers) {
            String head = DateStrings.WEEK_COLUMN.get();
            // Only its first character: the column is one cell wide, and the word would be clipped
            // to something unreadable. What a reader is told is the whole word (the tree below).
            String glyph = head.isEmpty() ? "" : head.substring(0, 1);
            ShapedText shaped = ruler.shape(glyph, font, ShapedText.Direction.of(glyph, neutral));
            canvas.drawText(shaped,
                    weekColumnLeft(rtl) + (weekColW - shaped.metrics().width()) / 2,
                    top + (weekdayH - fm.height()) / 2 + fm.ascent(), theme.textMuted);
        }
        for (int c = 0; c < DAYS_IN_WEEK; c++) {
            String text = weekdayText[c];
            ShapedText shaped = ruler.shape(text, font, ShapedText.Direction.of(text, neutral));
            canvas.drawText(shaped,
                    cellLeft(c, rtl) + (cellW - shaped.metrics().width()) / 2,
                    top + (weekdayH - fm.height()) / 2 + fm.ascent(), theme.textMuted);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void onFocusGained() {
        cursor = cursorOrDefault();
        focusFade.to(1);
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
    }

    /**
     * Which paging button {@code localX} is over, as the button's <b>logical</b> identity: -1 pages
     * back and +1 pages on in both directions, so nothing downstream has to know which gutter holds
     * which.
     */
    private int pagingAt(float localX, float localY, boolean rtl) {
        SizeTokens t = Theme.current().tokensFor(this);
        float pad = t.spacingSmall();
        if (localY < pad || localY > pad + headerH) {
            return 0;
        }
        float buttonW = t.calendarCell();
        if (localX >= pad && localX < pad + buttonW) {
            return rtl ? 1 : -1;
        }
        if (localX >= width() - pad - buttonW && localX < width() - pad) {
            return rtl ? -1 : 1;
        }
        return 0;
    }

    /** The day under a pointer, or {@code null} if it is not over the grid. */
    private LocalDate dayAt(float localX, float localY, boolean rtl) {
        if (localY < gridY || cellH <= 0 || cellW <= 0) {
            return null;
        }
        int row = (int) Math.floor((localY - gridY) / cellH);
        int column = columnAt(localX, rtl);
        if (row < 0 || row >= WEEKS || column < 0) {
            return null;
        }
        return dayAt(row * DAYS_IN_WEEK + column);
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        boolean rtl = isRightToLeft();
        float lx = sceneToLocalX(event.x());
        float ly = sceneToLocalY(event.y());
        switch (event.type()) {
            case MOVE, ENTER -> {
                int paging = pagingAt(lx, ly, rtl);
                LocalDate day = paging == 0 ? dayAt(lx, ly, rtl) : null;
                boolean previewMoved = false;
                if (selectionMode == SelectionMode.RANGE && rangeAnchor != null
                        && day != null && !day.equals(rangePreview)) {
                    rangePreview = day;
                    previewMoved = true;
                }
                if (previewMoved || paging != pagingHover
                        || !java.util.Objects.equals(day, hover)) {
                    hover = day;
                    pagingHover = paging;
                    invalidate();
                }
            }
            case EXIT -> {
                if (hover != null || pagingHover != 0) {
                    hover = null;
                    pagingHover = 0;
                    invalidate();
                }
            }
            case CLICK -> {
                if (event.button() != Keys.MOUSE_LEFT || !isEnabled()) {
                    return;
                }
                event.consume();
                int paging = pagingAt(lx, ly, rtl);
                if (paging != 0) {
                    pageMonths(paging, Change.Origin.USER);
                    return;
                }
                LocalDate day = dayAt(lx, ly, rtl);
                if (day != null) {
                    requestFocus();
                    pick(day, Change.Origin.USER);
                }
            }
            case PRESS -> event.consume();
            default -> {
            }
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed() || !isEnabled()) {
            return;
        }
        boolean rtl = isRightToLeft();
        boolean shift = (event.modifiers() & Keys.MOD_SHIFT) != 0;
        LocalDate at = cursorOrDefault();
        switch (event.key()) {
            // A day is a step along a row, so Left and Right name a SIDE and mirror with the row.
            case Keys.LEFT -> {
                moveCursor(at.plusDays(rtl ? 1 : -1), shift);
                event.consume();
            }
            case Keys.RIGHT -> {
                moveCursor(at.plusDays(rtl ? -1 : 1), shift);
                event.consume();
            }
            // A week is below a week in every language: the vertical pair never mirrors.
            case Keys.UP -> {
                moveCursor(at.minusWeeks(1), shift);
                event.consume();
            }
            case Keys.DOWN -> {
                moveCursor(at.plusWeeks(1), shift);
                event.consume();
            }
            // The first and last day of the WEEK, which is a position in a row and not a side, so
            // these do not mirror either: Home is the first day of the week in both directions.
            case Keys.HOME -> {
                moveCursor(at.minusDays(Math.floorMod(
                        at.getDayOfWeek().getValue() - firstDayOfWeek().getValue(), DAYS_IN_WEEK)),
                        shift);
                event.consume();
            }
            case Keys.END -> {
                moveCursor(at.plusDays(DAYS_IN_WEEK - 1 - Math.floorMod(
                        at.getDayOfWeek().getValue() - firstDayOfWeek().getValue(), DAYS_IN_WEEK)),
                        shift);
                event.consume();
            }
            case Keys.PAGE_UP -> {
                stepPage(at, shift ? -12 : -1, shift);
                event.consume();
            }
            case Keys.PAGE_DOWN -> {
                stepPage(at, shift ? 12 : 1, shift);
                event.consume();
            }
            case Keys.ENTER, Keys.SPACE -> {
                pick(at, Change.Origin.USER);
                event.consume();
            }
            default -> {
            }
        }
    }

    /**
     * Pages by months (or, with Shift, by a year) and carries the cursor with it, in the calendar
     * being drawn: a Hijri year is eleven days shorter than an ISO one, and paging by 365 would
     * drift a year every three decades.
     */
    private void stepPage(LocalDate from, int months, boolean extend) {
        Chronology chronology = chronology();
        ChronoLocalDate current = CalendarChronology.date(chronology, from);
        LocalDate next = current == null ? from.plusMonths(months)
                : CalendarChronology.iso(current.plus(months, ChronoUnit.MONTHS));
        if (next != null) {
            moveCursor(next, extend);
        }
    }

    // ------------------------------------------------------------------ accessibility

    /**
     * The grid as a table, which is what it is: {@code TABLE} carrying six rows and seven columns
     * (eight with week numbers), a header row of {@code COLUMN_HEADER}s, one {@code ROW} per week
     * and one {@code CELL} per day, plus the two paging buttons.
     *
     * <p><b>Every role here was mapped by ADR 041</b>, three weeks before this widget existed, and
     * that is the whole accessibility cost of a calendar: no role is added to the model, no facet,
     * and no bridge code in the repository the three bridges live in.
     *
     * <p><b>A cell is named with the whole date.</b> "9 September 2026" and not "9": a reader
     * arrowing into a cell hears the cell, and the column head says only which weekday it is under.
     * The strings come from the memo the layout filled, so a frame damaged by a hover allocates
     * nothing here &mdash; except the full dates, which are built in this hook because they are
     * needed nowhere else and building 42 of them per layout would be worse.
     *
     * <p>Today carries a word of its own, because today is drawn as a ring and nothing else says
     * so, which makes it exactly the kind of fact that reaches everyone who looks and nobody who
     * listens.
     *
     * <p>The pointer's hover is deliberately not published: a fact derived from it would republish
     * the whole tree on every mouse move.
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        rebuildGrid();
        SizeTokens t = Theme.current().tokensFor(this);
        Locale locale = locale();
        boolean rtl = isRightToLeft();
        int columns = showWeekNumbers ? DAYS_IN_WEEK + 1 : DAYS_IN_WEEK;
        a.role(Accessible.Role.TABLE);
        a.table(WEEKS, columns);
        a.selection(selectionMode == SelectionMode.RANGE, false);

        float pad = t.spacingSmall();
        float buttonW = t.calendarCell();
        describePaging(a, KEY_PREVIOUS, rtl ? width() - pad - buttonW : pad, buttonW, pad);
        describePaging(a, KEY_NEXT, rtl ? pad : width() - pad - buttonW, buttonW, pad);

        a.child(KEY_HEADER);
        a.bounds(0, pad + headerH, width(), weekdayH);
        a.role(Accessible.Role.GROUP);
        int column = 0;
        if (showWeekNumbers) {
            a.child(KEY_HEAD_BASE - column);
            a.bounds(weekColumnLeft(rtl), pad + headerH, weekColW, weekdayH);
            a.role(Accessible.Role.COLUMN_HEADER);
            a.name(DateStrings.WEEK_COLUMN, Accessible.NameFrom.CONTENT);
            a.cell(-1, column);
            a.endChild();
            column++;
        }
        for (int c = 0; c < DAYS_IN_WEEK; c++, column++) {
            a.child(KEY_HEAD_BASE - column);
            a.bounds(cellLeft(c, rtl), pad + headerH, cellW, weekdayH);
            a.role(Accessible.Role.COLUMN_HEADER);
            // The WHOLE weekday name and not the narrow letter that is drawn: a reader crossing the
            // header row would otherwise hear seven single letters, which name nothing.
            a.name(CalendarChronology.fullWeekday(weekdays[c], locale), textEpoch,
                    Accessible.NameFrom.CONTENT);
            a.cell(-1, column);
            a.endChild();
        }
        a.endChild();

        Chronology chronology = gridChronology;
        LocalDate today = LocalDate.now();
        for (int w = 0; w < WEEKS; w++) {
            float top = gridY + w * cellH;
            a.child(KEY_ROW_BASE - w);
            a.bounds(0, top, width(), cellH);
            a.role(Accessible.Role.ROW);
            int c = 0;
            if (showWeekNumbers) {
                a.child(KEY_WEEK_BASE - w);
                a.bounds(weekColumnLeft(rtl), top, weekColW, cellH);
                a.role(Accessible.Role.CELL);
                a.name(weekText[w], textEpoch, Accessible.NameFrom.CONTENT);
                a.cell(w, c);
                a.endChild();
                c++;
            }
            for (int d = 0; d < DAYS_IN_WEEK; d++, c++) {
                int index = w * DAYS_IN_WEEK + d;
                LocalDate day = dayAt(index);
                // The flat cell index and not the column: this is the key a SELECT comes back with.
                a.child(index);
                a.bounds(cellLeft(d, rtl), top, cellW, cellH);
                a.role(Accessible.Role.CELL);
                a.name(cellName(chronology, day, today, locale), textEpoch,
                        Accessible.NameFrom.CONTENT);
                a.cell(w, c);
                if (selectionMode != SelectionMode.NONE) {
                    a.selectionItem(isSelectedEnd(day) || isInBand(day), index + 1, CELLS);
                }
                // A day that cannot be picked carries no verb, which is the whole of how it says
                // so: the walk overwrites a synthetic node's enabled bit with its owner's, so a
                // widget cannot publish one disabled by any route.
                if (isEnabled() && isSelectable(day)) {
                    a.action(Accessible.Action.SELECT);
                }
                if (day.equals(cursor) && (isFocused() || keyboardActive)) {
                    a.state(Accessible.State.ACTIVE);
                }
                a.endChild();
            }
            a.endChild();
        }
    }

    /**
     * The full date, plus what else is true of the day: that it is today, and whatever a mark says
     * about it. Built here rather than memoized: it is wanted by nothing else, and forty-two long
     * dates per layout pass would cost more than the tree does.
     */
    private String cellName(Chronology chronology, LocalDate day, LocalDate today, Locale locale) {
        ChronoLocalDate drawn = CalendarChronology.date(chronology, day);
        String date = drawn == null ? day.toString()
                : CalendarChronology.fullDate(chronology, drawn, locale);
        DayMark mark = dayMarks == null ? null : dayMarks.apply(day);
        String note = mark == null || mark.description() == null ? null : mark.description().get();
        if (day.equals(today)) {
            date = date + ", " + DateStrings.TODAY.get();
        }
        return note == null ? date : date + ", " + note;
    }

    private void describePaging(Accessibility a, long key, float x, float w, float pad) {
        a.child(key);
        a.bounds(x, pad, w, headerH);
        a.role(Accessible.Role.BUTTON);
        a.name(key == KEY_PREVIOUS ? DateStrings.PREVIOUS_MONTH : DateStrings.NEXT_MONTH,
                Accessible.NameFrom.CONTENT);
        if (isEnabled()) {
            a.action(Accessible.Action.PRESS);
        }
        a.endChild();
    }

    /**
     * Picking a day and paging the month, through the same private paths a click takes: an
     * assistive technology's select reaches {@link #pick} exactly as a click does, so it refuses an
     * unselectable day the same way, pages the same way and reaches the handler through the same
     * {@code USER} seam.
     *
     * <p>No enabled guard of its own beyond the two the paths already carry: the scene's dispatcher
     * has walked this widget and every ancestor for the enabled flag, refused an owner that is not
     * showing and refused a modal-blocked window before this runs.
     */
    @Override
    protected boolean onSyntheticAction(long key, Accessible.Action action,
                                        Accessible.Argument arg) {
        if (key == KEY_PREVIOUS || key == KEY_NEXT) {
            if (action != Accessible.Action.PRESS) {
                return false;
            }
            pageMonths(key == KEY_PREVIOUS ? -1 : 1, Change.Origin.USER);
            return true;
        }
        if (key >= 0 && key < CELLS && action == Accessible.Action.SELECT) {
            // pick() refuses a day the grid refuses a click on, so a reader asking for a
            // filtered-out day is told no by the same rule the pointer is -- and is told, rather
            // than being reported done while nothing happened.
            return pick(dayAt((int) key), Change.Origin.USER);
        }
        return false;
    }
}
