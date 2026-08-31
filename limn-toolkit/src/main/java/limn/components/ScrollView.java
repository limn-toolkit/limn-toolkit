package limn.components;

import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Scrollable;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

/**
 * Generic scroll container: the child is measured unbounded on the scrolling
 * axes, painted clipped to the viewport and offset by the scroll position; the
 * wheel scrolls whenever there is overflow. The child is physically positioned
 * at {@code (-offsetX, -offsetY)}, so hit-testing and event coordinates work
 * unchanged.
 *
 * <p>Scroll indicators are the shared {@link ScrollBar} (draggable, themed,
 * policy-driven), the same component every scroller uses.
 *
 * <p><b>The bars either float over the content or take a gutter of their own</b>
 * ({@link #setBarLayout}). Which one is right is a property of what is being
 * scrolled, not a taste: an image, a video or a page of prose wants every point of
 * width and loses nothing to a bar that fades over it, while a table, a form or a
 * tree wants its last column readable at all times, and a bar sitting on it is a
 * defect however briefly it shows.
 */
public class ScrollView extends Widget implements Scrollable {

    /** Logical points scrolled per wheel notch. */
    private static final float WHEEL_STEP = 48;

    private final Widget child;
    private final boolean horizontal;
    private final boolean vertical;
    private final ScrollBar vBar;
    private final ScrollBar hBar;
    private float offsetX;
    private float offsetY;
    private final ScrollGutters gutters = new ScrollGutters();

    /** Wraps {@code child}, scrolling on both axes as its content overflows. */
    public ScrollView(Widget child) {
        this(child, false, true);
    }

    /** Wraps {@code child}, scrolling only on the axes enabled here. */
    public ScrollView(Widget child, boolean horizontal, boolean vertical) {
        this.child = child;
        this.horizontal = horizontal;
        this.vertical = vertical;
        add(child);
        vBar = vertical ? new ScrollBar(ScrollBar.Orientation.VERTICAL, new ScrollBar.Model() {
            @Override
            public float contentLength() {
                return child.height();
            }

            @Override
            public float viewportLength() {
                return viewportHeight();
            }

            @Override
            public float offset() {
                return offsetY;
            }

            @Override
            public void setOffset(float value) {
                scrollTo(offsetX, value);
            }
        }).setPolicy(ScrollBar.Policy.AUTO) : null;
        if (vBar != null) {
            add(vBar);
        }
        hBar = horizontal ? new ScrollBar(ScrollBar.Orientation.HORIZONTAL, new ScrollBar.Model() {
            @Override
            public float contentLength() {
                return child.width();
            }

            @Override
            public float viewportLength() {
                return viewportWidth();
            }

            @Override
            public float offset() {
                return offsetX;
            }

            @Override
            public void setOffset(float value) {
                scrollTo(value, offsetY);
            }
        }).setPolicy(ScrollBar.Policy.AUTO) : null;
        if (hBar != null) {
            add(hBar);
        }
    }

    /** Sets the scrollbar visibility policy (default {@link ScrollBar.Policy#AUTO}). */
    public ScrollView setScrollbarPolicy(ScrollBar.Policy policy) {
        if (vBar != null) {
            vBar.setPolicy(policy);
        }
        if (hBar != null) {
            hBar.setPolicy(policy);
        }
        return this;
    }

    /**
     * Sets whether the bars float over the content or reserve a strip of their own
     * (default {@link ScrollGutters.Layout#OVERLAY}).
     */
    public ScrollView setBarLayout(ScrollGutters.Layout layout) {
        Ui.checkUiThread();
        gutters.setLayout(layout);
        markNeedsLayout();
        return this;
    }

    /** Whether the scrollbars overlay the content or reserve a gutter. */
    public ScrollGutters.Layout barLayout() {
        return gutters.layout();
    }

    /**
     * The width content is actually shown in: the box, less any reserved gutter.
     * Every offset, clamp and reveal is measured against this rather than against
     * {@link #width()}, or a reserved bar would hide the last column it exists to
     * keep clear.
     */
    public float viewportWidth() {
        return gutters.viewportWidth(width());
    }

    /** Visible height in logical points, excluding anything the content overflows by. */
    public float viewportHeight() {
        return gutters.viewportHeight(height());
    }

