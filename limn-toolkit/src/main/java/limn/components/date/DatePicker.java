package limn.components.date;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.components.DisplayMode;
import limn.components.SizeTokens;
import limn.components.Strokes;
import limn.components.Theme;
import limn.concurrent.Subscription;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.RoundRect;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.input.Keys;
import limn.internal.lang.Checks;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.CharEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.chrono.Chronology;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A date field with a calendar to pick from: the field is a real child, the calendar is a
 * {@link CalendarView} in a popup, and this class is the wiring between them.
 *
 * <pre>{@code
 * new DatePicker()                                   // a date, with a calendar
 * new DatePicker().setGranularity(MINUTE)            // a date and a time; the popup gains a time row
 * new DatePicker().setGranularity(MONTH)             // a month picker
 * DatePicker.ofRange()                               // a period: two fields, one calendar
 * DatePicker.ofRange().setGranularity(MONTH)         // a period of whole months
 * }</pre>
 *
 * <p><b>Composition rather than modes.</b> The typing, the segments and the validity are
 * {@link DateField}'s and are not reimplemented here; the grid, the bounds and the marks are
 * {@link CalendarView}'s. What this class owns is exactly three things: the trailing button, when
 * the popup is open, and keeping the two halves in step.
 *
 * <p>Because the field is a widget rather than a painted region, a period is two fields with the
 * caret moving between them on Tab, the clipboard works, and a screen reader walks a subtree that
 * was already described. The picker paints one box around them so it reads as one control.
 *
 * <p><b>The popup never takes focus in a window of its own</b>, which is the toolkit's popup
 * contract and not this widget's choice: the field keeps the keyboard, and the navigation keys are
 * forwarded to the grid while it is open, so Up, Down, PageUp, PageDown and Enter drive the
 * calendar and the digits still reach the segments. In the in-scene presentation the overlay holds
 * the focus instead and forwards every key and every character the same way, so the two
 * presentations type alike. At an hour granularity or finer the popup carries a time row under the
 * grid, and Tab cycles the keyboard between the grid, its header and that row.
 *
 * <p>The presentation follows {@link DisplayMode}: a window of its own where the platform can place
 * one, an overlay inside the owner window where it cannot &mdash; and an application that documents
 * itself with screenshots asks for {@link DisplayMode#IN_SCENE} on purpose, because a native popup
 * is absent from a capture of the window.
 */
public final class DatePicker extends Widget<DatePicker> {

    /** Breathing room kept from the work-area edge when clamping the popup. */
    private static final float EDGE_MARGIN = 8;


    private final DateField field;
    /** The second end of a period, or {@code null} for a picker that is not one. */
    private final DateField endField;
    private final CalendarView calendar = new CalendarView();
    /**
     * The clock under the grid, present while the granularity has a time of day.
     * A real {@link DateField} that never takes the focus: the popup contract keeps the keyboard
     * on the field that opened the popup, and this row is driven the way the grid is, by keys
     * and characters the picker hands over while {@link #timeRowActive}.
     */
    private DateField timeRow;
    /** Whether the popup's keyboard is on the time row rather than on the grid. */
    private boolean timeRowActive;
    /**
     * Whether a key or character is being handed to a field by this class: the field's own
     * delegate would otherwise hand it straight back here.
     */
    private boolean forwarding;

    private boolean open;
    /** Which of the two fields the calendar is writing into; always the first unless it is a range. */
    private boolean fillingEnd;
    private DisplayMode requested = DisplayMode.NATIVE_WINDOW;

    private NativeWindow popupWindow;
    private Scene popupScene;
    private PopupPanel popupPanel;
    private ScenePopup scenePopup;
    private float sceneFade;
    private Subscription blurHandle;
    private Subscription dismissHandle;
    private Subscription popupBlurHandle;

    /**
     * The trailing affordance, a real widget rather than a painted region.
     *
     * <p>It began as chrome this class drew, with a synthetic accessible child standing in for it.
     * That was wrong on the axis that matters most: <b>in this toolkit only a {@code Widget} is a
     * tab stop</b>, so a painted button is one a keyboard user can never reach. The function was
     * reachable another way (Alt+Down opens the calendar from the field), which is why it took a
     * pair of eyes on the running program to notice, but "reachable by another route" is not the
     * same as "this control works", and keyboard navigation is not an optional half of a widget.
     * As a child it is a tab stop, it draws a real focus ring, and it publishes itself instead of
     * being described by its owner.
     */
    private final CalendarButton button = new CalendarButton();
    private final Transition focusFade =
            new Transition(this).duration(Theme.of(this).animFocus).easing(Theme.of(this).animEasing);
    /** The trailing button's own ring, which fades on its own widget rather than on the box. */
    private final Transition buttonFocus =
            new Transition(this).duration(Theme.of(this).animFocus).easing(Theme.of(this).animEasing);

    private Runnable onSelect;
    /** Set while {@link #setRange} fills both fields, so the half-set period is not announced. */
    private boolean settingRange;

    /** A picker for a date. */
    public DatePicker() {
        this(new DateField(), null);
    }

    private DatePicker(DateField field, DateField endField) {
        DateStrings.ensureRegistered();
        this.field = field;
        this.endField = endField;
        setCursor(Cursor.DEFAULT);
        // The fields first and the affordance last, because children() is the Tab order: a person
        // types the date far more often than they open the calendar, so the text is what the first
        // Tab should land in and the button is where the second one goes.
        adopt(field);
        if (endField != null) {
            adopt(endField);
            // The end of a period answers the last day or instant of what it names (decision
            // 51): a month range's end field reads June and answers the 30th.
            endField.setPeriodEnd(true);
            calendar.setSelectionMode(CalendarView.SelectionMode.RANGE);
            field.setAccessibleName(DateStrings.RANGE_START);
            endField.setAccessibleName(DateStrings.RANGE_END);
        }
        add(button);
        button.observeChanges((widget, change) -> {
            if (change.aspect() == Change.Aspect.FOCUS) {
                refreshFocusRing();
                invalidate();
            }
        });
        // The grid in a popup is NOT a tab stop, and the accessibility gallery is what caught it:
        // the popup contract keeps focus on the field and forwards the navigation keys here, so a
        // focusable grid is a node the traversal can land on and a node with no name -- a reader
        // arriving at it hears "table" and nothing. A standalone CalendarView stays focusable,
        // because there it IS the control and an application names it. A reader is still told
        // where the cursor is (decision 5, 2026-09-14): the grid publishes its cursor ACTIVE
        // while this picker drives it, and the tree's effective focus resolves to it -- under
        // the overlay in the scene presentation, and across the popup relation into the
        // popup window's tree in the native one, where the field keeps the focus and its
        // caret yields (DateField.popupHoldsKeyboard).
        calendar.setFocusable(false);
        // The grid inherits the picker's step, direction and language through the tree in the
        // in-scene presentation and through the host link in the other; the link is set when the
        // popup is built, which is the only moment the panel has no parent.
        calendar.onSelect(() -> {
            if (calendar.selectionMode() == CalendarView.SelectionMode.RANGE) {
                calendarPickedRange(calendar.selectedRange());
            } else {
                calendarPicked(calendar.selectedDate());
            }
        });
    }

    /**
     * Wires one of the fields in: the picker follows its focus (so the box it paints can carry the
     * focus ring for a child that has none of its own) and its value (so the grid follows what was
     * typed).
     *
     * <p>Through {@code observeChanges} and not through the fields' handler slots, because those
     * belong to the application: a picker that took {@code onChange} for its own use would make
     * {@code picker.field().onChange(...)} throw for the caller who reached for it.
     */
    private void adopt(DateField member) {
        member.setChrome(false);
        add(member);
        member.observeChanges((widget, change) -> {
            switch (change.aspect()) {
                case FOCUS -> {
                    refreshFocusRing();
                    if (member.isFocused() && endField != null) {
                        fillingEnd = member == endField;
                        syncCalendarFromFields();
                    }
                    invalidate();
                }
                case VALUE -> {
                    if (settingRange) {
                        break; // setRange announces once, with the whole period
                    }
                    syncCalendarFromFields();
                    // Forwarded so a watcher on the picker does not have to know it has children.
                    notifyChange(Change.of(Change.Aspect.VALUE, change.origin()));
                }
                case VALIDITY -> notifyChange(Change.of(Change.Aspect.VALIDITY, change.origin()));
                default -> {
                }
            }
        });
        member.setKeyDelegate(this::interceptKey);
        member.setCharDelegate(this::interceptChar);
        // The field is where a reader opens the calendar from (decision 18); opening from the
        // end of a period fills that end, whether or not the focus got there first. While the
        // popup is an overlay of the scene the walk takes the field's closing verb off it, with
        // every verb beneath the layer that owns input, and the overlay's CANCEL closes it there.
        member.setPopup(() -> open, wanted -> {
            if (wanted && endField != null) {
                fillingEnd = member == endField;
            }
            setOpen(wanted, Change.Origin.USER);
        });
    }

    /** The field the calendar and the time row are writing into. */
    private DateField filling() {
        return fillingEnd && endField != null ? endField : field;
    }

    /**
     * The box this class paints carries the focus ring for every member inside it, because none of
     * them draws one of its own: the fields are chromeless and the button is inside the same box.
     */
    private void refreshFocusRing() {
        focusFade.to(field.isFocused() || button.isFocused()
                || endField != null && endField.isFocused() ? 1 : 0);
    }

    /**
     * How fine this picker goes, on its field (or both ends of a period) and on its calendar at
     * once. {@link DateField.Granularity#MONTH} makes a month picker: the field shows a month and a
     * year and the popup opens on the twelve months, where a pick is the selection.
     * {@link DateField.Granularity#YEAR} likewise. {@link DateField.Granularity#HOUR} and finer
     * keep the day grid and add a time row under it.
     *
     * <p>Set here and not on {@link #field()}: the calendar has to follow, and a field told alone
     * would leave a month picker opening on days it cannot pick.
     *
     * @param level how fine to go
     * @return this
     */
    public DatePicker setGranularity(DateField.Granularity level) {
        Ui.checkUiThread();
        Objects.requireNonNull(level, "granularity");
        if (field.granularity() == level) {
            return this;
        }
        // A card already showing was built for the old level -- its time row is a child added
        // when the card was made, and a row that goes or arrives with the level would be left
        // on it, or off it, until the next open. So the popup is closed and reopened around the
        // change, through the same state machine a person's close-then-open takes (and the
        // release of the grid that path already handles), rather than patched in place.
        boolean showing = open;
        if (showing) {
            setOpen(false, Change.Origin.CODE);
        }
        field.setGranularity(level);
        if (endField != null) {
            endField.setGranularity(level);
        }
        calendar.setGranularity(switch (level) {
            case YEAR -> CalendarView.View.YEARS;
            case MONTH -> CalendarView.View.MONTHS;
            default -> CalendarView.View.DAYS; // an hour is nothing a grid can show
        });
        if (level.compareTo(DateField.Granularity.HOUR) >= 0) {
            if (timeRow == null) {
                timeRow = DateField.ofTime();
                timeRow.setFocusable(false);
                timeRow.setAccessibleName(DateStrings.TIME_OF_DAY);
                timeRow.setClock(clock);
                timeRow.observeChanges((widget, change) -> {
                    if (change.aspect() == Change.Aspect.VALUE && !syncingTimeRow) {
                        // Into the field through the row's own origin, so a person typing into
                        // the row reaches the application's handler as if they had typed into
                        // the field; a write from code is a no-op there, the value being equal.
                        filling().writeTime(timeRow.time(), change.origin());
                    }
                });
            }
            timeRow.setGranularity(level);
        } else {
            timeRow = null;
        }
        calendar.setTabLeavesAtEnds(timeRow != null);
        syncCalendarFromFields();
        if (showing) {
            setOpen(true, Change.Origin.CODE);
        }
        return this;
    }

    /** @return how fine this picker goes; {@link DateField.Granularity#DAY} unless it was changed */
    public DateField.Granularity granularity() {
        return field.granularity();
    }

    /** The clock handed to every part, kept so a time row built later gets it too. */
    private Clock clock;
    /** Guards the time row's own announcement while the picker is writing into it. */
    private boolean syncingTimeRow;

    /**
     * A picker for a period: two fields with the calendar between them in range mode. Focus in the
     * first field anchors the grid on the start and focus in the second on the end, and the grid
     * writes back into whichever end is being filled.
     *
     * @return the picker
     */
    public static DatePicker ofRange() {
        return new DatePicker(new DateField(), new DateField());
    }

    /** @return the field this picker types into; the first of the two for a period */
    public DateField field() {
        return field;
    }

    /** @return the second field of a period, or {@code null} for a picker that is not one */
    public DateField endField() {
        return endField;
    }

    /**
     * @return the grid this picker opens. Reach through it for anything the picker does not repeat:
     *         {@link CalendarView#setShowWeekNumbers}, {@link CalendarView#setDayMarks},
     *         {@link CalendarView#setFirstDayOfWeek}
     */
    public CalendarView calendar() {
        return calendar;
    }

    // ------------------------------------------------------------------ the value

    /** @return the date, or {@code null} while the field is incomplete */
    public LocalDate date() {
        return field.date();
    }

    /** @return the time of day, or {@code null} unless this picker carries one and it is complete */
    public LocalTime time() {
        return field.time();
    }

    /** @return date and time together, or {@code null} unless both are complete */
    public LocalDateTime dateTime() {
        return field.dateTime();
    }

    /**
     * @return the period, or {@code null} unless this is a range picker with both ends filled
     */
    public DateRange range() {
        LocalDate start = field.date();
        LocalDate end = endField == null ? null : endField.date();
        return start == null || end == null ? null : DateRange.of(start, end);
    }

    /**
     * Sets the date. Reaches the field, which announces it, which is what this picker forwards.
     *
     * @param date the date, or {@code null} to clear
     * @return this
     */
    public DatePicker setDate(LocalDate date) {
        Ui.checkUiThread();
        field.setDate(date);
        return this;
    }

    /**
     * Sets date and time at once.
     *
     * @param value the moment, or {@code null} to clear
     * @return this
     */
    public DatePicker setDateTime(LocalDateTime value) {
        Ui.checkUiThread();
        field.setDateTime(value);
        return this;
    }

    /**
     * Sets the period, filling both fields.
     *
     * @param range the period, or {@code null} to clear both ends
     * @return this
     * @throws IllegalStateException if this picker is not a range picker
     */
    public DatePicker setRange(DateRange range) {
        Ui.checkUiThread();
        if (endField == null) {
            throw new IllegalStateException("this DatePicker has one field; build it with ofRange()");
        }
        DateRange was = range();
        // Both ends, then one announcement: filling the fields one at a time announced twice, the
        // first time with a period that was half the old one and half the new.
        settingRange = true;
        try {
            field.setDate(range == null ? null : range.start());
            endField.setDate(range == null ? null : range.end());
        } finally {
            settingRange = false;
        }
        syncCalendarFromFields();
        calendar.setSelectedRange(range);
        if (!java.util.Objects.equals(was, range())) {
            notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.CODE));
        }
        return this;
    }

    // ------------------------------------------------------------------ bounds, fanned out

    /**
     * The earliest acceptable date, on the field <b>and</b> the grid: the two enforce it at
     * different moments (the grid refuses a click, the field holds a typed date and says it is
     * invalid), and setting it in one place is what keeps them from disagreeing.
     *
     * @param date the bound, or {@code null} for none
     * @return this
     */
    public DatePicker setMinDate(LocalDate date) {
        Ui.checkUiThread();
        field.setMinDate(date);
        if (endField != null) {
            endField.setMinDate(date);
        }
        calendar.setMinDate(date);
        return this;
    }

    /**
     * The latest acceptable date, on the field and the grid alike.
     *
     * @param date the bound, or {@code null} for none
     * @return this
     */
    public DatePicker setMaxDate(LocalDate date) {
        Ui.checkUiThread();
        field.setMaxDate(date);
        if (endField != null) {
            endField.setMaxDate(date);
        }
        calendar.setMaxDate(date);
        return this;
    }

    /**
     * Which dates are acceptable, on the field and the grid alike. The grid calls it once per
     * painted cell, so the obligation {@link CalendarView#setDateFilter} states &mdash; cheap, and
     * pure &mdash; is the one that binds here.
     *
     * @param filter the filter, or {@code null} to accept every date
     * @return this
     */
    public DatePicker setDateFilter(Predicate<LocalDate> filter) {
        Ui.checkUiThread();
        field.setDateFilter(filter);
        if (endField != null) {
            endField.setDateFilter(filter);
        }
        calendar.setDateFilter(filter);
        return this;
    }

    /**
     * The calendar system the field and the grid are drawn in; the value stays ISO.
     *
     * @param chronology the calendar, or {@code null} to follow the language
     * @return this
     */
    public DatePicker setChronology(Chronology chronology) {
        Ui.checkUiThread();
        field.setChronology(chronology);
        if (endField != null) {
            endField.setChronology(chronology);
        }
        calendar.setChronology(chronology);
        return this;
    }

    /**
     * Where the picker's parts read today from: the calendar's ring and opening month, and the
     * fields' first step on an empty segment. See {@link CalendarView#setClock}.
     *
     * @param clock the clock, or {@code null} for the system's
     * @return this
     */
    public DatePicker setClock(Clock clock) {
        Ui.checkUiThread();
        this.clock = clock;
        field.setClock(clock);
        if (endField != null) {
            endField.setClock(clock);
        }
        if (timeRow != null) {
            timeRow.setClock(clock);
        }
        calendar.setClock(clock);
        return this;
    }

    // ------------------------------------------------------------------ the popup

    /** @return whether the calendar is showing */
    public boolean isOpen() {
        return open;
    }

    /**
     * Asks for a presentation of the calendar; see {@link DisplayMode}. Default
     * {@link DisplayMode#NATIVE_WINDOW}, so the grid can extend past the window's edge the way a
     * platform picker's does.
     *
     * @param mode what to ask for
     * @return this
     */
    public DatePicker setDisplayMode(DisplayMode mode) {
        Ui.checkUiThread();
        requested = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /** @return how the open popup is actually presented, or {@code null} while it is closed */
    public DisplayMode displayMode() {
        if (!open) {
            return null;
        }
        return scenePopup != null ? DisplayMode.IN_SCENE : DisplayMode.NATIVE_WINDOW;
    }

    /** Opens the calendar. Does nothing if it is already open or the picker is disabled. */
    public DatePicker open() {
        Ui.checkUiThread();
        setOpen(true, Change.Origin.CODE);
        return this;
    }

    /** Closes the calendar. */
    public DatePicker close() {
        Ui.checkUiThread();
        setOpen(false, Change.Origin.CODE);
        return this;
    }

    private void setOpen(boolean wanted, Change.Origin origin) {
        Ui.checkUiThread();
        if (open == wanted || wanted && !isEnabled()) {
            return;
        }
        open = wanted;
        if (wanted) {
            syncCalendarFromFields();
            timeRowActive = false;
            calendar.setKeyboardActive(true);
            present();
        } else {
            timeRowActive = false;
            calendar.setKeyboardActive(false);
            if (timeRow != null) {
                timeRow.setKeyboardActive(false);
            }
            field.setKeyboardActive(false);
            if (endField != null) {
                endField.setKeyboardActive(false);
            }
            dismiss();
        }
        invalidate();
        notifyChange(Change.of(Change.Aspect.EXPANDED, origin));
    }

    /** Points the grid at what the fields hold, without disturbing what the person is typing. */
    private void syncCalendarFromFields() {
        if (timeRow != null) {
            syncingTimeRow = true;
            try {
                timeRow.setTime(filling().time());
            } finally {
                syncingTimeRow = false;
            }
        }
        if (endField != null) {
            DateRange picked = range();
            if (picked != null) {
                calendar.setSelectedRange(picked);
            }
            LocalDate anchor = filling().date();
            if (anchor != null) {
                calendar.setVisibleMonth(anchor);
            }
            return;
        }
        LocalDate date = field.date();
        calendar.setSelectedDate(date);
    }

    /**
     * The grid picked a day: it goes into whichever field is being filled, and the popup closes --
     * unless the pick was a reader's selection write, which marks the day in the grid and commits
     * nothing: a click and Enter still commit, and Enter on the marked day reaches here because the
     * calendar no longer swallows a user's pick of the day already selected.
     */
    private void calendarPicked(LocalDate day) {
        if (day == null || calendar.lastPickWasAClientWrite()) {
            return;
        }
        field.setDate(day);
        closeTo(field, Change.Origin.USER);
        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.USER));
    }

    private void calendarPickedRange(DateRange picked) {
        if (picked == null || endField == null || calendar.lastPickWasAClientWrite()) {
            return; // a client's write marks, as above (decision 102)
        }
        field.setDate(picked.start());
        endField.setDate(picked.end());
        closeTo(fillingEnd ? endField : field, Change.Origin.USER);
        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.USER));
    }

    /**
     * The field to hand the focus to once the popup is actually gone; see {@link #closeTo}.
     * Taken by {@link #dismiss} when the removal is deferred to the end of a fade, and
     * performed by {@link #closeTo} itself otherwise.
     */
    private DateField refocus;

    /**
     * Closes the popup and puts the focus on a field <em>once the popup is actually gone</em>.
     *
     * <p>In the scene presentation the overlay is removed at the end of its fade-out, and a
     * focus request made while it is still the top layer is refused: the scene confines the
     * focus to the layer that owns input. So the request that used to follow the close was
     * refused in every real window, and the overlay's removal then returned the focus to
     * whatever held it when the overlay was pushed &mdash; the calendar button, when the
     * popup was opened from it with Tab and Enter &mdash; rather than to the field the pick
     * had just filled. Headless, and in a window of its own, nothing defers the removal and
     * the request is made here.
     */
    private void closeTo(DateField target, Change.Origin origin) {
        refocus = target;
        setOpen(false, origin);
        DateField pending = refocus;
        refocus = null;
        if (pending != null) {
            pending.requestFocus();
        }
    }

    /**
     * The application's response to the user choosing: a date typed or picked from the grid, the
     * end of a period, or a time of day. The handler reads what it needs, {@link #date()},
     * {@link #range()} or {@link #dateTime()}.
     *
     * @param listener what to run, or {@code null} to clear the slot
     * @return this
     * @throws IllegalStateException if a handler is already registered
     */
    public DatePicker onSelect(Runnable listener) {
        Ui.checkUiThread();
        this.onSelect = Checks.handlerSlot(onSelect, listener, "DatePicker.onSelect");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.VALUE) {
            if (onSelect != null) {
                onSelect.run();
            }
            return;
        }
        super.handleUserChange(aspect);
    }

    // ------------------------------------------------------------------ keys, forwarded

    /**
     * The keys the picker takes before its field sees them.
     *
     * <p>While the popup is open the grid owns the navigation keys and the field keeps the digits,
     * which is what lets somebody arrow to a day and still type over it. Alt+Down opens; Escape
     * comes straight back to the finest view from a chooser (the years go to the days in one
     * press, not one level at a time) and closes from there; neither reaches the field.
     */
    private void interceptKey(KeyEvent event) {
        if (!event.isPressed() || forwarding) {
            return;
        }
        boolean alt = (event.modifiers() & Keys.MOD_ALT) != 0;
        if (!open) {
            if (alt && event.key() == Keys.DOWN || event.key() == Keys.F4) {
                setOpen(true, Change.Origin.USER);
                event.consume();
            }
            return;
        }
        if (timeRowActive) {
            timeRowKey(event);
            return;
        }
        switch (event.key()) {
            case Keys.ESCAPE -> {
                // Escape backs out of a chooser to the finest view the calendar picks in -- the
                // days, or the months of a month picker -- and only then out of the popup. A
                // single Escape that closed the whole thing from inside a chooser would throw
                // away the navigation as well as the popup.
                if (calendar.view() != calendar.granularity()) {
                    calendar.setView(calendar.granularity(), Change.Origin.USER);
                    repaintPopup();
                } else {
                    setOpen(false, Change.Origin.USER);
                }
                event.consume();
            }
            case Keys.TAB -> {
                // Forwarded so the grid's own parts -- the two paging arrows and the title --
                // are reachable while the field holds the focus. The calendar declines the key
                // when the walk runs off an end: with a time row under the grid that is where
                // the keyboard goes next (decision 19), and without one it is the signal to let
                // go -- the popup closes and the Tab does what it would have done, which is move
                // to the next control in the form.
                calendar.onKeyEvent(event);
                if (event.isConsumed()) {
                    repaintPopup();
                } else if (timeRow != null) {
                    enterTimeRow(event);
                } else {
                    setOpen(false, Change.Origin.USER);
                }
            }
            case Keys.UP, Keys.DOWN, Keys.LEFT, Keys.RIGHT, Keys.HOME, Keys.END,
                 Keys.PAGE_UP, Keys.PAGE_DOWN, Keys.ENTER, Keys.SPACE -> {
                // Straight into the grid's own handler, which is the path its keyboard already
                // takes: same clamping, same paging, same USER seam. Same package, so this is the
                // method the scene would have called had the grid held the focus.
                calendar.onKeyEvent(event);
                event.consume();
                repaintPopup();
            }
            default -> {
            }
        }
    }

    /**
     * The keyboard while it is on the popup's time row: the row's own segments take the arrows,
     * the digits and the two deleting keys exactly as a focused field would; Tab and Shift+Tab
     * carry on round the cycle to the grid and its header; Enter and Escape close the popup, the
     * time already written into the field as it was typed.
     */
    private void timeRowKey(KeyEvent event) {
        switch (event.key()) {
            case Keys.TAB -> {
                boolean shift = (event.modifiers() & Keys.MOD_SHIFT) != 0;
                leaveTimeRow();
                if (shift) {
                    calendar.enterFromEnd();
                } else {
                    calendar.setKeyboardActive(true);
                }
                event.consume();
                repaintPopup();
            }
            case Keys.ESCAPE, Keys.ENTER -> {
                closeTo(filling(), Change.Origin.USER);
                event.consume();
            }
            case Keys.UP, Keys.DOWN, Keys.LEFT, Keys.RIGHT, Keys.HOME, Keys.END,
                 Keys.DELETE, Keys.BACKSPACE -> {
                timeRow.onKeyEvent(event);
                event.consume();
                repaintPopup();
            }
            default -> {
            }
        }
    }

    private void enterTimeRow(KeyEvent tab) {
        calendar.setKeyboardActive(false);
        timeRowActive = true;
        timeRow.setKeyboardActive(true);
        // The caret is not drawn in the field while the row has it: one caret at a time.
        filling().setKeyboardActive(false);
        tab.consume();
        repaintPopup();
    }

    private void leaveTimeRow() {
        timeRowActive = false;
        timeRow.setKeyboardActive(false);
        if (scenePopup != null) {
            filling().setKeyboardActive(true);
        }
    }

    /**
     * The characters, before the field sees them: while the popup's time row holds the keyboard
     * the digits are the row's. Everything else falls through to the field that owns the caret,
     * which is what makes a day typable over an open calendar.
     */
    private void interceptChar(CharEvent event) {
        if (forwarding || !open || !timeRowActive) {
            return;
        }
        timeRow.onCharTyped(event);
        event.consume();
        repaintPopup();
    }

    /**
     * A key the in-scene overlay received, handed to the field that would have had it in a
     * window of its own: the overlay holds the focus there and the field does not, and a
     * Backspace, a paste or a digit that died at the overlay's root was the defect this answers.
     * Guarded so the field's own delegate does not hand it straight back.
     */
    private void forwardToField(KeyEvent event) {
        forwarding = true;
        try {
            filling().onKeyEvent(event);
        } finally {
            forwarding = false;
        }
    }

    private void forwardToField(CharEvent event) {
        forwarding = true;
        try {
            filling().onCharTyped(event);
        } finally {
            forwarding = false;
        }
    }

    /**
     * Asks the popup to draw again after a key it was handed.
     *
     * <p><b>Not a layout.</b> This asked for one, and a layout pass is a full frame by the
     * toolkit's structural invariant -- a widget that moved cannot damage where it used to be -- so
     * every forwarded arrow key repainted the entire window. Measured: a cursor step inside an open
     * popup was three full frames and one of one per cent, where the one per cent was the grid
     * correctly damaging two cells and the rest was this call throwing that away.
     *
     * <p>Nothing here needs a layout. The grid damages what it changed, and the popup's box is
     * deliberately the same in every view (its measure asks for the day grid's width whatever is
     * showing), so climbing to a chooser does not resize it either. Where a layout IS needed the
     * widget that needs it asks: {@code CalendarView.setView} marks itself, and that propagates.
     */
    private void repaintPopup() {
        // Only the window, and only because a second window has a frame loop of its own that this
        // scene's damage does not reach. Nothing else: the grid damaged whatever it changed, and
        // the in-scene overlay is the size of the WHOLE SCENE, so invalidating it turned every
        // forwarded arrow key into a full-window repaint -- measured at two full frames per key
        // against the half per cent the grid had just asked for.
        if (popupWindow != null) {
            popupWindow.requestFrame();
        }
    }

    // ------------------------------------------------------------------ presentation

    private void present() {
        Scene scene = scene();
        if (scene == null) {
            return; // headless: the state machine is the whole of what a test drives
        }
        blurHandle = scene.observeWindowBlur(() -> Ui.post(this::closeUnlessRefocused));
        if (requested == DisplayMode.IN_SCENE
                || (scene.window() != null && !scene.window().supportsAbsolutePositioning())) {
            presentInScene(scene);
            return;
        }
        dismissHandle = scene.observePresses(target -> {
            if (target != this && target != field && target != endField) {
                setOpen(false, Change.Origin.USER);
            }
        });
        if (scene.window() != null && popupWindow == null) {
            // Deferred: creating a window switches the GL context, which must not happen in the
            // middle of another window's frame callback.
            Ui.post(this::createPopupWindow);
        }
    }

    /**
     * Takes the grid back from whatever is still holding it.
     *
     * <p><b>The grid is one widget and a widget has one parent.</b> Each presentation builds a
     * fresh panel and that panel adopts the grid, so a second open finds the first panel still
     * holding it and {@code add} refuses -- correctly, since silently reparenting is how a widget
     * ends up in two trees. The failure was invisible in the worst way: the throw happens inside a
     * click, the scene contains it, {@code open} had already been set, and the picker then reports
     * itself open with nothing drawn. Every later click toggles a popup that never appears.
     *
     * <p>The normal path releases it when the fade-out finishes; this call is what makes an open
     * that arrives BEFORE that fade has finished work anyway, which is exactly the fast
     * close-then-open a person does. The old card then fades out empty for a few milliseconds,
     * which is the right trade: the popup being built is the one the user is looking at.
     */
    private void releaseCalendar() {
        if (calendar.parent() instanceof PopupPanel holder) {
            holder.drop(calendar);
        }
    }

    private void presentInScene(Scene owner) {
        releaseCalendar();
        popupPanel = new PopupPanel();
        // Hosted by the FIELD being filled, not by this composite (decisions 18 and 55,
        // 2026-09-14): the host is also the opener the walk names in the popup's POPUP_FOR and
        // mirrors as CONTROLLER_FOR, and the node a reader is at is the field -- a single
        // picker's own group is no node at all. Every inherited axis resolves through the field
        // to this picker exactly as it did, and decision 5's cursor lookup starts from the
        // focused node, which in a window of its own is the field.
        popupPanel.setInheritanceHost(filling());
        scenePopup = new ScenePopup(popupPanel);
        // The link goes on the OVERLAY as well, and it is the overlay's that does the work: a host
        // link on a widget that has a parent loses to the tree, and here the panel's parent is the
        // overlay. Without it the grid resolves the scene's defaults instead of the picker's, and
        // opens at a different density, direction or language from the field that opened it.
        scenePopup.setInheritanceHost(filling());
        boolean animate = owner.window() != null;
        sceneFade = animate ? 0f : 1f;
        // Before the overlay takes the focus: the field keeps its caret drawn and published,
        // because that is still where the digits land, and a field that knows a picker is aiming at
        // it does not treat the loss of focus as somebody moving on (a half-typed year is not
        // resolved by the popup opening).
        filling().setKeyboardActive(true);
        owner.pushOverlay(scenePopup);
        if (animate) {
            ScenePopup fading = scenePopup;
            owner.addRealTimeTicker(dt -> {
                if (!open) {
                    return false;
                }
                sceneFade = (float) Math.min(1, sceneFade + dt / Theme.of(this).animWindow);
                fading.invalidate();
                return sceneFade < 1;
            });
        }
    }

    private void createPopupWindow() {
        Scene scene = scene();
        if (!open || popupWindow != null || scene == null || scene.window() == null
                || scene.window().isClosed()) {
            return;
        }
        NativeWindow parent = scene.window();
        SizeTokens t = Theme.of(this).tokensFor(this);
        float gap = t.popupGap();
        float factor = parent.logicalToScreenFactor();
        Size content = popupContentSize();

        int screenX = parent.screenX() + Math.round(localToSceneX() * factor);
        int anchorTop = parent.screenY() + Math.round(localToSceneY() * factor);
        int anchorBottom = parent.screenY()
                + Math.round((localToSceneY() + height() + gap) * factor);
        limn.backend.ScreenRect area = parent.display() != null ? parent.display().workArea() : null;
        float spaceBelow = area != null
                ? (area.bottom() - anchorBottom) / factor - EDGE_MARGIN : content.height();
        float spaceAbove = area != null
                ? (anchorTop - area.y()) / factor - gap - EDGE_MARGIN : content.height();
        // A calendar does not scroll and cannot be clamped shorter: it flips above when it does not
        // fit below and there is more room there, and otherwise opens downward and is allowed to
        // run past the work area, which is what every platform picker does with a short screen.
        boolean above = content.height() > spaceBelow && spaceAbove > spaceBelow;

        popupWindow = parent.backend().createWindow(WindowConfig.popup(
                Math.max(1, Math.round(content.width())),
                Math.max(1, Math.round(content.height()))));
        parent.registerChildPopup(popupWindow);
        releaseCalendar();
        popupPanel = new PopupPanel();
        popupPanel.setInheritanceHost(filling()); // the opener a reader sees: see presentInScene
        // On the owner's clock, so the fade-out that destroys this window advances with it
        // (Scene#clock).
        popupScene = new Scene(popupPanel, scene.clock());
        popupScene.inheritRenderingFlags(scene);
        popupScene.bind(popupWindow);
        popupBlurHandle = popupScene.observeWindowBlur(() -> Ui.post(this::closeUnlessRefocused));
        popupScene.setBackground(Color.TRANSPARENT);
        int screenY = above
                ? anchorTop - Math.round((gap + content.height()) * factor)
                : anchorBottom;
        popupWindow.setScreenPosition(screenX, screenY);
        if (parent.isVisible()) {
            popupScene.fadeWindowIn(Theme.of(this).animWindow);
            popupWindow.show();
        }
        popupWindow.requestFrame();
    }

    /** The popup's box: the grid's own measurement, the time row's under it, and the panel's padding. */
    private Size popupContentSize() {
        SizeTokens t = Theme.of(this).tokensFor(this);
        float pad = t.popupPadV();
        Size grid = calendar.measure(Constraints.loose(Float.POSITIVE_INFINITY,
                Float.POSITIVE_INFINITY));
        float width = grid.width();
        float height = grid.height();
        if (timeRow != null) {
            Size row = timeRow.measure(Constraints.loose(Float.POSITIVE_INFINITY,
                    Float.POSITIVE_INFINITY));
            width = Math.max(width, row.width());
            height += t.spacingSmall() + row.height();
        }
        return new Size(width + 2 * pad, height + 2 * pad);
    }

    /**
     * Dismisses on OS focus loss, <b>unless the focus went to this picker's own popup</b>.
     *
     * <p>Deferred one turn so that an intra-application focus switch settles first: the owner
     * blurs and the popup gains focus in the same input batch, or the other way round, and a check
     * made between the two sees a moment when neither holds it.
     *
     * <p>The guard is not a nicety. Without it the popup closes itself the instant it takes focus,
     * because the owner blurring <em>is</em> the popup opening; and even where the popup is created
     * non-focus-stealing, clicking inside it hands it the OS focus on macOS, so the popup would
     * close on the very click that was choosing a day. The unconditional close this replaces was
     * a latent defect that only turned visible under the focus experiment of 2026-09-09 &mdash;
     * which is how a race announces itself: two of three pickers worked.
     */
    private void closeUnlessRefocused() {
        if (!open) {
            return;
        }
        boolean ownerFocused = scene() != null && scene().isWindowFocused();
        boolean popupFocused = popupScene != null && popupScene.isWindowFocused();
        if (!ownerFocused && !popupFocused) {
            setOpen(false, Change.Origin.ADJUSTMENT);
        }
    }

    /**
     * Takes the popup down, fading it out where there is a frame pump to run the fade.
     *
     * <p>The fields are relinquished <em>before</em> the fade starts, so that a picker reopened
     * while the old surface is still vanishing builds a new one rather than writing into the one
     * on its way out. The two presentations fade differently and both matter: a window fades its
     * own compositing, an overlay fades a number this class paints with, and the overlay's last
     * frame is what removes the layer holding input capture and focus &mdash; on wall time, not
     * frame time, or a paused application would be left with an open calendar over an unreachable
     * field.
     */
    private void dismiss() {
        if (blurHandle != null) {
            blurHandle.cancel();
            blurHandle = null;
        }
        if (dismissHandle != null) {
            dismissHandle.cancel();
            dismissHandle = null;
        }
        if (popupBlurHandle != null) {
            popupBlurHandle.cancel();
            popupBlurHandle = null;
        }
        if (popupWindow != null) {
            NativeWindow closing = popupWindow;
            Scene closingScene = popupScene;
            popupWindow = null;
            popupScene = null;
            popupPanel = null;
            NativeWindow parent = scene() != null ? scene().window() : null;
            Runnable destroy = () -> {
                if (parent != null && !parent.isClosed()) {
                    parent.unregisterChildPopup(closing);
                }
                closing.requestClose();
            };
            if (closingScene != null) {
                closingScene.fadeWindowOut(Theme.of(this).animWindow, () -> {
                    destroy.run();
                    releaseCalendar();
                });
            } else {
                destroy.run();
                releaseCalendar();
            }
        }
        if (scenePopup != null) {
            ScenePopup closing = scenePopup;
            Scene owner = scene();
            scenePopup = null;
            popupPanel = null;
            if (owner == null) {
                return; // detached mid-open: nothing holds the overlay any more
            }
            if (owner.window() == null) {
                owner.removeOverlay(closing); // headless: no frame pump to advance a fade
                releaseCalendar();
                return;
            }
            DateField pending = refocus; // the fade's end is where the focus can move: closeTo
            refocus = null;
            owner.addRealTimeTicker(dt -> {
                sceneFade = (float) Math.max(0, sceneFade - dt / Theme.of(this).animWindow);
                closing.invalidate();
                if (sceneFade > 0) {
                    return true;
                }
                owner.removeOverlay(closing);
                // Released here rather than at the start of the fade, so the card fades out with
                // the grid still on it; the next open takes it back whether this ran or not.
                releaseCalendar();
                if (pending != null) {
                    pending.requestFocus();
                }
                return false;
            });
        }
    }

    @Override
    protected void onDetached() {
        if (open) {
            setOpen(false, Change.Origin.ADJUSTMENT);
        }
        super.onDetached();
    }

    // ------------------------------------------------------------------ measure, layout, paint

    /** The width the trailing button takes, which is a square at the control's own height. */
    private float buttonWidth(SizeTokens t) {
        return t.fieldTrailing();
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.of(this).tokensFor(this);
        Size first = field.measure(constraints.loosened());
        float width = first.width();
        float height = first.height();
        if (endField != null) {
            Size second = endField.measure(constraints.loosened());
            width += second.width() + separatorWidth(t);
            height = Math.max(height, second.height());
        }
        return constraints.constrain(width + buttonWidth(t), Math.max(height, t.controlHeight()));
    }

    private float separatorWidth(SizeTokens t) {
        return textRuler().measure(rangeSeparator(), t.body()).width() + 2 * t.spacingSmall();
    }

    /** An en dash between the two ends of a period: the typographic mark for a range. */
    private static String rangeSeparator() {
        return "–";
    }

    @Override
    protected void onLayout() {
        SizeTokens t = Theme.of(this).tokensFor(this);
        boolean rtl = isRightToLeft();
        float button = buttonWidth(t);
        // The button takes the gutter on the side reading ends on, exactly as a search field's
        // clear button and a combo's chevron do; the fields take what is left, in reading order.
        float contentLeft = rtl ? button : 0;
        float contentWidth = Math.max(0, width() - button);
        // The affordance takes the gutter on the side reading ends on, as a search field's clear
        // button and a combo's chevron do.
        float buttonX = rtl ? 0 : width() - button;
        this.button.measure(Constraints.tight(button, height()));
        this.button.layoutBox(buttonX, 0, button, height());
        if (endField == null) {
            field.measure(Constraints.tight(contentWidth, height()));
            field.layoutBox(contentLeft, 0, contentWidth, height());
            return;
        }
        float separator = separatorWidth(t);
        float each = Math.max(0, (contentWidth - separator) / 2);
        float firstX = rtl ? contentLeft + each + separator : contentLeft;
        float secondX = rtl ? contentLeft : contentLeft + each + separator;
        field.measure(Constraints.tight(each, height()));
        field.layoutBox(firstX, 0, each, height());
        endField.measure(Constraints.tight(each, height()));
        endField.layoutBox(secondX, 0, each, height());
    }

    @Override
    protected float paintOutset() {
        return Strokes.FOCUS_RING_OUTSET;
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.of(this);
        SizeTokens t = theme.tokensFor(this);
        boolean enabled = isEnabled();
        float focus = focusFade.value();
        float radius = t.radiusMedium();
        float w = width();
        float h = height();

        canvas.fillRoundRect(0, 0, w, h, radius, enabled ? theme.surface() : theme.disabledFill());
        boolean invalid = !field.isValid() || endField != null && !endField.isValid();
        float half = Strokes.HALF_PIXEL_INSET;
        canvas.drawRoundRect(half, half, w - 2 * half, h - 2 * half, radius,
                Strokes.BORDER + (Strokes.FOCUS_RING - Strokes.BORDER) * focus,
                invalid ? theme.danger() : theme.outline().lerp(theme.focusRing(), focus));

        if (endField != null) {
            // The dash sits in the gap the layout left between the two fields, centred in it.
            TextRuler ruler = textRuler();
            TextMetrics fm = ruler.measure("Hg", t.body());
            String dash = rangeSeparator();
            ShapedText shaped = ruler.shape(dash, t.body(),
                    ShapedText.Direction.of(dash, neutralBase()));
            float gapLeft = isRightToLeft()
                    ? endField.x() + endField.width()
                    : field.x() + field.width();
            float gapWidth = separatorWidth(t);
            canvas.drawText(shaped, gapLeft + (gapWidth - shaped.metrics().width()) / 2,
                    (h - fm.height()) / 2 + fm.ascent(),
                    enabled ? theme.textMuted() : theme.disabledText());
        }
    }


    // ------------------------------------------------------------------ accessibility

    /**
     * The box, the focus ring and a period's dash are the chrome around the fields and the button,
     * which say everything a reader needs. A single picker is no node, so the walk's
     * paints-and-says-nothing guard named this class in every application's log and recommended
     * {@code setAccessibleName} &mdash; the one call that would make the group a node again and
     * leave the field nameless &mdash; or {@code setAccessibleIgnored}, which would take the field
     * and the button out with it. The {@code TabbedPane} and {@code BackdropPanel} case; a range
     * picker keeps its group and is never asked.
     *
     * @return {@code true}; what this widget draws frames its children
     */
    @Override
    protected boolean paintsDecoration() {
        return true;
    }

    /**
     * What the composite says of itself: a single picker says <b>nothing</b> and is no node &mdash;
     * its field is the picker a reader meets, carries the label bound to the picker, the popup
     * state and the verbs that open and close it &mdash; and a range picker is a group that keeps
     * the label over its two named ends. The expanded state this group once carried, and the verbs
     * it accepted without publishing, are the field's now; the button is a plain press. The fields
     * and the button are real children and describe themselves, which is the whole reason they are
     * widgets.
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        if (endField == null) {
            return; // transparent: declares nothing, so the walk hoists the field and the button
        }
        a.role(Accessible.Role.GROUP);
    }

    /**
     * Where a caption bound to this picker lands: on the single field, which is the node a reader
     * arrives at; a range picker keeps it on the group, whose two ends are named "Start date" and
     * "End date" for themselves.
     */
    @Override
    protected Widget<?> accessibleLabelTarget() {
        return endField == null ? field : this;
    }

    // ------------------------------------------------------------------ the popup's two shells


    /**
     * The trailing affordance: a square button that opens and closes the calendar.
     *
     * <p>A widget rather than a region its owner paints, and that is the whole point of it. In
     * this toolkit focus traversal visits widgets, so a painted button is unreachable by keyboard
     * however carefully it is described to a screen reader &mdash; and a control half of whose
     * users cannot operate it is not finished. As a widget it takes Tab, answers Enter and Space,
     * draws its own focus ring and publishes its own node.
     */
    private final class CalendarButton extends Widget<CalendarButton> {

        private boolean hover;

        CalendarButton() {
            setFocusable(true);
            setCursor(Cursor.POINTER);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = Theme.of(this).tokensFor(DatePicker.this);
            return constraints.constrain(buttonWidth(t), t.controlHeight());
        }

        @Override
        protected float paintOutset() {
            return Strokes.FOCUS_RING_OUTSET;
        }

        /**
         * The calendar glyph, drawn rather than set as a character: a font with no coverage for a
         * calendar symbol renders tofu, and this is chrome that must look the same in every
         * language. A page with a bound over it and two hangers.
         *
         * <p><b>Snapped to the pixel grid in scene coordinates, and that is the whole difficulty.</b>
         * The obvious version computes the glyph's box from this widget's own size and draws it,
         * which is correct arithmetic and renders a different glyph in every row of a form: a
         * column of controls puts each row at a fractional scene offset &mdash; measured on the
         * demo's own form, three pickers with identical boxes sat at y 280.0020, 346.4082 and
         * 412.8145 &mdash; so identical local coordinates land on three different sub-pixel phases
         * and a one-point stroke is spread over two rows of pixels by a different fraction in
         * each. The origin is therefore rounded <em>after</em> being taken into scene space and
         * brought back, which cancels this widget's own fractional offset; everything inside is an
         * integer or a half from there, with the half on the strokes.
         */
        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            SizeTokens t = theme.tokensFor(DatePicker.this);
            boolean enabled = isEnabled();
            if (hover && enabled) {
                canvas.fillRoundRect(Strokes.SPINNER_HOVER_INSET, Strokes.SPINNER_HOVER_INSET,
                        width() - 2 * Strokes.SPINNER_HOVER_INSET,
                        height() - 2 * Strokes.SPINNER_HOVER_INSET, t.radiusSmall(),
                        theme.surfaceRaised());
            }
            Color ink = !enabled ? theme.disabledText()
                    : open || hover || isFocused() ? theme.text() : theme.textMuted();

            float size = Math.round(t.fieldIcon());
            float sceneX = localToSceneX();
            float sceneY = localToSceneY();
            float x = Math.round(sceneX + (width() - size) / 2) - sceneX;
            float y = Math.round(sceneY + (height() - size) / 2) - sceneY;
            float pen = Strokes.BORDER;
            float half = Strokes.HALF_PIXEL_INSET;
            float hangers = Math.max(2, Math.round(size * 0.19f));
            float pageTop = y + hangers;
            float pageHeight = size - hangers;
            float radius = Math.max(1, Math.round(t.radiusSmall() * 0.6f));

            canvas.drawRoundRect(x + half, pageTop + half, size - pen, pageHeight - pen, radius,
                    pen, ink);
            // The bound: a filled bar inside the page's top edge, which is what makes the glyph
            // read as a calendar at sixteen points rather than as an empty box.
            canvas.fillRect(x + pen, pageTop + pen, size - 2 * pen,
                    Math.max(2, Math.round(size * 0.22f)), ink);
            float leftHanger = Math.round(x + size * 0.3f) + half;
            float rightHanger = Math.round(x + size * 0.7f) + half;
            canvas.drawLine(leftHanger, y, leftHanger, pageTop + pen, pen, ink);
            canvas.drawLine(rightHanger, y, rightHanger, pageTop + pen, pen, ink);

            float focus = buttonFocus.value();
            if (focus > 0.001f) {
                float gap = Strokes.FOCUS_GAP_BUTTON;
                canvas.drawRoundRect(gap, gap, width() - 2 * gap, height() - 2 * gap,
                        t.radiusSmall(), Strokes.FOCUS_RING, theme.focusRing().withAlpha(focus));
            }
        }

        @Override
        protected void onFocusGained() {
            buttonFocus.to(1);
        }

        @Override
        protected void onFocusLost() {
            buttonFocus.to(0);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            switch (event.type()) {
                case MOVE, ENTER -> {
                    if (!hover) {
                        hover = true;
                        invalidate();
                    }
                }
                case EXIT -> {
                    if (hover) {
                        hover = false;
                        invalidate();
                    }
                }
                case CLICK -> {
                    if (event.button() == Keys.MOUSE_LEFT && isEnabled()) {
                        event.consume();
                        press();
                    }
                }
                default -> {
                }
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (!event.isPressed() || !isEnabled()) {
                return;
            }
            if (event.key() == Keys.ENTER || event.key() == Keys.SPACE) {
                event.consume();
                press();
            }
        }

        /**
         * Opens or closes the calendar, and hands the keyboard to the field when it opens: the
         * popup does not take focus, so the field is what the navigation keys are forwarded from.
         * A person who reached this button with Tab therefore lands in the grid, which is where
         * they were going.
         */
        private void press() {
            setOpen(!open, Change.Origin.USER);
            if (open) {
                (fillingEnd && endField != null ? endField : field).requestFocus();
            }
        }

        /**
         * A plain press: the popup's state is the field's to tell, and a button that advertised an
         * expand state vended a pattern on Windows whose Expand it then refused.
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.BUTTON);
            a.name(DateStrings.OPEN_CALENDAR, Accessible.NameFrom.CONTENT);
            a.action(Accessible.Action.PRESS); // withdrawn by the walk while it is not ENABLED
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action action,
                                                Accessible.Argument arg) {
            if (action != Accessible.Action.PRESS || !isEnabled()) {
                return false;
            }
            press();
            return true;
        }
    }

    /**
     * The card the grid sits on: rounded, raised, and translucent in a window of its own, which is
     * what makes a native popup composite over the desktop like the rest of the theme.
     */
    private final class PopupPanel extends Widget<PopupPanel> {

        /** Lets go of a child a newer card is taking; the picker owns the panel's children. */
        void drop(Widget<?> child) {
            remove(child);
        }

        PopupPanel() {
            add(calendar);
            if (timeRow != null) {
                if (timeRow.parent() instanceof PopupPanel holder) {
                    holder.drop(timeRow); // the last card's, still fading: see releaseCalendar
                }
                add(timeRow);
            }
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            Size content = popupContentSize();
            return constraints.constrain(content.width(), content.height());
        }

        /**
         * The grid on top and the time row under it, centred: a clock is narrower than a month,
         * and a row stretched to the grid's width would put its segments off to one side.
         */
        @Override
        protected void onLayout() {
            SizeTokens t = Theme.of(this).tokensFor(DatePicker.this);
            float pad = t.popupPadV();
            float w = Math.max(0, width() - 2 * pad);
            float h = Math.max(0, height() - 2 * pad);
            if (timeRow == null) {
                calendar.measure(Constraints.tight(w, h));
                calendar.layoutBox(pad, pad, w, h);
                return;
            }
            Size row = timeRow.measure(Constraints.loose(w, h));
            float gridH = Math.max(0, h - t.spacingSmall() - row.height());
            calendar.measure(Constraints.tight(w, gridH));
            calendar.layoutBox(pad, pad, w, gridH);
            float rowW = Math.min(w, row.width());
            timeRow.measure(Constraints.tight(rowW, row.height()));
            timeRow.layoutBox(pad + (w - rowW) / 2, pad + gridH + t.spacingSmall(), rowW,
                    row.height());
        }

        /**
         * In-scene the canvas opacity runs the fade; a popup window fades through its own
         * compositor opacity, so that presentation paints solid here.
         *
         * <p>The grid is a <b>child</b> of this panel, unlike the combo box's rows, which it
         * paints itself. So the opacity has to be applied twice &mdash; here and around
         * {@link #paintChildren} &mdash; or the card fades in from nothing while the calendar on
         * it is already fully drawn, which is not a fade but a flash. The alternative, alpha on
         * the fill colour alone, is what produced exactly that.
         */
        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            SizeTokens t = theme.tokensFor(DatePicker.this);
            float radius = t.radiusLarge();
            boolean fading = fading();
            if (fading) {
                canvas.save();
                canvas.setOpacity(sceneFade);
            }
            // Solid in the scene, slightly translucent in a window of its own: in a window the
            // desktop shows through and the rounded corners are genuinely transparent, while in
            // the scene the same translucency would composite over the form underneath, and a
            // calendar you can read the form through is not frosted glass, it is unreadable.
            canvas.fillRoundRect(0, 0, width(), height(), radius,
                    scenePopup != null ? theme.surfaceRaised() : theme.surfaceRaised().withAlpha(0.94f));
            canvas.drawRoundRect(Strokes.HALF_PIXEL_INSET, Strokes.HALF_PIXEL_INSET,
                    width() - 2 * Strokes.HALF_PIXEL_INSET,
                    height() - 2 * Strokes.HALF_PIXEL_INSET, radius, Strokes.BORDER,
                    theme.outline());
            if (fading) {
                canvas.restore();
            }
        }

        @Override
        protected void paintChildren(Canvas canvas) {
            if (!fading()) {
                super.paintChildren(canvas);
                return;
            }
            canvas.save();
            canvas.setOpacity(sceneFade);
            super.paintChildren(canvas);
            canvas.restore();
        }

        private boolean fading() {
            return scenePopup != null && sceneFade < 1f;
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.GROUP);
            a.name(DateStrings.CALENDAR, Accessible.NameFrom.CONTENT);
        }
    }

    /**
     * The layer an in-scene popup is drawn on: it covers the scene, so a press that missed the card
     * is a press on it, and closing on that press is the click-outside dismissal.
     */
    private final class ScenePopup extends Widget<ScenePopup> {

        private final PopupPanel panel;

        ScenePopup(PopupPanel panel) {
            this.panel = panel;
            setFocusable(true);
            add(panel);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            SizeTokens t = Theme.of(this).tokensFor(DatePicker.this);
            boolean rtl = DatePicker.this.isRightToLeft();
            float gap = t.popupGap();
            float anchorX = DatePicker.this.localToSceneX();
            float anchorTop = DatePicker.this.localToSceneY();
            float anchorBottom = anchorTop + DatePicker.this.height() + gap;
            Size content = popupContentSize();
            boolean above = anchorBottom + content.height() > height()
                    && anchorTop - gap - content.height() >= 0;
            // Hung from the picker's LEADING edge, which is its right one reading right to left,
            // then clamped into the scene: the clamp needs no direction of its own, because the
            // interval it clamps into is never empty.
            float wanted = rtl
                    ? anchorX + DatePicker.this.width() - content.width() : anchorX;
            float x = Math.max(0, Math.min(wanted, Math.max(0, width() - content.width())));
            float y = above ? anchorTop - gap - content.height() : anchorBottom;
            y = Math.max(0, Math.min(y, Math.max(0, height() - content.height())));
            panel.measure(Constraints.tight(content.width(), content.height()));
            panel.layoutBox(x, y, content.width(), content.height());
        }

        /**
         * The overlay holds the focus in this presentation, so it is where the keys arrive: the
         * picker takes the ones the popup answers, and what is left goes to the field whose
         * caret is showing, exactly as it would in a window of its own.
         */
        @Override
        protected void onKeyEvent(KeyEvent event) {
            DatePicker.this.interceptKey(event);
            if (!event.isConsumed() && open) {
                forwardToField(event);
            }
        }

        @Override
        protected void onCharTyped(CharEvent event) {
            DatePicker.this.interceptChar(event);
            if (!event.isConsumed() && open) {
                forwardToField(event);
            }
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() == MouseEvent.Type.PRESS) {
                DatePicker.this.setOpen(false, Change.Origin.USER);
                event.consume();
            }
        }

        @Override
        protected void onAccessibility(Accessibility a) {
            // Declared rather than left to the default, for ComboBox's reason: this layer is the
            // scene's only tab stop while the popup is open, so a node it publishes with no role
            // would be a focusable UNKNOWN.
            a.role(Accessible.Role.GROUP);
            a.name(DateStrings.CALENDAR, Accessible.NameFrom.CONTENT);
            // Only while the calendar is open, ComboBox.ScenePopup's rule: through the fade-out
            // after a close the layer is still the top overlay and is still published, and a
            // CANCEL there reached setOpen(false), which returns at once for a picker already
            // closed while the hook answered done (semantics 5; the 2d review, 2026-09-15). The
            // hook below reads the same field. The calendar inside keeps its own verbs through
            // the fade, because it still performs them, as the pointer does.
            if (open) {
                a.action(Accessible.Action.CANCEL);
            }
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action action,
                                                Accessible.Argument arg) {
            if (action != Accessible.Action.CANCEL || !open) {
                return false; // not while the calendar fades out, where nothing publishes CANCEL
            }
            DatePicker.this.setOpen(false, Change.Origin.USER);
            return true;
        }
    }
}
