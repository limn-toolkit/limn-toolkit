package limn.components;

import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.i18n.I18nString;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Scrollable;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

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
 * panel. Focus follows a programmatic selection while the strip holds it.
 *
 * <p><b>Overflow:</b> when the headers don't fit, the strip becomes a clipped,
 * scrollable viewport (alignment is moot then): chevron buttons appear at the
 * edges (the dead side disabled, not hidden; hiding would resize the viewport
 * and oscillate at the ends), the mouse wheel scrolls the strip, and a list
 * button opens a {@link PopupMenu} of all tabs (check on the current one) to
 * jump directly. Selecting a tab (by click, keyboard or
 * {@link #setSelectedIndex}) always scrolls it into view. The three square controls
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
 */
public class TabbedPane extends Widget {

    public enum TabAlignment { LEFT, CENTER, RIGHT }

    /** Fraction of the strip viewport scrolled per chevron click. */
    private static final float SCROLL_STEP_FRACTION = 0.75f;

    /** Where selecting a tab moves keyboard focus. */
    private enum Focus { NONE, HEADER, CONTENT }

    private final List<TabHeader> headers = new ArrayList<>();
    private final List<Widget> contents = new ArrayList<>();
    private final TabStrip strip = new TabStrip();
    private final StripButton prevButton = new StripButton(StripButton.Kind.PREV);
    private final StripButton nextButton = new StripButton(StripButton.Kind.NEXT);
    private final StripButton listButton = new StripButton(StripButton.Kind.LIST);
    private int selected = -1;
    private TabAlignment alignment = TabAlignment.LEFT;
    private Consumer<Integer> onChange = index -> {
    };

    // Overflow state, recomputed each layout. Header widths are measured by the
    // pane and consumed by the strip (which lays the headers out inside itself).
    private boolean overflowing;
    private float scrollOffset;
    private float headersTotal;
    private float[] headerWidths = new float[0];
    private int revealPending = -1; // tab to scroll into view on the next layout

    // The selected-tab indicator slides between tabs by animating its two x edges
    // (strip-local); it snaps when the same tab merely moves (scroll/resize).
    private final Transition indicatorLeft =
            new Transition(this).duration(Theme.current().animTab).easing(Theme.current().animEasing);
    private final Transition indicatorRight =
            new Transition(this).duration(Theme.current().animTab).easing(Theme.current().animEasing);
    private boolean indicatorPlaced;
    private int indicatorTab = -1;

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
    public TabbedPane addTab(String title, Widget content) {
        return addTab(title, null, content);
    }

    /** A tab whose caption follows the UI language; see {@link I18nString}. */
    public TabbedPane addTab(I18nString title, Widget content) {
        return addTab(title, null, content);
    }

    /** Appends a tab with a leading icon, tinted to the tab's text colour. */
    public TabbedPane addTab(String title, Icon icon, Widget content) {
        return addTab(I18nString.literal(Objects.requireNonNull(title, "title")), icon, content);
    }

    /** Appends a tab with an icon and a caption that follows the UI language. */
    public TabbedPane addTab(I18nString title, Icon icon, Widget content) {
        Ui.checkUiThread();
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        int index = contents.size();
        TabHeader header = new TabHeader(title, icon, index);
        headers.add(header);
        contents.add(content);
        strip.add(header);
        add(content);
        if (selected < 0) {
            selected = 0;
        }
        // Roving focus (one tab stop for the whole strip): only the selected
        // header is focusable: Tab/Shift+Tab land on it, arrows move between tabs.
        header.setFocusable(index == selected);
        content.setVisible(index == selected);
        markNeedsLayout();
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
     * Selects a tab, scrolls it into view and fires {@link #onSelect}; code and a click take the
     * same path, so a listener sees every change either way. Re-selecting the current tab still
     * scrolls it back into view (a caller asking for a tab is asking to be shown it) but changes
     * nothing and fires nothing; that early return is what keeps two controls bound to each other
     * from recursing, so do not remove it. UI thread only.
     *
     * @param index a tab in {@code [0, tabCount)}
     * @throws IndexOutOfBoundsException if {@code index} is not a tab; an empty pane has none, so
     *         every index throws there. Arrowing past an end is not this: keyboard traversal
     *         stops at the end rather than raising.
     */
    public TabbedPane setSelectedIndex(int index) {
        Ui.checkUiThread();
        Objects.checkIndex(index, contents.size());
        selectTab(index, Focus.NONE);
        return this;
    }

    /** Called with the new index whenever the selection changes, by click, keyboard or code. */
    public TabbedPane onSelect(Consumer<Integer> listener) {
        Ui.checkUiThread();
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * The pane's own bookkeeping, and deliberately not the public setter's contract: every
     * internal caller passes a <em>computed</em> index ({@code index - 1} from a Left arrow,
     * {@code headers.size() - 1} from End on an empty strip), and arrowing off an end is a key
     * that has nowhere to go, not a programming error. Out of range is a no-op here; it throws
     * only where an application named the index.
     */
    private void selectTab(int index, Focus focus) {
        Ui.checkUiThread();
        if (index < 0 || index >= contents.size()) {
            return; // out of range (e.g. arrowing past an end)
        }
        Focus effective = focus;
        if (index != selected) {
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
            onChange.accept(selected);
            if (effective == Focus.NONE && stripHadFocus) {
                effective = Focus.HEADER; // focus follows the selection out of a focused strip
            }
        }
        revealPending = index; // scroll into view even when re-selecting
        markNeedsLayout();     // onLayout re-targets the indicator + applies the reveal
        applyFocus(index, effective);
    }

    private void applyFocus(int index, Focus focus) {
        switch (focus) {
            case CONTENT -> {
                // Land in the panel: focus its first focusable descendant, or fall
                // back to the header so keyboard tab-navigation still works for
                // panels that have nothing focusable (labels, images).
                Widget target = firstFocusable(contents.get(index));
                (target != null ? target : headers.get(index)).requestFocus();
            }
            case HEADER -> headers.get(index).requestFocus();
            case NONE -> {
            }
        }
    }

    /** Depth-first search for the first focusable, visible, enabled widget in a subtree. */
    private static Widget firstFocusable(Widget widget) {
        if (!widget.isVisible() || !widget.isEnabled()) {
            return null;
        }
        if (widget.isFocusable()) {
            return widget;
        }
        for (Widget child : widget.children()) {
            Widget found = firstFocusable(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ---------------------------------------------------------- overflow

    /** Scrolls the header strip by {@code dx} points (clamped to the content). */
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
        // Shift the headers NOW: revealInView re-reads coordinates between
        // nested scrollables in one pass (the Scrollable contract); deferring
        // to the next layout hands an outer scroller a rect a content-width off.
        for (TabHeader header : headers) {
            header.layoutBox(header.x() - applied, header.y(), header.width(), header.height());
        }
        markNeedsLayout(); // chevron enable state + indicator follow next frame
    }

    /** Opens the all-tabs popup anchored under the list button (check on the current tab). */
    private void openTabList() {
        if (scene() == null || headers.isEmpty()) {
            return;
        }
        Menu menu = new Menu();
        for (int i = 0; i < headers.size(); i++) {
            int index = i;
            menu.addCheck(headers.get(i).title.get(), i == selected,
                    on -> selectTab(index, Focus.NONE));
        }
        // Anchored on listButton, NOT on scene(): the Widget overload hosts the cascade on a
        // widget inside this pane, so the menu resolves the pane's step. Anchoring on the scene
        // routes through scene.root() and opens a MEDIUM menu out of an XSMALL pane.
        new PopupMenu(menu).showAnchored(listButton,
                listButton.localToSceneX(), listButton.localToSceneY(),
                listButton.width(), listButton.height());
    }

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
        return stripHeight(Theme.current().tokensFor(this));
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
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
        SizeTokens t = Theme.current().tokensFor(this);
        float stripH = stripHeight(t);
        int n = headers.size();
        headerWidths = new float[n];
        headersTotal = 0;
        for (int i = 0; i < n; i++) {
            headerWidths[i] = headers.get(i).measure(Constraints.loose(10_000, stripH)).width();
            headersTotal += headerWidths[i];
        }
        overflowing = headersTotal > width() + 0.5f;

        // With overflow the strip shrinks to a viewport between the controls:
        // ‹ at the left edge, › and the tab-list chevron at the right edge.
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
            viewLeft = button;
            prevButton.layoutBox(0, 0, button, stripH);
            nextButton.layoutBox(width() - 2 * button, 0, button, stripH);
            listButton.layoutBox(width() - button, 0, button, stripH);
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
        listButton.setVisible(overflowing);
        prevButton.setEnabled(scrollOffset > 0.5f);
        nextButton.setEnabled(scrollOffset < maxOffset - 0.5f);

        strip.layoutBox(viewLeft, 0, viewWidth, stripH); // places the headers inside

        if (selected >= 0) {
            contents.get(selected).layoutBox(0, stripH, width(), Math.max(0, height() - stripH));
            // Point the indicator at the selected header (strip-local coords). Snap on
            // the first placement and when the same tab merely moved (scroll/resize);
            // animate the slide only on an actual tab change.
            TabHeader header = headers.get(selected);
            float left = header.x() + t.tabPadH() / 2;
            float right = header.x() + header.width() - t.tabPadH() / 2;
            if (indicatorPlaced && indicatorTab == selected) {
                indicatorLeft.snap(left);
                indicatorRight.snap(right);
            } else if (indicatorPlaced) {
                indicatorLeft.to(left);
                indicatorRight.to(right);
            } else {
                indicatorLeft.snap(left);
                indicatorRight.snap(right);
                indicatorPlaced = true;
            }
            indicatorTab = selected;
        }
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        // Separator under the strip. A hairline is a weight: 1pt at XSMALL and at XLARGE, so
        // the strip and the controls above it keep one border read across a mixed-step window.
        float y = stripHeight(theme.tokensFor(this));
        canvas.drawLine(0, y, width(), y, Strokes.HAIRLINE, theme.outline);
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
    private final class TabStrip extends Widget implements Scrollable {

        /** Scrolls the minimum so the rect (in strip coordinates) becomes visible. */
        @Override
        public void revealRect(float x, float y, float rectWidth, float rectHeight) {
            if (!overflowing) {
                return;
            }
            if (x < 0) {
                scrollStripBy(x);
            } else if (x + rectWidth > width()) {
                scrollStripBy(Math.min(x, x + rectWidth - width()));
            }
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
            // The pane measured the headers; place them with the scroll offset
            // (overflow) or the configured alignment (everything fits).
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
            for (int i = 0; i < headers.size(); i++) {
                headers.get(i).layoutBox(cursor, 0, headerWidths[i], height());
                cursor += headerWidths[i];
            }
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
                super.paintChildren(canvas);
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
            canvas.fillRect(left, y, indicatorWidth, Strokes.TAB_INDICATOR, Theme.current().primary);
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
            event.consume();
            // A wheel detent is a device unit: the same flick travels the same distance in a
            // dense strip and a roomy one, so this is locked rather than tabled.
            scrollStripBy(-delta * Strokes.WHEEL_STEP);
        }
    }

    /** A square strip control: scroll chevron (‹ ›) or the all-tabs list (⌄). */
    private final class StripButton extends Widget {

        enum Kind { PREV, NEXT, LIST }

        private final Kind kind;
        private final Transition hover =
                new Transition(this).duration(Theme.current().animHover).easing(Theme.current().animEasing);

        StripButton(Kind kind) {
            this.kind = kind;
            setCursor(Cursor.POINTER);
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
            Theme theme = Theme.current();
            SizeTokens t = theme.tokensFor(this);
            float h = hover.value();
            if (isEnabled() && h > 0.01f) {
                float inset = t.stripBtnHoverInset();
                canvas.fillRoundRect(inset, inset, width() - 2 * inset, height() - 2 * inset,
                        t.radiusSmall(), theme.surfaceRaised.withAlpha(0.6f * h));
            }
            Color ink = isEnabled() ? theme.textMuted.lerp(theme.text, h) : theme.disabledText;
            // Chevrons drawn with lines (no glyph-coverage risk). The glyph's half-size grows
            // with the step; the pen that draws it does not; it is floored at its MEDIUM
            // extent for the two dense steps so pen/extent stays legible there.
            float cx = width() / 2;
            float cy = height() / 2;
            float s = t.tabChevron();
            float pen = Strokes.ARROW_PEN;
            switch (kind) {
                case PREV -> {
                    canvas.drawLine(cx + s / 2, cy - s, cx - s / 2, cy, pen, ink);
                    canvas.drawLine(cx - s / 2, cy, cx + s / 2, cy + s, pen, ink);
                }
                case NEXT -> {
                    canvas.drawLine(cx - s / 2, cy - s, cx + s / 2, cy, pen, ink);
                    canvas.drawLine(cx + s / 2, cy, cx - s / 2, cy + s, pen, ink);
                }
                case LIST -> {
                    canvas.drawLine(cx - s, cy - s / 2, cx, cy + s / 2, pen, ink);
                    canvas.drawLine(cx, cy + s / 2, cx + s, cy - s / 2, pen, ink);
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
    private final class TabHeader extends Widget {
        private final I18nString title;
        private final Icon icon;
        private final int index;
        private final Transition hover =
                new Transition(this).duration(Theme.current().animHover).easing(Theme.current().animEasing);
        private final Transition focusFade =
                new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);

        TabHeader(I18nString title, Icon icon, int index) {
            this.title = title;
            this.icon = icon;
            this.index = index;
            // Focusable only while selected (roving focus), managed by the pane.
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

        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = Theme.current().tokensFor(this);
            TextMetrics metrics = textRuler().measure(title.get(), t.body());
            // The height must be numerically identical to TabbedPane.stripHeight(): same two
            // tokens, same expression. Anything else and the strip clips its own headers.
            return constraints.constrain(metrics.width() + iconAdvance(t) + 2 * t.tabPadH(),
                    metrics.lineHeight() + 2 * t.tabPadV());
        }

        /** The baseline BASELINE rows align on, the very expression {@link #onPaint} draws with. */
        @Override
        protected float baselineOffset() {
            if (title.get().isEmpty()) {
                return super.baselineOffset(); // no text: align on the bottom edge
            }
            TextMetrics metrics = textRuler().measure(title.get(), Theme.current().tokensFor(this).body());
            return (height() - metrics.height()) / 2 + metrics.ascent();
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.current();
            SizeTokens t = theme.tokensFor(this);
            boolean isSelected = index == selected;
            float h = hover.value();
            Font font = t.body();
            Color ink = !isEnabled() ? theme.disabledText
                    : isSelected ? theme.text
                    : theme.textMuted.lerp(theme.text, h);

            if (!isSelected && h > 0.01f) {
                // tabHoverInset > FOCUS_GAP_TAB must hold at every step, or the hover pill
                // swallows the focus ring drawn inside it.
                float inset = t.tabHoverInset();
                canvas.fillRoundRect(inset, inset, width() - 2 * inset, height() - 2 * inset,
                        hoverPillRadius(t), theme.surfaceRaised.withAlpha(0.6f * h));
            }
            TextMetrics metrics = textRuler().measure(title.get(), font);
            float advance = iconAdvance(t);
            float contentW = advance + metrics.width();
            float x = (width() - contentW) / 2;
            if (icon != null) {
                float is = t.tabIconSize();
                icon.paint(canvas, x, (height() - is) / 2, is, ink, theme.dark);
                x += advance;
            }
            canvas.drawText(title.get(), x, (height() - metrics.height()) / 2 + metrics.ascent(), font, ink);

            // The selected indicator is drawn by the strip (it slides across tabs).
            float focus = focusFade.value();
            if (focus > 0.001f) {
                // Both the gap and the weight are absolute: a focus affordance must read the
                // same in a dense strip and a roomy one.
                float gap = Strokes.FOCUS_GAP_TAB;
                canvas.drawRoundRect(gap, gap, width() - 2 * gap, height() - 2 * gap,
                        t.radiusSmall(), Strokes.FOCUS_RING_THIN,
                        theme.focusRing.withAlpha(focus));
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
                        selectTab(index, Focus.CONTENT);
                    }
                }
                case PRESS -> event.consume();
                default -> {
                }
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (!event.isPressed()) {
                return;
            }
            switch (event.key()) {
                case Keys.LEFT -> {
                    event.consume();
                    selectTab(index - 1, Focus.HEADER); // keep arrowing across headers
                }
                case Keys.RIGHT -> {
                    event.consume();
                    selectTab(index + 1, Focus.HEADER);
                }
                case Keys.HOME -> {
                    event.consume();
                    selectTab(0, Focus.HEADER);
                }
                case Keys.END -> {
                    event.consume();
                    selectTab(headers.size() - 1, Focus.HEADER);
                }
                case Keys.ENTER, Keys.SPACE -> {
                    event.consume();
                    selectTab(index, Focus.CONTENT); // dive into the panel
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
