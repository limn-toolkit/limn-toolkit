package limn.components.date;

import limn.accessibility.Accessibility;
import limn.accessibility.CellFacet;
import limn.accessibility.Accessible;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.internal.a11y.GridAccessibility;
import limn.components.internal.a11y.RowsAccessibility;
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
import limn.internal.lang.Checks;
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
 *         .onSelect(() -> booking.setStart(calendar.selectedDate()));
 * }</pre>
 *
 * <p><b>The value is ISO and the calendar drawn is the reader's.</b> Everything this class hands
 * out and takes in is a {@link LocalDate}; the month names, the year number and the length of a
 * month come from the chronology resolved for the widget's effective locale, which is ISO for every
 * language until one carries a {@code u-ca} extension or an application calls
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
 * <p><b>Bounds are enforced, not snapped.</b> A day outside
 * {@link #setMinDate}/{@link #setMaxDate}, or refused by {@link #setDateFilter}, is drawn disabled,
 * refuses a click and Enter, and is published disabled without a select verb. The cursor
 * <em>stops</em> on it rather than skipping it: a reader arrowing across the month then hears that
 * the day is unavailable, where a skip would have left a hole nobody was told about.
 *
 * <p><b>To a screen reader this is a table:</b> {@code TABLE} over {@code ROW}s of {@code CELL}s
 * under a row of {@code COLUMN_HEADER}s, which are the four roles already mapped on all three
 * platforms for the {@code Table} widget. A day cell is named with the whole date and not the bare
 * number, because a cell heard on its own has to say what it is.
 *
 * <p>Like every widget, it is touched from the UI thread only: each method that changes it checks,
 * and throws on any other thread.
 */
public final class CalendarView extends Widget<CalendarView> {

    /** What a click does. */
    public enum SelectionMode {
        /** Nothing: a calendar that displays a month and is not an input. */
        NONE,
        /** One day. {@link #onSelect} is the handler. */
        SINGLE,
        /** A period, anchored by the first click and closed by the second. {@link #onSelect} too. */
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
     * cells therefore take the non-negative half and
     * everything that is not a day is negative and spaced apart from its neighbours by more than it
     * can ever have members. The cells are the only nodes here that carry a verb, and the key
     * is what makes {@code SELECT} land on the day it was asked for.
     *
     * <p><b>Amended 2026-09-16: a day is not a slot.</b> The day grid's three kinds
     * of node &mdash; the week rows, the week-number cells and the day cells &mdash; were keyed by
     * their <em>position in the grid</em>, 0..41 for a day and 0..5 for a row, which made paging a
     * month rewrite the name of forty-two nodes that stayed the same node. Measured on 2026-09-16:
     * NVDA 2024.4.2 read {@code property=NAME from="20 de setembro de 2026" to="18 de outubro de
     * 2026"} on one element and, subscribed to {@code Name} on the focused one, spoke the slot's
     * new occupant before the focus moved, in 6 of 7 paging events over three runs; Orca saw the
     * same 42 renames arrive one signal at a time, 190 in a run, and read a row mid-burst, saying
     * {@code '1 de outubro'} beside {@code '4 de setembro'}. macOS agrees at source (131
     * {@code AXTitleChanged} on stable node ids) and VoiceOver never spoke a wrong date, so that
     * platform passes before and after and is not where this is measured. Each of the three is now
     * keyed by <b>the day it shows</b> &mdash; a day cell by its epoch day, a row and its week
     * number by the epoch day their week starts on &mdash; in three ranges of its own high above
     * everything else here, so paging <em>retires</em> the days that left and <em>mints</em> the
     * days that arrived, renames nothing, and keeps the nodes of the weeks the two months share.
     * A verb that arrives for a day no longer in the grid decodes out of range and is refused,
     * where a slot key would have resolved it to whatever day now stands there.
     *
     * <p>A chooser's rows and cells have keys of their own rather than the day grid's. A key is
     * also an <em>identity</em>: the publish step interns (owner, key), so a month cell keyed
     * {@code 0} was the same node as the day cell keyed {@code 0} across a view change, and a
     * Windows element is built once with the interfaces its node had when a client first read it
     * &mdash; a cell first read in the month chooser, where it carries no selection item, answered
     * no SelectionItem for the day it later stood for. Disjoint keys make a view change destroy the
     * one set of nodes and mint the other.
     *
     * <p>The months and the years are apart from each other as well, not only from the days: in a
     * month picker the months carry a selection item and the years a person climbs to do not, so a
     * shared chooser range kept the same defect one level up.
     */
    private static final long KEY_PREVIOUS = -1;
    private static final long KEY_NEXT = -2;
    private static final long KEY_HEADER = -3;
    /** The header's title, which is a button: it is how a reader climbs to the choosers. */
    private static final long KEY_TITLE = -4;
    /** One per column head, offset so it cannot meet a row or a week number. */
    private static final long KEY_HEAD_BASE = -10;
    /**
     * A day cell's key: {@code this + the day's epoch day}. A {@code LocalDate}'s epoch day fits
     * in &plusmn;2<sup>39</sup> at the very ends of the ISO range, so the three day-grid ranges
     * are two powers of two apart and cannot meet each other or anything negative above.
     */
    private static final long KEY_DAY_BASE = 1L << 40;
    /** A week row's key: {@code this + the epoch day its week starts on}. */
    private static final long KEY_WEEK_ROW_BASE = 1L << 41;
    /** A week-number cell's key: {@code this + the epoch day its week starts on}. */
    private static final long KEY_WEEK_NUMBER_BASE = 1L << 42;
    /** One per row of the month chooser: three. */
    private static final long KEY_MONTH_ROW_BASE = -300;
    /** One per row of the year chooser: six. */
    private static final long KEY_YEAR_ROW_BASE = -400;
    /** One per month cell, twelve, decoded in {@link #onSyntheticAction}. */
    private static final long KEY_MONTH_BASE = -1000;
    /** One per year cell, twenty-four, decoded in {@link #onSyntheticAction}. */
    private static final long KEY_YEAR_BASE = -2000;

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

    /** The order Tab walks a calendar on its own: the grid first, because it is what it is for. */
    private static final Part[] TAB_ORDER = {Part.GRID, Part.PREVIOUS, Part.TITLE, Part.NEXT};

    /**
     * The order Tab walks while a picker drives the calendar: the card from top to bottom, the
     * header and then the grid, so that a time row under the grid comes last. With the grid first
     * the row sat between the header and the days, and a Tab from the last arrow skipped to the
     * row. The popup still opens on the grid, and without a row the cycle is the same one.
     */
    private static final Part[] POPUP_TAB_ORDER = {Part.PREVIOUS, Part.TITLE, Part.NEXT, Part.GRID};

    private Part part = Part.GRID;

    private SelectionMode selectionMode = SelectionMode.SINGLE;
    private View view = View.DAYS;
    /**
     * The finest view this calendar picks in: {@link View#DAYS} for a calendar, {@link View#MONTHS}
     * for a month picker, {@link View#YEARS} for a year picker.
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

    private Runnable onSelect;

    private final Transition focusFade =
            new Transition(this).duration(Theme.of(this).animFocus).easing(Theme.of(this).animEasing)
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
    /** The level the labels were built for: it decides which chooser names its cell on show. */
    private View gridGranularity = View.DAYS;
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
    /**
     * The composite name each week row and each chooser row was last published under, kept so a
     * quiet frame compares and allocates nothing (the rule {@code Tree} and {@code Table} rows
     * follow); {@code null} where the row draws nothing. The chooser's six is its deepest, the
     * year chooser's row count.
     */
    private final String[] rowNames = new String[WEEKS];
    private final String[] chooserRowNames = new String[6];
    /** Reused per walk to assemble a row's name before comparing it with the kept one. */
    private final StringBuilder nameBuilder = new StringBuilder();
    private final String[] weekdayText = new String[DAYS_IN_WEEK];
    private final DayOfWeek[] weekdays = new DayOfWeek[DAYS_IN_WEEK];

    /**
     * Whether Tab off either end of the header walk is declined rather than wrapped while a
     * picker drives this grid: the picker's popup has a time row after the grid,
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

    /**
     * What a picker runs when this grid is clicked, before the click acts: in a popup the grid is
     * not focusable, and a click on it while the time row held the keyboard paged the month and
     * left the arrows editing the hour. {@code DateField}'s hook of the same name, the other way.
     */
    private Runnable pointerHook;

    void setPointerHook(Runnable hook) {
        pointerHook = hook;
    }

    /**
     * The roving focus onto the control a click landed on, so the keys carry on from there: Enter
     * on an arrow just clicked pages again, and the arrows walk the header from it. Only while the
     * keys are this grid's; a click on an arrow does not take the focus, so an unfocused grid's
     * cursor stays where the keyboard left it.
     */
    private void pointTo(Part to) {
        if (part == to || !(keyboardActive || isFocused())) {
            return;
        }
        Part from = part;
        part = to;
        damagePartChange(from, to);
        notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
    }

    /** The picker's: see {@link #tabLeavesAtEnds}. */
    void setTabLeavesAtEnds(boolean leaves) {
        tabLeavesAtEnds = leaves;
    }

    /**
     * The picker's: the keyboard arrives from the thing after this grid in the popup's Tab cycle,
     * walking backwards, so it lands on the grid, the last part of the popup's walk.
     */
    void enterFromEnd() {
        setKeyboardActive(true);
        notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
    }

    /**
     * The picker's: the keyboard comes round the popup's Tab cycle from the time row, so it lands
     * on the first part of the walk, the arrow that pages back.
     */
    void enterFromStart() {
        setKeyboardActive(true);
        Part from = part;
        part = Part.PREVIOUS;
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
     * <p>Public because it is announced: the {@code ACTIVE} aspect says the cursor moved, and a
     * widget may not announce a state no accessor answers.
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
        setView(wanted, Change.Origin.CODE);
        return this;
    }

    /**
     * The one path a view change takes, whoever asked for it: a caller's write arrives as
     * {@code CODE}, and the title, the choosers' keys, Escape and a picker backing out of a
     * chooser as {@code USER}.
     *
     * <p>Announced as {@code VALUE}, the aspect the month paging already announces: what the grid
     * shows moved, from a month of days to a year of months, and a watcher that heard nothing for
     * it heard the title's published state flip with no change to explain it. A view already
     * showing announces nothing, like every other write of what a widget already holds.
     */
    void setView(View wanted, Change.Origin origin) {
        Objects.requireNonNull(wanted, "view");
        if (wanted.compareTo(granularity) < 0) {
            throw new IllegalArgumentException("a calendar picking " + granularity
                    + " cannot show " + wanted + "; set the granularity first");
        }
        if (view == wanted) {
            return;
        }
        view = wanted;
        chooserCursor = -1;
        markNeedsLayout();
        notifyChange(Change.of(Change.Aspect.VALUE, origin));
        if (wanted != View.DAYS && focusHere(Part.GRID)) {
            // The grid a person is standing in now has a cell they are standing on, and it is the
            // one on show: effectiveChooserCursor answers it from this frame on, so the climb out
            // of the days is a cursor arriving and is announced as one. Arriving is not moving --
            // the cell is not stepped, only landed on -- which is the rule enterPart states for the
            // descent and this is the same rule for the climb.
            notifyChange(Change.of(Change.Aspect.ACTIVE, origin));
        }
    }

    /**
     * The chooser cell the cursor is on: the one an arrow put it on, or, until one has, <b>the
     * cell that matches what is on show</b> &mdash; the month of the month being drawn, the year of
     * the year block.
     *
     * <p>{@link #chooserCursor} is {@code -1} from the moment a view change until the first arrow,
     * and that number reached the paint and the describe pass. So after Ctrl (or Cmd) and Up
     * climbed out of the days the months were drawn with <b>no cursor at all</b>: no ring, and no
     * cell published {@link Accessible.State#ACTIVE}, so a reader following the active descendant
     * was told the grid had moved and not where it now stood, and heard the first month only after
     * an arrow that had already stepped past it.
     *
     * <p>Answered here rather than by writing the field at the view change, because the field is
     * the user's and the fallback is the calendar's: {@code setVisibleMonth} moves what is on show
     * and must move this with it, while paging with the cursor already placed must not. It is the
     * same value {@code chooserKey} writes into the field on the first arrow, read one frame
     * earlier.
     *
     * @return the cell index, or {@code -1} in {@link View#DAYS}, which has no chooser cell
     */
    private int effectiveChooserCursor() {
        if (view == View.DAYS) {
            return -1;
        }
        return chooserCursor >= 0 ? chooserCursor : Math.max(0, currentChooserCell());
    }

    /** @return the finest view this calendar picks in; {@link View#DAYS} unless it was changed */
    public View granularity() {
        return granularity;
    }

    /**
     * What this calendar picks: a day, a month or a year.
     *
     * <p>A {@link View#MONTHS} calendar is a month picker: it opens on the twelve months, a pick
     * there is the selection (the first day of the month, as an ISO date) rather than a step
     * down to days it cannot pick, and the title climbs only to the years and back. A
     * {@link View#YEARS} calendar is a year picker the same way. A period at either level runs
     * from the first day of its first month or year to the last day of its last,
     * so a range of March to June answers 1 March to 30 June.
     *
     * <p>Takes a {@link View} rather than the field's finer list on purpose: an hour is nothing a
     * grid can show, so an hour granularity here does not compile. {@link DatePicker} converts.
     *
     * <p>Changing the level drops the selection, for {@link #setSelectionMode}'s reason: a day is
     * not a month, and carrying one across as the other would be inventing a choice. A view finer
     * than the new level moves up to it, announced as {@code VALUE} with the origin
     * {@code ADJUSTMENT}: the calendar moved it, because the level moved.
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
        markNeedsLayout();
        if (view.compareTo(level) < 0) {
            // Through the one path a view change takes, so it is announced like the rest: the
            // grid stops showing days because the level moved, which is this widget's doing.
            setView(level, Change.Origin.ADJUSTMENT);
        }
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
        }, Change.Origin.USER);
    }

    /**
     * @return the ISO date of the first day of the month currently drawn, <b>in the calendar being
     *         drawn</b>: for a Hijri grid that is the first of the Hijri month, which is most
     *         often the middle of an ISO one
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
                // The month moved because today did: the calendar's own consequence of the call.
                notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.ADJUSTMENT));
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
     * shown, which is what made paging a no-op for most of the year until 2026-09-14.
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
        ChronoLocalDate stepped = current == null ? null
                : CalendarChronology.plus(current, months, ChronoUnit.MONTHS);
        // Past either end of the calendar's range the step is ISO's, and the grid there falls
        // back to drawing the ISO month (rebuildGrid), rather than a page that throws or sticks.
        LocalDate next = stepped == null ? visibleMonth().plusMonths(months)
                : CalendarChronology.iso(stepped);
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
     * <p>Announced as {@code RANGE}/{@code CODE} when it is a different filter; handing the same one
     * again is no change. Two different filters that happen to answer alike are still announced: the
     * calendar cannot know that without asking every day there is.
     *
     * @param filter answers whether a day may be picked, or {@code null} to allow every day
     * @return this
     */
    public CalendarView setDateFilter(Predicate<LocalDate> filter) {
        Ui.checkUiThread();
        if (filter == dateFilter) {
            return this;
        }
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
     * way.
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
     * The application's response to the user picking: a day in {@link SelectionMode#SINGLE}, a
     * closed period in {@link SelectionMode#RANGE}. The first click of a period reaches nothing:
     * a range with one end is not a range. The handler reads what it needs, {@link #selectedDate()}
     * or {@link #selectedRange()}, as every list-shaped widget's does.
     *
     * <p>The user alone: a {@link #setSelectedDate} from code never reaches it. Code that
     * wants every change, whoever made it, watches the widget with
     * {@link Widget#observeChanges}.
     *
     * @param listener what to run, or {@code null} to clear the slot
     * @return this
     * @throws IllegalStateException if a handler is already registered
     */
    public CalendarView onSelect(Runnable listener) {
        Ui.checkUiThread();
        this.onSelect = Checks.handlerSlot(onSelect, listener, "CalendarView.onSelect");
        return this;
    }

    /**
     * The handler, reached for a day in {@code SINGLE} and a closed period in {@code RANGE}.
     *
     * <p>Chains to {@code super} for every other aspect: that call is the link a subclass must
     * not break.
     */
    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.SELECTION) {
            boolean picked = selectionMode == SelectionMode.SINGLE
                    || (selectionMode == SelectionMode.RANGE && selectedRange != null);
            if (picked && onSelect != null) {
                onSelect.run();
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
        return pick(day, true, origin);
    }

    /** Whether the last pick was a reader's {@code SELECT}; see {@link #lastPickWasAClientWrite()}. */
    private boolean lastPickWasAClientWrite;

    /**
     * Whether the pick the handler is being told about came from a client's selection write
     * rather than from a click or Enter: the one pick that holds the cursor back. A
     * {@link DatePicker} reads it inside its handler to mark the day without committing: VoiceOver
     * writes {@code AXSelected} on every cell its cursor reaches, and a popup that committed on the
     * write closed under the user's arrow keys.
     *
     * @return whether the pick being handled was a client's write
     */
    boolean lastPickWasAClientWrite() {
        return lastPickWasAClientWrite;
    }

    /**
     * The same, with the cursor held back.
     *
     * @param moveCursor whether the keyboard cursor lands on the day: a click and Enter say yes,
     *                   because the pointer and the key are where the user is; a reader's
     *                   {@code SELECT} says no, because a client write is not. <b>One exception,
     *                   and it is geometry rather than policy:</b> where the pick pages the
     *                   calendar, the cursor follows the selection anyway, because the grid it was
     *                   standing in is the one being replaced and a cursor left behind would be on
     *                   a day the calendar no longer draws. Selecting a day of the month on show —
     *                   which is every day a reader can address without paging first — leaves it
     *                   where it was.
     */
    private boolean pick(LocalDate day, boolean moveCursor, Change.Origin origin) {
        if (!isSelectable(day)) {
            return false;
        }
        lastPickWasAClientWrite = !moveCursor;
        LocalDate cursorWas = cursor;
        LocalDate monthWas = visibleMonth;
        cursor = day;
        showMonth(day, Change.Origin.ADJUSTMENT);
        if (!moveCursor && cursorWas != null && monthWas.equals(visibleMonth)) {
            cursor = cursorWas;
        }
        switch (selectionMode) {
            case SINGLE -> {
                if (day.equals(selected) && !(origin == Change.Origin.USER && moveCursor)) {
                    // Nothing moved: the early return two bound calendars need. A click or an
                    // Enter on the day already selected goes on through, because a picker's
                    // popup commits on the handler and a reader's client write may have marked
                    // that day a moment before (decision 102, 2026-09-22): the day is announced
                    // again and the handler runs again, with nothing changed in the tree.
                    return true;
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
     * A pick in the chooser this calendar picks in: the month or the year is the selection. The
     * same two-step in {@code RANGE} as {@link #pick}, and the same seam &mdash; a click, Enter on
     * the cursor and an assistive technology's {@code SELECT} all land here &mdash; with a period
     * taken whole, from the first day of its first month or year to the last day of its last: a
     * range of March to June is 1 March to 30 June, in the calendar being drawn.
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
     * this calendar's granularity: the start is the first day of the earlier period and the end the
     * last day of the later one.
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
     * <p>A cursor step changes two cells and used to repaint the widget: measured at <b>50.4% of a
     * nine-hundred-point window</b> for one arrow key, which under a full-frame default costs
     * nothing and under a partial one is the difference between a cell and half a screen. The rect
     * is the cell's box grown by the focus ring's own reach, because the ring is what a cursor step
     * draws and it sits inside the cell by a gap rather than on its edge.
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
            damageCell(effectiveChooserCursor());
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
        float pad = Theme.of(this).tokensFor(this).spacingSmall();
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
                && gridView == view && gridGranularity == granularity
                && gridEraCalendar == eraCalendar) {
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
        gridGranularity = granularity;
        chooserText = buildChooserLabels(chronology, chronoFirst, locale, true);
        chooserName = buildChooserLabels(chronology, chronoFirst, locale, false);
        // The cell holding the month or year on show says so in its name (decision 48): it is
        // filled on screen, and a chooser being passed through has no selection to say it with.
        // Built here, with the labels, so the name is one string held between frames and a
        // NAME_CHANGED reaches a client when the cell on show moves. The chooser this calendar
        // picks in carries a real selection instead and gets no word.
        if (view != View.DAYS && !terminalChooser()) {
            int current = currentChooserCell();
            if (current >= 0 && current < chooserName.length) {
                chooserName[current] = chooserName[current] + ", " + DateStrings.ON_SHOW.get();
            }
        }
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
     * rather than {@code String.toUpperCase}'s, which turns a Turkish dotless i into the
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
     * ("2026", "2569", "1448"), and the era with it where it is not ("令和8", "民國115").
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
     * <p>Blocked by the <b>proleptic</b> year and not the year of era. Blocking by the year of era
     * made every Japanese block start at a year-of-era multiple of 24 &mdash; Reiwa 0, which is not
     * a year &mdash; and paging back from Reiwa's block landed on Heisei's with the years 2012 to
     * 2018 in neither. The proleptic year is the one number every chronology counts without a gap:
     * for ISO, Thai and Hijri it equals the year of era, for Minguo it is the ROC year, and for
     * Japanese it is the ISO year.
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
        // The month before the first month a chronology holds is a month it cannot make (AH
        // 1299 threw out of a frame); its leading cells are ISO days the calendar cannot name
        // anyway, and they are numbered against the ISO month's length instead.
        ChronoLocalDate previous = chronoFirst == null ? null
                : CalendarChronology.plus(chronoFirst, -1, ChronoUnit.MONTHS);
        if (previous != null) {
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

    /**
     * Scrolls whatever the calendar sits in until day cell {@code index} is in view: the same
     * rectangle the accessible walk publishes as that cell's bounds, so what a reader is shown is
     * what it was told to expect.
     */
    private void revealDayCell(int index) {
        boolean rtl = isRightToLeft();
        revealInView(cellLeft(index % DAYS_IN_WEEK, rtl), gridY + (index / DAYS_IN_WEEK) * cellH,
                cellW, cellH);
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
        SizeTokens t = Theme.of(this).tokensFor(this);
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
        SizeTokens t = Theme.of(this).tokensFor(this);
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
        Theme theme = Theme.of(this);
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
                        top + (cellH - fm.height()) / 2 + fm.ascent(), theme.textMuted());
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
                    canvas.fillRect(left, top, cellW, cellH, theme.primary().withAlpha(0.18f));
                }
                if (end) {
                    canvas.fillRoundRect(left + inset, top + inset, cellW - 2 * inset,
                            cellH - 2 * inset, radius, enabled ? theme.primary() : theme.disabledFill());
                } else if (day.equals(hover) && selectable) {
                    canvas.fillRoundRect(left + inset, top + inset, cellW - 2 * inset,
                            cellH - 2 * inset, radius, theme.surfaceRaised());
                }
                if (day.equals(today) && !end) {
                    // A ring, so today is legible under a band and under a hover alike.
                    canvas.drawRoundRect(left + inset, top + inset, cellW - 2 * inset,
                            cellH - 2 * inset, radius, Strokes.BORDER, theme.primary());
                }

                String text = dayText[index];
                ShapedText line = ruler.shape(text, body, ShapedText.Direction.of(text, neutral));
                Color ink = !selectable ? theme.disabledText()
                        : end ? theme.onPrimary()
                        : inMonth ? theme.text() : theme.textMuted();
                canvas.drawText(line, left + (cellW - line.metrics().width()) / 2,
                        top + (cellH - fm.height()) / 2 + fm.ascent(), ink);

                DayMark mark = dayMarks == null ? null : dayMarks.apply(day);
                if (mark != null) {
                    float dot = Math.max(1.5f, t.popupDotRadius());
                    canvas.fillCircle(left + cellW / 2, top + cellH - dot - t.spacingSmall() / 2,
                            dot, end ? theme.onPrimary() : mark.dot());
                }
                // Gated on the fade rather than on isFocused, so the ring keeps rendering while it
                // fades out: focus is already gone by then and the ring would otherwise blink off.
                float focus = focusFade.value();
                if (focus > 0.001f && day.equals(cursor)) {
                    float gap = Strokes.FOCUS_GAP_INDICATOR;
                    canvas.drawRoundRect(left + gap, top + gap, cellW - 2 * gap, cellH - 2 * gap,
                            radius, Strokes.FOCUS_RING_THIN, theme.focusRing().withAlpha(focus));
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
        int[] stood = terminal ? chooserStands() : null;
        float focus = focusFade.value();
        int columns = columns();
        for (int i = 0; i < chooserText.length && i < cellCount(); i++) {
            int row = i / columns;
            int column = i % columns;
            float left = cellLeft(column, rtl);
            float top = gridY + row * cellH;
            boolean offered = enabled && isChooserCellOffered(i);
            int stands = terminal ? stood[i] : i == current ? 2 : 0;
            if (stands == 1) {
                canvas.fillRect(left, top, cellW, cellH, theme.primary().withAlpha(0.18f));
            }
            if (stands == 2) {
                canvas.fillRoundRect(left + inset, top + inset, cellW - 2 * inset,
                        cellH - 2 * inset, radius, enabled ? theme.primary() : theme.disabledFill());
            } else if (i == hoverChooserCell && offered) {
                canvas.fillRoundRect(left + inset, top + inset, cellW - 2 * inset,
                        cellH - 2 * inset, radius, theme.surfaceRaised());
            }
            String text = chooserText[i];
            ShapedText line = ruler.shape(text, font, ShapedText.Direction.of(text, neutral));
            Color ink = !offered ? theme.disabledText() : stands == 2 ? theme.onPrimary() : theme.text();
            canvas.drawText(line, left + (cellW - line.metrics().width()) / 2,
                    top + (cellH - fm.height()) / 2 + fm.ascent(), ink);
            if (focus > 0.001f && i == effectiveChooserCursor()) {
                float gap = Strokes.FOCUS_GAP_INDICATOR;
                canvas.drawRoundRect(left + gap, top + gap, cellW - 2 * gap, cellH - 2 * gap,
                        radius, Strokes.FOCUS_RING_THIN, theme.focusRing().withAlpha(focus));
            }
        }
    }

    /**
     * {@link #periodSelection} for every cell of a terminal chooser, computed once per change of
     * what it reads rather than per cell per frame: each answer converts the cell through the
     * chronology and back, and the period's end once more, which for twenty-four cells was
     * near a hundred allocations a frame while the popup fades. What it reads is held beside
     * the answers and compared on every paint, so a stale answer is impossible by construction
     * rather than by every selection path remembering to clear it.
     */
    private int[] chooserStands() {
        int count = Math.min(chooserText.length, cellCount());
        if (chooserStands.length == count
                && Objects.equals(standsSelected, selected)
                && Objects.equals(standsRange, selectedRange)
                && Objects.equals(standsAnchor, rangeAnchor)
                && standsMonthFirst == monthFirstEpoch
                && standsView == view && standsGranularity == granularity
                && standsChronology == gridChronology && standsMode == selectionMode) {
            return chooserStands;
        }
        int[] stands = new int[count];
        for (int i = 0; i < count; i++) {
            stands[i] = periodSelection(i);
        }
        chooserStands = stands;
        standsSelected = selected;
        standsRange = selectedRange;
        standsAnchor = rangeAnchor;
        standsMonthFirst = monthFirstEpoch;
        standsView = view;
        standsGranularity = granularity;
        standsChronology = gridChronology;
        standsMode = selectionMode;
        return stands;
    }

    private int[] chooserStands = new int[0];
    private LocalDate standsSelected;
    private DateRange standsRange;
    private LocalDate standsAnchor;
    private long standsMonthFirst;
    private View standsView;
    private View standsGranularity;
    private Chronology standsChronology;
    private SelectionMode standsMode;

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
        return CalendarChronology.iso(CalendarChronology.plus(cell, length - 1, ChronoUnit.DAYS));
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
                    t.radiusSmall(), theme.surfaceRaised());
        }
        canvas.drawText(title, titleX, pad + (headerH - fm.height()) / 2 + fm.ascent(),
                enabled ? theme.text() : theme.disabledText());
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
                    t.radiusSmall(), Strokes.FOCUS_RING_THIN, theme.focusRing().withAlpha(focus));
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
                    theme.surfaceRaised());
        }
        Color ink = !enabled ? theme.disabledText() : hovered ? theme.text() : theme.textMuted();
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
                    top + (weekdayH - fm.height()) / 2 + fm.ascent(), theme.textMuted());
        }
        for (int c = 0; c < DAYS_IN_WEEK; c++) {
            String text = weekdayText[c];
            ShapedText shaped = ruler.shape(text, font, ShapedText.Direction.of(text, neutral));
            canvas.drawText(shaped,
                    cellLeft(c, rtl) + (cellW - shaped.metrics().width()) / 2,
                    top + (weekdayH - fm.height()) / 2 + fm.ascent(), theme.textMuted());
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
        SizeTokens t = Theme.of(this).tokensFor(this);
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
        SizeTokens t = Theme.of(this).tokensFor(this);
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
                if (pointerHook != null) {
                    pointerHook.run();
                }
                int paging = pagingAt(lx, ly, rtl);
                if (paging != 0) {
                    pointTo(paging < 0 ? Part.PREVIOUS : Part.NEXT);
                    page(paging);
                    return;
                }
                if (onTitle(lx, ly)) {
                    requestFocus();
                    pointTo(Part.TITLE);
                    climb();
                    return;
                }
                int cell = cellAt(lx, ly, rtl);
                if (cell < 0) {
                    return;
                }
                requestFocus();
                pointTo(Part.GRID);
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
                setView(View.MONTHS, Change.Origin.USER);
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
        setView(view == View.YEARS ? View.MONTHS : View.DAYS, Change.Origin.USER);
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
     * Moves the roving focus one step along {@link #TAB_ORDER}, or {@link #POPUP_TAB_ORDER} while
     * a picker drives the calendar.
     *
     * @param delta +1 forwards, -1 backwards
     * @return whether the calendar kept the key; {@code false} means the walk ran off an end and
     *         the key belongs to whatever encloses this widget
     */
    private boolean movePart(int delta) {
        Part[] order = keyboardActive ? POPUP_TAB_ORDER : TAB_ORDER;
        int at = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == part) {
                at = i;
                break;
            }
        }
        int next = at + delta;
        if (next < 0 || next >= order.length) {
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
            next = Math.floorMod(next, order.length);
        }
        Part from = part;
        part = order[next];
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
                // The cursor moved, so say so: Down announces the same move and Escape did not,
                // which left a watcher with a grid whose ACTIVE cell had moved and no change to
                // explain it.
                notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
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
                damageCell(effectiveChooserCursor());
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
                setView(View.YEARS, Change.Origin.USER); // the coarsest; from YEARS it stays
                event.consume();
                return;
            }
            if (event.key() == Keys.DOWN) {
                View below = view == View.YEARS ? View.MONTHS : View.DAYS;
                if (below.compareTo(granularity) >= 0) {
                    setView(below, Change.Origin.USER); // never below what this calendar picks in
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
                    setView(granularity, Change.Origin.USER); // out of a chooser, back to the finest view
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
        ChronoLocalDate stepped = current == null ? null
                : CalendarChronology.plus(current, months, ChronoUnit.MONTHS);
        LocalDate next = stepped == null ? from.plusMonths(months) // past the range: ISO's step
                : CalendarChronology.iso(stepped);
        if (next != null) {
            moveCursor(next, extend);
        }
    }

    // ------------------------------------------------------------------ accessibility

    /**
     * The grid as a table, which is what it is: {@code TABLE} carrying six rows and seven columns
     * (eight with week numbers), a header row of {@code COLUMN_HEADER}s, one {@code ROW} per week
     * and one {@code CELL} per day, plus the two paging buttons and the title, a {@code BUTTON}
     * that climbs to the month and year choosers &mdash; the same {@code TABLE}, three rows of
     * four months or six of four years, each cell carrying {@code SELECT} and {@code FOCUS} where
     * it leads somewhere ({@link #describeChooser}).
     *
     * <p><b>Every role here was already mapped for the {@code Table} widget</b>, three weeks before
     * this widget existed, and that is the whole accessibility cost of a calendar: no role is added
     * to the model, no facet, and no bridge code in {@code limn-backend-lwjgl}, where the three
     * bridges live.
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
        SizeTokens t = Theme.of(this).tokensFor(this);
        Locale locale = locale();
        boolean rtl = isRightToLeft();
        boolean days = view == View.DAYS;
        int columns = days && showWeekNumbers ? DAYS_IN_WEEK + 1 : columns();
        // The GRID shape's container half, written once (ADR 045 §3; decision 99).
        GridAccessibility.describeGrid(a, rows(), columns);
        // Single in every mode, RANGE included (decision 105, 2026-09-22): a range is a band and
        // not a set, no day offers ADD_TO_SELECTION or DESELECT, and a container that said
        // "multiple" promised a reader two verbs no member had. Until then RANGE published
        // multi-selectable, which the rows helper already contradicted by naming the selection
        // SINGLE on every day.
        a.selection(false, false);

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
        // No enabled test: the walk withdraws every verb from a node that is not ENABLED (ADR 039
        // §1.5, amended 2026-09-15), a disabled calendar's children included.
        if (climbed) {
            a.action(Accessible.Action.PRESS, Accessible.Action.COLLAPSE);
        } else if (granularity != View.YEARS) {
            a.action(Accessible.Action.PRESS, Accessible.Action.EXPAND);
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
            GridAccessibility.describeHeaderCell(a, column, false, CellFacet.Sort.NONE);
            a.name(DateStrings.WEEK_COLUMN, Accessible.NameFrom.CONTENT);
            a.endChild();
            column++;
        }
        for (int c = 0; c < DAYS_IN_WEEK; c++, column++) {
            a.child(KEY_HEAD_BASE - column);
            a.bounds(cellLeft(c, rtl), pad + headerH, cellW, weekdayH);
            GridAccessibility.describeHeaderCell(a, column, false, CellFacet.Sort.NONE);
            // The WHOLE weekday name and not the narrow letter that is drawn: a reader crossing the
            // header row would otherwise hear seven single letters, which name nothing.
            a.name(CalendarChronology.fullWeekday(weekdays[c], locale), textEpoch,
                    Accessible.NameFrom.CONTENT);
            a.endChild();
        }
        a.endChild();

        Chronology chronology = gridChronology;
        LocalDate today = today();
        for (int w = 0; w < WEEKS; w++) {
            float top = gridY + w * cellH;
            long weekStart = gridStartEpoch + (long) w * DAYS_IN_WEEK;
            a.child(KEY_WEEK_ROW_BASE + weekStart);
            a.bounds(0, top, width(), cellH);
            a.role(Accessible.Role.ROW);
            // A row is what it shows, and says so: the numbers drawn across it, in reading
            // order. A ROW published with an empty name is the shape a screen-reader run found
            // on a table -- NVDA speaks a bare 'item de dados' before many of these cells, and
            // Orca discards an unnamed row that is not focusable, selectable or expandable as
            // layout only. It is the week's own numbers rather than the seven full dates its
            // cells are named with, because the cell says the date and the row says the week.
            String rowName = weekRowName(w);
            if (rowName != null) {
                a.name(rowName, System.identityHashCode(rowName), Accessible.NameFrom.CONTENT);
            }
            int c = 0;
            if (showWeekNumbers) {
                a.child(KEY_WEEK_NUMBER_BASE + weekStart);
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
                // The day it shows and not the slot it sits in: this is the key a SELECT comes
                // back with, and the identity a paged month must not hand to another date.
                a.child(KEY_DAY_BASE + day.toEpochDay());
                a.bounds(cellLeft(d, rtl), top, cellW, cellH);
                // A day is a grid cell whose verbs are the rows shape's (below), so no FOCUS and
                // no cursor mark from the grid half.
                GridAccessibility.describeCell(a, w, c, false, false);
                ChronoLocalDate drawn = CalendarChronology.date(chronology, day);
                a.name(cellName(chronology, drawn, day, today, locale), textEpoch,
                        Accessible.NameFrom.CONTENT);
                boolean cursorHere = day.equals(cursor) && focusHere(Part.GRID);
                if (selectionMode != SelectionMode.NONE) {
                    // A day is a member of the calendar's selection: the ROWS shape, written
                    // once (ADR 045 §3), read against a grid whose members are cells (§1.2).
                    // Numbered as the day of its own month over that month's length, in the
                    // calendar being drawn (decision 37, 2026-09-14): "15 of 30", and a leading
                    // cell of the month before "31 of 31"; the flat index over the forty-two
                    // cells it replaces was the grid's geometry, which is not what a reader
                    // asked for when told "item 33 of 42". A range is a band and not a set, so
                    // no day offers ADD_TO_SELECTION or DESELECT, which is why the selection
                    // is named SINGLE here whatever the mode. FOCUS moves the cursor onto the
                    // day and selects nothing (decision 11, 2026-09-15): the cursor and the
                    // selection are two things in a calendar, and it is what Up and Down do —
                    // never on a day the bounds or the filter refuse, which carries no verb at
                    // all (decision 30). SCROLL_INTO_VIEW brings the cell into view through the
                    // nearest scrolling ancestor, since the grid does not scroll by itself
                    // (decision 81 of 2026-09-17); published on a refused day too, because the
                    // walk withdraws it with the rest when the day is narrowed. No PRESS: a day
                    // has no activation apart from being picked.
                    int dayOfMonth = drawn == null ? day.getDayOfMonth()
                            : drawn.get(ChronoField.DAY_OF_MONTH);
                    int monthLength = drawn == null ? day.lengthOfMonth() : drawn.lengthOfMonth();
                    RowsAccessibility.describeRow(a, RowsAccessibility.Offer.OWNED,
                            RowsAccessibility.Selection.SINGLE,
                            isSelectedEnd(day) || isInBand(day), dayOfMonth, monthLength,
                            false, false, cursorHere, false, !isRefused(day), true);
                    // A day the bounds or the filter refuse is published disabled and carries
                    // no verb (decision 30, 2026-09-14): the cursor stops on it, so a reader
                    // hears "unavailable" where the eye sees the muted number, and Enter is
                    // refused by pick(). Narrowing only -- Accessibility.disabled -- which is
                    // the one route a synthetic child has to be less enabled than its owner, and
                    // the walk withdraws SELECT from the node it narrowed, as it does from every
                    // day of a disabled calendar (ADR 039 §1.5, amended 2026-09-15).
                    if (!isSelectable(day)) {
                        a.disabled();
                    }
                } else {
                    // In NONE nothing is selectable and no day is narrowed, so a day is not a
                    // member of anything and only the cursor's verbs remain: FOCUS by the same
                    // bounds as above, SCROLL_INTO_VIEW, and the cursor mark.
                    if (!isRefused(day)) {
                        a.action(Accessible.Action.FOCUS);
                    }
                    a.action(Accessible.Action.SCROLL_INTO_VIEW);
                    if (cursorHere) {
                        a.state(Accessible.State.ACTIVE);
                    }
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
     * calendar picks in is the exception and carries a real one: there the month
     * <em>is</em> the value.
     */
    private void describeChooser(Accessibility a, boolean rtl) {
        int columns = columns();
        boolean terminal = terminalChooser() && selectionMode != SelectionMode.NONE;
        int count = Math.min(chooserText.length, cellCount());
        long rowBase = view == View.MONTHS ? KEY_MONTH_ROW_BASE : KEY_YEAR_ROW_BASE;
        long cellBase = chooserKeyBase();
        for (int row = 0; row < rows(); row++) {
            float top = gridY + row * cellH;
            a.child(rowBase - row);
            a.bounds(0, top, width(), cellH);
            a.role(Accessible.Role.ROW);
            // Named for what it shows, as a week row is: without it a reader crossing the chooser
            // hears a bare 'item de dados' before each cell and Orca discards the row.
            String rowName = chooserRowName(row, columns);
            if (rowName != null) {
                a.name(rowName, System.identityHashCode(rowName), Accessible.NameFrom.CONTENT);
            }
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= chooserText.length) {
                    break;
                }
                a.child(cellBase - index);
                a.bounds(cellLeft(column, rtl), top, cellW, cellH);
                GridAccessibility.describeCell(a, row, column, false, false);
                a.name(chooserName[index], textEpoch, Accessible.NameFrom.CONTENT);
                if (terminal) {
                    a.selectionItem(periodSelection(index) > 0, index + 1, count);
                }
                // SELECT descends (or picks, in the chooser this calendar picks in) and FOCUS only
                // moves the chooser's cursor there (decision 11, 2026-09-15); the walk withdraws
                // both from a cell narrowed here or inside a disabled calendar.
                a.action(Accessible.Action.SELECT, Accessible.Action.FOCUS);
                if (!isChooserCellOffered(index)) {
                    a.disabled(); // a month with no selectable day: decision 30's rule, one level up
                }
                // The cell on show is not marked with a state here: CHECKED is the toggle
                // facet's and the builder refuses it. Its name carries the word instead
                // (decision 48), built with the labels in rebuildGrid.
                if (index == effectiveChooserCursor() && focusHere(Part.GRID)) {
                    a.state(Accessible.State.ACTIVE);
                }
                a.endChild();
            }
            a.endChild();
        }
    }

    /**
     * A reader's {@code FOCUS} on a day: the cursor goes there and nothing is selected, the way an
     * arrow takes it there. A standalone calendar takes the focus first, because a cursor is the
     * focused widget's; a picker's grid is no tab stop and is already driven by its field while it
     * is open. Paging follows the cursor as it does for an arrow, so a leading day of the month
     * before turns the page.
     */
    private void focusDay(LocalDate day) {
        if (isFocusable() && !keyboardActive) {
            requestFocus();
        }
        Part from = part;
        if (from != Part.GRID) {
            part = Part.GRID;
            damagePartChange(from, part);
        }
        if (day.equals(cursor)) {
            if (from != Part.GRID) {
                notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
            }
            return;
        }
        moveCursor(day, false);
    }

    /** A reader's {@code FOCUS} on a chooser cell: the chooser's cursor goes there, no descent. */
    private void focusChooserCell(int index) {
        if (isFocusable() && !keyboardActive) {
            requestFocus();
        }
        Part from = part;
        int was = chooserCursor;
        part = Part.GRID;
        chooserCursor = index;
        if (from != Part.GRID) {
            damagePartChange(from, part);
        }
        damageCell(was);
        damageCell(index);
        if (from != Part.GRID || was != index) {
            notifyChange(Change.of(Change.Aspect.ACTIVE, Change.Origin.USER));
        }
    }

    /**
     * Whether the bounds or the filter refuse a day, whatever the selection mode: the rule that
     * takes a day's verbs away. {@link #isSelectable} is the same rule plus the
     * mode, and in {@code NONE} it refuses every day.
     */
    private boolean isRefused(LocalDate day) {
        return minDate != null && day.isBefore(minDate)
                || maxDate != null && day.isAfter(maxDate)
                || dateFilter != null && !dateFilter.test(day);
    }

    /** Where the cells of the chooser on show are keyed: the months' range or the years'. */
    private long chooserKeyBase() {
        return view == View.MONTHS ? KEY_MONTH_BASE : KEY_YEAR_BASE;
    }

    /**
     * The full date, plus what else is true of the day: that it is today, and whatever a mark says
     * about it. Built here rather than memoized: it is wanted by nothing else, and forty-two long
     * dates per layout pass would cost more than the tree does.
     */
    /**
     * A week row's name: the numbers drawn across it in reading order &mdash; the week number
     * where the column is shown, then the seven day numbers &mdash; separated by a space.
     *
     * <p><b>What it shows and not what its cells are called.</b> A day cell is named with the
     * whole localized date, so a composite of its cells' names would be seven full dates, ~160
     * characters, spoken before every cell announcement a cursor move into a new week makes; the
     * row draws seven numbers and that is what it says. The rule a table row follows is the same
     * rule &mdash; the text the row shows, in column order &mdash; and it lands differently here
     * because the two widgets draw different things.
     *
     * <p>Kept per row and compared against a reused builder, so a quiet frame allocates nothing
     * and republishes nothing; the string's identity is then the witness the builder wants. With
     * the row keyed by its week (this class's key-space note) the name of a given week never
     * moves, so paging raises no name change on a surviving row at all.
     *
     * @return the name, or {@code null} before a grid has been built
     */
    private String weekRowName(int w) {
        nameBuilder.setLength(0);
        if (showWeekNumbers) {
            appendCellText(weekText[w]);
        }
        for (int d = 0; d < DAYS_IN_WEEK; d++) {
            appendCellText(dayText[w * DAYS_IN_WEEK + d]);
        }
        return keptName(rowNames, w);
    }

    /** A chooser row's name: the months or years drawn across it, as {@link #weekRowName}. */
    private String chooserRowName(int row, int columns) {
        nameBuilder.setLength(0);
        for (int column = 0; column < columns; column++) {
            int index = row * columns + column;
            if (index >= chooserText.length) {
                break;
            }
            appendCellText(chooserText[index]);
        }
        return keptName(chooserRowNames, row);
    }

    private void appendCellText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (nameBuilder.length() > 0) {
            nameBuilder.append(' ');
        }
        nameBuilder.append(text);
    }

    /** The builder's text, reusing the string kept at {@code slot} when it reads the same. */
    private String keptName(String[] kept, int slot) {
        if (slot >= kept.length) {
            return nameBuilder.length() == 0 ? null : nameBuilder.toString();
        }
        if (nameBuilder.length() == 0) {
            kept[slot] = null;
            return null;
        }
        String was = kept[slot];
        if (was != null && was.contentEquals(nameBuilder)) {
            return was;
        }
        String fresh = nameBuilder.toString();
        kept[slot] = fresh;
        return fresh;
    }

    private String cellName(Chronology chronology, ChronoLocalDate drawn, LocalDate day,
                            LocalDate today, Locale locale) {
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
        // Named for what the button pages in the view on show: a month of days,
        // a year of months, a block of years. The chevron drawn says none of it.
        boolean previous = key == KEY_PREVIOUS;
        a.name(switch (view) {
            case DAYS -> previous ? DateStrings.PREVIOUS_MONTH : DateStrings.NEXT_MONTH;
            case MONTHS -> previous ? DateStrings.PREVIOUS_YEAR : DateStrings.NEXT_YEAR;
            case YEARS -> previous ? DateStrings.PREVIOUS_YEARS : DateStrings.NEXT_YEARS;
        }, Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS); // withdrawn by the walk while the calendar is disabled
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
     * {@code USER} seam. {@code FOCUS} on a day or a chooser cell takes the path an arrow takes
     * instead, and moves only the cursor.
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
                    setView(granularity, Change.Origin.USER); // straight back down, as Escape does
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
        if (view != View.DAYS) {
            long base = chooserKeyBase();
            long index = base - key;
            if (key > base || index >= chooserText.length
                    || action != Accessible.Action.SELECT && action != Accessible.Action.FOCUS) {
                return false;
            }
            if (!isChooserCellOffered((int) index)) {
                return false;
            }
            if (action == Accessible.Action.FOCUS) {
                focusChooserCell((int) index);
            } else {
                descend((int) index);
            }
            return true;
        }
        int cell = dayCellOf(key);
        if (cell >= 0) {
            // A day's verbs, by the rules of the ROWS shape (RowsAccessibility.performOnRow,
            // ADR 045 §3) over the calendar's own mechanisms in RowsHost.
            return RowsAccessibility.performOnRow(rowsHost, cell, action);
        }
        return false;
    }

    /**
     * The calendar's mechanisms as the rows shape drives them, over a day cell's index in the grid.
     * {@code SELECT} is {@link #pick} without the cursor move: the cursor stays where it is, which
     * a click's does not, because FOCUS is the verb that moves it and a client writing a selection
     * is not a person pointing at a day. {@code pick} refuses a day the grid refuses a click on, by
     * the same rule the pointer meets; what tells a reader no is that such a day publishes no
     * SELECT and is not ENABLED, so that false never reaches the platform. {@code FOCUS} is refused
     * on a refused day, which was published with no verb. {@code SCROLL_INTO_VIEW} reveals the
     * cell's own rectangle through the scrolling ancestors, not the calendar's box: the grid does
     * not scroll by itself, so what moves is the pane the calendar sits in, and a calendar wholly
     * in view moves nothing, which is the free verb's own reading and not a refusal. A range is a
     * band and not a set, so the selection is single to the shape whatever the mode, and a day has
     * no activation of its own.
     */
    private final class RowsHost implements RowsAccessibility.Host<Integer> {
        @Override
        public RowsAccessibility.Selection selection() {
            return selectionMode == SelectionMode.NONE
                    ? RowsAccessibility.Selection.NONE : RowsAccessibility.Selection.SINGLE;
        }

        @Override
        public boolean cursorIsTheSelection() {
            return false;
        }

        @Override
        public boolean rowsActivate() {
            return false;
        }

        @Override
        public boolean isSelected(Integer cell) {
            LocalDate day = dayAt(cell);
            return isSelectedEnd(day) || isInBand(day);
        }

        @Override
        public boolean canBeCursor(Integer cell) {
            return !isRefused(dayAt(cell));
        }

        @Override
        public boolean select(Integer cell, boolean moveCursor) {
            return pick(dayAt(cell), moveCursor, Change.Origin.USER);
        }

        @Override
        public void moveCursor(Integer cell) {
            focusDay(dayAt(cell));
        }

        @Override
        public void reveal(Integer cell) {
            revealDayCell(cell);
        }
    }

    private final RowsHost rowsHost = new RowsHost();

    /**
     * The grid cell a day key names, or {@code -1} for a key that is not a day of the grid on
     * show &mdash; a row, a week number, a chooser cell, or a day that has paged away since a
     * client read it. The last is the point of keying a cell by its day (this class's key-space
     * note, amended 2026-09-16): a verb for a day the grid no longer shows is refused, where a
     * slot key would have quietly performed it on whichever day now stands in that slot.
     */
    private int dayCellOf(long key) {
        if (key < KEY_DAY_BASE || gridStartEpoch == Long.MIN_VALUE) {
            return -1;
        }
        long index = key - KEY_DAY_BASE - gridStartEpoch;
        return index >= 0 && index < CELLS ? (int) index : -1;
    }
}