    /**
     * Horizontal scroll position in logical points, {@code 0} at the <b>leading</b> edge: the
     * left edge in a left-to-right subtree and the right edge in a right-to-left one. The range
     * is {@code [0, maxOffsetX()]} in both, so "scrolled to the start" is {@code 0} either way.
     */
    public float offsetX() {
        return offsetX;
    }

    /** Vertical scroll position in logical points, {@code 0} at the top. */
    public float offsetY() {
        return offsetY;
    }

    /** Largest valid {@link #offsetX()}; {@code 0} when the content fits. */
    public float maxOffsetX() {
        return Math.max(0, child.width() - viewportWidth());
    }

    /** Largest valid {@link #offsetY()}; {@code 0} when the content fits. */
    public float maxOffsetY() {
        return Math.max(0, child.height() - viewportHeight());
    }

    /** Scrolls by a delta in logical points (clamped). */
    public void scrollBy(float dx, float dy) {
        scrollTo(offsetX + dx, offsetY + dy);
    }

    /** Scrolls the minimum so the rect (in viewport coordinates) becomes visible. */
    @Override
    public void revealRect(float x, float y, float rectWidth, float rectHeight) {
        Ui.checkUiThread();
        float dx = 0;
        float dy = 0;
        if (horizontal) {
            // Against the VIEWPORT's edges and not the box's: under RESERVED they differ by the
            // gutter, and a rect flush with the box would otherwise count as visible while
            // sitting under the bar.
            float left = viewportLeft();
            float right = left + viewportWidth();
            if (x < left) {
                dx = x - left; // off the leading edge: scroll back
            } else if (x + rectWidth > right) {
                // Oversize rects align their near edge.
                dx = Math.min(x - left, x + rectWidth - right);
            }
        }
        if (vertical) {
            if (y < 0) {
                dy = y;
            } else if (y + rectHeight > viewportHeight()) {
                dy = Math.min(y, y + rectHeight - viewportHeight());
            }
        }
        // The rect arrives in this widget's own PHYSICAL coordinates, so nothing above computed
        // dx knows a direction. What the direction decides is which way offsetX moves the
        // content: left to right the content sits at -offsetX and right to left at
        // viewportWidth() - childWidth + offsetX, so the same physical displacement is the
        // opposite change of offset. One sign flip, and the arithmetic above is untouched.
        if (dx != 0 && layoutDirection() == limn.scene.LayoutDirection.RTL) {
            dx = -dx;
        }
        if (dx != 0 || dy != 0) {
            scrollBy(dx, dy);
        }
    }

    /** Scrolls to an absolute offset (clamped to the content). UI thread only. */
    public void scrollTo(float newOffsetX, float newOffsetY) {
        Ui.checkUiThread();
        float clampedX = horizontal ? Math.min(Math.max(0, newOffsetX), maxOffsetX()) : 0;
        float clampedY = vertical ? Math.min(Math.max(0, newOffsetY), maxOffsetY()) : 0;
        if (clampedX == offsetX && clampedY == offsetY) {
            return;
        }
        boolean movedX = clampedX != offsetX;
        boolean movedY = clampedY != offsetY;
        offsetX = clampedX;
        offsetY = clampedY;
        // Fast path: content size unchanged, only its position moves. The origin is recomputed
        // rather than decremented, because it is not the same function of offsetX in the two
        // directions and a delta that assumed one would run the other backwards.
        moveChild(child, contentOriginX(child.width()), -offsetY);
        if (movedY && vBar != null) {
            vBar.onScrolled();
        }
        if (movedX && hBar != null) {
            hBar.onScrolled();
        }
        invalidate();
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        Size content = child.measure(childConstraints(constraints.loosened()));
        float w = constraints.hasBoundedWidth() ? constraints.maxWidth() : content.width();
        float h = constraints.hasBoundedHeight() ? constraints.maxHeight() : content.height();
        return constraints.constrain(w, h);
    }

    private Constraints childConstraints(Constraints loose) {
        return new Constraints(
                0, horizontal ? Constraints.UNBOUNDED_LIMIT : loose.maxWidth(),
                0, vertical ? Constraints.UNBOUNDED_LIMIT : loose.maxHeight());
    }

