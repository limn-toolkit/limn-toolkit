package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.animation.Motion;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.internal.AnchoredPopup;
import limn.components.internal.a11y.PopupOwnerAccessibility;
import limn.components.internal.a11y.RowsAccessibility;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.ShapedText;
import limn.i18n.I18nString;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.internal.lang.Checks;
import limn.math.Vec2;
import limn.scene.Constraints;
import limn.scene.Scrollable;
import limn.scene.Size;
import limn.scene.Change;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Tabbed panel: a horizontal strip of tab headers over a content area that
 * shows the selected tab. The strip's horizontal alignment is configurable:
 * {@link TabAlignment#LEFT}, {@link TabAlignment#CENTER} or
 * {@link TabAlignment#RIGHT}. Headers select on click; only the selected
 * content is laid out and painted. All visuals come from the {@link Theme}.
 *
 * <p><b>This or a {@link SegmentedControl}? Ask who owns the content.</b> A tabbed pane
 * owns it ({@link #addTab} hands it the pages and it decides which is laid out), which
 * is why it carries chevrons, a popup and a scrollable strip. A segmented control owns
 * nothing and hands back an index, so it fits wherever the caller keeps the content and
 * switches it itself.
 *
 * <p><b>Keyboard:</b> the strip is a single tab stop (roving focus): only the
 * selected header is focusable, so Tab/Shift+Tab land on it; Left/Right arrows
 * move between tabs (Home/End jump to the ends) and Enter/Space dive into the
 * panel. Alt+Down opens the list of every tab while the strip overflows. Focus
 * follows a programmatic selection while the strip holds it.
 *
 * <p><b>Overflow:</b> when the headers don't fit, the strip becomes a clipped,
 * scrollable viewport (alignment is moot then): chevron buttons appear at the
 * edges (the dead side disabled, not hidden; hiding would resize the viewport
 * and oscillate at the ends), the mouse wheel scrolls the strip, and a list
 * button opens the list of every tab to jump directly. The list is anchored to
 * that button, in a window of its own where the platform can place one and in
 * the window's own scene where it cannot: a row per tab in the strip's order,
 * the shown tab marked, each tab's icon, and on a closable tab a close button
 * at the row's end. Up, Down, Home and End move its cursor, Enter or Space shows
 * the tab under it, a typed letter goes to the next tab whose caption starts with
 * it, Delete or Ctrl+W (Cmd+W on macOS) closes that tab through
 * {@link #onTabClose} and the list stays open without it, and Escape or Tab
 * closes the list. Alt+Down on the focused tab opens it from the keyboard; the
 * list button stays out of the tab order, so the strip is still one stop. The
 * list goes with the last tab, and when the tabs fit again. Selecting a tab (by
 * click, keyboard or {@link #setSelectedIndex}) always scrolls it into view. The three square controls
 * shrink with the pane rather than being dropped, each capped at a sixth of its width, so
 * the viewport never falls below half the pane, never reaches zero, and never changes
 * non-monotonically as the pane is resized.
 *
 * <p><b>Sizes follow the {@link limn.scene.ControlSize} resolved on this pane</b>: type,
 * tab paddings, icon and reveal margin come from the {@link SizeTokens} row, while the
 * strip separator, the selected-tab indicator, the focus ring and the chevron pen are
 * weights and stay identical at every step. The strip height composes the independent
 * type and padding ramps, so it is deliberately fractional, which is why the whole
 * layout must derive from one resolve per pass.
 *
 * <p><b>Reading right to left</b> the strip is still one run of tabs in reading order, and the
 * run reflects with the interface: the first tab lands on the edge reading starts from and the
 * run advances away from it, the three overflow controls swap ends keeping the order they had,
 * and the two scroll chevrons turn around. Left and Right select the tab that is visually to the
 * left and to the right, so the keyboard and the pointer agree; Home and End name the ends of the
 * tab <em>order</em> and stay first and last in both directions.
 *
 * <p><b>Closing.</b> A tab the application made closable ({@link #setTabClosable}, or every tab
 * added after {@link #setTabsClosable}) draws a close button at its end while it is selected or
 * under the pointer, so a row of tabs does not read as a row of buttons. The button, a middle
 * click, and Ctrl+W (Cmd+W on macOS) while the keyboard is in the pane close it, after asking
 * {@link #onTabClose}, which may refuse, now or later: a document with unsaved changes asks first.
 * Closing the selected tab selects the one after it, or the one before when it was the last.
 *
 * <p><b>Moving.</b> A tab is dragged along the bar, the others making room as it passes, and
 * moved from the keyboard with Ctrl+Shift+Page Up and Page Down (Cmd on macOS), which a screen
 * reader hears as the tab's new place. {@link #onTabMove} hears what the user moved. A tab does not
 * leave its bar.
 */
public final class TabbedPane extends Widget<TabbedPane> {

    /**
     * Where the strip sits when the headers are narrower than the pane, named for where it lands
     * in an interface that reads left to right.
     *
     * <p><b>These three names are physical and their meaning is not.</b> The strip is one run of
     * tabs in reading order and the run reflects as a whole, so {@link #LEFT} is the edge reading
     * starts from and {@link #RIGHT} the edge it ends on: right to left they land on the right and
     * the left respectively, and the default strip of a right-to-left pane sits on the right where
     * its first tab is. {@link #CENTER} is a centre in both.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum TabAlignment { LEFT, CENTER, RIGHT }

    /**
     * One question to {@link #onTabClose}: may this tab close? Answered once, with
     * {@link #allow()} or {@link #refuse()}, now or later: a handler that shows a dialog keeps the
     * request and answers from its result. While it is unanswered the tab is not asked again.
     */
    public interface TabCloseRequest {

        /** @return the tab's index now, or -1 when it is no longer in the pane */
        int index();

        /** @return the content of the tab asked about, which is how it is known after a move */
        Widget<?> content();

        /**
         * Closes the tab, if it is still in the pane. UI thread only.
         *
         * @throws IllegalStateException if the request was already answered
         */
        void allow();

        /**
         * Keeps the tab. UI thread only.
         *
         * @throws IllegalStateException if the request was already answered
         */
        void refuse();

        /** @return whether {@link #allow()} or {@link #refuse()} has been called */
        boolean isAnswered();
    }

    /** What the user moved: a tab from one place in the order to another. */
    @FunctionalInterface
    public interface TabMoveListener {

        /**
         * @param from where the tab was
         * @param to   where it is now
         */
        void moved(int from, int to);
    }

    /** The pane's children before its first page: the strip and its three controls. */
    private static final int FIRST_PAGE = 4;

    /** How far a press travels along the bar before it drags the tab. */
    private static final float DRAG_SLOP = 4;

    /** The time a sliding tab takes to cover most of the way to its place, in seconds. */
    private static final double SLIDE_SECONDS = 0.05;

    /** The close button's synthetic child, under a tab's node. */
    private static final long KEY_CLOSE = 1;

    /** Fraction of the strip viewport scrolled per chevron click. */
    private static final float SCROLL_STEP_FRACTION = 0.75f;

    /** Where selecting a tab moves keyboard focus. */
    private enum Focus { NONE, HEADER, CONTENT }

    private final List<TabHeader> headers = new ArrayList<>();
    private final List<Widget<?>> contents = new ArrayList<>();
    private final TabStrip strip = new TabStrip();
    private final StripButton prevButton = new StripButton(StripButton.Kind.PREV);
    private final StripButton nextButton = new StripButton(StripButton.Kind.NEXT);
    private final StripButton listButton = new StripButton(StripButton.Kind.LIST);
    private int selected = -1;
    private TabAlignment alignment = TabAlignment.LEFT;
    private IntConsumer onSelect;
    private boolean tabsClosable;
    private @Nullable Consumer<TabCloseRequest> onTabClose;
    private @Nullable TabMoveListener onTabMove;
    /** The contents a close was asked about and not answered yet. */
    private final Set<Widget<?>> closeAsked = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The user's move the next {@code CHILDREN} announcement is, or -1 when it is none. */
    private int movedFrom = -1;
    private int movedTo = -1;
    /** The header the pointer drags along the bar, and where its leading edge is held. */
    private @Nullable TabHeader dragged;
    /**
     * The header that was dragged, from the drag until it is home again: drawn over the others and
     * on a background of its own, so it never shows their text through it.
     */
    private @Nullable TabHeader lifted;
    private float dragLeft;
    /** Bumped by every start of the slide ticker, so an earlier one stops. */
    private int slideRun;

    // Overflow state, recomputed each layout. Header widths are measured by the
    // pane and consumed by the strip (which lays the headers out inside itself).
    private boolean overflowing;
    private float scrollOffset;
    private float headersTotal;
    private float[] headerWidths = new float[0];
    private int revealPending = -1; // tab to scroll into view on the next layout

    // The selected-tab indicator slides between tabs by animating its two x edges
    // (strip-local); it snaps when the same tab merely moves (scroll/resize).
    //
    // Bound to the STRIP and not to this pane, because that is where it is drawn. Bound to the
    // pane, every frame of the slide damaged the whole widget -- the strip and the page under it
    // -- which under partial rendering is a page repainted eight times to move a two-point rule
    // across a row of tabs. Measured at 21.6% of a window per frame against a strip that is a
    // twentieth of it. The strip is declared above these two, so it exists by the time they are.
    private final Transition indicatorLeft =
            new Transition(strip).duration(Theme.of(this).animTab).easing(Theme.of(this).animEasing);
    private final Transition indicatorRight =
            new Transition(strip).duration(Theme.of(this).animTab).easing(Theme.of(this).animEasing);
    private boolean indicatorPlaced;
    /** The header the indicator was last placed under: a header, not an index, as a tab moves. */
    private @Nullable TabHeader indicatorTab;
    /**
     * The direction the two held edges above were computed in, and the second half of the
     * snap-vs-animate key. Without it a direction change that coincides with a tab change reads as
     * an ordinary slide and animates the indicator across the whole reflected strip.
     */
    private boolean indicatorRtl;
    /**
     * The edges last handed to the two transitions, so a layout that finds the selected tab where
     * it already was can leave them alone. NaN before the first placement.
     */
    private float indicatorTargetLeft = Float.NaN;
    private float indicatorTargetRight = Float.NaN;

    /** An empty pane; add pages with {@link #addTab}. */
    public TabbedPane() {
        add(strip);
        add(prevButton);
        add(nextButton);
        add(listButton);
        prevButton.setVisible(false);
        nextButton.setVisible(false);
        listButton.setVisible(false);
    }

    /** Appends a tab. The first one added is selected. UI thread only. */
    public TabbedPane addTab(String title, Widget<?> content) {
        return addTab(title, null, content);
    }

    /** A tab whose caption follows the UI language; see {@link I18nString}. */
    public TabbedPane addTab(I18nString title, Widget<?> content) {
        return addTab(title, null, content);
    }

    /**
     * Appends a tab with a leading icon, tinted to the tab's text colour and drawn as authored
     * whichever way the pane reads. <em>Leading</em> is the side reading starts on: the left of
     * the caption left to right, its right in a right-to-left pane.
     */
    public TabbedPane addTab(String title, Icon icon, Widget<?> content) {
        return addTab(title, icon, Icon.Mirroring.NEVER, content);
    }

    /** Appends a tab with an icon and a caption that follows the UI language. */
    public TabbedPane addTab(I18nString title, Icon icon, Widget<?> content) {
        return addTab(title, icon, Icon.Mirroring.NEVER, content);
    }

    /**
     * Appends a tab with a leading icon and says whether the glyph inside it turns around when the
     * interface does. The icon's <em>position</em> is leading either way and is this pane's
     * decision; whether the drawing is a back arrow (which turns around) or a logo, a download
     * arrow or a photograph (which must not) is knowable only at the call that placed it.
     *
     * @param mirroring {@link Icon.Mirroring#NEVER} unless this glyph is directional; a wrong
     *                  {@code NEVER} is one arrow pointing the wrong way, and a wrong
     *                  {@code IN_RTL} is a flipped brand mark
     */
    public TabbedPane addTab(String title, Icon icon, Icon.Mirroring mirroring, Widget<?> content) {
        return addTab(I18nString.literal(Objects.requireNonNull(title, "title")),
                icon, mirroring, content);
    }

    /** The icon-mirroring form for a caption that follows the UI language. */
    public TabbedPane addTab(I18nString title, Icon icon, Icon.Mirroring mirroring,
            Widget<?> content) {
        Ui.checkUiThread();
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(mirroring, "mirroring");
        Objects.requireNonNull(content, "content");
        int index = contents.size();
        TabHeader header = new TabHeader(title, icon, mirroring, index);
        header.closable = tabsClosable;
        headers.add(header);
        contents.add(content);
        strip.addHeader(header);
        add(content);
        boolean first = selected < 0;
        if (first) {
            selected = 0;
        }
        // Roving focus (one tab stop for the whole strip): only the selected
        // header is focusable: Tab/Shift+Tab land on it, arrows move between tabs.
        header.setFocusable(index == selected);
        content.setVisible(index == selected);
        markNeedsLayout();
        tabsChangedUnderTheList();
        if (first) {
            // The pane took its first tab by itself: a consequence of adding, not a caller naming
            // a selection, so a watcher hears it as an adjustment.
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
        }
        return this;
    }

    /** Where the strip sits when the headers are narrower than the pane. Ignored while overflowing. */
    public TabbedPane setAlignment(TabAlignment newAlignment) {
        Ui.checkUiThread();
        this.alignment = Objects.requireNonNull(newAlignment, "newAlignment");
        markNeedsLayout();
        return this;
    }

    /** The strip's horizontal alignment. */
    public TabAlignment alignment() {
        return alignment;
    }

    /**
     * Index of the selected tab, or {@code -1} when there are none. A pane that holds tabs always
     * has one selected, so there is no clearing operation and no way back to {@code -1}.
     */
    public int selectedIndex() {
        return selected;
    }

    /**
     * How many tabs the pane holds; and, read before an {@code addTab}, the index that
     * call will land on.
     *
     * <p>That is what it is for. {@code addTab} returns the pane so a builder can chain,
     * which leaves a caller that needs to select a tab later with a hand-written index,
     * and a hand-written index is wrong the moment a tab is inserted above it, silently,
     * because every index below merely shifts to another real tab.
     */
    public int tabCount() {
        return contents.size();
    }

    /**
     * @param index a tab in {@code [0, tabCount)}
     * @return that tab's caption, resolved in this pane's own language
     * @throws IndexOutOfBoundsException if {@code index} is not a tab
     */
    public String tabTitle(int index) {
        return headers.get(index).title.get();
    }

    /**
     * @param index a tab in {@code [0, tabCount)}
     * @return the localizable string behind {@link #tabTitle(int)}. A tab's caption was already an
     *         {@link I18nString} and was reachable only from inside this package; anything that has
     *         to announce a caption that changed needs it from outside.
     * @throws IndexOutOfBoundsException if {@code index} is not a tab
     */
    public I18nString tabTitleSource(int index) {
        return headers.get(index).title;
    }

    /**
     * Selects a tab and scrolls it into view: a caller's write, so it announces
     * {@code SELECTION}/{@code CODE} and reaches no handler. Re-selecting the current tab still
     * scrolls it back into view (a caller asking for a tab is asking to be shown it) but changes
     * nothing and announces nothing. UI thread only.
     *
     * @param index a tab in {@code [0, tabCount)}
     * @throws IndexOutOfBoundsException if {@code index} is not a tab; an empty pane has none, so
     *         every index throws there. Arrowing past an end is not this: keyboard traversal
     *         stops at the end rather than raising.
     */
    public TabbedPane setSelectedIndex(int index) {
        Ui.checkUiThread();
        Objects.checkIndex(index, contents.size());
        selectTab(index, Focus.NONE, Change.Origin.CODE);
        return this;
    }

    /**
     * The application's response to the user choosing a tab: a click on a header, an arrow key
     * across the strip, a pick from the list of every tab, an assistive technology's select. Never
     * for {@link #setSelectedIndex}, which is a caller's write; to hear every change whatever
     * caused it, {@linkplain #observeChanges watch} the pane instead.
     *
     * @param listener the handler, or {@code null} to clear the slot
     * @return this pane
     * @throws IllegalStateException if a handler is already registered
     */
    public TabbedPane onSelect(@Nullable IntConsumer listener) {
        Ui.checkUiThread();
        this.onSelect = Checks.handlerSlot(onSelect, listener, "TabbedPane.onSelect");
        return this;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.SELECTION) {
            if (onSelect != null) {
                onSelect.accept(selected);
            }
            return;
        }
        if (aspect == Change.Aspect.CHILDREN) {
            int from = movedFrom;
            int to = movedTo;
            movedFrom = -1;
            movedTo = -1;
            if (from >= 0 && onTabMove != null) {
                onTabMove.moved(from, to);
            }
            return;
        }
        super.handleUserChange(aspect);
    }

    // ------------------------------------------------------------- closing and moving

    /**
     * Whether tabs added from now on can be closed by the user. Tabs already in the pane keep
     * what they have; {@link #setTabClosable} changes one.
     *
     * @param value whether they can
     * @return this pane
     */
    public TabbedPane setTabsClosable(boolean value) {
        Ui.checkUiThread();
        tabsClosable = value;
        return this;
    }

    /** @return whether tabs added from now on can be closed by the user */
    public boolean isTabsClosable() {
        return tabsClosable;
    }

    /**
     * Whether the user can close this tab: by its close button, a middle click, or Ctrl+W (Cmd+W
     * on macOS). Code closes any tab with {@link #removeTab}.
     *
     * @param index a tab in {@code [0, tabCount)}
     * @param value whether it can
     * @return this pane
     * @throws IndexOutOfBoundsException if {@code index} is not a tab
     */
    public TabbedPane setTabClosable(int index, boolean value) {
        Ui.checkUiThread();
        TabHeader header = headers.get(index);
        if (header.closable != value) {
            header.closable = value;
            markNeedsLayout();
            header.invalidate();
        }
        return this;
    }

    /**
     * @param index a tab in {@code [0, tabCount)}
     * @return whether the user can close it
     * @throws IndexOutOfBoundsException if {@code index} is not a tab
     */
    public boolean isTabClosable(int index) {
        return headers.get(index).closable;
    }

    /**
     * @param index a tab in {@code [0, tabCount)}
     * @return the content the tab shows, as it was handed to {@link #addTab}
     * @throws IndexOutOfBoundsException if {@code index} is not a tab
     */
    public Widget<?> tabContent(int index) {
        return contents.get(index);
    }

    /**
     * @param content a widget
     * @return the index of the tab that shows it, by identity, or -1 when none does
     */
    public int indexOfTab(Widget<?> content) {
        for (int i = 0; i < contents.size(); i++) {
            if (contents.get(i) == content) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Takes a tab out of the pane, asking no handler: a caller's write, announced as
     * {@code CHILDREN}/{@code CODE}. Taking out the selected tab selects the one after it, or the
     * one before when it was the last, and that is announced as {@code SELECTION}/{@code
     * ADJUSTMENT}. The keyboard, if it was in the pane, goes to the newly selected tab.
     *
     * @param index a tab in {@code [0, tabCount)}
     * @return this pane
     * @throws IndexOutOfBoundsException if {@code index} is not a tab
     */
    public TabbedPane removeTab(int index) {
        Ui.checkUiThread();
        Objects.checkIndex(index, contents.size());
        closeTab(index, Change.Origin.CODE);
        return this;
    }

    /**
     * Moves a tab to another place in the order: a caller's write, announced as
     * {@code CHILDREN}/{@code CODE}. The selected tab stays selected wherever it goes.
     *
     * @param from a tab in {@code [0, tabCount)}
     * @param to   where it goes, in {@code [0, tabCount)}
     * @return this pane
     * @throws IndexOutOfBoundsException if either is not a tab
     */
    public TabbedPane moveTab(int from, int to) {
        Ui.checkUiThread();
        Objects.checkIndex(from, contents.size());
        Objects.checkIndex(to, contents.size());
        if (from != to) {
            reorder(from, to);
            moveChild(contents.get(to), FIRST_PAGE + to, Change.Origin.CODE);
        }
        return this;
    }

    /**
     * Asks the application whether a tab the user is closing may close. The handler is given a
     * request it answers now or later; without a handler the tab closes. Never asked for
     * {@link #removeTab}, which is a caller's own decision.
     *
     * @param handler the handler, or {@code null} to clear the slot
     * @return this pane
     * @throws IllegalStateException if a handler is already registered
     */
    public TabbedPane onTabClose(@Nullable Consumer<TabCloseRequest> handler) {
        Ui.checkUiThread();
        this.onTabClose = Checks.handlerSlot(onTabClose, handler, "TabbedPane.onTabClose");
        return this;
    }

    /**
     * The application's response to the user moving a tab, by dragging it along the bar or from
     * the keyboard, called once the move is over with where it was and where it is. Never for
     * {@link #moveTab}; to hear every change, {@linkplain #observeChanges watch} the pane for
     * {@code CHILDREN}.
     *
     * @param listener the handler, or {@code null} to clear the slot
     * @return this pane
     * @throws IllegalStateException if a handler is already registered
     */
    public TabbedPane onTabMove(@Nullable TabMoveListener listener) {
        Ui.checkUiThread();
        this.onTabMove = Checks.handlerSlot(onTabMove, listener, "TabbedPane.onTabMove");
        return this;
    }

    /** The user asked to close a tab: the handler decides, or the tab closes. */
    private void requestClose(int index, Change.Origin origin) {
        if (index < 0 || index >= contents.size()) {
            return;
        }
        Widget<?> content = contents.get(index);
        if (onTabClose == null) {
            closeTab(index, origin);
            return;
        }
        if (!closeAsked.add(content)) {
            return; // asked already, and not answered: a second click asks nothing new
        }
        onTabClose.accept(new CloseAsk(content, origin));
    }

    /** The request the close handler is given. */
    private final class CloseAsk implements TabCloseRequest {
        private final Widget<?> content;
        private final Change.Origin origin;
        private boolean answered;

        CloseAsk(Widget<?> content, Change.Origin origin) {
            this.content = content;
            this.origin = origin;
        }

        @Override
        public int index() {
            return indexOfTab(content);
        }

        @Override
        public Widget<?> content() {
            return content;
        }

        @Override
        public void allow() {
            answer();
            int index = indexOfTab(content);
            if (index >= 0) {
                closeTab(index, origin);
            }
        }

        @Override
        public void refuse() {
            answer();
        }

        private void answer() {
            Ui.checkUiThread();
            if (answered) {
                throw new IllegalStateException("the request was already answered");
            }
            answered = true;
            closeAsked.remove(content);
        }

        @Override
        public boolean isAnswered() {
            return answered;
        }
    }

    /**
     * Takes a tab out, settling everything before the one announcement the removal makes: the
     * selection, the tab stop and the keyboard move first, so a watcher that hears
     * {@code CHILDREN} reads a pane that is whole again.
     */
    private void closeTab(int index, Change.Origin origin) {
        TabHeader header = headers.get(index);
        Widget<?> content = contents.get(index);
        // Only the keyboard that was in the tab going away moves: a middle click on another tab
        // leaves it in the page it is typing in.
        boolean focusInside = holdsFocus(header) || holdsFocus(content);
        Widget<?> before = selected >= 0 ? contents.get(selected) : null;
        closeAsked.remove(content);
        if (dragged == header) {
            dragged = null;
        } else if (dragged != null) {
            dragged.tabLeftDuringDrag(index);
        }
        // The scene sends a header it takes out the release of a press it held: a tab closed
        // while it is dragged must find nothing left to finish, or its stale index ends the drag
        // in a list one shorter and stops this close half way.
        header.dropDrag();
        if (lifted == header) {
            lifted = null;
        }
        for (TabHeader each : headers) {
            each.slideFrom = each.x();
            each.slidePending = each != header;
        }
        headers.remove(index);
        contents.remove(index);
        if (contents.isEmpty()) {
            selected = -1;
        } else if (index < selected) {
            selected--;
        } else if (index == selected) {
            selected = Math.min(index, contents.size() - 1);
            contents.get(selected).setVisible(true);
        }
        reindex();
        for (int i = 0; i < headers.size(); i++) {
            headers.get(i).setFocusable(i == selected);
            headers.get(i).invalidate();
        }
        if (focusInside && selected >= 0) {
            headers.get(selected).focusFrom(origin);
        }
        revealPending = selected;
        strip.removeHeader(header);
        markNeedsContainedLayout();
        startSlides();
        // The list is settled before the page goes: the removal announces CHILDREN, and an
        // application that hears the pane empty may take it out of the window there and then.
        tabsChangedUnderTheList();
        remove(content, origin);
        if (selected >= 0 && contents.get(selected) != before) {
            // The pane chose the tab that is now selected: a consequence of the close.
            notifyChange(Change.of(Change.Aspect.SELECTION, Change.Origin.ADJUSTMENT));
        }
    }

    /**
     * Moves a tab in the order and keeps everything that hangs on the order with it: the
     * selection follows its tab, the headers slide from where they were drawn, the strip's
     * children keep the order a reader walks. Announces nothing; the caller does, once.
     */
    private void reorder(int from, int to) {
        for (TabHeader each : headers) {
            each.slideFrom = each.x();
            each.slidePending = each != dragged;
        }
        Widget<?> before = selected >= 0 ? contents.get(selected) : null;
        TabHeader header = headers.remove(from);
        headers.add(to, header);
        contents.add(to, contents.remove(from));
        if (before != null) {
            selected = indexOfTab(before);
        }
        reindex();
        strip.moveHeader(header, to);
        for (TabHeader each : headers) {
            each.invalidate();
        }
        revealPending = to;
        markNeedsContainedLayout();
        startSlides();
        tabsChangedUnderTheList();
    }

    /** The user moved a tab from the keyboard: moved, heard by the handler, then said. */
    private void moveByKeyboard(int from, int to) {
        if (to < 0 || to >= contents.size() || from == to) {
            return;
        }
        reorder(from, to);
        movedFrom = from;
        movedTo = to;
        moveChild(contents.get(to), FIRST_PAGE + to, Change.Origin.USER);
        if (scene() != null) {
            scene().announce(ComponentStrings.TAB_MOVED.format(headers.get(to).title.get(), to + 1,
                    headers.size()), Accessible.Politeness.POLITE);
        }
    }

    private void reindex() {
        for (int i = 0; i < headers.size(); i++) {
            headers.get(i).index = i;
        }
    }

    private boolean holdsFocus(Widget<?> subtree) {
        if (scene() == null) {
            return false;
        }
        for (Widget<?> w = scene().focusedWidget(); w != null; w = w.parent()) {
            if (w == subtree) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wears the headers' slides down to nothing, on wall time, repositioning the strip alone each
     * frame; at once while the user asked for less motion. A header waiting for its first slide
     * keeps the ticker alive until the layout that measures it has run.
     */
    private void startSlides() {
        if (scene() == null || !isShowing() || Motion.reduced()) {
            for (TabHeader each : headers) {
                each.slide = 0;
                each.slidePending = false;
            }
            if (lifted != dragged) {
                lifted = null;
            }
            return;
        }
        int run = ++slideRun;
        scene().addRealTimeTicker(dt -> {
            if (run != slideRun || scene() == null) {
                return false;
            }
            float keep = (float) Math.exp(-dt / SLIDE_SECONDS);
            boolean moving = false;
            for (TabHeader each : headers) {
                if (each.slidePending) {
                    moving = true;
                } else if (each.slide != 0) {
                    each.slide *= keep;
                    if (Math.abs(each.slide) < 0.5f) {
                        each.slide = 0;
                    } else {
                        moving = true;
                    }
                }
            }
            if (lifted != null && lifted != dragged && lifted.slide == 0 && !lifted.slidePending) {
                lifted.invalidate();
                lifted = null; // home: one of the row again
            }
            strip.placeHeaders();
            strip.invalidate();
            return moving;
        });
    }

    /**
     * Ctrl+W and Ctrl+Shift+Page Up and Page Down (Cmd on macOS), wherever the keyboard is in the
     * pane: on a tab or inside a page, as every tabbed interface takes them.
     */
    @Override
    protected void onKeyEvent(KeyEvent event) {
        if (!event.isPressed() || selected < 0) {
            return;
        }
        int command = Accelerator.commandModifier();
        int mods = event.modifiers()
                & (Keys.MOD_SHIFT | Keys.MOD_CONTROL | Keys.MOD_ALT | Keys.MOD_SUPER);
        if (event.key() == Keys.W && mods == command && !event.isRepeat()) {
            if (headers.get(selected).closable) {
                event.consume();
                requestClose(selected, Change.Origin.USER);
            }
            return;
        }
        if (mods == (command | Keys.MOD_SHIFT)
                && (event.key() == Keys.PAGE_UP || event.key() == Keys.PAGE_DOWN)) {
            event.consume();
            moveByKeyboard(selected, selected + (event.key() == Keys.PAGE_UP ? -1 : 1));
        }
    }

    /**
     * The pane's own bookkeeping, and deliberately not the public setter's contract: every
     * internal caller passes a <em>computed</em> index ({@code index - 1} from a Left arrow,
     * {@code headers.size() - 1} from End on an empty strip), and arrowing off an end is a key
     * that has nowhere to go, not a programming error. Out of range is a no-op here; it throws
     * only where an application named the index.
     *
     * <p>This is also the one seam every selection announces from, with {@code origin} saying who
     * asked: the public setter passes {@code CODE}, and the header's click and keys, the list of
     * every tab and an assistive technology's select pass {@code USER}. The announcement is the
     * <b>last</b> thing here, after the panel swap, the roving focus and the focus move have all
     * settled, so a watcher hears {@code VISIBLE}, {@code VISIBLE}, {@code FOCUSABLE},
     * {@code FOCUSABLE}, {@code FOCUS}, {@code FOCUS} and then the {@code SELECTION} they belong
     * to -- the settling order, with the aspect the call names as the signal that it is done.
     */
    /**
     * The pane's mechanisms as the rows shape drives them, over a header's index: a select is
     * what {@link #setSelectedIndex} does, changing the tab and leaving the keyboard where it
     * was; a press is what a left click and Enter do, changing the tab and then diving into the
     * panel. The cursor is the selection here (roving focus), so {@code FOCUS} is refused before
     * it could reach {@link #moveCursor}, and nothing reveals: the verb is not published on a
     * header, which the walk grants it free as a focusable widget.
     */
    private final class RowsHost implements RowsAccessibility.Host<Integer> {
        @Override
        public RowsAccessibility.Selection selection() {
            return RowsAccessibility.Selection.SINGLE;
        }

        @Override
        public boolean cursorIsTheSelection() {
            return true;
        }

        @Override
        public boolean rowsActivate() {
            return true;
        }

        @Override
        public boolean isSelected(Integer index) {
            return index == selected;
        }

        @Override
        public boolean select(Integer index, boolean moveCursor) {
            selectTab(index, Focus.NONE, Change.Origin.USER);
            return true;
        }

        @Override
        public void moveCursor(Integer index) {
            throw new UnsupportedOperationException("the cursor is the selection: FOCUS is refused");
        }

        @Override
        public void reveal(Integer index) {
            // Not published on a header, so never asked for.
        }

        @Override
        public void activate(Integer index) {
            selectTab(index, Focus.CONTENT, Change.Origin.USER);
        }
    }

    private final RowsHost rowsHost = new RowsHost();

    private void selectTab(int index, Focus focus, Change.Origin origin) {
        Ui.checkUiThread();
        if (index < 0 || index >= contents.size()) {
            return; // out of range (e.g. arrowing past an end)
        }
        Focus effective = focus;
        boolean moved = index != selected;
        if (moved) {
            boolean stripHadFocus = false;
            if (selected >= 0) {
                contents.get(selected).setVisible(false);
                TabHeader previous = headers.get(selected);
                stripHadFocus = previous.isFocused();
                previous.setFocusable(false); // roving focus: single tab stop
            }
            selected = index;
            headers.get(selected).setFocusable(true);
            contents.get(selected).setVisible(true);
            for (TabHeader header : headers) {
                header.invalidate();
            }
            if (effective == Focus.NONE && stripHadFocus) {
                effective = Focus.HEADER; // focus follows the selection out of a focused strip
            }
        }
        revealPending = index; // scroll into view even when re-selecting
        // onLayout re-targets the indicator and applies the reveal. Contained rather than a full
        // layout: a switch moves nothing outside this pane, and it clips its children, so the
        // scene can lay out and repaint the pane alone -- and falls back to a full pass by itself
        // if the pane's size moved, which it can only do on an unbounded axis. ADR 043 §9.4.4.
        markNeedsContainedLayout();
        applyFocus(index, effective, origin);
        if (moved) {
            notifyChange(Change.of(Change.Aspect.SELECTION, origin));
        }
    }

    private void applyFocus(int index, Focus focus, Change.Origin origin) {
        switch (focus) {
            case CONTENT -> {
                // Land in the panel: focus its first focusable descendant, or fall
                // back to the header so keyboard tab-navigation still works for
                // panels that have nothing focusable (labels, images).
                Widget<?> target = firstFocusable(contents.get(index));
                if (target != null) {
                    // A descendant the pane does not own: the public, CODE-labelled move is
                    // the only one reachable, and it is the honest label for a focus the pane
                    // lands somewhere it cannot see into.
                    target.requestFocus();
                } else {
                    headers.get(index).focusFrom(origin);
                }
            }
            case HEADER -> headers.get(index).focusFrom(origin);
            case NONE -> {
            }
        }
    }

    /** Depth-first search for the first focusable, visible, enabled widget in a subtree. */
    private static Widget<?> firstFocusable(Widget<?> widget) {
        if (!widget.isVisible() || !widget.isEnabled()) {
            return null;
        }
        if (widget.isFocusable()) {
            return widget;
        }
        for (Widget<?> child : widget.children()) {
            Widget<?> found = firstFocusable(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ---------------------------------------------------------- overflow

    /**
     * Scrolls the header strip by {@code dx} points (clamped to the content).
     *
     * <p>{@code dx} is a distance along the reading axis and {@code scrollOffset} a distance
     * travelled from the <b>leading</b> edge, so the clamp keeps its form and stays non-negative
     * in both directions; positive is always "toward the end of the tab order". Only the last step
     * knows a direction, because that is the one that turns the distance into an x.
     */
    private void scrollStripBy(float dx) {
        if (!overflowing || dx == 0) {
            return;
        }
        float maxOffset = Math.max(0, headersTotal - strip.width());
        float target = Math.min(Math.max(0, scrollOffset + dx), maxOffset);
        float applied = target - scrollOffset;
        if (applied == 0) {
            return;
        }
        scrollOffset = target;
        // The strip's own direction, resolved once here: these are its children and its
        // coordinates, and the run it laid out has to move the way it was placed.
        float shift = strip.isRightToLeft() ? applied : -applied;
        // Shift the headers NOW: revealInView re-reads coordinates between
        // nested scrollables in one pass (the Scrollable contract); deferring
        // to the next layout hands an outer scroller a rect a content-width off.
        for (TabHeader header : headers) {
            header.layoutBox(header.x() + shift, header.y(), header.width(), header.height());
        }
        markNeedsContainedLayout(); // chevron state + indicator follow; contained, as a switch is
    }

    // ---------------------------------------------------------- the tab list

    /** The list of every tab, opened from the strip's list button while the tabs overflow. */
    private final AnchoredPopup tabList = new AnchoredPopup(new TabListHost());
    private @Nullable TabListPanel tabListPanel;
    /** The row of the list the keyboard is on. */
    private int listCursor = -1;
    /** Whether the keyboard was in the pane, on its strip or in a page, when the list opened. */
    private boolean listHadKeyboard;
    /** Where it was then, while the list is open: what the scene gives it back to as the list goes. */
    private @Nullable Widget<?> listFocusWas;
    /** The keys the headers are known by to a reader, unique for the life of the pane. */
    private long headerKeys;

    /** @return whether the list of every tab is open; for the shape contracts' subjects */
    boolean isTabListOpen() {
        return tabList.isOpen();
    }

    /** Opens the list of every tab under the list button, or closes it when it is open. */
    private void openTabList() {
        if (scene() == null || headers.isEmpty()) {
            return;
        }
        if (tabList.isOpen()) {
            closeTabList(Change.Origin.USER);
            return;
        }
        if (!tabList.canOpen()) {
            return; // its button hidden: a list nobody sees is not open
        }
        listCursor = Math.max(0, selected);
        listHadKeyboard = holdsFocus(this);
        listFocusWas = scene().focusedWidget();
        tabList.open(limn.components.DisplayMode.NATIVE_WINDOW);
        listButton.invalidateAccessible();
        notifyChange(Change.of(Change.Aspect.EXPANDED, Change.Origin.USER));
    }

    /**
     * @param origin who closed it: the user, or the pane adjusting to its tabs, its window or the
     *               application losing the keyboard, which a watcher must not hear as the user's
     */
    private void closeTabList(Change.Origin origin) {
        if (!tabList.isOpen()) {
            return;
        }
        tabListPanel = null;
        tabList.close();
        listFocusWas = null; // read by the close, and held no longer than the list is open
        listButton.invalidateAccessible();
        if (!overflowing) {
            markNeedsLayout(); // its button stayed for the open list alone: it goes now
        }
        notifyChange(Change.of(Change.Aspect.EXPANDED, origin));
    }

    /** A pane taken out of its window takes its open list with it, as a combo box does. */
    @Override
    protected void onDetached() {
        closeTabList(Change.Origin.ADJUSTMENT);
        super.onDetached();
    }

    /** The tabs changed under the open list: it takes their new number, or goes with the last. */
    private void tabsChangedUnderTheList() {
        if (!tabList.isOpen()) {
            return;
        }
        if (headers.isEmpty()) {
            closeTabList(Change.Origin.ADJUSTMENT);
            return;
        }
        listCursor = Math.max(0, Math.min(listCursor, headers.size() - 1));
        tabList.refit();
        repaintTabList();
    }

    private void repaintTabList() {
        if (tabListPanel != null) {
            tabListPanel.invalidate();
        }
        if (tabList.window() != null) {
            tabList.window().requestFrame();
        }
    }

    private void moveListCursor(int row) {
        if (headers.isEmpty()) {
            return;
        }
        listCursor = Math.max(0, Math.min(row, headers.size() - 1));
        if (tabListPanel != null) {
            tabListPanel.reveal(listCursor);
        }
        repaintTabList();
    }

    /**
     * Chooses a tab from the list: it is selected, and the list goes. A keyboard that was in the
     * pane goes to the chosen tab, as from a header the arrows reached: the page it may have been
     * typing in is hidden now, and a focus revoked with it would go to nothing. One that was
     * elsewhere stays there, since the list's button never takes it.
     */
    private void chooseFromList(int row) {
        if (!isEnabled()) {
            // The one choke point every choice goes through: a key, a click on a row and a
            // reader's press. The list is not the pane's child, so the scene's gate on a disabled
            // pane never reaches it, and a reader, which loses the rows' verbs, would disagree.
            return;
        }
        closeTabList(Change.Origin.USER);
        if (row >= 0 && row < headers.size()) {
            selectTab(row, listHadKeyboard ? Focus.HEADER : Focus.NONE, Change.Origin.USER);
        }
    }

    /**
     * The list's keys, from its layer in the scene or routed from the owner while it is in a
     * window of its own: the arrows, Home and End move the cursor, Enter and Space choose, Delete
     * and the command key's W close the tab under the cursor, Escape and Tab close the list.
     */
    private void tabListKey(KeyEvent event) {
        if (!event.isPressed()) {
            return;
        }
        int mods = event.modifiers() & (Keys.MOD_SHIFT | Keys.MOD_CONTROL | Keys.MOD_ALT | Keys.MOD_SUPER);
        switch (event.key()) {
            case Keys.UP -> moveListCursor(listCursor - 1);
            case Keys.DOWN -> moveListCursor(listCursor + 1);
            case Keys.HOME, Keys.PAGE_UP -> moveListCursor(0);
            case Keys.END, Keys.PAGE_DOWN -> moveListCursor(headers.size() - 1);
            case Keys.ENTER, Keys.SPACE -> chooseFromList(listCursor);
            case Keys.ESCAPE, Keys.TAB -> closeTabList(Change.Origin.USER);
            case Keys.DELETE -> closeFromList(listCursor);
            case Keys.W -> {
                if (mods == Accelerator.commandModifier()) {
                    closeFromList(listCursor);
                }
            }
            default -> {
                return;
            }
        }
        event.consume();
    }

    /** A letter moves the cursor to the next tab whose caption starts with it. */
    private void tabListCharacter(limn.scene.event.CharEvent event) {
        String typed = new String(Character.toChars(event.codepoint())).toLowerCase(java.util.Locale.ROOT);
        int n = headers.size();
        for (int step = 1; step <= n; step++) {
            int row = (listCursor + step) % n;
            if (headers.get(row).title.get().toLowerCase(java.util.Locale.ROOT).startsWith(typed)) {
                moveListCursor(row);
                event.consume();
                return;
            }
        }
    }

    /**
     * Closes a tab from the list, as its own button would: the handler is asked, the list stays.
     * Nothing while the pane is disabled, for the reason a choice is refused then.
     */
    private void closeFromList(int row) {
        if (isEnabled() && row >= 0 && row < headers.size() && headers.get(row).closable) {
            requestClose(row, Change.Origin.USER);
        }
    }

    /** What the pane answers for its list of tabs. */
    private final class TabListHost implements AnchoredPopup.Host {
        @Override
        public Widget<?> anchor() {
            return listButton;
        }

        @Override
        public Widget<?> opener() {
            return listButton;
        }

        /** As wide as the longest caption needs, as tall as the rows, never under two of them. */
        @Override
        public AnchoredPopup.Placement place(SizeTokens t, float spaceAbove, float spaceBelow) {
            float contentH = listContentHeight(t);
            float minH = Math.min(contentH, 2 * t.popupItemHeight() + 2 * t.popupPadV());
            boolean above = contentH > spaceBelow && spaceAbove > spaceBelow;
            float h = Math.max(minH, Math.min(contentH, above ? spaceAbove : spaceBelow));
            return new AnchoredPopup.Placement(listContentWidth(t), h, above);
        }

        @Override
        public Widget<?> createPanel(boolean inWindow) {
            tabListPanel = new TabListPanel(inWindow);
            return tabListPanel;
        }

        @Override
        public void dismiss(Change.Origin origin) {
            closeTabList(origin);
        }

        /** The list button's own press toggles the list, so it is no press elsewhere. */
        @Override
        public boolean contains(@Nullable Widget<?> target) {
            return target == listButton;
        }

        @Override
        public void key(KeyEvent event) {
            tabListKey(event);
        }

        @Override
        public void character(limn.scene.event.CharEvent event) {
            tabListCharacter(event);
        }

        @Override
        public void describeLayer(Accessibility a) {
            a.role(Accessible.Role.GROUP);
            a.name(ComponentStrings.TAB_LIST_ALL, Accessible.NameFrom.CONTENT);
        }

        /** Opened from a button that never holds the keyboard, so the keys are routed to it. */
        @Override
        public boolean routesKeyboard() {
            return true;
        }

        /** The button is at the strip's end, so the list lines up with the end and opens inward. */
        @Override
        public boolean alignsTrailing() {
            return true;
        }

        /**
         * Where the keyboard was is where it goes back to, unless that went with a tab closed from
         * the list (the focused tab itself, or a field in its page): then it goes to the tab now
         * shown, where it would have gone had the tab closed from the strip, and not to nothing.
         */
        @Override
        public @Nullable Widget<?> refocus() {
            Widget<?> was = listFocusWas;
            if (!listHadKeyboard || selected < 0 || (was != null && was.isShowing())) {
                return null;
            }
            return headers.get(selected);
        }
    }

    private float listContentHeight(SizeTokens t) {
        return headers.size() * t.popupItemHeight() + 2 * t.popupPadV();
    }

    /** The mark column, the longest caption, and the close button's room, inside the row insets. */
    private float listContentWidth(SizeTokens t) {
        float widest = 0;
        for (TabHeader header : headers) {
            widest = Math.max(widest, textRuler().measure(header.title.get(), t.body()).width());
        }
        float w = 2 * t.popupRowInsetX() + t.popupMarkerCol() + listIconRoom(t) + widest
                + t.tabIconGap() + t.tabIconSize() + listClosePad(t) + t.popupRowInsetX();
        return Math.min(w, 480);
    }

    /**
     * The room the list keeps for the tabs' icons, before the captions: an icon and its gap when
     * any tab has one, so the captions line up whether or not their own tab has an icon, and
     * nothing when none has.
     */
    private float listIconRoom(SizeTokens t) {
        for (TabHeader header : headers) {
            if (header.icon != null) {
                return t.tabIconSize() + t.tabIconGap();
            }
        }
        return 0;
    }

    /**
     * How far the close button of a row of the list stands in from the row's highlight: as far as
     * it does from the highlight's top and bottom, so it sits in the band and not against its edge.
     */
    private static float listClosePad(SizeTokens t) {
        return Math.max(0, (t.popupItemHeight() - t.tabIconSize()) / 2 - Strokes.ROW_GUTTER);
    }

    /**
     * The list of every tab: a row each, the selected one marked, a close button at the end of a
     * closable tab's row under the pointer or the keyboard's cursor. It stays open as tabs close.
     */
    private final class TabListPanel extends Widget<TabListPanel> {

        private final boolean inWindow;
        private float scroll;
        private int hoverRow = -1;
        private boolean closeHot;

        TabListPanel(boolean inWindow) {
            this.inWindow = inWindow;
            setCursor(Cursor.POINTER);
        }

        private SizeTokens tokens() {
            return Theme.of(this).tokensFor(TabbedPane.this);
        }

        private boolean castsShadow() {
            return !inWindow || tabList.margin() > 0;
        }

        @Override
        protected float paintOutset() {
            return castsShadow() ? Theme.of(this).elevation(Elevation.OVERLAY).reach() : 0;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = tokens();
            return constraints.constrain(listContentWidth(t), listContentHeight(t));
        }

        private float rowTop(int row, SizeTokens t) {
            return t.popupPadV() + row * t.popupItemHeight() - scroll;
        }

        private int rowAt(float y, SizeTokens t) {
            int row = (int) Math.floor((y + scroll - t.popupPadV()) / t.popupItemHeight());
            return row >= 0 && row < headers.size() ? row : -1;
        }

        private float maxScroll(SizeTokens t) {
            return Math.max(0, listContentHeight(t) - height());
        }

        void reveal(int row) {
            SizeTokens t = tokens();
            float top = t.popupPadV() + row * t.popupItemHeight();
            float bottom = top + t.popupItemHeight();
            if (top - t.popupPadV() < scroll) {
                scroll = Math.max(0, top - t.popupPadV());
            } else if (bottom + t.popupPadV() > scroll + height()) {
                scroll = Math.min(maxScroll(t), bottom + t.popupPadV() - height());
            }
        }

        /** The close button's square in a row, at the end reading goes to. */
        private float closeLeft(SizeTokens t) {
            float side = t.tabIconSize();
            float edge = t.popupRowInsetX() + listClosePad(t);
            return isRightToLeft() ? edge : width() - edge - side;
        }

        private boolean overClose(float x, float y, int row, SizeTokens t) {
            if (row < 0 || !headers.get(row).closable) {
                return false;
            }
            float side = t.tabIconSize();
            float left = closeLeft(t);
            float top = rowTop(row, t) + (t.popupItemHeight() - side) / 2;
            float slop = Strokes.FOCUS_GAP_TAB;
            return x >= left - slop && x < left + side + slop && y >= top - slop && y < top + side + slop;
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            SizeTokens t = tokens();
            // No fade here: in the scene the list's own opacity fades it as one picture, and a
            // window of its own fades through its compositor opacity.
            if (castsShadow()) {
                theme.drawElevation(canvas, limn.graphics.RoundRect.of(0, 0, width(), height(),
                        t.radiusLarge()), Elevation.OVERLAY);
            }
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), t.radiusLarge(),
                    !inWindow ? theme.surfaceRaised() : theme.surfaceRaised().withAlpha(0.94f),
                    Strokes.BORDER, theme.outline());
            boolean rtl = isRightToLeft();
            float inset = t.popupRowInsetX();
            float itemH = t.popupItemHeight();
            Font font = t.body();
            TextMetrics metrics = textRuler().measure("Hg", font);
            float side = t.tabIconSize();
            float markX = rtl ? width() - inset - t.popupMarkerCol() / 2 : inset + t.popupMarkerCol() / 2;
            float iconRoom = listIconRoom(t);
            float iconStart = rtl ? width() - inset - t.popupMarkerCol() - side : inset + t.popupMarkerCol();
            float textStart = rtl ? width() - inset - t.popupMarkerCol() - iconRoom
                    : inset + t.popupMarkerCol() + iconRoom;
            float closeRoom = t.tabIconGap() + side + listClosePad(t) + inset;
            canvas.save();
            canvas.clipRect(Strokes.ROW_CLIP, Strokes.ROW_CLIP,
                    width() - 2 * Strokes.ROW_CLIP, height() - 2 * Strokes.ROW_CLIP);
            for (int i = 0; i < headers.size(); i++) {
                float top = rowTop(i, t);
                if (top + itemH < 0 || top > height()) {
                    continue;
                }
                TabHeader header = headers.get(i);
                boolean active = i == listCursor || i == hoverRow;
                if (active) {
                    canvas.fillRoundRect(inset, top + Strokes.ROW_GUTTER, width() - 2 * inset,
                            itemH - 2 * Strokes.ROW_GUTTER, t.radiusSmall(),
                            theme.primary().withAlpha(0.28f));
                }
                if (i == selected) {
                    canvas.fillCircle(markX, top + itemH / 2, t.popupDotRadius(), theme.primary());
                }
                Color ink = isEnabled() ? theme.text() : theme.disabledText();
                if (header.icon != null) {
                    // The tab's own icon, as its header draws it, so a row is found by the
                    // picture the strip shows as well as by its words.
                    header.icon.paint(canvas, iconStart, top + (itemH - side) / 2, side, ink,
                            theme.isDark(), rtl && header.iconMirroring == Icon.Mirroring.IN_RTL);
                }
                String title = header.title.get();
                ShapedText line = textRuler().shape(title, font,
                        ShapedText.Direction.of(title, neutralBase()));
                float room = Math.max(0, width() - inset - t.popupMarkerCol() - iconRoom - closeRoom);
                if (line.metrics().width() > room) {
                    line = textRuler().ellipsize(line, room);
                }
                canvas.drawText(line, rtl ? textStart - line.metrics().width() : textStart,
                        top + (itemH - metrics.height()) / 2 + metrics.ascent(), ink);
                if (header.closable && active) {
                    float left = closeLeft(t);
                    float y = top + (itemH - side) / 2;
                    boolean hot = closeHot && i == hoverRow;
                    canvas.fillRoundRect(left, y, side, side, side / 2,
                            theme.textMuted().withAlpha(hot ? 0.28f : 0.12f));
                    float cx = left + side / 2;
                    float cy = y + side / 2;
                    float arm = side * 0.18f;
                    Color cross = hot ? theme.text() : theme.textMuted();
                    canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, Strokes.ARROW_PEN, cross);
                    canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, Strokes.ARROW_PEN, cross);
                }
            }
            canvas.restore();
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            SizeTokens t = tokens();
            Vec2 local = sceneToLocal(event.x(), event.y());
            float x = local.x();
            float y = local.y();
            int row = rowAt(y, t);
            switch (event.type()) {
                case ENTER, MOVE -> {
                    boolean hot = overClose(x, y, row, t);
                    if (row != hoverRow || hot != closeHot) {
                        hoverRow = row;
                        closeHot = hot;
                        repaintTabList();
                    }
                }
                case EXIT -> {
                    hoverRow = -1;
                    closeHot = false;
                    repaintTabList();
                }
                case PRESS -> event.consume();
                case CLICK -> {
                    event.consume();
                    if (row < 0) {
                        return;
                    }
                    if (event.button() == Keys.MOUSE_MIDDLE) {
                        closeFromList(row);
                    } else if (event.button() == Keys.MOUSE_LEFT) {
                        if (overClose(x, y, row, t)) {
                            listCursor = row;
                            closeFromList(row);
                        } else {
                            chooseFromList(row);
                        }
                    }
                }
                case WHEEL -> {
                    float was = scroll;
                    scroll = Math.max(0, Math.min(maxScroll(t), scroll - event.scrollY() * Strokes.WHEEL_STEP));
                    if (scroll != was) {
                        event.consume();
                        repaintTabList();
                    }
                }
                default -> {
                }
            }
        }

        /** Keys reaching the list's own window, which a click in it can make the key window. */
        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (inWindow) {
                tabListKey(event);
            }
        }

        @Override
        protected void onCharTyped(limn.scene.event.CharEvent event) {
            if (inWindow) {
                tabListCharacter(event);
            }
        }

        /**
         * A list of the tabs, each an option a reader selects and presses, the selected one
         * marked and the cursor's active; a closable tab's option holds a button that closes it,
         * named as the strip's own is.
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            SizeTokens t = tokens();
            float itemH = t.popupItemHeight();
            float inset = t.popupRowInsetX();
            a.role(Accessible.Role.LIST);
            // Named as its button is, so a reader says what list it entered.
            a.name(ComponentStrings.TAB_LIST_ALL, Accessible.NameFrom.CONTENT);
            RowsAccessibility.describeContainer(a, RowsAccessibility.Selection.SINGLE, true, false);
            float contentH = listContentHeight(t);
            a.scrollFrom(0, 0, 1, 0, scroll, Math.max(0, contentH - height()), height(), contentH);
            boolean operable = tabList.isOpen() && TabbedPane.this.isEnabled();
            float side = t.tabIconSize();
            for (int i = 0; i < headers.size(); i++) {
                TabHeader header = headers.get(i);
                float top = rowTop(i, t);
                a.child(header.key);
                a.bounds(inset, top, width() - 2 * inset, itemH);
                a.role(Accessible.Role.LIST_ITEM);
                a.name(header.title, Accessible.NameFrom.CONTENT);
                // The selection follows the cursor, as in a combo box's list and a menu: marked on
                // the tab the pane shows, every row walked was read "not selected" (Orca, heard
                // 2026-10-16). That tab is said as the row's description instead.
                RowsAccessibility.describeRow(a, RowsAccessibility.Offer.OWNED,
                        RowsAccessibility.Selection.SINGLE, i == listCursor, i + 1, headers.size(),
                        false, false, i == listCursor, operable, false, false);
                if (i == selected) {
                    a.description(ComponentStrings.TAB_LIST_CURRENT);
                }
                if (header.closable) {
                    a.child(header.key | CLOSE_KEY_BIT);
                    a.bounds(closeLeft(t), top + (itemH - side) / 2, side, side);
                    a.role(Accessible.Role.BUTTON);
                    a.name(ComponentStrings.TAB_CLOSE, Accessible.NameFrom.CONTENT);
                    if (operable) {
                        a.action(Accessible.Action.PRESS);
                    }
                    a.endChild();
                }
                a.endChild();
            }
        }

        @Override
        protected boolean onSyntheticAction(long key, Accessible.Action action, Accessible.Argument arg) {
            if (!tabList.isOpen()) {
                return false;
            }
            for (int i = 0; i < headers.size(); i++) {
                TabHeader header = headers.get(i);
                if (key == (header.key | CLOSE_KEY_BIT)) {
                    if (action != Accessible.Action.PRESS || !header.closable) {
                        return false;
                    }
                    listCursor = i;
                    closeFromList(i);
                    return true;
                }
                if (key == header.key) {
                    int row = i;
                    return switch (action) {
                        case SELECT -> {
                            moveListCursor(row);
                            yield true;
                        }
                        case PRESS -> {
                            chooseFromList(row);
                            yield true;
                        }
                        default -> false;
                    };
                }
            }
            return false;
        }
    }

    /** The bit that tells a close button's key from its row's, both made of the header's key. */
    private static final long CLOSE_KEY_BIT = 1L << 40;

    // ------------------------------------------------------------- layout

    /**
     * The strip's extent: {@code lineHeight + 2 * tabPadV}, the composition of two independent
     * ramps and therefore fractional at all five steps, so do not round it. Everything on the
     * strip is measured against this number: the headers, the three square overflow controls,
     * the content's top edge and the separator.
     *
     * <p>Takes the row instead of resolving one, because {@link TabHeader#onMeasure} derives the
     * <em>same</em> number from the <em>same</em> two tokens: a second resolve that disagreed
     * would lay the headers out at one step and paint them at another, and the strip would clip
     * its own tabs.
     *
     * <p>No {@link Strokes#MIN_HIT_TARGET} clamp is needed on the square controls: the ramp pays
     * the floor by construction, {@code tabPadV} being 6 at XSMALL rather than 4 exactly so that
     * {@code 12.89 + 12 = 24.89} clears 24 at the densest step.
     */
    private float stripHeight(SizeTokens t) {
        return textRuler().measure("Hg", t.body()).lineHeight() + 2 * t.tabPadV();
    }

    /** Entry form for the paths that hold no row yet (the strip and its buttons). */
    private float stripHeight() {
        return stripHeight(Theme.of(this).tokensFor(this));
    }


    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.of(this).tokensFor(this);
        float stripH = stripHeight(t);
        boolean boundedW = constraints.hasBoundedWidth();
        boolean boundedH = constraints.hasBoundedHeight();
        float width = boundedW ? constraints.maxWidth() : preferredStripWidth(stripH);
        float height = boundedH ? constraints.maxHeight() : stripH;
        if ((!boundedW || !boundedH) && selected >= 0 && selected < contents.size()) {
            // An unbounded axis (a scrollable parent): consult the selected
            // content's natural size: the strip alone would make onLayout
            // hand the content zero height (or clip its width).
            Size content = contents.get(selected).measure(Constraints.loose(
                    boundedW ? width : Constraints.UNBOUNDED_LIMIT,
                    Constraints.UNBOUNDED_LIMIT));
            if (!boundedW) {
                width = Math.max(width, content.width());
            }
            if (!boundedH) {
                height = stripH + content.height();
            }
        }
        return constraints.constrain(width, height);
    }

    private float preferredStripWidth(float stripH) {
        float total = 0;
        for (TabHeader header : headers) {
            total += header.measure(Constraints.loose(10_000, stripH)).width();
        }
        return total;
    }

    @Override
    protected void onLayout() {
        SizeTokens t = Theme.of(this).tokensFor(this);
        float stripH = stripHeight(t);
        int n = headers.size();
        headerWidths = new float[n];
        headersTotal = 0;
        for (int i = 0; i < n; i++) {
            headerWidths[i] = headers.get(i).measure(Constraints.loose(10_000, stripH)).width();
            headers.get(i).measured = headerWidths[i];
            headersTotal += headerWidths[i];
        }
        overflowing = headersTotal > width() + 0.5f;
        if (!overflowing && tabList.isOpen()) {
            // The tabs fit again (tabs closed, or the pane widened): the list's button is about to
            // be hidden, and the list goes with it rather than hang from a button that is not
            // there. Deferred, so a layout pass never closes a popup over the tree it is placing.
            Ui.post(() -> {
                if (!overflowing) {
                    closeTabList(Change.Origin.ADJUSTMENT);
                }
            });
        }

        // With overflow the strip shrinks to a viewport between the controls: ‹ on the edge
        // reading starts from, › and the tab-list chevron on the edge it ends on.
        //
        // The three squares cost 3 * stripHeight, which is 75pt at XSMALL but 157pt at XLARGE,
        // so on a narrow pane the viewport is what pays. Below 4 * stripHeight of pane width the
        // two chevrons are dropped and only the tab-list chevron survives, keeping the viewport
        // at stripHeight or more. Without it an XLARGE pane under ~157pt reaches
        // viewWidth == 0 while overflowing stays true: every header unreachable, and maxOffset
        // computed against a zero-width viewport. The wheel, the keyboard and the tab-list popup
        // still reach every tab in compact mode. The threshold is measured against width(), not
        // against viewWidth, so it cannot oscillate with its own outcome.
        float viewLeft = 0;
        float viewWidth = width();
        // Resolved once for the whole pass: the three controls and the viewport between them are
        // one strip of items, and two resolutions that disagreed inside one layout would put a
        // chevron on top of the tabs it is meant to sit beside.
        boolean rtl = isRightToLeft();
        if (overflowing) {
            // Shrink the controls, never drop them. Dropping two of the three below a width
            // threshold made the viewport NON-MONOTONE in the pane's width: at MEDIUM a 137pt
            // pane got a 102pt viewport and a 138pt pane got 34pt, because full mode costs
            // 2 * stripH more than compact mode at EVERY width, so no threshold can remove the
            // discontinuity. Capping each square control at width/6 keeps
            // viewWidth = width - 3*button >= width/2 at every width: monotone, never zero, and
            // all three affordances stay reachable.
            float button = Math.min(stripH, width() / 6f);
            viewWidth = Math.max(0, width() - 3 * button);
            // PREV sits on the edge reading starts from and the NEXT/LIST pair on the other, LIST
            // outermost. Each of the four boxes is the left-to-right one reflected about the
            // pane's centre, which is what keeps that pair's order and leaves the viewport's
            // width, the overflow arithmetic and the dead-side tests below untouched.
            viewLeft = rtl ? 2 * button : button;
            prevButton.layoutBox(rtl ? width() - button : 0, 0, button, stripH);
            nextButton.layoutBox(rtl ? button : width() - 2 * button, 0, button, stripH);
            listButton.layoutBox(rtl ? 0 : width() - button, 0, button, stripH);
        }
        float maxOffset = Math.max(0, headersTotal - viewWidth);
        if (revealPending >= 0 && revealPending < n) {
            float start = 0;
            for (int i = 0; i < revealPending; i++) {
                start += headerWidths[i];
            }
            float end = start + headerWidths[revealPending];
            float margin = Math.min(t.tabRevealMargin(),
                    Math.max(0, (viewWidth - headerWidths[revealPending]) / 2));
            if (start - margin < scrollOffset) {
                scrollOffset = start - margin;
            } else if (end + margin > scrollOffset + viewWidth) {
                scrollOffset = end + margin - viewWidth;
            }
            revealPending = -1;
        }
        scrollOffset = overflowing ? Math.min(Math.max(0, scrollOffset), maxOffset) : 0;

        // The buttons appear only while overflowing; a dead direction disables
        // its chevron (guarded setters, safe inside layout).
        prevButton.setVisible(overflowing);
        nextButton.setVisible(overflowing);
        // The list's button stays visible while its list is open. Not to keep the list: once the
        // tabs fit, the close posted from onLayout takes it on the next turn. To keep that close
        // out of this pass: a popup hung from a button hidden here would close at once, inside
        // the layout placing the tree, which that posted close exists to avoid. It goes with the
        // layout after the list closes; meanwhile it keeps the box it last had.
        listButton.setVisible(overflowing || tabList.isOpen());
        prevButton.setEnabled(scrollOffset > 0.5f);
        nextButton.setEnabled(scrollOffset < maxOffset - 0.5f);

        // Measured before it is placed, so the strip has a size the scene can compare: a tab
        // moving reorders its children in a contained layout, which a widget never measured
        // cannot have, and the scene would lay the whole window out for it instead.
        strip.measure(Constraints.tight(viewWidth, stripH));
        strip.layoutBox(viewLeft, 0, viewWidth, stripH); // places the headers inside

        if (selected >= 0) {
            contents.get(selected).layoutBox(0, stripH, width(), Math.max(0, height() - stripH));
            // Point the indicator at the selected header (strip-local coords). Snap on
            // the first placement and when the same tab merely moved (scroll/resize);
            // animate the slide only on an actual tab change.
            TabHeader header = headers.get(selected);
            // A symmetric inset inside the header's own box, so it is invariant about that box's
            // centre and follows the header wherever the strip put it: nothing here chooses a side.
            // From the header's place in the run and not from where it is drawn, which a slide or
            // a drag may have it away from for a moment.
            float left = header.slotX + t.tabPadH() / 2;
            float right = header.slotX + header.width() - t.tabPadH() / 2;
            // The STRIP's direction, because these are the strip's coordinates: it is the widget
            // that placed the header this pair is measured from.
            boolean stripRtl = strip.isRightToLeft();
            // The two transitions hold physical edges across frames, and (tab, direction) is the
            // whole of their key. A direction change relocates every header at once, so it snaps
            // like a scroll does; animating it would slide the indicator across the entire
            // reflected strip on its way to a tab that never moved relative to its neighbours.
            if (indicatorPlaced && indicatorTab == header && stripRtl == indicatorRtl) {
                // The same tab. Snap only if its header actually MOVED: a layout that finds it
                // where it was -- any other widget in the window asking for a pass while the
                // indicator is still sliding -- must leave the slide alone, or it cuts it short.
                // Re-targeting was not idempotent, and a second layout read as "nothing changed,
                // so snap". ADR 043 §9.4.4.
                if (left != indicatorTargetLeft || right != indicatorTargetRight) {
                    indicatorLeft.snap(left);
                    indicatorRight.snap(right);
                }
            } else if (indicatorPlaced && stripRtl == indicatorRtl) {
                indicatorLeft.to(left);
                indicatorRight.to(right);
            } else {
                indicatorLeft.snap(left);
                indicatorRight.snap(right);
                indicatorPlaced = true;
            }
            indicatorTab = header;
            indicatorRtl = stripRtl;
            indicatorTargetLeft = left;
            indicatorTargetRight = right;
        }
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.of(this);
        // Separator under the strip. A hairline is a weight: 1pt at XSMALL and at XLARGE, so
        // the strip and the controls above it keep one border read across a mixed-step window.
        float y = stripHeight(theme.tokensFor(this));
        canvas.drawLine(0, y, width(), y, Strokes.HAIRLINE, theme.outline());
    }

    /**
     * Keeps a panel inside the pane.
     *
     * <p>{@link #onLayout} hands the selected panel a box, and a box is all it is: nothing stops a
     * child from measuring taller than it and painting past the bottom edge. A panel that does
     * (anything not wrapped in a {@code ScrollView}, which is most content once a window is made
     * short enough) then draws over whatever the pane is sitting on, and it looks like the
     * container has no bounds at all rather than like the content is too big.
     *
     * <p>Clipping is the honest answer, and it is not a substitute for scrolling: it makes
     * overflow read as content that continues past an edge, which is what it is. Content that
     * should be reachable still needs a scroll view around it, and the demo's panels have one.
     */
    @Override
    protected boolean clipsChildren() {
        return true;
    }

    @Override
    protected void paintChildren(Canvas canvas) {
        canvas.save();
        try {
            canvas.clipRect(0, 0, width(), height());
            super.paintChildren(canvas);
        } finally {
            canvas.restore();
        }
    }

    /**
     * The line under the strip is a rule, and a rule is material rather than meaning.
     *
     * <p>This pane is no node: it declares no role, no name, no verb and no state of its own, it
     * is never focusable of its own accord, and it declares no children beyond the widgets it
     * holds, so the accessible tree deletes it as scaffolding and hoists the strip, the three
     * overflow controls and every panel into its place. That is the right shape, and it is what
     * {@link #onAccessibilityChild} is written against.
     *
     * <p>What is not free is the log. The tree warns once per class about a widget that paints its
     * own content and says nothing about itself, because the usual cause is a gauge nobody named;
     * it can see that this class overrides {@link #onPaint} and it cannot see what the drawing is.
     * The advice that warning gives is {@code setAccessibleIgnored(true)}, which here would take
     * the tab list, the chevrons and every panel out of the tree — so the warning would name a
     * toolkit class in an application's log and recommend deleting the interface. The separator is
     * a hairline between the strip and the panel below it, and there is nothing in it for a reader
     * to lose, so this is the honest answer and the deletion happens in silence.
     *
     * @return {@code true}; the only thing this widget draws is that line
     */
    @Override
    protected boolean paintsDecoration() {
        return true;
    }

    // -------------------------------------------------------- accessibility

    /**
     * Says of a panel what only this pane knows: that it is the one a tab selects, which tab
     * names it, and that the two are linked.
     *
     * <p>A panel cannot say any of that itself. It is whatever an application handed
     * {@link #addTab} — a column, a scroll view, a label — and it knows nothing about tabs; the
     * pairing between a panel and a header exists only here. The header cannot say it either: its
     * own parent is the strip. So this is the hook, and the pane being deleted does not stop it,
     * because the slot open while it runs is the <em>child's</em> node and not this pane's. The
     * pane declares nothing here, so it stays scaffolding.
     *
     * <p><b>The role and the name are conditional, and that is the whole of the correctness.</b>
     * This hook runs after the child's own describe hook and before an application's overrides. A
     * panel that already said what it is keeps it: one wrapped in a scroll view is a scroll pane,
     * and eliding that role would take the scrolling with it. A panel that already has a name of
     * its own keeps that too. Only a panel that said nothing takes the tab's caption, and an
     * application's {@link #setAccessibleName} still wins over both, because the overrides run
     * last.
     *
     * <p>The link is unconditional, because it is true whatever the panel turned out to be. Its
     * mirror is on the tab, which says which panel it controls.
     *
     * <p>Only a panel is touched. The strip and the three overflow controls are children of this
     * pane as well and arrive here too; none of them is a tab's panel, and this hook leaves them
     * alone. Nothing is derived either: the caption is the string the header already holds, handed
     * over by reference, so a frame damaged by the indicator's slide walks this hook without
     * allocating.
     *
     * <p>Geometry needs nothing. The selected panel is laid out under the strip at the pane's full
     * width, and an unselected one is never laid out at all — but it is also not visible, so it
     * and everything inside it publish without VISIBLE and without SHOWING, which is what keeps a
     * hidden tab's controls from announcing as focusable and makes its stale box harmless.
     *
     * @param child the child being described; a panel only when it is one of the pages
     * @param a     the node being described, which is the child's and never this pane's
     */
    @Override
    protected void onAccessibilityChild(Widget<?> child, Accessibility a) {
        // By identity and not by List#indexOf, which asks equals(): a panel is an application's
        // widget, and two that answered equal to each other would name one of them after the
        // other one's tab.
        int index = -1;
        for (int i = 0; i < contents.size(); i++) {
            if (contents.get(i) == child) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return; // the strip, the two chevrons and the all-tabs button
        }
        TabHeader header = headers.get(index);
        if (!a.hasRole()) {
            a.role(Accessible.Role.TAB_PANEL);
        }
        if (!a.hasName()) {
            a.name(header.title, Accessible.NameFrom.LABEL);
        }
        a.relation(Accessible.Relation.LABELLED_BY, header);
    }

    /**
     * The hover pill's radius, concentric with the focus ring drawn one step further out.
     * Both used {@code radiusSmall} before: a mismatch that is invisible at a 4pt radius and
     * grows with the ramp. The rule is {@code inner = outer - (innerInset - outerInset)} with
     * the focus ring, at the locked {@link Strokes#FOCUS_GAP_TAB}, as the outer curve.
     *
     * <p><b>It reduces to {@code radiusSmall}.</b> The concentricity rule carries no
     * information here, because {@code radiusSmall} and {@code tabHoverInset} are the same ramp
     * (3/3/4/5/6), so the expression collapses to the constant {@link Strokes#FOCUS_GAP_TAB}
     * and would ship a non-monotone 2/2/4/2/2, so the concentric form is deliberately not
     * used here.
     */
    private static float hoverPillRadius(SizeTokens t) {
        // Just radiusSmall. The concentricity rule 'inner = outer - (innerInset - outerInset)'
        // carries no information here, because radiusSmall (3/3/4/5/6) and tabHoverInset
        // (3/3/4/5/6) are THE SAME RAMP: the expression reduces algebraically to
        // Strokes.FOCUS_GAP_TAB, a constant 2, and with MEDIUM pinned it would ship the
        // non-monotone 2/2/4/2/2: a 52pt XLARGE tab with a 2pt pill beside a 34pt MEDIUM tab
        // with a 4pt one, so the concentric form is deliberately not used here.
        return t.radiusSmall();
    }

    /**
     * The clipped, scrollable viewport that owns the tab headers. Clipping the
     * paint AND the hit-testing (its own bounds) is what keeps half-visible
     * headers tidy and unreachable outside the viewport.
     */
    private final class TabStrip extends Widget<TabStrip> implements Scrollable {

        /** A header joins the strip; the pane owns the strip's children. */
        void addHeader(Widget<?> header) {
            add(header);
        }

        void removeHeader(Widget<?> header) {
            remove(header);
        }

        /** Keeps the strip's children in the tab order, which is the order a reader walks. */
        void moveHeader(Widget<?> header, int index) {
            moveChild(header, index);
        }


        /** Scrolls the minimum so the rect (in strip coordinates) becomes visible. */
        @Override
        public void revealRect(float x, float y, float rectWidth, float rectHeight) {
            if (!overflowing) {
                return;
            }
            float dx = 0;
            if (x < 0) {
                dx = x;
            } else if (x + rectWidth > width()) {
                // Oversize rects align their near edge.
                dx = Math.min(x, x + rectWidth - width());
            }
            // The rect arrives in this widget's own PHYSICAL coordinates, so nothing above knows a
            // direction. What the direction decides is which way an offset moves the run: right to
            // left the headers sit further right as the offset grows, so the same physical
            // displacement is the opposite change of offset. One sign, and the arithmetic above is
            // untouched.
            scrollStripBy(isRightToLeft() ? -dx : dx);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            // Sized by the pane via layoutBox; report the natural strip size. Deliberately the
            // PANE's resolve (stripHeight() reads tokensFor(TabbedPane.this)) rather than this
            // widget's: one number for the container and its viewport, always.
            float width = constraints.hasBoundedWidth() ? constraints.maxWidth() : headersTotal;
            return constraints.constrain(width, stripHeight());
        }

        @Override
        protected void onLayout() {
            placeHeaders();
        }

        /**
         * Places the headers at their places in the run, each drawn where its slide or the
         * pointer's drag holds it: from the layout, and from the slide ticker and a drag outside
         * any layout, repainting the strip alone.
         */
        void placeHeaders() {
            // The pane measured the headers; place them with the scroll offset
            // (overflow) or the configured alignment (everything fits).
            //
            // Resolved once for the whole run: two resolutions that disagreed inside one layout
            // would place a header against one edge and its neighbour against the other.
            boolean rtl = isRightToLeft();
            float cursor;
            if (overflowing) {
                cursor = -scrollOffset;
            } else {
                cursor = switch (alignment) {
                    case LEFT -> 0;
                    case CENTER -> Math.max(0, (width() - headersTotal) / 2);
                    case RIGHT -> Math.max(0, width() - headersTotal);
                };
            }
            boolean reduced = Motion.reduced();
            for (int i = 0; i < headers.size(); i++) {
                // The cursor walk is untouched and only the final coordinate is reflected, the
                // same treatment a horizontal Flex gives its children: the first tab lands on the
                // edge reading starts from and the run advances away from it, which is what makes
                // the reveal arithmetic, the indicator and the pointer hit test correct for free
                // rather than each needing a direction of its own. CENTER falls out unchanged,
                // because reflecting a centred run about the same centre returns it.
                TabHeader header = headers.get(i);
                float w = header.measured;
                float slot = rtl ? width() - cursor - w : cursor;
                header.slotX = slot;
                if (header.slidePending) {
                    // Moved since it was drawn: it slides from there to its new place.
                    header.slide = reduced ? 0 : header.slideFrom - slot;
                    header.slidePending = false;
                }
                float x = header == dragged ? dragLeft : slot + header.slide;
                header.layoutBox(x, 0, w, height());
                cursor += w;
            }
        }

        /** The run's two physical ends, for a drag held inside it. */
        float runStart() {
            float least = Float.MAX_VALUE;
            for (TabHeader header : headers) {
                least = Math.min(least, header.slotX);
            }
            return headers.isEmpty() ? 0 : least;
        }

        float runEnd() {
            float most = -Float.MAX_VALUE;
            for (TabHeader header : headers) {
                most = Math.max(most, header.slotX + header.measured);
            }
            return headers.isEmpty() ? 0 : most;
        }

        @Override
        protected boolean clipsChildren() {
            return true; // partial rendering: off-ribbon damage clamps to the strip
        }

        @Override
        protected void paintChildren(Canvas canvas) {
            // In a finally, for the same reason the pane's own paintChildren is: a header can
            // carry an application's icon, and a throw in it must not leave the clip pushed.
            canvas.save();
            try {
                canvas.clipRect(0, 0, width(), height());
                // The dragged tab last, so it passes over the others rather than under them.
                for (Widget<?> child : children()) {
                    if (child != lifted) {
                        paintChild(canvas, child);
                    }
                }
                if (lifted != null && lifted.parent() == this) {
                    paintChild(canvas, lifted);
                }
            } finally {
                canvas.restore();
            }
        }

        private void paintChild(Canvas canvas, Widget<?> child) {
            canvas.save();
            try {
                canvas.translate(child.x(), child.y());
                child.paintWidget(canvas);
            } finally {
                canvas.restore();
            }
        }

        @Override
        protected void onPaintOverlay(Canvas canvas) {
            // The sliding selected-tab indicator, clipped with the headers.
            if (selected < 0 || headers.isEmpty()) {
                return;
            }
            canvas.save();
            canvas.clipRect(0, 0, width(), height());
            // Locked, not tabled: 2.5pt reads correctly over the whole 2.1x strip range, and
            // bottom-anchoring it (y = height - thickness) keeps it inside the box for free:
            // no paintOutset question, and the parity rule does not apply to it.
            float y = height() - Strokes.TAB_INDICATOR;
            float left = indicatorLeft.value();
            float indicatorWidth = Math.max(0, indicatorRight.value() - left);
            TabHeader chosen = headers.get(selected);
            if (chosen == dragged || chosen.slide != 0) {
                // The selected tab is away from its place, under the pointer or sliding home, and
                // its rule goes with it.
                float pad = Theme.of(this).tokensFor(TabbedPane.this).tabPadH() / 2;
                left = chosen.x() + pad;
                indicatorWidth = Math.max(0, chosen.width() - 2 * pad);
            }
            canvas.fillRect(left, y, indicatorWidth, Strokes.TAB_INDICATOR, Theme.of(this).primary());
            canvas.restore();
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() != MouseEvent.Type.WHEEL || !overflowing) {
                return;
            }
            float delta = event.scrollX() != 0 ? event.scrollX() : event.scrollY();
            if (delta == 0) {
                return;
            }
            // A wheel detent is a device unit: the same flick travels the same distance in a
            // dense strip and a roomy one, so this is locked rather than tabled. Consumed only
            // when the strip moved: at its first tab or its last the detent goes on outward.
            float was = scrollOffset;
            scrollStripBy(-delta * Strokes.WHEEL_STEP);
            if (scrollOffset != was) {
                event.consume();
            }
        }

        // --------------------------------------------------------- accessibility

        /**
         * What the strip is to an assistive technology: the tab list the headers are tabs of, one
         * horizontal run of them, holding the selection they are members of and saying how much of
         * itself is on screen.
         *
         * <p><b>The node exists so that the tabs have a container</b>, and that is the whole of
         * why. Without a role the strip declares nothing, is never focusable and — because the
         * sliding indicator is painted as an overlay rather than as content — would be deleted as
         * scaffolding <em>without even the warning</em> a widget that paints normally gets; the
         * headers would then hoist into whatever sits above the pane. A tab list is the container
         * every platform needs above a tab for a position in a set to mean anything; the selection
         * change a tab raises is addressed to that tab's published parent, so with no node here it
         * would arrive on the window; and a reader looking for the strip's own extent under
         * overflow would find the pane's.
         *
         * <p>The role is unconditional, an empty pane included. A pane with no tabs is legal and
         * an empty tab list is honest, while a role that arrived with the first tab would re-shape
         * the tree on a structural change that already re-shapes it.
         *
         * <p>No name and no description: the strip holds no string of its own and there is nothing
         * here for it to derive one from. It is not focusable either, so it is not one of the
         * nodes a name is required of.
         *
         * <p>The orientation is the one state declared. The class is a single horizontal run at
         * every width and in both directions — mirroring reflects the run and does not turn its
         * axis — and it is what tells a reader that Left and Right are the keys that traverse
         * this. Nothing else: enabled, visible, showing, focusable and focused are the walk's, and
         * selected and expanded belong to facets.
         *
         * <p>The selection facet carries the container's own shape and nothing about which tab is
         * selected, which is each header's to say. Single-selection, because the pane keeps one
         * index and swaps it; required only while there are tabs, because an empty pane genuinely
         * has no selection and there is no route back to none once it has one. The active
         * descendant is not declared here and could not be — the publish step resolves it from the
         * first node in this subtree that says it is active — and nothing in the strip marks one:
         * only the selected header is a tab stop, so whenever the strip holds the keyboard that
         * header is already the focused node, and the bit would additionally hand any enclosing
         * container one of these tabs as its own cursor.
         *
         * <p>The scroll facet is published whether or not anything overflows, so that resizing the
         * pane past the fitting point moves two booleans rather than making a whole facet appear
         * and disappear. It is derived from {@code overflowing}, the strip's own predicate, and
         * not from the arithmetic alone: that predicate carries the half-point slop below which
         * the widget refuses to scroll at all, and a strip within half a point of fitting must not
         * advertise a movement every one of its own paths declines to make. The percent is the
         * offset over the maximum offset, and the offset is a distance from the <b>leading</b>
         * edge, so it is zero on the first tab and one on the last in both layout directions and
         * is published unflipped by decision: mirroring lives in where the headers are placed and
         * never in the offset, and flipping it would tell a reader that a right-to-left strip
         * resting on its first tab is scrolled to the end. The vertical pair is the axis's
         * nothing-to-scroll answer, because the run has no second axis.
         *
         * <p>No verb, and no action hook. The strip is not focusable, so the walk grants it
         * neither of the two free ones; the vocabulary has no scroll verb to offer; and the two
         * chevrons and the all-tabs button are operable controls of their own. Every number here
         * is a field this widget or its pane already holds, so describing it builds no string and
         * allocates nothing, and a frame damaged by the indicator's slide compares equal and
         * republishes nothing.
         *
         * @param a the node being described
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.TAB_LIST);
            a.state(Accessible.State.HORIZONTAL);
            // The container half of the ROWS shape (ADR 045 §3), with no PRESS of its own.
            // selected >= 0 is exactly "there is at least one tab": the first addTab moves it off
            // -1 and nothing moves it back.
            RowsAccessibility.describeContainer(a, RowsAccessibility.Selection.SINGLE,
                    selected >= 0, false);
            // The strip's own box IS the viewport, whether it is the whole strip row or the gap
            // the pane left between the three overflow controls, and the publish step reads these
            // after layout, so they are this frame's.
            float viewport = width();
            float maxOffset = Math.max(0, headersTotal - viewport);
            boolean scrollable = overflowing && maxOffset > 0;
            a.scrollFrom(scrollOffset, scrollable ? maxOffset : 0, viewport,
                    scrollable ? headersTotal : 0, 0, 0, 1, 0);
        }
    }

    /** A square strip control: scroll chevron (‹ ›) or the all-tabs list (⌄). */
    private final class StripButton extends Widget<StripButton> {

        enum Kind { PREV, NEXT, LIST }

        private final Kind kind;
        private final Transition hover =
                new Transition(this).duration(Theme.of(this).animHover).easing(Theme.of(this).animEasing);

        StripButton(Kind kind) {
            this.kind = kind;
            setCursor(Cursor.POINTER);
            // The one string that is both the hover tooltip and, through the walk's free default,
            // the accessible name: a control that paints a chevron and no text has nothing else to
            // be called, and no application can reach a private inner class to name it. Safe here
            // for the reason setCursor above is safe -- the same UI-thread check, and the
            // invalidation it ends with does nothing while this widget has no scene.
            setTooltip(switch (kind) {
                case PREV -> ComponentStrings.TAB_PREVIOUS;
                case NEXT -> ComponentStrings.TAB_NEXT;
                case LIST -> ComponentStrings.TAB_LIST_ALL;
            });
        }


        @Override
        protected Size onMeasure(Constraints constraints) {
            // The PANE's strip height, the same expression onLayout sizes these controls with:
            // two independent resolutions here puts the chevron
            // the user aims at somewhere other than where the click lands.
            float side = stripHeight();
            return constraints.constrain(side, side);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            SizeTokens t = theme.tokensFor(this);
            float h = hover.value();
            if (isEnabled() && h > 0.01f) {
                float inset = t.stripBtnHoverInset();
                canvas.fillRoundRect(inset, inset, width() - 2 * inset, height() - 2 * inset,
                        t.radiusSmall(), theme.surfaceRaised().withAlpha(0.6f * h));
            }
            Color ink = isEnabled() ? theme.textMuted().lerp(theme.text(), h) : theme.disabledText();
            // Chevrons drawn with lines (no glyph-coverage risk). The glyph's half-size grows
            // with the step; the pen that draws it does not; it is floored at its MEDIUM
            // extent for the two dense steps so pen/extent stays legible there.
            float cx = width() / 2;
            float cy = height() / 2;
            float s = t.tabChevron();
            float pen = Strokes.ARROW_PEN;
            // Directional ink the toolkit draws itself, which is the only kind it may turn around:
            // these two name the start and the end of the tab order, and the start is on the right
            // in a right-to-left strip. A sign on the x offsets about the button's own centre,
            // which does not move; the LIST chevron points down and is not a site.
            float toEnd = isRightToLeft() ? -1 : 1;
            switch (kind) {
                case PREV -> {
                    canvas.drawLine(cx + toEnd * s / 2, cy - s, cx - toEnd * s / 2, cy, pen, ink);
                    canvas.drawLine(cx - toEnd * s / 2, cy, cx + toEnd * s / 2, cy + s, pen, ink);
                }
                case NEXT -> {
                    canvas.drawLine(cx - toEnd * s / 2, cy - s, cx + toEnd * s / 2, cy, pen, ink);
                    canvas.drawLine(cx + toEnd * s / 2, cy, cx - toEnd * s / 2, cy + s, pen, ink);
                }
                case LIST -> {
                    canvas.drawLine(cx - s, cy - s / 2, cx, cy + s / 2, pen, ink);
                    canvas.drawLine(cx, cy + s / 2, cx + s, cy - s / 2, pen, ink);
                }
            }
        }

        /**
         * One button, whichever of the three this is, named by the tooltip it now holds and
         * offering the press its own click already performs.
         *
         * <p><b>Why it is a node at all</b>, which is the only hard question here and has three
         * answers, each sufficient on its own. It paints: two lines and a hover pill, drawn from
         * {@link #onPaint}, so the transparency predicate would delete a control that declared
         * nothing and warn about it once per class — naming a toolkit class in an application's
         * log and recommending the one flag that hides an operable control, which is the case the
         * pane itself answers with {@code paintsDecoration} and this one cannot, because a chevron
         * is not a rule. It is operable: a left click reaches {@code activate()}, and an operation
         * is never deleted with the box that carried it. And the all-tabs button is where the
         * list it opens points back to: the popup names its opener through the inheritance host,
         * and the walk writes that relation onto the first node it published at or above the host,
         * so with this widget deleted the list would announce as the popup of whatever container
         * the deleted pane hoisted into.
         *
         * <p>No name is declared. The walk takes the tooltip when nothing else named the node,
         * which is what the constructor's string is for, so declaring it again here would leave
         * the walk to add the same string a second time as the description. No description
         * either, for the same reason: the tooltip is spent on the name.
         *
         * <p>Only the all-tabs button says it has a popup, and it says whether the list is open,
         * with the expanding or collapsing the state allows beside its press: the list is the
         * pane's own surface, so the pane can answer whether it is still up, as a
         * combo box answers for its list. A reader also meets the list's own node pointing back
         * at this one.
         *
         * <p>Nothing is conditional on overflow, though all three controls are laid out and made
         * visible only while the strip overflows. A pane that fits publishes three buttons that
         * are neither visible nor showing — free, from the inherited bits — and a stale box, which
         * is harmless for the same reason an unselected panel's is. Making the role and the verb
         * appear with the overflow instead would raise a structure change and three destroyed
         * nodes every time a resize crossed the fitting point, for a change that is not
         * structural.
         *
         * <p>Nor is the box: the walk's free rectangle is the one the pane laid out, which is a
         * square of {@code min(stripHeight, width/6)} and <em>not</em> the {@code stripHeight}
         * square {@link #onMeasure} answers — the two disagree on every pane narrower than six
         * strip heights, which is exactly the pane these controls were shrunk for. The pane
         * mirrors those boxes itself, so nothing here reads a layout direction.
         *
         * <p>No relation, and no state beyond the popup bit. The opener pair is the walk's, written
         * from the host link the popup already sets, and declaring the mirror here would put two
         * of the same relation on this node.
         *
         * @param a the node being described
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.BUTTON);
            if (kind == Kind.LIST) {
                // The opener of the list of every tab: a popup, open or not, and the one of
                // expanding and collapsing it the state allows, beside the press its click does.
                PopupOwnerAccessibility.describe(a, tabList.isOpen());
            }
            a.action(Accessible.Action.PRESS);
        }

        /**
         * Runs the click this control already has, through the same private path the left button
         * reaches, so that a reader's press scrolls the strip by the same three quarters of a
         * viewport, or opens the same list anchored on this button.
         *
         * <p>Expanding and collapsing are the all-tabs button's alone and are refused on the two
         * chevrons, which is what the two are doing in one hook: only one of the three has a
         * list, and a platform that routes such an action to a chevron has asked for something
         * that control does not have.
         *
         * <p>There is no enabled guard, and none is needed. The scene walks this widget and every
         * ancestor for the enabled flag and refuses an owner that is not showing before the hook
         * is called, which is the sibling tab header's reason unchanged; a chevron at the end of
         * its travel is published disabled by the layout that put it there, and the gate then
         * refuses its press. Below even that, scrolling declines a strip that does not overflow
         * and a clamp that yields no movement, and the tab list declines a pane with no scene and
         * no tabs, so a press that somehow arrived late is inert rather than wrong.
         *
         * @param action what was asked
         * @param arg    ignored; neither verb carries one
         * @return whether it ran
         */
        @Override
        protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
            switch (action) {
                case PRESS -> {
                    activate();
                    return true;
                }
                case EXPAND, COLLAPSE -> {
                    if (kind != Kind.LIST) {
                        return false;
                    }
                    return PopupOwnerAccessibility.perform(new PopupOwnerAccessibility.Host() {
                        @Override
                        public boolean isOpen() {
                            return tabList.isOpen();
                        }

                        @Override
                        public void open() {
                            openTabList();
                        }

                        @Override
                        public void close() {
                            closeTabList(Change.Origin.USER);
                        }
                    }, action);
                }
                default -> {
                    return false;
                }
            }
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            switch (event.type()) {
                case ENTER -> hover.to(1);
                case EXIT -> hover.to(0);
                case CLICK -> {
                    if (event.button() == Keys.MOUSE_LEFT) {
                        event.consume();
                        activate();
                    }
                }
                case PRESS -> event.consume();
                default -> {
                }
            }
        }

        private void activate() {
            switch (kind) {
                case PREV -> scrollStripBy(-strip.width() * SCROLL_STEP_FRACTION);
                case NEXT -> scrollStripBy(strip.width() * SCROLL_STEP_FRACTION);
                case LIST -> openTabList();
            }
        }
    }

    /** One clickable/focusable tab in the strip. */
    private final class TabHeader extends Widget<TabHeader> {
        private final I18nString title;
        private final Icon icon;
        private final Icon.Mirroring iconMirroring;
        /** Its place in the tab order, kept by the pane as tabs close and move. */
        int index;
        /** What a reader knows its row in the list of every tab by, whatever its place. */
        final long key = ++headerKeys;
        /** Whether the user can close it. */
        boolean closable;
        /** Its width as the pane measured it, and its place in the run, in strip coordinates. */
        float measured;
        float slotX;
        /** How far from its place it is drawn while it slides home, and where a move found it. */
        float slide;
        float slideFrom;
        boolean slidePending;
        /** The pointer: over the tab, over its close button, and a press that may become a drag. */
        private boolean pointerIn;
        private boolean closeHot;
        private boolean pressArmed;
        private boolean dragging;
        private boolean movedByDrag;
        private float pressX;
        private float grab;
        private int dragFrom;
        private final Transition hover =
                new Transition(this).duration(Theme.of(this).animHover).easing(Theme.of(this).animEasing);
        private final Transition focusFade =
                new Transition(this).duration(Theme.of(this).animFocus).easing(Theme.of(this).animEasing);

        /** Moves focus here with the origin of the gesture the pane is handling. */
        void focusFrom(Change.Origin origin) {
            requestFocus(origin);
        }

        TabHeader(I18nString title, Icon icon, Icon.Mirroring iconMirroring, int index) {
            // A press on the close button closes the tab and leaves the keyboard where it was,
            // so a close refused (an unsaved document kept) finds it still in the document.
            limn.scene.internal.SceneAccess.pressFocus(this,
                    (x, y) -> !overCloseAtScene(x, y));
            this.title = title;
            this.icon = icon;
            this.iconMirroring = iconMirroring;
            this.index = index;
            // Focusable only while selected (roving focus), managed by the pane. Nothing
            // directional is read here: a header is constructed before it has a parent, so a
            // direction captured now would resolve to the process default and stay wrong.
            setCursor(Cursor.POINTER);
        }


        /**
         * Horizontal room the icon claims, its gap to the label included: one expression so
         * the measure and the paint sides cannot drift apart and mis-centre the content.
         *
         * <p>{@code tabIconSize} is the former {@code measure("Hg", body).height()}:
         * integral at every step after D6 (MEDIUM was 16.40625), so the icon
         * rasterization cache keeps a bounded key count.
         */
        private float iconAdvance(SizeTokens t) {
            return icon == null ? 0 : t.tabIconSize() + t.tabIconGap();
        }

        /**
         * The room a closable tab keeps at its end for the close button, whether or not it shows:
         * a tab that grew as the pointer came would move every tab after it.
         */
        private float closeAdvance(SizeTokens t) {
            // The button sits half a padding from the tab's end and not a whole one, so the room it
            // takes is its gap and its square less the half padding it gives back.
            return closable ? t.tabIconGap() + t.tabIconSize() - closeEdge(t) : 0;
        }

        /** How far the close button stands from the tab's end: half its side padding. */
        private float closeEdge(SizeTokens t) {
            return t.tabPadH() / 2;
        }

        /** The close button's square, in this header's coordinates: at the end reading goes to. */
        private float closeLeft(SizeTokens t) {
            return isRightToLeft() ? closeEdge(t) : width() - closeEdge(t) - t.tabIconSize();
        }

        private boolean overClose(float x, float y) {
            if (!closable) {
                return false;
            }
            SizeTokens t = Theme.of(this).tokensFor(this);
            float side = t.tabIconSize();
            float left = closeLeft(t);
            float top = (height() - side) / 2;
            float slop = Strokes.FOCUS_GAP_TAB;
            return x >= left - slop && x < left + side + slop && y >= top - slop && y < top + side + slop;
        }

        /** Whether a pointer event, which carries scene coordinates, is over the close button. */
        private boolean overCloseAt(MouseEvent event) {
            return overCloseAtScene(event.x(), event.y());
        }

        /** Whether a scene point is over the close button. */
        private boolean overCloseAtScene(float sceneX, float sceneY) {
            Vec2 local = sceneToLocal(sceneX, sceneY);
            return overClose(local.x(), local.y());
        }

        /**
         * Where a pointer event is along the strip, in the strip's coordinates: from the strip's
         * own place in the scene, which a drag does not move, and never from this header's, which
         * it does.
         */
        private float stripX(MouseEvent event) {
            return strip.sceneToLocal(event.x(), event.y()).x();
        }

        private boolean closeShown() {
            return closable && (index == selected || pointerIn);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = Theme.of(this).tokensFor(this);
            TextMetrics metrics = textRuler().measure(title.get(), t.body());
            // The height must be numerically identical to TabbedPane.stripHeight(): same two
            // tokens, same expression. Anything else and the strip clips its own headers.
            return constraints.constrain(metrics.width() + iconAdvance(t) + closeAdvance(t)
                    + 2 * t.tabPadH(), metrics.lineHeight() + 2 * t.tabPadV());
        }

        /** The baseline BASELINE rows align on, the very expression {@link #onPaint} draws with. */
        @Override
        protected float baselineOffset() {
            if (title.get().isEmpty()) {
                return super.baselineOffset(); // no text: align on the bottom edge
            }
            TextMetrics metrics = textRuler().measure(title.get(), Theme.of(this).tokensFor(this).body());
            return (height() - metrics.height()) / 2 + metrics.ascent();
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            SizeTokens t = theme.tokensFor(this);
            boolean isSelected = index == selected;
            float h = hover.value();
            Font font = t.body();
            Color ink = !isEnabled() ? theme.disabledText()
                    : isSelected ? theme.text()
                    : theme.textMuted().lerp(theme.text(), h);

            if (lifted == this) {
                // Lifted out of the row: a background of its own, so the tabs it passes over do
                // not show through it.
                canvas.fillRoundRect(0, 0, width(), height(), t.radiusSmall(), theme.surfaceRaised());
            } else if (!isSelected && h > 0.01f) {
                // tabHoverInset > FOCUS_GAP_TAB must hold at every step, or the hover pill
                // swallows the focus ring drawn inside it.
                float inset = t.tabHoverInset();
                canvas.fillRoundRect(inset, inset, width() - 2 * inset, height() - 2 * inset,
                        hoverPillRadius(t), theme.surfaceRaised().withAlpha(0.6f * h));
            }
            TextMetrics metrics = textRuler().measure(title.get(), font);
            float advance = iconAdvance(t);
            float contentW = advance + metrics.width();
            // The icon+label block is centred, so the block's own left edge is a centre and does
            // not move. What mirrors is the order of the two things inside it: the icon takes the
            // slot reading starts from, which is the block's right end in a right-to-left tab.
            boolean rtl = isRightToLeft();
            // Centred in what the close button leaves, which is at the end reading goes to.
            float closeRoom = closeAdvance(t);
            float blockLeft = (rtl ? closeRoom : 0) + (width() - closeRoom - contentW) / 2;
            float textLeft = rtl ? blockLeft : blockLeft + advance;
            if (icon != null) {
                float is = t.tabIconSize();
                float iconLeft = rtl ? blockLeft + metrics.width() + t.tabIconGap() : blockLeft;
                // Where the icon sits is this tab's decision; whether the drawing inside it turns
                // around is the caller's, declared when the tab was added.
                icon.paint(canvas, iconLeft, (height() - is) / 2, is, ink, theme.isDark(),
                        rtl && iconMirroring == Icon.Mirroring.IN_RTL);
            }
            // Shaped here rather than left to the canvas, so this tab's own direction is the
            // neutral fallback instead of a hard-coded one. drawText places the LEFT edge of the
            // run under either base, which is why textLeft is the block's left edge either way.
            ShapedText line = textRuler().shape(title.get(), font,
                    ShapedText.Direction.of(title.get(), neutralBase()));
            canvas.drawText(line, textLeft,
                    (height() - metrics.height()) / 2 + metrics.ascent(), ink);

            if (closeShown()) {
                float side = t.tabIconSize();
                float left = closeLeft(t);
                float top = (height() - side) / 2;
                // A round button whenever it shows, deeper under the pointer: a cross alone read
                // as a mark beside the caption rather than as something to press.
                Color disc = isEnabled() && closeHot ? theme.textMuted().withAlpha(0.28f)
                        : theme.textMuted().withAlpha(0.12f);
                canvas.fillRoundRect(left, top, side, side, side / 2, disc);
                // A cross of the chevrons' pen, well inside the disc.
                float cx = left + side / 2;
                float cy = top + side / 2;
                float arm = side * 0.18f;
                Color cross = !isEnabled() ? theme.disabledText()
                        : closeHot ? theme.text() : theme.textMuted();
                canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, Strokes.ARROW_PEN, cross);
                canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, Strokes.ARROW_PEN, cross);
            }

            // The selected indicator is drawn by the strip (it slides across tabs).
            float focus = focusFade.value();
            if (focus > 0.001f) {
                // A rule along the top, the selection's own rule mirrored, in the focus colour: a
                // ring around the whole tab crossed the selection rule and read as a text field.
                // The same inset as the selection's, and the same locked weight, so the two frame
                // the caption alike in a dense strip and a roomy one.
                float inset = t.tabPadH() / 2;
                canvas.fillRect(inset, 0, Math.max(0, width() - 2 * inset), Strokes.TAB_INDICATOR,
                        theme.focusRing().withAlpha(focus));
            }
        }

        // --------------------------------------------------------- accessibility

        /**
         * What one tab is to an assistive technology: a tab node named by the caption it holds,
         * a member of the strip's selection with its one-based position and the number of tabs,
         * offering the two verbs the pointer and the keyboard already have.
         *
         * <p>The role is declared on every header and not only the selected one. Roving focus
         * leaves an unselected header unfocusable, and an unfocusable widget that declares nothing
         * is scaffolding the tree deletes — so without this the whole strip would publish as one
         * nameless node, and the deletion would warn besides, because a header paints.
         *
         * <p>The name is the held caption handed over by reference and never resolved here, the
         * way {@link #onMeasure} and {@link #onPaint} resolve it: a tab is damaged on every frame
         * of its hover and its focus fade, and a string built in this hook would be one allocation
         * per header per damaged frame spent concluding that nothing moved. It is handed over even
         * when the caption is empty, which {@link TabbedPane#addTab} accepts: the walk then names
         * the node from a tooltip, and a header has none, so such a tab publishes an empty name —
         * the application's to fix and not this widget's to invent.
         *
         * <p>The two numbers are the pane's own tab order and tab count, which are also the
         * tree's, because every tab has a header and every header is published. The selected state
         * is derived from the facet and is never written as a state of its own.
         *
         * <p><b>The selected header is deliberately not marked active</b>, though the strip's
         * selection and its keyboard cursor are the same thing. Only the selected header is
         * focusable, so whenever the strip holds the keyboard that header is already the focused
         * node and the bit would say it twice; and it is not free to add, because a container
         * takes the first active node anywhere in its subtree as its active descendant, so a pane
         * nested inside a list cell would hand the list one of these tabs as its own cursor.
         *
         * <p><b>One relation and not two.</b> No membership relation: the strip is this header's
         * accessible parent and the tree says so already. The panel this tab controls is the other
         * one, and it was refused before this pane gave a panel a role of its own — a link to a
         * content widget the tree had deleted resolved to whatever container it hoisted into,
         * which is a confidently wrong relation rather than none. A panel now declares what it is
         * whatever an application handed over, so it is a node and the link lands on it. The tab
         * says what it controls and the panel says what names it, the two ends of one edge.
         *
         * @param a the node being described
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.TAB);
            a.name(title, Accessible.NameFrom.CONTENT);
            // The ROWS shape, written once (ADR 045 §3), read against a header that is a widget
            // of its own: the membership, SELECT and PRESS as its own verbs, no cursor mark
            // because the header holds the keyboard itself (roving focus), and neither FOCUS nor
            // SCROLL_INTO_VIEW, which the walk grants it free as a focusable widget.
            RowsAccessibility.describeRow(a, RowsAccessibility.Offer.OWNED,
                    RowsAccessibility.Selection.SINGLE, index == selected, index + 1,
                    headers.size(), false, false, false, true, false, false);
            a.relation(Accessible.Relation.CONTROLLER_FOR, contents.get(index));
            if (closable) {
                // The close button, published whether or not it is drawn: what a reader finds
                // must not change as a pointer passes over the tabs.
                SizeTokens t = Theme.of(this).tokensFor(this);
                float side = t.tabIconSize();
                a.child(KEY_CLOSE);
                a.bounds(closeLeft(t), (height() - side) / 2, side, side);
                a.role(Accessible.Role.BUTTON);
                a.name(ComponentStrings.TAB_CLOSE, Accessible.NameFrom.CONTENT);
                a.action(Accessible.Action.PRESS);
                a.endChild();
            }
        }

        @Override
        protected boolean onSyntheticAction(long key, Accessible.Action action,
                Accessible.Argument arg) {
            if (key == KEY_CLOSE && action == Accessible.Action.PRESS && closable) {
                requestClose(index, Change.Origin.USER);
                return true;
            }
            return false;
        }

        /**
         * Performs one of the two selections a user can already make, through the pane's own
         * private path, so that a reader's tab change tells the application exactly what a click
         * tells it and scrolls the tab into view exactly as one does.
         *
         * <p>They are two gestures and not one duplicated. A select is what
         * {@link TabbedPane#setSelectedIndex} does: it changes the tab and leaves the keyboard
         * where it was, except that focus follows the selection out of a strip that held it, which
         * is what stops the cursor being stranded on a header that has just stopped being a tab
         * stop. A press is what a left click and Enter do: it changes the tab and then dives into
         * the panel, landing on its first focusable descendant, or back on the header when the
         * panel has none. A reader asking to select a tab is not asking to be moved into it, and a
         * reader pressing one is.
         *
         * <p>Both answer true, including on the tab that is already selected, where the pane's own
         * early return changes nothing and tells nobody but still scrolls the tab back into view:
         * the same answer the pointer gets there. There is no deselect, because a pane that holds
         * tabs always has one selected and there is no route back to none.
         *
         * <p>There is no enabled guard here and none is needed: the scene walks this widget and
         * every parent for the enabled flag before it calls the hook, and refuses anything whose
         * owner is not on screen — which, in an overflowing strip, is every header scrolled out of
         * the viewport. Selecting a visible neighbour scrolls the run, and the strip's list
         * chevron opens the list of every tab; those are the two routes to a tab a reader cannot
         * operate where it stands.
         *
         * @param action what was asked
         * @param arg    ignored; neither verb carries one
         * @return whether it ran
         */
        @Override
        protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
            return RowsAccessibility.performOnRow(rowsHost, index, action);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            switch (event.type()) {
                case ENTER -> {
                    hover.to(1);
                    pointerIn = true;
                    hotClose(overCloseAt(event));
                }
                case MOVE -> hotClose(overCloseAt(event));
                case EXIT -> {
                    hover.to(0);
                    pointerIn = false;
                    hotClose(false);
                }
                case PRESS -> {
                    event.consume();
                    movedByDrag = false;
                    if (event.button() == Keys.MOUSE_LEFT && !overCloseAt(event)) {
                        pressArmed = true;
                        pressX = stripX(event);
                    }
                }
                case DRAG -> {
                    if (pressArmed) {
                        event.consume();
                        dragTo(stripX(event));
                    }
                }
                case RELEASE -> {
                    event.consume();
                    pressArmed = false;
                    if (dragging) {
                        endDrag();
                    }
                }
                case CLICK -> {
                    if (movedByDrag) {
                        return; // the press became a drag: it moved the tab, it chose nothing
                    }
                    if (event.button() == Keys.MOUSE_LEFT) {
                        event.consume();
                        if (overCloseAt(event)) {
                            requestClose(index, Change.Origin.USER);
                        } else {
                            selectTab(index, Focus.CONTENT, Change.Origin.USER);
                        }
                    } else if (event.button() == Keys.MOUSE_MIDDLE && closable) {
                        event.consume();
                        requestClose(index, Change.Origin.USER);
                    }
                }
                default -> {
                }
            }
        }

        private void hotClose(boolean hot) {
            if (hot != closeHot) {
                closeHot = hot;
                invalidate();
            }
            setCursor(Cursor.POINTER);
        }

        /**
         * The press travelled: past a few points it drags the tab, which becomes the selected
         * one, along the bar and never off it, trading places with each neighbour whose middle it
         * passes. Past either end of an overflowing strip, the strip scrolls under it.
         */
        private void dragTo(float stripX) {
            if (!dragging) {
                if (Math.abs(stripX - pressX) < DRAG_SLOP) {
                    return;
                }
                dragging = true;
                movedByDrag = true;
                dragFrom = index;
                grab = pressX - x();
                TabbedPane.this.dragged = this;
                lifted = this;
                if (index != selected) {
                    selectTab(index, Focus.NONE, Change.Origin.USER);
                }
            }
            boolean rtl = strip.isRightToLeft();
            if (overflowing) {
                float beyond = stripX < 0 ? stripX : stripX > strip.width() ? stripX - strip.width() : 0;
                if (beyond != 0) {
                    scrollStripBy(rtl ? -beyond : beyond);
                }
            }
            float left = stripX - grab;
            left = Math.max(strip.runStart(), Math.min(left, strip.runEnd() - width()));
            dragLeft = left;
            float middle = left + width() / 2;
            // Trade places while the middle has passed a neighbour's: the next one in the order
            // lies to the right left to right and to the left right to left.
            //
            // Each trade places the strip again, so the next comparison reads where the neighbours
            // are now and not where they were; and one event trades in one direction only, at most
            // once per tab, so no tab can be traded back and forth inside it.
            int direction = 0;
            for (int trades = 0; trades < headers.size(); trades++) {
                int next = index + 1;
                int previous = index - 1;
                if (direction >= 0 && next < headers.size()
                        && passed(middle, headers.get(next), rtl, true)) {
                    reorder(index, next);
                    direction = 1;
                } else if (direction <= 0 && previous >= 0
                        && passed(middle, headers.get(previous), rtl, false)) {
                    reorder(index, previous);
                    direction = -1;
                } else {
                    break;
                }
                strip.placeHeaders();
            }
            strip.placeHeaders();
            strip.invalidate();
        }

        private boolean passed(float middle, TabHeader neighbour, boolean rtl, boolean onward) {
            float theirs = neighbour.slotX + neighbour.measured / 2;
            boolean rightward = onward != rtl;
            // A middle held inside the bar cannot pass the middle of a narrower tab at its end:
            // the end of the bar counts as passing it, or the last place could not be reached.
            float reach = width() / 2 + 0.5f;
            return rightward ? middle > Math.min(theirs, strip.runEnd() - reach)
                    : middle < Math.max(theirs, strip.runStart() + reach);
        }

        /** Forgets a press and a drag in progress: the tab is leaving the pane. */
        private void dropDrag() {
            pressArmed = false;
            dragging = false;
        }

        /**
         * Another tab closed while this one is dragged: where this one started is counted again
         * among the tabs left, so the move told on release names places in the order that is.
         * The tabs other than this one keep their order through a drag, so the closed one was
         * before the start exactly when it is before it among them.
         *
         * @param closed the closed tab's index, in the order before it went
         */
        private void tabLeftDuringDrag(int closed) {
            int amongOthers = closed < index ? closed : closed - 1;
            if (amongOthers < dragFrom) {
                dragFrom--;
            }
        }

        /** The tab is let go: it slides into its place, and a move is told once, as the user's. */
        private void endDrag() {
            dragging = false;
            TabbedPane.this.dragged = null;
            slideFrom = x();
            slidePending = true;
            startSlides();
            TabbedPane.this.markNeedsContainedLayout();
            if (index != dragFrom) {
                movedFrom = dragFrom;
                movedTo = index;
                TabbedPane.this.moveChild(contents.get(index), FIRST_PAGE + index,
                        Change.Origin.USER);
            } else {
                strip.placeHeaders();
                strip.invalidate();
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (!event.isPressed()) {
                return;
            }
            // Resolved once for this event. The arrows name a side of the screen, so Left has to
            // select the tab that is visually to the left or the keyboard disagrees with the
            // pointer, and right to left that tab is the next one in order rather than the
            // previous one. Home and End are not this: they name the ends of the tab ORDER, and
            // stay the first and the last tab in both directions.
            boolean rtl = isRightToLeft();
            int visualLeft = rtl ? index + 1 : index - 1;
            int visualRight = rtl ? index - 1 : index + 1;
            int mods = event.modifiers()
                    & (Keys.MOD_SHIFT | Keys.MOD_CONTROL | Keys.MOD_ALT | Keys.MOD_SUPER);
            if (event.key() == Keys.DOWN && mods == Keys.MOD_ALT) {
                // The list of every tab, from the keyboard, with the chord that opens a combo's
                // list and a calendar (ADR 068 §4): its button is no tab stop, so the strip stays
                // one. Only while the strip overflows, when the button is there to hang from.
                if (overflowing && !tabList.isOpen()) {
                    event.consume();
                    openTabList();
                }
                return;
            }
            switch (event.key()) {
                case Keys.LEFT -> {
                    event.consume();
                    selectTab(visualLeft, Focus.HEADER, Change.Origin.USER); // keep arrowing across headers
                }
                case Keys.RIGHT -> {
                    event.consume();
                    selectTab(visualRight, Focus.HEADER, Change.Origin.USER);
                }
                case Keys.HOME -> {
                    event.consume();
                    selectTab(0, Focus.HEADER, Change.Origin.USER);
                }
                case Keys.END -> {
                    event.consume();
                    selectTab(headers.size() - 1, Focus.HEADER, Change.Origin.USER);
                }
                case Keys.ENTER, Keys.SPACE -> {
                    event.consume();
                    selectTab(index, Focus.CONTENT, Change.Origin.USER); // dive into the panel
                }
                default -> {
                }
            }
        }

        @Override
        protected void onFocusGained() {
            focusFade.to(1);
        }

        @Override
        protected void onFocusLost() {
            focusFade.to(0);
        }
    }
}
