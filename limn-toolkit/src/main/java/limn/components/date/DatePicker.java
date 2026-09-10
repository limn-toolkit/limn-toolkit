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
import limn.lang.Checks;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

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
 * new DatePicker()                    // a date, with a calendar
 * DatePicker.ofDateTime()             // a date and a time, with a calendar
 * DatePicker.ofRange()                // a period: two fields, one calendar
 * }</pre>
 *
 * <p><b>Composition rather than modes</b> (ADR 042 &sect;2). The typing, the segments and the
 * validity are {@link DateField}'s and are not reimplemented here; the grid, the bounds and the
 * marks are {@link CalendarView}'s. What this class owns is exactly three things: the trailing
 * button, when the popup is open, and keeping the two halves in step.
 *
 * <p>Because the field is a widget rather than a painted region, a period is two fields with the
 * caret moving between them on Tab, the clipboard works, and a screen reader walks a subtree that
 * was already described. The picker paints one box around them so it reads as one control.
 *
 * <p><b>The popup never takes focus</b>, which is the toolkit's popup contract and not this
 * widget's choice: the field keeps the keyboard, and the navigation keys are forwarded to the grid
 * while it is open, so Up, Down, PageUp, PageDown and Enter drive the calendar and the digits still
 * reach the segments. That is also why {@code DatePicker.ofDateTime()} edits its time in the field
 * and not in the popup (ADR 042 &sect;11).
 *
 * <p>The presentation follows {@link DisplayMode}: a window of its own where the platform can place
 * one, an overlay inside the owner window where it cannot &mdash; and an application that documents
 * itself with screenshots asks for {@link DisplayMode#IN_SCENE} on purpose, because a native popup
 * is absent from a capture of the window.
 */
public class DatePicker extends Widget {

    /** Breathing room kept from the work-area edge when clamping the popup. */
    private static final float EDGE_MARGIN = 8;


    private final DateField field;
    /** The second end of a period, or {@code null} for a picker that is not one. */
    private final DateField endField;
    private final CalendarView calendar = new CalendarView();

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
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);
    /** The trailing button's own ring, which fades on its own widget rather than on the box. */
    private final Transition buttonFocus =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);

    private Consumer<LocalDate> onSelect;

    /** A picker for a date. */
    public DatePicker() {
        this(new DateField(), null);
    }

    private DatePicker(DateField field, DateField endField) {
        DateStrings.ensureRegistered();
        this.field = field;
        this.endField = endField;
        setCursor(Cursor.DEFAULT);
        add(button);
        button.observeChanges((widget, change) -> {
            if (change.aspect() == Change.Aspect.FOCUS) {
                refreshFocusRing();
                invalidate();
            }
        });
        adopt(field);
        if (endField != null) {
            adopt(endField);
            calendar.setSelectionMode(CalendarView.SelectionMode.RANGE);
            field.setAccessibleName(DateStrings.RANGE_START);
            endField.setAccessibleName(DateStrings.RANGE_END);
        }
        // The grid in a popup is NOT a tab stop, and the accessibility gallery is what caught it:
        // the popup contract keeps focus on the field and forwards the navigation keys here, so a
        // focusable grid is a node the traversal can land on and a node with no name -- a reader
        // arriving at it hears "table" and nothing. A standalone CalendarView stays focusable,
        // because there it IS the control and an application names it.
        calendar.setFocusable(false);
        // The grid inherits the picker's step, direction and language through the tree in the
        // in-scene presentation and through the host link in the other; the link is set when the
        // popup is built, which is the only moment the panel has no parent.
        calendar.onSelect(this::calendarPicked);
        calendar.onSelectRange(this::calendarPickedRange);
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
     * A picker for a date and a time of day. The time is typed in the field; the popup carries the
     * calendar alone, and ADR 042 &sect;11 says why.
     *
     * @return the picker
     */
    public static DatePicker ofDateTime() {
        return new DatePicker(DateField.ofDateTime(), null);
    }

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
        if (endField == null) {
            throw new IllegalStateException("this DatePicker has one field; build it with ofRange()");
        }
        field.setDate(range == null ? null : range.start());
        endField.setDate(range == null ? null : range.end());
        calendar.setSelectedRange(range);
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
        field.setChronology(chronology);
        if (endField != null) {
            endField.setChronology(chronology);
        }
        calendar.setChronology(chronology);
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
        setOpen(true, Change.Origin.CODE);
        return this;
    }

    /** Closes the calendar. */
    public DatePicker close() {
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
            calendar.setKeyboardActive(true);
            present();
        } else {
            calendar.setKeyboardActive(false);
            dismiss();
        }
        invalidate();
        notifyChange(Change.of(Change.Aspect.EXPANDED, origin));
    }

    /** Points the grid at what the fields hold, without disturbing what the person is typing. */
    private void syncCalendarFromFields() {
        if (endField != null) {
            DateRange picked = range();
            if (picked != null) {
                calendar.setSelectedRange(picked);
            }
            LocalDate anchor = (fillingEnd ? endField : field).date();
            if (anchor != null) {
                calendar.setVisibleMonth(anchor);
            }
            return;
        }
        LocalDate date = field.date();
        calendar.setSelectedDate(date);
    }

    /** The grid picked a day: it goes into whichever field is being filled, and the popup closes. */
    private void calendarPicked(LocalDate day) {
        if (day == null) {
            return;
        }
        field.setDate(day);
        setOpen(false, Change.Origin.USER);
        field.requestFocus();
        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.USER));
    }

    private void calendarPickedRange(DateRange picked) {
        if (picked == null || endField == null) {
            return;
        }
        field.setDate(picked.start());
        endField.setDate(picked.end());
        setOpen(false, Change.Origin.USER);
        (fillingEnd ? endField : field).requestFocus();
        notifyChange(Change.of(Change.Aspect.VALUE, Change.Origin.USER));
    }

    /**
     * Called with the date the user picked, whether they typed it or chose it from the grid. A
     * range picker's handler is reached with the start; the whole period is {@link #range()}.
     *
     * @param listener what to run, or {@code null} to clear the slot
     * @return this
     * @throws IllegalStateException if a handler is already registered
     */
    public DatePicker onSelect(Consumer<LocalDate> listener) {
        Ui.checkUiThread();
        this.onSelect = Checks.handlerSlot(onSelect, listener, "DatePicker.onSelect");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.VALUE) {
            if (onSelect != null) {
                onSelect.accept(field.date());
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
     * which is what lets somebody arrow to a day and still type over it. Alt+Down opens, Escape
     * closes, and neither reaches the field.
     */
    private void interceptKey(KeyEvent event) {
        if (!event.isPressed()) {
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
        switch (event.key()) {
            case Keys.ESCAPE -> {
                // Escape backs out one level at a time: out of the year chooser to the days, and
                // only then out of the popup. A single Escape that closed the whole thing from
                // inside a chooser would throw away the navigation as well as the popup.
                if (calendar.view() != CalendarView.View.DAYS) {
                    calendar.setView(CalendarView.View.DAYS);
                    repaintPopup();
                } else {
                    setOpen(false, Change.Origin.USER);
                }
                event.consume();
            }
            case Keys.TAB -> {
                // Forwarded so the grid's own parts -- the two paging arrows and the title --
                // are reachable while the field holds the focus. The calendar declines the key
                // when the walk runs off an end, and that is the signal to let go: the popup
                // closes and the Tab does what it would have done, which is move to the next
                // control in the form.
                calendar.onKeyEvent(event);
                if (event.isConsumed()) {
                    repaintPopup();
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

    private void repaintPopup() {
        if (popupPanel != null) {
            popupPanel.markNeedsLayout();
        }
        if (popupWindow != null) {
            popupWindow.requestFrame();
        }
        if (scenePopup != null) {
            scenePopup.invalidate();
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
        Widget holder = calendar.parent();
        if (holder != null) {
            holder.remove(calendar);
        }
    }

    private void presentInScene(Scene owner) {
        releaseCalendar();
        popupPanel = new PopupPanel();
        popupPanel.setInheritanceHost(this);
        scenePopup = new ScenePopup(popupPanel);
        // The link goes on the OVERLAY as well, and it is the overlay's that does the work: a host
        // link on a widget that has a parent loses to the tree, and here the panel's parent is the
        // overlay. Without it the grid resolves the scene's defaults instead of the picker's, and
        // opens at a different density, direction or language from the field that opened it.
        scenePopup.setInheritanceHost(this);
        boolean animate = owner.window() != null;
        sceneFade = animate ? 0f : 1f;
        owner.pushOverlay(scenePopup);
        if (animate) {
            ScenePopup fading = scenePopup;
            owner.addRealTimeTicker(dt -> {
                if (!open) {
                    return false;
                }
                sceneFade = (float) Math.min(1, sceneFade + dt / Theme.current().animWindow);
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
        SizeTokens t = Theme.current().tokensFor(this);
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
        popupPanel.setInheritanceHost(this);
        popupScene = new Scene(popupPanel);
        popupScene.inheritRenderingFlags(scene);
        popupScene.bind(popupWindow);
        popupBlurHandle = popupScene.observeWindowBlur(() -> Ui.post(this::closeUnlessRefocused));
        popupScene.setBackground(Color.TRANSPARENT);
        int screenY = above
                ? anchorTop - Math.round((gap + content.height()) * factor)
                : anchorBottom;
        popupWindow.setScreenPosition(screenX, screenY);
        if (parent.isVisible()) {
            popupScene.fadeWindowIn(Theme.current().animWindow);
            popupWindow.show();
        }
        popupWindow.requestFrame();
    }

    /** The popup's box: the grid's own measurement plus the panel's padding. */
    private Size popupContentSize() {
        SizeTokens t = Theme.current().tokensFor(this);
        float pad = t.popupPadV();
        Size grid = calendar.measure(Constraints.loose(Float.POSITIVE_INFINITY,
                Float.POSITIVE_INFINITY));
        return new Size(grid.width() + 2 * pad, grid.height() + 2 * pad);
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
                closingScene.fadeWindowOut(Theme.current().animWindow, () -> {
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
            owner.addRealTimeTicker(dt -> {
                sceneFade = (float) Math.max(0, sceneFade - dt / Theme.current().animWindow);
                closing.invalidate();
                if (sceneFade > 0) {
                    return true;
                }
                owner.removeOverlay(closing);
                // Released here rather than at the start of the fade, so the card fades out with
                // the grid still on it; the next open takes it back whether this ran or not.
                releaseCalendar();
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
        SizeTokens t = Theme.current().tokensFor(this);
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
        SizeTokens t = Theme.current().tokensFor(this);
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
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        boolean enabled = isEnabled();
        float focus = focusFade.value();
        float radius = t.radiusMedium();
        float w = width();
        float h = height();

        canvas.fillRoundRect(0, 0, w, h, radius, enabled ? theme.surface : theme.disabledFill);
        boolean invalid = !field.isValid() || endField != null && !endField.isValid();
        float half = Strokes.HALF_PIXEL_INSET;
        canvas.drawRoundRect(half, half, w - 2 * half, h - 2 * half, radius,
                Strokes.BORDER + (Strokes.FOCUS_RING - Strokes.BORDER) * focus,
                invalid ? theme.danger : theme.outline.lerp(theme.focusRing, focus));

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
                    enabled ? theme.textMuted : theme.disabledText);
        }
    }


    // ------------------------------------------------------------------ accessibility

    /**
     * The picker itself is a group carrying the expanded state and the trailing button; the fields
     * are real children and describe themselves, which is the whole reason they are widgets.
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.GROUP);
        a.expand(open);
        // The button is a real child and describes itself; the fields likewise. This hook says
        // what is true of the composite and nothing about its members.
    }

    @Override
    protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
        switch (action) {
            case EXPAND -> {
                setOpen(true, Change.Origin.USER);
                return true;
            }
            case COLLAPSE, CANCEL -> {
                setOpen(false, Change.Origin.USER);
                return true;
            }
            default -> {
                return false;
            }
        }
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
    private final class CalendarButton extends Widget {

        private boolean hover;

        CalendarButton() {
            setFocusable(true);
            setCursor(Cursor.POINTER);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = Theme.current().tokensFor(DatePicker.this);
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
            Theme theme = Theme.current();
            SizeTokens t = theme.tokensFor(DatePicker.this);
            boolean enabled = isEnabled();
            if (hover && enabled) {
                canvas.fillRoundRect(Strokes.SPINNER_HOVER_INSET, Strokes.SPINNER_HOVER_INSET,
                        width() - 2 * Strokes.SPINNER_HOVER_INSET,
                        height() - 2 * Strokes.SPINNER_HOVER_INSET, t.radiusSmall(),
                        theme.surfaceRaised);
            }
            Color ink = !enabled ? theme.disabledText
                    : open || hover || isFocused() ? theme.text : theme.textMuted;

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
                        t.radiusSmall(), Strokes.FOCUS_RING, theme.focusRing.withAlpha(focus));
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

        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.BUTTON);
            a.name(DateStrings.OPEN_CALENDAR, Accessible.NameFrom.CONTENT);
            a.expand(open);
            if (isEnabled()) {
                a.action(Accessible.Action.PRESS);
            }
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
    private final class PopupPanel extends Widget {

        PopupPanel() {
            add(calendar);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = Theme.current().tokensFor(DatePicker.this);
            float pad = t.popupPadV();
            Size grid = calendar.measure(constraints.loosened());
            return constraints.constrain(grid.width() + 2 * pad, grid.height() + 2 * pad);
        }

        @Override
        protected void onLayout() {
            SizeTokens t = Theme.current().tokensFor(DatePicker.this);
            float pad = t.popupPadV();
            float w = Math.max(0, width() - 2 * pad);
            float h = Math.max(0, height() - 2 * pad);
            calendar.measure(Constraints.tight(w, h));
            calendar.layoutBox(pad, pad, w, h);
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
            Theme theme = Theme.current();
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
                    scenePopup != null ? theme.surfaceRaised : theme.surfaceRaised.withAlpha(0.94f));
            canvas.drawRoundRect(Strokes.HALF_PIXEL_INSET, Strokes.HALF_PIXEL_INSET,
                    width() - 2 * Strokes.HALF_PIXEL_INSET,
                    height() - 2 * Strokes.HALF_PIXEL_INSET, radius, Strokes.BORDER,
                    theme.outline);
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
    private final class ScenePopup extends Widget {

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
            SizeTokens t = Theme.current().tokensFor(DatePicker.this);
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

        @Override
        protected void onKeyEvent(KeyEvent event) {
            DatePicker.this.interceptKey(event);
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
            a.action(Accessible.Action.CANCEL);
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action action,
                                                Accessible.Argument arg) {
            if (action != Accessible.Action.CANCEL) {
                return false;
            }
            DatePicker.this.setOpen(false, Change.Origin.USER);
            return true;
        }
    }
}
