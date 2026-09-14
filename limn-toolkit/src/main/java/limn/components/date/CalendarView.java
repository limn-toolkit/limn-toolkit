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

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.Chronology;
import java.time.chrono.IsoChronology;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.Objects;
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
 * <p><b>Bounds are enforced, not snapped</b> (ADR 042 &sect;5, amended 2026-09-14). A day outside
 * {@link #setMinDate}/{@link #setMaxDate}, or refused by {@link #setDateFilter}, is drawn disabled,
 * refuses a click and Enter, and is published disabled without a select verb. The cursor
 * <em>stops</em> on it rather than skipping it (decision 30): a reader arrowing across the month
 * then hears that the day is unavailable, where a skip would have left a hole nobody was told
 * about.
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

    /**
     * What the grid is showing. A calendar that can only be paged a month at a time is unusable for
     * the one date every form asks for and nobody remembers by month &mdash; a date of birth is
     * four hundred presses away &mdash; so the header is a button that climbs: days to months,
     * months to years, and each pick comes back down.
     *
     * <p>An application can start at any level, which is the point of it being public:
     * {@code setView(View.YEARS)} on a birth-date picker opens where that user is going.
     */
    public enum View {
        /** A month of days: the calendar proper. */
        DAYS,
        /** The twelve months of the year on show. */
        MONTHS,
        /** A block of years around the one on show. */
        YEARS
    }

    /** How many week rows are drawn, always. The class comment says why it is not variable. */
    private static final int WEEKS = 6;
    private static final int DAYS_IN_WEEK = 7;
    private static final int CELLS = WEEKS * DAYS_IN_WEEK;
    /** Both choosers are four across: twelve months in three rows, and a block of years in six. */
    private static final int CHOOSER_COLUMNS = 4;
    private static final int MONTHS_IN_YEAR = 12;
    private static final int YEARS_PER_PAGE = 24;

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
    /** The header's title, which is a button: it is how a reader climbs to the choosers. */
    private static final long KEY_TITLE = -4;
    /** One per column head, offset so it cannot meet a row or a week number. */
    private static final long KEY_HEAD_BASE = -10;
    /** One per week row. */
    private static final long KEY_ROW_BASE = -100;
    /** One per week-number cell. */
    private static final long KEY_WEEK_BASE = -200;

    /**
     * Which part of the calendar the keyboard is on.
     *
     * <p>A <b>roving</b> focus rather than four focusable widgets, and the difference is not
     * cosmetic: in a popup the grid holds no operating-system focus at all &mdash; the field that
     * opened it does, and the keys are forwarded &mdash; so real focus inside would work in one
     * presentation and not the other. This is the same pattern the platform accessibility APIs
     * call an <em>active descendant</em>: one node holds the focus, another says which of its
     * descendants the cursor is on, and every bridge already understands it. The grid's day
     * cursor was always this; the header simply joins it.
     */
    private enum Part {
        /** The days, months or years, wherever the cursor is inside them. */
        GRID,
        /** The arrow that pages back. */
        PREVIOUS,
        /** The title, which climbs to the next view. */
        TITLE,
        /** The arrow that pages on. */
        NEXT
    }

    /** The order Tab walks: the grid first, because it is what the widget is for. */
    private static final Part[] TAB_ORDER = {Part.GRID, Part.PREVIOUS, Part.TITLE, Part.NEXT};

    private Part part = Part.GRID;

    private SelectionMode selectionMode = SelectionMode.SINGLE;
    private View view = View.DAYS;
    /**
     * The finest view this calendar picks in: {@link View#DAYS} for a calendar, {@link View#MONTHS}
     * for a month picker, {@link View#YEARS} for a year picker (decisions 12 and 47, 2026-09-14).
     * The chooser at this level is <b>terminal</b>: a pick there is a selection and not a step
     * down, and the view never goes below it.
     */
    private View granularity = View.DAYS;
    /** Where the keyboard is inside a chooser, as a flat cell index; meaningless in DAYS. */
    private int chooserCursor;

    /**
     * Where today is read from, or {@code null} for the system clock in the default zone read at
     * each call, which is what {@link LocalDate#now()} does. See {@link #setClock}.
     */
    private Clock clock;
    /** Any day inside the month on show; normalized to its first day when the grid is rebuilt. */
    private LocalDate visibleMonth = LocalDate.now();
    /**
     * Whether a caller or the person has chosen the month on show. Until one has, the month is
     * only the one today fell in when this was built, and a clock set afterwards may move it.
     */
    private boolean monthChosen;
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
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing)
                    // One ring, wherever the roving cursor is, so that is what each frame of the
                    // fade repaints. Without this a Tab landing in the calendar repainted the
                    // whole grid eleven times over -- see Transition.damages.
                    .damages(this::damageFocusRing);

    /** Which paging button the pointer is over: -1 previous, +1 next, 0 neither. */
    private int pagingHover;
    /** Whether the pointer is over the header's title, which is the button that climbs. */
    private boolean titleHover;
    /** Which chooser cell the pointer is over, or -1. */
    private int hoverChooserCell = -1;
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
    private View gridView = View.DAYS;
    /** Whether the drawn calendar's years carry their era; {@link CalendarChronology#yearsNameTheirEra}. */
    private boolean gridEraCalendar;
    private String headerTitle = "";
    /** One label per chooser cell: twelve month names, or a block of years. */
    private String[] chooserText = new String[0];
    /**
     * What a reader is told each chooser cell is: the month name, or the year <em>with its era</em>
     * where the calendar's years need one ("令和8"), which the drawn label abbreviates to fit.
     */
    private String[] chooserName = new String[0];
    private final String[] dayText = new String[CELLS];
    private final String[] weekText = new String[WEEKS];
    private final String[] weekdayText = new String[DAYS_IN_WEEK];
    private final DayOfWeek[] weekdays = new DayOfWeek[DAYS_IN_WEEK];

    /**
     * Whether Tab off either end of the header walk is declined rather than wrapped while a
     * picker drives this grid: the picker's popup has a time row after the grid (decision 19),
     * and the walk running off its end is what hands the keyboard to it.
     */
    private boolean tabLeavesAtEnds;

    /** The picker's, not an application's: see {@link #keyboardActive}. */
    void setKeyboardActive(boolean active) {
        if (keyboardActive == active) {
            return;
        }
        keyboardActive = active;
        if (active) {
            cursor = cursorOrDefault();
            part = Part.GRID;
        }
        focusFade.to(active ? 1 : 0);
        invalidate();
    }

    /** The picker's: see {@link #tabLeavesAtEnds}. */
    void setTabLeavesAtEnds(boolean leaves) {
        tabLeavesAtEnds = leaves;
    }

    /**
     * The picker's: the keyboard arrives from the thing after this grid in the popup's Tab cycle,
     * walking backwards, so it lands on the last header control rather than on the grid.
     */
    void enterFromEnd() {
        setKeyboardActive(true);
        Part from = part;
        part = Part.NEXT;
        damagePartChange(from, part);
        notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
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
            day = periodStart(day); // a month picker told 15 June holds June, and answers the 1st
            setVisibleMonth(day);
        }
        if (Objects.equals(selected, day)) {
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
            range = periodRange(range.start(), range.end());
            setVisibleMonth(range.start());
        }
        if (Objects.equals(selectedRange, range)) {
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

    /** @return whether the grid is showing days, months or years */
    public View view() {
        return view;
    }

    /**
     * Shows days, months or years. An application opens a birth-date picker on {@link View#YEARS}
     * and saves its user four hundred presses.
     *
     * @param wanted what to show
     * @return this
     * @throws IllegalArgumentException for a view finer than the {@link #granularity()}: a month
     *         picker has no days to show
     */
    public CalendarView setView(View wanted) {
        Ui.checkUiThread();
        Objects.requireNonNull(wanted, "view");
        if (wanted.compareTo(granularity) < 0) {
            throw new IllegalArgumentException("a calendar picking " + granularity
                    + " cannot show " + wanted + "; set the granularity first");
        }
        if (view == wanted) {
            return this;
        }
        view = wanted;
        chooserCursor = -1;
        markNeedsLayout();
        return this;
    }

    /** @return the finest view this calendar picks in; {@link View#DAYS} unless it was changed */
    public View granularity() {
        return granularity;
    }

    /**
     * What this calendar picks: a day, a month or a year (decisions 12, 47 and 48, 2026-09-14).
     *
     * <p>A {@link View#MONTHS} calendar is a month picker: it opens on the twelve months, a pick
     * there is the selection (the first day of the month, as an ISO date) rather than a step
     * down to days it cannot pick, and the title climbs only to the years and back. A
     * {@link View#YEARS} calendar is a year picker the same way. A period at either level runs
     * from the first day of its first month or year to the last day of its last (decision 51),
     * so a range of March to June answers 1 March to 30 June.
     *
     * <p>Takes a {@link View} rather than the field's finer list on purpose: an hour is nothing a
     * grid can show, so an hour granularity here does not compile. {@link DatePicker} converts.
     *
     * <p>Changing the level drops the selection, for {@link #setSelectionMode}'s reason: a day is
     * not a month, and carrying one across as the other would be inventing a choice.
     *
     * @param level the finest view
     * @return this
     */
    public CalendarView setGranularity(View level) {
        Ui.checkUiThread();
        Objects.requireNonNull(level, "granularity");
        if (granularity == level) {
            return this;
        }
        granularity = level;
        boolean had = selected != null || selectedRange != null;
        selected = null;
        selectedRange = null;
        rangeAnchor = null;
        rangePreview = null;
        if (view.compareTo(level) < 0) {
            view = level;
            chooserCursor = -1;
        }
        markNeedsLayout();
        if (had) {
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
        }
        return this;
    }

    /** Whether the view on show is the one this calendar picks in: a pick there selects. */
    private boolean terminalChooser() {
        return view != View.DAYS && view == granularity;
    }

    /**
     * The header's own verb: days climb to months, months to years, and years come back down to
     * the finest view there is.
     */
    private void climb() {
        setView(switch (view) {
            case DAYS -> View.MONTHS;
            case MONTHS -> View.YEARS;
            case YEARS -> granularity;
        });
    }

    /**
     * @return the ISO date of the first day of the month currently drawn, <b>in the calendar being
     *         drawn</b>: for a Hijri grid that is the first of the Hijri month, which is most
     *         often the middle of an ISO one (DATES-NEW-1, 2026-09-14)
     */
    public LocalDate visibleMonth() {
        return firstOfMonth(visibleMonth);
    }

    /** The drawn calendar's first day of the month holding {@code day}, as an ISO date. */
    private LocalDate firstOfMonth(LocalDate day) {
        return CalendarChronology.firstOfMonth(chronology(), day);
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

    /**
     * Where this calendar reads <em>today</em> from: the ring on today's cell, the "today" a
     * reader hears on it, and the month it opens on while nobody has chosen one.
     *
     * <p>Default {@code null}: the system clock in the default zone, read at each call, exactly as
     * {@link LocalDate#now()}. A fixed clock is for whatever has to look the same tomorrow as it
     * does today -- a capture, a test, a screenshot in a document -- and for an application that
     * keeps its own idea of the date, a server's or a simulated one.
     *
     * <p>A month already chosen, by {@link #setVisibleMonth}, {@link #setSelectedDate} or the
     * person paging, stays where it is; only the month construction guessed from the wall clock
     * follows the new one.
     *
     * @param newClock the clock, or {@code null} for the system's
     * @return this
     */
    public CalendarView setClock(Clock newClock) {
        Ui.checkUiThread();
        clock = newClock;
        if (!monthChosen) {
            LocalDate first = firstOfMonth(today());
            if (!visibleMonth().equals(first)) {
                visibleMonth = first;
                markNeedsLayout();
                notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.CODE));
            }
        }
        invalidate(); // today's ring moves with the clock
        return this;
    }

    /** Today, by this calendar's clock. */
    private LocalDate today() {
        return clock == null ? LocalDate.now() : LocalDate.now(clock);
    }

    /**
     * Shows the month holding a day, <b>the drawn calendar's month</b>. Normalized to that
     * month's first day so two days of one Hijri month compare equal here, and so a Hijri "next"
     * lands on the next Hijri month rather than on a day whose ISO first is the one already
     * shown, which is what made paging a no-op for most of the year (DATES-NEW-1, 2026-09-14).
     */
    private CalendarView showMonth(LocalDate day, Change.Origin origin) {
        monthChosen = true; // every path here is somebody choosing: code, a page, a pick, a cursor
        LocalDate first = firstOfMonth(day);
        if (visibleMonth().equals(first)) {
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
        ChronoLocalDate current = CalendarChronology.date(chronology, visibleMonth());
        LocalDate next = current == null ? visibleMonth().plusMonths(months)
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
        if (Objects.equals(minDate, day)) {
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
        if (Objects.equals(maxDate, day)) {
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
        if (Objects.equals(declaredChronology, chronology)) {
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
        LocalDate cursorWas = cursor;
        LocalDate monthWas = visibleMonth;
        cursor = day;
        showMonth(day, Change.Origin.ADJUSTMENT);
        switch (selectionMode) {
            case SINGLE -> {
                if (day.equals(selected)) {
                    return true; // nothing moved: the early return two bound calendars need
                }
                LocalDate selectedWas = selected;
                selected = day;
                if (!monthWas.equals(visibleMonth)) {
                    invalidate(); // a pick in a neighbouring month pages the whole grid
                } else {
                    // Three cells at most, and usually two: the day that lost the selection, the
                    // one that gained it, and where the cursor ring came from. A click used to
                    // repaint all forty-two days, the header and the week column for them.
                    damageDay(selectedWas);
                    damageDay(day);
                    damageDay(cursorWas);
                }
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

    /**
     * A pick in the chooser this calendar picks in: the month or the year is the selection
     * (decision 48). The same two-step in {@code RANGE} as {@link #pick}, and the same seam
     * &mdash; a click, Enter on the cursor and an assistive technology's {@code SELECT} all land
     * here &mdash; with the period's bounds taken as decision 51 says: a range of March to June is
     * 1 March to 30 June, in the calendar being drawn.
     */
    private boolean pickPeriod(int index, Change.Origin origin) {
        if (!isChooserCellOffered(index)) {
            return false;
        }
        LocalDate start = chooserDate(index);
        if (start == null) {
            return false;
        }
        chooserCursor = index;
        showMonth(start, Change.Origin.ADJUSTMENT);
        switch (selectionMode) {
            case SINGLE -> {
                if (start.equals(selected)) {
                    return true;
                }
                selected = start;
                invalidate();
                notifyChange(Change.of(Change.Aspect.SELECTION, origin));
            }
            case RANGE -> {
                if (rangeAnchor == null) {
                    rangeAnchor = start;
                    rangePreview = start;
                    selectedRange = null;
                } else {
                    selectedRange = periodRange(rangeAnchor, start);
                    rangeAnchor = null;
                    rangePreview = null;
                }
                invalidate();
                notifyChange(Change.of(Change.Aspect.SELECTION, origin));
            }
            default -> {
            }
        }
        return true;
    }

    /**
     * The first day of the period a day falls in at this calendar's {@link #granularity()}: the
     * day itself, the first of its month, or the first of its year, in the calendar being drawn.
     */
    private LocalDate periodStart(LocalDate day) {
        return switch (granularity) {
            case DAYS -> day;
            case MONTHS -> firstOfMonth(day);
            case YEARS -> {
                ChronoLocalDate drawn = CalendarChronology.date(chronology(), day);
                LocalDate first = drawn == null ? null
                        : CalendarChronology.iso(drawn.with(ChronoField.DAY_OF_YEAR, 1));
                yield first != null ? first : day.withDayOfYear(1);
            }
        };
    }

    /** The last day of the period a day falls in: the 30th or the 31st, the 28th or the 29th. */
    private LocalDate periodEnd(LocalDate day) {
        if (granularity == View.DAYS) {
            return day;
        }
        LocalDate first = periodStart(day);
        ChronoLocalDate drawn = CalendarChronology.date(chronology(), first);
        int length = granularity == View.MONTHS
                ? drawn != null ? drawn.lengthOfMonth() : first.lengthOfMonth()
                : drawn != null ? drawn.lengthOfYear() : first.lengthOfYear();
        return first.plusDays(length - 1L);
    }

    /**
     * A period spanning two days' periods, in either order, widened to whole months or years at
     * this calendar's granularity (decision 51): the start is the first day of the earlier
     * period and the end the last day of the later one.
     */
    private DateRange periodRange(LocalDate a, LocalDate b) {
        DateRange ordered = DateRange.of(a, b);
        return granularity == View.DAYS ? ordered
                : new DateRange(periodStart(ordered.start()), periodEnd(ordered.end()));
    }

    /**
     * How a chooser cell stands to the selection, when the chooser is the one this calendar picks
     * in: {@code 2} for an end of it (or the whole of a single pick, or the anchor of a period
     * being built), {@code 1} for a period inside a range, {@code 0} for none.
     */
    private int periodSelection(int index) {
        LocalDate start = chooserDate(index);
        if (start == null) {
            return 0;
        }
        if (selectionMode == SelectionMode.SINGLE) {
            return start.equals(selected) ? 2 : 0;
        }
        if (selectionMode != SelectionMode.RANGE) {
            return 0;
        }
        if (selectedRange != null) {
            if (start.equals(selectedRange.start()) || start.equals(periodStart(selectedRange.end()))) {
                return 2;
            }
            return selectedRange.contains(start) ? 1 : 0;
        }
        return start.equals(rangeAnchor) ? 2 : 0;
    }

    /** Moves the keyboard cursor, paging the grid if it walked off the month. */
    private void moveCursor(LocalDate day, boolean extend) {
        if (day == null || day.equals(cursor) && !extend) {
            return;
        }
        LocalDate from = cursor;
        cursor = day;
        boolean band = extend && selectionMode == SelectionMode.RANGE && rangeAnchor != null;
        if (band) {
            rangePreview = day;
        }
        LocalDate wasShowing = visibleMonth;
        showMonth(day, Change.Origin.ADJUSTMENT);
        if (band || !wasShowing.equals(visibleMonth)) {
            // A band redraws every cell between its ends, and paging redraws all of them; two
            // cells is the wrong answer for both.
            invalidate();
        } else {
            damageDay(from);
            damageDay(day);
        }
        notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
    }

    /**
     * Damages one cell of whichever view is showing, rather than the whole grid.
     *
     * <p>ADR 043 &sect;9.2. A cursor step changes two cells and used to repaint the widget:
     * measured at <b>50.4% of a nine-hundred-point window</b> for one arrow key, which under a
     * full-frame default costs nothing and under a partial one is the difference between a cell
     * and half a screen. The rect is the cell's box grown by the focus ring's own reach, because
     * the ring is what a cursor step draws and it sits inside the cell by a gap rather than on
     * its edge.
     *
     * @param index the flat cell index, or a negative for nothing
     */
    private void damageCell(int index) {
        if (index < 0 || index >= cellCount() || cellW <= 0 || cellH <= 0) {
            return;
        }
        int columns = columns();
        float left = cellLeft(index % columns, isRightToLeft());
        float top = gridY + (index / columns) * cellH;
        float grow = Strokes.FOCUS_RING_THIN + Strokes.FOCUS_GAP_INDICATOR;
        invalidate(left - grow, top - grow, cellW + 2 * grow, cellH + 2 * grow);
    }

    /**
     * Damages wherever the roving focus ring is drawn: one cell of the grid, or the header strip
     * when the cursor is on one of its three controls.
     *
     * <p>Read live rather than remembered, because the ring is drawn from these same three fields
     * &mdash; {@link #part}, {@link #view} and whichever cursor the view has &mdash; so the answer
     * cannot drift from the picture. What moves the cursor damages both ends itself; this is only
     * for the frames where nothing moves and the ring is fading.
     */
    private void damageFocusRing() {
        if (part != Part.GRID) {
            damageHeader();
        } else if (view == View.DAYS) {
            damageDay(cursor);
        } else {
            damageCell(chooserCursor);
        }
    }

    /** Damages the cell a day sits in, if that day is on screen at all. */
    private void damageDay(LocalDate day) {
        if (day == null || view != View.DAYS || gridStartEpoch == Long.MIN_VALUE) {
            return;
        }
        long offset = day.toEpochDay() - gridStartEpoch;
        if (offset >= 0 && offset < CELLS) {
            damageCell((int) offset);
        }
    }

    /** The header strip: the two arrows, the title, and the roving ring on whichever holds it. */
    private void damageHeader() {
        float pad = Theme.current().tokensFor(this).spacingSmall();
        invalidate(0, 0, width(), pad + headerH + pad);
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
        LocalDate today = today();
        LocalDate first = visibleMonth();
        // "The month on show holds today" in the calendar being drawn, not in ISO: a Hijri month
        // spans two ISO ones, and the ISO comparison put the cursor on the first day of a month
        // whose second half was the month with today in it.
        return firstOfMonth(today).equals(first) ? today : first;
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
        // The first of the month IN THE CALENDAR BEING DRAWN, which is a different day from the
        // ISO first whenever the two disagree. Re-derived here rather than trusted from the
        // field, because the chronology can move between two layouts (a locale switch) and the
        // month then has to be re-read in the calendar it is now drawn in.
        LocalDate first = CalendarChronology.firstOfMonth(chronology, visibleMonth);
        ChronoLocalDate chronoFirst = CalendarChronology.date(chronology, first);
        boolean eraCalendar = CalendarChronology.yearsNameTheirEra(chronology, today());
        int shift = Math.floorMod(first.getDayOfWeek().getValue() - firstDay.getValue(), DAYS_IN_WEEK);
        LocalDate start = first.minusDays(shift);
        boolean languageMoved = textLanguage.moved();
        if (!languageMoved && gridStartEpoch == start.toEpochDay()
                && gridChronology.equals(chronology) && gridFirstDay == firstDay
                && gridView == view && gridEraCalendar == eraCalendar) {
            return;
        }
        gridStartEpoch = start.toEpochDay();
        gridChronology = chronology;
        gridFirstDay = firstDay;
        gridEraCalendar = eraCalendar;
        monthFirstEpoch = first.toEpochDay();
        int monthLength = chronoFirst != null ? chronoFirst.lengthOfMonth() : first.lengthOfMonth();
        monthLastEpoch = monthFirstEpoch + monthLength - 1;
        nextFirstEpoch = monthLastEpoch + 1;
        int previousLength = previousMonthLength(chronology, chronoFirst, first);
        previousFirstEpoch = monthFirstEpoch - previousLength;

        gridView = view;
        chooserText = buildChooserLabels(chronology, chronoFirst, locale, true);
        chooserName = buildChooserLabels(chronology, chronoFirst, locale, false);
        headerTitle = buildTitle(chronology, chronoFirst, locale);
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

    /**
     * What the header says, per view: the month and the year, the year alone, or the block of years
     * the chooser is showing.
     *
     * <p>Capitalised, and that is a decision rather than a slip either way. Portuguese writes
     * months in lower case inside a sentence and this is not a sentence &mdash; it is the title of
     * what is below it, and "setembro de 2026" as a heading reads as a fragment. The first
     * character is upper-cased through {@link I18n}, so it follows the language's own casing rules
     * (ADR 034) rather than {@code String.toUpperCase}'s, which turns a Turkish dotless i into the
     * wrong letter. Languages that already capitalise their months are unchanged, and scripts with
     * no case at all are untouched by construction.
     */
    private String buildTitle(Chronology chronology, ChronoLocalDate chronoFirst, Locale locale) {
        if (chronoFirst == null) {
            return "";
        }
        return switch (view) {
            case DAYS -> capitalize(CalendarChronology.monthHeader(chronology, chronoFirst, locale));
            case MONTHS -> yearLabel(chronology, chronoFirst, locale);
            case YEARS -> {
                // The block's two ends, each labelled as a year of this calendar is: a Japanese
                // block that crosses an era reads "\u5e73\u621028 \u2013 \u4ee4\u548c21", not two bare numbers that
                // would say the block runs backwards.
                ChronoLocalDate first = yearCell(chronoFirst, 0);
                ChronoLocalDate last = yearCell(chronoFirst, YEARS_PER_PAGE - 1);
                String from = first == null ? "" : yearLabel(chronology, first, locale);
                String to = last == null ? "" : yearLabel(chronology, last, locale);
                yield from + " \u2013 " + to;
            }
        };
    }

    private static String capitalize(String text) {
        if (text.isEmpty()) {
            return text;
        }
        int first = text.codePointAt(0);
        int width = Character.charCount(first);
        return I18n.toUpperCase(text.substring(0, width)) + text.substring(width);
    }

    /**
     * A year as this calendar writes one: the bare year of era where that is a whole year
     * ("2026", "2569", "1448"), and the era with it where it is not ("令和8", "民國115"; decision
     * 38 and era-year-width, 2026-09-14).
     */
    private String yearLabel(Chronology chronology, ChronoLocalDate date, Locale locale) {
        if (gridEraCalendar) {
            return CalendarChronology.eraYear(chronology, date, locale, false);
        }
        return I18n.localizeDigits(Integer.toString(date.get(ChronoField.YEAR_OF_ERA)));
    }

    /**
     * The first year of the block a year falls in, so paging lands on the same twenty-four.
     *
     * <p>Blocked by the <b>proleptic</b> year and not the year of era. Blocking by the year of
     * era made every Japanese block start at a year-of-era multiple of 24 &mdash; Reiwa 0, which
     * is not a year &mdash; and paging back from Reiwa's block landed on Heisei's with the years
     * 2012 to 2018 in neither (DATES-NEW-1). The proleptic year is the one number every
     * chronology counts without a gap: for ISO, Thai and Hijri it equals the year of era, for
     * Minguo it is the ROC year, and for Japanese it is the ISO year.
     */
    private static int yearBlockStart(ChronoLocalDate date) {
        int year = date.get(ChronoField.YEAR);
        return year - Math.floorMod(year, YEARS_PER_PAGE);
    }

    /**
     * The labels of whichever chooser is showing, built with the grid rather than per paint: twelve
     * month names in the calendar being drawn (the eighth month of a Hijri year is not August), or
     * twenty-four years.
     *
     * @param drawn whether the labels are the ones painted in the cells, which abbreviate an era
     *              to its one letter and drop it while the block stays inside one era, or the
     *              ones a reader is told, which always carry the whole era where the calendar's
     *              years need one
     */
    private String[] buildChooserLabels(Chronology chronology, ChronoLocalDate chronoFirst,
                                        Locale locale, boolean drawn) {
        if (view == View.DAYS || chronoFirst == null) {
            return new String[0];
        }
        if (view == View.MONTHS) {
            String[] names = new String[MONTHS_IN_YEAR];
            java.time.format.DateTimeFormatter format =
                    java.time.format.DateTimeFormatter.ofPattern("MMM", locale)
                            .withChronology(chronology);
            for (int i = 0; i < MONTHS_IN_YEAR; i++) {
                ChronoLocalDate month = monthCell(chronoFirst, i);
                names[i] = month == null ? "" : capitalize(format.format(month));
            }
            return names;
        }
        String[] years = new String[YEARS_PER_PAGE];
        ChronoLocalDate first = yearCell(chronoFirst, 0);
        ChronoLocalDate last = yearCell(chronoFirst, YEARS_PER_PAGE - 1);
        // Drawn with the era's one letter only when the block crosses an era: inside one era the
        // era is the title's business and twenty-four "R"s would be noise in a small cell.
        boolean crossesEra = gridEraCalendar && first != null && last != null
                && !first.getEra().equals(last.getEra());
        for (int i = 0; i < YEARS_PER_PAGE; i++) {
            ChronoLocalDate year = yearCell(chronoFirst, i);
            if (year == null) {
                years[i] = "";
            } else if (!gridEraCalendar) {
                years[i] = I18n.localizeDigits(Integer.toString(year.get(ChronoField.YEAR_OF_ERA)));
            } else if (drawn) {
                years[i] = crossesEra
                        ? CalendarChronology.eraYear(chronology, year, locale, true)
                        : I18n.localizeDigits(Integer.toString(year.get(ChronoField.YEAR_OF_ERA)));
            } else {
                years[i] = CalendarChronology.eraYear(chronology, year, locale, false);
            }
        }
        return years;
    }

    /** The first day of month {@code index} (zero-based) of the year on show, or null. */
    private ChronoLocalDate monthCell(ChronoLocalDate reference, int index) {
        try {
            return reference.with(ChronoField.MONTH_OF_YEAR, index + 1L)
                    .with(ChronoField.DAY_OF_MONTH, 1);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The first day of year {@code index} (zero-based) of the block on show, or null.
     *
     * <p>Through {@code dateYearDay} on the proleptic year rather than {@code with(YEAR_OF_ERA)}
     * and two more {@code with}s: the latter stepped a Reiwa reference back to Heisei 31 when the
     * month was rewound across the era's start, so the cell labelled "1" opened 2019 in the wrong
     * era. The first day of a proleptic year is one call in every chronology.
     */
    private static ChronoLocalDate yearCell(ChronoLocalDate reference, int index) {
        try {
            return reference.getChronology().dateYearDay(yearBlockStart(reference) + index, 1);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The first day of the month or year a chooser cell stands for, in the drawn calendar, or null. */
    private ChronoLocalDate chooserCell(int index) {
        Chronology chronology = gridChronology;
        ChronoLocalDate reference = CalendarChronology.date(chronology,
                LocalDate.ofEpochDay(monthFirstEpoch));
        if (reference == null) {
            return null;
        }
        return view == View.MONTHS ? monthCell(reference, index) : yearCell(reference, index);
    }

    /** The ISO day a chooser cell stands for, or null where the calendar has no such date. */
    private LocalDate chooserDate(int index) {
        return CalendarChronology.iso(chooserCell(index));
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
        TextMetrics title = ruler.measure(headerTitle.isEmpty() ? "Hg" : headerTitle, t.body());
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
        // Only where something is drawn in it. Reserving the week gutter in a chooser as well
        // looked like the conservative choice -- keep the geometry identical across views -- and
        // was measurably wrong: nothing is drawn there, so the four columns were centred inside a
        // box that included a phantom column, putting them half its width (12pt at MEDIUM) off the
        // widget's centre while the title above them stayed centred on the widget. The box's
        // WIDTH is still view-independent, which is the part that matters: onMeasure asks for the
        // day grid's width in every view, so climbing does not resize the popup.
        weekColW = showWeekNumbers && view == View.DAYS ? t.calendarWeekColumn() : 0;
        // The grid takes what is left and shares it, so a calendar stretched by its parent fills
        // the box rather than huddling at one end of it; never below the step's own cell, which is
        // the hit target the size axis promises.
        float available = Math.max(0, width() - 2 * pad - weekColW);
        cellW = Math.max(t.calendarCell(), available / columns());
        // The weekday strip's height is reserved in every view, not only the one that fills it:
        // a chooser that reclaimed it would resize the popup under the pointer on every climb.
        float rowSpace = Math.max(0, height() - 2 * pad - headerH - weekdayH);
        cellH = Math.max(t.calendarCell(), rowSpace / rows());
        float contentW = weekColW + columns() * cellW;
        float left = pad + Math.max(0, (width() - 2 * pad - contentW) / 2);
        // Reading right to left the week column is at the right edge, so the day columns start
        // where it ends on the other side. Only this coordinate mirrors; the columns themselves are
        // walked in reading order by cellLeft.
        gridX = isRightToLeft() ? left : left + weekColW;
        gridY = pad + headerH + weekdayH;
    }

    /** How many columns the view on show has: a week, or the choosers' four. */
    private int columns() {
        return view == View.DAYS ? DAYS_IN_WEEK : CHOOSER_COLUMNS;
    }

    /** How many rows: six weeks, three rows of months, or six rows of years. */
    private int rows() {
        return switch (view) {
            case DAYS -> WEEKS;
            case MONTHS -> MONTHS_IN_YEAR / CHOOSER_COLUMNS;
            case YEARS -> YEARS_PER_PAGE / CHOOSER_COLUMNS;
        };
    }

    private int cellCount() {
        return columns() * rows();
    }

    /**
     * Physical left edge of column {@code c}, counted in reading order: column 0 is the first day of
     * the week, which is the leftmost column reading left to right and the rightmost reading right
     * to left. The one place a column index becomes an x, and the exact inverse of
     * {@link #columnAt}.
     */
    private float cellLeft(int c, boolean rtl) {
        return rtl ? gridX + (columns() - 1 - c) * cellW : gridX + c * cellW;
    }

    private int columnAt(float localX, boolean rtl) {
        float offset = localX - gridX;
        int c = (int) Math.floor(offset / cellW);
        if (rtl) {
            c = columns() - 1 - c;
        }
        return c < 0 || c >= columns() ? -1 : c;
    }

    /** Where the week-number column sits: the edge reading starts from, beside the first column. */
    private float weekColumnLeft(boolean rtl) {
        return rtl ? gridX + columns() * cellW : gridX - weekColW;
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
        if (view != View.DAYS) {
            paintChooser(canvas, theme, t, ruler, neutral, rtl, enabled);
            return;
        }
        paintWeekdayRow(canvas, theme, t, ruler, neutral, rtl);

        Font body = t.body();
        TextMetrics fm = ruler.measure("Hg", body);
        LocalDate today = today();
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

    /**
     * The month and year choosers, which are the same grid with a different arithmetic behind it:
     * four columns of larger cells, the cell holding the month or year on show filled the way a
     * selected day is, and the keyboard cursor ringed the same way.
     */
    private void paintChooser(Canvas canvas, Theme theme, SizeTokens t, TextRuler ruler,
                              ShapedText.Direction neutral, boolean rtl, boolean enabled) {
        Font font = t.body();
        TextMetrics fm = ruler.measure("Hg", font);
        float radius = t.radiusSmall();
        float inset = Strokes.HALF_PIXEL_INSET;
        // A terminal chooser fills the SELECTION the way the day grid does, ends solid and the
        // periods between them washed; a chooser somebody is passing through fills the month or
        // year on show, which is the only thing "current" means there.
        boolean terminal = terminalChooser();
        int current = terminal ? -1 : currentChooserCell();
        float focus = focusFade.value();
        int columns = columns();
        for (int i = 0; i < chooserText.length && i < cellCount(); i++) {
            int row = i / columns;
            int column = i % columns;
            float left = cellLeft(column, rtl);
            float top = gridY + row * cellH;
            boolean offered = enabled && isChooserCellOffered(i);
            int stands = terminal ? periodSelection(i) : i == current ? 2 : 0;
            if (stands == 1) {
                canvas.fillRect(left, top, cellW, cellH, theme.primary.withAlpha(0.18f));
            }
            if (stands == 2) {
                canvas.fillRoundRect(left + inset, top + inset, cellW - 2 * inset,
                        cellH - 2 * inset, radius, enabled ? theme.primary : theme.disabledFill);
            } else if (i == hoverChooserCell && offered) {
                canvas.fillRoundRect(left + inset, top + inset, cellW - 2 * inset,
                        cellH - 2 * inset, radius, theme.surfaceRaised);
            }
            String text = chooserText[i];
            ShapedText line = ruler.shape(text, font, ShapedText.Direction.of(text, neutral));
            Color ink = !offered ? theme.disabledText : stands == 2 ? theme.onPrimary : theme.text;
            canvas.drawText(line, left + (cellW - line.metrics().width()) / 2,
                    top + (cellH - fm.height()) / 2 + fm.ascent(), ink);
            if (focus > 0.001f && i == chooserCursor) {
                float gap = Strokes.FOCUS_GAP_INDICATOR;
                canvas.drawRoundRect(left + gap, top + gap, cellW - 2 * gap, cellH - 2 * gap,
                        radius, Strokes.FOCUS_RING_THIN, theme.focusRing.withAlpha(focus));
            }
        }
    }

    /** Which chooser cell holds what is on show: the visible month, or its year. */
    private int currentChooserCell() {
        ChronoLocalDate reference = CalendarChronology.date(gridChronology,
                LocalDate.ofEpochDay(monthFirstEpoch));
        if (reference == null) {
            return -1;
        }
        if (view == View.MONTHS) {
            return reference.get(ChronoField.MONTH_OF_YEAR) - 1;
        }
        return reference.get(ChronoField.YEAR) - yearBlockStart(reference);
    }

    /**
     * Whether a chooser cell leads anywhere: a month or a year with no selectable day in it is
     * drawn disabled and refuses a click, the same rule a day out of bounds follows.
     *
     * <p>The period's last day is the drawn calendar's, not ISO's: the Hijri month that starts on
     * 14 August ends on 11 September, and asking ISO August for its length would have refused a
     * month whose second half is well inside the bounds.
     */
    private boolean isChooserCellOffered(int index) {
        LocalDate day = chooserDate(index);
        LocalDate last = chooserPeriodEnd(index);
        if (day == null || last == null) {
            return false;
        }
        return (minDate == null || !last.isBefore(minDate))
                && (maxDate == null || !day.isAfter(maxDate));
    }

    /** The ISO date of the last day of the month or year a chooser cell stands for, or null. */
    private LocalDate chooserPeriodEnd(int index) {
        ChronoLocalDate cell = chooserCell(index);
        if (cell == null) {
            return null;
        }
        int length = view == View.MONTHS ? cell.lengthOfMonth() : cell.lengthOfYear();
        return CalendarChronology.iso(cell.plus(length - 1, ChronoUnit.DAYS));
    }

    private void paintHeader(Canvas canvas, Theme theme, SizeTokens t, TextRuler ruler,
                             ShapedText.Direction neutral, boolean rtl, boolean enabled) {
        float pad = t.spacingSmall();
        ShapedText title = ruler.shape(headerTitle, t.body(),
                ShapedText.Direction.of(headerTitle, neutral));
        TextMetrics fm = ruler.measure("Hg", t.body());
        float titleWidth = title.metrics().width();
        float titleX = (width() - titleWidth) / 2;
        // The title is a button, so it is drawn as one when the pointer is on it: without that
        // affordance the climb to the choosers is a feature only somebody who read the guide finds.
        if (titleHover && enabled) {
            float padX = t.spacingSmall();
            canvas.fillRoundRect(titleX - padX, pad, titleWidth + 2 * padX, headerH,
                    t.radiusSmall(), theme.surfaceRaised);
        }
        canvas.drawText(title, titleX, pad + (headerH - fm.height()) / 2 + fm.ascent(),
                enabled ? theme.text : theme.disabledText);
        // The arrow that goes BACK is in the gutter reading starts from, and points that way; both
        // its side and its ink turn over, while what it does does not. Same split SegmentedControl
        // draws its scroll chevrons with, and for the same reason.
        float buttonW = t.calendarCell();
        float leftX = pad;
        float rightX = width() - pad - buttonW;
        paintChevron(canvas, theme, t, rtl ? rightX : leftX, buttonW, rtl ? 1 : -1,
                enabled, pagingHover == -1 || part == Part.PREVIOUS);
        paintChevron(canvas, theme, t, rtl ? leftX : rightX, buttonW, rtl ? -1 : 1,
                enabled, pagingHover == 1 || part == Part.NEXT);
        // The roving focus, drawn where it is: a ring on the header control the keyboard is on.
        float focus = focusFade.value();
        if (focus > 0.001f && part != Part.GRID) {
            float gap = Strokes.FOCUS_GAP_INDICATOR;
            float ringX = switch (part) {
                case PREVIOUS -> rtl ? rightX : leftX;
                case NEXT -> rtl ? leftX : rightX;
                default -> pad + buttonW;
            };
            float ringW = part == Part.TITLE
                    ? Math.max(0, width() - 2 * (pad + buttonW)) : buttonW;
            canvas.drawRoundRect(ringX + gap, pad + gap, ringW - 2 * gap, headerH - 2 * gap,
                    t.radiusSmall(), Strokes.FOCUS_RING_THIN, theme.focusRing.withAlpha(focus));
        }
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
        // The grid, not the header: it is what the widget is for, and a caret that landed on
        // "previous month" would make every arrival a detour.
        part = Part.GRID;
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

    /** The flat cell index under a pointer, in whatever view is showing, or -1. */
    private int cellAt(float localX, float localY, boolean rtl) {
        if (localY < gridY || cellH <= 0 || cellW <= 0) {
            return -1;
        }
        int row = (int) Math.floor((localY - gridY) / cellH);
        int column = columnAt(localX, rtl);
        if (row < 0 || row >= rows() || column < 0) {
            return -1;
        }
        return row * columns() + column;
    }

    /** The day under a pointer, or {@code null} if it is not over the grid. */
    private LocalDate dayAt(float localX, float localY, boolean rtl) {
        int index = cellAt(localX, localY, rtl);
        return index < 0 ? null : dayAt(index);
    }

    /**
     * Whether a point is on the header's title, which is the region that climbs to the next view.
     * The band between the two paging buttons, not the glyphs of the title itself: a two-character
     * year is a small target, and the whole gap belongs to it.
     */
    private boolean onTitle(float localX, float localY) {
        SizeTokens t = Theme.current().tokensFor(this);
        float pad = t.spacingSmall();
        if (localY < pad || localY > pad + headerH) {
            return false;
        }
        float button = t.calendarCell();
        return localX >= pad + button && localX < width() - pad - button;
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        boolean rtl = isRightToLeft();
        float lx = sceneToLocalX(event.x());
        float ly = sceneToLocalY(event.y());
        switch (event.type()) {
            case MOVE, ENTER -> {
                int paging = pagingAt(lx, ly, rtl);
                boolean overTitle = paging == 0 && onTitle(lx, ly);
                int cell = paging == 0 && !overTitle ? cellAt(lx, ly, rtl) : -1;
                LocalDate day = view == View.DAYS && cell >= 0 ? dayAt(cell) : null;
                int chooserCell = view == View.DAYS ? -1 : cell;
                boolean previewMoved = false;
                if (selectionMode == SelectionMode.RANGE && rangeAnchor != null
                        && day != null && !day.equals(rangePreview)) {
                    rangePreview = day;
                    previewMoved = true;
                }
                if (previewMoved || paging != pagingHover || overTitle != titleHover
                        || chooserCell != hoverChooserCell
                        || !Objects.equals(day, hover)) {
                    LocalDate wasHovering = hover;
                    int wasCell = hoverChooserCell;
                    boolean headerMoved = paging != pagingHover || overTitle != titleHover;
                    hover = day;
                    pagingHover = paging;
                    titleHover = overTitle;
                    hoverChooserCell = chooserCell;
                    if (previewMoved) {
                        invalidate(); // the band's extent changed, which is many cells
                    } else {
                        if (headerMoved) {
                            damageHeader();
                        }
                        damageDay(wasHovering);
                        damageDay(day);
                        damageCell(wasCell);
                        damageCell(chooserCell);
                    }
                }
            }
            case EXIT -> {
                if (hover != null || pagingHover != 0 || titleHover || hoverChooserCell >= 0) {
                    LocalDate wasHovering = hover;
                    int wasCell = hoverChooserCell;
                    boolean headerMoved = pagingHover != 0 || titleHover;
                    hover = null;
                    pagingHover = 0;
                    titleHover = false;
                    hoverChooserCell = -1;
                    if (headerMoved) {
                        damageHeader();
                    }
                    damageDay(wasHovering);
                    damageCell(wasCell);
                }
            }
            case CLICK -> {
                if (event.button() != Keys.MOUSE_LEFT || !isEnabled()) {
                    return;
                }
                event.consume();
                int paging = pagingAt(lx, ly, rtl);
                if (paging != 0) {
                    page(paging);
                    return;
                }
                if (onTitle(lx, ly)) {
                    requestFocus();
                    climb();
                    return;
                }
                int cell = cellAt(lx, ly, rtl);
                if (cell < 0) {
                    return;
                }
                requestFocus();
                if (view == View.DAYS) {
                    pick(dayAt(cell), Change.Origin.USER);
                } else {
                    descend(cell);
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
        // Tab walks the calendar's own parts before it leaves the widget, which is what makes the
        // two paging arrows and the title reachable at all: they are painted regions, and painted
        // regions are not tab stops. Declining the key at the end of the walk is deliberate --
        // that is what lets focus leave, and what a picker reads as "close and move on".
        if (event.key() == Keys.TAB) {
            if (movePart(shift ? -1 : 1)) {
                event.consume();
            }
            return;
        }
        if (part != Part.GRID) {
            headerKey(event, rtl);
            return;
        }
        if (view != View.DAYS) {
            chooserKey(event, rtl);
            return;
        }
        LocalDate at = cursorOrDefault();
        // Ctrl (Cmd on macOS) with the vertical arrows climbs out of the days and back into them,
        // which is the ONLY keyboard way to the month and year choosers: until this existed they
        // were reachable by pointer and by an assistive technology's press on the title, and by
        // nothing a keyboard user could do. Not Ctrl+Up on macOS, where the system takes it for
        // Mission Control -- Cmd is the modifier that arrives there, and the toolkit's own
        // "command" test accepts either.
        if ((event.modifiers() & (Keys.MOD_CONTROL | Keys.MOD_SUPER)) != 0) {
            if (event.key() == Keys.UP) {
                setView(View.MONTHS);
                event.consume();
                return;
            }
            if (event.key() == Keys.DOWN) {
                event.consume();
                return; // already at the finest view: nothing below the days
            }
        }
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
     * What the two arrows step, which is a different unit per view: a month of days, a year of
     * months, a block of years. Paging by a month in a year chooser would move nothing a reader
     * could see.
     */
    private void page(int direction) {
        switch (view) {
            case DAYS -> pageMonths(direction, Change.Origin.USER);
            case MONTHS -> pageMonths(direction * MONTHS_IN_YEAR, Change.Origin.USER);
            case YEARS -> pageMonths(direction * MONTHS_IN_YEAR * YEARS_PER_PAGE,
                    Change.Origin.USER);
            default -> {
            }
        }
    }

    /**
     * A chooser cell picked: a year opens its months, a month opens its days. Neither is a
     * selection &mdash; nothing is chosen until a day is &mdash; so neither announces one, and the
     * form's handler does not run while somebody is still navigating towards the year they want.
     */
    private void descend(int index) {
        if (terminalChooser()) {
            pickPeriod(index, Change.Origin.USER);
            return;
        }
        if (!isChooserCellOffered(index)) {
            return;
        }
        LocalDate target = chooserDate(index);
        if (target == null) {
            return;
        }
        showMonth(target, Change.Origin.USER);
        chooserCursor = -1;
        setView(view == View.YEARS ? View.MONTHS : View.DAYS);
        // Arriving somewhere means being somewhere in it. Descending used to change the view and
        // leave the cursor wherever it had been -- on a day of the month you just left, so out of
        // sight -- and a person who picked a month with the keyboard was returned to a grid with
        // no visible cursor at all, having to guess where the next arrow would take them.
        part = Part.GRID;
        if (view == View.DAYS) {
            cursor = landingDay();
        }
        enterPart();
        invalidate();
        notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
    }

    /**
     * Where the day cursor lands in a month just navigated to: the selected day when it is in that
     * month, and the month's first day otherwise.
     *
     * <p>Not "the same day number as before", which is the tempting rule and is wrong outside the
     * ISO calendar: the day-of-month of an ISO cursor means nothing in a Hijri month of 29 days
     * that started three weeks ago. The first day of whatever month is on show is the one answer
     * correct in every chronology, and following the selection when it is visible is the case that
     * actually matters &mdash; somebody editing a date they already have.
     */
    private LocalDate landingDay() {
        rebuildGrid();
        if (selected != null && isInVisibleMonth(selected.toEpochDay())) {
            return selected;
        }
        if (selectedRange != null && isInVisibleMonth(selectedRange.start().toEpochDay())) {
            return selectedRange.start();
        }
        return LocalDate.ofEpochDay(monthFirstEpoch);
    }

    /**
     * Moves the roving focus one step along {@link #TAB_ORDER}.
     *
     * @param delta +1 forwards, -1 backwards
     * @return whether the calendar kept the key; {@code false} means the walk ran off an end and
     *         the key belongs to whatever encloses this widget
     */
    private boolean movePart(int delta) {
        int at = 0;
        for (int i = 0; i < TAB_ORDER.length; i++) {
            if (TAB_ORDER[i] == part) {
                at = i;
                break;
            }
        }
        int next = at + delta;
        if (next < 0 || next >= TAB_ORDER.length) {
            // Inside a popup the walk WRAPS, and outside one it runs off the end.
            //
            // A popup is a place you are in until you leave it deliberately, and Escape is how
            // you leave: a Tab that fell out of the back of it closed the calendar on somebody
            // who was only stepping out of the header, which is the report this branch answers.
            // A calendar sitting in a page is not a place you are in -- it is one control among
            // others -- so there the key has to be declined or focus could never move past it.
            // A popup with a time row after the grid declines it too, and the picker carries the
            // keyboard on to the row (decision 19).
            if (!keyboardActive || tabLeavesAtEnds) {
                return false;
            }
            next = Math.floorMod(next, TAB_ORDER.length);
        }
        Part from = part;
        part = TAB_ORDER[next];
        enterPart();
        damagePartChange(from, part);
        notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
        return true;
    }

    /**
     * Puts a cursor where the roving focus just landed, so that arriving somewhere is visible
     * <em>on arrival</em> rather than on the next keystroke.
     *
     * <p>Without this, coming back into the grid showed no ring at all -- the chooser's cursor was
     * still unset -- and the next arrow both created it and moved it, so a Down that should have
     * settled on September landed on December. Two reports, one cause: a cursor that does not
     * exist until it moves.
     */
    private void enterPart() {
        if (part != Part.GRID) {
            return;
        }
        // The memo first: which cell is "the one on show" is read from the month the grid was last
        // rebuilt for, and a key can arrive before the first paint of a view the keyboard itself
        // just switched to. Without this the cursor landed on January -- cell zero of a grid built
        // for the epoch -- which is the same trap chooserKey already guards against.
        rebuildGrid();
        if (view == View.DAYS) {
            cursor = cursorOrDefault();
        } else if (chooserCursor < 0) {
            chooserCursor = Math.max(0, currentChooserCell());
        }
    }

    /**
     * The keyboard while the cursor is on one of the three header controls: plain arrows move
     * between them, Enter and Space press the one it is on, and Down drops back into the grid.
     *
     * <p>Left and Right mirror, because they name a side of a row of buttons; Down does not,
     * because the grid is below the header in every language.
     */
    private void headerKey(KeyEvent event, boolean rtl) {
        switch (event.key()) {
            case Keys.LEFT -> {
                stepHeader(rtl ? 1 : -1);
                event.consume();
            }
            case Keys.RIGHT -> {
                stepHeader(rtl ? -1 : 1);
                event.consume();
            }
            case Keys.DOWN -> {
                Part from = part;
                part = Part.GRID;
                enterPart();
                damagePartChange(from, part);
                notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
                event.consume();
            }
            case Keys.ENTER, Keys.SPACE -> {
                pressPart();
                event.consume();
            }
            case Keys.ESCAPE -> {
                Part from = part;
                part = Part.GRID;
                enterPart();
                damagePartChange(from, part);
                event.consume();
            }
            default -> {
            }
        }
    }

    /** Moves between the three header controls, stopping at the ends rather than wrapping. */
    private void stepHeader(int delta) {
        Part[] header = {Part.PREVIOUS, Part.TITLE, Part.NEXT};
        int at = part == Part.PREVIOUS ? 0 : part == Part.TITLE ? 1 : 2;
        int next = Math.max(0, Math.min(header.length - 1, at + delta));
        if (header[next] == part) {
            return;
        }
        Part from = part;
        part = header[next];
        damagePartChange(from, part);
        notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
    }

    /**
     * Damages what a move of the roving focus actually changes: the header strip when it is at
     * either end of the move, and the cursor's cell when the grid is.
     */
    private void damagePartChange(Part from, Part to) {
        if (from != Part.GRID || to != Part.GRID) {
            damageHeader();
        }
        if (from == Part.GRID || to == Part.GRID) {
            if (view == View.DAYS) {
                damageDay(cursor);
            } else {
                damageCell(chooserCursor);
            }
        }
    }

    /** What Enter does on the part the cursor is on: the same path the pointer takes. */
    private void pressPart() {
        switch (part) {
            case PREVIOUS -> page(-1);
            case NEXT -> page(1);
            case TITLE -> climb();
            default -> {
            }
        }
    }

    /**
     * The keyboard inside a chooser, which is the same shape as the day grid's: the arrows move a
     * cursor over the cells, Enter descends, and Escape climbs back down without choosing.
     *
     * <p>Left and Right mirror here for the day grid's reason &mdash; they name a side of a row of
     * cells &mdash; and Up and Down do not.
     */
    private void chooserKey(KeyEvent event, boolean rtl) {
        // The labels, before anything reads them: a key can arrive before the first paint of a
        // view the keyboard itself just switched to, and a handler that waited for one would
        // swallow the first press after every climb. rebuildGrid is memoized, so this costs a
        // comparison in the case where a frame has already run.
        rebuildGrid();
        int count = Math.min(chooserText.length, cellCount());
        if (count == 0) {
            return;
        }
        if (chooserCursor < 0) {
            chooserCursor = Math.max(0, Math.min(count - 1, currentChooserCell()));
            // Arriving is not moving: the first arrow after the cursor appears would otherwise
            // both place it and step it.
            invalidate();
        }
        if ((event.modifiers() & (Keys.MOD_CONTROL | Keys.MOD_SUPER)) != 0) {
            if (event.key() == Keys.UP) {
                setView(View.YEARS); // the coarsest there is; from YEARS it stays put
                event.consume();
                return;
            }
            if (event.key() == Keys.DOWN) {
                View below = view == View.YEARS ? View.MONTHS : View.DAYS;
                if (below.compareTo(granularity) >= 0) {
                    setView(below); // never below what this calendar picks in
                }
                event.consume();
                return;
            }
        }
        int columns = columns();
        int next = chooserCursor;
        switch (event.key()) {
            case Keys.LEFT -> next += rtl ? 1 : -1;
            case Keys.RIGHT -> next += rtl ? -1 : 1;
            case Keys.UP -> next -= columns;
            case Keys.DOWN -> next += columns;
            case Keys.HOME -> next = chooserCursor - chooserCursor % columns;
            case Keys.END -> next = chooserCursor - chooserCursor % columns + columns - 1;
            case Keys.PAGE_UP -> {
                page(-1);
                event.consume();
                return;
            }
            case Keys.PAGE_DOWN -> {
                page(1);
                event.consume();
                return;
            }
            case Keys.ENTER, Keys.SPACE -> {
                descend(chooserCursor);
                event.consume();
                return;
            }
            case Keys.ESCAPE -> {
                if (view != granularity) {
                    setView(granularity); // out of a chooser, back to the finest view there is
                    event.consume();
                }
                return;
            }
            default -> {
                return;
            }
        }
        event.consume();
        // Out of the grid means stay put, not slide to the nearest corner: clamping turned a Down
        // on the bottom row into a jump to the last cell of the year, which is a move nobody asked
        // for and the opposite of what an edge is for.
        if (next < 0 || next >= count) {
            return;
        }
        if (next != chooserCursor) {
            int from = chooserCursor;
            chooserCursor = next;
            damageCell(from);
            damageCell(next);
            notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
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
        boolean days = view == View.DAYS;
        int columns = days && showWeekNumbers ? DAYS_IN_WEEK + 1 : columns();
        a.role(Accessible.Role.TABLE);
        a.table(rows(), columns);
        a.selection((days || terminalChooser()) && selectionMode == SelectionMode.RANGE, false);

        float pad = t.spacingSmall();
        float buttonW = t.calendarCell();
        describePaging(a, KEY_PREVIOUS, rtl ? width() - pad - buttonW : pad, buttonW, pad);
        describePaging(a, KEY_NEXT, rtl ? pad : width() - pad - buttonW, buttonW, pad);
        // The title is a button and is published as one: it is the only way to the month and year
        // choosers, and a reader that is never offered it is left paging a month at a time towards
        // a date of birth.
        a.child(KEY_TITLE);
        a.bounds(pad + buttonW, pad, Math.max(0, width() - 2 * (pad + buttonW)), headerH);
        a.role(Accessible.Role.BUTTON);
        a.name(headerTitle, textEpoch, Accessible.NameFrom.CONTENT);
        // EXPANDED means "not the finest view" (settled calendar-title-verbs, 2026-09-14), and
        // the verb published beside PRESS is the one the state allows: EXPAND while the finest
        // view is showing and there is one above it, COLLAPSE while a chooser is -- so a bridge
        // vending an expand pattern from the facet offers what the title accepts. PRESS keeps
        // climbing one step, which is what the pointer does.
        boolean climbed = view != granularity;
        if (granularity != View.YEARS) {
            a.expand(climbed);
        }
        if (isEnabled()) {
            if (climbed) {
                a.action(Accessible.Action.PRESS, Accessible.Action.COLLAPSE);
            } else if (granularity != View.YEARS) {
                a.action(Accessible.Action.PRESS, Accessible.Action.EXPAND);
            }
        }
        if (focusHere(Part.TITLE)) {
            a.state(Accessible.State.ACTIVE);
        }
        a.endChild();

        if (!days) {
            describeChooser(a, rtl);
            return;
        }

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
        LocalDate today = today();
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
                    // A day the bounds or the filter refuse is published disabled and carries
                    // no verb (decision 30, 2026-09-14): the cursor stops on it, so a reader
                    // hears "unavailable" where the eye sees the muted number, and Enter is
                    // refused by pick(). Narrowing only -- Accessibility.disabled -- which is
                    // the one route a synthetic child has to be less enabled than its owner.
                    if (isEnabled() && isSelectable(day)) {
                        a.action(Accessible.Action.SELECT);
                    } else if (!isSelectable(day)) {
                        a.disabled();
                    }
                }
                if (day.equals(cursor) && focusHere(Part.GRID)) {
                    a.state(Accessible.State.ACTIVE);
                }
                a.endChild();
            }
            a.endChild();
        }
    }

    /**
     * The chooser as the same table: rows of cells, each named with the month or the year it
     * stands for, each carrying the verb that descends, and the keyboard cursor marked active.
     *
     * <p>No selection facet on the cells of a chooser somebody is passing through: descending
     * into a month is navigation and not a choice, and telling a reader that a month is
     * "selected" would be telling them the form now holds a value it does not. The chooser this
     * calendar picks in is the exception and carries a real one (decision 48): there the month
     * <em>is</em> the value.
     */
    private void describeChooser(Accessibility a, boolean rtl) {
        int columns = columns();
        boolean terminal = terminalChooser() && selectionMode != SelectionMode.NONE;
        int count = Math.min(chooserText.length, cellCount());
        for (int row = 0; row < rows(); row++) {
            float top = gridY + row * cellH;
            a.child(KEY_ROW_BASE - row);
            a.bounds(0, top, width(), cellH);
            a.role(Accessible.Role.ROW);
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= chooserText.length) {
                    break;
                }
                a.child(index);
                a.bounds(cellLeft(column, rtl), top, cellW, cellH);
                a.role(Accessible.Role.CELL);
                a.name(chooserName[index], textEpoch, Accessible.NameFrom.CONTENT);
                a.cell(row, column);
                if (terminal) {
                    a.selectionItem(periodSelection(index) > 0, index + 1, count);
                }
                if (isEnabled() && isChooserCellOffered(index)) {
                    a.action(Accessible.Action.SELECT);
                } else if (!isChooserCellOffered(index)) {
                    a.disabled(); // a month with no selectable day: decision 30's rule, one level up
                }
                // The cell on show (`current`) is not marked here: CHECKED is the toggle facet's
                // and the builder refuses it. Decision 48 (2026-09-14) puts the fact in the
                // cell's name instead, which is the dates lane's change.
                if (index == chooserCursor && focusHere(Part.GRID)) {
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

    /**
     * Whether the keyboard cursor is on a given part <em>and</em> this calendar is the thing being
     * driven &mdash; either because it holds the focus itself, or because a picker is forwarding
     * its keys here.
     */
    private boolean focusHere(Part which) {
        return part == which && (isFocused() || keyboardActive);
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
        // The roving cursor is published as the active descendant, which is the vocabulary every
        // bridge already has for "focus is here without the focus moving".
        if (focusHere(key == KEY_PREVIOUS ? Part.PREVIOUS : Part.NEXT)) {
            a.state(Accessible.State.ACTIVE);
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
            page(key == KEY_PREVIOUS ? -1 : 1);
            return true;
        }
        if (key == KEY_TITLE) {
            boolean climbed = view != granularity;
            switch (action) {
                case PRESS -> {
                    if (granularity == View.YEARS) {
                        return false; // a year picker's title has nowhere to climb
                    }
                    climb();
                    return true;
                }
                case EXPAND -> {
                    if (climbed || granularity == View.YEARS) {
                        return false; // published only on the finest view
                    }
                    climb();
                    return true;
                }
                case COLLAPSE -> {
                    if (!climbed) {
                        return false;
                    }
                    setView(granularity); // straight back to the finest view, as Escape does
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
        if (view != View.DAYS) {
            if (key < 0 || key >= chooserText.length || action != Accessible.Action.SELECT) {
                return false;
            }
            if (!isChooserCellOffered((int) key)) {
                return false;
            }
            descend((int) key);
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