    @Override
    protected void onLayout() {
        Size content = gutters.resolve(width(), height(), vBar, hBar,
                (viewW, viewH) -> child.measure(childConstraints(Constraints.loose(viewW, viewH))));
        float viewW = viewportWidth();
        float viewH = viewportHeight();
        float childWidth = horizontal ? Math.max(content.width(), viewW) : viewW;
        float childHeight = vertical ? Math.max(content.height(), viewH) : viewH;
        offsetX = horizontal ? Math.min(offsetX, Math.max(0, childWidth - viewW)) : 0;
        offsetY = vertical ? Math.min(offsetY, Math.max(0, childHeight - viewH)) : 0;
        child.layoutBox(contentOriginX(childWidth), -offsetY, childWidth, childHeight);

        float t = ScrollBar.thickness();
        // With both bars, leave a clear square in the corner so their thumbs never
        // overlap and each can travel all the way to its own (shortened) end. Under
        // RESERVED the gutter already is that square, and only when the other axis
        // actually overflows; an unused axis costs nothing there.
        boolean both = vBar != null && hBar != null;
        boolean reserved = gutters.layout() == ScrollGutters.Layout.RESERVED;
        float vLen = reserved ? viewH : (both ? height() - t : height());
        float hLen = reserved ? viewW : (both ? width() - t : width());
        // The vertical bar sits on the side reading ends on, and the horizontal one starts
        // after whatever strip that leaves, so the clear corner square is on the bar's own side
        // in both directions rather than always on the right.
        boolean rtl = layoutDirection() == limn.scene.LayoutDirection.RTL;
        if (vBar != null) {
            vBar.measure(Constraints.tight(t, vLen));
            vBar.layoutBox(rtl ? 0 : width() - t, 0, t, vLen);
            // Told after the geometry it reads from is settled, not before: this is where a bar
            // learns its content has just become scrollable, which is the only moment an AUTO bar
            // has to say so before the pointer arrives.
            vBar.refresh();
        }
        if (hBar != null) {
            hBar.measure(Constraints.tight(hLen, t));
            hBar.layoutBox(rtl ? width() - hLen : 0, height() - t, hLen, t);
            hBar.refresh();
        }
    }

    /**
     * Physical left edge of the viewport.
     *
     * <p>Zero, except reading right to left under {@link ScrollGutters.Layout#RESERVED}: the
     * vertical bar takes its strip from the side reading ends on, which is then the left, and the
     * content starts after it. {@link ScrollGutters} has no direction of its own &mdash; it
     * answers how much a strip takes, not which side takes it &mdash; so the side is resolved
     * here, where the bar is placed.
     */
    private float viewportLeft() {
        return layoutDirection() == limn.scene.LayoutDirection.RTL ? gutters.verticalStrip() : 0;
    }

    /**
     * Where the content's left edge goes for the current {@link #offsetX()}.
     *
     * <p>{@code offsetX == 0} is the <b>leading</b> edge in both directions, so left to right it
     * puts the content's left edge on the viewport's left edge and right to left it puts the
     * content's <em>right</em> edge on the viewport's right edge. Advancing the scroll then
     * reveals content on the trailing side either way, which is what lets every clamp keep its
     * form and {@link #maxOffsetX()} stay a positive magnitude.
     *
     * <p>The <b>viewport</b> width and not {@link #width()}: under
     * {@link ScrollGutters.Layout#RESERVED} the two differ by the gutter, and using the box width
     * would slide every right-to-left layout under the scrollbar.
     */
    private float contentOriginX(float childWidth) {
        return layoutDirection() == limn.scene.LayoutDirection.RTL
                ? viewportLeft() + viewportWidth() - childWidth + offsetX
                : -offsetX;
    }

    /** The vertical bar, or null when this view does not scroll vertically (tests). */
    ScrollBar verticalBar() {
        return vBar;
    }

    @Override
    protected boolean clipsChildren() {
        return true; // partial rendering: scrolled-out damage clamps to the viewport
    }

    @Override
    protected void paintChildren(Canvas canvas) {
        // The content is clipped to the viewport and the bars to the box: under
        // RESERVED those differ by the gutter, and content painting into it would
        // show through around the thumb, the one thing the mode exists to prevent.
        // Both in a finally, for the reason Widget.paintChildren gives: the content below is an
        // arbitrary subtree, a throw in it unwinds through here, and a clip left pushed would end
        // the frame unbalanced, reported against nobody, because whatever threw is long out of
        // the stack by then. paintOne already guards its own; these are the two it does not cover.
        canvas.save();
        try {
            canvas.clipRect(viewportLeft(), 0, viewportWidth(), viewportHeight());
            paintOne(canvas, child);
        } finally {
            canvas.restore();
        }
        canvas.save();
        try {
            canvas.clipRect(0, 0, width(), height());
            if (vBar != null) {
                paintOne(canvas, vBar);
            }
            if (hBar != null) {
                paintOne(canvas, hBar);
            }
        } finally {
            canvas.restore();
        }
    }

    private static void paintOne(Canvas canvas, Widget target) {
        canvas.save();
        try {
            canvas.translate(target.x(), target.y());
            target.paintWidget(canvas);
        } finally {
            canvas.restore();
        }
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        if (event.type() == MouseEvent.Type.MOVE) {
            if (vBar != null) {
                vBar.onHostActivity();
            }
            if (hBar != null) {
                hBar.onHostActivity();
            }
            return;
        }
        if (event.type() != MouseEvent.Type.WHEEL) {
            return;
        }
        // Shift turns a vertical wheel into a horizontal one, which is how a plain mouse (no
        // tilt wheel, no trackpad) scrolls a wide table on Windows and GTK. Without it the only
        // horizontal gesture left to that user is dragging the scrollbar thumb. Taken before the
        // normal mapping so a tilt wheel and Shift cannot both drive the same axis in one event.
        boolean shiftToHorizontal = (event.modifiers() & Keys.MOD_SHIFT) != 0
                && horizontal && maxOffsetX() > 0 && event.scrollY() != 0;
        if (shiftToHorizontal) {
            scrollBy(-event.scrollY() * WHEEL_STEP, 0);
            event.consume();
            return;
        }
        boolean useY = vertical && maxOffsetY() > 0 && event.scrollY() != 0;
        boolean useX = horizontal && maxOffsetX() > 0 && event.scrollX() != 0;
        if (!useX && !useY) {
            return;
        }
        scrollBy(useX ? -event.scrollX() * WHEEL_STEP : 0,
                useY ? -event.scrollY() * WHEEL_STEP : 0);
        event.consume();
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        scrollByKey(event);
    }

    /**
     * Page/Home/End scrolling, for the keys the focused widget did not want.
     *
     * <p>Package-private as well as an override because a {@link Dialog}'s action row sits
     * <b>outside</b> the body's scroll view by design, so a key pressed with a footer button
     * focused bubbles past the body rather than through it. The dialog hands those keys here
     * itself; without that, content a capped card cut off cannot be reached without a mouse.
     *
     * <p>Arrow keys are deliberately not taken. They are the one set a focused widget is most
     * likely to want for something else (a list moves its selection, a slider its value), and a
     * view that swallowed the ones nobody claimed would be guessing. Page and Home/End are what
     * Windows and GTK give a scrolling pane, and nothing else competes for them here.
     *
     * <p>Consumed only when the view actually moved, so a nested view already at its end lets the
     * one outside it take the key.
     */
    void scrollByKey(KeyEvent event) {
        if (!event.isPressed() || event.isConsumed()) {
            return;
        }
        // One axis answers: the vertical one where there is one, because that is the axis Page
        // means. A horizontal-only view takes them rather than leaving them unanswered.
        boolean useY = vertical && maxOffsetY() > 0;
        boolean useX = !useY && horizontal && maxOffsetX() > 0;
        if (!useX && !useY) {
            return;
        }
        float page = useY ? viewportHeight() : viewportWidth();
        float max = useY ? maxOffsetY() : maxOffsetX();
        float before = useY ? offsetY() : offsetX();
        float target = switch (event.key()) {
            case Keys.PAGE_UP -> before - page;
            case Keys.PAGE_DOWN -> before + page;
            case Keys.HOME -> 0;
            case Keys.END -> max;
            default -> before;
        };
        if (target == before) {
            return;
        }
        scrollTo(useX ? target : offsetX(), useY ? target : offsetY());
        if ((useY ? offsetY() : offsetX()) != before) {
            event.consume();
        }
    }
}
